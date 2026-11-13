;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.deepseek-messages-test
  "DeepSeek over its Anthropic-compatible Messages API, shaped as DeepSeek's
  own harness sends it (deepseek-harness llm-deepseek serialize.ts and
  translate.ts)."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.config :as config]
            [samizdat.llm.adapter :as adapter]
            [samizdat.llm.adapter.deepseek-messages :as dsm]
            [samizdat.llm.client :as client]
            [samizdat.llm.fence :as fence]
            [samizdat.llm.message :as message]
            [samizdat.llm.registry :as registry]))

(def ^:private preset (:deepseek config/providers-for-test))
(def ^:private a dsm/deepseek)

(defn- body [config request]
  (adapter/chat-body a (merge preset config) request))

(def ^:private calc
  {:name "calc" :description "Evaluate arithmetic."
   :parameters {:type "object" :properties {:expr {}}}})

(deftest deepseek-is-the-messages-endpoint-with-the-official-defaults
  (is (= a (registry/adapter-for :deepseek)))
  (is (= "https://api.deepseek.com/anthropic/v1/messages" (adapter/chat-url a preset)))
  (is (= "https://api.deepseek.com/models" (adapter/models-url a preset)))
  (is (= {"anthropic-version" "2023-06-01" "x-api-key" "k"}
         (adapter/auth-headers a (assoc preset :api-key "k"))))
  (is (= "high" (:reasoning-effort preset)))
  (is (= 256000 (:max-tokens preset)))
  (is (nil? (:temperature preset)))
  (testing "it continues no prefill and takes no forced tool_choice in thinking mode"
    (is (not (config/supports? preset :prefill)))
    (is (not (config/supports? preset :native-tool-choice))))
  (is (config/supports? preset :replay-reasoning))
  (testing "the chat endpoint is still there under its own name"
    (is (str/ends-with? (:base-url (:deepseek-chat config/providers-for-test)) "/beta"))
    (is (config/supports? (:deepseek-chat config/providers-for-test) :prefill))))

(deftest a-request-is-the-official-body
  (let [b (body {} {:messages [{:role "system" :content "Be terse."}
                               {:role "user" :content "hi"}]
                    :max-tokens 1000})]
    (is (= {:model "deepseek-flash" :max_tokens 1000
            :thinking {:type "enabled"} :output_config {:effort "high"}
            :system "Be terse."
            :messages [{:role "user" :content [{:type "text" :text "hi"}]}]}
           b)
        "no temperature, no tool_choice, no stream until asked")))

(deftest effort-takes-the-endpoint-s-levels
  (is (= {:effort "max"} (:output_config (body {} {:messages [] :reasoning-effort "max"}))))
  (is (= {:effort "low"} (:output_config (body {} {:messages [] :reasoning-effort "minimal"}))))
  (is (= {:effort "high"} (:output_config (body {} {:messages [] :reasoning-effort "medium"}))))
  (testing "off is thinking disabled and no effort"
    (let [b (body {} {:messages [] :reasoning-effort "none"})]
      (is (= {:type "disabled"} (:thinking b)))
      (is (not (contains? b :output_config))))))

(deftest a-tool-turn-is-thinking-text-and-tool-use-then-a-tool-result
  (let [b (body {} {:tools [calc]
                    :messages (message/prepare
                               [{:role "system" :content "sys"}
                                {:role "user" :content "17*23?"}
                                {:role "assistant"
                                 :content "<think>Use calc.</think>Calculating.\n\n```tool-call\n{\"name\": \"calc\", \"args\": {\"expr\": \"17*23\"}}\n```"}
                                {:role "user" :content (message/frame-result "calc" "391")}]
                               {:replay-reasoning? true})})
        [u1 asst u2] (:messages b)]
    (is (= [{:name "calc" :description "Evaluate arithmetic."
             :input_schema {:type "object" :properties {:expr {}}}}]
           (:tools b)))
    (is (= "user" (:role u1)))
    (is (= ["thinking" "text" "tool_use"] (map :type (:content asst))))
    (is (= "Use calc." (:thinking (first (:content asst)))) "the reasoning goes back")
    (is (= {"expr" "17*23"} (:input (last (:content asst)))))
    (let [result (first (:content u2))]
      (is (= "tool_result" (:type result)))
      (is (= (:id (last (:content asst))) (:tool_use_id result)))
      (is (= [{:type "text" :text "391"}] (:content result))))))

(deftest every-reply-carries-a-thinking-block-in-thinking-mode
  ;; Measured: the endpoint refuses a tool_use turn whose thinking is not
  ;; passed back, and takes an empty one.
  (let [b (body {} {:messages [{:role "user" :content "a"}
                               {:role "assistant" :content "b"}
                               {:role "user" :content "c"}]})]
    (is (= [{:type "thinking" :thinking ""} {:type "text" :text "b"}]
           (:content (second (:messages b))))))
  (testing "with thinking off, none"
    (let [b (body {} {:reasoning-effort "none"
                      :messages [{:role "user" :content "a"} {:role "assistant" :content "b"}]})]
      (is (= [{:type "text" :text "b"}] (:content (second (:messages b))))))))

(deftest a-later-system-message-follows-the-user-turn
  (let [b (body {} {:messages [{:role "system" :content "first"}
                               {:role "user" :content "u1"}
                               {:role "assistant" :content "a1"}
                               {:role "user" :content "u2"}
                               {:role "system" :content "update"}]})]
    (is (= "first" (:system b)))
    (is (= ["user" "assistant" "user" "system"] (map :role (:messages b))))
    (is (= [{:type "text" :text "update"}] (:content (last (:messages b)))))))

(deftest consecutive-same-role-messages-merge
  (let [b (body {} {:messages [{:role "user" :content "a"} {:role "user" :content "b"}]})]
    (is (= [{:role "user" :content [{:type "text" :text "a"} {:type "text" :text "b"}]}]
           (:messages b)))))

;; --- the reply ---------------------------------------------------------------

(def ^:private reply
  {:id "msg_1" :type "message" :role "assistant" :model "deepseek-flash"
   :content [{:type "thinking" :thinking "Use calc." :signature "sig"}
             {:type "text" :text "Calculating."}
             {:type "tool_use" :id "call_1" :name "calc" :input {:expr "17*23"}}]
   :stop_reason "tool_use"
   :usage {:input_tokens 100 :cache_read_input_tokens 900 :cache_creation_input_tokens 0
           :output_tokens 40}})

(deftest a-reply-folds-into-text-and-fences
  (let [r (adapter/parse-chat a reply)
        call (fence/parse-tool-call (:content r))]
    (is (str/starts-with? (:content r) "Calculating."))
    (is (= "calc" (:name call)))
    (is (= {:expr "17*23"} (:args call)))
    (is (= "Use calc." (:reasoning r)))
    (is (= "tool_calls" (:finish-reason r)))
    (is (= {:prompt-tokens 1000 :completion-tokens 40 :total-tokens 1040
            :cache-hit-tokens 900 :cache-miss-tokens 100}
           (:usage r))
        "input_tokens leaves out the cached part; the prompt is the whole"))
  (is (= "length" (:finish-reason (adapter/parse-chat a {:content [] :stop_reason "max_tokens"}))))
  (is (nil? (adapter/parse-chat a {:choices []}))))

(deftest an-error-body-names-itself
  (is (= "Thinking mode does not support this tool_choice (invalid_request_error)"
         (adapter/error-message a {:type "error"
                                   :error {:type "invalid_request_error"
                                           :message "Thinking mode does not support this tool_choice"}}))))

;; --- the stream --------------------------------------------------------------

(def ^:private events
  [{:type "message_start" :message {:id "msg_1" :model "deepseek-flash" :usage {:input_tokens 100}}}
   {:type "content_block_start" :index 0 :content_block {:type "thinking" :thinking ""}}
   {:type "content_block_delta" :index 0 :delta {:type "thinking_delta" :thinking "Use "}}
   {:type "content_block_delta" :index 0 :delta {:type "thinking_delta" :thinking "calc."}}
   {:type "content_block_delta" :index 0 :delta {:type "signature_delta" :signature "sig"}}
   {:type "content_block_stop" :index 0}
   {:type "content_block_start" :index 1 :content_block {:type "text" :text ""}}
   {:type "content_block_delta" :index 1 :delta {:type "text_delta" :text "Calc"}}
   {:type "content_block_stop" :index 1}
   {:type "content_block_start" :index 2 :content_block {:type "tool_use" :id "call_1" :name "calc" :input {}}}
   {:type "content_block_delta" :index 2 :delta {:type "input_json_delta" :partial_json "{\"expr\":"}}])

(def ^:private finished
  (conj events
        {:type "content_block_delta" :index 2 :delta {:type "input_json_delta" :partial_json "\"17*23\"}"}}
        {:type "content_block_stop" :index 2}
        {:type "message_delta" :delta {:stop_reason "tool_use"} :usage {:output_tokens 40}}
        {:type "message_stop"}))

(deftest a-stream-folds-into-the-reply-it-describes
  (let [r (adapter/parse-chat a (dsm/completion (reduce dsm/accumulate nil finished)))]
    (is (str/starts-with? (:content r) "Calc"))
    (is (= {:expr "17*23"} (:args (fence/parse-tool-call (:content r)))))
    (is (= "Use calc." (:reasoning r)))
    (is (= "tool_calls" (:finish-reason r)))
    (is (= 40 (get-in r [:usage :completion-tokens]))))
  (is (= [{:reasoning "Use "} {:reasoning "calc."} {:text "Calc"}] (keep dsm/delta finished)))
  (is ((:end? dsm/codec) (last finished)))
  (testing "cut short, the unclosed call is dropped and the rest kept"
    (let [r (adapter/parse-chat a (dsm/completion (reduce dsm/accumulate nil events)))]
      (is (= "Calc" (:content r))))))

(deftest a-call-through-the-client-streams-the-messages-way
  (let [sent (atom nil) url (atom nil) codec (atom nil) headers (atom nil)]
    (with-redefs [samizdat.llm.stream/post (fn [u {:keys [body] :as opts} _]
                                             (reset! url u) (reset! codec (:codec opts))
                                             (reset! headers (:headers opts))
                                             (reset! sent (json/read-str body :key-fn keyword))
                                             {:status 200 :headers {} :body (json/write-str reply)})
                  jolt.http-client/post (fn [_ _] (throw (ex-info "not streamed" {})))]
      (let [r (client/chat a (assoc preset :api-key "k")
                           [{:role "user" :content "x"}]
                           {:max-tokens 64 :on-delta identity})]
        (is (= "https://api.deepseek.com/anthropic/v1/messages" @url))
        (is (true? (:stream @sent)))
        (is (not (contains? @sent :stream_options)))
        (is (= "k" (get @headers "x-api-key")))
        (is (= dsm/codec @codec))
        (is (= "calc" (:name (fence/parse-tool-call (:content r)))))))))
