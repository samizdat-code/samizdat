;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.corrections
  "Corrections: guidance a person asked for, shown to the model when what it
  is doing matches (karamazov-05z0.4, after autolith's /complain).

  A rule is {:instruction text :when {...} :priority n}, keyed by id. The
  predicates in :when are ANDed and the values inside one are ORed:

    :output-contains  literal, case-insensitive, searched ONLY in the
                      model's own recent turns — what it said and the calls
                      it wrote — never a tool result, so a file or a page
                      carrying the text cannot fire a rule
    :tools            the tool of the last turn
    :roles            the branch's role
    :events           how the last turn went: success, failure, mechanics…

  An empty :when always applies. Rules come from two places: the person's
  (samizdat.layers \"corrections\", following them across projects) under
  the project's (the :corrections policy, versioned with the workflow).

  Mechanism only. What the block says is prompts/corrections-block.md, the
  bounds are gates.edn :corrections, and drafting a rule from a complaint
  takes the model call as an argument."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.prompt :as prompt]))

(def ^:private predicates #{:output-contains :tools :roles :events})

;; --- facts ------------------------------------------------------------------

(defn facts
  "What a rule can match about `branch`: its last `:window` assistant turns,
  the last turn's tool and outcome, and its role."
  [branch {:keys [window]}]
  (let [said (->> (:messages branch)
                  (filter #(= "assistant" (str (:role %))))
                  (map (comp str :content))
                  (take-last window))
        last-turn (last (:turns branch))]
    {:outputs (mapv str/lower-case said)
     :tool (some-> (:tool last-turn) str)
     :event (some-> (:category last-turn) name)
     :role (some-> (:role branch) name)}))

(defn- names [vs] (set (map #(if (keyword? %) (name %) (str %)) vs)))

(defn- holds? [[k vs] {:keys [outputs tool event role]}]
  (case k
    :output-contains (some (fn [lit] (let [l (str/lower-case (str lit))]
                                       (some #(str/includes? % l) outputs)))
                           vs)
    :tools (contains? (names vs) tool)
    :roles (contains? (names vs) role)
    :events (contains? (names vs) event)
    false))

(defn matches?
  "Whether every predicate of `rule`'s :when holds over `facts`."
  [rule facts]
  (every? #(holds? % facts) (:when rule)))

(defn matching
  "The rules that apply, highest :priority first; at most `:max-shown` of
  them when a policy is given."
  ([rules facts] (matching rules facts nil))
  ([rules facts {:keys [max-shown]}]
   (cond->> (->> rules
                 (filter #(matches? % facts))
                 (sort-by #(- (or (:priority %) 0))))
     max-shown (take max-shown))))

;; --- the rule set -----------------------------------------------------------

(defn rules
  "A corrections body's rules as a seq, each with its :id."
  [body]
  (for [[id r] (:corrections body)] (assoc r :id (str id))))

(defn combine
  "The person's rules under the project's: a project rule of the same id
  wins."
  [person project]
  (merge (:corrections person) (:corrections project)))

(defn problems
  "What is wrong with `body` as a corrections table, as data — nil when
  nothing is: [{:id :problem :limit}] with :problem one of :not-a-map,
  :not-a-rule, :no-instruction, :instruction-too-long, :when-not-a-map,
  :unknown-predicate (with :predicate), :value-not-a-vector,
  :trigger-not-a-short-literal. The words are prompts/corrections-problem.md."
  [body {:keys [max-instruction-chars max-trigger-chars]}]
  (let [m (:corrections body)]
    (if-not (map? m)
      [{:problem :not-a-map}]
      (not-empty
       (vec
        (for [[id {:keys [instruction] :as r}] m
              :let [w (when (map? r) (:when r))
                    unknown (first (remove predicates (keys w)))
                    bad (cond
                          (not (map? r)) {:problem :not-a-rule}
                          (str/blank? (str instruction)) {:problem :no-instruction}
                          (> (count (str instruction)) max-instruction-chars)
                          {:problem :instruction-too-long :limit max-instruction-chars}
                          (and (some? (:when r)) (not (map? (:when r)))) {:problem :when-not-a-map}
                          unknown {:problem :unknown-predicate :predicate unknown}
                          (some #(not (sequential? %)) (vals w)) {:problem :value-not-a-vector}
                          (some #(or (not (string? %)) (str/blank? %)
                                     (> (count %) max-trigger-chars))
                                (:output-contains w))
                          {:problem :trigger-not-a-short-literal :limit max-trigger-chars})]
              :when bad]
          (assoc bad :id (str id))))))))

(defn describe
  "`problems` as the text a refused save reports."
  [ps]
  (when (seq ps)
    (str/trim (prompt/render "corrections-problem"
                             {:not-a-map (= :not-a-map (:problem (first ps)))
                              :problems (mapv (fn [{:keys [problem predicate] :as p}]
                                                (assoc p (keyword (name problem)) true
                                                       :predicate (some-> predicate pr-str)
                                                       :predicates (str/join ", " (sort (map name predicates)))))
                                              ps)}))))

;; --- drafting from a complaint ----------------------------------------------

(defn- read-draft [reply]
  (try
    (let [s (str reply)
          i (str/index-of s "{")
          j (str/last-index-of s "}")
          v (when (and i j (< i j)) (edn/read-string (subs s i (inc j))))]
      (when (and (map? v) (not (str/blank? (str (:instruction v)))))
        v))
    (catch Throwable _ nil)))

(defn draft!
  "A rule drafted from a person's `complaint` by `complete` — (fn [prompt]
  -> reply text) — and checked against `outputs`, the model turns it was
  about: the trigger has to occur in one of them, or the rule would never
  fire. A miss is sent back as repair up to `:max-attempts` times; the last
  draft then comes back with :matched? false, for the person to confirm or
  drop. `render` turns {:complaint :outputs :miss} into the prompt."
  [complete {:keys [complaint outputs render]} {:keys [max-attempts]}]
  (let [lowered (map str/lower-case outputs)]
    (loop [n 1, miss nil, last-draft nil]
      (let [d (read-draft (complete (render {:complaint complaint :outputs outputs :miss miss})))
            trigger (some-> (:trigger d) str str/trim not-empty)
            hit? (boolean (and trigger
                               (some #(str/includes? % (str/lower-case trigger)) lowered)))
            rule (when d
                   {:id (or (some-> (:id d) str str/trim not-empty) "correction")
                    :instruction (str/trim (str (:instruction d)))
                    :when (if trigger {:output-contains [trigger]} {})
                    :matched? hit?})]
        (cond
          hit? rule
          (< n max-attempts) (recur (inc n) (or trigger "(no trigger)") (or rule last-draft))
          :else (or rule last-draft {:error :no-draft}))))))
