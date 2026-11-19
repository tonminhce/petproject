from __future__ import annotations

import subprocess
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable

from .. import config
from ..persistence.store import read_json, utc_now, write_json

PASS = "PASS"
FAIL = "FAIL"
BLOCKED = "BLOCKED"
INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
PENDING = "PENDING"


@dataclass
class Gate:
    id: str
    name: str
    status: str = PENDING
    evidence: list[str] = field(default_factory=list)
    detail: str = ""

    def to_document(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class CommandResult:
    command: list[str]
    exit_code: int
    duration_seconds: float
    stdout_tail: str
    stderr_tail: str
    log_path: str = ""

    def to_document(self) -> dict[str, Any]:
        return asdict(self)


def run_command(command: list[str], cwd: Path, log_path: Path | None = None, timeout: int = 3600) -> CommandResult:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        exit_code = completed.returncode
        stdout = completed.stdout
        stderr = completed.stderr
    except subprocess.TimeoutExpired as error:
        exit_code = 124
        stdout = (error.stdout or b"").decode("utf-8", errors="replace") if isinstance(error.stdout, bytes) else (error.stdout or "")
        stderr = f"timeout after {timeout}s"
    except OSError as error:
        exit_code = 127
        stdout = ""
        stderr = str(error)
    duration = time.monotonic() - started

    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as handle:
            handle.write(f"$ {' '.join(command)}\n")
            handle.write(f"exit={exit_code} duration={duration:.2f}s\n\n")
            handle.write(stdout)
            handle.write("\n")
            handle.write(stderr)

    return CommandResult(
        command=command,
        exit_code=exit_code,
        duration_seconds=round(duration, 3),
        stdout_tail=stdout[-4000:],
        stderr_tail=stderr[-4000:],
        log_path=str(log_path) if log_path else "",
    )


def interpret_test_exit(exit_code: int, stdout: str) -> dict[str, Any]:
    tests_run = 0
    failures = 0
    errors = 0
    skipped = 0
    import re

    matches = re.findall(r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)", stdout)
    for run, fail, err, skip in matches:
        tests_run += int(run)
        failures += int(fail)
        errors += int(err)
        skipped += int(skip)
    return {
        "exit_code": exit_code,
        "tests_run": tests_run,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
        "status": PASS if exit_code == 0 else FAIL,
    }


def load_verification(path: Any = None) -> dict[str, Any]:
    return read_json(path or config.verification_path(), {}) or {}


def save_verification(document: dict[str, Any], path: Any = None) -> Path:
    document["updated_at"] = utc_now()
    return write_json(path or config.verification_path(), document)


def record_suite_result(suite: str, result: dict[str, Any], path: Any = None) -> dict[str, Any]:
    document = load_verification(path)
    suites = document.setdefault("suites", {})
    suites[suite] = {
        "updated_at": utc_now(),
        **result,
    }
    document["revision"] = result.get("revision", "")
    save_verification(document, path)
    return document


def _coverage_summary() -> dict[str, Any]:
    document = read_json(config.source_coverage_path(), {}) or {}
    files = document.get("files", {})
    total = len(files)
    fully = sum(1 for entry in files.values() if entry.get("coverage_state") in ("FULLY_READ", "SEMANTICALLY_REVIEWED", "DEEP_TRACED", "CROSS_REVIEWED", "RUNTIME_VERIFIED"))
    return {"total": total, "reviewed": fully}


def _task_state_counts() -> dict[str, int]:
    from ..task_registry.registry import load_tasks, task_status_summary

    return task_status_summary(load_tasks())


def evaluate_gates(root: Path | None = None) -> list[Gate]:
    repository = root or config.repository_root()
    gates: list[Gate] = []

    coverage = _coverage_summary()
    manifest = read_json(config.source_manifest_path(), {}) or {}
    manifest_total = manifest.get("file_count", 0)
    complete = manifest_total > 0 and coverage["reviewed"] >= manifest_total
    gates.append(
        Gate(
            id="GATE-1",
            name="SOURCE_COVERAGE",
            status=PASS if complete else (PENDING if manifest_total == 0 else FAIL),
            evidence=[f"control/source-manifest.json: {manifest_total} files", f"control/source-coverage.json: {coverage['reviewed']} reviewed"],
            detail="all in-scope first-party source reviewed" if complete else f"{manifest_total - coverage['reviewed']} files not yet fully reviewed",
        )
    )

    architecture_doc = config.architecture_root() / "current.md"
    target_doc = config.architecture_root() / "target.md"
    architecture_ready = architecture_doc.exists() and target_doc.exists() and architecture_doc.stat().st_size > 500
    gates.append(
        Gate(
            id="GATE-2",
            name="SYSTEM_DESIGN",
            status=PASS if architecture_ready else PENDING,
            evidence=["production-audit/architecture/current.md", "production-audit/architecture/target.md"],
            detail="current and target architecture recorded" if architecture_ready else "architecture documents missing",
        )
    )

    from ..task_registry.registry import load_tasks

    tasks = load_tasks()
    pattern_tasks = [task for task in tasks.values() if task.design_pattern]
    pattern_verified = [task for task in pattern_tasks if task.state == "VERIFIED"]
    pattern_required = [task for task in pattern_tasks if task.state not in ("VERIFIED", "BLOCKED", "DISCOVERED")]
    gates.append(
        Gate(
            id="GATE-3",
            name="DESIGN_PATTERNS",
            status=PASS if pattern_tasks and not pattern_required else (PENDING if not pattern_tasks else FAIL),
            evidence=[f"{len(pattern_verified)}/{len(pattern_tasks)} pattern tasks verified"],
            detail="all selected pattern refactorings verified" if pattern_tasks and not pattern_required else "pattern refactorings incomplete",
        )
    )

    verification = load_verification()
    suites = verification.get("suites", {})

    def suite_status(name: str) -> str:
        entry = suites.get(name)
        if not entry:
            return PENDING
        if entry.get("status") == PASS:
            return PASS
        if entry.get("status") == FAIL:
            return FAIL
        return INSUFFICIENT_EVIDENCE

    gates.append(Gate(id="GATE-4", name="BUSINESS_CORRECTNESS", status=suite_status("happy"), evidence=[f"suite happy: {suite_status('happy')}"]))
    gates.append(Gate(id="GATE-5", name="INVENTORY_SAFETY", status=suite_status("concurrency_inventory"), evidence=[f"suite concurrency_inventory: {suite_status('concurrency_inventory')}"]))
    gates.append(Gate(id="GATE-6", name="PAYMENT_CORRECTNESS", status=suite_status("payment"), evidence=[f"suite payment: {suite_status('payment')}"]))
    gates.append(Gate(id="GATE-7", name="KAFKA_OUTBOX", status=suite_status("kafka"), evidence=[f"suite kafka: {suite_status('kafka')}"]))
    gates.append(Gate(id="GATE-8", name="REDIS_GATEWAY", status=suite_status("gateway"), evidence=[f"suite gateway: {suite_status('gateway')}"]))
    gates.append(Gate(id="GATE-9", name="DATABASE_INTEGRITY", status=suite_status("integration"), evidence=[f"suite integration: {suite_status('integration')}"]))
    gates.append(Gate(id="GATE-10", name="SECURITY", status=suite_status("security"), evidence=[f"suite security: {suite_status('security')}"]))

    from ..source_policy.scanner import scan_repository

    comment_report = scan_repository(repository)
    comment_pass = comment_report["prohibited_comments"] == 0 and comment_report["scanned_files"] > 0
    gates.append(
        Gate(
            id="GATE-11",
            name="NO_COMMENT_COMPLIANCE",
            status=PASS if comment_pass else FAIL,
            evidence=[f"{comment_report['scanned_files']} files scanned", f"{comment_report['prohibited_comments']} prohibited comments"],
            detail="no prohibited first-party comments remain" if comment_pass else "prohibited comments remain",
        )
    )

    build_suite = suites.get("build", {})
    build_pass = build_suite.get("status") in (PASS, "PASSED")
    gates.append(
        Gate(
            id="GATE-12",
            name="BUILD",
            status=PASS if build_pass else PENDING,
            evidence=[f"build exit={build_suite.get('exit_code', 'n/a')}"],
            detail="full reactor build succeeded" if build_pass else "reactor build not yet verified",
        )
    )

    gates.append(Gate(id="GATE-13", name="HAPPY_PATH_TESTS", status=suite_status("happy"), evidence=[f"suite happy: {suite_status('happy')}"]))
    gates.append(Gate(id="GATE-14", name="EDGE_NEGATIVE_TESTS", status=suite_status("edge"), evidence=[f"suite edge: {suite_status('edge')}"]))
    gates.append(Gate(id="GATE-15", name="CONCURRENCY", status=suite_status("concurrency"), evidence=[f"suite concurrency: {suite_status('concurrency')}"]))
    gates.append(Gate(id="GATE-16", name="REAL_E2E", status=suite_status("e2e"), evidence=[f"suite e2e: {suite_status('e2e')}"]))
    gates.append(Gate(id="GATE-17", name="PERFORMANCE", status=suite_status("performance"), evidence=[f"suite performance: {suite_status('performance')}"]))
    gates.append(Gate(id="GATE-18", name="OBSERVABILITY_OPERATIONS", status=suite_status("operations"), evidence=[f"suite operations: {suite_status('operations')}"]))

    reviews_root = config.run_root() / "independent-reviews"
    reviews = list(reviews_root.glob("*.md")) if reviews_root.exists() else []
    reviews_pass = len(reviews) > 0
    gates.append(
        Gate(
            id="GATE-19",
            name="INDEPENDENT_REVIEW",
            status=PASS if reviews_pass else PENDING,
            evidence=[f"{len(reviews)} independent review artifacts"],
            detail="independent review artifacts present" if reviews_pass else "no independent review artifacts",
        )
    )

    from ..git_delivery.delivery import delivery_report

    deliveries = delivery_report()
    has_commit = deliveries["total"] > 0
    gates.append(
        Gate(
            id="GATE-20",
            name="GIT_DELIVERY",
            status=PASS if has_commit else PENDING,
            evidence=[f"{deliveries['total']} delivery records", f"unpushed: {len(deliveries['unpushed'])}"],
            detail="verified batches have traceable commits" if has_commit else "no delivery records",
        )
    )

    blocking = [task for task in tasks.values() if task.severity == "CRITICAL" and task.state not in ("VERIFIED",)]
    gates.append(
        Gate(
            id="GATE-21",
            name="ZERO_RELEASE_BLOCKERS",
            status=PASS if tasks and not blocking else (PENDING if not tasks else FAIL),
            evidence=[f"{len(blocking)} unresolved CRITICAL tasks"],
            detail="no unresolved release blocker" if tasks and not blocking else "release blockers remain",
        )
    )

    manifest_revision = manifest.get("revision", "")
    head_revision = subprocess.run(["git", "-C", str(repository), "rev-parse", "HEAD"], capture_output=True, text=True, check=False).stdout.strip()
    current = bool(manifest_revision) and manifest_revision == head_revision
    gates.append(
        Gate(
            id="GATE-22",
            name="FINAL_RECONCILIATION",
            status=PASS if current and manifest_total > 0 else PENDING,
            evidence=[f"manifest revision={manifest_revision}", f"HEAD={head_revision}"],
            detail="evidence matches current revision" if current else "evidence does not match current revision",
        )
    )

    return gates


def completion_predicate(gates: list[Gate]) -> dict[str, Any]:
    required = [
        "GATE-1",
        "GATE-2",
        "GATE-3",
        "GATE-4",
        "GATE-5",
        "GATE-6",
        "GATE-7",
        "GATE-8",
        "GATE-9",
        "GATE-10",
        "GATE-11",
        "GATE-12",
        "GATE-13",
        "GATE-14",
        "GATE-15",
        "GATE-16",
        "GATE-17",
        "GATE-18",
        "GATE-19",
        "GATE-20",
        "GATE-21",
        "GATE-22",
    ]
    gate_map = {gate.id: gate for gate in gates}
    results = {gate_id: gate_map[gate_id].status == PASS for gate_id in required if gate_id in gate_map}
    production_ready = bool(results) and all(results.values())
    return {
        "evaluated_at": utc_now(),
        "production_ready": production_ready,
        "gate_results": results,
        "failing_gates": [gate_id for gate_id, value in results.items() if not value],
        "note": "git delivery status is reported separately from technical readiness",
    }
