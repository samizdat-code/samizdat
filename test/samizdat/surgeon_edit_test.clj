;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; Inspection fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim, source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.parser :as parser]
            [samizdat.resource.workspace :as workspace]
            [samizdat.surgeon.edit :as edit]))

(defn- refusal
  [sources edits]
  (try
    (edit/plan-replacements sources edits)
    nil
    (catch clojure.lang.ExceptionInfo exception
      (ex-data exception))))

(deftest owner-scoped-replacement-preserves-the-other-owner-and-source-bytes
  (let [file "src/demo.clj"
        source (str "(ns demo)\n\n"
                    "(defn intended [x]\n"
                    "  ;; Keep this exact comment.\n"
                    "  (old-call   x))\n\n"
                    "(defn unrelated [x] (old-call x))\n")
        expected (str "(ns demo)\n\n"
                      "(defn intended [x]\n"
                      "  ;; Keep this exact comment.\n"
                      "  (new-call\n  x))\n\n"
                      "(defn unrelated [x] (old-call x))\n")
        plan (edit/plan-replacements
               {file source}
               [{:file file
                 :within {:form "intended"}
                 :from "(old-call x)"
                 :to "(new-call\n  x)"}])
        compiled (first (:edits plan))]
    (is (= {file expected} (:future-sources plan)))
    (is (= {file (workspace/content-revision source)} (:file-hashes plan)))
    (is (= 1 (count (:edits plan))))
    (is (= "(old-call   x)" (:before compiled)))
    (is (= "(new-call\n  x)" (:after compiled)))
    (is (= "intended" (get-in compiled [:within :form])))
    (is (integer? (:offset compiled)))
    (is (= source (get {file source} file)))))

(deftest root-scoped-grouped-edits-use-explicit-cardinalities
  (let [file "src/group.clj"
        source "(ns group)\n(def value [(old x) (old x) :body])\n"
        plan (edit/plan-replacements
               {file source}
               [{:file file :from ":body" :to ":body.page"}
                {:file file :from "(old x)" :to "(new x)" :matches 2}])]
    (is (= "(ns group)\n(def value [(new x) (new x) :body.page])\n"
           (get-in plan [:future-sources file])))
    (is (= 3 (count (:edits plan))))
    (is (= [1 2] (sort (map (comp :match-count first)
                            (vals (group-by :edit-index (:edits plan)))))))))

(deftest grouped-edits-match-only-the-frozen-original-and-do-not-cascade
  (let [file "src/frozen.clj"
        source "(ns frozen)\n(def value [(old x) (new y)])\n"
        edits [{:file file :from "(old x)" :to "(new x)"}
               {:file file :from "(new y)" :to "(final y)"}]
        forward (edit/plan-replacements {file source} edits)
        reverse-plan (edit/plan-replacements {file source} (vec (reverse edits)))
        expected "(ns frozen)\n(def value [(new x) (final y)])\n"]
    (is (= expected (get-in forward [:future-sources file])))
    (is (= expected (get-in reverse-plan [:future-sources file])))
    (is (not= "(ns frozen)\n(def value [(final x) (final y)])\n"
              (get-in forward [:future-sources file])))))

(deftest comparison-ignores-whitespace-but-not-undeclared-comments
  (let [file "src/comments.clj"
        source "(ns comments)\n(def value (old-call ; keep this reason\n             x))\n"
        missing-comment (refusal {file source}
                                 [{:file file :from "(old-call x)" :to "(new-call x)"}])
        plan (edit/plan-replacements
               {file source}
               [{:file file
                 :from "(old-call ; keep this reason\n x)"
                 :to "(new-call ; replacement keeps the reason\n x)"}])]
    (is (= :match-count-mismatch (:surgeon/error missing-comment)))
    (is (= 0 (:actual-count missing-comment)))
    (is (= "(ns comments)\n(def value (new-call ; replacement keeps the reason\n x))\n"
           (get-in plan [:future-sources file])))))

(deftest metadata-reader-syntax-and-token-spelling-are-exact
  (let [file "src/exact.clj"
        source (str "(ns exact)\n"
                    ";; Unrelated bytes stay byte-for-byte.\n"
                    "(def values [^String x ^{:tag String} x #_ignored 1 1N])\n")
        expected (str "(ns exact)\n"
                      ";; Unrelated bytes stay byte-for-byte.\n"
                      "(def values [^String renamed ^{:tag String} x #_ignored 1 2N])\n")
        plan (edit/plan-replacements
               {file source}
               [{:file file :from "^String x" :to "^String renamed"}
                {:file file :from "1N" :to "2N"}])]
    (is (= expected (get-in plan [:future-sources file])))
    (doseq [[sources from]
            [[{file "(ns exact)\n(def only 1N)\n"} "1"]
             [{file source} "(ignored 1)"]]]
      (is (= :match-count-mismatch
             (:surgeon/error
               (refusal sources
                        [{:file file :from from :to ":not-found"}])))))))

(deftest missing-and-duplicate-owners-refuse-the-complete-plan
  (let [file "src/owners.clj"
        source (str "(ns owners)\n"
                    "(defn same [] (inc 1))\n"
                    "(defn same [] (inc 2))\n")]
    (doseq [[owner error actual]
            [["absent" :owner-not-found 0]
            ["same" :ambiguous-owner 2]]]
      (let [data (refusal {file source}
                          [{:file file :within {:form owner}
                            :from "(inc 1)" :to "(inc 3)"}])]
        (is (= error (:surgeon/error data)))
        (is (= actual (:owner-match-count data)))
        (is (nil? (:future-sources data)))))))

(deftest zero-excess-and-invalid-cardinalities-refuse-before-a-plan
  (let [file "src/counts.clj"
        source "(ns counts)\n(def values [(inc 1) (inc 1)])\n"]
    (doseq [[matches actual]
            [[1 2]
            [3 2]]]
      (let [data (refusal {file source}
                          [{:file file :from "(inc 1)" :to "(inc 2)"
                            :matches matches}])]
        (is (= :match-count-mismatch (:surgeon/error data)))
        (is (= matches (:expected-count data)))
        (is (= actual (:actual-count data)))))
    (let [zero (refusal {file source}
                        [{:file file :from "(missing)" :to "(replacement)"}])]
      (is (= :match-count-mismatch (:surgeon/error zero)))
      (is (= 0 (:actual-count zero))))
    (doseq [matches [0 -1 1.5 nil]]
      (is (= :invalid-matches
             (:surgeon/error
               (refusal {file source}
                        [{:file file :from "(inc 1)" :to "(inc 2)"
                          :matches matches}])))))))

(deftest overlapping-and-duplicate-effects-refuse-before-a-plan
  (let [file "src/overlap.clj"
        source "(ns overlap)\n(def value (outer (inner x)))\n"
        nested (refusal {file source}
                        [{:file file :from "(outer (inner x))" :to "(outer (new x))"}
                         {:file file :from "(inner x)" :to "(new x)"}])
        duplicate (refusal {file source}
                           [{:file file :from "(inner x)" :to "(new x)"}
                            {:file file :from "(inner x)" :to "(other x)"}])]
    (is (= :overlapping-edits (:surgeon/error nested)))
    (is (= :overlapping-edits (:surgeon/error duplicate)))
    (is (nil? (:future-sources nested)))
    (is (nil? (:future-sources duplicate)))))

(deftest malformed-input-and-replacement-forms-refuse
  (let [file "src/malformed.clj"
        source "(ns malformed)\n(def value (old x))\n"]
    (doseq [[field value]
            [[:from "(old"]
            [:from "(old x) extra"]
            [:from "; detached\n(old x)"]
            [:to "(new"]
            [:to "(new x) extra"]
            [:to "; detached\n(new x)"]]]
      (testing (str field " " (pr-str value))
        (let [request {:file file :from "(old x)" :to "(new x)"}
              data (refusal {file source} [(assoc request field value)])]
          (is (= :invalid-edit-form (:surgeon/error data)))
          (is (= field (:field data)))
          (is (nil? (:future-sources data))))))
    (is (= :invalid-source
           (:surgeon/error
             (refusal {file "(ns broken\n"}
                      [{:file file :from "(old x)" :to "(new x)"}]))))))

(deftest every-complete-future-file-is-reparsed-before-returning-a-plan
  (let [file "src/future.clj"
        source "(ns future)\n(def value :old)\n"
        future "(ns future)\n(def value :new)\n"
        original-parser parser/parse-string-all]
    (with-redefs [parser/parse-string-all
                  (fn [candidate]
                    (if (= future candidate)
                      (throw (ex-info "injected malformed future" {}))
                      (original-parser candidate)))]
      (let [data (refusal {file source}
                          [{:file file :from ":old" :to ":new"}])]
        (is (= :invalid-future-source (:surgeon/error data)))
        (is (= file (:file data)))
        (is (nil? (:future-sources data)))))))
