;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/cljc/require_ops.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.cljc.require-ops
  "Platform-aware require operations over source strings."
  (:require [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.cljc.merge :as merge]
            [samizdat.surgeon.cljc.split :as split]))

(defn- ns-zloc [src]
  (->> (z/of-string src {:track-position? true}) (iterate z/right) (take-while some?)
       (filter #(and (z/list? %) (= "ns" (some-> % z/down z/string)))) first))

(defn- require-form-zloc [ns-zl]
  (->> (-> ns-zl z/down z/right z/right) (iterate z/right) (take-while some?)
       (filter #(and (z/list? %) (= ":require" (some-> % z/down z/string)))) first))

(defn- token-vector-node [symbols]
  (n/vector-node (vec (interpose (n/spaces 1) (map n/token-node symbols)))))

(defn- entry-node
  ([ns-sym alias] (entry-node ns-sym alias nil))
  ([ns-sym alias referred]
   (let [head (cond (string? ns-sym) (n/string-node ns-sym)
                    (symbol? ns-sym) (n/token-node ns-sym)
                    :else (n/token-node (symbol ns-sym)))
         parts (cond-> [head]
                 alias (into [(n/spaces 1) (n/keyword-node :as) (n/spaces 1) (n/token-node (symbol alias))])
                 (seq referred) (into [(n/spaces 1) (n/keyword-node :refer) (n/spaces 1)
                                       (token-vector-node (mapv symbol referred))]))]
     (n/vector-node parts))))

(defn- existing-alias-target [src a]
  (when a
    (let [ns-zl (ns-zloc src) req-zl (and ns-zl (require-form-zloc ns-zl))]
      (when req-zl
        (some (fn [v-zl]
                (let [s (n/sexpr (z/node v-zl)) idx (.indexOf (vec s) :as)]
                  (when (and (pos? idx) (= (str (nth s (inc idx))) (str a))) (first s))))
              (->> (z/down req-zl) (iterate z/right) (take-while some?) (filter z/vector?)))))))

(defn- check-alias-collision! [src target-ns alias platform]
  (when alias
    (when-let [existing (existing-alias-target src alias)]
      (when (not= (str existing) (str target-ns))
        (throw (ex-info (str "Alias '" alias "' is already bound to a different namespace on the " platform " side")
                        {:platform platform :alias alias :existing-ns existing :requested-ns target-ns}))))))

(defn insert-into-require
  ([src ns-sym alias] (insert-into-require src ns-sym alias nil))
  ([src ns-sym alias referred]
   (let [ns-zl (ns-zloc src)]
     (when (nil? ns-zl) (throw (ex-info "Cannot insert require: source has no ns form" {})))
     (let [req-zl (require-form-zloc ns-zl) new-entry (entry-node ns-sym alias referred)]
       (if req-zl
         (-> req-zl (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces 3))
             (z/append-child* new-entry) z/root-string)
         (-> ns-zl (z/append-child* (n/newlines 1)) (z/append-child* (n/spaces 2))
             (z/append-child* (n/list-node [(n/keyword-node :require) (n/newlines 1)
                                            (n/spaces 3) new-entry])) z/root-string))))))

(defn insert-into-require-sorted [src ns-sym alias referred]
  (let [ns-zl (ns-zloc src)]
    (when (nil? ns-zl) (throw (ex-info "Cannot insert require: source has no ns form" {})))
    (let [req-zl (require-form-zloc ns-zl)
          entries (when req-zl (->> (some-> req-zl z/down z/right) (iterate z/right)
                                    (take-while some?) (filter z/vector?) vec))
          comment-bearing? (boolean (and req-zl (some #(= :comment (n/tag %))
                                                       (tree-seq n/inner? n/children (z/node req-zl)))))
          target (when-not comment-bearing?
                   (first (filter #(pos? (compare (str (first (z/sexpr %))) (str ns-sym))) entries)))]
      (if target
        (let [new-entry (entry-node ns-sym alias referred)
              insertion (n/forms-node [new-entry (n/newlines 1) (n/spaces 2)])]
          (-> target (z/insert-left insertion) z/root-string))
        (insert-into-require src ns-sym alias referred)))))

(defn add-require [cljc-src {:keys [platform ns as]}]
  (when-not (#{:clj :cljs :cljc} platform)
    (throw (ex-info "platform must be :clj, :cljs, or :cljc" {:got platform})))
  (let [{clj :clj cljs :cljs} (split/split-file cljc-src)]
    (when (#{:clj :cljc} platform) (check-alias-collision! clj ns as :clj))
    (when (#{:cljs :cljc} platform) (check-alias-collision! cljs ns as :cljs))
    (merge/merge-files
     (if (#{:clj :cljc} platform) (insert-into-require clj ns as) clj)
     (if (#{:cljs :cljc} platform) (insert-into-require cljs ns as) cljs))))
