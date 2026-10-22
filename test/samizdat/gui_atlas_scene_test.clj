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

(ns samizdat.gui-atlas-scene-test
  "The atlas canvas as data (RFC-016): labels drawn as strokes, the frame a
  zoom and a drag make, which box a click lands in, and what gets drawn."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.gui.atlas-scene :as scene]))

(def settings {:char-px 6.0 :box-height 18.0 :box-pad 4.0 :max-label-chars 10 :frame-pad 40.0
               :arrow-px 6.0
               :colors {:fn [0.3 0.5 0.8] :ns [0.4 0.4 0.4] :default [0.5 0.5 0.5]
                        :text [1.0 1.0 1.0] :edge [0.4 0.4 0.4] :selected [1.0 1.0 1.0]
                        :hover [0.8 0.8 0.8] :mark [1.0 0.6 0.1] :added [0.2 0.8 0.3]
                        :changed [0.9 0.7 0.1] :removed [0.9 0.2 0.2]}})

(deftest text-is-strokes
  (testing "every glyph is segments inside its cell, a letter in either case alike"
    (is (= (scene/glyph \A) (scene/glyph \a)))
    (is (seq (scene/glyph \0)))
    (is (empty? (scene/glyph \space)))
    (is (seq (scene/glyph \€)) "an unknown char still draws something"))
  (testing "a label's verts are 5 floats per point, 2 points per segment"
    (let [v (scene/text-verts "HI" 0.0 0.0 6.0 [1.0 1.0 1.0])]
      (is (zero? (mod (count v) 10)))
      (is (= (* 10 (+ 3 3)) (count v)) "H is three strokes, I three")))
  (is (= "abcdefg.." (scene/clip-label "abcdefghijk" 9)))
  (is (= "abc" (scene/clip-label "abc" 9))))

(deftest zoom-is-pixels-per-unit-centred-on-the-graph
  (let [pos {"a" [0.0 0.0] "b" [1000.0 0.0]}
        t (scene/frame pos 200 100 1.0 [0.0 0.0] 0.0)]
    (is (= 1.0 (:s t)) "at zoom 1 a unit is a pixel, however big the graph")
    (is (= [100.0 50.0] (scene/->px t [500.0 0.0])) "the graph's centre is the pane's"))
  (testing "fit-zoom is the zoom that frames everything"
    (is (= 0.2 (scene/fit-zoom {"a" [0.0 0.0] "b" [1000.0 0.0]} 200 100 0.0)))))

(deftest a-zoom-scales-about-the-centre-and-a-drag-moves-the-frame
  (let [pos {"a" [0.0 0.0] "b" [100.0 0.0]}
        t1 (scene/frame pos 200 100 1.0 [0.0 0.0] 0.0)
        t2 (scene/frame pos 200 100 2.0 [0.0 0.0] 0.0)
        t3 (scene/frame pos 200 100 1.0 [10.0 5.0] 0.0)]
    (is (= (* 2 (:s t1)) (:s t2)))
    (is (= [100.0 50.0] (scene/->px t2 [50.0 0.0])) "the centre stays put")
    (is (= (mapv + (scene/->px t1 [0.0 0.0]) [10.0 5.0]) (scene/->px t3 [0.0 0.0])))))

(def view {:nodes [{:id "a" :label "alpha" :kind :fn} {:id "b" :label "beta" :kind :ns :expandable? true}]
           :edges [{:from "a" :to "b" :count 2}]})
(def pos {"a" [0.0 0.0] "b" [100.0 0.0]})

(deftest a-click-lands-in-a-box
  (let [t (scene/frame pos 400 200 1.0 [0.0 0.0] (:frame-pad settings))
        [ax ay] (scene/->px t (pos "a"))]
    (is (= "a" (scene/pick view pos t settings 1.0 [ax ay])))
    (is (= "a" (scene/pick view pos t settings 1.0 [(+ ax 10.0) (+ ay 5.0)])) "anywhere in the box")
    (is (nil? (scene/pick view pos t settings 1.0 [ax (+ ay 40.0)])))))

(deftest the-scene-draws-boxes-labels-and-edges
  (let [t (scene/frame pos 400 200 1.0 [0.0 0.0] (:frame-pad settings))
        {:keys [lines tris over]} (scene/verts view pos t {:selected "a" :marked #{"b"}} settings 1.0)]
    (is (pos? (count lines)) "the edge")
    (is (pos? (count over)) "the labels, drawn over the boxes")
    (is (zero? (mod (count over) 10)))
    (is (zero? (mod (count tris) 15)) "whole triangles")
    (testing "the selection and the mark each add an outline"
      (let [plain (scene/verts view pos t {} settings 1.0)]
        (is (> (count (:over (scene/verts view pos t {:selected "a"} settings 1.0)))
               (count (:over plain))))
        (is (> (count (:tris (scene/verts view pos t {:marked #{"b"}} settings 1.0)))
               (count (:tris plain))))))))
