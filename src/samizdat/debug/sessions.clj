;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.debug.sessions
  "Bounded exact-owner debug session registry."
  (:require [samizdat.agent.gates :as gates]))

(defonce ^:private sessions (atom {}))

(defn- owner [ctx]
  [(:run-id ctx) (get-in ctx [:branch :id])])

(defn admit! [ctx id]
  (locking sessions
    (let [policy (gates/threshold :debug)
          owned (count (filter #(= (owner ctx) (:owner (val %))) @sessions))]
      (when (or (>= (count @sessions) (:max-sessions policy))
                (>= owned (:max-owner-sessions policy)))
        (throw (ex-info "debug session limit" {:debug/error :session-limit})))
      (swap! sessions assoc id {:owner (owner ctx) :session-id id
                                :conn (:conn ctx) :policy policy}))))

(defn register! [ctx id client]
  (when-not (get @sessions id)
    (throw (ex-info "debug session admission lost" {:debug/error :unknown-session})))
  (swap! sessions assoc id (assoc client :owner (owner ctx) :session-id id
                                  :conn (:conn ctx) :policy (gates/threshold :debug)))
  (get @sessions id))

(defn owned [ctx id]
  (let [client (get @sessions (str id))]
    (when-not (= (owner ctx) (:owner client))
      (throw (ex-info "unknown debug session" {:debug/error :unknown-session})))
    client))

(defn remove! [id]
  (swap! sessions dissoc (str id))
  nil)

(defn- close-entry! [[id client]]
  (try ((requiring-resolve 'samizdat.debug.dap/close!) client) (catch Throwable _ nil))
  (try
    (let [execution ((requiring-resolve 'samizdat.store.executions/execution) (:conn client) id)]
      (when (= :running (:status execution))
        ((requiring-resolve 'samizdat.store.executions/complete!)
         (:conn client) id {:status :error :artifacts [(str "debug:" id)]})))
    (catch Throwable _ nil))
  (remove! id))

(defn close-all! []
  (doseq [entry @sessions] (close-entry! entry))
  (reset! sessions {})
  nil)

(defn close-run! [run-id]
  (doseq [entry @sessions
          :when (= run-id (first (:owner (val entry))))]
    (close-entry! entry))
  nil)

(defn reset-sessions! []
  (close-all!))
