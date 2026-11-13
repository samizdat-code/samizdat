;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.code-mode-test
  "The code-mode helpers (karamazov-6p51.4), installed in a real project
  image with the eval bridge and called from a branch's session: reads run in
  the image, `sh` and `spit` go through the bridge and come back as data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [jolt.process :as process]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.shell :as shell]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.security.exposure]
            [samizdat.agent.state :as state]
            [samizdat.bridge :as bridge]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.repl.image :as image]
            [samizdat.repl.route :as route]
            [samizdat.userspace :as userspace]))

(defn- helpers [] (userspace/template :prompt "code-mode-helpers"))

(deftest the-helper-source-reads-and-names-its-namespace
  (let [src (helpers)
        forms (read-string (str "[" src "]"))]
    (is (= "samizdat.code" (bridge/helper-ns src)))
    (is (= 'samizdat.code (second (first forms))))
    (is (some #(and (seq? %) (= 'defn (first %)) (= 'refer-into! (second %))) forms)))
  (testing "the install refers it into user and each session"
    (let [src (bridge/install-source #{"shell"} [] 'tool {:timeout-ms 1000 :timeout-message "slow"}
                                     (helpers) #{'b1})
          forms (read-string (str "[" src "]"))]
      (is (some #{'(samizdat.code/refer-into! (quote user))} forms))
      (is (some #{'(samizdat.code/refer-into! (quote b1))} forms)))))

(deftest a-shell-result-carries-its-data-across-the-bridge
  (is (= {:value "out" :data {:exit 0 :out "out" :err ""}}
         (bridge/answer {:category :success :result "out" :data {:exit 0 :out "out" :err ""}})))
  (is (= {:error "boom" :data {:exit 2}}
         (bridge/answer {:category :failure :result "boom" :data {:exit 2}}))))

(defn- fake-tools
  "What answers the image's bridged calls here: shell runs the command, the
  writes write — standing in for the gated tool path, which has its own tests."
  [root calls]
  (fn [{:keys [op args]}]
    (swap! calls conj op)
    (case (str op)
      "shell" (let [r (process/sh {:dir root} "bash" "-c" (:command args))
                    d {:exit (:exit r) :out (:out r) :err (:err r)}]
                (if (zero? (:exit r)) {:value (:out r) :data d} {:error (str "exit " (:exit r)) :data d}))
      "write_file" (do (spit (str root "/" (:path args)) (:content args)) {:value "wrote"})
      {:error (str "no tool " op)})))

(deftest helpers-work-from-a-branch-session-in-a-real-image
  (let [root (str (fs/create-temp-dir))
        _ (spit (str root "/deps.edn") (pr-str {:paths ["src"]}))
        _ (fs/create-dirs (str root "/src/a"))
        _ (spit (str root "/src/a/b.clj") "(ns a.b)\n;; TODO one\n(defn f [] 1)\n")
        im (image/start! {:root root :backend :none
                          :sandbox-spec {:deny-read [] :exec-roots (route/runtime-exec-roots)}})
        calls (atom [])]
    (try
      (is (some? im))
      (when im
        (is (:ok (image/install-bridge! im ["shell" "write_file"] []
                                        {:timeout-ms 20000 :timeout-message "slow"}
                                        (helpers))))
        (let [ev (fn [code]
                   (let [id (str (random-uuid))]
                     (image/eval-in im (bridge/wrap id code) 'branch-1
                                    {:on-request (fake-tools root calls) :bridge-eval id
                                     :poll-ms 20 :call-timeout-ms 20000})))]
          (testing "reads run in the image"
            (is (= "[\"src/a/b.clj\"]" (:value (ev "(glob \"src/**/*.clj\")"))))
            (is (str/includes? (:value (ev "(grep \"TODO\")")) "src/a/b.clj:2:;; TODO one"))
            (is (= "\"(ns a.b)\"" (:value (ev "(head \"src/a/b.clj\" 1)")))))
          (testing "sh returns stdout, throws with the exit and streams"
            (is (= "\"hi there\\n\"" (:value (ev "(sh \"echo\" \"hi there\")"))))
            (is (= "1" (:value (ev "(try (sh \"false\") (catch Exception e (:exit (ex-data e))))"))))
            (is (= "{:exit 3, :out \"\", :err \"\"}" (:value (ev "(sh* \"sh\" \"-c\" \"exit 3\")")))))
          (testing "spit writes through the bridge; cat is still the transducer"
            (ev "(spit \"out.txt\" \"abc\")")
            (is (= "abc" (slurp (str root "/out.txt"))))
            (is (= "[1 2 3]" (:value (ev "(into [] cat [[1 2] [3]])")))))
          (is (= #{"shell" "write_file"} (set @calls)) "programs and writes took the bridge")
          (testing "a background process runs, reports, and goes with the image"
            (let [pid (:value (ev "(:pid (process/start \"echo up; sleep 120\"))"))]
              (is (re-matches #"\d+" (str pid)))
              (Thread/sleep 500)
              (is (= "\"up\"" (:value (ev (str "(process/output " pid ")")))))
              (is (= "true" (:value (ev "(:running? (first (process/list)))"))))
              (image/stop! im)
              (Thread/sleep 500)
              (is (not= 0 (:exit (process/sh "kill" "-0" (str pid))))
                  "stopping the image stopped its background process")))))
      (finally (when im (image/stop! im))))))

(deftest a-shell-call-that-a-helper-replaces-is-hinted
  (let [table (gates/threshold :helper-hints)]
    (is (= [{:program "cat" :helper "`(cat f)`"}] (shell/helper-hints table "cat src/a.clj")))
    (is (= ["cat" "grep"] (map :program (shell/helper-hints table "cat a.txt | grep -n foo"))))
    (is (= [] (shell/helper-hints table "make test")) "a program no helper replaces")
    (is (= ["ls" "wc"] (policy/command-heads "ls -la && wc -l x"))))
  (let [line (prompt/render "helper-hint" {:hints [{:program "cat" :helper "`(cat f)`"}
                                                   {:program "grep" :helper "`(grep #\"re\" path)`"}]})]
    (is (str/includes? line "`cat` is `(cat f)`, `grep` is"))
    (is (str/starts-with? line "[harness] hint:"))))

(deftest a-clipped-result-is-kept-whole-in-a-file
  (let [root (str (fs/create-temp-dir))
        big (apply str (repeat 200000 "x"))
        spill (#'aloop/spill-to {:root root} 7 "shell")
        out (#'aloop/truncate big 7 nil spill)]
    (is (< (count out) (count big)))
    (is (str/includes? out "`.samizdat/results/turn-7-shell.txt`"))
    (is (= big (slurp (str root "/.samizdat/results/turn-7-shell.txt"))))
    (is (not (str/includes? (#'aloop/truncate big 7 nil nil) "results/turn-")) "no file without a root")
    (is (= "short" (#'aloop/truncate "short" 7 nil spill)) "and nothing written for a result that fits")))

(deftest a-long-eval-is-handed-back-and-goes-on
  (let [stalling #'route/stalling
        k ["/tmp/stall-root" (gensym "s")]
        slow (fn [ms v] (fn [_] (Thread/sleep ms) {:ok true :value v}))]
    (testing "a quick eval answers as before"
      (is (= {:ok true :value "1"} (stalling {} k "(+ 1)" 200 100 (slow 0 "1")))))
    (testing "a slow one comes back, the session is busy, and wait gets its result"
      (let [r (stalling {} k "(build)" 50 100 (slow 400 "built"))]
        (is (= "(build)" (get-in r [:stalled :code])))
        (is (= "(build)" (get-in (stalling {} k "(other)" 50 100 (slow 0 "x")) [:busy :code]))
            "a new eval is not run while it runs")
        (let [w (stalling {:wait? true} k "" 2000 100 nil)]
          (is (= "built" (:value w)))
          (is (= "(build)" (get-in w [:waited :code]))))
        (is (:idle (stalling {:wait? true} k "" 50 100 nil)) "nothing left running")))
    (testing "abort interrupts it"
      (let [stop (promise)
            run (fn [interrupt]
                  (reset! interrupt #(deliver stop :stopped))
                  {:ok false :error (str "interrupted: " (deref stop 5000 :never))})]
        (is (:stalled (stalling {} k "(loop [])" 50 100 run)))
        (let [a (stalling {:abort? true} k "" 50 1000 nil)]
          (is (= "(loop [])" (get-in a [:aborted :code])))
          (is (= "interrupted: :stopped" (get-in a [:finished :error]))))))
    (testing "one that ends on its own hands its result to the next call"
      (is (:stalled (stalling {} k "(slow)" 30 100 (slow 120 "late"))))
      (Thread/sleep 300)
      (let [r (stalling {} k "(next)" 200 100 (slow 0 "now"))]
        (is (= "now" (:value r)))
        (is (= "late" (get-in r [:earlier :value])))
        (is (= "(slow)" (get-in r [:earlier :unasked :code])))))))

(deftest the-detached-states-read-as-one-line
  (doseq [m [{:stalled {:code "(build)" :seconds 60} :seconds 60}
             {:busy {:code "(build)" :seconds 75}}
             {:idle true}
             {:aborted {:code "(build)" :seconds 80} :stuck true}
             {:unasked {:code "(build)" :seconds 70}}
             {:waited {:code "(build)" :seconds 90}}]]
    (let [line (str/trim (prompt/render "eval-detached" m))]
      (is (seq line) (pr-str m))
      (is (not (str/includes? line "\n")) line))))

(deftest the-eval-tool-says-what-became-of-a-handed-back-eval
  (let [branch (state/new-branch {:id "B1" :problem "p"})
        run (fn [args answer]
              (with-redefs [route/eval-for (fn [ctx & _] (answer ctx))]
                (:result (tools/run-tool {:branch branch :tool-name "eval" :args args}))))]
    (is (str/includes? (run {:code "(build)"} (fn [_] {:ok true :stalled {:code "(build)" :seconds 60} :seconds 60}))
                       "Still running after 60s"))
    (is (str/includes? (run {:wait true} (fn [ctx] (if (:wait? ctx)
                                                     {:ok true :value "42" :waited {:code "(build)" :seconds 90}}
                                                     {:ok true :value "wrong"})))
                       "finished after 90s:\n=> 42")
        "wait takes no code")
    (is (str/includes? (run {:code "(+ 1 1)"} (fn [_] {:ok true :value "2"
                                                       :earlier {:ok true :value "built" :unasked {:code "(build)"}}}))
                       "(`(build)`) finished:\n=> built\n\n=> 2"))))

(deftest a-check-that-leaves-output-or-wrote-then-ran-is-still-a-check
  ;; tb21-code torch-pipeline-parallelism: done refused after checks that ran.
  (let [w {:tool "write_file" :category :success}
        ok? #(not (ship/unchecked-write? {:turns %}))]
    (is (not (ok? [w])) "a write with nothing after it")
    (is (ok? [w {:tool "eval" :category :success}]) "a run after it")
    (is (not (ok? [w {:tool "eval" :category :success :wrote true}])) "an eval that only wrote")
    (is (ok? [w {:tool "eval" :category :success :wrote true :ran? true}]) "one that wrote and ran")
    ;; tb21-code protein-assembly / code-from-image: a successful eval is :neutral
    ;; (base/ok), so an eval that ran the program never counted and done was
    ;; refused 15 times.
    (is (ok? [w {:tool "eval" :category :neutral :ran? true}]) "an eval that ran a program, as recorded")
    (is (ok? [w {:tool "eval" :category :neutral :wrote true :ran? true}]) "one that also wrote, as recorded")
    (is (not (ok? [w {:tool "eval" :category :neutral}])) "an eval that ran nothing is not a check")
    (is (not (ok? [w {:tool "eval" :category :failure :ran? true}])) "nor one that failed")
    (is (not (ok? [w {:tool "shell" :category :neutral}])) "a shell call held for approval ran nothing"))
  (let [ignore (:ignore (gates/threshold :tree-writes))
        ignored? (fn [p] (some #(samizdat.security.exposure/glob-matches? % p) ignore))]
    (is (ignored? "__pycache__/pipeline_parallel.cpython-312.pyc"))
    (is (ignored? "src/pkg/__pycache__/x.pyc"))
    (is (not (ignored? "pipeline_parallel.py")))))

;; karamazov-oi16.11. 20 of 23 failing TB trials ended on an accepted `done`,
;; several with half their time left (protein-assembly at 38%), and the
;; supervisor never looked: nothing about a confident wrong ship trips its
;; counters. A done with time left now waits for its review.
(deftest a-done-with-time-left-waits-for-the-supervisors-review
  (let [ev {:answer "Wrote /app/x and ran it." :problem "p" :time-used 0.3 :wind-down 0.8
            :keep-working "keep going" :review-hold "under review" :supervised? true}
        rung (fn [e] (:name (ship/ship-gate-rung e)))]
    (is (= :supervisor-review (rung ev)) "the first done asks for the review")
    (is (= "under review" (ship/ship-gate-block ev)))
    (is (= :supervisor-review (rung (assoc ev :refused {:supervisor-review 1} :review-pending? true)))
        "and is held while the review is pending")
    (is (= :time-left (rung (assoc ev :refused {:supervisor-review 1})))
        "released once it lands; the time-left rung has its turn after it")
    (is (= :time-left (rung (dissoc ev :supervised?))) "no supervisor, no hold")
    (is (not= :supervisor-review (rung (assoc ev :time-used 0.9))) "no hold in the wind-down")
    (is (not= :supervisor-review (rung (assoc ev :verdict? true))) "a verdict is never held")
    (is (= :ship-review-requested
           (:note-once (some #(when (= :supervisor-review (:name %)) %) (gates/threshold :ship-gates))))
        "the rung names the note that asks for the review")))

(deftest a-once-rung-sends-a-branch-back-once-not-once-per-streak
  ;; tb21-retry mteb-retrieve: time-left refused 8 dones with work between them,
  ;; each saying "Call `done` again to finish anyway".
  (let [ev {:answer "Wrote /app/result.txt and ran it." :problem "p"
            :time-used 0.2 :wind-down 0.8 :keep-working "keep going"
            :check-before-done "check it" :review-unusual "review it"}]
    (is (= "keep going" (ship/ship-gate-block ev)) "the first done is sent back")
    (is (nil? (ship/ship-gate-block (assoc ev :refused {:time-left 1})))
        "a later one is not, though work came between")
    (is (= "check it" (ship/ship-gate-block (assoc ev :refused {:time-left 1} :unchecked-write? true)))
        "another rung still gets its one turn")
    (is (nil? (ship/ship-gate-block (assoc ev :refused {:time-left 1 :check-after-last-write 1}
                                           :unchecked-write? true))))
    (is (nil? (ship/ship-gate-block (assoc ev :refused {:time-left 1 :unusual-change 1}
                                           :unusual-change true))))
    (is (= :time-left (:name (ship/ship-gate-rung ev))) "and which rung refused is named")))

(defn- helper-fns
  "The pure output helpers, read out of the helper source and evaluated in a
  scratch namespace: the rest of the file needs the image's bridge."
  [names]
  (let [forms (read-string (str "[" (helpers) "]"))
        wanted (filter #(and (seq? %) (contains? names (second %))) forms)
        n (create-ns (gensym "helpers-under-test"))]
    (binding [*ns* n]
      (refer-clojure)
      (alias 'str 'clojure.string)
      (doseq [f wanted] (eval f)))
    (fn [sym] @(ns-resolve n sym))))

(deftest test-summary-and-first-error-read-a-noisy-run
  (let [h (helper-fns '#{text-of error-line summary-line first-error test-summary})
        summary (h 'test-summary)
        first-error (h 'first-error)
        run (str/join "\n" (concat ["collecting ..."]
                                   (map #(str "test_ok_" % " PASSED") (range 200))
                                   ["test_edge FAILED" "E  assert 1 == 2" "E  +  where 1 = f()"]
                                   ["=== 1 failed, 200 passed in 2.1s ==="]))]
    (is (= ["test_edge FAILED" "E  assert 1 == 2" "E  +  where 1 = f()" "=== 1 failed, 200 passed in 2.1s ==="]
           (str/split-lines (summary run)))
        "failures and the count line, in order; no passing lines")
    (is (= "test_edge FAILED\nE  assert 1 == 2" (first-error run 1)))
    (is (= "error: x\nnext" (first-error {:out "ok\nerror: x\nnext\nmore" :err ""} 1)) "sh*'s result map")
    (is (nil? (first-error "all fine")))))
