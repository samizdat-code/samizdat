;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.role-output-test
  "A role's output contract (karamazov-05z0.14, after autolith's role
  :output and yield.submit): a role that declares one in roles.edn finishes
  `done` with a `result` that fits it, or is told why and asked again —
  rather than its verdict being parsed out of prose and failing open."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools]))

(def ^:private reviewer
  {:id "R1" :role :reviewer :advisory? true :problem "review it" :messages []})

(defn- done [branch args]
  (base/run-tool {:tool-name "done" :branch branch :args args}))

(deftest the-reviewer-declares-a-verdict-contract
  (is (some? (roles/output-schema :reviewer)))
  (is (nil? (roles/output-schema :implementor)) "a role without one finishes as before"))

(deftest a-result-that-fits-is-kept-on-the-branch
  (let [r (done reviewer {"answer" "REVISE: the error path is untested"
                          "result" {"verdict" "REVISE" "findings" ["the error path is untested"]}})]
    (is (:done? r) (str (:result r)))
    (is (= {:verdict "REVISE" :findings ["the error path is untested"]}
           (get-in r [:branch :final-result])))))

(deftest a-result-that-does-not-fit-is-asked-for-again
  (testing "missing"
    (let [r (done reviewer {"answer" "looks fine"})]
      (is (not (:done? r)))
      (is (str/includes? (str (:result r)) "verdict"))))
  (testing "the wrong shape"
    (let [r (done reviewer {"answer" "x" "result" {"verdict" "MAYBE"}})]
      (is (not (:done? r)))
      (is (str/includes? (str (:result r)) "PASS")))))

(deftest a-role-with-no-contract-ignores-result
  (let [b {:id "A1" :role :answerer :advisory? true :problem "q" :messages []}
        r (done b {"answer" "the answer is in src/x.clj"})]
    (is (:done? r) (str (:result r)))
    (is (nil? (get-in r [:branch :final-result])))))

(deftest a-bad-contract-is-refused-when-the-role-table-is-saved
  (is (some? (roles/output-problem [:map [:verdict [:enum]]])))
  (is (nil? (roles/output-problem [:map [:verdict [:enum "PASS" "REVISE"]]]))))
