;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.hydration
  "Covered-prefix reuse for the immutable tape's compaction projection. A
  cached prefix is reused only where the current plan cannot rewrite it."
  (:require [samizdat.llm.message :as message]))

(defn- first-difference [a b]
  (loop [i 0]
    (cond
      (>= i (count a)) (count a)
      (>= i (count b)) i
      (not= (nth a i) (nth b i)) i
      :else (recur (inc i)))))

(defn- stable-prefix-count [previous current]
  (let [source (:source previous)
        turns (:turns previous)
        next-source (:source current)
        next-turns (:turns current)
        n (count next-source)]
    (cond
      (not= (:options previous) (:options current)) 0
      (or (> (count source) n) (> (count turns) (count next-turns))) 0
      (and (= source next-source) (= turns next-turns)) n
      :else
      (min n
           (first-difference source next-source)
           (first-difference turns next-turns)
           (or (first (sort (:rewrites current))) n)))))

(defn compact
  "Render messages through the current pure plan, reusing the previous entry's
  rendered prefix only before the first current rewrite. Returns the messages,
  the next cache entry, and how much was reused."
  [previous messages turns options]
  (let [current (message/compaction-plan messages turns options)
        n (if previous (stable-prefix-count previous current) 0)
        out (if (pos? n)
              (into (subvec (:messages previous) 0 n)
                    (subvec (:messages current) n))
              (:messages current))]
    {:messages out
     :entry (assoc current :messages out)
     :reused-prefix n}))
