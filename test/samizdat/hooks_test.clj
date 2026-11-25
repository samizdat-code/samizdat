;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.hooks-test
  "Hooks on triggers (karamazov-k7gl.10): config.edn :hooks, run through the
  shell's own policy and sandbox, journalled as :hook, and their
  `###SAMIZDAT:<key> <value>` lines read back as directives."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools :as tools]
            [samizdat.prompt :as prompt]
            [samizdat.config :as config]
            [samizdat.hooks :as hooks]
            [samizdat.security.policy :as policy]
            [samizdat.store.grants :as grants]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(deftest hooks-are-normalised-and-bad-ones-dropped
  (let [hs (hooks/normalize [{:name "fmt" :trigger :after-write :command "cljfmt fix"}
                             {:name "slow" :trigger :run-end :command "x" :timeout-ms 99999999}
                             {:name "typo" :trigger :after-wrte :command "x"}
                             {:name "empty" :trigger :run-start :command "  "}
                             "not a map"])]
    (is (= ["fmt" "slow"] (map :name hs)) "an unknown trigger or no command is dropped")
    (is (pos? (:timeout-ms (first hs))) "a default timeout")
    (is (<= (:timeout-ms (second hs)) (:max-ms policy/shell-timeout))
        "never past the shell's own cap")
    (is (= [] (hooks/normalize nil)))
    (is (= [] (hooks/normalize {:not "a vector"})))))

(deftest directives-are-read-after-stripping-ansi
  (let [out (str "building\n"
                 "\u001B[32m###SAMIZDAT:message 3 files reformatted\u001B[0m\n"
                 "###SAMIZDAT:status ok\n"
                 "  ###SAMIZDAT:message not at line start is ignored\n"
                 "###SAMIZDAT:flag\n")]
    (is (= [{:key "message" :value "3 files reformatted"}
            {:key "status" :value "ok"}
            {:key "flag" :value ""}]
           (hooks/directives out)))
    (is (= "plain" (hooks/strip-ansi "\u001B[1;31mplain\u001B[0m")))))

(deftest the-hook-environment-names-the-trigger-and-its-context
  (let [env (hooks/hook-vars {:name "fmt" :trigger :after-write}
                             {:run-id "r1" :branch-id "B1" :root "/p"}
                             {:path "src/a.clj"})]
    (is (= "after-write" (get env "SAMIZDAT_HOOK_TRIGGER")))
    (is (= "fmt" (get env "SAMIZDAT_HOOK_NAME")))
    (is (= "src/a.clj" (get env "SAMIZDAT_HOOK_PATH")))
    (is (= "r1" (get env "SAMIZDAT_HOOK_RUN_ID")))
    (is (= "B1" (get env "SAMIZDAT_HOOK_BRANCH_ID")))
    (is (= "/p" (get env "SAMIZDAT_HOOK_ROOT"))))
  (let [env (hooks/hook-vars {:name "x" :trigger :after-shell} {} {:command "ls" :exit 2})]
    (is (= "ls" (get env "SAMIZDAT_HOOK_COMMAND")))
    (is (= "2" (get env "SAMIZDAT_HOOK_EXIT")))))

(defn- with-hooks [hs f]
  (with-redefs [config/file-config (constantly {:hooks hs})]
    (f)))

(deftest a-hook-goes-through-the-shell-policy-and-is-journalled
  (let [seen (atom nil)
        notes (atom [])
        sent (atom [])]
    (with-hooks [{:name "fmt" :trigger :after-write :command "fmt-it" :timeout-ms 5000}]
      #(with-redefs [policy/run-shell (fn [ctx] (reset! seen ctx)
                                        {:category :success :result "ok"
                                         :policy {:effect :allow}
                                         :data {:exit 0 :out "###SAMIZDAT:message reformatted a.clj\nok\n" :err ""}})
                     journal/note! (fn [_ run-id kind m] (swap! notes conj [run-id kind m]))
                     interventions/submit! (fn [_ run-id m] (swap! sent conj [run-id m]) 1)]
         (let [[ev] (hooks/fire! {:conn :c :run-id "r1" :root "/p" :branch {:id "B1"} :turn 3}
                                 :after-write {:path "a.clj"})]
           (is (= "fmt-it" (get-in @seen [:args :command])) "the command is the shell's to judge")
           (is (= "a.clj" (get-in @seen [:env "SAMIZDAT_HOOK_PATH"])))
           (is (= "/p" (:root @seen)))
           (is (= 5000 (:timeout-ms @seen)))
           (is (nil? (:branch @seen)) "a hook is not the branch: it does not move its flow label")
           (is (= 0 (:exit ev)))
           (is (= [{:key "message" :value "reformatted a.clj"}] (:directives ev)))
           (is (= [["r1" :hook]] (map (fn [[r k]] [r k]) @notes)))
           (is (= "B1" (:branch-id (nth (first @notes) 2))))
           (testing "a message directive is queued for the branch as a hook notice"
             (is (= 1 (count @sent)))
             (let [[run-id m] (first @sent)]
               (is (= "r1" run-id))
               (is (= "B1" (:branch-id m)))
               (is (= "message" (:kind m)))
               (is (= "hook" (:issued-by m)))
               (is (str/includes? (get-in m [:payload :text]) "reformatted a.clj"))
               (is (str/includes? (get-in m [:payload :text]) "fmt")))))))))

(deftest a-refused-hook-is-journalled-as-refused-and-says-nothing
  (let [notes (atom [])
        sent (atom [])]
    (with-hooks [{:name "rm" :trigger :run-start :command "rm -rf /"}]
      #(with-redefs [journal/note! (fn [_ _ kind m] (swap! notes conj [kind m]))
                     interventions/submit! (fn [& a] (swap! sent conj a) 1)
                     grants/for-run (constantly {:grants []})]
         (let [[ev] (hooks/fire! {:conn :c :run-id "r1" :root "/nonexistent-root"}
                                 :run-start {})]
           (is (= :deny (:effect ev)) "the shell policy refused it")
           (is (= "rm" (:refused ev)))
           (is (nil? (:exit ev)))
           (is (empty? @sent))
           (is (= :hook (ffirst @notes))))))))

(deftest a-hook-that-throws-is-an-error-not-an-allow
  (with-hooks [{:name "x" :trigger :run-start :command "echo hi"}]
    #(with-redefs [policy/run-shell (fn [_] (throw (ex-info "boom" {})))]
       (let [[ev] (hooks/fire! {:root "/p"} :run-start {})]
         (is (= :error (:effect ev)))
         (is (= "boom" (:error ev)))
         (is (empty? (:directives ev)))))))

(deftest no-hooks-no-work
  (with-hooks nil
    #(with-redefs [policy/run-shell (fn [_] (throw (ex-info "ran" {})))]
       (is (= [] (hooks/fire! {:root "/p"} :run-start {}))))))

(deftest a-real-allowed-hook-runs-in-the-sandbox-and-its-directive-is-read
  (let [root (str "/tmp/samizdat-hooks-" (random-uuid))]
    (fs/create-dirs root)
    (try
      (with-hooks [{:name "say" :trigger :after-shell
                    :command "echo \"###SAMIZDAT:message exit was $SAMIZDAT_HOOK_EXIT\""}]
        #(let [[ev] (hooks/fire! {:root root} :after-shell {:command "make" :exit 2})]
           (is (= :allow (:effect ev)))
           (is (= 0 (:exit ev)))
           (is (= [{:key "message" :value "exit was 2"}] (:directives ev)))))
      (finally (fs/delete-tree root)))))

(deftest tools-fire-the-trigger-the-gates-map-them-to
  (let [fired (atom [])]
    (with-redefs [hooks/fire! (fn [_ trigger vars] (swap! fired conj [trigger vars]) [])]
      (hooks/after-tool! {:tool-name "write_file" :args {:path "a.clj"}} {:category :success})
      (hooks/after-tool! {:tool-name "write_file" :args {:path "b.clj"}} {:category :failure})
      (hooks/after-tool! {:tool-name "shell" :args {:command "ls"}}
                         {:category :failure :policy {:effect :allow} :data {:exit 1}})
      (hooks/after-tool! {:tool-name "shell" :args {:command "rm x"}}
                         {:category :mechanics :policy {:effect :deny}})
      (hooks/after-tool! {:tool-name "read_file" :args {:path "a"}} {:category :neutral})
      (is (= [[:after-write {:path "a.clj"}]
              [:after-shell {:command "ls" :exit 1}]]
             @fired)))))

(deftest the-tool-seam-calls-the-hooks
  (let [root (str "/tmp/samizdat-hooks-" (random-uuid))
        fired (atom [])]
    (fs/create-dirs root)
    (try
      (with-redefs [hooks/fire! (fn [_ trigger vars] (swap! fired conj [trigger vars]) [])]
        (tools/run-tool {:branch {:id "B1"} :root root :tool-name "write_file"
                         :args {:path "a.txt" :content "hi\n"}}))
      (is (= [[:after-write {:path "a.txt"}]] @fired))
      (finally (fs/delete-tree root)))))

(deftest a-run-end-hook-fires-once-with-the-status
  (let [fired (atom [])]
    (with-redefs [hooks/fire! (fn [ctx trigger vars] (swap! fired conj [(:run-id ctx) trigger vars]) [])
                  runs/get-run (fn [_ id] {:id id :status "completed"})]
      (hooks/run-started! {:conn :c :run-id "r9" :root "/p"})
      (hooks/run-ended! "r9")
      (hooks/run-ended! "r9")
      (is (= [["r9" :run-start {}] ["r9" :run-end {:status "completed"}]] @fired)
          "the second end is a no-op: the run was forgotten"))))

(deftest a-hook-message-is-a-notice-not-an-order
  (is (contains? (gates/threshold :directive-notice-issuers) "hook")
      "it rides along with whatever steer fires instead of outranking every gate")
  (is (str/includes? (prompt/render "directive-notice" {:issuer "hook" :text "t"})
                     "hook")))
