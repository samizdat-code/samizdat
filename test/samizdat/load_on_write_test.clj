;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.load-on-write-test
  "A Clojure file written while a project REPL is up is loaded there, and the write's own
  result says whether it loaded: the live-coding answer to memdoor's build-after-edit.
  A model never has to remember to reload to learn that a file it wrote is broken."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.tools :as tools]
            [samizdat.repl.route :as route]))

(defn- root []
  (let [d (str "/tmp/samizdat-load-on-write-" (System/nanoTime))]
    (fs/create-dirs (str d "/src/shop"))
    d))

(defn- write [ctx path content]
  (tools/run-tool (assoc ctx :tool-name "write_file" :args {:path path :content content})))

(deftest a-written-namespace-is-loaded-and-the-result-says-so
  (let [d (root) evals (atom [])
        ctx {:root d :branch {:id "B1"} :config {}}]
    (try
      (with-redefs [route/image-of (fn [_] :project)
                    route/eval-for (fn [_ code & _] (swap! evals conj code)
                                     (if (str/includes? code "shop.bad")
                                       {:ok false :error "Unable to resolve symbol: nope"}
                                       {:ok true :value "nil"}))]
        (let [r (write ctx "src/shop/core.clj" "(ns shop.core)\n(defn f [] 1)\n")]
          (is (= ["(require 'shop.core :reload)"] @evals))
          (is (str/includes? (:result r) "shop.core"))
          (is (str/includes? (:result r) "loaded")))
        (testing "a load error comes back in the same result"
          (let [r (write ctx "src/shop/bad.clj" "(ns shop.bad)\n(defn g [] nope)\n")]
            (is (str/includes? (:result r) "Unable to resolve symbol: nope"))))
        (testing "a file that is not Clojure is not loaded"
          (reset! evals [])
          (write ctx "notes.md" "# hi\n")
          (is (empty? @evals))))
      (testing "with no project REPL nothing is evaluated"
        (let [evals (atom [])]
          (with-redefs [route/image-of (fn [_] :harness)
                        route/eval-for (fn [& _] (swap! evals conj :called) {:ok true})]
            (write ctx "src/shop/other.clj" "(ns shop.other)\n")
            (is (empty? @evals)))))
      (finally (fs/delete-tree d)))))
