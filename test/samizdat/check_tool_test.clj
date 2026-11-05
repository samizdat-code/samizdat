(ns samizdat.check-tool-test
  "The worker's `run_check`: the verdict a team's gate would give, without a shell
  command the approval policy refuses, such as a program reading a file on stdin."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.check]
            [samizdat.agent.verify :as verify]))

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

(defn- check-branch [root & [branch]]
  (:branch (base/run-tool {:tool-name "run_check" :root root :run-id "r"
                           :branch (merge {:id "W1" :current-turn 7} branch)})))

(deftest green-samples-are-recorded-on-the-branch
  ;; The stall gates count confirmed Clojure artifacts, so a finished Python
  ;; program with a passing check read as stuck: all 12 hard-corpus loop runs
  ;; had solution.py by worker turn 10 and went on for hundreds of turns, and
  ;; escalation opened siblings to "approach it differently".
  (let [good "print(sum(map(int, input().split())))"]
    (testing "a pass is remembered, with the turn it happened on"
      (let [g (:green-check (check-branch (dir-with good)))]
        (is (= 7 (:turn g)))
        (is (= "solution.py" (:artifact g)))))
    (testing "a fail takes an earlier pass back"
      (let [d (dir-with "print(0)")]
        (is (nil? (:green-check (check-branch d {:green-check {:turn 1}}))))))
    (testing "no program takes it back too"
      (is (nil? (:green-check (check-branch (dir-with nil) {:green-check {:turn 1}})))))))

(deftest a-green-verify-command-is-not-a-finished-task
  ;; A repo's suite is green before the work starts. Only paired samples judge
  ;; the program itself, so only they say the job is finished.
  (let [d (str "/tmp/samizdat-check-cmd-" (System/nanoTime))]
    (.mkdirs (java.io.File. d))
    (with-redefs [verify/run-configured (constantly {:green? true})]
      (let [b (:branch (base/run-tool {:tool-name "run_check" :root d :run-id "r"
                                       :config {:run {:verify-cmd "true"}}
                                       :branch {:id "W1" :current-turn 2 :green-check {:turn 1}}}))]
        (is (nil? (:green-check b)))))))

(deftest a-pass-pins-the-program-only-while-it-is-unchanged
  (let [d (dir-with "print(sum(map(int, input().split())))")
        g (:green-check (check-branch d))]
    (is (verify/samples-pinned? d g))
    (spit (str d "/solution.py") "print(0)\n")
    (is (not (verify/samples-pinned? d g)) "an edit since the check proves nothing")
    (is (not (verify/samples-pinned? d nil)) "no pass, no proof")))
