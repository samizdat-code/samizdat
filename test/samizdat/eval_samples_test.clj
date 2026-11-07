;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.eval-samples-test
  "The paired-sample grader for the team-solve eval corpus. The grader is the
  ground truth BOTH arms of the A/B are scored by — a model never decides a
  case — so its behaviour is pinned here in full: pass, wrong output, runtime
  error, timeout, missing expected file, empty corpus, trailing-newline
  tolerance."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.eval.samples :as samples]))

(def ^:dynamic *dir* nil)

(defn- write-case! [tests name input expected]
  (let [d (io/file *dir* tests)]
    (.mkdirs d)
    (spit (io/file d (str name ".in")) input)
    (when (some? expected)
      (spit (io/file d (str name ".out")) expected))))

(defn fixture [f]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "samizdat-eval-samples-" (System/nanoTime)))]
    (.mkdirs d)
    (binding [*dir* d]
      (f))
    (doseq [^java.io.File file (reverse (file-seq d))]
      (when (.isFile file) (.delete file)))
    (.delete d)))

(use-fixtures :each fixture)

(def pass-solution
  "a = list(map(int, input().split()))
print(sum(a))")

(def wrong-solution
  ;; Correct for a 3-number line, wrong for a 2-number line: one case passes,
  ;; one fails, and the failure report is what the test pins.
  "a = list(map(int, input().split()))
print(sum(a) + (1 if len(a) == 2 else 0))")

(defn- solve-file! [body]
  (let [f (io/file *dir* "solution.py")]
    (spit f body)
    (.getPath f)))

(defn- grade-in-dir [solution tests]
  (samples/grade (io/file *dir* tests) ["python3" solution]
                 {:dir *dir*}))

(deftest correct-solution-passes-every-case
  (testing "sum of a list, right program, two paired cases"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (write-case! "tests" "2" "10 20\n" "30\n")
    (let [r (grade-in-dir (solve-file! pass-solution) "tests")]
      (is (:ok? r))
      (is (= 2 (:passed r)))
      (is (= 2 (:total r)))
      (is (empty? (:failures r))))))

(deftest wrong-solution-fails-with-the-case-named
  (testing "the first wrong case is reported with input, expected and got"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (write-case! "tests" "2" "10 20\n" "30\n")
    (let [r (grade-in-dir (solve-file! wrong-solution) "tests")
          [f & more] (:failures r)]
      (is (not (:ok? r)))
      (is (= 1 (:passed r)) "the first case still passes")
      (is (= 1 (count (:failures r))) "only the wrong case is a failure")
      (is (= "2" (:name f)))
      (is (= "10 20\n" (:input f)))
      (is (= "30" (:expected f)))
      (is (= "31" (:got f))))))

(deftest trailing-newline-difference-is-not-a-failure
  (testing "stdout missing the final newline still matches an expected file that has it"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (let [sol (solve-file! "print(sum(map(int, input().split())), end='')")
          r (grade-in-dir sol "tests")]
      (is (:ok? r)))))

(deftest nonzero-exit-is-a-runtime-error-failure
  (testing "a crashing program fails the case with its stderr carried"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (let [sol (solve-file! "raise SystemExit('boom')")
          r (grade-in-dir sol "tests")
          [{:keys [reason exit]}] (:failures r)]
      (is (not (:ok? r)))
      (is (= :runtime-error reason))
      (is (pos? exit))
      (is (.contains ^String (get-in r [:failures 0 :got]) "boom")))))

(deftest timeout-is-a-failure-not-a-hang
  (testing "a program past the per-case timeout fails that case as :timeout"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (let [sol (solve-file! "import time
time.sleep(30)")
          r (samples/grade (io/file *dir* "tests") ["python3" sol]
                           {:dir *dir* :timeout-ms 500})
          [{:keys [reason]}] (:failures r)]
      (is (not (:ok? r)))
      (is (= :timeout reason)))))

(deftest an-input-without-its-expected-file-fails-the-case
  (testing "a stray .in with no .out is a failure naming it, not a silent skip"
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (write-case! "tests" "2" "10 20\n" nil)
    (let [sol (solve-file! pass-solution)
          r (grade-in-dir sol "tests")
          [{:keys [name reason]}] (:failures r)]
      (is (not (:ok? r)))
      (is (= "2" name))
      (is (= :missing-expected reason)))))

(deftest no-inputs-at-all-is-not-ok
  (testing "an empty tests directory grades as 0/0 and not ok — there is nothing to pass"
    (.mkdirs (io/file *dir* "tests"))
    (let [sol (solve-file! pass-solution)
          r (grade-in-dir sol "tests")]
      (is (not (:ok? r)))
      (is (zero? (:total r)))
      (is (zero? (:passed r))))))

(deftest paired-tests-reads-the-corpus-in-order
  (testing "paired-tests lists .in files sorted by name with their expected text"
    (write-case! "tests" "2" "b\n" "out-b\n")
    (write-case! "tests" "10" "c\n" "out-c\n")
    (write-case! "tests" "1" "a\n" "out-a\n")
    (let [names (map :name (samples/paired-tests (io/file *dir* "tests")))]
      (is (= ["1" "10" "2"] names)
          "lexicographic file order, like the shell glob in memdoor's check-samples.sh"))))

(deftest the-solution-runs-in-a-minimal-environment
  (testing "an env var present in the parent is not visible to the graded program"
    ;; The grader runs MODEL-WRITTEN code. If it inherited the parent's
    ;; environment, a solution could read a provider key out of it and phone
    ;; home, or read it and print it — scoring itself a pass on any task it
    ;; likes. The environment must be minimal by construction, like the
    ;; shell tool's scrub.
    (write-case! "tests" "1" "1 2 3\n" "6\n")
    (spit (io/file *dir* "probe.py")
          "import os
print(sum(map(int, input().split())) + (1 if 'SAMIZDAT_GRADER_PROBE' in os.environ else 0))")
    (let [r (samples/grade (io/file *dir* "tests") ["python3" (.getPath (io/file *dir* "probe.py"))]
                           {:dir *dir*})]
      ;; If the probe var leaked, got would be 7 and this case would fail.
      (is (:ok? r)))))
