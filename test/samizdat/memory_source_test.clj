;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.memory-source-test
  "Where a memory came from: the branch and the turns it was written from
  (karamazov-4dn8.3). A row kept only its run id, so nothing could put a
  memory back beside the trajectory it was drawn from — which is what
  revising it against its source needs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.reflect :as reflect]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]))

(defn- span [conn id]
  (select-keys (knowledge/get-by-id conn id)
               [:source_branch :source_turn_from :source_turn_to]))

(deftest a-memory-keeps-its-source-and-a-restatement-carries-it
  (let [c (db/open! ":memory:")
        id (knowledge/remember! c {:content "x" :kind "semantic"
                                   :source {:branch "T0" :from 3 :to 9}})]
    (is (= {:source_branch "T0" :source_turn_from 3 :source_turn_to 9} (span c id)))
    (is (= {:source_branch "T0" :source_turn_from 3 :source_turn_to 9}
           (span c (knowledge/restate! c id "y")))
        "a new wording of the same memory comes from the same place")
    (testing "unknown is NULL"
      (is (= {:source_branch nil :source_turn_from nil :source_turn_to nil}
             (span c (knowledge/remember! c {:content "z" :kind "semantic"})))))))

(deftest a-reflection-is-sourced-to-the-branch-it-reflected-on
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (doseq [t [4 5 6]]
      (journal/record-turn! c rid {:branch-id "T0" :turn t :tool-name "shell"
                                   :result "ok" :category :success}))
    (with-redefs [samizdat.llm.client/chat (fn [& _] {:content "## FACTS\n- builds with jolt bin"})]
      (reflect/distil-task! {:conn c :run-id rid} {:id "T0" :problem "p"}))
    (let [row (first (filter #(str/includes? (:content %) "jolt bin") (knowledge/recent c 10)))]
      (is (= {:source_branch "T0" :source_turn_from 4 :source_turn_to 6} (span c (:id row)))))))

(deftest a-command-fact-is-sourced-to-the-turn-that-ran-it
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (journal/record-turn! c rid {:branch-id "T1" :turn 12 :tool-name "shell"
                                 :args "{\"command\":\"jolt test\"}" :result "ok" :category :success})
    (let [[{:keys [id]}] (knowledge/distil-project! c {:run-id rid})]
      (is (= {:source_branch "T1" :source_turn_from 12 :source_turn_to 12} (span c id))))))

(deftest the-remember-tool-sources-to-the-calling-turn
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        r (tools/run-tool {:branch (state/new-branch {:id "T2" :problem "p"})
                           :conn c :run-id rid :turn 17 :tool-name "remember"
                           :args {:content "the renderer needs a GL context" :kind "semantic"}})
        id (some->> (str (:result r)) (re-find #"k-[0-9a-f]+"))]
    (is (some? id) (str (:result r)))
    (is (= {:source_branch "T2" :source_turn_from 17 :source_turn_to 17} (span c id)))))

(deftest the-source-reads-back-as-the-turns-it-came-from
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (doseq [t (range 1 8)]
      (journal/record-turn! c rid {:branch-id "T0" :turn t :tool-name "shell"
                                   :result (str "out " t) :category :success}))
    (let [id (knowledge/remember! c {:content "x" :kind "semantic" :run-id rid
                                     :source {:branch "T0" :from 3 :to 5}})
          lines (reflect/source-lines c (knowledge/get-by-id c id))]
      (is (= 3 (count lines)))
      (is (str/starts-with? (first lines) "t3 shell"))
      (testing "a row with no source has none"
        (is (empty? (reflect/source-lines c (knowledge/get-by-id
                                              c (knowledge/remember! c {:content "y" :kind "semantic"})))))))))
