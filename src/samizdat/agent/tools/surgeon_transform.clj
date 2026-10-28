;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.surgeon-transform
  "Bounded structural Clojure transforms through resource publication."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.prompt :as prompt]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.tool :as tool]
            [samizdat.surgeon.transform :as transform]))

(defn- msg [vars]
  (prompt/render "surgeon-transform-tool" vars))

(defn- refuse! [kind data]
  (throw (ex-info (name kind) (merge {:surgeon/error kind} data))))

(defn- checked-request [ctx policy]
  (let [file (base/arg ctx :file)
        expression (base/arg ctx :expression)
        expect (base/arg ctx :expect)
        commit (base/arg ctx :commit)]
    (when-not (and (string? file) (not (str/blank? file))
                   (string? expression) (not (str/blank? expression)))
      (refuse! :invalid-transform-request {}))
    (when-not (and (map? expect)
                   (= #{:matches :max_changed_characters} (set (keys expect)))
                   (pos-int? (:matches expect))
                   (<= (:matches expect) (:max-transform-matches policy))
                   (pos-int? (:max_changed_characters expect))
                   (<= (:max_changed_characters expect)
                       (:max-transform-changed-characters policy)))
      (refuse! :invalid-transform-expectation {}))
    (when-not (or (nil? commit) (boolean? commit))
      (refuse! :invalid-transform-request {:field :commit}))
    {:file file :expression expression :expect expect}))

(defn- checked-observation [ctx file policy]
  (let [observation (get (tool/observe-files ctx [{:file file}]) file)]
    (when-not (= :workspace (:resolver observation))
      (refuse! :unsupported-resource {:file file}))
    (when-not (string? (:content observation))
      (refuse! :missing-resource {:file file}))
    (when (> (count (:content observation)) (:max-source-chars policy))
      (refuse! :source-limit {:file file
                              :actual (count (:content observation))
                              :limit (:max-source-chars policy)}))
    observation))

(defn- run [ctx]
  (let [branch (:branch ctx)
        registry (resource-tool/registry-for ctx)
        policy (gates/threshold :clj-surgeon)
        request (checked-request ctx policy)
        file (or (tool/canonical-file ctx (:file request))
                 (refuse! :outside-root {:file (:file request)}))
        request (assoc request :file file)
        observation (checked-observation ctx file policy)
        plan (transform/plan (:content observation)
                             (assoc request
                                    :max-source-chars (:max-source-chars policy)
                                    :max-query-steps (:max-forms policy)
                                    :max-matches (:max-transform-matches policy)))
        commit (true? (base/arg ctx :commit))]
    (when (or (:comment-bearing-selection? plan)
              (:uneval-bearing-selection? plan))
      (refuse! :lossless-commit-refused {:matches (:matches plan)}))
    (let [replacements {(str resource/workspace-prefix file) (:future-source plan)}
          members (workspace/stage-workspace (get registry :workspace) ctx
                                             [observation] replacements)
          details (assoc (select-keys plan [:matches :changed-characters :edits
                                            :comment-bearing-selection?])
                         :file file :members members)
          rendered (pr-str details)]
      (when (> (count rendered) (:max-result-chars policy))
        (refuse! :result-limit {:actual (count rendered)
                                :limit (:max-result-chars policy)}))
      (let [proposal (resource-tool/stage-proposal! ctx members)]
        (if-not commit
          (base/ok branch (msg {:staged true :id (:id proposal)})
                   :resource (assoc details :proposal-id (:id proposal)))
          (let [result (changes/apply! registry ctx proposal)
                committed (= :committed (:status result))]
            (base/ok branch (msg (assoc result :show-status true))
                     :category (if committed :success :mechanics)
                     :progress? committed
                     :resource (merge details result))))))))

(defmethod base/run-tool "transform_clojure" [{:keys [branch] :as ctx}]
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
