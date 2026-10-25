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

(ns samizdat.llm.message
  "Message shaping shared by every adapter.

  Two jobs, both of which have to happen the same way for every provider or
  the harness's context management differs by which model it is talking to."
  (:require [clojure.string :as str]
            [samizdat.lexicon :as lexicon]
            [samizdat.tape :as tape]))

(defn strip-think-blocks
  "Remove <think>…</think> from content.

  Applied to PRIOR assistant turns before they go back over the wire. Both
  DeepSeek and Zhipu document that `reasoning_content` must not be fed back on
  later turns — the model regenerates its thinking each turn — and
  accumulating it burns context for nothing. Tolerant of nesting and of an
  unmatched open tag."
  [content]
  (-> (or content "")
      (str/replace #"(?s)<think>.*?</think>" "")
      str/trim))

(defn answer
  "What a reply SAID, with its reasoning removed: the text a side call parses
  or keeps.

  client/chat folds a reasoning model's thinking into :content as a <think>
  block, because the loop's fence parser has to find a tool call wherever the
  model put it. Every other caller wants the answer alone — a judge's verdict,
  a fold's summary, a reflection's sections — and one that reads :content
  whole reads the scratchpad as part of it.

  Only the reasoning that LEADS the reply is removed — where merge-reasoning
  puts it and where an inline-thinking model writes it. A tag later in the
  text is the answer's own: a summary or a memory about this very code names
  it, and cutting there destroyed the rest.

  AN UNTERMINATED LEADING BLOCK IS ALL REASONING: a reply cut off mid-thought
  never got to answering, and empty is the honest result."
  [content]
  (loop [s (str/triml (str content))]
    (if (str/starts-with? s "<think>")
      (if-let [i (str/index-of s "</think>")]
        (recur (str/triml (subs s (+ i (count "</think>")))))
        "")
      (str/trim s))))

(defn merge-reasoning
  "Fold a provider's separate reasoning stream into the visible content with
  <think> framing.

  This is what lets one fence parser work across providers: a model that emits
  its tool call inside its reasoning is handled identically to one that emits
  it in the content, because after this they are the same string."
  [content reasoning]
  (let [c (or content "") r (or reasoning "")]
    (cond
      (and (seq r) (seq c)) (str "<think>" r "</think>\n" c)
      (seq r) (str "<think>" r "</think>")
      :else c)))

(defn default-keep-pairs
  "How many recent turns stay verbatim when a branch's history is compacted.

  This is the branch's working memory: what it just tried, what the engine
  said, and the goal state it is mid-way through.

  `:context-budget :keep-pairs`, not a constant. `infer/render` already passes
  the budget's value in, so a constant here was a SECOND answer to the same
  question that applied on exactly the path nobody was watching — a direct
  caller passing no opts — and disagreed with the first as soon as anyone
  retuned the table."
  []
  (lexicon/budget :keep-pairs))

(defn default-compaction-threshold
  "Total content characters before compaction engages.

  Deliberately high. A 19-turn branch carries about 26,000 characters and does
  not need this; the longest branch on record is 86 turns and about 211,000.
  Compaction is for the tail, and a branch that never reaches it is sent
  byte-identical messages to what it would have been sent before.

  `:context-budget :compaction-chars`, for the reason above."
  []
  (lexicon/budget :compaction-chars))

(defn default-compaction-batch
  "How many exchanges the compaction frontier moves by at once.

  Measured live (karamazov-pdes, run 5e78b96a): once a branch had more than
  `keep-pairs` exchanges, EVERY turn rewrote history behind the tail, because
  the frontier advanced one exchange per turn and the whole verbatim window
  behind it — a quarter of every request — was re-prefilled on every call.
  In steps, the window floats between keep-pairs and keep-pairs+batch-1 and
  batch-1 turns in every batch send a byte-identical history.

  `:context-budget :compaction-batch`, for the reason `default-keep-pairs`
  gives."
  []
  (lexicon/budget :compaction-batch))

(def ^:private frame-size
  "Messages at the head of a tape that are never compaction candidates: the
  system prompt and the problem statement.

  Not a tunable — it is the SHAPE of a tape, fixed by `loop/seed-messages`,
  and the two things whose byte-stability the whole prefix cache rests on
  (RFC-004's caching rule). Named so the guard below reads as what it is
  rather than as a magic 3."
  2)

(defn- digest-line
  "One line for a turn that has been unloaded: what was tried, how it came out.

  The minimum a branch needs about its own distant past. Anything more — the
  encoding, the engine's full output, the reasoning — is in the journal, and
  confirmed results are in the settled-state block with ids to fetch."
  [{:keys [turn tool category error]}]
  (str "t" turn " " (or tool "?") " → " (name (or category :neutral))
       (when (seq error)
         (let [e (first (str/split-lines (str error)))]
           (str ": " (if (> (count e) 90) (str (subs e 0 90) "…") e))))))

(def ^:private compactable-roles
  "The roles compaction may rewrite here. Both, unlike llm-repl's
  assistant-only default, because a \"user\" message in this harness is a tool
  result rather than a human prompt — and tool results are the bulk of a long
  branch's context. The FRAME (system prompt, problem statement) is excluded
  by index, not by role."
  #{"assistant" "user"})

(def unloaded-marker
  "The marker every compacted message carries, so the model can tell a summary
  from something that was actually said.

  LEADS the line, and that position is the fix for a real failure. It used to
  trail — `t69 cell → neutral [unloaded]` — and on a 119-turn supervisor
  branch, whose context was by then almost entirely these lines standing in
  for its OWN past turns, the model concluded that a digest line is what an
  assistant writes here and emitted eight of them in a row instead of a tool
  call (karamazov-068). A model reads left to right: with the marker last,
  everything it sees first is imitable content. With the marker first, the
  line announces itself as harness bookkeeping before it says anything else.

  Still deliberately TERSE. It repeats on every compacted message, so a long
  sentence here is a per-message tax on the very context compaction exists to
  shrink. Where the detail went, and how to get it back with `fetch_turn`,
  belongs in the system prompt, which says it once. Constant, so a compacted
  message never changes again once written."
  "[unloaded] ")

(defn unloaded?
  "Whether `s` is (or begins as) one of the harness's own compaction digests.

  The loop uses this to recognize a reply that is nothing but an imitation of
  the marker, which needs a different complaint from an ordinary missing
  fence — telling a model that copied our bookkeeping to 'emit a tool call'
  produces another copy (karamazov-068)."
  [s]
  (str/includes? (str s) (str/trim unloaded-marker)))

(defn- self-summary
  "A message summarised from its OWN content: the first non-blank line,
  bounded, plus the pointer.

  The fallback when no turn record can be attributed to the message. It is
  never a lie about which turn a message was — which is the failure mode the
  positional guess had, since a provider error or a no-call turn appends
  messages without appending a turn row, so the k-th message is not the k-th
  turn."
  [{:keys [content]}]
  (let [line (or (first (remove str/blank? (str/split-lines (str content)))) "")
        line (str/trim line)]
    (str unloaded-marker
         (if (> (count line) 90) (str (subs line 0 90) "…") line))))

(defn- replacement-for
  "What an unloaded message becomes: its turn's digest where the message
  carries a turn stamp and that turn is on the log, else a summary of its own
  content."
  [m turns-by-number]
  (if-let [t (get turns-by-number (:turn m))]
    (str unloaded-marker (digest-line t))
    (self-summary m)))

;; The per-turn context block's markers (karamazov-05z0.2). Each regenerated
;; part — task, memories, inbox, failures, ... — is wrapped in them, so a
;; stale copy can be found and dropped; a part shown once is left outside.
(def context-open "<!--turn-context-->")
(def context-close "<!--/turn-context-->")

(def ^:private context-re
  (re-pattern (str "(?s)\\n*" (java.util.regex.Pattern/quote context-open)
                   ".*?" (java.util.regex.Pattern/quote context-close))))

(defn- strip-context-before
  "Every turn-context block in the verbatim messages before index `end`
  dropped. Compacted messages are already a digest line."
  [messages end]
  (into []
        (map-indexed (fn [i m]
                       (if (and (>= i frame-size) (< i end) (not (:compacted? m)))
                         (update m :content #(str/replace (str %) context-re ""))
                         m)))
        messages))

(defn compaction-plan
  "Compact a branch's older turns IN PLACE, so the array's shape never changes.

  A branch's context is its own narrative, and past a certain length most of
  it is prose it will never consult again — while the part it genuinely needs,
  which approaches are already spent, is buried in that prose. This inverts
  that: recent turns stay whole, older ones become one line each.

  WHY IN PLACE. The previous version appended its digest to the PROBLEM
  message, to keep the conversation strictly alternating after it. That works,
  but it rewrites message 1 every time compaction fires, so the shared prefix
  changes on every turn and the upstream prompt cache is invalidated from
  index 1 onward — every turn re-prefills the whole conversation. Replacing
  each aged-out message's content in place keeps roles, order and count
  identical, so alternation holds with no provider needing to be forgiving AND
  the prefix stays stable: each message is rewritten once, ever, and the
  prefix before the newest rewrite is byte-identical from turn to turn. This
  is llm-repl's chat-memory design; see samizdat.tape for the primitives and
  docs/RFCS/RFC-004-tape-and-inference.md for the argument.

  ONE ATTEMPT PER MESSAGE. A replacement is accepted only inside the
  compression band (|new| ≤ max(|original|, floor)); outside it the message is
  marked declined and left verbatim, and it never returns to the due set.
  Every outcome changes the array, which is what makes a loop impossible — a
  rejection that marked nothing decremented no measure, and llm-repl logged 31
  attempts against one message before finding that out.

  Nothing is lost, only unloaded: every turn is in the journal, every
  confirmed and refuted artifact is in the settled-state block, and the
  encodings are one `fetch_artifact` away. Applied on the way to the wire, so
  the branch's own history is untouched and a resume replays what was really
  sent at the time."
  ([messages turns] (compaction-plan messages turns nil))
  ([messages turns {:keys [keep-pairs threshold-chars floor batch]}]
   (let [messages (vec messages)
         turns (vec (or turns []))
         keep-pairs (or keep-pairs (default-keep-pairs))
         threshold (or threshold-chars (default-compaction-threshold))
         batch (or batch (default-compaction-batch))
         aged (- (count (filter #(= "assistant" (:role %)) messages)) keep-pairs)
         keep (if (and (number? batch) (> batch 1) (pos? aged))
                (+ keep-pairs (mod aged (long batch)))
                keep-pairs)
         total (reduce + 0 (map (comp count str :content) messages))
         active? (and (>= total threshold) (> (count messages) frame-size))
         turns-by-number (into {} (map (juxt :turn identity)) turns)
         opts {:floor (or floor tape/default-floor) :roles compactable-roles}
         due (if active?
               (remove #(< % frame-size)
                       (tape/due-indices messages keep compactable-roles))
               [])
         strip-before (if (and active? (>= aged (max 1 (long (or batch 1)))))
                        (let [r (if (and (number? batch) (> batch 1)) (mod aged (long batch)) 0)
                              assistants (keep-indexed (fn [i m] (when (= "assistant" (:role m)) i))
                                                       messages)]
                          (or (nth (reverse assistants) r nil) 0))
                        0)
         rendered (cond-> (reduce (fn [ms i]
                                    (tape/compact-at ms i
                                                     (replacement-for (nth ms i) turns-by-number)
                                                     opts))
                                  messages
                                  due)
                    (pos? strip-before) (strip-context-before strip-before))]
     {:source messages
      :turns turns
      :options {:keep-pairs keep-pairs :threshold-chars threshold
                :batch batch :floor (:floor opts)}
      :messages rendered
      :rewrites (into (set due) (range frame-size strip-before))})))

(defn compact
  "Compact a branch's older turns in place. Compatibility wrapper for the
  pure plan used by covered-prefix hydration; output is identical."
  ([messages turns] (:messages (compaction-plan messages turns)))
  ([messages turns opts] (:messages (compaction-plan messages turns opts))))

(def result-close
  "The tag that ends a framed tool result. A constant, like the unloaded
  marker: a message framed once never changes again."
  "</tool_result>")

(defn frame-result
  "`text` as the model is shown a tool's output: inside a frame naming the
  tool, with the only closing tag the output itself contained escaped.

  THE ONE THING THE MODEL CAN TRUST ABOUT PROVENANCE (karamazov-o4wm.3). The
  turn's user message joined tool output, the context block, a `---` rule and
  the steer with nothing marking where the tool stopped talking, so a file
  read or a fetched page carrying that rule followed by `[harness]` read as
  the harness. Floatboat escapes every observation and seals its envelope;
  here the frame is enough, because harness text is APPENDED after it and
  the only way in is to close it — which the escape forbids. Nothing else in
  the output is rewritten: a coding model reads file contents through this
  and writes them back, so a `---` or a `[harness]` inside stays exactly as
  the file has it.

  Applied where a tool result becomes a user message — the live turn, a
  replayed row, a handoff — and never to the harness's own messages, which
  are the thing the frame distinguishes from."
  [tool text]
  (str "<tool_result" (when (seq (str tool)) (str " tool=\"" tool "\"")) ">\n"
       (str/replace (str text) result-close "<\\/tool_result>")
       "\n" result-close))

(defn unframe
  "The body of a framed result — the frame's two lines off, the escape left
  as it is — or `s` unchanged when it is not framed. For a summariser that
  wants a result's first line to be the result's, not the frame's."
  [s]
  (let [s (str s)]
    (if (and (str/starts-with? s "<tool_result")
             (str/ends-with? s (str "\n" result-close)))
      (let [body-start (inc (or (str/index-of s "\n") -1))
            body-end (- (count s) (count result-close) 1)]
        (if (<= body-start body-end) (subs s body-start body-end) ""))
      s)))

(defn framed-result
  "`[tool body]` of a framed result at the head of `s` — the tool the frame
  names and the text inside it, without anything appended after the frame —
  or nil when `s` does not start with one."
  [s]
  (let [s (str s)
        open "<tool_result tool=\""
        head-end (str/index-of s "\n")
        close (str/index-of s (str "\n" result-close))]
    ;; The head is exactly what frame-result writes: open, the tool, `">`.
    (when (and (str/starts-with? s open) head-end close
               (str/ends-with? (subs s 0 head-end) "\">"))
      [(subs s (count open) (- head-end 2)) (subs s (inc head-end) close)])))

(def ledger-open "<!--settled-state-->")
(def ledger-close "<!--/settled-state-->")

(def ^:private ledger-re
  (re-pattern (str "(?s)" (java.util.regex.Pattern/quote ledger-open)
                   ".*?" (java.util.regex.Pattern/quote ledger-close))))

(defn strip-stale-ledgers
  "Drop every settled-state block except the most recent.

  The ledger is regenerated from the artifacts table each turn and appended to
  that turn's result, so without this a branch accumulates one copy per turn.
  gen-18's ledger is roughly 6,800 tokens; eighty turns of it would dwarf the
  transcript it exists to summarise.

  A ledger is STATE. Only the newest is true, and an older copy is a strictly
  worse version of it — the same argument strip-think-blocks makes about
  reasoning, and the reason both live here rather than at the call site.

  Applied on the way to the wire only. The branch keeps every copy in its own
  message list, so the journal and a resume see exactly what was sent at the
  time."
  [messages]
  (let [last-idx (last (keep-indexed (fn [i m]
                                       (when (re-find ledger-re (str (:content m))) i))
                                     messages))]
    (if (nil? last-idx)
      messages
      (map-indexed (fn [i m]
                     (if (= i last-idx)
                       m
                       (update m :content #(str/replace (str %) ledger-re ""))))
                   messages))))

(defn prepare
  "Normalize a conversation for the wire: keyword roles become strings, prior
  assistant turns lose their think blocks, and every settled-state block but
  the newest is dropped. System and user messages are otherwise left alone."
  [messages]
  (mapv (fn [{:keys [role content] :as m}]
          (let [role (if (keyword? role) (clojure.core/name role) (str role))]
            (assoc (select-keys m [])
                   :role role
                   :content (-> (if (and (= "assistant" role) content)
                                  (strip-think-blocks content)
                                  (or content ""))
                                ;; The markers are harness framing and must not
                                ;; reach the model, which would otherwise learn
                                ;; to emit them.
                                (str/replace ledger-open "")
                                (str/replace ledger-close "")
                                (str/replace context-open "")
                                (str/replace context-close "")
                                str/trim))))
        (strip-stale-ledgers messages)))
