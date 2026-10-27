;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource-tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource]
            [samizdat.security.flow :as flow]
            [samizdat.inference.trace :as trace]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- fixture []
  (let [root (str "/tmp/samizdat-resource-tool-" (random-uuid))]
    (fs/create-dirs root)
    (spit (str root "/a.txt") "abcdef")
    (let [conn (db/open! ":memory:")
          run-id (runs/start-run! conn {:problem "resource tool"})]
      (runs/open-branch! conn run-id {:branch-id "B1"})
      (runs/open-branch! conn run-id {:branch-id "B2"})
      {:root root :conn conn :run-id run-id})))

(defn- cleanup [{:keys [root conn]}]
  (db/close conn)
  (fs/delete-tree root))

(defn- ctx [fixture action args]
  (assoc fixture :tool-name "resource" :branch {:id "B1" :role :implementor}
         :args (assoc args :action action)))

(deftest resource-reads-are-bounded-and-discoverable
  (let [fixture (fixture)]
    (try
      (let [read-result (base/run-tool (ctx fixture "read" {:uri "workspace:a.txt" :offset 1 :limit 3}))
            operations (base/run-tool (ctx fixture "operations" {:uri "workspace:a.txt"}))]
        (is (str/includes? (:result read-result) "bcd"))
        (is (str/includes? (:result read-result) "4"))
        (is (= "B1" (get-in read-result [:branch :id])))
        (is (str/includes? (:result operations) "replace")))
      (finally (cleanup fixture)))))

(deftest unknown-resource-schemes-are-malformed-not-failures
  (let [fixture (fixture)]
    (try
      (let [result (base/run-tool (ctx fixture "read" {:uri "unknown:a"}))]
        (is (= :mechanics (:category result)))
        (is (str/includes? (:result result) "unknown-scheme"))
        (is (= "B1" (get-in result [:branch :id]))))
      (finally (cleanup fixture)))))

(deftest edits-are-stale-safe-and-apply-is-exact-owner
  (let [fixture (fixture)]
    (try
      (let [staged (base/run-tool (ctx fixture "edit" {:uri "workspace:a.txt" :content "changed"}))
            proposal-id (get-in staged [:resource :proposal-id])]
        (is (some? proposal-id) (pr-str staged))
        (testing "another branch cannot apply"
          (let [other (base/run-tool (assoc (ctx fixture "apply" {:proposal proposal-id})
                                            :branch {:id "B2" :role :implementor}))]
            (is (= :mechanics (:category other)))
            (is (= "abcdef" (slurp (str (:root fixture) "/a.txt"))))))
        (testing "a stale edit is refused without publication"
          (spit (str (:root fixture) "/a.txt") "external")
          (let [result (base/run-tool (ctx fixture "apply" {:proposal proposal-id}))]
            (is (= :mechanics (:category result)))
            (is (= "external" (slurp (str (:root fixture) "/a.txt")))))))
      (finally (cleanup fixture)))))

(deftest resource-writes-require-a-writing-role
  (let [fixture (fixture)]
    (try
      (let [result (base/run-tool (assoc (ctx fixture "edit" {:uri "workspace:a.txt" :content "x"})
                                         :branch {:id "B1" :role :reviewer}))]
        (is (= :mechanics (:category result)))
        (is (= "abcdef" (slurp (str (:root fixture) "/a.txt")))))
      (finally (cleanup fixture)))))

(deftest resource-results-cross-the-redaction-boundary
  (let [fixture (fixture)]
    (try
      (spit (str (:root fixture) "/secret.txt") "topsecret")
      (let [result (tools/run-tool (assoc (ctx fixture "read" {:uri "workspace:secret.txt"})
                                          :env {"TEST_CREDENTIAL" "topsecret"}))]
        (is (not (str/includes? (:result result) "topsecret")))
        (is (= "B1" (get-in result [:branch :id]))))
      (finally (cleanup fixture)))))

(deftest resource-writes-cannot-change-the-operator-config
  (let [fixture (fixture)]
    (try
      (fs/create-dirs (str (:root fixture) "/.samizdat"))
      (spit (str (:root fixture) "/.samizdat/config.edn") "{}")
      (let [result (base/run-tool (ctx fixture "edit" {:uri "workspace:.samizdat/config.edn"
                                                       :content "{:run {}}"}))]
        (is (= :mechanics (:category result)))
        (is (= "{}" (slurp (str (:root fixture) "/.samizdat/config.edn")))))
      (finally (cleanup fixture)))))

(deftest provider-denied-resource-reads-are-refused-before-observation
  (let [fixture (fixture)]
    (try
      (spit (str (:root fixture) "/secret.txt") "denied")
      (let [result (tools/run-tool
                    (assoc (ctx fixture "read" {:uri "workspace:secret.txt"})
                           :config {:run {:provider-deny {:deepseek ["secret.txt"]}}}
                           :llm-config {:provider :deepseek}))]
        (is (= :mechanics (:category result)))
        (is (not (str/includes? (:result result) "denied"))))
      (finally (cleanup fixture)))))

(deftest resource-reads-propagate-file-flow
  (let [fixture (fixture)]
    (try
      (let [reads (atom [])]
        (with-redefs [flow/read! (fn [_ paths via]
                                  (swap! reads conj {:paths (vec paths) :via via}))]
          (tools/run-tool (ctx fixture "read" {:uri "workspace:a.txt"})))
        (is (= [{:paths ["a.txt"] :via "resource"}] @reads)))
      (finally (cleanup fixture)))))

(deftest resource-writes-label-published-paths
  (let [fixture (fixture)]
    (try
      (let [staged (base/run-tool (ctx fixture "edit" {:uri "workspace:a.txt" :content "changed"}))
            proposal (get-in staged [:resource :proposal-id])
            writes (atom [])]
        (with-redefs [flow/wrote! (fn [_ path whole?]
                                   (swap! writes conj {:path path :whole? whole?}))]
          (let [applied (tools/run-tool (ctx fixture "apply" {:proposal proposal}))]
            (is (= :success (:category applied)) (pr-str applied))))
        (is (= 1 (count @writes)))
        (is (.endsWith (get-in (first @writes) [:path]) "/a.txt")))
      (finally (cleanup fixture)))))

(deftest inference-resources-enforce-exact-owner
  (let [fixture (fixture)
        frame-id (trace/start-frame! (:conn fixture)
                                     {:run-id (:run-id fixture) :branch-id "B1"
                                      :task "private" :schema :text})]
    (trace/record-view! (:conn fixture) frame-id {:label "secret" :content "private"})
    (let [result (base/run-tool (assoc (ctx fixture "read" {:uri (str "inference:" frame-id)})
                                       :branch {:id "B2" :role :reviewer}))]
      (is (= :mechanics (:category result)))
      (is (not (str/includes? (:result result) "private"))))
    (cleanup fixture)))

(deftest external-context-enforces-exact-owner
  (let [fixture (fixture)
        digest (trace/intern-context! (:conn fixture) "secret" "private"
                                      {:run-id (:run-id fixture) :branch-id "B1"})]
    (let [result (base/run-tool (assoc (ctx fixture "read" {:uri (str "context:" digest)})
                                       :branch {:id "B2" :role :reviewer}))]
      (is (= :mechanics (:category result)))
      (is (not (str/includes? (:result result) "private"))))
    (cleanup fixture)))

(deftest run-teardown-retires-resource-proposals
  (let [fixture (fixture)]
    (try
      (let [staged (base/run-tool (ctx fixture "edit" {:uri "workspace:a.txt" :content "changed"}))
            proposal (get-in staged [:resource :proposal-id])]
        ((requiring-resolve 'samizdat.agent.run-state/run-ended!) (:run-id fixture))
        (let [applied (base/run-tool (ctx fixture "apply" {:proposal proposal}))]
          (is (= :mechanics (:category applied)) (pr-str applied))
          (is (= "abcdef" (slurp (str (:root fixture) "/a.txt"))))))
      (finally (cleanup fixture)))))
