from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

from .. import config
from ..inventory.scanner import load_inventory
from ..persistence.store import utc_now
from .cleaner import clean_source
from .policy import Policy


def original_content(root: Path, relative_path: str, revision: str) -> str | None:
    completed = subprocess.run(
        ["git", "-C", str(root), "show", f"{revision}:{relative_path}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout


def compare_against_baseline(
    relative_path: str,
    root: Path | None = None,
    revision: str = "HEAD",
    policy: Policy | None = None,
) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    absolute = repository / relative_path
    if not absolute.exists():
        return {"path": relative_path, "status": "MISSING_WORKTREE_FILE"}

    baseline = original_content(repository, relative_path, revision)
    if baseline is None:
        return {"path": relative_path, "status": "NOT_IN_BASELINE"}

    current = absolute.read_text(encoding="utf-8", errors="replace")
    expected, _ = clean_source(absolute, relative_path, baseline, active_policy)
    equivalent = expected == current
    result = {
        "path": relative_path,
        "status": "EQUIVALENT" if equivalent else "DIVERGED",
        "baseline_bytes": len(baseline),
        "current_bytes": len(current),
        "expected_bytes": len(expected),
    }
    if not equivalent:
        expected_lines = expected.splitlines()
        current_lines = current.splitlines()
        differences: list[dict[str, Any]] = []
        for index in range(max(len(expected_lines), len(current_lines))):
            expected_line = expected_lines[index] if index < len(expected_lines) else None
            current_line = current_lines[index] if index < len(current_lines) else None
            if expected_line != current_line:
                differences.append({"line": index + 1, "expected": expected_line, "actual": current_line})
            if len(differences) >= 5:
                break
        result["first_differences"] = differences
    return result


def changed_file_insertion_counts(root: Path) -> dict[str, int]:
    completed = subprocess.run(
        ["git", "-C", str(root), "diff", "--numstat", "HEAD", "--"],
        capture_output=True,
        text=True,
        check=False,
    )
    counts: dict[str, int] = {}
    for line in completed.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        added = parts[0]
        deleted = parts[1]
        path = parts[2]
        if added == "-" or deleted == "-":
            counts[path] = 1
            continue
        counts[path] = int(added) + int(deleted)
    return counts


def comment_gate_paths(root: Path | None = None) -> set[str]:
    repository = root or config.repository_root()
    manifest = load_inventory()
    return {
        entry["path"]
        for entry in manifest.get("files", [])
        if entry.get("comment_gate")
    }


def verify_comment_only_changes(
    paths: list[str],
    root: Path | None = None,
    revision: str = "HEAD",
    policy: Policy | None = None,
) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    insertions = changed_file_insertion_counts(repository)
    gated = comment_gate_paths(repository)
    results = []
    designed = []
    skipped = []
    for path in paths:
        if path not in gated:
            skipped.append(path)
            continue
        if insertions.get(path, 0) > 0:
            designed.append({"path": path, "insertions": insertions[path]})
            continue
        results.append(compare_against_baseline(path, repository, revision, active_policy))
    diverged = [entry for entry in results if entry["status"] == "DIVERGED"]
    return {
        "generated_at": utc_now(),
        "baseline_revision": revision,
        "checked": len(results),
        "equivalent": sum(1 for entry in results if entry["status"] == "EQUIVALENT"),
        "diverged": len(diverged),
        "other": [entry for entry in results if entry["status"] not in ("EQUIVALENT", "DIVERGED")],
        "diverged_details": diverged,
        "modified_by_design": designed,
        "skipped_outside_gate": len(skipped),
        "clean": not diverged,
    }
