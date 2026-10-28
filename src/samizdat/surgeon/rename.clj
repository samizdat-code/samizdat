;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/rename.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.rename
  "Pure structural namespace-prefix rename plans over frozen sources."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn- rename-symbol-str [s from-prefix to-prefix]
  (if (or (= s from-prefix) (str/starts-with? s (str from-prefix ".")))
    (str to-prefix (subs s (count from-prefix))) s))

(defn- ns-prefix->dir [prefix]
  (-> prefix (str/replace "-" "_") (str/replace "." "/")))

(defn- find-ns-form [zloc]
  (loop [z zloc]
    (when z
      (if (and (z/list? z) (= "ns" (some-> z z/down z/string))) z (recur (z/right z))))))

(defn rename-source [source from-prefix to-prefix]
  (let [zloc (z/of-string source {:track-position? true}) ns-zloc (find-ns-form zloc)]
    (if (nil? ns-zloc)
      source
      (let [renamed (loop [z ns-zloc]
                      (let [z' (z/next z)]
                        (if (z/end? z')
                          z'
                          (if (and (= :token (n/tag (z/node z')))
                                   (let [s (z/string z')]
                                     (and (not (str/starts-with? s ":"))
                                          (not (str/starts-with? s "\""))
                                          (or (= s from-prefix)
                                              (str/starts-with? s (str from-prefix "."))))))
                            (recur (z/replace z' (n/token-node
                                                  (symbol (rename-symbol-str (z/string z') from-prefix to-prefix)))))
                            (recur z')))))]
        (z/root-string renamed)))))

(defn target-path [file from to]
  (let [from-dir (ns-prefix->dir (str from)) to-dir (ns-prefix->dir (str to))
        pattern (re-pattern (str "(^|/)" (java.util.regex.Pattern/quote from-dir) "(/|$)"))]
    (str/replace file pattern (str "$1" to-dir "$2"))))

(defn- file-move? [file from to]
  (not= file (target-path file from to)))

(defn- analyze-source [file source from to]
  (let [from (str from) to (str to)
        zloc (z/of-string source {:track-position? true})
        ns-zloc (find-ns-form zloc)
        ns-name (some-> ns-zloc z/down z/right z/string)
        ns-rename (when (and ns-name (or (= ns-name from) (str/starts-with? ns-name (str from "."))))
                    {:old ns-name :new (rename-symbol-str ns-name from to)})
        require-renames (when ns-zloc
                          (let [children (loop [z (z/down ns-zloc) acc []]
                                           (if (nil? z) acc (recur (z/right z) (conj acc z))))]
                            (->> children
                                 (filter #(and (z/list? %) (= ":require" (some-> % z/down z/string))))
                                 (mapcat (fn [req-form]
                                           (loop [z (some-> req-form z/down z/right) acc []]
                                             (if (nil? z) acc
                                                 (let [entry (when (z/vector? z)
                                                               (let [first-sym (some-> z z/down z/string)]
                                                                 (when (and first-sym
                                                                            (or (= first-sym from)
                                                                                (str/starts-with? first-sym (str from "."))))
                                                                   {:old first-sym :new (rename-symbol-str first-sym from to)})))]
                                                   (recur (z/right z) (if entry (conj acc entry) acc)))))))
                                 vec not-empty)))]
    (when (or ns-rename require-renames)
      {:file file :ns-rename ns-rename :require-renames (or require-renames [])})))

(defn- caller-proof [future-sources from decisions]
  (let [candidates (->> future-sources
                        (keep (fn [[file source]] (when (str/includes? source (str from)) file)))
                        sort vec)
        required (set candidates) supplied (set (keys decisions))
        missing (vec (sort (set/difference required supplied)))
        extra (vec (sort (set/difference supplied required)))
        invalid (vec (sort (remove #{:review-required :not-a-caller} (vals decisions))))]
    {:status :structural-candidates :semantic-complete false
     :candidates candidates :unknown-callers missing :dispositions (select-keys decisions candidates)
     :dispositions-complete (and (empty? missing) (empty? extra) (empty? invalid))
     :extra-dispositions extra :invalid-dispositions invalid}))

(defn compile-plan [{:keys [sources from to caller-decisions] :or {caller-decisions {}}}]
  (let [present-sources (into {} (filter (comp string? val) sources))
        analyses (keep (fn [[file source]] (analyze-source file source (str from) (str to))) present-sources)
        moved (for [[file _] present-sources :when (file-move? file from to)] [file (target-path file from to)])
        moves (vec (sort-by first moved))
        collisions (filter (fn [[_ target]] (some? (get sources target))) moves)
        duplicate-targets (->> moves (map second) frequencies (keep (fn [[file n]] (when (> n 1) file))) vec)]
    (cond
      (seq collisions)
      {:error-type :rename-target-exists
       :targets (mapv second collisions)}
      (seq duplicate-targets)
      {:error-type :duplicate-rename-target
       :targets duplicate-targets}
      :else
      (let [destination-for (into {} moves)
            final-sources (into {}
                                (map (fn [[file source]]
                                       (let [target (get destination-for file file)
                                             future (rename-source source (str from) (str to))]
                                         [target future])))
                                present-sources)
            affected (into {}
                           (filter (fn [[file source]]
                                     (let [target (get destination-for file file)]
                                       (or (not= file target) (not= source (get final-sources target))))))
                           present-sources)
            final-affected (select-keys final-sources (map #(get destination-for % %) (keys affected)))
            proof (caller-proof final-sources from caller-decisions)]
        (if-not (:dispositions-complete proof)
          {:error-type :caller-disposition-required :caller-proof proof}
          (let [renamed-files (filterv (fn [[file _]] (contains? affected file)) moves)
                renamed-set (set (map first renamed-files))
                writes (->> affected (remove (comp renamed-set key)) sort
                            (mapv (fn [[file _]] {:operation :write :file file})))
                rename-effects (mapv (fn [[file target]] {:operation :rename :file file :target target}) renamed-files)]
            {:from (str from) :to (str to)
             :file-analyses (vec analyses)
             :file-moves (mapv (fn [[file target]] {:from file :to target}) moves)
             :future-sources final-affected
             :created-files (mapv second renamed-files)
             :deleted-files (mapv first renamed-files)
             :renamed-files (mapv (fn [[file target]] {:from file :to target}) renamed-files)
             :resource-effects (vec (concat rename-effects writes))
             :caller-proof proof}))))))

(defn plan [opts] (compile-plan opts))
