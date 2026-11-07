;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

;; What a change made worse, for CI: the same measurement and policy as the
;; :metrics-worse gate, against a base commit. Prints a GitHub annotation per finding, and
;; exits 1 on any if asked to with --fail. The project's existing complexity does not count.
;;
;;   jolt ci/metrics.clj <base-ref> [--fail]

(require '[samizdat.agent.code-metrics :as code-metrics]
         '[samizdat.agent.gates :as gates]
         '[clojure.string :as str])

(let [[base & flags] *command-line-args*
      _ (when-not base
          (println "usage: jolt ci/metrics.clj <base-ref> [--fail]")
          (System/exit 2))
      policy (gates/threshold :code-metrics)
      found (code-metrics/of-tree "." base policy)]
  (doseq [{:keys [path name line violations]} found]
    (println (str "::warning file=" path ",line=" line ",title=Harder to change::" name ": "
                  (str/join ", " (for [{:keys [rule value limit]} violations]
                                   (str (clojure.core/name rule) " " (long value) " (limit " limit ")"))))))
  (println (count found) "finding(s) made worse since" base)
  (flush)
  (when (and (seq found) (some #{"--fail"} flags))
    (System/exit 1)))
