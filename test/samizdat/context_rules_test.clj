;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.context-rules-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.context-rules :as context-rules]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.policy :as policy-tool]
            [samizdat.userspace :as userspace]))

(def policy
  {:max-rules 8 :max-selector-values 4 :max-metadata-values 4 :max-value-chars 1024
   :max-analysis-comparisons 1000
   :max-contributions 3 :max-contribution-chars 80 :max-total-chars 120
   :language-extensions {"clj" "clojure" "lisp" "common-lisp" "py" "python"}})

(defn- compile-one [when & [instruction]]
  (context-rules/compile-rules
   {:rules [{:id "rule" :when when :instruction (or instruction "Advice")}]}
   policy))

(deftest selector-fields-conjoin-and-values-alternate
  (let [rules (compile-one {:workspace "/work" :paths ["src/" "test/"]
                            :languages ["clojure"] :tools ["read_file"]
                            :namespaces ["workspace"] :roles ["implementor"]
                            :tasks ["fix"] :workflows ["loop"]
                            :diagnostics ["warning"] :events ["tool-success"]
                            :output-contains ["ASSERT"]})
        facts {:workspace "/work" :paths ["src/core.clj"] :languages ["clojure"]
               :tools ["read_file"] :namespaces ["workspace"] :roles ["implementor"]
               :tasks ["fix"] :workflows ["loop"] :diagnostics ["warning"]
               :events ["tool-success"] :outputs ["failed ASSERT: one"]}]
    (is (= ["rule"] (mapv :id (context-rules/resolve-contributions rules facts policy))))
    (testing "a field with no matching alternative rejects the rule"
      (is (empty? (context-rules/resolve-contributions rules (assoc facts :tools ["shell"]) policy))))
    (testing "path selection is a prefix and output matching is literal and case-insensitive"
      (is (empty? (context-rules/resolve-contributions rules (assoc facts :paths ["other/core.clj"]) policy)))
      (is (= ["rule"] (mapv :id (context-rules/resolve-contributions rules (assoc facts :outputs ["assert: lower"]) policy))))
      (is (empty? (context-rules/resolve-contributions rules (assoc facts :outputs ["missing"]) policy))))))

(deftest language-identity-is-configured-and-bounded
  (is (= "clojure" (context-rules/language-for-path "src/core.clj" policy)))
  (is (nil? (context-rules/language-for-path "src/core.unknown" policy)))
  (is (thrown? clojure.lang.ExceptionInfo
               (compile-one {:languages (vec (repeat 5 "clojure"))}))))

(deftest stale-generations-cannot-add-late-diagnostics
  (let [current (context-rules/note-facts {:generation 4 :events ["old"]}
                                          {:events ["new"]} 4 policy)]
    (is (= ["new" "old"] (vec (:events current))))
    (is (= current (context-rules/note-facts current {:diagnostics ["late"]} 3 policy)))
    (is (= ["latest" "new" "old"]
           (vec (:events (context-rules/note-facts current {:events ["latest"]} 4 policy)))))))

(deftest contributions-have-stable-order-and-shared-bounds
  (let [rules (context-rules/compile-rules
               {:rules [{:id "dynamic" :when {:roles ["implementor"]}
                         :instruction (apply str (repeat 100 "d")) :priority 0 :lifetime :turn}
                        {:id "stable-b" :when {:roles ["implementor"]}
                         :instruction "B" :priority 2 :lifetime :persistent}
                        {:id "stable-a" :when {:roles ["implementor"]}
                         :instruction "A" :priority 1 :lifetime :persistent}]}
               policy)
        selected (context-rules/resolve-contributions rules {:roles ["implementor"]} policy)]
    (is (= ["stable-b" "stable-a" "dynamic"] (mapv :id selected)))
    (is (every? #(<= (count (:text %)) 80) selected))
    (is (<= (reduce + (map #(count (:text %)) selected)) 120))
    (is (= [true true false] (mapv :stable? selected)))))

(deftest validation-rejects-unknown-ambiguous-and-shadowed-rules
  (is (thrown? clojure.lang.ExceptionInfo (compile-one {:unknown ["x"]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (context-rules/compile-rules
                {:rules [{:id "one" :when {:roles ["implementor"]} :instruction "one"}
                         {:id "two" :when {:roles ["implementor"]} :instruction "two"}]} policy)))
  (is (thrown? clojure.lang.ExceptionInfo
               (context-rules/compile-rules
                {:rules [{:id "broad" :when {:roles ["implementor"]}
                          :instruction "same" :priority 1}
                         {:id "narrow" :when {:roles ["implementor"] :tools ["read_file"]}
                          :instruction "same" :priority 0}]} policy))))

(deftest turn-boundary-derives-facts-and-renders-project-rules
  (let [policy (assoc policy :max-recent-outputs 16 :max-output-chars 8000)
        original-body userspace/body
        body {:rules [{:id "clojure-read"
                       :when {:workspace "/work" :paths ["src/"] :languages ["clojure"]
                              :tools ["read_file"] :roles ["implementor"] :tasks ["fix"]
                              :workflows ["loop"] :diagnostics ["warning"]
                              :events ["diagnostics" "tool-success"]
                              :output-contains ["assert"]}
                       :instruction "Use the observed revision" :priority 1 :lifetime :persistent}]}]
    (with-redefs [gates/threshold (fn [k]
                                    (case k
                                      :context-rules policy
                                      :instructions {:path-args ["path" "paths"]}))
                  userspace/body (fn [kind name]
                                   (if (= :policy kind)
                                     (do (is (= "context-rules" name))
                                         (pr-str body))
                                     (original-body kind name)))]
      (let [boundary (context-rules/boundary
                      {:root "/work" :config {:run {:loop "loop"}}}
                      {:role :implementor :task {:id "fix"}
                       :messages [{:role "assistant" :content "ASSERT failed"}]}
                      {:name "read_file" :args {:path "src/core.clj"}}
                      {:category :success :reason "warning" :events [{:event "diagnostics"}]}
                      7)]
        (is (= ["clojure-read"] (mapv :id (:contributions boundary))))
        (is (= 7 (get-in boundary [:facts :generation])))
        (is (= ["clojure"] (get-in boundary [:facts :languages])))
        (is (str/includes? (:texts boundary) "Use the observed revision"))
        (is (true? (:stable? (first (:contributions boundary)))))))))

(deftest absent-role-is-inert-but-present-invalid-policy-is-refused
  (with-redefs [gates/threshold (fn [_] policy)
                userspace/body (fn [_ _] nil)]
    (is (= [] (context-rules/current-rules))))
  (with-redefs [gates/threshold (fn [_] policy)
                userspace/body (fn [_ _] "{:rules [{:id \"bad\" :when {:unknown [\"x\"]} :instruction \"bad\"}]}")]
    (is (thrown? clojure.lang.ExceptionInfo
                 (policy-tool/reload-and-verify! "context-rules")))))

(deftest validation-work-and-rendered-wrapper-are-bounded
  (is (thrown? clojure.lang.ExceptionInfo
               (context-rules/compile-rules
                {:rules [{:id "one" :when {:roles ["a" "b"]} :instruction "one"}
                         {:id "two" :when {:roles ["c" "d"]} :instruction "two"}]}
                (assoc policy :max-analysis-comparisons 1))))
  (let [original-body userspace/body
        long-id (apply str (repeat 100 "x"))
        body {:rules [{:id long-id :when {:roles ["implementor"]}
                       :instruction (apply str (repeat 100 "g"))}]}]
    (with-redefs [gates/threshold (fn [k]
                                    (if (= :instructions k)
                                      {:path-args []}
                                      (assoc policy :max-recent-outputs 1 :max-output-chars 10)))
                  userspace/body (fn [kind name]
                                   (if (= :policy kind)
                                     (pr-str body)
                                     (original-body kind name)))]
      (let [rendered (:texts (context-rules/boundary
                              {:root "/work"} {:role :implementor :messages []}
                              {:name "read_file" :args {}} {:category :success} 1))]
        (is (<= (count rendered) (:max-total-chars policy)))))))

(deftest directive-facts-reach-boundary-only-in-their-origin-generation
  (let [original-body userspace/body
        policy (assoc policy :max-recent-outputs 1 :max-output-chars 10)
        body {:rules [{:id "late" :when {:diagnostics ["late"]}
                       :instruction "Inspect late diagnostic"}]}]
    (with-redefs [gates/threshold (fn [k]
                                    (if (= :instructions k) {:path-args []} policy))
                  userspace/body (fn [kind name]
                                   (if (= :policy kind)
                                     (pr-str body)
                                     (original-body kind name)))]
      (let [branch {:current-turn 4 :role :implementor :messages []
                    :context-rule-facts {:generation 3 :diagnostics ["previous"]}}
            stale (context-rules/apply-directive
                   branch {:payload {:generation 3 :context-facts {:diagnostics ["late"]}}} 4)
            current (context-rules/apply-directive
                     branch {:payload {:generation 4 :context-facts {:diagnostics ["late"]}}} 4)]
        (is (nil? (get-in stale [:context-rule-facts :diagnostics])))
        (is (= ["late"] (get-in current [:context-rule-facts :diagnostics])))
        (is (= ["late"]
               (mapv :id (:contributions
                          (context-rules/boundary
                           {:root "/work"} current {:name "read_file" :args {}}
                           {:category :success} 4)))))))))

(deftest assistant-outputs-are-clamped-to-selector-value-limit
  (let [policy (assoc policy :max-recent-outputs 2 :max-output-chars 8000)
        long-output (apply str (repeat 2000 "x"))]
    (with-redefs [gates/threshold (fn [k]
                                    (if (= :instructions k)
                                      {:path-args []}
                                      policy))
                  userspace/body (fn [_ _] nil)]
      (let [facts (:facts (context-rules/boundary
                           {:root "/work"}
                           {:role :implementor
                            :messages [{:role "assistant" :content long-output}
                                       {:role "assistant" :content "   "}]}
                           {:name "read_file" :args {}}
                           {:category :success}
                           1))]
        (is (= 1 (count (:outputs facts))) "blank assistant output is not a selector value")
        (is (= 1024 (count (first (:outputs facts))))
            "the larger capture limit must not bypass the selector value limit")))))
