;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.lsp
  "LSP navigation and semantic proposals published as resource changes."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.lsp.client :as client]
            [samizdat.lsp.edit :as edit]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]))

(def ^:private usage
  (delay (prompt/render "lsp-tool" {:usage true})))

(defn- uri->path [u]
  (.getPath
   (java.net.URI.
    (if (keyword? u)
      (if-let [scheme (namespace u)]
        (str scheme
             (when-not (or (str/ends-with? scheme ":")
                           (str/starts-with? (name u) ":"))
               ":")
             (name u))
        (name u))
      (str u)))))

(defn- loc->str [loc]
  (let [{:keys [line character]} (get-in loc [:range :start])]
    (str (uri->path (:uri loc)) ":" line ":" character)))

(defn- contents->str [cs]
  (str/join "\n" (map #(if (map? %) (:value %) (str %)) (if (map? cs) [cs] cs))))

(defn- render-diag [d]
  (str (get-in d [:range :start :line]) ":" (get-in d [:range :start :character])
       " " (:severity d) ": " (:message d)))

(defn- resolve-file
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

(defn- observation-for [registry ctx uri]
  (if (str/starts-with? (str uri) "workspace:")
    (resource/observe registry (str uri) ctx)
    (let [path (uri->path uri)
          confined (files/resolve-under-root (:root ctx) path)
          _ (when-not confined
              (throw (ex-info "outside workspace"
                              {:resource/error :outside-root :uri uri})))
          workspace-uri (str "workspace:" (rel (:root ctx) confined))]
      (resource/observe registry workspace-uri ctx))))

(defn- members [registry ctx workspace-edit]
  (mapv
   (fn [member]
     (when (and (= :create (:operation member))
                (some? (:revision member))
                (not (:overwrite? member)))
       (throw (ex-info "resource exists"
                       {:resource/error :resource-exists :uri (:uri member)})))
     (let [member (if (str/starts-with? (str (:uri member)) "workspace:")
                    member
                    (let [observed (observation-for registry ctx (:uri member))]
                      (merge observed member
                             {:uri (:uri observed)
                              :identity (:identity observed)
                              :metadata (merge (:metadata member) (:metadata observed))
                              :base-revision (:revision observed)})))]
       (if-let [target (:replacement-uri member)]
         (let [observation (observation-for registry ctx target)]
           (when (and (some? (:revision observation)) (not (:overwrite? member)))
             (throw (ex-info "resource exists"
                             {:resource/error :resource-exists :uri target})))
           (assoc member :replacement-uri (:uri observation)
                  :target-revision (:revision observation)))
         member)))
   (edit/workspace-edit->members workspace-edit #(observation-for registry ctx %))))

(defn- synchronize! [client members]
  (doseq [{:keys [operation metadata replacement replacement-uri]} members]
    (case operation
      :rename (client/changed! client (:target-path metadata) replacement)
      :delete nil
      (client/changed! client (:path metadata) replacement)))
  members)

(defn- semantic [ctx file workspace-edit label]
  (let [branch (:branch ctx)
        registry (resource-tool/registry-for ctx)
        members (members registry ctx workspace-edit)]
    (if (empty? members)
      (base/fail branch (prompt/render "lsp-tool" {:no-rename true}))
      (let [proposal (resource-tool/stage-proposal!
                      ctx members
                      {:after-commit #(synchronize! (client/client-for (:root ctx)) members)})]
        (if (base/arg ctx :preview)
          (base/ok branch (prompt/render "lsp-tool" {:staged true :id (:id proposal)})
                   :resource {:proposal-id (:id proposal)})
          (let [result (changes/apply! registry ctx proposal)]
            (when (= :committed (:status result))
              (synchronize! (client/client-for (:root ctx)) members))
            (base/ok branch
                     (prompt/render "lsp-tool"
                                    (assoc result :show-status true :label label
                                           :files (str/join ", " (map :identity members))))
                     :category (if (= :committed (:status result)) :success :mechanics)
                     :progress? (= :committed (:status result))
                     :resource (assoc result :members members))))))))

(defn- writing! [ctx f]
  (if (and (:role (:branch ctx))
           (not (roles/may-use? (:role (:branch ctx)) "write_file")))
    (base/refusal (:branch ctx) (prompt/render "lsp-tool" {:no-write true}))
    (try
      (f)
      (catch clojure.lang.ExceptionInfo e
        (base/malformed (:branch ctx)
                        (prompt/render "lsp-tool"
                                       {:error true :kind (name (or (:resource/error (ex-data e))
                                                                    (:lsp-edit/error (ex-data e))
                                                                    :invalid-edit))}))))))

(defn- rename [ctx file]
  (let [new-name (some-> (base/arg ctx :new_name) str str/trim not-empty)
        err-pos (want-pos ctx)]
    (cond
      err-pos (base/malformed (:branch ctx) err-pos)
      (nil? new-name) (base/malformed (:branch ctx)
                                     (str (base/missing ctx :new_name) "\n\n" @usage))
      :else
      (writing! ctx
                #(semantic ctx file
                           (client/rename (client/client-for (:root ctx)) file
                                          (parse-int-or-nil (base/arg ctx :line))
                                          (parse-int-or-nil (base/arg ctx :col)) new-name)
                           new-name)))))

(defn- code-action [ctx file]
  (writing!
   ctx
   (fn []
     (let [c (client/client-for (:root ctx))
           actions (client/code-actions
                    c file
                    {:start {:line (parse-int-or-nil (base/arg ctx :line))
                             :character (parse-int-or-nil (base/arg ctx :col))}
                     :end {:line (parse-int-or-nil (base/arg ctx :end-line))
                           :character (parse-int-or-nil (base/arg ctx :end-col))}}
                    {:diagnostics []})
           title (base/arg ctx :title)
           action (or (when title (first (filter #(= title (:title %)) actions)))
                      (first (filter :edit actions))
                      (first actions))
           action (if (and action (not (:edit action)) (:data action))
                    (client/resolve-code-action c action)
                    action)]
       (semantic ctx file (:edit action) "code action")))))

(defn- file-uri [path]
  (str "file://" (.getAbsolutePath (java.io.File. (str path)))))

(defn- move [ctx file]
  (let [new-path (some-> (base/arg ctx :new-path) str str/trim not-empty)
        target (when new-path (files/resolve-under-root (:root ctx) new-path))]
    (if-not target
      (base/malformed (:branch ctx) @usage)
      (writing!
       ctx
       (fn []
         (let [proposal (client/prepare-file-rename (client/client-for (:root ctx)) file target)
               rename {:kind "rename" :oldUri (file-uri file) :newUri (file-uri target)}]
           (semantic ctx file
                     (update proposal :documentChanges
                             #(vec (concat % [rename])))
                     "move")))))))

(defmethod base/run-tool "lsp" [{:keys [branch root] :as ctx}]
  (if-not (client/available?)
    (base/fail branch "clojure-lsp is not installed; install it to use the lsp tool")
    (let [op (some-> (base/arg ctx :op) str str/trim str/lower-case not-empty)]
      (if-not (#{"definition" "references" "hover" "diagnostics" "rename"
                 "code_action" "move"} op)
        (base/malformed branch (str "lsp needs a valid :op. " @usage))
        (let [miss-file (base/missing ctx :file)
              file (when-not miss-file (resolve-file ctx))]
          (cond
            miss-file
            (base/malformed branch miss-file)

            (nil? file)
            (base/malformed branch
                            (prompt/render "file-tool"
                                           {:outside-root true
                                            :path (str (base/arg ctx :file))
                                            :verb "inspected"}))

            (= op "rename") (rename ctx file)
            (= op "code_action") (code-action ctx file)
            (= op "move") (move ctx file)

            (= op "diagnostics")
            (let [c (client/client-for root)]
              (if (nil? c)
                (base/fail branch "could not start clojure-lsp for root")
                (let [ds (client/diagnostics c file)]
                  (base/ok branch (if (seq ds) (str/join "\n" (map render-diag ds)) "no problems")
                           :resource {:diagnostics ds}))))

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
