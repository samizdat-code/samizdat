---
name: clj-replace
description: Use when Clojure or Jolt text replacement fails after formatting changes, or when a structural form or local binding needs exact replacement
---

# Replace Clojure and Jolt by structure

Formatting does not change an S-expression's identity. Use samizdat's
revision-gated structural tools for `.clj`, `.cljs`, `.cljc`, and `.jolt`
files instead of retrying whitespace-sensitive text edits or rewriting a
whole file.

## Replace a known form

Use `edit_clojure` when the old and new forms are known. Matching ignores
whitespace but keeps comments, metadata, reader syntax, and token spelling
significant. State the exact match count; use `within` or a larger form to
make the target unique.

```clojure
edit_clojure({
  :edits [{:file "src/example.jolt"
           :within {:form "transition"}
           :from "(old-call data)"
           :to "(new-call\n  data)"
           :matches 1}]
  :preview true})
```

Preview writes nothing and returns a single-use proposal. Inspect it, then
apply it once with `resource({:action "apply" :proposal "PROPOSAL-ID"})`.
Omit preview only for an already reviewed compact edit.

## Rename a local binding

A binding rename is not a sequence of text substitutions. Use
`apply_clojure_changes` so the binding and its references change only in the
selected owner. The match and edit counts include every renamed occurrence:
this example has one binding and one reference, so both counts are two.

```clojure
apply_clojure_changes({
  :changes [{:id :rename-data
             :in ["src/example.jolt"]
             :forms ['parse-request]
             :do [:rename-binding {:from data
                                   :to parsed-data
                                   :preserve-external-key true}]
             :expect {:matches 2}}]
  :expect {:changes 1 :edits 2 :files 1}
  :preview true})
```

Qualified symbols, keywords, and unrelated owners are not renamed. Apply the
returned proposal through the same `resource` boundary.

## When a call refuses

A count mismatch, ambiguous or overlapping target, stale revision, invalid
future source, or protected path refuses the batch without a partial edit.
Do not repeat the call or fall back to a broad text replacement. Inspect the
current structure, narrow the owner or match, and create a fresh preview
when the source changed.

Use `inspect_clojure` when the owner or exact source is uncertain. Load
`clj-surgeon` for heterogeneous transactions, computed transforms,
namespace operations, and other advanced structural work; keep the ordinary
single replacement on the routes above.
