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

(ns samizdat.atlas.snapshot-test
  "The code graph over time (RFC-016): a compact snapshot per point in a
  project's history, and what changed between two."
  (:require [clojure.test :refer [deftest is]]
            [samizdat.atlas.graph :as g]
            [samizdat.atlas.snapshot :as snap]))

(defn- n [id h] {:id id :label id :kind :fn :parent "ns" :hash h :file "f.clj" :line 1 :doc "x"})

(def before (g/build [{:id "ns" :kind :ns} (n "a" 1) (n "b" 1) (n "c" 1)]
                     [{:from "a" :to "b" :sites [{:line 3}]} {:from "b" :to "c"}]))
(def after (g/build [{:id "ns" :kind :ns} (n "a" 1) (n "b" 2) (n "d" 1)]
                    [{:from "a" :to "b"} {:from "a" :to "d"}]))

(deftest a-snapshot-keeps-what-a-diff-needs-and-no-more
  (let [s (snap/snapshot before ["a"])]
    (is (= {:kind :fn :parent "ns" :hash 1 :label "a"} (get-in s [:nodes "a"])))
    (is (= #{["a" "b"] ["b" "c"]} (:edges s)))
    (is (= ["a"] (:entries s)))
    (is (string? (:digest s)))
    (is (= (:digest s) (:digest (snap/snapshot before ["a"]))) "the same graph digests the same")
    (is (not= (:digest s) (:digest (snap/snapshot after ["a"]))))))

(deftest a-diff-says-what-came-went-and-changed
  (is (= {:added ["d"] :removed ["c"] :changed ["b"]
          :edges-added [["a" "d"]] :edges-removed [["b" "c"]]
          :entries-added [] :entries-removed []}
         (snap/diff (snap/snapshot before ["a"]) (snap/snapshot after ["a"])))))

(deftest a-snapshot-round-trips-through-edn
  (let [s (snap/snapshot before ["a"])]
    (is (= s (snap/read-edn (snap/write-edn s))))))
