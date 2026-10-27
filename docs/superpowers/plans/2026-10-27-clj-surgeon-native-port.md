# Native clj-surgeon Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver clj-surgeon's structural Clojure inspection, guarded editing, refactoring, and guidance as native Samizdat capabilities.

**Architecture:** Port pure rewrite-clj/SCI planning code under `samizdat.surgeon`, adapting only namespaces and effect seams. Model tools observe workspace resources and publish complete future sources through Samizdat's exact-owner, revision-gated resource changes; upstream CLI, MCP, direct writes, and process management are not ported.

**Tech Stack:** Clojure on Jolt, rewrite-clj, SCI, clojure.test, Samizdat resource/tool/skill/LSP protocols.

**Spec:** `docs/superpowers/specs/2026-10-27-clj-surgeon-native-port-design.md`

## Global Constraints

- Work on branch `feature/clj-surgeon-native-port`; preserve all unrelated working-tree changes.
- Upstream reference: `/Users/yogthos/src/clj-surgeon` at `affdfe3fd59a63b3fbbb50da16b82509fafea907`; retain MIT notices and add `docs/third-party/clj-surgeon-LICENSE`.
- Use existing rewrite-clj, SCI, JSON, file, process, and resource mechanisms; add no replacement dependency.
- Pure planners accept source maps and return future source maps or refusal data. They never write files.
- All path, role, phase, exposure, flow, ownership, revision, redaction, execution, and journal checks use Samizdat's ordinary tool path.
- Limits and model-facing prose live in userspace. Do not claim multi-file atomicity or semantic completeness the mechanism does not provide.
- Register every new test namespace in both the `:require` list and `namespaces` vector in `test/samizdat/test_runner.clj`.
- Commit after each completed task with no AI attribution. Push and create the PR only after final verification.

## Review Focus

- Comments, metadata, reader macros, and CLJC branches that look similar as text but are not interchangeable: pin exact fingerprints and platform selection in Tasks 1, 4, and 10.
- Duplicate owners, stale source revisions, overlapping edits, and wrong proposal owners: prove whole-call refusal without writes in Tasks 4, 5, and 7.
- SCI forms attempting I/O, host interop, unbounded/lazy results, or excess changed characters: prove refusal before staging in Task 8.
- Large batches and outputs: prove userspace limits refuse the complete request without partial source in Tasks 2 and 7.
- Missing, ambiguous, or stale semantic evidence: prove it remains explicitly unproved and cannot authorize deletion in Task 12.

---

### Task 1: Pure structural inspection kernel

**Files:**
- Create: `src/samizdat/surgeon/inspect.clj`
- Create: `src/samizdat/surgeon/owner_hypotheses.clj`
- Create: `src/samizdat/surgeon/structural_lens.clj`
- Modify: `src/samizdat/surgeon/outline.clj`, `forms.clj`, `cljc/walk.clj` only where upstream fixtures require reconciliation
- Create: `test/samizdat/surgeon_inspect_test.clj`
- Modify: `test/samizdat/test_runner.clj`
- Create: `docs/third-party/clj-surgeon-LICENSE`

**Interfaces:**
- Produces: `(inspect/outline-source [source file]) -> outline map`.
- Produces: `(inspect/forms-source [source file selector]) -> {:forms [...] :file-hash string}` or `{:error-type keyword ...}`.
- Produces: `(inspect/match-source [source {:keys [inside match]}]) -> {:matches [...] :file-hash string}`.
- Selector supports exactly one of `:form`, `:forms`, `:line`, or `:contains`, plus optional `:platform` and `:include-source?`.

- [ ] **Step 1: Write failing pure inspection tests**
  Cover named and batched forms, metadata-only reads, unique line/contains selection, duplicate and missing owner refusal, `_` subtree matching, CLJC platform disambiguation, and exact source hashes. Assert comments, metadata, reader syntax, and strings remain in exact source.

- [ ] **Step 2: Run the test and verify it fails**
  Run `jolt -A:test -e '(require (quote clojure.test) (quote samizdat.surgeon-inspect-test)) (clojure.test/run-tests (quote samizdat.surgeon-inspect-test))'`.
  Expected: missing `samizdat.surgeon.inspect` namespace.

- [ ] **Step 3: Port and adapt the inspection code**
  Port relevant code from upstream `show_form.clj`, `structural_lens.clj`, `owner_hypotheses.clj`, and reader-conditional support. Keep I/O out of the public functions above; use `samizdat.resource.workspace/content-revision` for hashes. Remove CLI-command rendering from the native path. Add the upstream license and source-commit/MIT headers.

- [ ] **Step 4: Run focused and existing Surgeon-dependent tests**
  Re-run Step 2, then run `samizdat.userspace-test` to prove the older cell-edit port still passes. Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon test/samizdat/surgeon_inspect_test.clj test/samizdat/test_runner.clj docs/third-party/clj-surgeon-LICENSE && git commit -m "feat: port structural Clojure inspection kernel"`

### Task 2: Native `inspect_clojure` tool

**Files:**
- Create: `src/samizdat/agent/tools/surgeon_inspect.clj`
- Create: `src/samizdat/surgeon/tool.clj`
- Create: `resources/prompts/surgeon-inspect-tool.md`
- Modify: `src/samizdat/agent/tools.clj`
- Modify: `resources/gates.edn`, `resources/roles.edn`, `resources/wordlists.edn`, `resources/manual.edn`, `resources/prompts/system-tools.md`
- Create: `test/samizdat/surgeon_inspect_tool_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Consumes: Task 1 pure inspection functions and `resource-tool/authorized-observation`.
- Produces: `(tool/observe-files [ctx requests]) -> {relative-file observation}` with one observation per distinct file.
- Produces: `base/run-tool` method for `"inspect_clojure"` accepting `{:requests [...] :expect {:requests n :files n}}`.
- Request operations: `outline`, `forms`, `match`; results are bounded EDN in `:result` and structured data in `:resource`.

- [ ] **Step 1: Write failing real-boundary tool tests**
  Use temporary workspaces and `samizdat.agent.tools/run-tool`. Assert batched reads observe each file once, preserve request order, return SHA-256 revisions, reject wrong aggregate counts, reject path escapes, cap requests/files/forms/source/output, and return no partial source after any failed request.

- [ ] **Step 2: Run tests and verify missing-tool failure**
  Run the new namespace through `jolt -A:test -e ...`. Expected: `No tool named inspect_clojure` or missing namespace.

- [ ] **Step 3: Implement observation, validation, dispatch, and policy wiring**
  Add `:clj-surgeon` gate values for maximum requests, files, forms, matches, source characters, and result characters. Register the tool, read-only roles, retry-safe status, prompt documentation, and manual entries. Keep all refusal wording in the new prompt template.

- [ ] **Step 4: Run tool and policy regression tests**
  Run the new test plus `samizdat.roles-test`, `samizdat.toolerr-test`, and `samizdat.resource-tool-test`. Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/agent/tools/surgeon_inspect.clj src/samizdat/surgeon/tool.clj src/samizdat/agent/tools.clj resources test/samizdat/surgeon_inspect_tool_test.clj test/samizdat/test_runner.clj && git commit -m "feat: add native Clojure inspection tool"`

### Task 3: X-ray reads and bounded analysis

**Files:**
- Create: `src/samizdat/surgeon/edit_dsl.clj`
- Modify: `src/samizdat/surgeon/structural_lens.clj`, `src/samizdat/surgeon/inspect.clj`
- Modify: `src/samizdat/agent/tools/surgeon_inspect.clj`, `resources/prompts/surgeon-inspect-tool.md`
- Modify: `test/samizdat/surgeon_inspect_test.clj`, `test/samizdat/surgeon_inspect_tool_test.clj`

**Interfaces:**
- Produces: `(inspect/xray-source [source {:keys [expression evidence]}]) -> result map`.
- Extends Task 2 requests with `{:operation "xray" :file path :expression string}`.
- SCI analysis returns realized EDN within the configured result limit and never writes.

- [ ] **Step 1: Write failing X-ray tests**
  Cover form/line selection, navigation, initializer, spans, partitions, `expect-count`, pure `analyze`, exact/compact evidence, malformed expressions, I/O and interop refusal, lazy/non-EDN output refusal, and output-limit refusal.

- [ ] **Step 2: Run tests and verify missing X-ray behavior**
  Expected: unknown operation or missing `evaluate-xray`.

- [ ] **Step 3: Port the path evaluator and SCI allowlist**
  Adapt upstream `edit_dsl.clj` and pure structural-lens evaluation. Reconcile the allowlist with `samizdat.sandbox.sci`; do not expose file, process, namespace-loading, mutable-host, class, or interop capabilities.

- [ ] **Step 4: Run all inspection tests**
  Expected: PASS for pure and real tool tests.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon src/samizdat/agent/tools/surgeon_inspect.clj resources/prompts test/samizdat && git commit -m "feat: add structural X-ray inspection"`

### Task 4: Pure exact-edit compiler

**Files:**
- Create: `src/samizdat/surgeon/edit.clj`
- Create: `test/samizdat/surgeon_edit_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces: `(edit/plan-replacements [sources edits]) -> {:future-sources {file source} :edits [...] :file-hashes {file sha}}` or throws `ex-info` with `:surgeon/error`.
- `sources` is `{relative-file original-source}`; each edit has `file`, optional `within.form`, `from`, `to`, and optional positive `matches` defaulting to 1.

- [ ] **Step 1: Write failing exact-edit tests**
  Assert owner-scoped replacement, root-scoped grouped edits, default and explicit cardinalities, whitespace-tolerant structural comparison, preservation of comments/metadata/token spelling, complete-future-file parsing, and refusal of missing/duplicate owners, zero/excess matches, overlapping edits, and malformed replacements. Stale input revisions are covered at the resource boundary in Task 5.

- [ ] **Step 2: Run tests and verify missing compiler failure**

- [ ] **Step 3: Port pure selection and plan construction**
  Adapt upstream structural-lens replacement and intent-transaction scoped compilation. Return future sources only; do not port atomic file writes, receipts, or verification processes.

- [ ] **Step 4: Run tests and verify PASS**

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon/edit.clj test/samizdat/surgeon_edit_test.clj test/samizdat/test_runner.clj && git commit -m "feat: compile guarded structural Clojure edits"`

### Task 5: Native `edit_clojure` with resource proposals

**Files:**
- Create: `src/samizdat/agent/tools/surgeon_edit.clj`
- Create: `resources/prompts/surgeon-edit-tool.md`
- Modify: `src/samizdat/agent/tools.clj` registration and flow observation sections, `resources/roles.edn`, `resources/manual.edn`, `resources/prompts/system-tools.md`
- Create: `test/samizdat/surgeon_edit_tool_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Consumes: `edit/plan-replacements`, Task 2 observation helper, `workspace/stage-workspace`, `resource-tool/stage-proposal!`, and `changes/apply!`.
- Produces: `base/run-tool` method for `"edit_clojure"` accepting `{:edits [...] :preview boolean}`.
- Ordinary calls stage and apply once; preview calls return `:proposal-id` for existing `resource apply`.

- [ ] **Step 1: Write failing end-to-end edit tests**
  Prove exact edits commit and read back with replacement revisions; preview performs no write; proposals are exact-owner and single-use; stale revisions, other owners, role denial, protected paths, and invalid future source do not write. Inject a publication failure and assert compensated or recovery-required status is truthful.

- [ ] **Step 2: Run tests and verify missing-tool failure**

- [ ] **Step 3: Implement the resource-backed adapter**
  Require `write_file` authority as LSP edits do. Stage every member from the same observations and apply through `changes/apply!`; never call `spit` or upstream `file_ops` from the tool. Add result prompts, role access, flow file labels, and documentation.

- [ ] **Step 4: Run edit and resource regression tests**
  Run the new namespace plus `samizdat.resource-changes-test`, `samizdat.resource-tool-test`, and `samizdat.security.flow-test`. Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/agent/tools/surgeon_edit.clj src/samizdat/agent/tools.clj resources test/samizdat/surgeon_edit_tool_test.clj test/samizdat/test_runner.clj && git commit -m "feat: add guarded native Clojure editing"`

### Task 6: Bundled clj-surgeon skill and routing

**Files:**
- Create: `resources/skills/clj-surgeon.md`
- Modify: `src/samizdat/agent/skills.clj`, `resources/prompts/system-tools.md`, `resources/manual.edn`
- Modify: `test/samizdat/skills_test.clj`

**Interfaces:**
- Produces: catalogue entry `clj-surgeon` and `(skills/load-skill "clj-surgeon") -> adapted Markdown body`.

- [ ] **Step 1: Write failing skill tests**
  Assert a single-line meaningful description, foreign-working-directory discovery, loadable body, native tool names, preview/apply guidance, and absence of MCP onboarding/recovery or cclsp instructions.

- [ ] **Step 2: Run tests and verify the skill is absent**

- [ ] **Step 3: Port and adapt the canonical skill**
  Use upstream `.claude/skills/clj-surgeon/SKILL.md` and relevant references, converting folded frontmatter and replacing linked-reference loading with explicit future skill names where needed. Keep ordinary routing in `system-tools.md`.

- [ ] **Step 4: Run skill and prompt tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add resources/skills/clj-surgeon.md resources/prompts/system-tools.md resources/manual.edn src/samizdat/agent/skills.clj test/samizdat/skills_test.clj && git commit -m "docs: add clj-surgeon agent skill"`

### Task 7: Multi-file `apply_clojure_changes`

**Files:**
- Create: `src/samizdat/surgeon/intent_transaction.clj`, `operation_algebra.clj`, `binding_rename.clj`
- Create: `src/samizdat/agent/tools/surgeon_apply.clj`
- Create: `resources/prompts/surgeon-apply-tool.md`
- Modify: tool registration, gates, roles, prompts, and manual
- Create: `test/samizdat/surgeon_transaction_test.clj`, `test/samizdat/surgeon_apply_tool_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces: `(intent/compile-changes [sources spec]) -> {:future-sources ... :edits ... :canonical-effects ...}`.
- Produces: `base/run-tool` method for `"apply_clojure_changes"` accepting one `changes` vector with exact per-change expectations and optional preview.
- Actions: replace, insert-before, insert-after, owner delete, binding rename, and assoc-entry.

- [ ] **Step 1: Write failing compiler and tool tests**
  Port upstream fixtures for each action and add native tests for heterogeneous multi-file changes, duplicate IDs, exact counts, comment-bearing insertion gaps, local-only binding rename, overlapping edits, output limits, one proposal per batch, and no partial write after refusal or injected publication failure.

- [ ] **Step 2: Run tests and verify missing compiler/tool failure**

- [ ] **Step 3: Port the transaction algebra and native adapter**
  Reuse Task 4 future-source planning and Task 5 resource publication. Remove upstream receipt files, direct writes, and verification profiles. Compute canonical effect identity from the frozen input; publication order does not change it.

- [ ] **Step 4: Run transaction and resource tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon src/samizdat/agent/tools/surgeon_apply.clj src/samizdat/agent/tools.clj resources test/samizdat && git commit -m "feat: add multi-file Clojure transactions"`

### Task 8: Bounded `transform_clojure`

**Files:**
- Create: `src/samizdat/surgeon/transform.clj`
- Create: `src/samizdat/agent/tools/surgeon_transform.clj`
- Create: `resources/prompts/surgeon-transform-tool.md`
- Modify: tool registration, gates, roles, prompts, manual, and skill
- Create: `test/samizdat/surgeon_transform_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces: `(transform/plan [source {:keys [file expression expect]}]) -> {:future-source string :matches n :changed-characters n}`.
- Produces: `base/run-tool` method for `"transform_clojure"`; preview is default and commit uses Task 5's resource path.

- [ ] **Step 1: Write failing transform tests**
  Cover pure computed replacement, exact match and changed-character guards, preview default, commit read-back, malformed paths, comments requiring narrower selection, and rejection of I/O, processes, namespace loading, mutable references, host interop, lazy/non-EDN results, and excess changes.

- [ ] **Step 2: Run tests and verify missing transform failure**

- [ ] **Step 3: Implement bounded SCI transformation and adapter**
  Reuse Task 3's evaluator and Task 4's frozen future-source validation. Do not create a second sandbox or direct commit path.

- [ ] **Step 4: Run transform and sandbox regression tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon/transform.clj src/samizdat/agent/tools/surgeon_transform.clj src/samizdat/agent/tools.clj resources test/samizdat && git commit -m "feat: add bounded structural Clojure transforms"`

### Task 9: Form movement and declare cleanup

**Files:**
- Create: `src/samizdat/surgeon/analyze.clj`, `forward_refs.clj`, `fix_declares.clj`
- Modify: `src/samizdat/surgeon/move.clj`
- Modify: `src/samizdat/agent/tools/surgeon_apply.clj` and native operation dispatch
- Create: `test/samizdat/surgeon_move_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces pure `(move/plan-form [source {:keys [form before with-deps?]}]) -> {:future-source ... :plan ...}`.
- Produces pure `(declares/plan-fix [source file]) -> {:future-source ... :summary ...}`; forward-reference evidence is injected, not discovered through a shell in the pure layer.

- [ ] **Step 1: Write failing movement/declare tests**
  Port dependency-closure, cycle, comment attachment, stranded caller, and skip/refusal fixtures. Assert no write occurs for unsupported movement and the existing cell `move` behavior remains compatible.

- [ ] **Step 2: Run tests and verify missing pure planners**

- [ ] **Step 3: Port dependency analysis and adapt movement**
  Keep direct filesystem effects outside the pure functions; adapt existing cell callers explicitly rather than leaving a hidden write path.

- [ ] **Step 4: Run movement and userspace tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon src/samizdat/agent/tools test/samizdat && git commit -m "feat: add dependency-aware Clojure form movement"`

### Task 10: Namespace extraction, rename, and CLJC operations

**Files:**
- Create: `src/samizdat/surgeon/extract.clj`, `extract_header.clj`, `rename.clj`, `quoted_var_refs.clj`, `syntax_var_refs.clj`
- Create: `src/samizdat/surgeon/cljc/analyze.clj`, `merge.clj`, `split.clj`, `require_ops.clj`
- Modify: native operation dispatch, prompts, manual, and skill
- Create: `test/samizdat/surgeon_refactor_test.clj`, `test/samizdat/surgeon_cljc_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces pure planners returning `{:future-sources ... :created-files ... :deleted-files ... :caller-proof ...}` for extraction and namespace rename.
- Produces pure CLJC merge/split/require functions over source strings.
- Native publish converts create/write/delete/rename members to resource operations with revisions checked for every member.

- [ ] **Step 1: Write failing refactor and CLJC tests**
  Port extraction header/minimal/copy-all, caller disposition, visibility, quoted-Var, undo-equivalent compensation, namespace rename, CLJC fixed-point, platform require, and malformed-input fixtures. Assert structural candidates are never labelled semantic-complete and every unknown caller requires an explicit decision.

- [ ] **Step 2: Run tests and verify missing planners**

- [ ] **Step 3: Port planners and resource-member conversion**
  Reuse one frozen source map and one resource change set. Do not port CLI receipt files; test compensation through Samizdat's durable member records.

- [ ] **Step 4: Run refactor, CLJC, and resource tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon src/samizdat/agent/tools resources test/samizdat && git commit -m "feat: add structural Clojure namespace refactors"`

### Task 11: LSP-backed semantic preparation

**Files:**
- Create: `src/samizdat/surgeon/semantic.clj`, `source_anchor.clj`
- Modify: `src/samizdat/agent/tools/surgeon_inspect.clj`, `surgeon_apply.clj`, LSP effect seams as needed
- Modify: prompts, manual, and skill
- Create: `test/samizdat/surgeon_semantic_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces: `(semantic/prepare [ctx {:keys [subject subjects file form intent scope]}]) -> {:basis id :surface [...] :next-call ...}` or typed refusal.
- Produces: apply accepts the returned basis and decisions, resolving retained exact observations without repeating discovery.
- Every surface site carries `:authority`; structural supplements never become `semantic-complete`.

- [ ] **Step 1: Write failing semantic tests with fake LSP evidence**
  Cover exact-source preparation without an index, resolved definition/reference union, quoted-Var supplements, mixed/stale sessions, owner disagreement, ambiguous definitions, expired/wrong-owner bases, keep/replace/delete decisions, and refusal of deletion when caller proof is unavailable.

- [ ] **Step 2: Run tests and verify missing preparation**

- [ ] **Step 3: Implement anchored semantic preparation**
  Reuse Samizdat LSP calls and resource identities. Persist only through existing execution/resource mechanisms; do not add cclsp or a second session registry.

- [ ] **Step 4: Run semantic, LSP, and apply tests**
  Expected: PASS.

- [ ] **Step 5: Commit**
  `git add src/samizdat/surgeon src/samizdat/agent/tools resources test/samizdat && git commit -m "feat: prepare proof-carrying Clojure changes"`

### Task 12: Final integration review, dogfood, and PR

**Files:**
- Modify only files needed by review findings and final documentation
- Review: all branch changes

**Interfaces:**
- Consumes: all preceding public tool and skill contracts.
- Produces: verified branch, pushed commits, and a pull request with no AI attribution.

- [ ] **Step 1: Run complete fresh-process verification**
  Run `jolt test`. Expected: all tests and assertions pass with zero failures/errors. Run focused real-tool dogfood covering inspect -> exact edit -> multi-file preview -> apply -> transform, then the repository's ordinary verification path.

- [ ] **Step 2: Perform a whole-branch code review**
  Check upstream MIT notices, no direct model-triggered writes, no policy constants in core, truthful semantic/revision claims, userspace adoption, skill routing, and preservation of unrelated files. Fix findings test-first and rerun affected suites plus `jolt test`.

- [ ] **Step 3: Inspect the final branch**
  Run `git status --short`, `git diff --stat origin/main...HEAD`, and `git log --oneline origin/main..HEAD`. Confirm only intended files and commits are included.

- [ ] **Step 4: Push and create the PR**
  Push `feature/clj-surgeon-native-port` to origin and create a PR against `main` summarizing the native tools, resource safety, skill, tests, and any explicitly remaining limitations. Do not include AI attribution, model names, session links, or generated-with trailers.
