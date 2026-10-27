# GTK GUI Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the GTK GUI and its tests into a standalone `samizdat-gui` project, remove GUI-specific wiring from Samizdat, push the new repository, and open a core pull request.

**Architecture:** `samizdat-gui` is a strict HTTP client and a Jolt application with its own source, tests, native dependencies, and CI. It reuses the shared HTTP client and approval transitions through a full-SHA Samizdat Git dependency; Samizdat retains those shared mechanisms but no GTK application.

**Tech Stack:** Jolt/Clojure, GTK4, OpenGL, Glimmer, GitHub Actions, Git.

**Spec:** `docs/superpowers/specs/2026-10-27-gtk-gui-extraction-design.md`

## Global Constraints

- `$CORE` is `/Users/yogthos/src/samizdat-code/samizdat-gui-extraction`, on branch `extract-gtk-gui` based on `origin/main`.
- `$GUI` is `/Users/yogthos/src/samizdat-code/samizdat-gui`, whose empty `main` branch tracks `https://github.com/samizdat-code/samizdat-gui.git`.
- Use Jolt 0.8.22 or newer and full-SHA Git dependencies; deliver no `:local/root` override.
- Pin Samizdat to `97727796fc245f918f10b0db52537562aacb80dc` in the initial standalone project.
- Do not change GUI behaviour, shared client code, TUI/ACP behaviour, or historical documents.
- Do not touch the original checkout at `/Users/yogthos/src/samizdat-code/samizdat` or its uncommitted files.
- Commit as `Yogthos <yogthos@gmail.com>` with no AI attribution.
- Run full suites only on trees that are not being edited concurrently.

## Review Focus

- A fresh standalone checkout must resolve every shared and native dependency; pin completeness is checked by Tasks 2 and 3.
- A moved test must not silently disappear from either runner; exact namespace counts are checked in Tasks 2 and 4.
- Native GTK/OpenGL loading must work without a display; cold-load and core-load checks are owned by Task 3.
- Current documentation must not send core users to a removed command or alias; current-tree reference sweeps are owned by Tasks 4 and 5.
- The original checkout and its unrelated edits must survive unchanged; final status comparison is owned by Task 6.

---

### Task 1: Record the approved design and plan

**Files:**
- Create: `$CORE/docs/superpowers/specs/2026-10-27-gtk-gui-extraction-design.md`
- Create: `$CORE/docs/superpowers/plans/2026-10-27-gtk-gui-extraction.md`

**Interfaces:**
- Consumes: the approved standalone design.
- Produces: versioned design and execution context for the core pull request.

- [ ] **Step 1: Confirm the isolated branch and clean baseline**

Run: `git -C "$CORE" status --short --branch`
Expected: only the two new design/plan documents are untracked; the branch is `extract-gtk-gui`. Record the already-running clean baseline suite result before any source edit.

- [ ] **Step 2: Commit the design and plan**

```bash
git -C "$CORE" add docs/superpowers/specs/2026-10-27-gtk-gui-extraction-design.md docs/superpowers/plans/2026-10-27-gtk-gui-extraction.md
git -C "$CORE" -c user.name=Yogthos -c user.email=yogthos@gmail.com commit -m "docs: design GTK GUI extraction"
```

### Task 2: Import the standalone GUI and its tests

**Files:**
- Create: `$GUI/deps.edn`
- Create: `$GUI/src/samizdat/gui/{api,approval,core,glpane,graph,input,mathtext,newrun,ops,style,textview}.clj`
- Create: `$GUI/test/samizdat/gui_{api,approval,graph,input,mathtext,newrun,ops,style}_test.clj`
- Create: `$GUI/test/samizdat/test_runner.clj`
- Create: `$GUI/{README.md,LICENSE,.gitignore}`

**Interfaces:**
- Consumes: the eleven existing GUI namespaces and eight existing GUI test namespaces from `$CORE` at the base revision.
- Produces: `jolt gui` and `jolt test` entry points in `$GUI`; `samizdat.test-runner/run` executes exactly the eight GUI test namespaces.

- [ ] **Step 1: Add the dependency map, moved tests, and runner before the implementation**

Create `$GUI/deps.edn` with `src` on the base path and a `:test` alias adding `test` and running `samizdat.test-runner`. Depend on Samizdat at the global SHA, Glimmer `09b8ab08705fa1e6f20145c2e99eaaba9ea812cc`, glimmer-gtk `3d6c3d69623673fa7b7bce18a9712a99ac682fe8`, and glimmer-gl `c11194924ef47c2245c819d0c0df6eb9c2693d3b`; retain the existing Glimmer tags where present. Define `gui` as `jolt -M:gui` and `test` as `jolt -M:test`, with `:gui` running `samizdat.gui.core`.

Copy the eight tests unchanged except stale layout comments. Add a focused runner requiring and listing exactly `samizdat.gui-api-test`, `samizdat.gui-approval-test`, `samizdat.gui-ops-test`, `samizdat.gui-graph-test`, `samizdat.gui-style-test`, `samizdat.gui-input-test`, `samizdat.gui-mathtext-test`, and `samizdat.gui-newrun-test` in both places.

- [ ] **Step 2: Run the moved suite and prove the import is incomplete**

Run: `jolt -M:test` in `$GUI`
Expected: FAIL because the `samizdat.gui.*` implementation namespaces are absent.

- [ ] **Step 3: Move the implementation into the standalone source root**

Copy `$CORE/gui/samizdat/gui/*.clj` to `$GUI/src/samizdat/gui/` without renaming namespaces or changing behaviour. Update only comments that describe GTK dependencies as living behind core's optional alias. Copy the GPL license and add ignore rules for `.cpcache/`, `.jolt/cpcache/`, `target/`, `samizdat.build/`, and local runtime state.

- [ ] **Step 4: Run all eight moved test namespaces**

Run: `jolt test` in `$GUI`
Expected: PASS, with all eight namespace names printed and zero failures/errors.

- [ ] **Step 5: Document and commit the runnable project**

The README must show `jolt serve` in the Samizdat checkout, `jolt gui` in `$GUI`, `SAMIZDAT_URL`, `HARNESS_PORT`, and `jolt test`. Commit all standalone files as `feat: extract GTK GUI from Samizdat`.

### Task 3: Add standalone native-load CI and exercise the real entry point

**Files:**
- Create: `$GUI/dev/cold-load.sh`
- Create: `$GUI/.github/workflows/tests.yml`

**Interfaces:**
- Consumes: the runnable project and eight-namespace suite from Task 2.
- Produces: repeatable native-load and CI checks for every standalone revision.

- [ ] **Step 1: Adapt the cold-load check to the standalone layout**

Copy core's script, scan `$GUI/src` rather than core `src gui`, and load with `-A:test` rather than `-A:test:gui`. Keep one Jolt process per namespace and the existing shim ordering checks.

- [ ] **Step 2: Verify native loading and the toolkit-bound namespace**

Run in `$GUI`:
```bash
./dev/cold-load.sh
jolt -A:test -e '(require (quote samizdat.gui.core))'
```
Expected: both commands exit zero without a display; no missing GTK/OpenGL or shim error appears.

- [ ] **Step 3: Add CI with the native prerequisites before tests**

Create an Ubuntu pull-request/main workflow that installs the latest Jolt, caches and fetches dependencies, installs `libgtk-4-1` and `libgl1`, then runs `jolt -M:test`, `./dev/cold-load.sh`, and the explicit `samizdat.gui.core` require. No display server is required.

- [ ] **Step 4: Smoke-test the real GUI against a local server**

Confirm a local Samizdat server's `/health` responds, launch `SAMIZDAT_URL=http://127.0.0.1:3985 jolt gui` in `$GUI` as a bounded background process, and assert it remains alive for 15 seconds without an exception. Stop only that launched process. Expected: startup succeeds and read-only run-list polling reaches the server; no run-changing endpoint is called. If no window-server access exists, report that limitation and retain the successful namespace-load evidence separately.

- [ ] **Step 5: Verify dependency delivery and commit**

Run: `jolt -Spath -A:test` in `$GUI`
Expected: Samizdat and Glimmer resolve from full-SHA Git checkouts, with no local checkout substitution. Commit as `ci: test standalone GTK GUI`.

### Task 4: Remove the GUI application from core

**Files:**
- Delete: `$CORE/gui/`
- Delete: `$CORE/test/samizdat/gui_*_test.clj`
- Modify: `$CORE/deps.edn`
- Modify: `$CORE/test/samizdat/test_runner.clj`
- Modify: `$CORE/dev/cold-load.sh`
- Modify: `$CORE/.github/workflows/tests.yml`
- Modify: `$CORE/src/samizdat/agent/verify.clj`

**Interfaces:**
- Consumes: the standalone project pushed or ready to push from Tasks 2–3.
- Produces: a core test classpath and CI pipeline with no GUI source, Glimmer dependency, or native GTK/OpenGL prerequisite.

- [ ] **Step 1: Remove GUI code, tests, dependencies, and test registration**

Delete the eleven GUI implementation files and eight GUI tests. Remove `gui` from `:test` paths, remove the entire `:gui` alias and `gui` task, and remove exactly the eight GUI entries from both runner locations. Update adjacent comments to describe only the remaining front ends.

- [ ] **Step 2: Remove core native wiring and stale path documentation**

Change cold-load to scan `src` and invoke `-A:test`; remove core CI's GTK/OpenGL installation step. Change the `verify.clj` docstring to name core's `test/` and `src/` roots rather than `gui/`. Leave the generic, project-configurable `:root-strip-regex` policy unchanged.

- [ ] **Step 3: Run focused and registration checks**

Run `samizdat.verify-test` in a fresh Jolt process, then:
```bash
jolt -A:test -e '(require (quote samizdat.test-runner)) (println (count samizdat.test-runner/namespaces)) (println (filter #(clojure.string/includes? (str %) "gui") samizdat.test-runner/namespaces))'
```
Expected: focused tests PASS; the runner count is eight lower than baseline and the GUI filter returns an empty sequence.

- [ ] **Step 4: Commit the mechanical removal**

Commit as `refactor: move GTK GUI to standalone project`.

### Task 5: Update current core documentation and verify the final tree

**Files:**
- Modify: `$CORE/AGENTS.md`
- Modify: `$CORE/README.md`
- Modify: `$CORE/dev/samizdat/dev/nrepl_eval.clj`
- Modify: `$CORE/src/samizdat/api/client.clj`
- Modify: `$CORE/docs/tui.md` only where it describes the GUI as an in-repository front end

**Interfaces:**
- Consumes: the GUI-free core from Task 4 and the standalone repository URL.
- Produces: current instructions that route GTK users to `samizdat-code/samizdat-gui` and core developers to GUI-free commands.

- [ ] **Step 1: Update current instructions and references**

Replace the two-front-end section with the remaining TUI/ACP arrangement and a standalone GUI pointer. Remove `gui` from live-development path explanations. Add the external GUI to README running instructions. Make the shared client docstring front-end-neutral. Leave generic `gui.edn` layering support and server comments about GUI API consumers intact. Do not edit historical specs, plans, RFCs, or provenance.

- [ ] **Step 2: Sweep current files for stale extraction wiring**

Search tracked current files for `samizdat.gui`, `jolt gui`, `:gui`, `gui/`, Glimmer, and GTK. Expected: no core alias, task, source path, runner registration, or CI native setup remains; remaining references are the external repository pointer, generic API/configuration discussion, or historical documents.

- [ ] **Step 3: Run core cold-load and the complete final suite**

Run in `$CORE` with no further edits until both finish:
```bash
./dev/cold-load.sh
jolt test
```
Expected: both exit zero. Compare the final test totals with baseline and account for exactly the eight moved GUI namespaces.

- [ ] **Step 4: Commit the documentation and verified core result**

Commit as `docs: point GTK users to samizdat-gui`.

### Task 6: Deliver both repositories

**Files:**
- No planned file changes; inspect only unless verification exposes a defect.

**Interfaces:**
- Consumes: all verified commits from Tasks 1–5.
- Produces: pushed `samizdat-code/samizdat-gui` and a Samizdat pull request targeting `main`.

- [ ] **Step 1: Inspect diffs, statuses, and commit text**

Review both complete diffs and logs. Confirm no `:local/root`, AI attribution, generated cache, build artifact, or unrelated edit is included. Confirm `git -C /Users/yogthos/src/samizdat-code/samizdat status --short --branch` still shows the original `autolith-adoption` state and pre-existing edits only.

- [ ] **Step 2: Push the standalone repository**

Run: `git -C "$GUI" push -u origin main`
Expected: GitHub accepts `main`; `gh repo view samizdat-code/samizdat-gui` reports a non-empty repository with `main` as default.

- [ ] **Step 3: Push the core branch and open the pull request**

Push `extract-gtk-gui` to `origin`, then create a PR targeting `main` titled `Extract GTK GUI into samizdat-gui`. The body must summarize the standalone repository, core removals, and exact test/launch results, and link `https://github.com/samizdat-code/samizdat-gui`.

- [ ] **Step 4: Close the tracking bead with durable evidence**

Close `karamazov-o7d4` with the standalone commit/repository, core PR URL, and verification results. Report both URLs and any environment-limited check to the user.
