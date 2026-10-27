# Revisioned Resources and Semantic Edits Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Samizdat's revisioned resource substrate and use it to make LSP semantic edits previewable, stale-safe, and recoverable.

**Architecture:** An authority-neutral resolver protocol produces observations and staged changes. Workspace adapters retain exact snapshots, durable change records make interrupted publication recoverable, and LSP adapters only propose changes. Existing file and tool authority remains the outer boundary.

**Tech Stack:** Jolt/Clojure, existing `jolt.fs`, SQLite/JDBC, hashline, clojure-lsp JSON-RPC, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`

This is the plan for bead `karamazov-d1pw.1`, the first stage of epic
`karamazov-d1pw`. Later children have their own plans after their inputs land.

## Global Constraints

- `src/` is mechanism; decisions, limits, and model-facing prose live in resources.
- Every tool call uses the existing role, phase, approval, flow, exposure,
  redaction, cancellation, and journaling path.
- LSP and AST adapters never write files directly.
- Proposals are exact-owner and single-use. They survive compaction but old
  tokens fail closed after process restart or branch handoff.
- Journal change intent before the first write. Never overwrite a later external
  edit during compensation.
- Preserve the unrelated `.gitignore` edit and pre-existing mutation commit
  `91bd75c9`; do not rewrite or include either in this stage's changes.
- Register new test namespaces in both places in
  `test/samizdat/test_runner.clj`.
- Do not commit or push without a separate explicit request.

## Review Focus

- A second file changes after preview but before apply: no file may be written.
- A third-party edit lands after one member publishes: rollback must not replace it.
- A file URI escapes the workspace or uses an unsupported scheme: refuse before reading.
- LSP ranges contain non-BMP characters or overlapping edits: apply correct
  UTF-16 coordinates and reject ambiguous edits.
- Publication stops after intent or one member: resume exposes recovery-required
  state and rejects the old proposal token.

---

### Task 1: Resolver protocol and observations

**Files:**
- Create: `src/samizdat/resource.clj`
- Create: `test/samizdat/resource_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces: `defprotocol Resolver` with `resolve-resource`, `observe-resource`,
  `read-resource`, and `resource-operations`.
- Produces: `(registry resolvers)`, `(resolve-uri registry uri)`,
  `(observe registry uri ctx)`, `(read-window registry observation ctx opts)`,
  `(operations registry uri ctx)`.
- Observation shape: `{:uri string :resolver keyword :identity string :revision any
  :content string|nil :metadata map}`.

- [ ] **Step 1: Write failing resolver tests**

Add `samizdat.resource-test` with a fake text resolver. Assert that URI scheme
resolution returns identity without authorization, unknown/malformed URIs throw
`ex-info` with `:resource/error :unknown-scheme|:malformed-uri`, observations
retain opaque revisions, bounded reads return `next-offset`, and operations come
from the selected resolver.

- [ ] **Step 2: Run the test and verify it fails**

Run:

```bash
jolt -A:test -e '(require (quote clojure.test) (quote samizdat.resource-test)) (clojure.test/run-tests (quote samizdat.resource-test))'
```

Expected: FAIL because `samizdat.resource` does not exist.

- [ ] **Step 3: Implement the protocol and registry**

Implement one focused namespace. Keep parsing and dispatch free of filesystem,
database, approval, and workflow decisions. Use `ex-info` data for all expected
resolver errors. Register the test namespace in both runner lists.

- [ ] **Step 4: Run resolver tests and existing base tests**

Run the command from Step 2, then the same fresh-process form for
`samizdat.base-test`. Expected: PASS.

### Task 2: Workspace observations and change staging

**Files:**
- Create: `src/samizdat/resource/workspace.clj`
- Create: `test/samizdat/resource_workspace_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Consumes: Task 1 resolver protocol and observation shape.
- Produces: `(workspace-resolver opts)` implementing `Resolver`.
- Produces: `(content-revision text)` returning a SHA-256 hex string.
- Produces: `(stage-workspace resolver ctx observations replacements)` returning
  member maps with original and replacement snapshots.
- `opts` injects file read/exists/publish/permission effects for tests.

- [ ] **Step 1: Write failing workspace tests**

Cover confined reads, declared read roots, write confinement, bounded windows,
identical-content revisions, changed-content revisions, missing files, and
replacement staging. Include a `file:` URI containing `..` and an absolute path;
both must fail before the injected read effect is called.

- [ ] **Step 2: Run tests and verify they fail**

Run the fresh-process test form for `samizdat.resource-workspace-test`.
Expected: FAIL because the workspace resolver is absent.

- [ ] **Step 3: Implement the workspace adapter**

Use `samizdat.agent.files/resolve-read!` for reads and `resolve-under-root` for
writes. Use `java.security.MessageDigest` SHA-256 over UTF-8 for revisions.
Reuse source vetting for replacement content. Do not add publication here;
staging retains values only.

- [ ] **Step 4: Run workspace and file tests**

Run `samizdat.resource-workspace-test` and `samizdat.files-test` in fresh
processes. Expected: PASS.

### Task 3: Durable transactional change sets

**Files:**
- Create: `src/samizdat/resource/changes.clj`
- Create: `src/samizdat/store/resources.clj`
- Modify: `src/samizdat/store/migrations.clj` after `v46`
- Create: `test/samizdat/resource_changes_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces migration `v47`: `resource_changes` and `resource_change_members`.
- Produces store functions `(begin-change! conn owner members)`,
  `(member-result! conn change-id uri status detail)`,
  `(finish-change! conn change-id status)`, `(unfinished-changes conn owner)`.
- Produces `(stage! registry ctx members)`, `(apply! registry ctx proposal)`,
  `(recover! registry ctx change-id)`.
- Proposal: `{:id string :run-id string :branch-id string :members vector}`.
- Result status: `:staged|:stale|:committed|:compensated|:recovery-required`.

- [ ] **Step 1: Write failing transaction tests**

Use two in-memory/file-backed members. Assert all members validate before the
first publish; a stale second member leaves the first untouched; a failure on
the second restores the first; a third-party edit after the first publish makes
compensation return `:recovery-required` without replacing the third-party text;
a second apply is refused; another branch cannot apply; restart invalidates the
proposal token while `unfinished-changes` returns the durable record.

- [ ] **Step 2: Run tests and verify they fail**

Run the fresh-process form for `samizdat.resource-changes-test`. Expected: FAIL
because migration and transaction functions are absent.

- [ ] **Step 3: Implement migration and durable store**

`resource_changes` stores id, run/branch owner, status, timestamps.
`resource_change_members` stores change id, URI, base revision, original and
replacement text, publication revision, status, and error detail. Use existing
`db/with-writer` and `db/now`. Original text is private recovery state, not a
model-facing event payload.

- [ ] **Step 4: Implement stage/apply/recover**

Serialize cooperating workspace mutations with one lock. Journal intent before
publication and member outcomes as they occur. Publish atomically per file and
preserve permissions. Revalidate before every write. Compensate only when the
current revision equals the revision this set installed. Recovery is explicit;
never rerun the entire set automatically.

- [ ] **Step 5: Run transaction and migration tests**

Run `samizdat.resource-changes-test` and `samizdat.store-test` in fresh
processes. Expected: PASS, including migration from `v46` to `v47`.

### Task 4: Resource tool and userspace surface

**Files:**
- Create: `src/samizdat/agent/tools/resource.clj`
- Create: `resources/prompts/resource-tool.md`
- Modify: `src/samizdat/agent/tools.clj`
- Modify: `src/samizdat/capabilities.clj`
- Modify: `resources/prompts/system.md`
- Modify: `resources/prompts/system-tools.md`
- Modify: `resources/userspace.edn`
- Modify: `resources/manual.edn`
- Modify: `resources/gates.edn`
- Create: `test/samizdat/resource_tool_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces tool calls `resource {action "read"|"operations"|"edit"|"apply" ...}`.
- `read` accepts `uri`, `offset`, `limit`; `edit` accepts observed URI/revision
  and replacement operations; `apply` accepts an exact-owner proposal id.
- Produces gates `:resources {:read-chars ... :max-proposal-members ...}`.

- [ ] **Step 1: Write failing tool-contract tests**

Dispatch through `base/run-tool`, not direct helper calls. Assert bounded reads,
unknown schemes, operation discovery, stale edit refusal, proposal ownership,
redaction, and a result envelope containing `:branch`. Assert role write
permission is required for edit/apply.

- [ ] **Step 2: Run tests and verify they fail**

Run `samizdat.resource-tool-test`. Expected: FAIL because the tool is not registered.

- [ ] **Step 3: Implement tool and userspace registration**

Adapt tool arguments to Task 1–3 APIs. Keep all response wording in
`resource-tool.md`; read limits and proposal caps in `gates.edn`. Register the
prompt role, tool namespace, manual entry, and system documentation.

- [ ] **Step 4: Run tool, prompt, and manual tests**

Run `samizdat.resource-tool-test`, `samizdat.prompt-test`, and
`samizdat.manual-test` in fresh processes. Expected: PASS.

### Task 5: Complete LSP WorkspaceEdit proposals

**Files:**
- Create: `src/samizdat/lsp/edit.clj`
- Modify: `src/samizdat/lsp/client.clj`
- Create: `test/samizdat/lsp_edit_test.clj`
- Modify: `test/samizdat/lsp_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces `(workspace-edit->members edit read-observation)` returning staged
  `:create|:write|:delete|:rename` members with URI, original, and replacement.
- Produces `(apply-text-edits text edits)` using LSP UTF-16 positions.
- Produces client calls `(code-actions client path range context)` and
  `(prepare-file-rename client old-path new-path)`.
- Existing `client/edit-files` delegates to the new text-edit mechanism or stays
  as a compatibility wrapper.

- [ ] **Step 1: Write failing normalization tests**

Cover `changes`, `documentChanges`, create/write/delete/rename, multiple edits
per document, versioned documents, overlapping edit rejection, unsupported URI
schemes, and a non-BMP character before an edited range. Assert file operations
are data only and no injected write effect runs.

- [ ] **Step 2: Run tests and verify they fail**

Run `samizdat.lsp-edit-test`. Expected: FAIL because normalization is absent.

- [ ] **Step 3: Implement normalization and client requests**

Translate UTF-16 code-unit offsets to Jolt string indexes rather than treating
`character` as a native index. Sort non-overlapping edits by descending start.
Normalize every file operation into Task 3 member data. Add only the LSP requests
needed for code actions and will-rename.

- [ ] **Step 4: Run LSP tests**

Run `samizdat.lsp-edit-test`, `samizdat.lsp-test`, and
`samizdat.lsp-cancel-test`. Expected: PASS.

### Task 6: Transactional semantic LSP tools and end-to-end verification

**Files:**
- Modify: `src/samizdat/agent/tools/lsp.clj`
- Modify: `resources/prompts/lsp-tool.md`
- Modify: `resources/manual.edn`
- Modify: `test/samizdat/lsp_test.clj`

**Interfaces:**
- Extends `lsp` with `code_action` and `move`; existing `rename` remains accepted.
- `preview: true` returns a proposal id; omitted/false applies through the same
  transactional path. `resource apply` consumes previews.
- All operations synchronize changed document contents through `client/changed!`
  only after successful publication.

- [ ] **Step 1: Write failing semantic-tool regressions**

Replace the old partial-write expectation. A fake two-file rename with a stale
second file must leave both original. Preview/apply works once. `code_action`
and `move` use the same member path. Reviewer roles remain read-only. Existing
definition/references/hover/diagnostics behavior is unchanged.

- [ ] **Step 2: Run tests and verify the old behavior fails**

Run `samizdat.lsp-test`. Expected: the new stale/transaction assertions FAIL
against sequential `write_file` publication.

- [ ] **Step 3: Implement the LSP consumer**

Resolve resources and observations, obtain the `WorkspaceEdit`, normalize to
members, stage/apply through Tasks 3–4, and render outcomes through
`lsp-tool.md`. Remove sequential per-file tool dispatch from rename. Update the
manual and usage text to describe preview and transactional apply.

- [ ] **Step 4: Run focused suites**

Run `samizdat.lsp-test`, `samizdat.resource-changes-test`,
`samizdat.resource-tool-test`, `samizdat.prompt-test`, and
`samizdat.base-test`. Expected: PASS.

- [ ] **Step 5: Exercise real clojure-lsp**

When `clojure-lsp` is installed, run the existing real-server fixture plus a
two-file rename through `base/run-tool`; verify both file contents and subsequent
diagnostics. If it is unavailable, report that exact limitation rather than
claiming real-server verification.

- [ ] **Step 6: Run final checks and record the stage**

Run `jolt test` on a quiescent tree and `git diff --check`. Review the complete
diff and unrelated-file preservation. Update `karamazov-d1pw.1` notes with test
evidence and any external-probe limitation; close it only if all required checks
pass. Do not commit without explicit permission.
