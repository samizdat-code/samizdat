#!/usr/bin/env bash
# Read the karamazov-xn0h.9 arms (eval bridge, vocabulary block) off a sweep's
# recordings.
#
#   dev/xn0h9-readout.sh [recordings-dir]     # default: $TMPDIR/arena-recordings
#
# One line per recorded run (the arm is in the file name,
# wt-<task>-<arm>-<k>.sqlite3), then per branch role:
#   to_write  turns before the branch's first write_file, edit_file or patch
#   turns     the branch's turns
#   res_kc    thousands of characters of tool results the branch was handed
#   tok_k     thousands of tokens the branch spent
#   evals     eval turns, and how many of them made at least one bridge call
#   calls     bridge calls (:eval-call notes), and their result characters
set -euo pipefail
DIR="${1:-${TMPDIR:-/tmp}/arena-recordings}"
ls "$DIR"/*.sqlite3 >/dev/null 2>&1 || { echo "no recordings under $DIR" >&2; exit 1; }

for db in "$DIR"/*.sqlite3; do
  nm=$(basename "$db" .sqlite3)
  echo "=== $nm"
  sqlite3 -header -column "$db" "
    SELECT status,
           (SELECT count(*) FROM turns) AS turns,
           (SELECT round(sum(coalesce(total_tokens,0))/1000.0) FROM turns) AS tok_k,
           (SELECT round(sum(length(result))/1000.0) FROM turns) AS res_kc,
           (SELECT count(*) FROM events WHERE kind = 'vocabulary') AS vocab_notes,
           (SELECT count(*) FROM events WHERE kind = 'eval-call') AS bridge_calls
      FROM runs;"
  sqlite3 -header -column "$db" "
    WITH w AS (SELECT branch_id, min(turn) AS first_write FROM turns
                WHERE tool_name IN ('write_file','edit_file','patch') GROUP BY branch_id),
         c AS (SELECT branch_id, turn, count(*) AS n,
                      sum(json_extract(data,'\$.chars')) AS chars
                 FROM events WHERE kind = 'eval-call' GROUP BY branch_id, turn)
    SELECT substr(t.branch_id,1,8) AS branch,
           coalesce(w.first_write - 1, '-') AS to_write,
           count(*) AS turns,
           round(sum(length(t.result))/1000.0) AS res_kc,
           round(sum(coalesce(t.total_tokens,0))/1000.0) AS tok_k,
           sum(t.tool_name = 'eval') AS evals,
           sum(t.tool_name = 'eval' AND c.n IS NOT NULL) AS evals_bridged,
           coalesce(sum(c.n),0) AS calls,
           coalesce(sum(c.chars),0) AS call_chars
      FROM turns t
      LEFT JOIN w ON w.branch_id = t.branch_id
      LEFT JOIN c ON c.branch_id = t.branch_id AND c.turn = t.turn
     GROUP BY t.branch_id ORDER BY min(t.id);"
  echo "--- bridge calls by tool"
  sqlite3 -column "$db" "
    SELECT json_extract(data,'\$.tool') AS tool, json_extract(data,'\$.category') AS cat, count(*)
      FROM events WHERE kind = 'eval-call' GROUP BY 1, 2;"
done
