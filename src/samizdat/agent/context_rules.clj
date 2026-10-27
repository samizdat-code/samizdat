;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.context-rules
  "Bounded, symbolic contextual-rule validation and selection."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.symbolic :as symbolic]
            [samizdat.util :as util]))

(def ^:private selectors
  #{:workspace :paths :languages :tools :namespaces :roles :tasks :workflows
    :diagnostics :events :output-contains})

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :context-rule/error kind))))

(defn- values [policy field value]
  (let [limit (:max-selector-values policy)
        char-limit (:max-value-chars policy)]
    (when-not (and (vector? value) (seq value) (<= (count value) limit)
                   (every? #(and (string? %) (not (str/blank? %))
                                 (<= (count %) char-limit)) value))
      (fail :invalid-selector-values {:field field :limit limit :char-limit char-limit}))
    value))

(defn- alternative-subsumes? [field general specific]
  (case field
    :paths (str/starts-with? specific general)
    :output-contains (str/includes? (str/lower-case specific) (str/lower-case general))
    (symbolic/subsumes? general specific)))

(defn- selector-subsumes? [general specific]
  (every? (fn [[field alternatives]]
            (and (contains? specific field)
                 (every? (fn [general-value]
                           (some #(alternative-subsumes? field general-value %)
                                 (get specific field)))
                         alternatives)))
          general))

(defn- shadowed? [a b]
  (and (= (:instruction a) (:instruction b))
       (>= (long (:priority a)) (long (:priority b)))
       (selector-subsumes? (:when a) (:when b))))

(defn- analysis-cost [a b]
  (reduce + 0
          (map (fn [[field alternatives]]
                 (* (count alternatives) (count (get (:when b) field))))
               (:when a))))

(defn- validate-pairs! [rules policy]
  (let [remaining (atom (:max-analysis-comparisons policy))]
    (doseq [[i a] (map-indexed vector rules)
            [j b] (map-indexed vector rules)
            :when (< i j)]
      (when (neg? (swap! remaining - (max (analysis-cost a b) (analysis-cost b a))))
        (fail :analysis-limit {:limit (:max-analysis-comparisons policy)}))
      (when (and (= (:when a) (:when b))
                 (= (:priority a) (:priority b)))
        (fail :ambiguous-rules {:ids [(:id a) (:id b)]}))
      (cond
        (shadowed? a b) (fail :shadowed-rule {:id (:id b) :shadowed-by (:id a)})
        (shadowed? b a) (fail :shadowed-rule {:id (:id a) :shadowed-by (:id b)})))))

(defn compile-rules [{:keys [rules]} policy]
  (when-not (and (vector? rules) (<= (count rules) (:max-rules policy)))
    (fail :invalid-rule-count {:limit (:max-rules policy)}))
  (let [compiled
        (mapv (fn [{:keys [id instruction priority lifetime]
                    :or {priority 0 lifetime :while-relevant} :as rule}]
                (when-not (and (string? id) (not (str/blank? id))
                               (<= (count id) (:max-value-chars policy)))
                  (fail :invalid-id {:rule rule}))
                (let [match-on (:when rule)
                      match-on (if (string? (:workspace match-on))
                                 (assoc match-on :workspace [(:workspace match-on)])
                                 match-on)]
                  (when-not (and (map? match-on) (seq match-on)
                                 (every? selectors (keys match-on)))
                    (fail :unknown-selector {:selectors (keys match-on)}))
                  (when (and (:workspace match-on)
                             (not (str/starts-with? (first (:workspace match-on)) "/")))
                    (fail :invalid-workspace {:workspace (:workspace match-on)}))
                  (doseq [[field value] match-on] (values policy field value))
                  (when-not (and (string? instruction) (not (str/blank? instruction)))
                    (fail :invalid-contribution {:id id}))
                  (when-not (contains? #{:turn :while-relevant :persistent} lifetime)
                    (fail :invalid-lifetime {:id id :lifetime lifetime}))
                  (when-not (integer? priority)
                    (fail :invalid-priority {:id id}))
                  {:id id :when match-on :instruction instruction
                   :priority priority :lifetime lifetime}))
              rules)]
    (when-not (= (count compiled) (count (distinct (map :id compiled))))
      (fail :duplicate-rule {}))
    (validate-pairs! compiled policy)
    compiled))

(defn language-for-path [path policy]
  (let [name (last (str/split (str path) #"/"))
        extension (some-> (re-find #"\.([^.]+)$" name) second str/lower-case)]
    (get (:language-extensions policy) extension)))

(defn note-facts [facts additions generation policy]
  (if (not= generation (:generation facts))
    facts
    (reduce (fn [acc [field additions]]
              (if (empty? additions)
                acc
                (let [additions (values policy field (vec additions))]
                  (assoc acc field
                         (vec (take (:max-metadata-values policy)
                                    (distinct (concat additions (get acc field)))))))))
            (assoc facts :generation generation)
            additions)))

(defn- fact-values [facts field]
  (let [value (get facts field)]
    (if (sequential? value) value (when (some? value) [value]))))

(defn- matches-field? [field expected facts]
  (let [actual (fact-values facts (if (= :output-contains field) :outputs field))]
    (boolean
     (some (fn [value]
             (some (fn [fact]
                     (case field
                       :paths (str/starts-with? (str fact) value)
                       :output-contains (str/includes? (str/lower-case (str fact))
                                                       (str/lower-case value))
                       (= fact value)))
                   actual))
           expected))))

(defn- matches? [rule facts]
  (every? (fn [[field expected]] (matches-field? field expected facts))
          (:when rule)))

(defn resolve-contributions [rules facts policy]
  (let [char-limit (:max-contribution-chars policy)
        total-limit (:max-total-chars policy)]
    (->> rules
         (filter #(matches? % facts))
         (sort-by (juxt (comp - :priority) :id))
         (take (:max-contributions policy))
         (map #(assoc % :text (util/truncate-middle (:instruction %) char-limit)
                      :stable? (= :persistent (:lifetime %))))
         (reduce (fn [{:keys [total] :as acc} contribution]
                   (let [remaining (- total-limit total)]
                     (if (pos? remaining)
                       (-> acc
                           (update :contributions conj
                                   (update contribution :text util/truncate-middle remaining))
                           (update :total + (min remaining (count (:text contribution)))))
                       acc)))
                 {:contributions [] :total 0})
         :contributions)))

(defonce ^:private compiled-rules (atom nil))

(defn current-rules []
  (let [policy (gates/threshold :context-rules)
        body (or (some-> ((requiring-resolve 'samizdat.userspace/body)
                          :policy "context-rules")
                         edn/read-string)
                 {:rules []})
        cache-key [policy body]]
    (if (= cache-key (:cache-key @compiled-rules))
      (:rules @compiled-rules)
      (let [rules (compile-rules body policy)]
        (reset! compiled-rules {:cache-key cache-key :rules rules})
        rules))))

(defn validate-current! []
  (current-rules)
  nil)

(defn- output-text [message limit]
  (when (and (= "assistant" (:role message)) (string? (:content message)))
    (util/truncate-middle (:content message) limit)))

(defn- path-values [parsed shell-paths]
  (let [args (:args parsed)
        configured (map keyword (:path-args (gates/threshold :instructions)))
        direct (mapcat (fn [key]
                         (let [value (get args key)]
                           (if (sequential? value) value [value])))
                       configured)
        uri (:uri args)]
    (vec (remove str/blank?
                 (concat (map str direct)
                         (when uri [(str/replace-first (str uri) #"^[^:]+:" "")])
                         shell-paths)))))

(defn facts [ctx branch parsed result generation]
  (let [policy (gates/threshold :context-rules)
        tool (str (:name parsed))
        shell-paths (when (= "shell" tool)
                      ((requiring-resolve 'samizdat.security.policy/read-paths)
                       (get-in parsed [:args :command])))
        paths (path-values parsed shell-paths)
        role (name (or (:role branch) (:role ctx) :implementor))
        workflow (some-> (or (:workflow ctx) (get-in ctx [:config :run :loop])) str)
        task (:task branch)
        outputs (keep #(output-text % (:max-output-chars policy))
                      (take-last (:max-recent-outputs policy) (:messages branch)))
        diagnostics (keep #(when-let [value (% result)]
                            (if (or (keyword? value) (string? value)) (name value)))
                          [:category :status :reason :error :kind])
        structured-events (keep #(or (:event %) (:kind %)) (:events result))]
    {:workspace (str (:root ctx))
     :generation generation
     :paths paths
     :languages (distinct (keep #(language-for-path % policy) paths))
     :tools (if (str/blank? tool) [] [tool])
     :namespaces (distinct (remove str/blank?
                                   [(some-> (get-in parsed [:args :namespace]) str)
                                    (some-> (get-in parsed [:args :uri]) str
                                            (str/split #":") first)]))
     :roles [role]
     :tasks (distinct (remove str/blank? [(some-> (:id task) str) (some-> (:title task) str)]))
     :workflows (if workflow [workflow] [])
     :diagnostics (distinct diagnostics)
     :events (distinct (conj (mapv #(if (keyword? %) (name %) (str %)) structured-events)
                             (str "tool-" (some-> (:category result) name))))
     :outputs (vec outputs)}))

(defn note-async [branch additions generation current-generation]
  (let [policy (gates/threshold :context-rules)
        current (:context-rule-facts branch)
        base (if (= current-generation (:generation current))
               current
               {:generation current-generation})]
    (assoc branch :context-rule-facts
           (note-facts base additions generation policy))))

(defn apply-directive [branch directive current-generation]
  (let [payload ((requiring-resolve 'samizdat.store.interventions/payload) directive)
        notes (or (:execution-notes payload) [payload])]
    (reduce (fn [b {:keys [generation context-facts]}]
              (if (and generation (map? context-facts))
                (note-async b context-facts generation current-generation)
                b))
            branch notes)))

(defn boundary [ctx branch parsed result generation]
  (let [policy (gates/threshold :context-rules)
        raw (facts ctx branch parsed result generation)
        previous (:context-rule-facts branch)
        base (if (= generation (:generation previous))
               previous
               {:generation generation})
        merged (note-facts base (dissoc raw :workspace :generation) generation policy)
        facts (assoc merged :workspace (:workspace raw) :outputs (:outputs raw))
        contributions (resolve-contributions (current-rules) facts policy)
        stable (filterv :stable? contributions)
        dynamic (remove :stable? contributions)]
    {:facts facts :contributions contributions
     :texts (when (seq contributions)
              (util/truncate-middle
               ((requiring-resolve 'samizdat.prompt/render)
                "context-rules"
                {:stable stable :dynamic dynamic})
               (:max-total-chars policy)))}))
