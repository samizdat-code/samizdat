;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.tui-onboarding-test
  "What's-new pages, shown once per version (karamazov-05z0.19)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [samizdat.layers :as layers]
            [samizdat.tui.layout :as layout]
            [samizdat.tui.onboarding :as ob]))

(def ^:private pages
  [{:id "welcome" :version 1 :lines ["Welcome."]}
   {:id "complain" :version 2 :lines ["New: /complain."]}])

(deftest a-page-is-due-until-seen-at-its-version
  (is (= ["welcome" "complain"] (mapv :id (ob/due pages {}))))
  (is (= ["complain"] (mapv :id (ob/due pages {"welcome" 1 "complain" 1})))
      "a bumped version is news again")
  (is (empty? (ob/due pages (ob/mark {} pages)))))

(deftest shown-once-per-person
  (let [home (doto (java.io.File/createTempFile "ob-home" "") (.delete) (.mkdirs))]
    (try
      (with-redefs [layers/global-dir (constantly (str home))]
        (is (= 2 (count (ob/due! pages))))
        (is (empty? (ob/due! pages)) "the second start shows nothing")
        (is (= {"welcome" 1 "complain" 2}
               (edn/read-string (slurp (io/file home "onboarding.edn"))))))
      (finally (doseq [f (reverse (file-seq home))] (.delete f))))))

(deftest the-shipped-pages-are-well-formed
  (let [ps (:onboarding (layout/template))]
    (is (seq ps) "tui.edn ships at least one page")
    (doseq [{:keys [id version lines]} ps]
      (is (string? id))
      (is (pos-int? version))
      (is (every? string? lines)))))
