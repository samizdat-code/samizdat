;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.papercuts-test
  "Papercuts: friction the agent reports about the harness, deduplicated,
  and closed only against a change measured to have helped
  (karamazov-05z0.5, after autolith's papercuts)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.papercuts]
            [samizdat.security.flow]
            [samizdat.session :as session]
            [samizdat.store.db :as db]
            [samizdat.store.papercuts :as papercuts]
            [samizdat.store.runs :as runs]))

(def ^:private policy {:window-ms 86400000 :overlap 0.6 :same-run-overlap 0.4 :min-word-chars 4})

(use-fixtures :each (fn [f] (session/reset!) (f) (session/reset!)))

(deftest a-report-that-repeats-an-open-one-is-folded-into-it
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})]
    (try
      (let [a (papercuts/report! c {:title "read_file truncates long lines silently"
                                    :body "a 4000-char line came back cut with no marker"
                                    :run-id rid :branch-id "B1"} policy)
            b (papercuts/report! c {:title "read_file silently truncates long lines"
                                    :body "lines over 2000 chars are cut"
                                    :run-id "other-run" :branch-id "B2"} policy)
            d (papercuts/report! c {:title "grep ignores .gitignore negations"
                                    :body "a !pattern line is not honoured"
                                    :run-id rid :branch-id "B1"} policy)]
        (is (:new? a))
        (is (= (:id a) (:id b)) "same words, reordered: one papercut")
        (is (not (:new? b)))
        (is (= 2 (:reports b)))
        (is (:new? d) "a different friction is its own")
        (is (= 2 (count (papercuts/open c)))))
      (testing "the same run gets a looser match: a branch re-filing rewords it"
        (let [x (papercuts/report! c {:title "shell timeout too short for builds"
                                      :body "cargo build killed at 120s" :run-id rid} policy)
              y (papercuts/report! c {:title "builds die on the shell timeout"
                                      :body "needs longer" :run-id rid} policy)]
          (is (= (:id x) (:id y)))))
      (finally (db/close c)))))

(deftest word-overlap-is-the-similarity
  (is (papercuts/similar? "read file truncates lines" "truncates lines in read file" 0.6 4))
  (is (not (papercuts/similar? "read file truncates lines" "grep ignores patterns" 0.6 4)))
  (is (not (papercuts/similar? "" "" 0.6 4)) "nothing is not a match"))

(deftest a-papercut-closes-only-against-a-change-that-helped
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        run (fn [tool args] (str (:result (base/run-tool {:tool-name "papercut" :args args
                                                          :conn c :run-id rid
                                                          :branch {:id "S"}}))))]
    (try
      (let [out (run "papercut" {"action" "report" "title" "fetch_turn loses the reasoning"
                                 "body" "the reasoning text is not returned"})
            id (:id (first (papercuts/open c)))]
        (is (str/includes? out id) "the reporter is told the id")
        (testing "listing shows it"
          (is (str/includes? (run "papercut" {"action" "list"}) "fetch_turn loses the reasoning")))
        (testing "closing without an experiment is refused"
          (is (str/includes? (run "papercut" {"action" "close" "id" id "resolution" "fixed"})
                             "experiment"))
          (is (= 1 (count (papercuts/open c)))))
        (testing "nor against one that did not help"
          (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
          (session/experiment! "nope" {:change "c" :hypothesis "h"})
          (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :mechanics
                                                 :signals {:parse-error true}}))
          (run "papercut" {"action" "close" "id" id "experiment" "nope" "resolution" "fixed"})
          (is (= 1 (count (papercuts/open c)))))
        (testing "a better verdict closes it, and the resolution is kept"
          (session/reset!)
          (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :mechanics
                                                 :signals {:parse-error true}}))
          (session/experiment! "fix" {:change "return reasoning" :hypothesis "h"})
          (dotimes [_ 8] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
          (is (str/includes? (run "papercut" {"action" "close" "id" id "experiment" "fix"
                                              "resolution" "fetch_turn returns it now"})
                             "closed"))
          (is (empty? (papercuts/open c)))
          (is (= "fetch_turn returns it now" (:resolution (papercuts/by-id c id))))
          (is (= "better" (:verdict (papercuts/by-id c id))))))
      (finally (db/close c)))))

(deftest a-papercut-carries-what-its-reporter-had-read
  ;; model-written text one branch writes and another reads: the same path a
  ;; message takes, so the same flow label (samizdat.security.flow).
  (let [c (db/open! ":memory:")
        rid (runs/start-run! c {:problem "p"})
        ctx (fn [b] {:conn c :run-id rid :branch {:id b}})]
    (try
      (samizdat.security.flow/observe! (ctx "W1") {:trust :untrusted} "webfetch")
      (base/run-tool (assoc (ctx "W1") :tool-name "papercut"
                            :args {"action" "report" "title" "webfetch strips the code blocks"}))
      (let [row (first (papercuts/open c))]
        (is (not-empty (str (:flow row))) "the reporter's label is on the row")
        (testing "and reading it on purpose takes it"
          (base/run-tool (assoc (ctx "SUP") :tool-name "papercut" :args {"action" "list"}))
          (is (not-empty (str (samizdat.security.flow/carried (ctx "SUP")))))))
      (finally
        (samizdat.security.flow/forget-run! rid)
        (db/close c)))))
