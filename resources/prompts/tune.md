## This run TUNES a program

You are making existing code better on a measured metric — running time, throughput, memory, solution quality — not adding a feature. Everything above still holds. The method is the Algorithm Engineering cycle (Sanders 2009), as Agentic Algorithm Engineering runs it: every change is a falsifiable hypothesis, predicted before it is measured, committed, benchmarked, and kept or discarded by the number.

### Setup, once

1. Read the code you may change, the benchmark (which you may NOT change), and anything fixed: inputs, time budget, metric definition. You may not add dependencies.
2. Write down correctness assertions: invariants that must hold after every change ("the output is a permutation of the input", "every node is in exactly one block", "the partition file is byte-identical to the baseline's"). Ask the person to confirm them with `ask_human` unless the problem already states them. A change that improves the metric and breaks an assertion is always discarded.
3. Work on a branch of its own (`git switch -c tune/<short-name>`, issued on its own) and never reset away changes that are not yours. If creating the branch is refused and nobody can grant it, carry on where you are and say so in `done`: the baseline commit is recorded, so the person can still move the work.
4. `tune {action: start, metric, direction, command, assertions}`.
5. Run the unmodified benchmark and `tune {action: record, hypothesis: "baseline", values}`. If running it is refused for approval and nobody grants it, do not route around the refusal through another runner: stop, and say in `done` that the person can allow it for this project with `:shell {:allow ["./bench.sh **"]}` (their benchmark command) in `.samizdat/config.edn`.

### Each experiment

1. **Model.** Where does the time actually go? Profile before guessing; read the results so far. When a result contradicts what you expected, your model is wrong — revise it, not the experiment.
2. **Hypothesis.** One specific, falsifiable change: "replacing the binary heap with a bucket queue cuts refinement time ~15% because keys are small integers". Not "make it faster".
3. **Predict.** Direction and size, and what could go wrong. Skip an experiment whose expected gain is not worth its complexity.
4. **Implement.** The minimal diff the hypothesis needs. Check the assertions; a failing one is a discard without benchmarking — record it as a crash.
5. **Commit** the code under tune, with the hypothesis as the message. Every experiment has its own commit, and it holds the change and nothing else: no binaries, logs or reference copies, and no helper scripts — keep those untracked, so each patch is the change a person would review.
6. **Run** the benchmark without letting its output flood your context: pipe it to the metric line (`./bench.sh 2>&1 | grep '^time_s:'`) or to its tail (`… 2>&1 | tail -n 5`). Writing to a file with `>` is asked about like any write, so pipe instead. A crashed run: the same command with `| tail -n 50`. Independent inputs can run in parallel when they do not compete for the same cores.
7. **Record**: `tune {action: record, hypothesis, values, prediction, learned}`. It keeps the change and exports the patch, or tells you the revert to run. Run it before the next change; never `git reset --hard`, which only a person can approve.

### Rules

- **Simplicity.** A tiny gain bought with ugly complexity is not worth keeping. An equal result with less code is a win: record it with `simpler: true`. Removing code at no cost is the best outcome there is.
- **When small changes stop paying,** stop tuning constants and look for work whose result is thrown away: values recomputed, structures rebuilt per call, solves whose answer is discarded, O(n) setup paid per small call. The biggest wins are usually redundancy, not micro-optimisation.
- **Ideas run out → think harder.** Re-read the code and the benchmark, combine near-misses, revisit a discarded idea with a modification, try a different algorithm for the core computation.
- Do not stop to ask whether to continue. Keep going until the turn budget or the person stops you. When you finish, `done` with the baseline, the best, the speedup, and the list of kept patches with one line each.
