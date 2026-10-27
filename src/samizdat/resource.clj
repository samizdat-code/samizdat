;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource
  "Authority-neutral resource resolution and observation.

  Resolvers own identity, revisions, bounded reads, and supported operations.
  Callers retain responsibility for authorization and for deciding how an
  observation may be used."
  (:require [clojure.string :as str]))

(def workspace-prefix "workspace:")

(defprotocol Resolver
  (resolve-resource [resolver uri]
    "Resolve `uri` to an authority-neutral resource identity.")
  (observe-resource [resolver resource ctx]
    "Observe the current resource and its opaque revision.")
  (read-resource [resolver observation ctx opts]
    "Read a bounded window of an observation.")
  (resource-operations [resolver resource ctx]
    "Operations the resolver supports for this resource and context."))

(defn registry
  "A resolver registry from a map of scheme keywords to Resolver values."
  [resolvers]
  (assoc resolvers :proposal-state (atom {})))

(defn- error [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defn- scheme [uri]
  (let [s (str uri)
        i (str/index-of s ":")]
    (when (or (nil? i) (zero? i) (= i (dec (count s))))
      (error :malformed-uri s))
    (keyword (subs s 0 i))))

(defn resolve-uri
  "Resolve `uri` through the registry's scheme-specific resolver."
  [registry uri]
  (let [scheme (scheme uri)
        resolver (get registry scheme)]
    (when-not resolver
      (error :unknown-scheme uri))
    (resolve-resource resolver uri)))

(defn- resolver-for [registry observation]
  (or (get registry (:resolver observation))
      (error :unknown-scheme (:uri observation))))

(defn observe
  "Observe `uri` in caller context `ctx`."
  [registry uri ctx]
  (let [resource (resolve-uri registry uri)]
    (observe-resource (get registry (:resolver resource)) resource ctx)))

(defn read-window
  "Read a bounded window from a previously returned observation."
  [registry observation ctx opts]
  (read-resource (resolver-for registry observation) observation ctx opts))

(defn operations
  "Operations supported for `uri` in caller context `ctx`."
  [registry uri ctx]
  (let [resource (resolve-uri registry uri)]
    (resource-operations (get registry (:resolver resource)) resource ctx)))
