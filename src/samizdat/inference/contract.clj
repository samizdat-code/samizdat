;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference.contract
  "Small JSON Schema to Malli contracts for inference frame results."
  (:require [clojure.data.json :as json]
            [malli.core :as m]))

(declare normalize)

(defn- object-schema [schema]
  (let [properties (:properties schema)
        required (set (:required schema))]
    (into [:map {:closed (false? (:additionalProperties schema))}]
          (for [[label child] properties]
            [(keyword label)
             (when-not (contains? required label) {:optional true})
             (normalize child)]))))

(defn normalize
  "Normalize a JSON Schema-shaped map, or a Malli schema, for frame results."
  [schema]
  (cond
    (nil? schema) :text
    (or (= :any schema) (= "any" schema) (= {} schema)) :any
    (= :text schema) :text
    (vector? schema) schema
    :else
    (case (:type schema)
      ("string" :string) :string
      ("integer" :integer) :int
      ("number" :number) number?
      ("boolean" :boolean) :boolean
      ("array" :array) [:vector (normalize (:items schema))]
      ("object" :object) (object-schema schema)
      (throw (ex-info "unsupported inference contract"
                      {:inference/error :unsupported-schema :schema schema})))))

(defn decode
  "Decode and validate one JSON value, returning {:value ...} or {:error ...}."
  [schema text]
  (if (= :text schema)
    {:value text}
    (try
      (let [value (json/read-str text :key-fn keyword)]
      (if (m/validate schema value)
        {:value value}
        {:error :schema-invalid}))
    (catch Throwable _
      {:error :json-invalid}))))
