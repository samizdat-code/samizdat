#!/usr/bin/env bash
# Grade one run's artifact and append the result row.
# Usage: PASS=n ./grade.sh <arm> <problem> <workdir> [run-id] [run-status]
# Exit 0 solved, 1 not solved. Appends to results.jsonl beside this script.
#
# The per-case timeout is read from gates.edn :eval-samples
# :per-case-timeout-ms — the same single source the in-harness grader
# (samizdat.eval.samples) reads — so tuning the gate cannot make this
# script and the loop's own sample runner diverge.
set -u
cd "$(dirname "$0")"
arm=$1; problem=$2; work=$3; run_id=${4:-}; status=${5:-}

timeout_ms=$(python3 -c '
import re, sys
text = open("resources/gates.edn").read() if __import__("os").path.exists("resources/gates.edn") else None
if text is None:
    # grade.sh runs from evals/team-solve; the repo root is two up.
    text = open("../../resources/gates.edn").read()
m = re.search(r":eval-samples\s*\{[^}]*:per-case-timeout-ms\s+(\d+)", text, re.S)
print(m.group(1) if m else "")
')
if [ -z "$timeout_ms" ]; then
  echo "grade.sh: could not read :per-case-timeout-ms from gates.edn" >&2
  exit 2
fi

EXTRA_JSON="${EXTRA_JSON:-{\}}" python3 - "$arm" "$problem" "$work" "$run_id" "$status" "$PASS" "$timeout_ms" <<'PY'
import json, os, subprocess, sys

arm, problem, work, run_id, status, pas, tms = sys.argv[1:8]
timeout_s = int(tms) / 1000.0
hidden = os.environ.get("HIDDEN") or os.path.join("problems", problem, "hidden")
sol = os.path.join(work, "solution.py")
rec = {"problem": problem, "arm": arm, "pass": pas,
       "run_id": run_id or None, "run_status": status or None}
if not os.path.exists(sol):
    rec.update(ok=False, passed=0, total=0, note="no solution.py produced")
else:
    total = passed = 0
    first_fail = None
    for f in sorted(os.listdir(hidden)):
        if not f.endswith(".in"):
            continue
        total += 1
        exp = open(os.path.join(hidden, f[:-3] + ".out")).read().strip()
        rcode = None
        try:
            r = subprocess.run([sys.executable, sol],
                               stdin=open(os.path.join(hidden, f)),
                               capture_output=True, text=True, timeout=timeout_s)
            got, rcode = r.stdout.strip(), r.returncode
        except subprocess.TimeoutExpired:
            got = None
        if got == exp and rcode == 0:
            passed += 1
        elif first_fail is None:
            first_fail = f
    rec.update(ok=(total > 0 and passed == total),
               passed=passed, total=total, fail=first_fail)
try:
    rec.update(json.loads(os.environ.get("EXTRA_JSON") or "{}"))
except ValueError:
    rec["report_error"] = os.environ.get("EXTRA_JSON")
with open(os.environ.get("RESULTS", "results.jsonl"), "a") as out:
    out.write(json.dumps(rec) + "\n")
print("SOLVED" if rec.get("ok") else "NOT-SOLVED",
      rec.get("passed", 0), "/", rec.get("total", 0))
sys.exit(0 if rec.get("ok") else 1)
PY
