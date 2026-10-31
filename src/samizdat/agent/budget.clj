;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.budget
  "What a run's calls in flight have reserved against its token budget.

  The journal is the bill, and it is written after a call returns, so a
  budget read off it alone is checked after the money is spent: every live
  branch could start a call under the line and all of them land past it
  (karamazov-05z0.3). A reservation is the call's worst case taken before it
  is made and given back when it returns. A reply that did not say what it
  cost keeps its reservation as its price — unknown is charged as the most it
  could have been, never as nothing.

  Mechanism only: whether to reserve, how much, and what to do when a call
  does not fit are the :budget/admit cell's and gates.edn :token-budget's.
  In memory, per run, because a reservation means nothing past the process
  that holds the call."
  (:require [samizdat.agent.compaction :as cmp]))

;; run-id -> {:reserved n :unpriced n}
(defonce ^:private ledger (atom {}))

(defn estimate
  "A call's worst case in tokens. The structured arity uses the prepared
  request's complete input and reserved output; the original message arity is
  retained for existing callers. Output is multiplied by the caller's policy
  factor in both forms."
  ([accounting {:keys [output-factor]}]
   (+ (long (:input-tokens accounting))
      (long (* (or (:reserved-output-tokens accounting) 0) output-factor))))
  ([messages max-tokens {:keys [chars-per-token output-factor]}]
   (+ (long (cmp/estimate-tokens messages chars-per-token))
      (long (* (or max-tokens 0) output-factor)))))

(defn held
  "Tokens the run's calls in flight have reserved, plus what unpriced replies
  were charged."
  [run-id]
  (let [{:keys [reserved unpriced]} (get @ledger run-id)]
    (+ (or reserved 0) (or unpriced 0))))

(defn unpriced
  "What replies that reported no usage were charged, which the journal's sum
  does not hold."
  [run-id]
  (or (get-in @ledger [run-id :unpriced]) 0))

(defn reserve!
  "Reserve `ask` tokens when `spent` plus what is held plus `ask` stays
  within `budget`. Atomic across branches: two calls cannot both take the
  last of it. Returns whether it was reserved."
  [run-id spent budget ask]
  (let [ok (volatile! false)]
    (swap! ledger (fn [m]
                    (let [h (+ (or (get-in m [run-id :reserved]) 0)
                               (or (get-in m [run-id :unpriced]) 0))]
                      (if (<= (+ spent h ask) budget)
                        (do (vreset! ok true)
                            (update-in m [run-id :reserved] (fnil + 0) ask))
                        (do (vreset! ok false) m)))))
    @ok))

(defn release!
  "Give back a reservation. With `priced?` false the reply reported no
  usage, and the reservation stays on the run as what the call is taken to
  have cost."
  [run-id ask priced?]
  (swap! ledger (fn [m]
                  (cond-> (update-in m [run-id :reserved] #(max 0 (- (or % 0) ask)))
                    (not priced?) (update-in [run-id :unpriced] (fnil + 0) ask))))
  nil)

(defn reset-run!
  "Forget a run's reservations: when it ends, or in a test."
  [run-id]
  (swap! ledger dissoc run-id)
  nil)
