from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Any

LOAD_OVERRIDE = "docker-compose.loadtest.yml"
BASE_COMPOSE = "docker-compose.yml"
GATEWAY_SERVICE = "gateway-service"


def _compose(command: list[str], cwd: Path) -> dict[str, Any]:
    try:
        result = subprocess.run(
            ["docker", "compose"] + command,
            cwd=str(cwd),
            capture_output=True,
            text=True,
            check=False,
            timeout=600,
        )
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"status": "ERROR", "detail": str(error)}
    return {
        "status": "OK" if result.returncode == 0 else "ERROR",
        "exit_code": result.returncode,
        "detail": (result.stderr or result.stdout or "").strip()[-600:],
    }


def apply_load_test_rate_limits(repository: Path | None = None) -> dict[str, Any]:
    root = repository or Path.cwd()
    override = root / LOAD_OVERRIDE
    if not override.exists():
        return {
            "status": "SKIPPED",
            "reason": "the load test compose override is not present, so the shipped rate limits apply",
        }
    applied = _compose(
        ["-f", BASE_COMPOSE, "-f", LOAD_OVERRIDE, "up", "-d", "--no-deps", "--force-recreate", GATEWAY_SERVICE],
        root,
    )
    if applied.get("status") != "OK":
        return {
            "status": "ERROR",
            "reason": "the gateway could not be recreated with the load test rate limits",
            "detail": applied.get("detail", ""),
        }

    from ..e2e.readiness import wait_for_healthy

    readiness = wait_for_healthy(services=[GATEWAY_SERVICE])
    if readiness.get("status") != "READY":
        return {
            "status": "ERROR",
            "reason": "the gateway did not report healthy after the recreation, so the measured run would have "
                      "counted the startup of the gateway as a business failure",
            "detail": str(readiness.get("pending", "")),
        }

    return {
        "status": "APPLIED",
        "override": LOAD_OVERRIDE,
        "readiness": readiness,
        "reason": "the shipped per client rate limit would throttle the measured traffic, so the gateway was "
                  "recreated with the load test limits and the run started only after it reported healthy",
    }


def restore_shipped_rate_limits(repository: Path | None = None) -> dict[str, Any]:
    root = repository or Path.cwd()
    restored = _compose(
        ["-f", BASE_COMPOSE, "up", "-d", "--no-deps", "--force-recreate", GATEWAY_SERVICE],
        root,
    )
    from ..e2e.readiness import wait_for_healthy

    readiness = wait_for_healthy(services=[GATEWAY_SERVICE])
    return {
        "status": "OK" if restored.get("status") == "OK" and readiness.get("status") == "READY" else "ERROR",
        "readiness": readiness,
        "detail": restored.get("detail", ""),
    }
