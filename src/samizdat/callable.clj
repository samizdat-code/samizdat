;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.callable
  "Callable cells: library cells a branch calls from eval (karamazov-xn0h.8).

  A cell no manifest reaches is a library entry. One marked `:callable` is
  also something a branch can call by name from inside an eval, through the
  bridge — so the cells a project keeps are the vocabulary its runs grow,
  versioned, validated and adopted like every other cell, instead of a defn
  that vanishes with the run.

  WHAT ONE MAY DO: compute over clojure.core in the cell sandbox, and reach
  tools through `tool` — only through it. `tool` calls the runner the caller
  bound, which is the same gated path a turn's tool call takes, so a cell
  inherits the role, phase and approval of the branch that called it and
  gains nothing of its own. Outside a call there is no runner and `tool`
  refuses.

  ITS SCHEMAS ARE ITS OPINIONS. :input is checked before the cell runs and
  :output after, so whoever calls it is held to the shape it promises.

  Mechanism only: which cells are callable is each cell's own declaration,
  and every sentence the model reads is a prompt."
  (:require [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [samizdat.prompt :as prompt]))

(def ^:dynamic *runner*
  "The tool runner of the call in progress: (fn [tool args]) returning the
  result text or throwing the refusal or failure. Bound by `invoke!`."
  nil)

(defn tool
  "Run tool `name` with `args` for the callable cell in progress — the one
  way a cell reaches a tool. Its result text, or a throw."
  [name args]
  (if-let [run *runner*]
    (run (str name) args)
    (throw (ex-info (prompt/render "callable-no-runner" {:tool (str name)})
                    {:callable/tool (str name)}))))

(defn cells
  "Every registered callable cell as `{:id :doc :input :output}`, by id."
  []
  (->> (cell/list-cells)
       (keep (fn [id]
               (let [c (cell/get-cell id)]
                 (when (:callable c)
                   {:id id :doc (:doc c)
                    :input (get-in c [:schema :input])
                    :output (get-in c [:schema :output])}))))
       (sort-by str)
       vec))

(defn- message [e] (or (ex-message e) (str e)))

(defn invoke!
  "Run callable cell `id` on `args` with tools reached through `runner`:
  `{:value v}`, or `{:error message}` when it is not callable, its input or
  output does not fit its schema, or it threw (a refused tool among the
  ways)."
  [id args runner]
  (let [c (cell/get-cell id)]
    (cond
      (not (:callable c))
      {:error (prompt/render "callable-unknown" {:id (str id)
                                                 :callable (map (comp str :id) (cells))})}

      :else
      (if-let [bad (schema/validate-input c args)]
        {:error (prompt/render "callable-schema" {:id (str id) :input true
                                                  :detail (pr-str (:errors bad bad))})}
        (try
          (let [out (binding [*runner* runner] ((:handler c) {} args))]
            (if-let [bad (schema/validate-output c out)]
              {:error (prompt/render "callable-schema" {:id (str id) :output true
                                                        :detail (pr-str (:errors bad bad))})}
              {:value out}))
          (catch Throwable e {:error (message e)}))))))

(defn example-problem
  "Why callable cell `id`'s examples do not hold, or nil — none declared is
  nil too: examples are checked when present (karamazov-xn0h.8). An example
  is `{:in args :out expected}` or `{:in args :check (fn [out] truthy)}`."
  [id runner]
  (let [c (cell/get-cell id)]
    (some (fn [[i {:keys [in out check] :as ex}]]
            (let [r (invoke! id in runner)
                  why (cond
                        (:error r) (:error r)
                        (and (contains? ex :out) (not= out (:value r)))
                        (pr-str {:expected out :got (:value r)})
                        (and check (not (try (check (:value r)) (catch Throwable _ false))))
                        (pr-str {:check-failed-on (:value r)}))]
              (when why
                (prompt/render "callable-example" {:id (str id) :n (inc i)
                                                   :in (pr-str in) :why why}))))
          (map-indexed vector (:examples c)))))
