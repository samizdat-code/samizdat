;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.execution
  "Exact-owner execution and completion resources."
  (:require [clojure.data.json :as json]
            [samizdat.resource :as resource]
            [samizdat.store.executions :as executions]))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defrecord ExecutionResolver [conn]
  resource/Resolver
  (resolve-resource [_ uri]
    {:uri uri :resolver :execution :identity (subs uri (count "execution:"))})
  (observe-resource [_ resource _ctx]
    (let [{:keys [uri identity]} resource]
      (if-let [execution (executions/execution conn identity)]
        (let [receipt (executions/receipt conn identity)]
          (assoc resource :revision (:updated_at execution)
                 :content (json/write-str {:execution execution :receipt receipt
                                           :continuity (executions/continuity conn identity)})
                 :metadata {:execution execution}))
        (fail :missing-resource uri))))
  (read-resource [_ observation _ctx {:keys [offset limit]}]
    (let [text (:content observation)
          start (max 0 (min (long (or offset 0)) (count text)))
          end (min (count text) (+ start (long (or limit (- (count text) start)))))]
      {:content (subs text start end)
       :next-offset (when (< end (count text)) end)}))
  (resource-operations [_ resource _ctx]
    [{:operation :read :uri (:uri resource)}]))

(defn resolver [conn]
  (->ExecutionResolver conn))
