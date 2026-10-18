;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp-test
  "MCP: a stdio client against a fake server, the `mcp` tool's policies,
  and which servers a project may name (karamazov-05z0.16)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools]
            [samizdat.approval :as approval]
            [samizdat.config :as config]
            [samizdat.layers :as layers]
            [samizdat.mcp.client :as mcp]))

(def ^:private fake
  {:command ["sh" (.getAbsolutePath (io/file "test/fixtures/mcp/fake-server.sh"))]
   :timeout-ms 10000})

(use-fixtures :each (fn [f] (try (f) (finally (mcp/shutdown-all!)))))

(deftest the-client-lists-and-calls-a-server-s-tools
  (let [ts (mcp/tools "fake" fake)]
    (is (= ["lookup" "boom"] (mapv :name ts)))
    (is (= ["word"] (get-in (first ts) [:inputSchema :required]))))
  (is (= {:error? false :text "a lookup result\nsecond part"}
         (mcp/call "fake" fake "lookup" {:word "x"})))
  (is (:error? (mcp/call "fake" fake "boom" {})) "the server's own failure is data")
  (is (contains? (mcp/running) "fake") "one process, kept")
  (testing "a server that is not there is an error, not a hang"
    (is (thrown? Throwable (mcp/tools "nope" {:command ["sh" "-c" "exit 0"] :timeout-ms 2000})))))

(defn- run [servers args]
  (with-redefs [config/mcp-servers (constantly servers)]
    (base/run-tool {:tool-name "mcp" :branch {:id "B1"} :run-id "r" :args args})))

(deftest the-tool-follows-each-server-s-policy
  (testing ":allow calls"
    (let [r (run {:servers {:fake (assoc fake :policy :allow)}}
                 {"action" "call" "server" "fake" "tool" "lookup" "arguments" {"word" "x"}})]
      (is (str/includes? (:result r) "a lookup result"))))
  (testing ":read-only calls only the trusted tools"
    (let [s {:servers {:fake (assoc fake :policy :read-only :trusted-tools ["lookup"])}}]
      (is (str/includes? (:result (run s {"action" "call" "server" "fake" "tool" "lookup"})) "a lookup result"))
      (is (str/includes? (:result (run s {"action" "call" "server" "fake" "tool" "boom"})) "Refused"))))
  (testing ":deny calls nothing"
    (is (str/includes? (:result (run {:servers {:fake (assoc fake :policy :deny)}}
                                     {"action" "call" "server" "fake" "tool" "lookup"}))
                       "Refused")))
  (testing ":prompt with nobody to ask does not run"
    (with-redefs [approval/policy (constantly {:mode :refuse})]
      (is (str/includes? (:result (run {:servers {:fake fake}}
                                       {"action" "call" "server" "fake" "tool" "lookup"}))
                         "not approved"))))
  (testing "tools lists what the server offers"
    (let [r (run {:servers {:fake fake}} {"action" "tools" "server" "fake"})]
      (is (str/includes? (:result r) "lookup: Look a word up."))))
  (testing "a project server nobody trusted is refused, and says why"
    (let [r (run {:servers {} :untrusted [:evil]} {"action" "call" "server" "evil" "tool" "x"})]
      (is (str/includes? (:result r) "trusted-roots")))))

(deftest a-project-names-servers-only-where-the-person-trusted-it
  (let [tmp (doto (java.io.File/createTempFile "mcp-cfg" "") (.delete) (.mkdirs))
        home (io/file tmp "home") root (io/file tmp "proj")
        write (fn [f m] (io/make-parents f) (spit f (pr-str m)))]
    (try
      (write (io/file home "config.edn") {:mcp {:servers {:mine {:command ["x"]}}}})
      (write (io/file root ".samizdat/config.edn") {:mcp {:servers {:theirs {:command ["y"]}}}})
      (layers/forget-files!)
      (with-redefs [layers/global-dir (constantly (str home))]
        (let [m (config/mcp-servers (str root))]
          (is (= #{:mine} (set (keys (:servers m)))) "the person's server only")
          (is (= [:theirs] (:untrusted m))))
        (write (io/file home "config.edn") {:mcp {:servers {:mine {:command ["x"]}}
                                                  :trusted-roots [(str root)]}})
        (layers/forget-files!)
        (is (= #{:mine :theirs} (set (keys (:servers (config/mcp-servers (str root))))))
            "once trusted, the project's servers count too"))
      (finally
        (layers/forget-files!)
        (doseq [f (reverse (file-seq tmp))] (.delete f))))))
