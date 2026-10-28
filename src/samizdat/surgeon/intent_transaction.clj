;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/intent_transaction.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.intent-transaction
  "Compile heterogeneous structural changes against frozen source snapshots."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.binding-rename :as binding-rename]
            [samizdat.surgeon.fix-declares :as declares]
            [samizdat.surgeon.move :as move]
            [samizdat.surgeon.operation-algebra :as algebra]
            [samizdat.surgeon.outline :as outline]))

(def ^:private supported-extensions [".clj" ".cljs" ".cljc" ".edn"])
(def ^:private spec-keys #{:changes :expect})
(def ^:private change-keys #{:id :in :forms :owner :find :inside :do :expect})
(def ^:private expectation-keys #{:matches :each-form :each-file})
(def ^:private aggregate-keys #{:changes :edits :files})

(defn- refuse! [error _ & [data]]
  (throw (ex-info (name error) (merge {:surgeon/error error} data))))

(defn- lossless-node-fingerprint [form-node]
  (when-not (node/whitespace? form-node)
    [(node/tag form-node)
     (if (node/inner? form-node)
       (vec (keep lossless-node-fingerprint (node/children form-node)))
       (node/string form-node))]))

(defn- duplicate-map-key [form-node]
  (when (= :map (node/tag form-node))
    (let [keys (->> (node/children form-node)
                    (remove node/whitespace-or-comment?)
                    (partition 2)
                    (map (comp node/sexpr first)))]
      (some (fn [[key n]] (when (> n 1) [key n])) (frequencies keys)))))

(defn- assert-distinct-map-keys! [form-node file {:keys [change-index id]}]
  (when-let [[key _] (duplicate-map-key form-node)]
    (refuse! :duplicate-map-key nil
             {:file file :change-index change-index :change-id id :key key})))

(defn- parse-one-form [source label]
  (when-not (string? source)
    (refuse! :invalid-intent-form nil {:field label :actual source}))
  (try
    (let [root (parser/parse-string-all source)
          forms (->> (node/children root) (remove node/whitespace?) vec)]
      (when-not (and (= 1 (count forms)) (not (node/comment? (first forms))))
        (refuse! :invalid-intent-form nil {:field label :form-count (count forms)}))
      (let [form-node (first forms)]
        {:node form-node :source (node/string form-node)
         :fingerprint (lossless-node-fingerprint form-node)}))
    (catch clojure.lang.ExceptionInfo error
      (if (:surgeon/error (ex-data error))
        (throw error)
        (refuse! :invalid-intent-form nil {:field label})))
    (catch Exception _
      (refuse! :invalid-intent-form nil {:field label}))))

(defn- validate-complete-source! [file source error]
  (when-not (string? source)
    (refuse! error nil {:file file}))
  (try (parser/parse-string-all source) source
       (catch Exception _
         (refuse! error nil {:file file}))))

(defn- zipper-locations [zloc]
  (take-while (complement z/end?) (iterate z/next zloc)))

(defn- node-size [form-node]
  (if (node/whitespace-or-comment? form-node)
    0
    (inc (if (node/inner? form-node)
           (reduce + 0 (map node-size (node/children form-node)))
           0))))

(defn- sibling-index [zloc]
  (loop [current zloc index 0]
    (if-let [left (z/left current)] (recur left (inc index)) index)))

(defn- location-path [zloc]
  (loop [current zloc path ()]
    (let [path (conj path (sibling-index current))]
      (if-let [parent (z/up current)] (recur parent path) (vec path)))))

(defn- position<=? [left-line left-character right-line right-character]
  (not (pos? (compare [left-line left-character] [right-line right-character]))))

(defn- contains-position? [form-node line character]
  (let [{:keys [row col end-row end-col]} (meta form-node)]
    (and row col end-row end-col
         (position<=? row col line character)
         (position<=? line character end-row end-col))))

(defn- addressed-form-at [source {:keys [line character]}]
  (let [root (z/of-string source {:track-position? true})]
    (some-> (->> (zipper-locations root)
                 (map-indexed vector)
                 (keep (fn [[preorder candidate]]
                         (let [candidate-node (z/node candidate)]
                           (when (and (node/inner? candidate-node)
                                      (not (node/whitespace-or-comment? candidate-node))
                                      (contains-position? candidate-node line character))
                             (let [{:keys [row end-row]} (meta candidate-node)
                                   size (node-size candidate-node)]
                               {:address {:preorder preorder}
                                :path (location-path candidate)
                                :end-preorder (+ preorder size -1)
                                :line row :end-line end-row
                                :before (z/string candidate)
                                :node-size size})))))
                 (sort-by :node-size)
                 first)
            (dissoc :node-size))))

(defn- supported-file? [file]
  (and (string? file) (some #(str/ends-with? file %) supported-extensions)))

(defn- normalized-path [file]
  (str (.normalize (java.nio.file.Paths/get file (make-array String 0)))))

(defn- validate-files! [files change-index]
  (when-not (and (vector? files) (seq files))
    (refuse! :invalid-files nil {:change-index change-index :files files}))
  (doseq [file files]
    (when-not (supported-file? file)
      (refuse! :unsupported-file nil {:change-index change-index :file file})))
  (let [normalized (mapv normalized-path files)]
    (when-not (= (count normalized) (count (distinct normalized)))
      (refuse! :duplicate-file nil {:change-index change-index :files files})))
  files)

(defn- validate-change-forms! [forms change-index]
  (when (some? forms)
    (when-not (and (vector? forms) (seq forms)
                   (every? #(or (symbol? %)
                                (and (map? %)
                                     (= #{:kind :name :dispatch} (set (keys %)))
                                     (= :defmethod (:kind %))
                                     (symbol? (:name %)) (string? (:dispatch %))))
                           forms)
                   (= (count forms) (count (distinct forms))))
      (refuse! :invalid-change-forms nil {:change-index change-index :forms forms})))
  (doseq [owner forms :when (map? owner)]
    (parse-one-form (:dispatch owner) ":forms dispatch"))
  forms)

(defn- positive-integer? [value] (and (integer? value) (pos? value)))

(defn- validate-expectation! [expect forms change-index change-id]
  (when-not (map? expect)
    (refuse! :invalid-change-expectation nil {:change-index change-index :change-id change-id}))
  (let [unknown (vec (sort (remove expectation-keys (keys expect))))]
    (when (seq unknown)
      (refuse! :unknown-change-expectation-arguments nil
               {:change-index change-index :change-id change-id :unknown unknown})))
  (when-not (positive-integer? (:matches expect))
    (refuse! :invalid-change-expectation nil
             {:change-index change-index :change-id change-id :actual (:matches expect)}))
  (doseq [field [:each-form :each-file]
          :let [value (get expect field)] :when (some? value)]
    (when-not (positive-integer? value)
      (refuse! :invalid-change-expectation nil
               {:change-index change-index :change-id change-id :field field :actual value})))
  (when (and (contains? expect :each-form) (nil? forms))
    (refuse! :invalid-change-expectation nil {:change-index change-index :change-id change-id}))
  expect)

(defn- validate-operator! [operator change-index change-id]
  (let [kind (first operator) value (second operator)]
    (cond
      (and (vector? operator) (= 2 (count operator)) (= :replace kind))
      {:kind :replace :form (parse-one-form value ":do replacement")}

      (and (vector? operator) (= [:delete true] operator)) {:kind :delete}

      (and (vector? operator) (= 2 (count operator))
           (#{:insert-before :insert-after :insert-left :insert-right} kind)
           (vector? value) (seq value))
      {:kind ({:insert-left :insert-before :insert-right :insert-after} kind kind)
       :forms (mapv #(parse-one-form % ":do insertion") value)}

      (and (vector? operator) (= 2 (count operator))
           (= :rename-binding kind) (map? value))
      (let [allowed #{:from :to :preserve-external-key}
            unknown (vec (sort (remove allowed (keys value))))
            from (:from value) to (:to value)]
        (when (seq unknown)
          (refuse! :invalid-binding-rename nil
                   {:change-index change-index :change-id change-id :unknown unknown}))
        (when-not (and (= allowed (set (keys value)))
                       (symbol? from) (nil? (namespace from))
                       (symbol? to) (nil? (namespace to)) (not= from to)
                       (= true (:preserve-external-key value)))
          (refuse! :invalid-binding-rename nil
                   {:change-index change-index :change-id change-id :rename value}))
        {:kind :rename-binding :rename value})

      (and (vector? operator) (= 2 (count operator))
           (= :assoc-entry kind) (map? value))
      (let [allowed #{:key :value} unknown (vec (sort (remove allowed (keys value))))]
        (when (or (seq unknown) (not= allowed (set (keys value))))
          (refuse! :invalid-assoc-entry nil
                   {:change-index change-index :change-id change-id :unknown unknown}))
        {:kind :assoc-entry
         :key (parse-one-form (:key value) ":do :assoc-entry :key")
         :value (parse-one-form (:value value) ":do :assoc-entry :value")})

      (and (vector? operator) (= 2 (count operator))
           (= :move kind) (map? value))
      (let [allowed #{:before :with-deps?}
            unknown (vec (sort (remove allowed (keys value))))]
        (when (or (seq unknown)
                  (not (symbol? (:before value)))
                  (namespace (:before value))
                  (and (contains? value :with-deps?)
                       (not (boolean? (:with-deps? value)))))
          (refuse! :invalid-move nil
                   {:change-index change-index :change-id change-id :move value :unknown unknown}))
        {:kind :move :move (update value :with-deps? boolean)})

      (and (vector? operator) (= [:fix-declares true] operator))
      {:kind :fix-declares}

      :else
      (refuse! :unsupported-change-operator nil
               {:change-index change-index :change-id change-id :operator operator}))))

(defn- validate-change! [change change-index]
  (when-not (map? change)
    (refuse! :invalid-changes nil {:change-index change-index :actual change}))
  (let [unknown (vec (sort (remove change-keys (keys change))))]
    (when (seq unknown)
      (refuse! :unknown-change-arguments nil {:change-index change-index :unknown unknown})))
  (let [change-id (:id change)]
    (when-not (keyword? change-id)
      (refuse! :invalid-change-id nil {:change-index change-index :actual change-id}))
    (let [files (validate-files! (:in change) change-index)
          forms (validate-change-forms! (:forms change) change-index)
          owner (:owner change)]
      (when (and forms owner)
        (refuse! :ambiguous-change-owner nil {:change-index change-index :change-id change-id}))
      (when owner
        (when-not (and (map? owner) (#{#{:kind} #{:kind :name}} (set (keys owner)))
                       (= :namespace (:kind owner))
                       (or (not (contains? owner :name)) (symbol? (:name owner))))
          (refuse! :invalid-change-owner nil
                   {:change-index change-index :change-id change-id :owner owner})))
      (let [operator (validate-operator! (:do change) change-index change-id)
            kind (:kind operator)
            rename? (= :rename-binding kind)
            delete? (= :delete kind)
            move? (= :move kind)
            fix-declares? (= :fix-declares kind)
            insertion? (#{:insert-before :insert-after} kind)
            from (when-not (or rename? delete? move? fix-declares?
                               (and insertion? (nil? (:find change))))
                   (parse-one-form (:find change) ":find"))
            inside (when-let [source (:inside change)]
                     (when-not (= :assoc-entry kind)
                       (refuse! :invalid-ancestor-selector nil
                                {:change-index change-index :change-id change-id}))
                     (parse-one-form source ":inside"))
            expectation (validate-expectation! (:expect change) forms change-index change-id)]
        (when (and insertion? (nil? (:find change)) (or owner (not= 1 (count forms))))
          (refuse! :invalid-top-level-insertion-owner nil
                   {:change-index change-index :change-id change-id :forms forms}))
        (when (and rename? (or (nil? forms) owner (some? (:find change))))
          (refuse! :invalid-binding-rename-owner nil
                   {:change-index change-index :change-id change-id}))
        (when (and delete? (or (nil? forms) owner (some? (:find change))))
          (refuse! :invalid-delete-owner nil {:change-index change-index :change-id change-id}))
        (when (and move? (or (not= 1 (count files)) (not= 1 (count forms))
                             owner (some? (:find change))))
          (refuse! :invalid-move-owner nil {:change-index change-index :change-id change-id}))
        (when (and fix-declares? (or (not= 1 (count files)) forms owner (some? (:find change))))
          (refuse! :invalid-fix-declares-owner nil
                   {:change-index change-index :change-id change-id}))
        (when (and (or move? fix-declares?)
                   (or (:each-form expectation) (:each-file expectation)))
          (refuse! :invalid-native-expectation nil
                   {:change-index change-index :change-id change-id}))
        (when (and (= :replace kind)
                   (= (:fingerprint from) (get-in operator [:form :fingerprint])))
          (refuse! :no-op-intent nil {:change-index change-index :change-id change-id}))
        (cond-> {:id change-id :change-index change-index :files files :forms forms
                 :operator kind :expect-count (:matches expectation)
                 :each-form (:each-form expectation) :each-file (:each-file expectation)}
          from (assoc :from from)
          inside (assoc :inside inside)
          owner (assoc :owner owner)
          rename? (assoc :rename (:rename operator))
          move? (assoc :move (:move operator))
          (= :assoc-entry kind) (assoc :assoc-key (:key operator) :assoc-value (:value operator))
          (= :replace kind) (assoc :to (:form operator))
          insertion? (assoc :insert-side kind :insert-sources (mapv :source (:forms operator)))
          (and insertion? (nil? (:find change))) (assoc :target-owner true))))))

(defn- validate-changes! [changes]
  (when-not (and (vector? changes) (seq changes))
    (refuse! :invalid-changes nil))
  (let [validated (mapv (fn [change index]
                          (try (validate-change! change index)
                               (catch clojure.lang.ExceptionInfo error
                                 (throw (ex-info (.getMessage error)
                                                 (merge {:change-index index :change-id (:id change)}
                                                        (ex-data error)) error)))))
                        changes (range))
        duplicate (first (for [[id n] (frequencies (keep :id validated)) :when (> n 1)] id))]
    (when duplicate (refuse! :duplicate-change-id nil {:change-id duplicate}))
    validated))

(defn- validate-aggregate! [expect]
  (when-not (map? expect)
    (refuse! :invalid-transaction-expectation nil))
  (let [unknown (vec (sort (remove aggregate-keys (keys expect))))
        missing (vec (sort (remove #(contains? expect %) aggregate-keys)))]
    (when (or (seq unknown) (seq missing))
      (refuse! :invalid-transaction-expectation nil {:unknown unknown :missing missing})))
  (doseq [[field value] expect]
    (when-not (positive-integer? value)
      (refuse! :invalid-transaction-expectation nil {:field field :actual value})))
  expect)

(defn- validate-spec! [spec]
  (when-not (map? spec)
    (refuse! :invalid-transaction-spec nil))
  (let [unknown (vec (sort (remove spec-keys (keys spec))))]
    (when (seq unknown)
      (refuse! :unknown-transaction-arguments nil {:unknown unknown})))
  {:changes (validate-changes! (:changes spec))
   :expect (validate-aggregate! (:expect spec))})

(defn- defmethod-dispatch [record]
  (when (= 'defmethod (:type record))
    (try (some-> (:source record) z/of-string z/down z/right z/right z/sexpr)
         (catch Exception _ (Object.)))))

(defn- scoped-owner-records! [source file {:keys [forms owner change-index id]}]
  (let [records (outline/top-level-form-records file source)]
    (cond
      forms
      (let [by-name (group-by :name records)]
        (mapv (fn [form-owner]
                (let [form-name (if (map? form-owner) (:name form-owner) form-owner)
                      dispatch (when (map? form-owner)
                                 (-> (parse-one-form (:dispatch form-owner) ":forms dispatch")
                                     :node node/sexpr))
                      matches (cond->> (get by-name form-name [])
                                (map? form-owner)
                                (filter #(and (= 'defmethod (:type %))
                                              (= dispatch (defmethod-dispatch %)))))
                      matches (mapv #(cond-> % (map? form-owner) (assoc :selector form-owner))
                                    matches)]
                  (when-not (= 1 (count matches))
                    (refuse! :change-owner-mismatch nil
                             {:change-index change-index :change-id id :file file
                              :owner form-owner :actual-count (count matches)}))
                  (first matches)))
              forms))

      owner
      (let [owner-name (:name owner)
            matches (->> records
                         (filter #(= 'ns (:type %)))
                         (keep (fn [record]
                                 (let [ns-name (some-> (:source record) z/of-string z/down z/right z/sexpr)]
                                   (when (or (nil? owner-name) (= owner-name ns-name))
                                     (assoc record :name ns-name)))))
                         vec)]
        (when-not (= 1 (count matches))
          (refuse! :change-owner-mismatch nil
                   {:change-index change-index :change-id id :file file
                    :owner owner :actual-count (count matches)}))
        matches)

      :else nil)))

(defn- owner-identity [record] (or (:selector record) (:name record)))

(defn- containing-owner [records candidate-node]
  (when records
    (let [{:keys [row end-row]} (meta candidate-node)]
      (some (fn [{:keys [line end-line] :as owner}]
              (when (and row end-row line end-line (<= line row) (<= end-row end-line)) owner))
            records))))

(defn- matching-edits [source file {:keys [change-index from to forms owner operator insert-side
                                           insert-sources assoc-key assoc-value inside id target-owner]
                                    :as change}]
  (let [root (z/of-string source {:track-position? true})
        all-owner-records (when target-owner (outline/top-level-form-records file source))
        owner-records (scoped-owner-records! source file change)
        scoped? (or forms owner)
        _ (when assoc-key (assert-distinct-map-keys! (:node from) file change))
        semantic-find (when assoc-key (node/sexpr (:node from)))
        semantic-inside (when inside (node/sexpr (:node inside)))]
    (if (or (= :delete operator) target-owner)
      (mapv (fn [record]
              (let [addressed (addressed-form-at source {:line (:line record) :character 1})]
                (when-not addressed
                  (refuse! :delete-owner-not-addressable nil {:file file :owner (:name record)}))
                (cond-> (assoc addressed :change-index change-index :change-id id
                               :owner (owner-identity record) :file file :operator operator)
                  target-owner
                  (assoc :target-owner true :insert-side insert-side :insert-sources insert-sources
                         :owner-context
                         (let [index (.indexOf all-owner-records record)
                               addressed-owner
                               (fn [neighbor]
                                 (when neighbor
                                   (assoc (addressed-form-at source {:line (:line neighbor) :character 1})
                                          :comment-start (:comment-start neighbor))))]
                           {:current-comment-start (:comment-start record)
                            :previous (addressed-owner (get all-owner-records (dec index)))
                            :next (addressed-owner (get all-owner-records (inc index)))})))))
            owner-records)
      (->> (zipper-locations root)
           (map-indexed vector)
           (keep (fn [[preorder candidate]]
                   (let [candidate-node (z/node candidate)
                         containing (containing-owner owner-records candidate-node)]
                     (when (and (if assoc-key
                                  (and (= :map (node/tag candidate-node))
                                       (= semantic-find (node/sexpr candidate-node)))
                                  (= (:fingerprint from) (lossless-node-fingerprint candidate-node)))
                                (or (not scoped?) containing)
                                (or (nil? semantic-inside)
                                    (some #(= semantic-inside (node/sexpr (z/node %)))
                                          (rest (take-while some? (iterate z/up candidate))))))
                       (let [{:keys [row end-row]} (meta candidate-node)
                             size (node-size candidate-node)
                             candidate-source (z/string candidate)
                             candidate-value (when assoc-key (node/sexpr candidate-node))
                             key-value (when assoc-key (node/sexpr (:node assoc-key)))
                             closing (when assoc-key (str/last-index-of candidate-source "}"))
                             _ (when assoc-key
                                 (assert-distinct-map-keys! candidate-node file change))]
                         (when (and assoc-key (contains? candidate-value key-value))
                           (refuse! :map-key-already-present nil
                                    {:file file :owner (:name containing) :key (:source assoc-key)}))
                         (cond-> {:change-index change-index :change-id id
                                  :owner (owner-identity containing) :file file
                                  :address {:preorder preorder} :path (location-path candidate)
                                  :end-preorder (+ preorder size -1)
                                  :line row :end-line end-row :before candidate-source
                                  :operator operator}
                           to (assoc :after (:source to))
                           assoc-key (assoc :after (str (subs candidate-source 0 closing)
                                                        " " (:source assoc-key) " "
                                                        (:source assoc-value)
                                                        (subs candidate-source closing)))
                           insert-side (assoc :insert-side insert-side
                                              :insert-sources insert-sources)))))))
           vec))))

(defn- binding-target-edit [source file change-index change-id {:keys [row col before after owner]}]
  (let [root (z/of-string source {:track-position? true})
        matches (->> (zipper-locations root)
                     (map-indexed vector)
                     (filter (fn [[_ location]]
                               (let [form-node (z/node location) m (meta form-node)]
                                 (and (= row (:row m)) (= col (:col m))
                                      (= before (z/string location))))))
                     vec)]
    (when-not (= 1 (count matches))
      (refuse! :binding-source-drift nil
               {:file file :owner owner :row row :col col :actual-count (count matches)}))
    (let [[preorder location] (first matches)
          form-node (z/node location) {:keys [end-row]} (meta form-node)
          size (node-size form-node)]
      {:change-index change-index :change-id change-id :owner owner :file file
       :address {:preorder preorder} :path (location-path location)
       :end-preorder (+ preorder size -1) :line row :end-line end-row
       :before before :after after :operator :replace})))

(defn- compile-binding-rename [sources options {:keys [files forms change-index id rename
                                                       expect-count each-form each-file]}]
  (when (and each-form (not= 1 each-form))
    (refuse! :change-distribution-mismatch nil
             {:change-index change-index :change-id id :distribution :each-form :expected each-form}))
  (let [compiled
        (mapv (fn [file]
                (let [source (get sources file)
                      owners (scoped-owner-records! source file {:forms forms :change-index change-index :id id})
                      analysis (get-in options [:binding-analysis file])]
                  (when-not (map? analysis)
                    (refuse! :binding-analysis-required nil
                             {:file file :change-index change-index :change-id id}))
                  (let [result (binding-rename/compile-targets
                                 {:file file :source source :owners owners :analysis analysis
                                  :from (:from rename) :to (:to rename)
                                  :preserve-external-key (:preserve-external-key rename)})]
                    {:file file :occurrence-count (:occurrence-count result)
                     :binding-count (:binding-count result) :per-form-counts (:per-form-counts result)
                     :edits (mapv #(binding-target-edit source file change-index id %) (:targets result))})))
              files)
        occurrence-count (reduce + (map :occurrence-count compiled))
        per-file-counts (into {} (map (juxt :file :occurrence-count) compiled))
        per-form-counts (into {} (map (juxt :file :per-form-counts) compiled))]
    (when-not (= expect-count occurrence-count)
      (refuse! :expect-count-mismatch nil
               {:change-index change-index :change-id id :expected-count expect-count
                :actual-count occurrence-count :per-file-counts per-file-counts
                :per-form-counts per-form-counts}))
    (when (and each-file (some #(not= each-file %) (vals per-file-counts)))
      (refuse! :change-distribution-mismatch nil
               {:change-index change-index :change-id id :distribution :each-file
                :expected each-file :actual per-file-counts}))
    {:id id :change-index change-index :files files :forms forms :operator :rename-binding
     :expected-count expect-count :match-count occurrence-count
     :per-file-counts per-file-counts :per-form-counts per-form-counts
     :binding-count (reduce + (map :binding-count compiled))
     :edits (vec (mapcat :edits compiled))}))

(defn- compile-change [sources options {:keys [kind] :as change}]
  (if (= :rename-binding (:operator change))
    (compile-binding-rename sources options change)
    (let [by-file (mapv (fn [file] [file (matching-edits (get sources file) file change)]) (:files change))
          edits (vec (mapcat second by-file))
          actual-count (count edits)
          per-file-counts (into {} (map (fn [[file matches]] [file (count matches)])) by-file)
          per-form-counts (when (:forms change)
                            (into {} (map (fn [[file matches]]
                                            [file (into {} (map (fn [form-name]
                                                                  [form-name
                                                                   (count (filter #(= form-name (:owner %)) matches))])
                                                                (:forms change)))])
                                          by-file)))]
      (when-not (= (:expect-count change) actual-count)
        (refuse! :expect-count-mismatch nil
                 {:change-index (:change-index change) :change-id (:id change)
                  :expected-count (:expect-count change) :actual-count actual-count
                  :per-file-counts per-file-counts :per-form-counts per-form-counts}))
      (when (and (:each-file change) (some #(not= (:each-file change) %) (vals per-file-counts)))
        (refuse! :change-distribution-mismatch nil
                 {:change-index (:change-index change) :change-id (:id change)
                  :distribution :each-file :expected (:each-file change) :actual per-file-counts}))
      (when (and (:each-form change)
                 (some #(not= (:each-form change) %) (mapcat vals (vals per-form-counts))))
        (refuse! :change-distribution-mismatch nil
                 {:change-index (:change-index change) :change-id (:id change)
                  :distribution :each-form :expected (:each-form change) :actual per-form-counts}))
      (cond-> {:id (:id change) :change-index (:change-index change) :files (:files change)
               :forms (:forms change) :operator (:operator change)
               :expected-count (:expect-count change) :match-count actual-count
               :per-file-counts per-file-counts :per-form-counts per-form-counts :edits edits}
        (:owner change) (assoc :owner (:owner change))))))

(defn- overlap? [left right]
  (letfn [(owner-boundary-insertion-disjoint? [insertion interior]
            (let [insertion-path (:path insertion) interior-path (:path interior)]
              (and (:insert-side insertion) (= 2 (count insertion-path))
                   (= (:owner insertion) (:owner interior))
                   (< (:address-preorder insertion) (:address-preorder interior))
                   (< (:end-preorder interior) (:end-preorder insertion))
                   (<= (count insertion-path) (count interior-path))
                   (= insertion-path (subvec interior-path 0 (count insertion-path))))))]
    (and (<= (:address-preorder right) (:end-preorder left))
         (not (or (owner-boundary-insertion-disjoint? left right)
                  (owner-boundary-insertion-disjoint? right left))))))

(defn- assert-disjoint-edits! [file edits]
  (let [ordered (->> edits (map #(assoc % :address-preorder (get-in % [:address :preorder])))
                     (sort-by :address-preorder) vec)]
    (doseq [[left right] (partition 2 1 ordered)]
      (when (overlap? left right)
        (refuse! :overlapping-intents nil
                 {:file file :change-indexes [(:change-index left) (:change-index right)]
                  :change-ids [(:change-id left) (:change-id right)]
                  :edits [(dissoc left :address-preorder) (dissoc right :address-preorder)]})))
    (mapv #(dissoc % :address-preorder) ordered)))

(defn- move-right [zloc n] (nth (iterate z/right zloc) n nil))

(defn- location-at-path [source path]
  (when (and (vector? path) (seq path) (every? nat-int? path))
    (let [first-form (z/of-string source {:track-position? true})
          forms-root (z/up first-form)
          [root-index & child-indexes] path]
      (when (and forms-root (zero? root-index))
        (reduce (fn [parent index] (some-> parent z/down (move-right index)))
                forms-root child-indexes)))))

(defn- line-offsets [source]
  (loop [offsets [0] index 0]
    (if-let [newline (str/index-of source "\n" index)]
      (recur (conj offsets (inc newline)) (inc newline)) offsets)))

(defn- addressed-target [source {:keys [address path before]}]
  (let [root (z/of-string source {:track-position? true})
        target (if (some? path) (location-at-path source path)
                   (nth (zipper-locations root) (:preorder address) nil))]
    (when-not target (refuse! :stale-path nil))
    (when-not (= before (z/string target)) (refuse! :stale-subform nil))
    target))

(defn- node-offsets [source target]
  (let [{:keys [row col end-row end-col]} (meta (z/node target))
        offsets (line-offsets source)]
    {:start (+ (nth offsets (dec row)) (dec col))
     :end (+ (nth offsets (dec end-row)) (dec end-col)) :row row}))

(defn- insertion-gap [source target side edit]
  (let [parent (z/up target) parent-tag (some-> parent z/tag) top-level? (= :forms parent-tag)]
    (when-not (#{:forms :list :vector :map :set} parent-tag)
      (refuse! :unsupported-insertion-parent nil
               {:change-id (:change-id edit) :file (:file edit) :parent-tag parent-tag}))
    (let [{target-start :start target-end :end} (node-offsets source target)
          {parent-start :start parent-end :end} (node-offsets source parent)
          parent-source (z/string parent)
          opening-boundary (if top-level? parent-start
                               (+ parent-start (if (str/starts-with? parent-source "#{") 2 1)))
          closing-boundary (if top-level? parent-end (dec parent-end))
          line-start (fn [line] (nth (line-offsets source) (dec line)))
          trailing-comment-end (fn [start limit]
                                 (let [comment (re-find #"^[ \t]*;[^\n\r]*" (subs source start limit))]
                                   (+ start (count (or comment "")))))
          accepted-gap (fn [gap]
                         (when-not (re-matches #"[\s,]*" gap)
                           (refuse! :ambiguous-insertion-gap nil
                                    {:change-id (:change-id edit) :file (:file edit)
                                     :target (:before edit) :gap gap}))
                         gap)
          anchor-line-start (inc (or (when (pos? target-start)
                                       (str/last-index-of source "\n" (dec target-start))) -1))
          anchor-prefix (subs source anchor-line-start target-start)
          anchor-indentation (if (re-matches #"[ \t]*" anchor-prefix) anchor-prefix "")]
      (if (and top-level? (:target-owner edit))
        (let [{:keys [current-comment-start previous next]} (:owner-context edit)
              previous-target (when previous (addressed-target source previous))
              next-target (when next (addressed-target source next))
              previous-end (if previous-target (:end (node-offsets source previous-target)) opening-boundary)
              next-start (if next-target (:start (node-offsets source next-target)) closing-boundary)
              current-owned-start (if current-comment-start (line-start current-comment-start) target-start)
              next-owned-start (if-let [comment-start (:comment-start next)]
                                 (line-start comment-start) next-start)
              [offset gap] (if (= :insert-before side)
                             (let [end (trailing-comment-end previous-end current-owned-start)]
                               [current-owned-start (accepted-gap (subs source end current-owned-start))])
                             (let [end (trailing-comment-end target-end next-owned-start)]
                               [end (accepted-gap (subs source end next-owned-start))]))]
          {:offset offset :separator (if (seq gap) gap " ")})
        (let [neighbor (if (= :insert-before side) (z/left target) (z/right target))
              neighbor-offsets (when neighbor (node-offsets source neighbor))
              opposite (if (= :insert-before side) (z/right target) (z/left target))
              opposite-offsets (when opposite (node-offsets source opposite))
              [gap-start gap-end] (if (= :insert-before side)
                                    [(or (:end neighbor-offsets) opening-boundary) target-start]
                                    [target-end (or (:start neighbor-offsets) closing-boundary)])
              gap (accepted-gap (subs source gap-start gap-end))
              opposite-gap (if (= :insert-before side)
                             (subs source target-end (or (:start opposite-offsets) closing-boundary))
                             (subs source (or (:end opposite-offsets) opening-boundary) target-start))]
          {:offset (if (= :insert-before side) target-start target-end)
           :separator (cond (seq gap) gap
                            (str/includes? opposite-gap "\n") (str "\n" anchor-indentation)
                            :else " ")})))))

(defn- deletion-offsets [source target]
  (when (= "ns" (some-> target z/down z/string))
    (refuse! :protected-namespace-form nil))
  (let [{target-start :start target-end :end row :row} (node-offsets source target)
        offsets (line-offsets source) lines (str/split source #"\n" -1)
        line-start (nth offsets (dec row))
        line-oriented? (str/blank? (subs source line-start target-start))
        first-comment-line (when line-oriented?
                             (loop [index (- row 2) first-index nil]
                               (if (and (>= index 0) (re-matches #"\s*;+.*" (nth lines index)))
                                 (recur (dec index) index) first-index)))
        raw-start (if line-oriented?
                    (nth offsets (or first-comment-line (dec row)))
                    (loop [at target-start]
                      (if (and (pos? at) (contains? #{\space \tab} (.charAt ^String source (dec at))))
                        (recur (dec at)) at)))
        delete-start (if (and (> raw-start 1)
                              (= \newline (.charAt ^String source (dec raw-start)))
                              (= \newline (.charAt ^String source (- raw-start 2))))
                       (dec raw-start) raw-start)
        line-end (or (str/index-of source "\n" target-end) (count source))
        trailing-source (subs source target-end line-end)
        delete-end (if line-oriented?
                     (let [content-end (if (re-matches #"\s*;+.*" trailing-source) line-end target-end)]
                       (if (and (< content-end (count source))
                                (= \newline (.charAt ^String source content-end)))
                         (inc content-end) content-end))
                     target-end)]
    {:start delete-start :end delete-end}))

(defn- prepare-raw-edit [source {:keys [operator after insert-side insert-sources] :as edit}]
  (let [target (addressed-target source edit)]
    (if insert-side
      (let [{:keys [offset separator]} (insertion-gap source target insert-side edit)
            inserted (str/join separator insert-sources)
            insertion (if (= :insert-before insert-side)
                        (str inserted separator) (str separator inserted))]
        (assoc edit :offset offset :before "" :after insertion))
      (let [{:keys [start end]} (if (= :delete operator)
                                  (deletion-offsets source target) (node-offsets source target))]
        (cond-> (assoc edit :offset start :before (subs source start end)
                       :after (if (= :delete operator) "" after))
          (= :assoc-entry operator) (assoc :operator :replace))))))

(defn- apply-raw-edit [source {:keys [offset before after]}]
  (let [end (+ offset (count before))]
    (when-not (and (nat-int? offset) (<= end (count source))
                   (= before (subs source offset end)))
      (refuse! :stale-raw-span nil))
    (str (subs source 0 offset) after (subs source end))))

(defn- apply-edits [source edits]
  (reduce apply-raw-edit source
          (sort-by (juxt :offset (comp count :before)) (fn [left right] (compare right left)) edits)))

(defn- compile-file [file source edits]
  (let [ordered (assert-disjoint-edits! file edits)
        prepared (mapv #(prepare-raw-edit source %) ordered)
        future (if (seq prepared) (apply-edits source prepared) source)]
    (validate-complete-source! file future :invalid-future-source)
    {:file file :source-hash (workspace/content-revision source)
     :result-hash (workspace/content-revision future)
     :edit-count (count prepared) :edits prepared :future-source future}))

(defn- native-change? [change]
  (contains? #{:move :fix-declares} (:operator change)))

(defn- compile-native-change [sources {:keys [operator files forms move id change-index expect-count]}]
  (let [file (first files)
        source (get sources file)
        result (case operator
                 :move (move/plan-form source {:file file :form (first forms)
                                               :before (:before move)
                                               :with-deps? (:with-deps? move)})
                 :fix-declares (declares/plan-fix source file))
        error-type (:error-type result)]
    (when error-type
      (refuse! error-type nil (assoc result :change-index change-index :change-id id)))
    (let [future (:future-source result)
          matches (case operator
                    :move 1
                    :fix-declares (get-in result [:summary :declares-deleted]))]
      (when-not (= expect-count matches)
        (refuse! :expect-count-mismatch nil
                 {:change-index change-index :change-id id :expected-count expect-count
                  :actual-count matches :file file}))
      (validate-complete-source! file future :invalid-future-source)
      (let [edit {:change-index change-index :change-id id :file file
                  :operator :replace :path [0] :address {:preorder 0} :end-preorder 0
                  :offset 0 :before source :after future :line 1}
            compiled {:id id :change-index change-index :files files :forms forms
                      :operator operator :expected-count expect-count :match-count matches
                      :plan (or (:plan result) (:summary result))}]
        {:compiled-change compiled
         :compiled-file {:file file :source-hash (workspace/content-revision source)
                         :result-hash (workspace/content-revision future)
                         :edit-count 1 :future-source future}
         :edit edit}))))

(defn- compile-native-transaction [sources changes expect]
  (when-not (= 1 (count changes))
    (refuse! :mixed-native-transaction nil
             {:change-ids (mapv :id changes) :operators (mapv :operator changes)}))
  (let [{:keys [compiled-change compiled-file edit]}
        (compile-native-change sources (first changes))
        file (:file compiled-file)
        actual {:changes 1 :edits 1 :files 1}]
    (when-not (= expect actual)
      (refuse! :transaction-expectation-mismatch nil {:expected expect :actual actual}))
    {:ok true :counts actual :changes [compiled-change]
     :files [(dissoc compiled-file :future-source)] :edits [edit]
     :original-sources {file (get sources file)}
     :future-sources {file (:future-source compiled-file)}
     :canonical-effects (algebra/canonical-effects {file (get sources file)}
                                                   {file (:future-source compiled-file)} [edit])
     :validated {:whole-files-parsed true :file-count 1}}))

(defn- compile-transaction [sources spec options]
  (let [{:keys [changes expect]} (validate-spec! spec)
        files (vec (distinct (mapcat :files changes)))]
    (doseq [file files] (validate-complete-source! file (get sources file) :invalid-source))
    (if (some native-change? changes)
      (compile-native-transaction sources changes expect)
      (let [compiled-changes (mapv #(compile-change sources options %) changes)
            edits-by-file (group-by :file (mapcat :edits compiled-changes))
            compiled-files (mapv #(compile-file % (get sources %) (get edits-by-file % [])) files)
            future-sources (into {} (map (juxt :file :future-source) compiled-files))
            edits (->> compiled-files (mapcat :edits)
                       (sort-by (juxt :file :offset :path)) vec)
            actual {:changes (count compiled-changes)
                    :edits (reduce + (map :match-count compiled-changes))
                    :files (count (filter (comp pos? :edit-count) compiled-files))}]
        (when-not (= expect actual)
          (refuse! :transaction-expectation-mismatch nil {:expected expect :actual actual}))
        {:ok true :counts actual
         :changes (mapv #(dissoc % :edits) compiled-changes)
         :files (mapv #(dissoc % :future-source :edits) compiled-files)
         :edits edits :original-sources (select-keys sources files)
         :future-sources future-sources
         :canonical-effects (algebra/canonical-effects (select-keys sources files) future-sources edits)
         :validated {:whole-files-parsed true :file-count (count compiled-files)}}))))

(defn compile-changes
  "Compile one exact change batch. No source, process, or publication effects."
  ([sources spec] (compile-changes sources spec {}))
  ([sources spec options]
   (try
     (when-not (map? sources) (refuse! :invalid-sources nil))
     (compile-transaction sources spec options)
     (catch clojure.lang.ExceptionInfo error
       (if (:surgeon/error (ex-data error))
         (throw error)
         (throw (ex-info "intent-compiler-failure"
                         {:surgeon/error :intent-compiler-failure :cause (.getMessage error)} error))))
     (catch Exception error
       (throw (ex-info "intent-compiler-failure"
                       {:surgeon/error :intent-compiler-failure :cause (.getMessage error)} error))))))
