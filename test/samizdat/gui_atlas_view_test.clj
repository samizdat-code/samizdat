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

(ns samizdat.gui-atlas-view-test
  "The atlas panels as data (RFC-016): what the sidebar, toolbar and focus
  card show for a state, and which action each control fires."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [clojure.walk :as walk]
            [samizdat.gui.atlas-view :as v]))

(def settings (read-string (slurp "resources/gui.edn")))

(def fired (atom []))

(def act (into {} (for [k [:reveal :toggle :select :open-flow :clear :open-unit :show-code
                           :open-workflow :open-diff :search :tab :set-lens :zoom :fit :close-all]]
                    [k (fn [& args] (swap! fired conj (into [k] args)))])))

(defn- buttons
  "Every [:button props] in `h`, depth first."
  [h]
  (let [out (atom [])]
    (walk/prewalk (fn [x] (when (and (vector? x) (= :button (first x))) (swap! out conj (second x))) x) h)
    @out))

(defn- click! [h label]
  (reset! fired [])
  (if-let [b (first (filter #(str/includes? (:label %) label) (buttons h)))]
    ((:on-click b))
    (throw (ex-info "no such button" {:label label :have (map :label (buttons h))})))
  @fired)

(def s (assoc (v/initial-state (:atlas settings))
              :view {:nodes [{:id "app.*" :label "app.*" :kind :group :expandable? true :size 5}
                             {:id "app.core/main" :label "main" :kind :fn}]
                     :edges []}))

(deftest the-code-tab-lists-the-view-and-search-results
  (let [h (v/sidebar-view act s nil)]
    (is (= [[:toggle "app.*"]] (click! h "app.*")) "a group opens")
    (is (= [[:select "app.core/main"]] (click! h "main")) "a definition is focused")
    (is (= [[:tab :flows]] (click! h "flows"))))
  (let [h (v/sidebar-view act (assoc s :search "sa" :results [{:id "app.db/save!" :kind "fn"}]) nil)]
    (is (= [[:reveal "app.db/save!"]] (click! h "app.db/save!")) "a result is revealed on the canvas")))

(deftest a-flow-shows-its-trie
  (let [flow {:entry "a/-main" :path-count 2 :paths [["a/-main" "a/f"] ["a/-main" "a/g"]]
              :labels {:a/-main "-main" :a/f "f" :a/g "g"}
              :trie {:id "a/-main" :count 2 :children [{:id "a/f" :count 1 :children []}
                                                       {:id "a/g" :count 1 :children []}]}}
        h (v/sidebar-view act (assoc s :tab :flows :flow flow) nil)]
    (is (= [[:reveal "a/g"]] (click! h "g")))
    (is (some #(= "-main  ×2" (:label %)) (buttons h)) "a step on several paths says how many")
    (is (= [[:clear]] (click! h "all flows")))))

(deftest the-flow-list-opens-a-flow
  (let [h (v/sidebar-view act (assoc s :tab :flows :flows [{:entry "a/-main" :label "-main" :path-count 3 :slice 4}]) nil)]
    (is (= [[:open-flow "a/-main"]] (click! h "-main")))))

(deftest history-diffs-a-snapshot
  (let [h (v/sidebar-view act (assoc s :tab :history :snapshots [{:id 7 :run-id "abcdef0123" :created-at "2026-10-21T10:00:00Z"}]) nil)]
    (is (= [[:open-diff 7]] (click! h "abcdef01"))))
  (let [h (v/sidebar-view act (assoc s :tab :history :diff {:added ["a/x"] :changed ["a/y"] :removed ["a/z"]}) nil)]
    (is (= [[:reveal "a/x"]] (click! h "+ a/x")))
    (is (= [[:reveal "a/y"]] (click! h "~ a/y")))))

(deftest a-workflow-is-opened-with-the-selected-runs-steps
  (let [h (v/sidebar-view act (assoc s :tab :workflows :workflows [{:name "beam" :description "d"}]) "run-1")]
    (is (= [[:open-workflow "beam" "run-1"]] (click! h "beam")))))

(deftest the-toolbar
  (let [h (v/toolbar-view act s)]
    (is (= [[:set-lens :units]] (click! h "lens")))
    (is (= [[:zoom 1.25]] (click! h "+")))
    (is (= [[:close-all]] (click! h "close all")))))

(deftest the-focus-card-is-callers-source-and-the-callees-stack
  (let [card {:node {:id "app.core/handle" :kind "fn" :file "src/app/core.clj" :line 2 :doc "Handles."}
              :source "(defn handle [x] (db/save! x))"
              :callers [{:id "app.core/-main" :label "-main"}]
              :callees [{:id "app.db/save!" :label "save!"}]
              :flows ["app.core/-main"]}
        h (v/card-view act (assoc s :selected "app.core/handle" :card card
                                  :stack {"app.db/save!" {:source "(defn save! [x] x)"}}))]
    (is (= [[:select "app.core/-main"]] (click! h "← app.core/-main")))
    (is (= [[:select "app.db/save!"]] (click! h "focus")))
    (is (= [[:open-flow "app.core/-main"]] (click! h "flow -main")))
    (testing "the call in the source is highlighted"
      (is (some #(and (string? %) (str/includes? % "<b>db/save!</b>"))
                (flatten (walk/prewalk #(if (map? %) (vals %) %) h)))))))

(deftest markup-source-escapes-and-highlights-whole-words
  (is (= "<tt>(f <span foreground=\"#ff0000\"><b>g</b></span> &lt;gx&gt;)</tt>"
         (v/markup-source "(f g <gx>)" #{"g"} "#ff0000"))))

(deftest a-workflow-on-the-canvas
  (let [w (v/wf-view {:nodes [{:id "turn" :kind "manifest" :label "turn"}
                              {:id "turn:start" :kind "state" :label "start" :cell ":t/open"}
                              {:id "turn:infer" :kind "state" :label "infer" :cell ":t/infer"}]
                      :edges [{:from "turn:start" :to "turn:infer" :labels [nil]}]
                      :overlay {:visits {(keyword "turn:infer") 3} :current {:b1 "turn:infer"}}})]
    (is (= ["turn:start" "turn:infer"] (map :id (:nodes w))) "the manifest's own node is not drawn")
    (is (= "infer ×3" (:label (second (:nodes w)))))
    (is (= #{"turn:infer"} (:visited w)))))

(deftest the-canvas-source-draws-and-picks-the-same-boxes
  (let [st (assoc s :positions (v/positions-for (:view s) (:atlas settings)))
        {:keys [draw pick]} (v/source-map st)
        env {:w 800 :h 600 :pan [0.0 0.0] :scale 1.0}]
    (is (seq (:tris (draw env))))
    (is (nil? (pick env 0 0)))
    (is (nil? (v/source-map (assoc s :view nil))) "nothing to draw yet")
    (testing "a HiDPI pane spreads the layout by the same factor as the boxes"
      (let [gap (fn [scale] (let [t (:tris (draw (assoc env :scale scale)))]
                              (Math/abs (- (nth t 1) (nth t 31)))))]
        (is (pos? (gap 1.0)))
        (is (= (* 2 (gap 1.0)) (gap 2.0)))))))
