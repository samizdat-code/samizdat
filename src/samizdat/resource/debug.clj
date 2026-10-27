;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.debug
  "Exact-owner debug session artifacts."
  (:require [clojure.data.json :as json]
            [samizdat.resource :as resource]
            [samizdat.store.debug :as debug]
            [samizdat.store.executions :as executions]))

(defrecord DebugResolver [conn]
  resource/Resolver
  (resolve-resource [_ uri]
    {:uri uri :resolver :debug :identity (subs uri (count "debug:"))})
  (observe-resource [_ value _ctx]
    (let [id (:identity value)
          execution (executions/execution conn id)]
      (when-not execution
        (throw (ex-info "missing debug session" {:resource/error :missing-resource :uri (:uri value)})))
      (assoc value :revision (:updated_at execution)
             :content (json/write-str {:execution execution :events (debug/events conn id)})
             :metadata {:execution execution})))
  (read-resource [_ observation _ctx {:keys [offset limit]}]
    (let [text (:content observation)
          start (max 0 (min (long (or offset 0)) (count text)))
          end (min (count text) (+ start (long (or limit (- (count text) start)))))]
      {:content (subs text start end) :next-offset (when (< end (count text)) end)}))
  (resource-operations [_ value _ctx]
    [{:operation :read :uri (:uri value)}]))

(defn resolver [conn]
  (->DebugResolver conn))
