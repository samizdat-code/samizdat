;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.debug
  "Durable debug event artifacts."
  (:require [clojure.data.json :as json]
            [samizdat.store.db :as db]))

(defn record-event! [conn session-id run-id branch-id {:keys [event body] :as value}]
  (let [body (json/write-str (or body {}))]
    (db/with-writer
      (db/execute! conn ["INSERT INTO debug_events
                          (session_id, run_id, branch_id, event, body, created_at)
                          VALUES (?, ?, ?, ?, ?, ?)"
                         session-id run-id branch-id (str event) body (db/now)]))
    (assoc value :body (json/read-str body :key-fn keyword))))

(defn events [conn session-id]
  (mapv #(update % :body json/read-str :key-fn keyword)
        (db/fetch conn ["SELECT id, session_id, event, body, created_at FROM debug_events
                         WHERE session_id = ? ORDER BY id" session-id])))
