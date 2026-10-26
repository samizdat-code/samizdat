;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.activity
  "A shared active-run projection over durable run and journal rows."
  (:require [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defn- projection [conn run]
  (let [run-id (:id run)
        branches (runs/branches conn run-id)]
    (-> run
        (select-keys [:id :problem :status :provider :model :started_at :ended_at])
        (assoc :last_progress_at (runs/last-progress-at conn run-id)
               :active_branches (count (filter #(= "active" (:status %)) branches))
               :branches (mapv #(select-keys % [:id :parent_id :status :role :problem :thesis])
                               branches)))))

(defn snapshot
  "Return active runs and the journal cursor for live cursor-based subscriptions.

  With RUN-ID, return only that run's preview; otherwise return every running
  run. The cursor is global for an all-run snapshot and run-scoped for a preview."
  ([conn] (snapshot conn nil))
  ([conn run-id]
   (let [rows (if run-id
                (if-let [run (runs/get-run conn run-id)] [run] [])
                (db/fetch conn ["SELECT * FROM runs WHERE status = 'running'
                                 ORDER BY started_at DESC"]))]
     {:runs (mapv #(projection conn %) rows)
      :cursor (journal/last-event-id conn run-id)})))
