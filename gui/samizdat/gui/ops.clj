;; samizdat - a claim-first verification harness
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

(ns samizdat.gui.ops
  "The decisions and toolkit-free action coordination behind gui.core's
  wiring, extracted so they are testable without loading a toolkit:
  glimmer/GTK deps live only under the :gui alias, so the suite stays
  headless and cannot require gui.core itself.")

(defn scroll-props
  "Props for the inspector's :scrolled wrapper. `:scroll-top` belongs ONLY
  on the render that follows a selection change: glimmer-gtk's scrolled
  spec resets the adjustment whenever the prop is present (the value is
  ignored), and the reconciler re-applies the full prop map on every
  render — with the prop always present, every poller batch (≤1.5s), hover
  change or draft keystroke yanked a long turn log back to the top
  mid-read (review2 #6). `prev` is the selection the previous render saw;
  the caller memoizes it."
  [prev selected]
  (if (= prev selected)
    {:vexpand true}
    {:vexpand true :scroll-top (str selected)}))

(defn refetch-after?
  "Whether a fetch that just completed for `fetch-sel` should re-run for a
  recorded pending selection. When the single-flight CAS in
  refresh-branch-log! fails, the new selection's fetch is not dropped — it
  is recorded as pending, and the in-flight fetch's finally re-runs for it
  (review2 #7); without that, the in-flight fetch discarded its own result
  (selection moved), the CAS loser was discarded, and on a finished run no
  poller event ever re-triggered — the panel read `(loading …)` forever.
  The same selection must NOT re-run: that is the busy guard's coalescing,
  which exists so a poller firing every second for one branch queues
  exactly one fetch."
  [pending fetch-sel]
  (boolean (and pending (not= pending fetch-sel))))

(defn tail-target
  "Which run to tail after a run-list refresh. nil means keep tailing
  whatever is currently tailed. An EMPTY list is `don't know`, not `no
  runs` (review2 #8): list-runs answers {:ok false} — no throw — on a dead
  or busy server, and treating that as `no runs` disconnected the run
  being tailed (worst case right after start-new-run!, while the run was
  actively spending) and wiped the graph."
  [current runs]
  (when (seq runs)
    (when-not (some #(= current (:id %)) runs)
      (some-> runs first :id))))

(defn apply-intervention
  "Fold an intervention response. Success clears only the unchanged sent
  draft; failure preserves whatever is in the entry."
  [s {:keys [ok error]} sent-draft branch]
  (if ok
    (cond-> (assoc s :notice
                   (str "queued for " (or branch "the run")
                        " — applies at the next turn boundary"))
      (= sent-draft (:draft s)) (assoc :draft ""))
    (assoc s :notice (str "failed: " (or error "request failed")))))

(defn send-intervention!
  [state branch intervene]
  (let [{:keys [base-url run draft]} @state]
    (swap! state assoc :notice (str "sending to " (or branch "the run") "…"))
    (future
      (let [response (try (intervene base-url run
                                     {:branch-id branch :kind "message" :payload draft})
                          (catch Throwable e {:ok false :error (ex-message e)}))]
        (swap! state (fn [s]
                       (if (= run (:run s))
                         (apply-intervention s response draft branch)
                         s)))
        response))))

(defn apply-start
  "Fold a start response, preserving the form on failure and releasing the
  in-flight guard on every outcome."
  [s {:keys [ok body error]}]
  (let [id (:run_id body)
        s (assoc s :starting? false)]
    (if (and ok id)
      (assoc s :composing? false
             :form {:problem "" :max-turns "" :beam-width ""
                    :seed-run "" :model "" :reasoning-effort ""}
             :notice (str "started " (subs (str id) 0 8)))
      (assoc s :notice (str "could not start: " (or error "no run id came back"))))))

(defn start!
  "Claim and submit one new run. A repeat while the request is in flight
  returns nil without issuing another request."
  [state body notice start-run on-success]
  (let [[previous] (swap-vals! state
                               (fn [s]
                                 (if (:starting? s)
                                   s
                                   (assoc s :starting? true :notice notice))))]
    (when-not (:starting? previous)
      (future
        (let [response (try (start-run (:base-url previous) body)
                            (catch Throwable e {:ok false :error (ex-message e)}))
              id (get-in response [:body :run_id])]
          (swap! state apply-start response)
          (when (and (:ok response) id)
            (on-success id))
          response)))))
