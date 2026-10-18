;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.acp.agent
  "Samizdat as an Agent Client Protocol agent (karamazov-05z0.17, after
  autolith's `autolith acp`): what lets an editor — Zed, Emacs agent-shell,
  JetBrains, Neovim — drive a run.

  A STRICT HTTP CLIENT of the server, as the TUI is: nothing here runs a
  workflow. A prompt starts a run (POST /v1/runs), or steers the run the
  session already has; the run's journal becomes the session's updates; a
  question the run puts to a person becomes a permission request to the
  editor; a cancel interrupts the run. Every effect — the HTTP calls, the
  messages to the editor, the clock — is passed in, so the whole of it is
  testable as data.

  One session is one run at a time. A prompt while that run is still going
  is a directive to it; a prompt after it ended starts the next run, with
  the last answer as context — the server's runs do not continue a
  conversation, so a multi-prompt session is a sequence of runs."
  (:require [clojure.string :as str]))

(def protocol-version 1)

(defn initialize-result
  "What `initialize` answers: the protocol version and what this agent can do."
  []
  {:protocolVersion protocol-version
   :agentCapabilities {:loadSession false
                       :promptCapabilities {:image false :audio false :embeddedContext false}}
   :authMethods []})

;; --- the journal, as session updates ----------------------------------------

(def ^:private tool-kinds
  {"read_file" "read" "read_digest" "read" "lsp" "read"
   "grep" "search" "glob" "search"
   "write_file" "edit" "edit_file" "edit" "patch" "edit"
   "shell" "execute" "eval" "execute"
   "webfetch" "fetch" "websearch" "fetch"})

(defn- call-id [{:keys [branch_id turn]}] (str branch_id "-" turn))

(defn- prose
  "What the model said before its call, without the call's fence."
  [said]
  (some-> (str said)
          (str/replace #"(?s)```tool-call.*?```" "")
          (str/replace #"(?s)<think>.*?</think>" "")
          str/trim
          not-empty))

(defn updates
  "The ACP session updates one journal event stands for, in order."
  [{:keys [kind data] :as event}]
  (case kind
    "dispatch"
    (cond-> []
      (prose (:said data))
      (conj {:sessionUpdate "agent_thought_chunk"
             :content {:type "text" :text (prose (:said data))}})
      true
      (conj {:sessionUpdate "tool_call"
             :toolCallId (call-id event)
             :title (str (:tool data))
             :kind (get tool-kinds (str (:tool data)) "other")
             :status "in_progress"
             :rawInput (or (:args data) {})}))

    "turn"
    (if (str/starts-with? (str (:tool data)) "__")
      []
      [{:sessionUpdate "tool_call_update"
        :toolCallId (call-id event)
        :status (if (#{"failure" "mechanics"} (str (:category data))) "failed" "completed")}])

    []))

(defn stop-reason
  "The prompt's stopReason for how the run ended."
  [status]
  (case (str status)
    "exhausted" "max_turn_requests"
    ("aborted" "interrupted" "cancelled") "cancelled"
    "end_turn"))

(defn finished
  "The run's ending in a batch of journal events, or nil."
  [events]
  (some #(when (= "run-finished" (:kind %)) (get-in % [:data :status])) events))

;; --- permission questions ---------------------------------------------------

(def permission-options
  [{:optionId "allow" :name "Allow" :kind "allow_once"}
   {:optionId "deny" :name "Deny" :kind "reject_once"}])

(defn permission-request
  "The session/request_permission params for one pending approval."
  [session-id {:keys [id input reason details]}]
  {:sessionId session-id
   :toolCall {:toolCallId (str "approval-" id)
              :title (str (or (not-empty (str reason)) input))
              :kind "execute"
              :status "pending"
              :rawInput {:input input :details details}}
   :options permission-options})

(defn decision
  "The approval decision a permission response stands for."
  [response]
  (if (= "allow" (get-in response [:outcome :optionId])) :allow :deny))

;; --- a prompt ---------------------------------------------------------------

(defn prompt-text
  "The text of an ACP prompt's content blocks."
  [blocks]
  (str/join "\n\n" (keep (fn [b] (case (:type b)
                                   "text" (:text b)
                                   "resource_link" (str "(" (:uri b) ")")
                                   "resource" (get-in b [:resource :text])
                                   nil))
                         blocks)))

(defn run-prompt!
  "Serve one session/prompt to its end and return its result.

  `io`: {:start-run! (fn [problem] -> run-id | {:error})
         :intervene! (fn [run-id text])
         :journal (fn [run-id cursor] -> {:events [...] :next n})
         :run-detail (fn [run-id] -> {:final_answer :status})
         :approvals (fn [run-id] -> [pending…])
         :ask-permission! (fn [params] -> response)
         :decide! (fn [approval-id decision])
         :update! (fn [update])        ; a session/update notification
         :sleep! (fn [])
         :cancelled? (fn [] -> bool)}
  `session` is an atom {:id :run-id :cursor :answer}."
  [io session blocks]
  (let [{:keys [start-run! intervene! journal run-detail approvals ask-permission!
                decide! update! sleep! cancelled?]} io
        text (prompt-text blocks)
        live (:run-id @session)]
    (if (and live (not (:ended? @session)))
      ;; A run already going: this prompt is a directive to it.
      (do (intervene! live text)
          (update! {:sessionUpdate "agent_message_chunk"
                    :content {:type "text" :text "(sent to the run in progress)"}})
          {:stopReason "end_turn"})
      (let [problem (if-let [a (:answer @session)]
                      (str text "\n\nThe previous run in this session answered:\n" a)
                      text)
            started (start-run! problem)]
        (if (map? started)
          (do (update! {:sessionUpdate "agent_message_chunk"
                        :content {:type "text" :text (str "Could not start a run: " (:error started))}})
              {:stopReason "refusal"})
          (do
            (swap! session assoc :run-id started :cursor 0 :ended? false :asked #{})
            (loop []
              (let [{:keys [events next]} (journal started (:cursor @session))]
                (doseq [e events, u (updates e)] (update! u))
                (swap! session assoc :cursor (or next (:cursor @session)))
                ;; Questions the run is waiting on, put to the editor once each.
                (doseq [a (approvals started)
                        :when (not (contains? (:asked @session) (:id a)))]
                  (swap! session update :asked conj (:id a))
                  (decide! (:id a) (decision (ask-permission! (permission-request (:id @session) a)))))
                (if-let [status (finished events)]
                  (let [{:keys [final_answer]} (run-detail started)]
                    (when-not (str/blank? (str final_answer))
                      (update! {:sessionUpdate "agent_message_chunk"
                                :content {:type "text" :text (str final_answer)}}))
                    (swap! session assoc :ended? true :answer (not-empty (str final_answer)))
                    {:stopReason (stop-reason status)})
                  (if (cancelled?)
                    {:stopReason "cancelled"}
                    (do (sleep!) (recur))))))))))))
