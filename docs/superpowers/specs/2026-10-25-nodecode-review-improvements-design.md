# Nodecode Review Improvements Design

## Intent

Port the useful recovery mechanisms from the Nodecode review without adopting its full-authority eval model or replacing Samizdat's existing architecture. Preserve immutable tapes, explicit tools, project-owned userspace, provider adapters, and cancellation.

## Goals

- File-backed userspace history and live files remain consistent after failed saves and reverts.
- Deterministic eviction is available without a summarizer, with bounded literal journal recall and cumulative turn references.
- Covered-prefix rendering reuses only stable message objects and is byte-identical to uncached compaction.
- Interrupted streams preserve partial output and can continue within a bound; optional slow-start failover uses existing provider fallbacks.

## Non-goals and anti-goals

- No arbitrary core mutation, mono-tool eval, new dependency, or new tool family.
- No replacement of `fetch_turn`, summary compaction, ordinary retry, or provider fallback.
- No cache result that differs from the uncached render.
- No retry, continuation, or fallback after cancellation.

## Approaches considered

### Deterministic context

Replace summary compaction wholesale versus add a selectable deterministic strategy. The second was chosen: `:compaction :strategy :evict` is available, while `:summary` remains the shipped default. Eviction uses turn-addressed journal records, not a model call, and refuses unaddressable content instead of losing it.

### Hydration

Memoize complete requests versus validate and reuse only the stable prefix. Prefix reuse was chosen because it preserves compaction while avoiding repeated work. Source messages, turn evidence, normalized options, and the first current rewrite determine the safe boundary.

### Stream recovery

Rebuild provider retry versus extend the existing ladder. The existing ladder was retained. EOF before `[DONE]` yields a partial completion, visible answer text may anchor a bounded same-provider continuation, and optional slow start uses `cancel/with-deadline` before ordinary fallback retirement.

## Decisions

- `userspace/save!` and `revert!` write the live file before appending history. Any failure restores the previous target file and role map; a process crash is recovered by existing file-edit recording.
- Eviction markers carry cumulative `:evicted-turns`, including earlier eviction markers. Pinned content is never evicted, and a message without a journal address makes deterministic eviction refuse the fold.
- `journal/search-turns` searches `assistant_text`, `args`, `result`, and `tool_name`, never `reasoning_text`, using literal terms. Previews are bounded and exact-turn behavior is unchanged.
- `llm.message/compaction-plan` is the pure semantic authority. `agent.hydration/compact` returns the rendered messages, next cache entry, and reused prefix length.
- `:llm :continuation` defaults to `{:enabled? true :min-chars 1000 :max-attempts 1}`. Reasoning-only or blank partial output is not continued. Usage and elapsed time are summed.
- `:llm :slow-start-ms` is unset by default. It acts only when another candidate exists and never overrides a control signal.
- Model-facing prose lives in registered resource prompts. Mechanism stays in `src/`; behavior stays in project-editable policy/config.

## Verification contract

- Focused red/green tests cover both save failure directions, role-map rollback, repeated eviction, literal search bounds, cache invalidation, cut streams, merged usage, slow start, cancellation, and monotonic delta offsets.
- The complete suite must pass after independent whole-branch review findings are addressed.
- The original save-failure reproduction must end with no candidate in either store or file.
