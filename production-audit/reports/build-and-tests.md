# Build and Test Verification

Generated: 2026-11-25T02:55:41Z

## Reactor build

- Baseline command: `./mvnw -B -ntp -T1C clean verify`
- Baseline result: PASS, exit 0, 1913 tests, 0 failures
- Baseline log: `/tmp/dsh-baseline-verify.log`

- Current command: `./mvnw -B -ntp -T 1 clean verify`
- Current result: PASS, exit 0, n/a tests, n/a failures
- Current log: `/Users/tonminh-mac/IdeaProjects/untitled5/production-audit/runs/run-20261118-a2b6e6fc/test-results/build-verify.log`

## Test suites

| Suite | Status | Exit | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- | --- |
| alerts | PASS | n/a | n/a | n/a | n/a | n/a |
| baseline | PASS | 0 | 1913 | 0 | 0 | 0 |
| build | PASS | 0 | n/a | n/a | n/a | n/a |
| comments | PASS | n/a | n/a | n/a | n/a | n/a |
| concurrency | PASS | 0 | 38 | 0 | 0 | 0 |
| concurrency_inventory | PASS | 0 | 23 | 0 | 0 | 0 |
| e2e | FAIL | n/a | n/a | n/a | n/a | n/a |
| edge | PASS | 0 | 41 | 0 | 0 | 0 |
| gateway | PASS | 0 | 141 | 0 | 0 | 0 |
| happy | PASS | 0 | 2045 | 0 | 0 | 0 |
| integration | PASS | 0 | 362 | 0 | 0 | 0 |
| k8s | FAIL | n/a | n/a | n/a | n/a | n/a |
| kafka | PASS | 0 | 266 | 0 | 0 | 0 |
| load_baseline | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_failure-during-peak | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_flash-sale | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_kafka-backlog | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_promotion-storm | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_ramp | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_recovery | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_soak | FAIL | n/a | n/a | n/a | n/a | n/a |
| load_spike | BLOCKED | n/a | n/a | n/a | n/a | n/a |
| load_sustained-peak | FAIL | n/a | n/a | n/a | n/a | n/a |
| operations | PASS | n/a | n/a | n/a | n/a | n/a |
| payment | PASS | 0 | 310 | 0 | 0 | 0 |
| performance | FAIL | n/a | n/a | n/a | n/a | n/a |
| security | PASS | 0 | 153 | 0 | 0 | 0 |
| unit | PASS | 0 | 2061 | 0 | 0 | 0 |
