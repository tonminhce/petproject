from __future__ import annotations

from typing import Any

from .. import config
from ..persistence.store import read_json, read_modify_write_json, utc_now, write_json


def alert_results_path():
    return config.state_root() / "alert-results.json"


def load_alert_results() -> dict[str, Any]:
    return read_json(alert_results_path(), {"results": [], "updated_at": utc_now()}) or {
        "results": [],
        "updated_at": utc_now(),
    }


def record_alert_result(gate: str, status: str, evidence: dict[str, Any]) -> dict[str, Any]:
    document = load_alert_results()
    entry = {
        "gate": gate,
        "status": status,
        "recorded_at": utc_now(),
        "evidence": evidence,
    }
    entries = [item for item in document.get("results", []) if item.get("gate") != gate]
    entries.append(entry)
    document["results"] = entries
    document["updated_at"] = utc_now()
    write_json(alert_results_path(), document)
    return entry


def alert_gate_statuses() -> dict[str, str]:
    document = load_alert_results()
    return {item.get("gate", "UNKNOWN"): item.get("status", "UNKNOWN") for item in document.get("results", [])}
