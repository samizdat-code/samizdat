;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.diagnosis
  "Bounded diagnostic cases through symbolic contracts and ordinary probes."
  (:require [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.verify :as verify]
            [samizdat.diagnosis :as diagnosis]
            [samizdat.prompt :as prompt]
            [samizdat.store.diagnoses :as diagnoses]
            [samizdat.userspace :as userspace]))

(defn- msg [vars]
  (prompt/render "diagnosis-tool" vars))

(defn- lsp-probe [contract ctx]
  (diagnosis/execute
   contract
   {:op "diagnostics"}
   (fn [args]
     (let [result ((requiring-resolve 'samizdat.agent.tools/run-tool)
                   (assoc ctx :tool-name "lsp"
                          :args (merge args (select-keys (:args ctx) [:file]))))]
       (if (#{:success :neutral} (:category result))
         (:resource result)
         (throw (ex-info (str (:result result)) {})))))))

(defn- verification-probe [{:keys [command]} ctx]
  (let [result (verify/run-configured (:run-id ctx) command
                                      (:verify-timeout-ms (gates/threshold :diagnosis)))]
    {:fact :verify-green :value (boolean (:green? result))
     :accepted? (some? (:exit result))
     :disputed? (nil? (:exit result))
     :observation result}))

(defn- probe-functions [ctx probes]
  (into {}
        (for [[id definition] probes]
          (hash-map
           id
           (assoc definition
                  :run (fn [_]
                         (let [outcome (case id
                                         :lsp-diagnostics (lsp-probe (:contract definition) ctx)
                                         :verify (verification-probe definition ctx)
                                         {:accepted? false :disputed? true :error "unknown probe"})]
                           (cond
                             (:fact outcome) outcome
                             (= :complete (:status outcome))
                             {:fact :diagnostics-clean
                              :value (empty? (second (first (:facts outcome))))
                              :accepted? true :disputed? false}
                             :else
                             {:accepted? false :disputed? true
                              :error (or (:error outcome) :missing-observation)}))))))))

(defn- unverified-known [known]
  (into {} (for [[fact value] known]
             [fact (if (map? value)
                     (assoc value :accepted? false)
                     {:value value :accepted? false :disputed? false})])))

(defmethod base/run-tool "diagnosis" [{:keys [branch conn run-id] :as ctx}]
  (let [action (some-> (base/arg ctx :action) str)
        policy (userspace/edn-body! :policy "diagnoses")]
    (try
      (case action
        "investigate"
        (if (> (count (or (base/arg ctx :hypotheses)
                          (get-in policy [:hypotheses :verification-failed])))
               (:max-hypotheses (gates/threshold :diagnosis)))
          (base/malformed branch (msg {:error true :kind :too-many-hypotheses}))
          (let [hypotheses (or (base/arg ctx :hypotheses)
                               (get-in policy [:hypotheses :verification-failed]))
                probes (probe-functions ctx (:probes policy))
              requested (some->> (base/arg ctx :probes) (map keyword) set)
              probes (if requested (select-keys probes requested) probes)
              result (diagnosis/investigate
                      {:hypotheses hypotheses
                       :known (unverified-known (or (base/arg ctx :known) {}))
                       :probes probes
                       :budget (min (long (or (base/arg ctx :budget)
                                             (:max-probe-budget (gates/threshold :diagnosis))))
                                    (:max-probe-budget (gates/threshold :diagnosis)))})
              id (diagnoses/create! conn {:run-id run-id :branch-id (:id branch)
                                          :hypotheses hypotheses})
              result (diagnoses/finish-and-return! conn id result)]
          (base/ok branch (msg (assoc result :investigated true
                                      :open (mapv :id (:open result))))
                   :resource result)))

        "read"
        (let [id (str (base/arg ctx :id))
              case (diagnoses/diagnosis conn id)]
          (if (or (nil? case) (not= run-id (:run_id case))
                  (not= (:id branch) (:branch_id case)))
            (base/malformed branch (msg {:error true :kind :missing-resource}))
            (base/ok branch (msg (assoc case :read true
                                        :open (mapv :id (:open_hypotheses case)))))))

        (base/malformed branch (msg {:usage true})))
      (catch Throwable e
        (base/fail branch (msg {:error true :kind (or (ex-message e) :diagnosis)}))))))
