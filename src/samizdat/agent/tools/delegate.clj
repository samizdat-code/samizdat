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

(ns samizdat.agent.tools.delegate
  "Hand one request to a sub-agent and get its answer back (karamazov-k7gl.9).

  A CHILD BRANCH, NOT A CHILD RUN. The child is a branch of the caller's run,
  opened with the caller as its parent and run through a sub-loop manifest —
  the shape team workers and feature's role sub-loops already have. That buys
  everything the feature needs without a new table: its turns and side calls
  are the run's rows, so its usage IS the run's usage; the run's mailbox is
  how a background child's report reaches its parent; a cancel of the run's
  turn reaches a foreground child through the task tree; fetch_turn reads its
  turns. A child run would have needed a parent column, its own startup (the
  workflow-selection call, orient, the seal) and a second budget to reconcile.

  What a child is — role, default workflow, the workflows allowed, depth,
  turns, timeout, how many may run in the background — is gates.edn :delegate
  and roles.edn :delegate; what it is told is prompts/delegate-child.md, and
  every word the caller reads is prompts/delegate-tool.md. The registry of
  running children is samizdat.agent.delegate."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [mycelium.core :as myc]
            [samizdat.agent.delegate :as delegate]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools.base :as base]
            [samizdat.cancel :as cancel]
            [samizdat.config :as config]
            [samizdat.llm.registry :as registry]
            [samizdat.prompt :as prompt]
            [samizdat.security.flow :as flow]
            [samizdat.store.journal :as journal]
            [samizdat.store.messages :as messages]
            [samizdat.store.runs :as runs]
            [samizdat.util :as util]))

(defn- msg [vars] (prompt/render "delegate-tool" vars))

(defn- policy [] (gates/threshold :delegate))

(defn- text-arg [ctx k]
  (let [v (base/arg ctx k)]
    (when-not (delegate/blank-id? v) (str/trim (str v)))))

(defn depth
  "How many delegations deep branch `b` is: 0 for a branch the run opened."
  [b]
  (or (:delegate-depth b) 0))

(defn- workflows-text [{:keys [workflows]}]
  (str/join ", " (sort workflows)))

(defn- resolve-model
  "{:llm …} for the provider alias `model` names, or {:error …}."
  [config model]
  (try {:llm (config/provider-llm config (keyword (str/replace model #"^:" "")) {})}
       (catch Throwable e {:error (ex-message e)})))

(defn- child-ctx
  "The ctx the child's sub-loop runs under: the caller's run ctx without the
  call, as the child's role (config :roles may give it its own model), on
  `llm` when the call named one, with its own turn cap and stream lane."
  [ctx id role llm max-turns]
  (let [base (-> (dissoc ctx :branch :turn :tool-name :args)
                 (assoc :max-turns max-turns))
        rctx ((requiring-resolve 'samizdat.workflow/role-ctx) base role)
        rctx (if llm
               (assoc rctx :llm-config llm :llm-adapter (registry/adapter-for (:provider llm)))
               rctx)]
    (update rctx :llm-config
            #(cond-> % (:stream-run-id %) (assoc :stream-branch id)))))

(defn- usage-of [conn run-id id]
  (try (journal/run-usage conn run-id id)
       (catch Throwable _ {:turns 0 :total-tokens 0})))

(defn- close-child! [conn run-id id status reason]
  (try (runs/close-branch! conn run-id id status reason)
       (catch Throwable e (log/warn "delegate: could not close" id ":" (ex-message e)))))

(defn- run-child
  "Open branch `id` under the caller and run it to a verdict. Returns
  {:status :verdict :answer :usage}. A cancel is rethrown, after the branch
  row is closed."
  [{:keys [conn run-id] :as cctx} id {:keys [parent request workflow role suffix depth advisory?]}]
  (runs/open-branch! conn run-id {:branch-id id :parent-id parent :problem request
                                  :role role :prompt-suffix suffix})
  (try
    (let [compiled ((requiring-resolve 'samizdat.workflow/compiled-manifest) workflow)
          opening ((requiring-resolve 'samizdat.agent.loop/initial-messages) request suffix role)
          b (-> (state/new-branch {:id id :parent-id parent :problem request :messages opening})
                (assoc :role role :advisory? (boolean advisory?) :delegate-depth depth))
          out (myc/run-compiled compiled cctx {:branch b :turn 1})
          failed (when (myc/error? out) (myc/workflow-error out))
          verdict (or (:verdict out) (:status out) (get-in out [:branch :status]) :error)
          answer (or (get-in out [:branch :final-answer]) (:answer out))
          status (cond failed :error (= :done verdict) :done :else :ended)]
      (close-child! conn run-id id (if (= :done status) :done :abandoned)
                    (str "delegated child " (name verdict)))
      {:status status :verdict verdict :answer answer
       :error (some-> failed pr-str)
       :usage (usage-of conn run-id id)})
    (catch Throwable e
      (close-child! conn run-id id :abandoned
                    (if (cancel/control-signal? e) "cancelled" (str "failed: " (ex-message e))))
      (if (cancel/control-signal? e)
        (throw e)
        {:status :error :verdict :error :error (ex-message e)
         :usage (usage-of conn run-id id)}))))

(defn- report-vars
  "What a finished child says, for the result and the mailbox note."
  [id spec {:keys [status verdict answer usage]}]
  {:id id :workflow (:workflow spec) :model (:model spec) :request (:request spec)
   :done (= :done status) :verdict (some-> verdict name)
   :turns (or (:turns usage) 0) :tokens (or (:total-tokens usage) 0)
   :answer (some-> answer str not-empty
                   (util/truncate-middle (:answer-chars (policy))))})

(defn- take-label!
  "The caller reads what the child read: the child's flow label comes back
  with its answer (samizdat.security.flow)."
  [{:keys [conn run-id] :as ctx} id]
  (try (flow/receive! ctx [(flow/carried-by-branch conn run-id id)] "delegate")
       (catch Throwable e (log/warn "delegate: no flow label for" id ":" (ex-message e)))))

(defn- note! [{:keys [conn run-id branch]} id data]
  (try (journal/note! conn run-id :delegate {:branch-id (:id branch)
                                             :data (assoc data :child id)})
       (catch Throwable _ nil)))

(defn- foreground [{:keys [branch conn run-id] :as ctx} cctx id spec]
  (let [{:keys [timeout-ms]} (policy)
        [tag v] (delegate/run-foreground #(run-child cctx id spec) timeout-ms)]
    (take-label! ctx id)
    (case tag
      :ok (let [vars (report-vars id spec v)]
            (note! ctx id {:mode "foreground" :status (name (:status v))
                           :turns (:turns vars) :tokens (:tokens vars)})
            (if (= :error (:status v))
              (base/ok branch (msg {:error true :id id :message (:error v)}))
              (base/ok branch (msg (assoc vars :result true))
                       :category (if (:done vars) :success :neutral)
                       :progress? (boolean (:done vars))
                       :data {:child id :status (:status v)})))
      :timeout (let [u (usage-of conn run-id id)]
                 (close-child! conn run-id id :abandoned "timed out")
                 (note! ctx id {:mode "foreground" :status "timeout"})
                 (base/ok branch (msg {:timeout true :id id :seconds (quot timeout-ms 1000)
                                       :turns (:turns u) :tokens (:total-tokens u)})))
      (if (cancel/control-signal? v)
        (throw v)
        (base/ok branch (msg {:error true :id id :message (ex-message v)}))))))

(defn- background [{:keys [branch conn run-id] :as ctx} cctx id spec]
  (delegate/start!
   run-id id {:parent (:id branch) :request (:request spec)}
   (fn []
     ;; Bounded by the wall clock as well as its turns: past
     ;; :background-timeout-ms it is cancelled and the parent told so.
     (let [limit (:background-timeout-ms (policy))
           [tag v] (delegate/run-foreground #(run-child cctx id spec) limit)
           r (case tag
               :ok v
               :timeout (do (close-child! conn run-id id :abandoned "timed out")
                            {:status :timeout :verdict :timeout
                             :usage (usage-of conn run-id id)})
               (if (cancel/control-signal? v)
                 (throw v)
                 {:status :error :error (ex-message v)}))
           vars (report-vars id spec r)]
       ;; The report goes to the parent's mailbox, carrying the child's label.
       (messages/send! conn {:run-id run-id :from id :to (:id branch)
                             :body (case (:status r)
                                     :error (msg {:error true :id id :message (:error r)})
                                     :timeout (msg (assoc vars :timeout true
                                                          :seconds (quot limit 1000)))
                                     (msg (assoc vars :finished true)))
                             :flow (flow/carried-by-branch conn run-id id)})
       (note! ctx id {:mode "background" :status (name (:status r))
                      :turns (:turns vars) :tokens (:tokens vars)})
       r))
   #(close-child! conn run-id id :abandoned "cancelled"))
  (note! ctx id {:mode "background" :status "started"})
  (base/ok branch (msg {:started true :id id :request (:request spec)})
           :progress? true :data {:child id :status :running}))

(defn- status-line [now c]
  {:id (:id c) :status (name (:status c)) :request (:request c)
   :seconds (quot (- (or (:ended-ms c) now) (:started-ms c)) 1000)
   :verdict (some-> c :result :verdict name)})

(defn- unknown [branch run-id id]
  (base/malformed branch (msg {:unknown true :id id
                               :ids (str/join ", " (map :id (delegate/children run-id)))})))

(defmethod base/run-tool "delegate"
  [{:keys [branch conn run-id config] :as ctx}]
  (let [{:keys [workflows max-depth max-turns max-background role advisory?] :as pol} (policy)
        status-id (text-arg ctx :status)
        cancel-id (text-arg ctx :cancel)
        request (text-arg ctx :request)
        workflow (or (text-arg ctx :workflow) (:workflow pol))
        model (text-arg ctx :model)
        bg? (contains? #{true "true"} (base/arg ctx :background))
        d (depth branch)]
    (cond
      (= "all" status-id)
      (let [now (System/currentTimeMillis)]
        (base/ok branch (msg {:status true
                              :children (mapv (partial status-line now)
                                              (delegate/children run-id))})))

      status-id
      (if-let [c (delegate/child run-id status-id)]
        (base/ok branch (msg {:status true
                              :children [(status-line (System/currentTimeMillis) c)]}))
        (unknown branch run-id status-id))

      cancel-id
      (case (delegate/cancel! run-id cancel-id)
        :cancelled (do (note! ctx cancel-id {:status "cancelled"})
                       (base/ok branch (msg {:cancelled true :id cancel-id}) :progress? true))
        :not-running (base/ok branch (msg {:not-running true :id cancel-id
                                           :state (name (:status (delegate/child run-id cancel-id)))}))
        (unknown branch run-id cancel-id))

      (nil? request)
      (base/malformed branch (msg {:usage true :workflows (workflows-text pol)}))

      (>= d max-depth)
      (base/refusal branch (msg {:depth true :max-depth max-depth}))

      (not (contains? (set workflows) workflow))
      (base/malformed branch (msg {:bad-workflow true :workflow workflow
                                   :workflows (workflows-text pol) :default (:workflow pol)}))

      (and bg? (>= (delegate/running-count run-id) max-background))
      (base/refusal branch (msg {:cap true :max max-background}))

      :else
      (let [{:keys [llm error]} (when model (resolve-model config model))]
        (if error
          (base/malformed branch (msg {:bad-model true :model model :error error
                                       :models (str/join ", " (map name (config/provider-names config)))}))
          (let [id (delegate/next-id (map :id (runs/branches conn run-id)))
                role (keyword role)
                remaining (- max-depth (inc d))
                spec {:parent (:id branch) :request request :workflow workflow :model model
                      :role role :depth (inc d) :advisory? advisory?
                      :suffix (prompt/render "delegate-child"
                                             {:can-delegate (pos? remaining) :remaining remaining})}
                cctx (child-ctx ctx id role llm max-turns)]
            (if bg?
              (background ctx cctx id spec)
              (foreground ctx cctx id spec))))))))
