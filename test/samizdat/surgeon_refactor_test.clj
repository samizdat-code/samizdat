;; samizdat - a self-hosting agentic harness
;; Inspection fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim, source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-refactor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [rewrite-clj.parser :as parser]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.resource :as resource-tool]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.db :as db]
            [samizdat.store.resources :as store]
            [samizdat.store.runs :as runs]
            [samizdat.surgeon.extract :as extract]
            [samizdat.surgeon.extract-header :as header]
            [samizdat.surgeon.quoted-var-refs :as quoted]
            [samizdat.surgeon.rename :as rename]
            [samizdat.surgeon.syntax-var-refs :as syntax]))

(def extraction-source
  (str "(ns sample.core\n"
       "  (:require [clojure.string :as str]))\n\n"
       "(defn- helper [x] (str/upper-case x))\n\n"
       "(defn- moved [x] (helper x))\n\n"
       "(defn remains [x] (moved x))\n"))

(def extraction-consumer
  (str "(ns sample.consumer (:require [sample.core :as core]))\n"
       "(def pinned #'core/moved)\n"))

(defn- extraction-input []
  {:file "src/sample/core.clj"
   :sources {"src/sample/core.clj" extraction-source
             "src/sample/extracted.clj" nil
             "src/sample/consumer.clj" extraction-consumer}
   :forms '[helper moved]
   :to "src/sample/extracted.clj"
   :target-ns "sample.extracted"
   :derive-required-public-forms true})

(deftest extraction-requires-explicit-structural-caller-dispositions
  (let [refused (extract/compile-plan (extraction-input))]
    (is (= :caller-disposition-required (:error-type refused)))
    (is (= ["src/sample/consumer.clj"]
           (get-in refused [:caller-proof :unknown-callers])))
    (is (false? (get-in refused [:caller-proof :semantic-complete])))
    (is (nil? (:future-sources refused))))
  (let [plan (extract/compile-plan
              (assoc (extraction-input)
                     :caller-decisions {"src/sample/consumer.clj" :review-required}))]
    (is (nil? (:error plan)) (pr-str plan))
    (is (= ["src/sample/extracted.clj"] (:created-files plan)))
    (is (= #{"src/sample/core.clj" "src/sample/extracted.clj"}
           (set (keys (:future-sources plan)))))
    (is (false? (get-in plan [:caller-proof :semantic-complete])))
    (is (= :structural-candidates (get-in plan [:caller-proof :status])))
    (is (= {"src/sample/consumer.clj" :review-required}
           (get-in plan [:caller-proof :dispositions])))
    (is (= 1 (count (:quoted-var-references plan))))
    (is (= ["moved"] (:public-forms plan)))
    (is (str/includes? (get-in plan [:future-sources "src/sample/extracted.clj"])
                       "(defn moved "))
    (is (str/includes? (get-in plan [:future-sources "src/sample/core.clj"])
                       "[sample.extracted :as extracted :refer [moved]]"))
    (doseq [[_ source] (:future-sources plan)]
      (is (some? (parser/parse-string-all source))))))

(deftest extraction-header-minimizes-or-copies-without-guessing
  (let [ns-form "(ns sample.core (:require [alpha.core :as alpha] [beta.core :as beta]))"
        minimal (header/compile-target-header
                 {:source-ns-form ns-form :target-ns "sample.extracted"
                  :form-sources ["(defn moved [] alpha/value)"]})
        copied (header/compile-target-header
                {:source-ns-form ns-form :target-ns "sample.extracted"
                 :form-sources ["(defn moved [] alpha/value)"]
                 :require-policy :copy-all})]
    (is (= ["alpha.core"] (:target-requires minimal)))
    (is (= ["beta.core"] (:omitted-target-requires minimal)))
    (is (= "(ns sample.extracted (:require [alpha.core :as alpha] [beta.core :as beta]))"
           (:ns-form copied)))
    (is (= :copied-exactly (:target-requires copied))))
  (let [refused (header/compile-target-header
                 {:source-ns-form "(ns sample.core (:require [side.effects]))"
                  :target-ns "sample.extracted" :form-sources ["(def x 1)"]})]
    (is (= :side-effect-only-require (:reason refused)))
    (is (false? (:ok refused)))))

(deftest quoted-and-qualified-syntax-evidence-stays-structural
  (let [source (str "(ns sample.consumer (:require [sample.core :as core]))\n"
                    "(def refs [#'core/moved core/moved 'core/moved `core/moved\n"
                    "           `(var sample.core/moved) #_(var sample.core/moved)])\n"
                    "(def text \"core/moved\")\n")
        var-refs (quoted/references-in-source
                  "src/sample/consumer.clj" source "sample.core/moved")
        syntax-proof (syntax/scan-sources
                      {"src/sample/consumer.clj" source} "sample.core/moved")]
    (is (= ["#'core/moved"] (mapv :source var-refs)))
    (is (= [:exact-qualified-syntax :exact-qualified-syntax]
           (mapv :reference-authority (:locations syntax-proof))))
    (is (every? true? (map :authority (:locations syntax-proof))))
    (is (empty? (:proof-gaps syntax-proof))))
  (let [bare (syntax/scan-sources
              {"src/sample/core.clj"
               "(ns sample.core)\n(defn moved [] :ok)\n(defn call [] (moved))\n"}
              "sample.core/moved")]
    (is (empty? (:locations bare)))
    (is (= 1 (:proof-gap-count bare)))
    (is (false? (-> bare :proof-gaps first :authority)))))

(deftest namespace-rename-plans-final-sources-and-dispositions
  (let [sources {"src/old_ns/core.clj" "(ns old-ns.core)\n(defn ping [] :ok)\n"
                 "src/new_ns/core.clj" nil
                 "src/sample/consumer.clj"
                 (str "(ns sample.consumer (:require [old-ns.core :as old]))\n"
                      "(def text \"old-ns.core/ping\")\n")
                 "src/sample/notes.clj"
                 "(ns sample.notes)\n(def text \"old-ns.core/ping\")\n"}
        input {:sources sources :from 'old-ns :to 'new-ns}
        refused (rename/compile-plan input)]
    (is (= :caller-disposition-required (:error-type refused)))
    (is (= ["src/sample/consumer.clj" "src/sample/notes.clj"]
           (get-in refused [:caller-proof :unknown-callers])))
    (let [plan (rename/compile-plan
                (assoc input :caller-decisions
                       {"src/sample/consumer.clj" :review-required
                        "src/sample/notes.clj" :not-a-caller}))]
      (is (= ["src/new_ns/core.clj"] (:created-files plan)))
      (is (= ["src/old_ns/core.clj"] (:deleted-files plan)))
      (is (= [{:from "src/old_ns/core.clj" :to "src/new_ns/core.clj"}]
             (:renamed-files plan)))
      (is (str/includes? (get-in plan [:future-sources "src/new_ns/core.clj"])
                         "(ns new-ns.core)"))
      (is (str/includes? (get-in plan [:future-sources "src/sample/consumer.clj"])
                         "[new-ns.core :as old]"))
      (is (str/includes? (get-in plan [:future-sources "src/sample/consumer.clj"])
                         "old-ns.core/ping"))
      (is (not (contains? (:future-sources plan) "src/sample/notes.clj")))
      (is (false? (get-in plan [:caller-proof :semantic-complete]))))))

(deftest malformed-refactor-input-refuses-without-candidates
  (is (thrown? Exception
               (extract/compile-plan
                (assoc (extraction-input)
                       :sources {"src/sample/core.clj" "(ns sample.core"
                                 "src/sample/extracted.clj" nil}))))
  (is (= :extraction-target-exists
         (:error-type (extract/compile-plan
                       (assoc-in (extraction-input)
                                 [:sources "src/sample/extracted.clj"] "(ns existing)\n")))))
  (is (thrown? Exception
               (rename/compile-plan
                {:sources {"src/old_ns/core.clj" "(ns old-ns.core"}
                 :from 'old-ns :to 'new-ns}))))

(defn- fixture []
  (let [root (str (fs/create-temp-dir))
        conn (db/open! ":memory:")
        run-id (runs/start-run! conn {:problem "surgeon refactor"})]
    (runs/open-branch! conn run-id {:branch-id "B1"})
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

(defn- call [fixture change expect]
  (tools/run-tool
   (assoc fixture :tool-name "apply_clojure_changes"
          :args {:changes [change] :expect expect})))

(deftest native-extraction-uses-resource-compensation-for-created-files
  (with-fixture [fixture]
    (spit (str (:root fixture) "/core.clj") extraction-source)
    (let [publish (fn [path content revision]
                    (if (str/ends-with? (str path) "core.clj")
                      (throw (java.io.IOException. "injected source publication failure"))
                      (do (spit path content) revision)))
          registry (resource/registry
                    {:workspace (workspace/workspace-resolver {:publish publish})})]
      (with-redefs [resource-tool/registry-for (constantly registry)]
        (let [result (call fixture
                           {:id :extract :in ["core.clj"] :forms '[helper moved]
                            :do [:extract {:to "extracted.clj" :target-ns 'sample.extracted
                                           :require-policy :copy-all
                                           :derive-required-public-forms true}]
                            :expect {:matches 2}}
                           {:changes 1 :edits 2 :files 2})
              members (get-in result [:resource :members])
              rows (store/change-members (:conn fixture) (get-in result [:resource :id]))]
          (is (= :compensated (get-in result [:resource :status])) (pr-str result))
          (is (= [:create :write] (mapv :operation members)))
          (is (every? #(or (nil? (:base-revision %))
                           (string? (:base-revision %))) members))
          (is (= ["compensated" "publishing"] (mapv :status rows)))
          (is (= extraction-source (slurp (str (:root fixture) "/core.clj"))))
          (is (not (fs/exists? (str (:root fixture) "/extracted.clj")))))))))

(deftest native-namespace-rename-commits-revisioned-rename-and-write-members
  (with-fixture [fixture]
    (fs/create-dirs (str (:root fixture) "/old_ns"))
    (spit (str (:root fixture) "/old_ns/core.clj") "(ns old-ns.core)\n(defn ping [] :ok)\n")
    (spit (str (:root fixture) "/consumer.clj")
          "(ns consumer (:require [old-ns.core :as old]))\n(defn ping [] (old/ping))\n")
    (let [result (call fixture
                       {:id :rename :in ["old_ns/core.clj" "consumer.clj"]
                        :do [:rename-namespace {:from 'old-ns :to 'new-ns}]
                        :expect {:matches 2}}
                       {:changes 1 :edits 2 :files 2})
          members (get-in result [:resource :members])]
      (is (= :committed (get-in result [:resource :status])) (pr-str result))
      (is (= [:rename :write] (mapv :operation members)))
      (is (nil? (:target-revision (first members))))
      (is (string? (:base-revision (first members))))
      (is (str/includes? (slurp (str (:root fixture) "/new_ns/core.clj"))
                         "(ns new-ns.core)"))
      (is (not (fs/exists? (str (:root fixture) "/old_ns/core.clj"))))
      (is (str/includes? (slurp (str (:root fixture) "/consumer.clj"))
                         "[new-ns.core :as old]")))))

(deftest extraction-refuses-private-required-var-without-visibility-decision
  (let [plan (extract/compile-plan
              (-> (extraction-input)
                  (dissoc :derive-required-public-forms)
                  (assoc :caller-decisions
                         {"src/sample/consumer.clj" :review-required})))]
    (is (= :required-public-forms (:error-type plan)))
    (is (= ["moved"] (:missing-required-public-forms plan)))
    (is (nil? (:future-sources plan)))))

(deftest namespace-rename-moves-every-segment-of-a-dotted-namespace
  (let [plan (rename/compile-plan
              {:sources {"src/old/ns/core.clj" "(ns old.ns.core)\n(defn ping [] :ok)\n"
                         "src/new/ns/core.clj" nil}
               :from 'old.ns :to 'new.ns})]
    (is (= ["src/new/ns/core.clj"] (:created-files plan)))
    (is (= ["src/old/ns/core.clj"] (:deleted-files plan)))
    (is (= [{:from "src/old/ns/core.clj" :to "src/new/ns/core.clj"}]
           (:renamed-files plan)))
    (is (= [{:operation :rename :file "src/old/ns/core.clj" :target "src/new/ns/core.clj"}]
           (:resource-effects plan)))
    (is (str/includes? (get-in plan [:future-sources "src/new/ns/core.clj"])
                       "(ns new.ns.core)"))))

(deftest later-member-failure-restores-original-bytes-after-rename
  (with-fixture [fixture]
    (let [original "(ns old-ns.core)\n(defn ping [] :ok)\n"]
      (fs/create-dirs (str (:root fixture) "/old_ns"))
      (spit (str (:root fixture) "/old_ns/core.clj") original)
      (spit (str (:root fixture) "/consumer.clj")
            "(ns consumer (:require [old-ns.core :as old]))\n(defn ping [] (old/ping))\n")
      (let [publish (fn [path content revision]
                      (if (str/ends-with? (str path) "consumer.clj")
                        (throw (java.io.IOException. "injected later write failure"))
                        (do (spit path content) revision)))
            registry (resource/registry
                      {:workspace (workspace/workspace-resolver {:publish publish})})]
        (with-redefs [resource-tool/registry-for (constantly registry)]
          (let [result (call fixture
                             {:id :rename :in ["old_ns/core.clj" "consumer.clj"]
                              :do [:rename-namespace {:from 'old-ns :to 'new-ns}]
                              :expect {:matches 2}}
                             {:changes 1 :edits 2 :files 2})]
            (is (= :compensated (get-in result [:resource :status])) (pr-str result))
            (is (= original (slurp (str (:root fixture) "/old_ns/core.clj"))))
            (is (not (fs/exists? (str (:root fixture) "/new_ns/core.clj"))))))))))

(def cyclic-source
  "(ns sample.cyclic)\n\n(defn even? [x] (if (zero? x) true (odd? (dec x))))\n\n(defn odd? [x] (if (zero? x) false (even? (dec x))))\n")

(deftest cyclic-extraction-refuses-before-producing-candidates
  (let [plan (extract/compile-plan
              {:file "cyclic.clj" :sources {"cyclic.clj" cyclic-source}
               :forms '[even? odd?] :to "extracted.clj" :target-ns "sample.extracted"})]
    (is (= :extraction-forms-not-orderable (:error-type plan)) (pr-str plan))
    (is (= #{"even?" "odd?"} (set (:unordered-forms plan))))
    (is (:source-unchanged plan))
    (is (:target-unchanged plan))
    (is (nil? (:future-sources plan)))))

(deftest native-cyclic-extraction-stages-nothing-and-preserves-source
  (with-fixture [fixture]
    (spit (str (:root fixture) "/cyclic.clj") cyclic-source)
    (let [staged (atom 0)]
      (with-redefs [resource-tool/stage-proposal! (fn [& _] (swap! staged inc))]
        (let [result (call fixture
                           {:id :extract :in ["cyclic.clj"] :forms '[even? odd?]
                            :do [:extract {:to "extracted.clj" :target-ns 'sample.extracted}]
                            :expect {:matches 2}}
                           {:changes 1 :edits 2 :files 2})]
          (is (= :mechanics (:category result)) (pr-str result))
          (is (str/includes? (:result result) "extraction-forms-not-orderable"))
          (is (zero? @staged))
          (is (= cyclic-source (slurp (str (:root fixture) "/cyclic.clj"))))
          (is (not (fs/exists? (str (:root fixture) "/extracted.clj"))))
          (is (empty? (store/unfinished-changes
                        (:conn fixture) {:run-id (:run-id fixture) :branch-id "B1"}))))))))
