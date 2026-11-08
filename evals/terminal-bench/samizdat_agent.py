"""Harbor agent: samizdat inside the task container, driven over its HTTP API.

  PYTHONPATH=evals/terminal-bench harbor run -d terminal-bench/terminal-bench-2-1 \
    --agent-import-path samizdat_agent:SamizdatAgent --ak arm=team-solve

The task's instruction goes in as written. Task images have no samizdat, so the
bundle from build_bundle.sh is unpacked at /opt/samizdat, and the project files it
adds to the task directory are removed before the verifier runs.
"""
import asyncio
import json
import os
import shlex
import sqlite3
import tempfile
import time
import tomllib
from pathlib import Path

from harbor.agents.installed.base import BaseInstalledAgent, with_prompt_template
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext

HERE = Path(__file__).resolve().parent
PORT = 3985
# Seconds before the task limit: the run's own limit, and the hard stop that leaves
# time to download the journal and clean up.
RUN_MARGIN = 75
STOP_MARGIN = 45
TERMINAL = {"completed", "failed", "exhausted", "aborted", "abandoned", "cancelled", "parked"}
LIBS = "ca-certificates curl git libsqlite3-0 zlib1g"
# OpenSSL 3 when the image has a package for it. An image too old for one (Debian 11)
# keeps its OpenSSL 1.1, which jolt's TLS also works with, so a missing package is no
# reason to fail the install.
SSL = ("for p in libssl3t64 libssl3; do apt-cache show $p >/dev/null 2>&1 "
       "&& DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $p >/dev/null && break; done; true")


class SamizdatAgent(BaseInstalledAgent):
    def __init__(self, *args, arm="team-solve", provider="deepseek-flash", model_id="deepseek-flash",
                 key_env="DEEPSEEK_API_KEY", provider_type="deepseek", max_turns=300,
                 eval_mode="off", harness_effort=None, seed_job=None, context_window=None, style=None,
                 **kwargs):
        super().__init__(*args, **kwargs)
        self.arm = arm
        # A gates.edn :styles key, such as lean; none is the full loop.
        self.style = style
        self.provider = provider
        self.model_id = model_id
        self.key_env = key_env
        self.provider_type = provider_type
        self.max_turns = int(max_turns)
        self.eval_mode = eval_mode
        self.harness_effort = harness_effort
        self.seed_job = seed_job
        self.context_window = context_window

    @staticmethod
    def name() -> str:
        return "samizdat"

    def version(self):
        rev = HERE / "samizdat-bundle.rev"
        return rev.read_text().strip()[:12] if rev.exists() else None

    async def install(self, environment: BaseEnvironment) -> None:
        await self.exec_as_root(
            environment,
            # Only what is missing, one package at a time, carrying on past one that will
            # not install: an old image's archive can 404 on a package (Debian 11's git).
            command=(f"apt-get update -qq; for p in {LIBS}; do dpkg -s $p >/dev/null 2>&1 || "
                     f"DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $p >/dev/null 2>&1 || "
                     f"echo \"could not install $p\" >&2; done; {SSL}"),
        )
        await environment.upload_file(HERE / "samizdat-bundle.tgz", "/tmp/samizdat-bundle.tgz")
        await self.exec_as_root(
            environment,
            command="tar -xzf /tmp/samizdat-bundle.tgz -C / && rm -f /tmp/samizdat-bundle.tgz "
                    "&& chmod -R a+rX /opt/samizdat",
        )

    async def _sh(self, environment, command, env=None, timeout=None):
        return await environment.exec(command=command, env=env, timeout_sec=timeout)

    async def _api(self, environment, token, method, path, body=None):
        data = f"-d {shlex.quote(json.dumps(body))}" if body is not None else ""
        timeout = 30 if method == 'GET' else 300
        curl = (f"curl -sf -m {timeout} -X {method} -H 'Authorization: Bearer {token}' "
                f"-H 'Content-Type: application/json' {data} http://127.0.0.1:{PORT}{path}")
        # An image whose archive cannot install curl (Debian 11's 404s) asks through
        # the bundled jolt instead, failing the same way curl -f does.
        form = ("(require '[jolt.http-client :as h])"
                f"(let [r (h/request {{:method :{method.lower()} :url \"http://127.0.0.1:{PORT}{path}\""
                f" :headers {{\"Authorization\" \"Bearer {token}\"}} :content-type :json"
                f" :socket-timeout {timeout * 1000} :throw-exceptions false"
                + (" :body (System/getenv \"SZ_BODY\")" if body is not None else "") +
                "})] (if (< (:status r) 400) (print (:body r)) (System/exit 22)))")
        jolt = ("cd /opt/samizdat/repo && JOLT_GITLIBS_DIR=/opt/samizdat/gitlibs "
                f"/opt/samizdat/jolt/jolt -e {shlex.quote(form)}")
        r = await self._sh(
            environment,
            f"if command -v curl >/dev/null; then {curl}; else {jolt}; fi",
            env={"SZ_BODY": json.dumps(body)} if body is not None else None,
        )
        if r.return_code != 0:
            return None
        try:
            return json.loads(r.stdout or "null")
        except json.JSONDecodeError:
            return None

    @staticmethod
    def _task_limit(logs_dir, cache=Path.home() / ".cache/harbor/tasks"):
        """The task's declared agent limit in seconds, or None. Harbor hands the task
        directory only to its oracle agent, so it is found from the trial's config,
        whose ref names the task's directory in Harbor's cache. Harbor writes that
        config after building the agent: call this from run(), not the constructor."""
        try:
            task = json.loads((Path(logs_dir).parent / "config.json").read_text())["task"]
            if task.get("path"):  # a task on disk rather than from a registry
                found = Path(task["path"]) / "task.toml"
            else:
                found = next(Path(cache).rglob(f"{task['ref'].split(':')[-1]}/task.toml"))
            return float(tomllib.loads(found.read_text())["agent"]["timeout_sec"])
        except Exception:
            return None

    async def _seed_memory(self, environment, root):
        """Start from the database an earlier trial of this task left, so it opens with that memory."""
        if not self.seed_job:
            return
        task = Path(self.logs_dir).parent.name.split("__")[0]
        found = sorted(Path(self.seed_job).glob(f"{task}__*/agent/samizdat.sqlite3"))
        if not found:
            raise RuntimeError(f"no earlier trial of {task} under {self.seed_job}")
        with tempfile.TemporaryDirectory() as tmp:
            copy = Path(tmp) / "samizdat.sqlite3"
            src, dst = sqlite3.connect(found[-1]), sqlite3.connect(copy)
            src.backup(dst)
            src.close(); dst.close()
            await environment.upload_file(copy, f"{root}/.samizdat/samizdat.sqlite3")

    def _cleanup_command(self, git_was_ours):
        rm_git = "rm -rf .git; " if git_was_ours else ""
        return f"cd {self._root} && rm -rf .samizdat .jolt .gitignore.samizdat; {rm_git}true"

    @with_prompt_template
    async def run(self, instruction: str, environment: BaseEnvironment, context: AgentContext) -> None:
        key = os.environ[self.key_env]
        began = time.monotonic()
        # Harbor kills the agent at the task's limit and that scores as a failure.
        # The run is told a limit short of it, and stopped here before it arrives.
        limit = self._task_limit(self.logs_dir)
        stop_at = began + limit - STOP_MARGIN if limit else None
        run_limit = f" :time-limit-seconds {int(limit - RUN_MARGIN)}" if limit else ""
        self._root = (await self._sh(environment, "pwd")).stdout.strip() or "/app"
        root = self._root
        config = (
            "{:providers {:%s {:type :%s :model %s :api-key-env \"%s\"%s}}\n"
            " :roles {:default :%s}\n"
            # The grader's tests decide here and Terminus has no gate of its own.
            " :run {:loop \"%s\" :require-test? false :verify-focused? false%s}\n"
            " :shell {:sandbox :none}\n"
            # Off where the task picks the language. :project is a Jolt REPL rooted at the
            # task, for benchmarks solved in Jolt.
            " :eval {:sandbox :none :mode :%s}}\n"
            % (self.provider, self.provider_type, json.dumps(self.model_id), self.key_env,
               f" :context-window {int(self.context_window)}" if self.context_window else "",
               self.provider, self.arm,
               run_limit + (f" :style :{self.style}" if self.style else ""), self.eval_mode)
        )
        git = await self._sh(environment, f"cd {root} && git rev-parse --is-inside-work-tree 2>/dev/null")
        git_was_ours = git.return_code != 0
        await self._sh(environment, " && ".join([
            'cp -rn /opt/samizdat/dotjolt "$HOME/.jolt" 2>/dev/null || true',
            f"mkdir -p {root}/.samizdat",
            f"printf %s {shlex.quote(config)} > {root}/.samizdat/config.edn",
        ]))
        await self._seed_memory(environment, root)
        if git_was_ours:
            await self._sh(environment, (
                f"cd {root} && git init -q && printf '.samizdat/\\n' >> .git/info/exclude && git add -A "
                "&& git -c user.email=eval@example.invalid -c user.name=eval commit -qm task --allow-empty"))
        else:
            await self._sh(environment, f"cd {root} && printf '.samizdat/\\n' >> \"$(git rev-parse --git-path info/exclude)\"")
        serve = (
            "cd /opt/samizdat/repo && "
            f"HARNESS_ROOT={root} HARNESS_PORT={PORT} HARNESS_NREPL_PORT={PORT + 4000} "
            + (f"HARNESS_REASONING_EFFORT={self.harness_effort} " if self.harness_effort else "") +
            "JOLT_GITLIBS_DIR=/opt/samizdat/gitlibs PATH=/opt/samizdat/jolt:$PATH "
            f"nohup jolt -m samizdat.core > /tmp/samizdat-serve.log 2>&1 &"
        )
        await self._sh(environment, serve, env={self.key_env: key})
        for _ in range(90):
            # curl when the image has it, else a bare TCP connect: an image may lack curl.
            if (await self._sh(environment, f"curl -sf -m 2 http://127.0.0.1:{PORT}/health || "
                                            f"bash -c 'exec 3<>/dev/tcp/127.0.0.1/{PORT}'")).return_code == 0:
                break
            await asyncio.sleep(2)
        token = (await self._sh(environment, f'cat "$HOME/.config/samizdat/http/{PORT}.token"')).stdout.strip()
        started = await self._api(environment, token, "POST", "/v1/runs",
                                  {"problem": instruction, "beam_width": 1, "max_turns": self.max_turns})
        run_id = (started or {}).get("run_id")
        meta = {"arm": self.arm, "provider": self.provider, "run_id": run_id, "status": "no-start", "time_limit": limit}
        try:
            if run_id:
                await self._api(environment, token, "POST", f"/v1/runs/{run_id}/interventions",
                                {"kind": "grant", "payload": {"pattern": "**"}})
                meta["status"] = "timeout"
                misses = 0
                while True:
                    got = await self._api(environment, token, "GET", f"/v1/runs/{run_id}")
                    misses = misses + 1 if got is None else 0
                    if misses >= 3:
                        # An OOM kill looks like this. Nothing more can happen, so
                        # score what is on disk now rather than idling to the limit.
                        meta["server_gone_at"] = round(time.monotonic() - began)
                        meta["status"] = "server-gone"
                        break
                    status = ((got or {}).get("run") or {}).get("status")
                    if status in TERMINAL:
                        meta["status"] = status
                        break
                    if stop_at and time.monotonic() >= stop_at:
                        meta["status"] = "time-limit"
                        break
                    await asyncio.sleep(10)
        finally:
            await self._finish(environment, context, meta, git_was_ours)

    async def _finish(self, environment, context, meta, git_was_ours):
        await self._sh(environment, "pkill -f samizdat.core; sleep 1; true")
        out = self.logs_dir
        db = f"{self._root}/.samizdat/samizdat.sqlite3"
        for remote, local in ((db, "samizdat.sqlite3"),
                              # The tail of the run is here until a checkpoint.
                              (db + "-wal", "samizdat.sqlite3-wal"),
                              (db + "-shm", "samizdat.sqlite3-shm"),
                              ("/tmp/samizdat-serve.log", "serve.log")):
            try:
                await environment.download_file(remote, out / local)
            except Exception as e:  # keep the verifier running whatever is missing
                meta.setdefault("missing", []).append(f"{local}: {e}")
        (out / "samizdat.json").write_text(json.dumps(meta, indent=1))
        await self._sh(environment, self._cleanup_command(git_was_ours))
