;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.shell-log-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt.fs :as fs]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.store.captures :as captures]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]))

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "shell logs"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (doseq [[stream text] [["out" "0123456789"] ["err" "warning"]]]
      (spit (str root "/" stream) text))
    {:root root :conn conn :run-id run-id}))

(defn- save-capture [{:keys [conn root run-id]} id]
  (captures/create! conn {:id id :run-id run-id :branch-id "B1"
                          :stdout-path (str root "/out") :stderr-path (str root "/err")
                          :stdout-read-path (str root "/out") :stderr-read-path (str root "/err")})
  (captures/finish! conn id :complete
                    {:stdout {:observed-bytes 10 :retained-bytes 10}
                     :stderr {:observed-bytes 7 :retained-bytes 7}})
  (captures/mark-delivered! conn id))

(defn- read-log [fixture uri & [args]]
  (base/run-tool (assoc fixture :tool-name "resource" :branch {:id "B1" :role :answerer}
                        :args (merge {:action "read" :uri uri} args))))

(deftest shell-logs-read-byte-windows-and-bounded-literal-search
  (let [fixture (fixture)]
    (try
      (save-capture fixture "c1")
      (let [window (read-log fixture "shell-log:c1?stream=stdout" {:offset 2 :limit 3})
            search (read-log fixture "shell-log:c1?stream=stdout" {:query "34" :limit 2})]
        (is (str/includes? (:result window) "234"))
        (is (str/includes? (:result window) "Next offset: 5"))
        (is (str/includes? (:result search) ":offset 3"))
        (is (str/includes? (:result search) ":next-offset 5"))
        (is (= 1 (count (re-seq #":offset" (:result search))))))
      (let [other (base/run-tool (assoc fixture :tool-name "resource"
                                        :branch {:id "B2" :role :answerer}
                                        :args {:action "read" :uri "shell-log:c1?stream=stdout"}))]
        (is (= :mechanics (:category other)))
        (is (str/includes? (:result other) "access-denied")))
      (finally
        (db/close (:conn fixture))
        (fs/delete-tree (:root fixture))))))

(deftest missing-incomplete-and-pruned-logs-stay-distinct
  (let [fixture (fixture)]
    (try
      (is (str/includes? (:result (read-log fixture "shell-log:missing")) "missing-resource"))
      (captures/create! (:conn fixture)
                        {:id "legacy" :run-id (:run-id fixture) :branch-id "B1"
                         :stdout-path (str (:root fixture) "/out")
                         :stderr-path (str (:root fixture) "/err")})
      (is (str/includes? (:result (read-log fixture "shell-log:legacy")) "redaction-required"))
      (captures/create! (:conn fixture)
                        {:id "running" :run-id (:run-id fixture) :branch-id "B1"
                         :stdout-path (str (:root fixture) "/out")
                         :stderr-path (str (:root fixture) "/err")
                         :stdout-read-path (str (:root fixture) "/out")
                         :stderr-read-path (str (:root fixture) "/err")})
      (is (str/includes? (:result (read-log fixture "shell-log:running")) "incomplete"))
      (save-capture fixture "pruned")
      (captures/prune! (:conn fixture)
                       {:max-age-ms 0 :max-count 100 :max-capacity-bytes 1000
                        :now "2999-01-01T00:00:00.000Z"}
                       (fn [_] true))
      (is (str/includes? (:result (read-log fixture "shell-log:pruned")) "pruned-resource"))
      (finally
        (db/close (:conn fixture))
        (fs/delete-tree (:root fixture))))))

(deftest inference-views-redact-captured-secrets
  (let [fixture (fixture)
        canary "sk-CANARYshelltest00000000"]
    (try
      (spit (str (:root fixture) "/out") (str "token=" canary))
      (save-capture fixture "secret")
      (let [view (resource-tool/read-view
                  (assoc fixture :env {"SECRET_TOKEN" canary} :branch {:id "B1" :role :answerer})
                  "shell-log:secret?stream=stdout")]
        (is (not (str/includes? view canary)))
        (is (not= (slurp (str (:root fixture) "/out")) view)))
      (finally
        (db/close (:conn fixture))
        (fs/delete-tree (:root fixture))))))
