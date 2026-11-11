#!/usr/bin/env python3
"""A results file as a paired comparison. Usage: summarize.py results.jsonl [armA armB]"""
import json, statistics as st, sys
from math import comb

rows = [json.loads(l) for l in open(sys.argv[1])]
A, B = (sys.argv[2], sys.argv[3]) if len(sys.argv) > 3 else ("team-solve", "loop")


def infra(r):
    """The model never got to try: no row of this should count against an arm."""
    return not r.get("ok") and (r.get("run_status") in ("no-start", "failed")
                                or (r.get("worker_tokens") or 0) == 0)


def total(r):
    return (r.get("worker_tokens") or 0) + (r.get("supervisor_tokens") or 0) + (r.get("side_tokens") or 0)


for arm in sorted({r["arm"] for r in rows}):
    every = [r for r in rows if r["arm"] == arm]
    x = [r for r in every if not infra(r)] or every
    ok = sum(1 for r in x if r.get("ok"))
    capped = sum(1 for r in x if r.get("run_status") == "timeout")
    print(f"{arm}: solved {ok}/{len(x)}; infrastructure failures {sum(map(infra, every))}; never ended by itself {capped}; "
          f"median tokens {st.median(total(r) for r in x):,.0f}; median wall {st.median(r.get('wall_s') or 0 for r in x):,.0f}s; "
          f"median turns {st.median(r.get('turns') or 0 for r in x):.0f}; hidden cases {sum(r.get('passed') or 0 for r in x)}/{sum(r.get('total') or 0 for r in x)}")
    print(f"  stages: reached verify {sum(1 for r in x if r.get('reached_verify'))}; longest silence {max((r.get('longest_silence_s') or 0) for r in x)}s; "
          f"signals {sum(r.get('signals') or 0 for r in x)}; supervisor looks {sum(r.get('supervisor_looks') or 0 for r in x)}, quiet {sum(r.get('quiet_looks') or 0 for r in x)}; "
          f"stranded directives {sum(r.get('directives_stranded') or 0 for r in x)}; stage errors {sum(len(r.get('stage_errors') or []) for r in x)}; "
          f"restored {sum(1 for r in x if r.get('restored'))}; overrode done {sum(r.get('overrode_done') or 0 for r in x)}; "
          f"unsticks {[u for r in x for u in (r.get('unsticks') or [])]}")

key = lambda r: (r["problem"], str(r["pass"]))
a = {key(r): r for r in rows if r["arm"] == A}
b = {key(r): r for r in rows if r["arm"] == B}
both = sorted(k for k in set(a) & set(b) if not infra(a[k]) and not infra(b[k]))
only_a = [k for k in both if a[k].get("ok") and not b[k].get("ok")]
only_b = [k for k in both if b[k].get("ok") and not a[k].get("ok")]
n = len(only_a) + len(only_b)
p = min(1.0, 2 * sum(comb(n, i) for i in range(0, min(len(only_a), len(only_b)) + 1)) / 2 ** n) if n else 1.0
print(f"\npaired {A} vs {B} on {len(both)}: both solved {sum(1 for k in both if a[k].get('ok') and b[k].get('ok'))}, "
      f"only {A} {len(only_a)} {[k[0] for k in only_a]}, only {B} {len(only_b)} {[k[0] for k in only_b]}, "
      f"neither {sum(1 for k in both if not a[k].get('ok') and not b[k].get('ok'))}; McNemar exact p={p:.3f}")
ratio = [total(a[k]) / total(b[k]) for k in both if total(b[k])]
if ratio:
    print(f"token ratio {A}/{B}: median {st.median(ratio):.2f}")
