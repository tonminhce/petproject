from __future__ import annotations

from pathlib import Path
from typing import Any

from .. import config
from ..git_delivery.delivery import delivery_report
from ..inventory.coverage import coverage_summary, load_coverage
from ..persistence.store import read_json, utc_now, write_json
from ..task_registry.registry import load_tasks, task_status_summary
from ..verification.gates import completion_predicate, evaluate_gates


def gate_table(gates: list[Any]) -> str:
    lines = ["| Gate | Name | Status | Detail |", "| --- | --- | --- | --- |"]
    for gate in gates:
        detail = (gate.detail or gate.evidence[0] if gate.evidence else "").replace("|", "\\|")
        lines.append(f"| {gate.id} | {gate.name} | {gate.status} | {detail} |")
    return "\n".join(lines)


def status_document() -> dict[str, Any]:
    tasks = load_tasks()
    coverage = coverage_summary(load_coverage())
    manifest = read_json(config.source_manifest_path(), {}) or {}
    gates = evaluate_gates()
    predicate = completion_predicate(gates)
    return {
        "generated_at": utc_now(),
        "run_id": config.run_identifier(),
        "revision": manifest.get("revision", ""),
        "coverage": coverage,
        "tasks": {"count": len(tasks), "states": task_status_summary(tasks)},
        "gates": [gate.to_document() for gate in gates],
        "completion_predicate": predicate,
        "git_delivery": delivery_report(),
    }


def write_status_report(path: Path | None = None) -> Path:
    document = status_document()
    target = path or (config.reports_root() / "status.json")
    return write_json(target, document)


def write_markdown_status(path: Path | None = None) -> Path:
    document = status_document()
    target = path or (config.reports_root() / "status.md")
    lines = [
        "# Harness Status",
        "",
        f"- Generated: {document['generated_at']}",
        f"- Run: {document['run_id']}",
        f"- Revision: {document['revision']}",
        f"- Production ready: {document['completion_predicate']['production_ready']}",
        "",
        "## Source Coverage",
        "",
        f"- Total files: {document['coverage']['total_files']}",
        f"- Reviewed: {document['coverage']['reviewed_files']} ({document['coverage']['percent_reviewed']}%)",
        "",
        "## Task States",
        "",
    ]
    for state, count in sorted(document["tasks"]["states"].items()):
        if count:
            lines.append(f"- {state}: {count}")
    lines.extend(["", "## Production Readiness Gates", "", gate_table([GateView(gate) for gate in evaluate_gates()]), ""])
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines), encoding="utf-8")
    return target


class GateView:
    def __init__(self, gate: Any) -> None:
        self.id = gate.id
        self.name = gate.name
        self.status = gate.status
        self.detail = gate.detail
        self.evidence = gate.evidence
