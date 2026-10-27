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
  (:require [clojure.test :refer [deftest is testing]]
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
