;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.context-edit
  "Editable projections over immutable message history."
  (:refer-clojure :exclude [apply])
  (:require [clojure.set :as set]
            [clojure.string :as str]))

(defn- message-id [message]
  (or (:id message) (:turn message)))

(defn stage
  "Stage an edit over SOURCE. Returns the immutable source and edit intent."
  [source {:keys [drop edits restore notes revision head]
           :or {drop #{} edits {} restore #{} notes [] revision 0 head "head"}}]
  (let [ids (set (keep message-id source))
        unknown (set/difference (set (concat drop restore (keys edits))) ids)]
    (when (seq unknown)
      (throw (ex-info (str "unknown context message ids: " (str/join ", " unknown))
                      {:unknown unknown})))
    {:source (vec source)
     :drop (set drop)
     :edits edits
     :restore (set restore)
     :notes (vec notes)
     :expected-revision revision
     :expected-head head}))

(defn- call-ids [message]
  (set (map :id (or (:tool-calls message) (:tool_calls message)))))

(defn- answer-id [message]
  (or (:tool-call-id message) (:tool_call_id message)))

(defn- assert-pairs [messages]
  (let [pending (loop [ids #{} remaining messages]
                  (if-let [message (first remaining)]
                    (let [answers (answer-id message)
                          calls (call-ids message)
                          tool-result? (some? answers)]
                      (when (and (not tool-result?) (seq ids))
                        (throw (ex-info "tool result must follow its call before other messages"
                                        {:pending ids :message message})))
                      (when (and tool-result? (not (contains? ids answers)))
                        (throw (ex-info "tool result has no matching call"
                                        {:answer answers :message message})))
                      (recur (if tool-result?
                               (disj ids answers)
                               (set/union ids calls))
                             (rest remaining)))
                    ids))]
    (when (seq pending)
      (throw (ex-info "tool call has no result in context" {:pending pending})))))

(defn apply-edit
  "Apply STAGED to CURRENT only when its revision and head still match."
  [staged current expected-revision expected-head]
  (when (not= expected-revision (:expected-revision staged))
    (throw (ex-info "context revision changed"
                    {:expected (:expected-revision staged) :actual expected-revision})))
  (when (not= expected-head (:expected-head staged))
    (throw (ex-info "context head changed"
                    {:expected (:expected-head staged) :actual expected-head})))
  (let [drop (set/difference (:drop staged) (:restore staged))
        edited (mapv (fn [message]
                       (let [id (message-id message)]
                         (cond
                           (contains? drop id) nil
                           (contains? (:edits staged) id)
                           (assoc message :content (get (:edits staged) id))
                           :else message)))
                     (:source staged))
        messages (into (vec (remove nil? edited))
                       (map (fn [note]
                              {:role "assistant"
                               :content (str "[context note]\n" note)
                               :context-note? true})
                            (:notes staged)))]
    (assert-pairs messages)
    {:messages messages
     :revision (inc expected-revision)
     :source (:source staged)}))

(defn fold
  "Drop every addressable message in a fold window, leaving the rest in order."
  [messages]
  (apply-edit (stage messages {:drop (set (keep message-id messages))})
              messages 0 "head"))

(defonce ^:private projections (atom {}))

(defn- projected-additions [state messages]
  (let [source (:source state)
        known (set (keep message-id source))
        original-anonymous (count (remove #(or (message-id %) (:context-note? %)) source))]
    (second
     (reduce (fn [[seen out] message]
               (cond
                 (:context-note? message) [seen out]
                 (message-id message)
                 [seen (if (contains? known (message-id message)) out (conj out message))]
                 :else
                 [(inc seen) (if (>= seen original-anonymous) (conj out message) out)]))
             [0 []]
             messages))))

(defn edit!
  "Apply a context edit to a branch and remember its new projection revision."
  [run-id branch-id branch args]
  (locking projections
    (let [k [run-id branch-id]
          state (get @projections k)
          previous-source (or (:source state) (vec (:messages branch)))
          previous-head (or (message-id (peek previous-source))
                            (str "head-" (count previous-source)))
          source (if state
                   (into previous-source (projected-additions state (:messages branch)))
                   previous-source)
          revision (:revision state 0)
          head (or (message-id (peek source)) (str "head-" (count source)))
          restore (set (:restore args))
          drop (set/difference (into (:drop state #{}) (:drop args)) restore)
          edits (reduce dissoc (merge (:edits state) (:edits args)) restore)
          notes (into (:notes state []) (:notes args))
          staged (stage source (assoc args
                                      :drop drop :edits edits :restore #{} :notes notes
                                      :revision revision :head previous-head))
          out (apply-edit staged (:messages branch) (:revision args) (:head args))]
      (swap! projections assoc k
             (assoc (select-keys out [:revision :source])
                    :drop drop :edits edits :notes notes))
      (assoc branch :messages (:messages out)
             :context-revision (:revision out) :context-head head))))

(defn forget-run! [run-id]
  (swap! projections (fn [m] (into {} (remove (fn [[[rid _] _]] (= rid run-id)) m)))))
