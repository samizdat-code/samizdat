;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/quoted_var_refs.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.quoted-var-refs
  "Lossless structural evidence for Var-quoted references, not semantic proof."
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [samizdat.agent.gates :as gates]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.analyze :as analyze]))

(defn- default-max-candidate-files []
  (:max-candidate-files (gates/threshold :clj-surgeon)))

(defn- top-level-locations [root]
  (take-while some? (iterate z/right root)))

(defn- namespace-context [source]
  (let [root (z/of-string source {:track-position? true})
        ns-zloc (->> (top-level-locations root)
                     (filter #(and (z/list? %) (= 'ns (some-> % z/sexpr first)))) first)
        ns-name (some-> ns-zloc z/sexpr second str)]
    {:root root :namespace ns-name
     :aliases (or (some-> ns-zloc analyze/parse-ns-aliases) {})}))

(defn- inert-ancestor? [location]
  (->> (iterate z/up (z/up location)) (take-while some?)
       (some (fn [ancestor]
               (let [form (try (z/sexpr ancestor) (catch Exception _ nil))]
                 (or (= :uneval (z/tag ancestor))
                     (and (seq? form) (contains? #{'quote 'clojure.core/quote} (first form)))))))))

(defn- target-symbols [target-namespace target-name current-namespace aliases]
  (into #{(symbol target-namespace target-name)}
        (concat (when (= target-namespace current-namespace) [(symbol target-name)])
                (for [[alias namespace] aliases :when (= target-namespace (str namespace))]
                  (symbol (str alias) target-name)))))

(defn- var-reference-symbol [location]
  (let [form (try (z/sexpr location) (catch Exception _ nil))]
    (when (and (seq? form) (= 2 (count form)) (= 'var (first form))
               (symbol? (second form)) (not (inert-ancestor? location)))
      (second form))))

(defn references-in-source-for-subjects [relative-file source subjects]
  (let [{:keys [root namespace aliases]} (namespace-context source)
        distinct-subjects (vec (distinct subjects))
        subjects-by-symbol
        (reduce (fn [result subject]
                  (let [[target-namespace target-name] (str/split subject #"/" 2)]
                    (reduce (fn [index accepted] (update index accepted (fnil conj []) subject))
                            result
                            (target-symbols target-namespace target-name namespace aliases))))
                {} distinct-subjects)
        references-by-subject
        (loop [location root references (zipmap distinct-subjects (repeat []))]
          (if (z/end? location)
            references
            (let [reference (var-reference-symbol location)
                  matching-subjects (get subjects-by-symbol reference)
                  node-meta (meta (z/node location))
                  evidence (when (seq matching-subjects)
                             {:file relative-file :line (:row node-meta) :character (:col node-meta)
                              :range {:start {:line (dec (:row node-meta)) :character (dec (:col node-meta))}
                                      :end {:line (dec (:end-row node-meta)) :character (dec (:end-col node-meta))}}
                              :source (z/string location) :reference-authority :structural-var-quote})]
              (recur (z/next location)
                     (reduce (fn [result subject] (update result subject conj evidence))
                             references matching-subjects)))))]
    (mapv (fn [[subject reference]] (assoc reference :subject subject))
          (for [subject subjects reference (get references-by-subject subject)] [subject reference]))))

(defn references-in-source [relative-file source subject]
  (mapv #(dissoc % :subject) (references-in-source-for-subjects relative-file source [subject])))

(defn- valid-subject? [subject]
  (and (string? subject) (boolean (re-matches #"[^/]+/[^/]+" subject))))

(defn- invalid-subjects [subjects]
  (if (sequential? subjects)
    (->> subjects
         (map-indexed (fn [index subject] (when-not (valid-subject? subject) {:index index :subject subject})))
         (keep identity) vec)
    [{:index nil :subject subjects}]))

(defn scan-sources
  "Scan one frozen file/source map for structural Var-quote evidence."
  ([sources subjects] (scan-sources sources subjects {}))
  ([sources subjects {:keys [file-paths max-candidate-files]
                      :or {file-paths {} max-candidate-files (default-max-candidate-files)}}]
   (let [invalid (invalid-subjects subjects)]
     (if (seq invalid)
       {:ok false :error-type :invalid-quoted-var-subjects
        :invalid-subjects invalid :source-unchanged true}
       (try
         (let [names (set (map #(second (str/split % #"/" 2)) subjects))
               candidates (->> sources (sort-by key)
                               (keep (fn [[file source]]
                                       (when (some #(str/includes? source %) names)
                                         {:file (str file) :file-path (str (get file-paths file file))
                                          :source source}))) vec)]
           (if (> (count candidates) max-candidate-files)
             {:ok false :error-type :quoted-var-scan-budget-exceeded
              :candidate-file-count (count candidates) :limit max-candidate-files :source-unchanged true}
             (let [locations (mapcat (fn [{:keys [file file-path source]}]
                                       (for [reference (references-in-source-for-subjects file source subjects)]
                                         (merge reference {:file_path file-path
                                                           :source_sha256 (workspace/content-revision source)
                                                           :role :reference}))) candidates)]
               {:ok true :locations (vec locations)
                :sources (into {} (map (juxt :file-path :source) candidates))
                :scanned-file-count (count sources) :candidate-file-count (count candidates)
                :reference-count (count locations)})))
         (catch Exception error
           {:ok false :error-type :quoted-var-scan-failed
            :error (.getMessage error) :source-unchanged true}))))))
