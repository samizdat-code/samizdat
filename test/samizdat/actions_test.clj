;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.actions-test
  "What each action a run took led to (karamazov-oi16.1): the key an action is
  known by, and its record of working and failing within the run."
  (:require [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.actions :as actions]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [clojure.string :as str]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools]
            [samizdat.agent.tools.base :as tools-base]
            [samizdat.store.db :as db]
            [samizdat.security.policy :as policy]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(deftest an-action-is-known-by-its-program-and-what-it-runs
  (is (= ["python3 solve.py"] (policy/command-keys "python3 /app/solve.py --n 3")))
  (is (= ["pip install"] (policy/command-keys "pip install -q torch")))
  (is (= ["npm test"] (policy/command-keys "npm test")))
  (is (= ["make"] (policy/command-keys "make")))
  (is (= ["cd /app" "pytest tests"] (policy/command-keys "cd /app && pytest -x tests/")))
  (is (= ["pytest tests"] (policy/command-keys "PYTHONPATH=. timeout 60 pytest -q tests"))
      "assignments and wrappers are not the action")
  (is (= ["python3 -" ] (policy/command-keys "python3 - <<'EOF'\nprint(1)\nEOF"))
      "a heredoc script is python3 reading stdin")
  (testing "a shell's -c script is the commands inside it"
    (is (= ["cargo build"] (policy/command-keys "bash -c 'cargo build --release'")))
    (is (= ["node vm.js"] (policy/command-keys "sh -c \"node vm.js\"")))))

(def ^:private notes
  [{:branch-id "B1" :turn 3 :command "python solve.py" :exit 127 :ms 20}
   {:branch-id "B1" :turn 4 :command "python solve.py" :exit 127 :ms 15}
   {:branch-id "B1" :turn 5 :command "python3 solve.py" :exit 0 :ms 900}
   {:branch-id "B1" :turn 7 :command "pip install torch" :exit 1 :ms 3000}
   {:branch-id "B1" :turn 9 :command "pip install --break-system-packages torch" :exit 0 :ms 60000}
   {:branch-id "B1" :turn 11 :command "make test" :exit nil :timeout? true :ms 120000}
   {:branch-id "B2" :turn 2 :command "python solve.py" :exit 127 :ms 10}])

(deftest each-action-has-a-record-of-working-and-failing
  (let [s (actions/scores notes)]
    (is (= {:ok 0 :failed 3 :timeouts 0} (select-keys (s "python solve.py") [:ok :failed :timeouts])))
    (is (= {:ok 1 :failed 0} (select-keys (s "python3 solve.py") [:ok :failed])))
    (is (= {:ok 1 :failed 1} (select-keys (s "pip install") [:ok :failed])))
    (is (= 1 (:timeouts (s "make test"))))
    (testing "what a key's last outcome was, and an example of the command that worked"
      (is (= 5 (:last-turn (s "python3 solve.py"))))
      (is (true? (:last-ok? (s "pip install"))))
      (is (= "pip install --break-system-packages torch" (:worked (s "pip install"))))
      (is (= 127 (:last-exit (s "python solve.py"))))))
  (testing "one branch's record"
    (is (= 2 (:failed ((actions/scores notes "B1") "python solve.py"))))))

(deftest what-keeps-failing-and-what-works
  (let [s (actions/scores notes)]
    (is (= ["python solve.py"] (map :key (actions/failing s {:min-failures 2}))))
    (is (= #{"python3 solve.py" "pip install"} (set (map :key (actions/working s)))))
    (is (empty? (actions/failing s {:min-failures 4})))))

(deftest a-note-is-built-from-a-shell-result
  (let [n (actions/note {:command "python3 x.py" :via "eval" :ms 12 :command-chars 8}
                        {:category :failure :data {:exit 2 :timeout? false}})]
    (is (= {:command "python3 " :exit 2 :ms 12 :via "eval" :timeout? false :category "failure"} n)))
  (is (nil? (:exit (actions/note {:command "x" :ms 1 :command-chars 99}
                                 {:category :mechanics :policy-refusal? true})))
      "a refusal ran nothing")
  (is (true? (:refused? (actions/note {:command "x" :ms 1 :command-chars 99}
                                      {:category :mechanics :policy-refusal? true})))))

(deftest the-shell-tool-journals-each-command-it-runs
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        root (str (fs/create-temp-dir))
        b (state/new-branch {:id "B1" :problem "p"})
        run (fn [cmd & [via]]
              (tools-base/run-tool (cond-> {:tool-name "shell" :branch b :root root :conn c
                                            :run-id rid :turn 4 :args {:command cmd}}
                                     via (assoc :via via))))]
    (run "echo hi")
    (run "ls /nonexistent-oi16" "eval")
    (run "rm -rf /")
    (let [ns (journal/notes c rid :action)]
      (is (= 3 (count ns)))
      (is (= [0 1 nil] (map :exit ns)))
      (is (= "eval" (:via (second ns))))
      (is (true? (:refused? (nth ns 2))))
      (is (= {"echo hi" [1 0] "ls /nonexistent-oi16" [0 1]}
             (update-vals (actions/scores ns) (juxt :ok :failed)))
          "the refusal ran nothing and scores nowhere")
      (is (= "B1" (:branch-id (first ns)))))))

(deftest a-failing-action-comes-with-what-worked-instead
  (let [s (actions/scores (conj notes {:branch-id "B1" :turn 12 :command "pip3 install numpy" :exit 0}))
        [a] (actions/steer s {:min-failures 2})]
    (is (= "python solve.py" (:key a)))
    (is (= 3 (:failed a)))
    (is (= 127 (:exit a)))
    (is (= ["python3 solve.py"] (:instead a))
        "what it runs, by another program, worked")))

(deftest a-run-leaves-its-failing-and-working-pairs-as-patterns
  ;; karamazov-oi16.12: what a run learned by trying — this fails here, that
  ;; works instead — is what the next run should start out knowing.
  (let [ps (actions/patterns (conj notes {:branch-id "B1" :turn 12 :command "pip3 install numpy" :exit 0})
                             {:min-failures 2})]
    (is (= [["python solve.py" ["python3 solve.py"]]] (mapv (juxt :key :instead) ps))))
  (is (empty? (actions/patterns [{:branch-id "B1" :turn 1 :command "make" :exit 2}
                                 {:branch-id "B1" :turn 2 :command "make" :exit 2}]
                                {:min-failures 2}))
      "a failure with nothing that worked instead teaches nothing to act on"))

(defn- gate-at [failing & {:as extra}]
  (arbiter/decide (merge {:branch (merge (state/new-branch {:id "B1" :problem "p"})
                                         {:role :implementer :turns [{:tool "shell"}]}
                                         (:branch extra))
                          :max-turns 300
                          :style-gates (:gates (gates/style {:run {:style :lean}}))
                          :failing-actions failing}
                         (dissoc extra :branch))))

(deftest a-command-that-keeps-failing-is-steered-off
  (let [failing [{:key "python solve.py" :failed 3 :exit 127 :instead ["python3 solve.py"]}]
        d (gate-at failing)]
    (is (= :repeat-failing-action (:gate d)) "on in the lean style")
    (is (str/includes? (:message d) "`python solve.py`"))
    (is (str/includes? (:message d) "`python3 solve.py`"))
    (testing "once for each action that comes to fail, and within the cap"
      (is (not= :repeat-failing-action
                (:gate (gate-at failing :branch {:gate-history [{:gate :repeat-failing-action :turn 1}]}))))
      (is (= :repeat-failing-action
             (:gate (gate-at (conj failing {:key "make" :failed 2 :exit 2})
                             :branch {:gate-history [{:gate :repeat-failing-action :turn 1}]}))))
      (is (not= :repeat-failing-action
                (:gate (gate-at (vec (for [i (range 9)] {:key (str "x" i) :failed 2 :exit 1}))
                                :branch {:gate-history (vec (repeat (gates/threshold :max-action-steers)
                                                                    {:gate :repeat-failing-action :turn 1}))})))))
    (is (not= :repeat-failing-action (:gate (gate-at []))) "and nothing to say without one")))

(deftest a-test-run-reports-what-passed-and-failed
  ;; The summary line of each common runner, from gates.edn :test-progress.
  (let [pats (:patterns (gates/threshold :test-progress))
        c #(actions/test-counts % pats)]
    (is (= {:passed 5 :failed 1} (c "....F.\n=========== 1 failed, 5 passed in 0.31s ===========")) "pytest")
    (is (= {:passed 12 :failed 0} (c "12 passed in 2.10s")) "pytest -q")
    (is (= {:passed 293 :failed 3} (c "Ran 81 tests. 293 assertions passed, 2 failures, 1 errors.")) "jolt")
    (is (= {:passed 0 :failed 2} (c "Ran 5 tests containing 10 assertions.\n2 failures, 0 errors.")) "clojure.test")
    (is (= {:passed 7 :failed 2} (c "test result: FAILED. 7 passed; 2 failed; 0 ignored")) "cargo")
    (is (= {:passed 9 :failed 3} (c "Tests:       3 failed, 9 passed, 12 total")) "jest")
    (is (= {:passed 4 :failed 1} (c "  4 passing (12ms)\n  1 failing\n")) "mocha")
    (is (= {:passed 6 :failed 2} (c "# tests 8\n# pass 6\n# fail 2\n")) "node --test")
    (is (= {:passed 3 :failed 1} (c " 3 pass\n 1 fail\nRan 4 tests across 1 files. [20ms]")) "bun")
    (is (nil? (c "Wrote 3 files.")) "not a test run")))

(deftest a-plateau-is-the-same-failing-count-run-after-run
  ;; The user's heuristic: tests run again and again with the pass count not
  ;; moving is a run that is stuck, however busy it looks.
  (let [p actions/test-plateau]
    (is (= {:runs 3 :passed 5 :failed 2}
           (p [{:passed 1 :failed 6} {:passed 5 :failed 2} {:passed 5 :failed 2} {:passed 5 :failed 2}])))
    (is (= {:runs 1 :passed 6 :failed 1} (p [{:passed 5 :failed 2} {:passed 6 :failed 1}]))
        "a count that moved starts a new streak")
    (is (nil? (p [{:passed 9 :failed 0} {:passed 9 :failed 0} {:passed 9 :failed 0}])) "all green is done, not stuck")
    (is (nil? (p [])))))

(deftest waiting-on-a-review-is-not-being-stuck
  ;; ctb-r9 bun-sourcemap-leak: with :stuck and :progress-stalled on under lean,
  ;; :stuck fired 13 times and :progress-stalled 6, telling the branch to split,
  ;; while its done was held for the supervisor's review: each refused done
  ;; counted as a failure and none as progress. And :stuck fired on the
  ;; supervisor's own branch.
  (let [decide (fn [b] (:gate (arbiter/decide {:branch (merge (state/new-branch {:id "B1" :problem "p"})
                                                              {:role :implementer} b)
                                               :max-turns 300
                                               :style-gates (:gates (gates/style {:run {:style :lean}}))})))]
    (testing ":stuck"
      (is (= :stuck (decide {:consecutive-failures 3 :last-failed-tool "shell" :turns [{:tool "shell"}]})))
      (is (not= :stuck (decide {:consecutive-failures 3 :last-failed-tool "done" :turns [{:tool "done"}]}))
          "not when the failures are dones the review is holding")
      (is (not= :stuck (decide {:role :supervisor :consecutive-failures 3 :last-failed-tool "shell"
                                :turns [{:tool "shell"}]}))
          "nor on the supervisor"))
    (testing ":progress-stalled"
      (let [stalled {:any-progress? true :turns-since-progress 20}]
        (is (= :progress-stalled (decide (assoc stalled :turns [{:tool "eval"}]))))
        (is (not= :progress-stalled (decide (assoc stalled :turns [{:tool "done"}])))
            "not on a turn that was a done waiting on its review")
        (is (not= :progress-stalled (decide (assoc stalled :role :tester :turns [{:tool "eval"}])))
            "nor on the tester")))))
