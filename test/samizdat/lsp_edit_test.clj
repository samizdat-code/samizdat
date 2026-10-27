;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.lsp-edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.lsp.edit :as edit]))

(defn- range* [start-line start-character end-line end-character]
  {:start {:line start-line :character start-character}
   :end {:line end-line :character end-character}})

(deftest utf16-positions-are-translated-before-editing
  (is (= "a😀bx"
         (edit/apply-text-edits
          "a😀b x"
          [{:range (range* 0 4 0 5) :newText ""}])))
  (is (= "a😀total"
         (edit/apply-text-edits
          "a😀sum"
          [{:range (range* 0 3 0 6) :newText "total"}]))))

(deftest overlapping-text-edits-are-rejected
  (is (= :overlapping-edits
         (try
           (edit/apply-text-edits
            "abcdef"
            [{:range (range* 0 1 0 4) :newText "x"}
             {:range (range* 0 3 0 5) :newText "y"}])
           nil
           (catch clojure.lang.ExceptionInfo e
             (:lsp-edit/error (ex-data e)))))))

(deftest changes-and-document-edits-become-workspace-members
  (let [reads (atom {"file:///p/a.clj" "(ns a)\n(sum 1)\n"
                     "file:///p/b.clj" "(ns b)\n(sum 2)\n"})
        read-observation (fn [uri]
                           {:uri uri :resolver :workspace :identity (subs uri 7)
                            :revision (str "rev-" uri) :content (get @reads uri)
                            :metadata {:path (subs uri 7)}})
        members (edit/workspace-edit->members
                 {:changes {"file:///p/a.clj"
                            [{:range (range* 0 1 0 3) :newText "ns2"}
                            {:range (range* 1 1 1 4) :newText "total"}]}
                  :documentChanges [{:textDocument {:uri "file:///p/b.clj" :version 4}
                                     :edits [{:range (range* 1 1 1 4) :newText "total"}]}]}
                 read-observation)]
    (is (= 2 (count members)))
    (is (= "(ns2 a)\n(total 1)\n" (:replacement (first members))))
    (is (= "(ns b)\n(total 2)\n" (:replacement (second members))))
    (is (every? #(= :write (:operation %)) members))
    (is (every? :base-revision members))))

(deftest file-operations-are-data-only
  (let [writes (atom 0)
        read-observation (fn [uri]
                           {:uri uri :resolver :workspace :identity (subs uri 7)
                            :revision "r1" :content "old"
                            :metadata {:path (subs uri 7)}})
        members (edit/workspace-edit->members
                 {:documentChanges [{:kind "create" :uri "file:///p/new.clj"}
                                    {:kind "delete" :uri "file:///p/old.clj"}
                                    {:kind "rename" :oldUri "file:///p/old.clj"
                                     :newUri "file:///p/renamed.clj"}]}
                 (fn [uri]
                   (swap! writes inc)
                   (read-observation uri)))]
    (is (= [:create :delete :rename] (mapv :operation members)))
    (is (= "file:///p/renamed.clj" (:replacement-uri (last members))))
    (is (= 3 @writes) "create, delete, and rename sources are observed")
    (is (zero? (count (filter #(= :write (:operation %)) members))))))

(deftest unsupported-document-uris-are-refused
  (is (= :unsupported-uri
         (try
           (edit/workspace-edit->members
            {:changes {"https://example.com/a.clj"
                       [{:range (range* 0 0 0 1) :newText "x"}]}}
            (fn [_] nil))
           nil
           (catch clojure.lang.ExceptionInfo e
             (:lsp-edit/error (ex-data e)))))))

(deftest malformed-ranges-are-refused-before-editing
  (doseq [[label edits kind]
          [["line outside document"
            [{:range (range* 4 0 4 0) :newText "x"}] :invalid-position]
           ["reversed range"
            [{:range (range* 0 3 0 1) :newText "x"}] :invalid-range]
           ["duplicate insertions at one point"
            [{:range (range* 0 1 0 1) :newText "x"}
             {:range (range* 0 1 0 1) :newText "y"}] :ambiguous-edits]]]
    (is (= kind
           (try (edit/apply-text-edits "abc" edits) nil
                (catch clojure.lang.ExceptionInfo e
                  (:lsp-edit/error (ex-data e)))))
        label)))
