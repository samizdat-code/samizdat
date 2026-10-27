;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.diagnosis
  "Exact-owner diagnosis case resources."
  (:require [clojure.data.json :as json]
            [samizdat.resource :as resource]
            [samizdat.store.diagnoses :as diagnoses]))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defrecord DiagnosisResolver [conn]
  resource/Resolver
  (resolve-resource [_ uri]
    {:uri uri :resolver :diagnosis :identity (subs uri (count "diagnosis:"))})
  (observe-resource [_ resource _ctx]
    (let [{:keys [uri identity]} resource]
      (if-let [case (diagnoses/diagnosis conn identity)]
        (assoc resource :revision (:updated_at case)
               :content (json/write-str case)
               :metadata {:diagnosis case})
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
  (->DiagnosisResolver conn))
