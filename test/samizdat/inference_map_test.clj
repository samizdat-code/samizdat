;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-map-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.inference :as inference-tool]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.map :as inference-map]))

(defn- run-item [_ {:keys [task budget barrier admitted wait]}]
  (when wait
    (.await wait 2 java.util.concurrent.TimeUnit/SECONDS))
  (if-let [reservation (budget/reserve! budget {:output-tokens 1})]
    (do
      (when admitted (.countDown admitted))
      (when barrier
        (.await barrier 2 java.util.concurrent.TimeUnit/SECONDS))
      (budget/settle! budget reservation {:usage {:completion-tokens 1} :priced? true})
      {:status :complete :value task})
    {:status :incomplete :reason :budget-exhausted}))

(deftest maps-preserve-input-order-and-share-budget
  (doseq [parallel? [false true]]
    (let [allowance (budget/budget {:calls 2 :tokens 10 :depth 1})
          barrier (when parallel? (java.util.concurrent.CountDownLatch. 2))
          admitted (when parallel? (java.util.concurrent.CountDownLatch. 2))
          results (inference-map/map-inference
                   {}
                   [{:task "slow" :barrier barrier :admitted admitted}
                    {:task "fast" :barrier barrier :admitted admitted}
                    {:task "exhausted" :wait admitted}]
                   {:run run-item :parallel? parallel? :budget allowance})]
      (is (= ["slow" "fast" :budget-exhausted]
             (mapv #(if (= :complete (:status %)) (:value %) (:reason %)) results)))
      (is (= 0 (:calls (budget/remaining allowance))))
      (is (= 8 (:tokens (budget/remaining allowance)))))))

(deftest parallel-failure-cancels-and-reaps-siblings
  (let [started (promise)
        cancelled (promise)
        run (fn [_ {:keys [task]}]
              (if (= "fail" task)
                (do @started (throw (ex-info "failed" {})))
                (try
                  (deliver started true)
                  (Thread/sleep 60000)
                  (catch InterruptedException _
                    (deliver cancelled true))
                  (finally
                    (deliver cancelled true)))))]
    (let [results (inference-map/map-inference
                   {} [{:task "fail"} {:task "sibling"}]
                   {:run run :parallel? true
                    :budget (budget/budget {:calls 2 :tokens 10 :depth 1})})]
      (is (= :error (:status (first results))))
      (is (= :cancelled (:reason (second results)))))
    (is (= true (deref cancelled 2000 :not-cancelled)))))

(deftest uninterruptible-siblings-cannot-hold-the-map-or-jvm-hostage
  (let [started (promise)
        run (fn [_ {:keys [task]}]
              (if (= "fail" task)
                (do @started (throw (ex-info "failed" {})))
                (do
                  (deliver started true)
                  (while true
                    (try (Thread/sleep 1000)
                         (catch InterruptedException _ nil))))))
        result (future
                 (inference-map/map-inference
                  {} [{:task "fail"} {:task "sibling"}]
                  {:run run :parallel? true
                   :budget (budget/budget {:calls 2 :tokens 10 :depth 1})}))]
    (let [value (deref result 3000 ::hung)]
      (is (not= ::hung value))
      (when (not= ::hung value)
        (is (= :cancelled (:reason (second value))))))))

(deftest map-action-forwards-its-shared-schema
  (let [seen (atom nil)]
    (with-redefs [inference-map/map-inference
                  (fn [_ _ opts] (reset! seen opts) [])]
      (base/run-tool {:tool-name "inference" :branch {:id "B1"}
                      :args {:action "map" :tasks [] :schema :text}}))
    (is (= :text (:schema @seen)))))
