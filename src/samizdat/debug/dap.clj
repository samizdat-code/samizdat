;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.debug.dap
  "Content-Length-framed Debug Adapter Protocol client."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ebb.core :as ebb]
            [jolt.process :as jp]
            [samizdat.cancel :as cancel]
            [samizdat.debug.sessions :as sessions]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]
            [samizdat.store.debug :as store]
            [samizdat.store.executions :as executions])
  (:import [java.io BufferedInputStream OutputStream]))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :debug/error kind))))

(defn send-frame! [{:keys [^OutputStream out]} message]
  (let [body (.getBytes ^String (json/write-str message :escape-slash false) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")]
    (locking out
      (.write out header)
      (.write out body)
      (.flush out))))

(defn read-frame [{:keys [^BufferedInputStream in max-body-bytes max-header-chars]}]
  (let [header (loop [builder (StringBuilder.)]
                 (let [c (.read in)]
                   (cond
                     (neg? c) nil
                     (> (.length builder) max-header-chars) (fail :header-limit {})
                     (str/ends-with? (.append builder (char c)) "\r\n\r\n") (.toString builder)
                     :else (recur builder))))
        length (some->> header (re-find #"(?i)Content-Length:\s*(\d+)") second Long/parseLong)]
    (when length
      (when (> length max-body-bytes)
        (fail :body-limit {:bytes length :limit max-body-bytes}))
      (let [buffer (byte-array length)]
        (loop [offset 0]
          (when (< offset length)
            (let [n (.read in buffer offset (- length offset))]
              (when (neg? n)
                (fail :truncated-frame {}))
              (recur (+ offset n)))))
        (json/read-str (String. buffer "UTF-8") :key-fn keyword)))))

(defn record-event! [client event]
  (locking client
    (when (>= (count @(:events client))
              (get-in client [:policy :max-events]))
      (fail :event-limit {:session (:session-id client)}))
    (let [stored (store/record-event! (:conn client) (:session-id client)
                                     (first (:owner client)) (second (:owner client)) event)]
      (swap! (:events client) conj stored)
      (doseq [[name waiter] @(:event-waiters client)
              :when (= name (:event event))]
        (deliver waiter event))
      (swap! (:event-waiters client)
             (fn [waiters] (vec (remove #(= (:event event) (first %)) waiters))))
      stored)))

(defn wait-event! [client name]
  (let [pending (locking client
                  (if-let [event (first (filter #(= name (:event %)) @(:events client)))]
                    (do (swap! (:events client) (fn [events] (vec (remove #{event} events))))
                        [:ready event])
                    (let [waiter (promise)]
                      (swap! (:event-waiters client) conj [name waiter])
                      [:waiting waiter])))]
    (if (= :ready (first pending))
      (second pending)
      (let [[tag event] (cancel/with-deadline (ebb/via ebb/blk (deref (second pending)))
                                               (get-in client [:policy :timeout-ms]))]
        (when (= :timeout tag) (fail :timeout {:event name}))
        (when (= :err tag) (throw event))
        event))))

(declare close!)

(defn- start-reader! [client]
  (future
    (try
      (loop []
        (when-let [message (read-frame (merge (:policy client) client))]
          (if (= "response" (:type message))
            (when-let [{:keys [promise]} (get @(:pending client) (:request_seq message))]
              (deliver promise message))
            (record-event! client message))
          (recur)))
      (catch Throwable _ nil))
    (doseq [[_ {:keys [promise]}] @(:pending client)] (deliver promise ::closed))
    (sessions/remove! (:session-id client))
    (executions/complete! (:conn client) (:session-id client)
                          {:status :error :artifacts [(str "debug:" (:session-id client))]})
    (close! client))
  client)

(defn request! [client method params]
  (if-let [request-fn (:request-fn client)]
    (request-fn client method params)
    (let [id (swap! (:next-id client) inc)
          waiter (promise)]
      (when (>= (count @(:pending client)) (get-in client [:policy :max-pending]))
        (fail :pending-limit {:method method}))
      (swap! (:pending client) assoc id {:promise waiter :method method})
      (try
        (send-frame! client {:seq id :type "request" :command method :arguments params})
        (let [[tag message] (cancel/with-deadline (ebb/via ebb/blk (deref waiter))
                                                  (get-in client [:policy :timeout-ms]))]
          (cond
            (= :timeout tag) (fail :timeout {:method method})
            (= :err tag) (throw message)
            (= ::closed message) (fail :adapter-died {:method method})
            (= ::cancelled message) (fail :cancelled {:method method})
            (false? (:success message)) (fail :adapter-rejected {:message (:message message)})
            :else (:body message)))
        (finally (swap! (:pending client) dissoc id))))))

(defn start! [ctx execution]
  (let [policy (:policy execution)
        process (jp/process (into [(:program policy)] (:adapter-args policy))
                            {:env (secrets/scrubbed-process-env) :dir (:root ctx)})
        os-process (:proc process)
        client {:proc process :out (.getOutputStream os-process)
                :in (BufferedInputStream. (.getInputStream os-process))
                :next-id (atom 0) :pending (atom {}) :events (atom [])
                :event-waiters (atom [])
                :conn (:conn execution) :owner (:owner execution)
                :policy policy :session-id (:id execution)}]
    (start-reader! client)))

(defn cancel! [client]
  (doseq [[id {:keys [promise]}] @(:pending client)]
    (try
      (send-frame! client {:seq (swap! (:next-id client) inc) :type "request"
                           :command "cancel" :arguments {:requestId id}})
      (catch Throwable _ nil))
    (deliver promise ::cancelled))
  nil)

(defn close! [client]
  (cancel! client)
  (when-let [process (:proc client)]
    (try (proc/reap! process) (catch Throwable _ nil)))
  nil)
