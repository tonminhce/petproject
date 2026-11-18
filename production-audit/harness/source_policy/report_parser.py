from __future__ import annotations

import re
from pathlib import Path
from typing import Any

HEADER_PATTERN = re.compile(r"^#{2,6}\s+(?P<id>[A-Z][A-Z0-9]*-\d{1,3})\s+—\s+(?P<rest>.+)$")
SEVERITY_PATTERN = re.compile(r"\b(CRITICAL|HIGH|MEDIUM|LOW)\b")
EXPLICIT_SEVERITY_PATTERN = re.compile(r"\*\*Severity:?\*\*\s*:?\s*\**\s*(CRITICAL|HIGH|MEDIUM|LOW)\b", re.IGNORECASE)
EVIDENCE_PATTERN = re.compile(r"`([A-Za-z0-9_./\-]+\.(?:java|yml|yaml|xml|json|properties|sql|md|sh|ts|js)):(\d+)(?:-(\d+))?`")
FILE_PATTERN = re.compile(r"`([A-Za-z0-9_./\-]+\.(?:java|yml|yaml|xml|json|properties|sql|md|sh|ts|js))`")

SEVERITY_PRIORITY = {"CRITICAL": 5, "HIGH": 20, "MEDIUM": 40, "LOW": 70}


def split_sections(markdown: str) -> list[tuple[str, str, str]]:
    sections: list[tuple[str, str, str]] = []
    current_id = ""
    current_title = ""
    buffer: list[str] = []

    def flush() -> None:
        if current_id:
            sections.append((current_id, current_title, "\n".join(buffer)))

    for line in markdown.splitlines():
        match = HEADER_PATTERN.match(line)
        if match:
            flush()
            current_id = match.group("id")
            current_title = match.group("rest").strip()
            buffer = []
            continue
        if current_id:
            buffer.append(line)
    flush()
    return sections


def derive_severity(header_rest: str, body: str) -> str:
    explicit = EXPLICIT_SEVERITY_PATTERN.search(body)
    if explicit:
        return explicit.group(1).upper()
    for candidate in (header_rest, body[:2000]):
        match = SEVERITY_PATTERN.search(candidate)
        if match:
            return match.group(1)
    return "MEDIUM"


def derive_evidence(body: str) -> list[str]:
    evidence: list[str] = []
    for match in EVIDENCE_PATTERN.finditer(body):
        start = match.group(2)
        end = match.group(3) or start
        evidence.append(f"{match.group(1)}:{start}" if start == end else f"{match.group(1)}:{start}-{end}")
    if not evidence:
        for match in FILE_PATTERN.finditer(body):
            evidence.append(match.group(1))
    unique: list[str] = []
    for item in evidence:
        if item not in unique:
            unique.append(item)
    return unique[:12]


def derive_title(rest: str) -> str:
    cleaned = re.sub(r"\b(CRITICAL|HIGH|MEDIUM|LOW)\b", "", rest)
    cleaned = cleaned.strip(" —-·|")
    return cleaned.strip()


def parse_defects(markdown: str, source_report: str) -> list[dict[str, Any]]:
    findings: list[dict[str, Any]] = []
    for identifier, rest, body in split_sections(markdown):
        severity = derive_severity(rest, body)
        findings.append(
            {
                "id": identifier,
                "title": derive_title(rest),
                "severity": severity,
                "priority": SEVERITY_PRIORITY.get(severity, 50),
                "affected_service": "",
                "source_evidence": derive_evidence(body),
                "business_risk": "",
                "technical_risk": "",
                "current_implementation": "",
                "target_implementation": "",
                "design_pattern": "",
                "acceptance_criteria": [
                    f"The behaviour reported for {identifier} in {source_report} no longer reproduces"
                ],
                "happy_path_tests": [],
                "edge_case_tests": [],
                "concurrency_tests": [],
                "integration_e2e_coverage": [],
                "dependencies": [],
                "assigned_agent": "",
                "independent_verifier": "",
                "notes": f"Extracted from {source_report}. Severity {severity}. Read the report section for the full root-cause analysis and the proposed fix before implementing.",
            }
        )
    return findings


def parse_report_file(path: Path) -> list[dict[str, Any]]:
    markdown = path.read_text(encoding="utf-8")
    return parse_defects(markdown, str(path))
