;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.sections
  "Read a role's reply as named sections. Pure.

  A one-call role answers in sections a program reads (`### PLAN`, `### STATUS`).
  Small models vary the header's shape and sometimes stop right after the last
  one, so the parser takes the shapes they write and keeps a section that has
  no body. What a missing section MEANS is the caller's decision."
  (:require [clojure.string :as str]
            [samizdat.llm.message :as message]))

(defn reply-text
  "A reply as the program should read it: no reasoning block, no surrounding space.
  A reply that is nothing but reasoning is kept, tags off, so a model that never got
  past thinking still says something."
  [text]
  (let [s (str text)
        clean (str/trim (str (message/strip-think-blocks s)))]
    (if (str/blank? clean)
      (str/trim (str/replace s #"</?think>" ""))
      clean)))

(defn strip-code
  "Fenced code replaced by a marker, for what must stay approach-only."
  [text]
  (str/trim (str/replace (str text) #"(?s)```.*?```" "[code omitted]")))

(defn- header
  "The section name a line opens, uppercased, or nil."
  [line]
  (let [s (str/trim line)]
    (some-> (or (second (re-matches #"#{1,3}\s*([A-Za-z_]+)\s*" s))
                (second (re-matches #"\*\*([A-Za-z_]+)\*\*\s*:?\s*" s))
                (second (re-matches #"([A-Z_]{3,})\s*:\s*" s)))
            str/upper-case)))

(defn parse
  "{SECTION body} for every header in `text`. Text before the first header is
  dropped. {} when there are no headers."
  [text]
  (let [flush (fn [{:keys [cur buf] :as acc}]
                (cond-> acc
                  cur (assoc-in [:out cur] (str/trim (str/join "\n" buf)))))
        end (reduce (fn [{:keys [cur] :as acc} line]
                      (if-let [h (header line)]
                        (assoc (flush acc) :cur h :buf [])
                        (cond-> acc cur (update :buf conj line))))
                    {:out {} :cur nil :buf []}
                    (str/split-lines (reply-text text)))]
    (:out (flush end))))

(defn bullets
  "The list items in `text`, in order, whichever list marker they use."
  [text]
  (into []
        (keep (fn [line]
                (some-> (re-matches #"\s*(?:[-*]|\d+[.)])\s+(.*\S)\s*" line)
                        second str/trim)))
        (str/split-lines (str text))))

(defn counterexample
  "{:input :expected} from a parsed reply, or nil. INPUT: and EXPECTED: read as
  section headers of their own. Half of one is none: an input with no expected
  output cannot be checked."
  [sections]
  (let [input (some-> (get sections "INPUT") str/trim not-empty)
        expected (some-> (get sections "EXPECTED") str/trim not-empty)]
    (when (and input expected)
      {:input input :expected expected})))
