;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.surgeon-semantic-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.lsp.client :as client]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.executions :as executions]
            [samizdat.store.runs :as runs]
            [samizdat.surgeon.semantic :as semantic]
            [samizdat.surgeon.source-anchor :as source-anchor]
            [samizdat.surgeon.tool :as surgeon-tool]))

(def definition-file "src/sample/core.clj")
(def caller-file "src/sample/caller.clj")
(def quoted-file "src/sample/quoted.clj")

(def definition-source
  "(ns sample.core)\n\n(defn alpha [x] x)\n")

(def caller-source
  "(ns sample.caller (:require [sample.core :as a]))\n\n(defn use-alpha [x] (a/alpha x))\n")

(def quoted-source
  "(ns sample.quoted (:require [sample.core :as a]))\n\n(def quoted #'a/alpha)\n")

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "semantic surgeon"})
        clock (atom 1000000)]
    (runs/open-branch! conn run-id {:branch-id "B1"})
    (runs/open-branch! conn run-id {:branch-id "B2"})
    (doseq [[file source] [[definition-file definition-source]
                           [caller-file caller-source]
                           [quoted-file quoted-source]]]
      (io/make-parents (io/file root file))
      (spit (io/file root file) source))
    {:root root :conn conn :run-id run-id :clock clock
     :now (fn [] @clock)
     :lsp-client {:session-id "session-a"}
     :lsp-sync (fn [& _] nil)
     :branch {:id "B1" :role :implementor}}))

(defn- cleanup [{:keys [root conn run-id]}]
  (resource-tool/forget-run! run-id)
  (executions/forget-run! run-id)
  (db/close conn)
  (fs/delete-tree root))

(defmacro with-fixture [[name] & body]
  `(let [~name (fixture)]
     (try ~@body (finally (cleanup ~name)))))

(defn- absolute [fixture file]
  (.getCanonicalPath (io/file (:root fixture) file)))

(defn- location
  ([fixture file line start end]
   {:uri (str "file://" (absolute fixture file))
    :range {:start {:line line :character start}
            :end {:line line :character end}}})
  ([fixture file line start end owner]
   (assoc (location fixture file line start end)
          :owner owner
          :lsp_session "session-a"
          :source_sha256 (workspace/content-revision
                          (slurp (absolute fixture file))))))

(defn- fake-lsp
  ([fixture]
   (fake-lsp fixture {}))
  ([fixture {:keys [definition references]
             :or {definition :default references :default}}]
   (let [definition-value (if (= :default definition)
                            (location fixture definition-file 2 6 11 "alpha")
                            definition)
         references-value (if (= :default references)
                            [(location fixture definition-file 2 6 11 "alpha")
                             (location fixture caller-file 2 22 29 "use-alpha")]
                            references)]
   (fn [f]
     (with-redefs [client/definition (fn [& _] definition-value)
                   client/references (fn [& _] references-value)]
       (f))))))

(defn- prepare [fixture request]
  (semantic/prepare fixture request))

(defn- semantic-prepare [fixture]
  ((fake-lsp fixture)
   #(prepare fixture {:subject "sample.core/alpha" :intent "change alpha safely"})))

(defn- apply-basis
  ([fixture prepared decisions]
   (apply-basis fixture prepared decisions {}))
  ([fixture prepared decisions overrides]
   (tools/run-tool
    (merge fixture
           {:tool-name "apply_clojure_changes"
            :args {:basis (:basis prepared) :decisions decisions}}
           overrides))))

(defn- refusal-kind [result]
  (some->> (:result result) (re-find #"refused the whole transaction: ([a-z0-9-]+)") second))

(deftest exact-source-anchor-selects-the-owner-token-from-retained-source
  (let [built (source-anchor/build-source-anchor
               "sample.core/alpha" definition-file definition-source)]
    (is (:ok built) (pr-str built))
    (is (= definition-file (get-in built [:source-anchor :file])))
    (is (= "alpha" (get-in built [:source-anchor :owner])))
    (is (= (workspace/content-revision definition-source)
           (get-in built [:source-anchor :source_sha256])))
    (is (= {:start {:line 2 :character 6} :end {:line 2 :character 11}}
           (get-in built [:source-anchor :selection_range])))))

(deftest exact-source-preparation-needs-no-index-and-claims-no-callers
  (with-fixture [fixture]
    (let [fixture (dissoc fixture :lsp-client :lsp-sync)]
      (with-redefs [client/client-for (fn [& _] (throw (ex-info "no index" {})))]
        (let [prepared (prepare fixture {:file definition-file :form "alpha"
                                         :intent "replace the exact owner"})]
          (is (:ok prepared) (pr-str prepared))
          (is (= 1 (count (:surface prepared))))
          (is (= :exact-source (get-in prepared [:surface 0 :authority])))
          (is (false? (get-in prepared [:surface 0 :semantic-complete?])))
          (is (false? (:caller-proof? prepared)))
          (is (false? (:references-claimed? prepared)))
          (is (= {:basis (:basis prepared)
                  :decisions [{:site "change/s01" :replace nil}]}
                 (:next-call prepared)))
          (let [observed (resource-tool/authorized-observation
                          fixture (str "execution:" (:basis prepared)))]
            (is (= (:basis prepared) (:identity observed)))))))))

(deftest resolved-references-and-quoted-vars-form-one-authority-labelled-union
  (with-fixture [fixture]
    (let [prepared (semantic-prepare fixture)]
      (is (:ok prepared) (pr-str prepared))
      (is (= 3 (count (:surface prepared))) (pr-str (:surface prepared)))
      (is (= [:definition :reference :reference] (mapv :role (:surface prepared))))
      (is (= [:language-server+exact-source
              :language-server+exact-source
              :structural-var-quote+exact-source]
             (mapv :authority (:surface prepared))))
      (is (= [true true false] (mapv :semantic-complete? (:surface prepared))))
      (is (:caller-proof? prepared))
      (is (:references-claimed? prepared))
      (is (= "session-a" (:lsp-session prepared)))
      (is (= ["change/s01" "change/s02" "change/s03"]
             (mapv :site (get-in prepared [:next-call :decisions])))))))

(deftest missing-ambiguous-stale-mixed-session-and-owner-evidence-refuses
  (with-fixture [fixture]
    (doseq [[label options expected]
            [["missing" {:definition nil} :semantic-definition-missing]
             ["ambiguous" {:definition [(location fixture definition-file 2 6 11 "alpha")
                                        (location fixture definition-file 2 6 11 "alpha")]}
              :semantic-definition-ambiguous]
             ["stale" {:definition (assoc (location fixture definition-file 2 6 11 "alpha")
                                          :source_sha256 (apply str (repeat 64 "0")))}
              :semantic-source-drift]
             ["mixed session" {:references [(location fixture definition-file 2 6 11 "alpha")
                                            (assoc (location fixture caller-file 2 22 29 "use-alpha")
                                                   :lsp_session "session-b")]}
              :semantic-session-drift]
             ["owner disagreement" {:references [(location fixture definition-file 2 6 11 "wrong")]}
              :semantic-owner-drift]
             ["resource disagreement" {:definition (assoc (location fixture definition-file 2 6 11 "alpha")
                                                          :file_path (absolute fixture caller-file))}
              :semantic-evidence-incomplete]
             ["missing references" {:references nil} :semantic-references-missing]]]
      (testing label
        (let [refused ((fake-lsp fixture options)
                       #(prepare fixture {:subject "sample.core/alpha" :intent "refuse unsafe evidence"}))]
          (is (false? (:ok refused)) (pr-str refused))
          (is (= expected (:error-type refused)) (pr-str refused))
          (is (:source-unchanged refused))
          (is (nil? (:basis refused))))))))

(deftest basis-decisions-use-retained-observations-without-rediscovery
  (with-fixture [fixture]
    (let [prepared (semantic-prepare fixture)
          decisions [{:site "change/s01" :replace "(defn alpha [x] (inc x))"}
                     {:site "change/s02" :keep true}
                     {:site "change/s03" :delete true}]]
      (with-redefs [surgeon-tool/observe-files
                    (fn [& _] (throw (ex-info "semantic rediscovery" {})))]
        (let [result (apply-basis fixture prepared decisions)]
          (is (= :success (:category result)) (pr-str result))
          (is (= :committed (get-in result [:resource :status])))
          (is (= "(ns sample.core)\n\n(defn alpha [x] (inc x))\n"
                 (slurp (absolute fixture definition-file))))
          (is (= caller-source (slurp (absolute fixture caller-file))))
          (is (= "(ns sample.quoted (:require [sample.core :as a]))\n"
                 (slurp (absolute fixture quoted-file))))
          (is (= :complete (:status (executions/execution (:conn fixture) (:basis prepared))))))))))

(deftest exact-source-replacement-uses-the-retained-owner-source
  (with-fixture [fixture]
    (let [prepared (prepare fixture {:file definition-file :form "alpha"
                                     :intent "replace one exact owner"})
          result (apply-basis fixture prepared
                              [{:site "change/s01" :replace "(defn alpha [x] (inc x))"}])]
      (is (= :success (:category result)) (pr-str result))
      (is (= "(ns sample.core)\n\n(defn alpha [x] (inc x))\n"
             (slurp (absolute fixture definition-file)))))))

(deftest basis-replacement-must-be-one-complete-named-owner
  (with-fixture [fixture]
    (let [prepared (prepare fixture {:file definition-file :form "alpha"
                                     :intent "replace one exact owner"})
          result (apply-basis fixture prepared
                              [{:site "change/s01" :replace "(inc 1)"}])]
      (is (= :mechanics (:category result)) (pr-str result))
      (is (= "invalid-basis-decision" (refusal-kind result)))
      (is (= definition-source (slurp (absolute fixture definition-file)))))))

(deftest exact-source-deletion-refuses-without-caller-proof
  (with-fixture [fixture]
    (let [prepared (prepare fixture {:file definition-file :form "alpha"
                                     :intent "delete without semantic callers"})
          result (apply-basis fixture prepared [{:site "change/s01" :delete true}])]
      (is (= :mechanics (:category result)) (pr-str result))
      (is (= "basis-caller-proof-required" (refusal-kind result)))
      (is (= definition-source (slurp (absolute fixture definition-file)))))))

(deftest wrong-owner-and-expired-bases-refuse-before-staging
  (with-fixture [fixture]
    (let [prepared (prepare fixture {:file definition-file :form "alpha"
                                     :intent "replace one owner"})
          decisions [{:site "change/s01" :replace "(defn alpha [x] (inc x))"}]
          wrong-owner (apply-basis fixture prepared decisions
                                   {:branch {:id "B2" :role :implementor}})]
      (is (= "basis-owner-mismatch" (refusal-kind wrong-owner)) (pr-str wrong-owner))
      (swap! (:clock fixture) + 300001)
      (let [expired (apply-basis fixture prepared decisions)]
        (is (= "unknown-or-expired-basis" (refusal-kind expired)) (pr-str expired)))
      (is (= definition-source (slurp (absolute fixture definition-file)))))))

(deftest inspect-tool-exposes-prepare-change-as-a-typed-refusal
  (with-fixture [fixture]
    (let [result ((fake-lsp fixture {:definition nil})
                  #(tools/run-tool
                    (assoc fixture :tool-name "inspect_clojure"
                           :args {:mode "prepare-change" :subject "sample.core/alpha"
                                  :intent "refuse missing semantic evidence"})))]
      (is (= :mechanics (:category result)) (pr-str result))
      (is (str/includes? (:result result) "semantic-definition-missing"))
      (is (not (contains? result :resource))))))
