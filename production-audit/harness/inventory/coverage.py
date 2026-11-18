from __future__ import annotations

from typing import Any

from .. import config
from ..persistence.store import read_json, sha256_file, utc_now, write_json

COVERAGE_STATES = (
    "DISCOVERED",
    "ASSIGNED",
    "PARTIALLY_READ",
    "FULLY_READ",
    "SEMANTICALLY_REVIEWED",
    "DEEP_TRACED",
    "CROSS_REVIEWED",
    "RUNTIME_VERIFIED",
)

_STATE_RANK = {state: index for index, state in enumerate(COVERAGE_STATES)}


def load_coverage(path: Any = None) -> dict[str, Any]:
    return read_json(path or config.source_coverage_path(), {}) or {}


def save_coverage(document: dict[str, Any], path: Any = None) -> Any:
    document["updated_at"] = utc_now()
    return write_json(path or config.source_coverage_path(), document)


def initialize_from_manifest(manifest: dict[str, Any], path: Any = None) -> dict[str, Any]:
    document = load_coverage(path)
    files = document.setdefault("files", {})
    for entry in manifest.get("files", []):
        relative = entry["path"]
        existing = files.get(relative, {})
        existing.setdefault("module", entry.get("module", ""))
        existing.setdefault("category", entry.get("category", ""))
        existing.setdefault("language", entry.get("language", ""))
        existing.setdefault("lines", entry.get("lines", 0))
        existing.setdefault("coverage_state", "DISCOVERED")
        existing.setdefault("reviewed_ranges", [])
        existing.setdefault("reviews", [])
        existing["sha256_at_discovery"] = entry.get("sha256", "")
        existing["current_sha256"] = entry.get("sha256", "")
        files[relative] = existing
    document["revision"] = manifest.get("revision", "")
    save_coverage(document, path)
    return document


def record_review(
    relative_path: str,
    state: str,
    ranges: list[tuple[int, int]] | None = None,
    reviewer: str = "",
    notes: str = "",
    path: Any = None,
) -> dict[str, Any]:
    if state not in COVERAGE_STATES:
        raise ValueError(f"unknown coverage state: {state}")

    document = load_coverage(path)
    files = document.setdefault("files", {})
    entry = files.setdefault(relative_path, {"coverage_state": "DISCOVERED", "reviewed_ranges": [], "reviews": []})
    entry["coverage_state"] = state
    if ranges:
        merged = list(entry.get("reviewed_ranges", []))
        merged.extend([list(item) for item in ranges])
        entry["reviewed_ranges"] = merged
    entry.setdefault("reviews", []).append(
        {
            "at": utc_now(),
            "reviewer": reviewer,
            "state": state,
            "ranges": [list(item) for item in (ranges or [])],
            "notes": notes,
        }
    )
    entry["updated_at"] = utc_now()
    save_coverage(document, path)
    return entry


def detect_hash_drift(root: Any = None, path: Any = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    document = load_coverage(path)
    drifted: list[dict[str, Any]] = []
    missing: list[str] = []
    for relative, entry in document.get("files", {}).items():
        absolute = repository / relative
        if not absolute.exists():
            missing.append(relative)
            continue
        current = sha256_file(absolute)
        if entry.get("current_sha256") and current != entry["current_sha256"]:
            drifted.append({"path": relative, "expected": entry["current_sha256"], "actual": current})
            entry["current_sha256"] = current
            entry["coverage_state"] = "DISCOVERED"
            entry["stale"] = True
    if drifted or missing:
        save_coverage(document, path)
    return {"drifted": drifted, "missing": missing, "checked": len(document.get("files", {}))}


def coverage_summary(document: dict[str, Any] | None = None) -> dict[str, Any]:
    active = document or load_coverage()
    files = active.get("files", {})
    summary: dict[str, int] = {state: 0 for state in COVERAGE_STATES}
    for entry in files.values():
        state = entry.get("coverage_state", "DISCOVERED")
        summary[state] = summary.get(state, 0) + 1
    total = len(files)
    reviewed = total - summary.get("DISCOVERED", 0) - summary.get("ASSIGNED", 0) - summary.get("PARTIALLY_READ", 0)
    return {
        "total_files": total,
        "state_summary": summary,
        "reviewed_files": reviewed,
        "percent_reviewed": round(100.0 * reviewed / total, 2) if total else 0.0,
    }
