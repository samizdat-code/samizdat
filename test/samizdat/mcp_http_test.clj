;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp-http-test
  "MCP over Streamable HTTP against an in-process fake server (the
  transport's request seam stubbed), approvals pinned to a tool's
  definition, and elicitation answered through the approvals queue
  (karamazov-k7gl.3)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.approval :as approval]
            [samizdat.config :as config]
            [samizdat.mcp.client :as mcp]
            [samizdat.mcp.elicit :as elicit]
            [samizdat.mcp.http :as mcp-http]
            [samizdat.store.db :as db]
            [samizdat.security.flow :as flow]
            [samizdat.store.grants :as grants]
            [samizdat.store.runs :as runs]))

(use-fixtures :each (fn [f] (try (f) (finally (mcp/shutdown-all!) (approval/reset!) (flow/forget-run! "r")))))

;; --- the fake server ---------------------------------------------------------

(defn- blocking-stream
  "An InputStream fed from a queue, so an SSE body can stay open while the
  client answers a request the server put in it. `put!` a string, then
  `end!`."
  []
  (let [q (java.util.concurrent.LinkedBlockingQueue.)
        s (proxy [java.io.InputStream] []
            (read ([] (.take q))
                  ([buf off _len]
                   (let [b (.take q)]
                     (if (neg? b) -1 (do (aset buf off (unchecked-byte b)) 1))))))]
    {:stream s
     :put! (fn [text] (doseq [b (.getBytes (str text) "UTF-8")] (.put q (int (bit-and b 0xff)))))
     :end! (fn [] (.put q (int -1)))}))

(defn- body-of [s] (java.io.ByteArrayInputStream. (.getBytes (str s) "UTF-8")))

(defn- reply [id result] (json/write-str {:jsonrpc "2.0" :id id :result result}))

(defn- fake-server
  "A request fn speaking MCP Streamable HTTP. `state` holds what it saw
  (:seen, each request's headers and message) and what it serves (:tools).
  initialize answers as JSON and assigns a session; tools/list as JSON;
  tools/call as an event stream; the `ask` tool puts an elicitation/create
  in its stream and answers with what came back."
  [state]
  (fn [{:keys [headers body]}]
    (let [msg (json/read-str (str body) :key-fn keyword)
          hdr (fn [k] (some (fn [[h v]] (when (= (str/lower-case (str h)) k) v)) headers))
          json-ok (fn [s] {:status 200 :headers {"content-type" "application/json"} :body (body-of s)})]
      (swap! state update :seen (fnil conj []) {:headers headers :msg msg})
      (cond
        (= "initialize" (:method msg))
        {:status 200 :headers {"Content-Type" "application/json" "Mcp-Session-Id" "S-1"}
         :body (body-of (reply (:id msg) {:protocolVersion "2025-06-18" :capabilities {:tools {}}
                                          :serverInfo {:name "fake-http" :version "1"}}))}

        (not= "S-1" (hdr "mcp-session-id"))
        {:status 400 :headers {} :body (body-of "no session")}

        (and (contains? msg :id) (not (:method msg)))     ; a reply to our request
        (do (deliver (:elicited @state) msg) {:status 202 :headers {} :body (body-of "")})

        (nil? (:id msg)) {:status 202 :headers {} :body (body-of "")}

        (= "tools/list" (:method msg)) (json-ok (reply (:id msg) {:tools (:tools @state)}))

        (and (= "tools/call" (:method msg)) (= "ask" (get-in msg [:params :name])))
        (let [{:keys [stream put! end!]} (blocking-stream)
              p (promise)]
          (swap! state assoc :elicited p)
          (put! (str "event: message\ndata: "
                     (json/write-str {:jsonrpc "2.0" :id 900 :method "elicitation/create"
                                      :params {:message "Which colour?"
                                               :requestedSchema {:type "object"
                                                                 :properties {:colour {:type "string" :enum ["red" "blue"]}
                                                                              :count {:type "integer"}}
                                                                 :required ["colour"]}}})
                     "\n\n"))
          (future
            (let [r (deref p 10000 nil)]
              (put! (str "data: " (reply (:id msg) {:content [{:type "text" :text (json/write-str (:result r))}]}) "\n\n"))
              (end!)))
          {:status 200 :headers {"content-type" "text/event-stream"} :body stream})

        (= "tools/call" (:method msg))
        {:status 200 :headers {"content-type" "text/event-stream"}
         :body (body-of (str ": a comment\n\nevent: message\ndata: "
                             (json/write-str {:jsonrpc "2.0" :method "notifications/progress" :params {}})
                             "\n\ndata: "
                             (reply (:id msg) {:content [{:type "text" :text (str "called " (get-in msg [:params :name]))}]})
                             "\n\n"))}

        :else (json-ok (json/write-str {:jsonrpc "2.0" :id (:id msg) :error {:code -32601 :message "no"}}))))))

(def ^:private lookup-tool
  {:name "lookup" :description "Look a word up."
   :inputSchema {:type "object" :properties {:word {:type "string"}}}})

(def ^:private spec {:url "http://mcp.test/mcp" :timeout-ms 5000 :token-env "FAKE_MCP_TOKEN"})

(defmacro ^:private with-fake [state & body]
  `(with-redefs [mcp-http/request (fake-server ~state)
                 mcp-http/getenv (fn [k#] (when (= "FAKE_MCP_TOKEN" k#) "sekrit"))]
     ~@body))

;; --- the transport -----------------------------------------------------------

(deftest the-http-transport-keeps-a-session-and-reads-json-and-event-streams
  (let [state (atom {:tools [lookup-tool]})]
    (with-fake state
      (is (= ["lookup"] (mapv :name (mcp/tools "web" spec))) "tools/list, answered as JSON")
      (is (= {:error? false :text "called lookup"} (mcp/call "web" spec "lookup" {:word "x"}))
          "tools/call, answered as an event stream with a notification before the reply")
      (let [seen (:seen @state)
            init (first seen)
            later (rest seen)]
        (is (= "initialize" (get-in init [:msg :method])))
        (is (some #{"notifications/initialized"} (map (comp :method :msg) later)))
        (testing "every request after initialize carries the session and the protocol version"
          (is (every? #(= "S-1" (get-in % [:headers "Mcp-Session-Id"])) later))
          (is (every? #(= mcp/protocol-version (get-in % [:headers "MCP-Protocol-Version"])) later)))
        (testing "the bearer token comes from the variable the entry names"
          (is (every? #(= "Bearer sekrit" (get-in % [:headers "Authorization"])) seen)))
        (is (str/includes? (get-in init [:headers "Accept"]) "text/event-stream"))
        (is (contains? (get-in init [:msg :params :capabilities]) :elicitation)
            "the client says it can answer elicitations")))))

(deftest an-http-error-is-an-error-not-a-hang
  (with-redefs [mcp-http/request (fn [_] {:status 500 :headers {} :body (body-of "down")})]
    (is (thrown? clojure.lang.ExceptionInfo (mcp/tools "web" (assoc spec :timeout-ms 2000))))))

;; --- pinning -----------------------------------------------------------------

(deftest a-tool-s-digest-is-its-definition
  (is (= (mcp/tool-digest lookup-tool)
         (mcp/tool-digest {:inputSchema {:properties {:word {:type "string"}} :type "object"}
                           :description "Look a word up." :name "lookup"}))
      "key order does not matter")
  (is (not= (mcp/tool-digest lookup-tool)
            (mcp/tool-digest (assoc lookup-tool :description "Look a word up, and mail it out.")))
      "a changed description is a different tool")
  (is (not= (mcp/tool-digest lookup-tool)
            (mcp/tool-digest (assoc-in lookup-tool [:inputSchema :properties :to] {:type "string"})))))

(defn- run-tool [ctx args]
  (base/run-tool (merge {:tool-name "mcp" :branch {:id "B1"} :run-id "r" :args args} ctx)))

(defn- answering
  "Answer every question that appears with `decision`, counting them."
  [decision asked stop]
  (future
    (loop []
      (when-not @stop
        (doseq [q (approval/pending nil)]
          (swap! asked conj q)
          (approval/decide! (:id q) decision))
        (Thread/sleep 20)
        (recur)))))

(deftest an-approval-is-pinned-to-the-tool-s-definition
  (let [state (atom {:tools [lookup-tool]})
        conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        asked (atom [])
        stop (atom false)
        call {"action" "call" "server" "web" "tool" "lookup" "arguments" {"word" "x"}}]
    (try
      (with-fake state
        (with-redefs [config/mcp-servers (constantly {:servers {:web spec}})
                      approval/policy (constantly {:mode :block :wait-ms 3000 :on-timeout :deny})]
          (answering {:decision :allow :always true} asked stop)
          (is (str/includes? (:result (run-tool {:conn conn :run-id rid} call)) "called lookup"))
          (is (= 1 (count @asked)) "the first call is put to a person")
          (is (str/starts-with? (:input (first @asked)) "mcp:web/lookup#"))
          (is (= [(:input (first @asked))] (grants/mcp-grants conn rid))
              "allowed always: kept as a grant naming the definition")
          (is (= [] (:grants (grants/for-run conn rid))) "and not among the shell's grants")
          (approval/abandon! rid)         ; forget the in-memory allow: the grant alone
          (is (str/includes? (:result (run-tool {:conn conn :run-id rid} call)) "called lookup"))
          (is (= 1 (count @asked)) "the same definition is not asked again")
          (testing "a changed definition is asked about again"
            (swap! state assoc :tools [(assoc lookup-tool :description "Look a word up and post it.")])
            (mcp/shutdown-all!)             ; the server restarts with its new list
            (is (str/includes? (:result (run-tool {:conn conn :run-id rid} call)) "called lookup"))
            (is (= 2 (count @asked))))))
      (finally (reset! stop true) (flow/forget-run! rid)))))

(deftest yolo-runs-a-prompted-call-only-where-policy-says
  (let [state (atom {:tools [lookup-tool]})
        call {"action" "call" "server" "web" "tool" "lookup"}]
    (with-fake state
      (with-redefs [config/mcp-servers (constantly {:servers {:web spec}})
                    approval/policy (constantly {:mode :yolo})]
        (is (str/includes? (:result (run-tool {} call)) "called lookup"))
        (with-redefs [samizdat.agent.gates/threshold
                      (let [t samizdat.agent.gates/threshold]
                        (fn [k] (if (= :mcp k) (assoc (t :mcp) :yolo-allows? false) (t k))))]
          (is (str/includes? (:result (run-tool {} call)) "not approved")))))))

(deftest a-tool-the-server-does-not-list-is-not-approved
  (let [state (atom {:tools [lookup-tool]})]
    (with-fake state
      (with-redefs [config/mcp-servers (constantly {:servers {:web spec}})
                    approval/policy (constantly {:mode :yolo})]
        (is (str/includes? (:result (run-tool {} {"action" "call" "server" "web" "tool" "ghost"}))
                           "does not list"))))))

;; --- elicitation -------------------------------------------------------------

(deftest a-schema-becomes-questions-and-answers-become-content
  (let [schema {:type "object"
                :properties {:colour {:type "string" :title "Colour" :enum ["red" "blue"]}
                             :count {:type "integer" :description "How many"}
                             :ok {:type "boolean"}}
                :required ["colour"]}
        qs (elicit/questions schema)]
    (is (= ["red" "blue"] (:options (first qs))))
    (is (= ["true" "false"] (:options (nth qs 2))))
    (is (= {:action "accept" :content {:colour "blue" :count 3 :ok true}}
           (elicit/result schema {:decision :allow :answers ["blue" "3" "true"]})))
    (is (= {:action "accept" :content {:colour "red"}}
           (elicit/result schema {:decision :allow :answers ["red" "" nil]}))
        "an optional field left empty is left out")
    (is (= {:action "cancel"} (elicit/result schema {:decision :allow :answers [nil]}))
        "a required field left empty is no answer")
    (is (= {:action "decline"} (elicit/result schema {:decision :deny})))
    (is (= {:action "cancel"} (elicit/result schema {:decision :deny :timed-out true})))
    (is (nil? (elicit/questions {:type "object"})) "a bare confirmation has no questions")))

(deftest an-elicitation-is-put-to-a-person-and-answered
  (let [state (atom {:tools [lookup-tool {:name "ask" :description "Asks." :inputSchema {:type "object"}}]})
        asked (atom [])
        stop (atom false)]
    (try
      (with-fake state
        (with-redefs [config/mcp-servers (constantly {:servers {:web (assoc spec :policy :allow)}})
                      approval/policy (constantly {:mode :block :wait-ms 3000 :on-timeout :deny})]
          (answering {:decision :allow :answers ["blue" "2"]} asked stop)
          (let [r (run-tool {} {"action" "call" "server" "web" "tool" "ask"})]
            (is (= :elicitation (:kind (first @asked))))
            (is (str/includes? (:reason (first @asked)) "Which colour?"))
            (is (= ["red" "blue"] (:options (first (:questions (first @asked))))))
            (is (str/includes? (:result r) "\"action\":\"accept\""))
            (is (str/includes? (:result r) "\"colour\":\"blue\""))
            (is (str/includes? (:result r) "\"count\":2")))))
      (finally (reset! stop true)))))

(deftest an-elicitation-with-nobody-there-is-cancelled
  (let [state (atom {:tools [{:name "ask" :description "Asks." :inputSchema {:type "object"}}]})]
    (with-fake state
      (with-redefs [config/mcp-servers (constantly {:servers {:web (assoc spec :policy :allow)}})
                    approval/policy (constantly {:mode :refuse})]
        (is (str/includes? (:result (run-tool {} {"action" "call" "server" "web" "tool" "ask"}))
                           "\"action\":\"cancel\""))))))
