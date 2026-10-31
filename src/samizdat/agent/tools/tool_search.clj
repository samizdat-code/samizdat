;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.tool-search
  "Search and exact selection inside a role's existing full tool surface."
  (:require [jolt.time]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.roles :as roles]
            [samizdat.agent.tools.base :as base]))

(defn canonical-catalogue
  "The role-scoped canonical system documentation, without dynamic presentation.
  Resolved late because agent.loop requires the tool registry that loads this
  namespace."
  [role]
  ((requiring-resolve 'samizdat.agent.loop/system-prompt-for) role false))

(defn- allowed-entries [role]
  (vec (filter #(roles/may-use? role (:name %))
               (roles/catalogue-entries (canonical-catalogue role)))))

(defn- parsed-args [args]
  (cond
    (map? args) args
    (string? args) (try (json/read-str args :key-fn keyword)
                        (catch Throwable _ nil))
    :else nil))

(defn- selected-names [select]
  (let [select (base/listed select)]
    (cond
      (sequential? select) (map str select)
      (some? select) [(str select)]
      :else [])))

(defn- with-companions [names {:keys [companion-groups]}]
  (let [requested (set names)]
    (into requested
          (mapcat (fn [group]
                    (when (some requested group) group)))
          companion-groups)))

(defn- successful-search-turn? [turn]
  (and (= "tool_search" (str (or (:tool turn) (:tool_name turn))))
       (= "success" (some-> (:category turn) name))))

(defn advertised-from-turns
  "Rebuild advertised tools from successful tool_search arguments and current
  role authorization. Returned docs are ordered canonical selections only; core
  docs remain in the system message."
  [role core-tools turns]
  (let [policy (gates/threshold :tool-discovery)
        core (set (map str (or core-tools (roles/core-tools role))))
        entries (allowed-entries role)
        by-name (into {} (map (juxt :name identity)) entries)
        selected (reduce (fn [names turn]
                           (if (successful-search-turn? turn)
                             (let [args (parsed-args (:args turn))]
                               (into names
                                     (with-companions
                                       (selected-names (:select args))
                                       policy)))
                             names))
                         #{}
                         turns)
        names (into (sorted-set)
                    (filter #(and (contains? by-name %) (roles/may-use? role %)))
                    (into core selected))
        docs (vec (keep (fn [nm]
                          (when (and (contains? selected nm) (contains? by-name nm))
                            (select-keys (get by-name nm) [:name :doc])))
                        names))]
    {:tools names :docs docs}))

(defn- clipped [s limit]
  (let [s (str s)]
    (if (and limit (> (count s) limit))
      (str (str/trimr (subs s 0 limit)) "…")
      s)))

(defn- query-tokens [query]
  (remove str/blank? (str/split (str/lower-case (str query)) #"\s+")))

(defn- matching-entries [entries query]
  (let [tokens (query-tokens query)]
    (->> entries
         (keep (fn [{:keys [name summary] :as entry}]
                 (let [text (str/lower-case (str name " " summary))
                       score (count (filter #(str/includes? text %) tokens))]
                   (when (pos? score)
                     (assoc entry :score score)))))
         (sort-by (juxt (comp - :score) :name))
         (map #(dissoc % :score)))))

(defn- query-result [branch entries {:keys [query limit]}]
  (let [{:keys [max-results summary-chars query-heading query-only-note no-matches]}
        (gates/threshold :tool-discovery)
        limit (or limit max-results)
        matches (take (min max-results limit) (matching-entries entries query))
        body (if (seq matches)
               (str query-heading "\n"
                    (str/join "\n" (map (fn [{:keys [name summary]}]
                                          (str "- " name " — "
                                               (clipped summary summary-chars)))
                                        matches)))
               no-matches)]
    (base/ok branch (str body "\n\n" query-only-note) :category :success)))

(defn- select-result [ctx branch entries select]
  (let [{:keys [selection-heading selection-error] :as policy}
        (gates/threshold :tool-discovery)
        role (:role branch)
        requested (selected-names select)
        expanded (with-companions requested policy)
        by-name (into {} (map (juxt :name identity)) entries)
        names (into (sorted-set) (filter by-name) expanded)]
    (if (empty? names)
      (base/malformed branch selection-error)
      (let [turns (conj (vec (:turns branch))
                        {:tool "tool_search" :category :success
                         :args {:select requested}})
            advertised (advertised-from-turns role nil turns)
            docs (vec (keep #(some-> (get by-name %) (select-keys [:name :doc])) names))
            result (str selection-heading "\n\n"
                        (str/join "\n\n" (map :doc docs)))]
        (base/ok (assoc branch
                        :advertised-tools (:tools advertised)
                        :advertised-tool-docs (:docs advertised))
                 result :category :success)))))

(defmethod base/run-tool "tool_search"
  [{:keys [branch args] :as ctx}]
  (let [role (:role branch)
        entries (allowed-entries role)
        query (base/arg ctx :query)
        select (base/arg ctx :select)
        limit (base/arg ctx :limit)
        {:keys [exclusive-arguments invalid-limit]} (gates/threshold :tool-discovery)]
    (cond
      (and (some? query) (some? select))
      (base/malformed branch exclusive-arguments)

      (some? select)
      (select-result ctx branch entries select)

      (and limit (not (and (integer? limit) (pos? limit))))
      (base/malformed branch invalid-limit)

      (and (some? query) (not (str/blank? (str query))))
      (query-result branch entries {:query query :limit limit})

      :else
      (base/malformed branch (base/missing ctx :query)))))
