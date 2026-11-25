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

(ns samizdat.agent.delegate
  "The registry of delegated children, per run (karamazov-k7gl.9): which
  children a run's branches handed work to, whether each is still running,
  how it ended, and the canceller that stops it.

  Mechanism only. What a child is — its role, workflow, prompt, depth and
  turn limits — is samizdat.agent.tools.delegate reading gates.edn :delegate
  and roles.edn; this namespace runs a thunk as a cancellable task and keeps
  the books on it. run-state/run-ended! calls `forget-run!`, which cancels
  every child still running, so a run cannot end with a child working on
  under it."
  (:require [clojure.string :as str]
            [samizdat.cancel :as cancel]))

;; {run-id {child-id {:id :parent :request :status :started-ms :ended-ms
;;                    :result :cancel :on-cancel}}}
(defonce ^:private registry (atom {}))

(defn next-id
  "The next free child branch id among `ids`: D1, D2, … one past the highest
  D-number taken, so an id is never reused within a run."
  [ids]
  (let [taken (keep #(some-> (re-matches #"D(\d+)" (str %)) second parse-long) ids)]
    (str "D" (inc (reduce max 0 taken)))))

(defn child
  "Child `id` of run `run-id`, without its canceller, or nil."
  [run-id id]
  (some-> (get-in @registry [run-id (str id)]) (dissoc :cancel :on-cancel)))

(defn children
  "Every child of run `run-id`, oldest first."
  [run-id]
  (->> (vals (get @registry run-id))
       (sort-by :started-ms)
       (mapv #(dissoc % :cancel :on-cancel))))

(defn running-count [run-id]
  (count (filter #(= :running (:status %)) (vals (get @registry run-id)))))

(defn- settle!
  "Record how child `id` ended, unless it was already settled (a cancel that
  landed first keeps its :cancelled)."
  [run-id id m]
  (swap! registry
         (fn [r] (if (= :running (get-in r [run-id id :status]))
                   (update-in r [run-id id] merge m {:ended-ms (System/currentTimeMillis)})
                   r))))

(defn- outcome-of
  "A settled task's [:ok v] / [:err e] as the registry's status and result."
  [[tag v]]
  (case tag
    :ok {:status (or (:status v) :done) :result v}
    :err (if (cancel/control-signal? v)
           {:status :cancelled}
           {:status :error :result {:error (or (ex-message v) (str v))}})
    {:status :error}))

(defn start!
  "Run `(task-fn)` as child `id` of run `run-id` on its own fiber and return
  at once. `meta` ({:parent :request …}) is kept on the entry; `on-cancel` is
  called once if the child is cancelled before it ends. The task's return
  value becomes the entry's :result, and its :status (default :done) the
  entry's status."
  [run-id id meta task-fn on-cancel]
  (let [id (str id)]
    ;; On the books BEFORE it starts, so an ending can never land first.
    (swap! registry assoc-in [run-id id]
           (merge meta {:id id :status :running :started-ms (System/currentTimeMillis)
                        :cancel (fn [] nil) :on-cancel on-cancel}))
    (let [{:keys [cancel]}
          (cancel/start!
           (cancel/spawn
            (fn []
              (let [r (try [:ok (task-fn)] (catch Throwable e [:err e]))]
                (settle! run-id id (outcome-of r))
                (when (= :err (first r)) (throw (second r)))
                (second r)))))]
      (swap! registry (fn [m] (cond-> m (get-in m [run-id id])
                                      (assoc-in [run-id id :cancel] cancel)))))
    id))

(defn run-foreground
  "Run `(task-fn)` as a cancellable task and wait for it up to `timeout-ms`:
  [:ok v], [:err e], or [:timeout] with the task cancelled. A cancel of the
  caller's own task while it waits cancels this one too (cancel/await-or-cancel)."
  [task-fn timeout-ms]
  (cancel/await-or-cancel (cancel/start! (cancel/spawn task-fn)) timeout-ms))

(defn cancel!
  "Stop child `id` of run `run-id`: :cancelled, :not-running when it already
  ended, or nil when there is no such child."
  [run-id id]
  (let [id (str id)]
    (when-let [c (get-in @registry [run-id id])]
      (if (not= :running (:status c))
        :not-running
        (do (settle! run-id id {:status :cancelled})
            (try ((:cancel c)) (catch Throwable _ nil))
            (when-let [f (:on-cancel c)] (try (f) (catch Throwable _ nil)))
            :cancelled)))))

(defn forget-run!
  "Cancel every child of run `run-id` still running, then drop the run's
  books. Called from run-state/run-ended!."
  [run-id]
  (doseq [[id c] (get @registry run-id)
          :when (= :running (:status c))]
    (cancel! run-id id))
  (swap! registry dissoc run-id)
  nil)

(defn blank-id?
  "Whether `v` names no child: nil, false, true or a blank string."
  [v]
  (or (nil? v) (boolean? v) (str/blank? (str v))))
