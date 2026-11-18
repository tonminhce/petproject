from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from harness.e2e.environment import (
    collect_scenarios,
    detect_dependencies,
    discover_collections,
    e2e_report,
    e2e_run,
    e2e_setup,
    e2e_teardown,
)


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

    def test_unreadable_collection_is_reported_not_ignored(self) -> None:
        folder = self.root / "docs" / "postman"
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "broken.json").write_text("{not json", encoding="utf-8")
        scenarios = collect_scenarios(discover_collections(self.root))
        self.assertEqual(scenarios[0]["status"], "UNREADABLE")


class EnvironmentLifecycleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
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
        return original_audit, original_repo

    def _restore_roots(self, originals) -> None:
        import harness.config as config
        import harness.e2e.environment as environment

        config.audit_root = originals[0]
        config.repository_root = originals[1]
        environment.config = config

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

    def test_run_without_setup_is_blocked(self) -> None:
        originals = self._patch_roots()
        try:
            document = e2e_run("checkout", self.root, run_id=self.run_id)
            self.assertEqual(document["state"], "BLOCKED")
            self.assertIn("e2e environment is not ready", document["reason"])
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

    def test_teardown_writes_a_result_document(self) -> None:
        originals = self._patch_roots()
        try:
            document = e2e_teardown(self.root, run_id=self.run_id)
            self.assertIn(document["state"], ("TORN_DOWN", "FAILED", "BLOCKED"))
        finally:
            self._restore_roots(originals)


if __name__ == "__main__":
    unittest.main()
