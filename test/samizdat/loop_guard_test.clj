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

(ns samizdat.loop-guard-test
  "A reply that starts to loop is cut while it streams (karamazov-loop guard):
  the detector, the per-attempt guard over reasoning and text, the stream
  closing on a trip, and the client reading the cut as a repeating reply."
  (:refer-clojure :exclude [chunk])
  (:require [jolt.time]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ring-chez.adapter :as adapter]
            [ring-chez.sse :as rsse]
            [samizdat.lexicon :as lexicon]
            [samizdat.llm.client :as client]
            [samizdat.llm.fence :as fence]
            [samizdat.llm.loop-guard :as lg]
            [samizdat.llm.registry :as registry]
            [samizdat.llm.stream :as stream]))

(def policy (lexicon/policy :loop-guard))

(defn- feed
  "Push `pieces` through a fresh detector; the first hit, or nil."
  [semantic? pieces]
  (reduce (fn [d piece]
            (let [d (lg/push d piece policy)]
              (if (:hit d) (reduced d) d)))
          (lg/detector semantic?)
          pieces))

(defn- tokens
  "`text` in token-sized pieces, as a stream would deliver it."
  [text]
  (map #(apply str %) (partition-all 7 text)))

;; --- the exact check ---------------------------------------------------------

(deftest the-shipped-policy-is-there
  (is (map? policy) "gates.edn carries :loop-guard")
  (is (= :semantic (lg/level :deepseek nil policy)))
  (is (= :exact (lg/level :local nil policy)))
  (is (= :off (lg/level :deepseek :off policy)) "a provider's :loop-guard outranks the table")
  (is (= :off (lg/level :deepseek nil nil)) "no policy, no guard"))

(deftest an-exact-cycle-at-the-tail-is-a-loop
  (let [loop-text (str "Let me look at the file first.\n"
                       (apply str (repeat 12 "Verifying that the task is complete. ")))]
    (is (lg/exact-suffix-cycle loop-text policy))
    (is (:hit (feed false (tokens loop-text))) "caught as it streams, at the stride")))

(deftest ordinary-text-is-not-a-loop
  (testing "prose"
    (is (nil? (:hit (feed true (tokens (str/join "\n\n" (map #(str "Step " % ": read file" % ".clj and check how it handles case " % ", then note the result for the summary.") (range 30)))))))))
  (testing "digits and padding repeat without being a loop"
    (is (nil? (lg/exact-suffix-cycle (apply str (repeat 400 "0 ")) policy))))
  (testing "a whole line restated a few times is under the long floor"
    (is (nil? (lg/exact-suffix-cycle (apply str (repeat 4 "Checking the build status.\n")) policy)))))

;; --- the paragraph check -----------------------------------------------------

(def ^:private drift-paragraphs
  ;; The same paragraph with cosmetic drift, the way DeepSeek loops in prose.
  (for [i (range 12)]
    (str "I need to confirm the configuration is safe before continuing, so I will "
         "verify the settings once more and make sure nothing was missed in the "
         "previous review of the configuration file "
         (nth ["carefully" "again" "now" "today" "once" "fully" "quickly" "slowly"
               "properly" "here" "first" "twice"] i) ".")))

(def ^:private distinct-paragraphs
  (for [i (range 12)]
    (str "Paragraph " i " looks at module" i " where the parser for format " i
         " reads a header, validates field " (* i 3) " and writes record " (* i 7)
         " to the store under key " (str "k" i i) ".")))

(deftest near-identical-paragraphs-are-a-loop-at-the-semantic-level
  (let [text (str/join "\n\n" drift-paragraphs)]
    (is (:hit (feed true (tokens text))))
    (is (nil? (:hit (feed false (tokens text))))
        "the exact level does not judge paragraphs")))

(deftest distinct-paragraphs-are-not
  (is (nil? (:hit (feed true (tokens (str/join "\n\n" distinct-paragraphs)))))))

(deftest code-is-not-judged-as-paragraphs
  ;; Code repeats one skeleton with different literals.
  (let [code (str/join "\n\n" (for [i (range 20)]
                                (str "  (defn handler-" i " [req]\n"
                                     "    (let [x (get req :field)]\n"
                                     "      (respond x))))")))]
    (is (nil? (:hit (feed true (tokens code)))))))

;; --- the attempt guard -------------------------------------------------------

(def ^:private loop-text
  (apply str (repeat 12 "Verifying that the task is complete. ")))

(deftest the-guard-watches-reasoning-and-text
  (testing "a reasoning loop"
    (let [g (lg/guard :exact policy)]
      (is (some #(g {:reasoning %}) (tokens loop-text)))))
  (testing "a text loop"
    (let [g (lg/guard :exact policy)]
      (is (some #(g {:text %}) (tokens loop-text)))))
  (testing "nil at :off"
    (is (nil? (lg/guard :off policy)))))

(deftest a-tool-call-s-arguments-are-not-watched
  ;; A file being written can repeat itself legitimately; the finished-reply
  ;; check still sees a loop inside a fence.
  (let [g (lg/guard :exact policy)]
    (is (nil? (g {:text "Writing it.\n```tool-call\n{\"name\":\"write_file\",\"args\":{\"content\":\""})))
    (is (not-any? #(g {:text %}) (tokens loop-text)))))

(deftest reasoning-stops-being-watched-once-the-answer-starts
  (let [g (lg/guard :exact policy)]
    (is (nil? (g {:text "Here is the answer."})))
    (is (not-any? #(g {:reasoning %}) (tokens loop-text)))))

;; --- the stream closes on a trip ----------------------------------------------

(defn- free-port [] (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- with-server [handler f]
  (let [port (free-port)
        server (adapter/run-server handler {:port port})]
    (try (f port) (finally (adapter/stop-server server)))))

(defn- chunk [delta]
  {:id "c1" :model "m" :choices [{:index 0 :delta delta :finish_reason nil}]})

(deftest a-looping-stream-is-cut-and-keeps-what-it-said
  (with-server (fn [_]
                 (let [ch (async/chan 64)]
                   (future
                     (try
                       (dotimes [_ 2000]
                         (rsse/send! ch {:data (json/write-str (chunk {:content "Verifying the task is complete. "}))})
                         (Thread/sleep 2))
                       (catch Throwable _ nil))
                     (async/close! ch))
                   {:status 200 :headers {"Content-Type" "text/event-stream"} :body ch}))
    (fn [port]
      (let [t0 (System/currentTimeMillis)
            g (lg/guard :exact policy)
            r (try (stream/post (str "http://127.0.0.1:" port "/v1/chat/completions")
                                {:headers {} :body "{}" :socket-timeout 30000 :conn-timeout 2000
                                 :guard g}
                                (fn [_]))
                   (catch clojure.lang.ExceptionInfo e e))
            took (- (System/currentTimeMillis) t0)]
        (is (instance? clojure.lang.ExceptionInfo r) "a loop is not an answer")
        (is (string? (:loop (ex-data r))) "the cut says why")
        (is (str/includes? (get-in (json/read-str (:partial-body (ex-data r)) :key-fn keyword)
                                   [:choices 0 :message :content])
                           "Verifying"))
        (is (< took 3000) (str "cut early, not at the end of 2000 chunks: " took "ms"))))))

;; --- the client reads the cut as a repeating reply ----------------------------

(deftest the-client-returns-a-cut-loop-as-a-repeating-reply
  (let [a (registry/adapter-for :deepseek-chat)
        body (json/write-str
              {:id "c1" :model "m" :object "chat.completion"
               :choices [{:index 0 :message {:role "assistant" :content loop-text}
                          :finish_reason nil}]})
        guard-seen (atom nil)]
    (with-redefs [stream/post (fn [_ opts _]
                                (reset! guard-seen (:guard opts))
                                (throw (ex-info "provider stream interrupted: looped"
                                                {:partial-body body :loop "repeated a passage"})))]
      (let [r (#'client/post-once* a {:base-url "http://x" :features #{:stream}
                                      :model "m" :timeout-ms 1000}
                                   {:messages [] :on-delta (fn [_])} "http://x")]
        (is (fn? @guard-seen) "deepseek is guarded")
        (is (= :ok (:outcome r)) "not retried: a retry re-runs the loop")
        (is (= "repetition" (get-in r [:response :finish-reason])))
        (is (= "repeated a passage" (get-in r [:response :loop])))
        (is (= loop-text (get-in r [:response :content])))))))

(deftest a-repetition-finish-is-a-periodic-truncation
  (let [s (fence/signals {:finish-reason "repetition" :content "Verifying."} nil)]
    (is (:truncated s))
    (is (:periodic s) "steered as a loop, never retried at a doubled budget")))
