# Real Stripe Provider Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Turn payment-service's StripeProvider skeleton into a working adapter against Stripe TEST mode (sk_test_ keys). Real/live keys remain ops-only; provider activation via env.

**Architecture:** stripe-java SDK behind existing PaymentProvider port. Idempotency-key reuse from payments.idempotency_key. 3DS confirmation via Stripe.js on client + PaymentIntent.confirm on server (client flow deferred to storefront Phase 9). Webhook signature via stripe-java Webhook.constructEvent. Spec is binding authority: docs/superpowers/specs/2026-09-01-real-stripe-adapter-design.md.

**Tech Stack:** Spring Boot 4 (fleet BOM), common-core/common-spring/common-security, com.stripe:stripe-java (24.x), spring-boot-starter-web, payment-service existing structure.

**Spec:** docs/superpowers/specs/2026-09-01-real-stripe-adapter-design.md

## Global Constraints

- Provider activation is env-driven: SHOP_PAYMENT_PROVIDER=stripe + SHOP_PAYMENT_STRIPE_SECRET_KEY=sk_test_* + SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=whsec_*.
- Mock provider remains default in compose; stripe is opt-in.
- Idempotency-key reuse from payments.idempotency_key column — already exists (payment D3).
- Webhook signature via stripe-java Webhook.constructEvent — do NOT hand-roll HMAC.
- No new endpoints. Stripe flows use existing payment API surface.
- i18n: no new keys (Stripe-specific messages already covered by payment's PAY-5xxx block).

---

### Task 1: pom + Stripe SDK wiring + config properties

**Files:**
- Modify: payment-service/pom.xml — add com.stripe:stripe-java:24.x (latest stable; verify version on mvnrepository).
- Modify: payment-service/src/main/resources/application.yml — add shop.payment.stripe.{secret-key, webhook-secret, api-version, connect} block per spec §3.
- Create: payment-service/.../config/PaymentStripeProperties.java (@ConfigurationProperties shop.payment.stripe: String secretKey, String webhookSecret, String apiVersion, boolean connect).
- Modify: PaymentProviderProperties (existing) — add nested Stripe nested record OR separate config class.
- Test: PaymentStripePropertiesTest (@ConfigurationProperties binding).

- [ ] **Step 1: failing test** for properties binding.
- [ ] **Step 2: implement.** Pin Stripe API version via `Stripe.apiVersion = "2024-06-20"` in @PostConstruct when provider=stripe.
- [ ] **Step 3: run** ./mvnw -pl payment-service compile → PASS.
- [ ] **Step 4: commit** feat(payment): stripe-java SDK + config properties

### Task 2: StripeAdapter implementation

**Files:**
- Modify: payment-service/.../provider/StripeAdapter.java (skeleton exists; flesh out):
  - @Component @ConditionalOnProperty("shop.payment.provider=stripe") implements PaymentProvider.
  - capture(): PaymentIntent.create with idempotency key from payments.idempotency_key; map to PaymentAttempt (status PENDING/REQUIRES_ACTION/CAPTURED per stripe intent status).
  - refund(): Refund.create with idempotency key `refund-{originalKey}-{amount}`; map to RefundResult.
  - Constructor validates secret-key non-empty → fail-fast per D10.
- Test: StripeAdapterTest (mock Stripe SDK via Mockito — static mocking PaymentIntent.create via mockito-inline):
  - capture success (status=succeeded) → CAPTURED attempt.
  - capture requires_action (status=requires_action) → REQUIRES_ACTION attempt with client_secret.
  - capture IdempotencyException → return cached intent (mock returns same PaymentIntent).
  - capture CardException → FAILED attempt with error code.
  - refund success → RefundResult.succeeded.
  - constructor with empty secret-key → bean creation fails.

- [ ] **Step 1: failing tests** (6+ cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(payment): StripeAdapter — PaymentIntent + Refund via stripe-java

### Task 3: WebhookEventService — Stripe signature verification + event mapping

**Files:**
- Modify: payment-service/.../service/WebhookEventService.java — add branch for `provider=stripe`:
  - Read raw body (use cached body from filter — verify webhook filter caches raw body before JSON parsing).
  - Read `Stripe-Signature` header.
  - Call `Webhook.constructEvent(rawBody, sigHeader, webhookSecret)`. Throws SignatureVerificationException → 401 PAY-5005.
  - Map event.type to payment lifecycle: payment_intent.succeeded → CAPTURED; payment_intent.payment_failed → FAILED; charge.refunded → REFUNDED.
  - Extract payment_id from event.data.object (PaymentIntent or Charge), find local Payment row, transition (existing logic).
- Test: WebhookEventServiceStripeTest (mock Webhook.constructEvent):
  - Valid signature + payment_intent.succeeded → payment CAPTURED.
  - Valid signature + payment_intent.payment_failed → payment FAILED.
  - Invalid signature → 401 PAY-5005 (no state change).
  - Duplicate event (already processed) → ack no-op (existing dedupe).

- [ ] **Step 1: failing tests** (4 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(payment): WebhookEventService — Stripe signature + event mapping

### Task 4: fail-fast + health check

**Files:**
- Modify: payment-service/.../config/PaymentProviderConfig.java (existing) — fail-fast: if shop.payment.provider=stripe AND stripe.secret-key is empty → throw at @PostConstruct ("StripeProvider requires shop.payment.stripe.secret-key env").
- Add: GET /actuator/health/payment-stripe (custom health indicator) — pings Stripe API via `Balance.retrieve()` when provider=stripe; reports UP/DOWN with Stripe account id. Mock provider has no equivalent.
- Test: PaymentProviderConfigTest — empty secret-key + provider=stripe → IllegalStateException at startup.

- [ ] **Step 1: failing test.**
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(payment): StripeProvider fail-fast + health indicator

### Task 5: IT — Stripe webhook signature + idempotency

**Files:**
- Create: payment-service/.../StripeWebhookIT.java (Testcontainers PG; uses stripe-java's own `Webhook.sign(payload, secret)` to generate valid signatures; mockito-inline for static mocking):
  - Construct signed payload + signature → POST /api/v1/webhooks/payments/stripe → 200, payment CAPTURED.
  - Tampered payload → 401 PAY-5005.
  - Replay same event (same event.id) → 200 ack no-op (dedupe).
  - Idempotent create: POST /api/v1/payments same idempotency_key twice → second call returns same Payment row, no second PaymentIntent.create call to Stripe (mocked).

- [ ] **Step 1: failing tests** (4 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** ./mvnw -pl payment-service test → GREEN.
- [ ] **Step 4: commit** test(payment): Stripe webhook + idempotency IT

### Task 6: compose + manual smoke checklist

**Files:**
- Modify: docker-compose.yml — payment-service stanza: ADD commented env block for SHOP_PAYMENT_STRIPE_* (commented because compose default is mock):

```yaml
  # To activate Stripe:
  # - SHOP_PAYMENT_PROVIDER=stripe
  # - SHOP_PAYMENT_STRIPE_SECRET_KEY=sk_test_xxx
  # - SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=whsec_xxx
```

- [ ] **Step 1:** docker compose config -q → exit 0.
- [ ] **Step 2:** ./mvnw -T1C install -DskipTests -q → exit 0.
- [ ] **Step 3:** Manual smoke checklist (NOT automated):
  1. Set SHOP_PAYMENT_PROVIDER=stripe + sk_test_* + whsec_* in local env.
  2. docker compose up -d payment-service.
  3. curl payment-service:8085/actuator/health/payment-stripe → 200 with account id.
  4. POST /api/v1/payments with test card → Stripe Dashboard shows PaymentIntent.
  5. stripe listen --forward-to localhost:8085/api/v1/webhooks/payments/stripe → trigger payment_intent.succeeded → payment CAPTURED.
- [ ] **Step 4: commit** chore(payment): compose stripe env template + manual smoke checklist

### Task 7: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1-D4 + §6 audit; StripeAdapter wired into PaymentProvider port; webhook signature via stripe-java Webhook.constructEvent; idempotency-key reuse; fail-fast at startup; no new endpoints; zero-regression on MockProvider; manual smoke checklist complete. Fix rounds until APPROVED.