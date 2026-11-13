---
name: harness-tuning
description: Supervisor only. Load before changing this project's loop — a cell, manifest, prompt or policy threshold — or running an experiment.
---

# Tuning the loop

Steering fixes one branch now. Tuning changes the loop for this run and every later one. Bar: steer on one observation; tune only on evidence seen in more than one run. One run fails for reasons unrelated to the loop: flaky provider, unlucky task, bad model day.

|  | Steering | Tuning |
|---|---|---|
| target | this branch | the loop |
| instrument | `intervene` | cell, manifest, prompt, threshold |
| cost if wrong | one turn | every run until reverted |

## Two layers

**Base**: compiled in. Provider calls, tools, database, templates, workflow compiler. Out of reach. Missing piece? Say so, name what it would enable, steer around it. A clear report of a missing capability is a result.

**Userspace**: this project's copy of cells, manifests, policy and prompts. Seeded on first read; every edit is a new version of the project's copy, reaching no other project. A run failing the same way twice is usually wrong assembly, not bad luck.

## Look before changing

- Workflows: catalogue in your digest. `decompose` when implementors cannot do the task in one shot.
- `cells` lists what is loaded. `cell list` shows project versions; `cell versions {name}` shows past attempts, their rationale and green runs. Do not remake a reverted change; do not casually undo one with green runs.
- `manifest list` / `manifest show`: the loops as data.
- `prompt list` / `prompt show {name}`: every word any role reads, this one included.
- `skill list`, `introspect`.

## Manifests and cells

A manifest is a mycelium graph: `:cells` (node → cell id), `:edges` (unconditional or labelled), `:dispatches` (ordered predicates, first wins), `:invariants`.
- A dispatch predicate READS a key the cell set; it never computes.
- Enforced invariants are compile errors. `:must-follow` = if A ran, B runs after; `:must-precede` = if B is on the path, A came first. A refused edit is breaking something written down on purpose.
- A cell declares `:pure` or `:effects`, and `:requires` (ctx keys it reads). Effect marks matter: the soak stubs effectful cells.
- A cell cannot remove a key: input merges over output, so `dissoc` is undone. Set it to nil.

Every save is compiled and dry-run first: a bad edit is a rejection, not a broken run.

## Changing userspace

- Compose before writing: move an edge or reuse a cell before writing a new one.
- One change per round, with a defensible reason.
- Cheapest layer that fits: threshold < prompt < cell < manifest < new workflow.
- Tool matches layer: `cell save`, `manifest save` / `manifest patch`, `prompt save {name, file}`. A prompt sentence is the commonest real cause; do not reach for a cell.
- Large body: write it with `shell` to a scratch file under the run root, outside `.samizdat/`, and pass it as `file`. A file edited directly in `.samizdat/` is live but unchecked. A fix elsewhere in the tree does not exist to the harness. Never leave a `cells/` directory or harness-named file in the project.
- Small mid-run fix: `cell outline {name}`, then `cell replace-form {name, form, clj, rationale}`; `reload_cells` loads it now. Prompts and policy are read fresh; manifests and cells compile in at the next round.
- Every save and revert carries a `rationale` written for the next reader: what the change is for.
- Never revert a version you did not author without reading its body and rationale. Green runs outweigh unfamiliarity.
- Read the validator's failure. Resubmitting a near-identical change is a repeated failed call.

## Experiments

1. Read fitness and findings first: where turns actually go.
2. Least invasive change that could work, and why lighter ones would not: nothing, `remember`, `intervene`, a threshold, a prompt's wording, a cell or manifest.
3. Hypothesis, then ONE change, behind `experiment`. A second is refused until `verdict {name, action, why}` settles the first.
4. Next round: `better` keep it; `worse` or `unchanged` revert it; `too early` wait. `action: reverted` restores the prior version when nothing else edited the target; `stale` means something did.
5. `remember` what you learned. A reverted change is knowledge.

Do not chase the fitness number. Do not sit still when evidence is clear.

## Judgement calls

`:verify-unknown`: what the ship gate does when it cannot tell whether tests pass. `:trust` ships anyway; `:refuse` blocks. Trust once shipped five test errors through an inert gate. Decide from this project's ship-verify notes, change it if evidence says so, `remember` why.
