from __future__ import annotations

import unittest

from harness.stripe.acceptance import MATRIX_CASES, stripe_event, stripe_signature
from harness.stripe.recorder import redact
from harness.verification.gates import BLOCKED, PASS, _payment_acceptance_verdict


DECLARED_CASES = (
    "authorisation_and_capture",
    "capture_webhook_signature_verified",
    "refund",
    "refund_idempotent_replay",
    "partial_refund",
    "second_partial_refund",
    "declined_card_refusal",
    "currency_mismatch_refusal",
)


def _acceptance(server: dict, summary: dict, cases: list[dict], credential: dict) -> dict:
    return {
        "stripe_server": server,
        "summary": summary,
        "cases": cases,
        "credential": credential,
    }


class MatrixDeclarationTest(unittest.TestCase):
    def test_every_declared_case_of_the_mandate_is_present(self) -> None:
        names = tuple(case["name"] for case in MATRIX_CASES)
        for name in DECLARED_CASES:
            self.assertIn(name, names)

    def test_each_case_carries_the_mandate_wording(self) -> None:
        for case in MATRIX_CASES:
            self.assertTrue(case["mandate"])
            self.assertTrue(case["proves"])


class PaymentAcceptanceVerdictTest(unittest.TestCase):
    def test_without_evidence_the_gate_is_blocked(self) -> None:
        verdict = _payment_acceptance_verdict({})
        self.assertEqual(verdict["status"], BLOCKED)

    def test_a_run_against_the_official_mock_is_not_verified_against_real_stripe(self) -> None:
        acceptance = _acceptance(
            {"kind": "official-stripe-mock", "version": "0.206.0", "is_public_stripe_api": False},
            {"cases": 8, "proven": 7, "failed": 0, "not_proven": 1},
            [{"name": "declined_card_refusal", "verdict": "NOT_PROVEN"}],
            {"secret_key_mode": "test"},
        )
        verdict = _payment_acceptance_verdict(acceptance)
        self.assertEqual(verdict["status"], BLOCKED)
        self.assertIn("NOT verified against the real Stripe", verdict["detail"])
        self.assertIn("declined_card_refusal", verdict["detail"])

    def test_the_public_base_address_alone_does_not_certify_the_acceptance(self) -> None:
        acceptance = _acceptance(
            {"kind": "official-stripe-mock", "version": "0.206.0", "is_public_stripe_api": True},
            {"cases": 8, "proven": 7, "failed": 0, "not_proven": 1},
            [{"name": "declined_card_refusal", "verdict": "NOT_PROVEN"}],
            {"secret_key_mode": "test"},
        )
        verdict = _payment_acceptance_verdict(acceptance)
        self.assertEqual(verdict["status"], BLOCKED)

    def test_a_placeholder_credential_does_not_certify_the_acceptance(self) -> None:
        acceptance = _acceptance(
            {"kind": "official-stripe-mock", "version": "0.206.0", "is_public_stripe_api": True},
            {"cases": 8, "proven": 8, "failed": 0, "not_proven": 0},
            [{"name": "refund", "verdict": "PROVEN"}],
            {"secret_key_mode": "placeholder"},
        )
        verdict = _payment_acceptance_verdict(acceptance)
        self.assertEqual(verdict["status"], BLOCKED)

    def test_a_complete_run_against_the_public_api_with_a_test_key_passes(self) -> None:
        acceptance = _acceptance(
            {"kind": "stripe", "version": "", "is_public_stripe_api": True},
            {"cases": 8, "proven": 8, "failed": 0, "not_proven": 0},
            [{"name": "refund", "verdict": "PROVEN"}],
            {"secret_key_mode": "test"},
        )
        verdict = _payment_acceptance_verdict(acceptance)
        self.assertEqual(verdict["status"], PASS)


class StripeWireFormatTest(unittest.TestCase):
    def test_the_signature_header_uses_the_stripe_t_v1_format(self) -> None:
        signature = stripe_signature(b"{}", "whsec_example", 1700000000)
        self.assertTrue(signature.startswith("t=1700000000,v1="))
        self.assertEqual(len(signature.split("v1=")[1]), 64)

    def test_the_event_carries_the_payment_metadata_the_platform_reads(self) -> None:
        event = stripe_event("evt_1", "payment_intent.succeeded", "pi_1", 24999, "USD", "payment-1")
        self.assertEqual(event["type"], "payment_intent.succeeded")
        self.assertEqual(event["data"]["object"]["amount"], 24999)
        self.assertEqual(event["data"]["object"]["currency"], "usd")
        self.assertEqual(event["data"]["object"]["metadata"]["payment_id"], "payment-1")


class RecorderRedactionTest(unittest.TestCase):
    def test_the_authorization_header_is_not_recorded(self) -> None:
        headers = redact({"Authorization": "Bearer secret-value", "Stripe-Version": "2023-10-16"})
        self.assertEqual(headers["Authorization"], "[redacted]")
        self.assertEqual(headers["Stripe-Version"], "2023-10-16")


if __name__ == "__main__":
    unittest.main()
