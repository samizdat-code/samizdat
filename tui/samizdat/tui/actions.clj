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

(ns samizdat.tui.actions
  "Toolkit-free asynchronous coordination for actions on the selected run."
  (:require [samizdat.tui.state :as st]))

(defn- response [f]
  (try (f) (catch Throwable e {:ok false :error (ex-message e)})))

(defn- apply-current
  "Fold `f` only while the run that issued the request is still selected."
  [state run-id f]
  (let [applied (volatile! false)]
    (swap! state (fn [s]
                   (vreset! applied false)
                   (if (= run-id (:run-id s))
                     (do (vreset! applied true) (f s))
                     s)))
    @applied))

(defonce ^:private action-clock (atom 0))

(defn- action-token [run-id kind]
  {:key [run-id kind] :generation (swap! action-clock inc)})

(defn- note-result [s r {:keys [key generation]}]
  (let [settled (get-in s [:action-settlements key] 0)
        error-generation (:action-error-generation s)]
    (if (< generation settled)
      s
      (let [s (assoc-in s [:action-settlements key] generation)]
        (cond
          (:ok r)
          (if (and (= key (:action-error-key s))
                   (<= (or error-generation 0) generation))
            (-> s
                (assoc :action-error-key nil :action-error-generation nil)
                (st/note-error nil))
            s)

          (>= generation (or error-generation 0))
          (-> s
              (st/note-error (:error r))
              (assoc :action-error-key key :action-error-generation generation))

          :else s)))))

(defn steer!
  "Send the compose box as a directive, or resume an interrupted run with it."
  [{:keys [state client poll!]} text]
  (when-let [payload (st/steer-payload text)]
    (let [{:keys [base run-id branch-id] :as s} @state]
      (if-not run-id
        (swap! state st/note-error "no run selected")
        (let [interrupted? (st/interrupted? s)
              action (action-token run-id :steer)]
          (when interrupted? (swap! state st/note-notice "resuming…"))
          (future
            (let [r (response #(if interrupted?
                                 ((:resume! client) base run-id {:message payload})
                                 ((:intervene! client) base run-id
                                  {:branch-id branch-id :kind "message" :payload payload})))]
              (when (apply-current state run-id
                                   (fn [s]
                                     (cond-> (note-result s r action)
                                       (:ok r) (st/clear-sent-draft text))))
                (poll!))
              r)))))))

(defn abort!
  [{:keys [state client poll!]}]
  (let [{:keys [base run-id]} @state]
    (if-not run-id
      (swap! state st/note-error "no run selected")
      (let [action (action-token run-id :abort)]
        (future
          (let [r (response #((:abort! client) base run-id))]
            (when (apply-current state run-id #(note-result % r action)) (poll!))
            r))))))

(defn resume!
  ([env] (resume! env nil))
  ([{:keys [state client poll!]} opts]
   (let [{:keys [base run-id]} @state]
     (if-not run-id
       (swap! state st/note-error "no run selected")
       (let [action (action-token run-id :resume)]
         (future
           (let [r (response #((:resume! client) base run-id opts))]
             (when (apply-current state run-id #(note-result % r action)) (poll!))
             r)))))))

(defn decide!
  "Answer an approval without blocking input. Its reply editor closes locally;
  the approval itself remains until a poll confirms the decision. A repeated
  decision is refused while the first is in flight."
  [{:keys [state client poll!]} id decision]
  (let [{:keys [base run-id]} @state
        decision (if (map? decision) decision {:decision decision})]
    (if-not run-id
      (swap! state st/note-error "no run selected")
      (let [action (action-token run-id [:decide id])
            [before]
            (swap-vals! state
                        (fn [s]
                          (if (and (= run-id (:run-id s))
                                   (= id (:approval-id s))
                                   (nil? (:decision-pending s)))
                            (-> s (assoc :decision-pending id) st/cancel-reply)
                            s)))]
        (when (and (= run-id (:run-id before))
                   (= id (:approval-id before))
                   (nil? (:decision-pending before)))
          (future
            (let [r (response #((:decide! client) base id decision))]
              (when (apply-current
                     state run-id
                     (fn [s]
                       (cond-> (note-result s r action)
                         (= id (:decision-pending s)) (assoc :decision-pending nil))))
                (poll!))
              r)))))))

(defn intervene!
  "Send a slash-command intervention and report its outcome in local notes."
  ([env kind payload branch-id]
   (intervene! env kind payload branch-id
               (fn [r]
                 (if (:ok r)
                   (str kind " sent — " (or (get-in r [:body :note]) "queued"))
                   (str kind " refused: " (:error r))))))
  ([{:keys [state client say!]} kind payload branch-id report]
   (let [{:keys [base run-id]} @state]
     (if-not run-id
       (swap! state st/note-error "no run selected")
       (future
         (let [r (response #((:intervene! client) base run-id
                             {:kind kind :payload payload :branch-id branch-id}))]
           (when (apply-current state run-id identity)
             (say! (report r)))
           r))))))
