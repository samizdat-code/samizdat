;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.orient-test
  "The opening block that finds what the problem statement already names
  (karamazov-fp21.3): which names are taken from the statement, how a
  definition is found under the root, what the block renders, and the two
  seams it crosses — the branch's first user message and the run row that
  lets a resume reopen on the same block."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.skills :as skills]
            [samizdat.agent.orient :as orient]
            [samizdat.store.db :as db]
            [samizdat.store.runs :as runs]
            [samizdat.userspace :as userspace]))

(defn- policy [] (gates/threshold :orient-inject))

;; --- the names ---------------------------------------------------------------

(deftest the-problem-names-are-what-the-statement-points-at
  (let [names (orient/problem-names
               "Fix `render-frame` in src/fps/core.clj so the hud.overlay draws; see fps.input/sample. Also `x`."
               (policy))]
    (is (= ["render-frame" "src/fps/core.clj" "hud.overlay" "fps.input/sample"] names)
        "backticked spans first, then paths, then dotted or hyphenated identifiers; nothing twice")
    (is (not-any? #{"x"} names) "a one-letter name locates nothing"))
  (testing "the cap holds"
    (is (= 2 (count (orient/problem-names "`a-one` `b-two` `c-three`" (assoc (policy) :max-names 2))))))
  (testing "an underscore is part of a name, not a boundary"
    ;; The first live run split test/fps/core_test.clj into test/fps/core
    ;; and test.clj — two names that located nothing.
    (is (= ["test/fps/core_test.clj"]
           (orient/problem-names "update the test in test/fps/core_test.clj to match" (policy)))))
  (testing "prose with no names yields nothing"
    (is (= [] (orient/problem-names "Make it faster and nicer." (policy))))))

;; --- the definitions ---------------------------------------------------------

(defn- tree []
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src/fps"))
    (fs/create-dirs (fs/path dir "node_modules/junk"))
    (fs/spit (fs/path dir "src/fps/core.clj")
             "(ns fps.core)\n\n(defn render-frame\n  [hud]\n  (draw hud))\n\n(def other 1)\n")
    (fs/spit (fs/path dir "app.py")
             "import os\n\nclass Thing:\n    pass\n\ndef render_frame(x):\n    return x\n")
    (fs/spit (fs/path dir "node_modules/junk/index.js") "function render_frame() {}\n")
    dir))

(deftest definitions-are-found-by-path-and-by-definition-line
  (let [dir (tree)
        p (assoc (policy) :snippet-lines 3)
        defs (orient/definitions dir ["render-frame" "src/fps/core.clj" "render_frame" "nothing-here"] p)]
    (is (= ["render-frame" "src/fps/core.clj" "render_frame"] (mapv :name defs))
        "every name that locates something, in the statement's order; a name that does not is silent")
    (is (= {:path "src/fps/core.clj" :line 3} (select-keys (first defs) [:path :line])))
    (is (= "(defn render-frame\n  [hud]\n  (draw hud))" (:snippet (first defs))))
    (is (= 1 (:line (second defs))) "a named file is shown from its top")
    (is (= "app.py" (:path (nth defs 2))) "a Python def is found — and node_modules is skipped")
    (testing "the snippet cap"
      (is (= 1 (count (orient/definitions dir ["render-frame" "app.py"] (assoc p :max-snippets 1))))))
    (testing "a file named by its basename alone"
      (is (= "src/fps/core.clj" (:path (first (orient/definitions dir ["core.clj"] p))))))))

;; --- the block ---------------------------------------------------------------

(deftest the-block-renders-only-when-on-and-when-something-was-found
  (let [dir (tree)
        problem "Fix `render-frame` in src/fps/core.clj."]
    (is (nil? (orient/block dir problem (assoc (policy) :enabled? false))) "off is off")
    (let [{:keys [block names found]} (orient/block dir problem (policy))]
      (is (= ["render-frame" "src/fps/core.clj"] names))
      (is (= ["render-frame" "src/fps/core.clj"] found))
      (is (str/includes? block "src/fps/core.clj:3"))
      (is (str/includes? block "(defn render-frame")))
    (let [{:keys [block names found]} (orient/block dir "Fix `nothing-here`." (policy))]
      (is (= ["nothing-here"] names))
      (is (= [] found))
      (is (nil? block) "nothing found, nothing said — not a heading over an empty list"))))

;; --- the seams ---------------------------------------------------------------

(deftest the-opening-carries-the-block-inside-the-problem-turn
  (let [with (aloop/initial-messages "do the thing" nil nil "ORIENT BLOCK")
        without (aloop/initial-messages "do the thing" nil nil nil)]
    (is (= 2 (count with)))
    (is (str/includes? (:content (second with)) "ORIENT BLOCK"))
    (is (str/includes? (:content (second with)) "do the thing"))
    (is (< (str/index-of (:content (second with)) "do the thing")
           (str/index-of (:content (second with)) "ORIENT BLOCK"))
        "after the problem, before the first-call instruction")
    (is (not (str/includes? (:content (second without)) "ORIENT")))
    (is (= without (aloop/initial-messages "do the thing" nil nil))
        "a nil block is byte-identical to the opening that never had one")))

(defn- with-root [root f]
  (let [previous (userspace/bind-root! root)]
    (try
      (f)
      (finally
        (userspace/bind-root! previous)))))

(deftest opening-auto-loads-only-relevant-jolt-skills
  (let [jolt-root (str (fs/create-temp-dir))
        other-root (str (fs/create-temp-dir))
        missing-root (str (fs/create-temp-dir))]
    (try
      (doseq [root [jolt-root other-root missing-root]]
        (fs/create-dirs (str root "/.samizdat/skills"))
        (fs/create-dirs (str root "/src"))
        (spit (str root "/src/core.clj") "(ns core)"))
      (spit (str jolt-root "/.samizdat/config.edn") "{:run {:project-language :jolt}}")
      (spit (str other-root "/.samizdat/config.edn") "{:run {:project-language :clojure}}")
      (spit (str jolt-root "/.samizdat/skills/clj-debug.md")
            "---\nname: clj-debug\ndescription: Use when debugging.\n---\nPROJECT DEBUG")
      (spit (str jolt-root "/.samizdat/skills/clj-replace.md")
            "---\nname: clj-replace\ndescription: Use when replacing.\n---\nPROJECT REPLACE")
      (let [messages (with-root jolt-root
                       #(aloop/initial-messages "debug this failing function" "SUFFIX" nil "ORIENT"))
            system (:content (first messages))
            user (:content (second messages))
            unrelated (with-root jolt-root
                        #(aloop/initial-messages "implement a retry helper" "SUFFIX" nil "ORIENT"))
            other (with-root other-root
                   #(aloop/initial-messages "debug this failing function" "SUFFIX" nil "ORIENT"))
            missing (with-root missing-root
                     #(aloop/initial-messages "debug this failing function" "SUFFIX" nil "ORIENT"))
            multi (with-root jolt-root
                   #(aloop/initial-messages "debug and replace this" "SUFFIX" nil "ORIENT"))
            other-unmodified (with-root other-root
                              #(with-redefs [skills/auto-activate (fn [& _] [])]
                                 (aloop/initial-messages "debug this failing function" "SUFFIX" nil "ORIENT")))
            missing-unmodified (with-root missing-root
                                #(with-redefs [skills/auto-activate (fn [& _] [])]
                                   (aloop/initial-messages "debug this failing function" "SUFFIX" nil "ORIENT")))]
        (is (= 1 (count (re-seq #"PROJECT DEBUG" system))) "the overlay body appears once")
        (is (str/includes? system "SUFFIX"))
        (is (str/includes? user "ORIENT"))
        (is (not (str/includes? (:content (first unrelated)) "PROJECT DEBUG")))
        (is (not (str/includes? (:content (first unrelated)) "PROJECT REPLACE")))
        (is (= other-unmodified other) "a matching trigger is suppressed for another language")
        (is (= missing-unmodified missing) "a matching trigger is suppressed without project language")
        (is (= 1 (count (re-seq #"PROJECT DEBUG" (:content (first multi))))))
        (is (= 1 (count (re-seq #"PROJECT REPLACE" (:content (first multi)))))))
      (finally
        (doseq [root [jolt-root other-root missing-root]]
          (fs/delete-tree root))))))

(deftest every-jolt-trigger-class-injects-its-skill-once
  (let [root (str (fs/create-temp-dir))
        previous (userspace/bind-root! root)]
    (try
      (fs/create-dirs (str root "/.samizdat"))
      (fs/create-dirs (str root "/src"))
      (spit (str root "/.samizdat/config.edn") "{:run {:project-language :jolt}}")
      (spit (str root "/src/core.clj") "(ns core)")
      (doseq [[problem marker] [["debug this failing function" "Debug in the live REPL"]
                               ["explain unfamiliar Java interop" "Discover before integrating"]
                               ["replace after cljfmt" "Replace Clojure and Jolt by structure"]
                               ["review mechanism/policy separation" "Review mechanism and policy"]]]
        (let [system (:content (first (aloop/initial-messages problem)))]
          (is (= 1 (count (re-seq (re-pattern marker) system))) problem)))
      (finally
        (userspace/bind-root! previous)
        (fs/delete-tree root)))))

(deftest the-run-row-keeps-the-block-so-a-resume-reopens-on-it
  (let [c (db/open! ":memory:")]
    (try
      (let [rid (runs/start-run! c {:problem "p" :opening-context "THE BLOCK"})
            bare (runs/start-run! c {:problem "p"})]
        (is (= "THE BLOCK" (:opening_context (runs/get-run c rid))))
        (is (nil? (:opening_context (runs/get-run c bare)))))
      (finally (db/close c)))))
