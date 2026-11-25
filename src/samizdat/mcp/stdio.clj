;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.stdio
  "The MCP stdio transport: one child process per server, one JSON-RPC
  message per line on its stdin and stdout. One reader thread hands every
  line to the session; the process exiting closes the transport.

  The child runs under the scrubbed environment plus the variables its
  server entry names — never the harness's own provider keys."
  (:require [jolt.process :as jp]
            [samizdat.mcp.transport :as t]
            [samizdat.security.secrets :as secrets])
  (:import [java.io BufferedReader InputStreamReader OutputStream]))

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

(defrecord Stdio [proc ^OutputStream out]
  t/Transport
  (send! [_ msg]
    (let [line (.getBytes (str (t/encode msg) "\n") "UTF-8")]
      (locking out
        (.write out line) (.flush out))))
  (close! [_]
    (try (.destroyForcibly ^java.lang.Process (:proc proc)) (catch Throwable _ nil))
    nil))

(defn open
  "Spawn `command` with `env` and start reading it. Every line that parses
  goes to (on-message msg nil); end of output calls (on-close)."
  [{:keys [command env]} {:keys [on-message on-close]}]
  (let [p (jp/process (mapv str command)
                      {:env (env-for env #(System/getenv ^String %))})
        ^java.lang.Process osproc (:proc p)
        in (BufferedReader. (InputStreamReader. (.getInputStream osproc) "UTF-8"))]
    (future
      (loop []
        (when-let [line (try (.readLine in) (catch Throwable _ nil))]
          (when-let [msg (t/decode line)]
            (try (on-message msg nil) (catch Throwable _ nil)))
          (recur)))
      (on-close))
    (->Stdio p (.getOutputStream osproc))))
