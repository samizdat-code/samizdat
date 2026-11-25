;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.security.ceiling-test
  "The tool ceiling (karamazov-k7gl.5): the most a run may use, held in the
  operator's config.edn layers. Every layer can only narrow it, the dispatch
  seam refuses a call above it whatever the workflow says, and nothing above
  it is advertised to the model."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools :as tools]
            [samizdat.security.ceiling :as ceiling]
            [samizdat.security.policy :as policy]))

(deftest no-ceiling-is-everything
  (is (ceiling/unrestricted? (ceiling/of-layers [])))
  (is (ceiling/unrestricted? (ceiling/of-layers [nil {} {:other 1}])))
  (is (ceiling/permits? (ceiling/of-layers []) "shell")))

(deftest a-layer-may-only-narrow
  (let [c (ceiling/of-layers [{:ceiling #{"read_file" "grep" "shell"}}
                              {:ceiling ["read_file" "shell" "eval"]}
                              {:deny #{"shell"}}])]
    (is (ceiling/permits? c "read_file"))
    (is (not (ceiling/permits? c "grep")) "a later layer's ceiling intersects")
    (is (not (ceiling/permits? c "eval")) "naming a tool another layer left out does not add it")
    (is (not (ceiling/permits? c "shell")) "a deny in any layer wins")))

(deftest names-are-read-however-they-are-spelled
  (let [c (ceiling/of-layers [{:ceiling [:read_file 'grep "done"]}])]
    (is (every? #(ceiling/permits? c %) ["read_file" "grep" "done"]))
    (is (not (ceiling/permits? c "shell")))))

(deftest a-malformed-ceiling-fails-closed
  (testing "a value that names no tools permits none rather than all"
    (is (not (ceiling/permits? (ceiling/of-layers [{:ceiling 42}]) "read_file")))
    (is (not (ceiling/permits? (ceiling/of-layers [{:deny 42}]) "read_file"))))
  (testing "a single string is one tool, not its characters"
    (is (ceiling/permits? (ceiling/of-layers [{:ceiling "grep"}]) "grep"))
    (is (not (ceiling/permits? (ceiling/of-layers [{:ceiling "grep"}]) "g")))))

(defn- project [cfg]
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir ".samizdat"))
    (fs/spit (fs/path dir ".samizdat/config.edn") (pr-str cfg))
    (fs/spit (fs/path dir "a.clj") "(ns a)\n")
    dir))

(deftest the-project-config-layer-sets-it
  (let [dir (project {:tools {:ceiling #{"read_file" "done"}}})]
    (is (ceiling/permits? (ceiling/for-root dir) "read_file"))
    (is (not (ceiling/permits? (ceiling/for-root dir) "grep")))
    (testing "the run's own config map can narrow it further, never widen it"
      (is (not (ceiling/permits? (ceiling/for-root dir {:ceiling #{"grep" "read_file"}}) "grep")))
      (is (not (ceiling/permits? (ceiling/for-root dir {:deny #{"read_file"}}) "read_file"))))))

(deftest the-dispatch-seam-refuses-above-the-ceiling
  (let [dir (project {})
        ctx {:root dir :branch {:id "B1"} :tool-name "read_file" :args {:path "a.clj"}
             :config {:tools {:deny #{"read_file"}}}}
        r (tools/run-tool ctx)]
    (is (= :mechanics (:category r)))
    (is (:policy-refusal? r))
    (is (= :tool-ceiling (:refusal-rule r)))
    (is (str/includes? (str (:result r)) "read_file"))
    (is (not (str/includes? (str (:result r)) "(ns a)")) "the tool never ran")
    (testing "without the ceiling the same call runs"
      (is (str/includes? (str (:result (tools/run-tool (dissoc ctx :config)))) "(ns a)")))
    (testing "the project file's ceiling is enforced with no run config at all"
      (let [dir (project {:tools {:ceiling #{"grep"}}})
            r (tools/run-tool {:root dir :branch {:id "B1"} :tool-name "read_file"
                               :args {:path "a.clj"}})]
        (is (= :tool-ceiling (:refusal-rule r)))))))

(deftest a-workflow-that-enables-a-tool-does-not-widen-the-ceiling
  ;; roles.edn is userspace: a role whose surface is :all, or one that names
  ;; the tool outright, is still capped.
  (let [c (ceiling/of-layers [{:deny #{"shell"}}])]
    (is (not (roles/may-use? :no-such-role "shell" :on c)))
    (is (roles/may-use? :no-such-role "read_file" :on c))
    (testing "and the catalogue the model reads leaves the entry out"
      (let [text (str "### Doing work\n\n"
                      "shell({command})\n    Run a command.\n\n"
                      "read_file({path})\n    Read a file.\n")
            scoped (roles/scope-catalogue text :no-such-role :on c)]
        (is (not (str/includes? scoped "shell({command})")))
        (is (not (str/includes? scoped "Run a command.")))
        (is (str/includes? scoped "read_file({path})"))))))

(deftest native-specs-above-the-ceiling-are-not-sent
  (let [c (ceiling/of-layers [{:ceiling #{"read_file"}}])
        specs [{:name "read_file"} {:name "shell"} {:name "eval"}]]
    (is (= ["read_file"] (map :name (ceiling/filter-specs c specs))))
    (is (= specs (ceiling/filter-specs (ceiling/of-layers []) specs)))))

(deftest the-operator-config-files-are-not-the-agents-to-write
  (let [dir (project {})
        canon #(str (fs/canonicalize %))]
    (testing "the project layer, as before"
      (is (files/run-config? dir (canon (str dir "/.samizdat/config.edn")))))
    (testing "the file SAMIZDAT_CONFIG_FILE names, when it lies under the root"
      (fs/spit (fs/path dir "ops.edn") "{}")
      (is (files/operator-config? dir (canon (str dir "/ops.edn"))
                                  {:getenv {"SAMIZDAT_CONFIG_FILE" (str dir "/ops.edn")}}))
      (is (not (files/operator-config? dir (canon (str dir "/a.clj"))
                                       {:getenv {"SAMIZDAT_CONFIG_FILE" (str dir "/ops.edn")}}))))
    (testing "the person's global layer, when a profile puts it under the root"
      (fs/create-dirs (fs/path dir "prof/config"))
      (fs/spit (fs/path dir "prof/config/config.edn") "{}")
      (is (files/operator-config? dir (canon (str dir "/prof/config/config.edn"))
                                  {:global-dir (str dir "/prof/config") :getenv {}}))))
  (testing "a shell statement writing the machine-wide config is a hard deny"
    (let [d (policy/decide {:grants ["sed **"]} "sed -i s/a/b/ ~/.config/samizdat/config.edn")]
      (is (= :deny (:effect d)))
      (is (= :protected-path (get-in d [:rule :name]))))
    (is (not= :deny (:effect (policy/decide {} "cat ~/.config/samizdat/config.edn")))
        "reading it stays a matter for the sandbox, not this list")))
