;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.peripherals
  "Lifecycle management for configured resources outside workflow cells."
  (:require [clojure.string :as str]))

(defonce ^:private registrations (atom {}))
(defonce ^:private state (atom {:order [] :handles {} :notices []}))

(defn validate-config
  "Return CONFIG when its peripheral section has the declared shape."
  [config]
  (let [peripherals (:peripherals config {})]
    (when-not (map? peripherals)
      (throw (ex-info ":peripherals must be a map of id to configuration"
                      {:peripherals peripherals})))
    (doseq [[id spec] peripherals]
      (when-not (map? spec)
        (throw (ex-info (str ":peripherals " id " must be a configuration map")
                        {:id id :spec spec})))
      (when-not (boolean? (:enabled spec))
        (throw (ex-info (str ":peripherals " id " :enabled must be boolean")
                        {:id id :enabled (:enabled spec)})))
      (when-not (map? (:options spec {}))
        (throw (ex-info (str ":peripherals " id " :options must be a map")
                        {:id id :options (:options spec)})))
      (when-let [unknown (seq (remove #{:enabled :options} (keys spec)))]
        (throw (ex-info (str ":peripherals " id " has unrecognized keys "
                             (str/join ", " unknown))
                        {:id id :unknown (set unknown)}))))
    config))

(defn register!
  "Register START and its exact inverse STOP for peripheral ID."
  [id start stop]
  (when-not (and (ifn? start) (ifn? stop))
    (throw (ex-info "a peripheral needs start and stop functions" {:id id})))
  (swap! registrations assoc id {:start start :stop stop})
  id)

(defn- notice! [id phase error]
  (swap! state update :notices conj
         {:id id :phase phase :message (or (ex-message error) (str error))}))

(defn notices []
  (:notices @state))

(defn start!
  "Start every enabled configured peripheral, retaining each acquired handle."
  [config]
  (validate-config config)
  (when (seq (:order @state))
    (throw (ex-info "peripherals already started; call stop! or restart!"
                    {:running (:order @state)})))
  (doseq [[id {:keys [enabled options]}] (:peripherals config)
          :when enabled]
    (if-let [{:keys [start stop]} (get @registrations id)]
      (try
        (let [handle (start (or options {}))]
          (swap! state (fn [s]
                         (-> s
                             (update :order conj id)
                             (assoc-in [:handles id] {:handle handle :stop stop})))))
        (catch Throwable error
          (notice! id :start error)))
      (notice! id :start (ex-info "no peripheral registration" {:id id}))))
  {:started (:order @state) :notices (notices)})

(defn stop!
  "Stop acquired peripherals once each in reverse acquisition order."
  []
  (doseq [id (reverse (:order @state))]
    (when-let [{:keys [handle stop]} (get-in @state [:handles id])]
      (try
        (stop handle)
        (catch Throwable error
          (notice! id :stop error)))))
  (swap! state assoc :order [] :handles {})
  {:notices (notices)})

(defn restart! [config]
  (stop!)
  (start! config))
