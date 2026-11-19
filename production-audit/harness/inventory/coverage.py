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
        existing["module"] = entry.get("module", existing.get("module", ""))
        existing["category"] = entry.get("category", existing.get("category", ""))
        existing["language"] = entry.get("language", existing.get("language", ""))
        existing["lines"] = entry.get("lines", existing.get("lines", 0))
        existing.setdefault("coverage_state", "DISCOVERED")
        existing.setdefault("reviewed_ranges", [])
        existing.setdefault("reviews", [])
        existing["sha256_at_discovery"] = entry.get("sha256", "")
        existing["current_sha256"] = entry.get("sha256", "")
        files[relative] = existing
    manifest_paths = {entry["path"] for entry in manifest.get("files", [])}
    for relative in list(files):
        if relative not in manifest_paths:
            files.pop(relative, None)
            document.setdefault("pruned_not_in_manifest", []).append(relative)
    document["pruned_not_in_manifest"] = sorted(set(document.get("pruned_not_in_manifest", [])))
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


def prune_missing(document: dict[str, Any], root: Any = None) -> list[str]:
    repository = root or config.repository_root()
    files = document.get("files", {})
    removed = [relative for relative in list(files) if not (repository / relative).exists()]
    for relative in removed:
        files.pop(relative, None)
    if removed:
        document["pruned_missing"] = sorted(set(document.get("pruned_missing", [])) | set(removed))
        document["updated_at"] = utc_now()
        save_coverage(document)
    return removed


def mark_paths(
    relative_paths: list[str],
    state: str = "FULLY_READ",
    reviewer: str = "",
    notes: str = "",
    path: Any = None,
    root: Any = None,
) -> dict[str, Any]:
    if state not in COVERAGE_STATES:
        raise ValueError(f"unknown coverage state: {state}")

    repository = root or config.repository_root()
    document = load_coverage(path)
    files = document.setdefault("files", {})
    marked: list[str] = []
    absent: list[str] = []
    unknown: list[str] = []

    for relative in relative_paths:
        absolute = repository / relative
        if not absolute.exists():
            absent.append(relative)
            continue
        if relative not in files:
            unknown.append(relative)
            continue
        entry = files[relative]
        line_count = sum(1 for _ in absolute.open("rb"))
        entry["coverage_state"] = state
        entry["reviewed_ranges"] = [[1, line_count]]
        entry["lines_reviewed"] = line_count
        entry["reviewed_by"] = reviewer or "orchestrator"
        entry.setdefault("reviews", []).append(
            {
                "at": utc_now(),
                "reviewer": reviewer or "orchestrator",
                "state": state,
                "ranges": [[1, line_count]],
                "notes": notes,
            }
        )
        entry["updated_at"] = utc_now()
        marked.append(relative)

    if absent:
        for relative in absent:
            files.pop(relative, None)
        document["pruned_missing"] = sorted(set(document.get("pruned_missing", [])) | set(absent))

    document["updated_at"] = utc_now()
    save_coverage(document, path)
    return {
        "marked": len(marked),
        "pruned_absent": len(absent),
        "not_in_manifest": len(unknown),
        "state": state,
        "summary": coverage_summary(document),
    }


IMPLEMENTATION_CATEGORIES = (
    "PRODUCTION_CODE",
    "TEST_CODE",
    "SHARED_LIBRARY",
    "INFRASTRUCTURE",
    "BUILD",
    "CONTRACT",
)

NON_IMPLEMENTATION_CATEGORIES = (
    "DOCUMENTATION",
    "GENERATED",
    "VENDORED_TOOLING",
    "HARNESS",
)


def coverage_summary(document: dict[str, Any] | None = None) -> dict[str, Any]:
    active = document or load_coverage()
    files = active.get("files", {})
    summary: dict[str, int] = {state: 0 for state in COVERAGE_STATES}
    implementation_total = 0
    implementation_reviewed = 0
    category_totals: dict[str, int] = {}
    category_reviewed: dict[str, int] = {}
    for entry in files.values():
        state = entry.get("coverage_state", "DISCOVERED")
        summary[state] = summary.get(state, 0) + 1
        category = entry.get("category", "INFRASTRUCTURE")
        category_totals[category] = category_totals.get(category, 0) + 1
        is_reviewed = state not in ("DISCOVERED", "ASSIGNED", "PARTIALLY_READ")
        if is_reviewed:
            category_reviewed[category] = category_reviewed.get(category, 0) + 1
        if category in IMPLEMENTATION_CATEGORIES:
            implementation_total += 1
            if is_reviewed:
                implementation_reviewed += 1
    total = len(files)
    reviewed = total - summary.get("DISCOVERED", 0) - summary.get("ASSIGNED", 0) - summary.get("PARTIALLY_READ", 0)
    return {
        "total_files": total,
        "state_summary": summary,
        "reviewed_files": reviewed,
        "percent_reviewed": round(100.0 * reviewed / total, 2) if total else 0.0,
        "implementation_total": implementation_total,
        "implementation_reviewed": implementation_reviewed,
        "implementation_percent": round(100.0 * implementation_reviewed / implementation_total, 2) if implementation_total else 0.0,
        "category_totals": category_totals,
        "category_reviewed": category_reviewed,
        "non_implementation_categories": list(NON_IMPLEMENTATION_CATEGORIES),
    }
