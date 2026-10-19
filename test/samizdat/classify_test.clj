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

(ns samizdat.classify-test
  "Auto mode's classifier: what it reads off a reply, and what it sends.
  Anything but a clear allow is an ask — the safe direction."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.llm.client :as llm]
            [samizdat.security.classify :as classify]))

(deftest a-reply-is-an-allow-only-when-it-says-so-first
  (is (= {:verdict :allow :why "reads a sibling checkout"}
         (classify/verdict-of "allow\nreads a sibling checkout")))
  (is (= {:verdict :ask :why "reaches the harness's source"}
         (classify/verdict-of "ASK: reaches the harness's source")))
  (is (= :allow (:verdict (classify/verdict-of "<think>hmm</think>\nAllow — only lists a directory"))))
  (testing "anything else asks"
    (doseq [r ["" nil "maybe" "I would allow this" "allowed?" "deny"]]
      (is (= :ask (:verdict (classify/verdict-of r))) (pr-str r)))))

(deftest the-call-carries-the-command-and-why-it-was-held
  (let [sent (atom nil)]
    (with-redefs [llm/chat (fn [_ _ msgs & _] (reset! sent msgs) {:content "ask\nnot sure"})]
      (let [v (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"}
                                 {:effect :ask :input "ls ~/src/samizdat-code"
                                  :reason "outside-project"
                                  :outside ["/Users/me/src/samizdat-code"]})
            user (:content (last @sent))]
        (is (= :ask (:verdict v)))
        (is (= "system" (:role (first @sent))))
        (is (str/includes? user "ls ~/src/samizdat-code"))
        (is (str/includes? user "/Users/me/src/samizdat-code"))
        (is (str/includes? user "/work/dodeca"))))))

(deftest a-failed-call-asks
  (with-redefs [llm/chat (fn [& _] (throw (ex-info "down" {})))]
    (is (= :ask (:verdict (classify/classify {:llm-adapter :fake :llm-config {}}
                                             {:effect :ask :input "ls /"}))))))
