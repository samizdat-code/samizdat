;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.executions
  "Durable execution records, receipts, and explicit revival claims."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.util :as util]
            [samizdat.store.db :as db]))

(defn- write [value]
  (json/write-str value))

(defn- read [value]
  (json/read-str value :key-fn keyword))

(defn- stable-intervention-id [text]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes (str text) "UTF-8"))]
    (bit-and Long/MAX_VALUE
             (loop [index 0 value 0]
               (if (= index 8)
                 value
                 (recur (inc index)
                        (bit-or (bit-shift-left value 8)
                                (bit-and 0xff (aget bytes index)))))))))

(defn start! [conn {:keys [id run-id branch-id kind specification lineage recovery revived-from]}]
  (let [id (or id (str (random-uuid)))
        now (db/now)]
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO executions
                     (id, run_id, branch_id, kind, specification, lineage, status, recovery,
                      revived_from, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, 'running', ?, ?, ?, ?)"
                    id run-id branch-id kind (write specification)
                    (some-> lineage write) (name (or recovery :unknown)) revived-from now now]))
    id))

(defn execution [conn id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM executions WHERE id = ?" id])]
    (-> row
        (update :status keyword)
        (update :recovery keyword)
        (update :specification read)
        (update :lineage #(when % (read %))))))

(defn complete! [conn id {:keys [status artifacts]}]
  (let [now (db/now)]
    (db/atomic
     conn
     (fn []
       (db/execute! conn ["UPDATE executions SET status = ?, updated_at = ?
                           WHERE id = ? AND status = 'running'"
                          (name (or status :complete)) now id])
       (db/execute! conn
                    ["INSERT OR IGNORE INTO execution_receipts
                      (execution_id, status, artifacts, delivery_status, created_at)
                      VALUES (?, ?, ?, 'pending', ?)"
                     id (name (or status :complete)) (write (or artifacts [])) now])))))

(defn receipt [conn id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM execution_receipts WHERE execution_id = ?" id])]
    (-> row
        (update :status keyword)
        (update :delivery_status keyword)
        (update :artifacts read))))

(defn continuity [conn id]
  (let [{:keys [status recovery revived_from]} (execution conn id)]
    (cond
      (= :unknown recovery) :decision-required
      (= :dispatched recovery) :decision-required
      (and (= :running status) revived_from (= :safe-restart recovery)) :safe-restart
      (= :running status) :live-owned
      (= :safe-restart recovery) :safe-restart
      :else :decision-required)))

(defn revive! [conn id {:keys [reason]}]
  (when (str/blank? (str reason))
    (throw (ex-info "revival requires a rationale" {:execution/id id})))
  (db/atomic
   conn
   (fn []
     (let [claimed (db/execute! conn
                                ["UPDATE executions SET recovery = 'revived', revival_reason = ?, updated_at = ?
                                  WHERE id = ? AND recovery = 'safe-restart' AND status != 'running'"
                                 (str reason) (db/now) id])]
       (when (zero? claimed)
         (throw (ex-info "execution cannot be revived without a safe claim"
                         {:execution/id id :reason reason})))
       (let [{:keys [run_id branch_id kind specification lineage]} (execution conn id)]
         (start! conn {:run-id run_id :branch-id branch_id :kind kind
                       :specification specification :lineage lineage
                       :recovery :safe-restart :revived-from id}))))))

(defn claim-dispatch! [conn id]
  (pos? (db/with-writer
          (db/execute! conn ["UPDATE executions SET recovery = 'dispatched', updated_at = ?
                              WHERE id = ? AND revived_from IS NOT NULL
                                AND recovery = 'safe-restart' AND status = 'running'"
                             (db/now) id]))))

(defn- intervention [conn id]
  (db/fetch-one conn ["SELECT id FROM interventions WHERE id = ?" id]))

(defn- queue! [conn run-id execution-ids owner]
  (db/atomic
   conn
   (fn []
     (when-not (intervention conn (:id owner))
       ((requiring-resolve 'samizdat.store.interventions/submit!) conn run-id owner))
     (doseq [execution-id execution-ids]
       (db/execute! conn ["UPDATE execution_receipts
                           SET delivery_status = 'queued', intervention_id = ? WHERE execution_id = ?"
                          (:id owner) execution-id])))))

(defn- completion-text [vars max-message-chars]
  (cond-> ((requiring-resolve 'samizdat.prompt/render) "execution-tool" vars)
    max-message-chars (util/truncate-middle max-message-chars)))

(defn deliver-pending!
  ([conn id] (deliver-pending! conn id {}))
  ([conn id {:keys [max-message-chars]}]
   (if-let [{:keys [delivery_status status artifacts]} (receipt conn id)]
     (if (not= :pending delivery_status)
       0
       (let [{:keys [run_id branch_id specification]} (execution conn id)]
         (queue! conn run_id [id]
                 {:id (stable-intervention-id id)
                  :branch-id branch_id :kind "message"
                  :payload (merge {:text (completion-text {:completion true :execution-id id
                                                           :status (name status)
                                                           :artifacts (when (seq artifacts) (pr-str artifacts))}
                                                          max-message-chars)
                                   :execution-id id :artifacts artifacts}
                                  (select-keys specification [:generation :context-facts]))
                  :issued-by "execution"})
         1))
     0)))

(defn pending-receipts
  ([conn run-id branch-id] (pending-receipts conn run-id branch-id nil))
  ([conn run-id branch-id limit]
   (mapv (fn [row]
           (-> row
               (update :status keyword)
               (update :delivery_status keyword)
               (update :artifacts read)))
         (db/fetch conn
                   (cond-> [(str "SELECT e.*, x.specification FROM execution_receipts e
                                  JOIN executions x ON x.id = e.execution_id
                                  WHERE x.run_id = ? AND x.branch_id = ? AND e.delivery_status = 'pending'
                                  ORDER BY e.created_at"
                                 (when limit " LIMIT ?"))
                            run-id branch-id]
                     limit (conj limit))))))

(defn- queue-group! [conn run-id branch-id rows {:keys [max-message-chars]}]
  (let [ids (mapv :execution_id rows)
        group-id (stable-intervention-id (str "coalesced-" (str/join "," ids)))
        artifacts (mapv (fn [{:keys [execution_id artifacts]}]
                          [execution_id artifacts])
                        rows)
        notes (keep (fn [{:keys [execution_id specification]}]
                      (let [{:keys [generation context-facts]} (read specification)]
                        (when (and generation context-facts)
                          {:execution-id execution_id :generation generation
                           :context-facts context-facts})))
                    rows)]
    (queue! conn run-id ids
            {:id group-id
             :branch-id branch-id :kind "message"
             :payload (cond-> {:text (completion-text {:coalesced true
                                                       :execution-ids (pr-str ids)
                                                       :artifacts (pr-str artifacts)}
                                                      max-message-chars)
                               :execution-ids ids :artifacts artifacts}
                        (seq notes) (assoc :execution-notes (vec notes)))
             :issued-by "execution"}))
  1)

(defonce ^:private last-delivery (atom {}))

(defn forget-run! [run-id]
  (swap! last-delivery
         (fn [entries]
           (into {} (remove (fn [[[rid _] _]] (= rid run-id)) entries)))))

(defn deliver-all!
  "Queue a bounded batch of pending receipts for one exact owner."
  [conn run-id branch-id {:keys [coalesce? coalesce-window-ms defer? max-batch now]
                          :as opts}]
  (let [now (or now (System/currentTimeMillis))
        previous (get @last-delivery [run-id branch-id] 0)]
    (if (and defer? coalesce-window-ms (< (- now previous) coalesce-window-ms))
      0
      (let [rows (pending-receipts conn run-id branch-id max-batch)]
        (when (seq rows)
          (swap! last-delivery assoc [run-id branch-id] now)
          (if coalesce?
            (queue-group! conn run-id branch-id rows opts)
            (reduce + 0 (map #(deliver-pending! conn (:execution_id %) opts) rows))))))))

(defn acknowledge!
  ([conn directive] (acknowledge! conn directive nil))
  ([conn directive turn]
   (db/atomic
    conn
    (fn []
      (let [payload ((requiring-resolve 'samizdat.store.interventions/payload) directive)
            ids (concat (when (:execution-id payload) [(:execution-id payload)])
                        (:execution-ids payload))
            delivered
            (reduce
             (fn [n id]
               (db/execute! conn ["UPDATE execution_receipts AS r
                                   SET delivery_status = 'delivered', delivered_at = ?
                                   WHERE r.execution_id = ? AND r.delivery_status = 'queued'
                                     AND r.intervention_id = ?
                                     AND EXISTS (SELECT 1 FROM executions x
                                                 WHERE x.id = r.execution_id
                                                   AND x.run_id = ? AND x.branch_id = ?)"
                                  (db/now) id (:id directive)
                                  (:run_id directive) (:branch_id directive)])
               (+ n (db/change-count conn)))
             0 ids)]
        ((requiring-resolve 'samizdat.store.interventions/resolve!)
         conn (:run_id directive) (:id directive) :applied nil turn)
        delivered)))))
