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

  The process, its buffer and its lifetime are samizdat.engine.jobs'; the
  decision to run it is samizdat.security.policy's, through `start-shell`.
  Every line read back is redacted with the values the command's own gate
  knew, as a command's output is."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.engine.jobs :as jobs]
            [samizdat.lexicon :as lexicon]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.security.secrets :as secrets]
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

(defmethod base/run-tool "jobs" [{:keys [branch run-id] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case)
        id (some-> (base/arg ctx :id) str str/trim not-empty)]
    (cond
      (= "list" action)
      (base/ok branch (msg {:list true
                            :jobs (mapv (fn [j] {:id (:id j) :status (status-name (:status j))
                                                 :exit (:exit j) :seconds (seconds j)
                                                 :command (:command j)})
                                        (jobs/jobs run-id))}))

      (not (contains? #{"output" "kill"} action))
      (base/malformed branch (msg {:usage true}))

      (nil? id)
      (base/malformed branch (msg {:usage true}))

      (nil? (jobs/job run-id id))
      (base/malformed branch (msg {:unknown true :id id}))

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
