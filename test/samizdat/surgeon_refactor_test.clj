;; samizdat - a self-hosting agentic harness
;; Inspection fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim, source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-refactor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [rewrite-clj.parser :as parser]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]
            [jolt-lsp.surgeon.extract :as extract]
            [jolt-lsp.surgeon.extract-header :as header]
            [jolt-lsp.surgeon.quoted-var-refs :as quoted]
            [jolt-lsp.surgeon.rename :as rename]
            [jolt-lsp.surgeon.syntax-var-refs :as syntax]))

(def extraction-source
  (str "(ns sample.core\n"
       "  (:require [clojure.string :as str]))\n\n"
       "(defn- helper [x] (str/upper-case x))\n\n"
       "(defn- moved [x] (helper x))\n\n"
       "(defn remains [x] (moved x))\n"))

(def extraction-consumer
  (str "(ns sample.consumer (:require [sample.core :as core]))\n"
       "(def pinned #'core/moved)\n"))

(defn- extraction-input []
  {:file "src/sample/core.clj"
   :sources {"src/sample/core.clj" extraction-source
             "src/sample/extracted.clj" nil
             "src/sample/consumer.clj" extraction-consumer}
   :forms '[helper moved]
   :to "src/sample/extracted.clj"
   :target-ns "sample.extracted"
   :derive-required-public-forms true})

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon refactor"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (fs/create-dirs (str root "/.samizdat"))
    (spit (str root "/.samizdat/config.edn") "{}")
    {:root root :conn conn :run-id run-id :branch {:id "B1" :role :implementor}}))

(defn- cleanup [{:keys [root conn run-id]}]
  (resource-tool/forget-run! run-id)
  (db/close conn)
  (fs/delete-tree root))

(defmacro with-fixture [[name] & body]
  `(let [~name (fixture)]
     (try ~@body (finally (cleanup ~name)))))

(defn- call [fixture change expect]
  (tools/run-tool
   (assoc fixture :tool-name "apply_clojure_changes"
          :args {:changes [change] :expect expect})))

(deftest native-extraction-uses-resource-compensation-for-created-files
  (with-fixture [fixture]
    (spit (str (:root fixture) "/core.clj") extraction-source)
    (let [publish (fn [path content revision]
                    (if (str/ends-with? (str path) "core.clj")
                      (throw (java.io.IOException. "injected source publication failure"))
                      (do (spit path content) revision)))
          registry (resource/registry
                    {:workspace (workspace/workspace-resolver {:publish publish})})]
      (with-redefs [resource-tool/registry-for (constantly registry)]
        (let [result (call fixture
                           {:id :extract :in ["core.clj"] :forms '[helper moved]
                            :do [:extract {:to "extracted.clj" :target-ns 'sample.extracted
                                           :require-policy :copy-all
                                           :derive-required-public-forms true}]
                            :expect {:matches 2}}
                           {:changes 1 :edits 2 :files 2})
              members (get-in result [:resource :members])
              rows (store/change-members (:conn fixture) (get-in result [:resource :id]))]
          (is (= :compensated (get-in result [:resource :status])) (pr-str result))
          (is (= [:create :write] (mapv :operation members)))
          (is (every? #(or (nil? (:base-revision %))
                           (string? (:base-revision %))) members))
          (is (= ["compensated" "publishing"] (mapv :status rows)))
          (is (= extraction-source (slurp (str (:root fixture) "/core.clj"))))
          (is (not (fs/exists? (str (:root fixture) "/extracted.clj")))))))))

(deftest native-namespace-rename-commits-revisioned-rename-and-write-members
  (with-fixture [fixture]
    (fs/create-dirs (str (:root fixture) "/old_ns"))
    (spit (str (:root fixture) "/old_ns/core.clj") "(ns old-ns.core)\n(defn ping [] :ok)\n")
    (spit (str (:root fixture) "/consumer.clj")
          "(ns consumer (:require [old-ns.core :as old]))\n(defn ping [] (old/ping))\n")
    (let [result (call fixture
                       {:id :rename :in ["old_ns/core.clj" "consumer.clj"]
                        :do [:rename-namespace {:from 'old-ns :to 'new-ns}]
                        :expect {:matches 2}}
                       {:changes 1 :edits 2 :files 2})
          members (get-in result [:resource :members])]
      (is (= :committed (get-in result [:resource :status])) (pr-str result))
      (is (= [:rename :write] (mapv :operation members)))
      (is (nil? (:target-revision (first members))))
      (is (string? (:base-revision (first members))))
      (is (str/includes? (slurp (str (:root fixture) "/new_ns/core.clj"))
                         "(ns new-ns.core)"))
      (is (not (fs/exists? (str (:root fixture) "/old_ns/core.clj"))))
      (is (str/includes? (slurp (str (:root fixture) "/consumer.clj"))
                         "[new-ns.core :as old]")))))

(deftest later-member-failure-restores-original-bytes-after-rename
  (with-fixture [fixture]
    (let [original "(ns old-ns.core)\n(defn ping [] :ok)\n"]
      (fs/create-dirs (str (:root fixture) "/old_ns"))
      (spit (str (:root fixture) "/old_ns/core.clj") original)
      (spit (str (:root fixture) "/consumer.clj")
            "(ns consumer (:require [old-ns.core :as old]))\n(defn ping [] (old/ping))\n")
      (let [publish (fn [path content revision]
                      (if (str/ends-with? (str path) "consumer.clj")
                        (throw (java.io.IOException. "injected later write failure"))
                        (do (spit path content) revision)))
            registry (resource/registry
                      {:workspace (workspace/workspace-resolver {:publish publish})})]
        (with-redefs [resource-tool/registry-for (constantly registry)]
          (let [result (call fixture
                             {:id :rename :in ["old_ns/core.clj" "consumer.clj"]
                              :do [:rename-namespace {:from 'old-ns :to 'new-ns}]
                              :expect {:matches 2}}
                             {:changes 1 :edits 2 :files 2})]
            (is (= :compensated (get-in result [:resource :status])) (pr-str result))
            (is (= original (slurp (str (:root fixture) "/old_ns/core.clj"))))
            (is (not (fs/exists? (str (:root fixture) "/new_ns/core.clj"))))))))))

(def cyclic-source
  "(ns sample.cyclic)\n\n(defn even? [x] (if (zero? x) true (odd? (dec x))))\n\n(defn odd? [x] (if (zero? x) false (even? (dec x))))\n")

(deftest native-cyclic-extraction-stages-nothing-and-preserves-source
  (with-fixture [fixture]
    (spit (str (:root fixture) "/cyclic.clj") cyclic-source)
    (let [staged (atom 0)]
      (with-redefs [resource-tool/stage-proposal! (fn [& _] (swap! staged inc))]
        (let [result (call fixture
                           {:id :extract :in ["cyclic.clj"] :forms '[even? odd?]
                            :do [:extract {:to "extracted.clj" :target-ns 'sample.extracted}]
                            :expect {:matches 2}}
                           {:changes 1 :edits 2 :files 2})]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (str/includes? (:result result) "extraction-forms-not-orderable"))
          (is (zero? @staged))
          (is (= cyclic-source (slurp (str (:root fixture) "/cyclic.clj"))))
          (is (not (fs/exists? (str (:root fixture) "/extracted.clj"))))
          (is (empty? (store/unfinished-changes
                        (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"}))))))))
