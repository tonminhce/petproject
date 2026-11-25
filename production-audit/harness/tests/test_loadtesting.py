from __future__ import annotations

import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import harness.loadtesting.runner as runner
from harness.loadtesting.customers import mint_tokens
from harness.loadtesting.profiles import resolve_profile
from harness.loadtesting.runner import _final_status, purge_token_residue, reset_commerce_data


class CredentialLifetimeTest(unittest.TestCase):
    def test_a_run_with_expired_credentials_is_blocked_and_not_failed(self) -> None:
        document = {
            "metrics": {
                "credential_error": 42,
                "business_success": 10,
                "business_failure": 90,
            },
            "k6_exit_code": 99,
        }

        status = _final_status(document)

        self.assertEqual(status, "BLOCKED")
        self.assertIn("credentials expired", document["status_detail"])

    def test_a_run_without_expired_credentials_is_not_blocked_for_that_reason(self) -> None:
        document = {
            "metrics": {
                "credential_error": 0,
                "business_success": 10,
                "business_failure": 0,
                "shed_requests": 0,
                "business_refusal": 0,
                "coupon_contention": 0,
                "cart_contention": 0,
            },
            "k6_exit_code": 0,
            "measurement_valid": True,
        }

        status = _final_status(document)

        self.assertNotEqual(status, "BLOCKED")

    def test_the_token_lifetime_covers_the_whole_profile(self) -> None:
        for name in ("baseline", "ramp", "spike", "flash-sale", "promotion-storm"):
            profile = resolve_profile(name)
            required = int(profile.duration_seconds) + 600
            self.assertGreater(required, int(profile.duration_seconds))
            self.assertLessEqual(required, 3600)

    def test_the_minting_reports_the_customers_whose_token_failed(self) -> None:
        class _FakeResponse:
            def __init__(self, payload: dict) -> None:
                self._payload = payload

            def read(self) -> bytes:
                import json

                return json.dumps(self._payload).encode()

            def __enter__(self) -> "_FakeResponse":
                return self

            def __exit__(self, *args: object) -> None:
                return None

        import harness.loadtesting.customers as customers

        original_password = customers.load_test_password
        customers.load_test_password = lambda: "test-password"
        original = customers.urllib.request.urlopen
        calls: list[str] = []

        def fake_urlopen(request, timeout=0):  # type: ignore[no-untyped-def]
            username = request.full_url
            calls.append(username)
            if "broken" in str(request.data):
                raise customers.urllib.error.URLError("refused")
            return _FakeResponse({"access_token": "token-" + str(len(calls))})

        customers.urllib.request.urlopen = fake_urlopen
        try:
            result = customers.mint_tokens(["good-1", "broken-2", "good-3"])
        finally:
            customers.urllib.request.urlopen = original
            customers.load_test_password = original_password

        self.assertEqual(result.get("status"), "READY")
        self.assertEqual(result.get("minted"), 2)
        self.assertEqual(result.get("failed"), ["broken-2"])


class CustomerTokenFileTest(unittest.TestCase):
    def test_the_token_file_is_private_and_removed_after_the_run(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with runner._customer_token_file(output, ["token-one", "token-two"]) as path:
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                self.assertEqual(path.read_text(encoding="utf-8"), "token-one\ntoken-two\n")
            self.assertFalse(path.exists())

    def test_the_token_file_is_removed_when_the_run_is_interrupted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with self.assertRaises(KeyboardInterrupt):
                with runner._customer_token_file(output, ["token-one"]):
                    raise KeyboardInterrupt
            self.assertFalse((output / "customer-tokens.txt").exists())

    def test_the_token_file_is_removed_when_creation_or_write_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            with patch.object(runner.os, "fchmod", side_effect=OSError("chmod failed")):
                with self.assertRaises(OSError):
                    with runner._customer_token_file(output, ["token-one"]):
                        pass
            self.assertFalse((output / "customer-tokens.txt").exists())


class TokenResidueTest(unittest.TestCase):
    def test_legacy_token_files_are_securely_removed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            token = root / "baseline-1" / "customer-tokens.txt"
            token.parent.mkdir()
            token.write_text("secret", encoding="utf-8")
            result = purge_token_residue(root)
            self.assertEqual(result["removed"], 1)
            self.assertFalse(token.exists())


class CommerceDataResetTest(unittest.TestCase):
    def test_reset_truncates_only_local_order_and_payment_data(self) -> None:
        with patch("harness.loadtesting.invariants.psql", return_value=(True, [])) as psql:
            result = reset_commerce_data()
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(
            [call.args[1] for call in psql.call_args_list],
            ["TRUNCATE TABLE payments CASCADE", "TRUNCATE TABLE orders CASCADE"],
        )


class TokenLifetimeRestorationTest(unittest.TestCase):
    def test_a_changed_lifetime_is_restored_after_success(self) -> None:
        def measure(*args: object, lifetime_state: dict, **kwargs: object) -> dict:
            lifetime_state.update({"status": "READY", "changed": True, "original_seconds": 300})
            return {"status": "PASS"}

        with patch.object(runner, "_measure_profile", side_effect=measure), patch.object(
            runner, "restore_token_lifetime", return_value={"status": "READY", "lifespan_seconds": 300}
        ) as restore, patch.object(runner, "restore_shipped_rate_limits", return_value={"status": "READY"}):
            result = runner.run_profile("ramp")

        restore.assert_called_once_with(300)
        self.assertEqual(result["status"], "PASS")
        self.assertEqual(result["token_lifetime"]["restore"]["status"], "READY")

    def test_a_changed_lifetime_is_restored_after_an_early_return(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(runner, "results_root", return_value=Path(temporary)), patch.object(
                runner, "wait_for_quiet_host", return_value={"status": "QUIET"}
            ), patch.object(runner, "apply_load_test_rate_limits", return_value={"status": "READY"}), patch.object(
                runner, "host_snapshot", return_value={"contaminated": False}
            ), patch.object(runner, "wait_for_gateway_traffic", return_value={"status": "READY"}), patch.object(
                runner,
                "ensure_token_lifetime",
                return_value={
                    "status": "READY",
                    "lifespan_seconds": 780,
                    "changed": True,
                    "original_seconds": 300,
                },
            ), patch.object(
                runner, "provision_customers", return_value={"status": "BLOCKED", "reason": "unavailable"}
            ), patch.object(
                runner, "restore_token_lifetime", return_value={"status": "READY", "lifespan_seconds": 300}
            ) as restore, patch.object(runner, "restore_shipped_rate_limits", return_value={"status": "READY"}):
                result = runner.run_profile("ramp", network="test-network", token="admin-token")

        restore.assert_called_once_with(300)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertEqual(result["token_lifetime"]["restore"]["status"], "READY")

    def test_a_changed_lifetime_is_restored_after_an_interruption(self) -> None:
        def interrupt(*args: object, lifetime_state: dict, **kwargs: object) -> dict:
            lifetime_state.update({"status": "READY", "changed": True, "original_seconds": 300})
            raise KeyboardInterrupt

        with patch.object(runner, "_measure_profile", side_effect=interrupt), patch.object(
            runner, "restore_token_lifetime", return_value={"status": "READY", "lifespan_seconds": 300}
        ) as restore, patch.object(
            runner, "restore_shipped_rate_limits", return_value={"status": "READY"}
        ) as restore_limits, self.assertRaises(KeyboardInterrupt):
            runner.run_profile("ramp")

        restore.assert_called_once_with(300)
        restore_limits.assert_called_once_with()

    def test_a_restore_failure_does_not_replace_the_original_result(self) -> None:
        def measure(*args: object, lifetime_state: dict, **kwargs: object) -> dict:
            lifetime_state.update({"status": "READY", "changed": True, "original_seconds": 300})
            return {"status": "PASS"}

        with patch.object(runner, "_measure_profile", side_effect=measure), patch.object(
            runner, "restore_token_lifetime", side_effect=RuntimeError("restore unavailable")
        ), patch.object(runner, "restore_shipped_rate_limits", return_value={"status": "READY"}):
            result = runner.run_profile("ramp")

        self.assertEqual(result["status"], "FAIL")
        self.assertEqual(result["token_lifetime"]["restore"]["status"], "ERROR")

    def test_blocked_keycloak_restore_fails_the_result(self) -> None:
        def measure(*args: object, lifetime_state: dict, **kwargs: object) -> dict:
            lifetime_state.update({"status": "READY", "changed": True, "original_seconds": 300})
            return {"status": "PASS"}

        with patch.object(runner, "_measure_profile", side_effect=measure), patch.object(
            runner, "restore_token_lifetime", return_value={"status": "BLOCKED"}
        ), patch.object(runner, "restore_shipped_rate_limits", return_value={"status": "READY"}):
            result = runner.run_profile("ramp")
        self.assertEqual(result["status"], "FAIL")

    def test_sigterm_is_converted_to_an_interrupt(self) -> None:
        handlers = []

        def install(signum: int, handler: object) -> None:
            handlers.append(handler)

        with patch.object(runner.signal, "getsignal", return_value="previous"), patch.object(
            runner.signal, "signal", side_effect=install
        ):
            with self.assertRaises(KeyboardInterrupt):
                with runner._sigterm_interrupt():
                    handlers[0](runner.signal.SIGTERM, None)
        self.assertEqual(handlers[-1], "previous")

    def test_gateway_limits_are_restored_when_measurement_raises(self) -> None:
        with patch.object(runner, "_measure_profile", side_effect=RuntimeError("measurement failed")), patch.object(
            runner, "restore_shipped_rate_limits", return_value={"status": "READY"}
        ) as restore, self.assertRaises(RuntimeError):
            runner.run_profile("ramp")
        restore.assert_called_once_with()

    def test_actual_rate_limit_restore_status_contract_is_accepted(self) -> None:
        with patch.object(runner, "_measure_profile", return_value={"status": "PASS"}), patch.object(
            runner, "restore_shipped_rate_limits", return_value={"status": "OK"}
        ):
            result = runner.run_profile("ramp")
        self.assertEqual(result["status"], "PASS")

    def test_an_unchanged_lifetime_is_not_restored(self) -> None:
        def measure(*args: object, lifetime_state: dict, **kwargs: object) -> dict:
            lifetime_state.update({"status": "READY", "changed": False, "original_seconds": 300})
            return {"status": "PASS"}

        with patch.object(runner, "_measure_profile", side_effect=measure), patch.object(
            runner, "restore_token_lifetime"
        ) as restore, patch.object(runner, "restore_shipped_rate_limits", return_value={"status": "READY"}):
            result = runner.run_profile("ramp")

        restore.assert_not_called()
        self.assertEqual(result["status"], "PASS")


if __name__ == "__main__":
    unittest.main()
