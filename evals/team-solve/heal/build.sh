#!/usr/bin/env bash
# Copy each fixture's problem from the built hard corpus (evals/team-solve/hard).
# The planted fault is the fixture; the problem only has to be solvable.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
hard=$here/../hard
for pair in worker-cap:lis-count loop-cap:distinct-subseq broken-cell:gap-intervals broken-manifest:one-free-edge; do
  f=${pair%%:*}; p=${pair##*:}
  rm -rf "$here/$f/tests" "$here/$f/hidden"
  cp -f "$hard/$p/task.md" "$here/$f/"
  cp -rf "$hard/$p/tests" "$hard/$p/hidden" "$here/$f/"
done
