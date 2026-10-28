;; Ported from clj-surgeon, https://github.com/realgenekim/clj-surgeon
;; source commit affdfe3fd59a63b3fbbb50da16b82509fafea907,
;; src/clj_surgeon/extract.clj.
;; MIT License, Copyright (c) 2026 Gene Kim.
;; The original notice is in docs/third-party/clj-surgeon-LICENSE.
;; SPDX-License-Identifier: MIT

(ns samizdat.surgeon.extract
  "Pure namespace extraction plans over one frozen source map."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.parser :as parser]
            [rewrite-clj.zip :as z]
            [samizdat.surgeon.analyze :as analyze]
            [samizdat.surgeon.cljc.require-ops :as require-ops]
            [samizdat.surgeon.extract-header :as extract-header]
            [samizdat.surgeon.forms :as forms]
            [samizdat.surgeon.outline :as outline]
            [samizdat.surgeon.quoted-var-refs :as quoted-var-refs]))

(defn publicize-defn-source [source]
  (let [form (z/of-string source) head (z/down form)]
    (when-not (= "defn-" (z/string head))
      (throw (ex-info "Only exact defn- forms can be made public"
                      {:error-type :unsupported-public-form :form-head (z/string head)})))
    (-> head (z/replace (parser/parse-string "defn")) z/root-string)))

(defn file-path->ns-name
  ([path] (file-path->ns-name path ["src"]))
  ([path source-paths]
   (let [source-paths (or (seq source-paths) ["src"])
         norm (str/replace path #"^\./" "")
         match-root (fn [root]
                      (cond
                        (str/starts-with? norm (str root "/")) (subs norm (inc (count root)))
                        (str/includes? norm (str "/" root "/"))
                        (let [i (str/index-of norm (str "/" root "/"))]
                          (subs norm (+ i (count root) 2)))))
         matched (->> source-paths
                      (keep (fn [root] (when-let [relative (match-root root)]
                                         {:root root :relative relative})))
                      (sort-by #(count (:root %)) >) first)
         relative (if matched (:relative matched)
                      (if (str/starts-with? norm "src/") (subs norm 4) norm))]
     (-> relative (str/replace #"\.clj[sc]?$" "")
         (str/replace "/" ".") (str/replace "_" "-")))))

(defn- source-line-chunks [source]
  (let [chunks (vec (str/split source #"(?<=\n)" -1))]
    (if (and (seq chunks) (= "" (peek chunks))) (pop chunks) chunks)))

(defn- removal-range [line-chunks {:keys [comment-start end-line]}]
  (let [trailing-line end-line
        end (if (and (< trailing-line (count line-chunks))
                     (str/blank? (nth line-chunks trailing-line)))
              (inc trailing-line) trailing-line)]
    {:start comment-start :end end}))

(defn- remove-form-ranges [source form-texts]
  (let [line-chunks (source-line-chunks source)
        ranges (sort-by :start > (map #(removal-range line-chunks %) form-texts))]
    (->> ranges
         (reduce (fn [chunks {:keys [start end]}]
                   (when-not (<= 0 start end (count chunks))
                     (throw (ex-info "Extraction range is outside the source snapshot"
                                     {:error-type :invalid-extraction-range
                                      :start start :end end :line-count (count chunks)})))
                   (into (subvec chunks 0 start) (subvec chunks end))) line-chunks)
         (apply str))))

(defn- validate-complete-source! [file source]
  (try (parser/parse-string-all source) source
       (catch Exception error
         (throw (ex-info "Extraction candidate is not complete Clojure source"
                         {:error-type :invalid-extraction-candidate :file file :source-unchanged true} error)))))

(defn compile-candidates [{:keys [source source-file target-file form-ranges target-source
                                  target-ns target-alias source-referred-forms]}]
  (let [source-without-forms (remove-form-ranges source form-ranges)
        future-source (if (seq source-referred-forms)
                        (require-ops/insert-into-require-sorted
                         source-without-forms (symbol target-ns)
                         (some-> target-alias symbol) (mapv symbol source-referred-forms))
                        source-without-forms)]
    {:source (validate-complete-source! source-file future-source)
     :target (validate-complete-source! target-file target-source)}))

(defn- caller-proof [candidates decisions]
  (let [candidates (vec (sort (distinct candidates)))
        supplied (set (keys decisions))
        required (set candidates)
        missing (vec (sort (set/difference required supplied)))
        extra (vec (sort (set/difference supplied required)))
        invalid (vec (sort (remove #{:review-required :not-a-caller} (vals decisions))))]
    {:status :structural-candidates
     :semantic-complete false
     :candidates candidates
     :unknown-callers missing
     :dispositions (select-keys decisions candidates)
     :dispositions-complete (and (empty? missing) (empty? extra) (empty? invalid))
     :extra-dispositions extra
     :invalid-dispositions invalid}))

(defn- disposition-refusal [proof]
  (assoc {:error-type :caller-disposition-required
          :source-unchanged true :target-unchanged true}
         :caller-proof proof))

(defn compile-plan
  "Compile one extraction without file, process, clock, or registry access.

  Caller decisions map every textual/quoted structural candidate to
  :review-required or :not-a-caller. They never promote the evidence to
  semantic completeness."
  [{:keys [file source sources workspace-sources forms to target-ns require-policy
           public-forms derive-required-public-forms caller-decisions]
    :or {require-policy :minimal public-forms [] derive-required-public-forms false
         caller-decisions {}}}]
  (let [sources (or sources workspace-sources {})
        source (or source (get sources file))
        captured-sources (assoc sources (str file) source)]
    (cond
      (not (string? source))
      {:error-type :missing-extraction-source}

      (some? (get captured-sources to))
      {:error-type :extraction-target-exists
       :source-unchanged true :target-unchanged true}

      :else
      (let [lines (vec (str/split-lines source))
            ol (outline/outline-source file source)
            all-forms (:forms ol)
            source-ns (some-> (:ns ol) str)
            form-names (set (map str forms))
            matched (->> all-forms
                         (filter #(and (contains? form-names (str (:name %)))
                                       (not= 'declare (:type %)))) vec)
            missing (set/difference form-names (set (map #(str (:name %)) matched)))]
        (cond
          (seq missing) {:error (str "Forms not found: " (str/join ", " (sort missing)))
                         :error-type :extraction-forms-not-found}
          (nil? source-ns) {:error-type :missing-source-namespace}
          (nil? target-ns) {:error-type :missing-target-namespace}
          :else
          (let [src-zloc (z/of-string source {:track-position? true})
                ns-zloc (loop [location src-zloc]
                          (when location
                            (if (and (z/list? location) (= "ns" (some-> location z/down z/string)))
                              location (recur (z/right location)))))
                ns-form-text (when ns-zloc (z/string ns-zloc))
                zloc (analyze/string->zloc source)
                extracted-names (set (map #(str (:name %)) matched))
                topo-order (->> (:sorted (analyze/topological-sort zloc)) (filter extracted-names))
                unordered-forms (set/difference extracted-names (set topo-order))
                form-texts
                (->> (sort-by :line matched)
                     (mapv (fn [form]
                             (let [form-start (let [index (- (:line form) 2)]
                                                (loop [line-index index]
                                                  (if (neg? line-index) 0
                                                      (if (str/starts-with? (str/trim (nth lines line-index "")) ";")
                                                        (recur (dec line-index)) (inc line-index)))))
                                   form-end (:end-line form)]
                               {:name (str (:name form)) :type (str (:type form))
                                :line (:line form) :end-line form-end :comment-start form-start
                                :text (str/join "\n" (subvec lines form-start form-end))}))))
                texts-by-name (into {} (map (juxt :name identity) form-texts))
                ordered-texts (mapv #(get texts-by-name %) topo-order)
                header-result (when (empty? unordered-forms)
                                (extract-header/compile-target-header
                                 {:source-ns-form ns-form-text :target-ns target-ns
                                  :form-sources (mapv :text ordered-texts) :require-policy require-policy}))
                alias-result (if (= :copy-all require-policy) {:ok true :aliases {}}
                                 (extract-header/source-aliases ns-form-text))
                target-alias (when (and (:ok alias-result) (not= :copy-all require-policy))
                               (extract-header/allocate-alias target-ns (:aliases alias-result)))
                remaining-callers (extract-header/remaining-source-callers source extracted-names)
                source-referred (extract-header/source-referred-forms remaining-callers)
                private-form-names (->> matched (filter #(forms/private-form? (str (:type %))))
                                        (map #(str (:name %))) set)
                supported-public-form-names (->> matched (filter #(= "defn-" (str (:type %))))
                                                 (map #(str (:name %))) set)
                required-public-forms (set/intersection private-form-names (set source-referred))
                requested-public-forms (if derive-required-public-forms required-public-forms
                                           (set (map str public-forms)))
                invalid-public-forms (set/difference requested-public-forms private-form-names)
                unsupported-public-forms (set/difference requested-public-forms supported-public-form-names)
                missing-required-public-forms (set/difference required-public-forms requested-public-forms)
                publicized-texts (if (seq unsupported-public-forms) ordered-texts
                                     (mapv (fn [form]
                                             (if (contains? requested-public-forms (:name form))
                                               (update form :text publicize-defn-source) form)) ordered-texts))
                new-file-content (when (:ok header-result)
                                   (str (str/join "\n\n" (concat [(:ns-form header-result)]
                                                                 (map :text publicized-texts))) "\n"))
                other-files (->> captured-sources
                                 (remove #(= (str file) (str (key %))))
                                 (filter (fn [[_ content]]
                                           (and (string? content)
                                                (some #(str/includes? content (str %)) extracted-names))))
                                 (map (comp str key)) sort vec)
                subjects (mapv #(str source-ns "/" %) (sort extracted-names))
                quoted-proof (quoted-var-refs/scan-sources
                              (into {} (filter (comp string? val) captured-sources)) subjects)
                proof (caller-proof other-files caller-decisions)]
            (cond
              (seq unordered-forms)
              {:error-type :extraction-forms-not-orderable
               :unordered-forms (vec (sort unordered-forms))
               :source-unchanged true :target-unchanged true}
              (seq invalid-public-forms)
              {:error-type :invalid-public-forms
               :invalid-public-forms (vec (sort invalid-public-forms))}
              (seq unsupported-public-forms)
              {:error-type :unsupported-public-forms
               :unsupported-public-forms (vec (sort unsupported-public-forms))}
              (seq missing-required-public-forms)
              {:error-type :required-public-forms
               :missing-required-public-forms (vec (sort missing-required-public-forms))
               :source-unchanged true :target-unchanged true}
              (not (:ok header-result)) header-result
              (not (:ok alias-result)) alias-result
              (not (:ok quoted-proof)) quoted-proof
              (not (:dispositions-complete proof)) (disposition-refusal proof)
              :else
              (let [candidates (compile-candidates
                                {:source source :source-file file :target-file to :form-ranges form-texts
                                 :target-source new-file-content :target-ns target-ns :target-alias target-alias
                                 :source-referred-forms source-referred})]
                {:file file :to to :source-ns source-ns :target-ns target-ns :target-alias target-alias
                 :require-policy (:require-policy header-result)
                 :copied-require-count (:copied-require-count header-result)
                 :target-requires (:target-requires header-result)
                 :omitted-target-requires (:omitted-target-requires header-result)
                 :remaining-source-callers remaining-callers :source-referred-forms source-referred
                 :required-public-forms (vec (sort required-public-forms))
                 :missing-required-public-forms (vec (sort missing-required-public-forms))
                 :public-forms (vec (sort requested-public-forms))
                 :source-require-added (boolean (seq source-referred))
                 :forms-to-extract (mapv :name form-texts) :form-count (count matched)
                 :callers-to-review other-files
                 :quoted-var-references (mapv #(select-keys % [:subject :file :line :character :reference-authority])
                                              (:locations quoted-proof))
                 :caller-proof proof
                 :future-sources {file (:source candidates) to (:target candidates)}
                 :created-files [to] :deleted-files [] :renamed-files []
                 :resource-effects [{:operation :create :file to}
                                    {:operation :write :file file}]}))))))))

(defn plan [opts] (compile-plan opts))
