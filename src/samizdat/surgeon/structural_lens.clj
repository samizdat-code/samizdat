;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/structural_lens.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.structural-lens
  "Exact structural search below a named top-level form."
  (:require [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.forms :as forms]
            [rewrite-clj.zip :as z]))

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
