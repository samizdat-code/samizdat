;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/show_form.clj and structural_lens.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.inspect
  "Pure structural inspection of Clojure source snapshots."
  (:require [clojure.string :as str]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.edit-dsl :as edit-dsl]
            [samizdat.surgeon.outline :as outline]
            [samizdat.surgeon.owner-hypotheses :as owner-hypotheses]
            [samizdat.surgeon.structural-lens :as structural-lens]))

(defn outline-source
  "Return a compact outline for one source snapshot."
  [source file]
  (outline/outline-source file source))

(defn- selector-keys
  [opts]
  (->> [:form :forms :line :contains]
       (filter #(contains? opts %))
       vec))

(defn- normalize-form
  [form]
  (cond
    (and (symbol? form) (nil? (namespace form))) form
    (and (string? form)
         (not (str/blank? form))
         (not (str/starts-with? form ":"))
         (or (= "/" form) (re-matches #"[^\s/]+" form)))
    (symbol form)
    :else nil))

(defn- normalize-forms
  [forms]
  (when (vector? forms)
    (mapv normalize-form forms)))

(defn- normalized-selector
  [{:keys [form forms line contains platform include-source?] :as opts}]
  (cond-> {}
    (contains? opts :form) (assoc :form (or (normalize-form form) form))
    (contains? opts :forms) (assoc :forms
                                   (if (vector? forms)
                                     (mapv #(or (normalize-form %) %) forms)
                                     forms))
    (contains? opts :line) (assoc :line line)
    (contains? opts :contains) (assoc :contains contains)
    (contains? opts :platform) (assoc :platform platform)
    (contains? opts :include-source?) (assoc :include-source? include-source?)))

(defn- base-result
  [source file opts]
  {:file file
   :selector (normalized-selector opts)
   :file-hash (workspace/content-revision source)})

(defn- error-result
  [base error-type fields]
  (merge base {:error-type error-type} fields))

(defn- platform-match?
  [platform record]
  (or (nil? platform) (some #{platform} (:platforms record))))

(defn- line-match?
  [line record]
  (let [start (or (:comment-start record) (:line record))]
    (and start (:end-line record) (<= start line (:end-line record)))))

(defn- candidate-location
  [record]
  (select-keys record [:type :name :platforms :line :end-line :comment-start
                       :occurrence-count]))

(def ^:private selected-record-keys
  [:type :name :platforms :line :end-line :comment-start :source
   :occurrence-count :occurrences])

(defn- duplicate-forms
  [forms]
  (let [counts (frequencies forms)]
    (->> forms (filter #(> (get counts %) 1)) distinct vec)))

(defn literal-occurrences
  "Return one-based line/column locations for a nonblank literal in source."
  [source start-line literal]
  (if (or (not (string? literal)) (str/blank? literal))
    []
    (loop [from-index 0 locations []]
      (if-let [offset (str/index-of source literal from-index)]
        (let [prefix (subs source 0 offset)
              newline-count (count (filter #(= \newline %) prefix))
              last-newline (str/last-index-of prefix "\n")
              column (if last-newline (- offset last-newline) (inc offset))]
          (recur (inc offset)
                 (conj locations {:line (+ start-line newline-count)
                                  :column column})))
        locations))))

(defn- owned-source
  [source record]
  (if-let [comment-start (:comment-start record)]
    (let [lines (str/split source #"\n" -1)
          comment-lines (subvec lines (dec comment-start) (dec (:line record)))]
      (str (str/join "\n" comment-lines) "\n" (:source record)))
    (:source record)))

(defn- with-literal-occurrences
  [source record literal]
  (let [locations (literal-occurrences
                    (owned-source source record)
                    (or (:comment-start record) (:line record))
                    literal)]
    (when (seq locations)
      (assoc record :occurrence-count (count locations)
             :occurrences locations))))

(defn- platform-remedy
  [matches]
  (let [platforms (->> matches (mapcat :platforms) distinct sort vec)]
    (when (> (count platforms) 1)
      {:select-platform {:platforms platforms}})))

(defn- batch-form-failure
  [form matches]
  (if (empty? matches)
    {:form form :error-type :form-not-found :match-count 0}
    (cond-> {:form form
             :error-type :ambiguous-form
             :match-count (count matches)
             :matches (mapv candidate-location matches)}
      (platform-remedy matches) (assoc :remedies (platform-remedy matches)))))

(defn- selected-form
  [record include-source?]
  (cond-> (select-keys record selected-record-keys)
    (false? include-source?) (dissoc :source)))

(defn- successful-forms
  [base records include-source?]
  (let [forms (mapv #(selected-form % include-source?) records)]
    (merge base
           {:form-count (count forms)
            :source-char-count (reduce + 0 (map #(count (:source %)) records))
            :forms forms})))

(defn- select-named-forms
  [base records requested-forms include-source?]
  (let [matches-by-name (group-by :name records)
        selections (mapv (fn [form]
                           {:form form :matches (get matches-by-name form [])})
                         requested-forms)
        failures (->> selections
                      (keep (fn [{:keys [form matches]}]
                              (when (not= 1 (count matches))
                                (batch-form-failure form matches))))
                      vec)]
    (if (seq failures)
      (error-result
        base :batch-form-selection-failed
        (merge {:requested-form-count (count requested-forms)
                :resolved-form-count (- (count requested-forms) (count failures))
                :failure-count (count failures)
                :failures failures}
               (owner-hypotheses/owner-recovery-evidence
                 requested-forms records failures)))
      (successful-forms base (mapv #(first (:matches %)) selections)
                        include-source?))))

(defn forms-source
  "Select one top-level form or an ordered batch from one source snapshot.
   Exactly one of :form, :forms, :line, or :contains is required. :platform
   and :include-source? are optional. Pure: source and selector in; EDN out."
  [source file {:keys [form forms line contains platform include-source?]
                :as opts}]
  (let [base (base-result source file opts)
        selectors (selector-keys opts)
        normalized-form (normalize-form form)
        normalized-forms (normalize-forms forms)]
    (cond
      (empty? selectors)
      (error-result base :missing-selector
                    {:required-one-of [:form :forms :line :contains]})

      (> (count selectors) 1)
      (error-result base :conflicting-selectors
                    {:supplied-selectors selectors})

      (and (contains? opts :form) (nil? normalized-form))
      (error-result base :invalid-form-selector {:form form})

      (and (contains? opts :forms)
           (or (not (vector? forms)) (empty? forms)
               (some nil? normalized-forms)))
      (error-result base :invalid-forms-selector {:forms forms})

      (and (contains? opts :forms) (seq (duplicate-forms normalized-forms)))
      (error-result base :duplicate-form-selectors
                    {:duplicate-forms (duplicate-forms normalized-forms)})

      (and (contains? opts :line) (not (and (integer? line) (pos? line))))
      (error-result base :invalid-line {:line line})

      (and (contains? opts :contains)
           (not (and (string? contains) (not (str/blank? contains)))))
      (error-result base :invalid-contains-selector {:contains contains})

      (and (contains? opts :platform) (not (keyword? platform)))
      (error-result base :invalid-platform {:platform platform})

      (and (contains? opts :include-source?)
           (not (boolean? include-source?)))
      (error-result base :invalid-include-source
                    {:include-source? include-source?})

      :else
      (try
        (let [contains-selector? (contains? opts :contains)
              records (outline/top-level-form-records file source)
              platform-records (->> records
                                    (filter #(platform-match? platform %)) vec)
              matches (when-not (contains? opts :forms)
                        (->> platform-records
                             (keep (cond
                                     normalized-form
                                     #(when (= normalized-form (:name %)) %)

                                     contains-selector?
                                     #(with-literal-occurrences source % contains)

                                     :else
                                     #(when (line-match? line %) %)))
                             vec))]
          (if (contains? opts :forms)
            (select-named-forms base platform-records normalized-forms
                                include-source?)
            (cond
              (= 1 (count matches))
              (successful-forms base [(first matches)] include-source?)

              (empty? matches)
              (let [by-name? (some? normalized-form)
                    error-type (cond
                                 by-name? :form-not-found
                                 contains-selector? :contains-not-found
                                 :else :line-not-in-form)]
                (error-result
                  base error-type
                  (cond-> {:match-count 0}
                    by-name?
                    (merge (owner-hypotheses/owner-recovery-evidence
                             [normalized-form] platform-records
                             [{:form normalized-form
                               :error-type error-type
                               :match-count 0}])))))

              :else
              (error-result
                base :ambiguous-form
                (cond-> {:match-count (count matches)
                         :matches (mapv candidate-location matches)}
                  (platform-remedy matches)
                  (assoc :remedies (platform-remedy matches))
                  normalized-form
                  (merge (owner-hypotheses/owner-recovery-evidence
                           [normalized-form] platform-records
                           [(batch-form-failure normalized-form matches)])))))))
        (catch Exception _
          (error-result base :invalid-source {}))))))

(defn match-source
  "Find structural subforms in one source snapshot. Pure: source and a
   selector with :match and optional :inside in; EDN matches out."
  [source selector]
  (structural-lens/find-subforms source selector))

(defn xray-source
  "Evaluate a pure structural X-ray expression against one source snapshot.
   Optional file context and numeric bounds are supplied by the caller."
  [source {:keys [expression evidence] :as opts}]
  (let [evidence (cond
                   (nil? evidence) :compact
                   (string? evidence) (keyword evidence)
                   :else evidence)]
    (if-not (#{:compact :full} evidence)
      {:operation :xray
       :expression expression
       :error-type :invalid-evidence-mode
       :evidence evidence
       :allowed [:compact :full]}
      (try
        (let [opts (assoc opts :evidence evidence)
              program (edit-dsl/compile-xray expression opts)
              result (edit-dsl/evaluate-xray source (assoc opts :xray program))]
          (cond-> result
            (:source-hash result) (assoc :file-hash (:source-hash result))))
        (catch clojure.lang.ExceptionInfo e
          (or (ex-data e)
              {:operation :xray :expression expression :error-type :invalid-source}))
        (catch Exception _
          {:operation :xray :expression expression :error-type :invalid-source})))))
