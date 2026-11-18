from __future__ import annotations

import subprocess
from typing import Any

from .. import config
from ..inventory.coverage import detect_hash_drift, load_coverage
from ..persistence.store import read_json, utc_now, write_json
from ..task_registry.registry import load_tasks


def current_revision(root: Any = None) -> str:
    repository = root or config.repository_root()
    completed = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        capture_output=True,
        text=True,
        check=False,
    )
    return completed.stdout.strip()


def reconcile(root: Any = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    head = current_revision(repository)
    manifest = read_json(config.source_manifest_path(), {}) or {}
    coverage = load_coverage()
    tasks = load_tasks()

    drift = detect_hash_drift(repository)
    manifest_revision = manifest.get("revision", "")
    revision_matches = bool(manifest_revision) and manifest_revision == head

    reopened: list[str] = []
    for task in tasks.values():
        if task.state == "VERIFIED":
            task.state = "REOPENED"
            task.updated_at = utc_now()
            task.notes = (task.notes + " reopened by reconciliation: source revision moved").strip()
            reopened.append(task.id)

    if reopened:
        from ..task_registry.registry import save_tasks

        save_tasks(tasks)

    stale_files = [entry["path"] for entry in drift["drifted"]]
    document = {
        "generated_at": utc_now(),
        "head_revision": head,
        "manifest_revision": manifest_revision,
        "revision_matches": revision_matches,
        "coverage_files": len(coverage.get("files", {})),
        "hash_drift_count": len(drift["drifted"]),
        "hash_drift_sample": stale_files[:20],
        "missing_files": drift["missing"][:20],
        "reopened_tasks": reopened,
        "task_state_summary": {state: sum(1 for task in tasks.values() if task.state == state) for state in sorted({task.state for task in tasks.values()})},
        "stable": revision_matches and not drift["drifted"] and not drift["missing"],
    }
    write_json(config.control_root() / "reconciliation.json", document)
    return document
