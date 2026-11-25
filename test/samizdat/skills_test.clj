;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.skills-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.skills]
            [samizdat.layers]))

(deftest the-bundled-mycelium-skill-is-discovered
  (let [cat (skills/catalog)]
    (is (some #(= "mycelium" (:name %)) cat) "mycelium is in the catalogue")
    (is (every? (comp seq :description) cat) "every skill has a description")))

(deftest load-returns-content-and-nil-for-a-miss
  (is (str/includes? (skills/load-skill "mycelium") "defcell"))
  (is (nil? (skills/load-skill "no-such-skill"))))

(deftest the-bundled-clj-discover-skill-is-discoverable
  (let [entry (some #(when (= "clj-discover" (:name %)) %)
                    (skills/catalog ["/no/such/dir"]))
        body (skills/load-skill ["/no/such/dir"] "clj-discover")]
    (is (some? entry) "the bundled skill is enumerated for classpath discovery")
    (is (str/starts-with? (str (:description entry)) "Use when"))
    (is (not (str/includes? (str (:description entry)) "\n")))
    (is (string? body) "the skill loads without a project overlay")))

(deftest the-bundled-clj-replace-skill-is-discoverable
  (let [entry (some #(when (= "clj-replace" (:name %)) %)
                    (skills/catalog ["/no/such/dir"]))
        body (skills/load-skill ["/no/such/dir"] "clj-replace")]
    (is (some? entry) "the bundled skill is enumerated for classpath discovery")
    (is (str/starts-with? (str (:description entry)) "Use when"))
    (is (not (str/includes? (str (:description entry)) "\n")))
    (is (string? body) "the skill loads without a project overlay")))

(deftest the-bundled-clj-refactor-skill-is-discoverable
  (let [entry (some #(when (= "clj-refactor" (:name %)) %)
                    (skills/catalog ["/no/such/dir"]))
        body (skills/load-skill ["/no/such/dir"] "clj-refactor")]
    (is (some? entry) "the bundled skill is enumerated for classpath discovery")
    (is (str/starts-with? (str (:description entry)) "Use when"))
    (is (not (str/includes? (str (:description entry)) "\n")))
    (is (string? body) "the skill loads without a project overlay")))

(deftest the-bundled-clj-debug-skill-is-discoverable
  (let [entry (some #(when (= "clj-debug" (:name %)) %)
                    (skills/catalog ["/no/such/dir"]))
        body (skills/load-skill ["/no/such/dir"] "clj-debug")]
    (is (some? entry) "the bundled skill is enumerated for classpath discovery")
    (is (str/starts-with? (str (:description entry)) "Use when"))
    (is (not (str/includes? (str (:description entry)) "\n")))
    (is (string? body) "the skill loads without a project overlay")))

(deftest the-bundled-clj-surgeon-skill-is-discoverable-and-native-only
  (let [entry (some #(when (= "clj-surgeon" (:name %)) %)
                    (skills/catalog ["/no/such/dir"]))
        body (skills/load-skill ["/no/such/dir"] "clj-surgeon")]
    (is (some? entry) "the bundled skill is enumerated for classpath discovery")
    (is (str/starts-with? (str (:description entry)) "Use for advanced")
        "the description is meaningful, not folded-YAML syntax")
    (is (not (str/includes? (str (:description entry)) "\n"))
        "the catalogue trigger is one line")
    (is (string? body) "the skill loads without a project overlay")
    (doseq [native ["inspect_clojure" "edit_clojure" "apply_clojure_changes"
                    "transform_clojure" "outline" "forms" "match" "xray"
                    ":move" ":fix-declares" ":preview true" "resource"]]
      (is (str/includes? (str body) native)
          (str "the adapted guidance covers " native)))
    (doseq [stale ["mcp" "cclsp" "CLI fallback"
                   "clj-surgeon :op" "restart_server"]]
      (is (not (str/includes? (str/lower-case (str body))
                              (str/lower-case stale)))
          (str "the native skill omits stale route: " stale)))))

(deftest frontmatter-parses-and-drives-the-catalogue
  (let [fm (skills/parse-frontmatter "---\nname: x\ndescription: Use when Y.\n---\n# T\nbody")]
    (is (= "Use when Y." (get-in fm [:meta :description])))
    (is (= "# T\nbody" (:body fm))))
  (let [fm (skills/parse-frontmatter "no frontmatter here\nmore")]
    (is (= {} (:meta fm)))
    (is (str/starts-with? (:body fm) "no frontmatter"))))

(deftest the-catalogue-is-always-renderable-with-triggers
  (let [r (skills/render-catalog)]
    (is (str/includes? r "mycelium"))
    (is (str/includes? r "Use when") "the description is a when-to-use trigger")
    (is (not (str/includes? r "defcell")) "the body is NOT in the always-on catalogue")))

(deftest a-skill-with-no-description-drops-from-the-catalogue-but-stays-loadable
  (is (nil? (#'skills/describe {:meta {} :body "# only a heading"} "h"))
      "no frontmatter description and only headings -> no trigger")
  (is (some? (#'skills/describe {:meta {} :body "a real line"} "h"))))

(deftest the-skill-tool-lists-and-loads
  (let [lst (base/run-tool {:branch {:id "B1"} :tool-name "skill" :args {:action "list"}})]
    (is (str/includes? (:result lst) "mycelium")))
  (let [ld (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                           :args {:action "load" :name "mycelium"}})]
    (is (= :neutral (:category ld)) "loading a skill is neutral bookkeeping")
    (is (str/includes? (:result ld) "manifest")))
  (let [miss (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                             :args {:action "load" :name "nope"}})]
    (is (= :mechanics (:category miss)) "an unknown skill is a malformed call")))

(deftest a-load-without-a-name-is-a-mechanics-complaint-with-a-skeleton
  ;; provenance CR1-1: base/missing was called with `branch` and its
  ;; complaint string returned RAW as the tool result — no :category, no
  ;; :branch — so tool-step threaded a nil branch into state/record-outcome,
  ;; which NPE'd on (update nil :turns-since-progress inc).
  (let [r (base/run-tool {:branch {:id "B1"} :tool-name "skill"
                          :args {:action "load"}})]
    (is (= :mechanics (:category r)) "a missing arg is malformed, not a failure")
    (is (map? (:branch r)) "the branch rides along")
    (is (str/includes? (:result r) "Missing required argument(s): name"))
    (is (str/includes? (:result r) "\"skill\"") "the skeleton names the tool")))

(deftest shipped-skills-resolve-off-the-classpath-from-any-cwd
  ;; karamazov-blt.33: the loader scanned cwd-relative "resources/skills", so
  ;; from any other project (and from a built binary, where the dir does not
  ;; exist on disk) every implementor silently lost the REPL/TDD guidance and
  ;; the catalogue rendered empty — the classpath-has-no-listing trap
  ;; cells/shipped-cells enumerates around, in a fourth place.
  (let [cat (skills/catalog ["/no/such/dir"])]
    (is (some #(= "repl-workflow" (:name %)) cat)
        "the bundled skills survive a foreign cwd"))
  (is (str/includes? (str (skills/load-skill ["/no/such/dir"] "repl-workflow"))
                     "eval")
      "and load by name with no overlay directory present")
  (let [on-disk (->> (file-seq (java.io.File. "resources/skills"))
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
                     (map #(str/replace (.getName ^java.io.File %) #"\.md$" ""))
                     set)]
    (is (= on-disk (set skills/shipped-skills))
        "the enumerated list is pinned against the directory")))

;; --- karamazov-15h -----------------------------------------------------------
;; Discovery scanned `<dir>/*.md` only, so it could not read a skill DIRECTORY
;; holding SKILL.md — the shape Claude Code, pi, opencode, vis and the agents
;; standard all use, and the shape of every skill checked into this very repo.
;; AGENTS.md points the agent at .agents/skills/beads/SKILL.md; its own harness
;; had no way to load it.

(deftest a-skill-directory-holding-SKILL-md-is-discovered
  (let [root (str "/tmp/samizdat-skills-" (random-uuid))
        dir (str root "/.samizdat/skills")]
    (fs/create-dirs (str dir "/nested-one"))
    (fs/create-dirs (str dir "/nested-two"))
    (try
      (spit (str dir "/flat.md")
            "---\nname: flat\ndescription: Use when flat.\n---\nflat body")
      (spit (str dir "/nested-one/SKILL.md")
            "---\nname: nested-one\ndescription: Use when nested.\n---\nnested body")
      ;; no `name:` in the frontmatter — the DIRECTORY name is the fallback
      (spit (str dir "/nested-two/SKILL.md")
            "---\ndescription: Use when unnamed.\n---\nunnamed body")
      (let [found (skills/discover (skills/project-dirs root))]
        (is (contains? found "flat") "the flat form still works")
        (is (contains? found "nested-one") "a SKILL.md directory is found")
        (is (contains? found "nested-two") "and names itself after its directory")
        (is (str/includes? (get-in found ["nested-one" :body]) "nested body"))
        (is (= "Use when unnamed." (get-in found ["nested-two" :description]))))
      (finally (fs/delete-tree root)))))

(deftest the-skill-roots-are-policy-not-a-literal-in-src
  (let [roots (skills/skill-roots)]
    (is (seq roots) "there is a root list, and it comes from resources")
    (is (some #(str/includes? % ".samizdat/skills") roots)
        "samizdat's own root still wins")
    (is (some #(str/includes? % ".agents/skills") roots)
        "the agents-standard root AGENTS.md points at is scanned")))

(deftest this-repos-own-beads-skill-is-reachable
  ;; The regression that motivated the bead: the canonical instruction file
  ;; directs the agent to a skill the harness could not read.
  (let [found (skills/discover (skills/project-dirs "."))]
    (is (contains? found "beads")
        "AGENTS.md names .agents/skills/beads/SKILL.md; it must be loadable")))

;; --- per-person skills -------------------------------------------------------
;; Every root was joined to the project root, so a skill could only live in a
;; project. A `{global}` root resolves to the person's config dir
;; (layers/global-dir), absolute and `~/` roots are kept as they are, and the
;; earliest root wins, so a project's skill still beats the person's.

(deftest roots-resolve-global-home-and-absolute-paths
  (with-redefs [samizdat.layers/global-dir (constantly "/cfg/samizdat")]
    (let [home (System/getenv "HOME")]
      (with-redefs [skills/skill-roots (constantly [".samizdat/skills"
                                                    "{global}/skills"
                                                    "~/my-skills"
                                                    "/abs/skills"])]
        (is (= ["/proj/.samizdat/skills"
                "/cfg/samizdat/skills"
                (str home "/my-skills")
                "/abs/skills"]
               (skills/project-dirs "/proj"))))))
  (with-redefs [samizdat.layers/global-dir (constantly nil)
                skills/skill-roots (constantly [".samizdat/skills" "{global}/skills"])]
    (is (= ["/proj/.samizdat/skills"] (skills/project-dirs "/proj"))
        "no config dir means the global root is skipped, not joined to the project")))

(deftest the-shipped-roots-put-the-persons-config-dir-after-the-project
  (let [roots (skills/skill-roots)
        g (.indexOf ^java.util.List roots "{global}/skills")]
    (is (<= 0 g))
    (is (every? #(not (re-find #"^(\{global\}|~|/)" %)) (take g roots))
        "every root before the person's is a project root")))

(deftest the-earliest-root-wins-and-a-global-skill-is-found
  (let [base (str "/tmp/samizdat-skills-" (random-uuid))
        proj (str base "/proj")
        cfg (str base "/cfg")]
    (fs/create-dirs (str proj "/.samizdat/skills"))
    (fs/create-dirs (str cfg "/skills/personal"))
    (try
      (spit (str proj "/.samizdat/skills/shared.md")
            "---\ndescription: Use when project.\n---\nproject body")
      (spit (str cfg "/skills/shared.md")
            "---\ndescription: Use when person.\n---\nperson body")
      (spit (str cfg "/skills/personal/SKILL.md")
            "---\ndescription: Use when personal.\n---\npersonal body")
      (with-redefs [samizdat.layers/global-dir (constantly cfg)]
        (let [found (skills/discover (skills/project-dirs proj))]
          (is (= "project body" (get-in found ["shared" :body]))
              "the project's copy beats the person's")
          (is (= "personal body" (get-in found ["personal" :body]))
              "a skill only the person has is found from any project")))
      (finally (fs/delete-tree base)))))

;; --- karamazov-k7gl.4: the Agent Skills format ------------------------------
;; A directory holding SKILL.md with YAML frontmatter: `name` (kebab-case, at
;; most 64 chars, the directory's own name), `description`, and optional
;; `allowed-tools`, `license` and `metadata`. The listing says what shadows
;; what and what is wrong with a skill rather than silently dropping it.

(deftest the-frontmatter-reads-the-yaml-subset-skills-use
  (let [fm (skills/parse-frontmatter
            (str "---\n"
                 "name: pdf-tools\n"
                 "description: \"Use when a PDF: \\\"quoted\\\" too.\"\n"
                 "license: 'Apache-2.0'\n"
                 "allowed-tools: Bash(git add:*) Read\n"
                 "metadata:\n"
                 "  author: someone\n"
                 "  version: \"1.0\"\n"
                 "---\n"
                 "body here"))]
    (is (= "Use when a PDF: \"quoted\" too." (get-in fm [:meta :description]))
        "a quoted scalar loses its quotes and its escapes")
    (is (= "Apache-2.0" (get-in fm [:meta :license])))
    (is (= {"author" "someone" "version" "1.0"} (get-in fm [:meta :metadata])))
    (is (= "Bash(git add:*) Read" (get-in fm [:meta :allowed-tools])))
    (is (= "body here" (:body fm)))
    (is (empty? (:errors fm))))
  (testing "block scalars and lists"
    (let [fm (skills/parse-frontmatter
              "---\ndescription: >\n  Use when folded\n  onto one line.\nallowed-tools:\n  - Read\n  - Grep\nnotes: |\n  a\n  b\n---\nx")]
      (is (= "Use when folded onto one line." (get-in fm [:meta :description])))
      (is (= ["Read" "Grep"] (get-in fm [:meta :allowed-tools])))
      (is (= "a\nb" (get-in fm [:meta :notes])))))
  (testing "a line that is not key: value is reported, not swallowed"
    (let [fm (skills/parse-frontmatter "---\nname: x\nthis is not yaml\n---\nbody")]
      (is (= "x" (get-in fm [:meta :name])))
      (is (seq (:errors fm)))))
  (testing "an unclosed fence is reported and the whole file is the body"
    (let [fm (skills/parse-frontmatter "---\nname: x\nbody with no fence")]
      (is (seq (:errors fm)))
      (is (str/includes? (:body fm) "body with no fence")))))

(defn- tmp-root [] (str "/tmp/samizdat-skills-" (random-uuid)))

(deftest a-skill-directory-is-validated-and-its-fields-surface
  (let [root (tmp-root)
        dir (str root "/skills")]
    (fs/create-dirs (str dir "/good-skill/scripts"))
    (fs/create-dirs (str dir "/mismatch"))
    (fs/create-dirs (str dir "/Bad_Name"))
    (fs/create-dirs (str dir "/no-desc"))
    (try
      (spit (str dir "/good-skill/SKILL.md")
            "---\nname: good-skill\ndescription: Use when good.\nallowed-tools: Read Grep\nlicense: MIT\nmetadata:\n  team: x\n---\nSee scripts/run.sh")
      (spit (str dir "/good-skill/scripts/run.sh") "echo hi\n")
      (spit (str dir "/mismatch/SKILL.md") "---\nname: other-name\ndescription: Use when m.\n---\nm")
      (spit (str dir "/Bad_Name/SKILL.md") "---\nname: Bad_Name\ndescription: Use when b.\n---\nb")
      (spit (str dir "/no-desc/SKILL.md") "---\nname: no-desc\n---\n# heading only")
      (let [{:keys [skills diagnostics]} (skills/registry [dir])
            good (get skills "good-skill")
            problems (fn [nm] (set (keep #(when (= nm (:name %)) (:problem %)) diagnostics)))]
        (is (= ["Read" "Grep"] (:allowed-tools good)) "allowed-tools is surfaced, as a list")
        (is (= "MIT" (:license good)))
        (is (= {"team" "x"} (:metadata good)))
        (is (= (str dir "/good-skill") (:root good)) "a directory skill knows its root")
        (is (empty? (problems "good-skill")))
        (is (contains? (problems "other-name") :name-mismatch))
        (is (contains? (problems "Bad_Name") :bad-name))
        (is (contains? (problems "no-desc") :missing-description))
        (is (contains? skills "other-name") "a diagnostic does not drop the skill"))
      (finally (fs/delete-tree root)))))

(deftest a-shadowed-skill-is-reported-with-the-winner
  (let [base (tmp-root)
        proj (str base "/proj")
        glob (str base "/glob")]
    (fs/create-dirs (str proj "/dup"))
    (fs/create-dirs glob)
    (try
      (spit (str proj "/dup/SKILL.md") "---\nname: dup\ndescription: Use when p.\n---\nproject")
      (spit (str glob "/dup.md") "---\ndescription: Use when g.\n---\nglobal")
      (spit (str glob "/mycelium.md") "---\ndescription: Use when mine.\n---\nmine")
      (let [{:keys [skills shadowed]} (skills/registry [proj glob])]
        (is (= "project" (get-in skills ["dup" :body])))
        (is (some #(and (= "dup" (:name %))
                        (= (str proj "/dup/SKILL.md") (:path %))
                        (= (str glob "/dup.md") (:shadows %)))
                  shadowed))
        (is (some #(and (= "mycelium" (:name %))
                        (str/starts-with? (str (:shadows %)) "classpath:"))
                  shadowed)
            "a person's skill shadowing a shipped one says so"))
      (finally (fs/delete-tree base)))))

(deftest a-file-relative-to-a-skill-is-readable-and-confined
  (let [root (tmp-root)
        dir (str root "/skills")]
    (fs/create-dirs (str dir "/kit/ref"))
    (try
      (spit (str dir "/kit/SKILL.md") "---\nname: kit\ndescription: Use when kit.\n---\nread ref/a.md")
      (spit (str dir "/kit/ref/a.md") "reference text")
      (spit (str root "/secret.txt") "nope")
      (is (= "reference text" (:content (skills/read-skill-file [dir] "kit" "ref/a.md"))))
      (is (= :outside-skill (:error (skills/read-skill-file [dir] "kit" "../../secret.txt"))))
      (is (= :not-found (:error (skills/read-skill-file [dir] "kit" "ref/none.md"))))
      (is (= :no-skill (:error (skills/read-skill-file [dir] "nope" "a"))))
      (is (= :no-root (:error (skills/read-skill-file [dir] "mycelium" "a")))
          "a flat skill has no directory of its own")
      (with-redefs [skills/skill-roots (constantly ["skills"])]
        (let [ld (base/run-tool {:branch {:id "B1"} :tool-name "skill" :root root
                                 :args {:action "load" :name "kit"}})]
          (is (str/includes? (:result ld) "read ref/a.md"))
          (is (str/includes? (:result ld) (str dir "/kit")) "the load names the skill's root"))
        (let [rd (base/run-tool {:branch {:id "B1"} :tool-name "skill" :root root
                                 :args {:action "read" :name "kit" :path "ref/a.md"}})]
          (is (= :neutral (:category rd)))
          (is (str/includes? (:result rd) "reference text")))
        (let [lst (base/run-tool {:branch {:id "B1"} :tool-name "skill" :root root
                                  :args {:action "list"}})]
          (is (str/includes? (:result lst) "kit"))))
      (finally (fs/delete-tree root)))))

(deftest the-person-roots-include-other-harnesses-after-samizdats-own
  (let [roots (skills/skill-roots)
        idx #(.indexOf ^java.util.List roots %)]
    (is (some #{"~/.claude/skills"} roots))
    (is (some #{"~/.agents/skills"} roots))
    (is (< (idx ".samizdat/skills") (idx "{global}/skills") (idx "~/.agents/skills"))
        "a project root beats the person's, and samizdat's own person root beats another harness's")))
