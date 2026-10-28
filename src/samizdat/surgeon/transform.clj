;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/mcp_program_tool.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.transform
  "Compile bounded pure SCI relations into exact future source. Pure."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.edit :as edit]
            [samizdat.surgeon.edit-dsl :as edit-dsl]
            [samizdat.surgeon.structural-lens :as structural-lens]))

(defn- refuse! [kind data]
  (throw (ex-info (name kind) (merge {:surgeon/error kind} data))))

(defn- contains-node-tag? [tag form-node]
  (or (= tag (node/tag form-node))
      (and (node/inner? form-node)
           (boolean (some #(contains-node-tag? tag %) (node/children form-node))))))

(defn- line-offsets [source]
  (loop [offsets [0] index 0]
    (if-let [newline (str/index-of source "\n" index)]
      (recur (conj offsets (inc newline)) (inc newline))
      offsets)))

(defn- zipper-locations [source]
  (vec (take-while (complement z/end?)
                   (iterate z/next (z/of-string source {:track-position? true})))))

(defn- node-offset [offsets form-node position]
  (let [{:keys [row col end-row end-col]} (meta form-node)
        [line column] (if (= :end position) [end-row end-col] [row col])]
    (when (and line column)
      (+ (nth offsets (dec line)) (dec column)))))

(defn- selected-targets [locations match]
  (let [address (:address match)
        preorders (or (:preorders address)
                      (when (some? (:preorder address)) [(:preorder address)]))
        targets (mapv #(nth locations % nil) preorders)]
    (when (or (empty? targets) (some nil? targets))
      (refuse! :stale-transform-selection {:address address}))
    targets))

(defn- selected-span [source offsets locations match]
  (let [targets (selected-targets locations match)
        start (node-offset offsets (z/node (first targets)) :start)
        end (node-offset offsets (z/node (last targets)) :end)
        before (when (and start end (<= start end) (<= end (count source)))
                 (subs source start end))]
    (when-not (= before (:source match))
      (refuse! :stale-transform-selection {:address (:address match)}))
    {:targets targets :before before :offset start :end-offset end}))

(defn- selected-value [match targets]
  (if (:forms match)
    (mapv z/sexpr targets)
    (z/sexpr (first targets))))

(defn- compile-edit [source offsets locations transformer budget index match]
  (let [{:keys [targets before offset end-offset]} (selected-span source offsets locations match)
        after-value (transformer (selected-value match targets))]
    (when-not (edit-dsl/concrete-edn? after-value)
      (refuse! :invalid-transform-result {:match-index index}))
    (let [after (binding [*print-meta* true] (pr-str after-value))
          changed (max (count before) (count after))
          parsed-before (parser/parse-string-all before)]
      {:file nil
       :before before
       :after after
       :offset offset
       :end-offset end-offset
       :changed-characters changed
       :comment-bearing? (contains-node-tag? :comment parsed-before)
       :uneval-bearing? (contains-node-tag? :uneval parsed-before)})))

(defn- assert-disjoint! [edits]
  (doseq [[left right] (partition 2 1 (sort-by :offset edits))]
    (when (< (:offset right) (:end-offset left))
      (refuse! :overlapping-transform-edits
               {:left-offset (:offset left) :right-offset (:offset right)})))
  edits)

(defn- apply-edits [source edits]
  (reduce (fn [current {:keys [offset end-offset after]}]
            (str (subs current 0 offset) after (subs current end-offset)))
          source
          (sort-by :offset > edits)))

(defn- validate-request [source {:keys [file expression expect]}]
  (when-not (string? source)
    (refuse! :invalid-source {:file file}))
  (when-not (and (string? file) (not (str/blank? file)))
    (refuse! :invalid-transform-request {:file file}))
  (when-not (and (string? expression) (not (str/blank? expression)))
    (refuse! :invalid-transform-request {:file file}))
  (when-not (and (map? expect)
                 (= #{:matches :max_changed_characters} (set (keys expect)))
                 (pos-int? (:matches expect))
                 (pos-int? (:max_changed_characters expect)))
    (refuse! :invalid-transform-expectation {:file file}))
  expect)

(defn plan
  "Return future source, exact match count, and changed characters for one pure relation."
  [source {:keys [file expression expect max-source-chars max-query-steps max-matches]
           :as request}]
  (try
    (validate-request source request)
    (let [{:keys [query transformer]}
          (edit-dsl/compile-transform expression {:max-source-chars max-source-chars})
          found (structural-lens/evaluate-query
                  source query {:file file
                                :max-query-steps max-query-steps
                                :max-matches max-matches})
          actual-count (:match-count found)
          expected-count (:matches expect)
          budget (:max_changed_characters expect)]
      (cond
        (:error-type found)
        (refuse! (:error-type found)
                 (select-keys found [:file :reason :step-index :match-count]))

        (:matches-truncated? found)
        (refuse! :transform-selection-too-large {:match-count actual-count
                                                 :limit max-matches})

        (not= expected-count actual-count)
        (refuse! :expected-count-mismatch {:expected-count expected-count
                                           :actual-count actual-count})

        :else
        (let [offsets (line-offsets source)
              locations (zipper-locations source)
              compiled
              (reduce (fn [result [index match]]
                        (let [compiled (compile-edit source offsets locations transformer
                                                     budget index match)
                              total (+ (:changed-characters result)
                                       (:changed-characters compiled))]
                          (when (> total budget)
                            (refuse! :change-budget-exceeded
                                     {:changed-characters total
                                      :max-changed-characters budget
                                      :match-count actual-count}))
                          (-> result
                              (update :edits conj (assoc compiled :file file))
                              (assoc :changed-characters total)
                              (update :comment-bearing-selection?
                                      #(or % (:comment-bearing? compiled)))
                              (update :uneval-bearing-selection?
                                      #(or % (:uneval-bearing? compiled))))))
                      {:edits [] :changed-characters 0
                       :comment-bearing-selection? false
                       :uneval-bearing-selection? false}
                      (map-indexed vector (:matches found)))
              edits (assert-disjoint! (:edits compiled))]
          (when (every? #(= (:before %) (:after %)) edits)
            (refuse! :no-op-transform {:match-count actual-count}))
          (let [future (apply-edits source edits)]
            (edit/validate-future-source file future)
            {:future-source future
             :matches actual-count
             :changed-characters (:changed-characters compiled)
             :comment-bearing-selection? (:comment-bearing-selection? compiled)
             :uneval-bearing-selection? (:uneval-bearing-selection? compiled)
             :edits (mapv #(dissoc % :comment-bearing? :uneval-bearing?) edits)}))))
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)]
        (if (:surgeon/error data)
          (throw error)
          (refuse! (if (= :transform-required (:reason data))
                     :transform-required
                     :invalid-transform-expression)
                   (select-keys data [:reason :symbol])))))
    (catch Exception _
      (refuse! :transform-program-failed {:file file}))))
