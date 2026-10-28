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
;; Inspection fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim, source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-inspect-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.edit-dsl :as edit-dsl]
            [samizdat.surgeon.forms :as forms]
            [samizdat.surgeon.inspect :as inspect]))

(def basic-source
  "(ns sample.core)\n\n;; Explain alpha.\n;; Preserve this context.\n(defn ^:private alpha [x]\n  ;; Keep the body comment.\n  (+ x\n     1))\n\n(comment\n  (def hidden :example))\n\n(defn omega []\n  :done)\n")

(def basic-hash
  "9201824c1011dde7b685deb144eb9347af0886d8828f769fb2cd4ac353092dbf")

(def alpha-source
  "(defn ^:private alpha [x]\n  ;; Keep the body comment.\n  (+ x\n     1))")

(def cljc-source
  "(ns sample.portable)\n#?(:clj\n   (defn shared [] :clj)\n   :cljs\n   (defn shared [] :cljs))\n#?@(:clj [(defn spliced [] :clj)]\n    :cljs [(defn spliced [] :cljs)])\n(defn common [] :both)\n")

(def lens-source
  "(ns demo.views)\n\n(defn other-pane []\n  (ds/post-action* \"/api/book/new-node\" :other))\n\n(defn book-workshop-pane [surface]\n  [:div.panel\n   [:button.secondary\n    {:data-star-on:click\n     (ds/post-action* \"/api/book/new-node\" surface)}\n    \"New\"]\n   ;; This sibling must survive inspection byte-for-byte.\n   [:p.note \"Keep me\"]])\n")

(def lens-hash
  "eddf33b51e6804ee5d70cec48361666d552ff2924170f184fbb0754df661d1a0")

(defn- no-source?
  [result]
  (not-any? #(and (map? %) (contains? % :source))
            (tree-seq coll? seq result)))

(deftest outline-source-is-pure-and-compact
  (let [result (inspect/outline-source basic-source "sample.clj")
        alpha (first (filter #(= 'alpha (:name %)) (:forms result)))]
    (is (= 'sample.core (:ns result)))
    (is (= "sample.clj" (:file result)))
    (is (= 14 (:lines result)))
    (is (= 2 (:form-count result)))
    (is (= '[alpha nil omega] (mapv :name (:forms result))))
    (is (= {:type 'defn :platforms [:clj] :line 5 :end-line 8
            :name 'alpha :args "[x]" :comment-start 3}
           alpha))
    (is (no-source? result))))

(deftest named-and-batched-form-reads-preserve-order-and-exact-source
  (testing "one name accepts a string and returns the common vector shape"
    (let [result (inspect/forms-source basic-source "sample.clj" {:form "alpha"})]
      (is (= basic-hash (:file-hash result)))
      (is (= ['alpha] (mapv :name (:forms result))))
      (is (= alpha-source (-> result :forms first :source)))
      (is (= 3 (-> result :forms first :comment-start)))))
  (testing "a batch preserves requested order rather than source order"
    (let [result (inspect/forms-source basic-source "sample.clj"
                                       {:forms ['omega "alpha"]})]
      (is (= basic-hash (:file-hash result)))
      (is (= ['omega 'alpha] (mapv :name (:forms result))))
      (is (= ["(defn omega []\n  :done)" alpha-source]
             (mapv :source (:forms result))))))
  (testing "reader syntax, metadata, comments, and strings are not canonicalized"
    (let [source "(ns exact)\n(defn exact [] ^:keep #(str \"#_not-a-discard\" %))\n"
          expected "(defn exact [] ^:keep #(str \"#_not-a-discard\" %))"
          result (inspect/forms-source source "exact.clj" {:form 'exact})]
      (is (= expected (-> result :forms first :source))))))

(deftest metadata-only-form-reads-omit-source-recursively
  (let [result (inspect/forms-source basic-source "sample.clj"
                                     {:forms ['alpha 'omega]
                                      :include-source? false})]
    (is (= basic-hash (:file-hash result)))
    (is (= ['alpha 'omega] (mapv :name (:forms result))))
    (is (= [[5 8] [13 14]] (mapv (juxt :line :end-line) (:forms result))))
    (is (no-source? result))))

(deftest line-and-literal-selectors-require-one-owner
  (doseq [line [3 5 7 8]]
    (let [result (inspect/forms-source basic-source "sample.clj" {:line line})]
      (is (= ['alpha] (mapv :name (:forms result))) (str "line " line))
      (is (= alpha-source (-> result :forms first :source)))))
  (testing "attached comments are searchable without becoming form source"
    (let [result (inspect/forms-source basic-source "sample.clj"
                                       {:contains "Preserve this context."})]
      (is (= ['alpha] (mapv :name (:forms result))))
      (is (= 1 (-> result :forms first :occurrence-count)))
      (is (= [{:line 4 :column 4}]
             (-> result :forms first :occurrences)))
      (is (= alpha-source (-> result :forms first :source)))))
  (testing "a line outside every form and absent literal refuse exactly"
    (is (= :line-not-in-form
           (:error-type (inspect/forms-source basic-source "sample.clj" {:line 2}))))
    (is (= :contains-not-found
           (:error-type (inspect/forms-source basic-source "sample.clj"
                                              {:contains "absent"})))))
  (testing "multiple containing forms are evidence, never an arbitrary choice"
    (let [source "(ns ambiguous)\n(defn alpha [] \"needle\")\n(defn beta [] \"needle\")\n"
          result (inspect/forms-source source "ambiguous.clj" {:contains "needle"})]
      (is (= :ambiguous-form (:error-type result)))
      (is (= 2 (:match-count result)))
      (is (= ['alpha 'beta] (mapv :name (:matches result))))
      (is (no-source? result)))))

(deftest missing-and-duplicate-owners-refuse-the-complete-read
  (testing "a missing owner returns source-free recovery evidence"
    (let [result (inspect/forms-source basic-source "sample.clj" {:form 'alpah})]
      (is (= :form-not-found (:error-type result)))
      (is (= basic-hash (:file-hash result)))
      (is (= ["alpha" "omega"] (:available-owners result)))
      (is (= "alpha" (get-in result [:selection-failures 0 :hypotheses 0 :owner])))
      (is (false? (get-in result [:selection-failures 0 :hypotheses 0 :authority])))
      (is (no-source? result))))
  (testing "one missing batch member suppresses every successful member"
    (let [result (inspect/forms-source basic-source "sample.clj"
                                       {:forms ['alpha 'missing]})]
      (is (= :batch-form-selection-failed (:error-type result)))
      (is (= ['missing] (mapv :form (:failures result))))
      (is (not (contains? result :forms)))
      (is (no-source? result))))
  (testing "duplicate names are never disambiguated by source position"
    (let [source "(ns duplicate)\n(defn same [] 1)\n(defn same [] 2)\n"
          result (inspect/forms-source source "duplicate.clj" {:form 'same})]
      (is (= :ambiguous-form (:error-type result)))
      (is (= 2 (:match-count result)))
      (is (= [2 3] (mapv :line (:matches result))))
      (is (no-source? result)))))

(deftest cljc-names-require-platform-disambiguation
  (let [ambiguous (inspect/forms-source cljc-source "sample.cljc" {:form 'shared})
        cljs-result (inspect/forms-source cljc-source "sample.cljc"
                                          {:form 'shared :platform :cljs})
        batch (inspect/forms-source cljc-source "sample.cljc"
                                    {:forms ['common 'spliced] :platform :clj})]
    (is (= :ambiguous-form (:error-type ambiguous)))
    (is (= [:clj :cljs]
           (get-in ambiguous [:remedies :select-platform :platforms])))
    (is (= "(defn shared [] :cljs)" (-> cljs-result :forms first :source)))
    (is (= [:cljs] (-> cljs-result :forms first :platforms)))
    (is (= ["(defn common [] :both)" "(defn spliced [] :clj)"]
           (mapv :source (:forms batch))))
    (is (= [[:clj :cljs] [:clj]] (mapv :platforms (:forms batch)))))
  (let [outline (inspect/outline-source cljc-source "sample.cljc")]
    (is (= ['shared 'shared 'spliced 'spliced 'common]
           (mapv :name (:forms outline))))
    (is (= [[:clj] [:cljs] [:clj] [:cljs] [:clj :cljs]]
           (mapv :platforms (:forms outline))))))

(deftest structural-match-treats-underscore-as-one-subtree
  (let [result (inspect/match-source
                 lens-source
                 {:inside 'book-workshop-pane
                  :match "(ds/post-action* \"/api/book/new-node\" _)"})
        match (first (:matches result))]
    (is (= lens-hash (:file-hash result)))
    (is (= 1 (:match-count result)))
    (is (= "book-workshop-pane" (:inside match)))
    (is (= "(ds/post-action* \"/api/book/new-node\" surface)" (:source match)))
    (is (= 10 (:line match)))
    (is (= [{:form 'book-workshop-pane}
            {:vector-tag :div.panel}
            {:vector-tag :button.secondary}
            {:attr :data-star-on:click}
            {:call 'ds/post-action*}]
           (:path match)))
    (is (integer? (-> match :address :preorder))))
  (testing "one wildcard does not absorb two subtrees"
    (let [source "(ns x)\n(defn f [] (loop [i 0] (if (< i 3) (recur (inc i)) i)))\n"]
      (is (zero? (:match-count
                   (inspect/match-source source {:inside 'f :match "(loop _)"}))))
      (is (= 1 (:match-count
                (inspect/match-source source {:inside 'f :match "(loop _ _)"})))))))

(deftest structural-match-refuses-missing-and-duplicate-inside-owners
  (let [source "(ns duplicate)\n(defn same [] (inc 1))\n(defn same [] (inc 2))\n"
        duplicate (inspect/match-source source {:inside 'same :match "(inc _)"})
        missing (inspect/match-source source {:inside 'absent :match "(inc _)"})]
    (is (= :ambiguous-inside (:error-type duplicate)))
    (is (= 2 (:owner-match-count duplicate)))
    (is (= [{:type 'defn :name 'same :line 2 :end-line 2}
            {:type 'defn :name 'same :line 3 :end-line 3}]
           (:candidates duplicate)))
    (is (zero? (:match-count duplicate)))
    (is (empty? (:matches duplicate)))
    (is (no-source? duplicate))
    (is (= :inside-not-found (:error-type missing)))
    (is (zero? (:match-count missing)))
    (is (empty? (:matches missing)))
    (is (no-source? missing))))

(deftest structural-match-ignores-legacy-global-project-aliases
  (let [source "(ns api)\n(defendpoint handle [] (inc 1))\n"
        selector {:inside 'handle :match "(inc _)"}
        baseline (inspect/match-source source selector)]
    (is (= :inside-not-found (:error-type baseline)))
    (with-redefs [forms/project-aliases
                  (atom {"defendpoint" {:kind :defn}})]
      (is (= baseline (inspect/match-source source selector))))))

(deftest selector-contract-and-invalid-source-return-data-without-prose
  (let [missing (inspect/forms-source basic-source "sample.clj" {})
        conflict (inspect/forms-source basic-source "sample.clj"
                                       {:form 'alpha :line 5})
        invalid-source (inspect/forms-source "(ns broken\n" "broken.clj"
                                             {:form 'broken})
        invalid-match (inspect/match-source lens-source {:match "(unclosed"})]
    (is (= :missing-selector (:error-type missing)))
    (is (= :conflicting-selectors (:error-type conflict)))
    (is (= :invalid-source (:error-type invalid-source)))
    (is (= :invalid-match (:error-type invalid-match)))
    (doseq [result [missing conflict invalid-source invalid-match]]
      (is (not (contains? result :error))))))

(def xray-source
  (str "(ns bench.xray)\n\n"
       "(def data\n"
       "  ^:private\n"
       "  {:xs [1 2]\n"
       "   ;; Keep the second vector distinct.\n"
       "   :ys [3 4]})\n\n"
       "(defn choose [x]\n"
       "  (case x\n"
       "    :a 1\n"
       "    :b 2))\n"))

(defn- xray
  ([expression] (xray expression {}))
  ([expression opts]
   (inspect/xray-source
     xray-source
     (merge {:file "xray.clj"
             :expression expression
             :max-source-chars 100000
             :max-query-steps 32
             :max-matches 100
             :max-result-chars 65536}
            opts))))

(deftest xray-literal-paths-select-forms-lines-and-initializers
  (let [adjacent (xray "(-> (form 'data) (match :ys) right)")
        initializer (xray "(-> (form 'data) initializer)")
        by-line (xray "(-> (line 11) (match :b) right)")]
    (is (= :literal (:mode adjacent)))
    (is (= "[3 4]" (get-in adjacent [:matches 0 :source])))
    (is (= [[:form 'data] [:find :ys] :right] (:query adjacent)))
    (is (= 3 (count (:trace adjacent))))
    (is (= {:xs [1 2] :ys [3 4]}
           (-> initializer :matches first :source z/of-string z/sexpr)))
    (is (= "2" (get-in by-line [:matches 0 :source])))
    (is (= "choose" (get-in by-line [:matches 0 :inside])))))

(deftest xray-spans-and-partitions-become-stable-value-vectors
  (let [span (xray (str "(-> (form 'choose) (match :a) (span 2) "
                        "(expect-count 1) (analyze first))")
                   {:evidence :full})
        partitions (xray (str "(-> (form 'choose) (match 'case) up down right right "
                              "(partition-all 2) (analyze #(mapv vec %)))")
                         {:evidence "full"})]
    (is (= [:a 1] (:value span)))
    (is (= [":a" "1"] (get-in span [:matches 0 :forms])))
    (is (= [[:a 1] [:b 2]] (:value partitions)))
    (is (= [":b" "2"] (get-in partitions [:matches 1 :forms])))
    (is (= {:size 2 :index 1 :complete? true}
           (get-in partitions [:matches 1 :partition])))))

(deftest xray-expect-count-and-analyze-enforce-cardinality-before-computation
  (let [computed (xray (str "(-> (form 'data) (match :xs) right "
                            "(expect-count 1) (analyze (fn [[xs]] (reduce + xs))))"))
        mismatch (xray (str "(-> (form 'data) (match :xs) right "
                            "(expect-count 2) (analyze identity))"))
        literal-mismatch (xray "(-> (form 'data) (match '[1 2]) (expect-count 0))")]
    (is (= 3 (:value computed)))
    (is (= [:exactly 1] (get-in computed [:xray :cardinality])))
    (is (= :selected-values (get-in computed [:xray :input-shape])))
    (is (= :xray-cardinality-mismatch (:error-type mismatch)))
    (is (= 2 (:expected-match-count mismatch)))
    (is (= 1 (:actual-match-count mismatch)))
    (is (not (contains? mismatch :value)))
    (is (= :xray-cardinality-mismatch (:error-type literal-mismatch)))
    (is (no-source? literal-mismatch))))

(deftest xray-analyze-handles-zero-one-and-many-without-evaluating-source
  (let [zero (xray (str "(-> (form 'data) (match :missing) "
                        "(analyze (fn [xs] {:empty? (empty? xs)})))"))
        many (xray (str "(-> (form 'data) (match '_) "
                        "(where {:tag :vector}) (analyze #(mapv count %)))"))
        map-syntax (xray (str "(-> (form 'data) initializer "
                              "(analyze (fn [[m]] (assoc m :seen true))))"))]
    (is (= {:empty? true} (:value zero)))
    (is (= [2 2] (:value many)))
    (is (= 2 (:match-count many)))
    (is (= {:xs [1 2] :ys [3 4] :seen true}
           (:value map-syntax)))))

(deftest xray-computed-evidence-is-compact-by-default-and-exact-on-request
  (let [expression "(-> (form 'data) (match :ys) right (analyze first))"
        compact (xray expression)
        full (xray expression {:evidence :full})]
    (is (= [3 4] (:value compact) (:value full)))
    (is (= :compact (get-in compact [:xray :evidence])))
    (is (= :full (get-in full [:xray :evidence])))
    (is (nil? (get-in compact [:matches 0 :source])))
    (is (= "[3 4]" (get-in full [:matches 0 :source])))
    (is (re-matches #"[0-9a-f]{64}" (:selection-hash compact)))
    (is (re-matches #"[0-9a-f]{64}" (get-in compact [:matches 0 :source-hash])))
    (is (= (:trace compact) (:trace full)))
    (is (= :invalid-evidence-mode
           (:error-type (xray expression {:evidence :brief}))))))

(deftest xray-refuses-malformed-effectful-and-interop-expressions
  (doseq [[expression reason]
          [["" :expected-one-form]
           ["(form 'data) (form 'choose)" :expected-one-form]
           ["(-> (form 'data) (replace :changed))" :xray-terminal-required]
           ["(spit \"/tmp/xray-must-not-write\" \"bad\")" :disallowed-symbol]
           ["(slurp \"/tmp/xray-must-not-read\")" :disallowed-symbol]
           ["(require 'clojure.java.io)" :disallowed-symbol]
           ["(load-string \"(+ 1 2)\")" :disallowed-symbol]
           ["(System/getenv \"HOME\")" :disallowed-symbol]
           ["(.getProperty System \"user.dir\")" :disallowed-symbol]
           ["(atom {})" :disallowed-symbol]
           ["(jolt.process/sh \"ls\")" :disallowed-symbol]]]
    (let [result (xray expression)]
      (is (= :invalid-xray-expression (:error-type result)) expression)
      (is (= reason (:reason result)) expression)
      (is (not (contains? result :value))))))

(deftest xray-returns-only-realized-bounded-edn
  (doseq [[expression error-type]
          [[(str "(-> (form 'data) (match '_) (where {:tag :vector}) "
                 "(analyze (fn [xs] (map identity xs))))")
            :invalid-xray-result]
           ["(-> (form 'data) (analyze (fn [_] identity)))"
            :invalid-xray-result]
           ["(-> (form 'data) (analyze (fn [_] (/ 1 0))))"
            :xray-analysis-failed]]]
    (let [result (xray expression)]
      (is (= error-type (:error-type result)))
      (is (not (contains? result :value)))))
  (let [result (xray "(-> (form 'data) (analyze (fn [_] (pr-str (range 200)))))"
                     {:max-result-chars 100})]
    (is (= :xray-result-too-large (:error-type result)))
    (is (not (contains? result :value)))))

(deftest xray-refuses-to-analyze-truncated-selections
  (let [many-source (str "(ns bench.many)\n(def many ["
                         (apply str (map #(str " :k" %) (range 101))) "])\n")
        result (inspect/xray-source
                 many-source
                 {:file "many.clj"
                  :expression (str "(-> (form 'many) (match '_) "
                                   "(where {:tag :token :parent-tag :vector}) "
                                   "(analyze identity))")
                  :max-source-chars 100000
                  :max-query-steps 32
                  :max-matches 100
                  :max-result-chars 65536})]
    (is (= :xray-input-truncated (:error-type result)))
    (is (= 101 (:match-count result)))
    (is (= 100 (count (:matches result))))
    (is (not (contains? result :value)))))

(defn- edn-round-trip?
  [value]
  (try
    (edn/read-string (pr-str value))
    true
    (catch Exception _ false)))

(deftest xray-rejects-non-edn-patterns-in-literal-and-computed-results
  (doseq [expression ["(-> (form 'data) (match identity))"
                      "(-> (form 'data) (match identity) (analyze identity))"
                      "(-> (form 'data) (match (re-pattern \"xs\")))"
                      "(-> (form 'data) (match (with-meta [1 2] {:bad identity})))"]]
    (let [result (xray expression)]
      (is (= :invalid-query (:error-type result)) expression)
      (is (= :non-edn-query (:reason result)) expression)
      (is (not (contains? result :query)))
      (is (not (contains? result :trace)))
      (is (not (contains? result :value)))
      (is (not-any? fn? (tree-seq coll? seq result)))
      (is (edn-round-trip? result) (pr-str result)))))

(deftest xray-preserves-valid-edn-structural-patterns
  (let [source "(ns bench.patterns)\n(def data {:set #{1 2} :seq (1 2) :meta ^:answer [1 2]})\n"
        expression (str "(-> (form 'data) (match "
                        "'{:set #{1 2} :seq (1 2) :meta ^:answer [1 2]}))")
        opts {:file "patterns.clj" :max-source-chars 100000 :max-query-steps 32
              :max-matches 100 :max-result-chars 65536}
        result (inspect/xray-source source (assoc opts :expression expression))
        reader-result
        (inspect/xray-source
          source
          (assoc opts :expression
                 "(-> (form 'data) (match #uuid \"123e4567-e89b-12d3-a456-426614174000\"))"))]
    (is (nil? (:error-type result)) (pr-str result))
    (is (= 1 (:match-count result)))
    (is (= :literal (:mode result)))
    (is (edn-round-trip? result) (pr-str result))
    (is (nil? (:error-type reader-result)) (pr-str reader-result))
    (is (edn-round-trip? reader-result) (pr-str reader-result))))

(deftest xray-numeric-results-round-trip-value-and-type-and-reject-lossy-numbers
  (doseq [value [1 1.0 1M 100000000000000000000N]]
    (let [result (xray (str "(-> (form 'data) (analyze (fn [_] " (pr-str value) ")))"))
          round-tripped (edn/read-string (pr-str (:value result)))]
      (is (nil? (:error-type result)) (pr-str result))
      (is (= value (:value result)))
      (is (= (type value) (type (:value result))))
      (is (= value round-tripped))
      (is (= (type value) (type round-tripped)))))
  (doseq [[expression expected]
          [["(-> (form 'data) (match 1/2))" :invalid-query]
           ["(-> (form 'data) (analyze (fn [_] (/ 1 2))))" :invalid-xray-result]]]
    (let [result (xray expression)]
      (is (= expected (:error-type result)) (pr-str result))
      (is (not (contains? result :value)))))
  (doseq [value [(float 1) Double/NaN Double/POSITIVE_INFINITY]]
    (is (not (edit-dsl/concrete-edn? value)) (pr-str value))))
