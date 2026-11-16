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

(ns samizdat.llm.rejected
  "Request fields an endpoint refused, remembered so they are not sent again
  (karamazov-kfnn.6).

  An OpenAI-compatible server that does not know `reasoning_effort` answers
  400 to every call that carries it, and the run ends on a knob it never
  needed. Only the REASONING fields are candidates: they are optional
  refinements whose absence leaves the provider at its default, where
  dropping a field about tools, messages or the model would change what is
  asked. A 400 that names one of them beside wordlists.edn
  :rejected-field-signals is that field refused; it is dropped, the call is
  sent once more, and it stays off for that endpoint and model in this
  process."
  (:require [clojure.string :as str]
            [samizdat.lexicon :as lexicon]))

(def droppable
  "The body keys that may be dropped: each only tunes how much the model
  reasons."
  [:reasoning_effort :reasoning :thinking :enable_thinking
   :reasoning_budget_tokens :thinking_budget])

(defonce ^:private refused
  ;; [base-url model] -> #{body key}
  (atom {}))

(defn- k [config] [(str (:base-url config)) (str (:model config))])

(defn strip
  "`body` without the fields `config`'s endpoint refused before."
  [config body]
  (if-let [ks (seq (get @refused (k config)))]
    (apply dissoc body ks)
    body))

(defn refused-in
  "The droppable fields of `body` that the error text `err` says the endpoint
  refused, or nil. Read only from a 400: a 5xx is not a statement about the
  request."
  [status body err]
  (let [t (str err)
        signals (try (some-> (lexicon/wordlist :rejected-field-signals) re-pattern)
                     (catch Throwable _ nil))]
    (when (and (= 400 status) signals (re-find signals t))
      (not-empty (filterv #(and (contains? body %) (str/includes? t (name %))) droppable)))))

(defn learn!
  "Remember that `config`'s endpoint refuses `fields`. True when any was new
  — the caller sends again only then, so a refusal cannot loop."
  [config fields]
  (let [was (get @refused (k config) #{})
        now (into was fields)]
    (swap! refused assoc (k config) now)
    (not= was now)))

(defn forget!
  "Drop everything remembered. For tests."
  []
  (reset! refused {}))
