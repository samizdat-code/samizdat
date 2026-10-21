;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.learned
  "Learned decisions (RFC-015): a model that earns what the LLM decides.

  A DECISION POINT is a site where an LLM picks one of two labels. Every
  occurrence is logged with what the learner predicted before the LLM
  answered, so the learner's agreement with the LLM is measured on this
  project's own decisions, out of sample. A point starts in :shadow, where
  the LLM decides and the learner only predicts. It graduates to :cascade
  when, for some label, the pairs above a confidence τ agree well enough;
  in cascade the learner answers what it is that sure of, the LLM the rest,
  and a seeded fraction of the learner's answers is still sent to the LLM
  (the audit) so agreement keeps being measured where the learner owns the
  decision. A CUSUM on audited disagreement sends the point back to shadow.

  The learner is a keyed Beta (augur.beta) layered over [key head all]
  (augur.layer): p(first label | key), drawn toward the occurrence's head
  and then toward everything this point has seen while its own key has
  little evidence. Evidence fades per run at :forget.

  MECHANISM ONLY. Every bar is gates.edn :learned-decisions, held at least
  as strict as the shipped file's (`stricter`), and cascade takes effect
  only when the operator turned it on for the point (`configure!`, from
  config.edn :learned) — a learned answer lets things through, so whether
  to trust one is not the agent's to decide. Never throws into the caller:
  a learner that fails is the LLM deciding."
  (:require [augur.beta :as beta]
            [augur.layer :as layer]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.events :as events]
            [samizdat.store.db :as db]
            [samizdat.userspace :as userspace]))

;; --- what the operator turned on ---------------------------------------------

(defonce ^:private operator
  ;; {point :cascade}: the points whose graduation may take effect.
  (atom {}))

(defn configure!
  "The operator's config.edn :learned: {point :cascade} for each point whose
  learner may answer once it has graduated. nil or absent is shadow
  everywhere."
  [m]
  (reset! operator (into {} (filter (fn [[_ v]] (= :cascade v))) (or m {})))
  nil)

;; --- the decision points -----------------------------------------------------

(defonce ^:private points
  ;; point -> {:labels [positive negative]}
  (atom {}))

(defn register!
  "Declare decision point `point` and its two labels, the first the one the
  learner's p is the probability of. Idempotent."
  [point {:keys [labels] :as spec}]
  (when-not (= 2 (count labels))
    (throw (ex-info "a learned decision point has two labels" {:point point :spec spec})))
  (swap! points assoc point spec)
  nil)

(defn- labels-of [point]
  (or (:labels (get @points point))
      (throw (ex-info "no learned decision point registered under this name" {:point point}))))

;; --- the bars ----------------------------------------------------------------

(defn stricter
  "`project`'s bars held at least as strict as `shipped`'s, field by field:
  more evidence, a higher level, higher agreement per label, more auditing,
  a CUSUM that trips sooner. A project may raise any bar and lower none."
  [shipped project]
  (let [hi (fn [k] (max (or (get shipped k) 0) (or (get project k) 0)))
        lo (fn [m k] (let [a (get-in shipped [:cusum k]) b (get-in m [:cusum k])]
                       (if (and a b) (min a b) (or a b))))]
    (-> (merge shipped project)
        (assoc :min-n (hi :min-n) :bin-n (hi :bin-n) :level (hi :level)
               :audit (hi :audit) :outcome-level (min (or (:outcome-level shipped) 1.0)
                                                      (or (:outcome-level project) 1.0))
               :agreement (merge-with max (:agreement shipped) (:agreement project))
               :cusum {:k (lo project :k) :h (lo project :h)}))))

(def ^:private shipped-learned
  ;; The shipped gates.edn's :learned-decisions, read once: the template is
  ;; the release's and cannot change under a running process, and every ask
  ;; reads the bars.
  (delay (try (get-in (edn/read-string (userspace/template :policy "gates"))
                      [:learned-decisions :value])
              (catch Throwable _ nil))))

(defn- shipped-bars [point]
  (get @shipped-learned point))

(defn bars
  "The bars point `point` is held to: the project's gates.edn
  :learned-decisions, no looser than the shipped file's."
  [point]
  (let [project (get (gates/threshold :learned-decisions) point)
        shipped (shipped-bars point)]
    (if shipped (stricter shipped project) project)))

;; --- the log -----------------------------------------------------------------

(defn- kw [s] (some-> s not-empty keyword))

(defn- row->map [r]
  {:id (:id r) :run-id (:run_id r) :key (edn/read-string (:key r)) :head (:head r)
   :learner-label (kw (:learner_label r)) :learner-p (:learner_p r)
   :llm-label (kw (:llm_label r)) :outcome (kw (:outcome r))
   :decided-by (kw (:decided_by r)) :audited? (= 1 (:audited r))
   :created-at (:created_at r)})

(defn rows
  "The logged occurrences of `point` for model `family`, oldest first, at most
  the bars' :max-rows of the newest."
  [conn point family]
  (let [n (or (:max-rows (bars point)) Integer/MAX_VALUE)]
    (->> (db/fetch conn ["SELECT * FROM learned_decisions WHERE point = ? AND family = ?
                          ORDER BY created_at DESC, rowid DESC LIMIT ?"
                         (name point) (str family) n])
         reverse
         (mapv row->map))))

(defn- run-weights
  "run-id -> forget^k, k the run's age in runs among `rows` (0: the newest)."
  [rows forget]
  (let [order (reverse (distinct (map :run-id rows)))]
    (into {} (map-indexed (fn [k r] [r (Math/pow (double (or forget 1.0)) k)]) order))))

;; --- the learner -------------------------------------------------------------

(defn- counted
  "A Beta over Jeffreys' prior from the weighted rows' LLM labels, the first
  label a success; nil when there are none."
  [rows w positive]
  (when (seq rows)
    (let [n (reduce + (map #(w (:run-id %)) rows))
          k (reduce + (map #(w (:run-id %)) (filter #(= positive (:llm-label %)) rows)))]
      (beta/from-counts k n (beta/jeffreys)))))

(defn predict
  "The learner's {:label :p}, p its confidence in that label, for occurrence
  `occ` ({:key :head :family}) of `point`, from the LLM's labels logged so
  far."
  [conn point {:keys [key head family]}]
  (let [[pos neg] (labels-of point)
        {:keys [forget strength]} (bars point)
        labelled (filter :llm-label (rows conn point family))
        w (run-weights labelled forget)
        layers [(counted (filter #(= key (:key %)) labelled) w pos)
                (counted (filter #(= (some-> head str) (:head %)) labelled) w pos)
                (or (counted labelled w pos) (beta/jeffreys))]
        m (beta/mean (layer/posterior layers {:strength (or strength 1.0)}))]
    (if (>= m (- 1.0 m)) {:label pos :p m} {:label neg :p (- 1.0 m)})))

;; --- agreement and graduation ------------------------------------------------

(defn- agreement-of [pairs w]
  (let [n (reduce + (map #(w (:run-id %)) pairs))
        k (reduce + (map #(w (:run-id %)) (filter #(= (:learner-label %) (:llm-label %)) pairs)))]
    (beta/from-counts k n (beta/jeffreys))))

(defn- bins
  "`pairs` by confidence, highest first, cut into equal-mass bins of at least
  `bin-n`; empty when there are fewer than `bin-n`."
  [pairs bin-n]
  (let [v (vec (sort-by (comp - :learner-p) pairs))
        k (quot (count v) (max 1 bin-n))]
    (if (zero? k)
      []
      (let [size (/ (count v) (double k))]
        (vec (for [i (range k)]
               (subvec v (Math/round (* i size)) (Math/round (* (inc i) size)))))))))

(defn- tau
  "The lowest confidence above which `label`'s pairs agree with the LLM well
  enough: the lower end of the :level interval of the pooled pairs above it
  clears the label's :agreement bar, and so does the mean of every bin
  above it, so a pocket of disagreement is not hidden by pooling. nil when
  no cut qualifies. Returns [tau bin-reports]."
  [pairs w {:keys [bin-n level agreement]} label]
  (let [bar (get agreement label 1.0)
        bs (bins pairs bin-n)
        reports (mapv (fn [b] {:lo (:learner-p (peek b)) :n (count b)
                               :agree (beta/mean (agreement-of b w))})
                      bs)
        ok (fn [i] (let [above (mapcat identity (subvec bs 0 (inc i)))]
                     (and (every? #(>= (:agree %) bar) (subvec reports 0 (inc i)))
                          (>= (first (beta/interval (agreement-of above w) level)) bar))))
        best (last (filter ok (range (count bs))))]
    [(when best (:lo (reports best))) reports]))

(defn- outcome-blocked?
  "Whether, where a person (or a later outcome) settled an occurrence the
  learner and the LLM disagreed on, the learner was the wrong one often
  enough: P(θ > 1/2) over :outcome-level, θ the share of those discordant
  pairs the learner got wrong."
  [rows {:keys [outcome-level]}]
  (let [d (filter #(and (:outcome %) (:learner-label %) (:llm-label %)
                        (not= (:learner-label %) (:llm-label %)))
                  rows)
        b (count (filter #(= (:llm-label %) (:outcome %)) d))
        c (count (filter #(= (:learner-label %) (:outcome %)) d))]
    (> (beta/prob-above (beta/from-counts b (+ b c) (beta/jeffreys)) 0.5)
       (or outcome-level 1.0))))

(defn report
  "Point `point`'s standing for model `family`: {:n pairs, :tau {label c},
  :bins {label [{:lo :n :agree}]}, :outcome-blocked?, :graduated?}. A pair
  is an occurrence both the learner and the LLM answered."
  [conn point family]
  (let [bars (bars point)
        rs (rows conn point family)
        pairs (filter #(and (:learner-label %) (:llm-label %)) rs)
        w (run-weights rs (:forget bars))
        per (into {} (for [l (labels-of point)]
                       [l (tau (filter #(= l (:learner-label %)) pairs) w bars l)]))
        taus (into {} (keep (fn [[l [t]]] (when t [l t]))) per)
        blocked (outcome-blocked? rs bars)]
    {:n (count pairs)
     :tau taus
     :bins (into {} (map (fn [[l [_ r]]] [l r])) per)
     :outcome-blocked? blocked
     :graduated? (boolean (and (>= (count pairs) (or (:min-n bars) Integer/MAX_VALUE))
                               (seq taus) (not blocked)))}))

;; --- the point's state -------------------------------------------------------

(defn state
  "{:mode :shadow|:cascade :tau {label c} :cusum s} for `point` and `family`.
  A point never seen is shadow."
  [conn point family]
  (let [r (db/fetch-one conn ["SELECT mode, state FROM learned_points WHERE point = ? AND family = ?"
                              (name point) (str family)])]
    (merge {:tau {} :cusum 0.0}
           (some-> (:state r) edn/read-string)
           {:mode (or (kw (:mode r)) :shadow)})))

(defn- put-state! [conn point family st]
  (db/with-writer
    (db/execute! conn ["INSERT INTO learned_points (point, family, mode, state, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(point, family) DO UPDATE SET
                          mode = excluded.mode, state = excluded.state,
                          updated_at = excluded.updated_at"
                       (name point) (str family) (name (:mode st))
                       (pr-str (dissoc st :mode)) (db/now)])))

(defn- moved!
  "Journal a point's change of mode as a :learned-mode event. Written to the
  events table directly, as samizdat.security.flow does, rather than through
  samizdat.store.journal: journal requires security.policy, which reaches
  approval, which calls this namespace."
  [conn run-id point family from to why]
  (log/info "learned:" (name point) family "moved from" (name from) "to" (name to))
  (try
    (let [data {:point (name point) :family family :from (name from) :to (name to) :why (name why)}
          now (db/now)]
      (db/with-writer
        (db/execute! conn ["INSERT INTO events (run_id, branch_id, turn, kind, data, created_at)
                            VALUES (?, NULL, NULL, 'learned-mode', ?, ?)"
                           (some-> run-id str) (json/write-str data) now])
        (events/publish! {:id (db/last-insert-id conn) :run-id run-id :kind :learned-mode
                          :data data :created-at now})))
    (catch Throwable _ nil)))

;; --- deciding ----------------------------------------------------------------

(defn audit?
  "Whether occurrence `occ-id` of run `run-id` is audited at `rate`. Seeded by
  the two, so a replay of the run audits the same occurrences."
  [run-id occ-id rate]
  (let [u (/ (double (mod (hash [(str run-id) (str occ-id)]) 1000003)) 1000003.0)]
    (< u (double (or rate 0.0)))))

(defn- log! [conn {:keys [run-id branch-id]} point occ pred llm-label by audited?]
  (db/with-writer
    (db/execute! conn ["INSERT OR REPLACE INTO learned_decisions
                        (id, run_id, branch_id, point, family, key, head, features,
                         learner_label, learner_p, llm_label, decided_by, audited, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                       (str (:id occ)) (some-> run-id str) (some-> branch-id str)
                       (name point) (str (:family occ)) (pr-str (:key occ))
                       (some-> (:head occ) str) (pr-str (:features occ))
                       (some-> (:label pred) name) (:p pred)
                       (some-> llm-label name) (name by) (if audited? 1 0) (db/now)])))

(defn- after-audit!
  "Fold one audited pair into the point's CUSUM; past :h, back to shadow."
  [conn run-id point family agreed?]
  (let [{:keys [k h]} (:cusum (bars point))
        st (state conn point family)
        s (max 0.0 (+ (:cusum st) (if agreed? 0.0 1.0) (- (or k 0.0))))]
    (if (and h (> s h))
      (do (put-state! conn point family (assoc st :mode :shadow :cusum 0.0))
          (moved! conn run-id point family :cascade :shadow :audit-disagreement))
      (put-state! conn point family (assoc st :cusum s)))))

(defn decide
  "Who answers occurrence `occ` ({:id :key :head :features :family}) of
  `point`, and the answer: {:label :by :llm|:learner :p :audited? :llm raw}.

  `:llm` is a thunk that asks the LLM; `:label-of` reads its label off what
  it returns. In shadow, or below the label's τ, or when the operator has
  not turned the point on, the LLM answers. Otherwise the learner does, and
  a seeded share of those (:audit) asks the LLM too, only to measure.
  Every occurrence is logged with the learner's prediction. The LLM's
  errors propagate: this layer does not decide what a failed call means."
  [{:keys [conn run-id] :as ctx} point occ {:keys [llm label-of]}]
  (let [family (:family occ)
        pred (try (predict conn point occ)
                  (catch Throwable e (log/warn "learned: predict failed:" (ex-message e)) nil))
        st (try (state conn point family) (catch Throwable _ {:mode :shadow}))
        t (get-in st [:tau (:label pred)])
        owned? (boolean (and pred t (= :cascade (:mode st)) (= :cascade (@operator point))
                             (>= (:p pred) t)))
        audited? (and owned? (audit? run-id (:id occ) (:audit (bars point))))
        raw (when (or (not owned?) audited?) (llm))
        llm-label (some-> raw label-of)
        label (if owned? (:label pred) llm-label)]
    (try
      (log! conn ctx point occ pred llm-label (if owned? :learner :llm) audited?)
      (when (and audited? llm-label)
        (after-audit! conn run-id point family (= llm-label (:label pred))))
      (catch Throwable e (log/warn "learned: could not log the occurrence:" (ex-message e))))
    {:label label :by (if owned? :learner :llm) :p (:p pred) :audited? (boolean audited?)
     :llm raw}))

(defn observe!
  "The outcome of occurrence `occ-id` once it is known — a person's answer to
  an ask the LLM or the learner left to them. Unknown ids are ignored."
  [conn occ-id {:keys [outcome]}]
  (try
    (db/with-writer
      (db/execute! conn ["UPDATE learned_decisions SET outcome = ?, resolved_at = ? WHERE id = ?"
                         (some-> outcome name) (db/now) (str occ-id)]))
    (catch Throwable e (log/warn "learned: could not record an outcome:" (ex-message e))))
  nil)

(defn run-ended!
  "At a run's end, re-read every point and family with occurrences and move
  each to the mode its report earns: graduated → cascade, no longer
  graduated → shadow. The τ in force is the one this report fitted.
  Returns {[point family] report}."
  [conn run-id]
  (try
    (into {}
          (for [{:keys [point family]} (db/fetch conn ["SELECT DISTINCT point, family FROM learned_decisions"])
                :let [p (keyword point)]
                :when (get @points p)]
            (let [r (report conn p family)
                  st (state conn p family)
                  to (if (:graduated? r) :cascade :shadow)]
              (put-state! conn p family (assoc st :mode to :tau (:tau r)
                                               :n (:n r) :outcome-blocked? (:outcome-blocked? r)))
              (when (not= to (:mode st))
                (moved! conn run-id p family (:mode st) to (if (:graduated? r) :graduated :fell-below)))
              [[p family] r])))
    (catch Throwable e
      (log/warn "learned: the run-end refit failed:" (ex-message e))
      {})))

(defn standing
  "Every point and family with occurrences: {:point :family :mode :operator
  :n :tau}. What the supervisor and the TUI read."
  [conn]
  (vec (for [{:keys [point family]} (db/fetch conn ["SELECT DISTINCT point, family FROM learned_decisions
                                                    ORDER BY point, family"])
             :let [p (keyword point) st (state conn p family)]]
         {:point p :family family :mode (:mode st)
          :operator (get @operator p :shadow)
          :n (:n st) :tau (:tau st) :outcome-blocked? (:outcome-blocked? st)})))
