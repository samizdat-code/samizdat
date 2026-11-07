# Benchmarks

Compares samizdat against a plain coding agent on rebuilding real command line
programs in Clojure on [Jolt](https://github.com/jolt-lang/jolt).

Each task is a [ProgramBench](https://github.com/facebookresearch/ProgramBench)
instance: the agent gets a compiled binary and its docs, and has to write a
`compile.sh` that builds a replacement with `jolt build`. The grader runs the
hidden tests against that replacement and scores the fraction that pass. The tests
are copied into the container only at grading, so no agent can read them.

The baseline is [Pi](https://huggingface.co/spaces/joelniklaus/harness-leaderboard),
chosen for being cheap and good on that leaderboard. Both arms use the same model,
limits and task images. Harbor runs the trials.

## Running it

You need Docker with at least 10 CPUs and 8 GB for two trials at once, `harbor`
0.25.0 on the path, a Jolt checkout at `~/src/jolt-lang/jolt`, and
`DEEPSEEK_API_KEY` in the environment.

```bash
cd benchmarks
./bench.py setup                    # clone ProgramBench at the pinned commit, build tasks
./bench.py bundle                   # package samizdat from the tree as it stands
./bench.py run my-run               # both arms, 3 attempts per task
./bench.py report my-run            # the comparison, as markdown
```

`run` also takes `--arms`, `--attempts`, `--concurrent`, `--tasks figlet yj`,
`--provider glm`, `--window` and `--dry-run`. `./bench.py replay my-run --from old-run`
runs the tasks with no memory and with what an earlier job's trials left, and says so
when no earlier memory reached the run. `python3 -m unittest test_bench` tests the helpers. Run `bundle` after any change to `src/` or `resources/`, or the run tests
the old code; `run` warns when the bundle does not match the tree. Stage new files
first, since `bundle` refuses to leave them out.

Three attempts on ten tasks takes about 16 hours at `--concurrent 1`, and one attempt
of both arms billed $4.21 on deepseek-flash. Raising `--concurrent` needs more memory,
at 4 GB per trial.

Results go to `programbench/jobs/`, which is not committed: `<name>-<arm>/` per arm
and `<name>.json` with the commit, bundle, versions and the balance before and after.

## What is fixed

| | |
|---|---|
| ProgramBench | `895f65916bc5` |
| Jolt | `v0.8.24`, and its `llms.txt` |
| Tasks | figlet, yj, csview, code-minimap, loop, ripsecrets, datasurgeon, shellharden, htmlq, dutree |
| Model | deepseek-flash, high effort |
| Limits per trial | 30 minutes, 2 CPUs, 4 GB, 3 GiB per file |
| Pi | 1.5.0 |

Network stays open, because the images need it, so an agent could fetch the upstream
source. `report` lists commands that look like fetches to check by hand, along with
any non-Clojure files in the solution.

Hermes is wired in as an arm but does not work yet: its install fails on
`hermes version`.

## Reading the report

- **Mean score** averages tasks, so one with many tests does not outweigh the others.
- **Mean without zero-score hangs** drops trials that scored 0 because a bash call
  never returned, and drops that task from both arms if no attempt is left. Pi sets no
  default timeout on commands. That is a real weakness, but it says nothing about how
  well Pi codes, so read both numbers. A call counts as hung when it sat open for
  five minutes or more at the end of a trial.
- **Cost at list rates** prices both arms from tokens with one rate table so they
  compare. Pilot 2's real bill was about 0.62 of that table; the balance change is the
  real figure, but it covers all arms together.
- **eval calls** shows whether samizdat is using its REPL workflow.

## Findings so far

These are from one attempt per task, which is not enough for a verdict. On
`datasurgeon`, Pi scored 82% in pilot 1 and 0% in pilot 2. `loop` on GLM's csview
went from 78.2% to 5.5% between two runs.

**A grader bug understates pilot 1, pilot 2 and every run before `fix4`.** The base
images carry libtmux, whose pytest plugin crashes pytest 9, so branches that let plugins
autoload ran no tests. The original csview scored 81.3% under that grader and scores
99.7% now, so 65 of its 348 tests could never pass. Grading the original program checks
this, with the same grader. So far: csview 99.7%, figlet 99.8%, yj 100%. The other seven
tasks are unchecked and their old scores may be low too, for both arms. The trials' source
was not kept, so the old scores can't be regraded.

GLM-5.3, one attempt per task, three tasks, Pi not run:

| Run | Changed | Arm | csview | figlet | yj |
|---|---|---|---|---|---|
| `fix1` | old settings | both | 0% | 0% | 0% |
| `fix2` | ZCode's settings | team-solve | 1.4% | 0.0% | 19.9% |
| `fix3` | supervisor fix, first-write warning, real tool messages | team-solve | 79.0%* | 39.4% | 6.8% |
| `fix3l` | same, with tool schemas fixed | loop | 78.2%* | 45.6% | 33.7% |
| `fix4` | mid-file repair and grader fixed | team-solve | 96.3% | 49.3% | 14.3% |
| `fix4` | same | loop | 5.5% | 62.4% | 21.3% |
| `fix5` | check at 45%, longer workers, solver told to wire up everything first | team-solve | 94.8% | 0.0% | 0.0% |
| `fix5` | same | loop | 82.8% | 51.2% | 20.1% |
| `fix6` | start small, do not draft in thinking, manager told in its own message | team-solve | 96.0% | 56.9% | 0.0%, rate limited |
| `fix6` | same | loop | 62.9% | 50.2% | 42.9% |

\* under the buggy grader, at most 81.3%. `fix2`'s loop arm was stopped early, and `fix1`
and `fix2` scored under that grader too.

What moved GLM, most to least certain:

- **Tool schemas.** `task({action, ...})` became a native schema naming only `action`.
  GLM sent just that, so it could not claim a task and could not write.
- **A repair that hid missing functions.** A paren missing mid-file was closed at the end
  of the file, so the file loaded with `-main` nested inside another function and no error.
  It went away in `fix4`: no auto-closes and no `unbound fn` in six trials.
- **Vendor settings and real tool messages.** First writes moved from 14 to 23 minutes in,
  to as early as 3, though most are still 6 to 13.

GLM manages about 90K output tokens in 30 minutes. Low, high and max effort showed no
useful difference in 12 probe calls, all near 55 tokens/s, and one call on figlet spent
536 s thinking. Late integration is what is left: a program that first runs at 90% of the
limit scores 5.5% on a task the other arm scores 96.3% on.

`fix5` shows how a prompt can backfire. Told to get all of a program running first,
team-solve's workers composed it in one go: one yj turn spent 599 s and 90K characters of
thinking before writing, and both its figlet and yj builds were still failing at the
limit. Their first writes moved from 626-635 s to 1119-1301 s, while loop, which does
not get that prompt, built earlier. The prompt now asks for the entry point and build
first, then one short file at a time.

`fix6`'s loop arm had no turn over 20K characters of thinking, after `fix5`'s one, while
team-solve's still had three each on csview and figlet (400 s and 686 s). Its yj scored 0
for a different reason: GLM rate limited the account for a few minutes (429, code 1302),
and five failed calls in a row ended the run at 169 s. loop hit the same limit and carried
on to its best yj so far. A rate limit is now waited out, and a usage cap still stops at once.

These runs do not compare samizdat with Pi. That needs Pi and samizdat on one provider
with the fixed grader, and several attempts.

Pilot 2, on DeepSeek, before the fixes listed below and under the buggy grader:

| Task | Pi | Samizdat |
|---|---|---|
| code-minimap | 0.0% | 91.9% |
| csview | 80.5% | 78.7% |
| datasurgeon | 0.0%, hung | 45.4% |
| dutree | 45.4% | 35.4% |
| figlet | 78.6% | 63.1% |
| htmlq | 11.5% | 86.9% |
| loop | 0.0%, hung | 72.4% |
| ripsecrets | 0.0%, hung | 93.6% |
| shellharden | 89.6% | 80.3% |
| yj | 85.7% | 67.4% |
| **Mean** | **39.1%** | **71.5%** |
| **Mean without zero-score hangs (7 tasks)** | **55.9%** | **72.0%** |

Each arm won five tasks. Samizdat's five are three Pi hangs, a task where Pi never
left its Python model, and `htmlq`. Pi's five are tasks where both arms finished, so
samizdat has not yet shown it writes better code when Pi does not trip over itself.

Samizdat finished every trial on its own at a mean of 1656 s; Pi hit the limit on all
ten, which costs it nothing here since the grader takes whatever is on disk. Samizdat
spent more: $4.20 against $2.54 at list rates. It made 136 `eval` calls and 622 shell
calls, so the workflow is still mostly shell.

Found and fixed after pilot 2:

- **Harness files counted as the model's work.** Task images ship their own `.git`
  with no ignore rule for `.samizdat/`, so its prompts and logs read as thousands of
  written lines (2615 on code-minimap before its first file) and fired `over-budget`
  early.
