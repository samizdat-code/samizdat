;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.skills
  "The skill tool: load an instruction bundle on demand. `skill list` shows the
  bounded catalogue; `skill load {name}` pulls one skill's full text into
  context — so a guide (like structuring the loop with mycelium) costs context
  only when the agent reaches for it, not every turn. A DIRECTORY skill (an
  Agent Skills SKILL.md) can bundle further files, which `skill read {name}
  {path}` reads relative to its root (karamazov-k7gl.4)."
  (:require [clojure.string :as str]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]))

(def ^:private usage
  "Actions: list (the catalogue), load {name} (a skill's full guidance), read
  {name} {path} (a file bundled with a directory skill).")

(defn- note [part m] (str/trim (prompt/render "skill-tool" (assoc m :part part))))

(defn- listing
  "The catalogue as the model reads it: each skill's trigger, the tools a
  skill says it wants (surfaced, not enforced), and — when there are any —
  which skills hide another of the same name and which have problems."
  [dirs]
  (let [{:keys [skills shadowed diagnostics]} (skills/registry dirs)
        cat (skills/catalog dirs)]
    (if (empty? cat)
      "No skills available."
      (str (note "catalogue" {}) "\n"
           (str/join "\n" (for [{:keys [name description]} cat
                                :let [tools (get-in skills [name :allowed-tools])]]
                            (str "  " name " — " description
                                 (when (seq tools)
                                   (str " " (note "tools" {:tools (str/join " " tools)}))))))
           (when (seq shadowed)
             (str "\n" (note "shadowed"
                             {:shadowed (str/join "; " (for [{:keys [name path shadows]} shadowed]
                                                         (str name " (" path " over " shadows ")")))})))
           (when (seq diagnostics)
             (str "\n" (note "problems"
                             {:problems (str/join "; " (for [{:keys [name problem detail]} diagnostics]
                                                         (str name ": " (clojure.core/name problem)
                                                              " — " detail)))})))))))

(defmethod base/run-tool "skill" [{:keys [branch root] :as ctx}]
  ;; Project skills resolve under the RUN's root, not the harness's cwd — a
  ;; run working on another checkout reads THAT project's .samizdat/skills
  ;; (karamazov-blt.33). The shipped skills come off the classpath either way.
  (let [action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)
        dirs (if root (skills/project-dirs root) (skills/default-dirs))
        name (some-> (base/arg ctx :name) str str/trim not-empty)]
    (case action
      nil
      (base/malformed branch (str "`skill` needs an `action`. " usage))

      "list"
      (base/ok branch (listing dirs))

      "load"
      (cond
        ;; base/missing takes CTX (it reads :tool-name for the skeleton) and
        ;; yields a complaint STRING — wrap it in malformed. Returned raw it
        ;; replaced the whole result map and tool-step threaded a nil branch
        ;; into state/record-outcome (provenance CR1-1).
        (nil? name) (base/malformed branch (base/missing ctx :name))
        :else
        (if-let [{:keys [body root]} (skills/skill-entry dirs name)]
          ;; Its root rides along, so a skill that says "see scripts/x.sh"
          ;; can be followed: with `skill read`, or read_file on the path.
          (base/ok branch (cond-> (str body)
                            root (str "\n\n---\n" (note "root" {:root root :name name}))))
          (base/malformed branch (str "No skill named '" name "'. " usage))))

      "read"
      (let [path (some-> (base/arg ctx :path) str str/trim not-empty)]
        (if (or (nil? name) (nil? path))
          (base/malformed branch (base/missing ctx :name :path))
          (let [{:keys [content error]} (skills/read-skill-file dirs name path)]
            (if error
              (base/malformed branch (note "read-error" {:error (clojure.core/name error)
                                                         :name name :path path}))
              (base/ok branch content)))))

      (base/malformed branch (str "Unknown skill action `" action "`. " usage)))))
