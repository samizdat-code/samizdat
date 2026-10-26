;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tui-actions-test
  "Asynchronous TUI action coordination with deferred HTTP responses."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.tui.actions :as actions]
            [samizdat.tui.state :as st]))

(defn- selected
  [input]
  (atom (-> (st/initial "http://x:1")
            (assoc :run-id "old-run" :branch-id "B1" :input input))))

(defn- environment
  [state client polls notes]
  {:state state
   :client client
   :poll! #(swap! polls inc)
   :say! #(swap! notes conj %)})

(defn- deferred-call
  [started answer calls]
  (fn [& args]
    (swap! calls conj args)
    (deliver started true)
    @answer))

(deftest successful-unchanged-steering-clears-only-the-sent-draft
  (let [state (selected "focus the parser")
        started (promise)
        answer (promise)
        calls (atom [])
        polls (atom 0)
        notes (atom [])
        env (environment state
                         {:intervene! (deferred-call started answer calls)}
                         polls notes)
        pending (actions/steer! env "focus the parser")]
    (is (future? pending))
    (is (= true (deref started 1000 false)))
    (is (= "focus the parser" (:input @state)) "the draft stays visible while sending")
    (is (= [["http://x:1" "old-run"
             {:branch-id "B1" :kind "message" :payload "focus the parser"}]]
           @calls))
    (deliver answer {:ok true})
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= "" (:input @state)))
    (is (= 1 @polls))))

(deftest steering-preserves-newer-typing-and-refused-drafts
  (testing "newer typing survives success"
    (let [state (selected "first thought")
          started (promise)
          answer (promise)
          pending (actions/steer!
                   (environment state
                                {:intervene! (deferred-call started answer (atom []))}
                                (atom 0) (atom []))
                   "first thought")]
      (is (= true (deref started 1000 false)))
      (swap! state st/set-input "first thought, plus a newer idea")
      (deliver answer {:ok true})
      (is (= {:ok true} (deref pending 1000 ::timeout)))
      (is (= "first thought, plus a newer idea" (:input @state)))))
  (testing "a refusal preserves the unchanged draft"
    (let [state (selected "keep this")
          started (promise)
          answer (promise)
          pending (actions/steer!
                   (environment state
                                {:intervene! (deferred-call started answer (atom []))}
                                (atom 0) (atom []))
                   "keep this")]
      (is (= true (deref started 1000 false)))
      (deliver answer {:ok false :error "HTTP 409: run ended"})
      (is (= {:ok false :error "HTTP 409: run ended"} (deref pending 1000 ::timeout)))
      (is (= "keep this" (:input @state)))
      (is (= "HTTP 409: run ended" (:error @state))))))

(deftest stale-steer-response-cannot-change-the-newly-selected-run
  (let [state (selected "old draft")
        started (promise)
        answer (promise)
        polls (atom 0)
        pending (actions/steer!
                 (environment state
                              {:intervene! (deferred-call started answer (atom []))}
                              polls (atom []))
                 "old draft")]
    (is (= true (deref started 1000 false)))
    (swap! state #(-> %
                      (st/select-run "new-run")
                      (st/set-input "new draft")
                      (st/note-error "new run error")))
    (deliver answer {:ok false :error "old run refusal"})
    (is (= {:ok false :error "old run refusal"} (deref pending 1000 ::timeout)))
    (is (= "new-run" (:run-id @state)))
    (is (= "new draft" (:input @state)))
    (is (= "new run error" (:error @state)))
    (is (zero? @polls))))

(deftest stale-abort-and-resume-responses-are-dropped
  (doseq [[action-name invoke client-key]
          [[:abort actions/abort! :abort!]
           [:resume actions/resume! :resume!]]]
    (testing (name action-name)
      (let [state (selected "new draft")
            started (promise)
            answer (promise)
            polls (atom 0)
            pending (invoke (environment state
                                         {client-key (deferred-call started answer (atom []))}
                                         polls (atom [])))]
        (is (= true (deref started 1000 false)))
        (swap! state #(-> %
                          (st/select-run "new-run")
                          (st/note-error "new run error")))
        (deliver answer {:ok true})
        (is (= {:ok true} (deref pending 1000 ::timeout)))
        (is (= "new-run" (:run-id @state)))
        (is (= "new draft" (:input @state)))
        (is (= "new run error" (:error @state)))
        (is (zero? @polls))))))

(deftest stale-decision-response-cannot-clear-new-run-dialog-state
  (let [state (atom (-> (st/initial "http://x:1")
                        (assoc :run-id "old-run"
                               :approvals [{:id "old-approval"}]
                               :approval-id "old-approval"
                               :reply {:kind :deny-note :id "old-approval"})))
        started (promise)
        answer (promise)
        polls (atom 0)
        pending (actions/decide!
                 (environment state
                              {:decide! (deferred-call started answer (atom []))}
                              polls (atom []))
                 "old-approval" {:decision :deny})]
    (is (= true (deref started 1000 false)))
    (swap! state #(-> %
                      (st/select-run "new-run")
                      (assoc :approvals [{:id "new-approval"}]
                             :reply {:kind :custom-answer :id "new-approval"})
                      (st/note-error "new run error")))
    (deliver answer {:ok true})
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= "new-run" (:run-id @state)))
    (is (= {:kind :custom-answer :id "new-approval"} (:reply @state)))
    (is (= "new run error" (:error @state)))
    (is (zero? @polls))))

(deftest duplicate-decisions-are-suppressed-until-the-first-completes
  (let [state (atom (-> (st/initial "http://x:1")
                        (assoc :run-id "old-run" :approvals [{:id "approval-1"}]
                               :approval-id "approval-1")))
        started (promise)
        answer (promise)
        calls (atom [])
        polls (atom 0)
        pending (actions/decide!
                 (environment state
                              {:decide! (deferred-call started answer calls)}
                              polls (atom []))
                 "approval-1" {:decision :allow})]
    (is (= true (deref started 1000 false)))
    (is (nil? (actions/decide!
               (environment state
                            {:decide! (deferred-call started answer calls)}
                            polls (atom []))
               "approval-1" {:decision :deny})))
    (deliver answer {:ok true})
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= [["http://x:1" "approval-1" {:decision :allow}]] @calls))
    (is (nil? (:decision-pending @state)))
    (is (= 1 @polls))))

(deftest slash-intervention-is-asynchronous-and-run-guarded
  (let [state (selected "")
        started (promise)
        answer (promise)
        calls (atom [])
        notes (atom [])
        pending (actions/intervene!
                 (environment state
                              {:intervene! (deferred-call started answer calls)}
                              (atom 0) notes)
                 "compact" "" nil)]
    (is (= true (deref started 1000 false)))
    (is (= [["http://x:1" "old-run" {:kind "compact" :payload "" :branch-id nil}]] @calls))
    (swap! state st/select-run "new-run")
    (deliver answer {:ok false :error "old refusal"})
    (is (= {:ok false :error "old refusal"} (deref pending 1000 ::timeout)))
    (is (empty? @notes) "the old command cannot report into the new run")))

(deftest successful-slash-intervention-reports-its-result
  (let [state (selected "")
        notes (atom [])
        pending (actions/intervene!
                 (environment state
                              {:intervene! (fn [_ _ _] {:ok true :body {}})}
                              (atom 0) notes)
                 "compact" "" nil)]
    (is (= {:ok true :body {}} (deref pending 1000 ::timeout)))
    (is (= ["compact sent — queued"] @notes))))

(deftest interrupted-steering-resumes-with-the-directive
  (let [state (atom (-> (st/initial "http://x:1")
                        (assoc :run-id "old-run" :input "carry on"
                               :detail {:run {:status "interrupted"}})))
        started (promise)
        answer (promise)
        calls (atom [])
        pending (actions/steer!
                 (environment state
                              {:resume! (deferred-call started answer calls)}
                              (atom 0) (atom []))
                 "carry on")]
    (is (= true (deref started 1000 false)))
    (is (= [["http://x:1" "old-run" {:message "carry on"}]] @calls))
    (deliver answer {:ok true})
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= "" (:input @state)))))

(deftest an-older-success-preserves-a-newer-action-refusal
  (let [state (selected "first directive")
        steer-started (promise)
        steer-answer (promise)
        abort-started (promise)
        abort-answer (promise)
        steer (actions/steer!
               (environment state
                            {:intervene! (deferred-call steer-started steer-answer (atom []))}
                            (atom 0) (atom []))
               "first directive")
        abort (actions/abort!
               (environment state
                            {:abort! (deferred-call abort-started abort-answer (atom []))}
                            (atom 0) (atom [])))]
    (is (= true (deref steer-started 1000 false)))
    (is (= true (deref abort-started 1000 false)))
    (deliver abort-answer {:ok false :error "newer abort refusal"})
    (is (= {:ok false :error "newer abort refusal"}
           (deref abort 1000 ::timeout)))
    (is (= "newer abort refusal" (:error @state)))
    (deliver steer-answer {:ok true})
    (is (= {:ok true} (deref steer 1000 ::timeout)))
    (is (= "newer abort refusal" (:error @state))
        "an older success cannot erase the refusal the user needs to see")))

(deftest a-stale-decision-is-refused-after-a-same-run-head-change
  (let [state (atom (-> (st/initial "http://x:1")
                        (assoc :run-id "old-run"
                               :approvals [{:id "new-approval"}]
                               :approval-id "new-approval")))
        calls (atom [])
        pending (actions/decide!
                 (environment state
                              {:decide! (fn [& args] (swap! calls conj args) {:ok true})}
                              (atom 0) (atom []))
                 "old-approval" {:decision :allow})]
    (is (nil? pending))
    (Thread/sleep 100)
    (is (empty? @calls))
    (is (nil? (:decision-pending @state)))
    (is (= [] (:question-answers @state)))))

(deftest a-stale-questionnaire-answer-does-not-change-the-new-head
  (let [answer-for (resolve 'samizdat.tui.state/answer-question-for)]
    (is (ifn? answer-for))
    (when answer-for
      (let [s (-> (st/initial "http://x:1")
                  (assoc :run-id "old-run"
                         :approvals [{:id "new-approval"
                                      :questions [{:question "new?"}]}]
                         :approval-id "new-approval"
                         :question-answers ["keep"]))
            [next done?] (answer-for s "old-approval" ["replace"])]
        (is (identical? s next))
        (is (false? done?))
        (is (= ["keep"] (:question-answers next)))))))

(deftest a-successful-retry-clears-only-that-actions-earlier-refusal
  (let [state (selected "retry this")
        responses (atom [{:ok false :error "temporary refusal"}
                         {:ok true}])
        polls (atom 0)
        env (environment state
                         {:intervene! (fn [& _]
                                        (let [r (first @responses)]
                                          (swap! responses subvec 1)
                                          r))}
                         polls (atom []))]
    (is (= {:ok false :error "temporary refusal"}
           (deref (actions/steer! env "retry this") 1000 ::timeout)))
    (is (= "temporary refusal" (:error @state)))
    (is (= {:ok true} (deref (actions/steer! env "retry this") 1000 ::timeout)))
    (is (nil? (:error @state))
        "the retry owns and clears the earlier refusal")))

(deftest an-older-success-cannot-clear-a-newer-refusal-for-the-same-action
  (let [state (selected "same action")
        first-started (promise)
        first-answer (promise)
        second-started (promise)
        second-answer (promise)
        first (actions/steer!
               (environment state
                            {:intervene! (deferred-call first-started first-answer (atom []))}
                            (atom 0) (atom []))
               "same action")
        second (actions/steer!
                (environment state
                             {:intervene! (deferred-call second-started second-answer (atom []))}
                             (atom 0) (atom []))
                "same action")]
    (is (= true (deref first-started 1000 false)))
    (is (= true (deref second-started 1000 false)))
    (deliver second-answer {:ok false :error "newer steer refusal"})
    (is (= {:ok false :error "newer steer refusal"}
           (deref second 1000 ::timeout)))
    (deliver first-answer {:ok true})
    (is (= {:ok true} (deref first 1000 ::timeout)))
    (is (= "newer steer refusal" (:error @state)))))

(deftest an-older-failure-cannot-replace-a-newer-success-for-the-same-action
  (let [state (selected "same action")
        first-started (promise)
        first-answer (promise)
        second-started (promise)
        second-answer (promise)
        first (actions/steer!
               (environment state
                            {:intervene! (deferred-call first-started first-answer (atom []))}
                            (atom 0) (atom []))
               "same action")
        second (actions/steer!
                (environment state
                             {:intervene! (deferred-call second-started second-answer (atom []))}
                             (atom 0) (atom []))
                "same action")]
    (is (= true (deref first-started 1000 false)))
    (is (= true (deref second-started 1000 false)))
    (deliver second-answer {:ok true})
    (is (= {:ok true} (deref second 1000 ::timeout)))
    (deliver first-answer {:ok false :error "stale steer refusal"})
    (is (= {:ok false :error "stale steer refusal"}
           (deref first 1000 ::timeout)))
    (is (nil? (:error @state))
        "the newer success has already settled this action")))
