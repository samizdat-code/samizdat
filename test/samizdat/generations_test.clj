;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.generations-test
  "Generations: the set of project versions current when a run ended, and
  how it ended (karamazov-05z0.8, after autolith's image generations). The
  per-role last-good fallback cannot say which versions worked TOGETHER, and
  nothing offered a way back from a set of edits that pass every check and
  make every run error."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.config :as config]
            [samizdat.store.db :as db]
            [samizdat.store.userspace :as us]
            [samizdat.userspace :as userspace]))

(deftest a-run-end-records-the-versions-it-ran-on
  (let [c (db/open! ":memory:")]
    (try
      (us/save! c :prompt "a" "a1" "project" "first")
      (us/save! c :cell "b" "b1" "project" "first")
      (us/save! c :prompt "factory-only" "f" "factory" nil)
      (us/record-generation! c "r1" :shipped)
      (us/save! c :prompt "a" "a2" "project" "second")
      (us/record-generation! c "r2" :error)
      (us/record-generation! c "r3" :error)
      (let [g (us/last-shipped-generation c)]
        (is (= "r1" (:run-id g)))
        (is (= {[:prompt "a"] 1 [:cell "b"] 1} (:versions g))
            "project-authored versions only: a factory copy is the baseline, not a tuning"))
      (is (= 2 (us/errors-since-shipped c)) "two runs in a row errored since")
      (is (= [{:kind :prompt :name "a" :from 2 :to 1}]
             (us/changed-since c (us/last-shipped-generation c)))
          "what moved since, and the version to go back to")
      (testing "a failed run breaks the streak — it ended, it did not crash"
        (us/record-generation! c "r4" :failed)
        (is (zero? (us/errors-since-shipped c))))
      (testing "with nothing shipped yet there is no generation to go back to"
        (let [c2 (db/open! ":memory:")]
          (try (us/record-generation! c2 "x" :error)
               (is (nil? (us/last-shipped-generation c2)))
               (finally (db/close c2)))))
      (finally (db/close c)))))

(deftest record-run-outcome-records-the-generation
  (let [c (db/open! ":memory:")]
    (try
      (userspace/bind! c)
      (userspace/save! :prompt "a" "a1" "first")
      (userspace/record-run-outcome! :shipped "run-1")
      (is (= "run-1" (:run-id (us/last-shipped-generation c))))
      (finally (userspace/unbind!) (db/close c)))))

(deftest pristine-mode-is-a-setting
  (is (= :shipped (:mode (config/userspace-settings {:userspace {:mode :shipped}}))))
  (is (= :project (:mode (config/userspace-settings {}))) "the project's workflow by default")
  (is (= :project (:mode (config/userspace-settings {:userspace {:mode :bogus}})))))
