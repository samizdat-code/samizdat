;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.surgeon-edit
  "Exact structural Clojure edits published as revisioned resource changes."
  (:require [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.edit :as edit]
            [samizdat.surgeon.tool :as tool]))

(defn- msg [vars]
  (prompt/render "surgeon-edit-tool" vars))

(defn- canonical-edits [ctx]
  (let [edits (base/listed (base/arg ctx :edits))]
    (if (vector? edits)
      (mapv (fn [request]
              (if (and (map? request) (string? (:file request)))
                (if-let [file (tool/canonical-file ctx (:file request))]
                  (assoc request :file file)
                  (throw (ex-info "outside-root"
                                  {:resource/error :outside-root
                                   :uri (str resource/workspace-prefix (:file request))})))
                request))
            edits)
      edits)))

(defn- run [ctx]
  (let [branch (:branch ctx)
        registry (resource-tool/registry-for ctx)
        edits (canonical-edits ctx)
        observations (tool/observe-files ctx edits)
        sources (into {} (map (fn [[file observation]] [file (:content observation)]))
                      observations)
        plan (edit/plan-replacements sources edits)
        files (vec (distinct (map :file edits)))
        observed (mapv observations files)
        replacements (into {}
                           (map (fn [[file future]]
                                  [(str resource/workspace-prefix file) future]))
                           (:future-sources plan))
        members (workspace/stage-workspace (get registry :workspace) ctx observed replacements)
        proposal (resource-tool/stage-proposal! ctx members)
        details {:members members :edits (:edits plan)}]
    (if (base/arg ctx :preview)
      (base/ok branch (msg {:staged true :id (:id proposal)})
               :resource (assoc details :proposal-id (:id proposal)))
      (let [result (changes/apply! registry ctx proposal)
            committed (= :committed (:status result))]
        (base/ok branch (msg (assoc result :show-status true
                                    :files (count members)))
                 :category (if committed :success :mechanics)
                 :progress? committed
                 :resource (merge details result))))))

(defmethod base/run-tool "edit_clojure" [{:keys [branch] :as ctx}]
  (if (and (:role branch)
           (not (roles/may-use? (:role branch) "write_file")))
    (base/refusal branch (msg {:no-write true}))
    (try
      (run ctx)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              kind (or (:surgeon/error data) (:resource/error data))]
          (if kind
            (base/malformed branch (msg {:refused true :kind (name kind)}))
            (throw e))))
      (catch Throwable _
        (base/fail branch (msg {:refused true :kind "internal-error"}))))))
