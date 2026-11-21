---
name: clj-debug
description: Use when debugging Clojure — something broken, throwing, failing, flaky or slow whose cause one read of the code does not show — and before adding println, prn, tap>, or logging to inspect a value
---

# Debug in the live REPL

The order that finds hard bugs: a loop that goes red on this bug first, then
a minimal repro, then ranked hypotheses, then one probe at a time, then the
fix with its test. Skip a step only when you can say why. Inspect values in
the running image rather than adding logging: a persistent `eval` session
gives the answer in one call and leaves nothing to remove.

## 1. Build a loop that goes red

Most of the effort belongs here. Once one command reliably shows the bug,
everything after it is mechanical; without one, reading code will not save
you. Reach for these in order:

1. `eval`: require the namespace and call the failing function with the real
   inputs. The verdict comes back in one call.
2. A failing test at the seam that reaches the bug.
3. The program run through `shell` on a fixture input, its output diffed
   against a known-good one.
4. A captured payload replayed through the code path on its own.

Make it sharp (check the exact symptom, not "did not throw") and
deterministic (pin time, seed randomness). For a flaky bug, run the trigger
many times in one `eval` until it fails reliably. If no loop can be built,
say so and what you tried, rather than theorising from the source.

## 2. Reproduce, then shrink

Watch it go red and confirm it is the failure you were told about, not a
neighbour. Then cut inputs and steps one at a time, re-running after each,
until everything left matters. That minimal repro becomes the test.

## 3. Hypothesise before probing

Write three to five ranked causes, each with its prediction: "if X is the
cause, changing Y makes the failure go away." A cause with no prediction is
a guess; sharpen it or drop it.

## 4. Probe one variable at a time

1. In `eval`, redefine the function with a temporary `(def debug-value local-value)` in the relevant scope. That definition exists only in the session; never copy it into the source file.
2. Call it with the failing input, then inspect the capture with `keys`, keyword access, `type`, `count`, or a small pure transformation. After an exception, look at `*e` and `(ex-data *e)`.
3. `with-redefs` in `eval` captures or replaces a callee without editing source.
4. Log only where the REPL cannot reach the code path, at a boundary that separates two hypotheses, with a unique prefix (`[DEBUG-a4f2]`) so removing it is one grep.

For something slow, logs mislead: time a baseline first, then bisect.

## 5. Fix with a test that failed first

1. Turn the minimal repro into a failing test and watch it fail.
2. Evaluate the corrected function in `eval` and check it against the failing input and the important boundary cases.
3. Write the correction to the file, reload it, and watch the test pass.
4. Re-run the step 1 loop on the original, unshrunk scenario.

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
(:permissions debug-candidate)        ; => "read"
(type (:permissions debug-candidate)) ; => java.lang.String

;; Prediction: downstream expects a collection, so wrapping a scalar scope
;; fixes it. Evaluate the correction without the debug def, both shapes:
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

## Before you call it done

- The original repro no longer reproduces, and the test passes.
- `grep` for `[DEBUG-` finds nothing; no capture def reached a file.
- The session and the files agree (`repl-workflow`, "Keep the session and
  the files in step").

## Red flags

- “One `println` is faster.” It adds a file edit and a test-run loop before inspection.
- “`def` inside a function is always forbidden here.” It is forbidden in source; this temporary capture lives only in `eval`.
- “I can see the bug from the source.” Then the loop costs one call to confirm it.
- “It works in the REPL, so I am done.” Reload the edited source and prove it with tests.

Load `repl-workflow` for the complete edit–reload–test discipline.
