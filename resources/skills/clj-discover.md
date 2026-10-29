---
name: clj-discover
description: Use when Clojure or Jolt code needs an unfamiliar API, unclear interop, or a macro whose generated behavior is not understood
---

# Discover before integrating

Do not guess an unfamiliar API from memory. Check what the project already
uses, choose the route that fits the runtime, then verify the chosen calls in
`eval` before editing source.

## Check dependencies and runtime first

1. Read dependency declarations and search for existing usage. An already-present library and established project convention come first.
2. For JVM Clojure, use `websearch({query: "Clojure wrapper for <library or class>"})` and compare maintained wrappers against direct interop. A wrapper is an option, not an automatic improvement; do not add or upgrade a dependency silently.
3. For Jolt, first check whether `clojure.core`, `clojure.string`, or `jolt.fs` already provides the operation. Jolt has no JVM, so do not assume a Java class or JVM wrapper exists.

For example, filesystem work starts with the native API:

```clojure
(require '[jolt.fs :as fs])
(fs/exists? "deps.edn")
```

When no idiomatic Jolt route exists, probe the actual shim in `eval`. Treat
reflection as incomplete evidence: empty output can mean the image retained
no metadata, not that the class has no methods. Consult official
documentation through `websearch`, then verify the smallest supported call.
If the class is unavailable, use a supported Jolt API rather than writing a
JVM fallback from memory.

## JVM Java interop

When direct interop is appropriate for JVM Clojure, inspect constructors,
static factories, method signatures, and return types with `clojure.reflect`:

```clojure
(require '[clojure.reflect :as r])

(->> (:members (r/reflect java.time.LocalDateTime))
     (filter #(instance? clojure.reflect.Method %))
     (map (juxt :name :parameter-types :return-type))
     (sort-by first)
     vec)
```

Empty reflection output is inconclusive here too. Consult official API
documentation, then verify a minimal static call or constructor in `eval`,
checking argument order and instance/static call shape.

## Macros

Expand the actual form in the project's image instead of explaining its
generated code from memory:

```clojure
(macroexpand-1 '(when ready? (go!)))
;; => (if ready? (do (go!)))

(macroexpand '(when ready? (go!)))
```

Use `macroexpand-1` to inspect the immediate transformation and
`macroexpand` for the recursive expansion. Read which forms receive
evaluation, binding, quoting, or side effects; then verify the behavior with
a small input. If expansion fails, read that error before changing the call.

## Common mistakes

- Jumping to raw interop because a deadline is close, without checking existing dependencies or wrappers.
- Adding a wrapper library merely because one appears in search results.
- Treating empty reflection as an empty API.
- Writing around a macro's supposed expansion without evaluating it.
