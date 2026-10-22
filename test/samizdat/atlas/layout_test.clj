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

(ns samizdat.atlas.layout-test
  "Laying a lifted graph out in layers (RFC-016): calls run left to right,
  a cycle does not stop it, and crossings are reduced."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.atlas.layout :as layout]))

(def opts {:layer-gap 100 :node-gap 40 :sweeps 4})

(deftest a-callee-sits-to-the-right-of-its-caller
  (let [{:keys [positions layers]}
        (layout/layered {:nodes ["a" "b" "c" "d"]
                         :edges [{:from "a" :to "b"} {:from "b" :to "c"} {:from "a" :to "c"}]}
                        opts)]
    (is (= {"a" 0 "b" 1 "c" 2 "d" 0} layers) "longest path from a source")
    (is (< (first (positions "a")) (first (positions "b")) (first (positions "c"))))
    (is (= 100.0 (- (first (positions "b")) (first (positions "a")))))))

(deftest a-cycle-is-laid-out-anyway
  (let [{:keys [layers]} (layout/layered {:nodes ["a" "b"] :edges [{:from "a" :to "b"} {:from "b" :to "a"}]} opts)]
    (is (= #{0 1} (set (vals layers))))))

(deftest ordering-uncrosses-a-crossed-pair
  ;; a1->b2 and a2->b1 cross when b1 sits above b2; barycentres swap them.
  (let [{:keys [positions]} (layout/layered {:nodes ["a1" "a2" "b1" "b2"]
                                             :edges [{:from "a1" :to "b2"} {:from "a2" :to "b1"}]}
                                            opts)
        y #(second (positions %))]
    (is (< (y "a1") (y "a2")))
    (is (< (y "b2") (y "b1")))))

(deftest every-layer-is-centred
  (let [{:keys [positions]} (layout/layered {:nodes ["a" "b" "c"] :edges [{:from "a" :to "b"} {:from "a" :to "c"}]} opts)]
    (is (= 0.0 (second (positions "a"))))
    (is (= 0.0 (+ (second (positions "b")) (second (positions "c")))))))
