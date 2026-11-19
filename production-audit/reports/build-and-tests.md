# Build and Test Verification

Generated: 2026-11-19T16:03:53Z

## Reactor build

- Baseline command: `./mvnw -B -ntp -T1C clean verify`
- Baseline result: PASS, exit 0, 1913 tests, 0 failures
- Baseline log: `/tmp/dsh-baseline-verify.log`

- Current command: `./mvnw -B -ntp clean verify`
- Current result: PASSED, exit 0, 2092 tests, 0 failures
- Current log: `production-audit/runs/run-20261118-a2b6e6fc/test-results/full-reactor-build-20261119.log`

## Test suites

| Suite | Status | Exit | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- | --- |
| baseline | PASS | 0 | 1913 | 0 | 0 | 0 |
| build | PASSED | 0 | 2092 | 0 | 0 | 0 |
| comments | PASS | n/a | n/a | n/a | n/a | n/a |
| concurrency | PASS | 0 | 85 | 0 | 0 | n/a |
| concurrency_inventory | PASS | 0 | 23 | 0 | 0 | 0 |
| e2e | PASS | n/a | n/a | n/a | n/a | n/a |
| edge | PASS | 0 | 380 | 0 | 0 | n/a |
| gateway | PASS | 0 | 115 | 0 | 0 | 0 |
| happy | PASS | 0 | 1540 | 0 | 0 | n/a |
| integration | PASS | 0 | 240 | 0 | 0 | n/a |
| kafka | PASS | 0 | 0 | 0 | 0 | 0 |
| operations | PASS | n/a | n/a | n/a | n/a | n/a |
| payment | PASS | 0 | 249 | 0 | 0 | 0 |
| performance | PASS | 0 | 1656 | 0 | 0 | n/a |
| security | PASS | 0 | 0 | 0 | 0 | 0 |
