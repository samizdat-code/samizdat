;; samizdat - a self-hosting agentic harness
;; Semantic preparation adapted from clj-surgeon source commit
;; affdfe3fd59a63b3fbbb50da16b82509fafea907; see
;; docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon.semantic
  "Proof-carrying semantic preparation over Samizdat LSP and resource snapshots."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [jolt.fs :as fs]
            [samizdat.lexicon :as lexicon]
            [samizdat.agent.files :as files]
            [samizdat.agent.gates :as gates]
            [samizdat.lsp.client :as client]
            [samizdat.resource :as resource]
            [samizdat.resource.workspace :as workspace]
            [samizdat.store.executions :as executions]
            [samizdat.surgeon.intent-transaction :as intent]
            [samizdat.surgeon.outline :as outline]
            [samizdat.surgeon.quoted-var-refs :as quoted]
            [samizdat.surgeon.source-anchor :as source-anchor]
            [samizdat.surgeon.tool :as tool])
  (:import [java.net URI]))

(def ^:private basis-kind "surgeon-semantic-basis")

(defn- fail! [kind data]
  (throw (ex-info (name kind) (merge {:semantic/error kind} data))))

(defn- refusal [kind data]
  (merge {:ok false :error-type kind :source-unchanged true} data))

(defn- now-ms [ctx]
  (if-let [clock (:now ctx)] (clock) (System/currentTimeMillis)))

(defn- canonical-root [ctx]
  (str (fs/canonicalize (:root ctx))))

(defn- observe! [ctx cache file]
  (let [canonical (or (tool/canonical-file ctx file)
                      (fail! :semantic-path-outside-project {:file file}))]
    (if-let [observation (get @cache canonical)]
      observation
      (let [observation (first (vals (tool/observe-files ctx [{:file canonical}])))]
        (when-not (= :workspace (:resolver observation))
          (fail! :unsupported-resource {:file canonical}))
        (when (nil? (:content observation))
          (fail! :missing-resource {:file canonical}))
        (when (> (count (:content observation))
                 (:max-source-chars (gates/threshold :clj-surgeon)))
          (fail! :source-limit {:file canonical}))
        (swap! cache assoc canonical observation)
        observation))))

(defn- existing-file? [ctx file]
  (when-let [canonical (tool/canonical-file ctx file)]
    (.isFile (java.io.File. (canonical-root ctx) canonical))))

(defn- location-path [location]
  (let [uri (:uri location)]
    (cond
      (and uri (or (string? uri) (keyword? uri)))
      (.getPath (URI. (str uri)))

      (:file_path location) (str (:file_path location))
      (:file location) (str (:file location))
      :else nil)))

(defn- complete-range? [range]
  (every? #(and (integer? %) (not (neg? %)))
          [(get-in range [:start :line])
           (get-in range [:start :character])
           (get-in range [:end :line])
           (get-in range [:end :character])]))

(defn- evidence-session [location]
  (or (:lsp-session location) (:lsp_session location)))

(defn- evidence-hash [location]
  (or (:source-sha256 location) (:source_sha256 location)))

(defn- validate-location! [ctx cache location session]
  (when-not (map? location)
    (fail! :semantic-evidence-incomplete {:location location}))
  (when-not (complete-range? (:range location))
    (fail! :semantic-evidence-incomplete {:location location}))
  (let [path (location-path location)
        file (when path (tool/canonical-file ctx path))]
    (when-not file
      (fail! :semantic-path-outside-project {:path path}))
    (doseq [provided ((juxt :file_path :file) location)
            :when (some? provided)]
      (when-not (= file (tool/canonical-file ctx (str provided)))
        (fail! :semantic-evidence-incomplete {:file provided :actual-file file})))
    (when (and session (evidence-session location)
               (not= session (evidence-session location)))
      (fail! :semantic-session-drift
             {:expected-session session :actual-session (evidence-session location)
              :file file}))
    (let [observation (observe! ctx cache file)]
      (when-let [provided (evidence-hash location)]
        (when-not (= provided (:revision observation))
          (fail! :semantic-source-drift
                 {:file file :provider-hash provided :actual-hash (:revision observation)})))
      {:file file :observation observation})))

(defn- exact-owner-at [file source location]
  (let [target-line (some-> (get-in location [:range :start :line]) inc)
        owners (->> (outline/top-level-form-records file source)
                    (filter (fn [{:keys [name line end-line]}]
                              (and name target-line line end-line
                                   (<= line target-line end-line))))
                    vec)]
    (when (= 1 (count owners))
      (first owners))))

(defn- location->site
  [ctx cache {:keys [owner] :as location} {:keys [role subject authority semantic-complete?]} session]
  (let [{:keys [file observation]} (validate-location! ctx cache location session)
        owner-record (exact-owner-at file (:content observation) location)]
    (when-not owner-record
      (fail! :semantic-owner-not-found
             {:file file :role role :subject subject :range (:range location)}))
    (when (and owner (not= (str owner) (str (:name owner-record))))
      (fail! :semantic-owner-drift
             {:file file :role role :subject subject
              :provider-form owner :actual-form (:name owner-record)}))
    {:file file :role role :form (str (:name owner-record))
     :line (:line owner-record) :end-line (:end-line owner-record)
     :before (:source owner-record) :source-revision (:revision observation)
     :subjects (if subject #{subject} #{})
     :authority authority :semantic-complete? (boolean semantic-complete?)}))

(defn- single-definition [definition]
  (cond
    (nil? definition) (fail! :semantic-definition-missing {})
    (map? definition) definition
    (sequential? definition) (case (count definition)
                               0 (fail! :semantic-definition-missing {})
                               1 (first definition)
                               (fail! :semantic-definition-ambiguous
                                      {:definition-count (count definition)}))
    :else (fail! :semantic-evidence-incomplete {:definition definition})))

(defn- subject-anchor [ctx cache subject]
  (let [anchors
        (->> (source-anchor/candidate-relative-files subject)
             (filter #(existing-file? ctx %))
             (keep (fn [file]
                     (let [observation (observe! ctx cache file)
                           built (source-anchor/build-source-anchor
                                  subject file (:content observation))]
                       (when (:ok built)
                         (assoc (:source-anchor built) :observation observation)))))
             vec)]
    (case (count anchors)
      0 (fail! :semantic-definition-missing {:subject subject})
      1 (first anchors)
      (fail! :semantic-definition-ambiguous
             {:subject subject :files (mapv :file anchors)}))))

(defn- lsp-client! [ctx]
  (let [semantic-client (or (:lsp-client ctx) (client/client-for (:root ctx)))]
    (when-not semantic-client
      (fail! :semantic-unavailable {}))
    (when (str/blank? (str (:session-id semantic-client)))
      (fail! :semantic-evidence-incomplete {:missing :lsp-session}))
    semantic-client))

(defn- synchronize! [ctx semantic-client path source]
  (let [effect (or (:lsp-sync ctx) client/synchronize!)]
    (effect semantic-client path source)))

(defn- semantic-subject-sites [ctx cache semantic-client subject]
  (let [{anchor-selection :selection_range :as anchor} (subject-anchor ctx cache subject)
        file (:file anchor)
        path (get-in anchor [:observation :metadata :path])
        source (get-in anchor [:observation :content])
        session (:session-id semantic-client)
        _ (synchronize! ctx semantic-client path source)
        position (get-in anchor-selection [:start])
        definition (single-definition
                    (client/definition semantic-client path (:line position) (:character position)))
        references (client/references semantic-client path (:line position) (:character position))]
    (when-not (sequential? references)
      (fail! :semantic-references-missing {:subject subject}))
    (let [definition-file (tool/canonical-file ctx (location-path definition))
          definition-start (get-in definition [:range :start])]
      (when-not (and (= file definition-file)
                     (= position (select-keys definition-start [:line :character])))
        (fail! :semantic-owner-drift
               {:subject subject :expected-file file :actual-file definition-file
                :expected-position position :actual-position definition-start})))
    (into [(location->site ctx cache definition
                           {:role :definition :subject subject
                            :authority :language-server+exact-source
                            :semantic-complete? true} session)]
          (map #(location->site ctx cache %
                                {:role :reference :subject subject
                                 :authority :language-server+exact-source
                                 :semantic-complete? true} session)
               references))))

(defn- project-source-files [ctx]
  (vec (distinct (mapcat #(files/glob-project (:root ctx) %)
                         (map #(str "**/*" %)
                              (:code (lexicon/wordlist :clj-surgeon-extensions)))))))

(defn- quoted-sites [ctx cache subjects session]
  (let [observations (mapv #(observe! ctx cache %) (project-source-files ctx))
        sources (into {} (map (fn [[file observation]] [file (:content observation)])) @cache)
        file-paths (into {} (map (fn [[file observation]]
                                   [file (get-in observation [:metadata :path])])) @cache)
        proof (quoted/scan-sources sources subjects
                                   {:file-paths file-paths
                                    :max-candidate-files
                                    (:max-candidate-files (gates/threshold :clj-surgeon))})]
    (when-not (:ok proof)
      (fail! (:error-type proof) (dissoc proof :ok :error-type)))
    (mapv #(location->site ctx cache %
                           {:role :reference :subject (:subject %)
                            :authority :structural-var-quote+exact-source
                            :semantic-complete? false} session)
          (:locations proof))))

(defn- merge-site [left right]
  (let [semantic? (or (:semantic-complete? left) (:semantic-complete? right))]
    (assoc left
           :role (if (or (= :definition (:role left)) (= :definition (:role right)))
                   :definition :reference)
           :subjects (set/union (:subjects left) (:subjects right))
           :authority (if semantic? :language-server+exact-source (:authority left))
           :semantic-complete? semantic?)))

(defn- ordered-sites [sites]
  (->> sites
       (reduce (fn [by-owner site]
                 (let [key [(:file site) (:line site) (:end-line site) (:form site)]]
                   (if-let [existing (get by-owner key)]
                     (assoc by-owner key (merge-site existing site))
                     (assoc by-owner key site))))
               (array-map))
       vals
       (sort-by (juxt #(if (= :definition (:role %)) 0 1) :file :line :form))
       (map-indexed (fn [index site]
                      (assoc site :id (str "change/s" (format "%02d" (inc index))))))
       vec))

(defn- public-site [site]
  (-> site
      (select-keys [:id :file :role :form :line :end-line :authority :semantic-complete?])
      (assoc :subjects (vec (sort (:subjects site))))))

(defn- retain-basis!
  [ctx {:keys [observations sites surface-sites subjects intent scope
               lsp-session caller-proof? references-claimed?]}]
  (let [policy (gates/threshold :clj-surgeon)
        surface-files (vec (distinct (map :file surface-sites)))]
    (when (empty? sites)
      (fail! :semantic-sites-not-addressable {}))
    (when (> (count surface-sites) (:max-matches policy))
      (fail! :semantic-site-limit {:actual (count surface-sites) :limit (:max-matches policy)}))
    (when (> (count surface-files) (:max-files policy))
      (fail! :file-limit {:actual (count surface-files) :limit (:max-files policy)}))
    (let [id (str (random-uuid))
          created (now-ms ctx)
          retained (select-keys @observations surface-files)
          basis {:id id :root (canonical-root ctx) :created-ms created
                 :expires-at-ms (+ created (:basis-ttl-ms policy))
                 :subjects subjects :intent intent :scope scope
                 :lsp-session lsp-session :caller-proof? caller-proof?
                 :references-claimed? references-claimed?
                 :surface-sites surface-sites :sites sites
                 :observations retained
                 :sources (into {} (map (fn [[file observation]] [file (:content observation)]))
                                retained)}
          public-surface (mapv public-site surface-sites)
          public-decisions (mapv #(assoc (public-site %) :source (:before %)) sites)
          response {:ok true :basis id :basis-resource (str "execution:" id)
                    :lsp-session lsp-session :subjects subjects :intent intent :scope scope
                    :caller-proof? caller-proof? :references-claimed? references-claimed?
                    :surface public-surface :decision-sites public-decisions
                    :next-call {:basis id :decisions (mapv (fn [site] {:site (:id site) :replace nil}) sites)}
                    :source-unchanged true}
          rendered (pr-str response)]
      (when (> (count rendered) (:max-result-chars policy))
        (fail! :result-limit {:actual (count rendered) :limit (:max-result-chars policy)}))
      (let [execution-id (executions/start!
                          (:conn ctx)
                          {:id id :run-id (:run-id ctx)
                           :branch-id (get-in ctx [:branch :id])
                           :kind basis-kind
                           :specification {:surgeon-basis? true :root (:root basis)
                                           :expires-at-ms (:expires-at-ms basis)
                                           :basis-edn (pr-str basis)}
                           :recovery :safe-restart})]
        (when-not (= id execution-id)
          (fail! :semantic-basis-retention-failed {}))
        response))))

(defn- requested-subjects [{:keys [subject subjects]}]
  (cond
    (and (string? subject) (nil? subjects)) [subject]
    (and (nil? subject) (vector? subjects) (seq subjects)) subjects
    :else nil))

(defn- prepare-exact [ctx {:keys [file form intent scope]}]
  (when-not (and (string? file) (string? form) (seq form))
    (fail! :invalid-exact-source-request {}))
  (let [cache (atom {})
        observation (observe! ctx cache file)
        canonical (:identity observation)
        subject (str (:ns (outline/outline-source canonical (:content observation))) "/" form)
        built (source-anchor/build-source-anchor subject canonical (:content observation))]
    (when-not (:ok built)
      (fail! (:error-type built) (dissoc built :ok :error-type)))
    (let [anchor (:source-anchor built)
          site {:file canonical :role :definition :form form
                :line (inc (get-in anchor [:range :start :line]))
                :end-line (inc (get-in anchor [:range :end :line]))
                :before (:source (first (filter #(= form (str (:name %)))
                                                (outline/top-level-form-records
                                                 canonical (:content observation)))))
                :source-revision (:revision observation) :subjects #{}
                :authority :exact-source :semantic-complete? false}
          sites (ordered-sites [site])]
      (retain-basis! ctx {:observations cache :sites sites :surface-sites sites
                          :subjects [] :intent intent :scope (or scope "definition")
                          :caller-proof? false :references-claimed? false}))))

(defn- prepare-semantic [ctx {:keys [intent scope] :as request}]
  (let [subjects (requested-subjects request)]
    (when-not (and subjects
                   (every? #(and (string? %) (re-matches #"[^/]+/[^/]+" %)) subjects)
                   (= (count subjects) (count (distinct subjects))))
      (fail! :invalid-change-subject {:subjects subjects}))
    (let [cache (atom {})
          semantic-client (lsp-client! ctx)
          session (:session-id semantic-client)
          semantic-sites (vec (mapcat #(semantic-subject-sites ctx cache semantic-client %)
                                      subjects))
          surface-sites (ordered-sites (into semantic-sites
                                             (quoted-sites ctx cache subjects session)))
          scope (or scope "surface")
          sites (if (= "definition" scope)
                  (filterv #(= :definition (:role %)) surface-sites)
                  surface-sites)]
      (retain-basis! ctx {:observations cache :sites sites :surface-sites surface-sites
                          :subjects subjects :intent intent :scope scope
                          :lsp-session session :caller-proof? true
                          :references-claimed? true}))))

(defn prepare
  "Prepare and durably retain exact semantic decisions for this run and branch."
  [ctx {:keys [subject subjects file form intent scope] :as request}]
  (try
    (when-not (map? request)
      (fail! :invalid-semantic-request {}))
    (when-not (and (string? intent) (not (str/blank? intent)))
      (fail! :invalid-change-intent {}))
    (when-not (contains? #{nil "definition" "surface"} scope)
      (fail! :invalid-change-scope {:scope scope}))
    (let [exact? (or file form)
          semantic? (or subject subjects)]
      (when (or (= exact? semantic?)
                (and exact? (or subject subjects)))
        (fail! :invalid-semantic-route {}))
      (if exact?
        (prepare-exact ctx request)
        (prepare-semantic ctx request)))
    (catch clojure.lang.ExceptionInfo error
      (if-let [kind (:semantic/error (ex-data error))]
        (refusal kind (dissoc (ex-data error) :semantic/error))
        (throw error)))))

(defn- load-basis [ctx id]
  (let [execution (executions/execution (:conn ctx) (str id))]
    (when-not (and execution
                   (= basis-kind (:kind execution))
                   (= :running (:status execution))
                   (true? (get-in execution [:specification :surgeon-basis?])))
      (fail! :unknown-or-expired-basis {:basis id}))
    (when-not (and (= (:run-id ctx) (:run_id execution))
                   (= (get-in ctx [:branch :id]) (:branch_id execution)))
      (fail! :basis-owner-mismatch {:basis id}))
    (let [basis (try (edn/read-string (get-in execution [:specification :basis-edn]))
                     (catch Exception _ nil))]
      (when-not (and (map? basis) (= (str id) (:id basis)))
        (fail! :unknown-or-expired-basis {:basis id}))
      (when-not (= (canonical-root ctx) (:root basis))
        (fail! :basis-workspace-mismatch {:basis id}))
      (when (< (:expires-at-ms basis) (now-ms ctx))
        (fail! :unknown-or-expired-basis {:basis id}))
      basis)))

(defn- valid-decision? [{:keys [keep replace delete] :as decision}]
  (and (map? decision)
       (every? #{:site :keep :replace :delete} (keys decision))
       (string? (:site decision))
       (= 1 (count (filter true? [(true? keep)
                                  (and (string? replace) (not (str/blank? replace)))
                                  (true? delete)])))))

(defn- named-owner-replacement? [sites-by-id {:keys [site replace]}]
  (or (nil? replace)
      (try
        (let [records (outline/top-level-form-records
                       (:file (get sites-by-id site)) replace)]
          (and (= 1 (count records)) (:name (first records))))
        (catch Exception _ false))))

(defn- validate-decisions [basis decisions]
  (let [expected (set (map :id (:sites basis)))
        actual (when (vector? decisions) (set (map :site decisions)))
        sites-by-id (into {} (map (juxt :id identity) (:sites basis)))]
    (cond
      (or (not (vector? decisions))
          (not= (count decisions) (count actual))
          (not= expected actual))
      (fail! :basis-coverage-mismatch
             {:expected-sites (vec (sort expected)) :actual-sites (vec (sort actual))})

      (not-every? valid-decision? decisions)
      (fail! :invalid-basis-decision {})

      (not-every? #(named-owner-replacement? sites-by-id %) decisions)
      (fail! :invalid-basis-decision {})

      (some (fn [{:keys [site replace]}]
              (and (string? replace) (= replace (:before (get sites-by-id site)))))
            decisions)
      (fail! :unchanged-basis-decision {})

      (and (some :delete decisions) (not (:caller-proof? basis)))
      (fail! :basis-caller-proof-required
             {:sites (mapv :site (filter :delete decisions))})

      :else decisions)))

(defn- decision-change [sites-by-id {:keys [site keep replace delete]}]
  (when-not keep
    (let [{:keys [file form before]} (get sites-by-id site)]
      (cond->
        {:id (keyword site) :in [file] :forms [(symbol form)]
         :expect {:matches 1}}
        replace (assoc :find before :do [:replace replace])
        delete (assoc :do [:delete true])))))

(defn compile-decisions
  "Compile decisions only against the observations retained by a live basis."
  [ctx basis-id decisions]
  (try
    (let [basis (load-basis ctx basis-id)
          decisions (validate-decisions basis decisions)
          sites-by-id (into {} (map (juxt :id identity) (:sites basis)))
          changes (vec (keep #(decision-change sites-by-id %) decisions))]
      (when (empty? changes)
        (fail! :empty-basis-change {}))
      (let [spec {:changes changes
                  :expect {:changes (count changes) :edits (count changes)
                           :files (count (distinct (mapcat :in changes)))}}
            plan (intent/compile-changes (:sources basis) spec)]
        {:ok true :basis basis :observations (:observations basis)
         :changes changes :plan plan}))
    (catch clojure.lang.ExceptionInfo error
      (if-let [kind (:semantic/error (ex-data error))]
        (refusal kind (dissoc (ex-data error) :semantic/error))
        (throw error)))))

(defn complete-basis! [ctx basis-id artifacts]
  (executions/complete! (:conn ctx) basis-id {:status :complete :artifacts artifacts}))
