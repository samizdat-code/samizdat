# Evidence Knowledge and Diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track work in bead `karamazov-d1pw.4`; Beads is the only durable ledger.

**Goal:** Extend knowledge with durable evidence/premises and locally recomputed status, then add symbolic action contracts and bounded diagnostic probes using existing tools and policy.

**Architecture:** Existing `knowledge` remains the claim and salience store. New evidence/premise tables link claims to file, user, URL, tool, artifact, and resource records. Recall is a pure local projection. `samizdat.symbolic` remains the only fact/rule engine. Contracts and diagnoses are userspace behavior, invoked through ordinary tool authority and existing phase/turn policy.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, sections 7–8. Coordinate with `karamazov-ei6t`; do not replace its broader memory model.

## Global Constraints

- Recall never fetches URLs, runs tools, or spends provider tokens.
- Status is derived, never frozen as a second authoritative copy; salience/confidence/outcome/corroboration/retirement remain independent.
- File evidence uses project-relative paths and exact content revisions; imports revalidate locally.
- Only accepted undisputed evidence rules out a hypothesis; unresolved cases remain open.
- Rules, contracts, hypotheses, probe costs, activation, and wording live in resources, with ordinary userspace validation and approval.
- Register new test namespaces in both runner locations; do not commit or push.

### Task 1: Durable evidence and premise edges

**Files:** `src/samizdat/store/migrations.clj`, new `src/samizdat/store/evidence.clj`, `src/samizdat/store/knowledge.clj`, tests.

1. Add failing tests for all source classes, exact file revision, tool/artifact references, dependent claims, and lineage preservation.
2. Add the next migration and CRUD APIs for evidence, premise links, and stable observations.
3. Run migration/store/knowledge compatibility suites.

### Task 2: Local status, explanation, and change notification

**Files:** evidence store, knowledge tool/resources, prompts/manual, tests.

1. Add failing tests for current, stale, unverified, conflicted, retired, missing external evidence, expiry, and local content mismatch. Prove recall has no provider/tool/network effects.
2. Implement pure status projection and explanation (evidence, premises, dependents, reasons).
3. Surface changed-since-delivered status at the existing safe context boundary; refresh is separately authorized and creates a new observation.
4. Add knowledge resource reads and explanation actions without changing existing retirement semantics.

### Task 3: Symbolic action contracts

**Files:** `src/samizdat/symbolic.clj`, new contract mechanism, userspace contract resources, LSP/verify consumers, tests.

1. Add failing contract examples for argument patterns, required facts, expected effects, structured observations, and requirement refusal before execution.
2. Implement contracts through `samizdat.symbolic`; no second engine. Separate external failure from failure to record an expected observation.
3. Wire shipped `lsp diagnostics` and configured verification commands through ordinary tools.
4. Validate and document contracts through userspace/manual.

### Task 4: Bounded diagnostic cases and probes

**Files:** new diagnosis mechanism/store/resolver, userspace diagnosis resources, ordinary tool dispatch and phase/turn activation, prompts/manual, tests.

1. Add failing tests for known-fact-first checks, bounded hypotheses, information-per-cost probe choice, accepted-vs-disputed evidence, open outcomes, persistence, and wrong-owner access.
2. Implement reusable diagnosis cases and `diagnosis:<id>` resources.
3. Run LSP diagnostics and configured verification probes under a fixed userspace budget; steer only the existing repeated-failure policy to diagnosis.
4. Verify no second repair manifest or forced diagnosis on every failure.

### Task 5: Verification and review

1. Run focused knowledge/LSP/policy/prompt/manual/boundary suites, `jolt test`, and `git diff --check`.
2. Smoke real local LSP diagnostics and configured verification where available.
3. Request independent review, fix Critical/Important findings, rerun verification, and close `karamazov-d1pw.4` with evidence.
