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

(ns samizdat.atlas.graph
  "The code graph (RFC-016): what a program is made of and what calls what.

  A graph is {:nodes {id node} :edges {[from to] edge} :out {id #{id}}
  :in {id #{id}} :kids {id #{id}}}. A node is {:id :label :kind :parent
  ...}: :parent is the containment hierarchy (a group holds namespaces, a
  namespace holds functions), and every extractor fills it, so zooming is
  the same operation whatever the language. An edge is {:from :to :sites},
  :sites the call sites the extractor saw; naming an edge twice adds its
  sites to the one edge.

  Pure functions only. Which nodes are entries, how deep a neighbourhood is
  shown and what gets grouped are decisions made elsewhere."
  (:refer-clojure :exclude [ancestors]))

(defn build
  "The graph of `nodes` and `edges`. An edge whose end is not a node — a
  call into a library the extractor did not read — is dropped."
  [nodes edges]
  (let [ns (into {} (map (juxt :id identity)) nodes)
        es (reduce (fn [m {:keys [from to sites] :as e}]
                     (if (and (ns from) (ns to))
                       (if-let [old (m [from to])]
                         (assoc m [from to] (update old :sites (fnil into []) sites))
                         (assoc m [from to] (assoc e :sites (vec sites))))
                       m))
                   {} edges)]
    {:nodes ns
     :edges es
     :out (reduce (fn [m [f t]] (update m f (fnil conj #{}) t)) {} (keys es))
     :in (reduce (fn [m [f t]] (update m t (fnil conj #{}) f)) {} (keys es))
     :kids (reduce (fn [m {:keys [id parent]}]
                     (if (and parent (ns parent)) (update m parent (fnil conj #{}) id) m))
                   {} nodes)}))

(defn node [g id] (get-in g [:nodes id]))

(defn edge [g from to] (get-in g [:edges [from to]]))

(defn edges
  "Every edge, ordered by its ends."
  [g]
  (mapv val (sort-by key (:edges g))))

(defn callees [g id] (vec (sort (get-in g [:out id]))))

(defn callers [g id] (vec (sort (get-in g [:in id]))))

(defn- reach
  "{id depth} of what `step` reaches from `id` within `depth` steps, `id`
  itself excluded, each at its least depth."
  [step id depth]
  (loop [seen {id 0} frontier [id] d 1]
    (if (or (> d depth) (empty? frontier))
      (dissoc seen id)
      (let [nxt (->> frontier (mapcat step) distinct (remove seen) vec)]
        (recur (into seen (map (fn [n] [n d])) nxt) nxt (inc d))))))

(defn butterfly
  "Node `id` with its callers and its callees out to `depth` calls each way:
  {:id :callers {id depth} :callees {id depth}}."
  [g id depth]
  {:id id
   :callers (reach #(get-in g [:in %]) id depth)
   :callees (reach #(get-in g [:out %]) id depth)})

(defn ancestors
  "The groups holding `id`, outermost first."
  [g id]
  (loop [acc () p (:parent (node g id))]
    (if (and p (node g p))
      (recur (conj acc p) (:parent (node g p)))
      (vec acc))))

(defn children [g id] (vec (sort (get-in g [:kids id]))))

(defn roots
  "The nodes nothing contains."
  [g]
  (vec (sort (keep (fn [[id n]] (when-not (and (:parent n) (node g (:parent n))) id))
                   (:nodes g)))))

(defn leaves
  "The nodes under `id` that hold nothing, or every such node."
  ([g] (vec (sort (remove #(seq (get-in g [:kids %])) (keys (:nodes g))))))
  ([g id]
   (loop [acc [] todo [id]]
     (if-let [[x & more] (seq todo)]
       (let [ks (get-in g [:kids x])]
         (if (seq ks) (recur acc (into (vec more) ks)) (recur (conj acc x) more)))
       (vec (sort (remove #{id} acc)))))))

(defn representative
  "What node `id` is drawn as when only the groups in `expanded` are open:
  its outermost closed ancestor, itself when all of them are open, nil when
  it is an open group (its children stand in for it)."
  [g expanded id]
  (or (some #(when-not (expanded %) %) (ancestors g id))
      (when-not (and (expanded id) (seq (get-in g [:kids id]))) id)))

(defn lift
  "The graph at a zoom level: the nodes visible with `expanded` open, and
  every call lifted to the visible nodes holding its ends, counted. A call
  inside one visible node is not drawn."
  [g expanded]
  (let [rep (memoize #(representative g expanded %))
        nodes (vec (sort (distinct (keep rep (keys (:nodes g))))))
        lifted (frequencies (for [[f t] (keys (:edges g))
                                  :let [a (rep f) b (rep t)]
                                  :when (and a b (not= a b))]
                              [a b]))]
    {:nodes nodes
     :edges (mapv (fn [[[a b] n]] {:from a :to b :count n}) (sort-by key lifted))}))

(defn scc
  "The strongly connected components of the call edges, Tarjan's algorithm
  with an explicit stack so a deep call chain cannot overflow. Each is a
  sorted vector of ids, in reverse topological order (callees first)."
  [g]
  (let [succ (fn [v] (vec (sort (get-in g [:out v]))))
        index (volatile! {}) low (volatile! {}) on (volatile! #{})
        stack (volatile! []) out (volatile! []) counter (volatile! 0)
        visit (fn [root]
                (vswap! index assoc root @counter) (vswap! low assoc root @counter)
                (vswap! counter inc) (vswap! stack conj root) (vswap! on conj root)
                (loop [work [[root 0]]]
                  (when-let [[v i] (peek work)]
                    (let [ws (succ v)]
                      (if (< i (count ws))
                        (let [w (nth ws i) work (conj (pop work) [v (inc i)])]
                          (cond
                            (not (contains? @index w))
                            (do (vswap! index assoc w @counter) (vswap! low assoc w @counter)
                                (vswap! counter inc) (vswap! stack conj w) (vswap! on conj w)
                                (recur (conj work [w 0])))
                            (@on w)
                            (do (vswap! low update v min (@index w)) (recur work))
                            :else (recur work)))
                        (let [work (pop work)]
                          (when (= (@low v) (@index v))
                            (let [s @stack
                                  k (loop [k (dec (count s))] (if (= v (nth s k)) k (recur (dec k))))
                                  comp (subvec s k)]
                              (vreset! stack (subvec s 0 k))
                              (vswap! on #(reduce disj % comp))
                              (vswap! out conj (vec (sort comp)))))
                          (when-let [[u] (peek work)]
                            (vswap! low update u min (@low v)))
                          (recur work)))))))]
    (doseq [v (sort (keys (:nodes g)))]
      (when-not (contains? @index v) (visit v)))
    @out))

(defn condense
  "The call graph with each cycle folded to one node, so recursion is a
  node and paths can be counted: {:members [ids] per component, :comp-of
  {id i}, :dag {i #{j}}}. Components are numbered callees first."
  [g]
  (let [members (scc g)
        comp-of (into {} (for [[i m] (map-indexed vector members), id m] [id i]))
        dag (reduce (fn [d [f t]]
                      (let [a (comp-of f) b (comp-of t)]
                        (if (= a b) d (update d a conj b))))
                    (into {} (map (fn [i] [i #{}])) (range (count members)))
                    (keys (:edges g)))]
    {:members members :comp-of comp-of :dag dag}))

(defn reach-set
  "Every node `step` reaches from `id`, `id` itself only on a cycle back."
  [step id]
  (loop [seen #{} todo (vec (step id))]
    (if-let [x (peek todo)]
      (if (seen x)
        (recur seen (pop todo))
        (recur (conj seen x) (into (pop todo) (step x))))
      seen)))
