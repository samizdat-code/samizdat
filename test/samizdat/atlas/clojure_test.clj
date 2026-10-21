;; samizdat - a self-hosting agentic harness
;; Copyright (C) 2026 Dmitri Sotnikov
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU General Public License for more details.
;;
;; You should have received a copy of the GNU General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.atlas.clojure-test
  "Reading a Clojure project into the code graph (RFC-016): definitions,
  the calls between them with their sites, namespaces grouped by prefix,
  and source that the host reader cannot take whole."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.atlas.clojure :as clj]
            [samizdat.atlas.graph :as g]))

(def core-src
  (str/join "\n"
            ["(ns app.core"
             "  \"The app.\""
             "  (:require [app.db :as db]"
             "            [app.util :refer [tidy]]))"
             ""
             "(declare render)"
             ""
             "(defn handle"
             "  \"Handles a request.\""
             "  [req]"
             "  (let [row (db/save (tidy req))]"
             "    (render row ::done #\"a(b\")))"
             ""
             "(defn- render [row]"
             "  (str \"(not a call\" row))"
             ""
             "(defn -main [& args]"
             "  (mapv handle args))"
             ""
             "(defmulti shape :kind)"
             "(defmethod shape :square [x] (render x))"
             ""
             "(comment (handle 1))"]))

(def db-src
  (str/join "\n"
            ["(ns app.db (:require [app.util :as u]))"
             "(def ^:private pool (atom nil))"
             "(defn conn [] @pool)"
             "(defn save [x] (u/tidy (conn)) x)"]))

(def util-src "(ns app.util)\n(defn tidy [x] x)\n(defmacro with-x [& body] `(do ~@body))")

(def files [{:path "src/app/core.clj" :text core-src}
            {:path "src/app/db.clj" :text db-src}
            {:path "src/app/util.clj" :text util-src}])

(def graph (clj/extract files))

(deftest definitions-become-nodes-under-their-namespace
  (let [h (g/node graph "app.core/handle")]
    (is (= {:label "handle" :kind :fn :parent "app.core" :file "src/app/core.clj"
            :line 8 :end-line 12 :doc "Handles a request."}
           (select-keys h [:label :kind :parent :file :line :end-line :doc]))))
  (is (:private? (g/node graph "app.core/render")))
  (is (= :var (:kind (g/node graph "app.db/pool"))))
  (is (= :macro (:kind (g/node graph "app.util/with-x"))))
  (is (= :multi (:kind (g/node graph "app.core/shape"))))
  (testing "declare is not a definition; the defn after it is"
    (is (= 14 (:line (g/node graph "app.core/render"))))))

(deftest namespaces-nest-under-their-prefix
  (is (= {:kind :ns :label "app.core" :parent "app.*" :file "src/app/core.clj" :doc "The app."}
         (select-keys (g/node graph "app.core") [:kind :label :parent :file :doc])))
  (is (= :group (:kind (g/node graph "app.*"))))
  (is (= ["app.*"] (g/roots graph))))

(deftest calls-resolve-through-aliases-refers-and-the-namespace-itself
  (is (= ["app.core/render" "app.db/save" "app.util/tidy"] (g/callees graph "app.core/handle")))
  (is (= [{:file "src/app/core.clj" :line 11 :col 13}]
         (:sites (g/edge graph "app.core/handle" "app.db/save"))))
  (is (= ["app.db/conn" "app.util/tidy"] (g/callees graph "app.db/save")))
  (testing "a function passed as a value is a reference from the enclosing call"
    (is (= ["app.core/handle"] (g/callees graph "app.core/-main")))
    (is (:ref? (first (:sites (g/edge graph "app.core/-main" "app.core/handle"))))))
  (testing "a method is its own node, which its multimethod dispatches to"
    (let [m "app.core/shape :square"]
      (is (= {:label "shape :square" :kind :method :parent "app.core" :line 21}
             (select-keys (g/node graph m) [:label :kind :parent :line])))
      (is (= [m] (g/callees graph "app.core/shape")))
      (is (:dispatch? (first (:sites (g/edge graph "app.core/shape" m)))))
      (is (= ["app.core/render"] (g/callees graph m)))))
  (testing "a comment form calls nothing, and a string holding a paren is not code"
    (is (= ["app.core/-main"] (g/callers graph "app.core/handle")))
    (is (= [] (g/callees graph "app.core/render"))))
  (testing "a var deref is a reference"
    (is (= ["app.db/pool"] (g/callees graph "app.db/conn")))))

(deftest a-file-the-reader-chokes-on-keeps-what-came-before
  (let [x (clj/extract [{:path "src/bad.clj" :text "(ns bad)\n(defn ok [] 1)\n(defn broken [] (oops"}])]
    (is (g/node x "bad/ok"))
    (is (= ["src/bad.clj"] (map :file (:errors (meta x)))))))

(deftest form-end-skips-strings-comments-and-characters
  (is (= 3 (clj/form-end "(a \")\"\n ; )\n \\) b)\n(c)" 1 1)))
  (is (= 1 (clj/form-end "(x) (y\n)" 1 1)))
  (is (= 2 (clj/form-end "(x) (y\n)" 1 5))))

(deftest sanitize-keeps-columns-and-neuters-what-the-reader-refuses
  (let [s "(f ::a/b #\"x(\" \"::in string\" ; ::c\n #'v)"]
    (is (= (count s) (count (clj/sanitize s))))
    (is (= "(f  :a/b  \"x(\" \"::in string\" ; ::c\n #'v)" (clj/sanitize s))))
  (testing "a regex's escapes, an escaped quote among them, are blanked so it reads as a plain string"
    (let [re "#\"\\s\\\"x\""]
      (is (= " \"    x\"" (clj/sanitize re)))
      (is (= "x" (str/trim (read-string (clj/sanitize re)))))))
  (testing "a string's escapes are left alone"
    (is (= "(g \"a\\\"b\")" (clj/sanitize "(g \"a\\\"b\")")))))

(deftest a-mycelium-cell-is-a-node-named-for-its-id
  (let [x (clj/extract [{:path "resources/cells/c.clj"
                         :text "(ns cells.c (:require [mycelium.cell :as cell] [app.db :as db]))\n(cell/defcell :c/save {:doc \"Saves.\"} (fn [r d] (db/save d)))"}
                        {:path "src/app/db.clj" :text db-src}])]
    (is (= {:kind :cell :label ":c/save" :parent "cells.c" :line 2}
           (select-keys (g/node x ":c/save") [:kind :label :parent :line])))
    (is (= ["app.db/save"] (g/callees x ":c/save")))))

(deftest a-definition-carries-a-hash-of-its-source
  (let [h #(:hash (g/node (clj/extract [{:path "x.clj" :text %}]) "x/f"))]
    (is (= (h "(ns x)\n(defn f [] 1)") (h "(ns x)\n\n\n(defn f [] 1)")) "moving it is not changing it")
    (is (not= (h "(ns x)\n(defn f [] 1)") (h "(ns x)\n(defn f [] 2)")))))
