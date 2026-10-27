;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.structural.backend
  "Read-only ast-grep process adapter."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.approval :as approval]
            [samizdat.engine.proc :as proc]
            [samizdat.security.secrets :as secrets]))

(defn- fail [kind data]
  (throw (ex-info (name kind) (assoc data :structural/error kind))))

(defn- run [ctx args input]
  (let [{:keys [program timeout-ms max-output-bytes]} (gates/threshold :structural)
        _ (when (str/blank? (str program))
            (fail :unavailable {}))
        decision (approval/resolve-ask ctx {:effect :ask :input (str program " run")
                                            :reason "structural backend"})
        id (str (random-uuid))
        stdout (str (:root ctx) "/.samizdat/structural-" id ".out")
        stderr (str (:root ctx) "/.samizdat/structural-" id ".err")]
    (when-not (= :allow (:effect decision))
      (fail :authorization {:effect (:effect decision)}))
    (try
      (let [result (apply proc/run {:timeout-ms timeout-ms :input input
                                    :env (secrets/scrubbed-process-env)
                                    :dir (:root ctx)
                                    :capture {:stdout stdout :stderr stderr
                                              :retain-bytes max-output-bytes :tail-bytes 0}}
                          program args)]
        (when (or (:timeout result) (not= 0 (:exit result)))
          (fail :backend {:timeout (:timeout result) :exit (:exit result)
                          :error (:err result)}))
        (when (some (fn [stream]
                      (> (get-in result [:capture stream :observed-bytes] 0)
                         (get-in result [:capture stream :retained-bytes] 0)))
                    [:stdout :stderr])
          (fail :result-limit {:bytes max-output-bytes}))
        (:out result))
      (finally
        (doseq [path [stdout stderr]]
          (try (io/delete-file path true) (catch Throwable _ nil)))))))

(defn- character-offsets [content]
  (loop [code-points (seq (vec (.toArray (.codePoints content)))) index 0 offsets [0]]
    (if-let [code-point (first code-points)]
      (let [bytes (count (.getBytes (String. (Character/toChars code-point)) "UTF-8"))]
        (recur (rest code-points) (inc index)
               (into offsets (repeat bytes (inc index)))))
      (conj offsets index))))

(defn query [ctx {:keys [content language pattern]}]
  (let [output (run ctx ["run" "--pattern" pattern "--lang" language
                         "--json=stream" "--stdin"] content)
        offsets (character-offsets content)]
    (mapv (fn [line]
            (let [match (json/read-str line :key-fn keyword)
                  start (get-in match [:range :byteOffset :start])
                  end (get-in match [:range :byteOffset :end])]
              {:match (str (get match :ruleId (get match :id "match")))
               :start (get offsets start) :end (get offsets end)
               :start-byte start :end-byte end
               :text (:text match)}))
          (remove str/blank? (str/split-lines output)))))

(defn rewrite [ctx {:keys [content language pattern replacement]}]
  (run ctx ["run" "--pattern" pattern "--rewrite" replacement
            "--lang" language "--stdin"] content))
