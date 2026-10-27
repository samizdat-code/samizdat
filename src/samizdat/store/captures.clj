;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.captures
  "Durable shell capture metadata and retention transitions."
  (:require [samizdat.store.db :as db]))

(defn create! [conn {:keys [id run-id branch-id stdout-path stderr-path
                            stdout-read-path stderr-read-path created-at]}]
  (let [now (or created-at (db/now))]
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO shell_captures
                     (id, run_id, branch_id, status, stdout_path, stderr_path,
                      stdout_read_path, stderr_read_path, created_at, updated_at)
                     VALUES (?, ?, ?, 'running', ?, ?, ?, ?, ?, ?)"
                    id run-id branch-id stdout-path stderr-path
                    stdout-read-path stderr-read-path now now]))
    id))

(defn capture [conn id]
  (db/fetch-one conn ["SELECT * FROM shell_captures WHERE id = ?" id]))

(defn finish!
  ([conn id status streams] (finish! conn id status streams nil))
  ([conn id status streams {:keys [stdout-read-path stderr-read-path]}]
   (let [now (db/now)]
     (db/with-writer
       (db/execute! conn
                    ["UPDATE shell_captures SET status = ?,
                      stdout_observed = ?, stdout_retained = ?,
                      stderr_observed = ?, stderr_retained = ?,
                      stdout_read_path = COALESCE(?, stdout_read_path),
                      stderr_read_path = COALESCE(?, stderr_read_path),
                      updated_at = ?, completed_at = ? WHERE id = ?"
                     (name status)
                     (get-in streams [:stdout :observed-bytes] 0)
                     (get-in streams [:stdout :retained-bytes] 0)
                     (get-in streams [:stderr :observed-bytes] 0)
                     (get-in streams [:stderr :retained-bytes] 0)
                     stdout-read-path stderr-read-path now now id])))))

(defn mark-delivered! [conn id]
  (db/with-writer
    (db/execute! conn ["UPDATE shell_captures SET delivered = 1, updated_at = ? WHERE id = ?"
                       (db/now) id])))

(defn live [conn]
  (db/fetch conn ["SELECT * FROM shell_captures WHERE status != 'pruned' ORDER BY created_at"]))

(defn pruned [conn]
  (db/fetch conn ["SELECT * FROM shell_captures WHERE status = 'pruned' ORDER BY created_at"]))

(defn- file-size [path]
  (let [file (java.io.File. (str path))]
    (if (.isFile file) (.length file) 0)))

(defn- total-bytes [row]
  (+ (:stdout_retained row) (:stderr_retained row)
     (if (and (:stdout_read_path row)
              (not= (:stdout_path row) (:stdout_read_path row)))
       (file-size (:stdout_read_path row)) 0)
     (if (and (:stderr_read_path row)
              (not= (:stderr_path row) (:stderr_read_path row)))
       (file-size (:stderr_read_path row)) 0)))

(defn- over-capacity [rows cap]
  (let [total (reduce + 0 (map total-bytes rows))]
    (loop [remaining total
           oldest-first (reverse rows)
           pruned []]
      (if (and (> remaining cap) (seq oldest-first))
        (let [oldest (first oldest-first)]
          (recur (- remaining (total-bytes oldest))
                 (rest oldest-first)
                 (conj pruned (:id oldest))))
        pruned))))

(defn prune!
  "Prune delivered captures outside age, count, or retained-byte policy."
  [conn {:keys [max-age-ms max-count max-capacity-bytes now exclude]} delete-path]
  (let [rows (db/fetch conn ["SELECT * FROM shell_captures
                              WHERE delivered = 1 AND status != 'pruned'
                              ORDER BY created_at DESC, id DESC"])
        newest-first (vec rows)
        aged (when (and max-age-ms now)
               (let [cutoff (db/iso-millis
                             (java.time.Instant/ofEpochMilli
                              (- (.toEpochMilli (java.time.Instant/parse now))
                                 (long max-age-ms))))]
                 (keep #(when (neg? (compare (:created_at %) cutoff)) (:id %)) rows)))
        excess-count (when max-count
                       (map :id (drop (max 0 (long max-count)) newest-first)))
        excess-capacity (when max-capacity-bytes
                          (over-capacity newest-first (max 0 (long max-capacity-bytes))))
        pending (keep #(when (= "prune-pending" (:status %)) (:id %)) rows)
        ids (set (remove (set exclude) (concat pending aged excess-count excess-capacity)))
        by-id (into {} (map (juxt :id identity) rows))]
    (doseq [id ids]
      (db/with-writer
        (db/execute! conn ["UPDATE shell_captures SET status = 'prune-pending', updated_at = ? WHERE id = ?"
                           (db/now) id]))
      (let [row (get by-id id)
            paths (distinct [(:stdout_path row) (:stderr_path row)
                             (:stdout_read_path row) (:stderr_read_path row)])]
        (when (every? delete-path (remove nil? paths))
          (db/with-writer
            (db/execute! conn ["UPDATE shell_captures SET status = 'pruned', updated_at = ? WHERE id = ?"
                               (db/now) id])))))
    (vec ids)))

(defn- delete-file [path]
  (try
    (java.nio.file.Files/deleteIfExists (.toPath (java.io.File. (str path))))
    true
    (catch Throwable _ false)))

(defn deliver!
  "Record journal delivery, then prune older captures without deleting the new reference."
  [conn id policy]
  (mark-delivered! conn id)
  (prune! conn (assoc policy :now (str (java.time.Instant/now)) :exclude #{id})
          delete-file))
