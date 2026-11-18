from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from typing import Any, Iterable

from .. import config
from ..persistence.store import read_json, read_modify_write_json, utc_now, write_json

TASK_STATES = (
    "DISCOVERED",
    "NORMALIZED",
    "READY",
    "IN_PROGRESS",
    "IMPLEMENTED",
    "VERIFYING",
    "VERIFIED",
    "FAILED_VERIFICATION",
    "REOPENED",
    "BLOCKED",
    "REQUIREMENT_CONFLICT",
)

ALLOWED_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "DISCOVERED": ("NORMALIZED", "BLOCKED", "REQUIREMENT_CONFLICT"),
    "NORMALIZED": ("READY", "BLOCKED", "REQUIREMENT_CONFLICT"),
    "READY": ("IN_PROGRESS", "BLOCKED"),
    "IN_PROGRESS": ("IMPLEMENTED", "FAILED_VERIFICATION", "BLOCKED"),
    "IMPLEMENTED": ("VERIFYING", "BLOCKED"),
    "VERIFYING": ("VERIFIED", "FAILED_VERIFICATION", "BLOCKED"),
    "VERIFIED": ("REOPENED",),
    "FAILED_VERIFICATION": ("REOPENED", "IN_PROGRESS", "BLOCKED"),
    "REOPENED": ("IN_PROGRESS", "READY", "BLOCKED"),
    "BLOCKED": ("READY", "DISCOVERED"),
    "REQUIREMENT_CONFLICT": ("NORMALIZED", "BLOCKED"),
}

TASK_ID_PATTERN = re.compile(r"^[A-Z][A-Z0-9]*-\d{2,3}$")


class TaskRegistryError(RuntimeError):
    pass


@dataclass
class Task:
    id: str
    title: str
    state: str = "DISCOVERED"
    severity: str = "MEDIUM"
    priority: int = 50
    affected_service: str = ""
    source_evidence: list[str] = field(default_factory=list)
    business_risk: str = ""
    technical_risk: str = ""
    current_implementation: str = ""
    target_implementation: str = ""
    design_pattern: str = ""
    acceptance_criteria: list[str] = field(default_factory=list)
    happy_path_tests: list[str] = field(default_factory=list)
    edge_case_tests: list[str] = field(default_factory=list)
    concurrency_tests: list[str] = field(default_factory=list)
    integration_e2e_coverage: list[str] = field(default_factory=list)
    dependencies: list[str] = field(default_factory=list)
    assigned_agent: str = ""
    independent_verifier: str = ""
    implementation_revision: str = ""
    git_commit_sha: str = ""
    push_state: str = "NOT_READY"
    verification_evidence: list[str] = field(default_factory=list)
    blockers: list[str] = field(default_factory=list)
    notes: str = ""
    created_at: str = field(default_factory=utc_now)
    updated_at: str = field(default_factory=utc_now)
    history: list[dict[str, str]] = field(default_factory=list)

    def to_document(self) -> dict[str, Any]:
        return asdict(self)

    @staticmethod
    def from_document(document: dict[str, Any]) -> "Task":
        known = {field_name for field_name in Task.__dataclass_fields__}
        filtered = {key: value for key, value in document.items() if key in known}
        return Task(**filtered)


def load_tasks(path: Any = None) -> dict[str, Task]:
    document = read_json(path or config.tasks_path(), {}) or {}
    raw_tasks = document.get("tasks", {}) if isinstance(document, dict) else {}
    tasks: dict[str, Task] = {}
    for key, value in raw_tasks.items():
        tasks[key] = Task.from_document(value)
    return tasks


def save_tasks(tasks: dict[str, Task], path: Any = None) -> Any:
    target = path or config.tasks_path()
    document = {
        "updated_at": utc_now(),
        "count": len(tasks),
        "tasks": {key: task.to_document() for key, task in sorted(tasks.items())},
    }
    return write_json(target, document)


def validate_task_id(task_id: str) -> bool:
    return bool(TASK_ID_PATTERN.match(task_id))


def upsert_task(task: Task, path: Any = None) -> Task:
    if not validate_task_id(task.id):
        raise TaskRegistryError(f"invalid task id: {task.id}")
    if task.state not in TASK_STATES:
        raise TaskRegistryError(f"invalid task state: {task.state}")

    def mutate(document: dict[str, Any]) -> dict[str, Any]:
        raw = document.get("tasks", {}) if isinstance(document, dict) else {}
        raw[task.id] = task.to_document()
        return {"updated_at": utc_now(), "count": len(raw), "tasks": raw}

    read_modify_write_json(path or config.tasks_path(), mutate, {"tasks": {}})
    return task


def transition(task_id: str, target_state: str, evidence: Iterable[str] = (), path: Any = None) -> Task:
    if target_state not in TASK_STATES:
        raise TaskRegistryError(f"unknown target state: {target_state}")

    captured: dict[str, Task] = {}

    def mutate(document: dict[str, Any]) -> dict[str, Any]:
        raw = document.get("tasks", {}) if isinstance(document, dict) else {}
        if task_id not in raw:
            raise TaskRegistryError(f"unknown task: {task_id}")
        task = Task.from_document(raw[task_id])
        if target_state == "VERIFIED" and not list(evidence):
            raise TaskRegistryError("VERIFIED requires verification evidence")
        if target_state != task.state and target_state not in ALLOWED_TRANSITIONS[task.state]:
            raise TaskRegistryError(f"illegal transition {task.state} -> {target_state}")
        history_entry = {
            "from": task.state,
            "to": target_state,
            "at": utc_now(),
            "evidence": "; ".join(evidence),
        }
        task.state = target_state
        task.updated_at = history_entry["at"]
        task.history.append(history_entry)
        if evidence:
            task.verification_evidence.extend(str(item) for item in evidence)
        raw[task_id] = task.to_document()
        captured[task_id] = task
        return {"updated_at": utc_now(), "count": len(raw), "tasks": raw}

    read_modify_write_json(path or config.tasks_path(), mutate, {"tasks": {}})
    return captured[task_id]


def dependency_ready(tasks: dict[str, Task]) -> list[Task]:
    ready: list[Task] = []
    for task in tasks.values():
        if task.state not in ("READY", "REOPENED"):
            continue
        blockers = [dep for dep in task.dependencies if dep in tasks and tasks[dep].state != "VERIFIED"]
        if not blockers:
            ready.append(task)
    ready.sort(key=lambda item: (item.priority, item.id))
    return ready


def next_task(path: Any = None) -> Task | None:
    tasks = load_tasks(path)
    candidates = dependency_ready(tasks)
    return candidates[0] if candidates else None


def task_status_summary(tasks: dict[str, Task]) -> dict[str, int]:
    summary = {state: 0 for state in TASK_STATES}
    for task in tasks.values():
        summary[task.state] = summary.get(task.state, 0) + 1
    return summary
