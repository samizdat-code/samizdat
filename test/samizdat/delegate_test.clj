;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.delegate-test
  "The `delegate` tool (karamazov-k7gl.9): a child branch on the parent's run,
  run in the foreground to its answer, or in the background with its result
  delivered to the parent's mailbox; status, cancel, the depth limit, and a
  parent run ending taking its unfinished children down."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.agent.delegate :as delegate]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.select :as select]
            [samizdat.agent.tools :as tools]
            [samizdat.cancel :as cancel]
            [samizdat.cells :as cells]
            [samizdat.fake-done :as fake-done]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.workflow :as workflow]))

(use-fixtures :once (fn [f] (cells/load-cells!)
                      (with-redefs [select/triage! (constantly nil)] (f))))

(defn- fence [m]
  {:content (str "```tool-call\n" (json/write-str m) "\n```")
   :finish-reason "stop"
   :usage {:prompt-tokens 100 :completion-tokens 10 :total-tokens 110}})

(defn- done [answer]
  (fence {:name "done" :args {:answer answer
                              :checklist (json/read-str fake-done/checklist-json)}}))

(defn- child? [messages]
  (str/includes? (str (:content (first messages))) "delegated sub-agent"))

(defn- turns-of [c run-id bid]
  (journal/branch-turns c run-id bid))

(defn- delegate-result [c run-id]
  (some #(when (= "delegate" (:tool_name %)) (:result %)) (turns-of c run-id "B1")))

(defn- scripted-parent
  "A chat fn: the child replies with `child-fn`, the parent works through
  `parent` (a vector of fns of the conn-free messages, one per turn)."
  [child-fn parent]
  (let [n (atom -1)]
    (fn [_ _ messages & _]
      (if (child? messages)
        (child-fn messages)
        (let [i (swap! n inc)]
          ((nth parent i (constantly (done "the widgets count is 3"))) messages))))))

(defn- run! [c chat]
  (with-redefs [llm/chat chat]
    (workflow/run! {:conn c :config {:run {}}
                    :llm-adapter :a :llm-config {:max-tokens 16384}
                    :problem "count the widgets for me" :max-turns 8})))

(deftest the-catalogue-documents-it-and-the-child-role-has-no-ask
  (is (some #{"delegate"} (tools/tool-names)))
  (is (roles/may-use? :delegate "done"))
  (is (not (roles/may-use? :delegate "ask_human")))
  (is (not (roles/may-use? :delegate "split")))
  (is (roles/may-use? :implementor "delegate")))

(deftest a-foreground-child-answers-in-the-same-call
  (let [c (db/open! ":memory:")
        r (run! c (scripted-parent
                   (fn [_] (done "there are 3 widgets in src/w.clj"))
                   [(fn [_] (fence {:name "delegate"
                                    :args {:request "find how many widgets there are"}}))]))
        rid (:run-id r)
        res (delegate-result c rid)]
    (is (= :completed (:status r)))
    (testing "the parent's call returned the child's answer with provenance"
      (is (str/includes? res "there are 3 widgets"))
      (is (str/includes? res "D1"))
      (is (re-find #"turns?\D*1|1\D*turns?" res)))
    (testing "the child ran as its own branch of the same run, under the delegate role"
      (let [b (runs/get-branch c rid "D1")]
        (is (= "B1" (:parent_id b)))
        (is (= "delegate" (:role b))))
      (is (= ["done"] (mapv :tool_name (turns-of c rid "D1")))))
    (testing "the child's usage is the parent run's usage"
      (let [u (journal/run-usage c rid)
            cu (journal/run-usage c rid "D1")]
        (is (= 1 (:turns cu)))
        (is (= (* 110 (+ (:turns cu) (:side-calls cu))) (:total-tokens cu)))
        (is (= (* 110 (+ (:turns u) (:side-calls u))) (:total-tokens u)))
        (is (< (:total-tokens cu) (:total-tokens u)))))))

(deftest a-child-cannot-delegate-past-the-depth-limit
  (let [c (db/open! ":memory:")
        max-depth (:max-depth (gates/threshold :delegate))
        r (run! c (scripted-parent
                   (let [k (atom 0)]
                     (fn [_] (if (zero? (dec (swap! k inc)))
                               (fence {:name "delegate" :args {:request "go deeper"}})
                               (done "could not go deeper"))))
                   [(fn [_] (fence {:name "delegate" :args {:request "look into it"}}))]))
        rid (:run-id r)]
    (is (= 1 max-depth))
    (is (nil? (runs/get-branch c rid "D2")) "no grandchild was opened")
    (let [refused (first (turns-of c rid "D1"))]
      (is (= "delegate" (:tool_name refused)))
      (is (= "mechanics" (:category refused))))
    (is (str/includes? (delegate-result c rid) "could not go deeper"))))

(defn- wait-for [pred ms]
  (loop [left ms]
    (cond (pred) true
          (neg? left) false
          :else (do (Thread/sleep 25) (recur (- left 25))))))

(deftest a-background-child-reports-to-the-mailbox
  (let [c (db/open! ":memory:")
        inbox-seen (atom nil)
        r (run! c (scripted-parent
                   (fn [_] (done "widgets: 3"))
                   [(fn [_] (fence {:name "delegate"
                                    :args {:request "count widgets" :background true}}))
                    (fn [_]
                      ;; The parent's next turn waits for the delivery.
                      (wait-for #(seq (db/fetch c ["SELECT * FROM messages WHERE to_branch = 'B1'"]))
                                10000)
                      (fence {:name "message" :args {:action "inbox"}}))
                    (fn [messages]
                      (reset! inbox-seen (:content (last messages)))
                      (done "the widgets count is 3"))]))
        rid (:run-id r)]
    (is (= :completed (:status r)))
    (testing "the call returned a handle at once"
      (is (str/includes? (delegate-result c rid) "D1")))
    (testing "the result arrived in the parent's mailbox from the child"
      (let [m (first (db/fetch c ["SELECT * FROM messages WHERE to_branch = 'B1'"]))]
        (is (= "D1" (:from_branch m)))
        (is (str/includes? (:body m) "widgets: 3"))))
    (is (str/includes? (str @inbox-seen) "widgets: 3"))
    (is (= "done" (:status (runs/get-branch c rid "D1"))))
    (is (nil? (delegate/child rid "D1")) "the run's books went with it")))

(defn- hangs
  "A child that never answers: parks until cancelled."
  [_]
  (cancel/sleep! 600000)
  (done "never"))

(deftest status-and-cancel-reach-a-background-child
  (let [c (db/open! ":memory:")
        r (run! c (scripted-parent
                   hangs
                   [(fn [_] (fence {:name "delegate"
                                    :args {:request "a long job" :background true}}))
                    (fn [_] (fence {:name "delegate" :args {:status "D1"}}))
                    (fn [_] (fence {:name "delegate" :args {:cancel "D1"}}))]))
        rid (:run-id r)
        results (->> (turns-of c rid "B1")
                     (filter #(= "delegate" (:tool_name %)))
                     (mapv :result))]
    (is (= 3 (count results)))
    (is (str/includes? (nth results 1) "running"))
    (is (str/includes? (nth results 2) "Cancelled sub-agent D1"))
    (is (wait-for #(= "abandoned" (:status (runs/get-branch c rid "D1"))) 5000))))

(deftest a-parent-run-ending-cancels-its-unfinished-children
  (let [c (db/open! ":memory:")
        r (run! c (scripted-parent
                   hangs
                   [(fn [_] (fence {:name "delegate"
                                    :args {:request "a long job" :background true}}))]))
        rid (:run-id r)]
    (is (= :completed (:status r)))
    (is (wait-for #(= "abandoned" (:status (runs/get-branch c rid "D1"))) 5000)
        "the child's branch was closed when the run ended")))

(deftest unknown-ids-workflows-and-models-are-refused-as-mechanics
  (let [ctx {:branch {:id "B1"} :run-id "r-none" :tool-name "delegate"}
        call (fn [args] (tools/run-tool (assoc ctx :args args)))]
    (is (= :mechanics (:category (call {}))))
    (is (= :mechanics (:category (call {:status "D9"}))))
    (is (= :mechanics (:category (call {:cancel "D9"}))))
    (is (= :mechanics (:category (call {:request "x" :workflow "feature"}))))))

(deftest ids-are-the-next-free-d-number
  (is (= "D1" (delegate/next-id [])))
  (is (= "D3" (delegate/next-id ["B1" "D1" "D2" "W0"]))))
