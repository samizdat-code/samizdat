(ns samizdat.check-tool-test
  "The worker's `run_check`: the verdict a team's gate would give, without a shell
  command the approval policy refuses, such as a program reading a file on stdin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.check]))

(defn- dir-with [program]
  (let [d (str "/tmp/samizdat-check-" (System/nanoTime))]
    (.mkdirs (java.io.File. (str d "/tests")))
    (spit (str d "/tests/1.in") "1 2\n")
    (spit (str d "/tests/1.out") "3\n")
    (when program (spit (str d "/solution.py") program))
    d))

(defn- check [root & [config]]
  (:result (base/run-tool {:tool-name "run_check" :root root :run-id "r" :config config :branch {:id "W1"}})))

(deftest it-runs-the-samples-and-says-what-failed
  (testing "passing"
    (is (str/includes? (check (dir-with "print(sum(map(int, input().split())))")) "PASSED all 1")))
  (testing "failing, in the check's own words"
    (let [r (check (dir-with "print(0)"))]
      (is (str/includes? r "FAILED"))
      (is (str/includes? r "\"3\""))
      (is (str/includes? r "\"0\""))))
  (testing "no program yet"
    (is (str/includes? (check (dir-with nil)) "solution.py does not exist"))))

(deftest with-nothing-to-run-it-says-so
  (let [d (str "/tmp/samizdat-check-empty-" (System/nanoTime))]
    (.mkdirs (java.io.File. d))
    (is (str/includes? (check d) "no check"))))
