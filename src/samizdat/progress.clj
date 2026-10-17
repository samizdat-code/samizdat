;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.progress
  "Where each branch is in its workflow, journaled as it moves.

  The supervisor's subject is whether the implementer is moving through its
  states, not the steps it takes inside one (karamazov-rz7t). Turns carry no
  state, and nothing recorded a transition, so all it could read was the
  steps. This records two kinds of movement as :progress notes:

  - STAGE: entering a node the manifest names under `:progress`. Which nodes
    those are is the manifest's to say, since a turn chain's nodes run every
    turn and a feature loop's are its stages; a manifest that names none
    records none.
  - BRANCH FIELDS: a change in any of the branch fields gates.edn :progress
    :branch-keys lists (phase, claimed task, stated goal, status).

  Mechanism only. It is installed on every compiled manifest as a mycelium
  interceptor pair, so a sub-loop a cell compiles records exactly as the run's
  own loop does, and it reads the run record from the resources the manifest
  runs with: no :conn, nothing recorded. It never throws — it runs inside the
  turn it observes."
  (:require [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.store.journal :as journal]))

(defonce ^:private seen
  ;; [run-id branch-id] -> the last snapshot recorded for that branch, with
  ;; "stage" beside the branch fields. Forgotten when the run ends.
  (atom {}))

(defn forget-run! [run-id]
  (swap! seen (fn [m] (into {} (remove (fn [[[r _] _]] (= r run-id))) m))))

(defn- text [v]
  (cond (nil? v) nil
        (keyword? v) (name v)
        :else (str v)))

(defn snapshot
  "The watched fields of `branch` as {key text}. `branch-keys` maps a key to
  its path in the branch."
  [branch branch-keys]
  (into {} (map (fn [[k path]] [k (text (get-in branch path))])) branch-keys))

(defn changes
  "What moved between two snapshots, as [{:key :from :to}] in key order. A key
  first seen holding nothing has not moved."
  [prev cur]
  (vec (for [k (sort-by name (keys cur))
             :let [from (get prev k) to (get cur k)]
             :when (not= from to)]
         {:key (name k) :from from :to to})))

(defn- record! [conn run-id branch-id turn moves]
  (doseq [m moves]
    (journal/note! conn run-id :progress {:branch-id branch-id :turn turn :data m})))

(defn- observe!
  "Swap in `f`'s view of this branch's snapshot and journal what moved."
  [{:keys [conn run-id]} branch-id turn f]
  (when (and conn run-id branch-id)
    (let [k [run-id branch-id]
          [old new] (swap-vals! seen update k f)
          moves (changes (get old k) (get new k))]
      (when (seq moves)
        (record! conn run-id branch-id turn moves)))))

(defn- guarded [what f]
  (fn [fsm-state resources]
    (try (f fsm-state resources)
         (catch Throwable e
           (log/debug "progress" what "not recorded:" (ex-message e))))
    fsm-state))

(defn interceptors
  "{:pre :post} for a manifest whose progress nodes are `stage-nodes` (node
  names as strings). :pre records entering a stage node; :post records the
  branch's watched fields after every node."
  [stage-nodes]
  {:pre (guarded :stage
                 (fn [{:keys [current-state-id data]} resources]
                   (let [node (some-> current-state-id name)]
                     (when (contains? stage-nodes node)
                       (observe! resources (get-in data [:branch :id]) (:turn data)
                                 #(assoc % :stage node))))))
   :post (guarded :branch
                  (fn [{:keys [data]} resources]
                    (when-let [b (:branch data)]
                      (let [ks (:branch-keys (gates/threshold :progress))]
                        (when (seq ks)
                          (observe! resources (:id b) (:turn data)
                                    #(merge % (snapshot b ks))))))))})
