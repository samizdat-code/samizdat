;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.responses-adapter-test
  "GPT models over OpenAI's Responses API, shaped as pi and opencode send
  them: a flat list of typed input items, flat function tools, calls and
  results as separate items paired by call_id, and a stream of typed events
  that ends on response.completed rather than [DONE]."
  (:require [jolt.time]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ring-chez.adapter :as server]
            [ring-chez.sse :as rsse]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.openai :as openai]
            [samizdat.llm.adapter.responses :as responses]
            [samizdat.llm.client :as client]
            [samizdat.llm.fence :as fence]
            [samizdat.llm.message :as message]
            [samizdat.llm.registry :as registry]
            [samizdat.llm.stream :as stream]))

(def ^:private preset (:openai-responses config/providers-for-test))

(def ^:private a responses/openai-responses)

(defn- body [config request]
  (adapter/chat-body a (merge preset {:model "gpt-x"} config) request))

(def ^:private edit-spec
  {:name "edit_file" :description "Edit a file."
   :parameters {:type "object" :properties {:path {} :old_text {} :new_text {}}}})

(deftest the-provider-is-registered-with-its-defaults
  (is (= a (registry/adapter-for :openai-responses)))
  (is (nil? (:temperature preset)) "reasoning models refuse a temperature")
  (is (config/supports? preset :native-tools))
  (is (config/supports? preset :stream))
  (is (not (config/supports? preset :prefill)))
  (is (not (config/supports? preset :replay-reasoning))
      "reasoning replay needs the encrypted item, which is not kept")
  (is (= "https://api.openai.com/v1/responses" (adapter/chat-url a preset))))

(deftest a-declared-provider-carries-its-preset-knobs
  (let [llm (fn [t] (:llm (config/load-config {:providers {:p {:type t :api-key "x"}}
                                               :roles {:default :p}})))]
    (is (= "low" (:text-verbosity (llm :openai-responses))))
    (is (= "medium" (:reasoning-effort (llm :openai-responses))))
    (testing "and DeepSeek's declared effort is the one sent"
      (is (= "high" (:reasoning-effort (llm :deepseek)))))))

(deftest the-conversation-is-a-flat-list-of-input-items
  (let [b (body {} {:messages [{:role "system" :content "Be terse."}
                               {:role "user" :content "hi"}
                               {:role "assistant" :content "hello"}
                               {:role "user" :content "again"}]})]
    (is (= "gpt-x" (:model b)))
    (is (false? (:store b)) "nothing kept server-side")
    (is (not (contains? b :messages)))
    (is (= [{:role "developer" :content "Be terse."}
            {:role "user" :content [{:type "input_text" :text "hi"}]}
            {:type "message" :role "assistant" :status "completed"
             :content [{:type "output_text" :text "hello" :annotations []}]}
            {:role "user" :content [{:type "input_text" :text "again"}]}]
           (:input b)))
    (testing "an empty past reply is left out"
      (is (= 1 (count (:input (body {} {:messages [{:role "assistant" :content ""}
                                                   {:role "user" :content "x"}]}))))))))

(deftest generation-knobs-take-the-responses-names
  (let [b (body {} {:messages [{:role "user" :content "x"}] :max-tokens 4096})]
    (is (= 4096 (:max_output_tokens b)))
    (is (not (contains? b :max_tokens)))
    (is (not (contains? b :temperature)) "none sent when the provider says nil")
    (is (= {:effort "medium" :summary "auto"} (:reasoning b)))
    (is (= {:verbosity "low"} (:text b))))
  (testing "the endpoint refuses fewer than 16 output tokens"
    (is (= 16 (:max_output_tokens (body {} {:messages [] :max-tokens 4}))))))

(deftest effort-goes-through-the-provider-s-map
  (testing "the call's effort beats the run's"
    (is (= "high" (get-in (body {} {:messages [] :reasoning-effort "high"}) [:reasoning :effort]))))
  (testing "none asks for no summary"
    (is (= {:effort "none"} (:reasoning (body {} {:messages [] :reasoning-effort "none"})))))
  (testing "a model that refuses a level gets the one mapped to it"
    (is (= {:effort "low" :summary "auto"}
           (:reasoning (body {:reasoning-effort-map {"none" "low"}}
                             {:messages [] :reasoning-effort "none"}))))))

(deftest tools-are-flat-and-calls-are-their-own-items
  (let [b (body {} {:tools [edit-spec]
                    :messages [{:role "system" :content "sys"}
                               {:role "user" :content "fix it"}
                               {:role "assistant"
                                :content "Fixing.\n\n```tool-call\n{\"name\": \"edit_file\", \"args\": {\"path\": \"a.clj\"}}\n```"}
                               {:role "user" :content (message/frame-result "edit_file" "ok")}]})]
    (is (= [{:type "function" :name "edit_file" :description "Edit a file."
             :parameters {:type "object" :properties {:path {} :old_text {} :new_text {}}}
             :strict false}]
           (:tools b))
        "no `function` wrapper, and not strict")
    (is (false? (:parallel_tool_calls b)) "one call a turn")
    (let [items (:input b)
          call (first (filter #(= "function_call" (:type %)) items))
          result (first (filter #(= "function_call_output" (:type %)) items))]
      (is (some? call) (pr-str items))
      (is (= "edit_file" (:name call)))
      (is (= {"path" "a.clj"} (json/read-str (:arguments call))) "arguments as a JSON string")
      (is (= (:call_id call) (:call_id result)) "paired by call_id")
      (is (string? (:output result)))
      (is (some #(= [{:type "output_text" :text "Fixing." :annotations []}] (:content %)) items)
          "the words beside the call stay a message"))))

(deftest a-forced-tool-names-itself-flat
  (let [done {:name "done" :parameters {:type "object" :properties {:answer {:type "string"}}}}
        b (body {} {:messages [{:role "user" :content "x"}] :force-tool done})]
    (is (= {:type "function" :name "done"} (:tool_choice b)))
    (is (= ["done"] (map :name (:tools b)))))
  (testing "beside the native surface the array stays whole"
    (let [b (body {} {:messages [] :tools [edit-spec]
                      :force-tool {:name "done" :parameters {:type "object" :properties {}}}})]
      (is (= ["edit_file" "done"] (map :name (:tools b)))))))

(deftest the-cache-key-rides-along-within-its-limit
  (is (= "b1" (:prompt_cache_key (body {} {:messages [] :cache-key "b1"}))))
  (is (= 64 (count (:prompt_cache_key (body {} {:messages [] :cache-key (apply str (repeat 80 "k"))}))))))

;; --- the reply ---------------------------------------------------------------

(def ^:private reply
  {:id "resp_1" :object "response" :model "gpt-x-2026" :status "completed"
   :output [{:type "reasoning" :id "rs_1"
             :summary [{:type "summary_text" :text "Look at the file."}]}
            {:type "message" :id "msg_1" :role "assistant"
             :content [{:type "output_text" :text "Reading it." :annotations []}]}
            {:type "function_call" :id "fc_1" :call_id "call_1" :name "read_file"
             :arguments "{\"path\":\"a.clj\"}"}]
   :usage {:input_tokens 1200 :output_tokens 80 :total_tokens 1280
           :input_tokens_details {:cached_tokens 1000}}})

(deftest a-reply-folds-its-items-into-text-and-fences
  (let [r (adapter/parse-chat a reply)
        call (fence/parse-tool-call (:content r))]
    (is (str/starts-with? (:content r) "Reading it."))
    (is (= "read_file" (:name call)))
    (is (= {:path "a.clj"} (:args call)))
    (is (= "Look at the file." (:reasoning r)))
    (is (= "tool_calls" (:finish-reason r)))
    (is (= "gpt-x-2026" (:model r)))
    (is (= {:prompt-tokens 1200 :completion-tokens 80 :total-tokens 1280 :cache-hit-tokens 1000}
           (:usage r)))))

(deftest how-a-reply-ended-is-its-finish-reason
  (is (= "length" (:finish-reason (adapter/parse-chat a {:status "incomplete" :output []
                                                         :incomplete_details {:reason "max_output_tokens"}}))))
  (is (= "stop" (:finish-reason (adapter/parse-chat a {:status "completed"
                                                       :output [{:type "message" :content [{:type "output_text" :text "hi"}]}]}))))
  (is (nil? (adapter/parse-chat a {:choices []})) "not a Response"))

(deftest an-error-body-names-itself
  (is (= "bad (code invalid_value)"
         (adapter/error-message a {:error {:message "bad" :code "invalid_value"}})))
  (is (nil? (adapter/error-message a {:error nil :output []})) "a Response's null error is no error"))

(deftest arguments-encoded-twice-are-the-object
  ;; A stringified root object, as opencode's tool-input repair unwraps.
  (let [twice (json/write-str (json/write-str {:path "a.clj"}))
        call (fence/parse-tool-call (openai/call->fence "read_file" twice))]
    (is (= {:path "a.clj"} (:args call))))
  (testing "a plain string argument is left alone"
    (is (str/includes? (openai/call->fence "x" "\"hello\"") "\"args\":\"hello\""))))

;; --- the stream --------------------------------------------------------------

(def ^:private events
  [{:type "response.created" :response {:id "resp_1" :model "gpt-x-2026" :status "in_progress" :output []}}
   {:type "response.output_item.added" :output_index 0 :item {:type "reasoning" :id "rs_1" :summary []}}
   {:type "response.reasoning_summary_text.delta" :output_index 0 :delta "Look"}
   {:type "response.output_item.done" :output_index 0
    :item {:type "reasoning" :id "rs_1" :summary [{:type "summary_text" :text "Look"}]}}
   {:type "response.output_item.added" :output_index 1 :item {:type "message" :id "msg_1" :content []}}
   {:type "response.output_text.delta" :output_index 1 :delta "Hel"}
   {:type "response.output_text.delta" :output_index 1 :delta "lo"}
   {:type "response.output_item.done" :output_index 1
    :item {:type "message" :id "msg_1" :content [{:type "output_text" :text "Hello"}]}}
   {:type "response.output_item.added" :output_index 2
    :item {:type "function_call" :id "fc_1" :call_id "call_1" :name "read_file" :arguments ""}}
   {:type "response.function_call_arguments.delta" :output_index 2 :delta "{\"path\":"}])

(def ^:private finished
  (conj events
        {:type "response.output_item.done" :output_index 2
         :item {:type "function_call" :id "fc_1" :call_id "call_1" :name "read_file"
                :arguments "{\"path\":\"a.clj\"}"}}
        {:type "response.completed"
         :response (assoc reply :output [])}))

(deftest a-stream-folds-into-the-response-it-describes
  (let [acc (reduce responses/accumulate nil finished)
        r (adapter/parse-chat a (responses/completion acc))]
    (is (str/starts-with? (:content r) "Hello"))
    (is (= "read_file" (:name (fence/parse-tool-call (:content r)))))
    (is (= "Look" (:reasoning r)))
    (is (= 1280 (get-in r [:usage :total-tokens]))))
  (testing "deltas are what a watcher sees"
    (is (= [{:reasoning "Look"} {:text "Hel"} {:text "lo"}]
           (keep responses/delta finished))))
  (testing "it ends on the terminal event"
    (is ((:end? responses/codec) (last finished)))
    (is (not ((:end? responses/codec) (first finished))))))

(deftest a-cut-stream-keeps-what-it-said-and-drops-the-half-call
  (let [r (adapter/parse-chat a (responses/completion (reduce responses/accumulate nil events)))]
    (is (= "Hello" (:content r)) "the unfinished call is not handed on")
    (is (= "Look" (:reasoning r)))))

(deftest an-error-event-is-the-reply
  (let [acc (reduce responses/accumulate nil [{:type "error" :code "server_error" :message "boom"}])]
    (is (= "boom (code server_error)" (adapter/error-message a (responses/completion acc))))))

(defn- free-port []
  (let [s (java.net.ServerSocket. 0)
        p (.getLocalPort s)]
    (.close s)
    p))

(deftest a-streamed-post-ends-on-the-terminal-event
  (let [seen (atom [])
        port (free-port)
        srv (server/run-server
             (fn [_]
               (let [ch (async/chan 64)]
                 (future
                   (doseq [e finished]
                     (rsse/send! ch {:data (json/write-str e)})
                     (Thread/sleep 5))
                   ;; No [DONE]: the Responses stream just ends.
                   (async/close! ch))
                 {:status 200 :headers {"Content-Type" "text/event-stream"} :body ch}))
             {:port port})]
    (try
      (let [r (stream/post (str "http://127.0.0.1:" port "/v1/responses")
                           {:headers {"Content-Type" "application/json"} :body "{}"
                            :socket-timeout 5000 :conn-timeout 2000
                            :codec (adapter/stream-codec a)}
                           #(swap! seen conj %))
            parsed (adapter/parse-chat a (json/read-str (:body r) :key-fn keyword))]
        (is (= 200 (:status r)))
        (is (str/starts-with? (:content parsed) "Hello"))
        (is (= [{:reasoning "Look"} {:text "Hel"} {:text "lo"}] @seen)))
      (finally (server/stop-server srv)))))

(deftest asking-for-a-stream-is-just-stream-true
  (is (= {:model "m" :stream true} (adapter/stream-body a {:model "m"})))
  (is (not (satisfies? adapter/StreamWire openai/openai))
      "chat completions keep the default stream wire"))

(deftest a-call-through-the-client-streams-the-responses-way
  (let [sent (atom nil)
        url (atom nil)
        codec (atom nil)]
    (with-redefs [samizdat.llm.stream/post (fn [u {:keys [body] :as opts} on-delta]
                                             (reset! url u)
                                             (reset! codec (:codec opts))
                                             (reset! sent (json/read-str body :key-fn keyword))
                                             (on-delta {:text "Hello"})
                                             {:status 200 :headers {}
                                              :body (json/write-str reply)})
                  jolt.http-client/post (fn [_ _] (throw (ex-info "not streamed" {})))]
      (let [r (client/chat a (merge preset {:model "gpt-x"})
                           [{:role "user" :content "x"}]
                           {:max-tokens 64 :on-delta identity})]
        (is (= "https://api.openai.com/v1/responses" @url))
        (is (true? (:stream @sent)))
        (is (not (contains? @sent :stream_options)) "a chat-completions knob the endpoint does not take")
        (is (= responses/codec @codec))
        (is (= "read_file" (:name (fence/parse-tool-call (:content r)))))))))
