;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.structural
  "Structural queries and revision-gated staged rewrites."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.executions :as executions]
            [samizdat.store.resources :as store]
            [samizdat.structural.backend :as process-backend]
            [samizdat.util :as util]))

(defn- msg [vars]
  (prompt/render "structural-tool" vars))

(defn- backend [ctx]
  (or (:structural-backend ctx)
      {:query process-backend/query :rewrite process-backend/rewrite}))

(defn- observation [ctx]
  (let [policy (gates/threshold :structural)
        uri (str (base/arg ctx :uri))
        expected (str (base/arg ctx :base-revision))
        observed (resource-tool/authorized-observation ctx uri)
        _ (when-not (= :workspace (:resolver observed))
            (throw (ex-info "structural requires a workspace resource"
                            {:structural/error :unsupported-resource :uri uri})))]
    (when (not= expected (:revision observed))
      (throw (ex-info "stale revision" {:structural/error :stale-revision :uri uri})))
    (when (> (count (str (:content observed))) (:max-source-chars policy))
      (throw (ex-info "source too large" {:structural/error :source-limit :uri uri})))
    observed))

(defn- execute [ctx action f]
  (let [id (executions/start! (:conn ctx)
                              {:run-id (:run-id ctx)
                               :branch-id (get-in ctx [:branch :id])
                               :kind "structural"
                               :specification (assoc (:args ctx) :action action)
                               :recovery :unknown})]
    (try
      (let [value (f id)]
        (executions/complete! (:conn ctx) id {:status :complete
                                              :artifacts [(str "execution:" id)]})
        value)
      (catch Throwable e
        (executions/complete! (:conn ctx) id {:status :error :artifacts []})
        (throw e)))))

(defn- query [ctx]
  (let [observed (observation ctx)
        policy (gates/threshold :structural)]
    (execute ctx "query"
             (fn [id]
               (let [matches ((:query (backend ctx))
                              ctx {:content (:content observed)
                                   :language (str (base/arg ctx :language))
                                   :pattern (str (base/arg ctx :pattern))})
                     shown (mapv #(update % :text util/truncate-middle
                                          (:max-preview-chars policy))
                                 (take (:max-matches policy) matches))
                     value {:execution-id id :uri (:uri observed)
                            :revision (:revision observed) :matches shown
                            :truncated (> (count matches) (count shown))}]
                 (base/ok (:branch ctx) (msg (assoc value :query true))
                          :resource value))))))

(defn- rewrite [ctx]
  (let [observed (observation ctx)]
    (execute ctx "rewrite"
             (fn [id]
               (let [replacement ((:rewrite (backend ctx))
                                  ctx {:content (:content observed)
                                       :language (str (base/arg ctx :language))
                                       :pattern (str (base/arg ctx :pattern))
                                       :replacement (str (base/arg ctx :replacement))})
                     uri (:uri observed)
                     members (workspace/stage-workspace
                              (get (resource-tool/registry-for ctx) :workspace)
                              ctx [observed] {uri replacement})
                     proposal (resource-tool/stage-proposal!
                               ctx members {:preview replacement :execution-id id})
                     value {:execution-id id :proposal-id (:id proposal)
                            :preview (util/truncate-middle
                                      replacement (:max-preview-chars (gates/threshold :structural)))}]
                 (base/ok (:branch ctx) (msg (assoc value :rewrite true))
                          :resource value))))))

(defn- owned-change [ctx id]
  (let [change (store/change-by-id (:conn ctx) id)]
    (when-not (and change
                   (= (:run-id ctx) (:run_id change))
                   (= (get-in ctx [:branch :id]) (:branch_id change)))
      (throw (ex-info "unknown proposal" {:structural/error :proposal-owner})))
    change))

(defn- inspect [ctx]
  (let [id (str (base/arg ctx :proposal))
        change (owned-change ctx id)
        members (store/change-members (:conn ctx) id)
        value {:id id :status (:status change)
               :previews (mapv #(select-keys % [:uri :replacement :status]) members)}]
    (base/ok (:branch ctx) (msg (assoc value :inspect true)) :resource value)))

(defn- apply [ctx]
  ((requiring-resolve 'samizdat.agent.tools/run-tool)
   (assoc ctx :tool-name "resource"
          :args {:action "apply" :proposal (str (base/arg ctx :proposal))})))

(defn- status [ctx]
  (let [configured (some? (:program (gates/threshold :structural)))
        available (or configured (some? (:structural-backend ctx)))
        value {:configured configured :available available
               :operations ["query" "rewrite" "inspect" "apply"]}]
    (base/ok (:branch ctx) (msg (assoc value :status true)) :resource value)))

(defmethod base/run-tool "structural" [{:keys [branch] :as ctx}]
  (try
    (case (some-> (base/arg ctx :action) str)
      "status" (status ctx)
      "query" (query ctx)
      "rewrite" (rewrite ctx)
      "inspect" (inspect ctx)
      "apply" (apply ctx)
      (base/malformed branch (msg {:usage true})))
    (catch clojure.lang.ExceptionInfo e
      (cond
        (= :unavailable (:structural/error (ex-data e)))
        (base/unavailable branch "structural" e)

        (:structural/error (ex-data e))
        (base/malformed branch (msg {:error true :kind (name (:structural/error (ex-data e)))}))

        (:resource/error (ex-data e))
        (base/malformed branch (msg {:error true :kind (name (:resource/error (ex-data e)))}))

        :else (throw e)))
    (catch Throwable e
      (base/fail branch (msg {:error true :kind (or (ex-message e) "structural-error")})))))
