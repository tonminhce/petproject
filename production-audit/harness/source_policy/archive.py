from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import utc_now
from .generic_lexer import scan_generic, scan_java
from .java_lexer import line_of
from .policy import Policy, classify_comment


def original_source(root: Path, relative_path: str, revision: str) -> str | None:
    completed = subprocess.run(
        ["git", "-C", str(root), "show", f"{revision}:{relative_path}"],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        return None
    return completed.stdout


def removed_comments_for(root: Path, relative_path: str, revision: str, policy: Policy) -> list[str]:
    source = original_source(root, relative_path, revision)
    if source is None:
        return []
    path = root / relative_path
    if path.suffix.lower() == ".java":
        comments = scan_java(path, source)
    else:
        comments = scan_generic(path, source)
    removed: list[str] = []
    for comment in comments:
        decision = classify_comment(comment.text, line_of(source, comment.start), policy)
        if decision.prohibited:
            removed.append(comment.text)
    return removed


def build_archive(root: Path | None = None, revision: str | None = None, policy: Policy | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    base_revision = revision or "HEAD"
    from ..inventory.scanner import load_inventory

    manifest = load_inventory()
    entries: list[dict[str, Any]] = []
    total = 0
    for entry in manifest.get("files", []):
        if not entry.get("comment_gate"):
            continue
        removed = removed_comments_for(repository, entry["path"], base_revision, active_policy)
        if removed:
            entries.append({"path": entry["path"], "removed": removed})
            total += len(removed)

    target = config.architecture_root() / "comment-rationale.md"
    lines = [
        "# Archived Source Rationale",
        "",
        "Design rationale that was present in first-party source at the baseline revision and was",
        "removed under the repository no-comment policy. The rationale is preserved here so it is",
        "not lost, while the source stays free of explanatory comments.",
        "",
        f"Baseline revision: `{base_revision}`",
        f"Files affected: {len(entries)}",
        f"Comments archived: {total}",
        "",
    ]
    for item in entries:
        lines.append(f"## {item['path']}")
        lines.append("")
        for text in item["removed"]:
            lines.append("```text")
            lines.append(text.strip("\n"))
            lines.append("```")
            lines.append("")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines), encoding="utf-8")
    return {
        "generated_at": utc_now(),
        "baseline_revision": base_revision,
        "files_affected": len(entries),
        "comments_archived": total,
        "archive": str(target),
    }
