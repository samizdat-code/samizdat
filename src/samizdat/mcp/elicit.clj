;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.elicit
  "MCP elicitation as questions a person answers (karamazov-k7gl.3).

  A server may ask, in the middle of a call, for something only the user
  knows: `elicitation/create` with a message and a flat object schema of
  strings, numbers, integers, booleans and enums. Pure translation both
  ways: the schema into the questionnaire the approvals queue already
  carries for ask_human (so every front end that answers one answers
  this), and the answer back into the result the server reads —
  {:action \"accept\" :content {...}}, \"decline\" or \"cancel\". Who is
  asked and for how long is the `mcp` tool's."
  (:require [clojure.string :as str]))

(defn- properties [schema]
  (seq (:properties schema)))

(defn questions
  "The questions for `schema`'s properties, in order, or nil when it asks
  for nothing beyond a yes or no."
  [schema]
  (when-let [ps (properties schema)]
    (mapv (fn [[k p]]
            (let [label (or (:title p) (name k))]
              (cond-> {:question (str label (when-let [d (:description p)] (str " — " d)))
                       :header (name k)
                       :options (cond
                                  (seq (:enum p)) (mapv str (:enum p))
                                  (= "boolean" (:type p)) ["true" "false"]
                                  :else [])}
                (seq (:enumNames p)) (assoc :descriptions (mapv str (:enumNames p))))))
          ps)))

(defn- coerce
  "Answer `a` as property `p` wants it, or nil when it does not fit."
  [p a]
  (let [s (str/trim (str (if (sequential? a) (first a) a)))]
    (when-not (str/blank? s)
      (case (:type p)
        "integer" (parse-long s)
        "number" (parse-double s)
        "boolean" (case (str/lower-case s) ("true" "yes" "y") true ("false" "no" "n") false nil)
        s))))

(defn result
  "The elicitation result for a person's `answer` (an approvals decision)
  to `schema`. An answer that ran out of time, or that a run ending
  released, is cancel; a refusal is decline; an allow is accept with the
  answers by position — unless a required field is left without one, which
  is no answer at all."
  [schema {:keys [decision answers timed-out abandoned]}]
  (cond
    (or timed-out abandoned) {:action "cancel"}
    (not= :allow decision) {:action "decline"}
    :else
    (let [ps (properties schema)
          content (into {}
                        (keep-indexed (fn [i [k p]]
                                        (when-some [v (coerce p (nth (vec answers) i nil))]
                                          [k v])))
                        ps)
          required (set (map keyword (:required schema)))]
      (if (every? #(contains? content %) required)
        {:action "accept" :content content}
        {:action "cancel"}))))
