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

    matches = re.findall(
        r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+),\s*Skipped:\s*(\d+)(?![^\n]*-- in)",
        stdout,
    )
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
    from ..inventory.scanner import source_fingerprint

    document = load_verification(path)
    suites = document.setdefault("suites", {})
    suites[suite] = {
        "updated_at": utc_now(),
        "source_fingerprint": source_fingerprint(),
        **result,
    }
    document["revision"] = result.get("revision", "")
    save_verification(document, path)
    return document


def evidence_is_fresh(entry: dict[str, Any], fingerprint: str) -> bool:
    return bool(entry.get("source_fingerprint")) and entry.get("source_fingerprint") == fingerprint


def _coverage_summary() -> dict[str, Any]:
    document = read_json(config.source_coverage_path(), {}) or {}
    files = document.get("files", {})
    total = len(files)
    fully = sum(1 for entry in files.values() if entry.get("coverage_state") in ("FULLY_READ", "SEMANTICALLY_REVIEWED", "DEEP_TRACED", "CROSS_REVIEWED", "RUNTIME_VERIFIED"))
    return {"total": total, "reviewed": fully}


def _review_verdict(path: Path) -> str:
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return "UNDECIDED"
    for line in text.splitlines():
        stripped = line.strip().lower()
        if not stripped.startswith("verdict") and "verdict" not in stripped.split(":")[0]:
            continue
        if "fail" in stripped:
            return "FAIL"
        if "pass" in stripped or "approved" in stripped:
            return "PASS"
    for line in text.splitlines():
        stripped = line.strip().lower()
        if stripped.startswith("## verdict") or stripped.startswith("### verdict"):
            if "fail" in stripped:
                return "FAIL"
            if "pass" in stripped:
                return "PASS"
    return "UNDECIDED"


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
    pattern_required = [task for task in pattern_tasks if task.state != "VERIFIED"]
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
    from ..inventory.scanner import source_fingerprint

    fingerprint = source_fingerprint(repository)
    stale_suites: list[str] = []

    def suite_status(name: str) -> str:
        entry = suites.get(name)
        if not entry:
            return PENDING
        if not evidence_is_fresh(entry, fingerprint):
            if name not in stale_suites:
                stale_suites.append(name)
            return PENDING
        if entry.get("status") == PASS:
            tests_run = entry.get("tests_run")
            if tests_run is not None and int(tests_run) == 0:
                return INSUFFICIENT_EVIDENCE
            return PASS
        if entry.get("status") == FAIL:
            return FAIL
        if entry.get("status") == "BLOCKED":
            return "BLOCKED"
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
    build_recorded = build_suite.get("status") in (PASS, "PASSED")
    build_fresh = evidence_is_fresh(build_suite, fingerprint)
    build_pass = build_recorded and build_fresh
    gates.append(
        Gate(
            id="GATE-12",
            name="BUILD",
            status=PASS if build_pass else PENDING,
            evidence=[
                f"build exit={build_suite.get('exit_code', 'n/a')}",
                f"build evidence fresh={build_fresh}",
            ],
            detail="full reactor build succeeded" if build_pass else ("reactor build evidence is stale" if build_recorded else "reactor build not yet verified"),
        )
    )

    gates.append(Gate(id="GATE-13", name="HAPPY_PATH_TESTS", status=suite_status("happy"), evidence=[f"suite happy: {suite_status('happy')}"]))
    gates.append(Gate(id="GATE-14", name="EDGE_NEGATIVE_TESTS", status=suite_status("edge"), evidence=[f"suite edge: {suite_status('edge')}"]))
    gates.append(Gate(id="GATE-15", name="CONCURRENCY", status=suite_status("concurrency"), evidence=[f"suite concurrency: {suite_status('concurrency')}"]))
    gates.append(Gate(id="GATE-16", name="REAL_E2E", status=suite_status("e2e"), evidence=[f"suite e2e: {suite_status('e2e')}"]))
    gates.append(Gate(id="GATE-17", name="PERFORMANCE", status=suite_status("performance"), evidence=[f"suite performance: {suite_status('performance')}"]))
    gates.append(Gate(id="GATE-18", name="OBSERVABILITY_OPERATIONS", status=suite_status("operations"), evidence=[f"suite operations: {suite_status('operations')}"]))
    gates.append(Gate(id="GATE-23", name="ALERTING_CONFIGURATION", status=suite_status("alerts"), evidence=[f"suite alerts: {suite_status('alerts')}"]))
    gates.append(Gate(id="GATE-24", name="KUBERNETES_MANIFESTS", status=suite_status("k8s"), evidence=[f"suite k8s: {suite_status('k8s')}"]))

    reviews_root = config.run_root() / "independent-reviews"
    reviews = list(reviews_root.glob("*.md")) if reviews_root.exists() else []
    review_verdicts = [(review, _review_verdict(review)) for review in reviews]
    failing_reviews = [review for review, verdict in review_verdicts if verdict == "FAIL"]
    passing_reviews = [review for review, verdict in review_verdicts if verdict == "PASS"]
    undecided_reviews = [review for review, verdict in review_verdicts if verdict == "UNDECIDED"]
    if not reviews:
        review_status = PENDING
        review_detail = "no independent review artifacts"
    elif failing_reviews:
        review_status = FAIL
        review_detail = str(len(failing_reviews)) + " of " + str(len(reviews)) + " independent reviews report a failing verdict"
    elif undecided_reviews:
        review_status = PENDING
        review_detail = str(len(undecided_reviews)) + " independent reviews carry no verdict marker"
    else:
        review_status = PASS
        review_detail = str(len(passing_reviews)) + " independent reviews report a passing verdict"
    gates.append(
        Gate(
            id="GATE-19",
            name="INDEPENDENT_REVIEW",
            status=review_status,
            evidence=[
                f"{len(reviews)} independent review artifacts",
                f"passing verdicts: {len(passing_reviews)}",
                f"failing verdicts: {len(failing_reviews)}",
                f"without a verdict: {len(undecided_reviews)}",
            ],
            detail=review_detail,
        )
    )

    from ..git_delivery.delivery import delivery_report

    deliveries = delivery_report()
    rejected = len(deliveries.get("rejected", []))
    from ..git_delivery.delivery import repository_status

    status = repository_status(repository)
    branch = status.get("branch", "")
    protected = bool(status.get("protected"))
    commit = status.get("commit", "")
    ahead_behind = str(status.get("ahead_behind", "")).strip()
    unpushed = "no upstream"
    if ahead_behind and "no upstream" not in ahead_behind:
        parts = ahead_behind.split()
        if len(parts) == 2:
            unpushed = parts[0]
    pushed = commit and not protected and unpushed in ("0", "0", "")
    if protected:
        delivery_status = FAIL
        delivery_detail = "the branch is protected, so the change would have been delivered to a branch that must not receive it"
    elif not commit:
        delivery_status = PENDING
        delivery_detail = "no commit exists"
    elif unpushed == "no upstream":
        delivery_status = PENDING
        delivery_detail = "the branch has no upstream, so the commit cannot be shown to be delivered"
    elif unpushed != "0":
        delivery_status = FAIL
        delivery_detail = str(unpushed) + " commits are not on the remote branch"
    else:
        delivery_status = PASS
        delivery_detail = "the current commit is on the remote branch " + branch
    gates.append(
        Gate(
            id="GATE-20",
            name="GIT_DELIVERY",
            status=delivery_status,
            evidence=[
                f"branch: {branch}",
                f"commit: {commit[:12]}",
                f"commits ahead of the remote: {unpushed}",
                f"protected branch: {protected}",
                f"{deliveries['total']} delivery records, {rejected} of them rejected by the gates",
            ],
            detail=delivery_detail,
        )
    )

    blocking = [
        task
        for task in tasks.values()
        if task.severity in ("CRITICAL", "HIGH") and task.state not in ("VERIFIED",)
    ]
    gates.append(
        Gate(
            id="GATE-21",
            name="ZERO_RELEASE_BLOCKERS",
            status=PASS if tasks and not blocking else (PENDING if not tasks else FAIL),
            evidence=[f"{len(blocking)} unresolved CRITICAL or HIGH tasks"],
            detail="no unresolved release blocker" if tasks and not blocking else "release blockers remain",
        )
    )

    manifest_revision = manifest.get("revision", "")
    head_revision = subprocess.run(["git", "-C", str(repository), "rev-parse", "HEAD"], capture_output=True, text=True, check=False).stdout.strip()
    manifest_fingerprint = manifest.get("source_fingerprint", "")
    revision_matches = bool(manifest_revision) and manifest_revision == head_revision
    tree_matches = bool(manifest_fingerprint) and manifest_fingerprint == fingerprint
    current = revision_matches and tree_matches
    gates.append(
        Gate(
            id="GATE-22",
            name="FINAL_RECONCILIATION",
            status=PASS if current and manifest_total > 0 else PENDING,
            evidence=[
                f"manifest revision={manifest_revision}",
                f"HEAD={head_revision}",
                f"manifest fingerprint matches working tree={tree_matches}",
            ],
            detail="evidence matches current revision" if current else "evidence does not match the current revision and working tree",
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
        "GATE-23",
        "GATE-24",
    ]
    gate_map = {gate.id: gate for gate in gates}
    missing = [gate_id for gate_id in required if gate_id not in gate_map]
    results = {gate_id: gate_map[gate_id].status == PASS for gate_id in required if gate_id in gate_map}
    production_ready = bool(results) and not missing and all(results.values())
    return {
        "evaluated_at": utc_now(),
        "production_ready": production_ready,
        "gate_results": results,
        "missing_gates": missing,
        "failing_gates": [gate_id for gate_id, value in results.items() if not value],
        "note": "git delivery status is reported separately from technical readiness",
    }
