;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.diagnosis-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.diagnosis]
            [samizdat.agent.tools.resource]
            [samizdat.agent.verify :as verify]
            [samizdat.diagnosis :as diagnosis]
            [samizdat.store.db :as db]
            [samizdat.store.diagnoses :as diagnoses]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as userspace]))

(def contract
  '{:name :diagnostics
   :requirements [{:name :op :when {:op "diagnostics"}}]
   :observations [{:name :problem :when {:severity 1 :file ?file} :then '[:diagnostic ?file]}]
   :expect #{:problem}})

(defn- policy-values [policy]
  (let [original userspace/edn-body!]
    (fn [kind name]
      (if (= "diagnoses" name)
        policy
        (original kind name)))))

(deftest contracts-refuse-before-execution-and-separate-observation-failure
  (let [calls (atom 0)
        run (fn [_] (swap! calls inc) {:severity 1 :file "a.clj"})]
    (is (= :refused (:status (diagnosis/execute contract {:op "other"} run))))
    (is (= 0 @calls))
    (is (= :complete (:status (diagnosis/execute contract {:op "diagnostics"} run))))
    (is (= 1 @calls))
    (is (= :missing-observation
           (:status (diagnosis/execute contract {:op "diagnostics"} (constantly {:severity 2})))))
    (is (= :external-error
           (:status (diagnosis/execute contract {:op "diagnostics"}
                                       (fn [_] (throw (ex-info "lsp failed" {})))))))))

(deftest contracts-require-every-prerequisite-and-effect
  (let [contract '{:requirements [{:name :op :when {:op "diagnostics"}}
                                  {:name :file :when {:file ?file}}]
                   :effects [{:name :completed :when {:exit 0}}]
                   :observations []
                   :expect #{}}
        run (fn [_] {:exit 0})]
    (is (= :refused (:status (diagnosis/execute contract {:op "diagnostics"} run))))
    (is (= :missing-effect (:status (diagnosis/execute
                                     (assoc contract :effects [{:name :completed :when {:exit 0}}])
                                     {:op "diagnostics" :file "a.clj"} (constantly {:exit 1})))))))

(deftest known-accepted-facts-are-checked-before-probes
  (let [hypotheses [{:id :h1 :predicted {:x true :y false}}
                    {:id :h2 :predicted {:x false}}]
        known {:x {:value false :accepted? true :disputed? false}}]
    (is (= {:h1 :ruled-out :h2 :open}
           (diagnosis/evaluate hypotheses known)))))

(deftest probe-choice-favors-information-per-policy-cost
  (let [hypotheses [{:id :h1 :predicted {:x true}}
                    {:id :h2 :predicted {:x false}}]
        probes {:x {:cost 1 :run (fn [_] {:fact :x :value true :accepted? true})}
                :y {:cost 50 :run (fn [_] {:fact :y :value true :accepted? true})}}
        result (diagnosis/investigate
                {:hypotheses hypotheses :probes probes :known {} :budget 1})]
    (is (= :resolved (:status result)))
    (is (= [:h1] (mapv :id (:open result))))
    (is (= [:x] (mapv :probe (:trace result))))))

(deftest disputed-evidence-and-exhaustion-leave-cases-open
  (let [hypotheses [{:id :h1 :predicted {:x true}}
                    {:id :h2 :predicted {:x false}}]]
    (is (= :open (:status (diagnosis/investigate
                           {:hypotheses hypotheses :known {} :budget 0}))))
    (let [result (diagnosis/investigate
                  {:hypotheses hypotheses
                   :known {:x {:value true :accepted? true :disputed? true}}
                   :budget 1})]
      (is (= :open (:status result)))
      (is (= 2 (count (:open result)))))))

(deftest configured-verification-produces-a-durable-owner-scoped-case
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "diagnosis"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (try
      (with-redefs [userspace/edn-body!
                    (policy-values
                     {:probes {:verify {:cost 1 :fact :verify-green :command "jolt test"}}
                      :hypotheses {:verification-failed
                                   [{:id :regression :predicted {:verify-green false}}
                                    {:id :environment :predicted {:verify-green true}}]}})
                    verify/run-configured (fn [& _] {:green? true :exit 0})]
        (let [result (base/run-tool
                      {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                       :tool-name "diagnosis" :args {:action "investigate" :budget 1}})
              id (get-in result [:resource :id])
              read-result (base/run-tool
                           {:conn conn :run-id run-id :branch {:id "B1" :role :answerer}
                            :tool-name "resource" :args {:action "read" :uri (str "diagnosis:" id)}})
              other (base/run-tool
                     {:conn conn :run-id run-id :branch {:id "B2" :role :answerer}
                      :tool-name "resource" :args {:action "read" :uri (str "diagnosis:" id)}})
              read-action (base/run-tool
                           {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                            :tool-name "diagnosis" :args {:action "read" :id id}})]
          (is (= :resolved (get-in result [:resource :status])))
          (is (= [:environment] (mapv :id (get-in result [:resource :open]))))
          (let [stored (diagnoses/diagnosis conn id)]
            (is (= ["regression" "environment"] (mapv :id (:hypotheses stored))))
            (is (= ["environment"] (mapv :id (:open_hypotheses stored)))))
          (is (str/includes? (:result read-result) "resolved"))
          (is (str/includes? (:result read-action) "environment"))
          (is (not (str/includes? (:result read-action) "regression")))
          (is (str/includes? (:result other) "access-denied"))))
      (finally (db/close conn)))))

(deftest lsp-contract-probe-uses-structured-diagnostics
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "diagnosis"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (try
      (with-redefs [userspace/edn-body!
                    (policy-values
                     {:probes {:lsp-diagnostics
                               {:cost 1 :fact :diagnostics-clean :contract
                                '{:requirements [{:name :op :when {:op "diagnostics"}}]
                                  :observations [{:name :result :when {:diagnostics ?diagnostics}
                                                  :then [:diagnostics ?diagnostics]}]
                                  :expect #{:result}}}}
                      :hypotheses {:verification-failed
                                   [{:id :clean :predicted {:diagnostics-clean true}}
                                    {:id :dirty :predicted {:diagnostics-clean false}}]}})
                    tools/run-tool (fn [_] {:category :neutral :resource {:diagnostics []}})]
        (let [result (base/run-tool
                      {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                       :tool-name "diagnosis" :args {:action "investigate" :budget 1}})]
          (is (= :resolved (get-in result [:resource :status])) (pr-str result))
          (is (= [:clean] (mapv :id (get-in result [:resource :open]))))))
      (finally (db/close conn)))))

(deftest refused-verification-is-not-accepted-regression-evidence
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "diagnosis"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (try
      (with-redefs [userspace/edn-body!
                    (policy-values
                     {:probes {:verify {:cost 1 :fact :verify-green :command "jolt test"}}
                      :hypotheses {:verification-failed
                                   [{:id :regression :predicted {:verify-green false}}
                                    {:id :environment :predicted {:verify-green true}}]}})
                    verify/run-configured (fn [& _] {:green? false :refused? true})]
        (let [result (base/run-tool
                      {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                       :tool-name "diagnosis" :args {:action "investigate" :budget 1}})]
          (is (= :open (get-in result [:resource :status])))
          (is (= 2 (count (get-in result [:resource :open]))))))
      (finally (db/close conn)))))

(deftest hypothesis-count-is-bounded-before-probing
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "diagnosis"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (try
      (with-redefs [verify/run-configured (fn [& _] (throw (ex-info "must not run" {})))]
        (let [hypotheses (mapv #(hash-map :id (keyword (str "h" %)) :predicted {:x true})
                               (range 5))
              result (base/run-tool
                      {:conn conn :run-id run-id :branch {:id "B1" :role :implementor}
                       :tool-name "diagnosis" :args {:action "investigate" :hypotheses hypotheses}})]
          (is (= :mechanics (:category result)))))
      (finally (db/close conn)))))

(deftest diagnosis-upgrade-backfills-the-remaining-open-set
  (let [conn (db/open! ":memory:")]
    (try
      (db/execute! conn ["ALTER TABLE diagnosis_cases DROP COLUMN open_hypotheses"])
      (db/execute! conn ["PRAGMA user_version = 58"])
      (db/execute! conn ["INSERT INTO diagnosis_cases
                          (id, run_id, branch_id, status, hypotheses, known, trace, created_at, updated_at)
                          VALUES ('case', 'run', 'B1', 'open', '[{\"id\":\"remaining\"}]', '[]', '[]', 'now', 'now')"])
      (is (= 59 (db/migrate! conn)))
      (is (= ["remaining"] (mapv :id (:open_hypotheses (diagnoses/diagnosis conn "case")))))
      (finally (db/close conn)))))
