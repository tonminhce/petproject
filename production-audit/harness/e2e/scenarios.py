from __future__ import annotations

import json
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import utc_now, write_json


@dataclass
class Assertion:
    kind: str
    target: str
    expectation: str
    passed: bool
    detail: str = ""

    def to_document(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "target": self.target,
            "expectation": self.expectation,
            "passed": self.passed,
            "detail": self.detail,
        }


def run_sql(container: str, database: str, statement: str, timeout: int = 30) -> tuple[int, str, str]:
    completed = subprocess.run(
        [
            "docker",
            "exec",
            container,
            "psql",
            "-U",
            "postgres",
            "-d",
            database,
            "-tAc",
            statement,
        ],
        capture_output=True,
        text=True,
        check=False,
        timeout=timeout,
    )
    return completed.returncode, completed.stdout.strip(), completed.stderr.strip()


def wait_for_row(
    container: str,
    database: str,
    statement: str,
    timeout_seconds: int = 60,
    interval_seconds: float = 1.0,
) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    last = ""
    while time.monotonic() < deadline:
        code, out, err = run_sql(container, database, statement)
        last = out or err
        if code == 0 and out:
            return True, out
        time.sleep(interval_seconds)
    return False, last


def wait_for_http(url: str, timeout_seconds: int = 120) -> tuple[bool, str]:
    deadline = time.monotonic() + timeout_seconds
    last = ""
    while time.monotonic() < deadline:
        completed = subprocess.run(
            ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "5", url],
            capture_output=True,
            text=True,
            check=False,
        )
        last = completed.stdout.strip() or completed.stderr.strip()
        if completed.returncode == 0 and last.startswith("2"):
            return True, last
        time.sleep(2)
    return False, last


def assert_row(
    container: str,
    database: str,
    label: str,
    statement: str,
    expected_minimum: int = 1,
) -> Assertion:
    code, out, err = run_sql(container, database, statement)
    if code != 0:
        return Assertion("DATABASE_ROW", label, f"at least {expected_minimum}", False, err[:200])
    try:
        count = int(out or "0")
    except ValueError:
        count = 0
    passed = count >= expected_minimum
    return Assertion("DATABASE_ROW", label, f"at least {expected_minimum}", passed, f"count={count}")


def assert_http_status(url: str, label: str, expected: str) -> Assertion:
    completed = subprocess.run(
        ["curl", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--max-time", "10", url],
        capture_output=True,
        text=True,
        check=False,
    )
    actual = completed.stdout.strip()
    return Assertion("HTTP_STATUS", label, expected, actual == expected, f"actual={actual}")


def write_scenario_result(
    scope: str,
    assertions: list[Assertion],
    run_id: str | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    identifier = run_id or config.run_identifier()
    target = config.run_root(identifier) / "e2e-results" / f"scenario-{scope}.json"
    document = {
        "generated_at": utc_now(),
        "scope": scope,
        "state": "PASSED" if all(item.passed for item in assertions) else "FAILED",
        "assertion_count": len(assertions),
        "passed": sum(1 for item in assertions if item.passed),
        "failed": sum(1 for item in assertions if not item.passed),
        "assertions": [item.to_document() for item in assertions],
        **(extra or {}),
    }
    write_json(target, document)
    return document
