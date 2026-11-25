;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.mcp.elicit
  "MCP elicitation as questions a person answers (karamazov-k7gl.3,
  schema coverage karamazov-k7gl.12).

  A server may ask, in the middle of a call, for something only the user
  knows: `elicitation/create` with a message and a requestedSchema. Pure
  translation both ways: the schema into the questionnaire the approvals
  queue already carries for ask_human (so every front end that answers one
  answers this), and the answers back into the result the server reads —
  {:action \"accept\" :content {...}}, \"decline\" or \"cancel\". Who is
  asked, for how long and how often is the `mcp` tool's.

  The schema subset is the 2025-11-25 form mode's, a superset of the
  2025-06-18 one the client negotiates: a flat object whose properties are
  strings (minLength, maxLength, pattern, format email / uri / date /
  date-time), numbers and integers (minimum, maximum), booleans, single
  enums (`enum`, titled by `oneOf` const+title or the legacy `enumNames`)
  and multi-selects (an array whose `items` is an `enum` or an `anyOf` of
  const+title, with minItems / maxItems), each with an optional `default`.
  A multi-select is a question with :multi, the shape ask_human's
  multi-select already has. A titled option is shown by its title and
  answered by its const.

  Answers are checked against those constraints before anything is sent
  (`check`): a blank answer takes the property's default, a required one
  with none is a problem, and so is anything that does not fit. The tool
  puts the questions to the person again with each problem marked
  (`questions` with :problems and the answers that did fit as :prior);
  what still does not fit after that is `decline` — the person did act,
  so it is not `cancel`, which the spec keeps for a dismissal without a
  choice and which invites the server to ask again, and it is not
  `accept`, whose content must match the schema.

  Anything outside the subset — a nested object, an array of free text or
  of objects, a type the spec does not list — is refused before a person
  sees it (`refusal`), and so is URL mode, which the client does not
  declare. The spec says nested structures are intentionally unsupported
  and that a mode the client did not declare is answered with -32602;
  the same answer fits a schema the client did not agree to. Flattening a
  nested schema into dotted keys would send content the server never
  asked for in that shape, and declining would tell the server the person
  said no when nobody was asked."
  (:require [clojure.string :as str]))

(defn- properties [schema]
  (seq (:properties schema)))

;; --- the subset ----------------------------------------------------------------

(defn- titled
  "[[const title] ...] for a list of {const, title} alternatives, or nil."
  [alts]
  (when (and (sequential? alts) (seq alts) (every? #(and (map? %) (contains? % :const)) alts))
    (mapv (fn [a] [(:const a) (str (or (:title a) (:const a)))]) alts)))

(defn- choices
  "[[const label] ...] for a single or item enum `p`, or nil when it is none."
  [p]
  (or (titled (:oneOf p))
      (titled (:anyOf p))
      (when (seq (:enum p))
        (let [names (:enumNames p)]
          (vec (map-indexed (fn [i v] [v (str (if (and (sequential? names) (< i (count names)))
                                                (nth names i)
                                                v))])
                            (:enum p)))))))

(defn- kind
  "What property `p` is, or nil when it is outside the subset."
  [p]
  (let [t (:type p)]
    (cond
      (not (map? p)) nil
      (= "array" t) (when (choices (:items p)) :multi)
      (choices p) (when (contains? #{nil "string" "number" "integer" "boolean"} t) :enum)
      (= "string" t) :string
      (= "number" t) :number
      (= "integer" t) :integer
      (= "boolean" t) :boolean
      :else nil)))

(defn refusal
  "Why elicitation `params` cannot be put to a person, as data: {:mode m}
  for a mode other than form, {:unsupported [property ...]} for a schema
  outside the subset, nil when it can."
  [{:keys [mode requestedSchema]}]
  (cond
    (and (some? mode) (not= "form" mode)) {:mode (str mode)}
    (and (some? requestedSchema)
         (or (not (map? requestedSchema))
             (not (contains? #{nil "object"} (:type requestedSchema)))))
    {:unsupported []}
    :else
    (when-let [bad (seq (keep (fn [[k p]] (when-not (kind p) (name k))) (properties requestedSchema)))]
      {:unsupported (vec bad)})))

;; --- questions ---------------------------------------------------------------

(defn- label-of
  "How value `v` of property `p` is shown: its title when it has one."
  [p v]
  (let [cs (choices (if (= :multi (kind p)) (:items p) p))]
    (or (some (fn [[c l]] (when (= c v) l)) cs)
        (str v))))

(defn- shown
  "Value `v` as the question shows it: a title, a vector of them, a string."
  [p v]
  (cond
    (nil? v) nil
    (= :multi (kind p)) (mapv #(label-of p %) (if (sequential? v) v [v]))
    :else (label-of p v)))

(defn- limits [p]
  (not-empty
   (into {}
         (remove (comp nil? val))
         {:format (:format p) :pattern (:pattern p)
          :minlen (:minLength p) :maxlen (:maxLength p)
          :minimum (:minimum p) :maximum (:maximum p)
          :minitems (:minItems p) :maxitems (:maxItems p)})))

(defn questions
  "The questions for `schema`'s properties, in order, or nil when it asks
  for nothing beyond a yes or no. Each carries what a front end or the
  tool's wording needs: :options (titles, for an enum), :multi,
  :required, :default (as shown), :limits, and — on a re-ask — the
  :problem `check` found, with the answer given before as the default."
  ([schema] (questions schema nil))
  ([schema {:keys [problems prior]}]
   (when-let [ps (properties schema)]
     (let [required (set (map keyword (:required schema)))]
       (mapv (fn [[k p]]
               (let [label (or (:title p) (name k))
                     kd (kind p)
                     default (if (contains? prior k) (get prior k) (:default p))]
                 (cond-> {:question (str label (when-let [d (:description p)] (str " — " d)))
                          :header (name k)
                          :options (case kd
                                     :multi (mapv second (choices (:items p)))
                                     :enum (mapv second (choices p))
                                     :boolean ["true" "false"]
                                     [])}
                   (= :multi kd) (assoc :multi true)
                   (contains? required k) (assoc :required true)
                   (some? default) (assoc :default (shown p default))
                   (limits p) (assoc :limits (limits p))
                   (get problems k) (assoc :problem (get problems k)))))
             ps)))))

;; --- answers -------------------------------------------------------------------

(def ^:private formats
  {"email" #"^[^@\s]+@[^@\s]+\.[^@\s]+$"
   "uri" #"^[A-Za-z][A-Za-z0-9+.\-]*:[^\s]+$"
   "date" #"^(\d{4})-(\d{2})-(\d{2})$"
   "date-time" #"^(\d{4})-(\d{2})-(\d{2})[Tt ]([01]\d|2[0-3]):[0-5]\d:([0-5]\d|60)(\.\d+)?([Zz]|[+-]([01]\d|2[0-3]):[0-5]\d)$"})

(defn- real-date?
  "Whether year, month and day name a day that exists."
  [y m d]
  (let [y (parse-long y) m (parse-long m) d (parse-long d)
        leap? (and (zero? (mod y 4)) (or (pos? (mod y 100)) (zero? (mod y 400))))
        days [31 (if leap? 29 28) 31 30 31 30 31 31 30 31 30 31]]
    (and (<= 1 m (count days)) (<= 1 d (nth days (dec m))))))

(defn- format-ok? [fmt s]
  (if-let [re (get formats fmt)]
    (if-let [m (re-find re s)]
      (if (contains? #{"date" "date-time"} fmt)
        (real-date? (nth m 1) (nth m 2) (nth m 3))
        true)
      false)
    true))

(defn- pattern-ok? [pat s]
  (let [re (try (re-pattern (str pat)) (catch Throwable _ nil))]
    (or (nil? re) (some? (re-find re s)))))

(defn- text
  "Answer `a` as one trimmed string, or nil when blank."
  [a]
  (let [s (str/trim (str (if (sequential? a) (first a) a)))]
    (when-not (str/blank? s) s)))

(defn- picks
  "A multi-select answer as a list of non-blank strings: the front ends
  send a vector; a typed answer is split at commas."
  [a]
  (->> (cond (sequential? a) a (nil? a) [] :else (str/split (str a) #","))
       (map #(str/trim (str %)))
       (remove str/blank?)
       vec))

(defn- const-for
  "The const that `s` (a title or a const) picks among `cs`, or ::none."
  [cs s]
  (or (some (fn [[c l]] (when (= s l) [c])) cs)
      (some (fn [[c _]] (when (= s (str c)) [c])) cs)
      ::none))

(defn- problem [k & [limit]]
  (cond-> {:kind k} (some? limit) (assoc :limit limit)))

(defn- typed
  "[value nil] for non-blank answer `a` to property `p`, or [nil problem]."
  [p a]
  (case (kind p)
    :multi
    (let [cs (choices (:items p))
          vs (mapv #(const-for cs %) (picks a))
          n (count vs)]
      (cond
        (some #{::none} vs) [nil (problem "option")]
        (and (:minItems p) (< n (:minItems p))) [nil (problem "minitems" (:minItems p))]
        (and (:maxItems p) (> n (:maxItems p))) [nil (problem "maxitems" (:maxItems p))]
        :else [(vec (map first vs)) nil]))
    :enum
    (let [v (const-for (choices p) (text a))]
      (if (= ::none v) [nil (problem "option")] [(first v) nil]))
    :boolean
    (case (str/lower-case (text a)) ("true" "yes" "y") [true nil] ("false" "no" "n") [false nil]
          [nil (problem "type" (:type p))])
    (:integer :number)
    (let [s (text a)
          n (if (= :integer (kind p))
              (parse-long s)
              (some-> (when (re-find #"^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$" s) s) parse-double))]
      (cond
        (nil? n) [nil (problem "type" (:type p))]
        (and (:minimum p) (< n (:minimum p))) [nil (problem "minimum" (:minimum p))]
        (and (:maximum p) (> n (:maximum p))) [nil (problem "maximum" (:maximum p))]
        :else [n nil]))
    ;; :string
    (let [s (text a)
          n (count s)]
      (cond
        (and (:minLength p) (< n (:minLength p))) [nil (problem "minlen" (:minLength p))]
        (and (:maxLength p) (> n (:maxLength p))) [nil (problem "maxlen" (:maxLength p))]
        (and (:pattern p) (not (pattern-ok? (:pattern p) s))) [nil (problem "pattern")]
        (and (:format p) (not (format-ok? (:format p) s))) [nil (problem "format")]
        :else [s nil]))))

(defn- blank? [p a]
  (if (= :multi (kind p)) (empty? (picks a)) (nil? (text a))))

(defn check
  "The content answers `answers` (by position) give `schema`, and the
  problems with them: {:content {k v} :problems {k {:kind k :limit n}}},
  :problems nil when there are none. A blank answer takes the default; a
  required one without a default is a problem — except a required
  multi-select, where an empty selection is a value its minItems judges."
  [schema answers]
  (let [required (set (map keyword (:required schema)))
        answers (vec answers)
        step (fn [acc i [k p]]
               (let [a (nth answers i nil)]
                 (cond
                   (and (blank? p a) (contains? p :default)) (assoc-in acc [:content k] (:default p))
                   (and (blank? p a) (contains? required k) (= :multi (kind p)))
                   (let [[v pr] (typed p [])]
                     (if pr (assoc-in acc [:problems k] pr) (assoc-in acc [:content k] v)))
                   (and (blank? p a) (contains? required k)) (assoc-in acc [:problems k] (problem "required"))
                   (blank? p a) acc
                   :else (let [[v pr] (typed p a)]
                           (if pr (assoc-in acc [:problems k] pr) (assoc-in acc [:content k] v))))))
        {:keys [content problems]} (reduce-kv (fn [acc i kp] (step acc i kp))
                                              {:content {} :problems nil}
                                              (vec (properties schema)))]
    {:content content :problems (not-empty problems)}))

(defn result
  "The elicitation result for a person's `answer` (an approvals decision)
  to `schema`. An answer that ran out of time, or that a run ending
  released, is cancel; a refusal is decline; an allow is accept with the
  checked content — unless it still has problems, which is decline (see
  the namespace doc)."
  [schema {:keys [decision answers timed-out abandoned]}]
  (cond
    (or timed-out abandoned) {:action "cancel"}
    (not= :allow decision) {:action "decline"}
    :else
    (let [{:keys [content problems]} (check schema answers)]
      (if problems
        {:action "decline"}
        {:action "accept" :content content}))))
