from __future__ import annotations

import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness import yamlmini
from harness.alerts.messages import (
    build_ci_alert,
    build_resolved_notification,
    build_runtime_incident,
    contains_forbidden_field,
)
from harness.alerts.render import render_alertmanager_config
from harness.kubernetes.validation import (
    evaluate_autoscaling_observation,
    load_autoscaling_observation,
    manifest_fingerprint,
    validate_manifests,
)
from harness.telegram.client import (
    CredentialError,
    credential_status,
    redact,
    resolve_credentials,
    validate_bot_token,
    validate_chat_id,
)


class YamlSubsetParserTest(unittest.TestCase):
    def test_nested_mapping_and_sequence(self) -> None:
        document = yamlmini.loads("root:", "  child: value", "  items:", "    - one", "    - two")
        self.assertEqual(document["root"]["child"], "value")
        self.assertEqual(document["root"]["items"], ["one", "two"])

    def test_sequence_of_mappings(self) -> None:
        document = yamlmini.loads("items:", "  - name: first", "    size: 1", "  - name: second", "    size: 2")
        self.assertEqual(len(document["items"]), 2)
        self.assertEqual(document["items"][1]["name"], "second")
        self.assertEqual(document["items"][1]["size"], 2)

    def test_block_scalar_is_preserved(self) -> None:
        document = yamlmini.loads("message: |-", "  first line", "  second line")
        self.assertEqual(document["message"], "first line\nsecond line")

    def test_scalar_types(self) -> None:
        document = yamlmini.loads("count: 5", "ratio: 0.25", "enabled: true", "missing: null", "name: petproject")
        self.assertEqual(document["count"], 5)
        self.assertEqual(document["ratio"], 0.25)
        self.assertTrue(document["enabled"])
        self.assertIsNone(document["missing"])
        self.assertEqual(document["name"], "petproject")

    def test_comments_are_removed_outside_quotes(self) -> None:
        document = yamlmini.loads("value: keep # trailing comment", "other: \"a # b\"")
        self.assertEqual(document["value"], "keep")
        self.assertEqual(document["other"], "a # b")

    def test_flow_sequence(self) -> None:
        document = yamlmini.loads("targets: [\"a:9093\", \"b:9093\"]")
        self.assertEqual(document["targets"], ["a:9093", "b:9093"])


class TelegramCredentialTest(unittest.TestCase):
    def test_bot_token_format_is_enforced(self) -> None:
        with self.assertRaises(CredentialError):
            validate_bot_token("not-a-token")
        with self.assertRaises(CredentialError):
            validate_bot_token("")

    def test_valid_bot_token_is_accepted(self) -> None:
        validate_bot_token("123456789:AAEtesttesttesttesttesttesttesttesttest")

    def test_chat_id_format_is_enforced(self) -> None:
        with self.assertRaises(CredentialError):
            validate_chat_id("not-a-chat", "TELEGRAM_CHAT_ID")
        validate_chat_id("-1001234567890", "TELEGRAM_CHAT_ID")

    def test_credentials_resolve_from_environment(self) -> None:
        credentials = resolve_credentials(
            {
                "TELEGRAM_BOT_TOKEN": "123456789:AAEtesttesttesttesttesttesttesttesttest",
                "TELEGRAM_CHAT_ID": "-1001234567890",
                "TELEGRAM_CI_CHAT_ID": "-1009876543210",
            }
        )
        self.assertEqual(credentials.destination_for("runtime"), "-1001234567890")
        self.assertEqual(credentials.destination_for("ci"), "-1009876543210")

    def test_missing_credentials_are_reported(self) -> None:
        status = credential_status({})
        self.assertFalse(status["ready"])
        self.assertFalse(status["bot_token_present"])

    def test_redaction_removes_the_token(self) -> None:
        token = "123456789:AAEtesttesttesttesttesttesttesttesttest"
        redacted = redact("token " + token + " end", [token])
        self.assertNotIn(token, redacted)
        self.assertIn("[redacted]", redacted)


class AlertMessageTest(unittest.TestCase):
    def test_ci_alert_carries_every_required_field(self) -> None:
        message = build_ci_alert(
            environment="staging",
            branch="audit/run",
            commit_sha="abc123",
            workflow="CI Pipeline",
            job="Build",
            failed_stage="unit tests",
            failure_category="TestFailure",
            run_url="https://example.invalid/run",
            logs_url="https://example.invalid/logs",
            task_id="task-1",
        )
        for field in ("Environment", "Branch", "Commit SHA", "Workflow", "Job", "Failed stage", "Failure category", "Timestamp", "CI run", "Logs", "Status"):
            self.assertIn(field, message)

    def test_runtime_incident_carries_every_required_field(self) -> None:
        message = build_runtime_incident(
            severity="Critical",
            environment="production",
            service="order-service",
            issue="checkout failing",
        )
        for field in ("Severity", "Environment", "Service", "Issue", "Started", "Impact", "Current metric", "Runbook", "Grafana", "Logs", "Traces", "Status"):
            self.assertIn(field, message)

    def test_resolved_notification_reports_resolution(self) -> None:
        message = build_resolved_notification(
            severity="Critical",
            environment="staging",
            service="order-service",
            issue="checkout failing",
            started_at="2026-01-01T00:00:00Z",
            duration_minutes=12.5,
        )
        self.assertIn("resolved", message)
        self.assertIn("12.5", message)

    def test_sensitive_field_names_are_recognised(self) -> None:
        self.assertTrue(contains_forbidden_field("Authorization"))
        self.assertTrue(contains_forbidden_field("stripe_api_key"))
        self.assertFalse(contains_forbidden_field("service_name"))


class AlertmanagerRenderingTest(unittest.TestCase):
    def test_rendering_replaces_every_placeholder(self) -> None:
        rendered = render_alertmanager_config("123456789:AAEtesttesttesttesttesttesttesttesttest", "-1001234567890", "-1009876543210")
        self.assertNotIn("__TELEGRAM_BOT_TOKEN__", rendered)
        self.assertNotIn("__TELEGRAM_CHAT_ID__", rendered)
        self.assertNotIn("__TELEGRAM_CI_CHAT_ID__", rendered)
        self.assertIn("123456789:AAEtesttesttesttesttesttesttesttesttest", rendered)

    def test_the_message_template_only_uses_the_fields_of_the_alertmanager_data(self) -> None:
        rendered = render_alertmanager_config(
            "123456789:AAEtesttesttesttesttesttesttesttesttest", "-1001234567890", "-1009876543210")
        allowed = {
            "CommonLabels",
            "CommonAnnotations",
            "Status",
            "Alerts",
            "ExternalURL",
            "Receiver",
            "GroupLabels",
        }
        for match in re.finditer(r"\{\{([^}]*)\}\}", rendered):
            expression = match.group(1).strip()
            if expression.startswith("if ") or expression.startswith("end"):
                continue
            roots = re.findall(r"\.([A-Za-z][A-Za-z0-9]*)", expression)
            if not roots:
                continue
            root = roots[0]
            self.assertIn(
                root,
                allowed,
                "the alertmanager template uses an unknown field: " + expression,
            )

    def test_the_started_field_is_read_from_the_alerts(self) -> None:
        rendered = render_alertmanager_config(
            "123456789:AAEtesttesttesttesttesttesttesttesttest", "-1001234567890", "-1009876543210")
        self.assertNotIn("{{ .StartsAt }}", rendered)
        self.assertIn("(index .Alerts 0).StartsAt", rendered)


class KubernetesValidationTest(unittest.TestCase):
    def test_manifest_validation_reports_a_result(self) -> None:
        document = validate_manifests()
        self.assertIn("status", document)
        self.assertIn("checks", document)
        self.assertTrue(document["checks"])

    def test_every_check_carries_an_identifier_and_status(self) -> None:
        document = validate_manifests()
        for check in document["checks"]:
            self.assertTrue(check["id"])
            self.assertIn(check["status"], {"PASS", "FAIL", "BLOCKED"})


class AutoscalingManifestEvidenceTest(unittest.TestCase):
    manifest = """apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: gateway-service-hpa
  namespace: shop-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: gateway-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 75
"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.manifest_path = self.root / "k8s" / "gateway-service.yaml"
        self.manifest_path.parent.mkdir()
        self.manifest_path.write_text(self.manifest, encoding="utf-8")
        repository_root = patch("harness.kubernetes.validation.config.repository_root", return_value=self.root)
        repository_root.start()
        self.addCleanup(repository_root.stop)

    def _observation(self) -> dict:
        return {
            "manifest_fingerprint": manifest_fingerprint(),
            "revision": "ancestor-revision",
            "source_fingerprint": "old-source-fingerprint",
            "harness_fingerprint": "old-harness-fingerprint",
            "cluster": {"kubernetes_version": "v1.36.1", "cluster_name": "test-cluster"},
            "image": {"digest": "sha256:test-image"},
            "autoscaler_manifest_as_applied": {
                "metadata": {"name": "gateway-service-hpa", "namespace": "shop-system"},
                "spec": {
                    "scaleTargetRef": {"apiVersion": "apps/v1", "kind": "Deployment", "name": "gateway-service"},
                    "minReplicas": 2,
                    "maxReplicas": 10,
                    "metrics": [
                        {
                            "type": "Resource",
                            "resource": {
                                "name": "cpu",
                                "target": {"type": "Utilization", "averageUtilization": 75},
                            },
                        }
                    ],
                },
                "raw_yaml": self.manifest,
                "raw_yaml_sha256": hashlib.sha256(self.manifest.encode("utf-8")).hexdigest(),
            },
            "observations": {
                "before_load": {"replicas": 2, "observed_at": "2026-11-21T05:04:01Z", "cpu_utilization_percent": 3},
                "under_load": {"replicas": 10, "observed_at": "2026-11-21T05:07:47Z", "cpu_utilization_percent": 206},
                "after_load": {"replicas": 2, "observed_at": "2026-11-21T05:18:14Z", "cpu_utilization_percent": 1},
            },
            "verdict": {"scaled_up_under_real_load": True, "scaled_back_down_after_load": True},
            "load": {"total_requests": 1000, "rate_per_second": 100, "target": "gateway"},
        }

    def _load(self, observation: dict) -> dict:
        path = self.root / "run" / "k8s-results" / "autoscaling.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(observation), encoding="utf-8")
        with patch("harness.kubernetes.validation.observation_path", return_value=path):
            return load_autoscaling_observation()

    def test_fingerprint_is_deterministic_and_covers_manifest_paths_and_content(self) -> None:
        original = manifest_fingerprint()
        self.assertEqual(original, manifest_fingerprint())

        self.manifest_path.write_text(self.manifest + "# changed\n", encoding="utf-8")
        self.assertNotEqual(original, manifest_fingerprint())
        self.manifest_path.write_text(self.manifest, encoding="utf-8")
        self.assertEqual(original, manifest_fingerprint())

        additional = self.manifest_path.parent / "nested" / "config.yaml"
        additional.parent.mkdir()
        additional.write_text("kind: ConfigMap\n", encoding="utf-8")
        self.assertNotEqual(original, manifest_fingerprint())
        additional.rename(additional.parent / "renamed.yaml")
        self.assertNotEqual(original, manifest_fingerprint())
        (additional.parent / "renamed.yaml").unlink()
        self.assertEqual(original, manifest_fingerprint())
        yml = self.manifest_path.parent / "nested" / "config.yml"
        yml.write_text("kind: ConfigMap\n", encoding="utf-8")
        self.assertNotEqual(original, manifest_fingerprint())
        yml.unlink()
        self.assertEqual(original, manifest_fingerprint())

    def test_matching_fingerprint_accepts_an_ancestor_revision_and_ignores_non_k8s_changes(self) -> None:
        observation = self._observation()
        original = observation["manifest_fingerprint"]
        source = self.root / "gateway-service" / "src" / "Main.java"
        source.parent.mkdir(parents=True)
        source.write_text("class Main {}\n", encoding="utf-8")
        harness = self.root / "production-audit" / "harness" / "kubernetes" / "validation.py"
        harness.parent.mkdir(parents=True)
        harness.write_text("# changed harness\n", encoding="utf-8")

        self.assertEqual(original, manifest_fingerprint())
        result = evaluate_autoscaling_observation(observation)
        self.assertEqual(result["status"], "PASS")
        self.assertIn("manifest fingerprint: " + original, result["evidence"])

    def test_missing_fingerprint_blocks_direct_evaluation_and_loading(self) -> None:
        observation = self._observation()
        del observation["manifest_fingerprint"]

        direct = evaluate_autoscaling_observation(observation)
        loaded = self._load(observation)
        for result in (direct, loaded):
            self.assertEqual(result["status"], "BLOCKED")
            self.assertIn("manifest_fingerprint", result["reason"])

    def test_manifest_change_blocks_an_old_observation_with_a_refresh_reason(self) -> None:
        observation = self._observation()
        old_fingerprint = observation["manifest_fingerprint"]
        self.manifest_path.write_text(self.manifest.replace("maxReplicas: 10", "maxReplicas: 12"), encoding="utf-8")

        result = self._load(observation)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertIn(old_fingerprint, result["reason"])
        self.assertIn(manifest_fingerprint(), result["reason"])
        self.assertIn("record a fresh observation", result["reason"])

    def test_boolean_and_string_values_cannot_prove_load_scaling(self) -> None:
        observation = self._observation()
        observation["observations"]["under_load"]["cpu_utilization_percent"] = True
        observation["verdict"]["scaled_up_under_real_load"] = "false"
        observation["load"]["total_requests"] = True
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")

    def test_raw_yaml_must_parse_and_match_the_applied_object(self) -> None:
        observation = self._observation()
        raw = "not yaml: ["
        observation["autoscaler_manifest_as_applied"]["raw_yaml"] = raw
        observation["autoscaler_manifest_as_applied"]["raw_yaml_sha256"] = hashlib.sha256(raw.encode("utf-8")).hexdigest()
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")

    def test_wrong_controller_or_metric_spec_is_rejected(self) -> None:
        observation = self._observation()
        observation["autoscaler_manifest_as_applied"]["metadata"]["name"] = "other-hpa"
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")
        observation = self._observation()
        observation["autoscaler_manifest_as_applied"]["spec"]["metrics"][0]["resource"]["name"] = "memory"
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")

    def test_replica_bounds_timestamps_and_raw_hash_are_enforced(self) -> None:
        observation = self._observation()
        observation["observations"]["under_load"]["replicas"] = 11
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")
        observation = self._observation()
        observation["observations"]["under_load"]["observed_at"] = "2026-11-21T04:00:00Z"
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")
        observation = self._observation()
        observation["autoscaler_manifest_as_applied"]["raw_yaml_sha256"] = "bad"
        self.assertEqual(evaluate_autoscaling_observation(observation)["status"], "FAIL")

    def test_matching_fingerprint_does_not_bypass_observation_checks(self) -> None:
        observation = self._observation()
        observation["verdict"]["scaled_up_under_real_load"] = False
        result = evaluate_autoscaling_observation(observation)
        self.assertEqual(result["status"], "FAIL")
        self.assertIn("does not claim a scale up", " ".join(result["problems"]))
