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

(ns samizdat.tui.input-test
  "The compose box through real FTXUI: that Up on its top row reaches the
  history and a drag over it selects what was typed. The data tests show the
  widget names :on-up-edge; only the toolkit can say a key press gets there."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [ftxui.core :as ui]
            [samizdat.tui.layout :as layout]
            [samizdat.tui.state :as st]
            [samizdat.tui.widgets :as w]))

(defn- box [state]
  (fn []
    (w/input (assoc @state
                    :settings {:editing (:editing (layout/template))}
                    :on {:input #(swap! state st/set-input %)
                                :history-back #(swap! state st/history-back)
                                :history-forward #(swap! state st/history-forward)})
             {})))

(deftest up-and-down-walk-what-was-sent-and-come-back-to-the-draft
  (let [state (atom (-> (st/initial "b")
                        (st/remember-input "first thing")
                        (st/remember-input "second thing")))]
    (ui/with-screen [s (box state)]
      (ui/render-text s 80 3)
      (ui/send-char! s "draft")
      (ui/send-key! s :arrow-up)
      (is (= "second thing" (:input @state)))
      (ui/send-key! s :arrow-up)
      (is (= "first thing" (:input @state)))
      (ui/send-key! s :arrow-down)
      (ui/send-key! s :arrow-down)
      (is (= "draft" (:input @state)) "past the newest, the line being typed")
      (testing "a recalled line is edited from its end"
        (ui/send-key! s :arrow-up)
        (ui/send-char! s "!")
        (is (= "second thing!" (:input @state)))))))

(deftest what-was-typed-can-be-selected
  (let [state (atom (st/set-input (st/initial "b") "select me please"))]
    (ui/with-screen [s (box state)]
      (let [frame (ui/render-text s 80 3)
            y (first (keep-indexed (fn [i l] (when (str/includes? l "select me") i))
                                   (str/split-lines frame)))
            x (str/index-of (nth (str/split-lines frame) y) "select")]
        (is (false? (ui/send-mouse! s {:x (+ x 2) :y y}))
            "a press in the box is left for the selection to start from")
        (is (= "select me" (ui/selection-text s 80 3 [x y (+ x 8) y])))))))

(deftest a-long-line-is-sent-whole
  ;; Every run started from the TUI had its problem cut to 128 characters —
  ;; FTXUI's read size — and the rest of what was typed or pasted was lost.
  (let [line (apply str (map #(format "%03d " %) (range 75)))
        sent (atom nil)
        state (atom (st/initial "b"))
        app (fn [] (w/input (assoc @state :run-id "r1" :detail {:run {:status "running"}}
                                   :on {:input #(swap! state st/set-input %)
                                        :submit #(reset! sent %)})
                            {}))]
    (ui/with-screen [s app]
      (ui/render-text s 120 6)
      ;; One event a character, as a terminal delivers them, and a frame
      ;; after every 128 — FTXUI's read size.
      (doseq [chunk (partition-all 128 line)]
        (doseq [c chunk] (ui/send-char! s (str c)))
        (ui/render-text s 120 6))
      (is (= line (:input @state)) "the state holds all of it")
      (ui/send-key! s :return)
      (is (= line @sent) "and Enter sends all of it"))))

(deftest the-editing-keys-reach-the-box
  ;; The data tests show the box names :on-key and the shipped keymap binds
  ;; these; only the toolkit can say the terminal's bytes get there.
  (let [state (atom (st/initial "b"))]
    (ui/with-screen [s (box state)]
      (ui/render-text s 80 3)
      (ui/send-char! s "one two three")
      (testing "Option+Left, as xterm sends it, then typing lands a word back"
        (ui/send-input! s "\u001b[1;3D")
        (ui/send-char! s "X")
        (is (= "one two Xthree" (:input @state))))
      (testing "Alt+B, the other spelling, and Ctrl+Left, Linux's"
        (ui/send-key! s :alt-b)
        (ui/send-key! s :arrow-left-ctrl)
        (ui/send-char! s "Y")
        (is (= "one Ytwo Xthree" (:input @state)) "a word back each"))
      (testing "fn+Right arrives as End: the line's end"
        (ui/send-key! s :end)
        (ui/send-char! s "!")
        (is (= "one Ytwo Xthree!" (:input @state))))
      (testing "Ctrl+W and Alt+Backspace delete a word back"
        (ui/send-key! s :ctrl-w)
        (is (= "one Ytwo " (:input @state)) "the word and the punctuation after it")
        (ui/send-input! s "\u001b\u007f")
        (is (= "one " (:input @state))))
      (testing "Ctrl+A then Ctrl+K kills the line"
        (ui/send-key! s :ctrl-a)
        (ui/send-key! s :ctrl-k)
        (is (= "" (:input @state)))))))
