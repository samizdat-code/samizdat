# Nodecode Review Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add recoverable userspace saves, opt-in deterministic context eviction with searchable recall, covered-prefix render hydration, and bounded stream continuation/slow-start failover, with tests and a reviewed PR.

**Architecture:** Keep Samizdat's immutable tape, journal, provider adapters, and project-userspace split. Add small mechanisms in `src/`, select behavior through existing config/policy files, and leave summary compaction and ordinary provider retry as the defaults. Stream recovery stays within the existing `llm.client` cancellation/fallback ladder; no new dependencies or thread model.

**Tech Stack:** Clojure on jolt, SQLite, `ebb` cancellation, existing `clojure.test` suites, GitHub CLI.

**Spec:** This file's Design Contract below is the approved design. The review source is `/Users/yogthos/src/nodecode`, especially its deterministic context projection, covered-prefix hydration, and stream continuation; only the behavior described here is in scope.

## Design Contract

### Intent and boundaries

- Make file-backed userspace history and live files agree after every failed save/revert, including role-map edits.
- Offer deterministic eviction as `:compaction :strategy :evict`; keep `:summary` as the shipped default. Eviction uses no model call and leaves full journal records recoverable by turn or bounded literal search.
- Cache only semantically stable rendered prefixes. Every result must equal the uncached `llm.message/compact` result exactly; object identity reuse is an optimization, never a semantic change.
- Continue only cut streams that produced visible answer text, after ordinary retries are exhausted, with a provider-configured attempt bound. EOF before `[DONE]` is a cut, not a successful short answer. Preserve partial output, usage, and monotonic UI deltas.
- Slow-start failover is opt-in through `:llm :slow-start-ms`; it uses the existing fallback list and `cancel/with-deadline`. Cancellation never starts another candidate.
- No arbitrary core self-mutation, no new tool family, no replacement of `fetch_turn`, no removal of summary mode, no generic rewrite of provider fallback.

### Exact policies and interfaces

- `userspace/save!` and `userspace/revert!`: write the live file first and append the DB version second; on any failure restore the previous file and role map. Store-only behavior is unchanged. Recovery after a process crash remains the existing file-edit recording path.
- `:compaction :strategy` accepts `:summary` (default) or `:evict`. Every eviction summary carries cumulative turn references, including references from earlier eviction messages, so repeated folds never lose the way back.
- `journal/search-turns [conn run-id branch-id query {:keys [limit before]}]` returns newest-first bounded `{:turn :tool_name :preview}` rows. Search terms are whitespace-separated literals ANDed with SQLite `instr(lower(...), lower(?))`; no LIKE wildcards. Search covers `assistant_text`, `args`, `result`, and `tool_name`, excludes `reasoning_text` exactly as `fetch_turn` does, and previews nonblank `result` first, else visible assistant text.
- `fetch_turn({query, branch?, limit?, before?})` searches when `:turn` is absent; existing exact-turn behavior is unchanged. `:journal-recall` in `gates.edn` supplies `{:limit 8 :preview-chars 220 :max-limit 60}`.
- `llm.message/compaction-plan` exposes the current pure compaction computation with source, normalized options, rendered messages, and rewrite indices. `agent.hydration/compact` consumes an optional prior entry and returns `{:messages ... :entry ...}`; `infer/render` owns one cache entry per tape id and `infer/clear-render-cache!` resets it.
- `:llm :continuation` defaults to `{:enabled? true :min-chars 1000 :max-attempts 1}` and can be overridden per provider. Continuation is same-provider only and occurs before fallback retirement.
- `:llm :slow-start-ms` is nil by default. When positive and fallbacks exist, a primary exceeding that deadline is retired and the next candidate starts through the existing ladder.
- New model-facing prose lives in `resources/prompts/{eviction-summary,fetch-turn-results,stream-continuation}.md`, registered in `resources/userspace.edn`; discovery lives in `resources/manual.edn` and `resources/prompts/system-tools.md`.

## Global Constraints

- `src/` is mechanism; project behavior and all model-facing prose remain in `resources/` and are runtime-editable.
- Preserve immutable tapes and byte-for-byte uncached rendering semantics.
- Preserve cancellation: no retry, continuation, or fallback after a control signal.
- No new library or native dependency.
- New tests go in existing namespaces; no test-runner additions are needed.
- No AI attribution in commits, PR text, beads, or comments. Commit only the requested branch work; leave unrelated untracked files alone.

## Review Focus

- File-write and DB-write failures in either order, including a newly added role: previous files and `userspace.edn` must both be restored (Task 1 tests).
- Literal search containing `%`, `_`, quotes, and huge results: no wildcard expansion and no unbounded model output (Task 2 tests).
- Appended history versus edited history, changed options, and changed turn records: only a proven-stable prefix may be reused (Task 3 tests).
- EOF without `[DONE]`, empty/reasoning-only output, partial fences, and repeated cuts: continuation is bounded and never accepted as an empty success (Task 4 tests).
- Cancel during slow start or continuation, and usage accounting across resumed responses: no extra candidate and no lost/double-counted usage (Task 4 tests).

---

### Task 1: Failure-atomic userspace files and versions

**Files:**
- Modify: `src/samizdat/userspace.clj:1214-1253`
- Test: `test/samizdat/userspace_files_test.clj`
- Test: `test/samizdat/mutation_test.clj`

**Interfaces:**
- Consumes: existing `store/save!`, `store/revert!`, `write-file!`, and role-map helpers.
- Produces: `save!` and `revert!` retain their public signatures and return values; failure restores prior file/map state and rethrows.

- [ ] **Step 1: Write failing regression tests**
  - Simulate `store/save!` throwing after a live file write; assert the old file returns and no new version exists.
  - Simulate `write-file!` throwing; assert the original store latest remains, covering the reproduced `stored-candidate? true / file-candidate? false` case.
  - Save a previously unmapped role and force failure; assert both its new file and the prior role-map text are restored.
  - Cover `revert!` and one `propose-cell!` failure so a rejected candidate neither stays live nor enters history.

- [ ] **Step 2: Run the focused tests and confirm they fail**
  Run: `jolt -A:test -e '(require (quote clojure.test) (quote samizdat.userspace-files-test) (quote samizdat.mutation-test)) (clojure.test/run-tests (quote samizdat.userspace-files-test) (quote samizdat.mutation-test))'`
  Expected: new assertions fail on the DB-first save ordering and incomplete rollback.

- [ ] **Step 3: Implement coordinated save/revert recovery**
  Capture prior target-file and role-map contents, write the live file before the DB append, and on any exception restore both (deleting a newly created file where appropriate). Keep store-only saves unchanged and restore registry state through the existing `propose-cell!` failure path. Update stale save/revert comments to describe the ordering and crash recovery.

- [ ] **Step 4: Re-run the focused tests**
  Run the Task 1 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit**
  `git add src/samizdat/userspace.clj test/samizdat/userspace_files_test.clj test/samizdat/mutation_test.clj && git commit -m "fix: make userspace saves failure atomic"`

### Task 2: Deterministic eviction and bounded journal recall

**Files:**
- Modify: `src/samizdat/agent/compaction.clj`
- Modify: `src/samizdat/store/journal.clj`
- Modify: `src/samizdat/agent/tools/journal.clj`
- Modify: `resources/cells/compaction.clj`
- Modify: `resources/gates.edn`
- Modify: `resources/userspace.edn`
- Modify: `resources/prompts/system-tools.md`
- Modify: `resources/manual.edn`
- Create: `resources/prompts/eviction-summary.md`
- Create: `resources/prompts/fetch-turn-results.md`
- Test: `test/samizdat/compaction_test.clj`
- Test: `test/samizdat/agent_test.clj`
- Test: `test/samizdat/store_test.clj`

**Interfaces:**
- Produces: `:compaction :strategy :evict`; `journal/search-turns` and the `fetch_turn` query shape described in the Design Contract.
- Preserves: summary folds, distillation, exact `fetch_turn({turn})`, pinned/protected messages, and fold-role behavior.

- [ ] **Step 1: Write failing eviction/search tests**
  - An `:evict` fold calls no summarizer, produces turn-addressed text, preserves pinned messages, and leaves the full journal recoverable; `:summary` still calls the old path.
  - A second eviction includes both the earlier eviction's references and the newly folded turns, so no `fetch_turn` pointer disappears across repeated folds.
  - Search returns only own-branch literal matches over `assistant_text`, `args`, `result`, and `tool_name`, never `reasoning_text`, newest first and bounded by limit/before; `%`, `_`, and quotes are literals. Result previews prefer nonblank results, otherwise visible assistant text, are capped, and tell the model how to open full turns.
  - Exact-turn behavior and cross-branch flow propagation remain unchanged.

- [ ] **Step 2: Run focused tests and confirm they fail**
  Run: `jolt -A:test -e '(require (quote clojure.test) (quote samizdat.compaction-test) (quote samizdat.agent-test) (quote samizdat.store-test)) (clojure.test/run-tests (quote samizdat.compaction-test) (quote samizdat.agent-test) (quote samizdat.store-test))'`
  Expected: missing strategy/search failures.

- [ ] **Step 3: Implement deterministic eviction and literal journal search**
  Derive turn ranges with existing pure compaction helpers, render `eviction-summary`, and bypass summarization/validation/distillation only for `:evict`. Add the bounded SQL search and `fetch_turn` query path, with prompts, policy, manual, and role-map registration.

- [ ] **Step 4: Re-run focused tests**
  Run the Task 2 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit**
  `git add src/samizdat/agent/compaction.clj src/samizdat/store/journal.clj src/samizdat/agent/tools/journal.clj resources/cells/compaction.clj resources/gates.edn resources/userspace.edn resources/prompts/system-tools.md resources/manual.edn resources/prompts/eviction-summary.md resources/prompts/fetch-turn-results.md test/samizdat/compaction_test.clj test/samizdat/agent_test.clj test/samizdat/store_test.clj && git commit -m "feat: add deterministic context eviction and recall"`

### Task 3: Covered-prefix render hydration

**Files:**
- Modify: `src/samizdat/llm/message.clj:226-318`
- Modify: `src/samizdat/agent/infer.clj:125-138,355-462`
- Create: `src/samizdat/agent/hydration.clj`
- Test: `test/samizdat/messages_test.clj`
- Test: `test/samizdat/infer_test.clj`

**Interfaces:**
- `llm.message/compaction-plan`: pure current-semantics plan containing source, normalized options, rendered messages, and rewrite indices.
- `agent.hydration/compact`: `[previous messages turns options] -> {:messages ... :entry ...}` as described above.
- `infer/render`: same wire result as before; `infer/clear-render-cache!` clears process-local memoization.

- [ ] **Step 1: Write failing hydration tests**
  - Cached and uncached renders are equal for append, edit, option change, and changed-turn cases.
  - An unchanged prefix before the first current rewrite reuses identical message objects; edits/options/turn changes never reuse invalid entries.
  - Repeated `infer/render` and `complete-fn` calls do not recompute the same compaction; the tape and request digest are unchanged.

- [ ] **Step 2: Run focused tests and confirm they fail**
  Run: `jolt -A:test -e '(require (quote clojure.test) (quote samizdat.messages-test) (quote samizdat.infer-test)) (clojure.test/run-tests (quote samizdat.messages-test) (quote samizdat.infer-test))'`
  Expected: missing plan/cache failures.

- [ ] **Step 3: Extract the pure plan and add covered-prefix reuse**
  Preserve `message/compact` as the compatibility wrapper. Reuse a previous rendered prefix only while source/turn prefixes and normalized options match and it ends before the first current rewrite. Compute `complete-fn`'s render once for fingerprint and request.

- [ ] **Step 4: Re-run focused tests**
  Run the Task 3 command. Expected: all selected tests pass and cache/uncached outputs are identical.

- [ ] **Step 5: Commit**
  `git add src/samizdat/llm/message.clj src/samizdat/agent/hydration.clj src/samizdat/agent/infer.clj test/samizdat/messages_test.clj test/samizdat/infer_test.clj && git commit -m "perf: hydrate stable context prefixes"`

### Task 4: Cut-stream continuation and slow-start failover

**Files:**
- Modify: `src/samizdat/llm/stream.clj`
- Modify: `src/samizdat/llm/client.clj`
- Modify: `src/samizdat/config.clj`
- Modify: `src/samizdat/agent/infer.clj`
- Modify: `resources/userspace.edn`
- Modify: `resources/manual.edn`
- Create: `resources/prompts/stream-continuation.md`
- Modify: `docs/RFCS/RFC-005-provider-layer.md`
- Test: `test/samizdat/llm_stream_test.clj`
- Test: `test/samizdat/llm_test.clj`
- Test: `test/samizdat/llm_fallback_test.clj`
- Test: `test/samizdat/infer_test.clj`

**Interfaces:**
- Stream exceptions carry `:partial-body` when output exists; client exceptions carry parsed `:partial-response`.
- `chat-one` merges bounded continuations, preserving content, finish/model fields, elapsed time, and summed numeric usage.
- `chat` honors optional `:slow-start-ms` before retiring a candidate; existing fallback signatures remain unchanged.

- [ ] **Step 1: Write failing recovery tests**
  - An SSE stream closing before `[DONE]` throws with partial JSON; a normal `[DONE]` still succeeds.
  - A cut after visible text sends original history + partial assistant text + the resource nudge, merges the result and usage, and stops at `:max-attempts`; reasoning-only/blank output, disabled policy, and cancellation do not continue.
  - A primary exceeding `:slow-start-ms` yields to the next configured fallback; cancellation starts no fallback.
  - Delta offsets remain monotonic across retry/continuation.

- [ ] **Step 2: Run focused tests and confirm they fail**
  Run: `jolt -A:test -e '(require (quote clojure.test) (quote samizdat.llm-stream-test) (quote samizdat.llm-test) (quote samizdat.llm-fallback-test) (quote samizdat.infer-test)) (clojure.test/run-tests (quote samizdat.llm-stream-test) (quote samizdat.llm-test) (quote samizdat.llm-fallback-test) (quote samizdat.infer-test))'`
  Expected: missing partial/continuation/slow-start failures.

- [ ] **Step 3: Implement partial capture and bounded recovery**
  Preserve accumulated stream JSON on cut, parse it at the adapter boundary, and continue only eligible visible text after normal retries. Keep continuation same-provider and bounded, merge usage, create one delta publisher per `complete-fn` call, and implement slow start with `cancel/with-deadline` plus the existing candidate ladder. Register prompt/manual/config documentation and update RFC-005.

- [ ] **Step 4: Re-run focused tests**
  Run the Task 4 command. Expected: all selected tests pass.

- [ ] **Step 5: Commit**
  `git add src/samizdat/llm/stream.clj src/samizdat/llm/client.clj src/samizdat/config.clj src/samizdat/agent/infer.clj resources/userspace.edn resources/manual.edn resources/prompts/stream-continuation.md docs/RFCS/RFC-005-provider-layer.md test/samizdat/llm_stream_test.clj test/samizdat/llm_test.clj test/samizdat/llm_fallback_test.clj test/samizdat/infer_test.clj && git commit -m "feat: recover interrupted model streams"`

### Task 5: Design record, full verification, review, and PR

**Files:**
- Create: `docs/superpowers/specs/2026-10-25-nodecode-review-improvements-design.md`
- Create: `docs/superpowers/plans/2026-10-25-nodecode-review-improvements.md`
- Modify: `docs/RFCS/RFC-004-tape-and-inference.md`

**Interfaces:**
- Deliver the approved design, implementation plan, verified branch, and PR covering all four review beads: `karamazov-qgg9`, `karamazov-3h4c`, `karamazov-243f`, `karamazov-cqby`.

- [ ] **Step 1: Record the approved design and update context RFC**
  Save the design/spec and this plan, and document deterministic eviction, hydration invalidation, and compatibility with existing compaction in RFC-004. Close no bead until its verification is green.

- [ ] **Step 2: Run the complete suite**
  Run: `jolt test`
  Expected: exit 0 with 0 failures/errors. The baseline is being established first; any pre-existing failure will be identified before edits and reported rather than attributed to this branch.

- [ ] **Step 3: Review the complete branch**
  Run `git diff --check main...HEAD` and inspect `git diff --stat main...HEAD` plus the full diff. Use the requesting-code-review workflow for independent review, then address every confirmed finding with focused tests.

- [ ] **Step 4: Verify final deliverable and close reviewed beads**
  Re-run `jolt test` after review fixes, `git diff --check main...HEAD`, and the save-failure reproduction (now expecting no candidate in either store or file). Close `karamazov-qgg9`, `karamazov-3h4c`, `karamazov-243f`, and `karamazov-cqby` with reasons naming the implemented behavior and verification.

- [ ] **Step 5: Commit documentation, push, and open the PR**
  `git add docs && git commit -m "docs: record context recovery design"`
  `git push -u origin nodecode-review-improvements`
  Open with `gh pr create --base main --head nodecode-review-improvements`, summarizing the four changes, opt-in policies, test evidence, and review outcome. Do not include attribution trailers.
