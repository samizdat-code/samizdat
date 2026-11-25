;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.plans
  "A branch's plan as a value with a history (karamazov-k7gl.2).

  The `plan` tool's declaration lives in branch state, which is process
  memory: before this it was recoverable only by re-reading the tool's args
  out of the turns table, it had no history, and it outlived a compaction
  fold only if the summary happened to mention it. Now every change is a
  journal event carrying the WHOLE new plan and its delta from the last one
  (journal/plan-snapshot!), and the latest event is what a fold carries and
  what a resume restores.

  Pure: the snapshot taken off a branch, the delta between two snapshots, and
  a snapshot put back on a branch. Writing and reading the events is the
  journal's; deciding when a fold shows the plan is the compaction cell's."
  (:require [samizdat.agent.state :as state]))

(def ^:private listed [:files :tests :checklist])
(def ^:private scalar [:goal :rfc])

(defn snapshot
  "The branch's plan as one value: `{:files :tests :goal :rfc :checklist}`,
  or nil when it has declared none."
  [branch]
  (when-let [p (state/plan branch)]
    {:files (vec (:files p))
     :tests (vec (:tests p))
     :goal (:goal p)
     :rfc (:rfc p)
     :checklist (state/checklist branch)}))

(defn delta
  "What changed from snapshot `old` to snapshot `new` (either may be nil):
  `{:added {k [...]} :removed {k [...]} :changed {k {:from :to}}}` over the
  list fields (:files :tests :checklist) and the scalar ones (:goal :rfc).
  Empty parts are left out, so an unchanged plan is `{}`. Entries keep the
  order they have in the plan they belong to."
  [old new]
  (let [diff (fn [a b] (let [s (set a)] (vec (remove s b))))
        part (fn [f] (into {} (keep (fn [k] (let [v (f k)] (when (seq v) [k v])))) listed))
        added (part #(diff (get old %) (get new %)))
        removed (part #(diff (get new %) (get old %)))
        changed (into {} (keep (fn [k]
                                 (let [a (get old k) b (get new k)]
                                   (when (not= a b) [k {:from a :to b}]))))
                      scalar)]
    (cond-> {}
      (seq added) (assoc :added added)
      (seq removed) (assoc :removed removed)
      (seq changed) (assoc :changed changed))))

(defn delta-empty?
  "Whether a delta records no change."
  [d]
  (empty? d))

(defn restore
  "`branch` with snapshot `snap` declared on it, as though the plan tool had
  just made it, then `written` (paths a write landed on) discharged from it.
  `fresh?` is whether nothing has been written since the snapshot — it keeps
  a session open whose declared files are all already written
  (state/planned?). A nil snapshot leaves the branch as it was."
  [branch snap written fresh?]
  (if-not snap
    branch
    (let [b (-> (state/declare-plan branch {:files (:files snap) :tests (:tests snap)
                                            :goal (:goal snap) :rfc (:rfc snap)})
                (assoc :checklist (vec (:checklist snap))))
          b (reduce state/note-write b written)]
      (assoc b :repl-fresh? (boolean fresh?)))))
