# Review lifecycle and front-end fixes design

## Intent

Repair all ten findings from review `karamazov-p2gm` in one branch and one PR.
The work covers run startup and teardown, consistent terminal states, approval
cleanup, ACP session cancellation, and TUI/GUI interaction correctness. Every
behavior repaired gets a focused regression test before its implementation.
The existing suite is green, so the new tests must demonstrate the old failure
before they can be trusted.

## Goals

- A run that throws anywhere after its row exists is marked failed and releases
  its run-scoped resources instead of remaining `running` forever.
- A startup deadline has one truthful ownership outcome: the late run cannot
  continue after the API has refused the start.
- Budget exhaustion has the same durable status in every driver.
- Ending a resumed run releases its approval requests and waiters.
- ACP, TUI, and GUI actions apply to the run and question the user currently
  sees, never to stale state left by an earlier selection.
- Run creation and approval decisions are safe against double submission.
- GUI users can answer permission requests and structured questionnaires.
- Slow HTTP does not freeze the TUI, and refused GUI directives preserve the
  user's text.

## Non-goals and anti-goals

- No redesign of the beam scheduler, intervention protocol, provider client,
  or ACP protocol.
- No new dependencies and no changes to provider behavior or approval policy.
- No broad TUI rewrite. Its keyboard policy and presentation stay in place;
  only the approval/questionnaire state that both front ends need is shared.
- No hiding of errors behind optimistic UI. A timeout, refusal, or disconnect
  must have a truthful visible result.
- No unrelated cleanup or changes to the user's existing `.gitignore` edit or
  untracked files.

## Constraints

- `src/` remains mechanism; behavior and wording stay in resources where the
  repository already makes them configurable.
- Both front ends remain strict HTTP clients. Shared code may be toolkit-free,
  but may not access the server database or userspace directly.
- The shared approval module must be loadable by `:tui`, `:gui`, and `:test`.
- New test namespaces, if any, must be registered in both places in
  `test/samizdat/test_runner.clj`. Prefer extending existing namespaces.
- No AI attribution in commits, PR text, beads, or other project-visible text.
- Work occurs on a new branch isolated from the pre-existing working-tree
  changes. Commit and PR creation are explicitly requested.

## Approaches

### Surgical fixes only

Add a small GUI-only approval implementation and leave TUI state untouched.
This minimizes the edited lines but keeps two implementations of question
cursor, selection, answer accumulation, reply, and run-reset behavior. The user
did not choose this approach.

### Shared approval refactor — chosen

Extract the toolkit-free approval/questionnaire state from `tui.state` into a
small shared module. TUI keeps its keymap and hiccup rendering; GUI gets GTK
rendering and HTTP wiring over the same state transitions. This costs a wider
change than a GUI-only panel, but removes the duplicated behavior that made the
missing GUI surface harder to add correctly. The rest of the findings remain
small, local fixes.

## Design

### 1. Startup and resume failure ownership

Restructure `samizdat.agent.beam/run!` so failure handling covers every step
after `runs/start-run!`, including seeding, root setup, sealing, session
creation, initial branch creation, and post-round outcome recording. The
failure path journals the original error where possible, marks the run failed
only if it is still running, records an error rather than a workflow failure,
and rethrows. Teardown is idempotent and covers early failures as well as a
running `run-rounds` driver: run-scoped state, the project image, and any eval
session created before the failure.

Apply the same ownership boundary in `samizdat.agent.resume/resume!` beginning
immediately after `mark-running!`. Failure before `beam/run-rounds` must not
leave the row running or skip `run-state/run-ended!`.

Tests force failures before `run-rounds` in both drivers and assert the run is
terminal, `ended_at` is set, and run-scoped seals are released. A control-path
test verifies that initialization failure no longer masquerades as an active
run.

### 2. Startup deadline

When `api.control/start-run!` reaches `:run-start-deadline-ms` without an id,
it sets the run's abort flag and cancels the spawned task. The `on-start`
callback checks that flag after the row is created and marks a late row aborted
before answering anyone still waiting. Thus a late callback cannot produce a
running run after the caller has received a refusal. The timeout prose says the
start was cancelled, not merely that no run started.

Tests cover both a delayed `on-start` and a start that never creates a row. The
late row, when one exists, is aborted and cannot spend on subsequent turns.

### 3. Consistent exhaustion status

Change `resources/cells/loop.clj` so `:exhausted` writes `:exhausted` to the
run row. A cross-driver regression test compares the single-branch finish cell
with the beam's durable status and checks ACP maps it to `max_turn_requests`.

### 4. Resume approval cleanup

Put resumed-driver active-registry removal and `approval/abandon!` in a
`finally` covering success and throw. Tests create an approval inside a fake
resumed run and verify it is released on both endings, matching the ordinary
start driver.

### 5. ACP cancellation

On `cancelled?`, `run-prompt!` marks the current run ended before returning
`{:stopReason "cancelled"}`. It retains no partial answer as conversation
context. The next prompt starts a new run. A regression test performs cancel,
then another prompt, and checks the run ids and effects.

### 6. TUI run isolation

`select-run` clears every run-scoped approval/question/reply/live-preview value
and stale fold/cancel state. `apply-event` drops an event whose `run_id` does
not match the selected run before moving cursors or requesting refreshes.
Tests extend the existing reset test and simulate an old stream event arriving
after selection.

### 7. Shared approval state and GUI surface

Add a toolkit-free `samizdat.approval-view` namespace under `src/` containing
the state transitions currently owned by `tui.state`: applying a new approval
list, resetting on head/run change, current-question selection, option movement
and toggling, custom/deny replies, answer accumulation, and completion. It
operates on the existing state keys, so the TUI can delegate without changing
its user-facing keyboard behavior.

TUI keyboard dispatch and hiccup remain in `tui.state` and `tui.widgets`.
Existing TUI tests continue to pin them and are moved or supplemented to pin
the shared transitions directly.

GUI additions:

- API aliases for approvals and decisions in `gui.api`.
- `approvals` and approval-decision pending state in `gui.core`.
- A single-flight refresh on initial run connection and approval events,
  guarded by the requested run id so late responses cannot cross runs.
- An approval view shown instead of the ordinary body while a question is
  pending. It displays the full command/input, reason, details, gaps, and
  allow-always scope without clipping.
- Permission buttons: allow once, allow always only when offered, deny, and
  deny with a note. Decisions run asynchronously and are guarded against double
  submission; failures leave the question visible.
- Questionnaire rendering one question at a time: option buttons for single
  select, toggle buttons plus confirm for multi-select, custom-answer entry,
  and reject. The shared answer/cursor state drives progression and final
  submission.
- The graph and polling continue underneath. Run changes clear approval state
  through the same shared reset used by TUI.

Pure GUI tests cover request/response and rendering decisions without GTK.
Existing TUI tests cover the shared transitions. A `:gui` namespace-load check
will verify toolkit wiring; no display-dependent test is added.

### 8. Duplicate new-run submission

Add `:starting?` to both front ends. Set it before the HTTP call and clear it
on every outcome. The action handler refuses a second invocation while true;
the GUI also presents the button as insensitive when supported. `apply-start`
clears the flag on success and failure, preserving the statement only on
failure as before. Tests invoke the second start before resolving the first
and assert one HTTP request.

### 9. Non-blocking TUI actions and draft preservation

Move steering, abort, resume, decision, and slash-command intervention HTTP to
background tasks, using the existing future idiom. Capture the run id and drop
responses that arrive after selection changes. On successful steering, clear
the input only if it still equals the sent draft, so an earlier response cannot
erase newer typing. Decision failure leaves the current question available.

GUI intervention submission follows the same rule: retain the draft while the
request is in flight and on refusal; clear it after success only when it has
not changed. Tests use deferred fake HTTP effects to cover stale responses,
double actions, and refused submissions.

## Verification

1. Each regression test fails against the old behavior before its fix.
2. Focused fresh-process test runs for every edited namespace.
3. `jolt test` on the final tree.
4. `jolt tui-test` on the final tree.
5. A `:gui` namespace-load check for the new GTK wiring.
6. `git diff --check`, review of the complete diff, and confirmation that no
   pre-existing user changes entered the branch.
7. Request a code review before committing and opening the requested PR.

## Builder latitude

The builder may choose the smallest resource-registration mechanism needed to
make early-start teardown idempotent, and the exact GTK container arrangement
for the approval view, provided the full input and all actions remain visible.
The builder should not change the public API shapes, introduce a second
approval state machine, or broaden the PR beyond these findings.
