# Contextual Rules Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Track implementation in `karamazov-d1pw.6`; no parallel progress ledger.

**Goal:** Resolve bounded userspace rules into the existing turn-boundary context block using symbolic selectors, exact turn generations, and cache-friendly stable/tail placement.

**Architecture:** `src/` supplies loading, symbolic validation, selection, and rendering mechanisms. `resources/context-rules.edn` contains rule data and `gates.edn` contains selector/contribution budgets. Existing directory instruction discovery remains unchanged.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`, section 10.

## Tasks

1. Add failing tests for workspace/path/language, tool/namespace, role/task/workflow, structured diagnostic/event, and assistant-output selectors; conjunction across fields and alternatives within a field; exact generation matching; bounded stable and dynamic contributions; unknown, ambiguous, and shadowed rule rejection.
2. Add the userspace `context-rules` policy role and symbolic compiler/validator. Validate selector names, contribution bounds, duplicates, and statically unreachable rules through `samizdat.symbolic`; wire candidate validation through `policy/reload-and-verify!`.
3. Resolve rules from the parsed tool/result and branch/run data at `steer-step`. Render stable contributions in deterministic order before dynamic tail content, record generation and size evidence, and leave directory instructions unchanged.
4. Document the policy and selectors in prompts/manual, register all tests in both runner locations, run focused and full suites plus `git diff --check`, request independent review, and close `.6` only with fresh green evidence.
