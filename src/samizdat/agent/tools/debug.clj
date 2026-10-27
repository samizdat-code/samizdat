;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.debug
  "Exact-owner semantic Debug Adapter Protocol operations."
  (:require [clojure.string :as str]
            [samizdat.agent.files :as files]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.approval :as approval]
            [samizdat.debug.dap :as dap]
            [samizdat.debug.sessions :as sessions]
            [samizdat.prompt :as prompt]
            [samizdat.store.executions :as executions]
            [samizdat.util :as util]))

(defn- msg [vars]
  (prompt/render "debug-tool" vars))

(defn- result [ctx session value]
  (base/ok (:branch ctx)
           (util/truncate-middle (msg {:result true :value (pr-str value)})
                                 (:max-result-chars (gates/threshold :debug)))
           :resource {:session session :result value}))

(defn- authorize! [ctx action value]
  (let [decision (approval/resolve-ask ctx {:effect :ask
                                            :input (str "debug." action " " (pr-str value))
                                            :reason "debug adapter operation"})]
    (when-not (= :allow (:effect decision))
      (throw (ex-info "debug authorization" {:debug/error :authorization})))))

(defn- start [ctx action]
  (let [policy (gates/threshold :debug)
        _ (when (str/blank? (str (:program policy)))
            (throw (ex-info "debug adapter unavailable" {:debug/error :unavailable})))
        _ (authorize! ctx action (:args ctx))
        execution-id (executions/start! (:conn ctx)
                                        {:run-id (:run-id ctx)
                                         :branch-id (get-in ctx [:branch :id])
                                         :kind "debug"
                                         :specification (assoc (:args ctx) :action action)
                                         :recovery :unknown})
        execution {:id execution-id :policy policy :conn (:conn ctx)
                   :owner [(:run-id ctx) (get-in ctx [:branch :id])]}
        spawn (or (:debug-spawn ctx) dap/start!)
        client (atom nil)]
    (try
      (sessions/admit! ctx execution-id)
      (let [spawned (spawn ctx execution)
            _ (reset! client spawned)
            adapter (sessions/register! ctx execution-id spawned)]
        (reset! client adapter)
        (dap/request! adapter "initialize" {:clientID "samizdat" :adapterID "samizdat"
                                            :linesStartAt1 true :columnsStartAt1 true})
        (dap/request! adapter action (or (:configuration (:args ctx)) {}))
        (dap/wait-event! adapter "initialized")
        (dap/request! adapter "configurationDone" {})
        (result ctx execution-id {:session execution-id :state "running"
                                  :capabilities (:capabilities adapter)}))
      (catch Throwable e
        (when-let [adapter @client] (dap/close! adapter))
        (sessions/remove! execution-id)
        (executions/complete! (:conn ctx) execution-id {:status :error :artifacts []})
        (throw e)))))

(defn- operation [ctx client method params]
  (let [session (:session-id client)
        value (dap/request! client method params)]
    (result ctx session value)))

(defn- breakpoints [ctx client]
  (let [path (str (base/arg ctx :path))
        absolute (files/resolve-under-root (:root ctx) path)
        lines (base/arg ctx :lines)
        limit (:max-breakpoints (gates/threshold :debug))]
    (when-not absolute
      (throw (ex-info "breakpoint path outside root" {:debug/error :invalid-path})))
    (when-not (and (vector? lines) (<= (count lines) limit)
                   (every? #(and (integer? %) (pos? %)) lines))
      (throw (ex-info "invalid breakpoints" {:debug/error :invalid-breakpoints})))
    (operation ctx client "setBreakpoints"
               {:source {:path absolute :name (.getName (java.io.File. absolute))}
                :breakpoints (mapv #(hash-map :line %) lines)})))

(defn- events [ctx client]
  (let [limit (min (long (or (base/arg ctx :count)
                             (:default-event-page (gates/threshold :debug))))
                   (:max-page (gates/threshold :debug)))
        pending @(:events client)
        page (vec (take limit pending))]
    (swap! (:events client) (fn [events] (vec (drop limit events))))
    (base/ok (:branch ctx)
             (util/truncate-middle (msg {:events true :event-items page})
                                   (:max-result-chars (gates/threshold :debug)))
             :resource {:session (:session-id client) :events page
                        :buffered-remaining (- (count pending) (count page))})))

(defn- terminate [ctx client]
  (let [id (:session-id client)]
    (try
      (let [value (dap/request! client "disconnect" {:terminateDebuggee true})]
        (executions/complete! (:conn ctx) id {:status :complete
                                              :artifacts [(str "debug:" id)]})
        (assoc (result ctx id value) :category :success :progress? true))
      (catch Throwable e
        (executions/complete! (:conn ctx) id {:status :error
                                              :artifacts [(str "debug:" id)]})
        (throw e))
      (finally
        (dap/close! client)
        (sessions/remove! id)))))

(defn- status [ctx]
  (let [program (:program (gates/threshold :debug))
        value {:configured (not (str/blank? (str program)))
               :available (and program (.isFile (java.io.File. (str program))))
               :operations ["launch" "attach" "breakpoints" "continue" "pause" "step"
                            "threads" "stack" "scopes" "variables" "evaluate" "events"
                            "cancel" "terminate"]}]
    (base/ok (:branch ctx) (msg (assoc value :status true)) :resource value)))

(defmethod base/run-tool "debug" [{:keys [branch] :as ctx}]
  (try
    (let [action (some-> (base/arg ctx :action) str)]
      (if (#{"status" "launch" "attach"} action)
        (case action
          "status" (status ctx)
          ("launch" "attach") (start ctx action))
        (let [client (sessions/owned ctx (str (base/arg ctx :session)))]
          (case action
            "breakpoints" (breakpoints ctx client)
            "continue" (operation ctx client "continue" {:threadId (base/arg ctx :thread)})
            "pause" (operation ctx client "pause" {:threadId (base/arg ctx :thread)})
            "step" (operation ctx client
                              (case (str (base/arg ctx :kind))
                                "in" "stepIn" "over" "next" "out" "stepOut"
                                (throw (ex-info "invalid step" {:debug/error :invalid-step})))
                              {:threadId (base/arg ctx :thread)})
            "threads" (operation ctx client "threads" {})
            "stack" (operation ctx client "stackTrace"
                               {:threadId (base/arg ctx :thread)
                                :startFrame (or (base/arg ctx :start) 0)
                                :levels (or (base/arg ctx :count)
                                            (:default-stack-frames (gates/threshold :debug)))})
            "scopes" (operation ctx client "scopes" {:frameId (base/arg ctx :frame)})
            "variables" (operation ctx client "variables"
                                   {:variablesReference (base/arg ctx :reference)
                                    :start (or (base/arg ctx :start) 0)
                                    :count (or (base/arg ctx :count)
                                               (:default-variables (gates/threshold :debug)))})
            "evaluate" (do (authorize! ctx "evaluate" (:args ctx))
                           (operation ctx client "evaluate"
                                      (cond-> {:expression (str (base/arg ctx :expression)) :context "repl"}
                                        (base/arg ctx :frame) (assoc :frameId (base/arg ctx :frame)))))
            "events" (events ctx client)
            "cancel" (do (dap/cancel! client)
                         (result ctx (:session-id client) {:cancelled true}))
            "terminate" (terminate ctx client)
            (base/malformed branch (msg {:usage true}))))))
    (catch clojure.lang.ExceptionInfo e
      (cond
        (= :unavailable (:debug/error (ex-data e))) (base/unavailable branch "debug" e)
        (:debug/error (ex-data e)) (base/malformed branch (msg {:error true :kind (name (:debug/error (ex-data e)))}))
        :else (throw e)))
    (catch Throwable e
      (base/fail branch (msg {:error true :kind (or (ex-message e) "debug-error")})))))
