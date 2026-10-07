;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.callable-test
  "Callable cells: library cells a branch calls from eval, with their schemas
  enforced and tools reached only through the runner it was handed
  (karamazov-xn0h.8)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [mycelium.cell :as cell]
            [samizdat.callable :as callable]
            [samizdat.cells :as cells]))

(def ^:private src
  "(ns lex.orders
  (:require [mycelium.cell :as cell]
            [samizdat.callable :as callable]))

(cell/defcell :lex/line-count
  {:doc \"How many lines a file has.\"
   :callable true
   :effects [:fs]
   :input [:map [:path :string]]
   :output [:map [:lines :int]]
   :examples [{:in {:path \"a.clj\"} :out {:lines 2}}]}
  (fn [_ {:keys [path]}]
    {:lines (count (clojure.string/split-lines (callable/tool \"read_file\" {:path path})))}))

(cell/defcell :lex/not-callable
  {:doc \"A workflow cell.\" :pure true}
  (fn [_ d] d))
")

(use-fixtures :each (fn [f] (let [snap (cell/registry-snapshot)]
                              (try (cells/eval-candidate! src) (f)
                                   (finally (cell/registry-restore! snap))))))

(defn- runner [seen]
  (fn [tool args] (swap! seen conj [tool args]) "line one\nline two"))

(deftest only-cells-marked-callable-are-listed
  (let [ids (set (map :id (callable/cells)))]
    (is (contains? ids :lex/line-count))
    (is (not (contains? ids :lex/not-callable))))
  (is (= "How many lines a file has." (:doc (first (filter #(= :lex/line-count (:id %)) (callable/cells)))))))

(deftest a-call-runs-the-cell-with-the-runner-it-was-handed
  (let [seen (atom [])]
    (is (= {:value {:lines 2}} (callable/invoke! :lex/line-count {:path "a.clj"} (runner seen))))
    (is (= [["read_file" {:path "a.clj"}]] @seen))))

(deftest the-schemas-are-the-cells-opinions
  (testing "input that does not fit is refused before the cell runs"
    (let [seen (atom [])
          r (callable/invoke! :lex/line-count {:path 7} (runner seen))]
      (is (:error r))
      (is (empty? @seen))))
  (testing "output that does not fit is an error, not a value"
    (let [r (callable/invoke! :lex/line-count {:path "a"} (fn [_ _] nil))]
      (is (:error r)))))

(deftest a-workflow-cell-cannot-be-called
  (is (:error (callable/invoke! :lex/not-callable {} (runner (atom []))))))

(deftest a-tool-refused-fails-the-cell
  (let [r (callable/invoke! :lex/line-count {:path "a"}
                            (fn [_ _] (throw (ex-info "not in this phase" {}))))]
    (is (str/includes? (:error r) "not in this phase"))))

(deftest examples-are-checked-when-present
  (is (nil? (callable/example-problem :lex/line-count (runner (atom [])))))
  (is (some? (callable/example-problem :lex/line-count (fn [_ _] "just one line"))))
  (is (nil? (callable/example-problem :lex/not-callable (runner (atom []))))
      "no examples, nothing to check"))

(deftest reaching-a-tool-is-an-effect-the-mark-must-cover
  (let [catalog ((requiring-resolve 'samizdat.agent.gates/threshold) :effect-symbols)
        lying (str/replace src ":effects [:fs]" ":pure true")]
    (is (empty? (filter #(= :lex/line-count (:id %)) (cells/effect-problems src catalog))))
    (is (= #{:fs} (:missing (first (filter #(= :lex/line-count (:id %))
                                           (cells/effect-problems lying catalog))))))))
