;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.sim-test
  "Self-improvement memory (karamazov-amgk), after 'Self-Improvement Memory
  Enables Continual Recursive Self-Improvement': every change the supervisor
  makes is an ATTEMPT on a named PROBLEM, with its rationale, what it was
  predicted to fix, what it did, and the model and project it was measured
  under — kept whole, never overwritten, and carried to the next model and
  the next project."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.experiments]
            [samizdat.layers :as layers]
            [samizdat.session :as session]
            [samizdat.sim :as sim]
            [samizdat.store.db :as db]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]
            [samizdat.store.sim :as store]
            [samizdat.store.userspace :as store-us]
            [samizdat.userspace :as us]))

(defn- with-db [f]
  (let [c (db/open! ":memory:")]
    (try (f c) (finally (db/close c)))))

(defn- run! [c model]
  (runs/start-run! c {:problem "p" :provider "deepseek" :model model}))

;; --- the record --------------------------------------------------------------

(deftest a-problem-named-twice-is-one-problem
  (with-db
    (fn [c]
      (let [a (store/problem-id! c "Premature completion" "r1")
            b (store/problem-id! c "  premature   completion " "r2")]
        (is (= a b) "identical root-cause text, up to case and spacing, is one entry")
        (is (= "r1" (:opened_run (first (store/problems c)))) "opened by the first run")))))

(deftest every-attempt-is-kept-with-its-model
  (with-db
    (fn [c]
      (let [r1 (run! c "deepseek-v4-flash")
            r2 (run! c "glm-5")
            p (store/problem-id! c "parse errors on fenced calls" r1)]
        (store/record-attempt! c {:problem-id p :run-id r1 :experiment "e1"
                                  :change "strip fences" :hypothesis "fewer parse errors"
                                  :target {:kind "cell" :name "parse" :version 2}
                                  :predicted ["case-a"] :project "/proj"})
        (store/record-attempt! c {:problem-id p :run-id r2 :experiment "e2"
                                  :change "repair fences" :hypothesis "fewer again"})
        (let [[{:keys [attempts]}] (store/problems c)]
          (is (= ["strip fences" "repair fences"] (mapv :change attempts))
              "a second attempt is appended, not written over the first")
          (is (= ["deepseek-v4-flash" "glm-5"] (mapv :model attempts))
              "each carries the model its run was on")
          (is (= ["case-a"] (:predicted (first attempts))))
          (is (= {:kind "cell" :name "parse" :version 2} (:target (first attempts)))))))))

(deftest an-attempt-is-settled-with-what-it-did
  (with-db
    (fn [c]
      (let [id (store/record-attempt! c {:change "c" :hypothesis "h"})]
        (store/settle-attempt! c id {:verdict :worse :before 0.5 :after 0.4 :prediction-hit false})
        (let [a (first (store/attempts c))]
          (is (= "worse" (:verdict a)))
          (is (= [0.5 0.4] [(:before a) (:after a)]))
          (is (false? (:prediction_hit a))))
        (testing "a later settle does not erase what an earlier one said"
          (store/settle-attempt! c id {:settled :reverted})
          (is (= ["worse" "reverted"] ((juxt :verdict :settled) (first (store/attempts c))))))))))

(deftest predictions-settle-against-the-battery
  (with-db
    (fn [c]
      (let [check! (fn [case before after]
                     (db/execute! c ["INSERT INTO heldout_checks (edit_kind, edit_name, case_id, target,
                                       ok_before, ok_after, accepted, created_at)
                                     VALUES ('cell', 'parse', ?, 'build', ?, ?, 1, ?)"
                                    case before after (db/now)]))
            id (store/record-attempt! c {:change "c" :hypothesis "h"
                                         :target {:kind "cell" :name "parse" :version 2}
                                         :predicted ["fixed-one" "missed-one" "never-run"]})]
        (check! "fixed-one" 0 1)
        (check! "missed-one" 0 0)
        (store/settle-predictions! c id)
        (let [a (first (store/attempts c))]
          (is (= ["fixed-one"] (:fixed a)))
          (is (= ["missed-one"] (:missed a)))
          (is (= ["never-run"] (:unresolved a)) "a case nothing has replayed stays open"))))))

;; --- model provenance on the edits themselves --------------------------------

(deftest a-version-carries-the-model-of-the-run-that-made-it
  (with-db
    (fn [c]
      (let [r (run! c "deepseek-v4-flash")]
        (store-us/save! c :prompt "task" "words" "project" "why" {:run-id r})
        (is (= {:run_id r :model "deepseek-v4-flash"}
               (select-keys (store-us/load-latest c "prompt" "task") [:run_id :model])))))))

(deftest edits-made-under-another-model-are-named-after-a-switch
  (with-db
    (fn [c]
      (let [old (run! c "deepseek-v4-flash")
            now (run! c "bonsai-27b")
            p (store/problem-id! c "fence echo" old)]
        (store-us/save! c :prompt "task" "workaround" "project" "work around the echo" {:run-id old})
        (store-us/save! c :policy "gates" "{}" "project" "tighter budget" {:run-id now})
        (store/record-attempt! c {:problem-id p :run-id old :change "workaround"
                                  :hypothesis "no echo"
                                  :target {:kind "prompt" :name "task" :version 1}})
        (let [ms (store/model-mismatches c "bonsai-27b")]
          (is (= [["prompt" "task" "deepseek-v4-flash"]] (mapv (juxt :kind :name :model) ms))
              "only the edit in force that was made on another model")
          (is (= "work around the echo" (:rationale (first ms))))
          (is (= ["workaround"] (mapv :change (:attempts (first ms))))
              "with what was tried on it"))
        (is (= [["policy" "gates"]] (mapv (juxt :kind :name) (store/model-mismatches c "deepseek-v4-flash")))
            "back on the first model, only the edit made on the second is named")))))

;; --- carried across projects ---------------------------------------------------

(defmacro ^:private with-file
  "Run `body` with the person-level file at `p` (nil: wherever the config
  dir says), putting back what the test runner set."
  [p & body]
  `(let [held# @sim/path-override]
     (reset! sim/path-override ~p)
     (try ~@body (finally (reset! sim/path-override held#)))))

(deftest the-record-follows-the-person-to-another-project
  (let [dir (str "/tmp/samizdat-sim-" (random-uuid))]
    (fs/create-dirs dir)
    (try
      (with-file (str dir "/sim.edn")
        (with-db
          (fn [a]
            (let [r (run! a "deepseek-v4-flash")
                  p (store/problem-id! a "fence echo" r)]
              (store/record-attempt! a {:problem-id p :run-id r :change "strip fences"
                                        :hypothesis "h" :verdict :better
                                        :target {:kind "prompt" :name "task" :version 2}})
              (sim/publish! a "/projects/a")
              (store/record-attempt! a {:problem-id p :run-id r :change "unrelated"
                                        :hypothesis "h" :target {:kind "cell" :name "other"}})
              (sim/publish! a "/projects/a"))))
        (is (= 2 (count (get-in (edn/read-string (slurp (str dir "/sim.edn")))
                                [:projects "/projects/a" :problems 0 :attempts])))
            "a republish replaces the project's record rather than appending a copy")
        (with-db
          (fn [b]
            (store-us/save! b :prompt "task" "mine" "project" nil)
            (let [fs (sim/foreign b "/projects/b" {:max-problems 5})]
              (is (= ["fence echo"] (mapv :name fs)) "a problem met on a role this project also has")
              (is (= "/projects/a" (:project (first fs))))
              (is (= ["strip fences"] (mapv :change (:attempts (first fs))))
                  "only the attempts on the roles this project has"))
            (is (empty? (sim/foreign b "/projects/a" {:max-problems 5}))
                "a project is not shown its own record as foreign"))))
      (finally (fs/delete-tree dir)))))

(deftest the-file-is-in-the-persons-config-dir
  (with-file nil
    (with-redefs [layers/global-dir (constantly "/cfg/samizdat")]
      (is (= "/cfg/samizdat/sim.edn" (sim/path))))
    (testing "and with no config dir nothing is carried"
      (with-redefs [layers/global-dir (constantly nil)]
        (with-db
          (fn [c]
            (is (nil? (sim/publish! c "/projects/a")))
            (is (= [] (sim/foreign c "/projects/a" {:max-problems 5})))))))))

;; --- the tools ---------------------------------------------------------------

(deftest an-experiment-is-filed-under-its-problem-before-it-is-measured
  (with-db
    (fn [c]
      (session/reset!)
      (let [r (run! c "glm-5")
            out (str (:result (base/run-tool {:tool-name "experiment" :conn c :run-id r
                                              :args {"name" "e1" "change" "strip fences"
                                                     "hypothesis" "fewer parse errors"
                                                     "problem" "parse errors on fenced calls"
                                                     "fixes" "case-a, case-b"}
                                              :branch {:id "SUP"}})))
            [{:keys [id name attempts]}] (store/problems c)]
        (is (str/includes? out (str "P" id)) "the reply names the problem it was filed under")
        (is (= "parse errors on fenced calls" name))
        (is (= ["case-a" "case-b"] (:predicted (first attempts)))
            "the prediction is on record before anything is measured")
        (is (= "glm-5" (:model (first attempts))))
        (testing "citing the problem by its id files the next attempt there"
          (session/reset!)
          (base/run-tool {:tool-name "experiment" :conn c :run-id r
                          :args {"name" "e2" "change" "repair fences" "hypothesis" "h"
                                 "problem" (str "P" id)}
                          :branch {:id "SUP"}})
          (is (= 1 (count (store/problems c))))
          (is (= 2 (count (:attempts (first (store/problems c)))))))
        (testing "settling it records the decision on the attempt"
          (base/run-tool {:tool-name "verdict" :conn c :run-id r
                          :args {"name" "e2" "action" "kept" "why" "fine"}
                          :branch {:id "SUP"}})
          (is (= "kept" (:settled (last (:attempts (first (store/problems c))))))))))))

(deftest the-run-end-settles-every-attempt-it-measured
  (with-db
    (fn [c]
      (let [id (store/record-attempt! c {:change "c" :hypothesis "h"})]
        (knowledge/distil-session! c {:run-id "r1"
                                      :experiments [{:name "e" :change "c" :hypothesis "h"
                                                     :attempt-id id :verdict :unchanged
                                                     :before 0.5 :after 0.5}]})
        (is (= "unchanged" (:verdict (first (store/attempts c)))))))))
