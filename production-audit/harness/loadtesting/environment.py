from __future__ import annotations

import os
import platform
import shutil
import subprocess
from typing import Any

CONTAMINATION_LOAD_PER_CPU = 0.6


def cpu_count() -> int:
    return os.cpu_count() or 1


def load_average() -> tuple[float, float, float] | None:
    try:
        return os.getloadavg()
    except OSError:
        return None


def host_snapshot() -> dict[str, Any]:
    average = load_average()
    cores = cpu_count()
    snapshot: dict[str, Any] = {
        "cpu_count": cores,
        "platform": platform.platform(),
        "load_average": None,
        "load_per_cpu": None,
        "contaminated": False,
        "reasons": [],
    }
    if average is not None:
        one, five, fifteen = average
        snapshot["load_average"] = [round(one, 2), round(five, 2), round(fifteen, 2)]
        per_cpu = one / cores
        snapshot["load_per_cpu"] = round(per_cpu, 2)
        if per_cpu > CONTAMINATION_LOAD_PER_CPU:
            snapshot["loaded_host"] = True
            snapshot["reasons"].append(
                "the one minute load average is "
                + str(round(one, 2))
                + " on "
                + str(cores)
                + " cores, which is "
                + str(round(per_cpu, 2))
                + " per core and above the "
                + str(CONTAMINATION_LOAD_PER_CPU)
                + " threshold used to detect an unloaded measurement host"
            )
    running_builds = _running_builds()
    if running_builds:
        snapshot["contaminated"] = True
        snapshot["reasons"].append("build processes were running: " + ", ".join(running_builds))
    if snapshot.get("loaded_host") and not running_builds:
        snapshot["reasons"].append(
            "the host carried load from an earlier measurement, so the run started before the host settled"
        )
    snapshot["concurrent_builds"] = running_builds
    return snapshot


def _running_builds() -> list[str]:
    if shutil.which("pgrep") is None:
        return []
    try:
        result = subprocess.run(
            ["pgrep", "-fl", "maven|java.*surefire|java.*maven"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return []
    entries = []
    for line in result.stdout.split("\n"):
        stripped = line.strip()
        if not stripped:
            continue
        entries.append(stripped.split(" ", 1)[1][:60] if " " in stripped else stripped)
    return entries[:5]


def compose_services() -> list[dict[str, str]]:
    if shutil.which("docker") is None:
        return []
    try:
        result = subprocess.run(
            ["docker", "compose", "ps", "--format", "{{.Service}}|{{.State}}|{{.Status}}"],
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return []
    if result.returncode != 0:
        return []
    services = []
    for line in result.stdout.split("\n"):
        parts = line.split("|")
        if len(parts) >= 2:
            services.append({"service": parts[0].strip(), "state": parts[1].strip(), "status": parts[2].strip() if len(parts) > 2 else ""})
    return services
