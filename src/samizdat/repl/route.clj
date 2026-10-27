;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.repl.route
  "WHICH IMAGE AN EVAL LANDS IN, and how a refusal reads when it does not.

  `config/eval-image` decides — `:harness` for the supervisor, whose job IS the
  harness, `:project` for everyone else, `:off` for nobody. This namespace owns
  the consequences: starting the project image on first use, keeping one per
  project root for the life of the process, bounding an eval that never
  returns, and turning a kernel refusal into a sentence the model can act on.

  THERE IS NO FALLBACK TO THE HARNESS IMAGE. A project image that will not
  start fails the eval and says so. That is not defensiveness, it is the
  security property: `:image-connect-ms` lives in gates.edn, which is
  agent-editable userspace, so a fallback would let a run shrink the timeout
  until the project image 'failed' and it was handed the unconfined one
  instead. Failing is the only safe direction, and the model is told the
  harness is at fault so it does not go hunting in its own code.

  A REFUSAL IS NOT A DEFECT, and the model cannot tell them apart on its own.
  `posix_spawn failed (errno 1)` reads exactly like a bug in the form that
  produced it, and a model that believes that goes looking in the file — the
  karamazov-60c failure, ten turns spent reading code that was never wrong.
  Every refusal this seam returns says what was refused, why, and which tool
  to reach for instead."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [clojure.tools.logging :as log]
            [samizdat.config :as config]
            [samizdat.approval :as approval]
            [samizdat.bridge :as bridge]
            [samizdat.callable :as callable]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt :as prompt]
            [samizdat.repl :as repl]
            [samizdat.repl.guard :as guard]
            [samizdat.repl.image :as image]
            [samizdat.security.confine :as confine]
            [samizdat.security.sandbox :as sandbox]
            [samizdat.util :as util]))

(defonce ^:private images
  ;; root -> image. Per ROOT rather than per run: two runs against the same
  ;; checkout share a process the way two branches share one, and the cost of
  ;; a jolt start is paid once.
  (atom {}))

(defn runtime-exec-roots
  "The directory holding the `jolt` binary, resolved through its symlink.

  Falls back to the Homebrew prefix only when `jolt` is not on PATH — which
  cannot happen in a process that is itself running under jolt, but an empty
  list here would stop every image starting, and that is a worse failure than
  a slightly wide directory."
  []
  (or (some-> (fs/which "jolt") str sandbox/resolved
              (->> (io/file)) .getParentFile .getPath vector)
      ["/opt/homebrew"]))

(defn sandbox-spec
  "The paths the image is confined to, for a project at `root`.

  IN src/ AND NOT IN gates.edn, deliberately — the same rule
  `policy/protected-paths` states. This is the list of places the agent's own
  image may not read; a list the agent can edit is not a list."
  [home harness-root]
  ;; The secret regions and the harness checkout are shared with the shell
  ;; (samizdat.security.confine); /etc is the image's alone, because a shell
  ;; resolves names and verifies TLS through it and a jolt REPL does not.
  {:deny-read (conj (confine/secret-regions home harness-root) "/etc")
   ;; THE RUNTIME BINARY'S OWN DIRECTORY, and nothing else.
   ;;
   ;; This has to be narrow and it is easy to get wrong in the safe-looking
   ;; direction. `exec-roots` cannot be empty — sandbox-exec's own exec of jolt
   ;; is subject to the profile, so an empty list means nothing starts — and
   ;; the obvious fix is to add the directories a runtime "might" need. Adding
   ;; /bin and /usr/bin does start it, and also hands the REPL every standard
   ;; unix utility: measured, `(jolt.process/sh "echo" "X")` came back
   ;; `:exit 0` through a profile that looked confining. /bin/echo was allowed
   ;; because /bin was.
   ;;
   ;; So it is resolved from the jolt binary itself rather than guessed, and
   ;; through its symlink — a Homebrew jolt resolves into Cellar, which is a
   ;; directory holding jolt and not much else.
   :exec-roots (runtime-exec-roots)})

(defn image-for!
  "The project image for `root`, started if this is the first eval against it.
  nil when it could not be started."
  [root backend]
  (or (get @images root)
      (locking images
        (or (get @images root)
            (when-let [im (image/start!
                           {:root root :backend backend
                            ;; The checkout the harness's code came from, not
                            ;; the cwd: a built binary runs in the project and
                            ;; has none (util/harness-checkout).
                            :sandbox-spec (sandbox-spec (System/getenv "HOME")
                                                        (util/harness-checkout))})]
              ;; THE PROJECT'S OWN ROOTS, in the image that evaluates for it.
              ;; The image starts as a bare `jolt nrepl-server` — no alias —
              ;; so a test tree declared only under an alias's :extra-paths was
              ;; not on its search path, and a namespace a branch had just
              ;; written there could not be required (karamazov-1b37). Added,
              ;; never replacing, like the harness image's.
              (let [roots (repl/declared-roots root)]
                (image/eval-in im (str "(jolt.host/set-source-roots! (into (vec (jolt.host/source-roots)) "
                                       "(remove (set (jolt.host/source-roots)) " (pr-str roots) ")))")))
              (swap! images assoc root im)
              im)))))

(defn release!
  "Stop and forget the image for `root`. Called at run teardown, and whenever
  an eval times out — a runaway computation is only really stopped by killing
  the process it is running in."
  [root]
  (when-let [im (get @images root)]
    (swap! images dissoc root)
    (image/stop! im))
  nil)

(defn image-port
  "The port of the live image for `ctx`'s root, or nil when none is up. For a
  caller that wants to know whether an image survived something."
  [{:keys [root]}]
  (some-> (get @images root) :port))

(defn release-all!
  "Stop every image. For shutdown, and for tests that must not leak a process."
  []
  (doseq [root (keys @images)] (release! root))
  nil)

;; --- making a refusal legible ------------------------------------------------

(def ^:private exec-refusal
  "What the kernel says when the sandbox refused to start a process."
  #"posix_spawn|Operation not permitted|Permission denied|EPERM")

(defn- exec-attempt? [s]
  (boolean (re-find #"posix_spawn|process|sh\b|exec" (str s))))

(defn denial
  "A kernel refusal rendered as policy, or nil when `error` is an ordinary
  failure. Pure over the strings so the wording is testable without a sandbox.

  `sandboxed?` GATES IT, because the strings are not exclusive to the sandbox:
  an ordinary `Permission denied` on a chmod-000 file inside the project would
  otherwise be dressed up as policy and the model told its code is fine, which
  is the same wrong-answer-that-looks-right this whole seam exists to remove.
  Under `:sandbox :none` nothing here can be a kernel refusal at all. The
  residual case — a genuine in-project permission error while the sandbox IS
  on — still mislabels, and narrowing that needs the path out of the message."
  [error root sandboxed?]
  (when (and error sandboxed? (re-find exec-refusal (str error)))
    (prompt/render "image-denied"
                   {:root root
                    :detail (str/trim (str error))
                    :exec (exec-attempt? error)})))

(defn- legible
  "`r` with a sandbox refusal reworded, so a denial cannot read as a defect
  in the form that produced it."
  [r root sandboxed?]
  (if-let [d (and (not (:ok r)) (denial (:error r) root sandboxed?))]
    (assoc r :error d :policy-refusal? true)
    r))

;; --- routing -----------------------------------------------------------------

(defn image-of
  "Which image `ctx` evaluates in. An unidentified role gets `:project` — the
  safe direction, and the reason `config/eval-image` treats an unknown role
  that way rather than falling through to the harness."
  [ctx]
  (or (:eval-image ctx)
      (config/eval-image (config/eval-mode (:root ctx))
                         (or (:role ctx) (get-in ctx [:branch :role])))))

(defn- timed-out
  "The answer for an eval `r` that hit its deadline in the project image.

  Bounded by the image itself (RFC-013): at the deadline the image is asked
  to interrupt the eval, which stops even a tight loop at the next engine
  tick, and the image survives with its defs. Only an eval that does not
  answer the interrupt — blocked in a foreign call the interrupt cannot
  reach — costs the image, since leaving it would hand the next eval a busy
  image."
  [r root timeout]
  (if (:interrupted? r)
    (log/warn "project image eval timed out after" timeout
              "ms — interrupted, image kept")
    (do (release! root)
        (log/warn "project image eval timed out after" timeout
                  "ms and did not answer the interrupt — image restarted")))
  {:ok false :error-type "timeout" :timeout? true
   :error (prompt/render "image-timeout" {:ms timeout})})

(defn- strict
  "Form text that evaluates with unresolved symbols refused.

  jolt's nREPL binds `*allow-unresolved-vars*` true around every eval (a name
  may arrive in a later one), so in the image a typo came back as a SUCCESS
  whose value was `#object[clojure.lang.Var$Unbound …]` — a wrong answer that
  looks like an answer, where the harness image says `Unable to resolve
  symbol` as JVM Clojure does. The form is read by the image in the session's
  namespace, as before, and only its evaluation is rebound."
  [text]
  (str "(binding [clojure.core/*allow-unresolved-vars* false] (eval (quote "
       text "\n)))"))

(defn- ns-switch
  "The namespace form `text` moves evaluation into — `(ns x …)` or
  `(in-ns 'x)` — or nil. Sent whole, the image carried such a switch to the
  forms after it; sent one form at a time with the session's `ns` key, the
  rest of the batch would land back in the session, so the switch is carried
  here."
  [text]
  (let [form (try (read-string text) (catch Throwable _ nil))]
    (when (seq? form)
      (case (some-> (first form) name)
        "ns" (when (symbol? (second form)) (second form))
        "in-ns" (let [a (second form)]
                  (when (and (seq? a) (= 'quote (first a)) (symbol? (second a)))
                    (second a)))
        nil))))

(defn bridge-policy
  "gates.edn :eval-bridge — whether tools are callable from eval, which, and
  how long a call may wait (karamazov-xn0h.7)."
  []
  (gates/threshold :eval-bridge))

(defn- bridge-for
  "The bridge options for this eval's image calls. Policy enables ordinary
  tools; a caller with :bridge-required? may instead install only its declared
  :bridge-tools and handler for a private execution environment."
  [im ctx]
  (let [{:keys [enabled? tools cells? call-timeout-ms poll-ms]} (bridge-policy)
        handler (:bridge ctx)]
    (when (and handler (or enabled? (:bridge-required? ctx)))
      (let [r (image/install-bridge! im (distinct (concat tools (:bridge-tools ctx)))
                                     (when cells? (map :id (callable/cells)))
                                     {:timeout-ms call-timeout-ms
                                      :timeout-message (prompt/render "bridge-timeout"
                                                                      {:ms call-timeout-ms})})]
        (if (:ok r)
          {:on-request handler :poll-ms poll-ms :call-timeout-ms call-timeout-ms}
          (do (log/warn "eval bridge did not install:" (:error r)) nil))))))

(defn- eval-project
  "Evaluate `code` in the project image `im`, one top-level form at a time,
  the way repl/eval-code does in the harness image (karamazov-xn0h.3).

  Handed the whole text, the image's nREPL went on evaluating the forms after
  one that failed — measured: `(/ 1 0) (def after 2)` defined `after` — and
  the failure said nothing of which form broke. One form per exchange stops
  at the failure, says which form it was and how many ran before it, and
  shares one deadline across the batch.

  THE EXIT GUARD, here too. An exit in this image is not the harness going
  down, but it is every def of the run going with it and a restart, for
  nothing the model wanted (karamazov-xn0h.6). Checked before the first form
  runs, on every form that reads in the harness; one that only reads after
  an earlier form ran (`::alias/kw`) is not visible to it."
  [im root sandboxed? code session timeout bridge]
  (let [forms (try (repl/top-level-forms code) (catch Throwable _ nil))
        refused (when forms (repl/refusal (repl/readable-ahead forms) :project))
        where (some-> session str)]
    (cond
      refused
      {:ok false :error-type "process-exit" :policy-refusal? true
       :error (ex-message refused)}

      ;; Text the splitter cannot parse goes whole, and the image's own
      ;; reader says what is wrong with it.
      (empty? forms)
      (let [r (image/eval-in im code session {:timeout-ms timeout})]
        (if (:timeout? r)
          (timed-out r root timeout)
          (cond-> (legible r root sandboxed?) where (assoc :where where))))

      :else
      (let [deadline (+ (System/currentTimeMillis) timeout)
            n (count forms)]
        (loop [i 0 outs [] in session]
          (let [{:keys [text line]} (nth forms i)
                left (max 1 (- deadline (System/currentTimeMillis)))
                eval-id (when bridge (str (random-uuid)))
                r (image/eval-in im (cond->> (strict text) bridge (bridge/wrap eval-id))
                                 in (merge {:timeout-ms left}
                                           (when bridge (assoc bridge :bridge-eval eval-id))))
                outs (conj outs (str (:out r)))
                at {:index (inc i) :count n :line line :completed i}]
            (cond
              (:timeout? r)
              (assoc (timed-out r root timeout) :form at :out (str/join outs))

              (not (:ok r))
              (cond-> (assoc (legible r root sandboxed?) :form at :out (str/join outs))
                where (assoc :where where))

              (= (inc i) n)
              (assoc r :out (str/join outs))

              :else (recur (inc i) outs (or (ns-switch text) in)))))))))

(defn fork-session
  "A new eval session carrying `parent`'s defs, in whichever image holds them.

  The beam forks a branch's session so a child can call what its inherited
  transcript shows it defining. repl/fork-session copies the HARNESS
  namespace; under :project the defs live in the image, so the child started
  empty there while its transcript said otherwise (karamazov-xn0h.4). Both
  are copied: the harness copy is what the supervisor's image reads, the
  image copy what every other role's does. No image for `root` yet means no
  defs in one to copy."
  [root parent]
  (let [child (repl/fork-session parent)]
    (when-let [im (and parent (get @images root))]
      (image/fork-ns! im parent child))
    child))

(defn eval-for
  "Evaluate `code` for `ctx`, in whichever image its role and the operator's
  config put it. Same result shape as `repl/eval-code`, always."
  [{:keys [root] :as ctx} code session timeout-ms]
  (let [timeout (or timeout-ms repl/default-eval-timeout-ms)]
    (case (image-of ctx)
      :off {:ok false :error (prompt/render "image-off" {}) :error-type "eval-off"}
      ;; THE SUPERVISOR'S IMAGE IS STILL THE LIVE ONE, so the last door to the
      ;; S2 escape is here. Static, and honest about being static — see
      ;; guard/kernel-write?. The refusal names the rule and what it fired on.
      :harness (if-let [hit (first (guard/kernel-writes
                                    (try (read-string (str "(do " code "\n)"))
                                         (catch Exception _ nil))))]
                 {:ok false :error-type "kernel-write"
                  :policy-refusal? true
                  :error (prompt/render "kernel-write-refused"
                                        {:rule (name (:rule hit))
                                         :on (str (:on hit))})}
                 ;; WHERE ITS FILES ARE, before what it does (karamazov-jk2y):
                 ;; this process's cwd is the harness's, and nothing here can
                 ;; move it per branch, so with the project elsewhere a
                 ;; relative path reads or writes the harness's tree. No
                 ;; person's answer makes that the project's file, so this
                 ;; comes before the approval below, yolo included.
                 (let [form (try (read-string (str "(do " code "\n)"))
                                 (catch Exception _ nil))
                       off (some-> root str not-empty repl/off-root)
                       rel (when off (guard/relative-paths form))]
                   (if (seq rel)
                     {:ok false :error-type "relative-path" :policy-refusal? true
                      :error (prompt/render "harness-eval-relative-path"
                                            {:paths (str/join ", " (distinct rel))
                                             :root (:root off) :cwd (:cwd off)})}
                 ;; WHAT IT WOULD DO OUTSIDE ITSELF, read first
                 ;; (karamazov-0e2c.21, after xi's scan-code): a process, a
                 ;; write, a request from inside the harness skips every
                 ;; check the shell and file tools make. A person decides it
                 ;; under :block, yolo lets it, :refuse sends it to the tools
                 ;; that are checked.
                 (let [effs (guard/effects form)]
                   (if (empty? effs)
                     (repl/eval-code code session timeout)
                     (let [listed (str/join "\n" (map #(str (name (:kind %)) ": " (:call %)
                                                            (some->> (:target %) (str " "))) effs))
                           d (approval/resolve-ask ctx {:effect :ask :head "eval" :complex? true
                                                        :input listed
                                                        :reason "harness eval with effects"})]
                       (if (= :allow (:effect d))
                         (repl/eval-code code session timeout)
                         {:ok false :error-type "harness-effects" :policy-refusal? true
                          :error (prompt/render "harness-eval-effects"
                                                {:effects listed
                                                 :unanswered (:timed-out d)})})))))))
      ;; NO ROOT, NO IMAGE. Confinement is defined relative to the project, so
      ;; a ctx that never said which project cannot be confined to it: the
      ;; profile came out with no writable project root and the subprocess
      ;; inherited the HARNESS's working directory, which is a broken image
      ;; wearing a working one's clothes — it answered `(+ 1 2)` with 3 while
      ;; being unable to read the tree it was supposed to be developing. Refuse
      ;; instead, and say so to the operator rather than only to the model.
      (if (str/blank? (str root))
        (do (log/error "eval routed to a project image with no :root —"
                       "the ctx did not carry one; refusing rather than"
                       "confining to nothing")
            {:ok false :error-type "image-down"
             :error (prompt/render "image-down" {})})
        (let [backend (sandbox/backend-for (config/eval-sandbox root)
                                           (System/getProperty "os.name")
                                           (some? (fs/which "bwrap")))]
          (if-let [im (image-for! root backend)]
            (eval-project im root (not= :none backend) code session timeout
                          (bridge-for im ctx))
            {:ok false :error-type "image-down"
             :error (prompt/render "image-down" {})}))))))

;; --- doc and complete answer out of the SAME image ---------------------------
;;
;; They used to read the harness image unconditionally, which under `:project`
;; makes them the same wrong-answer-that-looks-right the cwd bug was: a model
;; asking for the docstring of a var in the project it is building would have
;; been handed samizdat's, plausibly and silently. Whatever image an eval lands
;; in is the one these have to describe.

(defn session-of
  "The eval session `ctx` evaluates in: the branch's, else the run's — the
  same choice the eval tool makes, so `doc` describes what `eval` sees."
  [ctx]
  (or (get-in ctx [:branch :repl-session]) (:repl-session ctx)))

(defn session-defs
  "What `ctx`'s eval session has defined, from the image it lives in, as
  repl/session-defs-form describes — or nil when there is no session, or
  its image has not been started. Asking never starts one: a role that only
  reads the manual has no business paying for a process."
  [ctx limit doc-chars]
  (when-let [s (session-of ctx)]
    (case (image-of ctx)
      :harness (repl/session-defs s limit doc-chars)
      :project (when-let [im (get @images (:root ctx))]
                 (let [r (image/eval-in im (str "(" (pr-str repl/session-defs-form) " '" s
                                                " " limit " " doc-chars ")"))]
                   (when (:ok r)
                     (try (read-string (str (:value r))) (catch Exception _ nil)))))
      nil)))

(defn- read-value
  "Evaluate `form` in `ctx`'s image and session and read the printed value
  back, or nil."
  [ctx form]
  (let [r (eval-for ctx form (session-of ctx) nil)]
    (when (:ok r)
      (try (read-string (str (:value r))) (catch Exception _ nil)))))

(defn doc-for
  "The docstring and arglists of `sym` as `ctx`'s eval would resolve it — so
  a def the model made in its own session is found, not just a qualified
  project var (karamazov-xn0h.5)."
  [ctx sym]
  (if (= :harness (image-of ctx))
    (repl/doc-sym sym (session-of ctx))
    ;; The tool pr-strs :arglists itself, so this hands back the DATA rather
    ;; than a string of it — otherwise the model reads a quoted quote.
    (or (read-value ctx (str "(if-let [v (resolve (symbol " (pr-str (str sym)) "))]"
                             " (let [m (meta v)]"
                             "   {:name (str (:ns m) \"/\" (:name m)) :arglists (:arglists m)"
                             "    :doc (str (:doc m))})"
                             " {:not-found true})"))
        {:not-found true})))

(defn complete-for
  "Names containing `prefix`: qualified ones across every loaded namespace,
  and the session's own defs bare, as they are called from it."
  [ctx prefix]
  (if (= :harness (image-of ctx))
    (repl/complete prefix (session-of ctx))
    (or (read-value ctx (str "(->> (concat (map str (keys (ns-interns *ns*)))"
                             "   (mapcat (fn [n] (map #(str (ns-name n) \"/\" %)"
                             "                        (keys (ns-publics n)))) (all-ns)))"
                             " (filter #(clojure.string/includes? % " (pr-str (str prefix)) "))"
                             " distinct sort (take 50) vec)"))
        [])))
