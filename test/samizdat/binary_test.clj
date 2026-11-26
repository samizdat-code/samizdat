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

(ns samizdat.binary-test
  "What `jolt build` needs that `jolt serve` does not. A source run loads a
  stdlib namespace the first time one of its classes is named, so a missing
  require goes unnoticed; a built binary carries only the namespaces the
  program requires, and the same call fails there with \"No dependency
  provides java.net.ServerSocket\". So every file naming such a class must
  require the namespace that registers it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private providers
  "A class reference, and the stdlib namespace that registers the class."
  {#"java\.net\.(Server)?Socket\b" "jolt.socket"})

(defn- sources []
  (->> ["src/samizdat" "tui/samizdat" "acp/samizdat"]
       (mapcat #(file-seq (java.io.File. %)))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".clj"))))

(deftest a-file-naming-a-stdlib-class-requires-its-namespace
  (doseq [f (sources)
          :let [text (slurp f)]
          [pattern provider] providers
          :when (re-find pattern text)]
    (is (re-find (re-pattern (str "\\[" (str/replace provider "." "\\.") "[\\s\\]]")) text)
        (str (.getPath f) " names a class " provider " registers but does not require it;"
             " the standalone binary will not have the class"))))
