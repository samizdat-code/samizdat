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

(ns samizdat.atlas-api-test
  "The atlas over HTTP (RFC-016): what the GUI reads."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [samizdat.atlas :as atlas]
            [samizdat.atlas-test :as at]
            [samizdat.server :as server]
            [samizdat.system :as system]))

(defn- call
  ([method uri] (call method uri nil))
  ([method uri body]
   (let [[path qs] (clojure.string/split uri #"\?" 2)
         r (server/handler (cond-> {:request-method method :uri path}
                             qs (assoc :query-string qs)
                             body (assoc :body (json/write-str body)
                                         :headers {"content-type" "application/json"})))]
     (assoc r :json (some-> (:body r) (json/read-str :key-fn keyword))))))

(use-fixtures :each
  (fn [t]
    (atlas/forget!)
    (reset! at/files at/initial)
    (with-redefs [atlas/policy (constantly at/policy)
                  atlas/default-io at/io
                  system/conn (constantly nil)
                  system/config (constantly {:run {:root "/p"}})]
      (t))))

(deftest the-atlas-routes
  (testing "summary"
    (is (= 200 (:status (call :get "/v1/atlas/summary"))))
    (is (pos? (get-in (call :get "/v1/atlas/summary") [:json :nodes]))))
  (testing "a view is posted, since it names the open groups"
    (is (= ["app.core" "app.db"]
           (map :id (get-in (call :post "/v1/atlas/view" {:lens "namespaces" :expanded ["app.*"]})
                            [:json :nodes])))))
  (testing "a card"
    (is (= "fn" (get-in (call :get "/v1/atlas/card?id=app.core%2Fhandle") [:json :node :kind]))))
  (testing "an unknown id is a 404"
    (is (= 404 (:status (call :get "/v1/atlas/card?id=nope")))))
  (testing "flows, one flow, units, search"
    (is (= ["app.core/-main"] (map :entry (:json (call :get "/v1/atlas/flows")))))
    (is (= [["app.core/-main" "app.core/handle" "app.db/save!"]]
           (get-in (call :get "/v1/atlas/flow?entry=app.core%2F-main") [:json :paths])))
    (is (seq (:json (call :get "/v1/atlas/units"))))
    (is (= ["app.db/save!"] (map :id (:json (call :get "/v1/atlas/search?q=save")))))))

(deftest no-project-no-atlas
  (with-redefs [system/config (constantly {})]
    (is (= 409 (:status (call :get "/v1/atlas/summary"))))))
