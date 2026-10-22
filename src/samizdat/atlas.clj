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

(ns samizdat.atlas
  "The code atlas over a project (RFC-016): the call graph of the files the
  project has, read by samizdat.atlas.clojure, with flows, units, views at
  a zoom level, one function's card, the workflow state graphs, and
  snapshots over time. What the HTTP API, the agent's `atlas` tool and the
  GUI read.

  A file is reread only when its text changed: every call lists and reads
  the sources for their hashes and re-extracts the changed ones, so the
  graph is never staler than the files. Listing and reading are injected
  (`io`, default git's file list under the root and slurp), so a test
  needs no repository.

  MECHANISM. Which files, which entries and sinks, the caps and the unit
  similarity are gates.edn :atlas, read through `policy`."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.atlas.clojure :as clj]
            [samizdat.atlas.flows :as flows]
            [samizdat.atlas.graph :as g]
            [samizdat.atlas.snapshot :as snap]
            [samizdat.atlas.states :as states]
            [samizdat.manifests :as manifests]
            [samizdat.steps :as steps]
            [samizdat.store.db :as db]))

(defn policy
  "gates.edn :atlas."
  []
  (gates/threshold :atlas))

(def default-io
  {:list-files (fn [root] (gitdiff/project-files root))
   :read-file (fn [root path] (try (slurp (io/file root path)) (catch Throwable _ nil)))})

;; --- reading the project -------------------------------------------------------

(defonce ^:private files-cache (atom {}))   ; [root path] -> {:hash :result}
(defonce ^:private derived (atom {}))       ; root -> {:key :graph :flows ...}

(defn forget!
  "Drop every cached reading."
  []
  (reset! files-cache {})
  (reset! derived {}))

(defn- source?
  [path {:keys [include exclude]}]
  (and (some #(re-find (re-pattern %) path) include)
       (not-any? #(re-find (re-pattern %) path) exclude)))

(defn- read-sources
  "Every source file's extraction, from the cache where its text is unchanged."
  [root {:keys [list-files read-file]} pol]
  (vec (for [path (filter #(source? % (:sources pol)) (list-files root))
             :let [text (read-file root path)]
             :when text
             :let [h (hash text)
                   cached (get @files-cache [root path])]]
         (if (= h (:hash cached))
           [path h (:result cached)]
           (let [r (clj/extract-file {:path path :text text})]
             (swap! files-cache assoc [root path] {:hash h :result r})
             [path h r])))))

(defn- state-of
  "The derived state for `root`: its graph, rebuilt only when a source
  changed, and what was computed from it so far."
  [root io]
  (let [pol (policy)
        per (read-sources root (or io default-io) pol)
        k (mapv (fn [[p h]] [p h]) per)
        cur (get @derived root)]
    (if (= k (:key cur))
      cur
      (let [st {:key k :graph (clj/link (mapv peek per))}]
        (swap! derived assoc root st)
        st))))

(defn- cached
  "`(f graph)` under `k` for `root`'s current graph."
  [root io k f]
  (let [{:keys [graph] :as st} (state-of root io)]
    (if (contains? st k)
      (get st k)
      (let [v (f graph)]
        (swap! derived (fn [m] (if (identical? graph (get-in m [root :graph]))
                                 (assoc-in m [root k] v)
                                 m)))
        v))))

(defn graph
  "`root`'s code graph."
  ([root] (graph root nil))
  ([root io] (:graph (state-of root io))))

(defn callers-of [gr id] (g/callers gr id))

(defn- all-flows [root io]
  (cached root io :flows #(flows/all % (policy))))

(defn- units [root io]
  (cached root io :units
          (fn [_] (flows/coarsen (flows/units (all-flows root io))
                                 (or (:unit-similarity (policy)) 1.0)))))

(defn- unit-graph [root io]
  (cached root io :unit-graph #(flows/unit-graph % (units root io))))

;; --- what the viewer asks ----------------------------------------------------------

(defn- brief [gr id]
  (let [n (g/node gr id)]
    {:id id :label (:label n) :kind (:kind n)}))

(defn summary
  "Counts for `root`'s graph, and the files the reader could not finish."
  ([root] (summary root nil))
  ([root io]
   (let [gr (graph root io)
         kinds (frequencies (map :kind (vals (:nodes gr))))]
     {:nodes (count (:nodes gr)) :edges (count (:edges gr)) :kinds kinds
      :entries (count (flows/entries gr (policy)))
      :errors (:errors (meta gr))})))

(defn view
  "`root`'s graph at a zoom level: {:nodes [{:id :label :kind :size
  :expandable?}] :edges [{:from :to :count}]}, through the :namespaces lens
  (containment) or the :units lens (functions grouped by the flows they
  serve), with the groups in `expanded` open."
  ([root opts] (view root opts nil))
  ([root {:keys [lens expanded]} io]
   (let [gr (if (= :units (keyword lens)) (unit-graph root io) (graph root io))
         {:keys [nodes edges]} (g/lift gr (set expanded))]
     {:nodes (mapv (fn [id]
                     (let [n (g/node gr id)]
                       {:id id :label (:label n) :kind (:kind n)
                        :size (max 1 (count (g/leaves gr id)))
                        :expandable? (boolean (seq (g/children gr id)))}))
                   nodes)
      :edges edges})))

(defn- source-of
  [root io {:keys [file line end-line]}]
  (when (and file line)
    (when-let [text ((:read-file (or io default-io)) root file)]
      (let [ls (str/split-lines text)
            end (min (or end-line line) (count ls)
                     (+ line (dec (or (:max-source-lines (policy)) (count ls)))))]
        (str/join "\n" (subvec ls (dec line) end))))))

(defn card
  "Function `id`'s card: the node, its source, its callers and callees with
  their call sites, the flows it serves and the groups holding it."
  ([root id] (card root id nil))
  ([root id io]
   (let [gr (graph root io)]
     (when-let [n (g/node gr id)]
       {:node (dissoc n :hash)
        :source (source-of root io n)
        :callers (mapv #(assoc (brief gr %) :sites (:sites (g/edge gr % id))) (g/callers gr id))
        :callees (mapv #(assoc (brief gr %) :sites (:sites (g/edge gr id %))) (g/callees gr id))
        :flows (flows/through (all-flows root io) id)
        :ancestors (g/ancestors gr id)
        :butterfly (g/butterfly gr id (or (:butterfly-depth (policy)) 1))}))))

(defn flow-list
  "Every flow's summary, by entry."
  ([root] (flow-list root nil))
  ([root io]
   (let [gr (graph root io)]
     (mapv (fn [{:keys [entry slice sinks path-count truncated?]}]
             {:entry entry :label (:label (g/node gr entry)) :path-count path-count
              :slice (count slice) :sinks sinks :truncated? truncated?})
           (all-flows root io)))))

(defn flow-detail
  "The flow from `entry` in full: its paths, their trie, the cycles on them
  and a label for every id it names."
  ([root entry] (flow-detail root entry nil))
  ([root entry io]
   (let [gr (graph root io)]
     (when (g/node gr entry)
       (let [f (flows/flow gr entry (policy))]
         (assoc f
                :slice (vec (sort (:slice f)))
                :trie (flows/trie (:paths f))
                :labels (into {} (map (fn [id] [id (:label (g/node gr id))])) (:slice f))))))))

(defn unit-list
  "The units: functions grouped by the flows they serve."
  ([root] (unit-list root nil))
  ([root io]
   (let [ug (unit-graph root io)]
     (mapv (fn [{:keys [id flows members]}]
             {:id id :label (:label (g/node ug id)) :flows (vec (sort flows)) :members members})
           (units root io)))))

(defn search
  "Definitions whose name holds `q`, any case, exact names first."
  ([root q] (search root q nil))
  ([root q io]
   (let [gr (graph root io)
         q (str/lower-case (str q))]
     (->> (vals (:nodes gr))
          (filter #(str/includes? (str/lower-case (str (:label %))) q))
          (sort-by (juxt #(if (= q (str/lower-case (str (:label %)))) 0 1) :id))
          (mapv #(select-keys % [:id :label :kind :parent]))))))

;; --- workflows as state graphs -----------------------------------------------------

(defn state-graph
  "Manifest `name`'s state graph as {:nodes [...] :edges [{:from :to
  :labels}]}, each state carrying its :cell, the cell's node id here."
  [name]
  (let [sg (states/manifest-graph name (manifests/read-definition (manifests/manifest-body! name)))]
    {:nodes (mapv val (sort-by key (:nodes sg)))
     :edges (mapv (fn [{:keys [from to sites]}] {:from from :to to :labels (mapv :label sites)})
                  (g/edges sg))}))

(defn state-overlay
  "Run `run-id`'s recorded steps over manifest `name`."
  [name run-id]
  (states/overlay name (manifests/read-definition (manifests/manifest-body! name))
                  (:steps (steps/since run-id 0 Integer/MAX_VALUE))))

;; --- history -----------------------------------------------------------------------

(defn- latest [conn root]
  (db/fetch-one conn ["SELECT id, digest FROM atlas_snapshots WHERE root = ? ORDER BY id DESC LIMIT 1"
                      (str root)]))

(defn snapshot!
  "Store `root`'s graph as of run `run-id` when it differs from the last one
  stored for `root`. The new row's id, or nil when nothing changed."
  ([conn root run-id] (snapshot! conn root run-id nil))
  ([conn root run-id io]
   (let [gr (graph root io)
         s (snap/snapshot gr (flows/entries gr (policy)))]
     (when-not (= (:digest s) (:digest (latest conn root)))
       (db/with-writer
         (db/execute! conn ["INSERT INTO atlas_snapshots (root, run_id, digest, body, created_at)
                             VALUES (?, ?, ?, ?, ?)"
                            (str root) (some-> run-id str) (:digest s) (snap/write-edn s) (db/now)])
         (db/last-insert-id conn))))))

(defn snapshots
  "The snapshots stored for `root`, newest first, without their bodies."
  [conn root]
  (if-not conn
    []
    (mapv (fn [r] {:id (:id r) :run-id (:run_id r) :digest (:digest r) :created-at (:created_at r)})
        (db/fetch conn ["SELECT id, run_id, digest, created_at FROM atlas_snapshots
                           WHERE root = ? ORDER BY id DESC" (str root)]))))

(defn- body [conn id]
  (some-> (db/fetch-one conn ["SELECT body FROM atlas_snapshots WHERE id = ?" id]) :body snap/read-edn))

(defn diff
  "What changed from snapshot `a` to snapshot `b` (ids); `b` nil is the
  project as it is now."
  ([conn a b] (diff conn a b nil nil))
  ([conn a b root io]
   (let [sa (body conn a)
         sb (if b
              (body conn b)
              (let [gr (graph root io)] (snap/snapshot gr (flows/entries gr (policy)))))]
     (when (and sa sb) (snap/diff sa sb)))))

(defn run-ended!
  "At a run's end, a snapshot of the project's graph when policy :snapshot?
  says to keep them. Never throws into the caller."
  [conn root run-id]
  (when (and conn root (:snapshot? (policy)))
    (try (snapshot! conn root run-id)
         (catch Throwable e (log/warn "atlas: no snapshot at run end:" (ex-message e))))))
