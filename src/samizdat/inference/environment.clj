;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.environment
  "External-context root execution through the project-image eval bridge."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [samizdat.agent.gates :as gates]
            [samizdat.cancel :as cancel]
            [samizdat.inference.budget :as budget]
            [samizdat.inference.contract :as contract]
            [samizdat.inference.frame :as frame]
            [samizdat.inference.trace :as trace]
            [samizdat.prompt :as prompt]
            [samizdat.repl.route :as route]
            [samizdat.store.executions :as executions]))

(defonce ^:private continuations (atom {}))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :inference/error kind))))

(defn- environment [ctx root-trace-id content allowance finished]
  (let [cap (:max-slice-chars (gates/threshold :inference))
        search-cap (:search-results (gates/threshold :resources))]
    {"context-length"
     (fn [_] (count content))

     "context-slice"
     (fn [{:keys [start end]}]
       (let [start (max 0 (long start))
             end (max start (min (count content) (+ start cap) (long end)))]
         (subs content start end)))

     "context-search"
     (fn [{:keys [pattern]}]
       (let [pattern (str pattern)]
         (if (empty? pattern)
           []
           (loop [from 0 results []]
             (let [index (when (< (count results) search-cap)
                           (str/index-of content pattern from))]
               (if (nil? index)
                 results
                 (let [end (+ index (count pattern))]
                   (recur end (conj results {:offset index
                                             :excerpt (subs content index end)
                                             :next-offset end})))))))))

     "infer"
     (fn [{:keys [task]}]
       (let [result (frame/infer ctx {:task task :schema :text
                                      :parent-id root-trace-id
                                      :budget (budget/child allowance)})]
         (when (not= :complete (:status result))
           (fail :incomplete {:trace-id (:trace-id result)}))
         (:value result)))

     "finish"
     (fn [{:keys [value]}]
       (compare-and-set! finished nil {:value value :finished? true}))}))

(defn- bridge-handler [operations]
  (fn [{:keys [op args]}]
    (try
      (if-let [operation (get operations op)]
        {:value (operation (or args {}))}
        {:error (str "unknown inference environment operation: " op)})
      (catch Throwable e
        (if (cancel/control-signal? e)
          (throw e)
          {:error (or (ex-message e) (str e))})))))

(defn- root-source [code session]
  (str "(require 'sci.core)\n"
       "(let [operations {'context-length (bound-fn [] (tool/context-length {}))\n"
       "                  'context-slice (bound-fn [start end] (tool/context-slice {:start start :end end}))\n"
       "                  'context-search (bound-fn [pattern] (tool/context-search {:pattern pattern}))\n"
       "                  'infer (bound-fn [task] (tool/infer {:task task}))\n"
       "                  'finish (bound-fn [value] (tool/finish {:value value}))}\n"
       "      contexts (samizdat.bridge/environment-store)\n"
       "      context (locking contexts\n"
       "                (or (get @contexts " (pr-str session) ")\n"
       "                    (let [created (sci.core/init {:namespaces {'user {}}})]\n"
       "                      (swap! contexts assoc " (pr-str session) " created)\n"
       "                      created)))]\n"
       "  (doseq [[label f] operations] (sci.core/intern context 'user label f))\n"
       "  (sci.core/eval-string* context " (pr-str code) "))"))

(defn- store-continuation [key session image-port finished result]
  (swap! continuations assoc key
         (assoc result :session session :image-port image-port :finished finished))
  (dissoc result :session :image-port :finished))

(defn- run-root [ctx {:keys [task context schema budget continue]}
                 digest continuation-key previous]
  (let [finished (atom nil)
        session (or (:session previous) (route/session-of ctx)
                    (str "inference-" (random-uuid)))
        messages (prompt/render "inference-environment"
                                {:label task :digest digest :size (count context)
                                 :continuation (when continue (:reason previous))})
        result (frame/infer (assoc ctx :defer-execution-completion? true)
                            {:task task :schema :text :budget budget
                             :parent-id (:trace-id previous)
                             :messages-fn (fn [_] [{:role "user" :content messages}])})
        continued? (boolean continue)]
    (if (not= :complete (:status result))
      (store-continuation continuation-key session nil finished
                          (assoc result :continued? continued?))
      (let [operations (environment ctx (:trace-id result) context budget finished)
            eval-fn (or (:eval-fn ctx) route/eval-for)
            eval-ctx (assoc ctx
                            :eval-image :project
                            :bridge (bridge-handler operations)
                            :bridge-tools (set (keys operations))
                            :bridge-required? true)
            response (eval-fn eval-ctx (root-source (str (:value result)) session) session
                              (:eval-timeout-ms (gates/threshold :inference)))
            image-port (when-not (:eval-fn ctx) (route/image-port eval-ctx))
            value (get @finished :value ::none)
            normalized (contract/normalize schema)
            failure (cond
                      (:timeout? response) :timeout
                      (not (:ok response)) :evaluation-failed
                      (= ::none value) :no-finish
                      (and (= :text normalized) (not (string? value))) :schema-invalid
                      (and (not= :text normalized) (not (m/validate normalized value))) :schema-invalid)
            final (cond-> (assoc result :continued? continued?)
                    failure (-> (assoc :status :incomplete :reason failure)
                                (cond-> (and (= :evaluation-failed failure) (:error response))
                                  (assoc :error (:error response))))
                    (not failure) (assoc :status :complete :value value))]
        (trace/finish-frame! (:conn ctx) (:trace-id result) (:status final)
                             (if failure
                               (cond-> {:reason failure}
                                 (:error final) (assoc :error (:error final)))
                               value)
                             (:usage result))
        (executions/complete! (:conn ctx) (:trace-id result)
                              {:status (:status final)
                               :artifacts [(str "inference:" (:trace-id result))]})
        (store-continuation continuation-key session image-port finished final)))))

(defn complete
  "Run or explicitly continue one external-context root in its retained image session."
  [ctx {:keys [task context continue] :as options}]
  (let [owner {:run-id (:run-id ctx) :branch-id (get-in ctx [:branch :id])}
        digest (trace/intern-context! (:conn ctx) task context owner)
        continuation-key [(:run-id owner) (:branch-id owner) digest]
        previous (when continue (get @continuations continuation-key))
        current-port (when (and continue (not (:eval-fn ctx)))
                       (route/image-port (assoc ctx :eval-image :project)))]
    (when (and continue
               (or (not previous)
                   (and (not= :complete (:status previous))
                        (not (:eval-fn ctx))
                        (or (nil? (:image-port previous))
                            (not= (:image-port previous) current-port)))))
      (fail :execution-lost {:digest digest}))
    (if (and continue (= :complete (:status previous)))
      (assoc (dissoc previous :session :image-port :finished) :continued? true)
      (run-root ctx options digest continuation-key previous))))
