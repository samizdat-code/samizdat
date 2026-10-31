;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.compaction-test
  "The context-budget ladder, ported from dirge.

  Compaction was one threshold on a character count, fired reactively. These
  pin the ported behaviour: fractions of the model's window rather than an
  absolute size, the cheap rungs before the expensive ones, the protected head
  and tail, the user-boundary snapping that keeps a fold from orphaning half a
  tool call, and the structural check that must pass before any history is
  destroyed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.compaction :as cmp]
            [samizdat.agent.context-edit :as context-edit]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.loop]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.journal]
            [samizdat.cells :as cells]
            [samizdat.manifests :as manifests]
            [mycelium.cell :as cell]
            [samizdat.config :as config]
            [samizdat.store.journal]
            [samizdat.workflow]))

(def ^:private ladder
  {:aggressive-cap 0.60 :fold 0.75 :aggressive-fold 0.78 :force 0.80 :turn-start 0.90})

;; --- measuring ---------------------------------------------------------------

(deftest tokens-are-counted-across-both-content-shapes
  ;; A tool result arrives as a block vector, not a string. Counting only the
  ;; string shape is how dirge's own prune pass was a silent no-op on live
  ;; results — the pass ran, matched nothing, and reported success.
  (is (= 4 (cmp/content-chars "abcd")))
  (is (= 6 (cmp/content-chars [{:type "text" :text "abc"} {:type "text" :text "def"}])))
  (is (= 0 (cmp/content-chars nil)))
  (testing "and the estimate divides the total, not each message"
    (is (= 2 (cmp/estimate-tokens [{:content "abcd"} {:content "abcd"}] 4)))))

(deftest an-unknown-window-produces-no-pressure
  ;; A model whose context size nobody recorded gets NO ladder rather than a
  ;; guessed one: every rung is a fraction of that number.
  (is (= 0.0 (cmp/pressure 5000 nil)))
  (is (= 0.0 (cmp/pressure 5000 0)))
  (is (= 0.5 (cmp/pressure 5000 10000))))

;; --- the ladder --------------------------------------------------------------

(deftest the-ladder-answers-with-the-highest-rung-reached
  (is (nil? (cmp/tier 0.4 ladder)) "below every rung, nothing happens")
  (is (= :aggressive-cap (cmp/tier 0.62 ladder)))
  (is (= :fold (cmp/tier 0.76 ladder)))
  (is (= :aggressive-fold (cmp/tier 0.79 ladder)))
  (is (= :force-summary (cmp/tier 0.85 ladder)))
  (testing "a branch at 0.92 wants the turn-start fold, not the per-result cap
            it also passed — most severe wins"
    (is (= :turn-start-fold (cmp/tier 0.92 ladder)))))

(deftest only-the-upper-rungs-fold-and-only-they-are-aggressive
  (is (not (cmp/fold? :aggressive-cap)) "capping a result is not folding history")
  (is (every? cmp/fold? [:fold :aggressive-fold :force-summary :turn-start-fold]))
  (is (not (cmp/aggressive? :fold)))
  (is (every? cmp/aggressive? [:aggressive-fold :force-summary :turn-start-fold])))

(deftest the-result-cap-tightens-under-pressure
  (let [caps {:normal 3000 :aggressive 1000}]
    (is (= 3000 (cmp/result-cap 0.4 ladder caps)))
    (is (= 1000 (cmp/result-cap 0.7 ladder caps))
        "past the aggressive-cap rung one grep result may not eat the window")))

(deftest a-snip-can-excuse-a-normal-fold-but-never-an-aggressive-one
  (is (cmp/snip-bought-enough? 2000 10000 0.10 false)
      "freeing 20% of the window is headroom enough for this turn")
  (is (not (cmp/snip-bought-enough? 500 10000 0.10 false))
      "5% is not")
  (testing "past the aggressive rungs the summary is the thing that is needed,
            and a snip that looks sufficient is how a branch reaches 0.9
            having never folded"
    (is (not (cmp/snip-bought-enough? 9000 10000 0.10 true)))))

;; --- capping and pruning -----------------------------------------------------

(deftest capping-keeps-both-ends-of-an-oversized-result
  ;; The head says what ran and the tail says how it ended. A plain head
  ;; truncation drops every error message.
  (let [big (str "START" (apply str (repeat 4000 "x")) "END")
        {:keys [messages freed]}
        (cmp/cap-oversized-results
         [{:role "user" :content big} {:role "user" :content "recent"}]
         {:cap-tokens 100 :chars-per-token 4 :protect-tail 1
          :roles #{"user"} :note-fn (fn [n] (str "<" n " dropped>"))})]
    (is (str/starts-with? (:content (first messages)) "START"))
    (is (str/ends-with? (:content (first messages)) "END"))
    (is (str/includes? (:content (first messages)) "dropped"))
    (is (pos? freed))
    (is (= "recent" (:content (second messages))) "the protected tail is untouched")))

(deftest pruning-keeps-the-call-and-drops-the-output
  ;; The distinction that makes this cheaper than folding: every turn stays
  ;; where it is, and only the bulk goes. What the branch DID survives; what
  ;; it SAW is one fetch_turn away.
  (let [templates {:preview-chars 20
                   :render (fn [t ctx] (str "[" (:tool ctx) "] " (:chars ctx) " chars"))
                   :by-tool {} :default "x"}
        msgs [{:role "user" :tool "grep" :content (apply str (repeat 900 "m"))}
              {:role "user" :tool "shell" :content "short"}
              {:role "user" :tool "read_file" :content (apply str (repeat 900 "r"))}]
        out (cmp/prune-tool-outputs msgs {:protect-tail 1 :min-chars 500
                                          :roles #{"user"}
                                          :tool-of :tool :templates templates})]
    (is (= "[grep] 900 chars" (:content (first out))))
    (is (:pruned? (first out)))
    (is (= "short" (:content (second out))) "a short result is left readable")
    (is (= 900 (count (:content (last out)))) "the protected tail is untouched")))

;; --- choosing the window -----------------------------------------------------

(defn- convo [n]
  (vec (for [i (range n)]
         {:role (if (even? i) "user" "assistant") :content (str "m" i)})))

(deftest a-short-conversation-is-not-folded
  (is (= [0 0] (cmp/compress-window (convo 5) 2 5))
      "nothing to fold once the head and tail claim everything"))

(deftest both-cuts-snap-to-user-boundaries
  ;; NOT tidiness. A user message never carries a dangling tool call and is
  ;; never itself an orphaned tool result, so cutting there removes whole
  ;; turns. Cutting mid-turn leaves half a call/result pair, which some
  ;; providers reject outright.
  (let [msgs (convo 20)
        [s e] (cmp/compress-window msgs 2 5)]
    (is (= "user" (:role (nth msgs s))) "the head cut lands on a user turn")
    (is (= "user" (:role (nth msgs e))) "and so does the tail cut")
    (is (>= s 2) "the head snap only ever protects more")
    (is (<= e 15) "and so does the tail snap")))

(deftest a-later-fold-takes-the-earlier-summary-in
  ;; MEASURED on the rz7t.7 GLM run: the supervisor's summaries are system
  ;; messages, the head cut snapped PAST each one to the next user turn, and
  ;; every fold stacked a new summary after the last — four of 15-36k chars
  ;; by turn 42, none ever refolded, until a fold freed 1% of a 66k prompt.
  (let [summary {:role "system" :content "MARKER:old" :compaction? true}
        msgs (into [{:role "system" :content "sys"} {:role "user" :content "go"}
                    {:role "assistant" :content "call"} summary]
                   (convo 14))
        [s e] (cmp/compress-window msgs 2 5)]
    (is (= 3 s) "the window starts AT the earlier summary, so the next one replaces it")
    (is (< s e)))
  (testing "a user-role marker is a user turn already"
    (let [msgs (into [{:role "system" :content "sys"} {:role "user" :content "go"}
                      {:role "assistant" :content "call"}
                      {:role "user" :content "MARKER:old" :compaction? true}]
                     (convo 14))]
      (is (= 3 (first (cmp/compress-window msgs 2 5)))))))

(deftest the-summary-budget-is-a-fraction-floored-and-capped
  (let [b {:ratio 0.20 :floor 2000 :ceiling 12000}]
    (is (= 2000 (cmp/summary-budget 100 b)) "a small fold still carries its facts")
    (is (= 10000 (cmp/summary-budget 50000 b)))
    (is (= 12000 (cmp/summary-budget 500000 b))
        "a summary approaching the size of what it replaced has compacted nothing")))

;; --- the guard on destroying history -----------------------------------------

(def ^:private validation
  {:sections ["Active Task" "Goal" "Completed Actions" "Relevant Files" "Source Coverage"]
   :min-sections 2
   :empties #{"" "none" "n/a" "na" "-" "—" "unknown" "nothing" "todo" "tbd"}})

(deftest a-stub-summary-may-not-destroy-history
  ;; The caller acts on TRUE by dropping the folded region, so a false
  ;; positive costs real turns permanently.
  (is (not (cmp/validate-summary "" validation)))
  (is (not (cmp/validate-summary "## Active Task\nNone.\n## Goal\nN/A" validation))
      "every section a placeholder is a stub however many headings it has")
  (is (not (cmp/validate-summary "## Active Task\nFix the parser." validation))
      "one populated section is below the floor"))

(deftest a-terse-but-real-summary-passes
  ;; Permissiveness matters as much as safety here: rejecting a usable summary
  ;; forces prune-only folding, which walks the branch into the overflow the
  ;; fold existed to prevent.
  (is (cmp/validate-summary
       "## Active Task\nFix step-move.\n## Goal\nShip the game.\n## Goal\nx"
       validation))
  (testing "prose that merely uses the words is not mistaken for a summary"
    (is (not (cmp/validate-summary
              "The goal was to fix it and the active task is done." validation)))))

;; --- applying it -------------------------------------------------------------

(deftest a-fold-replaces-the-window-in-place
  (let [msgs (convo 12)
        out (cmp/apply-summary msgs [2 8] "MARKER:" "the summary")]
    (is (= (- 12 6 -1) (count out)) "six messages became one")
    (is (= "MARKER:the summary" (:content (nth out 2))))
    (is (:compaction? (nth out 2)))
    (is (= (:content (nth msgs 0)) (:content (nth out 0))) "the head keeps its place")
    (is (= (:content (last msgs)) (:content (last out))) "and the tail follows")))

(deftest a-fold-carries-pinned-messages-through-verbatim
  ;; The tape's per-message unload never touches a :pinned? message (the
  ;; current task's statement); the fold must not either, or a long enough
  ;; task gets summarised away exactly when it matters most (karamazov-d5wo.1).
  (let [pinned {:role "user" :content "THE TASK" :pinned? true}
        msgs (assoc (convo 12) 4 pinned)
        out (cmp/apply-summary msgs [2 8] "MARKER:" "the summary")]
    (is (= "MARKER:the summary" (:content (nth out 2))) "the summary stands where the window was")
    (is (= pinned (nth out 3)) "and the pinned message follows it, whole")
    (is (= (- 12 6 -2) (count out)) "the other five folded messages became the one summary")
    (is (= [pinned] (cmp/pinned-in msgs [2 8])))
    (is (= [] (cmp/pinned-in (convo 12) [2 8])))))

;; --- the cost fold (karamazov-d5wo.6) -----------------------------------------

(deftest a-fold-pays-when-carrying-the-window-costs-more-than-rebuilding
  (let [p {:cached-weight 0.1 :horizon 40}]
    (testing "fold cost: re-prefill what follows the fold, plus the summarizer reading the window"
      (is (= 26000.0 (cmp/fold-cost {:window-tokens 12000 :after-tokens 14000}))))
    (is (cmp/fold-pays? {:window-tokens 12000 :after-tokens 14000 :remaining 30} p)
        "0.1 x 12000 x 30 = 36000 carried against 26000 to fold")
    (is (not (cmp/fold-pays? {:window-tokens 12000 :after-tokens 14000 :remaining 10} p))
        "near the end of the budget there is not enough run left to earn it back")
    (is (not (cmp/fold-pays? {:window-tokens 12000 :after-tokens 14000 :remaining 30}
                             (assoc p :cached-weight 0.01)))
        "where a cached token is nearly free (a local prefix cache) it never pays")
    (is (not (cmp/fold-pays? {:window-tokens 12000 :after-tokens 14000 :remaining 500} (assoc p :horizon 20)))
        "the horizon caps how far ahead the saving is counted")
    (is (not (cmp/fold-pays? {:window-tokens 0 :after-tokens 100 :remaining 30} p)))))

(deftest measure-asks-for-a-fold-below-the-ladder-when-it-pays
  (cells/load-cells!)
  (let [big (apply str (repeat 4000 "x"))
        msgs (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
                   (mapcat (fn [i] [{:role "assistant" :content (str "call " i)}
                                    {:role "user" :content big}])
                           (range 10)))
        ;; ~10k tokens at 4 chars/token in a 20k window: 0.5, under every rung.
        measure (fn [cost-fold turn]
                  (with-redefs [gates/threshold (let [orig gates/threshold]
                                                  (fn [k] (if (= k :cost-fold) cost-fold (orig k))))]
                    ((:handler (cell/get-cell! :compaction/measure))
                     {:llm-config {:context-window 20000} :max-turns 60}
                     {:branch {:messages msgs} :turn turn})))
        on {:enabled? true :min-ratio 0.4 :cached-weight 0.5 :horizon 40}]
    (let [out (measure on 2)]
      (is (= :fold (:compaction/tier out)))
      (is (= :cap (:compaction/route out)))
      (is (= :cost (:compaction/why out))))
    (testing "off, or below the floor, or cheap to carry: the ladder alone decides"
      (is (= :none (:compaction/route (measure (assoc on :enabled? false) 2))))
      (is (= :none (:compaction/route (measure (assoc on :min-ratio 0.9) 2))))
      (is (= :none (:compaction/route (measure (assoc on :cached-weight 0.001) 2))))
      (is (= :none (:compaction/route (measure on 59))) "one turn left earns nothing back")
      (is (= :none (:compaction/route (measure (assoc on :cached-weight nil) 2)))
          "no declared price for a cached token, no cost fold"))
    (testing "the provider's declared weight is the one used"
      (with-redefs [gates/threshold (let [orig gates/threshold]
                                      (fn [k] (if (= k :cost-fold) (assoc on :cached-weight nil) (orig k))))]
        (is (= :fold (:compaction/tier
                      ((:handler (cell/get-cell! :compaction/measure))
                       {:llm-config {:context-window 20000 :cached-token-weight 0.5} :max-turns 60}
                       {:branch {:messages msgs} :turn 2}))))))))

;; --- the task fold (karamazov-d5wo.5) -----------------------------------------

(defn- task-convo
  "sys, problem, the claim call, then the task's statement and six turns,
  ending on the call that closes it."
  []
  (vec (concat
        [{:role "system" :content "sys"}
         {:role "user" :content "problem"}
         {:role "assistant" :content "task claim T1" :turn 1}
         {:role "user" :content "[harness] You are now working on T1" :task-id "T1"}
         {:role "user" :content "claimed" :turn 1}]
        (mapcat (fn [t] [{:role "assistant" :content (str "call " t) :turn t}
                         {:role "user" :content (str "result " t) :turn t}])
                (range 2 5))
        [{:role "user" :content "DIR RULES" :pinned? true :instructions "a"}
         {:role "assistant" :content "task close T1" :turn 5}])))

(deftest a-closed-tasks-span-runs-from-its-statement-to-the-closing-call
  (let [msgs (task-convo)]
    (is (= [3 (dec (count msgs))] (cmp/task-span msgs "T1"))
        "the statement starts it, and the closing call stays for its result")
    (is (nil? (cmp/task-span msgs "T9")) "no statement, no span")))

(deftest a-task-fold-leaves-one-line-and-the-pinned-messages
  (let [msgs (task-convo)
        [s e] (cmp/task-span msgs "T1")
        out (cmp/fold-task msgs [s e] "T1 folded")]
    (is (= (take 3 msgs) (take 3 out)) "everything before the task is untouched")
    (is (= {:role "user" :content "T1 folded" :task-fold "T1"} (nth out 3)))
    (is (= "DIR RULES" (:content (nth out 4))) "a pinned directory file rides through")
    (is (= "task close T1" (:content (last out))) "and the closing call is still there")
    (is (= 6 (count out))))
  (testing "the turns folded, for the line to name; the closing turn stays whole"
    (is (= [1 4] (cmp/turn-range (subvec (task-convo) 3 12))))))

(deftest dispatch-folds-the-task-a-call-closed
  (cells/load-cells!)
  (let [before {:id "B1" :task {:id "T1" :title "the task"} :messages (task-convo)}
        dispatch (fn [branch-after policy]
                   (with-redefs [samizdat.agent.loop/tool-step
                                 (fn [_ _ _ _] {:branch branch-after :result {:ok "closed"} :tool "task"})
                                 gates/threshold (let [orig gates/threshold]
                                                   (fn [k] (if (= k :task-fold) policy (orig k))))]
                     (:branch ((:handler (cell/get-cell! :tool/dispatch))
                               {}
                               {:branch before :turn 5
                                :parsed {:name "task" :args {:action "close" :id "T1"}}}))))
        closed (assoc before :task nil)]
    (let [out (dispatch closed {:enabled? true :min-messages 4})]
      (is (some #(= "T1" (:task-fold %)) (:messages out)) "the span folded")
      (is (str/includes? (:content (first (filter :task-fold (:messages out)))) "T1")))
    (testing "a span under the floor is left to age out on its own"
      (is (= (:messages closed) (:messages (dispatch closed {:enabled? true :min-messages 50})))))
    (testing "switched off, nothing folds"
      (is (= (:messages closed) (:messages (dispatch closed {:enabled? false :min-messages 4})))))
    (testing "a close that did not end the held task folds nothing"
      (is (= (:messages before) (:messages (dispatch before {:enabled? true :min-messages 4})))))))

(deftest a-task-fold-keeps-what-the-person-answered
  ;; Seen live: a branch asked ask_human at turn 5, built on the answers,
  ;; closed its task — and the fold took the answers with it. The next turn
  ;; read "the operator has not decided" in the problem and asked again.
  (cells/load-cells!)
  (let [answer (samizdat.llm.message/frame-result "ask_human" "How long is a loan? → 21 days")
        convo (assoc (task-convo) 6 {:role "user" :turn 3
                                     :content (str answer "\n\n---\n\n[harness] a gate said")})
        before {:id "B1" :task {:id "T1" :title "the task"} :messages convo}]
    (is (= ["How long is a loan? → 21 days"]
           (cmp/results-of convo (cmp/task-span convo "T1") #{"ask_human"}))
        "the framed body, without what the harness appended after the frame")
    (with-redefs [samizdat.agent.loop/tool-step
                  (fn [_ _ _ _] {:branch (assoc before :task nil) :result {:ok "closed"} :tool "task"})
                  gates/threshold (let [orig gates/threshold]
                                    (fn [k] (if (= k :task-fold)
                                              {:enabled? true :min-messages 4 :carry ["ask_human"]}
                                              (orig k))))]
      (let [out (:branch ((:handler (cell/get-cell! :tool/dispatch))
                          {} {:branch before :turn 5
                              :parsed {:name "task" :args {:action "close" :id "T1"}}}))
            line (:content (first (filter :task-fold (:messages out))))]
        (is (str/includes? line "21 days") "the answer rides on the fold's line")))))

(deftest a-later-fold-can-find-the-earlier-one
  (let [msgs [{:role "user" :content "a"}
              {:role "system" :content "MARKER:first summary"}
              {:role "user" :content "b"}]
        [i body] (cmp/find-previous-summary msgs "MARKER:")]
    (is (= 1 i))
    (is (= "first summary" body)))
  (is (nil? (cmp/find-previous-summary [{:role "user" :content "a"}] "MARKER:"))))

;; --- the shipped policy ------------------------------------------------------

(deftest the-shipped-ladder-is-ordered-and-complete
  ;; A ladder whose rungs are out of order silently disables the ones above
  ;; the misplaced entry, because `tier` answers with the first match.
  (let [p (gates/threshold :compaction)
        l (:ladder p)]
    (is (some? l))
    (is (< (:aggressive-cap l) (:fold l) (:aggressive-fold l) (:force l) (:turn-start l))
        "ascending pressure, or the higher rungs are unreachable")
    (is (every? #(< 0.0 % 1.0) (vals l)) "every rung is a fraction of the window")
    (testing "and the aggressive tail is tighter than the normal one — that is
              what makes an aggressive fold aggressive"
      (is (< (:aggressive-tail p) (:protect-tail p))))
    (testing "the summary floor is below its ceiling"
      (is (< (:floor (:summary p)) (:ceiling (:summary p)))))))

;; --- what the journal records -------------------------------------------------

(deftest a-cap-records-the-pressure-that-triggered-it
  ;; CAP IS THE MOST-USED RUNG AND WAS THE ONLY ONE WHOSE TRIGGER WENT
  ;; UNRECORDED. Run 623ccae8 capped 153 times (86,772 tokens freed) and
  ;; 69880d84 fourteen more, all noting {:tier :action :freed} and never the
  ;; pressure — while fold, which fires far less often, recorded :before and
  ;; :after all along.
  ;;
  ;; The cost is not hypothetical. Reading 69880d84 back, aggressive-cap fires
  ;; while max(prompt_tokens) is 22,905 against a 128,000 window whose
  ;; aggressive rung is 76,800. Either one oversized result spiked the measure
  ;; and was clipped back down — the first cap freed 17,999 tokens in one go —
  ;; or the ladder is miscalibrated. The number that decides it is the one
  ;; that was not written down (karamazov-be8).
  (cells/load-cells!)
  (let [notes (atom [])
        big (apply str (repeat 40000 "x"))
        data {:compaction/tier :aggressive-cap
              :compaction/ratio 0.83
              :compaction/before 61000
              :branch {:messages (into [{:role "user" :content "go"}]
                                       (repeat 8 {:role "user" :content big}))}}]
    (with-redefs [samizdat.store.journal/note!
                  (fn [_ _ kind m] (swap! notes conj (assoc (:data m) ::kind kind)) nil)]
      ((:handler (cell/get-cell! :compaction/cap))
       {:conn ::conn :run-id "r1"
        :llm-config {:context-window 128000}}
       data))
    (let [n (first (filter #(= "cap" (:action %)) @notes))]
      (is (some? n) "the cap noted something")
      (is (= 61000 (:before n))
          "the pressure it measured, so the ladder's calibration is auditable
           from the journal instead of by inference")
      (is (= 0.83 (:ratio n))
          "and the fraction of the window that pressure was")
      (testing "without losing what it already recorded"
        (is (= "aggressive-cap" (:tier n)))
        (is (pos? (:freed n)))))))

;; --- the routing lives in the manifest, not in a cell ------------------------

(deftest the-ladder-routes-through-the-manifest
  ;; THE POINT OF THE WHOLE CHANGE. A single cell that measured the pressure
  ;; and then did all four things internally is the same hardcoding moved out
  ;; one level: the manifest shows one opaque box and nothing about the policy
  ;; is rewireable. The rule roles/supervisor.md states is that a dispatch
  ;; predicate READS a key a cell wrote and never computes one.
  (cells/load-cells!)
  (doseq [m ["worker" "loop" "supervisor" "reviewer"]]
    (let [d (manifests/read-definition (manifests/manifest-body! m))]
      (testing (str m " has each rung as its own node")
        (is (= :compaction/measure (get-in d [:cells :measure])) m)
        (is (= :compaction/cap (get-in d [:cells :cap])) m)
        (is (= :compaction/prune (get-in d [:cells :prune])) m)
        (is (= :compaction/fold (get-in d [:cells :fold])) m))
      (testing (str m " routes on a key rather than an inline decision")
        (is (= {:cap :cap :none :admit} (get-in d [:edges :measure])) m)
        (is (= {:prune :prune :none :admit} (get-in d [:edges :cap])) m))
      ;; Through the budget admission, which reserves the call the ladder
      ;; has just sized (karamazov-05z0.3).
      (testing (str m " starts the turn by measuring, and ends the ladder at the call")
        (is (= :measure (get-in d [:edges :start])) m)
        (is (= :admit (get-in d [:edges :fold])) m)
        (is (= :infer (get-in d [:edges :admit :infer])) m)))))

(deftest the-dispatch-patterns-only-read-the-route
  ;; A predicate that computed the tier would put the routing back inside code
  ;; and leave the manifest describing something that is not what runs. The
  ;; tables are patterns now (karamazov-aqsr.4), which cannot compute at all;
  ;; what this pins is that they read the ROUTE key and nothing else.
  (cells/load-cells!)
  (let [d (manifests/read-definition (manifests/manifest-body! "worker"))
        entries (concat (get-in d [:dispatches :measure]) (get-in d [:dispatches :cap]))]
    (is (seq entries))
    (doseq [[_label pattern] entries]
      (is (or (= '_ pattern)
              (and (map? pattern)
                   (= [:compaction/route] (keys pattern))
                   (keyword? (:compaction/route pattern))))
          (str "a dispatch pattern must read :compaction/route, not compute: "
               (pr-str pattern))))))

(deftest measure-writes-the-route-and-touches-nothing
  (cells/load-cells!)
  (let [msgs [{:role "system" :content "sys"} {:role "user" :content (apply str (repeat 40000 "x"))}]
        run (fn [window]
              ((:handler (cell/get-cell! :compaction/measure))
               {:llm-config {:context-window window}}
               {:branch {:messages msgs}}))]
    (testing "an unknown window routes :none — every rung is a fraction of it"
      (is (= :none (:compaction/route (run nil))))
      (is (nil? (:compaction/tier (run nil)))))
    (testing "pressure past the first rung routes to the cheapest action"
      (let [out (run 12000)]
        (is (= :cap (:compaction/route out)))
        (is (some? (:compaction/tier out)))))
    (testing "and it rewrites no messages — measuring and acting are separate"
      (is (= msgs (get-in (run 12000) [:branch :messages]))))))

(deftest a-pruned-result-is-keyed-by-the-tool-that-produced-it
  ;; The loop stamps :tool on a tool-result message for exactly this. Without
  ;; it every pruned result falls to the generic template, which carries the
  ;; one thing worth nothing: a preview of output nobody will read.
  ;;
  ;; Enough messages that the protected tail does not claim them all — which
  ;; is itself worth pinning, since a short conversation must prune NOTHING.
  (cells/load-cells!)
  (let [long-out (apply str (repeat 900 "x"))
        head (fn [tool body] {:role "user" :tool tool :content body})
        msgs (into [(head "shell" (str "jolt -M:test\n" long-out))
                    (head "grep" long-out)
                    {:role "user" :content long-out}]
                   (repeat 6 {:role "user" :content "recent"}))
        out (get-in ((:handler (cell/get-cell! :compaction/prune))
                     {} {:branch {:messages msgs}})
                    [:branch :messages])]
    (is (str/starts-with? (:content (nth out 0)) "[shell] ran `jolt -M:test`")
        "a shell result's useful line is the command it ran")
    (is (str/starts-with? (:content (nth out 1)) "[grep]"))
    (is (str/includes? (:content (nth out 1)) "matches"))
    (is (str/starts-with? (:content (nth out 2)) "[]")
        "an unstamped message still prunes, on the generic line")
    (is (every? #(= "recent" (:content %)) (take-last 6 out))
        "the protected tail is untouched")
    (is (= :fold (:compaction/route
                  ((:handler (cell/get-cell! :compaction/prune))
                   {} {:branch {:messages msgs}}))))))

(deftest a-short-conversation-prunes-nothing
  ;; The protected tail claims every message, which is correct: there is no
  ;; older output to remove and clipping the live exchange would be the one
  ;; thing compaction must never do.
  (cells/load-cells!)
  (let [msgs (vec (repeat 4 {:role "user" :tool "shell" :content (apply str (repeat 900 "x"))}))
        out (get-in ((:handler (cell/get-cell! :compaction/prune))
                     {} {:branch {:messages msgs}})
                    [:branch :messages])]
    (is (= msgs out))))

(deftest the-ladder-has-a-window-to-measure-against
  ;; IT WAS BUILT INERT. Every rung is a fraction of the model's context
  ;; window and nothing in the config set one, so `measure` routed :none on
  ;; every turn and no fold could ever happen — the whole ladder ran and did
  ;; nothing, which is the shape of every bug this project keeps finding.
  (doseq [p [:glm :deepseek :openai :local :ollama]]
    (let [w (:context-window (get config/providers-for-test p))]
      (is (and w (pos? w)) (str "provider " p " must declare a context window"))))
  (testing "and it reaches the :llm config the loop hands to compaction"
    (is (pos? (:context-window (:llm (config/load-config {:roles {:default :glm}})))))))

(deftest a-compaction-note-names-the-branch-and-the-turn
  ;; Every rung noted WHAT it did and none noted WHERE: {:data …} with no
  ;; :branch-id and no :turn, so a fold could not be joined to the cache miss
  ;; it caused. On endless-flight (GLM-5.3, 2026-09-13) 249 caps and 11
  ;; folds were unjoinable to any of the 33 low-hit turns (karamazov-o4wm.1).
  (cells/load-cells!)
  (let [notes (atom [])
        big (apply str (repeat 40000 "x"))
        data {:compaction/tier :aggressive-cap
              :compaction/ratio 0.83
              :compaction/before 61000
              :turn 4
              :branch {:id "B7"
                       :messages (into [{:role "user" :content "go"}]
                                       (repeat 8 {:role "user" :content big}))}}]
    (with-redefs [samizdat.store.journal/note!
                  (fn [_ _ kind m] (swap! notes conj (assoc m ::kind kind)) nil)]
      ((:handler (cell/get-cell! :compaction/cap))
       {:conn ::conn :run-id "r1" :llm-config {:context-window 128000}}
       data))
    (let [n (first (filter #(= "cap" (get-in % [:data :action])) @notes))]
      (is (some? n))
      (is (= "B7" (:branch-id n)))
      (is (= 4 (:turn n))))))

(deftest a-framed-shell-result-still-prunes-to-its-command
  ;; karamazov-o4wm.3: the frame's opening tag is the first line of every
  ;; tool result now, and the shell template's `first-line` is the command.
  ;; The summariser reads the body, not the frame.
  (cells/load-cells!)
  (let [long-out (apply str (repeat 900 "x"))
        msgs (into [{:role "user" :tool "shell"
                     :content (samizdat.llm.message/frame-result
                               "shell" (str "jolt -M:test\n" long-out))}]
                   (repeat 6 {:role "user" :content "recent"}))
        out (get-in ((:handler (cell/get-cell! :compaction/prune))
                     {} {:branch {:messages msgs}})
                    [:branch :messages])]
    (is (str/starts-with? (:content (nth out 0)) "[shell] ran `jolt -M:test`")
        "the command, not the frame's opening tag")))

;; --- the fold's role (karamazov-fp21.2) --------------------------------------

(deftest a-fold-can-stand-as-a-user-turn-where-the-template-refuses-a-system-one
  ;; Qwen3.5's chat template enforces "System message must be at the
  ;; beginning" and 500s the next call on any system role mid-conversation.
  ;; The fold marker IS one. The role is a parameter, and a later fold finds
  ;; the marker whichever role carried it.
  (let [msgs (convo 12)
        out (cmp/apply-summary msgs [2 8] "MARKER:" "the summary" "user")]
    (is (= "user" (:role (nth out 2))))
    (is (= "MARKER:the summary" (:content (nth out 2))))
    (is (:compaction? (nth out 2))))
  (testing "the default is still a system message"
    (is (= "system" (:role (nth (cmp/apply-summary (convo 12) [2 8] "M:" "s") 2)))))
  (testing "a later fold finds a user-role marker as it finds a system one"
    (let [msgs [{:role "user" :content "a"}
                {:role "user" :content "MARKER:first"}
                {:role "user" :content "b"}]]
      (is (= [1 "first"] (cmp/find-previous-summary msgs "MARKER:")))))
  (testing "an assistant turn is never mistaken for a marker"
    (is (nil? (cmp/find-previous-summary
               [{:role "assistant" :content "MARKER:no"}] "MARKER:")))))

(deftest the-fold-cell-takes-its-role-from-the-probe-unless-policy-overrides
  ;; gates.edn :fold-role :auto reads what the startup probe learned about
  ;; the endpoint's template (llm-config :fold-role); an explicit "system" or
  ;; "user" is the operator's word over the probe's.
  (cells/load-cells!)
  (let [big (apply str (repeat 3000 "x"))
        msgs (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
                   (mapcat (fn [i] [{:role "assistant" :content (str "call " i)}
                                    {:role "user" :content big}])
                           (range 8)))
        data {:compaction/tier :fold :compaction/before 999999
              :branch {:messages msgs}}
        fold (fn [llm-config]
               (with-redefs [samizdat.store.journal/note! (fn [& _] nil)
                             samizdat.llm.client/chat
                             (fn [& _] {:content "## Active Task\nFinish it.\n## Goal\nShip.\n## Completed Actions\nRead a.\n## Active State\nGreen."})
                             samizdat.agent.compaction/validate-summary (fn [& _] true)
                             samizdat.store.knowledge/distil-session! (fn [& _] 0)]
                 (let [out ((:handler (cell/get-cell! :compaction/fold))
                            {:conn ::conn :run-id "r1"
                             :llm-adapter ::adapter :llm-config llm-config}
                            data)]
                   (some #(when (:compaction? %) (:role %))
                         (get-in out [:branch :messages])))))]
    (testing "no probe result and :auto — the system role, as before"
      (is (= "system" (fold {:context-window 128000}))))
    (testing "the probe said the template refuses a mid-conversation system role"
      (is (= "user" (fold {:context-window 128000 :fold-role "user"}))))
    (testing "a pinned message inside the window survives the fold and is not summarised"
      (let [task {:role "user" :content "PINNED TASK STATEMENT" :pinned? true}
            msgs' (assoc msgs 7 task)
            sent (atom nil)
            out (with-redefs [samizdat.store.journal/note! (fn [& _] nil)
                              samizdat.llm.client/chat
                              (fn [& args]
                                (reset! sent (pr-str args))
                                {:content "## Active Task\nFinish it.\n## Goal\nShip.\n## Completed Actions\nRead a.\n## Active State\nGreen."})
                              samizdat.agent.compaction/validate-summary (fn [& _] true)
                              samizdat.store.knowledge/distil-session! (fn [& _] 0)]
                  ((:handler (cell/get-cell! :compaction/fold))
                   {:conn ::conn :run-id "r1"
                    :llm-adapter ::adapter :llm-config {:context-window 128000}}
                   (assoc-in data [:branch :messages] msgs')))
            out-msgs (get-in out [:branch :messages])]
        (is (some :compaction? out-msgs) "the fold happened")
        (is (some #(= task %) out-msgs) "the pinned message is still there, whole")
        (is (not (str/includes? @sent "PINNED TASK STATEMENT"))
            "and the summarizer never saw it, so it is not duplicated in the summary")))
    (testing "the summary is the model's answer, not its reasoning"
      ;; GLM's reply carries its <think> block inline (client/chat merges
      ;; reasoning into :content for the loop's fence parser); kept, it made
      ;; the summary several times its budget.
      (let [out (with-redefs [samizdat.store.journal/note! (fn [& _] nil)
                              samizdat.llm.client/chat
                              (fn [& _] {:content "<think>let me weigh what matters here</think>\n## Active Task\nFinish it.\n## Goal\nShip."})
                              samizdat.agent.compaction/validate-summary (fn [& _] true)
                              samizdat.store.knowledge/distil-session! (fn [& _] 0)]
                  ((:handler (cell/get-cell! :compaction/fold))
                   {:conn ::conn :run-id "r1"
                    :llm-adapter ::adapter :llm-config {:context-window 128000}}
                   data))
            s (some #(when (:compaction? %) (:content %)) (get-in out [:branch :messages]))]
        (is (str/includes? s "Finish it."))
        (is (not (str/includes? s "weigh what matters")))))
    (testing "a second fold replaces the first summary instead of stacking another"
      (let [once (with-redefs [samizdat.store.journal/note! (fn [& _] nil)
                               samizdat.llm.client/chat
                               (fn [& _] {:content "## Active Task\nFinish it.\n## Goal\nShip."})
                               samizdat.agent.compaction/validate-summary (fn [& _] true)
                               samizdat.store.knowledge/distil-session! (fn [& _] 0)]
                   (let [f #((:handler (cell/get-cell! :compaction/fold))
                             {:conn ::conn :run-id "r1"
                              :llm-adapter ::adapter :llm-config {:context-window 128000}}
                             %)
                         first-fold (f data)
                         grown (update-in first-fold [:branch :messages] into
                                          (mapcat (fn [i] [{:role "assistant" :content (str "more " i)}
                                                           {:role "user" :content big}])
                                                  (range 8)))]
                     (f (assoc grown :compaction/before 999999))))]
        (is (= 1 (count (filter :compaction? (get-in once [:branch :messages])))))))
    (testing "an explicit policy value wins over the probe"
      (with-redefs [gates/threshold (let [orig gates/threshold]
                                      (fn [k] (if (= k :fold-role) "system" (orig k))))]
        (is (= "system" (fold {:context-window 128000 :fold-role "user"})))))))

(deftest a-fold-can-summarise-on-the-branch-s-own-prefix
  ;; karamazov-05z0.11: the summarizer was sent a fresh [system, material]
  ;; pair, so the whole window was prefilled again — ~110 s a fold on a
  ;; local model. :summarize-via :tape sends the branch's own conversation
  ;; with the instruction appended to its newest turn, so everything before
  ;; that turn is the prefix the provider already holds.
  (cells/load-cells!)
  (let [big (apply str (repeat 3000 "x"))
        msgs (into [{:role "system" :content "BRANCH SYSTEM PROMPT"} {:role "user" :content "go"}]
                   (mapcat (fn [i] [{:role "assistant" :content (str "call " i)}
                                    {:role "user" :content (str "result " i " " big)}])
                           (range 8)))
        data {:compaction/tier :fold :compaction/before 999999
              :branch {:id "B1" :messages msgs}}
        summary "## Active Task\nFinish it.\n## Goal\nShip.\n## Completed Actions\nRead a.\n## Active State\nGreen."
        fold (fn [via role-config]
               (let [sent (atom nil)
                     orig gates/threshold]
                 (with-redefs [gates/threshold (fn [k] (if (= k :compaction)
                                                         (assoc (orig k) :summarize-via via)
                                                         (orig k)))
                               samizdat.workflow/role-ctx (fn [ctx _] (cond-> ctx role-config (assoc :llm-config role-config)))
                               samizdat.store.journal/note! (fn [& _] nil)
                               samizdat.llm.client/chat (fn [_ _ messages & _]
                                                          (reset! sent (vec messages))
                                                          {:content summary :finish-reason "stop"})
                               samizdat.agent.compaction/validate-summary (fn [& _] true)
                               samizdat.store.knowledge/distil-session! (fn [& _] 0)]
                   (let [out ((:handler (cell/get-cell! :compaction/fold))
                              {:conn ::conn :run-id "r1"
                               :llm-adapter ::adapter :llm-config {:context-window 128000}}
                              data)]
                     {:sent @sent :folded? (some :compaction? (get-in out [:branch :messages]))}))))]
    (testing ":tape — the branch's own system prompt and history, the instruction on its newest turn"
      (let [{:keys [sent folded?]} (fold :tape nil)]
        (is folded?)
        (is (= "BRANCH SYSTEM PROMPT" (:content (first sent))))
        (is (= (count msgs) (count sent)) "no message added: the instruction rides the last turn")
        (is (str/includes? (str (:content (last sent))) "result 7") "the newest turn is still there")
        (is (str/includes? (str (:content (last sent))) "context checkpoint") "with the instruction after it")))
    (testing ":fresh — the summarizer's own two messages, as before"
      (let [{:keys [sent]} (fold :fresh nil)]
        (is (= 2 (count sent)))
        (is (not= "BRANCH SYSTEM PROMPT" (:content (first sent))))))
    (testing "a :summarizer role on another model cannot share the prefix, so it goes fresh"
      (let [{:keys [sent]} (fold :tape {:context-window 128000 :model "other"})]
        (is (= 2 (count sent)))))))

(deftest a-fold-on-the-tape-does-not-pay-to-read-the-window-again
  (is (= 1500.0 (cmp/fold-cost {:window-tokens 10000 :after-tokens 1500 :window-cached? true})))
  (is (= 11500.0 (cmp/fold-cost {:window-tokens 10000 :after-tokens 1500}))))

(defn- evictable-conversation []
  (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
        (mapcat (fn [t]
                  [{:role "assistant" :content (str "call " t) :turn t}
                   {:role "user" :content (apply str (repeat 3000 "x")) :turn t}])
                (range 1 9))))

(defn- fold-with-strategy [strategy calls data]
  (with-redefs [gates/threshold (let [orig gates/threshold]
                                  (fn [k]
                                    (if (= k :compaction)
                                      (assoc (orig k) :strategy strategy)
                                      (orig k))))
                samizdat.llm.client/chat (fn [& _]
                                           (swap! calls inc)
                                           (throw (ex-info "eviction must not call the model" {})))
                samizdat.store.journal/note! (fn [& _] nil)]
    ((:handler (cell/get-cell! :compaction/fold))
     {:conn ::conn :run-id "r1" :llm-adapter ::adapter
      :llm-config {:context-window 128000}}
     data)))

(deftest deterministic-eviction-keeps-cumulative-turn-addresses
  (cells/load-cells!)
  (let [calls (atom 0)
        msgs (assoc (evictable-conversation) 7
                    {:role "user" :content "PINNED" :turn 99 :pinned? true})
        first-fold (fold-with-strategy
                    :evict calls
                    {:compaction/tier :fold :compaction/before 999999
                     :branch {:messages msgs}})
        first-marker (first (filter :evicted-turns (get-in first-fold [:branch :messages])))
        first-refs (:evicted-turns first-marker)]
    (is (seq first-refs))
    (is (not (contains? (set first-refs) 99)) "a pinned message was not evicted")
    (is (some #(= "PINNED" (:content %)) (get-in first-fold [:branch :messages])))
    (is (zero? @calls) "no summarizer or other model call")
    (let [grown (update-in first-fold [:branch :messages] into
                           (mapcat (fn [t]
                                     [{:role "assistant" :content (str "later " t) :turn t}
                                      {:role "user" :content (apply str (repeat 3000 "y")) :turn t}])
                                   (range 9 13)))
          second-fold (fold-with-strategy :evict calls (assoc grown :compaction/before 999999))
          second-refs (first (keep :evicted-turns (get-in second-fold [:branch :messages])))]
      (is (every? (set second-refs) first-refs)
          "a later eviction does not forget the earlier references")
      (is (> (count second-refs) (count first-refs))
          "and it adds the newly evicted turns"))))

(deftest edit-strategy-drops-only-addressable-messages-without-a-model
  (cells/load-cells!)
  (let [calls (atom 0)
        msgs (assoc (evictable-conversation) 5
                    {:role "user" :content "UNADDRESSED"})
        out (fold-with-strategy :edit calls
                                {:compaction/tier :fold :compaction/before 999999
                                 :branch {:messages msgs}})
        remaining (get-in out [:branch :messages])]
    (is (zero? @calls))
    (is (some #(= "UNADDRESSED" (:content %)) remaining))
    (is (not-any? #(contains? #{2 3 4} (:turn %)) remaining)
        "the fold window leaves history only as a projection; originals stay in the journal")))

(deftest eviction-stamps-the-marker-it-inserts-not-an-older-one
  (let [msgs [{:role "system" :content "old" :compaction? true :evicted-turns [1]}
              {:role "user" :content "keep"}
              {:role "assistant" :content "call 2" :turn 2}
              {:role "user" :content "result 2" :turn 2}
              {:role "assistant" :content "call 3" :turn 3}
              {:role "user" :content "result 3" :turn 3}]
        out (cmp/apply-eviction msgs [3 5] "M:" "new" "system" [2 3])]
    (is (= [1] (:evicted-turns (nth out 0))) "the older marker keeps its references")
    (is (= [2 3] (:evicted-turns (nth out 3))) "the new marker carries the new references")))

(deftest eviction-refuses-history-without-a-journal-address
  (cells/load-cells!)
  (let [msgs (update (evictable-conversation) 5 dissoc :turn)
        data {:compaction/tier :fold :compaction/before 999999
              :branch {:messages msgs}}
        out (fold-with-strategy :evict (atom 0) data)]
    (is (= msgs (get-in out [:branch :messages]))
        "an unaddressable message is never dropped by deterministic eviction")))

(def ^:private editable-messages
  [{:id "m1" :role "assistant" :content "call 1" :tool-calls [{:id "c1"}]}
   {:id "m2" :role "user" :content "result 1" :tool-call-id "c1"}
   {:id "m3" :role "assistant" :content "old answer"}
   {:id "m4" :role "user" :content "next"}])

(deftest context-edits-compose-and-preserve-originals
  (let [staged (context-edit/stage editable-messages
                                   {:drop #{"m1" "m2"}
                                    :edits {"m3" "new answer"}
                                    :restore #{"m1" "m2"}
                                    :notes ["keep this"]})
        out (context-edit/apply-edit staged editable-messages 0 "head")]
    (is (= ["m1" "m2" "m3" "m4"] (mapv :id (take 4 (:messages out)))))
    (is (= "new answer" (:content (nth (:messages out) 2))))
    (is (= "[context note]\nkeep this" (:content (last (:messages out)))))
    (is (= 1 (:revision out)))
    (is (= editable-messages (:source staged)) "the source remains immutable for recall")))

(deftest context-edit-rejects-stale-state-and-broken-tool-pairs
  (let [staged (context-edit/stage editable-messages {:drop #{"m1"}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"revision"
                          (context-edit/apply-edit staged editable-messages 2 "head")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"head"
                          (context-edit/apply-edit staged editable-messages 0 "other")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"tool"
                          (context-edit/apply-edit staged editable-messages 0 "head")))))

(deftest context-edit-tool-composes-restores-and-rejects-stale-revisions
  (context-edit/forget-run! "edit-tool")
  (let [run-id "edit-tool"
        branch {:id "B1" :messages editable-messages}
        call (fn [branch args]
               (base/run-tool {:tool-name "context_edit" :run-id run-id
                               :branch branch :args args}))
        first-out (call branch {:drop ["m1" "m2"] :edits {"m3" "new answer"}
                                :revision 0 :head "m4"})
        second-out (call (:branch first-out)
                         {:restore ["m1" "m2"] :notes ["remember this"]
                          :revision 1 :head "m4"})
        stale-out (call (:branch second-out)
                        {:drop ["m4"] :revision 0 :head "m4"})]
    (is (= :neutral (:category first-out)))
    (is (= 1 (get-in first-out [:branch :context-revision])))
    (is (= ["m1" "m2" "m3" "m4"]
           (mapv :id (take 4 (get-in second-out [:branch :messages]))))
        "a later restore addresses the immutable source, not the dropped projection")
    (is (= "new answer" (:content (nth (get-in second-out [:branch :messages]) 2)))
        "a restore does not discard unrelated earlier edits")
    (is (= "[context note]\nremember this"
           (:content (last (get-in second-out [:branch :messages])))))
    (is (= :mechanics (:category stale-out)))
    (is (re-find #"revision" (:result stale-out)))
    (is (= 2 (get-in stale-out [:branch :context-revision])))))

(deftest context-edit-keeps-new-unaddressable-messages-and-returns-the-live-head
  (context-edit/forget-run! "edit-anonymous")
  (let [run-id "edit-anonymous"
        branch {:id "B1"
                :messages (into [{:role "system" :content "rules"}] editable-messages)}
        call (fn [branch args]
               (base/run-tool {:tool-name "context_edit" :run-id run-id
                               :branch branch :args args}))
        first-out (call branch {:edits {"m3" "changed"} :revision 0 :head "m4"})
        grown (update (:branch first-out) :messages conj
                      {:role "user" :content "new unaddressable"})
        second-out (call grown {:notes ["new note"] :revision 1 :head "m4"})
        anonymous-drop (call (:branch second-out)
                             {:drop [nil] :revision 2
                              :head (get-in second-out [:branch :context-head])})]
    (is (= :neutral (:category second-out)))
    (is (= 1 (count (filter #(= "new unaddressable" (:content %))
                            (get-in second-out [:branch :messages]))))
        "a message without a turn/id is not original journal data and survives edits")
    (is (not= "m4" (get-in second-out [:branch :context-head])))
    (is (str/includes? (:result second-out)
                       (str (get-in second-out [:branch :context-head])))
        "the model receives the head the next call must supply")
    (is (= :mechanics (:category anonymous-drop)))
    (is (re-find #"unknown" (:result anonymous-drop))
        "nil is not an address that can drop every unaddressable message")))

(deftest compaction-pressure-includes-native-tools-and-reserved-output
  (cells/load-cells!)
  (let [branch {:id "measure-prepared"
                :messages [{:role "system"
                            :content (str "Tools:\n```\nread_file({path})\n    Read a file by its path.\n"
                                          "alpha_tool({query, limit?})\n    Search the alpha index.\n```")}
                           {:role "user" :content "go"}]
                :turns []}
        ctx {:llm-config {:provider :test :model "measure-model"
                          :context-window 4096 :max-tokens 64
                          :features #{:native-tools}}}
        prepared (infer/prepare ctx (infer/of-branch branch)
                                {:attempt 1 :fit? false})
        expected (:total-tokens (:accounting prepared))
        message-only (cmp/estimate-tokens (:messages branch) 4)
        out ((:handler (cell/get-cell! :compaction/measure))
             (assoc ctx :max-turns 10)
             {:branch branch :turn 1})]
    (is (> expected (+ message-only 64)))
    (is (= expected (:compaction/before out))
        "measure consumes the preparation's structured total")
    (is (= (cmp/pressure expected 4096) (:compaction/ratio out))
        "reserved output and native tools contribute to pressure")))

(deftest fold-no-progress-compares-the-same-structured-total
  (cells/load-cells!)
  (let [msgs (into [{:role "system"
                     :content "Tools:\n```\nread_file({path})\n    Read a file by its path.\n```"}
                    {:role "user" :content "go"}]
                   (mapcat (fn [i] [{:role "assistant" :content (str "call " i)}
                                    {:role "user" :content (str "result " i)}])
                           (range 8)))
        ctx {:conn ::conn :run-id "fold-total" :llm-adapter ::adapter
             :llm-config {:provider :test :model "fold-model"
                          :context-window 8192 :max-tokens 64
                          :features #{:native-tools}}}
        branch {:id "fold-total-B1" :messages msgs :turns []}
        before (get-in (infer/prepare ctx (infer/of-branch branch)
                                      {:attempt 1 :fit? false})
                       [:accounting :total-tokens])
        data {:compaction/tier :fold :compaction/before before
              :branch branch :turn 1}
        notes (atom [])]
    (with-redefs [samizdat.workflow/role-ctx (fn [ctx _] ctx)
                  samizdat.llm.client/chat (fn [& _] {:content "## Active Task\nUnchanged"})
                  samizdat.agent.compaction/validate-summary (fn [& _] true)
                  samizdat.agent.compaction/apply-summary (fn [messages & _] messages)
                  samizdat.store.journal/note!
                  (fn [_ _ _ note] (swap! notes conj note) nil)]
      ((:handler (cell/get-cell! :compaction/fold)) ctx data))
    (is (some #(= "no-progress" (get-in % [:data :action])) @notes)
        "identical structured totals are no progress, even with tools and output reserve")))
