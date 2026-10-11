;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl-project-test
  "REPL semantics in the PROJECT image, through `route` — the path every role
  but the supervisor takes (karamazov-xn0h).

  The harness image's seam (samizdat.repl) had grown each of these
  properties and the project image had not: a forked branch kept its
  parent's defs, `doc` found a session's own defs, an exit was refused, a
  failure named its form. Each test here is one of those, against a real
  image. One image for the namespace, because starting one is the cost."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [jolt.fs :as fs]
            [samizdat.repl :as repl]
            [samizdat.callable]
            [samizdat.repl.route :as route]))

(def ^:private root
  (delay (let [r (str (fs/create-temp-dir))]
           (fs/create-dirs (str r "/src/demo"))
           (spit (str r "/deps.edn") (pr-str {:paths ["src"]}))
           (spit (str r "/src/demo/core.clj")
                 "(ns demo.core \"The demo.\")\n(defn greet \"Says hi.\" [who] (str \"hi \" who))\n")
           r)))

(use-fixtures :once (fn [f] (try (f) (finally (route/release-all!)))))

(defn- ctx [session] {:root @root :role :implementor :repl-session session})

(defn- ev [session code] (route/eval-for (ctx session) code session 15000))

(deftest a-failing-form-says-which-where-and-what-already-ran
  (let [s (repl/new-session)
        r (ev s "(def before-fail 1)\n(/ 1 0)\n(def after-fail 2)")]
    (is (false? (:ok r)))
    (is (= {:index 2 :count 3 :line 2 :completed 1} (:form r)))
    (is (= (str s) (:where r)) "the session it ran in, as the harness path says")
    (is (= "1" (:value (ev s "before-fail"))))
    (is (false? (:ok (ev s "after-fail"))))))

(deftest each-form-is-read-after-the-one-before-it-ran
  (let [s (repl/new-session)
        r (ev s "(require '[clojure.string :as sx]) ::sx/k")]
    (is (:ok r) (:error r))
    (is (= ":clojure.string/k" (:value r)))))

(deftest a-typo-is-an-error-not-an-unbound-value
  ;; jolt's nREPL lets an unresolved symbol through as an Unbound var; the
  ;; model read `#object[...Unbound...]` as a value.
  (let [s (repl/new-session)
        r (ev s "(+ 1 never-defined-q)")]
    (is (false? (:ok r)))
    (is (str/includes? (:error r) "Unable to resolve symbol"))
    (is (false? (:ok (ev s "never-defined-k"))))))

(deftest an-ns-form-moves-the-rest-of-the-batch
  ;; Sent whole, `(ns x) (def z 1)` defined z in x; one form per exchange must
  ;; keep that, or the def lands in the session and the ns is left empty.
  (let [s (repl/new-session)]
    (is (:ok (ev s "(ns demo.scratch) (def z 1)")))
    (is (= "1" (:value (ev s "demo.scratch/z"))))
    (is (:ok (ev s "(in-ns 'demo.other) (def w 2)")))
    (is (= "2" (:value (ev s "demo.other/w"))))))

(deftest an-exit-is-refused-and-the-session-survives
  ;; Not the harness here, but every def of the run lives in this image: an
  ;; exit costs them all and a restart, for nothing the model wanted.
  (let [s (repl/new-session)]
    (ev s "(def kept 7)")
    (let [r (ev s "(System/exit 0)")]
      (is (false? (:ok r)))
      (is (:policy-refusal? r))
      (is (str/includes? (:error r) "System/exit")))
    (let [r (ev s "(demo.core/-main)")]
      (is (false? (:ok r)))
      (is (:policy-refusal? r)))
    (is (= "7" (:value (ev s "kept"))) "nothing ran, nothing was lost")))

(deftest a-fork-carries-the-parents-defs-into-the-image
  ;; The beam forks a branch's session; under :project the defs live in the
  ;; image, so copying the harness namespace copied nothing.
  (let [p (repl/new-session)]
    (ev p "(def seed 41) (defmacro twice [x] `(do ~x ~x))")
    (let [c (route/fork-session @root p)]
      (is (= "42" (:value (ev c "(inc seed)"))))
      (is (= "2" (:value (ev c "(let [n (atom 0)] (twice (swap! n inc)) @n)"))))
      (testing "copied, not shared"
        (ev c "(def only-child 1)")
        (is (false? (:ok (ev p "only-child"))))))))

(deftest doc-and-complete-see-the-sessions-own-defs
  (let [s (repl/new-session)]
    (ev s "(defn helper \"Doubles.\" [n] (* 2 n))")
    (let [d (route/doc-for (ctx s) "helper")]
      (is (= "Doubles." (:doc d)))
      (is (= '([n]) (:arglists d))))
    (is (some #{"helper"} (route/complete-for (ctx s) "help")))))

(deftest the-session-lists-its-own-defs-without-touching-their-values
  (let [s (repl/new-session)]
    (ev s "(defn helper \"Doubles.\" [n] (* 2 n))
           (defmacro twice [x] `(do ~x ~x))
           (def hits (atom 0))
           (def lazy-one (map (fn [x] (swap! hits inc) x) (range 3)))")
    (let [{:keys [defs total]} (route/session-defs (ctx s) 60 100)
          by (into {} (map (juxt :name identity)) defs)]
      (is (= 4 total))
      (is (= "fn" (:kind (by "helper"))))
      (is (= ["[n]"] (:arglists (by "helper"))))
      (is (= "Doubles." (:doc (by "helper"))))
      (is (:macro (by "twice")))
      (is (= "value" (:kind (by "lazy-one"))))
      (is (= "0" (:value (ev s "@hits"))) "listing realised nothing"))
    (testing "a role whose image is not running gets nothing, and starts none"
      (is (nil? (route/session-defs {:root (str (fs/create-temp-dir)) :role :implementor
                                     :repl-session s}
                                    60 100))))))

;; --- the eval bridge (karamazov-xn0h.7) --------------------------------------

(defn- bridged
  ([session handler code] (bridged session handler code []))
  ([session handler code cells]
   (with-redefs [route/bridge-policy (constantly {:enabled? true :tools #{"read_file" "grep"}
                                                  :cells? true :call-timeout-ms 5000})
                 samizdat.callable/cells (constantly (mapv (fn [id] {:id id}) cells))]
     (route/eval-for (assoc (ctx session) :bridge handler) code session 15000))))

(deftest a-tool-called-from-eval-comes-back-as-a-value
  (let [s (repl/new-session)
        seen (atom [])
        r (bridged s (fn [frame] (swap! seen conj (select-keys frame [:op :args]))
                       {:value (str "read " (get-in frame [:args :path]))})
                   "(println \"before\") (let [t (tool/read_file {:path \"a.clj\"})] (str t \"!\"))")]
    (is (:ok r) (:error r))
    (is (= "\"read a.clj!\"" (:value r)))
    (is (= [{:op "read_file" :args {:path "a.clj"}}] @seen))
    (is (= "before\n" (:out r)) "the request is not output")))

(deftest a-refused-call-fails-the-form-that-made-it
  (let [s (repl/new-session)
        r (bridged s (fn [_] {:error "not in this phase"}) "(tool/grep {:pattern \"x\"})")]
    (is (false? (:ok r)))
    (is (str/includes? (:error r) "not in this phase"))))

(deftest with-the-bridge-off-there-is-no-tool-namespace
  (let [s (repl/new-session)
        r (with-redefs [route/bridge-policy (constantly {:enabled? false})]
            (ev s "(tool/grep {:pattern \"x\"})"))]
    (is (false? (:ok r)))))

(deftest concurrent-branches-each-answer-their-own-calls
  ;; Two branches share an image; each eval's poller must take only the
  ;; requests its own eval queued, or a call runs with the wrong branch.
  (let [a (repl/new-session) b (repl/new-session)
        code "(do (Thread/sleep 200) (tool/grep {:pattern \"x\"}))"
        fa (future (bridged a (fn [_] (Thread/sleep 100) {:value "from-a"}) code))
        fb (future (bridged b (fn [_] {:value "from-b"}) code))]
    (is (= "\"from-a\"" (:value @fa)) (pr-str @fa))
    (is (= "\"from-b\"" (:value @fb)) (pr-str @fb))))

(deftest a-poll-the-image-cannot-answer-does-not-outlive-the-eval
  ;; karamazov-xn0h.9: an eval wedged in a foreign call left the image unable
  ;; to answer the bridge's poll; the poll had no deadline and stopping the
  ;; poller waited on it, so the eval's own timeout never returned and the
  ;; branch hung for good. A poll that never answers stands in for that image.
  (let [s (repl/new-session)
        t0 (System/currentTimeMillis)
        r (with-redefs [samizdat.bridge/take-source (constantly "(Thread/sleep 600000)")
                        route/bridge-policy (constantly {:enabled? true :tools #{"grep"}
                                                         :cells? false :call-timeout-ms 5000
                                                         :poll-ms 50})]
            (deref (future (route/eval-for (assoc (ctx s) :bridge (fn [_] {:value "x"}))
                                           "(loop [] (recur))" s 3000))
                   60000 ::hung))]
    (is (not= ::hung r) "the eval never came back")
    (is (:timeout? r) (pr-str r))
    (is (< (- (System/currentTimeMillis) t0) 30000))))

(deftest end-to-end-a-real-read-through-the-bridge
  ;; The eval tool, the project image and the tool path, none stubbed: the
  ;; read happens through read_file and its text comes back into the form.
  (with-redefs [route/bridge-policy (constantly {:enabled? true :tools #{"read_file"}
                                                 :call-timeout-ms 10000 :poll-ms 20})]
    (let [run-tool (requiring-resolve 'samizdat.agent.tools/run-tool)
          new-branch (requiring-resolve 'samizdat.agent.state/new-branch)
          s (repl/new-session)
          r (run-tool {:tool-name "eval" :root @root :role :implementor
                       :branch (assoc (new-branch {:id "B1" :problem "p"}) :repl-session s)
                       :args {:code "(clojure.string/includes? (tool/read_file {:path \"src/demo/core.clj\"}) \"greet\")"}})]
      (is (str/includes? (str (:result r)) "=> true") (:result r)))))

(deftest a-callable-cell-is-a-function-in-the-image
  ;; karamazov-xn0h.8: :lex/line-count is lex/line-count from eval, and the
  ;; request names the cell and carries the arguments.
  (let [s (repl/new-session)
        seen (atom nil)
        r (bridged s (fn [q] (reset! seen (select-keys q [:op :args])) {:value {:lines 2}})
                   "(:lines (lex/line-count {:path \"a.clj\"}))" [:lex/line-count])]
    (is (= "2" (:value r)) (pr-str r))
    (is (= {:op "cell" :args {:id :lex/line-count :args {:path "a.clj"}}} @seen))))
