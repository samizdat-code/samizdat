;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.code-index-test
  "BM25 code search over FTS5 (karamazov-k7gl.7): chunking, what is skipped,
  the incremental refresh, the ranking, the tool and the route."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as tools-base]
            [samizdat.agent.tools.code-search]
            [samizdat.code-index :as ci]
            [samizdat.engine.proc :as proc]
            [samizdat.store.db :as db]
            [samizdat.system :as system]))

(def ^:private opts
  {:chunk-lines 4 :overlap-lines 1 :max-file-bytes 100000 :snippet-lines 2
   :path-weight 5.0 :body-weight 1.0 :limit 10 :exclude []})

(defn- project!
  "A fresh directory holding `files` {rel-path text}."
  [files]
  (let [root (str (fs/canonicalize (str (fs/create-temp-dir))))]
    (doseq [[p text] files]
      (io/make-parents (io/file root p))
      (spit (io/file root p) text))
    root))

(deftest text-is-cut-into-overlapping-line-chunks
  (let [text (str/join "\n" (map #(str "line " %) (range 1 11)))
        cs (ci/chunks text {:chunk-lines 4 :overlap-lines 1})]
    (is (= [[1 4] [4 7] [7 10]] (mapv (juxt :start-line :end-line) cs))
        "each chunk starts on the last line of the one before")
    (is (= "line 4\nline 5\nline 6\nline 7" (:text (second cs)))))
  (is (= [[1 2]] (mapv (juxt :start-line :end-line) (ci/chunks "a\nb" {:chunk-lines 4 :overlap-lines 1}))))
  (is (empty? (ci/chunks "" {:chunk-lines 4 :overlap-lines 1})))
  (is (= [[1 3] [4 6]] (mapv (juxt :start-line :end-line)
                             (ci/chunks "a\nb\nc\nd\ne\nf" {:chunk-lines 3 :overlap-lines 0})))))

(deftest excludes-are-globs-over-the-relative-path
  (is (ci/excluded? "vendor/x/y.clj" ["vendor/**"]))
  (is (ci/excluded? "a/b.min.js" ["**/*.min.js"]))
  (is (ci/excluded? "b.min.js" ["*.min.js"]))
  (is (not (ci/excluded? "src/b.js" ["*.min.js" "vendor/**"])))
  (is (ci/excluded? "build/out.txt" ["build/"]) "a trailing slash is the directory"))

(deftest a-query-is-safe-fts-text
  (is (= "\"run\" OR \"usage\" OR \"by\" OR \"model\"" (ci/fts-query "run-usage-by-model")))
  (is (= "" (ci/fts-query "\"*(")) "operators alone are no query"))

(deftest search-ranks-finds-and-refreshes
  (let [root (project! {"src/billing/invoice.clj" "(ns billing.invoice)\n(defn total [lines]\n  (reduce + lines))\n"
                        "src/util.clj" "(ns util)\n(defn helper [] :x)\n;; invoice mention\n"
                        "notes/bin.dat" (str "invoice" (char 0) "binary")
                        "vendor/lib.clj" "(defn invoice [] :vendored)\n"})
        conn (db/open! ":memory:")
        o (assoc opts :exclude ["vendor/**"])]
    (try
      (with-redefs [proc/available? (constantly false)] ; not a git repo: walk it
        (let [hits (ci/search conn root "invoice" o)]
          (is (= "src/billing/invoice.clj" (:path (first hits)))
              "a path token outranks one mention in a body")
          (is (= #{"src/billing/invoice.clj" "src/util.clj"} (set (map :path hits)))
              "binary files and excluded paths are not indexed")
          (is (every? #(and (pos? (:start-line %)) (>= (:end-line %) (:start-line %))
                            (number? (:score %)) (string? (:snippet %)))
                      hits))
          (is (str/includes? (:snippet (second hits)) "invoice mention")))
        (testing "an unchanged tree is not reindexed"
          (is (= {:indexed 0 :removed 0} (select-keys (ci/refresh! conn root o) [:indexed :removed]))))
        (testing "a changed file is reindexed before the next search"
          (spit (io/file root "src/util.clj") "(ns util)\n(defn ledger-reconcile [] :y)\n")
          (is (= ["src/util.clj"] (map :path (ci/search conn root "reconcile" o))))
          (is (not-any? #{"src/util.clj"} (map :path (ci/search conn root "invoice" o)))
              "its old text is gone from the index"))
        (testing "a deleted file leaves the index"
          (io/delete-file (io/file root "src/billing/invoice.clj"))
          (is (empty? (ci/search conn root "total" o)))))
      (finally (db/close conn)))))

(deftest in-a-git-repo-the-ignored-files-are-skipped
  (when (proc/available? "git")
    (let [root (project! {".gitignore" "secret.clj\n"
                          "a.clj" "(defn widget [] 1)\n"
                          "secret.clj" "(defn widget-secret [] 2)\n"})
          conn (db/open! ":memory:")]
      (try
        (proc/run {:timeout-ms 15000} "git" "-C" root "init" "-q")
        (is (= ["a.clj"] (map :path (ci/search conn root "widget" opts))))
        (finally (db/close conn))))))

(deftest the-tool-answers-with-paths-lines-and-snippets
  (let [root (project! {"src/alpha.clj" "(ns alpha)\n(defn frobnicate [x] x)\n"})
        conn (db/open! ":memory:")
        b (state/new-branch {:id "B1" :problem "p"})
        run (fn [args] (tools-base/run-tool {:tool-name "code_search" :branch b :root root
                                             :conn conn :args args :config {}}))]
    (try
      (with-redefs [proc/available? (constantly false)]
        (let [r (run {:query "frobnicate"})]
          (is (= :neutral (:category r)))
          (is (str/includes? (:result r) "src/alpha.clj:1-2"))
          (is (str/includes? (:result r) "defn frobnicate")))
        (is (str/includes? (:result (run {:query "nothingmatchesthis"})) "No code matches"))
        (is (not= :neutral (:category (run {})))) "a query is required")
      (finally (db/close conn)))))

(deftest the-server-serves-a-search
  (require 'samizdat.server)
  (let [seen (atom nil)]
    (with-redefs [ci/search (fn [_conn root q o] (reset! seen [root q (:limit o)])
                              [{:path "a.clj" :start-line 1 :end-line 2 :snippet "x" :score 1.5}])
                  system/conn (constantly nil)
                  system/config (constantly {:run {:root "/r"}})]
      (let [handler @(resolve 'samizdat.server/handler)
            resp (handler {:request-method :get :uri "/v1/harness/search"
                           :query-string "q=foo&limit=3"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (str (:body resp)) "a.clj"))
        (is (= ["/r" "foo" 3] @seen)))
      (let [handler @(resolve 'samizdat.server/handler)]
        (is (= 400 (:status (handler {:request-method :get :uri "/v1/harness/search"})))
            "no query, no search")))))
