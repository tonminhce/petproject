# Measured capacity limit of the platform

- Run: `run-20261118-a2b6e6fc`
- Recorded at: 2026-11-21T18:47:01Z
- Rule: the highest rate the platform delivered in the last complete measured run with zero business failures and zero invariant violations.
- Every number below is read from the recorded results in `production-audit/runs/run-20261118-a2b6e6fc/load-results` and the table names the file it was read from.

## 1. The limit

**42.2400 requests per second, which is 8.3326 iterations per second.**

- Profile that sustained it: `ramp`, scenario `full`, recorded status `PASS`.
- Recorded result: `production-audit/runs/run-20261118-a2b6e6fc/load-results/ramp-1795286502/result.json`.
- Successful business outcomes: 3907.
- Business failures: 0.
- Business refusals: 0.
- Requests the gateway shed: 0.
- Invariant violations: 0, invariant status `PASS`.
- Planned peak offered to the load generator: 12.0000 iterations per second, which is 48.0000 requests per second at the requests per iteration the harness declares for the scenario.
- Delivered: 8.3326 iterations per second and 42.2400 requests per second.
- Recorded ratio: 0.819.

## 2. The last complete measured run

- Selection: the newest recorded result of every profile that has a recorded result.
- Window: 2026-11-21T11:58:06Z to 2026-11-21T18:41:42Z (directory stamps 1795262286 to 1795286502).
- The window holds exactly the recorded results of one sweep: no.
- Profiles of the sweep: baseline, flash-sale, promotion-storm, ramp, recovery, spike.
- Recorded results of the sweep:
  - `flash-sale-1795262286`
  - `spike-1795268516`
  - `promotion-storm-1795269071`
  - `baseline-1795269895`
  - `recovery-1795271066`
  - `ramp-1795286502`

## 3. The numbers of every profile

| Profile | Planned peak iter/s | Planned peak req/s | Delivered iter/s | Delivered req/s | Recorded ratio | Source |
| --- | --- | --- | --- | --- | --- | --- |
| baseline | 10.0000 | 20.0000 | 6.7581 | 13.9867 | 0.851 | `production-audit/runs/run-20261118-a2b6e6fc/load-results/baseline-1795269895/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/baseline-1795269895/k6-stdout.log` |
| flash-sale | 50.0000 | 150.0000 | 5.6519 | 21.5188 | 0.1423 | `production-audit/runs/run-20261118-a2b6e6fc/load-results/flash-sale-1795262286/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/flash-sale-1795262286/k6-stdout.log` |
| promotion-storm | 60.0000 | 120.0000 | 11.2870 | 11.2870 | None | `production-audit/runs/run-20261118-a2b6e6fc/load-results/promotion-storm-1795269071/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/promotion-storm-1795269071/k6-stdout.log` |
| ramp | 12.0000 | 48.0000 | 8.3326 | 42.2400 | 0.819 | `production-audit/runs/run-20261118-a2b6e6fc/load-results/ramp-1795286502/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/ramp-1795286502/k6-stdout.log` |
| recovery | 5.0000 | 10.0000 | 2.9000 | 5.8909 | 0.8861 | `production-audit/runs/run-20261118-a2b6e6fc/load-results/recovery-1795271066/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/recovery-1795271066/k6-stdout.log` |
| spike | 50.0000 | 200.0000 | 0.9222 | 10.1242 | 0.0232 | `production-audit/runs/run-20261118-a2b6e6fc/load-results/spike-1795268516/result.json`, planned peak from `production-audit/runs/run-20261118-a2b6e6fc/load-results/spike-1795268516/k6-stdout.log` |

| Profile | Business success | Business failure | Refusals | Shed | Business failure rate | Shed rate | Load generator failure rate | Platform error rate | Invariant violations | Invariant status | Recorded status | Source |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| baseline | 2298 | 0 | 0 | 0 | 0.0 | 0.0 | 0 | 0.0 | 0 | PASS | PASS | `production-audit/runs/run-20261118-a2b6e6fc/load-results/baseline-1795269895/result.json` |
| flash-sale | 397 | 791 | 0 | 783 | 0.665825 | 0.39726 | 0.015965819653699122 | 0.012593 | 0 | PASS | FAIL | `production-audit/runs/run-20261118-a2b6e6fc/load-results/flash-sale-1795262286/result.json` |
| promotion-storm | 0 | 1261 | 0 | 0 | None | None | None | None | 0 | PASS | BLOCKED | `production-audit/runs/run-20261118-a2b6e6fc/load-results/promotion-storm-1795269071/result.json` |
| ramp | 3907 | 0 | 0 | 0 | 0.0 | 0.0 | 0 | 0.0 | 0 | PASS | PASS | `production-audit/runs/run-20261118-a2b6e6fc/load-results/ramp-1795286502/result.json` |
| recovery | 638 | 0 | 0 | 0 | 0.0 | 0.0 | 0 | 0.0 | 0 | PASS | PASS | `production-audit/runs/run-20261118-a2b6e6fc/load-results/recovery-1795271066/result.json` |
| spike | 722 | 32 | 0 | 68 | 0.04244 | 0.082725 | 0.015263417035942885 | 0.000985 | 0 | PASS | FAIL | `production-audit/runs/run-20261118-a2b6e6fc/load-results/spike-1795268516/result.json` |

The planned peak is the arrival rate the load generator was given, read from the header of `k6-stdout.log` of the same run. The request equivalent uses the requests per iteration the harness declares for the scenario in `production-audit/harness/loadtesting/profiles.py`.

## 4. Where the platform stops holding

The platform held 42.2400 requests per second with no business failure and no invariant violation. The next rate it was measured at broke:

- `flash-sale` delivered 21.5188 requests per second and recorded 791 business failures, 0 business refusals, 783 requests shed by the gateway and 0 invariant violations, out of 397 successful business outcomes.
  - The load generator of that run dropped 4267 iterations it had planned to start and exited with the code 99, so the platform never served the whole offered rate.
- `promotion-storm` delivered 11.2870 requests per second and recorded 1261 business failures, 0 business refusals, 0 requests shed by the gateway and 0 invariant violations, out of 0 successful business outcomes.
  - The load generator of that run dropped 4957 iterations it had planned to start and exited with the code 99, so the platform never served the whole offered rate.
- `spike` delivered 10.1242 requests per second and recorded 32 business failures, 0 business refusals, 68 requests shed by the gateway and 0 invariant violations, out of 722 successful business outcomes.
  - The load generator of that run dropped 5339 iterations it had planned to start and exited with the code 0, so the platform never served the whole offered rate.

The highest rate at which the platform broke is `flash-sale` at 21.5188 requests per second and 5.6519 iterations per second.

The platform therefore stops holding above 42.2400 requests per second.

## 5. The unit of the recorded ratio

- The recorded ratio is the delivered iteration rate divided by the planned iteration rate the profile declares, so it is a ratio of iterations per second and not of requests per second.
- The denominator of every recorded ratio:

| Profile | Recorded ratio | Denominator | Rate unit |
| --- | --- | --- | --- |
| baseline | 0.851 | `planned_iteration_rate_per_second` | iterations per second |
| flash-sale | 0.1423 | `planned_iteration_rate_per_second` | iterations per second |
| promotion-storm | None | `planned_iteration_rate_per_second` | iterations per second |
| ramp | 0.819 | `planned_iteration_rate_per_second` | iterations per second |
| recovery | 0.8861 | `planned_iteration_rate_per_second` | iterations per second |
| spike | 0.0232 | `planned_iteration_rate_per_second` | iterations per second |

- The runs that the harness produced before it planned the arrival rate in iterations per second divided the delivered iteration rate by the request rate the profile declares, so the table above names the denominator of every recorded ratio.
- The requests per iteration the harness declares for the scenario and the requests per iteration the recorded results show:

| Profile | Declared requests per iteration | Observed requests per iteration |
| --- | --- | --- |
| baseline | 2 | 2.0696 |
| flash-sale | 3 | 3.8074 |
| promotion-storm | 2 | 1.0 |
| ramp | 4 | 5.0693 |
| recovery | 2 | 2.0313 |
| spike | 4 | 10.9784 |

- The observed value is `requests / iterations` of the recorded result. The `full` scenario issues one request more per iteration than the harness declares, so the request equivalent of a planned arrival rate of that scenario is higher than the table of section 3 states.
- The ratio of a profile that failed is not a statement about the capacity of the platform, because the failure was recorded against a business outcome that the harness of that run booked as a failure when the gateway shed the request.

## 6. Does the recorded run still bind to the current source

**No.**

- Reason: the recorded results do not hold the fields the current harness records (platform_error_rate, transport_failure_rate), so they were produced by a harness whose measurement differs from the current one.
- The recorded results do not hold the fields the current harness writes into every run: platform_error_rate, transport_failure_rate.

## 7. The run that must be repeated

The whole sweep of this run must be repeated with the harness at the current revision, because the measurement itself changed: the profiles are now planned in iterations per second, the planned rate is recorded beside the delivered rate, a run that delivers less than the accepted ratio of the planned rate fails, a request the gateway shed is booked as shedding and not as a business failure, and every virtual user gets its own customer.

The runs to repeat are these six, in the order they were measured:

- `flash-sale-1795262286`
- `spike-1795268516`
- `promotion-storm-1795269071`
- `baseline-1795269895`
- `recovery-1795271066`
- `ramp-1795286502`

## 8. The files this document was derived from

- `production-audit/harness/loadtesting/profiles.py` — the profile definitions and the requests per iteration of every scenario.
- `production-audit/harness/loadtesting/runner.py` — how the runner records the rates and the outcome.
- `production-audit/harness/loadtesting/invariants.py` — the eight invariants that are verified after every run.
- `production-audit/harness/loadtesting/capacity.py` — the derivation that produced this document.
- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/result.json` — the recorded outcome of every profile.
- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/k6-stdout.log` — the arrival plan the load generator was given.
- `production-audit/runs/run-20261118-a2b6e6fc/load-results/<run>/k6-summary.json` — the raw counters of the load generator.

