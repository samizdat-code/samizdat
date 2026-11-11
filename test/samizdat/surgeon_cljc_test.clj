;; samizdat - a self-hosting agentic harness
;; Inspection fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim, source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-cljc-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as reader-types]
            [jolt.fs :as fs]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [jolt-lsp.surgeon.cljc.analyze :as analyze]
            [jolt-lsp.surgeon.cljc.merge :as merge]
            [jolt-lsp.surgeon.cljc.require-ops :as require-ops]
            [jolt-lsp.surgeon.cljc.split :as split]))

(def clj-source
  (str "(ns sample.shared\n"
       "  (:require [clojure.string :as str]\n"
       "            [clojure.java.io :as io]))\n\n"
       "(defn platform-file [x] (io/file x))\n\n"
       "(defn upper [x] (str/upper-case x))\n"))

(def cljs-source
  (str "(ns sample.shared\n"
       "  (:require [clojure.string :as str]\n"
       "            [goog.string :as gstr]))\n\n"
       "(defn platform-file [x] (gstr/htmlEscape x))\n\n"
       "(defn upper [x] (str/upper-case x))\n"))

(defn- parse-forms [source]
  (let [rdr (reader-types/string-push-back-reader source)]
    (->> (repeatedly #(reader/read {:eof ::end :read-cond :preserve} rdr))
         (take-while #(not= ::end %))
         vec)))

(defn- normalize-ns [form]
  (if (and (seq? form) (= 'ns (first form)))
    (let [[op name & clauses] form]
      (apply list op name
             (map (fn [clause]
                    (if (and (seq? clause) (= :require (first clause)))
                      (apply list :require (set (rest clause)))
                      clause))
                  clauses)))
    form))

(defn- normalized [source]
  (mapv normalize-ns (parse-forms source)))

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon cljc"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (fs/create-dirs (str root "/.samizdat"))
    (spit (str root "/.samizdat/config.edn") "{}")
    {:root root :conn conn :run-id run-id :branch {:id "B1" :role :implementor}}))

(defn- cleanup [{:keys [root conn run-id]}]
  (resource-tool/forget-run! run-id)
  (db/close conn)
  (fs/delete-tree root))

(deftest native-cljc-merge-require-and-split-share-the-resource-path
  (let [{:keys [root conn run-id] :as fixture} (fixture)]
    (try
      (spit (str root "/shared.clj") clj-source)
      (spit (str root "/shared.cljs") cljs-source)
      (let [call (fn [change expect]
                   (tools/run-tool
                    (assoc fixture :tool-name "apply_clojure_changes"
                           :args {:changes [change] :expect expect})))
            merged (call {:id :merge :in ["shared.clj" "shared.cljs"]
                          :do [:merge-cljc {:to "shared.cljc"}]
                          :expect {:matches 3}}
                         {:changes 1 :edits 3 :files 3})]
        (is (= :committed (get-in merged [:resource :status])) (pr-str merged))
        (is (= [:create :delete :delete]
               (mapv :operation (get-in merged [:resource :members]))))
        (is (fs/exists? (str root "/shared.cljc")))
        (is (not (fs/exists? (str root "/shared.clj"))))
        (let [required (call {:id :require :in ["shared.cljc"]
                              :do [:add-require {:platform :clj :ns 'clojure.set :as 'set}]
                              :expect {:matches 1}}
                             {:changes 1 :edits 1 :files 1})]
          (is (= :committed (get-in required [:resource :status])) (pr-str required))
          (is (str/includes? (slurp (str root "/shared.cljc")) "clojure.set"))
          (let [split-result (call {:id :split :in ["shared.cljc"]
                                    :do [:split-cljc {:clj "split.clj" :cljs "split.cljs"}]
                                    :expect {:matches 3}}
                                   {:changes 1 :edits 3 :files 3})]
            (is (= :committed (get-in split-result [:resource :status]))
                (pr-str split-result))
            (is (= [:create :create :delete]
                   (mapv :operation (get-in split-result [:resource :members]))))
            (is (str/includes? (slurp (str root "/split.clj")) "clojure.set"))
            (is (not (str/includes? (slurp (str root "/split.cljs")) "clojure.set")))
            (is (not (fs/exists? (str root "/shared.cljc")))))))
      (finally
        (resource-tool/forget-run! run-id)
        (db/close conn)
        (fs/delete-tree root)))))

(deftest native-split-refuses-before-deleting-unsupported-input
  (let [{:keys [root conn run-id] :as fixture} (fixture)
        original "(ns a \"docs\")\n\n(def x 1)\n"]
    (try
      (spit (str root "/bad.cljc") original)
      (let [result (tools/run-tool
                    (assoc fixture :tool-name "apply_clojure_changes"
                           :args {:changes [{:id :split :in ["bad.cljc"]
                                             :do [:split-cljc {:clj "bad.clj" :cljs "bad.cljs"}]
                                             :expect {:matches 3}}]
                                  :expect {:changes 1 :edits 3 :files 3}}))]
        (is (= :mechanics (:category result)) (pr-str result))
        (is (= original (slurp (str root "/bad.cljc"))))
        (is (not (fs/exists? (str root "/bad.clj"))))
        (is (not (fs/exists? (str root "/bad.cljs")))))
      (finally
        (resource-tool/forget-run! run-id)
        (db/close conn)
        (fs/delete-tree root)))))

(deftest native-add-require-preserves-default-and-asymmetric-branches
  (let [{:keys [root conn run-id] :as fixture} (fixture)
        original (str "(ns sample.native-defaults\n"
                      "  (:require #?@(:default [[clojure.string :as str]])\n"
                      "            #?@(:clj [] :default [[clojure.walk :as walk]])))\n")]
    (try
      (spit (str root "/defaults.cljc") original)
      (let [result (tools/run-tool
                    (assoc fixture :tool-name "apply_clojure_changes"
                           :args {:changes [{:id :require :in ["defaults.cljc"]
                                             :do [:add-require {:platform :cljc :ns 'clojure.set :as 'set}]
                                             :expect {:matches 1}}]
                                  :expect {:changes 1 :edits 1 :files 1}}))
            {:keys [clj cljs]} (split/split-file (slurp (str root "/defaults.cljc")))]
        (is (= :committed (get-in result [:resource :status])) (pr-str result))
        (doseq [source [clj cljs]]
          (is (str/includes? source "[clojure.string :as str]"))
          (is (str/includes? source "[clojure.set :as set]")))
        (is (not (str/includes? clj "clojure.walk")))
        (is (str/includes? cljs "[clojure.walk :as walk]")))
      (finally
        (resource-tool/forget-run! run-id)
        (db/close conn)
        (fs/delete-tree root)))))
