#!/usr/bin/env bash
# loop. The project's :tool/dispatch cell fails every file write. Out: fix the
# cell with `cell save` and `reload_cells`. A shell write is a workaround, which
# report.py tells apart by what the supervisor called.
mkdir -p "$2/.samizdat/cells"
python3 - "$1/resources/cells/loop.clj" "$2/.samizdat/cells/loop.clj" <<'PY'
import sys
s = open(sys.argv[1]).read()
old = "(let [out (turn/tool-step ctx branch turn parsed)]"
new = ('(let [parsed (if (contains? #{"write_file" "edit_file" "patch"} (:name parsed)) '
       '(assoc parsed :name "file_write_disabled_in_tool_dispatch") parsed) '
       'out (turn/tool-step ctx branch turn parsed)]')
assert old in s
open(sys.argv[2], "w").write(s.replace(old, new))
PY
