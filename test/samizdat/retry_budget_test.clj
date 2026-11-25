;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.retry-budget-test
  "The client's retry ladder spends a budget PER failure class
  (karamazov-k7gl.11): a rate limit, a server error, a dropped connection and
  a read timeout each have their own count, from gates.edn
  :provider-retry, instead of one number shared by all of them."
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.http-client :as http]
            [samizdat.cancel]
            [samizdat.llm.client :as client]
            [samizdat.llm.ratelimit :as ratelimit]
            [samizdat.llm.registry :as registry]))

(defn- calls-until-failure
  "How many requests a call makes before giving up when every one answers
  with `respond`, and the ex-data it gave up with."
  [cfg respond]
  (let [calls (atom 0)
        a (registry/adapter-for :deepseek-chat)]
    (ratelimit/reset-all!)
    (with-redefs [http/post (fn [& _] (swap! calls inc) (respond))
                  samizdat.cancel/sleep! (fn [_] nil)]
      (let [e (try (client/chat a (merge {:model "m" :base-url "http://budget.test/v1"} cfg)
                                [{:role "user" :content "go"}])
                   (catch Exception ex ex))]
        {:calls @calls :data (ex-data e)}))))

(def ^:private budgets
  {:rate-limited 3 :server 1 :transport 2 :timeout 0 :partial-stream 0 :empty-reply 0})

(deftest each-class-spends-its-own-budget
  (testing "a rate limit"
    (is (= 4 (:calls (calls-until-failure {:retry-budgets budgets}
                                          (fn [] {:status 429 :body "{}"}))))))
  (testing "a server error"
    (is (= 2 (:calls (calls-until-failure {:retry-budgets budgets}
                                          (fn [] {:status 503 :body "{}"}))))))
  (testing "a dropped connection"
    (is (= 3 (:calls (calls-until-failure
                      {:retry-budgets budgets}
                      (fn [] (throw (java.net.ConnectException. "connection refused"))))))))
  (testing "a read timeout is not retried at 0, and keeps its reason"
    (let [{:keys [calls data]} (calls-until-failure
                                {:retry-budgets budgets}
                                (fn [] (throw (ex-info "Read timed out" {}))))]
      (is (= 1 calls))
      (is (= :timeout (:reason data)))))
  (testing "and is retried when its budget says so"
    (is (= 2 (:calls (calls-until-failure
                      {:retry-budgets (assoc budgets :timeout 1)}
                      (fn [] (throw (ex-info "Read timed out" {})))))))))

(deftest the-classes-do-not-share-a-count
  ;; Two server errors then rate limits: the server budget of 1 is spent on
  ;; the first retry, and the 429s still get their own three.
  (let [n (atom 0)
        {:keys [calls]} (calls-until-failure
                         {:retry-budgets (assoc budgets :server 2)}
                         (fn [] (if (<= (swap! n inc) 2)
                                  {:status 503 :body "{}"}
                                  {:status 429 :body "{}"})))]
    (is (= 6 calls) "2 server errors, then 1 + 3 rate limits")))

(deftest an-explicit-max-retries-still-caps-every-class
  (is (= 1 (:calls (calls-until-failure {:retry-budgets budgets :max-retries 0}
                                        (fn [] {:status 429 :body "{}"})))))
  (is (= 2 (:calls (calls-until-failure {:max-retries 1}
                                        (fn [] {:status 503 :body "{}"}))))))

(deftest the-shipped-budgets-are-policy
  (let [b (client/retry-budgets {})]
    (is (every? #(contains? b %) [:rate-limited :server :transport :timeout]))
    (is (every? nat-int? (vals b)))))
