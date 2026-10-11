;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.vocabulary-test
  "The project's own vocabulary — its public functions and macros — read from
  its source and rendered into the run's opening (karamazov-xn0h.2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
            [samizdat.prompt]
            [samizdat.vocabulary :as vocab]))

(def ^:private src
  "(ns shop.orders
  \"Orders and what can happen to them.
  Second line is not shown.\"
  (:require [clojure.string :as str]))

(defmacro with-order
  \"Binds the order under `id` for body.\"
  [[sym id] & body]
  `(let [~sym (fetch ~id)] ~@body))

(defn fetch \"The order for id.\" [id] {:id id})

(defn- secret [x] x)

(def ^:private hidden 1)

(def ^{:private true} also-hidden 2)

(defn total
  \"Sum of line items.\"
  ([order] (total order 0))
  ([order discount] (- (reduce + (:lines order)) discount)))

(def tax-rate 0.2)

(defmulti ship :carrier)
")

(def ^:private policy (gates/threshold :vocabulary))

(def ^:private prompt-render samizdat.prompt/render)

(deftest a-namespace-reads-as-its-public-names
  (let [{:keys [ns doc publics]} (vocab/namespace-entry src policy)]
    (is (= "shop.orders" ns))
    (is (= "Orders and what can happen to them." doc) "first line only")
    (is (= ["with-order" "fetch" "total" "tax-rate" "ship"] (mapv :name publics))
        "private ones are not vocabulary; source order kept")
    (testing "a macro says so — it is the language, not just a function in it"
      (is (= "macro" (:kind (first publics)))))
    (testing "every arity, as written"
      (is (= ["[order]" "[order discount]"]
             (:arglists (first (filter #(= "total" (:name %)) publics))))))
    (is (= "The order for id." (:doc (second publics))))))

(deftest text-with-no-ns-form-is-no-namespace
  (is (nil? (vocab/namespace-entry "(defn f [] 1)" policy)))
  (is (nil? (vocab/namespace-entry "this is not ( clojure" policy))))

(defn- project! []
  (let [root (str (fs/create-temp-dir))]
    (fs/create-dirs (str root "/src/shop"))
    (fs/create-dirs (str root "/test/shop"))
    (spit (str root "/deps.edn") (pr-str {:paths ["src"] :aliases {:test {:extra-paths ["test"]}}}))
    (spit (str root "/src/shop/orders.clj") src)
    (spit (str root "/src/shop/cart.cljc") "(ns shop.cart)\n(defn add [cart item] (conj cart item))\n")
    (spit (str root "/test/shop/orders_test.clj") "(ns shop.orders-test)\n(defn helper [] 1)\n")
    root))

(deftest the-index-is-the-projects-main-paths
  ;; Test namespaces are not the domain language; :paths is, not an alias's
  ;; :extra-paths.
  (let [idx (vocab/index (project!) policy)]
    (is (= ["shop.cart" "shop.orders"] (mapv :ns idx)))))

(deftest a-dev-alias-is-where-project-helpers-live
  ;; Decided for karamazov-xn0h.8: REPL helpers that call project code go in
  ;; the project's dev/ source, so the aliases the policy names count too.
  (let [root (project!)]
    (fs/create-dirs (str root "/dev/shop"))
    (spit (str root "/deps.edn")
          (pr-str {:paths ["src"] :aliases {:dev {:extra-paths ["dev"]}
                                            :test {:extra-paths ["test"]}}}))
    (spit (str root "/dev/shop/repl.clj") "(ns shop.repl)\n(defn seed-orders [n] n)\n")
    (is (= ["shop.cart" "shop.orders" "shop.repl"]
           (mapv :ns (vocab/index root policy))))
    (is (= ["shop.cart" "shop.orders"]
           (mapv :ns (vocab/index root (assoc policy :aliases [])))))))

(deftest the-block-names-the-vocabulary-and-stays-in-budget
  (let [root (project!)
        {:keys [block namespaces]} (vocab/block root policy)]
    (is (= 2 namespaces))
    (is (str/includes? block "shop.orders"))
    (is (str/includes? block "with-order"))
    (is (str/includes? block "[order discount]"))
    (is (not (str/includes? block "secret")))
    (testing "past even the brief tier, whole namespaces drop and the block says how many"
      (let [budget (dec (count (:block (vocab/render (vocab/index root policy)
                                                     (assoc policy :max-chars 100000) :brief))))
            {:keys [block omitted]} (vocab/block root (assoc policy :max-chars budget))]
        (is (= 1 omitted))
        (is (str/includes? block "shop.cart"))
        (is (<= (count block) budget) (count block))))
    (testing "a namespace with nothing to show takes no space"
      (spit (str root "/src/shop/empty.clj") "(ns shop.empty)\n(defn- only-private [] 1)\n")
      (let [b (vocab/block root policy)]
        (is (= 2 (:namespaces b)))
        (is (not (str/includes? (:block b) "shop.empty")))))
    (testing "between the two sizes, every namespace stays and the detail goes"
      (let [names-only (:block (vocab/render (vocab/index root policy)
                                             (assoc policy :max-chars 100000) :brief))
            budget (dec (count block))
            {:keys [block omitted brief?]} (vocab/block root (assoc policy :max-chars budget))]
        (is (< (count names-only) budget) "the fixture leaves room for the brief tier")
        (is (= 0 omitted))
        (is brief?)
        (is (str/includes? block "with-order"))
        (is (not (str/includes? block "[order discount]")))))
    (testing "off is the control arm"
      (is (nil? (vocab/block root (assoc policy :enabled? false)))))
    (testing "a project with no Clojure source has no block"
      (is (nil? (vocab/block (str (fs/create-temp-dir)) policy))))))

(deftest an-unchanged-file-is-not-read-again
  ;; Every run opening builds the block, and on samizdat's own tree reading
  ;; 235 namespaces took 10 s a run: the suite, which opens dozens of runs on
  ;; the repository, went past CI's limit. A file is parsed again only when
  ;; its size or modification time says it changed.
  (let [root (project!)
        parsed (atom [])
        parse vocab/namespace-entry]
    (with-redefs [vocab/namespace-entry (fn [text p] (swap! parsed conj text) (parse text p))]
      (let [first-idx (vocab/index root policy)
            n (count @parsed)]
        (is (pos? n))
        (is (= first-idx (vocab/index root policy)))
        (is (= n (count @parsed)) "nothing changed, nothing was read")
        (spit (str root "/src/shop/cart.clj")
              "(ns shop.cart)\n(defn checkout [cart card] cart)\n(defn refund [cart] cart)\n")
        (is (some #{"refund"} (map :name (:publics (first (vocab/index root policy))))))
        (is (= (inc n) (count @parsed)) "only the edited file")))))

(deftest the-block-is-the-most-namespaces-that-fit
  ;; Fitting the budget searches for how many namespaces fit rather than
  ;; dropping one at a time: 235 namespaces meant some 470 renders.
  (let [entries (vec (for [i (range 60)]
                       {:ns (format "big.ns%02d" i)
                        :publics [{:name (str "f" i) :kind "fn" :arglists ["[x]"]}]}))
        renders (atom 0)
        render prompt-render
        one (count (:block (vocab/render (vec (take 1 entries))
                                         (assoc policy :max-chars 1000000) :brief)))]
    (doseq [budget [(+ one 200) (* 3 one) (* 8 one)]]
      (let [p (assoc policy :max-chars budget)
            {:keys [namespaces block brief?]} (vocab/render entries p)
            size (fn [k] (let [o (- 60 k)]
                           (count (prompt-render "vocabulary"
                                                 {:namespaces (take k entries) :brief brief?
                                                  :omitted (when (pos? o) o)
                                                  :many-omitted (> o 1)}))))]
        (is (<= (count block) budget))
        (is (pos? namespaces))
        (when (< namespaces 60)
          (is (> (size (inc namespaces)) budget)
              (str "one more namespace would have fit at " budget)))))
    (with-redefs [samizdat.prompt/render (fn [& a] (swap! renders inc) (apply render a))]
      (vocab/render entries (assoc policy :max-chars (+ one 200))))
    (is (< @renders 30) (str @renders " renders"))))
