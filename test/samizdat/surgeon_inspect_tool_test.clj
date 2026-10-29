;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon-inspect-tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.security.flow :as flow]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(def basic-source
  "(ns sample.core)\n\n;; Explain alpha.\n;; Preserve this context.\n(defn ^:private alpha [x]\n  ;; Keep the body comment.\n  (+ x\n     1))\n\n(comment\n  (def hidden :example))\n\n(defn omega []\n  :done)\n")

(def basic-hash
  "9201824c1011dde7b685deb144eb9347af0886d8828f769fb2cd4ac353092dbf")

(def alpha-source
  "(defn ^:private alpha [x]\n  ;; Keep the body comment.\n  (+ x\n     1))")

(def match-source
  "(ns sample.portable)\n(defn beta [] (inc 1) (inc 2))\n")

(def test-policy
  {:max-requests 16
   :max-files 8
   :max-forms 16
   :max-matches 16
   :max-source-chars 100000
   :max-result-chars 100000})

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon inspect"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (spit (str root "/a.clj") basic-source)
    (spit (str root "/b.cljc") match-source)
    {:root root :conn conn :run-id run-id :branch {:id "B1" :role :implementor}}))

(defn- cleanup [{:keys [root conn run-id]}]
  (resource-tool/forget-run! run-id)
  (db/close conn)
  (fs/delete-tree root))

(defmacro with-fixture [[name] & body]
  `(let [~name (fixture)]
     (try ~@body (finally (cleanup ~name)))))

(defn- call
  ([fixture args] (call fixture args {}))
  ([fixture args overrides]
   (let [threshold gates/threshold
         policy (merge test-policy overrides)]
     (with-redefs [gates/threshold (fn [k]
                                     (if (= :clj-surgeon k) policy (threshold k)))]
       (tools/run-tool (assoc fixture :tool-name "inspect_clojure" :args args))))))

(defn- outline-request []
  {:operation "outline" :file "a.clj"})

(defn- forms-request []
  {:operation "forms" :file "a.clj" :form "alpha"})

(defn- match-request []
  {:operation "match" :file "b.cljc" :inside "beta" :match "(inc _)"})

(defn- batch-args []
  {:requests [(forms-request) (outline-request) (match-request)]
   :expect {:requests 3 :files 2}})

(defn- refused? [result]
  (and (= :mechanics (:category result))
       (not (contains? result :resource))))

(deftest batched-reads-observe-once-preserve-order-and-return-revisions
  (with-fixture [fixture]
    (let [observations (atom {})
          authorized resource-tool/authorized-observation]
      (with-redefs [resource-tool/authorized-observation
                    (fn [ctx uri]
                      (swap! observations update uri (fnil inc 0))
                      (authorized ctx uri))]
        (let [result (call fixture (batch-args))
              requests (get-in result [:resource :requests])]
          (is (= :neutral (:category result)) (pr-str result))
          (is (= {"workspace:a.clj" 1 "workspace:b.cljc" 1} @observations))
          (is (= ["forms" "outline" "match"] (mapv :operation requests)))
          (is (= ["a.clj" "a.clj" "b.cljc"] (mapv :file requests)))
          (is (= basic-hash (get-in result [:resource :files "a.clj"])))
          (is (every? #(re-matches #"[0-9a-f]{64}" (:revision %)) requests))
          (is (= basic-hash (:revision (first requests))))
          (is (= alpha-source (get-in requests [0 :result :forms 0 :source])))
          (is (= '[alpha nil omega]
                 (mapv :name (get-in requests [1 :result :forms]))))
          (is (= 2 (get-in requests [2 :result :match-count])))
          (is (= "(inc 1)" (get-in requests [2 :result :matches 0 :source])))
          (is (= (pr-str (:resource result)) (:result result)))
          (is (= basic-source (slurp (str (:root fixture) "/a.clj"))))
          (is (= match-source (slurp (str (:root fixture) "/b.cljc")))))))))

(deftest path-aliases-share-one-observation-and-form-budget
  (with-fixture [fixture]
    (testing "aliases canonicalize to one observed file"
      (let [observations (atom {})
            authorized resource-tool/authorized-observation]
        (with-redefs [resource-tool/authorized-observation
                      (fn [ctx uri]
                        (swap! observations update uri (fnil inc 0))
                        (authorized ctx uri))]
          (let [result (call fixture {:requests [(forms-request)
                                                 (assoc (outline-request) :file "./a.clj")]
                                      :expect {:requests 2 :files 1}})]
            (is (= :neutral (:category result)) (pr-str result))
            (is (= {"workspace:a.clj" 1} @observations))
            (is (= ["a.clj" "a.clj"]
                   (mapv :file (get-in result [:resource :requests]))))
            (is (= ["a.clj"] (keys (get-in result [:resource :files]))))))))
    (testing "aliases share the per-file form cap"
      (let [observations (atom 0)
            authorized resource-tool/authorized-observation]
        (with-redefs [resource-tool/authorized-observation
                      (fn [ctx uri]
                        (swap! observations inc)
                        (authorized ctx uri))]
          (let [result (call fixture {:requests [(forms-request)
                                                 {:operation "forms" :file "./a.clj"
                                                  :form "omega"}]
                                      :expect {:requests 2 :files 1}}
                             {:max-forms 1})]
            (is (refused? result) (pr-str result))
            (is (str/includes? (:result result) "form-limit") (pr-str result))
            (is (zero? @observations))))))))

(deftest wrong-aggregate-counts-refuse-before-observation
  (with-fixture [fixture]
    (doseq [expect [{:requests 2 :files 2}
                    {:requests 3 :files 1}
                    {:requests 3 :files 3}]]
      (let [observations (atom 0)
            authorized resource-tool/authorized-observation]
        (with-redefs [resource-tool/authorized-observation
                      (fn [ctx uri]
                        (swap! observations inc)
                        (authorized ctx uri))]
          (let [result (call fixture (assoc (batch-args) :expect expect))]
            (is (refused? result) (pr-str result))
            (is (zero? @observations))))))))

(deftest path-escapes-absolute-paths-and-nonclojure-files-refuse-the-whole-batch
  (with-fixture [fixture]
    (doseq [file ["../outside.clj" "/tmp/outside.clj" "a.txt"]]
      (let [result (call fixture {:requests [(forms-request)
                                             {:operation "outline" :file file}]
                                  :expect {:requests 2 :files 2}})]
        (is (refused? result) (pr-str result))
        (is (not (str/includes? (:result result) "defn ^:private alpha")))))))

(deftest request-file-and-form-caps-refuse-before-observation
  (with-fixture [fixture]
    (doseq [[args overrides]
            [[(assoc (batch-args) :expect {:requests 3 :files 2})
              {:max-requests 2}]
             [{:requests [(outline-request) (match-request)]
               :expect {:requests 2 :files 2}}
              {:max-files 1}]
             [{:requests [{:operation "forms" :file "a.clj"
                           :forms ["alpha" "omega"]}]
               :expect {:requests 1 :files 1}}
              {:max-forms 1}]]]
      (let [observations (atom 0)
            authorized resource-tool/authorized-observation]
        (with-redefs [resource-tool/authorized-observation
                      (fn [ctx uri]
                        (swap! observations inc)
                        (authorized ctx uri))]
          (let [result (call fixture args overrides)]
            (is (refused? result) (pr-str result))
            (is (zero? @observations))))))))

(deftest source-match-and-rendered-result-caps-return-no-partial-source
  (with-fixture [fixture]
    (doseq [[args overrides]
            [[(batch-args) {:max-source-chars 16}]
             ({:requests [(match-request)] :expect {:requests 1 :files 1}}
              {:max-matches 1})
             ({:requests [(outline-request)] :expect {:requests 1 :files 1}}
              {:max-result-chars 64})]]
      (let [result (call fixture args overrides)]
        (is (refused? result) (pr-str result))
        (is (not (str/includes? (:result result) "defn ^:private alpha")))
        (is (not (str/includes? (:result result) "(inc 1)")))))))

(deftest one-failed-request-suppresses-every-earlier-result
  (with-fixture [fixture]
    (let [result (call fixture {:requests [(forms-request)
                                           {:operation "forms" :file "a.clj"
                                            :form "missing"}]
                                :expect {:requests 2 :files 1}})]
      (is (refused? result) (pr-str result))
      (is (not (str/includes? (:result result) "defn ^:private alpha"))))))

(deftest model-results-cross-the-redaction-boundary
  (with-fixture [fixture]
    (spit (str (:root fixture) "/secret.clj")
          "(ns sample.secret)\n(defn secret [] \"topsecret\")\n")
    (let [result (call (assoc fixture :env {"TEST_CREDENTIAL" "topsecret"})
                       {:requests [{:operation "forms" :file "secret.clj"
                                    :form "secret"}]
                        :expect {:requests 1 :files 1}})]
      (is (= :neutral (:category result)) (pr-str result))
      (is (not (str/includes? (:result result) "topsecret"))))))

(deftest reads-take-file-flow-labels-once-per-distinct-file
  (with-fixture [fixture]
    (let [reads (atom [])]
      (with-redefs [flow/read! (fn [_ paths via]
                                (swap! reads conj {:paths (vec paths) :via via}))]
        (let [result (call fixture (batch-args))]
          (is (= :neutral (:category result)) (pr-str result))))
      (is (= [{:paths ["a.clj" "b.cljc"] :via "inspect_clojure"}] @reads)))))

(defn- xray-request []
  {:operation "xray"
   :file "b.cljc"
   :expression (str "(-> (form 'beta) (match '(inc _)) (expect-count 2) "
                    "(analyze #(mapv second %)))")})

(deftest xray-reads-share-frozen-observations-and-preserve-source
  (with-fixture [fixture]
    (let [observations (atom {})
          authorized resource-tool/authorized-observation]
      (with-redefs [resource-tool/authorized-observation
                    (fn [ctx uri]
                      (swap! observations update uri (fnil inc 0))
                      (authorized ctx uri))]
        (let [result (call fixture
                           {:requests [(xray-request)
                                       (assoc (xray-request)
                                              :file "./b.cljc" :evidence "full")]
                            :expect {:requests 2 :files 1}})
              requests (get-in result [:resource :requests])]
          (is (= :neutral (:category result)) (pr-str result))
          (is (= {"workspace:b.cljc" 1} @observations))
          (is (= ["b.cljc" "b.cljc"] (mapv :file requests)))
          (is (= [1 2] (get-in requests [0 :result :value])))
          (is (= [1 2] (get-in requests [1 :result :value])))
          (is (nil? (get-in requests [0 :result :matches 0 :source])))
          (is (= "(inc 1)" (get-in requests [1 :result :matches 0 :source])))
          (is (= match-source (slurp (str (:root fixture) "/b.cljc")))))))))

(deftest xray-refusals-return-no-partial-source-or-computed-value
  (with-fixture [fixture]
    (doseq [[request overrides detail]
            [[(assoc (xray-request) :expression "(spit \"/tmp/no-xray-write\" \"x\")")
              {} "invalid-xray-expression"]
             [(assoc (xray-request) :expression "(.getProperty System \"user.dir\")")
              {} "invalid-xray-expression"]
             [(assoc (xray-request) :evidence "brief") {} "invalid-evidence-mode"]
             [(xray-request) {:max-matches 1} "xray-input-truncated"]
             [(xray-request) {:max-result-chars 100} "result-limit"]]]
      (let [result (call fixture {:requests [(forms-request) request]
                                  :expect {:requests 2 :files 2}}
                         overrides)]
        (is (refused? result) (pr-str result))
        (is (str/includes? (:result result) detail) (pr-str result))
        (is (not (str/includes? (:result result) "defn ^:private alpha")))
        (is (not (str/includes? (:result result) ":value")))))))

(deftest xray-rendered-batch-limit-refuses-literal-evidence
  (with-fixture [fixture]
    (let [result (call fixture
                       {:requests [{:operation "xray" :file "b.cljc"
                                    :expression "(-> (form 'beta) (match '(inc _)))"}]
                        :expect {:requests 1 :files 1}}
                       {:max-result-chars 64})]
      (is (refused? result) (pr-str result))
      (is (str/includes? (:result result) "result-limit") (pr-str result))
      (is (not (str/includes? (:result result) "(inc 1)"))))))

(deftest xray-non-edn-patterns-never-reach-resource-or-rendered-output
  (with-fixture [fixture]
    (doseq [expression ["(-> (form 'beta) (match identity))"
                        "(-> (form 'beta) (match identity) (analyze identity))"
                        "(-> (form 'beta) (match (re-pattern \"inc\")))"]]
      (let [result (call fixture
                         {:requests [(assoc (xray-request) :expression expression)]
                          :expect {:requests 1 :files 1}})]
        (is (refused? result) expression)
        (is (str/includes? (:result result) "invalid-query") (pr-str result))
        (is (not (contains? result :resource)))
        (is (not (str/includes? (:result result) "#object")))
        (is (not (str/includes? (:result result) ":query")))
        (is (not (str/includes? (:result result) ":trace")))))))

(deftest expectation-mismatch-never-renders-oversized-extra-keys
  (with-fixture [fixture]
    (let [observations (atom 0)
          authorized resource-tool/authorized-observation
          marker (apply str (repeat 20000 "x"))]
      (with-redefs [resource-tool/authorized-observation
                    (fn [ctx uri]
                      (swap! observations inc)
                      (authorized ctx uri))]
        (let [result (call fixture
                           {:requests [(outline-request)]
                            :expect {:requests 2 :files 1 :padding marker}}
                           {:max-result-chars 128})]
          (is (refused? result) (pr-str result))
          (is (zero? @observations))
          (is (not (str/includes? (:result result) marker)))
          (is (not (str/includes? (:result result) "padding")))
          (is (< (count (:result result)) 512)))))))

(deftest jolt-source-reads-exact-forms
  (with-fixture [fixture]
    (spit (str (:root fixture) "/core.jolt") basic-source)
    (let [result (call fixture
                       {:requests [{:operation "forms" :file "core.jolt" :form "alpha"}]
                        :expect {:requests 1 :files 1}})]
      (is (= :neutral (:category result)) (pr-str result))
      (is (= alpha-source
             (get-in result [:resource :requests 0 :result :forms 0 :source]))))))
