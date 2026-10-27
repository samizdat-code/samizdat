# Autolith architecture adoption design

## Intent

Adopt the useful mechanisms identified in the 2026-10-26 Autolith review without
importing Autolith's live-image architecture. The work is tracked by epic
`karamazov-d1pw` and delivered on `autolith-adoption` in dependency order.

The unifying change is a revisioned resource substrate. Files, inference traces,
retained command output, knowledge evidence, and engineering artifacts then share
identity, bounded reads, observed revisions, and controlled mutation. Recursive
inference, evidence-aware knowledge, and asynchronous completion build on that
substrate. Semantic LSP edits, structural editing, DAP, and contextual rules are
consumers rather than separate architectures.

## Goals

- One resource protocol for identity, observation, bounded reads, revisions,
  operation discovery, and staged change sets.
- Multi-file semantic edits that validate every observed revision before the
  first write and never silently overwrite a concurrent change.
- Private inference frames with explicit context views, output contracts,
  shared budgets, traces, recursive maps, and an external-context root mode.
- Knowledge whose evidence and premises can be rechecked, explained, and marked
  stale or conflicted instead of remaining permanently current by default.
- Diagnostic probes selected by the facts they distinguish.
- Durable asynchronous completion receipts and explicit continuity decisions
  that never blindly repeat side effects.
- Retained raw command output with bounded resource reads and search.
- Declarative contextual rules using the existing symbolic pattern language.
- Optional structural AST editing and DAP sessions integrated through ordinary
  tool authority and resource changes.
- Every model-facing capability documented, discoverable, configurable through
  userspace, and covered by behavioral tests.

## Non-goals and anti-goals

- No Autolith-style active-image mutation, saved Lisp generations, private
  replay commits, or pristine recovery image.
- No replacement of mycelium manifests, callable cells, the eval bridge,
  worktrees, acceptance gates, held-out validation, or the symbolic engine.
- No second task board, memory store, provider client, permission system, or
  workflow scheduler.
- No new generic protocol whose only consumer is hypothetical. Each mechanism
  lands with a real consumer and an end-to-end test.
- No direct filesystem writes by LSP, AST, or DAP adapters. They return data or
  proposed changes; Samizdat authorizes and publishes them.
- No broad migration of working tools merely for naming consistency.
- No autonomous promotion of learned knowledge, procedures, diagnoses, or
  contextual rules without the existing validation and authority boundaries.
- No claim that in-process evaluation or process separation is a hostile-code
  security boundary.

## Constraints

- `src/` contains mechanism with injected effect seams. Activation, workflow
  decisions, thresholds, retention policy, and all model-facing prose live in
  project-owned userspace files.
- New tools use the ordinary role, phase, exposure, approval, flow, redaction,
  cancellation, and journaling path.
- Provider usage, including nested inference and repair calls, remains visible
  to run budgets and the journal.
- Parking code follows the ebb fiber rules: no parking inside lazy sequences or
  counted-lock-held collection operations, and no database connection use that
  violates the existing writer discipline.
- New persistent state uses explicit SQLite migrations and resume tests.
- Optional external programs such as `ast-grep` and DAP adapters fail as
  unavailable, never as silently successful substitutes.
- New test namespaces are registered in both places in
  `test/samizdat/test_runner.clj`.
- Existing unrelated working-tree changes are preserved and not rewritten.
- Commits and pushes require separate explicit requests.

## Approaches

### Protocol-first — chosen

Build the resource/change protocols before their consumers. This costs more
before the first visible feature, but avoids separate revision, artifact, and
transaction models for LSP, inference, retained output, and knowledge. The user
selected this approach.

### Consumer-first extraction

Deliver transactional LSP rename and local inference frames, then extract their
common protocols later. This would produce visible features sooner but creates
temporary APIs and makes later consumers conform to decisions made without
their requirements. The user did not choose this approach.

### Live-image parity

Copy Autolith's live definition, generation, and recovery model. This conflicts
with Samizdat's validated userspace architecture and is rejected.

## Delivery order and dependencies

1. Resource identity, observations, and workspace change sets.
2. Resource tools and semantic LSP edits.
3. Inference frames, maps, traces, and external-context root execution.
4. Retained command output as resources.
5. Evidence-backed knowledge and diagnosis.
6. Asynchronous completion receipts and continuity.
7. Contextual rules.
8. Structural AST editing.
9. DAP sessions and debug artifacts.

Later stages may use earlier resource and receipt mechanisms, but each stage
must leave the full suite green. Documentation and manual discovery land with
the capability, not in a final catch-up pass.

## Design

### 1. Revisioned resources

Add a small `samizdat.resource` mechanism with authority-neutral values:

```clojure
{:uri "workspace:src/example.clj"
 :resolver :workspace
 :identity "src/example.clj"}
```

A resolver protocol supports `resolve`, `observe`, `read-window`, and
`operations`. An observation contains the URI, opaque revision, content or
bounded content descriptor, and enough adapter-private identity to revalidate
it. Revisions are compared by adapter operations, not by core assumptions about
hashes, timestamps, or database versions.

A registry holds resolver instances per run or branch context. Authorization
stays outside the generic protocol and uses the caller's ordinary tool context.
Read and edit tool descriptions are composed from the operations actually
registered for that context.

The workspace adapter uses existing confined path resolution, read-only
reference roots, hashline observations, and source vetting. Core never grants a
new path authority.

### 2. Staged change sets

A change set contains one or more observed resources and adapter-specific
operations. Its lifecycle is:

```text
observe -> stage -> validate all -> commit each -> compensate on failure
```

Validation reruns immediately before the first commit and before each member's
publication. Workspace publication is atomic per file and preserves permissions.
Original bytes are retained until the set finishes. Compensation restores only
a file still in the state this change set installed; a later external change is
reported for explicit recovery rather than overwritten.

The result distinguishes staged, stale, partially-published, compensated, and
recovery-required outcomes. Journaling records the complete identity and outcome
without requiring full file contents in the event.

Proposals are exact-owner, single-use runtime values. They survive compaction
but not branch handoff or process restart; an old token fails closed after
resume. Before the first write, the journal durably records the change-set id,
member revisions, and private undo-artifact references. Member outcomes are
journaled through completion. A crash during publication therefore leaves a
recoverable record and retained originals, not an anonymous partial edit.
Compensation runs synchronously before an ordinary tool result; interrupted
publication becomes `recovery-required` and never silently retries the set.

LSP rename becomes the first consumer. Code actions and file rename/move use the
same normalized `WorkspaceEdit -> change set` path. The model can preview a
proposal and apply it once against retained revisions. A failed member no longer
leaves an unreported partial rename.

### 3. Resource tools

Add `resource` operations for read, search where supported, operations, and
edit/apply where supported. Existing `read_file`, `patch`, `write_file`, and
`edit_file` remain during migration; new consumers use resources rather than
creating parallel revision conventions.

Role maps, prompt documentation, the manual, gates, and tests name the new tool.
Thresholds for windows, results, proposals, and retention live in `gates.edn`.
Prompts contain every model-facing message.

### 4. Inference frames and maps

Generalize `read_digest` into a private inference-frame mechanism. A frame has:

- a task and immutable labeled context views;
- an optional output schema;
- an optional role/model selection resolved through existing config;
- a private conversation and trace id;
- no inherited parent messages;
- a shared budget subtree;
- an explicit, minimal capability set.

The initial capabilities are pure inference and read-only resource/search
access. Tool-using frames route through the ordinary tool path with the caller's
authority; they do not inherit unrestricted tools.

`infer` returns a validated value or structured incomplete result. Malformed
structured output may be repaired within the remaining budget. `map` fans out
ordered tasks, preserves per-item success/incomplete/error outcomes, and shares
the parent's budget. No child may create an independent allowance.

Budgets track calls, tokens, and depth. Reservation and settlement are atomic
across sibling executions. Output reservations use provider limits where
available. Input usage settles after response and gates subsequent admission.
Cancellation and exhausted budgets return evidence and trace references rather
than fabricated contracted values.

`read_digest` becomes a compatibility consumer of a prose frame, preserving its
current tool behavior and billing. Every side call is linked to its frame trace.

### 5. Inference resources and external-context root execution

Persist frame requests, responses, context-view descriptors, usage, and parent
links as journal-backed traces. `inference:<trace-id>` provides bounded reads.
Compaction may cite a trace rather than inline its contents.

Large inputs become content-addressed `context:<digest>` objects. The root model
sees label, size, and digest, not the full object. A sandboxed execution
environment can slice, search, invoke nested inference, retain intermediate
values, and finish with a contracted value. Host-side operations enforce the
same budget, capabilities, and resource authority; model-authored environment
code cannot mint authority.

The root environment uses Samizdat's existing eval/project-image and bridge
mechanisms, not a second Lisp worker architecture. Continuation after an
incomplete run is explicit through the context/reference and retained execution
state. Ephemeral state loss is reported, never silently restarted with possible
repeated effects.

### 6. Retained command output

Give each shell execution private raw stdout/stderr captures before launch.
Return exit/timeout/cancellation status, observed and retained byte counts,
completeness, and `shell-log:<id>` references. The inline result remains bounded
and samples beginning and end, with policy-controlled allocation between
streams.

The resource adapter supports byte windows and literal UTF-8 search with scan
and result limits. Raw files remain outside model context until read. Access
follows the owning run/branch inspection authority and redaction rules.

Retention limits, age, count, capacity, and preview budgets live in gates.
Active captures and undelivered completion evidence are protected from pruning.
Missing, incomplete, and pruned captures have distinct truthful results.
Existing journal `full_result` behavior remains for compatibility and is not
used as unlimited raw storage.

### 7. Evidence-backed knowledge

Extend rather than replace `knowledge`. Add durable evidence and premise edges
for file/user/URL/tool/artifact sources. File evidence records project-relative
path, quotation where applicable, and exact content revision. Evidence from a
tool or artifact names its stable record. Imports require local revalidation.

Recall computes status from current evidence and premises: current, stale,
unverified, conflicted, or retired. Recall performs only local checks against
files, retained artifacts, and evidence already present. It never fetches a URL,
runs a tool, or spends provider tokens. Missing externally sourced evidence is
unverified; expired evidence is stale; a local content mismatch is stale. An
explicit separately authorized refresh operation may retrieve new evidence and
create a new observation. Status and derivations are not frozen as a second
authoritative copy. Existing salience, confidence, outcome, corroboration,
lineage, and retirement behavior remain independent axes.

Expose explanation through the knowledge tool and resource reads: evidence,
premises, dependent claims, and why a status changed. A recalled item whose
status changed since it was previously delivered is identified at a later safe
context boundary.

Use `samizdat.symbolic` for structured fact/rule derivation and contradiction
analysis. Do not add a second logic engine. Admission of new active rules or
contracts follows ordinary userspace and approval policy.

### 8. Action contracts and diagnosis

An optional action contract declares argument patterns, required facts,
expected effects, and observations extracted from structured results. Requirement
failures refuse before execution. External success and failure to record an
expected observation are separate outcomes.

A diagnosis names a small bounded set of hypotheses, their predicted facts, and
available probes. Existing known facts are checked first. Probe selection favors
information distinguished per policy cost and runs within a fixed probe budget.
Only accepted, undisputed evidence rules out a hypothesis; an unresolved case
stays open. Saved diagnoses and cases are inspectable and reusable through
resources.

The first concrete consumers are `lsp diagnostics` and operator-configured
verification commands. Their shipped contract examples map structured
diagnostics and command outcomes to facts. A repeated failed verification in the
existing turn/phase policy steers the branch to `diagnosis`; the tool and its
probes then run through the ordinary tool path. This exercises contracts and
diagnosis inside current coding workflows without creating a second repair
manifest or forcing diagnosis on every failure.

Activation, accepted evidence classes, costs, limits, and wording live in
userspace. Diagnosis composes with the existing turn and phase manifests rather
than becoming a new workflow engine.

### 9. Asynchronous completion and continuity

Introduce a durable execution record shared by asynchronous shell, inference,
structural, and debug work. It records original specification, exact owner,
lineage, lifecycle, artifact references, delivery receipt, and recovery
classification.

Completion delivery enters the exact owning run/branch through the existing
intervention and safe-boundary mechanism. Idle completions coalesce within a
userspace-defined window. A busy branch receives them at its next safe boundary.
Receipts survive resume and compaction. Notify-only work does not trigger an
automatic provider turn.

Continuation is explicit per execution: live-owned, reconstructible, safe to
restart with a recorded rationale, or requiring a decision. Recovery never
re-executes a mutating specification merely because no terminal row was found.
Unknown outcomes remain unknown until inspected. Revival links old and new
execution ids and consumes a durable claim before dispatch.

Extend existing parked decomposition behavior through this protocol rather than
creating a competing scheduler. Beam remains the scheduler; completion records
are durable evidence delivered to it.

### 10. Contextual rules

Add userspace data mapping symbolic selector patterns to bounded context
contributions. Selectors cover touched path/language, tool or namespace, current
role/task/workflow, and structured diagnostic/event data. Rules resolve at turn
boundaries into the existing context-block and budget path.

Turn generation identity prevents a late asynchronous diagnostic from activating
a rule in a newer turn. Stable applicable content stays cache-friendly; dynamic
content remains tail-appended. Directory instructions continue to work and can
migrate to the same selector mechanism without changing their discovery rules.

The manual enumerates contextual rule roles and their selectors. Validation
rejects unknown selectors, unbounded contributions, and ambiguous or shadowed
rules where symbolic analysis can establish them.

### 11. Structural AST search and rewrite

Add an optional backend adapter for `ast-grep`, discovered through explicit
configuration. Queries run against observed workspace resources and return
matches with resource revisions. Rewrites produce staged change sets and bounded
previews; apply is single-use and revision-gated.

The backend never writes the workspace. Executables and paths use ordinary
approval. Missing backends report unavailable. Tests use a deterministic fake
backend for protocol behavior and, when installed, an opt-in real `ast-grep`
integration check.

### 12. Debug Adapter Protocol

Implement DAP as an optional stdio JSON-RPC client mechanism using existing
process, cancellation, and JSON facilities; no new library is assumed. The
adapter owns framing, request correlation, events, deadlines, launch/attach,
and orderly process cleanup.

Semantic tools cover breakpoints, continue, pause, stepping, threads, stack,
scopes, variables, evaluation, events, cancellation, and termination. Sessions
belong to their originating run/branch and adapter processes require explicit
ordinary authorization. Adapter-specific raw requests are not required for the
first complete surface.

Debug output and terminal artifacts are retained resources. Sessions integrate
with asynchronous receipts and continuity where meaningful; an adapter that
cannot be reattached is classified as needing a decision, never silently
relaunched. Tests use a fake adapter for state and cancellation behavior plus an
opt-in real adapter smoke test where available.

## Migration and compatibility

- Existing tools remain callable while their internals migrate incrementally.
- `read_digest` keeps its current arguments and prose result.
- Existing knowledge rows remain valid; absent evidence means unverified rather
  than falsely current.
- Existing branch mailboxes and decomposition parking remain compatible with
  durable completion delivery.
- New database tables/columns are additive. Tests exercise migration and resume.
- Project userspace adopts new unedited templates through the existing adoption
  mechanism; no source fallback bypasses the project role map.
- Autolith-related existing beads are linked or superseded only after the new
  child issues cover their remaining scope. No duplicate implementation work is
  intended.

## Verification

Each stage follows test-first delivery:

1. Add focused failing behavior tests, including failure and cancellation paths.
2. Implement the smallest complete vertical slice.
3. Exercise the real tool boundary, not only namespace imports or pure helpers.
4. Run affected namespaces in fresh processes.
5. Run `jolt test` on a quiescent final tree after implementation stages and at
   epic completion.
6. Run real integration probes for LSP, inference, resource edits, asynchronous
   resume, and retained output. External AST/DAP probes run when their programs
   are available; otherwise their absence is reported plainly.
7. Verify role documentation, manual discovery, userspace adoption, migrations,
   cancellation, and budget accounting.
8. Inspect the complete diff and confirm unrelated pre-existing edits remain
   untouched.

Acceptance is per subsystem and end-to-end:

- LSP multi-file changes cannot publish against a stale member and cannot
  silently leave an unrecorded partial result.
- Nested inference cannot exceed its shared allowance or pollute the parent
  conversation, and a larger-than-window context can be processed through
  bounded views.
- Knowledge status changes when evidence or premises change, with an
  inspectable explanation.
- Restart/resume never duplicates an uncertain mutating execution.
- Retained output is readable after compaction without rerunning the command.
- Contextual rules activate only for their declared selectors and generation.
- AST rewrites and debug operations use ordinary authority and resource/receipt
  boundaries.

## Builder latitude

The builder may choose exact namespace splits, schema names, table layouts, and
small pure-helper APIs consistent with surrounding code. The builder may reduce
public surface duplication by migrating an existing tool internally, but may not
drop a listed capability, weaken authority or revision checks, hide incomplete
results, or move policy constants into core.

If a stage exposes a missing primitive in an existing library, record it in
beads and implement the smallest general mechanism at that layer. Do not work
around it with a feature-specific parallel subsystem.
