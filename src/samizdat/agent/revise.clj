;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.revise
  "Backward revision of the memories a branch was shown (karamazov-4dn8.4).

  ReHarness (arXiv 2610.12668) reads past reflections into a task, then
  REVISES each one it used, grounded in that reflection's own source
  trajectory with the new outcome as feedback only, and found that step to
  be where the gain is (CL-Bench 0.217 normalized gain with it, 0.144
  without). samizdat already reads memories into every branch and keeps a
  lineage when one is reworded; what it lacked was the step that puts a
  memory back beside where it came from after a branch has acted on it.

  Mechanism only: which memories qualify, what the model is shown, how its
  answer is read and applied. Whether to revise at all, and after which
  endings, is the :memory/revise cell's decision under gates.edn
  :memory-revise; the words are prompts/memory-revise*.md. Never throws: a
  revision that fails must not stop a branch from finishing."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [samizdat.agent.reflect :as reflect]
            [samizdat.claims :as claims]
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.client :as llm]
            [samizdat.llm.message :as message]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]
            [samizdat.store.runs :as runs]))

(defn candidates
  "The memories `branch-id` was shown that can be revised: current, written
  by an EARLIER run, and carrying a source span to be revised against. Each
  as {:row :exposed}, in the order the branch first saw them, at most
  `max-per-branch`. A memory this run wrote has no other trajectory to be
  checked against; one with no source has nothing to ground a revision."
  [conn run-id branch-id {:keys [max-per-branch]}]
  (let [;; A version this run wrote carries the run id of the row it
        ;; restated, so run_id alone let one branch's rewording be revised
        ;; again by every later branch shown it. A rewording this run wrote,
        ;; and anything created since it started, is this run's own.
        revised (into #{} (keep :new-id) (journal/notes conn run-id :memory-revised))
        started (:started_at (runs/get-run conn run-id))
        this-run? (fn [row]
                    (or (= run-id (:run_id row))
                        (contains? revised (:id row))
                        (and started (pos? (compare (str (:created_at row)) (str started))))))]
    (->> (journal/exposed conn run-id branch-id)
         (sort-by (comp #(or % 0) :turn val))
         (keep (fn [[id seen]]
                 (let [row (knowledge/get-by-id conn id)]
                   (when (and row (= 1 (:current row))
                              (not (this-run? row))
                              (:source_branch row) (:source_turn_from row))
                     {:row row :exposed seen}))))
         (take (or max-per-branch 0))
         vec)))

(defn parse-verdict
  "The reviser's reply as {:verdict :keep|:revise|:retire :text}, or nil when
  it is none of them — and nil changes nothing."
  [text]
  (let [t (message/answer text)]
    (when-let [[_ verb rest] (re-find (re-pattern (lexicon/wordlist :revision-verdict)) t)]
      (let [verdict (keyword (str/lower-case verb))
            body (str/trim (first (str/split-lines (str rest))))]
        (cond
          (= :keep verdict) {:verdict :keep}
          (str/blank? body) nil
          :else {:verdict verdict :text body})))))

(defn- tokens
  "The distinctive words of `text`: identifiers, paths, commands and numbers
  of at least `min-token` characters, lower-cased."
  [text min-token]
  (into #{}
        (comp (map str/lower-case)
              (map #(str/replace % #"[.,;:)]+$" ""))
              (filter #(>= (count %) min-token)))
        (re-seq #"[A-Za-z0-9_./:-]+" (str text))))

(defn grounded
  "Whether `revised` (a rewording of `old`) is supported by its `source`
  turns rather than lifted from the new branch's `outcome` turns
  (karamazov-4dn8.5). {:ok? true}, or {:ok? false :why :contradicted|:leaked
  :tokens} — :contradicted when the source record contradicts it
  (samizdat.claims), :leaked when more than `max-leaked` of the words it
  adds appear in the outcome and nowhere in the source. ReHarness measures
  exactly this leakage (0.8-2.1%); a revision that copies the later session's
  answer is the later session's memory, not a corrected old one.

  Only catches what it encodes. A rewording that adds no new words passes,
  and the lineage keeps the old wording either way."
  [old revised source outcome {:keys [min-token max-leaked]}]
  (let [turn-text (fn [ts] (str/join " " (map #(str (:args %) " " (:result %)) ts)))
        hits (claims/contradicted-by-record revised source (distinct (keep :tool_name source)))
        added (remove (tokens old min-token) (tokens revised min-token))
        in-source (tokens (turn-text source) min-token)
        in-outcome (tokens (turn-text outcome) min-token)
        leaked (vec (sort (filter #(and (in-outcome %) (not (in-source %))) added)))]
    (cond
      (seq hits) {:ok? false :why :contradicted :tokens (mapv :tool hits)}
      (> (count leaked) max-leaked) {:ok? false :why :leaked :tokens leaked}
      :else {:ok? true})))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- outcome-turns
  "What this branch did after it was first shown the memory: its turns from
  that turn on, at most `n`."
  [conn run-id branch-id from n]
  (->> (journal/branch-turns conn run-id branch-id)
       (filter #(>= (or (:turn %) 0) (or from 0)))
       (take-last n)
       vec))

(defn- source-turns
  "The turns memory `row` was written from."
  [conn {:keys [run_id source_branch source_turn_from source_turn_to]}]
  (filterv #(<= source_turn_from (:turn %) source_turn_to)
           (journal/branch-turns conn run_id source_branch)))

(defn- ask
  [{:keys [conn run-id llm-adapter llm-config]} branch {:keys [row exposed]} policy]
  (let [answer (llm/chat llm-adapter llm-config
                         [{:role "system" :content (prompt/prompt "memory-revise")}
                          {:role "user"
                           :content (prompt/render "memory-revise-input"
                                                   {:memory (:content row)
                                                    :kind (:kind row)
                                                    ;; The latest of a span that can be a
                                                    ;; whole branch, so one memory cannot
                                                    ;; outgrow the window.
                                                    :source (mapv reflect/turn-line
                                                                  (cond->> (source-turns conn row)
                                                                    (:source-turns policy) (take-last (:source-turns policy))))
                                                    :status (some-> (:status branch) name)
                                                    :answer (some-> (:final-answer branch) str (clip (:answer-chars policy)))
                                                    :outcome (mapv reflect/turn-line
                                                                   (outcome-turns conn run-id (:id branch)
                                                                                  (:turn exposed)
                                                                                  (:feedback-turns policy)))})}]
                         {:temperature 0.0})]
    (journal/record-side-call! conn run-id {:branch-id (:id branch) :kind :revision
                                            :model (:model llm-config) :usage (:usage answer)})
    answer))

(defn- apply!
  [conn run-id branch row {:keys [verdict text]}]
  (case verdict
    :revise (knowledge/restate! conn (:id row) text
                                {:reason "revised"
                                 :cause (prompt/render "memory-revised-cause"
                                                       {:run run-id :branch (:id branch)
                                                        :status (some-> (:status branch) name)})})
    :retire (knowledge/retire! conn (:id row) {:reason (str "revision: " text)})
    nil))

(defn revise!
  "Revise each candidate memory `branch` was shown. Returns
  [{:id :verdict :new-id}], one per memory asked about.

  A reduce, not a for: each step makes a model call, which parks, and a park
  inside a lazy body is a hang on a fiber (ebb ADR-001)."
  [{:keys [conn run-id] :as ctx} branch policy]
  (try
    (reduce
     (fn [acc {:keys [row exposed] :as c}]
       (let [answer (ask ctx branch c policy)
             parsed (parse-verdict (:content answer))
             ;; A rewording has to come from the source, not the outcome
             ;; (karamazov-4dn8.5); one that does not is refused and the
             ;; memory stays as it was.
             check (when (and (= :revise (:verdict parsed)) (:grounding policy))
                     (grounded (:content row) (:text parsed)
                               (source-turns conn row)
                               (outcome-turns conn run-id (:id branch) (:turn exposed)
                                              (:feedback-turns policy))
                               (:grounding policy)))
             refused? (and check (not (:ok? check)))
             new-id (when-not refused? (apply! conn run-id branch row parsed))
             result (cond-> {:id (:id row)
                             :verdict (if refused? "refused" (some-> (:verdict parsed) name))
                             :new-id (when (and (not refused?) (= :revise (:verdict parsed))) new-id)}
                      refused? (assoc :why (name (:why check)) :tokens (:tokens check)))]
         (journal/note! conn run-id :memory-revised
                        {:branch-id (:id branch)
                         :data (assoc result :tokens (get-in answer [:usage :total-tokens]))})
         (conj acc result)))
     []
     (candidates conn run-id (:id branch) policy))
    (catch Throwable e
      (log/warn "memory revision failed:" (ex-message e))
      [])))
