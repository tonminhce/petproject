from __future__ import annotations

import re
import unittest

from harness import yamlmini
from harness.alerts.messages import (
    build_ci_alert,
    build_resolved_notification,
    build_runtime_incident,
    contains_forbidden_field,
)
from harness.alerts.render import render_alertmanager_config
from harness.kubernetes.validation import validate_manifests
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
