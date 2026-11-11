;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.outline
  "A file's definitions with the line ranges they span (karamazov-d5wo.10).

  Reading was ~70% of tool-result characters across the campaign dbs, and
  most of it whole files. An outline is what a model needs to ask for the
  range it wants instead: `read_file({path, outline: true})` returns it, and
  an offset and limit read the one definition. Pure; what counts as a
  definition is gates.edn :outline :patterns, one regex per language shape
  whose first group is the name.

  A Clojure file is outlined from jolt-lsp's parse instead (`clojure-outline`):
  each top-level form whose head is a defining form, with the lines the form
  really spans, so a comment block or a `#_` form between two definitions is
  nobody's, and a definition whose name carries metadata is still found."
  (:require [clojure.string :as str]
            [jolt-lsp.forms :as forms]
            [jolt-lsp.tree :as tree]))

(defn outline
  "Every line of `text` a pattern in `patterns` matches, as
  [{:line :end :name}] (1-based, inclusive). A definition runs to the line
  before the next one; the last runs to the end of the file."
  [text patterns]
  (let [lines (str/split-lines (str text))
        res (mapv re-pattern patterns)
        hits (vec (keep-indexed
                   (fn [i l]
                     (some (fn [re] (when-let [m (re-find re l)]
                                      {:line (inc i) :name (if (vector? m) (second m) m)}))
                           res))
                   lines))]
    (vec (map-indexed (fn [k h]
                        (assoc h :end (if-let [nxt (get hits (inc k))]
                                        (dec (:line nxt))
                                        (count lines))))
                      hits))))

(defn clojure-outline
  "Every top-level form of Clojure `text` whose head is in `heads` (strings),
  as [{:line :end :name}] (1-based, inclusive, the form's own lines). The
  parse is tolerant — a broken form is masked, the ones around it kept — so
  an empty outline, not an error, is what tells the caller to fall back to
  the patterns."
  [text heads]
  (try
    (vec (for [{:keys [range node]} (forms/top-level-forms (str text))
               :when (= :list (tree/tag node))
               :let [[h nm] (map (comp tree/token-text tree/meta-form)
                                 (take 2 (tree/children node)))]
               :when (contains? heads h)]
           {:line (nth range 0) :end (nth range 2) :name (or nm h)}))
    (catch Throwable _ nil)))
