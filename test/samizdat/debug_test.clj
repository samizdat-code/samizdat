;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.debug-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.debug]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.approval :as approval]
            [samizdat.debug.dap :as dap]
            [samizdat.debug.sessions :as sessions]
            [samizdat.store.db :as db]
            [samizdat.store.executions :as executions]
            [samizdat.store.runs :as runs])
  (:import [java.io BufferedInputStream ByteArrayInputStream ByteArrayOutputStream]))

(def policy
  {:program "/fake/dap" :adapter-args ["--stdio"] :timeout-ms 100
   :default-stack-frames 20 :default-variables 50 :default-event-page 1 :max-header-chars 8192
   :max-sessions 4 :max-owner-sessions 2 :max-body-bytes 1024
   :max-events 8 :max-pending 2 :max-result-chars 4000
   :max-breakpoints 10 :max-page 2})

(defn- fixture []
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "debug"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    {:conn conn :run-id run-id :root "/tmp" :branch {:id "B1" :role :implementor}}))

(defn- fake-client [calls]
  {:next-id (atom 0) :pending (atom {}) :events (atom []) :event-waiters (atom [])
   :policy policy
   :request-fn (fn [client method params]
                 (swap! calls conj [method params])
                 (case method
                   "initialize" {:capabilities {:supportsStepBack false}}
                   ("launch" "attach") (do (dap/record-event! client {:event "initialized" :body {}})
                                           {})
                   "threads" {:threads [{:id 7 :name "main"}]}
                   "stackTrace" {:stackFrames [{:id 11 :name "frame"}]}
                   "scopes" {:scopes [{:name "locals" :variablesReference 22}]}
                   "variables" {:variables [{:name "x" :value "1"}]}
                   "evaluate" {:result "1"}
                   {}))})

(defn- call [ctx action & [args]]
  (base/run-tool (assoc ctx :tool-name "debug" :args (assoc args :action action))))

(deftest framing-roundtrip-and-body-limit
  (let [out (ByteArrayOutputStream.)]
    (dap/send-frame! {:out out} {:seq 1 :type "request" :command "threads"})
    (let [input (BufferedInputStream. (ByteArrayInputStream. (.toByteArray out)))
          message (dap/read-frame {:in input :max-header-chars 8192 :max-body-bytes 1024})]
      (is (= "threads" (:command message))))
    (let [input (BufferedInputStream. (ByteArrayInputStream. (.toByteArray out)))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (dap/read-frame {:in input :max-header-chars 8192 :max-body-bytes 4}))))))

(deftest transport-requests-time-out-and-cancel-waiters
  (let [client {:out (ByteArrayOutputStream.) :next-id (atom 0) :pending (atom {})
                :policy (assoc policy :timeout-ms 20)}]
    (is (= :timeout
           (try (dap/request! client "threads" {}) nil
                (catch clojure.lang.ExceptionInfo e (:debug/error (ex-data e))))))
    (let [waiting (future
                    (try (dap/request! client "threads" {}) nil
                         (catch clojure.lang.ExceptionInfo e (:debug/error (ex-data e)))))]
      (Thread/sleep 5)
      (dap/cancel! client)
      (is (= :cancelled (deref waiting 1000 ::not-cancelled))))))

(deftest semantic-debug-lifecycle-is-exact-owner
  (let [{:keys [conn] :as ctx} (fixture)
        calls (atom [])]
    (try
      (sessions/reset-sessions!)
      (with-redefs [gates/threshold (fn [k] (if (= :resources k)
                                              {:read-chars 4000 :search-results 20}
                                              policy))
                    approval/resolve-ask (fn [_ _] {:effect :allow})]
        (let [ctx (assoc ctx :debug-spawn (fn [_ _] (fake-client calls)))
              launched (call ctx "launch" {:configuration {:program "app"}})
              id (get-in launched [:resource :session])]
          (is (some? id) (pr-str launched))
          (is (= :decision-required (executions/continuity conn id)))
          (is (= :mechanics (:category (call (assoc ctx :branch {:id "B2" :role :implementor})
                                             "threads" {:session id}))))
          (is (= "main" (get-in (call ctx "threads" {:session id}) [:resource :result :threads 0 :name])))
          (is (= 11 (get-in (call ctx "stack" {:session id :thread 7}) [:resource :result :stackFrames 0 :id])))
          (is (= 22 (get-in (call ctx "scopes" {:session id :frame 11}) [:resource :result :scopes 0 :variablesReference])))
          (is (= "1" (get-in (call ctx "variables" {:session id :reference 22}) [:resource :result :variables 0 :value])))
          (is (= "1" (get-in (call ctx "evaluate" {:session id :expression "x" :frame 11}) [:resource :result :result])))
          (is (= :neutral (:category (call ctx "breakpoints" {:session id :path "src/core.clj" :lines [3 7]}))))
          (is (= :neutral (:category (call ctx "continue" {:session id :thread 7}))))
          (is (= :neutral (:category (call ctx "pause" {:session id :thread 7}))))
          (is (= :neutral (:category (call ctx "step" {:session id :thread 7 :kind "in"}))))
          (is (= :neutral (:category (call ctx "attach" {:configuration {:processId 42}}))))
          (is (= :success (:category (call ctx "terminate" {:session id}))))
          (is (= :complete (:status (executions/execution conn id))))
          (is (= ["initialize" "launch" "configurationDone" "threads" "stackTrace" "scopes"
                  "variables" "evaluate" "setBreakpoints" "continue" "pause" "stepIn"
                  "initialize" "attach" "configurationDone" "disconnect"]
                 (mapv first @calls)))))
      (finally (sessions/reset-sessions!) (db/close conn)))))

(deftest cancellation-events-and-debug-resources
  (let [{:keys [conn] :as ctx} (fixture)
        calls (atom [])]
    (try
      (sessions/reset-sessions!)
      (with-redefs [gates/threshold (fn [k] (if (= :resources k)
                                              {:read-chars 4000 :search-results 20}
                                              policy))
                    approval/resolve-ask (fn [_ _] {:effect :allow})]
        (let [ctx (assoc ctx :debug-spawn (fn [_ _] (fake-client calls)))
              id (get-in (call ctx "launch" {}) [:resource :session])]
          (dap/record-event! (sessions/owned ctx id) {:event "stopped" :body {:threadId 7}})
          (dap/record-event! (sessions/owned ctx id) {:event "output" :body {:output "hello"}})
          (let [events (call ctx "events" {:session id :count 1})]
            (is (= 1 (count (get-in events [:resource :events]))))
            (is (= 1 (get-in events [:resource :buffered-remaining]))))
          (is (= 1 (count (get-in (call ctx "events" {:session id}) [:resource :events]))))
          (is (= :neutral (:category (call ctx "cancel" {:session id}))))
          (is (= :success (:category (call ctx "terminate" {:session id}))))
          (let [read (base/run-tool
                      (assoc ctx :tool-name "resource"
                             :args {:action "read" :uri (str "debug:" id)}))]
            (is (= :neutral (:category read)) (pr-str read))
            (is (str/includes? (:result read) "stopped")))))
      (finally (sessions/reset-sessions!) (db/close conn)))))

(deftest run-teardown-closes-sessions-and-marks-uncertain-outcomes
  (let [{:keys [conn run-id] :as ctx} (fixture)
        calls (atom [])]
    (try
      (sessions/reset-sessions!)
      (with-redefs [gates/threshold (fn [_] policy)
                    approval/resolve-ask (fn [_ _] {:effect :allow})]
        (let [ctx (assoc ctx :debug-spawn (fn [_ _] (fake-client calls)))
              id (get-in (call ctx "launch" {}) [:resource :session])]
          (sessions/close-run! run-id)
          (is (= :error (:status (executions/execution conn id))))
          (is (thrown? clojure.lang.ExceptionInfo (sessions/owned ctx id)))))
      (finally (sessions/reset-sessions!) (db/close conn)))))

(deftest lost-admission-closes-the-spawned-adapter
  (let [{:keys [conn run-id] :as ctx} (fixture)
        calls (atom [])
        closed (atom nil)]
    (try
      (sessions/reset-sessions!)
      (with-redefs [gates/threshold (fn [_] policy)
                    approval/resolve-ask (fn [_ _] {:effect :allow})
                    dap/close! (fn [client] (reset! closed client))]
        (let [ctx (assoc ctx :debug-spawn
                         (fn [_ _]
                           (sessions/close-run! run-id)
                           (fake-client calls)))]
          (is (= :mechanics (:category (call ctx "launch" {}))))
          (is (some? @closed))))
      (finally (sessions/reset-sessions!) (db/close conn)))))
