;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-trace-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.inference.trace :as trace]
            [samizdat.resource :as resource]
            [samizdat.resource.inference :as inference]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- database []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "inference"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    [conn run-id]))

(deftest frames-retain-lineage-views-results-and-bounded-resource-reads
  (let [[conn run-id] (database)
        parent (trace/start-frame! conn {:run-id run-id :branch-id "B1" :task "parent"})
        child (trace/start-frame! conn {:run-id run-id :branch-id "B1"
                                        :parent-id parent :task "child" :role :reader :model "m"})]
    (try
      (trace/record-view! conn child {:label "source" :content "abcdef"})
      (trace/finish-frame! conn child :complete {:answer 42} {:total-tokens 12})
      (is (= parent (:parent-id (trace/frame conn child))))
      (let [registry (resource/registry {:inference (inference/resolver conn)})
            observation (resource/observe registry (str "inference:" child) {})
            window (resource/read-window registry observation {} {:offset 0 :limit 20})
            full (resource/read-window registry observation {} {:offset 0 :limit 100000})]
        (is (str/includes? (:content full) "\\\"answer\\\":42"))
        (is (some? (:next-offset window))))
      (finally (db/close conn)))))

(deftest context-objects-are-content-addressed-and-bounded
  (let [[conn _] (database)]
    (try
      (let [digest (trace/intern-context! conn "large" "abcdef")
            registry (resource/registry {:context (inference/resolver conn)})
            observation (resource/observe registry (str "context:" digest) {})]
        (is (= digest (trace/intern-context! conn "large" "abcdef")))
        (is (= "bcd" (:content (resource/read-window registry observation {}
                                                      {:offset 1 :limit 3}))))
        (is (= 1 (count (resource/read-window registry observation {}
                                               {:query "c" :limit 1})))))
      (finally (db/close conn)))))

(deftest duplicate-context-content-has-independent-owner-associations
  (let [[conn run-id] (database)]
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (try
      (let [first-digest (trace/intern-context!
                          conn "same" "abcdef" {:run-id run-id :branch-id "B1"})
            second-digest (trace/intern-context!
                           conn "same" "abcdef" {:run-id run-id :branch-id "B2"})
            registry (resource/registry {:context (inference/resolver conn)})
            observation (resource/observe registry (str "context:" second-digest)
                                          {:run-id run-id :branch {:id "B2"}})]
        (is (= first-digest second-digest))
        (is (= 1 (count (db/fetch conn ["SELECT * FROM inference_contexts"]))))
        (is (= 2 (count (db/fetch conn ["SELECT * FROM inference_context_owners"]))))
        (is (= "B2" (get-in observation [:metadata :context :branch_id]))))
      (finally (db/close conn)))))

(deftest unknown-traces-fail-with-structured-errors
  (let [[conn _] (database)
        registry (resource/registry {:inference (inference/resolver conn)})]
    (try
      (is (= :missing-resource
             (try (resource/observe registry "inference:missing" {}) nil
                  (catch clojure.lang.ExceptionInfo e
                    (:resource/error (ex-data e))))))
      (finally (db/close conn)))))
