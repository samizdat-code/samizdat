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

(ns samizdat.llm.windows
  "The context window an endpoint ACTUALLY takes, learned from its own
  overflow errors (karamazov-kfnn.6).

  A config that says 1M in front of a server started with -c 32768
  overflows, and the loop squeezes the branch one notch at a time — a failed
  call per notch. When the error names the limit (\"maximum context length
  is 32768 tokens\"), that number is the window from then on, for that
  endpoint and model, in this process. It only narrows: a learned window is
  what the server refused past, and nothing later can make it larger.

  How endpoints word the number is wordlists.edn :context-window-numbers
  (each a regex whose first group is the window), because the wording is
  theirs to change."
  (:require [samizdat.lexicon :as lexicon]))

(defonce ^:private learned
  ;; [base-url model] -> tokens
  (atom {}))

(defn- k [config] [(str (:base-url config)) (str (:model config))])

(defn window-in
  "The window `body` names, in tokens, or nil. The smallest plausible number
  any pattern finds: a message carries the request's size too, and the
  window is the limit it was refused against."
  [body]
  (let [b (str body)
        ns (for [p (or (try (lexicon/wordlist :context-window-numbers) (catch Throwable _ nil)) [])
                 :let [re (try (re-pattern (str p)) (catch Throwable _ nil))]
                 :when re
                 m (re-seq re b)
                 :let [n (try (Long/parseLong (str (if (vector? m) (second m) m))) (catch Throwable _ nil))]
                 :when (and n (pos? n))]
             n)]
    (when (seq ns) (apply min ns))))

(defn learn!
  "Record that the endpoint `config` names takes at most `n` tokens."
  [config n]
  (when (and n (pos? n))
    (swap! learned update (k config) #(if % (min % n) n)))
  nil)

(defn learned-window
  "The window learned for `config`'s endpoint and model, or nil."
  [config]
  (get @learned (k config)))

(defn effective
  "The window a request to `config` is sized against: what the config says
  the provider takes (:request-window, else :context-window, else
  :max-context), narrowed to what the endpoint was seen to refuse past."
  [config]
  (let [w (or (:request-window config) (:context-window config) (:max-context config))
        l (learned-window config)]
    (if (and l (or (nil? w) (< l w))) l w)))

(defn forget!
  "Drop everything learned. For tests."
  []
  (reset! learned {}))
