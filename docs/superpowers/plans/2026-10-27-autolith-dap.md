# Debug Adapter Protocol Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track implementation in `karamazov-d1pw.8`; no parallel progress ledger.

**Goal:** Add optional exact-owner DAP sessions with complete semantic debug operations, retained artifacts, cancellation, cleanup, and truthful continuity.

**Architecture:** Reuse the existing Content-Length framing/request-correlation approach, process/cancellation/JSON facilities, ordinary approval, and execution receipts. The adapter is a stdio client, not a debugger or a new scheduler. Configuration and policy control executables, budgets, and limits.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, section 12.

## Tasks

1. Add fake-adapter tests for framing, correlation, events, launch/attach, breakpoints, continue/pause/step, threads/stack/scopes/variables/eval, deadlines, cancellation, termination, exact ownership, and cleanup.
2. Implement the DAP stdio mechanism and semantic debug tool without raw adapter requests. Require ordinary authorization for process creation and classify non-reattachable sessions as decision-required.
3. Retain bounded debug output/event artifacts as resources and integrate lifecycle completions with execution receipts. Add prompts, roles, gates, manual, and both test-runner registrations.
4. Exercise real fake-adapter tool calls, add an opt-in real adapter smoke test where available, run full suite and `git diff --check`, request independent review, and close `.8` only with fresh green evidence.
