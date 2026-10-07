;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.propose-test
  "propose_cell: a branch offers a callable cell to the supervisor, checked
  first, written nowhere until it is taken (karamazov-xn0h.8)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [mycelium.cell :as cell]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.repl :as repl-tool]
            [samizdat.store.db :as db]
            [samizdat.userspace :as us]))

(defmacro ^:private with-project [[root] & body]
  `(let [~root (str (java.nio.file.Files/createTempDirectory
                     "samizdat-propose" (make-array java.nio.file.attribute.FileAttribute 0)))
         conn# (db/open! ":memory:")
         prev# (us/bind-root! ~root)]
     (us/bind! conn#)
     (try (us/seed-project! nil) ~@body
          (finally (us/unbind!) (us/bind-root! prev#) (db/close conn#)))))

(defn- cell-src [opts body]
  (str "(ns cells.lex (:require [mycelium.cell :as cell] [samizdat.callable :as callable]))\n"
       "(cell/defcell :lex/line-count " opts "\n  " body ")\n"))

(def ^:private good
  (cell-src "{:doc \"How many lines a file has.\" :callable true :effects [:fs] :requires []
              :input [:map [:path :string]]
              :examples [{:in {:path \"a.clj\"} :out {:lines 2}}]}"
            "(fn [_ {:keys [path]}] {:lines (count (clojure.string/split-lines (callable/tool \"read_file\" {:path path})))})"))

(defn- propose [args]
  (with-redefs [repl-tool/tool-runner (fn [_] (fn [_ _] "one\ntwo"))]
    (tools/run-tool {:tool-name "propose_cell" :role :implementor
                     :branch (state/new-branch {:id "B3" :problem "p"})
                     :args args})))

(defn- offered [] (some #(when (= :proposed (:offer %)) %) (us/offers)))

(deftest a-checked-proposal-waits-for-the-supervisor
  (with-project [root]
    (let [snap (cell/registry-snapshot)
          r (propose {:name "lex" :body good :rationale "line counts recur"})]
      (is (= :neutral (:category r)) (:result r))
      (is (str/includes? (:result r) "supervisor"))
      (is (= "B3" (:by (offered))))
      (is (not (.exists (io/file root ".samizdat/cells/lex.clj"))))
      (is (= (cell/registry-snapshot) snap) "checking it registered nothing"))))

(deftest what-is-refused-is-not-offered
  (with-project [root]
    (testing "a rationale is required: it is what the supervisor reviews"
      (is (= :mechanics (:category (propose {:name "lex" :body good}))))
      (is (nil? (offered))))
    (testing "only callable cells: a branch does not propose workflow steps"
      (let [r (propose {:name "lex" :rationale "x"
                        :body (str/replace good ":callable true " "")})]
        (is (= :mechanics (:category r)))
        (is (str/includes? (:result r) "callable"))
        (is (nil? (offered)))))
    (testing "a mark its body does not earn"
      (let [r (propose {:name "lex" :rationale "x"
                        :body (str/replace good ":effects [:fs]" ":pure true")})]
        (is (= :mechanics (:category r)))
        (is (nil? (offered)))))
    (testing "an example that does not hold"
      (let [r (propose {:name "lex" :rationale "x"
                        :body (str/replace good ":out {:lines 2}" ":out {:lines 3}")})]
        (is (= :mechanics (:category r)))
        (is (str/includes? (:result r) "example"))
        (is (nil? (offered)))))))
