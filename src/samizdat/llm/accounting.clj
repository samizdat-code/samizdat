(ns samizdat.llm.accounting
  "Complete-request accounting: what a prepared request costs before it is
  sent. Every tunable number arrives as policy data (gates.edn
  :request-accounting); none lives in this namespace. Message text is measured
  by agent.compaction, with accounting's non-ASCII adjustment applied on top.
  Settled provider usage feeds a bounded process-local calibration store."
  (:require [samizdat.agent.compaction :as compaction]))

(defn- split-chars
  "[ascii non-ascii] counts in s. Non-ASCII is charged at the tighter
  policy ratio because a denser tokenizer is the conservative assumption."
  [s policy]
  (reduce (fn [[a n] c]
            (if (<= (int c) (:ascii-max-codepoint policy))
              [(inc (long a)) n]
              [a (inc (long n))]))
          [0 0] s))

(defn- chars->tokens [s policy]
  (let [cpt (double (:chars-per-token policy))
        npt (double (:non-ascii-chars-per-token policy))
        [a n] (split-chars s policy)]
    (long (Math/ceil (+ (/ a cpt) (/ n npt))))))

(defn- bounded [s policy]
  (let [limit (long (:max-serialized-chars policy))]
    (if (> (count s) limit) (subs s 0 limit) s)))

(defn- accounting-content
  "Preserve compaction's text shapes; represent each nontext block as bounded
  serialized text so its unknown payload is included in the same measurement."
  [content policy]
  (if (sequential? content)
    (mapv (fn [block]
            (if (or (:text block) (:content block))
              block
              {:type "text" :text (bounded (pr-str block) policy)}))
          content)
    content))

(defn- non-ascii-chars [x policy]
  (cond
    (string? x) (second (split-chars x policy))
    (map? x) (reduce + 0 (map (fn [[k v]] (+ (non-ascii-chars k policy)
                                            (non-ascii-chars v policy))) x))
    (sequential? x) (reduce + 0 (map #(non-ascii-chars % policy) x))
    :else 0))

(defn- message-tokens
  "Compaction's message estimate, adjusted for non-ASCII content under the
  supplied policy. Nontext blocks have already become bounded text blocks."
  [messages policy]
  (let [prepared (mapv #(update % :content accounting-content policy) messages)
        cpt (double (:chars-per-token policy))
        npt (double (:non-ascii-chars-per-token policy))
        base (compaction/estimate-tokens prepared cpt)
        chars (reduce + 0 (map #(compaction/content-chars (:content %)) prepared))
        non-ascii (non-ascii-chars (map :content prepared) policy)
        adjusted (long (Math/ceil (+ (/ (- chars non-ascii) cpt)
                                     (/ non-ascii npt))))]
    (max base adjusted)))

(defn- tool-chars
  "The complete native tool spec, deterministically serialized and bounded.
  This includes :parameters and other schema spellings as well as unknown keys."
  [t policy]
  (bounded (pr-str t) policy))

(defn- framing-for [policy k] (get-in policy [:framing k]))

(defn- component
  "Framing overhead plus content tokens, floored at min-component-tokens
  when the part is present. Absent request parts are excluded entirely."
  [present? tokens policy k]
  (when present?
    (max (long (:min-component-tokens policy))
         (+ (long (framing-for policy k)) (long tokens)))))

(defonce ^:private calibration-store
  (atom {:entries {} :order []}))

(defn reset-calibration!
  "Forget all process-local provider-usage samples, including eviction order."
  []
  (reset! calibration-store {:entries {} :order []})
  nil)

(defn- calibration-settings [policy]
  (let [{:keys [min-ratio max-ratio max-samples max-scopes]}
        (:calibration policy)]
    (when (and (:calibration-version policy)
               (number? min-ratio) (number? max-ratio)
               (<= 0.0 (double min-ratio) (double max-ratio))
               (pos? (or max-samples 0)) (pos? (or max-scopes 0)))
      (:calibration policy))))

(defn- scope-key [estimate]
  [(:provider estimate) (:model estimate)
   (vec (sort (keys (:components estimate))))])

(defn- calibration-ratio [estimate policy]
  (let [{:keys [calibration-version]} policy
        {:keys [version ratios]} (get-in @calibration-store
                                         [:entries (scope-key estimate)])]
    (when (and (= calibration-version version) (seq ratios))
      (/ (reduce + 0.0 ratios) (count ratios)))))

(defn- scale-components
  "Scale a component map to the calibrated total while preserving every
  present component, exact component/input agreement, and deterministic order
  for the integer rounding remainder."
  [components ratio]
  (if (empty? components)
    {}
    (let [input (reduce + 0 (vals components))
          n (count components)
          target (max n (long (Math/ceil (* input ratio))))
          extra (- target n)
          ks (vec (sort-by pr-str (keys components)))
          raw (mapv (fn [k]
                      (let [x (* extra (/ (double (get components k))
                                          (double input)))]
                        [k (long (Math/floor x)) (- x (Math/floor x))]))
                    ks)
          initial (into {} (map (fn [[k whole _]] [k (inc whole)]) raw))
          assigned (reduce + 0 (vals initial))
          remainder (- target assigned)
          ranked (sort-by (fn [[k _ fraction]] [(- fraction) (pr-str k)]) raw)
          bonus (set (map first (take remainder ranked)))]
      (reduce (fn [out k] (cond-> out (bonus k) (update k inc)))
              initial ks))))

(defn- base-estimate [request policy]
  (let [comp {:messages (component (seq (:messages request))
                                   (message-tokens (:messages request) policy)
                                   policy :messages)
              :tools (component (seq (:tools request))
                                (chars->tokens
                                 (reduce (fn [acc t] (str acc (tool-chars t policy)))
                                         "" (:tools request))
                                 policy)
                                policy :tools)
              :grammar (component (:grammar request)
                                  (chars->tokens (str (:grammar request)) policy)
                                  policy :grammar)
              :prefill (component (:prefill request)
                                  (chars->tokens (str (:prefill request)) policy)
                                  policy :prefill)
              :tool-choice (component (some? (:tool-choice request))
                                      (chars->tokens (pr-str (:tool-choice request)) policy)
                                      policy :tool-choice)}
        comp (into {} (filter val) comp)
        input (reduce + 0 (vals comp))
        reserved (long (or (:max-tokens request) 0))]
    {:components comp
     :input-tokens input
     :base-input-tokens input
     :reserved-output-tokens reserved
     :total-tokens (+ input reserved)
     :context-window (:context-window request)
     :source :conservative-chars
     :confidence (:confidence policy)
     :calibration-version (:calibration-version policy)
     :provider (:provider request)
     :model (:model request)}))

(defn estimate
  "Estimate the full prepared request under policy. Returns structured data
  — never a bare integer: component map, input/reserved/total tokens, and
  the estimate's provenance (:source, :confidence, :calibration-version,
  provider/model passthrough). A matching bounded usage sample may calibrate
  the component totals; without one the conservative estimate is unchanged."
  [request policy]
  (let [base (base-estimate request policy)]
    (if-let [ratio (when (calibration-settings policy)
                     (calibration-ratio base policy))]
      (let [{:keys [min-ratio max-ratio]} (:calibration policy)
            bounded (-> ratio (max min-ratio) (min max-ratio))
            components (scale-components (:components base) bounded)
            input (reduce + 0 (vals components))]
        (assoc base
               :components components
               :input-tokens input
               :total-tokens (+ input (:reserved-output-tokens base))
               :source :provider-usage
               :confidence (:calibrated-confidence policy (:confidence base))))
      base)))

(defn record-usage!
  "Reconcile one settled response under the request-accounting policy scope.
  Ratios always use the preserved conservative input, never a previously
  calibrated total. Only provider/model/component-shape identity and numeric
  ratios are stored. Missing prompt usage cannot calibrate later estimates."
  [scope estimate usage]
  (let [actual (:prompt-tokens usage)
        estimated (:base-input-tokens estimate (:input-tokens estimate))]
    (if-not (and (calibration-settings scope)
                 (number? actual) (pos? actual)
                 (number? estimated) (pos? estimated))
      {:recorded? false :reason :missing-usage}
      (let [ratio (/ (double actual) (double estimated))
            {:keys [max-samples max-scopes]} (:calibration scope)
            version (:calibration-version scope)
            k (scope-key estimate)]
        (swap! calibration-store
               (fn [{:keys [entries order]}]
                 (let [old (get entries k)
                       prior (when (= version (:version old)) (:ratios old))
                       ratios (vec (take-last max-samples
                                              (conj (vec prior) ratio)))
                       new? (nil? old)
                       order (if new? (conj order k) order)
                       overflow (count (drop max-scopes order))
                       evicted (set (take overflow order))]
                   {:entries (assoc (apply dissoc entries evicted) k
                                    {:version version :ratios ratios})
                    :order (vec (drop overflow order))})))
        (let [stored (get-in @calibration-store [:entries k])]
          {:recorded? true
           :key k
           :ratio ratio
           :undercount? (> actual estimated)
           :samples (count (:ratios stored))
           :calibration-version version})))))

(defn admit
  "Decide whether an estimate fits its context window. The rule is
  input + reserved output <= context-window. A missing or non-positive
  window is a structured refusal, never an implicit pass."
  [estimate]
  (let [window (:context-window estimate)
        total (:total-tokens estimate)]
    (if (and (number? window) (pos? window))
      (if (<= total (long window))
        (assoc estimate :admissible? true)
        (assoc estimate :admissible? false :reason :exceeds-context-window))
      (assoc estimate :admissible? false :reason :unknown-context-window))))
