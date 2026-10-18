;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.findings-test
  "The review findings ledger (karamazov-05z0.9)."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.agent.findings :as f]))

(def ^:private a "[high] parse-args drops the last flag when it has no value")
(def ^:private b "[medium] the new test only covers the happy path")

(deftest an-id-survives-rewording-in-case-and-punctuation
  (is (= (f/id-of a) (f/id-of "[HIGH] parse-args drops the last flag, when it has no value!")))
  (is (not= (f/id-of a) (f/id-of b)))
  (is (re-matches #"f-[0-9a-f]{8}" (f/id-of a))))

(deftest a-review-folds-into-what-the-last-one-said
  (let [l1 (f/fold {} [a b])]
    (is (= #{:open} (set (map :status (vals l1)))))
    (testing "the owner disputes one and resolves the other"
      (let [l2 (-> l1
                   (f/answer (f/id-of a) :disputed "the flag is validated upstream in cli/read")
                   (f/answer (f/id-of b) :resolved "added the error-path test"))]
        (is (f/answered? l2))
        (is (nil? (f/answer l2 "f-nope" :disputed "x")) "an id nobody raised")
        (testing "the next review keeps a, drops b"
          (let [l3 (f/fold l2 [a])]
            (is (= :upheld (:status (get l3 (f/id-of a)))) "disputed and raised again: upheld")
            (is (= "the flag is validated upstream in cli/read" (:reason (get l3 (f/id-of a)))))
            (is (= :withdrawn (:status (get l3 (f/id-of b)))))
            (is (= [(f/id-of a)] (keys (f/standing l3))))
            (is (not (f/answered? l3)) "nothing new from the owner")))
        (testing "a resolved finding raised again is open again"
          (is (= :open (:status (get (f/fold l2 [b]) (f/id-of b))))))))))
