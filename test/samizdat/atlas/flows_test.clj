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

(ns samizdat.atlas.flows-test
  "A program as the set of smaller programs inside it (RFC-016): entries and
  sinks picked by rules, each entry's slice, its paths counted before they
  are listed, shared prefixes folded, and functions grouped by the flows
  they serve."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.atlas.flows :as flows]
            [samizdat.atlas.graph :as g]))

(defn- f
  ([n] (f n {}))
  ([n m] (merge {:id (str "a/" n) :label n :kind :fn :parent "a"} m)))

(defn- e [a b] {:from (str "a/" a) :to (str "a/" b)})

;;   -main ──> handle ──> parse ──> tidy
;;                   └──> save! ─> conn
;;   cli   ──> parse            (cli calls parse too)
;;   loopy ──> ping <──> pong ──> conn          (a cycle)
;;   helper   (private, uncalled: not an entry)
(def sample
  (g/build [{:id "a" :label "a" :kind :ns}
            (f "-main") (f "handle") (f "parse") (f "tidy") (f "save!") (f "conn")
            (f "cli") (f "loopy") (f "ping") (f "pong") (f "helper" {:private? true})]
           [(e "-main" "handle") (e "handle" "parse") (e "parse" "tidy")
            (e "handle" "save!") (e "save!" "conn") (e "cli" "parse")
            (e "loopy" "ping") (e "ping" "pong") (e "pong" "ping") (e "pong" "conn")]))

(def policy {:entries [{:uncalled? true}] :sinks [{:label-matches "!$"} {:leaf? true}]
             :max-paths 50 :max-depth 20})

(deftest entries-and-sinks-come-from-rules
  (is (= ["a/-main" "a/cli" "a/loopy"] (flows/entries sample policy)))
  (is (= ["a/-main"] (flows/entries sample {:entries [{:label "-main"}]})))
  (is (= ["a/conn" "a/helper" "a/save!" "a/tidy"] (flows/sinks sample policy)))
  (testing "a rule's fields must all hold"
    (is (= [] (flows/entries sample {:entries [{:label "-main" :kind :macro}]})))))

(deftest paths-are-counted-before-they-are-listed
  (is (= 2 (flows/path-count sample "a/-main")))
  (is (= 1 (flows/path-count sample "a/loopy")))
  (testing "the count is exact where the listing is capped"
    (let [wide (g/build (concat [(f "root")] (map #(f (str "m" %)) (range 30)) [(f "end")])
                        (concat (map #(e "root" (str "m" %)) (range 30))
                                (map #(e (str "m" %) "end") (range 30))))]
      (is (= 30 (flows/path-count wide "a/root")))
      (let [fl (flows/flow wide "a/root" (assoc policy :max-paths 5))]
        (is (= 5 (count (:paths fl))))
        (is (:truncated? fl))
        (is (= 30 (:path-count fl)))))))

(deftest a-flow-is-an-entry-its-slice-and-its-paths-to-the-end
  (let [fl (flows/flow sample "a/-main" policy)]
    (is (= "a/-main" (:entry fl)))
    (is (= #{"a/-main" "a/handle" "a/parse" "a/tidy" "a/save!" "a/conn"} (:slice fl)))
    (is (= [["a/-main" "a/handle" "a/parse" "a/tidy"]
            ["a/-main" "a/handle" "a/save!" "a/conn"]]
           (:paths fl)))
    (is (= ["a/conn" "a/save!" "a/tidy"] (:sinks fl)) "the outputs it reaches")
    (is (not (:truncated? fl)))))

(deftest a-cycle-on-a-path-is-one-step-and-says-what-it-holds
  (let [fl (flows/flow sample "a/loopy" policy)]
    (is (= [["a/loopy" "a/ping" "a/conn"]] (:paths fl)))
    (is (= {"a/ping" ["a/ping" "a/pong"]} (:loops fl)))))

(deftest shared-prefixes-fold-into-a-trie
  (is (= {:id "a/-main" :count 2
          :children [{:id "a/handle" :count 2
                      :children [{:id "a/parse" :count 1 :children [{:id "a/tidy" :count 1 :children []}]}
                                 {:id "a/save!" :count 1 :children [{:id "a/conn" :count 1 :children []}]}]}]}
         (flows/trie (:paths (flows/flow sample "a/-main" policy))))))

(deftest every-flow-and-what-each-shares
  (let [fs (flows/all sample policy)]
    (is (= ["a/-main" "a/cli" "a/loopy"] (map :entry fs)))
    (is (= {"a/parse" #{"a/-main" "a/cli"} "a/conn" #{"a/-main" "a/loopy"}}
           (select-keys (flows/membership fs) ["a/parse" "a/conn"])))
    (testing "a unit is the functions that serve exactly the same flows"
      (is (= [{:flows #{"a/-main"} :members ["a/-main" "a/handle" "a/save!"]}
              {:flows #{"a/-main" "a/cli"} :members ["a/parse" "a/tidy"]}
              {:flows #{"a/-main" "a/loopy"} :members ["a/conn"]}
              {:flows #{"a/cli"} :members ["a/cli"]}
              {:flows #{"a/loopy"} :members ["a/loopy" "a/ping" "a/pong"]}]
             (map #(select-keys % [:flows :members]) (flows/units fs)))))))

(deftest flows-through-a-function
  (is (= ["a/-main" "a/cli"] (flows/through (flows/all sample policy) "a/tidy"))))

(deftest units-coarsen-by-how-much-their-flows-overlap
  (let [us [{:id "unit:0" :flows #{"x" "y"} :members ["a/1"]}
            {:id "unit:1" :flows #{"x" "y" "z"} :members ["a/2" "a/3"]}
            {:id "unit:2" :flows #{"q"} :members ["a/4"]}]]
    (testing "at 1.0 nothing merges"
      (is (= 3 (count (flows/coarsen us 1.0)))))
    (testing "2 of 3 shared clears 0.6: the first two merge, flows and members joined"
      (is (= [{:id "unit:0" :flows #{"x" "y" "z"} :members ["a/1" "a/2" "a/3"]}
              {:id "unit:2" :flows #{"q"} :members ["a/4"]}]
             (flows/coarsen us 0.6))))))

(deftest units-become-a-containment-hierarchy-to-zoom-through
  (let [fs (flows/all sample policy)
        ug (flows/unit-graph sample (flows/units fs))]
    (is (= "unit:0" (:parent (g/node ug "a/handle"))))
    (is (= :unit (:kind (g/node ug "unit:0"))))
    (is (= "-main" (:label (g/node ug "unit:0"))) "named for the member the rest is entered through")
    (is (= {:from "unit:0" :to "unit:1" :count 1}
           (first (filter #(= ["unit:0" "unit:1"] [(:from %) (:to %)])
                          (:edges (g/lift ug #{}))))))))
