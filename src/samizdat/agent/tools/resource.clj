;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.resource
  "Model-facing reads and revision-gated changes through resource resolvers."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.diagnosis :as diagnosis-resource]
            [samizdat.resource.debug :as debug-resource]
            [samizdat.resource.execution :as execution-resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.resource.inference :as inference]
            [samizdat.resource.knowledge :as knowledge-resource]
            [samizdat.resource.shell-log :as shell-log]
            [samizdat.security.flow :as flow]
            [samizdat.security.secrets :as secrets]
            [samizdat.store.resources :as store]))

(defonce ^:private registries (atom {}))
(defonce ^:private proposals (atom {}))

(defn forget-run! [run-id]
  (doseq [state [registries proposals]]
    (swap! state (fn [entries]
                   (into {} (remove (fn [[[rid _] _]] (= rid run-id)) entries))))))

(defn- key [ctx]
  [(:run-id ctx) (get-in ctx [:branch :id]) (str (:root ctx))])

(defn registry-for
  "The branch-local resolver registry and proposal-token state."
  [ctx]
  (or (get @registries (key ctx))
      (get (swap! registries #(if (get % (key ctx)) %
                                  (assoc % (key ctx)
                                         (resource/registry
                                          {:workspace (workspace/workspace-resolver {})
                                           :inference (inference/resolver (:conn ctx) {:search-limit (:search-results (gates/threshold :resources))})
                                           :context (inference/resolver (:conn ctx) {:search-limit (:search-results (gates/threshold :resources))})
                                           :knowledge (knowledge-resource/resolver (:conn ctx))
                                           :diagnosis (diagnosis-resource/resolver (:conn ctx))
                                           :execution (execution-resource/resolver (:conn ctx))
                                           :debug (debug-resource/resolver (:conn ctx))
                                           :shell-log (shell-log/resolver
                                                       (:conn ctx)
                                                       (merge (gates/threshold :resources)
                                                              (gates/threshold :shell-logs)))}))))
           (key ctx))))

(defn- msg [vars]
  (prompt/render "resource-tool" vars))

(defn- number-arg [ctx k fallback]
  (let [v (base/arg ctx k)]
    (cond
      (nil? v) fallback
      (number? v) (long v)
      :else (Long/parseLong (str v)))))

(defn- writing? [branch]
  (or (nil? (:role branch)) (roles/may-use? (:role branch) "write_file")))

(defn- authorize! [ctx observation]
  (let [frame (get-in observation [:metadata :frame])
        context (get-in observation [:metadata :context])
        capture (get-in observation [:metadata :capture])
        diagnosis (get-in observation [:metadata :diagnosis])
        execution (get-in observation [:metadata :execution])
        run-id (or (:run_id frame) (:run_id context) (:run_id capture) (:run_id diagnosis) (:run_id execution))
        branch-id (or (:branch_id frame) (:branch_id context) (:branch_id capture) (:branch_id diagnosis) (:branch_id execution))]
    (when (or (and run-id (not= run-id (:run-id ctx)))
              (and branch-id (not= branch-id (get-in ctx [:branch :id]))))
      (throw (ex-info "resource owner mismatch"
                      {:resource/error :access-denied :uri (:uri observation)})))
    observation))

(defn authorized-observation [ctx uri]
  (authorize! ctx (resource/observe (registry-for ctx) uri ctx)))

(defn read-view
  "Resolve one bounded frame view with the caller's resource authority."
  [ctx uri]
  (let [registry (registry-for ctx)
        observation (authorize! ctx (resource/observe registry uri ctx))
        cap (:max-slice-chars (gates/threshold :inference))
        result (resource/read-window registry observation ctx {:offset 0 :limit cap})]
    (when (:next-offset result)
      (throw (ex-info "inference view exceeds its bound"
                      {:inference/error :view-too-large :uri uri :cap cap})))
    (when (str/starts-with? (str uri) resource/workspace-prefix)
      (flow/read! ctx [(str/replace-first (str uri) resource/workspace-prefix "")] "inference"))
    (secrets/redact (str (:content result))
                    (secrets/known-values (or (:env ctx) (into {} (System/getenv)))))))

(defn- read [ctx]
  (let [registry (registry-for ctx)
        uri (str (base/arg ctx :uri))
        observation (authorize! ctx (resource/observe registry uri ctx))
        _ (when-let [row (get-in observation [:metadata :knowledge])]
            (flow/receive! ctx [(:flow row)] "resource"))
        limit (min (number-arg ctx :limit (:read-chars (gates/threshold :resources)))
                   (:read-chars (gates/threshold :resources)))
        result (resource/read-window registry observation ctx
                                     {:offset (number-arg ctx :offset 0) :limit limit
                                      :query (base/arg ctx :query)})]
    (base/ok (:branch ctx) (msg (assoc result :read true)))))

(defn- operations [ctx]
  (base/ok (:branch ctx)
           (msg {:operations true
                 :operations-text (pr-str (resource/operations (registry-for ctx)
                                                               (str (base/arg ctx :uri)) ctx))})))

(defn stage-proposal!
  "Durably stage resource members and retain the proposal for a later tool call."
  ([ctx members] (stage-proposal! ctx members {}))
  ([ctx members opts]
   (when (> (count members) (:max-proposal-members (gates/threshold :resources)))
     (throw (ex-info "too many members" {:resource/error :too-many-members})))
   (let [proposal (merge (changes/stage! (registry-for ctx) ctx members) opts)]
     (swap! proposals assoc (conj (key ctx) (:id proposal)) proposal)
     proposal)))

(defn- edit [ctx]
  (if-not (writing? (:branch ctx))
    (base/refusal (:branch ctx) (msg {:no-write true}))
    (let [registry (registry-for ctx)
          uri (str (base/arg ctx :uri))
          observation (resource/observe registry uri ctx)
          members (workspace/stage-workspace
                   (get registry :workspace) ctx [observation]
                   {uri (str (base/arg ctx :content))})
          proposal (stage-proposal! ctx members)]
      (base/ok (:branch ctx) (msg {:staged true :proposal (:id proposal)})
               :resource {:proposal-id (:id proposal)}))))

(defn- apply-proposal [ctx]
  (if-not (writing? (:branch ctx))
    (base/refusal (:branch ctx) (msg {:no-write true}))
    (let [id (str (base/arg ctx :proposal))
          proposal (get @proposals (conj (key ctx) id))
          _ (when-not proposal
              (throw (ex-info "unknown proposal" {:resource/error :proposal-stale})))
          result (changes/apply! (registry-for ctx) ctx proposal)]
      (when (and (= :committed (:status result)) (:after-commit proposal))
        ((:after-commit proposal)))
      (base/ok (:branch ctx) (msg (assoc result :show-status true))
               :category (if (= :committed (:status result)) :success :mechanics)
               :progress? (= :committed (:status result))
               :resource (assoc result :members (:members proposal))))))

(defn- unfinished [ctx]
  (let [rows (store/unfinished-changes
              (:conn ctx) {:run-id (:run-id ctx) :branch-id (get-in ctx [:branch :id])})]
    (base/ok (:branch ctx)
             (pr-str (mapv #(select-keys % [:id :status :created_at :updated_at]) rows)))))

(defn- recover [ctx]
  (if-not (writing? (:branch ctx))
    (base/refusal (:branch ctx) (msg {:no-write true}))
    (let [result (changes/recover! (registry-for ctx) ctx (str (base/arg ctx :proposal)))]
      (base/ok (:branch ctx) (msg (assoc result :show-status true))
               :category (if (= :compensated (:status result)) :success :mechanics)
               :progress? (= :compensated (:status result))
               :resource result))))

(defmethod base/run-tool "resource" [{:keys [branch] :as ctx}]
  (try
    (case (some-> (base/arg ctx :action) str)
      "read" (read ctx)
      "operations" (operations ctx)
      "edit" (edit ctx)
      "apply" (apply-proposal ctx)
      "unfinished" (unfinished ctx)
      "recover" (recover ctx)
      (base/malformed branch (msg {:usage true})))
    (catch clojure.lang.ExceptionInfo e
      (base/malformed branch
                      (msg {:error true
                            :kind (name (or (:resource/error (ex-data e)) :invalid-request))
                            :uri (:uri (ex-data e))})))
    (catch Throwable _
      (base/fail branch (msg {:error true :kind "invalid-request"})))))
