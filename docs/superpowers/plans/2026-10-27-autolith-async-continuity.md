# Durable Async Completion and Continuity Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track implementation in `karamazov-d1pw.5`; no parallel progress ledger.

**Goal:** Add exact-owner durable execution records and receipts, safe-boundary delivery, and explicit continuity for async shell, inference, structural, and debug work.

**Architecture:** Beam remains the scheduler. Durable records are evidence delivered through the existing intervention and safe-boundary path. Parked decomposition migrates to the same protocol rather than gaining another scheduler. Resource references link captures, traces, structural output, and debug artifacts.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, section 9.

## Global Constraints

- Unknown outcomes remain unknown; no mutating spec is re-executed merely because no terminal row exists.
- Revival is explicit, classified, linked to the old execution, and consumes a durable claim before dispatch.
- Delivery reaches only the exact run/branch, at a safe boundary; notify-only completions do not trigger a provider turn.
- Receipts survive resume/compaction and coalesce only within userspace policy.
- Register tests in both runner locations; no commits or pushes.

### Task 1: Execution and receipt store

**Files:** migrations, new execution store, tests.

1. Add failing tests for original specification, exact owner, lineage, lifecycle, artifact refs, delivery receipt, and recovery classification.
2. Implement execution/receipt tables and atomic transitions, including unknown terminal uncertainty and durable revival claims.
3. Verify restart recovery and old/new execution linkage.

### Task 2: Safe-boundary delivery and coalescing

**Files:** agent loop/interventions, execution store, prompts/manual, tests.

1. Add failing tests for exact-owner delivery, busy safe boundary, idle coalescing window, notify-only no-provider behavior, resume and compaction.
2. Implement receipt enqueue/delivery through existing interventions and context blocks; no second scheduler.
3. Preserve resource references and delivery idempotence.

### Task 3: Continuity protocol and async producers

**Files:** execution store, shell/inference producers, decomposition parking, resource tools, tests.

1. Add failing tests for live-owned, reconstructible, safe-restart-with-rationale, decision-required, and unknown mutating effects.
2. Implement explicit inspection/revival actions with durable claim consumption and old/new linkage.
3. Move async shell and inference completion to the shared record; extend parked decomposition to the protocol.
4. Keep structural and DAP stages on the same API for later consumers.

### Task 4: Discovery and verification

1. Document execution resources, receipts, continuity classifications, and operator decisions in prompts/manual.
2. Run focused suites, real async shell/inference smoke, `jolt test`, and `git diff --check`.
3. Request independent review, fix Critical/Important findings, rerun verification, and close `.5` with evidence.
