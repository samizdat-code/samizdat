;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.acp.main
  "`jolt acp`: samizdat as an ACP agent on stdin/stdout (karamazov-05z0.17).

      jolt acp                 # against the server SAMIZDAT_URL / HARNESS_PORT names
      jolt acp URL             # against the server at URL

  Start the server first (`jolt serve`). It is not started in this process:
  its log would share stdout with the protocol.

  An editor starts this as a subprocess and speaks JSON-RPC to it, one
  message per line. stdout is the protocol — nothing else may be printed
  there, which is why logging goes to stderr. Each session/prompt runs on a
  thread of its own so a session/cancel can arrive while it waits; the
  agent's own requests to the editor (permission questions) are answered on
  the same stream and routed back by id."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.acp.agent :as agent]
            [samizdat.api.client :as client])
  (:import [java.io BufferedReader InputStreamReader]))

(defn- default-base []
  (or (System/getenv "SAMIZDAT_URL")
      (str "http://127.0.0.1:" (or (System/getenv "HARNESS_PORT") "3985"))))

(defn connection
  "The protocol's state over one pair of streams: `write!` sends one
  message; outbound requests wait on their id."
  [write!]
  {:write! write! :next-id (atom 0) :pending (atom {}) :sessions (atom {})})

(defn- send! [{:keys [write!]} msg] (write! (json/write-str msg :escape-slash false)))

(defn- respond! [conn id result] (send! conn {:jsonrpc "2.0" :id id :result result}))

(defn- error! [conn id message]
  (send! conn {:jsonrpc "2.0" :id id :error {:code -32603 :message (str message)}}))

(defn- request-client!
  "Ask the editor something and wait for its answer."
  [{:keys [next-id pending] :as conn} method params]
  (let [id (str "s" (swap! next-id inc)) p (promise)]
    (swap! pending assoc id p)
    (try (send! conn {:jsonrpc "2.0" :id id :method method :params params})
         (deref p)
         (finally (swap! pending dissoc id)))))

(defn- io-for
  "The effects one prompt runs with, against the server at `base`."
  [conn base session cancel]
  (let [ok-body (fn [r] (when (:ok r) (:body r)))]
    {:start-run! (fn [problem]
                   (let [r (client/start-run! base {:problem problem})]
                     (or (get-in r [:body :id]) (get-in r [:body :run_id])
                         {:error (or (:error r) "the server did not start a run")})))
     :intervene! (fn [run-id text] (client/intervene! base run-id {:kind "message" :payload text}))
     :journal (fn [run-id cursor] (or (ok-body (client/journal-since base run-id cursor)) {:events []}))
     :run-detail (fn [run-id] (or (some-> (ok-body (client/run-detail base run-id)) :run) {}))
     :approvals (fn [run-id] (or (some->> (ok-body (client/approvals base run-id)) :approvals
                                          (filter #(= "pending" (str (:status %)))))
                                 []))
     :ask-permission! (fn [params] (request-client! conn "session/request_permission" params))
     :decide! (fn [aid d] (client/decide! base aid {:decision d}))
     :update! (fn [u] (send! conn {:jsonrpc "2.0" :method "session/update"
                                   :params {:sessionId (:id @session) :update u}}))
     :sleep! (fn [] (Thread/sleep 1000))
     :cancelled? (fn [] @cancel)}))

(defn handle!
  "One message from the editor."
  [{:keys [pending sessions] :as conn} base {:keys [id method params] :as msg}]
  (cond
    ;; An answer to something we asked.
    (and id (nil? method))
    (when-let [p (get @pending (str id))] (deliver p (or (:result msg) {})))

    (= "initialize" method) (respond! conn id (agent/initialize-result))

    (= "session/new" method)
    (let [sid (str (random-uuid))]
      (swap! sessions assoc sid {:session (atom {:id sid}) :cancel (atom false)})
      (respond! conn id {:sessionId sid}))

    (= "session/prompt" method)
    (if-let [{:keys [session cancel]} (get @sessions (:sessionId params))]
      (do (reset! cancel false)
          (future
            (try (respond! conn id (agent/run-prompt! (io-for conn base session cancel)
                                                      session (:prompt params)))
                 (catch Throwable e (error! conn id (ex-message e))))))
      (error! conn id "no such session"))

    (= "session/cancel" method)
    (when-let [{:keys [session cancel]} (get @sessions (:sessionId params))]
      (reset! cancel true)
      (when-let [r (:run-id @session)] (client/interrupt! base r)))

    id (send! conn {:jsonrpc "2.0" :id id :error {:code -32601 :message (str "unknown method " method)}})
    :else nil))

(defn serve!
  "Read the editor's messages from `in` until it closes, against `base`."
  [^BufferedReader in write! base]
  (let [conn (connection write!)]
    (loop []
      (when-let [line (.readLine in)]
        (when-let [msg (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
          (try (handle! conn base msg)
               (catch Throwable e
                 (binding [*out* *err*] (println "samizdat acp:" (ex-message e))))))
        (recur)))))

(defn -main [& args]
  (let [base (or (first (remove #(str/starts-with? % "--") args)) (default-base))
        out (java.io.PrintStream. System/out true "UTF-8")
        write! (fn [line] (locking out (.println out ^String line) (.flush out)))]
    (serve! (BufferedReader. (InputStreamReader. System/in "UTF-8")) write! base)
    (System/exit 0)))
