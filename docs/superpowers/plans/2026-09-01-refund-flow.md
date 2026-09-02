# Refund Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Enable full-refund pipeline (CONFIRMED-or-DELIVERED order → admin /refund → REFUND_REQUESTED → payment refund → webhook → REFUNDED + inventory release + notification email).

**Architecture:** order-service owns refund endpoint, calls payment-service refund (existing), consumes payment.refunded.v1 to drive REFUNDED + inventory release. inventory-service is a NEW consumer of shop.order.refund.v1 for stock release. notification-service is a NEW consumer for refund email templates. Spec is binding authority: docs/superpowers/specs/2026-09-01-refund-flow-design.md.

**Tech Stack:** Spring Boot 4 (fleet BOM), common-core/common-security/common-spring, common-kafka, spring-kafka. No new infra dependencies.

**Spec:** docs/superpowers/specs/2026-09-01-refund-flow-design.md

## Global Constraints

- Cross-service epic with 4 lanes: order (W1 owner), inventory (consumer add), notification (consumer add), payment (verify-only).
- Two NEW consumers (inventory + notification) on shop.order.refund.v1 — each its own groupId; @EnableKafka on the listener config (T10 ruling).
- OrderStatus enum gains REFUND_REQUESTED + REFUNDED. FSM transitions per spec D1.
- New ErrorCode ORD-4013..4015 (appended after ORD-4012). i18n EN+VI.
- payment-service is verify-only — no payment code changes.
- Outbox topic shop.order.refund.v1 is NEW (in addition to existing shop.order.lifecycle.v1).
- i18n: utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties.

---

### Task 1: order-service schema + state machine (W1 lane)

**Files:**
- Modify: order-service/src/main/resources/db/changelog/changelog-{next}-refund-state.yaml (NEW changeSet):

```yaml
- changeSet:
  id: {NN}-refund-state-and-audit
  author: shop-platform
  changes:
    - sql: |
        ALTER TABLE orders ADD COLUMN refund_requested_at TIMESTAMPTZ;
        ALTER TABLE orders ADD COLUMN refunded_at TIMESTAMPTZ;
        ALTER TABLE orders ADD COLUMN refund_payment_id UUID;
        CREATE INDEX idx_orders_status_refund ON orders (status) WHERE status IN ('REFUND_REQUESTED', 'REFUNDED');
```

- Modify: order-service/.../constant/OrderStatus.java — add REFUND_REQUESTED, REFUNDED enum constants (after CANCELLED).
- Modify: order-service/.../entity/Order.java — add @Column fields for refundRequestedAt, refundedAt, refundPaymentId; previousStatus already in place.
- Test: OrderStatusTest (FSM rules) — happy paths + invalid transition → exception.

- [ ] **Step 1: failing test** OrderStatusTransitionsTest: PENDING → REFUND_REQUESTED → exception (not allowed); CONFIRMED → REFUND_REQUESTED → REFUNDED (allowed); CONFIRMED → REFUND_REQUESTED → CONFIRMED (revert, allowed).
- [ ] **Step 2: implement** enum constants + Order entity fields + changelog.
- [ ] **Step 3: run** ./mvnw -pl order-service test → GREEN.
- [ ] **Step 4: commit** feat(order): REFUND_REQUESTED + REFUNDED states + audit fields

### Task 2: order-service error codes + i18n

**Files:**
- Modify: utils/common-core/.../exception/ErrorCode.java — IMPORTANT (post-rating-epic): ORD-4012 has been flipped to , by rating T10. ORD-4013..4015 are INSERTED inline right after ORDER_PAYMENT_NOT_CAPTURED (ORD-4012, HttpStatus.CONFLICT) line, with ORD-4015 closing with ;. The file terminator (whichever block was appended last in the RTG / MED / SRC / SHP chain) keeps its ;. This is NOT a flip-and-append at end-of-file (which would mix ORD codes with the wrong block). Run grep -n ORD-4012 first to locate the exact insertion point.
- Modify: utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties (3 keys: order.refund_invalid_state, order.refund_no_payment, order.refund_gateway_error).

- [ ] **Step 1: implement** code + i18n keys.
- [ ] **Step 2: run** compile PASS.
- [ ] **Step 3: commit** feat(order): refund error codes ORD-4013..4015 + i18n keys

### Task 3: order-service PaymentServiceClient.findByOrderId + refund call

**Files:**
- Modify: order-service/.../client/PaymentServiceClient.java — add findCapturedByOrderId(UUID orderId) returns Optional<PaymentSnapshot>. Mirrors existing patterns. Uses payment-service GET /api/v1/payments?orderId=... with max page size; iterates until finds CAPTURED or empty.
- Modify: order-service/.../client/PaymentServiceClient.java — add refund(UUID paymentId) returns RefundResult. POST /api/v1/payments/{paymentId}/refund. Fail-closed (non-2xx → throw with REFUND_GATEWAY_ERROR).
- Test: PaymentServiceClientRefundTest (MockRestServiceServer): refund success; refund non-2xx → throw.

- [ ] **Step 1: failing tests** (2+ cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(order): PaymentServiceClient.findCapturedByOrderId + refund

### Task 4: order-service RefundService + /refund endpoint

**Files:**
- Create: order-service/.../service/RefundService.java (transactional; orchestrates: load order → validate state → find CAPTURED payment → transition to REFUND_REQUESTED + audit + outbox → call payment refund; on payment-throw → revert order to prior state + rethrow REFUND_GATEWAY_ERROR).
- Create: order-service/.../dto/RefundRequest.java (optional {reason} field for audit; @Size max=500).
- Create: order-service/.../dto/RefundResponse.java (id, status, refundRequestedAt).
- Modify: order-service/.../controller/OrderController.java — add POST /api/v1/orders/{id}/refund @PreAuthorize("hasRole('ADMIN')"), @Valid RefundRequest body; returns ApiResponse<RefundResponse> with HTTP 202.
- Test: RefundServiceTest (Mockito): happy path (CONFIRMED → REFUND_REQUESTED + outbox); DELIVERED → REFUND_REQUESTED happy; invalid state → ORD-4013; no CAPTURED payment → ORD-4014; payment 502 → ORD-4015 + revert.
- Test: OrderControllerRefundTest (@WebMvcTest): ADMIN 202; CUSTOMER 403; PENDING 409 ORD-4013.

- [ ] **Step 1: failing tests** (5+ cases).
- [ ] **Step 2: implement.** Outbox event: aggregateType="order", aggregateId=orderId, eventType="order.refund.requested.v1", topic="shop.order.refund.v1", payload with orderId + userId + amount + currency + previousStatus + refundPaymentId + requestedAt.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(order): /refund endpoint + RefundService + REFUND_REQUESTED outbox event

### Task 5: order-service OrderRefundConsumer (payment.refunded.v1 → REFUNDED + outbox)

**Files:**
- Modify: order-service/.../config/PaymentEventListenerConfig.java (or create if not exists) — add refundListenerFactory, topics=shop.payment.lifecycle.v1, groupId=order-service-refunds. Re-uses existing config OR adds new factory bean.
- Create: order-service/.../kafka/OrderRefundConsumer.java (extends BaseKafkaConsumer<String, PaymentRefundedEvent>; handler: filter eventType=payment.refunded.v1, load order, verify status=REFUND_REQUESTED (else ack no-op), transition to REFUNDED, write outbox order.refunded.v1).
- Test: OrderRefundConsumerTest (Mockito): happy path; replay → idempotent; order in non-REFUND_REQUESTED state → ack no-op; unknown orderId → log + skip.

- [ ] **Step 1: failing tests** (4 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(order): OrderRefundConsumer — payment.refunded.v1 → REFUNDED + outbox

### Task 6: inventory-service NEW consumer for shop.order.refund.v1 (W2)

**Files:**
- Modify: inventory-service/.../config/RefundListenerConfig.java (NEW file) — factory bean refundListenerFactory, groupId shop.kafka.consumer.group-id.refunds: inventory-service-refunds, topics: shop.order.refund.v1, @EnableKafka on this config.
- Create: inventory-service/.../dto/OrderRefundEvent.java (record: eventId, eventType, occurredAt, orderId, items List<OrderRefundItem>).
- Create: inventory-service/.../dto/OrderRefundItem.java (record: UUID orderItemId, UUID productId, UUID reservationId, int quantity).
- Create: inventory-service/.../kafka/InventoryRefundConsumer.java (extends BaseKafkaConsumer; handler: filter eventType=order.refunded.v1 only, for each item call inventoryService.release(reservationId), log + retry on throw).
- Modify: inventory-service/.../service/InventoryService.java (verify release(reservationId) is already idempotent — already exists per inventory impl §3).
- Test: InventoryRefundConsumerTest (Mockito InventoryService mock): happy path releases all items; release() throws → log + propagate (outbox retry).

- [ ] **Step 1: failing tests** (2 cases).
- [ ] **Step 2: implement.** InventoryService.release() is pre-existing — verify via grep.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(inventory): refund consumer — release stock on order.refunded.v1

### Task 7: notification-service NEW consumer + templates (W2)

**Files:**
- Modify: notification-service/.../config/RefundListenerConfig.java (NEW file) — factory bean refundListenerFactory, groupId shop.kafka.consumer.group-id.refunds: notification-service-refunds, topics: shop.order.refund.v1, @EnableKafka on this config.
- Create: notification-service/.../dto/OrderRefundEvent.java (flattened envelope, same shape as inventory's).
- Create: notification-service/.../kafka/NotificationRefundConsumer.java (extends BaseKafkaConsumer; handler: switch on eventType: order.refund.requested.v1 → renderRefundRequestedTemplate + queue email; order.refunded.v1 → renderRefundCompletedTemplate + queue email; ack on queue-success).
- Modify: notification-service/.../service/NotificationTemplates.java — add renderRefundRequested(order, locale) and renderRefundCompleted(order, locale, amount).
- Modify: utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties — add notification.refund_requested.subject, notification.refund_requested.body, notification.refunded.subject, notification.refunded.body.
- Test: NotificationRefundConsumerTest (Mockito): REFUND_REQUESTED → email queued; REFUNDED → email queued; unknown eventType → ack no-op.

- [ ] **Step 1: failing tests** (3 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(notification): refund consumer + email templates

### Task 8: cross-module verification + smoke

- [ ] **Step 1:** ./mvnw -pl order-service,inventory-service,notification-service test → all green.
- [ ] **Step 2:** ./mvnw -T1C install -DskipTests -q → exit 0 (verify nothing else broke).
- [ ] **Step 3:** E2E smoke checklist (post-merge; not in this epic):
  - Create order → pay+confirm → admin /refund → 202 → wait 1s → order status=REFUND_REQUESTED.
  - Mock provider sends webhook → payment.refunded.v1 → order REFUNDED + outbox order.refunded.v1 → inventory releases → notification email queued.
  - Replay payment.refunded.v1 → ack no-op (order already REFUNDED).
- [ ] **Step 4:** docker compose config -q → exit 0.
- [ ] **Step 5: commit** chore(refund): cross-module verification + smoke checklist

### Task 9: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1-D4 + §7 audit; /refund happy + revert-on-payment-failure path; refund event → inventory release → notification email end-to-end (via IT or mock provider); zero-regression (only order + inventory + notification changes + ErrorCode/i18n tails); consumer idempotency (OrderRefundConsumer, InventoryRefundConsumer, NotificationRefundConsumer all replay-safe); FSM correctness (no path skips REFUND_REQUESTED); security (ADMIN-only endpoint, SERVICE token to payment). Fix rounds until APPROVED.