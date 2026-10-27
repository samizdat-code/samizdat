;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon-edit-tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [rewrite-clj.parser :as parser]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.security.flow :as flow]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]))

(def a-source
  "(ns sample.core)\n\n(defn alpha [x]\n  ;; Keep this exact comment.\n  (old-call   x))\n\n(defn beta [x] (old-call x))\n")

(def b-source
  "(ns sample.other)\n(defn gamma [x] (old-call x))\n")

(def a-edited
  "(ns sample.core)\n\n(defn alpha [x]\n  ;; Keep this exact comment.\n  (new-call\n  x))\n\n(defn beta [x] (old-call x))\n")

(def b-edited
  "(ns sample.other)\n(defn gamma [x] (new-call x))\n")

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon edit"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (spit (str root "/a.clj") a-source)
    (spit (str root "/b.clj") b-source)
    (fs/create-dirs (str root "/.samizdat"))
    (spit (str root "/.samizdat/config.edn") "{}")
    {:root root :conn conn :run-id run-id :branch {:id "B1" :role :implementor}}))

(defn- cleanup [{:keys [root conn run-id]}]
  (resource-tool/forget-run! run-id)
  (db/close conn)
  (fs/delete-tree root))

(defmacro with-fixture [[name] & body]
  `(let [~name (fixture)]
     (try ~@body (finally (cleanup ~name)))))

(defn- call
  ([fixture edits] (call fixture edits {}))
  ([fixture edits overrides]
   (tools/run-tool (merge fixture
                          {:tool-name "edit_clojure" :args {:edits edits}}
                          overrides))))

(defn- apply-proposal [fixture proposal-id overrides]
  (tools/run-tool (merge fixture
                         {:tool-name "resource"
                          :args {:action "apply" :proposal proposal-id}}
                         overrides)))

(defn- a-edit []
  {:file "a.clj" :within {:form "alpha"}
   :from "(old-call x)" :to "(new-call\n  x)"})

(defn- b-edit []
  {:file "b.clj" :within {:form "gamma"}
   :from "(old-call x)" :to "(new-call x)"})

(defn- read-file [fixture file]
  (slurp (str (:root fixture) "/" file)))

(deftest ordinary-edits-stage-and-apply-one-frozen-change-set
  (with-fixture [fixture]
    (let [observations (atom {})
          applications (atom 0)
          authorized resource-tool/authorized-observation
          apply! changes/apply!]
      (with-redefs [resource-tool/authorized-observation
                    (fn [ctx uri]
                      (swap! observations update uri (fnil inc 0))
                      (authorized ctx uri))
                    changes/apply!
                    (fn [registry ctx proposal]
                      (swap! applications inc)
                      (apply! registry ctx proposal))]
        (let [result (call fixture [(a-edit) (assoc (b-edit) :file "./b.clj")])
              members (get-in result [:resource :members])]
          (is (= :success (:category result)) (pr-str result))
          (is (= :committed (get-in result [:resource :status])))
          (is (= 1 @applications))
          (is (= {"workspace:a.clj" 1 "workspace:b.clj" 1} @observations))
          (is (= a-edited (read-file fixture "a.clj")))
          (is (= b-edited (read-file fixture "b.clj")))
          (is (= [(workspace/content-revision a-edited)
                  (workspace/content-revision b-edited)]
                 (mapv :replacement-revision members)))
          (is (= ["(old-call   x)" "(old-call x)"]
                 (mapv :before (get-in result [:resource :edits]))))
          (is (= "committed"
                 (:status (store/change-by-id (:conn fixture)
                                              (get-in result [:resource :id]))))))))))

(deftest preview-is-write-free-exact-owner-and-single-use
  (with-fixture [fixture]
    (let [preview (call fixture [(a-edit)] {:args {:edits [(a-edit)] :preview true}})
          proposal-id (get-in preview [:resource :proposal-id])]
      (is (= :neutral (:category preview)) (pr-str preview))
      (is (some? proposal-id))
      (is (= a-source (read-file fixture "a.clj")))
      (testing "another branch cannot consume it"
        (let [other (apply-proposal fixture proposal-id
                                    {:branch {:id "B2" :role :implementor}})]
          (is (= :mechanics (:category other)))
          (is (= a-source (read-file fixture "a.clj")))))
      (testing "the exact owner can apply it once"
        (let [applied (apply-proposal fixture proposal-id {})]
          (is (= :success (:category applied)) (pr-str applied))
          (is (= :committed (get-in applied [:resource :status])))
          (is (= a-edited (read-file fixture "a.clj")))))
      (let [second-apply (apply-proposal fixture proposal-id {})]
        (is (= :mechanics (:category second-apply)))
        (is (str/includes? (:result second-apply) "proposal-consumed"))
        (is (= a-edited (read-file fixture "a.clj")))))))

(deftest stale-preview-never-overwrites-an-intervening-edit
  (with-fixture [fixture]
    (let [preview (call fixture [(a-edit)] {:args {:edits [(a-edit)] :preview true}})
          proposal-id (get-in preview [:resource :proposal-id])]
      (spit (str (:root fixture) "/a.clj") "(ns sample.core)\n(def external true)\n")
      (let [applied (apply-proposal fixture proposal-id {})]
        (is (= :mechanics (:category applied)))
        (is (= :stale (get-in applied [:resource :status])))
        (is (= "(ns sample.core)\n(def external true)\n"
               (read-file fixture "a.clj")))))))

(deftest role-denial-protected-paths-and-invalid-futures-write-nothing
  (with-fixture [fixture]
    (testing "write_file authority is required"
      (let [denied (call fixture [(a-edit)] {:branch {:id "B1" :role :reviewer}})]
        (is (= :mechanics (:category denied)))
        (is (:policy-refusal? denied))
        (is (= a-source (read-file fixture "a.clj")))))
    (testing "resource ownership still protects operator configuration"
      (let [protected (call fixture [{:file ".samizdat/config.edn"
                                      :from "{}" :to "{:run {}}"}])]
        (is (= :mechanics (:category protected)))
        (is (= "{}" (read-file fixture ".samizdat/config.edn")))))
    (testing "a future that does not parse is refused before staging"
      (let [original-parser parser/parse-string-all]
        (with-redefs [parser/parse-string-all
                      (fn [candidate]
                        (if (= a-edited candidate)
                          (throw (ex-info "injected malformed future" {}))
                          (original-parser candidate)))]
          (let [invalid (call fixture [(a-edit)])]
            (is (= :mechanics (:category invalid)) (pr-str invalid))
            (is (str/includes? (:result invalid) "invalid-future-source"))
            (is (= a-source (read-file fixture "a.clj")))
            (is (empty? (store/unfinished-changes
                         (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"})))))))))

(defn- failing-registry [fixture publish]
  (resource/registry
   {:workspace (workspace/workspace-resolver {:publish publish})}))

(deftest a-publication-failure-compensates-every-file-it-installed
  (with-fixture [fixture]
    (let [publish (fn [path content revision]
                    (if (str/ends-with? (str path) "b.clj")
                      (throw (java.io.IOException. "injected publication failure"))
                      (do (spit path content) revision)))
          registry (failing-registry fixture publish)]
      (with-redefs [resource-tool/registry-for (constantly registry)]
        (let [result (call fixture [(a-edit) (b-edit)])]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (= :compensated (get-in result [:resource :status])))
          (is (= a-source (read-file fixture "a.clj")))
          (is (= b-source (read-file fixture "b.clj"))))))))

(deftest compensation-conflict-truthfully-requires-recovery
  (with-fixture [fixture]
    (let [root (:root fixture)
          publish (fn [path content revision]
                    (cond
                      (str/ends-with? (str path) "b.clj")
                      (do (spit (str root "/a.clj") "(ns third.party)\n")
                          (throw (java.io.IOException. "injected publication failure")))
                      :else (do (spit path content) revision)))
          registry (failing-registry fixture publish)]
      (with-redefs [resource-tool/registry-for (constantly registry)]
        (let [result (call fixture [(a-edit) (b-edit)])]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (= :recovery-required (get-in result [:resource :status])))
          (is (= "(ns third.party)\n" (read-file fixture "a.clj")))
          (is (= b-source (read-file fixture "b.clj"))))))))

(deftest committed-edits-label-each-published-file
  (with-fixture [fixture]
    (let [writes (atom [])]
      (with-redefs [flow/wrote! (fn [_ path whole?]
                                 (swap! writes conj {:path path :whole? whole?}))]
        (let [result (call fixture [(a-edit) (b-edit)])]
          (is (= :success (:category result)) (pr-str result))))
      (is (= 2 (count @writes)))
      (is (= [true true] (mapv #(.endsWith (str (:path %)) ".clj") @writes)))
      (is (= [false false] (mapv :whole? @writes))))))
