#!/usr/bin/env python3
"""Grade a Jolt submission in /workspace: build it, run the behavioural tests, score the
fraction passed. Mirrors ProgramBench's eval, minus its container-per-branch machinery."""
import hashlib, json, os, re, shutil, subprocess, sys, tarfile, time
import xml.etree.ElementTree as ET
from pathlib import Path

WORKSPACE, TESTS, LOGS = Path("/workspace"), Path("/tests"), Path("/logs/verifier")
BUILT = Path("/opt/graded-executable")
BUILD_TIMEOUT, BRANCH_TIMEOUT, TEST_TIMEOUT = 600, 900, 30
FOREIGN = {".go", ".rs", ".c", ".cc", ".cpp", ".h", ".hpp", ".py", ".js", ".ts", ".java", ".rb", ".pl"}
OTHER_BUILD = re.compile(r"\b(gcc|cc|clang|g\+\+|go\s+build|cargo|rustc|python3?|node|perl|ruby|javac)\b")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def provenance():
    """What the submission is made of. A build that does not go through jolt scores zero;
    other source files are listed for review, since an agent may keep scratch scripts."""
    clj, foreign = [], []
    for p in WORKSPACE.rglob("*"):
        if ".git" in p.parts or not p.is_file():
            continue
        if p.suffix in (".clj", ".cljc"):
            clj.append(str(p.relative_to(WORKSPACE)))
        elif p.suffix in FOREIGN:
            foreign.append(str(p.relative_to(WORKSPACE)))
    script = WORKSPACE / "compile.sh"
    text = script.read_text(errors="replace") if script.exists() else ""
    return {"clj_files": sorted(clj), "foreign_files": sorted(foreign),
            "clj_lines": sum(len((WORKSPACE / c).read_text(errors="replace").splitlines()) for c in clj),
            "compile_uses_jolt": "jolt build" in text,
            "compile_uses_other": sorted(set(m.group(1) for m in OTHER_BUILD.finditer(text)))}


def build(report):
    prov = report["provenance"] = provenance()
    if not (WORKSPACE / "compile.sh").exists():
        return "no compile.sh"
    if not prov["clj_files"] or not prov["compile_uses_jolt"] or prov["compile_uses_other"]:
        return "not built with jolt"
    (WORKSPACE / "executable").unlink(missing_ok=True)
    r = subprocess.run("chmod +x ./compile.sh && ./compile.sh", shell=True, cwd=WORKSPACE,
                       capture_output=True, text=True, timeout=BUILD_TIMEOUT)
    report["build_log"] = (r.stdout + r.stderr)[-4000:]
    built = WORKSPACE / "executable"
    if r.returncode != 0 or not built.exists():
        return f"build failed, exit {r.returncode}"
    if sha(built) == Path("/opt/gold.sha256").read_text().split()[0] and not os.environ.get("GRADE_GOLD"):
        return "executable is the original program"
    shutil.copy(built, BUILT)
    BUILT.chmod(0o755)
    return None


def passed_in(xml_path):
    if not xml_path.exists():
        return set()
    out = set()
    for case in ET.parse(xml_path).getroot().iter("testcase"):
        if not any(c.tag in ("failure", "error", "skipped") for c in case):
            out.add(f"{case.get('classname')}.{case.get('name')}")
    return out


def run_branch(branch, tests):
    """Tests hard-code /workspace, so each branch gets it wiped and refilled. The built
    binary is standalone and kept outside it."""
    for p in WORKSPACE.iterdir():
        shutil.rmtree(p) if p.is_dir() and not p.is_symlink() else p.unlink()
    with tarfile.open(TESTS / "blobs" / f"{branch}.tar.gz") as t:
        t.extractall(WORKSPACE, filter="tar")
    shutil.copy(BUILT, WORKSPACE / "executable")
    (WORKSPACE / "executable").chmod(0o755)
    run = WORKSPACE / "eval" / "run.sh"
    run.write_text(re.sub(r"--timeout=\d+", f"--timeout={TEST_TIMEOUT}",
                          run.read_text().replace("--timeout-method=thread", "--timeout-method=signal")))
    env = dict(os.environ, PYTEST_XDIST_AUTO_NUM_WORKERS="2")
    try:
        subprocess.run(["bash", "eval/run.sh"], cwd=WORKSPACE, env=env, capture_output=True, timeout=BRANCH_TIMEOUT)
    except subprocess.TimeoutExpired:
        pass
    return passed_in(WORKSPACE / "eval" / "results.xml") & set(tests)


def main():
    began = time.time()
    report = {"score": 0.0, "passed": 0, "total": 0, "error": None, "branches": {}}
    plan = json.loads((TESTS / "tests.json").read_text())["branches"]
    active = {b: v["tests"] for b, v in plan.items() if not v.get("ignored")}
    report["total"] = sum(len(t) for t in active.values())
    try:
        report["error"] = build(report)
    except subprocess.TimeoutExpired:
        report["error"] = "build timed out"
    if not report["error"]:
        for branch, tests in active.items():
            got = run_branch(branch, tests)
            report["branches"][branch] = [len(got), len(tests)]
            report["passed"] += len(got)
        report["score"] = report["passed"] / report["total"] if report["total"] else 0.0
    report["grade_seconds"] = round(time.time() - began)
    LOGS.mkdir(parents=True, exist_ok=True)
    (LOGS / "score.json").write_text(json.dumps(report, indent=1))
    (LOGS / "reward.txt").write_text(f"{report['score']:.6f}")
    print(json.dumps({k: report[k] for k in ("score", "passed", "total", "error", "branches", "grade_seconds")}))


if __name__ == "__main__":
    sys.exit(main())
