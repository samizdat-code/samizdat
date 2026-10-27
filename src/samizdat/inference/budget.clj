;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.budget
  "Shared call and token counters with frame-local recursion depth.")

(defn budget
  "A budget tree with remaining calls, tokens, depth, and output tranche cap."
  [{:keys [calls tokens depth max-tranche]}]
  (atom {:calls calls :tokens tokens :depth depth :max-tranche max-tranche
         :cancelled? false :reservations {}}))

(defn- root [budget]
  (or (:parent @budget) budget))

(defn child
  "A frame with local depth and shared call/token counters."
  [budget]
  (atom {:parent (root budget) :depth (dec (:depth @budget)) :reservations {}}))

(defn remaining [budget]
  (let [root (root budget)]
    (assoc (select-keys @root [:calls :tokens :cancelled?])
           :depth (:depth @budget))))

(defn reserve!
  "Reserve one call and a bounded output tranche atomically, or nil."
  [budget {:keys [output-tokens]}]
  (let [root (root budget)
        id (random-uuid)]
    (locking root
      (let [{:keys [calls tokens cancelled? max-tranche] :as state} @root
            tranche (min (long (or output-tokens 0))
                         (long (quot tokens 4))
                         (long (or max-tranche tokens)))]
        (when (and (not cancelled?)
                   (pos? calls) (pos? tokens) (pos? (:depth @budget))
                   (pos? tranche))
          (swap! root
                 (fn [state]
                   (-> state
                       (update :calls dec)
                       (update :tokens - tranche)
                       (assoc-in [:reservations id] tranche))))
          {:id id :output-tokens tranche})))))

(defn settle!
  "Settle actual usage. Cached input is free; unpriced replies pay the tranche."
  [budget {:keys [id]} {:keys [usage priced?]}]
  (let [root (root budget)]
    (locking root
      (swap! root
             (fn [state]
               (if-let [tranche (get-in state [:reservations id])]
                 (let [completion (if priced? (long (or (:completion-tokens usage) 0)) tranche)
                       input (if priced?
                               (max 0 (- (long (or (:prompt-tokens usage) 0))
                                         (long (or (:cache-hit-tokens usage) 0))))
                               0)]
                   (-> state
                       (update :tokens + (- tranche completion input))
                       (update :reservations dissoc id)))
                 state))))
    (remaining budget)))

(defn cancel! [budget]
  (swap! (root budget) assoc :cancelled? true)
  nil)
