;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.argrules-test
  "Per-argument rules for every tool (karamazov-k7gl.11): the operator's
  config.edn `:tools :rules`, read before dispatch. The shell's own table
  judges commands; these judge any tool's arguments."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.tools :as tools]
            [samizdat.approval :as approval]
            [samizdat.security.argrules :as argrules]))

(def ^:private rules
  [{:tool "write_file" :args {:path "^secrets/"} :decision :deny}
   {:tool "webfetch" :args {:url "^https?://"} :decision :ask}
   {:tool "webfetch" :args {:url "^https://docs\\.example\\.com/"} :decision :allow}
   {:tool "webfetch" :args {:url "^https://internal\\."} :decision :deny}])

(deftest a-rule-matches-its-tool-and-every-argument-pattern
  (is (= :deny (:decision (argrules/decide [rules] "write_file" {:path "secrets/k.txt"}))))
  (is (nil? (argrules/decide [rules] "write_file" {:path "src/a.clj"})))
  (is (nil? (argrules/decide [rules] "read_file" {:path "secrets/k.txt"})) "another tool")
  (is (nil? (argrules/decide [rules] "write_file" {})) "an absent argument matches nothing")
  (testing "a list argument matches when any element does"
    (is (= :deny (:decision (argrules/decide [[{:tool "read_digest" :args {:paths "\\.pem$"}
                                                 :decision :deny}]]
                                               "read_digest" {:paths ["a.clj" "k.pem"]})))))
  (testing "a rule may name several tools, or all of them"
    (is (= :ask (:decision (argrules/decide [[{:tool ["grep" "read_file"] :args {:path "vendor/"}
                                                :decision :ask}]]
                                              "grep" {:path "vendor/x"}))))
    (is (= :deny (:decision (argrules/decide [[{:tool :all :args {:path "\\.env$"} :decision :deny}]]
                                               "edit_file" {:path ".env"})))))
  (testing "string-keyed arguments match too"
    (is (= :deny (:decision (argrules/decide [rules] "write_file" {"path" "secrets/k"}))))))

(deftest within-a-layer-the-last-match-wins
  (is (= :deny (:decision (argrules/decide [rules] "webfetch" {:url "https://internal.corp/x"}))))
  (is (= :ask (:decision (argrules/decide [rules] "webfetch" {:url "https://evil.test/"}))))
  (is (= :allow (:decision (argrules/decide [rules] "webfetch" {:url "https://docs.example.com/a"}))))
  (is (= :allow (:decision (argrules/decide [[{:tool "webfetch" :decision :ask}
                                              {:tool "webfetch" :args {:url "example"} :decision :allow}]]
                                            "webfetch" {:url "https://example.org"})))
      "an allow after an ask lifts it within its own layer"))

(deftest across-layers-the-most-restrictive-wins
  ;; A project file (lower trust: a checkout someone else wrote) cannot lift a
  ;; person's ask by allowing what the person asks about.
  (let [person [{:tool "webfetch" :decision :ask}]
        project [{:tool "webfetch" :decision :allow}]]
    (is (= :ask (:decision (argrules/decide [person project] "webfetch" {:url "x"}))))
    (is (= :deny (:decision (argrules/decide [person [{:tool "webfetch" :decision :deny}]]
                                             "webfetch" {:url "x"}))))))

(deftest a-malformed-rule-fails-closed
  (testing "a pattern that does not compile denies the tool it names"
    (is (= :deny (:decision (argrules/decide [[{:tool "write_file" :args {:path "(("} :decision :allow}]]
                                             "write_file" {:path "a"})))))
  (testing "an unknown decision is an ask, not an allow"
    (is (= :ask (:decision (argrules/decide [[{:tool "write_file" :decision :maybe}]]
                                            "write_file" {:path "a"}))))))

(defn- project [cfg]
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir ".samizdat"))
    (fs/spit (fs/path dir ".samizdat/config.edn") (pr-str cfg))
    (fs/spit (fs/path dir "a.clj") "(ns a)\n")
    dir))

(deftest the-dispatch-seam-applies-them
  (let [dir (project {:tools {:rules [{:tool "read_file" :args {:path "a\\.clj$"} :decision :deny}]}})
        ctx {:root dir :branch {:id "B1"} :tool-name "read_file" :args {:path "a.clj"}}
        r (tools/run-tool ctx)]
    (is (:policy-refusal? r))
    (is (= :argument-rule (:refusal-rule r)))
    (is (not (str/includes? (str (:result r)) "(ns a)")))
    (is (str/includes? (str (:result r)) "read_file"))))

(deftest an-ask-goes-through-the-approval-path
  (let [dir (project {})
        ctx {:root dir :run-id (str "argrules-" (random-uuid)) :branch {:id "B1"}
             :tool-name "read_file" :args {:path "a.clj"}
             :config {:tools {:rules [{:tool "read_file" :decision :ask}]}}}]
    (testing "with nobody to ask, an ask is refused"
      (let [r (tools/run-tool ctx)]
        (is (:policy-refusal? r))
        (is (not (str/includes? (str (:result r)) "(ns a)")))))
    (testing "under yolo it is allowed and the tool runs"
      (try
        (approval/set-mode! :yolo)
        (is (str/includes? (str (:result (tools/run-tool ctx))) "(ns a)"))
        (finally (approval/set-mode! nil))))))
