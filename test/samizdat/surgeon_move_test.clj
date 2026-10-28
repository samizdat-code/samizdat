;; samizdat - a self-hosting agentic harness
;; Movement and declare fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-move-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.surgeon.analyze :as analyze]
            [samizdat.surgeon.fix-declares :as declares]
            [samizdat.surgeon.forward-refs :as forward-refs]
            [samizdat.surgeon.intent-transaction :as intent]
            [samizdat.surgeon.move :as move]))

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

(deftest dependency-closure-is-minimal-topological-and-comment-preserving
  (let [result (move/plan-form dependent-source
                               {:form "worker" :before "destination" :with-deps? true})
        future (:future-source result)]
    (is (= ["config" "audit" "worker"] (get-in result [:plan :move-order])))
    (is (= ["config" "audit"] (get-in result [:plan :added-forms])))
    (is (before? future ";; Configuration" ";; Worker"))
    (is (before? future "(defn- audit" "(defn ^:private worker"))
    (is (before? future ";; Worker" "(defn destination"))
    (is (str/includes? future "(def ^:private config 1)"))))

(deftest exact-move-refuses-stranded-dependencies-and-callers
  (let [dependency-result (move/plan-form dependent-source
                                          {:form "worker" :before "destination"})]
    (is (= :would-strand-dependencies (:error-type dependency-result)))
    (is (= ["config" "audit"] (mapv :name (:stranded dependency-result))))
    (is (not (contains? dependency-result :future-source))))
  (let [source (str "(ns sample.move)\n\n"
                    "(defn callee [] :ok)\n\n"
                    "(defn caller [] (callee))\n\n"
                    "(defn tail [] nil)\n")
        result (move/plan-form source {:form "callee" :before "tail" :with-deps? true})]
    (is (= :would-strand-users (:error-type result)))
    (is (= ["caller"] (mapv :name (:stranded-users result))))
    (is (not (contains? result :future-source)))))

(deftest cyclic-closures-and-ambiguous-forms-fail-closed
  (let [source (str "(ns sample.move)\n\n"
                    "(declare a)\n\n"
                    "(defn destination [] (a))\n\n"
                    "(declare b)\n\n"
                    "(defn a [] (b))\n\n"
                    "(defn b [] (a))\n")
        result (move/plan-form source {:form "a" :before "destination" :with-deps? true})]
    (is (= :cyclic-move-dependencies (:error-type result)))
    (is (= #{"a" "b"} (set (:cycle result))))
    (is (not (contains? result :future-source))))
  (let [source "(ns sample.move)\n(defn destination [] nil) (defn worker [] 1) (defn worker [] 2)\n"
        result (move/plan-form source {:form "worker" :before "destination"})]
    (is (= :ambiguous-form (:error-type result)))
    (is (not (contains? result :future-source)))))

(deftest analysis-excludes-locals-and-quotes-but-keeps-destructuring-defaults
  (doseq [source ["(ns x) (def config 1) (defn worker [config] config)"
                  "(ns x) (def config 1) (defn worker [] 'config)"
                  "(ns x) (def config 1) (defn worker [] (let [config 2] config))"
                  "(ns x) (def config 1) (defn worker [{:keys [config]}] config)"]]
    (let [deps (analyze/intra-ns-deps (analyze/string->zloc source))
          worker (first (filter #(= "worker" (:name %)) deps))]
      (is (= #{} (:depends-on worker)))))
  (let [source "(ns x) (def fallback 1) (defn worker [{:keys [x] :or {x fallback}}] x)"
        deps (analyze/intra-ns-deps (analyze/string->zloc source))
        worker (first (filter #(= "worker" (:name %)) deps))]
    (is (= #{"fallback"} (:depends-on worker)))))

(deftest forward-reference-evidence-is-a-pure-analysis-projection
  (let [analysis {:var-definitions [{:ns 'sample.core :name 'helper :row 8}]
                  :var-usages [{:from 'sample.core :to 'sample.core :name 'helper :row 5}
                               {:from 'sample.core :to 'sample.core :name 'helper :row 6}
                               {:from 'sample.other :to 'sample.core :name 'helper :row 2}]}
        result (forward-refs/from-analysis analysis 'sample.core)]
    (is (= [{:name 'helper :used-at 5 :defined-at 8 :gap 3}] result))))

(def simple-declare-source
  "(ns sample.declares)\n\n(declare helper)\n\n(defn main []\n  (helper 42))\n\n(defn helper [x]\n  (inc x))\n")

(deftest declare-planning-uses-injected-evidence-and-removes-stale-declares
  (let [result (declares/plan-fix simple-declare-source "sample.clj"
                                  {:forward-refs [(forward-ref "helper" 6 8)]})
        future (:future-source result)]
    (is (not (str/includes? future "(declare helper)")))
    (is (before? future "(defn helper" "(defn main"))
    (is (= 1 (get-in result [:summary :moves])))
    (is (= 1 (get-in result [:summary :declares-deleted]))))
  (let [stale "(ns sample.declares)\n\n(declare helper)\n\n(defn helper [] 1)\n\n(defn main [] (helper))\n"
        result (declares/plan-fix stale "sample.clj" {:forward-refs []})]
    (is (not (str/includes? (:future-source result) "(declare helper)")))
    (is (= 1 (get-in result [:summary :stale])))
    (is (= 1 (get-in result [:summary :declares-deleted])))))

(deftest declare-planning-pulls-leaves-and-preserves-cycles
  (let [source (str "(ns sample.declares)\n\n"
                    "(declare foo ping)\n\n"
                    "(defn caller [] (foo (ping 1)))\n\n"
                    ";; configuration\n"
                    "(def config 1)\n\n"
                    "(defn foo [x] (+ x config))\n\n"
                    "(defn ping [x] (pong x))\n\n"
                    "(defn pong [x] (ping x))\n")
        result (declares/plan-fix source "sample.clj"
                                  {:forward-refs [(forward-ref "foo" 6 11)
                                                  (forward-ref "ping" 6 13)]})
        future (:future-source result)]
    (is (before? future ";; configuration" "(defn caller"))
    (is (before? future "(defn foo" "(defn caller"))
    (is (str/includes? future "(declare ping)"))
    (is (= ["ping"] (:needed-declares result)))
    (is (= 1 (get-in result [:summary :safe-with-pull])))))

(deftest unsafe-declare-move-is-skipped-without-deleting-the-declare
  (let [source (str "(ns sample.declares)\n\n"
                    "(declare foo)\n\n"
                    "(defn caller [] (foo 42))\n\n"
                    "(defn deep-helper [x] (inc x))\n\n"
                    "(defn compute [x] (deep-helper x))\n\n"
                    "(defn foo [x] (compute x))\n")
        result (declares/plan-fix source "sample.clj"
                                  {:forward-refs [(forward-ref "foo" 5 14)]})]
    (is (= source (:future-source result)))
    (is (= 1 (get-in result [:summary :unsafe])))
    (is (= 1 (get-in result [:summary :skipped])))
    (is (= ["compute"] (vec (:unresolved-deps (first (:actions result))))))))

(deftest two-arity-declare-planning-derives-pure-source-evidence
  (let [result (declares/plan-fix simple-declare-source "sample.clj")]
    (is (before? (:future-source result) "(defn helper" "(defn main"))
    (is (not (str/includes? (:future-source result) "(declare helper)")))))

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
