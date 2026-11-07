#!/usr/bin/env python3
"""One row per trial in a Harbor job, and the totals. Usage: summarize.py jobs/<name>
A trial that hit the task's time limit counts as strict-fail even if the files it
left pass: the published boards score an overrun as zero."""
import glob, json, os, sqlite3, sys

rows = []
for f in sorted(glob.glob(os.path.join(sys.argv[1], "*", "result.json"))):
    r = json.load(open(f))
    d = os.path.dirname(f)
    reward = ((r.get("verifier_result") or {}).get("rewards") or {}).get("reward")
    exc = (r.get("exception_info") or {}).get("exception_type")
    ag = r.get("agent_execution") or {}
    meta = {}
    p = os.path.join(d, "agent", "samizdat.json")
    if os.path.exists(p):
        meta = json.load(open(p))
    tok = sup = 0
    db = os.path.join(d, "agent", "samizdat.sqlite3")
    if os.path.exists(db):
        c = sqlite3.connect(db)
        try:
            tok = c.execute("SELECT COALESCE(SUM(total_tokens),0) FROM turns").fetchone()[0] + \
                  c.execute("SELECT COALESCE(SUM(total_tokens),0) FROM side_calls").fetchone()[0]
            sup = c.execute("SELECT COUNT(*) FROM turns WHERE branch_id='SUP'").fetchone()[0]
        except sqlite3.Error:
            pass
    rows.append(dict(task=r["task_name"].split("/")[-1], reward=reward, exc=exc,
                     status=meta.get("status"), tokens=tok, sup_turns=sup))
for x in rows:
    print(f"{x['task'][:34]:34} reward={x['reward']} status={x['status']} exc={x['exc']} tok={x['tokens']} sup={x['sup_turns']}")
n = len(rows)
if n:
    loose = sum(1 for x in rows if x["reward"] == 1)
    strict = sum(1 for x in rows if x["reward"] == 1 and x["exc"] is None)
    print(f"n={n} pass={loose} ({100*loose/n:.1f}%) strict={strict} ({100*strict/n:.1f}%) tokens={sum(x['tokens'] for x in rows)}")
