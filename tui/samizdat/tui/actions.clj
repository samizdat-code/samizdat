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

(defn- note-result [s r]
  (st/note-error s (when-not (:ok r) (:error r))))

(defn steer!
  "Send the compose box as a directive, or resume an interrupted run with it."
  [{:keys [state client poll!]} text]
  (when-let [payload (st/steer-payload text)]
    (let [{:keys [base run-id branch-id] :as s} @state]
      (if-not run-id
        (swap! state st/note-error "no run selected")
        (let [interrupted? (st/interrupted? s)]
          (when interrupted? (swap! state st/note-notice "resuming…"))
          (future
            (let [r (response #(if interrupted?
                                 ((:resume! client) base run-id {:message payload})
                                 ((:intervene! client) base run-id
                                  {:branch-id branch-id :kind "message" :payload payload})))]
              (when (apply-current state run-id
                                   (fn [s]
                                     (cond-> (note-result s r)
                                       (:ok r) (st/clear-sent-draft text))))
                (poll!))
              r)))))))

(defn abort!
  [{:keys [state client poll!]}]
  (let [{:keys [base run-id]} @state]
    (if-not run-id
      (swap! state st/note-error "no run selected")
      (future
        (let [r (response #((:abort! client) base run-id))]
          (when (apply-current state run-id #(note-result % r)) (poll!))
          r)))))

(defn resume!
  ([env] (resume! env nil))
  ([{:keys [state client poll!]} opts]
   (let [{:keys [base run-id]} @state]
     (if-not run-id
       (swap! state st/note-error "no run selected")
       (future
         (let [r (response #((:resume! client) base run-id opts))]
           (when (apply-current state run-id #(note-result % r)) (poll!))
           r))))))

(defn decide!
  "Answer an approval without blocking input. Its reply editor closes locally;
  the approval itself remains until a poll confirms the decision. A repeated
  decision is refused while the first is in flight."
  [{:keys [state client poll!]} id decision]
  (let [{:keys [base run-id]} @state
        decision (if (map? decision) decision {:decision decision})]
    (if-not run-id
      (swap! state st/note-error "no run selected")
      (let [[before]
            (swap-vals! state
                        (fn [s]
                          (if (and (= run-id (:run-id s))
                                   (nil? (:decision-pending s)))
                            (-> s (assoc :decision-pending id) st/cancel-reply)
                            s)))]
        (when (and (= run-id (:run-id before))
                   (nil? (:decision-pending before)))
          (future
            (let [r (response #((:decide! client) base id decision))]
              (when (apply-current
                     state run-id
                     (fn [s]
                       (cond-> (note-result s r)
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
