"""Requirements registry: reconciles external/standards requirements with findings."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from . import persistence


class Requirements:
    def __init__(self, root: Path):
        self.root = root
        self.path = root / "production-audit" / "state" / "requirements.json"
        data = persistence.read_json(self.path, default=None)
        if not isinstance(data, dict) or "requirements" not in data:
            data = {"schema_version": 1, "requirements": {}}
        self.data: dict[str, Any] = data

    def upsert(self, req: dict[str, Any]) -> dict[str, Any]:
        rid = req["id"]
        self.data["requirements"][rid] = {**self.data["requirements"].get(rid, {}), **req}
        return self.data["requirements"][rid]

    def save(self) -> None:
        persistence.write_json(self.path, self.data)

    @property
    def requirements(self) -> dict[str, dict[str, Any]]:
        return self.data["requirements"]

    def coverage(self) -> dict[str, Any]:
        """Map requirements -> linked tasks/findings, flagging uncovered items."""
        from .task_registry import TaskRegistry

        reg = TaskRegistry(self.root)
        tasks = reg.tasks
        uncovered = []
        for rid, r in self.requirements.items():
            linked = [
                t["id"]
                for t in tasks.values()
                if rid in t.get("requirement_refs", []) or r.get("id") in t.get("requirement_refs", [])
            ]
            r["linked_tasks"] = linked
            if r.get("mandatory", True) and not linked:
                uncovered.append(rid)
        self.save()
        return {
            "total": len(self.requirements),
            "uncovered_mandatory": uncovered,
            "coverage_pct": round(
                100.0
                * sum(1 for r in self.requirements.values() if r.get("linked_tasks"))
                / len(self.requirements),
                2,
            )
            if self.requirements
            else 0.0,
        }
