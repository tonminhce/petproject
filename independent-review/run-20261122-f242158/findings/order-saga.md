# Order/Checkout Saga — Independent Business-Correctness Audit

- **Revision audited:** `f242158265163d65ef73d378331d2bd7a92479ee`
- **Scope:** `order-service`, `payment-service`, `shipping-service`, `tax-service` (`src/main/java` + `src/main/resources`), plus read-only reference into `utils/common-kafka` for the shared Kafka consumer/producer behavior.
- **Method:** zero-trust source read. No docs, prior reports, or `production-audit/` artifacts were trusted. Nothing was executed; nothing outside `findings/order-saga.md` was modified.
- **Confidence vocabulary:** `CONFIRMED_FROM_SOURCE` = the defect is visible in the code/config as written. `HIGH_CONFIDENCE_INFERENCE` = code facts confirmed; the end-state damage requires an interleaving/dependency outside the audited code. `NEEDS_RUNTIME_VERIFICATION` = the claim depends on an external system (PSP, inventory-service internals) or on running the stack.

---

## 1. Checkout flow — what the code actually does

Entry point: `POST /api/v1/orders` (`order-service/.../controller/OrderController.java:35-45`, `ApiPaths.ORDERS = /api/v1/orders`). It accepts an **optional** `Idempotency-Key` header and calls `OrderServiceImpl.createOrder` → `OrderCreateSaga.execute`:

1. **TX1** (`OrderCreateSaga.java:121-146`): lock the cart `FOR UPDATE` (`CartRepository.findByUserIdForUpdate`, `CartRepository.java:23-26`, 3s lock timeout), read items, insert an `Order` row with `status=PENDING`, `subtotal/tax/discount/total = 0`, a random `trackingCode`.
2. **Outside any transaction** (`OrderCreateSaga.java:152-209`): `PricingServiceImpl.calculate` (product snapshots — Redis-cached, promotion reserve, tax calculate), then one inventory `reserve` HTTP call per item; on any failure it releases what was reserved, releases the promotion reservation, deletes the PENDING order and rethrows.
3. **TX2** (`OrderCreateSaga.java:211-218`): save the priced order + order items, **delete the cart items**, soft-delete the cart, and append `order.created.v1` to the DB outbox.

Idempotency is a **Postgres table, not Redis**: `idempotency_keys` with PK `(actor, key)` and `insertIfAbsent … ON CONFLICT DO NOTHING` (`IdempotencyKeyRepository.java:17-25`, `changelog-001-initial-schema.yaml:186-210`). Redis is used only for the product-price cache (`CacheConfig.java:21-31`).

## 2. Transaction boundaries

- Order-service deliberately keeps remote calls **outside** local transactions (`OrderCreateSaga`, `OrderCommitCoordinator.commitForConfirm`) — good shape.
- Payment-service executes **PSP HTTP calls inside `@Transactional`** (`PaymentServiceImpl.capture:150-164`, `refund:187-229`) and the refund path holds `SELECT … FOR UPDATE` on the payment row while doing it (`PaymentRepository.findByIdForUpdate`, lock timeout 10s). Order-service's `refund-timeout-ms: 45000` (`order-service application.yml:64`) shows refunds are expected to take up to 45s.
- `CartServiceImpl.addItem/updateItem` hold the cart `FOR UPDATE` while calling product-service over HTTP (5s timeout) inside `@Transactional`.
- `OrderReturnServiceImpl.reviewReturn` calls the payment refund API (45s budget) inside `@Transactional` while holding pessimistic locks on the order's return rows (`OrderReturnServiceImpl.java:146-211`).
- Shipping-service: carrier/webhook side effects are outside transactions; writers are small `@Transactional` methods.

Crash-mid-saga behavior is covered by ORD-1/ORD-5/ORD-11 below.

## 3. Order state machine

`OrderStatus.java:3-5`: `PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED`.
Legal transitions (`OrderStatusServiceImpl.java:16-22`): `PENDING→{CONFIRMED,CANCELLED}`, `CONFIRMED→{SHIPPED,CANCELLED}`, `SHIPPED→{DELIVERED,CANCELLED}`, `DELIVERED` and `CANCELLED` are terminal. Guard = in-memory `validateTransition` at every mutation site + `@Version` on `Order` (`Order.java:89-92`). There is **no** atomic `UPDATE … WHERE status=?` anywhere; every guard is read-then-act.

- A cancelled order **cannot** be confirmed by a delayed event: `PaymentLifecycleConsumer.handle` catches exactly `ORDER_INVALID_STATE_TRANSITION` and skips (`PaymentLifecycleConsumer.java:35-39`), and `ShippingDeliveredHandler` no-ops unless status is exactly `SHIPPED` (`ShippingDeliveredHandler.java:36-39`). Confirmed safe (modulo the ORD-6 interleaving).
- Stuck non-terminal states: `CONFIRMED` and `SHIPPED` have **no** sweeper, no timeout, and no event path from `CONFIRMED` to `DELIVERED` (shipping publishes only `shipping.delivered.v1`, which is dropped unless the order is `SHIPPED`) → ORD-14. `PENDING` is swept by `OrderReconciliationScheduler` (every 5 min, older than 30 min) — but the "mixed reservation state" bucket is never resolved (ORD-20).

## 4. Money correctness

- All money is `BigDecimal` with `precision=15, scale=2` in order-service (`Order.java:32-46`, `OrderItem.java:31-35`) and `19,2` in payment-service (`Payment.java:27-28`). Currencies are 3-letter codes, normalized via `PlatformCurrency.normalize` and cross-checked between the order, every line item, the payment and the refund (`PricingServiceImpl.resolveCurrency`, `requirePaymentMatchesOrder`, `PaymentServiceImpl.resolveRefundCurrency`). **Positive**: `requirePaymentMatchesOrder` (`OrderServiceImpl.java:333-348`) refuses to confirm unless paid amount **and** currency match the order exactly.
- Tax: single aggregate calculation on `subtotal − discount` (`PricingServiceImpl.java:112-116`), `TaxCalculator` = `amount × ratePct ÷ 100`, scale 2, `HALF_UP` (`tax-service TaxCalculator.java:13-16`). No per-line computation, no mixed-rate support (ORD-9); the integration is broken end-to-end (ORD-7).
- Discounts: `discountAmount` is whatever promotion-service returned; it is **never clamped** to the subtotal and no DB constraint enforces a non-negative total (ORD-8).
- Prices: re-verified at checkout from product-service, but through a **10-minute Redis cache** (`ProductServiceClient.getProduct` `@Cacheable`, TTL 10 min, `CacheConfig.java:21`) — the cart's own stored `unitPrice` is correctly ignored for the order total (ORD-10).

## 5. Payment integration

- Provider selection: `shop.payment.provider` (`payment-service application.yml:38`); per-payment override honored from the request body (`PaymentServiceImpl.resolveProviderForNewPayment:64-69`, `CreatePaymentRequest.provider`). Stripe via `com.stripe` SDK (`StripeClient`, `PaymentIntentCreateParams`), API version pinned `2024-06-20` with a drift warning against `com.stripe.Stripe.API_VERSION` (`StripeProvider.verifyConfiguration:51-58`, `PaymentStripeProperties.java:36-38`).
- Capture: create PaymentIntent with the **local idempotency key** as Stripe idempotency key (`StripeProvider.stripeOptions:258-263`); `succeeded → CAPTURED`, `requires_* → client_secret returned`, `canceled/rejected → REJECTED`, everything else `INDETERMINATE` → 503. Amount and currency are re-checked against the returned intent (`confirmedCapture:169-180`). Sound.
- Webhook endpoint: `POST /api/v1/webhooks/payments/{provider}` is a **public path** (`payment-service application.yml:81-84`). Stripe branch verifies with the official `Webhook.constructEvent` + `Stripe-Signature` (SDK default 5-min tolerance); non-Stripe providers use a custom constant-time HMAC (`WebhookSignatureVerifier`, `MessageDigest.isEqual`, 5-min tolerance for the Stripe-format header) and MoMo/VNPay verify their own signed fields (`MoMoCallbackVerifier`, `VNPayCallbackVerifier`). Signature handling is correct; the custom verifiers have **no freshness window** (ORD-19) but replay is neutralized by `uk_payment_events_provider_event` (`changelog-001-payments.yaml:69-73`) + `PROCESSED` skip (`WebhookEventService.handle:87-112`).
- Payment succeeded but local processing failed: the webhook row is `FAILED_RETRYABLE` and `WebhookRetryScheduler` reprocesses (6 attempts, 300s×5ⁿ backoff) — but there is **no reconciliation job that polls the PSP for payments left `PENDING`** (e.g. after a crash between the Stripe capture and the local commit) — ORD-4.
- Refunds: refund is reference-keyed (`refundReference`, unique `(payment_id, refund_reference)`, `changelog-012-payment-refunds.yaml:30-31`), the payment row is locked `FOR UPDATE`, the ledger row and the payment update share one transaction, and the provider call carries idempotency key `"refund-"+reference` (Stripe). Double refund is well defended for same-reference retries. Cancel: refund only in the `CONFIRMED && admin` branch of `cancelOrder` (`OrderServiceImpl.java:206-225`) — **not** for the user PENDING cancel (ORD-1) and **not** in the reconciliation auto-cancel (ORD-5).
- Test-mode guardrails: `PaymentStripeLiveKeyGuard` refuses `sk_live_`/`rk_live_` unless `shop.payment.allow-live-stripe-keys=true` (`PaymentStripeLiveKeyGuard.java:25-41`) — good. `PaymentProviderProductionGuard` blocks `mock` and unconfigured providers **only when `shop.payment.production-mode=true`** (`payment-service application.yml:39` defaults it to `false`) and explicitly allows COD (ORD-16); `PaymentStripeProperties.isSandbox()` is satisfied by the `sandbox` flag alone, which can pair with a live key (ORD-17).

## 6. Shipping

- A shipment is created when shipping-service consumes an order event with `status=CONFIRMED` (`OrderEventConsumer.java:20-25` → `ShipmentServiceImpl.handleOrderEvent:62-69` → `ShipmentCreationService.createForOrder`), one task per order enforced by `uk_shipment_creation_task_order` (`changelog-007-shipment-creation-tasks.yaml:34-37`) with 5 attempts / 60s×5ⁿ retry.
- A cancelled order **can still ship**: `cancelShipment` only cancels shipments still in `CREATED` (`ShipmentServiceImpl.cancelShipment:86-96`); once picked up, the parcel continues, auto-delivers after 7 days (`ReconciliationScheduler`), and the resulting `shipping.delivered.v1` is a no-op for the cancelled order — goods delivered against a cancelled/refunded order (ORD-15).
- Address validation: **none**. `OrderCreateRequest` (`OrderCreateRequest.java:5-11`) has no validation annotations; `recipient_name (100)`, `phone_number (20)`, `shipping_address (500)` are guarded only by DB column lengths (ORD-18).

## 7. Kafka usage

Topics (constants, `KafkaTopicContract.java:37-48`):

| Service | Produces (via DB outbox) | Consumes (groupId) |
|---|---|---|
| order-service | `shop.order.lifecycle.v1` (`order.created.v1`, `order.updated.v1`, `order.cancelled.v1`) | `shop.payment.lifecycle.v1` (`order-service-payment-group`), `shop.shipping.lifecycle.v1` (`order-service`) |
| payment-service | `shop.payment.lifecycle.v1` (`payment.captured.v1`, `payment.failed.v1`, `payment.refunded.v1`, `payment.partially_refunded.v1`) | — |
| shipping-service | `shop.shipping.lifecycle.v1` (`shipping.delivered.v1` only) | `shop.order.lifecycle.v1` (`shipping-service`) |
| tax-service | — | — (no Kafka at all) |

- **Outbox, not direct publish**: no `kafkaTemplate.send` exists in any of the four services (grep). All producers write an `outbox_events` row in the same transaction as the state change (`OrderEventPublisherImpl.save`, `PaymentEventPublisher.save`, `ShippingEventPublisherImpl.save`) and a `@Scheduled` relay publishes with a per-aggregate claim (`OrderOutboxRelay.claimPendingBatch` with `PESSIMISTIC_WRITE` + NOWAIT, `PaymentOutboxRelay.claimAggregateEvents` with `pg_try_advisory_xact_lock` + `FOR UPDATE SKIP LOCKED` + lease owner, `ShippingOutboxRelay`). This is the correct pattern; the only direct `KafkaTemplate` uses are the DLQ publisher inside the shared listener config.
- Consumer config (`BaseKafkaListenerConfig`, `KafkaProperties`): `enable.auto.commit=false`, `isolation.level=read_committed`, `max.in.flight=1`, `enable.idempotence=true`, `acks=all`; error handler `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000ms, 3))` → DLT `<topic>-dlt`; undecodable payloads raise `PoisonRecordException` → DLQ.
- Consumer idempotency: there is **no `eventId` dedupe in any consumer**. Safety rests on order/payment state machines: shipping dedupes by `orderId` (unique creation task, state-checked cancel), order-service dedupes confirms by `"payment-confirm-"+paymentId` in `idempotency_keys`, delivery handler by exact status. Replay of a topic into a new group is `earliest` by default — see ORD-12 for the hole this exposes.

## 8. `@Transactional` correctness summary

- No self-invocation bypass found: `shipOrder`/`deliverOrder` are annotated on the public interface methods and `confirmOrder`/`cancelOrder` intentionally use `TransactionTemplate`/`REQUIRES_NEW` instead of annotations; `IdempotencyServiceImpl.complete/abort` are called across bean boundaries.
- `cancelTransactionTemplate` is `PROPAGATION_REQUIRES_NEW` (`OrderServiceImpl.java:118-124`) — correct for the compensation-then-flip ordering, but see ORD-6.
- Missing `rollbackFor`: everything throws `RuntimeException` subclasses, so the default is adequate; the deliberate `noRollbackFor = BusinessException.class` on `trackOrder` (`OrderServiceImpl.java:463`) is required for the failure counter to persist and is correct.
- Real problems are transactions **spanning remote calls**: ORD-4 (payment capture/refund, cart item add, return review) and the cross-transaction detached-entity mutation in the webhook capture path (ORD-13).

## 9. Concurrency

- `@Version` exists on `Order` (`Order.java:89`), `Payment` (`Payment.java:68`), `Shipment`, `ShipmentCreationTask`, `ShipmentEvent` — every read-then-act is *detected* on write, but **never compensated**: the remote side effects have already happened by the time the optimistic lock fires (ORD-6).
- No atomic `UPDATE … WHERE status=?` state flips anywhere in order-service; `claimForProcessing`/`insertIfAbsent`/`claimAggregateEvents` in repositories show the pattern was known and applied elsewhere.
- DB invariants: `payments.idempotency_key` UNIQUE, `payment_refunds (payment_id, refund_reference)` UNIQUE, `payment_events (provider, provider_event_id)` UNIQUE, `shipment_creation_tasks.order_id` UNIQUE (partial), `carts.user_id` UNIQUE for active, `failed_compensations (reservation_id, kind)` UNIQUE for open. **Missing**: nothing ties a captured payment to a single order (`existsByOrderId` is declared at `PaymentRepository.java:45` but never called — ORD-3), and there is no `CHECK (total >= 0)` / non-negative constraint on any money column (ORD-8). Orders have no natural business key (`tracking_code` is a random UUID), so duplicate orders are only prevented by the optional idempotency key (ORD-2).

---

# Findings

## ORD-1 — User cancels a PENDING order after the money was captured: order cancelled, refund never issued
- **Severity:** P0 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:187-233` (refund only at line 222, inside the `CONFIRMED && isAdmin` branch), `.../kafka/PaymentLifecycleConsumer.java:30-42`, `.../service/OrderEventPublisherImpl.java:71-78` (`refunded: false` hardcoded)
- **Current behavior:** Payment capture is asynchronous relative to order state: the normal window between "customer paid" and "payment.captured.v1 consumed" has the order in `PENDING` with a `CAPTURED` payment. In that window `cancelOrder` passes `status != PENDING` (line 187), passes `validateTransition(PENDING→CANCELLED)` (line 190), takes the PENDING branch (line 192) which releases stock and the coupon but **never contacts payment-service**, flips the order to `CANCELLED` in its `REQUIRES_NEW` transaction (lines 230-232) and publishes `order.cancelled.v1` with `refunded=false`. When the captured-payment event is then consumed, `confirmOrder` fails `validateTransition(CANCELLED→CONFIRMED)` and the consumer deliberately swallows exactly that error code (`PaymentLifecycleConsumer.java:36-39`). Nothing else in order-service or payment-service reconciles a `CAPTURED` payment against a `CANCELLED` order (payment-service has no order-event consumer at all).
- **Expected:** The cancel path must check for a captured/refundable payment (as `OrderReturnServiceImpl` and the admin branch do) and either block cancellation until the confirm settles, or trigger `refundByOrderId(orderId)` with the compensation ledger on failure.
- **Unsafe interleaving:** 1) checkout creates order O (`PENDING`); 2) customer pays, Stripe captures, `payment.captured.v1` is produced; 3) customer clicks "Cancel" before the event is consumed → `status==PENDING` → cancel succeeds, no refund call; 4) consumer processes the captured event → `CANCELLED→CONFIRMED` rejected → skipped. Terminal state: money taken, order cancelled, `refunded=false` published, stock released.
- **Business impact:** direct financial loss per occurrence; also the coupon and stock were released so the customer can re-order the goods while keeping the charge.
- **Fix:** In `cancelOrder`, before the PENDING branch, query `paymentClient.findCapturedByOrderId`; if a captured payment exists, route through the refund flow (or reject cancel with "await confirmation"). Add a daily reconciliation that pairs `CANCELLED` orders with `CAPTURED` payments and auto-refunds. Consume `payment.captured.v1` for a cancelled order by issuing a refund instead of skipping.
- **Regression tests:** (a) cancel while payment captured → refund invoked exactly once; (b) cancel with no payment → no refund call; (c) captured event arriving after cancel → refund issued from the consumer; (d) refund RPC failure during that cancel is recorded in `failed_compensations`.

## ORD-2 — Checkout is not idempotent without a client-supplied key; concurrent identical requests create duplicate orders, reservations and coupon uses
- **Severity:** P0 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:35-45` (`required = false`), `.../service/impls/OrderServiceImpl.java:148-159` (`begin(null)` returns empty), `.../service/impls/OrderCreateSaga.java:121-146` vs `:211-218` (cart lock released at end of TX1; cart items deleted only in TX2), `.../repository/CartRepository.java:23-31`
- **Current behavior:** With no `Idempotency-Key`, nothing deduplicates: no unique constraint on orders (no order number; `tracking_code` is a random UUID, `changelog-001-initial-schema.yaml:66-113`), and the pessimistic cart lock is held only inside TX1, which ends **before** the multi-second pricing/reservation phase and **before** `cartItemRepository.deleteAll(items)` in TX2. The cart is therefore still fully populated when the next request acquires the lock.
- **Expected:** The whole checkout for a cart must be serialized (hold the cart lock for the entire saga, or atomically claim the cart items with `DELETE … RETURNING` / a `checked_out_at` guard), and/or an idempotency key must be mandatory (server-generated from `userId+cartId+contentHash` when absent).
- **Unsafe interleaving:** 1) R1 enters TX1, locks cart C, creates order O1, commits (lock released); 2) R2 enters TX1, locks C, reads the **same 3 cart items** (not yet deleted), creates order O2; 3) both run pricing → both reserve the same promotion, both reserve the same inventory; 4) both commit TX2, both `deleteAll` the same items. Result: two live orders, twice the stock held, coupon consumed twice (per-order reservation semantics), customer charged twice if the client also retries payment creation.
- **Business impact:** duplicate orders = duplicated stock holds and duplicated promotion usage; with a payment retry this is a double charge.
- **Fix:** wrap the saga in a single serialized unit per cart (`SELECT … FOR UPDATE` held to the end, or an atomic `UPDATE carts SET checkout_started_at … WHERE checkout_started_at IS NULL` claim checked by the DB), and default the idempotency key server-side (`sha256(userId|cartId|itemsHash)` stored in `idempotency_keys`) when the header is missing. Consider a DB-level guard (e.g. a `checked_out_from_cart` unique index on the active cart id).
- **Regression tests:** (a) two concurrent `POST /orders` without a key on the same cart → exactly one order; (b) retry with the same key after a timeout → same order returned; (c) retry with the same key but a mutated body → 409 `ORDER_DUPLICATE_REQUEST`; (d) two concurrent checkouts of *different* carts both succeed.

## ORD-3 — No guard that an order has at most one captured payment → double capture / double charge
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** payment-service
- **Files:** `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:87-117` (create has no per-order check), `.../repository/PaymentRepository.java:45` (`existsByOrderId` declared, never used), `.../resources/db/changelog/changelog-001-payments.yaml:36-40` (unique on `idempotency_key` only, `order_id` not even indexed uniquely), `order-service/.../service/impls/OrderServiceImpl.java:288-300` (confirm matches the newest captured payment)
- **Current behavior:** `create()` deduplicates only per `idempotencyKey`. Two payments for the same `orderId` with different keys can both be created and both captured; each capture publishes `payment.captured.v1`. `findCapturedByOrderId` returns the first captured row (newest first), so confirm succeeds against one of them while the customer was charged twice. There is also no cross-check at creation that the requested amount equals the order total.
- **Expected:** Either reject a second payment for an order that already has a non-failed payment (use the dead `existsByOrderId`), or make "one active payment per order" a DB constraint, and verify the amount against order-service before capture.
- **Unsafe interleaving:** create(key A, order X, 100) → capture → create(key B, order X, 100) → capture → two `CAPTURED` rows; order confirms once; customer paid 200 for a 100 order; the refund path refunds only the newest.
- **Business impact:** overcharging; refund bookkeeping ambiguity.
- **Fix:** enforce uniqueness of *active* payments per order (partial unique index `WHERE status <> 'FAILED'`), plus an amount reconciliation against order-service before capture.
- **Regression tests:** second create+capture for a paid order → rejected; failed payment may be replaced by a new one.

## ORD-4 — PSP HTTP calls executed inside `@Transactional` (row locks and connections held across 5–45s remote calls)
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** payment-service (also order-service)
- **Files:** `payment-service/.../service/impls/PaymentServiceImpl.java:148-164` (`capture`), `:187-229` (`refund`, payment row locked `FOR UPDATE` at `:189` with 10s lock timeout via `PaymentRepository.findByIdForUpdate`), `payment-service/.../config/PaymentPspRestClientConfig.java` (Stripe/MoMo/VNPay clients), `order-service/.../service/impls/CartServiceImpl.java:49-87` (product HTTP under cart `FOR UPDATE`), `order-service/.../service/impls/OrderReturnServiceImpl.java:146-211` (refund HTTP under return-row locks), `order-service/.../resources/application.yml:64` (`refund-timeout-ms: 45000`)
- **Current behavior:** `capture()` opens a transaction, reads the payment (no lock), then performs the Stripe/MoMo/VNPay call inside the transaction; `refund()` opens a transaction, locks the payment row, then performs the provider call inside the same transaction — with a configured 45s upper bound. Any slowness at the PSP blocks the payment row (all other refunds/webhook appliers on that payment queue behind the 10s `FOR UPDATE` timeout and fail with 503) and holds a JDBC connection; under a PSP incident the pool exhausts and takes the whole service down, including read endpoints.
- **Expected:** Remote call outside the transaction, then a short transaction that applies the confirmed outcome (the order-service saga already demonstrates this shape in `OrderCommitCoordinator`); the payment row lock should be taken only for the local write.
- **Unsafe interleaving:** refund T1 locks payment P and calls Stripe (slow); webhook `charge.refunded` for P arrives → `applyProviderRefund` blocks on `findByIdForUpdate` → 10s timeout → 503 → retry loop; meanwhile every Hikari connection is consumed by slow refunds → total outage.
- **Business impact:** availability (payment service outage), lock convoys, and 503-driven refund retries amplifying PSP traffic.
- **Fix:** restructure to `call-provider → short tx(persist + outbox)`; keep the `FOR UPDATE` only for the ledger write; use a shorter provider timeout with circuit breaker (Stripe/MoMo/VNPay clients currently have none of the resilience annotations used elsewhere).
- **Regression tests:** simulate a 45s PSP response → payment row remains readable to other transactions; concurrent refund + webhook on one payment → both complete without deadlock; pool occupancy stays flat.

## ORD-5 — Reconciliation auto-cancel ignores payment state: captured money auto-cancelled with no refund
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../service/OrderReconciliationScheduler.java:104-175` (`autoCancel` flips to `CANCELLED`, no payment lookup, no refund), `:80-102` (scan is PENDING older than `order.reconciliation.stuck-minutes:30`)
- **Current behavior:** A `PENDING` order whose reservations all read `RELEASED/EXPIRED` (or that has no items) is auto-cancelled. The scheduler polls **only** promotion/inventory reservation state; a payment that was already captured for that order is never consulted, and `autoCancel` never triggers a refund — it publishes `order.cancelled.v1` with `refunded=false`.
- **Expected:** auto-cancel must check `paymentClient.findCapturedByOrderId` and refund before/after cancelling, recording a compensation on failure.
- **Unsafe interleaving:** order PENDING + payment captured + inventory reservation TTL expires (slow customer or delayed confirm consumer) → 30 min later the scheduler cancels the order → customer charged, order cancelled, no refund, no trace beyond `order.cancelled.v1(refunded=false)`.
- **Business impact:** silent financial loss, recurring (every 5 min scan).
- **Fix:** add the payment gate to `autoCancel` (refund or route to a manual queue); record `FailedCompensation` entries when the refund fails; publish `refunded` truthfully in the event.
- **Regression tests:** captured payment + expired reservation → auto-cancel triggers refund; refund failure → ledger row + metric; no payment → unchanged behavior.

## ORD-6 — Remote compensations/commits run before the guarded status flip; optimistic-lock failure leaves remote state inconsistent
- **Severity:** P1 · **Confidence:** HIGH_CONFIDENCE_INFERENCE (interleaving) on CONFIRMED code facts · **Service:** order-service
- **Files:** `order-service/.../service/impls/OrderServiceImpl.java:179-233` (cancel: release stock/promotion/refund happen at lines 192-228 **before** the `REQUIRES_NEW` flip at 230-232), `:273-331` (confirm: `commitCoordinator.commitForConfirm` remote commits at 302-307 **before** the flip tx at 309-317), `.../service/OrderCommitCoordinator.java:46-128` (compensates only for failures *inside* the coordinator)
- **Current behavior:** Both transitions follow check-then-act with remote side effects first and a `@Version`-guarded save last. The optimistic lock *detects* a lost race (e.g. concurrent cancel vs confirm) and throws — but by then the remote inventory/promotion/refund effects are already applied and **no compensation runs** for them:
  - cancel wins, confirm loses → `commitForConfirm` already committed inventory+promotion for an order now `CANCELLED`; the thrown `ObjectOptimisticLockingFailureException` is wrapped into `CONFIRM_COMMIT_FAILED` (`OrderServiceImpl.java:322-329`) with no rollback of those commits. Stock is consumed against a cancelled order and `OrderReconciliationScheduler` only scans `PENDING`, so nothing ever reconciles it.
  - confirm wins, cancel loses → cancel already released the reservations and (admin case) triggered the refund **before** its save fails: stock released for a `CONFIRMED` paid order; refund issued against an order that remains `CONFIRMED` and shippable.
- **Expected:** Re-check-and-flip the status **atomically and first** (e.g. `UPDATE orders SET status=…, version=version+1 WHERE id=? AND status=? AND version=?` returning rows), then perform the remote effects with the ledger as the safety net; or run the remote effects in the coordinator and add a post-flip compensation that releases the coordinator's commits when the flip fails.
- **Unsafe interleaving:** as above (two interleavings listed).
- **Business impact:** stock loss / phantom stock, coupon corruption, refunds on shipped orders — all with the order in a "clean" terminal state.
- **Fix:** atomic conditional status update as the serialization point; then remote effects; on flip failure, compensate the coordinator's committed list (it already exists as `committed` — return it instead of swallowing); route "cancelled order with committed reservations" into the reconciliation scanner.
- **Regression tests:** concurrent cancel+confirm with forced interleavings → no committed inventory on a cancelled order, no released reservation on a confirmed order, no refund on a still-confirmed order; flip failure after commit → `failed_compensations` entry and reconciliation resolves it.

## ORD-7 — Tax integration is unusable when enabled, and silently zero when disabled (the default)
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE (mismatch) / HIGH_CONFIDENCE_INFERENCE (checkout failure when enabled) · **Service:** order-service ↔ tax-service
- **Files:** `order-service/.../client/TaxServiceClient.java:40-44` (calls `POST /api/v1/backoffice/tax-rates/calculate`), `order-service/.../service/impls/PricingServiceImpl.java:130-146` (sends `new TaxCalculateRequest(null, null, null, taxableAmount, currency)`), `tax-service/.../controller/TaxCalculationController.java:24-31` (real endpoint is `ApiPaths.TAX_CALCULATION = /api/v1/tax/calculate`, `utils/common-core/.../ApiPaths.java:45`), `tax-service/.../dto/request/TaxCalculateRequest.java:10-16` (`@NotNull UUID taxClassId`, `@NotNull @Pattern(^[A-Z]{2}$) country`), `order-service/.../resources/application.yml:56` (`TAX_SERVICE_ENABLED:false` default)
- **Current behavior:** Three independent breakages: (1) the URL order-service calls does not exist on tax-service (`/api/v1/backoffice/tax-rates/calculate` has no handler — the backoffice controller exposes only CRUD, `BackofficeTaxRateController.java:33-61`) → 404 → `HttpClientErrorException` → `ORDER_TAX_CALCULATION_FAILED` → the saga aborts and deletes the order; (2) even at the right URL the request would fail `@Valid` because `taxClassId` and `country` are null; (3) the payload is aggregate-only — per-line tax classes and destination are not sent, so mixed-rate taxation is impossible by construction. With the default `TAX_SERVICE_ENABLED:false`, `TaxServiceClient.calculate` short-circuits to `ZERO` (`TaxServiceClient.java:36-38`) and every order is created with `tax_amount=0`.
- **Expected:** a working per-line calculation call (product tax class + destination country/postal), with a clear fail-open/fail-closed policy.
- **Business impact:** enabling tax (the documented production posture, cf. `PaymentGateValidator`'s fail-closed philosophy) breaks 100% of checkouts; leaving it off means VAT is never charged — a legal/compliance defect.
- **Fix:** point the client at `POST /api/v1/tax/calculate`, send per-line rows (`taxClassId`, `country`, `postalCode`, amounts), and decide explicitly whether tax failure blocks checkout.
- **Regression tests:** contract test asserting the wired path exists on tax-service; checkout with tax enabled produces non-zero tax for a configured rate; mixed-rate cart sums correctly.

## ORD-8 — Discounts are never clamped and no non-negative invariant exists on totals
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../service/impls/PricingServiceImpl.java:112-116` (`taxableAmount = subtotal − discountAmount`, no clamp; total can be negative when tax is disabled), `.../entity/Order.java:32-46` (money columns, no checks), `order-service/.../resources/db/changelog/changelog-001-initial-schema.yaml:78-84` (no `CHECK`), `payment-service/.../dto/CreatePaymentRequest.java:14` (`@DecimalMin("0.01")` rejects a negative amount downstream)
- **Current behavior:** `discountAmount` is trusted from promotion-service. If it exceeds the subtotal, and tax is disabled (the default, ORD-7), the order is persisted with a **negative** total. The only backstop is payment creation's `@DecimalMin("0.01")`, so the order can never be paid — it lingers `PENDING` holding committed coupon state until the 30-minute reconciliation cancels it; the promotion usage was already consumed.
- **Expected:** `discountAmount ≤ subtotal` enforced in code and `CHECK (total >= 0 AND subtotal >= 0 AND tax_amount >= 0 AND discount_amount >= 0)` in the DB; a negative quote should be rejected before the promotion reservation is committed.
- **Business impact:** invalid orders, wasted coupon budget, potential for abuse if any future payment path accepts negative amounts.
- **Fix:** clamp/validate in `PricingServiceImpl`, add the DB checks, and treat a discount larger than the subtotal as a promotion-service contract violation (fail closed).
- **Regression tests:** promotion returning `discount > subtotal` → checkout rejected before inventory reserve; DB rejects direct negative-total writes.

## ORD-9 — Tax is computed once on the discounted order total; rate selection is "first match" with no specificity guarantee beyond postal-over-country
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE (design) · **Service:** tax-service
- **Files:** `tax-service/.../service/TaxCalculator.java:13-16`, `.../repository/TaxRateRepository.java:18-23` (`order by case when postalCode is null then 1 else 0 end` — ties beyond that are arbitrary), `.../service/impls/TaxCalculationServiceImpl.java:30-41`
- **Current behavior:** one rate × one rounded result for the whole order. Rounding itself is correct (`HALF_UP`, scale 2), but jurisdictions requiring per-line rounding or multiple concurrent rates (e.g. state+county+city) cannot be represented; two postal-specific rates for the same class/country are resolved nondeterministically (`findFirst` over an unordered-by-rate list).
- **Expected:** per-line tax calculation with explicit rate precedence (postal > country > default, then stable tie-break, e.g. most recently effective), or documented single-rate-only scope.
- **Business impact:** tax amounts can differ from legal requirements on multi-rate or per-line-rounding jurisdictions; silent mis-taxation.
- **Fix:** per-line API + explicit precedence; add a deterministic tie-break and a uniqueness constraint on (class, country, postal).
- **Regression tests:** two matching rates → deterministic choice; multi-line cart with different classes → sum of per-line rounded taxes.

## ORD-10 — Checkout prices come from a 10-minute product-price cache, not a live quote
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../client/ProductServiceClient.java:28-30` (`@Cacheable("productPrice")`), `.../config/CacheConfig.java:21` (`PRODUCT_PRICE_TTL = 10 minutes`), `.../service/impls/PricingServiceImpl.java:57-97` (order priced from cached snapshots)
- **Current behavior:** The order total is recomputed server-side (good — cart prices are ignored), but each product snapshot may be up to 10 minutes stale. A price change (or a currency change, or a delisting) takes up to 10 minutes to affect new orders; a customer can also observe different prices on the PDP vs checkout within that window. Cache misses during an outage fall through to the circuit breaker, so no *invented* prices occur.
- **Expected:** for the pricing-critical path, bypass the cache (or use a very short TTL / version-keyed invalidation from product lifecycle events), and reject snapshots older than a bounded staleness.
- **Business impact:** bounded revenue leakage/generosity window; customer-visible inconsistency.
- **Fix:** `@Cacheable` only on the catalog read path; checkout calls an uncached variant, or product-service pushes `product.lifecycle` price-version invalidation consumed by order-service.
- **Regression tests:** price update → checkout within TTL uses the new price; cache disabled → behavior identical.

## ORD-11 — A crash between the saga's final commit and `idempotencyService.complete` locks the client out of checkout for up to 24h
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../service/impls/IdempotencyServiceImpl.java:49-76` (a row with `response_status=0` yields `ORDER_DUPLICATE_REQUEST` on every replay), `:78-87` (`complete` is a separate transaction after the saga's TX2, `OrderCreateSaga.java:211-222`), `.../service/impls/IdempotencyKeyCleanupScheduler.java:19-28` (purge only after the 24h TTL)
- **Current behavior:** `begin` commits the claim row in its own `REQUIRES_NEW` transaction; the saga then runs; `complete` (or `abort` on exception) runs later. A crash (kill -9, OOM, deploy) between the order's TX2 and `complete` leaves the row at status 0 while the order exists: every retry within 24h gets `ORDER_DUPLICATE_REQUEST` instead of the created order (the client can find it via `GET /orders/{id}` only if it persisted the id). The same window exists in `confirmOrder` (`OrderServiceImpl.java:309-321`), where it is less harmful because the state machine rejects the retry.
- **Expected:** complete the idempotency row in the **same** transaction as the saga's final commit (it is a local table), or return the resource lookup instead of `ORDER_DUPLICATE_REQUEST` when a row is in-flight and the aggregate exists.
- **Business impact:** support burden and 24h checkout lockout per incident; no money is lost.
- **Fix:** move `idempotencyService.complete` into TX2 (both are local writes); on `resolve()` of an in-flight row, verify the aggregate and return it.
- **Regression tests:** simulate crash after TX2 → retry with the same key returns 201-equivalent cached/looked-up order; expiry purge unblocks the key.

## ORD-12 — Replaying an already-captured payment webhook re-publishes a lifecycle event with a fresh `eventId` (dedupe defeated)
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** payment-service
- **Files:** `payment-service/.../service/WebhookEventService.java:114-128` (`retry` reprocesses stored payloads), `:149-176` (`process` → `PaymentStateMachine.transition(payment.getStatus(), CAPTURED)` returns `CAPTURED` when already captured, `PaymentStateMachine.java:22-33`), `.../service/PaymentWriter.java:61-68` (`completeWithEvent` marks PROCESSED **and** publishes), `.../outbox/PaymentEventPublisher.java:39-51` (each publish generates a **new** `eventId`)
- **Current behavior:** A stored `FAILED_RETRYABLE` event that was in fact already applied (or a duplicate delivery after the first attempt half-succeeded) transitions `CAPTURED→CAPTURED` (legal no-op) and then emits a *new* `payment.captured.v1` with a fresh `eventId` to Kafka. Downstream, order-service is saved only by the `"payment-confirm-"+paymentId` idempotency key and the state machine — both of which expire or no-op — so the event is a pure duplicate that re-drives confirm logic; for a still-PENDING order it triggers a **second** remote commit attempt (inventory/promotion commit with the same reservation ids; idempotency depends on inventory-service, outside this audit → NEEDS_RUNTIME_VERIFICATION for the final damage).
- **Expected:** idempotent replay should be recognized as a no-op: if the payment is already in the target state, mark the event PROCESSED **without** re-publishing, or publish a deterministic `eventId` derived from `provider+providerEventId` so downstream dedupe works.
- **Business impact:** duplicate events; with a reset/`earliest` replay or after idempotency-key TTL expiry, repeated confirm attempts (double remote commits, duplicate notifications).
- **Fix:** `transition(from==to)` in the *webhook* path should return a "already applied" signal consumed by `completeWithEvent` (persist PROCESSED, skip publish); deterministic event ids.
- **Regression tests:** deliver the same Stripe event twice with different Stripe event ids but the same intent state → exactly one lifecycle event; replay after 24h → no second commit attempt.

## ORD-13 — Webhook capture path mutates a detached payment across two transactions (no lock), unlike the refund path
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** payment-service
- **Files:** `payment-service/.../service/WebhookEventService.java:87-112` (`handle` is **not** transactional), `:149-176` (`process` reads via `paymentRepository.findById` — no lock — mutates the detached entity, then `writer.completeWithEvent` opens its own transaction), vs `.../service/ProviderRefundApplier.java:36-89` (refund path uses `@Transactional` + `findByIdForUpdate`)
- **Current behavior:** Two webhook events for the same payment (e.g. `payment_intent.succeeded` and `charge.refunded`) can be processed concurrently: both read the same version, both compute a transition locally, the loser fails on `@Version` at flush and is marked retryable — acceptable — but there is no serialization point, so intermediate states (e.g. `providerIntentId` assignment at lines 166-168) can be silently lost, and the receipt attachment (`attachReceipt:265-271`) is yet another separate transaction.
- **Expected:** same discipline as the refund path: lock the payment, apply, persist, publish in one transaction.
- **Business impact:** low-frequency lost updates (intent id, receipt key); confusing retry behavior.
- **Fix:** route the capture/failed path through a `@Transactional` applier with `findByIdForUpdate`, mirroring `ProviderRefundApplier`.
- **Regression tests:** concurrent `succeeded` + `refunded` webhooks → both applied, no lost update; retry after version conflict succeeds.

## ORD-14 — `CONFIRMED`/`SHIPPED` orders and `CREATED` shipments have no sweeper; delivery requires a manual `/ship` call
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service + shipping-service
- **Files:** `order-service/.../service/OrderReconciliationScheduler.java:80-86` (PENDING only), `order-service/.../constant/OrderStatus.java` + `OrderStatusServiceImpl.java:16-22` (no `CONFIRMED→DELIVERED` edge), `shipping-service/.../outbox/ShippingEventPublisherImpl.java:24-38` (only `shipping.delivered.v1` is published), `shipping-service/.../scheduler/ReconciliationScheduler.java:25-36` (`inFlight` excludes `CREATED`), `order-service/.../controller/OrderStatusController.java:32-42` (`/ship`, `/deliver` are manual SERVICE/ADMIN calls)
- **Current behavior:** Nothing advances an order from `CONFIRMED` automatically: shipping never publishes picked-up/in-transit events, so `SHIPPED` is reachable only by someone calling `POST /orders/{id}/ship`; and `shipping.delivered.v1` is dropped unless the order is exactly `SHIPPED` (`ShippingDeliveredHandler.java:36-39`) — a delivered parcel against a still-`CONFIRMED` order leaves the order stuck forever (returns, review-purchase eligibility and ratings all require `DELIVERED`). Additionally a shipment stuck in `CREATED` (manual carrier, tracking never assigned) is excluded from the 7-day auto-delivery sweep and therefore never resolves.
- **Expected:** ship on carrier pickup (or a sweeper that promotes `CONFIRMED` orders with acknowledged shipments to `SHIPPED`), plus a sweeper/queue for `CONFIRMED` orders older than N days and for shipments stuck in `CREATED`.
- **Business impact:** orders permanently non-terminal → no returns, no purchase-verification, no SLA reporting; manual toil.
- **Fix:** publish carrier transitions; map them to order `SHIPPED`; add an aged-order sweeper with alerting; include `CREATED` shipments in reconciliation.
- **Regression tests:** delivered event while `CONFIRMED` → order promoted or dead-lettered visibly; shipment `CREATED` for 14 days → swept/alerted.

## ORD-15 — Cancelled orders can still physically ship; delivery is then silently dropped
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** shipping-service ↔ order-service
- **Files:** `shipping-service/.../service/impls/ShipmentServiceImpl.java:86-96` (`cancelShipment` only cancels `CREATED`; anything else is "left untouched"), `.../scheduler/ReconciliationScheduler.java:45-64` (auto-`DELIVERED` after 7 days for in-flight shipments), `.../outbox/ShippingEventPublisherImpl.java:30-38`, `order-service/.../service/ShippingDeliveredHandler.java:36-39` (no-op unless order is `SHIPPED`)
- **Current behavior:** Order cancel and shipment state are not reconciled across services. A cancelled order whose shipment already left `CREATED` keeps moving; 7 days later the reconciliation auto-delivers it and publishes `shipping.delivered.v1`, which order-service drops. The customer was refunded (return review) or never charged (ORD-1) while the goods are delivered anyway. There is no "recall/return-to-sender" flow.
- **Expected:** on `order.cancelled.v1`, cancel or recall any non-delivered shipment (carrier cancellation API for GHN/GHTK; explicit `CANCELLED` transition from `PICKED_UP/IN_TRANSIT/OUT_FOR_DELIVERY` is legal in `ShipmentStateMachine.java:20-23`), or create a reverse-logistics return task; surface delivered-but-cancelled as an exception queue.
- **Unsafe interleaving:** confirm → shipment picked up → admin cancel → 7-day auto-deliver → delivered event dropped; goods lost.
- **Business impact:** direct goods loss per occurrence; also refund+delivery double loss when combined with ORD-1.
- **Fix:** cross-service cancellation handler; restrict auto-deliver to shipments whose order is not cancelled (needs an order-state lookup or a cancelled flag on the shipment); exception queue for delivered-cancelled pairs.
- **Regression tests:** cancel while shipment in flight → shipment cancelled/recalled; auto-deliver skips cancelled orders; delivered-cancelled pair raises an operational event.

## ORD-16 — COD provider is always registered and returns an instant `CONFIRMED` capture; the caller selects the provider per payment
- **Severity:** P1 · **Confidence:** CONFIRMED_FROM_SOURCE (code facts) / NEEDS_RUNTIME_VERIFICATION (reachability via the BFF) · **Service:** payment-service
- **Files:** `payment-service/.../provider/CodProvider.java:9-26` (unconditional `@Component`; `capture` returns `confirmed` immediately), `.../service/impls/PaymentServiceImpl.java:64-69` (per-request provider honored), `.../dto/CreatePaymentRequest.java:18` (caller-supplied `provider`), `.../config/PaymentProviderProductionGuard.java:43-45` (COD explicitly allowed in production mode), `.../controller/PaymentController.java:39-55` (SERVICE/ADMIN + caller guard only)
- **Current behavior:** Unlike `MockProvider` (`@ConditionalOnProperty shop.payment.provider=mock`), `CodProvider` is registered unconditionally, so `providerFactory.getProvider("cod")` resolves in **any** deployment, including one configured for Stripe. Whoever can call `POST /payments` (an internal SERVICE caller; in this repo no order-service code path creates payments, so the caller is external to the audited scope) can create a payment with `provider=cod`, "capture" it with zero money movement, and thereby satisfy order-service's confirm gate (`OrderServiceImpl.java:288-300` only checks that a `CAPTURED` payment exists for the order). The confirm gate's entire purpose (`PaymentGateValidator.java:26-49`) is voided for such payments.
- **Expected:** COD should be an explicit deployment-level decision (like Mock), not a per-request choice; a COD payment must not satisfy the "paid" gate before goods delivery (capture-on-delivery semantics), or at minimum must be flagged so the confirm gate can treat it differently.
- **Business impact:** free goods if reachable by any caller that can set the field; at minimum an invariant hole between the payment gate and reality.
- **Fix:** `@ConditionalOnProperty` on the deployment's provider; ignore/reject per-request provider values that differ from the configured default unless ADMIN; mark COD payments `PENDING` until delivery confirmation.
- **Regression tests:** payment with `provider=cod` on a stripe deployment → rejected; COD flow only when the deployment provider is COD; confirm gate treats COD captures per business rule.

## ORD-17 — Stripe `sandbox` flag overrides key-type detection; auto-confirm test parameters can target a live account
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** payment-service
- **Files:** `payment-service/.../config/PaymentStripeProperties.java:53-55` (`isSandbox() = sandbox || sk_test_…`), `payment-service/.../resources/application.yml:48-49` (`sandbox: true`, `auto-confirm-sandbox: true` by default), `.../provider/StripeProvider.java:74-83` (`autoConfirmSandbox && isSandbox` → `setPaymentMethod(pm_card_visa).setConfirm(true)`), `.../config/PaymentStripeLiveKeyGuard.java:25-41` (live key requires explicit opt-in but nothing reconciles it with `sandbox`)
- **Current behavior:** `sandbox` is a free-floating flag: an operator who sets `allow-live-stripe-keys=true` and a live key but forgets `SHOP_PAYMENT_STRIPE_SANDBOX=false` still gets `isSandbox()==true`, so every capture is built with the test payment method `pm_card_visa` and `confirm=true` against the live account. Stripe rejects the test PM in live mode, so the practical failure is a hard capture failure (availability, not fraud) — but the flag semantics silently invert the key-type reality and hide misconfiguration.
- **Expected:** sandbox determination derived from the key prefix (source of truth), with the `sandbox` property only able to *narrow* (force test) — and a startup guard that fails when a live key is combined with `sandbox=true` or `auto-confirm-sandbox=true`.
- **Business impact:** misconfiguration class that either breaks all captures (best case) or, if Stripe behavior differs, sends test-flow parameters into production money movement.
- **Fix:** derive from key prefix + startup validation; log the effective mode at startup (partially exists via `PaymentGateValidator` for the order side only).
- **Regression tests:** live key + `sandbox=true` → refuse to boot; test key → auto-confirm allowed; live key + `sandbox=false` + `auto-confirm-sandbox=true` → refuse.

## ORD-18 — No validation of recipient/address/phone on order creation
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../dto/request/OrderCreateRequest.java:5-14` (no annotations), `.../entity/Order.java:70-87` (DB lengths only), `order-service/.../controller/OrderController.java:35-45`
- **Current behavior:** `recipientName`, `phoneNumber`, `shippingAddress` may be null/blank; over-length values surface as `DataIntegrityViolationException` **mid-saga** (after TX1 created the order and pricing consumed a coupon reservation) and are compensated only by the generic failure path. `trackOrder`'s phone-auth path (`OrderServiceImpl.java:440-454`) also cannot work for such orders. Downstream, shipping receives an order with no usable address.
- **Expected:** `@NotBlank` + `@Size` (+ phone pattern) on the DTO, matching the DB lengths; reject before the order row is created.
- **Business impact:** unshippable orders, wasted promotions, 500-class errors on bad input.
- **Fix:** bean validation at the edge; explicit `ADDRESS_REQUIRED` business error.
- **Regression tests:** blank/oversized/invalid input → 400 before any side effect; valid order unchanged.

## ORD-19 — Custom PSP signature verifiers have no freshness window; MoMo refund reuses one requestId per payment; VNPay refund omits required parameters
- **Severity:** P2 · **Confidence:** CONFIRMED_FROM_SOURCE (params) / NEEDS_RUNTIME_VERIFICATION (PSP behavior) · **Service:** payment-service
- **Files:** `payment-service/.../webhook/WebhookSignatureVerifier.java:21` (300s tolerance exists only for the Stripe-format header; the plain-HMAC path has none), `.../webhook/MoMoCallbackVerifier.java:15-28` (signed fields include `responseTime` but no tolerance check), `.../webhook/VNPayCallbackVerifier.java:22-47` (no timestamp param verified), `.../provider/MoMoProvider.java:128` (`refundOrderId = "RF"+paymentId` — same `requestId` for every refund of a payment), `.../provider/VNPayProvider.java:124-137` (refund params lack `vnp_RequestId`; `vnp_TransactionDate` = now, not the original transaction date, `:171-173`)
- **Current behavior:** Replayed MoMo/VNPay/shipping callbacks are neutralized only by the `provider_event_id` dedupe — the signature itself remains valid forever. More concretely for money: two partial refunds of one MoMo payment reuse the identical `requestId` (`RF…`), so the second refund either replays the first's cached response or fails the amount binding (`refundResponseBoundToRequest:171-175`) → `indeterminate`; VNPay's refund request as built cannot reference the original transaction date and omits the request id its API requires.
- **Expected:** freshness window (or nonce) on custom verifiers; refund request ids derived per refund reference (`refund-{reference}` like the Stripe path); VNPay refund must carry `vnp_RequestId` and the original `vnp_TransactionDate`.
- **Business impact:** second-and-later partial refunds are unreliable or fail permanently on MoMo/VNPay; long-window replays are a hardening gap.
- **Fix:** per-reference refund ids; timestamp validation on IPN callbacks (MoMo `responseTime`, VNPay `vnp_PayDate`); keep the ledger dedupe.
- **Regression tests:** two partial refunds on one MoMo payment → both succeed with distinct PSP request ids; replayed IPN older than tolerance → rejected; VNPay refund round-trips in the sandbox.

## ORD-20 — Reconciliation "mixed" bucket never resolves; the scan has no deterministic ordering
- **Severity:** P3 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../service/OrderReconciliationScheduler.java:104-125` (`routeToMixed` only logs + a metric), `:83-85` + `OrderRepository.java:23-24` (`PageRequest.of(0,50)` with no `Sort`)
- **Current behavior:** A PENDING order whose reservations are neither all-committed nor all-terminal (or whose state poll fails) is left PENDING indefinitely and re-scanned every cycle; with more than 50 stale PENDING orders the unsorted batch can starve newer ones. Stock for such orders is eventually freed by inventory TTLs (outside this audit), after which a later pass cancels them — so the practical impact is delay plus an unbounded warn-log loop, not a permanent leak.
- **Expected:** deterministic scan order (`createdAt ASC`), an age-based escalation (alert/queue) for orders in mixed state beyond N cycles, and a bounded retry counter.
- **Business impact:** delayed cleanup, alert noise, starvation under load.
- **Fix:** add `Sort.by("createdAt")`, mixed-state age counter, operational queue.
- **Regression tests:** mixed-state order older than N scans → escalated; 100 stale orders → oldest processed first.

## ORD-21 — `order.cancelled.v1` hardcodes `refunded=false` and nothing publishes reservation-release events
- **Severity:** P3 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../service/impls/OrderEventPublisherImpl.java:71-78`
- **Current behavior:** The cancelled event always claims `refunded=false` regardless of the refund flow's real outcome (it may be issued asynchronously later), and no event communicates reservation releases, so downstream consumers (notifications, analytics) cannot distinguish "cancelled and refunded" from "cancelled, money kept" — precisely the ORD-1/ORD-5 blind spot.
- **Expected:** publish the actual refund state (or a follow-up `refund.completed.v1`), and consider `reservation.released` events so inventory auditing is not TTL-dependent.
- **Business impact:** observability/auditability only.
- **Fix:** thread the refund outcome into the event; add release events.
- **Regression tests:** admin cancel of a paid order → event carries `refunded=true` after refund success.

## ORD-22 — Guest-cart ownership is a bare capability UUID; `trackOrder` failure counter is a non-atomic check-then-act
- **Severity:** P3 · **Confidence:** CONFIRMED_FROM_SOURCE · **Service:** order-service
- **Files:** `order-service/.../controller/CartController.java:36-37, 101-121` (`/api/v1/carts/**` is a public path per `application.yml:42-44`; possession of the guest UUID grants full cart write access and the right to merge it into any account), `OrderServiceImpl.java:433-460` (5-strikes/15-min lock stored on the order row, incremented via read-modify-write)
- **Current behavior:** Anyone who learns a guest cart id (logs, referrers, shared devices) can read/modify/merge that cart. The tracking lockout is per-order, per-phone-guess, non-atomic (a concurrent attempt loses its increment to a version conflict and 500s instead of counting), and the token path has no lockout at all (UUID entropy is the only defense).
- **Expected:** bind guest carts to a signed cookie/session with server-side issuance; per-IP rate limiting on `/orders/track`; atomic `UPDATE … SET track_failures = track_failures + 1` for the counter.
- **Business impact:** low-probability cart tampering / order-info enumeration.
- **Fix + tests:** signed guest-cart tokens; atomic counter update; brute-force regression test with parallel wrong phones → lock engages.

---

## Positive controls verified (for balance)

- All Kafka publishes in the four audited services go through a DB transactional outbox with claim-lease relays; no direct `kafkaTemplate.send` (grep across `src/main/java` of all four services).
- Stripe webhook verification uses the official SDK verifier; all HMAC comparisons are constant-time (`MessageDigest.isEqual`) in payment- and shipping-service.
- Refund double-spend is defended in depth: `FOR UPDATE` + `(payment_id, refund_reference)` unique index + provider idempotency key `"refund-"+reference` + order-side `carriesConfirmedRefund`.
- `requirePaymentMatchesOrder` blocks confirming an order unless paid amount and currency match exactly.
- Live Stripe keys are refused unless explicitly enabled per deployment.
- `IdempotencyKeyRepository.insertIfAbsent` + PK `(actor,key)` give race-free key claiming; `FailedCompensation` has a real claim-lease retry sweeper with a partial unique index.

## Files I could not read
None. Every file in the declared scope (order 99 + 14, payment 53 + 15, shipping 50 + 12, tax 22 + 3) was either read directly or deliberately skimmed only where noted above; shared `utils/common-kafka` / `common-core` files referenced by the audited code (`KafkaTopicContract`, `BaseKafkaConsumer`, `BaseKafkaListenerConfig`, `KafkaProperties`, `ApiPaths`) were also read.

## Findings count by severity

| Severity | Count | IDs |
|---|---|---|
| P0 | 2 | ORD-1, ORD-2 |
| P1 | 8 | ORD-3, ORD-4, ORD-5, ORD-6, ORD-7, ORD-8, ORD-15, ORD-16 |
| P2 | 9 | ORD-9, ORD-10, ORD-11, ORD-12, ORD-13, ORD-14, ORD-17, ORD-18, ORD-19 |
| P3 | 3 | ORD-20, ORD-21, ORD-22 |

**Total: 22 findings — P0×2, P1×8, P2×9, P3×3.**

## Top 5 one-liners

1. **ORD-1 (P0)** — Captured payment + user cancel while `PENDING` = money kept, order cancelled, no refund: `OrderServiceImpl.java:187-233` (refund only at `:222` in the admin-CONFIRMED branch), consumer skips the late captured event at `PaymentLifecycleConsumer.java:36-39`.
2. **ORD-2 (P0)** — Duplicate orders from concurrent checkout: `Idempotency-Key` optional at `OrderController.java:38` and the cart lock dies at `OrderCreateSaga.java:146` before items are deleted at `:214`.
3. **ORD-15 (P1)** — Cancelled order still ships: only `CREATED` shipments cancel (`ShipmentServiceImpl.java:86-96`), 7-day auto-deliver (`ReconciliationScheduler.java:45-64`), delivered event then dropped (`ShippingDeliveredHandler.java:36-39`).
4. **ORD-6 (P1)** — Cancel/confirm run stock/coupon/refund commits before the `@Version`-guarded flip (`OrderServiceImpl.java:192-232`, `:302-317`): a lost race leaves committed inventory on a CANCELLED order or a refund on a CONFIRMED one, with no compensation.
5. **ORD-7 (P1)** — Tax can never work: order-service calls `POST /api/v1/backoffice/tax-rates/calculate` (`TaxServiceClient.java:41`) which does not exist (real endpoint `/api/v1/tax/calculate`, `ApiPaths.java:45`), and sends `taxClassId=null, country=null` against `@NotNull` fields (`TaxCalculateRequest.java:11-12`); disabled by default → zero tax on every order.
