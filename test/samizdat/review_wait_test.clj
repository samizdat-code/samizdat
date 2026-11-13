;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.review-wait-test
  "A done made while its review is out waits for the review instead of being
  refused turn after turn. ctb-r12 payments-pipeline-fix spent 22 turns
  re-calling done during one review and ran out at the 300-turn cap."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.agent.tools.ship :as ship]))

(defn- clock [] (let [t (atom 0)] {:now #(deref t) :sleep! #(swap! t + %)}))

(deftest a-pending-review-is-waited-for-until-it-lands
  (let [{:keys [now sleep!]} (clock)
        reads (atom 0)
        read (fn [] (swap! reads inc) {:supervised? true :pending? (< @reads 4)})
        r (ship/await-review read {:pending? true} {:wait-ms 60000 :poll-ms 5000 :now now :sleep! sleep!})]
    (is (false? (:pending? r)) "it came back once the review landed")
    (is (= 4 @reads) "it landed on the fourth read")
    (is (= 20000 (now)) "four polls, no more")))

(deftest the-wait-is-bounded
  (let [{:keys [now sleep!]} (clock)
        r (ship/await-review (constantly {:pending? true}) {:pending? true}
                             {:wait-ms 20000 :poll-ms 5000 :now now :sleep! sleep!})]
    (is (true? (:pending? r)) "still pending: the done gets the ordinary hold")
    (is (<= (now) 20000))))

(deftest nothing-pending-nothing-to-wait-for
  (let [{:keys [now sleep!]} (clock)]
    (is (= {:pending? false}
           (ship/await-review (fn [] (throw (ex-info "never read" {}))) {:pending? false}
                              {:wait-ms 20000 :poll-ms 5000 :now now :sleep! sleep!})))
    (is (= 0 (now)))))
