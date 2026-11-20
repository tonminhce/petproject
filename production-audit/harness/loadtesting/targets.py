from __future__ import annotations

import os
import subprocess
from typing import Any

PROMOTION_DATABASE = "promotionservice"
PROMOTION_USER = "admin"


def _query(sql: str) -> str:
    try:
        result = subprocess.run(
            ["docker", "exec", "postgres", "psql", "-U", PROMOTION_USER, "-d", PROMOTION_DATABASE, "-t", "-c", sql],
            capture_output=True,
            text=True,
            check=False,
            timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired):
        return ""
    if result.returncode != 0:
        return ""
    for line in result.stdout.splitlines():
        candidate = line.strip()
        if candidate:
            return candidate
    return ""


def resolve_coupon_code() -> dict[str, Any]:
    configured = str(os.environ.get("COUPON_CODE", "")).strip()
    if configured:
        return {"status": "CONFIGURED", "code": configured, "source": "the environment"}
    code = _query(
        "SELECT code FROM campaign WHERE status = 'ACTIVE' "
        "AND (max_redemptions IS NULL OR max_redemptions > used_count) "
        "ORDER BY created_at DESC LIMIT 1"
    )
    if not code:
        return {
            "status": "UNAVAILABLE",
            "reason": "no active campaign is available, so the profile cannot exercise the coupon contention",
        }
    return {"status": "RESOLVED", "code": code, "source": "the live promotion catalogue"}


def resolve_hot_product_id() -> dict[str, Any]:
    configured = str(os.environ.get("HOT_PRODUCT_ID", "")).strip()
    if configured:
        return {"status": "CONFIGURED", "id": configured, "source": "the environment"}
    return {
        "status": "UNRESOLVED",
        "reason": "the profile needs a product that is in stock, so the product must be supplied",
    }
