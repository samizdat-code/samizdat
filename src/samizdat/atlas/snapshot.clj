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

(ns samizdat.atlas.snapshot
  "The code graph at a point in a project's history (RFC-016), kept small
  enough to store one per run: each node's kind, parent, label and source
  hash, the call edges as pairs, and the flow entries. `diff` says what
  came, what went and what changed between two — a node whose source hash
  moved changed, one that only moved within its file did not. Pure."
  (:require [clojure.edn :as edn]
            [samizdat.atlas.graph :as g]))

(defn snapshot
  "The snapshot of graph `gr` with flow `entries`, digested so an unchanged
  graph is recognised without comparing it."
  [gr entries]
  (let [nodes (into (sorted-map)
                    (for [[id n] (:nodes gr)]
                      [id (select-keys n [:kind :parent :hash :label])]))
        edges (into (sorted-set) (keys (:edges gr)))
        entries (vec (sort entries))]
    {:nodes nodes :edges edges :entries entries
     :digest (Long/toHexString (hash [nodes edges entries]))}))

(defn diff
  "What changed from snapshot `a` to `b`, each list sorted."
  [a b]
  (let [ka (set (keys (:nodes a))) kb (set (keys (:nodes b)))
        ea (set (:edges a)) eb (set (:edges b))
        na (set (:entries a)) nb (set (:entries b))
        sorted #(vec (sort %))]
    {:added (sorted (remove ka kb))
     :removed (sorted (remove kb ka))
     :changed (sorted (filter #(and (ka %) (not= (get-in a [:nodes % :hash])
                                                 (get-in b [:nodes % :hash])))
                              kb))
     :edges-added (sorted (remove ea eb))
     :edges-removed (sorted (remove eb ea))
     :entries-added (sorted (remove na nb))
     :entries-removed (sorted (remove nb na))}))

(defn write-edn [s] (pr-str (update s :edges vec)))

(defn read-edn [text]
  (-> (edn/read-string text)
      (update :nodes #(into (sorted-map) %))
      (update :edges #(into (sorted-set) %))))
