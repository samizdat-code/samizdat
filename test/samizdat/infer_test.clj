;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.infer-test
  "The inference step and its drivers, driven from literal tapes.

  That every test here runs with no db, no provider, no branch map and no
  config IS the property under test: the step is a pure function of a tape
  plus an injected `complete`. Before the refactor there was no way to ask
  what a turn would do without running one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mycelium.cell :as cell]
            [samizdat.agent.budget :as budget]
            [samizdat.agent.compaction :as cmp]
            [samizdat.agent.context-fit :as context-fit]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.infer :as infer]
            [samizdat.cells :as cells]
            [samizdat.llm.accounting :as accounting]
            [samizdat.events :as events]
            [samizdat.llm.message :as message]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.tool-search :as tool-search]
            [samizdat.store.journal :as journal]
            [samizdat.tape :as tape]))

(defn- fenced
  "An assistant reply carrying one well-formed tool call."
  [tool args]
  (str "Here is what I will do.\n\n```tool-call\n"
       "{\"name\": \"" tool "\", \"args\": " args "}\n```"))

(defn- replying
  "A `complete` stub: answers with `content`, recording every tape it saw."
  ([content] (replying content (atom [])))
  ([content seen]
   (fn [t]
     (swap! seen conj t)
     {:ok true :response {:content content :finish-reason "stop"}})))

(defn- failing [msg]
  (fn [_] {:ok false :error msg}))

(def ^:private base-tape
  {:id "B1"
   :messages [{:role "system" :content "sys"}
              {:role "user" :content "## Problem\n\ndo the thing"}]
   :turns []})

;; --- the projection ---------------------------------------------------------

(deftest of-branch-carries-exactly-what-a-call-depends-on
  (let [b (assoc (state/new-branch {:id "B1" :problem "p"
                                    :messages [{:role "user" :content "u"}]})
                 :prefill "```tool-call\n"
                 :force-tool {:name "done"})
        t (infer/of-branch b)]
    (is (= #{:id :messages :turns :prefill :force-tool :refused-tool :squeeze
             :advertised-tools :advertised-tool-docs}
           (set (keys t)))
        "nothing else about a branch can change what the model is sent —
         :squeeze is on the list because the overflow squeeze legitimately
         changes how much history the wire sees (karamazov-d41), and
         :refused-tool because a llama.cpp endpoint may sample the next
         decision without the tool the harness just refused (karamazov-fp21.1)")
    (is (= "B1" (:id t)))
    (is (= "```tool-call\n" (:prefill t)))))

(deftest into-branch-writes-messages-and-clears-the-per-turn-knobs
  (let [b (assoc (state/new-branch {:id "B1" :problem "p"})
                 :prefill "x" :force-tool {:name "done"} :thesis {:goal "keep me"})
        b' (infer/into-branch b (assoc base-tape :messages [{:role "user" :content "new"}]))]
    (is (= [{:role "user" :content "new"}] (:messages b')))
    (is (nil? (:prefill b')) "one steer forecloses prose on ONE turn")
    (is (nil? (:force-tool b')))
    (is (= {:goal "keep me"} (:thesis b')) "everything else about the branch survives")))

;; --- absorb is pure ---------------------------------------------------------

(deftest absorb-appends-the-reply-and-parses-the-call
  (let [{:keys [tape parsed said]}
        (infer/absorb base-tape {:content (fenced "eval" "{\"code\": \"(+ 1 2)\"}")
                                 :finish-reason "stop"})]
    (is (= "eval" (:name parsed)))
    (is (= 3 (tape/depth (:messages tape))) "the reply is appended, once")
    (is (= "assistant" (:role (last (:messages tape)))))
    (is (= said (:content (last (:messages tape))))
        "what is stored is what was said, opener included")))

(deftest absorb-does-not-mutate-its-input
  (let [t base-tape]
    (infer/absorb t {:content "prose" :finish-reason "stop"})
    (is (= 2 (tape/depth (:messages t)))
        "the accumulator is a value — a fork of it cannot see this call")))

(deftest absorb-reattaches-a-prefilled-opener
  (let [t (assoc base-tape :prefill "```tool-call\n")
        {:keys [tape parsed]}
        (infer/absorb t {:content "{\"name\": \"done\", \"args\": {}}\n```"
                         :finish-reason "stop"})]
    (is (= "done" (:name parsed))
        "without the prefill this parses as a no-call — the failure prefill exists to prevent")
    (is (nil? (:prefill tape)) "the knob is cleared by absorb, not by its caller")))

(deftest absorb-does-not-reattach-a-prefill-the-adapter-dropped
  ;; The tape asked for a prefill, but the client reports :prefilled nil
  ;; because the adapter could not continue a trailing assistant message (GLM,
  ;; DeepSeek /v1). The model therefore emitted its OWN complete fence, and
  ;; reattaching the tape's opener would store a DOUBLED ```tool-call that the
  ;; model then imitates on every later turn (karamazov-0r8s).
  (let [t (assoc base-tape :prefill "```tool-call\n")
        content "```tool-call\n{\"name\": \"done\", \"args\": {}}\n```"
        {:keys [tape parsed said]}
        (infer/absorb t {:content content :finish-reason "stop" :prefilled nil})]
    (is (= "done" (:name parsed)) "the model's own fence still parses")
    (is (= content said) "stored exactly as said — no second opener glued in front")
    (is (not (str/includes? said "```tool-call\n```tool-call"))
        "the doubled opener the bug produced is gone"))
  (testing "and when the client DID send the prefill, :prefilled reattaches it"
    (let [t (assoc base-tape :prefill "```tool-call\n")
          {:keys [said parsed]}
          (infer/absorb t {:content "{\"name\": \"done\", \"args\": {}}\n```"
                           :finish-reason "stop" :prefilled "```tool-call\n"})]
      (is (= "done" (:name parsed)))
      (is (str/starts-with? said "```tool-call\n{\"name\"")
          "the opener the provider continued is put back exactly once"))))

;; --- the step ---------------------------------------------------------------

(deftest step-advances-the-tape-on-a-reply
  (let [seen (atom [])
        {:keys [tape call parsed]}
        (infer/step (replying (fenced "read" "{\"path\": \"x\"}") seen) base-tape)]
    (is (:ok call))
    (is (= "read" (:name parsed)))
    (is (= 3 (tape/depth (:messages tape))))
    (is (= 1 (count @seen)) "one call, not a retry ladder on a clean reply")))

(deftest step-leaves-the-tape-alone-when-the-provider-fails
  (let [{:keys [tape call parsed]} (infer/step (failing "socket reset") base-tape)]
    (is (false? (:ok call)))
    (is (= "socket reset" (:error call)))
    (is (nil? parsed))
    (is (= base-tape tape)
        "a failed call costs the turn, never the history — retry continues from here")))

;; --- bounce: the non-committing probe ---------------------------------------

(deftest bounce-reads-the-outcome-and-leaves-the-tape-fixed
  (let [out (infer/bounce (replying (fenced "grep" "{\"pattern\": \"x\"}")) base-tape)]
    (is (= "grep" (:name (:parsed out))) "it tells you what the model WOULD call")
    (is (= 2 (:depth out)) "the fixed depth — the tape did not move")
    (is (= 2 (tape/depth (:messages base-tape))))))

(deftest bounce-has-no-tool-seam-at-all
  (let [out (infer/bounce (replying (fenced "shell" "{\"cmd\": \"rm -rf /\"}")) base-tape)]
    (is (= "shell" (:name (:parsed out))))
    (is (nil? (:result out))
        "a probe reports the call it saw and cannot run it — there is no dispatch here")
    (is (nil? (:branch out))
        "and no branch bookkeeping: a bad probe is not a branch that called badly")))

(deftest bounce-returns-a-provider-failure-as-data
  (let [out (infer/bounce (failing "429") base-tape)]
    (is (= "429" (:error out)))
    (is (= 2 (:depth out)))))

(deftest bounce-catches-a-throwing-complete
  (let [out (infer/bounce (fn [_] (throw (ex-info "boom" {}))) base-tape)]
    (is (re-find #"probe failed: boom" (:error out))
        "a probe never throws into the loop that scheduled it")))

;; --- trampoline: N probes off one fixed point -------------------------------

(deftest trampoline-fans-inputs-without-accumulating-them
  (let [seen (atom [])
        out (infer/trampoline (replying "ok" seen) base-tape ["try A" "try B" "try C"])]
    (is (= 3 (count (:bounces out))))
    (is (= ["try A" "try B" "try C"] (mapv :input (:bounces out))))
    (is (= 2 (:depth out)) "the fixed point never moves")
    (testing "each bounce forked the SAME prefix — inputs never see each other"
      (is (= [3 3 3] (mapv (comp count :messages) @seen)))
      (is (= ["try A" "try B" "try C"]
             (mapv (comp :content last :messages) @seen))))))

(deftest trampoline-isolates-a-failing-bounce
  (let [flaky (fn [t]
                (if (= "bad" (:content (last (:messages t))))
                  {:ok false :error "nope"}
                  {:ok true :response {:content "fine" :finish-reason "stop"}}))
        out (infer/trampoline flaky base-tape ["good" "bad" "good"])]
    (is (= [nil "nope" nil] (mapv :error (:bounces out)))
        "one failed probe does not sink the scan — unlike a fold, bounces are independent")))

(deftest trampoline-over-nothing-is-not-an-error
  (is (= [] (:bounces (infer/trampoline (replying "ok") base-tape [])))))

;; --- ab: one probe, N configs -----------------------------------------------

(deftest ab-varies-the-config-and-holds-the-tape
  (let [complete-for (fn [vk] (replying (str "answer from " (name vk))))
        out (infer/ab complete-for base-tape [:cheap :strong] "which approach?")]
    (is (= #{:cheap :strong} (set (keys (:variants out)))))
    (is (re-find #"answer from cheap" (:said (get-in out [:variants :cheap]))))
    (is (re-find #"answer from strong" (:said (get-in out [:variants :strong]))))
    (is (= 2 (:depth out)) "the parent never moves")))

(deftest ab-reports-a-failing-arm-as-data
  (let [complete-for (fn [vk] (if (= :broken vk) (failing "no such model") (replying "ok")))
        out (infer/ab complete-for base-tape [:fine :broken])]
    (is (nil? (:error (get-in out [:variants :fine]))))
    (is (= "no such model" (:error (get-in out [:variants :broken])))
        "one failed arm does not sink the fan")))

(deftest ab-without-an-input-probes-the-tape-as-it-stands
  (let [seen (atom [])
        _ (infer/ab (fn [_] (replying "ok" seen)) base-tape [:a])]
    (is (= 2 (count (:messages (first @seen))))
        "no probe turn is invented when the caller did not ask for one")))

;; --- the context squeeze (karamazov-d41) ------------------------------------

(deftest the-overflow-squeeze-scales-the-compaction-budget
  (let [budget {:keep-pairs 10 :compaction-chars 50000}
        policy {:factor 0.5 :min-keep-pairs 1 :min-compaction-chars 5000}]
    (testing "no squeeze, no change"
      (is (= {:keep-pairs 10 :threshold-chars 50000}
             (infer/squeezed-budget budget nil policy)))
      (is (= {:keep-pairs 10 :threshold-chars 50000}
             (infer/squeezed-budget budget 0 policy))))
    (testing "each level halves"
      (is (= {:keep-pairs 5 :threshold-chars 25000}
             (infer/squeezed-budget budget 1 policy)))
      (is (= {:keep-pairs 2 :threshold-chars 12500}
             (infer/squeezed-budget budget 2 policy))))
    (testing "the floors hold no matter how deep the squeeze"
      (is (= {:keep-pairs 1 :threshold-chars 5000}
             (infer/squeezed-budget budget 9 policy))
          "a squeezed branch still sees its current exchange"))))

(deftest the-squeeze-rides-the-tape
  (let [b (-> (state/new-branch {:id "B1" :problem "p"})
              state/squeeze-context
              state/squeeze-context)]
    (is (= 2 (:context-squeeze b)))
    (is (= 2 (:squeeze (infer/of-branch b))))))

(deftest a-context-overflow-squeezes-instead-of-asking-for-a-retry
  (with-redefs [journal/record-turn! (fn [& _] nil)]
    (let [b (state/new-branch {:id "B1" :problem "p"})
          n (count (:messages b))]
      (testing "overflow: squeeze, and DO NOT append — a message grows the
                very thing that overflowed, and the model never saw the
                failure anyway"
        (let [b' (aloop/provider-error-step {} b 3 "context exceeded"
                                            :context-overflow)]
          (is (= 1 (:context-squeeze b')))
          (is (= n (count (:messages b'))))))
      (testing "any other provider failure keeps the try-again message"
        (let [b' (aloop/provider-error-step {} b 3 "boom" :call-failed)]
          (is (nil? (:context-squeeze b')))
          (is (= (inc n) (count (:messages b')))))))))

(deftest provider-errors-in-a-row-are-counted-and-an-answer-clears-them
  ;; What lets a branch stop when its provider is down instead of spending
  ;; its whole turn cap on failed calls: run 3020cbca's supervisor made 2810
  ;; calls to a server that was not running (karamazov-e8iw).
  (with-redefs [journal/record-turn! (fn [& _] nil)]
    (let [b (state/new-branch {:id "B1" :problem "p"})
          failed (-> b
                     (#(aloop/provider-error-step {} % 1 "refused" :call-failed))
                     (#(aloop/provider-error-step {} % 2 "refused" :call-failed)))]
      (is (= 2 (:consecutive-provider-errors failed)))
      (is (zero? (or (:consecutive-provider-errors
                      (aloop/absorb-response failed {:content "fine"} 3))
                     0))
          "a call that answered is not an outage"))))

;; --- prefix identity: what the cache could have kept ------------------------

(deftest a-request-digest-names-one-request
  ;; One number for the whole request, so a replayed case can say whether the
  ;; tape a candidate renders at turn k is the tape the recorded reply
  ;; answered (karamazov-luqc.2). Over the wire fingerprint, so it measures
  ;; what the provider was sent; stable across processes, because it is
  ;; written to the journal and read back by a later one.
  (let [fp (fn [& contents]
             (infer/wire-fingerprint (map (fn [c] {:role "user" :content c}) contents)))]
    (is (= (infer/request-digest (fp "sys" "problem"))
           (infer/request-digest (fp "sys" "problem"))))
    (is (not= (infer/request-digest (fp "sys" "problem"))
              (infer/request-digest (fp "sys" "problem!")))
        "one character of one message is a different request")
    (is (not= (infer/request-digest (fp "sys" "problem"))
              (infer/request-digest (fp "sys" "problem" "more")))
        "one more message is a different request")
    (is (integer? (infer/request-digest (fp "x"))) "a number the journal can hold")))

(deftest prefix-stats-classifies-what-changed-since-the-last-render
  ;; A cache miss has three shapes and the journal could not tell them apart
  ;; (karamazov-o4wm.1): a branch's first call, the normal turn where only
  ;; the tail moved (the previous last message lost its ledger, two new
  ;; messages arrived), and a rewrite of history behind the tail — a fold, a
  ;; cap, a withheld digest — which is the only one compaction is responsible
  ;; for. The comparison is over per-message fingerprints of the WIRE
  ;; messages, so it measures exactly what the provider hashed.
  (let [fp (fn [& contents]
             (infer/wire-fingerprint (map (fn [c] {:role "user" :content c}) contents)))]
    (testing "no previous render: everything is new"
      (is (= {:change :first :stable-msgs 0 :stable-chars 0 :chars 5}
             (infer/prefix-stats nil (fp "abcde")))))
    (testing "an identical render is all tail"
      (let [w (fp "sys" "problem" "reply")]
        (is (= :tail (:change (infer/prefix-stats w w))))
        (is (= 15 (:stable-chars (infer/prefix-stats w w))))))
    (testing "the normal turn: the previous last message changed and two were appended"
      (let [prev (fp "sys" "problem" "result<ledger>")
            cur (fp "sys" "problem" "result" "call" "result2<ledger>")
            s (infer/prefix-stats prev cur)]
        (is (= :tail (:change s)))
        (is (= 2 (:stable-msgs s)))
        (is (= 10 (:stable-chars s)) "sys + problem")
        (is (= (count "sysproblemresultcallresult2<ledger>") (:chars s)))))
    (testing "a message behind the tail was rewritten: compaction's doing"
      (let [prev (fp "sys" "problem" "big result" "call" "result2")
            cur (fp "sys" "problem" "[unloaded] t3" "call" "result2" "call2" "r3")
            s (infer/prefix-stats prev cur)]
        (is (= :rewritten (:change s)))
        (is (= 2 (:stable-msgs s)))
        (is (= 10 (:stable-chars s)))
        ;; WHICH message, and what it turned into (karamazov-pdes): a
        ;; rewrite with no compaction note beside it was unattributable on
        ;; the first live run of these columns, and the role and the sizes
        ;; are what tell a digest from a re-rendered pinned block.
        (is (= "user" (:changed-role s)))
        (is (= (count "big result") (:was-chars s)))
        (is (= (count "[unloaded] t3") (:now-chars s)))))
    (testing "a first render names no changed message"
      (is (nil? (:changed-role (infer/prefix-stats nil (fp "a"))))))))

(deftest complete-fn-fingerprints-the-wire-it-sent
  ;; The fingerprint rides the RESPONSE, beside :prefilled, because that is
  ;; the value the loop already threads from the call to the journal. It is
  ;; taken over the PREPARED messages — think blocks stripped, stale ledgers
  ;; dropped — since those are the bytes the provider saw.
  (with-redefs [samizdat.llm.client/chat
                (fn [_ _ messages _]
                  {:content (fenced "verify" "{\"claim\": \"c\"}")
                   :finish-reason "stop"
                   :sent-count (count messages)})]
    (let [r ((infer/complete-fn {:llm-adapter ::a :llm-config {}})
             base-tape)
          wire (get-in r [:response :wire])]
      (is (:ok r))
      (is (vector? wire))
      (is (= (get-in r [:response :sent-count]) (count wire))
          "one fingerprint per message that went over the wire")
      (is (every? (fn [[h n]] (and (integer? h) (integer? n))) wire)
          "each entry is [hash chars]"))))

(deftest absorb-response-records-the-prefix-identity-on-the-branch
  (let [b (state/new-branch {:id "B1" :problem "p"})
        reply (fenced "verify" "{\"claim\": \"c\"}")
        wire1 (infer/wire-fingerprint (:messages b))
        {b1 :branch} (aloop/absorb-response
                      b {:content reply :finish-reason "stop"
                         :wire wire1 :forced "done" :forced-via :grammar} 1)]
    (testing "the first call is :first, and the forced tool is remembered"
      (is (= :first (get-in b1 [:last-prefix :change])))
      (is (= "done" (get-in b1 [:last-prefix :forced-tool])))
      (is (= :grammar (get-in b1 [:last-prefix :forced-via])) "and how")
      (is (= wire1 (:last-wire b1))))
    (testing "the next call compares against the previous render"
      (let [wire2 (infer/wire-fingerprint
                   (conj (:messages b1) {:role "user" :content "result"}))
            {b2 :branch} (aloop/absorb-response
                          b1 {:content reply :finish-reason "stop" :wire wire2} 2)]
        (is (= :tail (get-in b2 [:last-prefix :change])))
        (is (nil? (get-in b2 [:last-prefix :forced-tool])))
        (is (= wire2 (:last-wire b2)))))
    (testing "a response without a fingerprint (a stub, a replay) records nothing"
      (let [{b3 :branch} (aloop/absorb-response
                          b1 {:content reply :finish-reason "stop"} 2)]
        (is (= (:last-wire b1) (:last-wire b3)) "the last real render is kept")
        (is (nil? (:last-prefix b3)))))))

(deftest absorb-response-records-the-model-that-answered-once-per-branch
  ;; karamazov-a28w. Not a column on every turn — the branch carries the
  ;; model the provider REPORTED as context, and a change marker rides for
  ;; exactly one step so the journal step can note it: once when the branch
  ;; first learns what it is running on, again only if the provider switches.
  (let [b (state/new-branch {:id "B1" :problem "p"})
        reply (fenced "verify" "{\"claim\": \"c\"}")
        {b1 :branch} (aloop/absorb-response
                      b {:content reply :finish-reason "stop"
                         :model-requested "deepseek-chat" :model "deepseek-v4-flash"} 1)]
    (testing "the first response names the model, and marks it as news"
      (is (= "deepseek-v4-flash" (:model-reported b1)))
      (is (= {:requested "deepseek-chat" :reported "deepseek-v4-flash" :was nil}
             (:model-change b1))))
    (testing "the same model again is not news"
      (let [{b2 :branch} (aloop/absorb-response
                          b1 {:content reply :finish-reason "stop"
                              :model-requested "deepseek-chat" :model "deepseek-v4-flash"} 2)]
        (is (= "deepseek-v4-flash" (:model-reported b2)))
        (is (nil? (:model-change b2)) "the marker is cleared, not left over from turn 1")))
    (testing "a provider switching mid-run is news again, with what it was"
      (let [{b3 :branch} (aloop/absorb-response
                          b1 {:content reply :finish-reason "stop"
                              :model-requested "deepseek-chat" :model "deepseek-v4-pro"} 2)]
        (is (= "deepseek-v4-pro" (:model-reported b3)))
        (is (= {:requested "deepseek-chat" :reported "deepseek-v4-pro" :was "deepseek-v4-flash"}
               (:model-change b3)))))
    (testing "a response naming no model (a stub, a replay) changes nothing"
      (let [{b4 :branch} (aloop/absorb-response
                          b1 {:content reply :finish-reason "stop"} 2)]
        (is (= "deepseek-v4-flash" (:model-reported b4)) "what was known is kept")
        (is (nil? (:model-change b4)))))))

(deftest both-journal-paths-hand-the-turn-its-wall-clock-and-the-model-marker
  ;; karamazov-a28w. record-turn! is built by NAME from what the loop passes,
  ;; so a key the loop does not name never reaches the row — the same gap
  ;; that left reasoning_text empty for every run before it was threaded.
  ;; Both paths that journal a response: the tool path and the no-call path.
  (let [rows (atom [])
        b (assoc (state/new-branch {:id "B1" :problem "p"})
                 :model-change {:requested "m" :reported "m'" :was nil})
        response {:content "x" :finish-reason "stop" :elapsed-ms 4321}]
    (with-redefs [journal/record-turn! (fn [_ _ row] (swap! rows conj row))]
      (aloop/journal-step! {} b 1 {:parsed {:name "verify" :args {}}
                                  :result {:category :success :result "ok"}
                                  :tool "verify" :said "x" :response response})
      (aloop/no-call-step {} b 2 {:parsed nil :signals {} :said "x" :response response}))
    (is (= 2 (count @rows)))
    (doseq [row @rows]
      (is (= 4321 (:elapsed-ms row)) (str (:tool-name row)))
      (is (= {:requested "m" :reported "m'" :was nil} (:model-change row))
          (str (:tool-name row))))))

;; --- a loop is not retried at a doubled budget (karamazov-o4wm.5) ------------

(def ^:private loop-text
  (apply str (repeat 12 "I will now inspect the file to understand the failing test and then fix it. ")))

(deftest a-truncated-periodic-reply-is-not-retried-at-a-doubled-budget
  (let [calls (atom [])]
    (with-redefs [samizdat.llm.client/chat
                  (fn [_ _ _ opts]
                    (swap! calls conj (:max-tokens opts))
                    {:content loop-text :finish-reason "length"})]
      (let [r ((infer/complete-fn {:llm-adapter ::a :llm-config {:max-tokens 100}}) base-tape)]
        (is (:ok r))
        (is (= [100] @calls) "one call: a loop twice as long is not more room")))))

(deftest a-truncated-reply-that-is-not-repeating-still-gets-its-doubled-retry
  (let [calls (atom [])]
    (with-redefs [samizdat.llm.client/chat
                  (fn [_ _ _ opts]
                    (swap! calls conj (:max-tokens opts))
                    {:content "Let me think about this carefully. The parser expects"
                     :finish-reason "length"})]
      ((infer/complete-fn {:llm-adapter ::a :llm-config {:max-tokens 100}}) base-tape)
      (is (= [100 200] @calls)))))

(deftest complete-fn-renders-the-request-once
  (infer/clear-render-cache!)
  (let [calls (atom 0)
        real samizdat.agent.hydration/compact]
    (with-redefs [samizdat.agent.hydration/compact
                  (fn [& args]
                    (swap! calls inc)
                    (apply real args))
                  samizdat.llm.client/chat
                  (fn [_ _ _ _] {:content (fenced "verify" "{\"claim\": \"c\"}")})]
      ((infer/complete-fn {:llm-adapter ::a :llm-config {}}) base-tape))
    (is (= 1 @calls) "fingerprint and provider request share one render")
    (infer/clear-render-cache!)))

(deftest delta-offsets-stay-monotonic-across-a-retry
  (let [published (atom [])
        calls (atom 0)]
    (with-redefs [events/publish! (fn [event] (swap! published conj event))
                  samizdat.llm.client/chat
                  (fn [_ _ _ opts]
                    (let [n (swap! calls inc)]
                      ((:on-delta opts) {:text (str "part" n)})
                      (if (= 1 n)
                        {:content "no call" :finish-reason "length"}
                        {:content (fenced "verify" "{\"claim\": \"c\"}")
                         :finish-reason "stop"})))]
      ((infer/complete-fn {:llm-adapter ::a :llm-config {:max-tokens 100}
                           :run-id "r"}) base-tape))
    (let [offsets (mapv :text-at @published)]
      (is (= offsets (distinct offsets)) "one publisher, not one offset stream per attempt")
      (is (= offsets (sort offsets))))))

(deftest a-periodic-no-call-is-told-it-is-looping-and-clamped
  (with-redefs [journal/record-turn! (fn [& _] nil)]
    (let [b (state/new-branch {:id "B1" :problem "p"})
          b' (aloop/no-call-step {} b 3
                                 {:parsed nil
                                  :signals {:periodic true :periodic-repeats 7
                                            :truncated true}
                                  :said loop-text
                                  :response {:content loop-text :finish-reason "length"}})
          m (:content (peek (:messages b')))]
      (is (str/includes? m "repeated the same passage 7 times"))
      (is (not (str/includes? m "Think less")) "not the generic truncation advice")
      (is (= "```tool-call\n" (:prefill b')) "the next request opens inside the fence")
      (is (= 1 (get-in (state/record-mechanics b {:periodic true}) [:mechanics :periodic]))
          "and the tally the parse step keeps counts it"))))


(deftest a-call-in-a-run-publishes-its-reply-as-it-is-written
  ;; The TUI shows the reply while the model writes it: every delta the
  ;; client hands on goes onto the bus, tagged with where it starts, so a
  ;; reader that missed one can tell rather than garble the rest.
  (let [sub (events/subscribe)
        threshold gates/threshold]
    (try
      (with-redefs [gates/threshold (fn [k] (if (= :delta-publish-ms k) 0 (threshold k)))
                    samizdat.llm.client/chat
                    (fn [_ _ _ {:keys [on-delta]}]
                      (on-delta {:reasoning "hm"})
                      (on-delta {:text "Hel"})
                      (on-delta {:text "lo"})
                      {:content "Hello" :finish-reason "stop"})]
        ((infer/complete-fn {:llm-adapter ::a :llm-config {} :run-id "R1"}) base-tape))
      (let [ds (filter #(= :delta (:kind %)) (events/collect sub))]
        (is (every? #(= ["R1" "B1"] [(:run-id %) (:branch-id %)]) ds))
        (is (= "Hello" (apply str (keep :text ds))))
        (is (= "hm" (apply str (keep :reasoning ds))))
        (is (= [0 3] (keep #(when (:text %) (:text-at %)) ds)) "each piece says where it goes"))
      (finally (events/unsubscribe! sub)))))

(deftest a-prefilled-call-publishes-its-prefill-first
  ;; A forced turn's prefill ("```tool-call\n{") is the start of the reply,
  ;; and the stream carries only what the model wrote after it. Published
  ;; without it, the TUI drew the rest of the call — bare JSON — as the
  ;; agent's prose until the turn row landed.
  (let [sub (events/subscribe)
        threshold gates/threshold]
    (try
      (with-redefs [gates/threshold (fn [k] (if (= :delta-publish-ms k) 0 (threshold k)))
                    samizdat.llm.client/chat
                    (fn [_ _ _ {:keys [on-delta]}]
                      (on-delta {:text "\"name\":\"ls\"}"})
                      (on-delta {:text "\n```"})
                      {:content "\"name\":\"ls\"}\n```" :finish-reason "stop"})]
        ((infer/complete-fn {:llm-adapter ::a :llm-config {:features #{:prefill}} :run-id "R1"})
         (assoc base-tape :prefill "```tool-call\n{")))
      (let [ds (filter #(= :delta (:kind %)) (events/collect sub))]
        (is (= "```tool-call\n{\"name\":\"ls\"}\n```" (apply str (keep :text ds))))
        (is (= 0 (:text-at (first ds))) "from the very start"))
      (finally (events/unsubscribe! sub)))))

(deftest a-call-outside-a-run-publishes-nothing
  (let [sub (events/subscribe)
        seen (atom :unset)]
    (try
      (with-redefs [samizdat.llm.client/chat
                    (fn [_ _ _ opts] (reset! seen (:on-delta opts)) {:content "x" :finish-reason "stop"})]
        ((infer/complete-fn {:llm-adapter ::a :llm-config {}}) base-tape))
      (is (nil? @seen) "no watcher asked for, so the call is not streamed")
      (is (empty? (filter #(= :delta (:kind %)) (events/collect sub))))
      (finally (events/unsubscribe! sub)))))

(deftest a-call-carries-the-branchs-tools-where-the-endpoint-takes-them
  ;; The specs come from the branch's own system prompt (llm.toolspec), and
  ;; only an endpoint with :native-tools is handed them (karamazov-jl1f).
  (let [seen (atom nil)
        tape {:id "B1" :messages [{:role "system" :content "Tools:\n```\nread_file({path})\n    Read a file.\n```"}
                                  {:role "user" :content "go"}]
              :turns []}]
    (with-redefs [samizdat.llm.client/chat
                  (fn [_ _ _ opts] (reset! seen opts)
                    {:content (fenced "read_file" "{\"path\": \"a\"}") :finish-reason "stop"})]
      ((infer/complete-fn {:llm-adapter ::a :llm-config {:features #{:native-tools}}} {:journal? false}) tape)
      (is (= ["read_file"] (mapv :name (:tools @seen))))
      ((infer/complete-fn {:llm-adapter ::a :llm-config {:features #{}}} {:journal? false}) tape)
      (is (nil? (:tools @seen)) "an endpoint not measured to take them is not sent them"))))

(def ^:private prepared-native-tape
  {:id "prepared-B1"
   :messages [{:role "system"
               :content (str "Tools:\n```\nread_file({path})\n    Read a file by its path.\n"
                             "alpha_tool({query, limit?})\n    Search the alpha index.\n```")}
              {:role "user" :content "Prepare the request."}]
   :turns []})

(deftest prepare-is-deterministic-and-complete-fn-consumes-its-request
  (infer/clear-render-cache!)
  (let [ctx {:llm-adapter ::a
             :llm-config {:provider :test :model "prepared-model"
                          :context-window 8192 :max-tokens 64
                          :features #{:native-tools}}}
        prepared (infer/prepare ctx prepared-native-tape {:attempt 1 :fit? false})
        repeated (infer/prepare ctx prepared-native-tape {:attempt 1 :fit? true})
        sent (atom nil)]
    (is (= prepared repeated)
        "fitting is an exact no-op for an already admissible request")
    (is (= ["alpha_tool" "read_file"] (mapv :name (:tools prepared)))
        "native specifications have deterministic name ordering")
    (is (= (:wire prepared) (:wire repeated)))
    (is (= (infer/request-digest (:wire prepared))
           (infer/request-digest (:wire repeated)))
        "request fingerprints remain deterministic across preparation")
    (is (> (:input-tokens (:accounting prepared))
           (cmp/estimate-tokens (:messages prepared-native-tape) 4))
        "structured input exceeds the old message-only estimate when tools are present")
    (with-redefs [samizdat.llm.client/chat
                  (fn [_ _ messages opts]
                    (reset! sent {:messages messages :opts opts})
                    {:content (fenced "alpha_tool" "{\"query\": \"x\"}")
                     :finish-reason "stop"})]
      ((infer/complete-fn ctx {:journal? false}) prepared-native-tape))
    (is (= (:rendered prepared) (:messages @sent)))
    (is (= (:tools prepared) (:tools (:opts @sent))))
    (is (= (:call-opts prepared) (:opts @sent))
        "the provider receives the preparation's options, not a reconstructed request")
    (infer/clear-render-cache!)))

(deftest truncation-retry-changes-reserve-without-rendering-again
  (infer/clear-render-cache!)
  (let [renders (atom 0)
        budgets (atom [])
        real-compact samizdat.agent.hydration/compact]
    (with-redefs [samizdat.agent.hydration/compact
                  (fn [& args]
                    (swap! renders inc)
                    (apply real-compact args))
                  samizdat.llm.client/chat
                  (fn [_ _ _ opts]
                    (swap! budgets conj (:max-tokens opts))
                    (if (= 1 (count @budgets))
                      {:content "still thinking" :finish-reason "length"}
                      {:content (fenced "alpha_tool" "{\"query\": \"x\"}")
                       :finish-reason "stop"}))]
      ((infer/complete-fn {:llm-adapter ::a
                           :llm-config {:max-tokens 100 :features #{:native-tools}}}
                          {:journal? false})
       prepared-native-tape))
    (is (= [100 200] @budgets))
    (is (= 1 @renders) "retry accounting reuses the initial prepared render")
    (infer/clear-render-cache!)))

(deftest budget-admission-reserves-the-structured-request-total
  (cells/load-cells!)
  (let [asks (atom [])
        branch {:id "budget-B1" :messages (:messages prepared-native-tape) :turns []}
        ctx {:conn ::conn :run-id "budget-r1" :token-budget 100000
             :llm-config {:provider :test :model "budget-model"
                          :context-window 8192 :max-tokens 64
                          :features #{:native-tools}}}
        expected (+ (:input-tokens
                     (:accounting
                      (infer/prepare ctx (infer/of-branch branch)
                                     {:attempt 1 :fit? true})))
                    64)]
    (with-redefs [budget/reserve! (fn [_ _ _ ask]
                                    (swap! asks conj ask)
                                    true)
                  journal/run-usage (fn [_ _] {:total-tokens 0})]
      ((:handler (cell/get-cell! :budget/admit)) ctx {:branch branch}))
    (is (= [expected] @asks)
        "native tools and other prepared components are reserved, not just message bodies")))

(deftest an-unfittable-request-is-refused-without-calling-the-provider
  (let [ctx {:llm-adapter ::a
             :llm-config {:context-window 1 :max-tokens 32}}
        prepared (infer/prepare ctx base-tape {:fit? true})
        called? (atom false)]
    (is (false? (:ok prepared)))
    (is (= :context-window-exceeded (:reason prepared)))
    (is (false? (get-in prepared [:accounting :admissible?])))
    (with-redefs [samizdat.llm.client/chat
                  (fn [& _]
                    (reset! called? true)
                    (throw (ex-info "provider must not be called" {})))]
      (let [call ((infer/complete-fn ctx {:journal? false}) base-tape)]
        (is (false? (:ok call)))
        (is (= :context-window-exceeded (:reason call)))
        (is (some? (:error call)) "a refusal carries a diagnostic through the cells")))
    (is (false? @called?))))

(deftest local-window-refusal-squeezes-once-through-the-real-cells
  (cells/load-cells!)
  (let [ctx {:run-id "local-window-refusal"
             :llm-adapter ::a
             :llm-config {:context-window 1 :max-tokens 32}}
        branch (state/new-branch {:id "local-window-refusal" :problem "p"})
        message-count (count (:messages branch))
        provider-calls (atom 0)]
    (with-redefs [samizdat.llm.client/chat
                  (fn [& _]
                    (swap! provider-calls inc)
                    (throw (ex-info "provider must not be called" {})))
                  journal/record-turn! (fn [& _] nil)]
      (let [inferred ((:handler (cell/get-cell! :llm/infer))
                      ctx {:branch branch :turn 1})
            call (:call inferred)
            handled ((:handler (cell/get-cell! :loop/provider-error))
                     ctx (assoc inferred :turn 1))
            after (:branch handled)]
        (is (false? (:ok call)))
        (is (= :context-window-exceeded (:reason call)))
        (is (some? (:error call)))
        (is (zero? @provider-calls))
        (is (= 1 (:context-squeeze after)))
        (is (= message-count (count (:messages after)))
            "a local refusal must not append a growing retry message")))))

(deftest final-fit-is-outgoing-only-and-preserves-the-output-reserve
  (infer/clear-render-cache!)
  (let [result {:role "user" :turn 42
                :content (samizdat.llm.message/frame-result
                          "read_file" (apply str (repeat 12000 "x")))}
        tape (assoc base-tape
                    :messages [{:role "system" :content "sys"}
                               {:role "user" :content "## Problem\n\ndo the thing"}
                               {:role "assistant" :content "read the file"}
                               result
                               {:role "assistant" :content "the file was read"}])
        unbounded {:llm-adapter ::a
                   :llm-config {:context-window 100000 :max-tokens 32}}
        initial (infer/prepare unbounded tape {:fit? false})
        window (- (get-in initial [:accounting :total-tokens]) 40)
        ctx (assoc-in unbounded [:llm-config :context-window] window)
        before tape
        sent (atom nil)]
    (with-redefs [samizdat.llm.client/chat
                  (fn [_ _ messages opts]
                    (reset! sent {:messages messages :opts opts})
                    {:content (fenced "verify" "{\"claim\": \"c\"}")
                     :finish-reason "stop"})]
      (let [response ((infer/complete-fn ctx {:journal? false}) tape)]
        (is (:ok response))))
    (is (= before tape) "fitting never changes the complete source tape")
    (is (= result (nth (:messages tape) 3))
        "durable branch history is not shortened")
    (is (= (mapv #(nth (:messages tape) %) [0 1 2 4])
           (mapv #(nth (:messages @sent) %) [0 1 2 4]))
        "system, problem, and assistant messages reach the provider unchanged")
    (is (< (count (get-in @sent [:messages 3 :content]))
           (count (:content result)))
        "only the outgoing tool-result projection is shortened")
    (is (str/includes? (get-in @sent [:messages 3 :content])
                       "fetch_turn({\"turn\": 42})"))
    (is (= 32 (get-in @sent [:opts :max-tokens]))
        "fitting never lowers max output")
    (is (= (:messages @sent)
           (:rendered (infer/prepare ctx tape {:fit? true})))
        "prepare exposes the same fitted projection that dispatch sends")
    (infer/clear-render-cache!)))

(deftest calibrated-fit-continues-with-the-residual-deficit
  (accounting/reset-calibration!)
  (infer/clear-render-cache!)
  (let [result (fn [turn n]
                 {:role "user" :turn turn
                  :content (samizdat.llm.message/frame-result
                            "read_file" (apply str (repeat n "x")))})
        tape (assoc base-tape
                    :messages [{:role "system" :content "sys"}
                               {:role "user" :content "## Problem\n\ncalibrated fit"}
                               {:role "assistant" :content "first call"}
                               (result 51 1600)
                               {:role "assistant" :content "first response"}
                               {:role "assistant" :content "second call"}
                               (result 52 12000)
                               {:role "assistant" :content "second response"}])
        unbounded {:llm-adapter ::a
                   :llm-config {:provider :test :model "calibrated-fit-model"
                                :context-window 100000 :max-tokens 32}}
        initial (infer/prepare unbounded tape {:fit? false})
        fit-policy (assoc (gates/threshold :request-fit)
                          :chars-per-token
                          (get (gates/threshold :request-accounting) :chars-per-token))
        first-fit (context-fit/fit (:rendered initial) 1 fit-policy)
        first-saving (:freed-tokens first-fit)
        _ (accounting/record-usage!
           (gates/threshold :request-accounting)
           (:accounting initial)
           {:prompt-tokens (* 0.5 (:input-tokens (:accounting initial)))})
        calibrated (infer/prepare unbounded tape {:fit? false})
        window (- (get-in calibrated [:accounting :total-tokens]) first-saving)
        ctx (assoc-in unbounded [:llm-config :context-window] window)
        prepared (infer/prepare ctx tape {:fit? true})]
    (is (pos? first-saving))
    (is (= 1 (:shortened-earlier first-fit)) "the fixture isolates the first result")
    (is (= :provider-usage (get-in calibrated [:accounting :source])))
    (is (true? (get-in prepared [:accounting :admissible?])))
    (is (< (count (get-in prepared [:rendered 3 :content]))
           (count (get-in tape [:messages 3 :content]))))
    (is (< (count (get-in prepared [:rendered 6 :content]))
           (count (get-in tape [:messages 6 :content])))
        "the residual calibrated deficit requires the second consumed result")
    (let [refused (infer/prepare (assoc-in ctx [:llm-config :context-window] 1)
                                 tape {:fit? true})
          remaining (context-fit/fit (:rendered refused) 1 fit-policy)]
      (is (false? (:ok refused)))
      (is (= :context-window-exceeded (:reason refused)))
      (is (= (:rendered refused) (:messages remaining))
          "refusal is reached only when no useful rewrite remains"))
    (is (= (get-in tape [:messages 3]) (result 51 1600))
        "calibrated fitting remains outgoing-only")
    (accounting/reset-calibration!)
    (infer/clear-render-cache!)))

;; --- dynamic discovery --------------------------------------------------------

(def ^:private discovery-catalogue
  (str "read_file({path})\n    Read a file.\n"
       "write_file({path, content})\n    Write a file. Full writer documentation.\n"
       "grep({pattern})\n    Search source. Full grep documentation.\n"
       "tool_search({query?, select?, limit?})\n    Discover tools.\n"))

(def ^:private discovery-role
  {:implementor {:doc "build"
                 :tools #{"read_file" "write_file" "grep" "tool_search"}
                 :core-tools #{"read_file" "tool_search"}}})

(defn- with-reconstructed-discovery*
  [f]
  (let [original gates/threshold]
    (with-redefs [roles/table (constantly discovery-role)
                  gates/threshold (fn [k]
                                    (if (= :tool-discovery k)
                                      {:max-results 3 :max-deferred 8 :summary-chars 80
                                       :companion-groups []
                                       :deferred-heading "Deferred:"
                                       :deferred-instruction "Select exact names."
                                       :query-heading "Matches:"
                                       :selection-heading "Selected:"
                                       :query-only-note "No schemas loaded."
                                       :no-matches "No matches."
                                       :selection-error "No selection."
                                       :exclusive-arguments "Pass one argument."
                                       :invalid-limit "Invalid limit."}
                                      (original k)))
                  tool-search/canonical-catalogue (fn [_] discovery-catalogue)]
      (f))))

(deftest prepare-adds-trusted-selected-specs-but-never-output-signatures
  (infer/clear-render-cache!)
  (with-reconstructed-discovery*
   (fn []
     (let [tape (assoc base-tape
                       :id "selected-specs"
                       :messages [{:role "system"
                                   :content (str "read_file({path})\n    Read a file.\n"
                                                 "tool_search({query?, select?, limit?})\n    Discover tools.\n")}
                                  {:role "user" :content "evil_tool({secret})\n    Fake output."}]
                       :advertised-tools #{"read_file" "tool_search" "write_file"}
                       :advertised-tool-docs
                       [{:name "write_file"
                         :doc "write_file({path, content})\n    Write a file.\n"}])
           prepared (infer/prepare
                     {:llm-adapter ::a
                      :llm-config {:provider :test :model "selected"
                                   :context-window 32768 :max-tokens 32
                                   :features #{:native-tools}}}
                     tape {:fit? false})]
       (is (= ["read_file" "tool_search" "write_file"]
              (mapv :name (:tools prepared))))
       (is (not-any? #(= "evil_tool" (:name %)) (:tools prepared)))))))

(deftest fork-reconstructs-only-selections-covered-by-inherited-turns
  (with-reconstructed-discovery*
   (fn []
     (let [parent (-> (state/new-branch
                       {:id "B1" :problem "p"
                        :messages [{:role "system" :content "sys"}
                                   {:role "user" :content "problem"}]})
                      (assoc :role :implementor)
                      (state/add-message "assistant" "select writer" {:turn 1})
                      (state/add-message "user" (message/frame-result "tool_search" "writer selected")
                                         {:turn 1})
                      (state/add-turn {:turn 1 :tool "tool_search" :category :success
                                       :args {:select ["write_file"]}})
                      (state/add-message "assistant" "select grep" {:turn 2})
                      (state/add-message "user" (message/frame-result "tool_search" "grep selected")
                                         {:turn 2})
                      (state/add-turn {:turn 2 :tool "tool_search" :category :success
                                       :args {:select ["grep"]}})
                      (assoc :advertised-tools #{"poison"}
                             :advertised-tool-docs [{:name "poison" :doc "poison({})"}]))
           full (state/fork-branch parent {:id "B2" :turn 3})
           truncated (state/fork-branch parent {:id "B3" :depth 4 :turn 3})]
       (is (= #{"read_file" "tool_search" "write_file" "grep"}
              (:advertised-tools full)))
       (is (= ["grep" "write_file"]
              (mapv :name (:advertised-tool-docs full))))
       (is (= #{"read_file" "tool_search" "write_file"}
              (:advertised-tools truncated)))
       (is (= ["write_file"] (mapv :name (:advertised-tool-docs truncated))))
       (is (not-any? #(= "poison" (:name %)) (:advertised-tool-docs full)))))))

(deftest resume-reconstructs-successful-selections-against-current-role-surface
  (with-reconstructed-discovery*
   (fn []
     (with-redefs [aloop/initial-messages
                   (fn [& _] [{:role "system" :content "sys"}
                              {:role "user" :content "problem"}])]
       (let [rows [{:turn 1 :tool_name "tool_search"
                    :args "{\"select\":[\"write_file\",\"shell\"]}"
                    :category "success" :auto_repaired 0}
                   {:turn 2 :tool_name "tool_search"
                    :args "{\"select\":[\"grep\"]}"
                    :category "failure" :auto_repaired 0}]
             branch (#'resume/rebuild-branch
                     {:problem "p"}
                     {:id "B1" :role "implementor" :status "active"
                      :created_at_turn 0}
                     {"B1" rows} {} {} 10 (gates/storm-policy) nil)]
         (is (= #{"read_file" "tool_search" "write_file"}
                (:advertised-tools branch))
             "shell was not on the current role surface; failed grep was never selected")
         (is (= ["write_file"] (mapv :name (:advertised-tool-docs branch)))))))))

(deftest fork-replays-only-complete-selection-exchanges
  (with-reconstructed-discovery*
   (fn []
     (let [parent (-> (state/new-branch
                       {:id "B1" :problem "p"
                        :messages [{:role "system" :content "sys"}
                                   {:role "user" :content "problem"}]})
                      (assoc :role :implementor)
                      (state/add-message "user" "[harness] The explore prologue is over" {:turn 1})
                      (state/add-message "assistant" "select writer" {:turn 1})
                      (state/add-message "user" (message/frame-result "tool_search" "writer selected")
                                         {:turn 1})
                      (state/add-turn {:turn 1 :tool "tool_search" :category :success
                                       :args {:select ["write_file"]}})
                      (assoc :advertised-tools #{"read_file" "tool_search" "write_file"}
                             :advertised-tool-docs
                             [{:name "write_file" :doc "write_file({path, content})"}]))
           before-call (state/fork-branch parent {:id "before" :depth 3 :turn 2})
           between-call-and-result (state/fork-branch parent {:id "between" :depth 4 :turn 2})
           after-result (state/fork-branch parent {:id "after" :depth 5 :turn 2})]
       (doseq [child [before-call between-call-and-result]]
         (is (= #{"read_file" "tool_search"} (:advertised-tools child)))
         (is (empty? (:advertised-tool-docs child))))
       (is (= #{"read_file" "tool_search" "write_file"}
              (:advertised-tools after-result)))
       (is (= ["write_file"] (mapv :name (:advertised-tool-docs after-result))))))))

(defn- rebuild-discovery-branch
  [rows]
  (with-redefs [aloop/initial-messages
                (fn [& _] [{:role "system" :content "sys"}
                           {:role "user" :content "problem"}])]
    (#'resume/rebuild-branch
     {:problem "p"}
     {:id "B1" :role "implementor" :status "active" :created_at_turn 0}
     {"B1" rows} {} {} 10 (gates/storm-policy) nil)))

(deftest resume-then-full-fork-keeps-replayed-selections
  (with-reconstructed-discovery*
   (fn []
     (let [resumed (rebuild-discovery-branch
                    [{:turn 1 :tool_name "tool_search"
                      :assistant_text "select writer" :result "canonical writer docs"
                      :args "{\"select\":[\"write_file\",\"shell\"]}"
                      :category "success" :auto_repaired 0}
                     {:turn 2 :tool_name "tool_search"
                      :assistant_text "select grep" :result "canonical grep docs"
                      :args "{\"select\":[\"grep\"]}"
                      :category "success" :auto_repaired 0}])
           child (state/fork-branch resumed {:id "full-child" :turn 3})]
       (is (= #{"read_file" "tool_search" "write_file" "grep"}
              (:advertised-tools child)))
       (is (= ["grep" "write_file"] (mapv :name (:advertised-tool-docs child))))
       (is (not (contains? (:advertised-tools child) "shell"))
           "fork reapplies the current role surface to replayed selections")))))

(deftest resume-then-new-turn-then-fork-keeps-old-and-new-selections
  (with-reconstructed-discovery*
   (fn []
     (let [resumed (rebuild-discovery-branch
                    [{:turn 1 :tool_name "tool_search"
                      :assistant_text "select writer" :result "canonical writer docs"
                      :args "{\"select\":[\"write_file\",\"shell\"]}"
                      :category "success" :auto_repaired 0}])
           continued (-> resumed
                         (state/add-message "assistant" "select grep" {:turn 2})
                         (state/add-message "user" (message/frame-result "tool_search" "canonical grep docs")
                                            {:turn 2})
                         (state/add-turn {:turn 2 :tool "tool_search" :category :success
                                          :args {:select ["grep"]}}))
           child (state/fork-branch continued {:id "continued-child" :turn 3})]
       (is (= #{"read_file" "tool_search" "write_file" "grep"}
              (:advertised-tools child)))
       (is (= ["grep" "write_file"] (mapv :name (:advertised-tool-docs child))))
       (is (not (contains? (:advertised-tools child) "shell")))))))
