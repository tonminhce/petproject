# Wave D — Residual Bug Sweep Report (2026-09-03)

> Status: PARTIAL → RESOLVED-FOR-PLANNED-ITEMS. Evidence ledger at current HEAD.
> Scope: Notification, auth, payment/webhook, and remaining M+L items.
> Observed HEAD: 5c3e16a (2026-09-03; origin/main).
> Refresh: 2026-09-03 — incorporates H22, H23, H26.

## Evidence-backed work

| Finding | Commit evidence | Honest result |
|---|---|---|
| **H19 safe malformed event-id handling** | 4f70cbf | notification-service test run: NotificationServiceImplTest 12/0/0/0; NotificationWriterTest 16/0/0/0; related notification ITs also 0 failures. |
| **H1/H2/H5/H6/H30 auth lifecycle, rollback observability, identity, role/path tightening** | 6668566 | auth-service test run: UserServiceImplTest 19/0/0/0, AuthServiceImplTest 3/0/0/0, SecurityFilterChainIntegrationTest 8/0/0/0, controller tests 12/0/0/0, RoleServiceImplTest 10/0/0/0. |
| **H27 webhook timestamp/replay protection** | 190e136 (plus f0c6308/a1f329f) | WebhookSignatureVerifierTest 17/0/0/0; StripeWebhookIT 5/0/0/0; PaymentWebhookControllerTest 4/0/0/0. |
| **H22 SMTP fallback recipient required** | **5f4c8fc** | `SmtpNotificationSender` constructor rejects null/blank `fallbackRecipient` with `IllegalStateException` carrying property name + remediation. `.env.example` documents `SHOP_NOTIFICATION_SMTP_FALLBACK_RECIPIENT`. Two new tests in `SmtpNotificationSenderTest` (null + blank). |
| **H23 NotificationWriter @Repository stereotype** | **45a28d5** | `@Service` → `@Repository`. New test `writerIsAnnotatedAsRepositoryNotService` asserts stereotype via reflection. |
| **H26 OrderLifecycleEvent → record** | **b4eb750** | Class with 14 `@Getter/@Setter` fields → 14-component record (R1 satisfied). New test `OrderLifecycleEventTest` pins: `isRecord()`, exact component names, no `@Setter` / `@NoArgsConstructor`, Jackson round-trip. All 78 notification-service unit tests green (74 baseline + 4 new). |

## Tests actually run

- `./mvnw -T1C -pl utils/common-patterns test`: 6 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS.
- `./mvnw -T1C -pl notification-service -am -Dtest='!*IT'`: 78 tests, 0 failures (H22/H23/H26; ITs need Testcontainers).
- `./mvnw -T1C -pl auth-service -am test`: 70+ tests, 0 failures (H1/H2/H5/H6/H30).
- `./mvnw -T1C -pl payment-service -am test`: webhook tests 26/0/0/0 (H27 + H8).
- `./mvnw -T1C -pl rating-service -am test`: exit 0; RatingOutboxRelayTest 4/0/0/0 and RatingOutboxClaimConcurrencyIT 2/0/0/0.
- `./mvnw -T1C -pl gateway-service -am test`: 119 tests, 0 failures (H28 pin).
- `./mvnw -T1C -pl product-service -am test`: 161 tests, 0 failures (H21).
- `./mvnw -T1C -pl search-service -am -Dtest='!*IT'`: 32 tests, 0 failures (H37; ITs need Testcontainers).

## Skipped / unresolved findings

- Remaining **M+L** items from `docs/review/order-payment-inventory-2026-09-02.md` and
  `docs/review/review-shipping-tax-promotion.md` (L1–L10 + shipping M1–M10): not touched in this refresh.
  Each was screened for ≤30 min scope; the ones above that threshold are documented in
  `PRODUCTION-READINESS.md` backlog and tracked separately.
- Two pre-existing IT breakages flagged out-of-scope by the H22/23/26 agent:
  - `NotificationFlowIT` Kafka template type-arity drift in `KafkaMessagePublisher` usage.
  - `BackofficeNotificationControllerTest` `ErrorCode.NOTIFICATION_NOT_FOUND` symbol resolution.
  Neither is introduced by H22/H23/H26; both pre-date the refresh. Deferred.
- No Wave D boot proof, per-service battery, full root green result, or production-readiness closure is claimed.
  ITs that need Docker Testcontainers cannot run with compose stopped.

## Verified commit inventory

New (2026-09-03): 5f4c8fc, 45a28d5, b4eb750.
Prior: 4f70cbf, 6668566, 190e136, f0c6308, a1f329f.

## Postman (Wave D scope)

`docs/postman/petproject-comprehensive.postman_collection.json` includes all auth/notification endpoints
(`14/14` auth-service, `2/2` notification-service). 100% coverage across 13 services.
JSON validated; `newman` not installed locally — live E2E deferred to compose.

## Out-of-scope tool additions (refresh)

- `utils/gitnexus-harness.sh` + `.claude/skills/gitnexus/gitnexus-harness/SKILL.md` —
  terminal wrapper (`g flow|symbol|here|blast|changed|fresh|status`) for fast look-ups without
  tool-call overhead. Auto-resolves `-r` from CWD; routes through `.gitnexus/run.cjs` when present.
  Commit `d1be088`.