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

(ns samizdat.eval.samples
  "The paired-sample grader for the team-solve eval corpus.

  MECHANISM ONLY. A tests directory holds paired `N.in` / `N.out` files; a
  solution is an argv run once per input with the input on stdin; a case
  passes when the program exits 0 and its stdout matches the expected text
  up to trailing whitespace. The result is data — never a model's opinion.
  What the corpus IS, which cases are public and which hidden, lives in
  resources and the eval fixture, not here (docs/plans/agent-roles-team-solve.md).

  The child environment is minimal BY CONSTRUCTION: the graded program is
  model-written, and an inherited environment would let it read anything the
  harness holds. `proc/run` treats a given :env as the COMPLETE environment,
  so one PATH entry is all the child sees — same reasoning as the shell
  tool's scrub (samizdat.security.policy).

  The per-case timeout and every other number here live in
  gates.edn :eval-samples, not in this file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.engine.proc :as proc]
            [samizdat.lexicon :as lexicon]))

(defn paired-tests
  "The paired cases in `dir`, in file order: `{:name :input :expected}`.
  An `.in` with no `.out` carries `:missing true` — the caller decides
  whether that is a corpus error or a failed case."
  [dir]
  (let [d (io/file dir)
        ins (->> (file-seq d)
                 (filter #(.isFile ^java.io.File %))
                 (map #(.getName ^java.io.File %))
                 (filter #(str/ends-with? % ".in"))
                 (sort))]
    (mapv (fn [in]
            (let [base (subs in 0 (- (count in) 3))
                  out (io/file d (str base ".out"))]
              {:name base
               :input (slurp (io/file d in))
               :expected (when (.exists out) (slurp out))
               :missing (not (.exists out))}))
          ins)))

;; --- the raw file seam the team-solve cells use ------------------------------
;;
;; Cells run in the SCI sandbox with no clojure.java.io and no slurp/spit.
;; The workflow's own gate needs to read and write the artifact it grades
;; (solution.py) and to snapshot it; these are that seam, deliberately thin
;; and deliberately not model-facing: the cell passes paths it derived from
;; the run's config, never a model-supplied string.

(defn read-artifact
  "The full text of `path`, or nil when it does not exist. For the graded
  artifact (a few KB of Python), not for sources."
  [path]
  (let [f (io/file (str path))]
    (when (.exists f) (slurp f))))

(defn write-artifact!
  "Write `text` to `path` (parent dirs created). Returns the path."
  [path text]
  (let [f (io/file (str path))]
    (when-let [parent (.getParentFile f)]
      (.mkdirs parent))
    (spit f text)
    (str path)))

(defn- minimal-env []
  {"PATH" (or (System/getenv "PATH") "/usr/bin:/bin")})

(defn- per-case-timeout-ms
  "gates.edn :eval-samples :per-case-timeout-ms — the one place the number
  is written down (the base-test literal scan enforces that). The window
  GVS5H gave its sample runner; large enough for a 200k-line stress input
  under python3."
  []
  (let [v (get-in (lexicon/policy :eval-samples) [:per-case-timeout-ms])]
    (when-not v
      (throw (ex-info "gates.edn :eval-samples is missing :per-case-timeout-ms"
                      {:gate :eval-samples})))
    (long v)))

(defn- norm [s] (str/trimr (str s)))

(defn- run-case
  "One case against `argv`. `opts` as proc/run takes them (:dir), plus
  :timeout-ms per case (default 10s, the window GVS5H gave its samples)."
  [{:keys [name input expected missing]} argv {:keys [dir timeout-ms]}]
  (cond
    missing
    {:name name :reason :missing-expected :input input
     :expected nil :got nil}

    :else
    (let [r (apply proc/run {:input input
                             :timeout-ms (or timeout-ms (per-case-timeout-ms))
                             :env (minimal-env)
                             :dir (str dir)}
                   (mapv str argv))
          out (norm (:out r))]
      (cond
        (:timeout r)
        {:name name :reason :timeout :input input :expected (norm expected)
         :got (str "<timed out after " (:ms r) "ms>")}

        (not (zero? (:exit r)))
        {:name name :reason :runtime-error :input input :expected (norm expected)
         :exit (:exit r)
         :got (str "<exit " (:exit r) "> " (norm (:err r)))}

        (not= out (norm expected))
        {:name name :reason :output :input input :expected (norm expected) :got out}

        :else nil))))

(defn run-one
  "Run `argv` once on `input`, under the grader's environment and window.
  {:ran bool :out text}. `:ran` is false on a timeout or a crash, which proves
  nothing about a counterexample."
  [argv input {:keys [dir timeout-ms]}]
  (let [r (apply proc/run {:input (str input)
                           :timeout-ms (or timeout-ms (per-case-timeout-ms))
                           :env (minimal-env)
                           :dir (str dir)}
                 (mapv str argv))]
    {:ran (and (not (:timeout r)) (zero? (:exit r)))
     :out (norm (:out r))}))

(defn grade
  "Run `argv` against every paired case in `tests-dir`.
  Returns `{:ok? :passed :total :failures}` — `:ok?` only when there were
  cases and every one passed. 0/0 is not a pass: a corpus with no cases
  cannot prove anything, and reporting it green would hide a fixture bug."
  [tests-dir argv opts]
  (let [cases (paired-tests tests-dir)
        failures (keep #(run-case % argv opts) cases)]
    {:ok? (and (seq cases) (empty? failures))
     :passed (- (count cases) (count failures))
     :total (count cases)
     :failures (vec failures)}))
