;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/operation_algebra.clj and intent_transaction.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.operation-algebra
  "Pure canonical identity for one compiled structural effect set."
  (:require [samizdat.resource.workspace :as workspace]))

(defn- refuse! [_ data]
  (throw (ex-info "invalid-canonical-effect-input"
                  (merge {:surgeon/error :invalid-canonical-effect-input} data))))

(defn- effect-row [file edit]
  (let [{:keys [path address end-preorder offset before after operator]} edit
        preorder (:preorder address)]
    (when-not (and (string? file)
                   (vector? path) (seq path) (every? nat-int? path)
                   (nat-int? preorder) (nat-int? end-preorder)
                   (<= preorder end-preorder) (nat-int? offset)
                   (string? before) (string? after)
                   (contains? #{:replace :insert-before :insert-after :delete}
                              operator))
      (refuse! nil {:file file :change-id (:change-id edit)}))
    {:sort-key [path preorder end-preorder offset operator before after]
     :row [:effect operator path preorder end-preorder offset before after]}))

(defn canonical-effects
  "Hash the exact frozen inputs and resolved effects, independent of batch order."
  [original-sources future-sources edits]
  (let [by-file (group-by :file edits)
        files (vec (sort (keys by-file)))
        records
        (mapv (fn [file]
                (let [original (get original-sources file)
                      future (get future-sources file)
                      file-edits (get by-file file)]
                  (when-not (and (string? original) (string? future)
                                 (vector? file-edits) (seq file-edits))
                    (refuse! nil {:file file}))
                  (let [rows (->> file-edits
                                  (map #(effect-row file %))
                                  (sort-by :sort-key)
                                  (mapv :row))]
                    {:file file
                     :effect-count (count rows)
                     :row [:file file
                           (workspace/content-revision original)
                           (workspace/content-revision future)
                           rows]})))
              files)
        projection [:canonical-effect/v1
                    (mapv :row records)
                    (count records)
                    (reduce + 0 (map :effect-count records))]]
    {:version 1
     :sha256 (workspace/content-revision (pr-str projection))
     :files (count records)
     :effects (reduce + 0 (map :effect-count records))
     :projection projection}))
