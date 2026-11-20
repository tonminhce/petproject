# Build and Test Verification

Generated: 2026-11-19T22:33:06Z

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
| alerts | FAIL | n/a | n/a | n/a | n/a | n/a |
| baseline | PASS | 0 | 1913 | 0 | 0 | 0 |
| build | PASS | 0 | n/a | n/a | n/a | n/a |
| comments | PASS | n/a | n/a | n/a | n/a | n/a |
| concurrency | PASS | 0 | 85 | 0 | 0 | n/a |
| concurrency_inventory | PASS | 0 | 23 | 0 | 0 | 0 |
| e2e | PASS | n/a | n/a | n/a | n/a | n/a |
| edge | PASS | 0 | 380 | 0 | 0 | n/a |
| gateway | PASS | 0 | 115 | 0 | 0 | 0 |
| happy | PASS | 0 | 1540 | 0 | 0 | n/a |
| integration | PASS | 0 | 240 | 0 | 0 | n/a |
| k8s | PASS | n/a | n/a | n/a | n/a | n/a |
| kafka | PASS | 0 | 0 | 0 | 0 | 0 |
| operations | PASS | n/a | n/a | n/a | n/a | n/a |
| payment | PASS | 0 | 249 | 0 | 0 | 0 |
| performance | PASS | 0 | 1656 | 0 | 0 | n/a |
| security | PASS | 0 | 0 | 0 | 0 | 0 |
