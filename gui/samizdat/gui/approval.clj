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

(ns samizdat.gui.approval
  "Toolkit-free approval hiccup and asynchronous HTTP coordination."
  (:require [clojure.string :as str]
            [samizdat.approval-view :as shared]))

(def ^:private draft-keys
  [:question-cursor :question-answers :question-selected :question-option :reply :input])

(defn reset-run [s]
  (assoc (shared/reset-run s) :approval-pending nil
         :approval-refresh-needed? false :input ""))

(defn- event-name [x]
  (when (or (string? x) (keyword? x)) (name x)))

(defn approval-event? [event]
  (boolean (some #(= "approval" (event-name %))
                 [(:kind event) (:event event) (get-in event [:data :kind])])))

(defn refresh!
  "Fetch approvals with at most one request in flight. Requests that arrive
  during it coalesce into one follow-up for the run selected at that point."
  ([state approvals] (refresh! state approvals nil))
  ([state approvals on-approval]
   (let [token (Object.)
         [before after]
         (swap-vals! state
                     (fn [s]
                       (cond
                         (not (:run s)) s
                         (:approval-refresh-token s)
                         (assoc s :approval-refresh-needed? true)
                         :else
                         (assoc s :approval-refresh-token token
                                :approval-refresh-needed? false))))]
     (when (and (:run before)
                (nil? (:approval-refresh-token before))
                (= token (:approval-refresh-token after)))
       (future
         (loop [{:keys [base-url run]} after]
           (let [response (try (approvals base-url run)
                               (catch Throwable e {:ok false :error (ex-message e)}))
                 [previous next]
                 (swap-vals! state
                             (fn [s]
                               (let [s (if (and (= run (:run s))
                                                (= token (:approval-refresh-token s)))
                                         (shared/apply-approvals s response)
                                         s)]
                                 (cond
                                   (not= token (:approval-refresh-token s)) s
                                   (not (:approval-refresh-needed? s))
                                   (assoc s :approval-refresh-token nil)
                                   (:run s)
                                   (assoc s :approval-refresh-needed? false)
                                   :else
                                   (assoc s :approval-refresh-token nil
                                          :approval-refresh-needed? false)))))]
             (when (and on-approval (empty? (:approvals previous))
                        (seq (:approvals next)))
               (on-approval))
             (if (= token (:approval-refresh-token next))
               (recur {:base-url (:base-url next) :run (:run next)})
               response))))))))

(defn poll-status!
  "Refresh approvals on every connected journal poll, including empty batches.
  Approval announcements live on the event bus, not in the journal."
  [state approvals on-approval poll-state]
  (when (:connected? poll-state)
    (refresh! state approvals on-approval)))

(defn- claim-decision!
  ([state id] (claim-decision! state id nil))
  ([state id answers]
   (let [eligible? (fn [s]
                     (and (:run s) (= id (:approval-id s))
                          (not (:approval-pending s))))
         transition (fn [s]
                      (if (eligible? s)
                        (let [[next done?]
                              (if answers (shared/answer-question s answers) [s nil])]
                          (cond-> (assoc next :notice nil)
                            (or (nil? answers) done?) (assoc :approval-pending id)))
                        s))
         [before after] (swap-vals! state transition)]
     (when (and (eligible? before) (= after (transition before)))
       {:run (:run before)
        :before before
        :done? (when answers
                 (>= (count answers)
                     (count (:questions (first (:approvals before))))))}))))

(defn- clear-pending [state run id]
  (swap! state (fn [s]
                 (if (and (= run (:run s)) (= id (:approval-pending s)))
                   (assoc s :approval-pending nil)
                   s))))

(defn- post-decision! [state {:keys [approvals decide!]} id decision {:keys [run before]}]
  (future
    (let [response (try (decide! (:base-url before) id decision)
                        (catch Throwable e {:ok false :error (ex-message e)}))]
      (if (:ok response)
        (do
          (swap! state (fn [s]
                         (if (and (= run (:run s)) (= id (:approval-id s)))
                           (assoc s :input "" :reply nil)
                           s)))
          (when (and approvals (= run (:run @state)))
            @(refresh! state approvals))
          (clear-pending state run id))
        (swap! state
               (fn [s]
                 (if (= run (:run s))
                   (cond-> (assoc s :approval-pending nil
                                  :notice (str "decision failed: "
                                               (or (:error response) "request failed")))
                     (= id (:approval-id s)) (merge (select-keys before draft-keys)))
                   s))))
      response)))

(defn decide!
  "Send one visible approval's decision asynchronously. At most one decision
  is in flight for the selected run."
  [state http id decision]
  (when-let [claim (claim-decision! state id)]
    (post-decision! state http id decision claim)))

(defn answer!
  "Record one questionnaire answer through the shared transition, submitting
  asynchronously only when the answer set is complete."
  [state http id answers]
  (when-let [{:keys [done? run] :as claim} (claim-decision! state id answers)]
    (if done?
      (post-decision! state http id {:decision :answer :answers answers} claim)
      (do
        (swap! state (fn [s]
                       (if (and (= run (:run s)) (= id (:approval-id s)))
                         (assoc s :input "")
                         s)))
        nil))))

(defn start-reply! [state kind id]
  (swap! state shared/start-reply kind id))

(defn cancel-reply! [state]
  (swap! state #(assoc (shared/cancel-reply %) :input "")))

(defn set-input! [state text]
  (swap! state assoc :input text))

(defn submit-reply! [state http]
  (let [{:keys [reply approvals question-answers input] :as s} @state
        kind (shared/reply-kind s)
        id (or (:id reply) (:id (first approvals)))]
    (cond
      (= :deny-note kind)
      (decide! state http id {:decision :deny :note (str/trim (str input))})

      (= :custom-answer kind)
      (if (str/blank? (str input))
        (do (swap! state assoc :notice "type an answer first") nil)
        (answer! state http id (conj (vec question-answers) (str/trim (str input))))))))

(defn move-option! [state i]
  (swap! state shared/move-option i))

(defn toggle-option! [state i]
  (swap! state #(-> % (shared/move-option i) (shared/toggle-option i))))

(defn- invoke [handlers k & args]
  (when-let [f (get handlers k)]
    (apply f args)))

(defn- reply-editor [state handlers send-label]
  (let [busy? (boolean (:approval-pending state))]
    [:vbox {:spacing 6}
     [:entry {:text (:input state) :hexpand true
              :sensitive (not busy?)
              :on-change #(invoke handlers :input %)
              :on-activate #(invoke handlers :submit-reply)}]
     [:hbox {:spacing 6}
      [:button {:label send-label :sensitive (not busy?)
                :on-click #(invoke handlers :submit-reply)}]
      [:button {:label "cancel" :sensitive (not busy?)
                :on-click #(invoke handlers :cancel-reply)}]]]))

(defn- gap-line [{:keys [gap required actual because]}]
  (str "needs " (some-> gap name) " " (some-> required name)
       ", is " (some-> actual name)
       (when-let [tool (:tool because)]
         (str " since " tool
              (when-let [turn (:turn because)] (str " at turn " turn))))))

(defn- permission-view [state {:keys [id kind input reason details always always-exact gaps]} handlers]
  (let [busy? (boolean (:approval-pending state))
        noting? (= {:kind :deny-note :id id} (:reply state))
        decide (fn [decision] #(invoke handlers :decide id decision))]
    [:frame {:label "permission required" :vexpand true}
     [:scrolled {:vexpand true}
      [:vbox {:spacing 8 :margin 8}
       [:label {:label (str "tool: " kind) :xalign 0.0}]
       [:label {:label (str input) :wrap true :xalign 0.0}]
       (when (not-empty (str reason))
         [:label {:label (str reason) :wrap true :xalign 0.0}])
       (when (not-empty (str details))
         [:label {:label (str details) :wrap true :xalign 0.0}])
       (when (seq gaps)
         (into [:vbox {:spacing 3}]
               (for [gap gaps]
                 [:label {:label (gap-line gap) :wrap true :xalign 0.0}])))
       (when always
         [:label {:label (if always-exact
                           "allow always applies to this exact command for this session"
                           (str "allow always applies to: " always ", for this session"))
                  :wrap true :xalign 0.0}])
       (if noting?
         (reply-editor state handlers "send denial")
         [:hbox {:spacing 6}
          [:button {:label "allow once" :sensitive (not busy?)
                    :on-click (decide {:decision :allow})}]
          (when always
            [:button {:label "allow always" :sensitive (not busy?)
                      :on-click (decide {:decision :allow :always true})}])
          [:button {:label "deny" :sensitive (not busy?)
                    :on-click (decide {:decision :deny})}]
          [:button {:label "deny + note" :sensitive (not busy?)
                    :on-click #(invoke handlers :reply :deny-note id)}]])]]]))

(defn- questionnaire-view [state {:keys [id questions]} handlers]
  (let [q (shared/current-question state)
        i (min (or (:question-cursor state) 0) (max 0 (dec (count questions))))
        opts (vec (:options q))
        selected (set (:question-selected state))
        answers (vec (:question-answers state))
        busy? (boolean (:approval-pending state))
        writing? (= :custom-answer (shared/reply-kind state))]
    [:frame {:label (str "question " (inc i) " of " (count questions)) :vexpand true}
     [:scrolled {:vexpand true}
      [:vbox {:spacing 8 :margin 8}
       (when (not-empty (str (:header q)))
         [:label {:label (str (:header q)) :xalign 0.0}])
       [:label {:label (str (:question q)) :wrap true :xalign 0.0}]
       (when (:multi q)
         [:label {:label "select one or more" :xalign 0.0}])
       (into [:vbox {:spacing 4}]
             (map-indexed
              (fn [n option]
                (if (:multi q)
                  [:button {:label (str (if (contains? selected n) "[x] " "[ ] ") option)
                            :sensitive (not busy?)
                            :on-click #(invoke handlers :toggle n)}]
                  [:button {:label (str option) :sensitive (not busy?)
                            :on-click (fn []
                                        (invoke handlers :option n)
                                        (invoke handlers :answer id (conj answers (str option))))}]))
              opts))
       (when-let [description (nth (vec (:descriptions q)) (:question-option state) nil)]
         [:label {:label (str description) :wrap true :xalign 0.0}])
       (when writing?
         (reply-editor state handlers "send answer"))
       [:hbox {:spacing 6}
        (when (and (not writing?) (:multi q))
          [:button {:label "confirm" :sensitive (boolean (and (not busy?) (seq selected)))
                    :on-click (fn []
                                (when (seq selected)
                                  (invoke handlers :answer id
                                          (conj answers
                                                (mapv #(str (nth opts %)) (sort selected))))))}])
        (when (and (not writing?) (seq opts))
          [:button {:label "your own answer" :sensitive (not busy?)
                    :on-click #(invoke handlers :reply :custom-answer id)}])
        [:button {:label "reject" :sensitive (not busy?)
                  :on-click #(invoke handlers :decide id {:decision :deny :note "rejected"})}]]]]]))

(defn view
  "Render only the head approval, so a click cannot answer a hidden queue item."
  ([state] (view state {}))
  ([state handlers]
   (if-let [a (first (:approvals state))]
     (if (seq (:questions a))
       (questionnaire-view state a handlers)
       (permission-view state a handlers))
     [:vbox])))
