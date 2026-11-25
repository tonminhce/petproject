from __future__ import annotations

import http
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness import config
from harness.loadtesting.capacity import _rate_ratio_denominator, capacity_limit_document, render_capacity_report
from harness.loadtesting.profiles import resolve_profile
from harness.loadtesting.runner import (
    _business_outcome_is_acceptable,
    _customer_pool,
    _customer_pool_shared,
    _customer_pool_violations,
    _reconcile_with_load_generator,
)

SCENARIO = "load-tests/k6-scenario.js"
GATEWAY_WRITER = "gateway-service/src/main/java/com/shop/gateway/filter/GatewayErrorResponseWriter.java"
ERROR_CODES = "utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java"


def _source(relative: str) -> str:
    return (Path(config.repository_root()) / relative).read_text(encoding="utf-8")


def _scenario_shed_statuses() -> set[int]:
    source = _source(SCENARIO)
    match = re.search(r"function isShedStatus\(status\) \{\s*return ([^;]+);", source)
    if match is None:
        raise AssertionError("the scenario does not declare isShedStatus")
    return {int(value) for value in re.findall(r"status === (\d+)", match.group(1))}


def _gateway_shed_statuses() -> set[int]:
    writer = _source(GATEWAY_WRITER)
    codes = set(re.findall(r"writeEnvelope\(exchange, ErrorCode\.([A-Z_]+)\)", writer))
    codes.update(re.findall(r"writeEnvelope\(exchange, errorCode\)", writer) and [] or [])
    enum = _source(ERROR_CODES)
    statuses: set[int] = set()
    for code in codes:
        match = re.search(re.escape(code) + r"\(\"[^\"]*\", \"[^\"]*\", HttpStatus\.([A-Z_]+)\)", enum)
        if match is None:
            raise AssertionError("the error code " + code + " is not declared with a http status")
        statuses.add(int(getattr(http.HTTPStatus, match.group(1))))
    return statuses


class GatewayShedStatusTest(unittest.TestCase):
    def test_the_scenario_sheds_only_with_the_status_the_gateway_uses(self) -> None:
        self.assertEqual(_scenario_shed_statuses(), _gateway_shed_statuses())

    def test_the_gateway_sheds_with_429_and_503(self) -> None:
        self.assertEqual(_gateway_shed_statuses(), {429, 503})

    def test_a_server_error_is_not_a_shed(self) -> None:
        shed = _scenario_shed_statuses()
        for status in (500, 502, 504):
            self.assertNotIn(status, shed)

    def test_the_scenario_observes_every_response_it_receives(self) -> None:
        source = _source(SCENARIO)
        requests = len(re.findall(r"http\.(get|post|put|del)\(", source))
        observations = len(re.findall(r"(?<!function )\bobserve\(", source))
        self.assertGreater(requests, 0)
        self.assertEqual(requests, observations)


class ReconciliationTest(unittest.TestCase):
    def _metrics(self, **overrides: object) -> dict:
        metrics = {
            "requests": 1000,
            "failed_requests": 5,
            "failure_rate": 0.005,
            "business_success": 985,
            "business_failure": 5,
            "business_refusal": 0,
            "shed_requests": 2,
            "credential_error": 0,
            "unexpected_responses": 5,
            "platform_errors": 5,
        }
        metrics.update(overrides)
        return metrics

    def test_a_server_error_booked_as_a_failure_agrees_with_the_load_generator(self) -> None:
        reconciliation = _reconcile_with_load_generator(self._metrics())

        self.assertTrue(reconciliation["consistent"])
        self.assertEqual(reconciliation["reasons"], [])

    def test_a_server_error_outside_the_failure_budget_is_detected(self) -> None:
        reconciliation = _reconcile_with_load_generator(self._metrics(business_failure=0))

        self.assertFalse(reconciliation["consistent"])
        self.assertTrue(any("server error" in reason for reason in reconciliation["reasons"]))

    def test_a_count_that_contradicts_the_load_generator_is_detected(self) -> None:
        reconciliation = _reconcile_with_load_generator(self._metrics(unexpected_responses=9))

        self.assertFalse(reconciliation["consistent"])
        self.assertTrue(any("load generator" in reason for reason in reconciliation["reasons"]))

    def test_a_response_that_left_both_budgets_is_detected(self) -> None:
        reconciliation = _reconcile_with_load_generator(self._metrics(business_failure=1))

        self.assertFalse(reconciliation["consistent"])
        self.assertTrue(any("left both budgets" in reason for reason in reconciliation["reasons"]))

    def test_a_run_whose_accounting_contradicts_the_load_generator_fails(self) -> None:
        document = {
            "metrics": self._metrics(business_failure=0),
            "planned_iteration_rate_per_second": 10.0,
            "max_business_failure_rate": 0.05,
            "max_shed_rate": 0.01,
            "k6_exit_code": 0,
            "measurement_valid": True,
        }

        acceptable = _business_outcome_is_acceptable(document)

        self.assertFalse(acceptable)
        self.assertFalse(document["load_generator_reconciliation"]["consistent"])


class CustomerPoolTest(unittest.TestCase):
    def test_the_pool_records_the_number_of_tokens_that_were_really_minted(self) -> None:
        profile = resolve_profile("spike")

        pool = _customer_pool(profile, {"status": "READY"}, {"failed": ["a", "b"]}, ["t"] * 261)

        self.assertTrue(pool["required"])
        self.assertEqual(pool["customers"], 261)
        self.assertEqual(pool["customers_requested"], 400)
        self.assertEqual(pool["mint_failures"], 2)
        self.assertEqual(pool["share_factor"], round(400 / 261, 4))
        self.assertFalse(pool["one_customer_per_virtual_user"])

    def test_a_pool_that_covers_the_virtual_users_is_accepted(self) -> None:
        profile = resolve_profile("spike")

        pool = _customer_pool(profile, {"status": "READY"}, {"failed": []}, ["t"] * 400)

        self.assertTrue(pool["one_customer_per_virtual_user"])
        self.assertEqual(pool["share_factor"], 1.0)
        self.assertEqual(_customer_pool_violations(profile, pool), [])

    def test_the_safety_check_refuses_a_plan_whose_pool_cannot_be_provisioned(self) -> None:
        profile = resolve_profile("spike")

        pool = _customer_pool(profile, {"status": "READY"}, {"failed": ["a"] * 139}, ["t"] * 261)

        violations = _customer_pool_violations(profile, pool)

        self.assertTrue(violations)
        self.assertTrue(any("261" in violation and "400" in violation for violation in violations))

    def test_a_run_with_a_short_pool_is_blocked(self) -> None:
        document = {
            "customer_pool": {
                "required": True,
                "customers": 93,
                "virtual_users": 400,
                "one_customer_per_virtual_user": False,
            }
        }

        reason = _customer_pool_shared(document)

        self.assertIn("93", reason)
        self.assertIn("400", reason)

    def test_a_run_whose_load_generator_saw_a_short_pool_is_blocked(self) -> None:
        document = {
            "customer_pool": {
                "required": True,
                "customers": 400,
                "virtual_users": 400,
                "one_customer_per_virtual_user": True,
                "customer_tokens_in_load_generator": 174,
            }
        }

        reason = _customer_pool_shared(document)

        self.assertIn("174", reason)

    def test_a_profile_that_places_no_order_needs_no_pool(self) -> None:
        profile = resolve_profile("baseline")

        pool = _customer_pool(profile, {"status": "NOT_REQUIRED"}, {}, [])

        self.assertFalse(pool["required"])
        self.assertEqual(_customer_pool_violations(profile, pool), [])
        self.assertEqual(_customer_pool_shared({"customer_pool": pool}), "")


class CapacityFreshnessTest(unittest.TestCase):
    def test_recorded_snapshot_is_not_trusted_over_canonical_results(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            run = root / "baseline-1"
            run.mkdir()
            (run / "result.json").write_text(
                json.dumps(
                    {
                        "profile": "baseline",
                        "status": "PASS",
                        "metrics": {"business_success": 1, "requests": 2, "iterations": 1},
                        "invariants": {"status": "PASS"},
                        "measurement_valid": True,
                    }
                ),
                encoding="utf-8",
            )
            state = root / "state.json"
            state.write_text(
                json.dumps({"capacity_limit": {"measured": True, "binding": {"binds_to_current_source": True}}}),
                encoding="utf-8",
            )
            evidence = {"revision": "revision", "source_fingerprint": "source", "harness_fingerprint": "harness"}
            with patch("harness.loadtesting.capacity.current_evidence", return_value=evidence), patch(
                "harness.loadtesting.capacity._revision_is_contained", return_value=True
            ):
                document = capacity_limit_document(state, root)
            self.assertFalse(document["binding"]["binds_to_current_source"])
            self.assertEqual(document["binding"]["stale_profiles"], ["baseline"])


class RecordedRateRatioTest(unittest.TestCase):
    def test_a_ratio_of_iterations_is_named_after_the_planned_iteration_rate(self) -> None:
        document = {"achieved_rate_ratio": 0.851, "planned_iteration_rate_per_second": 7.9412}

        self.assertEqual(_rate_ratio_denominator(document), "planned_iteration_rate_per_second")

    def test_a_ratio_of_a_run_before_the_unit_change_keeps_its_denominator(self) -> None:
        document = {"achieved_rate_ratio": 0.338, "target_requests_per_second": 20}

        self.assertEqual(_rate_ratio_denominator(document), "target_requests_per_second")

    def test_the_report_names_the_denominator_of_every_ratio(self) -> None:
        document = {
            "recorded_at": "2026-11-21T00:00:00Z",
            "run_id": "run-test",
            "rule": "rule",
            "source_directory": "load-results",
            "last_complete_run": {"selection": "s", "profiles": ["spike"], "directories_in_run_order": ["spike-1"]},
            "profiles": [
                {
                    "profile": "spike",
                    "scenario": "full",
                    "status": "PASS",
                    "directory": "spike-1",
                    "result_file": "load-results/spike-1/result.json",
                    "planned_peak_iterations_per_second": 50.0,
                    "planned_peak_requests_per_second": 200.0,
                    "requests_per_iteration_declared": 4,
                    "requests_per_iteration_observed": 4.0,
                    "delivered_iterations_per_second": 25.0,
                    "delivered_requests_per_second": 100.0,
                    "recorded_rate_ratio": 0.851,
                    "recorded_rate_ratio_denominator": "planned_iteration_rate_per_second",
                    "recorded_rate_unit": "iterations per second",
                    "iterations": 100,
                    "requests": 400,
                    "dropped_iterations": 0,
                    "k6_exit_code": 0,
                    "business_success": 90,
                    "business_failure": 0,
                    "business_refusal": 0,
                    "shed_requests": 0,
                    "invariant_violations": 0,
                    "invariant_status": "PASS",
                    "invariant_failed": [],
                    "business_failure_rate": 0.0,
                    "shed_rate": 0.0,
                    "transport_failure_rate": 0.0,
                    "platform_error_rate": 0.0,
                    "measurement_valid": True,
                    "business_verdict": "ok",
                    "current_harness_fields_missing": [],
                }
            ],
            "stops_holding_at": {"highest_broken_profile": "", "profiles": []},
            "binding": {"binds_to_current_source": True, "reason": "none"},
            "capacity_limit": {
                "profile": "spike",
                "scenario": "full",
                "status": "PASS",
                "directory": "spike-1",
                "result_file": "load-results/spike-1/result.json",
                "requests_per_second": 100.0,
                "iterations_per_second": 25.0,
                "planned_peak_iterations_per_second": 50.0,
                "planned_peak_requests_per_second": 200.0,
                "recorded_rate_ratio": 0.851,
                "business_success": 90,
                "business_failure": 0,
                "business_refusal": 0,
                "shed_requests": 0,
                "invariant_violations": 0,
                "invariant_status": "PASS",
            },
        }

        report = render_capacity_report(document)

        self.assertIn("planned_iteration_rate_per_second", report)
        self.assertNotIn("divided by the request rate the profile declares", report)
        self.assertNotIn("compared an iteration rate with a request target", report)


if __name__ == "__main__":
    unittest.main()
