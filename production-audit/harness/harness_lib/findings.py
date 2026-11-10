"""Findings register: the consolidated, evidence-backed defect ledger."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from . import persistence

CATEGORIES = {
    "security",
    "distributed-systems",
    "correctness",
    "database",
    "performance",
    "configuration",
    "observability",
    "testing",
    "deployment",
    "documentation",
}

PRIORITIES = {"P0", "P1", "P2", "P3"}
STATUSES = {
    "OPEN",
    "IN_REMEDIATION",
    "REMEDIATED",
    "VERIFIED",
    "RISK_ACCEPTED",
    "NOT_APPLICABLE",
    "FALSE_POSITIVE",
}


class Findings:
    def __init__(self, root: Path):
        self.root = root
        self.path = root / "production-audit" / "state" / "findings.json"
        data = persistence.read_json(self.path, default=None)
        if not isinstance(data, dict) or "findings" not in data:
            data = {"schema_version": 1, "findings": {}}
        self.data: dict[str, Any] = data

    @property
    def findings(self) -> dict[str, dict[str, Any]]:
        return self.data["findings"]

    def upsert(self, finding: dict[str, Any]) -> dict[str, Any]:
        fid = finding["id"]
        prev = self.findings.get(fid, {})
        merged = {**prev, **finding}
        merged.setdefault("status", "OPEN")
        merged.setdefault("verification", "NOT_VERIFIED")
        merged.setdefault("evidence_class", "SOURCE_VERIFIED")
        merged.setdefault("history", [])
        self.findings[fid] = merged
        return merged

    def set_status(self, fid: str, status: str, note: str = "") -> dict[str, Any]:
        if status not in STATUSES:
            raise ValueError(f"invalid status {status}")
        f = self.findings[fid]
        old = f.get("status")
        f["status"] = status
        f.setdefault("history", []).append(
            {"from": old, "to": status, "at": time.strftime("%Y-%m-%dT%H:%M:%S"), "note": note}
        )
        self.save()
        return f

    def save(self) -> None:
        persistence.write_json(self.path, self.data)

    def summary(self) -> dict[str, Any]:
        by_priority: dict[str, int] = {}
        by_status: dict[str, int] = {}
        by_category: dict[str, int] = {}
        for f in self.findings.values():
            by_priority[f.get("priority", "?")] = by_priority.get(f.get("priority", "?"), 0) + 1
            by_status[f.get("status", "OPEN")] = by_status.get(f.get("status", "OPEN"), 0) + 1
            by_category[f.get("category", "?")] = by_category.get(f.get("category", "?"), 0) + 1
        release_blocking = [
            fid
            for fid, f in self.findings.items()
            if f.get("priority") in ("P0", "P1")
            and f.get("status") not in ("VERIFIED", "RISK_ACCEPTED", "NOT_APPLICABLE", "FALSE_POSITIVE")
        ]
        return {
            "total": len(self.findings),
            "by_priority": dict(sorted(by_priority.items())),
            "by_status": dict(sorted(by_status.items())),
            "by_category": dict(sorted(by_category.items())),
            "release_blocking": sorted(release_blocking),
        }
