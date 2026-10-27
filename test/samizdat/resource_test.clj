;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.resource-test
  (:require [clojure.test :refer [deftest is testing]]
            [samizdat.resource :as resource]))

(defn- text-resolver []
  (reify resource/Resolver
    (resolve-resource [_ uri]
      {:uri uri :resolver :text :identity (subs uri 5)})
    (observe-resource [_ {:keys [uri resolver identity]} _ctx]
      {:uri uri :resolver resolver :identity identity
       :revision {:version 7} :content "abcdef" :metadata {:length 6}})
    (read-resource [_ {:keys [content]} _ctx {:keys [offset limit]}]
      (let [end (min (count content) (+ offset limit))]
        {:content (subs content offset end)
         :next-offset (when (< end (count content)) end)}))
    (resource-operations [_ _resource _ctx]
      [{:operation :read} {:operation :replace}])))

(deftest uri-resolution-selects-the-scheme-without-authorization
  (let [registry (resource/registry {:text (text-resolver)})]
    (is (= {:uri "text:alpha" :resolver :text :identity "alpha"}
           (resource/resolve-uri registry "text:alpha")))))

(deftest malformed-and-unknown-uris-have-structured-errors
  (let [registry (resource/registry {:text (text-resolver)})]
    (testing "missing scheme"
      (is (= :malformed-uri
             (try (resource/resolve-uri registry "alpha") nil
                  (catch clojure.lang.ExceptionInfo e
                    (:resource/error (ex-data e)))))))
    (testing "unregistered scheme"
      (is (= :unknown-scheme
             (try (resource/resolve-uri registry "file:alpha") nil
                  (catch clojure.lang.ExceptionInfo e
                    (:resource/error (ex-data e)))))))))

(deftest observation-preserves-the-resolvers-opaque-revision
  (let [registry (resource/registry {:text (text-resolver)})]
    (is (= {:version 7}
           (:revision (resource/observe registry "text:alpha" {}))))))

(deftest bounded-reads-return-the-next-window
  (let [registry (resource/registry {:text (text-resolver)})
        observation (resource/observe registry "text:alpha" {})]
    (is (= {:content "bcd" :next-offset 4}
           (resource/read-window registry observation {} {:offset 1 :limit 3})))
    (is (= {:content "ef" :next-offset nil}
           (resource/read-window registry observation {} {:offset 4 :limit 3})))))

(deftest operations-come-from-the-selected-resolver
  (let [registry (resource/registry {:text (text-resolver)})]
    (is (= [{:operation :read} {:operation :replace}]
           (resource/operations registry "text:alpha" {})))))
