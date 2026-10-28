;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/cljc/split.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.cljc.split
  "Purely split one CLJC source string into CLJ and CLJS sources."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.cljc.merge :as merge]))

(defn- reader-cond? [zloc]
  (and (= :reader-macro (some-> zloc z/node n/tag))
       (#{"?" "?@"} (some-> zloc z/down z/string))))

(defn- splicing-reader-cond? [zloc]
  (and (reader-cond? zloc) (= "?@" (some-> zloc z/down z/string))))

(defn- decode-reader-cond [rmacro-zloc]
  (let [splicing? (splicing-reader-cond? rmacro-zloc)
        pair-list (-> rmacro-zloc z/down z/right)
        children (->> (z/down pair-list) (iterate z/right) (take-while some?))
        pairs (partition 2 children)
        as-strings (fn [zl]
                     (if splicing?
                       (->> (z/down zl) (iterate z/right) (take-while some?) (mapv z/string))
                       [(z/string zl)]))
        platform-map (into {} (for [[k v] pairs]
                                [(keyword (subs (z/string k) 1)) (as-strings v)]))]
    (assoc platform-map :splicing? splicing?)))

(defn- ns-zloc [src]
  (->> (z/of-string src {:track-position? true}) (iterate z/right) (take-while some?)
       (filter #(and (z/list? %) (= "ns" (some-> % z/down z/string)))) first))

(defn- require-form-zloc [ns-zl]
  (->> (-> ns-zl z/down z/right z/right) (iterate z/right) (take-while some?)
       (filter #(and (z/list? %) (= ":require" (some-> % z/down z/string)))) first))

(defn- split-require-block [require-zl]
  (loop [loc (some-> require-zl z/down z/right) clj [] cljs []]
    (cond
      (nil? loc) {:clj clj :cljs cljs}
      (z/vector? loc) (let [s (z/string loc)] (recur (z/right loc) (conj clj s) (conj cljs s)))
      (reader-cond? loc) (let [{c :clj cs :cljs} (decode-reader-cond loc)]
                           (recur (z/right loc) (into clj (or c [])) (into cljs (or cs []))))
      (contains? #{:whitespace :newline :comma} (n/tag (z/node loc)))
      (recur (z/right loc) clj cljs)
      :else
      (throw (ex-info (str "require entry '" (z/string loc) "' not supported in v1")
                      {:entry (z/string loc)})))))

(defn- emit-ns-form [ns-name require-strs]
  (if (seq require-strs)
    (str "(ns " ns-name "\n  (:require\n"
         (str/join "\n" (map #(str "   " %) require-strs)) "))")
    (str "(ns " ns-name ")")))

(defn split-file [cljc-src]
  (let [ns-zl (ns-zloc cljc-src)]
    (when (nil? ns-zl) (throw (ex-info "Input must contain an ns form" {})))
    (let [_ (merge/assert-supported-ns! ns-zl :cljc)
          ns-nm (-> ns-zl z/down z/right z/string)
          req-zl (require-form-zloc ns-zl)
          {clj-reqs :clj cljs-reqs :cljs} (if req-zl (split-require-block req-zl) {:clj [] :cljs []})
          clj-ns-text (emit-ns-form ns-nm clj-reqs)
          cljs-ns-text (emit-ns-form ns-nm cljs-reqs)
          top-forms (->> (z/of-string cljc-src {:track-position? true}) (iterate z/right) (take-while some?))
          after-ns (rest (drop-while #(not (and (z/list? %) (= "ns" (some-> % z/down z/string)))) top-forms))
          clj-bodies (atom []) cljs-bodies (atom [])]
      (doseq [zl after-ns]
        (if (reader-cond? zl)
          (let [{c :clj cs :cljs} (decode-reader-cond zl)]
            (when (seq c) (swap! clj-bodies into c))
            (when (seq cs) (swap! cljs-bodies into cs)))
          (let [s (z/string zl)] (swap! clj-bodies conj s) (swap! cljs-bodies conj s))))
      {:clj (str clj-ns-text (when (seq @clj-bodies) (str "\n\n" (str/join "\n\n" @clj-bodies))) "\n")
       :cljs (str cljs-ns-text (when (seq @cljs-bodies) (str "\n\n" (str/join "\n\n" @cljs-bodies))) "\n")})))
