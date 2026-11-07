(ns samizdat.finish-signal-test
  "Terminal-Bench next-30 (karamazov-0wvc): six trials had passing files and
  ran out the clock without calling done, because closing a task folded the
  work away and asked for the next task, and because the harness had no idea
  of the time limit. Also the mechanical losses seen in those runs."
  (:require [clojure.java.shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.config]
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

(deftest a-third-of-the-way-in-a-run-is-asked-whether-the-whole-thing-runs
  ;; figlet first built at 960 s of 1800 and had no -main at turn 128; the system
  ;; prompt's advice to get the whole thing running first had not reached it.
  (let [fired (fn [used] (set (map :gate (arbiter/eligible (ctx-at used)))))
        {:keys [whole-running wind-down]} (gates/threshold :time-budget)]
    (is (< whole-running wind-down))
    (is (not (contains? (fired nil) :time-whole-running)))
    (is (not (contains? (fired (- whole-running 0.01)) :time-whole-running)))
    (is (contains? (fired whole-running) :time-whole-running))
    (let [msg (:message (arbiter/decide (ctx-at whole-running)))]
      (is (str/includes? msg "end to end"))
      (is (str/includes? msg "100")))))

(deftest the-time-gates-stay-bounded-when-they-are-obeyed
  ;; A budget counts firings since the last met settlement, and these gates count as
  ;; met on an ordinary write or shell call: on csview that fired one 24 turns running.
  (let [p (gates/threshold :time-budget)
        fired (fn [used history]
                (set (map :gate (arbiter/eligible
                                 (assoc-in (ctx-at used) [:branch :gate-history] history)))))
        met (fn [g n] (vec (mapcat (fn [t] [{:gate g :turn t} {:gate g :turn t :settled :met}]) (range n))))]
    (testing "asked twice at most, the second time later on"
      (is (contains? (fired (:whole-running p) []) :time-whole-running))
      (is (not (contains? (fired (:whole-running p) (met :time-whole-running 1)) :time-whole-running)))
      (is (contains? (fired (:whole-running-again p) (met :time-whole-running 1)) :time-whole-running))
      (is (not (contains? (fired (:whole-running-again p) (met :time-whole-running 2)) :time-whole-running))))
    (testing "the wind-down speaks as often as its budget says, however it settled"
      (let [n (gates/threshold :max-time-steers)]
        (is (contains? (fired (:wind-down p) (met :time-wind-down (dec n))) :time-wind-down))
        (is (not (contains? (fired (:wind-down p) (met :time-wind-down n)) :time-wind-down)))))))

(deftest glm-has-room-to-think-and-still-call
  ;; Thinking counts against the reply budget. At 16384 a whole-program write on csview
  ;; ran out after 291 s and the call was lost.
  (is (>= (get-in samizdat.config/providers-for-test [:glm :max-tokens]) 65536)))

(deftest the-time-gates-leave-the-run-its-last-stretch
  ;; At 0.7 csview, figlet and yj made their last edit 7 to 9 minutes before their
  ;; runs ended, told a further check was the one thing they could not afford.
  (let [{:keys [wind-down last-call]} (gates/threshold :time-budget)
        words (prompt/prompt "time-wind-down")]
    (is (>= wind-down 0.85))
    (is (>= last-call 0.92))
    (is (not (str/includes? words "cannot afford")))
    (is (str/includes? words "keep fixing"))
    (is (contains? (:time-wind-down (gates/tool-vocab :settle-called)) "edit_file"))))

(deftest the-no-edits-steer-does-not-talk-a-run-out-of-checking
  ;; Comparing against a reference is how a run finds what to fix, and it writes
  ;; nothing while it does.
  (let [words (prompt/prompt "no-edits")]
    (is (not (str/includes? words "Another verification script")))
    (is (str/includes? words "write the fix"))))

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

(defn- fired-with [used history & {:as branch}]
  (set (map :gate (arbiter/eligible
                   (update (assoc-in (ctx-at used) [:branch :gate-history] history)
                           :branch merge branch)))))

(defn- fired-and-met
  "What the loop really leaves on a branch: an entry when a gate fires, and another
  when it settles as met."
  [g n]
  (vec (mapcat (fn [t] [{:gate g :turn t} {:gate g :turn t :settled :met}]) (range n))))

(deftest a-settled-firing-counts-once
  ;; Both entries were counted, so one obeyed firing used up a limit of two and the
  ;; second whole-running check never came, on any fix2 trial.
  (let [p (gates/threshold :time-budget)]
    (is (contains? (fired-with (:whole-running-again p) (fired-and-met :time-whole-running 1))
                   :time-whole-running))
    (is (not (contains? (fired-with (:whole-running-again p) (fired-and-met :time-whole-running 2))
                        :time-whole-running)))
    (is (contains? (fired-with (:wind-down p) (fired-and-met :time-wind-down 1)) :time-wind-down))
    (is (not (contains? (fired-with (:wind-down p)
                                    (fired-and-met :time-wind-down (gates/threshold :max-time-steers)))
                        :time-wind-down)))))

(deftest a-run-that-has-written-nothing-is-told-to-start
  ;; GLM wrote its first file 14 to 23 minutes into 30, with about 90K tokens of output
  ;; to spend in the whole run, and csview never reached a compile.sh.
  (let [{:keys [first-write first-write-again]} (gates/threshold :time-budget)
        probed [{:tool "shell"} {:tool "read_file"}]
        wrote [{:tool "shell"} {:tool "write_file"}]]
    (testing "not while there is still time to look around"
      (is (not (contains? (fired-with (- first-write 0.01) [] :turns probed) :time-nothing-written))))
    (testing "then told, and told once more later"
      (is (contains? (fired-with first-write [] :turns probed) :time-nothing-written))
      (is (not (contains? (fired-with first-write (fired-and-met :time-nothing-written 1) :turns probed)
                          :time-nothing-written)))
      (is (contains? (fired-with first-write-again (fired-and-met :time-nothing-written 1) :turns probed)
                     :time-nothing-written))
      (is (not (contains? (fired-with first-write-again (fired-and-met :time-nothing-written 2) :turns probed)
                          :time-nothing-written))))
    (testing "never once something has been written"
      (is (not (contains? (fired-with first-write-again [] :turns wrote) :time-nothing-written))))
    (testing "only a write answers it"
      (let [vocab (:time-nothing-written (gates/tool-vocab :settle-called))]
        (is (contains? vocab "write_file"))
        (is (not (contains? vocab "shell")))))
    (testing "the message gives the time left and says to write"
      (let [msg (:message (arbiter/decide (assoc-in (ctx-at first-write) [:branch :turns] probed)))]
        (is (str/includes? msg "100"))
        (is (str/includes? msg "write_file"))))))

(deftest a-worker-with-no-board-is-not-told-to-split-its-task
  ;; Every team-solve worker was told three times to use a `task` tool it does not hold.
  (let [lines (inc (gates/threshold :task-line-budget))
        at (fn [role] (set (map :gate (arbiter/eligible
                                       (update (ctx-at nil) :branch merge
                                               {:role role :lines-written lines})))))]
    (is (contains? (at :implementer) :over-budget))
    (is (not (contains? (at :solver) :over-budget)))))
