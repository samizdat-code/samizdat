;; Ported from sdiff, https://github.com/semantic-namespace/diff (commit
;; 70e436e), MIT License, Copyright (c) 2026 @tangrammer.
;;
;; SPDX-License-Identifier: MIT

(ns samizdat.sdiff.group-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-lsp.sdiff.core :as core]
            [jolt-lsp.sdiff.group :as group]
            [samizdat.agent.gitdiff :as gitdiff]))

(def report
  {:clj [(core/file-report "src/a.clj"
                           "(ns a (:require [b :as b]))\n(defn f [x] (b/g x))\n(defn lone [] 1)\n"
                           "(ns a (:require [b :as b]))\n(defn f [x] (b/g (inc x)))\n(defn lone [] 2)\n")
         (core/file-report "src/b.clj"
                           "(ns b)\n(defn g [x] x)\n"
                           "(ns b)\n(defn g [x] [x])\n")]})

(deftest the-groups-read-as-text
  (let [t (gitdiff/change-groups-text (group/by-calls report))]
    (is (str/includes? t "defn f"))
    (is (str/includes? t "defn lone"))))
