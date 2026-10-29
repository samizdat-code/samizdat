---
name: clj-debug
description: Use when debugging Clojure or Jolt code, especially before adding println, prn, tap>, or logging to inspect a value
---

# Debug in the live REPL

Inspect the value in the running image before changing source. A persistent
`eval` session gives immediate feedback and leaves no logging to remove. This
works for both `.clj` and `.jolt` sources; Jolt does not need a JVM for capture,
macro expansion, structural inspection, or evaluating a correction.

## Capture, inspect, then correct

1. Read the exception and identify the local value whose shape is uncertain.
2. In `eval`, redefine the function with a temporary `(def debug-value local-value)` in the relevant scope. This definition exists only in the REPL session; never copy it into the source file.
3. Call the function with the failing input. The first eval must redefine and invoke it; do not read the debug var before that call returns. Then inspect the captured value with `keys`, keyword access, `type`, `count`, or a small pure transformation.
4. Form one hypothesis, evaluate the corrected function in `eval`, and verify it with the failing input and important boundary cases.
5. Apply the verified correction to the file, reload it, and run the focused test followed by the required suite. The file and tests are the deliverable; the REPL capture is scratch.

For a function boundary that is easier to intercept, `with-redefs` in `eval` can capture or replace the callee without editing source. Do not add printing merely because that route is unfamiliar.

Use this call order: first `(do (defn prepare-user-with-capture [...] ...) (prepare-user-with-capture failing-record))`; only then `(:permissions debug-candidate)`. Starting with the debug var is always wrong because capture has not happened yet.

## Example

```clojure
(def failing-record {:id 7 :name "Ada" :claims {:scopes "read"}})

;; Source has no debug def. Evaluate this instrumented version only in eval:
(defn normalize-user [record]
  (let [candidate (-> record
                      (select-keys [:id :name])
                      (assoc :permissions (get-in record [:claims :scopes])))]
    (def debug-candidate candidate)
    candidate))

(normalize-user failing-record)
(keys debug-candidate)
(:permissions debug-candidate)       ; => "read"
(type (:permissions debug-candidate)) ; => java.lang.String

;; Hypothesis: downstream expects a collection, not a scalar scope.
;; Evaluate the correction without the debug def and test both input shapes:
(defn normalize-user [record]
  (let [candidate (-> record
                      (select-keys [:id :name])
                      (assoc :permissions (get-in record [:claims :scopes])))]
    (if (string? (:permissions candidate))
      (update candidate :permissions vector)
      candidate)))

(:permissions (normalize-user failing-record)) ; => ["read"]
(:permissions
 (normalize-user (assoc-in failing-record [:claims :scopes] ["read"])))
;; => ["read"]
```

Only after both checks pass, apply the corrected definition to the source
file and run its tests.

## Red flags

- “One `println` is faster.” It adds a file edit and a test-run loop before inspection.
- “`def` inside a function is always forbidden here.” It is forbidden in source; this temporary capture lives only in `eval`.
- “It works in the REPL, so I am done.” Reload the edited source and prove it with tests.

Load `repl-workflow` for the complete edit–reload–test discipline.
