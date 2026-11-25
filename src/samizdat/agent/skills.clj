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

(ns samizdat.agent.skills
  "Skills: instruction bundles the agent loads on demand, so a guide costs
  context only when it is needed. A skill is a markdown file whose first
  meaningful line is its one-line description (the catalogue entry) and whose
  body is the guidance loaded on request. Bundled skills live in
  resources/skills/; a project can add or override them in .samizdat/skills/,
  first match by directory order — the same layering as cells and config."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.layers :as layers]
            [samizdat.lexicon :as lexicon]
            [samizdat.util :as util]))

(def shipped-skills
  "Basenames of the skills that ship, ENUMERATED not globbed — a classpath has
  no directory listing and an embedded resource has no filesystem path, so a
  dir scan found nothing inside a built binary or from any cwd but the
  samizdat repo itself: every implementor silently lost the REPL/TDD guidance
  and the catalogue rendered empty (karamazov-blt.33). Same reasoning as
  cells/shipped-cells; pinned against resources/skills by skills-test."
  ["clojure-style" "clj-debug" "clj-discover" "clj-refactor" "clj-replace" "clj-surgeon" "mycelium" "repl-workflow" "harness-tuning"])

(defn skill-roots
  "The project-relative directories a skill may live in, in precedence order —
  wordlists.edn `:skill-roots`, earliest wins.

  A LIST, and policy rather than a literal here, because samizdat is not the
  only harness that leaves skills on disk. Claude Code, pi, opencode and the
  agents standard all write `<root>/<name>/SKILL.md`, and which of those roots
  a given project honours is exactly the sort of thing the standing rule says
  the agent should be able to change without a rebuild. Adapted from vis's
  ordered cross-harness registry (resources/vis-docs/skills.md), whose framing
  is the right one: reading another harness's skills is a compatibility layer,
  not a format."
  []
  (lexicon/wordlist :skill-roots))

(defn- resolve-root
  "One skill root as a directory, or nil when it names nothing here.
  `{global}` is the person's config dir (layers/global-dir: the selected
  profile's, else $XDG_CONFIG_HOME/samizdat or ~/.config/samizdat), so a skill
  can follow the person from project to project. `~/` is $HOME, an absolute
  root is kept, and anything else is under the run's root."
  [root r]
  (let [r (str r)
        home (System/getenv "HOME")]
    (cond
      (str/starts-with? r "{global}")
      (some-> (layers/global-dir) (str (subs r (count "{global}"))))

      (or (= r "~") (str/starts-with? r "~/"))
      (some-> home (str (subs r 1)))

      (str/starts-with? r "/") r

      :else (str (io/file (str (or root ".")) r)))))

(defn project-dirs
  "The directories a run's skills are read from, in precedence order: each
  root resolved against the RUN's root — not the harness's cwd
  (karamazov-blt.33) — except a `{global}`, `~/` or absolute one."
  [root]
  (into [] (keep #(resolve-root root %)) (skill-roots)))

(defn default-dirs
  "project-dirs for the process cwd. A function, not a value, because the
  person's config dir depends on the profile selected at runtime."
  []
  (project-dirs "."))

(def ^:private skill-doc
  "The filename a skill DIRECTORY carries its instructions in. Every other
  harness spells it this way, so reading theirs costs nothing."
  "SKILL.md")

(defn- skill-files
  "The skill sources in `dir`, as [name file] pairs, in stable name order.

  TWO SHAPES, because a skill on disk has two conventions and only one of them
  was ever read here (karamazov-15h):
    <dir>/<name>.md          — samizdat's own flat form
    <dir>/<name>/SKILL.md    — a directory per skill, plus its bundled
                               resources; what every other harness writes,
                               and what this repo's own .agents/skills holds
  A directory's name is the fallback skill name, so a SKILL.md with no
  frontmatter `name` still lands under something meaningful."
  [dir]
  (let [d (io/file dir)]
    (when (.isDirectory d)
      (->> (.listFiles d)
           (keep (fn [^java.io.File f]
                   (cond
                     (and (.isFile f) (str/ends-with? (.getName f) ".md"))
                     [(str/replace (.getName f) #"\.md$" "") f]

                     (.isDirectory f)
                     (let [doc (io/file f skill-doc)]
                       (when (.isFile doc) [(.getName f) doc])))))
           (sort-by first)))))

(defn- unquote-scalar
  "A YAML scalar as a string: double quotes with their backslash escapes,
  single quotes with '' for a quote, anything else trimmed as written."
  [v]
  (let [v (str/trim (str v))
        n (count v)]
    (cond
      (and (>= n 2) (str/starts-with? v "\"") (str/ends-with? v "\""))
      (str/replace (subs v 1 (dec n)) #"\\(.)"
                   (fn [[_ c]] (case c "n" "\n" "t" "\t" c)))

      (and (>= n 2) (str/starts-with? v "'") (str/ends-with? v "'"))
      (str/replace (subs v 1 (dec n)) "''" "'")

      :else v)))

(defn- scalar-or-flow
  "A value written on its key's own line: an inline `[a, b]` list, else a
  scalar."
  [v]
  (if (and (str/starts-with? v "[") (str/ends-with? v "]"))
    (into [] (comp (map str/trim) (remove str/blank?) (map unquote-scalar))
          (str/split (subs v 1 (dec (count v))) #","))
    (unquote-scalar v)))

(defn- indented? [line] (boolean (re-find #"^\s+\S" line)))

(defn- nested-value
  "The value of a key with nothing after its colon, from the indented lines
  under it: a `- item` list, a `k: v` map, or {:error ..}."
  [block]
  (let [lines (map str/trim (remove str/blank? block))]
    (cond
      (empty? lines) ""
      (every? #(str/starts-with? % "- ") lines)
      (mapv #(unquote-scalar (subs % 2)) lines)
      :else
      (let [pairs (map #(re-find #"^([^:\s][^:]*?)\s*:\s*(.*)$" %) lines)]
        (if (every? some? pairs)
          (into {} (map (fn [[_ k v]] [(unquote-scalar k) (scalar-or-flow v)])) pairs)
          {:error (first lines)})))))

(defn- parse-yaml-subset
  "The YAML a skill's frontmatter uses, without a YAML library: top-level
  `key: value` scalars (plain, quoted, or continued on indented lines),
  `>`/`|` block scalars, `- item` lists, inline `[a, b]` lists and one level
  of `k: v` map (an Agent Skills `metadata`). Keys are lower-cased keywords.
  Anything else is an ERROR naming its line, never silently dropped.
  Returns {:meta :errors}."
  [text]
  (let [lines (vec (str/split-lines (str text)))
        n (count lines)]
    (loop [i 0 meta {} errors []]
      (if (>= i n)
        {:meta meta :errors errors}
        (let [line (nth lines i)]
          (if (or (str/blank? line) (str/starts-with? (str/triml line) "#"))
            (recur (inc i) meta errors)
            (if-let [[_ k v] (re-find #"^([A-Za-z_][A-Za-z0-9_-]*)\s*:(?:\s+(.*?))?\s*$" line)]
              (let [block (vec (take-while #(or (str/blank? %) (indented? %))
                                           (subvec lines (inc i))))
                    nxt (+ i 1 (count block))
                    more (map str/trim (remove str/blank? block))
                    v (some-> v str/trim not-empty)
                    value (cond
                            (and v (re-matches #"[>|][+-]?" v))
                            (str/join (if (str/starts-with? v ">") " " "\n") more)
                            (and v (seq more))
                            (unquote-scalar (str/join " " (cons v more)))
                            v (scalar-or-flow v)
                            :else (nested-value block))]
                (if (and (map? value) (:error value))
                  (recur nxt meta (conj errors (str "line " (inc i) ": `" k "` " (:error value))))
                  (recur nxt (assoc meta (keyword (str/lower-case k)) value) errors)))
              (recur (inc i) meta
                     (conj errors (str "line " (inc i) ": not `key: value`: "
                                       (str/trim line)))))))))))

(defn parse-frontmatter
  "Split a skill file into {:meta {..} :body .. :errors [..]}. A leading
  `---` fenced block is read as the YAML subset skills use
  (parse-yaml-subset); the rest is the body. No frontmatter -> empty meta and
  the whole file as body. An opening fence with no closing one is an error,
  and then the whole file is the body. :errors is absent when there are none."
  [content]
  (let [content (str content)]
    (if-let [m (re-find #"(?s)\A---[ \t]*\r?\n(?:(.*?)\r?\n)?---[ \t]*(?:\r?\n(.*))?\z" content)]
      (let [{:keys [meta errors]} (parse-yaml-subset (nth m 1))]
        (cond-> {:meta meta :body (str/trim (str (nth m 2)))}
          (seq errors) (assoc :errors errors)))
      (if (re-find #"\A---[ \t]*\r?\n" content)
        {:meta {} :body (str/trim content)
         :errors [:unclosed]}
        {:meta {} :body (str/trim content)}))))

(defn- describe
  "A skill's one-line catalogue description — its trigger. The frontmatter
  `description` (write it as \"Use when …\"), else the first non-heading line,
  else nil so the skill drops from the catalogue but stays loadable."
  [fm name]
  (or (let [d (get-in fm [:meta :description])]
        (when (string? d) (not-empty (str/trim d))))
      (some #(when-not (or (str/blank? %) (str/starts-with? (str/trim %) "#"))
               (str/trim %))
            (str/split-lines (:body fm)))))

(defn- tool-list
  "`allowed-tools` as a list: the Agent Skills spelling is one
  space-separated string, where a pattern like `Bash(git add:*)` may hold
  spaces of its own; a YAML list is taken as written."
  [v]
  (cond
    (sequential? v) (mapv str v)
    (string? v) (vec (re-seq #"[^\s(]+(?:\([^)]*\))?" v))
    :else nil))

(defn- limits [] (or (gates/threshold :skills) {}))

(defn diagnose
  "What is wrong with one skill, as [{:problem kw :detail str}]. Every skill
  is checked for a frontmatter that reads; a DIRECTORY skill is held to the
  Agent Skills format besides: a `name` that is kebab-case, at most
  :name-max chars and its directory's own name, and a `description` of at
  most :description-max chars (gates.edn :skills). A diagnostic never drops
  the skill — the listing reports it and the skill still loads."
  [{:keys [dir-name format]} fm]
  (let [{:keys [name-max description-max]} (limits)
        nm (get-in fm [:meta :name])
        desc (get-in fm [:meta :description])]
    (into (mapv (fn [e] (if (= :unclosed e)
                         {:problem :unclosed-frontmatter :detail "---"}
                         {:problem :bad-frontmatter :detail e}))
                (:errors fm))
          (when (= :directory format)
            (cond-> []
              (not (and (string? desc) (not (str/blank? desc))))
              (conj {:problem :missing-description :detail "no `description`"})

              (and (string? desc) description-max (> (count desc) description-max))
              (conj {:problem :description-too-long
                     :detail (str (count desc) " chars, over " description-max)})

              (nil? nm)
              (conj {:problem :missing-name
                     :detail (str "no `name`; using `" dir-name "`")})

              (and (some? nm)
                   (or (not (string? nm))
                       (not (re-matches #"[a-z0-9]+(-[a-z0-9]+)*" nm))
                       (and name-max (> (count nm) name-max))))
              (conj {:problem :bad-name
                     :detail (str "`" nm "` (" name-max ")")})

              (and (some? nm) (not= (str nm) dir-name))
              (conj {:problem :name-mismatch
                     :detail (str "`" nm "` in directory `" dir-name "`")}))))))

(defn- entry
  "One skill file read into its registry entry, as [name {:path :root :source
  :format :description :body :allowed-tools :license :metadata
  :diagnostics}]."
  [{:keys [path root source format dir-name] :as where} content]
  (let [fm (parse-frontmatter content)
        nm (get-in fm [:meta :name])
        nm (or (when (string? nm) (not-empty nm)) dir-name)
        meta (:meta fm)]
    [nm (cond-> {:path path :source source :format format
                 :description (describe fm nm)
                 :body (:body fm)
                 :diagnostics (diagnose where fm)}
          root (assoc :root root)
          (some? (:allowed-tools meta)) (assoc :allowed-tools (tool-list (:allowed-tools meta)))
          (string? (:license meta)) (assoc :license (:license meta))
          (map? (:metadata meta)) (assoc :metadata (:metadata meta)))]))

(defn- shipped-entries
  "The bundled skills, read off the classpath so they resolve from a built
  binary and from any cwd."
  []
  (into []
        (keep (fn [nm]
                (try
                  (when-let [r (io/resource (str "skills/" nm ".md"))]
                    (entry {:path (str "classpath:skills/" nm ".md") :source "classpath"
                            :format :flat :dir-name nm}
                           (slurp r)))
                  (catch Throwable _ nil))))
        shipped-skills))

(defn- dir-entries
  "The skills in one root, as registry entries, in stable name order. A file
  that will not read is skipped, never fatal."
  [dir]
  (into []
        (keep (fn [[nm ^java.io.File f]]
                (try
                  (let [directory? (= skill-doc (.getName f))]
                    (entry (cond-> {:path (.getPath f) :source (str dir)
                                    :format (if directory? :directory :flat)
                                    :dir-name nm}
                             directory? (assoc :root (.getParent f)))
                           (slurp f)))
                  (catch Throwable _ nil))))
        (skill-files dir)))

(defn registry
  "Every skill visible from `dirs`, with what shadows what and what is wrong:
  {:skills {name entry} :shadowed [{:name :path :shadows}] :diagnostics
  [{:name :path :problem :detail}]}.

  Lowest precedence first: the shipped skills, then `dirs` from LAST to
  FIRST, so the earliest root wins — a project's .samizdat/skills beats the
  person's roots, which beat a bundled skill of the same name. Each time a
  later layer takes a name an earlier one had, :shadowed records the winner's
  path and the path it hid."
  ([] (registry (default-dirs)))
  ([dirs]
   (let [all (apply concat (shipped-entries) (map dir-entries (reverse dirs)))]
     (reduce (fn [acc [nm e]]
               (let [prior (get-in acc [:skills nm])]
                 (cond-> (assoc-in acc [:skills nm] e)
                   prior (update :shadowed conj {:name nm :path (:path e)
                                                 :shadows (:path prior)}))))
             {:skills {} :shadowed []
              :diagnostics (into [] (for [[nm e] all
                                          d (:diagnostics e)]
                                      (assoc d :name nm :path (:path e))))}
             all))))

(defn discover
  "skill-name -> entry (see `registry`): the winners only. The shipped skills
  come off the classpath; `dirs` overlay them with the EARLIEST winning.
  Malformed files are skipped, never fatal."
  ([] (discover (default-dirs)))
  ([dirs] (:skills (registry dirs))))

(defn catalog
  "The always-on catalogue the agent sees: [{:name :description} ...], sorted.
  A skill with no description is omitted (it can still be loaded by name), so
  the visible list stays high-signal."
  ([] (catalog (default-dirs)))
  ([dirs]
   (->> (discover dirs)
        (keep (fn [[nm {:keys [description]}]]
                (when description {:name nm :description description})))
        (sort-by :name)
        vec)))

(defn render-catalog
  "The cheap, always-injected system-prompt block: names + trigger descriptions
  only, never bodies. Empty string when there are no skills."
  ([] (render-catalog (default-dirs)))
  ([dirs]
   (let [cat (catalog dirs)]
     (if (empty? cat)
       ""
       (str "Skills are guidance you load only when a task matches. Load one"
            " with `skill load {name}` when its description fits what you are"
            " about to do:\n"
            (str/join "\n" (for [{:keys [name description]} cat]
                             (str "- **" name "** — " description))))))))

(defn load-skill
  "The full body of a named skill (frontmatter stripped), or nil for a miss."
  ([name] (load-skill (default-dirs) name))
  ([dirs name]
   (get-in (discover dirs) [name :body])))

(defn skill-entry
  "The registry entry of a named skill, or nil."
  [dirs name]
  (get (discover dirs) name))

(defn read-skill-file
  "A file bundled with a DIRECTORY skill, read relative to its root:
  {:content :path} or {:error kw} — :no-skill, :no-root (a flat skill has no
  directory of its own), :outside-skill (the path escapes the root, by `..`
  or a symlink alike: judged on canonical paths), :not-found. Content past
  gates.edn :skills :file-chars is cut in the middle."
  [dirs name rel]
  (let [e (skill-entry dirs name)]
    (cond
      (nil? e) {:error :no-skill}
      (nil? (:root e)) {:error :no-root}
      :else
      (let [canon #(.getCanonicalPath (io/file (str %)))
            root (canon (:root e))
            path (canon (io/file (:root e) (str rel)))]
        (cond
          (not (str/starts-with? path (str root "/"))) {:error :outside-skill}
          (not (.isFile (io/file path))) {:error :not-found}
          :else {:path path
                 :content (let [text (slurp path) cap (:file-chars (limits))]
                            (cond-> text cap (util/truncate-middle cap)))})))))
