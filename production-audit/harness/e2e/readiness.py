from __future__ import annotations

import shutil
import subprocess
import time
from typing import Any

HEALTHY_MARKER = "healthy"
UNHEALTHY_MARKER = "health:"
READY_TIMEOUT_SECONDS = 600
POLL_SECONDS = 5


def _running_services() -> list[tuple[str, str]]:
    if shutil.which("docker") is None:
        return []
    try:
        result = subprocess.run(
            ["docker", "compose", "ps", "--format", "{{.Service}}|{{.Status}}"],
            capture_output=True,
            text=True,
            check=False,
            timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    if result.returncode != 0:
        return []
    services: list[tuple[str, str]] = []
    for line in result.stdout.splitlines():
        if "|" not in line:
            continue
        service, _, status = line.partition("|")
        services.append((service.strip(), status.strip()))
    return services


def _is_ready(status: str) -> bool:
    if UNHEALTHY_MARKER in status:
        return HEALTHY_MARKER in status
    return True


def wait_for_healthy(
    services: list[str] | None = None,
    timeout_seconds: int = READY_TIMEOUT_SECONDS,
    poll_seconds: int = POLL_SECONDS,
) -> dict[str, Any]:
    deadline = time.time() + timeout_seconds
    last: list[tuple[str, str]] = []
    while time.time() < deadline:
        last = _running_services()
        if last:
            pending = [
                f"{service} ({status})"
                for service, status in last
                if (services is None or service in services) and not _is_ready(status)
            ]
            if not pending:
                return {
                    "status": "READY",
                    "services": [{"service": service, "status": status} for service, status in last],
                    "waited_seconds": round(timeout_seconds - (deadline - time.time()), 1),
                }
        time.sleep(poll_seconds)
    return {
        "status": "TIMEOUT",
        "services": [{"service": service, "status": status} for service, status in last],
        "pending": [
            f"{service} ({status})"
            for service, status in last
            if (services is None or service in services) and not _is_ready(status)
        ],
    }
