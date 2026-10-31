(ns samizdat.agent.context-fit
  "Outgoing-only tool-result fitting. The source messages are never changed;
  only framed result bodies in the returned projection are shortened."
  (:require [clojure.string :as str]
            [samizdat.llm.message :as message]))

(defn- result-parts [m]
  (let [content (str (:content m))
        framed (message/framed-result content)]
    (when (and (= "user" (:role m)) framed)
      (let [[tool _] framed
            close (str/index-of content (str "\n" message/result-close))
            frame-end (+ close (count message/result-close) 1)]
        {:tool tool
         :body (message/unframe (subs content 0 frame-end))
         :suffix (subs content frame-end)}))))

(defn- utf8-bytes [s]
  (alength (.getBytes (str s) "UTF-8")))

(defn- interpolate [template values]
  (reduce (fn [s [k v]]
            (str/replace s (str "{" (name k) "}") (str v)))
          (str template)
          values))

(defn- recovery [m policy]
  (if (some? (:turn m))
    (interpolate (:turn-recovery policy) {:turn (:turn m)})
    (:generic-recovery policy)))

(defn- recovery-text [template m omitted-bytes policy]
  (interpolate template
               {:omitted-bytes omitted-bytes
                :recovery (recovery m policy)}))

(defn- code-point-count [s]
  (.codePointCount s 0 (.length s)))

(defn- code-point-subs [s start end]
  (subs s
        (.offsetByCodePoints s 0 start)
        (.offsetByCodePoints s 0 end)))

(defn- excerpt-body [body target m policy]
  (let [n (code-point-count body)
        share (double (:head-share policy))
        kept (loop [kept 0]
               (let [head (min n (long (* kept share)))
                     tail (min (- n head) (- kept head))
                     omitted (code-point-subs body head (- n tail))
                     marker (recovery-text (:omission-text policy) m
                                           (utf8-bytes omitted) policy)
                     next-kept (max 0 (- (long target) (code-point-count marker)))]
                 (if (= kept next-kept)
                   kept
                   (recur next-kept))))
        head (min n (long (* kept share)))
        tail (min (- n head) (- kept head))
        omitted (code-point-subs body head (- n tail))]
    (str (code-point-subs body 0 head)
         (recovery-text (:omission-text policy) m (utf8-bytes omitted) policy)
         (code-point-subs body (- n tail) n))))

(defn- reference-body [body m policy]
  (recovery-text (:reference-text policy) m (utf8-bytes body) policy))

(defn- shortened-at [messages source index mode target policy]
  (let [current (get messages index)
        original (get source index)
        parts (result-parts current)
        original-parts (result-parts original)
        new-body (case mode
                   :excerpt (excerpt-body (:body parts) target original policy)
                   :reference (reference-body (:body original-parts) original policy))
        content (str (message/frame-result (:tool parts) new-body) (:suffix parts))
        saved (- (count (str (:content current))) (count content))]
    (when (>= saved (long (:minimum-savings-chars policy)))
      {:message (assoc current :content content)
       :saved saved})))

(defn- add-shortening [{:keys [shortened] :as state} index kind saved]
  (-> state
      (update :remaining #(max 0 (- (long %) (long saved))))
      (update :saved-chars + saved)
      (assoc :shortened (conj shortened [kind index]))))

(defn- sequential-stage [state indices mode target policy source kind]
  (reduce (fn [{:keys [messages remaining] :as state} index]
            (if (zero? remaining)
              state
              (if-let [{:keys [message saved]}
                       (shortened-at messages source index mode target policy)]
                (-> state
                    (assoc :messages (assoc messages index message))
                    (add-shortening index kind saved))
                state)))
          state
          indices))

(defn- cap-shortenings [state indices cap policy source]
  (keep (fn [index]
          (when-let [shortened
                     (shortened-at (:messages state) source index :excerpt cap policy)]
            [index shortened]))
        indices))

(defn- cap-savings [state indices cap policy source]
  (reduce + 0 (map (comp :saved second)
                   (cap-shortenings state indices cap policy source))))

(defn- common-cap [state indices policy source]
  (let [floor (long (:excerpt-floor-chars policy))
        largest (reduce (fn [n index]
                          (max n (count (:body (result-parts
                                                (get (:messages state) index))))))
                        floor
                        indices)
        remaining (:remaining state)]
    (cond
      (>= (cap-savings state indices floor policy source) remaining)
      (loop [low floor high largest]
        (if (= low high)
          low
          (let [middle (long (Math/ceil (/ (+ low high) 2.0)))]
            (if (>= (cap-savings state indices middle policy source) remaining)
              (recur middle high)
              (recur low (dec middle))))))
      :else floor)))

(defn- unresolved-stage [state indices policy source]
  (if (zero? (:remaining state))
    state
    (let [cap (common-cap state indices policy source)]
      (reduce (fn [{:keys [messages] :as state} [index {:keys [message saved]}]]
                (-> state
                    (assoc :messages (assoc messages index message))
                    (add-shortening index :unresolved saved)))
              state
              (cap-shortenings state indices cap policy source)))))

(defn- candidates [messages]
  (let [last-assistant (last (keep-indexed
                              (fn [index m]
                                (when (= "assistant" (:role m)) index))
                              messages))
        results (keep-indexed (fn [index m]
                                (when (result-parts m) index))
                              messages)]
    (reduce (fn [groups index]
              (if (or (nil? last-assistant) (> index last-assistant))
                (update groups :unresolved conj index)
                (update groups :earlier conj index)))
            {:earlier [] :unresolved []}
            results)))

(defn fit
  "Fit framed tool results to free `deficit-tokens`, oldest consumed results
  first. Unresolved results participate only in the water-filled second stage
  and the reference fourth stage. Returns a new message projection and fitting
  accounting; no source message or durable record is modified."
  [messages deficit-tokens policy]
  (let [messages (vec messages)
        base {:messages messages
              :freed-tokens 0
              :shortened-earlier 0
              :shortened-unresolved 0
              :exhausted? false}]
    (if-not (pos? (or deficit-tokens 0))
      base
      (let [chars-per-token (double (:chars-per-token policy))
            target-chars (long (Math/ceil (* deficit-tokens chars-per-token)))
            {:keys [earlier unresolved]} (candidates messages)
            initial {:messages messages
                     :remaining target-chars
                     :saved-chars 0
                     :shortened #{}}
            stage1 (sequential-stage initial earlier :excerpt
                                     (:excerpt-floor-chars policy) policy messages :earlier)
            stage2 (unresolved-stage stage1 unresolved policy messages)
            stage3 (sequential-stage stage2 earlier :reference nil policy messages :earlier)
            final (sequential-stage stage3 unresolved :reference nil policy messages :unresolved)
            counts (frequencies (map first (:shortened final)))]
        {:messages (:messages final)
         :freed-tokens (long (/ (:saved-chars final) chars-per-token))
         :shortened-earlier (get counts :earlier 0)
         :shortened-unresolved (get counts :unresolved 0)
         :exhausted? (pos? (:remaining final))}))))
