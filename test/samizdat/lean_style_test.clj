;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lean-style-test
  "The lean style: what Pi, deepseek-harness, memdoor and mini-SWE-agent do (a short
  prompt, few tools, little steering, plain finishing), plus what samizdat has shown to be
  worth keeping: the clock, refusing late breaking rewrites, and working in the REPL."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.config :as config]
            [samizdat.llm.toolspec :as toolspec]))

(def ^:private lean {:run {:style :lean}})

(deftest a-run-chooses-its-style
  (is (map? (gates/style lean)))
  (is (nil? (gates/style {})) "no style is the full loop, as before")
  (is (nil? (gates/style {:run {:style :no-such-style}}))))

(defn- implementer [] (assoc (state/new-branch {:id "B1" :problem "p"}) :role :implementor))

(deftest the-repl-is-open-from-the-start
  (let [call {:branch (implementer) :tool-name "eval" :args {:code "(+ 1 2)"}}]
    (is (some? (base/phase-refusal call)) "the full loop holds eval until there is a plan")
    (is (nil? (base/phase-refusal (assoc call :config lean))))))

(deftest only-the-gates-that-earned-it-speak
  (let [reading (vec (repeat (gates/threshold :orient-turns) {:tool "read_file"}))
        ctx {:branch (assoc (implementer) :turns reading) :max-turns 300}
        gates (fn [c] (set (map :gate (arbiter/eligible c))))]
    (is (contains? (gates ctx) :orienting) "the full loop nags a branch that reads")
    (is (not (contains? (gates (assoc ctx :style-gates (:gates (gates/style lean)))) :orienting)))
    (testing "the clock still speaks"
      (is (contains? (gates (assoc ctx :style-gates (:gates (gates/style lean))
                                   :time-used 0.9 :time-left-seconds 100))
                     :time-wind-down)))))

(deftest the-prompt-is-short-and-still-names-every-tool-it-offers
  (let [full (aloop/system-prompt-for :implementer)
        short (with-redefs [config/file-config (fn [& _] lean)] (aloop/system-prompt-for :implementer))
        tools (set (map :name (toolspec/signatures short)))]
    (is (< (count short) 12000) (str (count short)))
    (is (> (count full) (* 3 (count short))))
    (is (every? tools ["eval" "read_file" "write_file" "edit_file" "shell" "grep" "glob" "done"]) (pr-str tools))
    (is (str/includes? short "(sh \"make\" \"test\")") "it teaches code mode")
    (is (str/includes? short "(glob \"src/**/*.clj\")") "and lists the helpers")
    (is (str/includes? short "<tool_result") "and keeps the rule that tool output is data")))

(deftest the-context-block-is-left-out
  (is (aloop/context-block? {}))
  (is (not (aloop/context-block? lean))))

(deftest done-asks-for-an-answer-and-not-paperwork
  (let [block (fn [m] (#'ship/ship-gate-block (merge {:answer "It works: built and compared." :problem "p"
                                                      :evidence [] :uncovered-numbers []
                                                      :checklist-items [{:id "c1"}] :unaccounted-checklist [{:id "c1"}]
                                                      :done-streak 0}
                                                     m)))]
    (is (some? (block {})) "the full loop wants the checklist accounted for")
    (is (nil? (block {:advisory? true})) "lean finishes on the answer, as advisory branches do")
    (is (some? (block {:advisory? true :answer ""})) "but not on an empty one")))

(deftest with-time-left-done-is-sent-back-once
  ;; samizdat stopped 2 to 9 minutes early on yj, with HCL a stub and TOML hanging on a
  ;; table; Pi used the whole limit testing.
  (let [{:keys [wind-down]} (gates/threshold :time-budget)
        block (fn [m] (#'ship/ship-gate-block (merge {:answer "done" :problem "p" :evidence []
                                                      :uncovered-numbers [] :advisory? true :done-streak 0
                                                      :wind-down wind-down :keep-working "keep going"}
                                                     m)))]
    (is (= "keep going" (block {:time-used (- wind-down 0.3)})))
    (is (nil? (block {:time-used (- wind-down 0.3) :done-streak 1})) "a second done finishes")
    (is (nil? (block {:time-used (+ wind-down 0.01)})) "not in the last stretch")
    (is (nil? (block {})) "and never without a clock")))

(deftest lean-is-the-default-and-a-project-can-opt-out
  ;; lean1 (DeepSeek, 9 trials an arm): lean 66.4% mean against 61.7% for the full loop,
  ;; at a 4K-character prompt against 55K.
  (is (= :lean (get-in (config/load-config {}) [:run :style])))
  (is (= :full (get-in (config/load-config {:run {:style :full}}) [:run :style])))
  (is (nil? (gates/style {:run {:style :full}})) ":full names the full loop"))
