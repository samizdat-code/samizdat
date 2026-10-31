(ns samizdat.accounting-test
  "Task 1 (karamazov-a2y3.1): pure complete-request accounting.

  Pins the estimate/admit seam from the 2026-10-30 AgenC context-efficiency
  design: component-wise input accounting, reserved output, total = input +
  reserve, unknown windows never admitted, and unknown/non-ASCII content never
  silently counting as zero."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.llm.accounting :as accounting]))

(def policy
  {:chars-per-token 4
   :non-ascii-chars-per-token 2
   :ascii-max-codepoint 127
   :min-component-tokens 1
   :confidence :low
   :calibration-version "uncalibrated-1"
   :framing {:messages 8 :tools 12 :grammar 16 :prefill 4 :tool-choice 4}})

(def request
  {:provider :anthropic
   :model "claude-3-5-sonnet"
   :context-window 200000
   :max-tokens 100
   :tool-choice :auto
   :grammar "root ::= \"hello\" (\" \" word)*\nword ::= [a-z]+"
   :prefill "Here is the plan:"
   :tools [{:name "grep"
            :description "Search project source for a regex"
            :input-schema {:type "object" :properties {:pattern {:type "string"}}}}
           {:name "read_file"
            :description "Read a file by path"
            :input_schema {:type "object" :properties {:path {:type "string"}}}}]
   :messages [{:role "user"
               :content "Find the accounting seam in this repository and report its cost."}
              {:role "assistant"
               :content [{:type "text"
                          :text "Searching — naïve café ☺ results follow"}]}]})

(deftest messages-tools-options-and-output-are-all-accounted
  (testing "every present request component costs at least one token"
    (let [est (accounting/estimate request policy)]
      (doseq [k [:messages :tools :grammar :prefill :tool-choice]]
        (is (pos? (get-in est [:components k]))
            (str "component " k " must be positive")))
      (is (= 100 (:reserved-output-tokens est)))
      (is (= (+ (:input-tokens est) (:reserved-output-tokens est))
             (:total-tokens est)))
      (is (= (:input-tokens est) (reduce + 0 (vals (:components est)))))
      (is (pos? (:input-tokens est)))
      (is (= :conservative-chars (:source est)))
      (is (string? (str (:calibration-version est))))
      (is (true? (:admissible? (accounting/admit est)))))))

(deftest unknown-window-is-not-an-admission
  (testing "a request without a known context window is never admitted"
    (let [est (accounting/estimate (dissoc request :context-window) policy)
          adm (accounting/admit est)]
      (is (= {:admissible? false :reason :unknown-context-window}
             (select-keys adm [:admissible? :reason])))
      (is (false? (:admissible? (accounting/admit
                                  (accounting/estimate
                                   (assoc request :context-window 0) policy))))))))

(deftest block-content-and-unicode-are-bounded-conservatively
  (testing "block content and non-ASCII text never produce zero components"
    (let [est (accounting/estimate request policy)]
      (is (every? pos? (vals (:components est)))
          "no zero component results")
      (is (pos? (get-in est [:components :messages]))))
    (testing "non-ASCII is charged at the tighter ratio, not the ASCII one"
      (let [msg {:role "user" :content (apply str (repeat 40 "é"))}
            est (accounting/estimate (assoc request :messages [msg]) policy)
            tokens (get-in est [:components :messages])]
        (is (> tokens 20))))))

(deftest component-totals-match-input-total
  (testing "input-tokens is exactly the sum of the component map"
    (let [est (accounting/estimate request policy)]
      (is (= (:input-tokens est) (reduce + 0 (vals (:components est)))))
      (is (= (:total-tokens est)
             (+ (reduce + 0 (vals (:components est)))
                (:reserved-output-tokens est)))))))

(deftest nontext-blocks-and-unknown-tool-content-are-fully-accounted
  (testing "nontext block estimates keep growing beyond the serialization inspection cap"
    (let [bounded-policy (assoc policy :max-serialized-chars 32)
          base (assoc request
                      :messages [{:role "user" :content ""}]
                      :tools [] :grammar nil :prefill nil :tool-choice nil)
          small (assoc-in base [:messages 0 :content]
                          [{:type "image" :data (apply str (repeat 40 "x"))}])
          large (assoc-in base [:messages 0 :content]
                          [{:type "image" :data (apply str (repeat 4000 "x"))}])
          tokens #(get-in (accounting/estimate % bounded-policy)
                          [:components :messages])]
      (is (< (tokens base) (tokens small)))
      (is (< (tokens small) (tokens large)))))
  (testing "unknown tool keys keep affecting the estimate beyond the inspection cap"
    (let [bounded-policy (assoc policy :max-serialized-chars 256)
          tool {:name "opaque" :description "Unknown tool payload"
                :input-schema {:type "object"}}
          base (assoc request :messages [] :tools [tool]
                      :grammar nil :prefill nil :tool-choice nil)
          small (assoc base :tools [(assoc tool :opaque (apply str (repeat 200 "x")))])
          large (assoc base :tools [(assoc tool :opaque (apply str (repeat 4000 "x")))])
          tokens #(get-in (accounting/estimate % bounded-policy)
                          [:components :tools])]
      (is (< (tokens base) (tokens small)))
      (is (< (tokens small) (tokens large))))))

(deftest native-tool-parameters-are-accounted
  (testing "a native tool's :parameters schema affects its estimate"
    (let [empty-tool {:name "native" :description "Native schema"
                      :parameters {:type "object" :properties {}}}
          native-tool (assoc-in empty-tool [:parameters :properties :path]
                                {:type "string"
                                 :description (apply str (repeat 200 "p"))})
          tool-request (assoc request :messages [] :grammar nil :prefill nil
                              :tool-choice nil)
          tokens #(get-in (accounting/estimate (assoc tool-request :tools [%]) policy)
                          [:components :tools])]
      (is (< (tokens empty-tool) (tokens native-tool))))))

(deftest admission-exact-fit-and-overflow
  (testing "exact fit is admitted and one token over is refused"
    (is (true? (:admissible? (accounting/admit
                              {:context-window 100 :total-tokens 100}))))
    (is (= {:admissible? false :reason :exceeds-context-window}
           (select-keys (accounting/admit
                         {:context-window 100 :total-tokens 101})
                        [:admissible? :reason])))))

(def calibration-policy
  (assoc policy
         :calibration-version "provider-usage-test-1"
         :calibrated-confidence :medium
         :calibration {:min-ratio 0.5
                       :max-ratio 3.0
                       :max-samples 2
                       :max-scopes 2}))

(deftest provider-usage-calibrates-undercounts-without-recording-prompt-text
  (accounting/reset-calibration!)
  (let [conservative (accounting/estimate request calibration-policy)
        actual (* 2 (:input-tokens conservative))
        reconciliation (accounting/record-usage!
                        calibration-policy conservative
                        {:prompt-tokens actual :completion-tokens 7})
        calibrated (accounting/estimate request calibration-policy)]
    (is (:recorded? reconciliation))
    (is (:undercount? reconciliation))
    (is (= 2.0 (:ratio reconciliation)))
    (is (= (* 2 (:input-tokens conservative)) (:input-tokens calibrated)))
    (is (= :provider-usage (:source calibrated)))
    (is (= :medium (:confidence calibrated)))
    (is (not (str/includes? (pr-str reconciliation)
                            "Find the accounting seam"))
        "reconciliation contains ratios and shape identity, never prompt text")
    (accounting/reset-calibration!)
    (is (= conservative (accounting/estimate request calibration-policy))
        "reset deterministically restores the conservative estimate")))

(deftest missing-or-partial-usage-leaves-the-estimate-conservative
  (accounting/reset-calibration!)
  (let [conservative (accounting/estimate request calibration-policy)]
    (doseq [usage [nil {} {:completion-tokens 9} {:prompt-tokens 0}]]
      (let [reconciliation (accounting/record-usage!
                            calibration-policy conservative usage)]
        (is (false? (:recorded? reconciliation)))
        (is (= conservative (accounting/estimate request calibration-policy)))))))

(deftest calibration-is-bounded-and-partitioned-by-request-shape
  (accounting/reset-calibration!)
  (let [plain-request (assoc request :tools [] :grammar nil :prefill nil :tool-choice nil)
        plain (accounting/estimate plain-request calibration-policy)
        too-large (* 100 (:input-tokens plain))
        _ (accounting/record-usage! calibration-policy plain
                                    {:prompt-tokens too-large})
        capped (accounting/estimate plain-request calibration-policy)
        with-tools (accounting/estimate request calibration-policy)]
    (is (= (* 3 (:input-tokens plain)) (:input-tokens capped))
        "an extreme provider ratio is clamped to policy")
    (is (= :conservative-chars (:source with-tools))
        "another request shape cannot spend this shape's calibration")))

(deftest calibration-store-bounds-samples-and-scope-partitions
  (accounting/reset-calibration!)
  (let [base (accounting/estimate request calibration-policy)]
    (accounting/record-usage! calibration-policy base {:prompt-tokens (:input-tokens base)})
    (accounting/record-usage! calibration-policy base {:prompt-tokens (* 2 (:input-tokens base))})
    (accounting/record-usage! calibration-policy base {:prompt-tokens (* 3 (:input-tokens base))})
    (is (= (long (Math/ceil (* 2.5 (:input-tokens base))))
           (:input-tokens (accounting/estimate request calibration-policy)))
        "only the newest max-samples ratios contribute")
    (doseq [model ["other-1" "other-2"]]
      (let [r (assoc request :model model)
            e (accounting/estimate r calibration-policy)]
        (accounting/record-usage! calibration-policy e
                                  {:prompt-tokens (:input-tokens e)})))
    (is (= :conservative-chars
           (:source (accounting/estimate request calibration-policy)))
        "the oldest provider/model/shape partition is evicted at max-scopes")
    (accounting/reset-calibration!)
    (let [fresh (accounting/estimate request calibration-policy)]
      (accounting/record-usage! calibration-policy fresh
                                {:prompt-tokens (* 2 (:input-tokens fresh))})
      (is (= (* 2 (:input-tokens fresh))
             (:input-tokens (accounting/estimate request calibration-policy)))
          "reset also clears eviction history, making a replay deterministic"))))

(deftest calibration-version-isolates-incompatible-policy
  (accounting/reset-calibration!)
  (let [base (accounting/estimate request calibration-policy)
        next-policy (assoc calibration-policy :calibration-version "provider-usage-test-2")]
    (accounting/record-usage! calibration-policy base
                              {:prompt-tokens (* 2 (:input-tokens base))})
    (is (= :conservative-chars
           (:source (accounting/estimate request next-policy))))))

(deftest removing-calibration-policy-restores-the-conservative-estimate
  (accounting/reset-calibration!)
  (let [base (accounting/estimate request calibration-policy)
        uncalibrated-policy (dissoc calibration-policy :calibration)
        conservative (accounting/estimate request uncalibrated-policy)]
    (accounting/record-usage! calibration-policy base
                              {:prompt-tokens (* 2 (:input-tokens base))})
    (is (= conservative (accounting/estimate request uncalibrated-policy))
        "stored samples are inert without complete calibration bounds")))

(deftest repeated-responses-reconcile-against-the-conservative-base
  (accounting/reset-calibration!)
  (let [base (accounting/estimate request calibration-policy)
        observed (* 2 (:input-tokens base))
        estimates (loop [n 0
                         estimate base
                         seen []]
                    (if (= n 3)
                      seen
                      (let [_ (accounting/record-usage!
                               calibration-policy estimate
                               {:prompt-tokens observed :completion-tokens 1})
                            next-estimate (accounting/estimate request calibration-policy)]
                        (recur (inc n) next-estimate
                               (conj seen (:input-tokens next-estimate))))))]
    (is (= [observed observed observed] estimates)
        "constant provider usage must not oscillate as prepared estimates change")))