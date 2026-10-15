;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.memory-revise-test
  "Backward revision (karamazov-4dn8.4, ReHarness arXiv 2610.12668): a
  memory a branch was shown is rewritten against the trajectory it was drawn
  from, with this branch's outcome as feedback only, through restate! so its
  history stays."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [mycelium.cell :as cell]
            [samizdat.agent.revise :as revise]
            [samizdat.cells :as cells]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]))

(deftest the-reply-is-one-of-three-verdicts
  (is (= {:verdict :keep} (revise/parse-verdict "KEEP")))
  (is (= {:verdict :revise :text "build with jolt bin, not jolt uberjar"}
         (revise/parse-verdict "REVISE: build with jolt bin, not jolt uberjar")))
  (is (= {:verdict :retire :text "the flag was removed"}
         (revise/parse-verdict "  retire: the flag was removed\n")))
  (is (nil? (revise/parse-verdict "I think this memory is mostly fine")))
  (is (nil? (revise/parse-verdict "REVISE:")) "a revision with no text is no revision"))

(deftest a-verdict-is-read-after-the-reasoning
  ;; GLM's reply opens with its <think> block, so a verdict anchored at the
  ;; start never matched and every revision was refused as unparseable.
  (is (= {:verdict :revise :text "build with jolt bin"}
         (revise/parse-verdict "<think>KEEP? no, RETIRE? no.</think>\nREVISE: build with jolt bin")))
  (is (nil? (revise/parse-verdict "<think>KEEP it, I think")) "a truncated thought is no verdict"))

(def ^:private policy {:max-per-branch 3 :feedback-turns 4 :answer-chars 100})

(defn- fixture
  "Run r1 wrote a memory from T0's turns 2-3; run r2's branch B1 was shown it
  at turn 4 and went on to fail with it."
  []
  (let [c (db/open! ":memory:")
        r1 (runs/start-run! c {:problem "first"})
        _ (doseq [[t res] [[2 "jolt uberjar: unknown task"] [3 "jolt bin: wrote ./app"]]]
            (journal/record-turn! c r1 {:branch-id "T0" :turn t :tool-name "shell"
                                        :result res :category :success}))
        mem (knowledge/remember! c {:content "build the binary with jolt uberjar" :kind "semantic"
                                    :run-id r1 :source {:branch "T0" :from 2 :to 3}})
        own (let [r2 (runs/start-run! c {:problem "second"})]
              [r2 (knowledge/remember! c {:content "written this run" :kind "semantic"
                                          :run-id r2 :source {:branch "B1" :from 1 :to 1}})])
        [r2 own-id] own]
    (journal/note! c r2 :exposed {:branch-id "B1" :turn 4
                                  :data {:via "breadcrumb" :ids [mem own-id]}})
    (doseq [[t res] [[5 "jolt uberjar: unknown task"] [6 "gave up"]]]
      (journal/record-turn! c r2 {:branch-id "B1" :turn t :tool-name "shell"
                                  :result res :category :failure}))
    {:c c :r1 r1 :r2 r2 :mem mem :own own-id}))

(deftest only-memories-from-another-run-with-a-source-are-candidates
  (let [{:keys [c r2 mem]} (fixture)]
    (is (= [mem] (mapv (comp :id :row) (revise/candidates c r2 "B1" {:max-per-branch 3})))
        "this run's own memory has no earlier trajectory to be revised against")
    (is (empty? (revise/candidates c r2 "B9" {:max-per-branch 3})))))

(deftest a-revision-is-grounded-in-the-source-and-kept-in-the-lineage
  (let [{:keys [c r2 mem]} (fixture)
        sent (atom nil)
        out (with-redefs [samizdat.llm.client/chat
                          (fn [_ _ msgs & _]
                            (reset! sent (:content (second msgs)))
                            {:content "REVISE: build the binary with jolt bin"
                             :usage {:prompt-tokens 100 :completion-tokens 10}})]
              (revise/revise! {:conn c :run-id r2}
                              {:id "B1" :status :abandoned}
                              policy))]
    (testing "the model was given the source and the outcome, apart"
      (is (str/includes? @sent "jolt bin: wrote ./app") "the source trajectory")
      (is (str/includes? @sent "gave up") "this branch's outcome, as feedback")
      (is (str/includes? @sent "abandoned")))
    (testing "the old wording is retired, the new one carries its lineage and source"
      (let [old (knowledge/get-by-id c mem)
            new (knowledge/get-by-id c (:new-id (first out)))]
        (is (= 0 (:current old)))
        (is (= "build the binary with jolt bin" (:content new)))
        (is (= (:lineage_id old) (:lineage_id new)))
        (is (= "T0" (:source_branch new)))))
    (testing "and the run's record says what happened and what it cost"
      (is (= "revise" (:verdict (first (journal/notes c r2 :memory-revised)))))
      (is (pos? (or (:side-calls (journal/run-usage c r2)) 0))))))

(deftest retire-withdraws-and-keep-changes-nothing
  (let [{:keys [c r2 mem]} (fixture)]
    (with-redefs [samizdat.llm.client/chat (fn [& _] {:content "KEEP"})]
      (revise/revise! {:conn c :run-id r2} {:id "B1" :status :done} policy))
    (is (= 1 (:current (knowledge/get-by-id c mem))))
    (with-redefs [samizdat.llm.client/chat (fn [& _] {:content "RETIRE: uberjar was never a task here"})]
      (revise/revise! {:conn c :run-id r2} {:id "B1" :status :done} policy))
    (is (= 0 (:current (knowledge/get-by-id c mem))))))

(deftest the-cell-decides-by-policy-and-outcome
  (cells/load-cells!)
  (let [{:keys [c r2 mem]} (fixture)
        calls (atom 0)
        run (fn [policy status]
              (with-redefs [samizdat.agent.gates/threshold
                            (let [t samizdat.agent.gates/threshold]
                              (fn [k] (if (= k :memory-revise) policy (t k))))
                            samizdat.llm.client/chat (fn [& _] (swap! calls inc) {:content "KEEP"})]
                ((:handler (cell/get-cell! :memory/revise))
                 {:conn c :run-id r2} {:branch {:id "B1" :status status}})))]
    (run {:enabled? false :outcomes ["done"] :max-per-branch 3 :feedback-turns 4 :answer-chars 100} :done)
    (is (zero? @calls) "off by default until the arena says otherwise")
    (run {:enabled? true :outcomes ["done"] :max-per-branch 3 :feedback-turns 4 :answer-chars 100} :abandoned)
    (is (zero? @calls) "noisy outcomes are left out")
    (run {:enabled? true :outcomes ["done"] :max-per-branch 3 :feedback-turns 4 :answer-chars 100} :done)
    (is (= 1 @calls))
    (is (= 1 (:current (knowledge/get-by-id c mem))))))

;; --- grounding (karamazov-4dn8.5) --------------------------------------------

(def ^:private grounding {:min-token 4 :max-leaked 0})

(deftest a-revision-must-come-from-the-source-not-the-outcome
  (let [source [{:turn 3 :tool_name "shell" :args "{\"command\":\"jolt bin\"}"
                 :result "wrote ./app" :category "success"}]
        outcome [{:turn 5 :tool_name "shell" :args "{\"command\":\"jolt uberjar\"}"
                  :result "unknown task uberjar; try ./scripts/package.sh" :category "failure"}]
        check #(revise/grounded "build the binary with jolt bin" % source outcome grounding)]
    (is (:ok? (check "build the binary with jolt bin, which writes ./app"))
        "every new word is in the source")
    (let [r (check "build the binary with ./scripts/package.sh")]
      (is (not (:ok? r)) "the outcome's own advice is leakage, not grounding")
      (is (= :leaked (:why r)))
      (is (some #{"./scripts/package.sh"} (:tokens r))))
    (let [r (check "shell does not work in this project")]
      (is (not (:ok? r)))
      (is (= :contradicted (:why r)) "its own source shows shell working"))))

(deftest a-refused-revision-leaves-the-memory-as-it-was
  (let [{:keys [c r2 mem]} (fixture)]
    (with-redefs [samizdat.llm.client/chat (fn [& _] {:content "REVISE: the binary build gave up"})]
      (revise/revise! {:conn c :run-id r2} {:id "B1" :status :done}
                      (assoc policy :grounding grounding)))
    (is (= 1 (:current (knowledge/get-by-id c mem))))
    (is (= "refused" (:verdict (first (journal/notes c r2 :memory-revised)))))
    (is (= "leaked" (:why (first (journal/notes c r2 :memory-revised)))))))
