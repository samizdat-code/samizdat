;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.code-metrics
  "What a run has made worse, measured with jolt-metrics against where the tree
  started. Only what the run introduced or worsened counts, so a project's existing
  complexity is not put to a branch that did not write it. Reading never evaluates the
  source. A file that will not read is skipped: that is another gate's business, and
  noticing must never be able to fail the write it is noticing."
  (:require [clojure.string :as str]
            [metrics.core :as metrics]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.prompt :as prompt]))

(defn- source? [{:keys [extensions]} path]
  (some #(str/ends-with? (str path) %) extensions))

(defn- severe
  "{[path name] entry} for the queue entries of `files`, a {path text} map, at a severity
  the policy cares about."
  [{:keys [severities] :as policy} files]
  (let [readable (filter (fn [[path text]] (and (source? policy path) text)) files)]
    (->> (:queue (metrics/analyze-files (vec readable)))
         (filter #(contains? severities (:severity %)))
         (map (juxt (juxt :path :name) identity))
         (into {}))))

(defn- values [entry]
  (into {} (map (juxt :rule :value)) (:violations entry)))

(defn- worse?
  "Whether `entry` breaks a limit it did not break in `was`, or breaks one by more."
  [entry was]
  (let [before (values was)]
    (boolean (some (fn [[rule value]]
                     (let [old (get before rule)]
                       (or (nil? old) (> value old))))
                   (values entry)))))

(defn findings
  "The severe queue entries of `after` that are new since `before`, or worse than they
  were, worst first. Both are {path text} maps. Pure."
  [policy before after]
  (let [was (severe policy before)]
    (->> (severe policy after)
         (remove (fn [[k entry]] (when-let [old (get was k)] (not (worse? entry old)))))
         (map second)
         (sort-by (comp - :score))
         vec)))

(defn report
  "The findings as lines for the model, as many as the policy shows."
  [{:keys [max-shown]} found]
  (str/join "\n"
            (for [{:keys [path name line violations actions]} (take max-shown found)]
              (str/trim
               (prompt/render "metric-finding"
                              {:path path :name name :line line
                               :numbers (str/join ", " (for [{:keys [rule value limit]} violations]
                                                         (str (clojure.core/name rule) " " value " (limit " limit ")")))
                               :action (first actions)})))))

(defn of-tree
  "`findings` for the files changed in `root` since `baseline`, or nil when it cannot
  tell. Never throws."
  [root baseline {:keys [max-files] :as policy}]
  (try
    (when-let [paths (seq (filter #(source? policy %) (gitdiff/changed-files root baseline)))]
      (let [paths (take max-files paths)]
        (findings policy
                  (into {} (map (juxt identity #(gitdiff/file-at root baseline %))) paths)
                  (into {} (for [p paths
                                 :let [f (java.io.File. (str root "/" p))]
                                 :when (.exists f)]
                             [p (slurp f)])))))
    (catch Throwable _ nil)))
