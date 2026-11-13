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

(ns samizdat.llm.adapter.responses
  "OpenAI's Responses API (`POST /responses`), the wire GPT-5 and later
  reasoning models are driven through. Pi (packages/ai openai-responses) and
  opencode (packages/ai protocols/open-responses) both talk to OpenAI this
  way rather than through chat completions, and the request and reply shapes
  here follow theirs:

  - The conversation is `input`, a flat list of typed ITEMS, not nested
    messages: a system message is a `developer`-role message (the role
    reasoning models take instructions in), user text is an `input_text`
    part, a past reply is an assistant `message` item of `output_text`, and a
    tool call and its result are SEPARATE top-level items —
    `function_call` {call_id name arguments} and `function_call_output`
    {call_id output} — paired by call_id rather than nested in the
    assistant turn.
  - A tool is flat: {type function name description parameters strict},
    with no `function` wrapper, and so is a forced tool_choice
    {type function name}. `strict` is false, as both send it: the specs
    derived from the prompt (samizdat.llm.toolspec) require nothing, and
    strict mode would make every property required.
  - Reasoning is {reasoning {effort summary}}; `max_output_tokens`, not
    max_tokens; `text {verbosity}`; `store false`, so nothing is kept
    server-side and every request carries the whole history.
  - The reply is `output`, the same typed items. Text, refusals and calls are
    folded into one string with the calls as fences (samizdat.llm.adapter.
    openai/call->fence), and the reasoning summary is the reasoning.
  - The stream is typed events, not deltas of a choices array, and it ends on
    `response.completed` (or incomplete/failed), not `data: [DONE]` —
    `codec` folds it.

  Reasoning is not replayed: with store false that takes the encrypted
  reasoning item kept verbatim, which the journal does not store. Each turn
  reasons afresh from the visible history, as on a provider without
  :replay-reasoning."
  (:require ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.openai :as openai]
            [samizdat.llm.native-history :as native-history]))

;; --- the request -------------------------------------------------------------

(def ^:private min-output-tokens
  "The smallest max_output_tokens the endpoint accepts; a smaller one is a
  400 (pi issue 6265). A wire limit, not a budget."
  16)

(def ^:private cache-key-max
  "prompt_cache_key's length limit on the wire."
  64)

(defn- output-text [text]
  {:type "message" :role "assistant" :status "completed"
   :content [{:type "output_text" :text text :annotations []}]})

(defn input-items
  "Chat-shaped `messages` — {:role :content}, and after native-history's
  projection assistant :tool_calls and role \"tool\" results — as Responses
  input items. Empty assistant text is left out: an output_text item with
  nothing in it says nothing and is one more thing to be refused."
  [messages]
  (into []
        (mapcat
         (fn [{:keys [role content tool_calls tool_call_id]}]
           (let [role (str role) text (str content)]
             (case role
               "system" [{:role "developer" :content text}]
               "user" [{:role "user" :content [{:type "input_text" :text text}]}]
               "tool" [{:type "function_call_output" :call_id (str tool_call_id) :output text}]
               "assistant"
               (cond-> (if (str/blank? text) [] [(output-text text)])
                 (seq tool_calls)
                 (into (map (fn [tc]
                              {:type "function_call"
                               :call_id (str (:id tc))
                               :name (get-in tc [:function :name])
                               :arguments (str (get-in tc [:function :arguments]))}))
                       tool_calls))
               ;; Any other role goes as a message of that role.
               [{:role role :content text}]))))
        messages))

(defn tool->wire
  "A tool spec {:name :description :parameters} as a Responses function tool."
  [{:keys [name description parameters]}]
  (cond-> {:type "function"
           :name name
           :parameters (or parameters {:type "object" :properties {}})
           :strict false}
    description (assoc :description description)))

(defn- effort-wire
  "`effort` as the endpoint is to receive it: through the provider's
  :reasoning-effort-map when it has one, since models differ in the levels
  they take (pi's catalog: gpt-6.1-sol refuses `none`, others refuse
  `minimal`), and as given otherwise. A summary is asked for whenever the
  model reasons, so the reasoning shows."
  [config effort]
  (let [e (get (:reasoning-effort-map config) effort effort)]
    (when e
      (cond-> {:effort e}
        (not= "none" e) (assoc :summary "auto")))))

(defn- body
  [this config {:keys [messages max-tokens temperature top-p force-tool cache-key
                       reasoning-effort tools]}]
  (let [native (when (and (seq tools) (config/supports? config :native-tools))
                 (mapv tool->wire tools))
        messages (if (and native (config/supports? config :native-history))
                   (native-history/project messages)
                   messages)
        forced (when force-tool (tool->wire force-tool))
        effort (effort-wire config (or reasoning-effort (:reasoning-effort config)))]
    (cond-> {:model (:model config)
             :input (input-items messages)
             :store false}
      max-tokens (assoc :max_output_tokens (max min-output-tokens (long max-tokens)))
      (some? temperature) (assoc :temperature temperature)
      (some? top-p) (assoc :top_p top-p)
      effort (assoc :reasoning effort)
      (:text-verbosity config) (assoc :text {:verbosity (:text-verbosity config)})
      native (assoc :tools native
                    ;; One call a turn is the harness's protocol, and the
                    ;; history projection pairs one call with one result.
                    :parallel_tool_calls false)
      forced (assoc :tools (cond
                             (nil? native) [forced]
                             (some #(= (:name forced) (:name %)) native) native
                             :else (conj native forced))
                    :tool_choice {:type "function" :name (:name forced)})
      (some? cache-key)
      (assoc :prompt_cache_key (let [k (str cache-key)]
                                 (subs k 0 (min cache-key-max (count k))))))))

;; --- the reply ---------------------------------------------------------------

(defn- message-text [item]
  (->> (:content item)
       (keep #(or (:text %) (:refusal %)))
       (apply str)))

(defn- reasoning-text [item]
  (let [summary (keep :text (:summary item))
        content (keep :text (:content item))]
    (not-empty (str/join "\n\n" (if (seq summary) summary content)))))

(defn- finish-reason [{:keys [status incomplete_details output]}]
  (let [reason (:reason incomplete_details)]
    (cond
      (and (= "incomplete" status) (= "max_output_tokens" reason)) "length"
      (and (= "incomplete" status) (= "content_filter" reason)) "content_filter"
      (= "incomplete" status) (str "incomplete" (when reason (str ":" reason)))
      (some #(= "function_call" (:type %)) output) "tool_calls"
      :else "stop")))

(defn parse-response
  "A Response object as {:content :reasoning :finish-reason :model :usage}, or
  nil for a body that is not one."
  [body]
  (when (contains? body :output)
    (let [output (:output body)
          said (->> output
                    (filter #(= "message" (:type %)))
                    (map message-text)
                    (remove str/blank?))
          calls (->> output
                     (filter #(= "function_call" (:type %)))
                     (map #(openai/call->fence (:name %) (:arguments %))))
          reasoning (->> output
                         (filter #(= "reasoning" (:type %)))
                         (keep reasoning-text))]
      {:content (str/join "\n\n" (concat (map str/trim said) calls))
       :reasoning (not-empty (str/join "\n\n" reasoning))
       :finish-reason (finish-reason body)
       :model (some-> (:model body) str not-empty)
       :usage (when-let [u (:usage body)]
                ;; input_tokens counts the cached ones, as prompt_tokens does.
                (cond-> {:prompt-tokens (or (:input_tokens u) 0)
                         :completion-tokens (or (:output_tokens u) 0)
                         :total-tokens (or (:total_tokens u) 0)}
                  (get-in u [:input_tokens_details :cached_tokens])
                  (assoc :cache-hit-tokens (get-in u [:input_tokens_details :cached_tokens]))))})))

;; --- the stream --------------------------------------------------------------

(def ^:private terminal-events
  #{"response.completed" "response.incomplete" "response.failed"})

(defn accumulate
  "Fold one stream event into `acc`. The terminal event carries the whole
  Response, which is the reply; the items and deltas before it are kept so a
  stream cut short still has what it said."
  [acc {:keys [type output_index item delta response] :as ev}]
  (case type
    ("response.created" "response.in_progress")
    (merge acc (select-keys response [:id :model]))

    "response.output_item.added" (assoc-in acc [:items output_index] item)
    "response.output_item.done" (-> acc
                                    (assoc-in [:items output_index] item)
                                    (update :done (fnil conj #{}) output_index))

    ("response.output_text.delta" "response.refusal.delta")
    (update-in acc [:text output_index] str delta)

    ("response.reasoning_summary_text.delta" "response.reasoning_text.delta")
    (update-in acc [:reasoning output_index] str delta)

    ("response.completed" "response.incomplete" "response.failed")
    (assoc acc :response response)

    "error" (assoc acc :error (select-keys ev [:message :code :param]))

    acc))

(defn- items-so-far
  "The output items `acc` holds, in order: a finished item as it was sent; an
  unfinished message or reasoning from its deltas; an unfinished call
  dropped, since its arguments may be cut off."
  [{:keys [items done text reasoning]}]
  (->> (sort (distinct (concat (keys items) (keys text) (keys reasoning))))
       (keep (fn [i]
               (let [item (get items i)]
                 (cond
                   (contains? done i) item
                   (contains? text i) (output-text (get text i))
                   (contains? reasoning i) {:type "reasoning"
                                            :summary [{:type "summary_text"
                                                       :text (get reasoning i)}]}
                   :else nil))))
       vec))

(defn completion
  "`acc` as the Response a non-streamed call returns: the terminal event's,
  with its output filled from the items when it came without one; or, for a
  stream that never finished, an incomplete Response of what it had."
  [acc]
  (let [r (:response acc)]
    (cond-> (if r
              (cond-> r (empty? (:output r)) (assoc :output (items-so-far acc)))
              {:id (:id acc) :object "response" :model (:model acc)
               :status "incomplete" :incomplete_details {:reason "interrupted"}
               :output (items-so-far acc)})
      (:error acc) (assoc :error (:error acc)))))

(defn delta
  "What an event adds that a reader sees: {:text} or {:reasoning}, or nil."
  [{:keys [type delta]}]
  (when (not-empty (str delta))
    (case type
      ("response.output_text.delta" "response.refusal.delta") {:text delta}
      ("response.reasoning_summary_text.delta" "response.reasoning_text.delta") {:reasoning delta}
      nil)))

(def codec
  {:accumulate accumulate :completion completion :delta delta
   :end? #(contains? terminal-events (:type %))})

;; --- the adapter -------------------------------------------------------------

(defrecord ResponsesAdapter [provider-id label]
  adapter/Adapter
  (id [_] provider-id)
  (display-name [_] label)
  (chat-url [_ config] (str (:base-url config) "/responses"))
  (models-url [_ config] (str (:base-url config) "/models"))
  (auth-headers [_ config]
    (if-let [k (:api-key config)] {"Authorization" (str "Bearer " k)} {}))
  (chat-body [this config request] (body this config request))
  ;; The endpoint has no assistant prefill.
  (prefill-support? [_ _] false)
  (parse-chat [_ b] (parse-response b))
  (parse-models [_ b] (mapv :id (:data b)))
  (error-message [_ b]
    (when-let [e (:error b)]
      (when (map? e)
        (str (or (:message e) "unknown error")
             (when-let [c (:code e)] (str " (code " c ")"))))))
  (usage-cap? [_ status b] (adapter/usage-cap? openai/openai status b))

  adapter/StreamWire
  (stream-body [_ b] (assoc b :stream true))
  (stream-codec [_] codec))

(def openai-responses (->ResponsesAdapter :openai-responses "OpenAI"))
