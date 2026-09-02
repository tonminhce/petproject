# Stripe Provider — Manual Smoke Checklist (C5 Task 6)

- Date: 2026-09-02
- Status: **skipped-awaiting-keys** — the automated suites (StripeProviderTest,
  WebhookEventServiceStripeTest, StripeWebhookIT) run with locally-signed
  payloads and mocked SDK entry points; NO real Stripe keys were used in this
  epic. The steps below need ops-provisioned TEST-mode keys and a real Stripe
  TEST account, so they are intentionally NOT automated.
- Scope: compose boots payment-service with `SHOP_PAYMENT_PROVIDER=mock` by
  default; Stripe activation is a runtime env decision (spec §3).

## Preconditions (ops)

1. Stripe TEST account → Developers → API keys → copy `sk_test_*` (secret key).
2. Stripe TEST account → Developers → Webhooks → "Add endpoint" →
   `http://<payment-service-host>:8085/api/v1/webhooks/payments/stripe`
   (locally: use `stripe listen --forward-to`, see step 5) → copy `whsec_*`.
3. Stripe CLI installed (`brew install stripe/stripe-cli/stripe`) and
   `stripe login` done.

## Smoke steps

1. Set the activation env (local `.env` — never committed):

   ```bash
   SHOP_PAYMENT_PROVIDER=stripe
   SHOP_PAYMENT_STRIPE_SECRET_KEY=sk_test_xxx
   SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET=whsec_xxx
   ```

2. `docker compose up -d payment-service` — the container must start
   (fail-fast would refuse to boot with `provider=stripe` and a blank
   secret-key, D10).

3. `curl -s http://localhost:8085/actuator/health/payment-stripe` →
   `{"status":"UP", details with "stripeAccountId":"acct_..." , "livemode":false}`
   (30s probe cache — repeated calls within 30s are served from cache).

4. `POST /api/v1/payments` with an amount/currency pair, then
   `POST /api/v1/payments/{id}/capture` → Stripe Dashboard (TEST mode) shows a
   PaymentIntent with `metadata.payment_id` = the local payment UUID and the
   `Idempotency-Key` header equal to the payments row's `idempotency_key`.
   Replaying the capture reuses the same key → Stripe returns the cached
   intent, no duplicate charge (spec D2).

5. `stripe listen --forward-to localhost:8085/api/v1/webhooks/payments/stripe`
   then `stripe trigger payment_intent.succeeded` (with the intent's id in
   scope, or complete a real test-card flow `4242 4242 4242 4242`) → the
   payment row transitions PENDING → CAPTURED; the event row for the Stripe
   event id is PROCESSED; a second delivery of the same event id is a 200
   ack no-op (dedupe).

6. Negative probes (expect `401` + `PAY-5005`, no state change):
   - webhook POST with a `Stripe-Signature` signed by the wrong secret;
   - webhook POST with a JSON-corrupting body;
   - `provider=stripe` with blank `SHOP_PAYMENT_STRIPE_SECRET_KEY` → container
     fails to boot (IllegalStateException naming the env var).

## V1 limitations (binding, spec §7)

- 3DS/SCA completes via Stripe.js on the client — storefront Phase 9 epic.
  Server creates UNCONFIRMED intents; without a client confirmation the
  payment stays PENDING until the webhook arrives.
- Cards only; no SEPA/ACH, Connect, disputes, Radar, Tax.
- Refund maps the local row → REFUNDED on `charge.refunded`; partial-refund
  semantics are out of scope.
