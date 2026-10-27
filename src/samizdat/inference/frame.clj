;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.frame
  "Private, contracted inference frames with explicit views and shared budgets."
  (:require [samizdat.cancel :as cancel]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.contract :as contract]
            [samizdat.inference.trace :as trace]
            [samizdat.llm.client :as llm]
            [samizdat.prompt :as prompt]
            [samizdat.store.executions :as executions]
            [samizdat.store.journal :as journal]
            [samizdat.agent.gates :as gates]))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :inference/error kind))))

(defn- resolve-views! [ctx capabilities views cap]
  (mapv
   (fn [{:keys [uri content] :as view}]
     (when (and uri (not (contains? capabilities :resource/read)))
       (fail :unsupported-capability {:capability :resource/read :uri uri}))
     (let [view (if uri
                  (if-let [resolve-view (:resolve-view ctx)]
                    (assoc view :content (resolve-view uri))
                    (fail :missing-resource-resolver {:uri uri}))
                  view)]
       (when (and cap (> (count (str (:content view))) cap))
         (fail :view-too-large {:uri uri :chars (count (str (:content view))) :cap cap}))
       view))
   views))

(defn- usage! [ctx reply kind role trace-id]
  (when (and (:conn ctx) (:run-id ctx))
    (journal/record-side-call!
     (:conn ctx) (:run-id ctx)
     {:branch-id (get-in ctx [:branch :id]) :turn (get-in ctx [:branch :turn])
      :kind kind :role role :trace-id trace-id
      :model (get-in ctx [:llm-config :model]) :usage (:usage reply)})))

(defn- add-usage [total usage]
  (reduce (fn [acc [k v]] (update acc k (fnil + 0) (long (or v 0))))
          total
          (select-keys usage [:prompt-tokens :completion-tokens :total-tokens
                              :cache-hit-tokens :cache-miss-tokens])))

(defn- complete! [ctx trace-id status value usage]
  (trace/finish-frame! (:conn ctx) trace-id status value usage)
  (when (and (:run-id ctx)
             (not (and (:defer-execution-completion? ctx) (= :complete status))))
    (executions/complete! (:conn ctx) trace-id
                          {:status status :artifacts [(str "inference:" trace-id)]}))
  (merge {:status status :trace-id trace-id :usage usage}
         (if (= :complete status) {:value value} value)))

(defn infer
  "Run one private frame. Returns :complete with a value or :incomplete with evidence."
  [ctx {:keys [task views schema budget capabilities role kind messages-fn parent-id]
        :or {capabilities #{:resource/read} role :inference kind :inference}}]
  (let [specification {:task task :views views :schema schema
                       :parent-id parent-id :role role :kind kind}
        views (resolve-views! ctx capabilities views
                              (when-not messages-fn
                                (:max-slice-chars (gates/threshold :inference))))
        schema (contract/normalize schema)
        conn (:conn ctx)
        trace-id (trace/start-frame! conn {:id (:execution-id ctx)
                                           :run-id (:run-id ctx)
                                           :branch-id (get-in ctx [:branch :id])
                                           :parent-id parent-id
                                           :task task :schema schema
                                           :role role
                                           :model (get-in ctx [:llm-config :model])})
        _ (when (and (:run-id ctx) (not (executions/execution conn trace-id)))
            (executions/start! conn {:id trace-id :run-id (:run-id ctx)
                                     :branch-id (get-in ctx [:branch :id])
                                     :kind (name kind) :specification specification
                                     :recovery (if (and (= :inference kind) (nil? messages-fn))
                                                 :safe-restart :unknown)}))
        initial-messages (if messages-fn
                           (messages-fn {:task task :views views :schema schema})
                           [{:role "user"
                             :content (prompt/render "inference-frame"
                                                     {:task task :views views
                                                      :schema (when-not (= :text schema)
                                                                (pr-str schema))})}])]
    (doseq [view views] (trace/record-view! conn trace-id view))
    (loop [attempt 1 history initial-messages repair-error nil usage-total {}]
      (if-let [reservation (budget/reserve! budget {:output-tokens 8192})]
        (let [reply (atom nil)
              failure (atom nil)
              messages (if (= 1 attempt)
                         history
                         (conj history {:role "user"
                                        :content (prompt/render "inference-repair"
                                                                {:error repair-error
                                                                 :schema (pr-str schema)})}))
              config (assoc (:llm-config ctx) :max-tokens
                            (min (long (or (get-in ctx [:llm-config :max-tokens]
                                           (:output-tokens reservation))
                                         (:output-tokens reservation)))
                                 (:output-tokens reservation)))
              outcome
              (try
                (reset! reply (llm/chat (:llm-adapter ctx) config messages))
                (budget/settle! budget reservation {:usage (:usage @reply)
                                                    :priced? (some? (:usage @reply))})
                (let [decoded (contract/decode schema (str (:content @reply)))]
                  (if (contains? decoded :value)
                    [:complete (:value decoded)]
                    (if (and (= 1 attempt) (pos? (:calls (budget/remaining budget))))
                      [:repair decoded]
                      [:incomplete decoded])))
                (catch Throwable e
                  (reset! failure e)
                  (budget/settle! budget reservation {:usage (:usage @reply)
                                                      :priced? (some? (:usage @reply))})
                  (if (cancel/control-signal? e)
                    (do
                      (complete! ctx trace-id :cancelled
                                 {:reason :cancelled :error (ex-message e)}
                                 (add-usage usage-total (:usage @reply)))
                      (throw e))
                    [:incomplete {:error (ex-message e)}]))
                (finally
                  (trace/record-attempt! conn trace-id attempt messages @reply @failure)
                  (usage! ctx @reply kind role trace-id)))]
          (case (first outcome)
            :complete (complete! ctx trace-id :complete (second outcome)
                               (add-usage usage-total (:usage @reply)))
            :incomplete (complete! ctx trace-id :incomplete (second outcome)
                               (add-usage usage-total (:usage @reply)))
            :repair (recur (inc attempt)
                           (conj history {:role "assistant"
                                          :content (str (:content @reply))})
                           (:error (second outcome))
                           (add-usage usage-total (:usage @reply)))))
        (complete! ctx trace-id :incomplete {:reason :budget-exhausted} usage-total)))))
