;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.oversight-test
  "The supervisor as a PARALLEL STREAM.

  The mechanism under test is deliberately ignorant of supervision: it runs
  some pass function on a cadence, against a budget, in a thread that cannot
  hurt the run it watches. What that pass DOES is a cell, because the harness's
  own policy about when to think and what to think about has to be something
  the agent can rewrite at runtime."
  (:require ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ebb.core :as ebb]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.cells :as cells]
            [samizdat.events :as events]
            [samizdat.prompt :as prompt]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.session :as session]
            [samizdat.store.db :as db]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as userspace]
            [samizdat.agent.oversight :as ov]))

;; --- when a pass is due -----------------------------------------------------

(deftest a-pass-is-due-on-a-turn-boundary-and-no-sooner-than-the-spacing
  ;; RFC-012 F2. Phase 2 EVALUATES A TURN, so it runs when a turn has ended —
  ;; not on a wall clock that fired every two minutes whether or not the
  ;; implementer had done anything, which is what it used to do. The clock is
  ;; now a spacing: a beam of five branches must not buy a pass per turn.
  (testing "the FIRST pass is due immediately — a supervisor that waits out a
            full cadence before its first look is blind through exactly the
            opening stretch where a run picks its approach"
    (is (ov/due? {:last-at nil :passes 0} {:now 0 :every-ms 100})))
  (testing "time passing earns nothing on its own: with no turn ended since
            the last pass there is nothing to evaluate"
    (is (not (ov/due? {:last-at 100 :passes 0} {:now 1000 :every-ms 100})))
    (is (not (ov/due? {:last-at 100 :passes 0} {:now 1000 :every-ms 100 :boundary? false}))))
  (testing "a boundary inside the spacing waits"
    (is (not (ov/due? {:last-at 100 :passes 0} {:now 150 :every-ms 100 :boundary? true}))))
  (testing "a boundary after the spacing is due"
    (is (ov/due? {:last-at 100 :passes 0} {:now 200 :every-ms 100 :boundary? true}))))

(deftest the-reasoning-pass-fires-when-a-turn-ends-not-when-the-clock-ticks
  ;; The same claim, driven through the thread: the stream reads the bus, and
  ;; a :turn journal event of ITS run is the boundary.
  (let [ch (events/subscribe)
        passes (atom 0)
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 0 :budget 100
                         :run-id "R-f2" :event-ch ch}
                        (fn [_] (swap! passes inc) {:carry nil :spent? true}))]
    (try
      (Thread/sleep 80)
      (is (= 1 @passes) "the first look is immediate; after that the clock alone buys nothing")
      (events/publish! {:kind :turn :run-id "R-f2" :branch-id "B1" :turn 1})
      (Thread/sleep 80)
      (is (= 2 @passes) "a turn of this run ending is what evaluation runs on")
      (events/publish! {:kind :turn :run-id "someone-else" :branch-id "B1" :turn 1})
      (events/publish! {:kind :step :run-id "R-f2" :branch-id "B1" :turn 2 :node :infer})
      (Thread/sleep 80)
      (is (= 2 @passes) "another run's turn is not this run's boundary, and a step mid-turn is not a boundary")
      (finally (stop) (events/unsubscribe! ch)))))

(deftest a-pass-that-waits-on-io-finishes
  ;; Every pass makes a model call, and the call parks on its HTTP read. The
  ;; stream used to run its pass inside an ebb/reduce over an `ap` flow of
  ;; bus batches; a park there never came back — the reducer was re-entered
  ;; on the next tick and the parked call was never resumed, or, under a
  ;; finally, was refused as a fork. Run 74ddebb8's supervisor answered
  ;; nothing in 1002 turns (karamazov-ah7d).
  (let [done (atom [])
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 0 :budget 100 :run-id "R-io"}
                        (fn [_]
                          (swap! done conj (ebb/? (ebb/via ebb/blk :answered)))
                          {:carry nil :spent? true}))]
    (try
      (loop [n 0] (when (and (empty? @done) (< n 300)) (Thread/sleep 10) (recur (inc n))))
      (is (= :answered (first @done)) "the pass's call came back")
      (finally (stop)))))

(deftest passes-never-overlap
  ;; karamazov-qtj5. Run 9ead0638's SUP branch interleaved two and three
  ;; passes, each cold start recorded as an abandoned pass with no notes. A
  ;; GLM pass deliberates for minutes, far longer than the tick; a pass that
  ;; parks across many ticks must still be the only one running.
  (let [in-flight (atom 0) peak (atom 0) passes (atom 0)
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 0 :budget 100 :run-id "R-overlap"}
                        (fn [_]
                          (swap! peak max (swap! in-flight inc))
                          (ebb/? (ebb/sleep 80))
                          (swap! in-flight dec)
                          (swap! passes inc)
                          {:carry nil :spent? true}))]
    (try
      (loop [n 0] (when (and (< @passes 3) (< n 300)) (Thread/sleep 10) (recur (inc n))))
      (is (<= 3 @passes) "passes keep coming")
      (is (= 1 @peak) "one at a time")
      (finally (stop)))))

(deftest the-spacing-is-between-passes-not-between-their-starts
  ;; :last-at was stamped when a pass STARTED, so a pass longer than
  ;; :every-ms — a GLM deliberation of 3-4 minutes against 2 — was due again
  ;; the moment it ended, and the passes ran back to back through the budget.
  (let [spans (atom [])
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 150 :budget 100 :run-id "R-gap"}
                        (fn [_]
                          (let [t0 (System/currentTimeMillis)]
                            (ebb/? (ebb/sleep 200))
                            (swap! spans conj [t0 (System/currentTimeMillis)])
                            {:carry nil :spent? true})))]
    (try
      (loop [n 0] (when (and (< (count @spans) 2) (< n 300)) (Thread/sleep 10) (recur (inc n))))
      (let [[[_ end1] [start2 _]] @spans]
        (is (some? start2) "a second pass ran")
        (is (>= (- start2 end1) 140) (str "the gap was " (- start2 end1) " ms against a spacing of 150")))
      (finally (stop)))))

(deftest a-branch-whose-provider-keeps-failing-stops
  ;; The route used to see only the turn cap, so a pass whose every call
  ;; failed ran to it: 2810 supervisor turns against a server that was down
  ;; in run 3020cbca, 1002 in 74ddebb8 (karamazov-e8iw).
  (cells/load-cells!)
  (let [route (:handler (cell/get-cell! :loop/route))
        limit (gates/threshold :provider-error-limit)
        b (assoc (samizdat.agent.state/new-branch {:id "SUP" :problem "p"})
                 :consecutive-provider-errors limit)]
    (is (pos-int? limit) "the limit is policy, in gates.edn")
    (is (= :abandoned (:verdict (route {:max-turns 1000} {:branch b :turn 7}))))
    (is (= :continue (:verdict (route {:max-turns 1000}
                                      {:branch (update b :consecutive-provider-errors dec)
                                       :turn 7})))
        "under the limit it keeps trying")))

(deftest the-stream-is-bounded
  ;; Every pass is a model call. A supervisor that reasons on every tick of a
  ;; 300-turn run costs more than the run it is supervising.
  (testing "under budget, passes continue"
    (is (ov/due? {:last-at 0 :passes 3} {:now 999 :every-ms 1 :budget 5 :boundary? true})))
  (testing "at budget, nothing is due again — including on a boundary, or the
            bound would be advisory"
    (is (not (ov/due? {:last-at 0 :passes 5}
                      {:now 999 :every-ms 1 :budget 5 :boundary? true})))))

;; --- the stream cannot hurt the run ----------------------------------------

(deftest a-throwing-pass-neither-stops-the-stream-nor-escapes-it
  ;; The whole point of an observer is that its failure costs the run nothing.
  ;; watch.clj learned this already; a reasoning stream fails in more ways.
  (let [calls (atom 0)
        pass (fn [_] (swap! calls inc) (throw (ex-info "boom" {})))
        st (atom {:passes 0})]
    (is (nil? (ov/pass! {} st pass)))
    (is (nil? (ov/pass! {} st pass)))
    (is (= 2 @calls) "it kept going after the first throw")
    (is (= 2 (:passes @st)) "a throwing pass still spends its budget — an
                             observer that fails for free retries forever")))

(deftest the-stream-carries-one-context-across-passes
  ;; ITS OWN MEMORY STREAM. run-role mints a fresh branch per call, so the
  ;; supervisor in feature.edn re-reads the run cold every revision and cannot
  ;; refer to what it concluded last time. A stream that cannot remember its
  ;; own last conclusion cannot tell a change it made from a change it only
  ;; considered.
  (let [seen (atom [])
        pass (fn [{:keys [carry]}] (swap! seen conj carry) (inc (or carry 0)))
        st (atom {:passes 0})]
    (ov/pass! {} st pass)
    (ov/pass! {} st pass)
    (ov/pass! {} st pass)
    (is (= [nil 1 2] @seen)
        "each pass sees what the previous one returned")))

(deftest stopping-is-idempotent-and-ends-the-thread
  (let [stop (ov/start! {:enabled? false} (fn [_] nil))]
    (is (fn? stop) "a disabled stream still returns a stop function, so the
                    caller's teardown never has to check")
    (is (nil? (stop)))
    (is (nil? (stop)) "called twice from a crash path and a finally")))

;; --- the behaviour layer ----------------------------------------------------
;; The mechanism above is domain-blind. These cover the cells, which decide
;; what a pass looks at and whether it is worth a model call at all.

(defn- worth-a-look? [& args]
  (cells/load-cells!)
  (apply @(cells/cell-var 'cells.oversight 'worth-a-look?) args))

(deftest a-healthy-run-costs-nothing
  ;; The cheap path has to be the DEFAULT, or the stream costs more than the
  ;; run it watches. A run that is shipping has nothing to tune, and saying so
  ;; would spend a model call to say nothing.
  (let [floors {:unmet-floor 2 :idle-floor 25}]
    (is (not (worth-a-look? {:unmet-gates 0 :idle-turns 3 :errors nil} floors)))
    (is (not (worth-a-look? {:unmet-gates 1 :idle-turns 24 :errors nil} floors))
        "just under both floors is still quiet — one unmet gate is noise")))

(deftest the-three-signals-that-buy-a-model-call
  (let [floors {:unmet-floor 2 :idle-floor 25}]
    (testing "steering that is being ignored — the harness's own words failing,
              which is the supervisor's actual subject"
      (is (worth-a-look? {:unmet-gates 2 :idle-turns 0 :errors nil} floors)))
    (testing "a run producing nothing"
      (is (worth-a-look? {:unmet-gates 0 :idle-turns 25 :errors nil} floors)))
    (testing "a stage crashed — a harness bug the loop survived, which recurs
              on the next run if nobody looks"
      (is (worth-a-look? {:unmet-gates 0 :idle-turns 0 :errors [{:x 1}]} floors)))
    (testing "the outer loop reached its soft cap — a decision the loop hands
              to the supervisor, and now there is exactly one to hand it to"
      (is (worth-a-look? {:unmet-gates 0 :idle-turns 0 :errors nil :at-cap? true} floors)))))

(deftest a-green-round-the-reviewer-sent-back-is-worth-a-look
  ;; karamazov-ylte.1, run dbe64eea-successor. The owner shipped: 89 turns, a
  ;; real diff, ship-verify green. The board critic then failed the round
  ;; [high] for a requirement the answer never mentioned, and the round went
  ;; to revise. THE SUPERVISOR NEVER SAW IT — 18 of its 30 passes were quiet,
  ;; and this moment tripped none of the six triggers: gates were met, turns
  ;; were not idle, nothing crashed, the soft cap was far off, something DID
  ;; ship so nothing-shipped? was false, and no mutation was refused.
  ;;
  ;; The docstring's premise is that "a healthy run that is shipping gets no
  ;; supervision, which is correct" — and it is, as long as shipping means
  ;; shipping something whole. A confidently wrong ship looks identical to a
  ;; healthy one from every counter the stream reads.
  ;;
  ;; The signal is narrow on purpose: the work passed its OWN tests and a
  ;; reviewer refused it anyway. That is the first trigger's own subject — the
  ;; harness's words failing to convey something — rather than an ordinary
  ;; revise, where the tests were red and the loop is simply still working.
  (let [floors {:unmet-floor 2 :idle-floor 25}
        healthy {:unmet-gates 0 :idle-turns 0 :errors nil}]
    (testing "green work sent back buys a pass"
      (is (worth-a-look?
           (assoc healthy :sent-back-green? true) floors)))
    (testing "an ordinary revise on red tests does NOT — the loop is working"
      (is (not (worth-a-look?
                (assoc healthy :sent-back-green? false) floors))))
    (testing "and the cheap default is untouched"
      (is (not (worth-a-look? healthy floors))))))

(deftest sent-back-green-reads-the-round-the-loop-already-writes
  (cells/load-cells!)
  (let [f @(cells/cell-var 'cells.oversight 'sent-back-green?)]
    (testing "the two facts are both on the :route note the feature loop
              journals every round — nothing new has to be recorded"
      (is (true? (f {:decision "revise" :tests-passed true})))
      (is (true? (f {:decision :revise :tests-passed true}))
          "the note round-trips through JSON, so the decision comes back a
           string; reading only the keyword would make this silently never
           fire, which is the shape of karamazov-u5uy"))
    (testing "and it is neither of the things it must not be"
      (is (false? (f {:decision "revise" :tests-passed false}))
          "red tests sent back is the loop working, not a blind spot")
      (is (false? (f {:decision "ship" :tests-passed true})))
      (is (false? (f nil))))))

;; --- what the stage used to see, the stream now sees (RFC-012 F1/F4) --------

(defn- reasoning-over
  "Run :oversight/gather then :oversight/reason on `rid` with the model turn
  stubbed, returning {:gather :prob} — what gather decided and the brief the
  supervisor would have read."
  [conn rid]
  (cells/load-cells!)
  (let [ctx {:conn conn :run-id rid :config {}}
        g ((:handler (cell/get-cell! :oversight/gather)) ctx {})
        prob (atom nil)]
    (with-redefs [myc/run-compiled (fn [_ _ data]
                                     (reset! prob (get-in data [:branch :problem]))
                                     {:branch (assoc (:branch data) :final-answer "ok")})]
      ((:handler (cell/get-cell! :oversight/reason)) ctx g))
    {:gather g :prob @prob}))

(deftest a-crashed-stage-reaches-the-one-supervisor
  ;; The feature loop's stages fail soft and note :stage-error; the stage
  ;; that used to show those to a supervisor of its own is gone. The stream's
  ;; gather reads them off the journal, they buy a model call, and the brief
  ;; names the crash in its own words.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :stage-error
                   {:data {:stage "critique" :node "judge" :error "boom in the judge"}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (true? (:oversight/worth-a-look? gather)) "a crash is worth a look")
      (is (= 1 (count (:oversight/crashes gather))))
      (is (str/includes? (str prob) "STAGE CRASHED"))
      (is (str/includes? (str prob) "boom in the judge")))))

(deftest a-run-that-died-reaches-the-supervisor-with-its-stack
  ;; A failed run is resumable, and the resumed run's stream is the one role
  ;; that can edit a cell. It gets the run's :run-error beside the stages'
  ;; crashes, with the frames, so it can tell whose fault it is and where —
  ;; a cell frame makes the layer userspace, which the digest says outright.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :run-error
                   {:data {:error "boom in spawn" :node ":spawn"
                           :trace ["samizdat.agent.beam/spawn-children! (beam.clj:272)"
                                   "cells.beam/fn--612 (beam.clj:600)"]}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (true? (:oversight/worth-a-look? gather)))
      (is (= 1 (count (:oversight/crashes gather))))
      (is (str/includes? (str prob) "boom in spawn"))
      (is (str/includes? (str prob) "samizdat.agent.beam/spawn-children! (beam.clj:272)")
          "the frames, not just the message")
      (is (str/includes? (str prob) "layer: userspace")))))

(deftest the-loops-soft-cap-is-the-supervisors-to-decide
  ;; The feature loop's route note carries the revision and the soft cap. At
  ;; the cap the loop keeps solving on its own ladder, and the supervisor is
  ;; the one who may switch, re-budget or stop it — so reaching it is worth a
  ;; model call, and the brief says so with the round's own facts.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :route
                   {:data {:decision "revise" :revision 3 :soft-cap 3 :strategy "board"
                           :hollow false :tests-passed false}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (true? (:oversight/worth-a-look? gather)))
      (is (= 3 (get-in gather [:oversight/round :revision])))
      (is (str/includes? (str prob) "REVISION CAP REACHED"))
      (is (str/includes? (str prob) "revision 3")))))

(defn- gather-now [conn rid]
  (cells/load-cells!)
  ((:handler (cell/get-cell! :oversight/gather)) {:conn conn :run-id rid :config {}} {}))

(defn- spent-pass! [conn rid g]
  ;; What the reason cell leaves behind a pass that spent a model call: the
  ;; signals it was woken on, so the next gather can tell new from seen.
  (journal/note! conn rid :oversight
                 {:data {:verdict "done" :idle (:oversight/idle g) :unmet (:oversight/unmet g)
                         :signals (:oversight/signals g)}}))

(deftest a-pass-is-not-spent-again-on-evidence-it-already-read
  ;; karamazov-na2k.16. Run 390dcd17 spent all 12 passes in 24 minutes: after
  ;; one real pass, every gather still saw unmet 5 over the floor of 2 and
  ;; woke the supervisor to say 'already done', and the run then went two
  ;; hours with no supervision at all. The trigger has to be what CHANGED
  ;; since the last pass that spent a call, not the level.
  (testing "a crash wakes it once"
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (journal/note! conn rid :run-error {:data {:error "boom" :node ":spawn"}})
      (let [g1 (gather-now conn rid)]
        (is (true? (:oversight/worth-a-look? g1)))
        (spent-pass! conn rid g1)
        (is (false? (:oversight/worth-a-look? (gather-now conn rid)))
            "the same crash, already read, is not worth another call")
        (journal/note! conn rid :stage-error {:data {:error "boom again" :node ":board"}})
        (is (true? (:oversight/worth-a-look? (gather-now conn rid)))
            "a new crash is"))))
  (testing "the soft cap wakes it once per round"
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (journal/note! conn rid :route {:data {:decision "revise" :revision 3 :soft-cap 3}})
      (let [g1 (gather-now conn rid)]
        (is (true? (:oversight/worth-a-look? g1)))
        (spent-pass! conn rid g1)
        (is (false? (:oversight/worth-a-look? (gather-now conn rid))))
        (journal/note! conn rid :route {:data {:decision "revise" :revision 4 :soft-cap 3}})
        (is (true? (:oversight/worth-a-look? (gather-now conn rid)))
            "the next round at the cap is a new decision"))))
  (testing "a refusal wakes it once"
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (journal/note! conn rid :mutation-rolled-back {:data {:reason "no" :target "manifest x"}})
      (let [g1 (gather-now conn rid)]
        (is (true? (:oversight/worth-a-look? g1)))
        (spent-pass! conn rid g1)
        (is (false? (:oversight/worth-a-look? (gather-now conn rid))))))))

(deftest the-apply-cell-records-what-the-pass-was-woken-on
  ;; The same claim through the real cells: gather, a stubbed reason, apply's
  ;; note, and the next gather sees the evidence as read.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (journal/note! conn rid :run-error {:data {:error "boom" :node ":spawn"}})
    (let [g (gather-now conn rid)
          r (with-redefs [myc/run-compiled (fn [_ _ data]
                                             {:branch (assoc (:branch data) :final-answer "looked")})]
              ((:handler (cell/get-cell! :oversight/reason)) ctx g))]
      ((:handler (cell/get-cell! :oversight/apply)) ctx (assoc r :oversight/verdict :done))
      (is (= 1 (:errors (:signals (journal/last-note conn rid :oversight)))))
      (is (false? (:oversight/worth-a-look? (gather-now conn rid)))))))

(deftest a-worker-that-cannot-land-its-answer-wakes-the-supervisor
  ;; karamazov-i692: 12 quiet passes while done was refused three times on a
  ;; verified program. The first look came at worker turn 39.
  (let [floors {:unmet-floor 2 :idle-floor 25 :done-refused-floor 2}]
    (is (not (worth-a-look? {:done-refused 1} floors)) "one refusal is the gate working")
    (is (worth-a-look? {:done-refused 2} floors))
    (let [since @(cells/cell-var 'cells.oversight 'since-last-look)]
      (testing "counted from the last look"
        (is (= 2 (:done-refused (since {:done-refused 2} nil {:idle-floor 25}))))
        (is (= 1 (:done-refused (since {:done-refused 3} {:done-refused 2} {:idle-floor 25}))))))))

(deftest nearing-the-turn-cap-is-the-supervisors-call
  ;; How long a run may go is a decision, and `extend` is how it is made, but
  ;; nothing asked anyone before the cap ended the run.
  (let [floors {:unmet-floor 2 :idle-floor 25}
        since @(cells/cell-var 'cells.oversight 'since-last-look)]
    (is (worth-a-look? {:near-cap? true} floors))
    (testing "once per cap"
      (is (:near-cap? (since {:near-cap-at 300} nil {:idle-floor 25})))
      (is (not (:near-cap? (since {:near-cap-at 300} {:near-cap-at 300} {:idle-floor 25}))))
      (is (not (:near-cap? (since {:near-cap-at nil} nil {:idle-floor 25})))))
    (testing "an extended cap can be asked about again"
      (is (:near-cap? (since {:near-cap-at 400} {:near-cap-at 300} {:idle-floor 25}))))))

(deftest gather-reads-refused-dones-and-the-cap-from-the-record
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p" :max-turns 10})
        near (:near-cap-fraction (gates/threshold :oversight-limits))]
    (is (number? near))
    (cells/load-cells!)
    (runs/open-branch! conn rid {:branch-id "B1" :problem "p"})
    (doseq [t (range 1 (inc (long (* near 10))))]
      (journal/record-turn! conn rid {:branch-id "B1" :turn t :tool-name "read_file" :category :neutral}))
    (let [g ((:handler (cell/get-cell! :oversight/gather)) {:conn conn :run-id rid :config {}} {})]
      (is (= 10 (get-in g [:oversight/signals :near-cap-at])))
      (is (true? (:oversight/worth-a-look? g))))))

(deftest unmet-and-idle-count-from-the-last-look
  (let [since @(cells/cell-var 'cells.oversight 'since-last-look)
        floors {:idle-floor 25}]
    (testing "no earlier pass: the levels, as before"
      (is (= 5 (:unmet-gates (since {:unmet 5 :idle 3} nil floors)))))
    (testing "unmet counts only the firings since"
      (is (= 0 (:unmet-gates (since {:unmet 5} {:unmet 5} floors))))
      (is (= 3 (:unmet-gates (since {:unmet 8} {:unmet 5} floors)))))
    (testing "a stall already seen needs another floor's worth to wake it"
      (is (= 5 (:idle-turns (since {:idle 30} {:idle 25} floors))))
      (is (= 25 (:idle-turns (since {:idle 50} {:idle 25} floors)))))
    (testing "a stall not yet seen counts whole, and a write resets it"
      (is (= 30 (:idle-turns (since {:idle 30} {:idle 10} floors))))
      (is (= 26 (:idle-turns (since {:idle 26} {:idle 40} floors)))))))

(deftest a-round-under-the-cap-with-nothing-else-wrong-is-still-quiet
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :route
                   {:data {:decision "revise" :revision 1 :soft-cap 6 :strategy "board"}})
    (cells/load-cells!)
    (let [g ((:handler (cell/get-cell! :oversight/gather)) {:conn conn :run-id rid :config {}} {})]
      (is (false? (:oversight/worth-a-look? g))))))

(deftest the-reasoning-pass-reads-the-counters-since-its-last-look
  ;; RFC-012 protocol rule 4: measure what you changed. The stage used to stamp
  ;; a mark before each of its looks and show the delta; the stream inherits
  ;; that, so a pass can tell whether the change it made last pass moved
  ;; anything.
  (session/reset!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        mark (str "supervisor:" rid)]
    (dotimes [_ 5] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
    (is (nil? (session/since mark)) "no mark before the first pass")
    (let [{:keys [prob]} (reasoning-over conn rid)]
      (is (str/includes? (str prob) "This session so far")
          "the session block is in the brief")
      (dotimes [_ 2] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
      (is (= 2 (:turns (session/since mark)))
          "and the pass stamped its mark, so the next look measures from here"))
    (session/reset!)))

(deftest the-stalls-this-project-actually-had-would-all-have-woken-it
  ;; Regression against the record rather than against a number I chose. Every
  ;; run in this campaign that went quiet did so with a long idle stretch; if a
  ;; threshold change stops waking on these, it has gone wrong.
  (let [floors {:unmet-floor (gates/threshold :oversight-unmet-floor)
                :idle-floor (gates/threshold :oversight-idle-floor)}]
    (doseq [[run idle] [["bd56a286 T1" 316] ["c377260b revise" 148]
                        ["d304f539 T0" 87] ["986f33d8 T0 after its one write" 47]]]
      (is (worth-a-look? {:idle-turns idle} floors)
          (str run " stalled for " idle " turns and nothing looked at it")))))

(deftest clipping-a-note-survives-whitespace-collapse
  ;; The bug this replaces could only fire once a pass SUCCEEDED: the note was
  ;; indexed with the original length after the whitespace had been collapsed,
  ;; so any multi-line answer overran the shortened string. It would have
  ;; thrown on the first real supervisor conclusion and been swallowed whole by
  ;; the (then silent) stage guard.
  (let [clip (do (cells/load-cells!) @(cells/cell-var 'cells.oversight 'clip))]
    (is (= "a b c" (clip "a\n\n\nb\t\tc" 400))
        "collapsing must not leave the index past the end")
    (is (= "abc" (clip "abc" 400)) "shorter than the limit is returned whole")
    (is (= "ab" (clip "abcdef" 2)) "longer than the limit is cut to it")
    (is (= "" (clip nil 400)) "a pass with no answer clips to empty, not a throw")))

(deftest the-carry-continues-the-conversation-without-freezing-it
  ;; Run b2ffb2ad: S0 stuck at 23 turns across FIVE passes. The supervisor
  ;; called `done` on its first pass, and because the carry hands the whole
  ;; branch to the next pass, every later pass resumed an already-finished
  ;; branch and returned immediately. The supervisor spoke once and was
  ;; silent for the rest of the run — the exact failure the stream exists to
  ;; prevent, reintroduced by the mechanism meant to give it memory.
  ;;
  ;; The carry must preserve what it LEARNED and not that it had STOPPED.
  (let [resume (do (cells/load-cells!) @(cells/cell-var 'cells.oversight 'resume-branch))
        finished {:id "S0" :messages [{:role "user" :content "hello"}
                                      {:role "assistant" :content "a conclusion"}]
                  :final-answer "done for now" :verdict :done :advisory? true
                  :status :abandoned :inactive-reason "no call three times"
                  :consecutive-mechanics-failures 3 :consecutive-provider-errors 2}
        next-pass (resume finished "the brief for this pass")]
    (is (= "hello" (:content (first (:messages next-pass))))
        "the conversation so far is kept — that is the whole point of a stream")
    (is (nil? (:final-answer next-pass)) "not already answered")
    (is (nil? (:verdict next-pass)) "not already finished")
    (is (:advisory? next-pass) "still an advisory branch, not shippable work")
    ;; Run bcd61b39 (karamazov-3keg): the resumed branch was never handed the
    ;; new pass's brief, and with :status dissoc'd it was not ACTIVE, so every
    ;; pass after the first ran one turn and ended :abandoned.
    (is (= {:role "user" :content "the brief for this pass"} (last (:messages next-pass)))
        "this pass's brief is what the supervisor reads next")
    (is (= 3 (count (:messages next-pass))))
    (is (samizdat.agent.state/active? next-pass) "a resumed pass can take more than one turn")
    (is (nil? (:inactive-reason next-pass)))
    (is (nil? (:consecutive-mechanics-failures next-pass)) "not charged for the last pass's ending")
    (is (nil? (:consecutive-provider-errors next-pass)))
    (testing "and route lets it go on"
      (let [route (:handler (cell/get-cell! :loop/route))]
        (is (= :continue (:verdict (route {:max-turns 10} {:branch next-pass :turn 2}))))))))

(defn- event-count [conn run-id kind]
  (:n (first (db/fetch conn ["SELECT count(*) AS n FROM events
                               WHERE run_id = ? AND kind = ?" run-id kind]))))

(deftest every-oversight-stage-actually-runs
  ;; CELLS ARE LOAD-STRINGED, so nothing type- or arity-checks them until the
  ;; moment they run — inside a guard that catches and logs rather than throws.
  ;; A stage could therefore be broken for a whole run and the only trace was
  ;; one WARN nobody was reading. That is exactly what happened: renaming
  ;; `safely` to take a stage label missed one nested call site, so EVERY
  ;; reasoning pass of run 5a2605b1 died with "Wrong number of args (2)" and
  ;; the stream looked merely quiet.
  ;;
  ;; So: run the stages for real and assert on their OUTPUT, which a swallowed
  ;; exception cannot fake.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (testing "gather reaches its verdict rather than the guard's fallback"
      (let [out ((:handler (cell/get-cell! :oversight/gather)) ctx {})]
        ;; The fallback also sets worth-a-look? false, so assert on a key only
        ;; the real path produces.
        (is (contains? out :oversight/idle)
            "gather fell into its exception guard — check the log for
             'oversight :gather failed'")
        (is (contains? out :oversight/unmet))))
    (testing "quiet writes its heartbeat"
      (let [out ((:handler (cell/get-cell! :oversight/quiet)) ctx {:oversight/idle 1 :oversight/unmet 0})]
        (is (some? out))
        (is (pos? (event-count conn rid "oversight-quiet"))
            "no heartbeat row — a quiet stream is indistinguishable from a dead one")))
    (testing "apply records the pass"
      ((:handler (cell/get-cell! :oversight/apply)) ctx {:oversight/idle 9 :oversight/unmet 2
                                              :oversight/answer "a\n\nconclusion"})
      (is (pos? (event-count conn rid "oversight"))))))

(deftest the-reasoning-stage-runs-all-the-way-through
  ;; THE ONE THAT MATTERS. The arity bug lived in :oversight/reason, which the
  ;; stage test above cannot reach because reason takes a model turn. So stub
  ;; the turn and assert reason still carries its answer out — a swallowed
  ;; exception anywhere in its body (the catalog call, the digest, the prompt
  ;; render) leaves the answer nil, which is exactly how run 5a2605b1 looked
  ;; from outside: passes recorded, nothing learned.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (with-redefs [myc/run-compiled (fn [_ _ data]
                                     {:branch (assoc (:branch data)
                                                     :final-answer "nothing is wrong")})]
      (let [out ((:handler (cell/get-cell! :oversight/reason))
                 ctx {:oversight/idle 30 :oversight/unmet 2
                      :oversight/turns [] :oversight/firings []})]
        (is (= "nothing is wrong" (:oversight/answer out))
            "reason fell into its guard — check the log for 'oversight :reason failed'")
        (is (some? (:oversight/branch out))
            "the branch must come back out, or the stream has no memory")))))

(deftest a-blank-pass-says-why-it-was-blank
  ;; karamazov-r5a. Run b2ffb2ad journalled four passes, every one with
  ;; notes:null, and the record could not say whether the supervisor had
  ;; concluded nothing, run out of turns, or crashed. Three very different
  ;; things, one blank field. The verdict is what separates them.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}
        note (fn [] (json/read-str
                     (str (:data (last (db/fetch conn
                                                 ["SELECT data FROM events
                                                    WHERE run_id = ? AND kind = 'oversight'
                                                    ORDER BY id" rid]))))
                     :key-fn keyword))]
    (testing "a pass that concluded records both its verdict and its words"
      ((:handler (cell/get-cell! :oversight/apply))
       ctx {:oversight/idle 9 :oversight/unmet 2 :oversight/verdict :done
            :oversight/answer "the classpath is wrong"})
      (is (= "done" (:verdict (note))))
      (is (str/includes? (str (:notes (note))) "classpath")))
    (testing "a pass that ran out of turns says so instead of going blank"
      ((:handler (cell/get-cell! :oversight/apply))
       ctx {:oversight/idle 21 :oversight/unmet 5 :oversight/verdict :exhausted})
      (is (= "exhausted" (:verdict (note))))
      (is (nil? (:notes (note)))))))

(deftest a-reasoning-pass-that-throws-lands-in-the-record
  ;; The guard keeps the stream alive; it must not also keep the failure
  ;; secret. A silent guard is how an arity bug survived a whole run looking
  ;; like a merely quiet supervisor.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (with-redefs [myc/run-compiled (fn [& _] (throw (ex-info "provider exploded" {})))]
      (let [out ((:handler (cell/get-cell! :oversight/reason))
                 ctx {:oversight/idle 30 :oversight/unmet 2
                      :oversight/turns [] :oversight/firings []})]
        (is (= :error (:oversight/verdict out)))
        (is (str/includes? (str (:oversight/answer out)) "provider exploded"))))))

(deftest the-stream-and-the-stage-do-not-share-a-branch-id
  ;; Run 498450e1: branch S0 held 26 turn rows numbered up to 14. The stream
  ;; opened S0 and :feature/supervise opens S<revision>, which is S0 on the
  ;; first round — two supervisors with separate contexts writing one branch,
  ;; overwriting each other's turn numbers. A record that cannot say which
  ;; supervisor said what is a record of neither (karamazov-poe).
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (with-redefs [myc/run-compiled (fn [_ _ data]
                                     {:branch (assoc (:branch data) :final-answer "ok")})]
      (let [out ((:handler (cell/get-cell! :oversight/reason))
                 ctx {:oversight/idle 30 :oversight/unmet 2
                      :oversight/turns [] :oversight/firings []})]
        (is (= "SUP" (get-in out [:oversight/branch :id])))
        (is (not= "S0" (get-in out [:oversight/branch :id])))))))

;; --- what the budget actually counts (karamazov-808) ------------------------

(deftest a-quiet-pass-is-free-and-a-reasoning-one-is-not
  ;; Run a3566c73, live. The stream went silent exactly when the run needed
  ;; it: twelve quiet heartbeats through a healthy first half exhausted a
  ;; budget of twelve, and when the branch later livelocked with five unmet
  ;; gates — against a floor of two — nothing was left to spend.
  ;;
  ;; The budget bounds MODEL CALLS. worth-a-look? is cheap and pure precisely
  ;; so most passes make none, so counting those passes turned the mechanism
  ;; that makes the stream affordable into the one that silences it.
  (let [st (atom {:passes 0})]
    (dotimes [_ 5] (ov/pass! {} st (fn [_] {:carry :b :spent? false})))
    (is (= 0 (:passes @st)) "five quiet looks cost nothing")
    (is (= 5 (:looks @st)) "but they are still counted, so a watching stream
                            can be told apart from a dead one")
    (ov/pass! {} st (fn [_] {:carry :b :spent? true}))
    (is (= 1 (:passes @st)) "the pass that called a model spends")))

(deftest an-unreported-or-throwing-pass-is-assumed-expensive
  (testing "a bare return value spends, as it always did — guessing the other
            way is how a bound stops binding"
    (let [st (atom {:passes 0})]
      (ov/pass! {} st (fn [_] :some-carry))
      (is (= 1 (:passes @st)))
      (is (= :some-carry (:carry @st)))))
  (testing "and a throw still spends: an observer that fails for free retries
            a broken pass until the run ends"
    (let [st (atom {:passes 0})]
      (ov/pass! {} st (fn [_] (throw (ex-info "boom" {}))))
      (ov/pass! {} st (fn [_] (throw (ex-info "boom" {}))))
      (is (= 2 (:passes @st))))))

(deftest a-quiet-pass-does-not-erase-the-streams-memory
  ;; The other half of the same wiring bug. The beam's pass fn returned
  ;; (:oversight/branch out) as the carry, and a quiet pass produces no
  ;; branch — so every healthy look wiped the supervisor's accumulated context
  ;; and the next reasoning pass read the run cold. Carrying context across
  ;; passes is the entire reason this is a stream and not a node.
  (let [st (atom {:passes 0})
        remembered (fn [pass-ctx] {:carry (or nil (:carry pass-ctx)) :spent? false})]
    (ov/pass! {} st (fn [_] {:carry :the-branch :spent? true}))
    (is (= :the-branch (:carry @st)))
    (dotimes [_ 3] (ov/pass! {} st remembered))
    (is (= :the-branch (:carry @st))
        "three quiet passes later the supervisor still knows what it concluded")))

(deftest the-budget-gates-on-spent-passes-only
  (is (ov/due? {:last-at 0 :passes 0 :looks 99} {:now 999 :every-ms 1 :budget 3 :boundary? true})
      "ninety-nine free looks do not exhaust a budget of three")
  (is (not (ov/due? {:last-at 0 :passes 3 :looks 3} {:now 999 :every-ms 1 :budget 3 :boundary? true}))
      "three spent passes do"))

;; --- the implement round's outcomes reach the one supervisor (karamazov-u5uy)

(deftest the-brief-counts-what-the-implement-round-shipped
  ;; Every implement strategy journals its per-owner outcomes and gather reads
  ;; the last one. Before this the facts map the stream builds carried no
  ;; :results at all, so `Implementors: 0/0 shipped` was in EVERY brief and
  ;; :nobody-shipped could not fire on any strategy — not just decompose,
  ;; which is what karamazov-u5uy was filed as.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :implement-round
                   {:data {:strategy "board" :revision 0
                           :results [{:status :abandoned :subtask "alpha" :answer nil}
                                     {:status :abandoned :subtask "beta" :answer nil}]}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (= 2 (count (:oversight/results gather))))
      (testing "the status survives the journal's JSON round trip as a keyword,
                which is what the digest counts on"
        (is (= [:abandoned :abandoned] (mapv :status (:oversight/results gather)))))
      (is (str/includes? (str prob) "0/2 shipped"))
      (is (str/includes? (str prob) "NO IMPLEMENTOR SHIPPED")))))

(deftest a-round-that-shipped-something-does-not-read-as-nobody-shipped
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :implement-round
                   {:data {:strategy "team" :revision 1
                           :results [{:status :done :subtask "alpha" :answer "built alpha"}
                                     {:status :error :subtask "beta" :answer "worker failed"}]}})
    (let [{:keys [prob]} (reasoning-over conn rid)]
      (is (str/includes? (str prob) "1/2 shipped"))
      (is (not (str/includes? (str prob) "NO IMPLEMENTOR SHIPPED"))))))

(deftest a-round-where-nobody-shipped-is-worth-a-look
  ;; The fifth arming condition, and the most direct instance of the second
  ;; one the docstring already lists: the run is producing nothing. Measured
  ;; from the round's own outcomes rather than from turns since a write.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :implement-round
                   {:data {:strategy "decompose" :revision 0
                           :results [{:status :abandoned :subtask "T" :answer nil}]}})
    (let [{:keys [gather]} (reasoning-over conn rid)]
      (is (true? (:oversight/worth-a-look? gather)))))
  (testing "a round that shipped everything still buys nothing"
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (journal/note! conn rid :implement-round
                     {:data {:strategy "board" :revision 0
                             :results [{:status :done :subtask "T" :answer "ok"}]}})
      (let [{:keys [gather]} (reasoning-over conn rid)]
        (is (false? (:oversight/worth-a-look? gather)))))))

;; --- a run that regrades itself says so (karamazov-7mo M10) -----------------

(deftest changing-the-runs-own-scoring-lands-in-the-brief
  ;; The decision here was DETECTION, not prevention: a run may still rewrite
  ;; the gates it is judged by, because policy being runtime-editable data is
  ;; the project's premise. What it may not do is change its own score
  ;; quietly. Metan's M10 wants the evaluator outside the improver's editable
  ;; surface; this is the half of that we chose to take.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :self-graded
                   {:data {:keys ["fitness"]
                           :changes "{:fitness {:from ... :to ...}}"
                           :rationale "the weights undervalued shipping"}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (= ["fitness"] (:oversight/self-graded gather)))
      (is (str/includes? (str prob) "YOU CHANGED HOW THIS RUN IS SCORED"))
      (is (str/includes? (str prob) "fitness")))))

(deftest a-run-that-tuned-nothing-about-its-scoring-says-nothing
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (empty? (:oversight/self-graded gather)))
      (is (not (str/includes? (str prob) "YOU CHANGED HOW THIS RUN IS SCORED"))))))

;; --- a pass that died on the provider says so, and gets its own timeout ------
;; karamazov-cz90 (run e1b765e7, GLM-5.3): from 17:21 every pass ended
;; :abandoned with notes null because the supervisor's model calls hit the
;; run's 300 s read timeout three times running. The record could not tell a
;; dead stream from a quiet one, and the stream never steered again.

(deftest an-abandoned-pass-carries-the-failure-that-ended-it
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (runs/open-branch! conn rid {:branch-id "SUP"})
    (journal/record-turn! conn rid {:branch-id "SUP" :turn 1 :tool-name "introspect"
                                    :category :neutral :result "=== LOOP WIRING ==="})
    (journal/record-turn! conn rid {:branch-id "SUP" :turn 2 :tool-name "__provider_error__"
                                    :category :neutral
                                    :result "GLM call failed: read timeout: Read timed out"})
    ((:handler (cell/get-cell! :oversight/apply))
     ctx {:oversight/idle 21 :oversight/unmet 5 :oversight/verdict :abandoned
          :oversight/branch {:id "SUP"}})
    (let [note (json/read-str
                (str (:data (last (db/fetch conn ["SELECT data FROM events
                                                    WHERE run_id = ? AND kind = 'oversight'
                                                    ORDER BY id" rid]))))
                :key-fn keyword)]
      (is (= "abandoned" (:verdict note)))
      (is (= "__provider_error__" (get-in note [:failure :tool])))
      (is (str/includes? (str (get-in note [:failure :error])) "read timeout")
          "the note names what ended the pass, in the provider's own words"))))

(deftest an-abandoned-pass-whose-last-turn-looked-fine-still-says-why
  ;; karamazov-n6ql, run b8ae2b1f: two passes recorded verdict abandoned with
  ;; notes null AND failure null, because the failure heuristic reads only the
  ;; last turn and that turn was a neutral `recall`. The branch was carrying
  ;; the reason the whole time — loop.clj sets :inactive-reason on the branch
  ;; it ends — and apply read only its :id.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}]
    (runs/open-branch! conn rid {:branch-id "SUP"})
    (journal/record-turn! conn rid {:branch-id "SUP" :turn 1 :tool-name "recall"
                                    :category :neutral :result "nothing remembered"})
    ((:handler (cell/get-cell! :oversight/apply))
     ctx {:oversight/idle 87 :oversight/unmet 4 :oversight/verdict :abandoned
          :oversight/branch {:id "SUP"
                             :inactive-reason "four turns in a row produced no tool call"}})
    (let [note (json/read-str
                (str (:data (last (db/fetch conn ["SELECT data FROM events
                                                    WHERE run_id = ? AND kind = 'oversight'
                                                    ORDER BY id" rid]))))
                :key-fn keyword)]
      (is (= "abandoned" (:verdict note)))
      (is (nil? (:failure note)) "the last turn was fine, so that field has nothing to say")
      (is (str/includes? (str (:ended note)) "no tool call")
          "and the reason the loop ended it is recorded instead of nothing"))))

(deftest the-supervisors-calls-get-the-streams-own-read-timeout
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {} :llm-config {:timeout-ms 300000}}
        g ((:handler (cell/get-cell! :oversight/gather)) ctx {})
        seen (atom nil)]
    (with-redefs [myc/run-compiled (fn [_ ctx data]
                                     (reset! seen ctx)
                                     {:branch (assoc (:branch data) :final-answer "ok")
                                      :verdict :done})]
      ((:handler (cell/get-cell! :oversight/reason)) ctx g))
    (is (= (:timeout-ms (gates/threshold :oversight))
           (get-in @seen [:llm-config :timeout-ms]))
        "gates.edn :oversight :timeout-ms reaches the pass's provider calls")))

;; --- rejected edits to the project's files (karamazov-1a51.8) ---------------

(deftest a-rejected-edit-nobody-was-told-about-reaches-the-supervisor
  ;; A person's edit, or a shell command's, that did not pass its check: no
  ;; branch heard about it in a tool result, so the supervisor is asked to fix
  ;; it — and it is worth waking for. An edit a file tool made was rejected
  ;; back to its writer already, and is not the supervisor's.
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "oversight-rejected" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! ":memory:")
        prev-root (userspace/bind-root! root)]
    (userspace/bind! conn)
    (try
      (userspace/seed-project!)
      (let [rid (runs/start-run! conn {:problem "p"})
            f (java.io.File. root ".samizdat/manifests/review.edn")]
        (spit f "{:cells {:start :no-such/cell}}")
        (userspace/body :manifest "review")
        (let [{:keys [gather prob]} (reasoning-over conn rid)]
          (is (= 1 (count (:oversight/rejected gather))))
          (is (true? (:oversight/worth-a-look? gather)))
          (is (str/includes? (str prob) "manifests/review.edn"))
          (is (str/includes? (str prob) "no-such/cell"))
          (is (str/includes? (str prob) "Fix each one in place"))
          (is (not (str/includes? (str prob) "is on offer")) "nothing is on offer for it")
          (testing "and when the shipped workflow has an update for that file, the
                    rejection says so beside it"
            (with-redefs [userspace/offers (constantly [{:offer :updated :kind :manifest
                                                         :name "review" :path "manifests/review.edn"}])]
              (let [{:keys [prob]} (reasoning-over conn rid)]
                (is (str/includes? (str prob) "An updated `review` is on offer"))))))
        (testing "an edit already rejected back to the branch that wrote it is not"
          (userspace/check-written! (.getPath f) {:branch-id "B1"})
          (let [g ((:handler (cell/get-cell! :oversight/gather))
                   {:conn conn :run-id rid :config {}} {})]
            (is (empty? (:oversight/rejected g))))))
      (finally (userspace/unbind!) (userspace/bind-root! prev-root) (db/close conn)))))

;; --- what is on offer to the project (karamazov-1a51.6) ----------------------

(deftest what-is-on-offer-wakes-the-supervisor-once-per-run
  ;; Nothing applies a template a later release shipped, or a version the
  ;; project stored before its files, except the supervisor — so the first
  ;; pass of a run shows them, and later passes do not wake for them again.
  (let [root (str (java.nio.file.Files/createTempDirectory
                   "oversight-offers" (make-array java.nio.file.attribute.FileAttribute 0)))
        conn (db/open! ":memory:")
        prev-root (userspace/bind-root! root)]
    (userspace/bind! conn)
    (try
      (userspace/seed-project! (update (userspace/template-map) :prompts dissoc :critic))
      (let [rid (runs/start-run! conn {:problem "p"})
            {:keys [gather prob]} (reasoning-over conn rid)]
        (is (= [["prompt" "critic"]]
               (mapv (juxt (comp name :kind) :name) (:oversight/offers gather))))
        (is (true? (:oversight/worth-a-look? gather)))
        (is (str/includes? (str prob) "On offer to this project"))
        (is (str/includes? (str prob) "prompt `critic` — new"))
        (testing "a later pass of the same run is not woken by the same offers"
          (let [g ((:handler (cell/get-cell! :oversight/gather))
                   {:conn conn :run-id rid :config {}} {})]
            (is (empty? (:oversight/offers g)))
            (is (false? (:oversight/worth-a-look? g))))))
      (finally (userspace/unbind!) (userspace/bind-root! prev-root) (db/close conn)))))

;; --- rejection memory (karamazov-ylte.2) ------------------------------------

(deftest a-refused-mutation-reaches-the-supervisor
  ;; Step 4 of the Procedural Graph paper's Algorithm 1: a candidate the gate
  ;; turned down is kept and handed back as negative evidence, so the refiner
  ;; stops re-proposing equivalent edits.
  ;;
  ;; samizdat had the storage and no retrieval. mutation.clj:193 journals
  ;; :mutation-rolled-back with {:reason :attempt} — and its comment cites
  ;; karamazov-mpd for exactly this reason, that the reason alone lets the next
  ;; run re-derive the same edit — while the only readers were three tests in
  ;; mutation_test. gather read :route, :implement-round, :stage-error and
  ;; :self-graded, and not this. Same shape as karamazov-u5uy: a supervisory
  ;; signal is only as real as the channel it arrives on.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :mutation-rolled-back
                   {:data {:reason "soak did not terminate within the time budget"
                           :attempt {"cells/loop.clj" "(ns cells.loop) ;; edited"}}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (= 1 (count (:oversight/refused gather)))
          "gather carries the run's refused mutations")
      (is (str/includes? (str prob) "REFUSED")
          "and the brief names them, so a pass after a compaction still knows")
      (is (str/includes? (str prob) "soak did not terminate")
          "with the reason the protocol gave"))))

(deftest a-refused-edit-of-any-kind-reaches-the-supervisor
  ;; karamazov-na2k.1. Only the cell path journaled its refusals; a manifest
  ;; that did not compile, a policy body that broke its table, a prompt that
  ;; would not render went back to the branch as base/rejected and were never
  ;; written down, so the brief after a compaction — and every later run —
  ;; had no idea they had been tried.
  (require 'samizdat.agent.tools)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        r ((requiring-resolve 'samizdat.agent.tools/run-tool)
           {:branch {:id "SUP"} :conn conn :run-id rid :tool-name "manifest"
            :args {:action "save" :name "broken" :edn "{:cells {:x :no-such-cell}}"
                   :rationale "a bad save on purpose"}})]
    (is (:edit-rejected? r) (:result r))
    (let [notes (journal/notes conn rid :mutation-rolled-back)
          {:keys [prob]} (reasoning-over conn rid)]
      (is (= 1 (count notes)) "the refusal is journaled once")
      (is (= "manifest broken" (:target (first notes))))
      (is (str/includes? (str prob) "manifest broken")
          "and the brief names what was refused"))))

(deftest an-edit-that-has-not-earned-its-place-reaches-the-supervisor
  ;; karamazov-na2k.10: the brief offers it for removal, with why it was made.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    ((requiring-resolve 'samizdat.store.userspace/save!) conn :prompt "system" "v2" "project" "tightened the split rule")
    (dotimes [_ 3] ((requiring-resolve 'samizdat.store.userspace/record-run-outcome!) conn :failed))
    (journal/note! conn rid :run-error {:data {:error "boom" :node ":x"}})
    (let [{:keys [prob]} (reasoning-over conn rid)]
      (is (str/includes? (str prob) "not earned their place"))
      (is (str/includes? (str prob) "tightened the split rule")))))

(deftest an-open-experiment-is-shown-with-what-the-reviews-said-since
  ;; karamazov-na2k.12, from GEPA: reflection reads {inputs, outputs,
  ;; feedback}, not a bare score. The verdict is a fitness number; the
  ;; reviews written while the change was in force say why it moved.
  (session/reset!)
  (try
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (dotimes [_ 6] (session/observe-turn! {:tool "eval" :category :success :signals {}}))
      (session/experiment! "tighter-fence" {:change "one example call" :hypothesis "fewer parse errors"})
      (journal/note! conn rid :critique {:data {:decision "revise" :verdict "incomplete"
                                                :findings "- [high] the screenshot was never described"}})
      (journal/note! conn rid :run-error {:data {:error "boom" :node ":x"}})
      (let [{:keys [prob]} (reasoning-over conn rid)]
        (is (str/includes? (str prob) "tighter-fence"))
        (is (str/includes? (str prob) "the screenshot was never described")
            "the finding reaches the brief beside the change it was measured under")))
    (finally (session/reset!))))

(deftest a-green-round-refused-on-acceptance-wakes-the-supervisor-and-says-why
  ;; karamazov-na2k.23, run eb64f59d: every task shipped, the suite was green,
  ;; one acceptance judge said no, and three supervisor passes concluded
  ;; 'shipped clean, nothing wrong'. The brief has to say which criterion.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :verify
                   {:data {:passed false :tests-passed true :accepted false
                           :note "tests passed\nacceptance criteria not met:\nFAIL  the game was exercised, not only unit-tested"}})
    (journal/note! conn rid :route
                   {:data {:decision "revise" :revision 0 :soft-cap 6 :strategy "board"
                           :tests-passed true :accepted false}})
    (let [{:keys [gather prob]} (reasoning-over conn rid)]
      (is (true? (:oversight/worth-a-look? gather)) "green work sent back is worth a look")
      (is (str/includes? (str prob) "the game was exercised, not only unit-tested")
          "and the brief names the criterion that refused it")
      (is (not (str/includes? (str prob) "the tests did not pass"))
          "rather than saying the tests failed"))))

(deftest a-refused-mutation-is-worth-a-model-call
  ;; A refusal means the supervisor tried to change the harness and the
  ;; protocol said no. That is precisely a moment where the next pass will
  ;; re-derive the same edit unless something tells it not to, so it earns a
  ;; look on its own — the run need not also be stalling.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :mutation-rolled-back
                   {:data {:reason "validate: unreachable cells" :attempt {}}})
    (let [g ((:handler (cell/get-cell! :oversight/gather)) {:conn conn :run-id rid :config {}} {})]
      (is (true? (:oversight/worth-a-look? g))))))

(deftest a-committed-mutation-is-not-a-refusal-and-buys-nothing
  ;; The negative half only. An edit that went through is not evidence about
  ;; what not to try, and a supervisor woken every time its own change landed
  ;; would be paying a model call to be told it succeeded.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :mutation-committed {:data {:cells ["loop"]}})
    (let [g ((:handler (cell/get-cell! :oversight/gather)) {:conn conn :run-id rid :config {}} {})]
      (is (empty? (:oversight/refused g)))
      (is (false? (:oversight/worth-a-look? g))))))

(deftest dead-gates-reach-the-supervisor-as-deletion-candidates
  ;; The mirror of the graduation block: :candidates asks whether to PROMOTE
  ;; an episode to a rule, this asks whether to DELETE a gate that nothing
  ;; obeys. Before it the supervisor could only ever add, and gates.edn's own
  ;; record of deleting :reflection shows the evidence was always there and
  ;; only a person ever read it.
  (let [conn (db/open! ":memory:")]
    (doseq [r ["r1" "r2" "r3"]]
      (let [rid (runs/start-run! conn {:problem "p" :run-id r})]
        (runs/open-branch! conn rid {:branch-id "B1"})
        (let [id (journal/record-gate! conn rid {:branch-id "B1" :turn 1 :gate :deadwood
                                                 :prediction "p" :window 2})]
          (journal/settle-gate! conn id :unmet 2))))
    (let [rid (runs/start-run! conn {:problem "p"})
          ;; something to make the pass worth a look at all
          _ (journal/note! conn rid :stage-error {:data {:stage "s" :error "boom"}})
          {:keys [prob]} (reasoning-over conn rid)]
      (is (str/includes? (str prob) "never met")
          "the brief carries the retirement block")
      (is (str/includes? (str prob) "deadwood")
          "and names the gate")
      (is (str/includes? (str prob) "Nothing here is retired for you")
          "surfacing, not acting — the graduation block's discipline"))))

(deftest a-pass-continues-the-streams-turn-numbers
  ;; One branch for the run, but every pass ran the supervisor manifest from
  ;; {:turn 1}: run 3020cbca's SUP held 3067 rows under 233 numbers. Then `t8`
  ;; in its own compacted history named several turns, fetch_turn returned the
  ;; first of them, and a reader could not tell the passes apart
  ;; (karamazov-pefk). A pass picks up after the last turn the branch wrote,
  ;; and keeps a whole pass's allowance of turns to do it in.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {} :max-turns 40}
        seen (atom nil)]
    (doseq [n [1 2 3]]
      (journal/record-turn! conn rid {:branch-id "SUP" :turn n :tool-name "recall" :result "x"
                                      :category :neutral}))
    (journal/record-turn! conn rid {:branch-id "B1" :turn 90 :tool-name "shell" :result "x"
                                    :category :neutral})
    (with-redefs [myc/run-compiled (fn [_ rctx data]
                                     (reset! seen {:turn (:turn data) :max-turns (:max-turns rctx)})
                                     {:branch (assoc (:branch data) :final-answer "ok")})]
      ((:handler (cell/get-cell! :oversight/reason))
       ctx {:oversight/idle 30 :oversight/unmet 2 :oversight/turns [] :oversight/firings []}))
    (is (= 4 (:turn @seen)) "after SUP's own last turn, not another branch's")
    (is (= 23 (:max-turns @seen)) "the cap moves with the start, so the pass still gets its :pass-max-turns")
    (testing "a branch with no turns yet starts at 1 under a pass's cap"
      (let [rid2 (runs/start-run! conn {:problem "q"})]
        (with-redefs [myc/run-compiled (fn [_ rctx data]
                                         (reset! seen {:turn (:turn data) :max-turns (:max-turns rctx)})
                                         {:branch (assoc (:branch data) :final-answer "ok")})]
          ((:handler (cell/get-cell! :oversight/reason))
           (assoc ctx :run-id rid2)
           {:oversight/idle 30 :oversight/unmet 2 :oversight/turns [] :oversight/firings []}))
        (is (= {:turn 1 :max-turns 20} @seen))))))

(deftest the-measurement-in-a-notice-reads-as-words-not-edn
  ;; Run 0f4b9b55's notice said `Measured over this session: {:turns 1,
  ;; :retried 10}` — a map printed at whoever reads the agent log
  ;; (karamazov-8cih).
  (is (= "turns 1, retried 10, call failed 2"
         (ov/evidence-text (array-map :turns 1 :retried 10 :call-failed 2))))
  (is (= "" (ov/evidence-text nil))))

(deftest the-supervisor-reads-rfc-passes-that-rest-on-undecided-criteria
  ;; karamazov-0e2c.7: a rubric pass the undecided criteria could overturn
  ;; ships (fail-open), and the supervisor is told it was never shown.
  (let [c (samizdat.store.db/open! ":memory:")
        rid (samizdat.store.runs/start-run! c {:problem "p"})]
    (try
      (samizdat.store.journal/note! c rid :epic-review
                                    {:data {:task "sz-1" :decision "pass"
                                            :rubric {:reward 1.0 :pass true :inconclusive true
                                                     :ratings [{:criterion "parses nested" :rating true}
                                                               {:criterion "streams large files" :rating nil}]}}})
      (let [t (samizdat.prompt/render "rubric-inconclusive"
                                      {:reviews [{:task "sz-1" :reward 1.0 :undecided "streams large files"}]})]
        (is (str/includes? t "sz-1"))
        (is (str/includes? t "streams large files")))
      (finally (samizdat.store.db/close c)))))

(deftest idle-is-counted-per-writing-branch
  ;; karamazov-rz7t.1. Idle used to be turns since ANY write across the whole
  ;; run: the supervisor's own turns counted (it never writes, so its
  ;; investigation raised the number that woke it), and one branch writing
  ;; reset the count for a different branch that was stalled.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        turn! (fn [b n tool]
                (journal/record-turn! conn rid {:branch-id b :turn n :tool-name tool
                                                :result "x" :category :neutral}))]
    (runs/open-branch! conn rid {:branch-id "SUP" :role :supervisor})
    (runs/open-branch! conn rid {:branch-id "T0" :role :implementor})
    (runs/open-branch! conn rid {:branch-id "T1" :role :implementor})
    (turn! "T1" 1 "write_file")
    (doseq [n (range 2 12)] (turn! "T1" n "read_file"))
    (doseq [n (range 1 41)] (turn! "SUP" n "fetch_turn"))
    (turn! "T0" 1 "read_file")
    (turn! "T0" 2 "write_file")
    (let [g (gather-now conn rid)]
      (is (= 10 (:oversight/idle g))
          "T1's ten turns since its own write; SUP's 40 and T0's fresh write change nothing")
      (is (= {"T0" 0 "T1" 10} (:oversight/idle-by-branch g))))
    (testing "a stall on a second branch wakes the supervisor even while the
              first one's stall, already read, is longer"
      (let [g1 (gather-now conn rid)]
        (doseq [n (range 12 40)] (turn! "T1" n "read_file"))
        (let [g2 (gather-now conn rid)]
          (is (true? (:oversight/worth-a-look? g2)) "T1 at 38, never seen")
          (spent-pass! conn rid g2)
          (doseq [n (range 3 30)] (turn! "T0" n "read_file"))
          (is (true? (:oversight/worth-a-look? (gather-now conn rid)))
              "T0 newly at 27 counts whole although T1 is longer")
          (is (some? g1)))))))

(deftest the-brief-is-the-branches-positions-not-their-steps
  ;; karamazov-rz7t.3. A moving branch is one line; a stuck branch's
  ;; failures are shown; the supervisor's own turns are neither.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        turn! (fn [b n tool & [err]]
                (journal/record-turn! conn rid (cond-> {:branch-id b :turn n :tool-name tool
                                                        :result "x" :category :neutral}
                                                 err (assoc :category :mechanics
                                                            :parse-error err))))]
    (runs/open-branch! conn rid {:branch-id "SUP" :role :supervisor})
    (runs/open-branch! conn rid {:branch-id "T0" :role :implementor})
    (runs/open-branch! conn rid {:branch-id "T1" :role :implementor})
    (journal/note! conn rid :progress {:branch-id "T0" :turn 1 :data {:key "phase" :to "build"}})
    (journal/note! conn rid :progress {:branch-id "T1" :turn 1 :data {:key "phase" :to "build"}})
    (turn! "T1" 1 "write_file")
    (turn! "T1" 2 "__parse_error__" "T1 slipped once")
    (turn! "T0" 1 "__parse_error__" "T0 keeps failing")
    (doseq [n (range 2 32)] (turn! "T0" n "read_file"))
    (turn! "SUP" 1 "__parse_error__" "the supervisor's own slip")
    (let [{:keys [prob]} (reasoning-over conn rid)]
      (is (re-find #"T0: build.*STUCK: 31 turns without a write" (str prob)))
      (is (re-find #"T1: build" (str prob)))
      (is (str/includes? (str prob) "T0 keeps failing"))
      (is (not (str/includes? (str prob) "T1 slipped once")))
      (is (not (str/includes? (str prob) "the supervisor's own slip")))
      (is (not (re-find #"- SUP:" (str prob)))))))

;; --- the brief as a delta (karamazov-rz7t.4) --------------------------------

(deftest a-section-is-visible-while-the-brief-that-showed-it-is-in-context
  (let [visible @(cells/cell-var 'cells.oversight 'visible-sections)
        select @(cells/cell-var 'cells.oversight 'select-sections)
        b {:messages [{:role "system"} {:role "user" :brief 1} {:role "user" :brief 2}]
           :brief-shown {1 {:catalog 11 :learned 22} 2 {:learned 33}}}]
    (is (= {:catalog 11 :learned 33} (visible b)) "the newer showing wins")
    (is (= {:catalog 11 :learned 22}
           (visible (assoc b :messages [{:role "user" :brief 1}
                                        {:role "system" :compaction? true}])))
        "a brief folded away is no longer visible; the older showing is")
    (is (= {} (visible (assoc b :messages [{:role "system" :compaction? true}]))))
    (testing "a section is shown when it is new or changed, and not otherwise"
      (let [[shown hashes] (select {:catalog "same" :learned "new" :drift nil}
                                   {:catalog (hash "same") :learned (hash "old")})]
        (is (= {:catalog nil :learned "new" :drift nil} shown))
        (is (= {:learned (hash "new")} hashes))))
    (testing "a section that emptied says so once, rather than leaving the old one standing"
      (let [[shown hashes] (select {:offers nil :retire []} {:offers (hash "two offers") :retire (hash "x")})]
        (is (= ["offers" "retire"] (sort (:cleared shown))))
        (let [[again] (select {:offers nil :retire []} (merge {:offers 1 :retire 1} hashes))]
          (is (empty? (:cleared again)) "and not again"))))))

(deftest the-supervisor-window-never-exceeds-the-model-s
  (let [window @(cells/cell-var 'cells.oversight 'supervisor-window)]
    (is (= 32768 (window 32768 64000)) "a local model's 32K is the ceiling")
    (is (= 64000 (window 128000 64000)))
    (is (= 64000 (window nil 64000)))
    (is (nil? (window 128000 nil)) "no policy keeps the model's")))

(deftest a-closed-branch-is-neither-idle-nor-in-the-digest-as-stuck
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (runs/open-branch! conn rid {:branch-id "SUP" :role :supervisor})
    (runs/open-branch! conn rid {:branch-id "T0" :role :implementor})
    (runs/open-branch! conn rid {:branch-id "T1" :role :implementor})
    (doseq [n (range 1 40)]
      (journal/record-turn! conn rid {:branch-id "T0" :turn n :tool-name "read_file" :result "x" :category :neutral}))
    (journal/record-turn! conn rid {:branch-id "T1" :turn 1 :tool-name "write_file" :result "x" :category :neutral})
    (runs/close-branch! conn rid "T0" "culled" "lost the beam")
    (journal/note! conn rid :progress {:branch-id "SUP" :turn 1 :data {:key "status" :to "active"}})
    (let [g (gather-now conn rid)]
      (is (= {"T1" 0} (:oversight/idle-by-branch g)) "a culled branch is not stalled")
      (is (not (contains? (:oversight/progress g) "SUP")) "nor is the supervisor its own subject"))))

(deftest a-second-pass-over-an-unchanged-run-is-only-what-changed
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}
        seen (atom [])
        pass (fn [carry]
               (let [g (cond-> (gather-now conn rid) carry (assoc :oversight/carry carry))]
                 (with-redefs [myc/run-compiled (fn [_ _ data]
                                                  (swap! seen conj (:content (last (get-in data [:branch :messages]))))
                                                  {:branch (assoc (:branch data) :final-answer "ok")})]
                   ((:handler (cell/get-cell! :oversight/reason)) ctx g))))
        b1 (:oversight/branch (pass nil))
        _ (pass b1)
        [first-brief second-brief] @seen]
    (is (str/includes? first-brief "Decide one of four things"))
    (is (str/includes? first-brief "Workflows available"))
    (is (not (str/includes? second-brief "Decide one of four things"))
        "the instructions are still in its context")
    (is (not (str/includes? second-brief "Workflows available")))
    (is (str/includes? second-brief "Per branch") "the run's position is always new")
    (is (< (count second-brief) (/ (count first-brief) 2)))
    (testing "after a fold took the first brief away, it is shown again"
      (let [folded (update b1 :messages (fn [ms] (vec (remove :brief ms))))]
        (pass folded)
        (is (str/includes? (last @seen) "Workflows available"))))))

(deftest a-brief-the-prune-tier-shrank-is-no-longer-visible
  ;; The prune tier summarises long user-role messages to one line, and a
  ;; brief is one: it keeps its :brief tag but not its sections.
  (let [visible @(cells/cell-var 'cells.oversight 'visible-sections)]
    (is (= {} (visible {:messages [{:role "user" :brief 1 :pruned? true}]
                        :brief-shown {1 {:catalog 11}}})))))

(deftest a-finished-pass-leaves-its-tool-output-as-one-line-each
  ;; karamazov-rz7t.6. What a pass read is settled once the pass ends; its
  ;; bulk stayed in the carried branch until a fold near the ceiling.
  (cells/load-cells!)
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        ctx {:conn conn :run-id rid :config {}}
        seen (atom nil)
        big (apply str (repeat 3000 "x"))
        carry {:id "SUP" :messages [{:role "system" :content "sys"}
                                    {:role "user" :content (str "brief " big) :brief 1}
                                    {:role "assistant" :content "reading"}
                                    {:role "user" :content big :tool "read_file"}]
               :brief-shown {1 {}}}]
    (with-redefs [myc/run-compiled (fn [_ rctx data]
                                     (reset! seen {:branch (:branch data) :rctx rctx})
                                     {:branch (assoc (:branch data) :final-answer "ok")})]
      ((:handler (cell/get-cell! :oversight/reason))
       ctx (assoc (gather-now conn rid) :oversight/carry carry)))
    (let [ms (get-in @seen [:branch :messages])]
      (is (:pruned? (nth ms 3)) "the read is one line now")
      (is (< (count (:content (nth ms 3))) 400))
      (is (not (:pruned? (nth ms 1))) "a brief is not tool output")
      (is (= "reading" (:content (nth ms 2)))))
    (testing "the supervisor works under its own, smaller window"
      (is (= (get-in (gates/threshold :oversight) [:context-window])
             (get-in @seen [:rctx :llm-config :context-window]))))))

;; --- a workflow that asks, and a run that goes silent ------------------------

(deftest an-urgent-look-skips-the-spacing-but-not-the-budget
  (let [st {:last-at 1000 :passes 0}]
    (is (not (ov/due? st {:now 1001 :every-ms 120000 :budget 3 :boundary? true})))
    (is (ov/due? st {:now 1001 :every-ms 120000 :budget 3 :urgent? true}))
    (is (not (ov/due? (assoc st :passes 3) {:now 1001 :every-ms 120000 :budget 3 :urgent? true}))
        "a bound a signal can lift is not a bound")))

(deftest silence-counts-from-the-later-of-the-last-event-and-the-last-look
  (is (ov/silent? {:last-event-at 0 :last-at 0} 100 100))
  (is (not (ov/silent? {:last-event-at 50 :last-at 0} 100 100)))
  (is (not (ov/silent? {:last-event-at 0 :last-at 50} 100 100)) "looked already: once per silence")
  (is (not (ov/silent? {:last-event-at 0 :last-at 0} 100 nil)) "off when not configured"))

(deftest a-workflow-signal-buys-a-look-with-no-turn-ending
  ;; A side-call stage ends no turns, so a stream that only woke on :turn saw a
  ;; whole run die without a single pass.
  (let [ch (events/subscribe)
        passes (atom 0)
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 100000 :budget 10
                         :run-id "R-signal" :event-ch ch}
                        (fn [_] (swap! passes inc) {:carry nil :spent? false}))]
    (try
      (Thread/sleep 80)
      (is (= 1 @passes) "the first look")
      (events/publish! {:kind :progress :run-id "R-signal" :branch-id "B1"})
      (Thread/sleep 80)
      (is (= 1 @passes) "ordinary progress waits for its boundary")
      (events/publish! {:kind :workflow-signal :run-id "R-signal"})
      (Thread/sleep 80)
      (is (= 2 @passes) "the workflow asked, so the spacing does not apply")
      (finally (stop) (events/unsubscribe! ch)))))

(deftest a-silent-run-gets-a-look-and-the-pass-is-told
  (let [ch (events/subscribe)
        seen (atom [])
        clock (atom 0)
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 100000 :budget 10 :silent-ms 1000
                         :run-id "R-silent" :event-ch ch :now-fn (fn [] @clock)}
                        (fn [ctx] (swap! seen conj (:silent-for-ms ctx)) {:carry nil :spent? false}))]
    (try
      (Thread/sleep 60)
      (is (= 1 (count @seen)))
      (is (nil? (first @seen)) "the opening look is not about silence")
      (reset! clock 5000)
      (Thread/sleep 60)
      (is (= 2 (count @seen)))
      (is (= 5000 (second @seen)))
      (Thread/sleep 60)
      (is (= 2 (count @seen)) "one look per silence")
      (finally (stop) (events/unsubscribe! ch)))))

(deftest the-streams-own-turns-are-not-a-boundary
  (let [ch (events/subscribe)
        passes (atom 0)
        stop (ov/start! {:enabled? true :poll-ms 5 :every-ms 0 :budget 10
                         :run-id "R-own" :event-ch ch}
                        (fn [_] (swap! passes inc) {:carry nil :spent? false}))]
    (try
      (Thread/sleep 60)
      (events/publish! {:kind :turn :run-id "R-own" :branch-id "SUP" :turn 1})
      (Thread/sleep 60)
      (is (= 1 @passes))
      (finally (stop) (events/unsubscribe! ch)))))

(deftest a-workflow-signal-is-worth-a-look-once
  (let [floors {:unmet-floor 2 :idle-floor 25}
        since @(cells/cell-var 'cells.oversight 'since-last-look)
        signal {:kind "stuck"}]
    (is (worth-a-look? {:workflow-signals [signal]} floors))
    (is (worth-a-look? {:silent? true} floors))
    (testing "read already: not news"
      (let [news (since {:workflow-signals [signal]} {:workflow-signals 1} {:idle-floor 25})]
        (is (not (worth-a-look? news floors)))))
    (testing "a new one after it is"
      (let [news (since {:workflow-signals [signal signal]} {:workflow-signals 1} {:idle-floor 25})]
        (is (worth-a-look? news floors))))))

(deftest the-brief-says-where-the-workflow-is-and-what-reaches-it
  ;; A template error here ended the pass exactly when a stuck team asked for it.
  (let [brief (prompt/render "oversight-pass"
                             {:digest "D"
                              :workflow-signals [{:workflow "team-solve" :stage "unstick"
                                                  :kind "stuck" :detail "same task twice"}]
                              :silent-minutes 11
                              :position {:workflow "team-solve" :stage "unstick" :round 2 :max-rounds 10
                                         :gate "samples 1/2" :task "fix it"
                                         :attempts [{:round 1 :worker "done" :gate "samples 1/2" :task "write it"}]}})]
    (is (str/includes? brief "stuck: same task twice"))
    (is (str/includes? brief "silent for 11 minute"))
    (is (str/includes? brief "round 2 of 10"))
    (is (str/includes? brief "round 1, worker done, samples 1/2: write it"))
    (is (str/includes? brief "\"kind\": \"retask\""))))

(deftest a-cancelled-pass-is-not-a-failed-one
  ;; The run ending cancels a pass in flight, which recorded as a supervisor failure.
  (with-redefs [journal/note! (fn [& _] (throw (ex-info "must not journal" {})))]
    (let [state (atom {:passes 0})]
      (is (nil? (ov/pass! {:conn :c :run-id "r"} state
                          (fn [_] (throw (InterruptedException.))))))
      (is (= 1 (:passes @state))))))

(deftest a-command-that-keeps-failing-wakes-the-supervisor
  ;; karamazov-oi16.4. The supervisor woke on turns since a write, which a
  ;; branch retrying the same failing command while editing files never
  ;; crosses; on TB its steers arrived after the run had ended. A command that
  ;; has failed :failing-action-floor times and never worked is the stall.
  (let [floors {:unmet-floor 2 :idle-floor 25}
        since @(cells/cell-var 'cells.oversight 'since-last-look)]
    (is (not (worth-a-look? {:failing-actions 0} floors)))
    (is (worth-a-look? {:failing-actions 1} floors))
    (testing "counted from the last look"
      (is (= 2 (:failing-actions (since {:failing-actions 2} nil {:idle-floor 25}))))
      (is (= 0 (:failing-actions (since {:failing-actions 2} {:failing-actions 2} {:idle-floor 25}))))
      (is (= 1 (:failing-actions (since {:failing-actions 3} {:failing-actions 2} {:idle-floor 25})))))))

(deftest the-supervisor-sees-what-became-of-its-directives
  ;; karamazov-oi16.8. Its brief carried branch positions, failures and gate
  ;; health, and nothing about its own steers: whether one landed, was
  ;; refused, came too late, or was acted on.
  (cells/load-cells!)
  (let [outcomes interventions/outcomes
        news @(cells/cell-var 'cells.oversight 'directive-news)
        ivs [{:id 1 :branch_id "B1" :kind "message" :issued_by "supervisor" :status "applied"
              :applied_at_turn 10 :payload "{\"text\":\"run pytest\",\"expect\":\"pytest\"}"}
             {:id 2 :branch_id "B1" :kind "budget" :issued_by "supervisor" :status "rejected"
              :disposition "refused: no workflow boundary" :payload "{\"text\":\"60\"}"}
             {:id 3 :branch_id "B1" :kind "message" :issued_by "watch" :status "applied"
              :applied_at_turn 4 :payload "x"}
             {:id 4 :branch_id "B1" :kind "message" :issued_by "supervisor" :status "pending"
              :payload "later"}]
        firings [{:branch_id "B1" :turn 10 :gate "human-directive" :outcome "unmet"}]
        turns [{:branch_id "B1" :turn 10 :tool_name "shell" :args "{\"command\":\"make\"}"}
               {:branch_id "B1" :turn 11 :tool_name "shell" :args "{\"command\":\"make\"}"}]
        os (outcomes ivs firings turns)]
    (testing "only the supervisor's own"
      (is (= [1 2 4] (mapv :id os))))
    (testing "each says how it ended and what the branch did next"
      (let [o (first os)]
        (is (= "unmet" (:outcome o)))
        (is (= "pytest" (:expect o)))
        (is (= "run pytest" (:text o)))
        (is (str/includes? (:next o) "make"))))
    (testing "a refusal carries its reason"
      (is (str/includes? (:disposition (second os)) "no workflow boundary")))
    (testing "a pass is told only what changed since the last one"
      (let [states (into {} (map (juxt :id :state)) os)]
        (is (= [1 2 4] (mapv :id (news os nil))))
        (is (empty? (news os states)))
        (is (= [1] (mapv :id (news (assoc-in (vec os) [0 :state] "applied/met")
                                   states))))))))

(deftest a-branch-asking-to-ship-wakes-the-supervisor
  ;; karamazov-oi16.11: the ship review is the supervisor's highest-leverage
  ;; moment, and it is asked for by a :ship-review-requested note.
  (let [floors {:unmet-floor 2 :idle-floor 25}
        since @(cells/cell-var 'cells.oversight 'since-last-look)]
    (is (worth-a-look? {:review-requested 1} floors))
    (is (= 1 (:review-requested (since {:review-requested 1} nil {:idle-floor 25}))))
    (is (= 0 (:review-requested (since {:review-requested 1} {:review-requested 1} {:idle-floor 25}))))))

(deftest a-long-run-gets-a-look-after-the-check-in-interval
  ;; ctb-r1 interleaved-vigenere: an hour and 164 turns into a two-hour task,
  ;; on its 63rd analysis script at 16% accuracy, and the supervisor had not
  ;; looked once: every file write reset idle, every command succeeded, and
  ;; nothing else fires on a run that churns without progress.
  (let [since @(cells/cell-var 'cells.oversight 'since-last-look)
        floors {:unmet-floor 2 :idle-floor 25 :checkin-minutes 20}]
    (is (worth-a-look? {:checkin? true} floors))
    (testing "it is due when the interval has passed since the last spent pass"
      (is (:checkin? (since {:minutes-since-look 21} nil floors)))
      (is (not (:checkin? (since {:minutes-since-look 19} nil floors)))))
    (testing "every pass shows the run's clock (ctb-r2 kv-live-surgery: ENDGAME at 30 of 120 minutes)"
      (let [t (prompt/render "oversight-pass" {:clock {:used 30 :limit 120 :left 90}})]
        (is (str/includes? t "30 of 120 minutes"))
        (is (str/includes? t "90"))))
    (testing "and the pass is told why it was woken"
      (is (str/includes? (prompt/render "oversight-pass" {:checkin 21}) "converging")))
    (testing "and off when the policy has no interval"
      (is (not (:checkin? (since {:minutes-since-look 500} nil (dissoc floors :checkin-minutes))))))))

(deftest tests-that-stop-improving-wake-the-supervisor
  ;; The owner's heuristic (2026-11-09): a branch running its tests again and
  ;; again with the pass count not moving is stuck, however busy it looks.
  (let [since @(cells/cell-var 'cells.oversight 'since-last-look)
        floors {:unmet-floor 2 :idle-floor 25 :plateau-runs 4}]
    (is (worth-a-look? {:test-plateau 4} floors))
    (is (not (worth-a-look? {:test-plateau 3} floors)))
    (testing "a plateau the last pass saw wakes the next only after as many runs more"
      (is (= 4 (:test-plateau (since {:test-plateau 4} nil floors))))
      (is (= 1 (:test-plateau (since {:test-plateau 5} {:test-plateau 4} floors))))
      (is (= 4 (:test-plateau (since {:test-plateau 8} {:test-plateau 4} floors)))))
    (testing "the brief says what the runs reported"
      (is (str/includes? (prompt/render "test-plateau" {:runs 4 :passed 5 :failed 2}) "5 passed")))))

(deftest minutes-since-a-pass-counts-from-the-run-when-there-is-none
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})]
    (journal/note! conn rid :run-started {:data {}})
    (is (< (journal/minutes-since conn rid :oversight) 1) "from the run's first note")
    (journal/note! conn rid :oversight {:data {}})
    (is (< (journal/minutes-since conn rid :oversight) 1))))

(deftest a-ship-review-is-pending-until-the-supervisor-answers
  ;; tb21-review dna-assembly: the review was asked for mid-pass, the pass ran
  ;; on for minutes and its answer was a directive, not the pass ending, so
  ;; the hold ran out first. A supervisor directive after the request answers
  ;; it, and the wait is wall-clock seconds, since a turn is 20 s or 50 s.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "p"})
        state (fn [wait] (journal/review-state conn rid wait))]
    (is (not (:supervised? (state 600))) "no supervisor stream has run yet")
    (journal/note! conn rid :oversight-quiet {:data {}})
    (is (:supervised? (state 600)))
    (is (not (:pending? (state 600))) "nothing asked for")
    (journal/note! conn rid :ship-review-requested {:branch-id "B1" :turn 10 :data {}})
    (is (:pending? (state 600)))
    (is (not (:pending? (state 0))) "a review that never comes stops holding after the wait")
    (testing "a directive from someone else does not answer it"
      (interventions/submit! conn rid {:kind "message" :issued-by "watch" :payload "x"})
      (is (:pending? (state 600))))
    (testing "nor does a supervisor's message: it steers mid-review"
      ;; tb21-unread make-mips: a mid-review steer released the hold, the
      ;; done shipped, and the review itself landed after the run ended.
      (Thread/sleep 5)
      (interventions/submit! conn rid {:kind "message" :issued-by "supervisor" :payload "check the path"})
      (is (:pending? (state 600))))
    (testing "the supervisor's review does"
      ;; Timestamps are milliseconds; keep the order unambiguous.
      (Thread/sleep 5)
      (interventions/submit! conn rid {:kind "review" :issued-by "supervisor" :payload "approved"})
      (is (not (:pending? (state 600)))))
    (testing "as does a pass ending"
      (Thread/sleep 5)
      (journal/note! conn rid :ship-review-requested {:branch-id "B1" :turn 20 :data {}})
      (is (:pending? (state 600)))
      (journal/note! conn rid :oversight {:data {:verdict "done"}})
      (is (not (:pending? (state 600)))))))

(deftest a-pending-review-presses-the-supervisor-on-every-turn
  ;; ctb-r1 interleaved-vigenere: the supervisor spent its twelve review turns
  ;; doing the cryptanalysis itself, found the key's offsets and never sent
  ;; anything; the hold lapsed and a 19%-correct cracker shipped. raman-fitting
  ;; before it, the same. The prose said "send each gap as soon as you can name
  ;; it"; a gate says it on every turn, with the seconds left.
  (let [sup (merge (state/new-branch {:id "SUP" :problem "p"}) {:role :supervisor :turns [{:tool "shell"}]})
        decide #(arbiter/decide (merge {:branch sup :max-turns 300
                                        :style-gates (:gates (gates/style {:run {:style :lean}}))}
                                       %))]
    (let [d (decide {:review-left-seconds 200})]
      (is (= :review-deadline (:gate d)) "fires on the supervisor while a review is out")
      (is (str/includes? (:message d) "200")))
    (let [d (decide {:review-left-seconds 200 :time-left-seconds 4800})]
      (is (str/includes? (:message d) "80 minutes")
          "and says the run's own clock, so the review's is not read as it (ctb-r2 interleaved-vigenere)"))
    (is (not= :review-deadline (:gate (decide {}))) "and not without one")
    (is (not= :review-deadline
              (:gate (arbiter/decide {:branch (assoc sup :role :implementer) :max-turns 300
                                      :style-gates (:gates (gates/style {:run {:style :lean}}))
                                      :review-left-seconds 200})))
        "nor on the implementer"))
  (testing "review-state says how long the hold has left"
    (let [conn (db/open! ":memory:")
          rid (runs/start-run! conn {:problem "p"})]
      (journal/note! conn rid :oversight-quiet {:data {}})
      (journal/note! conn rid :ship-review-requested {:branch-id "B1" :turn 1 :data {}})
      (is (<= 590 (:left-seconds (journal/review-state conn rid 600)) 600)))))

(deftest a-ship-review-request-skips-the-spacing
  ;; karamazov-oi16.11: the done is held only :ship-review :max-wait-seconds, and
  ;; the spacing between passes is two minutes.
  (is (contains? (ov/urgent-kinds) :ship-review-requested))
  (is (contains? (ov/urgent-kinds) :workflow-signal)))

(deftest a-done-waits-for-a-directive-the-branch-has-not-read
  ;; tb21-review protein-assembly: the supervisor approved, then found the
  ;; provenance claim unsupported and sent a correction; the branch's done
  ;; landed five seconds later, before a boundary delivered it, and shipped the
  ;; wrong answer. A queued directive holds the done until the branch reads it.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "write /app/out.txt"})
        done! (fn [b turn]
                (tools/run-tool {:branch b :tool-name "done" :turn turn :conn conn :run-id rid
                                 :started-ms (System/currentTimeMillis)
                                 :config {:run {:style :lean}}
                                 :args {:answer "Wrote /app/out.txt and checked it."}}))
        b (state/new-branch {:id "B1" :problem "write /app/out.txt"})
        id (interventions/submit! conn rid {:branch-id "B1" :kind "message" :issued-by "supervisor"
                                            :payload {:text "the lookup returned nil"}})
        r (done! b 10)]
    (is (str/includes? (str (:result r)) "refused") "held while the directive waits")
    (is (str/includes? (str (:result r)) "supervisor") "and says who is waiting")
    (interventions/resolve! conn rid id :applied "delivered" 11)
    (is (str/includes? (str (:result (done! (:branch r) 12))) "accepted")
        "once delivered, the done goes through")
    (testing "a directive to another branch does not hold this one"
      (let [b2 (state/new-branch {:id "B2" :problem "write /app/out.txt"})]
        (interventions/submit! conn rid {:branch-id "SUP" :kind "message" :issued-by "ship-review"
                                         :payload {:text "review"}})
        (is (str/includes? (str (:result (done! b2 13))) "accepted"))))))

(deftest a-lean-done-with-time-left-is-held-for-review
  ;; tb21-review, first three trials: no review was ever asked for. Under :lean
  ;; the implementer is held to the advisory rungs (:lenient-done?), and the
  ;; review read was skipped for any advisory branch, so it never ran where it
  ;; was meant to. Through the real done tool, lean style, a supervised run.
  (let [conn (db/open! ":memory:")
        rid (runs/start-run! conn {:problem "write /app/out.txt"})
        done! (fn [b turn]
                (tools/run-tool {:branch b :tool-name "done" :turn turn :conn conn :run-id rid
                                 :started-ms (System/currentTimeMillis)
                                 :config {:run {:style :lean :time-limit-seconds 3600}}
                                 :args {:answer "Wrote /app/out.txt and checked it."}}))
        b (state/new-branch {:id "B1" :problem "write /app/out.txt"})]
    (journal/note! conn rid :oversight-quiet {:data {}})
    (let [r (done! b 10)]
      (is (str/includes? (str (:result r)) "supervisor") "the done is held for the review")
      (is (= 1 (count (journal/notes conn rid :ship-review-requested))) "and the review is asked for")
      (is (= "B1" (:branch (first (journal/notes conn rid :ship-review-requested))))
          "naming the branch: tb21-unread filter-js spent four minutes of its review looking for it")
      (is (str/includes? (prompt/render "ship-review" {:branch "B1" :problem "p" :answer "a" :evidence []})
                         "\"branch\": \"B1\"")
          "and the brief puts it in the intervene call it shows")
      (is (str/includes? (prompt/render "oversight-pass" {:branches ["B1" "B2"]}) "`B1`, `B2`")
          "and every pass names the branches it can steer")
      (is (str/includes? (prompt/render "ship-review" {:branch "B1" :wait 360 :problem "p" :answer "a" :evidence []})
                         "360 seconds")
          "and the review says when the hold lapses (tb21-unread raman: twelve turns, no answer sent)")
      (is (str/includes? (str (:payload (first (filter #(= "ship-review" (:issued_by %)) (interventions/history conn rid)))))
                         "seconds")
          "as does the notice to a pass already running")
      (let [told (filter #(= "ship-review" (:issued_by %)) (interventions/history conn rid))]
        (is (= ["SUP"] (mapv :branch_id told))
            "and a pass already running is told, at its next turn (tb21-review dna-assembly)")
        (is (str/includes? (str (:payload (first told))) "out.txt") "with the answer it is reviewing"))
      (let [r2 (done! (:branch r) 12)]
        (is (str/includes? (str (:result r2)) "has not landed") "held while it is out")
        (is (= 1 (count (journal/notes conn rid :ship-review-requested))) "asked for once")))))
