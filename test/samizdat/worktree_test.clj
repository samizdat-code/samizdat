;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.worktree-test
  "Worktrees for parallel owners (karamazov-05z0.10), against a real repo."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.worktree :as wt]
            [samizdat.engine.proc :as proc]))

(defn- sh [dir & args] (apply proc/run {:timeout-ms 30000 :dir (str dir)} args))

(defn- repo []
  (let [d (doto (java.io.File/createTempFile "samizdat-wt-repo" "") (.delete) (.mkdirs))]
    (sh d "git" "init" "-q")
    (sh d "git" "config" "user.email" "t@example.com")
    (sh d "git" "config" "user.name" "t")
    (spit (io/file d "a.txt") "one\ntwo\nthree\n")
    (spit (io/file d "b.txt") "bee\n")
    (sh d "git" "add" "-A")
    (sh d "git" "commit" "-q" "-m" "init")
    d))

(defn- rm-rf [f] (doseq [x (reverse (file-seq (io/file f)))] (.delete x)))

(deftest owners-work-apart-and-their-patches-come-back-in-order
  (if-not (proc/available? "git")
    (is true "no git here")
    (let [root (repo)
          base (gitdiff/baseline root)
          d1 (wt/dir-for "run/1" "W0")
          d2 (wt/dir-for "run/1" "W1")]
      (try
        (is (= d1 (wt/create! root base d1)))
        (is (= d2 (wt/create! root base d2)))
        ;; W0 edits a.txt and adds a file; W1 edits b.txt.
        (spit (io/file d1 "a.txt") "one\nTWO\nthree\n")
        (spit (io/file d1 "new.txt") "fresh\n")
        (spit (io/file d2 "b.txt") "BEE\n")
        (is (= "one\ntwo\nthree\n" (slurp (io/file root "a.txt"))) "the main tree is untouched")
        (let [p1 (wt/patch d1 base) p2 (wt/patch d2 base)]
          (is (str/includes? p1 "new.txt") "an untracked file is part of the change")
          (is (= ["a.txt" "new.txt"] (:applied (wt/integrate! root p1))))
          (is (= ["b.txt"] (:applied (wt/integrate! root p2))))
          (is (= "one\nTWO\nthree\n" (slurp (io/file root "a.txt"))))
          (is (= "fresh\n" (slurp (io/file root "new.txt"))))
          (is (= "BEE\n" (slurp (io/file root "b.txt")))))
        (testing "two owners editing the same line: the second is a conflict and changes nothing"
          (let [d3 (wt/dir-for "run/1" "W2")
                _ (wt/create! root base d3)
                _ (spit (io/file d3 "a.txt") "one\nzwei\nthree\n")
                r (wt/integrate! root (wt/patch d3 base))]
            (is (= ["a.txt"] (:conflict r)))
            (is (= "one\nTWO\nthree\n" (slurp (io/file root "a.txt"))) "left as it was")
            (wt/remove! root d3)))
        (testing "an owner that changed nothing has an empty patch"
          (is (= "" (str (wt/patch d2 (str/trim (:out (sh d2 "git" "write-tree")))))))
          (is (= {:applied []} (wt/integrate! root ""))))
        (finally
          (wt/remove! root d1)
          (wt/remove! root d2)
          (rm-rf root))))))

(deftest an-isolated-owner-lands-only-what-shipped
  (if-not (proc/available? "git")
    (is true "no git here")
    (let [root (repo)]
      (try
        (testing "a shipped owner's change lands in the main tree; its checkout is gone"
          (let [seen (atom nil)
                r (wt/isolated root "run-2" "W0"
                               (fn [dir] (reset! seen dir)
                                 (spit (io/file dir "a.txt") "one\nTWO\nthree\n")
                                 {:status :done})
                               #(= :done (:status %)))]
            (is (not= (str root) @seen) "it worked in its own checkout")
            (is (= ["a.txt"] (get-in r [:worktree :applied])))
            (is (= "one\nTWO\nthree\n" (slurp (io/file root "a.txt"))))
            (is (not (.exists (io/file @seen))) "and the checkout was removed")))
        (testing "an owner that did not ship lands nothing"
          (let [r (wt/isolated root "run-2" "W1"
                               (fn [dir] (spit (io/file dir "b.txt") "half done\n") {:status :exhausted})
                               #(= :done (:status %)))]
            (is (get-in r [:worktree :discarded]))
            (is (= "bee\n" (slurp (io/file root "b.txt"))))))
        (testing "a later owner starts from the tree as the earlier one left it"
          (let [r (wt/isolated root "run-2" "W2"
                               (fn [dir] {:status :done :saw (slurp (io/file dir "a.txt"))})
                               (constantly false))]
            (is (= "one\nTWO\nthree\n" (:saw r)))))
        (testing "no repo: the owner runs in place, told so by a nil dir"
          (let [plain (doto (java.io.File/createTempFile "plain" "") (.delete) (.mkdirs))]
            (try (is (= {:dir nil} (wt/isolated plain "r" "W" (fn [d] {:dir d}) (constantly true))))
                 (finally (rm-rf plain)))))
        (finally (rm-rf root))))))
