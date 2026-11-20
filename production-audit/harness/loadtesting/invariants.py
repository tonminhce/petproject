from __future__ import annotations

import subprocess
from typing import Any

from .. import config

POSTGRES_CONTAINER = "postgres"
POSTGRES_USER = "admin"

VALID_ORDER_STATUSES = ("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED")
TERMINAL_PAYMENT_STATUSES = ("CAPTURED", "REFUNDED", "FAILED")


def psql(database: str, statement: str, container: str = POSTGRES_CONTAINER) -> tuple[bool, list[str]]:
    command = [
        "docker",
        "exec",
        container,
        "psql",
        "-U",
        POSTGRES_USER,
        "-d",
        database,
        "-t",
        "-A",
        "-F",
        "|",
        "-c",
        statement,
    ]
    try:
        result = subprocess.run(command, capture_output=True, text=True, check=False)
    except OSError as error:
        return False, ["the postgres container could not be reached: " + str(error)]
    if result.returncode != 0:
        return False, [result.stderr.strip()[:400]]
    rows = [line.strip() for line in result.stdout.split("\n") if line.strip()]
    return True, rows


def _scalar(database: str, statement: str) -> tuple[bool, int | None, str]:
    ok, rows = psql(database, statement)
    if not ok:
        return False, None, rows[0] if rows else "query failed"
    if not rows:
        return True, 0, ""
    try:
        return True, int(rows[0]), ""
    except ValueError:
        return True, None, rows[0]


INVARIANTS: tuple[dict[str, Any], ...] = (
    {
        "id": "INV-01",
        "name": "NO_OVERSELLING",
        "database": "inventoryservice",
        "statement": (
            "SELECT COUNT(*) FROM inventory "
            "WHERE available_quantity < 0 OR reserved_quantity < 0"
        ),
        "description": "no inventory row may hold a negative available or reserved quantity",
    },
    {
        "id": "INV-02",
        "name": "COUPON_QUOTA_RESPECTED",
        "database": "promotionservice",
        "statement": (
            "SELECT COUNT(*) FROM campaign "
            "WHERE used_count < 0 OR (max_redemptions IS NOT NULL AND used_count > max_redemptions)"
        ),
        "description": "a campaign must never record more redemptions than its quota",
    },
    {
        "id": "INV-03",
        "name": "ORDER_STATUS_VALID",
        "database": "orderservice",
        "statement": (
            "SELECT COUNT(*) FROM orders WHERE status NOT IN ("
            + ", ".join("'" + item + "'" for item in VALID_ORDER_STATUSES)
            + ")"
        ),
        "description": "every order must hold a declared order status",
    },
    {
        "id": "INV-04",
        "name": "SINGLE_TERMINAL_PAYMENT",
        "database": "paymentservice",
        "statement": (
            "SELECT COUNT(*) FROM (SELECT order_id FROM payments WHERE deleted = false "
            "AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED') "
            "GROUP BY order_id HAVING COUNT(*) > 1) duplicates"
        ),
        "description": "an order must never hold more than one terminal payment",
    },
    {
        "id": "INV-05",
        "name": "PAYMENT_IDEMPOTENCY_UNIQUE",
        "database": "paymentservice",
        "statement": (
            "SELECT COUNT(*) FROM (SELECT idempotency_key FROM payments WHERE deleted = false "
            "GROUP BY idempotency_key HAVING COUNT(DISTINCT order_id) > 1) duplicates"
        ),
        "description": "one idempotency key must never be reused across different orders",
    },
    {
        "id": "INV-06",
        "name": "COUPON_RESERVATION_UNIQUE",
        "database": "promotionservice",
        "statement": (
            "SELECT COUNT(*) FROM (SELECT r.campaign_id, r.user_id, COUNT(*) AS committed "
            "FROM coupon_usage_reservation r JOIN campaign c ON c.id = r.campaign_id "
            "WHERE r.status = 'COMMITTED' AND c.per_user_limit > 0 "
            "GROUP BY r.campaign_id, r.user_id HAVING COUNT(*) > MAX(c.per_user_limit)) violations"
        ),
        "description": "a user must never hold more committed coupon reservations than the campaign per user limit",
    },
    {
        "id": "INV-07",
        "name": "PAYMENT_ORDER_CONSISTENCY",
        "database": "orderservice",
        "statement": (
            "SELECT id FROM orders WHERE deleted = false AND status IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')"
        ),
        "counterpart_database": "paymentservice",
        "counterpart_statement": (
            "SELECT DISTINCT order_id FROM payments WHERE deleted = false "
            "AND status IN ('CAPTURED', 'PARTIALLY_REFUNDED', 'REFUNDED')"
        ),
        "counterpart_match_column": 0,
        "description": "a confirmed order must have a payment that captured, was partially refunded or was refunded",
    },
    {
        "id": "INV-08",
        "name": "EXPIRED_RESERVATION_RELEASED",
        "database": "promotionservice",
        "statement": (
            "SELECT COUNT(*) FROM coupon_usage_reservation "
            "WHERE status = 'PENDING' AND expires_at < now() - interval '15 minutes'"
        ),
        "description": "an expired coupon reservation must not stay pending indefinitely",
    },
)


EXERCISED_BY: dict[str, tuple[str, ...]] = {
    "INV-01": ("full", "flash-sale", "promotion-storm"),
    "INV-02": ("promotion-storm",),
    "INV-03": ("full", "flash-sale", "promotion-storm"),
    "INV-04": ("full", "flash-sale", "promotion-storm"),
    "INV-05": ("full", "flash-sale", "promotion-storm"),
    "INV-06": ("promotion-storm",),
    "INV-07": ("full", "flash-sale", "promotion-storm"),
    "INV-08": ("promotion-storm",),
}

POPULATION_STATEMENTS: dict[str, str] = {
    "INV-01": "SELECT COUNT(*) FROM inventory",
    "INV-02": "SELECT COUNT(*) FROM campaign",
    "INV-03": "SELECT COUNT(*) FROM orders",
    "INV-04": "SELECT COUNT(*) FROM payments",
    "INV-05": "SELECT COUNT(*) FROM payments",
    "INV-06": "SELECT COUNT(*) FROM coupon_usage_reservation",
    "INV-07": "SELECT COUNT(*) FROM orders WHERE deleted = false AND status IN ('CONFIRMED', 'SHIPPED', 'DELIVERED')",
    "INV-08": "SELECT COUNT(*) FROM coupon_usage_reservation",
}


def _population(invariant: dict[str, Any]) -> tuple[bool, int | None, str]:
    statement = POPULATION_STATEMENTS.get(invariant["id"])
    if not statement:
        return True, None, ""
    return _scalar(invariant["database"], statement)


def _counterpart_violation(invariant: dict[str, Any]) -> tuple[bool, int | None, str]:
    ok, rows = psql(invariant["database"], invariant["statement"])
    if not ok:
        return False, None, rows[0] if rows else "query failed"
    expected = {row.split("|")[invariant["counterpart_match_column"]] for row in rows if row}
    ok, counterpart_rows = psql(invariant["counterpart_database"], invariant["counterpart_statement"])
    if not ok:
        return False, None, counterpart_rows[0] if counterpart_rows else "counterpart query failed"
    actual = {row.split("|")[0] for row in counterpart_rows if row}
    missing = expected - actual
    return True, len(missing), ""


def verify(container: str = POSTGRES_CONTAINER, scenario: str = "") -> dict[str, Any]:
    results: list[dict[str, Any]] = []
    for invariant in INVARIANTS:
        if "counterpart_statement" in invariant:
            ok, value, detail = _counterpart_violation(invariant)
        else:
            ok, value, detail = _scalar(invariant["database"], invariant["statement"])
        population_ok, population, population_detail = _population(invariant)
        if not ok:
            status = "BLOCKED"
        elif value is None:
            status = "BLOCKED"
            detail = detail or "the query returned an unexpected value"
        elif not population_ok:
            status = "BLOCKED"
            detail = population_detail or "the population of the inspected rows could not be read"
        elif population is not None and population == 0:
            exercised = EXERCISED_BY.get(invariant["id"], ())
            if scenario and exercised and scenario not in exercised:
                status = "SKIPPED"
                detail = (
                    "the measured scenario does not exercise this invariant, so an empty population proves nothing"
                )
            else:
                status = "BLOCKED"
                detail = (
                    "the invariant inspected zero rows, so it cannot prove anything about the exercised traffic"
                )
        elif value == 0:
            status = "PASS"
        else:
            status = "FAIL"
        results.append(
            {
                "id": invariant["id"],
                "name": invariant["name"],
                "description": invariant["description"],
                "database": invariant["database"],
                "databases": invariant["database"] + ("+" + invariant["counterpart_database"] if "counterpart_database" in invariant else ""),
                "violations": value,
                "population": population,
                "status": status,
                "detail": detail,
            }
        )
    failed = [item["id"] for item in results if item["status"] == "FAIL"]
    blocked = [item["id"] for item in results if item["status"] == "BLOCKED"]
    skipped = [item["id"] for item in results if item["status"] == "SKIPPED"]
    passed = [item["id"] for item in results if item["status"] == "PASS"]
    if failed:
        status = "FAIL"
    elif blocked:
        status = "BLOCKED"
    elif not passed:
        status = "BLOCKED"
        blocked = list(skipped)
    else:
        status = "PASS"
    return {
        "status": status,
        "failed": failed,
        "blocked": blocked,
        "skipped": skipped,
        "passed": passed,
        "invariants": results,
    }
