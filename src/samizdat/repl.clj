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

(ns samizdat.repl
  "The in-process eval seam: the agent develops REPL-first against the LIVE
  harness image.

  This is the homoiconic substrate the plan turns on. Because the agent runs
  inside a jolt image, `eval-code` evaluates into that same image — it can
  define functions, call them, require and exercise samizdat's own namespaces,
  and inspect the running system, exactly as a developer at a REPL does. A
  value, its printed output, and any exception all come back as data; nothing
  thrown escapes.

  Each session is its own namespace, so defs accumulate across calls within a
  run (define, then use) while staying isolated from other sessions. This is
  arbitrary code execution in the harness process, by design: it is the
  mechanism the mutation protocol (karamazov-ioo.11) will build its
  checkpoint/soak/rollback safety around."
  (:require [samizdat.cancel :as cancel]
            [ebb.core :as ebb]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.host :as host]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [samizdat.prompt :as prompt]
            [samizdat.repl.guard :as guard]))

(defn- project-paths
  "The source paths `root` declares in its own deps.edn: `:paths` plus every
  alias's `:extra-paths`. Falls back to src and test, which is what a Clojure
  project without a deps.edn (or with one this cannot read) almost always
  uses — a guess that is wrong costs a failed require, and no guess at all
  costs the whole REPL-first workflow."
  [root]
  (let [f (io/file root "deps.edn")
        declared (when (.exists f)
                   (try (let [d (edn/read-string (slurp f))]
                          (concat (:paths d)
                                  (mapcat :extra-paths (vals (:aliases d)))))
                        (catch Throwable _ nil)))]
    (distinct (or (seq declared) ["src" "test"]))))

(defn- cwd
  "The process working directory, absolute. What a RELATIVE path inside `eval`
  resolves against — jolt resolves against the real cwd, not `user.dir`, which
  is why setting that property does not move it."
  []
  (str (.getCanonicalFile (io/file "."))))

(defn off-root
  "`{:root :cwd}`, both canonical, when `root` is not the directory the harness
  runs in — so a relative path in a harness `eval` is not the project's — or
  nil."
  [root]
  (let [root* (str (.getCanonicalFile (io/file root)))
        here (cwd)]
    (when (not= root* here)
      {:root root* :cwd here})))

(defn warn-if-not-cwd!
  "Say so, loudly, when the run root is not the directory the harness is
  running in. Returns the mismatch as data, or nil.

  The source roots above make the project REQUIRABLE from `eval`; they do
  nothing about relative FILE paths, and jolt offers no chdir to fix that
  with. So an `eval` of `(slurp \"README.md\")` reads the harness's own README
  and answers plausibly — observed live, turn 4, where the agent read
  samizdat's README, listed samizdat's directory at turn 5, and went on to
  slurp samizdat's deps.edn believing all three were the project's.

  A wrong answer that looks right is the worst failure this seam has, and the
  fix is not in code: run the harness FROM the project root. The warning names
  both directories so the operator can, because nothing else in the process
  will ever notice. A supervisor's eval that hands a file call a relative
  path is refused while this holds (route/eval-for, karamazov-jk2y): the
  warning reached only the server log, where no branch reads it."
  [root]
  (when-let [{:keys [root cwd] :as off} (off-root root)]
    (log/warn "run root is not the working directory —"
              "`eval` requires from" root "but resolves relative FILE paths against" cwd
              "— start the harness from the project root, or use absolute paths in eval")
    off))

(defn declared-roots
  "The absolute source roots `root` declares, resolved.

  A declared path may be ABSOLUTE — a project that puts a library outside its
  own tree on the classpath, which is how fps-game reaches its raylib FFI
  binding. `(io/file parent child)` joins an absolute child ONTO the parent
  instead of replacing it, so resolving those the same way as relative ones
  produced `/…/fps-game//Users/…/raylib-jolt-examples/src`: a directory that
  does not exist. The require then failed inside `eval` while succeeding from
  a shell in the same directory, and the run spent its budget reading that
  library's source with `shell` because nothing else could see it (628ffd2e)."
  [root]
  (mapv (fn [p]
          (let [f (io/file p)]
            (str (if (.isAbsolute f) f (io/file root p)))))
        (project-paths root)))

(defn ensure-project-roots!
  "Make the project at `root` loadable from `eval`, and return the paths added.

  THE SYSTEM PROMPT PROMISES THIS AND THE HARNESS DID NOT DELIVER IT. The
  prompt's whole first section is REPL-first — *try a form, inspect what it
  returns, iterate BEFORE writing it to a file; require and exercise the
  project's own namespaces here too* — and `eval` runs in the harness image,
  whose source roots are samizdat's. A run targeting another project could
  write `src/todo/core.clj` and then not require it: observed live, turn 12,
  `Could not locate todo/core.jolt (or .clj/.cljc) on the source roots`. Every
  word of that instruction was unreachable for the case the harness exists to
  serve.

  ADDITIVE, never a replacement: samizdat's own roots stay, because the agent
  introspecting the harness it runs in is the other half of the job. Idempotent
  — a second run against the same root changes nothing.

  A stale root from a previous run is left in place deliberately. Removing it
  would unload nothing (namespaces are already interned) while breaking a
  resume that still refers to it, and an extra directory on the search path
  costs a stat."
  [root]
  (when root
    (warn-if-not-cwd! root)
    (let [added (declared-roots root)
          current (vec (host/source-roots))
          missing (remove (set current) added)]
      (when (seq missing)
        (host/set-source-roots! (into current missing))
        ;; The HARNESS image's search path: the supervisor evaluates here.
        ;; Every other role evaluates in the project image, which route adds
        ;; the same roots to when it starts one (karamazov-1b37).
        (log/info "the harness image's eval can now reach" (str/join ", " missing)))
      (vec missing))))

(def ^:private session-counter (atom 0))

(defn new-session
  "A fresh eval session: a unique namespace that `clojure.core` is referred
  into, so ordinary forms work and defs persist across calls to it."
  []
  (let [ns-sym (symbol (str "samizdat.repl.session-" (swap! session-counter inc)))
        ns* (create-ns ns-sym)]
    (binding [*ns* ns*]
      (refer-clojure))
    ns-sym))

(defn fork-session
  "A new session carrying everything `parent` has defined.

  A forked branch inherits its parent's CONVERSATION (state/fork-branch), so
  it inherits a transcript in which those defs were made — and a child that
  can read `(defn helper …)` in its own history but cannot call it is being
  shown a lie about its own state. Copying the parent's interned vars is what
  makes the inherited transcript true.

  Copied, not shared: the two branches are competing approaches and a def one
  makes after the fork must not appear in the other. `refer-clojure` gives the
  child core; only the parent's OWN interns come across, which is why this
  walks `ns-interns` rather than `ns-map`.

  A missing or already-dropped parent yields a plain new session, because a
  fork must never fail on the state of the thing it is forking from."
  [parent]
  (let [child (new-session)]
    (when-let [pns (and parent (find-ns parent))]
      (let [cns (find-ns child)]
        ;; The var's metadata rides on the symbol: a macro interned as its
        ;; bare value lost :macro and the child called it as a function. An
        ;; unbound var (a `declare` never filled) has no value to copy.
        (doseq [[sym v] (ns-interns pns)]
          (when (and (var? v) (bound? v))
            (intern cns (with-meta sym (meta v)) @v)))))
    child))

(defn close-session
  "Drop a session's namespace. Each run gets a fresh namespace so defs
  accumulate across its turns; without this a long-lived serve process kept
  one namespace (plus everything the agent defined in it) per run, forever
  (provenance CR1-6). Idempotent on an unknown or already-removed name."
  [session]
  (when (find-ns session)
    (remove-ns session))
  nil)

(defn top-level-forms
  "The top-level forms of `code` as `[{:text :line}]`, in order, 1-based
  lines. Comments and `#_` evaluate nothing, so they are not forms here.

  THE UNIT AN EVAL FAILURE IS REPORTED AGAINST, in both images. A batch that
  failed with a bare message left the model unable to tell which form broke
  or whether the ones before it ran — they did, defs and effects included —
  and it re-ran the whole batch (arrodes reports form, phase and line;
  karamazov-xn0h.3). Throws on text that does not parse; callers hold vetted
  source."
  [code]
  (->> (n/children (p/parse-string-all (str code)))
       (remove #(or (n/whitespace-or-comment? %) (= :uneval (n/tag %))))
       (mapv (fn [nd] {:text (n/string nd) :line (:row (meta nd))}))))

(defn refusal
  "Why `forms` (read forms) must not run in a process the caller cares about,
  as the ex-info to throw, or nil. `:image` is :harness or :project — the
  same call ends a different process, and the sentence says which."
  [forms image]
  (let [ctx {:harness (= :harness image) :project (= :project image)}]
    (cond
      (guard/terminating-form? forms)
      (ex-info (prompt/render "eval-terminates-process"
                              (assoc ctx
                                     :calls (str/join " and " (guard/offending forms))
                                     :rule (name :process-exit)))
               {:samizdat/refused :process-exit})

      ;; The route the symbol check cannot see: the exit is inside the
      ;; callee, one file away, in code the agent wrote itself.
      (guard/entry-point-call? forms)
      (ex-info (prompt/render "eval-calls-main"
                              (assoc ctx
                                     :call (str/join " and " (guard/main-calls forms))
                                     :rule (name :entry-point-call)))
               {:samizdat/refused :entry-point}))))

(defn readable-ahead
  "Every form of `forms` (from `top-level-forms`) that reads now, in order.

  The refusal checks the batch BEFORE THE FIRST FORM RUNS (karamazov-1xx): a
  partial eval that then kills the process is the worst of both. A form that
  only reads once an earlier one has run — `::alias/kw` after the require
  that makes the alias — is skipped here and checked at its own turn."
  [forms]
  (keep #(try (read-string (:text %)) (catch Throwable _ nil)) forms))

(def session-defs-form
  "A function of `[ns-sym limit doc-chars]` listing a session's own defs —
  `{:defs [{:name :kind :macro :arglists :doc :type}] :total n}` — as a FORM,
  so the harness image evaluates it and the project image is sent it printed:
  one definition for both (karamazov-xn0h.1, arrodes' `workspace`). Values are
  named by type, never printed or realised: a def can hold a lazy sequence or
  an open resource."
  '(fn [ns-sym limit doc-chars]
     (require 'clojure.string)
     (when-let [n (find-ns ns-sym)]
       (let [vs (sort-by (comp str key) (ns-interns n))]
         {:total (count vs)
          :defs (mapv (fn [[s v]]
                        (let [m (meta v)
                              x (when (bound? v) @v)
                              line (some-> (:doc m) clojure.string/split-lines first
                                           clojure.string/trim not-empty)]
                          (cond-> {:name (str s)
                                   :kind (cond (:macro m) "macro" (fn? x) "fn" :else "value")}
                            (:macro m) (assoc :macro true)
                            (:arglists m) (assoc :arglists (mapv pr-str (:arglists m)))
                            line (assoc :doc (if (> (count line) doc-chars)
                                               (str (subs line 0 doc-chars) "…")
                                               line))
                            (not (or (:macro m) (fn? x)))
                            (assoc :type (if (nil? x) "nil" (str (type x)))))))
                      (take limit vs))}))))

(def ^:private session-defs-fn (delay (eval session-defs-form)))

(defn session-defs
  "`session`'s own defs in this (the harness) image, as session-defs-form
  describes, or nil when the session has no namespace here."
  [session limit doc-chars]
  (when session (@session-defs-fn session limit doc-chars)))

(def ^:private default-session (delay (new-session)))

(def default-eval-timeout-ms
  "Wall-clock bound on one `eval`, unless the caller asks for more. The agent
  runs code in the same image the harness runs in, on a thread the harness waits
  on — an infinite loop or a runaway computation (a live one pinned a core with
  no bound) would otherwise hang the whole harness. The agent can pass a larger
  timeout when a call genuinely needs it."
  10000)

(defn eval-code
  "Evaluate `code` (a string of one or more Clojure forms) in the session's
  namespace, in the live harness image. Returns:
    {:ok true  :value \"<pr-str of the last form's value>\" :out \"<stdout>\"}
    {:ok false :error \"<message>\" :out \"<stdout>\" :error-type \"<class>\"}
  Reads and evaluates form by form so a leading `(require …)` takes effect
  before the forms that depend on it, matching REPL semantics.

  Bounded by `timeout-ms` (default `default-eval-timeout-ms`): the code runs on
  a separate thread the caller waits on with a deadline, so a runaway eval times
  out with :error-type \"timeout\" instead of hanging the harness. The abandoned
  computation is best-effort cancelled; a tight CPU loop may not honour it, but
  control returns to the harness regardless."
  ([code] (eval-code code @default-session nil))
  ([code session] (eval-code code session nil))
  ([code session timeout-ms]
   (let [ns* (the-ns (or session @default-session))
         out (java.io.StringWriter.)
         timeout (or timeout-ms default-eval-timeout-ms)
         ;; Run on its own thread and wait with a deadline. The eval catches its
         ;; own throwable and returns a result map, so deref yields a map or the
         ;; ::timeout sentinel — never a re-thrown exception.
         ;; READER FEATURES ARE PROCESS-WIDE AND THE AGENT SETS THEM. The
         ;; documented way to load a library that reads its :clj branches is
         ;; set, require, set back — and when the require throws, as it will
         ;; while the agent is still working out which features it needs, the
         ;; set-back never runs. Live: one such eval dropped "bb" from the
         ;; image, `honey.sql` failed on `::wrapper` from that turn onward,
         ;; every later attempt failed the same way, and the run reflected the
         ;; damage into long-term memory as a fact about the library. The
         ;; corruption outlives the branch, the run, and — through the
         ;; memories it explains — the process.
         ;;
         ;; So the seam restores them, the way it already scopes *ns* and
         ;; *out*. Features matter at READ time, so a namespace loaded under
         ;; the agent's features stays loaded after they are put back; what
         ;; does not survive is the ability to break every later read.
         features (__reader-features)
         ;; The form running now, so a failure can say which one it was and
         ;; how many before it completed (karamazov-xn0h.3).
         at (volatile! nil)
         body (fn []
               (try
                 (let [value (binding [*ns* ns* *out* out]
                               (let [forms (top-level-forms code)
                                     n (count forms)]
                                 ;; REFUSED BEFORE THE FIRST FORM RUNS, not per
                                 ;; form: the forms share a process, so form 1
                                 ;; having already run does not make form 2's
                                 ;; exit survivable — and a partial eval that
                                 ;; then kills the server is the worst of both
                                 ;; (karamazov-1xx).
                                 (when-let [e (refusal (readable-ahead forms) :harness)]
                                   (throw e))
                                 ;; ONE FORM AT A TIME, each read after the one
                                 ;; before it ran, as a REPL reads: an alias a
                                 ;; require just made is readable in the next
                                 ;; form. A form unreadable in the pass above is
                                 ;; checked here, at its own turn.
                                 (reduce (fn [_ [i {:keys [text line]}]]
                                           (vreset! at {:index (inc i) :count n
                                                        :line line :completed i})
                                           (let [form (read-string text)]
                                             (when-let [e (refusal [form] :harness)]
                                               (throw e))
                                             (eval form)))
                                         nil
                                         (map-indexed vector forms))))]
                   {:ok true :value (pr-str value) :out (str out)})
                 (catch Throwable e
                   {:ok false
                    ;; EVERY RUNG, because each of the ones above it has been
                    ;; observed returning nothing usable.
                    ;;
                    ;; not-empty, not `or`: `or` only falls through on NIL,
                    ;; and an exception carrying an EMPTY message handed the
                    ;; model the whole of "Eval error: " and nothing else
                    ;; (run bd56a286, twice).
                    ;;
                    ;; Then ex-data, which is where Jolt puts what it knows —
                    ;; {:jolt/error {:type :unresolved-symbol :symbol … :suggestions […]}}
                    ;; is strictly more actionable than any prose rendering of
                    ;; it, and it survives when the message does not.
                    ;;
                    ;; `(str e)` LAST, not first, because a Jolt condition is
                    ;; not a JVM Throwable and prints as the literal
                    ;; `#object[:object]` — an error the model cannot read,
                    ;; which is worse than a crash because it looks like an
                    ;; answer. Four of those cost run f2014821 ten turns and
                    ;; took a supervisor pass to unstick (karamazov-60c).
                    :error (or (not-empty (str (ex-message e)))
                               (some-> (ex-data e) not-empty pr-str)
                               (not-empty (str (type e)))
                               (str e))
                    :error-type (str (type e))
                    ;; WHERE it ran. An eval failure is about the live image,
                    ;; which may hold half-loaded state for a namespace whose
                    ;; file on disk is perfectly correct — and nothing said so,
                    ;; so a branch reads it as a defect in its own code and
                    ;; goes looking in the file.
                    :where (str ns*)
                    :form @at
                    :out (str out)})
                 (finally
                   (try (__reader-features-set! features)
                        (catch Throwable _ nil)))))
         ;; One deadline idiom (RFC-013): the eval runs on a thread the
         ;; caller parks on; at the budget it is cancelled — the interrupt
         ;; reaches a sleep or a wait, not a tight loop — and detached, so
         ;; control returns to the harness regardless.
         [tag result] (cancel/with-deadline (ebb/via ebb/blk (body)) timeout)]
     (case tag
       :timeout {:ok false
                 :error (str "eval timed out after " timeout "ms — the code ran too long "
                             "(an infinite loop or a heavy computation?). If it genuinely "
                             "needs more time, pass a larger :timeout-ms.")
                 :error-type "timeout"
                 :out (str out)}
       :ok result
       :err (throw result)))))

(defn- resolve-sym
  "Resolve a symbol string to its var, or nil: qualified ones by namespace,
  bare ones in `session` when given (so a session's own def is found) and
  clojure.core otherwise."
  [sym-str session]
  (try
    (let [s (symbol sym-str)]
      (if (namespace s)
        (when-let [ns* (find-ns (symbol (namespace s)))]
          (ns-resolve ns* (symbol (name s))))
        (or (some-> session find-ns (ns-resolve s))
            (ns-resolve 'clojure.core s))))
    (catch Throwable _ nil)))

(defn doc-sym
  "The docstring and arglists of a symbol, for the agent inspecting code.
  jolt strips core-var metadata, so this is most useful on the project's own
  vars (which carry their docstrings). Returns {:not-found true} when unknown.
  With `session`, a bare name resolves there first."
  ([sym-str] (doc-sym sym-str nil))
  ([sym-str session]
   (if-let [v (resolve-sym sym-str session)]
     (let [m (meta v)]
       {:name (str (:ns m) "/" (:name m))
        :arglists (:arglists m)
        :doc (or (:doc m) "(no docstring — jolt strips core-var metadata)")})
     {:not-found true :symbol sym-str})))

(defn complete
  "Public symbols whose name starts with `prefix`. A qualified prefix
  (`samizdat.lisp/b`) completes within that namespace; a bare prefix
  (`redu`) completes across clojure.core and, with `session`, the session's
  own defs. Returns a sorted vector of strings."
  ([prefix] (complete prefix nil))
  ([prefix session]
   (let [p (str prefix)]
     (if-let [slash (str/index-of p "/")]
       (let [ns-part (subs p 0 slash)
             name-part (subs p (inc slash))]
         (if-let [ns* (find-ns (symbol ns-part))]
           (->> (ns-publics ns*)
                keys
                (map name)
                (filter #(str/starts-with? % name-part))
                (map #(str ns-part "/" %))
                sort vec)
           []))
       (->> (concat (keys (ns-publics 'clojure.core))
                    (some-> session find-ns ns-interns keys))
            (map name)
            (filter #(str/starts-with? % p))
            distinct sort vec)))))
