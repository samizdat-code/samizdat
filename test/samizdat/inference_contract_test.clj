;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.inference-contract-test
  (:require [clojure.test :refer [deftest is]]
            [samizdat.inference.contract :as contract]))

(deftest contracts-accept-every-json-value-shape
  (is (= {:value false}
         (contract/decode (contract/normalize :any) "false")))
  (is (= {:value nil}
         (contract/decode (contract/normalize :any) "null")))
  (is (= {:value [1 "two" true]}
         (contract/decode (contract/normalize :any) "[1,\"two\",true]"))))

(deftest object-contracts-check-required-fields-and-types
  (let [schema (contract/normalize
                {:type "object"
                 :properties {"answer" {:type "string"}}
                 :required ["answer"]
                 :additionalProperties false})]
    (is (= {:value {:answer "yes"}}
           (contract/decode schema "{\"answer\":\"yes\"}")))
    (is (= :schema-invalid
           (:error (contract/decode schema "{\"answer\":4}"))))
    (is (= :schema-invalid
           (:error (contract/decode schema "{}"))))
    (is (= :schema-invalid
           (:error (contract/decode schema "{\"answer\":\"yes\",\"extra\":1}"))))))

(deftest numbers-accept-integers-and-floating-values
  (let [schema (contract/normalize {:type "number"})]
    (is (= {:value 1} (contract/decode schema "1")))
    (is (= {:value 1.5} (contract/decode schema "1.5")))))

(deftest malformed-json-is-a-structured-error
  (is (= :json-invalid
         (:error (contract/decode (contract/normalize :any) "{")))))
