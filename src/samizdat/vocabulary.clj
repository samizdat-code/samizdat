;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.vocabulary
  "The project's own vocabulary: its public functions and macros, read from
  its source (karamazov-xn0h.2).

  WHY. A Lisp project is written as a language for its domain, and a change
  that follows that language fits the program where one written beside it in
  generic code does not. The model can only follow a language it can see. A
  name, its arguments and its first doc line, per namespace, is that language
  at its cheapest — and small enough that most of a project fits in the
  opening, which is the other half of the argument: a model that sees the
  whole program's shape makes fewer changes that break the parts it did not
  read.

  STATIC, from the source on disk, not the image: it covers namespaces no
  eval has loaded, costs no process, and reads the same for every role,
  including one with no REPL at all. `manual` serves the full index; the
  opening block is the budgeted rendering of it.

  Mechanism only: what counts as vocabulary, how much of it is shown and
  every word around it are gates.edn :vocabulary and prompts/vocabulary.md."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [samizdat.prompt :as prompt]))

;; --- one file ----------------------------------------------------------------

(defn- kids [node]
  (if (n/inner? node)
    (vec (remove n/whitespace-or-comment? (n/children node)))
    []))

(defn- strip-meta
  "`node` without its metadata wrapper, and whether that metadata said
  private."
  [node]
  (loop [nd node private? false]
    (if (= :meta (n/tag nd))
      (let [[m target] (kids nd)
            mv (try (n/sexpr m) (catch Throwable _ nil))]
        (recur target (or private? (= :private mv)
                          (and (map? mv) (true? (:private mv))))))
      [nd private?])))

(defn- string-value [node]
  (when (and node (#{:token :multi-line} (n/tag node)))
    (let [v (try (n/sexpr node) (catch Throwable _ nil))]
      (when (string? v) v))))

(defn- first-line [s limit]
  (when-let [line (some-> s str/split-lines first str/trim not-empty)]
    (if (> (count line) limit) (str (subs line 0 limit) "…") line)))

(defn- squash [s] (str/replace (str/trim s) #"\s+" " "))

(defn- arglists
  "The argument vectors after a defn-like form's name, docstring and attr map:
  one vector, or the first vector of each `([args] body)` arity."
  [after-name]
  (let [body (drop-while #(or (string-value %) (= :map (n/tag %))) after-name)
        head (first body)]
    (cond
      (nil? head) []
      (= :vector (n/tag head)) [(squash (n/string head))]
      :else (vec (keep (fn [arity]
                         (when (= :list (n/tag arity))
                           (when-let [v (first (kids arity))]
                             (when (= :vector (n/tag v)) (squash (n/string v))))))
                       body)))))

(defn- public-entry
  "The vocabulary entry for one top-level form, or nil when it is not a
  public definition of a listed kind."
  [node {:keys [kinds doc-chars]}]
  (when (= :list (n/tag node))
    (let [[hd nm & more] (kids node)
          head (some-> hd n/string)
          kind (get kinds head)]
      (when (and kind nm)
        (let [[nm private?] (strip-meta nm)]
          (when (and (= :token (n/tag nm)) (not private?))
            (let [doc (when (or (not= "var" kind) (next more))
                        (string-value (first more)))]
              (cond-> {:name (n/string nm) :kind kind
                       :arglists (if (#{"fn" "macro"} kind)
                                   (arglists more)
                                   (if (#{"record" "type"} kind)
                                     (if-let [v (first more)]
                                       (if (= :vector (n/tag v)) [(squash (n/string v))] [])
                                       [])
                                     []))}
                doc (assoc :doc (first-line doc doc-chars) :docstring doc)
                (= "macro" kind) (assoc :macro true)))))))))

(defn namespace-entry
  "`text`'s namespace and its public vocabulary, as
  `{:ns :doc :publics [{:name :kind :arglists :doc :macro}]}` in source
  order, or nil when the text has no `ns` form or does not parse."
  [text policy]
  (when-let [forms (try (vec (remove n/whitespace-or-comment?
                                     (n/children (p/parse-string-all (str text)))))
                        (catch Throwable _ nil))]
    (when-let [ns-node (first (filter #(and (= :list (n/tag %))
                                            (= "ns" (some-> (first (kids %)) n/string)))
                                      forms))]
      (let [[_ nm maybe-doc] (kids ns-node)
            [nm _] (some-> nm strip-meta)]
        (when nm
          (cond-> {:ns (n/string nm)
                   :publics (vec (keep #(public-entry % policy) forms))}
            (string-value maybe-doc)
            (assoc :doc (first-line (string-value maybe-doc) (:doc-chars policy)))))))))

;; --- the project ---------------------------------------------------------------

(defn source-paths
  "The project's source paths for its vocabulary: deps.edn `:paths` (or the
  policy's `:fallback-paths`) and the `:extra-paths` of each alias the policy
  names in `:aliases`. Tests and tooling under other aliases are not the
  domain language; a dev alias is where the project's own REPL helpers live
  (karamazov-xn0h.8), so the shipped policy names :dev."
  [root {:keys [fallback-paths aliases]}]
  (let [f (io/file root "deps.edn")
        d (when (.exists f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))
        main (or (seq (:paths d)) fallback-paths)
        extra (mapcat #(get-in d [:aliases (keyword (name %)) :extra-paths]) aliases)]
    (->> (concat main extra)
         distinct
         (mapv (fn [path]
                 (let [pf (io/file path)]
                   (str (if (.isAbsolute pf) pf (io/file root path)))))))))

(defn- source-files [dir {:keys [extensions max-files]}]
  (when (fs/directory? dir)
    (->> (fs/glob dir "**/*")
         (map str)
         (filter (fn [p] (some #(str/ends-with? p %) extensions)))
         (filter fs/regular-file?)
         sort
         (take max-files))))

(defn index
  "Every namespace under `root`'s main source paths with its public
  vocabulary, sorted by namespace name. Files over `:max-file-chars` and
  past `:max-files` are skipped; one that does not parse is simply absent."
  [root {:keys [max-file-chars] :as policy}]
  (->> (source-paths root policy)
       (mapcat #(source-files % policy))
       (keep (fn [f]
               (when (<= (or (try (fs/size f) (catch Throwable _ nil)) Long/MAX_VALUE)
                         max-file-chars)
                 (some-> (try (slurp f) (catch Throwable _ nil))
                         (namespace-entry policy)))))
       (sort-by :ns)
       vec))

(defn lookup
  "What `wanted` names in `entries` (from `index`): `{:namespace entry}` for a
  namespace, `{:var public :ns name}` for `ns/var`, or nil."
  [entries wanted]
  (let [w (str wanted)
        by-ns (into {} (map (juxt :ns identity)) entries)]
    (if-let [e (by-ns w)]
      {:namespace e}
      (when-let [slash (str/last-index-of w "/")]
        (let [nsn (subs w 0 slash) vn (subs w (inc slash))]
          (when-let [p (some #(when (= vn (:name %)) %) (:publics (by-ns nsn)))]
            {:var p :ns nsn}))))))

(defn render
  "`entries` rendered through prompts/vocabulary.md within `:max-chars`, as
  `{:block :namespaces :omitted :brief?}`, or nil when not even one namespace
  fits.

  Detail goes before coverage: every namespace with names, arguments and doc
  lines; then every namespace with its names alone (the `:brief` tier); only
  then whole namespaces from the end, with the block saying how many it left
  out. A mid-size project keeps its whole language in view that way, at the
  cost of the arguments `manual` can still give. With `tier` (:full or
  :brief) only that tier is tried."
  ([entries policy]
   (or (render entries policy :full nil)
       (render entries policy :brief)))
  ([entries policy tier] (render entries policy tier ::drop))
  ([entries {:keys [max-chars]} tier drop?]
   (let [total (count entries)
         brief? (= :brief tier)
         rendered (fn [k]
                    (let [omitted (- total k)]
                      (prompt/render "vocabulary"
                                     {:namespaces (take k entries)
                                      :brief brief?
                                      :omitted (when (pos? omitted) omitted)
                                      :many-omitted (> omitted 1)})))]
     (loop [k total]
       (when (pos? k)
         (let [block (rendered k)]
           (cond
             (<= (count block) max-chars)
             {:block block :namespaces k :omitted (- total k) :brief? brief?}

             drop? (recur (dec k)))))))))

(defn block
  "The opening's vocabulary block for the project at `root`, as
  `{:block :namespaces :omitted}`, or nil when the policy is off, there is no
  root, or the project has no Clojure namespaces to show."
  [root policy]
  (when (and (:enabled? policy) root)
    ;; A namespace with nothing public of a listed kind is a heading over
    ;; an empty list: no vocabulary, and space the budget needs.
    (let [entries (filterv (comp seq :publics) (index root policy))]
      (when (seq entries)
        (render entries policy)))))
