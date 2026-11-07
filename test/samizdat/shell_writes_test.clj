;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.shell-writes-test
  "A file written through the shell is a file written. Both models write most files with
  heredocs, sed and python, and counting only the file tools fired no-edits 20 times in one
  run and refused done three times over a compile.sh that existed."
  (:require [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.state :as state]
            [samizdat.agent.supervisor :as sup]
            [samizdat.engine.proc :as proc]))

(defn- repo []
  (let [d (str "/tmp/samizdat-shell-writes-" (System/nanoTime))]
    (fs/create-dirs d)
    (spit (str d "/README.md") "x\n")
    (proc/run {:dir d} "git" "init" "-q")
    (proc/run {:dir d} "git" "add" "-A")
    (proc/run {:dir d} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "base")
    d))

(def ^:private note @#'aloop/note-tree-writes)

(defn- planned [files]
  (-> (state/new-branch {:id "B1" :problem "p"})
      (assoc :repl-plan {:files files})
      (state/add-turn {:turn 1 :tool "shell"})))

(deftest a-shell-write-discharges-the-plan-and-counts-as-a-write
  (let [root (repo)
        ctx {:root root :git-baseline (gitdiff/baseline root)}]
    (try
      (let [b (-> (planned ["compile.sh"]) (note ctx "shell" false))]
        (is (= ["compile.sh"] (state/unwritten b)) "nothing written yet")
        (spit (str root "/compile.sh") "jolt build\n")
        (let [b (-> b (state/add-turn {:turn 2 :tool "shell"}) (note ctx "shell" false))]
          (is (empty? (state/unwritten b)))
          (is (:wrote (peek (:turns b))) "the turn is marked as a write")
          (testing "and an unchanged tree on the next turn is not"
            (let [b (-> b (state/add-turn {:turn 3 :tool "shell"}) (note ctx "shell" false))]
              (is (not (:wrote (peek (:turns b)))))))
          (testing "a file the file tools wrote is not credited to the next shell turn"
            (spit (str root "/a.clj") "(ns a)\n")
            (let [b (-> b (state/add-turn {:turn 4 :tool "write_file"}) (note ctx "write_file" false)
                        (state/add-turn {:turn 5 :tool "shell"}) (note ctx "shell" false))]
              (is (not (:wrote (peek (:turns b)))))))
          (testing "a refused call wrote nothing"
            (spit (str root "/b.clj") "(ns b)\n")
            (let [b (-> b (state/add-turn {:turn 6 :tool "shell"}) (note ctx "shell" true))]
              (is (not (:wrote (peek (:turns b)))))))))
      (finally (fs/delete-tree root)))))

(deftest the-gates-see-a-shell-write
  (let [tools (gates/tool-vocab :file-write)
        turns [{:tool "shell" :wrote true} {:tool "shell"} {:tool "read_file"}]]
    (is (sup/shipped? tools {:tool "shell" :wrote true}))
    (is (not (sup/shipped? tools {:tool "shell"})))
    (is (not (state/plan-stale? (assoc (planned ["x.clj"]) :turns (vec (concat (repeat 3 {:tool "read_file"}) [{:tool "shell" :wrote true}])))
                                tools 4)))
    (testing "nothing-written does not fire after a shell write"
      (let [ctx {:branch (merge (state/new-branch {:id "B1" :problem "p"})
                                {:role :implementer :turns turns})
                 :max-turns 300 :time-used (:first-write (gates/threshold :time-budget)) :time-left-seconds 100}]
        (is (not (contains? (set (map :gate (arbiter/eligible ctx))) :time-nothing-written)))))))
