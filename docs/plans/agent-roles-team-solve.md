# Team-solve as the default loop: review, plan, and test strategy

Source review 2026-11: `/Users/yogthos/src/memdoor` (`examples/workflows/team-solve`) and
`/Users/yogthos/src/GVS5H` (`codebase/v2-current/escalation/multiagent.py`).

## 1. What the method actually is

Two different workflows carry the name, and the first draft of this plan confused them.

| | loop | calls | result |
|---|---|---|---|
| GVS5H / memdoor `parity-5pass` | manager loop, up to 10 rounds, then finalize | one call per role, no tools | 9.8 of 10 on Qwen3.8-27B |
| memdoor `team-llm-lcb` | fixed 11 steps, one repair round | one call per role, no tools | 47 of 100 against 35 for a single call |
| memdoor `steps/*.yaml` | fixed 8 steps, one repair round | agent turns with tools | one sample problem, never benchmarked |

The parity number belongs to the manager loop, not to the eight YAML files. Memdoor's
bench notes also record that the agent-turn variant cost over two dollars a problem and
five to eight minutes a role. So: adopt the manager loop, and keep each role as small as
the work allows.

### Roles (same model, fresh context each, temperature from a table)

| role | step | reads | writes | t |
|---|---|---|---|---|
| planner | once | problem | PLAN, TASKS | 0.3 |
| ideator A, B | once | problem, plan | NOTES, NEXT; no code, code blocks stripped | 0.4 |
| manager | every round | problem, current work, notes, tasks, latest result with the gate verdict, proposed steps | STATUS, NEXT, TASKS | 0.2 |
| worker | one task | problem, plan, notes, current work, the task | the work; NOTES rewritten whole, NEXT, STATUS | 0.2 |
| cutoff summarizer | when a worker is cut off | the attempt | 3 to 5 sentences for the manager | 0.2 |
| finalizer | when the loop ends without done | everything | the definitive work | 0.2 |

### Gates, all mechanical

- `done` needs an artifact on disk.
- `done` while the samples fail is overridden to continue.
- The manager issuing the same task twice stops the loop.
- Ten rounds, then one forced finalize.
- Notes are not overwritten by a worker that was cut off.
- A worker cut off at the cap tells the manager to prefer a simpler or different approach.

### Published loss modes, both left open in the authors' loop

1. The manager invented a wrong counterexample and the team rewrote a correct program.
2. A program passing every hidden test was overwritten because the manager did not say done.

Memdoor's rules that transfer: the prompt is the work and the command is the proof;
conditions are checks, never instructions; loops have a bound and a mechanical stop rule;
parsers survive a reply that ends at the last section; the exact problem text matters (a
rewritten task file planned measurably worse than the original text).

## 2. What running the first draft showed

The branch's draft never ran end to end. Driving it over HTTP found:

1. `resources/userspace.edn` closed its top-level map before `:prompts`, so all 269 prompt
   roles vanished and `POST /v1/runs` failed for every workflow.
2. The `team-solve-*` prompts were never added to the role map, so the plan cell threw.
3. That throw reached the journal as `branch error: execution error`. The cause was lost.
4. The run then ended `exhausted` with no supervisor pass. The stream only wakes on a
   `:turn` event, and a side-call stage produces none.
5. `samples-report` destructures `name` and then calls `(name reason)`, so any failing
   sample crashes the review.
6. A manager SHIP on failing samples goes to verify and abandons, where GVS5H forces
   another round.
7. Keep-best holds the first passing program. The published fix is the last one.
8. The draft is the one-repair shape, so it has no manager loop, task list, curated notes,
   stop rule or cutoff handling.
9. `evals/team-solve/run.sh` starts `jolt serve` outside the repo, hardcodes the port the
   operator's server holds, and grades a path the workflow does not write.
10. A stray `solution.py` holding the deliberately wrong test program was left in the repo
    root.

Rebuilding it and driving the result found more, each of which would have stuck a default
run on its own:

11. `write_file` is refused until a branch holds a task, and a worker has no tool to claim
    one. It now opens holding a board row.
12. The ship gate refused a worker's `done` over a checklist for its whole 40-turn budget.
    A worker reports to the manager and a script decides, so its `done` is not a ship.
13. The worker's system prompt told it, on a Python problem, that it was a Clojure developer
    working REPL-first, at about 17K characters. A role can now name its own frame (9.5K).
14. The worker's `done` path ran a reflection and a memory revision per round: two model
    calls the method does not have. It now runs a turn chain with nothing after it.
15. `INPUT:` and `EXPECTED:` lines parse as section headers, so counterexamples were lost.
16. "Done with no work" skipped the round bound and looped forever.
17. A shipped cell failing validation disabled the supervisor for the whole run with
    nothing in the journal. Stream failures are now journalled as `:oversight-failed`.
18. An invalid template operator killed the supervisor's pass at the moment a stuck team
    asked for it. The team's mechanical fallback carried the run, as designed.
19. On a contradictory fixture the supervisor found the contradiction and then told the
    team to edit the test. It is now told to `stop` instead, and modified samples are
    restored by the gate.

The supervisor gaps, read from the code:

- `switch`, `budget` and `stop` drain only at the feature loop's stage. On any other
  workflow they stay pending forever while the tool reports them as queued.
- A run-wide `message` is delivered only to a branch taking turns.
- Nothing in `worth-a-look?` knows a workflow is stuck at a stage.
- Nothing lets a workflow ask the supervisor for help and wait for the answer.

## 3. Design

Standing rule: mechanism in `src/`, decisions in cells, numbers in `gates.edn`, words in
`resources/prompts/`, roles in `roles.edn`, discoverability in `manual.edn`.

### The manifest

```
start -> plan -> ideate -> manage -+-> work -> test -> manage
                                   +-> unstick -+-> work
                                   |            +-> verify
                                   +-> verify -> finish
```

Every node is a progress stage, so the supervisor reads position rather than inferring it.

The ledger is the database (user steer on karamazov-p4b3): `:team-solve/ledger` notes in
the run's journal hold plan, notes, tasks, proposals, round and attempts. Each role's
context is assembled from the latest ledger note and nothing else. Only the work itself
is on disk.

### The gate generalises past `solution.py`

A default loop has to work on a repository, so the gate is chosen from what the run has:

| gate | when | decides by | current work shown to the manager | keep-best |
|---|---|---|---|---|
| samples | `tests/*.in` paired with `.out` at the root | the paired grader | the program | program text |
| verify-cmd | config `:run :verify-cmd` | exit status under the sealed runner, then one independent review of the diff | the diff since baseline | git tree id |
| none | neither | one independent review of the diff; the answer says no check ran | the diff since baseline | none |

The review exists because a live worker wrote a `pytest.py` stand-in until the check went
green. A gate can say the tests pass. It cannot say why.

### Manager rules (pure, in the cell, numbers in gates)

In order: a `stop` directive; done without an artifact continues; done with a failing gate
continues; green plus continue needs a counterexample (below); the round bound finalizes;
the same task twice, or the same failure for `:stall-rounds` rounds, goes to `unstick`.

Loss mode 1: on a green program, the manager may continue only by giving a concrete
`COUNTEREXAMPLE` with input and expected output. The cell runs it. No counterexample, or
one the program already satisfies, ships. One it fails permits at most
`:max-green-revisions` more rounds.

Loss mode 2: verify restores the last passing work whenever the final work fails.

### Worker

A new `:solver` role: the file, search and shell surface plus `run_check` and `done`, a
short role frame in place of the implementer's system prompt, and a per-task turn cap from
gates. `run_check` exists because the shell policy rightly refuses a program reading a file
on stdin, and a local model that wrote a correct program on turn 3 spent 25 more turns
hunting the harness for an allow list. No plan, task-board, memory or harness tools. It ends
with RESULT, NOTES, NEXT and STATUS sections in `done`'s answer, parsed leniently, so a
malformed ending can never refuse a finish and cost turns.

### Supervisor

| gap | fix |
|---|---|
| blind on side-call stages | urgent wakes on `:workflow-signal` and `:stage-error` events, ignoring the spacing but not the budget |
| blind on a silent run | a pass when nothing has been journalled for `:silent-ms` |
| no stuck trigger | `:workflow-signal` notes wake `worth-a-look?`, once per signal |
| cannot see the position | `:workflow-position` note in every brief: stage, round, attempts, last gate, last task |
| directives do nothing | `retask` (new), `budget` and `stop` drain at the manager and `unstick` boundaries; run-wide `message` reaches the manager |
| nobody waits for it | `unstick` raises a signal and waits up to `:unstick-wait-ms` for a directive |
| crash cause lost | the root cause is carried to the branch's reason and a `:stage-error` note |

If no directive arrives, `unstick` applies the mechanical fallback: a fresh attempt from a
different family, naming what failed. After `:max-unsticks` it finalizes. A silent or
exhausted supervisor can slow a stuck run but never hang it.

### Selection

`team-solve` joins `:candidates`, and the `:effort` rows for a `small` or `large` change
name it. `:fallback` stays `loop`: no code reads it, it documents `workflow/loop-name`, and
a failed triage usually means the model is unreachable anyway. `answer`, `investigate` and
trivial changes keep their shorter workflows. Dropping `:workflow` from those two rows
reverts it.

### Not adopted

The mario YAML engine, files as the state medium, the escalation ladder, their sampling
parameters as fixed truth (they are starting values in gates), and one-call workers for
repository work, where the worker needs tools. Parallel ideators are also out: on the
local model two generations drop to about 6 tok/s each, so the calls run in sequence with
contexts kept independent.

## 4. Steps

1. Repair the role map; pin it with a test that the shipped map has all its sections and
   names every prompt file.
2. Section parser, pure, with the tests memdoor's rule asks for.
3. Rewrite the cells and manifest to section 3, with tests first against a scripted model:
   every transition, every manager rule, both loss modes, each gate kind.
4. Roles, prompts ported from GVS5H, gates, manual.
5. Supervisor: directive drain, signals, urgent and silent wakes, position in the brief,
   crash cause.
6. Fix the eval rig; run live over HTTP and read the journals.
7. Fault injection: a problem that cannot pass, checked for a supervisor look, a directive
   applied and a clean end.
8. A/B, then decide the default.

## 5. Empirical strategy

### Does it transition and end? (Every run, every arm)

A script reads each journal and reports the stage sequence, longest silence, rounds,
supervisor looks, directives applied, and the end state. Failure means a run that ended
without reaching `verify`, a stage held past its bound, a directive left pending at the
end, or a signal with no look after it while budget remained.

### Does the supervisor work? (Fault injection)

| fixture | fault | expected |
|---|---|---|
| contradictory sample | no program can pass | signal, look within `:every-ms`-free wake, a directive or the fallback, ends `abandoned` honestly |
| unavailable role prompt | a cell throws | cause named in the journal, not `execution error` |
| manager repeats itself | scripted | `unstick`, not a loop |

### Does it solve more? (Paired A/B)

- Arms: `loop` (the current default), `team-solve`, and `team-solve` without ideation as
  the ablation.
- Models: Bonsai 27B is the case the method is about, but at 15 to 20 tok/s a full sweep
  takes days, so a harder subset runs there. GLM runs the whole corpus first, since it is
  the project's test provider.
- 20 fresh problems, which see public samples only, and hidden tests decide. A script
  grades them. Infrastructure failures are tallied apart from solve failures.
- Primary: hidden-test solve rate, paired per problem, McNemar on discordant pairs.
- Secondary: tokens, wall clock, turns, and the loss-mode counts.
- A corpus every arm solves measures nothing, so report the ceiling plainly and add harder
  problems rather than reading a tie as a result.

### Acceptance for flipping the default, stated in advance

1. `team-solve` solves at least as many as `loop`, with p at most 0.15 if it claims a gain.
2. `jolt test` is green and no run in the sweep fails the transition check.
3. Median tokens at most 3 times `loop`'s, or the gain is reported with its real cost for
   a person to decide.

Rollback is dropping `:workflow` from two `:effort` rows.

## 6. Results (2026-11-05)

Corpus: `evals/team-solve/hard`, 12
problems whose samples hide the trap, references cross-checked against brute force. One
pass. Hidden tests score it. Raw rows are not in the repo; rerun with `sweep.py`.

| DeepSeek flash, final build | team-solve | loop |
|---|---|---|
| solved on all hidden tests | 12/12 | 12/12 |
| ended by itself | 12/12 | 2/12, the rest hit the 1800 s limit |
| median tokens | 69,317 | 13,860,181, a lower bound |
| median tokens without the supervisor | 69,317 | 8,189,224 |
| median wall clock | 145 s | 1,802 s |
| median turns | 7.5 | 398 |
| reached verify; directives stranded; stage errors | 12; 0; 0 | n/a; 0; 2 |

How to read that:

- There is NO measured solve-rate lift. Both arms solved everything, so this corpus cannot
  show one on this model. What it shows is cost and termination.
- Much of the gap is a defect in `loop`, not a virtue of the method. A correct program existed
  by worker turn 10 in all 12 runs. Nothing treated a passing check as the end, the stall gate
  read it as stuck, and escalation opened two siblings to "approach it differently", even at
  an asked width of 1. "You added no test" was only 13 of 62 `done` calls. These rows predate
  the fix below. The cheapest `loop` run, 433K tokens without the supervisor, still cost three
  times the dearest team-solve run (140K).
- `loop` used the older build's uncapped supervisor, which is why tokens are shown both ways.
- GLM on an earlier build: 7/7 each on the 7 pairs before the quota ran out, median ratio 0.14.
  The other 5 pairs were provider errors, excluded.
- Qwen3.8-27B, the model the method is about: two valid pairs, both solved by both arms.
  team-solve ended by itself in 53 and 60 minutes on 61K and 175K tokens. `loop` hit the
  90-minute limit both times with a correct program on disk, on 266K and 276K tokens, a lower
  bound. Two pairs are an anecdote, and neither separates the arms on solving. The other 4
  pairs never started: Homebrew moved jolt to 0.8.24 mid-sweep and the server failed to load
  until the `jolt-lang/time` pin was bumped. They are infrastructure, not results.

`loop` after that fix (karamazov-i692), GLM, 4 of the hard problems, 1800 s limit: all solved
on one worker branch with no escalation. 2 of 4 ended by themselves, on 496K and 1.06M tokens;
2 hit the limit at about 50 turns, on 1.38M and 1.68M. The same problems before it, 3000 s
limit, on the 3 with valid rows: none ended, 2.3M to 3.1M tokens. Better, not fixed, and not
re-measured against team-solve.

Live scenarios on the final build, through normal workflow selection:

| scenario | result |
|---|---|
| repo with a working check | fixed in 1 round, review passed, shipped |
| repo with no check | fixed in 1 round, review passed, shipped saying no check ran |
| repo whose check tool is missing | no stand-in written; ended unsolved on a supervisor `stop` naming the cause, in 7 rounds |
| samples that contradict each other | supervisor `stop` naming the contradiction, 138 s, 6 rounds |

Acceptance, as stated in advance: solve rate at least equal, no gain claimed; transition
check clean; tokens far under 3 times. On that, `:effort` now routes small and large changes
to team-solve. Whether it RAISES capability on small models is still unanswered: 2 of 6 Qwen
pairs ran, and both arms solved both. The default stands on cost and termination.

Open: supervisor passes remain dear at about 25K tokens a turn; on the missing-tool repo its
267K exceeded all the workers. The first stuck signal there fell through to the fallback
before a pass answered. A change whose only proof is a verify command the worker may also
touch is judged by one review call, which is a real but soft guard.

## Not verified

Memdoor's and GVS5H's numbers are read from their tables, not regraded. Contamination of
the LCB problems cannot be excluded for any column there, which is one reason this corpus
is fresh.
