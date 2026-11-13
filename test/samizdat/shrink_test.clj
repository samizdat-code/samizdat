;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.shrink-test
  "Long tool output shrunk to what matters before it is clipped, after
  caveman's log and repetition compressors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.loop]
            [samizdat.agent.shrink :as shrink]))

(def ^:private keep-re #"(?i)\b(error|failed|traceback)\b|^\s*(File \"|E  |===)")

(def ^:private opts {:min-chars 200 :min-run 3 :keep-head 2 :keep-tail 2 :context-after 2})

(defn- opts* [] (assoc opts :keep keep-re))

(deftest identical-lines-collapse-to-one-with-a-count
  (let [out (shrink/repetition (str/join "\n" (concat ["start"] (repeat 50 "waiting for server...") ["ready"])) 3)]
    (is (= ["start" "waiting for server..." "[... 49 more identical lines]" "ready"] (str/split-lines out))))
  (testing "a short run stays"
    (is (= "a\na\nb" (shrink/repetition "a\na\nb" 3)))))

(deftest a-long-log-keeps-its-errors-and-their-frames
  (let [lines (concat ["build start" "step 1"]
                      (map #(str "compiling unit " %) (range 100))
                      ["Traceback (most recent call last):"
                       "  File \"app.py\", line 3, in <module>"
                       "    main()"
                       "ValueError: bad input"]
                      (map #(str "cleanup " %) (range 50))
                      ["done in 3s" "exit"])
        out (shrink/shrink (str/join "\n" lines) (opts*))
        kept (str/split-lines out)]
    (is (< (count out) (count (str/join "\n" lines))))
    (is (= ["build start" "step 1"] (take 2 kept)) "the head stays")
    (is (= ["done in 3s" "exit"] (take-last 2 kept)) "and the tail")
    (is (some #{"Traceback (most recent call last):"} kept))
    (is (some #{"  File \"app.py\", line 3, in <module>"} kept))
    (is (some #{"ValueError: bad input"} kept) "a line right after a kept one stays as context")
    (is (some #(str/includes? % "[... 100 lines]") kept) "a dropped run says how many")
    (is (not-any? #(str/includes? % "compiling unit 50") kept))))

(deftest short-output-is-left-alone
  (is (= "ok\nall good" (shrink/shrink "ok\nall good" (opts*)))))

(deftest a-test-run-keeps-the-failures-and-the-summary
  (let [lines (concat (map #(str "test_case_" % " PASSED") (range 300))
                      ["test_edge FAILED" "E  assert 1 == 2"]
                      (map #(str "test_more_" % " PASSED") (range 100))
                      ["=== 1 failed, 400 passed in 2.1s ==="])
        out (shrink/shrink (str/join "\n" lines) (opts*))]
    (is (str/includes? out "test_edge FAILED"))
    (is (str/includes? out "E  assert 1 == 2"))
    (is (str/includes? out "=== 1 failed, 400 passed in 2.1s ==="))
    (is (< (count out) 600) (str (count out)))))

(deftest a-long-result-is-shrunk-before-it-is-clipped
  (let [lines (concat (map #(str "compiling unit " %) (range 400))
                      ["error: undefined reference to `main'"]
                      (map #(str "linking " %) (range 300)))
        raw (str/join "\n" lines)
        spilled (atom nil)
        out (#'samizdat.agent.loop/truncate raw 7 nil (fn [s] (reset! spilled s) ".samizdat/results/turn-7-shell.txt"))]
    (is (< (count out) (count raw)))
    (is (str/includes? out "error: undefined reference to `main'") "the error in the middle survives")
    (is (str/includes? out (str "Shrunk from " (count raw) " chars")))
    (is (str/includes? out ".samizdat/results/turn-7-shell.txt"))
    (is (= raw @spilled) "the file holds the whole output, not the shrunk one")))
