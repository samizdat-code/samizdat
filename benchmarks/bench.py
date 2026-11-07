#!/usr/bin/env python3
"""Run ProgramBench in Jolt under Harbor and compare the arms. See README.md.

  bench.py setup                 clone ProgramBench at the pinned commit, build the tasks
  bench.py bundle                build the samizdat bundle from the current tree
  bench.py run NAME [options]    run the arms, writing jobs/NAME-<arm> and jobs/NAME.json
  bench.py replay NAME --from JOB   the same tasks with no memory and with JOB's
  bench.py report NAME           print the comparison as markdown
"""
import argparse, json, os, re, sqlite3, subprocess, sys, urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
PB = HERE / "programbench"
RIG = HERE.parent / "evals/terminal-bench"
JOBS, LOGS, TASKS, CHECKOUT = PB / "jobs", PB / "logs", PB / "tasks", PB / ".checkout"
PROGRAMBENCH = ("https://github.com/facebookresearch/ProgramBench", "895f65916bc5ba34a915d36da321fde0301125ad")
JOLT_REPO, JOLT_TAG = Path.home() / "src/jolt-lang/jolt", "v0.8.24"
HARBOR = "0.25.0"
MODEL = "deepseek/deepseek-flash"
# USD per token: uncached input, cached input, output. What Pi's price table charged in
# pilot 2. Both arms are priced with it so they compare; the bill itself came to about 0.62
# of this, so use the balance change in jobs/NAME.json for what was actually spent.
RATES = (0.3e-6, 0.006e-6, 1.2e-6)
NOISE = 0.05  # score gap below this says nothing: the same task moved far more than this between pilots
HANG_SECS = 300  # a bash call open this long at the end of a trial, with nothing after it
SAMIZDAT = ["--agent-import-path", "samizdat_agent:SamizdatAgent", "--ak", "eval_mode=project"]
ARMS = {
    "pi": ["-a", "pi", "-m", MODEL, "--ak", "reasoning_effort=high", "--ak", "version=1.5.0"],
    "samizdat": SAMIZDAT + ["--ak", "arm=loop"],
    "samizdat-memory": SAMIZDAT + ["--ak", "arm=loop"],
    "team-solve": SAMIZDAT + ["--ak", "arm=team-solve"],
    "hermes": ["--agent-import-path", "hermes_effort:HermesEffort", "-m", MODEL],
}
# What each vendor's own harness defaults to.
DEFAULT_EFFORT = {"deepseek": "high", "glm": "max"}
# Samizdat's model. Pi and Hermes stay on MODEL: compare arms only on the same one.
PROVIDERS = {"deepseek": [], "glm": ["--ak", "provider=glm", "--ak", "provider_type=glm", "--ak", "model_id=glm-5.3",
                                     "--ak", "key_env=ZHIPU_API_KEY"]}
FETCH = re.compile(r"\b(curl|wget|git clone|cargo (install|download|fetch)|pip3? install|npm install|apt(-get)? install)\b")


def sh(*cmd, cwd=None):
    return subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def setup(_):
    url, commit = PROGRAMBENCH
    if not CHECKOUT.exists():
        sh("git", "clone", "-q", url, str(CHECKOUT))
    sh("git", "checkout", "-q", commit, cwd=CHECKOUT)
    # The llms.txt of the Jolt the task images install, not whatever a checkout is on.
    llms = CHECKOUT / "llms-pinned.txt"
    llms.write_text(sh("git", "show", f"{JOLT_TAG}:llms.txt", cwd=JOLT_REPO) + "\n")
    subprocess.run([sys.executable, str(PB / "build_tasks.py"), str(CHECKOUT), "--llms", str(llms)], check=True)


def tree_rev():
    """The commit a bundle of the tree as it stands is built from, and whether it is a real one."""
    head, snap = sh("git", "rev-parse", "HEAD"), sh("git", "stash", "create")
    return snap or head, bool(snap)


def bundle(_):
    rev, dirty = tree_rev()
    new = sh("git", "ls-files", "--others", "--exclude-standard", "src", "resources", cwd=HERE.parent)
    if new:
        sys.exit("untracked files would be left out of the bundle, git add them:\n" + new)
    subprocess.run(["bash", str(RIG / "build_bundle.sh"), rev], check=True)
    print("bundle", rev, "(uncommitted changes)" if dirty else "")


def balance():
    """DeepSeek balance, or None. The key goes in a header so it stays off every command line."""
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        return None
    req = urllib.request.Request("https://api.deepseek.com/user/balance", headers={"Authorization": f"Bearer {key}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return float(json.load(r)["balance_infos"][0]["total_balance"])
    except Exception as e:
        print("balance unavailable:", type(e).__name__, file=sys.stderr)
        return None


def harbor_cmd(name, arm, args):
    extra = PROVIDERS[args.provider] if arm in ("samizdat", "samizdat-memory", "team-solve") else []
    if arm in ("samizdat", "samizdat-memory", "team-solve"):
        extra = extra + ["--ak", f"harness_effort={args.effort or DEFAULT_EFFORT[args.provider]}"]
    if args.window and arm in ("samizdat", "samizdat-memory", "team-solve"):
        extra = extra + ["--ak", f"context_window={args.window}"]
    if arm == "samizdat-memory":
        extra = extra + ["--ak", f"seed_job={JOBS / args.seed_job}"]
    cmd = ["harbor", "run", "-p", str(TASKS), *ARMS[arm], *extra, "-n", str(args.concurrent), "-k", str(args.attempts),
           "-o", str(JOBS), "--job-name", f"{name}-{arm}", "-y"]
    for t in args.tasks or []:
        cmd += ["-i", t]
    return cmd


def replay(args):
    """The same tasks with no memory and with what an earlier job's trial left."""
    args.arms = "samizdat,samizdat-memory"
    if not (JOBS / args.seed_job).is_dir():
        sys.exit(f"no job {args.seed_job} under {JOBS}")
    run(args)


def delivered(trial_dir):
    """Memories from an earlier run that were put in front of this one."""
    db = Path(trial_dir) / "agent/samizdat.sqlite3"
    if not db.exists():
        return 0
    c = sqlite3.connect(db)
    run_id = c.execute("select id from runs order by started_at desc limit 1").fetchone()[0]
    old = {r[0] for r in c.execute("select id from knowledge where run_id is not ?", (run_id,))}
    shown = set()
    for data in c.execute("select data from events where run_id = ? and kind in ('recall','exposed')", (run_id,)):
        try:
            shown |= set(json.loads(data[0]).get("ids") or [])
        except (TypeError, ValueError):
            pass
    return len(shown & old)


def memory_verdict(off, on, shown):
    """The weakest claim the pair supports, in words."""
    if not shown:
        return "not measured: no earlier memory reached the run"
    if on - off >= NOISE:
        return "better with memory"
    if off - on >= NOISE:
        return "worse with memory"
    return "no visible difference"


def replay_report(per):
    print("| Task | No memory | With memory | Memories shown | Verdict |\n|---|---|---|---|---|")
    for task in sorted(set(per["samizdat"]) & set(per["samizdat-memory"])):
        off = mean(t["score"] or 0 for t in per["samizdat"][task])
        on = mean(t["score"] or 0 for t in per["samizdat-memory"][task])
        shown = sum(delivered(t["dir"]) for t in per["samizdat-memory"][task])
        print(f"| {task} | {pct(off)} | {pct(on)} | {shown} | {memory_verdict(off, on, shown)} |")
    print()


def run(args):
    arms = args.arms.split(",")
    meta_path = JOBS / f"{args.name}.json"
    if not args.dry_run:
        if meta_path.exists():
            sys.exit(f"{meta_path} exists: pick another name")
        if not TASKS.exists():
            sys.exit("no tasks: run bench.py setup")
        key = "ZHIPU_API_KEY" if args.provider == "glm" else "DEEPSEEK_API_KEY"
        if not os.environ.get(key) or ("pi" in arms and not os.environ.get("DEEPSEEK_API_KEY")):
            sys.exit(f"{key} is not set")
    env = dict(os.environ, DOCKER_DEFAULT_PLATFORM="linux/amd64", PYTHONPATH=f"{PB}{os.pathsep}{RIG}",
               PATH=f"{Path.home()}/.local/bin{os.pathsep}{os.environ['PATH']}")
    cmds = {a: harbor_cmd(args.name, a, args) for a in arms}
    if args.dry_run:
        for c in cmds.values():
            print(" ".join(c))
        return
    version = subprocess.run(["harbor", "--version"], env=env, capture_output=True, text=True).stdout.strip()
    if version != HARBOR:
        print(f"warning: harbor {version}, results so far are from {HARBOR}", file=sys.stderr)
    if {"samizdat", "samizdat-memory", "team-solve"} & set(arms):
        built = (RIG / "samizdat-bundle.rev").read_text().strip()
        if built != tree_rev()[0]:
            print(f"warning: bundle is {built[:12]}, not this tree: bench.py bundle", file=sys.stderr)
    now = lambda: datetime.now(timezone.utc).isoformat(timespec="seconds")
    meta = {"name": args.name, "arms": cmds, "started": now(), "balance_before": balance(), "harbor": version,
            "commit": sh("git", "rev-parse", "HEAD"), "model": MODEL, "provider": args.provider, "attempts": args.attempts,
            "bundle": (RIG / "samizdat-bundle.rev").read_text().strip() if (RIG / "samizdat-bundle.rev").exists() else None,
            "programbench": PROGRAMBENCH[1], "jolt": JOLT_TAG}
    JOBS.mkdir(exist_ok=True)
    LOGS.mkdir(exist_ok=True)
    meta_path.write_text(json.dumps(meta, indent=1))
    procs = {a: subprocess.Popen(c, cwd=PB, env=env, stdout=open(LOGS / f"{args.name}-{a}.log", "w"), stderr=subprocess.STDOUT)
             for a, c in cmds.items()}
    meta["exit"] = {a: p.wait() for a, p in procs.items()}
    meta.update(finished=now(), balance_after=balance())
    meta_path.write_text(json.dumps(meta, indent=1))
    report(args)


def stamp(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def pi_trial(d, secs):
    """Commands, and how long the last bash call sat open if it never came back."""
    out = {"commands": [], "tools": Counter(), "stuck": 0}
    for f in d.glob("agent/pi/sessions/**/*.jsonl"):
        rows = []
        for line in f.open(errors="replace"):
            try:
                rows.append(json.loads(line))
            except ValueError:
                pass
        msgs = [r for r in rows if r.get("type") == "message"]
        pending = set()
        for r in msgs:
            m = r["message"]
            if m.get("role") == "assistant" and isinstance(m.get("content"), list):
                for b in m["content"]:
                    if b.get("type") == "toolCall":
                        pending.add(b["id"])
                        out["tools"][b["name"]] += 1
                        out["commands"].append(json.dumps(b.get("arguments")))
            if m.get("role") == "toolResult":
                pending.discard(m.get("toolCallId"))
        if msgs and pending and secs:
            active = (stamp(msgs[-1]["timestamp"]) - stamp(rows[0]["timestamp"])).total_seconds()
            out["stuck"] = max(0, round(secs - active))
    return out


def samizdat_trial(d):
    c = sqlite3.connect(d / "agent/samizdat.sqlite3")
    hit, miss, cout = c.execute("select sum(cache_hit_tokens),sum(cache_miss_tokens),sum(completion_tokens) from turns").fetchone()
    parts = Counter()
    for (data,) in c.execute("select data from events where kind='context-block' and branch_id!='SUP'"):
        try:
            parts.update(json.loads(data).get("parts") or {})
        except ValueError:
            pass
    return {"tokens": (miss or 0, hit or 0, cout or 0), "parts": parts,
            "tools": Counter(dict(c.execute("select tool_name,count(*) from turns where branch_id!='SUP' group by 1"))),
            "gates": Counter(dict(c.execute("select gate,count(*) from gate_firings group by 1"))),
            "commands": [r[0] or "" for r in c.execute("select args from turns where tool_name in ('shell','eval')")]}


def trial(d):
    r = json.loads((d / "result.json").read_text())
    ae, a = r.get("agent_execution") or {}, r.get("agent_result") or {}
    secs = (stamp(ae["finished_at"]) - stamp(ae["started_at"])).total_seconds() if ae.get("finished_at") else None
    try:
        sc = json.loads((d / "verifier/score.json").read_text())
    except (OSError, ValueError):
        sc = {}
    p = sc.get("provenance") or {}
    t = {"task": d.name.split("__")[0], "dir": str(d), "score": sc.get("score"), "passed": sc.get("passed") or 0,
         "total": sc.get("total") or 0, "error": sc.get("error"), "secs": secs, "clj": p.get("clj_lines") or 0,
         "foreign": p.get("foreign_files") or [], "exception": (r.get("exception_info") or {}).get("exception_type"),
         "stuck": 0, "tools": Counter(), "gates": Counter(), "commands": [], "tokens": None}
    if (d / "agent/samizdat.sqlite3").exists():
        t.update(samizdat_trial(d))
    else:
        t.update(pi_trial(d, secs))
        if a.get("n_input_tokens") is not None:
            cached = a.get("n_cache_tokens") or 0
            t["tokens"] = (a["n_input_tokens"] - cached, cached, a.get("n_output_tokens") or 0)
    t["cost"] = sum(n * x for n, x in zip(t["tokens"], RATES)) if t["tokens"] else None
    t["hung"] = t["stuck"] >= HANG_SECS
    return t


def load(job):
    return [trial(d) for d in sorted(job.glob("*__*")) if (d / "result.json").exists()]


def mean(xs):
    xs = list(xs)
    return sum(xs) / len(xs) if xs else None


def pct(x):
    return "-" if x is None else f"{100 * x:.1f}%"


def by_task(trials):
    out = {}
    for t in trials:
        out.setdefault(t["task"], []).append(t)
    return out


def report(args):
    jobs = sorted(p for p in JOBS.glob(f"{args.name}-*") if p.is_dir())
    arms = {p.name[len(args.name) + 1:]: load(p) for p in jobs}
    if not arms:
        sys.exit(f"nothing under {JOBS}/{args.name}-*")
    tasks = sorted({t["task"] for ts in arms.values() for t in ts})
    names = list(arms)
    meta = JOBS / f"{args.name}.json"
    print(f"## {args.name}\n")
    if meta.exists():
        m = json.loads(meta.read_text())
        # The balance is DeepSeek's, so it says nothing about a run with no arm on it.
        on_deepseek = m.get("provider", "deepseek") == "deepseek" or "pi" in m["arms"] or "hermes" in m["arms"]
        spent = None if None in (m.get("balance_before"), m.get("balance_after")) or not on_deepseek \
            else m["balance_before"] - m["balance_after"]
        print(f"Pi on {m['model']}, samizdat on {m.get('provider', 'deepseek')}, {m['attempts']} attempt(s) per task, commit {m['commit'][:12]}, bundle {str(m['bundle'])[:12]}, "
              f"harbor {m['harbor']}, started {m['started']}" + (f", DeepSeek balance fell ${spent:.2f} during the run, all arms and anything else on that key" if spent is not None else "") + "\n")
    per = {n: by_task(ts) for n, ts in arms.items()}
    if "samizdat-memory" in per and "samizdat" in per:
        replay_report(per)
    print("| Task | " + " | ".join(names) + " |\n|---|" + "---|" * len(names))
    for task in tasks:
        cells = []
        for n in names:
            ts = per[n].get(task, [])
            s = pct(mean(t["score"] or 0 for t in ts)) if ts else "-"
            if len(ts) > 1:
                s += " (" + ", ".join(f"{100 * (t['score'] or 0):.0f}" for t in ts) + ")"
            if any(t["hung"] and not t["score"] for t in ts):
                s += " hung"
            cells.append(s)
        print(f"| {task} | " + " | ".join(cells) + " |")
    print()
    # Task means, so a task with more hidden tests does not outweigh the others.
    usable = lambda ts: [t for t in ts if not (t["hung"] and not t["score"])]
    shared = [t for t in tasks if all(per[n].get(t) for n in names)]
    clean = [t for t in shared if all(usable(per[n][t]) for n in names)]
    print("| | " + " | ".join(names) + " |\n|---|" + "---|" * len(names))
    row = lambda label, f: print(f"| {label} | " + " | ".join(str(f(n)) for n in names) + " |")
    row(f"Mean score, {len(shared)} shared tasks", lambda n: pct(mean(mean(t["score"] or 0 for t in per[n][k]) for k in shared)))
    row(f"Mean without zero-score hangs, {len(clean)} tasks", lambda n: pct(mean(mean(t["score"] or 0 for t in usable(per[n][k])) for k in clean)))
    row("Tests passed", lambda n: f"{sum(t['passed'] for t in arms[n])} / {sum(t['total'] for t in arms[n])}")
    if len(names) == 2:
        a, b = names
        wins = Counter()
        for k in shared:
            x, y = (mean(t["score"] or 0 for t in per[n][k]) for n in (a, b))
            wins[a if x > y else b if y > x else "tie"] += 1
        row("Tasks won", lambda n: wins[n])
    row("Trials", lambda n: len(arms[n]))
    row("Trials cut off by the limit", lambda n: sum(t["exception"] == "AgentTimeoutError" for t in arms[n]))
    row("Trials with a hung call", lambda n: sum(t["hung"] for t in arms[n]))
    row("Mean seconds", lambda n: round(mean(t["secs"] or 0 for t in arms[n])))
    row("Mean Clojure lines", lambda n: round(mean(t["clj"] for t in arms[n])))
    row("Cost at list rates, USD", lambda n: f"{sum(t['cost'] or 0 for t in arms[n]):.2f}")
    row("eval calls", lambda n: sum(t["tools"]["eval"] for t in arms[n]))
    row("shell calls", lambda n: sum(t["tools"]["shell"] + t["tools"]["bash"] for t in arms[n]))
    print()
    totals = {n: sum((t.get("parts") or Counter() for t in arms[n]), Counter()) for n in names}
    for n, p in totals.items():
        if p:
            whole = sum(p.values())
            print(f"What {n}'s prompts held, by characters: " + ", ".join(f"{k} {100 * v / whole:.0f}%" for k, v in p.most_common()) + "\n")
    flags = [(n, t["task"], "foreign files", ", ".join(t["foreign"])) for n in names for t in arms[n] if t["foreign"]]
    flags += [(n, t["task"], "score missing", t["exception"] or t["error"]) for n in names for t in arms[n] if t["score"] is None]
    flags += [(n, t["task"], "possible fetch", m[max(0, x.start() - 50):x.end() + 70]) for n in names for t in arms[n]
              for m in t["commands"] for x in [FETCH.search(m)] if x]
    if flags:
        print("To check by hand:\n")
        for n, task, kind, what in flags[:40]:
            print(f"- {n} {task}, {kind}: `{what}`")
        print()
    gates = Counter()
    for n in names:
        for t in arms[n]:
            gates.update(t["gates"])
    if gates:
        print("Gates fired: " + ", ".join(f"{g} {c}" for g, c in gates.most_common(12)) + "\n")


def main():
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = a.add_subparsers(dest="cmd", required=True)
    s.add_parser("setup").set_defaults(fn=setup)
    s.add_parser("bundle").set_defaults(fn=bundle)
    for cmd, fn in (("run", run), ("replay", replay)):
        r = s.add_parser(cmd)
        r.add_argument("name")
        if cmd == "run":
            r.add_argument("--arms", default="pi,samizdat")
        else:
            r.add_argument("--from", dest="seed_job", required=True, help="the job whose trials' memory to start from")
        r.add_argument("--attempts", type=int, default=1 if cmd == "replay" else 3)
        r.add_argument("--provider", default="deepseek", choices=list(PROVIDERS), help="samizdat's model")
        r.add_argument("--concurrent", type=int, default=1, help="trials at once per arm")
        r.add_argument("--effort", choices=["low", "high", "max"], help="samizdat's reasoning effort, default the vendor's")
        r.add_argument("--window", type=int, help="samizdat's context window, where compaction measures from")
        r.add_argument("--tasks", nargs="*", help="task names, default all")
        r.add_argument("--dry-run", action="store_true")
        r.set_defaults(fn=fn)
    p = s.add_parser("report")
    p.add_argument("name")
    p.set_defaults(fn=report)
    args = a.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
