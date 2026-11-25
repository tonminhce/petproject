from __future__ import annotations

import re
import subprocess
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable

from .. import config
from ..persistence.store import file_lock, read_json, utc_now, write_json

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


def next_suite_sequence(suite: str) -> int:
    safe = re.sub(r"[^a-zA-Z0-9_.-]", "_", suite)
    path = config.state_root() / (safe + "-sequence.json")
    with file_lock(path):
        document = read_json(path, {}) or {}
        value = document.get("sequence")
        sequence = value if type(value) is int else 0
        sequence += 1
        write_json(path, {"sequence": sequence})
    return sequence


def record_suite_result(suite: str, result: dict[str, Any], path: Any = None) -> dict[str, Any]:
    from ..inventory.scanner import harness_fingerprint, source_fingerprint

    target = path or config.verification_path()
    with file_lock(target):
        document = load_verification(target)
        suites = document.setdefault("suites", {})
        existing = suites.get(suite) or {}
        existing_sequence = int(existing.get("sequence", 0) or 0)
        new_sequence = int(result.get("sequence", 0) or 0) or existing_sequence
        if existing_sequence and new_sequence < existing_sequence:
            return document
        status_rank = {PENDING: 0, PASS: 1, BLOCKED: 2, FAIL: 3}
        if existing_sequence and new_sequence == existing_sequence:
            existing_rank = status_rank.get(str(existing.get("status", "")), -1)
            new_rank = status_rank.get(str(result.get("status", "")), -1)
            if existing_rank > new_rank:
                return document
        suites[suite] = {
            "updated_at": utc_now(),
            "source_fingerprint": source_fingerprint(),
            "harness_fingerprint": harness_fingerprint(),
            **result,
        }
        document["revision"] = result.get("revision", "")
        save_verification(document, target)
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
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
    except OSError:
        return "UNDECIDED"
    if not lines:
        return "UNDECIDED"
    first = lines[0]
    markers = [line.strip() for line in lines if line.strip().lower().startswith("verdict:")]
    if len(markers) != 1 or markers[0] != first:
        return "UNDECIDED"
    if first == "Verdict: PASS":
        return "PASS"
    if first == "Verdict: FAIL":
        return "FAIL"
    return "UNDECIDED"


def _review_candidate_revision(text: str) -> str:
    patterns = (
        r"Candidate reviewed:\s*HEAD\s*\**([0-9a-f]{7,40})",
        r"candidate(?: at)?\s+HEAD\s+([0-9a-f]{7,40})",
        r"HEAD\s+([0-9a-f]{7,40})",
    )
    for pattern in patterns:
        match = re.search(pattern, text, re.IGNORECASE)
        if match:
            return match.group(1)
    return ""


def _revision_is_ancestor(repository: Path, revision: str) -> bool:
    if not revision:
        return False
    try:
        result = subprocess.run(
            ["git", "-C", str(repository), "merge-base", "--is-ancestor", revision, "HEAD"],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return result.returncode == 0


def _head_revision(repository: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(repository), "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    return result.stdout.strip()


def _review_bindings_match(text: str, source: str, harness: str) -> bool:
    source_matches = re.findall(r"^Source fingerprint:\s*([0-9a-f]{64})\s*$", text, re.MULTILINE)
    harness_matches = re.findall(r"^Harness fingerprint:\s*([0-9a-f]{64})\s*$", text, re.MULTILINE)
    contradictory = re.findall(r"^(?:Source|Harness) fingerprint:.*$", text, re.MULTILINE)
    return (
        source_matches == [source]
        and harness_matches == [harness]
        and len(contradictory) == 2
    )


def _review_is_current(
    text: str,
    repository: Path,
    source_fingerprint_value: str,
    harness_fingerprint_value: str,
) -> bool:
    candidate = _review_candidate_revision(text)
    head = _head_revision(repository)
    if not _review_bindings_match(text, source_fingerprint_value, harness_fingerprint_value):
        return False
    return bool(candidate) and (candidate == head or _revision_is_ancestor(repository, candidate))


def _task_state_counts() -> dict[str, int]:
    from ..task_registry.registry import load_tasks, task_status_summary

    return task_status_summary(load_tasks())


def _payment_acceptance_evidence() -> dict[str, Any]:
    from ..stripe.acceptance import load_evidence

    return load_evidence()


def _payment_acceptance_verdict(acceptance: dict[str, Any]) -> dict[str, Any]:
    from ..stripe.acceptance import MATRIX_CASES

    server = acceptance.get("stripe_server") or {}
    summary = acceptance.get("summary") or {}
    cases = acceptance.get("cases") or []
    credential = acceptance.get("credential") or {}
    api_base = str(server.get("http_address_in_network", "") or "")
    public = bool(server.get("is_public_stripe_api")) or api_base.startswith("https://api.stripe.com")
    proven = len([case for case in cases if case.get("verdict") == "PROVEN"])
    outstanding = [str(case.get("name", "")) for case in cases if case.get("verdict") != "PROVEN"]
    expected_names = [case["name"] for case in MATRIX_CASES]
    recorded_names = [str(case.get("name", "")) for case in cases]
    matrix_complete = (
        len(recorded_names) == len(expected_names)
        and len(set(recorded_names)) == len(recorded_names)
        and set(recorded_names) == set(expected_names)
    )
    summary_complete = (
        int(summary.get("cases", 0) or 0) == len(expected_names)
        and int(summary.get("proven", 0) or 0) == len(expected_names)
        and int(summary.get("failed", 0) or 0) == 0
        and int(summary.get("not_proven", 0) or 0) == 0
    )
    probe = credential.get("credential_probe") or acceptance.get("credential_probe") or {}
    credential_proven = int(probe.get("status", 0) or 0) == 200 and probe.get("livemode") is False
    version = str(server.get("version", "") or "")
    evidence = [
        "payment acceptance cases: " + str(summary.get("cases", 0)) + " (" + str(proven) + " proven)",
        "stripe server under test: " + str(server.get("kind", "none")) + " " + version,
        "stripe api base of record: " + (api_base or "none"),
        "credential mode: " + str(credential.get("secret_key_mode", "none")),
    ]
    if not acceptance:
        return {
            "status": BLOCKED,
            "detail": "no Stripe payment acceptance evidence: the payment suite alone cannot certify the payment acceptance",
            "evidence": evidence,
        }
    if public and str(credential.get("secret_key_mode", "")) == "test" and not outstanding \
            and matrix_complete and summary_complete and credential_proven \
            and acceptance.get("verified_against_real_stripe_api") is True:
        return {
            "status": PASS,
            "detail": "the payment acceptance ran against api.stripe.com with a genuine test-mode key and every case was proven",
            "evidence": evidence,
        }
    if public:
        return {
            "status": BLOCKED,
            "detail": "the base address was api.stripe.com but the acceptance did not complete with a genuine test-mode key "
                      "and every case proven; outstanding: " + (", ".join(outstanding) or "none"),
            "evidence": evidence,
        }
    return {
        "status": BLOCKED,
        "detail": "the payment acceptance is NOT verified against the real Stripe: it ran against the official Stripe mock "
                  + version
                  + " ("
                  + str(proven)
                  + " of "
                  + str(summary.get("cases", 0))
                  + " cases proven; outstanding: "
                  + (", ".join(outstanding) or "none")
                  + "); a genuine test-mode key against api.stripe.com is still required",
        "evidence": evidence,
    }


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
    from ..inventory.scanner import harness_fingerprint, source_fingerprint

    fingerprint = source_fingerprint(repository)
    current_harness = harness_fingerprint(repository)
    stale_suites: list[str] = []

    def suite_status(name: str) -> str:
        entry = suites.get(name)
        if not entry:
            return PENDING
        if not evidence_is_fresh(entry, fingerprint) or entry.get("harness_fingerprint") != current_harness:
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
    payment_suite = suite_status("payment")
    acceptance = _payment_acceptance_evidence()
    stripe_suite = suites.get("stripe_acceptance") or {}
    expected_acceptance_path = str(config.run_root() / "stripe-acceptance" / "acceptance.json")
    acceptance_fresh = (
        acceptance.get("source_fingerprint") == fingerprint
        and acceptance.get("harness_fingerprint") == current_harness
        and _revision_is_ancestor(repository, str(acceptance.get("revision") or ""))
        and stripe_suite.get("status") == PASS
        and stripe_suite.get("source_fingerprint") == fingerprint
        and stripe_suite.get("harness_fingerprint") == current_harness
        and _revision_is_ancestor(repository, str(stripe_suite.get("revision") or ""))
        and stripe_suite.get("evidence") == expected_acceptance_path
    )
    if acceptance_fresh:
        acceptance_verdict = _payment_acceptance_verdict(acceptance)
    else:
        acceptance_verdict = {
            "status": BLOCKED,
            "detail": "the Stripe acceptance evidence is stale or missing source, harness or revision binding",
            "evidence": [
                "acceptance source fingerprint current: " + str(acceptance.get("source_fingerprint") == fingerprint),
                "acceptance harness fingerprint current: " + str(acceptance.get("harness_fingerprint") == current_harness),
                "acceptance revision contained in HEAD: " + str(_revision_is_ancestor(repository, str(acceptance.get("revision") or ""))),
            ],
        }
    if payment_suite == FAIL:
        payment_status = FAIL
    elif acceptance_verdict["status"] == PASS:
        payment_status = payment_suite
    else:
        payment_status = acceptance_verdict["status"]
    gates.append(
        Gate(
            id="GATE-6",
            name="PAYMENT_CORRECTNESS",
            status=payment_status,
            evidence=[f"suite payment: {payment_suite}"] + acceptance_verdict["evidence"],
            detail=acceptance_verdict["detail"] + "; the payment suite reports " + payment_suite,
        )
    )
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
    build_fresh = evidence_is_fresh(build_suite, fingerprint) and build_suite.get("harness_fingerprint") == current_harness
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
    from ..e2e.environment import e2e_report

    try:
        e2e_evidence = e2e_report()
    except Exception as error:
        e2e_evidence = {
            "e2e_verified": False,
            "evaluated_artifacts": [],
            "executed_collections": 0,
            "malformed_artifacts": [{"file": "e2e-report", "reason": str(error)}],
        }
    e2e_status = PASS if e2e_evidence.get("e2e_verified") and not e2e_evidence.get("malformed_artifacts") else FAIL
    gates.append(
        Gate(
            id="GATE-16",
            name="REAL_E2E",
            status=e2e_status,
            evidence=[
                "evaluated artifacts: " + (", ".join(e2e_evidence.get("evaluated_artifacts", [])) or "none"),
                "executed collections: " + str(e2e_evidence.get("executed_collections", 0)),
                "malformed artifacts: " + str(len(e2e_evidence.get("malformed_artifacts", []))),
                "stale completions ignored: " + str(e2e_evidence.get("ignored_stale_completed", 0)),
            ],
            detail="current end to end artifacts were revalidated" if e2e_status == PASS else "current end to end artifacts are missing, stale, malformed or failed",
        )
    )
    from ..loadtesting.capacity import sweep_verdict

    performance_report = sweep_verdict()
    gates.append(
        Gate(
            id="GATE-17",
            name="PERFORMANCE",
            status=performance_report["status"],
            evidence=[
                "configured profiles: " + str(len(performance_report["configured_profiles"])),
                "missing: " + (", ".join(performance_report["missing"]) or "none"),
                "blocked: " + (", ".join(performance_report["blocked"]) or "none"),
                "failed: " + (", ".join(performance_report["failed"]) or "none"),
                "stale: " + (", ".join(performance_report["stale"]) or "none"),
                "capacity binding: " + str(performance_report["capacity_binds_to_current_source"]),
            ],
            detail="the canonical newest result of every configured load profile is current and acceptable"
            if performance_report["status"] == PASS
            else "the canonical load sweep does not satisfy every configured profile",
        )
    )
    gates.append(Gate(id="GATE-18", name="OBSERVABILITY_OPERATIONS", status=suite_status("operations"), evidence=[f"suite operations: {suite_status('operations')}"]))
    gates.append(Gate(id="GATE-23", name="ALERTING_CONFIGURATION", status=suite_status("alerts"), evidence=[f"suite alerts: {suite_status('alerts')}"]))
    from ..kubernetes.validation import load_autoscaling_observation

    observation = load_autoscaling_observation()
    observation_status = str(observation.get("status", BLOCKED))
    observation_reason = str(observation.get("reason", "no autoscaling observation is recorded"))
    manifests_status = suite_status("k8s")
    if observation_status == FAIL:
        kubernetes_status = FAIL
        kubernetes_detail = "the recorded autoscaling observation does not prove the behaviour of the autoscaler"
    elif observation_status == BLOCKED:
        kubernetes_status = BLOCKED
        kubernetes_detail = "the manifests validate but no autoscaling observation of a real cluster is recorded"
    else:
        kubernetes_status = manifests_status
        kubernetes_detail = "the manifests validate and the autoscaler was observed on a real cluster"
    gates.append(
        Gate(
            id="GATE-24",
            name="KUBERNETES_MANIFESTS",
            status=kubernetes_status,
            evidence=[
                f"suite k8s: {manifests_status}",
                f"autoscaling observation: {observation_status}",
                observation_reason,
            ],
            detail=kubernetes_detail,
        )
    )
    gates.append(
        Gate(
            id="GATE-25",
            name="AUTOSCALING_OBSERVATION",
            status=observation_status,
            evidence=[str(item) for item in observation.get("evidence", [])] or [observation_reason],
            detail=observation_reason,
        )
    )

    reviews_root = config.run_root() / "independent-reviews"
    reviews = list(reviews_root.glob("*.md")) if reviews_root.exists() else []
    failing_reviews: list[Path] = []
    passing_reviews: list[Path] = []
    undecided_reviews: list[Path] = []
    for review in reviews:
        verdict = _review_verdict(review)
        if verdict == "FAIL":
            failing_reviews.append(review)
            continue
        try:
            text = review.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            undecided_reviews.append(review)
            continue
        if verdict == "PASS" and _review_is_current(text, repository, fingerprint, current_harness):
            passing_reviews.append(review)
        else:
            undecided_reviews.append(review)
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
    revision_is_ancestor = bool(manifest_revision) and subprocess.run(
        ["git", "-C", str(repository), "merge-base", "--is-ancestor", manifest_revision, head_revision],
        capture_output=True, text=True, check=False,
    ).returncode == 0
    tree_matches = bool(manifest_fingerprint) and manifest_fingerprint == fingerprint
    current = (revision_matches or revision_is_ancestor) and tree_matches
    gates.append(
        Gate(
            id="GATE-22",
            name="FINAL_RECONCILIATION",
            status=PASS if current and manifest_total > 0 else PENDING,
            evidence=[
                f"manifest revision={manifest_revision}",
                f"HEAD={head_revision}",
                f"manifest revision is contained in HEAD={revision_matches or revision_is_ancestor}",
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
        "GATE-25",
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
