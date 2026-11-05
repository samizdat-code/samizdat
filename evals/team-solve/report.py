#!/usr/bin/env python3
"""What a run did, from its journal: stages walked, whether it got stuck, whether
the supervisor looked and what it did. One JSON object on stdout."""
import json, sqlite3, sys
from datetime import datetime


def stamp(s):
    return datetime.fromisoformat(str(s).replace("Z", "+00:00")).timestamp()


def main(db, run_id):
    c = sqlite3.connect(db)
    c.row_factory = sqlite3.Row
    ev = c.execute("SELECT kind, branch_id, data, created_at FROM events WHERE run_id=? ORDER BY id", (run_id,)).fetchall()
    data = lambda r: json.loads(r["data"] or "{}")
    kinds = {}
    for r in ev:
        kinds.setdefault(r["kind"], []).append(r)
    stages = [data(r)["to"] for r in kinds.get("progress", [])
              if data(r).get("key") == "stage" and r["branch_id"] in (None, "B1")]
    times = [stamp(r["created_at"]) for r in ev]
    gaps = [b - a for a, b in zip(times, times[1:])] or [0]
    turns = c.execute("""SELECT branch_id, COUNT(*) n, COALESCE(SUM(total_tokens),0) t
                         FROM turns WHERE run_id=? GROUP BY branch_id""", (run_id,)).fetchall()
    sup = sum(r["t"] for r in turns if r["branch_id"] == "SUP")
    side = c.execute("SELECT COALESCE(SUM(total_tokens),0) FROM side_calls WHERE run_id=?", (run_id,)).fetchone()[0] \
        if c.execute("SELECT 1 FROM sqlite_master WHERE name='side_calls'").fetchone() else 0
    iv = c.execute("SELECT kind, status, issued_by FROM interventions WHERE run_id=?", (run_id,)).fetchall()
    verify = [data(r) for r in kinds.get("team-solve-verify", [])]
    provider_errors = c.execute("SELECT COUNT(*) FROM turns WHERE run_id=? AND tool_name='__provider_error__' AND branch_id!='SUP'", (run_id,)).fetchone()[0]
    out = {
        "stages": stages,
        "reached_verify": "verify" in stages,
        "wall_s": round(times[-1] - times[0]) if times else 0,
        "longest_silence_s": round(max(gaps)),
        "turns": sum(r["n"] for r in turns if r["branch_id"] != "SUP"),
        "worker_tokens": sum(r["t"] for r in turns if r["branch_id"] != "SUP"),
        "supervisor_tokens": sup,
        "side_tokens": side,
        "provider_errors": provider_errors,
        "rounds": len(kinds.get("team-solve-round", [])),
        "signals": len(kinds.get("workflow-signal", [])),
        "unsticks": [data(r).get("by") for r in kinds.get("team-solve-unstick", [])],
        "supervisor_looks": len(kinds.get("oversight", [])),
        "quiet_looks": len(kinds.get("oversight-quiet", [])),
        "stage_errors": [str(data(r).get("error"))[:200] for r in kinds.get("stage-error", [])],
        "directives": [dict(k) for k in iv],
        "directives_stranded": sum(1 for k in iv if k["status"] == "pending"),
        "overrode_done": sum(1 for r in kinds.get("team-solve-round", []) if data(r).get("overrode-done")),
        "counterexamples": [data(r).get("counterexample") for r in kinds.get("team-solve-round", []) if data(r).get("counterexample")],
        "restored": bool(verify and verify[-1].get("restored")),
        "verify": verify[-1].get("final") if verify else None,
    }
    print(json.dumps(out))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
