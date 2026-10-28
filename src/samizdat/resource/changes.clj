;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.changes
  "Exact-owner staged changes with durable recovery records."
  (:require [samizdat.agent.files :as files]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.journal :as journal]
            [samizdat.store.resources :as store]))

(defonce ^:private mutation-lock (Object.))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :resource/error kind))))

(defn- owner [ctx]
  {:run-id (:run-id ctx) :branch-id (get-in ctx [:branch :id])})

(defn- note! [ctx kind data]
  (when (and (:conn ctx) (:run-id ctx))
    (try
      (journal/note! (:conn ctx) (:run-id ctx) kind
                     {:branch-id (get-in ctx [:branch :id]) :data data})
      (catch Throwable _ nil))))

(defn- authorize-member! [ctx {:keys [resolver metadata]}]
  (when (= :workspace resolver)
    (doseq [path (keep identity [(:path metadata) (:target-path metadata)])]
      (when (or (files/run-config? (:root ctx) path)
                (files/workflow-refused? ctx (:root ctx) path))
        (fail :write-refused {:path path})))))

(defn- validate-members! [registry ctx members]
  (doseq [{:keys [uri base-revision replacement-uri target-revision] :as member} members]
    (authorize-member! ctx member)
    (let [current (resource/observe registry uri ctx)]
      (when (not= base-revision (:revision current))
        (fail :stale-revision {:uri uri :expected base-revision
                               :actual (:revision current)})))
    (when replacement-uri
      (let [current (resource/observe registry replacement-uri ctx)]
        (when (not= target-revision (:revision current))
          (fail :stale-revision {:uri replacement-uri :expected target-revision
                                 :actual (:revision current)}))))))

(defn stage!
  "Validate and durably stage already-prepared members for this exact owner."
  [registry ctx members]
  (when-not (= (count members) (count (distinct (map :uri members))))
    (fail :duplicate-resource {:uris (mapv :uri members)}))
  (validate-members! registry ctx members)
  (let [owner (owner ctx)
        id (store/begin-change! (:conn ctx) owner members)
        token (str (random-uuid))]
    (swap! (:proposal-state registry) assoc id token)
    (note! ctx :resource-change-staged {:change-id id :uris (mapv :uri members)})
    {:id id :token token :run-id (:run-id owner) :branch-id (:branch-id owner)
     :members (vec members)}))

(defn- consumed! [registry ctx proposal]
  (let [id (:id proposal)
        state (:proposal-state registry)
        token (get @state id)
        stored (when (:conn ctx) (store/change-by-id (:conn ctx) id))]
    (cond
      (not= (owner ctx) {:run-id (:run-id proposal) :branch-id (:branch-id proposal)})
      (fail :proposal-owner {:id id})

      (and token (= token (:token proposal)))
      (do (swap! state dissoc id) true)

      (and stored (contains? #{"committed" "stale" "compensated" "recovery-required"}
                             (:status stored)))
      (fail :proposal-consumed {:id id})

      :else
      (fail :proposal-stale {:id id}))))

(defn- resolver-for [registry member]
  (or (get registry (:resolver member))
      (fail :unknown-scheme {:uri (:uri member)})))

(defn- publish! [registry ctx member]
  (let [resolver (resolver-for registry member)]
    (when-not (= :workspace (:resolver member))
      (fail :unsupported-change {:uri (:uri member)}))
    (validate-members! registry ctx [member])
    (when-let [target (:replacement-uri member)]
      (let [observation (resource/observe registry target ctx)]
        (when-not (= (:target-revision member) (:revision observation))
          (fail :stale-revision {:uri target :expected (:target-revision member)
                                 :actual (:revision observation)}))))
    (store/member-result! (:conn ctx) (:change-id member) (:uri member)
                          :publishing {:publication-revision (:replacement-revision member)})
    (let [revision (case (:operation member)
                     (:write :create) (workspace/publish-workspace resolver member)
                     :delete (workspace/delete-workspace resolver member)
                     :rename (workspace/rename-workspace resolver member)
                     (workspace/publish-workspace resolver member))]
      (store/member-result! (:conn ctx) (:change-id member) (:uri member)
                            :published {:publication-revision revision})
      (assoc member :publication-revision revision))))

(defn- compensate! [registry ctx change-id published]
  (reduce
   (fn [status member]
     (let [current-uri (if (= :rename (:operation member))
                         (:replacement-uri member)
                         (:uri member))
           current (resource/observe registry current-uri ctx)]
       (cond
         (= (:revision current)
            (if (= :rename (:operation member))
              (:target-revision member)
              (:base-revision member)))
         status

         (and (not= (:revision current) (:publication-revision member))
              (not (and (= :rename (:operation member))
                        (= (:revision current) (:base-revision member)))))
         (do (store/member-result! (:conn ctx) change-id (:uri member)
                                   :recovery-required {:error "resource changed after publication"})
             :recovery-required)

         :else
         (let [resolver (resolver-for registry member)
               restoration (assoc member
                                  :replacement (:original member)
                                  :replacement-revision
                                  (workspace/content-revision (:original member))
                                  :metadata (:metadata current))]
           (case (:operation member)
             :create (workspace/delete-workspace resolver restoration)
             :delete (workspace/publish-workspace resolver restoration)
             :rename (workspace/rename-workspace
                      resolver (assoc restoration
                                      :metadata {:path (get-in current [:metadata :path])
                                                 :target-path (get-in member [:metadata :path])}))
             (workspace/publish-workspace resolver restoration))
           (store/member-result! (:conn ctx) change-id (:uri member)
                                 :compensated {:publication-revision
                                               (workspace/content-revision (:original member))})
           status))))
   :compensated
   (reverse published)))

(defn- published-members [registry ctx change-id members]
  (let [by-uri (into {} (map (juxt :uri identity) members))]
    (mapv (fn [row]
            (let [member (get by-uri (:uri row))
                  observed (resource/observe registry (:uri row) ctx)]
              (merge member
                     {:resolver (:resolver observed)
                      :identity (:identity observed)
                      :metadata (:metadata observed)
                      :publication-revision (:publication_revision row)})))
          (filterv #(contains? #{"published" "publishing"} (:status %))
                   (store/change-members (:conn ctx) change-id)))))

(defn- safe-compensate! [registry ctx change-id published]
  (try
    (compensate! registry ctx change-id published)
    (catch Throwable e
      (store/finish-change! (:conn ctx) change-id :recovery-required)
      :recovery-required)))

(defn apply!
  "Consume and apply a proposal. Publication is atomic per member, not per set;
  failures compensate only states installed by this change."
  [registry ctx proposal]
  (locking mutation-lock
    (consumed! registry ctx proposal)
    (let [id (:id proposal)
          members (mapv #(assoc % :change-id id) (:members proposal))]
      (try
        (validate-members! registry ctx members)
        (store/finish-change! (:conn ctx) id :publishing)
        (try
          (let [published (reduce (fn [rows member]
                                    (conj rows (publish! registry ctx member)))
                                  [] members)]
            (store/finish-change! (:conn ctx) id :committed)
            (note! ctx :resource-change-committed {:change-id id})
            {:status :committed :id id})
          (catch Exception e
            (let [stale? (= :stale-revision (:resource/error (ex-data e)))
                  published (published-members registry ctx id members)
                  status (cond
                           (seq published) (safe-compensate! registry ctx id published)
                           stale? :stale
                           :else :compensated)]
              (store/finish-change! (:conn ctx) id status)
              (note! ctx :resource-change-recovery {:change-id id :status status})
              (cond-> {:status status :id id :error (ex-message e)}
                stale? (assoc :uri (:uri (ex-data e)))))))
        (catch clojure.lang.ExceptionInfo e
          (if (= :stale-revision (:resource/error (ex-data e)))
            (do (store/finish-change! (:conn ctx) id :stale)
                {:status :stale :id id :uri (:uri (ex-data e))})
            (throw e)))))))

(defn recover!
  "Compensate a durable interrupted publication without reusing a proposal."
  [registry ctx change-id]
  (locking mutation-lock
    (let [change (store/change-by-id (:conn ctx) change-id)]
      (when-not change
        (fail :unknown-change {:id change-id}))
      (when-not (= (owner ctx) {:run-id (:run_id change) :branch-id (:branch_id change)})
        (fail :proposal-owner {:id change-id}))
      (when-not (contains? #{"staged" "publishing" "recovery-required"} (:status change))
        (fail :invalid-change-status {:id change-id :status (:status change)}))
      (let [rows (store/change-members (:conn ctx) change-id)
            members (mapv (fn [row]
                            (let [observed (resource/observe registry (:uri row) ctx)]
                              {:change-id change-id :uri (:uri row)
                               :resolver (:resolver observed)
                               :identity (:identity observed)
                               :metadata (:metadata observed)
                               :original (:original_text row)
                               :replacement (:replacement_text row)
                               :operation (keyword (:operation row))
                               :replacement-uri (:replacement_uri row)
                               :base-revision (:base_revision row)
                               :publication-revision (:publication_revision row)
                               :status (:status row)}))
                          rows)
            conflict (some #(= "recovery-required" (:status %)) rows)
            published (filterv #(contains? #{"published" "publishing"} (:status %)) members)
            status (cond
                     conflict :recovery-required
                     (seq published) (safe-compensate! registry ctx change-id published)
                     :else :compensated)]
        (store/finish-change! (:conn ctx) change-id status)
        {:status status :id change-id}))))
