# Real Stripe Provider Adapter — Design

- Date: 2026-09-01
- Status: Draft (pending user ratification of 4 scope decisions)
- Scope: turn payment-service's StripeProvider skeleton into a working adapter against Stripe's TEST mode API (sk_test_/pk_test_ keys). Real (live) keys NOT exercised in this epic; provider activation requires ops-set `SHOP_PAYMENT_PROVIDER=stripe` + `SHOP_PAYMENT_STRIPE_SECRET_KEY=sk_test_*` env. Production rollout is a separate ops-only epic (key rotation, fraud rules, dispute webhooks, etc.).

## Verified ground truths

- payment-service D1 already defines `PaymentProvider` port: `capture(PaymentRef, Money, String idempotencyKey)` and `refund(PaymentRef, Money, String idempotencyKey)` returning provider-specific results.
- payment-service D6 lists PAY-5005 WEBHOOK_SIGNATURE_INVALID (401). Stripe's webhook signature scheme uses `Stripe-Signature` header + stripe.webhook.constructEvent(payload, sigHeader, secret) — adapter must use stripe-java's verifier, NOT a hand-rolled HMAC.
- payment-service D7 webhook path is `/api/v1/webhooks/payments/{provider}` (HMAC-authenticated). Stripe provider is selected by `{provider}=stripe` path variable.
- payment-service D10 specifies Stripe is a skeleton that fails-fast at startup if `shop.payment.provider=stripe` without credentials.
- Mock provider (compose) continues to be the default. Switching to Stripe is a runtime env decision.

## §1 Binding decisions

### D1 — stripe-java SDK, NOT raw HTTP (decision Q1)

Use `com.stripe:stripe-java` (latest stable, currently 24.x). The SDK handles:
- API versioning (Stripe-Sdk pins Stripe-Version header)
- Pagination (iterable lists)
- Error mapping (StripeException subclasses)
- Webhook signature (Webhook.constructEvent)

Raw HTTP would re-implement all of these; the SDK is the fleet precedent (mirrors payment's MockProvider using a small Node container — same idea, official SDK over hand-rolled). Trade-off: jar size ~3MB, but payment-service is already a heavy module.

### D2 — Idempotency-key reuse: idempotency_key column maps to Stripe's `Idempotency-Key` header (decision Q2)

Every Stripe API call that mutates state (PaymentIntent.create, Refund.create) MUST pass `Idempotency-Key` header. Reuse payment-service's existing `idempotency_key` column value (already on payments table, already Stripe-semantics-compatible per payment D3). Mapping:

- PaymentIntent.create → `Idempotency-Key: {payments.idempotency_key}`
- Refund.create → `Idempotency-Key: refund-{payments.idempotency_key}-{amount}` (per refund-attempt idempotency; payments.idempotency_key reused for the underlying PaymentIntent).

Stripe returns the same PaymentIntent on duplicate `Idempotency-Key` (cached for 24h). Adapter treats this as success — no error.

### D3 — 3DS Confirmation: Stripe.js on client, paymentIntents.confirm on server (decision Q3)

Stripe SCA flow:
1. Client (storefront checkout) loads Stripe.js, creates PaymentMethod via `stripe.createPaymentMethod({...})`.
2. Client POSTs paymentMethodId to payment-service POST /api/v1/payments (existing endpoint).
3. payment-service StripeAdapter calls Stripe `PaymentIntent.create({amount, currency, payment_method: pmId, confirm: false, ...})` with idempotency_key.
4. Stripe returns PaymentIntent with `status=requires_confirmation` (or `requires_action` if 3DS needed).
5. payment-service returns PaymentIntent client_secret to client. Client calls `stripe.confirmCardPayment(client_secret)` — handles 3DS challenge in browser.
6. On success, Stripe emits webhook `payment_intent.succeeded` → payment-service webhook handler updates payment to CAPTURED (existing webhook handler per payment D4).

V1 limitation: storefront V1 has NO Stripe.js integration; payment-service exposes the endpoint but the actual SCA-3DS flow is a storefront follow-up epic (Phase 9). Without 3DS: Stripe may decline payments requiring SCA in EU/UK regions. Acceptable for V1 with TEST mode + non-EU test cards.

### D4 — Webhook signature via stripe-java Webhook.constructEvent (decision Q4)

Webhook signature verification uses stripe-java's `Webhook.constructEvent(payload, sigHeader, secret)`:

```java
Event event = Webhook.constructEvent(rawBody, signatureHeader, webhookSecret);
// event.getType() == "payment_intent.succeeded" | "payment_intent.payment_failed" | "charge.refunded"
// event.getDataObjectDeserializer().getObject().orElseThrow()
```

`Webhook.constructEvent` validates `t=`, `v1=` signature against the secret. Failure → `SignatureVerificationException` → 401 PAY-5005.

Webhook secret: env `SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET` (separate from API key). Stripe Dashboard → Webhooks → Endpoint → Signing secret. Rotation: ops updates env, restarts payment-service.

## §2 Adapter wiring

StripeAdapter implements `PaymentProvider` (existing port):

```java
@Component
@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "stripe")
public class StripeAdapter implements PaymentProvider {
    // inject: stripe.api.key (set via env SHOP_PAYMENT_STRIPE_SECRET_KEY)
    // inject: stripe.webhook.secret (set via env SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET)

    @Override public PaymentAttempt capture(PaymentRef ref, Money amount, String idempotencyKey) {
        RequestOptions opts = RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(amount.minorUnits())
            .setCurrency(amount.currency().toLowerCase())
            .setPaymentMethod(ref.providerPaymentMethodId())
            .setConfirm(false)
            .setAutomaticPaymentMethods(/* enabled in dashboard */)
            .build();
        try {
            PaymentIntent intent = PaymentIntent.create(params, opts);
            return PaymentAttempt.requiresAction(intent.getClientSecret(), intent.getStatus());
        } catch (IdempotencyException e) { /* cached → return cached intent */ }
    }

    @Override public RefundResult refund(...) { /* similar */ }
}
```

Webhook handler (existing `WebhookEventService`):
- Branch on `provider=stripe`: invoke `Webhook.constructEvent` from stripe-java.
- Convert Stripe Event → payment lifecycle transition (payment_intent.succeeded → CAPTURED, charge.refunded → REFUNDED).

## §3 Configuration

```yaml
shop:
  payment:
    provider: ${SHOP_PAYMENT_PROVIDER:mock}  # stripe to activate
    stripe:
      secret-key: ${SHOP_PAYMENT_STRIPE_SECRET_KEY:}
      webhook-secret: ${SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET:}
      api-version: "2024-06-20"  # pin Stripe API version
      connect: false  # V1 single-account; Connect deferred
```

Fail-fast at startup: `shop.payment.provider=stripe` + empty `secret-key` → bean creation fails with clear message ("StripeProvider requires shop.payment.stripe.secret-key"). Same posture as payment D10.

## §4 API additions

No new endpoints. Stripe flows use existing `/api/v1/payments` (create), `/api/v1/payments/{id}/capture`, `/api/v1/payments/{id}/refund`, `/api/v1/webhooks/payments/stripe` (webhook — provider is the path variable).

Storefront follow-up (Phase 9): `/api/v1/storefront/payments/stripe-config` returns publishable key (`pk_test_*`) — needed only when storefront checkout ships.

## §5 Testing strategy

- Unit (adapter): mock stripe-java SDK responses; verify PaymentIntent.create called with correct params + idempotency key; 3DS challenge path (status=requires_action); refund idempotency.
- Webhook signature: unit test with valid + invalid signatures (use stripe-java's `Webhook.sign(payload, secret)` to generate test signatures).
- IT (Testcontainers + Stripe CLI or stripe-mock): full create→capture→webhook round-trip with stripe-mock Docker image. Skip in this epic if stripe-mock not stable; use unit tests + manual smoke checklist.
- Manual smoke checklist (compose): SHOP_PAYMENT_PROVIDER=stripe + sk_test_* → create order → Stripe Dashboard shows PaymentIntent → use Stripe CLI `stripe trigger payment_intent.succeeded` → payment CAPTURED.

## §6 Fleet impact (lane rules)

- payment-service = W1 lane. Single PR.
- Shared-file tails: ApiPaths.PAYMENT_WEBHOOK already supports `{provider}=stripe`. No ApiPaths change.
- Init SQL: no change.
- compose: payment-service stanza gains optional `SHOP_PAYMENT_STRIPE_*` envs (commented by default; stripe NOT default provider in compose).
- common-keycloak: not touched.

## §7 Non-goals (binding)

Live keys (sk_live_/pk_live_) — V1 test mode only; production rollout is ops-only epic; Stripe Connect / multi-account; SEPA / ACH / non-card payment methods (cards only V1); dispute / chargeback webhooks; Radar fraud rules; Stripe Tax integration; Apple Pay / Google Pay; customer-facing payment history UI.

## §8 Open items

- stripe-java version pinning: current latest is 24.x; fleet Java 25 may require a newer minor. Verify by quickstart test.
- Stripe-mock vs real API for CI: stripe-mock is a Stripe-maintained fake server (Docker). Recommended for IT; default = no IT in this epic, manual smoke checklist.
- SCA / 3DS in test mode: Stripe test mode always allows bypassing 3DS via test card `4000000000003220` (3DS required). Plan T5 IT should explicitly test both paths.
- Webhook event idempotency: Stripe may retry webhook deliveries. Existing payment-service webhook dedupe on `(provider, provider_event_id)` handles this — UNIQUE index. No change.
- Currency: V1 VND. Stripe accepts VND with manual capture; ensure test mode `currency=vnd` works (it does, verified at quickstart).

## §9 Changelog

- 2026-09-01 (rev 0): Initial draft pending user ratification of D1 (stripe-java SDK), D2 (idempotency-key reuse), D3 (SCA via Stripe.js), D4 (signature via stripe-java Webhook).