;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lean-extras-test
  "What agenc-core measured and samizdat took: a check after the last write before done,
  scope limits per model family, the time left on every result, a stable cached prefix,
  recovery that asks the model to carry on, and the file list in the first message."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.select :as select]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.config :as config]
            [samizdat.engine.proc :as proc]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.userspace :as userspace]))

(def ^:private lean {:run {:style :lean}})

(deftest done-wants-a-check-after-the-last-write
  ;; agenc-core's completion gate: graded failures were done claims no check backed.
  (let [block (fn [m] (#'ship/ship-gate-block (merge {:answer "Works." :problem "p" :evidence []
                                                      :uncovered-numbers [] :advisory? true :done-streak 0
                                                      :check-before-done "run it first"}
                                                     m)))]
    (is (= "run it first" (block {:unchecked-write? true})))
    (is (nil? (block {:unchecked-write? false})) "a check after the last write is enough")
    (is (nil? (block {:unchecked-write? true :done-streak 1})) "and a second done finishes")
    (is (nil? (block {:unchecked-write? true :verdict? true})) "a verdict is never held back"))
  (testing "what counts"
    (let [u #'ship/unchecked-write?]
      (is (u {:turns [{:tool "write_file"} {:tool "read_file"}]}))
      (is (not (u {:turns [{:tool "write_file"} {:tool "shell" :category :success}]})))
      (is (u {:turns [{:tool "write_file"} {:tool "shell" :category :failure}]}) "a failing run is not a check")
      (is (u {:turns [{:tool "eval" :category :success} {:tool "shell" :wrote true}]}) "a shell write counts as a write")
      (is (not (u {:turns [{:tool "read_file"}]})) "nothing written, nothing to check"))))

(deftest the-lean-prompt-states-limits-for-its-model
  (let [prompt-for (fn [provider model]
                     (with-redefs [config/file-config (fn [& _] lean)
                                   userspace/model-context (fn [] {:provider provider :model model})]
                       (aloop/system-prompt-for :implementor)))
        base (prompt-for nil nil)
        deepseek (prompt-for :deepseek "deepseek-flash")
        glm (prompt-for :glm "glm-5.3")]
    (is (str/includes? base "Rerun a check only after"))
    (is (str/includes? deepseek "probing tool versions") "DeepSeek gets its own")
    (is (str/includes? glm "in one reply") "and GLM its own")
    (is (not= deepseek glm))
    (testing "a provider alias still finds its family"
      (is (str/includes? (prompt-for :deepseek-flash "deepseek-flash") "probing tool versions")))))

(deftest every-result-carries-the-time-left
  (let [now (System/currentTimeMillis)]
    (is (re-find #"time_remaining_sec=\d+" (str (aloop/time-stamp {:config {:run {:time-limit-seconds 1800}}
                                                                     :started-ms (- now 600000)}))))
    (is (nil? (aloop/time-stamp {:config {}})) "and nothing without a clock")))

(deftest a-reply-that-did-nothing-is-asked-to-carry-on
  ;; Asking DeepSeek for a final answer had it describe unfinished work and stop.
  (is (str/includes? (aloop/truncated-reply-message) "Continue"))
  (let [b (with-redefs [samizdat.store.journal/record-turn! (fn [& _] nil)]
            (aloop/provider-error-step {} (samizdat.agent.state/new-branch {:id "B" :problem "p"})
                                       1 "empty" :empty-reply))]
    (is (str/includes? (:content (peek (:messages b))) "Continue"))))

(deftest the-lean-first-message-lists-the-files
  ;; 75 of 110 DeepSeek trials in agenc-core spent their first call listing files.
  (let [d (str "/tmp/samizdat-lean-files-" (System/nanoTime))]
    (fs/create-dirs (str d "/src"))
    (spit (str d "/README.md") "x\n") (spit (str d "/src/a.clj") "(ns a)\n")
    (proc/run {:dir d} "git" "init" "-q")
    (try
      (let [first-user (fn [cfg] (with-redefs [config/file-config (fn [& _] cfg)
                                               userspace/project-root (fn [] d)]
                                   (:content (second (aloop/initial-messages "Do it.")))))]
        (is (str/includes? (first-user lean) "src/a.clj"))
        (is (not (str/includes? (first-user {}) "src/a.clj")) "the full loop is unchanged"))
      (finally (fs/delete-tree d)))))

(defn- call [tool args]
  {:content (str "```tool-call\n" (json/write-str {:name tool :args args}) "\n```") :finish-reason "stop"})

(deftest consecutive-requests-share-their-prefix
  ;; One misplaced reminder cost agenc-core 25,600 uncached tokens a call on DeepSeek.
  (let [root (str "/tmp/samizdat-prefix-" (System/nanoTime))
        conn (db/open! ":memory:")
        sent (atom [])
        n (atom 0)
        model (fn [_ _ messages & _]
                (swap! sent conj (mapv #(select-keys % [:role :content]) messages))
                (case (swap! n inc)
                  1 (call "read_file" {:path "README.md"})
                  2 (call "write_file" {:path "a.txt" :content "hi"})
                  3 (call "read_file" {:path "a.txt"})
                  4 (call "shell" {:command "ls"})
                  (call "done" {:answer "Wrote a.txt and listed it."})))]
    (fs/create-dirs root)
    (spit (str root "/README.md") "x\n")
    (proc/run {:dir root} "git" "init" "-q")
    (proc/run {:dir root} "git" "add" "-A")
    (proc/run {:dir root} "git" "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "base")
    (try
      (with-redefs [llm/chat model select/triage! (constantly nil)
                    config/file-config (fn [& _] lean)]
        (beam/run! {:conn conn :config {:run {:loop "loop" :root root :style :lean}}
                    :llm-adapter :a :llm-config {:max-tokens 16384}
                    :problem "Write a.txt." :max-turns 8 :beam-width 1 :oversight? false}))
      ;; The agent's own requests: other calls in the run (the end-of-run memory
      ;; distillation) are conversations of their own.
      (let [own (filterv #(= (first %) (first (first @sent))) @sent)]
        (is (>= (count own) 4))
        (doseq [[prev cur] (partition 2 1 own)]
        (is (= prev (subvec cur 0 (min (count prev) (count cur))))
            (str "request " (count prev) " messages were not a prefix of the next"))))
      (finally (fs/delete-tree root)))))
