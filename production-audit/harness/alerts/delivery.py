from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Any

from .. import config
from ..telegram.client import (
    CredentialError,
    DeliveryError,
    credentials_from_environment,
    send_message,
)
from .messages import build_ci_alert, build_resolved_notification, build_runtime_incident
from .state import record_alert_result

DEFAULT_ALERTMANAGER_URL = "http://127.0.0.1:9093"
DEDUP_SAMPLE_SIZE = 5


def _environment(env: dict | None) -> dict:
    return env if env is not None else dict(os.environ)


def _deliver(
    gate: str,
    message: str,
    route: str,
    env: dict | None,
    detail: str,
) -> dict[str, Any]:
    source = _environment(env)
    try:
        credentials = credentials_from_environment(source)
    except CredentialError as error:
        evidence = {"reason": str(error), "detail": detail}
        record_alert_result(gate, "BLOCKED", evidence)
        return {"gate": gate, "status": "BLOCKED", "evidence": evidence}
    try:
        response = send_message(message, credentials, route=route)
    except DeliveryError as error:
        evidence = {"reason": str(error), "detail": detail}
        record_alert_result(gate, "FAIL", evidence)
        return {"gate": gate, "status": "FAIL", "evidence": evidence}
    result = response.get("result") or {}
    evidence = {
        "detail": detail,
        "route": route,
        "chat_id": credentials.destination_for(route),
        "telegram_message_id": result.get("message_id"),
        "telegram_chat_id": result.get("chat", {}).get("id"),
        "telegram_date": result.get("date"),
        "message_length": len(message),
    }
    record_alert_result(gate, "PASS", evidence)
    return {"gate": gate, "status": "PASS", "evidence": evidence}


def run_ci_alert_test(env: dict | None = None) -> dict[str, Any]:
    message = build_ci_alert(
        environment="harness-verification",
        branch=str(_environment(env).get("GITHUB_REF_NAME", "audit/production-hardening")),
        commit_sha=str(_environment(env).get("GITHUB_SHA", "local-verification")),
        workflow=str(_environment(env).get("GITHUB_WORKFLOW", "harness alerts test-ci")),
        job=str(_environment(env).get("GITHUB_JOB", "harness-alerts")),
        failed_stage="harness alerts test-ci",
        failure_category="ControlledVerification",
        run_url="https://github.com/petproject/petproject/actions/runs/harness-verification",
        logs_url="https://github.com/petproject/petproject/actions/runs/harness-verification",
        task_id=str(_environment(env).get("HARNESS_RUN_ID", "harness-verification")),
    )
    return _deliver("QA-ALERT-01", message, "ci", env, "controlled CI failure notification delivered to the CI destination")


def run_runtime_alert_test(env: dict | None = None) -> dict[str, Any]:
    message = build_runtime_incident(
        severity="Critical",
        environment="harness-verification",
        service="harness-alert-verifier",
        issue="Controlled runtime alert fired by the harness to prove delivery",
        impact="none, this is a controlled verification message",
        current_metric="synthetic_value=1",
        status="firing",
    )
    return _deliver("QA-ALERT-02", message, "runtime", env, "controlled runtime incident delivered to the runtime destination")


def run_recovery_test(env: dict | None = None) -> dict[str, Any]:
    message = build_resolved_notification(
        severity="Critical",
        environment="harness-verification",
        service="harness-alert-verifier",
        issue="Controlled runtime alert fired by the harness to prove delivery",
        started_at="2026-01-01T00:00:00Z",
        duration_minutes=0.0,
    )
    return _deliver("QA-ALERT-03", message, "runtime", env, "recovery notification delivered")


def _post_alerts(alertmanager_url: str, alerts: list[dict[str, Any]], timeout: int = 10) -> None:
    body = json.dumps(alerts).encode("utf-8")
    request = urllib.request.Request(
        alertmanager_url.rstrip("/") + "/api/v2/alerts",
        data=body,
        method="POST",
    )
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        response.read()


def _fetch_alerts(alertmanager_url: str, timeout: int = 10) -> list[dict[str, Any]]:
    request = urllib.request.Request(alertmanager_url.rstrip("/") + "/api/v2/alerts", method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def run_dedup_test(env: dict | None = None) -> dict[str, Any]:
    source = _environment(env)
    alertmanager_url = str(source.get("ALERTMANAGER_URL", DEFAULT_ALERTMANAGER_URL))
    labels = {
        "alertname": "HarnessDeduplicationProbe",
        "severity": "warning",
        "category": "harness",
        "application": "harness-alert-verifier",
        "environment": "harness-verification",
    }
    fingerprint_labels = dict(labels)
    now = "2026-01-01T00:00:00Z"
    alerts = [
        {
            "labels": fingerprint_labels,
            "annotations": {
                "summary": "Controlled duplicate probe " + str(index),
                "metric": "probe=" + str(index),
            },
            "startsAt": now,
            "endsAt": "0001-01-01T00:00:00Z",
            "generatorURL": "http://harness.invalid/probe",
        }
        for index in range(DEDUP_SAMPLE_SIZE)
    ]
    try:
        _post_alerts(alertmanager_url, alerts)
        active = _fetch_alerts(alertmanager_url)
    except (urllib.error.URLError, OSError, json.JSONDecodeError) as error:
        evidence = {
            "reason": "alertmanager is not reachable at " + alertmanager_url + ": " + str(error),
            "detail": "grouping could not be proven against a running alertmanager",
        }
        record_alert_result("QA-ALERT-04", "BLOCKED", evidence)
        return {"gate": "QA-ALERT-04", "status": "BLOCKED", "evidence": evidence}
    matching = [
        item
        for item in active
        if (item.get("labels") or {}).get("alertname") == labels["alertname"]
    ]
    reported_count = int((matching[0].get("count") or 0)) if matching else 0
    collapsed = len(matching) == 1 and reported_count in (0, DEDUP_SAMPLE_SIZE)
    evidence = {
        "detail": "identical alerts must collapse into a single grouped alert",
        "submitted": DEDUP_SAMPLE_SIZE,
        "grouped_alerts": len(matching),
        "alertmanager_count": reported_count,
        "alertmanager_url": alertmanager_url,
    }
    status = "PASS" if collapsed else "FAIL"
    record_alert_result("QA-ALERT-04", status, evidence)
    return {"gate": "QA-ALERT-04", "status": status, "evidence": evidence}


def run_security_probe(env: dict | None = None) -> dict[str, Any]:
    source = _environment(env)
    try:
        credentials = credentials_from_environment(source)
    except CredentialError as error:
        evidence = {"reason": str(error)}
        record_alert_result("QA-ALERT-05", "BLOCKED", evidence)
        return {"gate": "QA-ALERT-05", "status": "BLOCKED", "evidence": evidence}
    probe = build_runtime_incident(
        severity="Warning",
        environment="harness-verification",
        service="harness-alert-verifier",
        issue="secret hygiene probe " + credentials.bot_token + " chat " + credentials.chat_id,
        impact="none, this is a controlled verification message",
        current_metric="synthetic_value=1",
    )
    from ..telegram.client import redact

    redacted = redact(probe, [credentials.bot_token, credentials.chat_id])
    leaked = credentials.bot_token in redacted or credentials.chat_id in redacted
    evidence = {
        "detail": "the redaction path must remove credentials before delivery",
        "token_leaked": leaked,
        "redacted_length": len(redacted),
    }
    status = "FAIL" if leaked else "PASS"
    record_alert_result("QA-ALERT-05", status, evidence)
    return {"gate": "QA-ALERT-05", "status": status, "evidence": evidence}
