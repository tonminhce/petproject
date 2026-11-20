from __future__ import annotations

import json
import unittest

from harness.stripe.sandbox import (
    classify_key,
    classify_webhook_secret,
    load_credentials,
    sign_payload,
    test_webhooks,
    verify_configuration,
    verify_webhook_signature,
)


class StripeCredentialClassificationTest(unittest.TestCase):
    def test_live_key_is_detected(self) -> None:
        self.assertEqual(classify_key("sk_live_abc"), "live")
        self.assertEqual(classify_key("rk_live_abc"), "live")

    def test_test_key_is_detected(self) -> None:
        self.assertEqual(classify_key("sk_test_" + "a" * 24), "test")

    def test_a_placeholder_test_key_is_not_treated_as_a_real_key(self) -> None:
        self.assertEqual(classify_key("sk_test_abc"), "placeholder")
        self.assertEqual(classify_key("sk_test_placeholder"), "placeholder")
        self.assertEqual(classify_key("sk_test_local_e2e_only"), "placeholder")

    def test_missing_and_unrecognised_keys(self) -> None:
        self.assertEqual(classify_key(""), "missing")
        self.assertEqual(classify_key("nonsense"), "unrecognised")

    def test_webhook_secret_placeholder_is_detected(self) -> None:
        self.assertEqual(classify_webhook_secret(""), "missing")
        self.assertEqual(classify_webhook_secret("whsec_local_e2e_only"), "placeholder")
        self.assertEqual(classify_webhook_secret("whsec_" + "a" * 32), "genuine")


class StripeWebhookSignatureTest(unittest.TestCase):
    def test_a_correct_signature_is_accepted(self) -> None:
        secret = "whsec_" + "b" * 32
        payload = json.dumps({"id": "evt_1"}).encode("utf-8")
        import time

        header = sign_payload(payload, secret, int(time.time()))
        result = verify_webhook_signature(payload, header, secret)
        self.assertTrue(result["valid"])

    def test_a_tampered_payload_is_rejected(self) -> None:
        secret = "whsec_" + "c" * 32
        payload = json.dumps({"id": "evt_1"}).encode("utf-8")
        import time

        header = sign_payload(payload, secret, int(time.time()))
        result = verify_webhook_signature(payload + b"x", header, secret)
        self.assertFalse(result["valid"])

    def test_a_stale_timestamp_is_rejected(self) -> None:
        secret = "whsec_" + "d" * 32
        payload = json.dumps({"id": "evt_1"}).encode("utf-8")
        import time

        header = sign_payload(payload, secret, int(time.time()) - 5000)
        result = verify_webhook_signature(payload, header, secret)
        self.assertFalse(result["valid"])

    def test_a_malformed_header_is_rejected(self) -> None:
        result = verify_webhook_signature(b"{}", "garbage", "whsec_" + "e" * 32)
        self.assertFalse(result["valid"])


class StripeConfigurationGateTest(unittest.TestCase):
    def test_a_missing_credential_blocks_the_gate(self) -> None:
        document = verify_configuration(credentials={"secret_key": "", "webhook_secret": "", "provider": "stripe", "sandbox": True, "api_base": "https://api.stripe.com", "payment_method": "pm_card_visa"})
        self.assertIn(document["status"], {"FAIL", "BLOCKED"})
        self.assertIn("secret_key_present", document["failed"])

    def test_a_live_key_is_rejected(self) -> None:
        document = verify_configuration(credentials={"secret_key": "sk_live_definitely_not_allowed", "webhook_secret": "", "provider": "stripe", "sandbox": True, "api_base": "https://api.stripe.com", "payment_method": "pm_card_visa"})
        self.assertIn("secret_key_mode", document["failed"])

    def test_a_placeholder_webhook_secret_is_rejected(self) -> None:
        document = verify_configuration(
            credentials={
                "secret_key": "sk_test_" + "f" * 24,
                "webhook_secret": "whsec_local_e2e_only",
                "provider": "stripe",
                "sandbox": True,
                "api_base": "https://api.stripe.com",
                "payment_method": "pm_card_visa",
            }
        )
        self.assertIn("webhook_secret", document["failed"])


class StripeCredentialLoadingTest(unittest.TestCase):
    def test_the_environment_wins_over_the_dotenv_file(self) -> None:
        credentials = load_credentials({"STRIPE_SECRET_KEY": "sk_test_from_environment"})
        self.assertEqual(credentials["secret_key"], "sk_test_from_environment")

    def test_the_provider_defaults_to_stripe(self) -> None:
        credentials = load_credentials(
            {
                "STRIPE_SECRET_KEY": "sk_test_x",
                "SHOP_PAYMENT_STRIPE_SANDBOX": "true",
            }
        )
        self.assertEqual(credentials["provider"], "stripe")
        self.assertTrue(credentials["sandbox"])

    def test_the_sandbox_flag_is_read_from_the_configuration(self) -> None:
        credentials = load_credentials({"STRIPE_SECRET_KEY": "sk_test_x", "SHOP_PAYMENT_STRIPE_SANDBOX": "false"})
        self.assertFalse(credentials["sandbox"])