;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.reflect-test
  "What a finished task leaves behind about the PROJECT.

  Everything else the harness distils is the harness watching itself. This is
  the half that was missing: across the live runs the implementor made 46 turns
  and zero `remember` calls, so every session started knowing nothing about the
  codebase and spent its first turns rediscovering the layout."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.reflect :as reflect]
            [samizdat.llm.client]
            [samizdat.store.db :as db]
            [samizdat.store.journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs]))

(def ^:private conn (atom nil))
(use-fixtures :each (fn [f] (reset! conn (db/open! ":memory:")) (f) (db/close @conn)))

;; --- the half that CAN be tested --------------------------------------------

(deftest the-reasoning-is-not-a-reflection
  (is (= {"FACTS" ["builds with jolt bin"]}
         (select-keys (reflect/parse-sections
                       "<think>\n## FACTS\n- maybe it uses make\n</think>\n## FACTS\n- builds with jolt bin")
                      ["FACTS"]))))

(deftest every-fact-the-harness-means-to-hand-over-reaches-the-prompt
  ;; dirge guards exactly this in CI (compaction_recall.rs), and the reasoning
  ;; carries over: nobody can unit-test what a model chooses to write, but a
  ;; fact that never ARRIVED is a harness bug — a truncation or a window
  ;; mistake silently starving the reflector — and that is testable.
  (let [turns [{:turn 1 :tool_name "shell" :args "{:command \"jolt -A:test\"}"
                :result "Ran 5 tests, 0 failures" :category "success"}
               {:turn 2 :tool_name "shell" :args "{:command \"find . | head\"}"
                :result "Command needs approval: not on the allow list" :category "neutral"}
               {:turn 3 :tool_name "edit_file" :args "{:path \"src/calc/core.clj\"}"
                :result "Edited src/calc/core.clj" :category "success"}]
        memories [{:id "k-abc123" :kind "semantic" :content "tests live under test/"}]
        p (reflect/build-prompt {:problem "implement the calculator"
                                 :turns turns :memories memories})]
    (testing "the task, every command, every outcome"
      (is (str/includes? p "implement the calculator"))
      (is (str/includes? p "jolt -A:test"))
      (is (str/includes? p "find . | head"))
      (is (str/includes? p "src/calc/core.clj")))
    (testing "the error text, because the gotchas worth recording live in it"
      (is (str/includes? p "not on the allow list"))
      (is (str/includes? p "Ran 5 tests")))
    (testing "and the memories it is being asked to check"
      (is (str/includes? p "k-abc123"))
      (is (str/includes? p "tests live under test/")))))

;; --- parsing ----------------------------------------------------------------

(deftest an-empty-section-is-not-a-memory
  ;; A model told to leave a section empty writes "None." or a dash or echoes
  ;; the bracketed instruction. Recording those as facts would fill the store
  ;; with lines that cost every future session tokens and say nothing.
  (let [s (reflect/parse-sections
           "## OVERVIEW\nNone.\n\n## FACTS\n- tests live under test/\n\n## RULES\n-\n\n## GOTCHAS\n[One per line]\n")]
    (is (= {"FACTS" ["tests live under test/"]} s))))

(deftest sections-map-to-kinds-and-gotchas-are-rules-not-episodes
  ;; `find | head is refused here` holds next time; it is not a thing that
  ;; happened once.
  (let [s (reflect/parse-sections
           "## OVERVIEW\nA calculator library.\n## FACTS\nbuilt with deps.edn\n## GOTCHAS\n`find | head` is refused\n")
        written (reflect/record! @conn {:run-id "r1"} s)
        kinds (into {} (map (juxt :id (comp :kind #(knowledge/get-by-id @conn %) :id))) written)]
    (is (= 3 (count written)))
    (is (= #{"overview" "semantic" "procedural"} (set (vals kinds))))))

(deftest there-is-at-most-one-overview
  ;; It is the orientation note, not a pile of them — capped by construction
  ;; through a fixed pattern key rather than by asking the model nicely.
  (reflect/record! @conn {:run-id "r1"} {"OVERVIEW" ["A calculator library."]})
  (reflect/record! @conn {:run-id "r2"} {"OVERVIEW" ["Actually an expression evaluator."]})
  (is (= 1 (count (filter #(= "overview" (:kind %)) (knowledge/recent @conn 20))))))

(deftest the-same-fact-twice-is-corroborated-not-duplicated
  (reflect/record! @conn {:run-id "r1"} {"FACTS" ["tests live under test/"]})
  (reflect/record! @conn {:run-id "r2"} {"FACTS" ["tests live under test/"]})
  (let [rows (filter #(= "semantic" (:kind %)) (knowledge/recent @conn 20))]
    (is (= 1 (count rows)))
    (is (= 2 (:corroborations (first rows))))))

(deftest flagging-a-stale-memory-counts-against-it
  ;; The implementor is the only role positioned to notice that a memory it was
  ;; handed is wrong, so its word counts against that memory's record.
  (let [id (knowledge/remember! @conn {:content "tests live under spec/" :kind "semantic"})]
    (reflect/record! @conn {:run-id "r1" :shown #{id}}
                     {"WRONG" [(str id " — the tests moved to test/")]})
    (is (= 1 (:failure_count (knowledge/get-by-id @conn id))))))

(deftest a-correction-against-a-memory-the-branch-never-saw-counts-for-nothing
  ;; karamazov-4dn8.2. The reflector was handed knowledge/standing recomputed
  ;; at reflection time, not what the branch read, so a WRONG could land on a
  ;; row the branch never saw.
  (let [id (knowledge/remember! @conn {:content "tests live under spec/" :kind "semantic"})]
    (reflect/record! @conn {:run-id "r1" :shown #{}}
                     {"WRONG" [(str id " — the tests moved to test/")]})
    (is (zero? (or (:failure_count (knowledge/get-by-id @conn id)) 0)))))

(deftest the-reflector-is-shown-what-the-branch-was-shown
  (let [c @conn
        rid (samizdat.store.runs/start-run! c {:problem "p"})
        seen (knowledge/remember! c {:content "build with jolt bin" :kind "semantic"})
        unseen (knowledge/remember! c {:content "the overview of everything" :kind "overview"})
        sent (atom nil)]
    (samizdat.store.journal/note! c rid :exposed {:branch-id "T0" :turn 2
                                                  :data {:via "breadcrumb" :ids [seen]}})
    (with-redefs [samizdat.llm.client/chat (fn [_ _ msgs & _]
                                             (reset! sent (:content (second msgs)))
                                             {:content "## FACTS\n- x"})]
      (reflect/distil-task! {:conn c :run-id rid} {:id "T0" :problem "p"}))
    (is (str/includes? (str @sent) "build with jolt bin"))
    (is (not (str/includes? (str @sent) "the overview of everything"))
        "standing, recomputed, is not what the branch read")))

(deftest a-correction-naming-an-unknown-id-is-ignored
  (is (some? (reflect/record! @conn {:run-id "r1"} {"WRONG" ["k-nosuch — invented"]}))))

(deftest the-overview-is-restated-not-agreed-with
  ;; The overview describes a moving target — what the codebase IS — so a run
  ;; that adds a namespace makes the previous wording wrong without making the
  ;; memory wrong to hold. Live: run one wrote "nothing is implemented yet",
  ;; run two created src/todomvc/db.clj, corroborated that description rather
  ;; than correcting it, and then flagged the memory it had just agreed with
  ;; as WRONG in the same breath.
  (let [conn (db/open! ":memory:")]
    (reflect/record! conn {:run-id "r1"}
                     {"OVERVIEW" ["nothing is implemented yet"]
                      "FACTS" ["tests run with jolt -M:test"]})
    (reflect/record! conn {:run-id "r2"}
                     {"OVERVIEW" ["src/todomvc/db.clj holds the storage layer"]
                      "FACTS" ["tests run with jolt -M:test"]})
    (let [rows (knowledge/recent conn 20)
          overviews (filter #(= "overview" (:kind %)) rows)
          facts (filter #(= "semantic" (:kind %)) rows)]
      (testing "still exactly one overview, and it says what is true now"
        (is (= 1 (count overviews)))
        (is (= "src/todomvc/db.clj holds the storage layer" (:content (first overviews)))))
      (testing "restating does not cost it its record — the id and count survive"
        (is (= 2 (:corroborations (first overviews)))))
      (testing "a standing fact still corroborates rather than being rewritten"
        (is (= 1 (count facts)))
        (is (= 2 (:corroborations (first facts))))))))

(deftest revised-memories-become-the-reflectors-examples
  ;; karamazov-4dn8.6: ReHarness writes new reflections with revised ones as
  ;; demonstrations. A memory that was revised against its source and has not
  ;; failed since is the shape a reflection should take.
  (let [c @conn
        old (knowledge/remember! c {:content "build with uberjar" :kind "semantic"})
        new (knowledge/restate! c old "build the binary with `jolt bin`; it writes ./app"
                                {:reason "revised"})
        _ (knowledge/remember! c {:content "never revised" :kind "semantic"})]
    (is (= [new] (mapv :id (knowledge/revised c 3))))
    (is (empty? (knowledge/revised c 0)) "none when the policy asks for none")
    (let [p (reflect/build-prompt {:problem "p" :turns [] :memories []
                                   :examples (knowledge/revised c 3)})]
      (is (str/includes? p "jolt bin"))
      (is (not (str/includes? p "never revised"))))))
