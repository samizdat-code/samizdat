# Test-environment findings — agent-roles branch, context-accounting suite

Ran `jolt test` (full suite) on branch `agent-roles`, tree otherwise clean
(only `notes/` untracked). Result, verbatim from the run's log tail:

    Ran 3775 tests. 19832 assertions passed, 83 failures, 10 errors.

## The discriminator

A failing path that crosses the project-image **spawn** is environmental and
not a wiring bug. A failing path that does **not** cross the spawn is a real
bug and belongs in a bead.

The spawn fails in this confinement because the child cannot apply its
sandbox profile. Verbatim denial lines from the run log:

    sandbox-exec: sandbox_apply: Operation not permitted
    ERROR samizdat.repl.image - project image did not come up on 58288 (attempt 1, waited up to 20000 ms, child exited 71) — profile at /var/folders/4k/.../image.profile
    sandbox-exec: sandbox_apply: Operation not permitted
    ERROR samizdat.repl.image - project image did not come up on 58291 (attempt 2, waited up to 20000 ms, child exited 71) — profile at /var/folders/4k/.../image.profile

`sandbox_apply: Operation not permitted` means the sandboxed child itself was
refused the right to install its profile, so it exits 71 before serving. The
image never comes up, and everything downstream that needs an image to answer
deadlines instead of returning a value.

## What that explains

`eval_deadline_test` fails at three lines, all of them after a spawn attempt:

    82:FAIL in (a-runaway-image-eval-is-interrupted-and-the-image-survives) (eval_deadline_test.clj:78)
    93:FAIL in (a-runaway-image-eval-is-interrupted-and-the-image-survives) (eval_deadline_test.clj:82)
    97:FAIL in (a-runaway-image-eval-is-interrupted-and-the-image-survives) (eval_deadline_test.clj:83)

These assert on what a live image does when an eval overruns its deadline.
With no image, there is no deadline to overrun — the failure is the missing
child, not the deadline logic.

## What still passes, and why that matters

**In-process namespace tests DO pass.** The parts of the suite that exercise
the same code through ordinary in-process calls — loading namespaces, calling
functions directly, asserting on returned data — are green. So the
context-accounting code under test is reachable and correct on the paths that
do not need a spawned image; only the spawn-crossing paths are dark.

## Conclusion for this verification task

The suite is **not** green as a whole: 83 failures and 10 errors, concentrated
in the nine namespaces that stand up a project image to test against
(`repl_project_test`, `repl_confinement_test`, `confine_test`,
`sandbox_test`, `image_test`, `eval_deadline_test`, `lsp_test`,
`policy_test`, `inference_environment_test`). Each of those was checked against
the discriminator: every failure inspected traces back to an image that never
started, or to a spawn that was refused, not to a wrong branch of the logic.

No test file was modified. This note is the record; anything that turns out to
cross no spawn gets its own bead rather than a silent fix.
