;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.code-metrics-test
  "What a run makes worse, measured with jolt-metrics against where the tree started.
  A project's existing complexity is not the run's to answer for."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.beam :as beam]
            [samizdat.agent.code-metrics :as cm]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.ship :as ship]
            [samizdat.llm.client :as llm]
            [samizdat.store.db :as db]
            [samizdat.agent.select :as select]
            [clojure.java.shell :as sh]))

(defn- branches [n]
  (apply str (for [i (range n)] (str "(= x " i ") " i " "))))

(defn- tangled
  "A function well over the critical cyclomatic limit."
  [name n]
  (str "(defn " name " [x]\n  (cond " (branches n) ":else 0))\n"))

(def ^:private plain "(ns a)\n(defn small [] 1)\n")
(def ^:private policy (gates/threshold :code-metrics))

(deftest what-the-run-introduced-is-found
  (let [found (cm/findings policy {"a.clj" plain} {"a.clj" (str plain (tangled "big" 30))})]
    (is (= ["big"] (map :name found)))
    (is (= :critical (:severity (first found))))
    (is (= "a.clj" (:path (first found))))))

(deftest what-was-already-there-is-not
  (let [old (str plain (tangled "old" 30))]
    (testing "untouched"
      (is (empty? (cm/findings policy {"a.clj" old} {"a.clj" (str old "(defn more [] 2)\n")}))))
    (testing "made worse is the run's"
      (is (= ["old"] (map :name (cm/findings policy {"a.clj" old}
                                             {"a.clj" (str plain (tangled "old" 45))})))))
    (testing "made better is not"
      (is (empty? (cm/findings policy {"a.clj" old} {"a.clj" (str plain (tangled "old" 25))}))))))

(deftest a-new-file-counts-and-a-warning-does-not
  (is (= ["big"] (map :name (cm/findings policy {} {"b.clj" (str "(ns b)\n" (tangled "big" 30))}))))
  (is (empty? (cm/findings policy {} {"b.clj" (str "(ns b)\n" (tangled "medium" 12))}))
      "over the warning limit, under the critical one"))

(deftest what-cannot-be-read-is-left-alone
  ;; A half-written file is another gate's business, and must not throw on the write path.
  (is (empty? (cm/findings policy {} {"bad.clj" "(defn f [] (+ 1"})))
  (is (empty? (cm/findings policy {} {"notes.md" "# not clojure"})))
  (is (empty? (cm/findings policy {} {}))))

(deftest the-report-names-where-and-what
  (let [text (cm/report policy (cm/findings policy {} {"b.clj" (str "(ns b)\n" (tangled "big" 30))}))]
    (is (str/includes? text "b.clj"))
    (is (str/includes? text "big"))
    (is (str/includes? text "cyclomatic"))))

(deftest it-reads-the-tree-against-the-baseline
  (let [root (str "/tmp/samizdat-metrics-" (random-uuid))
        git (fn [& args] (apply sh/sh (concat ["git"] args [:dir root])))]
    (fs/create-dirs root)
    (try
      (spit (str root "/a.clj") (str plain (tangled "old" 30)))
      (git "init" "-q") (git "add" "-A")
      (git "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qm" "base")
      (let [base (gitdiff/baseline root)]
        (is (empty? (cm/of-tree root base policy)))
        (spit (str root "/b.clj") (str "(ns b)\n" (tangled "fresh" 30)))
        (is (= ["fresh"] (map :name (cm/of-tree root base policy)))))
      (finally (fs/delete-tree root))))
  (testing "a link out of the project is not read"
    (let [base (str "/tmp/samizdat-metrics-" (random-uuid))
          root (str base "/p")
          git (fn [& args] (apply sh/sh (concat ["git"] args [:dir root])))]
      (fs/create-dirs root)
      (try
        (spit (str root "/a.clj") plain)
        (spit (str base "/out.clj") (str "(ns out)\n" (tangled "outside" 30)))
        (git "init" "-q") (git "add" "-A")
        (git "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qm" "base")
        (let [b (gitdiff/baseline root)]
          (sh/sh "ln" "-s" (str base "/out.clj") "linked.clj" :dir root)
          (is (empty? (cm/of-tree root b policy))))
        (finally (fs/delete-tree base)))))
  (testing "no baseline, nothing to compare"
    (is (empty? (cm/of-tree "/tmp" nil policy)))))

(defn- ctx [m] {:branch (merge (state/new-branch {:id "B1" :problem "p"}) {:role :implementer} m) :max-turns 300})
(defn- fired [c] (set (map :gate (arbiter/eligible c))))
(def ^:private found [{:path "a.clj" :name "big" :severity :critical}])

(deftest the-gate
  (testing "speaks when the run made something worse, and says what"
    (is (contains? (fired (ctx {:metric-findings found})) :metrics-worse))
    (let [d (arbiter/decide (ctx {:metric-findings found :metric-report "a.clj big"}))]
      (is (= :metrics-worse (:gate d)))
      (is (str/includes? (:message d) "a.clj big"))))
  (testing "not otherwise"
    (is (not (contains? (fired (ctx {})) :metrics-worse)))
    (is (not (contains? (fired (ctx {:metric-findings []})) :metrics-worse))))
  (testing "not against a clock, where cleaning up costs what is left"
    (is (not (contains? (fired (assoc (ctx {:metric-findings found}) :time-used 0.4)) :metrics-worse))))
  (testing "a limited number of times, however it settles"
    (let [n (gates/threshold :max-metric-steers)
          told (vec (mapcat (fn [t] [{:gate :metrics-worse :turn t} {:gate :metrics-worse :turn t :settled :met}])
                            (range n)))]
      (is (contains? (fired (ctx {:metric-findings found :gate-history (vec (take 2 told))})) :metrics-worse))
      (is (not (contains? (fired (ctx {:metric-findings found :gate-history told})) :metrics-worse)))))
  (testing "a write answers it"
    (is (contains? (:metrics-worse (gates/tool-vocab :settle-called)) "edit_file"))))

(defn- call [tool args]
  {:content (str "```tool-call\n" (json/write-str {:name tool :args args}) "\n```") :finish-reason "stop"})

(deftest a-real-run-that-writes-tangled-code-hears-about-it
  (let [root (str "/tmp/samizdat-metrics-run-" (System/nanoTime))
        git (fn [& args] (apply sh/sh (concat ["git"] args [:dir root])))
        conn (db/open! ":memory:")
        said (atom [])
        model (fn [_ _ messages & _]
                (let [all (str/join "\n" (map (comp str :content) messages))]
                  (swap! said conj all)
                  (cond
                    (not (str/includes? all "Created sz-")) (call "task" {"action" "create" "title" "do it"})
                    (not (str/includes? all "Claimed sz-")) (call "task" {"action" "claim" "id" (re-find #"sz-[0-9a-f]+" all)})
                    (not (str/includes? all "Wrote ")) (call "write_file" {"path" "src/a.clj" "content" (str "(ns a)\n" (tangled "big" 30))})
                    :else (call "read_file" {"path" "src/a.clj"}))))]
    (.mkdirs (java.io.File. (str root "/src")))
    (spit (str root "/README.md") "x\n")
    (git "init" "-q") (git "add" "-A")
    (git "-c" "user.name=t" "-c" "user.email=t@t" "commit" "-qm" "base")
    (with-redefs [llm/chat model select/triage! (constantly nil)]
      (beam/run! {:conn conn :config {:run {:loop "loop" :root root}}
                  :llm-adapter :a :llm-config {:max-tokens 16384}
                  :problem "Write a." :max-turns 8 :beam-width 1 :oversight? false}))
    (is (seq (db/fetch conn ["SELECT id FROM gate_firings WHERE gate = 'metrics-worse'"]))
        (pr-str (db/fetch conn ["SELECT gate FROM gate_firings"])))
    
    (let [msg (:message (first (db/fetch conn ["SELECT message FROM gate_firings WHERE gate = 'metrics-worse'"])))]
      (is (str/includes? (str msg) "cyclomatic") (pr-str msg)))
    (is (some #(str/includes? % "cyclomatic") @said) "and the model heard it")))

;; --- unusual changes (metrics.entropy) ----------------------------------------

(deftest an-unusual-change-stands-out-from-the-history
  (let [history (mapv (fn [d] {:delta d}) (take 40 (cycle [0.1 -0.2 0.15 -0.05 0.2])))]
    (is (nil? (cm/unusual history 0.1)) "an ordinary change")
    (let [u (cm/unusual history 25.0)]
      (is (> (:z u) 3))
      (is (= 40 (:commits u))))
    (is (some? (cm/unusual history -25.0)) "a removal counts too")
    (is (nil? (cm/unusual (take 5 history) 25.0)) "too little history to tell")))

(deftest the-change-entropy-is-over-source-files
  (let [policy {:extensions [".clj"]}
        a "(defn alpha [beta] (gamma beta delta))"]
    (is (zero? (cm/change-entropy policy {"a.clj" a} {"a.clj" a})))
    (is (pos? (cm/change-entropy policy {} {"a.clj" a})) "a new file adds")
    (is (neg? (cm/change-entropy policy {"a.clj" a} {})) "a deleted one removes")
    (is (zero? (cm/change-entropy policy {} {"notes.md" a})) "only source counts")))

(deftest an-unusual-change-sends-done-back-once
  (let [block (fn [m] (ship/ship-gate-block
                       (merge {:answer "Built it and ran the tests." :problem "p" :evidence []
                               :uncovered-numbers [] :can-write? true :done-streak 0}
                              m)))]
    (is (str/includes? (str (block {:unusual-change true :review-unusual "review your diff"}))
                       "review your diff"))
    (is (nil? (block {:unusual-change true :review-unusual "review your diff" :done-streak 1}))
        "a second done finishes")
    (is (nil? (block {:unusual-change true :review-unusual "x" :verdict? true})) "not a verdict")))

(deftest an-unusual-change-is-read-from-a-real-history
  (let [root (str (fs/create-temp-dir))
        git (fn [& args] (apply sh/sh "git" "-C" root args))
        policy (assoc (gates/threshold :code-metrics) :entropy-commits 500)]
    (git "init" "-q")
    (doseq [i (range 15)]
      (spit (str root "/a.clj") (str "(ns a)\n" (apply str (for [j (range (inc i))] (str "(defn f" j " [x] (inc x))\n")))))
      (git "add" "-A")
      (git "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" (str "c" i)))
    (let [base (clojure.string/trim (:out (git "rev-parse" "HEAD")))]
      (is (nil? (cm/unusual-change root base policy)) "no change, nothing unusual")
      (spit (str root "/b.clj")
            (str "(ns b)\n" (apply str (for [w (range 400)] (str "(def word" w "thing" (char (+ 97 (mod w 26))) "zeta" w " \"" w "\")\n")))))
      (let [u (cm/unusual-change root base policy)]
        (is (some? u) "a change far bigger than any commit before it")
        (is (= ["b.clj"] (:files u)))
        (is (pos? (:delta u)))))))

;; --- unusual files (jolt-metrics 0.3.2) ------------------------------------------

(defn- ordinary-file [i]
  (str "(ns app.part" i ")\n"
       (apply str (for [j (range 30)]
                    (str "(defn handle-request" j " [request response]"
                         " (assoc response :status (get request :status) :body (str request)))\n")))))

(defn- odd-file []
  (apply str (for [w (range 300)]
               (str "quixotic" w " zephyr" (mod w 7) " xylophone" (mod w 11) " "))))

(deftest a-file-unlike-the-project-is-named
  (let [sources (merge (into {} (for [i (range 15)] [(str "src/p" i ".clj") (ordinary-file i)]))
                       {"src/odd.clj" (odd-file)})]
    (is (= ["src/odd.clj"] (map :path (cm/unusual-files-in sources ["src/odd.clj" "src/p1.clj"]))))
    (is (empty? (cm/unusual-files-in sources ["src/p1.clj"])) "only changed files count")
    (is (empty? (cm/unusual-files-in (select-keys sources ["src/p1.clj" "src/odd.clj"]) ["src/odd.clj"]))
        "too few files to tell")))

(deftest an-unusual-file-is-read-from-a-real-tree
  (let [root (str (fs/create-temp-dir))
        git (fn [& args] (apply sh/sh "git" "-C" root args))
        policy (gates/threshold :code-metrics)]
    (git "init" "-q")
    (fs/create-dirs (str root "/src"))
    (doseq [i (range 15)] (spit (str root "/src/p" i ".py") (ordinary-file i)))
    (git "add" "-A")
    (git "-c" "user.email=t@t" "-c" "user.name=t" "commit" "-qm" "base")
    (let [base (clojure.string/trim (:out (git "rev-parse" "HEAD")))]
      (is (nil? (cm/unusual-file-change root base policy)))
      (spit (str root "/src/odd.py") (odd-file))
      (is (= ["src/odd.py"] (map :path (cm/unusual-file-change root base policy)))
          "a Python file too: the check counts words"))))

(deftest the-review-names-whichever-signal-fired
  (let [r (fn [m] (samizdat.prompt/render "unusual-change" m))]
    (is (str/includes? (r {:odd true :odd-files "`src/odd.py` (4.2)"}) "These files you changed read unlike"))
    (is (not (str/includes? (r {:odd true :odd-files "x"}) "standard deviations from its last")))
    (is (str/includes? (r {:change true :z "3.4" :added true :commits 40 :files "`a.clj`"}) "adds far more"))))
