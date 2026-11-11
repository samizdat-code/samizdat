(ns samizdat.lsp-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
             [samizdat.agent.tools]
             [samizdat.agent.tools.lsp]
             [samizdat.lsp.index :as client]
             [samizdat.resource :as resource]
             [samizdat.store.db :as db]
             [samizdat.store.runs :as runs]))

;; The index is jolt-lsp, in this process: these run everywhere, with nothing
;; to install.

(defn- fake-diag-client
  [f resp-writer]
  {:in (java.io.BufferedInputStream. (java.io.PipedInputStream. resp-writer))
   :out (java.io.ByteArrayOutputStream.)
   :root "/tmp/samizdat-lsp-diag-root"
   :next-id (atom 0)
   :next-document-version (atom 0)
   :opened (atom #{(str f)})
   :diagnostics (atom {})
   :diag-n (atom 0)
   :pending (atom {})})

(deftest the-lsp-tool-refuses-a-path-that-escapes-the-root
  ;; read_file refuses ../..; the lsp tool resolved with a bare io/file, then
  ;; slurped the result and handed the text to clojure-lsp (karamazov-blt.28).
  (let [root (io/file "/tmp" "samizdat-lsp-escape-root")]
    (.mkdirs root)
    (with-redefs [client/available? (constantly true)]
      (doseq [path ["../outside.clj" "/etc/hosts"]]
        (let [r (base/run-tool {:tool-name "lsp"
                                :branch {:id "B1"}
                                :root (str root)
                                :args {:op "diagnostics" :file path}})]
          (is (= :mechanics (:category r))
              (str path " is refused before anything is read"))
          (is (str/includes? (str (:result r)) "root")
              "the refusal names the boundary"))))))

(deftest a-rename-publishes-every-file-as-one-resource-change
  (let [root (doto (java.io.File/createTempFile "lsp-rename" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") b (io/file root "src/b.clj")
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "rename"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (try
      (io/make-parents a)
      (spit a "(ns a)\n(defn sum [x] x)\n")
      (spit b "(ns b (:require [a]))\n(a/sum 1)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/rename (fn [_ _ _ _ new-name]
                                    {:changes {(str "file://" (.getCanonicalPath a))
                                               [{:range {:start {:line 1 :character 6} :end {:line 1 :character 9}}
                                                 :newText new-name}]
                                               (str "file://" (.getCanonicalPath b))
                                               [{:range {:start {:line 1 :character 3} :end {:line 1 :character 6}}
                                                 :newText new-name}]}})]
        (let [r (base/run-tool {:tool-name "lsp" :root (str root) :conn conn :run-id run-id
                                :branch {:id "B1" :role :implementor}
                                :args {:op "rename" :file "src/a.clj" :line 1 :col 7 :new_name "total"}})]
          (is (str/includes? (str (:result r)) "Renamed to `total`") (str (:result r)))
          (is (= "(ns a)\n(defn total [x] x)\n" (slurp a)))
          (is (= "(ns b (:require [a]))\n(a/total 1)\n" (slurp b))))
        (testing "a role that does not write cannot rename"
          (let [r (base/run-tool {:tool-name "lsp" :root (str root) :conn conn :run-id run-id
                                  :branch {:id "R1" :role :reviewer}
                                  :args {:op "rename" :file "src/a.clj" :line 1 :col 7 :new_name "x"}})]
            (is (str/includes? (str (:result r)) "does not write")))))
      (finally (db/close conn)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(defn- semantic-context [root]
  (let [conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "semantic edits"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    {:tool-name "lsp" :root (str root) :conn conn :run-id run-id
     :branch {:id "B1" :role :implementor}}))

(defn- close-semantic-context [ctx]
  (db/close (:conn ctx)))

(deftest a-stale-lsp-preview-leaves-every-file-original
  (let [root (doto (java.io.File/createTempFile "lsp-stale" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") b (io/file root "src/b.clj")
        ctx (semantic-context root)]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n(defn sum [x] x)\n")
      (spit b "(ns b (:require [a]))\n(a/sum 1)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/rename (fn [_ _ _ _ new-name]
                                    {:changes {(str "file://" (.getCanonicalPath a))
                                               [{:range {:start {:line 1 :character 6} :end {:line 1 :character 9}}
                                                 :newText new-name}]
                                               (str "file://" (.getCanonicalPath b))
                                               [{:range {:start {:line 1 :character 3} :end {:line 1 :character 6}}
                                                 :newText new-name}]}})]
        (let [staged (base/run-tool (assoc ctx :args {:op "rename" :file "src/a.clj"
                                                      :line 1 :col 7 :new_name "total" :preview true}))
              proposal (get-in staged [:resource :proposal-id])]
          (is (some? proposal) (pr-str staged))
          (spit b "(ns b (:require [a]))\n(a/sum 2)\n")
          (let [result (base/run-tool (assoc ctx :tool-name "resource"
                                             :args {:action "apply" :proposal proposal}))]
            (is (= :mechanics (:category result)))
            (is (= "(ns a)\n(defn sum [x] x)\n" (slurp a)))
            (is (= "(ns b (:require [a]))\n(a/sum 2)\n" (slurp b))))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest code-actions-publish-through-the-resource-transaction
  (let [root (doto (java.io.File/createTempFile "lsp-action" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj")
        ctx (semantic-context root)]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n(sum 1)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/code-actions (fn [_ _ _ _]
                                          [{:title "Replace sum"
                                            :edit {:changes {(str "file://" (.getCanonicalPath a))
                                                             [{:range {:start {:line 1 :character 1} :end {:line 1 :character 4}}
                                                               :newText "total"}]}}}])]
        (let [result (base/run-tool (assoc ctx :args {:op "code_action" :file "src/a.clj"
                                                      :line 1 :col 1 :end_line 1 :end_col 4}))]
          (is (= :success (:category result)) (pr-str result))
          (is (= "(ns a)\n(total 1)\n" (slurp a)))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest file-moves-are-staged-resource-changes
  (let [root (doto (java.io.File/createTempFile "lsp-move" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") b (io/file root "src/b.clj") c (io/file root "src/c.clj")
        ctx (semantic-context root)]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n")
      (spit b "(ns b (:require [a]))\n(a/sum 1)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/prepare-file-rename (fn [_ _ _]
                                                 {:changes {(str "file://" (.getCanonicalPath b))
                                                            [{:range {:start {:line 1 :character 3} :end {:line 1 :character 6}}
                                                              :newText "total"}]}})]
        (let [result (base/run-tool (assoc ctx :args {:op "move" :file "src/a.clj" :new_path "src/c.clj"}))]
          (is (= :success (:category result)) (pr-str result))
          (is (not (.exists a)))
          (is (= "(ns a)\n" (slurp c)))
          (is (= "(ns b (:require [a]))\n(a/total 1)\n" (slurp b)))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest command-only-code-actions-are-explicitly-unsupported
  (let [root (doto (java.io.File/createTempFile "lsp-command" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") ctx (semantic-context root)]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/code-actions (constantly [{:title "Run command" :command "do.it"}])]
        (let [result (base/run-tool (assoc ctx :args {:op "code_action" :file "src/a.clj"
                                                      :line 0 :col 0 :end_line 0 :end_col 0}))]
          (is (= :failure (:category result)))
          (is (= "(ns a)\n" (slurp a)))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest preview-apply-synchronizes-the-lsp-document
  (let [root (doto (java.io.File/createTempFile "lsp-preview-sync" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") ctx (semantic-context root)
        changes (atom [])]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n(sum 1)\n")
      (with-redefs [client/available? (constantly true)
                    client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                    client/rename (fn [_ _ _ _ _]
                                    {:changes {(str "file://" (.getCanonicalPath a))
                                               [{:range {:start {:line 1 :character 1} :end {:line 1 :character 4}}
                                                 :newText "total"}]}})
                    client/changed! (fn [_ path text] (swap! changes conj [path text]))]
        (let [staged (base/run-tool (assoc ctx :args {:op "rename" :file "src/a.clj"
                                                      :line 1 :col 1 :new_name "total" :preview true}))
              proposal (get-in staged [:resource :proposal-id])]
          (is (empty? @changes))
          (let [result (base/run-tool (assoc ctx :tool-name "resource"
                                             :args {:action "apply" :proposal proposal}))]
            (is (= :success (:category result)) (pr-str result))
            (is (= [[(.getCanonicalPath a) "(ns a)\n(total 1)\n"]] @changes)))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(deftest lsp-normalization-uses-one-observation-per-document
  (let [root (doto (java.io.File/createTempFile "lsp-observations" "") (.delete) (.mkdirs))
        a (io/file root "src/a.clj") ctx (semantic-context root)]
    (try
      (io/make-parents a)
      (spit a "(ns a)\n(sum 1)\n")
      (let [old-observe resource/observe
            calls (atom 0)]
        (with-redefs [client/available? (constantly true)
                      client/client-for (constantly {:opened (atom #{}) :next-document-version (atom 0)})
                      client/rename (fn [_ _ _ _ _]
                                      {:changes {(str "file://" (.getCanonicalPath a))
                                                 [{:range {:start {:line 1 :character 1} :end {:line 1 :character 4}}
                                                   :newText "total"}]}})
                      resource/observe (fn [& args]
                                         (swap! calls inc)
                                         (when (= 2 @calls)
                                           (spit a "(ns a)\n(sum 2)\n"))
                                         (apply old-observe args))]
          (let [result (base/run-tool (assoc ctx :args {:op "rename" :file "src/a.clj"
                                                        :line 1 :col 1 :new_name "total"}))]
            (is (= :mechanics (:category result)) (pr-str result))
            (is (= "(ns a)\n(sum 2)\n" (slurp a)))
            (is (= 2 @calls)))))
      (finally (close-semantic-context ctx)
               (doseq [f (reverse (file-seq root))] (.delete f))))))

(defn- project! [prefix files]
  (let [root (doto (java.io.File/createTempFile prefix "") (.delete) (.mkdirs))]
    (doseq [[path text] files]
      (let [f (io/file root path)] (io/make-parents f) (spit f text)))
    (.getCanonicalFile root)))

(deftest the-index-answers-navigation-in-lsp-coordinates
  (let [root (project! "lsp-nav" {"deps.edn" "{:paths [\"src\"]}"
                                  "src/sample.clj" "(ns sample)\n\n(defn greet [x]\n  (str \"hi \" x))\n\n(greet \"world\")\n\nundefined-thing\n"})
        f (str root "/src/sample.clj")
        c (client/client-for root)]
    (try
      (is (some? (:session-id c)))
      (let [loc (client/definition c f 5 1)]
        (is (= 2 (get-in loc [:range :start :line])) "defn greet is on 0-based line 2")
        (is (= 6 (get-in loc [:range :start :character]))))
      (is (= 2 (count (client/references c f 2 6))) "the definition and the call")
      (is (str/includes? (get-in (client/hover c f 5 1) [:contents :value]) "sample/greet"))
      (is (some #(str/includes? (:message %) "undefined-thing") (client/diagnostics c f))
          "an unresolved symbol is diagnosed")
      (let [r (base/run-tool {:tool-name "lsp" :branch {:id "B1"} :root (str root)
                              :args {:op "definition" :file "src/sample.clj" :line 5 :col 1}})]
        (is (= :neutral (:category r)))
        (is (re-matches #".*sample\.clj:2:6" (:result r))))
      (finally (doseq [x (reverse (file-seq root))] (.delete x))))))

(deftest the-index-renames-across-files
  (let [root (project! "lsp-rename-real" {"deps.edn" "{:paths [\"src\"]}"
                                          "src/a.clj" "(ns a)\n\n(defn sum [x] x)\n"
                                          "src/b.clj" "(ns b (:require [a]))\n(a/sum 1)\n"})
        ctx (semantic-context root)]
    (try
      (let [result (base/run-tool (assoc ctx :args {:op "rename" :file "src/a.clj"
                                                    :line 2 :col 6 :new_name "total"}))]
        (is (= :success (:category result)) (pr-str result))
        (is (= "(ns a)\n\n(defn total [x] x)\n" (slurp (io/file root "src/a.clj"))))
        (is (= "(ns b (:require [a]))\n(a/total 1)\n" (slurp (io/file root "src/b.clj")))))
      (finally (close-semantic-context ctx)
               (doseq [x (reverse (file-seq root))] (.delete x))))))

(deftest the-index-moves-a-file-and-renames-its-namespace
  (let [root (project! "lsp-move-real" {"deps.edn" "{:paths [\"src\"]}"
                                        "src/shop/core.clj" "(ns shop.core)\n(defn sum [x] x)\n"
                                        "src/shop/web.clj" "(ns shop.web (:require [shop.core :as core]))\n(core/sum 1)\n"})
        ctx (semantic-context root)]
    (try
      (let [result (base/run-tool (assoc ctx :args {:op "move" :file "src/shop/core.clj"
                                                    :new_path "src/shop/pricing.clj"}))]
        (is (= :success (:category result)) (pr-str result))
        (is (not (.exists (io/file root "src/shop/core.clj"))))
        (is (str/starts-with? (slurp (io/file root "src/shop/pricing.clj")) "(ns shop.pricing)"))
        (is (str/includes? (slurp (io/file root "src/shop/web.clj")) "[shop.pricing :as core]")))
      (finally (close-semantic-context ctx)
               (doseq [x (reverse (file-seq root))] (.delete x))))))

(deftest the-index-offers-its-refactors-as-code-actions
  (let [root (project! "lsp-actions" {"deps.edn" "{:paths [\"src\"]}"
                                      "src/a.clj" "(ns a (:require [clojure.string :as str]))\n(defn f [] 1)\n"})
        ctx (semantic-context root)]
    (try
      (let [actions (client/code-actions (client/client-for root) (str root "/src/a.clj")
                                         {:start {:line 1 :character 1} :end {:line 1 :character 1}} nil)]
        (is (some #(= "Clean namespace" (:title %)) actions) "an unused require can be cleaned"))
      (let [result (base/run-tool (assoc ctx :args {:op "code_action" :file "src/a.clj" :title "Clean namespace"
                                                    :line 0 :col 0 :end_line 0 :end_col 0}))]
        (is (= :success (:category result)) (pr-str result))
        (is (not (str/includes? (slurp (io/file root "src/a.clj")) "clojure.string"))))
      (finally (close-semantic-context ctx)
               (doseq [x (reverse (file-seq root))] (.delete x))))))

