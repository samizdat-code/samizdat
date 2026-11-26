;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.tune
  "The `tune` tool: the bookkeeping of an optimisation loop over the project,
  after Agentic Algorithm Engineering. The model forms the hypothesis, makes
  and commits the change and runs the benchmark; this records what it
  measured, judges it against the best kept result, logs it, and exports a
  kept change as the next numbered patch.

  Here so the keep-or-discard decision is a measurement and not the model's
  reading of two numbers: the summary is the geometric mean, the gain is
  relative in the metric's own direction, and the bar is gates.edn :tune.

  State lives under the project's `.samizdat/tune/` (gates.edn :tune :dir):
  session.edn, results.tsv and patches/. That directory is added to
  .git/info/exclude on start, so the `git reset --hard` back to the best
  commit after a discard never touches the record of the experiment that
  caused it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.engine.proc :as proc]
            [samizdat.prompt :as prompt]
            [samizdat.security.secrets :as secrets]
            [samizdat.tune :as tune]))

(defn- msg [ctx] (prompt/render "tune-tool" ctx))

(defn- policy [] (gates/threshold :tune))

(defn- git
  "git in `root`, stdout or nil on any failure."
  [root & args]
  (let [r (apply proc/run {:timeout-ms 30000 :env (secrets/scrubbed-process-env)}
                 "git" "-C" (str root) args)]
    (when (and (not (:timeout r)) (zero? (or (:exit r) 1)))
      (str (:out r)))))

(defn- head [root] (some-> (git root "rev-parse" "--short" "HEAD") str/trim not-empty))

(defn- dir-of [root] (str root "/" (or (:dir (policy)) ".samizdat/tune")))

(defn- mkdirs! [path] (.mkdirs (java.io.File. (str path))))

(defn- read-session [root]
  (let [f (java.io.File. (str (dir-of root) "/session.edn"))]
    (when (.isFile f) (try (edn/read-string (slurp f)) (catch Throwable _ nil)))))

(defn- write-session! [root s]
  (mkdirs! (dir-of root))
  (spit (str (dir-of root) "/session.edn") (pr-str s)))

(defn- exclude!
  "Keep the tune directory out of git's reach. Idempotent; silent when the
  project is not a git work tree."
  [root]
  (when-let [gd (some-> (git root "rev-parse" "--git-dir") str/trim not-empty)]
    (let [gd (if (str/starts-with? gd "/") gd (str root "/" gd))
          f (java.io.File. (str gd "/info/exclude"))
          line (str (or (:dir (policy)) ".samizdat/tune") "/")
          now (if (.isFile f) (slurp f) "")]
      (when-not (some #(= line (str/trim %)) (str/split-lines now))
        (mkdirs! (.getParent f))
        (spit f (str now (when-not (or (str/blank? now) (str/ends-with? now "\n")) "\n")
                     line "\n"))))))

(defn- append-row! [root row]
  (spit (str (dir-of root) "/results.tsv") (str (tune/tsv-row row) "\n") :append true))

(defn- export-patch!
  "HEAD as patches/<name>; the name, or nil when git could not produce it."
  [root n title]
  (let [nm (tune/patch-name n title)]
    (when-let [p (git root "format-patch" "-1" "HEAD" "--stdout")]
      (mkdirs! (str (dir-of root) "/patches"))
      (spit (str (dir-of root) "/patches/" nm) p)
      nm)))

(defn- arg [ctx k] (some-> (base/arg ctx k) str str/trim not-empty))

(defn- num6 [x] (when x (format "%.6g" (double x))))

(defn- pct [x] (when x (format "%+.1f%%" (* 100.0 x))))

(defn- start! [{:keys [branch] :as ctx} root]
  (let [metric (arg ctx :metric) command (arg ctx :command)
        assertions (arg ctx :assertions)
        direction (some-> (arg ctx :direction) str/lower-case keyword)]
    (cond
      (not (and metric command assertions direction))
      (base/malformed branch (str (base/missing ctx :metric :direction :command :assertions)
                                  "\n\n" (msg {:usage true})))

      (not (#{:lower :higher} direction))
      (base/malformed branch (msg {:bad-direction true}))

      (and (read-session root) (not= "true" (some-> (arg ctx :fresh) str/lower-case)))
      (base/refusal branch (msg {:exists true}))

      :else
      (let [s {:metric metric :direction direction :command command
               :assertions assertions :unit (arg ctx :unit) :budget (arg ctx :budget)
               :baseline-commit (head root) :at (str (java.time.Instant/now))
               :experiments [] :kept 0}]
        (write-session! root s)
        (spit (str (dir-of root) "/results.tsv") (str tune/tsv-header "\n"))
        (exclude! root)
        (base/ok branch (msg {:started true :metric metric :direction (name direction)
                              :command command :assertions assertions
                              :commit (:baseline-commit s)
                              :timeout-factor (:timeout-factor (policy))})
                 :progress? true)))))

(defn- record! [{:keys [branch] :as ctx} root s]
  (let [hypothesis (arg ctx :hypothesis)
        crash? (= "crash" (some-> (arg ctx :status) str/lower-case))
        values (tune/parse-values (arg ctx :values))
        metric (when (vector? values) (tune/geomean values))
        commit (head root)
        baseline? (nil? (:best s))
        simpler? (= "true" (some-> (arg ctx :simpler) str/lower-case))]
    (cond
      (not hypothesis)
      (base/malformed branch (str (base/missing ctx :hypothesis) "\n\n" (msg {:usage true})))

      (and (not crash?) (or (nil? values) (= :bad values) (nil? metric)))
      (base/malformed branch (msg {:bad-values true}))

      (and (not crash?) (not baseline?) (= commit (:best-commit s)))
      (base/refusal branch (msg {:uncommitted true :commit commit}))

      :else
      (let [{:keys [verdict gain reason]}
            (cond crash? {:verdict :crash}
                  baseline? {:verdict :baseline}
                  :else (tune/judge {:direction (:direction s) :best (:best s) :value metric
                                     :min-gain (:min-gain (policy)) :simpler? simpler?}))
            kept? (#{:keep :baseline} verdict)
            n (when (= :keep verdict) (inc (:kept s 0)))
            patch (when n (export-patch! root n hypothesis))
            exp {:exp (inc (count (:experiments s))) :commit commit :metric metric
                 :values values :status verdict :gain gain :hypothesis hypothesis
                 :prediction (arg ctx :prediction) :learned (arg ctx :learned)
                 :simpler? simpler? :patch patch}
            s' (cond-> (update s :experiments conj exp)
                 kept? (assoc :best metric :best-commit commit)
                 (= :baseline verdict) (assoc :baseline metric)
                 n (assoc :kept n))]
        (write-session! root s')
        (append-row! root {:commit commit :metric metric :status verdict :hypothesis hypothesis})
        (base/ok branch (msg {:recorded true :exp (:exp exp) :verdict (name verdict)
                              :metric (num6 metric) :unit (:unit s)
                              :best (num6 (:best s)) :gain (pct gain)
                              :simpler (= :simpler reason)
                              :patch patch
                              :keep (= :keep verdict) :baseline (= :baseline verdict)
                              :discard (= :discard verdict) :crash crash?
                              :reset (when-not kept? (:best-commit s))
                              :prediction (:prediction exp)
                              :min-gain (pct (:min-gain (policy)))})
                 :progress? true)))))

(defn- status [{:keys [branch]} s]
  (let [exps (:experiments s)
        by (frequencies (map :status exps))
        total (when (and (:baseline s) (:best s))
                (let [b (:baseline s) x (:best s)]
                  (if (= :higher (:direction s)) (/ x b) (/ b x))))]
    (base/ok branch (msg {:status true :metric (:metric s) :direction (name (:direction s))
                          :n (count exps) :kept (get by :keep 0) :discarded (get by :discard 0)
                          :crashed (get by :crash 0)
                          :baseline (num6 (:baseline s)) :best (num6 (:best s))
                          :best-commit (:best-commit s)
                          :speedup (when total (format "%.3fx" (double total)))
                          :recent (str/join "\n" (map (fn [e]
                                                        (str "  " (format "%03d" (:exp e)) " "
                                                             (name (:status e)) " "
                                                             (or (num6 (:metric e)) "-") " — "
                                                             (:hypothesis e)))
                                                      (take-last 5 exps)))}))))

(defmethod base/run-tool "tune" [{:keys [branch] :as ctx}]
  (let [root (or (:root ctx) ".")
        action (some-> (arg ctx :action) str/lower-case)
        s (read-session root)]
    (case action
      "start" (start! ctx root)
      ("record" "status")
      (if-not s
        (base/malformed branch (msg {:no-session true}))
        (if (= "record" action) (record! ctx root s) (status ctx s)))
      (base/malformed branch (msg {:usage true})))))
