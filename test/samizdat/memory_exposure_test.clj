;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.memory-exposure-test
  "Which memories each branch was SHOWN (karamazov-4dn8.1). Only an explicit
  recall was journaled; the breadcrumb index and the learned-since block,
  where nearly every memory reaches a branch, left no record, so nothing could
  say which rows a branch actually read — the reflector judged rows it never
  saw, and a revision step has nothing to revise."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.agent.loop :as loop]
            [samizdat.agent.state :as state]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]))

(defn- remember! [conn content]
  (knowledge/remember! conn {:kind "semantic" :content content}))

(deftest the-index-says-which-rows-it-rendered
  (let [conn (db/open! ":memory:")
        a (remember! conn "parser rejects trailing commas in edn")
        _ (remember! conn "renderer caches glyphs per frame")
        {:keys [text ids]} (knowledge/breadcrumb conn "parser commas")]
    (is (some? text))
    (is (some #{a} ids))
    (is (= text (knowledge/breadcrumb-index conn "parser commas"))
        "the index is the same string it always was")))

(deftest a-branch-records-each-memory-the-first-time-it-is-shown
  (let [conn (db/open! ":memory:")
        prev (runs/start-run! conn {:problem "earlier"})
        _ (runs/finish-run! conn prev :completed nil)
        a (remember! conn "parser rejects trailing commas in edn")
        rid (runs/start-run! conn {:problem "p"})
        b0 (assoc (state/new-branch {:id "T0" :problem "p"}) :current-turn 3)
        {b1 :branch} (#'loop/context-block conn rid b0 "parser commas" false)
        _ (#'loop/context-block conn rid (assoc b1 :current-turn 4) "parser commas" false)
        seen (journal/exposed conn rid "T0")]
    (is (contains? seen a))
    (testing "first sighting, not every turn it stays in the block"
      (is (= 3 (get-in seen [a :turn])))
      (is (= 1 (count (filter #(= a %) (mapcat :ids (journal/notes conn rid :exposed)))))))
    (testing "a recall counts as a showing too"
      (journal/note! conn rid :recall {:branch-id "T0" :data {:ids [999] :turn 9}})
      (is (= {:turn 9 :via "recall"} (get (journal/exposed conn rid "T0") 999))))
    (testing "another branch saw nothing"
      (is (empty? (journal/exposed conn rid "T1"))))))
