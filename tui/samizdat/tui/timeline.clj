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

(ns samizdat.tui.timeline
  "A branch's conversation as one timeline of who said what.

  A run is not a chat between two parties. The person states the problem
  and steers; the agent speaks and calls tools; the critic scores it; the
  supervisor watches the whole run and intervenes; the harness notes what
  it did. Each is a ROLE, and the conversation pane draws each in its own
  voice, dirge-style (`<you>`, `<agent>`, `<critic>` …), in the order it
  happened.

  Pure: the entries are built from the view state and the settings in
  tui.edn `:conversation` — which issuer is which role, which journal notes
  are shown and as whom, and where in a note's data its words are."
  (:require ;; the java.time.* host shim, before data.json — see samizdat.store.journal
            [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.llm.fence :as fence]))

(defn- parse [s]
  (if (string? s)
    (try (json/read-str s :key-fn keyword) (catch Throwable _ s))
    s))

(defn- banner-arg
  "The one argument a tool call is known by — its path, command, pattern —
  named per tool in the settings, else the first string argument."
  [tool args by-tool]
  (let [a (parse args)]
    (when (map? a)
      (let [k (get by-tool tool)
            v (if k (get a (keyword k)) (some #(when (string? %) %) (vals a)))]
        (some-> v str not-empty)))))

(defn- failed? [t]
  (contains? #{"failure" "mechanics"} (str (:category t))))

(defn- words
  "`s` split into what is said and what was thought: the prose with call
  markup and reasoning blocks removed (fence/prose), and the reasoning those
  blocks held joined onto `reasoning`.

  Joined only when they differ: a provider that returns reasoning_text AND
  leaves the same words in a <think> block drew every paragraph of the fold
  twice (karamazov-mu66)."
  [s reasoning]
  {:say (fence/prose s)
   :thinking (let [parts (remove str/blank? [(str reasoning) (fence/reasoning-of s)])]
               (str/join "\n" (if (and (= 2 (count parts)) (apply = (map str/trim parts))) (take 1 parts) parts)))})

(defn- turn-entries
  "One turn row as its entries. `tid` is the row's name in keys: its turn
  number, suffixed `.2`, `.3` … when the branch has used that number before."
  [t tid text banner]
  (let [n (:turn t)
        at (str (:created_at t))
        {:keys [say thinking]} (words (:assistant_text text) (:reasoning_text text))
        base {:role :agent :turn n :turn-key tid :at at}]
    (cond-> []
      (not (str/blank? thinking))
      (conj (assoc base :key (str "t" tid "/thinking") :kind :thinking :text thinking))

      (not (str/blank? say))
      (conj (assoc base :key (str "t" tid "/say") :kind :say :text say))

      :always
      (conj (assoc base :key (str "t" tid "/tool") :kind :tool
             :tool (str (:tool_name t))
             :arg (banner-arg (str (:tool_name t)) (:args t) banner)
             :result (str (:result t))
             :failed? (failed? t)
             :turn-row t)))))

(defn- all-turn-entries
  "Every turn row's entries, in the order the branch wrote them.

  A turn NUMBER is not a turn: runs before karamazov-pefk numbered each
  supervisor pass from 1 on the one SUP branch, 3067 rows under 233 numbers.
  The first row with a number keeps the plain key, so a branch that never
  repeats one is keyed as it always was; a later row with that number is
  `n.2`, `n.3` — stable as the branch grows, because rows only append. Its
  prose is the first row's alone: the per-turn endpoint answers a number
  with the first row that has it, and that text is not the others'."
  [turns text banner]
  (loop [[t & more] turns seen {} out (transient [])]
    (if-not t
      (persistent! out)
      (let [n (:turn t)
            k (inc (get seen n 0))
            tid (if (= 1 k) (str n) (str n "." k))]
        (recur more (assoc seen n k)
               (reduce conj! out (turn-entries t tid (when (= 1 k) (get text n)) banner)))))))

(defn- steer-text [{:keys [kind payload]}]
  (let [p (parse payload)
        words (cond (map? p) (or (:text p) (:message p) (pr-str p))
                    :else (str p))]
    (if (= "message" (str kind)) words (str "[" kind "] " words))))

(defn- steer-entries [interventions branch-id issued-by]
  (for [i interventions
        :when (or (nil? (:branch_id i)) (= branch-id (:branch_id i)))]
    {:key (str "i" (:id i)) :role (get issued-by (str (:issued_by i)) :user) :kind :say
     :at (str (:created_at i)) :text (steer-text i)
     :pending? (= "pending" (str (:status i)))}))

(defn- note-text [data say]
  (let [v (when (seq say) (get-in data (mapv keyword say)))]
    (if (and v (not (str/blank? (str v))))
      (str v)
      (pr-str data))))

(defn- note-entries [notes by-kind]
  (for [n notes
        :let [{:keys [role say]} (get by-kind (str (:kind n)))]
        :when role
        :let [w (words (note-text (parse (:data n)) say) nil)
              base {:role role :at (str (:created_at n)) :note (str (:kind n))}]
        e [(when-not (str/blank? (:thinking w))
             (assoc base :key (str "n" (:id n) "/thinking") :kind :thinking :text (:thinking w)))
           (when-not (str/blank? (:say w))
             (assoc base :key (str "n" (:id n)) :kind :say :text (:say w)))]
        :when e]
    e))

(defn- live-entries
  "The reply the branch is writing right now, as it streams in: after
  everything else, marked :live?, and gone when its turn row lands."
  [{:keys [text reasoning text-gap]} working?]
  (let [{:keys [say thinking]} (words text reasoning)
        ;; Missing its start, the text may be a tool call's tail: say only
        ;; that something is being written, until the turn row lands.
        say (if (and text-gap (not (str/blank? say))) "(writing…)" say)
        ;; A fence still arriving: "```" or "```tool-ca" at the very end is
        ;; a call being opened, not something said.
        say (str/replace (str say) #"(?:^|\n)`{1,3}[A-Za-z-]*\s*$" "")]
    (cond-> []
      ;; While the branch works the thinking line is always there — the one
      ;; sign it is not stuck — and opens on whatever has streamed so far.
      (or working? (not (str/blank? thinking)))
      (conj (cond-> {:key "live/thinking" :role :agent :kind :thinking
                     :text (str thinking) :live? true}
              working? (assoc :working? true)))
      (not (str/blank? say))
      (conj {:key "live/say" :role :agent :kind :say :text say :live? true}))))

(def ^:private ended-branch #{"abandoned" "exhausted" "failed" "error" "culled"})

(defn- ending
  "How a run that ended with no answer ended: its status, and the reason each
  branch that stopped short recorded. Run 9af33edc ended exhausted with its
  branch abandoned and no answer, and the last thing the log said was an old
  supervisor note — nobody could tell it had stopped, or why. nil while the
  run runs or when it has an answer, which ends the conversation instead."
  [state]
  (let [{:keys [status final_answer]} (get-in state [:detail :run])
        status (str status)]
    (when (and (not (str/blank? status)) (not= "running" status)
               (str/blank? (str final_answer)))
      (let [why (for [b (get-in state [:detail :branches])
                      :when (and (ended-branch (str (:status b)))
                                 (not (str/blank? (str (:inactive_reason b)))))]
                  (str (:id b) " " (:status b) ": " (str/trim (str (:inactive_reason b)))))]
        {:key "ending" :role :system :kind :say :final? true
         :text (str "The run ended " status " with no answer."
                    (when (seq why) (str "\n\n" (str/join "\n\n" why))))}))))

(defn- history
  "Everything but the reply being streamed, oldest first — ending with the
  run's answer when it has one, because that is what the person asked for.
  A finished run's answer used to be only a truncated claim in a side panel
  (karamazov-ttrn)."
  [problem turns text interventions branch-id notes local-notes answer end labels
   {:keys [issued-by banner collapsed-roles] by-kind :notes}]
  (let [aside? (set collapsed-roles)
        ;; A tool that means something else on this branch's role: a
        ;; team-solve worker's `done` hands its work to the manager and the
        ;; run goes on — drawn as DONE / "Answer accepted." it read as the
        ;; run finishing (run 9af33edc). tui.edn :conversation :relabel.
        relabel (fn [e]
                  (if-let [{:keys [label drop]} (and (= :tool (:kind e)) (get labels (:tool e)))]
                    (cond-> (assoc e :tool (str label))
                      (and drop (str/starts-with? (:result e) (str drop)))
                      (update :result #(str/triml (subs % (count (str drop))))))
                    e))
        ;; What the agents tell each other — a supervisor's steer, a critic's
        ;; score, the harness's notes — folds to one line: the person reads the
        ;; status of the work, and these are the machinery of getting there.
        aside (fn [e] (if (and (= :say (:kind e)) (aside? (:role e))) (assoc e :kind :aside) e))
        later (sort-by :at
                       (concat (all-turn-entries turns text banner)
                               (steer-entries interventions branch-id issued-by)
                               (note-entries notes by-kind)
                               ;; What this TUI printed: command output, /help.
                               (for [n local-notes]
                                 {:key (:key n) :role :system :kind :say
                                  :at (:at n) :text (:text n)})))]
    (cond-> (vec (cond->> (map (comp aside relabel) later)
                   (not (str/blank? (str problem)))
                   (cons {:key "problem" :role :user :kind :say :at "" :text (str problem)})))
      (not (str/blank? (str answer)))
      (conj {:key "answer" :role :agent :kind :say :final? true :text (str answer)})
      end (conj end))))

;; The last answer, and what it was made from. Every frame asks — a keystroke
;; is a frame — and rebuilding it from every turn row was 108ms of a 170ms
;; frame on a 3067-turn branch, for a vector that had not changed: typing
;; only moves :input (karamazov-iimi). One slot is enough, since one
;; conversation is drawn. Compared with `=`, which is `identical?` first and
;; so costs nothing while the state holds the same values.
(defonce ^:private last-history (atom nil))

(defn- history-of [state settings]
  (let [in [(get-in state [:detail :run :problem])
            (get-in state [:branch :turns])
            (:turn-text state)
            (get-in state [:detail :interventions])
            (:branch-id state)
            (get-in state [:branch :notes])
            (:local-notes state)
            (get-in state [:detail :run :final_answer])
            (ending state)
            (get-in settings [:relabel
                              (some #(when (= (:branch-id state) (:id %)) (some-> (:role %) name))
                                    (get-in state [:detail :branches]))])
            settings]
        [held es] @last-history]
    (if (and held (= held in))
      es
      (let [es (apply history in)]
        (reset! last-history [in es])
        es))))

(defn entries
  "The branch on screen as a vector of entries, oldest first:
  {:key :role :kind :at …} with :kind one of :say (a line in a role's voice,
  :text), :thinking (:text) or :tool (:tool :arg :result :failed? :turn
  :turn-key). Keys are stable as the story grows, so a fold or a scroll
  anchor holds.

  The same vector, not an equal one, while nothing it is made from has
  changed; the reply being streamed is laid on top of it, which is cheap."
  [state settings]
  (let [es (history-of state settings)
        ;; Working: the run runs, the branch on screen is active, and nobody
        ;; is being asked — then the log says it is thinking, even before a
        ;; word has streamed.
        working? (and (= "running" (str (get-in state [:detail :run :status])))
                      (= "active" (str (:status (first (filter #(= (:branch-id state) (:id %))
                                                                (get-in state [:detail :branches]))))))
                      (empty? (:approvals state)))
        live (live-entries (get-in state [:live (:branch-id state)]) working?)]
    (if (seq live) (into es live) es)))

(defn fold-id
  "The id of the fold an entry's body would sit behind."
  [e]
  (case (:kind e)
    :thinking (:key e)
    :aside (:key e)
    :tool (str (:key e) "/more")
    nil))

(defn fold-ids
  "The folds the conversation actually draws, in order: every thinking, and
  every result longer than `:result-lines`."
  [es {:keys [result-lines]}]
  (keep (fn [e]
          (case (:kind e)
            :thinking (fold-id e)
            :aside (fold-id e)
            :tool (when (> (count (re-seq #"\n" (:result e))) (dec (or result-lines 4)))
                    (fold-id e))
            nil))
        es))
