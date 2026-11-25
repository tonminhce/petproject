from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from harness.cli.main import _load_sweep_verdict, _new_load_attempt, command_load
from harness.loadtesting.invariants import INVARIANTS
from harness.reconciliation.reconcile import reconcile
from harness.verification.gates import (
    BLOCKED,
    FAIL,
    PASS,
    PENDING,
    CommandResult,
    Gate,
    _review_bindings_match,
    _review_candidate_revision,
    _review_verdict,
    completion_predicate,
    interpret_test_exit,
    load_verification,
    record_suite_result,
    run_command,
)
from harness.verification.operations import check_privacy
from harness.verification.suites import DEFAULT_SUITES, _execution_evidence, build_command, load_suites


class PrivacyEvidenceTest(unittest.TestCase):
    def test_privacy_evidence_contains_counts_and_digests_not_secret_text(self) -> None:
        secret = "whsec_" + "A" * 24
        sample = "request failed with webhook secret " + secret
        streams = [{"stream": {"container": "payment-service"}, "values": [["1", sample]]}]
        with patch("harness.verification.operations._loki_query", return_value=streams):
            result = check_privacy()

        self.assertFalse(result["passed"])
        self.assertEqual(result["evidence"]["leak_count"], 1)
        self.assertEqual(
            result["evidence"]["leaked_sample_sha256"],
            [hashlib.sha256(sample.encode("utf-8")).hexdigest()],
        )
        self.assertNotIn("leaked_samples", result["evidence"])
        self.assertNotIn(secret, json.dumps(result))
        self.assertNotIn(sample, json.dumps(result))

    def test_privacy_evidence_reports_zero_leaks_without_samples(self) -> None:
        streams = [{"stream": {"container": "gateway-service"}, "values": [["1", "request completed"]]}]
        with patch("harness.verification.operations._loki_query", return_value=streams):
            result = check_privacy()

        self.assertTrue(result["passed"])
        self.assertEqual(result["evidence"], {"leak_count": 0, "leaked_sample_sha256": []})


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
        gates += [Gate(id=f"GATE-{index}", name="B", status=PASS) for index in range(2, 26)]
        predicate = completion_predicate(gates)
        self.assertTrue(predicate["production_ready"])
        self.assertEqual(predicate["failing_gates"], [])

    def test_pending_gate_blocks_completion(self) -> None:
        gates = [Gate(id="GATE-1", name="A", status=PASS)]
        gates += [Gate(id=f"GATE-{index}", name="B", status=PASS) for index in range(2, 26)]
        gates[5].status = PENDING
        predicate = completion_predicate(gates)
        self.assertFalse(predicate["production_ready"])


class SuiteEvidenceTest(unittest.TestCase):
    def test_reconcile_does_not_reopen_verified_tasks_without_an_explicit_transition(self) -> None:
        task = SimpleNamespace(id="TASK-1", state="VERIFIED", notes="")
        with patch("harness.reconciliation.reconcile.current_revision", return_value="head"), patch(
            "harness.reconciliation.reconcile.load_coverage", return_value={}
        ), patch("harness.reconciliation.reconcile.load_tasks", return_value={task.id: task}), patch(
            "harness.task_registry.registry.task_status_summary", return_value={"VERIFIED": 1}
        ), patch(
            "harness.reconciliation.reconcile.detect_hash_drift",
            return_value={"drifted": [], "missing": [], "checked": 1},
        ), patch("harness.reconciliation.reconcile.read_json", return_value={"revision": "head"}), patch(
            "harness.reconciliation.reconcile.write_json"
        ):
            document = reconcile(Path("/repo"))
        self.assertTrue(document["stable"])
        self.assertEqual(document["reopened_tasks"], [])
        self.assertEqual(task.state, "VERIFIED")

    def test_execution_evidence_snapshots_revision_and_both_fingerprints(self) -> None:
        with patch("harness.reconciliation.reconcile.current_revision", return_value="revision"), patch(
            "harness.inventory.scanner.source_fingerprint", return_value="source"
        ), patch("harness.inventory.scanner.harness_fingerprint", return_value="harness"):
            with patch("harness.verification.gates.next_suite_sequence", return_value=42):
                evidence = _execution_evidence(Path("/repo"), "unit")
        self.assertEqual(evidence["revision"], "revision")
        self.assertEqual(evidence["source_fingerprint"], "source")
        self.assertEqual(evidence["harness_fingerprint"], "harness")
        self.assertEqual(evidence["sequence"], 42)

    def test_review_verdict_requires_one_exact_first_line(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "review.md"
            path.write_text("Verdict: FAIL\nquoted text: Verdict: PASS\n", encoding="utf-8")
            self.assertEqual(_review_verdict(path), "FAIL")
            path.write_text("Verdict: PASS\nVerdict: FAIL\n", encoding="utf-8")
            self.assertEqual(_review_verdict(path), "UNDECIDED")
            path.write_text("# Review\n## Verdict: PASS\n", encoding="utf-8")
            self.assertEqual(_review_verdict(path), "UNDECIDED")
            path.write_text("  Verdict: PASS  \n", encoding="utf-8")
            self.assertEqual(_review_verdict(path), "UNDECIDED")

    def test_review_fingerprint_bindings_are_exact_and_unambiguous(self) -> None:
        source = "a" * 64
        harness = "b" * 64
        text = "Source fingerprint: " + source + "\nHarness fingerprint: " + harness + "\n"
        self.assertTrue(_review_bindings_match(text, source, harness))
        self.assertFalse(_review_bindings_match("prose mentions " + source + " and " + harness, source, harness))
        self.assertFalse(_review_bindings_match(text + "Source fingerprint: " + "c" * 64 + "\n", source, harness))

    def test_review_candidate_revision_is_extracted(self) -> None:
        text = "# Final review\nCandidate reviewed: HEAD **b8858b39102586e25ad2b380addaf06114a12952**\nVerdict: PASS\n"
        self.assertEqual(
            _review_candidate_revision(text),
            "b8858b39102586e25ad2b380addaf06114a12952",
        )
        self.assertEqual(_review_candidate_revision("Verdict: PASS\n"), "")

    def test_recorded_source_evidence_is_not_restamped(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "verification.json"
            with patch("harness.inventory.scanner.source_fingerprint", return_value="new-fingerprint"):
                record_suite_result(
                    "e2e",
                    {
                        "suite": "e2e",
                        "status": PASS,
                        "revision": "run-revision",
                        "source_fingerprint": "run-fingerprint",
                    },
                    path,
                )
            recorded = load_verification(path)["suites"]["e2e"]
            self.assertEqual(recorded["revision"], "run-revision")
            self.assertEqual(recorded["source_fingerprint"], "run-fingerprint")


class LoadSweepVerdictTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.capacity = {"measured": True, "binding": {"binds_to_current_source": True}}
        self.evidence = {
            "revision": "current-revision",
            "source_fingerprint": "current-source",
            "harness_fingerprint": "current-harness",
        }

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _write_result(
        self,
        name: str,
        stamp: int = 1,
        status: str = PASS,
        valid: bool = True,
        invariants: str = PASS,
        evidence: dict[str, str] | None = None,
    ) -> None:
        directory = self.root / (name + "-" + str(stamp))
        directory.mkdir(parents=True, exist_ok=True)
        document = {
            "profile": name,
            "status": status,
            "measurement_valid": valid,
            "invariants": {
                "status": invariants,
                "failed": [],
                "passed": [item["id"] for item in INVARIANTS],
                "skipped": [],
                "invariants": [
                    {"id": item["id"], "status": "PASS", "violations": 0}
                    for item in INVARIANTS
                ],
            },
            "metrics": {
                "business_success": 1,
                "business_failure": 0,
                "business_refusal": 0,
                "shed_requests": 0,
                "requests": 4,
                "iterations": 1,
                "request_rate_per_second": 4,
                "iteration_rate_per_second": 1,
            },
            "k6_exit_code": 0,
            "load_generator_reconciliation": {"consistent": True},
            "rate_limit_restore": {"status": "READY"},
            "rate_unit": "iterations per second",
            "requests_per_iteration": 4,
            "target_iterations_per_second": 1,
            "planned_iteration_rate_per_second": 1,
            "planned_request_rate_per_second": 4,
            "arrival_plan": [],
            "transport_failure_rate": 0,
            "platform_error_rate": 0,
            **(evidence or self.evidence),
        }
        (directory / "result.json").write_text(json.dumps(document), encoding="utf-8")

    def _verdict(self) -> dict:
        with patch("harness.loadtesting.capacity.profile_names", return_value=("baseline", "ramp")), patch(
            "harness.loadtesting.capacity._revision_is_contained", return_value=True
        ):
            return _load_sweep_verdict(self.root, self.capacity, self.evidence)

    def test_all_profiles_and_capacity_must_pass(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp")
        self.assertEqual(self._verdict()["status"], PASS)

    def test_one_failed_profile_fails_the_sweep(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp", status=FAIL)
        verdict = self._verdict()
        self.assertEqual(verdict["status"], FAIL)
        self.assertEqual(verdict["failed"], ["ramp"])

    def test_one_blocked_profile_blocks_the_sweep(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp", status=BLOCKED)
        self.assertEqual(self._verdict()["status"], BLOCKED)

    def test_missing_profile_leaves_the_sweep_pending(self) -> None:
        self._write_result("baseline")
        self.assertEqual(self._verdict()["status"], PENDING)

    def test_invalid_measurement_or_invariants_fail_the_sweep(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp", valid=False)
        self.assertEqual(self._verdict()["status"], FAIL)
        self._write_result("ramp", stamp=2, invariants=FAIL)
        self.assertEqual(self._verdict()["status"], FAIL)

    def test_unbound_capacity_leaves_the_sweep_pending(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp")
        self.capacity["binding"]["binds_to_current_source"] = False
        self.assertEqual(self._verdict()["status"], PENDING)

    def test_newer_canonical_result_overrides_an_older_pass(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp", stamp=1)
        self._write_result("ramp", stamp=2, status=FAIL)
        self.assertEqual(self._verdict()["status"], FAIL)

    def test_known_failure_outranks_missing_profile(self) -> None:
        self._write_result("baseline", status=FAIL)
        self.assertEqual(self._verdict()["status"], FAIL)

    def test_missing_status_is_not_a_passing_result(self) -> None:
        self._write_result("baseline", status="MISSING")
        self._write_result("ramp")
        verdict = self._verdict()
        self.assertEqual(verdict["status"], PENDING)
        self.assertIn("baseline", verdict["missing"])

    def test_active_attempt_blocks_the_previous_sweep(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp")
        (self.root / "active-load.json").write_text(json.dumps({"profile": "spike"}), encoding="utf-8")
        verdict = self._verdict()
        self.assertEqual(verdict["status"], FAIL)
        self.assertEqual(verdict["active_attempt"]["profile"], "spike")

    def test_token_residue_fails_the_sweep(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp")
        residue = self.root / "baseline-1" / "customer-tokens.txt"
        residue.write_text("secret", encoding="utf-8")
        verdict = self._verdict()
        self.assertEqual(verdict["status"], FAIL)
        self.assertEqual(verdict["token_residue"], ["baseline-1/customer-tokens.txt"])

    def test_malformed_active_attempt_fails_closed(self) -> None:
        self._write_result("baseline")
        self._write_result("ramp")
        (self.root / "active-load.json").write_text("[]", encoding="utf-8")
        self.assertEqual(self._verdict()["status"], FAIL)

    def test_attempt_ids_are_monotonic_above_future_residue(self) -> None:
        future = self.root / "baseline-9999999999"
        future.mkdir()
        attempt = _new_load_attempt(self.root, "baseline")
        self.assertGreater(int(attempt.name.rsplit("-", 1)[-1]), 9999999999)

    def test_stale_result_cannot_pass(self) -> None:
        self._write_result("baseline")
        self._write_result(
            "ramp",
            evidence={**self.evidence, "source_fingerprint": "old-source"},
        )
        verdict = self._verdict()
        self.assertEqual(verdict["status"], PENDING)
        self.assertEqual(verdict["stale"], ["ramp"])

    def test_interrupted_profile_persists_fail_and_clears_active_marker(self) -> None:
        arguments = SimpleNamespace(action="baseline", scale=1, base_url="http://gateway:8080")
        with patch("harness.loadtesting.runner.results_root", return_value=self.root), patch(
            "harness.loadtesting.runner.run_profile", side_effect=RuntimeError("interrupted")
        ) as run, patch("harness.loadtesting.capacity.current_evidence", return_value=self.evidence), patch(
            "harness.loadtesting.capacity.record_capacity_limit"
        ), patch("harness.cli.main.record_suite_result") as recorder, patch(
            "harness.cli.main.config.events_path", return_value=self.root / "events.jsonl"
        ), patch("harness.cli.main.config.state_root", return_value=self.root / "state"):
            with self.assertRaises(RuntimeError):
                command_load(arguments)
        results = list(self.root.glob("baseline-*/result.json"))
        self.assertEqual(len(results), 1)
        document = json.loads(results[0].read_text(encoding="utf-8"))
        self.assertEqual(document["status"], FAIL)
        self.assertEqual(document["source_fingerprint"], "current-source")
        self.assertFalse((self.root / "active-load.json").exists())
        self.assertEqual(Path(run.call_args.kwargs["output"]), results[0].parent)
        recorded = {call.args[0]: call.args[1] for call in recorder.call_args_list}
        self.assertEqual(recorded["performance"]["status"], FAIL)

    def test_individual_profile_never_records_performance_pass(self) -> None:
        arguments = SimpleNamespace(action="baseline", scale=1, base_url="http://gateway:8080")
        with patch("harness.loadtesting.runner.results_root", return_value=self.root), patch(
            "harness.loadtesting.runner.run_profile",
            return_value={
                "status": PASS,
                "profile": "baseline",
                "output": str(self.root / "baseline-9"),
                "measurement_valid": True,
                "invariants": {"status": PASS},
            },
        ), patch("harness.loadtesting.capacity.current_evidence", return_value=self.evidence), patch(
            "harness.loadtesting.capacity.record_capacity_limit"
        ), patch("harness.cli.main.record_suite_result") as recorder, patch(
            "harness.cli.main.config.events_path", return_value=self.root / "events.jsonl"
        ), patch("harness.cli.main.config.state_root", return_value=self.root / "state"):
            command_load(arguments)
        recorded = {call.args[0]: call.args[1] for call in recorder.call_args_list}
        self.assertEqual(recorded["load_baseline"]["status"], PASS)
        self.assertEqual(recorded["performance"]["status"], PENDING)


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
