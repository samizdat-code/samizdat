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

(ns samizdat.eval-hint-test
  "An eval error says what works instead (karamazov-0e2c.18, after xi's
  interop-hint): a model with JVM habits in jolt loses a turn to each one
  unless the error names the move that works."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.repl :as repl-tool]
            [samizdat.repl.route :as route]))

(deftest the-error-names-what-works
  (is (re-find #"(?i)require" (str (repl-tool/eval-hint "Unknown class gates"))))
  (is (re-find #"(?i)shell" (str (repl-tool/eval-hint "Could not locate clojure/java/shell.jolt (or .clj/.cljc) on the source roots"))))
  (is (re-find #"(?i)deps.edn|:paths" (str (repl-tool/eval-hint "Could not locate flight/core.jolt (or .clj/.cljc) on the source roots"))))
  (is (nil? (repl-tool/eval-hint "Divide by zero")) "an ordinary error carries no hint"))

(deftest an-eval-error-carries-its-hint
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "Unknown class str"})]
    (let [r (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(str/join [1])"}})]
      (is (str/includes? (:result r) "Unknown class str"))
      (is (re-find #"(?i)require" (:result r)) (:result r)))))

(deftest a-failed-batch-says-which-form-and-what-already-ran
  ;; arrodes' failure detail (karamazov-xn0h.3): the form, its line, and that
  ;; the ones before it ran — so the model fixes one form rather than
  ;; re-running a batch whose first forms already took effect.
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "Divide by zero"
                                          :form {:index 2 :count 3 :line 4 :completed 1}})]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"}
                                      :args {:code "(def a 1)\n(/ 1 0)\n(def b 2)"}}))]
      (is (str/includes? r "Divide by zero"))
      (is (re-find #"form 2 of 3" r) r)
      (is (re-find #"line 4" r) r)
      (is (re-find #"(?i)1 form.*ran" r) r)))
  (testing "a single form says nothing extra"
    (with-redefs [route/eval-for (fn [& _] {:ok false :error "Divide by zero"
                                            :form {:index 1 :count 1 :line 1 :completed 0}})]
      (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"}
                                        :args {:code "(/ 1 0)"}}))]
        (is (not (re-find #"form 1 of 1" r)) r)))))

(deftest the-diagnosis-names-the-image-it-ran-in
  ;; eval-error.md was appended only on the harness path, and said "the live
  ;; image" — under :project the session lives in a separate process.
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "boom" :where "samizdat.repl.session-9"})
                route/image-of (constantly :project)]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(f)"}}))]
      (is (str/includes? r "samizdat.repl.session-9"))
      (is (re-find #"project's image" r) r)
      (is (not (re-find #"harness image" r)) r)))
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "boom" :where "samizdat.repl.session-9"})
                route/image-of (constantly :harness)]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(f)"}}))]
      (is (re-find #"harness image" r) r))))
