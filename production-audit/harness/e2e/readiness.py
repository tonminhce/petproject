from __future__ import annotations

import shutil
import subprocess
import time
from pathlib import Path
from typing import Any

from .. import config

READY_TIMEOUT_SECONDS = 600
POLL_SECONDS = 5
RUNNING_PREFIXES = ("Up ", "Up(")


def _compose_project(root: Path | None) -> Path:
    if root is not None:
        return root
    return config.repository_root()


def _running_services(root: Path | None = None) -> list[tuple[str, str]]:
    if shutil.which("docker") is None:
        return []
    try:
        result = subprocess.run(
            ["docker", "compose", "ps", "--format", "{{.Service}}|{{.Status}}"],
            cwd=str(_compose_project(root)),
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


def _expected_services(services: list[str] | None, root: Path | None = None) -> list[str] | None:
    if services is not None:
        return list(services)
    if shutil.which("docker") is None:
        return None
    try:
        result = subprocess.run(
            ["docker", "compose", "config", "--services"],
            cwd=str(_compose_project(root)),
            capture_output=True,
            text=True,
            check=False,
            timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if result.returncode != 0:
        return None
    declared = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    return declared or None


def _is_ready(status: str) -> bool:
    normalised = status.strip()
    if not normalised.startswith(RUNNING_PREFIXES):
        return False
    if "(unhealthy)" in normalised:
        return False
    if "health:" in normalised:
        return "(healthy)" in normalised
    return True


def wait_for_healthy(
    services: list[str] | None = None,
    timeout_seconds: int = READY_TIMEOUT_SECONDS,
    poll_seconds: int = POLL_SECONDS,
    root: Path | None = None,
) -> dict[str, Any]:
    deadline = time.time() + timeout_seconds
    last: list[tuple[str, str]] = []
    expected = _expected_services(services, root)
    missing: list[str] = []
    while time.time() < deadline:
        last = _running_services(root)
        if last:
            present = {service for service, _ in last}
            missing = sorted(service for service in (expected or []) if service not in present)
            pending = [
                f"{service} ({status})"
                for service, status in last
                if (services is None or service in services) and not _is_ready(status)
            ]
            if not pending and not missing:
                return {
                    "status": "READY",
                    "services": [{"service": service, "status": status} for service, status in last],
                    "waited_seconds": round(timeout_seconds - (deadline - time.time()), 1),
                }
        time.sleep(poll_seconds)
    present = {service for service, _ in last}
    missing = sorted(service for service in (expected or []) if service not in present)
    return {
        "status": "TIMEOUT",
        "services": [{"service": service, "status": status} for service, status in last],
        "pending": [
            f"{service} ({status})"
            for service, status in last
            if (services is None or service in services) and not _is_ready(status)
        ],
        "missing": missing,
    }
