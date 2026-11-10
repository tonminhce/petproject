"""Autonomous remediation scheduler: DISCOVER -> PLAN -> IMPLEMENT -> TEST -> VERIFY -> REOPEN."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from . import persistence, verification
from .task_registry import TaskRegistry


class Scheduler:
    def __init__(self, root: Path, max_iterations: int = 20):
        self.root = root
        self.max_iterations = max_iterations
        self.reg = TaskRegistry(root)
        self.store = verification.EvidenceStore(root)
        self.runs_dir = root / "production-audit" / "runs"

    def _run_id(self) -> str:
        return time.strftime("%Y%m%dT%H%M%S")

    def run(self) -> dict[str, Any]:
        run_id = self._run_id()
        rd = self.runs_dir / run_id
        for sub in ("source-reviews", "test-results", "benchmarks", "traces", "independent-reviews", "reports"):
            (rd / sub).mkdir(parents=True, exist_ok=True)
        iterations = 0
        history = []
        skipped: list[str] = []
        while iterations < self.max_iterations:
            iterations += 1
            task = self.reg.next_task()
            if task is None:
                status = "completed"
                break
            outcome = self._process(task)
            if outcome == "SKIPPED":
                # Nothing to verify - do not spin on the same task forever.
                skipped.append(task["id"])
                if len(skipped) >= 5:
                    status = "exhausted"
                    break
                continue
            self.reg.transition(task["id"], "IN_PROGRESS", note=f"iteration {iterations}")
            self.reg.record_attempt(task["id"], "started", note=f"iteration {iterations}")
            outcome = self._process(task)
            history.append({"iteration": iterations, "task": task["id"], "outcome": outcome})
            persistence.append_jsonl(
                self.runs_dir / run_id / "iterations.jsonl",
                {"iteration": iterations, "task": task["id"], "outcome": outcome},
            )
        else:
            status = "exhausted"
        summary = {
            "command": "run",
            "run_id": run_id,
            "status": status,
            "iterations": iterations,
            "history": history,
            "tasks": self.reg.summary(),
        }
        persistence.write_json(rd / "run-summary.json", summary)
        return summary

    def _process(self, task: dict[str, Any]) -> str:
        """Execute one task's declared verification command, then judge."""
        cmd = task.get("verify_command")
        if not cmd:
            # No automated verification is declared for this task. That is NOT
            # an external blocker - it means the task has not been implemented
            # yet and there is nothing to verify. Leave it READY and move on,
            # rather than poisoning the registry with a false BLOCKED.
            self.reg.record_attempt(task["id"], "skipped",
                                    note="no verify_command declared - task left READY")
            return "SKIPPED"
        import shlex

        res = verification._run(shlex.split(cmd), self.root, timeout=task.get("verify_timeout", 900))
        passed = res.get("returncode") == 0
        self.reg.record_attempt(task["id"], "pass" if passed else "fail", note=cmd)
        self.store.record(
            f"task:{task['id']}",
            {"task": task["id"], "command": cmd, "result": res, "passed": passed},
        )
        if passed:
            self.reg.transition(task["id"], "VERIFIED", note=f"verify_command passed: {cmd}")
            return "VERIFIED"
        self.reg.transition(task["id"], "FAILED_VERIFICATION", note=f"verify_command failed: {cmd}")
        return "FAILED_VERIFICATION"
