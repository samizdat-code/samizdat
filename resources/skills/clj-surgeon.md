---
name: clj-surgeon
description: Use for advanced structural Clojure work with inspect_clojure and edit_clojure: batched selectors, structural paths and X-ray analysis, exact multi-form edits, and preview/apply workflows. Do not load for ordinary calls covered by the tool schemas.
---

# Advanced clj-surgeon routes

Optimize complete verified task time. An ordinary bounded structural read or an
already-decided compact edit needs only the always-loaded routing and tool
schemas.

## Make one coherent structural read

Choose the narrowest operation that answers the question:

- `outline` discovers top-level owners and their line ranges. Do not run it as a
  preflight when the owner or distinctive source text is already known.
- `forms` reads exact source selected by one of `form`, `forms`, `line`, or
  `contains`. Use `forms` for known owners across one file.
- `match` finds a structural pattern, optionally `inside` a named owner. `_`
  matches exactly one subtree; there is no variadic wildcard.
- `xray` follows a structural path and computes bounded facts without evaluating
  project code.

Batch related requests over one frozen snapshot and state the exact aggregate
counts. `files` counts distinct canonical project-relative files, and results
preserve request order.

```clojure
inspect_clojure({
  :requests [{:operation "outline" :file "src/example.clj"}
             {:operation "forms" :file "src/example.clj" :form "transition"}
             {:operation "match" :file "src/other.clj"
              :inside "render" :match "(inc _)"}]
  :expect {:requests 3 :files 2}})
```

Every request carries its source revision. A bad selector, count, bound, or
source refuses the whole batch without partial source; correct the request
rather than repeating it or reconstructing the result with text reads.

## Structural paths and X-ray

A path starts at `(form 'NAME)` or `(line N)`. Navigation skips whitespace and
comments:

- `right` and `left` select the next and previous structural sibling; `up`
  selects the parent and `down` the first structural child.
- `(match :key) right` selects the value paired with a map key.
- `span 2` selects adjacent peers; `partition-all 2` groups the remaining
  sibling run into pairs.
- `outermost` removes selected nodes beneath another selected node. Promote
  nested matches with `up` before `outermost` when owners must be disjoint.
- `initializer` selects a `def` right-hand side without evaluating it.

A `case` clause, `cond` branch, map entry, or binding pair is sibling syntax,
not a synthetic wrapper list. Plain paths return exact source. Add
`expect-count` when cardinality matters, then `analyze` to realize ordinary EDN
from the selected syntax. Use `tree-seq` only when the input shape is unknown.

```clojure
inspect_clojure({
  :requests [{:operation "xray" :file "src/example.clj"
              :expression "(-> (form 'transition) (match '(inc _)) (expect-count 2) (analyze #(mapv second %)))"
              :evidence "full"}]
  :expect {:requests 1 :files 1}})
```

Compact evidence returns the computed value with hashes; `full` also retains
exact matched source. Analysis is pure and capability-limited, not
termination-proof: keep it bounded and never use it to write source or a plan.

## Compile one exact edit batch

When the files, targets, replacements, and counts are known, submit one coherent
`edit_clojure` call rather than splitting the plan into repeated edits. Each
edit has `file`, `from`, and `to`; optional `within` narrows the target to an
owner, and `matches` states its exact cardinality (default 1).

```clojure
edit_clojure({
  :edits [{:file "src/example.clj"
           :within {:form "transition"}
           :from "(old-call x)"
           :to "(new-call\n  x)"
           :matches 1}]
  :preview true})
```

Matching ignores whitespace but keeps comments, metadata, reader syntax, and
token spelling exact. Targets must be disjoint. The complete future source must
parse, and every file is observed at one frozen revision before anything is
staged.

An ordinary call without `:preview true` stages and applies one revision-gated
resource change. Use preview when the exact change should be inspected before
writing: it writes nothing and returns a `proposal-id`. Apply that proposal once
through the ordinary resource boundary:

```clojure
resource({:action "apply" :proposal "PROPOSAL-ID"})
```

A proposal is exact-owner and single-use. Do not infer a commit from a preview,
and do not retry a consumed or stale proposal. Inspect the current source and
create a fresh proposal instead. Count mismatches, overlaps, stale revisions,
protected paths, and invalid future source refuse the batch rather than
partially writing it. After a committed change, run the relevant compile and
test checks.
