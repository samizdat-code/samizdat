;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl-test
  "The in-process eval seam: the agent develops REPL-first against the live
  harness image — eval a form, see the value and any output, inspect a var,
  complete a name — the way the plan says development should work."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.repl :as repl]))

(deftest close-session-removes-the-namespace
  ;; provenance CR1-6: one namespace per run, never removed — unbounded
  ;; growth on a long-lived serve process.
  (let [s (repl/new-session)]
    (repl/eval-code "(def close-session-leak-check 1)" s)
    (is (some? (find-ns s)))
    (repl/close-session s)
    (is (nil? (find-ns s)))))

(deftest eval-returns-value-and-output
  (testing "a form's value is captured, printed readably"
    (let [r (repl/eval-code "(+ 1 2 3)")]
      (is (:ok r))
      (is (= "6" (:value r)))))
  (testing "stdout is captured alongside the value"
    (let [r (repl/eval-code "(do (println \"side effect\") :done)")]
      (is (:ok r))
      (is (= ":done" (:value r)))
      (is (str/includes? (:out r) "side effect"))))
  (testing "an exception is data, not a thrown error"
    (let [r (repl/eval-code "(/ 1 0)")]
      (is (not (:ok r)))
      (is (str/includes? (:error r) "Divide by zero"))))
  (testing "a read error is reported, not thrown"
    (let [r (repl/eval-code "(+ 1 2")]
      (is (not (:ok r)))
      (is (some? (:error r))))))

(deftest defs-persist-within-a-session
  ;; REPL-first development: define, then use, across calls in one session.
  (let [sess (repl/new-session)]
    (repl/eval-code "(def scratch-x 41)" sess)
    (is (= "42" (:value (repl/eval-code "(inc scratch-x)" sess))))
    (testing "a defn is callable on the next eval"
      (repl/eval-code "(defn scratch-double [n] (* 2 n))" sess)
      (is (= "20" (:value (repl/eval-code "(scratch-double 10)" sess)))))))

(deftest top-level-forms-split-with-their-lines
  ;; What a failure is reported against: the form, by position and line.
  ;; Comments and #_ evaluate nothing, so they are not forms here.
  (is (= [{:text "(def a 1)" :line 1}
          {:text "(def b 2)" :line 3}
          {:text "(+ a b)" :line 4}]
         (repl/top-level-forms "(def a 1)\n\n(def b 2) ; c\n#_(skipped)(+ a b)")))
  (is (= [] (repl/top-level-forms "  ; only a comment\n"))))

(deftest a-failing-form-says-which-and-what-already-ran
  ;; arrodes reports the failing form, its line, and that earlier forms took
  ;; effect; a bare message left the model re-running the whole batch.
  (let [s (repl/new-session)
        r (repl/eval-code "(def before-fail 1)\n(/ 1 0)\n(def after-fail 2)" s)]
    (is (false? (:ok r)))
    (is (= {:index 2 :count 3 :line 2 :completed 1} (:form r)))
    (is (= "1" (:value (repl/eval-code "before-fail" s)))
        "the form before the failure ran and its def stands")
    (is (false? (:ok (repl/eval-code "after-fail" s)))
        "the form after the failure never ran")))

(deftest each-form-is-read-after-the-one-before-it-ran
  ;; A REPL reads a form after evaluating the previous one, so an alias a
  ;; require just made is readable in the next form. Reading the whole batch
  ;; first refused `::alias/kw` that any REPL accepts.
  (let [s (repl/new-session)
        r (repl/eval-code "(require '[clojure.string :as sx]) ::sx/k" s)]
    (is (:ok r) (:error r))
    (is (= ":clojure.string/k" (:value r)))))

(deftest a-fork-keeps-its-parents-macros
  ;; Interning the value alone drops :macro, so a forked branch saw its
  ;; parent's macro as a function and called it with forms as arguments.
  (let [p (repl/new-session)]
    (repl/eval-code "(defmacro twice [x] `(do ~x ~x))" p)
    (let [c (repl/fork-session p)
          r (repl/eval-code "(let [n (atom 0)] (twice (swap! n inc)) @n)" c)]
      (is (:ok r) (:error r))
      (is (= "2" (:value r))))))

(deftest sessions-are-isolated
  (let [a (repl/new-session) b (repl/new-session)]
    (repl/eval-code "(def only-in-a 1)" a)
    (is (:ok (repl/eval-code "only-in-a" a)))
    (is (not (:ok (repl/eval-code "only-in-a" b)))
        "a def in one session is not visible in another")))

(deftest can-reach-the-live-harness-image
  ;; The homoiconic point: the agent evaluates against the running harness, so
  ;; it can inspect and exercise samizdat's own code, not a fresh sandbox.
  (let [r (repl/eval-code "(require '[samizdat.lisp :as l]) (:status (l/balance \"(+ 1 2\"))")]
    (is (:ok r))
    (is (= ":repaired" (:value r)))))

(deftest doc-of-a-project-var
  (testing "arglists and docstring of samizdat's own code"
    (let [r (repl/doc-sym "samizdat.lisp/balance")]
      (is (= '([s]) (:arglists r)))
      (is (str/includes? (:doc r) "delimiter"))))
  (testing "an unknown symbol is reported, not thrown"
    (is (:not-found (repl/doc-sym "samizdat.lisp/nope")))))

(deftest complete-a-prefix
  (testing "public symbols of a namespace matching a prefix"
    (let [ms (repl/complete "samizdat.lisp/b")]
      (is (some #{"samizdat.lisp/balance"} ms))))
  (testing "a bare prefix completes across clojure.core"
    (is (some #{"reduce"} (repl/complete "redu")))))

(deftest eval-is-bounded-so-a-runaway-cannot-hang-the-harness
  ;; An agent's infinite loop or heavy computation used to peg a core and hang
  ;; the harness — eval ran unbounded on a thread the harness waits on. It is
  ;; now bounded; a form that overruns the deadline times out.
  (testing "a form past the deadline times out instead of hanging"
    (let [s (repl/new-session)
          r (repl/eval-code "(Thread/sleep 100000)" s 200)]
      (is (false? (:ok r)))
      (is (= "timeout" (:error-type r)))
      (is (str/includes? (:error r) "timed out"))))
  (testing "a normal form still returns its value"
    (let [s (repl/new-session)
          r (repl/eval-code "(+ 1 2)" s)]
      (is (:ok r))
      (is (= "3" (:value r)))))
  (testing "the agent can raise the timeout for a form that genuinely needs it"
    (let [s (repl/new-session)
          r (repl/eval-code "(do (Thread/sleep 150) :slow-but-ok)" s 3000)]
      (is (:ok r))
      (is (= ":slow-but-ok" (:value r))))))

(deftest a-root-that-is-not-the-working-directory-is-reported
  ;; The seam's worst failure is a plausible wrong answer: source roots make
  ;; the project requirable, but a relative path inside `eval` still resolves
  ;; against the harness's own directory, and jolt has no chdir to fix that
  ;; with. Live, that had the agent read samizdat's README, list samizdat's
  ;; directory, and slurp samizdat's deps.edn believing all three were the
  ;; project's. Nothing in the process notices, so the harness has to say so.
  (testing "a root elsewhere is a mismatch, reported with both directories"
    ;; Compare CANONICALIZED paths on both sides: warn-if-not-cwd! resolves
    ;; symlinks so the two directories it names are the real ones, and on
    ;; macOS /tmp IS a symlink (to /private/tmp) — the raw-string expectation
    ;; kept this suite permanently red there (karamazov-blt.37).
    (let [m (repl/warn-if-not-cwd! "/tmp")]
      (is (some? m))
      (is (= (str (.getCanonicalFile (java.io.File. "/tmp"))) (:root m)))
      (is (not= (:root m) (:cwd m)))))
  (testing "the ordinary self-hosting case is silent"
    (is (nil? (repl/warn-if-not-cwd! ".")))))

(deftest an-eval-cannot-leave-the-image-unable-to-read
  ;; Reader features are process-wide and the agent sets them: the documented
  ;; way to load a library that reads its :clj branches is set, require, set
  ;; back. When the require throws — as it will while the agent is still
  ;; working out which features it needs — the set-back never runs. Live, one
  ;; such eval dropped "bb" from the image; `honey.sql` failed on `::wrapper`
  ;; from that turn onward, every later attempt failed identically, and the
  ;; run wrote the damage into long-term memory as a fact about the library.
  (let [before (__reader-features)]
    (testing "an eval sees the features it sets"
      (is (= ["only"] (:value-read (assoc {} :value-read
                                          (read-string (:value (repl/eval-code
                                            "(__reader-features-set! [\"only\"]) (__reader-features)"))))))))
    (testing "and the image is unchanged afterwards"
      (is (= before (__reader-features))))
    (testing "including when the eval throws mid-way, which is the live case"
      (repl/eval-code "(__reader-features-set! [\"only\"]) (throw (ex-info \"boom\" {}))")
      (is (= before (__reader-features))))))

(deftest the-session-lists-its-own-defs
  (let [s (repl/new-session)]
    (repl/eval-code "(defn helper \"Doubles.\" [n] (* 2 n)) (def xs [1 2])" s)
    (let [{:keys [defs total]} (repl/session-defs s 60 100)]
      (is (= 2 total))
      (is (= #{"helper" "xs"} (set (map :name defs))))
      (is (= "Doubles." (:doc (first (filter #(= "helper" (:name %)) defs))))))
    (is (nil? (repl/session-defs nil 60 100)))))
