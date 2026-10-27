;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource.shell-log
  "Owner-scoped shell capture resources with bounded byte access."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.resource :as resource]
            [samizdat.store.captures :as captures]))

(defn- fail [kind uri]
  (throw (ex-info (name kind) {:resource/error kind :uri uri})))

(defn- parse-uri [uri]
  (let [[id query] (str/split uri #"\?" 2)
        params (into {} (for [part (str/split (or query "") #"&")
                              :let [[k v] (str/split part #"=" 2)]]
                          [k v]))
        stream (get params "stream" "stdout")]
    (when-not (#{"stdout" "stderr"} stream)
      (fail :malformed-uri uri))
    {:id (str/replace-first id "shell-log:" "") :stream (keyword stream)}))

(defn- stream-info [row stream]
  {:path (get row (if (= :stdout stream) :stdout_read_path :stderr_read_path))
   :observed (get row (if (= :stdout stream) :stdout_observed :stderr_observed))
   :retained (get row (if (= :stdout stream) :stdout_retained :stderr_retained))})

(defn- status [row]
  (if (= "running" (:status row)) :incomplete (keyword (:status row))))

(defn- observe [conn {:keys [uri] :as resource}]
  (let [{:keys [id stream]} (parse-uri uri)
        row (or (captures/capture conn id) (fail :missing-resource uri))]
    (when (= "pruned" (:status row))
      (fail :pruned-resource uri))
    (let [{:keys [path observed retained] :as info} (stream-info row stream)]
      (when (nil? path)
        (fail :redaction-required uri))
      (when (and (not= "running" (:status row))
                 (not (.isFile (io/file path))))
        (fail :missing-resource uri))
      (assoc resource
             :revision (:updated_at row)
             :path path
             :capture row
             :stream stream
             :observed observed
             :retained retained
             :read-retained (if (.isFile (io/file path)) (.length (io/file path)) 0)
             :metadata {:capture (assoc info :run_id (:run_id row)
                                        :branch_id (:branch_id row)
                                        :status (:status row))}))))

(defn- read-bytes [path offset amount]
  (with-open [input (io/input-stream path)]
    (.skip input (long offset))
    (.readNBytes input (long amount))))

(defn- byte-matches [data pattern from]
  (let [limit (- (alength data) (alength pattern))]
    (loop [offset from]
      (when (<= offset limit)
        (if (loop [index 0]
              (or (= index (alength pattern))
                  (and (= (aget data (+ offset index)) (aget pattern index))
                       (recur (inc index)))))
          offset
          (recur (inc offset)))))))

(defn- search [observation query limit scan-cap]
  (let [data (read-bytes (:path observation) 0
                         (min (:read-retained observation) scan-cap))
        pattern (.getBytes (str query) "UTF-8")]
    (if (zero? (alength pattern))
      []
      (loop [from 0 results []]
        (let [offset (when (< (count results) limit)
                       (byte-matches data pattern from))]
          (if (nil? offset)
            results
            (let [end (+ offset (alength pattern))]
              (recur end
                     (conj results {:offset offset
                                    :excerpt (str query)
                                    :next-offset end})))))))))

(defn- read-window [observation {:keys [offset limit query]} options]
  (let [base {:status (status (:capture observation))
              :complete? (and (not (#{"running" "error" "capture-error"}
                                   (get-in observation [:capture :status])))
                              (= (:observed observation) (:retained observation)))
              :observed-bytes (:observed observation)
              :retained-bytes (:retained observation)}]
    (cond
      (= "running" (get-in observation [:capture :status]))
      (assoc base :content "")

      query
      (assoc base :results
             (search observation query
                     (min (long (or limit (:search-results options)))
                          (long (:search-results options)))
                     (:search-scan-bytes options))
             :scan-truncated (> (:read-retained observation) (:search-scan-bytes options)))
      :else
      (let [start (max 0 (min (long (or offset 0)) (:read-retained observation)))
            amount (max 0 (min (long (or limit (:read-chars options)))
                               (- (:read-retained observation) start)))
            bytes (read-bytes (:path observation) start amount)
            end (+ start (alength bytes))]
        (assoc base :content (String. bytes "UTF-8")
               :next-offset (when (< end (:read-retained observation)) end))))))

(defrecord ShellLogResolver [conn options]
  resource/Resolver
  (resolve-resource [_ uri]
    (assoc (parse-uri uri) :uri uri :resolver :shell-log :identity uri))
  (observe-resource [_ resource _ctx]
    (observe conn resource))
  (read-resource [_ observation _ctx opts]
    (read-window observation opts options))
  (resource-operations [_ resource _ctx]
    [{:operation :read :uri (:uri resource)}
     {:operation :search :uri (:uri resource)}]))

(defn resolver [conn options]
  (->ShellLogResolver conn options))
