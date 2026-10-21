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

(ns samizdat.atlas.flows
  "A program as the smaller programs inside it (RFC-016).

  A large program is a superposition of smaller programs that share code;
  each runs from an input to an output. An ENTRY is where one starts (a
  -main, a handler, a tool's method, a public function nothing calls), a
  SINK is where it puts something out (by default a function named with a
  `!`, and anything that calls nothing further). A FLOW is one entry with
  its slice — everything it can reach, the minimal program behind it — and
  its paths to the end, each a step-by-step route through the slice.

  Paths are listed on the call graph with its cycles folded (graph/condense),
  so a recursion is one step and `:loops` says what it holds. They are
  COUNTED first, exactly, and listed only up to the policy's :max-paths and
  :max-depth, so a slice with millions of routes still answers how many.
  Shared prefixes fold into a `trie` for display.

  Functions that serve exactly the same flows form a UNIT: the code one set
  of flows owns. That is the grouping a zoomed-out view draws.

  Pure. What counts as an entry or a sink, and the caps, are the policy
  passed in (gates.edn :atlas); a rule is a map whose every field must
  hold of the node."
  (:require [clojure.string :as str]
            [samizdat.atlas.graph :as g]))

;; --- rules -------------------------------------------------------------------

(defn- matches?
  "Whether node `n` of `gr` meets every field of `rule`."
  [gr n rule]
  (every? (fn [[k v]]
            (case k
              :id (= v (:id n))
              :label (= v (:label n))
              :label-matches (boolean (re-find (re-pattern v) (str (:label n))))
              :id-matches (boolean (re-find (re-pattern v) (str (:id n))))
              :kind (if (coll? v) (contains? (set v) (:kind n)) (= v (:kind n)))
              :private? (= v (boolean (:private? n)))
              :uncalled? (= v (and (empty? (g/callers gr (:id n)))
                                   (not (:private? n))))
              :leaf? (= v (empty? (g/callees gr (:id n))))
              false))
          rule))

(def ^:private callable #{:fn :multi :method :macro :def :var :test})

(defn- picked [gr rules]
  (vec (sort (for [[id n] (:nodes gr)
                   :when (and (callable (:kind n))
                              (some #(matches? gr n %) rules))]
               id))))

(defn entries
  "The ids policy :entries picks, sorted."
  [gr {rules :entries}]
  (picked gr rules))

(defn sinks
  "The ids policy :sinks picks, sorted."
  [gr {rules :sinks}]
  (picked gr rules))

;; --- the condensed graph, once per graph ---------------------------------------

(defonce ^:private last-condensed (atom nil))

(defn- condensed
  "graph/condense of `gr`, kept for the graph last asked about: every flow
  of one graph shares it."
  [gr]
  (let [[k v] @last-condensed]
    (if (identical? k gr)
      v
      (let [c (g/condense gr)
            c (assoc c :rep (mapv first (:members c)))]
        (reset! last-condensed [gr c])
        c))))

(defn- sat+ [a b]
  (if (> a (- Long/MAX_VALUE b)) Long/MAX_VALUE (+ a b)))

(defn path-count
  "How many routes run from `entry` to an end, cycles folded. Exact up to
  Long/MAX_VALUE, where it stays."
  [gr entry]
  (let [{:keys [comp-of dag]} (condensed gr)
        start (comp-of entry)]
    ;; Components are numbered callees first, so every successor of a
    ;; component has a lower number: one pass upward fills the counts.
    (loop [todo (sort (conj (g/reach-set #(dag %) start) start)) counts {}]
      (if-let [[c & more] (seq todo)]
        (let [ss (dag c)]
          (recur more (assoc counts c (if (empty? ss) 1 (reduce sat+ 0 (map counts ss))))))
        (counts start)))))

(defn- slice [gr entry]
  (conj (g/reach-set #(get-in gr [:out %]) entry) entry))

(defn flow
  "Entry `entry`'s flow: {:entry :slice #{ids} :sinks [ids it reaches]
  :path-count n :paths [[ids]] :loops {rep [members]} :truncated?}. Paths
  run to a component that calls nothing further, in a stable order, at
  most :max-paths of them and :max-depth steps each."
  [gr entry {:keys [max-paths max-depth] :as policy}]
  (let [{:keys [comp-of dag members rep]} (condensed gr)
        max-paths (or max-paths Long/MAX_VALUE)
        max-depth (or max-depth Long/MAX_VALUE)
        succ (fn [c] (sort-by rep (dag c)))
        [paths cut?]
        (loop [stack [[(comp-of entry)]] out [] cut? false]
          (cond
            (empty? stack) [out cut?]
            (>= (count out) max-paths) [out true]
            :else
            (let [p (peek stack) stack (pop stack) ss (succ (peek p))]
              (cond
                (empty? ss) (recur stack (conj out p) cut?)
                (>= (count p) max-depth) (recur stack (conj out p) true)
                :else (recur (into stack (map #(conj p %)) (reverse ss)) out cut?)))))
        sl (slice gr entry)
        sink-set (set (sinks gr policy))]
    {:entry entry
     :slice sl
     :sinks (vec (sort (filter sink-set sl)))
     :path-count (path-count gr entry)
     :paths (mapv #(mapv rep %) paths)
     :loops (into {} (for [c (distinct (map comp-of sl))
                           :when (< 1 (count (members c)))]
                       [(rep c) (members c)]))
     :truncated? cut?}))

(defn all
  "Every entry's flow, by entry, at most :max-flows of them."
  [gr {:keys [max-flows] :as policy}]
  (mapv #(flow gr % policy) (cond->> (entries gr policy) max-flows (take max-flows))))

;; --- reading flows --------------------------------------------------------------

(defn trie
  "`paths`, which share a first step, folded on their shared prefixes:
  {:id :count :children [...]}, :count the paths through the node."
  [paths]
  (letfn [(build [ps]
            (let [id (first (first ps))
                  rests (keep #(seq (rest %)) ps)]
              {:id id :count (count ps)
               :children (mapv (fn [[_ grp]] (build grp))
                               (sort-by key (group-by first rests)))}))]
    (when (seq paths) (build paths))))

(defn membership
  "{id #{entry}}: which flows each function serves."
  [flows]
  (reduce (fn [m {:keys [entry slice]}]
            (reduce #(update %1 %2 (fnil conj #{}) entry) m slice))
          {} flows))

(defn through
  "The entries of the flows that reach `id`, sorted."
  [flows id]
  (vec (sort (keep #(when (contains? (:slice %) id) (:entry %)) flows))))

(defn units
  "The functions grouped by the exact set of flows they serve:
  [{:id :flows #{entry} :members [ids]}], ordered by their flows."
  [flows]
  (let [key-of #(str/join "\u0000" (sort %))]
    (->> (membership flows)
         (group-by val)
         (map (fn [[fs ms]] {:flows fs :members (vec (sort (map key ms)))}))
         (sort-by (comp key-of :flows))
         (map-indexed (fn [i u] (assoc u :id (str "unit:" i))))
         vec)))

(defn- jaccard [a b]
  (let [i (count (filter a b))]
    (if (zero? i) 0.0 (/ (double i) (- (+ (count a) (count b)) i)))))

(defn coarsen
  "`units` merged wherever two serve flows that overlap at least
  `similarity` (shared over joined), transitively: each merged unit keeps
  the first one's id and joins the flows and members. 1.0 merges only
  identical flow sets, which units never have."
  [units similarity]
  (let [n (count units)
        v (vec units)
        parent (volatile! (vec (range n)))
        root-of (fn root-of [i] (let [p (@parent i)] (if (= p i) i (root-of p))))]
    (doseq [i (range n) j (range (inc i) n)
            :when (>= (jaccard (:flows (v i)) (:flows (v j))) similarity)]
      (let [a (root-of i) b (root-of j)]
        (when (not= a b) (vswap! parent assoc (max a b) (min a b)))))
    (->> (range n)
         (group-by root-of)
         (sort-by key)
         (mapv (fn [[root is]]
                 {:id (:id (v root))
                  :flows (reduce into #{} (map #(:flows (v %)) is))
                  :members (vec (sort (mapcat #(:members (v %)) is)))})))))

(defn unit-graph
  "`gr` with its functions held by `units` instead of their namespaces, so
  graph/lift zooms through units. A unit is labelled for the members the
  rest of it is entered through (those no other member calls)."
  [gr units]
  (let [unit-of (into {} (for [u units, m (:members u)] [m (:id u)]))
        heads (fn [{:keys [members]}]
                (let [ms (set members)]
                  (filter (fn [m] (not-any? ms (g/callers gr m))) members)))
        label (fn [u] (let [hs (map #(:label (g/node gr %)) (heads u))]
                        (str/join ", " (or (seq hs) [(:label (g/node gr (first (:members u))))]))))
        nodes (concat (for [u units]
                        {:id (:id u) :label (label u) :kind :unit
                         :flows (vec (sort (:flows u))) :size (count (:members u))})
                      (for [[id n] (:nodes gr) :when (unit-of id)]
                        (assoc n :parent (unit-of id))))]
    (g/build nodes (g/edges gr))))
