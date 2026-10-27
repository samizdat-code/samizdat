;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.diagnoses
  "Durable exact-owner diagnosis cases."
  (:require [clojure.data.json :as json]
            [samizdat.store.db :as db]))

(defn- write [value]
  (json/write-str value))

(defn create! [conn {:keys [run-id branch-id hypotheses]}]
  (let [id (str (random-uuid))
        now (db/now)]
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO diagnosis_cases
                     (id, run_id, branch_id, status, hypotheses, known, trace, created_at, updated_at)
                     VALUES (?, ?, ?, 'open', ?, '[]', '[]', ?, ?)"
                    id run-id branch-id (write hypotheses) now now]))
    id))

(defn finish! [conn id {:keys [status open known trace]}]
  (db/with-writer
    (db/execute! conn
                 ["UPDATE diagnosis_cases SET status = ?, open_hypotheses = ?, known = ?, trace = ?,
                   updated_at = ? WHERE id = ?"
                  (name status) (write open) (write known) (write trace) (db/now) id])))

(defn- read [text]
  (json/read-str text :key-fn keyword))

(defn diagnosis [conn id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM diagnosis_cases WHERE id = ?" id])]
    (update row :status keyword)
    (let [row (update row :status keyword)]
      (-> row
          (update :hypotheses read)
          (update :open_hypotheses read)
          (update :known read)
          (update :trace read)))))

(defn finish-and-return! [conn id result]
  (finish! conn id result)
  (assoc result :id id))
