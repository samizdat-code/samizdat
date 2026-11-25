;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.hooks
  "Hooks: a person's commands, run when something happens in a run
  (karamazov-k7gl.10).

  A hook is data in config.edn — `:hooks [{:name :trigger :command
  :timeout-ms}]`, layered like the rest of the file — and config.edn is a
  file no tool the agent holds can write, so the agent cannot give itself a
  hook. Four triggers: `:run-start`, `:after-write` (a file tool wrote a
  file), `:after-shell` (the shell tool ran a command) and `:run-end`.

  MECHANISM ONLY. A hook's command goes through `policy/run-shell` exactly as
  the model's own command would — the rules, the session's grants, a
  person's answer to an :ask, the scrubbed environment, the OS sandbox and
  output redaction — so a hook can do nothing the shell tool could not. It is
  NOT run as the branch: no branch rides the shell ctx, so it neither moves
  the branch's flow label nor opens an execution record. What it is told
  arrives as SAMIZDAT_HOOK_* environment variables.

  Its output is bounded, journalled as a `:hook` event, and scanned (ANSI
  stripped) for lines `###SAMIZDAT:<key> <value>`, returned as directives.
  WHAT REACHES THE MODEL is gates.edn :hooks — which directive keys, from
  which triggers, and which tools map to which trigger. A `message` directive
  is delivered through the directive queue as a notice from issuer `hook`, so
  it lands at the branch's next steer boundary like any other."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.config :as config]
            [samizdat.lexicon :as lexicon]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.util :as util]))

(def triggers
  "The moments a hook can be attached to: what the harness has a seam for."
  #{:run-start :after-write :after-shell :run-end})

(defn- policy* [] (or (gates/threshold :hooks) {}))

(defn normalize
  "config.edn's :hooks as a vector of {:name :trigger :command :timeout-ms}.
  An entry that is not a map, names a trigger there is no seam for, or has no
  command is dropped with a warning — a hook that silently means nothing is a
  setting somebody believes is in effect, but one bad entry must not take the
  rest down. The timeout defaults to gates.edn :hooks :default-timeout-ms and
  never passes the shell's own cap (policy/shell-timeout), a security bound."
  [hooks]
  (let [default (or (:default-timeout-ms (policy*)) (:max-ms policy/shell-timeout))
        cap (:max-ms policy/shell-timeout)]
    (if-not (sequential? hooks)
      []
      (into []
            (keep (fn [h]
                    (let [trigger (when (map? h) (keyword (some-> (:trigger h) name)))
                          command (when (map? h) (some-> (:command h) str str/trim not-empty))]
                      (if (and trigger (contains? triggers trigger) command)
                        {:name (str (or (some-> (:name h) name) command))
                         :trigger trigger
                         :command command
                         :timeout-ms (min cap (let [t (:timeout-ms h)]
                                                (if (and (integer? t) (pos? t)) t default)))}
                        (do (log/warn "hooks: ignoring a hook that is not"
                                      "{:trigger <one of" (sort triggers) "> :command <string>}:"
                                      (pr-str h))
                            nil)))))
            hooks))))

(defn configured
  "The hooks config.edn attaches to `trigger` for the project at `root`."
  [root trigger]
  (filterv #(= trigger (:trigger %))
           (normalize (:hooks (config/file-config (or root "."))))))

(def ^:private ansi
  #"\x1B\[[0-?]*[ -/]*[@-~]|\x1B\][^\x07\x1B]*(?:\x07|\x1B\\)|\x1B[@-Z\\-_]")

(defn strip-ansi [s] (str/replace (str s) ansi ""))

(def ^:private directive-re
  ;; wordlists.edn :hook-directive — the marker a hook's output line carries
  ;; to say something back. Data, so a project can pick its own marker.
  (util/generation-cache lexicon/gen
                         #(re-pattern (lexicon/wordlist :hook-directive))))

(defn directives
  "The `###SAMIZDAT:<key> <value>` lines of `out`, ANSI stripped first, as
  [{:key :value}] in order. The marker must start its line."
  [out]
  (into []
        (keep (fn [line]
                (when-let [[_ k v] (re-find (directive-re) line)]
                  {:key k :value (str v)})))
        (str/split-lines (strip-ansi out))))

(defn hook-vars
  "The SAMIZDAT_HOOK_* variables a hook is told what happened through."
  [{:keys [name trigger]} {:keys [run-id branch-id root]} {:keys [path command exit status resumed]}]
  (cond-> {"SAMIZDAT_HOOK_TRIGGER" (clojure.core/name trigger)
           "SAMIZDAT_HOOK_NAME" (str name)}
    run-id (assoc "SAMIZDAT_HOOK_RUN_ID" (str run-id))
    branch-id (assoc "SAMIZDAT_HOOK_BRANCH_ID" (str branch-id))
    root (assoc "SAMIZDAT_HOOK_ROOT" (str root))
    path (assoc "SAMIZDAT_HOOK_PATH" (str path))
    command (assoc "SAMIZDAT_HOOK_COMMAND" (str command))
    (some? exit) (assoc "SAMIZDAT_HOOK_EXIT" (str exit))
    status (assoc "SAMIZDAT_HOOK_STATUS" (str status))
    resumed (assoc "SAMIZDAT_HOOK_RESUMED" "1")))

(defn- run-one
  "Run one hook through the shell's gate and return its event."
  [{:keys [conn run-id root] :as where} hook vars]
  (let [env (merge (into {} (System/getenv)) (hook-vars hook where vars))
        r (try (policy/run-shell {:conn conn :run-id run-id :root root
                                  :args {:command (:command hook)}
                                  :env env :timeout-ms (:timeout-ms hook)})
               ;; Not :allow: nothing is known to have run.
               (catch Throwable e
                 {:category :failure :policy {:effect :error}
                  :error (or (ex-message e) (str (class e)))}))
        effect (get-in r [:policy :effect])
        {:keys [exit out err timeout?]} (:data r)
        text (strip-ansi (str out (when (seq (str err)) (str "\n" err))))]
    (cond-> {:name (:name hook) :trigger (:trigger hook) :command (:command hook)
             :effect effect}
      (= :allow effect)
      (assoc :exit exit :timeout? (boolean timeout?)
             :output (if-let [cap (:output-chars (policy*))] (util/truncate-middle text cap) text)
             :directives (directives out))

      (:error r) (assoc :error (:error r))

      (#{:deny :ask} effect)
      (assoc :refused (get-in r [:policy :refused])))))

(defn- deliver!
  "Queue what gates.edn :hooks :deliver lets through as a notice to the
  branch (to every branch, for a trigger that has none). Returns what was
  queued."
  [{:keys [conn run-id branch-id]} event vars]
  (let [{:keys [keys triggers]} (:deliver (policy*))]
    (when (and conn run-id (contains? (set triggers) (:trigger event)))
      (into []
            (keep (fn [{:keys [key value]}]
                    (when (and (contains? (set keys) key) (not (str/blank? value)))
                      (try
                        (interventions/submit!
                         conn run-id
                         {:branch-id branch-id :kind "message" :issued-by "hook"
                          :payload {:text (str/trim (prompt/render "hook-message"
                                                                   {:name (:name event)
                                                                    :trigger (name (:trigger event))
                                                                    :path (:path vars)
                                                                    :command (:command vars)
                                                                    :text value}))}})
                        key
                        (catch Throwable e
                          (log/warn "hooks: could not queue a message:" (ex-message e))
                          nil)))))
            (:directives event)))))

(defn fire!
  "Run every hook attached to `trigger`, in config order, and return their
  events. `ctx` carries :conn :run-id :root and, for a tool trigger, :branch
  and :turn; `vars` what happened (:path, :command, :exit, :status). Never
  throws: a hook is the person's, and a broken one must not cost the run."
  [{:keys [conn run-id root branch turn] :as _ctx} trigger vars]
  (let [hooks (try (configured root trigger)
                   (catch Throwable e
                     (log/warn "hooks: could not read config:" (ex-message e))
                     []))]
    (into []
          (map (fn [hook]
                 (let [where {:conn conn :run-id run-id :root root
                              :branch-id (:id branch)}
                       event (run-one where hook vars)
                       sent (try (deliver! where event vars)
                                 (catch Throwable _ nil))
                       event (cond-> event (seq sent) (assoc :delivered sent))]
                   (when (and conn run-id)
                     (try (journal/note! conn run-id :hook
                                         {:branch-id (:id branch) :turn turn
                                          :data (assoc event :vars vars)})
                          (catch Throwable e
                            (log/warn "hooks: could not journal:" (ex-message e)))))
                   event)))
          hooks)))

;; --- the seams ---------------------------------------------------------------

(defn after-tool!
  "The tool seam (agent.tools/run-tool): a call that ended fires the trigger
  gates.edn :hooks :tool-triggers maps its tool to — :after-write when a
  file tool succeeded, :after-shell when the shell's policy let the command
  run (whatever it exited with)."
  [{:keys [tool-name args] :as ctx} r]
  (case (get (:tool-triggers (policy*)) (str tool-name))
    :after-write
    (when (and (= :success (:category r)) (:path args))
      (fire! ctx :after-write {:path (str (:path args))}))

    :after-shell
    (when (= :allow (get-in r [:policy :effect]))
      (fire! ctx :after-shell {:command (str (:command args))
                               :exit (get-in r [:data :exit])}))

    nil))

(defonce ^:private live
  ;; run-id -> {:conn :root}: what :run-end needs and run-state's teardown,
  ;; which knows only the run id, does not carry.
  (atom {}))

(defn run-started!
  "The drivers' seam: a run (or a resumed one) has opened. Fires :run-start
  and remembers what :run-end will need."
  [{:keys [conn run-id root resumed?]}]
  (when run-id
    (swap! live assoc run-id {:conn conn :root root})
    (fire! {:conn conn :run-id run-id :root root} :run-start
           (cond-> {} resumed? (assoc :resumed true)))))

(defn run-ended!
  "run-state's teardown seam: fires :run-end with the run's final status,
  once, and forgets the run."
  [run-id]
  (when-let [{:keys [conn root]} (get @live run-id)]
    (swap! live dissoc run-id)
    (let [status (try (some-> (runs/get-run conn run-id) :status str)
                      (catch Throwable _ nil))]
      (fire! {:conn conn :run-id run-id :root root} :run-end
             (cond-> {} status (assoc :status status))))))
