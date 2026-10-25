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

(ns samizdat.approval-view
  "Toolkit-free approval and questionnaire transitions over a front end's state map.")

(defn- connected [s]
  (cond-> (assoc s :connected? true)
    (:error-outage? s) (assoc :error nil :error-outage? false)))

(defn- disconnected [s {:keys [error]}]
  (assoc s :connected? false :error (or error "no server") :error-outage? true))

(defn apply-approvals
  "Fold the questions waiting on a person.

  The cursor and the answers collected so far are reset when the question at
  the head CHANGES — a questionnaire half answered must survive the poll that
  arrives in the middle of answering it, and a new question must not inherit
  the previous one's position."
  [s {:keys [ok body] :as r}]
  (if-not ok
    (disconnected s r)
    (let [as (vec (:approvals body))
          head (:id (first as))]
      (cond-> (assoc (connected s) :approvals as)
        (not= head (:approval-id s))
        (assoc :approval-id head :question-cursor 0 :question-answers []
               :question-selected #{} :question-option 0 :reply nil)))))

(defn reset-run
  "Clear approval, questionnaire, reply, live-preview and fold state for a run change."
  [s]
  (assoc s
         :approvals []
         :approval-id nil
         :question-cursor 0
         :question-answers []
         :question-selected #{}
         :question-option 0
         :reply nil
         :live {}
         :expanded #{}))

(defn current-question
  "The question on screen: the head questionnaire's, at the cursor. nil with none."
  [s]
  (let [qs (vec (:questions (first (:approvals s))))]
    (when (seq qs)
      (nth qs (min (or (:question-cursor s) 0) (dec (count qs)))))))

(defn answer-question
  "Record an answer and move to the next question, or say the set is done.

  Returns `[state done?]` — the caller sends the answers only when done?, so
  a half-answered questionnaire is never submitted."
  [s answers]
  (let [total (count (:questions (first (:approvals s))))
        next-i (count answers)]
    [(assoc s :question-answers (vec answers) :question-cursor (min next-i (dec total))
            :question-selected #{} :question-option 0 :reply nil)
     (>= next-i total)]))

(defn move-option
  "Put the questionnaire's cursor on row `i`."
  [s i]
  (assoc s :question-option i))

(defn toggle-option
  "Tick or untick option `i` of a multi-select question."
  [s i]
  (update s :question-selected (fn [sel] (let [sel (set sel)]
                                           (if (contains? sel i) (disj sel i) (conj sel i))))))

(defn start-reply
  "Give the compose box to a dialog: its next Enter is the deny note or the custom answer."
  [s kind id]
  (assoc s :reply {:kind kind :id id} :input ""))

(defn cancel-reply [s]
  (assoc s :reply nil))

(defn reply-kind
  "What the compose box's next Enter is while a dialog holds it."
  [s]
  (or (:kind (:reply s))
      (let [q (current-question s)]
        (when (and q (empty? (:options q))) :custom-answer))))
