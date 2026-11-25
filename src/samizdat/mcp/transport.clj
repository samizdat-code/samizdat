;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.transport
  "The seam between an MCP session and the wire it runs over
  (karamazov-k7gl.3).

  A transport moves JSON-RPC messages and nothing else: it sends one, and
  every message the server sends back — a reply, a notification, a request
  of its own — goes to the `on-message` function it was opened with, as
  (on-message msg origin). `origin` is the id of the request whose exchange
  carried the message, when the wire says (an HTTP response stream belongs
  to one POST), else nil (stdio is one stream for everything). When the
  wire is gone it calls `on-close` once. Everything above that — ids,
  waiting, what a server request means — is samizdat.mcp.client's, the
  same for every transport."
  (:require [clojure.data.json :as json]))

(defprotocol Transport
  (send! [t msg] "Send one JSON-RPC message.")
  (close! [t] "Stop: the process, the session. Idempotent."))

(defn encode
  "`msg` as JSON text. :escape-slash false — \"tools/list\", not
  \"tools\\/list\": JSON allows both, and a server matching method names
  literally does not."
  [msg]
  (json/write-str msg :escape-slash false))

(defn decode
  "One JSON-RPC message (or a batch, as a vector) from `text`, or nil when
  it is not JSON."
  [text]
  (try (json/read-str (str text) :key-fn keyword) (catch Throwable _ nil)))
