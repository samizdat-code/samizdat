;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.structural-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.agent.tools.structural]
            [samizdat.approval :as approval]
            [samizdat.engine.proc :as proc]
            [samizdat.resource :as resource]
            [samizdat.structural.backend :as backend]
            [samizdat.store.db :as db]
            [samizdat.store.executions :as executions]
            [samizdat.store.runs :as runs]))

(def policy
  {:program nil :timeout-ms 1000 :max-source-chars 10000 :max-output-bytes 10000
   :max-matches 10 :max-preview-chars 4000})

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "structural"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (spit (str root "/core.js") "function add(a, b) { return a + b; }\n")
    {:root root :conn conn :run-id run-id :branch {:id "B1" :role :implementor}}))

(defn- observation [ctx]
  (resource/observe (resource-tool/registry-for ctx) "workspace:core.js" ctx))

(defn- call [ctx action args]
  (base/run-tool (assoc ctx :tool-name "structural" :args (assoc args :action action))))

(defn- fake-backend [calls]
  {:query (fn [_ request]
            (swap! calls conj [:query request])
            [{:match "m1" :start 0 :end 12 :start-byte 0 :end-byte 12 :text "(defn add [a b]"}])
   :rewrite (fn [_ request]
              (swap! calls conj [:rewrite request])
              "function add(a, b) { return a - b; }\n")})

(deftest query-observes-an-exact-resource-revision
  (let [{:keys [conn root] :as ctx} (fixture)
        calls (atom [])]
    (try
      (with-redefs [gates/threshold (fn [_] policy)]
        (let [observed (observation ctx)
              result (call (assoc ctx :structural-backend (fake-backend calls)) "query"
                           {:uri "workspace:core.js" :base-revision (:revision observed)
                            :language "javascript" :pattern "function $NAME($$$ARGS) { $$$BODY }"})]
          (is (= :neutral (:category result)) (pr-str result))
          (is (= (:revision observed) (get-in result [:resource :revision])))
          (is (= "m1" (get-in result [:resource :matches 0 :match])))
          (is (= (:content observed) (get-in @calls [0 1 :content]))))
        (is (= :mechanics
               (:category (call (assoc ctx :structural-backend (fake-backend calls)) "query"
                                {:uri "workspace:core.js" :base-revision "stale"
                                 :language "javascript" :pattern "x"})))))
      (finally (db/close conn) (fs/delete-tree root)))))

(deftest queries-refuse-nonworkspace-and-cross-owner-resources
  (let [{:keys [conn root run-id] :as ctx} (fixture)
        calls (atom [])]
    (try
      (let [id (executions/start! conn {:run-id run-id :branch-id "B2"
                                        :kind "inference" :specification {:task "private"}
                                        :recovery :unknown})
            observed (resource/observe (resource-tool/registry-for
                                        (assoc ctx :branch {:id "B2" :role :implementor}))
                                       (str "execution:" id) ctx)]
        (with-redefs [gates/threshold (fn [_] policy)]
          (let [result (call (assoc ctx :structural-backend (fake-backend calls)) "query"
                             {:uri (str "execution:" id) :base-revision (:revision observed)
                              :language "javascript" :pattern "x"})]
            (is (= :mechanics (:category result)))
            (is (empty? @calls)))))
      (finally (db/close conn) (fs/delete-tree root)))))

(deftest rewrite-previews-applies-once-and-refuses-other-owners
  (let [{:keys [conn root] :as ctx} (fixture)
        calls (atom [])]
    (try
      (with-redefs [gates/threshold (fn [k]
                                      (if (= :resources k)
                                        {:read-chars 4000 :search-results 20 :max-proposal-members 32}
                                        policy))]
        (let [observed (observation ctx)
              staged (call (assoc ctx :structural-backend (fake-backend calls)) "rewrite"
                           {:uri "workspace:core.js" :base-revision (:revision observed)
                            :language "javascript" :pattern "+" :replacement "-"})
              id (get-in staged [:resource :proposal-id])]
          (is (some? id) (pr-str staged))
          (is (= "function add(a, b) { return a + b; }\n" (slurp (str root "/core.js"))))
          (is (str/includes? (:result (call ctx "inspect" {:proposal id})) "-"))
          (is (= :mechanics (:category (call (assoc ctx :branch {:id "B2" :role :implementor})
                                             "apply" {:proposal id}))))
          (let [applied (call ctx "apply" {:proposal id})]
            (is (= :success (:category applied)) (pr-str applied))
            (is (= "function add(a, b) { return a - b; }\n" (slurp (str root "/core.js")))))
          (is (not= :success (:category (call ctx "apply" {:proposal id}))))))
      (finally (db/close conn) (fs/delete-tree root)))))

(deftest missing-backend-is-unavailable-and-status-is-honest
  (let [{:keys [conn root] :as ctx} (fixture)]
    (try
      (with-redefs [gates/threshold (fn [_] policy)]
        (let [status (call ctx "status" {})]
          (is (false? (get-in status [:resource :configured])))
          (is (false? (get-in status [:resource :available]))))
        (is (= :neutral
               (:category (call ctx "query" {:uri "workspace:core.js"
                                             :base-revision (:revision (observation ctx))
                                             :language "javascript" :pattern "x"})))))
      (finally (db/close conn) (fs/delete-tree root)))))

(deftest process-backend-requires-approval-and-parses-data
  (let [calls (atom [])]
    (with-redefs [gates/threshold (fn [_] (assoc policy :program "/opt/ast-grep"))
                  approval/resolve-ask (fn [_ request]
                                        (swap! calls conj [:approval request])
                                        {:effect :allow})
                  proc/run (fn [options & args]
                             (swap! calls conj [:process options args])
                             {:exit 0 :out "{\"text\":\"😀b\",\"range\":{\"byteOffset\":{\"start\":1,\"end\":6}}}\n"})]
      (let [matches (backend/query {:root "/tmp"}
                                   {:content "a😀b" :language "javascript" :pattern "b"})]
        (is (= 1 (get-in matches [0 :start])))
        (is (= 3 (get-in matches [0 :end])))
        (is (= 1 (get-in matches [0 :start-byte])))
        (is (= 6 (get-in matches [0 :end-byte])))
        (is (= "😀b" (get-in matches [0 :text])))
        (is (= ["/opt/ast-grep" "run"] (take 2 (get-in @calls [1 2]))))
        (is (= "a😀b" (get-in @calls [1 1 :input])))))))
