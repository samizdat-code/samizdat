;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.stream-path-test
  "Every model call in a run streams by one path. Branch turns streamed;
  the workflow choice and team-solve's planner and manager did not, so the
  first minute or two of a run showed `thinking…` with nothing under it."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.infer]
            [samizdat.events :as events]
            [samizdat.llm.client :as client]
            [samizdat.llm.registry :as registry]
            [samizdat.workflow :as wf]))

(def ^:private whole "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}")

(defn- streaming-post [seen-opts]
  (fn [_ opts on-delta]
    (reset! seen-opts opts)
    (on-delta {:reasoning "planning the task"})
    (on-delta {:text "ok"})
    {:status 200 :headers {} :body whole}))

(def ^:private cfg {:base-url "http://h/v1" :model "m" :features #{:stream}})

(deftest a-call-with-no-watcher-streams-to-the-run
  (let [hooked (atom []) flushed (atom 0) seen (atom nil)
        hook (fn [run-id branch-id]
               {:on-delta (fn [d] (swap! hooked conj [run-id branch-id d]))
                :flush! (fn [] (swap! flushed inc))})]
    (with-redefs [samizdat.llm.client/delta-hook (atom hook)
                  samizdat.llm.stream/post (streaming-post seen)
                  jolt.http-client/post (fn [_ _] (throw (ex-info "not streamed" {})))]
      (is (= "ok" (:content (client/chat (registry/adapter-for :local)
                                         (assoc cfg :stream-run-id "r1" :stream-branch "B1")
                                         [{:role "user" :content "x"}]))))
      (is (= [["r1" "B1" {:reasoning "planning the task"}] ["r1" "B1" {:text "ok"}]] @hooked)
          "the reasoning reached the run's root branch")
      (is (= 1 @flushed) "and the tail was flushed when the call ended")
      (testing "a call that brings its own watcher streams through it"
        (reset! hooked [])
        (let [mine (atom [])]
          (client/chat (registry/adapter-for :local)
                       (assoc cfg :stream-run-id "r1" :stream-branch "B1")
                       [{:role "user" :content "x"}] {:on-delta #(swap! mine conj %)})
          (is (= 2 (count @mine)))
          (is (empty? @hooked)))))
    (testing "a call that names no run is not streamed"
      (with-redefs [samizdat.llm.client/delta-hook (atom hook)
                    samizdat.llm.stream/post (fn [& _] (throw (ex-info "streamed" {})))
                    jolt.http-client/post (fn [_ _] {:status 200 :body whole})]
        (is (= "ok" (:content (client/chat (registry/adapter-for :local) cfg [{:role "user" :content "x"}]))))))))

(deftest a-role-keeps-the-run-s-stream-target
  (let [ctx {:config {} :llm-config (assoc cfg :provider :local :stream-run-id "r1" :stream-branch "B1")}]
    (is (= ["r1" "B1"] ((juxt :stream-run-id :stream-branch) (:llm-config (wf/role-ctx ctx :manager)))))))

(deftest the-registered-hook-publishes-to-the-bus
  (let [sub (events/subscribe)]
    (try
      (with-redefs [samizdat.llm.stream/post (streaming-post (atom nil))
                    jolt.http-client/post (fn [_ _] (throw (ex-info "not streamed" {})))]
        (client/chat (registry/adapter-for :local) (assoc cfg :stream-run-id "r9" :stream-branch "B1")
                     [{:role "user" :content "x"}]))
      (is (some #(and (= :delta (:kind %)) (= "r9" (:run-id %)) (= "B1" (:branch-id %))
                      (= "planning the task" (:reasoning %)))
                (:buf @sub))
          "infer's publisher put the reasoning on the bus as B1")
      (finally (events/unsubscribe! sub)))))
