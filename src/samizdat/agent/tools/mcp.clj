;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.mcp
  "The `mcp` tool: the tools of the MCP servers config.edn :mcp names
  (karamazov-05z0.16, after autolith's MCP design).

  Actions: servers, tools {server}, call {server, tool, arguments}. A call
  passes the server's :policy first —

    :allow      every tool
    :read-only  only the tools in :trusted-tools; the rest are refused
    :prompt     each call goes to the approval gate
    :deny       nothing

  — an entry with none takes gates.edn :mcp :default-policy. A :prompt call
  is approved for the tool's DEFINITION (karamazov-k7gl.3): the ask names a
  hash of its name, description and schema, an allow-always is kept as a
  grant of that text, and a server that changes the tool is asked about
  again. An elicitation the server sends during the call goes to a person
  on the same approvals queue as ask_human, or is cancelled when there is
  nobody to ask.

  A call's result passes the redaction boundary like a shell's, and lowers
  the branch's trust as webfetch does (karamazov-kfnn.11): a server can hand
  back a page, an issue body or a message anyone wrote. A server entry with
  `:output :trusted` is the operator saying its output is theirs. Which
  servers a project may run at all is config/mcp-servers: a project's own
  config names servers only for a root the person trusted."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.approval :as approval]
            [samizdat.config :as config]
            [samizdat.mcp.client :as mcp]
            [samizdat.mcp.elicit :as elicit]
            [samizdat.prompt :as prompt]
            [samizdat.security.flow :as flow]
            [samizdat.security.secrets :as secrets]
            [samizdat.store.grants :as grants]))

(defn- msg [ctx] (prompt/render "mcp-tool" ctx))

(defn- gate
  "gates.edn :mcp, or {} when it is not a map."
  []
  (let [g (gates/threshold :mcp)] (if (map? g) g {})))

(defn- servers [ctx]
  (config/mcp-servers (:root ctx)))

(defn- spec-of [ctx server]
  (let [{:keys [servers untrusted]} (servers ctx)
        k (some #(when (= (name %) (str server)) %) (keys servers))]
    (cond
      ;; How long a call may take, unless the server entry says.
      k {:spec (merge {:timeout-ms (:timeout-ms (gate))} (get servers k))}
      (some #{(str server)} (map name untrusted)) {:untrusted true}
      :else {:unknown true})))

(defn- policy-of
  "The server entry's :policy, else gates.edn :mcp :default-policy — which
  may only tighten: a default that would run calls unasked reads as
  :prompt, as does a project copy of the file that predates the key."
  [{:keys [policy]}]
  (keyword (or policy
               (#{:prompt :read-only :deny} (:default-policy (gate)))
               :prompt)))

(defn- allowed
  "What the server's policy says about calling `tool`: :yes, :no, or :ask."
  [{:keys [trusted-tools] :as spec} tool]
  (case (policy-of spec)
    :allow :yes
    :deny :no
    :read-only (if (contains? (set (map str trusted-tools)) (str tool)) :yes :no)
    :ask))

(defn- approve!
  "Put the call to the approval gate, pinned to the tool's definition:
  :allow, :deny, or :unlisted when the server does not list the tool, so
  there is no definition to approve.

  The ask is `mcp:<server>/<tool>#<digest>` (samizdat.store.grants): a
  grant of that text, or an allow of the same text earlier this run, is
  this definition approved. An allow-always is kept as a grant."
  [{:keys [run-id branch conn] :as ctx} server spec tool args]
  (if-let [d (some #(when (= (str (:name %)) (str tool)) %) (mcp/tools server spec))]
    (let [pin (str grants/mcp-prefix server "/" tool "#" (mcp/tool-digest d))
          {:keys [mode]} (approval/policy run-id)]
      (cond
        (and conn run-id (some #{pin} (grants/mcp-grants conn run-id))) :allow
        (and (= :yolo mode) (false? (:yolo-allows? (gate)))) :deny
        :else
        (let [r (approval/resolve-ask
                 (assoc ctx :branch-id (:id branch))
                 {:effect :ask :ask-kind :mcp :input pin
                  :details (pr-str args)
                  :reason (str/trim (msg {:ask-reason true :server server :tool tool}))
                  :content-key pin
                  ;; The classifier judges where a shell command reaches,
                  ;; not what a server's tool does.
                  :person-only? true})]
          (when (and (= :allow (:effect r)) (:allowed-always? r) conn run-id)
            (grants/grant! conn run-id pin))
          (if (= :allow (:effect r)) :allow :deny))))
    :unlisted))

(defn- elicit!
  "Answer `server`'s elicitation during `tool`: the question goes to a
  person on the approvals queue (a questionnaire, as ask_human's) when the
  run has someone to ask, for up to gates.edn :mcp :elicitation-wait-ms;
  with nobody there, the server hears cancel."
  [{:keys [run-id branch]} server tool {:keys [message requestedSchema]}]
  (let [{:keys [mode wait-ms]} (approval/policy run-id)]
    (if (not= :block mode)
      {:action "cancel"}
      (let [id (approval/request! {:run-id run-id :branch-id (:id branch) :kind :elicitation
                                   :input (str server "/" tool)
                                   :reason (str/trim (msg {:elicit-reason true :server server
                                                           :tool tool :message (str message)}))
                                   :details (pr-str requestedSchema)
                                   :questions (elicit/questions requestedSchema)})]
        (elicit/result requestedSchema
                       (approval/await! id (or (:elicitation-wait-ms (gate)) wait-ms)
                                        {:decision :deny}))))))

(defn- call! [{:keys [branch] :as ctx} server spec tool args]
  (let [{:keys [text error?]} (mcp/call server spec tool args
                                        {:on-elicit #(elicit! ctx server tool %)})
        ;; Before the result reaches the model: a failure's text came from
        ;; the server too.
        _ (when-not (= :trusted (some-> (:output spec) keyword))
            (flow/observe!* ctx {:trust :untrusted}
                            {:turn (:turn ctx) :tool "mcp"
                             :via (str server "/" tool)}))
        ;; Model-bound, so through the redaction boundary (RFC-003), as a
        ;; shell result is.
        text (secrets/redact text (secrets/known-values (into {} (System/getenv))))]
    (if error?
      (base/fail branch (msg {:failed true :server server :tool tool :text text}))
      (base/ok branch (msg {:called true :server server :tool tool :text text})))))

(defmethod base/run-tool "mcp" [{:keys [branch] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        server (some-> (base/arg ctx :server) str str/trim not-empty)]
    (try
      (case action
        "servers"
        (let [{:keys [servers untrusted]} (servers ctx)]
          (base/ok branch (msg {:listed true
                                :servers (mapv (fn [[k s]] {:name (name k)
                                                            :policy (name (policy-of s))
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
                (case (case (allowed spec tool)
                        :yes :allow
                        :no :refused
                        :ask (approve! ctx server spec tool args))
                  :refused (base/refusal branch (msg {:denied true :server server :tool tool
                                                      :policy (name (policy-of spec))}))
                  :unlisted (base/refusal branch (msg {:unlisted true :server server :tool tool}))
                  :deny (base/refusal branch (msg {:declined true :server server :tool tool}))
                  :allow (call! ctx server spec tool args))))))

        (base/malformed branch (msg {:usage true})))
      (catch clojure.lang.ExceptionInfo e
        (if (:mcp/error (ex-data e))
          (base/unavailable branch (str "MCP server " server) e)
          (throw e))))))
