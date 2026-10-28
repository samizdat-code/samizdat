;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/cljc/analyze.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.cljc.analyze
  "Pure normalized CLJ/CLJS pair and CLJC analysis."
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.cljc.merge :as merge]
            [samizdat.surgeon.cljc.split :as split]
            [samizdat.surgeon.cljc.walk :as cwalk]))

(defn- require-infos [src]
  (let [ns-zl (merge/parse-ns-form src)]
    (when ns-zl (mapv merge/vec-node->info (or (merge/require-vector-nodes ns-zl) [])))))

(defn- summarize-vec-info [v]
  (cond-> {:ns (let [k (:ns v)] (if (string? k) k (str k)))}
    (:alias v) (assoc :as (:alias v))))

(defn- top-level-form-summary [{:keys [zloc platforms]}]
  (let [head (some-> zloc z/down z/string)
        nm (let [second (some-> zloc z/down z/right)]
             (when second (if (= :meta (some-> second z/node n/tag))
                            (some-> second z/down z/rightmost z/string) (z/string second))))
        m (meta (z/node zloc))]
    (cond-> {:type head :platforms (vec (sort platforms))}
      nm (assoc :name nm) (:row m) (assoc :line (:row m)))))

(defn- analyze-pair* [clj-src cljs-src]
  (let [classified (merge/classify-requires (require-infos clj-src) (require-infos cljs-src))
        clj-forms (cwalk/top-level-forms clj-src #{:clj})
        cljs-forms (cwalk/top-level-forms cljs-src #{:cljs})]
    {:requires {:shared (mapv summarize-vec-info (:shared classified))
                :clj-only (mapv summarize-vec-info (:clj-only classified))
                :cljs-only (mapv summarize-vec-info (:cljs-only classified))
                :divergent (mapv (fn [{c :clj cs :cljs}]
                                   {:clj (summarize-vec-info c) :cljs (summarize-vec-info cs)})
                                 (:divergent classified))}
     :forms-clj (mapv top-level-form-summary
                      (remove #(= "ns" (some-> % :zloc z/down z/string)) clj-forms))
     :forms-cljs (mapv top-level-form-summary
                       (remove #(= "ns" (some-> % :zloc z/down z/string)) cljs-forms))}))

(defn analyze-pair [clj-src cljs-src] (analyze-pair* clj-src cljs-src))

(defn analyze-cljc [cljc-src]
  (let [{:keys [clj cljs]} (split/split-file cljc-src)]
    (assoc (analyze-pair* clj cljs) :input :cljc)))
