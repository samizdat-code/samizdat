(ns samizdat.sections-test
  "The role-reply parser: GVS5H's rules, plus memdoor's rule 9 that a reply
  ending at its last section must still parse."
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.agent.sections :as sections]))

(deftest headers-split-a-reply-in-any-of-the-shapes-small-models-write
  (is (= {"PLAN" "do it" "TASKS" "- a\n- b"}
         (sections/parse "### PLAN\ndo it\n### TASKS\n- a\n- b")))
  (is (= {"PLAN" "x"} (sections/parse "**PLAN**:\nx")))
  (is (= {"STATUS" "done"} (sections/parse "STATUS:\ndone"))))

(deftest a-reply-ending-at-its-last-section-keeps-that-section
  (is (= "continue" (get (sections/parse "### NOTES\nn\n### STATUS\ncontinue") "STATUS")))
  (is (= "" (get (sections/parse "### NOTES\nn\n### NEXT") "NEXT"))))

(deftest thinking-is-not-part-of-a-reply
  (is (= {"PLAN" "real"}
         (sections/parse "<think>### PLAN\nfake</think>\n### PLAN\nreal"))))

(deftest a-reply-reads-without-its-reasoning
  (testing "a verdict the program checks must be the reply's first word"
    (is (= "PASS" (sections/reply-text "<think>was it fine?</think>\nPASS"))))
  (testing "a reply that is only reasoning still says something"
    (is (= "it ran out here" (sections/reply-text "<think>it ran out here</think>")))))

(deftest prose-with-no-headers-parses-to-nothing
  (testing "the caller decides what an unstructured reply means"
    (is (= {} (sections/parse "just some words")))
    (is (= {} (sections/parse nil)))))

(deftest bullets-read-every-list-shape-and-ignore-the-rest
  (is (= ["a" "b" "c"] (sections/bullets "- a\n* b\n1. c\nnot a bullet"))))

(deftest code-blocks-are-stripped-from-what-must-be-approach-only
  (is (= "before [code omitted] after"
         (sections/strip-code "before ```python\nprint(1)\n``` after"))))

(deftest counterexamples-parse-or-are-absent
  (is (= {:input "1 2" :expected "3"}
         (sections/counterexample
          (sections/parse "### STATUS\ncontinue\n### COUNTEREXAMPLE\nINPUT:\n1 2\nEXPECTED:\n3"))))
  (testing "half a counterexample is not one"
    (is (nil? (sections/counterexample (sections/parse "### COUNTEREXAMPLE\nINPUT:\n1 2"))))
    (is (nil? (sections/counterexample (sections/parse "### COUNTEREXAMPLE\nnone"))))
    (is (nil? (sections/counterexample {})))))
