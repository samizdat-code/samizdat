;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.argrules
  "PER-ARGUMENT RULES FOR EVERY TOOL (karamazov-k7gl.11).

  The shell has its allow/ask/deny table (samizdat.security.policy), and a
  few tools have fixed checks of their own: the writers are confined to the
  project root, a read outside it asks, the confinement files ask a person.
  What no tool had was a rule the OPERATOR writes about its arguments — ask
  before any webfetch outside the docs host, never write under secrets/,
  refuse an mcp call to one server. These are those rules, from config.edn:

    {:tools {:rules [{:tool \"webfetch\" :args {:url \"^https?://\"} :decision :ask}
                     {:tool \"webfetch\" :args {:url \"^https://docs\\\\.example\\\\.com/\"}
                      :decision :allow}
                     {:tool \"write_file\" :args {:path \"^secrets/\"} :decision :deny}]}}

  A rule matches a call when `:tool` names it (a string, a list of them, or
  :all) and every `:args` pattern — a regex, as a string since EDN has no
  regex literal — is found in that argument (any element, for a list). A
  rule with no :args matches every call to its tool.

  Within one layer the LAST matching rule decides, as the shell table does,
  so a later allow can carve an exception out of an ask. ACROSS layers the
  most restrictive decision wins (deny > ask > allow): a project file someone
  else wrote cannot lift a person's ask by allowing what it asks about.

  These rules only add asks and denies. An :allow lifts an earlier ask in its
  own layer and nothing else: it never lets past the ceiling, the shell's
  table, the root confinement or the flow labels, each of which still judges
  the call. An :ask goes to the approval path the shell uses
  (approval/resolve-ask), so a person, yolo or a refusal decides it the same
  way.

  In src/ and read from config.edn, for the reason samizdat.security.ceiling
  gives. Fails closed: a pattern that does not compile turns its rule into a
  deny of the tool it names, and an unknown decision is an ask."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.approval :as approval]
            [samizdat.config :as config]
            [samizdat.prompt :as prompt]
            [samizdat.userspace :as userspace]))

(def ^:private rank {:allow 0 :ask 1 :deny 2})

(def ^:private compile-pattern
  (memoize (fn [p]
             (try (re-pattern (str p))
                  (catch Throwable e
                    (log/warn "config.edn :tools :rules: pattern" (pr-str p)
                              "does not compile; the rule denies:" (ex-message e))
                    ::bad)))))

(def ^:private pattern-class (class #"x"))

(defn- pattern [p]
  (cond (string? p) (compile-pattern p)
        (instance? pattern-class p) p
        :else ::bad))

(defn- tool-name [x]
  (if (or (keyword? x) (symbol? x)) (name x) (str x)))

(defn- names-tool? [{:keys [tool]} t]
  (cond
    (or (= :all tool) (= "*" tool)) true
    (and (coll? tool) (not (map? tool))) (contains? (into #{} (map tool-name) tool) t)
    (some? tool) (= (tool-name tool) t)
    :else false))

(defn- arg-value [args k]
  (let [nm (tool-name k)]
    (some #(when (contains? args %) (get args %))
          [(keyword nm) nm (keyword (str/replace nm "-" "_")) (keyword (str/replace nm "_" "-"))])))

(defn- arg-matches? [args [k p]]
  (let [v (arg-value args k)
        re (pattern p)]
    (and (some? v)
         (some #(re-find re (str %)) (if (sequential? v) v [v])))))

(defn- judge
  "What `rule` says about this call: nil when it does not match, else
  {:decision :rule}."
  [rule t args]
  (when (and (map? rule) (names-tool? rule t))
    (let [pats (:args rule)
          pats (if (map? pats) pats {})
          bad? (some #(= ::bad (pattern %)) (vals pats))
          decision (get #{:allow :ask :deny} (:decision rule) :ask)]
      (cond
        bad? {:decision :deny :rule rule}
        (every? #(arg-matches? args %) pats) {:decision decision :rule rule}))))

(defn- decide-layer [rules t args]
  (when (sequential? rules)
    (reduce (fn [acc rule] (or (judge rule t args) acc)) nil rules)))

(defn decide
  "The decision `layers` (each a vector of rules, lowest first) make about
  calling `tool` with `args`: {:decision :allow|:ask|:deny :rule} or nil
  when no rule matched."
  [layers tool args]
  (let [t (tool-name tool)
        args (if (map? args) args {})]
    (reduce (fn [acc r]
              (cond (nil? r) acc
                    (nil? acc) r
                    (> (rank (:decision r)) (rank (:decision acc))) r
                    :else acc))
            nil
            (map #(decide-layer % t args) layers))))

(defn layers-of
  "The rule layers for one tool call: each config.edn layer's, then the run's
  own config map's."
  [{:keys [root config]}]
  (let [root (or root (get-in config [:run :root]) (userspace/project-root))]
    (concat (when root (map :rules (config/tool-layers root)))
            [(get-in config [:tools :rules])])))

(defn- label [rule]
  (pr-str (select-keys rule [:tool :args :decision])))

(defn refusal
  "nil when the operator's rules let this call go ahead, else
  {:message :decision :rule}. An :ask is put to approval/resolve-ask first."
  [{:keys [tool-name args] :as ctx}]
  (when-let [{:keys [decision rule]} (decide (layers-of ctx) tool-name args)]
    (when-not (= :allow decision)
      (let [d (if (= :ask decision)
                (approval/resolve-ask ctx {:effect :ask :head (str tool-name) :complex? true
                                           :input (str tool-name " " (pr-str args))
                                           :reason (str "argument rule " (label rule))})
                {:effect :deny})]
        (when-not (= :allow (:effect d))
          {:decision decision :rule rule
           :message (try (prompt/render "argument-rule"
                                        {:tool (str tool-name) :rule (label rule)
                                         :asked (and (= :ask decision)
                                                     (= :block (:mode (approval/policy (:run-id ctx)))))
                                         :note (:note d)})
                         (catch Throwable _
                           (pr-str {:refused (str tool-name) :by [:tools :rules] :rule rule})))})))))
