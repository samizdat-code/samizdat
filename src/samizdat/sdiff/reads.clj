;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.sdiff.reads
  "What changed in a file, form by form, since a branch last read it whole
  (karamazov-0e2c.15). The fingerprints are jolt-lsp's; which branch read
  what, and when, is the run's, so the ledger is kept here."
  (:require [jolt-lsp.sdiff.address :as address]))

(defonce ^:private reads
  ;; [run-id branch-id path] -> fingerprints at that branch's last read
  (atom {}))

(defn since-read!
  "What changed in `src` since the branch last read `path` whole, as
  {:changed :added :removed :same} (form ids), or nil on a first read; and
  remember `src` as this read."
  [run-id branch-id path src]
  (let [k [run-id branch-id path]
        now (address/fingerprints src)
        was (get @reads k)]
    (swap! reads assoc k now)
    (when was
      {:changed (vec (sort (filter #(and (contains? was %) (not= (was %) (now %))) (keys now))))
       :added (vec (sort (remove #(contains? was %) (keys now))))
       :removed (vec (sort (remove #(contains? now %) (keys was))))
       :same (count (filter #(= (was %) (now %)) (keys now)))})))

(defn forget-reads!
  "Drop a run's remembered reads: the run is over."
  [run-id]
  (swap! reads (fn [m] (into {} (remove (fn [[[r] _]] (= r run-id))) m))))
