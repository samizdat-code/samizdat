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

(ns samizdat.atlas.states-test
  "A workflow manifest as a state graph (RFC-016), a run's steps laid over
  it, and each state's way into the code that runs there."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.atlas.graph :as g]
            [samizdat.atlas.states :as states]))

(def turn
  {:cells {:start :t/open :infer :t/infer :tool :t/tool :done :t/done}
   :edges {:start :infer
           :infer {:tool :tool :answer :done}
           :tool :infer
           :done :end}})

(def sg (states/manifest-graph "turn" turn))

(deftest states-and-transitions
  (is (= {:kind :manifest :label "turn"} (select-keys (g/node sg "turn") [:kind :label])))
  (is (= {:kind :state :label "infer" :cell ":t/infer" :parent "turn"}
         (select-keys (g/node sg "turn:infer") [:kind :label :cell :parent])))
  (is (= :end (:kind (g/node sg "turn:end"))))
  (is (= ["turn:done" "turn:tool"] (g/callees sg "turn:infer")))
  (is (= [{:label :tool}] (:sites (g/edge sg "turn:infer" "turn:tool"))))
  (is (= [{:label nil}] (:sites (g/edge sg "turn:tool" "turn:infer")))))

(deftest a-runs-steps-overlay-the-graph
  (let [steps [{:branch-id "b1" :node :start}
               {:branch-id "b1" :node :infer :transition :tool}
               {:branch-id "b1" :node :tool}
               {:branch-id "b1" :node :infer :transition :answer}
               {:branch-id "b2" :node :start}
               {:branch-id "b2" :node :infer :transition :tool :failed true}]
        o (states/overlay "turn" turn steps)]
    (is (= {"turn:start" 2 "turn:infer" 3 "turn:tool" 1} (:visits o)))
    (is (= {["turn:start" "turn:infer"] 2 ["turn:infer" "turn:tool"] 2
            ["turn:tool" "turn:infer"] 1 ["turn:infer" "turn:done"] 1}
           (:transitions o)))
    (is (= {"b1" "turn:infer" "b2" "turn:infer"} (:current o)) "where each branch was last")
    (is (= {"turn:infer" 1} (:failed o)))))

(deftest a-state-reaches-its-cells-code
  (is (= ":t/infer" (states/cell-of sg "turn:infer"))))
