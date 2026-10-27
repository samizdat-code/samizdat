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

(ns samizdat.agent.run-state
  "The in-memory state a run keeps outside its database rows, dropped in ONE
  place when the run ends. Every driver that ends a run — beam/run!,
  resume, workflow/run! — calls `run-ended!` in its finally, so a new piece
  of per-run state is added here once and cannot be forgotten by one of
  them (a serve process otherwise keeps one entry per run it ever ran)."
  (:require [samizdat.agent.context-edit :as context-edit]
            [samizdat.agent.verify :as verify]
            [samizdat.llm.client :as llm]
            [samizdat.progress :as progress]
            [samizdat.sdiff.address :as address]
            [samizdat.security.flow :as flow]))

(defn run-ended!
  "Forget run `run-id`'s seal, its branches' flow labels, the models it
  retired, the file reads its branches made and where its branches last were. Each is best effort: one
  failing must not keep the rest."
  [run-id]
  (doseq [f [verify/unseal-run! flow/forget-run! llm/forget-retired! address/forget-reads!
             progress/forget-run! context-edit/forget-run!]]
    (try (f run-id) (catch Throwable _ nil))))
