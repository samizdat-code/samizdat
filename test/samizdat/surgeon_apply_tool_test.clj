;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon-apply-tool-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]
            [samizdat.resource.changes :as changes]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]))

(def a-source
  (str "(ns sample.core)\n\n"
       "(defn alpha [x] [(old-call x) :body])\n\n"
       "(defn obsolete [] :gone)\n"))

(def b-source "(ns sample.other)\n(def view {:a 1 :b 2})\n")

(def a-edited
  (str "(ns sample.core)\n\n"
       "(defn alpha [x] [(new-call x) :ready])\n\n"
       "(defn beta [] :b)\n"))

(def b-edited "(ns sample.other)\n(def view {:a 1 :b 2 :status :ready})\n")

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon apply"})]
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
  ([fixture changes expect] (call fixture changes expect {}))
  ([fixture changes expect overrides]
   (tools/run-tool (merge fixture
                          {:tool-name "apply_clojure_changes"
                           :args {:changes changes :expect expect}}
                          overrides))))

(defn- read-file [fixture file]
  (slurp (str (:root fixture) "/" file)))

(defn- transaction-changes []
  [{:id :call :in ["a.clj"] :forms ['alpha]
    :find "(old-call x)" :do [:replace "(new-call x)"] :expect {:matches 1}}
   {:id :body :in ["./a.clj"] :forms ['alpha]
    :find ":body" :do [:replace ":ready"] :expect {:matches 1}}
   {:id :insert :in ["a.clj"] :forms ['alpha]
    :do [:insert-after ["(defn beta [] :b)"]] :expect {:matches 1}}
   {:id :delete :in ["a.clj"] :forms ['obsolete]
    :do [:delete true] :expect {:matches 1}}
   {:id :entry :in ["b.clj"] :find "{:a 1 :b 2}"
    :do [:assoc-entry {:key ":status" :value ":ready"}] :expect {:matches 1}}])

(def transaction-expect {:changes 5 :edits 5 :files 2})

(deftest heterogeneous-batch-commits-one-resource-change-from-one-frozen-snapshot
  (with-fixture [fixture]
    (let [observations (atom {})
          applications (atom 0)
          proposals (atom 0)
          authorized resource-tool/authorized-observation
          apply! changes/apply!
          stage! resource-tool/stage-proposal!]
      (with-redefs [resource-tool/authorized-observation
                    (fn [ctx uri]
                      (swap! observations update uri (fnil inc 0))
                      (authorized ctx uri))
                    changes/apply!
                    (fn [registry ctx proposal]
                      (swap! applications inc)
                      (apply! registry ctx proposal))
                    resource-tool/stage-proposal!
                    (fn [ctx members]
                      (swap! proposals inc)
                      (stage! ctx members))]
        (let [result (call fixture (transaction-changes) transaction-expect)
              members (get-in result [:resource :members])]
          (is (= :success (:category result)) (pr-str result))
          (is (= :committed (get-in result [:resource :status])))
          (is (= 1 @proposals))
          (is (= 1 @applications))
          (is (= {"workspace:a.clj" 1 "workspace:b.clj" 1} @observations))
          (is (= a-edited (read-file fixture "a.clj")))
          (is (= b-edited (read-file fixture "b.clj")))
          (is (= 2 (count members)))
          (is (= 5 (count (get-in result [:resource :edits]))))
          (is (= 5 (get-in result [:resource :canonical-effects :effects])))
          (is (= "committed"
                 (:status (store/change-by-id (:conn fixture)
                                              (get-in result [:resource :id]))))))))))

(deftest preview-stages-exactly-one-proposal-and-writes-nothing
  (with-fixture [fixture]
    (let [applications (atom 0)
          apply! changes/apply!]
      (with-redefs [changes/apply! (fn [& _] (swap! applications inc))]
        (let [preview (call fixture (transaction-changes) transaction-expect
                            {:args {:changes (transaction-changes)
                                    :expect transaction-expect :preview true}})]
          (is (= :neutral (:category preview)) (pr-str preview))
          (is (some? (get-in preview [:resource :proposal-id])))
          (is (= 1 (count (store/unfinished-changes
                            (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"}))))
          (is (zero? @applications))
          (is (= a-source (read-file fixture "a.clj")))
          (is (= b-source (read-file fixture "b.clj"))))))))

(deftest refusal-and-output-limit-stage-nothing-and-write-nothing
  (with-fixture [fixture]
    (let [staged (atom 0)
          stage! resource-tool/stage-proposal!]
      (with-redefs [resource-tool/stage-proposal!
                    (fn [ctx members]
                      (swap! staged inc)
                      (stage! ctx members))]
        (let [bad (update-in (transaction-changes) [0 :expect :matches] inc)
              refused (call fixture bad transaction-expect)]
          (is (= :mechanics (:category refused)) (pr-str refused))
          (is (str/includes? (:result refused) "expect-count-mismatch"))
          (is (zero? @staged))
          (is (= a-source (read-file fixture "a.clj"))))
        (let [original gates/threshold]
          (with-redefs [gates/threshold
                        (fn [key]
                          (cond-> (original key)
                            (= :clj-surgeon key) (assoc :max-result-chars 10)))]
            (let [limited (call fixture (transaction-changes) transaction-expect)]
              (is (= :mechanics (:category limited)) (pr-str limited))
              (is (str/includes? (:result limited) "result-limit"))
              (is (zero? @staged))
              (is (= a-source (read-file fixture "a.clj")))
              (is (= b-source (read-file fixture "b.clj"))))))))))

(defn- failing-registry [publish]
  (resource/registry
   {:workspace (workspace/workspace-resolver {:publish publish})}))

(deftest injected-publication-failure-compensates-the-complete-batch
  (with-fixture [fixture]
    (let [publish (fn [path content revision]
                    (if (str/ends-with? (str path) "b.clj")
                      (throw (java.io.IOException. "injected publication failure"))
                      (do (spit path content) revision)))]
      (with-redefs [resource-tool/registry-for
                    (constantly (failing-registry publish))]
        (let [result (call fixture (transaction-changes) transaction-expect)]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (= :compensated (get-in result [:resource :status])))
          (is (= a-source (read-file fixture "a.clj")))
          (is (= b-source (read-file fixture "b.clj"))))))))

(deftest binding-rename-uses-local-identities-through-the-real-tool
  (with-fixture [fixture]
    (let [source (str "(ns a)\n"
                      "(defn feed [{:keys [sort-by]}] [sort-by :sort-by clojure.core/sort-by])\n")
          expected (str "(ns a)\n"
                        "(defn feed [{:keys [] sort-field :sort-by}] [sort-field :sort-by clojure.core/sort-by])\n")]
      (spit (str (:root fixture) "/a.clj") source)
      (let [result (call fixture
                         [{:id :rename :in ["a.clj"] :forms ['feed]
                           :do [:rename-binding {:from 'sort-by :to 'sort-field
                                                 :preserve-external-key true}]
                           :expect {:matches 2 :each-form 1}}]
                         {:changes 1 :edits 2 :files 1})]
        (is (= :success (:category result)) (pr-str result))
        (is (= expected (read-file fixture "a.clj")))))))

(deftest move-operation-commits-through-the-existing-resource-apply-path
  (with-fixture [fixture]
    (let [source (str "(ns sample.move)\n\n"
                      "(defn destination [] (worker))\n\n"
                      ";; worker comment\n"
                      "(defn worker [] :ok)\n")]
      (spit (str (:root fixture) "/a.clj") source)
      (let [result (call fixture
                         [{:id :move :in ["a.clj"] :forms ['worker]
                           :do [:move {:before 'destination :with-deps? true}]
                           :expect {:matches 1}}]
                         {:changes 1 :edits 1 :files 1})
            future (read-file fixture "a.clj")]
        (is (= :success (:category result)) (pr-str result))
        (is (= :committed (get-in result [:resource :status])))
        (is (< (str/index-of future ";; worker comment")
               (str/index-of future "(defn destination")))
        (is (str/includes? future ";; worker comment\n(defn worker"))))))

(deftest fix-declares-operation-commits-and-unsafe-move-writes-nothing
  (with-fixture [fixture]
    (let [source (str "(ns sample.declares)\n\n"
                      "(declare helper)\n\n"
                      "(defn main [] (helper))\n\n"
                      "(defn helper [] :ok)\n")]
      (spit (str (:root fixture) "/a.clj") source)
      (let [result (call fixture
                         [{:id :declares :in ["a.clj"]
                           :do [:fix-declares true] :expect {:matches 1}}]
                         {:changes 1 :edits 1 :files 1})
            future (read-file fixture "a.clj")]
        (is (= :success (:category result)) (pr-str result))
        (is (= "(ns sample.declares)\n\n\n(defn helper [] :ok)\n\n(defn main [] (helper))\n\n"
               future))
        (is (not (str/includes? future "(declare)")))
        (is (< (str/index-of future "(defn helper")
               (str/index-of future "(defn main")))))
    (let [source (str "(ns sample.refusal)\n\n"
                      "(defn destination [] (worker))\n\n"
                      "(def config 1)\n\n"
                      "(defn worker [] config)\n")]
      (spit (str (:root fixture) "/a.clj") source)
      (let [result (call fixture
                         [{:id :move :in ["a.clj"] :forms ['worker]
                           :do [:move {:before 'destination :with-deps? false}]
                           :expect {:matches 1}}]
                         {:changes 1 :edits 1 :files 1})]
        (is (= :mechanics (:category result)) (pr-str result))
        (is (str/includes? (:result result) "would-strand-dependencies"))
        (is (= source (read-file fixture "a.clj")))))))

(deftest jolt-source-uses-transaction-replacement
  (with-fixture [fixture]
    (let [file "core.jolt"
          source "(ns sample.jolt)\n\n(defn alpha [x]\n  ;; Keep.\n  (old-call   x))\n"
          expected "(ns sample.jolt)\n\n(defn alpha [x]\n  ;; Keep.\n  (new-call x))\n"]
      (spit (str (:root fixture) "/" file) source)
      (let [result (call fixture
                         [{:id :call :in [file] :forms ['alpha]
                           :find "(old-call x)" :do [:replace "(new-call x)"]
                           :expect {:matches 1}}]
                         {:changes 1 :edits 1 :files 1})]
        (is (= :success (:category result)) (pr-str result))
        (is (= :committed (get-in result [:resource :status])))
        (is (= expected (read-file fixture file)))))))
