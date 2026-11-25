;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.client
  "A Model Context Protocol client (karamazov-05z0.16, after autolith's MCP
  design; Streamable HTTP and elicitation karamazov-k7gl.3).

  One long-lived session per configured server, over the transport its
  entry names: `:command` is the stdio transport (samizdat.mcp.stdio), a
  child process speaking one message per line; `:url` is Streamable HTTP
  (samizdat.mcp.http). The session logic is the same over both: requests
  park on their id, replies are routed to them, the server's own requests
  are answered, and a transport that closes releases every waiter and
  forgets the server, so the next call starts it again rather than waiting
  on a corpse.

  A server may ask for something in the middle of a call
  (`elicitation/create`). The call's caller passes `:on-elicit`, a function
  of the request's params returning the result; while it runs, the call's
  own deadline is held, since a person answering is not a server that
  stopped.

  Mechanism only: start, list tools, call one. Which servers a project may
  run, what a call needs approved and who answers an elicitation are the
  `mcp` tool's and config.edn :mcp's."
  (:require [clojure.string :as str]
            [ebb.core :as ebb]
            [samizdat.cancel :as cancel]
            [samizdat.mcp.http :as http]
            [samizdat.mcp.stdio :as stdio]
            [samizdat.mcp.transport :as t]))

(def protocol-version "2025-06-18")

;; name -> {:transport :token :next-id :pending :calls :tools :stale?
;;          :server-info :timeout-ms}
(defonce ^:private clients (atom {}))

(defn env-for
  "The stdio child's environment — see samizdat.mcp.stdio/env-for."
  [env getenv]
  (stdio/env-for env getenv))

(defn- now [] (System/currentTimeMillis))

(defn- reply! [{:keys [transport]} id result-or-error]
  (try (t/send! transport (merge {:jsonrpc "2.0" :id id} result-or-error))
       (catch Throwable _ nil)))

(defn- elicit!
  "Answer the server's elicitation `msg`, which arrived on the exchange of
  request `origin` (nil when the wire cannot say). Its handler is that
  call's, or — over stdio, where nothing ties the two — the one call in
  flight that has one. With none, the answer is decline: nobody here asked
  for this."
  [{:keys [calls] :as client} msg origin]
  (let [[rid call] (or (find @calls origin)
                       (first (filter (comp :on-elicit val) @calls)))
        handler (:on-elicit call)]
    (future
      (some-> (:deadline call) (reset! Long/MAX_VALUE))
      (let [result (try (if handler (handler (:params msg)) {:action "decline"})
                        (catch Throwable _ {:action "cancel"}))]
        (when-let [d (:deadline (get @calls rid))]
          (reset! d (+ (now) (:timeout-ms client))))
        (reply! client (:id msg) {:result result})))))

(defn- on-message
  "Route one message from the server."
  [name client msg origin]
  (let [{:keys [pending]} client]
    (cond
      (and (contains? msg :id) (or (contains? msg :result) (contains? msg :error)))
      (when-let [p (get @pending (:id msg))] (deliver p msg))
      ;; The server's tool list moved: re-list before the next use.
      (= "notifications/tools/list_changed" (:method msg))
      (swap! clients #(if (contains? % name) (assoc-in % [name :stale?] true) %))
      (and (contains? msg :id) (= "elicitation/create" (:method msg)))
      (elicit! client msg origin)
      (and (contains? msg :id) (= "ping" (:method msg)))
      (reply! client (:id msg) {:result {}})
      (contains? msg :id)
      (reply! client (:id msg) {:error {:code -32601 :message (str "not supported: " (:method msg))}})
      :else nil)))

(defn- request!
  "Send `method` and wait for its answer: up to `timeout-ms`, held while an
  elicitation the server sent during it is being answered. The :result,
  or a throw naming what went wrong — an error reply, a timeout, a closed
  server. `on-elicit` answers an elicitation the server sends meanwhile."
  ([client method params timeout-ms] (request! client method params timeout-ms nil))
  ([{:keys [pending next-id calls name transport]} method params timeout-ms on-elicit]
   (let [id (swap! next-id inc)
         p (promise)
         deadline (atom (+ (now) timeout-ms))]
     (swap! pending assoc id p)
     (swap! calls assoc id {:deadline deadline :on-elicit on-elicit})
     (try
       (t/send! transport {:jsonrpc "2.0" :id id :method method :params params})
       (let [msg (loop []
                   ;; In slices, each wait ending on its own, so a deadline
                   ;; an elicitation moves is read again.
                   (let [slice (min (- @deadline (now)) 1000)
                         [tag v] (if (pos? slice)
                                   (cancel/with-deadline (ebb/via ebb/blk (deref p slice ::slice))
                                     (+ slice 1000))
                                   [:timeout])]
                     (cond
                       (= :err tag) (throw v)
                       (and (= :ok tag) (not= ::slice v)) v
                       (> @deadline (now)) (recur)
                       :else ::timeout)))]
         (cond
           (= ::timeout msg) (throw (ex-info (str "mcp " name ": no answer to " method)
                                             {:mcp/error :timeout :server name}))
           (= ::closed msg) (throw (ex-info (str "mcp " name ": the server closed during " method)
                                            {:mcp/error :closed :server name}))
           (:error msg) (throw (ex-info (str "mcp " name ": " (get-in msg [:error :message]))
                                        {:mcp/error :reply :server name :error (:error msg)}))
           :else (:result msg)))
       (finally (swap! pending dissoc id)
                (swap! calls dissoc id))))))

(defn- notify! [{:keys [transport]} method params]
  (t/send! transport {:jsonrpc "2.0" :method method :params params}))

(defn start!
  "Open server `name` from `spec` — {:command [argv…] :env {…}} for stdio,
  {:url … :token-env \"VAR\"} for Streamable HTTP, either with :timeout-ms
  — and run the initialize handshake. Returns the client."
  [name {:keys [url timeout-ms] :as spec}]
  (let [token (Object.)
        pending (atom {})
        cell (promise)
        handlers {:on-message (fn [msg origin] (on-message name @cell msg origin))
                  :on-close (fn []
                              (doseq [[_ p] @pending] (deliver p ::closed))
                              (swap! clients #(if (identical? (get-in % [name :token]) token)
                                                (dissoc % name)
                                                %)))
                  :version protocol-version}
        transport (if url (http/open spec handlers) (stdio/open spec handlers))
        client {:name name :transport transport :token token
                :next-id (atom 0) :pending pending :calls (atom {})
                ;; The caller's (gates.edn :mcp :timeout-ms, through the tool).
                :timeout-ms timeout-ms}]
    (deliver cell client)
    (try
      (let [r (request! client "initialize"
                        {:protocolVersion protocol-version
                         ;; Elicitation is always answered — by a person, or
                         ;; with cancel when there is nobody to ask.
                         :capabilities {:elicitation {}}
                         :clientInfo {:name "samizdat" :version "1"}}
                        timeout-ms)]
        (notify! client "notifications/initialized" {})
        (assoc client :server-info (:serverInfo r) :stale? true))
      (catch Throwable e
        (t/close! transport)
        (throw e)))))

(defn client-for
  "The live client for server `name`, started on first use."
  [name spec]
  (locking clients
    (or (get @clients name)
        (let [c (start! name spec)]
          (swap! clients assoc name c)
          c))))

(defn tools
  "Server `name`'s tools as [{:name :description :inputSchema}], listed again
  when the server said its list changed. Follows pagination."
  [name spec]
  (let [c (client-for name spec)]
    (if (and (not (get-in @clients [name :stale?])) (get-in @clients [name :tools]))
      (get-in @clients [name :tools])
      (let [ts (loop [cursor nil acc []]
                 (let [r (request! c "tools/list" (cond-> {} cursor (assoc :cursor cursor))
                                   (:timeout-ms c))
                       acc (into acc (:tools r))]
                   (if-let [n (:nextCursor r)] (recur n acc) acc)))]
        (swap! clients #(if (contains? % name)
                          (update % name assoc :tools ts :stale? false)
                          %))
        ts))))

(defn- canonical
  "`x` with every map sorted by its keys as strings, so equal definitions
  print equal whatever order their keys arrived in."
  [x]
  (cond
    (map? x) (into (sorted-map)
                   (map (fn [[k v]] [(if (keyword? k) (subs (str k) 1) (str k)) (canonical v)]))
                   x)
    (sequential? x) (mapv canonical x)
    :else x))

(defn tool-digest
  "A stable hash of a tool's definition — its name, description and input
  schema. An approval pinned to it stops applying when the server changes
  what the tool says it does or takes."
  [tool]
  (let [text (t/encode (canonical (select-keys tool [:name :description :inputSchema])))
        md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String text "UTF-8"))]
    (subs (apply str (map #(format "%02x" (bit-and % 0xff)) bs)) 0 16)))

(defn call
  "Call `tool` on server `name` with `args`. {:text :error?} — the result's
  text content joined, and whether the server said the call failed.
  `opts` {:on-elicit f}: f answers an elicitation the server sends during
  the call (its params in, the result map out)."
  ([name spec tool args] (call name spec tool args nil))
  ([name spec tool args {:keys [on-elicit]}]
   (let [c (client-for name spec)
         r (request! c "tools/call" {:name (str tool) :arguments (or args {})}
                     (:timeout-ms c) on-elicit)]
     {:error? (boolean (:isError r))
      :text (str/join "\n" (keep (fn [part]
                                   (case (:type part)
                                     "text" (:text part)
                                     "resource" (or (get-in part [:resource :text]) (pr-str (:resource part)))
                                     (pr-str part)))
                                 (:content r)))})))

(defn shutdown!
  "Stop server `name`, if it is running."
  [name]
  (when-let [c (get @clients name)]
    (swap! clients dissoc name)
    (try (t/close! (:transport c)) (catch Throwable _ nil)))
  nil)

(defn shutdown-all!
  "Stop every server this process started."
  []
  (locking clients
    (doseq [n (vec (keys @clients))] (shutdown! n))))

(defn running
  "The names of the servers running now."
  []
  (set (keys @clients)))
