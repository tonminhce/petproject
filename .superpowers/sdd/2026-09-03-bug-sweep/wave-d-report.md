# Wave D — Residual Bug Sweep Report (2026-09-03)

> Status: PARTIAL. Only the notification/auth commits listed below are evidenced. Remaining findings are not silently marked done.
> Scope: Notification, auth, and remaining M+L items.
> Observed HEAD: a48f158.

## Evidence-backed work

| Finding | Commit evidence | Honest result |
|---|---|---|
| H19 safe malformed event-id handling | 4f70cbf changes NotificationWriter, NotificationServiceImpl, and its test | Implemented in source; standalone test result not recorded. |
| H1/H2/H5/H6/H30 auth lifecycle, rollback observability, identity, role/path tightening | 6668566 changes auth services/controller, Keycloak client, config, and tests | Implemented in source; standalone test result not recorded. |

## Tests actually run

- ./mvnw -T1C -pl utils/common-patterns test: 6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- ./mvnw -T1C test was run twice and exited 1. Available output contains Testcontainers Kafka/Redis shutdown and reconnect messages. No green fleet count is claimed.

## Skipped / unresolved findings

H22 (SMTP fallback recipient configuration), H23 (NotificationWriter repository stereotype), H26 (OrderLifecycleEvent record), and remaining M+L items, including shipping M1–M10 and L1–L10, have no verified commit or test result in the current history. They remain open.

No Wave D boot proof, per-service battery, full root green result, or production-readiness closure is claimed.

## Verified commit inventory

4f70cbf (notification) and 6668566 (auth). a48f158 is unrelated documentation. No production changes are made by this report.
