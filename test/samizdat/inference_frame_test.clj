;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-frame-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.frame :as frame]
            [samizdat.inference.trace :as trace]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.executions :as executions]
            [samizdat.store.runs :as runs]))

(defn- context []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "frame"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    {:conn conn :run-id run-id :root "/tmp" :branch {:id "B1" :turn 3}
     :llm-adapter :fake :llm-config {:model "test-model"}
     :messages [{:role "user" :content "parent-secret"}]}))

(def answer-schema
  {:type "object" :properties {"answer" {:type "string"}}
   :required ["answer"] :additionalProperties false})

(deftest frames-use-explicit-views-and-repair-within-budget
  (let [ctx (context)
        replies (atom [{:content "{\"answer\":4}" :usage {:total-tokens 4}}
                       {:content "{\"answer\":\"ok\"}" :usage {:total-tokens 5}}])
        calls (atom [])]
    (try
      (with-redefs [llm/chat (fn [_ _ messages]
                              (swap! calls conj messages)
                              (let [reply (first @replies)]
                                (swap! replies rest)
                                reply))]
        (let [result (frame/infer ctx {:task "answer"
                                       :views [{:label "source" :content "evidence"}]
                                       :schema answer-schema
                                       :parent-id "parent-trace"
                                       :budget (budget/budget {:calls 2 :tokens 100 :depth 1})})
              side-calls (db/fetch (:conn ctx) ["SELECT * FROM side_calls"])
              attempts (db/fetch (:conn ctx) ["SELECT * FROM inference_attempts ORDER BY attempt"])
              parent-id (-> (db/fetch (:conn ctx)
                                      ["SELECT parent_id FROM inference_frames"])
                            first :parent_id)]
          (is (= :complete (:status result)))
          (is (= {:answer "ok"} (:value result)))
          (is (= 2 (count @calls)))
          (is (not (str/includes? (pr-str @calls) "parent-secret")))
          (is (str/includes? (pr-str @calls) "evidence"))
          (is (= 2 (count side-calls)))
          (is (some? (:trace-id result)))
          (is (= :complete (:status (executions/execution (:conn ctx) (:trace-id result)))))
          (is (= [(str "inference:" (:trace-id result))]
                 (:artifacts (executions/receipt (:conn ctx) (:trace-id result)))))
          (is (str/includes? (pr-str (second @calls)) "answer"))
          (is (str/includes? (pr-str (second @calls)) "evidence"))
          (is (str/includes? (pr-str (second @calls)) "schema-invalid"))
          (is (= 9 (reduce + (map :total_tokens side-calls))))
          (is (= 2 (count attempts)))
          (is (str/includes? (:messages (first attempts)) "evidence"))
          (is (str/includes? (:response (first attempts)) "\"answer\":4"))
          (is (= "parent-trace" parent-id))))
      (finally
        (db/close (:conn ctx))))))

(deftest exhausted-frames-return-incomplete-evidence
  (let [ctx (context)]
    (try
      (let [result (frame/infer ctx {:task "answer"
                                     :views []
                                     :schema answer-schema
                                     :budget (budget/budget {:calls 0 :tokens 100 :depth 1})})]
        (is (= :incomplete (:status result)))
        (is (some? (:trace-id result)))
        (is (= :budget-exhausted (:reason result))))
      (finally (db/close (:conn ctx))))))

(deftest frames-refuse-undeclared-capabilities-before-calling-tools
  (let [ctx (context)]
    (try
      (is (= :unsupported-capability
             (try (frame/infer ctx {:task "read"
                                    :views [{:uri "workspace:secret"}]
                                    :capabilities #{}
                                    :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    (:inference/error (ex-data e))))))
      (finally (db/close (:conn ctx))))))

(deftest uri-views-use-the-callers-authorized-resolver
  (let [ctx (assoc (context) :resolve-view (fn [_] "resolved resource"))
        seen (atom nil)]
    (try
      (with-redefs [llm/chat (fn [_ _ messages]
                              (reset! seen messages)
                              {:content "ok"})]
        (frame/infer ctx {:task "read"
                          :views [{:uri "workspace:src/a.clj" :content "untrusted"}]
                          :schema :text
                          :budget (budget/budget {:calls 1 :tokens 100 :depth 1})}))
      (is (str/includes? (pr-str @seen) "resolved resource"))
      (is (not (str/includes? (pr-str @seen) "untrusted")))
      (is (not (str/includes? (pr-str @seen) "JSON")))
      (finally (db/close (:conn ctx))))))

(deftest provider-failures-are-accounted-and-finish-their-trace
  (let [ctx (context)]
    (try
      (with-redefs [llm/chat (fn [_ _ _] (throw (ex-info "provider down" {})))]
        (let [result (frame/infer ctx {:task "answer" :schema :text
                                       :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})]
          (is (= :incomplete (:status result)))
          (is (= 1 (count (db/fetch (:conn ctx) ["SELECT * FROM side_calls"]))))
          (is (= "incomplete"
                 (:status (first (db/fetch (:conn ctx) ["SELECT status FROM inference_frames"])))))))
      (finally (db/close (:conn ctx))))))

(deftest cancellation-is-propagated-accounted-and-traced
  (let [ctx (context)]
    (try
      (with-redefs [llm/chat (fn [_ _ _] (throw (InterruptedException. "cancelled")))]
        (is (= ::cancelled
               (try (frame/infer ctx {:task "answer" :schema :text
                                      :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})
                    (catch InterruptedException _ ::cancelled))))
        (is (= 1 (count (db/fetch (:conn ctx) ["SELECT * FROM side_calls"]))))
        (is (= "cancelled"
               (:status (first (db/fetch (:conn ctx) ["SELECT status FROM inference_frames"]))))))
      (finally (db/close (:conn ctx))))))

(deftest malformed-output-gets-one-repair-not-the-whole-call-allowance
  (let [ctx (context)
        calls (atom 0)]
    (try
      (with-redefs [llm/chat (fn [_ _ _]
                              (swap! calls inc)
                              {:content "not json" :usage {:total-tokens 1}})]
        (let [result (frame/infer ctx {:task "answer" :schema answer-schema
                                       :budget (budget/budget {:calls 4 :tokens 100 :depth 1})})]
          (is (= :incomplete (:status result)))
          (is (= 2 @calls))))
      (finally (db/close (:conn ctx))))))

(deftest revived-frames-create-a-new-trace-with-the-claimed-id
  (let [ctx (context)]
    (try
      (with-redefs [llm/chat (fn [_ _ _] {:content "first" :usage {:total-tokens 1}})]
        (let [initial (frame/infer ctx {:task "answer" :schema :text
                                        :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})
              old-id (:trace-id initial)
              new-id (executions/revive! (:conn ctx) old-id {:reason "retry provider response"})]
          (is (= :safe-restart (:recovery (executions/execution (:conn ctx) new-id))))
          (with-redefs [llm/chat (fn [_ _ _] {:content "second" :usage {:total-tokens 1}})]
            (let [revived (frame/infer (assoc ctx :execution-id new-id)
                                       {:task "answer" :schema :text
                                        :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})]
              (is (= "second" (:value revived)))
              (is (= new-id (:trace-id revived)))
              (is (= "complete" (:status (trace/frame (:conn ctx) new-id))))
              (is (= old-id (:revived_from (executions/execution (:conn ctx) new-id))))))))
      (finally (db/close (:conn ctx))))))

(deftest custom-frame-consumers-require-a-recovery-decision
  (let [ctx (context)]
    (try
      (with-redefs [llm/chat (fn [_ _ _] {:content "done"})]
        (let [result (frame/infer ctx {:task "digest" :schema :text :kind :digest
                                       :messages-fn (fn [_] [{:role "user" :content "custom"}])
                                       :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})]
          (is (= :unknown (:recovery (executions/execution (:conn ctx) (:trace-id result)))))
          (is (= :decision-required (executions/continuity (:conn ctx) (:trace-id result))))))
      (finally (db/close (:conn ctx))))))
