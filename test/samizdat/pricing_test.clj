;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.pricing-test
  "Dollar cost beside a run's token usage (karamazov-k7gl.8): the price table
  as data, the pure cost function, and :cost on GET /v1/runs/:id."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.api.runs :as api-runs]
            [samizdat.pricing :as pricing]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(def ^:private table
  {:models {"m-big" {:input 2.0 :output 8.0 :cache-read 0.5 :cache-write 2.5}
            "m-plain" {:input 1.0 :output 3.0}}
   :providers {"acme" {"m-big" {:input 4.0 :output 16.0 :cache-read 1.0}}}})

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-9))

(deftest a-price-is-looked-up-by-provider-then-model
  (is (= 4.0 (:input (pricing/price table "acme" "m-big"))) "a provider's own rate wins")
  (is (= 2.0 (:input (pricing/price table "other" "m-big"))))
  (is (= 2.0 (:input (pricing/price table nil "m-big"))))
  (is (nil? (pricing/price table "acme" "m-none")))
  (is (nil? (pricing/price {:models {"half" {:input 1.0}}} nil "half"))
      "a rate without an output price is no price: never guessed"))

(deftest a-row-costs-its-tokens-at-its-rates
  (testing "cache lanes reported: the hits at the read rate, the misses at input"
    (is (close? (/ (+ (* 600 2.0) (* 400 0.5) (* 100 8.0)) 1e6)
                (pricing/row-usd {:input 2.0 :output 8.0 :cache-read 0.5}
                                 {:prompt-tokens 1000 :completion-tokens 100
                                  :cache-hit-tokens 400 :cache-miss-tokens 600}))))
  (testing "no cache lanes: the whole prompt at input"
    (is (close? (/ (+ 1000 300) 1e6)
                (pricing/row-usd {:input 1.0 :output 3.0}
                                 {:prompt-tokens 1000 :completion-tokens 100}))))
  (testing "writes, when a row carries them, at the write rate"
    (is (close? (/ (+ (* 100 2.0) (* 500 2.5)) 1e6)
                (pricing/row-usd {:input 2.0 :output 8.0 :cache-write 2.5}
                                 {:prompt-tokens 600 :completion-tokens 0
                                  :cache-hit-tokens 0 :cache-miss-tokens 600
                                  :cache-write-tokens 500}))))
  (testing "a missing cache-read rate bills a read at input, as a provider without a discount does"
    (is (close? (/ (* 1000 1.0) 1e6)
                (pricing/row-usd {:input 1.0 :output 3.0}
                                 {:prompt-tokens 1000 :completion-tokens 0
                                  :cache-hit-tokens 400 :cache-miss-tokens 600})))))

(deftest a-run-costs-what-its-priced-models-cost
  (let [c (pricing/cost table [{:provider "acme" :model "m-big" :prompt-tokens 1000000
                                :completion-tokens 0}
                               {:provider "x" :model "m-plain" :prompt-tokens 0
                                :completion-tokens 1000000}
                               {:provider "x" :model "mystery" :prompt-tokens 5
                                :completion-tokens 5}])]
    (is (close? 7.0 (:usd c)) "4 + 3: only the priced")
    (is (close? 4.0 (get-in c [:by-model "acme/m-big" :usd])))
    (is (close? 3.0 (get-in c [:by-model "x/m-plain" :usd])))
    (is (= ["x/mystery"] (:unpriced c)) "listed, never guessed")
    (is (not (contains? (:by-model c) "x/mystery"))))
  (is (= {:usd 0.0 :by-model {} :unpriced []} (pricing/cost table [])))
  (is (nil? (:usd (pricing/cost table [{:model "mystery" :prompt-tokens 1 :completion-tokens 1}])))
      "nothing priced is an unknown total, not a free run"))

(deftest the-shipped-table-reads-and-a-layer-adds-to-it
  (is (map? (pricing/shipped)) "resources/pricing.edn is EDN")
  (let [t (pricing/table {:pricing {:models {"mine" {:input 1.0 :output 1.0}}}}
                         {:shipped "{:models {\"theirs\" {:input 2.0 :output 2.0}}}"})]
    (is (= 1.0 (:input (pricing/price t nil "mine"))) "config.edn :pricing adds")
    (is (= 2.0 (:input (pricing/price t nil "theirs"))) "the shipped rates stay"))
  (let [t (pricing/table {:pricing {:models {"theirs" {:input 9.0}}}}
                         {:shipped "{:models {\"theirs\" {:input 2.0 :output 2.0}}}"})]
    (is (= {:input 9.0 :output 2.0} (pricing/price t nil "theirs")) "and overrides rate by rate")))

(deftest usage-is-split-by-model
  (let [conn (db/open! ":memory:")]
    (try
      (let [rid (runs/start-run! conn {:problem "p" :provider "acme" :model "m-big"})]
        (db/execute! conn ["INSERT INTO turns (run_id, branch_id, turn, created_at, prompt_tokens,
                                               completion_tokens, total_tokens)
                            VALUES (?, 'B', 1, 'now', 100, 10, 110)" rid])
        (db/execute! conn ["INSERT INTO side_calls (run_id, kind, model, prompt_tokens,
                                                    completion_tokens, total_tokens, created_at)
                            VALUES (?, 'reader', 'm-plain', 50, 5, 55, 'now')" rid])
        (db/execute! conn ["INSERT INTO side_calls (run_id, kind, prompt_tokens,
                                                    completion_tokens, total_tokens, created_at)
                            VALUES (?, 'judge', 7, 1, 8, 'now')" rid])
        (let [rows (sort-by :model (journal/run-usage-by-model conn rid))]
          (is (= [["acme" "m-big" 107 11] [nil "m-plain" 50 5]]
                 (mapv (juxt :provider :model :prompt-tokens :completion-tokens) rows))
              "a side call naming no model is the run's"))
        (testing "the run detail carries :cost beside :usage"
          (with-redefs [pricing/table (fn [& _] table)]
            (let [cost (get-in (api-runs/get-run conn rid {}) [:run :cost])]
              (is (close? (/ (+ (* 107 4.0) (* 11 16.0) (* 50 1.0) (* 5 3.0)) 1e6) (:usd cost)))
              (is (= [] (:unpriced cost)))))))
      (finally (db/close conn)))))
