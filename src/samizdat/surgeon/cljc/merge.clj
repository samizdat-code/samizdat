;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/cljc/merge.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.cljc.merge
  "Purely merge parallel CLJ and CLJS source strings into CLJC."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn parse-ns-form [src]
  (let [zloc (z/of-string src {:track-position? true})]
    (->> zloc (iterate z/right) (take-while some?)
         (filter #(and (z/list? %) (= "ns" (some-> % z/down z/string)))) first)))

(defn- ns-children [ns-zloc]
  (->> (-> ns-zloc z/down z/right z/right) (iterate z/right) (take-while some?)))

(defn assert-supported-ns! [ns-zloc which]
  (doseq [child (ns-children ns-zloc)]
    (let [tag (some-> child z/node n/tag)]
      (cond
        (and (= :token tag) (string? (n/sexpr (z/node child))))
        (throw (ex-info "ns docstring not supported in v1 (would be lost)" {:platform which}))
        (#{:multi-line :string} tag)
        (throw (ex-info "ns docstring not supported in v1 (would be lost)" {:platform which}))
        (= :map tag)
        (throw (ex-info "ns attr-map not supported in v1 (would be lost)" {:platform which}))
        (z/list? child)
        (let [head (some-> child z/down z/string)]
          (when-not (= ":require" head)
            (throw (ex-info (str "ns sub-form '" head "' not supported in v1")
                            {:platform which :sub-form head}))))))))

(defn require-vector-nodes [ns-zloc]
  (when ns-zloc
    (let [require-form (->> (ns-children ns-zloc)
                            (filter #(and (z/list? %) (= ":require" (some-> % z/down z/string)))) first)]
      (when require-form
        (let [entries (->> (some-> require-form z/down z/right)
                           (iterate z/right) (take-while some?)
                           (remove #(contains? #{:whitespace :newline :comma}
                                               (n/tag (z/node %))))
                           vec)]
          (doseq [entry entries :when (not (z/vector? entry))]
            (throw (ex-info (str "require entry '" (z/string entry) "' not supported in v1")
                            {:entry (z/string entry)})))
          (mapv z/node entries))))))

(defn vec-node->info [v-node]
  (let [sexpr (n/sexpr v-node) ns-key (first sexpr)
        as-i (.indexOf (vec sexpr) :as)
        alias (when (pos? as-i) (str (nth sexpr (inc as-i))))]
    {:ns ns-key :alias alias :node v-node :sexpr sexpr}))

(defn classify-requires [clj-vecs cljs-vecs]
  (let [by-ns-clj (into {} (map (juxt :ns identity) clj-vecs))
        by-ns-cljs (into {} (map (juxt :ns identity) cljs-vecs))
        by-alias-clj (into {} (keep (fn [r] (when (:alias r) [(:alias r) r])) clj-vecs))
        by-alias-cljs (into {} (keep (fn [r] (when (:alias r) [(:alias r) r])) cljs-vecs))
        all-aliases (set (concat (keys by-alias-clj) (keys by-alias-cljs)))
        divergent-alias-set (set (for [a all-aliases :let [c (get by-alias-clj a) s (get by-alias-cljs a)]
                                       :when (and c s (not= (:ns c) (:ns s)))] a))
        alias-divergent? #(contains? divergent-alias-set (:alias %))
        clj-vecs' (remove alias-divergent? clj-vecs)
        cljs-vecs' (remove alias-divergent? cljs-vecs)
        ns-divergent-set (set (for [r clj-vecs' :let [m (get by-ns-cljs (:ns r))]
                                    :when (and m (not= (:sexpr r) (:sexpr m)))] (:ns r)))
        ns-divergent? #(contains? ns-divergent-set (:ns %))
        shared (filter (fn [r] (when-let [m (get by-ns-cljs (:ns r))] (= (:sexpr r) (:sexpr m)))) clj-vecs')
        clj-only (remove #(or (ns-divergent? %) (contains? by-ns-cljs (:ns %))) clj-vecs')
        cljs-only (remove #(or (ns-divergent? %) (contains? by-ns-clj (:ns %))) cljs-vecs')
        alias-divergent (for [a (sort divergent-alias-set)] {:clj (get by-alias-clj a) :cljs (get by-alias-cljs a)})
        ns-divergent (for [k (sort-by str ns-divergent-set)] {:clj (get by-ns-clj k) :cljs (get by-ns-cljs k)})]
    {:shared (vec shared) :clj-only (vec clj-only) :cljs-only (vec cljs-only)
     :divergent (vec (concat alias-divergent ns-divergent))}))

(defn- top-level-zlocs [src]
  (->> (z/of-string src {:track-position? true}) (iterate z/right) (take-while some?)))

(defn- post-ns-form-strings [src]
  (let [forms (top-level-zlocs src)
        after-ns (drop-while #(not (and (z/list? %) (= "ns" (some-> % z/down z/string)))) forms)]
    (when (seq after-ns) (mapv z/string (rest after-ns)))))

(defn- emit-vec [v-info] (n/string (:node v-info)))
(defn- ns-key-string [v-info] (let [k (:ns v-info)] (if (string? k) k (str k))))

(defn- emit-platform-splice [plat-clj plat-cljs]
  (let [indent "   " clj-vecs (sort-by ns-key-string plat-clj) cljs-vecs (sort-by ns-key-string plat-cljs)
        clj-strs (map emit-vec clj-vecs) cljs-strs (map emit-vec cljs-vecs)]
    (cond
      (and (seq plat-clj) (seq plat-cljs))
      (str indent "#?@(:clj  [" (str/join " " clj-strs) "]\n"
           indent "    :cljs [" (str/join "\n              " cljs-strs) "])")
      (seq plat-clj) (str indent "#?@(:clj [" (str/join " " clj-strs) "])")
      (seq plat-cljs) (str indent "#?@(:cljs [" (str/join " " cljs-strs) "])"))))

(defn- emit-require-block [{:keys [shared clj-only cljs-only divergent]}]
  (let [indent "   " shared-sorted (sort-by ns-key-string shared)
        shared-lines (map #(str indent (emit-vec %)) shared-sorted)
        platform-block (emit-platform-splice (concat (map :clj divergent) clj-only)
                                             (concat (map :cljs divergent) cljs-only))
        body-lines (cond-> (vec shared-lines) platform-block (conj platform-block))]
    (when (seq body-lines) (str "(:require\n" (str/join "\n" body-lines) ")"))))

(defn- emit-body-form [clj-s cljs-s]
  (if (= clj-s cljs-s) clj-s (str "#?(:clj  " clj-s "\n   :cljs " cljs-s ")")))

(defn- emit-strict-body-split [clj-bodies cljs-bodies]
  (let [clj-block (str/join "\n\n             " clj-bodies)
        cljs-block (str/join "\n\n             " cljs-bodies)]
    (cond
      (and (seq clj-bodies) (seq cljs-bodies))
      [(str "#?@(:clj  [" clj-block "]\n    :cljs [" cljs-block "])")]
      (seq clj-bodies) [(str "#?@(:clj [" clj-block "])")]
      (seq cljs-bodies) [(str "#?@(:cljs [" cljs-block "])")]
      :else [])))

(defn- emit-bodies [clj-bodies cljs-bodies]
  (if (= (count clj-bodies) (count cljs-bodies))
    (mapv emit-body-form clj-bodies cljs-bodies)
    (emit-strict-body-split clj-bodies cljs-bodies)))

(defn merge-files [clj-src cljs-src]
  (let [clj-ns (parse-ns-form clj-src) cljs-ns (parse-ns-form cljs-src)]
    (when (or (nil? clj-ns) (nil? cljs-ns))
      (throw (ex-info "Both inputs must contain an ns form" {})))
    (assert-supported-ns! clj-ns :clj)
    (assert-supported-ns! cljs-ns :cljs)
    (let [ns-name (-> clj-ns z/down z/right z/string)
          ns-name-cljs (-> cljs-ns z/down z/right z/string)]
      (when-not (= ns-name ns-name-cljs)
        (throw (ex-info "ns names differ" {:clj ns-name :cljs ns-name-cljs})))
      (let [classed (classify-requires (mapv vec-node->info (require-vector-nodes clj-ns))
                                       (mapv vec-node->info (require-vector-nodes cljs-ns)))
            require-block (emit-require-block classed)
            bodies (emit-bodies (or (post-ns-form-strings clj-src) [])
                                (or (post-ns-form-strings cljs-src) []))
            ns-line (if require-block (str "(ns " ns-name "\n  " require-block ")")
                        (str "(ns " ns-name ")"))
            body-text (when (seq bodies) (str "\n\n" (str/join "\n\n" bodies)))]
        (str ns-line body-text "\n")))))
