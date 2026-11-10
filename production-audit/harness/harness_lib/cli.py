"""harness CLI -- production-audit entry point.

Usage:
  harness inventory
  harness audit source|architecture|distributed-systems|security|performance|database
  harness tasks list|next|status|show <id>
  harness verify [--suite NAME] [--module M ...]
  harness reconcile
  harness run --max-iterations N
  harness resume
  harness report
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any

from . import analysis, inventory, persistence, verification
from .task_registry import TaskRegistry

HERE = Path(__file__).resolve().parent
HARNESS_ROOT = HERE.parent
AUDIT_ROOT = HARNESS_ROOT.parent


def _repo_root() -> Path:
    env = os.environ.get("PETPROJECT_ROOT")
    if env:
        return Path(env).resolve()
    # production-audit/harness/harness_lib/cli.py -> repo root
    return AUDIT_ROOT.parent.resolve()


def _out(obj: Any) -> None:
    if isinstance(obj, str):
        print(obj)
    else:
        print(json.dumps(obj, indent=2, ensure_ascii=False))


# ------------------------------------------------------------------ cmd
def cmd_inventory(args: argparse.Namespace) -> int:
    root = _repo_root()
    manifest = inventory.build_manifest(root)
    control = root / "production-audit" / "control"
    control.mkdir(parents=True, exist_ok=True)
    persistence.write_json(control / "source-manifest.json", manifest)

    cov_path = control / "source-coverage.json"
    coverage = persistence.read_json(cov_path, default={"schema_version": 1, "reviews": {}})
    coverage = inventory.reconcile_manifest(manifest, coverage)
    persistence.write_json(cov_path, coverage)

    totals = manifest["totals"]
    _out(
        {
            "command": "inventory",
            "root": str(root),
            "git": manifest["git"],
            "totals": totals,
            "by_classification": manifest["by_classification"],
            "modules": len(manifest["by_module"]),
            "invalidated_reviews": len(coverage.get("invalidated", [])),
        }
    )
    return 0


def cmd_audit(args: argparse.Namespace) -> int:
    root = _repo_root()
    control = root / "production-audit" / "control"
    control.mkdir(parents=True, exist_ok=True)
    which = args.audit_type

    if which == "source":
        cov_path = control / "source-coverage.json"
        coverage = persistence.read_json(cov_path, default={"schema_version": 1, "reviews": {}})
        manifest = persistence.read_json(control / "source-manifest.json", default={"files": {}})
        files = manifest.get("files", {})
        reviews = coverage.get("reviews", {})
        in_scope = {r: m for r, m in files.items() if m.get("in_scope")}
        reviewed = {r: m for r, m in in_scope.items() if r in reviews}
        java_scope = {
            r: m
            for r, m in in_scope.items()
            if m["classification"] in ("FIRST_PARTY_PRODUCTION", "FIRST_PARTY_TEST", "SHARED_PLATFORM")
        }
        java_reviewed = {r: m for r, m in java_scope.items() if r in reviews}
        total_lines = sum(m["lines"] for m in java_scope.values())
        read_lines = sum(java_scope[r]["lines"] for r in java_reviewed)
        result = {
            "command": "audit source",
            "in_scope_files": len(in_scope),
            "reviewed_files": len(reviewed),
            "coverage_pct": round(100.0 * len(reviewed) / len(in_scope), 2) if in_scope else 0.0,
            "java_scope_files": len(java_scope),
            "java_reviewed_files": len(java_reviewed),
            "java_coverage_pct": round(100.0 * len(java_reviewed) / len(java_scope), 2) if java_scope else 0.0,
            "java_line_coverage_pct": round(100.0 * read_lines / total_lines, 2) if total_lines else 0.0,
            "invalidated": coverage.get("invalidated", []),
            "unreviewed_sample": sorted(set(java_scope) - set(java_reviewed))[:40],
        }
        persistence.write_json(control / "source-coverage-summary.json", result)
        _out(result)
        return 0

    if which == "architecture":
        modules = analysis.maven_modules(root)
        topics = analysis.scan_topics(root)
        ds = analysis.scan_datasources(root)
        endpoints = {}
        for rel, meta in inventory.build_inventory(root).items():
            if meta.rest_endpoints:
                endpoints[rel] = meta.rest_endpoints
        amap = {
            "schema_version": 1,
            "maven": modules,
            "kafka": topics,
            "datasources": ds,
            "rest_endpoints": endpoints,
        }
        persistence.write_json(control / "architecture-map.json", amap)
        _out(
            {
                "command": "audit architecture",
                "modules": len(modules["modules"]),
                "edges": len(modules["edges"]),
                "kafka_topics": len(topics["topic_to_groups"]),
                "services_with_endpoints": len(endpoints),
                "written": str(control / "architecture-map.json"),
            }
        )
        return 0

    if which == "distributed-systems":
        ann = analysis.scan_annotations(root)
        outbox = analysis.scan_outbox(root)
        ds = analysis.scan_datasources(root)
        result = {
            "command": "audit distributed-systems",
            "kafka_listeners": ann["kafka_listener"],
            "kafka_producers": ann["kafka_producer"],
            "outbox_implementations": outbox,
            "scheduled_jobs": ann["scheduled"],
            "transactional_sites": len(ann["transactional"]),
            "services": ds,
        }
        persistence.write_json(control / "distributed-systems-map.json", result)
        _out(
            {
                "command": "audit distributed-systems",
                "kafka_listeners": len(ann["kafka_listener"]),
                "kafka_producers": len(ann["kafka_producer"]),
                "outbox_impls": len(outbox),
                "scheduled_jobs": len(ann["scheduled"]),
                "written": str(control / "distributed-systems-map.json"),
            }
        )
        return 0

    if which == "security":
        ann = analysis.scan_annotations(root)
        cfg = analysis.scan_config_secrets(root)
        result = {
            "command": "audit security",
            "preauthorize": ann["preauthorize"],
            "secured": ann["secured"],
            "permit_all": ann["permit_all"],
            "config_credentials": cfg,
            "hardcoded_secret_literals": ann["hardcoded_secret"],
        }
        persistence.write_json(control / "security-scan.json", result)
        _out(
            {
                "command": "audit security",
                "preauthorize_sites": len(ann["preauthorize"]),
                "secured_sites": len(ann["secured"]),
                "permit_all_sites": len(ann["permit_all"]),
                "config_credentials": len(cfg),
                "written": str(control / "security-scan.json"),
            }
        )
        return 0

    if which == "performance":
        blocking = analysis.scan_reactive_blocking(root)
        ann = analysis.scan_annotations(root)
        result = {
            "command": "audit performance",
            "blocking_call_sites": blocking,
            "scheduled_jobs": ann["scheduled"],
            "cacheable_sites": ann["cacheable"],
        }
        persistence.write_json(control / "performance-scan.json", result)
        _out(
            {
                "command": "audit performance",
                "blocking_call_sites": len(blocking),
                "scheduled_jobs": len(ann["scheduled"]),
                "written": str(control / "performance-scan.json"),
            }
        )
        return 0

    if which == "database":
        ds = analysis.scan_datasources(root)
        ann = analysis.scan_annotations(root)
        changesets = []
        for p in sorted(root.rglob("db.changelog-master.yaml")):
            rel = p.relative_to(root).as_posix()
            if "production-audit" in rel:
                continue
            dirp = p.parent
            cs = sorted(x.name for x in dirp.rglob("*.yaml") if "master" not in x.name)
            changesets.append({"master": rel, "changesets": cs, "count": len(cs)})
        result = {
            "command": "audit database",
            "datasources": ds,
            "transactional_sites": len(ann["transactional"]),
            "liquibase_masters": changesets,
        }
        persistence.write_json(control / "database-map.json", result)
        _out(
            {
                "command": "audit database",
                "liquibase_masters": len(changesets),
                "total_changesets": sum(c["count"] for c in changesets),
                "written": str(control / "database-map.json"),
            }
        )
        return 0

    _out({"error": f"unknown audit type {which}"})
    return 2


def cmd_tasks(args: argparse.Namespace) -> int:
    root = _repo_root()
    reg = TaskRegistry(root)
    if args.tasks_cmd == "list":
        rows = []
        for t in sorted(reg.tasks.values(), key=lambda x: (x.get("priority", "P3"), x["id"])):
            rows.append(
                {
                    "id": t["id"],
                    "priority": t.get("priority"),
                    "state": t.get("state"),
                    "title": t.get("title", t.get("description", ""))[:90],
                    "owner": t.get("agent"),
                    "verifier": t.get("verifier"),
                }
            )
        _out(rows)
        return 0
    if args.tasks_cmd == "next":
        t = reg.next_task()
        _out(t or {"message": "no actionable task"})
        return 0
    if args.tasks_cmd == "status":
        _out(reg.summary())
        return 0
    if args.tasks_cmd == "import-findings":
        return cmd_tasks_import(args)
    if args.tasks_cmd == "show":
        t = reg.get(args.id)
        _out(t or {"error": f"unknown task {args.id}"})
        return 0
    _out({"error": "unknown tasks command"})
    return 2


def cmd_verify(args: argparse.Namespace) -> int:
    root = _repo_root()
    store = verification.EvidenceStore(root)
    names = [args.suite] if args.suite else sorted(verification.SUITES)
    results = {}
    worst = "PASS"
    order = ["PASS", "PARTIAL", "BLOCKED", "FAIL", "NOT_APPLICABLE"]
    for name in names:
        kwargs: dict[str, Any] = {}
        if args.module:
            kwargs["modules"] = args.module
        res = verification.run_suite(root, name, store, **kwargs)
        results[name] = res
        if order.index(res["status"]) > order.index(worst):
            worst = res["status"]
    _out({"command": "verify", "overall": worst, "suites": {k: v["status"] for k, v in results.items()}})
    return 0 if worst in ("PASS", "PARTIAL", "NOT_APPLICABLE") else 1


def cmd_reconcile(args: argparse.Namespace) -> int:
    root = _repo_root()
    control = root / "production-audit" / "control"
    state = root / "production-audit" / "state"
    reg = TaskRegistry(root)
    coverage = persistence.read_json(control / "source-coverage.json", default={"reviews": {}})
    manifest = persistence.read_json(control / "source-manifest.json", default={"files": {}})
    findings = persistence.read_json(state / "findings.json", default={"findings": {}})
    changed = []
    for rel, rev in coverage.get("reviews", {}).items():
        meta = manifest.get("files", {}).get(rel)
        if meta and rev.get("sha256") != meta["sha256"]:
            changed.append(rel)
    reopened = reg.invalidate_by_paths(changed) if changed else []
    reg.save()
    summary = {
        "command": "reconcile",
        "git": manifest.get("git", {}),
        "source_files": len(manifest.get("files", {})),
        "reviewed_files": len(coverage.get("reviews", {})),
        "findings": len(findings.get("findings", {})),
        "tasks": reg.summary(),
        "stale_reviews": len(changed),
        "reopened_tasks": reopened,
    }
    persistence.write_json(state / "reconciliation.json", summary)
    _out(summary)
    return 0


_AGENT_BY_CATEGORY = {
    "security": "A7", "distributed-systems": "A3", "correctness": "A5",
    "database": "A4", "performance": "A10", "configuration": "A11",
    "observability": "A11", "testing": "A12", "deployment": "A11", "documentation": "A14",
}
_VERIFIER_BY_CATEGORY = {
    "security": "A15", "distributed-systems": "A15", "correctness": "A2",
    "database": "A4", "performance": "A12", "configuration": "A15",
    "observability": "A15", "testing": "A12", "deployment": "A15", "documentation": "A14",
}


def cmd_tasks_import(args: argparse.Namespace) -> int:
    """Convert the findings register into actionable, dependency-aware tasks."""
    from .findings import Findings

    root = _repo_root()
    reg = TaskRegistry(root)
    fnd = Findings(root)
    created = []
    for fid, f in sorted(fnd.findings.items()):
        if f.get("status") in ("FALSE_POSITIVE", "NOT_APPLICABLE"):
            continue
        task_id = "TSK-" + fid.split("-", 1)[1]
        deps = list(f.get("depends_on", []))
        task = {
            "id": task_id,
            "finding_id": fid,
            "title": f.get("title", ""),
            "description": f.get("actual", ""),
            "module": (f.get("modules") or [""])[0],
            "modules": f.get("modules", []),
            "business_impact": f.get("consequences", ""),
            "priority": f.get("priority", "P3"),
            "category": f.get("category", "correctness"),
            "dependencies": deps,
            "scope": f.get("remediation", ""),
            "acceptance_criteria": [
                f.get("expected", "") or "Behaviour matches the documented expectation.",
                "A regression test exists and passes.",
                "No new release-blocking finding is introduced by the change.",
            ],
            "required_tests": f.get("required_tests", ["unit"]),
            "agent": _AGENT_BY_CATEGORY.get(f.get("category", "correctness"), "A1"),
            "verifier": _VERIFIER_BY_CATEGORY.get(f.get("category", "correctness"), "A15"),
            "watch_paths": f.get("watch_paths", f.get("modules", [])),
            "source_files": f.get("files", []),
            "regression_test": f.get("regression_test", ""),
            "state": "READY" if not deps else "NORMALIZED",
            "mandatory": f.get("priority") in ("P0", "P1"),
        }
        # Keep the task lifecycle in step with the finding lifecycle: a task
        # whose finding has been remediated is IMPLEMENTED (awaiting
        # independent verification), never READY.
        fstatus = f.get("status", "OPEN")
        if fstatus == "REMEDIATED":
            task["state"] = "IMPLEMENTED"
        elif fstatus == "VERIFIED":
            task["state"] = "VERIFIED"
        elif fstatus in ("RISK_ACCEPTED", "NOT_APPLICABLE", "FALSE_POSITIVE"):
            task["state"] = "NOT_APPLICABLE_APPROVED"
        elif deps:
            task["state"] = "NORMALIZED"
        reg.upsert(task)
        created.append(task_id)
    reg.save()
    _out({"created": len(created), "tasks": created, "summary": reg.summary()})
    return 0


def cmd_findings(args: argparse.Namespace) -> int:
    from .findings import Findings

    root = _repo_root()
    reg = Findings(root)
    if args.findings_cmd == "list":
        rows = []
        for fid, f in sorted(reg.findings.items()):
            rows.append(
                {
                    "id": fid,
                    "priority": f.get("priority"),
                    "category": f.get("category"),
                    "status": f.get("status"),
                    "title": f.get("title", "")[:100],
                }
            )
        _out(rows)
        return 0
    if args.findings_cmd == "summary":
        _out(reg.summary())
        return 0
    if args.findings_cmd == "add":
        payload = json.loads(Path(args.file).read_text(encoding="utf-8"))
        items = payload if isinstance(payload, list) else [payload]
        for item in items:
            reg.upsert(item)
        reg.save()
        _out({"added": len(items), "total": len(reg.findings)})
        return 0
    if args.findings_cmd == "status":
        f = reg.set_status(args.id, args.new_status, args.note or "")
        _out({"id": args.id, "status": f["status"]})
        return 0
    if args.findings_cmd == "remediate":
        payload = json.loads(Path(args.file).read_text(encoding="utf-8"))
        items = payload.get("findings", payload) if isinstance(payload, dict) else payload
        applied = []
        for item in items:
            fid = item["id"]
            merged = dict(item)
            merged.pop("id", None)
            merged["id"] = fid
            reg.upsert(merged)
            if item.get("status") in ("REMEDIATED", "VERIFIED", "RISK_ACCEPTED", "NOT_APPLICABLE", "FALSE_POSITIVE"):
                reg.set_status(fid, item["status"], item.get("verification_evidence", "")[:200])
            applied.append(fid)
        reg.save()
        _out({"applied": applied, "summary": reg.summary()})
        return 0
    _out({"error": "unknown findings command"})
    return 2


def cmd_run(args: argparse.Namespace) -> int:
    from .scheduler import Scheduler

    root = _repo_root()
    sched = Scheduler(root, max_iterations=args.max_iterations)
    result = sched.run()
    _out(result)
    return 0 if result.get("status") in ("completed", "exhausted") else 1


def cmd_resume(args: argparse.Namespace) -> int:
    root = _repo_root()
    reg = TaskRegistry(root)
    state = root / "production-audit" / "state"
    summary = reg.summary()
    _out(
        {
            "command": "resume",
            "persisted_state": str(state),
            "tasks": summary,
            "next_actionable": [t["id"] for t in reg.ready_tasks()[:10]],
        }
    )
    return 0


def cmd_report(args: argparse.Namespace) -> int:
    from .report import generate_reports

    root = _repo_root()
    out = generate_reports(root)
    _out(out)
    return 0


def cmd_mark_reviewed(args: argparse.Namespace) -> int:
    """Record that a source file has been fully read (coverage ledger)."""
    root = _repo_root()
    control = root / "production-audit" / "control"
    cov_path = control / "source-coverage.json"
    coverage = persistence.read_json(cov_path, default={"schema_version": 1, "reviews": {}})
    manifest = persistence.read_json(control / "source-manifest.json", default={"files": {}})
    files = manifest.get("files", {})
    reviews = coverage.setdefault("reviews", {})
    added, skipped = [], []
    for rel in args.paths:
        meta = files.get(rel)
        if meta is None:
            skipped.append({"path": rel, "reason": "not_in_manifest"})
            continue
        prev = reviews.get(rel, {})
        reviews[rel] = {
            "path": rel,
            "sha256": meta["sha256"],
            "lines": meta["lines"],
            "module": meta["module"],
            "classification": meta["classification"],
            "status": "FULLY_READ",
            "reviewer": args.reviewer,
            "batch": args.batch,
            "read_ranges": args.ranges or [[1, meta["lines"]]],
            "reviewed_at": __import__("time").strftime("%Y-%m-%dT%H:%M:%S"),
            "notes": args.note or prev.get("notes", ""),
            "findings": prev.get("findings", []),
        }
        added.append(rel)
    persistence.write_json(cov_path, coverage)
    _out({"marked": len(added), "skipped": skipped, "paths": added})
    return 0


# -------------------------------------------------------------- parser
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="harness", description="Petproject production-audit harness")
    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("inventory", help="build source manifest + coverage reconciliation")

    a = sub.add_parser("audit", help="architecture / source / security / performance audits")
    asub = a.add_subparsers(dest="audit_type", required=True)
    for t in (
        "source",
        "architecture",
        "distributed-systems",
        "security",
        "performance",
        "database",
    ):
        asub.add_parser(t)

    t = sub.add_parser("tasks", help="task registry operations")
    tsub = t.add_subparsers(dest="tasks_cmd", required=True)
    tsub.add_parser("list")
    tsub.add_parser("next")
    tsub.add_parser("status")
    tsub.add_parser("import-findings")
    show = tsub.add_parser("show")
    show.add_argument("id")

    v = sub.add_parser("verify", help="run verification suites")
    v.add_argument("--suite")
    v.add_argument("--module", action="append")

    sub.add_parser("reconcile", help="reconcile state, findings and coverage")
    r = sub.add_parser("run", help="autonomous remediation loop")
    r.add_argument("--max-iterations", type=int, default=20)
    sub.add_parser("resume", help="show persisted state and next actions")
    sub.add_parser("report", help="generate markdown reports")

    fnd = sub.add_parser("findings", help="findings register operations")
    fsub = fnd.add_subparsers(dest="findings_cmd", required=True)
    fsub.add_parser("list")
    fsub.add_parser("summary")
    fadd = fsub.add_parser("add")
    fadd.add_argument("file", help="JSON file with one finding object or a list")
    fst = fsub.add_parser("status")
    fst.add_argument("id")
    fst.add_argument("new_status")
    fst.add_argument("--note", default="")
    frem = fsub.add_parser("remediate")
    frem.add_argument("file", help="JSON file with per-finding remediation records")

    m = sub.add_parser("mark-reviewed", help="record full-read coverage for source files")
    m.add_argument("paths", nargs="+")
    m.add_argument("--reviewer", default="A0")
    m.add_argument("--batch", default="")
    m.add_argument("--ranges", action="append")
    m.add_argument("--note", default="")
    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    handlers = {
        "inventory": cmd_inventory,
        "audit": cmd_audit,
        "tasks": cmd_tasks,
        "verify": cmd_verify,
        "reconcile": cmd_reconcile,
        "run": cmd_run,
        "resume": cmd_resume,
        "report": cmd_report,
        "findings": cmd_findings,
        "mark-reviewed": cmd_mark_reviewed,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    sys.exit(main())
