from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path
from typing import Any

from .. import config
from ..e2e.environment import e2e_report, e2e_run, e2e_setup, e2e_teardown, detect_dependencies
from ..git_delivery.delivery import commit_verified, delivery_report, push_verified, record_delivery, repository_status
from ..inventory.coverage import coverage_summary, initialize_from_manifest, load_coverage, record_review
from ..inventory.scanner import build_inventory, persist_inventory
from ..persistence.store import append_event, file_lock, read_json, utc_now, write_json
from ..reconciliation.reconcile import reconcile
from ..reporting.report import status_document, write_markdown_status, write_status_report
from ..research.store import research_index, seed_research_index
from ..source_policy.cleaner import clean_repository
from ..source_policy.policy import Policy
from ..source_policy.scanner import scan_repository
from ..task_registry.registry import dependency_ready, load_tasks, next_task, task_status_summary, transition, upsert_task, validate_task_id, Task
from ..verification.gates import (
    BLOCKED,
    FAIL,
    PASS,
    PENDING,
    completion_predicate,
    evaluate_gates,
    load_verification,
    record_suite_result,
)
from ..verification.suites import load_suites, run_build, run_suite

AUDIT_TOPICS = ("architecture", "patterns", "concurrency", "distributed", "security", "performance")


def emit(payload: Any, as_json: bool = True) -> None:
    if as_json:
        print(json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False, default=str))
    else:
        print(payload)


def _policy() -> Policy:
    policy_path = config.comment_policy_path()
    if not policy_path.exists():
        policy = Policy()
        policy.save(policy_path)
        return policy
    return Policy.load(policy_path)


def command_inventory(arguments: argparse.Namespace) -> int:
    repository = config.repository_root()
    manifest = build_inventory(repository)
    persist_inventory(manifest)
    initialize_from_manifest(manifest)
    append_event(config.events_path(), "inventory", {"file_count": manifest["file_count"], "revision": manifest["revision"]})
    emit(
        {
            "revision": manifest["revision"],
            "branch": manifest["branch"],
            "file_count": manifest["file_count"],
            "total_lines": manifest["total_lines"],
            "category_summary": manifest["category_summary"],
            "language_summary": manifest["language_summary"],
            "module_summary": manifest["module_summary"],
        }
    )
    return config.EXIT_OK


def command_coverage(arguments: argparse.Namespace) -> int:
    document = load_coverage()
    summary = coverage_summary(document)
    if arguments.import_reports:
        from ..inventory.coverage_import import import_report_coverage

        result = import_report_coverage(reviewer=arguments.reviewer or "audit-agent")
        emit({**result, "summary": coverage_summary(load_coverage())})
        return config.EXIT_OK
    if arguments.mark:
        relative = arguments.mark
        entry = record_review(relative, arguments.state, reviewer=arguments.reviewer, notes=arguments.notes or "")
        emit({"marked": relative, "state": entry.get("coverage_state"), "summary": coverage_summary()})
        return config.EXIT_OK
    if arguments.mark_file:
        from ..inventory.coverage import mark_paths

        listing = Path(arguments.mark_file)
        paths = [line.strip() for line in listing.read_text(encoding="utf-8").splitlines() if line.strip()]
        result = mark_paths(paths, state=arguments.state, reviewer=arguments.reviewer, notes=arguments.notes or "")
        emit(result)
        return config.EXIT_OK
    emit(summary)
    return config.EXIT_OK


def command_audit(arguments: argparse.Namespace) -> int:
    topic = arguments.topic
    run_id = config.run_identifier()
    reviews_root = config.run_root(run_id) / "source-reviews"
    mapping = {
        "architecture": "shared-libraries.md",
        "patterns": "shared-libraries.md",
        "concurrency": "order-inventory.md",
        "distributed": "kafka-outbox.md",
        "security": "security.md",
        "performance": "catalog-search-media.md",
    }
    expected = mapping.get(topic, f"{topic}.md")
    report_path = reviews_root / expected
    findings = read_json(config.findings_path(), {}) or {}
    topic_findings = [entry for entry in findings.get("findings", []) if entry.get("topic") == topic]
    document = {
        "topic": topic,
        "expected_artifact": str(report_path),
        "artifact_present": report_path.exists(),
        "artifact_bytes": report_path.stat().st_size if report_path.exists() else 0,
        "registered_findings": len(topic_findings),
        "severity_breakdown": severity_breakdown(topic_findings),
        "status": "EVIDENCE_AVAILABLE" if report_path.exists() else "EVIDENCE_MISSING",
    }
    emit(document)
    return config.EXIT_OK if document["artifact_present"] else config.EXIT_BLOCKED


def severity_breakdown(findings: list[dict[str, Any]]) -> dict[str, int]:
    summary: dict[str, int] = {}
    for entry in findings:
        severity = entry.get("severity", "UNKNOWN")
        summary[severity] = summary.get(severity, 0) + 1
    return summary


def command_research(arguments: argparse.Namespace) -> int:
    seed_research_index()
    index = research_index()
    emit({"count": index["count"], "missing": index["missing"], "reference_index": index["topics_known"]})
    return config.EXIT_OK


def command_comments_scan(arguments: argparse.Namespace) -> int:
    report = scan_repository(config.repository_root(), _policy())
    write_json(config.run_root() / "comments-scan.json", report)
    emit(
        {
            "scanned_files": report["scanned_files"],
            "total_comments": report["total_comments"],
            "prohibited_comments": report["prohibited_comments"],
            "preserved_comments": report["preserved_comments"],
            "files_with_violations": report["files_with_violations"],
            "violations": report["violations"][:200],
            "report": str(config.run_root() / "comments-scan.json"),
        }
    )
    return config.EXIT_OK


def _write_rationale_archive(outcomes: list[Any]) -> Path | None:
    captured = [outcome for outcome in outcomes if outcome.get("removed_texts")]
    if not captured:
        return None
    target = config.architecture_root() / "comment-rationale.md"
    lines = [
        "# Archived Source Rationale",
        "",
        "Design rationale removed from first-party source under the no-comment policy.",
        "Each entry records the original location and the exact removed text.",
        "",
    ]
    for outcome in captured:
        lines.append(f"## {outcome['path']}")
        lines.append("")
        for text in outcome["removed_texts"]:
            lines.append("```text")
            lines.append(text.strip("\n"))
            lines.append("```")
            lines.append("")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines), encoding="utf-8")
    return target


def command_comments_archive(arguments: argparse.Namespace) -> int:
    from ..source_policy.archive import build_archive

    document = build_archive(config.repository_root(), arguments.revision or None)
    emit(document)
    return config.EXIT_OK


def command_comments_clean(arguments: argparse.Namespace) -> int:
    policy = _policy()
    manifest = read_json(config.source_manifest_path(), {}) or {}
    paths = [entry["path"] for entry in manifest.get("files", []) if entry.get("comment_gate")]
    if arguments.module:
        paths = [path for path in paths if path.startswith(arguments.module)]
    if arguments.paths:
        paths = list(arguments.paths)
    result = clean_repository(paths, config.repository_root(), policy, dry_run=arguments.dry_run)
    write_json(config.run_root() / "comments-clean.json", result)
    archive = None
    if not arguments.dry_run:
        archive = _write_rationale_archive(result["outcomes_detail"])
    emit(
        {
            "dry_run": arguments.dry_run,
            "processed_files": result["processed_files"],
            "changed_files": result["changed_files"],
            "removed_comments": result["removed_comments"],
            "removed_lines": result["removed_lines"],
            "preserved_comments": result["preserved_comments"],
            "rationale_archive": str(archive) if archive else "",
            "changed": [entry["path"] for entry in result["outcomes"] if entry["changed"]],
        }
    )
    return config.EXIT_OK


def command_verify(arguments: argparse.Namespace) -> int:
    if arguments.suite == "comments":
        report = scan_repository(config.repository_root(), _policy())
        if arguments.deep:
            from ..source_policy.equivalence import verify_comment_only_changes

            manifest = read_json(config.source_manifest_path(), {}) or {}
            paths = [entry["path"] for entry in manifest.get("files", []) if entry.get("comment_gate")]
            equivalence = verify_comment_only_changes(paths, config.repository_root(), "HEAD", _policy())
            write_json(config.run_root() / "comments-equivalence.json", equivalence)
            report["equivalence"] = equivalence
            report["status"] = "PASS" if report["prohibited_comments"] == 0 and equivalence["clean"] else "FAIL"
            record_suite_result("comments", {"suite": "comments", "status": report["status"], "prohibited_comments": report["prohibited_comments"], "scanned_files": report["scanned_files"], "equivalence_diverged": equivalence["diverged"], "updated_at": utc_now()})
            emit({"suite": "comments", "status": report["status"], "prohibited_comments": report["prohibited_comments"], "scanned_files": report["scanned_files"], "equivalence_checked": equivalence["checked"], "equivalence_diverged": equivalence["diverged"], "diverged_details": equivalence["diverged_details"][:10], "other": equivalence["other"][:10]})
            return config.EXIT_OK if report["status"] == "PASS" else config.EXIT_GATE_FAILED
        write_json(config.run_root() / "comments-scan.json", report)
        gate = {
            "suite": "comments",
            "status": "PASS" if report["prohibited_comments"] == 0 else "FAIL",
            "prohibited_comments": report["prohibited_comments"],
            "scanned_files": report["scanned_files"],
            "updated_at": utc_now(),
        }
        record_suite_result("comments", gate)
        emit(gate)
        return config.EXIT_OK if gate["status"] == "PASS" else config.EXIT_GATE_FAILED

    gates = evaluate_gates()
    predicate = completion_predicate(gates)
    existing = load_verification()
    document = {
        "generated_at": utc_now(),
        "revision": existing.get("revision", ""),
        "suites": existing.get("suites", {}),
        "gates": [gate.to_document() for gate in gates],
        "completion_predicate": predicate,
    }
    write_json(config.run_root() / "verification.json", document)
    emit(document)
    failed = [gate.id for gate in gates if gate.status == "FAIL"]
    if failed:
        return config.EXIT_GATE_FAILED
    pending = [gate.id for gate in gates if gate.status in ("PENDING", "BLOCKED", "INSUFFICIENT_EVIDENCE")]
    if pending:
        return config.EXIT_BLOCKED
    return config.EXIT_OK


def command_build(arguments: argparse.Namespace) -> int:
    result = run_build(config.repository_root(), quick=arguments.quick, modules=arguments.modules)
    record_suite_result("build", result)
    emit(result)
    return config.EXIT_OK if result["status"] == "PASS" else config.EXIT_GATE_FAILED


def command_operations(arguments: argparse.Namespace) -> int:
    from ..verification.operations import run_checks

    document = run_checks()
    status = PASS if document.get("status") == "PASS" else FAIL
    record_suite_result(
        "operations",
        {
            "suite": "operations",
            "status": status,
            "detail": f"{document.get('passed')} of {document.get('total')} observability checks passed",
            "checks": document.get("checks", []),
            "not_verified": document.get("not_verified", []),
            "updated_at": utc_now(),
        },
    )
    emit(document)
    return config.EXIT_OK if status == PASS else config.EXIT_GATE_FAILED


def command_k8s(arguments: argparse.Namespace) -> int:
    from ..kubernetes.validation import validate_manifests
    from ..persistence.store import append_event

    action = arguments.action
    if action == "validate":
        document = validate_manifests()
        record_suite_result(
            "k8s",
            {
                "suite": "k8s",
                "status": PASS if document["status"] == "PASS" else FAIL,
                "detail": str(len(document["failed_checks"])) + " kubernetes manifest checks failed",
                "checks": document["checks"],
                "updated_at": utc_now(),
            },
        )
        append_event(config.events_path(), "k8s_validate", {"status": document["status"]})
        write_json(config.state_root() / "k8s-results.json", {"action": "validate", "updated_at": utc_now(), "result": document})
        emit(document)
        return config.EXIT_OK if document["status"] == "PASS" else config.EXIT_GATE_FAILED

    emit(
        {
            "action": action,
            "status": "BLOCKED",
            "reason": "a reachable kubernetes cluster is required for this command and none is configured",
        }
    )
    return config.EXIT_BLOCKED


def _load_sweep_verdict(
    root: Path,
    capacity: dict[str, Any],
    evidence: dict[str, str] | None = None,
) -> dict[str, Any]:
    from ..loadtesting.capacity import sweep_verdict

    return sweep_verdict(root, capacity, evidence)


def _new_load_attempt(root: Path, profile: str) -> Path:
    with file_lock(root / "load-sequence.lock"):
        sequence_document = read_json(root / "load-sequence.json", {}) or {}
        recorded = sequence_document.get("sequence")
        sequence = recorded if type(recorded) is int else 0
        directory_stamps = []
        for path in root.glob("*-*"):
            try:
                directory_stamps.append(int(path.name.rsplit("-", 1)[-1]))
            except ValueError:
                continue
        stamp = max([sequence, int(time.time()), *directory_stamps]) + 1
        write_json(root / "load-sequence.json", {"sequence": stamp})
        output = root / (profile + "-" + str(stamp))
        output.mkdir(parents=True)
        return output


def _begin_load_attempt(root: Path, profile: str, evidence: dict[str, str]) -> tuple[Path, dict[str, Any]]:
    with file_lock(root / "load-sequence.lock"):
        active_path = root / "active-load.json"
        if active_path.exists():
            raise RuntimeError("another load attempt is active or did not finalize")
        sequence_document = read_json(root / "load-sequence.json", {}) or {}
        recorded = sequence_document.get("sequence")
        sequence = recorded if type(recorded) is int else 0
        directory_stamps = []
        for path in root.glob("*-*"):
            try:
                directory_stamps.append(int(path.name.rsplit("-", 1)[-1]))
            except ValueError:
                continue
        stamp = max([sequence, int(time.time()), *directory_stamps]) + 1
        output = root / (profile + "-" + str(stamp))
        active = {
            "profile": profile,
            "output": str(output),
            "started_at": utc_now(),
            **evidence,
        }
        with active_path.open("x", encoding="utf-8") as handle:
            handle.write(json.dumps(active, indent=2, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        write_json(root / "load-sequence.json", {"sequence": stamp})
        output.mkdir(parents=True)
        return output, active


def command_load(arguments: argparse.Namespace) -> int:
    from ..loadtesting.invariants import verify
    from ..loadtesting.profiles import describe_profiles, enforce_safety, resolve_profile
    from ..loadtesting.runner import results_root, run_profile
    from ..persistence.store import append_event, write_json

    action = arguments.action
    if action == "profiles":
        emit({"profiles": describe_profiles()})
        return config.EXIT_OK
    if action == "reset-data":
        from ..loadtesting.runner import reset_commerce_data

        document = reset_commerce_data()
        emit(document)
        return config.EXIT_OK if document["status"] == "PASS" else config.EXIT_GATE_FAILED
    if action == "invariants":
        document = verify()
        emit(document)
        return config.EXIT_OK if document["status"] == "PASS" else config.EXIT_GATE_FAILED
    if action == "report":
        from ..loadtesting.capacity import capacity_limit_document, record_capacity_limit

        collected = []
        root = results_root()
        if root.exists():
            for path in sorted(root.iterdir()):
                summary = path / "summary.json"
                if summary.exists():
                    document = json.loads(summary.read_text(encoding="utf-8"))
                    collected.append({"directory": path.name, "metrics": document.get("metrics", {})})
        record_capacity_limit()
        capacity = capacity_limit_document()
        aggregate = _load_sweep_verdict(root, capacity)
        passed = sum(1 for row in aggregate["profiles"] if row["status"] == PASS)
        record_suite_result(
            "performance",
            {
                "suite": "performance",
                "status": aggregate["status"],
                "profile": "all",
                "measurement_valid": aggregate["status"] == PASS,
                "metrics": {},
                "invariants": PASS if not aggregate["invalid"] else FAIL,
                "detail": (
                    str(passed) + "/" + str(len(aggregate["profiles"])) + " load profiles passed; "
                    "capacity binding=" + str(aggregate["capacity_binds_to_current_source"])
                ),
                "updated_at": utc_now(),
                **{
                    key: aggregate[key]
                    for key in ("revision", "source_fingerprint", "harness_fingerprint")
                    if aggregate.get(key)
                },
            },
        )
        emit({"runs": collected, "aggregate": aggregate, "capacity": capacity})
        if aggregate["status"] == PASS:
            return config.EXIT_OK
        if aggregate["status"] in (PENDING, BLOCKED):
            return config.EXIT_BLOCKED
        return config.EXIT_GATE_FAILED

    scale = arguments.scale
    try:
        profile = resolve_profile(action, scale)
    except (KeyError, ValueError) as error:
        emit({"status": "FAIL", "reason": str(error)})
        return config.EXIT_USAGE
    from ..loadtesting.capacity import current_evidence, record_capacity_limit

    violations = enforce_safety(profile)
    evidence = current_evidence()
    root = results_root()
    active_path = root / "active-load.json"
    attempt_output, active = _begin_load_attempt(root, action, evidence)
    run_error: BaseException | None = None
    try:
        if violations:
            document = {
                "status": BLOCKED,
                "profile": action,
                "reason": "the safety boundary refused this plan",
                "violations": violations,
                **evidence,
            }
        else:
            try:
                document = {
                    **run_profile(action, scale=scale, base_url=arguments.base_url, output=attempt_output),
                    **evidence,
                }
            except BaseException as error:
                run_error = error
                document = {
                    "status": FAIL,
                    "profile": action,
                    "reason": "the load attempt raised an exception",
                    "error": str(error),
                    "output": str(attempt_output),
                    **evidence,
                }
    except BaseException as error:
        run_error = error
        document = {
            "status": FAIL,
            "profile": action,
            "reason": "the load attempt raised an exception",
            "error": str(error),
            "output": str(attempt_output),
            **evidence,
        }
    output_value = document.get("output")
    output = Path(output_value) if output_value else attempt_output
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "result.json", document)
    write_json(root / (action + "-latest.json"), document)
    append_event(config.events_path(), "load_" + action, {"status": document.get("status")})
    baselines_path = config.state_root() / "performance-baselines.json"
    baselines = read_json(baselines_path, {}) or {}
    baselines["profile"] = action
    baselines["updated_at"] = utc_now()
    baselines["result"] = document
    write_json(baselines_path, baselines)
    record_capacity_limit(baselines_path)
    record_suite_result(
        "load_" + action,
        {
            "suite": "load_" + action,
            "status": document.get("status", FAIL),
            "profile": action,
            "measurement_valid": bool(document.get("measurement_valid")),
            "metrics": document.get("metrics"),
            "invariants": (document.get("invariants") or {}).get("status"),
            "updated_at": utc_now(),
            **evidence,
        },
    )
    record_suite_result(
        "performance",
        {
            "suite": "performance",
            "status": FAIL if run_error is not None else PENDING,
            "profile": "pending",
            "detail": "profile " + action + " finished " + str(document.get("status")) + "; aggregate report required",
            "updated_at": utc_now(),
            **evidence,
        },
    )
    with file_lock(active_path):
        if read_json(active_path, {}) == active:
            active_path.unlink(missing_ok=True)
    if run_error is not None:
        raise run_error
    emit(document)
    if document.get("status") == "PASS":
        invariants = document.get("invariants") or {}
        if invariants.get("status") == "FAIL":
            return config.EXIT_GATE_FAILED
        return config.EXIT_OK
    if document.get("status") == "BLOCKED":
        return config.EXIT_BLOCKED
    return config.EXIT_GATE_FAILED


def command_stripe(arguments: argparse.Namespace) -> int:
    from ..persistence.store import append_event
    from ..stripe.sandbox import reconcile, test_payment, test_webhooks, verify_configuration

    action = arguments.action
    if action == "acceptance":
        return command_stripe_acceptance()
    if action == "webhook-setup":
        return command_stripe_webhook_setup()
    if action == "verify-config":
        document = verify_configuration()
    elif action == "sandbox-smoke":
        document = verify_configuration()
    elif action == "test-payment":
        document = test_payment()
    elif action == "test-webhooks":
        document = test_webhooks()
    elif action == "reconcile":
        document = reconcile()
    else:
        emit({"status": "FAIL", "reason": "unknown action"})
        return config.EXIT_USAGE
    append_event(config.events_path(), "stripe_" + action, {"status": document.get("status")})
    write_json(config.state_root() / "stripe-results.json", {"action": action, "updated_at": utc_now(), "result": document})
    emit(document)
    if document.get("status") == "PASS":
        return config.EXIT_OK
    if document.get("status") == "BLOCKED":
        return config.EXIT_BLOCKED
    return config.EXIT_GATE_FAILED


def command_stripe_webhook_setup() -> int:
    from ..stripe.webhook_setup import setup_webhook

    document = setup_webhook()
    print(json.dumps(document, indent=2, default=str))
    if document.get("state") == "ready":
        return 0
    return config.EXIT_BLOCKED


def command_stripe_acceptance() -> int:
    from ..stripe.acceptance import run_acceptance, write_evidence
    from ..verification.gates import record_suite_result

    try:
        document = run_acceptance()
    except BaseException as error:
        from ..inventory.scanner import harness_fingerprint, source_fingerprint
        from ..reconciliation.reconcile import current_revision

        repository = config.repository_root()
        document = {
            "generated_at": utc_now(),
            "status": "FAILED",
            "reason": "the Stripe acceptance attempt raised an exception",
            "error": str(error),
            "summary": {"cases": 0, "proven": 0, "not_proven": 0, "failed": 1},
            "cases": [],
            "verified_against_real_stripe_api": False,
            "revision": current_revision(repository),
            "source_fingerprint": source_fingerprint(repository),
            "harness_fingerprint": harness_fingerprint(repository),
        }
    path = write_evidence(document)
    summary = document.get("summary") or {}
    verified = bool(document.get("verified_against_real_stripe_api"))
    status = "PASS" if verified else ("FAIL" if document.get("status") == "FAILED" else "BLOCKED")
    record_suite_result(
        "stripe_acceptance",
        {
            "suite": "stripe_acceptance",
            "status": status,
            "exit_code": 0 if document.get("status") != "FAILED" else 1,
            "cases": summary.get("cases", 0),
            "proven": summary.get("proven", 0),
            "not_proven": summary.get("not_proven", 0),
            "failed": summary.get("failed", 0),
            "stripe_server": (document.get("stripe_server") or {}).get("kind", ""),
            "stripe_version": (document.get("stripe_server") or {}).get("version", ""),
            "verified_against_real_stripe_api": verified,
            "evidence": str(path),
            "updated_at": utc_now(),
            **{
                key: document[key]
                for key in ("revision", "source_fingerprint", "harness_fingerprint")
                if document.get(key)
            },
        },
        path=config.run_root() / "verification.json",
    )
    write_json(
        config.run_root() / "stripe-acceptance" / "cli-result.json",
        {"action": "acceptance", "updated_at": utc_now(), "result": document},
    )
    emit(document)
    if document.get("status") == "FAILED":
        return config.EXIT_GATE_FAILED
    if verified:
        return config.EXIT_OK
    return config.EXIT_BLOCKED


def command_alerts(arguments: argparse.Namespace) -> int:
    from ..alerts.delivery import (
        run_ci_alert_test,
        run_dedup_test,
        run_recovery_test,
        run_runtime_alert_test,
        run_security_probe,
    )
    from ..alerts.render import write_rendered_config
    from ..alerts.state import alert_gate_statuses, load_alert_results
    from ..alerts.validation import validate_alerting
    from ..persistence.store import append_event

    action = arguments.action
    if action == "validate":
        document = validate_alerting()
        record_suite_result(
            "alerts",
            {
                "suite": "alerts",
                "status": PASS if document["status"] == "PASS" else ("BLOCKED" if document["status"] == "BLOCKED" else FAIL),
                "detail": str(len(document["failed_checks"])) + " failed and " + str(len(document["blocked_checks"])) + " blocked checks",
                "checks": document["checks"],
                "updated_at": utc_now(),
            },
        )
        append_event(config.events_path(), "alerts_validate", {"status": document["status"]})
        write_json(config.state_root() / "alerting-results.json", {"action": "validate", "updated_at": utc_now(), "result": document})
        emit(document)
        if document["status"] == "PASS":
            return config.EXIT_OK
        if document["status"] == "BLOCKED":
            return config.EXIT_BLOCKED
        return config.EXIT_GATE_FAILED

    if action == "status":
        document = load_alert_results()
        emit({"gates": alert_gate_statuses(), "results": document.get("results", [])})
        return config.EXIT_OK

    if action == "render":
        target = write_rendered_config()
        emit({"rendered": str(target), "note": "the rendered file holds the live credential and must stay out of git"})
        return config.EXIT_OK

    runners = {
        "test-ci": run_ci_alert_test,
        "test-runtime": run_runtime_alert_test,
        "test-recovery": run_recovery_test,
        "test-dedup": run_dedup_test,
        "test-security": run_security_probe,
    }
    result = runners[action]()
    append_event(config.events_path(), "alert_" + action, {"status": result["status"]})
    emit(result)
    if result["status"] == "PASS":
        return config.EXIT_OK
    if result["status"] == "BLOCKED":
        return config.EXIT_BLOCKED
    return config.EXIT_GATE_FAILED


def command_test(arguments: argparse.Namespace) -> int:
    result = run_suite(arguments.suite, config.repository_root(), modules=arguments.modules)
    record_suite_result(arguments.suite, result)
    emit(result)
    return config.EXIT_OK if result["status"] == "PASS" else config.EXIT_GATE_FAILED


def command_e2e(arguments: argparse.Namespace) -> int:
    action = arguments.action
    if action == "setup":
        document = e2e_setup(config.repository_root())
    elif action == "teardown":
        document = e2e_teardown(config.repository_root(), volumes=arguments.volumes)
    elif action == "run":
        document = e2e_run(arguments.scope, config.repository_root())
    elif action == "report":
        document = e2e_report()
    elif action == "dependencies":
        document = {"components": [status.to_document() for status in detect_dependencies()]}
    else:
        document = {"error": f"unknown e2e action: {action}"}
    if action == "report":
        write_json(config.run_root() / "e2e-results" / "report.json", document)
    emit(document)
    record_e2e_suite(action, document)
    state = document.get("state", "")
    if state in ("BLOCKED", "FAILED"):
        return config.EXIT_BLOCKED if state == "BLOCKED" else config.EXIT_GATE_FAILED
    return config.EXIT_OK


def record_e2e_suite(action: str, document: dict[str, Any]) -> None:
    if action not in ("run", "report"):
        return
    evidence = {
        key: document[key]
        for key in ("revision", "source_fingerprint", "harness_fingerprint")
        if document.get(key)
    }
    sequence = int(document.get("sequence") or document.get("attempt") or 0)
    if action == "report":
        verified = bool(document.get("e2e_verified"))
        executed = int(document.get("executed_collections", 0) or 0)
        status = PASS if verified and executed > 0 else FAIL
        detail = (
            f"{executed} end to end collections completed the run"
            if verified and executed > 0
            else "no current end to end collection completed a run"
        )
    else:
        state = document.get("state", "")
        status = FAIL if state == "FAILED" else (BLOCKED if state == "BLOCKED" else PENDING)
        detail = f"end to end run state {state}; a reconciled report is required before pass"
    record_suite_result(
        "e2e",
        {
            "suite": "e2e",
            "status": status,
            "detail": detail,
            "sequence": sequence,
            "updated_at": utc_now(),
            **evidence,
        },
    )


def command_tasks(arguments: argparse.Namespace) -> int:
    action = arguments.action
    tasks = load_tasks()
    if action == "list":
        emit({"tasks": [task.to_document() for task in sorted(tasks.values(), key=lambda item: item.id)]})
        return config.EXIT_OK
    if action == "status":
        emit({"count": len(tasks), "states": task_status_summary(tasks)})
        return config.EXIT_OK
    if action == "next":
        candidate = next_task()
        if candidate is None:
            emit({"task": None, "reason": "no dependency-ready task available", "states": task_status_summary(tasks)})
            return config.EXIT_BLOCKED
        emit({"task": candidate.to_document()})
        return config.EXIT_OK
    if action == "add":
        task = Task(
            id=arguments.id,
            title=arguments.title,
            severity=arguments.severity,
            priority=arguments.priority,
            affected_service=arguments.service,
            source_evidence=arguments.evidence or [],
            design_pattern=arguments.pattern or "",
        )
        upsert_task(task)
        emit({"added": task.id, "state": task.state})
        return config.EXIT_OK
    if action == "transition":
        updated = transition(arguments.id, arguments.state, arguments.evidence or [])
        emit({"task": updated.id, "state": updated.state})
        return config.EXIT_OK
    if action == "import":
        return command_tasks_import(arguments)
    if action == "normalize":
        return command_tasks_normalize(arguments)


def command_tasks_normalize(arguments: argparse.Namespace) -> int:
    tasks = load_tasks()
    promoted: list[str] = []
    rejected: list[dict[str, str]] = []
    for task in sorted(tasks.values(), key=lambda item: item.id):
        if task.state != "DISCOVERED":
            continue
        if not task.acceptance_criteria:
            rejected.append({"id": task.id, "reason": "no acceptance criteria"})
            continue
        if not task.source_evidence:
            rejected.append({"id": task.id, "reason": "no source evidence"})
            continue
        transition(task.id, "NORMALIZED", ["normalized from discovered"], None)
        transition(task.id, "READY", ["acceptance criteria and source evidence present"], None)
        promoted.append(task.id)
    emit({"promoted": promoted, "promoted_count": len(promoted), "rejected": rejected})
    return config.EXIT_OK


def command_findings_import_report(arguments: argparse.Namespace) -> int:
    from ..source_policy.report_parser import parse_report_file

    source = Path(arguments.file)
    if not source.exists():
        emit({"error": "file not found", "path": str(source)})
        return config.EXIT_USAGE
    findings = parse_report_file(source)
    target = Path(arguments.output) if arguments.output else config.state_root() / "findings" / f"{source.stem}.json"
    write_json(target, {"topic": arguments.topic, "source_report": str(source), "findings": findings})
    emit({"parsed": len(findings), "output": str(target), "severities": severity_breakdown(findings)})
    return config.EXIT_OK


def command_tasks_import(arguments: argparse.Namespace) -> int:
    source = Path(arguments.file)
    if not source.exists():
        emit({"error": "file not found", "path": str(source)})
        return config.EXIT_USAGE
    document = json.loads(source.read_text(encoding="utf-8"))
    findings = document.get("findings", document if isinstance(document, list) else [])
    created: list[str] = []
    skipped: list[dict[str, str]] = []
    for finding in findings:
        task_id = finding.get("id", "")
        if not validate_task_id(task_id):
            skipped.append({"id": task_id, "reason": "invalid task id"})
            continue
        existing = load_tasks()
        if task_id in existing:
            skipped.append({"id": task_id, "reason": "already registered"})
            continue
        task = Task(
            id=task_id,
            title=finding.get("title", task_id),
            severity=finding.get("severity", "MEDIUM"),
            priority=int(finding.get("priority", 50)),
            affected_service=finding.get("affected_service", ""),
            source_evidence=finding.get("source_evidence", []),
            business_risk=finding.get("business_risk", ""),
            technical_risk=finding.get("technical_risk", ""),
            current_implementation=finding.get("current_implementation", ""),
            target_implementation=finding.get("target_implementation", ""),
            design_pattern=finding.get("design_pattern", ""),
            acceptance_criteria=finding.get("acceptance_criteria", []),
            happy_path_tests=finding.get("happy_path_tests", []),
            edge_case_tests=finding.get("edge_case_tests", []),
            concurrency_tests=finding.get("concurrency_tests", []),
            integration_e2e_coverage=finding.get("integration_e2e_coverage", []),
            dependencies=finding.get("dependencies", []),
            assigned_agent=finding.get("assigned_agent", ""),
            independent_verifier=finding.get("independent_verifier", ""),
            notes=finding.get("notes", ""),
        )
        upsert_task(task)
        created.append(task_id)
    emit({"created": created, "created_count": len(created), "skipped": skipped})
    return config.EXIT_OK


def command_findings(arguments: argparse.Namespace) -> int:
    if arguments.action == "import-report":
        return command_findings_import_report(arguments)
    emit({"error": f"unknown findings action: {arguments.action}"})
    return config.EXIT_USAGE


def command_reconcile(arguments: argparse.Namespace) -> int:
    document = reconcile(config.repository_root())
    emit(document)
    return config.EXIT_OK if document["stable"] else config.EXIT_BLOCKED


def command_git(arguments: argparse.Namespace) -> int:
    action = arguments.action
    root = config.repository_root()
    if action == "status":
        emit(repository_status(root))
        return config.EXIT_OK
    if action == "commit-verified":
        record = commit_verified(root, arguments.message, arguments.paths or [], arguments.allow_path)
        record_delivery(record)
        emit(record.to_document())
        return config.EXIT_OK if record.state == "COMMITTED" else config.EXIT_GATE_FAILED
    if action == "push-verified":
        record = push_verified(root, arguments.remote)
        record_delivery(record)
        emit(record.to_document())
        return config.EXIT_OK if record.state == "PUSHED" else config.EXIT_BLOCKED
    if action == "delivery-report":
        emit(delivery_report())
        return config.EXIT_OK
    emit({"error": f"unknown git action: {action}"})
    return config.EXIT_USAGE


def command_run(arguments: argparse.Namespace) -> int:
    iterations = 0
    history: list[dict[str, Any]] = []
    maximum = arguments.max_iterations
    while iterations < maximum:
        iterations += 1
        manifest = build_inventory(config.repository_root())
        persist_inventory(manifest)
        initialize_from_manifest(manifest)
        gates = evaluate_gates()
        predicate = completion_predicate(gates)
        candidate = next_task()
        history.append(
            {
                "iteration": iterations,
                "ready_task": candidate.id if candidate else None,
                "production_ready": predicate["production_ready"],
                "failing_gates": predicate["failing_gates"],
            }
        )
        append_event(config.events_path(), "loop_iteration", history[-1])
        if candidate is None:
            break
        break
    emit({"iterations": iterations, "history": history, "note": "implementation is performed by the agent team; the harness refreshes state and selects work"})
    return config.EXIT_OK


def command_resume(arguments: argparse.Namespace) -> int:
    return command_run(arguments)


def command_status(arguments: argparse.Namespace) -> int:
    document = status_document()
    write_status_report()
    write_markdown_status()
    emit(document)
    return config.EXIT_OK


def command_report(arguments: argparse.Namespace) -> int:
    from ..reporting.generators import write_all_reports

    document = status_document()
    write_status_report()
    write_markdown_status()
    written = write_all_reports()
    emit({
        "report": str(config.reports_root() / "status.md"),
        "status": str(config.reports_root() / "status.json"),
        "generated": [str(path) for path in written],
        "production_ready": document["completion_predicate"]["production_ready"],
    })
    return config.EXIT_OK


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="harness", description="Petproject production engineering harness")
    subparsers = parser.add_subparsers(dest="command", required=True)

    inventory = subparsers.add_parser("inventory", help="build the first-party source manifest")
    inventory.set_defaults(handler=command_inventory)

    coverage = subparsers.add_parser("coverage", help="source coverage manifest")
    coverage.add_argument("--mark", help="path to mark as reviewed")
    coverage.add_argument("--state", default="FULLY_READ")
    coverage.add_argument("--reviewer", default="")
    coverage.add_argument("--notes", default="")
    coverage.add_argument("--import-reports", action="store_true", help="import the file inventories of the source review reports")
    coverage.add_argument("--mark-file", default="", help="mark every path listed in this file as reviewed")
    coverage.set_defaults(handler=command_coverage)

    audit = subparsers.add_parser("audit", help="audit evidence status for a topic")
    audit.add_argument("topic", choices=AUDIT_TOPICS)
    audit.set_defaults(handler=command_audit)

    research = subparsers.add_parser("research", help="research index management")
    research.add_argument("action", choices=("refresh",))
    research.set_defaults(handler=command_research)

    comments = subparsers.add_parser("comments", help="comment policy operations")
    comments_sub = comments.add_subparsers(dest="action", required=True)
    archive = comments_sub.add_parser("archive", help="archive removed rationale from the baseline revision")
    archive.add_argument("--revision", default="")
    archive.set_defaults(handler=command_comments_archive)
    scan = comments_sub.add_parser("scan", help="detect prohibited comments")
    scan.set_defaults(handler=command_comments_scan)
    clean = comments_sub.add_parser("clean", help="safely remove prohibited comments")
    clean.add_argument("--module", default="")
    clean.add_argument("--paths", nargs="*", default=[])
    clean.add_argument("--dry-run", action="store_true")
    clean.set_defaults(handler=command_comments_clean)

    verify = subparsers.add_parser("verify", help="evaluate verification gates")
    verify.add_argument("--suite", default="all", choices=("all", "comments"))
    verify.add_argument("--deep", action="store_true", help="prove every cleaned file equals clean(baseline)")
    verify.set_defaults(handler=command_verify)

    build = subparsers.add_parser("build", help="run the maven reactor build")
    build.add_argument("--quick", action="store_true", help="run validate only")
    build.add_argument("--modules", nargs="*", default=[])
    build.set_defaults(handler=command_build)

    test = subparsers.add_parser("test", help="run a test suite")
    test.add_argument("--suite", required=True, choices=sorted(load_suites().keys()))
    test.add_argument("--modules", nargs="*", default=[])
    test.set_defaults(handler=command_test)

    load = subparsers.add_parser("load", help="black friday workload profiles")
    load.add_argument(
        "action",
        choices=(
            "baseline",
            "ramp",
            "spike",
            "sustained-peak",
            "flash-sale",
            "promotion-storm",
            "soak",
            "recovery",
            "kafka-backlog",
            "failure-during-peak",
            "profiles",
            "invariants",
            "report",
            "reset-data",
        ),
    )
    load.add_argument("--scale", type=int, default=1)
    load.add_argument("--base-url", default="http://gateway-service:8080")
    load.add_argument("--persist", action="store_true")
    load.set_defaults(handler=command_load)

    stripe = subparsers.add_parser("stripe", help="real stripe sandbox verification")
    stripe.add_argument(
        "action",
        choices=(
            "verify-config",
            "sandbox-smoke",
            "test-payment",
            "test-webhooks",
            "reconcile",
            "acceptance",
            "webhook-setup",
        ),
    )
    stripe.set_defaults(handler=command_stripe)

    k8s = subparsers.add_parser("k8s", help="kubernetes manifest and cluster operations")
    k8s.add_argument(
        "action",
        choices=(
            "validate",
            "deploy-staging",
            "readiness",
            "hpa-status",
            "keda-status",
            "rollout-status",
        ),
    )
    k8s.set_defaults(handler=command_k8s)

    alerts = subparsers.add_parser("alerts", help="telegram alerting configuration and delivery verification")
    alerts.add_argument(
        "action",
        choices=(
            "validate",
            "test-ci",
            "test-runtime",
            "test-recovery",
            "test-dedup",
            "test-security",
            "status",
            "render",
        ),
    )
    alerts.set_defaults(handler=command_alerts)

    operations = subparsers.add_parser("operations", help="observability operations checks")
    operations.add_argument("action", choices=("check",))
    operations.set_defaults(handler=command_operations)

    e2e = subparsers.add_parser("e2e", help="end to end environment lifecycle")
    e2e.add_argument("action", choices=("setup", "run", "report", "teardown", "dependencies"))
    e2e.add_argument("--scope", default="all")
    e2e.add_argument("--volumes", action="store_true")
    e2e.set_defaults(handler=command_e2e)

    tasks = subparsers.add_parser("tasks", help="task registry operations")
    tasks.add_argument("action", choices=("list", "next", "status", "add", "transition", "import", "normalize"))
    tasks.add_argument("--id", default="")
    tasks.add_argument("--title", default="")
    tasks.add_argument("--severity", default="MEDIUM")
    tasks.add_argument("--priority", type=int, default=50)
    tasks.add_argument("--service", default="")
    tasks.add_argument("--evidence", nargs="*", default=[])
    tasks.add_argument("--pattern", default="")
    tasks.add_argument("--state", default="")
    tasks.add_argument("--file", default="")
    tasks.set_defaults(handler=command_tasks)

    findings = subparsers.add_parser("findings", help="findings ledger operations")
    findings.add_argument("action", choices=("import-report",))
    findings.add_argument("--file", default="")
    findings.add_argument("--output", default="")
    findings.add_argument("--topic", default="")
    findings.set_defaults(handler=command_findings)

    reconcile = subparsers.add_parser("reconcile", help="reconcile state with the current revision")
    reconcile.set_defaults(handler=command_reconcile)

    git = subparsers.add_parser("git", help="git delivery operations")
    git.add_argument("action", choices=("status", "commit-verified", "push-verified", "delivery-report"))
    git.add_argument("--message", default="")
    git.add_argument("--paths", nargs="*", default=[])
    git.add_argument("--allow-path", nargs="*", default=None)
    git.add_argument("--remote", default="origin")
    git.set_defaults(handler=command_git)

    run = subparsers.add_parser("run", help="autonomous engineering loop")
    run.add_argument("--max-iterations", type=int, default=20)
    run.set_defaults(handler=command_run)

    resume = subparsers.add_parser("resume", help="resume the engineering loop from persisted state")
    resume.add_argument("--max-iterations", type=int, default=20)
    resume.set_defaults(handler=command_resume)

    status = subparsers.add_parser("status", help="harness status")
    status.set_defaults(handler=command_status)

    report = subparsers.add_parser("report", help="write harness reports")
    report.set_defaults(handler=command_report)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    arguments = parser.parse_args(argv)
    try:
        return arguments.handler(arguments)
    except Exception as error:  # noqa: BLE001
        emit({"error": type(error).__name__, "message": str(error)}, as_json=True)
        return config.EXIT_BLOCKED


if __name__ == "__main__":
    sys.exit(main())
