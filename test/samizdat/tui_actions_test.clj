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
