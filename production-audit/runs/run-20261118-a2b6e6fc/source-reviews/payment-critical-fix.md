# Payment Critical Fix — PAY-01 / PAY-02 (payment-service)

**Repo:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Branch:** `audit/production-hardening-run-20261118-a2b6e6fc` (base commit `a2b6e6fc`)
**Write scope honoured:** every code change is under `payment-service/`. The only file outside that scope is this report.
**Tasks:** PAY-01 (CRITICAL — capture marks CAPTURED while no money moved) and PAY-02 (CRITICAL — unknown provider silently falls back to the default provider). PAY-08 (provider intent id never persisted) is closed as a side effect, as instructed.

---

## 1. Final design — `ProviderResult` and the outcome semantics

`payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java`

```java
enum Outcome { CONFIRMED, REQUIRES_CONFIRMATION, REJECTED, INDETERMINATE }

record ProviderResult(String providerEventId, Outcome outcome, String clientSecret) {

    public ProviderResult {                       // a null outcome can never be read as a success
        if (outcome == null) {
            outcome = Outcome.INDETERMINATE;
        }
    }

    public ProviderResult(String providerEventId, boolean accepted) {   // source-compatible path
        this(providerEventId, accepted ? Outcome.CONFIRMED : Outcome.REJECTED, null);
    }

    public ProviderResult(String providerEventId, Outcome outcome) {
        this(providerEventId, outcome, null);
    }

    public boolean accepted() {                   // true ONLY for CONFIRMED
        return outcome == Outcome.CONFIRMED;
    }

    static ProviderResult confirmed(String providerEventId);
    static ProviderResult requiresConfirmation(String providerEventId, String clientSecret);
    static ProviderResult rejected(String providerEventId);
    static ProviderResult indeterminate(String providerEventId);
}
```

| Outcome | Meaning | `accepted()` | PaymentServiceImpl effect (capture) |
|---|---|---|---|
| `CONFIRMED` | the provider reported the money movement for the exact amount/currency | `true` | PENDING → CAPTURED, `payment.captured.v1` published exactly once |
| `REQUIRES_CONFIRMATION` | the provider holds an intent that the **client** must confirm; the `client_secret` is carried | `false` | payment stays **PENDING**, intent id + client secret persisted, **no** capture event |
| `REJECTED` | a definitive provider refusal (card decline, 4xx, cancelled intent, amount/currency mismatch) | `false` | `BusinessException(PAY-5008 PAYMENT_PROVIDER_REJECTED)` — a definitive rejection |
| `INDETERMINATE` | the provider outcome is unknown (timeout, connection failure, idempotency replay, `processing` intent) | `false` | `BusinessException(ERR-0503 SERVICE_UNAVAILABLE, HTTP 503)` — **retryable**, never a definitive rejection |

Source compatibility: the two-argument `(providerEventId, boolean)` constructor is retained and maps `true → CONFIRMED`, `false → REJECTED`, i.e. exactly the meaning the old flag had. Every production call site was nevertheless converted to the explicit factories, and every test that constructs a `ProviderResult` was updated (see §6).

`PaymentProvider` also gained one source-compatible default method:

```java
default ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey, String providerIntentId) {
    return refund(paymentId, amount, idempotencyKey);
}
```

Existing provider implementations keep compiling; `StripeProvider` overrides it to refund against the **persisted** intent id (closes PAY-08).

### Stripe capture mapping (`StripeProvider.capture`)

| Stripe signal | Outcome |
|---|---|
| intent `succeeded` + matching amount + matching currency | `CONFIRMED` |
| intent `succeeded` + different amount | `REJECTED` (`amount-mismatch`) |
| intent `succeeded` + different currency | `REJECTED` (`currency-mismatch`) |
| intent `requires_payment_method` / `requires_confirmation` / `requires_action` | `REQUIRES_CONFIRMATION` (+ `client_secret`) |
| intent `canceled` | `REJECTED` (`intent-canceled`) |
| intent `processing` / unknown / missing status | `INDETERMINATE` |
| `CardException` | `REJECTED` (decline code) |
| `ApiConnectionException` (timeout / network) | `INDETERMINATE` — never `REJECTED` |
| `IdempotencyException` | `INDETERMINATE`, event id `idempotency-conflict` — **no fabricated identifier** any more |
| other `StripeException` with HTTP 4xx | `REJECTED` |
| other `StripeException` (5xx / no status) | `INDETERMINATE` |

Refund mapping: refund status `succeeded` + matching amount → `CONFIRMED`; `failed`/`canceled` → `REJECTED`; `pending`/unknown → `INDETERMINATE`; `ApiConnectionException`/`IdempotencyException` → `INDETERMINATE`.

---

## 2. Files created or modified

### Main sources (`payment-service/src/main/java/com/shop/paymentservice/`)

| File | Change |
|---|---|
| `provider/PaymentProvider.java` | `Outcome` enum, `ProviderResult(providerEventId, outcome, clientSecret)` with `accepted()` true only for CONFIRMED, compat boolean constructor, static factories, new `refund(..., providerIntentId)` default method. |
| `provider/StripeProvider.java` | capture maps the real intent status/amount/currency to the four outcomes, carries the `client_secret`, timeout/idempotency → INDETERMINATE, refund uses the persisted intent id (`PaymentIntent.retrieve`) with the old search only as the legacy fallback, refund status/amount verified. |
| `provider/MockProvider.java` | `@ConditionalOnProperty(name="shop.payment.provider", havingValue="mock")` — the mock PSP only exists while it is the configured provider. |
| `provider/PaymentProviderFactory.java` | `getProvider` rejects unknown **and** blank names with `PAY-5008` (no substitution); `getDefaultProvider` fails fast when the configured default is not registered (no `providers.values().iterator().next()`); the "MOCK" default fabrication is gone. |
| `provider/PaymentProviderConfig.java` | `primary` throws `IllegalStateException("Configured payment provider is not registered: …")` instead of `all.get(0)`; the property default changed from `mock` to empty so a missing configuration fails fast. |
| `provider/CodProvider.java` | uses the explicit `ProviderResult.confirmed/rejected` factories (same semantics as before). |
| `provider/MoMoProvider.java` | uses the explicit `ProviderResult.confirmed/rejected` factories (same semantics as before). |
| `provider/VNPayProvider.java` | uses the explicit `ProviderResult.confirmed/rejected` factories (same semantics as before). |
| `service/impls/PaymentServiceImpl.java` | capture/refund switch on the outcome: CAPTURED + `payment.captured.v1` only on CONFIRMED; PENDING + persisted intent id/client secret and **no** event on REQUIRES_CONFIRMATION; retryable 503 on INDETERMINATE; PAY-5008 on REJECTED. Provider resolution is now strict (registered name only) for capture/refund; a new payment without a requested provider still uses the configured default. |
| `dto/PaymentResponse.java` | carries `providerIntentId` and `clientSecret` to the caller. |
| `entity/Payment.java` | new columns `provider_intent_id` and `client_secret`. |
| `service/WebhookEventService.java` | the Stripe carrier payload carries the PaymentIntent id and `process` persists it on the payment row, so a webhook-driven capture is refundable against the stored intent id. |
| `webhook/WebhookPayload.java` | new nullable `providerIntentId` component. |

### Resources

| File | Change |
|---|---|
| `src/main/resources/db/changelog/changelog-009-provider-intent-reference.yaml` | **new** changeset (see §3). |
| `src/main/resources/db/changelog/db.changelog-master.yaml` | registers `changelog-009-provider-intent-reference.yaml` (no existing changeset modified). |

### Tests

| File | Change |
|---|---|
| `src/test/java/com/shop/paymentservice/PaymentCaptureConfirmationIT.java` | **new** — 6 integration tests asserting persisted state and emitted outbox events (see §5). |
| `src/test/java/com/shop/paymentservice/provider/PaymentProviderRoutingTest.java` | **new** — 5 tests for the factory/config routing rules (see §5). |
| `src/test/java/com/shop/paymentservice/support/ScriptedPaymentProvider.java` | **new** — a scripted provider bean used by the IT to produce each outcome. |
| `src/test/java/com/shop/paymentservice/service/impls/PaymentServiceImplTest.java` | 5 new tests; existing capture/refund tests adapted to the new call shape and outcome API. |
| `src/test/java/com/shop/paymentservice/provider/StripeProviderTest.java` | 2 new tests; 5 existing tests changed (they encoded the buggy "accepted" semantics). |
| `src/test/java/com/shop/paymentservice/provider/PaymentProviderConfigTest.java` | 2 existing assertions changed: the mock bean is now absent when another provider is configured. |
| `src/test/java/com/shop/paymentservice/StripeWebhookIT.java` | the mocked `PaymentIntent` fixture now reports amount/currency (the capture now verifies them). |
| `src/test/java/com/shop/paymentservice/controller/BackofficePaymentControllerTest.java` | the `PaymentResponse` fixture gained the two new components. |

No file outside `payment-service/` was touched (except this report). No `git commit`, no `git push`.

---

## 3. Liquibase changeset

* **Changeset id:** `009-provider-intent-reference`
* **File:** `payment-service/src/main/resources/db/changelog/changelog-009-provider-intent-reference.yaml`
* **Author:** `production-audit`
* **Registered in:** `payment-service/src/main/resources/db/changelog/db.changelog-master.yaml` (appended after `changelog-008-partial-refund.yaml`)
* **Changes (additive, safe on an existing database):**
  * `payments.provider_intent_id` — `varchar(255)`, nullable
  * `payments.client_secret` — `varchar(255)`, nullable
  * index `idx_payments_provider_intent_id` on `(provider_intent_id)`
  * index `idx_payments_provider_intent` on `(provider, provider_intent_id)`
* No existing changeset was modified or dropped; no data backfill, no NOT NULL, no unique constraint — a rolling deployment is safe (old binaries ignore the new columns).

---

## 4. Commands executed

| # | Command | Exit code | Result |
|---|---|---|---|
| 1 | `./mvnw -B -ntp -pl payment-service -am test` (baseline, before any change) | **0** | BUILD SUCCESS — payment-service: **32 test classes / 205 tests / 0 failures / 0 errors / 0 skipped**; the whole 10-module reactor green |
| 2 | `./mvnw -B -ntp -pl payment-service -am test -Dtest='com.shop.paymentservice.**.*Test' -Dsurefire.failIfNoSpecifiedTests=false` (unit-test fast loop, run 3×) | 1, then 0, then 0 | first iteration failed on 2 of my own new tests (see §6); final iteration **0** — 182 unit tests green |
| 3 | `./mvnw -B -ntp -pl payment-service -am test -Dtest='com.shop.paymentservice.**.*IT' -Dsurefire.failIfNoSpecifiedTests=false` (integration-test loop, run 2×) | 1, then 0 | first iteration failed on 2 tests of mine (see §6); final iteration **0** — 41 integration tests green (Postgres + Kafka Testcontainers) |
| 4 | **`./mvnw -B -ntp -pl payment-service -am test`** (mandated verification, final) | **0** | **BUILD SUCCESS** — payment-service: **34 test classes / 223 tests / 0 failures / 0 errors / 0 skipped**; reactor total time 01:22 min (re-run after the comment cleanup: exit 0, 223 tests, 01:41 min) |

Test-count delta: 205 → 223 (**+18 tests**: 11 in the two new classes, 7 added to existing classes). No test was disabled or skipped (`Skipped: 0` in every class).

Regressions checked explicitly: the webhook suites (`StripeWebhookIT`, `PaymentFlowIT`, `WebhookEventServiceTest`, `WebhookEventServiceStripeTest`, `PaymentWebhookControllerTest`, `WebhookSignatureVerifierTest`), the state machine (`PaymentStateMachineTest`), the outbox (`PaymentOutboxRelayOrderTest`, `PaymentOutboxRelayRecoveryIT`, `PaymentOutboxClaimConcurrencyIT`, `PaymentOutboxBatchAsyncIT`, `PaymentOutboxStrandedClaimIT`, `OutboxRetentionSchedulerTest`) and the provider suites (`StripeProviderTest`, `MockProviderTest`, `MoMoProviderTest`, `VNPayProviderTest`, `PaymentProviderConfigTest`, `StripeHealthIndicatorTest`, `PaymentProviderRoutingTest`) all pass unchanged in intent.

**Webhook path (PAY-01 requirement 6):** `PaymentStateMachine.transition(PENDING, CAPTURED)` is still legal (`LEGAL_TRANSITIONS` maps `PENDING → {CAPTURED, FAILED}`), the webhook handler was not changed in its transition logic, and the newly persisted `provider_intent_id` does not interfere with it — `StripeWebhookIT` (real Stripe wiring) and `PaymentFlowIT` (carrier webhook) both still drive PENDING → CAPTURED.

---

## 5. New tests

`payment-service/src/test/java/com/shop/paymentservice/PaymentCaptureConfirmationIT.java` (real Postgres + Kafka, asserts the persisted `payments` row and the `outbox_events` rows):

1. `confirmedCapturePersistsCapturedAndPublishesExactlyOneCaptureEvent` — happy path: the row is CAPTURED/previousStatus PENDING, the intent id is stored, **exactly one** `payment.captured.v1` outbox row.
2. `refundThroughTheOwningProviderReachesRefunded` — happy path: REFUNDED/previousStatus CAPTURED, one `payment.refunded.v1` row, and the refund was issued with the **stored** intent id.
3. `requiresConfirmationKeepsPaymentPendingAndStoresTheClientSecret` — edge case: status stays PENDING, `provider_intent_id` + `client_secret` persisted, **no** capture event.
4. `captureOfAnUnregisteredProviderIsRejectedAndDoesNotFallBack` — edge case: `PAY-5008`, the row stays PENDING, no capture event, the default provider was not used.
5. `indeterminateCaptureRaisesARetryableErrorAndLeavesThePaymentPending` — edge case: `ERR-0503` / HTTP 503, the row is neither FAILED nor CAPTURED, no capture event.
6. `theDefaultMockProviderCannotBackAPaymentOfAnotherProvider` — regression: a payment owned by `stripe` (not registered) cannot be refunded by the default mock; the row stays CAPTURED, no refund event, the owning provider was never called.

`payment-service/src/test/java/com/shop/paymentservice/provider/PaymentProviderRoutingTest.java`:

7. `unknownProviderName_isRejectedAndNeverSubstitutedByTheDefaultProvider`
8. `blankProviderName_isRejectedInsteadOfDefaulting`
9. `unregisteredDefaultProvider_failsFastInsteadOfPickingAnArbitraryProvider`
10. `primaryBeanFailsFastWhenTheConfiguredProviderIsNotRegistered`
11. `mockProviderBeanIsAbsentWhenAnotherProviderIsConfigured`

`payment-service/src/test/java/com/shop/paymentservice/service/impls/PaymentServiceImplTest.java`:

12. `capture_requiresConfirmation_keepsPaymentPendingAndStoresIntentIdAndClientSecret`
13. `capture_indeterminate_throwsRetryableErrorAndLeavesThePaymentPending`
14. `capture_paymentOfAnUnregisteredProvider_isRejectedAndNeverFallsBackToTheDefaultProvider`
15. `refund_capturedPayment_passesTheStoredProviderIntentIdToTheOwningProvider`
16. `refund_providerIndeterminate_throwsRetryableErrorAndLeavesThePaymentCaptured`

`payment-service/src/test/java/com/shop/paymentservice/provider/StripeProviderTest.java`:

17. `captureSucceededIntentWithAnotherAmount_isRejected`
18. `refundUsesTheStoredProviderIntentId_withoutSearch`

---

## 6. Pre-existing tests I had to change, and why

| Test | Why it changed |
|---|---|
| `StripeProviderTest.captureRequiresActionIntent_isAccepted_scaCompletesViaWebhook` | **It encoded the bug**: an unconfirmed (`requires_action`) intent was asserted to be `accepted() == true`. Renamed to `captureRequiresActionIntent_requiresClientConfirmation_andCarriesTheClientSecret` and it now asserts `REQUIRES_CONFIRMATION`, `accepted() == false` and that the `client_secret` is carried. |
| `StripeProviderTest.captureIdempotencyConflict_returnsCachedIntentAsAccepted` | **It encoded the bug**: an idempotency replay was asserted to be accepted with a fabricated `"idempotent-<key>"` id. Renamed to `captureIdempotencyConflict_isIndeterminate_andCarriesNoFabricatedIntentId`; asserts `INDETERMINATE`, `accepted() == false`, id `idempotency-conflict`. |
| `StripeProviderTest.captureStripeInfrastructureError_mapsToRejected` | **It encoded the bug**: a Stripe timeout was asserted to be a rejection. Renamed to `captureStripeTimeout_isIndeterminate_notRejected`; asserts `INDETERMINATE`. |
| `StripeProviderTest.refundStripeError_mapsToRejected` | Same bug on the refund path (timeout reported as a rejection). Renamed to `refundStripeTimeout_isIndeterminate_notRejected`. |
| `StripeProviderTest.refundIdempotencyConflict_returnsCachedRefundAsAccepted` | **It encoded the bug** (fabricated id + accepted). Renamed to `refundIdempotencyConflict_isIndeterminate_andCarriesNoFabricatedRefundId`. |
| `StripeProviderTest.captureSucceededIntent_isAccepted_withIntentIdAsProviderEvent` | Not buggy, but it now also asserts `outcome() == CONFIRMED`; the shared `intent(...)` fixture additionally reports amount `9800` / currency `usd` because the capture now verifies the amount and currency. |
| `StripeProviderTest.refundSucceeds_withRefundIdempotencyKeyDerivedFromPaymentKey` | The `Refund` fixture now reports `amount = 9800` (the refund now verifies the amount). |
| `StripeWebhookIT.duplicateIdempotencyKeyReturnsSameRowWithoutExtraSdkCalls` | The statically mocked `PaymentIntent` reported only `id`/`status`; it now also reports `amount = 9800` and `currency = "usd"`, otherwise the new amount verification rejects the capture. The test's actual subject (idempotency, one SDK call) is unchanged. |
| `PaymentProviderConfigTest.codResolvesAsPrimaryWhenSelected` | Asserted `hasBean("mockProvider")` — the mock is now absent when another provider is configured; changed to `doesNotHaveBean("mockProvider")`. |
| `PaymentProviderConfigTest.stripeSelectedWhenConfiguredWithSecretKey` | Same: `hasBean("mockProvider")` → `doesNotHaveBean("mockProvider")`. |
| `PaymentServiceImplTest.capture_pendingPayment_callsProviderWithPaymentFields` / `capture_providerRejected_throwsPaymentProviderRejected` / `refund_capturedPayment_callsProvider` / `refund_providerRejected_throwsPaymentProviderRejected` | Adapted to the new API: explicit `ProviderResult` factories, `provider.name()` stubbed (the service now verifies that the wired provider is the owning one), and the refund stub moved to the 4-argument `refund(..., providerIntentId)` call. Assertions and intent unchanged. |
| `BackofficePaymentControllerTest.sampleResponse()` | The `PaymentResponse` record gained two components; the fixture was extended (`pi_9001`, `pi_secret_9001`). |

No test was deleted, weakened, skipped or disabled.

---

## 7. Acceptance criteria, and what I could NOT satisfy

### Satisfied

* PAY-01 — a payment is CAPTURED only on a provider-verified CONFIRMED result for the exact amount and currency; the `client_secret` is persisted and returned to the caller (never discarded); an unconfirmed intent publishes no `payment.captured.v1`.
* PAY-01 — the intent id and the client secret are persisted on the payment row; a new Liquibase changeset adds them additively.
* PAY-01 — the webhook path still drives PENDING → CAPTURED and now also stores the intent id.
* PAY-02 — an unknown (or blank) provider name is rejected with `PAYMENT_PROVIDER_REJECTED` and is never routed to another provider; the mock PSP is only registered when it is the configured provider; the `primary` bean fails fast; a refund can only reach REFUNDED with a CONFIRMED result from the provider that owns the payment.
* PAY-08 (side effect) — the provider intent id is stored with the payment and the refund uses it instead of the eventually-consistent Stripe Search API.

### Not satisfied / deliberately left out — stated plainly

1. **No dedicated "retryable" payment error code.** `ErrorCode` lives in `utils/common-core`, outside my write scope, and has no PAY-level retryable code. An INDETERMINATE outcome therefore raises `ErrorCode.SERVICE_UNAVAILABLE` (**ERR-0503**, HTTP **503**), while a definitive refusal keeps `PAYMENT_PROVIDER_REJECTED` (**PAY-5008**, HTTP 502). The distinction is observable in the HTTP status and in the error code, but if the fleet wants a PAY-5xxx retryable code, `common-core` has to be extended.
2. **VNPay / MoMo / COD still report `CONFIRMED` at capture time.** They only produce a redirect URL / QR reference / COD receipt — no money moves at capture, so they are the same defect class as PAY-01. I kept their previous semantics because the mandate is the Stripe path and the source-compatible path requires existing implementations to keep their meaning, but they should be re-classified as `REQUIRES_CONFIRMATION` in a follow-up finding.
3. **The client secret is returned by every `PaymentResponse`** (capture, detail, list, backoffice). The requirement is that the response "must be able to carry the client secret"; restricting it to the capture response only would need a second factory method and a decision about the backoffice surface, which I did not take unilaterally.
4. **`shop.payment.provider` still defaults to `mock`** in `application.yml` (the compose default). The mock is now *unregistered* for every non-mock deployment, but a deployment that never sets `SHOP_PAYMENT_PROVIDER` still boots with the mock PSP. Making the default fail-fast requires editing `docker-compose.yml`, outside my write scope.
5. **Legacy rows without an intent id still fall back to the Stripe Search API** on refund (eventually consistent). They are migrated to the stored-intent path as soon as they are captured again or confirmed by webhook.
6. **The PSP call still runs inside the `@Transactional` boundary** (PAY-10) and the Stripe client still has no explicit connect/read timeout — out of scope for PAY-01/PAY-02.
7. **PAY-09 is only partially addressed**: the fabricated `"idempotent-<key>"` identifier is gone and a timeout is no longer a rejection, but the "reconcile an indeterminate outcome by idempotency key" design is not implemented.
8. **PAY-03 … PAY-07, PAY-10 … PAY-24 were not touched** — they are separate registry entries; `PaymentStateMachine` (self-transitions, PAY-03) was deliberately left alone.

### Deliberate behaviour changes to flag for the orchestrator

* `POST /api/v1/payments` with an explicit `provider` that is not registered now fails with **PAY-5008** instead of silently creating a payment bound to the default provider (e.g. `provider="stripe"` on a mock deployment). The order-service never sends a provider, so no in-repo caller is affected; the Postman collection has such requests and they now fail loudly by design.
* `PaymentResponse` gained the fields `providerIntentId` and `clientSecret` (additive JSON change; request DTOs, HTTP routes and Kafka topic names are unchanged).
* A deployment whose configured provider is not registered now **fails to start** instead of silently using the first registered provider.

---

## 8. Constraint compliance

* No comments were added in any file I wrote (source or test); one stale Javadoc block in `StripeProvider` was removed because it described the old "intent id was never persisted" design.
* No public HTTP route, request DTO shape or Kafka topic name was changed.
* No test was disabled, skipped or deleted; every change to a pre-existing test is listed in §6 with its reason.
* No file outside `payment-service/` was modified, except this report.
* No `git commit`, no `git push`.
