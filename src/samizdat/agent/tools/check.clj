;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.check
  "The `run_check` tool: the verdict a run's own check gives, for a worker to read
  while it can still act on it.

  The shell policy refuses a program reading a file on stdin, as it refuses any
  redirection, and rightly: it cannot see what a compound command does. A local
  model that wrote a correct program on turn 3 spent its next 25 turns searching
  the harness for an allow list. This runs only the paired samples, through the
  grader's minimal environment, or the run's sealed verify command. It takes no
  command, so it is no way round the approval policy."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.verify :as verify]
            [samizdat.eval.samples :as samples]
            [samizdat.lexicon :as lexicon]
            [samizdat.prompt :as prompt]))

(defn- msg [ctx] (str/trim (prompt/render "check-tool" ctx)))

(defn- run-samples [root artifact chars]
  (let [path (str root "/" artifact)]
    (if-not (samples/read-artifact path)
      {:missing artifact}
      (let [r (samples/grade (str root "/tests") ["python3" path] {:dir (str root)})
            f (first (:failures r))
            clip (fn [s] (let [s (str s)] (if (> (count s) chars) (subs s 0 chars) s)))]
        (if (:ok? r)
          {:passed true :total (:total r)}
          (cond-> {:failed true :total (:total r) :passed-count (:passed r)}
            f (assoc :case (:name f) :reason (name (:reason f))
                     :input (pr-str (clip (:input f)))
                     :expected (pr-str (clip (:expected f)))
                     :got (pr-str (clip (:got f))))))))))

(defmethod base/run-tool "run_check" [{:keys [branch root run-id config]}]
  (let [{:keys [artifact report-chars verify-timeout-ms]} (lexicon/policy :team-solve)
        cmd (get-in config [:run :verify-cmd])
        has-samples? (seq (remove :missing (samples/paired-tests (str root "/tests"))))]
    (base/ok branch
             (msg (cond
                    has-samples? (run-samples root artifact report-chars)
                    (not (str/blank? (str cmd)))
                    (let [r (verify/run-configured run-id cmd verify-timeout-ms)]
                      (if (:green? r)
                        {:passed true}
                        {:failed true :output (:output r)}))
                    :else {:none true}))
             :progress? true)))
