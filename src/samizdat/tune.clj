;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tune
  "Mechanism behind the `tune` workflow, after Agentic Algorithm Engineering
  (CHSZLab/AgenticAlgorithmEngineering): an autonomous loop of falsifiable
  hypotheses, each committed, benchmarked, and kept or discarded against the
  best result so far.

  Pure functions only. When a run is tuned, what counts as a gain, and what
  the model is told to do about it are the workflow's — manifests/tune.edn,
  prompts/tune.md and gates.edn :tune — and the files and git calls are the
  `tune` tool's."
  (:require [clojure.string :as str]))

(defn geomean
  "The geometric mean of `xs`, AAE's default summary of one experiment's
  measurements across inputs or seeds: right for ratios and times, and less
  moved by one outlier than the arithmetic mean. nil for nothing to
  summarize or any value that is not positive, which a geometric mean cannot
  take — a zero time is a broken benchmark, not a fast one."
  [xs]
  (let [v (vec xs)]
    (when (and (seq v) (every? #(and (number? %) (pos? %)) v))
      (Math/exp (/ (reduce + (map #(Math/log (double %)) v)) (count v))))))

(defn judge
  "Keep or discard `value` against the best kept so far.

  `:gain` is the relative improvement in the metric's own `direction`
  (`:lower` or `:higher` is better), positive when it improved. A change is
  kept at `:gain >= min-gain`. AAE's simplicity criterion: a change declared
  `simpler?` (it removes code or complexity) is also kept when it is no worse
  than `min-gain` — an equal result with less code is a win — and the reason
  says so. Returns {:verdict :keep|:discard :gain g :reason :gain|:simpler|:none}."
  [{:keys [direction best value min-gain simpler?]}]
  (let [gain (if (= :higher direction)
               (/ (- value best) (double best))
               (/ (- best value) (double best)))]
    (cond
      (>= gain min-gain) {:verdict :keep :gain gain :reason :gain}
      (and simpler? (> gain (- min-gain))) {:verdict :keep :gain gain :reason :simpler}
      :else {:verdict :discard :gain gain :reason :none})))

(defn slug
  "A few words of `s` as a lower-case kebab slug, at most `n` words; nil for
  nothing usable."
  ([s] (slug s 5))
  ([s n]
   (some->> (re-seq #"[a-z0-9]+" (str/lower-case (str s)))
            (take n)
            seq
            (str/join "-"))))

(defn patch-name
  "The file a kept change is exported to: its acceptance number, zero padded
  to four, then a slug of `title` — `0003-bucket-queue-in-refinement.patch`.
  Numbered in order of acceptance, so applying them in name order to the
  baseline reproduces the best version."
  [n title]
  (format "%04d-%s.patch" (long n) (or (slug title) "change")))

(def tsv-header "commit\tmetric_value\tstatus\thypothesis")

(defn tsv-row
  "One experiment as a results.tsv line. A tab or newline in the hypothesis
  becomes a space, so a row stays one row; a crash has no metric and logs 0."
  [{:keys [commit metric status hypothesis]}]
  (str/join "\t" [(str commit)
                  (format "%.6f" (double (or metric 0.0)))
                  (name status)
                  (str/replace (str hypothesis) #"[\t\r\n]+" " ")]))

(defn parse-values
  "\"3.48, 3.97\" as [3.48 3.97]: numbers split on commas and spaces. nil
  when there is nothing, :bad when any piece is not a number."
  [s]
  (let [parts (remove str/blank? (str/split (str s) #"[,\s]+"))]
    (when (seq parts)
      (let [vs (map #(try (Double/parseDouble %) (catch Exception _ nil)) parts)]
        (if (some nil? vs) :bad (vec vs))))))
