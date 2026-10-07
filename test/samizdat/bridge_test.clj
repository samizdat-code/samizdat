;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.bridge-test
  "The eval bridge's pure parts (karamazov-xn0h.7): the queue read back from
  the image, and the answer a tool result becomes."
  (:require [clojure.test :refer [deftest testing is]]
            [samizdat.bridge :as bridge]))

(deftest queued-requests-read-back-from-what-the-image-printed
  ;; take-requests! returns pr-str of the queue, and nREPL prints that value
  ;; again — two layers of printing.
  (let [q [{:id "a" :op "grep" :args {:pattern "x"}}]]
    (is (= q (bridge/requests (pr-str (pr-str q)))))
    (is (= q (bridge/requests (pr-str q)))))
  (is (= [] (bridge/requests "nil")))
  (is (= [] (bridge/requests "not edn {")))
  (is (= [] (bridge/requests (pr-str (pr-str [{:no "id"}]))))
      "only well-formed requests"))

(deftest a-result-becomes-a-value-or-an-error
  (is (= {:value "found"} (bridge/answer {:category :success :result "found"})))
  (is (= {:value "nothing"} (bridge/answer {:category :neutral :result "nothing"})))
  (is (= {:error "refused"} (bridge/answer {:category :mechanics :result "refused"})))
  (is (= {:error "no such file"} (bridge/answer {:category :failure :result "no such file"}))))

(deftest the-install-source-reads-and-names-each-tool
  (let [src (bridge/install-source #{"grep" "read_file"} [] 'tool
                                   {:timeout-ms 1000 :timeout-message "slow"})
        forms (read-string (str "[" src "]"))]
    (is (some #(= '(ns tool) %) forms))
    (is (= #{'grep 'read_file}
           (set (keep #(when (and (seq? %) (= 'defn (first %))
                                  (seq? (last %))
                                  (= 'samizdat.bridge/call (first (last %))))
                         (second %))
                      forms))))))
