(ns mycelium.cell
  "Cell registry for Mycelium. Cells are registered via `defmethod cell-spec`."
  (:require [mycelium.validation :as v]))

(defmulti cell-spec
  "Multimethod-backed cell registry. Dispatches on cell-id keyword,
   returns the full cell spec map or nil for unknown cells."
  identity)

(defmethod cell-spec :default [_] nil)

(defonce ^:private cell-overrides (atom {}))

;; --- recording a change, so it can be undone alone -----------------------------
;; The registry is one table for the whole process. Undoing an operation by
;; putting back a whole-registry snapshot also undoes whatever ANOTHER thread
;; registered while it ran — a fixture, a reload, a run's own load
;; (karamazov-bmce: callable_test's cells vanished after the eval that
;; registered them). So an operation records the ids IT changes, with what
;; each held before its first change, and an undo puts back only those.

(def ^:dynamic *recorders*
  "The recorders active on this thread, innermost first: atoms of
  {id {:spec s :override o :override? b}}. Nested on purpose — a reload inside
  a mutation is the mutation's change too."
  ())

(defn- touched!
  "Note `id`'s current state in every active recorder that has not seen it."
  [id]
  (doseq [r *recorders*]
    (when-not (contains? @r id)
      (swap! r (fn [m]
                 (if (contains? m id)
                   m
                   (assoc m id {:spec (cell-spec id)
                                :override (get @cell-overrides id)
                                :override? (contains? @cell-overrides id)})))))))

(defn clear-registry!
  "Removes all cells from the registry."
  []
  (doseq [k (keys (dissoc (methods cell-spec) :default))]
    (touched! k)
    (remove-method cell-spec k))
  (reset! cell-overrides {}))


(def ^:dynamic *overlay*
  "nil, or an atom of {id spec} that get-cell answers from before the
  registry and that registrations go INTO instead of the registry, on this
  thread only. How a dry run swaps cells for stubs, and compiles what is
  built from them, without registering any of it where every other run
  would pick it up (karamazov-t25t)."
  nil)

(defn- registered
  "The registry's own spec for `id`, its overrides merged in."
  [id]
  (let [spec (cell-spec id)]
    (when spec
      (if-let [overrides (get @cell-overrides id)]
        (merge spec overrides)
        spec))))

(defn get-cell
  "Returns the cell spec for the given id, or nil if not found."
  [id]
  (if-let [o *overlay*]
    (let [v (get @o id ::none)]
      (if (= ::none v) (registered id) v))
    (registered id)))

(defn- overlay-merge!
  "Under an overlay, `overrides` merged into the overlay's copy of `id`."
  [o id overrides]
  (swap! o assoc id (merge (get-cell id) overrides)))

(defn get-cell!
  "Returns the cell spec for the given id, or throws if not found."
  [id]
  (or (get-cell id)
      (throw (ex-info (str "Cell " id " not found in registry")
                      {:id id}))))

(defn set-cell-schema!
  "Sets or overwrites the schema for an already-registered cell.
   Normalizes lite syntax, validates Malli, then updates.
   Throws if the cell is not found or the schema is invalid.
   opts:
     :malli/registry — local Malli registry used to validate named schemas."
  ([cell-id schema-map]
   (set-cell-schema! cell-id schema-map {}))
  ([cell-id schema-map opts]
   (when-not (get-cell cell-id)
     (throw (ex-info (str "Cell " cell-id " not found in registry")
                     {:id cell-id})))
   (do
     (when (:input schema-map)
       (v/validate-malli-schema! (:input schema-map)
                                 (str cell-id " :input")
                                 opts))
     (when (:output schema-map)
       (v/validate-output-schema! (:output schema-map)
                                  (str cell-id " :output")
                                  opts))
     (if-let [o *overlay*]
       (overlay-merge! o cell-id {:schema schema-map})
       (do (touched! cell-id)
           (swap! cell-overrides update cell-id merge {:schema schema-map})))
     schema-map)))

(defn set-cell-meta!
  "Sets metadata overrides (schema, requires) on a registered cell.
   The manifest calls this to inject metadata into cells that were registered
   without schemas/requires. Schemas are stored verbatim and compiled
   at workflow boundaries.
   Validates schema is well-formed. Throws if the cell is not found.
   opts:
     :malli/registry — local Malli registry used to validate named schemas."
  ([cell-id meta-map]
   (set-cell-meta! cell-id meta-map {}))
  ([cell-id {:keys [schema requires] :as meta-map} opts]
   (when-not (get-cell cell-id)
     (throw (ex-info (str "Cell " cell-id " not found in registry")
                     {:id cell-id})))
   (when schema
     (when (:input schema)
       (v/validate-malli-schema! (:input schema)
                                 (str cell-id " :input")
                                 opts))
     (when (:output schema)
       (v/validate-output-schema! (:output schema)
                                  (str cell-id " :output")
                                  opts)))
   (let [overrides (cond-> {}
                     schema   (assoc :schema schema)
                     requires (assoc :requires requires))]
     (if-let [o *overlay*]
       (overlay-merge! o cell-id overrides)
       (do (touched! cell-id)
           (swap! cell-overrides update cell-id merge overrides))))
   meta-map))

(defn list-cells
  "Returns a seq of all registered cell IDs."
  []
  (keys (dissoc (methods cell-spec) :default)))

(defn register-spec!
  "Register a raw cell spec map under its id, bypassing defcell's validation.
  Used by registry-restore! to put a snapshot back verbatim."
  [id spec]
  (if-let [o *overlay*]
    (swap! o assoc id spec)
    (do (touched! id)
        (.addMethod cell-spec id (constantly spec)))))

(defn remove-cell!
  "Unregister a single cell by id — the surgical counterpart to
  clear-registry!, so a loader can drop its own cells without touching cells
  other code registered in the shared registry."
  [id]
  (if-let [o *overlay*]
    (swap! o assoc id nil)
    (do (touched! id)
        (remove-method cell-spec id)
        (swap! cell-overrides dissoc id))))

(defn registry-snapshot
  "Capture the whole cell registry — every registered spec plus the override
  map — so a load can be rolled back to exactly this state (samizdat's cell
  loader wraps a reload in snapshot/restore, per autolith's transactional
  registry pattern)."
  []
  {:specs (into {} (map (fn [id] [id (cell-spec id)])) (list-cells))
   :overrides @cell-overrides})

(defn registry-restore!
  "Restore the registry to a prior snapshot, surgically: re-register every
  spec the snapshot keeps, then drop what it does not. No id the snapshot
  keeps is ever unobservable mid-restore — a clear-then-repopulate window
  read empty from under another thread (karamazov-bmce; CI 2026-10-21).
  Used when a cell reload fails partway, so a bad cell file never leaves the
  registry half-loaded."
  [{:keys [specs overrides]}]
  (doseq [[id spec] specs]
    (register-spec! id spec))
  (doseq [id (remove (set (keys specs)) (list-cells))]
    (remove-cell! id))
  (reset! cell-overrides (or overrides {})))

(defn recorder
  "A fresh recorder for `recording` and `undo!`."
  []
  (atom {}))

(defn recording
  "Call `f` with `r` recording every registry change made on this thread,
  including inside nested recordings. Returns what `f` returns."
  [r f]
  (binding [*recorders* (cons r *recorders*)]
    (f)))

(defn undo!
  "Put back what recorder `r` saw changed, and only that: each id it recorded
  gets the spec and override it held before the first change. A cell some
  other thread registered meanwhile is left where it is. The undo is itself a
  change to any recording still active around it."
  [r]
  (doseq [[id {:keys [spec override override?]}] @r]
    (touched! id)
    (if spec
      (.addMethod cell-spec id (constantly spec))
      (remove-method cell-spec id))
    (if override?
      (swap! cell-overrides assoc id override)
      (swap! cell-overrides dissoc id))))

(defn trial
  "Call `f`, then undo whatever it changed in the registry, whether it
  returned or threw. For loading something only to look at it."
  [f]
  (let [r (recorder)]
    (try (recording r f)
         (finally (undo! r)))))

;; --- purity -------------------------------------------------------------------
;; A cell declares either `:pure true` or `:effects [:fs :net ...]` — what it
;; touches beyond its data. The declaration is data for three consumers: graph
;; readers (dot, briefs), compile-time validation (which warns about cells that
;; never said), and the mutation-protocol soak (which stubs effectful cells to
;; shadow-run a workflow). Undeclared is tolerated for compatibility but never
;; passes for pure: an unaccounted-for cell is treated as possibly effectful.

(defn effects-declared?
  "Whether the cell said anything about its effects."
  [spec]
  (boolean (or (:pure spec) (seq (:effects spec)))))

(defn pure?
  "Whether the cell declared itself pure. Undeclared is NOT pure."
  [spec]
  (true? (:pure spec)))

(defn effects
  "The declared effect set. Empty for pure and for undeclared cells —
  effects-declared? is how those two are told apart."
  [spec]
  (set (:effects spec)))

(defn validate-effects-declaration!
  "Throws unless the :pure/:effects declaration is well-formed: :pure may only
  be true, :effects only a non-empty collection of keywords, and the two are
  mutually exclusive. Absent is fine."
  [cell-id {:keys [pure] :as spec}]
  (let [fx (:effects spec)]
    (when (and (contains? spec :pure) (not (true? pure)))
      (throw (ex-info (str cell-id ": :pure may only be true — a cell that is"
                           " not pure declares :effects instead")
                      {:id cell-id :pure pure})))
    (when (and (true? pure) (seq fx))
      (throw (ex-info (str cell-id ": declares both :pure true and :effects — "
                           "it cannot be both")
                      {:id cell-id :effects fx})))
    (when (contains? spec :effects)
      (when-not (and (coll? fx) (seq fx) (every? keyword? fx))
        (throw (ex-info (str cell-id ": :effects must be a non-empty collection"
                             " of keywords, e.g. [:fs :net :proc]")
                        {:id cell-id :effects fx}))))))

(defn effects-info
  "Resolves a cell reference to its effects declaration:
  {:pure true}, {:effects #{...}}, or {:undeclared true}.

  Accepts a spec map, a manifest cell-def carrying its own declaration, a
  cell-def carrying an :id to resolve against the registry, or a bare cell-id
  keyword."
  [cell-ref]
  (let [spec (cond
               (keyword? cell-ref) (get-cell cell-ref)
               (and (map? cell-ref) (effects-declared? cell-ref)) cell-ref
               (map? cell-ref) (or (some-> (:id cell-ref) get-cell) cell-ref)
               :else nil)]
    (cond
      (pure? spec) {:pure true}
      (seq (effects spec)) {:effects (effects spec)}
      :else {:undeclared true})))

(defn defcell
  "Registers a cell with less boilerplate than the raw defmethod.
   Eliminates ID duplication — the cell-id is specified once.

   Arity:
     (defcell :ns/id opts handler-fn)

   opts is a map that MUST contain :doc (a non-empty string describing the cell's
   purpose and semantics for LLM consumption). It may also contain :input, :output
   (schema), :requires, and :async?.
   The :input/:output keys become the cell's :schema. Extra keys (:doc, :requires,
   :async?) are lifted to the top-level spec.

   Examples:
     ;; Minimal — no schema
     (defcell :order/compute-tax
       {:doc \"Computes sales tax on the order subtotal\"}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema
     (defcell :order/compute-tax
       {:doc    \"Computes sales tax on the order subtotal\"
        :input  [:map [:subtotal :double]]
        :output [:map [:tax :double]]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))

     ;; With schema + opts
     (defcell :order/compute-tax
       {:doc      \"Computes sales tax using regional tax rate tables\"
        :input    [:map [:subtotal :double]]
        :output   [:map [:tax :double]]
        :requires [:tax-rates]}
       (fn [resources data] {:tax (* (:subtotal data) 0.1)}))"
  [cell-id opts handler-fn]
  (when-not (and (map? opts) (string? (:doc opts)) (seq (:doc opts)))
    (throw (ex-info (str "defcell " cell-id ": opts map with non-empty :doc string is required")
                    {:id cell-id})))
  (validate-effects-declaration! cell-id opts)
  (let [schema-keys #{:input :output}
        ;; :callable and :examples are samizdat's (karamazov-xn0h.8): a
        ;; library cell a branch may call from eval, and the examples its
        ;; proposal is checked against.
        opt-keys    #{:doc :requires :async? :pure :effects :callable :examples}
        raw-schema  (let [s (select-keys opts schema-keys)]
                      (when (seq s) s))
        schema      raw-schema
        extra       (select-keys opts opt-keys)
        spec        (cond-> {:id cell-id :handler handler-fn :doc (:doc extra)}
                      schema (assoc :schema schema)
                      (:requires extra) (assoc :requires (:requires extra))
                      (:async? extra) (assoc :async? (:async? extra))
                      (:pure extra) (assoc :pure true)
                      (:effects extra) (assoc :effects (vec (:effects extra)))
                      (:callable extra) (assoc :callable true)
                      (:examples extra) (assoc :examples (vec (:examples extra))))]
    (register-spec! cell-id spec)
    spec))
