;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.jobs-test
  "Background jobs: `shell {background: true}` and the `jobs` tool. A process
  that has to outlive the call that started it — a server the work is tested
  against — runs as a job owned by the run, through the same policy gate as
  any shell command, and is killed when the run ends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs]
            [samizdat.agent.run-state :as run-state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.base :as base]
            [samizdat.engine.jobs :as jobs]
            [samizdat.store.db :as db]
            [samizdat.store.grants :as grants]
            [samizdat.store.runs :as runs]))

(def ^:private opts {:max-jobs 3 :buffer-lines 50 :max-line-chars 200})

(defn- start [run-id argv & {:as extra}]
  (jobs/start! (merge opts {:run-id run-id :branch-id "B1" :command (str/join " " argv)
                            :argv argv :dir "." :env {"PATH" (System/getenv "PATH")}}
                      extra)))

(defn- texts [r] (mapv :text (:lines r)))

(defn- settle
  "Wait until `pred` holds or `ms` pass."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 20) (recur))))))

(deftest a-job-outlives-the-call-and-is-listed
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "sleep 5"])]
        (is job)
        (Thread/sleep 300)
        (is (= :running (:status (jobs/job run job))))
        (is (= [job] (mapv :id (jobs/jobs run))))
        (is (empty? (jobs/jobs (str run "-other"))) "another run does not see it")
        (is (nil? (jobs/job (str run "-other") job))))
      (finally (jobs/forget-run! run)))))

(deftest output-returns-only-the-lines-since-the-last-read
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "echo one; echo two >&2; sleep 0.6; echo three; sleep 5"])]
        (is (settle #(= 2 (count (:lines (jobs/peek-lines run job)))) 2000))
        (let [first-read (jobs/read! run job)]
          ;; Two pipes: their lines interleave in whatever order they land.
          (is (= #{{:text "one" :stream :stdout} {:text "two" :stream :stderr}}
                 (set (:lines first-read)))))
        (is (empty? (:lines (jobs/read! run job))) "nothing new yet")
        (is (settle #(= 3 (count (:lines (jobs/peek-lines run job)))) 3000))
        (is (= ["three"] (texts (jobs/read! run job)))))
      (finally (jobs/forget-run! run)))))

(deftest the-buffer-is-bounded-and-says-what-it-dropped
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "for i in $(seq 1 200); do echo line$i; done"])]
        (is (settle #(not= :running (:status (jobs/job run job))) 3000))
        (let [r (jobs/read! run job)]
          (is (= 50 (count (:lines r))))
          (is (= "line200" (:text (peek (:lines r)))))
          (is (= 150 (:dropped r)))))
      (finally (jobs/forget-run! run)))))

(deftest an-exit-is-recorded-with-its-code
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "echo boom; exit 3"])
            snap (jobs/await-start! run job 2000 20)]
        (is (= :failed (:status snap)))
        (is (= 3 (:exit snap)))
        (is (= ["boom"] (texts (jobs/read! run job)))))
      (finally (jobs/forget-run! run)))))

(deftest a-killed-job-stays-killed
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "sleep 30"])]
        (Thread/sleep 200)
        (is (= :killed (jobs/kill! run job)))
        (Thread/sleep 300)
        (is (= :killed (:status (jobs/job run job))) "the exit monitor did not overwrite it")
        (is (= :not-running (jobs/kill! run job)))
        (is (nil? (jobs/kill! run "job-nope")))
        (is (empty? (jobs/take-exits! run "B1")) "a kill is not news to the branch that made it"))
      (finally (jobs/forget-run! run)))))

(deftest the-cap-refuses-a-job-past-the-limit
  (let [run (str "jobs-" (random-uuid))]
    (try
      (dotimes [_ 3] (is (:job (start run ["bash" "-c" "sleep 5"]))))
      (is (= {:refused :cap :max 3} (start run ["bash" "-c" "sleep 5"])))
      (is (= 3 (count (jobs/jobs run))) "the refused one never started")
      (is (:job (start (str run "-b") ["bash" "-c" "sleep 5"])) "the cap is per run")
      (finally (jobs/forget-run! run) (jobs/forget-run! (str run "-b"))))))

(deftest run-teardown-kills-every-job
  (let [run (str "jobs-" (random-uuid))
        {:keys [job]} (start run ["bash" "-c" "sleep 30"])
        pid (:pid (jobs/job run job))]
    (Thread/sleep 200)
    (is (pos? pid))
    (run-state/run-ended! run)
    (is (empty? (jobs/jobs run)))
    (is (settle #(let [h (java.lang.ProcessHandle/of pid)] (or (not (.isPresent h)) (not (.isAlive (.get h))))) 3000)
        "the process is gone")))

(deftest an-exit-is-told-once-to-the-branch-that-started-it
  (let [run (str "jobs-" (random-uuid))]
    (try
      (let [{:keys [job]} (start run ["bash" "-c" "echo bye; exit 0"])]
        (is (settle #(not= :running (:status (jobs/job run job))) 2000))
        (is (empty? (jobs/take-exits! run "B2")) "only the branch that started it")
        (let [[e :as exits] (jobs/take-exits! run "B1")]
          (is (= 1 (count exits)))
          (is (= job (:id e)))
          (is (= :completed (:status e))))
        (is (empty? (jobs/take-exits! run "B1")) "once"))
      (finally (jobs/forget-run! run)))))

;; --- through the tools ------------------------------------------------------

(defmacro with-run [[conn run root] & body]
  `(let [~conn (db/open! ":memory:")
         ~run (runs/start-run! ~conn {:problem "p"})
         ~root (str (jolt.fs/canonicalize (str (jolt.fs/create-temp-dir))))]
     (try ~@body
          (finally (jobs/forget-run! ~run) (db/close ~conn)))))

(defn- shell [conn run root command]
  (tools/run-tool {:tool-name "shell" :conn conn :run-id run :root root
                   :branch {:id "B1"}
                   :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")}
                   :args {:command command :background true}}))

(defn- jobs-tool [conn run args]
  (tools/run-tool {:tool-name "jobs" :conn conn :run-id run :branch {:id "B1"} :args args}))

(deftest a-background-command-still-faces-the-policy
  (with-run [c run root]
    (testing "a denied command is refused and never started"
      (let [r (shell c run root "rm -rf /")]
        (is (= :deny (get-in r [:policy :effect])))
        (is (:policy-refusal? r))
        (is (empty? (jobs/jobs run)))))
    (testing "an unapproved one is refused the same way"
      (let [r (shell c run root "python3 server.py")]
        (is (= :ask (get-in r [:policy :effect])))
        (is (empty? (jobs/jobs run)))))))

(deftest a-background-command-that-exits-at-once-is-a-failure
  (with-run [c run root]
    (spit (str root "/fail.sh") "echo boom-out\nexit 3\n")
    (grants/grant! c run "bash *")
    (let [r (shell c run root "bash fail.sh")]
      (is (= :failure (:category r)) (:result r))
      (is (str/includes? (:result r) "3"))
      (is (str/includes? (:result r) "boom-out")))))

(deftest a-background-server-is-read-and-killed-through-the-jobs-tool
  (with-run [c run root]
    (spit (str root "/serve.sh") "echo listening\nsleep 1.8\necho request-1\nsleep 30\n")
    (grants/grant! c run "bash *")
    (let [r (shell c run root "bash serve.sh")
          [{:keys [id]}] (jobs/jobs run)]
      (is (= :success (:category r)) (:result r))
      (is id)
      (is (str/includes? (:result r) id))
      (is (str/includes? (:result r) "listening") "the initial output comes back")
      (testing "list names it"
        (let [l (jobs-tool c run {"action" "list"})]
          (is (str/includes? (:result l) id))
          (is (str/includes? (:result l) "bash serve.sh"))))
      (testing "output is only what is new"
        (Thread/sleep 1000)
        (let [o (jobs-tool c run {"action" "output" "id" id})]
          (is (str/includes? (:result o) "request-1"))
          (is (not (str/includes? (:result o) "listening")))))
      (testing "kill stops it"
        (let [k (jobs-tool c run {"action" "kill" "id" id})]
          (is (str/includes? (:result k) id))
          (is (= :killed (:status (jobs/job run id))))))
      (testing "an unknown id is said to be unknown"
        (is (= :mechanics (:category (jobs-tool c run {"action" "output" "id" "job-0"})))))
      (testing "a malformed call says how to call it"
        (is (= :mechanics (:category (jobs-tool c run {"action" "frobnicate"}))))))))

(deftest a-job-s-exit-is-told-at-the-branch-s-next-call
  (with-run [c run root]
    (spit (str root "/brief.sh") "echo working\nsleep 1.8\necho finished-now\nexit 4\n")
    (grants/grant! c run "bash *")
    (shell c run root "bash brief.sh")
    (let [[{:keys [id]}] (jobs/jobs run)]
      (Thread/sleep 1000)
      (let [r (jobs-tool c run {"action" "list"})]
        (is (str/includes? (:result r) (str id)))
        (is (str/includes? (:result r) "finished-now") "the notice carries the tail")
        (is (str/includes? (:result r) "4")))
      (let [again (jobs-tool c run {"action" "list"})]
        (is (not (str/includes? (:result again) "finished-now")) "told once")))))

(defn- alive? [pid]
  (let [h (java.lang.ProcessHandle/of pid)]
    (and (.isPresent h) (.isAlive (.get h)))))

(defn- orphan-pid
  "The pid a job's detached grandchild wrote to `f`, once it is there."
  [f]
  (when (settle #(pos? (count (str/trim (try (slurp f) (catch Throwable _ ""))))) 3000)
    (parse-long (str/trim (slurp f)))))

(deftest a-job-s-orphans-die-with-it
  ;; Round 3 of phase 2: a shell command runs in its own process group and is
  ;; killed by group, so an orphan it detached dies with it (html-js-filter's
  ;; 117 filter.py processes). A job was killed by tree only, and an orphan
  ;; reparented to init is no longer in the tree.
  (let [run (str "jobs-" (random-uuid))
        f (str (jolt.fs/create-temp-dir) "/orphan.pid")]
    (try
      (testing "killing the job"
        (let [{:keys [job]} (start run ["bash" "-c" (str "(sleep 30 & echo $! > " f ") ; sleep 30")])
              pid (orphan-pid f)]
          (is (pos? pid))
          (is (alive? pid))
          (is (= :killed (jobs/kill! run job)))
          (is (settle #(not (alive? pid)) 3000) "the orphan is gone")))
      (testing "a job that ended on its own still has its orphans killed at run end"
        (spit f "")
        (let [{:keys [job]} (start run ["bash" "-c" (str "(sleep 30 & echo $! > " f ") ; exit 0")])
              pid (orphan-pid f)]
          (is (settle #(= :completed (:status (jobs/job run job))) 3000))
          (is (alive? pid) "a job may leave a server behind while the run goes on")
          (jobs/forget-run! run)
          (is (settle #(not (alive? pid)) 3000) "but not past the run")))
      (finally (jobs/forget-run! run)))))

(deftest a-job-s-output-is-kept-as-a-shell-log
  ;; Background jobs had no shell-log capture: their output lived only in the
  ;; bounded buffer, so lines that left it were gone. Now the job's streams
  ;; are captured like any command's, readable through the resource tool once
  ;; it ends, and the jobs tool says where.
  (with-run [c run root]
    (spit (str root "/many.sh") "for i in $(seq 1 120); do echo line-$i; done\necho oops >&2\nexit 0\n")
    (grants/grant! c run "bash *")
    (let [r (shell c run root "bash many.sh")
          [{:keys [id]}] (jobs/jobs run)]
      (is id (:result r))
      (is (settle #(not= :running (:status (jobs/job run id))) 3000))
      (let [capture (settle #(= "complete" (:status (first (db/fetch c ["SELECT status FROM shell_captures"])))) 3000)
            row (first (db/fetch c ["SELECT * FROM shell_captures"]))]
        (is capture "the capture is finished when the job ends")
        (is (str/includes? (slurp (:stdout_read_path row)) "line-1\n"))
        (is (str/includes? (slurp (:stdout_read_path row)) "line-120"))
        (is (str/includes? (slurp (:stderr_read_path row)) "oops"))
        (let [o (jobs-tool c run {"action" "output" "id" id})]
          (is (str/includes? (:result o) (str "shell-log:" (:id row))) (:result o)))))))
