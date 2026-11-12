;; samizdat - a self-hosting agentic harness
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

(ns samizdat.agent.tools.shell
  "The shell tool.

  One call into samizdat.security.policy, which owns the permission engine,
  the scrubbed environment, and output redaction. See
  samizdat.agent.tools.base for the shared result helpers and the run-tool
  multimethod."
  (:require [clojure.string :as str]
            [samizdat.agent.actions :as actions]
            [samizdat.agent.files :as files]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.jobs :as jobs]
            [samizdat.prompt :as prompt]
            [samizdat.repl.route :as route]
            [samizdat.security.confine :as confine]
            [samizdat.security.policy :as policy]
            [samizdat.store.journal :as journal]))

(defn helper-hints
  "The code-mode helpers that replace programs `command` runs, as
  [{:program :helper}], from the `table` of program -> helper call
  (gates.edn :helper-hints, karamazov-6p51.2)."
  [table command]
  (vec (for [h (policy/command-heads command)
             :let [helper (get table h)]
             :when helper]
         {:program h :helper (str "`" helper "`")})))

(defn- code-mode?
  "Whether this branch's evals run in the project image with the helpers in it."
  [ctx]
  (and (:enabled? (route/bridge-policy))
       (:helpers (route/bridge-policy))
       (= :project (try (route/image-of ctx) (catch Throwable _ nil)))))

(defn- with-hint
  "`r` with a line naming the helpers its command could have been, when there
  are any and code mode is on."
  [r ctx]
  (let [hints (when (and (string? (:result r))
                         (= :allow (get-in r [:policy :effect]))
                         (code-mode? ctx))
                (helper-hints (gates/threshold :helper-hints) (base/arg ctx :command)))]
    (if (seq hints)
      (update r :result str "\n" (prompt/render "helper-hint" {:hints hints}))
      r)))

(defmethod base/run-tool "shell" [{:keys [branch] :as ctx}]
  ;; Every command faces the permission engine, runs under a scrubbed
  ;; environment, and its output is redacted before it returns — one call into
  ;; samizdat.security.policy, which owns all three. A denied or unapproved
  ;; command never spawns. The result's :category is what the cull guard
  ;; reads: a deny is :mechanics with :policy-refusal? (the branch did
  ;; nothing wrong, the harness declined — the refusal counter's business,
  ;; not the cull counter's), an :ask is :neutral (waiting on a human), and
  ;; a real command failure is :failure.
  (if-let [m (base/missing ctx :command)]
    (base/malformed branch m)
    ;; What the shell may read without asking: the project, the reference
    ;; roots the operator declared — read_file's boundary — and the build
    ;; caches, where a dependency's source is (karamazov-3vu1.3) — the
    ;; harness's own among them, readable though not writable (karamazov-1gbh).
    (let [home (System/getenv "HOME")
          began (System/currentTimeMillis)
          ;; The call's own timeout, capped (policy/shell-timeout).
          asked (some-> (base/arg ctx :timeout_ms) str str/trim parse-long)
          {:keys [default-ms max-ms]} policy/shell-timeout
          ctx (assoc ctx
                     :timeout-ms (min (or max-ms default-ms) (or asked default-ms))
                     :read-roots (concat [(or (:root ctx) ".")]
                                         (files/ctx-reference-roots ctx)
                                         (confine/build-caches home)
                                         (confine/harness-caches home)))
          ;; `background` starts it as a job of the run instead of waiting
          ;; for it — through the same gate (samizdat.agent.tools.jobs).
          r (if (jobs/background? ctx) (jobs/start! ctx) (policy/run-shell ctx))
          refused? (contains? #{:deny :ask} (get-in r [:policy :effect]))]
      ;; What the command led to, for the run's record of actions
      ;; (karamazov-oi16): a turn's own call and an eval's bridged `sh` alike.
      (when-let [{:keys [conn run-id turn]} (when (and (:conn ctx) (:run-id ctx)) ctx)]
        (try (journal/note! conn run-id :action
                            {:branch-id (:id branch) :turn turn
                             ;; Repeated in the data, which is what journal/notes reads.
                             :data (assoc (actions/note {:command (base/arg ctx :command)
                                                         :via (:via ctx)
                                                         :ms (- (System/currentTimeMillis) began)
                                                         :command-chars (:command-chars (gates/threshold :actions))}
                                                        (assoc r :policy-refusal? refused?))
                                          :branch-id (:id branch) :turn turn)})
             (catch Throwable _ nil)))
      (assoc (with-hint r ctx) :branch branch
             ;; A policy refusal is journalled as declined, like a phase
             ;; refusal, so the record can tell it from a command that ran
             ;; and failed.
             :policy-refusal? (or refused? (boolean (:policy-refusal? r)))))))
