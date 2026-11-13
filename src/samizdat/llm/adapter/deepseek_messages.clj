;; samizdat - a claim-first verification harness
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

(ns samizdat.llm.adapter.deepseek-messages
  "DeepSeek over its Anthropic-compatible Messages API, as DeepSeek's own
  harness drives it (deepseek-harness packages/llm/llm-deepseek: serialize.ts,
  translate.ts, adapter.ts), rather than over the OpenAI-compatible chat
  endpoint:

  - POST <base>/v1/messages with `x-api-key` and `anthropic-version`.
  - {model stream max_tokens system messages thinking output_config tools}:
    thinking {type enabled} with output_config {effort low|high|max}, high
    by default; `off` is thinking {type disabled} and no output_config. No
    temperature unless one is set, no tool_choice, no prefill.
  - The first system message is `system`; a later one is a role \"system\"
    message placed after the user turn before the next reply (the model's
    systemPromptUpdate in-history).
  - A past reply is its thinking, text and tool_use blocks, in that order;
    the thinking goes back on every reply (measured: the endpoint refuses a
    tool_use turn without it, `content[].thinking in the thinking mode must
    be passed back`), unsigned, as the official harness sends history whose
    signature it no longer holds. A tool result is a tool_result block at
    the head of the next user message, and consecutive same-role messages
    merge.
  - The stream is typed events ending on message_stop.

  Measured on the endpoint, 2026-11-12: a forced tool_choice is refused in
  thinking mode, and a trailing assistant message is not continued — so a
  steered turn here is steered by words, as on the official harness."
  (:require ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.openai :as openai]
            [samizdat.llm.native-history :as native-history]))

;; --- the request -------------------------------------------------------------

(def ^:private anthropic-version "2023-06-01")

(defn messages-root
  "The root Messages resources live under: `base` with /v1, unless it
  already ends in /v1 (the official harness's messagesApiRoot)."
  [base]
  (let [b (str/replace (str base) #"/+$" "")]
    (if (str/ends-with? b "/v1") b (str b "/v1"))))

(defn effort-wire
  "Samizdat's effort as the endpoint's: off, low, high or max. The levels
  between collapse onto the endpoint's nearest."
  [effort]
  (case (some-> effort str str/lower-case)
    ("none" "off") "off"
    ("minimal" "low") "low"
    ("medium" "high" nil "") "high"
    ("xhigh" "ultra" "max") "max"
    "high"))

(defn- text-block [text] {:type "text" :text (str text)})

(defn- tool-input
  "A call's JSON arguments as the object tool_use takes; {} when they do not
  read as one (the official harness's toolInput)."
  [raw]
  (let [v (try (json/read-str (str raw)) (catch Throwable _ nil))]
    (if (map? v) v {})))

(defn- assistant-blocks [{:keys [content tool_calls reasoning_content]} thinking?]
  (cond-> []
    thinking? (conj {:type "thinking" :thinking (str reasoning_content)})
    (not (str/blank? (str content))) (conj (text-block content))
    (seq tool_calls) (into (map (fn [tc]
                                  {:type "tool_use" :id (str (:id tc))
                                   :name (get-in tc [:function :name])
                                   :input (tool-input (get-in tc [:function :arguments]))}))
                           tool_calls)))

(defn- push
  "`msgs` with a `role` message of `blocks`, merged into the last one when
  it has the same role. Tool results lead their user message."
  [msgs role blocks]
  (let [prev (peek msgs)]
    (if (= role (:role prev))
      (conj (pop msgs)
            (update prev :content
                    (fn [c] (let [all (into (vec c) blocks)
                                  {results true others false} (group-by #(= "tool_result" (:type %)) all)]
                              (into (vec results) others)))))
      (conj msgs {:role role :content (vec blocks)}))))

(defn wire-messages
  "Chat-shaped `messages` (after native-history's projection) as
  {:system :messages}. `thinking?` says whether replies carry a thinking
  block."
  [messages thinking?]
  (loop [[m & more :as all] (seq messages) system nil out [] pending []]
    (if-not (seq all)
      {:system system
       :messages (if (seq pending) (into out pending) out)}
      (let [role (str (:role m)) text (str (:content m))]
        (case role
          "system"
          (if (and (nil? system) (empty? out))
            (recur more text out pending)
            (recur more system out
                   (conj pending {:role "system" :content [(text-block text)]})))

          "assistant"
          (let [blocks (assistant-blocks m thinking?)
                ;; A system update goes after the user turn it follows,
                ;; before this reply; with none before it, it rides as user
                ;; text, the only place a reply may follow.
                out (if (seq pending)
                      (if (= "user" (:role (peek out)))
                        (into out pending)
                        (reduce #(push %1 "user" (:content %2)) out pending))
                      out)]
            (recur more system (if (seq blocks) (push out "assistant" blocks) out) []))

          "tool"
          (recur more system
                 (push out "user" [{:type "tool_result" :tool_use_id (str (:tool_call_id m))
                                    :content [(text-block text)]}])
                 pending)

          ;; user, and anything else, as user text
          (recur more system
                 (if (str/blank? text) out (push out "user" [(text-block text)]))
                 pending))))))

(defn- tool->wire [{:keys [name description parameters]}]
  {:name name
   :description (str description)
   :input_schema (or parameters {:type "object" :properties {}})})

(defn- body
  [config {:keys [messages max-tokens temperature reasoning-effort tools]}]
  (let [effort (effort-wire (or reasoning-effort (:reasoning-effort config)))
        thinking? (not= "off" effort)
        native (when (and (seq tools) (config/supports? config :native-tools))
                 (mapv tool->wire tools))
        messages (if (and native (config/supports? config :native-history))
                   (native-history/project messages)
                   messages)
        {:keys [system messages]} (wire-messages messages thinking?)]
    (cond-> {:model (:model config)
             :messages messages
             :thinking {:type (if thinking? "enabled" "disabled")}}
      ;; The endpoint requires it; a config always carries one, and a body
      ;; built without (a test, a probe) says so in the 400 rather than here.
      (or max-tokens (:max-tokens config)) (assoc :max_tokens (long (or max-tokens (:max-tokens config))))
      thinking? (assoc :output_config {:effort effort})
      (not (str/blank? (str system))) (assoc :system system)
      (some? temperature) (assoc :temperature temperature)
      native (assoc :tools native))))

;; --- the reply ---------------------------------------------------------------

(defn- stop->finish [r]
  (case r
    ("end_turn" "stop_sequence") "stop"
    "tool_use" "tool_calls"
    "max_tokens" "length"
    (if r (str r) "stop")))

(defn parse-message
  "A Messages reply as {:content :reasoning :finish-reason :model :usage},
  or nil for a body that is not one."
  [body]
  (when (sequential? (:content body))
    (let [blocks (:content body)
          text (->> blocks (filter #(= "text" (:type %))) (map :text) (remove str/blank?))
          calls (->> blocks (filter #(= "tool_use" (:type %)))
                     (map #(openai/call->fence (:name %)
                                               (or (:input_json %) (json/write-str (or (:input %) {}))))))
          thinking (->> blocks (filter #(= "thinking" (:type %))) (map :thinking)
                        (remove #(or (str/blank? (str %)) (= "null" %))))]
      {:content (str/join "\n\n" (concat (map str/trim text) calls))
       :reasoning (not-empty (str/join "\n\n" thinking))
       :finish-reason (stop->finish (:stop_reason body))
       :model (some-> (:model body) str not-empty)
       :usage (when-let [u (:usage body)]
                ;; input_tokens leaves out what the cache served and what it
                ;; stored; the prompt is all three.
                (let [in (or (:input_tokens u) 0)
                      hit (or (:cache_read_input_tokens u) 0)
                      wrote (or (:cache_creation_input_tokens u) 0)
                      out (or (:output_tokens u) 0)]
                  {:prompt-tokens (+ in hit wrote)
                   :completion-tokens out
                   :total-tokens (+ in hit wrote out)
                   :cache-hit-tokens hit
                   :cache-miss-tokens (+ in wrote)}))})))

;; --- the stream --------------------------------------------------------------

(defn accumulate
  "Fold one stream event into `acc`."
  [acc {:keys [type index content_block delta usage message error]}]
  (case type
    "message_start" (merge acc (select-keys message [:id :model]) {:usage (:usage message)})
    "content_block_start" (assoc-in acc [:blocks index] content_block)
    "content_block_delta"
    (case (:type delta)
      "text_delta" (update-in acc [:blocks index :text] str (:text delta))
      "thinking_delta" (update-in acc [:blocks index :thinking] str (:thinking delta))
      "signature_delta" (update-in acc [:blocks index :signature] str (:signature delta))
      "input_json_delta" (update-in acc [:blocks index :input_json] str (:partial_json delta))
      acc)
    "content_block_stop" (update acc :stopped (fnil conj #{}) index)
    "message_delta" (cond-> acc
                      (:stop_reason delta) (assoc :stop_reason (:stop_reason delta))
                      usage (update :usage merge usage))
    "message_stop" (assoc acc :finished true)
    "error" (assoc acc :error error)
    acc))

(defn completion
  "`acc` as the Messages reply a non-streamed call returns. A tool_use block
  the stream never closed is left out: its input may be cut off."
  [acc]
  (cond-> {:id (:id acc) :type "message" :role "assistant" :model (:model acc)
           :content (->> (sort-by key (:blocks acc))
                         (keep (fn [[i b]]
                                 (when (or (not= "tool_use" (:type b))
                                           (contains? (:stopped acc) i))
                                   b)))
                         vec)
           :stop_reason (or (:stop_reason acc) (when-not (:finished acc) "interrupted"))
           :usage (:usage acc)}
    (:error acc) (assoc :error (:error acc))))

(defn delta
  "What an event adds that a reader sees: {:text} or {:reasoning}, or nil."
  [{:keys [type delta]}]
  (when (= "content_block_delta" type)
    (case (:type delta)
      "text_delta" (when (seq (:text delta)) {:text (:text delta)})
      "thinking_delta" (when (seq (:thinking delta)) {:reasoning (:thinking delta)})
      nil)))

(def codec
  {:accumulate accumulate :completion completion :delta delta
   :end? #(= "message_stop" (:type %))})

;; --- the adapter -------------------------------------------------------------

(defrecord DeepSeekMessagesAdapter []
  adapter/Adapter
  (id [_] :deepseek)
  (display-name [_] "DeepSeek")
  (chat-url [_ config] (str (messages-root (:base-url config)) "/messages"))
  ;; `/models` at the base URL's origin, as the official listing asks it:
  ;; /anthropic/v1/models is a 404.
  (models-url [_ config]
    (when-let [[_ origin] (re-find #"^([a-z]+://[^/]+)" (str (:base-url config)))]
      (str origin "/models")))
  (auth-headers [_ config]
    (cond-> {"anthropic-version" anthropic-version}
      (:api-key config) (assoc "x-api-key" (:api-key config))))
  (chat-body [_ config request] (body config request))
  (prefill-support? [_ _] false)
  (parse-chat [_ b] (parse-message b))
  (parse-models [_ b] (mapv :id (:data b)))
  (error-message [_ b]
    (when-let [e (:error b)]
      (when (map? e)
        (str (or (:message e) "unknown error")
             (when-let [t (:type e)] (str " (" t ")"))))))
  (usage-cap? [_ status b] (adapter/usage-cap? openai/deepseek status b))

  adapter/StreamWire
  (stream-body [_ b] (assoc b :stream true))
  (stream-codec [_] codec))

(def deepseek (->DeepSeekMessagesAdapter))
