# Wave D — Residual Bug Sweep Report (2026-09-03)

> Status: PARTIAL. Evidence is updated to current HEAD; remaining findings are not silently marked done.
> Scope: Notification, auth, payment/webhook, and remaining M+L items.
> Observed HEAD: 90cea14 (2026-09-03; origin/main).

## Evidence-backed work

| Finding | Commit evidence | Honest result |
|---|---|---|
| H19 safe malformed event-id handling | 4f70cbf changes NotificationWriter, NotificationServiceImpl, and its test | notification-service test run: NotificationServiceImplTest 12/0/0/0; NotificationWriterTest 16/0/0/0; related notification ITs also 0 failures. |
| H1/H2/H5/H6/H30 auth lifecycle, rollback observability, identity, role/path tightening | 6668566 changes auth services/controller, Keycloak client, config, and tests | auth-service test run: UserServiceImplTest 19/0/0/0, AuthServiceImplTest 3/0/0/0, SecurityFilterChainIntegrationTest 8/0/0/0, controller tests 12/0/0/0, RoleServiceImplTest 10/0/0/0. |
| H27 webhook timestamp/replay protection | 190e136 (plus f0c6308/a1f329f) | WebhookSignatureVerifierTest 17/0/0/0; StripeWebhookIT 5/0/0/0; PaymentWebhookControllerTest 4/0/0/0. Implemented and evidenced by focused payment run. |

## Tests actually run

- ./mvnw -T1C -pl auth-service -am test: exit 0; cited auth reports are all 0 failures/errors/skips.
- ./mvnw -T1C -pl notification-service -am test: exit 0; cited notification reports are all 0 failures/errors/skips.
- ./mvnw -T1C -pl payment-service -am test: exit 0; cited webhook reports are all 0 failures/errors/skips.
- ./mvnw -T1C -pl rating-service -am test: exit 0; RatingOutboxRelayTest 4/0/0/0 and RatingOutboxClaimConcurrencyIT 2/0/0/0.
- ./mvnw -T1C -pl gateway-service -am test: exit 1; 1 failure in AdminIpAllowlistFilterTest.firstForwardedEntryIsDecisive (expected 200, actual 403) after the current forwarded-IP behavior. No gateway green claim.

## Skipped / unresolved findings

H22 (SMTP fallback recipient configuration), H23 (NotificationWriter repository stereotype), H26 (OrderLifecycleEvent record), and remaining M+L items, including shipping M1–M10 and L1–L10, have no verified commit or test result in the current history. They remain open. In particular, shipping M1 remains unresolved; do not infer it from media-service M1 work.

No Wave D boot proof, per-service battery, full root green result, or production-readiness closure is claimed.

## Verified commit inventory

4f70cbf (notification) and 6668566 (auth). a48f158 is unrelated documentation. No production changes are made by this report.
