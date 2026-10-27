;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-budget-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.inference.budget :as budget]))

(deftest reservations-and-settlement-share-one-tree
  (let [root (budget/budget {:calls 2 :tokens 20000 :depth 2})
        reservation (budget/reserve! root {:output-tokens 10000})]
    (is (some? reservation))
    (is (= 5000 (:output-tokens reservation)) "output tranche is capped at one quarter")
    (is (= 1 (:calls (budget/remaining root))))
    (let [child (budget/child root)]
      (is (= 1 (:depth (budget/remaining child))))
      (budget/settle! root reservation
                      {:usage {:prompt-tokens 10000 :completion-tokens 1000
                               :cache-hit-tokens 8000}
                       :priced? true})
      (is (= 17000 (:tokens (budget/remaining root)))
          "cached input is not billed; unused output reservation is refunded")
      (is (nil? (budget/reserve! (budget/budget {:calls 5 :tokens 100 :depth 0})
                                {:output-tokens 1}))
          "depth exhaustion is independent of remaining tokens"))))

(deftest unpriced-replies-pay-their-output-reservation
  (let [root (budget/budget {:calls 1 :tokens 1000 :depth 1})
        reservation (budget/reserve! root {:output-tokens 100})]
    (budget/settle! root reservation {:usage nil :priced? false})
    (is (= 900 (:tokens (budget/remaining root))))
    (is (zero? (:calls (budget/remaining root))))))

(deftest cancellation-closes-the-tree
  (let [root (budget/budget {:calls 1 :tokens 100 :depth 1})]
    (budget/cancel! root)
    (is (nil? (budget/reserve! root {:output-tokens 1})))
    (is (:cancelled? (budget/remaining root)))))

(deftest exhausted-call-and-token-allowances-admit-nothing
  (doseq [allowance [{:calls 0 :tokens 100 :depth 1}
                     {:calls 1 :tokens 0 :depth 1}]]
    (let [root (budget/budget allowance)]
      (is (nil? (budget/reserve! root {:output-tokens 1})))
      (is (= (select-keys allowance [:calls :tokens])
             (select-keys (budget/remaining root) [:calls :tokens]))))))

(deftest only-one-sibling-can-reserve-the-last-call
  (let [root (budget/budget {:calls 1 :tokens 100 :depth 1})
        results (doall
                 (for [_ (range 8)]
                   (future (some? (budget/reserve! root {:output-tokens 1})))))]
    (is (= [true] (filter true? (map deref results))))))

(deftest siblings-have-independent-depth-but-shared-calls-and-tokens
  (let [root (budget/budget {:calls 2 :tokens 10 :depth 2})
        first-child (budget/child root)
        second-child (budget/child root)]
    (is (= 1 (:depth (budget/remaining first-child))))
    (is (= 1 (:depth (budget/remaining second-child))))
    (is (some? (budget/reserve! first-child {:output-tokens 1})))
    (is (some? (budget/reserve! second-child {:output-tokens 1})))
    (is (zero? (:calls (budget/remaining root))))))

(deftest concurrent-reservation-and-settlement-never-admits-overspend
  (let [root (budget/budget {:calls 4 :tokens 100 :depth 1})
        results (doall
                 (for [i (range 16)]
                   (future
                     (if-let [reservation (budget/reserve! root {:output-tokens 1})]
                       (do (budget/settle! root reservation
                                           {:usage {:completion-tokens 1} :priced? true})
                           true)
                       false))))]
    (is (= 4 (count (filter true? (map deref results)))))
    (is (zero? (:calls (budget/remaining root))))
    (is (= 96 (:tokens (budget/remaining root))))))
