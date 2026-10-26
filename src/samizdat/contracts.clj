;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.contracts
  "One declaration for an external payload's schema and its decoded value.

  Foreign JSON is checked at the boundary: required fields, scalar types and
  unknown members all come from the same field map that JSON Schema serves."
  (:require [clojure.string :as str]))

(defonce ^:private registry (atom {}))

(defn register!
  "Register a contract id and return it."
  [id spec]
  (swap! registry assoc id spec)
  id)

(defmacro defcontract
  "Define a contract var and register its field declarations."
  [id spec]
  `(def ~id (register! (keyword ~(name id)) ~spec)))

(defn schema
  "The JSON-Schema projection of a contract var or id."
  [contract]
  (let [c (if (keyword? contract) (get @registry contract) contract)
        fields (:fields c)
        required (vec (for [[k spec] fields :when (:required spec)] (name k)))]
    (cond-> {:type "object"
             :properties (into {} (for [[k spec] fields]
                                    [(name k) (select-keys spec [:type :description])]))
             :additionalProperties (boolean (:allow-unknown? c))}
      (seq required) (assoc :required required))))

(defn- type-error [expected actual]
  (str "expected " expected ", received " (pr-str actual)))

(defn- valid-scalar? [kind value]
  (case kind
    :any true
    :string (string? value)
    :non-blank-string (and (string? value) (not (str/blank? value)))
    :pos-int (pos-int? value)
    false))

(defn- field-problem [value k spec]
  (let [present? (contains? value k)
        v (get value k)]
    (or (when (and (:required spec) (not present?))
          {:field k :message (str "missing required field " k)})
        (when (and present? (not (valid-scalar? (:type spec :any) v)))
          {:field k :message (type-error (:type spec :any) v)}))))

(defn decode
  "Decode a foreign payload as {:value MAP} or {:error PROBLEM}."
  [contract value]
  (let [c (if (keyword? contract) (get @registry contract) contract)
        fields (:fields c)
        unknown (when (and (map? value) (not (:allow-unknown? c)))
                  (some #(when (not (contains? fields %)) %) (keys value)))
        problem (when (map? value)
                  (some (fn [[k spec]] (field-problem value k spec)) fields))]
    (cond
      (not (map? value))
      {:error {:message "expected an object" :type "invalid_request_error"}}

      unknown
      {:error {:message (str "unknown-option " unknown) :field unknown
               :type "invalid_request_error"}}

      problem
      {:error (assoc problem :type "invalid_request_error")}

      (:required-any c)
      (if (some #(valid-scalar? (get-in c [:fields % :type] :any) (get value %))
                (:required-any c))
        {:value value}
        {:error {:message (str "one of " (str/join ", " (:required-any c)) " is required")
                 :type "invalid_request_error"}})

      :else
      {:value value})))

(defn problem
  "The contract's problem text, or nil when the payload decodes."
  [contract value]
  (get-in (decode contract value) [:error :message]))

(defcontract run-request-contract
  {:allow-unknown? false
   :fields {:problem {:type :non-blank-string :required true
                      :description "The task the run should perform"}
            :beam_width {:type :pos-int :description "Maximum initial branches"}
            :max_turns {:type :pos-int :description "Maximum rounds per branch"}
            :token_budget {:type :pos-int :description "Whole-run token budget"}
            :model {:type :non-blank-string}
            :reasoning_effort {:type :non-blank-string}
            :seed_run {:type :non-blank-string}
            :quarantine {:type :any}
            :modes {:type :any}}})

(defcontract fetch-turn-contract
  {:allow-unknown? false
   :required-any [:turn :query]
   :fields {:turn {:type :any :description "Turn number, or tN"}
            :query {:type :non-blank-string :description "Literal turn search"}
            :branch {:type :non-blank-string}
            :limit {:type :pos-int}
            :before {:type :pos-int}}})
