;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.acp-test
  "The ACP agent (karamazov-05z0.17): journal events as session updates, a
  prompt served to the end of its run, and the JSON-RPC routing — all with
  the server and the editor faked."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.acp.agent :as agent]
            [samizdat.acp.main :as main]))

(deftest journal-events-become-session-updates
  (let [d {:kind "dispatch" :branch_id "B1" :turn 3
           :data {:tool "read_file" :args {:path "src/x.clj"}
                  :said "I will read it.\n```tool-call\n{\"name\":\"read_file\"}\n```"}}
        [thought call] (agent/updates d)]
    (is (= "agent_thought_chunk" (:sessionUpdate thought)))
    (is (= "I will read it." (get-in thought [:content :text])) "the prose, not the fence")
    (is (= {:sessionUpdate "tool_call" :toolCallId "B1-3" :title "read_file" :kind "read"
            :status "in_progress" :rawInput {:path "src/x.clj"}}
           call)))
  (is (= [{:sessionUpdate "tool_call_update" :toolCallId "B1-3" :status "failed"}]
         (agent/updates {:kind "turn" :branch_id "B1" :turn 3 :data {:tool "shell" :category "failure"}})))
  (is (empty? (agent/updates {:kind "turn" :data {:tool "__provider_error__"}})) "harness bookkeeping is not a call")
  (is (empty? (agent/updates {:kind "context-block" :data {}})))
  (is (= "max_turn_requests" (agent/stop-reason "exhausted")))
  (is (= "cancelled" (agent/stop-reason "interrupted")))
  (is (= "end_turn" (agent/stop-reason "done"))))

(defn- fake-io [{:keys [batches approvals answer]}]
  (let [sent (atom []) decided (atom []) steered (atom []) asked (atom [])
        batches (atom batches) approvals (atom approvals)]
    {:sent sent :decided decided :steered steered :asked asked
     :io {:start-run! (fn [_] "run-1")
          :intervene! (fn [r t] (swap! steered conj [r t]))
          :journal (fn [_ _] (let [b (first @batches)] (swap! batches rest) {:events (or b []) :next 9}))
          :run-detail (fn [_] {:final_answer answer :status "done"})
          :approvals (fn [_] (let [a @approvals] (reset! approvals []) a))
          :ask-permission! (fn [p] (swap! asked conj p) {:outcome {:outcome "selected" :optionId "allow"}})
          :decide! (fn [aid d] (swap! decided conj [aid d]))
          :update! (fn [u] (swap! sent conj u))
          :sleep! (fn [])
          :cancelled? (constantly false)}}))

(deftest a-prompt-runs-to-the-end-of-its-run
  (let [{:keys [io sent decided asked]}
        (fake-io {:batches [[{:kind "dispatch" :branch_id "B1" :turn 1 :data {:tool "shell" :args {:command "make"}}}]
                            [{:kind "turn" :branch_id "B1" :turn 1 :data {:tool "shell" :category "success"}}
                             {:kind "run-finished" :data {:status "done"}}]]
                  :approvals [{:id "a1" :input "rm -rf build" :reason "clean the build" :status "pending"}]
                  :answer "Built it."})
        session (atom {:id "s1"})
        r (agent/run-prompt! io session [{:type "text" :text "build it"}])]
    (is (= {:stopReason "end_turn"} r))
    (is (= ["tool_call" "tool_call_update" "agent_message_chunk"] (mapv :sessionUpdate @sent)))
    (is (= "Built it." (get-in (last @sent) [:content :text])) "the run's answer is the agent's message")
    (is (= [["a1" :allow]] @decided) "the editor's answer reached the run's question")
    (is (str/includes? (get-in (first @asked) [:toolCall :title]) "clean the build"))
    (testing "the next prompt starts the next run, carrying the last answer"
      (let [seen (atom nil)
            {:keys [io]} (fake-io {:batches [[{:kind "run-finished" :data {:status "done"}}]] :answer "ok"})]
        (agent/run-prompt! (assoc io :start-run! (fn [p] (reset! seen p) "run-2"))
                           session [{:type "text" :text "now test it"}])
        (is (str/includes? @seen "now test it"))
        (is (str/includes? @seen "Built it."))))))

(deftest a-prompt-while-a-run-is-going-steers-it
  (let [{:keys [io steered]} (fake-io {})
        session (atom {:id "s1" :run-id "run-1" :ended? false})]
    (is (= {:stopReason "end_turn"} (agent/run-prompt! io session [{:type "text" :text "also check the docs"}])))
    (is (= [["run-1" "also check the docs"]] @steered))))

(deftest the-protocol-routes-requests-by-method
  (let [out (atom [])
        conn (main/connection #(swap! out conj (json/read-str % :key-fn keyword)))]
    (main/handle! conn "http://x" {:jsonrpc "2.0" :id 1 :method "initialize" :params {:protocolVersion 1}})
    (is (= 1 (get-in (first @out) [:result :protocolVersion])))
    (main/handle! conn "http://x" {:jsonrpc "2.0" :id 2 :method "session/new" :params {:cwd "/tmp" :mcpServers []}})
    (is (string? (get-in (second @out) [:result :sessionId])))
    (main/handle! conn "http://x" {:jsonrpc "2.0" :id 3 :method "session/prompt" :params {:sessionId "nope"}})
    (is (= "no such session" (get-in (nth @out 2) [:error :message])))
    (main/handle! conn "http://x" {:jsonrpc "2.0" :id 4 :method "bogus"})
    (is (= -32601 (get-in (nth @out 3) [:error :code])))))
