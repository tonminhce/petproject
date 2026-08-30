# Payment Service — Design

## 1. Purpose

First money-touching service: capture and refund order payments through a
pluggable provider, driven by signed webhooks. Ships standalone — the
order-service wiring (confirm-time PAID guard) is a single post-merge task
owned by neither epic (see Fleet impact).

## 2. Binding decisions

- **D1 — Provider port (user decision Q1).** `payment-service` knows ZERO
  about any concrete gateway. All gateway access goes through one interface:
  `PaymentProvider { PaymentAttempt capture(PaymentPaymentRef ref, Money amount,
  String idempotencyKey); RefundResult refund(...); }`. Exactly three
  implementations in this epic: `MockProvider` (compose, dev/CI only),
  `StripeProvider` (real-API skeleton behind `payment.provider=stripe`, not
  exercised until keys exist), and nothing else. Provider selection =
  `@ConditionalOnProperty("shop.payment.provider")`, default `mock`.

- **D2 — Mock provider is a compose service, not an in-process stub.** A
  ~50-line Node/Express container (`mock-payment-provider`) exposing
  `POST /mock-payments/{id}/capture` (delays 200–800 ms, then POSTs a signed
  webhook to payment-service), `POST /mock-payments/{id}/refund`,
  `POST /mock-payments/reset` (IT hygiene), `GET /mock-payments/_health`.
  Webhooks are HMAC-SHA256 signed with `PAYMENT_WEBHOOK_SECRET` shared via
  compose. This makes signature verification, delayed-confirmation and retry
  behavior testable end-to-end in the same topology production will have.

- **D3 — Payment state machine with previousStatus (promotion precedent).**
  `PENDING → CAPTURED | FAILED`; `CAPTURED → REFUNDED` (single-step refund in
  this epic; `REFUNDING` reserved). Every transition stores
  `previous_status` — payment.captured.v1 vs payment.refunded.v1 vs
  payment.failed.v1 consumers can distinguish fresh vs transitional states.
  Idempotency: `idempotency_key` (client-supplied, unique live constraint on
  `payments`); duplicate create → returns existing payment (Stripe semantics),
  not an error.

- **D4 — Webhook receiver is the only write path to CAPTURED/FAILED/REFUNDED.**
  `POST /api/v1/webhooks/payments/{provider}`: (1) HMAC-SHA256 verify
  fail-closed (bad signature → 401, no state change); (2) persist-first /
  ack-always (notification D5 precedent): webhook event row committed in its
  own tx, then state transition; handler exceptions never fail the HTTP 200 —
  they mark the event `FAILED` for reconciliation. Dedupe: `event_id` unique
  live constraint on `payment_events`; duplicate delivery → ack + no-op.

- **D5 — Events via outbox + relay (order-service reference port).**
  Status-changing transitions write `OutboxEvent` rows in the same tx as the
  payment update; `PaymentOutboxRelay` (port of `OrderOutboxRelay`) publishes
  `shop.payment.lifecycle.v1` topics:
  `payment.captured.v1`, `payment.failed.v1`, `payment.refunded.v1`.
  Payload wrapper identical to order events:
  `{eventId, eventType, occurredAt, orderId, paymentId, amount, currency, status, previousStatus}`.

- **D6 — Error codes: extend the existing PAY-5xxx block.** `PAY-5001`
  `PAYMENT_FAILED` and `PAY-5002` `PAYMENT_NOT_FOUND` pre-exist (order's stub
  era) — they stay. New: `PAY-5003 PAYMENT_DUPLICATE_REQUEST` (409),
  `PAY-5004 PAYMENT_INVALID_STATE` (409), `PAY-5005 WEBHOOK_SIGNATURE_INVALID`
  (401), `PAY-5006 REFUND_INVALID_STATE` (409), `PAY-5007 AMOUNT_MISMATCH`
  (400). i18n keys EN+VI for all.

- **D7 — Security: fail-closed with one signature carve-out.** No public
  paths except the webhook endpoint, which is authenticated by HMAC
  verification, not JWT. The webhook path needs a new public-paths key
  (`/api/v1/webhooks/**`) with a dedicated HMAC filter — JWT anchor must NOT
  cover it. Backoffice read API (list + get by id, paged newest-first) is
  `hasRole('ADMIN')` only. No customer-facing endpoints this epic.

- **D8 — Schema (TIMESTAMPTZ audit columns).** `payments(id, order_id,
  amount numeric(19,2), currency char(3), status, previous_status, provider,
  idempotency_key, created_at, updated_at, version, deleted)` — unique live
  index on `idempotency_key`, index on `order_id`.
  `payment_events(id, payment_id, provider_event_id, type, payload jsonb,
  status, created_at, ...)` — unique live index on `(provider, provider_event_id)`.
  Entity mirrors Campaign precedent (`@Version Long`, soft-delete).

- **D9 — Receipts to object storage (common-storage, free win).** On CAPTURED,
  render a receipt JSON and store via `ObjectStorageService` (RustFS/S3) under
  `receipts/{paymentId}.json`; store the object key on the payment row
  (`receipt_key`). Failure to store → log + continue (receipt is auxiliary,
  never blocks the payment state).

- **D10 — Deployment.** Port **8095**, DB `paymentservice` (init SQL addition
  is payment-lane-owned), compose stanza with
  `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` + webhook-secret env +
  mock-provider stanza beside it. Config flags fail-closed:
  `shop.payment.provider: mock`, `shop.payment.webhook.secret` required.

## 3. API

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/api/v1/payments` | SERVICE, ADMIN | create PENDING payment (idempotent by key) |
| POST | `/api/v1/payments/{id}/capture` | SERVICE, ADMIN | delegates to provider; actual state via webhook |
| POST | `/api/v1/payments/{id}/refund` | ADMIN | CAPTURED only |
| GET | `/api/v1/payments/{id}` | ADMIN | PAY-5002 if missing |
| GET | `/api/v1/payments` | ADMIN | paged newest-first, optional orderId filter |
| POST | `/api/v1/webhooks/payments/{provider}` | HMAC (D7) | sole state write path |

## 4. Capture flow

create → PENDING → `capture` → provider (mock: 200–800 ms delay) → signed
webhook → HMAC verify → `payment_events` insert (idempotent) → payments row
CAPTURED + previous_status → outbox row → relay → `payment.captured.v1`.
Refund mirrors it with `payment.refunded.v1`.

## 5. Testing strategy (fleet 3 layers)

- **Unit/service**: state machine (all transitions + invalid → PAY-5004),
  HMAC verify (good/bad/tampered/replayed), idempotent create, refund guards.
- **Controller slices**: webhook 401 on bad signature, 200-on-poison (ack),
  backoffice ADMIN matrix, validation 400s.
- **IT (Testcontainers PG + real HTTP)**: full capture round-trip with the IT
  playing the provider (correctly-signed webhook POSTs, replay dedupe,
  outbox→relay publish asserted via embedded consumer), refund round-trip,
  receipt-to-storage happy path. Compose-level smoke for the real
  mock-provider container happens at Task N (config + manual curl checklist),
  matching fleet precedent.

## 6. Fleet impact (lane rules)

- **payment lane = W1 shared-file owner** (ErrorCode PAY-5xxx tail, i18n,
  ApiPaths `BACKOFFICE_PAYMENTS` + webhook path constant, init SQL
  `CREATE DATABASE paymentservice;`) until merge; shipping lane waits for the
  branch-to-branch unlock merge before touching tails.
- **order-service: untouched in this epic.** The confirm-time payment guard
  and any order↔payment client are the post-merge wiring task.
- notification-service template additions (receipt/payment subjects) =
  separate follow-up, not this epic.
- Gateway: add payment route (config-only) in the wiring task.

## 7. Out of scope

Real gateway credentials/live calls; partial refunds; multi-currency;
payment-method tokenization; customer-facing payment history; dispute flow.
