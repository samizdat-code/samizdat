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
  leaves the ask for a person."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.gates :as gates]
            [samizdat.llm.client :as llm]
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

(defn classify
  "The verdict on `decision`, an ask, for the run in `ctx`."
  [ctx {:keys [input reason outside]}]
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
        (ask-model ctx {:input input :reason reason :outside outside :parent parent
                        :scratch scratch
                        :rationale (rationale ctx rationale-chars)})))
    (catch Throwable e
      (log/warn "classify: the call failed:" (ex-message e))
      {:verdict :ask :why (ex-message e)})))
