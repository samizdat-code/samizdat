;; samizdat - a claim-first verification harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.digest
  "read_digest — a read that returns an ANSWER instead of a page.

  Most of what a branch does with a large file is not reasoning about it: it
  pages through five screens to answer one question, and every page is
  billed at the branch's rate and resent on every following turn. This tool
  sends the files to a reader — the :reader role's model when config :roles
  assigns one, the branch's own otherwise — with the question,
  and hands back only the bullets. The files never enter the branch's
  context, so a follow-up question costs the reader again and the branch
  nothing (karamazov-b76m, after Spotify's shunt).

  Mechanism only. WHEN a read should be a digest is phases.edn's rule
  (files/large-untargeted-read?), the numbers are gates.edn :digest, and
  every word the reader is told is prompts/digest.md.

  Two things stay with the branch, on purpose. Editing: a digest is not text
  to patch against — unless the branch asks for anchors, in which case every
  line the reader sees carries its `line:hash`, and a bullet that cites one
  is a valid `patch` address. And judgement: the reader finds what it is
  asked for and stops; it is not asked to debug, and what it answers enters
  the run as a claim like any other."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.config :as config]
            [samizdat.hashline :as hashline]
            [samizdat.inference.budget :as inference-budget]
            [samizdat.inference.frame :as frame]
            [samizdat.llm.message :as message]
            [samizdat.llm.registry :as registry]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]))

(defn- msg [vars] (prompt/render "digest-tool" vars))

(defn reader
  "The adapter and config a digest runs on: the :reader role's when one is
  assigned, else the branch's own — still a saving, of context if not of
  price."
  [{:keys [config llm-adapter llm-config]}]
  (if-let [llm (config/role-llm config llm-config :reader)]
    {:adapter (registry/adapter-for (:provider llm)) :config llm :role :reader}
    {:adapter llm-adapter :config llm-config :role :branch}))

(defn- paths-of [ctx]
  (let [p (base/arg ctx :paths)]
    (cond (nil? p) []
          (string? p) [p]
          (sequential? p) (mapv str p)
          :else [(str p)])))

(defn material
  "The files as the reader sees them: each wrapped in a <file path=…> element
  so the boundaries are unambiguous, anchored when asked, and the whole
  clipped at `cap` characters with a note saying how much was left out — the
  reader must not guess at what it did not see. {:text :chars :clipped}."
  [files anchors? cap]
  (let [render (fn [{:keys [path content]}]
                 (str "<file path=\"" path "\">\n"
                      (if anchors?
                        (hashline/render content 1 (count (hashline/content-lines content)))
                        content)
                      "\n</file>"))
        full (str/join "\n\n" (map render files))
        chars (count full)]
    (if (<= chars cap)
      {:text full :chars chars :clipped 0}
      {:text (str (subs full 0 cap) "\n" (msg {:clipped (- chars cap) :cap cap}))
       :chars chars :clipped (- chars cap)})))

(defn- turns-of [ctx]
  (let [t (base/arg ctx :turns)]
    (->> (cond (nil? t) [] (sequential? t) t :else [t])
         (keep #(cond (number? %) (long %) :else (parse-long (str %))))
         vec)))

(defn- turn-files
  "The branch's own earlier turns' results as the reader's material
  (karamazov-05z0.15): what a clipped result was before the clip, so it can
  be questioned whole. {:files [...]} or {:missing [turns]}."
  [{:keys [conn run-id branch]} turns]
  (let [rows (for [t turns]
               [t (when (and conn run-id)
                    (journal/branch-turn conn run-id (:id branch) t))])
        missing (vec (for [[t r] rows :when (nil? r)] t))]
    (if (seq missing)
      {:missing missing}
      {:files (vec (for [[t r] rows]
                     {:path (str "turn " t " " (:tool_name r))
                      ;; The whole result where it was clipped (v45).
                      :content (str (or (not-empty (:full_result r)) (:result r)))}))})))

(defn- digest-of
  "The reader's answer to `question` over `files` ({:path :content}), as the
  tool's result, with the saving and the bill journaled."
  [{:keys [branch conn run-id] :as ctx} files question policy]
  (let [anchors? (boolean (base/arg ctx :anchors))
        paths (mapv :path files)
        {:keys [text chars clipped]} (material files anchors? (:input-chars policy))
        {:keys [adapter config role]} (reader ctx)
        messages [{:role "system"
                   :content (prompt/render "digest" {:budget-chars (:budget-chars policy)
                                                     :anchors anchors?})}
                  {:role "user" :content (str question "\n\n" text)}]]
    (try
      (let [{:keys [max-calls max-tokens max-depth output-tranche]}
            (gates/threshold :inference)
            result (frame/infer
                    (assoc ctx :llm-adapter adapter :llm-config config)
                    {:task question
                     :views [{:label "material" :content text}]
                     :schema :text
                     :messages-fn (fn [_] messages)
                     :role role :kind :digest
                     :budget (inference-budget/budget
                              {:calls max-calls :tokens max-tokens :depth max-depth
                               :max-tranche output-tranche})})
            _ (when (not= :complete (:status result))
                (throw (ex-info "inference incomplete" result)))
            answer (message/strip-think-blocks (str (:value result)))
            ceiling (* 2 (:budget-chars policy))
            answer (if (> (count answer) ceiling)
                     (str (subs answer 0 ceiling) "\n"
                          (msg {:clipped-answer (- (count answer) ceiling)
                                :ceiling ceiling}))
                     answer)]
        (when (and conn run-id)
          (journal/note! conn run-id :digest
                         {:branch-id (:id branch)
                          :data {:paths paths :chars-in chars :clipped clipped
                                 :role role :model (:model config)}}))
        (base/ok branch (if (str/blank? answer) (msg {:empty true}) answer)
                 :digest {:paths paths :chars-in chars :role role
                          :trace-id (:trace-id result)}))
      (catch Throwable e
        (log/warn "read_digest: the reader failed:" (ex-message e))
        (base/unavailable branch "the reader" e)))))

(defn read-digest
  "Answer `question` about `paths` through the reader. The tool result is the
  reader's bullets, :neutral — a digest establishes nothing, like a read —
  and a :digest note on the run's journal records what the branch did not
  have to read and what the answer cost."
  [{:keys [branch root conn run-id] :as ctx}]
  (let [paths (paths-of ctx)
        turns (turns-of ctx)
        question (str (base/arg ctx :question))
        refs (files/ctx-reference-roots ctx)
        policy (gates/digest-policy)
        from-turns (when (and (empty? paths) (seq turns)) (turn-files ctx turns))]
    (cond
      (:missing from-turns)
      (base/malformed branch (msg {:no-turns true :turns (str/join ", " (:missing from-turns))}))
      (and from-turns (str/blank? question)) (base/malformed branch (msg {:needs-question true}))
      from-turns (digest-of ctx (:files from-turns) question policy)
      (empty? paths) (base/malformed branch (msg {:needs-paths true}))
      (str/blank? question) (base/malformed branch (msg {:needs-question true}))
      :else
      (let [resolved (for [p paths]
                       {:path p :abs (files/resolve-read! ctx refs p)})
            ;; EVERY path it cannot read, named at once (karamazov-0e2c.13):
            ;; naming the first sent the model back one path at a time.
            outside (not-empty (remove :abs resolved))
            missing (not-empty (filter #(and (:abs %) (not (fs/exists? (:abs %)))) resolved))
            ;; A directory was slurped, and the branch read the host's
            ;; "(Is a directory)" — named here, with the move that works.
            directory (first (filter #(and (:abs %) (.isDirectory (java.io.File. (str (:abs %)))))
                                     resolved))]
        (cond
          outside (base/malformed branch (msg {:outside-root true :path (str/join ", " (map :path outside))}))
          missing (base/malformed branch (msg {:missing true :path (str/join ", " (map :path missing))}))
          directory (base/malformed branch (msg {:directory true :path (:path directory)}))
          :else
          (digest-of ctx (for [{:keys [path abs]} resolved] {:path path :content (slurp abs)})
                     question policy))))))

(defmethod base/run-tool "read_digest" [ctx] (read-digest ctx))
