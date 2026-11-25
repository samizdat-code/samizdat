;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.http
  "The MCP Streamable HTTP transport (protocol 2025-06-18,
  karamazov-k7gl.3).

  Every message is a POST of one JSON-RPC message to the server's one
  endpoint. A notification or a reply is answered 202 with no body. A
  request is answered with either one JSON message or an event stream
  (text/event-stream) whose events are JSON-RPC messages: notifications and
  requests of the server's own — an elicitation, a ping — and finally the
  reply. Each is handed to the session with the request's id as its origin,
  so a server request is known to belong to the call that was running.

  The session id the server assigns on initialize (the Mcp-Session-Id
  header) is sent on every request after it, with the protocol version. A
  404 on a request that carried one means the server forgot the session:
  the transport closes, and the next use starts a new one. An entry's
  :token-env names the variable holding a bearer token, so the token is
  never written in config.

  The request itself goes through `request`, the one seam tests stub."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.http-client :as http]
            [samizdat.mcp.transport :as t]))

(defn request
  "Send one HTTP request (a clj-http-lite request map). The seam."
  [req]
  (http/request req))

(defn getenv
  "This process's variable `k`. A seam, so tests need not set one."
  [k]
  (System/getenv ^String (str k)))

(defn- header
  "Response header `k`, whatever case the server wrote it in."
  [headers k]
  (some (fn [[h v]] (when (= (str/lower-case (name h)) k) (if (sequential? v) (first v) v)))
        headers))

(defn read-events!
  "Read an event stream from `reader`, calling (on-data text) with each
  event's data — its data lines joined with newlines. Comments, ids and
  event names are not the session's business. Returns at end of stream."
  [^java.io.BufferedReader reader on-data]
  (loop [data []]
    (let [line (.readLine reader)]
      (cond
        (nil? line) (when (seq data) (on-data (str/join "\n" data)))
        (= "" line) (do (when (seq data) (on-data (str/join "\n" data))) (recur []))
        (str/starts-with? line ":") (recur data)
        (str/starts-with? line "data:")
        (let [v (subs line 5)]
          (recur (conj data (if (str/starts-with? v " ") (subs v 1) v))))
        :else (recur data)))))

(defn- error-reply [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defrecord Http [url token version session on-message on-close closed]
  t/Transport
  (send! [this msg]
    (let [rid (when (and (contains? msg :id) (:method msg)) (:id msg))
          exchange
          (fn []
            (try
              (let [sid @session
                    r (request {:method :post :url url
                                :headers (cond-> {"Content-Type" "application/json"
                                                  "Accept" "application/json, text/event-stream"}
                                           token (assoc "Authorization" (str "Bearer " token))
                                           sid (assoc "Mcp-Session-Id" sid
                                                      "MCP-Protocol-Version" version))
                                :body (t/encode msg)
                                :as :stream
                                :throw-exceptions false})
                    status (:status r)
                    ctype (str/lower-case (str (header (:headers r) "content-type")))
                    body (:body r)
                    deliver! (fn [m] (doseq [m (if (sequential? m) m [m])]
                                       (when (map? m) (on-message m rid))))]
                (when-let [s (header (:headers r) "mcp-session-id")]
                  (reset! session (str s)))
                (cond
                  (and (= 404 status) sid)
                  (do (some-> ^java.io.Closeable body .close)
                      (t/close! this))

                  (not (<= 200 status 299))
                  (let [text (try (slurp body) (catch Throwable _ ""))]
                    (when rid
                      (on-message (error-reply rid status
                                               (str "HTTP " status
                                                    (when-not (str/blank? text)
                                                      (str ": " (subs text 0 (min 200 (count text)))))))
                                  rid)))

                  (str/starts-with? ctype "text/event-stream")
                  (with-open [rd (io/reader body :encoding "UTF-8")]
                    (read-events! rd (fn [data] (some-> (t/decode data) deliver!))))

                  (str/starts-with? ctype "application/json")
                  (some-> (t/decode (slurp body)) deliver!)

                  :else (some-> ^java.io.Closeable body .close)))
              (catch Throwable e
                (when rid (on-message (error-reply rid -32000 (or (ex-message e) (str e))) rid)))))]
      (if rid
        ;; A request's answer may be a stream that stays open while the
        ;; server asks us something, and our answer is a POST of its own: so
        ;; the exchange runs on its own thread and the session waits on the
        ;; reply as it does for stdio.
        (do (future (exchange)) nil)
        (exchange))))
  (close! [_]
    (when (compare-and-set! closed false true)
      ;; Tell the server the session is over, as the spec asks; it may not
      ;; care, and a failure here changes nothing.
      (when-let [sid @session]
        (future
          (try (request {:method :delete :url url :throw-exceptions false
                         :headers (cond-> {"Mcp-Session-Id" sid}
                                    token (assoc "Authorization" (str "Bearer " token)))})
               (catch Throwable _ nil))))
      (on-close))
    nil))

(defn open
  "A transport to the server at `url`. Nothing is sent until the session's
  first message."
  [{:keys [url token-env]} {:keys [on-message on-close version]}]
  (->Http (str url)
          (some-> token-env getenv not-empty)
          version (atom nil) on-message on-close (atom false)))
