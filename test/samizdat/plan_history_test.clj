;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.plan-history-test
  "Plan snapshots in the journal (karamazov-k7gl.2): every plan change is an
  event carrying the whole plan and its delta, the model is shown the delta, a
  fold carries the latest plan apart from the summary, a resume restores it,
  and the API serves the plan with its history."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; the java.time host shim, before data.json
            [jolt.time]
            [clojure.data.json :as json]
            [mycelium.cell :as cell]
            [samizdat.agent.compaction :as cmp]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.plans :as plans]
            [samizdat.agent.resume :as resume]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.api.runs :as api-runs]
            [samizdat.cells :as cells]
            [samizdat.server :as server]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.system :as system]))

(defmacro ^:private with-db [[binding] & body]
  `(let [~binding (db/open! ":memory:")]
     (try ~@body (finally (db/close ~binding)))))

;; --- the delta ---------------------------------------------------------------

(deftest a-delta-names-what-was-added-removed-and-changed
  (let [old {:files ["src/a.clj" "test/a_test.clj"] :tests ["test/a_test.clj"]
             :goal "fix a" :rfc nil :checklist ["x is 1"]}
        new {:files ["src/b.clj" "test/a_test.clj"] :tests ["test/a_test.clj"]
             :goal "fix b" :rfc nil :checklist ["x is 1" "y is 2"]}
        d (plans/delta old new)]
    (is (= {:files ["src/b.clj"] :checklist ["y is 2"]} (:added d)))
    (is (= {:files ["src/a.clj"]} (:removed d)))
    (is (= {:goal {:from "fix a" :to "fix b"}} (:changed d)))
    (is (not (plans/delta-empty? d))))
  (testing "the same plan twice is an empty delta"
    (let [p {:files ["a"] :tests [] :goal "g" :checklist []}]
      (is (plans/delta-empty? (plans/delta p p)))
      (is (= {} (plans/delta p p)))))
  (testing "a first plan is all additions"
    (is (= {:added {:files ["a"]} :changed {:goal {:from nil :to "g"}}}
           (plans/delta nil {:files ["a"] :tests [] :goal "g" :checklist []})))))

(deftest a-snapshot-is-the-plan-and-its-checklist
  (let [b (-> (state/new-branch {:id "B1" :problem "p"})
              (state/declare-plan {:files ["src/a.clj"] :tests ["test/a_test.clj"] :goal "g"})
              (state/declare-checklist ["one"]))]
    (is (= {:files ["src/a.clj" "test/a_test.clj"] :tests ["test/a_test.clj"]
            :goal "g" :rfc nil :checklist ["one"]}
           (plans/snapshot b)))
    (is (nil? (plans/snapshot (state/new-branch {:id "B2" :problem "p"}))))))

(deftest a-restored-plan-is-the-plan-it-was
  (let [b (-> (state/new-branch {:id "B1" :problem "p"})
              (state/declare-plan {:files ["src/a.clj"] :tests ["test/a_test.clj"] :goal "g"})
              (state/declare-checklist ["one"]))
        snap (plans/snapshot b)
        r (plans/restore (state/new-branch {:id "B1" :problem "p"}) snap #{"src/a.clj"} false)]
    (is (= (state/plan b) (state/plan r)))
    (is (= ["one"] (state/checklist r)))
    (is (= ["test/a_test.clj"] (state/unwritten r)) "the write replayed discharges its file")
    (is (= r (plans/restore r nil #{} false)) "no snapshot, no change")))

;; --- the journal ----------------------------------------------------------------

(deftest plan-snapshots-are-journalled-and-read-back
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (is (nil? (journal/latest-plan c rid "B1")))
      (journal/plan-snapshot! c rid "B1" 2 {:plan {:files ["a"]} :delta {:added {:files ["a"]}}})
      (journal/plan-snapshot! c rid "B1" 5 {:plan {:files ["b"]} :delta {:added {:files ["b"]}
                                                                         :removed {:files ["a"]}}})
      (journal/plan-snapshot! c rid "B2" 6 {:plan {:files ["z"]} :delta {}})
      (is (= {:files ["b"]} (:plan (journal/latest-plan c rid "B1"))))
      (is (= 5 (:turn (journal/latest-plan c rid "B1"))))
      (let [h (journal/plan-history c rid "B1")]
        (is (= [2 5] (mapv :turn h)) "oldest first, this branch only")
        (is (= {:removed {:files ["a"]} :added {:files ["b"]}} (:delta (second h))))))))

;; --- the tool ---------------------------------------------------------------------

(deftest the-plan-tool-journals-every-change-and-shows-the-delta
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})
          _ (runs/open-branch! c rid {:branch-id "B1"})
          call (fn [branch turn args]
                 (tools/run-tool {:branch branch :tool-name "plan" :turn turn
                                  :conn c :run-id rid :args args}))
          r1 (call (state/new-branch {:id "B1" :problem "p"}) 1
                   {:files ["src/a.clj"] :goal "fix a"})
          r2 (call (:branch r1) 3 {:files ["src/b.clj"] :goal "fix b"})
          h (journal/plan-history c rid "B1")]
      (is (= [1 3] (mapv :turn h)))
      (is (= ["src/b.clj"] (get-in (last h) [:plan :files])) "the full plan rides every event")
      (is (= {:files ["src/b.clj"]} (get-in (last h) [:delta :added])))
      (is (= {:files ["src/a.clj"]} (get-in (last h) [:delta :removed])))
      (is (str/includes? (str (:result r2)) "src/a.clj") "the model sees what left the plan")
      (is (str/includes? (str (:result r2)) "fix b"))
      (testing "an unchanged re-plan journals nothing new"
        (call (:branch r2) 4 {:files ["src/b.clj"] :goal "fix b"})
        (is (= 2 (count (journal/plan-history c rid "B1")))))
      (testing "a withdrawal is a change too"
        (call (:branch r2) 5 {})
        (is (= [] (get-in (last (journal/plan-history c rid "B1")) [:plan :files])))))))

(deftest the-plan-tool-runs-without-a-journal
  (let [r (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                           :tool-name "plan" :turn 1 :args {:files ["src/a.clj"]}})]
    (is (= ["src/a.clj"] (:files (state/plan (:branch r)))))))

;; --- the fold ----------------------------------------------------------------------

(deftest carrying-a-message-replaces-the-one-carried-before
  (let [msgs [{:role "system" :content "s"}
              {:role "user" :content "old plan" :carried :plan}
              {:role "user" :content "u"}]
        out (cmp/carry msgs 3 {:role "user" :content "new plan"} :plan)]
    (is (= ["s" "u" "new plan"] (mapv :content out)))
    (is (= :plan (:carried (last out))))
    (is (= ["s" "new plan" "u"]
           (mapv :content (cmp/carry msgs 1 {:role "user" :content "new plan"} :plan)))
        "the index is the position in the messages as given")))

(defn- conversation []
  (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
        (mapcat (fn [t]
                  [{:role "assistant" :content (str "call " t) :turn t}
                   {:role "user" :content (apply str (repeat 3000 "x")) :turn t}])
                (range 1 9))))

(defn- fold [strategy carry? latest]
  (with-redefs [gates/threshold (let [orig gates/threshold]
                                  (fn [k]
                                    (if (= k :compaction)
                                      (assoc (orig k) :strategy strategy :carry-plan? carry?)
                                      (orig k))))
                samizdat.llm.client/chat (fn [& _] (throw (ex-info "no model" {})))
                journal/note! (fn [& _] nil)
                journal/latest-plan (fn [_ _ b] (when (= "B1" b) latest))]
    ((:handler (cell/get-cell! :compaction/fold))
     {:conn ::conn :run-id "r1" :llm-adapter ::adapter
      :llm-config {:context-window 128000}}
     {:compaction/tier :fold :compaction/before 999999
      :branch {:id "B1" :messages (conversation)}})))

(deftest a-fold-carries-the-latest-plan-apart-from-the-summary
  (cells/load-cells!)
  (let [latest {:turn 3 :plan {:files ["src/a.clj"] :tests [] :goal "fix a" :checklist ["x is 1"]}}]
    (doseq [strategy [:evict :edit]]
      (testing (str strategy)
        (let [msgs (get-in (fold strategy true latest) [:branch :messages])
              carried (filter :carried msgs)]
          (is (= 1 (count carried)) "exactly one plan block")
          (is (str/includes? (:content (first carried)) "src/a.clj"))
          (is (str/includes? (:content (first carried)) "x is 1"))
          (is (= "sys" (:content (first msgs))) "the head survives the fold"))))
    (testing "the policy can turn it off"
      (is (not-any? :carried (get-in (fold :evict false latest) [:branch :messages]))))
    (testing "no snapshot, no block"
      (is (not-any? :carried (get-in (fold :evict true nil) [:branch :messages]))))))

;; --- resume -------------------------------------------------------------------------

(deftest a-resumed-branch-comes-back-with-its-latest-plan
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})
          _ (runs/open-branch! c rid {:branch-id "B1"})
          r1 (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                              :tool-name "plan" :turn 1 :conn c :run-id rid
                              :args {:files ["src/a.clj" "src/b.clj"] :goal "g"
                                     :checklist ["one"]}})
          _ (journal/record-turn! c rid {:branch-id "B1" :turn 2 :tool-name "write_file"
                                         :args {:path "src/a.clj" :content "x"}
                                         :result "ok" :category :success})
          run (runs/get-run c rid)
          b (#'resume/rebuild-branch run (runs/get-branch c rid "B1")
                                     (group-by :branch_id (journal/turns c rid)) {} {}
                                     10 (gates/storm-policy) nil
                                     {"B1" (journal/latest-plan c rid "B1")})]
      (is (= (state/plan (:branch r1)) (state/plan b)))
      (is (= ["one"] (state/checklist b)))
      (is (= ["src/b.clj"] (state/unwritten b)) "a write after the plan still discharges its file"))))

;; --- the API -------------------------------------------------------------------------

(deftest the-api-serves-a-branch-plan-with-its-history
  (with-db [c]
    (let [rid (runs/start-run! c {:problem "p" :max-turns 10 :beam-width 1})]
      (runs/open-branch! c rid {:branch-id "B1"})
      (is (nil? (api-runs/branch-plan c rid "nope")) "an unknown branch is nil, a 404")
      (is (= {:run_id rid :branch_id "B1" :plan nil :history []}
             (api-runs/branch-plan c rid "B1")))
      (journal/plan-snapshot! c rid "B1" 2 {:plan {:files ["a"]} :delta {:added {:files ["a"]}}})
      (let [r (api-runs/branch-plan c rid "B1")]
        (is (= {:files ["a"]} (:plan r)))
        (is (= 1 (count (:history r))))
        (is (= 2 (:turn (first (:history r))))))))
  (with-redefs [system/conn (constantly nil)
                api-runs/branch-plan (fn [_ id b] (when (= "B2" b) {:run_id id :branch_id b}))]
    (let [call (fn [uri] (let [r (server/handler {:request-method :get :uri uri})]
                           (assoc r :json (some-> (:body r) (json/read-str :key-fn keyword)))))]
      (is (= {:run_id "r1" :branch_id "B2"} (:json (call "/v1/runs/r1/branches/B2/plan"))))
      (is (= 404 (:status (call "/v1/runs/r1/branches/B3/plan")))))))
