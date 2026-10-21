;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.atlas.clojure
  "Clojure source into the code graph (RFC-016), with the host reader and
  nothing else: no clj-kondo, no evaluation, so it reads any project,
  including one that does not load.

  Each top-level `def…` form is a node under its namespace; namespaces sit
  under groups named for their prefix (`samizdat.agent.*`). A symbol in a
  definition's body that resolves — through the namespace's aliases and
  refers, a fully qualified name, or a definition of its own namespace —
  to another definition is an edge, its site the enclosing list. In head
  position it is a call; anywhere else (`(mapv handle xs)`, `@pool`) a
  reference, marked :ref?, because a function handed on is still reached.
  Locals bound by fn params and let-like forms shadow definitions.

  Approximate where reading cannot be exact: a macro's expansion is not
  seen, and a call through a protocol or a multimethod's dispatch lands on
  the protocol or the multimethod. Each defmethod is a node of its own
  (`ns/multi :dispatch-value`) that its multimethod dispatches to, so the
  methods of one multimethod read as separate paths rather than one hub.

  The host reader refuses `::alias/kw` without the alias loaded and
  compiles regex literals, so `sanitize` blanks both first without moving a
  column. A form the reader still chokes on ends that file; what came before
  it is kept and the error is listed under the graph's metadata :errors."
  (:require [clojure.string :as str]
            [samizdat.atlas.graph :as g]))

;; --- text --------------------------------------------------------------------

(defn- scan
  "Fold (f acc state i c) over `text` from offset `from`, state :code,
  :string or :comment for the char c at offset i. A character literal's
  escaped char is skipped. Stops early on a reduced acc."
  [^String text from acc f]
  (let [n (count text)]
    (loop [i from st :code acc acc]
      (if (>= i n)
        acc
        (let [c (.charAt text i)
              acc (f acc st i c)]
          (if (reduced? acc)
            @acc
            (case st
              :code (cond (= c \;) (recur (inc i) :comment acc)
                          (= c \") (recur (inc i) :string acc)
                          (= c \\) (recur (+ i 2) :code acc)
                          :else (recur (inc i) :code acc))
              :string (cond (= c \\) (recur (+ i 2) :string acc)
                            (= c \") (recur (inc i) :code acc)
                            :else (recur (inc i) :string acc))
              :comment (recur (inc i) (if (= c \newline) :code :comment) acc))))))))

(defn sanitize
  "`text` with what the host reader refuses blanked, column for column:
  the first colon of an auto-resolved keyword, and the # of a regex literal
  with every escape in it, so it reads as a plain string. Strings and
  comments are left alone."
  [^String text]
  (let [n (count text)
        at (fn [i] (when (< -1 i n) (.charAt text i)))
        {:keys [blank]}
        (scan text 0 {:blank [] :regex? false}
              (fn [{:keys [regex?] :as acc} st i c]
                (cond
                  (and (= st :string) regex? (= c \\)) (update acc :blank conj i (inc i))
                  (and (= st :string) (= c \")) (assoc acc :regex? false)
                  (not= st :code) acc
                  (and (= c \:) (= \: (at (inc i))) (not= \: (at (dec i)))) (update acc :blank conj i)
                  (and (= c \#) (= \" (at (inc i)))) (-> acc (update :blank conj i) (assoc :regex? true))
                  :else acc)))
        sb (StringBuilder. text)]
    (doseq [i blank :when (< i n)] (.setCharAt sb (int i) \space))
    (str sb)))

(defn- offset-of
  "The offset of 1-based `line` and `col` in `text`."
  [^String text line col]
  (loop [i 0 l 1]
    (cond (= l line) (+ i (dec col))
          :else (let [j (.indexOf text "\n" (int i))]
                  (if (neg? j) (count text) (recur (inc j) (inc l)))))))

(defn form-end
  "The line on which the form opening at `line`/`col` of `text` closes,
  skipping brackets inside strings, comments and character literals; the
  last line when it never closes."
  [^String text line col]
  (let [r (scan text (offset-of text line col) {:depth 0 :line line}
                (fn [{:keys [depth] :as acc} st _ c]
                  (let [acc (if (= c \newline) (update acc :line inc) acc)]
                    (cond
                      (not= st :code) acc
                      (#{\( \[ \{} c) (update acc :depth inc)
                      (#{\) \] \}} c) (if (= 1 depth)
                                        (reduced (assoc acc :closed true))
                                        (update acc :depth dec))
                      :else acc))))]
    (:line r)))

;; --- reading -----------------------------------------------------------------

(defn- read-forms
  "The top-level forms of `text`: [forms error-message-or-nil]."
  [text]
  (let [r (java.io.PushbackReader. (java.io.StringReader. (sanitize text)))
        opts {:eof ::eof :read-cond :allow :features #{:clj}}]
    (binding [*default-data-reader-fn* (fn [_ v] v)]
      (loop [acc []]
        (let [f (try (read opts r) (catch Throwable e {::error (ex-message e)}))]
          (cond (= ::eof f) [acc nil]
                (and (map? f) (::error f)) [acc (::error f)]
                :else (recur (conj acc f))))))))

;; --- namespaces --------------------------------------------------------------

(defn- libspecs
  "Flatten a :require/:use clause's specs into [ns-sym opts-map], prefix
  lists included."
  [specs]
  (mapcat (fn [s]
            (let [s (if (and (seq? s) (= 'quote (first s))) (second s) s)]
              (cond
                (symbol? s) [[s {}]]
                (and (sequential? s) (symbol? (first s))
                     (or (empty? (rest s)) (keyword? (second s))))
                [[(first s) (apply hash-map (rest s))]]
                (and (sequential? s) (symbol? (first s)))
                (for [[sub opts] (libspecs (rest s))]
                  [(symbol (str (first s) "." sub)) opts])
                :else [])))
          specs))

(defn- ns-env
  "The aliases, refers and refer-alls the ns form `form` sets up."
  [form]
  (reduce (fn [env clause]
            (if (and (seq? clause) (#{:require :use} (first clause)))
              (reduce (fn [env [lib {:keys [as refer]}]]
                        (let [lib (str lib)
                              all? (or (= :all refer) (= :use (first clause)))]
                          (cond-> env
                            as (assoc-in [:aliases (str as)] lib)
                            (sequential? refer) (update :refers into (map (fn [r] [(str r) lib])) refer)
                            all? (update :refer-all conj lib))))
                      env (libspecs (rest clause)))
              env))
          {:aliases {} :refers {} :refer-all []}
          (drop 2 form)))

(defn- first-line [s]
  (some-> s str/trim (str/split #"\n") first str/trim not-empty))

(defn- group-of
  "The group a namespace sits in: its prefix with `.*`, or nil at the top."
  [ns-name]
  (let [i (str/last-index-of ns-name ".")]
    (when (and i (pos? i)) (str (subs ns-name 0 i) ".*"))))

(defn- group-nodes
  "The prefix groups above `ns-name`, each under the next one up."
  [ns-name]
  (loop [acc [] gid (group-of ns-name)]
    (if gid
      (let [prefix (subs gid 0 (- (count gid) 2))
            up (group-of prefix)]
        (recur (conj acc {:id gid :label gid :kind :group :parent up}) up))
      acc)))

;; --- definitions -------------------------------------------------------------

(def ^:private kinds
  {'defn :fn 'defn- :fn 'defmacro :macro 'defmulti :multi 'def :var 'defonce :var
   'defcell :cell 'deftest :test 'defprotocol :protocol 'defrecord :type 'deftype :type})

(def ^:private keyword-named
  ;; Defining forms whose name is a keyword: mycelium's cells, which the
  ;; workflow manifests name.
  '#{defcell})

(defn- def-head
  "The unqualified head of a defining form, or nil."
  [form]
  (when (and (seq? form) (symbol? (first form)))
    (let [h (symbol (name (first form)))]
      (when (and (str/starts-with? (name h) "def") (not= 'declare h)
                 (or (symbol? (second form))
                     (and (keyword-named h) (keyword? (second form)))))
        h))))

(defn- def-kind [head form]
  (or (when (and (#{'def 'defonce} head) (seq? (last form))
                 (#{'fn 'fn*} (first (last form))))
        :fn)
      (kinds head)
      :def))

(defn- doc-of [head form]
  (cond (#{'defn 'defn- 'defmacro 'defmulti} head) (when (string? (nth form 2 nil)) (nth form 2))
        (#{'def 'defonce} head) (when (and (= 4 (count form)) (string? (nth form 2))) (nth form 2))
        :else nil))

;; --- bodies ------------------------------------------------------------------

(def ^:private let-like
  '#{let let* loop loop* when-let if-let when-some if-some when-first doseq for
     dotimes with-open with-local-vars binding with-redefs})

(defn- bound-syms
  "The symbols a binding form binds, through destructuring."
  [b]
  (cond (symbol? b) (if (= '& b) [] [b])
        (vector? b) (mapcat bound-syms b)
        (map? b) (concat (mapcat (fn [[k v]]
                                   (cond (= :keys k) (map (comp symbol name) v)
                                         (#{:strs :syms} k) (map (comp symbol name) v)
                                         (= :as k) [v]
                                         (= :or k) []
                                         (keyword? k) []
                                         :else (bound-syms k)))
                                 b))
        :else []))

(defn- fn-params
  "The params a fn-like form binds, over all its arities."
  [args]
  (let [args (if (symbol? (first args)) (rest args) args)
        args (drop-while #(or (string? %) (map? %)) args)]
    (if (vector? (first args))
      (bound-syms (first args))
      (mapcat #(when (and (seq? %) (vector? (first %))) (bound-syms (first %))) args))))

(defn- refs
  "Every [symbol call? site] in `form` not shadowed by `locals`, the site
  the nearest enclosing list's {:line :col}, `site` outside any."
  [form locals site]
  (cond
    (symbol? form) (when-not (locals form) [[form false site]])
    (seq? form)
    (let [site (if-let [{:keys [line column]} (meta form)]
                 (if line {:line line :col column} site)
                 site)
          [h & args] form
          hname (when (symbol? h) (symbol (name h)))]
      (cond
        (#{'quote 'comment} hname) nil
        (and (let-like hname) (vector? (first args)))
        (let [pairs (partition-all 2 (first args))
              locals' (into locals (mapcat (comp bound-syms first) pairs))]
          (concat (mapcat #(refs (second %) locals' site) pairs)
                  (mapcat #(refs % locals' site) (rest args))))
        (#{'fn 'fn*} hname)
        (let [locals' (cond-> (into locals (fn-params args))
                        (symbol? (first args)) (conj (first args)))]
          (mapcat #(refs % locals' site) args))
        :else
        (concat (when (and (symbol? h) (not (locals h))) [[h true site]])
                (when-not (symbol? h) (refs h locals site))
                (mapcat #(refs % locals site) args))))
    (map? form) (mapcat #(refs % locals site) (mapcat identity form))
    (coll? form) (mapcat #(refs % locals site) form)
    :else nil))

(defn- candidates
  "The ids symbol `s` may name in namespace `ns-name` with `env`, most
  likely first."
  [s ns-name {:keys [aliases refers refer-all]} own]
  (if-let [q (namespace s)]
    [(str (get aliases q q) "/" (name s))]
    (let [n (name s)]
      (cond (own n) [(str ns-name "/" n)]
            (refers n) [(str (refers n) "/" n)]
            :else (map #(str % "/" n) refer-all)))))

;; --- files -------------------------------------------------------------------

(defn extract-file
  "One file's {:nodes :calls :error}: its namespace and definitions, and
  each body's references as {:from :candidates :site :call?}."
  [{:keys [path text]}]
  (let [[forms err] (read-forms text)
        lines (str/split-lines text)
        ns-form (first (filter #(and (seq? %) (= 'ns (first %)) (symbol? (second %))) forms))
        ns-name (if ns-form (str (second ns-form)) (str "file:" path))
        env (if ns-form (ns-env ns-form) {:aliases {} :refers {} :refer-all []})
        defs (keep (fn [f]
                     (when-let [h (def-head f)]
                       (when-not (#{'defmethod} h) [h f])))
                   forms)
        own (set (keep (fn [[_ f]] (when (symbol? (second f)) (name (second f)))) defs))
        id-of (fn [nm] (if (keyword? nm) (str nm) (str ns-name "/" (name nm))))
        source-hash (fn [line end-line]
                      (when line
                        (hash (str/join "\n" (subvec lines (dec line) (min (count lines) end-line))))))
        ns-node {:id ns-name :label ns-name :kind :ns :parent (group-of ns-name) :file path
                 :line (or (:line (meta ns-form)) 1)
                 :doc (when ns-form (first-line (first (filter string? (take 3 (drop 2 ns-form))))))}
        def-nodes (for [[h f] defs
                        :let [{:keys [line column]} (meta f) sym (second f)]]
                    (cond-> (let [end (if line (form-end text line (or column 1)) line)]
                              {:id (id-of sym) :label (if (keyword? sym) (str sym) (name sym))
                               :kind (def-kind h f) :parent ns-name :file path :line line
                               :end-line end :hash (source-hash line end)})
                      (or (= 'defn- h) (:private (meta sym))) (assoc :private? true)
                      (doc-of h f) (assoc :doc (first-line (doc-of h f)))))
        method-id (fn [f] (str (first (candidates (second f) ns-name env own))
                               " " (pr-str (nth f 2 nil))))
        method-nodes (for [f forms
                           :when (= 'defmethod (def-head f))
                           :let [{:keys [line column]} (meta f)]]
                       (let [end (if line (form-end text line (or column 1)) line)]
                         {:id (method-id f) :label (str (name (second f)) " " (pr-str (nth f 2 nil)))
                          :kind :method :parent ns-name :file path :line line
                          :end-line end :hash (source-hash line end)}))
        dispatches (for [f forms
                         :when (= 'defmethod (def-head f))
                         :let [{:keys [line column]} (meta f)]]
                     {:from (first (candidates (second f) ns-name env own))
                      :candidates [(method-id f)] :call? true
                      :site {:file path :line line :col column :dispatch? true}})
        calls (for [f forms
                    :let [h (def-head f)]
                    :when h
                    :let [method? (= 'defmethod h)
                          from (if method? (method-id f) (id-of (second f)))
                          {:keys [line column]} (meta f)
                          body (drop (if method? 3 2) f)]
                    [s call? site] (mapcat #(refs % (set (fn-params body)) {:line line :col column}) body)
                    :let [cs (candidates s ns-name env own)]
                    :when (seq cs)]
                {:from from :candidates cs :call? call?
                 :site (cond-> (assoc site :file path) (not call?) (assoc :ref? true))})]
    {:nodes (concat (group-nodes ns-name) [ns-node] def-nodes method-nodes)
     :calls (concat dispatches calls)
     :error (when err {:file path :message err})}))

(defn extract
  "The code graph of Clojure `files` ({:path :text} each). A reference
  resolves to its first candidate that is a definition read here; one to a
  library this did not read is dropped by graph/build."
  [files]
  (let [per (mapv extract-file files)
        nodes (vals (into {} (map (juxt :id identity)) (mapcat :nodes per)))
        known (set (map :id nodes))
        edges (keep (fn [{:keys [from candidates site]}]
                      (when-let [to (first (filter known candidates))]
                        (when (not= from to)
                          {:from from :to to :sites [site]})))
                    (mapcat :calls per))]
    (with-meta (g/build nodes edges)
      {:errors (vec (keep :error per))})))
