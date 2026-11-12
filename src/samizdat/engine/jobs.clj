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
;;
;; The registry, the line buffer with its read cursor, and the kill that the
;; exit monitor cannot overwrite are ported from ECA
;; (https://github.com/editor-code-assistant/eca,
;; src/eca/features/background_tasks.clj), Copyright Eric Dallo and
;; contributors, under the Apache License 2.0.

(ns samizdat.engine.jobs
  "Background processes owned by a run rather than by the call that started
  them.

  `proc/run` waits for its process and reaps it at a deadline; that is right
  for a command and wrong for a server the work is tested against, which has
  to outlive the call. A job is started here from an argv the caller already
  built — the shell tool builds it through samizdat.security.policy, so the
  decision, the scrubbed environment and the OS sandbox are the same as any
  command's — and nothing here waits for it. Its stdout and stderr are read a
  line at a time into a bounded buffer; a per-job cursor makes each read
  return only the lines since the last; an exit monitor records how it ended,
  except over a kill, which is committed first.

  Keyed by run: a run sees only its own jobs, the cap counts its running
  ones, and `forget-run!` (called from run-state/run-ended!) kills them all,
  so nothing outlives the run. Mechanism only — the numbers are the caller's
  and every word the model reads is rendered by the tool."
  (:require [clojure.java.io :as io]
            [samizdat.engine.proc :as proc]))

(defonce ^:private registry
  ;; {:next n :jobs {id job}}. A job's :output and :cursor are atoms of their
  ;; own, so a chatty process does not contend with the registry.
  (atom {:next 1 :jobs {}}))

(defn- now [] (System/currentTimeMillis))

(defn- daemon [f]
  (doto (Thread. ^Runnable f) (.setDaemon true) (.start)))

(defn- append-line!
  "Add one line to `output`, keeping at least the last `cap` lines. Trimmed
  when it reaches twice that, so a long-lived chatty server costs an amortised
  constant per line instead of a copy of the buffer per line."
  [output cap stream text]
  (swap! output (fn [{:keys [lines total]}]
                  (let [lines (conj lines {:text text :stream stream})]
                    {:lines (if (> (count lines) (* 2 cap))
                              (vec (subvec lines (- (count lines) cap)))
                              lines)
                     :total (inc total)}))))

(defn- pump!
  "Read `in` on a daemon thread, splitting on newline into `output`. A line
  longer than `max-chars` bytes is cut there, and what is left at end of
  stream is the last line. Returns the thread."
  [in output cap max-chars stream]
  (daemon
   (fn []
     (let [buffer (byte-array 8192)
           pending (java.io.ByteArrayOutputStream.)
           flush! (fn []
                    (append-line! output cap stream
                                  (String. (.toByteArray pending) "UTF-8"))
                    (.reset pending))]
       (try
         (loop []
           (let [n (.read ^java.io.InputStream in buffer)]
             (when-not (neg? n)
               (loop [i 0 start 0]
                 (cond
                   (= i n) (when (< start n) (.write pending buffer start (- n start)))
                   (= 10 (aget buffer i))
                   (do (.write pending buffer start (- i start))
                       (flush!)
                       (recur (inc i) (inc i)))
                   (>= (+ (.size pending) (- i start)) max-chars)
                   (do (.write pending buffer start (- i start))
                       (flush!)
                       (recur i i))
                   :else (recur (inc i) start)))
               (recur))))
         (catch Throwable _ nil)
         (finally
           (when (pos? (.size pending)) (flush!))))))))

(defn- spawn
  "Start `argv` in `dir` with exactly `env`, its stdin closed."
  [argv env dir]
  (let [builder (ProcessBuilder. ^java.util.List (mapv str argv))]
    (when dir (.directory builder (io/file (str dir))))
    (let [environment (.environment builder)]
      (.clear environment)
      (doseq [[k v] env] (.put environment (str k) (str v))))
    (let [p (.start builder)]
      (try (.close (.getOutputStream p)) (catch Throwable _ nil))
      p)))

(defn- ended
  "`reg` with job `id` ended as `status` with `exit` — unless it was killed,
  which the kill committed before it signalled, so this cannot overwrite it."
  [reg id status exit]
  (if (or (nil? (get-in reg [:jobs id])) (= :killed (get-in reg [:jobs id :status])))
    reg
    (update-in reg [:jobs id] assoc :status status :exit exit :ended-ms (now))))

(defn- monitor!
  "Wait for `p` on a daemon thread; once its output is drained, record how it
  ended."
  [id ^java.lang.Process p pumps]
  (daemon
   (fn []
     (let [exit (try (.waitFor p) (.exitValue p) (catch Throwable _ nil))]
       ;; Drain what it wrote before saying it ended, so a reader that sees
       ;; the end also sees the last lines.
       (doseq [^Thread t pumps] (try (.join t 1000) (catch Throwable _ nil)))
       (swap! registry ended id (if (= 0 exit) :completed :failed) exit)))))

(defn- running? [job] (= :running (:status job)))

(defn- of-run [reg run-id]
  (filter #(= run-id (:run-id %)) (vals (:jobs reg))))

(defn- public [job]
  (select-keys job [:id :run-id :branch-id :command :status :exit :pid
                    :started-ms :ended-ms]))

(defn- id-number [id] (or (some-> (re-find #"\d+$" (str id)) parse-long) 0))

(defn job
  "Job `id` of run `run-id`, or nil — another run's job is nil too."
  [run-id id]
  (let [j (get-in @registry [:jobs id])]
    (when (and j (= run-id (:run-id j))) (public j))))

(defn jobs
  "Run `run-id`'s jobs, running and ended, oldest first."
  [run-id]
  (->> (of-run @registry run-id) (sort-by (comp id-number :id)) (mapv public)))

(defn running-count [run-id]
  (count (filter running? (of-run @registry run-id))))

(defn start!
  "Start `argv` as a job of run `run-id`, for branch `branch-id`.

  {:job id}, {:refused :cap :max n} when the run already has `max-jobs`
  running (nothing is started), or {:error message} when the spawn failed.
  `known` rides along for the reader's redaction; `buffer-lines` and
  `max-line-chars` bound what is kept."
  [{:keys [run-id branch-id command argv env dir known max-jobs buffer-lines max-line-chars]}]
  (let [slot (volatile! nil)]
    ;; Reserve the id under the cap before spawning, so two starts at once
    ;; cannot both take the last place.
    (swap! registry (fn [{:keys [next] :as reg}]
                      (if (>= (count (filter running? (of-run reg run-id))) max-jobs)
                        (do (vreset! slot nil) reg)
                        (let [id (str "job-" next)]
                          (vreset! slot id)
                          (-> reg
                              (assoc :next (inc next))
                              (assoc-in [:jobs id]
                                        {:id id :run-id run-id :branch-id branch-id
                                         :command command :status :running
                                         :started-ms (now) :known known
                                         :output (atom {:lines [] :total 0})
                                         :cursor (atom 0)
                                         :buffer-lines buffer-lines}))))))
    (if-let [id @slot]
      (try
        (let [p (spawn argv env dir)
              output (get-in @registry [:jobs id :output])
              pumps [(pump! (.getInputStream p) output buffer-lines max-line-chars :stdout)
                     (pump! (.getErrorStream p) output buffer-lines max-line-chars :stderr)]]
          (swap! registry update-in [:jobs id] assoc :proc p
                 :pid (try (.pid p) (catch Throwable _ nil)))
          (monitor! id p pumps)
          ;; Killed (or its run forgotten) between the reservation and the
          ;; spawn: the kill found no process to signal, so signal it here.
          (let [j (get-in @registry [:jobs id])]
            (when (or (nil? j) (= :killed (:status j)))
              (proc/reap! {:proc p})))
          {:job id})
        (catch Throwable e
          (swap! registry update :jobs dissoc id)
          {:error (or (ex-message e) (str e))}))
      {:refused :cap :max max-jobs})))

(defn- window
  "The lines a reader may see: the last `cap` retained, and where they start
  in the job's whole output."
  [{:keys [lines total]} cap]
  (let [visible (if (> (count lines) cap) (subvec lines (- (count lines) cap)) lines)]
    {:lines visible :start (- total (count visible)) :total total}))

(defn peek-lines
  "Job `id`'s retained lines, without moving its cursor:
  {:lines [{:text :stream}] :status :exit :known}, or nil."
  [run-id id]
  (when-let [j (get-in @registry [:jobs id])]
    (when (= run-id (:run-id j))
      (assoc (select-keys j [:status :exit :known])
             :lines (:lines (window @(:output j) (:buffer-lines j)))))))

(defn read!
  "The lines job `id` wrote since the last read, moving its cursor:
  {:lines :dropped :status :exit :known}, `:dropped` counting lines that left
  the buffer before anyone read them — or nil for an unknown job."
  [run-id id]
  (when-let [j (get-in @registry [:jobs id])]
    (when (= run-id (:run-id j))
      (let [{:keys [lines start total]} (window @(:output j) (:buffer-lines j))
            [prev] (swap-vals! (:cursor j) (constantly total))
            from (max 0 (- prev start))
            current (get-in @registry [:jobs id])]
        {:lines (if (< from (count lines)) (subvec lines from) [])
         :dropped (max 0 (- start prev))
         :status (:status current) :exit (:exit current) :known (:known j)}))))

(defn await-start!
  "Wait up to `ms`, looking every `poll-ms`, for job `id` to end; its state
  either way. A server that is up keeps running for the whole wait, which is
  the time its first output has to arrive."
  [run-id id ms poll-ms]
  (let [deadline (+ (now) ms)]
    (loop []
      (let [j (job run-id id)]
        (if (or (nil? j) (not (running? j)) (>= (now) deadline))
          j
          (do (Thread/sleep (long (max 1 (min poll-ms (- deadline (now))))))
              (recur)))))))

(defn mark-told!
  "Job `id`'s end no longer needs saying."
  [run-id id]
  (swap! registry (fn [reg]
                    (if (= run-id (get-in reg [:jobs id :run-id]))
                      (assoc-in reg [:jobs id :told?] true)
                      reg))))

(defn take-exits!
  "The jobs of `branch-id` in run `run-id` that ended on their own since it was
  last told, each told once."
  [run-id branch-id]
  (let [[before] (swap-vals! registry
                             (fn [reg]
                               (reduce (fn [r {:keys [id] :as j}]
                                         (if (and (= branch-id (:branch-id j))
                                                  (#{:completed :failed} (:status j))
                                                  (not (:told? j)))
                                           (assoc-in r [:jobs id :told?] true)
                                           r))
                                       reg (of-run reg run-id))))]
    (->> (of-run before run-id)
         (filter #(and (= branch-id (:branch-id %))
                       (#{:completed :failed} (:status %))
                       (not (:told? %))))
         (sort-by (comp id-number :id))
         (mapv public))))

(defn kill!
  "Stop job `id`: :killed, :not-running when it had already ended, nil when
  there is no such job in this run. The status is committed BEFORE the signal,
  so the exit monitor, which runs as soon as the process dies, cannot record
  the kill as a failure (the ordering ECA's kill-job! keeps)."
  [run-id id]
  (let [[before] (swap-vals! registry
                             (fn [reg]
                               (let [j (get-in reg [:jobs id])]
                                 (if (and j (= run-id (:run-id j)) (running? j))
                                   (update-in reg [:jobs id] assoc :status :killed
                                              :ended-ms (now) :told? true)
                                   reg))))
        j (get-in before [:jobs id])]
    (cond
      (or (nil? j) (not= run-id (:run-id j))) nil
      (not (running? j)) :not-running
      :else (do (when-let [p (or (:proc j) (get-in @registry [:jobs id :proc]))]
                  (proc/reap! {:proc p}))
                :killed))))

(defn forget-run!
  "Kill every running job of run `run-id` and drop them all."
  [run-id]
  (doseq [{:keys [id] :as j} (of-run @registry run-id)]
    (when (running? j)
      (try (kill! run-id id) (catch Throwable _ nil))))
  (swap! registry update :jobs
         (fn [m] (into {} (remove (fn [[_ j]] (= run-id (:run-id j)))) m))))
