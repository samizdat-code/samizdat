;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.native-history-test
  "History as real tool messages, on the wire only (karamazov-n7n5)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.openai :as openai]
            [samizdat.llm.message :as message]
            [samizdat.llm.native-history :as native]))

(defn- call [tool args] (str "```tool-call\n" (json/write-str {:name tool :args args}) "\n```"))

(def ^:private history
  [{:role "system" :content "sys"}
   {:role "user" :content "go"}
   {:role "assistant" :content (str "Looking.\n\n" (call "read_file" {:path "a.clj"})) :reasoning_content "need the file"}
   {:role "user" :content (str (message/frame-result "read_file" "(ns a)") "\n\n---\n\n[harness] a steer")}
   {:role "assistant" :content (call "shell" {:command "ls"})}
   {:role "user" :content (message/frame-result "shell" "a.clj")}])

(deftest a-call-and-its-result-become-tool-messages
  (let [[_ _ a t u a2 t2 :as out] (native/project history)]
    (is (= 7 (count out)))
    (testing "the assistant keeps its words and its reasoning"
      (is (= "assistant" (:role a)))
      (is (= "Looking." (:content a)))
      (is (= "need the file" (:reasoning_content a)))
      (is (= "call_2" (get-in a [:tool_calls 0 :id])))
      (is (= "read_file" (get-in a [:tool_calls 0 :function :name])))
      (is (= {"path" "a.clj"} (json/read-str (get-in a [:tool_calls 0 :function :arguments])))))
    (testing "the result is the tool's, with no frame"
      (is (= {:role "tool" :tool_call_id "call_2" :content "(ns a)"} t)))
    (testing "what the harness added stays apart from it"
      (is (= {:role "user" :content "---\n\n[harness] a steer"} u)))
    (testing "and a result with nothing after it makes no empty message"
      (is (= "" (:content a2)))
      (is (= "tool" (:role t2))))))

(deftest what-does-not-pair-stays-text
  (doseq [msgs [[{:role "assistant" :content (call "shell" {:command "ls"})}]
                [{:role "assistant" :content (call "shell" {:command "ls"})}
                 {:role "user" :content "[harness] that did not run"}]
                [{:role "assistant" :content (call "shell" {:command "ls"})}
                 {:role "user" :content (message/frame-result "read_file" "x")}]
                [{:role "assistant" :content "```tool-call\n{broken\n```"}
                 {:role "user" :content "[harness] did not parse"}]
                [{:role "assistant" :content "no call at all"}
                 {:role "user" :content (message/frame-result "shell" "x")}]]]
    (is (= msgs (native/project msgs)) (pr-str msgs))))

(deftest the-same-history-gives-the-same-bytes
  (is (= (native/project history) (native/project history)))
  (testing "and a later turn leaves the earlier messages as they were"
    (let [longer (into history [{:role "assistant" :content "done talking"}])]
      (is (= (native/project history) (vec (butlast (native/project longer))))))))

(defn- body [adapter cfg opts]
  (adapter/chat-body adapter cfg (merge {:messages history
                                         :tools [{:name "read_file" :parameters {}}]} opts)))

(deftest the-adapter-sends-them-when-the-provider-has-them
  (let [glm (assoc (:glm config/providers-for-test) :model "glm-5.3")
        ds (assoc (:deepseek config/providers-for-test) :model "deepseek-flash")
        roles #(mapv :role (:messages %))]
    (is (config/supports? glm :native-history))
    (is (config/supports? ds :native-history))
    (is (some #{"tool"} (roles (body openai/glm glm {}))))
    (is (some #{"tool"} (roles (body openai/deepseek ds {}))))
    (testing "a call a provider needs reasoning on always has the field"
      (let [a (second (filter :tool_calls (:messages (body openai/deepseek ds {}))))]
        (is (= "" (:reasoning_content a)))))
    (testing "with no tools sent, a side call, history stays text"
      (is (not-any? #{"tool"} (roles (body openai/glm glm {:tools nil})))))
    (testing "and beside a prefill, which refuses native calls"
      (is (not-any? #{"tool"} (roles (body openai/deepseek ds {:prefill "```tool-call"})))))
    (testing "a provider without the feature is left alone"
      (let [o (assoc (:openai config/providers-for-test) :model "m" :features #{:native-tools})]
        (is (not-any? #{"tool"} (roles (body openai/openai o {}))))))))
