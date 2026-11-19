# Build and Test Verification

Generated: 2026-11-18T16:42:54Z

## Reactor build

- Baseline command: `./mvnw -B -ntp -T1C clean verify`
- Baseline result: PASS, exit 0, 1913 tests, 0 failures
- Baseline log: `/tmp/dsh-baseline-verify.log`

- Current command: `./mvnw -B -ntp clean verify`
- Current result: PASS, exit 0, 1931 tests, 0 failures
- Current log: `/tmp/dsh-full-verify.log`

## Test suites

| Suite | Status | Exit | Tests | Failures | Errors | Skipped |
| --- | --- | --- | --- | --- | --- | --- |
| baseline | PASS | 0 | 1913 | 0 | 0 | 0 |
| build | PASS | 0 | 1931 | 0 | 0 | 0 |
| comments | PASS | n/a | n/a | n/a | n/a | n/a |
