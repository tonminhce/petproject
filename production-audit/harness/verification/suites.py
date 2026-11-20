from __future__ import annotations

from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import read_json, utc_now, write_json
from .gates import FAIL, PASS, interpret_test_exit, run_command

DEFAULT_SUITES: dict[str, dict[str, Any]] = {
    "unit": {"patterns": ["*Test"], "include_integration": False, "description": "surefire unit tests"},
    "happy": {"patterns": ["*Test"], "exclude": ["*Edge*", "*Negative*", "*Concurrency*", "*Race*"], "include_integration": False, "description": "happy path business scenarios"},
    "edge": {"patterns": ["*Edge*Test", "*Negative*Test", "*Validation*Test", "*Boundary*Test"], "include_integration": False, "description": "edge and negative scenarios"},
    "concurrency": {"patterns": ["*Concurrency*Test", "*Concurrency*IT", "*Race*Test", "*Parallel*Test"], "include_integration": True, "description": "concurrency and multi-instance scenarios"},
    "integration": {"patterns": ["*IT"], "include_integration": True, "description": "integration tests against real infrastructure"},
}

SUITES_PATH = config.config_root() / "suites.json"


def load_suites() -> dict[str, dict[str, Any]]:
    document = read_json(SUITES_PATH, None)
    if not document:
        document = DEFAULT_SUITES
        write_json(SUITES_PATH, document)
    return document


def build_command(suite_name: str, suite: dict[str, Any], repository: Path) -> list[str]:
    patterns = [pattern for pattern in suite.get("patterns", []) if pattern not in suite.get("exclude", [])]
    command = ["./mvnw", "-B", "-ntp", "-T1C"]
    if suite.get("include_integration"):
        command.append("verify")
    else:
        command.append("test")
    if patterns:
        command.append("-Dtest=" + ",".join(patterns))
        command.append("-Dsurefire.failIfNoSpecifiedTests=false")
    if suite.get("include_integration"):
        command.append("-Dit.test=" + ",".join(patterns))
        command.append("-Dfailsafe.failIfNoSpecifiedTests=false")
    command.extend(suite.get("extra_arguments", []))
    return command


def run_suite(suite_name: str, repository: Path | None = None, run_id: str | None = None, modules: list[str] | None = None) -> dict[str, Any]:
    root = repository or config.repository_root()
    identifier = run_id or config.run_identifier()
    suites = load_suites()
    if suite_name not in suites:
        return {
            "suite": suite_name,
            "status": "UNKNOWN_SUITE",
            "exit_code": 3,
            "available": sorted(suites),
            "detail": f"unknown suite: {suite_name}",
        }

    suite = suites[suite_name]
    command = build_command(suite_name, suite, root)
    if modules:
        command = ["./mvnw", "-B", "-ntp", "-T", str(BUILD_PARALLELISM), "-pl", ",".join(modules), "-am"] + command[4:]

    log_path = config.run_root(identifier) / "test-results" / f"{suite_name}.log"
    result = run_command(command, root, log_path)
    interpretation = interpret_test_exit(result.exit_code, result.stdout_tail + result.stderr_tail)
    revision = run_command(["git", "-C", str(root), "rev-parse", "HEAD"], root).stdout_tail.strip()

    document = {
        "suite": suite_name,
        "description": suite.get("description", ""),
        "status": PASS if result.exit_code == 0 else FAIL,
        "exit_code": result.exit_code,
        "command": " ".join(command),
        "duration_seconds": result.duration_seconds,
        "tests_run": interpretation["tests_run"],
        "failures": interpretation["failures"],
        "errors": interpretation["errors"],
        "skipped": interpretation["skipped"],
        "log": str(log_path),
        "revision": revision,
        "updated_at": utc_now(),
    }
    write_json(config.run_root(identifier) / "test-results" / f"{suite_name}.json", document)
    return document


BUILD_PARALLELISM = 1


def build_command_full(repository: Path | None = None, quick: bool = False) -> list[str]:
    root = repository or config.repository_root()
    if quick:
        return ["./mvnw", "-B", "-ntp", "-T1C", "validate"]
    return ["./mvnw", "-B", "-ntp", "-T", str(BUILD_PARALLELISM), "clean", "verify"]


def run_build(repository: Path | None = None, run_id: str | None = None, quick: bool = False, modules: list[str] | None = None) -> dict[str, Any]:
    root = repository or config.repository_root()
    identifier = run_id or config.run_identifier()
    command = build_command_full(root, quick)
    if modules:
        command = ["./mvnw", "-B", "-ntp", "-T", str(BUILD_PARALLELISM), "-pl", ",".join(modules), "-am"] + command[4:]
    suffix = "-".join(modules) if modules else ("validate" if quick else "verify")
    log_path = config.run_root(identifier) / "test-results" / f"build-{suffix}.log"
    result = run_command(command, root, log_path)
    revision = run_command(["git", "-C", str(root), "rev-parse", "HEAD"], root).stdout_tail.strip()
    document = {
        "suite": "build",
        "mode": ("validate" if quick else "clean-verify") + (f" [{','.join(modules)}]" if modules else ""),
        "status": PASS if result.exit_code == 0 else FAIL,
        "exit_code": result.exit_code,
        "command": " ".join(command),
        "duration_seconds": result.duration_seconds,
        "log": str(log_path),
        "revision": revision,
        "updated_at": utc_now(),
    }
    write_json(config.run_root(identifier) / "test-results" / f"build-{suffix}.json", document)
    return document
