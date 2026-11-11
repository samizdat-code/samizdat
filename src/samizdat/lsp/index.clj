;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lsp.index
  "The lsp tool's language server, answered in-process by jolt-lsp's project
  index instead of a clojure-lsp subprocess.

  The tool and the surgeon's semantic step speak LSP shapes — 0-based UTF-16
  positions, Location, Hover, WorkspaceEdit — and samizdat.lsp.edit turns a
  WorkspaceEdit into resource changes. This namespace keeps those shapes and
  converts at the edge, so nothing above it changed when the subprocess went:
  no binary to install, no JSON-RPC framing, no reader thread, no timeouts.
  jolt-lsp re-reads files changed on disk before every answer, so there is
  nothing to synchronize after the harness writes."
  (:require [clojure.string :as str]
            [jolt-lsp.api :as api]
            [jolt-lsp.db :as db]
            [jolt-lsp.text :as text]))

(defn available?
  "Always: the index is a library in this process."
  []
  true)

(defn client-for
  "The handle the tool passes back in: the project root, and a session id the
  semantic step records as the evidence's source."
  [root]
  (when root
    (let [root (api/root-for (str root))]
      {:root root :session-id (str "jolt-lsp:" root)})))

(defn shutdown-all!
  "Nothing runs outside this process."
  []
  nil)

(defn synchronize!
  "jolt-lsp follows the disk; a written file is re-read on the next request."
  [_client _path _text]
  nil)

(defn changed!
  "As synchronize!."
  [_client _path _text]
  nil)

;; ---- positions -------------------------------------------------------------

(defn- uri [path] (str "file://" path))

(defn- source-of [path]
  (try (slurp (str path)) (catch Throwable _ "")))

(defn- lsp->1based
  "A 0-based UTF-16 LSP position in `path` as jolt-lsp's 1-based line and
  codepoint column."
  [path line character]
  (let [s (source-of path)
        row (inc (long line))]
    [row (inc (text/utf16->codepoint-col s row (long character)))]))

(defn- lsp-position
  "1-based row and codepoint column in `s` as an LSP position."
  [s row col]
  {:line (dec row) :character (text/codepoint->utf16-col s row (dec col))})

(defn- location [loc]
  {:uri (uri (:file loc)) :range (:lsp loc)})

;; ---- reads -----------------------------------------------------------------

(defn definition
  "The Location defining the symbol at the position, or nil."
  [client path line character]
  (let [[row col] (lsp->1based path line character)
        found (api/definition (:root client) {:file (str path) :line row :col col})]
    (some-> (if (map? found) found (first found)) location)))

(defn references
  "Every Location of the symbol at the position, its definition included."
  [client path line character]
  (let [[row col] (lsp->1based path line character)
        found (api/references (:root client) {:file (str path) :line row :col col})]
    (mapv location (if (map? found) (:locations found) found))))

(defn hover
  "{:contents {:kind \"markdown\" :value …}} for the symbol at the position,
  or nil."
  [client path line character]
  (let [[row col] (lsp->1based path line character)
        h (api/hover (:root client) {:file (str path) :line row :col col})]
    (when (and (map? h) (:fqn h))
      {:contents {:kind "markdown"
                  :value (str/join "\n\n"
                                   (remove str/blank?
                                           [(str "`" (:fqn h) "`"
                                                 (when-let [a (:arglists h)] (str " " a)))
                                            (str (:doc h))
                                            (when-let [c (:code h)] (str "```clojure\n" c "\n```"))]))}})))

(defn diagnostics
  "LSP Diagnostics for `path` from jolt-lsp's linters (and clj-kondo's, when
  it is installed)."
  [client path]
  (let [s (source-of path)
        r (api/diagnostics* (:root client) {:file (str path)})]
    (vec (for [d (if (map? r) (:diagnostics r) r)]
           {:range {:start (lsp-position s (:row d) (:col d))
                    :end (lsp-position s (or (:end-row d) (:row d)) (or (:end-col d) (:col d)))}
            :severity (name (:severity d))
            :code (some-> (:code d) name)
            :source (some-> (:source d) name)
            :message (:message d)}))))

;; ---- edits -----------------------------------------------------------------

(defn- text-edit
  "One of jolt-lsp's edit views over `s` as an LSP TextEdit."
  [s e]
  {:range {:start (lsp-position s (:start-row e) (:start-col e))
           :end (lsp-position s (:end-row e) (:end-col e))}
   :newText (str (:new-text e))})

(defn- workspace-edit
  "jolt-lsp's previewed edits ({path [edit]}) as a WorkspaceEdit, or nil when
  the preview failed or changed nothing."
  [r]
  (when (and (:ok r) (seq (:edits r)))
    {:changes (into {} (for [[path es] (:edits r) :when (seq es)
                             :let [s (source-of path)]]
                         [(uri path) (mapv #(text-edit s %) es)]))}))

(defn rename
  "The WorkspaceEdit renaming the symbol at the position to `new-name` — a
  PROPOSAL; nothing is written here."
  [client path line character new-name]
  (let [[row col] (lsp->1based path line character)]
    (workspace-edit (api/rename* (:root client) {:path (str path) :row row :col col
                                                 :to (str new-name)}))))

(def ^:private refactors
  ;; jolt-lsp refactor kinds offered as code actions at a position, by title
  [["Clean namespace" nil]
   ["Sort requires" "sort-requires"]
   ["Inline symbol" "inline-symbol"]
   ["Cycle keyword" "cycle-keyword"]
   ["Destructure keys" "destructure-keys"]
   ["Restructure keys" "restructure-keys"]])

(defn code-actions
  "The refactors that apply at `range`'s start, each {:title :edit} with its
  WorkspaceEdit already computed (previews; nothing is written)."
  [client path range _context]
  (let [{:keys [line character]} (:start range)
        [row col] (lsp->1based path (or line 0) (or character 0))]
    (vec (for [[title kind] refactors
               :let [r (try (if kind
                              (api/refactor* (:root client) {:kind kind :file (str path) :line row :col col})
                              (api/clean-ns* (:root client) {:file (str path)}))
                            (catch Throwable _ nil))
                     edit (workspace-edit r)]
               :when edit]
           {:title title :edit edit}))))

(defn resolve-code-action
  "Actions arrive resolved."
  [_client action]
  action)

(defn prepare-file-rename
  "The WorkspaceEdit that goes with moving `old-path` to `new-path`: the
  namespace the new path implies replaces the old one in the moved file and
  in every require and qualified reference to it. The move itself is the
  caller's. Nothing is written here."
  [client old-path new-path]
  (let [root (:root client)
        d (api/ensure-db root)
        from (:ns (db/file-by-path d (api/resolve-path root (str old-path))))
        to (:ns (api/new-file-ns root {:file (str new-path)}))]
    (if-not (and from to (not= from to))
      {:changes {}}
      (let [r (api/rename-namespace* root {:from from :to to :texts true})
            abs #(api/resolve-path root %)
            moved (into {} (for [{f :from t :to} (:moves r)] [t f]))]
        (when-not (:ok r)
          (throw (ex-info (str "namespace rename refused: " (:reason r))
                          {:lsp-edit/error :rename-refused :reason (:reason r)})))
        {:changes (into {} (for [[rel text] (:texts r)
                                 :let [path (abs (get moved rel rel))
                                       old (source-of path)
                                       lines (text/line-starts old)]]
                             [(uri path)
                              [{:range {:start {:line 0 :character 0}
                                        :end (lsp-position old (count lines)
                                                           (inc (- (count old) (peek lines))))}
                                :newText text}]]))}))))
