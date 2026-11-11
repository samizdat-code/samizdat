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

(ns samizdat.agent.handoff
  "WHAT A BRANCH IS TOLD ABOUT A TURN THAT DID NOT FINISH (karamazov-o4wm.2).

  A turn ends three ways short of its journal row: the beam cancels it at
  the deadline, the process dies under it, or it never got past the model
  call. The forfeit kept the pre-turn branch, so the call the model made was
  gone from its own tape, no row existed, and the only word it got was
  `abandoned`. If that call was a mutating shell — a deploy, a push, a test
  run that writes — its effect is unknown and the model no longer knows what
  it called, so the natural next move is to make the call again, which is
  the one unsafe thing. Floatboat's turn runner states the invariant as
  `interruption does not roll back committed side effects` and hands the
  next turn the tool, its status and its side-effect state; this is that.

  Mechanism only. What the journal holds about the turn — a row, a dispatch
  note the loop wrote before running the tool, or nothing — decides the
  shape; the retry-safe allowlist (wordlists.edn) decides the side-effect
  state; every sentence is prompts/turn-interrupted.md and
  prompts/uncertain-effect.md."
  (:require [samizdat.agent.toolerr :as toolerr]
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.message :as message]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]))

(defn clip
  "`s` cut to `chars`, saying how much was dropped; `s` when it fits. nil
  stays nil, so an absent value is recorded as absent."
  [s chars]
  (when (some? s)
    (let [s (str s)]
      (if (<= (count s) chars)
        s
        (str (subs s 0 chars) "… [" (- (count s) chars) " more chars]")))))

(defn clip-args
  "A tool's args with every string value clipped to `chars`. The shape
  survives — the handoff names what was called, not what a 60k write_file
  carried — and a non-map stays as it was."
  [args chars]
  (when (map? args)
    (into {} (map (fn [[k v]] [k (if (string? v) (clip v chars) v)])) args)))

(defn side-effect
  "What re-running `tool` risks: `:no-effect` for the retry-safe allowlist —
  pure reads, which return the same answer twice — and `:unknown` for
  everything else, including a shell whose command only read: a timed-out
  shell may have run to completion, and nothing here can know."
  [tool read-only]
  (if (toolerr/retry-safe? (str tool) (or read-only #{})) :no-effect :unknown))

(defn- note
  [{:keys [why seconds tool committed side-effect]}]
  (prompt/render "turn-interrupted"
                 {:deadline (= :deadline why)
                  :seconds seconds
                  :tool tool
                  :committed (boolean committed)
                  :unknown (= :unknown side-effect)
                  :no-effect (= :no-effect side-effect)
                  :uncertain (when (= :unknown side-effect)
                               (prompt/render "uncertain-effect" {:tool tool}))}))

(defn messages
  "The messages appended to a branch whose turn `turn` did not finish.

  `row`       the turn's journal row, when the tool ran and was recorded
              before the turn was cut short: the branch gets what it said,
              what came back, and word that the turn stopped there.
  `dispatch`  the dispatch note, when the tool was in flight: the branch gets
              the call it made and its side-effect state — unknown, or
              no-effect for a read it may simply make again.
  neither     a deadline that landed during the model call keeps the plain
              deadline message; a crash with nothing on record adds nothing.

  `why` is :deadline or :crash; `seconds` the deadline. Every message is
  stamped with `turn` so compaction can digest it from its row where one
  exists. Pure."
  [{:keys [why seconds turn row dispatch read-only]}]
  (let [meta (when turn {:turn turn})
        msg (fn [role content] (merge {:role role :content content} meta))]
    (cond
      row
      (cond-> []
        (seq (:assistant_text row)) (conj (msg "assistant" (:assistant_text row)))
        true (conj (msg "user" (str (message/frame-result (:tool_name row) (:result row))
                                    "\n\n"
                                    (note {:why why :seconds seconds
                                           :tool (:tool_name row) :committed true})))))

      dispatch
      (cond-> []
        (seq (:said dispatch)) (conj (msg "assistant" (:said dispatch)))
        true (conj (msg "user" (note {:why why :seconds seconds
                                      :tool (:tool dispatch)
                                      :side-effect (side-effect (:tool dispatch)
                                                                read-only)}))))

      (= :deadline why)
      [(msg "user" (str "[harness] " (prompt/render "turn-deadline"
                                                    {:seconds seconds})))]

      :else [])))

(defn forfeit!
  "The messages a branch forfeiting `turn` at a `seconds` deadline gets, and
  the forfeit on record. Reads the journal for the turn's row, else its
  dispatch note; without a run to read, or if the read fails, the plain
  deadline message — a forfeit must never throw, the beam is mid-round."
  [conn run-id branch turn seconds]
  (let [read-only (lexicon/wordlist :retry-safe-tools)
        {:keys [row dispatch]}
        (try
          (when (and conn run-id)
            (let [row (journal/turn-row conn run-id (:id branch) turn)]
              {:row row
               :dispatch (when-not row
                           (journal/dispatch-at conn run-id (:id branch) turn))}))
          (catch Throwable _ nil))
        tool (or (:tool_name row) (:tool dispatch))
        state (cond row :committed
                    dispatch (side-effect tool read-only))]
    (when (and conn run-id)
      (try
        (journal/record-forfeit! conn run-id
                                 {:branch-id (:id branch) :turn turn
                                  :data (cond-> {:seconds seconds}
                                          tool (assoc :tool tool
                                                      :side-effect (name state)))})
        (catch Throwable _ nil)))
    (messages {:why :deadline :seconds seconds :turn turn
               :row row :dispatch dispatch :read-only read-only})))

(defn stage-error
  "The messages a branch gets when its turn threw inside the harness: what was
  in flight, as a crash reports it, and what broke. Reads like `forfeit!` and
  for the same reason must never throw, the beam is mid-round."
  [conn run-id branch turn why]
  (let [{:keys [row dispatch]}
        (try
          (when (and conn run-id)
            (let [row (journal/turn-row conn run-id (:id branch) turn)]
              {:row row
               :dispatch (when-not row
                           (journal/dispatch-at conn run-id (:id branch) turn))}))
          (catch Throwable _ nil))
        said (try (messages {:why :crash :turn turn :row row :dispatch dispatch
                             :read-only (lexicon/wordlist :retry-safe-tools)})
                  (catch Throwable _ []))
        text (try (prompt/render "turn-stage-error" {:why why})
                  (catch Throwable _ (str why)))]
    (if (= "user" (:role (peek (vec said))))
      (update (vec said) (dec (count said)) update :content str "\n\n" text)
      (conj (vec said) {:role "user" :content text :turn turn}))))

(defn after-crash
  "The messages a resumed branch gets for the call in flight when the
  process died: the dispatch note no turn row settled, or nil. One the beam
  had already forfeited is worded as the deadline it was, since that is
  what happened to it."
  [conn run-id branch-id]
  (when-let [d (journal/in-flight-dispatch conn run-id branch-id)]
    (messages {:why (if (:forfeited? d) :deadline :crash)
               :seconds (:seconds d)
               :turn (:turn d)
               :dispatch d
               :read-only (lexicon/wordlist :retry-safe-tools)})))
