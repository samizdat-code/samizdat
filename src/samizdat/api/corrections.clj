;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.api.corrections
  "The HTTP side of corrections (karamazov-05z0.4): draft a rule from a
  person's complaint about a run, then save it for the person or for the
  project. Drafting proposes and saving disposes — nothing is stored until
  the person has seen the draft and said where it goes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [samizdat.agent.corrections :as corrections]
            [samizdat.agent.gates :as gates]
            [samizdat.layers :as layers]
            [samizdat.prompt :as prompt]
            [samizdat.store.journal :as journal]
            [samizdat.userspace :as userspace]))

(defn- said [k & [ctx]]
  (str/trim (prompt/render "corrections-api" (assoc ctx k true))))

(defn draft!
  "A rule drafted from `complaint` by `complete` — (fn [prompt] -> text) —
  against the model's last turns of `run-id` (one branch's with
  `branch-id`). {:id :instruction :when :matched?} or {:error}."
  [conn {:keys [complaint run-id branch-id]} complete]
  (let [p (gates/threshold :corrections)
        outputs (if (and conn run-id)
                  (journal/recent-assistant-texts conn run-id branch-id (:window p))
                  [])]
    (cond
      (str/blank? (str complaint)) {:error (said :no-complaint)}
      (empty? outputs) {:error (said :no-turns)}
      :else (let [d (corrections/draft! complete
                                        {:complaint complaint :outputs outputs
                                         :render #(prompt/render "complaint" %)}
                                        p)]
              (if (= :no-draft (:error d))
                {:error (said :no-draft)}
                d)))))

(defn- with-header
  "`body` printed under the leading comment block of `old-text`, so the
  explanation a file opens with survives a save."
  [old-text body]
  (let [header (take-while #(or (str/blank? %) (str/starts-with? (str/triml %) ";"))
                           (str/split-lines (str old-text)))]
    (str (str/join "\n" header) (when (seq header) "\n") (pr-str body) "\n")))

(defn- person-file []
  (some-> (layers/global-dir) (str "/corrections.edn")))

(defn- person-body []
  (when-let [f (person-file)]
    (when (.isFile (io/file f))
      (edn/read-string (slurp f)))))

(defn listing
  "Both sets as they stand: {:person {...} :project {...}}."
  []
  {:person (or (:corrections (person-body)) {})
   :project (or (:corrections (try (userspace/edn-body :policy "corrections")
                                   (catch Throwable _ nil)))
                {})})

(defn save!
  "Add `rule` ({:id :instruction :when :priority?}) to `scope`'s set —
  :person (~/.config/samizdat/corrections.edn) or :project (the
  :corrections policy, a new version). {:status :saved|:refused :problem}."
  [{:keys [id] :as rule} scope]
  (let [entry (select-keys rule [:instruction :when :priority])
        current (case scope
                  :person (person-body)
                  :project (try (userspace/edn-body :policy "corrections")
                                (catch Throwable _ nil)))
        body (assoc-in (or current {:corrections {}}) [:corrections (str id)] entry)]
    (if-let [problem (or (when (str/blank? (str id)) (said :no-id))
                         (corrections/describe
                          (corrections/problems body (gates/threshold :corrections))))]
      {:status :refused :problem problem}
      (case scope
        :person (if-let [f (person-file)]
                  (do (io/make-parents f)
                      (spit f (with-header (when (.isFile (io/file f)) (slurp f)) body))
                      (layers/forget-files!)
                      {:status :saved :scope :person :path f})
                  {:status :refused :problem (said :no-home)})
        :project (if-let [v (userspace/save! :policy "corrections"
                                             (with-header (try (userspace/body :policy "corrections")
                                                               (catch Throwable _ nil))
                                                          body)
                                             (str "correction " id " from a complaint"))]
                   {:status :saved :scope :project :version v}
                   {:status :refused :problem (said :no-project)})
        {:status :refused :problem (said :bad-scope {:scope (pr-str scope)})}))))
