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

(ns samizdat.tui.editing
  "The compose box's editing keys: by word, to a line's ends, and readline's
  kills. Each command is a pure edit of `{:value text :cursor n}`, the cursor
  a character index; which key runs which command is tui.edn's :editing
  :keys, so a terminal that sends something else for Option+Left is a line
  of EDN, not a release. The box hands these its keys through ftxui-jolt's
  :on-key.

  A word is a run of letters, digits and underscores, so punctuation
  separates words as it does for Option+arrows on a Mac. A line is the
  cursor's own line of a multi-line draft.")

(defn- word-char? [c]
  (or (Character/isLetterOrDigit (char c)) (= \_ c)))

(defn- word-start
  "Where a move a word back from `i` lands: over what is not a word, then
  over the word."
  [s i]
  (let [skip (fn [i pred] (loop [i i] (if (and (pos? i) (pred (nth s (dec i)))) (recur (dec i)) i)))]
    (skip (skip i (complement word-char?)) word-char?)))

(defn- word-end
  "Where a move a word forward from `i` lands: the end of the next word."
  [s i]
  (let [n (count s)
        skip (fn [i pred] (loop [i i] (if (and (< i n) (pred (nth s i))) (recur (inc i)) i)))]
    (skip (skip i (complement word-char?)) word-char?)))

(defn- line-start [s i]
  (if-let [nl (and (pos? i) (.lastIndexOf ^String s "\n" (int (dec i))))]
    (if (neg? nl) 0 (inc nl))
    0))

(defn- line-end [s i]
  (let [nl (.indexOf ^String s "\n" (int i))]
    (if (neg? nl) (count s) nl)))

(defn- cut
  "`s` without [from, to), the cursor at `from`."
  [s from to]
  {:value (str (subs s 0 from) (subs s to)) :cursor from})

(def ^:private commands
  {:word-left           (fn [s i] {:cursor (word-start s i)})
   :word-right          (fn [s i] {:cursor (word-end s i)})
   :line-start          (fn [s i] {:cursor (line-start s i)})
   :line-end            (fn [s i] {:cursor (line-end s i)})
   :text-start          (fn [_ _] {:cursor 0})
   :text-end            (fn [s _] {:cursor (count s)})
   :delete-word-back    (fn [s i] (cut s (word-start s i) i))
   :delete-word-forward (fn [s i] (cut s i (word-end s i)))
   ;; At a line's own start or end there is nothing on the line to kill,
   ;; so the break goes instead and the lines join, as in emacs.
   :kill-line-start     (fn [s i] (let [b (line-start s i)]
                                    (cut s (if (and (= b i) (pos? i)) (dec i) b) i)))
   :kill-line-end       (fn [s i] (let [e (line-end s i)]
                                    (cut s i (if (and (= e i) (< i (count s))) (inc i) e))))})

(defn edit
  "Run command `cmd` on `{:value :cursor}`: the text and cursor after it, or
  nil for a command this does not know."
  [cmd {:keys [value cursor]}]
  (when-let [f (commands cmd)]
    (let [s (str value)
          i (max 0 (min (count s) (or cursor (count s))))]
      (merge {:value s :cursor i} (f s i)))))

(defn insert
  "`text` put in at the cursor of `{:value :cursor}`, the cursor after it."
  [{:keys [value cursor]} text]
  (let [s (str value)
        i (max 0 (min (count s) (or cursor (count s))))
        t (str text)]
    {:value (str (subs s 0 i) t (subs s i)) :cursor (+ i (count t))}))

(defn command
  "The command `keymap` binds to `event`: by its key's name (:alt-b), or else
  by the bytes the terminal sent (\"\\u001b[1;3D\"), for the sequences that
  have no name. A typed character is text and never a command."
  [keymap {:keys [type key input]}]
  (when (#{:key :unknown} type)
    (or (when key (get keymap key))
        (when input (get keymap input)))))
