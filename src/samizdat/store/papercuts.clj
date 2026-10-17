;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.store.papercuts
  "Papercuts: friction a branch reports about the harness itself
  (karamazov-05z0.5, after autolith's state/papercuts).

  The implementor used to put friction in the prose of its answer, where it
  was read once by whoever read the answer and then gone. A papercut is a
  row: reported, folded into an open one when it is the same friction
  reworded, listed for the supervisor, and closed only with the experiment
  whose verdict says the fix helped — so a closed papercut is a measured
  fix, not a claim.

  The same friction is recognised by words, not by meaning: title word sets
  that are equal, or a title-and-body overlap of at least `:overlap`
  (`:same-run-overlap` when it comes from the run that reported it, since a
  branch re-filing a problem rewords it), within `:window-ms` of the last
  report. Words shorter than `:min-word-chars` do not count. The numbers are
  gates.edn :papercut."
  (:require [clojure.string :as str]
            [samizdat.store.db :as db]))

(defn- new-id []
  (str "pc-" (subs (str/replace (str (random-uuid)) "-" "") 0 6)))

(defn words
  "The words of `s` that count: lower case, at least `min-chars` long."
  [s min-chars]
  (into #{} (comp (map str/lower-case) (filter #(>= (count %) min-chars)))
        (re-seq #"[\p{L}\p{N}_]+" (str s))))

(defn similar?
  "Whether two texts share at least `overlap` of the smaller one's words."
  [a b overlap min-chars]
  (let [wa (words a min-chars) wb (words b min-chars)
        n (min (count wa) (count wb))]
    (and (pos? n)
         (>= (/ (double (count (filter wb wa))) n) overlap))))

(defn by-id [conn id]
  (first (db/fetch conn ["SELECT * FROM papercuts WHERE id = ?" (str id)])))

(defn open
  "The open papercuts, most recently reported first."
  [conn]
  (db/fetch conn ["SELECT * FROM papercuts WHERE status = 'open' ORDER BY last_at DESC, id"]))

(defn- since [ms]
  (db/iso-millis (.minusMillis (java.time.Instant/now) (long ms))))

(defn- same-one
  "The open papercut this report repeats, or nil."
  [conn {:keys [title body run-id]} {:keys [window-ms overlap same-run-overlap min-word-chars]}]
  (let [text (str title " " body)]
    (first
     (filter (fn [row]
               (or (and (seq (words title min-word-chars))
                        (= (words title min-word-chars) (words (:title row) min-word-chars)))
                   (similar? text (str (:title row) " " (:body row))
                             (if (and run-id (= (str run-id) (str (:last_run_id row))))
                               same-run-overlap overlap)
                             min-word-chars)))
             (db/fetch conn ["SELECT * FROM papercuts WHERE status = 'open' AND last_at >= ?
                              ORDER BY last_at DESC" (since window-ms)])))))

(defn report!
  "Record friction. Folds into the open papercut it repeats, else opens one;
  a fold keeps the first report's text, so a later reporter's flow label
  has nothing of its to carry. {:id :new? :reports}."
  [conn {:keys [title body run-id branch-id flow] :as r} policy]
  (when (str/blank? (str title))
    (throw (ex-info "a papercut needs a title" {})))
  (db/with-writer
    (let [now (db/now)]
      (if-let [row (same-one conn r policy)]
        (do (db/execute! conn ["UPDATE papercuts SET reports = reports + 1, last_at = ?,
                                last_run_id = ? WHERE id = ?"
                               now (some-> run-id str) (:id row)])
            {:id (:id row) :new? false :reports (inc (:reports row))})
        (let [id (new-id)]
          (db/execute! conn ["INSERT INTO papercuts
                              (id, title, body, run_id, branch_id, first_at, last_at, last_run_id, flow)
                              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                             id (str/trim (str title)) (str/trim (str body))
                             (some-> run-id str) (some-> branch-id str) now now
                             (some-> run-id str) flow])
          {:id id :new? true :reports 1})))))

(defn close!
  "Close `id` with the experiment that fixed it and the verdict it got. The
  caller checks the verdict; this records it."
  [conn id {:keys [resolution experiment verdict]}]
  (db/with-writer
    (db/execute! conn ["UPDATE papercuts SET status = 'closed', resolution = ?, experiment = ?,
                        verdict = ?, closed_at = ? WHERE id = ? AND status = 'open'"
                       (str resolution) (str experiment) (some-> verdict name) (db/now) (str id)])))
