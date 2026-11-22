# Independent E2E + Stripe Sandbox Evidence — run-20261122-f242158
(original run 18:46-18:58 UTC 2026-11-21; restored verbatim from session evidence after 03:00 wipe)

Environment: local Docker Compose stack, all 14 services healthy, images built 2026-11-21T18:00-18:41Z from this worktree. Gateway http://localhost:8080. Keycloak realm ecommerce (testuser[USER], adminuser[ADMIN,MANAGER], password grant on public client ecommerce-client).

## Auth
- Password grant tokens for testuser and adminuser: OK. Tokens invalidated by realm container restarts.

## Checkout saga — happy path (order 48deffcc-a061-4df2-97e9-dea2c0c278ba)
- POST /api/v1/carts/me/items (product 14469db6…, qty 10) → 200, subtotal 24,990.00 VND.
- POST /api/v1/orders → 200 PENDING, total 24,990.00 VND, taxAmount 0 (tax disabled/misconfigured — ORD-7), trackingCode assigned at creation.
- Payment creation guard: USER token → ERR-0403; hasAnyRole(SERVICE,ADMIN) + ServiceCallerGuard.requireCaller("order-service"); human ADMIN bypasses client check (utils/common-security/.../ServiceCallerGuard.java:24-26). order-service never creates payments — payment is admin/service-initiated only.
- Payment e24d582c-08a7-4a5c-a955-8197db8de28b PENDING → POST /{id}/capture → CAPTURED, providerIntentId pi_3UeJOE00k8Tlns6n1uunCXGc (REAL Stripe test API; container env SHOP_PAYMENT_PROVIDER=stripe, api-base=https://api.stripe.com, sandbox=true, auto-confirm=true, pm_card_visa).
- Stripe-side independent verification (GET /v1/payment_intents/pi_3UeJOE00k8Tlns6n1uunCXGc with sk_test): status=succeeded, amount=24990, currency=vnd, livemode=false, metadata.payment_id=e24d582c-…, metadata.idempotency_key=audit-e2e-pay-002, latest_charge ch_3UeJOE00k8Tlns6n1m9JgiOd.
- Order auto-CONFIRMED 18:52:16.435583Z without manual call. Chain: payments.outbox_events payment.captured.v1 → topic shop.payment.lifecycle.v1 status=SENT 18:52:04 → order-service consumer → CONFIRMED. Transactional outbox + Kafka + idempotent consumer verified end to end with real money in test mode.
- Inventory: reservation 4acc288d COMMITTED; available 99→89, reserved=2 (unrelated stale PENDING). Accounting invariant held.
- Shipment d43fbb74 CREATED 18:52:22 automatically on confirm. /ship → SHIPPED 18:54:04; /deliver → DELIVERED 18:54:05. Full lifecycle.
- State guard: confirm on CONFIRMED → ORD-4004 (guard works).

## Stripe negative cases (live, real API)
- amount_too_small: capture 4,498.20 VND → InvalidRequestException amount_too_small, request-id req_9eU7B5e5fxUSk3 ("Amount must convert to at least 50 cents" — matches Stripe docs on USD-equivalent minimum); surfaced PAY-5008; payment stayed PENDING; NO orphan intent.
- Refund: 5000 VND + refundReference ae5f0a1a-… → PARTIALLY_REFUNDED; Stripe exactly one refund re_3UeJOE00k8Tlns6n1gMSewQD (5000, succeeded).
- Duplicate refund (same refundReference) → no double refund locally OR on Stripe. Over-refund 999,999 → PAY-5007 rejected, no state change.

## Webhook receiver tests (synthetic payload, REAL signature crypto)
- Valid HMAC (Stripe-Signature t=…,v1=… with SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET) → 200 accepted; stored in payment_events (unique (provider,provider_event_id)).
- Replay same event id → 200 no-op (dedupe).
- Invalid signature → 401 PAY-5005, nothing stored.
- Unknown payment_id → FAILED_RETRYABLE retry_count=1 (WebhookRetryScheduler owns retries).

## Genuine Stripe webhook delivery — NOT VERIFIED (structural gap, P1)
- Stripe account has ONE registered endpoint: we_1UeI4X00k8Tlns6ndTPYeII0 → https://example.com/api/v1/webhooks/payments/stripe (placeholder).
- payment_events contains ONLY synthetic evt_acceptance_* rows (prior harness) — zero genuine evt_* deliveries processed. Stripe CLI not installed on host.
- Receiving path verified; end-to-end delivery from Stripe NOT verified in this environment.

## Reservation TTL/sweeper (live)
- 67 PENDING reservations expired at 18:57:22Z; sweeper log 18:58:15Z "Expired-reservation sweep released 67 reservation(s)"; reserved_quantity 99→0; 887 EXPIRED / 1 COMMITTED — invariant consistent.

## Observability of the flow
- Structured JSON AUDIT lines (actor/action/resource/traceId) in payment-service + inventory-service; outbox rows with topics/status SENT across payments/orders; gateway + service traces present in Tempo.
