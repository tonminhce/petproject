from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import time
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from .. import config
from ..inventory.scanner import harness_fingerprint, source_fingerprint
from ..persistence.store import file_lock, read_json, sha256_file, utc_now, write_json
from ..reconciliation.reconcile import current_revision
from .postman_environment import write_postman_environment
from .readiness import wait_for_healthy
from ..verification.gates import run_command

REQUIRED_COMPONENTS = {
    "postgres": ["docker", "compose"],
    "redis": ["docker", "compose"],
    "kafka": ["docker", "compose"],
    "elasticsearch": ["docker", "compose"],
    "keycloak": ["docker", "compose"],
    "rustfs": ["docker", "compose"],
}

SCOPES = ("auth", "catalog", "checkout", "inventory", "payment", "shipping", "ratings", "refund", "media", "gateway", "kafka", "lifecycle")

COMPOSE_PROBE = ["docker", "compose", "version", "--short"]

SCOPE_FOLDERS: dict[str, tuple[str, ...]] = {
    "auth": ("1. Authentication",),
    "catalog": ("2. Catalog & Products",),
    "inventory": ("3. Inventory",),
    "checkout": ("4. Cart & Order",),
    "payment": ("6. Payment (Multi-Gateway)",),
    "shipping": ("7. Order Fulfillment Lifecycle",),
    "ratings": ("8. Rating & Favourite",),
    "refund": ("9. RMA Order Returns Workflow",),
    "media": ("19. Edge Cases - Rating & Order & Media",),
    "gateway": ("12. Gateway E2E Routing",),
    "kafka": ("10. Search & Notification",),
    "lifecycle": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
        "6. Payment (Multi-Gateway)",
        "7. Order Fulfillment Lifecycle",
        "8. Rating & Favourite",
        "9. RMA Order Returns Workflow",
        "10. Search & Notification",
        "11. Tax & Promotion",
    ),
}

SCOPE_PREREQUISITES: dict[str, tuple[str, ...]] = {
    "auth": (),
    "catalog": ("1. Authentication",),
    "inventory": ("1. Authentication", "2. Catalog & Products"),
    "checkout": ("1. Authentication", "2. Catalog & Products", "3. Inventory"),
    "payment": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
    ),
    "shipping": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
        "6. Payment (Multi-Gateway)",
    ),
    "ratings": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
        "6. Payment (Multi-Gateway)",
        "7. Order Fulfillment Lifecycle",
    ),
    "refund": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
        "6. Payment (Multi-Gateway)",
        "7. Order Fulfillment Lifecycle",
    ),
    "media": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
        "6. Payment (Multi-Gateway)",
        "7. Order Fulfillment Lifecycle",
    ),
    "gateway": (
        "1. Authentication",
        "2. Catalog & Products",
        "3. Inventory",
        "4. Cart & Order",
    ),
    "kafka": (
        "1. Authentication",
        "2. Catalog & Products",
    ),
    "lifecycle": (),
}


@dataclass
class ComponentStatus:
    name: str
    available: bool
    detail: str

    def to_document(self) -> dict[str, Any]:
        return {"name": self.name, "available": self.available, "detail": self.detail}


def compose_available() -> tuple[bool, str]:
    if shutil.which("docker") is None:
        return False, "docker binary not on PATH"
    probe = subprocess.run(COMPOSE_PROBE, capture_output=True, text=True, check=False, timeout=60)
    if probe.returncode != 0:
        return False, f"docker compose plugin unavailable: {probe.stderr.strip()[:200]}"
    return True, f"docker compose {probe.stdout.strip()}"


def detect_dependencies() -> list[ComponentStatus]:
    statuses: list[ComponentStatus] = []
    compose_ok, compose_detail = compose_available()
    running = _running_containers()
    for name in REQUIRED_COMPONENTS:
        if not compose_ok:
            statuses.append(ComponentStatus(name, False, compose_detail))
            continue
        matches = sorted(container for container in running if name in container)
        if not matches:
            statuses.append(ComponentStatus(name, False, "no running container carries the name " + name))
            continue
        statuses.append(ComponentStatus(name, True, "running: " + ", ".join(matches)))
    return statuses


def _running_containers() -> list[str]:
    try:
        probe = subprocess.run(
            ["docker", "ps", "--format", "{{.Names}}|{{.Status}}"],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    if probe.returncode != 0:
        return []
    containers: list[str] = []
    for line in probe.stdout.splitlines():
        if "|" not in line:
            continue
        name, _, status = line.partition("|")
        if "Up" not in status:
            continue
        containers.append(name.strip())
    return containers


def compose_files(root: Path | None = None) -> list[Path]:
    repository = root or config.repository_root()
    return [repository / "docker-compose.yml"]


def e2e_setup(root: Path | None = None, profile: str = "default", run_id: str | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    evidence = _e2e_evidence(repository)
    statuses = detect_dependencies()
    unavailable = [status for status in statuses if not status.available]

    if unavailable:
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "reason": "required e2e infrastructure unavailable",
            "components": [status.to_document() for status in statuses],
            "unavailable": [status.name for status in unavailable],
            **evidence,
        }
        write_json(log_dir / "setup.json", document)
        return document

    command = ["docker", "compose", "-f", str(repository / "docker-compose.yml"), "up", "-d"]
    result = run_command(command, repository, log_dir / "compose-up.log")
    evidence = _e2e_evidence(repository)
    document = {
        "generated_at": utc_now(),
        "state": "READY" if result.exit_code == 0 else "FAILED",
        "components": [status.to_document() for status in statuses],
        "compose_exit_code": result.exit_code,
        "compose_log": str(log_dir / "compose-up.log"),
        **evidence,
    }
    write_json(log_dir / "setup.json", document)
    return document


def e2e_teardown(root: Path | None = None, run_id: str | None = None, volumes: bool = False) -> dict[str, Any]:
    repository = root or config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    command = ["docker", "compose", "-f", str(repository / "docker-compose.yml"), "down"]
    if volumes:
        command.append("-v")
    result = run_command(command, repository, log_dir / "compose-down.log")
    document = {
        "generated_at": utc_now(),
        "state": "TORN_DOWN" if result.exit_code == 0 else "FAILED",
        "exit_code": result.exit_code,
        "log": str(log_dir / "compose-down.log"),
    }
    (log_dir / "postman-environment.json").unlink(missing_ok=True)
    write_json(log_dir / "teardown.json", document)
    return document


def collection_folder_names(collection: Path) -> tuple[str, ...]:
    try:
        document = json.loads(collection.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return ()
    names: list[str] = []

    def walk(items: list[dict[str, Any]]) -> None:
        for item in items:
            children = item.get("item")
            if isinstance(children, list):
                name = item.get("name")
                if isinstance(name, str):
                    names.append(name)
                walk(children)

    walk(document.get("item", []))
    return tuple(names)


def discover_collections(root: Path | None = None) -> list[Path]:
    repository = root or config.repository_root()
    candidates: list[Path] = []
    for base in (repository / "tests", repository / "load-tests", repository / "docs" / "postman"):
        if base.exists():
            candidates.extend(sorted(base.rglob("*.json")))
    return candidates


def collect_scenarios(collections: list[Path]) -> list[dict[str, Any]]:
    scenarios: list[dict[str, Any]] = []
    for collection in collections:
        try:
            document = json.loads(collection.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            scenarios.append({"collection": collection.name, "status": "UNREADABLE", "detail": str(error)})
            continue
        items = document.get("item", [])
        scenarios.append(
            {
                "collection": collection.name,
                "status": "READABLE",
                "top_level_items": len(items),
                "has_test_scripts": any(_item_has_test(item) for item in items),
                "postman_version": document.get("info", {}).get("schema", ""),
            }
        )
    return scenarios


def _item_has_test(item: dict[str, Any]) -> bool:
    if "event" in item:
        return True
    if "item" in item:
        return any(_item_has_test(child) for child in item["item"])
    return False


def _source_evidence(repository: Path) -> dict[str, str]:
    compose = repository / "docker-compose.yml"
    return {
        "revision": current_revision(repository),
        "source_fingerprint": source_fingerprint(repository),
        "harness_fingerprint": harness_fingerprint(repository),
        "compose_fingerprint": sha256_file(compose) if compose.exists() else "missing",
    }


def _build_evidence(repository: Path) -> dict[str, Any]:
    verification = read_json(config.verification_path(), {}) or {}
    build = (verification.get("suites") or {}).get("build") or {}
    current = _source_evidence(repository)
    valid = (
        build.get("status") == "PASS"
        and build.get("mode") == "clean-verify"
        and type(build.get("sequence")) is int
        and build.get("source_fingerprint") == current["source_fingerprint"]
        and build.get("harness_fingerprint") == current["harness_fingerprint"]
        and _revision_is_contained(repository, str(build.get("revision") or ""))
    )
    return {
        "build_sequence": build.get("sequence") if valid else "",
        "build_revision": build.get("revision") if valid else "",
        "build_source_fingerprint": build.get("source_fingerprint") if valid else "",
        "build_harness_fingerprint": build.get("harness_fingerprint") if valid else "",
    }


def _container_images(repository: Path) -> dict[str, str]:
    compose = ["docker", "compose", "--profile", "observability", "-f", str(repository / "docker-compose.yml")]
    listed = run_command(compose + ["ps", "--format", "{{.Service}}|{{.Name}}"], repository)
    images: dict[str, str] = {}
    for line in listed.stdout_tail.splitlines():
        service, separator, container = line.partition("|")
        if not separator or not service or not container:
            continue
        inspected = run_command(["docker", "inspect", "--format", "{{.Image}}", container], repository)
        if inspected.exit_code == 0 and inspected.stdout_tail.strip():
            images[service] = inspected.stdout_tail.strip()
    return images


def _e2e_evidence(repository: Path) -> dict[str, Any]:
    return {
        **_source_evidence(repository),
        **_build_evidence(repository),
        "container_images": _container_images(repository),
    }


def _artifact_identity(path: Path) -> tuple[str, int]:
    match = re.fullmatch(r"run-(.+)-(\d+)\.json", path.name)
    return (match.group(1), int(match.group(2))) if match else ("", 0)


def _artifact_attempt(path: Path) -> int:
    return _artifact_identity(path)[1]


def _attempt_value(value: Any) -> int:
    return value if type(value) is int and value >= 0 else 0


def _max_recorded_attempt(log_dir: Path) -> int:
    ledger = read_json(log_dir / "e2e-ledger.json", {}) or {}
    scopes = ledger.get("scopes") or {}
    attempts = [_attempt_value(entry.get("attempt")) for entry in scopes.values() if isinstance(entry, dict)]
    running = read_json(log_dir / "running.json", {}) or {}
    attempts.append(_attempt_value(running.get("attempt")))
    attempts.extend(_artifact_attempt(path) for path in log_dir.glob("run-*.json"))
    return max(attempts, default=0)


def _next_e2e_attempt(log_dir: Path) -> int:
    path = log_dir / "attempt-sequence.json"
    with file_lock(path):
        current = read_json(path, {}) or {}
        recorded = current.get("sequence")
        sequence = recorded if type(recorded) is int and recorded >= 0 else 0
        value = max(sequence, _max_recorded_attempt(log_dir)) + 1
        write_json(path, {"sequence": value})
    return value


def _revision_is_contained(repository: Path, revision: str) -> bool:
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


def _validate_run_document(document: dict[str, Any]) -> str:
    if type(document.get("attempt")) is not int or document["attempt"] < 1:
        return "attempt is not a positive integer"
    if not isinstance(document.get("scope"), str) or not document["scope"]:
        return "scope is missing"
    if document.get("state") not in ("COMPLETED", "FAILED", "BLOCKED", "NO_SCOPE"):
        return "state is invalid"
    if type(document.get("executed_collections")) is not int or document["executed_collections"] < 0:
        return "executed_collections is not a nonnegative integer"
    if not isinstance(document.get("collections"), list):
        return "collections is not a list"
    try:
        datetime.strptime(str(document.get("generated_at") or ""), "%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return "generated_at is invalid"
    if document["state"] == "COMPLETED":
        passed = [
            entry
            for entry in document["collections"]
            if isinstance(entry, dict)
            and entry.get("status") == "PASSED"
            and type(entry.get("executions")) is int
            and entry["executions"] > 0
        ]
        if document["executed_collections"] == 0 or len(passed) != document["executed_collections"]:
            return "completed run does not prove one executed request per collection"
        if any(isinstance(entry, dict) and entry.get("status") == "FAILED" for entry in document["collections"]):
            return "completed run contains a failed collection"
    return ""


def _validate_newman_evidence(document: dict[str, Any], log_dir: Path, repository: Path) -> str:
    scope = str(document.get("scope") or "")
    expected: dict[str, tuple[str, ...]] = {}
    discovered = discover_collections(repository)
    available_names = {name for collection in discovered for name in collection_folder_names(collection)}
    prerequisites = SCOPE_PREREQUISITES.get(scope, ())
    folders = SCOPE_FOLDERS.get(scope, ())
    requested = tuple(prerequisites) + tuple(folders) if scope != "all" else ()
    missing_folders = [name for name in requested if name not in available_names]
    if missing_folders:
        return "required scope folders are missing: " + ", ".join(missing_folders)
    for collection in discovered:
        available = collection_folder_names(collection)
        selected = tuple(folder for folder in requested if folder in available)
        if scope == "all" or selected:
            expected[collection.name] = selected
    actual: dict[str, tuple[str, ...]] = {}
    reports: set[str] = set()
    for entry in document.get("collections", []):
        if not isinstance(entry, dict) or entry.get("status") == "SKIPPED":
            continue
        name = str(entry.get("collection") or "")
        if not name or name in actual:
            return "collection identities are missing or duplicated"
        actual[name] = tuple(entry.get("folders") or [])
        report = entry.get("newman_report")
        expected_hash = entry.get("newman_report_sha256")
        if not isinstance(report, str) or not report or not isinstance(expected_hash, str) or not expected_hash:
            return "collection has no bound Newman export"
        if report in reports:
            return "collections reuse one Newman export"
        reports.add(report)
        path = Path(report)
        if not path.is_absolute() or path.parent != log_dir:
            return "collection Newman export is outside the run directory"
        if not path.exists() or sha256_file(path) != expected_hash:
            return "collection Newman export is missing or changed"
        executions, error = _newman_execution_count(path)
        if error or type(entry.get("executions")) is not int or entry["executions"] != executions:
            return "collection execution count does not match the Newman export"
    if actual != expected:
        return "collection or folder inventory does not match the discovered collections"
    return ""


def _redact_newman_report(path: Path) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    def sensitive_name(value: Any) -> bool:
        normalized = re.sub(r"[^a-z0-9]", "", str(value).lower())
        return (
            normalized in {"authorization", "password", "passwd", "token", "secret", "apikey", "cookie", "setcookie"}
            or normalized.endswith(("token", "secret", "password", "passwd", "clientsecret"))
        )

    def redact(value: Any, key: str = "") -> Any:
        if isinstance(value, dict):
            header_key = str(value.get("key", ""))
            result = {}
            for name, item in value.items():
                lowered = str(name).lower()
                if sensitive_name(name) or (lowered == "value" and sensitive_name(header_key)):
                    result[name] = "[REDACTED]"
                else:
                    result[name] = redact(item, lowered)
            return result
        if isinstance(value, list):
            return [redact(item, key) for item in value]
        if isinstance(value, str):
            return re.sub(
                r"(?i)(\"?[a-z0-9_]*(?:password|passwd|token|secret|clientsecret|authorization)\"?\s*[:=]\s*)([^,}\s]+)",
                r"\1[REDACTED]",
                value,
            )
        return value

    payload = json.dumps(redact(document), indent=2, sort_keys=True) + "\n"
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.fchmod(descriptor, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            descriptor = -1
            handle.write(payload)
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def _newman_execution_count(path: Path) -> tuple[int, str]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        return 0, str(error)
    if not isinstance(document, dict):
        return 0, "newman report is not an object"
    run = document.get("run")
    if not isinstance(run, dict):
        return 0, "newman report has no run object"
    executions = run.get("executions")
    if not isinstance(executions, list):
        return 0, "newman report has no executions list"
    if any(not isinstance(entry, dict) for entry in executions):
        return 0, "newman report contains a non-object execution"
    if run.get("failures"):
        return 0, "newman report contains failed assertions"
    return len(executions), ""


def _start_e2e_run(log_dir: Path, scope: str, attempt: int, evidence: dict[str, str]) -> None:
    write_json(
        log_dir / "running.json",
        {
            "scope": scope,
            "attempt": attempt,
            "started_at": utc_now(),
            **evidence,
        },
    )


def _finish_e2e_run(log_dir: Path, scope: str, attempt: int, document: dict[str, Any]) -> Path:
    path = log_dir / f"run-{scope}-{attempt}.json"
    payload = json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    with path.open("x", encoding="utf-8") as handle:
        handle.write(payload)
    digest = sha256_file(path)
    ledger_path = log_dir / "e2e-ledger.json"
    with file_lock(ledger_path):
        ledger = read_json(ledger_path, {}) or {}
        scopes = ledger.setdefault("scopes", {})
        current = scopes.get(scope) or {}
        if _attempt_value(current.get("attempt")) <= attempt:
            scopes[scope] = {
                "attempt": attempt,
                "file": path.name,
                "state": document.get("state"),
                "sha256": digest,
            }
            write_json(ledger_path, ledger)
    running_path = log_dir / "running.json"
    running = read_json(running_path, {}) or {}
    if running.get("attempt") == attempt:
        running_path.unlink(missing_ok=True)
    sequence_path = log_dir / "attempt-sequence.json"
    with file_lock(sequence_path):
        sequence_document = read_json(sequence_path, {}) or {}
        sequence = _attempt_value(sequence_document.get("sequence"))
        write_json(sequence_path, {"sequence": max(sequence, attempt)})
    return path


def e2e_run(scope: str = "all", root: Path | None = None, run_id: str | None = None, extra_arguments: list[str] | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    with file_lock(log_dir / "run.lock"):
        try:
            return _e2e_run_locked(scope, repository, identifier, extra_arguments)
        finally:
            (log_dir / "postman-environment.json").unlink(missing_ok=True)


def _e2e_run_locked(scope: str, repository: Path, identifier: str, extra_arguments: list[str] | None) -> dict[str, Any]:
    log_dir = config.run_root(identifier) / "e2e-results"
    evidence = _e2e_evidence(repository)
    attempt = _next_e2e_attempt(log_dir)
    _start_e2e_run(log_dir, scope, attempt, evidence)
    try:
        return _e2e_run(scope, repository, identifier, attempt, evidence, extra_arguments)
    except BaseException as error:
        document = {
            "generated_at": utc_now(),
            "attempt": attempt,
            "state": "FAILED",
            "scope": scope,
            "executed_collections": 0,
            "collections": [],
            "reason": "the end to end run raised an exception",
            "error": str(error),
            **evidence,
        }
        for export in log_dir.glob(f"newman-{scope}-{attempt}-*.json"):
            export.unlink(missing_ok=True)
        try:
            _finish_e2e_run(log_dir, scope, attempt, document)
        except BaseException:
            pass
        raise


def _e2e_run(
    scope: str,
    repository: Path,
    identifier: str,
    attempt: int,
    evidence: dict[str, str],
    extra_arguments: list[str] | None,
) -> dict[str, Any]:
    log_dir = config.run_root(identifier) / "e2e-results"
    setup_document = read_json(log_dir / "setup.json", {}) or {}
    setup_matches = (
        setup_document.get("state") == "READY"
        and all(setup_document.get(key) == evidence[key] for key in evidence)
        and _revision_is_contained(repository, str(setup_document.get("revision") or ""))
    )
    if not setup_matches:
        document = {
            "generated_at": utc_now(),
            "attempt": attempt,
            "state": "BLOCKED",
            "scope": scope,
            "executed_collections": 0,
            "collections": [],
            "reason": "e2e environment is not ready for the current source, harness and compose configuration; run harness e2e setup again",
            "setup_state": setup_document.get("state", "NEVER_RUN"),
            **evidence,
        }
        _finish_e2e_run(log_dir, scope, attempt, document)
        return document

    readiness = wait_for_healthy()
    if readiness.get("status") != "READY":
        document = {
            "generated_at": utc_now(),
            "attempt": attempt,
            "state": "BLOCKED",
            "scope": scope,
            "executed_collections": 0,
            "collections": [],
            "reason": "the compose stack did not report every service healthy, so the collection would measure a booting environment",
            "readiness": readiness,
            **evidence,
        }
        _finish_e2e_run(log_dir, scope, attempt, document)
        return document

    environment_path, environment_document = write_postman_environment(log_dir)
    missing_credentials = environment_document.get("missing_credentials", [])
    if missing_credentials:
        document = {
            "generated_at": utc_now(),
            "attempt": attempt,
            "state": "BLOCKED",
            "scope": scope,
            "executed_collections": 0,
            "collections": [],
            "reason": "the collection needs credentials that the local environment does not provide, so the run would only measure a rejected login",
            "missing_credentials": missing_credentials,
            **evidence,
        }
        _finish_e2e_run(log_dir, scope, attempt, document)
        return document

    newman = shutil.which("newman")
    collections = discover_collections(repository)
    if newman is None:
        document = {
            "generated_at": utc_now(),
            "attempt": attempt,
            "state": "BLOCKED",
            "scope": scope,
            "executed_collections": 0,
            "collections": [str(path) for path in collections],
            "reason": "newman is not installed on PATH",
            "scenarios": collect_scenarios(collections),
            **evidence,
        }
        _finish_e2e_run(log_dir, scope, attempt, document)
        return document

    results: list[dict[str, Any]] = []
    folders = SCOPE_FOLDERS.get(scope, ())
    prerequisites = SCOPE_PREREQUISITES.get(scope, ())
    if scope != "all":
        available_names = {name for collection in collections for name in collection_folder_names(collection)}
        missing_folders = [name for name in tuple(prerequisites) + tuple(folders) if name not in available_names]
        if missing_folders:
            document = {
                "generated_at": utc_now(),
                "attempt": attempt,
                "state": "BLOCKED",
                "scope": scope,
                "executed_collections": 0,
                "collections": [],
                "reason": "required scope folders are missing: " + ", ".join(missing_folders),
                **evidence,
            }
            _finish_e2e_run(log_dir, scope, attempt, document)
            return document
    for collection in collections:
        available = collection_folder_names(collection)
        requested = tuple(prerequisites) + tuple(folders) if scope != "all" else ()
        selected = tuple(folder for folder in requested if folder in available)
        if scope != "all" and not selected:
            results.append(
                {
                    "collection": collection.name,
                    "exit_code": 0,
                    "status": "SKIPPED",
                    "log": str(log_dir / f"{collection.stem}.{scope}.log"),
                    "folders": [],
                    "reason": "no folder in this collection belongs to the scope",
                }
            )
            continue
        report_path = log_dir / f"newman-{scope}-{attempt}-{collection.stem}-{time.time_ns()}.json"
        descriptor = os.open(report_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        os.close(descriptor)
        command = [newman, "run", str(collection), "--environment", str(environment_path), "--reporters", "cli,json", "--reporter-json-export", str(report_path)]
        for folder in selected:
            command.extend(["--folder", folder])
        command.extend(extra_arguments or [])
        result = run_command(command, repository, log_dir / f"{collection.stem}.{scope}.log")
        if report_path.exists():
            _redact_newman_report(report_path)
        executions, export_error = _newman_execution_count(report_path)
        results.append(
            {
                "collection": collection.name,
                "exit_code": result.exit_code,
                "status": "PASSED" if result.exit_code == 0 and executions > 0 else "FAILED",
                "executions": executions,
                "export_error": export_error,
                "newman_report": str(report_path),
                "newman_report_sha256": sha256_file(report_path) if report_path.exists() else "",
                "log": str(log_dir / f"{collection.stem}.{scope}.log"),
                "folders": list(selected),
                "prerequisites": list(prerequisites),
            }
        )

    executed = [entry for entry in results if entry["status"] != "SKIPPED"]
    if not executed:
        state = "NO_SCOPE"
        reason = "no collection in this repository contains a folder for the requested scope, so the run executed nothing"
    elif all(entry["exit_code"] == 0 and entry["executions"] > 0 for entry in executed):
        state = "COMPLETED"
        reason = "every collection that owns a folder for this scope passed and executed at least one request"
    else:
        state = "FAILED"
        reason = "at least one collection that owns a folder for this scope failed"

    document = {
        "generated_at": utc_now(),
        "attempt": attempt,
        "state": state,
        "reason": reason,
        "scope": scope,
        "executed_collections": len(executed),
        "collections": results,
        "scenario_inventory": collect_scenarios(collections),
        **evidence,
    }
    _finish_e2e_run(log_dir, scope, attempt, document)
    return document


def e2e_report(run_id: str | None = None) -> dict[str, Any]:
    repository = config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    with file_lock(log_dir / "run.lock"):
        return _e2e_report_locked(repository, identifier)


def _e2e_report_locked(repository: Path, identifier: str) -> dict[str, Any]:
    log_dir = config.run_root(identifier) / "e2e-results"
    evidence = {
        **_source_evidence(repository),
        **_build_evidence(repository),
    }
    entries: list[dict[str, Any]] = []
    malformed: list[dict[str, Any]] = []
    artifact_hashes: dict[str, str] = {}
    ledger_path = log_dir / "e2e-ledger.json"
    try:
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        ledger = {}
        malformed.append({"file": ledger_path.name, "reason": str(error)})
    if not isinstance(ledger, dict) or not isinstance(ledger.get("scopes"), dict):
        malformed.append({"file": ledger_path.name, "reason": "ledger has no scopes object"})
        ledger = {"scopes": {}}
    if (log_dir / "running.json").exists():
        malformed.append({"file": "running.json", "reason": "an end to end run is still in progress"})
    scopes = ledger["scopes"]
    unknown_scopes = sorted(set(scopes) - set(SCOPES) - {"all"})
    if unknown_scopes:
        malformed.append({"file": ledger_path.name, "reason": "unknown scopes: " + ", ".join(unknown_scopes)})
    artifact_max: dict[str, int] = {}
    for path in log_dir.glob("run-*.json"):
        scope, attempt = _artifact_identity(path)
        if scope:
            artifact_max[scope] = max(artifact_max.get(scope, 0), attempt)
    for scope in sorted(set(artifact_max) - set(scopes)):
        malformed.append({"file": ledger_path.name, "reason": "unledgered artifacts exist for " + scope})
    sequence_path = log_dir / "attempt-sequence.json"
    try:
        sequence_document = json.loads(sequence_path.read_text(encoding="utf-8"))
        sequence_value = sequence_document.get("sequence") if isinstance(sequence_document, dict) else None
    except (OSError, json.JSONDecodeError):
        sequence_value = None
    expected_sequence = max([_max_recorded_attempt(log_dir), *artifact_max.values()])
    if type(sequence_value) is not int or sequence_value != expected_sequence:
        malformed.append({"file": sequence_path.name, "reason": "attempt sequence does not match the recorded high-water mark"})
    for scope, record in sorted(scopes.items()):
        if not isinstance(record, dict) or type(record.get("attempt")) is not int or record["attempt"] < 1:
            malformed.append({"file": ledger_path.name, "reason": "invalid ledger record for " + scope})
            continue
        attempt = record["attempt"]
        if artifact_max.get(scope) != attempt:
            malformed.append({"file": ledger_path.name, "reason": "ledger is not the high-water record for " + scope})
            continue
        expected_name = f"run-{scope}-{attempt}.json"
        if record.get("file") != expected_name:
            malformed.append({"file": ledger_path.name, "reason": "ledger file does not match the immutable attempt for " + scope})
            continue
        path = log_dir / expected_name
        if not path.exists():
            malformed.append({"file": expected_name, "reason": "ledger artifact is missing"})
            continue
        digest = sha256_file(path)
        artifact_hashes[expected_name] = digest
        if record.get("sha256") != digest:
            malformed.append({"file": expected_name, "reason": "ledger artifact hash changed"})
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            malformed.append({"file": expected_name, "reason": str(error)})
            continue
        if not isinstance(document, dict):
            malformed.append({"file": expected_name, "reason": "artifact is not an object"})
            continue
        evidence_matches = all(document.get(key) == evidence[key] for key in evidence)
        revision_contained = _revision_is_contained(repository, str(document.get("revision") or ""))
        validation_error = _validate_run_document(document) if evidence_matches and revision_contained else ""
        if not validation_error and evidence_matches and revision_contained:
            images = document.get("container_images")
            if not isinstance(images, dict) or not images or any(
                not isinstance(service, str) or not service or not isinstance(digest, str) or not digest.startswith("sha256:")
                for service, digest in images.items()
            ):
                validation_error = "container image manifest is missing or invalid"
            else:
                validation_error = _validate_newman_evidence(document, log_dir, repository)
        if validation_error:
            malformed.append({"file": expected_name, "reason": validation_error})
        if document.get("attempt") != attempt or document.get("scope") != scope or document.get("state") != record.get("state"):
            malformed.append({"file": expected_name, "reason": "artifact identity does not match the ledger"})
        entries.append(
            {
                "file": expected_name,
                "scope": scope,
                "attempt": attempt,
                "state": document.get("state", "UNKNOWN"),
                "fresh": evidence_matches and revision_contained and not validation_error,
                "sha256": digest,
                "document": document,
            }
        )
    by_scope = {entry["scope"]: entry for entry in entries}
    latest_all = by_scope.get("all")
    if latest_all is None:
        if set(scopes) != set(SCOPES):
            malformed.append(
                {
                    "file": ledger_path.name,
                    "reason": "complete coverage requires scope all or every configured scope; missing: "
                    + ", ".join(sorted(set(SCOPES) - set(scopes))),
                }
            )
        relevant = [by_scope[scope] for scope in SCOPES if scope in by_scope]
    else:
        relevant = [latest_all] + [
            entry
            for scope, entry in by_scope.items()
            if scope != "all" and entry["attempt"] > latest_all["attempt"]
        ]
    completed = [
        entry
        for entry in relevant
        if entry.get("fresh") and entry.get("state") == "COMPLETED" and int(entry["document"].get("executed_collections") or 0) > 0
    ]
    verified = not malformed and bool(relevant) and len(completed) == len(relevant)
    executed = max(
        (int(entry["document"].get("executed_collections") or 0) for entry in relevant if entry.get("fresh")),
        default=0,
    )
    sequence = max([_max_recorded_attempt(log_dir), *(entry["attempt"] for entry in entries)])
    return {
        "generated_at": utc_now(),
        "run_id": identifier,
        **evidence,
        "sequence": sequence,
        "state": "COMPLETED" if verified else "FAILED",
        "reason": "the authoritative complete end to end attempt completed with executed requests"
        if verified
        else "the authoritative end to end ledger is incomplete, stale, malformed, failed or running",
        "artifacts": entries,
        "artifact_hashes": artifact_hashes,
        "malformed_artifacts": malformed,
        "e2e_verified": verified,
        "verified_scopes": list(dict.fromkeys(entry["document"].get("scope") for entry in completed)) if verified else [],
        "executed_collections": executed,
        "ignored_stale_completed": 0,
        "ignored_superseded_completed": 0,
        "evaluated_artifacts": [entry["file"] for entry in relevant],
        "evaluated_artifact_hashes": {entry["file"]: entry["sha256"] for entry in relevant},
    }
