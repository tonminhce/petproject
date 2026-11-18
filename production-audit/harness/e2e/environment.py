from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json
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


@dataclass
class ComponentStatus:
    name: str
    available: bool
    detail: str

    def to_document(self) -> dict[str, Any]:
        return {"name": self.name, "available": self.available, "detail": self.detail}


def detect_dependencies() -> list[ComponentStatus]:
    statuses: list[ComponentStatus] = []
    for name, binaries in REQUIRED_COMPONENTS.items():
        missing = [binary for binary in binaries if shutil.which(binary) is None]
        if missing:
            statuses.append(ComponentStatus(name, False, f"missing binaries: {missing}"))
            continue
        probe = subprocess.run(
            ["docker", "info", "--format", "{{.ServerVersion}}"],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
        if probe.returncode != 0:
            statuses.append(ComponentStatus(name, False, f"docker daemon unavailable: {probe.stderr.strip()[:200]}"))
            continue
        statuses.append(ComponentStatus(name, True, f"docker server {probe.stdout.strip()}"))
    return statuses


def compose_files(root: Path | None = None) -> list[Path]:
    repository = root or config.repository_root()
    return [repository / "docker-compose.yml"]


def e2e_setup(root: Path | None = None, profile: str = "default", run_id: str | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    statuses = detect_dependencies()
    unavailable = [status for status in statuses if not status.available]

    if unavailable:
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "reason": "required e2e infrastructure unavailable",
            "components": [status.to_document() for status in statuses],
            "unavailable": [status.name for status in unavailable],
        }
        write_json(log_dir / "setup.json", document)
        return document

    command = ["docker", "compose", "-f", str(repository / "docker-compose.yml"), "up", "-d"]
    result = run_command(command, repository, log_dir / "compose-up.log")
    document = {
        "generated_at": utc_now(),
        "state": "READY" if result.exit_code == 0 else "FAILED",
        "components": [status.to_document() for status in statuses],
        "compose_exit_code": result.exit_code,
        "compose_log": str(log_dir / "compose-up.log"),
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
    write_json(log_dir / "teardown.json", document)
    return document


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


def e2e_run(scope: str = "all", root: Path | None = None, run_id: str | None = None, extra_arguments: list[str] | None = None) -> dict[str, Any]:
    repository = root or config.repository_root()
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"

    setup_document = read_json(log_dir / "setup.json", {}) or {}
    if setup_document.get("state") != "READY":
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "scope": scope,
            "reason": "e2e environment is not ready; run harness e2e setup first",
            "setup_state": setup_document.get("state", "NEVER_RUN"),
        }
        write_json(log_dir / f"run-{scope}.json", document)
        return document

    newman = shutil.which("newman")
    collections = discover_collections(repository)
    if newman is None:
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "scope": scope,
            "reason": "newman is not installed on PATH",
            "collections": [str(path) for path in collections],
            "scenarios": collect_scenarios(collections),
        }
        write_json(log_dir / f"run-{scope}.json", document)
        return document

    results: list[dict[str, Any]] = []
    for collection in collections:
        command = [newman, "run", str(collection), "--reporters", "cli,json", "--reporter-json-export", str(log_dir / f"{collection.stem}.json")]
        command.extend(extra_arguments or [])
        result = run_command(command, repository, log_dir / f"{collection.stem}.log")
        results.append(
            {
                "collection": collection.name,
                "exit_code": result.exit_code,
                "status": "PASSED" if result.exit_code == 0 else "FAILED",
                "log": str(log_dir / f"{collection.stem}.log"),
            }
        )

    document = {
        "generated_at": utc_now(),
        "state": "COMPLETED" if all(entry["exit_code"] == 0 for entry in results) else "FAILED",
        "scope": scope,
        "collections": results,
        "scenario_inventory": collect_scenarios(collections),
    }
    write_json(log_dir / f"run-{scope}.json", document)
    return document


def e2e_report(run_id: str | None = None) -> dict[str, Any]:
    identifier = run_id or config.run_identifier()
    log_dir = config.run_root(identifier) / "e2e-results"
    entries: list[dict[str, Any]] = []
    for path in sorted(log_dir.glob("*.json")) if log_dir.exists() else []:
        document = read_json(path, {}) or {}
        entries.append({"file": path.name, "state": document.get("state", "UNKNOWN"), "document": document})
    return {
        "generated_at": utc_now(),
        "run_id": identifier,
        "artifacts": entries,
        "e2e_verified": any(entry.get("state") == "COMPLETED" for entry in entries),
    }
