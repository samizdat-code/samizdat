---
name: clj-refactor
description: Use when the user asks for a mechanism/policy review or refactoring opportunities in Clojure or Jolt code
---

# Review mechanism and policy

Mechanism is stable, context-free, reusable behavior. Policy is the current
opinionated choice: domain rules, thresholds, defaults, and workflow
decisions. The distinction lies in how code is written, not what it computes.

Review only unless the user also requested edits. Preserve behavior and keep
every finding grounded in an actual form and its callers.

## Inspect the boundary

Ask of each candidate:

- Are dependencies and choices explicit parameters, or hidden in globals and defaults?
- Is this a stable technical operation or a current project decision?
- Would reuse call the same operation with different data, or require copying and changing it?
- Which tests pin the behavior that a structural change must preserve?

For samizdat, apply the existing architecture rather than inventing a new
one: general mechanisms belong in `src/` with effects injected; workflow
decisions belong in runtime cells and manifests; thresholds and business
values belong in policy EDN. A capability with no manifest consumer may be a
library entry, but a project decision embedded in core mechanism is a leak.

## Do not invent reuse

Extract a shared mechanism only with evidence from at least two distinct
current use cases. “Could be reused someday” is not a use case. With one
caller, it can still be useful to make a hard-coded choice a parameter or
move it to policy, but do not create a shared abstraction or utility library.

## Report shape

For each examined file or module, report:

1. **Mixed code** — the exact function, the mechanism and policy it combines, and the boundary between them.
2. **Extraction candidates** — the two or more concrete use cases supporting each candidate; otherwise state that there are none.
3. **Misplaced policy** — the hidden decision and the runtime policy or caller that should own it.
4. **Proposed structure** — a concise before/after showing parameter flow and effect injection, plus the tests that must stay green. No speculative features or behavior changes.

Example with two callers needing the same retry control flow:

```clojure
;; Before: retry loop embeds 3 attempts, 250 ms, and status 503.
;; After: src mechanism accepts attempts, delay-ms, retry?, and sleep!;
;; policy EDN supplies {:attempts 3 :delay-ms 250 :retry-statuses #{503}};
;; each caller supplies its own operation and effect implementations.
```

Do not use this example as evidence for extraction in a codebase with only
one retry caller.
