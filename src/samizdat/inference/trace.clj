;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.trace
  "Durable inference frames, views, and content-addressed external context."
  (:require [clojure.data.json :as json]
            [samizdat.store.db :as db]))

(defn- digest [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes ^String (str text) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn start-frame!
  "Start a private frame and return its id."
  [conn {:keys [id run-id branch-id parent-id task schema role model]}]
  (let [id (or id (str (random-uuid)))]
    (when conn
      (let [now (db/now)]
        (db/with-writer
          (db/execute! conn
                       ["INSERT INTO inference_frames
                         (id, run_id, branch_id, parent_id, task, schema, role, model,
                          status, created_at, updated_at)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'running', ?, ?)"
                        id run-id branch-id parent-id (str task)
                        (some-> schema pr-str) (some-> role name) (some-> model str) now now])))
    id)))

(defn record-view!
  "Record one immutable context view and its content digest."
  [conn frame-id {:keys [label content]}]
  (when conn
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO inference_views (frame_id, label, digest, content)
                     VALUES (?, ?, ?, ?)"
                    frame-id (str label) (digest content) (str content)]))
  nil))

(defn finish-frame!
  "Finish a frame with a portable result and provider usage."
  [conn id status result usage]
  (when conn
    (db/with-writer
      (db/execute! conn
                   ["UPDATE inference_frames
                     SET status = ?, result = ?, usage = ?, updated_at = ? WHERE id = ?"
                    (name status) (json/write-str result) (json/write-str usage) (db/now) id]))))

(defn record-attempt!
  "Retain one provider request and its raw response or failure."
  [conn frame-id attempt messages reply error]
  (when conn
    (db/with-writer
      (db/execute! conn
                   ["INSERT INTO inference_attempts
                     (frame_id, attempt, messages, response, error, usage, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)"
                    frame-id attempt (json/write-str messages)
                    (some-> reply :content) (some-> error str)
                    (some-> reply :usage json/write-str) (db/now)]))))

(defn frame
  "A frame and its immutable views."
  [conn id]
  (when-let [row (db/fetch-one conn ["SELECT * FROM inference_frames WHERE id = ?" id])]
    (assoc row :parent-id (:parent_id row)
           :views (db/fetch conn ["SELECT label, digest, content FROM inference_views
                                   WHERE frame_id = ? ORDER BY id" id])
           :attempts (db/fetch conn ["SELECT attempt, messages, response, error, usage, created_at
                                      FROM inference_attempts WHERE frame_id = ? ORDER BY attempt" id]))))

(defn intern-context!
  "Store content once and return its content digest."
  ([conn label content] (intern-context! conn label content nil))
  ([conn label content owner]
   (let [id (digest content)
         now (db/now)]
     (when conn
       (db/with-writer
         (db/execute! conn
                      ["INSERT OR IGNORE INTO inference_contexts
                        (digest, label, content, run_id, branch_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)"
                       id (str label) (str content)
                       (:run-id owner) (:branch-id owner) now])
         (when (and (:run-id owner) (:branch-id owner))
           (db/execute! conn
                        ["INSERT OR IGNORE INTO inference_context_owners
                          (digest, run_id, branch_id, created_at) VALUES (?, ?, ?, ?)"
                         id (:run-id owner) (:branch-id owner) now]))))
     id)))

(defn context
  ([conn digest]
   (db/fetch-one conn ["SELECT * FROM inference_contexts WHERE digest = ?" digest]))
  ([conn digest owner]
   (db/fetch-one conn
                 ["SELECT c.*, o.run_id, o.branch_id FROM inference_contexts c
                   JOIN inference_context_owners o ON o.digest = c.digest
                   WHERE c.digest = ? AND o.run_id = ? AND o.branch_id = ?"
                  digest (:run-id owner) (:branch-id owner)])))
