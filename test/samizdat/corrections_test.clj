;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.corrections-test
  "Corrections: a person's complaint turned into a rule with a literal
  trigger, shown to the model when the trigger fires (karamazov-05z0.4)."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.corrections :as corr]
            [samizdat.agent.loop]
            [samizdat.agent.tools]
            [samizdat.agent.tools.base]
            [samizdat.api.corrections]
            [samizdat.layers]
            [samizdat.store.db]
            [samizdat.store.journal]
            [samizdat.store.runs]
            [samizdat.userspace]))

(def ^:private policy
  {:window 4 :max-shown 2 :max-instruction-chars 200 :max-trigger-chars 40
   :max-attempts 3})

(defn- branch [& {:keys [said results role turns]}]
  {:id "B1" :role role
   :turns (or turns [])
   :messages (vec (concat [{:role "system" :content "sys"}
                           {:role "user" :content "problem"}]
                          (mapcat (fn [s r] [{:role "assistant" :content s}
                                             {:role "user" :content r}])
                                  said (concat results (repeat "ok")))))})

(deftest a-trigger-matches-only-what-the-model-wrote
  (let [r {:id "no-python" :instruction "Use the eval tool, not python3 -c."
           :when {:output-contains ["python3 -c"]}}
        b (branch :said ["I will run python3 -C 'print(1)'"])]
    (is (= ["no-python"] (map :id (corr/matching [r] (corr/facts b policy))))
        "case-insensitive, in an assistant turn")
    (testing "a tool result carrying the text does not fire it"
      (let [b (branch :said ["read the file"]
                      :results ["#!/bin/sh\npython3 -c 'x'"])]
        (is (empty? (corr/matching [r] (corr/facts b policy))))))
    (testing "only the last :window assistant turns count"
      (let [b (branch :said ["python3 -c 1" "a" "b" "c" "d"])]
        (is (empty? (corr/matching [r] (corr/facts b policy))))))))

(deftest predicates-are-anded-and-values-ored
  (let [r {:id "r" :instruction "i"
           :when {:tools ["shell" "eval"] :roles ["implementor"]}}
        f (fn [tool role] (corr/facts (branch :said ["x"] :role role
                                              :turns [{:turn 1 :tool tool :category :success}])
                                      policy))]
    (is (seq (corr/matching [r] (f "shell" :implementor))))
    (is (seq (corr/matching [r] (f "eval" "implementor"))) "role as keyword or string")
    (is (empty? (corr/matching [r] (f "read_file" :implementor))))
    (is (empty? (corr/matching [r] (f "shell" :supervisor))))
    (testing "an event names how the last turn went"
      (let [r {:id "e" :instruction "i" :when {:events ["failure"]}}
            b (branch :said ["x"] :turns [{:turn 1 :tool "shell" :category :failure}])]
        (is (seq (corr/matching [r] (corr/facts b policy))))))
    (testing "an empty :when always applies"
      (is (seq (corr/matching [{:id "a" :instruction "i" :when {}}] (f "x" nil)))))))

(deftest the-rules-shown-are-bounded-and-ordered
  (let [rs (for [i (range 4)] {:id (str "r" i) :instruction "i" :priority i :when {}})
        shown (corr/matching rs (corr/facts (branch :said ["x"]) policy) policy)]
    (is (= ["r3" "r2"] (map :id shown)) "highest priority first, :max-shown of them")))

(deftest a-malformed-rule-is-named
  (let [kinds (fn [body] (set (map :problem (corr/problems body policy))))]
    (is (nil? (corr/problems {:corrections {"ok" {:instruction "do x" :when {:tools ["shell"]}}}} policy)))
    (is (= #{:no-instruction} (kinds {:corrections {"a" {:when {}}}})))
    (is (= #{:unknown-predicate} (kinds {:corrections {"a" {:instruction "x" :when {:pathz ["a"]}}}})))
    (is (= #{:trigger-not-a-short-literal}
           (kinds {:corrections {"a" {:instruction "x"
                                      :when {:output-contains [(apply str (repeat 41 "a"))]}}}}))
        "a trigger is a literal, and a short one")
    (is (= #{:not-a-map} (kinds {:corrections []})))
    (testing "and said in words a refused save can report"
      (let [text (corr/describe (corr/problems {:corrections {"a" {:instruction "x" :when {:pathz ["a"]}}}}
                                               policy))]
        (is (str/includes? text "`a`"))
        (is (str/includes? text ":pathz"))
        (is (str/includes? text "output-contains") "names the predicates there are")))))

(deftest a-rule-set-is-the-person-s-under-the-project-s
  (is (= {"a" {:instruction "project" :when {}} "b" {:instruction "person" :when {}}}
         (corr/combine {:corrections {"a" {:instruction "person" :when {}}
                                      "b" {:instruction "person" :when {}}}}
                       {:corrections {"a" {:instruction "project" :when {}}}}))))

(deftest a-draft-is-checked-against-what-was-actually-said
  ;; The trigger has to occur in the outputs the complaint was about, or a
  ;; saved rule would never fire. A miss goes back to the model as repair.
  (let [outputs ["Here is a long preamble before the answer." "Sure! Let me explain."]
        replies (atom ["{:id \"x\" :instruction \"Lead with the result.\" :trigger \"In summary\"}"
                       "{:id \"lead-with-result\" :instruction \"Lead with the result.\" :trigger \"long preamble\"}"])
        asked (atom [])
        complete (fn [prompt] (swap! asked conj prompt) (let [r (first @replies)] (swap! replies rest) r))
        d (corr/draft! complete {:complaint "stop the preambles" :outputs outputs
                                 :render (fn [ctx] (pr-str ctx))}
                       policy)]
    (is (= "lead-with-result" (:id d)))
    (is (true? (:matched? d)))
    (is (= {:output-contains ["long preamble"]} (:when d)))
    (is (= 2 (count @asked)))
    (is (str/includes? (second @asked) "In summary") "the miss is named in the retry")
    (testing "no attempt matches: the last draft comes back unconfirmed"
      (let [d (corr/draft! (constantly "{:id \"y\" :instruction \"i\" :trigger \"zzz\"}")
                           {:complaint "c" :outputs outputs :render pr-str} policy)]
        (is (false? (:matched? d)))
        (is (= "y" (:id d)))))
    (testing "a reply that is not a draft at all is an error, not a rule"
      (let [d (corr/draft! (constantly "I think you should be nicer")
                           {:complaint "c" :outputs outputs :render pr-str} policy)]
        (is (= :no-draft (:error d)))))))

(deftest the-block-a-turn-carries-is-the-person-s-and-the-project-s-matches
  (let [dir (doto (java.io.File/createTempFile "corrections" "") (.delete) (.mkdirs))]
    (try
      (spit (str dir "/corrections.edn")
            (pr-str {:corrections {"person" {:instruction "Answer in English."
                                             :when {:output-contains ["bonjour"]}}
                                   "both" {:instruction "person wording" :when {}}}}))
      (samizdat.layers/forget-files!)
      (with-redefs [samizdat.layers/global-dir (constantly (str dir))
                    samizdat.userspace/edn-body
                    (fn [kind nm]
                      (when (= [:policy "corrections"] [kind nm])
                        {:corrections {"both" {:instruction "Keep replies short." :when {}}}}))]
        (let [text (samizdat.agent.loop/corrections-text
                    (branch :said ["Bonjour, je vais lire le fichier"]))]
          (is (str/includes? text "Answer in English."))
          (is (str/includes? text "Keep replies short.") "the project's rule of the same id")
          (is (not (str/includes? text "person wording"))))
        (let [text (samizdat.agent.loop/corrections-text (branch :said ["hello"]))]
          (is (str/includes? text "Keep replies short.") "an empty :when always applies")
          (is (not (str/includes? text "Answer in English.")) "the trigger did not fire")))
      (finally
        (samizdat.layers/forget-files!)
        (doseq [f (reverse (file-seq dir))] (.delete f))))))

(deftest a-complaint-is-drafted-from-the-run-s-own-turns-and-saved-where-asked
  (let [c (samizdat.store.db/open! ":memory:")
        rid (samizdat.store.runs/start-run! c {:problem "p"})
        dir (doto (java.io.File/createTempFile "corrections" "") (.delete) (.mkdirs))
        prompts (atom [])]
    (try
      (doseq [[t text] [[1 "Let me explain the plan at length first."]
                        [2 "Here is the answer: 42"]]]
        (samizdat.store.journal/record-turn!
         c rid {:branch-id "B1" :turn t :tool-name "shell" :category :success
                :result "" :assistant-text text}))
      (let [d (samizdat.api.corrections/draft!
               c {:complaint "stop explaining the plan, just do it" :run-id rid}
               (fn [p] (swap! prompts conj p)
                 "{:id \"no-plan-talk\" :instruction \"Act; do not narrate the plan.\" :trigger \"explain the plan\"}"))]
        (is (str/includes? (first @prompts) "Let me explain the plan at length first.")
            "the prompt carries the run's own turns")
        (is (= "no-plan-talk" (:id d)))
        (is (true? (:matched? d)))
        (testing "saved for the person, it lands in their global layer"
          (with-redefs [samizdat.layers/global-dir (constantly (str dir))]
            (is (= :saved (:status (samizdat.api.corrections/save! d :person))))
            (is (= "Act; do not narrate the plan."
                   (get-in (clojure.edn/read-string (slurp (str dir "/corrections.edn")))
                           [:corrections "no-plan-talk" :instruction])))))
        (testing "saved for the project, it is a new version of the policy"
          (samizdat.userspace/bind! c)
          (try
            (is (= :saved (:status (samizdat.api.corrections/save! d :project))))
            (is (contains? (:corrections (samizdat.userspace/edn-body :policy "corrections"))
                           "no-plan-talk"))
            (is (str/starts-with? (samizdat.userspace/body :policy "corrections") ";;")
                "the file's explanation survives the save")
            (finally (samizdat.userspace/unbind!))))
        (testing "a rule that would not pass the save check is refused, not stored"
          (is (= :refused (:status (samizdat.api.corrections/save!
                                    (assoc d :instruction "") :person))))))
      (finally
        (samizdat.store.db/close c)
        (samizdat.layers/forget-files!)
        (doseq [f (reverse (file-seq dir))] (.delete f))))))

(deftest a-run-s-modes-are-words-in-its-context-block
  ;; karamazov-05z0.18: a mode is a prompt overlay chosen at run start, in the
  ;; per-turn block rather than the system prompt so the prefix stays cached.
  (let [text (samizdat.agent.loop/modes-text ["hurry" "nonsense"])]
    (is (str/includes? text "smallest change") "the shipped hurry mode")
    (is (str/includes? text "Modes this run was started with")))
  (is (nil? (samizdat.agent.loop/modes-text [])))
  (is (nil? (samizdat.agent.loop/modes-text ["nonsense"])) "an unknown name is no mode"))

(deftest saving-rules-says-nothing-reads-them
  ;; karamazov-05z0.22: rules.edn is checked and versioned and fired by
  ;; nothing, so a save says so rather than letting the agent think its rule
  ;; is in force.
  (let [c (samizdat.store.db/open! ":memory:")]
    (try
      (samizdat.userspace/bind! c)
      (let [r (samizdat.agent.tools.base/run-tool
               {:tool-name "policy" :branch {:id "SUP"}
                :args {"action" "save" "name" "rules" "edn" "{:rules []}" "rationale" "test"}})]
        (is (str/includes? (str (:result r)) "nothing reads rules.edn") (str (:result r))))
      (finally (samizdat.userspace/unbind!) (samizdat.store.db/close c)))))
