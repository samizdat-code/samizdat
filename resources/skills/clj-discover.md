---
name: clj-discover
description: Use when Clojure code needs an unfamiliar Java class, unclear interop, or a macro whose generated behavior is not understood
---

# Discover before integrating

Do not guess an unfamiliar API from memory. Check what the project already
uses, look for an idiomatic Clojure route, then verify the chosen calls in
`eval` before editing source.

## Java and existing libraries

1. Read the project's dependency declarations and search for existing usage of the class or related wrappers. An already-present library and established project convention come first.
2. If the class is unfamiliar, use `websearch({query: "Clojure wrapper for <library or class>"})` and compare maintained wrappers against direct interop. A wrapper is an option, not an automatic improvement; do not add or upgrade a dependency silently.
3. When direct interop is appropriate, inspect its constructors, static factories, method signatures, and return types in `eval` with `clojure.reflect`. Verify a minimal call before integrating it.

```clojure
(require '[clojure.reflect :as r])

(->> (:members (r/reflect java.time.LocalDateTime))
     (filter #(instance? clojure.reflect.Method %))
     (map (juxt :name :parameter-types :return-type))
     (sort-by first)
     vec)
```

Empty reflection output is inconclusive: some images do not retain metadata
for every JDK class. It does not prove the class has no methods. Consult the
class's official API documentation through `websearch`, then test the
smallest static call or constructor in `eval`. Check argument order and
instance/static call shape rather than assuming them.

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
