;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.code-search
  "The `code_search` tool: the project's source ranked by BM25 for a query
  of words (karamazov-k7gl.7). Mechanism is samizdat.code-index; this is the
  dispatch and the words, which are prompts/code-search-tool.md.

  :neutral, like grep — finding a place establishes nothing."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.code-index :as code-index]
            [samizdat.prompt :as prompt]
            [samizdat.security.exposure :as exposure]))

(defn- msg [ctx] (prompt/render "code-search-tool" ctx))

(defmethod base/run-tool "code_search" [{:keys [branch conn root config] :as ctx}]
  (if-let [m (base/missing ctx :query)]
    (base/malformed branch (str m "\n\n" (msg {:usage true})))
    (let [query (str (base/arg ctx :query))
          opts (code-index/options config)
          opts (assoc opts :limit (code-index/clamp-limit (base/arg ctx :limit) opts))
          hits (try (code-index/search conn (or root ".") query opts)
                    (catch Throwable e {:error (ex-message e)}))]
      (cond
        (map? hits) (base/fail branch (msg {:failed true :detail (:error hits)}))
        (str/blank? (code-index/fts-query query)) (base/malformed branch (msg {:usage true}))
        :else
        ;; Hits in files the project keeps from this provider are dropped
        ;; (samizdat.security.exposure), as grep's are.
        (let [hits (vec (exposure/visible ctx hits))]
          (if (empty? hits)
            (base/ok branch (msg {:empty true :query query}))
            (base/ok branch (msg {:found true :query query :count (count hits)
                                  :plural (not= 1 (count hits))
                                  :hits (mapv (fn [{:keys [path start-line end-line score snippet]}]
                                                {:path path :lines (str start-line "-" end-line)
                                                 :score (format "%.2f" (double score))
                                                 :snippet snippet})
                                              hits)}))))))))
