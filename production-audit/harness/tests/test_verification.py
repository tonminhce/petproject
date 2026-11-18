from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from harness.verification.gates import (
    FAIL,
    PASS,
    PENDING,
    CommandResult,
    Gate,
    completion_predicate,
    interpret_test_exit,
    run_command,
)
from harness.verification.suites import DEFAULT_SUITES, build_command, load_suites


class ExitCodeInterpretationTest(unittest.TestCase):
    def test_success_exit_code_is_pass(self) -> None:
        result = interpret_test_exit(0, "Tests run: 5, Failures: 0, Errors: 0, Skipped: 0")
        self.assertEqual(result["status"], PASS)
        self.assertEqual(result["tests_run"], 5)

    def test_failure_exit_code_is_fail(self) -> None:
        result = interpret_test_exit(1, "Tests run: 5, Failures: 2, Errors: 0, Skipped: 0")
        self.assertEqual(result["status"], FAIL)
        self.assertEqual(result["failures"], 2)

    def test_multiple_summaries_are_aggregated(self) -> None:
        payload = "Tests run: 3, Failures: 0, Errors: 0, Skipped: 0\nTests run: 4, Failures: 1, Errors: 0, Skipped: 0\n"
        result = interpret_test_exit(1, payload)
        self.assertEqual(result["tests_run"], 7)
        self.assertEqual(result["failures"], 1)

    def test_no_summary_produces_zero_counts(self) -> None:
        result = interpret_test_exit(0, "no test output")
        self.assertEqual(result["tests_run"], 0)


class CommandExecutionTest(unittest.TestCase):
    def test_successful_command_returns_zero(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = run_command(["/bin/sh", "-c", "exit 0"], Path(temporary))
            self.assertEqual(result.exit_code, 0)

    def test_failing_command_returns_its_code(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = run_command(["/bin/sh", "-c", "exit 3"], Path(temporary))
            self.assertEqual(result.exit_code, 3)

    def test_missing_binary_is_reported_not_raised(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = run_command(["definitely-not-a-real-binary-xyz"], Path(temporary))
            self.assertEqual(result.exit_code, 127)

    def test_log_file_is_written(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "logs" / "run.log"
            run_command(["/bin/sh", "-c", "echo hello"], Path(temporary), log)
            self.assertTrue(log.exists())
            self.assertIn("hello", log.read_text(encoding="utf-8"))

    def test_timeout_is_reported_as_124(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            result = run_command(["/bin/sh", "-c", "sleep 5"], Path(temporary), timeout=1)
            self.assertEqual(result.exit_code, 124)


class GateEvaluationTest(unittest.TestCase):
    def test_completion_predicate_is_false_without_gates(self) -> None:
        predicate = completion_predicate([])
        self.assertFalse(predicate["production_ready"])

    def test_completion_predicate_is_false_when_any_gate_fails(self) -> None:
        gates = [Gate(id="GATE-1", name="A", status=PASS)]
        gates += [Gate(id=f"GATE-{index}", name="B", status=PASS) for index in range(2, 23)]
        gates[3].status = FAIL
        predicate = completion_predicate(gates)
        self.assertFalse(predicate["production_ready"])
        self.assertIn("GATE-4", predicate["failing_gates"])

    def test_completion_predicate_is_true_when_all_gates_pass(self) -> None:
        gates = [Gate(id="GATE-1", name="A", status=PASS)]
        gates += [Gate(id=f"GATE-{index}", name="B", status=PASS) for index in range(2, 23)]
        predicate = completion_predicate(gates)
        self.assertTrue(predicate["production_ready"])
        self.assertEqual(predicate["failing_gates"], [])

    def test_pending_gate_blocks_completion(self) -> None:
        gates = [Gate(id="GATE-1", name="A", status=PASS)]
        gates += [Gate(id=f"GATE-{index}", name="B", status=PASS) for index in range(2, 23)]
        gates[5].status = PENDING
        predicate = completion_predicate(gates)
        self.assertFalse(predicate["production_ready"])


class SuiteDefinitionTest(unittest.TestCase):
    def test_required_suites_are_defined(self) -> None:
        for name in ("unit", "happy", "edge", "concurrency", "integration"):
            self.assertIn(name, DEFAULT_SUITES)

    def test_command_is_built_with_the_surefire_pattern(self) -> None:
        command = build_command("unit", DEFAULT_SUITES["unit"], Path("/repo"))
        self.assertIn("test", command)
        self.assertTrue(any(argument.startswith("-Dtest=") for argument in command))

    def test_integration_suite_uses_the_verify_lifecycle(self) -> None:
        command = build_command("integration", DEFAULT_SUITES["integration"], Path("/repo"))
        self.assertIn("verify", command)
        self.assertTrue(any(argument.startswith("-Dit.test=") for argument in command))

    def test_edge_suite_patterns_are_specific(self) -> None:
        patterns = DEFAULT_SUITES["edge"]["patterns"]
        self.assertTrue(any("Edge" in pattern for pattern in patterns))
        self.assertTrue(any("Negative" in pattern for pattern in patterns))

    def test_concurrency_suite_includes_integration_tests(self) -> None:
        self.assertTrue(DEFAULT_SUITES["concurrency"]["include_integration"])

    def test_suites_file_is_created_when_absent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            import harness.verification.suites as suites_module

            original = suites_module.SUITES_PATH
            suites_module.SUITES_PATH = Path(temporary) / "suites.json"
            try:
                suites = load_suites()
                self.assertIn("unit", suites)
                self.assertTrue(suites_module.SUITES_PATH.exists())
            finally:
                suites_module.SUITES_PATH = original


if __name__ == "__main__":
    unittest.main()
