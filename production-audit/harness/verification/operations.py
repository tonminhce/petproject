from __future__ import annotations

import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.request
from typing import Any

PROMETHEUS_URL = os.environ.get("AUDIT_PROMETHEUS_URL", "http://127.0.0.1:9095")
LOKI_URL = os.environ.get("AUDIT_LOKI_URL", "http://127.0.0.1:3100")
TEMPO_URL = os.environ.get("AUDIT_TEMPO_URL", "http://127.0.0.1:3200")
GRAFANA_URL = os.environ.get("AUDIT_GRAFANA_URL", "http://127.0.0.1:3000")
GRAFANA_USER = os.environ.get("GRAFANA_ADMIN_USER", "admin")


def _env_value(name: str) -> str:
    value = os.environ.get(name, "")
    if value:
        return value
    from .. import config

    env_file = config.repository_root() / ".env"
    if not env_file.exists():
        return ""
    for line in env_file.read_text().splitlines():
        stripped = line.strip()
        if stripped.startswith(name + "="):
            return stripped.split("=", 1)[1].split("#")[0].strip().strip('"').strip("'")
    return ""


GRAFANA_PASSWORD = _env_value("GRAFANA_ADMIN_PASSWORD")

SERVICE_TARGETS = (
    "gateway-service:8080",
    "auth-service:8088",
    "product-service:8086",
    "inventory-service:8082",
    "order-service:8084",
    "payment-service:8085",
    "shipping-service:8087",
    "favourite-service:8081",
    "media-service:8083",
    "tax-service:8091",
    "promotion-service:8093",
    "rating-service:8089",
    "search-service:8094",
    "notification-service:8090",
)

SECRET_PATTERN = re.compile(
    r"(eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"
    r"|whsec_[A-Za-z0-9]{16,}"
    r"|sk_(live|test)_[A-Za-z0-9]{16,}"
    r"|-----BEGIN [A-Z ]*PRIVATE KEY-----)",
    re.IGNORECASE,
)


def _get(url: str, authenticated: bool = False, timeout: int = 30) -> tuple[int, str]:
    request = urllib.request.Request(url)
    if authenticated:
        token = _basic_token()
        if token:
            request.add_header("Authorization", f"Basic {token}")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        return 0, str(error)


def _basic_token() -> str:
    if not GRAFANA_PASSWORD:
        return ""
    import base64

    raw = f"{GRAFANA_USER}:{GRAFANA_PASSWORD}".encode()
    return base64.b64encode(raw).decode()


def _json(url: str, authenticated: bool = False) -> dict[str, Any]:
    status, body = _get(url, authenticated=authenticated)
    if status != 200:
        return {}
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {}


def _prometheus_query(expression: str) -> list[dict[str, Any]]:
    payload = _json(f"{PROMETHEUS_URL}/api/v1/query?query={urllib.parse.quote(expression)}")
    return payload.get("data", {}).get("result", [])


def _loki_query(query: str, window_seconds: int = 3600) -> list[dict[str, Any]]:
    end = int(time.time())
    start = end - window_seconds
    url = (
        f"{LOKI_URL}/loki/api/v1/query_range?query={urllib.parse.quote(query)}"
        f"&start={start}&end={end}&limit=50"
    )
    payload = _json(url)
    return payload.get("data", {}).get("result", [])


def _tempo_traces(limit: int = 20) -> list[dict[str, Any]]:
    payload = _json(f"{TEMPO_URL}/api/search?limit={limit}")
    return payload.get("traces", [])


def check_metrics() -> dict[str, Any]:
    targets = _json(f"{PROMETHEUS_URL}/api/v1/targets?state=active").get("data", {})
    active = targets.get("activeTargets", [])
    up_services = [
        entry["labels"].get("instance")
        for entry in active
        if entry.get("health") == "up" and entry["labels"].get("job") == "petproject-services"
    ]
    request_series = _prometheus_query("http_server_requests_seconds_count")
    return {
        "id": "OBS-01",
        "name": "metrics",
        "passed": bool(request_series) and len(up_services) > 0,
        "detail": f"{len(up_services)} service targets up, {len(request_series)} request metric series",
        "evidence": {
            "service_targets_up": up_services,
            "request_series": [entry["metric"].get("application") for entry in request_series][:10],
        },
    }


def check_runtime_metrics() -> dict[str, Any]:
    heap = _prometheus_query("jvm_memory_used_bytes")
    cpu = _prometheus_query("system_cpu_usage")
    threads = _prometheus_query("jvm_threads_live_threads")
    return {
        "id": "OBS-07",
        "name": "runtime metrics",
        "passed": bool(heap) and bool(cpu) and bool(threads),
        "detail": f"heap series {len(heap)}, cpu series {len(cpu)}, thread series {len(threads)}",
    }


def check_logs() -> dict[str, Any]:
    streams = _loki_query('{container=~".+"}')
    samples = [values[1] for stream in streams for values in stream.get("values", [])]
    leaked = [sample for sample in samples if SECRET_PATTERN.search(sample)]
    return {
        "id": "OBS-02",
        "name": "centralized logs",
        "passed": bool(streams),
        "detail": f"{len(streams)} log streams, {len(samples)} samples",
        "evidence": {"containers": sorted({stream["stream"].get("container", "") for stream in streams})[:10]},
    }


def check_privacy() -> dict[str, Any]:
    streams = _loki_query('{container=~".+"}')
    samples = [values[1] for stream in streams for values in stream.get("values", [])]
    leaked = [sample for sample in samples if SECRET_PATTERN.search(sample)]
    return {
        "id": "OBS-08",
        "name": "no credential in the telemetry",
        "passed": not leaked,
        "detail": "no token, webhook secret or private key in the collected logs" if not leaked else f"{len(leaked)} leaks",
        "evidence": {
            "leak_count": len(leaked),
            "leaked_sample_sha256": [
                hashlib.sha256(sample.encode("utf-8")).hexdigest() for sample in leaked[:5]
            ],
        },
    }


def check_traces() -> dict[str, Any]:
    traces = _tempo_traces()
    services = sorted({trace.get("rootServiceName", "") for trace in traces if trace.get("rootServiceName")})
    return {
        "id": "OBS-03",
        "name": "distributed traces",
        "passed": bool(traces),
        "detail": f"{len(traces)} traces from {len(services)} services",
        "evidence": {"services": services[:10], "trace_ids": [trace.get("traceID") for trace in traces][:5]},
    }


def check_dashboards() -> dict[str, Any]:
    status, body = _get(f"{GRAFANA_URL}/api/search?type=dash-db", authenticated=True)
    if status != 200:
        return {
            "id": "OBS-06",
            "name": "dashboards",
            "passed": False,
            "detail": f"grafana search returned {status}",
        }
    try:
        dashboards = json.loads(body)
    except json.JSONDecodeError:
        dashboards = []
    panels = 0
    matched = False
    for dashboard in dashboards:
        if dashboard.get("uid") == "petproject-fleet-overview":
            matched = True
            detail = _json(f"{GRAFANA_URL}/api/dashboards/uid/{dashboard['uid']}", authenticated=True)
            panels = len(detail.get("dashboard", {}).get("panels", []))
    return {
        "id": "OBS-06",
        "name": "dashboards",
        "passed": matched and panels > 0,
        "detail": f"{len(dashboards)} dashboards provisioned, fleet overview has {panels} panels",
        "evidence": {"dashboard_titles": [dashboard.get("title") for dashboard in dashboards][:10]},
    }


def check_datasources() -> dict[str, Any]:
    datasources = _json(f"{GRAFANA_URL}/api/datasources", authenticated=True)
    healthy = []
    for datasource in datasources:
        url = f"{GRAFANA_URL}/api/datasources/uid/{datasource['uid']}/health"
        status, body = _get(url, authenticated=True)
        state = ""
        if status == 200:
            try:
                state = json.loads(body).get("status", "")
            except json.JSONDecodeError:
                state = ""
        healthy.append({"name": datasource.get("name"), "type": datasource.get("type"), "status": state})
    return {
        "id": "OBS-06b",
        "name": "datasources",
        "passed": bool(healthy) and all(entry["status"] == "OK" for entry in healthy),
        "detail": ", ".join(f"{entry['name']}={entry['status'] or 'unknown'}" for entry in healthy),
        "evidence": {"datasources": healthy},
    }


def run_checks() -> dict[str, Any]:
    checks = [
        check_metrics(),
        check_runtime_metrics(),
        check_logs(),
        check_traces(),
        check_dashboards(),
        check_datasources(),
        check_privacy(),
    ]
    passed = sum(1 for check in checks if check["passed"])
    return {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "checks": checks,
        "passed": passed,
        "total": len(checks),
        "status": "PASS" if passed == len(checks) else "FAIL",
        "not_verified": [
            "OBS-04 kafka producer and consumer spans are correlated by hand, not asserted automatically",
            "OBS-05 a controlled failure is visible in the backends but the check is not automated",
        ],
    }
