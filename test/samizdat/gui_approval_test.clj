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

(ns samizdat.gui-approval-test
  "Approval hiccup and its asynchronous HTTP coordination, without GTK."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.approval-view :as shared]
            [samizdat.gui.api :as api]
            [samizdat.gui.approval :as approval]
            [samizdat.gui.ops :as ops]))

(def ^:private permission
  {:id "p1" :kind :shell :input "frobnicate --all\nand every argument"
   :reason "outside the project" :details "first detail\nsecond detail"
   :always "frobnicate *" :always-exact false
   :gaps [{:gap :depth :required :deep :actual :shallow
           :because {:tool "shell" :turn 4}}]})

(def ^:private questionnaire
  {:id "q1"
   :questions [{:header "storage" :question "which store?"
                :options ["sqlite" "postgres"]
                :descriptions ["local file" "server database"]}
               {:question "which features?" :multi true
                :options ["search" "export" "sync"]}]})

(defn- asking
  ([] (asking questionnaire))
  ([a] (atom {:base-url "http://x:1" :run "r1" :input ""
              :approvals [a] :approval-id (:id a)
              :question-cursor 0 :question-answers []
              :question-selected #{} :question-option 0})))

(defn- recording-http
  [response]
  (let [calls (atom [])]
    {:calls calls
     :http {:approvals (fn [_ _] {:ok true :body {:approvals []}})
            :decide! (fn [base id decision]
                       (swap! calls conj [base id decision])
                       response)}}))

(defn- handlers [state http]
  {:decide (fn [id decision] (approval/decide! state http id decision))
   :answer (fn [id answers] (approval/answer! state http id answers))
   :reply (fn [kind id] (approval/start-reply! state kind id))
   :cancel-reply (fn [] (approval/cancel-reply! state))
   :option (fn [i] (approval/move-option! state i))
   :toggle (fn [i] (approval/toggle-option! state i))
   :input (fn [text] (approval/set-input! state text))
   :submit-reply (fn [] (approval/submit-reply! state http))})

(defn- vectors [form]
  (filter vector? (tree-seq vector? rest form)))

(defn- strings [form]
  (filter string? (tree-seq coll? seq form)))

(defn- labelled [form label]
  (first (filter #(= label (:label (second %))) (vectors form))))

(defn- click [form label]
  (when-let [f (:on-click (second (labelled form label)))]
    (f)))

(deftest successful-intervention-clears-an-unchanged-draft
  (let [state (atom {:base-url "http://x:1" :run "r1" :draft "try modulus 25"})
        pending (ops/send-intervention!
                 state "B1"
                 (fn [_ _ _] {:ok true}))]
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= "" (:draft @state)))))

(deftest refused-intervention-preserves-the-draft
  (let [state (atom {:base-url "http://x:1" :run "finished-run"
                     :draft "try the vendored parser"})
        started (promise)
        answer (promise)
        calls (atom [])
        pending (ops/send-intervention!
                 state nil
                 (fn [base run request]
                   (swap! calls conj [base run request])
                   (deliver started true)
                   @answer))]
    (is (= true (deref started 1000 ::timeout)))
    (is (= "try the vendored parser" (:draft @state)) "the draft stays while the request is in flight")
    (deliver answer {:ok false :status 409 :error "HTTP 409"})
    (is (= {:ok false :status 409 :error "HTTP 409"}
           (deref pending 1000 ::timeout)))
    (is (= [["http://x:1" "finished-run"
             {:branch-id nil :kind "message" :payload "try the vendored parser"}]]
           @calls))
    (is (= "try the vendored parser" (:draft @state)))
    (is (re-find #"HTTP 409" (str (:notice @state))))))

(deftest intervention-errors-preserve-the-draft
  (let [state (atom {:base-url "http://x:1" :run "r1" :draft "keep this directive"})
        started (promise)
        pending (ops/send-intervention!
                 state "B1"
                 (fn [_ _ _]
                   (deliver started true)
                   (throw (ex-info "connection refused" {}))))]
    (is (= true (deref started 1000 ::timeout)))
    (is (= "keep this directive" (:draft @state)))
    (is (= {:ok false :error "connection refused"}
           (deref pending 1000 ::timeout)))
    (is (= "keep this directive" (:draft @state)))
    (is (re-find #"connection refused" (str (:notice @state))))))

(deftest successful-intervention-preserves-newer-typing
  (let [state (atom {:base-url "http://x:1" :run "r1" :draft "first directive"})
        started (promise)
        answer (promise)
        pending (ops/send-intervention!
                 state "B1"
                 (fn [_ _ _]
                   (deliver started true)
                   @answer))]
    (is (= true (deref started 1000 ::timeout)))
    (is (= "first directive" (:draft @state)))
    (swap! state assoc :draft "first directive\nsecond, newer directive")
    (deliver answer {:ok true})
    (is (= {:ok true} (deref pending 1000 ::timeout)))
    (is (= "first directive\nsecond, newer directive" (:draft @state)))))

(deftest stale-intervention-responses-cannot-change-the-newly-selected-run
  (doseq [[outcome response] [["success" {:ok true}]
                              ["failure" {:ok false :error "old refusal"}]]]
    (testing outcome
      (let [state (atom {:base-url "http://x:1" :run "old" :draft "old draft"})
            started (promise)
            answer (promise)
            pending (ops/send-intervention!
                     state "B1"
                     (fn [_ _ _]
                       (deliver started true)
                       @answer))]
        (is (true? (deref started 1000 false)))
        (swap! state assoc :run "new" :draft "new draft" :notice "new notice")
        (deliver answer response)
        (is (= response (deref pending 1000 ::timeout)))
        (is (= "new" (:run @state)))
        (is (= "new draft" (:draft @state)))
        (is (= "new notice" (:notice @state)))))))

(deftest a-deferred-second-new-run-sends-one-request-and-releases-the-guard
  (doseq [[outcome response] [["success" {:ok true :body {:run_id "12345678-r7"}}]
                              ["failure" {:ok false :error "HTTP 503"}]]]
    (testing outcome
      (let [draft {:problem "build a parser" :max-turns "4" :beam-width "2"
                   :seed-run "r0" :model "model" :reasoning-effort "high"}
            state (atom {:base-url "http://x:1" :composing? true :form draft})
            calls (atom [])
            attached (atom [])
            requested (promise)
            answer (promise)
            start-run (fn [base body]
                        (swap! calls conj [base body])
                        (deliver requested true)
                        @answer)
            pending (ops/start! state {:problem "build a parser"} "starting…"
                                start-run #(swap! attached conj %))]
        (is (true? @requested))
        (is (true? (:starting? @state)))
        (is (nil? (ops/start! state {:problem "build a parser"} "starting…"
                              start-run #(swap! attached conj %))))
        (is (= 1 (count @calls)))
        (deliver answer response)
        (is (= response @pending))
        (is (false? (:starting? @state)))
        (is (= 1 (count @calls)))
        (if (:ok response)
          (do
            (is (false? (:composing? @state)))
            (is (= {:problem "" :max-turns "" :beam-width "" :seed-run ""
                    :model "" :reasoning-effort ""}
                   (:form @state)))
            (is (= ["12345678-r7"] @attached)))
          (do
            (is (true? (:composing? @state)))
            (is (= draft (:form @state)))
            (is (empty? @attached))
            (is (re-find #"HTTP 503" (str (:notice @state))))))))))

(deftest simultaneous-new-run-starts-launch-only-one-request
  (let [state (atom {:base-url "http://x:1" :composing? true
                     :form {:problem "build a parser"}})
        calls (atom 0)
        requested (promise)
        second-request (promise)
        answer (promise)
        entered (atom 0)
        both (promise)
        release (promise)
        swap* swap!
        swap-vals* swap-vals!
        pause (fn []
                (when (= 2 (swap! entered inc)) (deliver both true))
                (deref both 1000 nil)
                (deref release 1000 nil))
        pause-transition (fn [f]
                           (fn [s & args]
                             (let [next-state (apply f s args)]
                               (when (and (not (:starting? s)) (:starting? next-state))
                                 (pause))
                               next-state)))
        start-run (fn [_ _]
                    (let [n (swap! calls inc)]
                      (deliver requested true)
                      (when (= 2 n) (deliver second-request true))
                      @answer))]
    (with-redefs [swap! (fn [a f & args]
                          (apply swap* a (pause-transition f) args))
                  swap-vals! (fn [a f & args]
                               (apply swap-vals* a (pause-transition f) args))]
      (let [a (future (ops/start! state {:problem "build a parser"} "starting…"
                                  start-run (fn [_])))
            b (future (ops/start! state {:problem "build a parser"} "starting…"
                                  start-run (fn [_])))]
        (is (= true (deref both 1000 ::timeout)))
        (deliver release true)
        (let [pending-a (deref a 1000 ::timeout)
              pending-b (deref b 1000 ::timeout)]
          (is (not= ::timeout pending-a))
          (is (not= ::timeout pending-b))
          (is (= true (deref requested 1000 ::timeout)))
          (deref second-request 100 nil)
          (is (= 1 @calls))
          (deliver answer {:ok false :error "HTTP 503"})
          (doseq [pending [pending-a pending-b]]
            (when (future? pending) @pending)))))))

(deftest connection-refreshes-approvals-and-approval-events-request-refresh
  (let [state (atom {:base-url "http://x:1" :run "r1"})
        calls (atom [])]
    @(approval/refresh! state (fn [base run]
                                (swap! calls conj [base run])
                                {:ok true :body {:approvals [permission]}}))
    (is (= [["http://x:1" "r1"]] @calls))
    (is (= ["p1"] (mapv :id (:approvals @state))))
    (is (= "p1" (:approval-id @state))))
  (testing "only approval events trigger the event-driven path"
    (is (approval/approval-event? {:kind "approval"}))
    (is (approval/approval-event? {:event :approval}))
    (is (approval/approval-event? {:data {:kind "approval"}}))
    (is (not (approval/approval-event? {:kind "turn"})))))

(deftest a-response-for-the-run-left-behind-is-dropped
  (let [state (atom {:base-url "http://x:1" :run "old" :input "old draft"
                     :reply {:kind :custom-answer :id "old-approval"}
                     :approval-pending "old-approval"})]
    @(approval/refresh! state (fn [_ _]
                                (swap! state #(-> % (assoc :run "new") approval/reset-run))
                                {:ok true :body {:approvals [permission]}}))
    (is (= "new" (:run @state)))
    (is (empty? (:approvals @state)))
    (is (nil? (:approval-id @state)))
    (is (= "" (:input @state)))
    (is (nil? (:reply @state)))
    (is (nil? (:approval-pending @state)))))

(deftest overlapping-refreshes-coalesce-and-run-one-fresh-follow-up
  (let [state (asking permission)
        calls (atom 0)
        started [(promise) (promise)]
        gates [(promise) (promise)]
        fetch (fn [_ _]
                (let [n (dec (swap! calls inc))]
                  (deliver (nth started n) true)
                  @(nth gates n)))
        worker (approval/refresh! state fetch)]
    (is (true? (deref (nth started 0) 1000 false)))
    (approval/refresh! state fetch)
    (is (= 1 @calls) "the second request coalesces behind the one in flight")
    (deliver (nth gates 0) {:ok true :body {:approvals [permission]}})
    (is (true? (deref (nth started 1) 1000 false))
        "the coalesced request still earns a fresh follow-up")
    (deliver (nth gates 1) {:ok true :body {:approvals []}})
    (is (= {:ok true :body {:approvals []}} (deref worker 1000 ::timeout)))
    (is (= 2 @calls) "a burst becomes one in-flight request and one follow-up")
    (is (empty? (:approvals @state)) "the follow-up's fresher answer is visible")
    (is (nil? (:approval-id @state)))))

(deftest duplicate-decisions-are-suppressed-while-the-first-is-in-flight
  (let [state (asking permission)
        gate (promise)
        started (promise)
        calls (atom [])
        http {:decide! (fn [_ id decision]
                         (swap! calls conj [id decision])
                         (deliver started true)
                         @gate)
              :approvals (fn [_ _] {:ok true :body {:approvals []}})}
        first-call (approval/decide! state http "p1" {:decision :allow})]
    @started
    (is (nil? (approval/decide! state http "p1" {:decision :deny})))
    (deliver gate {:ok true})
    @first-call
    (is (= [["p1" {:decision :allow}]] @calls))
    (is (nil? (:approval-pending @state)))))

(deftest permission-view-shows-the-whole-request-and-all-decision-paths
  (let [state (asking permission)
        decisions (atom [])
        replies (atom [])
        form (approval/view @state {:decide #(swap! decisions conj [%1 %2])
                                    :reply #(swap! replies conj [%1 %2])})
        text (strings form)]
    (is (some #{"frobnicate --all\nand every argument"} text))
    (is (some #{"first detail\nsecond detail"} text))
    (is (some #{"outside the project"} text))
    (is (some #(and (string? %) (.contains ^String % "depth")
                    (.contains ^String % "deep")
                    (.contains ^String % "shallow")
                    (.contains ^String % "shell")) text))
    (click form "allow once")
    (click form "allow always")
    (click form "deny")
    (click form "deny + note")
    (is (= [["p1" {:decision :allow}]
            ["p1" {:decision :allow :always true}]
            ["p1" {:decision :deny}]] @decisions))
    (is (= [[:deny-note "p1"]] @replies))))

(deftest deny-note-uses-the-shared-reply-state
  (let [state (asking permission)
        {:keys [http calls]} (recording-http {:ok false :error "HTTP 409"})]
    (approval/start-reply! state :deny-note "p1")
    (approval/set-input! state "use the vendored copy")
    @(approval/submit-reply! state http)
    (is (= [["http://x:1" "p1" {:decision :deny :note "use the vendored copy"}]] @calls))
    (is (= "use the vendored copy" (:input @state)) "a failed note is not erased")
    (is (= {:kind :deny-note :id "p1"} (:reply @state)))
    (is (= "p1" (:approval-id @state)))))

(deftest single-select-progresses-and-submits-only-the-complete-answer-set
  (let [state (asking)
        {:keys [http calls]} (recording-http {:ok true})]
    (is (nil? (approval/answer! state http "q1" ["sqlite"])))
    (is (= 1 (:question-cursor @state)))
    (is (empty? @calls))
    @(approval/answer! state http "q1" ["sqlite" ["search"]])
    (is (= [["http://x:1" "q1" {:decision :answer :answers ["sqlite" ["search"]]}]] @calls))))

(deftest single-and-multi-select-buttons-use-the-shared-transitions
  (let [single (asking (update questionnaire :questions #(vector (first %))))
        {:keys [http calls]} (recording-http {:ok true})
        form (approval/view @single (handlers single http))]
    @(click form "postgres")
    (is (= [["http://x:1" "q1" {:decision :answer :answers ["postgres"]}]] @calls)))
  (let [state (asking (update questionnaire :questions #(vector (second %))))
        {:keys [http calls]} (recording-http {:ok true})]
    (approval/toggle-option! state 2)
    (approval/toggle-option! state 0)
    (is (= #{0 2} (:question-selected @state)))
    @(click (approval/view @state (handlers state http)) "confirm")
    (is (= [["http://x:1" "q1" {:decision :answer :answers [["search" "sync"]]}]] @calls))))

(deftest empty-multi-select-confirmation-does-not-submit
  (let [state (asking (update questionnaire :questions #(vector (second %))))
        {:keys [http calls]} (recording-http {:ok true})
        form (approval/view @state (handlers state http))]
    (is (false? (:sensitive (second (labelled form "confirm")))))
    (is (nil? (click form "confirm")))
    (is (empty? @calls))
    (is (= [] (:question-answers @state)))))

(deftest custom-answers-and-rejection-reach-the-decision-endpoint
  (let [state (asking (update questionnaire :questions #(vector (first %))))
        {:keys [http calls]} (recording-http {:ok true})
        form (approval/view @state (handlers state http))]
    (click form "your own answer")
    (let [reply-form (approval/view @state (handlers state http))]
      ((:on-change (second (first (filter #(= :entry (first %)) (vectors reply-form)))))
       "  my own store  "))
    @(approval/submit-reply! state http)
    (is (= [["http://x:1" "q1" {:decision :answer :answers ["my own store"]}]] @calls))
    (is (= "" (:input @state))))
  (let [state (asking)
        decisions (atom [])]
    (click (approval/view @state {:decide #(swap! decisions conj [%1 %2])}) "reject")
    (is (= [["q1" {:decision :deny :note "rejected"}]] @decisions))))

(deftest a-failed-custom-answer-restores-the-visible-question-and-draft
  (let [state (asking (update questionnaire :questions #(vector (first %))))
        {:keys [http]} (recording-http {:ok false :error "connection refused"})]
    (approval/start-reply! state :custom-answer "q1")
    (approval/set-input! state "draft answer")
    @(approval/submit-reply! state http)
    (is (= "q1" (:approval-id @state)))
    (is (= "which store?" (:question (shared/current-question @state))))
    (is (= "draft answer" (:input @state)))
    (is (= {:kind :custom-answer :id "q1"} (:reply @state)))
    (is (nil? (:approval-pending @state)))
    (is (re-find #"connection refused" (str (:notice @state))))))

(deftest an-open-ended-question-can-be-rejected-while-its-editor-is-visible
  (let [state (asking {:id "q9" :questions [{:question "what should we use?"}]})
        decisions (atom [])
        form (approval/view @state {:decide #(swap! decisions conj [%1 %2])})]
    (is (some #(= :entry (first %)) (vectors form)))
    (click form "reject")
    (is (= [["q9" {:decision :deny :note "rejected"}]] @decisions))))

(deftest a-local-answer-cannot-leave-a-pending-lock-on-the-next-approval
  (let [state (asking)
        {:keys [http]} (recording-http {:ok true})
        raced (atom false)]
    (add-watch state ::head-change
               (fn [_ _ _ new]
                 (when (and (not @raced) (= "q1" (:approval-id new))
                            (= ["sqlite"] (:question-answers new)))
                   (reset! raced true)
                   (swap! state shared/apply-approvals
                          {:ok true :body {:approvals [{:id "q2" :questions [{:question "next?"}]}]}}))))
    (approval/answer! state http "q1" ["sqlite"])
    (remove-watch state ::head-change)
    (is @raced)
    (is (= "q2" (:approval-id @state)))
    (is (nil? (:approval-pending @state)))))

(deftest successful-journal-polls-fetch-approvals-that-only-live-on-the-bus
  (let [state (atom {:base-url "http://x:1" :run "r1"})
        calls (atom 0)
        poll-status (resolve 'samizdat.gui.approval/poll-status!)
        fetch (fn [_ _]
                (swap! calls inc)
                {:ok true :body {:approvals [permission]}})]
    (is (ifn? poll-status))
    (when poll-status
      (with-redefs [samizdat.api.client/journal-since
                    (fn [& _] {:ok true :body {:events []}})]
        (let [{:keys [stop!]} (api/start-poller!
                               {:base "http://x:1" :run-id "r1"
                                :on-status #(poll-status state fetch nil %)})]
          (try
            (is (loop [n 0]
                  (cond (>= @calls 2) true
                        (< n 200) (do (Thread/sleep 10) (recur (inc n)))
                        :else false))
                "empty journal batches still deliver periodic approval refreshes")
            (is (= ["p1"] (mapv :id (:approvals @state))))
            (finally (stop!))))))))

(defn- raced-call
  [original competitor a f args]
  (let [before @a]
    (apply f before args)
    (reset! a (competitor before))
    (apply original a f args)))

(deftest a-retried-refresh-claim-cannot-start-a-refused-request
  (let [state (atom {:base-url "http://x:1" :run "r1"})
        calls (atom 0)
        swap* swap!
        swap-vals* swap-vals!
        competitor #(assoc % :approval-refresh-token (Object.))
        pending (with-redefs [swap! (fn [a f & args]
                                      (raced-call swap* competitor a f args))
                              swap-vals! (fn [a f & args]
                                           (raced-call swap-vals* competitor a f args))]
                  (approval/refresh! state (fn [& _] (swap! calls inc) {:ok true})))]
    (is (nil? pending)
        "a speculative request is discarded when a competing refresh wins")
    (Thread/sleep 100)
    (is (zero? @calls))))

(deftest a-retried-decision-claim-cannot-post-a-refused-decision
  (let [state (asking permission)
        calls (atom [])
        swap* swap!
        swap-vals* swap-vals!
        competitor #(assoc % :approval-pending "competing-decision")
        pending (with-redefs [swap! (fn [a f & args]
                                      (raced-call swap* competitor a f args))
                              swap-vals! (fn [a f & args]
                                           (raced-call swap-vals* competitor a f args))]
                  (approval/decide! state {:decide! (fn [& args]
                                                     (swap! calls conj args)
                                                     {:ok true})}
                                    "p1" {:decision :allow}))]
    (is (nil? pending)
        "a speculative decision is discarded when a competing decision wins")
    (Thread/sleep 100)
    (is (empty? @calls))))
