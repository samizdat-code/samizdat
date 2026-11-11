;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.outline-test
  "A file's definitions with their line ranges (karamazov-d5wo.10): reading
  was ~70% of tool-result characters across the campaign dbs, mostly whole
  files, and an outline lets a model ask for the range it needs."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jolt.fs :as fs]
            [samizdat.agent.files :as files]
            [samizdat.agent.gates :as gates]
            [samizdat.agent.outline :as outline]
            [samizdat.agent.tools.base :as tools-base]))

(defn- patterns [] (:patterns (gates/threshold :outline)))

(deftest an-outline-is-every-definition-and-the-lines-it-spans
  (is (= [{:line 1 :end 2 :name "a"}
          {:line 3 :end 6 :name "f"}
          {:line 7 :end 7 :name "y"}]
         (outline/outline "(ns a)\n\n(defn f\n  [x]\n  x)\n\n(def y 1)" (patterns)))
      "a definition runs to the line before the next one, the last to the end")
  (testing "other languages, by their own patterns"
    (is (= ["A" "m" "g"] (mapv :name (outline/outline "class A:\n  def m(self):\n    pass\ndef g():\n  pass" (patterns)))))
    (is (= ["run" "Thing"] (mapv :name (outline/outline "export async function run() {}\nclass Thing {}" (patterns)))))
    (is (= ["main" "Point"] (mapv :name (outline/outline "pub fn main() {}\nstruct Point {}" (patterns))))))
  (testing "a file with no definitions has an empty outline"
    (is (= [] (outline/outline "just\nsome\ntext" (patterns))))))

(deftest a-clojure-outline-is-each-definition-s-own-lines
  ;; From jolt-lsp's parse: the form's real span, a name behind metadata,
  ;; and nothing for a discarded form or a comment block.
  (let [heads #{"ns" "defn" "def" "defmethod"}
        src "(ns a)\n\n(defn ^:private f\n  [x]\n  x)\n\n;; note\n#_(defn gone [])\n(comment (defn z []))\n(def y 1)\n(defmethod m :k [x] x)\n"]
    (is (= [{:line 1 :end 1 :name "a"}
            {:line 3 :end 5 :name "f"}
            {:line 10 :end 10 :name "y"}
            {:line 11 :end 11 :name "m"}]
           (outline/clojure-outline src heads)))
    (is (= [{:line 1 :end 1 :name "ok"}] (outline/clojure-outline "(def ok 1)\n(defn broken [" heads))
        "a broken form is masked; the forms around it still outline")))

(deftest read-file-returns-the-outline-when-asked
  (let [dir (str (fs/create-temp-dir))]
    (fs/create-dirs (fs/path dir "src"))
    (fs/spit (fs/path dir "src/a.clj") "(ns a)\n\n(defn f\n  [x]\n  x)\n\n(def y 1)\n")
    (let [r (files/read-file {:root dir :branch {:id "B1"} :args {:path "src/a.clj" :outline true}})]
      (is (= :neutral (:category r)))
      (is (str/includes? (:result r) "3-5") "a Clojure file: the form's own lines")
      (is (str/includes? (:result r) "f"))
      (is (not (str/includes? (:result r) "[x]")) "names and ranges, not the body"))
    (testing "an outline read is not the whole-file read the digest steer refuses"
      (is (not (files/large-untargeted-read? {:root dir :args {:path "src/a.clj" :outline true}} 1))))))
