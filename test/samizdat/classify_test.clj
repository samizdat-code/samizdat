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
                                 {:effect :ask :input "ls /work/samizdat-code"
                                  :reason "outside-project"
                                  :outside ["/work/samizdat-code"]})
            user (:content (last @sent))]
        (is (= :ask (:verdict v)))
        (is (= "system" (:role (first @sent))))
        (is (str/includes? user "ls /work/samizdat-code"))
        (is (str/includes? user "/work/samizdat-code\n"))
        (is (str/includes? user "/work/dodeca"))))))

(deftest the-call-carries-the-agents-reason-and-the-project-parent
  (let [sent (atom nil)]
    (with-redefs [llm/chat (fn [_ _ msgs & _] (reset! sent msgs) {:content "allow\nreads a sibling"})]
      (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"
                          :branch {:messages [{:role "user" :content "go"}
                                              {:role "assistant"
                                               :content "<think>hmm</think>Check how a5 lays out its modules."}]}}
                         {:effect :ask :input "ls /work/a5" :reason "outside-project"
                          :outside ["/work/a5"]})
      (let [user (:content (last @sent))]
        (is (str/includes? user "Check how a5 lays out its modules."))
        (is (not (str/includes? user "hmm")) "the reasoning block is not the rationale")
        (is (str/includes? user "/work\n") "the parent the reach is measured from")))))

(deftest a-read-beyond-the-reach-asks-without-a-call
  (let [called (atom false)]
    (with-redefs [llm/chat (fn [& _] (reset! called true) {:content "allow"})]
      (testing "past the project's parent"
        (is (= :ask (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"}
                                                 {:effect :ask :input "ls ~/bin"
                                                  :outside ["/home/me/bin"]}))))
        (is (not @called)))
      (testing "the parent and a sibling are within it"
        (is (= :allow (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"}
                                                   {:effect :ask :input "ls /work /work/a5/src"
                                                    :outside ["/work" "/work/a5/src"]}))))
        (is @called)))))

(deftest scratch-is-within-the-reach
  (let [sent (atom nil)]
    (with-redefs [llm/chat (fn [_ _ msgs & _] (reset! sent msgs) {:content "allow\nscratch"})]
      (doseq [p ["/tmp/fx/run.out" "/private/tmp/fx"]]
        (reset! sent nil)
        (is (= :allow (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"}
                                                   {:effect :ask :input (str "cat " p) :outside [p]}))))
        (is (some? @sent) p))
      (is (str/includes? (:content (last @sent)) "/tmp") "the model is told what scratch is"))))

(deftest a-parent-at-or-above-home-is-no-reach
  ;; A project at /tmp/x or ~/x would otherwise reach the whole disk or the
  ;; whole home directory.
  (with-redefs [llm/chat (fn [& _] {:content "allow"})]
    (doseq [[root p] [["/tmp" "/opt/homebrew/bin"]
                      ["/Users/me/proj" "/Users/me/.zshrc"]
                      ["/Users/me/src/x" "/Users/me/src/y"]]]
      (with-redefs [classify/home (constantly "/Users/me/src")]
        (is (= :ask (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root root}
                                                 {:effect :ask :input (str "ls " p) :outside [p]})))
            [root p])))))

(deftest what-the-shell-may-already-read-is-within-the-reach
  (with-redefs [llm/chat (fn [& _] {:content "allow"})]
    (is (= :allow (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"
                                                :read-roots ["/work/dodeca" "/home/me/.m2"]}
                                               {:effect :ask :input "unzip -p ~/.m2/x.jar"
                                                :outside ["/home/me/.m2/x.jar"]}))))))

(deftest a-path-the-command-names-past-the-reach-asks-without-a-call
  ;; A compound or unlisted command reaches the classifier with no :outside:
  ;; what it names is measured here, not left to the model.
  (let [called (atom false)]
    (with-redefs [llm/chat (fn [& _] (reset! called true) {:content "allow"})]
      (is (= :ask (:verdict (classify/classify {:llm-adapter :fake :llm-config {} :root "/work/dodeca"}
                                               {:effect :ask :input "cd /usr/bin && jolt -M:test"}))))
      (is (not @called)))))

(deftest the-reach-is-the-ancestor-n-levels-up
  (is (= "/work" (classify/reach-root "/work/dodeca" 1)))
  (is (= "/" (classify/reach-root "/work/dodeca" 2)))
  (is (= "/work/dodeca" (classify/reach-root "/work/dodeca" 0)))
  (is (classify/within? "/work" "/work"))
  (is (classify/within? "/work" "/work/a5/x"))
  (is (not (classify/within? "/work" "/workshop/x"))))

(deftest a-failed-call-asks
  (with-redefs [llm/chat (fn [& _] (throw (ex-info "down" {})))]
    (is (= :ask (:verdict (classify/classify {:llm-adapter :fake :llm-config {}}
                                             {:effect :ask :input "ls /"}))))))
