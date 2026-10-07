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
