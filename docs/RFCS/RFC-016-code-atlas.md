# RFC-016 — The code atlas: a program as the smaller programs inside it

**Status:** implemented for Clojure (karamazov-f73x). The graph, flows, units,
workflow state graphs, snapshots and diffs, the HTTP API, the agent's `atlas`
tool and the GUI's atlas view are built and tested. Other languages are not
read yet (karamazov-f73x.10).

## Purpose

To look at the program being built and see how it fits together: click a
function, see what it calls and what calls it, zoom out to groups of
functions as units, zoom in to source, and watch how it changes run to run.
The references are Code Bubbles (cs.brown.edu/people/spr/codebubbles) and the
Light Table demo. The extraction ideas come from stratify
(~/src/stratify).

The organising idea is that a large program is a superposition of smaller
programs that share code, each running from an input to an output. So the
atlas first lists those paths as separate flows that can each be inspected
on their own. Functions are then grouped by which flows they serve.

## Decisions (the spike, karamazov-f73x.1)

- **Extraction uses the host reader, not clj-kondo or LSP.** jolt's reader
  keeps line and column on every list. That is enough for definitions, call
  sites and, with a bracket scan, each form's end line. It needs no external
  binary and reads a project that does not load. Two things the reader
  refuses (`::alias/kw` without the alias, and regex literals, which it
  compiles) are blanked first, column for column (`clojure/sanitize`).
  clj-kondo would see through macros. It is available on this machine but
  not guaranteed elsewhere, and the reader's approximation measured well
  enough: every one of the repository's 230+ files reads.
- **Stratify is ported from, not depended on.** It is JVM-only, and samizdat
  runs on jolt. What carried over is its model, a call graph with a
  `:parent` containment hierarchy, and two operations from its studio
  package: edge lifting (`aggregate-collapsed-next`, here `graph/lift`) and
  the butterfly (`graph/butterfly`). What stratify lacks, the atlas adds:
  - path enumeration
  - grouping beyond the namespace
  - call sites on edges
  - an EDN/JSON export
  - state machines
- **The GUI is the existing GTK one, not a browser.** A web front end would
  have to carry the server's bearer token and get past its Origin checks
  (`server/refusal`). The GTK GUI is already a strict HTTP client with the
  token. Its GL pane draws only lines and triangles, so canvas labels are
  strokes from a small vector font (`gui/atlas_scene`). Source cards are
  Pango markup in ordinary labels.
- **Path explosion is handled by counting before listing.** Measured on this
  repository:
  - 348 entries under the default rules
  - a median of 3 paths per entry, a p90 of 1552, and a maximum of 150,600
  - all flows computed in 2.6 s

  Paths are counted exactly on the cycle-condensed graph by one pass in
  topological order, and listed only up to the policy's caps.

## Layers

| Layer | Namespace | What it does |
|---|---|---|
| Model | `samizdat.atlas.graph` | Nodes, edges with sites, containment. Callers, callees, butterfly, lift, SCC and condensation. |
| Extraction | `samizdat.atlas.clojure` | Source text to graph. `extract-file` is per file and cacheable; `link` resolves references across files. |
| Flows | `samizdat.atlas.flows` | Entries and sinks by rule, slices, path counts, capped path listing, the trie, membership, units, coarsening. |
| States | `samizdat.atlas.states` | A manifest as a state graph; a run's steps as an overlay. |
| History | `samizdat.atlas.snapshot` | A compact snapshot and a diff between two. |
| Layout | `samizdat.atlas.layout` | Layered (Sugiyama-style) positions, calls left to right. |
| Facade | `samizdat.atlas` | The project's graph with a per-file cache, under gates.edn `:atlas`. Views, cards, flows, units, search, state graphs, snapshots. |
| HTTP | `samizdat.server` | `/v1/atlas/*`. |
| Agent | `samizdat.agent.tools.atlas` | The `atlas` tool. |
| GUI | `gui/samizdat/gui/atlas*.clj` | The scene (pure), the panels (pure), and the effects. |

Everything in `src/` is mechanism. What counts as a source, an entry or a
sink, the caps, the unit similarity and the unit size cap are gates.edn
`:atlas`. The GUI's sizes, colours and starting zoom are gui.edn `:atlas`, a
layered settings file served at `/v1/harness/layout?name=gui` so the agent
can rearrange it.

## The model

`{:nodes {id node} :edges {[from to] edge}}`, plus adjacency.

- **Node:** `{:id :label :kind :parent :file :line :end-line :hash :doc :private?}`.
- **Edge:** `{:from :to :sites [{:file :line :col :ref? :dispatch?}]}`. Naming
  an edge again adds its sites to the same edge. An edge to an id that is not
  a node (a library) is dropped.

Ids and kinds:

- A definition is `ns/name`.
- A namespace's parent is the prefix group (`samizdat.agent.*`), and groups
  nest up to the root.
- A `defmethod` is its own node, `ns/multi dispatch`, which its multimethod
  dispatches to. Otherwise one multimethod with many methods, such as
  `run-tool`, becomes a hub that every tool's path runs through.
- A `defcell` is a node whose id is its cell keyword, so a workflow state
  (whose `:cell` is that keyword) links straight to its code.

A reference in head position is a call. Anywhere else it is a reference
(`:ref?`), because a function passed on is still reached. Locals bound by fn
params and let-like forms shadow definitions.

## Invariants

- **What is drawn is what is hit.** The canvas's `:draw` and `:pick` use the
  same transform from the same state value (`atlas-view/source-map`).
  *Enforced by:* gui-atlas-view-test.
- **A path count is exact even when the listing is capped.** *Enforced by:*
  flows-test `paths-are-counted-before-they-are-listed`.
- **A file is reread only when its text changed.** *Enforced by:* atlas-test
  `a-file-is-reread-only-when-it-changed`.
- **Moving a function does not change it.** The source hash covers the
  form's text, not its position. *Enforced by:* clojure-test
  `a-definition-carries-a-hash-of-its-source`.
- **A snapshot is stored only when the graph changed.** *Enforced by:*
  atlas-test `snapshots-are-kept-when-the-graph-changes-and-diffed`.
- **Nothing here decides what the harness does.** The run-end snapshot is
  taken only when `:snapshot?` says so. The tool is read-only (`:neutral`).

## Units

Functions serving exactly the same set of flows form a unit: the code one
set of flows owns. On this repository that gives 601 units, 327 of them a
single function, which is too fine for a zoomed-out view. `coarsen` merges
units whose flow sets overlap at least `:unit-similarity`, most similar pairs
first, and never past `:max-unit-size` members:

| Similarity | Units, cap 60 | Largest, without the cap |
|---|---|---|
| 1.0 | 601 | 227 |
| 0.8 | 350 | 1408 |
| 0.5 | 211 | 1794 |

Without the cap, merging along chains of overlap pulls most of the shared
code into one unit. The 227 is one flow set shared exactly by that many
functions, which the cap does not split.

## Evaluation (karamazov-f73x.5)

Two projects, read with the shipped policy:

| | samizdat | stratify |
|---|---|---|
| Files read, unread | 230+, 0 | all, 0 |
| Nodes / call edges | 4314 / 7990 | 541 / 651 |
| Read, cold | 3.8 s | 0.6 s |
| Entries | 251 | 85 |
| All flows | 2.8 s | 87 ms |
| Paths per entry, median / max | 3 / 82,031 (`samizdat.core/-main`) | 3 / 184 |
| Units (0.5, cap 60) | 211 | 66 |

**Where it helped.**
- Callers and callees match the source wherever they were checked:
  `extract-graph`'s single caller in stratify, and `resolve-ask`'s callers
  here.
- A flow's slice answers "what does this entry point need" directly. For
  stratify's `-main` it is 99 of 541 functions.
- Each tool's `run-tool` method being its own flow is what made the
  methods-as-nodes decision.
- The workflow graph puts the manifest and its cells' code one click apart.

**Where it was noise, and what changed because of it.**
- **Opening every namespace at once is unreadable.** That is 255 boxes in a
  few layers, and fitting them into the pane piled the boxes on top of each
  other. So zoom is now pixels per layout unit, the canvas starts with the
  top groups closed, and "fit" is a button.
- **The leaf-sink rule caught every `defonce` atom.** It now applies to
  functions only.
- **Single-linkage units chained 1794 functions together.** This led to the
  size cap.
- **The first view of a large project took longer than the client's 10 s
  timeout.** The atlas's calls now wait up to two minutes.
- **The workflow overlay is empty for any run older than the server
  process,** because step events are kept only in memory (`samizdat.steps`).
  The panel says so. Making the overlay durable is karamazov-f73x.11.
- **Public functions that nothing calls count as entries.** Some are dead
  code and some are reached by name (`requiring-resolve`, a symbol in EDN).
  Both are worth seeing, but they are not the same thing, and the policy
  cannot tell them apart.

## Not done

- Other languages (karamazov-f73x.10). The extractor seam is
  `extract-file` → `{:nodes :calls}`. An LSP extractor
  (`documentSymbol` plus `references`, keeping the call-site ranges stratify
  drops) needs a multi-server LSP client; `samizdat.lsp.client` speaks only
  clojure-lsp today.
- Macro expansion. A call made only inside a macro's expansion is not seen.
