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
  (workflow/role-ctx). Only a reply whose first word is `allow` allows:
  anything else, a failed call included, leaves the ask for a person."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.llm.client :as llm]
            [samizdat.llm.message :as message]
            [samizdat.prompt :as prompt]
            [samizdat.workflow :as workflow]))

(defn verdict-of
  "{:verdict :allow|:ask :why} from a reply: :allow only when its first word,
  after any <think> block, is `allow`."
  [reply]
  (let [body (str/trim (message/strip-think-blocks (str reply)))
        [_ word why] (re-find #"(?is)^\W*(\w+)\W*(.*)" body)]
    {:verdict (if (= "allow" (some-> word str/lower-case)) :allow :ask)
     :why (some-> why str/trim not-empty)}))

(defn classify
  "The verdict on `decision`, an ask, for the run in `ctx`."
  [ctx {:keys [input reason outside]}]
  (try
    (let [{:keys [llm-adapter llm-config]} (workflow/role-ctx ctx :classifier)
          reply (llm/chat llm-adapter llm-config
                          [{:role "system" :content (prompt/prompt "approval-classify-system")}
                           {:role "user"
                            :content (prompt/render "approval-classify"
                                                    {:command (str input)
                                                     :reason (str reason)
                                                     :outside (str/join "\n" (map str outside))
                                                     :root (str (:root ctx))})}]
                          {:temperature 0.0})]
      (verdict-of (:content reply)))
    (catch Throwable e
      (log/warn "classify: the call failed:" (ex-message e))
      {:verdict :ask :why "the classifier could not answer"})))
