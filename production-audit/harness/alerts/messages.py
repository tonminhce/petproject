from __future__ import annotations

from datetime import datetime, timezone

PROJECT_NAME = "petproject-microservices-platform"
RUNBOOK_BASE = "https://github.com/petproject/petproject/blob/main/docs/PRODUCTION-READINESS.md"
GRAFANA_BASE = "http://localhost:3000"
LOKI_BASE = "http://localhost:3100"
TEMPO_BASE = "http://localhost:3200"

FORBIDDEN_FIELDS = ("authorization", "cookie", "password", "secret", "api_key", "apikey", "token", "card")


def escape(value: object) -> str:
    text = "" if value is None else str(value)
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def timestamp(value: datetime | None = None) -> str:
    moment = value or datetime.now(timezone.utc)
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def contains_forbidden_field(name: str) -> bool:
    lowered = name.strip().lower()
    return any(marker in lowered for marker in FORBIDDEN_FIELDS)


def build_ci_alert(
    environment: str,
    branch: str,
    commit_sha: str,
    workflow: str,
    job: str,
    failed_stage: str,
    failure_category: str,
    run_url: str,
    logs_url: str,
    task_id: str,
    started_at: str | None = None,
) -> str:
    lines = [
        "<b>CI FAILURE</b>",
        "<b>Severity:</b> Critical",
        "<b>Environment:</b> " + escape(environment),
        "<b>Project:</b> " + escape(PROJECT_NAME),
        "<b>Branch:</b> " + escape(branch),
        "<b>Commit SHA:</b> " + escape(commit_sha),
        "<b>Workflow:</b> " + escape(workflow),
        "<b>Job:</b> " + escape(job),
        "<b>Failed stage:</b> " + escape(failed_stage),
        "<b>Failure category:</b> " + escape(failure_category),
        "<b>Timestamp:</b> " + escape(started_at or timestamp()),
        "<b>Task ID:</b> " + escape(task_id),
        "<b>CI run:</b> " + escape(run_url),
        "<b>Logs:</b> " + escape(logs_url),
        "<b>Status:</b> firing",
    ]
    return "\n".join(lines)


def build_runtime_incident(
    severity: str,
    environment: str,
    service: str,
    issue: str,
    started_at: str | None = None,
    impact: str = "unknown",
    current_metric: str = "unknown",
    runbook: str = RUNBOOK_BASE,
    grafana: str = GRAFANA_BASE,
    logs: str = LOKI_BASE,
    traces: str = TEMPO_BASE,
    status: str = "firing",
) -> str:
    lines = [
        "<b>RUNTIME INCIDENT</b>",
        "<b>Severity:</b> " + escape(severity),
        "<b>Environment:</b> " + escape(environment),
        "<b>Service:</b> " + escape(service),
        "<b>Issue:</b> " + escape(issue),
        "<b>Started:</b> " + escape(started_at or timestamp()),
        "<b>Impact:</b> " + escape(impact),
        "<b>Current metric:</b> " + escape(current_metric),
        "<b>Runbook:</b> " + escape(runbook),
        "<b>Grafana:</b> " + escape(grafana),
        "<b>Logs:</b> " + escape(logs),
        "<b>Traces:</b> " + escape(traces),
        "<b>Status:</b> " + escape(status),
    ]
    return "\n".join(lines)


def build_resolved_notification(
    severity: str,
    environment: str,
    service: str,
    issue: str,
    started_at: str,
    resolved_at: str | None = None,
    duration_minutes: float | None = None,
) -> str:
    lines = [
        "<b>RUNTIME INCIDENT RESOLVED</b>",
        "<b>Severity:</b> " + escape(severity),
        "<b>Environment:</b> " + escape(environment),
        "<b>Service:</b> " + escape(service),
        "<b>Issue:</b> " + escape(issue),
        "<b>Started:</b> " + escape(started_at),
        "<b>Resolved:</b> " + escape(resolved_at or timestamp()),
    ]
    if duration_minutes is not None:
        lines.append("<b>Duration:</b> " + escape(str(round(duration_minutes, 2))) + " minutes")
    lines.append("<b>Status:</b> resolved")
    return "\n".join(lines)
