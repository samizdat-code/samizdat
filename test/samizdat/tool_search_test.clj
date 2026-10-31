;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tool-search-test
  "Dynamic presentation and discovery stay inside the role's full surface."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.infer :as infer]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.tool-search :as tool-search]
            [samizdat.agent.tools.base :as base]))

(def ^:private catalogue
  (str "### Doing work\n"
       "read_file({path, offset, limit})\n"
       "    Read a file. Core documentation stays whole.\n"
       "    CORE-ONLY-DETAIL.\n"
       "write_file({path, content})\n"
       "    Write a file. This summary is safe to defer.\n\n"
       "    DEFERRED-FULL-DETAIL must only arrive after selection.\n"
       "grep({pattern, paths?, offset?})\n"
       "    Search source text. Find matching lines.\n"
       "    GREP-FULL-DETAIL.\n"
       "split({reason, parts})\n"
       "    Delegate independent work. Parts run separately.\n"
       "    SPLIT-FULL-DETAIL.\n"
       "### Discovering deferred tools\n"
       "tool_search({query?, select?, limit?})\n"
       "    Search allowed tools or select exact names.\n"
       "<!-- deferred-tools -->\n"))

(def ^:private role-table
  {:implementor
   {:doc "build"
    :tools #{"read_file" "write_file" "grep" "split" "tool_search"}
    :core-tools #{"read_file" "tool_search"}}
   :static
   {:doc "static"
    :tools #{"read_file" "write_file" "grep" "split" "tool_search"}}})

(def ^:private discovery-policy
  {:max-results 2
   :max-deferred 3
   :max-selected 2
   :summary-chars 90
   :companion-groups [["grep" "split"]]
   :deferred-heading "Deferred tools:"
   :deferred-instruction "Select exact names with tool_search."
   :query-heading "Matching tools:"
   :selection-heading "Loaded canonical documentation:"
   :query-only-note "Querying does not load tool schemas."
   :no-matches "No allowed tools matched."
   :selection-error "No allowed tools were selected."
   :exclusive-arguments "Pass one argument."
   :invalid-limit "Invalid limit."})

(defn- with-discovery*
  [f]
  (let [original gates/threshold]
    (with-redefs [roles/table (constantly role-table)
                  gates/threshold (fn [k]
                                    (if (= :tool-discovery k)
                                      discovery-policy
                                      (original k)))
                  tool-search/canonical-catalogue (fn [_] catalogue)]
      (f))))

(deftest opening-presentation-is-core-plus-bounded-deferred-summaries
  (with-discovery*
   (fn []
     (let [presented (roles/presentation-catalogue catalogue :implementor)]
       (is (str/includes? presented "read_file({path, offset, limit})"))
       (is (str/includes? presented "CORE-ONLY-DETAIL"))
       (is (str/includes? presented "write_file — Write a file."))
       (is (str/includes? presented "grep — Search source text."))
       (is (not (str/includes? presented "DEFERRED-FULL-DETAIL")))
       (is (not (str/includes? presented "write_file({path, content})")))
       (is (not (re-find #"SPLIT-FULL-DETAIL|GREP-FULL-DETAIL" presented))
           "companion documentation is not eagerly loaded")
       (testing "a role without core policy keeps the existing static catalogue"
         (is (= (str/replace (roles/scope-catalogue catalogue :static)
                             "<!-- deferred-tools -->" "")
                (roles/presentation-catalogue catalogue :static))))))))

(deftest queries-are-bounded-deterministic-and-never-load-documentation
  (with-discovery*
   (fn []
     (let [branch {:id "B1" :role :implementor :turns []}
           call (fn [args]
                  (base/run-tool {:branch branch :tool-name "tool_search" :args args}))
           first-result (call {:query "file" :limit 99})
           second-result (call {:query "file" :limit 99})]
       (is (= :success (:category first-result)))
       (is (= (:result first-result) (:result second-result)))
       (is (= 2 (count (re-seq #"(?m)^- " (:result first-result)))))
       (is (not (str/includes? (:result first-result) "DEFERRED-FULL-DETAIL")))
       (is (empty? (:advertised-tool-docs (:branch first-result))))
       (is (empty? (:advertised-tools (:branch first-result))))
       (testing "a denied name is neither searchable nor selectable"
         (doseq [result [(call {:query "shell"})
                         (call {:select ["shell"]})]]
           (is (not (str/includes? (:result result) "shell({command}")))
           (is (empty? (:advertised-tool-docs (:branch result))))
           (is (empty? (:advertised-tools (:branch result))))))))))

(deftest exact-selection-returns-canonical-docs-and-companions-in-sorted-order
  (with-discovery*
   (fn []
     (let [branch {:id "B1" :role :implementor :turns []}
           selected (base/run-tool {:branch branch :tool-name "tool_search"
                                    :args {:select ["split"]}})
           docs (:advertised-tool-docs (:branch selected))]
       (is (= :success (:category selected)))
       (is (= #{"read_file" "tool_search" "grep" "split"}
              (:advertised-tools (:branch selected))))
       (is (= ["grep" "split"] (mapv :name docs)))
       (is (str/includes? (:result selected) "GREP-FULL-DETAIL"))
       (is (str/includes? (:result selected) "SPLIT-FULL-DETAIL"))
       (is (< (str/index-of (:result selected) "grep({pattern")
              (str/index-of (:result selected) "split({reason")))))))

(deftest cumulative-selection-evicts-the-oldest-deferred-docs-at-the-policy-bound
  (with-discovery*
   (fn []
     (let [branch {:id "B1" :role :implementor :turns []}
           first-selection (base/run-tool {:branch branch :tool-name "tool_search"
                                           :args {:select ["write_file"]}})
           second-selection (base/run-tool {:branch (assoc (:branch first-selection) :turns
                                                           [{:tool "tool_search" :category :success
                                                             :args {:select ["write_file"]}}])
                                            :tool-name "tool_search"
                                            :args {:select ["split"]}})]
       (is (= ["write_file"] (mapv :name (:advertised-tool-docs
                                           (:branch first-selection)))))
       (is (= ["grep" "split"] (mapv :name (:advertised-tool-docs
                                            (:branch second-selection)))))
       (is (= #{"read_file" "tool_search" "grep" "split"}
              (:advertised-tools (:branch second-selection))))
       (is (= (:advertised-tool-docs (:branch second-selection))
              (:docs (tool-search/advertised-from-turns
                      :implementor nil
                      (conj (:turns branch)
                            {:tool "tool_search" :category :success
                             :args {:select ["write_file"]}}
                            {:tool "tool_search" :category :success
                             :args {:select ["split"]}}))))
           "replay applies the same bound and eviction order")))))

(deftest selecting-a-core-tool-does-not-consume-deferred-selection-or-fitting
  (with-discovery*
   (fn []
     (let [original gates/threshold]
       (with-redefs [gates/threshold
                     (fn [k]
                       (if (= :tool-discovery k)
                         (assoc discovery-policy :max-selected 1)
                         (original k)))]
         (infer/clear-render-cache!)
         (try
           (let [branch {:id "core-selection" :role :implementor :turns []
                         :messages [{:role "system"
                                     :content (roles/presentation-catalogue catalogue :implementor)}
                                    {:role "user" :content "go"}]}
                 first-selection (base/run-tool {:branch branch :tool-name "tool_search"
                                                 :args {:select ["write_file"]}})
                 second-selection (base/run-tool
                                   {:branch (assoc (:branch first-selection) :turns
                                                   [{:tool "tool_search" :category :success
                                                     :args {:select ["write_file"]}}])
                                    :tool-name "tool_search"
                                    :args {:select ["read_file"]}})
                 docs (:advertised-tool-docs (:branch second-selection))
                 fitted (infer/prepare
                         {:llm-adapter ::test
                          :llm-config {:provider :test :model "core-selection-window"
                                       :context-window 220 :max-tokens 16
                                       :features #{:native-tools}}}
                         (infer/of-branch (:branch second-selection)) {:fit? true})]
             (is (= ["write_file"] (mapv :name docs))
                 "a core no-op selection does not evict deferred documentation")
             (is (= (:docs (tool-search/advertised-from-turns
                            :implementor nil
                            [{:tool "tool_search" :category :success
                              :args {:select ["write_file"]}}
                             {:tool "tool_search" :category :success
                              :args {:select ["read_file"]}}]))
                    docs)
                 "replay also excludes core names from the deferred budget")
             (is (true? (get-in fitted [:accounting :admissible?])))
             (is (every? (set (mapv :name (:tools fitted))) ["read_file" "tool_search"])
                 "core native specs survive deferred-spec fitting"))
           (finally (infer/clear-render-cache!))))))))

(deftest real-selection-feeds-the-next-real-infer-prepare
  (with-discovery*
   (fn []
     (infer/clear-render-cache!)
     (try
       (let [core (roles/presentation-catalogue catalogue :implementor)
             branch {:id "discovery-integration"
                     :role :implementor
                     :turns []
                     :messages [{:role "system" :content core}
                                {:role "user" :content "load the writer"}]}
             selected (base/run-tool {:branch branch :tool-name "tool_search"
                                      :args {:select ["write_file"] :limit 50}})
             tape (infer/of-branch (:branch selected))
             prepared (infer/prepare
                       {:llm-adapter ::test
                        :llm-config {:provider :test :model "discovery"
                                     :context-window 32768 :max-tokens 64
                                     :features #{:native-tools}}}
                       tape {:attempt 1 :fit? false})
             specs (into {} (map (juxt :name identity)) (:tools prepared))]
         (is (= :success (:category selected)))
         (is (str/includes? (:result selected) "DEFERRED-FULL-DETAIL")
             "selection returns the whole canonical entry across blank lines")
         (is (= ["read_file" "tool_search" "write_file"]
                (mapv :name (:tools prepared))))
         (is (= #{:query :select :limit}
                (set (keys (get-in specs ["tool_search" :parameters :properties]))))
             "execution and native presentation use the full canonical argument contract")
         (is (= #{:path :content}
                (set (keys (get-in specs ["write_file" :parameters :properties]))))))
       (finally (infer/clear-render-cache!))))))

(deftest request-fitting-sheds-deferred-native-specs-when-messages-cannot-make-room
  (with-discovery*
   (fn []
     (infer/clear-render-cache!)
     (try
       (let [core (roles/presentation-catalogue catalogue :implementor)
             branch {:id "discovery-window"
                     :role :implementor
                     :turns []
                     :messages [{:role "system" :content core}
                                {:role "user" :content "go"}]}
             selected (base/run-tool {:branch branch :tool-name "tool_search"
                                      :args {:select ["split"]}})
             tape (infer/of-branch (:branch selected))
             ctx {:llm-adapter ::test
                  :llm-config {:provider :test :model "small-discovery-window"
                               :context-window 220 :max-tokens 16
                               :features #{:native-tools}}}
             oversized (infer/prepare ctx tape {:fit? false})
             fitted (infer/prepare ctx tape {:fit? true})]
         (is (false? (get-in oversized [:accounting :admissible?]))
             "the fixture really does overflow with the selected native specs")
         (is (not (false? (:ok fitted))))
         (is (true? (get-in fitted [:accounting :admissible?])))
         (is (= ["read_file" "tool_search"] (mapv :name (:tools fitted)))
             "core tools remain while deferrable specs are omitted from this request"))
       (finally (infer/clear-render-cache!))))))

(defn- prepared-tool-names [role presented]
  (infer/clear-render-cache!)
  (let [prepared (infer/prepare
                  {:llm-adapter ::test
                   :llm-config {:provider :test :model "denied-presentation"
                                :context-window 32768 :max-tokens 64
                                :features #{:native-tools}}}
                  {:id (str "presentation-" (name role))
                   :messages [{:role "system" :content presented}
                              {:role "user" :content "prepare"}]
                   :turns []}
                  {:attempt 1 :fit? false})]
    (mapv :name (:tools prepared))))

(deftest denied-core-tool-is-absent-from-opening-docs-and-native-specs
  (with-discovery*
   (fn []
     (with-redefs [roles/table
                   (constantly (assoc-in role-table [:implementor :denied]
                                         #{"read_file"}))]
       (let [presented (roles/presentation-catalogue catalogue :implementor :on)]
         (is (not (str/includes? presented "read_file({path, offset, limit})")))
         (is (not (str/includes? presented "CORE-ONLY-DETAIL")))
         (is (= ["tool_search"] (prepared-tool-names :implementor presented))))))))

(deftest denied-deferred-tool-is-absent-from-opening-docs-and-native-specs
  (with-discovery*
   (fn []
     (with-redefs [roles/table
                   (constantly (assoc-in role-table [:implementor :denied]
                                         #{"write_file"}))]
       (let [presented (roles/presentation-catalogue catalogue :implementor :on)]
         (is (not (str/includes? presented "write_file —")))
         (is (not (str/includes? presented "DEFERRED-FULL-DETAIL")))
         (is (= ["read_file" "tool_search"]
                (prepared-tool-names :implementor presented))))))))

(deftest core-equals-surface-leaves-no-deferred-marker
  (with-discovery*
   (fn []
     (with-redefs [roles/table
                   (constantly
                    (assoc role-table :core-only
                           {:doc "core only"
                            :tools #{"read_file" "tool_search"}
                            :core-tools #{"read_file" "tool_search"}}))]
       (let [presented (roles/presentation-catalogue catalogue :core-only :on)]
         (is (str/includes? presented "read_file({path, offset, limit})"))
         (is (not (str/includes? presented "<!-- deferred-tools -->")))
         (is (not (str/includes? presented "Deferred tools:"))))))))
