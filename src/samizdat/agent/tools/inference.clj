;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.inference
  "Bounded private inference frames and maps."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.cancel :as cancel]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.frame :as frame]
            [samizdat.inference.map :as inference-map]
            [samizdat.inference.environment :as environment]
            [samizdat.prompt :as prompt]))

(defn- msg [vars]
  (prompt/render "inference-tool" vars))

(defn- allowance [ctx]
  (let [configured (gates/threshold :inference)
        supplied (:budget (:args ctx))]
    (budget/budget
     {:calls (min (or (:calls supplied) (:max-calls configured)) (:max-calls configured))
      :tokens (min (or (:tokens supplied) (:max-tokens configured)) (:max-tokens configured))
      :depth (min (or (:depth supplied) (:max-depth configured)) (:max-depth configured))
      :max-tranche (:output-tranche configured)})))

(defn- views [args]
  (mapv (fn [view]
          (if (string? view)
            {:label "literal" :content view}
            (select-keys view [:label :content :uri])))
        (:views args)))

(defmethod base/run-tool "inference" [{:keys [branch] :as ctx}]
  (try
    (let [ctx (assoc ctx :resolve-view #(resource-tool/read-view ctx %))
          action (some-> (base/arg ctx :action) str)]
      (case action
        "infer"
        (let [allowance (allowance ctx)
              result (frame/infer ctx {:task (str (base/arg ctx :task))
                                       :views (views (:args ctx))
                                       :schema (base/arg ctx :schema)
                                       :budget allowance})
              result (assoc result :remaining (budget/remaining allowance))]
          (base/ok branch (msg (assoc result :complete (= :complete (:status result))
                                      :incomplete (not= :complete (:status result))
                                      :value (pr-str (:value result))
                                      :reason (or (:reason result) (:error result))))
                   :resource result))

        "map"
        (let [allowance (allowance ctx)
              results (inference-map/map-inference
                       ctx (base/arg ctx :tasks)
                       {:budget allowance :parallel? (boolean (base/arg ctx :parallel))
                        :schema (base/arg ctx :schema)})]
          (base/ok branch (msg {:complete true :value (pr-str results)})
                   :resource {:results results :remaining (budget/remaining allowance)}))

        "complete"
        (let [allowance (allowance ctx)
              result (environment/complete
                      ctx {:task (str (base/arg ctx :task))
                           :context (str (base/arg ctx :context))
                           :schema (base/arg ctx :schema)
                           :continue (boolean (base/arg ctx :continue))
                           :budget allowance})]
          (base/ok branch (msg (assoc result :complete (= :complete (:status result))
                                      :incomplete (not= :complete (:status result))
                                      :value (pr-str (:value result))
                                      :reason (or (:reason result) (:error result))))
                   :resource result))

        (base/malformed branch (msg {:usage true}))))
    (catch clojure.lang.ExceptionInfo e
      (if (cancel/control-signal? e)
        (throw e)
        (base/malformed branch (msg {:error true :kind (name (:inference/error (ex-data e)))}))))
    (catch Throwable e
      (if (cancel/control-signal? e)
        (throw e)
        (base/fail branch (msg {:error true :kind (or (ex-message e) "inference-error")}))))))
