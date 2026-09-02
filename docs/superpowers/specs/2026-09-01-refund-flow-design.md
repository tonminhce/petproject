# Refund Flow — Design (Phase 8)

- Date: 2026-09-01
- Status: Draft (pending user ratification of 4 scope decisions)
- Scope: cross-service refund pipeline. Adds REFUND_REQUESTED + REFUNDED to OrderStatus, /api/v1/orders/{id}/refund endpoint, order→payment refund client call, order-service payment.refunded.v1 consumer driving inventory release + notification trigger. payment-service refund endpoint already exists (payment D6 §3 PAY-5006 REFUND_INVALID_STATE). inventory-service release-on-REFUNDED is the missing link. notification-service handles templates.

## Verified ground truths

- payment-service already exposes POST /api/v1/payments/{id}/refund (ADMIN-only, CAPTURED only, returns 409 REFUND_INVALID_STATE otherwise). Emits payment.refunded.v1 via outbox. Idempotent: re-call on REFUNDED → no-op 200 (Stripe semantics).
- order-service OrderStatus enum currently: PENDING → CONFIRMED → SHIPPED → DELIVERED (terminal); CANCELLED-from-PENDING (refund n/a) and CANCELLED-from-CONFIRMED (admin-only, no stock release by design — order spec §3.5 note). DELIVERED is terminal today (order spec §3.5, recording that return/refund would be Phase 8).
- inventory-service has release() endpoint (POST /api/v1/reservations/{id}/release), idempotent on RELEASED/EXPIRED (inventory impl §3). For refund, the order's `reservation_id` (per-item via reservation commit in confirm flow — order spec §4) must be released.
- notification-service already subscribes to shop.order.lifecycle.v1 (notification spec §3). Adding refund events = same topic, new eventType, same template harness.
- shop.kafka topic convention: shop.{service}.lifecycle.v1 for state events, shop.{service}.process.v1 for work queues. Refund uses lifecycle.
- §1 rated Code-base constraint: order's outbox-emits shop.order.lifecycle.v1 (status transitions) AND shop.order.cancellation.v1 (cancel-specific). Refund adds shop.order.refund.v1 as a SUB-LIFECYCLE topic for finer-grained consumers (notification can subscribe to refund without parsing the lifecycle envelope).

## §1 Binding decisions

### D1 — Order state machine: REFUND_REQUESTED → REFUNDED (decision Q1)

Two new states, not one:

- `REFUND_REQUESTED` — order accepted refund; payment refund call in flight (or failed).
- `REFUNDED` — payment.refunded.v1 received and verified; inventory released; terminal.

Why split: a refund API call is synchronous, but the payment provider's webhook is async (mock: 200-800 ms; Stripe: seconds to minutes). Without an intermediate state, an order is stuck between admin-click and webhook for an arbitrary window — observers (UI polling, customer-facing queries) can't distinguish in-flight refund from confirmed. Splitting matches payment's `PENDING → CAPTURED | FAILED` precedent.

Allowed transitions:
- `CONFIRMED → REFUND_REQUESTED` (admin /refund endpoint; CAPTURED payment required)
- `DELIVERED → REFUND_REQUESTED` (post-delivery return — NEW path; this is the Phase 8 unlock for DELIVERED no-longer-terminal)
- `REFUND_REQUESTED → REFUNDED` (payment.refunded.v1 consumer)
- `REFUND_REQUESTED → CONFIRMED` (refund call failed → revert; records in audit column)
- `CANCELLED` → NO refund path (cancel-before-CONFIRMED is pre-payment; nothing to refund)

REFUND_REQUESTED is admin-actionable from CONFIRMED or DELIVERED. Other states reject with ORD-4013.

### D2 — Refund endpoint lives in order-service, calls payment-service (decision Q2)

POST /api/v1/orders/{id}/refund (ADMIN-only) on OrderController. Flow:
1. Load order (ORD-5001 not found).
2. Validate state ∈ {CONFIRMED, DELIVERED} (else ORD-4013 REFUND_INVALID_STATE).
3. Find payment by orderId via PaymentServiceClient.findByOrderId(orderId, page=0, size=1). Take first with status=CAPTURED (fail-closed: no CAPTURED payment → ORD-4014 REFUND_NO_PAYMENT).
4. Transition order to REFUND_REQUESTED (save + outbox event `order.refund.requested.v1`).
5. Call payment-service POST /api/v1/payments/{paymentId}/refund (SERVICE token; fail-closed → ORD-4015 REFUND_GATEWAY_ERROR, revert order to prior state on payment-client throw).

The refund's actual success/failure comes back via Kafka `payment.refunded.v1` event (drives state to REFUNDED + inventory release).

Why order-service owns the endpoint and not payment-service: order is the aggregate root for the user's refund intent; payment is the gateway. Symmetry with confirm-flow (order → payment check).

### D3 — Inventory release on REFUNDED (decision Q3)

When order transitions REFUND_REQUESTED → REFUNDED (driven by payment.refunded.v1), inventory is released automatically:
1. order-service consumer of payment.refunded.v1 (NOT order-lifecycle self-consumer — different envelope):
   - Maps `paymentId` → `orderId` via the payment metadata in the event payload (payment D5 envelope already carries `orderId`).
   - Verifies the order is currently REFUND_REQUESTED (idempotent: if already REFUNDED, ack no-op).
   - Transitions order to REFUNDED.
   - Writes outbox event `order.refunded.v1` with `eventType: order.refunded.v1` and full order snapshot.
2. inventory-service consumer of `shop.order.refund.v1` (NEW consumer group `inventory-service-refunds`):
   - For each `OrderItem` in the order, calls `inventory-service release()` on its `reservationId`.
   - Idempotent: release() returns 200 on RELEASED/EXPIRED (inventory impl §3).
   - Failures: log + retry (outbox + relay pattern; failed release → outbox event status=FAILED, ops alert).

Why a NEW inventory consumer group (separate from order.lifecycle.v1 inventory consumer — if any exists; verify) and not a synchronous order→inventory call: same reasons as shipping (rating T3 nit #1): keep the refund handler async + dumb; inventory release is best-effort with ops escalation.

### D4 — Notification triggers (decision Q4)

notification-service extends its order.lifecycle.v1 consumer to handle TWO new event types:
- `order.refund.requested.v1` → email template REFUND_REQUESTED (subject: "Your refund is being processed").
- `order.refunded.v1` → email template REFUNDED (subject: "Refund completed — {amount}").

Templates follow existing notification precedent (i18n EN+VI). New i18n keys: `notification.refund_requested.subject/body`, `notification.refunded.subject/body`.

## §2 API additions

| Method | Path | Auth | Behavior |
|---|---|---|---|
| POST | /api/v1/orders/{id}/refund | ADMIN | D2 flow; 202 Accepted + order snapshot (status=REFUND_REQUESTED). Errors: ORD-4013/4014/4015 |
| GET | /api/v1/orders/{id} | existing | Response gains `previousStatus`, `refundRequestedAt`, `refundedAt` audit fields |

## §3 State machine (extended)

```
PENDING ──confirm──> CONFIRMED ──ship──> SHIPPED ──deliver──> DELIVERED ──refund──> REFUND_REQUESTED ──webhook──> REFUNDED (terminal)
  │                      │                                                                                                              ↑
  ├──cancel──> CANCELLED │                                                                                                              │
  │                      │                                                                                                              │
  └────(cancel, no refund path)───────────────────────────────────────────────────────────────────────────────────────────────────── REFUND_REQUESTED (from CONFIRMED via admin)
```

## §4 Error codes (new ORD-4013..4015)

`ORD-4013 REFUND_INVALID_STATE` (409, not CONFIRMED/DELIVERED), `ORD-4014 REFUND_NO_PAYMENT` (404, no CAPTURED payment for order), `ORD-4015 REFUND_GATEWAY_ERROR` (502, payment-service unreachable / errored). i18n EN+VI keys. Appended after ORD-4012 (last order code).

## §5 Kafka topics (new)

shop.order.refund.v1 — single topic carrying order.refund.requested.v1 + order.refunded.v1 + order.refund.failed.v1 (sub-lifecycle; mirrors the spec split). Consumers:
- inventory-service: shop.order.refund.v1 (group inventory-service-refunds) → release.
- notification-service: shop.order.refund.v1 (group notification-service-refunds) → email. (Or extend existing notification consumer to handle sub-topics — verify which pattern fits. Default: SEPARATE topic, new consumer config.)

## §6 Testing strategy

- Unit (order): state transitions (CONFIRMED → REFUND_REQUESTED → REFUNDED; DELIVERED → REFUND_REQUESTED → REFUNDED; CONFIRMED → REFUND_REQUESTED → CONFIRMED revert on payment failure).
- Unit (inventory consumer): release() called per OrderItem; idempotent on RELEASED.
- Controller (order): ADMIN refund 202, CUSTOMER 403, PENDING 409, no CAPTURED payment 404.
- IT (cross-service, Testcontainers PG + real Kafka): full refund → inventory release → notification email. Replay of payment.refunded.v1 → idempotent (state already REFUNDED, ack).
- Negative IT: payment-service down during /refund → ORD-4015 + order state reverted.

## §7 Fleet impact (lane rules)

- **order-service = W1** on order changes (RefundService, OrderStatus enum +2, OrderController +1 endpoint, OrderRefundConsumer, PaymentServiceClient.findByOrderId, outbox shop.order.refund.v1, ErrorCode ORD-4013..4015).
- **payment-service = verify-only.** Existing refund endpoint is already correct (D6 + spec §3 + outbox). No payment changes needed.
- **inventory-service = NEW consumer** for shop.order.refund.v1. InventoryReleaseConsumer config (mirror ShippingListenerConfig). NO domain changes — release() endpoint already idempotent.
- **notification-service = NEW consumer** for shop.order.refund.v1. New templates. NO domain changes.
- **shared-file tails**: ErrorCode ORD-4013..4015 + i18n keys (`order.refund_invalid_state`, `order.refund_no_payment`, `order.refund_gateway_error`) — order-service owns, single PR.
- Init SQL: no new DBs. Outbox lives in order DB; consumers are stateless.

## §8 Non-goals (binding)

Partial refunds (only full-refund V1); customer-initiated refund (ADMIN-only V1, customer request → admin queue Phase 9+); multi-currency refund (assume VND); refund dispute window; auto-refund on cancel-after-CONFIRMED (admin chooses; auto is Phase 9+); RMA / RETURNED state (separate epic); refund of shipping cost (always full product cost V1, shipping refund deferred).

## §9 Open items

- DELIVERED → REFUND_REQUESTED transition: should this require a RETURNED state in between (RMA flow)? Default V1: skip RETURNED, direct DELIVERED → REFUND_REQUESTED via admin click; RETURNED added in a separate RMA epic.
- Refund window: any time-bound (e.g., 30 days post-delivery)? Default V1: unbounded, ADMIN decides.
- Refund notification channel: email only, or also SMS/push? Default V1: email only (notification precedent).
- order-service→payment-service findByOrderId: pagination. payment already exposes GET /api/v1/payments?orderId= (paged). Default size 20, take first CAPTURED — note order-wiring F2 (payment-service pagination default 10) — verify the relevant page-size constant; ensure we use max page size or filter properly.

## §10 Changelog

- 2026-09-01 (rev 0): Initial draft pending user ratification of D1 (REFUND_REQUESTED intermediate state), D2 (refund endpoint in order), D3 (inventory release via NEW consumer), D4 (notification via NEW consumer).