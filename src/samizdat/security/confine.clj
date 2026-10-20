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

(ns samizdat.security.confine
  "WHICH paths and ports the agent's processes are confined to — the decisions
  samizdat.security.sandbox deliberately does not make.

  IN src/ AND NOT IN gates.edn, for the reason `policy/protected-paths` and
  `route/sandbox-spec` give: this is the list of places the agent may not
  reach, and a list the agent can edit is not a list. An operator widens it in
  config.edn (`:shell :writable`), which no tool the agent holds can write.

  The shell tool's confinement lives here (karamazov-3vu1.5). The shell ran
  unconfined while its allow table held heads that run code the agent wrote
  — `make`, `just`, `jolt -e`, `cargo run` — so every confinement the eval
  image has was one Makefile away."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.fs :as fs]
            [samizdat.security.sandbox :as sandbox]
            [samizdat.util :as util]))

(defn secret-regions
  "Where the operator's credentials live, for a user whose home is `home`,
  plus the harness's own checkout at `harness-root`. Unreadable from the eval
  image and from the shell.

  Not /etc: the image denies it, and the shell cannot — name resolution and
  TLS verification read it. `route/sandbox-spec` adds it for the image."
  [home harness-root]
  (vec (remove nil?
               (concat
                (when home
                  (map #(str home "/" %)
                       [".ssh" ".aws" ".gnupg" ".config/gh" ".claude"
                        ".samizdat-secrets"
                        ;; The machine-wide config: provider keys, the roles
                        ;; map. Readable from both until karamazov-3vu1.1.
                        ".config/samizdat"]))
                ;; THE HARNESS'S OWN CHECKOUT. Harmless when the harness IS the
                ;; project: both backends re-allow the project root after the
                ;; denies, so self-hosting reads itself fine.
                [harness-root]))))

(defn harness-caches
  "The trees under `home` the HARNESS loads code from: jolt's gitlibs, AOT
  cache and build caches, the Maven repository its jars come from (selmer,
  malli, rewrite-clj and the rest), tools.deps' ~/.gitlibs, which jolt also
  resolves a git dep from, and ~/.clojure, whose deps.edn every jolt start
  merges in. Readable from a confined
  process, never writable (karamazov-1gbh): a shell that could edit a
  dependency's source, swap a jar or plant a compiled fasl there would have
  its code run by the next harness start, outside every sandbox. Confined
  processes build in `confined-home` instead."
  [home]
  (when home
    (mapv #(str home "/" %) [".jolt" ".m2" ".gitlibs" ".clojure"])))

(defn build-caches
  "The trees a build writes under `home` — dependency caches and toolchain
  state. Writable from the shell, because `cargo build`, `go test` and `npm`
  fail without them; not $HOME as a whole, and not `harness-caches`. A JVM
  build that needs ~/.m2 gets it only from the operator's `:shell
  :writable`, which hands the shell the harness's jars."
  [home]
  (when home
    (mapv #(str home "/" %)
          [".cache" ".cpcache" ".lein"
           ".cargo" ".rustup" "go" ".npm" ".yarn" ".pnpm-store" ".gradle"
           ".local/share" "Library/Caches"])))

(defonce ^:private confined-base
  ;; Where every project's confined caches go, when not under ~/.cache. The
  ;; test runner points it at a temp dir: each test project is a fresh temp
  ;; root, and each would otherwise leave its fetched dependencies behind.
  (atom nil))

(defn set-confined-base!
  "Put every project's confined caches under `dir` (nil: ~/.cache)."
  [dir]
  (reset! confined-base dir))

(defn confined-home
  "Where the confined processes working on `root` keep jolt's caches and a
  Maven repository: under ~/.cache, one directory per project, so a fetch or
  a compile in the shell lands somewhere only confined processes read."
  [home root]
  (let [canon (str (.getCanonicalFile (io/file (str root))))
        tag (-> (.getName (io/file canon)) (str/replace #"[^A-Za-z0-9._-]" "_"))]
    ;; jolt's `hash` is stable across processes (infer/request-digest), so
    ;; the next run of the same project finds the same directory.
    (str (or @confined-base (str home "/.cache/samizdat/confined")) "/" tag "-"
         (str/replace (str (hash canon)) "-" "n"))))

(defn confined-env
  "The environment that points jolt's caches at `confined-home`."
  [home root]
  (let [d (confined-home home root)]
    {"JOLT_CACHE_DIR" (str d "/aot-cache")
     "JOLT_GITLIBS_DIR" (str d "/gitlibs")
     "JOLT_MAVEN_REPOSITORY" (str d "/m2")
     "JOLT_BUILD_CACHE_DIR" (str d "/build-cache")
     "JOLT_RUNTIME_CACHE_DIR" (str d "/runtime-cache")
     "GITLIBS" (str d "/gitlibs")}))

(defn protected
  "The trees under the project root nothing the agent spawns may write: its
  .samizdat, which holds the cells the harness runs, the role surfaces and
  refusal rules that confine the agent, and the database with the grants
  table (karamazov-3vu1.2). The supervisor changes the workflow through its
  own tools, which run in the harness."
  [root]
  [(str root "/.samizdat")])

(defn shell-spec
  "The confinement for one shell command, as samizdat.security.sandbox reads
  it. Pure.

  `:scratch` is the shell's own temp dir (its TMPDIR); `:ports` the loopback
  ports the harness listens on (samizdat.security.listen); `:settings` the
  operator's `config/shell-settings`."
  [{:keys [home root harness-root scratch ports settings]}]
  {:project-root root
   ;; /tmp as well as the shell's own scratch: tools write there by name.
   ;; The profile is NOT under either — see `shell-command`.
   :scratch-paths (vec (remove nil? [scratch "/tmp"]))
   :writable (vec (concat (build-caches home)
                          (when home [(confined-home home root)])
                          (:writable settings)))
   :deny-read (secret-regions home harness-root)
   :deny-ports (vec ports)
   :protect (protected root)})

(defn unconfined-reason
  "Why the shell setting `setting` leaves the shell unconfined on `os-name` —
  `:configured-off`, `:no-bwrap` (Linux without bubblewrap) or `:no-backend` —
  or nil when it does not."
  [setting os-name bwrap?]
  (when (= :none (sandbox/backend-for setting os-name bwrap?))
    (cond (= :none setting) :configured-off
          (str/starts-with? (str os-name) "Linux") :no-bwrap
          :else :no-backend)))

(defonce ^:private warned (atom #{}))

(defn backend
  "The backend for the shell setting `setting` on this host. Says once per
  reason when that is no confinement at all — it used to be silent
  (karamazov-3vu1.6)."
  [setting]
  (let [os (System/getProperty "os.name")
        bwrap? (some? (fs/which "bwrap"))]
    (when-let [why (unconfined-reason setting os bwrap?)]
      (when-not (contains? @warned why)
        (swap! warned conj why)
        (log/warn "the shell tool runs UNSANDBOXED:"
                  (case why
                    :configured-off "config.edn :shell :sandbox is :none"
                    :no-bwrap "no bubblewrap on this Linux host; install it"
                    (str "no sandbox backend on " os)))))
    (sandbox/backend-for setting os bwrap?)))

(defonce ^:private dirs
  ;; root -> {:scratch :profile-dir}. The scratch is the shell's; the profile
  ;; dir is a SIBLING temp dir, not inside it and not inside anything writable.
  (atom {}))

(defn- dirs-for! [root]
  (or (get @dirs root)
      (get (swap! dirs (fn [m] (if (get m root)
                                 m
                                 (assoc m root {:scratch (str (fs/create-temp-dir))
                                                :profile-dir (str (fs/create-temp-dir))}))))
           root)))

(defn shell-command
  "The argv, environment and working directory to run `command` in `root`
  under `backend`, as `{:argv :env :dir :spec :profile}`.

  `:dir` is the root: the child STARTS there. Started in the harness's own
  directory — a denied read region — a sandboxed bash could not find its
  working directory, and every command printed two getcwd errors before the
  `cd` moved it.

  `:env` is the child's complete environment (already scrubbed by the
  caller); under a sandbox it gains TMPDIR pointing at the shell's own scratch.
  THE SYSTEM TEMP DIR IS NOT WRITABLE, and that is the point: the profile is
  written into a temp dir of its own, and a shell that could write where the
  profile lives could rewrite the confinement of its next command.

  `:ports`, `:settings` and `:home` default to the process's own, and
  `:harness-root` to the checkout its code was loaded from — none in a built
  binary (util/harness-checkout); tests pass them."
  [{:keys [backend root command env ports settings home harness-root]}]
  (let [cd-cmd (str "cd " (util/sh-quote (or root ".")) " && " command)
        bash ["bash" "-c" cd-cmd]]
    (if (or (nil? backend) (= :none backend))
      {:argv bash :env env :dir root}
      (let [{:keys [scratch profile-dir]} (dirs-for! (str root))
            spec (shell-spec {:home (or home (System/getenv "HOME"))
                              :root (str root)
                              :harness-root (or harness-root (util/harness-checkout))
                              :scratch scratch
                              :ports ports
                              :settings settings})
            spec (merge spec (sandbox/deny-read-kinds (:deny-read spec)))
            profile (str (io/file profile-dir "shell.sb"))
            _ (when (= :seatbelt backend)
                (let [text (sandbox/seatbelt-shell-profile spec)]
                  (when-not (= text (try (slurp profile) (catch Throwable _ nil)))
                    (spit profile text))))
            cenv (confined-env (or home (System/getenv "HOME")) root)]
        ;; Made here, by the harness: jolt makes what it fetches into, but a
        ;; tool that writes straight to one would fail on a missing parent.
        (doseq [d (vals cenv)] (try (fs/create-dirs d) (catch Throwable _ nil)))
        {:argv (sandbox/wrap-shell backend {:profile profile :spec spec} bash)
         :env (when env (merge env cenv {"TMPDIR" scratch}))
         :dir root
         :spec spec
         :profile profile}))))
