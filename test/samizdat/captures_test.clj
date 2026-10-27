;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.captures-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.store.captures :as captures]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- database []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "captures"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    [conn run-id]))

(defn- capture [run-id id created-at]
  {:id id :run-id run-id :branch-id "B1"
   :stdout-path (str "/tmp/" id ".out") :stderr-path (str "/tmp/" id ".err")
   :created-at created-at})

(deftest capture-lifecycle-retains-counts-and-delivery
  (let [[conn run-id] (database)]
    (try
      (captures/create! conn (capture run-id "c1" "2026-01-01T00:00:00.000Z"))
      (is (= "running" (:status (captures/capture conn "c1"))))
      (captures/finish! conn "c1" :timeout
                        {:stdout {:observed-bytes 9 :retained-bytes 4}
                         :stderr {:observed-bytes 3 :retained-bytes 3}})
      (let [row (captures/capture conn "c1")]
        (is (= "timeout" (:status row)))
        (is (= 9 (:stdout_observed row)))
        (is (= 4 (:stdout_retained row)))
        (is (= 3 (:stderr_observed row))))
      (captures/mark-delivered! conn "c1")
      (is (= 1 (:delivered (captures/capture conn "c1"))))
      (finally (db/close conn)))))

(deftest pruning-protects-active-and-undelivered-evidence
  (let [[conn run-id] (database)
        deleted (atom [])]
    (try
      (doseq [id ["active" "undelivered" "old" "new"]]
        (captures/create! conn (capture run-id id
                                        (if (= "old" id)
                                          "2020-01-01T00:00:00.000Z"
                                          "2026-01-01T00:00:00.000Z")))
        (when-not (= "active" id)
          (captures/finish! conn id :complete
                            {:stdout {:observed-bytes 10 :retained-bytes 10}
                             :stderr {:observed-bytes 10 :retained-bytes 10}}))
        (when (#{"old" "new"} id)
          (captures/mark-delivered! conn id)))
      (captures/prune! conn {:max-age-ms 1000 :max-count 10 :max-capacity-bytes 1000
                             :now "2026-01-01T00:00:00.000Z"}
                       #(swap! deleted conj %))
      (is (= ["old"] (mapv :id (captures/pruned conn))))
      (is (= 2 (count @deleted)))
      (is (= #{"active" "undelivered" "new"}
             (set (map :id (captures/live conn)))))
      (captures/prune! conn {:max-count 0 :max-capacity-bytes 1000
                             :now "2026-01-01T00:00:00.000Z"}
                       #(swap! deleted conj %))
      (is (= #{"old" "new"} (set (map :id (captures/pruned conn)))))
      (finally (db/close conn)))))

(deftest capacity-pruning-removes-oldest-delivered-captures-first
  (let [[conn run-id] (database)]
    (try
      (doseq [[id day] [["small" "01"] ["large" "02"]]]
        (captures/create! conn (capture run-id id (str "2026-01-" day "T00:00:00.000Z")))
        (captures/finish! conn id :complete
                          {:stdout {:observed-bytes (if (= "small" id) 5 20)
                                    :retained-bytes (if (= "small" id) 5 20)}
                           :stderr {:observed-bytes 0 :retained-bytes 0}})
        (captures/mark-delivered! conn id))
      (captures/prune! conn {:max-capacity-bytes 10 :max-count 10
                             :now "2026-01-03T00:00:00.000Z"}
                       (constantly true))
      (is (= #{"small" "large"} (set (map :id (captures/pruned conn)))))
      (is (empty? (captures/live conn)))
      (finally (db/close conn)))))

(deftest failed-deletion-stays-pending-and-can-be-retried
  (let [[conn run-id] (database)]
    (try
      (captures/create! conn (capture run-id "c1" "2020-01-01T00:00:00.000Z"))
      (captures/finish! conn "c1" :complete
                        {:stdout {:observed-bytes 1 :retained-bytes 1}
                         :stderr {:observed-bytes 0 :retained-bytes 0}})
      (captures/mark-delivered! conn "c1")
      (let [policy {:max-age-ms 0 :max-count 10 :max-capacity-bytes 100
                    :now "2026-01-01T00:00:00.000Z"}]
        (captures/prune! conn policy (constantly false))
        (is (= "prune-pending" (:status (captures/capture conn "c1"))))
        (captures/prune! conn policy (constantly true))
        (is (= "pruned" (:status (captures/capture conn "c1")))))
      (finally (db/close conn)))))

(deftest delivery-prunes-older-captures-not-the-new-reference
  (let [[conn run-id] (database)]
    (try
      (doseq [id ["old" "new"]]
        (captures/create! conn (capture run-id id "2020-01-01T00:00:00.000Z"))
        (captures/finish! conn id :complete
                          {:stdout {:observed-bytes 1 :retained-bytes 1}
                           :stderr {:observed-bytes 0 :retained-bytes 0}}))
      (captures/mark-delivered! conn "old")
      (captures/deliver! conn "new" {:max-count 0 :max-capacity-bytes 100})
      (is (= ["old"] (mapv :id (captures/pruned conn))))
      (is (= ["new"] (mapv :id (captures/live conn))))
      (finally (db/close conn)))))

(deftest new-captures-count-toward-budgets-without-being-deleted
  (let [[conn run-id] (database)]
    (try
      (doseq [id ["c1" "c2"]]
        (captures/create! conn (capture run-id id "2020-01-01T00:00:00.000Z"))
        (captures/finish! conn id :complete
                          {:stdout {:observed-bytes 1 :retained-bytes 1}
                           :stderr {:observed-bytes 0 :retained-bytes 0}})
        (captures/deliver! conn id {:max-count 1 :max-capacity-bytes 100}))
      (is (= ["c1"] (mapv :id (captures/pruned conn))))
      (is (= ["c2"] (mapv :id (captures/live conn))))
      (finally (db/close conn)))))
