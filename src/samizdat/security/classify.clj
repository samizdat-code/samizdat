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

(ns samizdat.security.classify
  "Auto mode's classifier (samizdat.approval :auto): one small model call
  that reads an ask — the command, why the policy held it, the paths it
  reaches outside the project — and answers allow or ask.

  MECHANISM ONLY. What the model is told is prompts/approval-classify*.md;
  which model runs it is config :roles :classifier, else the run's own
  (workflow/role-ctx); how far outside the project a read may reach is
  gates.edn :approval-classifier :reach, and a read past that (and past
  its :scratch directories) asks without a call, as does a command whose
  text hides what it reaches (policy/paths-opaque?). Only a reply whose
  first word is `allow` allows: anything else, a failed call included,
  leaves the ask for a person.

  The model's call is a learned decision point (samizdat.learned, RFC-015):
  every verdict is logged beside what the learner predicted, and once the
  learner has matched the model on this project's record, and the operator
  turned that on, the learner answers the occurrences it is sure of. Only
  the call: the reach and opacity checks before it are never learned over."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.llm.client :as llm]
            [samizdat.learned :as learned]
            [samizdat.llm.message :as message]
            [samizdat.prompt :as prompt]
            [samizdat.security.policy :as policy]
            [samizdat.workflow :as workflow]))

(defn verdict-of
  "{:verdict :allow|:ask :why} from a reply: :allow only when its first word,
  after any <think> block, is `allow`."
  [reply]
  (let [body (str/trim (message/strip-think-blocks (str reply)))
        [_ word why] (re-find #"(?is)^\W*(\w+)\W*(.*)" body)]
    {:verdict (if (= "allow" (some-> word str/lower-case)) :allow :ask)
     :why (some-> why str/trim not-empty)}))

(defn reach-root
  "The directory `levels` above `root`: what a read outside the project may
  reach and still go to the model."
  [root levels]
  (loop [f (io/file (str root)) n levels]
    (if (and (pos? n) (.getParentFile f)) (recur (.getParentFile f) (dec n)) (str f))))

(defn within?
  "Whether `path` is `dir` or under it."
  [dir path]
  (let [dir (str dir) path (str path)]
    (or (= dir path)
        (str/starts-with? path (if (str/ends-with? dir "/") dir (str dir "/"))))))

(defn home
  "The home directory: a project's parent at or above it is no reach, or a
  project in ~ would reach all of it."
  []
  (System/getenv "HOME"))

(defn- reach-of
  "The directory a read outside the project may stay under and still go to
  the model: `levels` above `root`, unless that is / or the home directory
  or above it, when there is none."
  [root levels]
  (let [p (reach-root root levels)]
    (when-not (or (= p (str root)) (= "/" p) (and (home) (within? p (home))))
      p)))

(defn- rationale
  "What the branch said before this call: its last assistant message, less
  any reasoning block, clipped to gates.edn :approval-classifier
  :rationale-chars."
  [ctx n]
  (let [m (last (filter #(= "assistant" (str (:role %))) (get-in ctx [:branch :messages])))
        t (str/trim (message/strip-think-blocks (str (:content m))))]
    (if (> (count t) n) (str (subs t 0 n) "…") t)))

(learned/register! :approval {:labels [:allow :ask]})

(defn- ask-model
  "The model's verdict on an ask the reach check left to it."
  [ctx {:keys [input reason outside parent scratch rationale]}]
  (let [{:keys [llm-adapter llm-config]} (workflow/role-ctx ctx :classifier)
        reply (llm/chat llm-adapter llm-config
                        [{:role "system" :content (prompt/prompt "approval-classify-system")}
                         {:role "user"
                          :content (prompt/render "approval-classify"
                                                  {:command (str input)
                                                   :reason (str reason)
                                                   :outside (str/join "\n" (map str outside))
                                                   :root (str (:root ctx))
                                                   :parent (str parent)
                                                   :scratch (str/join " " scratch)
                                                   :readable (str/join " " (remove #{(str (:root ctx))}
                                                                                   (map str (:read-roots ctx))))
                                                   :rationale (str rationale)})}]
                        {:temperature 0.0})]
    (verdict-of (:content reply))))

(defn- path-class
  "Where the paths an ask reaches outside the project lie, for the learner's
  key: :none, all in :scratch, all in a :read-root, else under the :parent."
  [outside scratch read-roots]
  (let [all-in (fn [dirs] (every? (fn [p] (some #(within? % p) dirs)) outside))]
    (cond (empty? outside) :none
          (all-in scratch) :scratch
          (all-in read-roots) :read-root
          :else :parent)))

(defn occurrence
  "The learned decision point's view of an ask: keyed by its head, where it
  reaches and the rule that held it, for the model that would answer it."
  [ctx {:keys [input head reason outside occurrence]} scratch]
  (let [first-word #(apply str (take-while (complement #{\space}) (str/trim (str %))))
        head (or (some-> head str not-empty) (first-word input))]
    {:id (or occurrence (str (random-uuid)))
     :key [head (path-class outside scratch (:read-roots ctx)) (first-word reason)]
     :head head
     :features [(count outside)]
     :family (str (:model (:llm-config (workflow/role-ctx ctx :classifier))))}))

(defn- decided
  "The verdict on an ask the checks left to the model: the model's, or the
  learner's where it has earned it (samizdat.learned/decide)."
  [ctx decision scratch ask]
  (if-not (:conn ctx)
    (ask)
    (let [r (learned/decide ctx :approval (occurrence ctx decision scratch)
                            {:llm ask :label-of :verdict})]
      (if (= :learner (:by r))
        {:verdict (:label r) :learned (:p r)}
        (:llm r)))))

(defn classify
  "The verdict on `decision`, an ask, for the run in `ctx`."
  [ctx {:keys [input reason outside] :as decision}]
  (try
    (let [{:keys [reach rationale-chars scratch]} (gates/threshold :approval-classifier)
          parent (reach-of (:root ctx) reach)
          ;; What the shell may already read (the caches, the operator's
          ;; reference roots) is no further than the project.
          near (concat (when parent [parent]) scratch (:read-roots ctx))
          ;; The paths it was held for, and any other existing path the
          ;; command names: an ask for a compound command carries none.
          far (distinct
               (concat (remove (fn [p] (some #(within? % p) near)) outside)
                       (policy/outside-reads input (:root ctx) near)))]
      (cond
        (seq far) {:verdict :ask :why (str/join " " far)}
        ;; Nothing named is not nothing reached when the text hides its
        ;; paths: the model would be the only judge of where it reaches.
        (policy/paths-opaque? input (:root ctx))
        {:verdict :ask :opaque? true}
        :else
        (decided ctx decision scratch
                 #(ask-model ctx {:input input :reason reason :outside outside :parent parent
                                  :scratch scratch
                                  :rationale (rationale ctx rationale-chars)}))))
    (catch Throwable e
      (log/warn "classify: the call failed:" (ex-message e))
      {:verdict :ask :why (ex-message e)})))
