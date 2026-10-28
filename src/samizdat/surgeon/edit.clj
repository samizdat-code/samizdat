;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/structural_lens.clj and intent_transaction.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.edit
  "Compile exact structural replacements against frozen source snapshots."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.outline :as outline]))

(defn- refuse!
  [error data]
  (throw (ex-info (name error) (merge {:surgeon/error error} data))))

(defn- lossless-node-fingerprint
  [form-node]
  (when-not (node/whitespace? form-node)
    [(node/tag form-node)
     (if (node/inner? form-node)
       (vec (keep lossless-node-fingerprint (node/children form-node)))
       (node/string form-node))]))

(defn- parse-one-form
  [source field edit-index]
  (when-not (string? source)
    (refuse! :invalid-edit-form {:field field :edit-index edit-index}))
  (try
    (let [forms (->> (parser/parse-string-all source)
                     node/children
                     (remove node/whitespace?)
                     vec)]
      (when-not (and (= 1 (count forms))
                     (not (node/comment? (first forms))))
        (refuse! :invalid-edit-form {:field field :edit-index edit-index}))
      (let [form-node (first forms)]
        {:node form-node
         :source (node/string form-node)
         :fingerprint (lossless-node-fingerprint form-node)}))
    (catch clojure.lang.ExceptionInfo exception
      (if (:surgeon/error (ex-data exception))
        (throw exception)
        (refuse! :invalid-edit-form {:field field :edit-index edit-index})))
    (catch Exception _
      (refuse! :invalid-edit-form {:field field :edit-index edit-index}))))

(defn- validate-complete-source!
  [file source error]
  (when-not (string? source)
    (refuse! error {:file file}))
  (try
    (parser/parse-string-all source)
    source
    (catch Exception _
      (refuse! error {:file file}))))

(defn validate-future-source
  "Validate one complete generated source with the exact-edit parser guard."
  [file source]
  (validate-complete-source! file source :invalid-future-source))

(defn- normalize-within
  [within edit-index]
  (cond
    (nil? within) nil
    (and (map? within)
         (or (symbol? (:form within))
             (and (string? (:form within))
                  (not (str/blank? (:form within))))))
    {:form (str (:form within))}
    :else (refuse! :invalid-within {:edit-index edit-index :within within})))

(defn- validate-edit
  [edit edit-index]
  (when-not (map? edit)
    (refuse! :invalid-edit {:edit-index edit-index}))
  (let [file (:file edit)
        matches (get edit :matches 1)]
    (when-not (and (string? file) (not (str/blank? file)))
      (refuse! :invalid-edit {:edit-index edit-index :file file}))
    (when-not (pos-int? matches)
      (refuse! :invalid-matches {:edit-index edit-index :matches matches}))
    {:edit-index edit-index
     :file file
     :within (normalize-within (:within edit) edit-index)
     :from (parse-one-form (:from edit) :from edit-index)
     :to (parse-one-form (:to edit) :to edit-index)
     :matches matches}))

(defn- zipper-locations
  [root]
  (take-while (complement z/end?) (iterate z/next root)))

(defn- line-offsets
  [source]
  (loop [offsets [0] index 0]
    (if-let [newline (str/index-of source "\n" index)]
      (recur (conj offsets (inc newline)) (inc newline))
      offsets)))

(defn- node-offsets
  [source offsets zloc]
  (let [{:keys [row col end-row end-col]} (meta (z/node zloc))]
    (when (and row col end-row end-col)
      {:start (+ (nth offsets (dec row)) (dec col))
       :end (+ (nth offsets (dec end-row)) (dec end-col))})))

(defn- owner-scope
  [file source offsets locations within edit-index]
  (when within
    (let [owner-name (:form within)
          records (outline/top-level-form-records file source)
          matches (filterv #(= owner-name (some-> % :name str)) records)]
      (when-not (= 1 (count matches))
        (refuse! (if (empty? matches) :owner-not-found :ambiguous-owner)
                 {:edit-index edit-index
                  :file file
                  :within within
                  :owner-match-count (count matches)}))
      (let [record (first matches)
            owner (some (fn [candidate]
                          (let [{:keys [row end-row]} (meta (z/node candidate))]
                            (when (and (= (:line record) row)
                                       (= (:end-line record) end-row)
                                       (= (:source record) (z/string candidate)))
                              candidate)))
                        locations)]
        (when-not owner
          (refuse! :owner-not-addressable
                   {:edit-index edit-index :file file :within within}))
        (node-offsets source offsets owner)))))

(defn- compile-edit
  [{:keys [file source offsets locations]}
   {:keys [edit-index within from to matches] :as edit}]
  (let [scope (owner-scope file source offsets locations within edit-index)
        replacements
        (into []
              (keep (fn [candidate]
                      (when (= (:fingerprint from)
                               (lossless-node-fingerprint (z/node candidate)))
                        (when-let [{:keys [start end]}
                                   (node-offsets source offsets candidate)]
                          (when (or (nil? scope)
                                    (and (<= (:start scope) start)
                                         (<= end (:end scope))))
                            (let [{:keys [row end-row]} (meta (z/node candidate))]
                              {:edit-index edit-index
                               :file file
                               :within within
                               :from (:source from)
                               :to (:source to)
                               :expected-count matches
                               :before (z/string candidate)
                               :after (:source to)
                               :offset start
                               :end-offset end
                               :line row
                               :end-line end-row}))))))
              locations)
        actual-count (count replacements)]
    (when-not (= matches actual-count)
      (refuse! :match-count-mismatch
               {:edit-index edit-index
                :file file
                :within within
                :expected-count matches
                :actual-count actual-count}))
    (mapv #(assoc % :match-count actual-count) replacements)))

(defn- assert-disjoint!
  [file edits]
  (let [ordered (vec (sort-by :offset edits))]
    (doseq [[left right] (partition 2 1 ordered)]
      (when (< (:offset right) (:end-offset left))
        (refuse! :overlapping-edits
                 {:file file
                  :edit-indexes [(:edit-index left) (:edit-index right)]})))
    ordered))

(defn- apply-edits
  [source edits]
  (reduce (fn [current {:keys [offset end-offset after]}]
            (str (subs current 0 offset) after (subs current end-offset)))
          source
          (sort-by :offset > edits)))

(defn- file-context
  [sources file]
  (let [source (get sources file)]
    (validate-complete-source! file source :invalid-source)
    {:file file
     :source source
     :offsets (line-offsets source)
     :locations (vec (zipper-locations (z/of-string source {:track-position? true})))}))

(defn plan-replacements
  "Return future sources and exact raw edits for one frozen source map. Pure."
  [sources edits]
  (try
    (when-not (and (map? sources) (vector? edits))
      (refuse! :invalid-edit-batch {}))
    (let [validated (mapv validate-edit edits (range))
          files (vec (distinct (map :file validated)))
          contexts (into {} (map (fn [file] [file (file-context sources file)]) files))
          compiled (vec (mapcat #(compile-edit (get contexts (:file %)) %) validated))
          edits-by-file (group-by :file compiled)
          ordered-by-file (into {} (map (fn [[file file-edits]]
                                          [file (assert-disjoint! file file-edits)])
                                        edits-by-file))
          future-sources (into {}
                               (map (fn [file]
                                      (let [source (get-in contexts [file :source])
                                            future (apply-edits source (get ordered-by-file file))]
                                        (validate-complete-source! file future
                                                                   :invalid-future-source)
                                        [file future])))
                               files)]
      {:future-sources future-sources
       :edits (vec (mapcat #(get ordered-by-file % []) files))
       :file-hashes (into {} (map (fn [file]
                                    [file (workspace/content-revision
                                            (get-in contexts [file :source]))]))
                          files)})
    (catch clojure.lang.ExceptionInfo exception
      (if (:surgeon/error (ex-data exception))
        (throw exception)
        (refuse! :edit-compiler-failure {:cause (.getMessage exception)})))
    (catch Exception exception
      (refuse! :edit-compiler-failure {:cause (.getMessage exception)}))))
