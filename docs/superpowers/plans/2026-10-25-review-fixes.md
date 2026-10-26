# Review Lifecycle and Front-End Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair all ten findings from `karamazov-p2gm` with regression coverage in one reviewed branch and PR.

**Architecture:** Keep scheduler and API changes local, while extracting approval/questionnaire state into a toolkit-free module shared by TUI and GTK. Each task lands a small, independently testable fix; the GUI approval task consumes the shared module rather than introducing another state machine.

**Tech Stack:** Jolt Clojure, EDN manifests/cells, SQLite-backed run state, GTK/Glimmer, FTXUI, ACP JSON-RPC, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-25-review-fixes-design.md`

## Global Constraints

- Work in an isolated branch/worktree; never include the user's `.gitignore` edit, `.beads.gate.lock`, or `.clj-kondo/imports/babashka/`.
- Add no dependencies. Front ends remain strict HTTP clients.
- Keep TUI keyboard and hiccup policy in TUI; only shared approval state moves.
- Use fresh processes for test runs. Do not edit source while the full suite is running.
- New tests go in existing test namespaces where practical. Any new namespace must be registered in both places in `test/samizdat/test_runner.clj`.
- No AI attribution in commits, PR text, beads, or project-visible history.
- Commit each task only after its focused tests pass. Do not push until final verification and review pass.

## Review Focus

- Late HTTP responses after a run switch: they must not alter the newly selected run (Tasks 6, 7, 9, 10).
- Double actions before the first response: only one run or decision may be submitted (Tasks 2, 7, 8).
- Failure during partially acquired resources: every registered resource is released exactly once (Task 1).
- Empty and custom questionnaire answers: cursor state and final answers remain valid (Task 7).
- A refusal after additional typing: the newer draft is preserved (Tasks 9 and 10).

---

### Task 1: Cover all post-row initialization with failure teardown

**Files:**
- Modify: `src/samizdat/agent/beam.clj:1083-1383`
- Modify: `src/samizdat/agent/resume.clj:309-419`
- Test: `test/samizdat/beam_test.clj`
- Test: `test/samizdat/control_test.clj`

**Interfaces:**
- Consumes: existing `beam/run!`, `resume/resume!`, `runs/finish-run!`, `run-state/run-ended!`.
- Produces: no new public API; all post-row failures rethrow after durable failure recording and idempotent cleanup.

- [ ] **Step 1: Write failing fresh-start and resume tests**

Add `an-initialization-crash-is-recorded-and-cleaned-up`: force `repl/new-session` to throw after `on-start` and after `verify/seal-run!`, assert the caught message, `status="failed"`, non-nil `ended_at`, and `verify/sealed?` is false. Add the resume analogue at the same post-seal boundary, proving the failure happens after `mark-running!` but before `beam/run-rounds`.

- [ ] **Step 2: Verify the tests fail**

Run:
`jolt -A:test -e '(require (quote clojure.test) (quote samizdat.beam-test) (quote samizdat.control-test)) (clojure.test/run-tests (quote samizdat.beam-test) (quote samizdat.control-test))'`

Expected: new tests fail with the run still `running` and nil `ended_at`.

- [ ] **Step 3: Implement whole-initialization ownership**

Move the failure/teardown boundary around every post-row step. Track partially acquired eval/project resources so early failure releases them once; normal `run-rounds` retains ownership after entry. Preserve the original exception and `:error` workflow outcome. Begin resume's boundary immediately after `mark-running!`.

- [ ] **Step 4: Verify focused tests pass**

Run the Step 2 command. Expected: both namespaces pass.

- [ ] **Step 5: Commit**

`git add src/samizdat/agent/beam.clj src/samizdat/agent/resume.clj test/samizdat/beam_test.clj test/samizdat/control_test.clj && git commit -m "fix: own failures throughout run initialization"`

---

### Task 2: Cancel late startup after the API deadline

**Files:**
- Modify: `src/samizdat/api/control.clj:163-228`
- Modify: `resources/prompts/run-start-timeout.md`
- Test: `test/samizdat/control_test.clj`

**Interfaces:**
- Consumes: Task 1's initialization ownership.
- Produces: timeout sets `abort` and cancels the task; `on-start` closes a late row as aborted before delivering its id.

- [ ] **Step 1: Write the failing delayed-start test**

Use a deferred fake `beam/run!`, a 20 ms start deadline, and a real run row. Return from `start-run!`, assert 503, then release startup. Assert no active running row remains and no turn executes. Add a never-created-row case to check the cancel path does not throw.

- [ ] **Step 2: Verify the test fails**

Run:
`jolt -A:test -e '(require (quote clojure.test) (quote samizdat.control-test)) (clojure.test/run-tests (quote samizdat.control-test))'`

Expected: the delayed run proceeds as active/running after the 503.

- [ ] **Step 3: Implement cancellation and truthful timeout text**

Set the existing abort atom and invoke the spawned canceller on timeout. In `on-start`, close a late row as aborted when that atom is already set. Update the prompt to say startup was cancelled after the deadline.

- [ ] **Step 4: Verify focused tests pass**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

`git add src/samizdat/api/control.clj resources/prompts/run-start-timeout.md test/samizdat/control_test.clj && git commit -m "fix: cancel runs that miss the start deadline"`

---

### Task 3: Record single-branch exhaustion accurately

**Files:**
- Modify: `resources/cells/loop.clj:433-440`
- Test: `test/samizdat/control_test.clj`

**Interfaces:**
- Consumes: existing `:loop/finish` handler and `agent/stop-reason`.
- Produces: durable `exhausted` status and ACP `max_turn_requests`.

- [ ] **Step 1: Write the failing cross-driver status test**

Invoke the real `:loop/finish` handler with `{:verdict :exhausted :turn 3}`, assert the run row is `exhausted`, and assert `(agent/stop-reason "exhausted")` is `max_turn_requests`.

- [ ] **Step 2: Verify failure**

Run `samizdat.control-test`. Expected: row status is `failed`.

- [ ] **Step 3: Change the finish status**

Replace only the exhausted run-row status with `:exhausted`, leaving the returned verdict and residual intact.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.control-test`, then:
`git add resources/cells/loop.clj test/samizdat/control_test.clj && git commit -m "fix: record budget exhaustion as exhausted"`

---

### Task 4: Release approvals after every resumed ending

**Files:**
- Modify: `src/samizdat/api/control.clj:328-348`
- Test: `test/samizdat/control_test.clj`

**Interfaces:**
- Consumes: `approval/request!`, `approval/abandon!`, resumed task registry.
- Produces: `finally` cleanup of `active` and all resumed-run approvals.

- [ ] **Step 1: Write success and throw regression tests**

Create an approval inside fake `resume/resume!`. After normal completion and after a thrown failure, assert `approval/pending` is empty and `active` has no entry. Use long enough waits to prove the driver ended, not merely that the HTTP response arrived.

- [ ] **Step 2: Verify failure**

Run `samizdat.control-test`. Expected: the approval remains pending.

- [ ] **Step 3: Add finally cleanup**

Ensure both active-registry removal and `approval/abandon!` run on success and throw, preserving the result/error value.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.control-test`, then:
`git add src/samizdat/api/control.clj test/samizdat/control_test.clj && git commit -m "fix: release approvals when resumed runs end"`

---

### Task 5: End ACP runs on cancellation

**Files:**
- Modify: `acp/samizdat/acp/agent.clj:160-179`
- Test: `test/samizdat/acp_test.clj`

**Interfaces:**
- Consumes: existing injected ACP IO and session atom.
- Produces: cancelled sessions have `:ended? true`; the next prompt starts a new run.

- [ ] **Step 1: Write cancel-then-next-prompt test**

Cancel the first prompt before a finish event, assert `cancelled` and `:ended? true`, then issue a second prompt and assert a new run starts and no intervention is sent to the old run.

- [ ] **Step 2: Verify failure**

Run `samizdat.acp-test`. Expected: old session has `:ended? false` and receives the next prompt as an intervention.

- [ ] **Step 3: Mark cancellation ended**

Assoc `:ended? true` in the cancellation branch; do not retain a partial answer as context.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.acp-test`, then:
`git add acp/samizdat/acp/agent.clj test/samizdat/acp_test.clj && git commit -m "fix: end ACP runs on cancellation"`

---

### Task 6: Share approval state and isolate TUI runs

**Files:**
- Create: `src/samizdat/approval_view.clj`
- Modify: `tui/samizdat/tui/state.clj`
- Test: `test/samizdat/tui_state_test.clj`

**Interfaces:**
- Consumes: existing TUI approval state keys.
- Produces: `approval-view/apply-approvals`, `reset-run`, `current-question`, `answer-question`, `move-option`, `toggle-option`, `start-reply`, `cancel-reply`, and `reply-kind`, all pure and operating on the existing state map. `reset-run` clears approval/question/reply/live/fold state.

- [ ] **Step 1: Write failing run-isolation tests**

After selecting a new run, assert approvals are empty, no old live delta remains, and `dialog-action` cannot decide the old id. Feed an event carrying the old `run_id` and assert no cursor or wanted-refresh changes. Keep existing questionnaire tests green as the delegation contract.

- [ ] **Step 2: Verify isolation tests fail**

Run `samizdat.tui-state-test`. Expected: old approval and live state remain actionable/visible.

- [ ] **Step 3: Extract shared transitions and add reset/filtering**

Move the approval-specific transitions without changing their semantics. Delegate TUI wrappers to them. Clear run-scoped state in `select-run` and drop mismatched run events before cursor updates. `reset-run` must compose with `select-run` without resetting unrelated draft/history/project state.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.tui-state-test`, then:
`git add src/samizdat/approval_view.clj tui/samizdat/tui/state.clj test/samizdat/tui_state_test.clj && git commit -m "fix: share approval state and isolate TUI runs"`

---

### Task 7: Add GTK approvals using the shared state

**Files:**
- Create: `gui/samizdat/gui/approval.clj` for toolkit-free approval hiccup and effect coordination.
- Create: `test/samizdat/gui_approval_test.clj`.
- Modify: `test/samizdat/test_runner.clj` in both the require list and namespaces vector.
- Modify: `gui/samizdat/gui/api.clj`
- Modify: `gui/samizdat/gui/core.clj`
- Modify: `src/samizdat/approval_view.clj` only if GTK exposes a genuinely shared gap.

**Interfaces:**
- Consumes: Task 6's pure transitions and `client/approvals`, `client/decide!`.
- Produces: `gui.approval/view`, plus asynchronous refresh/decision functions taking explicit state and HTTP seams; the pending-question view supports permission, single-select, multi-select, custom answers, notes, and rejection.

- [ ] **Step 1: Write failing GUI approval data tests**

In `samizdat.gui-approval-test`, pin initial connection/event refresh, stale-response dropping, double-decision suppression, full-input hiccup, and questionnaire progression. Include custom answers and an empty multi-select confirmation, which must not submit an empty answer set. Register the namespace in both runner locations.

- [ ] **Step 2: Verify tests fail**

Run `samizdat.gui-approval-test`. Expected: `samizdat.gui.approval` and its API are absent.

- [ ] **Step 3: Implement toolkit-free coordination and GTK rendering**

Fetch approvals on connection and approval events with run-id guards. Disable duplicate decisions while pending. Keep hiccup and effect coordination in `gui.approval`; `gui.core` only supplies state, HTTP, and containers. Render the approval view in place of the normal body, preserving full input/details/gaps. Use Task 6 transitions for all cursor and answer changes; failures leave the question and draft visible.

- [ ] **Step 4: Verify data tests and namespace loading**

Run `samizdat.gui-approval-test`, then:
`jolt -A:gui -e '(require (quote samizdat.gui.core))'`

Expected: tests pass and the namespace loads without a display.

- [ ] **Step 5: Commit**

`git add gui/samizdat/gui/api.clj gui/samizdat/gui/approval.clj gui/samizdat/gui/core.clj src/samizdat/approval_view.clj test/samizdat/gui_approval_test.clj test/samizdat/test_runner.clj && git commit -m "feat: answer approvals from the GUI"`

---

### Task 8: Suppress duplicate new-run submissions

**Files:**
- Modify: `tui/samizdat/tui/core.clj`
- Modify: `tui/samizdat/tui/state.clj`
- Modify: `gui/samizdat/gui/core.clj`
- Modify: `gui/samizdat/gui/ops.clj` for pure start/draft transition helpers.
- Test: `test/samizdat/tui_state_test.clj`
- Test: `test/samizdat/gui_approval_test.clj`

**Interfaces:**
- Consumes: Task 2's truthful server timeout and existing async start calls.
- Produces: `:starting?` lifecycle in both front ends; `apply-start` and GUI ops helpers always clear it.

- [ ] **Step 1: Write deferred double-start tests**

Hold the first start response, invoke start again through the toolkit-free coordination seam, and assert one HTTP call. Resolve with success and failure separately and assert the guard clears in both cases.

- [ ] **Step 2: Verify failure**

Run the TUI/GUI data tests. Expected: two requests are issued.

- [ ] **Step 3: Implement in-flight guards**

Set `:starting?` before the request, reject repeat invocation, clear it on every outcome, and make the GUI button insensitive when supported. Preserve the current failure-draft behavior.

- [ ] **Step 4: Verify pass and commit**

Run both test sets, then:
`git add tui/samizdat/tui/core.clj tui/samizdat/tui/state.clj gui/samizdat/gui/core.clj gui/samizdat/gui/ops.clj test/samizdat/tui_state_test.clj test/samizdat/gui_approval_test.clj && git commit -m "fix: suppress duplicate run submissions"`

---

### Task 9: Move TUI actions off the UI thread

**Files:**
- Create: `tui/samizdat/tui/actions.clj` for toolkit-free asynchronous action coordination.
- Create: `test/samizdat/tui_actions_test.clj`, registered in both runner locations.
- Modify: `test/samizdat/test_runner.clj`
- Modify: `tui/samizdat/tui/core.clj`
- Modify: `tui/samizdat/tui/state.clj` for a pure clear-sent-draft helper.

**Interfaces:**
- Consumes: existing future idiom and run-id selection state.
- Produces: `actions/steer!`, `abort!`, `resume!`, `decide!`, and `intervene!`, taking `{:state atom :client {...} :poll! fn :say! fn}` plus their existing arguments. Responses are dropped after a run change; newer drafts are preserved.

- [ ] **Step 1: Write stale-response and draft tests**

In `samizdat.tui-actions-test`, defer steer/abort/decision responses, change selection or append text before resolving them, and assert the old response cannot clear an error/draft or mutate the new run. A successful unchanged steer clears only its own text. Register the namespace in both runner locations.

- [ ] **Step 2: Verify failure**

Run `samizdat.tui-actions-test`. Expected: the coordination namespace is absent; the old synchronous core cannot satisfy the deferred contract.

- [ ] **Step 3: Implement and wire asynchronous actions**

Use futures in `tui.actions` for all listed HTTP calls. Capture run id; apply responses only when it still matches. Clear steering input only when its current expanded value equals the sent value. Replace core's synchronous bodies with delegation to this module.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.tui-actions-test` and `samizdat.tui-state-test`, then:
`git add tui/samizdat/tui/actions.clj tui/samizdat/tui/core.clj tui/samizdat/tui/state.clj test/samizdat/tui_actions_test.clj test/samizdat/tui_state_test.clj test/samizdat/test_runner.clj && git commit -m "fix: keep TUI actions responsive"`

---

### Task 10: Preserve GUI drafts on refusal

**Files:**
- Modify: `gui/samizdat/gui/core.clj:230-252`
- Modify: `gui/samizdat/gui/ops.clj`
- Test: `test/samizdat/gui_approval_test.clj`

**Interfaces:**
- Consumes: async GUI intervention effect and GUI ops transitions.
- Produces: success clears only the unchanged sent draft; refusal always preserves text.

- [ ] **Step 1: Write refusal and newer-draft tests**

Return 409 for a finished run and assert the original draft remains. Return success after additional typing and assert only the sent draft is cleared, not the newer text.

- [ ] **Step 2: Verify failure**

Run `samizdat.gui-approval-test`. Expected: text is cleared before the response.

- [ ] **Step 3: Implement conditional draft clearing**

Keep the draft in flight; clear on success only if it still equals the sent draft. Leave it untouched on every error.

- [ ] **Step 4: Verify pass and commit**

Run `samizdat.gui-approval-test`, then:
`git add gui/samizdat/gui/core.clj gui/samizdat/gui/ops.clj test/samizdat/gui_approval_test.clj && git commit -m "fix: preserve GUI directives on refusal"`

---

### Task 11: Whole-branch review and PR

**Files:**
- Review all Task files and the design/plan documents.
- No production changes unless a verified review finding requires one.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: verified branch, review findings resolved, and one PR to `yogthos/samizdat:main`.

- [ ] **Step 1: Run final verification**

Run sequentially on the unchanged tree:

```bash
jolt test
jolt tui-test
jolt -A:gui -e '(require (quote samizdat.gui.core))'
git diff --check
```

Expected: full suite and TUI suite report zero failures/errors, GUI loads, and diff check is empty.

- [ ] **Step 2: Request whole-branch code review**

Use the requesting-code-review workflow. Check every finding against its regression test and inspect startup cleanup, stale-response guards, and approval state delegation. Fix and re-verify any confirmed issue.

- [ ] **Step 3: Close/update beads accurately**

Each of `karamazov-p2gm.1` through `.10` gets its implemented fix and test evidence. Close only verified items with `--reason`; leave the parent open until all children are closed, then close it with the suite evidence.

- [ ] **Step 4: Commit documentation and inspect branch**

Ensure the design and plan are committed. Confirm `git status --short` is clean and `git diff main...HEAD --stat` contains only intended files. No user's pre-existing changes may appear.

- [ ] **Step 5: Push and open the PR**

Push the branch and create one PR with a concise summary of the ten fixes, the shared approval design, and exact test results. Use no attribution or session links. Return the branch name and PR URL.
