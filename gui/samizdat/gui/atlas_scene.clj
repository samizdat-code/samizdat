;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.gui.atlas-scene
  "The code atlas canvas as data (RFC-016): what the GL pane draws for a
  view of the call graph, and which box a click lands in.

  Toolkit-free, like samizdat.gui.graph, so the headless suite covers it.
  The pane's pipeline draws only lines and triangles, so labels are STROKES:
  a small vector font, each glyph polylines on a 4x6 grid, scaled to
  :char-px. Boxes are sized in pixels, not world units, so a label stays
  readable at every zoom and zooming spreads the layout instead.

  Vertices are interleaved [x y r g b] in device pixels, as glpane expects."
  (:require [clojure.string :as str]
            [samizdat.gui.graph :as graph]))

;; --- the font ----------------------------------------------------------------

(def ^:private box-glyph [[[0 0] [4 0] [4 6] [0 6] [0 0]]])

(def ^:private glyphs
  {\A [[[0 6] [0 2] [2 0] [4 2] [4 6]] [[0 4] [4 4]]]
   \B [[[0 0] [0 6] [3 6] [4 5] [4 4] [3 3] [0 3]] [[0 0] [3 0] [4 1] [4 2] [3 3]]]
   \C [[[4 0] [0 0] [0 6] [4 6]]]
   \D [[[0 0] [0 6] [3 6] [4 5] [4 1] [3 0] [0 0]]]
   \E [[[4 0] [0 0] [0 6] [4 6]] [[0 3] [3 3]]]
   \F [[[4 0] [0 0] [0 6]] [[0 3] [3 3]]]
   \G [[[4 1] [4 0] [0 0] [0 6] [4 6] [4 3] [2 3]]]
   \H [[[0 0] [0 6]] [[4 0] [4 6]] [[0 3] [4 3]]]
   \I [[[1 0] [3 0]] [[2 0] [2 6]] [[1 6] [3 6]]]
   \J [[[4 0] [4 6] [0 6] [0 4]]]
   \K [[[0 0] [0 6]] [[4 0] [0 3] [4 6]]]
   \L [[[0 0] [0 6] [4 6]]]
   \M [[[0 6] [0 0] [2 3] [4 0] [4 6]]]
   \N [[[0 6] [0 0] [4 6] [4 0]]]
   \O [[[0 0] [4 0] [4 6] [0 6] [0 0]]]
   \P [[[0 6] [0 0] [4 0] [4 3] [0 3]]]
   \Q [[[0 0] [4 0] [4 6] [0 6] [0 0]] [[2 4] [4 6]]]
   \R [[[0 6] [0 0] [4 0] [4 3] [0 3] [4 6]]]
   \S [[[4 0] [0 0] [0 3] [4 3] [4 6] [0 6]]]
   \T [[[0 0] [4 0]] [[2 0] [2 6]]]
   \U [[[0 0] [0 6] [4 6] [4 0]]]
   \V [[[0 0] [2 6] [4 0]]]
   \W [[[0 0] [1 6] [2 3] [3 6] [4 0]]]
   \X [[[0 0] [4 6]] [[4 0] [0 6]]]
   \Y [[[0 0] [2 3] [4 0]] [[2 3] [2 6]]]
   \Z [[[0 0] [4 0] [0 6] [4 6]]]
   \0 [[[0 0] [4 0] [4 6] [0 6] [0 0]] [[4 0] [0 6]]]
   \1 [[[1 1] [2 0] [2 6]] [[1 6] [3 6]]]
   \2 [[[0 0] [4 0] [4 3] [0 3] [0 6] [4 6]]]
   \3 [[[0 0] [4 0] [4 6] [0 6]] [[1 3] [4 3]]]
   \4 [[[0 0] [0 3] [4 3]] [[4 0] [4 6]]]
   \5 [[[4 0] [0 0] [0 3] [4 3] [4 6] [0 6]]]
   \6 [[[4 0] [0 0] [0 6] [4 6] [4 3] [0 3]]]
   \7 [[[0 0] [4 0] [2 6]]]
   \8 [[[0 0] [4 0] [4 6] [0 6] [0 0]] [[0 3] [4 3]]]
   \9 [[[4 3] [0 3] [0 0] [4 0] [4 6] [0 6]]]
   \- [[[1 3] [3 3]]]
   \_ [[[0 6] [4 6]]]
   \. [[[2 5] [2 6]]]
   \, [[[2 5] [1 7]]]
   \/ [[[0 6] [4 0]]]
   \! [[[2 0] [2 4]] [[2 5] [2 6]]]
   \? [[[0 1] [0 0] [4 0] [4 3] [2 3] [2 4]] [[2 5] [2 6]]]
   \> [[[0 0] [4 3] [0 6]]]
   \< [[[4 0] [0 3] [4 6]]]
   \* [[[2 1] [2 5]] [[0 2] [4 4]] [[0 4] [4 2]]]
   \: [[[2 1] [2 2]] [[2 4] [2 5]]]
   \+ [[[2 1] [2 5]] [[0 3] [4 3]]]
   \= [[[0 2] [4 2]] [[0 4] [4 4]]]
   \( [[[3 0] [1 2] [1 4] [3 6]]]
   \) [[[1 0] [3 2] [3 4] [1 6]]]
   \[ [[[3 0] [1 0] [1 6] [3 6]]]
   \] [[[1 0] [3 0] [3 6] [1 6]]]
   \" [[[1 0] [1 2]] [[3 0] [3 2]]]
   \' [[[2 0] [2 2]]]
   \# [[[1 0] [1 6]] [[3 0] [3 6]] [[0 2] [4 2]] [[0 4] [4 4]]]
   \space []})

(defn glyph
  "Char `c`'s polylines on the 4x6 grid, case folded; a box for a char the
  font lacks."
  [c]
  (get glyphs (first (str/upper-case (str c))) box-glyph))

(def ^:private cell 6.0)

(defn text-verts
  "Line verts drawing `s` with its top-left at [x y], each char `px` wide."
  [s x y px color]
  (let [k (/ (double px) cell)
        [r g b] color]
    (vec (for [[i c] (map-indexed vector s)
               line (glyph c)
               [[x0 y0] [x1 y1]] (partition 2 1 line)
               v [(+ x (* k (+ (* i cell) x0))) (+ y (* k y0)) r g b
                  (+ x (* k (+ (* i cell) x1))) (+ y (* k y1)) r g b]]
           v))))

(defn clip-label
  "`s` cut to `n` chars, the cut marked."
  [s n]
  (let [s (str s)]
    (if (<= (count s) n) s (str (subs s 0 (- n 2)) ".."))))

;; --- the frame ---------------------------------------------------------------

(defn- bounds [positions]
  (let [xs (map first (vals positions)) ys (map second (vals positions))]
    [(reduce min xs) (reduce min ys) (reduce max xs) (reduce max ys)]))

(defn fit-zoom
  "The zoom at which all of `positions` fits a `w`x`h` viewport with `pad`
  margin."
  [positions w h pad]
  (:s (graph/fit positions w h pad)))

(defn frame
  "The world->pixel transform for `positions` in a `w`x`h` viewport: `zoom`
  pixels per layout unit — so at 1 the layout's own spacing keeps the boxes
  apart however large the graph — with the graph's centre at the viewport's,
  then moved by the drag `pan`. `pad` is the margin `fit-zoom` leaves."
  [positions w h zoom pan _pad]
  (let [[x0 y0 x1 y1] (bounds positions)
        z (double (or zoom 1.0))]
    (graph/with-pan {:s z
                     :ox (- (/ w 2.0) (* z (/ (+ x0 x1) 2.0)))
                     :oy (- (/ h 2.0) (* z (/ (+ y0 y1) 2.0)))}
                    pan)))

(def ->px graph/world->px)

;; --- boxes -------------------------------------------------------------------

(defn- box
  "Node `n`'s box in pixels around its projected position: [x0 y0 x1 y1]."
  [n p t {:keys [char-px box-height box-pad max-label-chars]} scale]
  (let [[cx cy] (->px t p)
        w (* scale (+ (* 2 box-pad) (* char-px (count (clip-label (:label n) max-label-chars)))))
        h (* scale box-height)]
    [(- cx (/ w 2)) (- cy (/ h 2)) (+ cx (/ w 2)) (+ cy (/ h 2))]))

(defn pick
  "The id of the node whose box holds pixel [x y], or nil; the box drawn
  last (on top) first."
  [{:keys [nodes]} positions t settings scale [x y]]
  (some (fn [n]
          (when-let [p (positions (:id n))]
            (let [[x0 y0 x1 y1] (box n p t settings scale)]
              (when (and (<= x0 x x1) (<= y0 y y1)) (:id n)))))
        (reverse nodes)))

(defn- rect-tris [[x0 y0 x1 y1] [r g b]]
  [x0 y0 r g b, x1 y0 r g b, x1 y1 r g b
   x0 y0 r g b, x1 y1 r g b, x0 y1 r g b])

(defn- outline [[x0 y0 x1 y1] [r g b]]
  (vec (mapcat (fn [[[a b'] [c d]]] [a b' r g b c d r g b])
               [[[x0 y0] [x1 y0]] [[x1 y0] [x1 y1]] [[x1 y1] [x0 y1]] [[x0 y1] [x0 y0]]])))

(defn- grow [[x0 y0 x1 y1] d] [(- x0 d) (- y0 d) (+ x1 d) (+ y1 d)])

(defn- edge-verts
  "An edge from box `a` to box `b` with an arrowhead: right side to left
  side when `b` is to the right, else centre to centre."
  [[ax0 ay0 ax1 ay1] [bx0 by0 bx1 by1] arrow [r g b]]
  (let [[x0 y0 x1 y1] (if (< ax1 bx0)
                        [ax1 (/ (+ ay0 ay1) 2) bx0 (/ (+ by0 by1) 2)]
                        [(/ (+ ax0 ax1) 2) (/ (+ ay0 ay1) 2) (/ (+ bx0 bx1) 2) (/ (+ by0 by1) 2)])
        dx (- x1 x0) dy (- y1 y0)
        len (max 1e-6 (Math/sqrt (+ (* dx dx) (* dy dy))))
        ux (/ dx len) uy (/ dy len)
        side (fn [sgn] [(- x1 (* arrow ux) (* sgn arrow 0.5 uy))
                        (+ (- y1 (* arrow uy)) (* sgn arrow 0.5 ux))])
        [lx ly] (side 1) [rx ry] (side -1)]
    [x0 y0 r g b x1 y1 r g b
     x1 y1 r g b lx ly r g b
     x1 y1 r g b rx ry r g b]))

(defn verts
  "{:lines :tris :over} for `view` laid out at `positions` under transform
  `t`: edges as :lines under the box :tris, labels and outlines as :over
  lines above them.
  `marks` is {:selected id :hover id :marked #{ids} :diff {id :added|:changed}}:
  the selection and the hover outline a box, a mark or a diff state backs
  it with a halo."
  [{:keys [nodes edges]} positions t {:keys [selected hover marked diff]} settings scale]
  (let [{:keys [colors char-px box-pad box-height arrow-px max-label-chars]} settings
        color #(get colors % (:default colors))
        by-id (into {} (map (juxt :id identity)) nodes)
        boxes (into {} (for [n nodes :let [p (positions (:id n))] :when p]
                         [(:id n) (box n p t settings scale)]))
        halo (* 2 scale)]
    {:lines (vec (mapcat (fn [{:keys [from to]}]
                           (when (and (boxes from) (boxes to))
                             (edge-verts (boxes from) (boxes to) (* scale arrow-px) (color :edge))))
                         edges))
     ;; Labels and outlines go on top of the box fills, so they are drawn
     ;; after them.
     :over (vec (mapcat (fn [[id b]]
                            (let [[x0 y0] b
                                  label (clip-label (:label (by-id id)) max-label-chars)]
                              (concat
                               (text-verts label (+ x0 (* scale box-pad))
                                           (+ y0 (/ (- (* scale box-height) (* scale char-px)) 2))
                                           (* scale char-px) (color :text))
                               (cond (= id selected) (outline (grow b halo) (color :selected))
                                     (= id hover) (outline (grow b halo) (color :hover))))))
                        boxes))
     :tris (vec (mapcat (fn [[id b]]
                          (concat
                           (when-let [k (get diff id)] (rect-tris (grow b (* 2 halo)) (color k)))
                           (when (contains? (set marked) id) (rect-tris (grow b halo) (color :mark)))
                           (rect-tris b (color (:kind (by-id id))))))
                        boxes))}))
