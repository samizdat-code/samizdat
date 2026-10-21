;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.learned-test
  "Learned decisions (RFC-015): the LLM decides until the learner has matched
  it on this project's own record, then the learner takes the occurrences it
  is sure of, a seeded sample of those is still checked against the LLM, and
  drift hands the decision back."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.learned :as learned]
            [samizdat.store.db :as db]))

(def ^:private small-bars
  {:min-n 40 :bin-n 10 :level 0.95 :agreement {:allow 0.9 :ask 0.9}
   :audit 0.0 :cusum {:k 0.1 :h 2.0} :forget 1.0 :strength 4.0
   :outcome-level 0.8 :max-rows 5000})

(def ^:dynamic *bars* small-bars)

(use-fixtures :each
  (fn [t]
    (learned/configure! nil)
    (learned/register! :approval {:labels [:allow :ask]})
    (with-redefs [learned/bars (fn [_] *bars*)]
      (t))
    (learned/configure! nil)))

(defn- conn []
  (let [c (db/open! ":memory:")] (db/migrate! c) c))

(def ^:private family "glm-5")

(defn- occ [i key]
  {:id (str "o" i) :key key :head (first key) :features [] :family family})

(defn- decide [c run i key llm-label]
  (let [called (atom false)]
    [(learned/decide {:conn c :run-id run :branch-id "B1"} :approval (occ i key)
                     {:llm (fn [] (reset! called true) {:verdict llm-label})
                      :label-of :verdict})
     @called]))

(defn- feed!
  "`n` occurrences of `key` the LLM labels `label`, on run `run`."
  [c run start n key label]
  (doseq [i (range start (+ start n))] (decide c run i key label)))

(deftest a-fresh-point-is-shadow-the-llm-decides-and-the-pair-is-logged
  (let [c (conn)
        [r called] (decide c "r1" 0 ["ls" :parent "default"] :allow)]
    (is called "the LLM was asked")
    (is (= :allow (:label r)))
    (is (= :llm (:by r)))
    (is (= :shadow (:mode (learned/state c :approval family))))
    (let [[row] (learned/rows c :approval family)]
      (is (= :allow (:llm-label row)))
      (is (some? (:learner-label row)) "the learner predicted before it saw the answer")
      (is (= :llm (:decided-by row))))))

(deftest the-learner-learns-a-key-and-leans-on-its-head-for-a-new-one
  (let [c (conn)]
    (feed! c "r1" 0 30 ["ls" :parent "default"] :allow)
    (feed! c "r1" 30 30 ["rm" :scratch "default"] :ask)
    (let [p (fn [k] (learned/predict c :approval (occ 999 k)))]
      (is (= :allow (:label (p ["ls" :parent "default"]))))
      (is (> (:p (p ["ls" :parent "default"])) 0.9))
      (is (= :ask (:label (p ["rm" :scratch "default"]))))
      (testing "an unseen key with a known head is drawn toward the head"
        (is (= :allow (:label (p ["ls" :scratch "outside-project"]))))))))

(deftest graduation-needs-the-lower-end-of-the-interval-not-the-mean
  (let [c (conn)]
    (testing "thin evidence never graduates"
      (feed! c "r1" 0 15 ["ls" :parent "default"] :allow)
      (is (nil? (get-in (learned/report c :approval family) [:tau :allow])))
      (is (not (:graduated? (learned/report c :approval family)))))
    (testing "enough agreeing pairs above a confidence give that label a tau"
      (feed! c "r1" 15 60 ["ls" :parent "default"] :allow)
      (let [r (learned/report c :approval family)]
        (is (number? (get-in r [:tau :allow])))
        (is (:graduated? r))))))

(deftest a-pocket-of-disagreement-holds-the-label-back
  ;; Pooled agreement can clear the bar while one confidence bin is bad;
  ;; every bin above tau must agree on its own mean too.
  (let [c (conn)]
    (feed! c "r1" 0 60 ["ls" :parent "default"] :allow)
    ;; the LLM flips on a key the learner is sure of: those pairs sit in
    ;; the top bin and disagree
    (feed! c "r1" 60 12 ["ls" :parent "default"] :ask)
    (let [r (learned/report c :approval family)]
      (is (nil? (get-in r [:tau :allow])) (pr-str (:bins r))))))

(defn- graduate! [c]
  (doseq [i (range 70)]
    (decide c "r1" (* 2 i) ["ls" :parent "default"] :allow)
    (decide c "r1" (inc (* 2 i)) ["rm" :scratch "default"] :ask))
  (learned/run-ended! c "r1")
  (is (= :cascade (:mode (learned/state c :approval family)))))

(deftest cascade-takes-effect-only-with-the-operators-consent
  (let [c (conn)]
    (graduate! c)
    (testing "graduated, but the operator has not turned it on: the LLM decides"
      (let [[r called] (decide c "r2" 500 ["ls" :parent "default"] :allow)]
        (is called)
        (is (= :llm (:by r)))))
    (testing "turned on: above tau the learner answers and the LLM is not asked"
      (learned/configure! {:approval :cascade})
      (let [[r called] (decide c "r2" 501 ["ls" :parent "default"] :ask)]
        (is (not called))
        (is (= :learner (:by r)))
        (is (= :allow (:label r)) "the learner's label, not what the LLM would have said")))
    (testing "below tau the LLM still decides"
      (let [[r called] (decide c "r2" 502 ["curl" :parent "blocked-segment"] :ask)]
        (is called)
        (is (= :llm (:by r)))))))

(deftest an-audit-asks-the-llm-anyway-and-is-seeded
  (let [c (conn)]
    (graduate! c)
    (learned/configure! {:approval :cascade})
    (binding [*bars* (assoc small-bars :audit 1.0)]
      (let [[r called] (decide c "r2" 600 ["ls" :parent "default"] :allow)]
        (is called "every owned occurrence is audited at rate 1")
        (is (:audited? r))
        (is (= :learner (:by r)))))
    (is (= (learned/audit? "r9" "o1" 0.5) (learned/audit? "r9" "o1" 0.5))
        "the same run and occurrence audit the same way, so a replay agrees")))

(deftest audited-disagreement-demotes-to-shadow
  (let [c (conn)]
    (graduate! c)
    (learned/configure! {:approval :cascade})
    (binding [*bars* (assoc small-bars :audit 1.0)]
      (doseq [i (range 700 710)]
        (decide c "r2" i ["ls" :parent "default"] :ask)))
    (is (= :shadow (:mode (learned/state c :approval family))))
    (let [[r called] (decide c "r2" 800 ["ls" :parent "default"] :ask)]
      (is called)
      (is (= :llm (:by r)) "demoted: the LLM decides again"))))

(deftest another-model-family-starts-in-shadow
  (let [c (conn)]
    (graduate! c)
    (is (= :shadow (:mode (learned/state c :approval "deepseek"))))))

(deftest a-learner-that-fails-leaves-the-llm-deciding
  (let [c (conn)]
    (with-redefs [learned/predict (fn [& _] (throw (ex-info "boom" {})))]
      (let [[r called] (decide c "r1" 0 ["ls" :parent "default"] :allow)]
        (is called)
        (is (= :allow (:label r)))))))

(deftest the-outcome-guard-blocks-a-learner-worse-on-what-people-said
  (let [c (conn)]
    (feed! c "r1" 0 80 ["ls" :parent "default"] :allow)
    ;; people answered: and on the occurrences where the learner and the
    ;; LLM differed, the person sided with the LLM every time
    (doseq [i (range 100 110)]
      (decide c "r1" i ["ls" :parent "default"] :ask)
      (learned/observe! c (str "o" i) {:outcome :ask}))
    (let [r (learned/report c :approval family)]
      (is (:outcome-blocked? r))
      (is (not (:graduated? r))))))

(deftest a-project-may-raise-the-bars-never-lower-them
  (let [shipped {:min-n 300 :bin-n 20 :level 0.95 :agreement {:allow 0.98 :ask 0.9}
                 :audit 0.1 :cusum {:k 0.05 :h 3.0}}
        project {:min-n 10 :bin-n 50 :level 0.5 :agreement {:allow 0.5 :ask 0.95}
                 :audit 0.0 :cusum {:k 0.5 :h 100.0}}]
    (is (= {:min-n 300 :bin-n 50 :level 0.95 :agreement {:allow 0.98 :ask 0.95}
            :audit 0.1 :cusum {:k 0.05 :h 3.0}}
           (select-keys (learned/stricter shipped project)
                        [:min-n :bin-n :level :agreement :audit :cusum])))))

(deftest every-state-round-trips-through-edn
  (let [c (conn)]
    (graduate! c)
    (let [s (learned/state c :approval family)]
      (is (= s (clojure.edn/read-string (pr-str s)))))))
