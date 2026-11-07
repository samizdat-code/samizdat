;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later
;;
;; The TEAM-SOLVE cells (manifests/team-solve.edn wires them): GVS5H's manager
;; loop. Every model-facing word is a prompt, every number is gates.edn
;; :team-solve, and the ledger is the run's journal: each role's context is
;; assembled from the latest :team-solve-ledger note, which is also how the
;; supervisor reads the team and how a resumed run finds it.
;;
;; WHAT DECIDES. A script decides whether the work passes (`test`, `verify`).
;; The manager decides what to try next, inside rules it cannot talk past
;; (`decide`). The supervisor can redirect at `manage` and `unstick`, the two
;; boundaries where nothing is running.
(ns cells.team-solve
  (:require [clojure.string :as str]
            [mycelium.cell :as cell]
            [mycelium.core :as myc]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.gitdiff :as gitdiff]
            [samizdat.agent.loop :as turn]
            [samizdat.agent.sections :as sections]
            [samizdat.agent.state :as state]
            [samizdat.agent.verify :as verify]
            [samizdat.cancel :as cancel]
            [samizdat.eval.samples :as samples]
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.client :as llm]
            [samizdat.prompt :as prompt]
            [samizdat.store.interventions :as interventions]
            [samizdat.store.journal :as journal]
            [samizdat.store.runs :as runs]
            [samizdat.store.tasks :as tasks]
            [samizdat.workflow :as wf]))

(defn- policy [] (lexicon/policy :team-solve))

(defn- clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (subs s 0 n) s)))

;; --- the ledger ---------------------------------------------------------------

(defn- ledger [{:keys [conn run-id]}]
  (or (journal/last-note conn run-id :team-solve-ledger) {}))

(defn- save-ledger!
  "Merge `changes` into the ledger and journal the result. Returns it."
  [{:keys [conn run-id] :as ctx} changes]
  (let [l (merge (ledger ctx) changes)]
    (journal/note! conn run-id :team-solve-ledger {:data l})
    l))

(defn- position!
  "Where the team is, for the supervisor's brief: the one thing a stream beside
  the run cannot work out from turns alone."
  [{:keys [conn run-id]} stage l]
  (journal/note! conn run-id :workflow-position
                 {:data {:workflow "team-solve"
                         :stage (name stage)
                         :round (or (:round l) 0)
                         :max-rounds (:max-rounds (policy))
                         :task (:task l)
                         :gate (get-in l [:gate :summary])
                         :program (some? (:program l))
                         :attempts (:attempts l)}}))

(defn- signal!
  "Tell the supervisor this workflow needs a look. The stream wakes on it."
  [{:keys [conn run-id]} kind stage detail]
  (journal/note! conn run-id :workflow-signal
                 {:data {:workflow "team-solve" :kind (name kind)
                         :stage (name stage) :detail (str detail)}}))

;; --- one model call -----------------------------------------------------------

(declare call-failed! program?)

(defn- call-failed!
  "A role's call kept failing. One signal per distinct error: repeating it would
  only spend the supervisor's budget on a provider that is down."
  [{:keys [conn run-id] :as ctx} step error]
  (let [error (clip error (:report-chars (policy)))
        seen (journal/notes conn run-id :workflow-signal)]
    (when-not (some #(= error (:detail %)) seen)
      (signal! ctx :role-call-failed step error))
    (let [l (ledger ctx)]
      (save-ledger! ctx {:call-failures (inc (or (:call-failures l) 0))
                         :call-error error}))))

(defn- ask*
  "One fresh-context call for `role` at temperature `step`. {:text :finish}, the
  text as the model wrote it. Retried per gates; a call that keeps failing raises a
  signal and answers \"\", and the caller decides what an empty reply means."
  [ctx role step system user]
  (let [{:keys [llm-adapter llm-config]} (wf/role-ctx ctx role)
        t (get-in (policy) [:temperatures step])
        cfg (cond-> llm-config t (assoc :temperature t))
        ;; What their numbers were measured under, for the provider type that can do it.
        cfg (cond-> cfg
              (program? ctx) (merge (get-in (policy) [:program-llm (:provider cfg)])))
        msgs [{:role "system" :content system} {:role "user" :content user}]]
    ;; On the record before the call: a long generation on a slow model is not
    ;; silence, and the stream reading the journal can tell.
    (journal/note! (:conn ctx) (:run-id ctx) :workflow-step {:data {:role (name role) :step (name step)}})
    (loop [left (or (:call-retries (policy)) 0)]
      (let [r (try {:reply (llm/chat llm-adapter cfg msgs)}
                   (catch Throwable e
                     (when (cancel/control-signal? e) (throw e))
                     {:error (or (ex-message e) (str e))}))]
        (cond
          (:reply r) (let [{:keys [content reasoning usage finish-reason]} (:reply r)]
                       (journal/record-side-call! (:conn ctx) (:run-id ctx)
                                                  {:kind (keyword (str "team-solve-" (name step)))
                                                   :role role
                                                   :model (:model cfg)
                                                   :usage usage})
                       ;; A reply with no text is answered by its reasoning.
                       {:text (str (if (str/blank? (str content)) reasoning content))
                        :finish (some-> finish-reason name)})
          (pos? left) (recur (dec left))
          :else (do (call-failed! ctx step (:error r)) {:text "" :finish nil}))))))

(defn- ask
  "`ask*`, as the text the repository path reads."
  [ctx role step system user]
  (sections/reply-text (:text (ask* ctx role step system user))))

;; --- the gate -----------------------------------------------------------------

(defn- samples-dir [root] (str root "/tests"))

(defn- gate-kind
  "What can decide this run's work: paired samples, the sealed verify command,
  or nothing."
  [{:keys [root config]}]
  (cond
    (seq (remove :missing (samples/paired-tests (samples-dir root)))) :samples
    (not (str/blank? (str (get-in config [:run :verify-cmd])))) :verify-cmd
    :else :none))

(defn- target [{:keys [root]}] (str root "/" (:artifact (policy))))

(defn- program?
  "A task with paired samples, on the worker that is GVS5H's: from here on this
  workflow is their multiagent_solve, request for request."
  [ctx]
  (and (= :samples (gate-kind ctx))
       (= :one-call (:program-worker (policy)))))

(defn- report [flags] (str/trim (prompt/render "team-solve-gate-report" flags)))

(defn- tests-fingerprint
  "What the samples say. A worker has a shell, and passing tests it rewrote prove
  nothing."
  [root]
  (str (hash (mapv (juxt :name :input :expected) (samples/paired-tests (samples-dir root))))))

(defn- save-tests!
  "Keep the samples as they were at the start, on the record."
  [{:keys [conn run-id root]}]
  (journal/note! conn run-id :team-solve-tests
                 {:data {:cases (mapv #(select-keys % [:name :input :expected])
                                      (samples/paired-tests (samples-dir root)))}}))

(defn- restore-tests!
  "Put modified samples back. A case a worker added stays, harmlessly failing the
  fingerprint until it is gone, so remove nothing here: the report tells it."
  [{:keys [conn run-id root]}]
  (doseq [{:keys [name input expected]} (:cases (journal/last-note conn run-id :team-solve-tests))]
    (samples/write-artifact! (str (samples-dir root) "/" name ".in") input)
    (samples/write-artifact! (str (samples-dir root) "/" name ".out") expected)))

(defn- run-samples [ctx]
  (let [path (target ctx)
        dir (str (:root ctx))
        l (ledger ctx)]
    (cond
     (and (:tests-sig l) (not= (:tests-sig l) (tests-fingerprint dir)))
     (do (restore-tests! ctx)
         {:kind "samples" :ran true :ok false :sig "tampered"
          :summary "samples were modified"
          :report (report {:tampered true})})

     (not (samples/read-artifact path))
      {:kind "samples" :ran true :ok false :sig "missing"
       :summary "no work yet"
       :report (report {:missing (:artifact (policy))})}
     :else
      (let [r (samples/grade (samples-dir dir) ["python3" path] {:dir dir})
            f (first (:failures r))
            chars (:report-chars (policy))]
        {:kind "samples" :ran true :ok (boolean (:ok? r))
         :passed (:passed r) :total (:total r)
         :summary (str "samples " (:passed r) "/" (:total r))
         :sig (when f (str (:name f) "|" (name (:reason f)) "|" (clip (:got f) chars)))
         :report (report (if (:ok? r)
                           {:passed true :total (:total r)}
                           (cond-> {:failed true :total (:total r) :passed-count (:passed r)}
                             f (assoc :case (:name f) :reason (name (:reason f))
                                      :input (pr-str (clip (:input f) chars))
                                      :expected (pr-str (clip (:expected f) chars))
                                      :got (pr-str (clip (:got f) chars))))))}))))

(defn- run-verify-cmd [{:keys [run-id config]}]
  (let [cmd (get-in config [:run :verify-cmd])
        r (verify/run-configured run-id cmd (:verify-timeout-ms (policy)))
        output (clip (:output r) (:report-chars (policy)))]
    {:kind "verify-cmd" :ran true :ok (boolean (:green? r))
     :summary (if (:green? r) "verify passed" "verify failed")
     :sig (when-not (:green? r) output)
     :report (report (if (:green? r) {:passed true} {:failed true :output output}))}))

(defn- run-gate [ctx]
  (case (gate-kind ctx)
    :samples (run-samples ctx)
    :verify-cmd (run-verify-cmd ctx)
    {:kind "none" :ran false :ok false :summary "no check" :report (report {:none true})}))

;; --- the work -----------------------------------------------------------------

(defn- diff-text [{:keys [root git-baseline]} cap]
  (when git-baseline (gitdiff/diff root git-baseline cap)))

(defn- current-work
  "What the manager and a worker read as the work so far."
  [ctx]
  (let [cap (:current-chars (policy))]
    (if (= :samples (gate-kind ctx))
      (some-> (samples/read-artifact (target ctx)) (clip cap))
      (diff-text ctx cap))))

(defn- artifact?
  "Whether there is any work at all. `done` needs one."
  [{:keys [root git-baseline] :as ctx}]
  (if (= :samples (gate-kind ctx))
    (not (str/blank? (str (samples/read-artifact (target ctx)))))
    (if git-baseline
      (boolean (seq (gitdiff/changed-files root git-baseline)))
      true)))

(defn- snapshot
  "The work as it stands, restorable: program text under the samples gate, a
  git tree id otherwise."
  [ctx]
  (if (= :samples (gate-kind ctx))
    {:artifact (samples/read-artifact (target ctx))}
    (when-let [t (gitdiff/work-tree-id (:root ctx))] {:tree t})))

(defn- restore! [ctx {:keys [artifact tree]}]
  (cond
    artifact (samples/write-artifact! (target ctx) artifact)
    tree (gitdiff/restore-work-tree! (:root ctx) tree)))

(defn- keep-best!
  "Remember the LAST passing work, so a later worse round cannot destroy it."
  [{:keys [conn run-id] :as ctx} round]
  (when-let [s (snapshot ctx)]
    (journal/note! conn run-id :team-solve-best {:data (assoc s :round round)})))

;; --- the supervisor's directives ------------------------------------------------

(defn- drain-directives
  "Apply what the supervisor queued for this workflow's boundary. Returns
  {:retask text :stop why :finish answer :budget n :guidance text}. Directives scoped to a
  branch are that branch's own, and a `message` to the whole run has no worker
  to reach here, so the manager reads it."
  [{:keys [conn run-id]} round]
  (reduce
   (fn [acc d]
     (let [applied! (fn [m]
                      (interventions/resolve! conn run-id (:id d) :applied nil round)
                      (merge-with (fn [a b] (if (string? a) (str a "\n" b) b)) acc m))
           text (interventions/text-of d)]
       (case (:kind d)
         ("retask" "switch") (applied! {:retask text})
         "stop" (applied! {:stop (or text "the supervisor stopped the run")})
         "finish" (applied! {:finish (or text "the supervisor finished the run")})
         "budget" (if-let [n (interventions/turns-asked d)]
                    (applied! {:budget n})
                    acc)
         "message" (if (nil? (:branch_id d)) (applied! {:guidance text}) acc)
         acc)))
   {}
   (interventions/pending conn run-id)))

;; --- the plan and the ideas -----------------------------------------------------

(defn- parse-tasks
  "Task rows from the manager's or planner's bullets, `[done]` or `[todo]`."
  [text cap]
  (->> (sections/bullets text)
       (keep (fn [b]
               (let [[_ mark desc] (re-matches #"(?i)\[\s*([a-z_ ]+?)\s*\]\s*(.*)" b)
                     desc (str/trim (or desc b))]
                 (when-not (str/blank? desc)
                   {:desc desc :done (= "done" (some-> mark str/lower-case))}))))
       (take cap)
       vec))

(defn- task-lines [tasks]
  (str/join "\n" (for [{:keys [desc done]} tasks]
                   (str "- [" (if done "done" "todo") "] " desc))))

;; --- the program path: GVS5H's multiagent_solve ---------------------------------
;;
;; Ported from codebase/v2-current/escalation/multiagent.py, MIT, Copyright (c) 2026
;; Persis Capital Inc; their prompts are prompts/program-*.md, verbatim. One call per
;; role and no tools. Every function here matches theirs named in its docstring, and
;; samizdat.team-solve-parity-test compares what they send, byte for byte.

(defn- clip-of [k s] (clip s (get-in (policy) [:program-clips k])))

(defn- say
  "A prompt with its file's edges as they are: these are compared byte for byte."
  ([name] (prompt/prompt name))
  ([name ctx] (prompt/render name ctx)))

(defn- program-plan!
  "_primary_plan."
  [ctx problem]
  (let [p (policy)
        reply (:text (ask* ctx :planner :plan (say "program-plan-system")
                           (say "program-plan-user" {:problem problem})))
        sec (sections/parse-exact reply)
        plan (clip (or (not-empty (get sec "PLAN")) (str/trim reply)) (:plan-chars p))]
    (save-ledger! ctx {:problem problem :plan plan :notes "" :round 0 :attempts [] :program true
                       :tasks (sections/add-tasks [] (sections/bullets (get sec "TASKS")) (:max-tasks p))})))

(defn- program-ideate!
  "_ideation_worker."
  [ctx]
  (let [p (policy)
        l (ledger ctx)
        reply (:text (ask* ctx :ideator :ideate (say "program-ideate-system")
                           (say "program-ideate-user" {:problem (:problem l) :plan (:plan l)})))
        sec (sections/parse-exact reply)
        analysis (sections/strip-code-exact (or (not-empty (get sec "NOTES")) (str/trim reply)))]
    (save-ledger! ctx {:notes (str "\n## ideation\n" (clip analysis (:notes-chars p)) "\n")
                       :proposals (mapv sections/strip-code-exact (sections/bullets (get sec "NEXT")))
                       :latest (say "program-ideated")})))

(defn- program-task-lines [tasks]
  (or (not-empty (str/join "\n" (for [{:keys [status desc]} tasks]
                                  (str "- [" (if (= "done" status) "done" "todo") "] " desc))))
      "(none)"))

(defn decide-program
  "Where multiagent_solve's loop goes after a manager's reply. Pure.
  `status` and `task` are what _primary_manage returned; `failing?` is whether the
  samples that just ran failed. The override for that is theirs, and so is what a
  repeated task does: their loop breaks and finalizes. Ours stops at :unstick on the
  way so a supervisor can answer, and with no answer it finalizes the same."
  [{:keys [status task prev-task round failing? answer?]} {:keys [max-rounds]} fix-task]
  (let [overridden? (and failing? (= "done" status))
        status (if overridden? "continue" status)
        task (if (and overridden? (str/blank? (str task))) fix-task task)
        same? (fn [a b] (= (str/lower-case (str/trim (str a))) (str/lower-case (str/trim (str b)))))]
    (cond
      (and (= "continue" status) (seq (str task)) (< (or round 0) max-rounds))
      (if (and prev-task (same? task prev-task))
        {:go :unstick :task task :why "the manager issued the same task twice"}
        {:go :work :task task :overrode-done overridden?})

      (and (= "done" status) answer?) {:go :verify}
      :else {:go :finalize})))

(defn- none? [s]
  (let [s (str/lower-case (str/trim (str s)))]
    (or (str/blank? s) (= "none" s))))

(defn- guard-counterexample
  "A manager continued on passing samples. If it named a concrete input, run it: the
  published loss is a wrong expected answer talking the team out of a correct
  program. {:overrule? bool :verdict text}, or nil when there is nothing to check,
  which leaves the manager's word standing. So does a brute force that will not run:
  no evidence is not evidence against it."
  [ctx l reply]
  (let [sec (sections/parse-exact (:text (ask* ctx :manager :manage
                                               (say "program-guard-extract-system")
                                               (say "program-guard-extract-user" {:reply reply}))))
        input (get sec "INPUT")
        expected (get sec "EXPECTED")]
    (when-not (none? input)
      (let [dir (str (:root ctx))
            run (fn [argv] (samples/run-one argv (str input "\n")
                                            {:dir dir :timeout-ms (:program-brute-timeout-ms (policy))}))
            mine (run ["python3" (target ctx)])
            out (fn [r] (str/trim (:stdout r)))]
        (if (and (not (none? expected)) (:ran mine) (= (out mine) (str/trim expected)))
          {:overrule? true :verdict "the program already prints it"}
          (let [code (sections/extract-program
                      (:text (ask* ctx :manager :manage (say "program-guard-brute-system")
                                   (say "program-guard-brute-user" {:problem (:problem l)}))))
                brute (when (seq code) (run ["python3" "-c" code]))]
            (cond
              (not (:ran brute)) {:overrule? false :verdict "the brute force did not run"}
              (and (:ran mine) (= (out brute) (out mine)))
              {:overrule? true :verdict "brute force agrees with the program"}
              :else {:overrule? false :verdict "brute force agrees with the manager"})))))))

(defn- program-manage
  "_primary_manage, then multiagent_solve's rules for what it said."
  [{:keys [conn run-id] :as ctx} data l round guidance]
  (let [p (policy)
        cur (str/trim (str (samples/read-artifact (target ctx))))
        reply (:text (ask* ctx :manager :manage (say "program-manage-system")
                           (say "program-manage-user"
                                {:problem (:problem l)
                                 :current (or (not-empty cur) "(none yet)")
                                 :notes (:notes l)
                                 :tasks (program-task-lines (:tasks l))
                                 :latest (:latest l)
                                 :proposals (or (not-empty (str/join "\n" (map #(str "- " %) (:proposals l))))
                                                "(none)")
                                 :guidance guidance})))
        sec (sections/parse-exact reply)
        curated (or (not-empty (sections/parse-task-list (get sec "TASKS") (:max-tasks p))) (:tasks l))
        said-done? (str/starts-with? (str/lower-case (str/trim (str (get sec "STATUS")))) "done")
        status (if (and said-done? (seq cur)) "done" "continue")
        named (or (first (sections/bullets (get sec "NEXT"))) (str/trim (str (get sec "NEXT"))))
        named (str/trim (str/replace named #"^[-*]\s*" ""))
        next-task (if (and (= "continue" status) (str/blank? named))
                    (or (:desc (first (remove #(= "done" (:status %)) curated))) "")
                    named)
        [status next-task] (if (and (str/blank? cur) (str/blank? next-task))
                             ["continue" (say "program-first-task")]
                             [status next-task])
        res (:res l)
        d (decide-program {:status status :task next-task :prev-task (:task l) :round round
                           :failing? (and res (:ran res) (not (:ok res)))
                           :answer? (seq cur)}
                          p (say "program-fix-task"))
        guard (when (and (:loss-guards? p) res (:ok res) (= :work (:go d)))
                (guard-counterexample ctx l reply))
        _ (when guard (journal/note! conn run-id :team-solve-guard {:data (assoc guard :round round)}))
        d (if (:overrule? guard) {:go :verify} d)
        go (if (= :finalize (:go d)) :work (:go d))
        l (save-ledger! ctx (cond-> {:tasks curated}
                              (:task d) (assoc :task (:task d))
                              (:why d) (assoc :stuck-why (:why d))
                              (= :finalize (:go d)) (assoc :final true)))]
    (journal/note! conn run-id :team-solve-round
                   {:data {:round round :status status :go (name (:go d)) :task (:task l) :why (:why d)
                           :overrode-done (boolean (:overrode-done d))}})
    (position! ctx :manage l)
    (assoc data :team-solve/go go)))

(defn- cutoff-snippet
  "_summarize_cutoff's window on a long attempt."
  [reply]
  (let [{:keys [cutoff-chars program-clips]} (policy)
        {:keys [cutoff-head cutoff-tail]} program-clips
        txt (or (not-empty (sections/strip-think reply)) reply)]
    (if (<= (count txt) cutoff-chars)
      txt
      (str (subs txt 0 cutoff-head) "\n...[middle omitted]...\n" (subs txt (- (count txt) cutoff-tail))))))

(defn- program-work!
  "_worker: one call, and the harness writes what it replied."
  [ctx]
  (let [p (policy)
        l (ledger ctx)
        final? (:final l)
        round (if final? (or (:round l) 0) (inc (or (:round l) 0)))
        cur (str (samples/read-artifact (target ctx)))
        _ (position! ctx :work (assoc l :round round))
        {:keys [text finish]} (ask* ctx :solver :work (say "program-work-system")
                                    (say "program-work-user"
                                         {:problem (:problem l) :plan (:plan l) :notes (:notes l)
                                          :current (or (not-empty cur) "(none yet)")
                                          :goal (if final?
                                                  (say "program-finalize")
                                                  (say "program-goal" {:task (:task l)}))}))
        sec (sections/parse-exact text)
        code (or (not-empty (sections/extract-program (get sec "CODE")))
                 (sections/extract-program text))
        wrote? (boolean (seq code))
        cut? (= "length" finish)
        nexts (vec (remove #(= "none" (str/lower-case %)) (sections/bullets (get sec "NEXT"))))
        status (str/lower-case (str/trim (or (get sec "STATUS") "continue")))
        task-clip (clip-of :task (:task l))
        summary (if (and cut? (not final?))
                  (let [digest (:text (ask* ctx :summarizer :cutoff (say "program-cutoff-system")
                                            (say "program-cutoff-user"
                                                 {:task (:task l) :attempt (cutoff-snippet text)})))
                        digest (or (not-empty (sections/strip-think digest)) (say "program-no-summary"))]
                    (say "program-worker-cut" {:task task-clip :digest (clip-of :digest digest)}))
                  (say "program-worker-result" {:status status :task task-clip
                                                :notes (clip-of :notes (get sec "NOTES"))}))]
    (when wrote? (samples/write-artifact! (target ctx) code))
    (save-ledger! ctx (cond-> {:round round
                               :worker {:verdict (if cut? "cut" "done") :status status :summary summary
                                        :wrote wrote? :nexts nexts}
                               :result summary}
                        (and (not-empty (get sec "NOTES")) (not cut?))
                        (assoc :notes (str (clip (get sec "NOTES") (:notes-chars p)) "\n"))))))

(defn- program-samples
  "_run_samples and _sample_feedback."
  [ctx]
  (let [{:keys [program-sample-timeout-ms program-clips]} (policy)
        dir (str (:root ctx))
        results (mapv (fn [{:keys [input expected]}]
                        (let [r (samples/run-one ["python3" (target ctx)] input
                                                 {:dir dir :timeout-ms program-sample-timeout-ms})
                              out (str/trim (:stdout r))
                              got (cond
                                    (:timed-out r) (say "program-timed-out")
                                    (and (not (zero? (or (:exit r) 0))) (str/blank? out))
                                    (say "program-runtime-error" {:stderr (clip (:stderr r) (:stderr program-clips))})
                                    :else out)
                              exp (str/trim (str expected))]
                          {:ok (= got exp) :input input :expected exp :got got}))
                      (remove :missing (samples/paired-tests (samples-dir dir))))
        total (count results)
        passed (count (filter :ok results))
        fail (first (remove :ok results))
        ok? (= passed total)]
    {:kind "samples" :ran true :ok ok? :passed passed :total total
     :summary (str "samples " passed "/" total)
     :sig (when fail (:got fail))
     :report (if ok?
               (say "program-samples-passed" {:total total})
               (say "program-samples-failed"
                    {:passed passed :total total
                     :input (sections/py-repr (clip (:input fail) (:input program-clips)))
                     :expected (sections/py-repr (clip (:expected fail) (:output program-clips)))
                     :got (sections/py-repr (clip (:got fail) (:output program-clips)))}))}))

(defn- program-test!
  "multiagent_solve's step after a worker: samples only when it wrote, and the
  manager reads the verdict in front of the report. The finalize round is not
  tested here; verify does that."
  [{:keys [conn run-id] :as ctx} round-l]
  (let [{:keys [worker final]} round-l
        g (when (and (:wrote worker) (not final)) (program-samples ctx))
        l (save-ledger! ctx (cond-> {:res g :proposals (:nexts worker)
                                     :latest (str (when g (:report g)) (:summary worker))
                                     :attempts (conj (vec (:attempts round-l))
                                                     {:round (:round round-l)
                                                      :task (clip (:task round-l) (:report-chars (policy)))
                                                      :worker (:verdict worker)
                                                      :gate (:summary g)})}
                              g (assoc :gate g)))]
    (when (and g (:ok g))
      (when (:loss-guards? (policy)) (keep-best! ctx (:round l))))
    (when g
      (journal/note! conn run-id :team-solve-gate
                     {:data (select-keys g [:kind :ran :ok :passed :total :summary])}))
    (position! ctx :test l)
    (boolean (and g (:ok g)))))

(cell/defcell :team-solve/plan
  {:doc "PRIMARY: one call for the plan and the first task list. An unstructured
        reply is the plan; an empty one leaves the ideators and manager to work
        from the problem alone."
   :effects [:net :db]
   :requires [:config :conn :run-id :root]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/planned :boolean]]}
  (fn [ctx {:keys [branch] :as data}]
    (if (program? ctx)
      (let [l (program-plan! ctx (:problem branch))]
        (position! ctx :plan l)
        (assoc data :team-solve/planned (boolean (seq (:plan l)))))
    (let [p (policy)
          reply (ask ctx :planner :plan
                     (prompt/prompt "team-solve-plan-system")
                     (prompt/render "team-solve-plan-user" {:problem (:problem branch)}))
          sec (sections/parse reply)
          plan (clip (or (not-empty (get sec "PLAN")) reply) (:plan-chars p))
          l (save-ledger! ctx {:problem (:problem branch)
                               :tests-sig (when (= :samples (gate-kind ctx))
                                            (tests-fingerprint (:root ctx)))
                               :plan plan
                               :notes ""
                               :tasks (parse-tasks (get sec "TASKS") (:max-tasks p))
                               :round 0
                               :attempts []})]
      (when (:tests-sig l) (save-tests! ctx))
      (position! ctx :plan l)
      (assoc data :team-solve/planned (boolean (seq plan)))))))

(cell/defcell :team-solve/ideate
  {:doc "Independent proposals, each a fresh context under a different directive
        from gates.edn :ideators, so the pair cannot converge on one idea by
        construction. Code is stripped: a later worker implements. Run one after
        the other, because a local model serves two generations at a third of
        the speed each; neither sees the other's reply."
   :effects [:net :db]
   :requires [:config :conn :run-id]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/ideas :int]]}
  (fn [ctx data]
    (if (program? ctx)
      (let [l (program-ideate! ctx)]
        (position! ctx :ideate l)
        (assoc data :team-solve/ideas 1))
    (let [p (policy)
          l (ledger ctx)
          user (prompt/render "team-solve-ideate-user"
                              {:problem (:problem l) :plan (:plan l)})
          replies (when (:ideate? p)
                    (mapv (fn [directive]
                            (ask ctx :ideator :ideate
                                 (prompt/render "team-solve-ideate-system"
                                                {:directive (prompt/prompt directive)})
                                 user))
                          (:ideators p)))
          parsed (mapv sections/parse replies)
          notes (str/join "\n\n"
                          (for [[i reply sec] (map vector (range) replies parsed)]
                            (str "Proposal " (inc i) ":\n"
                                 (sections/strip-code (or (not-empty (get sec "NOTES")) reply)))))
          proposals (into [] (comp (mapcat #(sections/bullets (get % "NEXT")))
                                   (map sections/strip-code))
                          parsed)
          l (save-ledger! ctx {:notes (clip notes (:notes-chars p))
                               :proposals proposals
                               :latest (prompt/prompt "team-solve-ideated")})]
      (position! ctx :ideate l)
      (assoc data :team-solve/ideas (count replies))))))

;; --- the manager ----------------------------------------------------------------

(defn- norm-task [s] (str/lower-case (str/trim (str s))))

(defn decide
  "The manager's mechanical rules, pure: its reply plus the run's facts in, where
  the team goes next out. The model chooses the task; these decide whether it may
  stop, and what happens when it repeats itself.

  Returns {:go :work|:unstick|:verify, :task text?, :final? bool, :why text?}."
  [{:keys [status next gate artifact? prev-task stall round final? green-revisions have-best?]}
   {:keys [max-rounds stall-rounds max-green-revisions]}
   {:keys [first-task fix-task finalize]}]
  (let [ran? (:ran gate)
        green? (and ran? (:ok gate))
        red? (and ran? (not (:ok gate)))
        done? (= "done" status)
        named (not-empty (str/trim (str next)))
        task (or named (if artifact? fix-task first-task))]
    (cond
      ;; The finalize round has run: only verify is left.
      final? {:go :verify}
      (and done? artifact? (not red?)) {:go :verify}
      ;; A passing program is only sent back on a checked counterexample, which
      ;; the cell has run by now: this is the published loss mode where a manager
      ;; invented one and the team rewrote correct work.
      (and green? (not (:counterexample-fails gate))) {:go :verify}
      (and green? (>= (or green-revisions 0) max-green-revisions)) {:go :verify}
      ;; Passing work exists, its revisions are spent, and they made it worse:
      ;; verify restores it. More rounds would only repeat the overwrite.
      (and red? have-best? (>= (or green-revisions 0) max-green-revisions)) {:go :verify}
      ;; Everything below continues, so everything below is bounded, including
      ;; a manager saying done over a failing check or over no work at all.
      (>= (or round 0) max-rounds) {:go :work :task finalize :final? true}
      (and prev-task (= (norm-task task) (norm-task prev-task)))
      {:go :unstick :task task :why "the manager issued the same task twice"}
      (>= (or stall 0) stall-rounds)
      {:go :unstick :task task :why "the same check failure has repeated"}
      :else {:go :work :task task :overrode-done (boolean done?)})))

(defn- check-counterexample
  "Run the manager's counterexample against the program. True only when the
  program really gives a different answer: anything else is a claim that did not
  survive being run."
  [ctx {:keys [input expected]}]
  (let [r (samples/run-one ["python3" (target ctx)] (str input "\n") {:dir (str (:root ctx))})]
    (and (:ran r) (not= (str/trim (str (:out r))) (str/trim (str expected))))))

(defn- review!
  "An independent reviewer, one call in a fresh context, on a repository change that
  is about to ship. It reads what was asked, the diff and the captured check. A check
  says the tests pass; only this can say they pass because a worker wrote a stand-in
  for the test runner, or that half the request was never done. Returns
  {:pass? bool :findings text}. A reply that does not begin PASS sends it back, but a
  call that failed outright passes: it reviewed nothing, and its signal is raised."
  [ctx l]
  (let [reply (ask ctx :critic :review
                   (prompt/prompt "team-solve-review-system")
                   (prompt/render "team-solve-review-user"
                                  {:problem (:problem l)
                                   :diff (diff-text ctx (:current-chars (policy)))
                                   :check (get-in l [:gate :report])}))
        head (str/upper-case (subs reply 0 (min 4 (count reply))))]
    {:pass? (or (str/blank? reply) (= "PASS" head))
     :findings reply}))

(cell/defcell :team-solve/manage
  {:doc "PRIMARY: one call that reads the ledger and the captured check, curates
        the tasks and names the next one. Then `decide` applies the rules the
        model cannot talk past. The supervisor's queued directives land here,
        between rounds, where nothing is running."
   :effects [:net :db :proc :fs]
   :requires [:config :conn :run-id :root]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/go :keyword]]}
  (fn [{:keys [conn run-id] :as ctx} data]
    (let [p (policy)
          l (ledger ctx)
          round (or (:round l) 0)
          drained (drain-directives ctx round)
          budget (:budget drained)
          l (if budget (save-ledger! ctx {:worker-turns budget}) l)]
      (cond
        (:stop drained)
        (do (save-ledger! ctx {:stopped (:stop drained)})
            (journal/note! conn run-id :team-solve-round
                           {:data {:round round :go "verify" :why "supervisor stop"}})
            (assoc data :team-solve/go :verify))

        ;; Not a stop: verify's script judges the work as it stands, so this
        ;; only says there is no more of it coming.
        (:finish drained)
        (do (journal/note! conn run-id :team-solve-round
                           {:data {:round round :go "verify" :why "supervisor finish"}})
            (assoc data :team-solve/go :verify))

        (:retask drained)
        (do (save-ledger! ctx {:task (:retask drained) :stall 0})
            (journal/note! conn run-id :team-solve-round
                           {:data {:round round :go "work" :why "supervisor retask"}})
            (assoc data :team-solve/go :work))

        (:final l)
        (assoc data :team-solve/go :verify)

        ;; The roles cannot reach a model. More rounds would journal the same
        ;; refusal; end now, saying why.
        (>= (or (:call-failures l) 0) (:max-call-failures p))
        (do (save-ledger! ctx {:stopped (str "The model could not be reached: " (:call-error l))})
            (journal/note! conn run-id :team-solve-round
                           {:data {:round round :go "verify" :why "model unreachable"}})
            (assoc data :team-solve/go :verify))

        (program? ctx)
        (program-manage ctx data l round (:guidance drained))

        :else
        (let [reply (ask ctx :manager :manage
                         (prompt/prompt "team-solve-manage-system")
                         (prompt/render "team-solve-manage-user"
                                        {:problem (:problem l)
                                         :current (current-work ctx)
                                         :notes (:notes l)
                                         :tasks (task-lines (:tasks l))
                                         :latest (:latest l)
                                         :proposals (str/join "\n" (map #(str "- " %) (:proposals l)))
                                         :guidance (:guidance drained)
                                         :time-left (gates/run-time-left ctx)}))
              sec (sections/parse reply)
              status (if (str/starts-with? (str/lower-case (str/trim (str (get sec "STATUS")))) "done")
                       "done" "continue")
              ;; A pointer, not a plan: on csview a first task that was a whole probing
              ;; programme took the worker's entire budget. The worker has the request.
              next-task (some-> (or (first (sections/bullets (get sec "NEXT")))
                                    (some-> (get sec "NEXT") str/trim not-empty))
                                (clip (:repo-task-chars p)))
              curated (or (not-empty (parse-tasks (get sec "TASKS") (:max-tasks p))) (:tasks l))
              ;; Manager continued but named nothing: the first open task.
              next-task (or next-task
                            (when (= "continue" status)
                              (:desc (first (remove :done curated)))))
              gate (:gate l)
              counter (when (and (:ok gate) (= "samples" (:kind gate)))
                        (sections/counterexample sec))
              counter-fails? (boolean (and counter (check-counterexample ctx counter)))
              d (decide {:status status :next next-task
                         :gate (assoc gate :counterexample-fails counter-fails?)
                         :artifact? (artifact? ctx)
                         :prev-task (:task l)
                         :stall (:stall l)
                         :round round
                         :final? (:final l)
                         :green-revisions (:green-revisions l)
                         :have-best? (some? (journal/last-note conn run-id :team-solve-best))}
                        p
                        {:first-task (str/trim (prompt/prompt "team-solve-first-task"))
                         :fix-task (str/trim (prompt/prompt "team-solve-fix-task"))
                         :finalize (str/trim (prompt/prompt "team-solve-finalize"))})
              ;; About to ship a repository change: a reviewer reads the diff first.
              ;; A program graded on samples has no diff worth arguing over.
              reviewed (when (and (= :verify (:go d)) (not= "samples" (:kind gate))
                                  (not (:final l)) (not (:stopped l)))
                         (review! ctx l))
              reviews (cond-> (or (:reviews l) 0) (false? (:pass? reviewed)) inc)
              refused? (false? (:pass? reviewed))
              out-of-reviews? (and refused? (>= reviews (:max-reviews p)))
              d (cond
                  out-of-reviews? {:go :verify}
                  refused? {:go :work
                            :task (str (str/trim (prompt/prompt "team-solve-review-task"))
                                       "\n\n" (:findings reviewed))}
                  :else d)
              task (cond-> (:task d)
                     counter-fails? (str "\n\nThe program fails this checked case. Input:\n"
                                         (:input counter) "\nExpected:\n" (:expected counter)))
              l (save-ledger! ctx (cond-> {:tasks curated :task (or task (:task l)) :reviews reviews}
                                    out-of-reviews? (assoc :stopped (:findings reviewed))
                                    (:final? d) (assoc :final true)
                                    (:why d) (assoc :stuck-why (:why d))
                                    (and counter-fails? (:ok gate))
                                    (assoc :green-revisions (inc (or (:green-revisions l) 0)))))]
          (journal/note! conn run-id :team-solve-round
                         {:data {:round round :status status :go (name (:go d))
                                 :task (:task l) :why (:why d)
                                 :overrode-done (boolean (:overrode-done d))
                                 :review (when reviewed (if (:pass? reviewed) "pass" "refused"))
                                 :counterexample (when counter (if counter-fails? "fails" "false"))}})
          (position! ctx :manage l)
          (assoc data :team-solve/go (:go d)))))))

;; --- the worker -----------------------------------------------------------------

(defn- worker-turn-cap
  "How many turns one worker gets: what the run set for itself, else the policy's, which
  is larger when the clock is what limits the run."
  [ctx l p]
  (or (:worker-turns l)
      (if (gates/run-time-left ctx) (:worker-max-turns-timed p) (:worker-max-turns p))))

(defn- run-solver
  "One worker on one task: its own branch, a fresh context, a turn cap, and a board
  row it holds, so the tree's single-writer rule sees whose change this is. A crash
  is a result, so the manager and the supervisor can both read it."
  [{:keys [conn run-id] :as ctx} bid prob title turns]
  (let [task-id (tasks/create! conn {:title (clip title (:report-chars (policy))) :body prob
                                     :run-id run-id :contract prob})
        work-exists? (boolean (artifact? ctx))]
    (try
      (let [suffix (wf/prompt-text "roles/solver")
            rctx (wf/role-ctx ctx :solver)
            t (get-in (policy) [:temperatures :work])
            rctx (cond-> (assoc rctx :max-turns turns)
                   t (assoc-in [:llm-config :temperature] t))
            _ (runs/open-branch! conn run-id {:branch-id bid :problem prob :role :solver
                                              :prompt-suffix suffix})
            claimed (tasks/claim! conn task-id run-id bid)
            b (assoc (state/new-branch {:id bid :problem prob
                                        :messages (turn/initial-messages prob suffix :solver)})
                     :role :solver
                     ;; It reports to the manager, and a script decides whether
                     ;; the work passes, so `done` must always get its report
                     ;; through: the ship rungs refused it for its whole budget.
                     :advisory? true
                     ;; A worker after the first starts on what is already there.
                     :work-exists? work-exists?
                     :task {:id (:id claimed) :title (:title claimed)})
            out (myc/run-compiled (wf/compiled-manifest "solver") rctx {:branch b :turn 1})
            done? (= :done (:verdict out))]
        (if done? (tasks/close! conn task-id) (tasks/release! conn task-id bid))
        {:verdict (:verdict out)
         :answer (get-in out [:branch :final-answer])
         :reason (get-in out [:branch :inactive-reason])})
      (catch Throwable e
        (when (cancel/control-signal? e) (throw e))
        (try (tasks/release! conn task-id bid) (catch Throwable _ nil))
        {:verdict :error :reason (or (ex-message e) (str e))}))))

(defn- attempt-trace
  "The worker's last turns, for the cutoff summarizer to read."
  [{:keys [conn run-id]} bid chars]
  (let [rows (journal/branch-turns conn run-id bid)
        text (str/join "\n" (for [t (take-last 20 rows)]
                              (str "turn " (:turn t) " " (:tool_name t) ": "
                                   (clip (:result t) 400))))]
    (if (> (count text) chars) (subs text (- (count text) chars)) text)))

(cell/defcell :team-solve/work
  {:doc "WORKER: one task in a fresh context, assembled from the ledger only. A
        worker that finished rewrites the notes; one that was cut off does not,
        and a summarizer tells the manager how far it got. A worker that crashed
        also raises a signal."
   :effects [:net :db :fs :proc :run]
   :requires [:config :conn :run-id :root]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/worked :keyword]]}
  (fn [{:keys [config] :as ctx} data]
    (if (program? ctx)
      (let [l (program-work! ctx)]
        (assoc data :team-solve/worked (keyword (get-in l [:worker :verdict]))))
    (let [p (policy)
          l (ledger ctx)
          round (inc (or (:round l) 0))
          bid (str "W" round)
          samples? (= :samples (gate-kind ctx))
          prob (prompt/render "team-solve-work"
                              {:problem (:problem l)
                               :plan (:plan l)
                               :notes (:notes l)
                               :current (current-work ctx)
                               :task (:task l)
                               :samples samples?
                               :target (:artifact p)
                               :verify-cmd (get-in config [:run :verify-cmd])})
          l (save-ledger! ctx {:round round})
          _ (position! ctx :work l)
          tree-before (gitdiff/work-tree-id (:root ctx))
          r (run-solver ctx bid prob (:task l) (worker-turn-cap ctx l p))
          tree-after (gitdiff/work-tree-id (:root ctx))
          finished? (= :done (:verdict r))
          sec (sections/parse (:answer r))
          summary (if finished?
                    (or (not-empty (get sec "RESULT")) (clip (:answer r) (:report-chars p)))
                    (ask ctx :summarizer :cutoff
                         (prompt/prompt "team-solve-cutoff-system")
                         (prompt/render "team-solve-cutoff-user"
                                        {:task (:task l)
                                         :attempt (attempt-trace ctx bid (:cutoff-chars p))})))]
      (when (= :error (:verdict r))
        (signal! ctx :worker-crashed :work (:reason r)))
      (save-ledger! ctx (cond-> {:worker {:verdict (name (or (:verdict r) :unknown))
                                          :status (str/trim (str (get sec "STATUS")))
                                          :summary summary
                                          :reason (:reason r)
                                          :branch bid
                                          ;; Mechanical, so a report cannot talk past it.
                                          ;; Unknown without git, and then nothing is said.
                                          :tree-unchanged (boolean (and tree-before (= tree-before tree-after)))}
                                 :proposals (into (vec (:proposals l))
                                                  (remove #(= "none" (str/lower-case %))
                                                          (sections/bullets (get sec "NEXT"))))}
                          (and finished? (not-empty (get sec "NOTES")))
                          (assoc :notes (clip (get sec "NOTES") (:notes-chars p)))
                          finished?
                          (assoc :result summary)))
      (assoc data :team-solve/worked (or (:verdict r) :unknown))))))

;; --- the check ------------------------------------------------------------------

(cell/defcell :team-solve/test
  {:doc "The deterministic gate. Nothing here asks a model. Keeps the last passing
        work, counts rounds with an unchanged failure, and writes the captured
        result where the manager reads it."
   :effects [:proc :fs :db]
   :requires [:config :conn :run-id :root]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/passed :boolean]]}
  (fn [{:keys [conn run-id] :as ctx} data]
    (if (program? ctx)
      (assoc data :team-solve/passed (program-test! ctx (ledger ctx)))
    (let [l (ledger ctx)
          g (run-gate ctx)
          round (:round l)
          w (:worker l)
          cut? (not= "done" (:verdict w))
          stall (if (and (:ran g) (not (:ok g)) (:sig g) (= (:sig g) (:sig (:gate l))))
                  (inc (or (:stall l) 0))
                  0)
          attempt {:round round :task (clip (:task l) (:report-chars (policy)))
                   :worker (:verdict w) :gate (:summary g)}
          latest (str/trim (prompt/render "team-solve-worker-result"
                                          {:gate (:report g) :cut cut? :task (:task l)
                                           :status (:status w) :summary (:summary w)
                                           :tree-unchanged (:tree-unchanged w)}))
          l (save-ledger! ctx {:gate g :stall stall :latest latest
                               :attempts (conj (vec (:attempts l)) attempt)})]
      (when (and (:ran g) (:ok g)) (keep-best! ctx round))
      (journal/note! conn run-id :team-solve-gate
                     {:data (select-keys g [:kind :ran :ok :passed :total :summary])})
      (position! ctx :test l)
      (assoc data :team-solve/passed (boolean (:ok g)))))))

;; --- stuck ----------------------------------------------------------------------

(defn- supervisor-concluded
  "What a pass concluded after `seen` of them, or nil. A pass can end with a written
  verdict and no directive, which reaches nobody; a waiting team reads it as guidance
  rather than sit out its timeout."
  [{:keys [conn run-id]} seen]
  (some-> (drop seen (journal/notes conn run-id :oversight))
          last :notes str not-empty))

(defn- await-directive
  "Poll the supervisor's queue, bounded. {} on timeout."
  [{:keys [conn run-id] :as ctx} round wait-ms poll-ms]
  (let [seen (count (journal/notes conn run-id :oversight))]
    (loop [left wait-ms]
      (let [d (drain-directives ctx round)
            said (when-not (seq d) (supervisor-concluded ctx seen))]
        (cond
          (seq (select-keys d [:retask :stop :finish :guidance])) d
          said {:guidance said}
          (<= left 0) d
          :else (do (cancel/sleep! poll-ms) (recur (- left poll-ms))))))))

(cell/defcell :team-solve/unstick
  {:doc "The team cannot move by itself. Raise a signal, which wakes the supervisor,
        and wait a bounded time for what it says. It can retask or stop the team.
        If it says nothing, take the mechanical way out: a fresh attempt from a
        different technique, naming what failed. Past :max-unsticks, finalize.
        Silence from the supervisor can slow a stuck run but never hang it."
   :effects [:db :run]
   :requires [:config :conn :run-id]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/go :keyword]]}
  (fn [{:keys [conn run-id] :as ctx} data]
    (let [p (policy)
          l (ledger ctx)
          round (or (:round l) 0)
          why (or (:stuck-why l) "the team stopped making progress")
          n (inc (or (:unsticks l) 0))
          _ (position! ctx :unstick l)
          _ (signal! ctx :stuck :unstick
                     (str why ". Round " round ", last check: " (get-in l [:gate :summary])
                          ". Answer with intervene kind retask (the next task) or stop."))
          d (await-directive ctx round (:unstick-wait-ms p) (:unstick-poll-ms p))
          out (cond
                (:stop d) {:go :verify :by "supervisor" :stopped (:stop d)}
                (:finish d) {:go :verify :by "supervisor"}
                (:retask d) {:go :work :by "supervisor" :task (:retask d)}
                ;; Silence on a program is what their loop does on a repeated task.
                (and (program? ctx) (:guidance d)) {:go :work :by "supervisor" :task (:guidance d)}
                (program? ctx) {:go :work :by "method" :final? true}
                (> n (:max-unsticks p)) {:go :work :by "bound" :final? true
                                         :task (str/trim (prompt/prompt "team-solve-finalize"))}
                :else {:go :work :by (if (:guidance d) "supervisor" "fallback")
                       :task (str (str/trim (prompt/render "team-solve-switch"
                                                           {:why why
                                                            :attempts (:attempts l)}))
                                  (when-let [g (:guidance d)] (str "\n\nThe supervisor says: " g)))})]
      (save-ledger! ctx (cond-> {:unsticks n :stall 0 :task (or (:task out) (:task l))}
                          (:final? out) (assoc :final true)
                          (:stopped out) (assoc :stopped (:stopped out))))
      (journal/note! conn run-id :team-solve-unstick
                     {:data {:round round :why why :by (:by out) :go (name (:go out))}})
      (assoc data :team-solve/go (:go out)))))

;; --- verify ---------------------------------------------------------------------

(cell/defcell :team-solve/verify
  {:doc "The strict rerun. When the current work fails and earlier work passed,
        restore the last passing work and re-check. Pass ships with an honest
        answer; fail abandons with the reason, never a pretend completion. With no
        check available, work that exists ships and the answer says it is unverified."
   :effects [:proc :fs :db]
   :requires [:config :conn :run-id :root]
   :input  [:map [:branch :map]]
   :output [:map [:team-solve/final :keyword] [:verdict :keyword] [:branch :map]]}
  (fn [{:keys [conn run-id] :as ctx} {:keys [branch] :as data}]
    (let [l (ledger ctx)
          g0 (run-gate ctx)
          best (journal/last-note conn run-id :team-solve-best)
          restore? (and (:ran g0) (not (:ok g0)) (or (:artifact best) (:tree best)) true)
          _ (when restore? (restore! ctx best))
          g (if restore? (run-gate ctx) g0)
          pass? (if (:ran g) (:ok g) (artifact? ctx))
          ;; A stop is a verdict: the supervisor's, or a reviewer's that kept refusing.
          ;; A green check does not overrule one that read why it is green.
          pass? (and pass? (not (:stopped l)))
          final (if pass? :pass :fail)]
      (journal/note! conn run-id :team-solve-verify
                     {:data {:final (name final) :restored (boolean restore?)
                             :gate (:summary g) :rounds (:round l)
                             :unverified (not (:ran g))}})
      (position! ctx :verify (assoc l :gate g))
      (assoc data
             :team-solve/final final
             :verdict (if pass? :done :abandoned)
             :branch (if pass?
                       (assoc branch :final-answer
                              (str (or (:result l) (:summary g))
                                   (when restore? (str "\n\n" (prompt/prompt "team-solve-restored")))
                                   (when-not (:ran g) (str "\n\n" (str/trim (prompt/prompt "team-solve-unverified"))))))
                       (let [why (or (:stopped l) "The check never passed.")]
                         (assoc branch
                                :status :abandoned
                                :final-answer nil
                                :inactive-reason
                                (str/trim (prompt/render "team-solve-unsolved"
                                                         {:why why :gate (:summary g)})))))))))
