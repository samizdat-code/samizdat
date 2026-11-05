;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.sections
  "Read a role's reply as named sections. Pure.

  A one-call role answers in sections a program reads (`### PLAN`, `### STATUS`).
  Small models vary the header's shape and sometimes stop right after the last
  one, so the parser takes the shapes they write and keeps a section that has
  no body. What a missing section MEANS is the caller's decision."
  (:require [clojure.string :as str]
            [samizdat.llm.message :as message]))

(defn reply-text
  "A reply as the program should read it: no reasoning block, no surrounding space.
  A reply that is nothing but reasoning is kept, tags off, so a model that never got
  past thinking still says something."
  [text]
  (let [s (str text)
        clean (str/trim (str (message/strip-think-blocks s)))]
    (if (str/blank? clean)
      (str/trim (str/replace s #"</?think>" ""))
      clean)))

(defn strip-code
  "Fenced code replaced by a marker, for what must stay approach-only."
  [text]
  (str/trim (str/replace (str text) #"(?s)```.*?```" "[code omitted]")))

(defn- header
  "The section name a line opens, uppercased, or nil."
  [line]
  (let [s (str/trim line)]
    (some-> (or (second (re-matches #"#{1,3}\s*([A-Za-z_]+)\s*" s))
                (second (re-matches #"\*\*([A-Za-z_]+)\*\*\s*:?\s*" s))
                (second (re-matches #"([A-Z_]{3,})\s*:\s*" s)))
            str/upper-case)))

(defn- read-sections
  [text]
  (let [flush (fn [{:keys [cur buf] :as acc}]
                (cond-> acc
                  cur (assoc-in [:out cur] (str/trim (str/join "\n" buf)))))
        end (reduce (fn [{:keys [cur] :as acc} line]
                      (if-let [h (header line)]
                        (assoc (flush acc) :cur h :buf [])
                        (cond-> acc cur (update :buf conj line))))
                    {:out {} :cur nil :buf []}
                    (str/split-lines text))]
    (:out (flush end))))

(defn parse
  "{SECTION body} for every header in `text`. Text before the first header is
  dropped. {} when there are no headers."
  [text]
  (read-sections (reply-text text)))

;; --- GVS5H's readers, for the program path --------------------------------------
;;
;; Ported from codebase/v2-current/escalation/multiagent.py, MIT, Copyright (c) 2026
;; Persis Capital Inc. Where one differs from the readers above it is on purpose:
;; these have to read a reply exactly as theirs does (team-solve-parity-test).

(declare bullets)

(defn strip-think
  "_strip_think."
  [text]
  (str/trim (str/replace (str text) #"(?s)<think>.*?</think>" "")))

(defn parse-exact
  "_sections."
  [text]
  (read-sections (strip-think text)))

(defn strip-code-exact
  "_strip_code."
  [text]
  (str/trim (str/replace (str text) #"(?s)```.*?```" "[code omitted -- approach only]")))

(defn extract-program
  "_extract_py: the first fenced block's text, or \"\"."
  [text]
  (if-let [m (re-find #"(?s)```(?:python)?\s*\n(.*?)```" (strip-think text))]
    (str/trim (second m))
    ""))

(defn parse-task-list
  "_parse_tasks: {:id :desc :status} per bullet, at most `cap`."
  [text cap]
  (let [rows (reduce
              (fn [out b]
                (let [m (re-find #"(?i)^\[\s*([a-z_ ]+?)\s*\]\s*(.*)" b)
                      raw (when m (-> (second m) str/lower-case (str/replace " " "") (str/replace "_" "")))
                      desc (str/trim (if m (nth m 2) b))
                      status (cond (nil? m) "pending"
                                   (= "done" raw) "done"
                                   (#{"wip" "inprogress"} raw) "in_progress"
                                   :else "pending")]
                  (if (str/blank? desc)
                    out
                    (conj out {:id (inc (count out)) :desc desc :status status}))))
              []
              (bullets text))]
    (vec (take cap rows))))

(defn add-tasks
  "_add_tasks: new descriptions onto `tasks`, skipping duplicates, up to `cap`."
  [tasks descs cap]
  (reduce
   (fn [ts d]
     (let [d (str/trim (str d))]
       (if (and (seq d) (< (count ts) cap)
                (not-any? #(= (str/lower-case d) (str/lower-case (:desc %))) ts))
         (conj ts {:id (inc (reduce max 0 (map :id ts))) :desc d :status "pending"})
         ts)))
   (vec tasks)
   descs))

(defn py-repr
  "Python's repr of a string, which their failure report prints."
  [s]
  (let [s (str s)
        q (if (and (str/includes? s "'") (not (str/includes? s "\""))) \" \')
        esc (fn [c]
              (cond
                (= c \\) "\\\\"
                (= c q) (str "\\" c)
                (= c \newline) "\\n"
                (= c \return) "\\r"
                (= c \tab) "\\t"
                (< (int c) 32) (format "\\x%02x" (int c))
                :else (str c)))]
    (str q (apply str (map esc s)) q)))

(defn bullets
  "The list items in `text`, in order, whichever list marker they use."
  [text]
  (into []
        (keep (fn [line]
                (some-> (re-matches #"\s*(?:[-*]|\d+[.)])\s+(.*\S)\s*" line)
                        second str/trim)))
        (str/split-lines (str text))))

(defn counterexample
  "{:input :expected} from a parsed reply, or nil. INPUT: and EXPECTED: read as
  section headers of their own. Half of one is none: an input with no expected
  output cannot be checked."
  [sections]
  (let [input (some-> (get sections "INPUT") str/trim not-empty)
        expected (some-> (get sections "EXPECTED") str/trim not-empty)]
    (when (and input expected)
      {:input input :expected expected})))
