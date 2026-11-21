;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.code-lint-test
  "Discouraged vars a run writes, found with jolt-lsp's :discouraged-var linter
  against where the tree started. What the project already had is not the
  run's to answer for."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.agent.arbiter :as arbiter]
            [samizdat.agent.code-lint :as lint]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.state :as state]))

(def ^:private policy (gates/threshold :code-lint))

(defn- project
  "A throwaway project with these files ({relative-path text}); returns its root."
  [files]
  (let [root (.getCanonicalPath (doto (io/file (System/getProperty "java.io.tmpdir")
                                               (str "samizdat-lint-" (random-uuid)))
                                  .mkdirs))]
    (spit (io/file root "deps.edn") "{:paths [\"src\"]}")
    (doseq [[p text] files]
      (let [f (io/file root p)]
        (io/make-parents f)
        (spit f text)))
    root))

(deftest the-policy-expands-its-presets
  (let [cfg (lint/discouraged-config policy)]
    (is (contains? (:vars cfg) "clojure.core/eval"))
    (is (contains? (:vars cfg) "clojure.core/read-string"))))

(deftest what-the-run-introduced-is-found
  (let [root (project {"src/a.clj" "(ns a)\n(defn f [x] (eval x))\n"})
        found (lint/findings policy root {"src/a.clj" "(ns a)\n(defn f [x] x)\n"})]
    (is (= [["src/a.clj" "clojure.core/eval" 2]] (mapv (juxt :path :var :row) found)))
    (is (= :error (:level (first found))))
    (is (str/includes? (lint/report policy found) "src/a.clj"))))

(deftest a-new-file-counts
  (let [root (project {"src/b.clj" "(ns b (:require [clojure.core :as c]))\n(defn g [s] (c/read-string s))\n"})]
    (is (= ["clojure.core/read-string"]
           (mapv :var (lint/findings policy root {"src/b.clj" nil}))))))

(deftest what-was-already-there-is-not
  (let [old "(ns a)\n(defn f [x] (eval x))\n"]
    (testing "untouched"
      (let [root (project {"src/a.clj" (str old "(defn g [] 1)\n")})]
        (is (empty? (lint/findings policy root {"src/a.clj" old})))))
    (testing "one more use is the run's"
      (let [root (project {"src/a.clj" (str old "(defn g [y] (eval y))\n")})]
        (is (= [3] (mapv :row (lint/findings policy root {"src/a.clj" old}))))))))

(deftest a-shadowing-local-is-not-the-var
  (let [root (project {"src/a.clj" "(ns a)\n(defn f [eval] (eval 1))\n"})]
    (is (empty? (lint/findings policy root {"src/a.clj" nil})))))

(deftest what-cannot-be-read-is-left-alone
  (let [root (project {"src/bad.clj" "(defn f [] (eval 1"
                       "notes.md" "(eval 1)"})]
    (is (empty? (lint/findings policy root {"src/bad.clj" nil "notes.md" nil})))
    (is (nil? (lint/of-tree "/nonexistent/root" "HEAD" policy)))))

(defn- ctx [m] {:branch (merge (state/new-branch {:id "B1" :problem "p"}) {:role :implementer} m) :max-turns 300})
(defn- fired [c] (set (map :gate (arbiter/eligible c))))
(def ^:private found [{:path "src/a.clj" :var "clojure.core/eval" :row 2}])

(deftest the-gate
  (testing "speaks when the run wrote a discouraged call, and says where"
    (is (contains? (fired (ctx {:lint-findings found})) :discouraged-code))
    (let [d (arbiter/decide (ctx {:lint-findings found :lint-report "src/a.clj eval"}))]
      (is (= :discouraged-code (:gate d)))
      (is (str/includes? (:message d) "src/a.clj eval"))))
  (testing "not otherwise"
    (is (not (contains? (fired (ctx {})) :discouraged-code)))
    (is (not (contains? (fired (ctx {:lint-findings []})) :discouraged-code))))
  (testing "a limited number of times, however it settles"
    (let [n (gates/threshold :max-lint-steers)
          told (vec (mapcat (fn [t] [{:gate :discouraged-code :turn t} {:gate :discouraged-code :turn t :settled :met}])
                            (range n)))]
      (is (not (contains? (fired (ctx {:lint-findings found :gate-history told})) :discouraged-code)))))
  (testing "a write answers it"
    (is (contains? (:discouraged-code (gates/tool-vocab :settle-called)) "edit_file"))))
