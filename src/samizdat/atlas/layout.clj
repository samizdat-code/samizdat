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

(ns samizdat.atlas.layout
  "A layered drawing of a lifted graph (RFC-016): calls run left to right,
  so what a function calls sits to its right and what calls it to its left,
  the way the Light Table demo stacked callees beside the focused function.

  Sugiyama's steps, kept simple: back edges found by a depth-first walk are
  turned around so a cycle can be layered; each node goes in the layer of
  its longest path from a source; each layer is reordered by the mean
  position of its neighbours, sweeping down and up `:sweeps` times; layers
  are `:layer-gap` apart and nodes `:node-gap` apart, each layer centred on
  zero. An edge spanning layers is drawn straight. Pure; the gaps and the
  sweeps are the caller's.")

(defn- back-edges
  "The edges a depth-first walk over `nodes` (in order) finds pointing back
  to a node on its stack."
  [nodes succ]
  (let [state (volatile! {}) back (volatile! #{})]
    (doseq [root nodes :when (nil? (@state root))]
      (vswap! state assoc root :open)
      (loop [stack [[root (succ root)]]]
        (when-let [[v ws] (peek stack)]
          (if-let [w (first ws)]
            (let [stack (conj (pop stack) [v (rest ws)])]
              (case (@state w)
                :open (do (vswap! back conj [v w]) (recur stack))
                :done (recur stack)
                (do (vswap! state assoc w :open) (recur (conj stack [w (succ w)])))))
            (do (vswap! state assoc v :done) (recur (pop stack)))))))
    @back))

(defn- longest-path-layers
  "{node layer} over the acyclic `pairs`."
  [nodes pairs]
  (let [preds (reduce (fn [m [a b]] (update m b (fnil conj #{}) a)) {} pairs)
        succs (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} pairs)]
    (loop [ready (vec (filter #(empty? (preds %)) nodes))
           indeg (into {} (map (fn [n] [n (count (preds n))])) nodes)
           layer (into {} (map (fn [n] [n 0])) nodes)]
      (if-let [v (peek ready)]
        (let [ws (sort (succs v))
              layer (reduce (fn [l w] (update l w max (inc (l v)))) layer ws)
              indeg (reduce (fn [d w] (update d w dec)) indeg ws)]
          (recur (into (pop ready) (filter #(zero? (indeg %)) ws)) indeg layer))
        layer))))

(defn- reorder
  "`order` (a vector of nodes) sorted by the mean index of each node's
  neighbours in `pos`, a node with none keeping its own index."
  [order nbrs pos]
  (let [bary (fn [i n]
               (let [ps (keep pos (nbrs n))]
                 (if (seq ps) (/ (double (reduce + ps)) (count ps)) (double i))))]
    (mapv second (sort-by first (map-indexed (fn [i n] [[(bary i n) i] n]) order)))))

(defn layered
  "Positions for `lifted` ({:nodes [ids] :edges [{:from :to}]}):
  {:positions {id [x y]} :layers {id layer}}."
  [{:keys [nodes edges]} {:keys [layer-gap node-gap sweeps]}]
  (let [pairs (distinct (for [{:keys [from to]} edges :when (not= from to)] [from to]))
        succ (let [m (reduce (fn [m [a b]] (update m a (fnil conj []) b)) {} pairs)]
               #(sort (get m % [])))
        back (back-edges nodes succ)
        dag (map (fn [[a b :as p]] (if (back p) [b a] p)) pairs)
        layer (longest-path-layers nodes dag)
        preds (reduce (fn [m [a b]] (update m b (fnil conj #{}) a)) {} dag)
        succs (reduce (fn [m [a b]] (update m a (fnil conj #{}) b)) {} dag)
        depth (inc (reduce max 0 (vals layer)))
        start (vec (for [l (range depth)] (vec (filter #(= l (layer %)) nodes))))
        index-of (fn [order] (into {} (map-indexed (fn [i n] [n i])) order))
        sweep (fn [layers]
                (let [down (reduce (fn [ls l]
                                     (assoc ls l (reorder (ls l) preds (index-of (ls (dec l))))))
                                   layers (range 1 depth))]
                  (reduce (fn [ls l]
                            (assoc ls l (reorder (ls l) succs (index-of (ls (inc l))))))
                          down (reverse (range 0 (dec depth))))))
        ordered (nth (iterate sweep start) (or sweeps 0))]
    {:layers layer
     :positions (into {}
                      (for [[l order] (map-indexed vector ordered)
                            [i n] (map-indexed vector order)]
                        [n [(* (double layer-gap) l)
                            (* (double node-gap) (- i (/ (dec (count order)) 2.0)))]]))}))
