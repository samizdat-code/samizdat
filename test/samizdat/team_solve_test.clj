;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.team-solve-test
  "Team-solve as a manager loop. These run the real manifest and the real grader
  against a scripted model, and pin what the method depends on: every stage is
  reached in order; a script decides, not a model; both published loss modes are
  closed; a stuck team reaches the supervisor and comes back or ends on its own."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.agent.gates]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.worktree]
            [samizdat.engine.proc :as proc]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.select :as select]
            [samizdat.cells :as cells]
            [samizdat.lexicon :as lexicon]
            [samizdat.prompt :as prompt]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.userspace :as us]
            [samizdat.workflow :as workflow]))

(def ^:private right "print(sum(map(int, input().split())))")
(def ^:private wrong "print(sum(map(int, input().split())) + 1)")
(def ^:private other-right "import sys\nprint(sum(int(x) for x in sys.stdin.read().split()))")

(defn- problem-dir []
  (let [d (str "/tmp/samizdat-team-solve-" (System/nanoTime))]
    (.mkdirs (java.io.File. (str d "/tests")))
    (spit (str d "/tests/1.in") "1 2 3\n")
    (spit (str d "/tests/1.out") "6\n")
    (spit (str d "/tests/2.in") "10 20\n")
    (spit (str d "/tests/2.out") "30\n")
    d))

(defn- repo-dir []
  (let [d (str "/tmp/samizdat-team-solve-repo-" (System/nanoTime))]
    (.mkdirs (java.io.File. d))
    (spit (str d "/README.md") "hello\n")
    (proc/run {:dir d} "git" "init" "-q")
    (proc/run {:dir d} "git" "add" "-A")
    (proc/run {:dir d} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "init")
    d))

(defn- json-call [tool args]
  (str "```tool-call\n{\"name\":\"" tool "\",\"args\":{"
       (str/join "," (for [[k v] args] (str (pr-str k) ":" (pr-str v))))
       "}}\n```"))

(def ^:private finished
  "### RESULT\nwrote it\n### NOTES\n- **Fact:** worker note\n### NEXT\nnone\n### STATUS\nsolved")

(defn- manager-reply
  [{:keys [status next tasks counterexample]}]
  (str "### STATUS\n" (or status "continue")
       (when next (str "\n### NEXT\n" next))
       "\n### TASKS\n" (or tasks "- [todo] implement")
       (when counterexample (str "\n### COUNTEREXAMPLE\n" counterexample))))

(defn- scripted
  "One fn answers every call by who is asking. `programs` is what each successive
  worker writes (nil writes nothing and gives up its turn); `manager` is a fn from
  the manager's call number to its reply map; `calls` collects what each role was sent."
  [{:keys [programs manager calls worker review]}]
  (let [workers (atom -1)
        managers (atom -1)
        record! (fn [role msgs] (when calls (swap! calls update role (fnil conj []) msgs)))]
    (fn [_ _ messages & _]
      (let [sys (str (:content (first messages)))
            all (str/join "\n" (map (comp str :content) (rest messages)))]
        (cond
          (str/includes? sys "of a small team of workers")
          (do (record! :plan messages)
              {:content "### PLAN\nsum them\n### TASKS\n- implement\n- check" :finish-reason "stop"})

          (str/includes? sys "independent WORKER")
          (do (record! :ideate messages)
              {:content "### NOTES\napproach ```python\nprint(1)\n``` in prose\n### NEXT\n- try a fold"
               :finish-reason "stop"})

          (str/includes? sys "independent reviewer")
          (do (record! :review messages)
              {:content (if review (review messages) "PASS") :finish-reason "stop"})

          (str/includes? sys "PRIMARY orchestrator and manager")
          (do (record! :manage messages)
              {:content (manager-reply (manager (swap! managers inc))) :finish-reason "stop"})

          (str/includes? sys "CUT OFF")
          (do (record! :cutoff messages)
              {:content "it was reading and never wrote anything" :finish-reason "stop"})

          ;; A worker. A fresh branch is one with no tool result yet.
          (str/includes? all "YOUR TASK:")
          (let [fresh? (not (str/includes? all "<tool_result"))]
            (record! :worker messages)
            (if worker
              (worker messages)
              (if fresh?
                (let [i (swap! workers inc)
                      body (nth programs i (last programs))]
                  (if (nil? body)
                    {:content (json-call "done" {"answer" "### RESULT\nnothing\n### STATUS\ncontinue"})
                     :finish-reason "stop"}
                    {:content (json-call "write_file" {"path" "solution.py" "content" body})
                     :finish-reason "stop"}))
                {:content (json-call "done" {"answer" finished}) :finish-reason "stop"})))

          :else {:content "ok" :finish-reason "stop"})))))

(defn- repo-worker
  "A worker that writes `file` once per round, then finishes."
  [file]
  (fn [messages]
    (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
      (if (str/includes? all "<tool_result tool=\"write_file\"")
        {:content (json-call "done" {"answer" finished}) :finish-reason "stop"}
        {:content (json-call "write_file" {"path" file "content" (str (System/nanoTime))})
         :finish-reason "stop"}))))

(defn- run [conn d & [opts]]
  (workflow/run! (merge {:conn conn
                         :config {:run {:loop "team-solve" :root d}}
                         :llm-adapter :a :llm-config {:max-tokens 16384}
                         :problem "Sum the integers on stdin."
                         :max-turns 200}
                        opts)))

(defn- stages [conn run-id]
  (mapv :to (filter #(= "stage" (str (:key %))) (journal/notes conn run-id :progress))))

(defn- rounds [conn run-id] (journal/notes conn run-id :team-solve-round))

(defn- with-policy
  "Run `f` with gates :team-solve overridden."
  [overrides f]
  (let [real lexicon/policy]
    (with-redefs [lexicon/policy (fn [k] (cond-> (real k) (= :team-solve k) (merge overrides)))]
      (f))))

;; These pin the tool-using worker, which is repository work's and :program-worker
;; :tools. A program's default, one call per role, is team-solve-parity-test's.
(use-fixtures :each (fn [f] (with-policy {:program-worker :tools} f)))

(deftest it-is-a-discoverable-whole-run-workflow
  (let [conn (db/open! ":memory:")
        ts (some #(when (= "team-solve" (:name %)) %) (workflow/catalog conn))]
    (is ts "the manifest ships")
    (is (:turn-sliceable? ts))))

(deftest the-shipped-role-map-is-whole
  (testing "a misplaced brace once dropped every prompt role and no run could start"
    (let [m (edn/read-string (slurp "resources/userspace.edn"))
          prompts (set (vals (:prompts m)))]
      (is (= #{:manifests :policies :cells :prompts} (set (keys m))))
      (doseq [f (.listFiles (java.io.File. "resources/prompts"))
              :when (.isFile f)]
        (is (contains? prompts (str "prompts/" (.getName f)))
            (str (.getName f) " has no role in the shipped map")))
      (doseq [f (.listFiles (java.io.File. "resources/prompts/roles"))]
        (is (contains? prompts (str "prompts/roles/" (.getName f)))
            (str "roles/" (.getName f) " has no role in the shipped map"))))))

(deftest a-solved-run-walks-every-stage-in-order
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom {})
        r (with-redefs [llm/chat (scripted {:programs [right] :calls calls
                                            :manager (fn [n] (if (zero? n)
                                                               {:next "implement it"}
                                                               {:status "done"}))})]
            (run conn d))]
    (is (= :completed (:status r)) (pr-str (:status r)))
    (is (= right (slurp (str d "/solution.py"))))
    (is (= ["plan" "ideate" "manage" "work" "test" "manage" "verify"]
           (stages conn (:run-id r))))
    (testing "the ideators are independent and carry different directives"
      (let [[a b] (:ideate @calls)]
        (is (= 2 (count (:ideate @calls))))
        (is (not= (:content (first a)) (:content (first b))))
        (is (not (str/includes? (str (:content (second b))) "Proposal")))))
    (testing "ideator code never reaches the notes the worker reads"
      (let [notes (:notes (journal/last-note conn (:run-id r) :team-solve-ledger))]
        (is (str/includes? notes "worker note") "the worker rewrote the notes")))
    (testing "the worker is a fresh context fed from the ledger"
      (let [task (str/join "\n" (map (comp str :content) (first (:worker @calls))))]
        (is (str/includes? task "sum them") "the plan")
        (is (str/includes? task "Proposal 1") "the ideators' notes")
        (is (str/includes? task "YOUR TASK: implement it"))
        (is (not (str/includes? task "print(1)")) "no ideator code")))
    (testing "the manager reads what actually happened"
      (let [second-look (str/join "\n" (map (comp str :content) (second (:manage @calls))))]
        (is (str/includes? second-look "[CHECK: PASSED all 2 public samples"))
        (is (str/includes? second-look right) "the program itself")))))

(deftest done-on-a-failing-check-is-overridden
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom {})
        r (with-redefs [llm/chat (scripted {:programs [wrong right] :calls calls
                                            :manager (fn [n] (case n
                                                               0 {:next "implement"}
                                                               1 {:status "done"}
                                                               {:status "done"}))})]
            (run conn d))
        rs (rounds conn (:run-id r))]
    (is (= :completed (:status r)))
    (is (true? (:overrode-done (second rs))) (pr-str (second rs)))
    (is (= right (slurp (str d "/solution.py"))))
    (testing "the failure reached the manager in the check's own words"
      (let [look (str/join "\n" (map (comp str :content) (second (:manage @calls))))]
        (is (str/includes? look "[CHECK: FAILED, passed 0/2"))
        (is (str/includes? look "expected=\"6\" got=\"7\""))))))

(deftest passing-tests-a-worker-rewrote-proves-nothing
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        wrote (atom 0)
        model (scripted {:manager (fn [_] {:next "again"})
                         :worker (fn [messages]
                                   (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
                                     (if (str/includes? all "<tool_result tool=\"write_file\"")
                                       (if (zero? @wrote)
                                         (do (swap! wrote inc)
                                             ;; Rewrites the expected output to match a wrong program.
                                             (spit (str d "/tests/1.out") "7\n")
                                             (spit (str d "/tests/2.out") "31\n")
                                             {:content (json-call "done" {"answer" finished}) :finish-reason "stop"})
                                         {:content (json-call "done" {"answer" finished}) :finish-reason "stop"})
                                       {:content (json-call "write_file" {"path" "solution.py" "content" wrong})
                                        :finish-reason "stop"})))})
        r (with-redefs [llm/chat model]
            (with-policy {:unstick-wait-ms 0 :max-rounds 2} #(run conn d)))]
    (is (not= :completed (:status r)) (pr-str (:status r)))
    (is (= "6\n" (slurp (str d "/tests/1.out"))) "the samples are back as they started")
    (is (some #(= "samples were modified" (:gate %)) (journal/notes conn (:run-id r) :workflow-position)))))

(deftest passing-work-is-not-sent-back-on-the-managers-word
  (testing "loss mode 1, no counterexample: a green program ships"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          r (with-redefs [llm/chat (scripted {:programs [right wrong]
                                              :manager (fn [_] {:next "polish it"})})]
              (run conn d))]
      (is (= :completed (:status r)))
      (is (= right (slurp (str d "/solution.py"))))))
  (testing "loss mode 1, a counterexample the program satisfies: it ships"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          r (with-redefs [llm/chat (scripted {:programs [right wrong]
                                              :manager (fn [n]
                                                         (if (zero? n)
                                                           {:next "implement"}
                                                           {:next "rewrite it"
                                                            :counterexample "INPUT:\n2 2\nEXPECTED:\n4"}))})]
              (run conn d))]
      (is (= :completed (:status r)))
      (is (= right (slurp (str d "/solution.py"))))
      (is (= "false" (:counterexample (last (rounds conn (:run-id r)))))))))

(deftest a-worse-round-cannot-destroy-passing-work
  (testing "loss mode 2: a checked counterexample buys a round, and the round makes it worse"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          r (with-redefs [llm/chat (scripted {:programs [right wrong]
                                              :manager (fn [n]
                                                         (case n
                                                           0 {:next "implement"}
                                                           1 {:next "fix the case"
                                                              :counterexample "INPUT:\n2 2\nEXPECTED:\n5"}
                                                           {:status "done"}))})]
              (run conn d))]
      (is (= :completed (:status r)))
      (is (= right (slurp (str d "/solution.py"))) "the last passing work is back")
      (is (true? (:restored (journal/last-note conn (:run-id r) :team-solve-verify))))
      (is (str/includes? (str (:answer r)) "restored") (pr-str (:answer r)))))
  (testing "the LAST passing work is what comes back, not the first"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          r (with-redefs [llm/chat (scripted {:programs [right other-right wrong]
                                              :manager (fn [n]
                                                         (case n
                                                           0 {:next "implement"}
                                                           1 {:next "fix one"
                                                              :counterexample "INPUT:\n2 2\nEXPECTED:\n5"}
                                                           {:status "done"}))})]
              (with-policy {:max-green-revisions 2} #(run conn d)))]
      (is (= :completed (:status r)))
      (is (= other-right (slurp (str d "/solution.py")))))))

(deftest a-manager-repeating-itself-reaches-the-supervisor
  (testing "the supervisor's retask ends the wait and is the next task"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          calls (atom {})
          answered (atom false)
          base (scripted {:programs [wrong wrong right] :calls calls
                          :manager (fn [n] (if (< n 3) {:next "same task"} {:status "done"}))})
          r (with-redefs [llm/chat base]
              (with-policy {:unstick-wait-ms 60000 :unstick-poll-ms 10}
                #(let [t (future (run conn d))]
                   ;; The cell is waiting and no model call will fire: answer here.
                   (loop [i 0]
                     (let [rid (:run_id (first (db/fetch conn ["SELECT run_id FROM events LIMIT 1"])))]
                       (cond
                         (realized? t) nil
                         (and rid (not @answered) (seq (journal/notes conn rid :workflow-signal)))
                         (do (reset! answered true)
                             (interventions/submit! conn rid {:kind "retask"
                                                              :payload {:text "use a fold"}
                                                              :issued-by "supervisor"}))
                         (< i 6000) (do (Thread/sleep 10) (recur (inc i))))))
                   @t)))
          id (:run-id r)
          unstuck (journal/last-note conn id :team-solve-unstick)]
      (is (= "supervisor" (:by unstuck)) (pr-str unstuck))
      (is (some #(str/includes? (str/join " " (map :content %)) "YOUR TASK: use a fold")
                (:worker @calls))
          "the retask reached a worker")
      (is (empty? (interventions/pending conn id)) "nothing is left queued")))
  (testing "a silent supervisor cannot hang it: the mechanical way out runs"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          calls (atom {})
          r (with-redefs [llm/chat (scripted {:programs [wrong wrong right] :calls calls
                                              :manager (fn [n] (if (< n 3) {:next "same task"} {:status "done"}))})]
              (with-policy {:unstick-wait-ms 0} #(run conn d)))
          unstuck (journal/last-note conn (:run-id r) :team-solve-unstick)]
      (is (= "fallback" (:by unstuck)))
      (is (= :completed (:status r)))
      (is (some #(str/includes? (str/join " " (map :content %)) "Write a FRESH attempt")
                (:worker @calls))))))

(deftest a-supervisor-that-concludes-without-a-directive-still-reaches-the-team
  ;; A pass ended with a written verdict and no intervene call, and the team sat out
  ;; its whole timeout and then ignored it.
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom {})
        said (atom false)
        base (scripted {:programs [wrong wrong right] :calls calls
                        :manager (fn [n] (if (< n 3) {:next "same task"} {:status "done"}))})
        r (with-redefs [llm/chat base]
            (with-policy {:unstick-wait-ms 60000 :unstick-poll-ms 10}
              #(let [t (future (run conn d))]
                 (loop [i 0]
                   (let [rid (:run_id (first (db/fetch conn ["SELECT run_id FROM events LIMIT 1"])))]
                     (cond
                       (realized? t) nil
                       (and rid (not @said) (seq (journal/notes conn rid :workflow-signal)))
                       (do (reset! said true)
                           (journal/note! conn rid :oversight {:data {:notes "try a fold instead"}}))
                       (< i 6000) (do (Thread/sleep 10) (recur (inc i))))))
                 @t)))
        un (journal/last-note conn (:run-id r) :team-solve-unstick)]
    (is (= "supervisor" (:by un)) (pr-str un))
    (is (some #(str/includes? (str/join " " (map :content %)) "try a fold instead") (:worker @calls)))))

(deftest a-reviewers-thinking-is-not-its-verdict
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        r (with-redefs [llm/chat (scripted {:manager (fn [n] (if (zero? n) {:next "add it"} {:status "done"}))
                                            :worker (repo-worker "ok.txt")
                                            :review (fn [_] "<think>let me look at this</think>\nPASS")})]
            (run conn d {:problem "Create ok.txt."
                         :config {:run {:loop "team-solve" :root d :verify-cmd "test -f ok.txt"}}}))]
    (is (= :completed (:status r)) (pr-str (:status r)))))

(deftest the-same-failure-repeating-is-stuck-even-with-new-tasks
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        r (with-redefs [llm/chat (scripted {:programs [wrong wrong wrong right]
                                            :manager (fn [n]
                                                       (if (> n 4) {:status "done"} {:next (str "try " n)}))})]
            (with-policy {:unstick-wait-ms 0} #(run conn d)))]
    (is (seq (journal/notes conn (:run-id r) :team-solve-unstick)))
    (is (= :completed (:status r)))))

(deftest work-that-never-passes-ends-honestly-and-within-its-bound
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom {})
        r (with-redefs [llm/chat (scripted {:programs [wrong] :calls calls
                                            :manager (fn [n] {:next (str "attempt " n)})})]
            (with-policy {:unstick-wait-ms 0 :max-rounds 4 :stall-rounds 99} #(run conn d)))]
    (is (not= :completed (:status r)) (pr-str (:status r)))
    (is (= "fail" (:final (journal/last-note conn (:run-id r) :team-solve-verify))))
    (testing "the bound holds, including the one finalize round"
      (is (= 5 (count (filter (fn [m] (not (str/includes? (str/join " " (map :content (rest m))) "<tool_result")))
                              (:worker @calls))))))
    (testing "the last worker was told to finalize"
      (is (some #(str/includes? (str/join " " (map :content %)) "DEFINITIVE") (:worker @calls))))))

(deftest a-worker-cut-off-is-summarized-and-keeps-the-notes
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom {})
        r (with-redefs [llm/chat
                        (scripted {:calls calls
                                   :manager (fn [i] (if (< i 2) {:next (str "go " i)} {:status "done"}))
                                   :worker (fn [messages]
                                             (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
                                               (if (str/includes? all "YOUR TASK: go 0")
                                                 ;; Reads forever: hits the worker's cap.
                                                 {:content (json-call "read_file" {"path" (str "tests/1.in")})
                                                  :finish-reason "stop"}
                                                 (if (str/includes? all "<tool_result tool=\"write_file\"")
                                                   {:content (json-call "done" {"answer" finished}) :finish-reason "stop"}
                                                   {:content (json-call "write_file" {"path" "solution.py" "content" right})
                                                    :finish-reason "stop"}))))})]
            (with-policy {:worker-max-turns 3} #(run conn d)))]
    (is (= :completed (:status r)) (pr-str (:status r)))
    (is (= 1 (count (:cutoff @calls))) "the summarizer ran once")
    (testing "the manager heard about it"
      (let [look (str/join "\n" (map (comp str :content) (second (:manage @calls))))]
        (is (str/includes? look "CUT OFF"))
        (is (str/includes? look "never wrote anything"))))))

(deftest a-stop-directive-ends-the-run-unsolved-with-the-reason
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        model (scripted {:programs [wrong]
                         :manager (fn [_] {:next "again"})})
        wrapped (fn [a b msgs & more]
                  (let [rid (:run_id (first (db/fetch conn ["SELECT run_id FROM events LIMIT 1"])))]
                    (when (and rid (empty? (interventions/history conn rid)))
                      (interventions/submit! conn rid {:kind "stop" :payload {:text "the samples contradict the task"}
                                                       :issued-by "supervisor"})))
                  (apply model a b msgs more))
        r (with-redefs [llm/chat wrapped] (run conn d))]
    (is (not= :completed (:status r)))
    (is (= "fail" (:final (journal/last-note conn (:run-id r) :team-solve-verify))))
    (is (empty? (interventions/pending conn (:run-id r))))))

(deftest with-no-check-it-works-on-a-repository-and-says-it-is-unverified
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        r (with-redefs [llm/chat (scripted {:manager (fn [n] (if (zero? n) {:next "edit it"} {:status "done"}))
                                            :worker (fn [messages]
                                                      (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
                                                        (if (str/includes? all "<tool_result tool=\"write_file\"")
                                                          {:content (json-call "done" {"answer" finished}) :finish-reason "stop"}
                                                          {:content (json-call "write_file" {"path" "feature.txt" "content" "new"})
                                                           :finish-reason "stop"})))})]
            (run conn d {:problem "Add a feature file."}))]
    (is (= :completed (:status r)) (pr-str (:status r)))
    (is (true? (:unverified (journal/last-note conn (:run-id r) :team-solve-verify))))
    (is (str/includes? (str (:answer r)) "NO CHECK RAN"))))

(deftest a-verify-command-is-the-gate-when-a-repository-has-one
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        r (with-redefs [llm/chat (scripted {:manager (fn [n] (if (zero? n) {:next "add it"} {:status "done"}))
                                            :worker (fn [messages]
                                                      (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
                                                        (if (str/includes? all "<tool_result tool=\"write_file\"")
                                                          {:content (json-call "done" {"answer" finished}) :finish-reason "stop"}
                                                          {:content (json-call "write_file" {"path" "ok.txt" "content" "yes"})
                                                           :finish-reason "stop"})))})]
            (run conn d {:problem "Create ok.txt."
                         :config {:run {:loop "team-solve" :root d :verify-cmd "test -f ok.txt"}}}))]
    (is (= :completed (:status r)) (pr-str (:status r)))
    (is (= "verify passed" (:gate (journal/last-note conn (:run-id r) :team-solve-verify))))))

(deftest a-work-tree-snapshot-restores-files-and-leaves-the-harness-alone
  (let [d (repo-dir)]
    (.mkdirs (java.io.File. (str d "/.samizdat")))
    (spit (str d "/.samizdat/live.db") "before")
    (spit (str d "/a.txt") "good")
    (let [tree (gitdiff/work-tree-id d)]
      (is (string? tree))
      (spit (str d "/a.txt") "bad")
      (spit (str d "/new.txt") "should go")
      (spit (str d "/.samizdat/live.db") "after")
      (is (true? (gitdiff/restore-work-tree! d tree)))
      (is (= "good" (slurp (str d "/a.txt"))))
      (is (not (.exists (java.io.File. (str d "/new.txt")))))
      (is (= "after" (slurp (str d "/.samizdat/live.db"))) "the live database is not the work"))))

(deftest a-crash-names-its-cause-and-its-stage
  ;; A cell that threw reached the journal as "branch error: execution error".
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        real prompt/prompt
        r (with-redefs [prompt/prompt (fn [n] (if (= "team-solve-plan-system" n)
                                                (throw (ex-info "no prompt role :the-cause" {}))
                                                (real n)))
                        llm/chat (scripted {:manager (fn [_] {:status "done"})})]
            (beam/run! {:conn conn
                        :config {:run {:loop "team-solve" :root d :beam-width 1}}
                        :llm-adapter :a :llm-config {:max-tokens 16384}
                        :problem "Sum the integers." :max-turns 10}))
        errs (journal/notes conn (:run-id r) :stage-error)]
    (is (some #(str/includes? (str (:error %)) "no prompt role :the-cause") errs) (pr-str errs))))

(deftest a-model-that-cannot-be-reached-ends-the-run-quickly-and-says-so
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        calls (atom 0)
        r (with-redefs [llm/chat (fn [& _] (swap! calls inc) (throw (ex-info "429 quota" {})))]
            (with-policy {:call-retries 0} #(run conn d)))
        id (:run-id r)]
    (is (not= :completed (:status r)))
    (is (= "verify" (last (stages conn id))))
    (is (< @calls 12) "no more rounds against a dead provider")
    (is (= 1 (count (journal/notes conn id :workflow-signal))) "one signal for one error")
    (is (str/includes? (str (:stopped (journal/last-note conn id :team-solve-ledger))) "429 quota"))))

(deftest a-repository-change-gets-an-independent-review-before-it-ships
  (testing "a reviewer that sends it back buys one bounded round, with its findings as the task"
    (let [conn (db/open! ":memory:")
          d (repo-dir)
          calls (atom {})
          verdicts (atom ["REVISE\n- pytest.py is a stand-in that makes the check pass" "PASS"])
          r (with-redefs [llm/chat (scripted {:manager (fn [n] (if (zero? n) {:next "add it"} {:status "done"}))
                                              :worker (repo-worker "ok.txt")
                                              :calls calls
                                              :review (fn [_] (let [v (first @verdicts)]
                                                                (swap! verdicts rest)
                                                                v))})]
              (run conn d {:problem "Create ok.txt."
                           :config {:run {:loop "team-solve" :root d :verify-cmd "test -f ok.txt"}}}))]
      (is (= :completed (:status r)) (pr-str (:status r)))
      (is (= 2 (count (:review @calls))))
      (is (some #(str/includes? (str/join " " (map :content %)) "YOUR TASK: The independent review sent this back")
                (:worker @calls)))
      (testing "the review reads the change and the check, not the worker's claims"
        (let [seen (str/join " " (map :content (first (:review @calls))))]
          (is (str/includes? seen "Create ok.txt."))
          (is (str/includes? seen "ok.txt"))
          (is (str/includes? seen "CHECK: PASSED"))))))
  (testing "past its bound it stops sending work back, and the run ends unsolved saying why"
    (let [conn (db/open! ":memory:")
          d (repo-dir)
          r (with-redefs [llm/chat (scripted {:manager (fn [n] (if (zero? n) {:next "add it"} {:status "done"}))
                                              :worker (repo-worker "ok.txt")
                                              :review (fn [_] "REVISE\n- still a stand-in")})]
              (with-policy {:max-reviews 2}
                #(run conn d {:problem "Create ok.txt."
                              :config {:run {:loop "team-solve" :root d :verify-cmd "test -f ok.txt"}}})))]
      (is (not= :completed (:status r)) (pr-str (:status r)))
      (is (str/includes? (str (:stopped (journal/last-note conn (:run-id r) :team-solve-ledger))) "still a stand-in"))))
  (testing "a program judged by its samples needs no reviewer: hidden correctness is the samples' claim"
    (let [conn (db/open! ":memory:")
          d (problem-dir)
          calls (atom {})
          r (with-redefs [llm/chat (scripted {:programs [right] :calls calls
                                              :manager (fn [n] (if (zero? n) {:next "x"} {:status "done"}))})]
              (run conn d))]
      (is (= :completed (:status r)))
      (is (empty? (:review @calls))))))

(deftest finish-sends-team-solve-to-its-verify-stage
  ;; The verify stage's script judges the work, so finish there is not a claim
  ;; anyone has to trust, and unlike stop it does not mark the run stopped.
  (let [drain @(cells/cell-var 'cells.team-solve 'drain-directives)
        conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (interventions/submit! conn rid {:kind "finish" :payload {:text "the check holds"}})
    (is (= "the check holds" (:finish (drain {:conn conn :run-id rid} 1))))
    (is (= "applied" (:status (first (interventions/history conn rid)))))))

;; End to end through the real beam and the real turn loop, karamazov-i692: what
;; the journals showed was a correct program and a run that never ended.
(defn- run-loop [conn d turns]
  (with-redefs [select/triage! (constantly nil)]
    (beam/run! {:conn conn :config {:run {:loop "loop" :root d}}
                :llm-adapter :a :llm-config {:max-tokens 16384}
                :problem "Sum the integers on stdin."
                :max-turns turns :beam-width 1 :oversight? false})))

(defn- worker-that-never-finishes
  "Claims a task, writes a right program, checks it, then reads it for ever. `then` runs once,
  after the check."
  [conn then]
  (let [once (atom false)]
    (fn [_ _ messages & _]
      (let [all (str/join "\n" (map (comp str :content) messages))]
        (cond
          (not (str/includes? all "Created sz-"))
          {:content (json-call "task" {"action" "create" "title" "sum"}) :finish-reason "stop"}
          (not (str/includes? all "Claimed sz-"))
          {:content (json-call "task" {"action" "claim" "id" (re-find #"sz-[0-9a-f]+" all)}) :finish-reason "stop"}
          (not (str/includes? all "Wrote "))
          {:content (json-call "write_file" {"path" "solution.py" "content" right}) :finish-reason "stop"}
          (not (str/includes? all "PASSED"))
          {:content (json-call "run_check" {}) :finish-reason "stop"}
          :else
          (do (when (compare-and-set! once false true)
                (then (:id (first (runs/list-runs conn)))))
              {:content (json-call "read_file" {"path" "solution.py"}) :finish-reason "stop"}))))))

(deftest finish-ends-a-loop-run-whose-worker-will-not
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        r (with-redefs [llm/chat (worker-that-never-finishes
                                  conn
                                  (fn [id]
                                    (interventions/submit!
                                     conn id {:kind "finish" :issued-by "supervisor"
                                              :payload {:text "solution.py passes every sample"}})))]
            (run-loop conn d 14))]
    (is (= :completed (:status r)) (pr-str r))
    (is (= "solution.py passes every sample" (:final_answer (runs/get-run conn (:run-id r)))))))

(deftest a-manifest-saved-mid-run-drives-that-runs-next-round
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        r (with-redefs [llm/chat (worker-that-never-finishes
                                  conn
                                  (fn [_]
                                    (us/save! conn :manifest "loop"
                                              (str (:body (us/load-latest conn :manifest "loop")) "\n;; tuned\n")
                                              "project" "tuned mid-run")))]
            (run-loop conn d 9))]
    (is (= 2 (:version (journal/last-note conn (:run-id r) :loop-refreshed)))
        (pr-str (journal/last-note conn (:run-id r) :loop-refresh-failed)))))

(deftest a-run-at-its-cap-asks-and-can-be-extended
  ;; A run can go from start to cap inside the stream's spacing: one fixture spent
  ;; 45 s and got no look at all. The cap has to be a question, not just an end.
  (let [conn (db/open! ":memory:")
        d (problem-dir)
        asked (atom nil)
        real beam/await-cap-decision!
        r (with-redefs [llm/chat (worker-that-never-finishes conn (fn [_] nil))
                        beam/await-cap-decision!
                        (fn [ctx cap _ _]
                          (reset! asked cap)
                          (interventions/submit! (:conn ctx) (:run-id ctx)
                                                 {:kind "finish" :issued-by "supervisor"
                                                  :payload {:text "the check holds"}})
                          (real ctx cap 1000 10))]
            (beam/run! {:conn conn :config {:run {:loop "loop" :root d}}
                        :llm-adapter :a :llm-config {:max-tokens 16384}
                        :problem "Sum the integers on stdin."
                        :max-turns 6 :beam-width 1}))]
    (is (= 6 @asked))
    (is (= :completed (:status r)) (pr-str (:status r)))
    (is (seq (journal/notes conn (:run-id r) :workflow-signal)))))

(deftest silence-at-the-cap-ends-the-run-as-before
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (is (false? (beam/await-cap-decision! {:conn conn :run-id rid} 6 0 1)))))

;; --- the manager routes, the worker owns the request (karamazov-lu4p.5) --------

(defn- manage-texts [calls] (mapv #(str (:content (second %))) (:manage @calls)))

(deftest a-worker-that-changed-nothing-is-reported-as-having-changed-nothing
  ;; On csview a worker spent 25 of 30 minutes on a task that wrote only to /tmp,
  ;; and the manager had only its report to go on.
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        calls (atom {})
        worker (fn [messages]
                 (let [all (str/join "\n" (map (comp str :content) (rest messages)))]
                   (if (or (str/includes? all "<tool_result") (str/includes? all "study"))
                     {:content (json-call "done" {"answer" finished}) :finish-reason "stop"}
                     {:content (json-call "write_file" {"path" "feature.txt" "content" "x"})
                      :finish-reason "stop"})))]
    (with-redefs [llm/chat (scripted {:calls calls :worker worker
                                      :manager (fn [n] (case n
                                                         0 {:next "study it"}
                                                         1 {:next "build it"}
                                                         {:status "done"}))})]
      (run conn d {:problem "Add a feature file."}))
    (let [[_ after-study after-build] (manage-texts calls)]
      (is (str/includes? after-study "TREE DID NOT CHANGE"))
      (is (not (str/includes? (str after-build) "TREE DID NOT CHANGE"))))))

(deftest a-long-task-is-cut-to-a-pointer
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        limit (:repo-task-chars (lexicon/policy :team-solve))
        long-task (str/join " " (repeat 400 "probe"))
        r (with-redefs [llm/chat (scripted {:worker (repo-worker "feature.txt")
                                            :manager (fn [n] (if (zero? n) {:next long-task} {:status "done"}))})]
            (run conn d {:problem "Add a feature file."}))
        task (:task (first (rounds conn (:run-id r))))]
    (is (<= (count task) (+ limit 3)))
    (is (str/starts-with? task "probe"))))

(deftest the-manager-is-told-the-time-left
  (let [conn (db/open! ":memory:")
        d (repo-dir)
        calls (atom {})]
    (with-redefs [llm/chat (scripted {:calls calls :worker (repo-worker "feature.txt")
                                      :manager (fn [_] {:status "done"})})]
      (run conn d {:problem "Add a feature file."
                   :config {:run {:loop "team-solve" :root d :time-limit-seconds 1800}}}))
    (is (str/includes? (first (manage-texts calls)) "TIME LEFT"))
    (testing "and with no limit it is not mentioned"
      (is (not (str/includes? (prompt/render "team-solve-manage-user" {:problem "p"}) "TIME LEFT"))))))

(deftest the-roles-say-who-owns-the-request
  (let [manage (prompt/prompt "team-solve-manage-system")
        work (prompt/render "team-solve-work" {:problem "p" :task "t"})
        role (prompt/prompt "roles/solver")]
    (is (str/includes? manage "goes to the worker whole"))
    (is (str/includes? manage "builds and runs"))
    (is (str/includes? work "where to start"))
    (testing "the worker reports in words a program can read"
      (doseq [w ["ready" "progress" "blocked" "failed" "dropped" "### TESTS"]]
        (is (str/includes? role w) w)))))

(deftest a-worker-has-room-for-a-repository-task
  (is (>= (:worker-max-turns (lexicon/policy :team-solve)) 40)))

(deftest against-a-clock-the-clock-limits-a-worker
  ;; A worker at its turn cap with a third of the time left was pushed to finish, and the
  ;; next one started from nothing and spent its turns re-reading the code.
  (let [cap @(cells/cell-var 'cells.team-solve 'worker-turn-cap)
        p (lexicon/policy :team-solve)]
    (is (= (:worker-max-turns p) (cap {} {} p)))
    (is (= (:worker-max-turns-timed p)
           (cap {:started-ms 0 :config {:run {:time-limit-seconds 1800}}} {} p)))
    (is (> (:worker-max-turns-timed p) (:worker-max-turns p)))
    (testing "a budget the run set for itself still wins"
      (is (= 7 (cap {:started-ms 0 :config {:run {:time-limit-seconds 1800}}} {:worker-turns 7} p))))))

(deftest a-worker-and-its-manager-are-told-to-write-it-first
  (testing "the manager's first task is not to study"
    (is (str/includes? (prompt/prompt "team-solve-manage-system") "not to study")))
  (testing "the worker starts small, since asking for all of it at once got one huge write"
    (is (str/includes? (prompt/prompt "roles/solver") "entry point and the build"))
    (is (str/includes? (prompt/prompt "roles/solver") "one part at a time"))))

(deftest a-manager-against-the-clock-with-nothing-yet-is-told-to-have-it-written
  ;; A rule in the system prompt lost to the plan twice: the first task was a probe matrix.
  (let [render (fn [m] (prompt/render "team-solve-manage-user" (merge {:problem "p" :notes "n" :latest "l"} m)))
        line "Nothing exists yet"]
    (is (str/includes? (render {:time-left 1700}) line))
    (is (not (str/includes? (render {:time-left 1700 :current "(ns a)"}) line)))
    (is (not (str/includes? (render {}) line)) "and with no clock it reads as it did")))

(deftest the-model-is-told-to-write-rather-than-draft-in-its-head
  ;; Three turns of drafting took 1018 s of a figlet run, with up to 67K characters of thinking.
  (is (str/includes? (prompt/prompt "system") "Do not draft a program in your thinking"))
  (testing "and the worker, whose frame replaces that prompt"
    (is (str/includes? (prompt/prompt "roles/solver") "Do not draft a program in your thinking"))))
