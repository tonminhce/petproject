from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .. import config
from ..persistence.store import utc_now, write_json
from ..telegram.client import PUBLIC_BOT_API_BASE, bot_api_base
from .delivery import (
    run_ci_alert_test,
    run_dedup_test,
    run_identity_probe,
    run_recovery_test,
    run_runtime_alert_test,
    run_security_probe,
)
from .state import load_alert_results

EVIDENCE_DIRECTORY_NAME = "alerts-results"
EVIDENCE_FILE_NAME = "delivery-verification.json"
EVIDENCE_REPORT_NAME = "delivery-verification.md"

STEP_RUNNERS = (
    ("identity-probe", run_identity_probe),
    ("ci-route", run_ci_alert_test),
    ("runtime-route", run_runtime_alert_test),
    ("recovery-notification", run_recovery_test),
    ("deduplication", run_dedup_test),
    ("security-probe", run_security_probe),
)

DELIVERY_GATES = ("QA-ALERT-00", "QA-ALERT-01", "QA-ALERT-02", "QA-ALERT-03")


def evidence_directory(run_id: str | None = None) -> Path:
    return config.run_root(run_id) / EVIDENCE_DIRECTORY_NAME


def run_delivery_verification(env: dict | None = None) -> dict[str, Any]:
    steps: list[dict[str, Any]] = []
    for name, runner in STEP_RUNNERS:
        result = runner(env)
        steps.append(
            {
                "step": name,
                "gate": result.get("gate"),
                "status": result.get("status"),
                "evidence": result.get("evidence", {}),
            }
        )
    return {
        "bot_api_base": bot_api_base(env),
        "steps": steps,
    }


def _status_summary(verification: dict[str, Any]) -> dict[str, str]:
    return {str(step["gate"]): str(step["status"]) for step in verification["steps"]}


def _render_report(document: dict[str, Any]) -> str:
    server = document.get("server", {})
    endpoint = str(document.get("bot_api_base", ""))
    lines = [
        "# Telegram alerting delivery verification",
        "",
        "- generated at: " + str(document.get("generated_at")),
        "- run: " + str(document.get("run_id")),
        "- bot api base address: " + endpoint,
        "- server under test: " + str(server.get("name", "unknown")),
        "- server version: " + str(server.get("version", "unknown")),
        "- server role: " + str(server.get("role", "unknown")),
        "- server notes: " + str(server.get("notes", "")),
        "- is the public telegram.org service: " + str(server.get("public_telegram_org", False)),
        "- telegram.org delivery verified: " + str(document.get("telegram_org_verified", False)),
        "",
        "The bot token and the chat identifiers were read from the environment and are not recorded in this file.",
        "Every chat identifier below is a sha256 fingerprint of the identifier that was used.",
        "",
        "## Steps",
        "",
    ]
    for step in document.get("steps", []):
        evidence = step.get("evidence", {})
        lines.append("### " + str(step.get("step")) + " (" + str(step.get("gate")) + ") - " + str(step.get("status")))
        lines.append("")
        for key in sorted(evidence):
            value = evidence[key]
            if key == "response":
                lines.append("- " + key + ":")
                lines.append("")
                lines.append("~~~json")
                lines.append(value)
                lines.append("~~~")
                lines.append("")
                continue
            lines.append("- " + key + ": " + str(value))
        lines.append("")
    lines.append("## Findings")
    lines.append("")
    for finding in document.get("findings", []):
        lines.append("### " + str(finding.get("title", "finding")))
        lines.append("")
        for key in sorted(finding):
            if key == "title":
                continue
            lines.append("- " + key + ": " + str(finding[key]))
        lines.append("")
    return chr(10).join(lines) + chr(10)


def write_delivery_evidence(
    run_id: str | None = None,
    env: dict | None = None,
    server: dict[str, Any] | None = None,
    findings: list[dict[str, Any]] | None = None,
    stem: str = EVIDENCE_FILE_NAME.replace(".json", ""),
) -> dict[str, Any]:
    verification = run_delivery_verification(env)
    endpoint = str(verification["bot_api_base"])
    statuses = _status_summary(verification)
    delivered = all(statuses.get(gate) == "PASS" for gate in DELIVERY_GATES)
    document = {
        "generated_at": utc_now(),
        "run_id": config.run_identifier(),
        "bot_api_base": endpoint,
        "public_bot_api": endpoint == PUBLIC_BOT_API_BASE,
        "telegram_org_verified": bool(delivered and endpoint == PUBLIC_BOT_API_BASE),
        "server": dict(server or {}),
        "findings": list(findings or []),
        "gate_statuses": statuses,
        "steps": verification["steps"],
        "recorded_alert_results": load_alert_results().get("results", []),
    }
    directory = evidence_directory(run_id)
    directory.mkdir(parents=True, exist_ok=True)
    write_json(directory / (stem + ".json"), document)
    (directory / (stem + ".md")).write_text(_render_report(document), encoding="utf-8")
    return document
