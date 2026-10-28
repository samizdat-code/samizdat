# Native clj-surgeon port design

## Intent

Give Samizdat's model a Clojure-native structural editor: inspect exact forms,
compute over concrete syntax, and turn one declared refactoring decision into a
guarded change instead of reconstructing Clojure structure with text edits.
Port the useful kernel from `clj-surgeon` and adapt its model-facing guidance,
while keeping Samizdat's existing authority, resource, role, and recovery
boundaries.

The source reference is `/Users/yogthos/src/clj-surgeon` at commit
`affdfe3fd59a63b3fbbb50da16b82509fafea907`, MIT licensed, copyright Gene Kim.
The user selected a native phased port after considering the existing MCP
service and a CLI wrapper.

## Goals

- Read outlines, exact named forms, nested structural matches, and X-ray
  selections from bounded, hash-identified source snapshots.
- Apply exact owner-scoped edits with structural comparison, explicit match
  counts, complete-future-file parsing, and read-back evidence.
- Reuse Samizdat's staged resource proposals for preview, exact-owner apply,
  stale-revision refusal, durable status, and compensation.
- Preserve the useful upstream four-tool conceptual surface:
  `inspect_clojure`, `edit_clojure`, `apply_clojure_changes`, and
  `transform_clojure`, delivered incrementally rather than as a big-bang port.
- Reuse Samizdat's LSP integration for semantic evidence instead of introducing
  cclsp and another process stack.
- Ship an adapted `clj-surgeon` skill plus always-on routing that makes the
  tools discoverable and preferable for structural Clojure work.
- Keep pure planning separate from filesystem effects so upstream behavior and
  fixtures can be ported with minimal adaptation.

## Non-goals and anti-goals

- No wholesale port of the upstream CLI, MCP transports, hot-service lifecycle,
  telemetry, worktree management, installer, cclsp client, or shell-configured
  verification runner.
- No second proposal store, revision convention, permission system, journal, or
  recovery protocol.
- No direct model-triggered writes from the Surgeon planning kernel.
- No replacement of `read_file`, `patch`, `edit_file`, generic `structural`, or
  `lsp`; each keeps its existing niche.
- No claim that resource change sets are atomic across files. They validate all
  revisions, publish atomically per member, and compensate only states installed
  by the change, as `samizdat.resource.changes` already specifies.
- No fuzzy selection, regular-expression source rewriting, silent widening of
  scope, or choosing the first ambiguous match.
- No arbitrary model-supplied verification command or host evaluation. Bounded
  SCI transformation, when delivered, uses Samizdat's capability-limited SCI
  mechanisms.
- No big-bang parity target for every upstream operation before the first
  end-to-end slice ships.

## Constraints

- `src/` contains general mechanism with injected effect seams. Activation,
  limits, role access, workflow decisions, and all model-facing prose live in
  project-owned userspace files.
- New tools use the ordinary role, phase, exposure, approval, flow, redaction,
  cancellation, execution, and journaling paths. Read tools are neutral;
  committed writes report success and progress; malformed calls and refusals
  retain the existing category distinctions.
- Every model-supplied path is confined through Samizdat's existing file and
  workspace-resource authority. Writes require the same role authority as
  `write_file` and cannot target protected workflow or run-configuration files.
- Samizdat already pins `rewrite-clj` and SCI. Do not add another parser,
  sandbox, JSON library, or process library for this port.
- Source derived from upstream retains its MIT notice. Add the upstream license
  under `docs/third-party/` and record source commit provenance in ported files.
- New test namespaces are registered in both required places in
  `test/samizdat/test_runner.clj`.
- Existing unrelated working-tree changes remain untouched. Commits and pushes
  require separate explicit requests.

## Approaches

### Native phased port — chosen

Port the pure structural kernel and adapt it to native tools and resource
changes. This has the highest initial adaptation cost but gives the model a
first-class interface and preserves Samizdat's authority and recovery
boundaries. The user selected this approach.

### Existing MCP service

Configure the already-implemented upstream MCP service and call it through
Samizdat's generic `mcp` tool. This is the fastest way to evaluate full upstream
behavior, but it adds a process stack and does not integrate writes with native
resource proposals. It remains useful as an optional comparison oracle, not the
chosen implementation.

### CLI wrapper

Invoke the installed Babashka CLI through the shell. This requires little new
code but pays process startup, shell quoting, approval, and output adaptation
costs, and produces weaker tool receipts. It is rejected as the primary route.

## Existing Samizdat pieces to reuse

- `src/samizdat/surgeon/`: the earlier MIT-licensed outline, forms, reader
  conditional, fields, and movement code. Keep it and reconcile duplicate
  upstream helpers rather than creating a parallel namespace tree.
- `src/samizdat/agent/tools.clj` and `tools/base.clj`: runtime-redefinable tool
  registration and the common result envelope.
- `src/samizdat/agent/tools/structural.clj`: the model-facing pattern for an
  observed-revision structural query, staged rewrite, execution record, and
  proposal application. Its optional ast-grep backend remains independent.
- `src/samizdat/agent/tools/resource.clj` and `resource/changes.clj`: authorized
  observations, exact-owner proposals, single-use apply, stale checks, durable
  member status, and compensation.
- `src/samizdat/agent/files.clj`: confined paths, source vetting, and write
  authority primitives.
- `src/samizdat/agent/tools/lsp.clj`: semantic definitions, references,
  diagnostics, rename, code actions, and file movement.
- `src/samizdat/agent/skills.clj`: always-on skill catalogue and on-demand
  bodies, with project overlays and cross-harness directory discovery.

## Delivery order

### Stage 1: structural inspection

Deliver `inspect_clojure` with batched `outline`, `forms`, and `match` requests.
Add X-ray after the basic request and snapshot contract is working. Each request
names a project-relative `.clj`, `.cljs`, or `.cljc` file. The adapter observes
each distinct workspace resource once, checks requested bounds, compiles the
whole batch against those observations, and returns ordered results with file
revisions and source hashes.

Selection is all-or-nothing within a request. Named forms may be selected by
exact unqualified owner name; later compatibility may add line and distinctive
literal selection from upstream. Structural match treats `_` as one subtree and
never as text. Results preserve exact source when requested and support a
metadata-only form view for compact inspection.

Port the relevant pure code and fixtures from upstream `show_form.clj`,
`outline.clj`, `forms.clj`, `cljc/walk.clj`, and `structural_lens.clj`, reusing
or merging the existing Samizdat equivalents. X-ray later adds the pure path
evaluator and bounded SCI analysis from `edit_dsl.clj`; it never writes.

Output and request limits live in a new userspace `:clj-surgeon` gate covering
maximum requests, files, forms per file, selected matches, source characters,
and rendered result characters. Do not copy upstream numeric constants into
core. `inspect_clojure` joins the read-only retry allowlist only after its
execution behavior is shown not to duplicate effects.

### Stage 2: exact guarded edits

Deliver `edit_clojure` for exact literal subtree replacement inside one named
owner, with one file or an explicitly grouped file set. The caller supplies
`file`, `within.form`, `from`, `to`, and an optional positive `matches` count
whose default is one. The planner:

1. Observes the current resource and validates the complete source.
2. Resolves exactly one requested owner per file.
3. Finds structurally equivalent `from` forms without ignoring comments,
   metadata, reader syntax, collection type, or token spelling.
4. Refuses zero, excess, overlapping, or ambiguous selections before staging.
5. Constructs every future source from the same frozen observations and parses
   each complete future file.
6. Returns changed-file previews, revisions, hashes, counts, and a bounded diff.

An ordinary exact edit stages and immediately applies through
`samizdat.resource.changes`, returning its committed, stale, compensated, or
recovery-required outcome. A `preview: true` call only stages and returns the
proposal id; the model inspects it and applies it once through the existing
`resource apply` action. No direct file write or separate undo receipt is added
in this stage. The resource proposal and execution records are the native audit
artifacts.

Port pure selection and plan construction from `structural_lens.clj` and the
scoped compiler portions of `intent_transaction.clj`. Replace upstream
`file_ops`, receipt publication, and process verification with injected
Samizdat staging and application functions. Preserve the pure compiler's guards
rather than reimplementing them in the tool adapter.

### Stage 3: multi-file changes and computed transforms

Add `apply_clojure_changes` for coherent multi-file `changes` batches, including
literal replacement, insertion, owner deletion, binding rename, and
comment-preserving map association as their upstream pure compilers are ported.
Every action uses the same observed snapshot, exact cardinality guards, disjoint
edit validation, complete-future-file parsing, and one resource change set.

Add `transform_clojure` for a bounded pure SCI relation ending in `transform`.
Preview is the default. The function receives selected syntax as data and cannot
perform I/O, load namespaces, mutate host state, launch processes, or use host
interop. Match and changed-character limits live in userspace. Commit uses the
same Stage 2 resource path. Do not port the upstream general SCI environment
without reconciling it with `samizdat.sandbox.sci`.

### Stage 4: refactors and semantic preparation

Port dependency-aware movement, namespace extraction, namespace rename, declare
cleanup, and CLJC operations in independent vertical slices. Each must retain
its upstream planning and refusal fixtures and publish through the same native
resource path.

When semantic preparation is needed, adapt upstream's basis concept to
Samizdat's LSP client and exact source anchors. A basis must distinguish
language-server evidence from structural supplements and must never describe
structural candidates as semantic completeness. Reuse durable execution and
resource identities rather than porting cclsp sessions or the upstream
process-local basis registry unchanged.

Verification remains Samizdat's ordinary configured test and acceptance flow in
these stages. A future stage may design transaction-scoped verification and
rollback separately; it is not silently copied from upstream shell profiles.

## Tool registration and model routing

Create a shared native tool implementation namespace, tentatively
`samizdat.agent.tools.surgeon`, and register each public tool with
`base/run-tool`. Require it from `samizdat.agent.tools`. The builder may split
pure inspection, edit compilation, and adapter namespaces where that reduces
coupling; the four public names remain stable.

Add only the delivered tools to the implementor's `roles.edn` surface and to
other roles that genuinely need them. Read-only roles may receive
`inspect_clojure` without edit tools. Update `system-tools.md` at each stage
with ordinary routing:

- Use `lsp` for resolved semantic definitions, references, and semantic edits.
- Use `inspect_clojure` for exact Clojure structure and bounded source reads.
- Use `edit_clojure` for an already-known exact structural replacement.
- Use one `apply_clojure_changes` call for a coherent multi-file or
  heterogeneous transaction.
- Use `transform_clojure` when a pure relation derives the replacement.
- Keep `grep` for broad textual discovery and native file tools for prose,
  unsupported transformations, and small visible text edits.

Tool results use prompt templates rather than hard-coded prose. Add each newly
usable capability to `resources/manual.edn` when it lands. File-flow observation
and redaction cover the new tool names; reads take labels from returned source
files and writes label their committed targets through the existing boundary.

## Skill design

Ship `resources/skills/clj-surgeon.md` and enumerate it in
`samizdat.agent.skills/shipped-skills`, keeping the existing flat bundled-skill
mechanism. Convert folded YAML frontmatter to one `name` line and one
single-line `description` trigger.

The first skill is compact and advanced-only, matching upstream's intent:
X-ray, computed transforms, multi-file transactions, preview/apply, and when to
load later refactor guidance. Ordinary inspection and exact-edit routing stays
in the always-on tool catalogue. Rewrite MCP-specific instructions to the
native names and remove CLI fallback, MCP recovery, process onboarding, and
cclsp instructions.

Port linked reference material only when its operation lands. Where a reference
is substantial, ship another descriptively named flat skill and replace Markdown
link loading with an explicit `skill load {name}` instruction. This avoids a new
reference-resolution mechanism and works from the embedded binary as well as a
checkout. Projects can still override the skill through
`.samizdat/skills/clj-surgeon.md`; no project userspace role-map entry is needed
for the current skill loader.

## Verification

Deliver test-first at both the pure and real tool boundaries.

- Port focused upstream fixtures for exact form selection, comments, metadata,
  strings, reader conditionals, nested matches, cardinality, stale hashes, and
  complete-file parse failures.
- Test tool requests against temporary workspaces with real resource proposals:
  all-or-nothing reads, bounded output, exact edits, preview without writes,
  single-use apply, stale revision refusal, wrong-owner refusal, path escape,
  protected-path refusal, role denial, and compensation after an injected
  publication failure.
- Test CLJ, CLJS, and CLJC behavior and ensure unrelated bytes and comments
  survive exact edits.
- Exercise `samizdat.agent.tools/run-tool`, not only pure functions or namespace
  imports, so envelope, redaction, role, flow, execution, and journal behavior
  are covered.
- Verify the shipped skill appears in the catalogue with a real description,
  loads by name, resolves from a foreign working directory, and contains no
  stale MCP-only routing.
- Run affected namespaces in fresh processes and `jolt test` on the final tree.
- Dogfood one real multi-form Clojure edit through the model-facing tools and
  the project's ordinary verification command. Report any unrun external or
  live-model check plainly.

## Builder latitude

The builder may choose concrete namespace splits, internal argument
normalization, pure helper APIs, and the order of independent operations within
a stage. The builder may merge duplicate existing and newly ported Surgeon
helpers when behavior remains fixture-compatible.

The builder may not weaken exact selection, path confinement, source revision,
role, ownership, or result bounds; introduce direct filesystem writes; claim
multi-file atomicity or semantic completeness the mechanism does not provide;
move policy constants or model-facing prose into core; or substitute a generic
text replace for a structural operation.
