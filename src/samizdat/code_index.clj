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

(ns samizdat.code-index
  "A project's source as a BM25-ranked FTS5 index (karamazov-k7gl.7): what
  the `code_search` tool and GET /v1/harness/search answer from.

  Files are cut into line-bounded chunks that overlap, so a definition that
  straddles a boundary is whole in one of them. Each chunk is indexed with
  its PATH as a column of its own, weighted above the body, so `invoice`
  finds src/billing/invoice.clj before a file that mentions an invoice once.

  WHAT IS INDEXED: in a git project, what `git ls-files` shows — tracked and
  new, without what .gitignore hides; elsewhere every file under the root
  outside a hidden directory. Either way, minus the excludes (globs over the
  path relative to the root), files over `:max-file-bytes`, and anything with
  a NUL byte in it.

  INCREMENTAL. code_files keeps each file's mtime, size and content hash per
  root. A refresh stats every file: one whose mtime and size are unchanged is
  skipped; one whose hash is unchanged only has its stat updated; anything
  else is re-chunked. A file that is gone leaves the index. `search` refreshes
  before it ranks, so the first search builds the index and every later one
  sees the tree as it is.

  Mechanism only: the numbers are the caller's (gates.edn :code-search, the
  excludes config.edn :code-search :exclude)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.lexicon :as lexicon]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]))

;; --- chunks ------------------------------------------------------------------

(defn chunks
  "`text` cut into chunks of `chunk-lines` lines, each starting
  `overlap-lines` before the last one ended: [{:start-line :end-line :text}],
  1-based and inclusive."
  [text {:keys [chunk-lines overlap-lines]}]
  (let [lines (str/split-lines (str text))
        n (count lines)
        size (max 1 (long chunk-lines))
        step (max 1 (- size (max 0 (long overlap-lines))))]
    (if (or (zero? n) (and (= 1 n) (str/blank? (first lines))))
      []
      (loop [start 0, out []]
        (let [end (min n (+ start size))
              out (conj out {:start-line (inc start) :end-line end
                             :text (str/join "\n" (subvec (vec lines) start end))})]
          (if (>= end n) out (recur (+ start step) out)))))))

;; --- what is indexed ---------------------------------------------------------

(defn- glob-re
  "A path glob as a regex: `**/` any directories, `**` anything, `*` and `?`
  within one segment. A trailing `/` names a directory and all beneath it."
  [glob]
  (let [g (if (str/ends-with? glob "/") (str glob "**") glob)]
    (loop [cs (seq g), out "^"]
      (cond
        (empty? cs) (re-pattern (str out "$"))
        (= [\* \* \/] (take 3 cs)) (recur (drop 3 cs) (str out "(?:.*/)?"))
        (= [\* \*] (take 2 cs)) (recur (drop 2 cs) (str out ".*"))
        (= \* (first cs)) (recur (rest cs) (str out "[^/]*"))
        (= \? (first cs)) (recur (rest cs) (str out "[^/]"))
        (re-find #"[.+^$()|{}\[\]\\]" (str (first cs)))
        (recur (rest cs) (str out "\\" (first cs)))
        :else (recur (rest cs) (str out (first cs)))))))

(defn excluded?
  "Whether relative `path` matches any of the `globs`."
  [path globs]
  (boolean (some #(re-find (glob-re (str %)) (str path)) globs)))

(defn- walk
  "Every regular file under `root` outside a hidden directory, relative."
  [root]
  (for [p (fs/glob root "**")
        :let [f (io/file (str p))]
        :when (.isFile f)
        :let [rel (str (fs/relativize root (str p)))]
        :when (not-any? #(str/starts-with? % ".") (str/split rel #"/"))]
    rel))

(defn project-files
  "The relative paths the index covers under `root` (see the ns doc)."
  [root {:keys [exclude]}]
  (->> (or (gitdiff/project-files root) (walk root))
       (remove #(excluded? % exclude))
       distinct
       sort))

(defn- readable-text
  "The text of `f`, or nil when it is too big or not text."
  [f max-bytes]
  (when (and (.isFile f) (<= (.length f) (long max-bytes)))
    (let [text (try (slurp f) (catch Throwable _ nil))]
      (when (and text (not (str/includes? text (str (char 0)))))
        text))))

;; --- the index ---------------------------------------------------------------

(defn- drop-file! [conn root path]
  (db/execute! conn ["DELETE FROM code_chunks_fts WHERE rowid IN
                       (SELECT id FROM code_chunks WHERE root = ? AND path = ?)" root path])
  (db/execute! conn ["DELETE FROM code_chunks WHERE root = ? AND path = ?" root path])
  (db/execute! conn ["DELETE FROM code_files WHERE root = ? AND path = ?" root path]))

(defn- index-file! [conn root path text stat hash opts]
  (drop-file! conn root path)
  (doseq [{:keys [start-line end-line text]} (chunks text opts)]
    (db/execute! conn ["INSERT INTO code_chunks (root, path, start_line, end_line, text)
                        VALUES (?, ?, ?, ?, ?)" root path start-line end-line text])
    (let [id (:id (db/fetch-one conn ["SELECT last_insert_rowid() AS id"]))]
      (db/execute! conn ["INSERT INTO code_chunks_fts (rowid, path, body) VALUES (?, ?, ?)"
                         id path text])))
  (db/execute! conn ["INSERT INTO code_files (root, path, mtime, size, hash, indexed_at)
                      VALUES (?, ?, ?, ?, ?, ?)"
                     root path (:mtime stat) (:size stat) hash (db/now)]))

(defn- canonical [root] (str (fs/canonicalize (or root "."))))

(defn refresh!
  "Bring `root`'s index up to the tree on disk. Returns {:indexed :removed
  :unchanged :files}: files (re)chunked, dropped, and left alone."
  [conn root {:keys [max-file-bytes] :as opts}]
  (let [root (canonical root)
        held (into {} (map (juxt :path identity))
                   (db/fetch conn ["SELECT path, mtime, size, hash FROM code_files WHERE root = ?" root]))
        paths (project-files root opts)
        counts (atom {:indexed 0 :removed 0 :unchanged 0 :files (count paths)})
        ;; One transaction a file: its chunks and its row land together, and
        ;; a large first index is not one lock held for the whole tree.
        tx! (fn [f] (db/atomic conn f))]
    (doseq [path paths
            :let [f (io/file root path)
                  stat {:mtime (.lastModified f) :size (.length f)}
                  old (get held path)]]
      (if (and old (= (:mtime stat) (:mtime old)) (= (:size stat) (:size old)))
        (swap! counts update :unchanged inc)
        (if-let [text (readable-text f max-file-bytes)]
          (let [hash (workspace/content-revision text)]
            (if (and old (= hash (:hash old)))
              (do (db/execute! conn ["UPDATE code_files SET mtime = ?, size = ?
                                      WHERE root = ? AND path = ?"
                                     (:mtime stat) (:size stat) root path])
                  (swap! counts update :unchanged inc))
              (do (tx! #(index-file! conn root path text stat hash opts))
                  (swap! counts update :indexed inc))))
          ;; Became binary or too big: it leaves the index.
          (when old
            (tx! #(drop-file! conn root path))
            (swap! counts update :removed inc)))))
    (doseq [path (remove (set paths) (keys held))]
      (tx! #(drop-file! conn root path))
      (swap! counts update :removed inc))
    @counts))

;; --- searching ---------------------------------------------------------------

(defn- terms [text]
  (->> (str/split (str/lower-case (str text)) #"[^a-z0-9]+")
       (remove str/blank?)
       distinct
       (take 16)))

(defn fts-query
  "Free text as an FTS5 OR query of quoted terms — FTS5 reads several
  characters as operators, so raw text is not a safe query. \"\" when no
  term is left."
  [text]
  (str/join " OR " (map #(str "\"" % "\"") (terms text))))

(defn- snippet
  "The `n` lines of `text` around the line most of `ts` appear on, and that
  line's number (from `start`)."
  [text start ts n]
  (let [lines (vec (str/split-lines (str text)))
        hits (fn [l] (let [l (str/lower-case l)] (count (filter #(str/includes? l %) ts))))
        best (if (seq lines) (apply max-key #(hits (nth lines %)) (reverse (range (count lines)))) 0)
        from (max 0 (min (- best (quot (dec n) 2)) (- (count lines) n)))
        to (min (count lines) (+ from n))]
    {:line (+ start best)
     :snippet (str/join "\n" (subvec lines from to))}))

(defn rank
  "`query`'s best chunks under `root`, best first: [{:path :start-line
  :end-line :line :snippet :score}], score the negated bm25 (higher is
  better). Searches what is indexed; `search` refreshes first."
  [conn root query {:keys [limit path-weight body-weight snippet-lines]}]
  (let [q (fts-query query)]
    (if (str/blank? q)
      []
      (let [ts (terms query)
            rows (db/fetch conn [(format "SELECT c.path, c.start_line, c.end_line, c.text,
                                                 bm25(code_chunks_fts, %s, %s) AS rank
                                          FROM code_chunks_fts f
                                          JOIN code_chunks c ON c.id = f.rowid
                                          WHERE code_chunks_fts MATCH ? AND c.root = ?
                                          ORDER BY rank LIMIT ?"
                                         (double path-weight) (double body-weight))
                                 q (canonical root) (long limit)])]
        (mapv (fn [{:keys [path start_line end_line text rank]}]
                (merge {:path path :start-line start_line :end-line end_line
                        :score (- (double rank))}
                       (snippet text start_line ts (max 1 (long snippet-lines)))))
              rows)))))

(defn search
  "Refresh `root`'s index, then `rank` `query` in it."
  [conn root query opts]
  (refresh! conn root opts)
  (rank conn root query opts))

(defn clamp-limit
  "The hits to return: `asked` (a number or its text), else the default
  :limit, never past :max-limit and never below one."
  [asked {:keys [limit max-limit]}]
  (let [n (or (some-> asked str str/trim parse-long) limit)]
    (max 1 (min (long n) (long max-limit)))))

(defn options
  "The search settings in force: gates.edn :code-search, with config.edn
  :code-search :exclude added to its excludes."
  [config]
  (let [g (lexicon/policy :code-search)]
    (update g :exclude #(vec (concat % (get-in config [:code-search :exclude]))))))
