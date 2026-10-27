;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.surgeon-inspect
  "Bounded structural Clojure reads over frozen workspace observations."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.prompt :as prompt]
            [samizdat.surgeon.inspect :as inspect]
            [samizdat.surgeon.tool :as tool]))

(def ^:private operations #{"outline" "forms" "match" "xray"})

(defn- msg [vars]
  (prompt/render "surgeon-inspect-tool" vars))

(defn- refuse! [kind data]
  (throw (ex-info (name kind) (merge {:surgeon/error kind} data))))

(defn- operation [request]
  (let [value (:operation request)]
    (if (keyword? value) (name value) (str value))))

(defn- clojure-file? [file]
  (and (string? file)
       (not (str/blank? file))
       (not (str/starts-with? file "/"))
       (some? (re-matches #"(?i).+\.clj[cs]?" file))))

(defn- count-arg? [value]
  (and (integer? value) (not (neg? value))))

(defn- requested-form-count [request]
  (cond
    (vector? (:forms request)) (count (:forms request))
    (contains? request :form) 1
    :else 0))

(defn- validate-batch [ctx policy]
  (let [requests (base/listed (base/arg ctx :requests))
        expect (base/arg ctx :expect)]
    (when-not (vector? requests)
      (refuse! :invalid-requests {}))
    (when-not (and (map? expect)
                   (count-arg? (:requests expect))
                   (count-arg? (:files expect)))
      (refuse! :invalid-expect {}))
    (when (> (count requests) (:max-requests policy))
      (refuse! :request-limit {:actual (count requests)
                               :limit (:max-requests policy)}))
    (doseq [[index request] (map-indexed vector requests)]
      (when-not (map? request)
        (refuse! :invalid-request {:index index}))
      (when-not (contains? operations (operation request))
        (refuse! :unknown-operation {:index index}))
      (when-not (clojure-file? (:file request))
        (refuse! :invalid-file {:index index})))
    (let [requests (mapv (fn [request]
                           (if-let [file (tool/canonical-file ctx (:file request))]
                             (assoc request :file file)
                             (refuse! :outside-root {:file (:file request)})))
                         requests)
          files (vec (distinct (map :file requests)))]
      (when (> (count files) (:max-files policy))
        (refuse! :file-limit {:actual (count files) :limit (:max-files policy)}))
      (when (or (not= (count requests) (:requests expect))
                (not= (count files) (:files expect)))
        (refuse! :expectation-mismatch
                 {:actual (pr-str {:requests (count requests) :files (count files)})
                  :limit (pr-str expect)}))
      (let [forms-by-file
            (reduce (fn [counts request]
                      (if (= "forms" (operation request))
                        (update counts (:file request) (fnil + 0)
                                (requested-form-count request))
                        counts))
                    {} requests)]
        (doseq [[file actual] forms-by-file]
          (when (> actual (:max-forms policy))
            (refuse! :form-limit {:file file :actual actual
                                  :limit (:max-forms policy)}))))
      {:requests requests :files files})))

(defn- checked-observations [ctx requests policy]
  (let [observations (tool/observe-files ctx requests)]
    (doseq [[file observation] observations]
      (when-not (= :workspace (:resolver observation))
        (refuse! :unsupported-resource {:file file}))
      (when (nil? (:content observation))
        (refuse! :missing-resource {:file file}))
      (when (> (count (:content observation)) (:max-source-chars policy))
        (refuse! :source-limit {:file file :actual (count (:content observation))
                                :limit (:max-source-chars policy)})))
    observations))

(defn- pure-result [request source file policy]
  (try
    (case (operation request)
      "outline" (inspect/outline-source source file)
      "forms" (inspect/forms-source source file
                                    (dissoc request :operation :file))
      "match" (inspect/match-source source (select-keys request [:inside :match]))
      "xray" (inspect/xray-source
               source
               (-> (select-keys request [:expression :evidence])
                   (assoc :file file
                          :max-source-chars (:max-source-chars policy)
                          :max-query-steps (:max-forms policy)
                          :max-matches (:max-matches policy)
                          :max-result-chars (:max-result-chars policy)))))
    (catch Exception _
      (refuse! :request-failed {:file file :detail "invalid-source"}))))

(defn- inspect-request [observations policy request]
  (let [file (:file request)
        observation (get observations file)
        result (pure-result request (:content observation) file policy)]
    (when-let [error-type (:error-type result)]
      (refuse! :request-failed {:file file :detail (name error-type)}))
    (when (> (or (:match-count result) 0) (:max-matches policy))
      (refuse! :match-limit {:file file :actual (:match-count result)
                             :limit (:max-matches policy)}))
    (assoc request :revision (:revision observation) :result result)))

(defn- run [ctx]
  (let [policy (gates/threshold :clj-surgeon)
        {:keys [requests]} (validate-batch ctx policy)
        observations (checked-observations ctx requests policy)
        results (mapv #(inspect-request observations policy %) requests)
        value {:files (into {} (map (fn [[file observation]]
                                      [file (:revision observation)]))
                            observations)
               :requests results}
        rendered (pr-str value)]
    (when (> (count rendered) (:max-result-chars policy))
      (refuse! :result-limit {:actual (count rendered)
                              :limit (:max-result-chars policy)}))
    (base/ok (:branch ctx) (msg {:success true :edn rendered}) :resource value)))

(defmethod base/run-tool "inspect_clojure" [{:keys [branch] :as ctx}]
  (try
    (run ctx)
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)
            kind (or (:surgeon/error data) (:resource/error data))]
        (if kind
          (base/malformed
           branch
           (msg (merge {:refused true :kind (name kind)}
                       (select-keys data [:index :file :detail :actual :limit]))))
          (throw e))))
    (catch Throwable _
      (base/fail branch (msg {:refused true :kind "internal-error"})))))
