;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.load-on-write-test
  "A Clojure file written while a project REPL is up is loaded there, and the write's own
  result says whether it loaded: the live-coding answer to memdoor's build-after-edit.
  A model never has to remember to reload to learn that a file it wrote is broken."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.loop :as aloop]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.prompt]
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

(deftest a-file-a-script-wrote-is-loaded-too
  ;; lean1's third figlet edited main.clj with spit inside eval, left it unbalanced, and
  ;; shipped a build that failed: only file-tool writes were loaded, and its own build
  ;; check piped compile.sh through tail, which hid the exit code.
  (let [d (root) evals (atom [])
        ctx {:root d :branch {:id "B1"} :config {}}]
    (try
      (spit (str d "/src/shop/core.clj") "(ns shop.core)\n(defn f [] nope)\n")
      (with-redefs [route/image-of (fn [_] :project)
                    route/eval-for (fn [_ code & _] (swap! evals conj code)
                                     {:ok false :error "Unable to resolve symbol: nope"})]
        (let [r (aloop/tree-load-reports ctx ["src/shop/core.clj" "README.md"])]
          (is (= ["(require 'shop.core :reload)"] @evals) "the Clojure file, and only it")
          (is (str/includes? r "shop.core"))
          (is (str/includes? r "Unable to resolve symbol: nope"))))
      (testing "the files a turn changed are kept on the branch for this"
        (let [b (-> (state/new-branch {:id "B1" :problem "p"})
                    (state/add-turn {:turn 1 :tool "eval"})
                    (state/note-tree-writes {"src/a.clj" [1 2]} false))]
          (is (= ["src/a.clj"] (:last-tree-changes b)))
          (is (empty? (:last-tree-changes (state/note-tree-writes b {"src/a.clj" [1 2]} false)))
              "and cleared when the next turn changed nothing")))
      (finally (fs/delete-tree d)))))

(deftest a-script-write-that-does-not-read-is-named-with-its-line
  ;; spit writes a string, so the paren check every file tool runs never saw it.
  (let [d (root) evals (atom [])
        ctx {:root d :branch {:id "B1"} :config {}}]
    (try
      (spit (str d "/src/shop/core.clj") "(ns shop.core)\n(defn f [] (+ 1 2)))\n(defn g [] 1)\n")
      (with-redefs [route/image-of (fn [_] :project)
                    route/eval-for (fn [_ code & _] (swap! evals conj code) {:ok true :value "nil"})]
        (let [r (aloop/tree-load-reports ctx ["src/shop/core.clj"])]
          (is (str/includes? r "src/shop/core.clj"))
          (is (str/includes? r "line 2") "where, as the file tools say it")
          (is (empty? @evals) "and it is not loaded, since it cannot be")))
      (finally (fs/delete-tree d)))))

(deftest scripts-are-told-to-write-through-the-tools
  ;; In eval, `spit` and `edit` are the code-mode helpers that write through
  ;; write_file and edit_file (karamazov-6p51.4); sed and echo are what is not.
  (doseq [p ["system-lean" "system-tools"]]
    (let [text (samizdat.prompt/prompt p)]
      (is (re-find #"`spit`.{0,40}`edit`" text) p)
      (is (re-find #"not[^.]{0,30}`sed`" text) p))))
