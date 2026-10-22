;; samizdat - a self-hosting agentic harness
;; License: GPL-3.0-or-later

(ns samizdat.agent.tools.atlas
  "The `atlas` tool (RFC-016): the project's call graph and the flows in it,
  for the questions grep answers badly — what calls this, what does a
  change here reach, which of the programs inside the project does it
  serve, and what changed since the last snapshot. Read-only."
  (:require [clojure.string :as str]
            [samizdat.agent.tools.base :as base]
            [samizdat.atlas :as atlas]
            [samizdat.atlas.graph :as g]
            [samizdat.prompt :as prompt]))

(def ^:private usage (delay (prompt/render "atlas-tool" {:usage true})))

(defn- say [m] (prompt/render "atlas-tool" m))

(defn- site [{:keys [file line]}] (str file ":" line))

(defn- capped
  "`xs` cut to policy :max-listed: [shown more]."
  [xs]
  (let [n (or (:max-listed (atlas/policy)) (count xs))]
    [(vec (take n xs)) (max 0 (- (count xs) n))]))

(defn- listing [branch flag m xs]
  (let [[shown more] (capped xs)]
    (base/ok branch (say (merge {:listing true flag true :items shown}
                                (when (pos? more) {:more more})
                                m)))))

(defn- neighbours [branch gr id step edge-of]
  (listing branch step {:id id}
           (for [n (if (= :callers step) (g/callers gr id) (g/callees gr id))]
             (str n "  " (str/join ", " (map site (:sites (edge-of n))))))))

(defn- flow [branch root entry]
  (if-let [{:keys [paths path-count truncated? labels loops]} (atlas/flow-detail root entry)]
    (base/ok branch (say {:flow true :entry entry :count path-count :truncated truncated?
                          :shown (count paths)
                          :paths (for [p paths] (str/join " → " (map #(get labels % %) p)))
                          :loops (when (seq loops)
                                   (str/join "; " (map #(str/join " ⇄ " %) (vals loops))))}))
    (base/malformed branch (say {:missing true :id entry}))))

(defn- card [branch root id]
  (let [{:keys [node source callers callees flows]} (atlas/card root id)]
    (base/ok branch (say {:card true :id id :kind (name (:kind node)) :file (:file node)
                          :line (:line node) :doc (:doc node) :source source
                          :flow-count (count flows) :callee-count (count callees)
                          :caller-count (count callers)}))))

(defn- changes [{:keys [branch conn root]}]
  (if-let [last-id (:id (first (atlas/snapshots conn root)))]
    (let [{:keys [added removed changed]} (atlas/diff conn last-id nil root nil)]
      (base/ok branch (say {:changes true :added (count added) :removed (count removed)
                            :changed (count changed)
                            :items (first (capped (concat (map #(str "+ " %) added)
                                                          (map #(str "- " %) removed)
                                                          (map #(str "~ " %) changed))))})))
    (base/ok branch (say {:no-history true}))))

(defmethod base/run-tool "atlas" [{:keys [branch root] :as ctx}]
  ;; Read-only, :neutral like grep: knowing the graph establishes nothing.
  (let [op (some-> (base/arg ctx :op) str str/trim str/lower-case not-empty)
        id (some-> (base/arg ctx :id) str str/trim not-empty)]
    (cond
      (nil? root) (base/fail branch (say {:no-root true}))
      (not (#{"search" "callers" "callees" "card" "impact" "flows" "flow" "changes"} op))
      (base/malformed branch (str "atlas needs a valid op. " @usage))

      (= "search" op)
      (if-let [q (some-> (base/arg ctx :q) str str/trim not-empty)]
        (listing branch :search {:q q}
                 (for [{:keys [id kind]} (atlas/search root q)] (str id " (" (name kind) ")")))
        (base/malformed branch (str (base/missing ctx :q) " " @usage)))

      (= "flow" op)
      (if-let [e (or (some-> (base/arg ctx :entry) str str/trim not-empty) id)]
        (flow branch root e)
        (base/malformed branch (str (base/missing ctx :entry) " " @usage)))

      (= "changes" op) (changes ctx)

      (nil? id) (base/malformed branch (str (base/missing ctx :id) " " @usage))

      :else
      (let [gr (atlas/graph root)]
        (if-not (g/node gr id)
          (base/malformed branch (say {:missing true :id id}))
          (case op
            "callers" (neighbours branch gr id :callers #(g/edge gr % id))
            "callees" (neighbours branch gr id :callees #(g/edge gr id %))
            "card" (card branch root id)
            "flows" (listing branch :flows {:id id} (:flows (atlas/card root id)))
            "impact" (let [up (sort (g/reach-set #(get-in gr [:in %]) id))]
                       (listing branch :impact {:id id :total (count up)
                                                :flow-count (count (:flows (atlas/card root id)))}
                                up))))))))
