;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl-guard-test
  "Eval may not kill the server.

  Run a3ba69bb: the serve process exited 0 mid-run and nothing in abort, beam
  teardown or process disposal calls exit. Reproduced directly — an eval of
  `(System/exit 0)` ends the process, and the line after it never runs
  (karamazov-1xx)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.approval]
            [samizdat.repl :as repl]
            [samizdat.repl.guard :as guard]
            [samizdat.repl.route]))

(defn- forms [s] (read-string (str "[" s "\n]")))

(deftest the-routes-out-of-the-process-are-refused
  (testing "the observed one"
    (is (guard/terminating-form? (forms "(System/exit 0)"))))
  (testing "and both interop routes to the same thing, which read as ordinary
            method calls — only the receiver gives them away"
    (is (guard/terminating-form? (forms "(.exit (Runtime/getRuntime) 1)")))
    (is (guard/terminating-form? (forms "(.halt (Runtime/getRuntime) 0)"))))
  (testing "nesting does not hide it: the walk is over the read form as data"
    (is (guard/terminating-form? (forms "(do (println :a) (when true (System/exit 2)))")))
    (is (guard/terminating-form? (forms "(let [n 0] (System/exit n))"))))
  (testing "a later form is refused even though an earlier one is fine — the
            forms share a process, so form 1 having run does not make form 2's
            exit survivable"
    (is (guard/terminating-form? (forms "(+ 1 2)\n(System/exit 0)")))))

(deftest defining-a-thing-that-exits-is-not-exiting
  ;; NOT ACADEMIC. Every Clojure test runner ends (System/exit 0); this
  ;; project's deps.edn requires the agent to write one; and the eval tool's
  ;; whole pitch is to iterate on a form before writing it to a file. A guard
  ;; that refuses the work it exists to protect gets worked around, and then
  ;; protects nothing.
  (is (not (guard/terminating-form? (forms "(defn -main [& args] (System/exit 0))"))))
  (is (not (guard/terminating-form? (forms "(defn -main [] (when bad (System/exit 1)) (System/exit 0))"))))
  (is (not (guard/terminating-form? (forms "(fn [] (System/exit 0))"))))
  (testing "but the same call outside a definition body still runs, and is
            still refused"
    (is (guard/terminating-form? (forms "(do (println :a) (System/exit 2))")))))

(deftest calling-a-main-is-refused-and-writing-one-is-not
  ;; THE ROUTE THE SYMBOL CHECK CANNOT SEE. The exit is inside the callee, one
  ;; file away, in code the agent wrote itself minutes earlier — and the
  ;; harness's own eval guidance invites requiring and exercising the project's
  ;; namespaces, which walks it straight in.
  (is (guard/entry-point-call? (forms "(flight.test-runner/-main)")))
  (is (guard/entry-point-call? (forms "(do (require 'flight.main) (flight.main/-main))")))
  (is (= ["flight.main/-main"] (guard/main-calls (forms "(flight.main/-main \"a\")")))
      "named as written, so the refusal points at the one it reached for")
  (testing "HEAD POSITION ONLY — that is the difference between calling one and
            writing one, and writing one is required of this project"
    (is (not (guard/entry-point-call? (forms "(defn -main [] 1)"))))
    (is (not (guard/entry-point-call? (forms "(defn -main [& args] (System/exit 0))")))))
  (testing "and the alternative it names actually works"
    (is (not (guard/entry-point-call?
              (forms "(clojure.test/run-tests 'flight.mechanics-test)"))))))

(deftest the-main-refusal-names-both-things-that-do-work
  (let [r (repl/eval-code "(flight.test-runner/-main)")]
    (is (false? (:ok r)))
    (is (re-find #"flight\.test-runner/-main" (str (:error r))))
    (is (re-find #"jolt -M:test" (str (:error r)))
        "the child-process route, where an exit code is the point")
    (is (re-find #"run-tests" (str (:error r)))
        "and the in-eval route, skipping the runner that wraps them")))

(deftest ordinary-evals-are-untouched
  ;; The guard costs the agent nothing on the work it actually does. System is
  ;; a common namespace and only the exit member of it is a terminator.
  (is (not (guard/terminating-form? (forms "(+ 1 2)"))))
  (is (not (guard/terminating-form? (forms "(println (System/currentTimeMillis))"))))
  (is (not (guard/terminating-form? (forms "(System/getenv \"HOME\")"))))
  (is (not (guard/terminating-form? (forms "(require '[clojure.string :as str])")))))

(deftest the-refusal-names-the-call-and-the-alternative
  (let [r (repl/eval-code "(System/exit 0)")]
    (is (false? (:ok r)) "refused, not run")
    (is (re-find #"System/exit" (str (:error r)))
        "names the call, so the model knows which of its forms was the problem")
    (is (re-find #"(?i)abort" (str (:error r)))
        "and names what to do instead — a refusal with no alternative is one the
         model works around")))

(deftest the-process-survives-the-eval-that-used-to-end-it
  ;; The whole point. If this regresses, the test run itself dies here rather
  ;; than reporting a failure — which is exactly what the bug looks like.
  (repl/eval-code "(System/exit 0)")
  (is true "still running after evaluating an exit"))

(deftest an-exit-with-work-in-flight-reads-differently-from-a-clean-one
  ;; The forensic layer. It cannot prevent anything and does not try; what it
  ;; does is make the 0 stop looking like somebody stopping the server.
  (is (:bug? (guard/exit-note ["run-abc" "run-def"]))
      "work in flight at exit is the bug, and the note says so as data")
  (is (= ["run-abc"] (:active (guard/exit-note ["run-abc"])))
      "names the runs, because that is what makes the record actionable")
  (is (= 2 (:count (guard/exit-note ["run-abc" "run-def"]))))
  (is (not (:bug? (guard/exit-note [])))
      "an idle exit is somebody stopping the server, not this bug")
  (testing "the WORDS live at the log call, not here — a note assembled one
            function away from its logging is prose the ratchet cannot tell
            from a sentence aimed at the model"
    (is (not (string? (guard/exit-note ["run-abc"]))))))

(deftest every-refusal-is-a-named-rule
  ;; The guard is rules over facts read off the form (karamazov-41a.4), so a
  ;; refusal can say WHICH rule fired and on what — the thing a model needs
  ;; in order to comply rather than guess — and the reach is enumerable.
  (is (= [:process-exit] (map :rule (guard/findings (forms "(System/exit 0)")))))
  (is (= [:entry-point-call]
         (map :rule (guard/findings (forms "(flight.runner/-main)")))))
  (is (= [] (guard/findings (forms "(+ 1 2)"))))
  (testing "one form, two rules, both named"
    (is (= #{:process-exit :entry-point-call}
           (set (map :rule (guard/findings (forms "(do (System/exit 0) (-main))"))))))))

(deftest the-rules-are-enumerable-with-what-each-catches
  (let [rules (guard/rules)]
    (is (= #{:process-exit :kernel-source-write :harness-reload :entry-point-call}
           (set (map :name rules))))
    (is (every? (comp string? :doc) rules) "each says what it catches")))

(deftest the-exit-refusal-names-its-rule
  (let [r (repl/eval-code "(System/exit 0)")]
    (is (re-find #"process-exit" (str (:error r))))))

;; --- a harness eval's effects, read before it runs (karamazov-0e2c.21) --------------

(deftest the-effects-of-a-harness-eval-are-read-before-it-runs
  ;; After xi's scan-code: the literal processes, writes and requests a form
  ;; would make, so a person is asked once, with the list, before it runs.
  (is (= [{:kind :process :call "clojure.java.shell/sh" :target "rm"}]
         (guard/effects '(clojure.java.shell/sh "rm" "-rf" "build"))))
  (is (= [{:kind :write :call "spit" :target "resources/prompts/system.md"}]
         (guard/effects '(spit "resources/prompts/system.md" "x"))))
  (is (= :network (:kind (first (guard/effects '(jolt.http-client/get "https://x.example"))))))
  (is (empty? (guard/effects '(defn later [] (spit "x" "y"))))
      "defining a thing that writes is not writing")
  (is (empty? (guard/effects '(->> (samizdat.store.journal/turns conn "R") (take 3))))
      "reading the run is the supervisor's job"))

(deftest the-relative-paths-a-form-hands-to-files-are-read
  ;; karamazov-jk2y: the paths that resolve against the harness's cwd.
  (is (= ["notes.txt" "README.md" "out/x.edn"]
         (guard/relative-paths '(do (spit "notes.txt" "x")
                                    (slurp "README.md")
                                    (clojure.java.io/copy src (io/file "out/x.edn"))))))
  (is (= ["build"] (guard/relative-paths '(fs/delete-tree "build"))))
  (is (empty? (guard/relative-paths '(spit "/tmp/abs.txt" "notes.txt")))
      "an absolute path is fine, and the content is not a path")
  (is (empty? (guard/relative-paths '(slurp "https://x.example/a"))))
  (is (empty? (guard/relative-paths '(io/file root "notes.txt")))
      "a path under a root the code names")
  (is (empty? (guard/relative-paths '(io/resource "prompts/system.md")))
      "the classpath is not the cwd")
  (is (empty? (guard/relative-paths '(defn later [] (slurp "x"))))
      "defining a thing that reads is not reading"))

(deftest a-relative-path-from-the-harness-is-refused-when-the-project-is-elsewhere
  ;; karamazov-jk2y, run 582980ef: the supervisor wrote manual backups with
  ;; relative paths from eval, and they landed in the samizdat checkout. The
  ;; harness cannot chdir per branch, so the path is refused with the root it
  ;; should have been under — before any approval, yolo included, since
  ;; allowing it puts the file somewhere nobody asked for.
  (let [ran (atom 0)
        here (System/getProperty "user.dir")
        elsewhere {:root "/tmp/elsewhere-project" :role :supervisor :run-id "R"}]
    (with-redefs [samizdat.repl/eval-code (fn [& _] (swap! ran inc) {:ok true :value "nil"})
                  samizdat.approval/policy (constantly {:mode :yolo})]
      (testing "refused before it runs, naming the path and the root"
        (let [r (samizdat.repl.route/eval-for elsewhere "(spit \"manual-backup.edn\" \"x\")" nil 1000)]
          (is (not (:ok r)))
          (is (= "relative-path" (:error-type r)))
          (is (str/includes? (str (:error r)) "manual-backup.edn"))
          (is (str/includes? (str (:error r)) "elsewhere-project"))
          (is (zero? @ran))))
      (testing "a read too: it answers from the wrong tree, plausibly"
        (is (= "relative-path" (:error-type (samizdat.repl.route/eval-for
                                             elsewhere "(slurp \"README.md\")" nil 1000)))))
      (testing "an absolute path runs"
        (is (:ok (samizdat.repl.route/eval-for
                  elsewhere "(spit \"/tmp/elsewhere-project/m.edn\" \"x\")" nil 1000))))
      (testing "with the harness started from the project root, a relative path is the project's"
        (is (:ok (samizdat.repl.route/eval-for (assoc elsewhere :root here)
                                               "(slurp \"README.md\")" nil 1000)))))))

(deftest a-harness-eval-with-effects-is-a-persons-call
  (let [ran (atom 0)
        ;; The harness's own directory, so the relative path below is the
        ;; project's and only the effects decide.
        ctx {:root (System/getProperty "user.dir") :role :supervisor :run-id "R"}]
    (with-redefs [samizdat.repl/eval-code (fn [& _] (swap! ran inc) {:ok true :value "nil"})]
      (testing "under refuse it does not run, and says what to use"
        (with-redefs [samizdat.approval/policy (constantly {:mode :refuse})]
          (let [r (samizdat.repl.route/eval-for ctx "(spit \"notes.txt\" \"x\")" nil 1000)]
            (is (not (:ok r)))
            (is (re-find #"(?i)write_file|shell" (str (:error r))))
            (is (zero? @ran)))))
      (testing "a form with no effects runs as before"
        (with-redefs [samizdat.approval/policy (constantly {:mode :refuse})]
          (is (:ok (samizdat.repl.route/eval-for ctx "(+ 1 2)" nil 1000)))))
      (testing "under block a person sees the effects, once"
        (let [asked (atom nil)]
          (with-redefs [samizdat.approval/policy (constantly {:mode :block :wait-ms 1 :on-timeout :deny})
                        samizdat.approval/request! (fn [q] (reset! asked q) "id")
                        samizdat.approval/await! (fn [_ _ _] {:decision :allow})]
            (is (:ok (samizdat.repl.route/eval-for ctx "(spit \"notes.txt\" \"x\")" nil 1000)))
            (is (str/includes? (str (:input @asked)) "notes.txt"))))))))
