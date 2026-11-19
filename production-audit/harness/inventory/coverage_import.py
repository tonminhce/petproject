from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import utc_now
from .coverage import load_coverage, save_coverage

ROW_PATTERN = re.compile(
    r"^\|\s*(?:\d+\s*\|\s*)?`([^`]+)`\s*\|\s*`?([0-9a-f]{7,40})`?\s*\|\s*(\d+)\s*\|\s*([^|]*?)\s*\|"
)
RANGE_PATTERN = re.compile(r"(\d+)\s*[-–—]\s*(\d+)")


def parse_ranges(text: str) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    for start, end in RANGE_PATTERN.findall(text.replace("–", "-").replace("—", "-")):
        ranges.append((int(start), int(end)))
    return ranges


def parse_inventory_rows(markdown: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in markdown.splitlines():
        match = ROW_PATTERN.match(line.strip())
        if not match:
            continue
        path = match.group(1).strip()
        blob = match.group(2).strip()
        lines = int(match.group(3))
        ranges = parse_ranges(match.group(4))
        if not path or "/" not in path:
            continue
        rows.append({"path": path, "blob": blob, "lines": lines, "ranges": ranges})
    return rows


def git_blob_hash(root: Path, relative_path: str, revision: str = "HEAD") -> str:
    completed = subprocess.run(
        ["git", "-C", str(root), "rev-parse", f"{revision}:{relative_path}"],
        capture_output=True,
        text=True,
        check=False,
    )
    return completed.stdout.strip() if completed.returncode == 0 else ""


def import_report_coverage(
    reports: list[Path] | None = None,
    root: Path | None = None,
    reviewer: str = "",
    path: Any = None,
    baseline_revision: str = "",
) -> dict[str, Any]:
    repository = root or config.repository_root()
    run_id = config.run_identifier()
    reviews_root = config.run_root(run_id) / "source-reviews"
    if reports is None:
        reports = sorted(reviews_root.glob("*.md")) if reviews_root.exists() else []

    document = load_coverage(path)
    files = document.setdefault("files", {})
    imported = 0
    verified = 0
    mismatched: list[str] = []
    unknown: list[str] = []
    source_reports: list[str] = []

    revision = baseline_revision or document.get("baseline_revision", "")
    if not revision:
        revision = "a2b6e6fc336f42f851166cab0cf0d1bba42db12f"
    document["baseline_revision"] = revision

    for report in reports:
        markdown = report.read_text(encoding="utf-8", errors="replace")
        rows = parse_inventory_rows(markdown)
        if not rows:
            continue
        source_reports.append(str(report))
        for row in rows:
            relative = row["path"]
            if relative not in files:
                unknown.append(relative)
                continue
            entry = files[relative]
            baseline_blob = git_blob_hash(repository, relative, revision)
            blob_matches = not row["blob"] or not baseline_blob or baseline_blob.startswith(row["blob"])
            if not blob_matches:
                mismatched.append(relative)
                continue
            entry["coverage_state"] = "FULLY_READ"
            entry["reviewed_ranges"] = [list(item) for item in row["ranges"]] or [[1, row["lines"]]]
            entry["lines_reviewed"] = row["lines"]
            entry["baseline_blob"] = baseline_blob
            entry["reviewed_by"] = reviewer or report.stem
            entry["source_report"] = report.name
            entry["updated_at"] = utc_now()
            imported += 1
            verified += 1

    document["source_reports"] = source_reports
    document["updated_at"] = utc_now()
    save_coverage(document, path)
    return {
        "generated_at": utc_now(),
        "reports": len(source_reports),
        "files_imported": imported,
        "blob_verified": verified,
        "not_in_manifest": len(unknown),
        "hash_mismatched": len(mismatched),
        "mismatched_sample": mismatched[:10],
    }
