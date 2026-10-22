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

(ns samizdat.atlas.states
  "Workflow manifests as state graphs (RFC-016), in the code graph's model so
  the same viewer draws both.

  A manifest's :cells name its states and :edges its transitions, an edge
  either the next state or a map from the label a cell dispatched on to the
  next state. Each state is a node `<manifest>:<state>` holding its cell id
  as :cell, which is the id the Clojure extractor gives that cell's
  `defcell` — so a state leads to the code that runs in it.

  `overlay` folds a run's step events (samizdat.events/step: the node a
  cell ran as and the label it dispatched on) into visit and transition
  counts and where each branch was last. Pure."
  (:require [samizdat.atlas.graph :as g]))

(defn- state-id [manifest node] (str manifest ":" (name node)))

(defn- next-state
  "Where `node` goes on `label` under `edges`, or nil."
  [edges node label]
  (let [e (get edges node)]
    (if (map? e) (get e label) e)))

(defn manifest-graph
  "The state graph of manifest `name`'s definition `defn-map` (with
  :extends resolved)."
  [name {:keys [cells edges]}]
  (let [states (distinct (concat (keys cells) (keys edges)
                                 (mapcat #(let [e (val %)] (if (map? e) (vals e) [e])) edges)))]
    (g/build
     (cons {:id name :label name :kind :manifest}
           (for [s states]
             (cond-> {:id (state-id name s) :label (clojure.core/name s)
                      :kind (if (= :end s) :end :state) :parent name}
               (get cells s) (assoc :cell (str (get cells s))))))
     (for [[from e] edges
           [label to] (if (map? e) e [[nil e]])]
       {:from (state-id name from) :to (state-id name to) :sites [{:label label}]}))))

(defn overlay
  "Manifest `name`'s `defn-map` with `steps` laid over it: {:visits {state
  n} :transitions {[from to] n} :current {branch state} :failed {state n}}."
  [name {:keys [edges]} steps]
  (reduce (fn [acc {:keys [node transition branch-id failed]}]
            (let [s (state-id name node)
                  to (next-state edges node transition)]
              (cond-> (-> acc
                          (update-in [:visits s] (fnil inc 0))
                          (assoc-in [:current (str branch-id)] s))
                to (update-in [:transitions [s (state-id name to)]] (fnil inc 0))
                failed (update-in [:failed s] (fnil inc 0)))))
          {:visits {} :transitions {} :current {} :failed {}}
          (filter :node steps)))

(defn cell-of
  "The cell id state `id` runs, which is its node id in the code graph."
  [sg id]
  (:cell (g/node sg id)))
