;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp-elicit-test
  "The elicitation schema subset of MCP 2025-11-25 (and 2025-06-18, which
  it extends): questions out, validated content back (karamazov-k7gl.12)."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.mcp.elicit :as elicit]))

(defn- check [schema answers] (elicit/check schema answers))

(defn- obj [props & [required]]
  (cond-> {:type "object" :properties props} required (assoc :required required)))

(deftest what-the-spec-allows-is-accepted-and-the-rest-refused
  (is (nil? (elicit/refusal {:requestedSchema (obj {:a {:type "string"}})})))
  (is (nil? (elicit/refusal {:mode "form" :requestedSchema (obj {:a {:type "array" :items {:enum ["x"]}}})})))
  (is (nil? (elicit/refusal {:requestedSchema (obj {:a {:type "array" :items {:anyOf [{:const "x" :title "X"}]}}})})))
  (is (nil? (elicit/refusal {:requestedSchema {:type "object"}})) "a bare confirmation")
  (is (= {:mode "url"} (elicit/refusal {:mode "url" :url "https://x.test" :elicitationId "1"}))
      "url mode is not declared, so it is refused")
  (is (= {:unsupported ["addr" "tags"]}
         (elicit/refusal {:requestedSchema (obj {:name {:type "string"}
                                                 :addr {:type "object" :properties {:city {:type "string"}}}
                                                 :tags {:type "array" :items {:type "string"}}})}))
      "a nested object and an array of free text are outside the subset")
  (is (= {:unsupported ["x"]} (elicit/refusal {:requestedSchema (obj {:x {:type "null"}})}))))

(deftest questions-carry-options-multi-select-defaults-and-limits
  (let [qs (elicit/questions
            (obj {:colour {:type "string" :oneOf [{:const "#f00" :title "Red"} {:const "#00f" :title "Blue"}]
                           :default "#00f"}
                  :tags {:type "array" :minItems 1 :maxItems 2
                         :items {:type "string" :enum ["a" "b" "c"]} :default ["a"]}
                  :legacy {:type "string" :enum ["x" "y"] :enumNames ["Ex" "Why"]}
                  :email {:type "string" :format "email" :minLength 3}
                  :age {:type "integer" :minimum 18 :maximum 99}
                  :ok {:type "boolean" :default false}}
                 ["email"]))
        [colour tags legacy email age ok] qs]
    (is (= ["Red" "Blue"] (:options colour)) "a titled enum shows its titles")
    (is (= "Blue" (:default colour)) "and its default by title")
    (is (true? (:multi tags)))
    (is (= ["a" "b" "c"] (:options tags)))
    (is (= ["a"] (:default tags)))
    (is (= {:minitems 1 :maxitems 2} (:limits tags)))
    (is (= ["Ex" "Why"] (:options legacy)) "legacy enumNames are titles too")
    (is (true? (:required email)))
    (is (= {:format "email" :minlen 3} (:limits email)))
    (is (= {:minimum 18 :maximum 99} (:limits age)))
    (is (= ["true" "false"] (:options ok)))
    (is (= "false" (:default ok)))
    (is (not (:multi colour)))))

(deftest answers-are-typed-and-validated
  (let [schema (obj {:colour {:type "string" :oneOf [{:const "#f00" :title "Red"} {:const "#00f" :title "Blue"}]}
                     :tags {:type "array" :minItems 1 :maxItems 2 :items {:anyOf [{:const "a" :title "A"} {:const "b" :title "B"} {:const "c" :title "C"}]}}
                     :n {:type "number" :minimum 0 :maximum 1}
                     :i {:type "integer"}
                     :ok {:type "boolean"}}
                    ["colour"])]
    (is (= {:content {:colour "#00f" :tags ["a" "c"] :n 0.5 :i 3 :ok true} :problems nil}
           (check schema ["Blue" ["A" "C"] "0.5" "3" "yes"])))
    (is (= {:colour "#f00"} (:content (check schema ["#f00" nil "" nil nil])))
        "a const is taken as well as its title; optional blanks are left out")
    (is (= {:colour {:kind "option"} :tags {:kind "maxitems" :limit 2} :n {:kind "maximum" :limit 1}
            :i {:kind "type" :limit "integer"} :ok {:kind "type" :limit "boolean"}}
           (:problems (check schema ["Green" ["A" "B" "C"] "2" "3.5" "maybe"]))))
    (is (= {:tags {:kind "minitems" :limit 1}} (:problems (check (assoc schema :required ["tags"]) ["Red" []])))
        "an empty selection of a required multi-select falls to its minItems")
    (is (= {:colour {:kind "required"}} (:problems (check schema [nil]))))))

(deftest strings-are-checked-against-length-pattern-and-format
  (let [p (fn [prop a] (:problems (check (obj {:s prop}) [a])))]
    (is (= {:s {:kind "minlen" :limit 3}} (p {:type "string" :minLength 3} "ab")))
    (is (= {:s {:kind "maxlen" :limit 3}} (p {:type "string" :maxLength 3} "abcd")))
    (is (= {:s {:kind "pattern"}} (p {:type "string" :pattern "^[A-Za-z]+$"} "ab1")))
    (is (nil? (p {:type "string" :pattern "^[A-Za-z]+$"} "abc")))
    (testing "formats"
      (is (nil? (p {:type "string" :format "email"} "a@b.co")))
      (is (= {:s {:kind "format"}} (p {:type "string" :format "email"} "not-an-email")))
      (is (nil? (p {:type "string" :format "uri"} "https://example.com/x")))
      (is (= {:s {:kind "format"}} (p {:type "string" :format "uri"} "example dot com")))
      (is (nil? (p {:type "string" :format "date"} "2026-02-28")))
      (is (= {:s {:kind "format"}} (p {:type "string" :format "date"} "2026-13-01")))
      (is (= {:s {:kind "format"}} (p {:type "string" :format "date"} "2026-02-30")))
      (is (nil? (p {:type "string" :format "date-time"} "2026-02-28T10:00:00Z")))
      (is (nil? (p {:type "string" :format "date-time"} "2026-02-28T10:00:00.5+01:00")))
      (is (= {:s {:kind "format"}} (p {:type "string" :format "date-time"} "2026-02-28 noon"))))))

(deftest a-blank-answer-takes-the-default
  (let [schema (obj {:s {:type "string" :default "x"}
                     :b {:type "boolean" :default false}
                     :m {:type "array" :items {:enum ["a" "b"]} :default ["b"]}
                     :n {:type "integer" :default 5}}
                    ["s" "b" "m" "n"])]
    (is (= {:content {:s "x" :b false :m ["b"] :n 5} :problems nil} (check schema ["" nil [] " "])))))

(deftest the-result-says-what-the-person-did
  (let [schema (obj {:name {:type "string"}} ["name"])]
    (is (= {:action "accept" :content {:name "octo"}} (elicit/result schema {:decision :allow :answers ["octo"]})))
    (is (= {:action "decline"} (elicit/result schema {:decision :allow :answers [""]}))
        "a submission that still lacks a required field is not data the server may read as accepted")
    (is (= {:action "decline"} (elicit/result schema {:decision :deny})))
    (is (= {:action "cancel"} (elicit/result schema {:decision :deny :timed-out true})))
    (is (= {:action "cancel"} (elicit/result schema {:abandoned true})))
    (is (= {:action "accept" :content {}} (elicit/result {:type "object"} {:decision :allow}))
        "a bare confirmation accepted")))

(deftest a-re-ask-marks-the-problem-and-keeps-what-was-given
  (let [schema (obj {:name {:type "string"} :age {:type "integer" :minimum 18}} ["name"])
        {:keys [content problems]} (check schema ["octo" "12"])
        [name age] (elicit/questions schema {:problems problems :prior content})]
    (is (= "octo" (:default name)) "the valid answer is offered back")
    (is (nil? (:problem name)))
    (is (= {:kind "minimum" :limit 18} (:problem age)))))
