;; samizdat - a self-hosting agentic harness
;; SPDX-License-Identifier: GPL-3.0-or-later

(ns samizdat.agent.tools.propose
  "The `propose_cell` tool: a branch offers a callable cell to the project's
  workflow, and the supervisor reviews it (karamazov-xn0h.8).

  A branch that found a helper worth keeping does not write it into the
  workflow. It proposes it: the text is checked here the way a save would
  check it — it reads, its marks are earned, it loads in a context of its
  own, every cell in it is callable, and the examples it declares hold — and
  then waits in the supervisor's offers (userspace/propose!), where `adopt`
  takes or declines it. Nothing runs, and nothing is written, until then."
  (:require [clojure.string :as str]
            [mycelium.cell :as cell]
            [samizdat.agent.source :as source]
            [samizdat.agent.tools.base :as base]
            [samizdat.agent.tools.repl :as repl-tool]
            [samizdat.callable :as callable]
            [samizdat.cells :as cells]
            [samizdat.mutation :as mutation]
            [samizdat.prompt :as prompt]
            [samizdat.userspace :as userspace]))

(defn- refuse [branch m]
  (base/malformed branch (prompt/render "propose-cell" m)))

(defn- trial
  "Why `body`'s cells cannot be proposed once loaded, or nil: they do not
  load, one is not callable, or an example does not hold. Loaded in a
  context and registry of its own, restored after, so checking registers
  nothing."
  [ctx body]
  (let [snapshot (cell/registry-snapshot)]
    (try
      (cells/eval-candidate! body)
      (let [ids (cells/defcell-ids body)
            not-callable (remove #(:callable (cell/get-cell %)) ids)]
        (cond
          (empty? ids) {:none true}
          (seq not-callable) {:not-callable (str/join ", " (map str not-callable))}
          :else (some #(some->> (callable/example-problem % (repl-tool/tool-runner ctx))
                                (hash-map :example))
                      ids)))
      (catch Throwable e {:load (or (ex-message e) (str e))})
      (finally (cell/registry-restore! snapshot)))))

(defmethod base/run-tool "propose_cell" [{:keys [branch] :as ctx}]
  (if-let [m (base/missing ctx :name :body :rationale)]
    (base/malformed branch m)
    (let [nm (str/trim (str (base/arg ctx :name)))
          why (str/trim (str (base/arg ctx :rationale)))
          {:keys [code problem note]} (source/vet (str (base/arg ctx :body)) {:whole? true})]
      (cond
        problem (base/malformed branch note)
        :else
        (if-let [unearned (mutation/unearned-marks code)]
          (base/malformed branch unearned)
          (if-let [t (trial ctx code)]
            (refuse branch t)
            (let [r (userspace/propose! :cell nm code why (:id branch))]
              (cond
                (:unbound r) (refuse branch {:unbound true})
                (:problem r) (refuse branch {:load (str (get-in r [:problem :message]))})
                :else (base/ok branch (prompt/render "propose-cell"
                                                     {:proposed true :name nm
                                                      :repaired note}))))))))))
