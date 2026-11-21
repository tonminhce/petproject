# Independent E2E + Stripe Sandbox Evidence — run-20261122-f242158
(recreated from session evidence after external wipe; original run window 18:46-18:58 UTC 2026-11-21)

Environment: local Docker Compose stack, all 14 services healthy, images built 2026-11-21T18:00-18:41Z from this worktree. Gateway http://localhost:8080. Keycloak realm ecommerce (testuser[USER], adminuser[ADMIN,MANAGER], password grant enabled on public client ecommerce-client).

## Auth
- Password grant tokens for testuser and adminuser: OK. Tokens invalidate on Keycloak restarts (observed 401 after realm container restart).

## Checkout saga — happy path (order 48deffcc-a061-4df2-97e9-dea2c0c278ba)
- POST /api/v1/carts/me/items (product 14469db6…, qty 10) → 200, subtotal 24,990.00 VND.
- POST /api/v1/orders → 200 PENDING, total 24,990.00 VND, taxAmount 0 (no tax classes configured; see ORD-7), trackingCode assigned at creation.
- Payment create (POST /api/v1/payments) is guarded: USER token → ERR-0403; requires hasAnyRole(SERVICE,ADMIN) + ServiceCallerGuard; human ADMIN bypasses client check (ServiceCallerGuard.java:24-26). No user-facing payment initiation exists in order-service (PaymentServiceClient has only findCaptured/findRefundable/refundByOrderId — order-service never creates payments; payment is admin/service-initiated only).
- Payment e24d582c… created PENDING → POST /{id}/capture → CAPTURED, providerIntentId pi_3UeJOE00k8Tlns6n1uunCXGc (REAL Stripe test API at api.stripe.com; container env: SHOP_PAYMENT_PROVIDER=stripe, SHOP_PAYMENT_STRIPE_API_BASE=https://api.stripe.com, sandbox=true, auto-confirm-sandbox=true, sandbox-payment-method=pm_card_visa).
- Independent Stripe-side verification (GET /v1/payment_intents/pi_3UeJOE00k8Tlns6n1uunCXGc with sk_test key): status=succeeded, amount=24990, currency=vnd, livemode=false, metadata.payment_id=e24d582c-…, metadata.idempotency_key=audit-e2e-pay-002, latest_charge ch_3UeJOE00k8Tlns6n1m9JgiOd.
- Order auto-CONFIRMED at 18:52:16.435583Z without manual confirm. Chain: payments.outbox_events row payment.captured.v1 → topic shop.payment.lifecycle.v1 SENT 18:52:04 → order-service consumer → CONFIRMED.
- Inventory commit: reservation 4acc288d COMMITTED; inventory available 99→89, reserved=2 (only unrelated stale PENDING reservation). Accounting invariant held.
- Shipment d43fbb74 CREATED 18:52:22 automatically on order confirmation.
- POST /{orderId}/ship → SHIPPED 18:54:04; /deliver → DELIVERED 18:54:05. Full lifecycle OK.
- State guard: POST confirm on CONFIRMED order → ORD-4004 (transition guard works).

## Stripe negative cases (all live, real API)
- amount_too_small: capture of 4,498.20 VND → InvalidRequestException amount_too_small, request-id req_9eU7B5e5fxUSk3 ("Amount must convert to at least 50 cents"), surfaced PAY-5008; local payment stayed PENDING; no orphan intent.
- Refund: 5000 VND with refundReference → PARTIALLY_REFUNDED; Stripe side exactly one refund re_3UeJOE00k8Tlns6n1gMSewQD (5000, succeeded).
- Duplicate refund (same refundReference) → no double refund (refundedAmount unchanged; single Stripe refund). Over-refund 999,999 → PAY-5007 rejected, no state change.

## Webhook receiver tests (synthetic but real signature crypto)
- Valid HMAC (Stripe-Signature t=…,v1=… from SHOP_PAYMENT_STRIPE_WEBHOOK_SECRET) → 200 accepted; stored in payment_events (uk_payment_events_provider_event unique).
- Same event replayed → 200 no-op (dedupe).
- Invalid signature → 401 PAY-5005, nothing stored.
- Unknown payment_id payload → FAILED_RETRYABLE retry_count=1 (WebhookRetryScheduler owns retries).

## Genuine Stripe webhook delivery — STRUCTURAL GAP
- Stripe account has one registered endpoint: we_1UeI4X00k8Tlns6ndTPYeII0 → https://example.com/api/v1/webhooks/payments/stripe (placeholder).
- payment_events contains ONLY synthetic evt_acceptance_* events (prior harness) — no genuine evt_* delivery ever processed here. Stripe CLI not installed.
- VERDICT: webhook receiving path verified (signature, dedupe, retry); real end-to-end Stripe→service delivery NOT verified in this environment.

## Reservation TTL/sweeper (live)
- 67 PENDING reservations (left by k6/acceptance runs) expired at 18:57:22; at 18:58:15 the sweeper logged "Expired-reservation sweep released 67 reservation(s)"; reserved_quantity 99→0, accounting consistent.

## Observability of the flow
- Structured JSON AUDIT log lines with actor/action/resource/traceId observed in payment-service and inventory-service.
- payment.captured.v1 / payment.refunded.v1 / order lifecycle events verified in outbox tables (status SENT with topic names).
