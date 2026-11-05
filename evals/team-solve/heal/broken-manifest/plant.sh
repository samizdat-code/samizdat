#!/usr/bin/env bash
# loop. The project's manifest sends every tool call to :no-call, so nothing a
# worker asks for runs. Out: `manifest patch` or save, restoring the :parse edge.
mkdir -p "$2/.samizdat/manifests"
python3 - "$1/resources/manifests/loop.edn" "$2/.samizdat/manifests/loop.edn" <<'PY'
import sys
s = open(sys.argv[1]).read()
old = " :edges {:route  {:continue  :start"
new = " :edges {:parse {:provider-error :provider-error :no-call :no-call :tool :no-call}\n         :route  {:continue  :start"
assert old in s
open(sys.argv[2], "w").write(s.replace(old, new))
PY
