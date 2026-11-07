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

`run` also takes `--arms`, `--attempts`, `--concurrent`, `--tasks figlet yj` and
`--dry-run`. Run `bundle` after any change to `src/` or `resources/`, or the run tests
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
`datasurgeon`, Pi scored 82% in pilot 1 and 0% in pilot 2.

Pilot 2, before the fixes listed below:

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
