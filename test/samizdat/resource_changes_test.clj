;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource-changes-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.resume :as resume]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]))

(defn- fixture [publish]
  (let [root (str "/tmp/samizdat-resource-changes-" (random-uuid))]
    (fs/create-dirs root)
    (spit (str root "/a.clj") "(ns a)\n")
    (spit (str root "/b.clj") "(ns b)\n")
    (let [conn (db/open! ":memory:")
          run-id (runs/start-run! conn {:problem "resources"})]
      (runs/open-branch! conn run-id {:branch-id "B1"})
      (runs/open-branch! conn run-id {:branch-id "B2"})
      {:root root :conn conn :run-id run-id
       :resolver (workspace/workspace-resolver {:publish publish})})))

(defn- cleanup [{:keys [root conn]}]
  (db/close conn)
  (fs/delete-tree root))

(defn- context [{:keys [root conn run-id]}]
  {:root root :conn conn :run-id run-id :branch {:id "B1"}})

(defn- proposal [{:keys [resolver] :as fixture}]
  (let [r (resource/registry {:workspace resolver})
        ctx (context fixture)
        observations (mapv #(resource/observe r (str "workspace:" % ".clj") ctx) ["a" "b"])
        members (workspace/stage-workspace
                 resolver ctx observations
                 {"workspace:a.clj" "(ns a2)\n"
                  "workspace:b.clj" "(ns b2)\n"})]
    (assoc (changes/stage! r ctx members) :registry r)))

(defn- successful-publish [path content _revision]
  (spit path content)
  (workspace/content-revision content))

(deftest a-stale-member-prevents-every-publication
  (let [fixture (fixture successful-publish)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (spit (str root "/b.clj") "(ns external)\n")
        (is (= :stale (:status (changes/apply! (:registry proposal)
                                               (context fixture) proposal))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj")))))
      (finally (cleanup fixture)))))

(deftest a-later-publication-failure-compensates-earlier-members
  (let [publish (fn [path content revision]
                  (if (.endsWith (str path) "b.clj")
                    (throw (ex-info "publish failed" {}))
                    (successful-publish path content revision)))
        fixture (fixture publish)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (= :compensated (:status (changes/apply! (:registry proposal)
                                                     (context fixture) proposal))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj"))))
        (is (= "(ns b)\n" (slurp (str root "/b.clj")))))
      (finally (cleanup fixture)))))

(deftest compensation-never-overwrites-a-third-party-edit
  (let [root-holder (atom nil)
        publish (fn [path content revision]
                  (cond
                    (.endsWith (str path) "b.clj")
                    (do (spit (str @root-holder "/a.clj") "(ns third-party)\n")
                        (throw (ex-info "publish failed" {})))
                    :else
                    (successful-publish path content revision)))
        fixture (fixture publish)]
    (reset! root-holder (:root fixture))
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (= :recovery-required (:status (changes/apply! (:registry proposal)
                                                           (context fixture) proposal))))
        (is (= "(ns third-party)\n" (slurp (str root "/a.clj")))))
      (finally (cleanup fixture)))))

(deftest apply-is-exact-owner-and-single-use
  (let [fixture (fixture successful-publish)]
    (try
      (let [{:keys [root run-id conn]} fixture
            r (resource/registry {:workspace (:resolver fixture)})
            proposal (proposal fixture)]
        (is (= :committed (:status (changes/apply! (:registry proposal) (context fixture) proposal))))
        (is (= "(ns a2)\n" (slurp (str root "/a.clj"))))
        (testing "a second apply is refused"
          (is (= :proposal-consumed
                 (try (changes/apply! (:registry proposal) (context fixture) proposal) nil
                      (catch clojure.lang.ExceptionInfo e
                        (:resource/error (ex-data e)))))))
        (testing "another branch cannot consume the proposal"
          (let [other (proposal fixture)]
            (is (= :proposal-owner
                   (try (changes/apply! (:registry proposal) (assoc (context fixture) :branch {:id "B2"}) other) nil
                        (catch clojure.lang.ExceptionInfo e
                          (:resource/error (ex-data e)))))))
        (is (some? run-id)) (is (some? conn))))
      (finally (cleanup fixture)))))

(deftest durable-intent-survives-proposal-token-loss
  (let [fixture (fixture successful-publish)]
    (try
      (let [{:keys [conn run-id]} fixture
            r (resource/registry {:workspace (:resolver fixture)})
            proposal (proposal fixture)
            rows (store/unfinished-changes conn {:run-id run-id :branch-id "B1"})]
        (is (= [(:id proposal)] (mapv :id rows)))
        (is (.contains (resume/unfinished-resources conn run-id "B1") (:id proposal)))
        (is (= :proposal-stale
               (try (changes/apply! (resource/registry {:workspace (:resolver fixture)})
                                    (context fixture) proposal)
                    nil
                    (catch clojure.lang.ExceptionInfo e
                      (:resource/error (ex-data e)))))))
      (finally (cleanup fixture)))))

(deftest a-stale-revision-after-one-publication-still-compensates
  (let [root-holder (atom nil)
        publish (fn [path content revision]
                  (let [installed (successful-publish path content revision)]
                    (when (.endsWith (str path) "a.clj")
                      (spit (str @root-holder "/b.clj") "(ns external)\n"))
                    installed))
        fixture (fixture publish)]
    (reset! root-holder (:root fixture))
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (= :compensated (:status (changes/apply! (:registry proposal)
                                                     (context fixture) proposal))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj"))))
        (is (= "(ns external)\n" (slurp (str root "/b.clj")))))
      (finally (cleanup fixture)))))

(deftest recovery-restores-published-members-after-an-interruption
  (let [publish (fn [path content revision]
                  (if (.endsWith (str path) "b.clj")
                    (throw (java.lang.AssertionError. "interrupted"))
                    (successful-publish path content revision)))
        fixture (fixture publish)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (thrown? java.lang.AssertionError
                     (changes/apply! (:registry proposal) (context fixture) proposal)))
        (is (= "(ns a2)\n" (slurp (str root "/a.clj"))))
        (is (= :compensated (:status (changes/recover! (:registry proposal)
                                                       (context fixture) (:id proposal)))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj")))))
      (finally (cleanup fixture)))))

(deftest default-publication-is-atomic-and-commits
  (let [fixture (fixture nil)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (= :committed (:status (changes/apply! (:registry proposal)
                                                   (context fixture) proposal))))
        (is (= "(ns a2)\n" (slurp (str root "/a.clj"))))
        (is (= "(ns b2)\n" (slurp (str root "/b.clj"))))
        (is (empty? (filter #(.startsWith (.getName %) ".samizdat-resource-")
                            (.listFiles (java.io.File. (str root)))))))
      (finally (cleanup fixture)))))

(deftest recovery-inspects-publication-before-the-member-outcome-write
  (let [fixture (fixture successful-publish)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)
            original store/member-result!]
        (with-redefs [store/member-result! (fn [& args]
                                            (if (= :published (nth args 3))
                                              (throw (java.lang.AssertionError. "database interrupted"))
                                              (apply original args)))]
          (is (thrown? java.lang.AssertionError
                       (changes/apply! (:registry proposal) (context fixture) proposal))))
        (is (= "(ns a2)\n" (slurp (str root "/a.clj"))))
        (is (= :compensated (:status (changes/recover! (:registry proposal)
                                                       (context fixture) (:id proposal)))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj"))))
        (is (some? original)))
      (finally (cleanup fixture)))))

(deftest committed-and-conflicted-changes-are-not-silently-recovered
  (let [root-holder (atom nil)
        publish (fn [path content revision]
                  (cond
                    (.endsWith (str path) "b.clj")
                    (do (spit (str @root-holder "/a.clj") "(ns third-party)\n")
                        (throw (ex-info "publish failed" {})))
                    :else (successful-publish path content revision)))
        fixture (fixture publish)]
    (reset! root-holder (:root fixture))
    (try
      (let [{:keys [root]} fixture
            first-proposal (proposal fixture)]
        (is (= :recovery-required (:status (changes/apply! (:registry first-proposal)
                                                           (context fixture) first-proposal))))
        (is (= :recovery-required (:status (changes/recover! (:registry first-proposal)
                                                             (context fixture) (:id first-proposal)))))
        (is (= "(ns third-party)\n" (slurp (str root "/a.clj"))))
        (let [other (proposal (assoc fixture :resolver
                                     (workspace/workspace-resolver {:publish successful-publish})))]
          (is (= :committed (:status (changes/apply! (:registry other)
                                                     (context fixture) other))))
          (is (= :invalid-change-status
                 (try (changes/recover! (:registry other) (context fixture) (:id other)) nil
                      (catch clojure.lang.ExceptionInfo e
                        (:resource/error (ex-data e))))))))
      (finally (cleanup fixture)))))

(deftest ordinary-io-failures-compensate-earlier-members
  (let [publish (fn [path content revision]
                  (if (.endsWith (str path) "b.clj")
                    (throw (java.io.IOException. "disk full"))
                    (successful-publish path content revision)))
        fixture (fixture publish)]
    (try
      (let [{:keys [root]} fixture
            proposal (proposal fixture)]
        (is (= :compensated (:status (changes/apply! (:registry proposal)
                                                     (context fixture) proposal))))
        (is (= "(ns a)\n" (slurp (str root "/a.clj")))))
      (finally (cleanup fixture)))))

(deftest duplicate-members-fail-before-any-durable-intent
  (let [fixture (fixture successful-publish)]
    (try
      (let [proposal (proposal fixture)
            member (first (:members proposal))
            before (:n (db/fetch-one (:conn fixture)
                                     ["SELECT COUNT(*) AS n FROM resource_changes"]))]
        (is (= :duplicate-resource
               (try (changes/stage! (:registry proposal) (context fixture) [member member]) nil
                    (catch clojure.lang.ExceptionInfo e
                      (:resource/error (ex-data e))))))
        (is (= before (:n (db/fetch-one (:conn fixture)
                                        ["SELECT COUNT(*) AS n FROM resource_changes"])))))
      (finally (cleanup fixture)))))

(deftest create-publication-makes-confined-parent-directories
  (let [fixture (fixture nil)]
    (try
      (let [{:keys [root]} fixture
            r (resource/registry {:workspace (:resolver fixture)})
            ctx (context fixture)
            uri "workspace:nested/new/file.txt"
            observation (resource/observe r uri ctx)
            member (assoc observation :operation :create :original nil
                          :replacement "new" :base-revision nil
                          :replacement-revision (workspace/content-revision "new"))
            proposal (changes/stage! r ctx [member])]
        (is (= :committed (:status (changes/apply! r ctx proposal))))
        (is (= "new" (slurp (str root "/nested/new/file.txt")))))
      (finally (cleanup fixture)))))

(deftest resource-migration-adds-ordered-recovery-tables
  (let [conn (db/open! ":memory:")]
    (try
      (db/execute! conn ["DROP TABLE IF EXISTS resource_change_members"])
      (db/execute! conn ["DROP TABLE IF EXISTS resource_changes"])
      (db/execute! conn ["DROP TABLE IF EXISTS inference_attempts"])
      (db/execute! conn ["DROP TABLE IF EXISTS inference_views"])
      (db/execute! conn ["DROP TABLE IF EXISTS inference_frames"])
      (db/execute! conn ["DROP TABLE IF EXISTS inference_context_owners"])
      (db/execute! conn ["DROP TABLE IF EXISTS inference_contexts"])
      (db/execute! conn ["DROP TABLE IF EXISTS shell_captures"])
      (db/execute! conn ["DROP TABLE IF EXISTS knowledge_premises"])
      (db/execute! conn ["DROP TABLE IF EXISTS knowledge_evidence"])
      (db/execute! conn ["DROP TABLE IF EXISTS diagnosis_cases"])
      (db/execute! conn ["DROP TABLE IF EXISTS knowledge_status_deliveries"])
      (db/execute! conn ["DROP TABLE IF EXISTS execution_receipts"])
      (db/execute! conn ["DROP TABLE IF EXISTS executions"])
      (db/execute! conn ["ALTER TABLE side_calls DROP COLUMN trace_id"])
      (db/execute! conn ["PRAGMA user_version = 46"])
      (is (= 59 (db/migrate! conn)))
      (is (= 59 (db/schema-version conn)))
      (is (some #(= "ordinal" (:name %))
                (db/fetch conn ["PRAGMA table_info(resource_change_members)"])))
      (is (some #(= "trace_id" (:name %))
                (db/fetch conn ["PRAGMA table_info(side_calls)"])))
      (is (some #(= "run_id" (:name %))
                (db/fetch conn ["PRAGMA table_info(inference_contexts)"])))
      (is (some #(= "stdout_read_path" (:name %))
                (db/fetch conn ["PRAGMA table_info(shell_captures)"])))
      (finally (db/close conn)))))

(deftest default-publication-preserves-file-permissions
  (let [fixture (fixture nil)]
    (try
      (let [{:keys [root]} fixture
            path (str root "/a.clj")
            file (java.nio.file.Paths/get path (make-array String 0))
            original (java.nio.file.Files/getPosixFilePermissions
                      file (make-array java.nio.file.LinkOption 0))]
        (java.nio.file.Files/setPosixFilePermissions
         file (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------"))
        (let [proposal (proposal fixture)]
          (is (= :committed (:status (changes/apply! (:registry proposal)
                                                     (context fixture) proposal))))
          (is (= (java.nio.file.attribute.PosixFilePermissions/fromString "rw-------")
                 (java.nio.file.Files/getPosixFilePermissions
                  file (make-array java.nio.file.LinkOption 0))))
          (is (some? original))))
      (finally (cleanup fixture)))))

(deftest unfinished-changes-is-empty-when-the-table-is-absent
  (let [conn (db/open! ":memory:")]
    (try
      (db/execute! conn ["DROP TABLE IF EXISTS resource_changes"])
      (is (= [] (store/unfinished-changes conn {:run-id "run" :branch-id "B1"})))
      (is (nil? (resume/unfinished-resources conn "run" "B1")))
      (finally (db/close conn)))))
