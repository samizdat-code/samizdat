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

(ns samizdat.store.grants
  "Session permission grants: the allow-always the human gives when a command
  asks. Scoped to a run, consulted ahead of the base rules, never able to
  override a hard deny.

  The write side (`grant!`) is a HUMAN-ONLY surface — nothing the model emits
  reaches it. That is property 4 of the security model (docs/RFCS/RFC-003-security-model.md): the
  model has no edge into the grants table. Enforced structurally by never
  exposing grant! through a tool; the model can only ever produce a
  needs-approval result that a person acts on."
  (:require [clojure.string :as str]
            [samizdat.store.db :as db]))

(defn grant!
  "Record a human's allow-always for `pattern` on this run. Idempotent-ish: a
  duplicate pattern is harmless (matching is set-membership, not counting)."
  [conn run-id pattern]
  (db/with-writer
    (db/execute! conn ["INSERT INTO grants (run_id, pattern, created_at)
                        VALUES (?, ?, ?)"
                       run-id pattern (db/now)]))
  pattern)

(def mcp-prefix
  "What starts an MCP tool's grant: `mcp:<server>/<tool>#<digest>`, the
  digest of the tool's definition (samizdat.mcp.client/tool-digest), so the
  grant stops applying when the server changes the tool (karamazov-k7gl.3).
  Kept in the same table as the shell's, and out of what the shell reads."
  "mcp:")

(defn- patterns [conn run-id]
  (mapv :pattern
        (db/fetch conn ["SELECT pattern FROM grants WHERE run_id = ?
                         ORDER BY id" run-id])))

(defn for-run
  "The shell grant patterns active for a run, as the shape the policy engine
  reads: {:grants [pattern ...]}. Read once per decision so a fresh grant is
  seen on the next command without re-plumbing. MCP grants are not shell
  globs and are left out."
  [conn run-id]
  {:grants (into [] (remove #(str/starts-with? % mcp-prefix)) (patterns conn run-id))})

(defn mcp-grants
  "The MCP tool grants active for a run, each `mcp:<server>/<tool>#<digest>`."
  [conn run-id]
  (into [] (filter #(str/starts-with? % mcp-prefix)) (patterns conn run-id)))
