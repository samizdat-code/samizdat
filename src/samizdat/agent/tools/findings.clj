;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.findings
  "The `finding` tool: the task owner's side of the review ledger
  (karamazov-05z0.9). A reviewer's finding the owner believes is wrong can
  be DISPUTED with the reason, and one it has fixed can be marked RESOLVED;
  the next review is shown both and rules on each. Before this the only
  answer to a wrong finding was more code."
  (:require [clojure.string :as str]
            [samizdat.agent.findings :as findings]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.store.tasks :as tasks]))

(defn- msg [ctx] (prompt/render "finding-tool" ctx))

(defmethod base/run-tool "finding" [{:keys [branch conn] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        task (some-> (:task branch) :id)
        ledger (when (and conn task) (tasks/findings conn task))]
    (cond
      (nil? task) (base/fail branch (msg {:no-task true}))

      (= "list" action)
      (base/ok branch (msg {:listed true :task task
                            :findings (mapv (fn [[id f]] (assoc f :id id :status (name (:status f))))
                                            (sort-by key (findings/standing ledger)))}))

      (#{"dispute" "resolve"} action)
      (let [id (some-> (base/arg ctx :id) str str/trim not-empty)
            reason (some-> (or (base/arg ctx :reason) (base/arg ctx :note)) str str/trim not-empty)
            status (if (= "dispute" action) :disputed :resolved)
            next-ledger (when (and id reason) (findings/answer ledger id status reason))]
        (cond
          (not (and id reason)) (base/malformed branch (str (base/missing ctx :id :reason) "\n\n"
                                                            (msg {:usage true})))
          (nil? next-ledger) (base/fail branch (msg {:unknown true :id id :task task}))
          :else (do (tasks/set-findings! conn task next-ledger)
                    (base/ok branch (msg {:answered true :id id :disputed (= :disputed status)})
                             :progress? true))))

      :else (base/malformed branch (msg {:usage true})))))
