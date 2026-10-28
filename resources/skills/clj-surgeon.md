---
name: clj-surgeon
description: Use for advanced structural Clojure work with inspect_clojure, edit_clojure, apply_clojure_changes, and transform_clojure: batched selectors, structural paths and X-ray analysis, exact multi-file transactions, namespace extraction/rename, CLJC operations, bounded computed transforms, and preview/apply workflows. Do not load for ordinary calls covered by the tool schemas.
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

## Apply one heterogeneous transaction

Use `apply_clojure_changes` when a plan mixes files or actions. Do not decompose
it into sequential edits: every selector reads the same frozen snapshot, all
spans must be disjoint, and one resource proposal carries the complete result.
Every change needs an exact `:expect {:matches n}` and the batch needs exact
`:expect {:changes n :edits n :files n}`; add `:each-file` or `:each-form` when
the distribution matters.

```clojure
apply_clojure_changes({
  :changes [{:id :call :in ["src/example.clj"] :forms ['transition]
             :find "(old-call x)" :do [:replace "(new-call x)"]
             :expect {:matches 1 :each-form 1}}
            {:id :helper :in ["src/example.clj"] :forms ['transition]
             :do [:insert-after ["(defn helper [] :ready)"]]
             :expect {:matches 1}}
            {:id :status :in ["src/other.clj"] :find "{:a 1}"
             :do [:assoc-entry {:key ":status" :value ":ready"}]
             :expect {:matches 1 :each-file 1}}]
  :expect {:changes 3 :edits 3 :files 2}
  :preview true})
```

Use `[:insert-before [SOURCE ...]]` and `[:insert-after [SOURCE ...]]` for
siblings, `[:delete true]` with exact named `:forms` for whole owners,
`[:assoc-entry {:key K :value V}]` for a semantic map match, and
`[:rename-binding {:from old :to new :preserve-external-key true}]` for local
bindings. Binding rename changes only the selected owners' local identities;
keywords and qualified symbols are not renamed. Comment-bearing insertion gaps
are ambiguous: replace a larger exact span instead of moving comments by text.

For dependency-aware movement, use one single-file change with the source in
`:forms` and `:do [:move {:before 'destination :with-deps? true}]`. An exact move
refuses newly stranded dependencies or callers; dependency expansion moves the
minimum unresolved closure in topological order and refuses cycles. Attached
comments travel with their forms. Use `:do [:fix-declares true]` without `:forms`
to remove stale declarations and move safe definitions above their first callers;
cyclic or unsafe declarations remain in place. These native operations each form
a single-file transaction and cannot be mixed with ordinary span edits.

## Extract, rename, and convert namespaces

Use one native change per operation. `[:extract {:to "src/example/moved.clj"
:target-ns 'example.moved :require-policy :minimal}]` moves the named `:forms`
into a new namespace and rewrites the source. `:minimal` retains only proved
requirements and refuses ambiguous side-effect-only or comment-bearing require
clauses; `:copy-all` preserves the complete header instead. Set
`:derive-required-public-forms true` when a remaining source caller requires a
moved private `defn-` to become public.

`[:rename-namespace {:from 'old.ns :to 'new.ns}]` rewrites matching namespace
syntax and moves files under the corresponding namespace directory. Supply every
source that may contain a caller in `:in`; the pure plan reads only that frozen
map. For extraction and rename, every residual structural caller candidate must
have a `:caller-decisions` entry of `:review-required` or `:not-a-caller`. These
decisions do not make the evidence semantic-complete, and quoted-Var or bare
symbol candidates still need their stated review.

`[:merge-cljc {:to "shared.cljc"}]` consumes one CLJ and one CLJS input, creates
the CLJC target, and deletes the two inputs. `[:split-cljc {:clj "shared.clj"
:cljs "shared.cljs"}]` creates both platform files and deletes the CLJC input.
`[:add-require {:platform :clj :ns 'clojure.set :as 'set}]` rewrites one CLJC
file; platform `:cljs` adds only there and `:cljc` adds a shared require. Merge,
split, and add-require preserve reader-conditional platform branches, with alias
collisions and unsupported namespace forms refused.

All these operations stage create/write/delete/rename members with their observed
revisions in one resource change. Publication failures use ordinary resource
compensation; there is no separate receipt or undo workflow.

Preview and resource apply have the same exact-owner, single-use lifecycle as
`edit_clojure`. A span-edit transaction's canonical effect identity does not
depend on change or publication order. A refusal, stale revision, output bound,
or failed publication is not a successful commit; inspect the actual resource
status.

## Derive replacements with a pure transform

Use `transform_clojure` when the replacement is computed from selected syntax
rather than already known. End the structural relation in `transform`; the
function receives each selected form as data and must return concrete EDN.
Realize collection results with `vec`, `mapv`, or another concrete constructor.
The relation cannot perform I/O, start processes, load namespaces, mutate host
state, or use host interop.

```clojure
transform_clojure({
  :file "src/example.clj"
  :expression "(-> (form 'retry-policy) (match :timeout) right (transform #(+ % 50)))"
  :expect {:matches 2 :max_changed_characters 6}})
```

`expect.matches` is the exact selection cardinality and
`expect.max_changed_characters` bounds the sum of each replacement's larger
before/after source length. A mismatch, lazy or non-EDN result, excess churn, or
invalid future source refuses before staging. Preview is the default and uses
the same exact-owner proposal and `resource` apply flow as exact edits. Set
`:commit true` only for the already reviewed relation.

A selected subtree containing comments is refused before staging, for preview as
well as commit, because printing the computed form drops those comments and the
proposal would otherwise remain applicable through `resource`. Narrow the
selection to comment-free values, then preview or commit; comments elsewhere in
the file remain unchanged.
