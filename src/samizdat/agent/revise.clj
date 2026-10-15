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
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.client :as llm]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]
            [samizdat.store.knowledge :as knowledge]))

(defn candidates
  "The memories `branch-id` was shown that can be revised: current, written
  by an EARLIER run, and carrying a source span to be revised against. Each
  as {:row :exposed}, in the order the branch first saw them, at most
  `max-per-branch`. A memory this run wrote has no other trajectory to be
  checked against; one with no source has nothing to ground a revision."
  [conn run-id branch-id {:keys [max-per-branch]}]
  (->> (journal/exposed conn run-id branch-id)
       (sort-by (comp #(or % 0) :turn val))
       (keep (fn [[id seen]]
               (let [row (knowledge/get-by-id conn id)]
                 (when (and row (= 1 (:current row))
                            (not= run-id (:run_id row))
                            (:source_branch row) (:source_turn_from row))
                   {:row row :exposed seen}))))
       (take (or max-per-branch 0))
       vec))

(defn parse-verdict
  "The reviser's reply as {:verdict :keep|:revise|:retire :text}, or nil when
  it is none of them — and nil changes nothing."
  [text]
  (let [t (str/trim (str text))]
    (when-let [[_ verb rest] (re-find (re-pattern (lexicon/wordlist :revision-verdict)) t)]
      (let [verdict (keyword (str/lower-case verb))
            body (str/trim (first (str/split-lines (str rest))))]
        (cond
          (= :keep verdict) {:verdict :keep}
          (str/blank? body) nil
          :else {:verdict verdict :text body})))))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- outcome-lines
  "What this branch did after it was first shown the memory: its turns from
  that turn on, at most `n`, as the reflector sees turns."
  [conn run-id branch-id from n]
  (->> (journal/branch-turns conn run-id branch-id)
       (filter #(>= (or (:turn %) 0) (or from 0)))
       (take-last n)
       (mapv reflect/turn-line)))

(defn- ask
  [{:keys [conn run-id llm-adapter llm-config]} branch {:keys [row exposed]} policy]
  (let [answer (llm/chat llm-adapter llm-config
                         [{:role "system" :content (prompt/prompt "memory-revise")}
                          {:role "user"
                           :content (prompt/render "memory-revise-input"
                                                   {:memory (:content row)
                                                    :kind (:kind row)
                                                    :source (reflect/source-lines conn row)
                                                    :status (some-> (:status branch) name)
                                                    :answer (some-> (:final-answer branch) str (clip (:answer-chars policy)))
                                                    :outcome (outcome-lines conn run-id (:id branch)
                                                                            (:turn exposed)
                                                                            (:feedback-turns policy))})}]
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
     (fn [acc {:keys [row] :as c}]
       (let [answer (ask ctx branch c policy)
             parsed (parse-verdict (:content answer))
             new-id (apply! conn run-id branch row parsed)
             result {:id (:id row) :verdict (some-> (:verdict parsed) name)
                     :new-id (when (= :revise (:verdict parsed)) new-id)}]
         (journal/note! conn run-id :memory-revised
                        {:branch-id (:id branch)
                         :data (assoc result :tokens (get-in answer [:usage :total-tokens]))})
         (conj acc result)))
     []
     (candidates conn run-id (:id branch) policy))
    (catch Throwable e
      (log/warn "memory revision failed:" (ex-message e))
      [])))
