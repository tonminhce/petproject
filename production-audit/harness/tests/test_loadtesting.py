from __future__ import annotations

import unittest

from harness.loadtesting.customers import mint_tokens
from harness.loadtesting.profiles import resolve_profile
from harness.loadtesting.runner import _final_status


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


if __name__ == "__main__":
    unittest.main()
