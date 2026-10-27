;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.diagnosis
  "Symbolic action contracts and bounded evidence-driven diagnosis."
  (:require [samizdat.symbolic :as symbolic]))

(defn execute [{:keys [requirements effects observations expect]} args run]
  (let [requirements (symbolic/ruleset requirements)
        effects (symbolic/ruleset effects)
        observations (symbolic/ruleset observations)]
    (if-not (every? #(symbolic/match % args) requirements)
      {:status :refused}
      (try
        (let [result (run args)
              missing-effects (remove #(symbolic/match % result) effects)]
          (if (seq missing-effects)
            {:status :missing-effect :missing missing-effects :result result}
            (let [hits (vec (for [item (if (sequential? result) result [result])
                                  observation observations
                                  :let [hit (symbolic/first-match [observation] item)]
                                  :when hit]
                              hit))
                  missing (remove (set (map :rule hits)) expect)]
              (if (seq missing)
                {:status :missing-observation :missing missing :result result}
                {:status :complete :result result :facts (mapv :result hits)}))))
        (catch Throwable e
          {:status :external-error :error (ex-message e)})))))

(defn- accepted? [fact]
  (and (:accepted? fact) (not (:disputed? fact))))

(defn evaluate [hypotheses known]
  (into {}
        (for [{:keys [id predicted]} hypotheses]
          [id
           (if (some (fn [[fact expected]]
                       (let [actual (get known fact)]
                         (and (accepted? actual)
                              (not= expected (:value actual)))))
                     predicted)
             :ruled-out
             :open)])))

(defn- probe-information [probe open known]
  (let [values (distinct (keep (fn [{:keys [predicted]}]
                                 (when (contains? predicted probe)
                                   (get predicted probe)))
                               open))
        unresolved (count (filter (fn [{:keys [predicted]}]
                                    (and (contains? predicted probe)
                                         (not (accepted? (get known probe)))))
                                  open))]
    (if (> (count values) 1) unresolved 0)))

(defn- informative-choice [probes open known attempted]
  (first
   (sort-by (fn [[_ candidate]] [(- (:score candidate)) (:cost candidate)])
            (keep (fn [[probe {:keys [cost] :as candidate}]]
                    (let [information (probe-information (or (:fact candidate) probe) open known)]
                      (when (and (not (contains? attempted probe)) (pos? information))
                        [probe (assoc candidate :information information
                                      :score (/ information (double cost)))])))
                  probes))))

(defn investigate [{:keys [hypotheses known probes budget]}]
  (loop [known (or known {})
         attempted #{}
         spent 0
         trace []]
    (let [states (evaluate hypotheses known)
          open (vec (filter #(= :open (get states (:id %))) hypotheses))]
      (cond
        (<= (count open) 1)
        {:status :resolved :open open :known known :trace trace}

        (>= spent (long (or budget 0)))
        {:status :open :open open :known known :trace trace}

        :else
        (let [choice (informative-choice probes open known attempted)]
          (if-not choice
            {:status :open :open open :known known :trace trace}
            (let [[probe {:keys [cost run]}] choice]
              (if (> (+ spent (long cost)) budget)
                {:status :open :open open :known known :trace trace}
                (let [outcome (try
                                (run known)
                                (catch Throwable e
                                  {:accepted? false :disputed? true :error (ex-message e)}))
                      known (if (:fact outcome)
                              (assoc known (:fact outcome) (dissoc outcome :fact))
                              known)]
                  (recur known (conj attempted probe) (+ spent cost)
                         (conj trace (assoc outcome :probe probe :cost cost))))))))))))
