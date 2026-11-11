;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.team-solve-parity-test
  "On a program with samples, team-solve IS GVS5H's multiagent_solve: this is what
  makes that a fact and not a claim. fixtures/gvs5h_parity.json is what their own
  code sent a scripted model, written by evals/team-solve/parity/gen_golden.py on
  memdoor's four scenarios. Each request has to match byte for byte, with the
  same temperature, in the same order, and leave the same program on disk.

  Our additions may not appear here: the supervisor when it says nothing, and the
  loss guards, which are off for the comparison and pinned on their own below."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.beam]
            [samizdat.agent.sections :as sections]
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.workflow :as workflow]))

(def ^:private golden
  (json/read-str (slurp "test/samizdat/fixtures/gvs5h_parity.json")))

(defn- role-of [messages]
  (let [s (str (:content (first messages)))
        u (str (:content (second messages)))]
    (cond
      (str/starts-with? s "You are the PRIMARY orchestrator (manager)") "plan"
      (str/starts-with? s "You are the FIRST WORKER") "ideate"
      (str/starts-with? s "You are the PRIMARY orchestrator and manager") "manage"
      (str/starts-with? s "A worker's solution attempt was CUT OFF") "cutoff"
      (str/ends-with? u "Produce the DEFINITIVE final solution now, using all notes and current work.") "finalize"
      :else "worker")))

(defn- scripted [script calls]
  (let [used (atom {})]
    (fn [_ cfg messages & _]
      (if (str/starts-with? (str (:content (first messages))) "You read a manager's reply")
        ;; The loss guard's own call, which theirs does not make.
        {:content "### INPUT\nnone" :finish-reason "stop"}
      (let [r (role-of messages)
            i (get (swap! used update r (fnil inc -1)) r)
            entries (get script r)
            e (nth entries (min i (dec (count entries))))
            [content finish] (if (string? e) [e "stop"] e)]
        (swap! calls conj {"role" r "temperature" (:temperature cfg)
                           "system" (:content (first messages))
                           "user" (:content (second messages))})
        {:content content :finish-reason finish})))))

(defn- project []
  (let [d (str "/tmp/samizdat-parity-" (System/nanoTime))]
    (.mkdirs (java.io.File. (str d "/tests")))
    (doseq [[i [in out]] (map-indexed vector (get golden "tests"))]
      (spit (str d "/tests/" (inc i) ".in") in)
      (spit (str d "/tests/" (inc i) ".out") out))
    d))

(defn- with-policy [overrides f]
  (let [real lexicon/policy]
    (with-redefs [lexicon/policy (fn [k] (cond-> (real k) (= :team-solve k) (merge overrides)))]
      (f))))

(defn- run-scenario [name overrides]
  (let [{:strs [script]} (get-in golden ["scenarios" name])
        calls (atom [])
        d (project)
        r (with-policy (merge {:unstick-wait-ms 0} overrides)
            #(with-redefs [llm/chat (scripted script calls)]
               (workflow/run! {:conn (db/open! ":memory:")
                               :config {:run {:loop "team-solve" :root d}}
                               :llm-adapter :a :llm-config {:max-tokens 128000}
                               :problem (get golden "problem") :max-turns 200})))]
    {:calls @calls :result r
     :final (let [f (java.io.File. d "solution.py")] (when (.exists f) (slurp f)))}))

(defn- first-difference [a b]
  (let [n (min (count a) (count b))
        i (or (first (filter #(not= (nth a %) (nth b %)) (range n))) n)
        at (fn [s] (subs s (max 0 (- i 60)) (min (count s) (+ i 120))))]
    (str "first differs at " i "\n  theirs: " (pr-str (at a)) "\n  ours:   " (pr-str (at b)))))

(defn- same-requests? [name]
  (let [{:strs [calls final]} (get-in golden ["scenarios" name])
        {ours :calls our-final :final} (run-scenario name {:loss-guards? false})]
    (testing name
      (is (= (mapv #(get % "role") calls) (mapv #(get % "role") ours)) "the same calls in the same order")
      (doseq [[k theirs mine] (map vector (range) calls ours)
              field ["system" "user"]]
        (when (not= (get theirs field) (get mine field))
          (is false (str name " call " k " (" (get theirs "role") ") " field ": "
                         (first-difference (str (get theirs field)) (str (get mine field)))))))
      (doseq [[k theirs mine] (map vector (range) calls ours)]
        (is (= (double (get theirs "temperature")) (double (or (get mine "temperature") -1)))
            (str name " call " k " temperature")))
      ;; Theirs creates an empty solution.py up front; no file here says the same.
      (is (= final (str our-final)) "the same program is left on disk"))))

(deftest a-straight-solve-is-request-for-request-theirs (same-requests? "happy"))
(deftest cut-offs-a-wrong-program-and-a-repeated-task-are-theirs (same-requests? "hard"))
(deftest ten-rounds-then-finalize-is-theirs (same-requests? "ten"))
(deftest a-model-that-says-nothing-useful-is-handled-as-theirs (same-requests? "empty"))

(deftest the-loss-guard-keeps-the-last-passing-program
  ;; Their run left print(0) on disk here, the last round's wrong program, after a
  ;; passing one existed: arc196_c and arc196_d's loss mode. The guard changes what
  ;; ships and no request.
  (let [{:strs [calls]} (get-in golden ["scenarios" "ten"])
        {ours :calls our-final :final} (run-scenario "ten" {:loss-guards? true})]
    (is (= (mapv #(get % "user") calls) (mapv #(get % "user") ours)))
    (is (str/includes? our-final "a+b"))))

(deftest what-the-port-reads-like-python
  (testing "repr, which their failure report prints"
    (is (= "'2 3\\n'" (sections/py-repr "2 3\n")))
    (is (= "\"it's\"" (sections/py-repr "it's")))
    (is (= "'a\"b\\'c'" (sections/py-repr "a\"b'c"))))
  (testing "a task line's status"
    (is (= [{:id 1 :desc "x" :status "done"} {:id 2 :desc "y" :status "in_progress"} {:id 3 :desc "z" :status "pending"}]
           (sections/parse-task-list "- [done] x\n- [wip] y\n- z" 12)))))

;; The first published loss mode, abc399_e: the manager invented a counterexample
;; with a wrong expected answer and the team rewrote a correct program. Their fix
;; is to check it against a brute force on small inputs. Extra calls, so only with
;; the guards on, and only when a manager continues on passing samples.
(def ^:private right-program "import sys\na,b=map(int,sys.stdin.read().split())\nprint(a+b)")
(def ^:private brute "import sys\nprint(sum(map(int,sys.stdin.read().split())))")

(defn- guard-run
  "A green first round, then a manager who continues, saying `said`. `extracted` is
  what the guard reads out of that, and `brute-reply` its brute force."
  [said extracted brute-reply]
  (let [workers (atom 0) managers (atom 0) calls (atom [])
        model (fn [_ _ messages & _]
                (let [sys (str (:content (first messages)))
                      reply (cond
                              (str/starts-with? sys "You are the PRIMARY orchestrator (manager)")
                              "### PLAN\nadd\n### TASKS\n- add"
                              (str/starts-with? sys "You are the FIRST WORKER") "### NOTES\nadd\n### NEXT\n- add"
                              (str/starts-with? sys "You are the PRIMARY orchestrator and manager")
                              (case (swap! managers inc)
                                1 "### STATUS\ncontinue\n### NEXT\nImplement the sum\n### TASKS\n- [todo] sum"
                                2 (str "### STATUS\ncontinue\n### NEXT\n" said "\n### TASKS\n- [todo] fix")
                                "### STATUS\ndone\n### TASKS\n- [done] sum")
                              (str/starts-with? sys "You read a manager's reply") extracted
                              (str/starts-with? sys "Write a brute force") brute-reply
                              :else (do (swap! workers inc)
                                        (str "### CODE\n```python\n" right-program
                                             "\n```\n### NOTES\n- **x:** y\n### NEXT\n- none\n### STATUS\nsolved")))]
                  (swap! calls conj sys)
                  {:content reply :finish-reason "stop"}))
        d (project)
        conn (db/open! ":memory:")
        r (with-policy {:unstick-wait-ms 0}
            #(with-redefs [llm/chat model]
               (workflow/run! {:conn conn :config {:run {:loop "team-solve" :root d}}
                               :llm-adapter :a :llm-config {:max-tokens 128000}
                               :problem (get golden "problem") :max-turns 200})))]
    {:workers @workers :status (:status r)
     :guard (journal/last-note conn (:run-id r) :team-solve-guard)}))

(deftest a-wrong-counterexample-does-not-rewrite-a-correct-program
  (let [r (guard-run "It fails on 2 3, which should print 6."
                     "### INPUT\n2 3\n### EXPECTED\n6"
                     (str "```python\n" brute "\n```"))]
    (is (= 1 (:workers r)) "no second worker")
    (is (= :completed (:status r)))
    (is (= "brute force agrees with the program" (:verdict (:guard r))))))

(deftest a-claim-the-program-already-satisfies-is-overruled-without-a-brute-force
  (let [r (guard-run "It fails on 2 3, which should print 5."
                     "### INPUT\n2 3\n### EXPECTED\n5"
                     "should not be asked")]
    (is (= 1 (:workers r)))
    (is (= "the program already prints it" (:verdict (:guard r))))))

(deftest a-real-counterexample-still-sends-the-team-back
  (let [r (guard-run "It fails on 2 3, which should print 6."
                     "### INPUT\n2 3\n### EXPECTED\n6"
                     "```python\nprint(6)\n```")]
    (is (= 2 (:workers r)) "the brute force agrees with the manager")
    (is (= "brute force agrees with the manager" (:verdict (:guard r))))))

(deftest a-doubt-with-no-counterexample-is-still-the-managers-call
  ;; Slowness cannot be written as a short counterexample: this is why the manager's
  ;; continue on passing samples stands, unlike the earlier rule that shipped.
  (let [r (guard-run "This is O(N^2) and will time out at N=200000; use a Fenwick tree."
                     "### INPUT\nnone"
                     "should not be asked")]
    (is (= 2 (:workers r)))))

(deftest a-brute-force-that-will-not-run-proves-nothing
  (let [r (guard-run "It fails on 2 3, which should print 6."
                     "### INPUT\n2 3\n### EXPECTED\n6"
                     "```python\nraise SystemExit(3)\n```")]
    (is (= 2 (:workers r)) "no evidence against the manager, so its word stands")))

(deftest what-their-numbers-were-measured-under-reaches-a-local-model
  (let [seen (atom nil)
        model (fn [_ cfg _ & _] (reset! seen cfg) {:content "" :finish-reason "stop"})
        d (project)]
    (with-policy {:unstick-wait-ms 0}
      #(with-redefs [llm/chat model]
         (workflow/run! {:conn (db/open! ":memory:") :config {:run {:loop "team-solve" :root d}}
                         :llm-adapter :a :llm-config {:provider :local :max-tokens 8192}
                         :problem "p" :max-turns 20})))
    (is (= 128000 (:max-tokens @seen)))
    (is (= [0.95 20] [(:top-p @seen) (:top-k @seen)]))
    (is (= 14400000 (:max-response-ms @seen))))
  (testing "a hosted model keeps its own ceiling"
    (let [seen (atom nil)
          model (fn [_ cfg _ & _] (reset! seen cfg) {:content "" :finish-reason "stop"})
          d (project)]
      (with-policy {:unstick-wait-ms 0}
        #(with-redefs [llm/chat model]
           (workflow/run! {:conn (db/open! ":memory:") :config {:run {:loop "team-solve" :root d}}
                           :llm-adapter :a :llm-config {:provider :deepseek :max-tokens 8192}
                           :problem "p" :max-turns 20})))
      (is (= 8192 (:max-tokens @seen)))
      (is (nil? (:top-k @seen))))))
