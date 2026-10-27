;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.executions-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.oversight :as oversight]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.execution]
            [samizdat.agent.tools.resource]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.security.flow :as flow]
            [samizdat.security.policy :as policy]
            [samizdat.store.db :as db]
            [samizdat.store.executions :as executions]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.runs :as runs]))

(defn- database []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "executions"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    [conn run-id]))

(deftest completion-creates-one-exact-owner-receipt-and-delivery
  (let [[conn run-id] (database)]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "make test"}
                                        :recovery :safe-restart})]
        (executions/complete! conn id {:status :complete :artifacts ["shell-log:abc"]})
        (is (= 1 (executions/deliver-pending! conn id)))
        (is (= 0 (executions/deliver-pending! conn id)))
        (let [receipt (executions/receipt conn id)
              directive (first (db/fetch conn ["SELECT * FROM interventions WHERE kind = 'message'"]))]
          (is (= :complete (:status receipt)))
          (is (= run-id (:run_id directive)))
          (is (= "B1" (:branch_id directive)))
          (is (nil? (:delivered_at receipt)))
          (executions/acknowledge! conn directive)
          (is (= :delivered (:delivery_status (executions/receipt conn id))))
          (is (some? (:delivered_at (executions/receipt conn id))))))
      (finally (db/close conn)))))

(deftest unknown-mutating-work-requires-a-decision-safe-revival-consumes-claim
  (let [[conn run-id] (database)]
    (try
      (let [unknown (executions/start! conn {:run-id run-id :branch-id "B1"
                                             :kind "shell" :specification {:command "rm generated"}
                                             :recovery :unknown})]
        (is (= :decision-required (executions/continuity conn unknown)))
        (is (thrown? clojure.lang.ExceptionInfo
                     (executions/revive! conn unknown {:reason "uncertain outcome"})))
      (let [safe (executions/start! conn {:run-id run-id :branch-id "B1"
                                          :kind "shell" :specification {:command "make test"}
                                          :recovery :safe-restart})
            _ (executions/complete! conn safe {:status :complete :artifacts []})
            new-id (executions/revive! conn safe {:reason "retry after image loss"})]
        (is (some? new-id))
        (is (= safe (:revived_from (executions/execution conn new-id))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (executions/revive! conn safe {:reason "repeat without a new decision"})))))
      (finally (db/close conn)))))

(deftest receipt-intervention-ids-are-stable-nonzero-and-distinct
  (let [[conn run-id] (database)]
    (try
      (let [ids (repeatedly 2 #(executions/start!
                                conn {:run-id run-id :branch-id "B1"
                                      :kind "shell" :specification {:command "echo ok"}
                                      :recovery :safe-restart}))]
        (doseq [id ids]
          (executions/complete! conn id {:status :complete :artifacts []})
          (is (= 1 (executions/deliver-pending! conn id)))
          (is (= 0 (executions/deliver-pending! conn id))))
        (let [directive-ids (mapv :id (db/fetch conn ["SELECT id FROM interventions ORDER BY id"]))]
          (is (every? pos? directive-ids))
          (is (= 2 (count (distinct directive-ids))))))
      (finally (db/close conn)))))

(deftest coalesced-delivery-queues-one-message-without-a-provider-call
  (let [[conn run-id] (database)]
    (try
      (doseq [n (range 2)]
        (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                          :kind "inference"
                                          :specification {:task "classify" :generation n
                                                          :context-facts {:diagnostics ["warning"]}}
                                          :recovery :safe-restart})]
          (executions/complete! conn id {:status :complete :artifacts []})))
      (is (= 1 (executions/deliver-all! conn run-id "B1" {:coalesce? true})))
      (is (= 2 (count (db/fetch conn ["SELECT execution_id FROM execution_receipts
                                       WHERE delivery_status = 'queued'"]))))
      (let [directive (first (db/fetch conn ["SELECT * FROM interventions WHERE kind = 'message'"]))]
        (is (some? directive))
        (is (= 2 (count (:execution-notes (interventions/payload directive))))))
      (finally (db/close conn)))))

(deftest execution-tool-inspects-and-revives-only-with-a-safe-claim
  (let [[conn run-id] (database)]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "echo ok"}
                                        :recovery :safe-restart})
            _ (executions/complete! conn id {:status :complete :artifacts []})
            inspected (base/run-tool
                       {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                        :tool-name "execution" :args {:action "inspect" :id id}})
            revived (base/run-tool
                     {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                      :tool-name "execution" :args {:action "revive" :id id :reason "retry"}})
            new-id (get-in revived [:resource :new-id])]
        (is (= :neutral (:category inspected)))
        (is (= :safe-restart (get-in inspected [:resource :continuity])))
        (is (some? new-id))
        (is (= id (:revived_from (executions/execution conn new-id))))
      (with-redefs [samizdat.security.confine/backend (constantly :none)]
        (let [dispatched (base/run-tool
                          {:conn conn :run-id run-id :root "/tmp" :branch {:id "B1" :role :implementor}
                           :tool-name "execution" :args {:action "dispatch" :id new-id}})]
          (is (= :success (:category dispatched)) (pr-str dispatched))
          (is (= :complete (:status (executions/execution conn new-id)))))))
      (finally (db/close conn)))))

(deftest acknowledgment-resolves-and-delivers-atomically
  (let [[conn run-id] (database)]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "echo ok"}
                                        :recovery :safe-restart})
            _ (executions/complete! conn id {:status :complete :artifacts []})
            _ (executions/deliver-pending! conn id)
            directive (first (interventions/pending conn run-id "B1"))
            resolve! interventions/resolve!]
        (with-redefs [interventions/resolve!
                      (fn [& args]
                        (apply resolve! args)
                        (throw (ex-info "crash after application" {})))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (executions/acknowledge! conn directive 4))))
        (is (= "pending" (:status (first (interventions/pending conn run-id "B1")))))
        (is (= :queued (:delivery_status (executions/receipt conn id))))
        (is (= 1 (executions/acknowledge! conn directive 4)))
        (is (empty? (interventions/pending conn run-id "B1")))
        (is (= :delivered (:delivery_status (executions/receipt conn id)))))
      (finally (db/close conn)))))

(deftest acknowledgment-refuses-mismatched-and-cross-owner-receipts
  (let [[conn run-id] (database)]
    (try
      (runs/open-branch! conn run-id {:branch-id "B2"})
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "echo ok"}
                                        :recovery :safe-restart})
            _ (executions/complete! conn id {:status :complete :artifacts []})
            _ (executions/deliver-pending! conn id)
            forged-id (interventions/submit!
                       conn run-id {:branch-id "B2" :kind "message"
                                    :payload {:text "forged" :execution-id id}})
            forged (first (interventions/pending conn run-id "B2"))]
        (is (zero? (executions/acknowledge! conn forged 4)))
        (is (= :queued (:delivery_status (executions/receipt conn id))))
        (is (= "applied" (:status (db/fetch-one conn ["SELECT status FROM interventions WHERE id = ?" forged-id])))))
      (finally (db/close conn)))))

(deftest read-only-shell-producer-revives-with-readable-artifacts
  (let [[conn run-id] (database)
        ctx {:conn conn :run-id run-id :root "/tmp" :branch {:id "B1" :role :implementor}}]
    (try
      (with-redefs [samizdat.security.confine/backend (constantly :none)]
        (let [_ (flow/observe! ctx (flow/delta flow/table "webfetch" {:url "u"}) "webfetch")
              produced (policy/run-shell (assoc ctx :args {:command "echo first"}))
              old-id (get-in produced [:captures :id])
              revived (base/run-tool
                       (assoc ctx :tool-name "execution"
                              :args {:action "revive" :id old-id :reason "repeat read-only probe"}))
              new-id (get-in revived [:resource :new-id])
              dispatched (base/run-tool
                          (assoc ctx :tool-name "execution"
                                 :args {:action "dispatch" :id new-id}))
              artifacts (:artifacts (executions/receipt conn new-id))]
          (is (= :success (:category produced)))
          (is (= :revived (:recovery (executions/execution conn old-id))))
          (is (= :dispatched (:recovery (executions/execution conn new-id))))
          (is (= :success (:category dispatched)) (pr-str dispatched))
          (is (seq artifacts))
          (doseq [uri artifacts]
            (is (string? (resource-tool/read-view (assoc ctx :env {}) uri))))))
      (finally (db/close conn)))))

(deftest idle-delivery-defers-within-the-coalescing-window
  (let [[conn run-id] (database)]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "inference" :specification {:task "one"}
                                        :recovery :safe-restart})]
        (executions/complete! conn id {:status :complete :artifacts []})
        (is (= 1 (executions/deliver-all! conn run-id "B1"
                                          {:coalesce? true :defer? true
                                           :coalesce-window-ms 250 :now 1000})))
        (let [next-id (executions/start! conn {:run-id run-id :branch-id "B1"
                                               :kind "inference" :specification {:task "two"}
                                               :recovery :safe-restart})]
          (executions/complete! conn next-id {:status :complete :artifacts []})
          (is (zero? (executions/deliver-all! conn run-id "B1"
                                              {:coalesce? true :defer? true
                                               :coalesce-window-ms 250 :now 1100})))
          (is (= :pending (:delivery_status (executions/receipt conn next-id))))
          (is (= 1 (executions/deliver-all! conn run-id "B1"
                                            {:coalesce? true :defer? true
                                             :coalesce-window-ms 250 :now 1300})))))
      (finally (db/close conn)))))

(deftest coalescing-bounds-each-message-and-leaves-overflow-pending
  (let [[conn run-id] (database)]
    (try
      (doseq [n (range 3)]
        (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                          :kind "inference" :specification {:task (str "task-" n)}
                                          :recovery :safe-restart})]
          (executions/complete! conn id {:status :complete :artifacts [(str "inference:" id)]})))
      (is (= 1 (executions/deliver-all! conn run-id "B1"
                                        {:coalesce? true :max-batch 2 :max-message-chars 120})))
      (is (= 2 (count (db/fetch conn ["SELECT execution_id FROM execution_receipts WHERE delivery_status = 'queued'"]))))
      (is (= 1 (count (db/fetch conn ["SELECT execution_id FROM execution_receipts WHERE delivery_status = 'pending'"]))))
      (let [directive (first (interventions/pending conn run-id "B1"))]
        (is (<= (count (interventions/text-of directive)) 120)))
      (finally (db/close conn)))))

(deftest receipt-only-timer-delivers-without-a-provider
  (let [[conn run-id] (database)
        threshold gates/threshold]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "inference" :specification {:task "idle"}
                                        :recovery :safe-restart})]
        (executions/complete! conn id {:status :complete :artifacts []})
        (with-redefs [gates/threshold
                      (fn [k]
                        (if (= :async k)
                          {:coalesce? true :coalesce-window-ms 0 :poll-ms 10
                           :max-batch 16 :max-message-chars 12000}
                          (threshold k)))]
          (let [stop (oversight/completion-stream {:conn conn :run-id run-id})]
            (try
              (loop [attempt 0]
                (when (and (= :pending (:delivery_status (executions/receipt conn id)))
                           (< attempt 100))
                  (Thread/sleep 10)
                  (recur (inc attempt))))
              (is (= :queued (:delivery_status (executions/receipt conn id))))
              (finally (stop))))))
      (finally (db/close conn)))))

(deftest revival-claim-and-new-record-are-atomic
  (let [[conn run-id] (database)
        start! executions/start!]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "echo ok"}
                                        :recovery :safe-restart})]
        (executions/complete! conn id {:status :complete :artifacts []})
        (with-redefs [executions/start! (fn [& _] (throw (ex-info "crash during revival" {})))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (executions/revive! conn id {:reason "retry"}))))
        (is (= :safe-restart (:recovery (executions/execution conn id))))
        (is (= 1 (count (db/fetch conn ["SELECT id FROM executions"]))))
        (is (some? (start! conn {:run-id run-id :branch-id "B1"
                                 :kind "shell" :specification {:command "echo ok"}
                                 :recovery :safe-restart}))))
      (finally (db/close conn)))))

(deftest dispatch-claim-prevents-repeat-after-a-producer-crash
  (let [[conn run-id] (database)
        ctx {:conn conn :run-id run-id :root "/tmp" :branch {:id "B1" :role :implementor}
             :tool-name "execution"}]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "shell" :specification {:command "echo ok"}
                                        :recovery :safe-restart})
            _ (executions/complete! conn id {:status :complete :artifacts []})
            new-id (executions/revive! conn id {:reason "retry"})]
        (with-redefs [policy/run-shell (fn [_] (throw (ex-info "producer crashed" {})))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (base/run-tool (assoc ctx :args {:action "dispatch" :id new-id})))))
        (is (= :running (:status (executions/execution conn new-id))))
        (is (= :dispatched (:recovery (executions/execution conn new-id))))
        (is (= :decision-required (executions/continuity conn new-id)))
        (is (= :mechanics (:category (base/run-tool (assoc ctx :args {:action "dispatch" :id new-id}))))))
      (finally (db/close conn)))))

(deftest terminal-status-and-receipt-are-atomic
  (let [[conn run-id] (database)
        execute! db/execute!]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B1"
                                        :kind "inference" :specification {:task "one"}
                                        :recovery :safe-restart})]
        (with-redefs [db/execute!
                      (fn [conn statement]
                        (let [result (execute! conn statement)]
                          (when (clojure.string/includes? (first statement) "INSERT OR IGNORE INTO execution_receipts")
                            (throw (ex-info "crash after terminal update" {})))
                          result))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (executions/complete! conn id {:status :complete :artifacts []}))))
        (is (= :running (:status (executions/execution conn id))))
        (is (nil? (executions/receipt conn id)))
        (executions/complete! conn id {:status :complete :artifacts []})
        (is (= :complete (:status (executions/receipt conn id)))))
      (finally (db/close conn)))))
