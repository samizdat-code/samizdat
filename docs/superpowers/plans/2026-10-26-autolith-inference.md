# Bounded Recursive Inference Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add private, contracted inference frames and maps with shared budgets, traces, and an external-context root mode.

**Architecture:** Inference frames generalize `read_digest`: explicit immutable views, private conversations, and one reservation tree across nested calls. Journal-backed traces and content-addressed objects are resource resolvers; the existing project image and eval bridge provide root execution without another runtime.

**Tech Stack:** Jolt/Clojure, existing provider adapters, Malli, SQLite/JDBC, ebb cancellation, revisioned resources, project-image eval bridge.

**Spec:** `docs/superpowers/specs/2026-10-26-autolith-adoption-design.md`

This implements bead `karamazov-d1pw.2` after the closed resource stage
`karamazov-d1pw.1`.

## Global Constraints

- Parent conversations never inherit frame messages; only values and trace ids return.
- Every nested call shares one call/token/depth allowance and appears in run accounting.
- No model-authored code gains authority: all host operations use the caller's gated path.
- Resource and trace reads are bounded and use existing authorization/redaction.
- `read_digest` keeps its public behavior and reader role semantics.
- Register every new test namespace in both places in `test/samizdat/test_runner.clj`.
- Do not commit or push without explicit permission; beads is the progress ledger.

## Review Focus

- Two siblings reserve the last call/token: only one is admitted and the other is incomplete.
- A malformed structured answer consumes a repair allowance or returns incomplete, never a fabricated value.
- A frame cancelled or losing its process cannot leave an admitted operation unaccounted.
- A context larger than the provider window is processed only through bounded slices.
- Resuming after token loss never silently restarts effects or invents retained environment state.

---

### Task 1: Shared inference budget tree

**Files:**
- Create: `src/samizdat/inference/budget.clj`
- Create: `test/samizdat/inference_budget_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces `(budget {:calls n :tokens n :depth n})`, `(child budget)`,
  `(remaining budget)`, `(reserve! budget {:output-tokens n})` returning a reservation or nil,
  `(settle! budget reservation {:usage ... :priced? bool})`, `(cancel! budget)`.
- Reservation includes settled usage and remaining allowances.

- [ ] **Step 1: Write failing budget tests**

Cover call/depth exhaustion, atomic competition for one remaining call, output
tranche caps, cached-input settlement, unpriced output reservations, nested child
consumption, and cancellation. Use real concurrent threads for the last reservation.

- [ ] **Step 2: Verify tests fail because the namespace is absent**

Run the fresh-process test form for `samizdat.inference-budget-test`.

- [ ] **Step 3: Implement the budget**

Use one atom per budget tree and atomic reserve/settle operations. Children share
counters and reduce depth. Reservation state carries cancellation so every terminal
path can release or mark its allowance truthfully.

- [ ] **Step 4: Run budget and existing agent budget tests**

Run `samizdat.inference-budget-test` and `samizdat.budget-test`; expected PASS.

### Task 2: Frame traces, contracts, and resolvers

**Files:**
- Create: `src/samizdat/inference/trace.clj`
- Create: `src/samizdat/inference/contract.clj`
- Create: `src/samizdat/resource/inference.clj`
- Modify: `src/samizdat/store/migrations.clj`
- Create: `test/samizdat/inference_trace_test.clj`
- Create: `test/samizdat/inference_contract_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Migration `v48`: `inference_frames` and `inference_views` with parent links,
  role/model, task, schema, status, usage, timestamps; context objects with digest/content.
- Produces `(start-frame! conn frame)`, `(finish-frame! conn id status result usage)`,
  `(record-view! conn frame-id view)`, `(frame conn id)`, `(intern-context! conn label content)`,
  `(context conn digest)`, `(resolver conn)`.
- Produces `(normalize schema)`, `(decode schema text)`, returning value or structured error.

- [ ] **Step 1: Write failing contract and trace tests**

Cover JSON values including false/null/arrays, malformed answer errors, parent-linked
frame lifecycle, content digests, bounded trace windows, unknown ids, and context
objects larger than an arbitrary small test cap.

- [ ] **Step 2: Verify tests fail**

Run both namespaces; expected missing implementation.

- [ ] **Step 3: Implement schema and durable traces**

Reuse Malli for validation and `clojure.data.json` for transport. Persist portable
values as JSON, views as descriptors with content digests. Store context content
privately, never in model-facing resource registry descriptions.

- [ ] **Step 4: Implement bounded inference resources**

`inference:<id>` pages frames and views; `context:<digest>` supports offset/limit and
literal search through one resolver protocol. Keep role/ownership authorization in
the tool layer.

- [ ] **Step 5: Run trace, contract, resource, and migration tests**

Run new tests plus `samizdat.store-test` and `samizdat.resource-test`; expected PASS.

### Task 3: Private inference frames

**Files:**
- Create: `src/samizdat/inference/frame.clj`
- Create: `src/samizdat/agent/tools/inference.clj`
- Create: `resources/prompts/inference-frame.md`
- Create: `resources/prompts/inference-tool.md`
- Modify: `resources/gates.edn`, `resources/userspace.edn`, `resources/manual.edn`
- Modify: `src/samizdat/agent/tools.clj`, `src/samizdat/capabilities.clj`
- Create: `test/samizdat/inference_frame_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces `(infer ctx {:task :views :schema :budget :capabilities})` returning
  `{:status :complete|:incomplete :value ... :trace-id ... :remaining ...}`.
- Produces tool `inference {action "infer", task, views?, schema?, budget?}`.

- [ ] **Step 1: Write failing frame tests**

Use a fake provider and real trace store. Assert explicit views only, no parent
messages, schema validation, one bounded repair, side-call billing, incomplete
budget results, and truthful missing-capability refusals.

- [ ] **Step 2: Verify tests fail**

Run `samizdat.inference-frame-test`; expected missing frame/tool.

- [ ] **Step 3: Implement frames and tool**

Build private system/user messages from prompts and views. Reserve before each
provider call, settle in `finally`, and record every call. Pure and read-only
resource capabilities use the ordinary tool context. Register userspace, role,
prompt, manual, and gates surfaces.

- [ ] **Step 4: Run frame, prompt, manual, and boundary tests**

Expected PASS.

### Task 4: Ordered inference maps

**Files:**
- Create: `src/samizdat/inference/map.clj`
- Create: `test/samizdat/inference_map_test.clj`
- Modify: `src/samizdat/agent/tools/inference.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces `(map-inference ctx tasks opts)` returning ordered per-item complete,
  incomplete, or error values and one shared remaining budget.
- Tool action `"map"`.

- [ ] **Step 1: Write failing map tests**

Assert ordered results, per-item contracts, nested allowance exhaustion, and no
sibling overspend. Exercise parallel and serial execution through injected runners.

- [ ] **Step 2: Verify tests fail**

Run `samizdat.inference-map-test`.

- [ ] **Step 3: Implement maps and tool action**

Use eager `reduce`/loops around parking calls, not lazy collection realization.
Keep result order independent of completion order.

- [ ] **Step 4: Run map and frame tests**

Expected PASS.

### Task 5: Migrate read_digest to a prose frame

**Files:**
- Modify: `src/samizdat/agent/tools/digest.clj`
- Modify: `test/samizdat/digest_test.clj`

**Interfaces:**
- Existing `read_digest` behavior stays stable; it uses frame lifecycle and trace ids.

- [ ] **Step 1: Add failing compatibility assertions**

Require a frame trace containing paths, question, role/model, and usage while the
returned bullets, clipping, anchors, turn inputs, and error categories stay unchanged.

- [ ] **Step 2: Verify new assertions fail**

Run `samizdat.digest-test`.

- [ ] **Step 3: Delegate prose inference to the frame**

Keep current material construction, role selection, anchor handling, and response
clipping. Record one frame and side call per digest, not an extra nested call.

- [ ] **Step 4: Run digest and frame tests**

Expected PASS.

### Task 6: External-context root execution

**Files:**
- Create: `src/samizdat/inference/environment.clj`
- Modify: `src/samizdat/agent/tools/inference.clj`
- Create: `resources/prompts/inference-environment.md`
- Create: `test/samizdat/inference_environment_test.clj`
- Modify: `test/samizdat/test_runner.clj`

**Interfaces:**
- Produces `(complete ctx {:task :context :schema :budget})`.
- Environment operations: `(context-length)`, `(context-slice start end)`,
  `(context-search pattern)`, `(infer ...)`, `(finish value)`.
- Root input appears to the root model only as label/size/digest.

- [ ] **Step 1: Write failing root-environment tests**

Use an input larger than the test provider window. Assert bounded provider requests,
programmatic slicing/search, intermediate values outside parent context, first-finish
wins, budget sharing, and continuation refused when retained execution state is gone.

- [ ] **Step 2: Verify tests fail**

Run `samizdat.inference-environment-test`.

- [ ] **Step 3: Implement root execution through the eval bridge**

Intern context through Task 2. Install environment functions in the existing project
image; proxy nested inference through the caller's gated bridge and budget. Root
execution uses no new host process or network endpoint. Retain only declared execution
state and report loss explicitly.

- [ ] **Step 4: Run environment and bridge tests**

Expected PASS.

### Task 7: Verification and independent review

- [ ] Run all focused suites, `jolt test`, `git diff --check`, and a real provider
  smoke test only if credentials and a local/provider endpoint are already configured.
- [ ] Request one fresh read-only review. Fix Critical/Important findings with
  regression tests and rerun the suite.
- [ ] Record evidence and close `karamazov-d1pw.2` only when frames, maps, traces,
  and root execution all work end-to-end.
