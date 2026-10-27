;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.map
  "Ordered inference maps over one shared budget tree."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.cancel :as cancel]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.frame :as frame]))

(defn- invoke [run ctx task]
  (try
    (run ctx task)
    (catch Throwable e
      (if (cancel/control-signal? e)
        (throw e)
        {:status :error :error (ex-message e)}))))

(defn- spawn [f]
  (let [result (promise)
        thread (Thread. (fn []
                          (deliver result
                                   (try [:ok (f)]
                                        (catch Throwable e [:error e])))))]
    (.setDaemon thread true)
    (.start thread)
    {:thread thread :result result}))

(defn- await [worker]
  (let [[tag value] @(:result worker)]
    (if (= :error tag) (throw value) value)))

(defn- cancel-and-reap! [workers]
  (doseq [worker workers] (.interrupt ^Thread (:thread worker)))
  (doseq [worker workers]
    (.join ^Thread (:thread worker)
           (long (:cancel-grace-ms (gates/threshold :inference))))))

(defn- parallel-results [run tasks budget]
  (let [workers (mapv #(spawn (fn [] (run %))) tasks)]
    (try
      (loop [remaining workers results []]
        (if-let [worker (first remaining)]
          (let [result (await worker)
                others (vec (rest remaining))]
            (if (= :error (:status result))
              (do
                (budget/cancel! budget)
                (cancel-and-reap! others)
                (into results (cons result (repeat (count others)
                                                   {:status :incomplete :reason :cancelled}))))
              (recur others (conj results result))))
          results))
      (catch Throwable e
        (budget/cancel! budget)
        (cancel-and-reap! workers)
        (throw e)))))

(defn map-inference
  "Run tasks with shared budget, preserving input order and per-item outcomes."
  [ctx tasks {:keys [run parallel? budget schema] :or {run frame/infer parallel? false}}]
  (let [tasks (mapv #(cond-> (assoc % :budget budget)
                       (and schema (not (:schema %))) (assoc :schema schema)) tasks)
        run (fn [task] (invoke run ctx task))]
    (try
      (if parallel?
        (parallel-results run tasks budget)
        (reduce (fn [results task]
                  (conj results (run task)))
                [] tasks))
      (catch Throwable e
        (budget/cancel! budget)
        (throw e)))))
