;; samizdat - a claim-first verification harness
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

(ns samizdat.agent.shrink
  "A long tool output reduced to the lines that matter before it is clipped,
  after caveman's log and repetition compressors (engine/compressors/log.go,
  repetition.go). The head-and-tail clip alone kept a build log's first and
  last lines and dropped the error in the middle.

  Two passes, both deterministic and line-preserving: runs of identical
  lines collapse to one plus a count; then, when the output is still long,
  the head, the tail, every line the keep pattern matches (errors,
  failures, tracebacks, test summaries) and a few lines after each stay,
  and each dropped run becomes one line saying how many. Order is never
  changed and kept lines are never edited. The whole output is still written
  where the clip spills it, so nothing is lost. Pure: the policy and the
  pattern come in as arguments."
  (:require [clojure.string :as str]))

(defn- gap [n] (str "[... " n " lines]"))

(defn repetition
  "`s` with each run of more than `min-run` identical consecutive lines
  collapsed to its first line and a count of the rest."
  [s min-run]
  (let [lines (str/split-lines (str s))]
    (->> (partition-by identity lines)
         (mapcat (fn [run]
                   (if (> (count run) (long min-run))
                     [(first run) (str "[... " (dec (count run)) " more identical lines]")]
                     run)))
         (str/join "\n"))))

(defn- kept-indices
  [lines {:keys [keep-head keep-tail context-after keep]}]
  (let [n (count lines)
        hits (keep-indexed (fn [i l] (when (and keep (re-find keep l)) i)) lines)]
    (into (into (set (range (min n keep-head))) (range (max 0 (- n keep-tail)) n))
          (mapcat (fn [i] (range i (min n (+ i 1 context-after)))) hits))))

(defn shrink
  "`s` shrunk as the namespace describes, or `s` unchanged when it is under
  `:min-chars` or shrinking would not make it shorter. Options: :min-chars
  :min-run :keep-head :keep-tail :context-after, and :keep, a regex."
  [s {:keys [min-chars min-run] :as opts}]
  (let [s (str s)]
    (if (< (count s) (long min-chars))
      s
      (let [r (repetition s min-run)]
        (if (< (count r) (long min-chars))
          r
          (let [lines (vec (str/split-lines r))
                keep (kept-indices lines opts)
                out (loop [i 0 dropped 0 acc (transient [])]
                      (if (= i (count lines))
                        (persistent! (cond-> acc (pos? dropped) (conj! (gap dropped))))
                        (if (contains? keep i)
                          (recur (inc i) 0 (cond-> acc
                                             (pos? dropped) (conj! (gap dropped))
                                             true (conj! (nth lines i))))
                          (recur (inc i) (inc dropped) acc))))
                joined (str/join "\n" out)]
            (if (< (count joined) (count r)) joined r)))))))
