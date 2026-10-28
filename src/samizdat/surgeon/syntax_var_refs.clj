;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/syntax_var_refs.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.syntax-var-refs
  "Exact qualified-syntax evidence; bare symbols remain unresolved proof gaps."
  (:require [clojure.string :as str]
            [rewrite-clj.zip :as z]
            [samizdat.agent.gates :as gates]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.analyze :as analyze]))

(defn- default-max-candidate-files []
  (:max-candidate-files (gates/threshold :clj-surgeon)))
(def ^:private definition-heads '#{def defonce defn defn- defmacro defmulti})

(defn- top-level-locations [root] (take-while some? (iterate z/right root)))

(defn- namespace-context [source]
  (let [root (z/of-string source {:track-position? true})
        ns-location (->> (top-level-locations root)
                         (filter #(and (z/list? %) (= 'ns (some-> % z/sexpr first)))) first)
        ns-form (some-> ns-location z/sexpr)]
    {:root root :namespace (some-> ns-form second str) :namespace-form ns-form
     :aliases (or (some-> ns-location analyze/parse-ns-aliases) {})}))

(defn- inert-ancestor? [location]
  (->> (iterate z/up (z/up location)) (take-while some?)
       (some (fn [ancestor]
               (let [form (try (z/sexpr ancestor) (catch Exception _ nil))]
                 (or (contains? #{:uneval :quote :syntax-quote} (z/tag ancestor))
                     (and (seq? form) (contains? #{'quote 'clojure.core/quote
                                                   'comment 'clojure.core/comment} (first form)))))))))

(defn- namespace-ancestor? [location]
  (->> (iterate z/up (z/up location)) (take-while some?)
       (some (fn [ancestor]
               (let [form (try (z/sexpr ancestor) (catch Exception _ nil))]
                 (and (seq? form) (= 'ns (first form))))))))

(defn- definition-name? [location target-name]
  (let [parent (z/up location) form (try (z/sexpr parent) (catch Exception _ nil))]
    (and (seq? form) (contains? definition-heads (first form))
         (= (symbol target-name) (second form)))))

(defn- require-refers-target? [namespace-form target-namespace target-name]
  (some (fn [clause]
          (when (and (seq? clause) (= :require (first clause)))
            (some (fn [spec]
                    (when (and (vector? spec) (= target-namespace (str (first spec))))
                      (some (fn [[option value]]
                              (and (= :refer option)
                                   (or (= :all value)
                                       (and (sequential? value) (some #{(symbol target-name)} value)))))
                            (partition-all 2 (rest spec))))) (rest clause))))
        (drop 2 namespace-form)))

(defn- uses-target-namespace? [namespace-form target-namespace]
  (some (fn [clause]
          (when (and (seq? clause) (= :use (first clause)))
            (some (fn [spec] (= target-namespace (str (if (vector? spec) (first spec) spec))))
                  (rest clause)))) (drop 2 namespace-form)))

(defn- bare-target-visible? [current-namespace namespace-form target-namespace target-name]
  (or (= current-namespace target-namespace)
      (require-refers-target? namespace-form target-namespace target-name)
      (uses-target-namespace? namespace-form target-namespace)))

(defn- subject-parts [subject]
  (when (string? subject)
    (when-let [[_ target-namespace target-name] (re-matches #"^([^/\s]+)/([^/\s]+)$" subject)]
      [target-namespace target-name])))

(defn- accepted-symbols [target-namespace target-name aliases]
  (into {(symbol target-namespace target-name) :fully-qualified}
        (for [[alias namespace] aliases :when (= target-namespace (str namespace))]
          [(symbol (str alias) target-name) :alias-qualified])))

(defn- location-evidence [file source-sha accepted location]
  (let [form (try (z/sexpr location) (catch Exception _ nil))
        relation (and (symbol? form) (get accepted form))
        node-meta (meta (z/node location))]
    (when (and relation (not (inert-ancestor? location)))
      {:file file :line (:row node-meta) :character (:col node-meta)
       :range {:start {:line (dec (:row node-meta)) :character (dec (:col node-meta))}
               :end {:line (dec (:end-row node-meta)) :character (dec (:end-col node-meta))}}
       :source (z/string location) :source_sha256 source-sha :role :reference
       :relation relation :authority true :reference-authority :exact-qualified-syntax})))

(defn- proof-gap-evidence [file source-sha target-name bare-visible? location]
  (let [form (try (z/sexpr location) (catch Exception _ nil)) node-meta (meta (z/node location))]
    (when (and bare-visible? (symbol? form) (nil? (namespace form)) (= target-name (name form))
               (not (inert-ancestor? location)) (not (namespace-ancestor? location))
               (not (definition-name? location target-name)))
      {:file file :line (:row node-meta) :character (:col node-meta)
       :range {:start {:line (dec (:row node-meta)) :character (dec (:col node-meta))}
               :end {:line (dec (:end-row node-meta)) :character (dec (:end-col node-meta))}}
       :source (z/string location) :source_sha256 source-sha :role :reference-candidate
       :reason :bare-symbol-needs-resolution :authority false})))

(defn- reference-evidence-in-source [relative-file source target-namespace target-name]
  (let [{:keys [root aliases namespace namespace-form]} (namespace-context source)
        accepted (accepted-symbols target-namespace target-name aliases)
        bare-visible? (bare-target-visible? namespace namespace-form target-namespace target-name)
        source-sha (workspace/content-revision source)]
    (loop [location root locations [] proof-gaps []]
      (if (z/end? location)
        {:locations locations :proof-gaps proof-gaps}
        (recur (z/next location)
               (if-let [evidence (location-evidence relative-file source-sha accepted location)]
                 (conj locations evidence) locations)
               (if-let [gap (proof-gap-evidence relative-file source-sha target-name bare-visible? location)]
                 (conj proof-gaps gap) proof-gaps))))))

(defn references-in-source [relative-file source subject]
  (if-let [[target-namespace target-name] (subject-parts subject)]
    (:locations (reference-evidence-in-source relative-file source target-namespace target-name))
    []))

(defn scan-sources
  ([sources subject] (scan-sources sources subject {}))
  ([sources subject {:keys [max-candidate-files]
                     :or {max-candidate-files (default-max-candidate-files)}}]
   (if-let [[target-namespace target-name] (subject-parts subject)]
     (try
       (let [candidates (->> sources (sort-by key)
                             (filterv (fn [[_ source]] (str/includes? source target-name))))]
         (if (> (count candidates) max-candidate-files)
           {:ok false :error-type :syntax-var-scan-budget-exceeded
            :candidate-file-count (count candidates) :limit max-candidate-files :source-unchanged true}
           (let [evidence (mapv (fn [[file source]]
                                  (reference-evidence-in-source (str file) source target-namespace target-name))
                                candidates)
                 locations (vec (mapcat :locations evidence))
                 proof-gaps (vec (mapcat :proof-gaps evidence))]
             {:ok true :subject subject :locations locations :proof-gaps proof-gaps
              :scanned-file-count (count sources) :candidate-file-count (count candidates)
              :candidate-files (mapv (comp str first) candidates)
              :reference-count (count locations) :proof-gap-count (count proof-gaps)})))
       (catch Exception error
         {:ok false :error-type :syntax-var-scan-failed
          :error (.getMessage error) :source-unchanged true}))
     {:ok false :error-type :invalid-syntax-var-subject :subject subject :source-unchanged true})))
