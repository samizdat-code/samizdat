;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.actions
  "What each action a run took led to (karamazov-oi16.1).

  The shell tool journals every command it runs — a turn's own call and an
  eval script's bridged `sh` alike — as an `:action` note: the command, its
  exit, how long it took, and how it was invoked. This namespace reads those
  notes back as a record per action key (`policy/command-keys`): how often it
  worked, failed or timed out, what it last did, and the command that worked.

  Mechanism only. What counts as failing often enough to steer on, and what is
  done about it, are the callers' decisions under gates.edn :actions."
  (:require [clojure.string]
            [samizdat.security.policy :as policy]))

(defn note
  "The `:action` note for a shell run of `command`, from its tool result `r`.
  A refused command ran nothing, so it has no exit."
  [{:keys [command via ms command-chars]} r]
  (let [cmd (str command)
        refused? (boolean (:policy-refusal? r))]
    (cond-> {:command (subs cmd 0 (min (count cmd) (or command-chars (count cmd))))
             :ms ms
             :category (name (or (:category r) :unknown))}
      via (assoc :via via)
      refused? (assoc :refused? true)
      (not refused?) (assoc :exit (get-in r [:data :exit])
                            :timeout? (boolean (get-in r [:data :timeout?]))))))

(defn- outcome [{:keys [exit timeout? refused?]}]
  (cond refused? nil
        timeout? :timeout
        (= 0 exit) :ok
        (some? exit) :failed))

(defn scores
  "Each action key's record from `:action` notes (as `journal/notes` reads them:
  the note, with the `:branch-id` and `:turn` it was taken on),
  all branches' or `branch-id`'s: `{key {:key :ok :failed :timeouts :last-turn
  :last-ok? :last-exit :worked :ms}}`. A command's outcome is its last
  statement's, as the shell's exit is, so it is that statement's key that
  carries it."
  ([notes] (scores notes nil))
  ([notes branch-id]
   (reduce
    (fn [acc {:keys [turn] :as data}]
      (let [o (outcome data)
            k (last (policy/command-keys (:command data)))]
        (if (or (nil? o) (nil? k) (and branch-id (not= branch-id (:branch-id data))))
          acc
          (update acc k
                  (fn [s]
                    (cond-> (merge {:key k :ok 0 :failed 0 :timeouts 0 :ms 0} s)
                      (= o :ok) (-> (update :ok inc) (assoc :worked (:command data)))
                      (= o :failed) (update :failed inc)
                      (= o :timeout) (update :timeouts inc)
                      true (-> (update :ms + (or (:ms data) 0))
                               (assoc :last-turn turn :last-ok? (= o :ok)
                                      :last-exit (:exit data)))))))))
    {}
    notes)))

(defn failing
  "The actions that have failed at least `min-failures` times and never worked,
  most failures first."
  [scores {:keys [min-failures]}]
  (->> (vals scores)
       (filter #(and (zero? (:ok %)) (>= (+ (:failed %) (:timeouts %)) min-failures)))
       (sort-by #(- (+ (:failed %) (:timeouts %))))))

(defn working
  "The actions that have worked, most recently used first."
  [scores]
  (->> (vals scores) (filter #(pos? (:ok %))) (sort-by #(- (or (:last-turn %) 0)))))

(defn- parts [k] (let [[p & r] (clojure.string/split (str k) #" " 2)] [p (first r)]))

(defn steer
  "What to tell a branch about the actions that keep failing: each as `{:key
  :failed :exit :instead}`, where :instead is the commands that worked for an
  action running the same thing by another program, or the same program on
  something else — `python3 solve.py` for a failing `python solve.py`."
  [scores policy]
  (let [ok (working scores)]
    (vec (for [{k :key :keys [failed timeouts last-exit]} (failing scores policy)]
           (let [[prog arg] (parts k)
                 alike (fn [w] (let [[p a] (parts (:key w))]
                                 (and (not= k (:key w)) (or (and arg (= arg a)) (= prog p)))))]
             {:key k :failed (+ failed timeouts) :exit last-exit
              :instead (vec (distinct (map :worked (filter alike ok))))})))))
