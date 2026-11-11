;; samizdat - a self-hosting agentic harness
;; Movement and declare fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-move-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt-lsp.surgeon.analyze :as analyze]
            [jolt-lsp.surgeon.fix-declares :as declares]
            [jolt-lsp.surgeon.forms :as forms]
            [jolt-lsp.surgeon.forward-refs :as forward-refs]
            [samizdat.surgeon.intent-transaction :as intent]
            [jolt-lsp.surgeon.move :as move]))

(defn- before? [source left right]
  (< (str/index-of source left) (str/index-of source right)))

(defn- forward-ref [name used-at defined-at]
  {:name (symbol name) :used-at used-at :defined-at defined-at
   :gap (- defined-at used-at)})

(def dependent-source
  (str "(ns sample.move)\n\n"
       "(declare worker)\n\n"
       "(defn destination [] (worker))\n\n"
       ";; Configuration\n"
       "(def ^:private config 1)\n\n"
       "(defn- audit [x] (inc x))\n\n"
       ";; Worker\n"
       "(defn ^:private worker [] (audit config))\n"))

(def simple-declare-source
  "(ns sample.declares)\n\n(declare helper)\n\n(defn main []\n  (helper 42))\n\n(defn helper [x]\n  (inc x))\n")

(def simple-declare-fixed-source
  "(ns sample.declares)\n\n\n(defn helper [x]\n  (inc x))\n\n(defn main []\n  (helper 42))\n\n")

(deftest native-move-and-declare-operations-compile-to-future-sources
  (let [file "src/move.clj"
        result (intent/compile-changes
                {file dependent-source}
                {:changes [{:id :move :in [file] :forms ['worker]
                            :do [:move {:before 'destination :with-deps? true}]
                            :expect {:matches 1}}]
                 :expect {:changes 1 :edits 1 :files 1}})]
    (is (:ok result) (pr-str result))
    (is (before? (get-in result [:future-sources file]) ";; Worker" "(defn destination")))
  (let [file "src/declares.clj"
        result (intent/compile-changes
                {file simple-declare-source}
                {:changes [{:id :declares :in [file]
                            :do [:fix-declares true] :expect {:matches 1}}]
                 :expect {:changes 1 :edits 1 :files 1}})]
    (is (:ok result) (pr-str result))
    (is (not (str/includes? (get-in result [:future-sources file]) "(declare helper)")))))

(deftest native-operation-refusals-return-no-future-source
  (let [file "src/refusal.clj"
        error (try
                (intent/compile-changes
                 {file dependent-source}
                 {:changes [{:id :move :in [file] :forms ['worker]
                             :do [:move {:before 'destination :with-deps? false}]
                             :expect {:matches 1}}]
                  :expect {:changes 1 :edits 1 :files 1}})
                nil
                (catch clojure.lang.ExceptionInfo exception (ex-data exception)))]
    (is (= :would-strand-dependencies (:surgeon/error error)))
    (is (nil? (:future-sources error)))))
