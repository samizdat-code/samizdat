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

(ns samizdat.atlas.graph-test
  "The code graph (RFC-016): containment, neighbourhoods, edge lifting for a
  zoom level, and the condensation path enumeration runs on."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.atlas.graph :as g]))

(defn- fn-node [ns n] {:id (str ns "/" n) :label n :kind :fn :parent ns})

(def sample
  ;; app            a.core: main -> handle -> (a.db/save, render)
  ;;  ├ app.core            render -> a.db/save ; a.db/save -> a.db/conn
  ;;  └ app.db
  (g/build
   [{:id "app" :label "app" :kind :group}
    {:id "app.core" :label "app.core" :kind :ns :parent "app"}
    {:id "app.db" :label "app.db" :kind :ns :parent "app"}
    (fn-node "app.core" "main") (fn-node "app.core" "handle") (fn-node "app.core" "render")
    (fn-node "app.db" "save") (fn-node "app.db" "conn")]
   [{:from "app.core/main" :to "app.core/handle" :sites [{:line 3}]}
    {:from "app.core/handle" :to "app.db/save" :sites [{:line 6}]}
    {:from "app.core/handle" :to "app.core/render" :sites [{:line 7}]}
    {:from "app.core/render" :to "app.db/save" :sites [{:line 9}]}
    {:from "app.core/handle" :to "app.db/save" :sites [{:line 8}]}
    {:from "app.db/save" :to "app.db/conn" :sites [{:line 2}]}]))

(deftest an-edge-named-twice-is-one-edge-with-both-call-sites
  (is (= [{:line 6} {:line 8}] (:sites (g/edge sample "app.core/handle" "app.db/save"))))
  (is (= 5 (count (g/edges sample)))))

(deftest an-edge-to-an-unknown-node-is-dropped
  (let [x (g/build [(fn-node "a" "f")] [{:from "a/f" :to "clojure.core/map"}])]
    (is (empty? (g/edges x)))))

(deftest callers-and-callees
  (is (= ["app.core/render" "app.db/save"] (g/callees sample "app.core/handle")))
  (is (= ["app.core/handle" "app.core/render"] (g/callers sample "app.db/save")))
  (is (= [] (g/callers sample "app.core/main"))))

(deftest a-butterfly-is-a-node-with-its-callers-and-callees-to-a-depth
  (let [b (g/butterfly sample "app.core/render" 2)]
    (is (= {"app.core/handle" 1 "app.core/main" 2} (:callers b)))
    (is (= {"app.db/save" 1 "app.db/conn" 2} (:callees b)))
    (is (= {"app.core/handle" 1} (:callers (g/butterfly sample "app.core/render" 1))))))

(deftest containment
  (is (= ["app" "app.core"] (g/ancestors sample "app.core/main")))
  (is (= ["app.core" "app.db"] (g/children sample "app")))
  (is (= ["app"] (g/roots sample)))
  (is (= ["app.core/handle" "app.core/main" "app.core/render" "app.db/conn" "app.db/save"]
         (g/leaves sample "app"))))

(deftest lifting-to-a-zoom-level
  (testing "everything collapsed is the root alone, and no self-loop"
    (is (= {:nodes ["app"] :edges []} (g/lift sample #{}))))
  (testing "the root open shows its namespaces and the calls between them, counted"
    (is (= {:nodes ["app.core" "app.db"]
            :edges [{:from "app.core" :to "app.db" :count 2}]}
           (g/lift sample #{"app"}))))
  (testing "one namespace open shows its functions beside the closed one"
    (let [{:keys [nodes edges]} (g/lift sample #{"app" "app.core"})]
      (is (= ["app.core/handle" "app.core/main" "app.core/render" "app.db"] nodes))
      (is (= #{{:from "app.core/main" :to "app.core/handle" :count 1}
               {:from "app.core/handle" :to "app.core/render" :count 1}
               {:from "app.core/handle" :to "app.db" :count 1}
               {:from "app.core/render" :to "app.db" :count 1}}
             (set edges))))))

(deftest a-cycle-condenses-to-one-component
  (let [x (g/build (map #(fn-node "n" %) ["a" "b" "c" "d"])
                   [{:from "n/a" :to "n/b"} {:from "n/b" :to "n/c"}
                    {:from "n/c" :to "n/b"} {:from "n/c" :to "n/d"}])
        {:keys [comp-of members dag]} (g/condense x)]
    (is (= (comp-of "n/b") (comp-of "n/c")))
    (is (= 3 (count members)))
    (is (= #{(comp-of "n/b")} (dag (comp-of "n/a"))))
    (is (= #{(comp-of "n/d")} (dag (comp-of "n/b"))))
    (is (empty? (dag (comp-of "n/d"))))))

(deftest condensing-a-deep-chain-does-not-blow-the-stack
  (let [n 20000
        x (g/build (map #(fn-node "n" (str %)) (range n))
                   (map (fn [i] {:from (str "n/" i) :to (str "n/" (inc i))}) (range (dec n))))]
    (is (= n (count (:members (g/condense x)))))))
