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
;;
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.messages-test
  "Mailbox store: routing between branches. A directed message reaches its
  addressee and not the sender; a broadcast reaches everyone but the sender;
  mark-read! is what consumes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [samizdat.agent.hydration :as hydration]
            [samizdat.llm.message :as llm-message]
            [samizdat.agent.tools :as tools]
            [samizdat.store.db :as db]
            [samizdat.store.messages :as messages]))

(def conn (atom nil))

(def ^:private run "run-xyz")

(use-fixtures :each (fn [f] (reset! conn (db/open! ":memory:")) (f)))

(deftest send-returns-a-short-id
  (let [id (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "hello"})]
    (is (string? id))
    (is (str/starts-with? id "msg-"))
    (is (= 10 (count id)))))

(deftest directed-message-reaches-addressee-only
  (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "im taking the parser"})
  (is (= ["im taking the parser"] (mapv :body (messages/inbox @conn run "b2"))))
  (is (empty? (messages/inbox @conn run "b1"))))

(deftest broadcast-reaches-others-not-sender
  (messages/send! @conn {:run-id run :from "b1" :to nil :body "schema changed"})
  (is (= ["schema changed"] (mapv :body (messages/inbox @conn run "b2"))))
  (is (empty? (messages/inbox @conn run "b1"))))

(deftest inbox-is-scoped-to-run-and-excludes-read
  (messages/send! @conn {:run-id "other-run" :from "b9" :to "b2" :body "wrong run"})
  (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "right run"})
  (is (= ["right run"] (mapv :body (messages/inbox @conn run "b2"))))
  (let [id (messages/send! @conn {:run-id run :from "b3" :to "b2" :body "consumed"})]
    (messages/mark-read! @conn [id])
    (is (= ["right run"] (mapv :body (messages/inbox @conn run "b2"))))))

(deftest mark-read-consumes-the-inbox
  (let [id (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "read me"})]
    (is (= 1 (messages/mark-read! @conn [id])))
    (is (empty? (messages/inbox @conn run "b2")))
    (is (= 0 (messages/mark-read! @conn [id])))))

(deftest thread-shows-recent-regardless-of-read
  (let [a (messages/send! @conn {:run-id run :from "b1" :to "b2" :body "one"})
        _ (messages/send! @conn {:run-id run :from "b2" :to "b1" :body "two"})]
    (messages/mark-read! @conn [a])
    (is (= ["two" "one"] (mapv :body (messages/thread @conn run))))
    (is (= ["two"] (mapv :body (messages/thread @conn run 1)))))
  (is (empty? (messages/thread @conn "no-such-run"))))

(deftest send-rethrows-non-collision-failures-instead-of-retrying
  ;; provenance R2-15: the id-retry loop caught every Exception, so a disk or
  ;; lock failure was retried five times and then reported as an id
  ;; allocation problem. Only a UNIQUE collision is retryable.
  (let [real-execute db/execute!
        inserts (atom 0)]
    (with-redefs [db/execute!
                  (fn [conn q & opts]
                    (when (str/includes? (str (first q)) "INSERT INTO messages")
                      (swap! inserts inc)
                      (throw (ex-info "disk I/O error" {:errno 5})))
                    (apply real-execute conn q opts))]
      (is (thrown-with-msg? Exception #"disk I/O error"
                            (messages/send! @conn {:run-id run :from "b1"
                                                   :body "will not land"}))))
    (is (= 1 @inserts) "a non-collision failure is not retried")))

(deftest the-message-tool-sends-the-branch-id-as-from
  ;; provenance R2-2: the tool passed the whole branch map as :from and the store
  ;; str'd it — from_branch held 50-73KB state dumps (confirmed against the
  ;; live DB), and the inbox's from_branch != sender comparison could never
  ;; match, so senders saw their own broadcasts.
  (let [b {:id "b1"}]
    (doseq [body ["from the tool" "second note"]]
      (tools/run-tool {:branch b :conn @conn :run-id run :turn 1
                       :tool-name "message"
                       :args {:action "send" :body body}}))
    (let [rows (db/fetch @conn ["SELECT from_branch FROM messages ORDER BY id"])]
      (is (seq rows))
      (is (every? #(= "b1" (:from_branch %)) rows)
          "from_branch holds the branch id, not a str'd branch map"))
    ;; and the sender-exclusion the store builds on actually works
    (is (empty? (messages/inbox @conn run "b1")))))

(def ^:private hydration-opts
  {:keep-pairs 2 :threshold-chars 1 :batch 2 :floor 120})

(defn- hydration-conversation []
  (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
        (mapcat (fn [t]
                  [{:role "assistant" :content (str "call " t) :turn t}
                   {:role "user" :content (apply str (repeat 800 "x")) :turn t}])
                (range 1 7))))

(deftest hydration-reuses-only-a-verified-stable-prefix
  (let [msgs (hydration-conversation)
        turns (mapv (fn [t] {:turn t :tool-name "eval" :result (str "result " t)})
                    (range 1 7))
        previous (:entry (hydration/compact nil msgs turns hydration-opts))
        repeated (hydration/compact previous msgs turns hydration-opts)]
    (is (= (:messages previous) (llm-message/compact msgs turns hydration-opts)))
    (is (= (:messages repeated) (:messages previous)))
    (is (= (count msgs) (:reused-prefix repeated))
        "an identical render reuses the whole entry")
    (is (every? true? (map identical? (:messages previous) (:messages repeated))))
    (let [msgs' (conj msgs {:role "assistant" :content "call 7" :turn 7}
                      {:role "user" :content "result 7" :turn 7})
          turns' (conj turns {:turn 7 :tool-name "eval" :result "result 7"})
          next-entry (hydration/compact previous msgs' turns' hydration-opts)]
      (is (= (:messages next-entry) (llm-message/compact msgs' turns' hydration-opts))
          "an appended tape is byte-identical to an uncached render")
      (is (pos? (:reused-prefix next-entry)))
      (is (every? true? (map identical?
                             (take (:reused-prefix next-entry) (:messages previous))
                             (take (:reused-prefix next-entry) (:messages next-entry)))))
      (testing "an edit, an option change, and changed turn evidence each invalidate reuse"
        (is (= 2 (:reused-prefix
                  (hydration/compact previous (assoc msgs 2 {:role "assistant" :content "edited" :turn 1})
                                     turns hydration-opts)))
            "only the protected frame precedes the edit")
        (is (zero? (:reused-prefix
                    (hydration/compact previous msgs turns (assoc hydration-opts :keep-pairs 3)))))
        (is (zero? (:reused-prefix
                    (hydration/compact previous msgs (assoc turns 0 {:turn 1 :tool-name "other"})
                                       hydration-opts))))))))
;; --- a file rewritten later drops its earlier body ----------------------------

(defn- writes-conversation []
  [{:role "system" :content "sys"} {:role "user" :content "go"}
   {:role "assistant" :content (str "v1 " (apply str (repeat 500 "a"))) :turn 1}
   {:role "user" :content "ok" :turn 1}
   {:role "assistant" :content (str "other " (apply str (repeat 500 "b"))) :turn 2}
   {:role "user" :content "ok" :turn 2}
   {:role "assistant" :content (str "v2 " (apply str (repeat 500 "c"))) :turn 3}
   {:role "user" :content "ok" :turn 3}
   {:role "assistant" :content (str "refused " (apply str (repeat 500 "d"))) :turn 4}
   {:role "user" :content "refused" :turn 4}])

(def ^:private writes-turns
  [{:turn 1 :tool "write_file" :path "src/a.py" :category :success}
   {:turn 2 :tool "write_file" :path "src/b.py" :category :success}
   {:turn 3 :tool "write_file" :path "src/a.py" :category :success}
   {:turn 4 :tool "write_file" :path "src/b.py" :category :failure}])

(def ^:private writes-opts
  ;; Far under any threshold: only the supersede rule can fire.
  {:keep-pairs 30 :threshold-chars 1000000 :batch 1 :floor 120
   :supersede-tools #{"write_file"}})

(deftest a-rewritten-file-s-earlier-write-is-unloaded
  (let [plan (llm-message/compaction-plan (writes-conversation) writes-turns writes-opts)
        out (:messages plan)]
    (is (llm-message/unloaded? (:content (nth out 2))) "v1 of src/a.py, rewritten at turn 3")
    (is (str/includes? (:content (nth out 2)) "t1 write_file"))
    (is (str/starts-with? (:content (nth out 6)) "v2 ") "the current version stays whole")
    (is (str/starts-with? (:content (nth out 4)) "other ")
        "src/b.py's only successful write stays: the later one was refused")
    (is (str/starts-with? (:content (nth out 8)) "refused "))
    (is (contains? (:rewrites plan) 2) "the rewrite is one the prefix cache knows about")
    (is (= (count (writes-conversation)) (count out)) "shape unchanged")))

(deftest without-the-policy-nothing-is-superseded
  (let [out (:messages (llm-message/compaction-plan (writes-conversation) writes-turns
                                                    (dissoc writes-opts :supersede-tools)))]
    (is (= (writes-conversation) out))))

(deftest a-superseded-render-matches-an-uncached-one
  (let [opts writes-opts
        msgs (vec (take 6 (writes-conversation)))
        turns (vec (take 2 writes-turns))
        previous (:entry (hydration/compact nil msgs turns opts))
        msgs' (writes-conversation)
        next-entry (hydration/compact previous msgs' writes-turns opts)]
    (is (= (:messages next-entry) (llm-message/compact msgs' writes-turns opts))
        "a write superseded after the last render is rewritten, not reused stale")))

(deftest replayed-reasoning-counts-toward-the-fold
  ;; ctb-r12 interleaved-vigenere: ~480K characters of reasoning went back to the
  ;; endpoint while the threshold counted only the ~180K without it.
  (let [msgs (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
                   (mapcat (fn [t]
                             [{:role "assistant" :content (str "<think>" (apply str (repeat 400 "r")) "</think>call " t) :turn t}
                              {:role "user" :content "ok" :turn t}])
                           (range 1 9)))
        turns (mapv (fn [t] {:turn t :tool "eval" :category :success}) (range 1 9))
        opts {:keep-pairs 2 :threshold-chars 1500 :batch 1 :floor 120}]
    (is (= msgs (:messages (llm-message/compaction-plan msgs turns opts)))
        "without replay the visible history is under the threshold")
    (is (some #(llm-message/unloaded? (:content %))
              (:messages (llm-message/compaction-plan msgs turns (assoc opts :count-reasoning? true))))
        "with replay the same history folds")))

;; --- reasoning goes back for the recent replies only -----------------------------

(defn- thinking-conversation [n]
  (into [{:role "system" :content "sys"} {:role "user" :content "go"}]
        (mapcat (fn [t]
                  [{:role "assistant" :content (str "<think>thought " t "</think>call " t) :turn t}
                   {:role "user" :content (str "result " t) :turn t}])
                (range 1 (inc n)))))

(defn- thinks [out] (mapv #(str/includes? (str (:content %)) "<think>")
                          (filter #(= "assistant" (:role %)) out)))

(deftest only-the-recent-replies-keep-their-reasoning
  ;; ctb-r12 photonic-waveguide-routing: one 251K-character thought went back on
  ;; every later turn and the builder reached 200K tokens in 17 turns.
  (let [opts {:keep-pairs 30 :threshold-chars 1000000 :batch 1 :floor 120 :reasoning-keep-turns 2}
        plan (llm-message/compaction-plan (thinking-conversation 5) [] opts)]
    (is (= [false false false true true] (thinks (:messages plan))))
    (is (str/includes? (:content (nth (:messages plan) 2)) "call 1") "the reply itself stays")
    (is (contains? (:rewrites plan) 2) "the rewrite is one the prefix cache knows about"))
  (testing "off without the policy"
    (is (= [true true true true true]
           (thinks (:messages (llm-message/compaction-plan (thinking-conversation 5) []
                                                           {:keep-pairs 30 :threshold-chars 1000000}))))))
  (testing "the frontier moves in batches, so most turns send an identical history"
    (let [opts {:keep-pairs 30 :threshold-chars 1000000 :batch 4 :floor 120 :reasoning-keep-turns 2}
          at (fn [n] (thinks (:messages (llm-message/compaction-plan (thinking-conversation n) [] opts))))]
      (is (= [false false false false true true] (at 6)) "6 replies: 4 aged, a whole batch, stripped")
      (is (= [false false false false true true true] (at 7)) "7: the frontier holds")
      (is (= [false false false false true true true true true] (at 9)) "9: still holds")
      (is (= [false false false false false false false false true true] (at 10)) "10: it moves by a batch"))))

(deftest a-reasoning-window-render-matches-an-uncached-one
  (let [opts {:keep-pairs 30 :threshold-chars 1000000 :batch 2 :floor 120 :reasoning-keep-turns 2}
        previous (:entry (hydration/compact nil (thinking-conversation 5) [] opts))
        next-entry (hydration/compact previous (thinking-conversation 7) [] opts)]
    (is (= (:messages next-entry) (llm-message/compact (thinking-conversation 7) [] opts)))))

(deftest aged-reasoning-does-not-count-toward-the-fold
  (let [msgs (thinking-conversation 6)
        big (mapv #(if (= "assistant" (:role %))
                     (update % :content (fn [c] (str/replace c "thought" (apply str (repeat 300 "t")))))
                     %)
                  msgs)
        opts {:keep-pairs 1 :threshold-chars 1000 :batch 1 :floor 120 :count-reasoning? true}]
    (is (some #(llm-message/unloaded? (:content %))
              (:messages (llm-message/compaction-plan big [] opts)))
        "all of it replayed: over the threshold")
    (is (not-any? #(llm-message/unloaded? (:content %))
                  (:messages (llm-message/compaction-plan big [] (assoc opts :reasoning-keep-turns 2))))
        "only two replies' reasoning sent: under it")))
