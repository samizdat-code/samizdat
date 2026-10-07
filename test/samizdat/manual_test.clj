;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.manual-test
  "The operator manual (LR-6).

  The promise the manual makes is that everything it lists exists. So the
  load-bearing test is that every curated entry resolves — and that an entry
  which does not fails LOUD rather than rendering a hole, because a manual
  that quietly lists nothing reads as 'there is nothing here'."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [samizdat.agent.tools :as tools]
            [samizdat.agent.state :as state]
            [jolt.fs :as fs]
            [samizdat.callable]
            [samizdat.manual :as manual]
            [samizdat.repl.route :as route]))

(deftest every-curated-entry-resolves
  ;; This is the whole point. The manual is editable at runtime, so a stale
  ;; entry is a live possibility and has to be caught here rather than at the
  ;; moment an agent tries to call something that is gone.
  (let [es (manual/entries)]
    (is (seq es))
    (doseq [{:keys [name summary]} es]
      (is (str/includes? (str name) "/") (str name " must be namespace-qualified"))
      (is (not (str/blank? summary)) (str name " has no operator summary")))))

(deftest each-entry-carries-both-texts
  ;; Two audiences, two texts: the curated sentence for the operator, the
  ;; var's own docstring for whoever wants the dense version.
  (let [e (manual/find-entry 'samizdat.agent.infer/bounce)]
    (is (some? e))
    (is (= "Probing without committing" (:group e)))
    (is (str/includes? (:summary e) "fixed tape"))
    (is (str/includes? (:doc e) "non-committing")
        "the docstring is the var's own, not a copy of the summary")
    (is (not= (:summary e) (:doc e)))))

(deftest groups-keep-the-resource-order
  ;; The order is editorial — the first group is what to read first — so it
  ;; must survive the compile rather than being sorted.
  (let [gs (mapv :group (manual/groups))]
    (is (= "The tape" (first gs)))
    (is (= (distinct gs) gs) "a group appears once, not scattered")))

(deftest a-broken-entry-fails-loud
  (testing "a name that does not resolve"
    (with-redefs [manual/entries (fn [] (throw (ex-info "boom" {})))]
      (is (thrown? Exception (manual/entries)))))
  (testing "the real reader refuses an unqualified name"
    (is (thrown-with-msg?
         Exception #"namespace-qualified"
         (#'manual/resolve-entry {:name 'bounce :summary "s"}))))
  (testing "and a qualified name that is not there"
    (is (thrown-with-msg?
         Exception #"does not resolve"
         (#'manual/resolve-entry {:name 'samizdat.tape/no-such-thing
                                  :summary "s"})))))

(deftest render-shows-groups-and-summaries
  (let [txt (manual/render)]
    (is (str/includes? txt "## The tape"))
    (is (str/includes? txt "samizdat.agent.infer/trampoline"))
    (is (str/includes? txt "The tape as it was after its first N messages"))))

;; --- the tool ----------------------------------------------------------------

(defn- run
  "The tool as the SUPERVISOR calls it — the role whose eval is the harness
  image, which is the surface the curated manual describes."
  [args]
  (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                   :role :supervisor
                   :tool-name "manual"
                   :args args}))

(deftest the-tool-renders-the-whole-surface
  (let [r (run {})]
    (is (= :neutral (:category r)))
    (is (str/includes? (:result r) "## Changing the harness"))
    ;; The manual advertises the store-based protocol, not the legacy dir one
    ;; (karamazov-blt.7): apply-cell-edit! refuses a store-mode image now.
    (is (str/includes? (:result r) "samizdat.mutation/propose-cell!"))))

(deftest the-tool-gives-one-entrys-full-docstring
  (let [r (run {:name "samizdat.agent.state/fork-branch"})]
    (is (str/includes? (:result r) "carrying its parent's conversation")
        "the summary")
    (is (str/includes? (:result r) "gate counter")
        "and the docstring, which is where the detail lives")))

(deftest an-unknown-name-says-so-and-points-somewhere
  ;; A dead end that names no alternative is how a branch burns turns guessing.
  (let [r (run {:name "samizdat.nope/gone"})]
    (is (= :mechanics (:category r))
        "not a failure — the branch's line of inquiry is not what was wrong")
    (is (str/includes? (:result r) "not in the manual"))
    (is (str/includes? (:result r) "The tape") "it lists the groups")
    (is (str/includes? (:result r) "doc") "and points at the uncurated escape hatch")))

(deftest every-manual-namespace-is-in-the-binary
  ;; `jolt build` compiles what samizdat.main's require graph reaches; a
  ;; namespace nothing requires is not in the binary, and the manual's
  ;; runtime `require` of it fails there — which failed the WHOLE manual tool.
  ;; Run 756b5572's supervisor spent its pass on "samizdat.agent.tournament/run:
  ;; its namespace could not be loaded". samizdat.capabilities requires every
  ;; namespace the shipped manual names, and samizdat.core requires it.
  (let [ns-form (read-string (slurp "src/samizdat/capabilities.clj"))
        required (set (for [clause (rest ns-form)
                            :when (and (seq? clause) (= :require (first clause)))
                            spec (rest clause)]
                        (if (vector? spec) (first spec) spec)))
        named (set (for [g (clojure.edn/read-string (slurp "resources/manual.edn"))
                         e (:entries g)]
                     (symbol (namespace (:name e)))))
        core-form (read-string (slurp "src/samizdat/core.clj"))]
    (is (empty? (remove required named))
        (str "manual.edn names namespaces samizdat.capabilities does not require: "
             (vec (remove required named))))
    (is (str/includes? (pr-str core-form) "samizdat.capabilities")
        "and samizdat.core requires it, so both `jolt serve` and the binary load them")))

;; --- from the image the role evaluates in (karamazov-xn0h.1) -----------------
;;
;; The curated list is the HARNESS's surface. An implementor's eval runs in
;; the project image, where none of it is loaded, and it was told "call these
;; from eval" all the same. arrodes builds discovery from what is live; here
;; that is the session's own defs and the project's vocabulary.

(defn- project! []
  (let [root (str (fs/create-temp-dir))]
    (fs/create-dirs (str root "/src/shop"))
    (spit (str root "/deps.edn") (pr-str {:paths ["src"]}))
    (spit (str root "/src/shop/orders.clj")
          (str "(ns shop.orders \"Orders.\")\n"
               "(defn place-order\n  \"Places the order.\n\n  Charges the card first.\"\n  [cart]\n  cart)\n"
               "(defmacro with-order [[s id] & body] `(let [~s ~id] ~@body))\n"))
    root))

(defn- run-as [role root args]
  (tools/run-tool {:branch (state/new-branch {:id "B1" :problem "p"})
                   :role role :root root
                   :tool-name "manual"
                   :args args}))

(def ^:private own-defs
  [{:name "helper" :kind "fn" :arglists ["[n]"] :doc "Doubles."}
   {:name "orders" :kind "value" :type "clojure.lang.PersistentVector"}])

(deftest an-implementor-sees-its-session-and-its-project
  (with-redefs [route/session-defs (fn [& _] {:defs own-defs :total 2})]
    (let [r (:result (run-as :implementor (project!) {}))]
      (is (str/includes? r "helper") r)
      (is (str/includes? r "Doubles."))
      (is (str/includes? r "shop.orders") "the project's namespaces")
      (is (not (str/includes? r "samizdat.agent.infer"))
          "and not harness vars its image cannot call"))))

(deftest a-namespace-name-lists-its-vocabulary
  (let [r (:result (run-as :implementor (project!) {:name "shop.orders"}))]
    (is (str/includes? r "place-order"))
    (is (str/includes? r "[cart]"))
    (is (str/includes? r "macro"))))

(deftest a-var-name-gives-its-whole-docstring
  (let [r (:result (run-as :implementor (project!) {:name "shop.orders/place-order"}))]
    (is (str/includes? r "[cart]"))
    (is (str/includes? r "Charges the card first.") "past the first line")))

(deftest a-session-def-is-found-by-its-bare-name
  (with-redefs [route/session-defs (fn [& _] {:defs own-defs :total 2})]
    (let [r (:result (run-as :implementor (project!) {:name "helper"}))]
      (is (str/includes? r "Doubles."))
      (is (str/includes? r "[n]")))))

(deftest an-unknown-project-name-says-what-there-is
  (let [r (run-as :implementor (project!) {:name "shop.nope"})]
    (is (= :mechanics (:category r)))
    (is (str/includes? (:result r) "shop.orders"))))

(deftest a-role-without-eval-still-reads-the-project
  ;; The answerer has manual and no eval: its manual is the vocabulary, and
  ;; asking must not start an image it will never use.
  (with-redefs [route/image-for! (fn [& _] (throw (ex-info "started an image" {})))]
    (let [r (:result (run-as :answerer (project!) {}))]
      (is (str/includes? r "shop.orders")))))

(deftest the-supervisor-also-sees-its-session
  (with-redefs [route/session-defs (fn [& _] {:defs own-defs :total 2})]
    (let [r (:result (run {}))]
      (is (str/includes? r "## The tape") "the curated surface, still")
      (is (str/includes? r "helper") "and what it defined"))))

(deftest callable-cells-are-in-the-manual-when-eval-can-call-them
  ;; karamazov-xn0h.8: what a branch can call from eval includes the cells
  ;; the project kept.
  (with-redefs [samizdat.callable/cells (constantly [{:id :lex/line-count
                                                      :doc "How many lines a file has.\nMore."
                                                      :input [:map [:path :string]]}])
                route/bridge-policy (constantly {:enabled? true :cells? true :tools #{}})]
    (let [r (:result (run-as :implementor (project!) {}))]
      (is (str/includes? r "lex/line-count"))
      (is (str/includes? r "How many lines a file has.")))
    (let [r (:result (run-as :implementor (project!) {:name "lex/line-count"}))]
      (is (str/includes? r "[:map [:path :string]]"))
      (is (str/includes? r "More.") "the whole doc")))
  (testing "not with the bridge off: it could not be called"
    (with-redefs [samizdat.callable/cells (constantly [{:id :lex/line-count :doc "x"}])
                  route/bridge-policy (constantly {:enabled? false})]
      (is (not (str/includes? (:result (run-as :implementor (project!) {})) "lex/line-count"))))))
