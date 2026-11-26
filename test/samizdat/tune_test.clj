;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tune-test
  "The `tune` workflow's mechanism, after Agentic Algorithm Engineering: a
  benchmark's values summarized by their geometric mean, each experiment
  judged against the best kept one, a results log, and one numbered patch
  per kept change."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.tune]
            [samizdat.engine.proc :as proc]
            [samizdat.tune :as tune]))

(deftest the-summary-of-several-inputs-is-their-geometric-mean
  (is (< (Math/abs (- 4.0 (tune/geomean [2.0 8.0]))) 1e-9))
  (is (< (Math/abs (- 3.0 (tune/geomean [3.0]))) 1e-9))
  (testing "nothing to summarize, or a value a geometric mean cannot take"
    (is (nil? (tune/geomean [])))
    (is (nil? (tune/geomean [1.0 0.0])))
    (is (nil? (tune/geomean [1.0 -2.0])))))

(deftest a-change-is-kept-only-past-the-minimum-gain
  (let [j (fn [direction best value & [simpler?]]
            (tune/judge {:direction direction :best best :value value
                         :min-gain 0.01 :simpler? simpler?}))]
    (testing "lower is better"
      (is (= :keep (:verdict (j :lower 10.0 9.0))))
      (is (< (Math/abs (- 0.1 (:gain (j :lower 10.0 9.0)))) 1e-9))
      (is (= :discard (:verdict (j :lower 10.0 9.95))) "inside the minimum gain")
      (is (= :discard (:verdict (j :lower 10.0 11.0)))))
    (testing "higher is better"
      (is (= :keep (:verdict (j :higher 10.0 11.0))))
      (is (= :discard (:verdict (j :higher 10.0 9.0)))))
    (testing "AAE's simplicity criterion: an equal result with simpler code is kept"
      (is (= :keep (:verdict (j :lower 10.0 10.05 true))))
      (is (= :simpler (:reason (j :lower 10.0 10.05 true))))
      (is (= :discard (:verdict (j :lower 10.0 11.0 true))) "simpler does not buy a loss"))))

(deftest a-patch-name-is-its-number-and-a-slug
  (is (= "0003-bucket-queue-in-refinement.patch"
         (tune/patch-name 3 "Bucket queue in refinement!")))
  (is (= "0001-change.patch" (tune/patch-name 1 "   "))))

(deftest a-results-row-is-tab-separated-and-one-line
  (is (= "a1b2c3d\t2.181000\tkeep\treplacing the heap with a bucket queue"
         (tune/tsv-row {:commit "a1b2c3d" :metric 2.181 :status :keep
                        :hypothesis "replacing the heap\twith a bucket queue"})))
  (is (str/starts-with? (tune/tsv-row {:commit "x" :metric nil :status :crash
                                       :hypothesis "a\nb"})
                        "x\t0.000000\tcrash\ta b")))

;; --- the tool, over a real git repository -----------------------------------

(defn- git! [dir & args]
  (let [r (apply proc/run {:timeout-ms 15000} "git" "-C" dir args)]
    (when-not (zero? (:exit r)) (throw (ex-info "git failed" {:args args :r r})))
    (str/trim (str (:out r)))))

(defn- repo! []
  (let [dir (str (System/getProperty "java.io.tmpdir") "/tune-test-" (random-uuid))]
    (proc/run {:timeout-ms 15000} "mkdir" "-p" dir)
    (git! dir "init" "-q")
    (git! dir "config" "user.email" "t@example.com")
    (git! dir "config" "user.name" "t")
    (spit (str dir "/solve.c") "slow\n")
    (git! dir "add" ".")
    (git! dir "commit" "-qm" "baseline")
    dir))

(defn- tune! [dir args]
  (base/run-tool {:tool-name "tune" :root dir :branch {:id "B1"} :args args}))

(defn- commit! [dir body msg]
  (spit (str dir "/solve.c") body)
  (git! dir "commit" "-qam" msg))

(deftest the-tool-runs-the-cycle-and-keeps-only-what-beat-the-best
  (let [dir (repo!)]
    (try
      (testing "start records what is measured and the baseline commit"
        (let [r (tune! dir {"action" "start" "metric" "time" "direction" "lower"
                            "command" "./bench.sh" "assertions" "output is sorted"})]
          (is (not= :mechanics (:category r)) (str (:result r)))
          (is (str/includes? (str (:result r)) "baseline")))
        (is (str/includes? (slurp (str dir "/.git/info/exclude")) ".samizdat/tune/")
            "a reset back to the best never touches the log"))
      (testing "a record before start is refused elsewhere; here the first is the baseline"
        (let [r (tune! dir {"action" "record" "hypothesis" "baseline" "values" "4.0, 9.0"})]
          (is (str/includes? (str (:result r)) "6.0"))))
      (testing "a change that beats the best is kept and exported as patch 0001"
        (commit! dir "fast\n" "bucket queue")
        (let [r (tune! dir {"action" "record" "hypothesis" "bucket queue cuts time ~20%"
                            "prediction" "down 20%" "values" "3.0, 7.5"})]
          (is (str/includes? (str (:result r)) "keep"))
          (is (str/includes? (str (:result r)) "-20.9%")
              "the metric's own change: a time that fell reads as a fall")
          (is (str/includes? (str (:result r)) ".samizdat/tune/patches/0001-")
              "the patch is named where it actually is")
          (is (.exists (java.io.File. (str dir "/.samizdat/tune/patches/0001-bucket-queue-cuts-time-20.patch"))))))
      (testing "a change that does not is discarded, with the revert to run"
        (let [best (git! dir "rev-parse" "--short" "HEAD")]
          (commit! dir "worse\n" "unrolled")
          (let [r (str (:result (tune! dir {"action" "record" "hypothesis" "unrolling helps"
                                            "values" "5.0, 9.0"})))]
            (is (str/includes? r "discard"))
            (is (str/includes? r (str "git revert --no-edit " best "..HEAD"))
                "a reset --hard is an ask only a person answers; a revert loses nothing")
            (is (not (str/includes? r "reset --hard"))))
          (git! dir "revert" "--no-edit" (str best "..HEAD"))
          (is (= :mechanics (:category (tune! dir {"action" "record" "hypothesis" "again"
                                                   "values" "3.0, 7.5"})))
              "the revert alone is the best again, so there is nothing to measure")))
      (testing "a crash is logged and needs no values"
        (let [r (str (:result (tune! dir {"action" "record" "hypothesis" "doubling the table"
                                          "status" "crash"})))]
          (is (str/includes? r "crash"))))
      (testing "the results log has the header and one row per experiment"
        (let [rows (str/split-lines (slurp (str dir "/.samizdat/tune/results.tsv")))]
          (is (= "commit\tmetric_value\tstatus\thypothesis" (first rows)))
          (is (= ["baseline" "keep" "discard" "crash"]
                 (mapv #(nth (str/split % #"\t") 2) (rest rows))))))
      (testing "status reports the best against the baseline"
        (let [r (str (:result (tune! dir {"action" "status"})))]
          (is (str/includes? r "1 kept"))
          (is (str/includes? r "4 experiments"))))
      (finally (proc/run {:timeout-ms 15000} "rm" "-rf" dir)))))

(deftest a-kept-patch-carries-every-commit-since-the-best
  (let [dir (repo!)]
    (try
      (tune! dir {"action" "start" "metric" "time" "direction" "lower" "command" "c"
                  "assertions" "a"})
      (tune! dir {"action" "record" "hypothesis" "baseline" "values" "4.0"})
      (commit! dir "fast\n" "first half")
      (spit (str dir "/extra.c") "helper\n")
      (git! dir "add" "extra.c")
      (git! dir "commit" "-qm" "second half")
      (tune! dir {"action" "record" "hypothesis" "two step change" "values" "3.0"})
      (let [patch (slurp (str dir "/.samizdat/tune/patches/0001-two-step-change.patch"))]
        (is (str/includes? patch "+fast"))
        (is (str/includes? patch "+helper")
            "an experiment committed in two steps exports both"))
      (finally (proc/run {:timeout-ms 15000} "rm" "-rf" dir)))))

(deftest the-tool-refuses-what-it-cannot-measure
  (let [dir (repo!)]
    (try
      (is (= :mechanics (:category (tune! dir {"action" "record" "hypothesis" "h" "values" "1"})))
          "no session started")
      (tune! dir {"action" "start" "metric" "time" "direction" "lower" "command" "c"
                  "assertions" "a"})
      (is (= :mechanics (:category (tune! dir {"action" "record" "hypothesis" "h"
                                               "values" "fast"}))))
      (is (= :mechanics (:category (tune! dir {"action" "record" "values" "1.0"})))
          "a record with no hypothesis teaches nothing")
      (is (= :mechanics (:category (tune! dir {"action" "start" "metric" "time"
                                               "direction" "sideways" "command" "c"
                                               "assertions" "a"}))))
      (finally (proc/run {:timeout-ms 15000} "rm" "-rf" dir)))))
