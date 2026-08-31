# Order Wiring — Payment Gate + Shipping Delivered — Design

## 1. Purpose

The single post-merge wiring task both epic specs deferred to: order-service
consumes its two new siblings — a payment-CAPTURED guard on confirm (sync,
fail-closed, flag-gated) and a `shipping.delivered.v1` consumer driving the
order DELIVERED transition — plus the payment HTTP surface order needs, and
gateway webhook exposure for real carriers.

## 2. Binding decisions

- **D1 — Confirm guard: synchronous HTTP, fail-closed, flag-gated (user
  decision, tax/promotion precedent).** `OrderConfirmCoordinator` (or the
  confirm service path that owns it) gains a pre-confirm check:
  `paymentClient.getByOrderId(orderId)` must return a CAPTURED payment, else
  `ORD-4012 ORDER_PAYMENT_NOT_CAPTURED` (409) — including on client failure
  (unreachable/timeout = fail-closed, one code, ORDER_TAX_CALCULATION_FAILED
  pattern). Guard active ONLY under `shop.services.payment.enabled`
  (default false — existing E2E keeps working; compose flips it on
  post-verify, tax-flag precedent). No new order status (no PAID — user
  decision #3): PENDING → CONFIRMED keeps its edge, now guarded.

- **D2 — Payment HTTP surface (spec §3 ownership, epic F6).**
  `PaymentController`: `POST /api/v1/payments` (create, SERVICE+ADMIN,
  validation errors → fleet ERR-0422-V; `CreatePaymentRequest` gains its
  bean-validation annotations now), `POST /{id}/capture`, `POST /{id}/refund`
  (SERVICE+ADMIN; refund spec said ADMIN — keep spec: ADMIN only), and
  `GET /api/v1/payments?orderId=` extended to SERVICE+ADMIN (order's guard
  path; ADMIN-only list stays as-is for unfiltered reads). No new error
  codes — PAY-5002/5004/5006 + ERR-0422-V cover the surface.

- **D3 — Order's first Kafka consumer.** common-kafka consumer stack
  (BaseKafkaListenerConfig subclass + `@EnableKafka` on the factory config —
  notif T10 ruling), group `order-service`, `latest`, topic
  `shop.shipping.lifecycle.v1`. Handler: on `shipping.delivered.v1`, map
  orderId → load order → FSM: SHIPPED→DELIVERED via the existing
  `OrderStatusService.validateTransition` + persist + publish
  `order.updated.v1` through the existing outbox/publisher (status DELIVERED).
  Idempotent by FSM state: DELIVERED→DELIVERED or any non-SHIPPED source →
  ack no-op + INFO log (persist-first philosophy; no event table this task —
  FSM state IS the dedupe, shipping T7 precedent). Poison/malformed →
  deserializer-layer skip (ErrorHandlingDeserializer, notif precedent).

- **D4 — Gateway webhook exposure.** Webhook endpoints must be reachable
  from outside the docker network through the gateway
  (`/payments/api/v1/webhooks/payments/{provider}`,
  `/shipping/api/v1/webhooks/shipping/{carrier}` — ServiceRoute prefixes
  pre-exist). Gateway must NOT JWT-block these paths: add gateway
  public-paths entries for the two webhook prefixes — HMAC remains the sole
  authentication (defense stays at the service). Verify gateway's security
  mechanism first (it may already forward auth-optional paths per-route);
  minimal change only.

- **D5 — ErrorCode.** `ORD-4012 ORDER_PAYMENT_NOT_CAPTURED` (409,
  `order.payment.not_captured`) appended after `CARRIER_NOT_CONFIGURED`
  (SHP-10006) as the new last entry. i18n EN+VI. Sequence-gated anchor:
  SHP-10006 `;` → `,`.

- **D6 — No order FSM changes.** Edges untouched; SHIPPED remains
  admin-triggered (`OrderStatusController.ship`) — auto-ship on
  payment/shipment events is future work, out of scope.

- **D7 — Contract notes (documentation, binding).** (a) Unwrap-once
  footgun: `shop.{payment,shipping}.lifecycle.v1` values are JSON-encoded
  STRINGS wrapping the event envelope — the common-kafka consumer stack
  handles this transparently (notification proof); any CUSTOM/raw consumer
  must unwrap once. (b) `CANCELLED` is intentionally outside shipping's
  `advance.count{from,to}` matrix (ops-side exit, not forward progress).

- **D8 — Verification.** Payment: @WebMvcTest matrix for the new controller
  (401/403/200 + validation 400 + PAY-5xxx mapping). Order: unit tests for
  the guard (flag on/off × CAPTURED/FAILED/absent/client-throws) and the
  consumer (SHIPPED→DELIVERED publishes; non-SHIPPED no-op; duplicate
  no-op); existing order suites stay green. Gateway: config-level verify.
  Ops checklist: enable `SHOP_SERVICES_PAYMENT_ENABLED` in order's compose
  stanza post-verify; webhook URLs for real carriers go through the gateway.

## 3. Out of scope

Auto-ship; payment simulation wiring into E2E happy path (ops checklist
item); customer-facing payment history; dispute flows; retry DLQs.
