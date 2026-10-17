;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tui.onboarding
  "What's new, shown once (karamazov-05z0.19, after autolith's versioned
  onboarding pages).

  tui.edn :onboarding is a list of pages, each {:id :version :lines}. A page
  is due when the person has not seen that id at that version: a new page,
  or one whose version was bumped because what it describes changed. Seen
  pages are recorded in the person's ~/.config/samizdat/onboarding.edn as
  {id version}, so a page shows once per person, not once per project.

  Toolkit-free: the TUI calls `due!` at start and prints what it returns;
  /tour shows every page again."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [samizdat.layers :as layers]))

(defn due
  "The pages `seen` ({id version}) has not seen at their current version."
  [pages seen]
  (filterv (fn [{:keys [id version]}]
             (< (long (get seen (str id) 0)) (long (or version 1))))
           pages))

(defn mark
  "`seen` with `pages` recorded at their versions."
  [seen pages]
  (into (or seen {}) (map (fn [{:keys [id version]}] [(str id) (long (or version 1))])) pages))

(defn- seen-file []
  (some-> (layers/global-dir) (str "/onboarding.edn")))

(defn read-seen
  "What this person has seen, {} when nothing is recorded or it does not read."
  []
  (or (try (some-> (seen-file) io/file (#(when (.isFile %) (edn/read-string (slurp %)))))
           (catch Throwable _ nil))
      {}))

(defn due!
  "The pages due for this person, marked seen as they are handed over — a
  page is shown once. Nothing is recorded where there is no config home."
  [pages]
  (let [seen (read-seen)
        d (due pages seen)]
    (when-let [f (and (seq d) (seen-file))]
      (try (io/make-parents f) (spit f (pr-str (mark seen d))) (catch Throwable _ nil)))
    d))

(defn lines
  "The text of `pages` as the conversation's lines."
  [pages]
  (vec (mapcat (fn [{:keys [lines]}] (concat lines [""])) pages)))
