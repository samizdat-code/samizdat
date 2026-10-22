;; samizdat - a claim-first verification harness
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

(ns samizdat.gui.style
  "How a branch node looks, and the polygon geometry that draws it.

  Pure and GL-free so the headless suite covers it. Two independent signals
  are encoded, because collapsing them loses the one you needed:

    SHAPE and FILL say what kind of work the branch is doing — square when it
    is making claims (thesis, done), diamond when it edits files or the
    harness itself, triangle when it reads (read_file, grep, lsp), hexagon
    when it executes (shell, eval), circle for everything else.

    RING says the branch's status — alive, culled, exhausted, shipped.

  A node is drawn as up to three stacked polygons in one painter's-algorithm
  pass: selection halo, status ring, engine body. No depth buffer, so draw
  order is the layering.")

(def status-color
  {:active    [0.30 0.78 0.42]
   :done      [0.25 0.55 0.95]
   :culled    [0.88 0.33 0.28]
   :exhausted [0.93 0.68 0.24]
   :abandoned [0.50 0.50 0.55]
   :seed      [0.68 0.45 0.88]})

(def tool-color
  {:claim [0.35 0.68 0.92]
    :edit  [0.95 0.72 0.30]
    :read  [0.72 0.55 0.95]
    :run   [0.35 0.85 0.75]
    :meta  [0.72 0.74 0.80]
    :seed  [0.68 0.45 0.88]})

(def tool-shape
  {:claim :square
    :edit  :diamond
    :read  :triangle
    :run   :hexagon
    :meta  :circle
    :seed  :circle})

(def edge-color [0.42 0.42 0.48])
(def select-color [1.0 1.0 1.0])

(defn engine
  "The work family a tool name belongs to — every name here must be a
  registered run-tool (pinned by the vocabulary test in agent-test). Claim
  moves are square, edits diamond, reads triangle, execution hexagon;
  bookkeeping (task, message, memory tools) is :meta."
  [tool]
  (let [t (str tool)]
    (cond
      (#{"thesis" "done" "give_up" "branch_theses" "complete"} t) :claim
      (#{"edit_file" "write_file" "reload_cells" "cells" "manifest"} t) :edit
      (#{"read_file" "grep" "lsp" "atlas" "fetch_artifact" "fetch_turn" "introspect" "doc" "skill"} t) :read
      (#{"shell" "eval"} t) :run
      :else :meta)))

(def claim-color
  "How a single verification attempt turned out. Distinct from branch
  status: a refuted claim is a healthy branch doing its job."
  {:confirmed  [0.30 0.78 0.42]
   :refuted    [0.88 0.33 0.28]
   :existential [0.93 0.68 0.24]
   ;; A measurement is neither. Deliberately not on the green-to-red axis:
   ;; nothing was decided, so reading it as a weak confirmation or a soft
   ;; refutation is the one mistake this colour exists to prevent.
   :empirical  [0.38 0.62 0.95]
   :ambiguous  [0.55 0.55 0.60]})

(defn node-style
  "{:shape :fill :ring} for a folded graph node.

  Branch nodes: shape and fill are the engine the branch is working in,
  ring is its status. Artifact nodes: shape and fill are the engine that
  produced the claim, ring is how the claim came out."
  [{:keys [status tool kind engine] :as node}]
  (cond
    (= :seed status)
    {:shape :circle :fill (tool-color :seed) :ring (status-color :seed)}

    (= :artifact kind)
    {:shape (tool-shape engine :circle)
     :fill (tool-color engine [0.7 0.7 0.7])
     :ring (claim-color (or status :confirmed) [0.6 0.6 0.6])}

    :else
    (let [fam (if tool (samizdat.gui.style/engine tool) :meta)]
      {:shape (tool-shape fam :circle)
       :fill (tool-color fam [0.7 0.7 0.7])
       :ring (status-color (or status :active) [0.6 0.6 0.6])})))

(defn node-radius
  "Branch nodes grow with each confirmed artifact, capped so one productive
  branch cannot swallow the pane. Artifact nodes are small and uniform —
  they are events, not accumulations."
  [{:keys [confirmed kind]}]
  (if (= :artifact kind)
    7.0
    (+ 10.0 (* 1.8 (min 10 (or confirmed 0))))))

(def ^:private sides {:circle 20 :hexagon 6 :square 4 :diamond 4 :triangle 3})
(def ^:private rotation
  ;; A 4-gon at 45° reads as a square; at 0° as a diamond. The triangle is
  ;; nudged so it points up rather than sideways.
  {:square (/ Math/PI 4.0) :diamond 0.0 :triangle (/ Math/PI -2.0) })

(defn polygon
  "Perimeter points of a regular n-gon for `shape`, centered at [cx cy]."
  [cx cy r shape]
  (let [n (sides shape 20)
        rot (rotation shape 0.0)]
    (for [i (range n)
          :let [a (+ rot (* 2.0 Math/PI (/ (double i) n)))]]
      [(+ cx (* r (Math/cos a))) (+ cy (* r (Math/sin a)))])))

(defn shape-verts
  "A filled `shape` as an interleaved [x y r g b] triangle-fan-as-triangles
  list: one triangle per perimeter edge, all sharing the center."
  [cx cy r shape [cr cg cb]]
  (let [pts (vec (polygon cx cy r shape))
        n (count pts)]
    (vec (mapcat (fn [i]
                   (let [[x0 y0] (nth pts i)
                         [x1 y1] (nth pts (mod (inc i) n))]
                     [cx cy cr cg cb, x0 y0 cr cg cb, x1 y1 cr cg cb]))
                 (range n)))))

(def hover-color [0.75 0.78 0.85])

;; Deliberately not white: white is the selection halo, and the two must
;; stay tellable apart when a node is both selected and working.
(def working-color [0.30 0.90 0.95])

(defn node-verts
  "Every triangle for one node, back to front: the hover or selection halo,
  then the status ring, then the engine body. Selection wins over hover —
  the pointer is transient, the selection is a decision."
  ([cx cy node selected?] (node-verts cx cy node selected? false))
  ([cx cy node selected? hovered?] (node-verts cx cy node selected? hovered? false))
  ([cx cy node selected? hovered? working?]
   (let [{:keys [shape fill ring]} (node-style node)
         r (node-radius node)]
     (vec (concat
           (cond
             selected? (shape-verts cx cy (+ r 9.0) shape select-color)
             hovered?  (shape-verts cx cy (+ r 7.0) shape hover-color)
             :else nil)
           ;; Between the halo and the status ring, so a node can be
           ;; selected AND working and still read as both.
           (when working?
             (shape-verts cx cy (+ r 6.5) shape working-color))
           (shape-verts cx cy (+ r 4.0) shape ring)
           (shape-verts cx cy r shape fill))))))
