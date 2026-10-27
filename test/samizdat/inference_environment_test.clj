;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-environment-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.bridge]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.environment :as environment]
            [samizdat.llm.client :as llm]
            [samizdat.repl.route :as route]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [jolt.fs :as fs]
            [sci.core :as sci]))

(defn- fake-eval [ctx code _session _timeout]
  (let [handler (:bridge ctx)
        operations (into {}
                         (for [op (:bridge-tools ctx)]
                           [(symbol op)
                            (fn [args]
                              (let [answer (handler {:op op :args args})]
                                (if-let [error (:error answer)]
                                  (throw (ex-info error {}))
                                  (:value answer))))]))]
    (create-ns 'tool)
    (doseq [[op f] operations] (intern 'tool op f))
    (try
      {:ok true :value (pr-str (load-string code))}
      (catch Throwable e {:ok false :error (ex-message e)})
      (finally (remove-ns 'tool)))))

(defn- context []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "environment"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    {:conn conn :run-id run-id :branch {:id "B1"} :llm-adapter :fake :llm-config {}
     :eval-fn fake-eval}))

(deftest root-execution-processes-large-context-through-bounded-operations
  (let [ctx (context)
        content (apply str (repeat 2000 "x"))
        code (str "(do (infer \"classify\")"
                  "    (finish (context-slice 0 4)))")
        calls (atom [])]
    (with-redefs [llm/chat (fn [_ _ messages]
                            (swap! calls conj messages)
                            (if (= 1 (count @calls))
                              {:content code :usage {:total-tokens 3}}
                              {:content "\"classify\"" :usage {:total-tokens 2}}))]
      (let [result (environment/complete
                    ctx {:task "classify and sample"
                         :context content
                         :schema :text
                         :budget (budget/budget {:calls 2 :tokens 100 :depth 2})})]
        (is (= :complete (:status result)))
        (is (= "xxxx" (:value result)))
        (is (= 2 (count @calls)))
        (is (not (str/includes? (pr-str @calls) content)))
        (is (str/includes? (pr-str @calls) "2000"))))
    (db/close (:conn ctx))))

(deftest first-finish-is-terminal
  (let [ctx (context)]
    (with-redefs [llm/chat (fn [_ _ _]
                            {:content "(do (finish \"first\") (finish \"second\"))"})]
      (let [result (environment/complete
                    ctx {:task "finish once" :context "abc"
                         :budget (budget/budget {:calls 1 :tokens 20 :depth 1})})]
        (is (= "first" (:value result)))))
    (db/close (:conn ctx))))

(deftest missing-continuation-state-is-an-explicit-loss
  (let [ctx (context)]
    (is (= :execution-lost
           (try (environment/complete
                 ctx {:task "continue" :context "abc" :continue true
                      :budget (budget/budget {:calls 1 :tokens 20 :depth 1})})
                nil
                (catch clojure.lang.ExceptionInfo e
                  (:inference/error (ex-data e))))))
    (db/close (:conn ctx))))

(deftest continuation-returns-retained-result-without-rerunning
  (let [ctx (context)
        calls (atom 0)]
    (with-redefs [llm/chat (fn [_ _ _]
                            (swap! calls inc)
                            {:content "(finish \"first\")"})]
      (let [allowance (budget/budget {:calls 1 :tokens 20 :depth 1})
            first-result (environment/complete ctx {:task "first" :context "abc"
                                                    :budget allowance})
            continued (environment/complete ctx {:task "continue" :context "abc" :continue true
                                                 :budget allowance})]
        (is (= :complete (:status first-result)))
        (is (= "first" (:value continued)))
        (is (:continued? continued))
        (is (= 1 @calls))))
    (db/close (:conn ctx))))

(deftest slices-and-text-results-are-bounded-and-validated
  (let [ctx (context)]
    (with-redefs [llm/chat (fn [_ _ _]
                            {:content "(finish (context-slice 0 (context-length)))"})]
      (let [result (environment/complete
                    ctx {:task "bounded slice" :context (apply str (repeat 20000 "x"))
                         :schema :text
                         :budget (budget/budget {:calls 1 :tokens 100 :depth 1})})]
        (is (= 16384 (count (:value result)))))
    (with-redefs [llm/chat (fn [_ _ _]
                            {:content "(finish {:not-text true})"})]
      (let [result (environment/complete
                    ctx {:task "not text" :context "abc" :schema :text
                         :budget (budget/budget {:calls 1 :tokens 20 :depth 1})})]
        (is (= :incomplete (:status result)))
        (is (= :schema-invalid (:reason result)))))
    (db/close (:conn ctx)))))

(deftest nonterminating-execution-has-a-deadline-and-a-truthful-trace
  (let [ctx (assoc (context) :eval-fn (fn [& _] {:ok false :timeout? true}))]
    (with-redefs [llm/chat (fn [_ _ _]
                            {:content "(loop [] (recur))" :usage {:total-tokens 3}})]
      (let [result (environment/complete
                    ctx {:task "loop forever" :context "abc"
                         :budget (budget/budget {:calls 1 :tokens 20 :depth 1})})
            traced (first (db/fetch (:conn ctx) ["SELECT status, usage FROM inference_frames"]))]
        (is (= :incomplete (:status result)))
        (is (= :timeout (:reason result)))
        (is (= :incomplete (:status (samizdat.store.executions/execution (:conn ctx) (:trace-id result)))))
        (is (= "incomplete" (:status traced)))
        (is (str/includes? (:usage traced) "total-tokens"))))
    (db/close (:conn ctx))))

(deftest incomplete-execution-continues-explicitly-in-the-same-session
  (let [ctx (context)
        replies (atom ["(do (def retained 40) (context-length))"
                       "(finish (str (+ retained 2)))"])]
    (with-redefs [llm/chat (fn [_ _ _]
                            (let [reply (first @replies)]
                              (swap! replies rest)
                              {:content reply}))
                  route/session-of (fn [_] "retained-session")]
      (let [allowance (budget/budget {:calls 2 :tokens 100 :depth 1})
            first-result (environment/complete ctx {:task "resume" :context "abc"
                                                    :budget allowance})
            continued (environment/complete ctx {:task "resume" :context "abc" :continue true
                                                 :budget allowance})]
        (is (= :incomplete (:status first-result)))
        (is (= :complete (:status continued)))
        (is (= "42" (:value continued)))
        (is (:continued? continued))))
    (db/close (:conn ctx))))

(deftest search-results-locate-the-next-slice
  (let [ctx (context)]
    (with-redefs [llm/chat (fn [_ _ _]
                            {:content "(finish (pr-str (context-search \"bc\")))"})]
      (let [result (environment/complete ctx {:task "search" :context "abcabc" :schema :text
                                              :budget (budget/budget {:calls 1 :tokens 20 :depth 1})})]
        (is (str/includes? (:value result) ":offset 1"))
        (is (str/includes? (:value result) ":next-offset 3"))))
    (db/close (:conn ctx))))

(deftest root-program-runs-through-the-real-project-image-bridge
  (let [root (str (fs/create-temp-dir))
        ctx (assoc (context) :root root :role :supervisor :eval-fn nil)]
    (try
      (with-redefs [llm/chat (fn [_ _ _]
                              {:content "(finish (context-slice 0 2))" :usage {:total-tokens 2}})]
        (let [result (environment/complete ctx {:task "project image" :context "abc" :schema :text
                                                :budget (budget/budget {:calls 1 :tokens 50 :depth 1})})]
          (is (= :complete (:status result)) (pr-str result))
          (is (= "ab" (:value result)))))
      (with-redefs [llm/chat (fn [_ _ _]
                              {:content (str "(do (spit \"" root "/escape.txt\" \"x\")"
                                             " (finish \"escaped\"))")})]
        (let [result (environment/complete ctx {:task "no effects" :context "abc" :schema :text
                                                :budget (budget/budget {:calls 1 :tokens 50 :depth 1})})]
          (is (= :incomplete (:status result)))
          (is (not (fs/exists? (str root "/escape.txt"))))))
      (let [replies (atom ["(do (def retained (infer \"classify\")) (context-length))"
                           "classified"
                           "(finish retained)"])
            calls (atom 0)]
        (with-redefs [llm/chat (fn [_ _ _]
                                (swap! calls inc)
                                (let [reply (first @replies)]
                                  (swap! replies rest)
                                  {:content reply}))]
          (let [allowance (budget/budget {:calls 3 :tokens 100 :depth 2})
                first-result (environment/complete
                              ctx {:task "retained operations" :context "abc"
                                   :budget allowance})
                _ (with-redefs [route/bridge-policy
                                (constantly {:enabled? true :tools #{"grep"}
                                             :cells? false :call-timeout-ms 5000})]
                    (is (:ok (route/eval-for (assoc ctx :bridge (fn [_] {:value "ordinary"}))
                                             "(+ 1 2)" nil 15000))))
                continued (environment/complete
                           ctx {:task "retained operations" :context "abc" :continue true
                                :budget allowance})]
            (is (= :incomplete (:status first-result)))
            (is (= :complete (:status continued)) (pr-str continued))
            (is (= "classified" (:value continued)))
            (is (= 3 @calls) "continuation does not repeat the completed nested inference"))))
      (with-redefs [llm/chat (fn [_ _ _] {:content "(context-length)"})]
        (is (= :incomplete
               (:status (environment/complete
                         ctx {:task "lost session" :context "abc"
                              :budget (budget/budget {:calls 1 :tokens 50 :depth 1})}))))
        (route/release! root)
        (is (= :execution-lost
               (try
                 (environment/complete
                  ctx {:task "lost session" :context "abc" :continue true
                       :budget (budget/budget {:calls 1 :tokens 50 :depth 1})})
                 (catch clojure.lang.ExceptionInfo e
                   (:inference/error (ex-data e)))))))
      (finally
        (route/release! root)
        (db/close (:conn ctx))))))

(deftest run-teardown-forgets-inference-continuations
  (let [ctx (context)]
    (with-redefs [llm/chat (fn [_ _ _] {:content "(finish \"done\")"})]
      (let [allowance (budget/budget {:calls 2 :tokens 50 :depth 1})]
        (is (= :complete
               (:status (environment/complete ctx {:task "teardown" :context "abc"
                                                   :budget allowance}))))
        ((requiring-resolve 'samizdat.agent.run-state/run-ended!) (:run-id ctx))
        (is (= :execution-lost
               (try
                 (environment/complete ctx {:task "teardown" :context "abc" :continue true
                                            :budget allowance})
                 (catch clojure.lang.ExceptionInfo e
                   (:inference/error (ex-data e))))))))
    (db/close (:conn ctx))))
