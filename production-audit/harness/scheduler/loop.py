from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from ..persistence.store import utc_now
from ..task_registry.registry import Task, dependency_ready, load_tasks, task_status_summary
from ..verification.gates import completion_predicate, evaluate_gates


@dataclass
class LoopDecision:
    iteration: int
    action: str
    task_id: str
    reason: str
    production_ready: bool
    failing_gates: list[str]

    def to_document(self) -> dict[str, Any]:
        return {
            "iteration": self.iteration,
            "action": self.action,
            "task_id": self.task_id,
            "reason": self.reason,
            "production_ready": self.production_ready,
            "failing_gates": self.failing_gates,
            "at": utc_now(),
        }


def select_work(tasks: dict[str, Task] | None = None) -> Task | None:
    candidates = dependency_ready(tasks if tasks is not None else load_tasks())
    return candidates[0] if candidates else None


def advance(tasks: dict[str, Task] | None = None) -> LoopDecision:
    active = tasks if tasks is not None else load_tasks()
    gates = evaluate_gates()
    predicate = completion_predicate(gates)
    candidate = select_work(active)

    if candidate is None:
        action = "AWAIT_VERIFICATION"
        reason = "no dependency-ready task; remaining work requires verification evidence"
        task_id = ""
    else:
        action = "IMPLEMENT"
        reason = f"task {candidate.id} is dependency-ready with priority {candidate.priority}"
        task_id = candidate.id

    return LoopDecision(
        iteration=0,
        action=action,
        task_id=task_id,
        reason=reason,
        production_ready=predicate["production_ready"],
        failing_gates=predicate["failing_gates"],
    )


def run_loop(max_iterations: int = 20, tasks: dict[str, Task] | None = None) -> list[LoopDecision]:
    active = tasks if tasks is not None else load_tasks()
    decisions: list[LoopDecision] = []
    for iteration in range(1, max_iterations + 1):
        decision = advance(active)
        decision.iteration = iteration
        decisions.append(decision)
        if decision.action != "IMPLEMENT":
            break
    return decisions


def summarize(tasks: dict[str, Task] | None = None) -> dict[str, Any]:
    active = tasks if tasks is not None else load_tasks()
    gates = evaluate_gates()
    predicate = completion_predicate(gates)
    return {
        "generated_at": utc_now(),
        "task_states": task_status_summary(active),
        "ready_now": [task.id for task in dependency_ready(active)],
        "production_ready": predicate["production_ready"],
        "failing_gates": predicate["failing_gates"],
        "gates": [gate.to_document() for gate in gates],
    }
