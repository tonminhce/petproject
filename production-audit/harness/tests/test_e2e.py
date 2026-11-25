from __future__ import annotations

import json
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness.cli.main import record_e2e_suite
from harness.e2e.environment import (
    collect_scenarios,
    detect_dependencies,
    discover_collections,
    e2e_report,
    e2e_run,
    e2e_setup,
    e2e_teardown,
    _build_evidence,
    _finish_e2e_run,
    _newman_execution_count,
    _redact_newman_report,
    _validate_newman_evidence,
    _next_e2e_attempt,
)
from harness.e2e.postman_environment import write_postman_environment
from harness.persistence.store import sha256_file


class DependencyDetectionTest(unittest.TestCase):
    def test_detection_returns_a_status_per_component(self) -> None:
        statuses = detect_dependencies()
        names = {status.name for status in statuses}
        for expected in ("postgres", "redis", "kafka", "elasticsearch", "keycloak", "rustfs"):
            self.assertIn(expected, names)

    def test_missing_docker_is_reported_as_unavailable(self) -> None:
        statuses = detect_dependencies()
        for status in statuses:
            if not status.available:
                self.assertTrue(status.detail)


class CollectionDiscoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _collection(self, name: str) -> None:
        folder = self.root / "docs" / "postman"
        folder.mkdir(parents=True, exist_ok=True)
        document = {
            "info": {"schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
            "item": [
                {"name": "folder", "item": [{"name": "request", "event": [{"listen": "test", "script": {"exec": []}}]}]},
                {"name": "plain", "event": [{"listen": "test", "script": {"exec": []}}]},
            ],
        }
        (folder / name).write_text(json.dumps(document), encoding="utf-8")

    def test_collections_are_discovered_under_docs_postman(self) -> None:
        self._collection("flow.postman_collection.json")
        collections = discover_collections(self.root)
        self.assertEqual(len(collections), 1)

    def test_scenario_inventory_reports_test_scripts(self) -> None:
        self._collection("flow.postman_collection.json")
        scenarios = collect_scenarios(discover_collections(self.root))
        self.assertEqual(scenarios[0]["status"], "READABLE")
        self.assertTrue(scenarios[0]["has_test_scripts"])
        self.assertEqual(scenarios[0]["top_level_items"], 2)

    def test_postman_environment_is_private(self) -> None:
        document = {"environment": {"values": [{"key": "adminPass", "value": "secret"}]}, "missing_credentials": []}
        with patch("harness.e2e.postman_environment.build_postman_environment", return_value=document):
            path, _ = write_postman_environment(self.root)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_newman_exports_are_redacted_and_private(self) -> None:
        path = self.root / "newman.json"
        path.write_text(
            json.dumps(
                {
                    "run": {
                        "executions": [{}],
                        "failures": [],
                        "request": {
                            "headers": [{"key": "Authorization", "value": "Bearer secret-token"}],
                            "body": "password=secret-password",
                        },
                        "response": {
                            "body": "{\"accessToken\":\"access-secret\",\"refreshToken\":\"refresh-secret\",\"idToken\":\"id-secret\",\"clientSecret\":\"client-secret\"}"
                        },
                    }
                }
            ),
            encoding="utf-8",
        )
        _redact_newman_report(path)
        content = path.read_text(encoding="utf-8")
        self.assertNotIn("secret-token", content)
        self.assertNotIn("secret-password", content)
        self.assertNotIn("access-secret", content)
        self.assertNotIn("refresh-secret", content)
        self.assertNotIn("id-secret", content)
        self.assertNotIn("client-secret", content)
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_unreadable_collection_is_reported_not_ignored(self) -> None:
        folder = self.root / "docs" / "postman"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "broken.json").write_text("{not json", encoding="utf-8")
        scenarios = collect_scenarios(discover_collections(self.root))
        self.assertEqual(scenarios[0]["status"], "UNREADABLE")

    def test_newman_report_counts_only_real_executions(self) -> None:
        report = self.root / "newman.json"
        report.write_text(json.dumps({"run": {"executions": []}}), encoding="utf-8")
        self.assertEqual(_newman_execution_count(report), (0, ""))
        report.write_text(json.dumps({"run": {"executions": [{"item": {}}, {"item": {}}]}}), encoding="utf-8")
        self.assertEqual(_newman_execution_count(report), (2, ""))
        report.write_text("[]", encoding="utf-8")
        self.assertEqual(_newman_execution_count(report)[0], 0)
        self.assertTrue(_newman_execution_count(report)[1])
        report.write_text(json.dumps({"run": {"executions": [None], "failures": []}}), encoding="utf-8")
        self.assertEqual(_newman_execution_count(report)[0], 0)
        self.assertTrue(_newman_execution_count(report)[1])


class EnvironmentLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.expected_collections: list[str] = []
        self.run_id = "run-test"
        self.audit = Path(self.temporary.name) / "audit"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _patch_roots(self):
        import harness.config as config
        import harness.e2e.environment as environment

        original_audit = config.audit_root
        original_repo = config.repository_root
        config.audit_root = lambda: self.audit
        config.repository_root = lambda: self.root
        environment.config = config
        evidence_patch = patch("harness.e2e.environment._e2e_evidence", return_value=self._evidence())
        source_patch = patch("harness.e2e.environment._source_evidence", return_value=self._evidence())
        build_patch = patch("harness.e2e.environment._build_evidence", return_value={})
        evidence_patch.start()
        source_patch.start()
        build_patch.start()
        return original_audit, original_repo, (evidence_patch, source_patch, build_patch)

    def _restore_roots(self, originals) -> None:
        import harness.config as config
        import harness.e2e.environment as environment

        config.audit_root = originals[0]
        config.repository_root = originals[1]
        environment.config = config
        for active_patch in originals[2]:
            active_patch.stop()

    def _evidence(self) -> dict[str, str]:
        return {
            "revision": "current-revision",
            "source_fingerprint": "current-fingerprint",
            "harness_fingerprint": "current-harness",
            "compose_fingerprint": "current-compose",
        }

    def _write_run(
        self,
        name: str,
        state: str,
        scope: str,
        attempt: int,
        executed: int = 2,
        generated_at: str = "2026-11-25T00:00:00Z",
        evidence: dict[str, str] | None = None,
    ) -> Path:
        artifacts = self.audit / "runs" / self.run_id / "e2e-results"
        artifacts.mkdir(parents=True, exist_ok=True)
        collections = []
        if state == "COMPLETED":
            collections = [{"status": "PASSED", "executions": 1} for _ in range(executed)]
        elif state == "FAILED" and executed:
            collections = [{"status": "FAILED", "executions": 1}]
        self.expected_collections = []
        for index, entry in enumerate(collections):
            name = "collection-" + str(index)
            self.expected_collections.append(name)
            entry["collection"] = name
            entry["folders"] = []
            report = artifacts / f"newman-{scope}-{attempt}-{index}.json"
            report.write_text(json.dumps({"run": {"executions": [{}], "failures": []}}), encoding="utf-8")
            entry["newman_report"] = str(report)
            entry["newman_report_sha256"] = sha256_file(report)
        return _finish_e2e_run(
            artifacts,
            scope,
            attempt,
            {
                "state": state,
                "scope": scope,
                "attempt": attempt,
                "executed_collections": executed,
                "generated_at": generated_at,
                "collections": collections,
                "container_images": {"gateway-service": "sha256:test-image"},
                **(evidence or self._evidence()),
            },
        )

    def _report(self) -> dict:
        with patch("harness.e2e.environment._revision_is_contained", return_value=True), patch(
            "harness.e2e.environment.discover_collections",
            return_value=[Path(name) for name in self.expected_collections],
        ), patch("harness.e2e.environment.collection_folder_names", return_value=()), patch(
            "harness.e2e.environment._validate_newman_evidence", return_value=""
        ):
            return e2e_report(self.run_id)

    def test_setup_without_docker_is_blocked_not_failed(self) -> None:
        originals = self._patch_roots()
        try:
            document = e2e_setup(self.root, run_id=self.run_id)
            self.assertIn(document["state"], ("BLOCKED", "READY", "FAILED"))
            if document["state"] == "BLOCKED":
                self.assertIn("unavailable", document)
                self.assertTrue(document["unavailable"])
        finally:
            self._restore_roots(originals)

    def test_build_evidence_requires_a_current_full_verify_sequence(self) -> None:
        verification = self.root / "verification.json"
        build = {
            "status": "PASS",
            "mode": "clean-verify",
            "sequence": 7,
            "revision": "revision",
            "source_fingerprint": "current-fingerprint",
            "harness_fingerprint": "current-harness",
        }
        verification.write_text(json.dumps({"suites": {"build": build}}), encoding="utf-8")
        with patch("harness.e2e.environment.config.verification_path", return_value=verification), patch(
            "harness.e2e.environment._source_evidence", return_value=self._evidence()
        ), patch("harness.e2e.environment._revision_is_contained", return_value=True):
            self.assertEqual(_build_evidence(self.root)["build_sequence"], 7)
            build["mode"] = "validate"
            verification.write_text(json.dumps({"suites": {"build": build}}), encoding="utf-8")
            self.assertEqual(_build_evidence(self.root)["build_sequence"], "")

    def test_missing_required_scope_folder_is_rejected(self) -> None:
        document = {"scope": "checkout", "collections": []}
        with patch("harness.e2e.environment.discover_collections", return_value=[Path("collection")]), patch(
            "harness.e2e.environment.collection_folder_names", return_value=("1. Authentication",)
        ):
            error = _validate_newman_evidence(document, self.root, self.root)
        self.assertIn("required scope folders are missing", error)

    def test_run_without_setup_is_blocked(self) -> None:
        originals = self._patch_roots()
        try:
            environment_path = self.audit / "runs" / self.run_id / "e2e-results" / "postman-environment.json"
            environment_path.parent.mkdir(parents=True, exist_ok=True)
            environment_path.write_text("secret", encoding="utf-8")
            document = e2e_run("checkout", self.root, run_id=self.run_id)
            self.assertEqual(document["state"], "BLOCKED")
            self.assertIn("e2e environment is not ready", document["reason"])
            self.assertFalse(environment_path.exists())
        finally:
            self._restore_roots(originals)

    def test_report_collects_artifacts(self) -> None:
        originals = self._patch_roots()
        try:
            e2e_setup(self.root, run_id=self.run_id)
            report = e2e_report(self.run_id)
            self.assertIn("artifacts", report)
            self.assertIn("e2e_verified", report)
        finally:
            self._restore_roots(originals)

    def test_run_records_source_evidence_when_blocked(self) -> None:
        originals = self._patch_roots()
        try:
            with patch("harness.e2e.environment.source_fingerprint", return_value="current-fingerprint"), patch(
                "harness.e2e.environment.current_revision", return_value="current-revision"
            ):
                document = e2e_run("checkout", self.root, run_id=self.run_id)
            self.assertEqual(document["state"], "BLOCKED")
            self.assertEqual(document["source_fingerprint"], "current-fingerprint")
            self.assertEqual(document["revision"], "current-revision")
        finally:
            self._restore_roots(originals)

    def test_report_ignores_completed_artifacts_from_another_source(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run(
                "run-all.json",
                "COMPLETED",
                "all",
                1,
                evidence={**self._evidence(), "revision": "old-revision", "source_fingerprint": "old-fingerprint"},
            )
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["executed_collections"], 0)
            self.assertEqual(report["ignored_stale_completed"], 0)
        finally:
            self._restore_roots(originals)

    def test_report_accepts_current_completed_artifacts_only(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run(
                "run-old.json",
                "COMPLETED",
                "all",
                1,
                evidence={**self._evidence(), "revision": "old-revision", "source_fingerprint": "old-fingerprint"},
            )
            self._write_run("run-all.json", "COMPLETED", "all", 2)
            report = self._report()
            self.assertTrue(report["e2e_verified"])
            self.assertEqual(report["verified_scopes"], ["all"])
            self.assertEqual(report["executed_collections"], 2)
            self.assertEqual(report["ignored_stale_completed"], 0)
            self.assertEqual(set(report["evaluated_artifact_hashes"]), {"run-all-2.json"})
        finally:
            self._restore_roots(originals)

    def test_report_rejects_current_completed_artifact_without_executions(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all.json", "COMPLETED", "all", 1, executed=0)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["executed_collections"], 0)
            self.assertEqual(len(report["malformed_artifacts"]), 1)
        finally:
            self._restore_roots(originals)

    def test_newer_failed_all_run_supersedes_completed_scope(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-checkout.json", "COMPLETED", "checkout", 1)
            self._write_run("run-all.json", "FAILED", "all", 2)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["evaluated_artifacts"], ["run-all-2.json"])
            self.assertEqual(report["ignored_superseded_completed"], 0)
        finally:
            self._restore_roots(originals)

    def test_newer_failed_scope_invalidates_older_completed_all(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all.json", "COMPLETED", "all", 1)
            self._write_run("run-checkout.json", "FAILED", "checkout", 2)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["verified_scopes"], [])
            self.assertEqual(set(report["evaluated_artifacts"]), {"run-all-1.json", "run-checkout-2.json"})
        finally:
            self._restore_roots(originals)

    def test_newer_failed_scope_supersedes_older_completed_scope(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-checkout-old.json", "COMPLETED", "checkout", 1, executed=1)
            self._write_run("run-checkout.json", "FAILED", "checkout", 2, executed=1)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["evaluated_artifacts"], ["run-checkout-2.json"])
            self.assertEqual(report["ignored_superseded_completed"], 0)
        finally:
            self._restore_roots(originals)

    def test_partial_scope_coverage_cannot_pass(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-checkout-1.json", "COMPLETED", "checkout", 1)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertTrue(any("missing" in entry["reason"] for entry in report["malformed_artifacts"]))
        finally:
            self._restore_roots(originals)

    def test_running_marker_blocks_the_report(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-1.json", "COMPLETED", "all", 1)
            artifacts = self.audit / "runs" / self.run_id / "e2e-results"
            (artifacts / "running.json").write_text(json.dumps({"attempt": 2, "scope": "all"}), encoding="utf-8")
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertTrue(any(entry["file"] == "running.json" for entry in report["malformed_artifacts"]))
        finally:
            self._restore_roots(originals)

    def test_lower_attempt_cannot_overwrite_a_newer_failure(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-2.json", "FAILED", "all", 2)
            self._write_run("run-all-1.json", "COMPLETED", "all", 1)
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["evaluated_artifacts"], ["run-all-2.json"])
        finally:
            self._restore_roots(originals)

    def test_unledgered_newer_failure_invalidates_the_ledger(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-1.json", "COMPLETED", "all", 1)
            artifacts = self.audit / "runs" / self.run_id / "e2e-results"
            ledger_path = artifacts / "e2e-ledger.json"
            old_ledger = ledger_path.read_text(encoding="utf-8")
            self._write_run("run-all-2.json", "FAILED", "all", 2)
            ledger_path.write_text(old_ledger, encoding="utf-8")
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertTrue(any("high-water" in entry["reason"] for entry in report["malformed_artifacts"]))
        finally:
            self._restore_roots(originals)

    def test_newman_export_deletion_or_empty_rebinding_invalidates_the_run(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-1.json", "COMPLETED", "all", 1, executed=1)
            artifacts = self.audit / "runs" / self.run_id / "e2e-results"
            document = json.loads((artifacts / "run-all-1.json").read_text(encoding="utf-8"))
            entry = document["collections"][0]
            report_path = Path(entry["newman_report"])
            report_path.unlink()
            with patch(
                "harness.e2e.environment.discover_collections",
                return_value=[Path(entry["collection"])],
            ), patch("harness.e2e.environment.collection_folder_names", return_value=()):
                self.assertIn("missing or changed", _validate_newman_evidence(document, artifacts, self.root))
            report_path.write_text(json.dumps({"run": {"executions": [], "failures": []}}), encoding="utf-8")
            entry["newman_report_sha256"] = sha256_file(report_path)
            with patch(
                "harness.e2e.environment.discover_collections",
                return_value=[Path(entry["collection"])],
            ), patch("harness.e2e.environment.collection_folder_names", return_value=()):
                self.assertIn("execution count", _validate_newman_evidence(document, artifacts, self.root))
        finally:
            self._restore_roots(originals)

    def test_malformed_sequence_fails_the_report_without_raising(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-1.json", "COMPLETED", "all", 1)
            artifacts = self.audit / "runs" / self.run_id / "e2e-results"
            (artifacts / "attempt-sequence.json").write_text("{bad", encoding="utf-8")
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertTrue(any("sequence" in entry["reason"] for entry in report["malformed_artifacts"]))
        finally:
            self._restore_roots(originals)

    def test_sequence_is_reconciled_with_the_ledger_maximum(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all-5.json", "COMPLETED", "all", 5)
            artifacts = self.audit / "runs" / self.run_id / "e2e-results"
            (artifacts / "attempt-sequence.json").write_text("{bad", encoding="utf-8")
            self.assertEqual(_next_e2e_attempt(artifacts), 6)
        finally:
            self._restore_roots(originals)

    def test_harness_change_makes_an_artifact_stale(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run(
                "run-all.json",
                "COMPLETED",
                "all",
                1,
                evidence={**self._evidence(), "harness_fingerprint": "old-harness"},
            )
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["ignored_stale_completed"], 0)
        finally:
            self._restore_roots(originals)

    def test_malformed_newer_artifact_fails_closed(self) -> None:
        originals = self._patch_roots()
        try:
            self._write_run("run-all.json", "COMPLETED", "all", 1)
            path = self._write_run("run-checkout.json", "FAILED", "checkout", 2)
            path.write_text("{not json", encoding="utf-8")
            report = self._report()
            self.assertFalse(report["e2e_verified"])
            self.assertEqual(report["malformed_artifacts"][0]["file"], "run-checkout-2.json")
        finally:
            self._restore_roots(originals)

    def test_deleting_artifacts_invalidates_an_earlier_pass(self) -> None:
        originals = self._patch_roots()
        try:
            path = self._write_run("run-all.json", "COMPLETED", "all", 1)
            self.assertTrue(self._report()["e2e_verified"])
            path.unlink()
            self.assertFalse(self._report()["e2e_verified"])
        finally:
            self._restore_roots(originals)

    def test_suite_record_preserves_the_run_source_evidence(self) -> None:
        documents = (
            ("run", {"state": "COMPLETED", "executed_collections": 1}),
            ("report", {"e2e_verified": True, "executed_collections": 1}),
        )
        for action, document in documents:
            with patch("harness.cli.main.record_suite_result") as recorder:
                record_e2e_suite(
                    action,
                    {
                        **document,
                        "revision": "run-revision",
                        "source_fingerprint": "run-fingerprint",
                        "harness_fingerprint": "run-harness",
                    },
                )
            recorded = recorder.call_args.args[1]
            self.assertEqual(recorded["revision"], "run-revision")
            self.assertEqual(recorded["source_fingerprint"], "run-fingerprint")
            self.assertEqual(recorded["harness_fingerprint"], "run-harness")
            self.assertEqual(recorded["status"], "PENDING" if action == "run" else "PASS")

    def test_teardown_writes_a_result_document(self) -> None:
        originals = self._patch_roots()
        try:
            document = e2e_teardown(self.root, run_id=self.run_id)
            self.assertIn(document["state"], ("TORN_DOWN", "FAILED", "BLOCKED"))
        finally:
            self._restore_roots(originals)


if __name__ == "__main__":
    unittest.main()
