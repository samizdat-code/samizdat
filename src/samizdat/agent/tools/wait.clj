;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.wait
  "The `wait` tool: let a branch wait for the world instead of polling it
  turn by turn (karamazov-05z0.21, after autolith's scheduled and event
  wakeups). A long build, a CI job, a service coming up: a branch that
  checks every turn spends a turn and its context on each look.

  wait {seconds} sleeps; wait {until, every?, seconds?} runs `until` — one of
  the run's OPERATOR-configured commands (config.edn :run :verify-cmd or an
  acceptance :check, the ones verify/run-configured allows) — every `every`
  seconds until it goes green or `seconds` pass. Only those commands: a wait
  is not a way round the shell's approval. Bounded by gates.edn :wakeups,
  and a cancelled turn wakes at once (the sleep is cancel/sleep!)."
  (:require [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.verify :as verify]
            [samizdat.cancel :as cancel]
            [samizdat.prompt :as prompt]))

(defn- msg [ctx] (prompt/render "wait-tool" ctx))

(defn- seconds-arg [ctx k]
  (some-> (base/arg ctx k) str str/trim not-empty parse-double))

(defn wait-until
  "Poll `check` — (fn [] -> {:green? :output :refused?}) — every `every-ms`
  until it is green, refused, or `max-ms` have passed, sleeping with
  `sleep!`. {:green? :refused? :waited-ms :checks :output}."
  [check every-ms max-ms sleep!]
  (loop [waited 0 n 1]
    (let [r (check)]
      (if (or (:green? r) (:refused? r) (>= (+ waited every-ms) max-ms))
        {:green? (boolean (:green? r)) :refused? (boolean (:refused? r))
         :waited-ms waited :checks n :output (:output r)}
        (do (sleep! every-ms) (recur (+ waited every-ms) (inc n)))))))

(defmethod base/run-tool "wait" [{:keys [branch run-id] :as ctx}]
  (let [{:keys [max-wait-ms poll-floor-ms]} (gates/threshold :wakeups)
        until (some-> (base/arg ctx :until) str str/trim not-empty)
        secs (seconds-arg ctx :seconds)
        every (seconds-arg ctx :every)
        max-ms (long (min max-wait-ms (* 1000 (or secs (/ max-wait-ms 1000.0)))))
        every-ms (long (max poll-floor-ms (* 1000 (or every (/ poll-floor-ms 1000.0)))))]
    (cond
      (and (nil? until) (nil? secs))
      (base/malformed branch (msg {:usage true}))

      (nil? until)
      (do (cancel/sleep! max-ms)
          (base/ok branch (msg {:slept true :seconds (quot max-ms 1000)})))

      :else
      (let [r (wait-until #(verify/run-configured run-id until nil) every-ms max-ms cancel/sleep!)]
        (cond
          (:refused? r) (base/refusal branch (str (:output r)))
          (:green? r) (base/ok branch (msg {:green true :until until :checks (:checks r)
                                            :seconds (quot (:waited-ms r) 1000)})
                               :progress? true)
          :else (base/ok branch (msg {:timed-out true :until until :checks (:checks r)
                                      :seconds (quot max-ms 1000) :output (:output r)})))))))
