from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from harness.inventory.coverage import coverage_summary, initialize_from_manifest, load_coverage, record_review
from harness.inventory.scanner import build_inventory, classify


class ClassificationTest(unittest.TestCase):
    def test_service_main_source_is_production_code(self) -> None:
        category, module = classify("order-service/src/main/java/com/shop/App.java", Path("."))
        self.assertEqual(category, "PRODUCTION_CODE")
        self.assertEqual(module, "order-service")

    def test_service_test_source_is_test_code(self) -> None:
        category, _ = classify("order-service/src/test/java/com/shop/AppTest.java", Path("."))
        self.assertEqual(category, "TEST_CODE")

    def test_shared_library_is_classified(self) -> None:
        category, module = classify("utils/common-core/src/main/java/A.java", Path("."))
        self.assertEqual(category, "SHARED_LIBRARY")
        self.assertEqual(module, "utils/common-core")

    def test_documentation_is_classified(self) -> None:
        category, _ = classify("docs/architecture.md", Path("."))
        self.assertEqual(category, "DOCUMENTATION")

    def test_infrastructure_is_classified(self) -> None:
        category, _ = classify("k8s/gateway-service.yaml", Path("."))
        self.assertEqual(category, "INFRASTRUCTURE")


class InventoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _make_module(self, name: str) -> None:
        source = self.root / name / "src" / "main" / "java" / "com" / "shop"
        source.mkdir(parents=True, exist_ok=True)
        (source / "App.java").write_text("package com.shop;\n\nclass App {}\n", encoding="utf-8")

    def test_inventory_discovers_sources_and_skips_target(self) -> None:
        self._make_module("order-service")
        build = self.root / "order-service" / "target" / "classes"
        build.mkdir(parents=True, exist_ok=True)
        (build / "App.class").write_text("binary", encoding="utf-8")
        manifest = build_inventory(self.root)
        paths = [entry["path"] for entry in manifest["files"]]
        self.assertIn("order-service/src/main/java/com/shop/App.java", paths)
        self.assertFalse(any("target" in path for path in paths))

    def test_inventory_records_hash_and_line_count(self) -> None:
        self._make_module("order-service")
        manifest = build_inventory(self.root)
        entry = manifest["files"][0]
        self.assertTrue(entry["sha256"])
        self.assertEqual(entry["lines"], 3)

    def test_inventory_hash_changes_when_content_changes(self) -> None:
        self._make_module("order-service")
        first = build_inventory(self.root)["files"][0]["sha256"]
        target = self.root / "order-service" / "src" / "main" / "java" / "com" / "shop" / "App.java"
        target.write_text("package com.shop;\n\nclass App { int a; }\n", encoding="utf-8")
        second = build_inventory(self.root)["files"][0]["sha256"]
        self.assertNotEqual(first, second)


class CoverageTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / "coverage.json"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_initialization_creates_discovered_entries(self) -> None:
        manifest = {"revision": "abc", "files": [{"path": "a.java", "sha256": "h", "module": "m", "category": "PRODUCTION_CODE", "language": "java", "lines": 10}]}
        document = initialize_from_manifest(manifest, self.path)
        self.assertEqual(document["files"]["a.java"]["coverage_state"], "DISCOVERED")

    def test_recording_a_review_updates_the_state(self) -> None:
        manifest = {"revision": "abc", "files": [{"path": "a.java", "sha256": "h", "module": "m", "category": "PRODUCTION_CODE", "language": "java", "lines": 10}]}
        initialize_from_manifest(manifest, self.path)
        entry = record_review("a.java", "FULLY_READ", [(1, 10)], reviewer="tester", path=self.path)
        self.assertEqual(entry["coverage_state"], "FULLY_READ")
        self.assertEqual(entry["reviewed_ranges"], [[1, 10]])

    def test_unknown_coverage_state_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            record_review("a.java", "MADE_UP", path=self.path)

    def test_summary_counts_reviewed_files(self) -> None:
        manifest = {
            "revision": "abc",
            "files": [
                {"path": "a.java", "sha256": "h", "module": "m", "category": "PRODUCTION_CODE", "language": "java", "lines": 1},
                {"path": "b.java", "sha256": "h", "module": "m", "category": "PRODUCTION_CODE", "language": "java", "lines": 1},
            ],
        }
        initialize_from_manifest(manifest, self.path)
        record_review("a.java", "SEMANTICALLY_REVIEWED", path=self.path)
        summary = coverage_summary(load_coverage(self.path))
        self.assertEqual(summary["total_files"], 2)
        self.assertEqual(summary["reviewed_files"], 1)


if __name__ == "__main__":
    unittest.main()
