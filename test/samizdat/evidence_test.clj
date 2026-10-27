;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.evidence-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.knowledge]
            [samizdat.agent.tools.resource]
            [samizdat.store.db :as db]
            [samizdat.store.evidence :as evidence]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]
            [samizdat.resource.workspace :as workspace]))

(defn- database []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "evidence"})]
    [conn run-id]))

(defn- claim [conn run-id text]
  (knowledge/remember! conn {:content text :kind "fact" :run-id run-id}))

(deftest evidence-and-premises-retain-stable-sources-and-dependents
  (let [[conn run-id] (database)
        claim-id (claim conn run-id "claim")
        premise-id (claim conn run-id "premise")]
    (try
      (evidence/add! conn claim-id {:source-type :file :path "src/a.clj"
                                    :revision "rev1" :quotation "fact"})
      (evidence/add! conn premise-id {:source-type :tool :source-id "turn-7"})
      (evidence/add! conn premise-id {:source-type :artifact :source-id "artifact-8"})
      (evidence/add! conn premise-id {:source-type :user :source-id "operator"})
      (evidence/add! conn premise-id {:source-type :url :source-id "https://example.test"
                                      :quotation "quoted"})
      (evidence/add-premise! conn claim-id premise-id)
      (is (= 1 (count (evidence/for-claim conn claim-id))))
      (is (= 4 (count (evidence/for-claim conn premise-id))))
      (is (= [premise-id] (mapv :premise_id (evidence/premises-for conn claim-id))))
      (is (= [claim-id] (mapv :claim_id (evidence/dependents-for conn premise-id))))
      (finally (db/close conn)))))

(deftest recall-status-is-derived-locally-and-explained
  (let [[conn run-id] (database)
        claim-id (claim conn run-id "claim")]
    (try
      (evidence/add! conn claim-id {:source-type :file :path "src/a.clj" :revision "rev1"})
      (is (= :current (evidence/status conn claim-id
                                       {:now "2026-01-01T00:00:00.000Z"
                                        :check-file (constantly :current)})))
      (is (= :stale (evidence/status conn claim-id
                                     {:now "2026-01-01T00:00:00.000Z"
                                      :check-file (constantly :stale)})))
      (let [explanation (evidence/explain conn claim-id
                                          {:check-file (constantly :stale)})]
        (is (= :stale (:status explanation)))
        (is (= "src/a.clj" (get-in explanation [:evidence 0 :path])))
        (is (some? (:reason explanation))))
      (finally (db/close conn)))))

(deftest external-disputed-expired-and-retired-evidence-stay-distinct
  (let [[conn run-id] (database)
        external (claim conn run-id "external")
        disputed (claim conn run-id "disputed")
        expired (claim conn run-id "expired")
        retired (claim conn run-id "retired")]
    (try
      (evidence/add! conn external {:source-type :url :source-id "https://example.test"})
      (evidence/add! conn disputed {:source-type :user :source-id "review" :disputed? true})
      (evidence/add! conn expired {:source-type :user :source-id "old"
                                   :expires-at "2025-01-01T00:00:00.000Z"})
      (evidence/add! conn retired {:source-type :user :source-id "old"})
      (knowledge/retire! conn retired {:reason "withdrawn"})
      (let [opts {:now "2026-01-01T00:00:00.000Z" :check-file (constantly :current)}]
        (is (= :unverified (evidence/status conn external opts)))
        (is (= :conflicted (evidence/status conn disputed opts)))
        (is (= :stale (evidence/status conn expired opts)))
        (is (= :retired (evidence/status conn retired opts))))
      (finally (db/close conn)))))

(deftest premise-status-and-contradictions-propagate
  (let [[conn run-id] (database)
        claim-id (claim conn run-id "claim")
        premise-id (claim conn run-id "premise")]
    (try
      (evidence/add! conn claim-id {:source-type :user :source-id "test"})
      (evidence/add! conn premise-id {:source-type :file :path "src/p.clj" :revision "r1"})
      (evidence/add-premise! conn claim-id premise-id)
      (is (= :unverified (evidence/status conn claim-id
                                          {:check-file (constantly :unverified)})))
      (is (= :conflicted (evidence/status conn claim-id
                                          {:check-file (constantly :conflicted)})))
      (finally (db/close conn)))))

(deftest retired-premises-and-missing-records-do-not-support-claims
  (let [[conn run-id] (database)
        claim-id (claim conn run-id "claim")
        premise-id (claim conn run-id "premise")]
    (try
      (evidence/add-premise! conn claim-id premise-id)
      (knowledge/retire! conn premise-id {:reason "withdrawn"})
      (is (= :unverified (evidence/status conn claim-id)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (evidence/validate! {:source-type :artifact})))
      (evidence/add! conn claim-id {:source-type :artifact :source-id "missing"})
      (is (= :unverified (evidence/status conn claim-id)))
      (finally (db/close conn)))))

(deftest local-file-validation-recall-and-resource-explanation-stay-local
  (let [[conn run-id] (database)
        root (str (fs/create-temp-dir))
        path "src/a.clj"
        absolute (str root "/" path)
        claim-id (claim conn run-id "claim")]
    (fs/create-dirs (str root "/src"))
    (spit absolute "current")
    (try
      (evidence/add! conn claim-id {:source-type :file :path path
                                    :revision (workspace/content-revision "current")})
      (is (= :current (evidence/status conn claim-id {:check-file (evidence/file-checker root)})))
      (spit absolute "changed")
      (is (= :stale (evidence/status conn claim-id {:check-file (evidence/file-checker root)})))
      (let [ctx {:conn conn :run-id run-id :root root :branch {:id "B1" :role :answerer}
                 :tool-name "recall" :args {:id claim-id}}
            recalled (base/run-tool ctx)
            queried (base/run-tool (assoc ctx :args {:query "claim"}))
            resource (base/run-tool (assoc ctx :tool-name "resource"
                                           :args {:action "read" :uri (str "knowledge:" claim-id)}))]
        (is (str/includes? (:result recalled) ":stale"))
        (is (str/includes? (:result recalled) path))
        (is (str/includes? (:result queried) ":stale"))
        (is (str/includes? (:result resource) "\"stale\""))
        (is (str/includes? (:result resource) "src\\/a.clj")))
      (finally
        (db/close conn)
        (fs/delete-tree root)))))

(deftest remember-admits-validated-evidence-and-premises
  (let [[conn run-id] (database)
        premise-id (claim conn run-id "premise")]
    (try
      (let [result (base/run-tool
                    {:conn conn :run-id run-id :branch {:id "B1"} :tool-name "remember"
                     :args {:content "claim" :evidence [{:source-type "user" :source-id "test"}]
                            :premises [premise-id]}})
            stored (first (db/fetch conn ["SELECT id FROM knowledge WHERE content = 'claim'"]))]
        (is (= :neutral (:category result)))
        (is (= 1 (count (evidence/for-claim conn (:id stored)))))
        (is (= [premise-id] (mapv :premise_id (evidence/premises-for conn (:id stored))))))
      (let [unsupported (claim conn run-id "unsupported")]
        (is (= :unverified (evidence/status conn unsupported))))
      (finally (db/close conn)))))

(deftest knowledge-resource-reads-carry-their-flow-label
  (let [[conn run-id] (database)
        claim-id (knowledge/remember! conn {:content "labelled claim" :kind "fact"
                                            :run-id run-id :flow "encoded-label"})
        received (atom nil)]
    (try
      (with-redefs [samizdat.security.flow/receive!
                    (fn [_ labels via] (reset! received {:labels labels :via via}))]
        (base/run-tool {:conn conn :run-id run-id :root "/tmp"
                        :branch {:id "B1" :role :answerer}
                        :tool-name "resource"
                        :args {:action "read" :uri (str "knowledge:" claim-id)}}))
      (is (= {:labels ["encoded-label"] :via "resource"} @received))
      (finally (db/close conn)))))
