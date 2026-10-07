;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.vocabulary-test
  "The project's own vocabulary — its public functions and macros — read from
  its source and rendered into the run's opening (karamazov-xn0h.2)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [jolt.fs :as fs]
            [samizdat.agent.gates :as gates]
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
