;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.findings
  "A review's findings as a ledger (karamazov-05z0.9, after autolith's
  mission review): each finding has a stable id, the owner can dispute one
  or say it is resolved, and the next review is shown what it said last time
  and what the owner answered, and rules on each.

  A stateless review re-reads the diff from nothing every round. A finding
  the owner thinks is wrong could only be argued with by changing the code,
  so a task bounced until the attempts ran out — given up one test error from
  green — and a re-review of an unchanged diff paid the judge for the same
  answer.

  Pure: ids, folding, and the shape the cell persists. The cell stores the
  ledger and decides when a review can be reused (gates.edn :review-ledger)."
  (:require [clojure.string :as str]))

(defn- normalised [text]
  (-> (str/lower-case (str text)) (str/replace #"[^a-z0-9]" "")))

(defn id-of
  "A finding's id: a digest of its text normalised to lower-case
  alphanumerics, the identity `judge/dedupe-findings` already uses — so the
  same finding raised twice, reworded only in case or punctuation, keeps its
  id."
  [text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes ^String (normalised text) "UTF-8"))]
    (str "f-" (subs (apply str (map #(format "%02x" (bit-and % 0xff)) bs)) 0 8))))

(defn digest
  "A short digest of `text` — what the cell keys an unchanged diff by."
  [text]
  (subs (id-of text) 2))

(defn fold
  "The ledger after a review that raised `segments` (the verified findings,
  one per finding): {id {:text :status :reason}}.

  A finding raised again keeps its id and its status unless it was
  :resolved — raised again after the owner said it was fixed means it is
  not, so it is :open. A disputed one raised again is :upheld: the reviewer
  read the dispute and kept it. A prior finding not raised again is
  :withdrawn. A new one is :open."
  [prior segments]
  (let [now (into {} (map (fn [s] [(id-of s) (str/trim (str s))])) segments)]
    (merge
     (into {}
           (for [[id f] prior :when (not (contains? now id))]
             [id (assoc f :status :withdrawn)]))
     (into {}
           (for [[id text] now
                 :let [was (get prior id)]]
             [id (cond
                   (nil? was) {:text text :status :open}
                   (= :disputed (:status was)) (assoc was :text text :status :upheld)
                   :else (assoc was :text text :status :open))])))))

(defn standing
  "The findings a review must still rule on: everything not withdrawn."
  [ledger]
  (into {} (remove (comp #{:withdrawn} :status val)) ledger))

(defn answered?
  "Whether the owner has answered any finding since the ledger was written:
  a dispute or a resolution the reviewer has not ruled on yet."
  [ledger]
  (boolean (some (comp #{:disputed :resolved} :status) (vals ledger))))

(defn answer
  "The ledger with the owner's answer to finding `id`: :disputed or
  :resolved, with their reason. nil when there is no such standing finding."
  [ledger id status reason]
  (when (contains? (standing ledger) id)
    (update ledger id assoc :status status :reason (str reason))))
