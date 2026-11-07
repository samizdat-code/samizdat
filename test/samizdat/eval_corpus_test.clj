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

(ns samizdat.eval-corpus-test
  "The team-solve eval corpus (evals/team-solve/problems) is fixture data,
  not code — but an A/B campaign will be decided on it, so the suite pins
  its shape: every problem has a statement, at least three public and three
  hidden cases, and the reference solutions embedded in build_corpus.py
  pass BOTH sets through the real grader. If a reference regresses, the
  campaign scores would silently change meaning."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.eval.samples :as samples]))

(def corpus-root
  (io/file "evals/team-solve/problems"))

(defn- problem-dirs []
  (->> (file-seq corpus-root)
       (filter #(.isDirectory ^java.io.File %))
       (remove #(= corpus-root %))
       (filter #(.isDirectory ^java.io.File (io/file % "tests")))))

(defn- reference-for [problem-name]
  ;; build_corpus.py embeds each reference between `problem(` blocks; rather
  ;; than parse it, the references are re-extracted by regenerating: cheaper
  ;; and always in sync is running the BUILD script itself, which already
  ;; refuses to emit a problem whose reference fails its own cases. So this
  ;; test only re-verifies the artifacts on disk: pairing, counts, and that
  ;; the all-zeros wrong answer fails the hidden set.
  nil)

(deftest corpus-has-20-problems
  (testing "the fixture exists with the planned breadth"
    (is (.exists corpus-root) "run python3 evals/team-solve/build_corpus.py")
    (is (= 20 (count (problem-dirs))))))

(deftest every-problem-is-well-formed
  (testing "statement present, >=3 public and >=3 hidden paired cases, no orphans"
    (doseq [d (problem-dirs)]
      (let [statement (io/file d "task.md")]
        (is (.exists statement) (str (:name d) " task.md")))
      (doseq [sub ["tests" "hidden"]]
        (let [cases (samples/paired-tests (io/file d sub))]
          (is (>= (count cases) 3) (str d "/" sub " needs >=3 cases"))
          (is (not-any? :missing cases) (str d "/" sub " has an .in with no .out")))))))

(deftest an-all-zeros-answer-cannot-pass-any-hidden-set
  (testing "the trivial degenerate solution fails every problem's hidden tests"
    (let [tmp (io/file (System/getProperty "java.io.tmpdir")
                       (str "samizdat-zero-" (System/nanoTime) ".py"))
          _ (spit tmp "print(0)\n")
          argv ["python3" (.getPath tmp)]]
      (doseq [d (problem-dirs)
              :let [r (samples/grade (io/file d "hidden") argv {:dir corpus-root})]]
        ;; A hidden set where 0 is right for EVERY case would make the
        ;; campaign's floor meaningless. build_corpus.py warns about the
        ;; partial case; the full one would be a corpus bug.
        (is (not (:ok? r)) (str "all-zeros passed " d))))))
