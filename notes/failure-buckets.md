# Failure buckets — agent-roles suite, run of Nov 4

Source: the `jolt test` run whose tail B1 recorded in
`notes/environment-findings.md` — 3775 tests, 19832 assertions passed,
**83 failures, 10 errors**. The failure list (`FAIL in (...)` lines, taken from
the run log and saved as `/tmp/fails.txt`) holds **81 named TestName/line
entries**, so some tests are reported at more than one assertion.

A test is bucketed **spawn** when the failure it reports is downstream of a
project-image or sandbox spawn that was refused. It is bucketed **real** when
the assertion it failed on is reachable without a spawn having to succeed.

## The spawn denial, verbatim

    sandbox-exec: sandbox_apply: Operation not permitted
    ERROR samizdat.repl.image - project image did not come up on 58288 (attempt 1, waited up to 20000 ms, child exited 71)
    sandbox-exec: sandbox_apply: Operation not permitted
    ERROR samizdat.repl.image - project image did not come up on 58291 (attempt 2, waited up to 20000 ms, child exited 71)

Exit 71 before serving: the child could not install its profile, so no image
ever came up.

## Bucket 1 — spawn-crossing (environmental)

Every failing test in these namespaces drives a `jolt` child in a sandbox or
stands up a project image to answer through. Nothing upstream of the spawn
failed; the assertions are on what a live child/image does.

| namespace | failing tests (TestName @ line) |
| --- | --- |
| `confine_test` | `a-sandboxed-command-builds-in-its-own-caches-and-not-the-harnesss`; `a-sandboxed-command-runs`; `gh-uses-its-confined-config-instead-of-the-operators` |
| `image_test` | `a-sandboxed-image-works-on-the-project-and-cannot-leave-it`; `an-abandoned-eval-does-not-poison-the-next-one`; `concurrent-evals-do-not-read-each-others-replies`; `stopping-an-image-kills-it-and-is-idempotent` |
| `repl_confinement_test` | `a-sandbox-refusal-does-not-read-as-a-defect`; `eval-resolves-relative-paths-against-the-run-root` (×2 lines); `the-project-can-still-read-itself`; `the-project-image-can-require-what-the-project-declares`; `the-project-image-never-receives-the-harness-secrets` (×2 lines) |
| `repl_project_test` | all 37 entries: the whole namespace exercises a bridge whose every case needs a live image (`a-fork-carries-the-parents-defs-into-the-image`, `a-poll-the-image-cannot-answer-does-not-outlive-the-eval`, `a-refused-call-fails-the-form-that-made-it`, `a-tool-called-from-eval-comes-back-as-a-value`, `a-typo-is-an-error-not-an-unbound-value`, `an-exit-is-refused-and-the-session-survives`, `an-ns-form-moves-the-rest-of-the-batch`, `concurrent-branches-each-answer-their-own-calls`, `doc-and-complete-see-the-sessions-own-defs`, `each-form-is-read-after-the-one-before-it-ran`, `end-to-end-a-real-read-through-the-bridge`, `the-session-lists-its-own-defs-without-touching-their-values`, `a-failing-form-says-which-where-and-what-already-ran`) |
| `sandbox_test` | `a-sandboxed-shell-cannot-write-the-workflow`; `a-sandboxed-shell-works-in-the-project-and-cannot-leave-it` |
| `eval_deadline_test` | `a-runaway-image-eval-is-interrupted-and-the-image-survives` @78, @82, @83 — three assertion failures of one test, all after the deadline eval was handed to an image |
| `inference_environment_test` | `root-program-runs-through-the-real-project-image-bridge` |
| `lsp_test` | `real-clojure-lsp-renames-across-files-and-refreshes-diagnostics` |

## Bucket 2 — NOT spawn-crossing (real defects)

`policy_test` is the exception and it is a real one. `spec-the-shell-tool-gates
every-command` fails at **six** separate assertion lines (253, 254, 268, 274,
276, 293, 294 — seven lines) plus `an-allowed-command-is-still-confined-by-the-os`
at 584. These assert on the gate decision the shell tool makes **before** any
command is run — a pure policy question the sandbox has no say in. A refusal of
the spawn cannot make a gate spec disagree with itself.

    namespace            test                                           line
    policy_test          spec-the-shell-tool-gates-every-command         253
    policy_test          spec-the-shell-tool-gates-every-command         254
    policy_test          spec-the-shell-tool-gates-every-command         268
    policy_test          spec-the-shell-tool-gates-every-command         274
    policy_test          spec-the-shell-tool-gates-every-command         276
    policy_test          spec-the-shell-tool-gates-every-command         293
    policy_test          spec-the-shell-tool-gates-every-command         294
    policy_test          an-allowed-command-is-still-confined-by-the-os      584

`an-allowed-command-is-still-confined-by-the-os` is the harder of the two: it
runs a command and then asks the OS what it was confined to, so it may sit on
the spawn boundary. It is listed here rather than in bucket 1 because its
assertion is about the CONFINEMENT the harness requested, which the tool
records before the child starts.

## Beads opened for non-spawn failures

- **karamazov-461w** — `policy_test/spec-the-shell-tool-gates-every-command`,
  seven assertion failures (253, 254, 268, 274, 276, 293, 294) in the shell
  gate spec. One test, so probably one root cause: a gate change that moved
  several expectations. Needs the spec read beside
  `src/samizdat/tool/policy.clj` to see which cases disagree.
- **karamazov-iy61** — `policy_test/an-allowed-command-is-still-confined-by-
  the-os` @584. Sits on the spawn boundary, so it needs one run with
  `sandbox_apply` permitted before the bucket-2 assignment is confirmed.

## What this note does NOT claim

The bucket-1 assignments rest on the failure text plus the structure of each
namespace (every test in them takes the spawn path). They are not a per-test
trace: the run's log was kept as a tail, not per-test output, so a bucket-1
test that ALSO has a real defect behind the spawn would not show here. The
honest next step is a run with `--report` per test on a machine where
`sandbox_apply` is permitted.
