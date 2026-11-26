;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.experiments
  "The `experiment` and `verdict` tools: how the supervisor applies selection
  pressure to the loop and finds out whether it worked.

  The supervisor could already change the loop, and the harness could already
  measure itself. What was missing was the binding between the two — a change
  was MADE, and then the next round's numbers were whatever they were, with
  nothing connecting them. That is variation without selection, which is drift.

  An experiment names a change, states what it was expected to do, and stamps
  the tally it will be judged against. The verdict compares per-turn fitness
  before and after and answers better / worse / unchanged / too early.

  The hypothesis is not decoration. A change with no stated expectation cannot
  be wrong, and a change that cannot be wrong teaches nothing whichever way the
  numbers move."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.session :as session]
            [samizdat.sim]
            [samizdat.store.sim :as sim]
            [samizdat.userspace :as userspace]))

(defn- msg [ctx] (prompt/render "experiment-tool" ctx))

(def ^:private usage (delay (msg {:usage true})))

(defn- read-size
  "\"30%\" as 0.3; nil for nothing given, :bad for anything else."
  [s]
  (when s
    (if-let [[_ n] (re-matches #"(\d+(?:\.\d+)?)%" s)]
      (let [v (/ (Double/parseDouble n) 100.0)]
        (if (pos? v) v :bad))
      :bad)))

(defn- read-prediction
  "`predicts` as {:signal :direction :by?}: \"<signal> up\" or \"<signal>
  down\", optionally followed by the size expected, \"parse-error down 30%\"
  (after Agentic Algorithm Engineering: a prediction with a size can be wrong
  in more ways than one that only says which way). The signal is one of the
  counts the fitness weighs. nil for nothing given, :bad for something that
  is not that shape."
  [s]
  (when-let [t (some-> s str str/trim not-empty)]
    (let [[sig dir size & more] (str/split (str/lower-case t) #"\s+")
          sig (keyword sig)
          by (read-size size)]
      (if (and (contains? (set (session/signal-names)) sig) (#{"up" "down"} dir)
               (not= :bad by) (empty? more))
        (cond-> {:signal sig :direction (keyword dir)} by (assoc :by by))
        :bad))))

(defn- pct
  "0.3 as \"30%\", -0.5 as \"-50%\"."
  [x]
  (let [v (* 100.0 x)]
    (str (if (== v (Math/rint v)) (long (Math/rint v)) (format "%.1f" v)) "%")))

(defn- said
  "A prediction as the supervisor wrote it."
  [{:keys [signal direction by]}]
  (str (clojure.core/name signal) " " (clojure.core/name direction)
       (when by (str " " (pct by)))))

(defn- target-of
  "The userspace version an experiment tests: the latest stored version of
  `kind`/`name` in this project, so the ledger names the edit and not only
  the idea behind it (karamazov-na2k.5)."
  [kind name]
  (when (and kind name)
    {:kind kind :name name
     :version (try (some-> (userspace/versions (keyword kind) name) last :version)
                   (catch Throwable _ nil))}))

;; How a verdict notices its target was edited again (karamazov-05z0.1).
;; Installed here because session is a leaf and this is where the target's
;; version is read in the first place.
(defn- read-cases
  "`fixes` as the battery case ids it names, in order: split on commas and
  spaces."
  [s]
  (->> (str/split (str s) #"[,\s]+") (map str/trim) (remove str/blank?) distinct vec))

(defn- sim-conn [ctx] (or (:conn ctx) (userspace/conn)))

(defn- file-attempt!
  "Put the experiment on the self-improvement memory (karamazov-amgk): under
  the problem it cites by id or names, with what it predicts, before anything
  is measured. {:problem {:id :name}} or nil with no store. Never throws into
  the tool: a record that cannot be written is not a reason to refuse the
  experiment."
  [ctx name {:keys [change hypothesis target problem fixes]}]
  (when-let [c (sim-conn ctx)]
    (try
      (let [run-id (:run-id ctx)
            pid (when problem
                  (or (sim/problem-by-ref c problem)
                      (sim/problem-id! c problem run-id)))
            pname (when pid (:name (first (filter #(= pid (:id %)) (sim/problems c)))))
            id (sim/record-attempt! c {:problem-id pid :run-id run-id :experiment name
                                       :change change :hypothesis hypothesis :target target
                                       :project (userspace/project-root) :predicted fixes})]
        (session/attempt! name id)
        {:problem (when pid {:id pid :name pname})})
      (catch Throwable _ nil))))

(defn- settle-attempt!
  "Write a verdict or a decision onto the experiment's attempt, if it has one."
  [ctx name m]
  (when-let [id (session/attempt-id name)]
    (when-let [c (sim-conn ctx)]
      (try (sim/settle-attempt! c id m)
           (sim/settle-predictions! c id)
           (catch Throwable _ nil)))))

(session/set-version-lookup!
 (fn [kind name]
   (some-> (userspace/versions (keyword kind) name) last :version)))

(defmethod base/run-tool "experiment" [{:keys [branch] :as ctx}]
  (let [name (some-> (base/arg ctx :name) str str/trim not-empty)
        change (some-> (base/arg ctx :change) str str/trim not-empty)
        hypothesis (some-> (base/arg ctx :hypothesis) str str/trim not-empty)
        predicts (read-prediction (base/arg ctx :predicts))
        target (target-of (some-> (base/arg ctx :kind) str str/trim not-empty)
                          (some-> (base/arg ctx :target) str str/trim not-empty))
        until (some-> (base/arg ctx :until) str str/trim not-empty)
        problem (some-> (base/arg ctx :problem) str str/trim not-empty)
        fixes (read-cases (base/arg ctx :fixes))]
    (cond
      (not (and name change hypothesis))
      (base/malformed branch (str (base/missing ctx :name :change :hypothesis)
                                  "\n\n" @usage))

      (= :bad predicts)
      (base/malformed branch (msg {:bad-prediction true
                                   :signals (str/join ", " (map clojure.core/name
                                                                (session/signal-names)))}))

      :else
      (try
        (session/experiment! name {:change change :hypothesis hypothesis
                                   :target target :predicts predicts})
        (let [filed (file-attempt! ctx name {:change change :hypothesis hypothesis
                                             :target target :problem problem :fixes fixes})]
        ;; When the edit should come out, on the version itself, where it
        ;; outlives this session (karamazov-05z0.7).
        (when (and until (:version target))
          (userspace/set-remove-when! (keyword (:kind target)) (:name target)
                                      (:version target) until))
        (base/ok branch (msg {:started true :name name
                              :change change :hypothesis hypothesis
                              :target (when target
                                        (str (:kind target) " " (:name target)
                                             (when (:version target) (str " v" (:version target)))))
                              :until (when (and until (:version target)) until)
                              :predicts (when predicts (said predicts))
                              :problem (when-let [{:keys [id name]} (:problem filed)]
                                         (str "P" id " " name))
                              :unfiled (and (nil? (:problem filed)) (nil? problem))
                              :fixes (when (seq fixes) (str/join ", " fixes))})
                 :progress? true))
        (catch clojure.lang.ExceptionInfo e
          (if (= :samizdat.session/too-many-open (:type (ex-data e)))
            ;; A refusal, not a crash: the supervisor made a well-formed call
            ;; and the harness declined it, which is a policy refusal like any
            ;; other and must not read as its mistake.
            (let [{:keys [open cap unsettled]} (ex-data e)]
              (base/refusal branch (msg {:too-many true :open open :cap cap
                                         :unsettled (when (seq unsettled)
                                                      (str/join ", " unsettled))})))
            (throw e)))))))

(defn- undo-target!
  "Settling a change as reverted puts the version before it back
  (karamazov-05z0.6), through the edit's own tool so a cell or a policy is
  reloaded and checked as any revert is. Only while the target is still at
  the version the experiment opened on: a target that has moved was already
  reverted by hand, or edited again, and is left alone. The text of what
  happened, or nil when the experiment named no target."
  [ctx {:keys [target stale]}]
  (let [{:keys [kind version] tname :name} target]
    (when (and kind tname version)
      (cond
        stale (msg {:undo-moved true :target (str kind " " tname) :now (:now stale)})
        (< (long version) 2) (msg {:undo-first true :target (str kind " " tname)})
        :else
        (let [r (base/run-tool (assoc ctx :tool-name (str kind)
                                      :args {"action" "revert" "name" tname
                                             "version" (str (dec (long version)))
                                             "rationale" (str "experiment settled as reverted: "
                                                              (or (base/arg ctx :why) "no reason given"))}))]
          (str (:result r)))))))

(defmethod base/run-tool "verdict" [{:keys [branch] :as ctx}]
  (let [name (some-> (base/arg ctx :name) str str/trim not-empty)
        action (some-> (base/arg ctx :action) str str/trim str/lower-case not-empty)]
    (cond
      (not name)
      (base/malformed branch (str (base/missing ctx :name) "\n\n" @usage))

      ;; Settling is how a supervisor answers the nag. Without it
      ;; `unsettled-losses` would raise the same change every turn forever,
      ;; which trains a reader to skip the block — the opposite of the point.
      action
      (if-not (contains? #{"reverted" "kept"} action)
        (base/malformed branch (str "`action` is `reverted` or `kept`.\n\n" @usage))
        (if-let [v (session/verdict name)]
          (let [undo (when (= "reverted" action) (undo-target! ctx v))]
            (session/reverted! name (= "kept" action) (base/arg ctx :learned))
            (settle-attempt! ctx name {:settled (keyword action)})
            (base/ok branch (str (msg {:settled true :name name :action action
                                       :why (base/arg ctx :why)
                                       :learned (some-> (base/arg ctx :learned) str str/trim
                                                        not-empty)})
                                 (when undo (str "\n\n" undo)))
                     :progress? true))
          (base/fail branch (msg {:no-experiment true :name name}))))

      :else
      (if-let [v (session/verdict name)]
        (do
         (when-not (= :too-early (:verdict v))
           (settle-attempt! ctx name (cond-> {:verdict (:verdict v)}
                                       (:before v) (assoc :before (:before v))
                                       (:after v) (assoc :after (:after v))
                                       (some? (get-in v [:prediction :hit?]))
                                       (assoc :prediction-hit (get-in v [:prediction :hit?])))))
         (base/ok branch
                 (msg {:reported true :name name
                       :verdict (clojure.core/name (:verdict v))
                       :change (:change v) :hypothesis (:hypothesis v)
                       :numbers (when (and (:before v) (:after v))
                                  (str (format "fitness %.2f -> %.2f over %d turns"
                                               (:before v) (:after v) (:turns-since v))
                                       (when-let [c (:cost v)]
                                         (format ", tokens/turn %.0f -> %.0f"
                                                 (:before c) (:after c)))))
                       :better (= :better (:verdict v))
                       :costly (= :costly (:verdict v))
                       :cheaper (= :cheaper (:verdict v))
                       :prediction (when-let [p (:prediction v)]
                                     {:said (said p)
                                      :moved (if (and (:before p) (:after p))
                                               (str (format "%.2f -> %.2f per turn" (:before p) (:after p))
                                                    (when (and (:by p) (:change p))
                                                      (str " (" (pct (:change p)) ")")))
                                               "not measurable yet")
                                      :hit (:hit? p)
                                      :calibration (some-> (:calibration p) clojure.core/name)})
                       :below-best (when (= :below-best (:verdict v))
                                     (format "%.2f" (double (:best v))))
                       :worse (= :worse (:verdict v))
                       :unchanged (= :unchanged (:verdict v))
                       :too-early (= :too-early (:verdict v))
                       :stale (when-let [{:keys [started now]} (:stale v)]
                                {:target (str (:kind (:target v)) " " (:name (:target v)))
                                 :started started :now now})
                       ;; The second reading, and only when it is the reason
                       ;; for the verdict: on any other verdict it agrees with
                       ;; the first and is noise.
                       :confounded
                       (when (= :confounded (:verdict v))
                         (let [b (:health-blind v)]
                           {:verdict (clojure.core/name (:verdict b))
                            :numbers (when (and (:before b) (:after b))
                                       (format "%.2f -> %.2f" (:before b) (:after b)))}))
                       :regraded
                       (when (:regraded v)
                         (when-let [stamped (:before-as-stamped v)]
                           (format "%.2f" stamped)))
                       :branches
                       (when-let [br (:branches v)]
                         (when (pos? (:regressed br))
                           {:regressed (:regressed br) :measured (:measured br)}))})))
        (base/fail branch (msg {:no-experiment true :name name}))))))
