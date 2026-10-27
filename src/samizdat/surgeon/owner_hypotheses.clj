;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/owner_hypotheses.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.owner-hypotheses
  "Owner-name hypotheses for selector refusals. Ranking is evidence only;
   it never selects an owner or creates executable authority."
  (:require [clojure.string :as str]))

(defn- normalized-name
  [value]
  (str/lower-case (str value)))

(defn- levenshtein-distance
  [left right]
  (loop [index 0
         left (seq left)
         previous (vec (range (inc (count right))))]
    (if-let [left-char (first left)]
      (recur (inc index)
             (next left)
             (reduce (fn [row [right-index right-char]]
                       (conj row
                             (min (inc (peek row))
                                  (inc (nth previous (inc right-index)))
                                  (+ (nth previous right-index)
                                     (if (= left-char right-char) 0 1)))))
                     [(inc index)]
                     (map-indexed vector right)))
      (peek previous))))

(defn normalized-levenshtein-score
  "Return normalized character similarity in [0, 1]."
  [requested candidate]
  (let [requested (normalized-name requested)
        candidate (normalized-name candidate)
        width (max (count requested) (count candidate))]
    (if (zero? width)
      1.0
      (- 1.0 (/ (double (levenshtein-distance requested candidate)) width)))))

(defn- candidate-evidence
  [{:keys [type platforms line end-line] :as record} rank]
  (cond-> {:owner (str (:name record))
           :rank rank
           :ranking-basis :normalized-levenshtein
           :authority false}
    type (assoc :type (str type))
    (seq platforms) (assoc :platforms (mapv name platforms))
    line (assoc :line line)
    end-line (assoc :end-line end-line)))

(defn- unique-records
  [records]
  (->> records
       (filter :name)
       (reduce (fn [{:keys [seen] :as result} record]
                 (let [owner (str (:name record))]
                   (if (contains? seen owner)
                     result
                     (-> result
                         (update :seen conj owner)
                         (update :records conj record)))))
               {:seen #{} :records []})
       :records))

(defn rank-owner-hypotheses
  "Rank real named owners for one missing owner. Resolved sibling requests
   are excluded; an optional platform restricts the candidate universe first."
  [requested records {:keys [platform resolved-names]}]
  (let [resolved (set (map str resolved-names))
        available (->> (unique-records records)
                       (filter #(or (nil? platform)
                                    (some #{platform} (:platforms %))))
                       vec)
        candidates (->> available
                        (remove #(contains? resolved (str (:name %))))
                        vec)
        ranked (->> candidates
                    (map #(assoc % ::score
                                 (normalized-levenshtein-score
                                   requested (:name %))))
                    (sort-by (juxt (comp - ::score)
                                   (comp str :name)
                                   (comp #(or % Long/MAX_VALUE) :line)))
                    vec)]
    {:requested-owner (str requested)
     :ranking-basis :normalized-levenshtein
     :authority false
     :available-owner-count (count available)
     :excluded-resolved-owner-count (- (count available) (count candidates))
     :candidate-count (count candidates)
     :candidates-returned (count ranked)
     :candidates-truncated false
     :did-you-mean (mapv (fn [rank candidate]
                           (candidate-evidence candidate rank))
                         (range 1 (inc (count ranked)))
                         ranked)}))

(defn- selection-failure
  [failure available-records resolved-names]
  (let [requested (str (:form failure))
        missing? (= :form-not-found (:error-type failure))
        ranking (when missing?
                  (rank-owner-hypotheses requested available-records
                                         {:resolved-names resolved-names}))
        hypotheses (or (:did-you-mean ranking) [])
        candidate-count (or (:candidate-count ranking) 0)
        returned (count hypotheses)]
    (cond-> {:requested-owner requested
             :failure-kind (:error-type failure)
             :match-count (:match-count failure)
             :hypotheses hypotheses
             :hypotheses-returned returned
             :hypotheses-omitted (- candidate-count returned)
             :hypotheses-truncated false}
      (:matches failure) (assoc :matches (:matches failure)))))

(defn owner-recovery-evidence
  "Compile source-free recovery evidence for one exact-owner refusal."
  [requested records failures]
  (let [available-records (unique-records records)
        available-names (mapv (comp str :name) available-records)
        failed-names (set (map (comp str :form) failures))
        resolved-names (remove failed-names (map str requested))
        selection-failures (mapv #(selection-failure % available-records
                                                     resolved-names)
                                 failures)
        first-ranked (some #(when (seq (:hypotheses %)) %) selection-failures)
        candidates (mapv :owner (:hypotheses first-ranked))]
    {:available-form-count (count available-names)
     :available-owner-count (count available-names)
     :available-owners available-names
     :available-owners-returned (count available-names)
     :available-owners-omitted 0
     :available-owners-truncated false
     :selection-failures selection-failures
     :form-candidates candidates
     :candidates-truncated false}))
