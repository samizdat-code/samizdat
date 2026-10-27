# Structural AST Query and Rewrite Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track implementation in `karamazov-d1pw.7`; no parallel progress ledger.

**Goal:** Add optional configured `ast-grep` structural search and revision-gated rewrite through observed workspace resources and staged change sets.

**Architecture:** The backend returns data and never writes. Configuration selects the executable and bounds; the tool uses ordinary shell approval, resource observations, staged proposals, and execution receipts. No new parser dependency.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, section 11.

## Tasks

1. Add fake-backend tests for unavailable configuration, bounded query results with resource revisions, rewrite previews, exact-owner/single-use apply, stale revisions, backend failures, and receipts.
2. Implement the configured process adapter and semantic structural tool actions. Keep executable invocation behind existing approval and retain bounded artifacts through the execution API.
3. Convert rewrite output into workspace change members and apply only through `resource.changes`; never let the backend modify files. Add prompts, roles, gates, manual, and both test-runner registrations.
4. Run fake and real-tool-boundary tests, an opt-in real `ast-grep` check when installed, full suite, `git diff --check`, and independent review before closing `.7`.
