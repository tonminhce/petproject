from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import utc_now
from .generic_lexer import scan_generic, scan_java
from .java_lexer import line_of, tokenize_java
from .policy import Policy, classify_comment, is_exempt_path


@dataclass
class CleanOutcome:
    path: str
    removed_comments: int
    removed_lines: int
    preserved_comments: int
    changed: bool
    error: str = ""
    removed_texts: list[str] = field(default_factory=list)

    def to_document(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "removed_comments": self.removed_comments,
            "removed_lines": self.removed_lines,
            "preserved_comments": self.preserved_comments,
            "changed": self.changed,
            "error": self.error,
        }


def _removal_spans_java(source: str, policy: Policy) -> tuple[list[tuple[int, int]], int, list[str]]:
    tokens = tokenize_java(source).tokens
    spans: list[tuple[int, int]] = []
    preserved = 0
    removed_texts: list[str] = []
    for token in tokens:
        if not token.is_comment:
            continue
        decision = classify_comment(token.text, line_of(source, token.start), policy)
        if decision.prohibited:
            spans.append((token.start, token.end))
            removed_texts.append(token.text)
        else:
            preserved += 1
    return spans, preserved, removed_texts


def _removal_spans_generic(path: Path, source: str, policy: Policy) -> tuple[list[tuple[int, int]], int, list[str]]:
    spans: list[tuple[int, int]] = []
    preserved = 0
    removed_texts: list[str] = []
    for comment in scan_generic(path, source):
        decision = classify_comment(comment.text, line_of(source, comment.start), policy)
        if decision.prohibited:
            spans.append((comment.start, comment.end))
            removed_texts.append(comment.text)
        else:
            preserved += 1
    return spans, preserved, removed_texts


def _apply_removals(source: str, spans: list[tuple[int, int]]) -> tuple[str, int, int]:
    if not spans:
        return source, 0, 0
    removal_mask = bytearray(len(source))
    for start, end in spans:
        for index in range(start, end):
            removal_mask[index] = 1

    output: list[str] = []
    removed_comments = 0
    removed_lines = 0
    offset = 0
    for line in source.splitlines(keepends=True):
        line_start = offset
        line_end = offset + len(line)
        offset = line_end
        body = line.rstrip("\n").rstrip("\r")
        trailer = line[len(body):]
        local_mask = removal_mask[line_start:line_end]
        has_removal = False
        has_code = False
        for index, character in enumerate(body):
            if index < len(local_mask) and local_mask[index]:
                has_removal = True
            elif not character.isspace():
                has_code = True
        if has_removal and not has_code:
            removed_comments += 1
            removed_lines += 1
            continue
        if not has_removal:
            output.append(line)
            continue
        rebuilt = "".join(
            character for index, character in enumerate(body) if not (index < len(local_mask) and local_mask[index])
        )
        removed_comments += 1
        output.append(rebuilt.rstrip() + trailer)
    return "".join(output), removed_comments, removed_lines


def clean_source(path: Path, relative_path: str, source: str, policy: Policy) -> tuple[str, CleanOutcome]:
    if is_exempt_path(relative_path, policy):
        return source, CleanOutcome(relative_path, 0, 0, 0, False, "path exempt by policy")

    if path.suffix.lower() == ".java":
        spans, preserved, removed_texts = _removal_spans_java(source, policy)
    else:
        spans, preserved, removed_texts = _removal_spans_generic(path, source, policy)

    cleaned, removed_comments, removed_lines = _apply_removals(source, spans)
    changed = cleaned != source
    outcome = CleanOutcome(relative_path, removed_comments, removed_lines, preserved, changed)
    outcome.removed_texts = removed_texts
    return cleaned, outcome


def clean_repository(
    paths: list[str],
    root: Path | None = None,
    policy: Policy | None = None,
    dry_run: bool = False,
) -> dict[str, Any]:
    repository = root or config.repository_root()
    active_policy = policy or Policy.load()
    outcomes: list[CleanOutcome] = []

    for relative in paths:
        absolute = repository / relative
        if not absolute.exists():
            outcomes.append(CleanOutcome(relative, 0, 0, 0, False, "file not found"))
            continue
        try:
            raw = absolute.read_bytes()
        except OSError as error:
            outcomes.append(CleanOutcome(relative, 0, 0, 0, False, f"read failed: {error}"))
            continue
        encoding = "utf-8"
        text = raw.decode(encoding, errors="replace")
        cleaned, outcome = clean_source(absolute, relative, text, active_policy)
        if outcome.changed and not dry_run:
            absolute.write_bytes(cleaned.encode(encoding))
        outcomes.append(outcome)

    return {
        "generated_at": utc_now(),
        "dry_run": dry_run,
        "processed_files": len(outcomes),
        "changed_files": sum(1 for outcome in outcomes if outcome.changed),
        "removed_comments": sum(outcome.removed_comments for outcome in outcomes),
        "removed_lines": sum(outcome.removed_lines for outcome in outcomes),
        "preserved_comments": sum(outcome.preserved_comments for outcome in outcomes),
        "outcomes": [outcome.to_document() for outcome in outcomes],
        "outcomes_detail": [
            {
                "path": outcome.path,
                "removed_texts": outcome.removed_texts,
                "removed_comments": outcome.removed_comments,
                "removed_lines": outcome.removed_lines,
            }
            for outcome in outcomes
        ],
    }
