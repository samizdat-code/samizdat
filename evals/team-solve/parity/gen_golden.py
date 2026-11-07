#!/usr/bin/env python3
"""Write what GVS5H's own multiagent_solve sends, call for call, on a scripted model.

    python3 evals/team-solve/parity/gen_golden.py <GVS5H>/codebase/v2-current/escalation

The scenarios are memdoor's lib/test_parity.py. The result is the definition of a
faithful port: samizdat.team-solve-parity-test drives team-solve with the same
script and compares each request. GVS5H is MIT, Copyright (c) 2026 Persis Capital Inc.
Run again only when the source changes.
"""
import importlib, json, os, shutil, sys, tempfile
from types import SimpleNamespace

ESC = sys.argv[1]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
                   "test", "samizdat", "fixtures", "gvs5h_parity.json")
PROBLEM = "### Question\nAdd two numbers.\n\n### Format: Read the inputs from stdin solve the problem and write the answer to stdout (do not directly test on the sample inputs). Enclose your code within delimiters as follows."
TESTS = [("2 3\n", "5\n"), ("10 7\n", "17\n")]
GOOD = "import sys\na,b=map(int,sys.stdin.read().split())\nprint(a+b)"
BAD = "print(0)"


def role(messages):
    s = messages[0]["content"]
    if s.startswith("You are the PRIMARY orchestrator (manager)"):
        return "plan"
    if s.startswith("You are the FIRST WORKER"):
        return "ideate"
    if s.startswith("You are the PRIMARY orchestrator and manager"):
        return "manage"
    if s.startswith("A worker's solution attempt was CUT OFF"):
        return "cutoff"
    if messages[1]["content"].endswith("Produce the DEFINITIVE final solution now, using all notes and current work."):
        return "finalize"
    return "worker"


def worker_reply(code, notes, nxt="none", status="continue"):
    return f"### CODE\n```python\n{code}\n```\n### NOTES\n- **Idea:** {notes}\n### NEXT\n- {nxt}\n### STATUS\n{status}"


SCENARIOS = {
    "happy": {
        "plan": ["### PLAN\nAdd them.\n### TASKS\n- Read input\n- Print sum\n- Read input"],
        "ideate": ["### NOTES\nTrivial.\n```python\nx\n```\n### NEXT\n- Use int()\n- Use `eval` ```code```"],
        "manage": ["### STATUS\ncontinue\n### NEXT\n- Implement the sum\n### TASKS\n- [todo] Implement the sum\n- [done] Read input",
                   "### STATUS\ndone\n### TASKS\n- [done] Implement the sum"],
        "worker": [worker_reply(GOOD, "sum works", status="solved")],
    },
    "hard": {
        "plan": [("We need answer user's request. Let's think about the cuts..." * 40, "length")],
        "ideate": ["no sections at all, just prose about approaches"],
        "manage": ["### STATUS\ncontinue\n### NEXT\nTry approach A\n### TASKS\n- [todo] Try approach A\n- [wip] Think",
                   "### STATUS\ncontinue\n### NEXT\n* Try approach B\n### TASKS\n- [todo] Try approach B",
                   "### STATUS\ndone\n### TASKS\n- [done] Try approach B",
                   "### STATUS\ncontinue\n### NEXT\nFix the bug\n### TASKS\n- [todo] Fix the bug",
                   "### STATUS\ncontinue\n### NEXT\nfix the bug \n### TASKS\n- [todo] Fix the bug"],
        "worker": [("We need solve. " + "derivation " * 3000, "length"),
                   worker_reply(BAD, "B is wrong somewhere", nxt="check"),
                   worker_reply(BAD, "still wrong")],
        "cutoff": ["<think>hmm</think>It tried approach A and got half way."],
        "finalize": [worker_reply(GOOD, "final", status="solved")],
    },
    "ten": {
        "plan": ["### PLAN\nP\n### TASKS\n" + "\n".join(f"- task {i}" for i in range(15))],
        "ideate": ["### NOTES\nN\n### NEXT\n- a"],
        "manage": [f"### STATUS\ncontinue\n### NEXT\nround {i}\n### TASKS\n- [todo] round {i}" for i in range(12)],
        "worker": [worker_reply(BAD if i % 2 else GOOD, f"r{i}") for i in range(10)],
        "finalize": [("cut " * 100, "length")],
    },
    "empty": {
        "plan": [""],
        "ideate": [""],
        "manage": ["", "", "", "### STATUS\ndone"] + [""] * 10,
        "worker": ["no code here"] * 10,
        "finalize": ["still nothing"],
    },
}


def run(script):
    used, calls = {}, []

    def chat(m, messages, temperature=0.2, meta=None):
        r = role(messages)
        i = used.get(r, 0)
        used[r] = i + 1
        entry = script[r][min(i, len(script[r]) - 1)]
        content, finish = (entry, "stop") if isinstance(entry, str) else entry
        calls.append({"role": r, "temperature": temperature,
                      "system": messages[0]["content"], "user": messages[1]["content"]})
        if meta is not None:
            meta["finish_reason"] = finish
        return content

    ws_root = tempfile.mkdtemp()
    os.environ.update(MULTIAGENT_WS=ws_root, MULTIAGENT_MODEL="groq:small-model",
                      MULTIAGENT_MAX_ITERS="10", MULTIAGENT_MAX_TASKS="12")
    sys.path.insert(0, ESC)
    orch = importlib.import_module("orchestrator")
    ma = importlib.reload(importlib.import_module("multiagent"))
    ma.chat = chat
    tests = [SimpleNamespace(testtype="stdin", input=i, output=o) for i, o in TESTS]
    cwd = os.getcwd()
    os.chdir(ws_root)
    try:
        ma.multiagent_solve(PROBLEM, orch.CODE_SPEC, tests=tests)
    finally:
        os.chdir(cwd)
    final = open(os.path.join(ws_root, ma._slug(PROBLEM), "solution.py")).read()
    shutil.rmtree(ws_root)
    return calls, final


out = {"problem": PROBLEM, "tests": TESTS, "scenarios": {}}
for name, script in SCENARIOS.items():
    calls, final = run(script)
    wire = {k: [list(e) if isinstance(e, tuple) else e for e in v] for k, v in script.items()}
    out["scenarios"][name] = {"script": wire, "calls": calls, "final": final}
    print(name, len(calls), "calls:", " ".join(c["role"] for c in calls))
os.makedirs(os.path.dirname(OUT), exist_ok=True)
json.dump(out, open(OUT, "w"), indent=0, ensure_ascii=False)
