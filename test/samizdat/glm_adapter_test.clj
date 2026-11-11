;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.glm-adapter-test
  "GLM-5.3 as its vendor's own harness drives it (ZCode: config/provider/zcode-builtin.json
  and its GLM-5.3 adapter design). Settings carried over from other models scored 0 of 2217
  hidden tests on ProgramBench."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.infer :as infer]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.openai :as openai]
            [samizdat.llm.message :as message]
            [samizdat.llm.toolspec :as toolspec]
            [samizdat.prompt :as prompt]))

(def ^:private glm (:glm config/providers-for-test))

(deftest the-model-has-its-published-room
  (is (= 1000000 (:context-window glm)))
  (is (= 128000 (:max-tokens glm)))
  (testing "and a project that names the provider still gets it"
    (let [llm (:llm (config/load-config {:providers {:glm {:type :glm :model "glm-5.3" :api-key-env "ZHIPU_API_KEY"}}
                                         :roles {:default :glm}}))]
      (is (= 1000000 (:context-window llm)))
      (is (= 128000 (:max-tokens llm))))))

(deftest temperature-is-left-to-the-model
  (is (nil? (:temperature glm))))

(defn- wire [effort]
  (adapter/chat-body openai/glm {:model "glm-5.3" :reasoning-effort effort}
                     {:messages [{:role "user" :content "hi"}]}))

(deftest effort-is-sent-the-way-the-vendor-sends-it
  (testing "thinking on, beside the level"
    (doseq [e ["low" "high" "max"]]
      (let [b (wire e)]
        (is (= {:type "enabled"} (:thinking b)) e)
        (is (= e (:reasoning_effort b)) e))))
  (testing "it has no medium"
    (is (= "high" (:reasoning_effort (wire "medium")))))
  (testing "it cannot be turned off, so off is the least it will think"
    (is (= "low" (:reasoning_effort (wire "none")))))
  (testing "another provider's wire is unchanged"
    (let [b (adapter/chat-body openai/deepseek {:model "m" :reasoning-effort "high"}
                               {:messages [{:role "user" :content "hi"}]})]
      (is (= "high" (:reasoning_effort b)))
      (is (nil? (:thinking b))))))

(def ^:private history
  [{:role "user" :content "go"}
   {:role "assistant" :content "<think>the plan is X</think>\n\ncalling it"}
   {:role "user" :content "result"}])

(deftest past-reasoning-goes-back-when-the-provider-wants-it
  (testing "by default it is stripped"
    (let [[_ a] (message/prepare history)]
      (is (= "calling it" (:content a)))
      (is (nil? (:reasoning_content a)))))
  (testing "replayed, it moves to its own field and out of the text"
    (let [[_ a] (message/prepare history {:replay-reasoning? true})]
      (is (= "calling it" (:content a)))
      (is (= "the plan is X" (:reasoning_content a)))))
  (testing "a turn with no reasoning has no field"
    (let [[a] (message/prepare [{:role "assistant" :content "plain"}] {:replay-reasoning? true})]
      (is (not (contains? a :reasoning_content)))))
  (testing "and only for assistant turns"
    (let [[u] (message/prepare [{:role "user" :content "<think>quoted</think>hi"}] {:replay-reasoning? true})]
      (is (not (contains? u :reasoning_content)))))
  (testing "their vendors' harnesses both do it, and a generic endpoint is left alone"
    (is (config/supports? glm :replay-reasoning))
    (is (config/supports? (:deepseek config/providers-for-test) :replay-reasoning))
    (is (not (config/supports? (:openai config/providers-for-test) :replay-reasoning)))))

(deftest deepseek-has-its-vendors-defaults
  (let [ds (:deepseek config/providers-for-test)]
    (is (= 1048576 (:context-window ds)))
    (is (= 256000 (:max-tokens ds)))
    (is (= "high" (:reasoning-effort ds)))))

(deftest no-temperature-is-sent-unless-a-run-asks
  (is (nil? (config/provider-temperature :glm)))
  (is (nil? (config/provider-temperature :deepseek)))
  (is (= 0.7 (config/provider-temperature :openai)))
  (is (not (contains? (wire "high") :temperature))))

(deftest the-system-prompt-says-to-act
  (let [p (prompt/render "system" {:repl true})]
    (is (str/includes? p "When you have enough information to act, act."))
    (is (str/includes? p "Do not re-derive"))))

(deftest a-branch-with-a-smaller-window-can-still-make-a-call
  ;; Oversight folds its branch against a 64K window. The full reply reserve does not fit
  ;; in that, so with nothing naming what the provider takes, every supervisor call on
  ;; fix2 was refused before it was sent.
  (let [tape {:id "SUP" :messages [{:role "user" :content "go"}] :turns []}
        prepared (infer/prepare {:llm-adapter ::a :llm-config (assoc glm :context-window 64000)}
                                tape {:fit? true})]
    (is (>= (:request-window glm) (:context-window glm)))
    (is (not (false? (:ok prepared))))
    (is (true? (get-in prepared [:accounting :admissible?])))))

(deftest a-tool-with-actions-declares-what-its-actions-take
  ;; `task({action, ...})` became a schema naming only `action`. Sent as real tool
  ;; messages, GLM sent exactly that, `task create` needed a title it never got, and a
  ;; write needs a claimed task, so nothing could be written.
  (let [specs (into {} (map (juxt :name identity))
                    (toolspec/signatures (slurp (clojure.java.io/resource "prompts/system-tools.md"))))
        props (fn [tool] (set (keys (get-in specs [tool :parameters :properties]))))]
    (testing "the arguments named on its action lines"
      (is (every? (props "task") [:action :title :body :parentId :id :status :reason]))
      (is (every? (props "message") [:action :body :to]))
      (is (every? (props "cell") [:action :name :version :clj])))
    (testing "and it can be sent others, since the signature says there are more"
      (is (true? (get-in specs ["task" :parameters :additionalProperties]))))
    (testing "a signature that lists everything stays closed to that list"
      (is (nil? (get-in specs ["edit_file" :parameters :additionalProperties]))))
    (testing "only argument names, not the code in its examples"
      (is (not-any? #(re-find #"^:" (name %)) (props "manifest"))))))

(deftest the-prompt-does-not-say-thinking-is-dropped
  ;; DeepSeek and GLM have their reasoning replayed, so telling them it is gone was false.
  (let [turn (prompt/prompt "system-turn")]
    (is (not (str/includes? turn "not kept between turns")))
    (is (str/includes? turn "visible sentences"))))
