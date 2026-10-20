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

(ns samizdat.tui-editing-test
  "The compose box's editing keys: the commands as pure edits of a text and
  a cursor, the keymap tui.edn ships, and the box handing its keys to them."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [samizdat.tui.editing :as ed]
            [samizdat.tui.layout :as layout]
            [clojure.walk]
            [samizdat.tui.widgets]))

(defn- at
  "\"ab|cd\" -> {:value \"abcd\" :cursor 2}: a text with its cursor marked."
  [s]
  (let [i (str/index-of s "|")]
    {:value (str (subs s 0 i) (subs s (inc i))) :cursor i}))

(defn- shown [{:keys [value cursor]}]
  (str (subs value 0 cursor) "|" (subs value cursor)))

(defn- run [cmd s] (shown (ed/edit cmd (at s))))

(deftest word-moves
  (is (= "hello |world" (run :word-left "hello world|")))
  (is (= "|hello world" (run :word-left "hello |world")))
  (is (= "foo.|bar" (run :word-left "foo.bar|")) "punctuation separates words")
  (is (= "|" (run :word-left "|")) "nothing to the left, nowhere to go")
  (is (= "hello| world" (run :word-right "|hello world")) "to the end of the word")
  (is (= "hello world|" (run :word-right "hello| world")))
  (is (= "héllo| wörld" (run :word-right "|héllo wörld")) "letters beyond ASCII are word characters")
  (is (= "one\n|two" (run :word-left "one\ntwo|"))))

(deftest line-moves-stay-on-the-cursor-s-line
  (is (= "one\n|two\nthree" (run :line-start "one\ntw|o\nthree")))
  (is (= "one\ntwo|\nthree" (run :line-end "one\nt|wo\nthree")))
  (is (= "|one two" (run :line-start "one tw|o")))
  (is (= "one two|" (run :line-end "o|ne two")))
  (is (= "|one\ntwo" (run :text-start "one\ntw|o")))
  (is (= "one\ntwo|" (run :text-end "o|ne\ntwo"))))

(deftest deletions
  (is (= "hello |" (run :delete-word-back "hello world|")))
  (is (= "foo.|" (run :delete-word-back "foo.bar|")))
  (is (= "|" (run :delete-word-back "hello  |")) "the spaces and the word before them, as readline does")
  (is (= "hello |" (run :delete-word-forward "hello |world")))
  (is (= "|" (run :delete-word-forward "| world")))
  (is (= "one\n|ree" (run :kill-line-start "one\nth|ree")))
  (is (= "one\nth|" (run :kill-line-end "one\nth|ree")))
  (is (= "one|two" (run :kill-line-end "one|\ntwo")) "at a line's end the break goes, as in emacs")
  (is (= "one|two" (run :kill-line-start "one\n|two")) "and at its start, the one before it"))

(deftest a-command-at-the-edge-changes-nothing
  (doseq [cmd [:word-left :line-start :text-start :delete-word-back :kill-line-start]]
    (is (= "|abc" (run cmd "|abc")) (name cmd)))
  (doseq [cmd [:word-right :line-end :text-end :delete-word-forward :kill-line-end]]
    (is (= "abc|" (run cmd "abc|")) (name cmd))))

(deftest an-unknown-command-is-nil
  (is (nil? (ed/edit :no-such-command (at "a|b")))))

(deftest the-keymap-matches-a-named-key-or-the-raw-bytes
  (let [km {:alt-b :word-left "\u001b[1;3D" :word-left "x" :word-right}]
    (is (= :word-left (ed/command km {:type :key :key :alt-b :input "\u001bb"})))
    (is (= :word-left (ed/command km {:type :unknown :input "\u001b[1;3D"})))
    (is (nil? (ed/command km {:type :character :char "x" :input "x"}))
        "a typed character is text, whatever the map says")
    (is (nil? (ed/command km {:type :mouse :button :left})))
    (is (nil? (ed/command nil {:type :key :key :alt-b})))))

(deftest the-shipped-keymap-has-the-mac-and-linux-conventions
  (let [km (get-in (layout/template) [:editing :keys])
        cmd (fn [e] (ed/command km e))]
    (testing "Option+Left/Right, in both spellings terminals send"
      (is (= :word-left (cmd {:type :key :key :alt-b})))
      (is (= :word-right (cmd {:type :key :key :alt-f})))
      (is (= :word-left (cmd {:type :unknown :input "\u001b[1;3D"})))
      (is (= :word-right (cmd {:type :unknown :input "\u001b[1;3C"}))))
    (testing "fn+Left/Right, which arrive as Home and End; Cmd+arrows as Ctrl+A/E"
      (is (= :line-start (cmd {:type :key :key :home})))
      (is (= :line-end (cmd {:type :key :key :end})))
      (is (= :line-start (cmd {:type :key :key :ctrl-a})))
      (is (= :line-end (cmd {:type :key :key :ctrl-e}))))
    (testing "Linux: Ctrl+arrows, Ctrl+Backspace and Ctrl+Delete by word"
      (is (= :word-left (cmd {:type :key :key :arrow-left-ctrl})))
      (is (= :word-right (cmd {:type :key :key :arrow-right-ctrl})))
      (is (= :delete-word-back (cmd {:type :key :key :ctrl-h})))
      (is (= :delete-word-forward (cmd {:type :unknown :input "\u001b[3;5~"}))))
    (testing "readline's kills"
      (is (= :delete-word-back (cmd {:type :key :key :ctrl-w})))
      (is (= :delete-word-back (cmd {:type :unknown :input "\u001b\u007f"})))
      (is (= :delete-word-forward (cmd {:type :key :key :alt-d})))
      (is (= :kill-line-start (cmd {:type :key :key :ctrl-u})))
      (is (= :kill-line-end (cmd {:type :key :key :ctrl-k}))))
    (testing "every binding names a command edit knows"
      (doseq [[k c] km]
        (is (some? (ed/edit c {:value "a b" :cursor 1})) (pr-str k))))))

(defn- compose-input [state]
  (let [acc (atom nil)]
    (clojure.walk/postwalk (fn [x] (when (and (vector? x) (= :input (first x))) (reset! acc x)) x)
                           ((get @layout/widgets :widget/input) state {}))
    @acc))

(deftest the-compose-box-hands-its-keys-to-the-keymap
  (let [state {:input "hello world" :settings {:editing {:keys {:alt-b :word-left}}}}
        on-key (:on-key (second (compose-input state)))]
    (is (fn? on-key))
    (is (= {:value "hello world" :cursor 6}
           (on-key {:type :key :key :alt-b} {:value "hello world" :cursor 11})))
    (is (nil? (on-key {:type :key :key :alt-z} {:value "hello world" :cursor 11}))
        "an unbound key is the box's own")))

(deftest a-paste-goes-in-at-the-cursor
  (is (= "hello big |world" (shown (ed/insert (at "hello |world") "big "))))
  (is (= "ab|" (shown (ed/insert (at "a|") "b"))))
  (testing "the box takes the closed paste when the bracket closing it arrives"
    (let [taken (atom "[8 lines pasted #1]")
          state {:input "hello world"
                 :on {:take-paste #(let [p @taken] (reset! taken nil) p)}}
          on-key (:on-key (second (compose-input state)))
          close {:type :unknown :input "\u001b[201~"}]
      (is (= {:value "hello [8 lines pasted #1]world" :cursor 25}
             (on-key close {:value "hello world" :cursor 6})))
      (is (nil? (on-key close {:value "hello world" :cursor 6}))
          "once: nothing left to take, the key is the box's"))))
