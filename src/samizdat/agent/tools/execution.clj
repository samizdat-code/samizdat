;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.execution
  "Inspect, deliver, and explicitly revive durable executions."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.store.executions :as executions]))

(defn- msg [vars]
  (prompt/render "execution-tool" vars))

(defn- owned [ctx id]
  (let [execution (executions/execution (:conn ctx) id)]
    (when (and execution
               (= (:run-id ctx) (:run_id execution))
               (= (get-in ctx [:branch :id]) (:branch_id execution)))
      execution)))

(defn- inspect [ctx id]
  (if-let [execution (owned ctx id)]
    (base/ok (:branch ctx)
             (msg {:inspect true :execution execution
                   :receipt (executions/receipt (:conn ctx) id)
                   :continuity (executions/continuity (:conn ctx) id)})
             :resource {:execution execution
                        :receipt (executions/receipt (:conn ctx) id)
                        :continuity (executions/continuity (:conn ctx) id)})
    (base/malformed (:branch ctx) (msg {:missing true}))))

(defn- revive [ctx id]
  (if-let [execution (owned ctx id)]
    (let [new-id (executions/revive! (:conn ctx) id {:reason (base/arg ctx :reason)})
          resource-map {:new-id new-id
                        :continuity (executions/continuity (:conn ctx) new-id)}]
      (base/ok (:branch ctx)
               (msg (assoc resource-map :revived true :old-id id))
               :resource resource-map))
    (base/malformed (:branch ctx) (msg {:missing true}))))

(defn- dispatch [ctx id]
  (let [execution (owned ctx id)]
    (if (and execution (:revived_from execution)
             (#{"shell" "inference"} (:kind execution))
             (= :safe-restart (:recovery execution))
             (= :running (:status execution))
             (executions/claim-dispatch! (:conn ctx) id))
      (case (:kind execution)
        "shell" (policy/run-shell (assoc ctx :execution-id id
                                         :args (:specification execution)))
        "inference" ((requiring-resolve 'samizdat.agent.tools/run-tool)
                     (assoc ctx :execution-id id :tool-name "inference"
                            :args (merge {:action "infer"} (:specification execution))))
        (base/malformed (:branch ctx) (msg {:error true :kind :unsupported-execution-kind})))
      (base/malformed (:branch ctx) (msg {:error true :kind :not-restartable})))))

(defmethod base/run-tool "execution" [{:keys [branch] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str)
        id (str (base/arg ctx :id))]
    (case action
      "inspect" (inspect ctx id)
      "revive" (revive ctx id)
      "dispatch" (dispatch ctx id)
      "deliver" (base/ok branch
                         (msg {:delivered true
                               :count (executions/deliver-all!
                                       (:conn ctx) (:run-id ctx) (:id branch)
                                       (assoc (gates/threshold :async) :defer? false))})
      (base/malformed branch (msg {:usage true}))))))
