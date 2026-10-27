(ns samizdat.lsp-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [samizdat.agent.tools.base :as base]
             [samizdat.agent.tools]
             [samizdat.agent.tools.lsp]
             ;; the java.time.* host shim, before data.json — see samizdat.store.journal
             [jolt.time]
             [clojure.data.json :as json]
             [samizdat.lsp.client :as client]
             [samizdat.security.secrets :as secrets]
             [samizdat.resource :as resource]
             [samizdat.store.db :as db]
             [samizdat.store.runs :as runs]))

;; clojure-lsp is not installed on CI; every lsp-touching assert is gated so the
;; suite is trivially green there and meaningful on a machine that has it.

(defn- sample-dir [] (io/file "/tmp" "samizdat-lsp-test"))

(defn- write-sample! [dir]
  (.mkdirs dir)
  (let [f (io/file dir "sample.clj")]
    (spit f "(ns sample)\n\n(defn greet [x]\n  (str \"hi \" x))\n\n(greet \"world\")\n\nundefined-thing\n")
    f))

(deftest lsp-tool-test
  (if-not (client/available?)
    (is true "clojure-lsp absent; skipped")
    (let [root (sample-dir)
          f    (write-sample! root)]
      (try
        (let [c (client/client-for root)]
          (is (some? c) "client-for returns a client")
          ;; definition of a used symbol points back at its defn line
          (let [loc (client/definition c (str f) 5 1)]
            (is (some? loc))
            (is (= 2 (get-in loc [:range :start :line])) "defn starts on 0-based line 2"))
          ;; unresolved symbol shows up in diagnostics
          (is (seq (client/diagnostics c (str f))) "unresolved symbol is diagnosed")
          ;; the tool renders path:line:col
          (let [r (base/run-tool {:tool-name "lsp"
                                  :branch {:id "B1"}
                                  :root   (str root)
                                  :args   {:op "definition" :file "sample.clj" :line 5 :col 1}})]
            (is (= :neutral (:category r)))
            (is (re-matches #".*sample\.clj:2:6" (:result r)))))
        (finally
          (client/shutdown! root))))))

(defn- write-frame!
  "Test-side framer: hand-written response frames onto the client's stream."
  [^java.io.PipedOutputStream out msg]
  (let [body (.getBytes (json/write-str msg) "UTF-8")
        header (.getBytes (str "Content-Length: " (alength body) "\r\n\r\n") "UTF-8")]
    (.write out header) (.write out body) (.flush out)))

(deftest concurrent-requests-correlate-their-own-responses
  ;; provenance CR1-4: every caller read the shared stream itself, so
  ;; two concurrent requests could steal each other's frames — the response
  ;; landed by whichever reader got there first was dropped by the other.
  ;; One dedicated reader thread routes frames by id instead.
  (let [resp-writer (java.io.PipedOutputStream.)
        client {:in (java.io.BufferedInputStream.
                     (java.io.PipedInputStream. resp-writer))
                :out (java.io.ByteArrayOutputStream.)
                :next-id (atom 0)
                :opened (atom #{})
                :diagnostics (atom {})
                :pending (atom {})}]
    (#'client/start-reader! client)
    (let [f1 (future (#'client/request! client "textDocument/hover" {}))]
      ;; Pin the ids: whichever request is pending first holds id 1, so the
      ;; out-of-order responses below prove correlation, not scheduling luck.
      (while (nil? (get @(:pending client) 1)) (Thread/sleep 5))
      (let [f2 (future (#'client/request! client "textDocument/definition" {}))]
        (while (nil? (get @(:pending client) 2)) (Thread/sleep 5))
        ;; answered out of order on purpose: the definition (id 2) first
        (write-frame! resp-writer {:jsonrpc "2.0" :id 2 :result "def"})
        (write-frame! resp-writer {:jsonrpc "2.0" :id 1 :result "hover"})
        (is (= "def" (deref f2 5000 ::timeout)))
        (is (= "hover" (deref f1 5000 ::timeout)))))))

(deftest client-for-starts-one-client-per-root-under-race
  ;; provenance R2-9: client-for was get-then-start, so two branches sharing a
  ;; machine could both miss and both start! — the loser leaked a clojure-lsp
  ;; process and the registry only remembered one of them.
  (let [started (atom 0)
        latch (promise)
        slow-start (fn [_root]
                     (swap! started inc)
                     @latch
                     {:fake (deref started)})
        root (io/file "/tmp" "samizdat-lsp-race")]
    (with-redefs [client/available? (constantly true)
                  client/start! slow-start]
      (let [f1 (future (client/client-for root))
            f2 (future (client/client-for root))]
        (Thread/sleep 100)                       ;; both are inside start!
        (deliver latch :go)
        (let [c1 (deref f1 5000 ::timeout)
              c2 (deref f2 5000 ::timeout)]
          (is (= 1 @started) "the registry starts one client per root")
          (is (identical? c1 c2) "both racers get the same client"))
        (swap! @#'client/clients dissoc root)))))

(defn- fake-diag-client
  [f resp-writer]
  {:in (java.io.BufferedInputStream. (java.io.PipedInputStream. resp-writer))
   :out (java.io.ByteArrayOutputStream.)
   :root "/tmp/samizdat-lsp-diag-root"
   :next-id (atom 0)
   :opened (atom #{(str f)})
   :diagnostics (atom {})
   :diag-n (atom 0)
   :pending (atom {})})

(deftest a-late-diagnostics-caller-does-not-erase-a-waiting-caller-s-push
  ;; provenance R2-10: diagnostics dissoc'd the uri at entry, so a second caller
  ;; on the same file erased the push the first caller was still waiting for
  ;; — the first caller timed out and [] read as "clean". The store is
  ;; version-keyed per uri instead, and a timeout is an error, not silence.
  (let [f (doto (java.io.File. "/tmp" "samizdat-diag.clj") (spit "(ns d)\n"))
        resp-writer (java.io.PipedOutputStream.)
        c (fake-diag-client f resp-writer)
        out (:out c)
        u (str "file://" (.getAbsolutePath f))
        push! (fn [diags]
                (write-frame! resp-writer
                              {:jsonrpc "2.0"
                               :method "textDocument/publishDiagnostics"
                               :params {:uri u :diagnostics diags}}))]
    (#'client/start-reader! c)
    ;; A enters: takes its snapshot, sends didChange (a frame on :out), polls
    (let [n0 (.size out)
          a (future (client/diagnostics c (str f)))]
      (while (= n0 (.size out)) (Thread/sleep 5))
      ;; A's push lands while only A is waiting
      (push! [{:a 1}])
      (Thread/sleep 100)                       ;; inside A's 250ms poll window
      ;; B enters on the same uri: under the old code its entry dissoc
      ;; erased A's arrived push and A starved into a [] false-clean
      (let [n1 (.size out)
            b (future (client/diagnostics c (str f)))]
        (while (= n1 (.size out)) (Thread/sleep 5))
        (is (= [{:a 1}] (deref a 8000 ::timeout))
            "A gets the push that landed in its window, not B's erasure")
        (push! [{:b 2}])
        (is (= [{:b 2}] (deref b 8000 ::timeout)) "B gets its own later push")
        ;; a caller whose push never arrives is an error, not "clean"
        (is (thrown? Exception (client/diagnostics c (str f)))))
      (swap! @#'client/clients dissoc (:root c)))))

(deftest a-dead-reader-releases-waiters-and-evicts-the-client
  ;; provenance R2-17: the reader's routing ran outside any guard, so a throw
  ;; there killed the loop without releasing parked requests — and even a
  ;; clean EOF left the corpse in the registry, so every later call reused a
  ;; dead client and waited out the full 20s timeout.
  (let [f (doto (java.io.File. "/tmp" "samizdat-evict.clj") (spit "(ns e)\n"))
        resp-writer (java.io.PipedOutputStream.)
        c (fake-diag-client f resp-writer)
        reg (deref (var client/clients))]
    (#'client/start-reader! c)
    (swap! reg assoc (:root c) c)
    (let [req (future (#'client/request! c "textDocument/hover" {}))]
      (while (nil? (get @(:pending c) 1)) (Thread/sleep 5))
      (.close resp-writer)
      (is (thrown? Exception (deref req 5000 ::timeout))
          "the parked request fails fast with server-closed, not a 20s hang")
      (Thread/sleep 200)
      (is (nil? (get @reg (:root c)))
          "the dead client is evicted so the next call starts a fresh one"))))

(deftest shutdown-all-empties-the-registry
  ;; review4: shutdown! existed for single roots but nothing called it —
  ;; system/stop! now drains every lazily-spawned client through this.
  (let [reg (deref (var client/clients))]
    (swap! reg assoc "/tmp/r1" {:opened (atom #{}) :proc {:proc nil}}
                       "/tmp/r2" {:opened (atom #{}) :proc {:proc nil}})
    (client/shutdown-all!)
    (is (empty? @(deref (var client/clients)))
        "every entry is gone, and the junk fake clients did not throw")))

(deftest the-lsp-server-spawns-with-a-scrubbed-environment
  ;; Every other subprocess seam (shell, verify, gitdiff) passes
  ;; scrubbed-process-env; clojure-lsp inherited the whole parent environment,
  ;; and it re-spawns children for classpath resolution that inherit whatever
  ;; it got (karamazov-blt.27). RFC-003's flow graph gains the lsp node with
  ;; this — a check cannot fail against a graph that omits its subject.
  (let [{:keys [cmd opts]} (client/spawn-spec)]
    (is (= ["clojure-lsp" "listen"] cmd))
    (is (contains? opts :env) "the child environment is explicit, not inherited")
    (is (= (secrets/scrubbed-process-env) (:env opts))
        "and it is the scrubbed one — same seam as the shell tool")))

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

(deftest a-workspace-edit-becomes-whole-files
  ;; karamazov-05z0.20: LSP edits are ranges; the harness writes files.
  (is (= "(defn total [x] x)\n(total 1)"
         (client/apply-edits "(defn sum [x] x)\n(sum 1)"
                             [{:range {:start {:line 0 :character 6} :end {:line 0 :character 9}} :newText "total"}
                              {:range {:start {:line 1 :character 1} :end {:line 1 :character 4}} :newText "total"}]))
      "both edits, applied last first so the first's offsets hold")
  (is (= {"/p/a.clj" "new"}
         (client/edit-files {:documentChanges [{:textDocument {:uri "file:///p/a.clj"}
                                                :edits [{:range {:start {:line 0 :character 0}
                                                                 :end {:line 0 :character 3}}
                                                         :newText "new"}]}]}
                            (constantly "old")))))

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
                    client/client-for (constantly {:opened (atom #{})})
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
                    client/client-for (constantly {:opened (atom #{})})
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
                    client/client-for (constantly {:opened (atom #{})})
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
                    client/client-for (constantly {:opened (atom #{})})
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
                    client/client-for (constantly {:opened (atom #{})})
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
                    client/client-for (constantly {:opened (atom #{})})
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
                      client/client-for (constantly {:opened (atom #{})})
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

(deftest real-clojure-lsp-renames-across-files-and-refreshes-diagnostics
  (if-not (client/available?)
    (is true "clojure-lsp absent; skipped")
    (let [root (doto (java.io.File/createTempFile "lsp-real-rename" "") (.delete) (.mkdirs))
          a (io/file root "src/a.clj") b (io/file root "src/b.clj")
          ctx (semantic-context root)]
      (try
        (io/make-parents a)
        (spit a "(ns a)\n\n(defn sum [x] x)\n")
        (spit b "(ns b (:require [a]))\n(a/sum 1)\n")
        (let [result (base/run-tool (assoc ctx :args {:op "rename" :file "src/a.clj"
                                                      :line 2 :col 7 :new_name "total"}))]
          (is (= :success (:category result)) (pr-str result))
          (is (= "(ns a)\n\n(defn total [x] x)\n" (slurp a)))
          (is (= "(ns b (:require [a]))\n(a/total 1)\n" (slurp b)))
          (let [c (client/client-for root)]
            (is (empty? (client/diagnostics c (str b))))))
        (finally (close-semantic-context ctx)
                 (client/shutdown! root)
                 (doseq [f (reverse (file-seq root))] (.delete f)))))))
