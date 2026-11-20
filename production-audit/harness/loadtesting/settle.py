from __future__ import annotations

import os
import time
from typing import Any

from .environment import CONTAMINATION_LOAD_PER_CPU, cpu_count, load_average

SETTLE_TIMEOUT_SECONDS = 240
SETTLE_POLL_SECONDS = 10


def wait_for_quiet_host(
    timeout_seconds: int = SETTLE_TIMEOUT_SECONDS,
    poll_seconds: int = SETTLE_POLL_SECONDS,
) -> dict[str, Any]:
    cores = cpu_count()
    deadline = time.time() + timeout_seconds
    waited = 0.0
    per_cpu = None
    while True:
        average = load_average()
        if average is not None:
            per_cpu = average[0] / cores if cores else average[0]
            if per_cpu <= CONTAMINATION_LOAD_PER_CPU:
                return {
                    "status": "QUIET",
                    "waited_seconds": round(waited, 1),
                    "load_per_cpu": round(per_cpu, 2),
                }
        if time.time() >= deadline:
            return {
                "status": "BUSY",
                "waited_seconds": round(waited, 1),
                "load_per_cpu": round(per_cpu, 2) if per_cpu is not None else None,
            }
        time.sleep(poll_seconds)
        waited += poll_seconds
