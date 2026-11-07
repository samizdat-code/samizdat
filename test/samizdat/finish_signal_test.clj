(ns samizdat.finish-signal-test
  "Terminal-Bench next-30 (karamazov-0wvc): six trials had passing files and
  ran out the clock without calling done, because closing a task folded the
  work away and asked for the next task, and because the harness had no idea
  of the time limit. Also the mechanical losses seen in those runs."
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.checklist :as checklist]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.phases :as phases]
            [samizdat.agent.state :as state]
            [samizdat.llm.fence :as fence]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.prompt :as prompt]))

(deftest a-call-the-provider-cut-the-opening-off-is-still-recovered
  (fence/install-orphan-tools! (gates/threshold :orphan-call))
  ;; deepseek-flash's trained DSML reached the text with the <invoke> opener
  ;; gone, or with the fence's opener in front: 43 no-call turns in next-30.
  (testing "parameters only, with DSML closers: the tool is read off their names"
    (let [p (fence/parse-tool-call
             "Let me look.\n\n<parameter name=\"path\">src/a.py</｜｜DSML｜｜ parameter>\n<parameter name=\"limit\">40</｜｜DSML｜｜ parameter>\n</｜｜DSML｜｜ invoke>\n</｜｜DSML｜｜ calls>")]
      (is (= "read_file" (:name p)))
      (is (= {:path "src/a.py" :limit 40} (:args p)))))
  (testing "inside a fence, with no closer at all on the last parameter"
    (let [p (fence/parse-tool-call "```tool-call\n<parameter name=\"command\">ls -la")]
      (is (= "shell" (:name p)))
      (is (= "ls -la" (get-in p [:args :command])))))
  (testing "the tool's name written as an empty parameter"
    (let [p (fence/parse-tool-call
             "Run it.\n\n<parameter name=\"shell\">\n<parameter name=\"command\">make test</parameter>\n</invoke>")]
      (is (= "shell" (:name p)))
      (is (= "make test" (get-in p [:args :command])))))
  (testing "name and args written as parameters"
    (let [p (fence/parse-tool-call
             "```tool-call\n<parameter name=\"name\">shell</parameter>\n<parameter name=\"args\">{\"command\":\"ls\"}</parameter>\n</invoke>")]
      (is (= "shell" (:name p)))
      (is (= "ls" (get-in p [:args :command])))))
  (testing "an invoke with no close keeps its name"
    (let [p (fence/parse-tool-call
             "<invoke name=\"write_file\">\n<parameter name=\"path\">a.txt</parameter>\n<parameter name=\"content\">hi</parameter>")]
      (is (= "write_file" (:name p)))
      (is (= "hi" (get-in p [:args :content])))))
  (testing "a body that cannot be read as a call stays a no-call"
    (is (nil? (fence/parse-tool-call "I used a <parameter name=\"foo\">bar</parameter> here.")))
    (is (nil? (fence/parse-tool-call "<invoke name=\"shell\">")))
    (is (nil? (fence/parse-tool-call "Plain prose with no call.")))))

(deftest a-closed-task-points-at-done
  (let [closed (prompt/render "task-none" {:closed-id "sz-1"})
        none (prompt/render "task-none" {})]
    (is (str/includes? closed "sz-1"))
    (is (str/includes? closed "`done`"))
    (is (str/includes? none "No task claimed")))
  (is (str/includes? (prompt/render "task-claimed" {:id "x" :title "y" :budget 500}) "`done`"))
  (testing "the refusal names an action the tool has"
    (let [text (prompt/render "task-required" {:tool-name "shell"})]
      (is (not (str/includes? text "\"board\"")))
      (is (str/includes? text "\"list\"")))))

(deftest the-no-edits-steer-offers-the-way-out
  (is (str/includes? (prompt/prompt "no-edits") "`done`"))
  (is (contains? (:no-edits (gates/tool-vocab :settle-called)) "done")))

(defn- ctx-at [used]
  {:branch (state/new-branch {:id "B1" :problem "p"}) :max-turns 300 :time-used used :time-left-seconds 100})

(deftest the-time-limit-winds-a-run-down-and-then-forces-a-ship
  (let [fired (fn [used] (set (map :gate (arbiter/eligible (ctx-at used)))))
        {:keys [wind-down last-call]} (gates/threshold :time-budget)]
    (testing "no limit, no gate"
      (is (not-any? #{:time-wind-down :time-last-call} (fired nil))))
    (testing "the fractions"
      (is (not-any? #{:time-wind-down :time-last-call} (fired (- wind-down 0.01))))
      (is (contains? (fired wind-down) :time-wind-down))
      (is (contains? (fired last-call) :time-last-call)))
    (testing "the last call forces a done, and tells the time"
      (let [d (arbiter/decide (ctx-at last-call))]
        (is (= :time-last-call (:gate d)))
        (is (= "done" (:tool d)))
        (is (str/includes? (:message d) "100"))))))

(deftest a-configured-limit-reaches-the-gates-through-the-steer-step
  (let [c (db/open! ":memory:")]
    (try
      (db/migrate! c)
      (let [rid (runs/start-run! c {:problem "p" :beam-width 1})
            _ (runs/open-branch! c rid {:branch-id "B1" :created-at-turn 0})
            step (fn [config started]
                   (:content (peek (:messages
                                    (aloop/steer-step
                                     {:conn c :run-id rid :max-turns 300 :role :implementor
                                      :config config :started-ms started}
                                     (state/new-branch {:id "B1" :problem "p"}) 3
                                     {:parsed {:name "read_file" :args {:path "x"}}
                                      :result {:result "x" :category :neutral}})))))
            now (System/currentTimeMillis)]
        (is (str/includes? (step {:run {:time-limit-seconds 100}} (- now 80000)) "**Time:**"))
        (is (not (str/includes? (step {:run {:time-limit-seconds 100}} (- now 1000)) "**Time:**")))
        (is (not (str/includes? (str (step {} (- now 80000))) "**Time:**"))))
      (finally (db/close c)))))

(deftest a-wait-fits-inside-a-turn
  (is (< (:max-wait-ms (gates/threshold :wakeups)) (gates/threshold :turn-deadline-ms))))

(deftest the-share-of-the-limit-used
  (is (nil? (gates/time-used nil 1000 2000)))
  (is (nil? (gates/time-used 100 nil 2000)))
  (is (= 0.5 (gates/time-used 100 0 50000)))
  (is (= 50 (gates/time-left-seconds 100 0 50000)))
  (is (= 0 (gates/time-left-seconds 100 0 500000))))

;; --- ProgramBench pilot (karamazov-0wvc) -----------------------------------

(deftest a-split-clears-the-line-count-without-waiting-for-a-write
  ;; dutree: the count only moved on a file write, so after a split the gate
  ;; still read the old task's 777 lines, and each `task` call "met" it and
  ;; refreshed its budget. It fired 15 turns running and nested subtasks five deep.
  (let [b (state/note-lines {:turns [] :task {:id "T1"}} 777)]
    (is (= 777 (:lines-written b)))
    (testing "the same task keeps its count"
      (is (= 777 (:lines-written (state/rebase-lines b)))))
    (testing "another task starts at zero, and counts from here on"
      (let [b2 (state/rebase-lines (assoc b :task {:id "T2"}))]
        (is (zero? (:lines-written b2)))
        (is (= 30 (:lines-written (state/note-lines b2 807))))))
    (testing "a branch that has written nothing is left alone"
      (let [fresh {:turns [] :task {:id "T1"}}]
        (is (nil? (:lines-written (state/rebase-lines fresh))))))))

(deftest tool-caches-are-not-lines-the-model-wrote
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "lines" (make-array java.nio.file.attribute.FileAttribute 0)))
        run (fn [& args] (apply clojure.java.shell/sh (concat args [:dir dir])))]
    (run "git" "init" "-q")
    (run "git" "-c" "user.email=a@b.c" "-c" "user.name=a" "commit" "-qm" "x" "--allow-empty")
    (let [base (clojure.string/trim (:out (run "git" "rev-parse" "HEAD")))]
      (.mkdirs (java.io.File. (str dir "/.jolt/cpcache")))
      (spit (str dir "/.jolt/cpcache/cp") (str/join "\n" (repeat 700 "x")))
      (spit (str dir "/.nrepl-port") "1234\n")
      (spit (str dir "/a.clj") "(ns a)\n(def x 1)\n")
      (is (= 2 (gitdiff/changed-lines dir base))))))

(deftest harness-state-and-build-output-are-not-the-runs-work
  ;; A task image ships its own .git with no ignore rule for .samizdat/, so every
  ;; prompt and log the harness keeps there read as lines the model had written
  ;; (2615 before its first file) and as files the run had changed.
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "lines" (make-array java.nio.file.attribute.FileAttribute 0)))
        run (fn [& args] (apply clojure.java.shell/sh (concat args [:dir dir])))
        _ (run "git" "init" "-q")
        _ (run "git" "-c" "user.email=a@b.c" "-c" "user.name=a" "commit" "-qm" "x" "--allow-empty")
        base (clojure.string/trim (:out (run "git" "rev-parse" "HEAD")))
        junk (str/join "\n" (repeat 700 "x"))]
    (doseq [rel [".samizdat/prompts/system.md" "executable.build/runtime.ss"]]
      (let [f (java.io.File. (str dir "/" rel))]
        (.mkdirs (.getParentFile f))
        (spit f junk)))
    (is (= 0 (gitdiff/changed-lines dir base)))
    (is (= [] (gitdiff/changed-files dir base)))
    (spit (str dir "/real.clj") "(ns real)\n(def a 1)\n")
    (is (= 2 (gitdiff/changed-lines dir base)))
    (is (= ["real.clj"] (gitdiff/changed-files dir base)))))

(deftest a-checklist-refusal-names-everything-the-next-call-must-carry
  ;; code-minimap: 14 refusals in a row. Told only what was missing, the model
  ;; answered t1 and p1, then c1..c5, then t1 and p1 again.
  (let [items [{:id "t1" :text "first"} {:id "p1" :text "second"} {:id "c1" :text "third"}]
        msg (checklist/refusal [{:id "c1" :text "third"}] items)]
    (is (str/includes? msg "**c1** third"))
    (is (str/includes? msg "t1"))
    (is (str/includes? msg "p1"))
    (is (str/includes? msg "same call"))))

(deftest done-is-not-refused-for-paperwork-forever
  (let [turn (fn [tool] {:turn 1 :tool tool :category :neutral})
        b (reduce state/add-turn {:turns []}
                  (map turn ["shell" "done" "shell" "done" "done"]))]
    (is (= 2 (state/done-streak b)) "only the unbroken run at the end counts")
    (is (zero? (state/done-streak (state/add-turn b (turn "shell")))))
    (testing "the unwritten-plan refusal lets go after a row of refused calls"
      (let [rule (some #(when (= :plan-not-landed (:rule %)) %) (phases/refusals))
            owes (state/declare-plan {:turns []} {:files ["moved.clj"]})
            spent (reduce state/add-turn owes (repeat 3 (turn "done")))]
        (is ((:when rule) {:branch owes :tool-name "done"}))
        (is (not ((:when rule) {:branch spent :tool-name "done"})))))))

(deftest the-time-warning-restates-what-was-asked-for
  ;; code-minimap: warned seven times while holding a subtask, it finished the
  ;; subtask and shipped with the build script the request named never written.
  (let [b {:turns [] :problem "Provide compile.sh that builds it"
           :task {:id "T2" :title "Vertical scaling"}}
        ctx (gates/message-context b 300)]
    (is (= "Provide compile.sh that builds it" (:problem ctx)))
    (let [msg (prompt/render "time-wind-down" (assoc ctx :time-left-seconds 400))]
      (is (str/includes? msg "Provide compile.sh that builds it"))
      (is (str/includes? msg "Vertical scaling")))))

(deftest the-system-prompt-asks-for-the-whole-thing-running-first
  (let [msg (prompt/render "system" {:repl true})]
    (is (str/includes? msg "end to end"))))
