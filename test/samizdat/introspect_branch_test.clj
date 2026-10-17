;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.introspect-branch-test
  "introspect({branch}): the supervisor's way into one branch once the brief
  says it is not moving (karamazov-rz7t.5) — its moves, its recent turns as
  one line each, what it keeps repeating, the steering it is ignoring and its
  last failure in its own words, bounded, with turn numbers fetch_turn takes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defn- stuck-run []
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        turn! (fn [n tool args result cat]
                (journal/record-turn! c rid {:branch-id "T0" :turn n :tool-name tool
                                             :args args :result result :category cat}))]
    (runs/open-branch! c rid {:branch-id "SUP" :role :supervisor})
    (runs/open-branch! c rid {:branch-id "T0" :role :implementor})
    (journal/note! c rid :progress {:branch-id "T0" :turn 1 :data {:key "phase" :from nil :to "explore"}})
    (journal/note! c rid :progress {:branch-id "T0" :turn 3 :data {:key "task" :from nil :to "T0-parse"}})
    (journal/note! c rid :progress {:branch-id "T0" :turn 6 :data {:key "phase" :from "explore" :to "build"}})
    (turn! 1 "read_file" "{\"path\":\"src/a.clj\"}" "(ns a)" :neutral)
    (turn! 2 "write_file" "{\"path\":\"src/a.clj\"}" "wrote" :success)
    (doseq [n (range 3 20)]
      (turn! n "shell" "{\"command\":\"jolt test\"}" (str "FAIL in (parse-test) run " n) :failure))
    (db/execute! c ["INSERT INTO gate_firings (run_id, branch_id, turn, gate, message, outcome, created_at)
                     VALUES (?, 'T0', 12, 'suspect-the-test', 'read the assertion, not the code', 'unmet', '2026-10-15T00:00:00Z')"
                    rid])
    {:c c :rid rid}))

(deftest the-branch-view-is-its-moves-its-tail-and-why-it-is-not-moving
  (let [{:keys [c rid]} (stuck-run)
        r (tools/run-tool {:branch (state/new-branch {:id "SUP" :problem "p"})
                           :conn c :run-id rid :tool-name "introspect"
                           :args {:branch "T0"}})
        out (str (:result r))]
    (is (= :neutral (:category r)) "a lookup establishes nothing")
    (testing "where it has been"
      (is (str/includes? out "explore → build"))
      (is (str/includes? out "T0-parse")))
    (testing "its last turns, one line each, numbered for fetch_turn"
      (is (re-find #"t19\s+shell" out))
      (is (not (re-find #"t1\s+read_file" out)) "bounded to the tail"))
    (testing "what it keeps doing"
      (is (re-find #"shell .*jolt test.*×\s*17|17\s*×.*shell" out)))
    (testing "the steering it has not acted on"
      (is (str/includes? out "suspect-the-test")))
    (testing "its last failure, in its own words"
      (is (str/includes? out "FAIL in (parse-test) run 19")))
    (testing "no whole-run dump"
      (is (not (str/includes? out "LOOP WIRING"))))))

(deftest a-branch-the-run-does-not-have-is-a-miss
  (let [{:keys [c rid]} (stuck-run)
        r (tools/run-tool {:branch (state/new-branch {:id "SUP" :problem "p"})
                           :conn c :run-id rid :tool-name "introspect"
                           :args {:branch "T9"}})]
    (is (= :mechanics (:category r)))
    (is (str/includes? (str (:result r)) "T0") "names the branches it does have")))
