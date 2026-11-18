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

(ns samizdat.store.sim
  "SELF-IMPROVEMENT MEMORY (karamazov-amgk): the record of what the
  supervisor tried on its own harness, grouped by the problem each change was
  meant to fix.

  After 'Self-Improvement Memory Enables Continual Recursive
  Self-Improvement'. Its finding is the reason this is a history and not a
  score: continuing to improve a harness after the model or the work changed
  went better with the whole record — why each edit was made, what it was
  predicted to fix, what it did, and under which model and project — than
  with the harness alone, and better than with the problem names alone. The
  lever memory (store.knowledge/distill-verdicts!) keeps a running tally per
  change and rewrites its text each time; the attempts here are appended and
  only ever SETTLED, so an edit that was later replaced still says what it
  was for and what it did.

  A PROBLEM is a root cause in the supervisor's words. Two problems whose
  names match up to case and spacing are one (the paper merges identical
  root-cause text the same way); anything looser is the supervisor's call,
  made by citing a problem by id.

  MECHANISM ONLY: rows in and rows out. What to show the supervisor and when
  is the oversight cell's; the person-level copy is samizdat.sim's, reached
  through `on-change!`, the effect seam a write calls."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.store.db :as db]))

;; --- the seam ----------------------------------------------------------------

(defonce ^:private change-hook (atom nil))

(defn on-change!
  "Register `f`, called with the connection after the record changes. One
  slot: samizdat.sim fills it to keep the person-level copy current."
  [f]
  (reset! change-hook f))

(defn- changed! [conn]
  (when-let [f @change-hook]
    ;; Never into the caller: a copy that cannot be written is not a reason
    ;; to lose the write it copies.
    (try (f conn) (catch Throwable _ nil))))

;; --- problems ----------------------------------------------------------------

(defn norm
  "A problem name as it is compared: trimmed, lower case, spacing collapsed."
  [name]
  (-> (str name) str/trim str/lower-case (str/replace #"\s+" " ")))

(defn problem-id!
  "The id of the problem named `name`, opened by `run-id` when it is new."
  [conn name run-id]
  (let [n (norm name)]
    (when-not (str/blank? n)
      (db/with-writer
        (or (:id (db/fetch-one conn ["SELECT id FROM sim_problems WHERE norm = ?" n]))
            (do (db/execute! conn ["INSERT INTO sim_problems (name, norm, opened_run, created_at)
                                    VALUES (?, ?, ?, ?)"
                                   (str/trim (str name)) n (some-> run-id str) (db/now)])
                (db/last-insert-id conn)))))))

(defn problem-by-ref
  "The id of an existing problem cited as `P3` or `3`, or nil."
  [conn ref]
  (when-let [[_ n] (re-matches #"(?i)\s*p?(\d+)\s*" (str ref))]
    (:id (db/fetch-one conn ["SELECT id FROM sim_problems WHERE id = ?" (parse-long n)]))))

;; --- attempts ----------------------------------------------------------------

(defn- edn-col [v] (when (seq v) (pr-str (vec v))))

(defn- read-col [s] (if (str/blank? (str s)) [] (vec (edn/read-string (str s)))))

(defn- parse
  "An attempt row as a reader wants it: lists as vectors, the target as a
  map, the prediction's hit as a boolean."
  [r]
  (-> r
      (assoc :predicted (read-col (:predicted r))
             :fixed (read-col (:fixed r))
             :missed (read-col (:missed r))
             :unresolved (read-col (:unresolved r))
             :before (:fitness_before r)
             :after (:fitness_after r)
             :prediction_hit (some-> (:prediction_hit r) long pos?)
             :target (when (:target_kind r)
                       (cond-> {:kind (:target_kind r) :name (:target_name r)}
                         (:target_version r) (assoc :version (:target_version r)))))
      (dissoc :fitness_before :fitness_after :target_kind :target_name :target_version)))

(defn- run-model [conn run-id]
  (when run-id
    (db/fetch-one conn ["SELECT model, provider FROM runs WHERE id = ?" (str run-id)])))

(defn record-attempt!
  "Append an attempt and return its id. `run-id` stamps the model and
  provider the run was on. The prediction goes in now, before anything is
  measured: a prediction written after the result is not one."
  [conn {:keys [problem-id run-id experiment change hypothesis target project predicted verdict]}]
  (let [{:keys [model provider]} (run-model conn run-id)
        id (db/with-writer
             (db/execute! conn ["INSERT INTO sim_attempts
                                   (problem_id, run_id, experiment, change, hypothesis,
                                    target_kind, target_name, target_version, model, provider,
                                    project, predicted, verdict, created_at)
                                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                problem-id (some-> run-id str) (some-> experiment str)
                                (str change) (some-> hypothesis str)
                                (some-> (:kind target) str) (some-> (:name target) str)
                                (some-> (:version target) long)
                                (some-> model str not-empty) (some-> provider str not-empty)
                                (some-> project str) (edn-col predicted)
                                (some-> verdict name)
                                (db/now)])
             (db/last-insert-id conn))]
    (changed! conn)
    id))

(defn settle-attempt!
  "Write what is known about attempt `id`: its `verdict`, the decision taken
  on it (`settled`), the fitness before and after, whether its predicted
  signal moved. Only what is given is written, so a later settle adds to an
  earlier one instead of erasing it."
  [conn id {:keys [verdict settled before after prediction-hit] :as m}]
  (let [cols (cond-> []
               (contains? m :verdict) (conj ["verdict" (some-> verdict name)])
               (contains? m :settled) (conj ["settled" (some-> settled name)])
               (contains? m :before) (conj ["fitness_before" (some-> before double)])
               (contains? m :after) (conj ["fitness_after" (some-> after double)])
               (contains? m :prediction-hit) (conj ["prediction_hit" (case prediction-hit
                                                                       true 1 false 0 nil)]))]
    (when (seq cols)
      (db/with-writer
        (db/execute! conn (into [(str "UPDATE sim_attempts SET "
                                      (str/join ", " (map #(str (first %) " = ?") cols))
                                      ", updated_at = ? WHERE id = ?")]
                                (concat (map second cols) [(db/now) id]))))
      (changed! conn))))

(defn attempts
  "Every attempt, oldest first."
  [conn]
  (mapv parse (db/fetch conn ["SELECT * FROM sim_attempts ORDER BY id"])))

(defn attempts-on
  "The attempts whose edit was `kind`/`name`, oldest first."
  [conn kind name]
  (mapv parse (db/fetch conn ["SELECT * FROM sim_attempts WHERE target_kind = ? AND target_name = ?
                               ORDER BY id"
                              (some-> kind clojure.core/name) (str name)])))

(defn settle-predictions!
  "Settle attempt `id`'s predicted battery cases against the held-out
  checks of its edit: each case's newest check on that edit is FIXED when it
  failed before and passed after, MISSED when it ran and did not flip, and
  UNRESOLVED while nothing has replayed it."
  [conn id]
  (when-let [a (some-> (db/fetch-one conn ["SELECT * FROM sim_attempts WHERE id = ?" id]) parse)]
    (when (and (seq (:predicted a)) (:target a))
      (let [checks (db/fetch conn ["SELECT case_id, ok_before, ok_after FROM heldout_checks
                                    WHERE edit_kind = ? AND edit_name = ? ORDER BY id"
                                   (get-in a [:target :kind]) (get-in a [:target :name])])
            ;; Per case, every target of its newest check: a case flips when
            ;; something that failed before passes after.
            by-case (group-by :case_id checks)
            outcome (fn [c]
                      (if-let [rows (seq (get by-case c))]
                        (if (some #(and (zero? (long (:ok_before %))) (pos? (long (:ok_after %)))) rows)
                          :fixed :missed)
                        :unresolved))
            sorted (group-by outcome (:predicted a))]
        (db/with-writer
          (db/execute! conn ["UPDATE sim_attempts SET fixed = ?, missed = ?, unresolved = ?, updated_at = ?
                              WHERE id = ?"
                             (edn-col (:fixed sorted)) (edn-col (:missed sorted))
                             (edn-col (:unresolved sorted)) (db/now) id]))
        (changed! conn)))))

(defn problems
  "Every named problem with its attempts, oldest first: {:id :name
  :opened_run :created_at :attempts [...]}."
  [conn]
  (let [as (group-by :problem_id (attempts conn))]
    (mapv (fn [p] (assoc p :attempts (vec (get as (:id p)))))
          (db/fetch conn ["SELECT id, name, opened_run, created_at FROM sim_problems ORDER BY id"]))))

(defn settle-experiments!
  "Settle the attempts behind a session's experiments as the run ends: the
  verdict each reached, its fitness either side, whether its predicted
  signal moved, the decision taken on it, and its predicted battery cases.
  An experiment too early to read leaves its attempt open rather than
  recording a verdict nobody measured. Returns how many it settled."
  [conn experiments]
  (reduce (fn [n {:keys [attempt-id verdict before after prediction settled]}]
            (if-not attempt-id
              n
              (do (settle-attempt! conn attempt-id
                                   (cond-> {}
                                     (and verdict (not= :too-early verdict)) (assoc :verdict verdict)
                                     before (assoc :before before)
                                     after (assoc :after after)
                                     (some? (:hit? prediction)) (assoc :prediction-hit (:hit? prediction))
                                     settled (assoc :settled settled)))
                  (settle-predictions! conn attempt-id)
                  (inc n))))
          0 experiments))

;; --- edits made under another model ----------------------------------------------

(defn model-mismatches
  "The project's edits IN FORCE that were made on a model other than
  `model`: {:kind :name :version :model :rationale :attempts}. An edit with no
  recorded model — the template, or one made before models were recorded —
  is not listed: nothing is known to differ."
  [conn model]
  (let [model (str model)]
    (if (str/blank? model)
      []
      (mapv (fn [r] {:kind (:kind r) :name (:name r) :version (:version r)
                     :model (:model r) :rationale (:rationale r)
                     :attempts (attempts-on conn (:kind r) (:name r))})
            (db/fetch conn ["SELECT u.kind, u.name, u.version, u.model, u.rationale
                             FROM userspace u
                             WHERE u.model IS NOT NULL AND u.model <> ?
                               AND u.version = (SELECT max(version) FROM userspace v
                                                WHERE v.kind = u.kind AND v.name = u.name)
                             ORDER BY u.kind, u.name"
                            model])))))
