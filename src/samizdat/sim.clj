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

(ns samizdat.sim
  "The self-improvement memory CARRIED between projects (karamazov-amgk.4).

  A project's record (samizdat.store.sim) lives in its own database, like
  everything else it learned. But a project is seeded from the same templates
  as every other, so a problem one project met on a role — the prompt it
  edited, the cell it rewrote — is evidence for the next project that has
  that role, the way the paper carries the record to a new benchmark. So the
  record follows the person, as settings do: each project's problems are
  published to one file in the person's config directory (layers/global-dir:
  the selected profile's, else ~/.config/samizdat), keyed by the project, and
  another project reads the ones that touched roles it also has.

  Published whole and replaced, never appended: the file holds each
  project's CURRENT record. Kept current through store.sim's change seam, so
  a project that ends mid-run has still published what it had.

  `$SAMIZDAT_SIM_FILE`, or `path-override` (the test runner's), names
  another file. MECHANISM ONLY: what is shown, and how much, is the oversight
  cell's and gates.edn :sim."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.layers :as layers]
            [samizdat.profiles :as profiles]
            [samizdat.store.sim :as store]
            [samizdat.userspace :as userspace]
            [samizdat.store.db :as db]))

(defonce ^{:doc "A path that wins over every other: how the test suite keeps the
  person's real file out of its runs."}
  path-override (atom nil))

(defn path
  "The person-level file, or nil when there is no config directory."
  []
  (or @path-override
      (not-empty (System/getenv "SAMIZDAT_SIM_FILE"))
      (some-> (layers/global-dir) (str "/sim.edn"))))

(defn- read-file [p]
  (try
    (let [v (edn/read-string (slurp p))]
      (if (map? v) v {}))
    (catch Throwable _ {})))

(defn- write-file!
  "Through a sibling temp file and a rename, so a reader never sees half."
  [p v]
  (let [f (io/file p)
        tmp (io/file (str p ".tmp"))]
    (some-> (.getParentFile f) .mkdirs)
    (spit tmp (pr-str v))
    (when-not (.renameTo tmp f)
      (spit f (pr-str v))
      (.delete tmp))))

(def ^:private carried-keys
  [:change :hypothesis :target :model :provider :verdict :settled
   :predicted :fixed :missed :unresolved :before :after :created_at])

(defn- carried
  "A problem as it travels: its name and its attempts, each with what it
  was measured under. Problems with nothing tried are left at home."
  [problems]
  (vec (for [{:keys [name attempts]} problems
             :when (seq attempts)]
         {:name name
          :attempts (mapv #(into {} (remove (comp nil? val)) (select-keys % carried-keys))
                          attempts)})))

(defn publish!
  "Replace project `root`'s entry in the person-level file with its record.
  nil, writing nothing, when there is no file or no project."
  [conn root]
  (when-let [p (path)]
    (when-not (str/blank? (str root))
      (profiles/with-shared-lock p
        (let [now (read-file p)
              ps (carried (store/problems conn))]
          (write-file! p (assoc-in now [:projects (str root)]
                                   {:updated-at (db/now) :problems ps}))
          p)))))

(defn- roles-here
  "The [kind name] pairs this project has a version of."
  [conn]
  (set (map (juxt :kind :name) (db/fetch conn ["SELECT DISTINCT kind, name FROM userspace"]))))

(defn foreign
  "Other projects' problems that touched a role this project has, newest
  project first, with only the attempts on those roles and the project they
  came from: [{:project :name :attempts}]. At most `max-problems`."
  [conn root {:keys [max-problems]}]
  (if-let [p (path)]
    (let [here (roles-here conn)
          projects (->> (:projects (read-file p))
                        (remove #(= (str root) (key %)))
                        (sort-by (comp str :updated-at val) #(compare %2 %1)))]
      (->> (for [[proj {:keys [problems]}] projects
                 {:keys [name attempts]} problems
                 :let [on (filterv #(contains? here [(get-in % [:target :kind])
                                                      (get-in % [:target :name])])
                                   attempts)]
                 :when (seq on)]
             {:project proj :name name :attempts on})
           ((fn [ps] (if max-problems (take max-problems ps) ps)))
           vec))
    []))

(defn foreign-here
  "`foreign` for the bound project: what a cell asks for, since a cell does
  not hold the project's root."
  [conn opts]
  (foreign conn (userspace/project-root) opts))

;; Keep the person's copy current whenever the record changes, for whichever
;; project is bound. Nothing is written with no project bound — a test, a
;; bare REPL.
(store/on-change! (fn [conn]
                    (when-let [root (userspace/project-root)]
                      (publish! conn root))))
