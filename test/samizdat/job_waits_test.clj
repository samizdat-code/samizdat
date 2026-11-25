;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.job-waits-test
  "Waits on a background job's output (karamazov-k7gl.1): the pure step over
  a job snapshot, the registry with an injected clock and job source, the
  `jobs` tool's wait/watch actions, the wake at a branch's next call, the
  journal record a resume restores from, and the beam's hold cell."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs]
            [mycelium.cell :as cell]
            [samizdat.agent.state :as state]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.jobs :as jobs-tool]
            [samizdat.cells :as cells]
            [samizdat.engine.jobs :as jobs]
            [samizdat.engine.waits :as waits]
            [samizdat.store.db :as db]
            [samizdat.store.grants :as grants]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]))

(defn- snap [texts & {:keys [start status exit last-ms] :or {start 0 status :running}}]
  {:lines (mapv (fn [t] {:text t :stream :stdout}) texts)
   :start start :total (+ start (count texts)) :status status :exit exit :last-ms last-ms})

(defn- fresh [& {:as opts}]
  (waits/make (merge {:id "wait-1" :run-id "r" :branch-id "B1" :job "job-1"
                      :pattern "ready" :timeout-ms 10000 :quiet-ms nil}
                     opts)
              (snap ["old ready line"]) 1000))

;; --- the pure step ------------------------------------------------------------

(deftest a-wait-starts-at-the-end-unless-it-asks-for-history
  (is (= 1 (:from (fresh))))
  (is (= 0 (:from (fresh :history? true))))
  (is (= :pending (:state (waits/step (fresh) (snap ["old ready line"]) 1100)))
      "the line already there does not match a wait made after it")
  (let [w (waits/step (fresh :history? true) (snap ["old ready line"]) 1100)]
    (is (= :ready (:state w)))
    (is (= :matched (:outcome w)))
    (is (= ["old ready line"] (mapv :text (:matches w))))))

(deftest a-new-matching-line-makes-it-ready
  (let [w (waits/step (fresh) (snap ["old ready line" "booting" "server ready on 8080"]) 1200)]
    (is (= :ready (:state w)))
    (is (= [{:text "server ready on 8080" :stream :stdout :index 2}] (:matches w)))))

(deftest an-exit-satisfies-either-condition
  (let [w (waits/step (fresh) (snap ["old ready line" "boom"] :status :failed :exit 2) 1200)]
    (is (= [:ready :exited] [(:state w) (:outcome w)]))
    (is (= [:failed 2] [(:job-status w) (:exit w)])))
  (testing "a matching last line still counts as the match"
    (let [w (waits/step (fresh) (snap ["old ready line" "ready"] :status :completed :exit 0) 1200)]
      (is (= :matched (:outcome w)))))
  (testing "an :exit wait ignores output"
    (let [w (waits/make {:id "wait-2" :job "job-1" :condition :exit :timeout-ms 5000}
                        (snap []) 0)]
      (is (= :pending (:state (waits/step w (snap ["ready"]) 10))))
      (is (= :exited (:outcome (waits/step w (snap ["ready"] :status :killed) 10)))))))

(deftest deadline-quiet-and-a-vanished-job-expire-it
  (is (= [:expired :deadline]
         ((juxt :state :outcome) (waits/step (fresh) (snap ["old ready line"]) 11000))))
  (let [w (fresh :quiet-ms 3000)]
    (is (= :pending (:state (waits/step w (snap ["old ready line" "x"] :last-ms 2500) 5000)))
        "output 2.5s in resets the quiet clock")
    (is (= [:expired :quiet]
           ((juxt :state :outcome) (waits/step w (snap ["old ready line" "x"] :last-ms 2500) 5600)))))
  (is (= :job-gone (:outcome (waits/step (fresh) nil 1100))))
  (testing "a terminal wait does not move"
    (let [w (waits/step (fresh) nil 1100)]
      (is (= w (waits/step w (snap ["ready"]) 1200))))))

(deftest lines-that-left-the-buffer-are-counted
  (let [w (waits/step (fresh) (snap ["a" "b"] :start 5) 1100)]
    (is (= 4 (:dropped w)))
    (is (= 7 (:scanned w)))))

;; --- the registry, with a fake job source and clock -------------------------------

(defn- fake-env [state clock]
  {:snapshot (fn [_ _] (:snap @state))
   :now (fn [] @clock)
   :sleep! (fn [ms] (swap! clock + ms)
             (when-let [f (:on-sleep @state)] (f @clock)))
   :poll-ms 100
   :record (fn [w] (swap! state update :records (fnil conj []) w))})

(deftest a-blocking-wait-returns-when-the-line-arrives
  (let [run (str "w-" (random-uuid))
        clock (atom 0)
        state (atom {:snap (snap ["starting"])})
        env (fake-env state clock)]
    (try
      (swap! state assoc :on-sleep (fn [t] (when (>= t 500)
                                             (swap! state assoc :snap (snap ["starting" "listening on 3000"])))))
      (let [{w :wait} (waits/register! {:run-id run :branch-id "B1" :job "job-1"
                                        :pattern "listening" :timeout-ms 10000 :mode :block}
                                       env)
            done (waits/await! run (:id w) env)]
        (is (= :ready (:state done)))
        (is (= 500 @clock) "it slept in poll-sized steps until the line")
        (is (= [:pending :ready] (mapv :state (:records @state))) "made and readied, recorded"))
      (finally (waits/forget-run! run)))))

(deftest register-refuses-a-missing-job-and-a-bad-pattern
  (let [env (fake-env (atom {:snap nil}) (atom 0))]
    (is (= {:error :no-job} (waits/register! {:run-id "r" :job "job-9" :pattern "x" :timeout-ms 1} env))))
  (let [env (fake-env (atom {:snap (snap [])}) (atom 0))
        r (waits/register! {:run-id "r" :job "job-1" :pattern "(" :timeout-ms 1} env)]
    (is (= :bad-pattern (:error r)))
    (is (string? (:message r)))))

(deftest a-watch-is-taken-once-and-holds-until-ready
  (let [run (str "w-" (random-uuid))
        clock (atom 0)
        state (atom {:snap (snap [])})
        env (fake-env state clock)]
    (try
      (let [{w :wait} (waits/register! {:run-id run :branch-id "B1" :job "job-1"
                                        :pattern "done" :timeout-ms 60000} env)]
        (is (= [w] (waits/pending run ["B1"])))
        (is (empty? (waits/pending run ["B2"])))
        (is (empty? (waits/take-wakes! run "B1" env)) "nothing ready yet")
        (swap! state assoc :on-sleep (fn [t] (when (>= t 300) (swap! state assoc :snap (snap ["all done"])))))
        (let [r (waits/await-any! run ["B1"] (assoc env :max-ms 5000))]
          (is (= [(:id w)] (mapv :id (:ready r)))))
        (is (empty? (waits/pending run nil)))
        (let [[woke] (waits/take-wakes! run "B1" env)]
          (is (= :ready (:state woke)))
          (is (= :delivered (:state (waits/wait run (:id w))))))
        (is (empty? (waits/take-wakes! run "B1" env)) "told once"))
      (finally (waits/forget-run! run)))))

(deftest await-any-stops-at-its-cap-and-on-abort
  (let [run (str "w-" (random-uuid))
        clock (atom 0)
        env (fake-env (atom {:snap (snap [])}) clock)]
    (try
      (waits/register! {:run-id run :branch-id "B1" :job "job-1" :pattern "x" :timeout-ms 600000} env)
      (is (:timeout (waits/await-any! run ["B1"] (assoc env :max-ms 1000))))
      (is (:aborted (waits/await-any! run ["B1"] (assoc env :max-ms 1000 :abort? (constantly true)))))
      (is (:none (waits/await-any! run ["B2"] (assoc env :max-ms 1000))))
      (finally (waits/forget-run! run)))))

(deftest a-resumed-run-expires-what-was-pending-and-still-owes-a-ready-wake
  (let [run (str "w-" (random-uuid))
        recorded (atom [])
        records [{:id "wait-7" :branch-id "B1" :job "job-1" :mode "watch" :state "pending"
                  :condition "output-regex" :pattern "x"}
                 {:id "wait-8" :branch-id "B1" :job "job-2" :mode "watch" :state "pending"}
                 {:id "wait-8" :branch-id "B1" :job "job-2" :mode "watch" :state "ready"
                  :outcome "exited"}]]
    (try
      (let [ws (waits/restore! run records {:now (constantly 5) :record #(swap! recorded conj %)})]
        (is (= {"wait-7" [:expired :job-gone] "wait-8" [:ready :exited]}
               (into {} (map (juxt :id (juxt :state :outcome))) ws)))
        (is (= ["wait-7"] (mapv :id @recorded)) "the expiry is recorded")
        (is (= #{"wait-7" "wait-8"}
               (set (map :id (waits/take-wakes! run "B1" {:snapshot (constantly nil) :now (constantly 6)}))))))
      (finally (waits/forget-run! run)))))

;; --- a real job ------------------------------------------------------------------

(deftest a-real-job-s-line-is-waited-for
  (let [run (str "w-" (random-uuid))]
    (try
      (let [{:keys [job]} (jobs/start! {:run-id run :branch-id "B1" :command "sh"
                                        :argv ["sh" "-c" "echo booting; sleep 0.3; echo READY 42; sleep 5"]
                                        :dir "." :env {"PATH" (System/getenv "PATH")}
                                        :max-jobs 3 :buffer-lines 50 :max-line-chars 200})
            env {:snapshot jobs/snapshot :now #(System/currentTimeMillis)
                 :sleep! #(Thread/sleep (long %)) :poll-ms 20}
            {w :wait} (waits/register! {:run-id run :branch-id "B1" :job job
                                        :pattern "READY \\d+" :timeout-ms 4000 :mode :block} env)
            done (waits/await! run (:id w) env)]
        (is (= [:ready :matched] [(:state done) (:outcome done)]))
        (is (= ["READY 42"] (mapv :text (:matches done))))
        (is (= :running (:status (jobs/job run job))) "the job keeps running"))
      (finally (waits/forget-run! run) (jobs/forget-run! run)))))

;; --- through the tool ------------------------------------------------------------

(defmacro with-run [[conn run root] & body]
  `(let [~conn (db/open! ":memory:")
         ~run (runs/start-run! ~conn {:problem "p"})
         ~root (str (jolt.fs/canonicalize (str (jolt.fs/create-temp-dir))))]
     (try ~@body
          (finally (waits/forget-run! ~run) (jobs/forget-run! ~run) (db/close ~conn)))))

(defn- shell [conn run root command]
  (tools/run-tool {:tool-name "shell" :conn conn :run-id run :root root
                   :branch {:id "B1"}
                   :env {"PATH" (System/getenv "PATH") "HOME" (System/getenv "HOME")}
                   :args {:command command :background true}}))

(defn- jobs-tool [conn run args]
  (tools/run-tool {:tool-name "jobs" :conn conn :run-id run :branch {:id "B1"} :args args}))

(deftest the-jobs-tool-waits-for-a-line-and-a-watch-wakes-the-next-call
  (with-run [c run root]
    (spit (str root "/serve.sh") "echo booting\nsleep 2.5\necho 'listening on 9000'\nsleep 1\necho second-phase\nsleep 30\n")
    (grants/grant! c run "bash *")
    (shell c run root "bash serve.sh")
    (let [[{:keys [id]}] (jobs/jobs run)]
      (testing "wait blocks until the line and returns it with the job's status"
        (let [r (jobs-tool c run {:action "wait" :id id :pattern "listening on \\d+" :seconds 5})]
          (is (:progress? r) (:result r))
          (is (str/includes? (:result r) "listening on 9000"))
          (is (str/includes? (:result r) "running"))))
      (testing "a bad pattern is malformed"
        (let [r (jobs-tool c run {:action "wait" :id id :pattern "(" :seconds 1})]
          (is (= :mechanics (:category r)) (:result r))))
      (testing "a timeout says so"
        (let [r (jobs-tool c run {:action "wait" :id id :pattern "never-printed" :seconds 0.3})]
          (is (str/includes? (str/lower-case (:result r)) "no line matching") (:result r))))
      (testing "watch returns at once and the wake arrives at a later call"
        (let [r (jobs-tool c run {:action "watch" :id id :pattern "second-phase" :seconds 10})]
          (is (re-find #"wait-\d+" (:result r)) (:result r))
          (is (= 1 (count (waits/pending run ["B1"])))))
        (Thread/sleep 1500)
        (let [r (jobs-tool c run {:action "list"})]
          (is (str/includes? (:result r) "Your watch") (:result r))
          (is (str/includes? (:result r) "second-phase") (:result r))
          (is (empty? (waits/pending run nil))))
        (let [r (jobs-tool c run {:action "list"})]
          (is (not (str/includes? (:result r) "Your watch")) "told once")))
      (testing "every move is journalled for a resume"
        (let [states (mapv :state (journal/notes c run :job-wait))]
          (is (some #{"delivered"} states) states))))))

(deftest a-watch-can-be-cancelled
  (with-run [c run root]
    (grants/grant! c run "bash *")
    (spit (str root "/s.sh") "sleep 30\n")
    (shell c run root "bash s.sh")
    (let [[{:keys [id]}] (jobs/jobs run)
          r (jobs-tool c run {:action "watch" :id id :seconds 60})
          wid (re-find #"wait-\d+" (:result r))]
      (is (str/includes? (:result (jobs-tool c run {:action "unwatch" :id wid})) wid))
      (is (empty? (waits/pending run nil))))))

(deftest restore-waits-reads-the-journal
  (with-run [c run _root]
    (journal/note! c run :job-wait {:branch-id "B1" :data {:id "wait-3" :branch-id "B1" :job "job-1"
                                                           :mode "watch" :state "pending"
                                                           :condition "exit"}})
    (jobs-tool/restore-waits! c run)
    (is (= :expired (:state (waits/wait run "wait-3"))))
    (let [r (jobs-tool c run {:action "list"})]
      (is (str/includes? (:result r) "wait-3") (:result r))
      (is (str/includes? (:result r) "did not survive") "the branch is told at its next call"))))

;; --- the beam's hold cell ------------------------------------------------------

(defn- hold-cell []
  (cells/load-cells!)
  (:handler (cell/get-cell! :beam/hold)))

(defn- shipped [c run id]
  (runs/open-branch! c run {:branch-id id})
  (runs/close-branch! c run id :done nil)
  (assoc (state/new-branch {:id id :problem "p" :messages [{:role "user" :content "p"}]})
         :status :done :final-answer "shipped"))

(deftest only-a-completing-run-s-shipped-branch-is-held
  (hold-cell)
  (let [hold-for (deref (cells/cell-var 'cells.beam 'hold-for))]
    (is (= #{"B1"} (hold-for {:verdict :completed :done-branch {:id "B1"}} {:hold? true})))
    (is (nil? (hold-for {:verdict :completed :done-branch {:id "B1"}} {:hold? false})))
    (is (nil? (hold-for {:verdict :exhausted :done-branch nil} {:hold? true})))
    (is (nil? (hold-for {:verdict :aborted :done-branch {:id "B1"}} {:hold? true})))))

(deftest a-finishing-run-with-no-watch-finishes
  (with-run [c run _root]
    (let [b (shipped c run "B1")
          data {:verdict :completed :done-branch b :branches [b] :active [] :multi-candidate? false}
          out ((hold-cell) {:conn c :run-id run :abort (atom false)} data)]
      (is (= :completed (:verdict out)))
      (is (= b (:done-branch out))))))

(deftest a-finishing-run-is-held-for-a-watch-and-the-branch-woken
  (with-run [c run root]
    (grants/grant! c run "bash *")
    (spit (str root "/s.sh") "sleep 2.2\necho 'migration finished'\nsleep 30\n")
    (shell c run root "bash s.sh")
    (let [[{:keys [id]}] (jobs/jobs run)
          _ (jobs-tool c run {:action "watch" :id id :pattern "finished" :seconds 20})
          b (shipped c run "B1")
          data {:verdict :completed :done-branch b :branches [b] :active [] :multi-candidate? false}
          out ((hold-cell) {:conn c :run-id run :abort (atom false)} data)
          woken (first (:branches out))]
      (is (= :continue (:verdict out)) "held, then woken rather than finished")
      (is (nil? (:done-branch out)))
      (is (= :active (:status woken)))
      (is (nil? (:final-answer woken)))
      (is (= [woken] (:active out)))
      (is (str/includes? (str (:content (last (:messages woken)))) "migration finished"))
      (is (= "active" (:status (runs/get-branch c run "B1"))) "its row reopened")
      (is (seq (journal/notes c run :run-held))))))
