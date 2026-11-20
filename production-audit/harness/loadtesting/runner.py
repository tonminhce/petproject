from __future__ import annotations

import json
import os
import subprocess
import time
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from .. import config
from .environment import compose_services, host_snapshot
from .limits import apply_load_test_rate_limits, restore_shipped_rate_limits
from .customers import mint_tokens, provision_customers
from .settle import wait_for_quiet_host
from .targets import resolve_coupon_code
from .invariants import verify
from .profiles import Profile, enforce_safety, resolve_profile

DEFAULT_NETWORK = "untitled5_ecommerce-network"
DEFAULT_BASE_URL = "http://gateway-service:8080"
K6_RUN_TIMEOUT_SECONDS = 2400


def results_root() -> Path:
    return config.run_root() / "load-results"


def _docker_network() -> str:
    result = subprocess.run(
        ["docker", "network", "ls", "--format", "{{.Name}}"],
        capture_output=True,
        text=True,
        check=False,
    )
    for line in result.stdout.split("\n"):
        if line.strip().endswith("_ecommerce-network"):
            return line.strip()
    return DEFAULT_NETWORK


def acquire_token() -> str:
    from .. import config

    config.load_local_environment()
    token = str(os.environ.get("LOAD_TEST_TOKEN", "")).strip()
    if token:
        return token
    url = str(os.environ.get("KEYCLOAK_TOKEN_URL", "")).strip()
    client_id = str(os.environ.get("KEYCLOAK_CLIENT_ID", "")).strip()
    username = str(os.environ.get("KEYCLOAK_USERNAME", "")).strip()
    password = str(os.environ.get("KEYCLOAK_PASSWORD", "")).strip()
    if not (url and client_id and username and password):
        return ""
    body = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": client_id,
            "username": username,
            "password": password,
        }
    ).encode("utf-8")
    request = urllib.request.Request(url, data=body)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, OSError, json.JSONDecodeError):
        return ""
    return str(payload.get("access_token", ""))


def _metric_value(metrics: dict[str, Any], name: str, field: str = "count", default: Any = None) -> Any:
    entry = metrics.get(name)
    if not isinstance(entry, dict):
        return default
    values = entry.get("values") or {}
    if field in values:
        return values.get(field)
    if field in entry:
        return entry.get(field)
    return default


def summarise(summary: dict[str, Any]) -> dict[str, Any]:
    metrics = summary.get("metrics") or {}
    return {
        "requests": _metric_value(metrics, "http_reqs", "count"),
        "request_rate_per_second": _metric_value(metrics, "http_reqs", "rate"),
        "failed_requests": _metric_value(metrics, "http_req_failed", "passes"),
        "successful_requests": _metric_value(metrics, "http_req_failed", "fails"),
        "failure_rate": _metric_value(metrics, "http_req_failed", "value"),
        "check_rate": _metric_value(metrics, "checks", "value"),
        "latency_avg_ms": _metric_value(metrics, "http_req_duration", "avg"),
        "latency_min_ms": _metric_value(metrics, "http_req_duration", "min"),
        "latency_p50_ms": _metric_value(metrics, "http_req_duration", "med"),
        "latency_p90_ms": _metric_value(metrics, "http_req_duration", "p(90)"),
        "latency_p95_ms": _metric_value(metrics, "http_req_duration", "p(95)"),
        "latency_p99_ms": _metric_value(metrics, "http_req_duration", "p(99)"),
        "latency_max_ms": _metric_value(metrics, "http_req_duration", "max"),
        "business_success": _metric_value(metrics, "business_success_total", "count"),
        "business_failure": _metric_value(metrics, "business_failure_total", "count", default=0),
        "oversell_responses": _metric_value(metrics, "oversell_attempts_total", "count", default=0),
        "iterations": _metric_value(metrics, "iterations", "count"),
        "iteration_rate_per_second": _metric_value(metrics, "iterations", "rate"),
    }


def run_profile(
    name: str,
    scale: int = 1,
    base_url: str = DEFAULT_BASE_URL,
    network: str | None = None,
    token: str | None = None,
    run_invariants: bool = True,
) -> dict[str, Any]:
    try:
        profile: Profile = resolve_profile(name, scale)
    except (KeyError, ValueError) as error:
        return {"status": "FAIL", "profile": name, "reason": str(error)}

    violations = enforce_safety(profile)
    if violations:
        return {
            "status": "BLOCKED",
            "profile": profile.name,
            "reason": "the safety boundary refused this plan",
            "violations": violations,
        }

    output = results_root() / (profile.name + "-" + str(int(time.time())))
    output.mkdir(parents=True, exist_ok=True)
    settle = wait_for_quiet_host()
    environment = host_snapshot()
    environment["host_settle"] = settle
    limiter = apply_load_test_rate_limits()
    coupon = resolve_coupon_code()
    if profile.scenario == "promotion-storm" and coupon.get("status") not in ("RESOLVED", "CONFIGURED"):
        return {
            "status": "BLOCKED",
            "profile": profile.name,
            "reason": "the profile measures many users redeeming one coupon, so an active coupon is required and "
                      + str(coupon.get("reason", "")),
            "coupon": coupon,
        }
    if limiter.get("status") == "ERROR":
        return {
            "status": "FAIL",
            "profile": profile.name,
            "reason": "the gateway could not be prepared for the measured run, so the run would have measured the "
                      "startup of the gateway instead of the platform",
            "rate_limit_override": limiter,
        }
    resolved_network = network or _docker_network()
    resolved_token = token if token is not None else acquire_token()

    customers: dict[str, Any] = {"status": "NOT_REQUIRED"}
    customer_tokens: list[str] = []
    if profile.scenario in ("full", "flash-sale", "promotion-storm"):
        customers = provision_customers(profile.virtual_users)
        if customers.get("status") != "READY":
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the profile places orders, and every virtual user needs its own customer because a cart "
                          "belongs to one customer, so the customer pool could not be provisioned: "
                          + str(customers.get("reason", "")),
                "customers": customers,
            }
        minted = mint_tokens(customers.get("usernames", []))
        if minted.get("status") != "READY":
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the access tokens for the load test customers could not be minted: "
                          + str(minted.get("reason", "")),
                "customers": customers,
            }
        customer_tokens = minted.get("tokens", [])

    command = [
        "docker",
        "compose",
        "--profile",
        "loadtest",
        "run",
        "--rm",
        "-T",
        "-v",
        str(output) + ":/results",
        "-e",
        "SCENARIO=" + profile.scenario,
        "-e",
        "RATE=" + str(profile.requests_per_second),
        "-e",
        "VUS=" + str(profile.virtual_users),
        "-e",
        "DURATION=" + str(profile.duration_seconds) + "s",
        "-e",
        "BASE_URL=" + base_url,
        "-e",
        "TOKEN=" + resolved_token,
        "-e",
        "CUSTOMER_TOKENS=" + ",".join(customer_tokens),
        "-e",
        "HOT_PRODUCT_ID=" + str(os.environ.get("HOT_PRODUCT_ID", "")),
        "-e",
        "COUPON_CODE=" + str(coupon.get("code", "")),
        "k6",
    ]

    started = time.time()
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            check=False,
            timeout=K6_RUN_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return {
            "status": "FAIL",
            "profile": profile.name,
            "reason": "the load run exceeded the harness timeout",
            "output": str(output),
        }
    elapsed = time.time() - started

    (output / "k6-stdout.log").write_text(result.stdout[-200000:], encoding="utf-8")
    (output / "k6-stderr.log").write_text(result.stderr[-200000:], encoding="utf-8")

    summary: dict[str, Any] = {}
    for candidate in ("k6-summary.json", "summary.json"):
        summary_path = output / candidate
        if not summary_path.exists():
            continue
        try:
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            summary = {}
        if summary:
            break

    document = {
        "status": "PASS" if result.returncode == 0 else "FAIL",
        "profile": profile.name,
        "scenario": profile.scenario,
        "scale": scale,
        "requests_per_second": profile.requests_per_second,
        "virtual_users": profile.virtual_users,
        "duration_seconds": profile.duration_seconds,
        "base_url": base_url,
        "network": resolved_network,
        "authenticated": bool(resolved_token),
        "k6_exit_code": result.returncode,
        "elapsed_seconds": round(elapsed, 2),
        "output": str(output),
        "measurement_host": environment,
        "measurement_valid": not environment.get("contaminated")
        and environment.get("host_settle", {}).get("status") == "QUIET",
        "max_business_failure_rate": profile.max_business_failure_rate,
        "rate_limit_override": limiter,
        "coupon": coupon,
        "customer_pool": {
            "status": customers.get("status"),
            "customers": len(customer_tokens),
        },
        "services": compose_services(),
        "metrics": summarise(summary),
    }
    if run_invariants:
        document["invariants"] = verify(scenario=profile.scenario)
    document["rate_limit_restore"] = restore_shipped_rate_limits()
    document["status"] = _final_status(document)
    (output / "result.json").write_text(json.dumps(document, indent=2, default=str), encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    return document


def _final_status(document: dict[str, Any]) -> str:
    if document.get("k6_exit_code") != 0:
        return "FAIL"
    if not document.get("measurement_valid"):
        return "BLOCKED"
    if not _business_outcome_is_acceptable(document):
        return "FAIL"
    invariants = document.get("invariants") or {}
    if invariants:
        if invariants.get("status") == "FAIL":
            return "FAIL"
        if invariants.get("status") == "BLOCKED":
            return "BLOCKED"
    return "PASS"


def _business_outcome_is_acceptable(document: dict[str, Any]) -> bool:
    metrics = document.get("metrics") or {}
    success = float(metrics.get("business_success") or 0)
    failure = float(metrics.get("business_failure") or 0)
    attempts = success + failure
    if attempts <= 0:
        document["business_verdict"] = (
            "no business request was recorded, so the run proves nothing about the business behaviour"
        )
        return False
    rate = failure / attempts
    document["business_failure_rate"] = round(rate, 6)
    limit = float(document.get("max_business_failure_rate") or 0.05)
    if success <= 0:
        document["business_verdict"] = (
            "every business request failed, so the platform served no successful business operation"
        )
        return False
    if rate > limit:
        document["business_verdict"] = (
            "the business failure rate " + format(rate, ".4f") + " exceeds the limit " + format(limit, ".4f")
        )
        return False
    document["business_verdict"] = (
        "the business failure rate " + format(rate, ".4f") + " is within the limit " + format(limit, ".4f")
    )
    return True
