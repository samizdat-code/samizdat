;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.surgeon-apply
  "Transactional structural Clojure changes through resource publication."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.binding-rename :as binding-rename]
            [samizdat.surgeon.intent-transaction :as intent]
            [samizdat.surgeon.tool :as tool]))

(defn- msg [vars]
  (prompt/render "surgeon-apply-tool" vars))

(defn- refuse! [kind data]
  (throw (ex-info (name kind) (merge {:surgeon/error kind} data))))

(defn- canonical-changes [ctx policy]
  (let [changes (base/listed (base/arg ctx :changes))]
    (when (and (vector? changes) (> (count changes) (:max-changes policy)))
      (refuse! :change-limit {:actual (count changes) :limit (:max-changes policy)}))
    (if (vector? changes)
      (mapv (fn [change]
              (if (and (map? change) (vector? (:in change)))
                (update change :in
                        (fn [files]
                          (mapv (fn [file]
                                  (or (tool/canonical-file ctx file)
                                      (refuse! :outside-root {:file file})))
                                files)))
                change))
            changes)
      changes)))

(defn- checked-observations [ctx changes policy]
  (let [requests (mapcat (fn [change]
                           (map (fn [file] {:file file}) (:in change)))
                         changes)
        files (vec (distinct (map :file requests)))]
    (when (> (count files) (:max-files policy))
      (refuse! :file-limit {:actual (count files) :limit (:max-files policy)}))
    (let [observations (tool/observe-files ctx requests)]
      (doseq [[file observation] observations]
        (when-not (= :workspace (:resolver observation))
          (refuse! :unsupported-resource {:file file}))
        (when (nil? (:content observation))
          (refuse! :missing-resource {:file file}))
        (when (> (count (:content observation)) (:max-source-chars policy))
          (refuse! :source-limit {:file file :actual (count (:content observation))
                                  :limit (:max-source-chars policy)})))
      observations)))

(defn- binding-analysis [ctx changes sources policy]
  (let [files (vec (distinct (mapcat :in (filter #(= :rename-binding (first (:do %))) changes))))]
    (into {} (map (fn [file]
                    [file (binding-rename/analyze-source
                            file (get sources file)
                            {:root (:root ctx)
                             :timeout-ms (:analysis-timeout-ms policy)
                             :max-result-chars (:max-result-chars policy)})])
                  files))))

(defn- run [ctx]
  (let [branch (:branch ctx)
        registry (resource-tool/registry-for ctx)
        policy (gates/threshold :clj-surgeon)
        changes (canonical-changes ctx policy)
        observations (checked-observations ctx changes policy)
        sources (into {} (map (fn [[file observation]] [file (:content observation)]))
                      observations)
        analysis (binding-analysis ctx changes sources policy)
        plan (intent/compile-changes sources {:changes changes :expect (base/arg ctx :expect)}
                                     {:binding-analysis analysis})
        _ (when (or (> (get-in plan [:counts :edits]) (:max-edits policy))
                    (> (count (:edits plan)) (:max-edits policy)))
            (refuse! :edit-limit {:actual (get-in plan [:counts :edits])
                                  :physical (count (:edits plan)) :limit (:max-edits policy)}))
        files (vec (distinct (mapcat :in changes)))
        observed (mapv observations files)
        replacements (into {} (map (fn [[file future]]
                                     [(str resource/workspace-prefix file) future]))
                           (:future-sources plan))
        members (workspace/stage-workspace (get registry :workspace) ctx observed replacements)
        details {:members members :edits (:edits plan)
                 :canonical-effects (:canonical-effects plan)
                 :counts (:counts plan) :changes (:changes plan)}
        rendered (pr-str details)]
    (when (> (count rendered) (:max-result-chars policy))
      (refuse! :result-limit {:actual (count rendered) :limit (:max-result-chars policy)}))
    (let [proposal (resource-tool/stage-proposal! ctx members)]
      (if (base/arg ctx :preview)
        (base/ok branch (msg {:staged true :id (:id proposal)})
                 :resource (assoc details :proposal-id (:id proposal)))
        (let [result (changes/apply! registry ctx proposal)
              committed (= :committed (:status result))]
          (base/ok branch (msg (assoc result :show-status true :files (count members)))
                   :category (if committed :success :mechanics)
                   :progress? committed
                   :resource (merge details result)))))))

(defmethod base/run-tool "apply_clojure_changes" [{:keys [branch] :as ctx}]
  (if (and (:role branch)
           (not (roles/may-use? (:role branch) "write_file")))
    (base/refusal branch (msg {:no-write true}))
    (try
      (run ctx)
      (catch clojure.lang.ExceptionInfo error
        (let [data (ex-data error)
              kind (or (:surgeon/error data) (:resource/error data))]
          (if kind
            (base/malformed branch (msg {:refused true :kind (name kind)}))
            (throw error))))
      (catch Throwable _
        (base/fail branch (msg {:refused true :kind "internal-error"}))))))
