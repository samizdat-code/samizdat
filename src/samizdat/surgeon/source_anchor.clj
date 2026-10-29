;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/mcp_source_anchor.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.source-anchor
  "Exact source anchors for semantic requests over retained Clojure source."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.zip :as z]
            [samizdat.lexicon :as lexicon]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.outline :as outline]))

(defn- refusal [error-type data]
  (merge {:ok false :error-type error-type :source-unchanged true} data))

(defn- subject-parts [subject]
  (when (string? subject)
    (let [separator (.lastIndexOf ^String subject "/")]
      (when (pos? separator)
        (let [namespace-name (subs subject 0 separator)
              owner (subs subject (inc separator))]
          (when (and (seq namespace-name) (seq owner))
            {:namespace (symbol namespace-name) :owner (symbol owner)}))))))

(defn- owner-name-node [form-node owner]
  (when-let [header-node (->> (node/children form-node)
                              (remove node/whitespace-or-comment?)
                              second)]
    (->> (tree-seq node/inner? node/children header-node)
         (filter node/symbol-node?)
         (filter #(= (name owner) (node/string %)))
         first)))

(defn- owner-selection-range [source owner form-line form-end-line]
  (let [root-node (z/node (z/of-string* source {:track-position? true}))
        form-node (->> (tree-seq node/inner? node/children root-node)
                       (filter node/inner?)
                       (filter #(let [{:keys [row end-row]} (meta %)]
                                  (and (= form-line row) (= form-end-line end-row))))
                       (filter #(owner-name-node % owner))
                       first)
        owner-node (some-> form-node (owner-name-node owner))]
    (when owner-node
      (let [{:keys [row col end-row end-col]} (meta owner-node)]
        (when (every? some? [row col end-row end-col])
          {:start {:line (dec row) :character (dec col)}
           :end {:line (dec end-row) :character (dec end-col)}})))))

(defn build-form-source-anchor
  "Build zero-based LSP ranges from one named form and its exact source bytes."
  [relative-file source {:keys [name line end-line]}]
  (let [owner (some-> name str)
        lines (str/split source #"\n" -1)
        start-text (when line (nth lines (dec line) nil))
        end-text (when end-line (nth lines (dec end-line) nil))
        form-character (count (or (some->> start-text (re-find #"^\s*")) ""))
        selection-range (when (and owner start-text end-text)
                          (owner-selection-range source owner line end-line))]
    (cond
      (not (and owner (seq owner) line end-line start-text end-text))
      (refusal :semantic-candidate-range-invalid
               {:file relative-file :owner owner :line line :end-line end-line})

      (nil? selection-range)
      (refusal :semantic-candidate-selection-missing
               {:file relative-file :owner owner})

      :else
      {:ok true
       :source-anchor
       {:file relative-file
        :source_sha256 (workspace/content-revision source)
        :owner owner
        :range {:start {:line (dec line) :character form-character}
                :end {:line (dec end-line) :character (count end-text)}}
        :selection_range selection-range}})))

(defn build-source-anchor
  "Resolve one fully qualified Var to exactly one named owner in exact source."
  [subject relative-file source]
  (if-let [{:keys [namespace owner]} (subject-parts subject)]
    (try
      (let [outlined (outline/outline-source relative-file source)
            owners (filterv #(= owner (:name %)) (:forms outlined))]
        (cond
          (not= namespace (:ns outlined))
          (refusal :semantic-candidate-namespace-mismatch
                   {:subject subject :file relative-file
                    :expected-namespace namespace :actual-namespace (:ns outlined)})

          (not= 1 (count owners))
          (refusal :semantic-candidate-owner-mismatch
                   {:subject subject :file relative-file
                    :owner owner :owner-count (count owners)})

          :else
          (assoc (build-form-source-anchor relative-file source (first owners))
                 :subject subject)))
      (catch Exception error
        (refusal :semantic-candidate-parse-failed
                 {:subject subject :file relative-file :cause (.getMessage error)})))
    (refusal :invalid-change-subject {:subject subject})))

(defn candidate-relative-files
  "Conventional confined source candidates for one fully qualified Var."
  ([subject] (candidate-relative-files subject ["" "src" "test" "dev"]))
  ([subject source-roots]
   (when-let [{:keys [namespace]} (subject-parts subject)]
     (let [namespace-path (-> (str namespace)
                              (str/replace "-" "_")
                              (str/replace "." "/"))]
       (vec
        (distinct
         (for [root source-roots
               extension (:code (lexicon/wordlist :clj-surgeon-extensions))]
           (str (when (seq root) (str root "/"))
                namespace-path extension))))))))
