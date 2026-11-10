"""Markdown report generation from persisted state (no invented numbers)."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from . import persistence
from .task_registry import TaskRegistry


def _table(rows: list[list[str]], headers: list[str]) -> str:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(c) for c in r) + " |")
    return "\n".join(out)


def generate_reports(root: Path) -> dict[str, Any]:
    control = root / "production-audit" / "control"
    state = root / "production-audit" / "state"
    reports = root / "production-audit" / "reports"
    reports.mkdir(parents=True, exist_ok=True)

    manifest = persistence.read_json(control / "source-manifest.json", default={"files": {}, "git": {}})
    coverage = persistence.read_json(control / "source-coverage.json", default={"reviews": {}})
    arch = persistence.read_json(control / "architecture-map.json", default={})
    dist = persistence.read_json(control / "distributed-systems-map.json", default={})
    sec = persistence.read_json(control / "security-scan.json", default={})
    perf = persistence.read_json(control / "performance-scan.json", default={})
    db = persistence.read_json(control / "database-map.json", default={})
    findings = persistence.read_json(state / "findings.json", default={"findings": {}})
    evidence = persistence.read_json(state / "evidence.json", default={"evidence": {}})
    reg = TaskRegistry(root)
    summary = reg.summary()

    files = manifest.get("files", {})
    reviews = coverage.get("reviews", {})
    java_scope = {
        r: m
        for r, m in files.items()
        if m.get("classification") in ("FIRST_PARTY_PRODUCTION", "FIRST_PARTY_TEST", "SHARED_PLATFORM")
    }
    reviewed = [r for r in java_scope if r in reviews]
    total_lines = sum(m["lines"] for m in java_scope.values())
    read_lines = sum(java_scope[r]["lines"] for r in reviewed)

    written: list[str] = []

    # ---------------------------------------------------------- coverage
    cov = f"""# Source Coverage Report

Generated: {time.strftime('%Y-%m-%dT%H:%M:%S')}

| Metric | Value |
|---|---|
| Repository commit | `{manifest.get('git', {}).get('commit', 'n/a')}` |
| Branch | `{manifest.get('git', {}).get('branch', 'n/a')}` |
| Total files in repo | {manifest.get('totals', {}).get('files', 0)} |
| Total lines (all files) | {manifest.get('totals', {}).get('lines', 0)} |
| In-scope files | {manifest.get('totals', {}).get('in_scope_files', 0)} |
| Java scope files (main+test+shared) | {len(java_scope)} |
| Java scope lines | {total_lines} |
| Fully-read files | {len(reviewed)} |
| Fully-read lines | {read_lines} |
| File coverage | {round(100.0*len(reviewed)/len(java_scope),2) if java_scope else 0}% |
| Line coverage | {round(100.0*read_lines/total_lines,2) if total_lines else 0}% |

## By classification

{_table([[k, v] for k, v in manifest.get('by_classification', {}).items()], ['Classification', 'Files'])}

## By module

{_table([[k, v['files'], v['main'], v['test'], v['shared'], v['lines']] for k, v in manifest.get('by_module', {}).items()], ['Module', 'Files', 'Main', 'Test', 'Shared', 'Lines'])}
"""
    (reports / "source-coverage.md").write_text(cov, encoding="utf-8")
    written.append("source-coverage.md")

    # --------------------------------------------------------- findings
    f_rows = []
    for fid, f in sorted(findings.get("findings", {}).items()):
        f_rows.append(
            [
                fid,
                f.get("priority", ""),
                f.get("category", ""),
                f.get("title", "")[:70],
                f.get("status", ""),
            ]
        )
    rep = f"""# Findings Register

Generated: {time.strftime('%Y-%m-%dT%H:%M:%S')}

Total findings: {len(findings.get('findings', {}))}

{_table(f_rows, ['ID', 'Priority', 'Category', 'Title', 'Status'])}
"""
    (reports / "findings.md").write_text(rep, encoding="utf-8")
    written.append("findings.md")

    # ----------------------------------------------------------- tasks
    t_rows = [
        [t["id"], t.get("priority", ""), t.get("state", ""), (t.get("title") or "")[:70]]
        for t in sorted(reg.tasks.values(), key=lambda x: (x.get("priority", "P3"), x["id"]))
    ]
    tasks = f"""# Outstanding Tasks

Generated: {time.strftime('%Y-%m-%dT%H:%M:%S')}

{_table([[k, v] for k, v in summary['by_state'].items()], ['State', 'Count'])}
{_table([[k, v] for k, v in summary['by_priority'].items()], ['Priority', 'Count'])}

Mandatory open tasks: **{summary['mandatory_open_count']}**

{_table(t_rows, ['ID', 'Priority', 'State', 'Title'])}
"""
    (reports / "outstanding-tasks.md").write_text(tasks, encoding="utf-8")
    written.append("outstanding-tasks.md")

    # ------------------------------------------------------ architecture
    modules = arch.get("maven", {}).get("modules", {})
    edges = arch.get("maven", {}).get("edges", [])
    topics = arch.get("kafka", {}).get("topic_to_groups", {})
    arch_md = f"""# Actual Architecture (reconstructed from source)

Generated: {time.strftime('%Y-%m-%dT%H:%M:%S')}

## Maven modules ({len(modules)})

{_table([[k, m.get('artifact',''), len(m.get('depends_on',[])), 'shared' if m.get('is_shared') else ('service' if m.get('is_service') else 'other')] for k, m in sorted(modules.items())], ['Module', 'Artifact', 'Internal deps', 'Kind'])}

## Internal dependency edges ({len(edges)})

{_table([[e['from'], e['to']] for e in sorted(edges, key=lambda x: (x['from'], x['to']))], ['From', 'To'])}

## Kafka topics and consumer groups

{_table([[k, ', '.join(v)] for k, v in sorted(topics.items())], ['Topic', 'Consumer groups'])}

## Service datasource ownership

{_table([[k, ', '.join(v.get('datasources', [])) or 'n/a'] for k, v in sorted(arch.get('datasources', {}).items())], ['application.yml', 'JDBC URLs'])}
"""
    (reports / "architecture.md").write_text(arch_md, encoding="utf-8")
    written.append("architecture.md")

    # ------------------------------------------------------ verification
    e_rows = [[k, v.get("status", "")] for k, v in sorted(evidence.get("evidence", {}).items())]
    vr = f"""# Verification Evidence

Generated: {time.strftime('%Y-%m-%dT%H:%M:%S')}

{_table(e_rows, ['Evidence key', 'Status']) if e_rows else 'No verification evidence recorded yet.'}
"""
    (reports / "verification-evidence.md").write_text(vr, encoding="utf-8")
    written.append("verification-evidence.md")


    # ------------------------------------------- category-scoped reports
    scoped = {
        "distributed-systems.md": ("distributed-systems", "Distributed Systems & Messaging"),
        "security.md": ("security", "Security & Identity"),
        "correctness.md": ("correctness", "Business Correctness"),
        "performance.md": ("performance", "Performance & Concurrency"),
        "database.md": ("database", "Database & Data Integrity"),
        "deployment.md": ("deployment", "Deployment & Operations"),
        "testing.md": ("testing", "Test Suite & Verification"),
        "configuration.md": ("configuration", "Configuration & Configuration Drift"),
    }
    for filename, (category, title) in scoped.items():
        rows = []
        for fid, f in sorted(findings.get("findings", {}).items()):
            if f.get("category") != category:
                continue
            rows.append([
                fid,
                f.get("priority", ""),
                f.get("status", "OPEN"),
                f.get("title", "")[:110],
            ])
        detail = []
        for fid, f in sorted(findings.get("findings", {}).items()):
            if f.get("category") != category:
                continue
            def _line(key, value):
                return "- **{}:** {}\n".format(key, value)

            detail.append("### {} - {}\n\n".format(fid, f.get("title", "")))
            detail.append(_line("Priority / severity",
                                "{} / {} (confidence: {}, evidence: {})".format(
                                    f.get("priority", "?"), f.get("severity", "?"),
                                    f.get("confidence", "?"), f.get("evidence_class", "?"))))
            detail.append(_line("Status", "{} | verification: {}".format(
                f.get("status", "OPEN"), f.get("verification", "NOT_VERIFIED"))))
            detail.append(_line("Affected modules", ", ".join(f.get("modules", [])) or "n/a"))
            detail.append("- **Source files:**\n")
            for src in f.get("files", []):
                detail.append("  - `{}`\n".format(src))
            detail.append(_line("Actual implementation", f.get("actual", "n/a")))
            detail.append(_line("Expected", f.get("expected", "n/a")))
            detail.append(_line("Trigger", f.get("trigger", "n/a")))
            detail.append(_line("Root cause", f.get("root_cause", "n/a")))
            detail.append(_line("Consequences", f.get("consequences", "n/a")))
            detail.append(_line("Required remediation", f.get("remediation", "n/a")))
            detail.append(_line("Regression test", f.get("regression_test", "n/a")))
            detail.append(_line("Remediation applied", f.get("remediation_applied", "(none yet)")))
            detail.append(_line("Verification evidence", f.get("verification_evidence", "(none yet)")))
        body = "# {}\n\nGenerated: {}\n\n".format(title, time.strftime('%Y-%m-%dT%H:%M:%S'))
        body += "Findings in this category: **{}**\n\n".format(len(rows))
        body += (_table(rows, ['ID', 'Priority', 'Status', 'Title']) if rows else '_No findings recorded._')
        body += "\n\n---\n\n" + ("".join(detail) if detail else "")
        (reports / filename).write_text(body, encoding="utf-8")
        written.append(filename)

    return {"command": "report", "written": written, "dir": str(reports)}
