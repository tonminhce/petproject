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
from .limits import apply_load_test_rate_limits, restore_shipped_rate_limits, wait_for_gateway_traffic
from .customers import ensure_token_lifetime, mint_tokens, provision_customers, restore_token_lifetime
from .settle import wait_for_quiet_host
from .targets import resolve_coupon_code
from .invariants import verify
from .profiles import (
    CUSTOMER_POOL_LIMIT,
    CUSTOMER_POOL_SCENARIOS,
    Profile,
    arrival_plan,
    enforce_safety,
    planned_iteration_rate,
    requests_per_iteration,
    resolve_profile,
)

DEFAULT_NETWORK = "untitled5_ecommerce-network"
DEFAULT_BASE_URL = "http://gateway-service:8080"
RATE_SHORTFALL_RATIO = 0.8
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
        "business_refusal": _metric_value(metrics, "business_refusal_total", "count", default=0),
        "shed_requests": _metric_value(metrics, "shed_requests_total", "count", default=0),
        "credential_error": _metric_value(metrics, "credential_error_total", "count", default=0),
        "unexpected_responses": _metric_value(metrics, "unexpected_response_total", "count", default=None),
        "platform_errors": _metric_value(metrics, "platform_error_total", "count", default=None),
        "coupon_contention": _metric_value(metrics, "coupon_contention_total", "count", default=0),
        "cart_contention": _metric_value(metrics, "cart_contention_total", "count", default=0),
        "cart_failure": _metric_value(metrics, "cart_failure_total", "count", default=0),
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
    document = _measure_profile(
        name,
        scale=scale,
        base_url=base_url,
        network=network,
        token=token,
        run_invariants=run_invariants,
    )
    document["rate_limit_restore"] = restore_shipped_rate_limits()
    return document


def _customer_pool(
    profile: Profile,
    customers: dict[str, Any],
    minted: dict[str, Any],
    tokens: list[str],
) -> dict[str, Any]:
    required = profile.scenario in CUSTOMER_POOL_SCENARIOS
    minted_count = len(tokens)
    return {
        "status": customers.get("status"),
        "required": required,
        "customers": minted_count,
        "customers_requested": profile.virtual_users if required else 0,
        "mint_failures": len(minted.get("failed") or []),
        "virtual_users": profile.virtual_users,
        "pool_limit": CUSTOMER_POOL_LIMIT,
        "share_factor": round(profile.virtual_users / minted_count, 4) if minted_count else 1.0,
        "one_customer_per_virtual_user": (not required) or minted_count >= profile.virtual_users,
    }


def _customer_pool_violations(profile: Profile, pool: dict[str, Any]) -> list[str]:
    if not pool.get("required"):
        return []
    return enforce_safety(profile, provisioned_customers=int(pool.get("customers") or 0))


def _customer_pool_shared(document: dict[str, Any]) -> str:
    pool = document.get("customer_pool") or {}
    if not pool.get("required"):
        return ""
    virtual_users = int(pool.get("virtual_users") or 0)
    minted = int(pool.get("customers") or 0)
    if minted < virtual_users:
        return (
            "the customer pool of " + str(minted) + " customers could not give the " + str(virtual_users)
            + " virtual users their own customer, a cart and a coupon allowance, so the run would have measured "
            "shared customers"
        )
    seen = pool.get("customer_tokens_in_load_generator")
    if isinstance(seen, int) and seen < virtual_users:
        return (
            "the load generator used a customer pool of " + str(seen) + " tokens for " + str(virtual_users)
            + " virtual users, so the run measured shared customers"
        )
    return ""


def _measure_profile(
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
    limiter = apply_load_test_rate_limits()
    environment = host_snapshot()
    environment["host_settle"] = settle
    if limiter.get("status") == "ERROR":
        return {
            "status": "FAIL",
            "profile": profile.name,
            "reason": "the gateway could not be prepared for the measured run, so the run would have measured the "
                      "startup of the gateway instead of the platform",
            "rate_limit_override": limiter,
        }
    coupon = resolve_coupon_code() if profile.scenario == "promotion-storm" else {"status": "NOT_APPLIED"}
    if profile.scenario == "promotion-storm" and coupon.get("status") not in ("RESOLVED", "CONFIGURED"):
        restore_shipped_rate_limits()
        return {
            "status": "BLOCKED",
            "profile": profile.name,
            "reason": "the profile measures many users redeeming one coupon, so an active coupon is required and "
                      + str(coupon.get("reason", "")),
            "coupon": coupon,
        }
    resolved_network = network or _docker_network()
    resolved_token = token if token is not None else acquire_token()

    traffic = wait_for_gateway_traffic(base_url, resolved_token)
    if traffic.get("status") != "READY":
        return {
            "status": "FAIL",
            "profile": profile.name,
            "reason": "the gateway did not serve an authenticated request, so the measurement would have counted "
                      "the startup of the gateway and of its key material as a business failure",
            "gateway_traffic": traffic,
            "rate_limit_override": limiter,
        }

    customers: dict[str, Any] = {"status": "NOT_REQUIRED"}
    customer_tokens: list[str] = []
    minted: dict[str, Any] = {"status": "NOT_REQUIRED"}
    lifetime: dict[str, Any] = {"status": "NOT_REQUIRED"}
    pool = _customer_pool(profile, customers, minted, customer_tokens)
    if profile.scenario in CUSTOMER_POOL_SCENARIOS:
        pool_size = profile.virtual_users
        required_lifetime = int(profile.duration_seconds) + 600
        lifetime = ensure_token_lifetime(seconds=required_lifetime)
        if lifetime.get("status") != "READY":
            restore_shipped_rate_limits()
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the lifetime of the customer tokens could not be set, so a long profile would measure "
                          "expired credentials instead of the platform: " + str(lifetime.get("reason", "")),
                "token_lifetime": lifetime,
                "rate_limit_override": limiter,
            }
        customers = provision_customers(pool_size)
        if customers.get("status") != "READY":
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the profile places orders, and every virtual user needs its own customer because a cart "
                          "belongs to one customer, so the customer pool could not be provisioned: "
                          + str(customers.get("reason", "")),
                "customers": customers,
                "rate_limit_override": limiter,
            }
        minted = mint_tokens(customers.get("usernames", []))
        customer_tokens = minted.get("tokens", [])
        pool = _customer_pool(profile, customers, minted, customer_tokens)
        if minted.get("status") != "READY":
            restore_shipped_rate_limits()
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the access tokens for the load test customers could not be minted: "
                          + str(minted.get("reason", "")),
                "customers": customers,
                "customer_pool": pool,
                "rate_limit_override": limiter,
            }

    (output / "customer-tokens.txt").write_text(
        "\n".join(customer_tokens) + ("\n" if customer_tokens else ""), encoding="utf-8"
    )

    if profile.scenario in CUSTOMER_POOL_SCENARIOS:
        pool_violations = _customer_pool_violations(profile, pool)
        if pool_violations:
            restore_shipped_rate_limits()
            return {
                "status": "BLOCKED",
                "profile": profile.name,
                "reason": "the profile places orders and every virtual user needs its own customer, a cart and a "
                          "coupon allowance, but the customer pool could not be provisioned, so the virtual users "
                          "would have shared a customer",
                "violations": pool_violations,
                "customer_pool": pool,
                "customers": customers,
                "rate_limit_override": limiter,
            }

    plan = arrival_plan(profile)
    planned_rate = planned_iteration_rate(profile)
    per_iteration = requests_per_iteration(profile.scenario)
    peak_iteration_rate = max(int(stage["target"]) for stage in plan)

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
        "RATE=" + str(peak_iteration_rate),
        "-e",
        "STAGES=" + json.dumps(plan, separators=(",", ":")),
        "-e",
        "VUS=" + str(profile.virtual_users),
        "-e",
        "DURATION=" + str(profile.duration_seconds) + "s",
        "-e",
        "BASE_URL=" + base_url,
        "-e",
        "TOKEN=" + resolved_token,
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

    pool["customer_tokens_in_load_generator"] = summary.get("customer_tokens")

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
        "max_shed_rate": profile.max_shed_rate,
        "target_requests_per_second": profile.requests_per_second,
        "target_virtual_users": profile.virtual_users,
        "rate_unit": "iterations per second",
        "requests_per_iteration": per_iteration,
        "target_iterations_per_second": peak_iteration_rate,
        "planned_iteration_rate_per_second": round(planned_rate, 4),
        "planned_request_rate_per_second": round(planned_rate * per_iteration, 4),
        "arrival_plan": plan,
        "rate_limit_override": limiter,
        "gateway_traffic": traffic,
        "coupon": coupon,
        "customer_pool": pool,
        "services": compose_services(),
        "metrics": summarise(summary),
    }
    if run_invariants:
        document["invariants"] = verify(scenario=profile.scenario)
    document["status"] = _final_status(document)
    (output / "result.json").write_text(json.dumps(document, indent=2, default=str), encoding="utf-8")
    (output / "summary.json").write_text(json.dumps(summary, indent=2, default=str), encoding="utf-8")
    return document


def _final_status(document: dict[str, Any]) -> str:
    shared = _customer_pool_shared(document)
    if shared:
        document["status_detail"] = shared
        return "BLOCKED"
    credentials = _credentials_expired(document)
    if credentials:
        document["status_detail"] = credentials
        return "BLOCKED"
    outcome_acceptable = _business_outcome_is_acceptable(document)
    if not outcome_acceptable:
        document["status_detail"] = document.get("business_verdict", "")
        return "FAIL"
    if document.get("k6_exit_code") != 0:
        document["status_detail"] = (
            "the load generator reported that a threshold was crossed, so the measured run did not meet its "
            "own acceptance criteria"
        )
        return "FAIL"
    if not document.get("measurement_valid"):
        document["status_detail"] = (
            "the measurement was taken while the host was busy, so it does not describe the platform"
        )
        return "BLOCKED"
    invariants = document.get("invariants") or {}
    if invariants:
        if invariants.get("status") == "FAIL":
            return "FAIL"
        if invariants.get("status") == "BLOCKED":
            return "BLOCKED"
    return "PASS"


def _credentials_expired(document: dict[str, Any]) -> str:
    metrics = document.get("metrics") or {}
    expired = float(metrics.get("credential_error") or 0)
    if expired <= 0:
        return ""
    return (
        "the customer credentials expired during the run: " + str(int(expired)) + " requests were refused with "
        "a status of unauthorised or forbidden, so the run measured the lifetime of the tokens and not the "
        "platform. The lifetime of the customer tokens must cover the whole profile."
    )


def _reconcile_with_load_generator(metrics: dict[str, Any]) -> dict[str, Any]:
    transport_failed = metrics.get("failed_requests")
    unexpected = metrics.get("unexpected_responses")
    platform_errors = metrics.get("platform_errors")
    failure = float(metrics.get("business_failure") or 0)
    credential = float(metrics.get("credential_error") or 0)
    shed = float(metrics.get("shed_requests") or 0)
    reasons: list[str] = []
    if unexpected is not None and transport_failed is not None and float(unexpected) != float(transport_failed):
        reasons.append(
            "the load generator counted " + format(float(transport_failed), ".0f") + " failed requests while the "
            "scenario counted " + format(float(unexpected), ".0f") + " responses outside the expected statuses, so "
            "the two accountings of the same run disagree"
        )
    if platform_errors is not None and float(platform_errors) > failure:
        reasons.append(
            "the platform answered " + format(float(platform_errors), ".0f") + " requests with a server error, but "
            "only " + format(failure, ".0f") + " business failures were booked, so a server error left the "
            "business failure budget"
        )
    if unexpected is not None and float(unexpected) > failure + credential:
        reasons.append(
            "the scenario received " + format(float(unexpected), ".0f") + " responses outside the expected "
            "statuses, but only " + format(failure, ".0f") + " business failures and " + format(credential, ".0f")
            + " credential errors were booked, so a response left both budgets"
        )
    return {
        "transport_failed_requests": transport_failed,
        "unexpected_responses": unexpected,
        "platform_errors": platform_errors,
        "gateway_sheds": shed,
        "business_failures": failure,
        "credential_errors": credential,
        "transport_failure_rate": metrics.get("failure_rate"),
        "consistent": not reasons,
        "reasons": reasons,
    }


def _business_outcome_is_acceptable(document: dict[str, Any]) -> bool:
    metrics = document.get("metrics") or {}
    success = float(metrics.get("business_success") or 0)
    failure = float(metrics.get("business_failure") or 0)
    refusals = float(metrics.get("business_refusal") or 0)
    shed = float(metrics.get("shed_requests") or 0)
    contention = float(metrics.get("coupon_contention") or 0) + float(metrics.get("cart_contention") or 0)
    attempts = success + failure
    if attempts <= 0 and refusals + shed + contention <= 0:
        document["business_verdict"] = (
            "no business request was recorded, so the run proves nothing about the business behaviour"
        )
        return False
    rate = failure / attempts if attempts > 0 else 0.0
    document["business_failure_rate"] = round(rate, 6)
    reconciliation = _reconcile_with_load_generator(metrics)
    document["load_generator_reconciliation"] = reconciliation
    document["transport_failure_rate"] = metrics.get("failure_rate")
    requests = float(metrics.get("requests") or 0)
    platform_errors = metrics.get("platform_errors")
    if platform_errors is not None and requests > 0:
        document["platform_error_rate"] = round(float(platform_errors) / requests, 6)
    if not reconciliation["consistent"]:
        document["business_verdict"] = "; ".join(reconciliation["reasons"])
        return False
    achieved = float(metrics.get("iteration_rate_per_second") or 0)
    planned = float(document.get("planned_iteration_rate_per_second") or 0)
    document["achieved_iteration_rate_per_second"] = round(achieved, 4)
    document["accepted_rate_ratio"] = RATE_SHORTFALL_RATIO
    if planned > 0:
        document["achieved_rate_ratio"] = round(achieved / planned, 4)
    recorded_metrics = document.get("metrics")
    if isinstance(recorded_metrics, dict):
        recorded_metrics["achieved_rate_ratio"] = document.get("achieved_rate_ratio")
        recorded_metrics["rate_unit"] = "iterations per second"
        recorded_metrics["planned_iteration_rate_per_second"] = document.get(
            "planned_iteration_rate_per_second"
        )
    served = success + failure + refusals + shed
    shed_rate = shed / served if served > 0 else 0.0
    document["shed_rate"] = round(shed_rate, 6)
    recorded_limit = document.get("max_business_failure_rate")
    limit = 0.05 if recorded_limit is None else float(recorded_limit)
    shed_limit = document.get("max_shed_rate")
    shed_limit = 0.01 if shed_limit is None else float(shed_limit)
    if success <= 0 and refusals + contention + shed > 0:
        document["business_verdict"] = (
            "the platform completed no business request: it refused "
            + format(refusals, ".0f")
            + " orders as a business refusal, refused "
            + format(contention, ".0f")
            + " as a contention on the cart or the coupon and shed "
            + format(shed, ".0f")
            + " requests, so it did not serve the requested load"
        )
        return False
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
    if shed_rate > shed_limit:
        document["business_verdict"] = (
            "the business failure rate "
            + format(rate, ".4f")
            + " is within the limit "
            + format(limit, ".4f")
            + " but the platform shed "
            + format(shed_rate, ".4f")
            + " of the requests, which exceeds the limit "
            + format(shed_limit, ".4f")
        )
        return False
    if planned > 0 and achieved / planned < RATE_SHORTFALL_RATIO:
        document["rate_shortfall"] = True
        document["business_verdict"] = (
            "the platform delivered only "
            + format(achieved / planned, ".2f")
            + " of the planned "
            + format(planned, ".2f")
            + " iterations per second, which is below the accepted "
            + format(RATE_SHORTFALL_RATIO, ".2f")
            + ", so it did not sustain the requested rate"
        )
        return False
    document["business_verdict"] = (
        "the business failure rate "
        + format(rate, ".4f")
        + " is within the limit "
        + format(limit, ".4f")
        + " and the platform shed "
        + format(shed_rate, ".4f")
        + " of the requests"
    )
    if planned > 0:
        document["business_verdict"] = (
            document["business_verdict"]
            + " and the platform delivered "
            + format(achieved / planned, ".2f")
            + " of the planned "
            + format(planned, ".2f")
            + " iterations per second"
        )
    return True
