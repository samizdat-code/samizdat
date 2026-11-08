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
            [metrics.entropy :as entropy]
            [metrics.git :as mgit]
            [samizdat.agent.files :as files]
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
                  (files/read-sources root paths))))
    (catch Throwable _ nil)))

;; --- an unusual change (jolt-metrics' metrics.entropy) -----------------------
;;
;; Torres, Baltes, Treude and Wagner (2025): a change that moves the code's
;; information content — the Shannon entropy of its words — far more than the
;; project's commits before it did, either way, is worth a second look. The
;; run's whole change is scored against the project's history as one more
;; commit would be.

(defn- entropy-source?
  "Whether `path` is source the entropy checks read: they count words, so any
  language, by gates.edn :code-metrics :entropy-extensions."
  [{:keys [entropy-extensions] :as policy} path]
  (if entropy-extensions
    (some #(str/ends-with? (str path) %) entropy-extensions)
    (source? policy path)))

(defn- read-texts
  "{path text} for the files among `paths` under `root`, skipping any that will not read."
  [root paths]
  (into {} (keep (fn [p] (when-let [abs (files/resolve-under-root root p)]
                           (let [f (java.io.File. ^String abs)]
                             (when (.isFile f) (try [p (slurp f)] (catch Throwable _ nil)))))))
        paths))

(defn change-entropy
  "How much the change from `before` to `after`, {path text} maps, moved the
  entropy of the source files in them. Pure."
  [policy before after]
  (reduce + 0.0
          (for [path (distinct (concat (keys before) (keys after)))
                :when (entropy-source? policy path)]
            (- (if-let [t (get after path)] (entropy/source-entropy t) 0.0)
               (if-let [t (get before path)] (entropy/source-entropy t) 0.0)))))

(defn unusual
  "{:z :delta :commits} when `delta` is unusual against the entropy changes of
  the `history` commits, nil when it is not or there is too little history to
  tell (metrics.entropy/min-history). Pure."
  [history delta]
  (let [z (last (entropy/z-scores (conj (mapv :delta history) delta)))]
    (when (entropy/unusual? z)
      {:z z :delta delta :commits (count history)})))

(defonce ^:private histories (atom {}))

(defn- history-of
  "The project's commit history of entropy changes up to `baseline`, read once
  per root and baseline: it does not move while a run works on top of it."
  [root baseline policy]
  (let [k [root baseline]]
    (if (contains? @histories k)
      (get @histories k)
      (let [h (mgit/history root (:entropy-commits policy 500) #(entropy-source? policy %))]
        (swap! histories assoc k h)
        h))))

(defn unusual-change
  "`unusual` for the change in `root` since `baseline`, with the :files it
  touched, or nil when it is not unusual or that cannot be told. Never throws."
  [root baseline {:keys [max-files] :as policy}]
  (try
    (when-let [paths (seq (take max-files (filter #(entropy-source? policy %)
                                                  (gitdiff/changed-files root baseline))))]
      (when-let [u (unusual (history-of root baseline policy)
                            (change-entropy policy
                                            (into {} (map (juxt identity #(gitdiff/file-at root baseline %))) paths)
                                            (read-texts root paths)))]
        (assoc u :files (vec paths))))
    (catch Throwable _ nil)))

;; --- an unusual file (jolt-metrics 0.3.2) -------------------------------------
;;
;; A file whose words are far more surprising, given the project's other files,
;; than theirs are given the rest: written in another style, another language's
;; idiom, generated, or pasted in.

(defn unusual-files-in
  "The paths among `changed` whose vocabulary is unusual for the project, as
  [{:path :z}] most surprising first; `sources` is the project's {path text}.
  Pure."
  [sources changed]
  (let [ps (vec (keys sources))
        scored (mapv (fn [p s] {:path p :surprisal s})
                     ps (entropy/surprisals (mapv #(entropy/word-counts (get sources %)) ps)))
        changed (set changed)]
    (vec (for [f (entropy/unusual-files scored)
               :when (contains? changed (:path f))]
           (select-keys f [:path :z])))))

(defn unusual-file-change
  "`unusual-files-in` for the files `root` changed since `baseline`, against the
  project's source files, or nil when none stands out or it cannot tell. Never
  throws."
  [root baseline {:keys [max-files entropy-files] :as policy}]
  (try
    (when-let [changed (seq (take max-files (filter #(entropy-source? policy %)
                                                    (gitdiff/changed-files root baseline))))]
      (let [all (take (or entropy-files 2000)
                      (filter #(entropy-source? policy %) (gitdiff/project-files root)))]
        (not-empty (unusual-files-in (read-texts root all) changed))))
    (catch Throwable _ nil)))
