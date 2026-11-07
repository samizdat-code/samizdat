;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.context-handling-test
  "What a long run keeps in front of the model (karamazov-lu4p): folds that count
  their own saving correctly, a record that survives a rejected summary, several
  read-only calls from one reply, and old write bodies out of the prompt."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.compaction :as cmp]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.tools :as tools]
            [samizdat.cells :as cells]
            [mycelium.cell :as cell]
            [samizdat.agent.gates :as gates]
            [samizdat.llm.client]
            [samizdat.llm.fence]
            [samizdat.store.journal]
            [samizdat.store.knowledge]
            [samizdat.workflow]
            [samizdat.prompt :as prompt]))

(defn- convo []
  (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
        (mapcat (fn [i] [{:role "assistant" :content (str "call " i " " (apply str (repeat 400 "x")))}
                         {:role "user" :content (str "result " i " " (apply str (repeat 400 "y")))}])
                (range 12))))

(defn- fold! [{:keys [chat turns before]}]
  (cells/load-cells!)
  (let [notes (atom [])
        side-calls (atom [])
        ctx {:conn ::conn :run-id "r" :llm-adapter ::a
             :llm-config {:provider :test :model "m" :context-window 128000 :max-tokens 65536}}
        branch {:id "B1" :messages (convo) :turns []}
        ladder (cmp/ladder-tokens (:accounting (infer/prepare ctx (infer/of-branch branch) {:attempt 1 :fit? false}))
                                  {:reply-reserve-tokens 16384})
        out (with-redefs [samizdat.workflow/role-ctx (fn [c _] c)
                          samizdat.llm.client/chat chat
                          samizdat.store.knowledge/remember! (fn [& _] "k-1")
                          samizdat.store.journal/branch-turns (fn [& _] turns)
                          samizdat.store.journal/record-side-call! (fn [_ _ m] (swap! side-calls conj m))
                          samizdat.store.journal/note! (fn [_ _ _ n] (swap! notes conj (:data n)) nil)]
              ((:handler (cell/get-cell! :compaction/fold))
               ctx {:compaction/tier :fold :compaction/before (or before ladder) :branch branch :turn 30}))]
    {:out out :notes @notes :side-calls @side-calls :before ladder}))

(def ^:private good-summary
  "## Active Task\nFinish it.\n## Goal\nShip.\n## Completed Actions\nWrote a.clj.")

(deftest a-fold-compares-like-with-like
  ;; The ladder counts at most :reply-reserve-tokens of the reply budget. `after`
  ;; counted all 65536, so a fold had to free about 49K to count as progress:
  ;; every supervisor fold failed that, after paying for the summary.
  (let [{:keys [out notes before]} (fold! {:chat (fn [& _] {:content good-summary})})
        {:keys [action after]} (first (filter :action notes))]
    (is (= "fold" action) (pr-str notes))
    (is (< after before))
    (is (< (count (get-in out [:branch :messages])) (count (convo))))))

(deftest a-summary-is-on-the-bill
  (let [{:keys [side-calls]} (fold! {:chat (fn [& _] {:content good-summary :usage {:total_tokens 9}})})]
    (is (= [:compaction] (mapv :kind side-calls)))
    (is (= {:total_tokens 9} (:usage (first side-calls))))))

(defn- snap-opts [m] (merge (:snapshot (gates/threshold :compaction)) m))

(def ^:private turns
  [{:turn 3 :tool_name "write_file" :args (json/write-str {:path "src/a.clj" :content "x"}) :result "Wrote" :category "success"}
   {:turn 4 :tool_name "shell" :args (json/write-str {:command "jolt test"}) :result "boom\nFAIL in thing" :category "failure"}
   {:turn 5 :tool_name "edit_file" :args (json/write-str {:path "src/b.clj"}) :result "ok" :category "success"}
   {:turn 6 :tool_name "edit_clojure" :args (json/write-str {:edits [{:file "src/c.clj"}]}) :result "ok" :category "success"}
   {:turn 7 :tool_name "shell" :args (json/write-str {:command "./compile.sh"}) :result "fatal: no main" :category "failure"}
   {:turn 8 :tool_name "shell" :args (json/write-str {:command "./compile.sh"}) :result "built" :category "success"}
   {:turn 9 :tool_name "read_file" :args (json/write-str {:path "src/a.clj"}) :result "..." :category "success"}])

(deftest the-snapshot-is-read-from-the-journal
  (let [{:keys [files commands errors]} (cmp/snapshot-facts turns (snap-opts {:max-files 10 :max-commands 2 :max-errors 5 :result-chars 40}))]
    (is (= ["src/a.clj" "src/b.clj" "src/c.clj"] (mapv :path files)))
    (testing "the latest commands, each with how it ended"
      (is (= ["jolt test" "./compile.sh"] (mapv :command commands)) "one line per command, at its latest run")
      (is (= "built" (:last (peek commands)))))
    (testing "only failures nothing later fixed"
      (is (= ["jolt test"] (mapv :command errors)))
      (is (str/includes? (:last (first errors)) "FAIL in thing"))))
  (testing "bounds hold, and an empty journal has nothing to say"
    (is (= 1 (count (:files (cmp/snapshot-facts turns (snap-opts {:max-files 1 :max-commands 1 :max-errors 1 :result-chars 5}))))))
    (is (cmp/snapshot-empty? (cmp/snapshot-facts [] (snap-opts {}))))))

(deftest a-rejected-summary-no-longer-blocks-a-fold
  (let [{:keys [out notes]} (fold! {:turns turns :chat (fn [& _] {:content "## Active Task\nNone."})})
        text (str/join "\n" (map :content (get-in out [:branch :messages])))
        note (first (filter :action notes))]
    (is (= "fold" (:action note)))
    (is (= "summary rejected" (:summary note)))
    (is (str/includes? (str (:rejected note)) "Active Task") "what was rejected can be read later")
    (is (str/includes? text "src/a.clj"))
    (is (str/includes? text "jolt test")))
  (testing "a good summary gets the snapshot beside it"
    (let [{:keys [out]} (fold! {:turns turns :chat (fn [& _] {:content good-summary})})
          text (str/join "\n" (map :content (get-in out [:branch :messages])))]
      (is (str/includes? text "Wrote a.clj."))
      (is (str/includes? text "src/b.clj"))))
  (testing "with no journal to read, a rejected summary still drops nothing"
    (let [{:keys [out notes]} (fold! {:turns [] :chat (fn [& _] {:content "nothing"})})]
      (is (= "prune-only" (:action (first (filter :action notes)))))
      (is (= (count (convo)) (count (get-in out [:branch :messages])))))))

(deftest which-calls-may-run-beside-another
  (doseq [c [{:name "read_file" :args {:path "a"}}
             {:name "grep" :args {:pattern "x"}}
             {:name "shell" :args {:command "ls -la"}}
             {:name "shell" :args {:command "cd /workspace; sed -n 1,20p a.txt"}}
             {:name "shell" :args {:command "grep -n foo src/a.clj | head -5"}}]]
    (is (tools/batchable? c) (pr-str c)))
  (doseq [c [{:name "write_file" :args {:path "a" :content "x"}}
             {:name "shell" :args {:command "jolt test"}}
             {:name "shell" :args {:command "cat a > b"}}
             {:name "shell" :args {:command "ls; rm -rf x"}}
             {:name "shell" :args {:command "find . -delete"}}
             {:name "shell" :args {:command "sed -i s/a/b/ f"}}
             {:name "shell" :args {:command "cat a | sh"}}
             {:name "shell" :args {:command "echo $(rm x)"}}
             {:name "done" :args {}}]]
    (is (not (tools/batchable? c)) (pr-str c))))

(deftest a-reply-keeps-the-calls-it-could-not-run-first
  (let [p (samizdat.llm.fence/parse-tool-call
           (str "```tool-call\n{\"name\":\"read_file\",\"args\":{\"path\":\"a\"}}\n```\n"
                "```tool-call\n{\"name\":\"write_file\",\"args\":{\"path\":\"b\",\"content\":\"x\"}}\n```\n"
                "```tool-call\n{\"name\":\"shell\",\"args\":{\"command\":\"ls\"}}\n```\n"))]
    (is (= "shell" (:name p)))
    (is (= ["read_file" "write_file"] (:dropped p)))
    (is (= [{:name "read_file" :args {:path "a"}}
            {:name "write_file" :args {:path "b" :content "x"}}]
           (:dropped-calls p)))))

(deftest read-only-calls-run-first-and-the-rest-are-still-named
  (let [ran (atom [])]
    (with-redefs [samizdat.agent.tools/phase-refusal (fn [_] nil)
                  samizdat.agent.tools/run-tool
                  (fn [{:keys [tool-name]}] (swap! ran conj tool-name)
                    {:result (str tool-name " out") :category :success})]
      (let [{:keys [text ran-names still-dropped]}
            (tools/run-batched {:branch {:id "B1"}}
                               {:dropped ["read_file" "write_file" "grep" "read_file" "read_file" "read_file"]
                                :dropped-calls [{:name "read_file" :args {:path "a"}}
                                                {:name "write_file" :args {:path "b"}}
                                                {:name "grep" :args {:pattern "x"}}
                                                {:name "read_file" :args {:path "c"}}
                                                {:name "read_file" :args {:path "d"}}
                                                {:name "read_file" :args {:path "e"}}]})]
        (is (= ["read_file" "grep" "read_file"] @ran ran-names))
        (is (= ["write_file" "read_file" "read_file"] still-dropped) "over the limit, and the write")
        (is (str/includes? text "read_file out"))
        (is (str/includes? text "grep out"))))))

(deftest old-write-bodies-leave-the-prompt-in-one-batch
  (let [body (apply str (repeat 6000 "z"))
        call (fn [tool args]
               (str "Writing it.\n\n```tool-call\n" (json/write-str {:name tool :args args}) "\n```"))
        msgs [{:role "system" :content "sys"}
              {:role "assistant" :content (call "write_file" {:path "src/a.clj" :content body})}
              {:role "user" :content "Wrote"}
              {:role "assistant" :content (call "edit_clojure" {:edits [{:file "src/b.clj" :from body :to body}]})}
              {:role "user" :content "ok"}
              {:role "assistant" :content (call "shell" {:command (apply str (repeat 900 "c"))})}
              {:role "user" :content "ok"}
              {:role "assistant" :content (call "write_file" {:path "src/tail.clj" :content body})}]
        opts {:protect-tail 1 :body-chars 200 :min-freed-tokens 1000 :chars-per-token 4
              :tools #{"write_file" "edit_file" "patch" "edit_clojure"}
              :note-fn (fn [n] (str "[" n " chars, on disk]"))}
        {:keys [messages freed]} (cmp/clip-write-bodies msgs opts)
        parsed (fn [i] (samizdat.llm.fence/parse-tool-call (:content (nth messages i))))]
    (is (pos? freed))
    (testing "the call still parses and still says what it touched"
      (is (= "src/a.clj" (get-in (parsed 1) [:args :path])))
      (is (= "[6000 chars, on disk]" (get-in (parsed 1) [:args :content])))
      (is (= "src/b.clj" (get-in (parsed 3) [:args :edits 0 :file])))
      (is (str/starts-with? (:content (nth messages 1)) "Writing it.")))
    (testing "only writes, and never the protected tail"
      (is (= (nth msgs 5) (nth messages 5)))
      (is (= (nth msgs 7) (nth messages 7))))
    (testing "too little to free is not worth breaking the cache"
      (let [r (cmp/clip-write-bodies msgs (assoc opts :min-freed-tokens 1000000))]
        (is (= msgs (:messages r)))
        (is (zero? (:freed r)))))))

(deftest the-prompt-can-be-read-by-part
  (let [parts (cmp/prompt-parts
               [{:role "system" :content "12345"}
                {:role "user" :content "abc"}
                {:role "assistant" :content "```tool-call\n{\"name\":\"write_file\",\"args\":{\"path\":\"a\",\"content\":\"zz\"}}\n```"}
                {:role "assistant" :content "```tool-call\n{\"name\":\"shell\",\"args\":{\"command\":\"ls\"}}\n```"}
                {:role "system" :content "sum" :compaction? true}]
               #{"write_file"})]
    (is (= 5 (:system parts)))
    (is (= 3 (:results parts)))
    (is (= 3 (:summaries parts)))
    (is (pos? (:writes parts)))
    (is (pos? (:calls parts)))
    (is (< (:calls parts) (:writes parts)))))

(deftest the-snapshot-prompt-renders
  (let [msg (prompt/render "compaction-snapshot"
                           (cmp/snapshot-facts turns (snap-opts {})))]
    (is (str/includes? msg "src/a.clj"))
    (is (str/includes? msg "./compile.sh"))))
