;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon-transform-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]
            [samizdat.surgeon.transform :as transform]))

(def source
  (str "(ns sample.core)\n\n"
       "(def configs\n"
       "  [{:name :a :timeout 100}\n"
       "   {:name :b :timeout 100}])\n\n"
       "(def settings {:timeout 100 ; keep\n"
       "               :ready? true})\n"))

(def edited
  (str "(ns sample.core)\n\n"
       "(def configs\n"
       "  [{:name :a :timeout 150}\n"
       "   {:name :b :timeout 150}])\n\n"
       "(def settings {:timeout 100 ; keep\n"
       "               :ready? true})\n"))

(def timeouts-expression
  "(-> (form 'configs) (match :timeout) right (transform #(+ % 50)))")

(def timeouts-request
  {:file "a.clj" :expression timeouts-expression
   :expect {:matches 2 :max_changed_characters 6}})

(defn- plan [request]
  (transform/plan source request))

(defn- plan-error [request]
  (try
    (plan request)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest pure-transform-computes-all-replacements-from-one-frozen-source
  (let [result (plan timeouts-request)]
    (is (= edited (:future-source result)))
    (is (= 2 (:matches result)))
    (is (= 6 (:changed-characters result)))
    (is (= ["100" "100"] (mapv :before (:edits result))))
    (is (= ["150" "150"] (mapv :after (:edits result))))
    (is (false? (:comment-bearing-selection? result)))))

(deftest pure-transform-enforces-cardinality-churn-and-terminal-guards
  (testing "exact match count"
    (let [error (plan-error (assoc-in timeouts-request [:expect :matches] 3))]
      (is (= :expected-count-mismatch (:surgeon/error error)))
      (is (= 3 (:expected-count error)))
      (is (= 2 (:actual-count error)))))
  (testing "changed-character budget"
    (let [error (plan-error (assoc-in timeouts-request [:expect :max_changed_characters] 5))]
      (is (= :change-budget-exceeded (:surgeon/error error)))
      (is (= 6 (:changed-characters error)))
      (is (= 5 (:max-changed-characters error)))))
  (testing "the relation must end in transform"
    (is (= :transform-required
           (:surgeon/error (plan-error (assoc timeouts-request
                                              :expression "(-> (form 'configs) initializer)"))))))
  (testing "no-op transforms are refused"
    (is (= :no-op-transform
           (:surgeon/error
             (plan-error {:file "a.clj"
                          :expression "(-> (form 'configs) (match :timeout) right (transform identity))"
                          :expect {:matches 2 :max_changed_characters 6}}))))))

(deftest pure-transform-rejects-lazy-and-non-edn-results
  (doseq [[label expression]
          [[:lazy "(-> (form 'configs) initializer (transform #(map (fn [x] x) %)))"]
           [:function "(-> (form 'configs) initializer (transform (fn [v] (fn [] v))))"]
           [:regex "(-> (form 'configs) initializer (transform (fn [_] (re-pattern \"x\"))))"]]]
    (testing (name label)
      (is (= :invalid-transform-result
             (:surgeon/error
               (plan-error {:file "a.clj" :expression expression
                            :expect {:matches 1 :max_changed_characters 1000}})))))))

(deftest pure-transform-marks-comment-bearing-selection-and-preserves-narrow-comments
  (let [broad (plan {:file "a.clj"
                     :expression "(-> (form 'settings) initializer (transform #(assoc % :retries 3)))"
                     :expect {:matches 1 :max_changed_characters 200}})]
    (is (true? (:comment-bearing-selection? broad)))
    (is (not (str/includes? (:future-source broad) "; keep"))))
  (let [narrow (plan {:file "a.clj"
                      :expression "(-> (form 'settings) (match :timeout) right (transform inc))"
                      :expect {:matches 1 :max_changed_characters 3}})]
    (is (false? (:comment-bearing-selection? narrow)))
    (is (str/includes? (:future-source narrow) "101 ; keep"))
    (is (str/includes? (:future-source narrow) ":timeout 101"))))

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon transform"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (spit (str root "/a.clj") source)
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

(defn- call
  ([fixture request] (call fixture request {}))
  ([fixture request overrides]
   (tools/run-tool (merge fixture {:tool-name "transform_clojure" :args request}
                          overrides))))

(defn- read-file [fixture]
  (slurp (str (:root fixture) "/a.clj")))

(deftest transform-preview-is-the-default-and-resource-apply-commits-it
  (with-fixture [fixture]
    (let [applications (atom 0)
          preview (with-redefs [changes/apply! (fn [& _] (swap! applications inc))]
                    (call fixture timeouts-request))
          proposal-id (get-in preview [:resource :proposal-id])]
      (is (= :neutral (:category preview)) (pr-str preview))
      (is (some? proposal-id))
      (is (= 2 (get-in preview [:resource :matches])))
      (is (= 6 (get-in preview [:resource :changed-characters])))
      (is (zero? @applications))
      (is (= source (read-file fixture)))
      (is (= 1 (count (store/unfinished-changes
                        (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"}))))
      (let [applied (tools/run-tool
                      (assoc fixture :tool-name "resource"
                             :args {:action "apply" :proposal proposal-id}))]
        (is (= :success (:category applied)) (pr-str applied))
        (is (= edited (read-file fixture)))))))

(deftest transform-commit-uses-resource-publication-and-reads-back
  (with-fixture [fixture]
    (let [result (call fixture (assoc timeouts-request :commit true))
          members (get-in result [:resource :members])]
      (is (= :success (:category result)) (pr-str result))
      (is (= :committed (get-in result [:resource :status])))
      (is (= edited (read-file fixture)))
      (is (= [(workspace/content-revision edited)]
             (mapv :replacement-revision members)))
      (is (= "committed"
             (:status (store/change-by-id (:conn fixture)
                                          (get-in result [:resource :id]))))))))

(deftest malformed-paths-refuse-before-staging-or-writing
  (with-fixture [fixture]
    (let [staged (atom 0)
          stage! resource-tool/stage-proposal!]
      (with-redefs [resource-tool/stage-proposal!
                    (fn [ctx members]
                      (swap! staged inc)
                      (stage! ctx members))]
        (let [result (call fixture (assoc timeouts-request :file "../a.clj" :commit true))]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (str/includes? (:result result) "outside-root"))
          (is (zero? @staged))
          (is (= source (read-file fixture))))))))

(deftest comments-require-a-narrow-selection-only-for-commit
  (with-fixture [fixture]
    (let [broad {:file "a.clj"
                 :expression "(-> (form 'settings) initializer (transform #(assoc % :retries 3)))"
                 :expect {:matches 1 :max_changed_characters 200}}
          preview (call fixture broad)
          refused (call fixture (assoc broad :commit true))]
      (is (= :neutral (:category preview)) (pr-str preview))
      (is (true? (get-in preview [:resource :comment-bearing-selection?])))
      (is (= :mechanics (:category refused)) (pr-str refused))
      (is (str/includes? (:result refused) "lossless-commit-refused"))
      (is (= source (read-file fixture))))
    (let [narrow {:file "a.clj"
                  :expression "(-> (form 'settings) (match :timeout) right (transform inc))"
                  :expect {:matches 1 :max_changed_characters 3}
                  :commit true}
          committed (call fixture narrow)]
      (is (= :success (:category committed)) (pr-str committed))
      (is (str/includes? (read-file fixture) "101 ; keep"))
      (is (str/includes? (read-file fixture) ":timeout 101")))))

(deftest unsafe-lazy-and-excess-transforms-fail-before-staging
  (with-fixture [fixture]
    (let [staged (atom 0)
          stage! resource-tool/stage-proposal!
          expressions
          ["(-> (form 'configs) initializer (transform (fn [_] (slurp \"x\"))))"
           "(-> (form 'configs) initializer (transform (fn [_] (spit \"x\" \"y\"))))"
           "(-> (form 'configs) initializer (transform (fn [_] (.exec (Runtime/getRuntime) \"true\"))))"
           "(-> (form 'configs) initializer (transform (fn [_] (require 'clojure.java.io))))"
           "(-> (form 'configs) initializer (transform (fn [_] (atom 0))))"
           "(-> (form 'configs) initializer (transform (fn [_] (future 1))))"
           "(-> (form 'configs) initializer (transform (fn [_] (java.io.File. \"x\"))))"
           "(-> (form 'configs) initializer (transform (fn [v] (.toString v))))"
           "(-> (form 'configs) initializer (transform #(map (fn [x] x) %)))"
           "(-> (form 'configs) initializer (transform (fn [v] (fn [] v))))"]]
      (with-redefs [resource-tool/stage-proposal!
                    (fn [ctx members]
                      (swap! staged inc)
                      (stage! ctx members))]
        (doseq [expression expressions]
          (let [result (call fixture {:file "a.clj" :expression expression
                                      :expect {:matches 1 :max_changed_characters 1000}
                                      :commit true})]
            (is (= :mechanics (:category result)) (pr-str result))
            (is (= source (read-file fixture)))))
        (let [mismatch (call fixture (assoc-in timeouts-request [:expect :matches] 3))
              excess (call fixture (assoc-in timeouts-request
                                             [:expect :max_changed_characters] 5))]
          (is (str/includes? (:result mismatch) "expected-count-mismatch"))
          (is (str/includes? (:result excess) "change-budget-exceeded")))
        (is (zero? @staged))
        (is (empty? (store/unfinished-changes
                      (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"})))))))

(deftest configured-transform-bounds-refuse-before-staging
  (with-fixture [fixture]
    (let [staged (atom 0)
          original gates/threshold]
      (with-redefs [gates/threshold
                    (fn [key]
                      (cond-> (original key)
                        (= :clj-surgeon key)
                        (assoc :max-transform-matches 1
                               :max-transform-changed-characters 5)))
                    resource-tool/stage-proposal!
                    (fn [& _] (swap! staged inc))]
        (let [result (call fixture timeouts-request)]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (str/includes? (:result result) "invalid-transform-expectation"))
          (is (zero? @staged))
          (is (= source (read-file fixture))))))))
