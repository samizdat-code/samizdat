;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/edit_dsl.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.edit-dsl
  "Capability-limited structural X-ray expressions over source snapshots."
  (:refer-clojure :exclude [partition-all])
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [samizdat.resource.workspace :as workspace]
            [samizdat.sandbox.sci :as sandbox]
            [samizdat.surgeon.structural-lens :as structural-lens]
            [sci.core :as sci]))

(def ^:private terminal-steps #{:replace :replace-span :transform})

(def pure-core-symbols
  '[* + - / < <= = == > >=
    and or not boolean true? false? nil? some?
    bit-and bit-or bit-xor bit-not bit-shift-left bit-shift-right
    inc dec int max min mod quot rem zero? pos? neg? even? odd? number? integer?
    int? nat-int? pos-int? neg-int? ratio? rational? float? decimal?
    compare comparator hash
    -> ->> as-> some-> some->> cond-> cond->>
    if if-let if-some when when-not when-let when-some cond condp for
    let let* fn fn* quote do
    identity constantly comp complement partial juxt fnil every-pred some-fn apply
    vector vec list list* hash-map array-map sorted-map sorted-map-by
    hash-set sorted-set sorted-set-by set
    conj cons disj pop peek subvec concat into merge merge-with select-keys
    assoc assoc-in dissoc update update-in
    seq first ffirst nfirst second fnext next rest last butlast nth nthnext key val
    nthrest get get-in find contains? keys vals count empty empty? not-empty
    seq? sequential? associative? coll? counted? indexed? reversible? map?
    vector? set? list? map-entry?
    map mapv mapcat filter filterv remove keep keep-indexed map-indexed
    reduce reduce-kv reductions transduce sequence eduction
    take take-last take-nth take-while drop drop-last drop-while split-at
    split-with partition partition-by interleave interpose flatten tree-seq distinct
    dedupe sort sort-by group-by frequencies zipmap
    some every? not-every? not-any? range
    str subs format name namespace keyword symbol simple-symbol?
    simple-keyword? qualified-symbol? qualified-keyword?
    pr-str print-str println-str meta with-meta vary-meta
    re-pattern re-find re-matches re-seq
    clojure.core/partition-all clojure.core/replace])

(def ^:private macro-expansion-symbols
  '[lazy-seq loop loop* recur case* throw new unchecked-inc
    chunked-seq? chunk-first chunk-rest chunk-buffer chunk-append chunk chunk-cons])

(def ^:private builder-symbols
  '[form line match where right left up down outermost initializer span partition-all
    replace replace-span transform xray xray-one compute aggregate inspect one all
    expect-count analyze])

(def ^:private allowed-symbols (vec (distinct (concat pure-core-symbols builder-symbols))))
(def ^:private sci-allowed-symbols
  (vec (distinct (concat allowed-symbols macro-expansion-symbols))))
(def ^:private forbidden-source-symbols (set (map name macro-expansion-symbols)))

(defn- forbidden-source-symbol
  [form]
  (letfn [(forbidden-symbol? [node]
            (when (symbol? node)
              (let [source-name (name node)]
                (or (contains? forbidden-source-symbols source-name)
                    (str/starts-with? source-name ".")
                    (str/ends-with? source-name ".")))))
          (walk [node]
            (cond
              (and (seq? node) (#{'quote 'clojure.core/quote} (first node))) nil
              (forbidden-symbol? node) node
              (coll? node) (some walk node)
              :else nil))]
    (walk form)))

(defn- terminal-step?
  [step]
  (and (vector? step) (contains? terminal-steps (first step))))

(defn- validate-path
  [path]
  (when-not (vector? path)
    (throw (ex-info "X-ray path must be a vector"
                    {:error-type :invalid-xray-path :path path})))
  (when (terminal-step? (peek path))
    (throw (ex-info "X-ray paths cannot contain edit terminals"
                    {:error-type :invalid-xray-path
                     :terminal-step (peek path)})))
  path)

(defn- append-step
  [path step]
  (conj (validate-path path) step))

(defn- append-counted-step
  [path step value]
  (validate-path path)
  (when-not (pos-int? value)
    (throw (ex-info "Structural step requires a positive integer"
                    {:error-type :invalid-step-argument :step step :value value})))
  (conj path [step value]))

(defn form
  ([form-name] [[:form form-name]])
  ([form-name platform] [[:form form-name platform]]))

(defn line
  [value]
  (when-not (pos-int? value)
    (throw (ex-info "Line root requires a positive integer"
                    {:error-type :invalid-line-root :line value})))
  [[:line value]])

(defn match [path pattern] (append-step path [:find pattern]))
(defn where [path predicates] (append-step path [:where predicates]))
(defn right [path] (append-step path :right))
(defn left [path] (append-step path :left))
(defn up [path] (append-step path :up))
(defn down [path] (append-step path :down))
(defn outermost [path] (append-step path :outermost))
(defn initializer [path] (append-step path :initializer))
(defn span [path n] (append-counted-step path :span n))
(defn partition-all [path n] (append-counted-step path :partition-all n))
(defn replace [path replacement] (append-step path [:replace replacement]))
(defn replace-span [path & replacements]
  (append-step path (into [:replace-span] replacements)))
(defn transform [path transformer] (append-step path [:transform transformer]))

(defn xray
  [path analyzer]
  (validate-path path)
  (when-not (fn? analyzer)
    (throw (ex-info "X-ray analyzer must be a function"
                    {:error-type :invalid-xray-analyzer})))
  {:kind :xray :query path :analyzer analyzer})

(defn xray-one
  [path analyzer]
  (assoc (xray path analyzer) :cardinality :one :input-shape :selected-value))

(defn compute [path analyzer] (xray-one path analyzer))
(defn aggregate [path analyzer] (xray path analyzer))

(defn inspect
  [path cardinality analyzer]
  (case cardinality
    :one (xray-one path analyzer)
    :all (xray path analyzer)
    (throw (ex-info "X-ray cardinality must be one or all"
                    {:error-type :invalid-xray-cardinality
                     :cardinality cardinality :allowed [:one :all]}))))

(defn one [path analyzer] (xray-one path analyzer))
(defn all [path analyzer] (xray path analyzer))

(defn expect-count
  [path n]
  (validate-path path)
  (when-not (and (integer? n) (not (neg? n)))
    (throw (ex-info "Expected match count must be non-negative"
                    {:error-type :invalid-xray-cardinality :expected-count n})))
  {:kind :selection :query path :expected-count n})

(defn analyze
  [selection analyzer]
  (when-not (fn? analyzer)
    (throw (ex-info "X-ray analyzer must be a function"
                    {:error-type :invalid-xray-analyzer})))
  (let [{:keys [query expected-count]}
        (cond
          (vector? selection) {:query (validate-path selection)}
          (and (map? selection) (= :selection (:kind selection))
               (vector? (:query selection))) selection
          :else (throw (ex-info "Analyze input must be a structural path"
                                {:error-type :invalid-xray-path})))]
    {:kind :xray
     :query query
     :analyzer analyzer
     :expected-count expected-count
     :input-shape :selected-values}))

(def ^:private sci-bindings
  {'form form
   'line line
   'match match
   'where where
   'right right
   'left left
   'up up
   'down down
   'outermost outermost
   'initializer initializer
   'span span
   'partition-all partition-all
   'replace replace
   'replace-span replace-span
   'transform transform
   'xray xray
   'xray-one xray-one
   'compute compute
   'aggregate aggregate
   'inspect inspect
   'one one
   'all all
   'expect-count expect-count
   'analyze analyze})

(defn- invalid-xray-expression!
  ([expression reason] (invalid-xray-expression! expression reason nil))
  ([expression reason cause]
   (throw (ex-info "Invalid Clojure X-ray expression"
                   (cond-> {:error-type :invalid-xray-expression
                            :reason reason
                            :expression expression
                            :allowed-symbols allowed-symbols}
                     (:symbol (ex-data cause))
                     (assoc :symbol (:symbol (ex-data cause))))
                   cause))))

(defn- evaluate-expression
  [expression max-source-chars]
  (when-not (string? expression)
    (invalid-xray-expression! expression :expression-must-be-string))
  (when (and max-source-chars (> (count expression) max-source-chars))
    (invalid-xray-expression! expression :expression-too-large))
  (let [context (sandbox/pure-context sci-bindings sci-allowed-symbols)
        reader (sci/reader expression)
        user-ns (sci/create-ns 'user)]
    (try
      (let [form (sci/parse-next context reader)
            trailing (sci/parse-next context reader)]
        (when (or (= :sci.core/eof form) (not= :sci.core/eof trailing))
          (invalid-xray-expression! expression :expected-one-form))
        (when-let [symbol (forbidden-source-symbol form)]
          (invalid-xray-expression!
            expression :disallowed-symbol
            (ex-info "Forbidden source symbol" {:symbol symbol})))
        (:val (sci/eval-string+ context expression {:ns user-ns})))
      (catch Exception exception
        (if (= :invalid-xray-expression (:error-type (ex-data exception)))
          (throw exception)
          (invalid-xray-expression!
            expression
            (cond
              (#{:invalid-xray-path :invalid-xray-analyzer}
               (:error-type (ex-data exception)))
              (:error-type (ex-data exception))

              (or (str/includes? (str (.getMessage exception)) "is not allowed")
                  (str/includes? (str (.getMessage exception)) "Unable to resolve symbol")
                  (str/includes? (str (.getMessage exception)) "Could not find namespace")
                  (str/includes? (str (.getMessage exception)) "Unknown class"))
              :disallowed-symbol

              :else :evaluation-failed)
            exception))))))

(defn compile-xray
  "Compile one pure structural path or terminal computation."
  ([expression] (compile-xray expression nil))
  ([expression {:keys [max-source-chars]}]
   (let [program (evaluate-expression expression max-source-chars)]
     (cond
       (vector? program)
       (do
         (when (terminal-step? (peek program))
           (invalid-xray-expression! expression :xray-terminal-required))
         {:kind :literal :query program :expression expression})

       (and (map? program) (= :xray (:kind program))
            (vector? (:query program)) (fn? (:analyzer program)))
       (assoc program :expression expression)

       (and (map? program) (= :selection (:kind program))
            (vector? (:query program)) (integer? (:expected-count program)))
       {:kind :literal
        :query (:query program)
        :expected-count (:expected-count program)
        :expression expression}

       :else
       (invalid-xray-expression!
         expression :xray-expression-must-return-path-or-computation)))))

(defn- source-value [source] (z/sexpr (z/of-string source)))

(defn- match-value
  [{:keys [forms source]}]
  (if forms (mapv source-value forms) (source-value source)))

(def ^:private canonical-map-constructors
  '#{hash-map clojure.core/hash-map array-map clojure.core/array-map})

(defn- canonical-analysis-value
  [value]
  (if (and (list? value) (contains? canonical-map-constructors (first value)))
    (let [arguments (rest value)]
      (when (odd? (count arguments))
        (throw (ex-info "Map constructor syntax requires key/value pairs"
                        {:error-type :invalid-map-constructor-syntax})))
      (apply array-map arguments))
    value))

(defn- concrete-edn?
  [value]
  (cond
    (or (nil? value) (boolean? value) (char? value) (string? value)
        (symbol? value) (keyword? value) (number? value)) true
    (vector? value) (every? concrete-edn? value)
    (list? value) (every? concrete-edn? value)
    (set? value) (every? concrete-edn? value)
    (map? value) (every? (fn [[k v]] (and (concrete-edn? k) (concrete-edn? v))) value)
    :else false))

(defn- xray-refusal
  [found expression error-type]
  (-> found
      (assoc :operation :xray :expression expression :error-type error-type)
      (dissoc :value)))

(defn- compact-match
  [match]
  (-> (select-keys match [:path :tag :address :line :end-line :count :partition :inside])
      (assoc :source-hash (workspace/content-revision (:source match)))))

(defn- xray-evidence
  [found mode]
  (if (= :full mode)
    found
    (let [sources (mapv :source (:matches found))]
      (cond-> (update found :matches #(mapv compact-match %))
        (not (:matches-truncated? found))
        (assoc :selection-hash (workspace/content-revision (pr-str sources)))))))

(defn- evaluate-computed-xray
  [source {:keys [expression file xray evidence max-query-steps max-matches
                  max-result-chars]}]
  (let [{:keys [query analyzer cardinality input-shape expected-count]} xray
        found (structural-lens/evaluate-query
                source query {:file file
                              :max-query-steps max-query-steps
                              :max-matches max-matches})
        evidence-mode (or evidence :compact)
        evidence (-> (xray-evidence found evidence-mode)
                     (assoc :operation :xray
                            :expression expression
                            :xray {:input-shape (or input-shape :selected-values)
                                   :input-count (:match-count found)
                                   :data-view :canonical-collections
                                   :cardinality (cond
                                                  expected-count [:exactly expected-count]
                                                  cardinality cardinality
                                                  :else :any)
                                   :evidence evidence-mode}))]
    (cond
      (:error-type found) evidence

      (:matches-truncated? found)
      (xray-refusal evidence expression :xray-input-truncated)

      (and expected-count (not= expected-count (:match-count found)))
      (-> (xray-refusal evidence expression :xray-cardinality-mismatch)
          (assoc :expected-match-count expected-count
                 :actual-match-count (:match-count found)))

      (and (= :one cardinality) (not= 1 (:match-count found)))
      (-> (xray-refusal evidence expression :xray-cardinality-mismatch)
          (assoc :expected-match-count 1
                 :actual-match-count (:match-count found)))

      :else
      (let [values (try
                     (mapv #(canonical-analysis-value (match-value %)) (:matches found))
                     (catch Exception exception exception))]
        (if (instance? Exception values)
          (xray-refusal evidence expression :xray-input-invalid)
          (let [analyzer-input (if (= :one cardinality) (first values) values)
                value (try
                        (analyzer analyzer-input)
                        (catch Exception exception exception))]
            (cond
              (instance? Exception value)
              (xray-refusal evidence expression :xray-analysis-failed)

              (not (concrete-edn? value))
              (xray-refusal evidence expression :invalid-xray-result)

              (and max-result-chars (> (count (pr-str value)) max-result-chars))
              (xray-refusal evidence expression :xray-result-too-large)

              :else (assoc evidence :value value))))))))

(defn evaluate-xray
  "Evaluate a compiled literal path or pure computation against source."
  [source {:keys [expression file xray max-query-steps max-matches] :as opts}]
  (if (= :literal (:kind xray))
    (let [found (structural-lens/evaluate-query
                  source (:query xray) {:file file
                                        :max-query-steps max-query-steps
                                        :max-matches max-matches})
          expected-count (:expected-count xray)]
      (cond
        (:error-type found)
        (assoc found :operation :xray :mode :literal :expression expression)

        (and (some? expected-count) (not= expected-count (:match-count found)))
        {:error-type :xray-cardinality-mismatch
         :operation :xray
         :mode :literal
         :expression expression
         :source-hash (:source-hash found)
         :expected-match-count expected-count
         :actual-match-count (:match-count found)}

        :else
        (assoc found :operation :xray :mode :literal :expression expression)))
    (assoc (evaluate-computed-xray source opts) :mode :computed)))
