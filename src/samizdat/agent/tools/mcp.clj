;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.mcp
  "The `mcp` tool: the tools of the MCP servers config.edn :mcp names
  (karamazov-05z0.16, after autolith's MCP design).

  Actions: servers, tools {server}, call {server, tool, arguments}. A call
  passes the server's :policy first —

    :allow      every tool
    :read-only  only the tools in :trusted-tools; the rest are refused
    :prompt     each call is put to a person (the default)
    :deny       nothing

  — and its result passes the redaction boundary like a shell's. Which
  servers a project may run at all is config/mcp-servers: a project's own
  config names servers only for a root the person trusted."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.approval :as approval]
            [samizdat.config :as config]
            [samizdat.mcp.client :as mcp]
            [samizdat.prompt :as prompt]
            [samizdat.security.secrets :as secrets]))

(defn- msg [ctx] (prompt/render "mcp-tool" ctx))

(defn- servers [ctx]
  (config/mcp-servers (:root ctx)))

(defn- spec-of [ctx server]
  (let [{:keys [servers untrusted]} (servers ctx)
        k (some #(when (= (name %) (str server)) %) (keys servers))]
    (cond
      ;; How long a call may take, unless the server entry says.
      k {:spec (merge {:timeout-ms (:timeout-ms (gates/threshold :mcp))} (get servers k))}
      (some #{(str server)} (map name untrusted)) {:untrusted true}
      :else {:unknown true})))

(defn- allowed
  "What the server's policy says about calling `tool`: :yes, :no, or :ask."
  [{:keys [policy trusted-tools]} tool]
  (case (keyword (or policy :prompt))
    :allow :yes
    :deny :no
    :read-only (if (contains? (set (map str trusted-tools)) (str tool)) :yes :no)
    :ask))

(defn- ask!
  "Put the call to a person. true when they allow it."
  [{:keys [run-id branch]} server tool args]
  (let [{:keys [mode wait-ms on-timeout]} (approval/policy run-id)]
    (case mode
      :yolo true
      :block (let [id (approval/request! {:run-id run-id :branch-id (:id branch) :kind :mcp
                                          :input (str server "/" tool)
                                          :details (pr-str args)
                                          :reason (str/trim (msg {:ask-reason true :server server :tool tool}))})]
               (= :allow (:decision (approval/await! id wait-ms {:decision (or on-timeout :deny)}))))
      false)))

(defmethod base/run-tool "mcp" [{:keys [branch] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        server (some-> (base/arg ctx :server) str str/trim not-empty)]
    (try
      (case action
        "servers"
        (let [{:keys [servers untrusted]} (servers ctx)]
          (base/ok branch (msg {:listed true
                                :servers (mapv (fn [[k s]] {:name (name k)
                                                            :policy (name (or (:policy s) :prompt))
                                                            :running (contains? (mcp/running) (name k))})
                                               (sort-by key servers))
                                :untrusted (not-empty (str/join ", " (map name untrusted)))})))

        ("tools" "call")
        (let [{:keys [spec untrusted unknown]} (when server (spec-of ctx server))]
          (cond
            (nil? server) (base/malformed branch (str (base/missing ctx :server) "\n\n" (msg {:usage true})))
            untrusted (base/refusal branch (msg {:untrusted true :server server}))
            unknown (base/fail branch (msg {:unknown true :server server}))
            (= "tools" action)
            (base/ok branch (msg {:tools true :server server
                                  :entries (mapv (fn [t] {:name (:name t) :description (:description t)
                                                          :schema (pr-str (:inputSchema t))})
                                                 (mcp/tools server spec))}))
            :else
            (let [tool (some-> (base/arg ctx :tool) str str/trim not-empty)
                  args (or (base/arg ctx :arguments) {})]
              (if-not tool
                (base/malformed branch (str (base/missing ctx :tool) "\n\n" (msg {:usage true})))
                (case (allowed spec tool)
                  :no (base/refusal branch (msg {:denied true :server server :tool tool
                                                 :policy (name (or (:policy spec) :prompt))}))
                  (:yes :ask)
                  (if (and (= :ask (allowed spec tool)) (not (ask! ctx server tool args)))
                    (base/refusal branch (msg {:declined true :server server :tool tool}))
                    (let [{:keys [text error?]} (mcp/call server spec tool args)
                          ;; Model-bound, so through the redaction boundary
                          ;; (RFC-003), as a shell result is.
                          text (secrets/redact text (secrets/known-values (into {} (System/getenv))))]
                      (if error?
                        (base/fail branch (msg {:failed true :server server :tool tool :text text}))
                        (base/ok branch (msg {:called true :server server :tool tool :text text}))))))))))

        (base/malformed branch (msg {:usage true})))
      (catch clojure.lang.ExceptionInfo e
        (if (:mcp/error (ex-data e))
          (base/unavailable branch (str "MCP server " server) e)
          (throw e))))))
