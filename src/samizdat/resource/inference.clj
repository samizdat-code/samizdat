;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.inference
  "Bounded inference trace and external-context resources."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.inference.trace :as trace]
            [samizdat.resource :as resource]))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defn- frame-observation [conn {:keys [uri identity] :as resource}]
  (if-let [frame (trace/frame conn identity)]
    (assoc resource :revision (:updated_at frame)
           :content (json/write-str frame)
           :metadata {:frame frame})
    (fail :missing-resource uri)))

(defn- context-observation [conn {:keys [uri identity] :as resource} ctx]
  (if-let [context (if (:run-id ctx)
                     (trace/context conn identity
                                    {:run-id (:run-id ctx)
                                     :branch-id (get-in ctx [:branch :id])})
                     (trace/context conn identity))]
    (assoc resource :revision (:digest context)
           :content (:content context)
           :metadata {:context context})
    (fail :missing-resource uri)))

(defn- search [observation {:keys [query limit offset]} search-limit]
  (let [text (:content observation)
        start (long (or offset 0))
        cap (long (or limit search-limit Long/MAX_VALUE))]
    (loop [from start results []]
      (let [index (when (< (count results) cap)
                    (str/index-of text query from))]
        (if (nil? index)
          (vec results)
          (let [end (+ index (count query))]
            (recur end (conj results {:offset index :excerpt (subs text index end)
                                      :next-offset end}))))))))

(defn- window [observation {:keys [offset limit query]} search-limit]
  (if query
    (search observation {:query query :limit limit :offset offset} search-limit)
    (let [text (:content observation)
          start (long (or offset 0))
          amount (long (or limit (- (count text) start)))
          end (min (count text) (+ start amount))]
      {:content (subs text start end)
       :next-offset (when (< end (count text)) end)})))

(defrecord InferenceResolver [conn options]
  resource/Resolver
  (resolve-resource [_ uri]
    (let [scheme (second (re-find #"^([^:]+):" uri))]
      {:uri uri :resolver (keyword scheme) :identity (subs uri (inc (count scheme)))}))
  (observe-resource [_ resource ctx]
    (case (:resolver resource)
      :inference (frame-observation conn resource)
      :context (context-observation conn resource ctx)
      (fail :unknown-scheme (:uri resource))))
  (read-resource [_ observation _ctx opts]
    (window observation opts (:search-limit options)))
  (resource-operations [_ resource _ctx]
    [{:operation :read :uri (:uri resource)}
     {:operation :search :uri (:uri resource)}]))

(defn resolver
  ([conn] (resolver conn {}))
  ([conn options] (->InferenceResolver conn options)))
