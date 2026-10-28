;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/forward_refs.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.forward-refs
  "Pure forward-reference projections over injected analysis or source data."
  (:require [samizdat.surgeon.analyze :as analyze]))

(defn- largest-gaps [references]
  (->> references
       (group-by :name)
       (map (fn [[_ entries]] (apply max-key :gap entries)))
       (sort-by :gap >)
       vec))

(defn from-analysis
  "Project same-namespace forward references from clj-kondo analysis data."
  [analysis ns-name]
  (let [ns-str (str ns-name)
        definitions (into {}
                          (for [definition (:var-definitions analysis)
                                :when (= ns-str (str (:ns definition)))]
                            [(str (:name definition)) (:row definition)]))]
    (largest-gaps
     (for [usage (:var-usages analysis)
           :let [name (str (:name usage))
                 defined-at (get definitions name)]
           :when (and (= ns-str (str (:from usage)))
                      (= ns-str (str (:to usage)))
                      defined-at
                      (< (:row usage) defined-at))]
       {:name (symbol name)
        :used-at (:row usage)
        :defined-at defined-at
        :gap (- defined-at (:row usage))}))))

(defn from-source
  "Derive conservative source-order evidence without invoking an analyzer."
  [source]
  (let [dependencies (analyze/intra-ns-deps (analyze/string->zloc source))
        lines (into {} (map (juxt :name :line) dependencies))]
    (largest-gaps
     (for [{user :name used-at :line deps :depends-on} dependencies
           dependency deps
           :let [defined-at (get lines dependency)]
           :when (and defined-at (< used-at defined-at))]
       {:name (symbol dependency)
        :used-at used-at
        :defined-at defined-at
        :gap (- defined-at used-at)}))))
