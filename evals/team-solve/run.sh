#!/usr/bin/env bash
# One ARM on one PROBLEM for one PASS, end to end, over the HTTP API: make a
# scratch project, serve it, POST the problem, wait, grade on the hidden tests,
# and append one row to results.jsonl.
#
# Usage: PORT=3991 ./run.sh <arm> <problem> [pass] [model]
#   arm      loop | team-solve | team-solve-noideate (or any manifest name)
#   problem  directory under problems/, or under $PROBLEMS (faults/ for fixtures
#            that test the supervisor, not the solve rate)
#   pass     labels the row (default 1)
#   model    provider alias (default glm)
#
# EVAL_SCRATCH holds the projects, which are kept: each has its journal.
set -u
here=$(cd "$(dirname "$0")" && pwd)
# SAMIZDAT_REPO runs another checkout under this rig, for a before and after.
repo=${SAMIZDAT_REPO:-$(cd "$here/../.." && pwd)}
arm=$1; problem=$2; pass=${3:-1}; model=${4:-glm}
port=${PORT:-3991}
limit=${RUN_LIMIT_S:-5400}
scratch=${EVAL_SCRATCH:-/tmp/samizdat-eval}/$arm/$model/$problem-p$pass

rm -rf "$scratch"; mkdir -p "$scratch/.samizdat"
problems=${PROBLEMS:-$here/problems}
cp "$problems/$problem/task.md" "$scratch/"
cp -r "$problems/$problem/tests" "$scratch/tests"
printf '.samizdat/\nserve.log\n__pycache__/\n' > "$scratch/.gitignore"
git -C "$scratch" init -q
git -C "$scratch" add -A
git -C "$scratch" -c user.email=eval@example.invalid -c user.name=eval commit -qm problem

loop=$arm
if [ "$arm" = team-solve-noideate ]; then
  loop=team-solve
  # A file that exists is not overwritten when the project is seeded.
  sed 's/:ideate? true/:ideate? false/' "$repo/resources/gates.edn" > "$scratch/.samizdat/gates.edn"
fi
printf '{:roles {:default :%s}\n :run {:loop "%s"}}\n' "$model" "$loop" > "$scratch/.samizdat/config.edn"
# A fixture plants a fault in the project's own workflow before its first run.
[ -x "$problems/$problem/plant.sh" ] && "$problems/$problem/plant.sh" "$repo" "$scratch"

cd "$repo"
# The `serve` task's own command. Another project's restart on this machine was
# taking down anything named `jolt serve`.
HARNESS_ROOT=$scratch HARNESS_PORT=$port HARNESS_NREPL_PORT=$((port + 4000)) \
  jolt -m samizdat.core > "$scratch/serve.log" 2>&1 &
serve_pid=$!
trap 'kill $serve_pid 2>/dev/null' EXIT

for _ in $(seq 1 120); do
  curl -sf -m 2 "http://127.0.0.1:$port/health" >/dev/null 2>&1 && break
  sleep 2
done
token=$(cat "$HOME/.config/samizdat/http/$port.token")
api() { curl -sf -m 300 -H "Authorization: Bearer $token" -H 'Content-Type: application/json' "$@"; }

# The problem text itself, as the method's authors found it matters: a rewritten
# task planned measurably worse than the original.
body=$(python3 - "$scratch/task.md" "$(cat "$problems/$problem/max_turns" 2>/dev/null || echo 300)" "$arm" <<'PY'
import json, sys
task = open(sys.argv[1]).read()
# team-solve on samples is GVS5H's method, one call per role, and the harness writes
# the program, so it reads the task exactly as written. A tool-using arm has to be told.
told = task + "\n\nWrite a complete, self-contained Python 3 program to solution.py. It reads stdin and prints only the answer, using only the standard library. Public sample tests are paired files tests/N.in and tests/N.out; do not modify anything under tests/."
print(json.dumps({"problem": task if sys.argv[3].startswith("team-solve") else told,
                  "beam_width": 1, "max_turns": int(sys.argv[2])}))
PY
)
run_id=$(api -X POST "http://127.0.0.1:$port/v1/runs" -d "$body" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("run_id",""))')
if [ -z "$run_id" ]; then
  EXTRA_JSON='{"infra":"run did not start"}' PASS=$pass bash "$here/grade.sh" "$arm" "$problem" "$scratch" "" "no-start"
  exit 1
fi
echo "$arm/$problem/p$pass run $run_id on :$port"
# The harness's default asks a person before running an interpreter, and there is
# no person here. A grant is the operator's call, the same for every arm.
api -X POST "http://127.0.0.1:$port/v1/runs/$run_id/interventions" \
  -d '{"kind":"grant","payload":{"pattern":"python3 **"}}' >/dev/null

status=timeout
start=$(date +%s)
while [ $(($(date +%s) - start)) -lt "$limit" ]; do
  s=$(api "http://127.0.0.1:$port/v1/runs/$run_id" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("run",{}).get("status",""))' 2>/dev/null)
  case "$s" in
    completed|failed|exhausted|aborted|abandoned|cancelled|parked) status=$s; break ;;
  esac
  sleep 5
done
[ "$status" = timeout ] && api -X POST "http://127.0.0.1:$port/v1/runs/$run_id/abort" -d '{}' >/dev/null
sleep 2
kill $serve_pid 2>/dev/null
wait $serve_pid 2>/dev/null
trap - EXIT

extra=$(python3 "$here/report.py" "$scratch/.samizdat/samizdat.sqlite3" "$run_id")
HIDDEN=$problems/$problem/hidden EXTRA_JSON=$extra PASS=$pass bash "$here/grade.sh" "$arm" "$problem" "$scratch" "$run_id" "$status"
