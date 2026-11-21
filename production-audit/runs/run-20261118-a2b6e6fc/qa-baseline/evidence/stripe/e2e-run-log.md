# Independent E2E + Stripe Sandbox Evidence — run-20261122-f242158

Environment: local Docker Compose stack (repo docker-compose.yml), all 14 services healthy, images built 2026-11-21T18:00-18:41Z from this worktree.
Gateway: http://localhost:8080. Keycloak realm: ecommerce (users testuser[USER], adminuser[ADMIN,MANAGER]).

## Auth
- Password grant token for testuser: OK (realm role USER).
- Password grant token for adminuser: OK (realm roles ADMIN, MANAGER).

## Checkout saga — happy path (order 48deffcc-a061-4df2-97e9-dea2c0c278ba)
- POST /api/v1/carts/me/items (product 14469db6…, qty 10) → 200, cart subtotal 24,990.00 VND.
- POST /api/v1/orders (no coupon) → 200 PENDING, total 24,990.00 VND, taxAmount 0, trackingCode assigned at creation.
- Payment creation: POST /api/v1/payments is guarded (hasAnyRole SERVICE,ADMIN + ServiceCallerGuard.requireCaller(order-service)); a USER token gets ERR-0403; ADMIN (human) bypasses the client check (ServiceCallerGuard.java:24-26).
- Payment e24d582c-08a7-4a5c-a955-8197db8de28b created PENDING → POST /{id}/capture → CAPTURED with providerIntentId pi_3UeJOE00k8Tlns6n1uunCXGc (REAL Stripe test API, api.stripe.com).
- Stripe-side verification (direct GET /v1/payment_intents/pi_3UeJOE00k8Tlns6n1uunCXGc with sk_test key):
  status=succeeded, amount=24990, currency=vnd, livemode=false, metadata.payment_id=e24d582c-…, metadata.idempotency_key=audit-e2e-pay-002, latest_charge ch_3UeJOE00k8Tlns6n1m9JgiOd.
- Order auto-confirmed WITHOUT manual confirm call: orders.status=CONFIRMED, confirmed_at=18:52:16.435583Z.
  Evidence chain: payments.outbox_events row event_type=payment.captured.v1 topic=shop.payment.lifecycle.v1 status=SENT sent_at=18:52:04 → order-service consumed → confirmed. (Kafka + transactional outbox + idempotent consumer chain working end to end.)
- Inventory commit: reservation 4acc288d COMMITTED; inventory 14469db6: available 99 → 89, reserved 2 (only an unrelated stale PENDING reservation). Accounting invariant holds (committed decrements available; reserved holds only pending).
- Shipment d43fbb74 CREATED 18:52:22 for the order (automatic on confirm).
- POST /{orderId}/ship → SHIPPED 18:54:04; POST /{orderId}/deliver → DELIVERED 18:54:05. Full lifecycle OK.

## Stripe negative cases
- amount_too_small: order total 4,498.20 VND → capture rejected by REAL Stripe: InvalidRequestException code=amount_too_small, request-id=req_9eU7B5e5fxUSk3, surfaced as PAY-5008; local payment stayed PENDING, no intent created (no orphan provider objects). NOTE: VND is zero-decimal; ₫4,498 < $0.50 minimum.
- Refund: POST /{id}/refund 5000 VND with refundReference ae5f0a1a-… → PARTIALLY_REFUNDED, refundedAmount 5000.00. Stripe side: exactly one refund re_3UeJOE00k8Tlns6n1gMSewQD amount 5000 succeeded.
- Duplicate refund with SAME refundReference → no double refund (refundedAmount unchanged; single Stripe refund). Idempotency verified both locally and on Stripe.
- Over-refund attempt 999,999 VND → rejected PAY-5007 (amount mismatch), no state change.

## Webhook receiver (independent synthetic tests)
- Valid HMAC signature (Stripe-Signature t=…,v1=…) → 200 accepted; payment_events row evt_audit_synth_001 stored.
- Replay of the same event id → 200 no-op (dedupe via uk_payment_events_provider_event unique (provider, provider_event_id)).
- Invalid signature → 401 PAY-5005, nothing stored.
- Unknown payment_id in payload → status FAILED_RETRYABLE retry_count=1 (WebhookRetryScheduler owns retry).

## Genuine Stripe webhook delivery — STRUCTURAL GAP
- Stripe account has exactly one registered webhook endpoint: we_1UeI4X00k8Tlns6ndTPYeII0 → https://example.com/api/v1/webhooks/payments/stripe (placeholder, unreachable).
- payment_events contains ONLY synthetic evt_acceptance_* ids from the prior harness run — no genuine Stripe evt_* delivery ever processed in this environment.
- Conclusion: webhook RECEIVING path is verified (signature, dedupe, retry); webhook DELIVERY from Stripe end-to-end is NOT verified in this environment (needs reachable public endpoint or stripe CLI forwarding). Stripe CLI not installed on this machine.

## State guard evidence
- POST /orders/{id}/confirm on an already-CONFIRMED order → ORD-4004 "Order cannot transition from CONFIRMED to CONFIRMED" (state machine guard active).

## Reservation TTL observation
- Prior run left 99 PENDING reservations on product 9f5166c0 (reserved_quantity=99, available 99 → sellable 0; checkout ORD-4007 correctly rejected). TTL expiry 18:57:22Z — sweeper behavior check scheduled.

## Live sweeper verification (2026-11-21T18:58:15Z)
- inventory-service log: `Expired-reservation sweep released 67 reservation(s)` (ReservationCleanupScheduler, fixedDelay 60s).
- DB after: 9f5166c0... available=99 reserved=0; reservation statuses 887 EXPIRED / 1 COMMITTED. TTL + sweeper invariant verified live.
