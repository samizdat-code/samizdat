;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource-workspace-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]))

(defn- temp-root []
  (let [root (str "/tmp/samizdat-resource-workspace-" (random-uuid))]
    (fs/create-dirs root)
    root))

(defn- registry [opts]
  (resource/registry {:workspace (workspace/workspace-resolver opts)}))

(deftest content-revisions-are-stable-and-content-sensitive
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (workspace/content-revision "abc")))
  (is (not= (workspace/content-revision "abc")
            (workspace/content-revision "abd"))))

(deftest workspace-observations-and-reads-are-confined-and-bounded
  (let [root (temp-root)]
    (try
      (spit (str root "/hello.txt") "abcdef")
      (let [r (registry {:read slurp})
            observation (resource/observe r "workspace:hello.txt" {:root root})]
        (is (= "abcdef" (:content observation)))
        (is (= (workspace/content-revision "abcdef") (:revision observation)))
        (is (= "hello.txt" (:identity observation)))
        (is (= {:content "bcd" :next-offset 4}
               (resource/read-window r observation {:root root} {:offset 1 :limit 3})))
        (is (= {:content "ef" :next-offset nil}
               (resource/read-window r observation {:root root} {:offset 4 :limit 3}))))
      (finally (fs/delete-tree root)))))

(deftest missing-resources-do-not-fabricate-an-empty-file
  (let [root (temp-root)
        r (registry {:read slurp})]
    (try
      (let [observation (resource/observe r "workspace:missing.txt" {:root root})]
        (is (nil? (:content observation)))
        (is (:missing? (:metadata observation)))
        (is (= :missing-resource
               (try (resource/read-window r observation {:root root} {:offset 0 :limit 10}) nil
                    (catch clojure.lang.ExceptionInfo e
                      (:resource/error (ex-data e)))))))
      (finally (fs/delete-tree root)))))

(deftest declared-reference-roots-are-readable-but-not-writable
  (let [root (temp-root)
        reference (temp-root)]
    (try
      (spit (str reference "/guide.txt") "reference")
      (let [resolver (workspace/workspace-resolver {:read slurp :refs [reference]})
            r (resource/registry {:workspace resolver})
            observation (resource/observe r "workspace:guide.txt" {:root root})]
        (is (= "reference" (:content observation)))
        (is (= :outside-root
               (try
                 (workspace/stage-workspace resolver {:root root} [observation]
                                            {"workspace:guide.txt" "changed"})
                 nil
                 (catch clojure.lang.ExceptionInfo e
                   (:resource/error (ex-data e)))))))
      (finally (fs/delete-tree root) (fs/delete-tree reference)))))

(deftest escaping-workspace-uris-fail-before-file-reads
  (let [root (temp-root)
        reads (atom 0)
        r (registry {:read (fn [path] (swap! reads inc) (slurp path))})]
    (try
      (doseq [uri ["workspace:../outside.txt" "workspace:/etc/hosts"]]
        (is (= :outside-root
               (try (resource/observe r uri {:root root}) nil
                    (catch clojure.lang.ExceptionInfo e
                      (:resource/error (ex-data e)))))))
      (is (zero? @reads))
      (finally (fs/delete-tree root)))))

(deftest staging-retains-original-and-vetted-replacement-snapshots
  (let [root (temp-root)]
    (try
      (spit (str root "/core.clj") "(ns core)\n")
      (let [resolver (workspace/workspace-resolver {:read slurp})
            r (resource/registry {:workspace resolver})
            observation (resource/observe r "workspace:core.clj" {:root root})
            [member] (workspace/stage-workspace
                      resolver {:root root} [observation]
                      {"workspace:core.clj" "(defn f [] 1"})]
        (is (= "(ns core)\n" (:original member)))
        (is (= "(defn f [] 1)" (:replacement member)))
        (is (= (:revision observation) (:base-revision member)))
        (is (= (workspace/content-revision "(defn f [] 1)")
               (:replacement-revision member))))
      (finally (fs/delete-tree root)))))
