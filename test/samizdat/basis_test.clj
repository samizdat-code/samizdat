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

(ns samizdat.basis-test
  "A claim names what it rests on, and the names are checked
  (karamazov-0e2c.12, after sdiff's decorate: an inferred note carries its
  author and the derived facts it rests on, and a reference that does not
  resolve is reported, never dropped)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.basis :as basis]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]
            [samizdat.store.tasks :as tasks]))

(defmacro with-run [[c rid] & body]
  `(let [~c (db/open! ":memory:")
         ~rid (runs/start-run! ~c {:problem "p"})]
     (try ~@body (finally (db/close ~c)))))

(deftest a-reference-resolves-or-is-named
  (with-run [c rid]
    (journal/record-turn! c rid {:branch-id "B1" :turn 3 :tool-name "shell" :args {} :result "ok"
                                 :category :success})
    (journal/record-artifact! c rid {:branch-id "B1" :turn 3 :kind "lemma" :claim "x"
                                     :claim-status "confirmed"})
    (let [a (:id (first (journal/artifacts c rid)))
          t (tasks/create! c {:title "t" :run-id rid})
          k (knowledge/remember! c {:content "the build takes 40s" :run-id rid})
          ctx {:conn c :run-id rid :branch {:id "B1"}}]
      (is (empty? (basis/unresolved ctx ["t3" "B1:t3" (str "a#" a) t k])))
      (is (= ["t9" "B2:t3" "a#999" "sz-ffffff" "k-ffffff" "the docs"]
             (mapv :ref (basis/unresolved ctx ["t9" "B2:t3" "a#999" "sz-ffffff" "k-ffffff" "the docs"]))))
      (is (every? :why (basis/unresolved ctx ["t9" "the docs"]))))))

(deftest a-steer-may-cite-the-branch-it-steers-by-bare-turn
  ;; tb21-unread filter-js: the supervisor's review of B1 cited `t45`, B1's
  ;; done, and was refused as "no such turn" because a bare turn was read as
  ;; the supervisor's own. The approval never landed. A directive's bare turn
  ;; resolves on the caller's branch or on the branch it is addressed to.
  (with-run [c rid]
    (journal/record-turn! c rid {:branch-id "B1" :turn 45 :tool-name "done" :args {} :result "ok"
                                 :category :success})
    (journal/record-turn! c rid {:branch-id "SUP" :turn 4 :tool-name "shell" :args {} :result "ok"
                                 :category :success})
    (let [ctx {:conn c :run-id rid :branch {:id "SUP"}}]
      (is (= ["t45"] (mapv :ref (basis/unresolved ctx ["t45" "t4"]))) "plain: the caller's own only")
      (is (empty? (basis/unresolved (assoc ctx :addressed "B1") ["t45" "t4"])))
      (is (= ["t46"] (mapv :ref (basis/unresolved (assoc ctx :addressed "B1") ["t46"]))))
      (testing "and the supervisor's memory may cite the run it watched"
        ;; tb21-paced mteb: its `remember` citing B1's t24 was refused.
        (let [sup (assoc ctx :branch {:id "SUP" :role :supervisor})]
          (is (empty? (basis/unresolved sup ["t45" "t4"])))
          (is (= ["t46"] (mapv :ref (basis/unresolved sup ["t46"])))))))))

(deftest a-basis-sent-as-a-json-list-string-is-read-as-the-list
  ;; tb21-full2 query-optimize: the supervisor's `remember` passed its basis
  ;; as the string "[\"B1:t3\", \"B1:t4\"]" and was refused: split on commas
  ;; it read as `["B1:t3"` and `"B1:t4"]`, neither a reference.
  (is (= ["B1:t3" "B1:t4"] (basis/refs "[\"B1:t3\", \"B1:t4\"]")))
  (is (= ["t3" "a#2"] (basis/refs "['t3', 'a#2']")))
  (is (= ["t3" "B2:t7"] (basis/refs "t3, B2:t7")))
  (testing "a turn written branch-last, as session-window-debug's supervisor did"
    (is (= ["B1:t122"] (basis/refs "t122/B1")))
    (is (= ["B1:t122"] (basis/refs "B1/t122")))))

(defn- run-tool [c rid name args]
  (tools/run-tool {:tool-name name :args args :conn c :run-id rid :turn 5
                   :branch (state/new-branch {:id "B1" :problem "p"})}))

(deftest a-memory-that-cites-nothing-real-is-refused
  (with-run [c rid]
    (journal/record-turn! c rid {:branch-id "B1" :turn 2 :tool-name "shell" :args {} :result "ok"
                                 :category :success})
    (let [r (run-tool c rid "remember" {:content "requires of flight.* fail here" :kind "note"
                                        :basis ["t2" "t40"]})]
      (is (not= :success (:category r)))
      (is (str/includes? (:result r) "t40") "it names the reference that did not resolve"))
    (testing "one that cites what happened is kept, with its basis"
      (let [r (run-tool c rid "remember" {:content "the suite needs -A:test" :kind "note"
                                          :basis ["t2"]})
            row (first (knowledge/recall c "suite"))]
        (is (= :neutral (:category r)) (:result r))
        (is (str/includes? (str (:cause row)) "t2"))))))

(deftest a-directive-that-cites-nothing-real-is-refused
  (with-run [c rid]
    (runs/open-branch! c rid {:branch-id "B2"})
    (let [r (run-tool c rid "intervene" {:kind "message" :branch "B2" :text "requires fail, stop trying"
                                         :basis ["B2:t4"]})]
      (is (str/includes? (str (:result r)) "B2:t4") (:result r))
      (is (empty? (samizdat.store.interventions/pending c rid "B2")) "nothing was sent"))))
