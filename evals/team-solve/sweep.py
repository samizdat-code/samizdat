#!/usr/bin/env python3
"""Run every (arm, problem, pass) through run.sh, at most PARALLEL at once, skipping any
row already in the results file so a sweep can be resumed.

Usage: sweep.py --arms loop,team-solve --problems-dir hard --passes 1 --model glm \
                --parallel 4 --base-port 4100 --results hard-glm.jsonl [--only a,b]
Do not edit resources/ while this runs: each run seeds its project from them.
"""
import argparse, json, os, subprocess, threading, queue

HERE = os.path.dirname(os.path.abspath(__file__))
a = argparse.ArgumentParser()
for k, d in [("arms", "loop,team-solve"), ("problems-dir", "problems"), ("model", "glm"),
             ("results", "results.jsonl"), ("only", ""), ("scratch", "/tmp/samizdat-eval")]:
    a.add_argument("--" + k, default=d)
a.add_argument("--passes", type=int, default=1)
a.add_argument("--parallel", type=int, default=4)
a.add_argument("--base-port", type=int, default=4100)
a.add_argument("--limit-s", type=int, default=5400)
o = a.parse_args()

pdir = os.path.join(HERE, o.problems_dir)
names = sorted(n for n in os.listdir(pdir) if os.path.isdir(os.path.join(pdir, n, "tests")))
if o.only:
    names = [n for n in names if n in o.only.split(",")]
results = os.path.abspath(o.results)
done = set()
if os.path.exists(results):
    for line in open(results):
        r = json.loads(line)
        done.add((r["arm"], r["problem"], str(r["pass"])))
q = queue.Queue()
# Pass by problem so a stopped sweep leaves the arms paired.
for p in range(1, o.passes + 1):
    for n in names:
        for arm in o.arms.split(","):
            if (arm, n, str(p)) not in done:
                q.put((arm, n, p))
print(q.qsize(), "runs")


def worker(port):
    while True:
        try:
            arm, n, p = q.get_nowait()
        except queue.Empty:
            return
        env = dict(os.environ, PORT=str(port), PROBLEMS=pdir, RESULTS=results,
                   EVAL_SCRATCH=o.scratch, RUN_LIMIT_S=str(o.limit_s))
        out = subprocess.run([os.path.join(HERE, "run.sh"), arm, n, str(p), o.model],
                             env=env, capture_output=True, text=True)
        print(arm, n, p, (out.stdout.strip().splitlines() or ["?"])[-1], flush=True)


ts = [threading.Thread(target=worker, args=(o.base_port + i * 10,)) for i in range(o.parallel)]
[t.start() for t in ts]
[t.join() for t in ts]
