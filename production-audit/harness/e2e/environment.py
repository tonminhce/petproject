from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json
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
    for name in REQUIRED_COMPONENTS:
        if not compose_ok:
            statuses.append(ComponentStatus(name, False, compose_detail))
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
        statuses.append(ComponentStatus(name, True, f"docker server {probe.stdout.strip()}, {compose_detail}"))
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

    readiness = wait_for_healthy()
    if readiness.get("status") != "READY":
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "scope": scope,
            "reason": "the compose stack did not report every service healthy, so the collection would measure a booting environment",
            "readiness": readiness,
        }
        write_json(log_dir / f"run-{scope}.json", document)
        return document

    environment_path, environment_document = write_postman_environment(log_dir)
    missing_credentials = environment_document.get("missing_credentials", [])
    if missing_credentials:
        document = {
            "generated_at": utc_now(),
            "state": "BLOCKED",
            "scope": scope,
            "reason": "the collection needs credentials that the local environment does not provide, so the run would only measure a rejected login",
            "missing_credentials": missing_credentials,
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
    folders = SCOPE_FOLDERS.get(scope, ())
    prerequisites = SCOPE_PREREQUISITES.get(scope, ())
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
        command = [newman, "run", str(collection), "--environment", str(environment_path), "--reporters", "cli,json", "--reporter-json-export", str(log_dir / f"{collection.stem}.{scope}.json")]
        for folder in selected:
            command.extend(["--folder", folder])
        command.extend(extra_arguments or [])
        result = run_command(command, repository, log_dir / f"{collection.stem}.{scope}.log")
        results.append(
            {
                "collection": collection.name,
                "exit_code": result.exit_code,
                "status": "PASSED" if result.exit_code == 0 else "FAILED",
                "log": str(log_dir / f"{collection.stem}.{scope}.log"),
                "folders": list(selected),
                "prerequisites": list(prerequisites),
            }
        )

    executed = [entry for entry in results if entry["status"] != "SKIPPED"]
    if not executed:
        state = "NO_SCOPE"
        reason = "no collection in this repository contains a folder for the requested scope, so the run executed nothing"
    elif all(entry["exit_code"] == 0 for entry in executed):
        state = "COMPLETED"
        reason = "every collection that owns a folder for this scope passed"
    else:
        state = "FAILED"
        reason = "at least one collection that owns a folder for this scope failed"

    document = {
        "generated_at": utc_now(),
        "state": state,
        "reason": reason,
        "scope": scope,
        "executed_collections": len(executed),
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
    completed = [entry for entry in entries if entry.get("state") == "COMPLETED"]
    return {
        "generated_at": utc_now(),
        "run_id": identifier,
        "artifacts": entries,
        "e2e_verified": bool(completed),
        "verified_scopes": [entry["document"].get("scope") for entry in completed],
        "executed_collections": sum(
            int(entry.get("document", {}).get("executed_collections", 0) or 0) for entry in completed
        ),
    }
