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

(ns samizdat.llm.loop-guard
  "A REPLY THAT STARTS TO LOOP, CAUGHT WHILE IT STREAMS (karamazov-hgvj).

  `samizdat.llm.repetition` judges the finished reply, so a model that
  degenerates into one passage ran to :max-tokens before anything noticed.
  This watches the stream: `guard` answers a function of one delta that
  returns what looped, in words, once it has; `samizdat.llm.stream/post`
  closes the connection on it.

  Two checks, ported from nodecode's loop-guard.lisp, itself a port of
  oh-my-pi's thinking-loop guard (packages/ai/src/utils/thinking-loop.ts,
  MIT, Copyright (c) 2025 Mario Zechner, 2025-2026 Can Bölük, 2026 Stencil
  Labs, Inc.):
    1. :exact — one unit repeated back to back at the end of the stream,
       found with a Z-array over the tail read from its end;
    2. :semantic adds near-duplicate paragraphs, word-trigram Jaccard
       against the recent ones.
  Every number is gates.edn :loop-guard. Pure but for `guard`, which holds
  one attempt's detectors in an atom."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; --- the exact check -----------------------------------------------------------

(defn- z-suffix
  "Z[i] for i in 1..max-unit over `text` read from its end: how far the end
  agrees with itself shifted back by i."
  [^String text max-unit]
  (let [n (count text)
        last-i (dec n)
        z (long-array (inc max-unit))]
    (loop [i 1 left 0 right 0]
      (if (> i max-unit)
        z
        (let [zi (loop [zi (if (<= i right) (min (inc (- right i)) (aget z (- i left))) 0)]
                   (if (and (< (+ i zi) n)
                            (= (.charAt text (- last-i zi)) (.charAt text (- last-i i zi))))
                     (recur (inc zi))
                     zi))]
          (aset z i (long zi))
          (if (> (+ i zi -1) right)
            (recur (inc i) i (+ i zi -1))
            (recur (inc i) left right)))))))

(defn- lettered? [unit] (boolean (some #(Character/isLetter (char %)) unit)))

(defn- digits-and-blanks? [unit]
  (every? #(or (Character/isDigit (char %)) (Character/isWhitespace (char %))) unit))

(defn exact-suffix-cycle
  "The cycle `text` ends in, `{:unit :repeats}`, or nil. A unit with a letter
  counts at the short or long floor; one with no letter only past the long
  floor; one of digits and whitespace never — padding and zero-filled dumps
  are ordinary output."
  [text {:keys [max-unit short-unit short-repeated-chars long-repeated-chars]}]
  (let [text (str text)
        n (count text)]
    (when (>= n short-repeated-chars)
      (let [max-unit (min max-unit (quot n 3))
            z (z-suffix text max-unit)]
        (some (fn [len]
                (let [repeats (inc (quot (aget z len) len))
                      covered (* len repeats)]
                  (when (>= repeats (if (<= len short-unit) 4 3))
                    (let [unit (subs text (- n len))]
                      (cond
                        (lettered? unit)
                        (when (>= covered (if (and (<= len short-unit)
                                                   (not (str/includes? unit "\n")))
                                            short-repeated-chars
                                            long-repeated-chars))
                          {:unit unit :repeats repeats})
                        (digits-and-blanks? unit) nil
                        (>= covered long-repeated-chars) {:unit unit :repeats repeats})))))
              (range 2 (inc max-unit)))))))

;; --- the paragraph check ------------------------------------------------------

(def ^:private code-line
  ;; A whole line shaped like code: a fence, an indented line that is not a
  ;; list item, or one ending in a block, statement or markup delimiter.
  #"(?m)^(?:[ \t]*```.*|(?: {2,}|\t)(?![ \t]*(?:[-*+]|\d+[.)])[ \t]).*|.*[{}\[\](;,>][ \t]*)$")

(def ^:private heading-line #"(?m)^[ \t]*#{1,6}[ \t].*$")

(def ^:private title-line #"(?m)^[ \t]*\*{2,3}.+?\*{2,3}[ \t]*$")

(def ^:private paragraph-break #"\n\s*\n")

(defn- normalize
  "Lowercase words of letters and digits, the all-digit ones dropped."
  [segment]
  (->> (str/split (str/replace (str/lower-case segment) #"[^a-z0-9]+" " ") #" +")
       (filter #(re-find #"[a-z]" %))
       vec))

(defn- trigrams [words]
  (if (< (count words) 3)
    (if (seq words) #{(str/join " " words)} #{})
    (set (map #(str/join " " %) (partition 3 1 words)))))

(defn- jaccard [a b]
  (if (or (empty? a) (empty? b))
    0.0
    (let [shared (count (set/intersection a b))]
      (/ (double shared) (- (+ (count a) (count b)) shared)))))

(defn- judge-segment
  "Fold one paragraph into `d`; `d` with :hit when it completes a cluster."
  [d raw {:keys [segment-floor segment-window segment-similarity segment-warmup segment-cluster]}]
  (let [words (normalize (-> raw
                             (str/replace code-line "")
                             (str/replace heading-line "")
                             (str/replace title-line "")))]
    (if (< (count (str/join " " words)) segment-floor)
      d
      (let [grams (trigrams words)
            cluster (inc (count (filter #(>= (jaccard grams %) (- segment-similarity 1e-9))
                                        (:recent d))))
            judged (inc (:judged d 0))
            d (assoc d
                     :recent (vec (take-last segment-window (conj (:recent d []) grams)))
                     :judged judged)]
        (if (and (>= judged segment-warmup) (>= cluster segment-cluster))
          (assoc d :hit (str "wrote " cluster " near-identical paragraphs within its last "
                             segment-window))
          d)))))

(defn- judge-paragraph [d text {:keys [segment-cap] :as policy}]
  (reduce (fn [d start]
            (let [d (judge-segment d (subs text start (min (count text) (+ start segment-cap))) policy)]
              (if (:hit d) (reduced d) d)))
          d
          (range 0 (count text) segment-cap)))

(defn- take-paragraphs
  "Judge every whole paragraph `pending` holds, keeping the rest."
  [d {:keys [segment-cap] :as policy}]
  (loop [d d]
    (let [pending (:pending d)
          m (re-matcher paragraph-break pending)
          [para more] (cond
                        (.find m) [(subs pending 0 (.start m)) (subs pending (.end m))]
                        (> (count pending) segment-cap) [(subs pending 0 segment-cap)
                                                         (subs pending segment-cap)])]
      (if-not para
        d
        (let [d (judge-paragraph (assoc d :pending more) para policy)]
          (if (:hit d) d (recur d)))))))

;; --- the detector ------------------------------------------------------------

(defn detector
  "A fresh detector for one stream of text."
  [semantic?]
  {:semantic? (boolean semantic?) :tail "" :unscanned 0 :pending "" :recent [] :judged 0})

(defn push
  "Feed `text`, the next streamed piece, to `d`. Answers `d`, with :hit — the
  loop in words — once what it has seen ends in one."
  [d text {:keys [tail-window check-stride] :as policy}]
  (let [text (str text)]
    (if (or (:hit d) (str/blank? text) (empty? text))
      d
      (let [tail (str (:tail d) text)
            tail (if (> (count tail) tail-window) (subs tail (- (count tail) tail-window)) tail)
            unscanned (+ (:unscanned d) (count text))
            scan? (>= unscanned check-stride)
            cycle (when scan? (exact-suffix-cycle tail policy))
            d (assoc d :tail tail :unscanned (if scan? 0 unscanned))]
        (cond
          cycle (assoc d :hit (str "repeated one " (count (:unit cycle)) "-character passage "
                                   (:repeats cycle) " times back to back"))
          (:semantic? d) (take-paragraphs (update d :pending str text) policy)
          :else d)))))

;; --- the level and the attempt guard -------------------------------------------

(defn level
  "How much of the guard watches an attempt on adapter `adapter-id`: :off,
  :exact or :semantic. `configured`, the provider's own :loop-guard, outranks
  the policy's table; no policy is :off."
  [adapter-id configured policy]
  (cond
    configured (keyword configured)
    (nil? policy) :off
    :else (get (:levels policy) (keyword adapter-id) (:default-level policy :off))))

(def ^:private fence-open #"```tool-call|<tool-call>")

(defn guard
  "One attempt's watch at `lvl`, or nil at :off: a function of a stream
  delta ({:text} and/or {:reasoning}) answering what looped, in words, once
  something has. Reasoning is watched until the answer's text starts; text
  until a tool-call fence opens, since what follows is arguments."
  [lvl policy]
  (when (and policy (#{:exact :semantic} lvl))
    (let [semantic? (= :semantic lvl)
          state (atom {:reasoning (detector semantic?) :text (detector semantic?)
                       :answering? false :text-seen "" :in-call? false})]
      (fn [{:keys [text reasoning]}]
        (let [s (swap! state
                       (fn [s]
                         (let [s (if (and (seq reasoning) (not (:answering? s)))
                                   (update s :reasoning push reasoning policy)
                                   s)]
                           (if (or (empty? (str text)) (:in-call? s))
                             s
                             (let [seen (str (:text-seen s) text)
                                   m (re-matcher fence-open seen)]
                               (if (.find m)
                                 (-> s
                                     (assoc :answering? true :in-call? true :text-seen "")
                                     ;; the new text before the opener; an
                                     ;; opener begun in the last delta leaves none
                                     (update :text push
                                             (subs seen (min (count (:text-seen s)) (.start m)) (.start m))
                                             policy))
                                 (-> s
                                     (assoc :answering? true
                                            ;; enough to see an opener split across deltas
                                            :text-seen (let [k (count seen)] (subs seen (max 0 (- k 16)))))
                                     (update :text push text policy))))))))]
          (or (some->> (get-in s [:reasoning :hit]) (str "its reasoning "))
              (some->> (get-in s [:text :hit]) (str "its answer "))))))))
