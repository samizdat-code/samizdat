;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.lsp
  "LSP tool: code navigation via clojure-lsp, and a rename that writes
  through the file tools (karamazov-05z0.20)."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.lsp.client :as client]
            [samizdat.prompt :as prompt]))

(def ^:private usage
  (delay (prompt/render "lsp-tool" {:usage true})))

(defn- uri->path [u]
  (.getPath (java.net.URI. u)))

(defn- loc->str [loc]
  (let [{:keys [line character]} (get-in loc [:range :start])]
    (str (uri->path (:uri loc)) ":" line ":" character)))

(defn- contents->str [cs]
  (str/join "\n" (map #(if (map? %) (:value %) (str %)) (if (map? cs) [cs] cs))))

(defn- render-diag [d]
  (str (get-in d [:range :start :line]) ":" (get-in d [:range :start :character])
       " " (:severity d) ": " (:message d)))

(defn- resolve-file
  "The confined path, or nil when it escapes the project root. The bare
  io/file version let `../` and absolute paths escape the boundary read_file
  enforces, and ensure-open! then slurped whatever they named
  (karamazov-blt.28)."
  [ctx]
  (files/resolve-under-root (:root ctx) (str (base/arg ctx :file))))

(defn- parse-int-or-nil [s]
  (try (Integer/parseInt (str/trim (str s))) (catch Exception _ nil)))

(defn- want-pos [ctx]
  (or (base/missing ctx :line :col)
      (let [line (parse-int-or-nil (base/arg ctx :line))
            col  (parse-int-or-nil (base/arg ctx :col))]
        (when (or (nil? line) (nil? col))
          (str "line and col must be integers. " @usage)))))

(defn- lookup-op [op c file line col]
  (case op
    "definition" (let [loc (client/definition c file line col)]
                   (if loc (loc->str loc) "no definition found"))
    "references" (let [refs (client/references c file line col)]
                   (if (seq refs) (str/join "\n" (map loc->str refs)) "no references found"))
    "hover"      (let [h (client/hover c file line col)]
                   (if h (contents->str (:contents h)) "no hover info"))))

(defn- rel [root path]
  (let [r (str (.getCanonicalPath (java.io.File. (str root))) "/")
        p (.getCanonicalPath (java.io.File. (str path)))]
    (if (str/starts-with? p r) (subs p (count r)) p)))

(defn- rename
  "Rename the symbol at file:line:col to new_name across the project: the
  server's WorkspaceEdit, written file by file through `write_file`, so every
  file is confined, checked and journaled like any edit (karamazov-05z0.20).
  A role that may not write cannot rename."
  [{:keys [branch root] :as ctx} file]
  (let [new-name (some-> (base/arg ctx :new_name) str str/trim not-empty)
        err-pos (want-pos ctx)]
    (cond
      err-pos (base/malformed branch err-pos)
      (nil? new-name) (base/malformed branch (str (base/missing ctx :new_name) "\n\n" @usage))
      (and (:role branch) (not (roles/may-use? (:role branch) "write_file")))
      (base/refusal branch (prompt/render "lsp-tool" {:no-write true}))
      :else
      (if-let [c (client/client-for root)]
        (let [ws (client/rename c file (parse-int-or-nil (base/arg ctx :line))
                                (parse-int-or-nil (base/arg ctx :col)) new-name)
              files (when ws (client/edit-files ws slurp))]
          (if (empty? files)
            (base/fail branch (prompt/render "lsp-tool" {:no-rename true}))
            (loop [b branch, [[path text] & more] (seq files), done []]
              (if-not path
                (base/ok b (prompt/render "lsp-tool" {:renamed true :name new-name
                                                      :files (str/join ", " done)})
                         :progress? true)
                (let [r (base/run-tool (assoc ctx :branch b :tool-name "write_file"
                                              :args {:path (rel root path) :content text}))]
                  (if (#{:failure :mechanics} (:category r))
                    (base/fail (:branch r) (prompt/render "lsp-tool" {:partial true :at (rel root path)
                                                                      :done (str/join ", " done)
                                                                      :why (:result r)}))
                    (do (client/changed! c path text)
                        (recur (:branch r) more (conj done (rel root path))))))))))
        (base/fail branch "could not start clojure-lsp for root")))))

(defmethod base/run-tool "lsp" [{:keys [branch root] :as ctx}]
  ;; Read-only code inspection: :neutral, the same reasoning as grep.
  (if-not (client/available?)
    (base/fail branch "clojure-lsp is not installed; install it to use the lsp tool")
    (let [op (some-> (base/arg ctx :op) str str/trim str/lower-case not-empty)]
      (if-not (#{"definition" "references" "hover" "diagnostics" "rename"} op)
        (base/malformed branch (str "lsp needs a valid :op. " @usage))
        (let [miss-file (base/missing ctx :file)
              file (when-not miss-file (resolve-file ctx))]
          (cond
            miss-file
            (base/malformed branch miss-file)

            (nil? file)
            ;; The same sentence read_file's refusal renders, from the same
            ;; template — one surface for one message class, and editable
            ;; without a rebuild (the no-new-model-facing-prose ratchet).
            (base/malformed branch
                            (prompt/render "file-tool"
                                           {:outside-root true
                                            :path (str (base/arg ctx :file))
                                            :verb "inspected"}))

            (= op "rename") (rename ctx file)

            (= op "diagnostics")
            (let [c (client/client-for root)]
              (if (nil? c)
                (base/fail branch "could not start clojure-lsp for root")
                (let [ds (client/diagnostics c file)]
                  (base/ok branch (if (seq ds) (str/join "\n" (map render-diag ds)) "no problems")))))

            :else
            (let [err-pos (want-pos ctx)]
              (if err-pos
                (base/malformed branch err-pos)
                (let [c (client/client-for root)]
                  (if (nil? c)
                    (base/fail branch "could not start clojure-lsp for root")
                    (base/ok branch (lookup-op op c file
                                               (parse-int-or-nil (base/arg ctx :line))
                                               (parse-int-or-nil (base/arg ctx :col))))))))))))))
