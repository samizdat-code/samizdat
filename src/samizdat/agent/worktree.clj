;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.worktree
  "A git worktree per parallel owner (karamazov-05z0.10, after autolith's
  task worktrees).

  Owners fanned out over ONE tree negotiate every file through the mailbox
  and the shared-tree block, and RFC-011 made the board serial because that
  negotiation cost more than the parallelism bought. A worktree gives each
  owner its own checkout of the run's baseline: it works alone, its change
  comes back as a patch, and the parent applies the patches in order,
  stopping at the first that does not apply cleanly.

  Mechanism only — fixed git subcommands under the scrubbed environment, as
  samizdat.agent.gitdiff runs them. Whether to isolate, the order patches go
  in, and what a conflict means for the owner are the cell's and gates.edn
  :isolation's. Everything fails soft: nil, never a throw."
  (:require [clojure.string :as str]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]))

(defn- git
  "{:exit :out :err} of `git -C root args…`, or nil when git did not finish."
  [root & args]
  (let [r (apply proc/run {:timeout-ms 60000 :env (secrets/scrubbed-process-env)}
                 "git" "-C" (str root) args)]
    (when-not (:timeout r) r)))

(defn- ok-out [r] (when (and r (zero? (or (:exit r) 1))) (str (:out r))))

(defn dir-for
  "Where owner `owner` of run `run-id` gets its checkout: outside the
  project, so the project's own grep and file walks never see it."
  [run-id owner]
  (str (System/getProperty "java.io.tmpdir") "/samizdat-worktrees/"
       (str/replace (str run-id) #"[^A-Za-z0-9_.-]" "_") "/"
       (str/replace (str owner) #"[^A-Za-z0-9_.-]" "_")))

(defn create!
  "A detached worktree of `root` at `baseline` in `dir`. Returns `dir`, or
  nil when git refuses."
  [root baseline dir]
  (when (and root baseline)
    (some-> (git root "worktree" "add" "--detach" "--force" (str dir) (str baseline))
            ok-out
            (do (str dir)))))

(defn patch
  "What the owner in worktree `dir` changed since `baseline`, as a binary
  patch, untracked files included. \"\" when it changed nothing; nil when
  git refuses."
  [dir baseline]
  (when (ok-out (git dir "add" "-A"))
    ;; --no-ext-diff: a person's gitconfig may route diffs through a
    ;; viewer (difftastic), whose output is not a patch.
    (ok-out (git dir "diff" "--cached" "--binary" "--no-ext-diff" "--no-color" (str baseline)))))

(defn- touched
  "The paths a patch names, as git reads them."
  [root patch-file]
  (->> (str/split-lines (str (ok-out (git root "apply" "--numstat" (str patch-file)))))
       (keep #(last (str/split % #"\t")))
       (remove str/blank?)
       vec))

(defn integrate!
  "Apply `patch-text` to `root`'s working tree. {:applied [paths]} when it
  went in, {:conflict [paths] :why git's words} when it would not — checked
  first, so a patch that does not apply leaves the tree as it was."
  [root patch-text]
  (if (str/blank? (str patch-text))
    {:applied []}
    (let [f (java.io.File/createTempFile "samizdat-owner" ".patch")]
      (try
        (spit f patch-text)
        (let [paths (touched root (.getPath f))
              check (git root "apply" "--check" "--binary" (.getPath f))]
          (if (and check (zero? (or (:exit check) 1)))
            (let [r (git root "apply" "--binary" (.getPath f))]
              (if (ok-out r)
                {:applied paths}
                {:conflict paths :why (str/trim (str (:err r)))}))
            {:conflict paths :why (str/trim (str (:err check)))}))
        (finally (.delete f))))))

(defn remove!
  "Remove the worktree at `dir` and git's record of it."
  [root dir]
  (git root "worktree" "remove" "--force" (str dir))
  (git root "worktree" "prune")
  nil)

;; Held while a baseline is taken and while a patch goes in, so an owner
;; never starts from a tree another owner's patch is half-way into.
(defonce ^:private tree-lock (Object.))

(defn isolated
  "Run `run!` — (fn [dir] -> result) — for `owner` of `run-id` in its own
  worktree of `root`, taken from the tree as it stands now. When `land?` of
  the result holds, its change goes into `root`; either way the worktree is
  removed. The result comes back with :worktree {:applied paths} |
  {:conflict paths :why} | {:discarded true} | {:no-patch true}.

  When no worktree can be made — not a git tree, git refused — `run!` is
  called with nil, which a caller reads as \"work in place\"."
  [root run-id owner run! land?]
  (let [dir (dir-for run-id owner)
        base (locking tree-lock (gitdiff/baseline root))
        made (when base (create! root base dir))]
    (if-not made
      (run! nil)
      (try
        (let [r (run! made)]
          (assoc r :worktree
                 (if (land? r)
                   (locking tree-lock
                     (if-let [p (patch made base)]
                       (integrate! root p)
                       {:no-patch true}))
                   {:discarded true})))
        (finally (remove! root made))))))
