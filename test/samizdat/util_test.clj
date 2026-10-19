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

(ns samizdat.util-test
  "Tests for samizdat.util/truncate-middle."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]
            [samizdat.util :as util]))

(deftest truncate-middle-test
  (testing "short string returned unchanged"
    (is (= "hello" (util/truncate-middle "hello" 10))))

  (testing "long string shortened to exactly max-len"
    (let [s "The quick brown fox jumps over the lazy dog"
          result (util/truncate-middle s 20)]
      (is (= 20 (count result)))
      (is (= "The quic …  lazy dog" result))))

  (testing "middle marker present"
    (let [result (util/truncate-middle "abcdefghijklmnopqrstuvwxyz" 10)]
      (is (str/includes? result " … ")))))

(deftest the-harness-checkout-is-where-its-code-was-loaded-from
  ;; Not the working directory: a built samizdat runs in the project it works
  ;; on, and took that project for its own checkout — /health reported
  ;; dodeca's commit as the harness's revision.
  (testing "from source, the checkout holding samizdat's code"
    (let [dir (util/harness-checkout)]
      (is (string? dir))
      (is (.exists (java.io.File. (str dir "/src/samizdat/core.clj"))))))
  (testing "in a built binary, where the code is no file, none"
    (with-redefs [clojure.java.io/resource (fn [& _] nil)]
      (is (nil? (util/harness-checkout))))))
