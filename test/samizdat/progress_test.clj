;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.progress-test
  "Where each branch is in its workflow, journaled as it moves
  (karamazov-rz7t.2). The supervisor reads this instead of the implementer's
  steps, so it has to be recorded by every compiled manifest, not by a driver
  that remembered to ask."
  (:require [clojure.test :refer [deftest testing is]]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.manifests :as manifests]
            [samizdat.progress :as progress]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(deftest a-change-is-what-differs-between-two-snapshots
  (let [keys {:phase [:phase] :task [:task :id]}]
    (is (= {:phase "explore" :task nil}
           (progress/snapshot {:phase :explore} keys)))
    (is (= [{:key "phase" :from "explore" :to "build"}
            {:key "task" :from nil :to "T1"}]
           (progress/changes {:phase "explore" :task nil}
                             {:phase "build" :task "T1"})))
    (is (empty? (progress/changes {:phase "build"} {:phase "build"})))
    (testing "the first sighting of a branch is a change from nothing, but
              only for the keys that hold something"
      (is (= [{:key "phase" :from nil :to "explore"}]
             (progress/changes nil {:phase "explore" :task nil}))))))

(defn- register-cells!
  ;; Inside the call rather than at load: other namespaces reload the cell
  ;; registry, and a cell defined once at load is gone by the time this runs.
  []
  (cell/defcell :progress-test/claim
    {:doc "Claims a task and leaves explore." :pure true :input [:map] :output [:map]}
    (fn [_ d] (update d :branch assoc :phase :build :task {:id "T1" :title "t"})))
  (cell/defcell :progress-test/work
    {:doc "Does nothing to the branch." :pure true :input [:map] :output [:map]}
    (fn [_ d] d)))

(defn- compile* [extra]
  (register-cells!)
  (manifests/compile-definition
   (merge {:cells {:start :progress-test/work :claim :progress-test/claim
                   :review :progress-test/work}
           :edges {:start :claim :claim :review :review :end}}
          extra)))

(deftest every-compiled-manifest-journals-where-its-branch-is
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        wf (compile* {:progress [:review]})]
    (myc/run-compiled wf {:conn conn :run-id rid}
                      {:branch {:id "T0" :phase :explore} :turn 7})
    (let [p (get (journal/progress conn rid) "T0")]
      (is (= [["phase" nil "explore"] ["phase" "explore" "build"]
              ["task" nil "T1"] ["stage" nil "review"]]
             (mapv (juxt :key :from :to) p))
          "the branch's fields as they change, and the one node the manifest
           names as progress — not :start or :claim")
      (is (every? #(= 7 (:turn %)) p)))
    (progress/forget-run! rid)))

(deftest a-manifest-that-names-no-progress-nodes-journals-only-branch-changes
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (myc/run-compiled (compile* {}) {:conn conn :run-id rid}
                      {:branch {:id "T0" :phase :build :task {:id "T1"}} :turn 1})
    (is (not-any? #(= "stage" (:key %)) (get (journal/progress conn rid) "T0")))
    (progress/forget-run! rid)))

(deftest a-branch-seen-again-journals-only-what-moved
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        wf (compile* {})]
    (myc/run-compiled wf {:conn conn :run-id rid}
                      {:branch {:id "T0" :phase :explore} :turn 1})
    (myc/run-compiled wf {:conn conn :run-id rid}
                      {:branch {:id "T0" :phase :build :task {:id "T1"}} :turn 2})
    (is (= 3 (count (get (journal/progress conn rid) "T0")))
        "the second turn starts where the first ended, so nothing new")
    (progress/forget-run! rid)))

(deftest no-run-record-no-journal-and-no-failure
  (is (= {:x 1} (select-keys (myc/run-compiled (compile* {:progress [:review]}) {}
                                               {:branch {:id "T0"} :turn 1 :x 1})
                             [:x]))))

(deftest a-progress-node-the-graph-does-not-have-is-refused-at-compile
  ;; A misspelt node would record nothing, forever, and look like a branch
  ;; that never got there.
  (let [e (try (compile* {:progress [:reveiw]}) nil
               (catch Throwable e e))]
    (is (some? e))
    (is (re-find #"reveiw" (str (ex-message e))))))

(deftest every-shipped-manifest-names-only-nodes-it-has
  (doseq [f (.listFiles (java.io.File. "resources/manifests"))
          :let [d (manifests/read-definition (slurp f))]
          :when (seq (:progress d))]
    (is (every? (set (keys (:cells d))) (:progress d)) (str f))
    (when-not (false? (:turn-sliceable? d))
      (let [t (manifests/turn-manifest d)]
        (is (every? (set (keys (:cells t))) (:progress t))
            (str f " sliced"))))))
