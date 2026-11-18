from __future__ import annotations

import json
import os
import tempfile
import threading
import unittest
from pathlib import Path

from harness.persistence.store import read_json, write_json
from harness.task_registry.registry import (
    ALLOWED_TRANSITIONS,
    TASK_STATES,
    Task,
    TaskRegistryError,
    dependency_ready,
    load_tasks,
    next_task,
    save_tasks,
    transition,
    upsert_task,
    validate_task_id,
)


class TaskIdentityTest(unittest.TestCase):
    def test_valid_task_id(self) -> None:
        self.assertTrue(validate_task_id("INV-001"))
        self.assertTrue(validate_task_id("PAY-042"))

    def test_invalid_task_id(self) -> None:
        self.assertFalse(validate_task_id("inv-001"))
        self.assertFalse(validate_task_id("INV_001"))
        self.assertFalse(validate_task_id("INV-1"))
        self.assertFalse(validate_task_id(""))

    def test_unknown_task_state_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "tasks.json"
            task = Task(id="INV-001", title="x", state="NOT_A_STATE")
            with self.assertRaises(TaskRegistryError):
                upsert_task(task, path)


class TaskTransitionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "tasks.json"
        upsert_task(Task(id="INV-001", title="reserve stock"), self.path)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_illegal_transition_is_rejected(self) -> None:
        with self.assertRaises(TaskRegistryError):
            transition("INV-001", "VERIFIED", ["evidence"], self.path)

    def test_verified_requires_evidence(self) -> None:
        transition("INV-001", "NORMALIZED", [], self.path)
        transition("INV-001", "READY", [], self.path)
        transition("INV-001", "IN_PROGRESS", [], self.path)
        transition("INV-001", "IMPLEMENTED", [], self.path)
        transition("INV-001", "VERIFYING", [], self.path)
        with self.assertRaises(TaskRegistryError):
            transition("INV-001", "VERIFIED", [], self.path)
        updated = transition("INV-001", "VERIFIED", ["build passed"], self.path)
        self.assertEqual(updated.state, "VERIFIED")
        self.assertIn("build passed", updated.verification_evidence)

    def test_implemented_is_not_verified(self) -> None:
        transition("INV-001", "NORMALIZED", [], self.path)
        transition("INV-001", "READY", [], self.path)
        transition("INV-001", "IN_PROGRESS", [], self.path)
        updated = transition("INV-001", "IMPLEMENTED", [], self.path)
        self.assertEqual(updated.state, "IMPLEMENTED")
        self.assertNotEqual(updated.state, "VERIFIED")

    def test_verified_task_can_be_reopened(self) -> None:
        transition("INV-001", "NORMALIZED", [], self.path)
        transition("INV-001", "READY", [], self.path)
        transition("INV-001", "IN_PROGRESS", [], self.path)
        transition("INV-001", "IMPLEMENTED", [], self.path)
        transition("INV-001", "VERIFYING", [], self.path)
        transition("INV-001", "VERIFIED", ["evidence"], self.path)
        reopened = transition("INV-001", "REOPENED", ["regression"], self.path)
        self.assertEqual(reopened.state, "REOPENED")

    def test_unknown_task_transition_raises(self) -> None:
        with self.assertRaises(TaskRegistryError):
            transition("NOPE-001", "READY", [], self.path)

    def test_history_is_recorded(self) -> None:
        transition("INV-001", "NORMALIZED", ["note"], self.path)
        tasks = load_tasks(self.path)
        self.assertTrue(tasks["INV-001"].history)
        self.assertEqual(tasks["INV-001"].history[0]["to"], "NORMALIZED")

    def test_all_declared_states_have_transitions(self) -> None:
        for state in TASK_STATES:
            self.assertIn(state, ALLOWED_TRANSITIONS)


class TaskSchedulingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "tasks.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_dependency_ready_respects_blockers(self) -> None:
        upsert_task(Task(id="A-001", title="a", state="READY", priority=10), self.path)
        upsert_task(Task(id="B-001", title="b", state="READY", priority=5, dependencies=["A-001"]), self.path)
        ready = dependency_ready(load_tasks(self.path))
        self.assertEqual([task.id for task in ready], ["A-001"])

    def test_next_task_prefers_lowest_priority_number(self) -> None:
        upsert_task(Task(id="A-001", title="a", state="READY", priority=90), self.path)
        upsert_task(Task(id="B-001", title="b", state="READY", priority=10), self.path)
        self.assertEqual(next_task(self.path).id, "B-001")

    def test_next_task_returns_none_without_candidates(self) -> None:
        upsert_task(Task(id="A-001", title="a", state="DISCOVERED"), self.path)
        self.assertIsNone(next_task(self.path))


class PersistenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "tasks.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_state_survives_reload(self) -> None:
        upsert_task(Task(id="A-001", title="a", severity="HIGH"), self.path)
        reloaded = load_tasks(self.path)
        self.assertEqual(reloaded["A-001"].severity, "HIGH")

    def test_corrupt_state_file_is_tolerated(self) -> None:
        self.path.write_text("{not json", encoding="utf-8")
        self.assertEqual(load_tasks(self.path), {})

    def test_atomic_write_leaves_no_partial_file(self) -> None:
        write_json(self.path, {"value": 1})
        self.assertEqual(read_json(self.path), {"value": 1})
        leftovers = list(self.path.parent.glob(".harness-*"))
        self.assertEqual(leftovers, [])

    def test_crash_recovery_keeps_previous_document(self) -> None:
        write_json(self.path, {"value": 1})
        before = self.path.read_text(encoding="utf-8")
        self.path.write_text('{"value": ', encoding="utf-8")
        recovered = read_json(self.path, {"value": 0})
        self.assertEqual(recovered, {"value": 0})
        self.assertTrue(before)

    def test_concurrent_registry_writes_do_not_lose_tasks(self) -> None:
        errors: list[BaseException] = []

        def writer(index: int) -> None:
            try:
                upsert_task(Task(id=f"T-{index:03d}", title="t"), self.path)
            except BaseException as error:  # noqa: BLE001
                errors.append(error)

        threads = [threading.Thread(target=writer, args=(index,)) for index in range(12)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()

        self.assertEqual(errors, [])
        tasks = load_tasks(self.path)
        self.assertEqual(len(tasks), 12)

    def test_save_and_load_round_trip(self) -> None:
        tasks = {"A-001": Task(id="A-001", title="a", state="READY")}
        save_tasks(tasks, self.path)
        self.assertEqual(load_tasks(self.path)["A-001"].state, "READY")


if __name__ == "__main__":
    unittest.main()
