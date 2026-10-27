# GTK GUI extraction design

## Intent

Move the optional GTK GUI out of the Samizdat core repository and into the
existing empty `samizdat-code/samizdat-gui` repository. The GUI remains a
strict HTTP client of a running Samizdat server. Core development and CI
should no longer need GTK, OpenGL, or the GUI's source and tests.

The split makes the GUI independently runnable and testable without creating
a second implementation of the shared HTTP client or approval transitions.

## Goals

- Create a standalone Jolt project at
  `/Users/yogthos/src/samizdat-code/samizdat-gui`.
- Move all eleven `samizdat.gui.*` implementation namespaces and all eight GUI
  test namespaces into that project without changing UI behaviour.
- Reuse `samizdat.api.client` and `samizdat.approval-view` from Samizdat through
  a full-SHA Git dependency, initially Samizdat `origin/main` at
  `97727796fc245f918f10b0db52537562aacb80dc`.
- Give the GUI project its own test runner, cold-load check, documentation,
  license, ignore rules, and GitHub Actions workflow with GTK/OpenGL installed.
- Remove GUI source, tests, aliases, tasks, native CI setup, and current
  developer instructions from the core repository.
- Keep `jolt gui` as the launch command inside the standalone GUI checkout.
- Push `samizdat-gui` to its existing public `origin` and open a Samizdat pull
  request from a clean branch based on `origin/main`.

## Non-goals

- Preserving GUI-related Git history; the chosen approach is a clean initial
  import of the current files.
- Redesigning the GUI or changing its interaction with the server.
- Moving or duplicating shared front-end mechanism such as
  `samizdat.api.client`, authentication-token handling, or
  `samizdat.approval-view`.
- Keeping a `jolt gui` launcher or a GUI Git dependency in the core project.
- Changing the TUI or ACP front ends beyond documentation that currently
  describes the GTK GUI as part of core.
- Editing historical plans, specifications, RFCs, or provenance records.

## Anti-goals

- Core CI must not install or load GTK/OpenGL after the split.
- The GUI must not depend on Samizdat's server internals, database, userspace,
  or in-process run state.
- The extraction must not modify the user's existing `autolith-adoption`
  checkout or its unrelated uncommitted files.

## Constraints

- Samizdat uses Jolt 0.8.22 or newer and tracks the latest Jolt release in CI.
- Git dependencies use full commit SHAs. `:local/root` is not part of either
  delivered project.
- GUI tests that can run without a display remain in the ordinary test suite;
  native GTK/OpenGL libraries are required because Jolt loads dependency
  native declarations before namespaces.
- Historical documents remain unchanged even where they describe the old
  in-repository layout.
- Commits and pull-request text carry no AI attribution.

## Approaches

### Standalone GUI with a pinned core dependency — chosen

The GUI owns its source, tests, Glimmer/GTK/OpenGL dependencies, and CI. It
uses Samizdat as a library for the two shared namespaces. Core removes the
GUI completely and links to the new repository. This gives the cleanest
boundary and avoids copying shared code.

### Standalone GUI with copied client code — rejected

This would remove the core dependency but duplicate HTTP and approval logic
still used by the TUI and ACP clients. The copies would drift.

### Core launcher for an external GUI — rejected

Keeping `:gui` in core would preserve one old invocation but make core fetch
the GUI and native toolkit again. That contradicts the purpose of the split.

## Standalone project layout

- Move `gui/samizdat/gui/*.clj` to `src/samizdat/gui/*.clj`.
- Move `test/samizdat/gui_*_test.clj` to `test/samizdat/` unchanged except for
  any documentation that describes the old alias layout.
- Add a small `samizdat.test-runner` that requires and runs exactly the eight
  GUI test namespaces.
- Add `deps.edn` with `src` on the base path, Samizdat and the three existing
  Glimmer dependencies as full-SHA Git dependencies, a `:test` alias, and
  `gui` / `test` tasks.
- Add a standalone README documenting server startup in the Samizdat checkout,
  `jolt gui`, `SAMIZDAT_URL`, `HARNESS_PORT`, and test commands.
- Copy the GPL license and add ignore rules for Jolt caches and build output.
- Add a cold-load script derived from core's script, scanning the standalone
  `src` tree and loading with the project's test classpath.
- Add GitHub Actions for the test suite, independent namespace loads, and a
  `samizdat.gui.core` load check. Install `libgtk-4-1` and `libgl1`; do not
  require a display.

## Core changes

- Delete `gui/` and the eight `test/samizdat/gui_*_test.clj` files.
- Remove the GUI test namespaces from both required places in
  `test/samizdat/test_runner.clj`.
- Remove `gui` from the `:test` paths, remove the `:gui` alias and `gui` task,
  and adjust nearby dependency comments in `deps.edn`.
- Change `dev/cold-load.sh` to scan only core sources and use `-A:test`.
- Remove the GTK/OpenGL installation step from core CI.
- Update `AGENTS.md`, `src/samizdat/api/client.clj`, and other current code or
  developer documentation that describes the old in-repository GUI. Update
  path-convention policy only if it actually names the removed `gui/` root.
- Add a short README pointer to the standalone GUI repository.

## Verification

For `samizdat-gui`:

1. Run its complete test suite in a fresh process.
2. Run its cold-load script and explicitly require `samizdat.gui.core`.
3. Launch the real GUI against a local Samizdat HTTP server and confirm the
   process starts and connects without issuing run-changing requests.
4. Confirm the delivered dependency map contains no `:local/root` override.

For Samizdat:

1. Establish and record the clean baseline suite result before edits.
2. Run the complete suite again on the final tree.
3. Run `dev/cold-load.sh` without the GUI alias or native GTK setup.
4. Search tracked current files for stale GUI aliases, paths, test entries,
   and toolkit setup, excluding historical documents deliberately left alone.

Finally inspect both diffs and Git statuses, verify commit text, push the GUI
repository, and create the core pull request. Report any display-specific
verification that the environment cannot support rather than claiming it.

## Builder discretion

The builder may choose the exact standalone workflow job structure and minor
README wording. The builder may update comments in moved files to describe
the standalone layout. Behavioural changes, dependency-version upgrades, and
additional shared-code extraction are out of scope.
