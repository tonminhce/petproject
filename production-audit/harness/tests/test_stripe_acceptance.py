from __future__ import annotations

import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness.stripe import acceptance
from harness.stripe.acceptance import MATRIX_CASES, run_acceptance, stripe_event, stripe_signature
from harness.stripe.recorder import Recorder, redact
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


class CredentialModeTest(unittest.TestCase):
    def test_real_sandbox_key_is_kept_when_webhook_secret_is_local(self) -> None:
        credential = acceptance.resolve_credential(
            {
                "SHOP_PAYMENT_STRIPE_API_BASE": acceptance.PUBLIC_STRIPE_API,
                "SHOP_PAYMENT_STRIPE_SECRET_KEY": "sk_test_example",
                "SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET": "whsec_local_example",
            }
        )
        self.assertEqual(credential["mode"], "real-test-local-webhook")


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
            [{"name": name, "verdict": "PROVEN"} for name in DECLARED_CASES],
            {"secret_key_mode": "test"},
        )
        acceptance["verified_against_real_stripe_api"] = True
        acceptance["credential"]["credential_probe"] = {"status": 200, "livemode": False}
        verdict = _payment_acceptance_verdict(acceptance)
        self.assertEqual(verdict["status"], PASS)

    def test_one_proven_case_cannot_certify_the_matrix(self) -> None:
        acceptance = _acceptance(
            {"kind": "stripe", "version": "", "is_public_stripe_api": True},
            {"cases": 8, "proven": 8, "failed": 0, "not_proven": 0},
            [{"name": "refund", "verdict": "PROVEN"}],
            {"secret_key_mode": "test"},
        )
        acceptance["verified_against_real_stripe_api"] = True
        acceptance["credential"]["credential_probe"] = {"status": 200, "livemode": False}
        self.assertEqual(_payment_acceptance_verdict(acceptance)["status"], BLOCKED)


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


class AcceptanceEvidenceTest(unittest.TestCase):
    def test_binding_uses_the_revision_and_both_fingerprints(self) -> None:
        with patch.object(acceptance, "current_revision", return_value="revision"), patch.object(
            acceptance, "source_fingerprint", return_value="source"
        ), patch.object(acceptance, "harness_fingerprint", return_value="harness"):
            binding = acceptance._acceptance_binding()

        self.assertEqual(
            binding,
            {
                "revision": "revision",
                "source_fingerprint": "source",
                "harness_fingerprint": "harness",
            },
        )

    def test_evidence_preserves_the_pre_run_binding(self) -> None:
        binding = {
            "revision": "pre-run-revision",
            "source_fingerprint": "pre-run-source",
            "harness_fingerprint": "pre-run-harness",
        }
        document = {"stripe_server": {"kind": "official-stripe-mock"}, **binding}
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            acceptance, "evidence_directory", return_value=Path(temporary)
        ), patch.object(acceptance, "write_json") as write:
            path = acceptance.write_evidence(document, "run-test")

        self.assertEqual(path, Path(temporary) / "acceptance.json")
        self.assertEqual(write.call_count, 2)
        self.assertIs(write.call_args_list[0].args[1], document)
        server = write.call_args_list[1].args[1]
        for name, value in binding.items():
            self.assertEqual(document[name], value)
            self.assertEqual(server[name], value)


class RecorderLifecycleTest(unittest.TestCase):
    def test_recorder_cleanup_failure_does_not_mask_the_body_error(self) -> None:
        error = RuntimeError("acceptance body failed")

        class FakeRecorder:
            def start(self) -> None:
                return

            def stop(self) -> None:
                raise RuntimeError("recorder cleanup failed")

        with patch.object(acceptance, "Recorder", return_value=FakeRecorder()):
            with self.assertRaises(RuntimeError) as caught:
                with acceptance._recorder("http://127.0.0.1:1", 0):
                    raise error

        self.assertIs(caught.exception, error)

    def test_the_recorder_releases_its_socket_when_it_is_stopped(self) -> None:
        recorder = Recorder(upstream="http://127.0.0.1:1", port=0)
        port = recorder.server.server_address[1]
        recorder.start()
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.settimeout(2)
                self.assertEqual(probe.connect_ex(("127.0.0.1", port)), 0)
        finally:
            recorder.stop()
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.settimeout(2)
            self.assertNotEqual(probe.connect_ex(("127.0.0.1", port)), 0)

    def test_acceptance_cleans_owned_containers_on_success(self) -> None:
        binding = {
            "revision": "pre-run-revision",
            "source_fingerprint": "pre-run-source",
            "harness_fingerprint": "pre-run-harness",
        }
        events: list[str] = []

        def run(identifier: str, expected: dict, containers: object) -> dict:
            events.append("run")
            self.assertIs(expected, binding)
            containers.add(acceptance.STRIPE_MOCK_CONTAINER)
            containers.add(acceptance.PAYMENT_CONTAINER)
            return {"run_id": identifier, "revision": "changed-during-run"}

        with patch.object(acceptance, "_acceptance_binding", return_value=binding) as bind, patch.object(
            acceptance, "_run_acceptance", side_effect=run
        ), patch.object(acceptance, "_docker", return_value=(0, "", "")) as docker:
            document = run_acceptance("run-test")

        self.assertEqual(events, ["run"])
        bind.assert_called_once_with()
        for name, value in binding.items():
            self.assertEqual(document[name], value)
        self.assertEqual(
            [call.args[0] for call in docker.call_args_list],
            [
                ["rm", "-f", acceptance.PAYMENT_CONTAINER],
                ["rm", "-f", acceptance.STRIPE_MOCK_CONTAINER],
            ],
        )

    def test_nonzero_container_removal_fails_successful_acceptance(self) -> None:
        def run(identifier: str, binding: dict, containers: object) -> dict:
            containers.add(acceptance.PAYMENT_CONTAINER)
            return {"status": "PASS"}

        with patch.object(acceptance, "_acceptance_binding", return_value={}), patch.object(
            acceptance, "_run_acceptance", side_effect=run
        ), patch.object(acceptance, "_docker", return_value=(1, "", "not found")):
            with self.assertRaises(RuntimeError):
                run_acceptance("run-test")

    def test_sigterm_is_converted_to_an_interrupt_and_restored(self) -> None:
        handlers = []

        def install(signum: int, handler: object) -> None:
            handlers.append(handler)

        with patch.object(acceptance.signal, "getsignal", return_value="previous"), patch.object(
            acceptance.signal, "signal", side_effect=install
        ):
            with self.assertRaises(KeyboardInterrupt):
                with acceptance._sigterm_interrupt():
                    handlers[0](acceptance.signal.SIGTERM, None)
        self.assertEqual(handlers[-1], "previous")

    def test_cleanup_failure_does_not_mask_the_acceptance_error(self) -> None:
        error = RuntimeError("acceptance failed")

        def fail(identifier: str, binding: dict, containers: object) -> dict:
            containers.add(acceptance.STRIPE_MOCK_CONTAINER)
            containers.add(acceptance.PAYMENT_CONTAINER)
            raise error

        def docker(args: list[str], timeout: int = 180) -> tuple[int, str, str]:
            if args[2] == acceptance.PAYMENT_CONTAINER:
                raise RuntimeError("cleanup failed")
            return 0, "", ""

        with patch.object(acceptance, "_acceptance_binding", return_value={}), patch.object(
            acceptance, "_run_acceptance", side_effect=fail
        ), patch.object(acceptance, "_docker", side_effect=docker) as cleanup:
            with self.assertRaises(RuntimeError) as caught:
                run_acceptance("run-test")

        self.assertIs(caught.exception, error)
        self.assertEqual(cleanup.call_count, 2)
        self.assertEqual(
            {call.args[0][2] for call in cleanup.call_args_list},
            {acceptance.PAYMENT_CONTAINER, acceptance.STRIPE_MOCK_CONTAINER},
        )

    def test_acceptance_cleans_owned_containers_on_interruption(self) -> None:
        interruption = KeyboardInterrupt()

        def interrupt(identifier: str, binding: dict, containers: object) -> dict:
            containers.add(acceptance.STRIPE_MOCK_CONTAINER)
            containers.add(acceptance.PAYMENT_CONTAINER)
            raise interruption

        with patch.object(acceptance, "_acceptance_binding", return_value={}), patch.object(
            acceptance, "_run_acceptance", side_effect=interrupt
        ), patch.object(acceptance, "_docker", return_value=(0, "", "")) as cleanup:
            with self.assertRaises(KeyboardInterrupt) as caught:
                run_acceptance("run-test")

        self.assertIs(caught.exception, interruption)
        self.assertEqual(cleanup.call_count, 2)

    def test_payment_container_is_owned_before_docker_run(self) -> None:
        interruption = KeyboardInterrupt()
        removals: list[str] = []

        class FakeRecorder:
            started = False
            stopped = False

            def start(self) -> None:
                self.started = True

            def stop(self) -> None:
                self.stopped = True

        recorder = FakeRecorder()

        def stripe_mock(containers: object) -> dict:
            containers.add(acceptance.STRIPE_MOCK_CONTAINER)
            return {"ready": True, "http_address_in_network": "http://stripe-mock"}

        def docker(args: list[str], timeout: int = 180) -> tuple[int, str, str]:
            if args[:2] == ["rm", "-f"]:
                removals.append(args[2])
                return 0, "", ""
            if args[0] == "inspect":
                return 1, "", ""
            if args[0] == "run":
                raise interruption
            self.fail("unexpected Docker command: " + " ".join(args))

        with patch.object(acceptance, "_acceptance_binding", return_value={}), patch.object(
            acceptance, "_dotenv", return_value={}
        ), patch.object(
            acceptance, "resolve_credential", return_value={"mode": "generated-mock"}
        ), patch.object(
            acceptance, "ensure_stripe_mock", side_effect=stripe_mock
        ), patch.object(
            acceptance, "ensure_compose_services", return_value={"state": "already running"}
        ), patch.object(
            acceptance, "Recorder", return_value=recorder
        ), patch.object(
            acceptance, "_docker", side_effect=docker
        ):
            with self.assertRaises(KeyboardInterrupt) as caught:
                run_acceptance("run-test")

        self.assertIs(caught.exception, interruption)
        self.assertTrue(recorder.started)
        self.assertTrue(recorder.stopped)
        self.assertEqual(removals, [acceptance.PAYMENT_CONTAINER, acceptance.STRIPE_MOCK_CONTAINER])

    def test_stripe_mock_is_owned_before_docker_run(self) -> None:
        interruption = KeyboardInterrupt()
        removals: list[str] = []

        def docker(args: list[str], timeout: int = 180) -> tuple[int, str, str]:
            if args[:2] == ["rm", "-f"]:
                removals.append(args[2])
                return 0, "", ""
            if args[0] == "run":
                raise interruption
            self.fail("unexpected Docker command: " + " ".join(args))

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            acceptance, "_acceptance_binding", return_value={}
        ), patch.object(
            acceptance, "_dotenv", return_value={}
        ), patch.object(
            acceptance, "resolve_credential", return_value={"mode": "generated-mock"}
        ), patch.object(
            acceptance, "STATE_DIRECTORY", Path(temporary) / "state"
        ), patch.object(
            acceptance, "MOCK_STATE_DIRECTORY", Path(temporary) / "state" / "stripe-mock"
        ), patch.object(
            acceptance, "_docker", side_effect=docker
        ):
            with self.assertRaises(KeyboardInterrupt) as caught:
                run_acceptance("run-test")

        self.assertIs(caught.exception, interruption)
        self.assertEqual(removals, [acceptance.STRIPE_MOCK_CONTAINER, acceptance.STRIPE_MOCK_CONTAINER])

    def test_recorder_stops_when_payment_start_fails(self) -> None:
        class FakeRecorder:
            started = False
            stopped = False

            def start(self) -> None:
                self.started = True

            def stop(self) -> None:
                self.stopped = True

        recorder = FakeRecorder()

        def stripe_mock(containers: object) -> dict:
            containers.add(acceptance.STRIPE_MOCK_CONTAINER)
            return {"ready": True, "http_address_in_network": "http://stripe-mock"}

        def payment(*args: object, containers: object, **kwargs: object) -> dict:
            containers.add(acceptance.PAYMENT_CONTAINER)
            return {"state": "failed"}

        with patch.object(acceptance, "_acceptance_binding", return_value={}), patch.object(
            acceptance, "_dotenv", return_value={}
        ), patch.object(
            acceptance, "resolve_credential", return_value={"mode": "generated-mock"}
        ), patch.object(
            acceptance, "ensure_stripe_mock", side_effect=stripe_mock
        ), patch.object(
            acceptance, "ensure_compose_services", return_value={"state": "already running"}
        ), patch.object(
            acceptance, "Recorder", return_value=recorder
        ), patch.object(
            acceptance, "ensure_payment_service", side_effect=payment
        ), patch.object(
            acceptance, "_docker", return_value=(0, "", "")
        ) as cleanup:
            document = run_acceptance("run-test")

        self.assertEqual(document["status"], "BLOCKED")
        self.assertTrue(recorder.started)
        self.assertTrue(recorder.stopped)
        self.assertEqual(cleanup.call_count, 2)


if __name__ == "__main__":
    unittest.main()
