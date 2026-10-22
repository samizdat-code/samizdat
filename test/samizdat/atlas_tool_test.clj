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

(ns samizdat.atlas-tool-test
  "The agent's `atlas` tool (RFC-016): the code graph and its flows as
  answers to what calls what, what a change reaches, and which programs
  inside the project a function serves."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.agent.tools.atlas]
            [samizdat.agent.tools.base :as base]
            [samizdat.atlas :as atlas]
            [samizdat.atlas-test :as at]))

(use-fixtures :each
  (fn [t]
    (atlas/forget!)
    (reset! at/files at/initial)
    (with-redefs [atlas/policy (constantly (assoc at/policy :max-listed 10))
                  atlas/default-io at/io]
      (t))))

(defn- run [args]
  (base/run-tool {:branch {:id "B1"} :root "/p" :tool-name "atlas" :args args}))

(deftest the-atlas-tool
  (testing "search"
    (is (str/includes? (:result (run {:op "search" :q "save"})) "app.db/save!")))
  (testing "callers and callees with their sites"
    (let [r (:result (run {:op "callers" :id "app.db/save!"}))]
      (is (str/includes? r "app.core/handle"))
      (is (str/includes? r "src/app/core.clj:2"))))
  (testing "impact is every transitive caller and the flows that reach it"
    (let [r (:result (run {:op "impact" :id "app.db/save!"}))]
      (is (str/includes? r "app.core/-main"))
      (is (str/includes? r "app.core/handle"))))
  (testing "a flow's paths"
    (is (str/includes? (:result (run {:op "flow" :entry "app.core/-main"}))
                       "-main → handle → save!")))
  (testing "the card has the source"
    (is (str/includes? (:result (run {:op "card" :id "app.core/handle"}))
                       "(defn handle [x] (db/save! x))")))
  (testing "a read establishes nothing"
    (is (= :neutral (:category (run {:op "search" :q "x"})))))
  (testing "an unknown id and an unknown op are malformed"
    (is (= :mechanics (:category (run {:op "callers" :id "nope"}))))
    (is (= :mechanics (:category (run {:op "frobnicate"}))))))
