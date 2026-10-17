;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.client
  "A Model Context Protocol client over stdio (karamazov-05z0.16, after
  autolith's MCP design).

  One long-lived process per configured server, speaking JSON-RPC 2.0 one
  message per line on stdin/stdout — the MCP stdio transport. The shape is
  samizdat.lsp.client's: one reader thread routes responses to the request
  parked on their id, and any exit releases every waiter and forgets the
  server, so the next call starts it again rather than waiting on a corpse.

  Mechanism only: start, list tools, call one. Which servers a project may
  run, which roles see them and what a call needs approved are the `mcp`
  tool's and config.edn :mcp's. The child runs under the scrubbed
  environment plus the variables its server entry names — never the
  harness's own provider keys."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [ebb.core :as ebb]
            [jolt.process :as jp]
            [samizdat.cancel :as cancel]
            [samizdat.security.secrets :as secrets])
  (:import [java.io BufferedReader InputStreamReader OutputStream]))

(def protocol-version "2025-06-18")

;; name -> {:proc :out :in :next-id :pending :tools :stale? :server-info}
(defonce ^:private clients (atom {}))

(defn- send! [{:keys [^OutputStream out]} msg]
  ;; :escape-slash false — "tools/list", not "tools\/list": JSON allows
  ;; both, and a server matching method names literally does not.
  (let [line (.getBytes (str (json/write-str msg :escape-slash false) "\n") "UTF-8")]
    (locking out
      (.write out line) (.flush out))))

(defn- start-reader! [{:keys [pending ^BufferedReader in name] :as client}]
  (future
    (loop []
      (let [line (try (.readLine in) (catch Throwable _ nil))]
        (when line
          (let [msg (try (json/read-str line :key-fn keyword) (catch Throwable _ nil))]
            (cond
              (nil? msg) nil
              (and (contains? msg :id) (or (contains? msg :result) (contains? msg :error)))
              (when-let [p (get @pending (:id msg))] (deliver p msg))
              ;; The server's tool list moved: re-list before the next use.
              (= "notifications/tools/list_changed" (:method msg))
              (swap! clients #(if (contains? % name) (assoc-in % [name :stale?] true) %))
              :else nil))
          (recur))))
    (doseq [[_ p] @pending] (deliver p ::closed))
    (swap! clients #(if (identical? (get-in % [name :proc]) (:proc client)) (dissoc % name) %)))
  nil)

(defn- request!
  "Send `method` and wait up to `timeout-ms` for its answer. The :result, or
  a throw naming what went wrong — an error reply, a timeout, a closed
  server."
  [{:keys [pending next-id name] :as client} method params timeout-ms]
  (let [id (swap! next-id inc)
        p (promise)]
    (swap! pending assoc id p)
    (try
      (send! client {:jsonrpc "2.0" :id id :method method :params params})
      (let [[tag msg] (cancel/with-deadline (ebb/via ebb/blk (deref p)) timeout-ms)]
        (cond
          (= :timeout tag) (throw (ex-info (str "mcp " name ": no answer to " method)
                                           {:mcp/error :timeout :server name}))
          (= :err tag) (throw msg)
          (= ::closed msg) (throw (ex-info (str "mcp " name ": the server closed during " method)
                                           {:mcp/error :closed :server name}))
          (:error msg) (throw (ex-info (str "mcp " name ": " (get-in msg [:error :message]))
                                       {:mcp/error :reply :server name :error (:error msg)}))
          :else (:result msg)))
      (finally (swap! pending dissoc id)))))

(defn- notify! [client method params]
  (send! client {:jsonrpc "2.0" :method method :params params}))

(defn env-for
  "The child's environment: the scrubbed one, plus `env` — a map of
  variable to value, where a value {:from \"VAR\"} is read from this
  process's environment, which is how a server's key reaches it without
  being written in config."
  [env getenv]
  (into (secrets/scrubbed-process-env)
        (keep (fn [[k v]]
                (let [v (if (map? v) (getenv (str (:from v))) v)]
                  (when (some? v) [(str k) (str v)]))))
        env))

(defn start!
  "Spawn server `name` from `spec` ({:command [argv…] :env {…} :timeout-ms
  n}) and run the initialize handshake. Returns the client."
  [name {:keys [command env timeout-ms]}]
  (let [p (jp/process (mapv str command)
                      {:env (env-for env #(System/getenv ^String %))})
        ^java.lang.Process osproc (:proc p)
        client {:name name :proc p
                :out (.getOutputStream osproc)
                :in (BufferedReader. (InputStreamReader. (.getInputStream osproc) "UTF-8"))
                :next-id (atom 0) :pending (atom {})
                ;; The caller's (gates.edn :mcp :timeout-ms, through the tool).
                :timeout-ms timeout-ms}]
    (start-reader! client)
    (let [r (request! client "initialize"
                      {:protocolVersion protocol-version
                       :capabilities {}
                       :clientInfo {:name "samizdat" :version "1"}}
                      (:timeout-ms client))]
      (notify! client "notifications/initialized" {})
      (assoc client :server-info (:serverInfo r) :stale? true))))

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

(defn call
  "Call `tool` on server `name` with `args`. {:text :error?} — the result's
  text content joined, and whether the server said the call failed."
  [name spec tool args]
  (let [c (client-for name spec)
        r (request! c "tools/call" {:name (str tool) :arguments (or args {})}
                    (:timeout-ms c))]
    {:error? (boolean (:isError r))
     :text (str/join "\n" (keep (fn [part]
                                  (case (:type part)
                                    "text" (:text part)
                                    "resource" (or (get-in part [:resource :text]) (pr-str (:resource part)))
                                    (pr-str part)))
                                (:content r)))}))

(defn shutdown!
  "Stop server `name`, if it is running."
  [name]
  (when-let [c (get @clients name)]
    (try (.destroyForcibly ^java.lang.Process (:proc (:proc c))) (catch Throwable _ nil))
    (swap! clients dissoc name))
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
