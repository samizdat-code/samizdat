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
;; The start, read and kill flow follows ECA's background shell mode
;; (https://github.com/editor-code-assistant/eca, src/eca/features/tools/
;; shell.clj and background.clj), Copyright Eric Dallo and contributors,
;; under the Apache License 2.0.

(ns samizdat.agent.tools.jobs
  "Background jobs from the model's side: `shell {background: true}` starts
  one (through `start!` here, which the shell tool calls), the `jobs` tool
  lists, reads and kills them, and `exit-notice` is what a branch is told at
  its next call when one of its jobs ended on its own.

  `jobs` also waits on a job's output (samizdat.engine.waits): \"wait\" blocks
  the call until the job prints a line matching `pattern` or exits, \"watch\"
  registers the same wait and returns, and `wait-notice` is the wake a branch
  gets at its next call — or, when the run is finishing, from the beam's hold
  cell, which decides whether a pending watch keeps the run open. Every move
  a wait makes is journalled as a :job-wait note, which is what
  `restore-waits!` reads back on a resume.

  The process, its buffer and its lifetime are samizdat.engine.jobs'; the
  decision to run it is samizdat.security.policy's, through `start-shell`.
  Every line read back is redacted with the values the command's own gate
  knew, as a command's output is."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.cancel :as cancel]
            [samizdat.engine.jobs :as jobs]
            [samizdat.engine.waits :as waits]
            [samizdat.lexicon :as lexicon]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.security.secrets :as secrets]
            [samizdat.store.journal :as journal]
            [samizdat.util :as util]))

(defn- policy-knobs [] (gates/threshold :background-jobs))

(defn background?
  "Whether a shell call asked to run in the background."
  [ctx]
  (contains? #{true "true"} (base/arg ctx :background)))

(defn- line-text [{:keys [text stream]}]
  (if (= :stderr stream) (str "[stderr] " text) text))

(defn- lines-text
  "`lines` as the text the model reads: stderr marked, secrets redacted, and
  cut to the shell's own output budget."
  [lines known]
  (util/truncate-middle (secrets/redact (str/join "\n" (map line-text lines)) known)
                        (lexicon/budget :shell-output-chars)))

(defn- msg [vars] (prompt/render "jobs-tool" vars))

(defn- launch
  "The launcher `policy/start-shell` calls once the command is allowed: start
  the job, give it the initial wait, and say how it is."
  [{:keys [run-id branch]} {:keys [argv env dir command known capture capture-id on-end]}]
  (let [{:keys [max-jobs buffer-lines max-line-chars initial-wait-ms poll-ms]} (policy-knobs)
        started (jobs/start! {:run-id run-id :branch-id (:id branch) :command command
                              :argv argv :env env :dir dir :known known
                              :max-jobs max-jobs :buffer-lines buffer-lines
                              :max-line-chars max-line-chars
                              :capture capture :capture-id capture-id :on-end on-end})
        say (fn [vars] (prompt/render "background-job" vars))]
    (cond
      (:refused started)
      {:category :mechanics :progress? false :policy-refusal? true
       :result (say {:cap true :max (:max started)})}

      (:error started)
      {:category :failure :progress? false :result (say {:error (:error started)})}

      :else
      (let [id (:job started)
            snap (jobs/await-start! run-id id initial-wait-ms poll-ms)
            {:keys [lines]} (jobs/read! run-id id)
            output (when (seq lines) (lines-text lines known))
            vars {:id id :command command :output output :exit (:exit snap)}]
        (case (:status snap)
          :running {:category :success :progress? true :result (say (assoc vars :started true))
                    :data {:job id :running? true}}
          ;; Reported here, so not again as a notice.
          :completed (do (jobs/mark-told! run-id id)
                         {:category :success :progress? true
                          :result (say (assoc vars :finished true))
                          :data {:job id :exit 0}})
          (do (jobs/mark-told! run-id id)
              {:category :failure :progress? true :result (say (assoc vars :failed true))
               :data {:job id :exit (:exit snap)}}))))))

(defn start!
  "Run the shell call `ctx` as a background job, through the shell gate. A
  run at its job cap is refused before the command is decided, so nobody is
  asked to approve a command that could not start."
  [{:keys [branch run-id] :as ctx}]
  (let [{:keys [max-jobs]} (policy-knobs)]
    (if (>= (jobs/running-count run-id) max-jobs)
      (base/refusal branch (prompt/render "background-job" {:cap true :max max-jobs}))
      (policy/start-shell ctx (partial launch ctx)))))

(defn- seconds [{:keys [started-ms ended-ms]}]
  (quot (- (or ended-ms (System/currentTimeMillis)) (or started-ms 0)) 1000))

(defn- status-name [s] (some-> s name))

;; --- waits on a job's output ----------------------------------------------------

(defn- wait-knobs [] (gates/threshold :job-waits))

(defn- record-of
  "What the journal keeps of wait `w`: enough to restore it on a resume."
  [w]
  (select-keys w [:id :branch-id :job :condition :pattern :mode :state :outcome
                  :from :scanned :created-ms :deadline-ms :quiet-ms :ended-ms :told?
                  :job-status :exit]))

(defn- recorder [conn run-id]
  (fn [w]
    (when conn
      (journal/note! conn run-id :job-wait {:branch-id (:branch-id w) :data (record-of w)}))))

(defn wait-env
  "The effects a wait runs on: this process's jobs, the wall clock, a
  cancellable sleep, and the journal."
  [conn run-id]
  {:snapshot jobs/snapshot :now #(System/currentTimeMillis) :sleep! cancel/sleep!
   :poll-ms (:poll-ms (wait-knobs)) :record (recorder conn run-id)})

(defn- seconds-arg [ctx k]
  (let [v (base/arg ctx k)]
    (if (number? v)
      (double v)
      (some-> v str str/trim not-empty parse-double))))

(defn- bounded-ms
  "`secs` as ms, or `default-ms`, never past `max-ms` nor under zero."
  [secs default-ms max-ms]
  (long (max 0 (min max-ms (if secs (* 1000 secs) default-ms)))))

(defn- wait-vars
  "What the model is told about wait `w`, with the job's last lines."
  [run-id w]
  (let [{:keys [lines known]} (jobs/peek-lines run-id (:job w))
        j (jobs/job run-id (:job w))
        n (:tail-lines (wait-knobs))]
    {:wait (:id w) :job (:job w) :pattern (:pattern w)
     :matched (= :matched (:outcome w)) :exited (= :exited (:outcome w))
     :deadline (= :deadline (:outcome w)) :quiet (= :quiet (:outcome w))
     :gone (= :job-gone (:outcome w)) :cancelled (= :cancelled (:state w))
     :quiet-seconds (some-> (:quiet-ms w) (quot 1000))
     :seconds (quot (- (or (:ended-ms w) (System/currentTimeMillis)) (or (:created-ms w) 0)) 1000)
     :dropped (when (pos? (or (:dropped w) 0)) (:dropped w))
     :matches (when (seq (:matches w)) (lines-text (:matches w) known))
     :status (status-name (or (:status j) (:job-status w)))
     :exit (or (:exit j) (:exit w))
     :tail (when (and (seq lines) (not= :matched (:outcome w)))
             (lines-text (take-last n lines) known))}))

(defn- wait-call
  "jobs wait/watch: register a wait on job `id`, and for wait, block on it."
  [{:keys [branch run-id conn] :as ctx} id block?]
  (let [{:keys [default-wait-ms max-wait-ms default-watch-ms max-watch-ms max-matches]} (wait-knobs)
        pattern (some-> (base/arg ctx :pattern) str not-empty)
        env (wait-env conn run-id)
        secs (seconds-arg ctx :seconds)
        r (waits/register! {:run-id run-id :branch-id (:id branch) :job id
                            :pattern pattern
                            :condition (if pattern :output-regex :exit)
                            :history? (contains? #{true "true"} (base/arg ctx :history))
                            :timeout-ms (if block?
                                          (bounded-ms secs default-wait-ms max-wait-ms)
                                          (bounded-ms secs default-watch-ms max-watch-ms))
                            :quiet-ms (some-> (seconds-arg ctx :quiet) (* 1000) long)
                            :mode (if block? :block :watch)
                            :max-matches max-matches}
                           env)]
    (cond
      (= :bad-pattern (:error r))
      (base/malformed branch (msg {:bad-pattern true :pattern pattern :why (:message r)}))

      (:error r)
      (base/malformed branch (msg {:unknown true :id id}))

      (and (not block?) (= :pending (:state (:wait r))))
      (let [w (:wait r)]
        (base/ok branch (msg {:watching true :wait (:id w) :job id :pattern pattern
                              :seconds (quot (- (:deadline-ms w) (:created-ms w)) 1000)
                              :quiet-seconds (some-> (:quiet-ms w) (quot 1000))})
                 :progress? true))

      :else
      ;; A wait blocks; a watch already satisfied is said now rather than at
      ;; the next call.
      (let [w (if block? (waits/await! run-id (:id (:wait r)) env) (:wait r))]
        (waits/settle! run-id (:id w))
        (base/ok branch (msg (assoc (wait-vars run-id w) :waited true))
                 :progress? (= :ready (:state w)))))))

(defn wait-notice
  "What branch `branch-id` of run `run-id` is told about its watches that
  became ready or expired since it was last told, or nil. Each is told once."
  [conn run-id branch-id]
  (when branch-id
    (when-let [ws (seq (waits/take-wakes! run-id branch-id (wait-env conn run-id)))]
      (prompt/render "job-wait-woke" {:waits (mapv #(wait-vars run-id %) ws)}))))

(defn await-wake!
  "Block until a watch of `branch-ids` is ready, none is pending, `max-ms`
  pass or `abort?` says stop — samizdat.engine.waits/await-any!."
  [conn run-id branch-ids {:keys [max-ms poll-ms abort?]}]
  (waits/await-any! run-id branch-ids
                    (assoc (wait-env conn run-id) :max-ms max-ms :poll-ms poll-ms :abort? abort?)))

(defn restore-waits!
  "A resumed run's waits, from its :job-wait notes (samizdat.engine.waits/restore!)."
  [conn run-id]
  (waits/restore! run-id (journal/notes conn run-id :job-wait) (wait-env conn run-id)))

(defmethod base/run-tool "jobs"[{:keys [branch run-id] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case)
        id (some-> (base/arg ctx :id) str str/trim not-empty)]
    (cond
      (= "list" action)
      (base/ok branch (msg {:list true
                            :jobs (mapv (fn [j] {:id (:id j) :status (status-name (:status j))
                                                 :exit (:exit j) :seconds (seconds j)
                                                 :command (:command j)})
                                        (jobs/jobs run-id))
                            :waits (mapv (fn [w] {:id (:id w) :job (:job w) :pattern (:pattern w)
                                                  :state (status-name (:state w))
                                                  :outcome (status-name (:outcome w))})
                                         (waits/poll! run-id (wait-env (:conn ctx) run-id)))}))

      (= "unwatch" action)
      (case (and id (waits/cancel! run-id id (System/currentTimeMillis)))
        :cancelled (base/ok branch (msg {:unwatched true :wait id}) :progress? true)
        :not-pending (base/ok branch (msg {:wait-ended true :wait id
                                           :state (status-name (:state (waits/wait run-id id)))}))
        (base/malformed branch (msg {:unknown-wait true :wait id})))

      (not (contains? #{"output" "kill" "wait" "watch"} action))
      (base/malformed branch (msg {:usage true}))

      (nil? id)
      (base/malformed branch (msg {:usage true}))

      (nil? (jobs/job run-id id))
      (base/malformed branch (msg {:unknown true :id id}))

      (#{"wait" "watch"} action)
      (wait-call ctx id (= "wait" action))

      (= "output" action)
      (let [{:keys [lines dropped status exit known]} (jobs/read! run-id id)]
        (base/ok branch (msg {:output true :id id :status (status-name status) :exit exit
                              :log (:capture-id (jobs/job run-id id))
                              :dropped (when (pos? dropped) dropped)
                              :text (when (seq lines) (lines-text lines known))})
                 :progress? (boolean (seq lines))))

      :else
      (let [outcome (jobs/kill! run-id id)
            j (jobs/job run-id id)]
        (if (= :killed outcome)
          (base/ok branch (msg {:killed true :id id}) :progress? true)
          (base/ok branch (msg {:not-running true :id id :status (status-name (:status j))
                                :exit (:exit j)})))))))

(defn exit-notice
  "What branch `branch-id` of run `run-id` is told about its jobs that ended
  on their own since it was last told, or nil. Each is told once."
  [run-id branch-id]
  (when branch-id
    (when-let [ended (seq (jobs/take-exits! run-id branch-id))]
      (let [n (:notice-tail-lines (policy-knobs))]
        (prompt/render "job-exited"
                       {:jobs (mapv (fn [j]
                                      (let [{:keys [lines known]} (jobs/peek-lines run-id (:id j))]
                                        {:id (:id j) :command (:command j)
                                         :status (status-name (:status j)) :exit (:exit j)
                                         :tail (when (seq lines)
                                                 (lines-text (take-last n lines) known))}))
                                    ended)})))))
