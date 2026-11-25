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

(ns samizdat.pricing
  "What a run's tokens cost in dollars (karamazov-k7gl.8).

  The PRICES ARE DATA: resources/pricing.edn, layered like any settings file
  (samizdat.layers — $SAMIZDAT_PRICING_FILE > .samizdat/pricing.edn > the
  profile's or ~/.config/samizdat/pricing.edn > shipped), with config.edn's
  `:pricing` merged over the lot. Rates are US dollars per million tokens:

    {:models    {\"model-id\" {:input :output :cache-read :cache-write}}
     :providers {\"provider\" {\"model-id\" {...}}}}

  A provider's entry, when it has one, is the price whole; otherwise the
  model's. A rate needs :input and :output; a missing cache rate bills those
  tokens at :input, which is what a provider without a cache discount
  charges.

  NEVER GUESSED. A model with no price is listed under :unpriced and adds
  nothing to the total — and a run where nothing was priced has a nil total,
  unknown rather than free."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [samizdat.layers :as layers]))

(defn shipped
  "The shipped price table, resources/pricing.edn, as EDN."
  []
  (or (some-> (io/resource "pricing.edn") slurp edn/read-string) {}))

(defn table
  "The price table in force: the layered pricing.edn files, with config.edn's
  `:pricing` over them. `opts` may hand in `:shipped` text, `:root` and
  `:global-dir` (samizdat.layers/resolve); `:root` defaults to the config's
  run root."
  ([config] (table config nil))
  ([config opts]
   (let [opts (merge {:root (get-in config [:run :root])
                      :global-dir (layers/global-dir)
                      :shipped (some-> (io/resource "pricing.edn") slurp)}
                     opts)]
     (layers/deep-merge (or (:value (layers/resolve "pricing" opts)) {})
                        (or (:pricing config) {})))))

(defn- rate? [r]
  (and (map? r) (number? (:input r)) (number? (:output r))))

(defn price
  "The rate for `model` as served by `provider` (nil: unknown), or nil when
  the table has none."
  [table provider model]
  (let [r (or (when provider (get-in table [:providers (str provider) (str model)]))
              (get-in table [:models (str model)]))]
    (when (rate? r) r)))

(defn row-usd
  "Dollars for one usage row at `rate`. The prompt splits into cache reads
  (:cache-hit-tokens), cache writes (:cache-write-tokens, when a row carries
  them) and the rest at :input."
  [rate {:keys [prompt-tokens completion-tokens cache-hit-tokens cache-write-tokens]}]
  (let [hit (or cache-hit-tokens 0)
        wrote (or cache-write-tokens 0)
        plain (max 0 (- (or prompt-tokens 0) hit wrote))
        in (:input rate)]
    (/ (+ (* plain in)
          (* hit (or (:cache-read rate) in))
          (* wrote (or (:cache-write rate) in))
          (* (or completion-tokens 0) (:output rate)))
       1e6)))

(defn- model-key [{:keys [provider model]}]
  (if provider (str provider "/" model) (str model)))

(defn cost
  "{:usd :by-model :unpriced} for usage `rows` ({:provider :model
  :prompt-tokens :completion-tokens :cache-hit-tokens ...}, as
  journal/run-usage-by-model gives them). :by-model is keyed
  \"provider/model\" (the model alone when the provider is unknown) and holds
  only priced models; :unpriced names the rest; :usd sums the priced, nil
  when there were rows and none was priced."
  [table rows]
  (let [priced (for [r rows :let [p (price table (:provider r) (:model r))] :when p]
                 [(model-key r) (assoc (select-keys r [:calls :prompt-tokens :completion-tokens
                                                       :cache-hit-tokens :cache-miss-tokens])
                                       :usd (double (row-usd p r)))])
        unpriced (vec (distinct (for [r rows :when (not (price table (:provider r) (:model r)))]
                                  (model-key r))))]
    {:usd (when (or (seq priced) (empty? unpriced))
            (double (reduce + 0.0 (map (comp :usd second) priced))))
     :by-model (into {} priced)
     :unpriced unpriced}))
