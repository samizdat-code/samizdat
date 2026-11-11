;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.llm.native-history
  "A conversation as the provider's own tool messages, for the wire only.

  The branch keeps every call as a fence in the assistant's text and every result
  as a framed part of the next user message, and everything that reads a branch
  relies on that. An endpoint given native tools is sent what ZCode and DeepSeek
  Harness send: `tool_calls` on the assistant turn, a `tool` message with the
  result, and whatever the harness appended to that result as a user message.

  Only what pairs cleanly is converted. A call with no framed result of the same
  tool after it, a reply that did not parse, and a harness message all stay text."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.llm.fence :as fence]
            [samizdat.llm.message :as message]))

(defn- call-in
  "The parsed call an assistant turn's text makes, with the words before it, or nil."
  [content]
  (let [text (str content)
        at (str/index-of text "```tool-call")
        {:keys [name args parse-error]} (when at (fence/parse-tool-call text))]
    (when (and at (string? name) (not parse-error) (or (map? args) (nil? args)))
      {:name name :args (or args {}) :said (str/trim (subs text 0 at))})))

(defn- result-in
  "The tool, result and anything after it in a framed user message, or nil."
  [{:keys [role content]}]
  (when (= "user" (str role))
    (let [text (str content)
          [tool inner] (message/framed-result text)]
      (when tool
        {:tool tool :inner inner
         :rest (str/trim (subs text (count (message/frame-result tool inner))))}))))

(defn project
  "`messages`, prepared for the wire, with their tool calls and results as the
  provider's own messages. Ids are by position, so the same history gives the same
  bytes, and the prompt cache holds."
  [messages]
  (loop [i 0 out []]
    (if-let [m (get (vec messages) i)]
      (let [call (when (= "assistant" (str (:role m))) (call-in (:content m)))
            result (when call (result-in (get (vec messages) (inc i))))]
        (if (and result (= (:name call) (:tool result)))
          (let [id (str "call_" i)]
            (recur (+ i 2)
                   (cond-> (conj out
                                 ;; DeepSeek refuses a thinking model's call without the field.
                                 (assoc (merge {:reasoning_content ""} (select-keys m [:reasoning_content]))
                                        :role "assistant"
                                        :content (:said call)
                                        :tool_calls [{:id id :type "function"
                                                      :function {:name (:name call)
                                                                 :arguments (json/write-str (:args call))}}])
                                 {:role "tool" :tool_call_id id :content (:inner result)})
                     (seq (:rest result)) (conj {:role "user" :content (:rest result)}))))
          (recur (inc i) (conj out m))))
      out)))
