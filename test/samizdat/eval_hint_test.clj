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

(ns samizdat.eval-hint-test
  "An eval error says what works instead (karamazov-0e2c.18, after xi's
  interop-hint): a model with JVM habits in jolt loses a turn to each one
  unless the error names the move that works."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [mycelium.cell]
            [samizdat.cells]
            [samizdat.agent.gates]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.tools.repl :as repl-tool]
            [samizdat.repl.route :as route]))

(deftest the-error-names-what-works
  (is (re-find #"(?i)require" (str (repl-tool/eval-hint "Unknown class gates"))))
  (is (re-find #"(?i)shell" (str (repl-tool/eval-hint "Could not locate clojure/java/shell.jolt (or .clj/.cljc) on the source roots"))))
  (is (re-find #"(?i)deps.edn|:paths" (str (repl-tool/eval-hint "Could not locate flight/core.jolt (or .clj/.cljc) on the source roots"))))
  (is (nil? (repl-tool/eval-hint "Divide by zero")) "an ordinary error carries no hint"))

(deftest an-eval-error-carries-its-hint
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "Unknown class str"})]
    (let [r (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(str/join [1])"}})]
      (is (str/includes? (:result r) "Unknown class str"))
      (is (re-find #"(?i)require" (:result r)) (:result r)))))

(deftest a-failed-batch-says-which-form-and-what-already-ran
  ;; arrodes' failure detail (karamazov-xn0h.3): the form, its line, and that
  ;; the ones before it ran — so the model fixes one form rather than
  ;; re-running a batch whose first forms already took effect.
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "Divide by zero"
                                          :form {:index 2 :count 3 :line 4 :completed 1}})]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"}
                                      :args {:code "(def a 1)\n(/ 1 0)\n(def b 2)"}}))]
      (is (str/includes? r "Divide by zero"))
      (is (re-find #"form 2 of 3" r) r)
      (is (re-find #"line 4" r) r)
      (is (re-find #"(?i)1 form.*ran" r) r)))
  (testing "a single form says nothing extra"
    (with-redefs [route/eval-for (fn [& _] {:ok false :error "Divide by zero"
                                            :form {:index 1 :count 1 :line 1 :completed 0}})]
      (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"}
                                        :args {:code "(/ 1 0)"}}))]
        (is (not (re-find #"form 1 of 1" r)) r)))))

(deftest the-diagnosis-names-the-image-it-ran-in
  ;; eval-error.md was appended only on the harness path, and said "the live
  ;; image" — under :project the session lives in a separate process.
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "boom" :where "samizdat.repl.session-9"})
                route/image-of (constantly :project)]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(f)"}}))]
      (is (str/includes? r "samizdat.repl.session-9"))
      (is (re-find #"project's image" r) r)
      (is (not (re-find #"harness image" r)) r)))
  (with-redefs [route/eval-for (fn [& _] {:ok false :error "boom" :where "samizdat.repl.session-9"})
                route/image-of (constantly :harness)]
    (let [r (:result (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "(f)"}}))]
      (is (re-find #"harness image" r) r))))

;; --- the eval bridge's handler (karamazov-xn0h.7) ----------------------------

(deftest the-eval-tool-hands-the-image-a-gated-tool-runner
  ;; What the eval tool puts in ctx :bridge is the same path a turn's call
  ;; takes; route answers the image's requests with it.
  (let [seen (atom nil)]
    (with-redefs [route/eval-for (fn [ctx & _] (reset! seen (:bridge ctx)) {:ok true :value "nil"})
                  route/bridge-policy (constantly {:enabled? true :tools #{"read_file"}})]
      (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "1"}})
      (let [h @seen]
        (is (fn? h))
        (testing "a tool the policy does not list is refused, naming what is"
          (let [a (h {:id "1" :op "shell" :args {:command "ls"}})]
            (is (contains? a :error))
            (is (str/includes? (:error a) "read_file"))))
        (testing "a listed one runs through the tool path"
          (with-redefs [samizdat.agent.tools/run-tool
                        (fn [c] {:branch (:branch c) :category :success
                                 :result (str "ran " (:tool-name c) " " (pr-str (:args c)))})]
            (is (= {:value "ran read_file {:path \"a\"}"}
                   (h {:id "2" :op "read_file" :args {:path "a"}})))))))))

(deftest with-the-bridge-off-the-eval-tool-offers-no-runner
  (let [seen (atom ::unset)]
    (with-redefs [route/eval-for (fn [ctx & _] (reset! seen (:bridge ctx)) {:ok true :value "nil"})
                  route/bridge-policy (constantly {:enabled? false})]
      (tools/run-tool {:tool-name "eval" :branch {:id "B1"} :args {:code "1"}})
      (is (nil? @seen)))))

(deftest the-prompt-names-the-bridge-tools-only-when-they-are-served
  (let [prompt-for (requiring-resolve 'samizdat.agent.loop/system-prompt-for)
        threshold samizdat.agent.gates/threshold
        with-bridge (fn [on?]
                      (with-redefs [samizdat.agent.gates/threshold
                                    (fn [k] (if (= k :eval-bridge)
                                              {:enabled? on? :tools #{"grep"}}
                                              (threshold k)))]
                        (prompt-for :implementor)))]
    (is (str/includes? (with-bridge true) "`tool/grep`"))
    (is (not (str/includes? (with-bridge false) "TOOLS ARE FUNCTIONS")))))

(deftest a-callable-cell-runs-with-the-branchs-gated-tools
  ;; karamazov-xn0h.8: a cell called from eval reaches tools only through the
  ;; branch's own path — the bridge's allowlist, then run-tool.
  (let [snap (mycelium.cell/registry-snapshot)
        seen (atom nil)]
    (try
      (samizdat.cells/eval-candidate!
       "(ns lex.t (:require [mycelium.cell :as cell] [samizdat.callable :as callable]))
        (cell/defcell :lex/shout {:doc \"Shouts a file.\" :callable true :effects [:fs]
                                  :input [:map [:path :string]]}
          (fn [_ {:keys [path]}] {:loud (clojure.string/upper-case (callable/tool \"read_file\" {:path path}))}))
        (cell/defcell :lex/sneaky {:doc \"Tries the shell.\" :callable true :effects [:fs]}
          (fn [_ _] (callable/tool \"shell\" {:command \"ls\"})))")
      (with-redefs [route/eval-for (fn [ctx & _] (reset! seen (:bridge ctx)) {:ok true :value "nil"})
                    route/bridge-policy (constantly {:enabled? true :tools #{"read_file"} :cells? true})
                    samizdat.agent.tools/run-tool (fn [c] {:branch (:branch c) :category :success
                                                          :result (str "text of " (get-in c [:args :path]))})]
        ;; Straight to the eval tool: run-tool is stubbed as the inner path.
        ((requiring-resolve 'samizdat.agent.tools.base/run-tool)
         {:tool-name "eval" :branch {:id "B1"} :args {:code "1"}})
        (let [h @seen]
          (is (= {:value {:loud "TEXT OF A.CLJ"}}
                 (h {:id "1" :op "cell" :args {:id :lex/shout :args {:path "a.clj"}}})))
          (testing "a tool the bridge does not offer is not reachable through a cell either"
            (is (str/includes? (:error (h {:id "2" :op "cell" :args {:id :lex/sneaky :args {}}}))
                               "read_file")))))
      (finally (mycelium.cell/registry-restore! snap)))))
