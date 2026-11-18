from __future__ import annotations

from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable

from .. import config
from ..inventory.scanner import load_inventory
from ..persistence.store import sha256_text, utc_now
from .generic_lexer import scan_generic, scan_java, strip_generic_comments
from .java_lexer import line_of, tokenize_java
from .policy import Policy, classify_comment, is_exempt_path


@dataclass
class CommentFinding:
    path: str
    line: int
    end_line: int
    category: str
    reason: str
    excerpt: str
    style: str

    def to_document(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class FileCommentReport:
    path: str
    module: str
    category: str
    language: str
    sha256: str
    total_comments: int
    prohibited: list[CommentFinding]
    preserved: list[CommentFinding]
    exempt: bool
    exempt_reason: str

    def to_document(self) -> dict[str, Any]:
        document = asdict(self)
        document["prohibited"] = [item.to_document() for item in self.prohibited]
        document["preserved"] = [item.to_document() for item in self.preserved]
        return document


def _read_text(path: Path) -> str:
    payload = path.read_bytes()
    if payload.startswith(b"\xef\xbb\xbf"):
        payload = payload[3:]
    return payload.decode("utf-8", errors="replace")


def _excerpt(text: str, limit: int = 160) -> str:
    collapsed = " ".join(text.split())
    return collapsed[:limit]


def analyze_file(path: Path, relative_path: str, policy: Policy, module: str = "", category: str = "", language: str = "") -> FileCommentReport:
    source = _read_text(path)
    digest = sha256_text(source)

    if is_exempt_path(relative_path, policy):
        return FileCommentReport(relative_path, module, category, language, digest, 0, [], [], True, "path exempt by policy")

    if path.suffix.lower() == ".java":
        comments = scan_java(path, source)
    else:
        comments = scan_generic(path, source)

    prohibited: list[CommentFinding] = []
    preserved: list[CommentFinding] = []
    for comment in comments:
        start_line = line_of(source, comment.start)
        end_line = line_of(source, max(comment.start, comment.end - 1))
        decision = classify_comment(comment.text, start_line, policy)
        finding = CommentFinding(
            path=relative_path,
            line=start_line,
            end_line=end_line,
            category=decision.category,
            reason=decision.reason,
            excerpt=_excerpt(comment.text),
            style=comment.style,
        )
        if decision.prohibited:
            prohibited.append(finding)
        else:
            preserved.append(finding)

    return FileCommentReport(
        path=relative_path,
        module=module,
        category=category,
        language=language,
        sha256=digest,
        total_comments=len(comments),
        prohibited=prohibited,
        preserved=preserved,
        exempt=False,
        exempt_reason="",
    )


def scan_repository(root: Path | None = None, policy: Policy | None = None, inventory: dict[str, Any] | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    manifest = inventory if inventory is not None else load_inventory()
    entries = manifest.get("files", [])

    reports: list[FileCommentReport] = []
    for entry in entries:
        if not entry.get("comment_gate"):
            continue
        absolute = repository / entry["path"]
        if not absolute.exists():
            continue
        report = analyze_file(
            absolute,
            entry["path"],
            active_policy,
            module=entry.get("module", ""),
            category=entry.get("category", ""),
            language=entry.get("language", ""),
        )
        reports.append(report)

    prohibited_total = sum(len(report.prohibited) for report in reports)
    preserved_total = sum(len(report.preserved) for report in reports)
    files_with_violations = [report for report in reports if report.prohibited]

    return {
        "generated_at": utc_now(),
        "repository_root": str(repository),
        "scanned_files": len(reports),
        "total_comments": prohibited_total + preserved_total,
        "prohibited_comments": prohibited_total,
        "preserved_comments": preserved_total,
        "files_with_violations": len(files_with_violations),
        "policy": active_policy.to_document(),
        "violations": [
            {
                "path": report.path,
                "module": report.module,
                "count": len(report.prohibited),
                "findings": [finding.to_document() for finding in report.prohibited],
            }
            for report in files_with_violations
        ],
        "files": [report.to_document() for report in reports],
    }


def build_report_for_paths(paths: Iterable[str], root: Path | None = None, policy: Policy | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    reports: list[FileCommentReport] = []
    for relative in paths:
        absolute = repository / relative
        if not absolute.exists():
            continue
        reports.append(analyze_file(absolute, relative, active_policy))
    prohibited_total = sum(len(report.prohibited) for report in reports)
    return {
        "generated_at": utc_now(),
        "scanned_files": len(reports),
        "prohibited_comments": prohibited_total,
        "violations": [
            {"path": report.path, "count": len(report.prohibited), "findings": [f.to_document() for f in report.prohibited]}
            for report in reports
            if report.prohibited
        ],
    }


def strip_source_comments(path: Path, source: str) -> str:
    if path.suffix.lower() == ".java":
        result = tokenize_java(source)
        removals = [(token.start, token.end) for token in result.tokens if token.is_comment]
        if not removals:
            return source
        pieces: list[str] = []
        cursor = 0
        for start, end in removals:
            pieces.append(source[cursor:start])
            cursor = end
        pieces.append(source[cursor:])
        return "".join(pieces)
    comments = scan_generic(path, source)
    return strip_generic_comments(source, comments)
