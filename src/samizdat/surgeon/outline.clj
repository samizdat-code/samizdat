;; Ported from xi, https://github.com/floscr/xi (commit 1701ddd),
;; src/xi/ext/clj_surgeon, MIT License, Copyright (c) 2026 Florian Schroedl.
;; Copied as written and adapted only where samizdat needs it
;; (karamazov-0e2c.24); the original notice is in docs/third-party/xi-LICENSE.
;; Source-only form records adapted from clj-surgeon source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907, MIT License,
;; Copyright (c) 2026 Gene Kim; see docs/third-party/clj-surgeon-LICENSE.
;;
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.outline
  "Parse a Clojure file and return structured outline of all top-level forms.
   For CLJC files (and any file containing reader conditionals), forms inside
   #?(:clj ...) / #?@(:cljs [...]) are surfaced too, each tagged with the
   platforms it applies to."
  (:require [rewrite-clj.zip :as z]
            [rewrite-clj.node :as n]
            [samizdat.surgeon.cljc.walk :as cwalk]
            [samizdat.surgeon.forms :as forms]
            [clojure.string :as str]))

(defn- resolve-user-fields
  "Run each user-supplied extractor fn against the form zloc. Returns a
   map field-key -> value, omitting nil results.

   When an extractor throws, attach context so the user can find the
   broken field — which macro, which field key, which form line."
  [user-fields zloc type-str line]
  (into {} (for [[k f] user-fields
                 :let [v (try (f zloc)
                              (catch Exception e
                                (throw (ex-info
                                        (str ".samizdat.surgeon.edn: extractor for "
                                             type-str " :fields " k
                                             " threw at line " line ": "
                                             (.getMessage e))
                                        {:macro type-str
                                         :field k
                                         :line line}
                                        e))))]
                 :when (some? v)]
             [k v])))

(defn- extract-name
  "Get the name from the second child of a form. Handles metadata like ^:private.
   Walks past meta nodes to find the actual symbol name."
  [zloc]
  (loop [child (some-> zloc z/down z/right)]
    (when child
      (let [s (z/string child)
            tag (n/tag (z/node child))]
        ;; Skip metadata nodes (^:private, ^:dynamic, ^String, etc.)
        (if (= :meta tag)
          ;; Meta node wraps the actual symbol — get the last child
          (let [inner (some-> child z/down z/rightmost z/string)]
            (or inner s))
          ;; Regular symbol
          (if (or (= :token tag) (= :symbol tag))
            s
            (recur (z/right child))))))))

(defn- extract-arglist
  "Get arglist from a defn form. Descends into :meta nodes so meta-tagged
   arglists like `^String [k]` are found."
  [zloc]
  (let [type-str (some-> zloc z/down z/string)]
    (when (forms/has-arglists? type-str)
      ;; Walk children to find first vector (the arglist)
      ;; Meta nodes (^String [k]) wrap the vector — descend into them
      (loop [child (some-> zloc z/down)]
        (when child
          (let [tag (n/tag (z/node child))]
            (cond
              (= :vector tag) (z/string child)
              (= :meta tag)   (let [inner (some-> child z/down z/rightmost)]
                                (if (and inner (= :vector (n/tag (z/node inner))))
                                  (z/string inner)
                                  (recur (z/right child))))
              :else           (recur (z/right child)))))))))

(defn attached-comment-start
  "Look backwards from a form's start line to find attached comment lines.
   Comments must be contiguous (no blank lines between them and the form)."
  [lines form-line]
  (let [idx (dec form-line)]
    (loop [i (dec idx), comment-start form-line]
      (if (neg? i)
        comment-start
        (let [line (str/trim (nth lines i ""))]
          (if (str/starts-with? line ";")
            (recur (dec i) (inc i))
            comment-start))))))

(defn- file-extension [file]
  (let [s (str file)
        i (.lastIndexOf s ".")]
    (when (pos? i) (subs s (inc i)))))

(defn extract-ns-requires
  "Extract require entries from a (ns ...) zipper location.
   Returns a vector of strings like [\"[clojure.string :as str]\"]
   or nil if no :require block found."
  [ns-zloc]
  (when ns-zloc
    (let [require-form (->> (z/down ns-zloc)
                            (iterate z/right)
                            (take-while some?)
                            (filter #(and (z/list? %)
                                          (= ":require" (some-> % z/down z/string))))
                            first)]
      (when require-form
        (->> (z/down require-form)
             (iterate z/right)
             (take-while some?)
             (filter z/vector?)
             (mapv z/string))))))

(defn top-level-form-records
  "Return every parsed top-level list form in source order, including exact
   source for structural readers. Project aliases are explicit, never read
   from the process-global configuration atom."
  ([file source]
   (top-level-form-records file source {}))
  ([file source project-aliases]
   (let [lines (str/split-lines source)
         defaults (cwalk/platforms-for-extension (file-extension file))
         walked (cwalk/top-level-forms source defaults)]
     (mapv (fn [{:keys [zloc platforms]}]
             (let [m (meta (z/node zloc))
                   type-str (some-> zloc z/down z/string)
                   form-spec (forms/spec-with-project-aliases
                               project-aliases type-str)
                   user-fields (:fields form-spec)
                   extracted (when user-fields
                               (resolve-user-fields user-fields zloc
                                                    type-str (:row m)))
                   name-val (cond
                              user-fields (:name extracted)
                              (some? form-spec) (extract-name zloc))
                   arglist (cond
                             user-fields (:arglist extracted)
                             name-val (extract-arglist zloc))
                   form-line (:row m)
                   comment-start (when form-line
                                   (attached-comment-start lines form-line))
                   extras (when extracted
                            (dissoc extracted :name :arglist))]
               (cond-> {:type (symbol (or type-str "?"))
                        :platforms (vec (sort platforms))
                        :source (z/string zloc)}
                 form-line (assoc :line form-line)
                 (:end-row m) (assoc :end-line (:end-row m))
                 name-val (assoc :name (if (symbol? name-val)
                                         name-val
                                         (symbol (str name-val))))
                 arglist (assoc :args arglist)
                 (seq extras) (merge extras)
                 (and form-line comment-start (< comment-start form-line))
                 (assoc :comment-start comment-start))))
           walked))))

(defn outline-source
  "Return the compact outline for a filename and source string. Complete
   form source remains internal to `top-level-form-records`."
  ([file source]
   (outline-source file source {}))
  ([file source project-aliases]
   (let [records (top-level-form-records file source project-aliases)
         zloc (z/of-string source {:track-position? true})
         ns-zloc (some-> zloc
                         (z/find-value z/next 'ns)
                         z/up)
         ns-name (some-> ns-zloc z/down z/right z/string symbol)
         requires (extract-ns-requires ns-zloc)]
     {:ns ns-name
      :file file
      :lines (count (str/split-lines source))
      :form-count (count (filter :name records))
      :forms (->> records
                  (remove #(= 'ns (:type %)))
                  (mapv #(dissoc % :source)))
      :requires (or requires [])
      :forward-refs []})))

(defn outline
  "Return outline of all top-level forms in a Clojure file.
   Returns EDN map with :ns, :file, :lines, :forms, :forward-refs.

   Each form includes :platforms — the set of platforms (#{:clj}, #{:cljs},
   #{:clj :cljs}, etc.) under which it appears. For .clj/.cljs files this
   reflects the file extension; for .cljc files it surfaces reader-conditional
   structure, so a `#?(:clj (defn foo ...))` shows up as a real form with
   :platforms #{:clj}."
  [file]
  (outline-source file (slurp file) @forms/project-aliases))
