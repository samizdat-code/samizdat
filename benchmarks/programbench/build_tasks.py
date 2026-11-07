#!/usr/bin/env python3
"""Turn ProgramBench instances into Harbor tasks solved in Jolt.

Usage: build_tasks.py <programbench checkout> [--limit SECONDS]
Tasks go to ./tasks (not committed). Test archives hold the original source; Harbor copies
tests/ into the container only at grading, so no harness can read them while it works.
"""
import argparse, json, re, shutil, urllib.request
from pathlib import Path

HERE = Path(__file__).parent
BLOBS = "https://huggingface.co/datasets/programbench/ProgramBench-Tests/resolve/main"
JOLT = "v0.8.24"
PILOT = ["cmatsuoka__figlet.202a0a8", "sclevine__yj.8016400", "wfxr__csview.8ac4de0",
         "wfxr__code-minimap.0ddeea5", "miserlou__loop.209927c", "sirwart__ripsecrets.34c9e03",
         "drew-alleman__datasurgeon.d257cee", "anordal__shellharden.6a6ffd4",
         "mgdm__htmlq.6e31bc8", "nachoparker__dutree.44e877d"]


def build(iid, pb, limit, llms):
    src = pb / "src/programbench/data/tasks" / iid
    meta = dict(re.findall(r"^(\w+): (.+)$", (src / "task.yaml").read_text(), re.M))
    name = iid.split("__")[1].split(".")[0]
    out = HERE / "tasks" / name
    if out.exists():
        shutil.rmtree(out)
    (out / "tests/blobs").mkdir(parents=True)
    (out / "environment").mkdir()
    plan = json.loads((src / "tests.json").read_text())
    for branch, v in plan["branches"].items():
        if not v.get("ignored"):
            urllib.request.urlretrieve(f"{BLOBS}/{iid}/tests/{branch}.tar.gz", out / "tests/blobs" / f"{branch}.tar.gz")
    shutil.copy(src / "tests.json", out / "tests/tests.json")
    shutil.copy(HERE / "grade.py", out / "tests/grade.py")
    (out / "tests/test.sh").write_text("#!/bin/sh\nexec python3 /tests/grade.py\n")
    shutil.copy(HERE / "instruction.md", out / "instruction.md")
    shutil.copy(llms, out / "environment/llms.txt")
    image = f"programbench/{iid.replace('__', '_1776_')}:task_cleanroom_v6"
    # One runaway command filled the VM's disk and took other trials down with it.
    (out / "environment/docker-compose.yaml").write_text(
        "services:\n  main:\n    ulimits:\n      fsize: %d\n" % (3 * 2**30))
    (out / "environment/Dockerfile").write_text((HERE / "Dockerfile.tmpl").read_text().format(image=image, jolt=JOLT))
    (out / "task.toml").write_text((HERE / "task.toml.tmpl").read_text().format(
        name=name, repo=meta["repository"], iid=iid, language=meta["language"],
        difficulty=meta["difficulty"], limit=float(limit)))
    print(name, sum(len(v["tests"]) for v in plan["branches"].values() if not v.get("ignored")))


if __name__ == "__main__":
    a = argparse.ArgumentParser()
    a.add_argument("checkout", type=Path)
    a.add_argument("--limit", type=int, default=1800)
    a.add_argument("--llms", type=Path, default=Path.home() / "src/jolt-lang/jolt/llms.txt")
    a.add_argument("--only", nargs="*", default=PILOT)
    args = a.parse_args()
    for iid in args.only:
        build(iid, args.checkout, args.limit, args.llms)
