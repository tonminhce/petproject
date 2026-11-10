"""Task registry: atomic task lifecycle with dependency ordering.

Task states (per audit contract):
DISCOVERED -> NORMALIZED -> READY -> IN_PROGRESS -> IMPLEMENTED
  -> VERIFYING -> VERIFIED | FAILED_VERIFICATION -> REOPENED
  BLOCKED, NOT_APPLICABLE_APPROVED
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import persistence

STATES = [
    "DISCOVERED",
    "NORMALIZED",
    "READY",
    "IN_PROGRESS",
    "IMPLEMENTED",
    "VERIFYING",
    "VERIFIED",
    "FAILED_VERIFICATION",
    "REOPENED",
    "BLOCKED",
    "NOT_APPLICABLE_APPROVED",
]

TERMINAL_OK = {"VERIFIED", "NOT_APPLICABLE_APPROVED"}


class TaskRegistry:
    def __init__(self, root: Path):
        self.root = root
        self.path = root / "production-audit" / "state" / "tasks.json"
        data = persistence.read_json(self.path, default=None)
        if not isinstance(data, dict) or "tasks" not in data:
            data = {"schema_version": 1, "tasks": {}}
        self.data: dict[str, Any] = data

    # ---------------------------------------------------------------- io
    def save(self) -> None:
        persistence.write_json(self.path, self.data)

    def log(self, event: str, **fields: Any) -> None:
        persistence.append_jsonl(
            self.root / "state" / "events.jsonl",
            {"event": event, "timestamp": _now(), **fields},
        )

    # ------------------------------------------------------------- tasks
    @property
    def tasks(self) -> dict[str, dict[str, Any]]:
        return self.data["tasks"]

    def get(self, task_id: str) -> dict[str, Any] | None:
        return self.tasks.get(task_id)

    def upsert(self, task: dict[str, Any]) -> dict[str, Any]:
        tid = task["id"]
        existing = self.tasks.get(tid, {})
        merged = {**existing, **task}
        merged.setdefault("state", "DISCOVERED")
        merged.setdefault("attempts", [])
        merged.setdefault("dependencies", [])
        merged.setdefault("evidence", [])
        merged.setdefault("history", [])
        self.tasks[tid] = merged
        return merged

    def transition(self, task_id: str, new_state: str, note: str = "", **fields: Any) -> dict[str, Any]:
        if new_state not in STATES:
            raise ValueError(f"unknown task state: {new_state}")
        t = self.tasks.get(task_id)
        if t is None:
            raise KeyError(f"unknown task: {task_id}")
        old = t.get("state")
        t["state"] = new_state
        t.update(fields)
        t.setdefault("history", []).append(
            {"from": old, "to": new_state, "at": _now(), "note": note}
        )
        self.log("task.transition", task=task_id, **{"from": old, "to": new_state, "note": note})
        return t

    def record_attempt(self, task_id: str, outcome: str, note: str = "", **fields: Any) -> None:
        t = self.tasks[task_id]
        t.setdefault("attempts", []).append(
            {"at": _now(), "outcome": outcome, "note": note, **fields}
        )

    # ------------------------------------------------------- selection
    def ready_tasks(self) -> list[dict[str, Any]]:
        out = []
        for t in self.tasks.values():
            if t.get("state") not in ("READY", "REOPENED", "FAILED_VERIFICATION", "IN_PROGRESS"):
                continue
            if t.get("state") == "IN_PROGRESS":
                out.append(t)
                continue
            deps = t.get("dependencies", [])
            if all(self.tasks.get(d, {}).get("state") in TERMINAL_OK for d in deps):
                out.append(t)
        order = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
        out.sort(key=lambda t: (order.get(t.get("priority", "P3"), 9), t.get("id", "")))
        return out

    def next_task(self) -> dict[str, Any] | None:
        ready = self.ready_tasks()
        return ready[0] if ready else None

    def summary(self) -> dict[str, Any]:
        counts: dict[str, int] = {}
        prio: dict[str, int] = {}
        for t in self.tasks.values():
            counts[t.get("state", "DISCOVERED")] = counts.get(t.get("state", "DISCOVERED"), 0) + 1
            p = t.get("priority", "P3")
            prio[p] = prio.get(p, 0) + 1
        mandatory_open = [
            t["id"]
            for t in self.tasks.values()
            if t.get("mandatory", True)
            and t.get("state") not in TERMINAL_OK
            and t.get("state") != "NOT_APPLICABLE_APPROVED"
        ]
        return {
            "total": len(self.tasks),
            "by_state": dict(sorted(counts.items())),
            "by_priority": dict(sorted(prio.items())),
            "mandatory_open": sorted(mandatory_open),
            "mandatory_open_count": len(mandatory_open),
        }

    # ------------------------------------------------- regression link
    def invalidate_by_paths(self, changed_paths: list[str]) -> list[str]:
        """Reopen tasks whose verification depends on changed source paths."""
        touched = set(changed_paths)
        reopened = []
        for t in self.tasks.values():
            watched = set(t.get("watch_paths", []))
            if not watched or t.get("state") not in TERMINAL_OK:
                continue
            if watched & touched:
                self.transition(
                    t["id"],
                    "REOPENED",
                    note=f"source changed: {sorted(watched & touched)[:5]}",
                    reopened_reason="source_change",
                )
                reopened.append(t["id"])
        return reopened


def _now() -> str:
    import datetime

    return datetime.datetime.now().isoformat(timespec="seconds")
