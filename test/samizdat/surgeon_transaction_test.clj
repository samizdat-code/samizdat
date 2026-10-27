;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; Transaction fixtures ported from clj-surgeon, MIT License,
;; Copyright (c) 2026 Gene Kim.
;; SPDX-License-Identifier: GPL-3.0-or-later AND MIT

(ns samizdat.surgeon-transaction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rewrite-clj.parser :as parser]
            [samizdat.surgeon.intent-transaction :as intent]))

(defn- compile-changes
  ([sources spec] (intent/compile-changes sources spec))
  ([sources spec options] (intent/compile-changes sources spec options)))

(defn- refusal
  ([sources spec]
   (try (compile-changes sources spec) nil
        (catch clojure.lang.ExceptionInfo exception (ex-data exception))))
  ([sources spec options]
   (try (compile-changes sources spec options) nil
        (catch clojure.lang.ExceptionInfo exception (ex-data exception)))))

(defn- replacement
  [id files forms find to matches]
  (cond-> {:id id :in files :find find :do [:replace to]
           :expect {:matches matches}}
    forms (assoc :forms forms)))

(deftest heterogeneous-changes-compile-against-one-frozen-source-map
  (let [a "src/a.clj"
        b "src/b.clj"
        sources {a (str "(ns app.a)\n\n"
                        "(defn alpha [x] [(old-call x) :body])\n\n"
                        "(defn obsolete [] :gone)\n")
                 b "(ns app.b)\n(def view {:a 1 :b 2})\n"}
        spec {:changes
              [(replacement :call [a] ['alpha] "(old-call x)" "(new-call x)" 1)
               (replacement :body [a] ['alpha] ":body" ":ready" 1)
               {:id :insert :in [a] :forms ['alpha]
                :do [:insert-after ["(defn beta [] :b)"]]
                :expect {:matches 1 :each-form 1}}
               {:id :delete :in [a] :forms ['obsolete]
                :do [:delete true] :expect {:matches 1 :each-form 1}}
               {:id :entry :in [b] :find "{:a 1 :b 2}"
                :do [:assoc-entry {:key ":status" :value ":ready"}]
                :expect {:matches 1 :each-file 1}}]
              :expect {:changes 5 :edits 5 :files 2}}
        result (compile-changes sources spec)]
    (is (:ok result) (pr-str result))
    (is (= (str "(ns app.a)\n\n"
                "(defn alpha [x] [(new-call x) :ready])\n\n"
                "(defn beta [] :b)\n")
           (get-in result [:future-sources a])))
    (is (= "(ns app.b)\n(def view {:a 1 :b 2 :status :ready})\n"
           (get-in result [:future-sources b])))
    (is (= 5 (count (:edits result))))
    (is (= {:changes 5 :edits 5 :files 2} (:counts result)))
    (is (get-in result [:validated :whole-files-parsed]))
    (is (= sources (select-keys sources (keys (:future-sources result)))))))

(deftest exact-per-change-and-aggregate-expectations-refuse-the-batch
  (let [file "src/counts.clj"
        source "(ns counts)\n(def value [(inc 1) (inc 1)])\n"
        change (replacement :increment [file] nil "(inc 1)" "(inc 2)" 1)
        valid {:changes [change] :expect {:changes 1 :edits 2 :files 1}}]
    (is (= :expect-count-mismatch
           (:surgeon/error (refusal {file source} valid))))
    (is (= 2 (:actual-count (refusal {file source} valid))))
    (is (= :transaction-expectation-mismatch
           (:surgeon/error
             (refusal {file source}
                      {:changes [(assoc change :expect {:matches 2})]
                       :expect {:changes 1 :edits 1 :files 1}}))))
    (is (= :duplicate-change-id
           (:surgeon/error
             (refusal {file source}
                      {:changes [change change]
                       :expect {:changes 2 :edits 2 :files 1}}))))))

(deftest missing-change-ids-refuse-before-selection
  (let [file "src/missing_ids.clj"
        source "(ns missing-ids)\n(def value [(old x) :body])\n"
        data (refusal
               {file source}
               {:changes [(assoc (replacement nil [file] nil "(old x)" "(new x)" 1)
                                 :id nil)
                          (assoc (replacement nil [file] nil ":body" ":ready" 1)
                                 :id nil)]
                :expect {:changes 2 :edits 2 :files 1}})]
    (is (= :invalid-change-id (:surgeon/error data)))
    (is (= 0 (:change-index data)))
    (is (nil? (:future-sources data)))))

(deftest frozen-selection-does-not-cascade-and-overlaps-refuse
  (let [file "src/frozen.clj"
        source "(ns frozen)\n(def value [(old x) (new y)])\n"
        spec {:changes [(replacement :old [file] nil "(old x)" "(new x)" 1)
                        (replacement :new [file] nil "(new y)" "(final y)" 1)]
              :expect {:changes 2 :edits 2 :files 1}}
        result (compile-changes {file source} spec)]
    (is (= "(ns frozen)\n(def value [(new x) (final y)])\n"
           (get-in result [:future-sources file])))
    (is (not (str/includes? (get-in result [:future-sources file]) "final x"))))
  (let [file "src/overlap.clj"
        source "(ns overlap)\n(def value (outer (inner x)))\n"
        data (refusal
               {file source}
               {:changes [(replacement :outer [file] ['value]
                                       "(outer (inner x))" "(outer (new x))" 1)
                          (replacement :inner [file] ['value]
                                       "(inner x)" "(new x)" 1)]
                :expect {:changes 2 :edits 2 :files 1}})]
    (is (= :overlapping-intents (:surgeon/error data)))
    (is (= [:outer :inner] (:change-ids data)))
    (is (nil? (:future-sources data)))))

(deftest insertion-preserves-collection-layout-and-refuses-comment-gaps
  (doseq [[find operator inserted expected]
          [[":a" :insert-after [":b"]
            "(ns sample)\n(def xs [:a :b :c])\n"]
           [":c" :insert-before [":b"]
            "(ns sample)\n(def xs [:a :b :c])\n"]
           [":c" :insert-before [":b" "2"]
            "(ns sample)\n(def xs {:a 1 :b 2 :c 3})\n"]]]
    (let [file "src/insert.clj"
          map? (str/includes? expected "{:a")
          source (if map?
                   "(ns sample)\n(def xs {:a 1 :c 3})\n"
                   "(ns sample)\n(def xs [:a :c])\n")
          result (compile-changes
                   {file source}
                   {:changes [{:id :insert :in [file] :forms ['xs] :find find
                               :do [operator inserted] :expect {:matches 1}}]
                    :expect {:changes 1 :edits 1 :files 1}})]
      (is (:ok result) (pr-str result))
      (is (= expected (get-in result [:future-sources file])))))
  (doseq [[find operator] [[":c" :insert-before] [":a" :insert-after]]]
    (let [file "src/comments.clj"
          source "(ns sample)\n(def xs [:a\n ;; belongs to :c\n :c])\n"
          data (refusal
                 {file source}
                 {:changes [{:id :ambiguous :in [file] :forms ['xs] :find find
                             :do [operator [":b"]] :expect {:matches 1}}]
                  :expect {:changes 1 :edits 1 :files 1}})]
      (is (= :ambiguous-insertion-gap (:surgeon/error data)))
      (is (= :ambiguous (:change-id data))))))

(deftest owner-deletion-removes-only-attached-comments
  (let [file "src/delete.clj"
        source (str "(ns sample)\n\n"
                    ";; attached to doomed\n"
                    "(defn doomed [] 1)\n\n"
                    ";; detached from kept\n\n"
                    "(defn kept [] 2)\n")
        result (compile-changes
                 {file source}
                 {:changes [{:id :delete :in [file] :forms ['doomed]
                             :do [:delete true] :expect {:matches 1}}]
                  :expect {:changes 1 :edits 1 :files 1}})]
    (is (= (str "(ns sample)\n\n"
                ";; detached from kept\n\n"
                "(defn kept [] 2)\n")
           (get-in result [:future-sources file])))))

(def ^:private binding-analysis
  {:locals
   [{:row 2 :col 21 :end-row 2 :end-col 28 :name 'sort-by :id 1}
    {:row 3 :col 22 :end-row 3 :end-col 29 :name 'sort-by :id 2}]
   :local-usages
   [{:row 2 :col 35 :end-row 2 :end-col 42 :name 'sort-by :id 1}
    {:row 2 :col 54 :end-row 2 :end-col 61 :name 'sort-by :id 1}
    {:row 3 :col 39 :end-row 3 :end-col 46 :name 'sort-by :id 2}]})

(deftest binding-rename-is-local-and-preserves-external-keys
  (let [file "src/binding.clj"
        source (str "(ns demo)\n"
                    "(defn feed [{:keys [sort-by] :or {sort-by :score}}] [sort-by :sort-by clojure.core/sort-by])\n"
                    "(defn table [{:keys [sort-by]}] (name sort-by))\n")
        spec {:changes [{:id :rename :in [file] :forms ['feed 'table]
                         :do [:rename-binding {:from 'sort-by :to 'sort-field
                                               :preserve-external-key true}]
                         :expect {:matches 5 :each-form 1}}]
              :expect {:changes 1 :edits 5 :files 1}}
        result (compile-changes {file source} spec
                                {:binding-analysis {file binding-analysis}})]
    (is (:ok result) (pr-str result))
    (is (= (str "(ns demo)\n"
                "(defn feed [{:keys [] :or {sort-field :score} sort-field :sort-by}] [sort-field :sort-by clojure.core/sort-by])\n"
                "(defn table [{:keys [] sort-field :sort-by}] (name sort-field))\n")
           (get-in result [:future-sources file])))
    (is (= 2 (get-in result [:changes 0 :binding-count])))))

(deftest binding-rename-refuses-capture-and-comment-sensitive-destructuring
  (let [file "src/binding.clj"
        source "(ns demo)\n(defn feed [{:keys [sort-by]}] sort-by)\n"
        spec {:changes [{:id :rename :in [file] :forms ['feed]
                         :do [:rename-binding {:from 'sort-by :to 'sort-field
                                               :preserve-external-key true}]
                         :expect {:matches 2}}]
              :expect {:changes 1 :edits 2 :files 1}}
        analysis {:locals [{:row 2 :col 21 :end-row 2 :end-col 28
                            :name 'sort-by :id 1}
                           {:row 2 :col 32 :end-row 2 :end-col 42
                            :name 'sort-field :id 2}]
                  :local-usages [{:row 2 :col 32 :end-row 2 :end-col 39
                                  :name 'sort-by :id 1}]}
        data (refusal {file source} spec {:binding-analysis {file analysis}})]
    (is (= :binding-capture-risk (:surgeon/error data)))))

(deftest assoc-entry-matches-semantically-and-keeps-comments
  (let [file "src/maps.clj"
        source (str "(ns demo)\n"
                    "(defn assertions []\n"
                    "  [(is (= {:a 1 :b 2} actual-a))\n"
                    "   (is (= {:a 1\n"
                    "           ;; Keep this exact explanation.\n"
                    "           :b 2} actual-b))])\n")
        result (compile-changes
                 {file source}
                 {:changes [{:id :status :in [file] :forms ['assertions]
                             :find "{:a 1 :b 2}"
                             :do [:assoc-entry {:key ":status" :value ":ready"}]
                             :expect {:matches 2 :each-file 2}}]
                  :expect {:changes 1 :edits 2 :files 1}})
        future (get-in result [:future-sources file])]
    (is (:ok result) (pr-str result))
    (is (str/includes? future "{:a 1 :b 2 :status :ready}"))
    (is (str/includes? future ";; Keep this exact explanation."))
    (is (str/includes? future ":b 2 :status :ready}"))))

(deftest assoc-entry-refuses-duplicate-map-keys-before-semantic-matching
  (let [file "src/duplicate_map.clj"
        duplicate-source "(ns demo)\n(def view {:a 1 :a 2})\n"
        spec (fn [find]
               {:changes [{:id :status :in [file] :find find
                           :do [:assoc-entry {:key ":status" :value ":ready"}]
                           :expect {:matches 1}}]
                :expect {:changes 1 :edits 1 :files 1}})]
    (doseq [[source find] [[duplicate-source "{:a 2}"]
                           ["(ns demo)\n(def view {:a 2})\n" "{:a 1 :a 2}"]
                           ["(ns demo)\n(def view {nil 1 nil 2})\n" "{nil 2}"]
                           ["(ns demo)\n(def view {nil 2})\n" "{nil 1 nil 2}"]
                           ["(ns demo)\n(def view {false 1 false 2})\n" "{false 2}"]
                           ["(ns demo)\n(def view {false 2})\n" "{false 1 false 2}"]]]
      (let [data (refusal {file source} (spec find))]
        (is (= :duplicate-map-key (:surgeon/error data)))
        (is (= file (:file data)))
        (is (nil? (:future-sources data)))))))

(deftest complete-future-files-must-parse
  (let [file "src/future.clj"
        source "(ns future)\n(def value :old)\n"
        future "(ns future)\n(def value :new)\n"
        original parser/parse-string-all]
    (with-redefs [parser/parse-string-all
                  (fn [candidate]
                    (if (= future candidate)
                      (throw (ex-info "injected malformed future" {}))
                      (original candidate)))]
      (let [data (refusal
                   {file source}
                   {:changes [(replacement :value [file] nil ":old" ":new" 1)]
                    :expect {:changes 1 :edits 1 :files 1}})]
        (is (= :invalid-future-source (:surgeon/error data)))
        (is (= file (:file data)))
        (is (nil? (:future-sources data)))))))

(deftest canonical-effect-identity-is-order-independent-and-source-bound
  (let [a "src/a.clj"
        b "src/b.clj"
        sources {a "(ns app.a)\n(def value [(old x) :body])\n"
                 b "(ns app.b)\n(def view {:status :idle})\n"}
        changes [(replacement :call [a] nil "(old x)" "(new x)" 1)
                 (replacement :body [a] nil ":body" ":ready" 1)
                 (replacement :status [b] nil ":idle" ":ready" 1)]
        forward (compile-changes sources {:changes changes
                                          :expect {:changes 3 :edits 3 :files 2}})
        backward (compile-changes sources {:changes (vec (reverse changes))
                                           :expect {:changes 3 :edits 3 :files 2}})]
    (is (:ok forward) (pr-str forward))
    (is (= (:future-sources forward) (:future-sources backward)))
    (is (= (:canonical-effects forward) (:canonical-effects backward)))
    (is (= 3 (get-in forward [:canonical-effects :effects])))
    (is (string? (get-in forward [:canonical-effects :sha256])))
    (is (not= (get-in forward [:canonical-effects :sha256])
              (get-in (compile-changes
                        (assoc sources a "(ns app.a)\n(def value [(older x) :body])\n")
                        {:changes [(replacement :call [a] nil "(older x)" "(new x)" 1)
                                   (second changes) (nth changes 2)]
                         :expect {:changes 3 :edits 3 :files 2}})
                      [:canonical-effects :sha256])))))
