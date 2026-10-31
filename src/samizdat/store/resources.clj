;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.resources
  "Durable intent and member outcomes for revisioned resource changes."
  (:require [samizdat.store.db :as db]))

(defn- in-transaction [conn f]
  (db/with-writer
    (db/execute! conn ["BEGIN IMMEDIATE"])
    (try
      (let [value (f)]
        (db/execute! conn ["COMMIT"])
        value)
      (catch Throwable e
        (try (db/execute! conn ["ROLLBACK"]) (catch Throwable _ nil))
        (throw e)))))

(defn begin-change!
  "Record a staged change and its original snapshots. Returns the change id."
  [conn {:keys [run-id branch-id]} members]
  (let [id (str (random-uuid))
        now (db/now)]
    (in-transaction
     conn
     (fn []
       (db/execute! conn
                    ["INSERT INTO resource_changes
                      (id, run_id, branch_id, status, created_at, updated_at)
                      VALUES (?, ?, ?, 'staged', ?, ?)"
                     id run-id branch-id now now])
       (doseq [[ordinal {:keys [uri base-revision original replacement operation replacement-uri metadata]}]
               (map-indexed vector members)]
         (db/execute! conn
                      ["INSERT INTO resource_change_members
                        (change_id, ordinal, uri, base_revision, original_text,
                         replacement_text, operation, replacement_uri, metadata,
                         publication_revision, status, detail)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 'staged', NULL)"
                       id ordinal uri base-revision original replacement
                       (name (or operation :write)) replacement-uri (pr-str metadata)]))
       id))))

(defn member-result!
  "Record one member's publication or compensation outcome."
  [conn change-id uri status detail]
  (db/with-writer
    (db/execute! conn
                 ["UPDATE resource_change_members
                   SET status = ?, publication_revision = ?, detail = ?
                   WHERE change_id = ? AND uri = ?"
                  (name status)
                  (when (map? detail) (:publication-revision detail))
                  (when (map? detail) (:error detail) detail)
                  change-id uri])))

(defn finish-change!
  "Set the terminal or recovery-required status for a change."
  [conn change-id status]
  (db/with-writer
    (db/execute! conn
                 ["UPDATE resource_changes SET status = ?, updated_at = ? WHERE id = ?"
                  (name status) (db/now) change-id])))

(defn unfinished-changes
  "Changes requiring completion or an explicit recovery decision. An older
  database without the recovery table has no such changes to resume."
  [conn {:keys [run-id branch-id]}]
  (if (some #{"resource_changes"} (db/table-names conn))
    (db/fetch conn
              ["SELECT * FROM resource_changes
                WHERE run_id = ? AND branch_id = ?
                  AND status IN ('staged', 'publishing', 'recovery-required')
                ORDER BY created_at, id"
               run-id branch-id])
    []))

(defn change-by-id [conn id]
  (db/fetch-one conn ["SELECT * FROM resource_changes WHERE id = ?" id]))

(defn change-members [conn id]
  (db/fetch conn
            ["SELECT * FROM resource_change_members WHERE change_id = ? ORDER BY ordinal" id]))
