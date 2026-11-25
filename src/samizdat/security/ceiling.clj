;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.ceiling
  "THE TOOL CEILING (karamazov-k7gl.5): the most a run may use, whatever its
  workflow says.

  The workflow is the agent's to rewrite — roles.edn surfaces, phases.edn
  refusals, the prompts that document each tool — and every one of those can
  hand a role a tool. The ceiling is the operator's, in config.edn:

    {:tools {:ceiling #{\"read_file\" \"grep\" \"edit_file\" \"done\"}
             :deny #{\"webfetch\" \"eval\"}}}

  `:ceiling` is the set a run may use (absent: every tool); `:deny` is taken
  out of it. Each config.edn layer — the file SAMIZDAT_CONFIG_FILE names, the
  project's .samizdat/config.edn, the person's global file — is read on its
  own and they are INTERSECTED: ceilings intersect and denies union, so a
  layer can only narrow. A project file someone else wrote cannot widen a
  person's ceiling by naming more tools, and the run's own config map (an
  override, a test) narrows like one more layer.

  Enforced in the dispatch seam (samizdat.agent.tools/run-tool), which every
  call crosses, and applied to what the model is shown: the role catalogue
  (samizdat.agent.roles) and the native tool specs (samizdat.agent.infer).
  A userspace edit that enables a tool above the ceiling is accepted — it
  narrows nothing — and the tool stays refused.

  IN src/ AND READ FROM config.edn, for the reason `policy/protected-paths`
  gives: this confines the agent, and a list the agent can edit is not a
  list. The file tools and the shell's protected paths refuse writes to
  every config.edn layer (files/operator-config?).

  FAILS CLOSED. A `:ceiling` or `:deny` that names no tools (a number, a
  map) permits nothing rather than everything: somebody meant to restrict."
  (:require [clojure.set :as set]
            [clojure.tools.logging :as log]
            [samizdat.config :as config]
            [samizdat.prompt :as prompt]
            [samizdat.userspace :as userspace]))

(def unrestricted
  "No ceiling: every tool, nothing denied."
  {:allow nil :deny #{}})

(defn- tool-name [x]
  (if (or (keyword? x) (symbol? x)) (name x) (str x)))

(defn- names
  "A layer value as a set of tool names, nil when absent, ::malformed when it
  names none."
  [v]
  (cond
    (nil? v) nil
    (string? v) #{v}
    (or (keyword? v) (symbol? v)) #{(name v)}
    (and (coll? v) (not (map? v))) (into #{} (map tool-name) v)
    :else ::malformed))

(defn of-layer
  "One config layer's `:tools` block as {:allow set-or-nil :deny set}."
  [tools]
  (let [tools (if (map? tools) tools {})
        allow (names (:ceiling tools))
        deny (names (:deny tools))]
    (when (or (= ::malformed allow) (= ::malformed deny))
      (log/warn "config.edn :tools names no tools; permitting none:" (pr-str tools)))
    {:allow (if (or (= ::malformed allow) (= ::malformed deny)) #{} allow)
     :deny (if (set? deny) deny #{})}))

(defn combine
  "Ceilings as one: allows intersect (nil is unrestricted), denies union."
  [cs]
  (reduce (fn [acc c]
            {:allow (cond (nil? (:allow acc)) (:allow c)
                          (nil? (:allow c)) (:allow acc)
                          :else (set/intersection (:allow acc) (:allow c)))
             :deny (set/union (:deny acc) (:deny c))})
          unrestricted
          cs))

(defn of-layers
  "The ceiling several `:tools` blocks allow between them."
  [blocks]
  (combine (map of-layer blocks)))

(defn permits?
  "Whether ceiling `c` lets a run call `tool`."
  [c tool]
  (let [t (tool-name tool)]
    (and (not (contains? (:deny c) t))
         (or (nil? (:allow c)) (contains? (:allow c) t)))))

(defn unrestricted?
  "Whether `c` permits every tool."
  [c]
  (and (nil? (:allow c)) (empty? (:deny c))))

(defn for-root
  "The ceiling of the project at `root`: every config.edn layer, plus `extra`
  (one more `:tools` block, the run's own config) narrowing it. A nil root —
  a test, a bare REPL — reads no files, the way config/eval-mode does."
  ([root] (for-root root nil))
  ([root extra]
   (of-layers (concat (when root (config/tool-layers root)) [extra]))))

(defn current
  "The ceiling of the bound project."
  []
  (for-root (userspace/project-root)))

(defn of-ctx
  "The ceiling for one tool call."
  [{:keys [root config]}]
  (for-root (or root (get-in config [:run :root]) (userspace/project-root))
            (:tools config)))

(defn filter-specs
  "`specs` ({:name ...}) without the tools `c` does not permit."
  [c specs]
  (if (unrestricted? c)
    specs
    (filterv #(permits? c (:name %)) specs)))

(defn refusal
  "Why this call is above the ceiling, as the message the branch reads, or
  nil when the ceiling permits it."
  [{:keys [tool-name] :as ctx}]
  (let [c (of-ctx ctx)]
    (when-not (permits? c tool-name)
      ;; The prose is userspace; the refusal is not. A project whose role map
      ;; predates the prompt still refuses, in plain words.
      (try (prompt/render "tool-ceiling" {:tool (str tool-name)})
           (catch Throwable _
             (pr-str {:refused (str tool-name) :by [:tools :ceiling]}))))))
