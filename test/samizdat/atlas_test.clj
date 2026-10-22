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

(ns samizdat.atlas-test
  "The code atlas over a project (RFC-016): the graph read from the files a
  project has, reread only where a file changed; views at a zoom level;
  one function's card; flows; and snapshots over time."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.atlas :as atlas]
            [samizdat.store.db :as db]))

(def policy
  {:sources {:include ["\\.clj$"] :exclude ["_test\\.clj$"]}
   :entries [{:label "-main"} {:uncalled? true :kind [:fn]}]
   :sinks [{:label-matches "!$"} {:leaf? true}]
   :max-paths 50 :max-depth 20 :max-flows 100 :unit-similarity 1.0
   :butterfly-depth 2 :max-source-lines 50})

(def initial
  {"src/app/core.clj" (str/join "\n" ["(ns app.core (:require [app.db :as db]))"
                                            "(defn handle [x] (db/save! x))"
                                            "(defn -main [& args] (handle args))"])
         "src/app/db.clj" "(ns app.db)\n(defn save! [x] x)"
         "test/app/core_test.clj" "(ns app.core-test)\n(defn t [] 1)"
         "README.md" "# hi"})

(def files (atom initial))

(def reads (atom 0))

(def io {:list-files (fn [_] (sort (keys @files)))
         :read-file (fn [_ p] (swap! reads inc) (get @files p))})

(use-fixtures :each
  (fn [t]
    (atlas/forget!)
    (reset! files initial)
    (with-redefs [atlas/policy (constantly policy)]
      (t))))

(deftest the-graph-is-read-from-the-projects-sources
  (let [gr (atlas/graph "/p" io)]
    (is (contains? (:nodes gr) "app.core/handle"))
    (is (not (contains? (:nodes gr) "app.core-test/t")) "tests are excluded by policy")
    (is (= ["app.core/handle"] (atlas/callers-of gr "app.db/save!")))))

(deftest a-file-is-reread-only-when-it-changed
  (atlas/graph "/p" io)
  (reset! reads 0)
  (atlas/graph "/p" io)
  (is (= 2 @reads) "both read for their hashes")
  (swap! files assoc "src/app/db.clj" "(ns app.db)\n(defn save! [x] x)\n(defn other [] 1)")
  (is (contains? (:nodes (atlas/graph "/p" io)) "app.db/other")))

(deftest a-view-at-a-zoom-level
  (let [v (atlas/view "/p" {:lens :namespaces :expanded #{"app.*"}} io)]
    (is (= ["app.core" "app.db"] (map :id (:nodes v))))
    (is (= {:id "app.core" :label "app.core" :kind :ns :size 2 :expandable? true}
           (select-keys (first (:nodes v)) [:id :label :kind :size :expandable?])))
    (is (= [{:from "app.core" :to "app.db" :count 1}] (:edges v)))))

(deftest a-units-view-zooms-through-flow-units
  (let [v (atlas/view "/p" {:lens :units :expanded #{}} io)]
    (is (every? #(= :unit (:kind %)) (:nodes v)))
    (is (seq (:nodes v)))))

(deftest a-functions-card
  (let [c (atlas/card "/p" "app.core/handle" io)]
    (is (= "(defn handle [x] (db/save! x))" (:source c)))
    (is (= [{:id "app.db/save!" :label "save!" :kind :fn :sites [{:file "src/app/core.clj" :line 2 :col 18}]}]
           (:callees c)))
    (is (= ["app.core/-main"] (map :id (:callers c))))
    (is (= ["app.core/-main"] (:flows c)) "the flows it serves")
    (is (= ["app.*" "app.core"] (:ancestors c)))))

(deftest flows-listed-and-one-in-full
  (is (= [{:entry "app.core/-main" :label "-main" :path-count 1 :slice 3 :sinks ["app.db/save!"]
           :truncated? false}]
         (atlas/flow-list "/p" io)))
  (let [f (atlas/flow-detail "/p" "app.core/-main" io)]
    (is (= [["app.core/-main" "app.core/handle" "app.db/save!"]] (:paths f)))
    (is (= "app.core/-main" (get-in f [:trie :id])))
    (is (= "save!" (get-in f [:labels "app.db/save!"])))))

(deftest search-finds-functions-by-name
  (is (= ["app.db/save!"] (map :id (atlas/search "/p" "save" io)))))

(deftest snapshots-are-kept-when-the-graph-changes-and-diffed
  (let [c (db/open! ":memory:")]
    (db/migrate! c)
    (is (some? (atlas/snapshot! c "/p" "run-1" io)))
    (is (nil? (atlas/snapshot! c "/p" "run-2" io)) "nothing changed, nothing stored")
    (swap! files assoc "src/app/db.clj" "(ns app.db)\n(defn save! [x] (inc x))")
    (atlas/snapshot! c "/p" "run-3" io)
    (let [[newer older] (atlas/snapshots c "/p")]
      (is (= ["run-3" "run-1"] [(:run-id newer) (:run-id older)]))
      (is (= ["app.db/save!"] (:changed (atlas/diff c (:id older) (:id newer))))))))
