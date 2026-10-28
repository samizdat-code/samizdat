;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/fix_declares.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.fix-declares
  "Pure planning for removable declarations and their guarded form moves."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as node]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.analyze :as analyze]
            [samizdat.surgeon.forward-refs :as forward-refs]
            [samizdat.surgeon.move :as move]
            [samizdat.surgeon.outline :as outline]))

(defn- definition [forms name]
  (some #(when (and (= name (str (:name %))) (not= 'declare (:type %))) %) forms))

(defn- form-at-or-before [forms line]
  (->> forms
       (filter #(and (:name %) (not= 'declare (:type %))
                     (:line %) (<= (:line %) line)))
       (sort-by :line)
       last))

(defn- first-usage [references name]
  (some #(when (= name (str (:name %))) (:used-at %)) references))

(defn- declared-names [record]
  (when (= 'declare (:type record))
    (try
      (mapv str (rest (z/sexpr (z/of-string (:source record)))))
      (catch Exception _ []))))

(defn- declaration-actions [forms references dependencies cyclic]
  (let [by-name (into {} (map (juxt :name identity) dependencies))]
    (vec
     (for [record forms
           name (declared-names record)
           :when (not (contains? cyclic name))
           :let [target-line (first-usage references name)
                 target (when target-line (form-at-or-before forms target-line))
                 deps (get-in by-name [name :depends-on] #{})
                 unresolved (when target
                              (set (filter (fn [dependency]
                                             (when-let [entry (definition forms dependency)]
                                               (> (:line entry) (:line target))))
                                           deps)))
                 unresolved (not-empty unresolved)
                 leaves? (every? #(empty? (get-in by-name [% :depends-on] #{}))
                                 (or unresolved #{}))
                 def-form (definition forms name)]]
       (cond-> {:type :fix-declare
                :name name
                :declare-line (:line record)
                :defn-line (:line def-form)
                :defn-end-line (:end-line def-form)}
         target (assoc :move-before (str (:name target))
                       :move-before-line (:line target))
         target-line (assoc :first-usage target-line)
         (and unresolved leaves?) (assoc :pull-deps unresolved)
         (and unresolved (not leaves?))
         (assoc :unresolved-deps unresolved :warning :non-leaf-dependencies)
         (nil? def-form) (assoc :error :missing-definition)
         (nil? target-line) (assoc :stale? true :note :no-forward-reference))))))

(defn- line-offsets [source]
  (loop [offsets [0] index 0]
    (if-let [newline (str/index-of source "\n" index)]
      (recur (conj offsets (inc newline)) (inc newline))
      offsets)))

(defn- node-offsets [source form-node]
  (let [{:keys [row col end-row end-col]} (meta form-node)
        offsets (line-offsets source)]
    {:start (+ (nth offsets (dec row)) (dec col))
     :end (+ (nth offsets (dec end-row)) (dec end-col))}))

(defn- declaration-locations [source]
  (let [root (z/of-string source {:track-position? true})]
    (for [form (take-while some? (iterate z/right root))
          :when (and (z/list? form) (= "declare" (some-> form z/down z/string)))
          :let [children (rest (take-while some? (iterate z/right (z/down form))))
                names (filter #(= :token (node/tag (z/node %))) children)]
          child names]
      {:name (z/sexpr child)
       :form form
       :token child
       :count (count names)})))

(defn- remove-whole-declare [source form]
  (let [{:keys [start end]} (node-offsets source (z/node form))
        offsets (line-offsets source)
        row (:row (meta (z/node form)))
        line-start (nth offsets (dec row))
        start (if (str/blank? (subs source line-start start)) line-start start)
        next-newline (str/index-of source "\n" end)
        trailing (subs source end (or next-newline (count source)))
        end (if (and next-newline (str/blank? trailing)) (inc next-newline) end)]
    (str (subs source 0 start) (subs source end))))

(defn- remove-declare-name [source name]
  (if-let [{:keys [form token count]}
           (some #(when (= name (str (:name %))) %) (declaration-locations source))]
    (if (= 1 count)
      (remove-whole-declare source form)
      (let [{:keys [start end]} (node-offsets source (z/node token))
            start (loop [index start]
                    (if (and (pos? index)
                             (contains? #{\space \tab} (.charAt ^String source (dec index))))
                      (recur (dec index))
                      index))]
        (str (subs source 0 start) (subs source end))))
    source))

(defn- initial-summary [actions needed]
  {:removable (count actions)
   :needed (count needed)
   :safe (count (filter #(and (not (:unresolved-deps %))
                              (not (:pull-deps %))
                              (not (:stale? %))
                              (not (:error %))) actions))
   :safe-with-pull (count (filter :pull-deps actions))
   :unsafe (count (filter :unresolved-deps actions))
   :stale (count (filter :stale? actions))})

(defn- apply-action [state action]
  (if (or (:error action) (:unresolved-deps action))
    (update state :skipped inc)
    (let [moved (if (:stale? action)
                  {:future-source (:future-source state) :plan {:move-order []}}
                  (move/plan-form (:future-source state)
                                  {:form (:name action)
                                   :before (:move-before action)
                                   :with-deps? (boolean (:pull-deps action))}))]
      (if (:error moved)
        (-> state (update :skipped inc)
            (update :refusals conj (select-keys moved [:name :error-type :error])))
        (-> state
            (assoc :future-source (remove-declare-name (:future-source moved) (:name action)))
            (update :moves + (count (get-in moved [:plan :move-order])))
            (update :declares-deleted inc))))))

(defn plan-fix
  "Plan declare cleanup over source. Forward-reference evidence may be injected
   with {:forward-refs [...]}; the two-arity path derives it purely from source."
  ([source file]
   (plan-fix source file {}))
  ([source file options]
   (try
     (let [forms (outline/top-level-form-records file source)
           references (if (contains? options :forward-refs)
                        (or (:forward-refs options) [])
                        (forward-refs/from-source source))
           zloc (analyze/string->zloc source)
           dependencies (analyze/intra-ns-deps zloc)
           cyclic (set (:cycles (analyze/topological-sort zloc)))
           needed (->> forms
                       (mapcat declared-names)
                       (filter cyclic)
                       vec)
           actions (declaration-actions forms references dependencies cyclic)
           ordered (sort-by (fn [action] [(if (:stale? action) 0 1)
                                          (or (:defn-line action) 0)])
                            (fn [left right] (compare right left)) actions)
           completed (reduce apply-action
                             {:future-source source :moves 0 :declares-deleted 0
                              :skipped 0 :refusals []}
                             ordered)]
       {:file file
        :future-source (:future-source completed)
        :actions actions
        :needed-declares needed
        :skipped-unsafe (mapv #(select-keys % [:name :unresolved-deps])
                              (filter :unresolved-deps actions))
        :summary (merge (initial-summary actions needed)
                        (select-keys completed [:moves :declares-deleted :skipped :refusals]))})
     (catch Exception _
       {:error :invalid-source
        :error-type :invalid-source
        :file file}))))
