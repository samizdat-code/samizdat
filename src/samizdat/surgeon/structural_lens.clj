;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/structural_lens.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.structural-lens
  "Exact structural search below a named top-level form."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.cljc.walk :as cwalk]
            [samizdat.surgeon.forms :as forms]
            [samizdat.surgeon.outline :as outline]))

(defn- one-complete-form
  [value error-type label]
  (when (nil? value)
    (throw (ex-info (str label " is required") {:error-type error-type})))
  (let [source (if (string? value) value (pr-str value))]
    (try
      (let [root (z/of-string source {:track-position? true})
            forms (->> (iterate z/right root) (take-while some?) vec)]
        (when-not (= 1 (count forms))
          (throw (ex-info (str label " must contain exactly one complete form")
                          {:error-type error-type})))
        {:sexpr (z/sexpr (first forms))
         :source (z/string (first forms))})
      (catch clojure.lang.ExceptionInfo e
        (if (= error-type (:error-type (ex-data e)))
          (throw e)
          (throw (ex-info (str "Invalid " (.toLowerCase ^String label) ": "
                               (.getMessage e))
                          {:error-type error-type}))))
      (catch Exception e
        (throw (ex-info (str "Invalid " (.toLowerCase ^String label) ": "
                             (.getMessage e))
                        {:error-type error-type}))))))

(defn- wildcard-match?
  [pattern candidate]
  (cond
    (= '_ pattern) true
    (and (map? pattern) (map? candidate))
    (and (= (set (keys pattern)) (set (keys candidate)))
         (every? (fn [[k v]] (wildcard-match? v (get candidate k))) pattern))
    (and (sequential? pattern) (sequential? candidate))
    (and (= (count pattern) (count candidate))
         (every? true? (map wildcard-match? pattern candidate)))
    (and (set? pattern) (set? candidate)) (= pattern candidate)
    :else (= pattern candidate)))

(defn- zipper-locations
  [zloc]
  (->> (iterate z/next zloc) (take-while #(and % (not (z/end? %))))))

(defn- top-level-locations
  [zloc]
  (->> (iterate z/right zloc) (take-while some?)))

(defn- defining-form-name
  [zloc]
  (when (z/list? zloc)
    (let [head (some-> zloc z/down z/sexpr str)]
      (when (forms/spec-with-project-aliases {} head)
        (some-> zloc z/down z/right z/sexpr str)))))

(defn- inside-ranges
  [zloc inside]
  (keep (fn [candidate]
          (when (= (str inside) (defining-form-name candidate))
            (let [{:keys [row end-row]} (meta (z/node candidate))]
              {:zloc candidate :row row :end-row end-row})))
        (top-level-locations zloc)))

(defn- owner-location
  [{:keys [zloc row end-row]}]
  {:type (some-> zloc z/down z/sexpr)
   :name (symbol (defining-form-name zloc))
   :line row
   :end-line end-row})

(defn- within-range?
  [{:keys [row end-row]} zloc]
  (let [{node-row :row node-end-row :end-row} (meta (z/node zloc))]
    (and node-row node-end-row (<= row node-row) (<= node-end-row end-row))))

(defn- enclosing-form-name
  [top-levels candidate]
  (some (fn [top-level]
          (let [{:keys [row end-row]} (meta (z/node top-level))]
            (when (within-range? {:row row :end-row end-row} candidate)
              (defining-form-name top-level))))
        top-levels))

(defn- node-head
  [zloc]
  (when-let [child (z/down zloc)]
    (try (z/sexpr child) (catch Exception _ nil))))

(defn- semantic-path
  [zloc inside]
  (loop [current zloc child nil path '()]
    (if-not current
      (vec path)
      (let [head (node-head current)
            descriptor
            (cond
              (z/list? current)
              (if (and inside (= (str inside) (defining-form-name current)))
                {:form (symbol (str inside))}
                (when head {:call head}))
              (z/vector? current)
              (if (keyword? head)
                {:vector-tag head}
                (let [left (some-> child z/left)
                      binding (when left (try (z/sexpr left) (catch Exception _ nil)))]
                  (if (symbol? binding) {:binding binding} {:vector true})))
              (z/map? current)
              (let [left (some-> child z/left)
                    key (when left (try (z/sexpr left) (catch Exception _ nil)))]
                (if (keyword? key) {:attr key} {:map true}))
              :else nil)]
        (recur (z/up current) current
               (cond-> path descriptor (conj descriptor)))))))

(def supported-query-steps
  [[:form 'NAME] [:line 'POSITIVE-LINE] [:find 'PATTERN]
   [:where {:tag :TAG}] [:where {:parent-tag :TAG}]
   :right :left :up :down :outermost :initializer
   [:span 'POSITIVE-COUNT] [:partition-all 'POSITIVE-COUNT]])

(def ^:private navigation-steps #{:right :left :up :down})
(def ^:private where-keys #{:tag :parent-tag})

(defn- invalid-query!
  [reason data]
  (throw (ex-info (name reason)
                  (merge {:error-type :invalid-query
                          :reason reason
                          :supported-query-steps supported-query-steps}
                         data))))

(defn- parse-query
  [query max-query-steps]
  (let [parsed (cond
                 (vector? query) query
                 (string? query)
                 (if (str/blank? query)
                   (invalid-query! :nonempty-vector-required {})
                   (:sexpr (one-complete-form query :invalid-query "Query")))
                 :else (invalid-query! :nonempty-vector-required {}))]
    (when-not (vector? parsed)
      (invalid-query! :edn-vector-required {}))
    (when (empty? parsed)
      (invalid-query! :empty-query {}))
    (when (and max-query-steps (> (count parsed) max-query-steps))
      (invalid-query! :step-limit
                      {:step-count (count parsed)
                       :max-query-steps max-query-steps}))
    (doseq [[index step] (map-indexed vector parsed)]
      (cond
        (navigation-steps step) nil
        (#{:outermost :initializer} step) nil
        (not (vector? step))
        (invalid-query! :unsupported-step {:step-index index :step step})

        (= :form (first step))
        (when-not (and (zero? index)
                       (#{2 3} (count step))
                       (or (symbol? (second step))
                           (and (string? (second step))
                                (not (str/blank? (second step)))))
                       (or (= 2 (count step)) (keyword? (nth step 2))))
          (invalid-query! :invalid-form-root {:step-index index :step step}))

        (= :line (first step))
        (when-not (and (zero? index) (= 2 (count step))
                       (pos-int? (second step)))
          (invalid-query! :invalid-line-root {:step-index index :step step}))

        (= :find (first step))
        (when-not (= 2 (count step))
          (invalid-query! :invalid-find {:step-index index :step step}))

        (= :where (first step))
        (let [predicates (second step)]
          (when-not (and (= 2 (count step)) (map? predicates) (seq predicates)
                         (every? where-keys (keys predicates))
                         (every? keyword? (vals predicates)))
            (invalid-query! :invalid-predicates
                            {:step-index index :step step})))

        (#{:span :partition-all} (first step))
        (when-not (and (= 2 (count step)) (pos-int? (second step))
                       (= index (dec (count parsed))))
          (invalid-query! :invalid-terminal-span
                          {:step-index index :step step}))

        :else
        (invalid-query! :unsupported-step {:step-index index :step step})))
    parsed))

(defn- location-key
  [zloc]
  (let [{:keys [row col end-row end-col]} (meta (z/node zloc))]
    [row col end-row end-col (z/tag zloc)]))

(defn- unique-items
  [items]
  (second
    (reduce (fn [[seen result] item]
              (let [address (if (= :span (:kind item))
                              [:span (:addresses item)]
                              (:address item))]
                (if (contains? seen address)
                  [seen result]
                  [(conj seen address) (conj result item)])))
            [#{} []]
            items)))

(defn- outermost-items
  [items]
  (let [items (unique-items items)
        current-locations (set (map (comp location-key :zloc) items))]
    (filterv (fn [{:keys [zloc]}]
               (not-any? #(contains? current-locations (location-key %))
                         (take-while some? (iterate z/up (z/up zloc)))))
             items)))

(defn- semantic-span
  [{:keys [by-location]} {:keys [zloc]} span-count]
  (let [zlocs (->> (iterate z/right zloc)
                   (take span-count)
                   (take-while some?)
                   vec)]
    (when (= span-count (count zlocs))
      (let [items (mapv #(get by-location (location-key %)) zlocs)]
        (when (every? some? items)
          {:kind :span
           :zloc (first zlocs)
           :zlocs zlocs
           :address (:address (first items))
           :addresses (mapv :address items)})))))

(defn- semantic-partitions
  [{:keys [by-location]} {:keys [zloc]} size]
  (->> (iterate z/right zloc)
       (take-while some?)
       (partition-all size)
       (map-indexed
         (fn [index zlocs]
           (let [zlocs (vec zlocs)
                 items (mapv #(get by-location (location-key %)) zlocs)]
             (when (every? some? items)
               {:kind :span
                :zloc (first zlocs)
                :zlocs zlocs
                :address (:address (first items))
                :addresses (mapv :address items)
                :partition {:size size
                            :index index
                            :complete? (= size (count zlocs))}}))))
       (keep identity)))

(defn- raw-span-source
  [zlocs]
  (let [end-key (location-key (last zlocs))]
    (loop [current (first zlocs) result ""]
      (let [result (str result (z/string current))]
        (if (= end-key (location-key current))
          result
          (recur (z/right* current) result))))))

(defn- raw-between
  [left right]
  (let [end-key (location-key right)]
    (loop [current (z/right* left) result ""]
      (if (= end-key (location-key current))
        result
        (recur (z/right* current) (str result (z/string current)))))))

(defn- span-gaps
  [zlocs]
  (mapv (fn [[left right]] (raw-between left right))
        (partition 2 1 zlocs)))

(defn platform-context-for-file
  "Return the platforms valid for ordinary forms in a Clojure source file."
  [file]
  (when file
    (case (some->> (re-find #"(?i)\.([^.\\/]+)$" (str file))
                   second
                   str/lower-case)
      "clj" #{:clj}
      "cljs" #{:cljs}
      "cljc" #{:clj :cljs}
      nil)))

(defn- source-index
  [root default-platforms source]
  (let [locations (vec (zipper-locations root))
        lines (str/split-lines source)
        entries (mapv (fn [address zloc] {:address address :zloc zloc})
                      (range) locations)
        walked (->> (cwalk/top-level-forms source default-platforms)
                    (mapv #(update % :platforms set/intersection default-platforms)))
        walked-by-location
        (into {}
              (map (fn [{:keys [zloc platforms]}]
                     (let [{:keys [row end-row]} (meta (z/node zloc))]
                       [(location-key zloc)
                        {:platforms platforms
                         :owner-line row
                         :owner-end-line end-row
                         :comment-start
                         (when row (outline/attached-comment-start lines row))}]))
              walked))
        entries (mapv (fn [item]
                        (if-let [owner (get walked-by-location
                                            (location-key (:zloc item)))]
                          (merge item owner)
                          item))
                      entries)
        by-location (into {} (map (fn [item]
                                    [(location-key (:zloc item)) item])
                                  entries))
        ordinary-top-levels (top-level-locations root)
        walked-top-levels (map :zloc walked)
        line-roots (into [] (keep #(get by-location (location-key %)))
                         walked-top-levels)]
    {:entries entries
     :by-location by-location
     :top-levels (vec walked-top-levels)
     :line-roots (unique-items line-roots)
     :initial (unique-items
                (into [] (keep #(get by-location (location-key %)))
                      (concat ordinary-top-levels walked-top-levels)))}))

(defn- subtree-items
  [entries {:keys [address zloc]}]
  (let [subtree-size (count (zipper-locations (z/subzip zloc)))
        end (min (count entries) (+ address subtree-size))]
    (subvec entries address end)))

(defn- matching-descendants
  [entries item pattern]
  (keep (fn [candidate]
          (when (try
                  (wildcard-match? pattern (z/sexpr (:zloc candidate)))
                  (catch Exception _ false))
            candidate))
        (subtree-items entries item)))

(defn- where-match?
  [zloc predicates]
  (and (if-let [tag (:tag predicates)]
         (= tag (z/tag zloc))
         true)
       (if-let [parent-tag (:parent-tag predicates)]
         (= parent-tag (some-> zloc z/up z/tag))
         true)))

(defn- navigate
  [zloc step]
  (case step
    :right (z/right zloc)
    :left (z/left zloc)
    :up (z/up zloc)
    :down (z/down zloc)))

(defn- def-initializer
  [by-location {:keys [zloc]}]
  (let [children (loop [child (z/down zloc) result []]
                   (if child
                     (recur (z/right child) (conj result child))
                     result))
        head (some-> children first z/sexpr)]
    (when (and (= 'def head) (seq (drop 2 children)))
      (get by-location (location-key (peek children))))))

(defn- bounded-items
  [items max-matches]
  (if max-matches (vec (take max-matches items)) (vec items)))

(defn- apply-query-step
  [{:keys [entries by-location line-roots]} items step max-matches]
  (unique-items
    (cond
      (navigation-steps step)
      (keep (fn [item]
              (some->> (navigate (:zloc item) step)
                       location-key
                       (get by-location)))
            items)

      (= :outermost step) (outermost-items items)
      (= :initializer step) (keep #(def-initializer by-location %) items)

      (= :form (first step))
      (let [[_ form-name platform] step]
        (filter #(and (= (str form-name) (defining-form-name (:zloc %)))
                      (or (nil? platform) (contains? (:platforms %) platform)))
                items))

      (= :line (first step))
      (let [line (second step)
            matches (->> line-roots
                         (filter (fn [item]
                                   (let [start (or (:comment-start item)
                                                   (:owner-line item))
                                         end (:owner-end-line item)]
                                     (and start end (<= start line end)))))
                         vec)]
        (cond
          (= 1 (count matches)) matches
          (empty? matches)
          (throw (ex-info "Line is not contained by a top-level form"
                          {:error-type :line-not-in-form
                           :line line :match-count 0}))
          :else
          (throw (ex-info "Line is contained by multiple top-level forms"
                          {:error-type :ambiguous-form
                           :line line
                           :match-count (count matches)
                           :matches-truncated? (and max-matches
                                                    (> (count matches) max-matches))
                           :matches (mapv (fn [item]
                                            {:line (:owner-line item)
                                             :end-line (:owner-end-line item)
                                             :platforms (vec (sort (:platforms item)))})
                                          (bounded-items matches max-matches))}))))

      (= :find (first step))
      (mapcat #(matching-descendants entries % (second step)) items)

      (= :where (first step))
      (filter #(where-match? (:zloc %) (second step)) items)

      (= :span (first step))
      (keep #(semantic-span {:by-location by-location} % (second step)) items)

      (= :partition-all (first step))
      (mapcat #(semantic-partitions {:by-location by-location} % (second step))
              items))))

(defn- query-match
  [top-levels {:keys [address addresses kind partition zloc zlocs]}]
  (let [{:keys [row]} (meta (z/node zloc))
        {end-row :end-row} (meta (z/node (or (last zlocs) zloc)))
        owner (enclosing-form-name top-levels zloc)]
    (cond-> {:path (cond-> (semantic-path zloc owner)
                     (= :span kind)
                     (conj (if partition
                             {:partition-all
                              {:size (:size partition)
                               :index (:index partition)
                               :count (count zlocs)}}
                             {:span {:count (count zlocs)}})))
             :tag (if (= :span kind) :span (z/tag zloc))
             :address (if (= :span kind)
                        {:preorders addresses}
                        {:preorder address})
             :line row
             :end-line end-row
             :source (if (= :span kind) (raw-span-source zlocs) (z/string zloc))}
      (= :span kind) (assoc :count (count zlocs)
                            :forms (mapv z/string zlocs)
                            :gaps (span-gaps zlocs))
      partition (assoc :partition partition)
      owner (assoc :inside owner))))

(defn- query-error-result
  [source query exception max-matches]
  (merge {:operation :lens
          :query query
          :error-type (or (:error-type (ex-data exception)) :invalid-source)
          :match-count 0
          :matches []
          :source-hash (workspace/content-revision source)}
         (select-keys (ex-data exception)
                      [:step-index :step :step-count :max-query-steps
                       :supported-query-steps :file :platform :line :match-count
                       :matches :matches-truncated?])))

(defn evaluate-query
  "Evaluate a read-only structural query against one source snapshot. Optional
   :file, :max-query-steps, and :max-matches supply context and bounds."
  ([source query] (evaluate-query source query nil))
  ([source query {:keys [file max-query-steps max-matches]}]
   (try
     (let [query (parse-query query max-query-steps)
           platform (when (and (vector? (first query))
                               (= :form (ffirst query)))
                      (nth (first query) 2 nil))
           file-platforms (platform-context-for-file file)]
       (when (and platform (nil? file-platforms))
         (throw (ex-info "Platform selection requires a Clojure file extension"
                         {:error-type :platform-context-required
                          :file file :platform platform})))
       (let [default-platforms (or file-platforms #{:clj :cljs})
             root (z/of-string source {:track-position? true})
             {:keys [initial top-levels] :as index}
             (source-index root default-platforms source)
             {:keys [items trace]}
             (reduce (fn [{:keys [items trace]} step]
                       (let [next-items (apply-query-step index items step max-matches)]
                         {:items next-items
                          :trace (conj trace {:step step
                                              :input-count (count items)
                                              :output-count (count next-items)})}))
                     {:items initial :trace []}
                     query)
             matches (mapv #(query-match top-levels %) items)
             match-count (count matches)]
         (cond-> {:operation :lens
                  :query query
                  :trace trace
                  :match-count match-count
                  :matches (bounded-items matches max-matches)
                  :source-hash (workspace/content-revision source)}
           (and max-matches (> match-count max-matches))
           (assoc :matches-truncated? true))))
     (catch Exception e
       (query-error-result source query e max-matches)))))

(defn find-subforms
  "Find structural matches in one source snapshot. The symbol `_` matches
   exactly one arbitrary subtree. Pure: source and selector in; EDN out."
  [source {:keys [inside match]}]
  (try
    (let [{pattern :sexpr match-source :source}
          (one-complete-form match :invalid-match "Match")
          root (z/of-string source {:track-position? true})
          top-levels (vec (top-level-locations root))
          owner-ranges (when inside (vec (inside-ranges root inside)))
          range (when (= 1 (count owner-ranges)) (first owner-ranges))]
      (cond
        (and inside (empty? owner-ranges))
        {:error-type :inside-not-found
         :inside (str inside) :match match-source :match-count 0 :matches []
         :file-hash (workspace/content-revision source)}

        (and inside (< 1 (count owner-ranges)))
        {:error-type :ambiguous-inside
         :inside (str inside) :match match-source
         :owner-match-count (count owner-ranges)
         :candidates (mapv owner-location owner-ranges)
         :match-count 0 :matches []
         :file-hash (workspace/content-revision source)}

        :else
        (let [matches (->> (zipper-locations root)
                           (map-indexed vector)
                           (keep (fn [[index candidate]]
                                   (when (and (or (nil? range)
                                                  (within-range? range candidate))
                                              (try
                                                (wildcard-match? pattern
                                                                 (z/sexpr candidate))
                                                (catch Exception _ false)))
                                     (let [{:keys [row end-row]}
                                           (meta (z/node candidate))
                                           owner (enclosing-form-name top-levels
                                                                      candidate)]
                                       (cond-> {:path (semantic-path candidate inside)
                                                :address {:preorder index}
                                                :line row :end-line end-row
                                                :source (z/string candidate)}
                                         owner (assoc :inside owner))))))
                           vec)]
          {:inside (when inside (str inside)) :match match-source
           :match-count (count matches) :matches matches
           :file-hash (workspace/content-revision source)})))
    (catch Exception e
      {:error-type (or (:error-type (ex-data e)) :invalid-source)
       :match-count 0 :matches []
       :file-hash (workspace/content-revision source)})))
