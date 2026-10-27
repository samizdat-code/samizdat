;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.evidence
  "Durable evidence and premise edges with local status projection."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]))

(def ^:private source-types #{:file :user :url :tool :artifact :resource})

(defn validate! [{:keys [source-type source-id path revision]}]
  (when-not (contains? source-types source-type)
    (throw (ex-info "unknown evidence source" {:source-type source-type})))
  (when (and (= :file source-type) (or (str/blank? path) (str/blank? revision)))
    (throw (ex-info "file evidence needs path and revision" {:path path :revision revision})))
  (when (and (#{:tool :artifact :resource} source-type)
             (str/blank? (str source-id)))
    (throw (ex-info "evidence needs a stable record" {:source-type source-type}))))

(defn add! [conn claim-id {:keys [source-type source-id path quotation revision
                                  observed-at expires-at disputed? metadata]
                          :as evidence}]
  (validate! evidence)
  (let [id (str (random-uuid))]
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO knowledge_evidence
                     (id, claim_id, source_type, source_id, path, quotation, revision,
                      observed_at, expires_at, disputed, metadata)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    id claim-id (name source-type) source-id path quotation revision
                    (or observed-at (db/now)) expires-at (if disputed? 1 0)
                    (some-> metadata pr-str)]))
    id))

(defn add-premise! [conn claim-id premise-id]
  (db/with-writer
    (db/execute! conn ["INSERT OR IGNORE INTO knowledge_premises
                        (claim_id, premise_id, created_at) VALUES (?, ?, ?)"
                       claim-id premise-id (db/now)])))

(defn for-claim [conn claim-id]
  (db/fetch conn ["SELECT * FROM knowledge_evidence WHERE claim_id = ? ORDER BY observed_at, id"
                  claim-id]))

(defn premises-for [conn claim-id]
  (db/fetch conn ["SELECT * FROM knowledge_premises WHERE claim_id = ?" claim-id]))

(defn dependents-for [conn premise-id]
  (db/fetch conn ["SELECT * FROM knowledge_premises WHERE premise_id = ?" premise-id]))

(defn- expired? [row now]
  (and (:expires_at row)
       (neg? (compare (:expires_at row) now))))

(defn- record-status [conn row]
  (let [table (case (:source_type row)
                "tool" "turns"
                "artifact" "artifacts"
                "resource" "resource_changes"
                nil)]
    (if (and table (:source_id row)
             (db/fetch-one conn [(str "SELECT id FROM " table " WHERE id = ?")
                                 (:source_id row)]))
      :current
      :unverified)))

(defn- evidence-status [conn row {:keys [check-file now]}]
  (cond
    (pos? (:disputed row)) :conflicted
    (expired? row now) :stale
    (= "file" (:source_type row)) ((or check-file (constantly :unverified)) row)
    (and (= "url" (:source_type row)) (str/blank? (:quotation row))) :unverified
    (#{"tool" "artifact" "resource"} (:source_type row)) (record-status conn row)
    :else :current))

(defn file-checker
  "Validate file evidence against current project bytes without running a tool."
  [root]
  (fn [{:keys [path revision]}]
    (if-let [absolute (files/resolve-under-root root path)]
      (if-not (.isFile (java.io.File. absolute))
        :unverified
        (if (= revision (workspace/content-revision (slurp absolute)))
          :current
          :stale))
      :unverified)))

(defn- precedence [statuses]
  (cond
    (empty? statuses) :unverified
    (some #{:conflicted} statuses) :conflicted
    (some #{:stale} statuses) :stale
    (some #{:unverified} statuses) :unverified
    (some #{:retired} statuses) :unverified
    :else :current))

(defn- status* [conn claim-id opts visited]
  (let [claim (db/fetch-one conn ["SELECT current FROM knowledge WHERE id = ?" claim-id])]
    (cond
      (nil? claim) :unverified
      (zero? (:current claim)) :retired
      (contains? visited claim-id) :conflicted
      :else
      (let [opts (assoc opts :now (or (:now opts) (db/now)))
            evidence-statuses (map #(evidence-status conn % opts) (for-claim conn claim-id))
            premise-statuses (map #(status* conn (:premise_id %) opts (conj visited claim-id))
                                  (premises-for conn claim-id))]
        (precedence (concat evidence-statuses premise-statuses))))))

(defn status
  ([conn claim-id] (status conn claim-id {}))
  ([conn claim-id opts]
   (status* conn claim-id opts #{})))

(defn last-delivery [conn claim-id]
  (db/fetch-one conn ["SELECT status, delivered_at FROM knowledge_status_deliveries
                       WHERE claim_id = ?" claim-id]))

(defn record-delivery! [conn claim-id status]
  (db/with-writer
    (db/execute! conn
                 ["INSERT INTO knowledge_status_deliveries (claim_id, status, delivered_at)
                   VALUES (?, ?, ?)
                   ON CONFLICT(claim_id) DO UPDATE SET status = excluded.status,
                   delivered_at = excluded.delivered_at"
                  claim-id (name status) (db/now)])))

(defn status-change [conn claim-id status]
  (let [previous (:status (last-delivery conn claim-id))]
    {:status status :previous (some-> previous keyword)
     :changed? (and previous (not= status (keyword previous)))}))

(defn explain
  ([conn claim-id] (explain conn claim-id {}))
  ([conn claim-id opts]
   (let [status (status conn claim-id opts)
         evidence (mapv #(assoc % :status (evidence-status conn % (assoc opts :now (or (:now opts) (db/now)))))
                        (for-claim conn claim-id))
         reason (or (some #(when (not= :current (:status %))
                            (str (:source_type %) " evidence is " (name (:status %)))) evidence)
                    (when (not= :current status) (str "premises are " (name status))))]
     {:claim-id claim-id :status status :reason reason :evidence evidence
      :premises (premises-for conn claim-id)
      :dependents (dependents-for conn claim-id)})))
