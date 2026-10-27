;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.knowledge
  "Bounded knowledge status and evidence explanations."
  (:require [clojure.data.json :as json]
            [samizdat.resource :as resource]
            [samizdat.store.evidence :as evidence]
            [samizdat.store.knowledge :as knowledge]))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defn- observation [conn {:keys [uri identity] :as resource} ctx]
  (let [row (or (knowledge/get-by-id conn identity) (fail :missing-resource uri))
        explanation (evidence/explain conn identity {:check-file (evidence/file-checker (:root ctx))})]
    (assoc resource :revision (str (:updated_at row) ":" (:status explanation))
           :content (json/write-str explanation)
           :metadata {:knowledge row})))

(defrecord KnowledgeResolver [conn]
  resource/Resolver
  (resolve-resource [_ uri]
    {:uri uri :resolver :knowledge :identity (subs uri (count "knowledge:"))})
  (observe-resource [_ resource ctx]
    (observation conn resource ctx))
  (read-resource [_ observation _ctx {:keys [offset limit]}]
    (let [text (:content observation)
          start (max 0 (min (long (or offset 0)) (count text)))
          end (min (count text) (+ start (long (or limit (- (count text) start)))))]
      {:status (:status observation)
       :content (subs text start end)
       :next-offset (when (< end (count text)) end)}))
  (resource-operations [_ resource _ctx]
    [{:operation :read :uri (:uri resource)}]))

(defn resolver [conn]
  (->KnowledgeResolver conn))
