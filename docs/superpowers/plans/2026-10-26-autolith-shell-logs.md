# Retained Command Output Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track completion in bead `karamazov-d1pw.3`, not a parallel TODO ledger.

**Goal:** Capture shell stdout/stderr before launch, retain bounded raw streams, return truthful `shell-log:` references, and expose owner-checked byte windows and literal search through the resource protocol.

**Architecture:** `samizdat.engine.proc` gains an optional streaming capture seam without changing existing callers. Shell policy allocates capture identities and retention before spawn. Durable metadata lives in SQLite, private bytes under the project's `.samizdat` data directory, and a shell-log resolver serves bounded, redacted observations. The existing journal `full_result` path remains unchanged and bounded.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, section 6.

## Global Constraints

- Denied commands never allocate or spawn; every admitted command allocates both stream captures before spawn.
- Timeout and cancellation retain partial bytes and truthful observed counts.
- Retention bounds bytes on write, not only on read; previews never require an unlimited raw result string.
- Exact run/branch ownership, existing exposure checks, and output redaction apply to every resource read and inference view.
- Active captures and undelivered completion evidence cannot be pruned.
- Missing, incomplete, and pruned captures remain distinct.
- Register new test namespaces in both places in `test/samizdat/test_runner.clj`; do not commit or push.

### Task 1: Streaming process captures

**Files:** `src/samizdat/engine/proc.clj`, `test/samizdat/proc_test.clj` (or the existing process suite if one is present), runner registration if added.

**Interfaces:** extend `proc/run` with optional `:capture {:stdout path :stderr path :retain-bytes n}`. Preserve existing `{:exit :out :err}` behavior and return capture metadata with observed/retained byte counts and completeness.

1. Write failing tests for completed, non-zero, timed-out, and interrupted processes; multi-byte UTF-8 split across reads; output larger than retention; no orphan pipe/thread.
2. Implement streaming capture before process start, daemon pump completion, deadline/tree reaping, and bounded file writes. Keep existing non-capture behavior intact.
3. Run the process and shell-policy tests.

### Task 2: Durable capture catalogue and retention

**Files:** `src/samizdat/store/migrations.clj`, new `src/samizdat/store/captures.clj`, `src/samizdat/agent/gates.clj`, `resources/gates.edn`, new capture tests, migration and store tests.

**Interfaces:** create/finish/get/list/prune capture records with id, exact owner, stream paths, lifecycle, observed/retained counts, completeness, delivery state, timestamps.

1. Add failing migration and lifecycle tests, including restart recovery and active/undelivered pruning protection.
2. Add the next migration; never edit an already shipped migration.
3. Implement atomic metadata transitions and policy-driven age/count/capacity pruning. File deletion follows durable state transition and never removes active or undelivered evidence.
4. Run migration, store, and recovery tests.

### Task 3: Shell-log resources and shell integration

**Files:** new `src/samizdat/resource/shell_log.clj`, `src/samizdat/agent/tools/resource.clj`, `src/samizdat/security/policy.clj`, `src/samizdat/agent/tools/shell.clj`, prompts and resource/tool tests.

**Interfaces:** `shell-log:<id>?stream=stdout|stderr` observations; byte `offset`/`limit`; literal UTF-8 `query` with bounded scan/results. Shell results include per-stream status/counts/completeness and resource references.

1. Add failing tests for head/tail preview allocation, separate stdout/stderr references, partial timeout output, byte windows, literal search caps, wrong-owner denial, redaction, and missing/incomplete/pruned distinctions.
2. Implement the resolver and register it in the branch-local resource registry.
3. Integrate capture allocation into the admitted shell path and return bounded previews without changing denial or exit semantics.
4. Run shell, exposure, resource, and boundary suites.

### Task 4: Discovery, compatibility, and verification

**Files:** `resources/manual.edn`, `resources/prompts/resource-tool.md`, `resources/prompts/system-tools.md` if the shell contract is described there, relevant userspace/role metadata if required.

1. Document shell-log operations, preview policy, retention states, and the move from repeated commands to bounded reads/search.
2. Verify existing journal `full_result` and `read_digest {turns}` compatibility tests still pass.
3. Run focused suites, `jolt test`, `git diff --check`, and a real shell smoke covering success and timeout.
4. Request an independent read-only review, fix Critical/Important findings, rerun verification, and close `karamazov-d1pw.3` with evidence.
