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

(ns samizdat.confine-test
  "Which confinement the shell tool runs under (karamazov-3vu1.5): the paths,
  the ports, and the files the confinement itself is made of."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.config :as config]
            [samizdat.engine.proc :as proc]
            [samizdat.security.confine :as confine]
            [samizdat.security.listen :as listen]
            [samizdat.security.sandbox :as sandbox]))

(deftest the-secret-regions-cover-the-operators-credentials
  (let [rs (set (confine/secret-regions "/home/dev" "/work/samizdat"))]
    (doseq [p ["/home/dev/.ssh" "/home/dev/.aws" "/home/dev/.gnupg"
               "/home/dev/.config/gh" "/home/dev/.claude" "/home/dev/.samizdat-secrets"
               ;; The machine config holds provider keys. It was readable from
               ;; the eval image and from the shell.
               "/home/dev/.config/samizdat"
               "/work/samizdat"]]
      (is (contains? rs p) p))
    (is (not (contains? rs "/etc"))
        "the shell resolves names and verifies TLS through /etc")))

(deftest the-harness-ports-are-what-the-servers-registered
  (listen/reset!)
  (try
    (listen/register! :http 3985)
    (listen/register! :nrepl 7888)
    (listen/register! :http 4000)
    (is (= #{4000 7888} (set (listen/ports))) "a restarted server replaces its entry")
    (finally (listen/reset!))))

(deftest the-shell-spec
  (let [spec (confine/shell-spec {:home "/home/dev" :root "/work/proj"
                                  :harness-root "/work/samizdat"
                                  :scratch "/tmp/shell-1"
                                  :ports [3985 7888]
                                  :settings {:writable ["/opt/cache"]}})]
    (is (= "/work/proj" (:project-root spec)))
    (is (some #{"/tmp/shell-1"} (:scratch-paths spec)))
    (testing "the caches a build writes, and the operator's additions"
      (is (some #{"/home/dev/.cargo"} (:writable spec)))
      (is (some #{"/opt/cache"} (:writable spec))))
    (testing "not the caches the harness itself loads code from (karamazov-1gbh)"
      (doseq [h ["/home/dev/.jolt" "/home/dev/.m2" "/home/dev/.gitlibs"
                 ;; user deps.edn: every jolt start merges it, the harness's too
                 "/home/dev/.clojure"]]
        (is (not-any? #(or (= h %) (str/starts-with? h (str % "/"))) (:writable spec)) h)))
    (testing "the project's own confined caches instead, named outright: they
              need not be under a build cache (the test runner moves them)"
      (is (some #{(confine/confined-home "/home/dev" "/work/proj")} (:writable spec))))
    (testing "not $HOME itself"
      (is (not (some #{"/home/dev"} (:writable spec)))))
    (is (= [3985 7888] (:deny-ports spec)))
    (is (some #{"/home/dev/.ssh"} (:deny-read spec)))))

(deftest a-confined-process-gets-its-own-jolt-and-maven-caches
  ;; karamazov-1gbh: ~/.jolt and ~/.m2 hold the source, jars and compiled code
  ;; the harness runs unconfined; a shell that could write them could plant
  ;; code in the next harness start.
  (let [prev @@#'confine/confined-base]
    ;; The default location; the test runner moves it to a temp dir.
    (confine/set-confined-base! nil)
    (try
      (let [a (confine/confined-home "/home/dev" "/work/a")
            env (confine/confined-env "/home/dev" "/work/a")]
        (is (= a (confine/confined-home "/home/dev" "/work/a")) "stable for a project")
        (is (not= a (confine/confined-home "/home/dev" "/work/b")) "one per project")
        (is (str/starts-with? a "/home/dev/.cache/"))
        (doseq [k ["JOLT_CACHE_DIR" "JOLT_GITLIBS_DIR" "JOLT_MAVEN_REPOSITORY"
                   "JOLT_BUILD_CACHE_DIR" "JOLT_RUNTIME_CACHE_DIR"
                   ;; tools.deps' own gitlibs, which jolt also resolves from
                   "GITLIBS"]]
          (is (str/starts-with? (str (get env k)) (str a "/")) k)))
      (finally (confine/set-confined-base! prev)))))

(deftest the-shell-setting-is-read-strictly
  (is (= :auto (:sandbox (config/shell-settings {}))))
  (is (= :none (:sandbox (config/shell-settings {:shell {:sandbox :none}}))))
  (is (= :auto (:sandbox (config/shell-settings {:shell {:sandbox "none"}})))
      "a string is not the keyword, and a security setting that cannot be read is the default")
  (is (= ["/opt/c"] (:writable (config/shell-settings {:shell {:writable ["/opt/c" 7 nil]}})))))

(deftest no-sandbox-is-plain-bash
  (let [{:keys [argv env]} (confine/shell-command {:backend :none :root "/w" :command "ls"
                                                   :env {"PATH" "/bin"}})]
    (is (= ["bash" "-c" "cd '/w' && ls"] argv))
    (is (= {"PATH" "/bin"} env))))

(deftest the-confinement-files-are-out-of-the-shells-reach
  (when (str/starts-with? (str (System/getProperty "os.name")) "Mac OS X")
    (let [root (str (fs/create-temp-dir))
          {:keys [argv env spec profile]} (confine/shell-command {:backend :seatbelt :root root
                                                                  :command "ls" :env {"PATH" "/bin"}})
          writable (concat [(:project-root spec)] (:scratch-paths spec) (:writable spec))]
      (is (= ["sandbox-exec" "-f" profile] (subvec argv 0 3)))
      (is (not-any? #(str/starts-with? (sandbox/resolved profile) (str (sandbox/resolved %) "/"))
                    writable)
          "the shell could rewrite the profile its next command runs under")
      (testing "TMPDIR is the shell's own scratch, not the directory the profile is in"
        (is (some #{(get env "TMPDIR")} (:scratch-paths spec)))))))

(deftest a-sandboxed-command-runs
  (when (str/starts-with? (str (System/getProperty "os.name")) "Mac OS X")
    (let [root (str (fs/create-temp-dir))
          {:keys [argv env dir]} (confine/shell-command {:backend :seatbelt :root root
                                                         :command "echo hi > f && cat f && echo $TMPDIR > t && touch \"$(cat t)/ok\" && echo tmp-ok"
                                                         :env {"PATH" "/usr/bin:/bin"}})
          r (apply proc/run {:env env :dir dir :timeout-ms 30000} argv)]
      (is (str/includes? (:out r) "hi") (str r))
      (is (str/includes? (:out r) "tmp-ok") (str r))
      ;; The harness's checkout is a denied read region and the harness runs
      ;; from it: a bash started there could not find its own directory, and
      ;; every command of every run in a project elsewhere came back with
      ;; `shell-init: error retrieving current directory` twice on its end.
      (is (not (str/includes? (str (:err r)) "getcwd")) (str r))
      (is (= root dir) "the shell starts in the project, not where the harness was started"))))

(deftest a-sandboxed-command-builds-in-its-own-caches-and-not-the-harnesss
  (when (str/starts-with? (str (System/getProperty "os.name")) "Mac OS X")
    (let [home (str (fs/create-temp-dir))
          root (str (fs/create-temp-dir))
          _ (doseq [d [".jolt/gitlibs" ".m2/repository" ".gitlibs/libs" ".clojure"]]
              (fs/create-dirs (str home "/" d)))
          {:keys [argv env dir]} (confine/shell-command
                                  {:backend :seatbelt :root root :home home
                                   :command (str "touch \"$JOLT_GITLIBS_DIR/ok\" && echo own-ok; "
                                                 "touch " home "/.jolt/gitlibs/planted || echo jolt-denied; "
                                                 "touch " home "/.m2/repository/planted || echo m2-denied; "
                                                 "touch " home "/.gitlibs/libs/planted || echo gitlibs-denied; "
                                                 "touch " home "/.clojure/deps.edn || echo user-deps-denied")
                                   :env {"PATH" "/usr/bin:/bin" "HOME" home}})
          r (apply proc/run {:env env :dir dir :timeout-ms 30000} argv)]
      (is (str/includes? (:out r) "own-ok") (str r))
      (is (str/includes? (:out r) "jolt-denied") (str r))
      (is (str/includes? (:out r) "m2-denied") (str r))
      (is (str/includes? (:out r) "gitlibs-denied") (str r))
      (is (str/includes? (:out r) "user-deps-denied") (str r))
      (is (not (fs/exists? (str home "/.jolt/gitlibs/planted"))))
      (fs/delete-tree home))))

(deftest the-workflow-is-protected-in-both-specs
  (is (= ["/work/proj/.samizdat"] (confine/protected "/work/proj")))
  (is (= ["/work/proj/.samizdat"]
         (:protect (confine/shell-spec {:home "/h" :root "/work/proj"})))))

(deftest an-unsandboxed-shell-says-so
  ;; karamazov-3vu1.6: :auto on Linux without bubblewrap ran the shell with
  ;; no confinement and said nothing.
  (is (= :no-bwrap (confine/unconfined-reason :auto "Linux" false)))
  (is (= :configured-off (confine/unconfined-reason :none "Mac OS X" false)))
  (is (nil? (confine/unconfined-reason :auto "Mac OS X" false)))
  (is (nil? (confine/unconfined-reason :auto "Linux" true))))
