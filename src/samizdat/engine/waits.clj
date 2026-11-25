;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.engine.waits
  "Waits on a background job's output (karamazov-k7gl.1).

  A wait watches one job of a run for a condition: `:output-regex` (a line
  matching `:pattern`) or `:exit`. Either is satisfied by the job ending, so
  a regex wait on a server that dies says so instead of running out its
  clock. It scans from a cursor of its own — the job's end when it was made,
  or the start of what the buffer still holds when it asked for history — so
  it neither moves nor is moved by the `jobs` tool's read cursor.

  States: :pending, then :ready (the condition held; :outcome :matched or
  :exited) and :delivered once whoever waited has been told, or :expired
  (:outcome :deadline, :quiet — no new output for :quiet-ms — or :job-gone)
  or :cancelled. An expired wait is told once too (:told?).

  Two modes. :block is a tool call waiting in its own turn; :watch is
  registered and left, and its wake is delivered later — at the branch's next
  call, or by whatever holds a finishing run open for it. Whether a pending
  watch keeps a run alive is NOT decided here: this reports the waits and
  delivers them, and the beam's hold cell decides.

  Pure where it can be: `make` and `step` are functions of a job snapshot and
  a clock reading. The effects are injected as `env`
  {:snapshot (fn [run-id job-id] -> snapshot or nil) :now (fn [] ms)
   :sleep! (fn [ms]) :record (fn [wait])}, `:record` being called with every
  wait made and every state it moves to — the durable copy, which the tool
  layer journals."
  (:require [clojure.string :as str]))

(defonce ^:private registry
  ;; {:next n :waits {id wait} :recorders {run-id f}}
  (atom {:next 1 :waits {} :recorders {}}))

(defn pattern-error
  "Why `pattern` is not a usable regex, or nil when it is."
  [pattern]
  (cond
    (str/blank? (str pattern)) "empty pattern"
    :else (try (re-pattern (str pattern)) nil
               (catch Throwable e (or (ex-message e) (str e))))))

(defn make
  "A new pending wait, given the job's snapshot `snap` at `now`. `history?`
  starts the cursor at the oldest line the buffer still holds instead of its
  end."
  [{:keys [id run-id branch-id job condition pattern history? timeout-ms quiet-ms mode max-matches]} snap now]
  (let [from (if history? (or (:start snap) 0) (or (:total snap) 0))]
    {:id id :run-id run-id :branch-id branch-id :job job
     :condition (or condition (if pattern :output-regex :exit))
     :pattern (some-> pattern str)
     :mode (or mode :watch)
     :max-matches max-matches
     :from from :scanned from :dropped 0
     :created-ms now :deadline-ms (+ now (long timeout-ms))
     :quiet-ms (when (and quiet-ms (pos? quiet-ms)) (long quiet-ms))
     :state :pending}))

(defn- end [w state outcome now]
  (assoc w :state state :outcome outcome :ended-ms now))

(defn step
  "`w` advanced over the job snapshot `snap` at `now`. A wait that is no
  longer pending is returned as it is; a nil snapshot means the job is gone.
  Only lines past the wait's own cursor are scanned, and lines that left the
  buffer before the scan are counted in :dropped."
  [{:keys [state scanned condition pattern deadline-ms quiet-ms created-ms max-matches] :as w} snap now]
  (cond
    (not= :pending state) w
    (nil? snap) (end w :expired :job-gone now)
    :else
    (let [{:keys [lines start total status exit last-ms]} snap
          start (or start 0)
          total (or total 0)
          from (max scanned start)
          offset (- from start)
          fresh (if (< offset (count lines)) (subvec (vec lines) offset) [])
          re (when (= :output-regex condition) (re-pattern pattern))
          hits (when re
                 (into []
                       (cond-> (comp (map-indexed (fn [i l] (assoc l :index (+ from i))))
                                     (filter #(re-find re (str (:text %)))))
                         ;; gates.edn :job-waits :max-matches: how many
                         ;; matching lines of one scan a wait keeps.
                         max-matches (comp (take max-matches)))
                       fresh))
          w (assoc w :scanned (max scanned total)
                   :dropped (+ (or (:dropped w) 0) (max 0 (- start scanned)))
                   :job-status status :exit exit)]
      (cond
        (seq hits) (assoc (end w :ready :matched now) :matches hits)
        (not= :running status) (end w :ready :exited now)
        (>= now deadline-ms) (end w :expired :deadline now)
        (and quiet-ms (>= (- now (max created-ms (or last-ms 0))) quiet-ms))
        (end w :expired :quiet now)
        :else w))))

;; --- the registry -------------------------------------------------------------

(defn- id-number [id] (or (some-> (re-find #"\d+$" (str id)) parse-long) 0))

(defn- record! [run-id w]
  (when-let [f (get-in @registry [:recorders run-id])]
    (try (f w) (catch Throwable _ nil))))

(defn wait
  "Wait `id` of run `run-id`, or nil — another run's is nil too."
  [run-id id]
  (let [w (get-in @registry [:waits (str id)])]
    (when (= run-id (:run-id w)) w)))

(defn waits
  "Run `run-id`'s waits, oldest first."
  [run-id]
  (->> (vals (:waits @registry))
       (filter #(= run-id (:run-id %)))
       (sort-by (comp id-number :id))
       vec))

(defn- put! [w]
  (swap! registry assoc-in [:waits (:id w)] w)
  w)

(defn- advance!
  "Step wait `w` against its job now, store it, and record it when its state
  moved."
  [{:keys [snapshot now]} w]
  (let [w' (step w (snapshot (:run-id w) (:job w)) (now))]
    (put! w')
    (when (not= (:state w) (:state w')) (record! (:run-id w) w'))
    w'))

(defn register!
  "Make a wait on job `:job` of run `:run-id` and step it once, so a job that
  already ended — or history that already matches — is ready at once.
  {:wait w}, {:error :no-job} when there is no such job, or {:error
  :bad-pattern :message m}."
  [{:keys [run-id job pattern condition] :as opts} {:keys [snapshot now record] :as env}]
  (let [snap (snapshot run-id job)
        bad (when (or pattern (= :output-regex condition)) (pattern-error pattern))]
    (cond
      (nil? snap) {:error :no-job}
      bad {:error :bad-pattern :message bad}
      :else
      (let [id (-> (swap-vals! registry update :next inc) first :next)
            w (make (assoc opts :id (str "wait-" id)) snap (now))]
        (when record (swap! registry assoc-in [:recorders run-id] record))
        (put! w)
        (record! run-id w)
        {:wait (advance! env w)}))))

(defn poll!
  "Step every pending wait of run `run-id`; all its waits after."
  [run-id env]
  (doseq [w (waits run-id) :when (= :pending (:state w))]
    (advance! env w))
  (waits run-id))

(defn await!
  "Block until wait `id` is no longer pending, looking every `:poll-ms` and
  sleeping with `:sleep!`; the wait as it ended. Its own deadline bounds the
  wait. A throw out of the sleep (a cancelled turn) cancels the wait."
  [run-id id {:keys [now sleep! poll-ms] :as env}]
  (try
    (loop []
      (let [w (wait run-id id)]
        (if (or (nil? w) (not= :pending (:state w)))
          w
          (let [w (advance! env w)]
            (if (not= :pending (:state w))
              w
              (do (sleep! (max 1 (min poll-ms (- (:deadline-ms w) (now)))))
                  (recur)))))))
    (catch Throwable e
      (when-let [w (wait run-id id)]
        (when (= :pending (:state w))
          (let [w' (end w :cancelled :interrupted (now))]
            (put! w')
            (record! run-id w'))))
      (throw e))))

(defn settle!
  "A ready wait whose waiter has been told becomes :delivered, and an expired
  one :told?. Returns the wait as it was before."
  [run-id id]
  (let [w (wait run-id id)]
    (case (:state w)
      :ready (let [w' (assoc w :state :delivered)]
               (put! w') (record! run-id w') w)
      :expired (let [w' (assoc w :told? true)]
                 (put! w') (record! run-id w') w)
      w)))

(defn pending
  "The pending :watch waits of run `run-id`, of the branches in `branch-ids`
  (every branch when nil)."
  [run-id branch-ids]
  (filterv #(and (= :pending (:state %)) (= :watch (:mode %))
                 (or (nil? branch-ids) (contains? (set branch-ids) (:branch-id %))))
           (waits run-id)))

(defn take-wakes!
  "The :watch waits of branch `branch-id` that are ready, or expired and not
  yet told, after one poll — each delivered (or told) once. Returns them as
  they were before delivery, so :state still says ready or expired."
  [run-id branch-id env]
  (poll! run-id env)
  (->> (waits run-id)
       (filter #(and (= branch-id (:branch-id %)) (= :watch (:mode %))
                     (or (= :ready (:state %))
                         (and (= :expired (:state %)) (not (:told? %))))))
       (mapv #(settle! run-id (:id %)))))

(defn await-any!
  "Block until one of run `run-id`'s pending :watch waits of `branch-ids` is
  ready, none is left pending, `:max-ms` pass, or `:abort?` says stop. Polls
  every `:poll-ms`. {:ready [w..]}, {:none true}, {:timeout true :pending
  [w..]} or {:aborted true}. Delivers nothing: the caller takes the wakes it
  acts on."
  [run-id branch-ids {:keys [now sleep! poll-ms max-ms abort?] :as env}]
  (let [started (now)
        mine? #(and (= :watch (:mode %))
                    (or (nil? branch-ids) (contains? (set branch-ids) (:branch-id %))))]
    (loop []
      (let [ws (filter mine? (poll! run-id env))
            ready (filterv #(= :ready (:state %)) ws)
            pend (filterv #(= :pending (:state %)) ws)]
        (cond
          (seq ready) {:ready ready}
          (empty? pend) {:none true}
          (and abort? (abort?)) {:aborted true}
          (>= (- (now) started) max-ms) {:timeout true :pending pend}
          :else (do (sleep! (max 1 (min poll-ms (- (+ started max-ms) (now)))))
                    (recur)))))))

(defn cancel!
  "Cancel wait `id`: :cancelled, :not-pending when it had already ended, nil
  when the run has no such wait."
  [run-id id now]
  (when-let [w (wait run-id id)]
    (if (= :pending (:state w))
      (let [w' (end w :cancelled :cancelled now)]
        (put! w') (record! run-id w') :cancelled)
      :not-pending)))

(defn forget-run!
  "Cancel every pending wait of run `run-id`, recording it, and drop them all."
  [run-id]
  (let [t (System/currentTimeMillis)]
    (doseq [w (waits run-id) :when (= :pending (:state w))]
      (record! run-id (end w :cancelled :run-ended t))))
  (swap! registry (fn [reg]
                    (-> reg
                        (update :waits #(into {} (remove (fn [[_ w]] (= run-id (:run-id w)))) %))
                        (update :recorders dissoc run-id)))))

;; --- durability -----------------------------------------------------------------

(defn latest
  "The last recorded state of each wait in `records` (oldest first), by id."
  [records]
  (reduce (fn [m r] (if (:id r) (assoc m (str (:id r)) r) m)) {} records))

(defn restore!
  "Load run `run-id`'s waits from their records, after a resume. The jobs did
  not survive the process that ran them, so a wait still pending expires as
  :job-gone (recorded, and told to its branch at its next call); one that was
  ready and never delivered is still owed its wake. Returns the restored waits."
  [run-id records {:keys [now record]}]
  (when record (swap! registry assoc-in [:recorders run-id] record))
  (let [ws (for [[_ r] (latest records)]
             (-> r
                 (assoc :run-id run-id)
                 (update :state #(some-> % keyword))
                 (update :mode #(some-> % keyword))
                 (update :condition #(some-> % keyword))
                 (update :outcome #(some-> % keyword))))]
    (swap! registry update :next
           (fn [n] (reduce max n (map (comp inc id-number :id) ws))))
    (mapv (fn [w]
            (if (= :pending (:state w))
              (let [w' (end w :expired :job-gone (now))]
                (put! w') (record! run-id w') w')
              (put! w)))
          ws)))
