;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.code-lint
  "Discouraged vars a run has written, found with jolt-lsp's :discouraged-var
  linter against where the tree started. The vars and their levels are the
  policy's (gates.edn :code-lint), not the project's lint config, so a project
  cannot switch them off by accident; the project's own config still decides
  its other linters. Only uses the run added count: a file that already called
  `eval` is not put to a branch that did not write the call. Reading never
  evaluates the source, and a file that will not read is skipped, so noticing
  cannot fail the write it is noticing."
  (:require [clojure.string :as str]
            [jolt-lsp.api :as api]
            [jolt-lsp.db :as db]
            [jolt-lsp.diagnostics :as diagnostics]
            [jolt-lsp.settings :as settings]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.prompt :as prompt]))

(defn discouraged-config
  "The :discouraged-var linter config the policy names: its :presets
  expanded (jolt-lsp's named sets, :security among them), then its own :vars
  over them, as {:vars {\"ns/name\" {:message :level}} :level :in-ns}."
  [{:keys [presets vars level in-ns]}]
  {:vars (merge (apply merge (map settings/discouraged-presets presets))
                (into {} (for [[k v] vars] [(str k) v])))
   :level (or level :warning)
   :in-ns (into {} (for [[n m] in-ns]
                     [(str n) (into {} (for [[k v] m] [(str k) v]))]))})

(defn- source? [{:keys [extensions]} path]
  (some #(str/ends-with? (str path) %) extensions))

(defn- uses
  "The discouraged-var findings in `abs` as `d` indexes it, each with the :var it
  is about, or nil when the file is not indexed or did not parse. The
  diagnostic sits on the var reference's range, so the index's ref there names
  the var."
  [d abs config]
  (let [file (db/file-by-path d abs)]
    (when (and file (not (:parse-error file)))
      (let [fqn-at (into {} (map (juxt (juxt :row :col) :fqn))
                         (db/var-refs-in-file d (:id file)))]
        (for [f (diagnostics/file-diagnostics d abs {:discouraged config})
              :when (= :discouraged-var (:code f))]
          (assoc f :var (fqn-at [(:row f) (:col f)])))))))

(defn- introduced
  "The findings in `after` past the count of the same var in `before`, in
  order, so a run that adds a second `eval` is shown the second."
  [before after]
  (let [was (frequencies (map :var before))]
    (->> (group-by :var after)
         (mapcat (fn [[v fs]] (drop (get was v 0) (sort-by (juxt :row :col) fs)))))))

(defn findings
  "The discouraged-var uses in the files of `before` ({relative-path text},
  nil text for a file the run created) that the run added, as they stand on disk
  under `root` now: each {:path :var :row :col :level :message}, by path and
  position. The baseline text is linted against the same index, so an alias
  resolves the same way in both."
  [policy root before]
  (let [config (discouraged-config policy)
        paths (filter #(source? policy %) (keys before))]
    (when (seq paths)
      (let [root (api/root-for root)
            _ (doseq [p paths] (api/refresh-file! root (api/resolve-path root p)))
            d (api/ensure-db root)]
        (->> (for [p paths
                   :let [abs (api/resolve-path root p)
                         now (uses d abs config)
                         text (get before p)
                         was (when (and now text)
                               (try
                                 (uses (db/update-file-text d root abs text) abs config)
                                 (finally
                                   ;; the patch also rewrote jolt-lsp's analysis
                                   ;; cache; put the file as it stands back
                                   (api/refresh-file! root abs))))]
                   f (introduced was now)]
               {:path p :var (:var f) :row (:row f) :col (:col f)
                :level (:severity f) :message (:message f)})
             (sort-by (juxt :path :row :col))
             vec)))))

(defn report
  "The findings as lines for the model, as many as the policy shows."
  [{:keys [max-shown]} found]
  (str/join "\n"
            (for [{:keys [path var row message]} (take max-shown found)]
              (str/trim (prompt/render "lint-finding"
                                       {:path path :var var :line row :message message})))))

(defn of-tree
  "`findings` for the files changed in `root` since `baseline`, or nil when it
  cannot tell. Never throws."
  [root baseline {:keys [max-files] :as policy}]
  (try
    (when-let [paths (seq (take max-files (filter #(source? policy %)
                                                  (gitdiff/changed-files root baseline))))]
      (findings policy root (into {} (map (juxt identity #(gitdiff/file-at root baseline %))) paths)))
    (catch Throwable _ nil)))
