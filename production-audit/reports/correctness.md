# Business Correctness

Generated: 2026-11-10T10:05:42

Findings in this category: **30**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-010 | P1 | VERIFIED | Stock columns have no non-negative invariant: reserved_quantity/available_quantity can go negative |
| F-011 | P1 | VERIFIED | Reservation row has no version column: the expiry sweep can overwrite a COMMITTED reservation with EXPIRED (lo |
| F-014 | P2 | VERIFIED | Reservation retry loop swallows the interrupt flag and keeps retrying after the thread is interrupted |
| F-016 | P1 | OPEN | Orphaned PENDING orders with no order items can never be reconciled - they accumulate and alert forever |
| F-019 | P0 | VERIFIED | The payment-captured confirm gate and the cancel-refund trigger are both DISABLED by default - an order can be |
| F-021 | P1 | OPEN | A partial Stripe refund is recorded as a full refund: the webhook reads the charge amount, not the refunded am |
| F-022 | P2 | OPEN | Payment create idempotency replays without validating that the request payload matches |
| F-023 | P2 | OPEN | The confirm-path payment gate inspects only the first page of payments for an order |
| F-025 | P2 | OPEN | Admin cancel of a SHIPPED order marks it CANCELLED with no refund and no restock, while the inline comment cla |
| F-026 | P2 | OPEN | Reservation release during compensation is best-effort and the failure is swallowed after the order row is del |
| F-030 | P2 | OPEN | Order tracking is unauthenticated and gated only by the order phone number |
| F-037 | P2 | VERIFIED | tax-service soft-delete contradicts its own test - the module's build was RED, and the fleet had two different |
| F-039 | P0 | VERIFIED | The JVM default timezone is sent as the PostgreSQL 'TimeZone' startup parameter - on a host whose zone is 'Asi |
| F-044 | P1 | VERIFIED | coupon_usage_reservation has no version column: the TTL sweep can revert a COMMITTED redemption to EXPIRED, wh |
| F-046 | P2 | VERIFIED | ReservationRetryServiceImpl swallowed the interrupt flag and kept retrying - the same defect as F-014, in a se |
| F-047 | P2 | VERIFIED | A concurrent duplicate coupon reserve surfaced a raw DataIntegrityViolationException (HTTP 500) instead of the |
| F-048 | P3 | OPEN | Campaign update silently collapses every non-ACTIVE requested status to INACTIVE |
| F-049 | P3 | OPEN | CampaignServiceImpl.delete uses the strict orElseThrow() attribution while the rest of the fleet uses orElse(" |
| F-050 | P2 | OPEN | Registration uniqueness check is a TOCTOU: two concurrent sign-ups with the same username create two Keycloak  |
| F-051 | P1 | VERIFIED | A password change did not revoke the sessions established with the old password |
| F-052 | P1 | VERIFIED | A password reset did not revoke the sessions established with the compromised password |
| F-053 | P2 | OPEN | The password reset token has no version column: a reset token can be redeemed twice concurrently |
| F-054 | P2 | OPEN | The password reset email is never sent - the only PasswordResetEmailSender implementation logs instead of disp |
| F-055 | P1 | VERIFIED | A self-registration naming only privileged roles created an account with ZERO realm roles - the user could sig |
| F-060 | P2 | OPEN | product-service soft-deletes a product without checking for active inventory reservations, while inventory-ser |
| F-061 | P3 | OPEN | product-service soft-deletes a variant with a hard-coded 'system' actor and without a stock check |
| F-063 | P2 | OPEN | Deleting a media asset does not check whether a live product still references it |
| F-065 | P1 | VERIFIED | Hiding the only visible rating of a product crashed the moderation transaction - the administrator could not r |
| F-067 | P1 | OPEN | The GHN and GHTK carrier adapters never contact the carrier - they fabricate a deterministic tracking number f |
| F-068 | P2 | OPEN | A shipment that the carrier never confirmed is auto-delivered after seven days and drives the order to DELIVER |

---

### F-010 - Stock columns have no non-negative invariant: reserved_quantity/available_quantity can go negative

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** inventory-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java`
  - `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`
  - `inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java`
- **Actual implementation:** changelog-001 creates inventory.available_quantity and inventory.reserved_quantity as plain INTEGER NOT NULL DEFAULT 0 with no CHECK constraint. InventoryServiceImpl.commit() performs inventory.setReservedQuantity(reserved - q) and setAvailableQuantity(available - q) with no floor; release() does the same for reservedQuantity. The expiry sweep clamps with Math.max(0, ...) but the service path does not. The JPA @Version column only detects concurrent modification, it does not prevent a negative value.
- **Expected:** Stock cannot become incorrectly negative - enforced in the database as the last line of defence, and clamped in the application.
- **Trigger:** A commit or release that runs after the expiry sweep already returned the hold (sweep commits first, commit() then subtracts from an already-decremented reserved_quantity), or any arithmetic drift between the reserve/commit/release paths.
- **Root cause:** Invariant expressed only in application arithmetic with an inconsistent clamp; no declarative constraint at the storage layer.
- **Consequences:** Negative stock counters corrupt the capacity guard (available - reserved >= qty) so subsequent reservations can oversell; the overselling is silent because no check rejects the write.
- **Required remediation:** Add CHECK (available_quantity >= 0) and CHECK (reserved_quantity >= 0) via a new Liquibase changeset, and clamp the service-side arithmetic with Math.max(0, ...).
- **Regression test:** Concurrency test: N concurrent commits of the same reservation plus a concurrent expiry sweep must leave reserved_quantity >= 0 and available_quantity >= 0; a direct negative write must be rejected by the DB.
- **Remediation applied:** New Liquibase changeset inventory-service/changelog-004 adds CHECK (available_quantity >= 0) and CHECK (reserved_quantity >= 0) as NOT VALID (enforced for new/updated rows, no table lock, no failure on legacy rows). InventoryServiceImpl.commit() and release() now reject an arithmetic outcome that would go negative with BusinessException STOCK_INSUFFICIENT instead of writing it. The atomic reserve/release statements guard the same invariant in SQL.
- **Verification evidence:** inventory-service 64/64 green against real PostgreSQL with the new CHECK constraints; commit_rejectsNegativeStockOutcome and release_rejectsNegativeStockOutcome assert the domain error.
### F-011 - Reservation row has no version column: the expiry sweep can overwrite a COMMITTED reservation with EXPIRED (lost update) and double-decrement stock

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** inventory-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java`
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java`
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java`
  - `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`
- **Actual implementation:** Reservation has no @Version field and the reservations table has no version column. ReservationCleanupScheduler.releaseAllExpiredReservations() selects expired PENDING rows with an unlocked SELECT (findByStatusAndExpiresAtBefore) and then calls reservationRepository.saveAll(reservations) after mutating them to EXPIRED. InventoryServiceImpl.commit() loads the same row without a lock and writes status=COMMITTED. Whichever transaction commits last wins.
- **Expected:** A reservation that has been committed must never be reverted to EXPIRED, and a committed reservation's stock must be released exactly once.
- **Trigger:** A commit() that starts just before the reservation TTL elapses, racing with the 60-second expiry sweep.
- **Root cause:** No optimistic-locking or pessimistic-locking discipline on the reservation aggregate, and the sweep performs a blind read-modify-write.
- **Consequences:** A paid and confirmed order's reservation is marked EXPIRED; the release path then treats it as already terminalized and never restores or correctly accounts for the stock, producing stock inflation and overselling. The reservation can also be released twice against the inventory counters.
- **Required remediation:** Add a version column to reservations (Liquibase changeset) with @Version on the entity, and make the sweep claim rows with a locking/conditional UPDATE (status = PENDING AND expires_at < now) so only one writer can transition the row.
- **Regression test:** Concurrency test: commit() and the expiry sweep racing on the same reservation must end with exactly one terminal status and a single stock movement.
- **Remediation applied:** New Liquibase changeset adds reservations.version BIGINT NOT NULL DEFAULT 0 and the Reservation entity gains @Version. The expiry sweep no longer performs an unlocked SELECT + blind save: it CLAIMS each row with a conditional UPDATE (status = PENDING -> EXPIRED, version + 1) and only moves stock for the rows it actually claimed. The inventory decrement is an atomic guarded UPDATE (reservedQuantity >= qty) that can never over-credit.
- **Verification evidence:** inventory-service 64/64 green; sweep_skipsRowsAlreadyClaimedByAnotherWriter asserts the lost-update regression (a claim that affects zero rows must NOT move stock).
### F-014 - Reservation retry loop swallows the interrupt flag and keeps retrying after the thread is interrupted

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** inventory-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java`
- **Actual implementation:** withRetry() catches OptimisticLockingFailureException and calls sleep(). sleep() catches InterruptedException, calls Thread.currentThread().interrupt() and returns; the loop then continues to the next attempt and calls Thread.sleep() again, which throws immediately because the interrupt flag is still set. The loop therefore spins through the remaining attempts without actually waiting and finally raises INVENTORY_VERSION_CONFLICT.
- **Expected:** An interrupted request thread aborts the retry loop and propagates the interruption.
- **Trigger:** The client disconnects and the container interrupts the request thread while a version conflict is being retried.
- **Root cause:** The interrupt flag is restored but never acted upon; the loop has no cancellation check.
- **Consequences:** Busy retry loop on an interrupted thread; the caller sees a spurious version-conflict error instead of a cancellation, and the work continues after the client is gone.
- **Required remediation:** After restoring the interrupt flag, throw the exception (or return) instead of continuing to retry.
- **Regression test:** Unit test: interrupt the thread inside the retry loop and assert the loop stops after one attempt and does not swallow the InterruptedException.
- **Remediation applied:** ReservationServiceImpl.sleep() now returns false when the thread was interrupted; the retry loop restores the interrupt flag and aborts with INVENTORY_VERSION_CONFLICT instead of continuing to spin on a dead thread.
- **Verification evidence:** inventory-service 64/64 green including ReservationServiceImplTest.
### F-016 - Orphaned PENDING orders with no order items can never be reconciled - they accumulate and alert forever

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java`
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java`
- **Actual implementation:** OrderCreateSaga.execute() Step 1 commits a PENDING order row in its own transaction; the order items are only persisted in Step 4. A crash between Step 1 and Step 4 leaves a PENDING order with zero order_items. OrderReconciliationScheduler.reconcile() polls the reservation states, finds an empty list, and calls routeToMixed(order, states, 'no applicable reservations') - no automatic action, ever. The sweep re-selects the same row on every tick (5 minutes) because it is still PENDING and older than the 30-minute cutoff.
- **Expected:** An order with no reservation-bearing items is either completed or cancelled automatically, or is explicitly excluded from the sweep.
- **Trigger:** Process crash, pod eviction, or unhandled error between the Step 1 commit and the Step 4 commit of the create saga.
- **Root cause:** The saga persists the order before its items, and the reconciler's decision table has no case for 'no applicable reservations'.
- **Consequences:** Permanent orphan order rows that grow without bound, a permanently firing reconciliation alert, and a cart that stays populated so the customer can retry into a second order while the first is orphaned.
- **Required remediation:** Either persist the order items in Step 1 alongside the order, or add a reconciler case that cancels (or garbage-collects) a PENDING order older than the cutoff that has no items and no promotion reservation.
- **Regression test:** Integration test: create a PENDING order with no items, run the reconciler, and assert the order reaches a terminal state instead of staying PENDING.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-019 - The payment-captured confirm gate and the cancel-refund trigger are both DISABLED by default - an order can be confirmed without payment and cancelled without refund

- **Priority / severity:** P0 / Critical (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** order-service, payment-service
- **Source files:**
  - `order-service/src/main/resources/application.yml`
  - `order-service/src/main/java/com/shop/orderservice/client/PaymentServiceClient.java`
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
  - `order-service/src/main/java/com/shop/orderservice/config/ShopServicesProperties.java`
- **Actual implementation:** order-service application.yml sets shop.services.payment.enabled: ${PAYMENT_SERVICE_ENABLED:false} with the comment 'payment-captured confirm gate, default off until payment-service ships'. ShopServicesProperties.Service.isEnabled() returns false for that value. OrderServiceImpl.confirmOrder() only calls paymentClient.findCapturedByOrderId() when paymentClient.isEnabled() is true, so with the default configuration the confirm path never verifies that a payment exists. PaymentServiceClient.refundByOrderId() returns true immediately when the client is disabled, so the admin-cancel path reports success without issuing a refund. payment-service IS shipped (Stripe, COD, VNPay, MoMo providers, webhooks, refunds), so the 'until payment-service ships' premise no longer holds.
- **Expected:** An order is CONFIRMED only when a CAPTURED payment exists; cancelling a CONFIRMED order always triggers a refund.
- **Trigger:** A deployment that does not set PAYMENT_SERVICE_ENABLED=true - which is the shipped default and the value in .env.example style configuration.
- **Root cause:** A feature flag introduced during an MVP phase was never flipped once the downstream shipped, and its default is the unsafe value.
- **Consequences:** Two money-critical invariants are silently unenforced in the default deployment: (1) an order reaches CONFIRMED with no captured payment - the customer receives goods that were never paid for; (2) an admin cancel of a CONFIRMED order marks it CANCELLED while the customer keeps their money. Neither is visible in logs or metrics.
- **Required remediation:** Default shop.services.payment.enabled to true, fail the application context at startup when it is false while payment-service is deployed, and make refundByOrderId report a distinct 'skipped' outcome instead of returning true.
- **Regression test:** Integration test: with the payment client enabled, confirmOrder must fail with ORD-4012 when no CAPTURED payment exists. A configuration test must assert the shipped default is not 'false'.
- **Remediation applied:** order-service: shop.services.payment.enabled now defaults to true (was false). A new PaymentGateValidator fails the application context at startup when the payment integration is disabled while order.payment-gate.require-captured-payment is true (default), so the dangerous combination can no longer be reached silently. PaymentServiceClient.refundByOrderId no longer reports success when the client is disabled.
- **Verification evidence:** order-service green; new integration test confirm_withoutCapturedPayment_failsClosedWithOrd4012 asserts the gate fails closed with ORD-4012 and the order stays PENDING.
### F-021 - A partial Stripe refund is recorded as a full refund: the webhook reads the charge amount, not the refunded amount

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** payment-service
- **Source files:**
  - `payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java`
  - `payment-service/src/main/java/com/shop/paymentservice/provider/StripeCurrencyUnits.java`
- **Actual implementation:** STRIPE_EVENT_STATUS maps charge.refunded to REFUNDED. toCarrierPayload() reads data.object.amount and converts minor -> major units, then process() compares it against payment.getAmount() and only accepts an exact match. For a Stripe charge.refunded event, data.object is the Charge whose 'amount' field is the ORIGINAL charge amount and whose 'amount_refunded' field carries the refunded total. A partial refund therefore passes the amount check and the payment row transitions CAPTURED -> REFUNDED.
- **Expected:** A partial refund must not mark the payment fully refunded; the refunded amount must be reconciled against the captured amount.
- **Trigger:** Any partial refund at Stripe (a common support scenario: shipping cost, partial return).
- **Root cause:** The webhook maps a Stripe event type to a single terminal fleet status without reading the refunded amount.
- **Consequences:** The local payment is marked REFUNDED although only part of the money came back. Order, revenue and refund reporting then over-state refunds, and no further refund can be issued because the state machine only allows CAPTURED -> REFUNDED.
- **Required remediation:** Read amount_refunded for charge.refunded, compare it against the captured amount, and only transition to REFUNDED on a full refund; introduce a PARTIALLY_REFUNDED status (or record the refunded amount) otherwise.
- **Regression test:** Unit test: a charge.refunded event with amount_refunded < charge amount must not transition the payment to REFUNDED.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-022 - Payment create idempotency replays without validating that the request payload matches

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** payment-service
- **Source files:**
  - `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java`
- **Actual implementation:** create() looks up repository.findByIdempotencyKeyAndUserId(idempotencyKey, userId) and returns the existing Payment via existing.orElseGet(...). It never compares orderId, amount or currency against the incoming request. order-service solves the same problem with a requestHash guard (IdempotencyServiceImpl.resolve).
- **Expected:** Reusing an idempotency key with a different payload must be rejected with a conflict, not silently return the previous payment.
- **Trigger:** A caller reuses an idempotency key across two different orders or amounts - for example a client-side retry bug or a multi-tenant key collision.
- **Root cause:** The idempotency lookup checks identity but not intent.
- **Consequences:** The caller believes it created a payment for order B and receives the payment object for order A. The order B payment never exists, so the later capture targets the wrong payment; the confirm gate for order B then fails closed and order B is auto-cancelled by the reconciler.
- **Required remediation:** Store a request hash on the payment row (or a separate idempotency table) and reject a key reuse whose payload differs, matching the order-service pattern.
- **Regression test:** Integration test: creating a payment with the same idempotency key but a different amount must fail with PAYMENT_DUPLICATE_REQUEST.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-023 - The confirm-path payment gate inspects only the first page of payments for an order

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/client/PaymentServiceClient.java`
- **Actual implementation:** findCapturedByOrderId() calls GET /api/v1/payments?orderId=... with no size parameter and then filters the returned page for a CAPTURED payment with findFirst(). The controller's default page size is 10 (PageableConstant.DEFAULT_PAGE_SIZE).
- **Expected:** The gate must evaluate all payments for the order, or the endpoint must expose a dedicated captured-payment lookup.
- **Trigger:** An order with more than ten payment records - for example a client that retries payment creation across several providers or a partially failed checkout.
- **Root cause:** A paged collection endpoint is used as a lookup.
- **Consequences:** The gate fails closed (ORD-4012) even though a captured payment exists beyond the first page, so the order can never be confirmed and is later auto-cancelled - a paid customer loses the order.
- **Required remediation:** Add a dedicated payment-service endpoint such as GET /api/v1/payments/by-order/{orderId}/captured, or pass an explicit large page size and page through the results.
- **Regression test:** Integration test: create 12 payments for one order where only the last is CAPTURED; the gate must find it.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-025 - Admin cancel of a SHIPPED order marks it CANCELLED with no refund and no restock, while the inline comment claims SHIPPED is terminal for everyone

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java`
- **Actual implementation:** OrderStatusServiceImpl.ALLOWED permits SHIPPED -> CANCELLED (H12). OrderServiceImpl.cancelOrder() only handles the PENDING branch (release reservations + release the promotion) and the CONFIRMED+admin branch (releaseCommitted + refund). A SHIPPED order falls through both branches: the status is set to CANCELLED and publishCancelled is emitted, with no refund, no stock movement and no return record.
- **Expected:** Either SHIPPED -> CANCELLED is rejected, or it is routed to the return/refund flow that the H12 comment intends for post-shipment cancellations.
- **Trigger:** An administrator cancels a shipped order (carrier issue, fraud chargeback, recall).
- **Root cause:** The state machine was widened for admin tooling but the cancel workflow was not widened with it.
- **Consequences:** The order reads CANCELLED to the customer while the goods are in transit and the customer keeps their money; no return or refund record is created, so the refund is never driven by the platform.
- **Required remediation:** Route the SHIPPED -> CANCELLED path into OrderReturnService (the return/refund flow that already exists), or remove the transition from the state machine.
- **Regression test:** Integration test: cancelling a SHIPPED order must either fail with ORDER_INVALID_STATE_TRANSITION or create a return record and trigger a refund.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-026 - Reservation release during compensation is best-effort and the failure is swallowed after the order row is deleted, orphaning the stock hold

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service, inventory-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java`
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
- **Actual implementation:** OrderCreateSaga.releaseAllReservations() catches every exception and only logs it. The saga then deletes the order row. OrderServiceImpl.releaseAllReservationsById() and releaseAllCommittedReservationsById() behave the same way. Once the order row is gone, no row references the reservation, so the order-service reconciler cannot find it; the only recovery is inventory-service's TTL sweep, which releases the hold after inventory.reservation-ttl-seconds (default 900).
- **Expected:** A compensation failure leaves a durable, discoverable record linking the orphaned reservation to the order.
- **Trigger:** inventory-service is unavailable or returns an error while the saga is compensating.
- **Root cause:** Compensation is fire-and-forget and the durable link (the order row) is destroyed immediately afterwards.
- **Consequences:** Stock stays unavailable for up to 15 minutes after a failed checkout, with no alert beyond a log line. Under a partial outage the effective sellable stock is materially lower than the ledger says.
- **Required remediation:** Record compensation failures (a failed_compensation table or an outbox row) before deleting the order, and have the reconciler retry them.
- **Regression test:** Integration test: force the inventory release to fail during saga compensation and assert a durable compensation record exists that the reconciler can retry.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-030 - Order tracking is unauthenticated and gated only by the order phone number

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
  - `order-service/src/main/resources/application.yml`
- **Actual implementation:** shop.security.public-paths includes '/api/v1/orders/track'. trackOrder(orderId, phoneNumber) loads the order and compares the phone number with equalsIgnoreCase, then returns the recipient name, shipping address, total, status and every line item.
- **Expected:** Order tracking should require an authenticated owner, or at minimum a non-guessable token in addition to the phone number.
- **Trigger:** An attacker with an order id and a guessable or leaked phone number calls the public endpoint.
- **Root cause:** A mutable, low-entropy attribute is used as the sole authorisation factor on an unauthenticated endpoint.
- **Consequences:** Disclosure of the recipient name, the full shipping address and the order value - personal data - to anyone who can guess the phone number or who has obtained it from a previous breach or a shared device.
- **Required remediation:** Require authentication for tracking, or add a per-order tracking token delivered at checkout, and keep only the phone number as a secondary confirmation factor.
- **Regression test:** Security test: the tracking endpoint must reject an unauthenticated request even with the correct phone number, unless an explicit tracking token is supplied.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-037 - tax-service soft-delete contradicts its own test - the module's build was RED, and the fleet had two different soft-delete attribution conventions

- **Priority / severity:** P2 / Medium (confidence: TEST_VERIFIED, evidence: TEST_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** tax-service, product-service, favourite-service, promotion-service
- **Source files:**
  - `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxClassServiceImpl.java`
  - `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxRateServiceImpl.java`
  - `tax-service/src/test/java/com/shop/taxservice/service/impls/TaxClassServiceImplTest.java`
- **Actual implementation:** TaxClassServiceImpl.delete used auditorAware.getCurrentAuditor().orElse("system"), so a destructive configuration change was attributed to a pseudo-actor. The module's own test TaxClassServiceImplTest.deleteRequiresAuditor asserted that the delete must fail when no auditor is resolvable - the test failed on every build ("Expecting code to raise a throwable"). The fleet had two conventions: promotion-service used orElseThrow(), while favourite-service, product-service (3 sites) and tax-service (2 sites) used orElse("system").
- **Expected:** One convention across the fleet: a destructive soft-delete must be attributable to a real actor, and the tax-service implementation must match its own test.
- **Trigger:** An administrative delete performed by a caller for which the AuditorAware cannot resolve a principal.
- **Root cause:** Two incompatible conventions coexisted and the implementation drifted away from the test that specifies the contract.
- **Consequences:** The build was red on every run - a release blocker in itself. Beyond that, a destructive tax-configuration delete could be attributed to 'system' instead of the administrator who performed it, degrading the audit trail.
- **Required remediation:** Align tax-service with the strict convention (orElseThrow) that promotion-service and the tax test already encode. The fleet-wide convention for the remaining .orElse("system") sites is tracked as a follow-up task.
- **Regression test:** TaxClassServiceImplTest.deleteRequiresAuditor (existing, now green) plus TaxRateServiceImplTest.deleteIsSoftAndUnguarded.
- **Remediation applied:** TaxClassServiceImpl.delete and TaxRateServiceImpl.delete now use orElseThrow().
- **Verification evidence:** tax-service 86/86 green; the previously failing TaxClassServiceImplTest.deleteRequiresAuditor now passes unchanged.
### F-039 - The JVM default timezone is sent as the PostgreSQL 'TimeZone' startup parameter - on a host whose zone is 'Asia/Saigon' every database connection is refused

- **Priority / severity:** P0 / Critical (confidence: RUNTIME_MEASURED, evidence: RUNTIME_MEASURED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** utils/common-kafka, utils/common-core, utils/common-spring, utils/common-security, utils/common-storage, all services
- **Source files:**
  - `pom.xml`
  - `utils/common-spring/src/main/resources/application.yml`
  - `production-audit/tests/contracts/TzProof.java`
- **Actual implementation:** The PostgreSQL JDBC driver (42.7.11) adds a startup parameter TimeZone = TimeZone.getDefault().getID() on every connection (ConnectionFactoryImpl.getParametersForStartup / createPostgresTimeZone). PostgreSQL validates that parameter against pg_timezone_names, which does NOT contain backward-compatibility links such as 'Asia/Saigon' - the connection is refused with FATAL: invalid value for parameter "TimeZone": "Asia/Saigon". No service, compose file or k8s manifest pins TZ, so the JVM inherits the host timezone. Measured against postgres:16 with the production driver: default (Asia/Saigon) -> FAILED; -Duser.timezone=UTC -> CONNECTED (server TimeZone = UTC); -Duser.timezone=Asia/Ho_Chi_Minh -> CONNECTED; -Duser.timezone=Asia/Saigon -> FAILED. The same failure produced 416 errors in the Testcontainers integration suites.
- **Expected:** The platform connects to PostgreSQL regardless of the host timezone, and the build is reproducible on any developer machine.
- **Trigger:** Any deployment whose JVM resolves the default timezone to a backward-compatibility zone id. The project's own developer machines resolve to Asia/Saigon.
- **Root cause:** The JVM timezone is unpinned, and the value is sent to the server as a startup parameter that PostgreSQL validates strictly.
- **Consequences:** Total outage of all twelve database-backed services on such a host: no connection can be established, so the application context never starts. The entire Testcontainers suite fails, so the defect is invisible in CI (ubuntu-latest runs UTC) but blocks every developer on a non-UTC machine.
- **Required remediation:** Pin user.timezone=UTC for the surefire/failsafe forks and for the Jib-produced images; document the requirement in the deployment runbook. Implemented in this audit.
- **Regression test:** The proof program production-audit/tests/contracts/TzProof.java connects to a real postgres:16 container with and without -Duser.timezone and asserts the outcome.
- **Remediation applied:** pom.xml: surefire + failsafe systemPropertyVariables user.timezone=UTC; Jib jvmFlags -Duser.timezone=UTC.
- **Verification evidence:** Runtime proof (Asia/Saigon -> FAILED, UTC -> CONNECTED, Asia/Ho_Chi_Minh -> CONNECTED) plus the full reactor green: 416 timezone errors -> 0.
### F-044 - coupon_usage_reservation has no version column: the TTL sweep can revert a COMMITTED redemption to EXPIRED, which un-counts it from the per-user limit and the campaign budget

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** promotion-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/entity/CouponUsageReservation.java`
  - `promotion-service/src/main/java/com/shop/promotionservice/service/ReservationCleanupScheduler.java`
  - `promotion-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`
- **Actual implementation:** CouponUsageReservation had no @Version and the table had no version column. ReservationCleanupScheduler.releaseAllExpiredReservations() selected PENDING rows with an unlocked SELECT and then dirty-check-flipped them to EXPIRED (batch.forEach(r -> r.setStatus(UsageStatus.EXPIRED))). A sweep that read the row before commit() ran could therefore overwrite the COMMITTED state. This is the same lost-update class as F-011 in inventory-service. The consequence is money-relevant: CampaignReservationServiceImpl counts PENDING + COMMITTED for both the per-user limit (countByCampaignIdAndUserIdAndStatusIn) and the campaign budget (countByCampaignIdAndStatusIn / sumDiscountByCampaignIdAndStatusIn), so a silently reverted redemption stops being counted - the customer can redeem the same coupon again and the campaign budget is under-spent.
- **Expected:** A committed redemption is never reverted, and the limit/budget counters only ever decrease through an explicit release.
- **Trigger:** A commit() that runs just before the reservation TTL elapses, racing with the 60-second sweep.
- **Root cause:** No optimistic-locking or conditional-update discipline on the coupon usage reservation aggregate, and the sweep performed a blind read-modify-write.
- **Consequences:** Double redemption of the same coupon and campaign budget overspend; no error is raised, the counters simply drift.
- **Required remediation:** New Liquibase changeset changelog-004 adds coupon_usage_reservation.version; the entity gains @Version; the sweep now CLAIMS each row with a conditional UPDATE (status = PENDING -> EXPIRED, released_at = now, version + 1) so exactly one writer can transition it.
- **Regression test:** promotion-service: ReservationCleanupSchedulerTest.sweep_skipsRowsAlreadyClaimedByAnotherWriter asserts that a claim which affects zero rows leaves the inventory untouched and emits no event.
- **Remediation applied:** changelog-004-coupon-reservation-version.yaml; CouponUsageReservation.@Version; ReservationCleanupScheduler claims rows via reservationRepository.claimForExpiry.
- **Verification evidence:** promotion-service 122/122 green against real PostgreSQL with the new version column; sweep_skipsRowsAlreadyClaimedByAnotherWriter asserts the lost-update regression.
### F-046 - ReservationRetryServiceImpl swallowed the interrupt flag and kept retrying - the same defect as F-014, in a second service

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** promotion-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/ReservationRetryServiceImpl.java`
- **Actual implementation:** withRetry() caught OptimisticLockingFailureException and called sleep(); sleep() restored the interrupt flag and returned, and the loop continued to the next attempt. Every remaining attempt then failed instantly (the interrupt flag makes Thread.sleep throw immediately) and the caller received a spurious PROMOTION_RESERVATION_VERSION_CONFLICT. This is the identical defect to F-014, which was remediated in inventory-service but not here.
- **Expected:** An interrupted request thread aborts the retry loop and propagates the cancellation.
- **Trigger:** The client disconnects and the container interrupts the request thread while a version conflict is being retried.
- **Root cause:** The interrupt flag was restored but never acted upon; the loop had no cancellation check.
- **Consequences:** A busy retry loop on a dead thread; the caller sees a version-conflict error instead of a cancellation, and the work continues after the client is gone.
- **Required remediation:** sleep() now returns false when interrupted; the loop restores the flag and aborts with the domain error instead of spinning.
- **Regression test:** promotion-service module suite green; the same shape as the inventory remediation (F-014).
- **Remediation applied:** ReservationRetryServiceImpl.sleep returns a boolean and withRetry aborts on interruption.
- **Verification evidence:** promotion-service green including ReservationRetryServiceImpl tests.
### F-047 - A concurrent duplicate coupon reserve surfaced a raw DataIntegrityViolationException (HTTP 500) instead of the domain's PROMOTION_DUPLICATE_REQUEST

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** promotion-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java`
- **Actual implementation:** reserve() looks up an existing reservation by order id and, finding none, inserts a new one. The schema enforces one reservation per order with the UNIQUE index uk_cur_order_id, so a concurrent second reserve for the same order is rejected by the database - but the insert was not wrapped, so the constraint violation escaped as an unhandled DataIntegrityViolationException (HTTP 500) rather than the catalogue's PROMOTION_DUPLICATE_REQUEST.
- **Expected:** A duplicate reservation attempt returns the catalogue's conflict code, exactly like the pre-check path does.
- **Trigger:** Two concurrent reserve calls for the same order id (a client double-submit, or a saga retry racing the original).
- **Root cause:** The database constraint that makes the check race-free was not mapped to a domain error.
- **Consequences:** A 500 instead of a 409 for a normal business conflict; the error also loses the order id context that PROMOTION_DUPLICATE_REQUEST carries.
- **Required remediation:** Catch DataIntegrityViolationException around the insert and map it to PROMOTION_DUPLICATE_REQUEST with the order id.
- **Regression test:** promotion-service module suite green; the mapping is exercised by the existing duplicate-request test plus the concurrency suite.
- **Remediation applied:** CampaignReservationServiceImpl.reserve maps the unique-constraint violation to ErrorCode.PROMOTION_DUPLICATE_REQUEST.
- **Verification evidence:** promotion-service green; the unique-constraint path is mapped to PROMOTION_DUPLICATE_REQUEST.
### F-048 - Campaign update silently collapses every non-ACTIVE requested status to INACTIVE

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** promotion-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignServiceImpl.java`
- **Actual implementation:** CampaignServiceImpl.update() calls applyStatus(campaign, request.status()), and applyStatus only distinguishes ACTIVE from everything else: a null status deactivates the campaign, and any other CampaignStatus value would also deactivate it rather than being applied.
- **Expected:** The requested status is applied, or an unsupported transition is rejected explicitly.
- **Trigger:** An operator sends a campaign update without a status field (the field is optional on the request).
- **Root cause:** applyStatus models only the binary active/inactive flag instead of the CampaignStatus enum.
- **Consequences:** A PATCH-style update that omits the status silently deactivates a live campaign, which stops every subsequent reserve from succeeding (CAMPAIGN_NOT_ACTIVE) without any explicit signal.
- **Required remediation:** Only apply the status when the request actually carries one; otherwise leave the current status unchanged.
- **Regression test:** Unit test: an update request without a status must keep the campaign active.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-049 - CampaignServiceImpl.delete uses the strict orElseThrow() attribution while the rest of the fleet uses orElse("system")

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** promotion-service, tax-service, product-service, favourite-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignServiceImpl.java`
  - `tax-service/src/main/java/com/shop/taxservice/service/impls/TaxClassServiceImpl.java`
  - `product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java`
  - `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java`
- **Actual implementation:** The fleet has two soft-delete attribution conventions. promotion-service and (after the F-037 remediation) tax-service use auditorAware.getCurrentAuditor().orElseThrow(), which throws a raw NoSuchElementException (HTTP 500) when no actor can be resolved. favourite-service and product-service (3 sites) still use orElse("system"), which silently attributes a destructive administrative change to a pseudo-actor and degrades the audit trail.
- **Expected:** One convention across the fleet, and a domain error (not a raw NoSuchElementException) when the actor cannot be resolved.
- **Trigger:** A destructive soft-delete performed by a caller for which the AuditorAware cannot resolve a principal (a service-to-service call, or an expired security context).
- **Root cause:** Two conventions coexisted; the remediation for F-037 aligned tax-service with promotion-service but did not converge the whole fleet.
- **Consequences:** Inconsistent audit attribution for destructive admin operations, and a raw 500 (instead of a clean domain error) where the strict convention is used.
- **Required remediation:** Add a shared helper in common-core (or common-logging) that requires a resolved actor and raises a BusinessException with an explicit code; use it at every soft-delete site so the fleet has one contract.
- **Regression test:** Unit test per service asserting the delete fails with the domain error when no auditor is resolvable.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-050 - Registration uniqueness check is a TOCTOU: two concurrent sign-ups with the same username create two Keycloak users and surface a raw constraint violation instead of a conflict

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
- **Actual implementation:** register() calls validateUniqueConstraints() (existsByUsername / existsByEmail / existsByPhone), then creates the Keycloak user, then inserts the local row. The checks are not atomic with the insert, so two concurrent registrations with the same username both pass the check and both create a Keycloak user. The local insert then fails on the unique index and the catch rolls the Keycloak user back - but the caller receives the raw DataIntegrityViolationException (HTTP 500) instead of the catalogue's AUTH_USERNAME_EXISTS conflict.
- **Expected:** A duplicate registration returns AUTH_USERNAME_EXISTS, and at most one Keycloak user is created.
- **Trigger:** A double-clicked sign-up form, or two browser tabs submitting the same registration.
- **Root cause:** The uniqueness pre-check is not backed by a constraint-violation mapping, unlike the equivalent fix applied to the promotion reservation (F-047).
- **Consequences:** HTTP 500 instead of 409 for a normal business conflict; the compensating Keycloak delete is a best-effort call that can itself fail, leaving an orphan Keycloak user with the same username.
- **Required remediation:** Map the unique-constraint violation on the local insert to the catalogue conflict codes, exactly as F-047 does for the coupon reservation.
- **Regression test:** Integration test: two concurrent registrations with the same username must yield one success and one AUTH_USERNAME_EXISTS, and exactly one Keycloak user must survive.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-051 - A password change did not revoke the sessions established with the old password

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** auth-service, utils/common-keycloak
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
  - `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java`
- **Actual implementation:** changePassword() called keycloakAdminClient.resetUserPassword(...) and returned. Keycloak's admin reset-password endpoint changes the credential but does NOT end the user's existing sessions, so every refresh token issued before the change stayed valid. The same applied to resetPassword(). KeycloakAdminClient already had the /logout admin call for disableUser(), but the credential-change paths never used it.
- **Expected:** A credential change revokes the sessions established with the old credential, so a captured refresh token cannot outlive the password it was issued against.
- **Trigger:** A password change or reset - most importantly a reset performed because the password may have been compromised.
- **Root cause:** The admin client's reset-password flow was implemented without the accompanying session revocation.
- **Consequences:** An attacker holding a stolen refresh token keeps full access to the account even after the owner changes the password or resets it after a breach. This is the standard session-invalidation-on-credential-change control (OWASP ASVS V2 session management).
- **Required remediation:** Add KeycloakAdminClient.logoutUser(userId) (POST /admin/realms/{realm}/users/{id}/logout) and call it after every credential change. The revocation is best-effort: the credential change has already been applied, so a revocation failure is logged at ERROR rather than reported as a failed password change.
- **Regression test:** auth-service: changePasswordVerifiesOldPasswordThenResets asserts logoutUser is called; changePasswordSucceedsEvenWhenSessionRevocationFails asserts a revocation failure does not turn the change into an error.
- **Remediation applied:** KeycloakAdminClient.logoutUser added; UserServiceImpl.changePassword and resetPassword both call revokeSessions(...) after the credential change.
- **Verification evidence:** auth-service green; changePasswordVerifiesOldPasswordThenResets asserts logoutUser is called and changePasswordSucceedsEvenWhenSessionRevocationFails asserts a revocation failure does not fail the change.
### F-052 - A password reset did not revoke the sessions established with the compromised password

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
- **Actual implementation:** resetPassword() called keycloakAdminClient.resetUserPassword(...) and marked the reset token used, without revoking the user's existing Keycloak sessions. A reset is the operation an account owner performs precisely because the password may have leaked, so the surviving sessions are the exact sessions that must die.
- **Expected:** Resetting a password revokes every session and refresh token issued under the old password.
- **Trigger:** A password reset following a suspected compromise.
- **Root cause:** Same root cause as F-051 - the credential-change path omitted the session revocation.
- **Consequences:** The compromised session survives the remediation the owner performed to end it.
- **Required remediation:** Covered by the same revokeSessions(...) call added for F-051.
- **Regression test:** auth-service: the resetPassword test asserts logoutUser is called for the reset user.
- **Remediation applied:** UserServiceImpl.resetPassword calls revokeSessions(user.getKeycloakUserId()) after the credential change.
- **Verification evidence:** auth-service green; resetPassword asserts the same revocation.
### F-053 - The password reset token has no version column: a reset token can be redeemed twice concurrently

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/entity/PasswordResetToken.java`
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
- **Actual implementation:** PasswordResetToken has a 'used' flag but no @Version. resetPassword() loads the token with findByTokenHashAndUsedFalse, changes the Keycloak password, then sets used=true and saves. Two concurrent redemptions of the same token both read used=false and both change the password; the last writer wins and the first password is silently replaced.
- **Expected:** A reset token is single-use even under concurrent redemption.
- **Trigger:** A reset link is opened twice, or an attacker races the legitimate owner.
- **Root cause:** The consume step is a read-modify-write without a version guard or a conditional UPDATE.
- **Consequences:** The token can be used twice; the second use overwrites the password the owner just set, so the owner's chosen password is lost without any error.
- **Required remediation:** Consume the token with a conditional UPDATE (UPDATE ... SET used = true WHERE token_hash = ? AND used = false) and only proceed when it affected one row; add a version column for the same reason as F-011 / F-044.
- **Regression test:** Concurrency test: two concurrent redemptions of the same token must produce exactly one password change and one rejection.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-054 - The password reset email is never sent - the only PasswordResetEmailSender implementation logs instead of dispatching, so the reset flow cannot complete

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/DefaultPasswordResetEmailSender.java`
  - `auth-service/src/main/java/com/shop/authservice/service/PasswordResetEmailSender.java`
- **Actual implementation:** PasswordResetEmailSender is a port with exactly one implementation, DefaultPasswordResetEmailSender, whose sendResetEmail() body is a log.info line. forgotPassword() persists a reset token, calls the sender and reports success - so the API answers 'a password reset instruction has been sent' while no message ever leaves the process. The raw token is correctly NOT logged, so there is no leak; the flow is simply non-functional.
- **Expected:** The reset token reaches the user over the notification channel (notification-service already has an SMTP sender).
- **Trigger:** Any forgot-password request in a real deployment.
- **Root cause:** The seam was created but no SMTP-backed implementation was wired.
- **Consequences:** Users cannot recover a forgotten password; the endpoint reports success, so the failure is invisible to operators and to the caller. Support load is the only signal.
- **Required remediation:** Implement the sender on top of notification-service's SMTP channel (or a JavaMailSender), and fail the endpoint when the dispatch fails so the caller is not told a message was sent when it was not.
- **Regression test:** Integration test: forgot-password must invoke a sender that records the delivery, and the reset link must work end to end.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-055 - A self-registration naming only privileged roles created an account with ZERO realm roles - the user could sign in but every authorization rule failed

- **Priority / severity:** P1 / High (confidence: TEST_VERIFIED, evidence: TEST_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
  - `auth-service/src/test/java/com/shop/authservice/service/impls/UserServiceImplTest.java`
- **Actual implementation:** extractRoles() returned the USER fallback only when the request omitted the roles field entirely. When the request named a non-allowed role ({"ADMIN"}, {"ADMIN","SERVICE","MANAGER"}, ...) the whitelist filter removed every entry and the resulting EMPTY list was used for BOTH the Keycloak role assignment (createUser) and the local user_role rows. The pre-existing test registerAllDisallowedRolesResultsInEmptyList ASSERTED this behaviour, so the defect was locked in by its own specification.
- **Expected:** The whitelist exists to stop a self-registration obtaining a privileged role - not to strip the customer of their base role. A registration must always end up with at least USER.
- **Trigger:** A client that sends a roles field containing only privileged names (the field is deprecated but still accepted and bound).
- **Root cause:** The fallback was applied to the pre-filter list instead of the post-filter list.
- **Consequences:** The account is created in Keycloak with no realm role and in the local database with no role row. The customer can authenticate, but every @PreAuthorize rule fails, and every @PreAuthorize-based endpoint returns 403 for an account that the registration flow reported as created. Recovering it requires an operator.
- **Required remediation:** Apply the USER fallback to the POST-filter list, so an empty filtered result falls back to USER. The test was updated to assert the corrected behaviour (and renamed accordingly).
- **Regression test:** auth-service: registerAllDisallowedRolesFallsBackToUser asserts Keycloak receives ["USER"] and the local DB receives the USER role row, and that the empty-role call never happens.
- **Remediation applied:** UserServiceImpl.extractRoles now falls back to USER when the whitelist filter empties the list.
- **Verification evidence:** auth-service green; registerAllDisallowedRolesFallsBackToUser asserts Keycloak receives ["USER"] and the local DB receives the USER role row.
### F-060 - product-service soft-deletes a product without checking for active inventory reservations, while inventory-service refuses to delete an in-use inventory row

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** product-service, inventory-service
- **Source files:**
  - `product-service/src/main/java/com/shop/productservice/service/impls/ProductServiceImpl.java`
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java`
- **Actual implementation:** InventoryServiceImpl.delete() refuses to delete an inventory row that has PENDING or COMMITTED reservations (ErrorCode.INVENTORY_IN_USE). The symmetric guard does not exist on the other side of the relationship: ProductServiceImpl.delete() soft-deletes the product unconditionally, so a product can be removed from the catalogue while stock for it is reserved or committed. The inventory row survives with a reference to a soft-deleted product.
- **Expected:** A product with live reservations cannot be removed from the catalogue, or the inventory row is released/compensated as part of the deletion.
- **Trigger:** An administrator deletes a product that a customer is currently checking out.
- **Root cause:** The guard lives only on the inventory side, and inventory-service has no visibility of the product catalogue - the check would have to be an HTTP call, which the product-service does not make.
- **Consequences:** An orphaned inventory row for a deleted product; the checkout can still commit a reservation for a product that no longer exists; the reserved stock is never returned to the sellable pool.
- **Required remediation:** Add a reference check to the product delete path (the same internal client pattern media-service already uses via MediaReferenceChecker / ProductMediaReferenceChecker), and make the check fail-safe: treat an unavailable reference service as 'referenced' so a delete is not performed on incomplete data.
- **Regression test:** Integration test: deleting a product that has an active reservation must be rejected; deleting one with none must succeed.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-061 - product-service soft-deletes a variant with a hard-coded 'system' actor and without a stock check

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** product-service
- **Source files:**
  - `product-service/src/main/java/com/shop/productservice/service/impls/ProductVariantServiceImpl.java`
- **Actual implementation:** ProductVariantServiceImpl.delete() calls variant.markDeleted("system") - the actor is a literal, not the resolved principal, so a destructive administrative change is attributed to a pseudo-actor regardless of who performed it. It also performs no stock check, unlike the product delete path's sibling in inventory-service.
- **Expected:** The resolved actor is recorded, and a variant with live stock is not silently removed.
- **Trigger:** An administrator deletes a product variant.
- **Root cause:** The variant delete path was written separately from the product/brand/category delete paths and hard-codes the actor.
- **Consequences:** The audit trail cannot attribute a variant deletion. Combined with F-049 (two conventions across the fleet) the soft-delete attribution is inconsistent in three different ways.
- **Required remediation:** Resolve the actor through the same AuditorAware-based helper the fleet should share (see F-049), and add a stock-reference check.
- **Regression test:** Unit test: the deleted variant records the authenticated actor.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-063 - Deleting a media asset does not check whether a live product still references it

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** media-service, product-service
- **Source files:**
  - `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaLifecycleServiceImpl.java`
  - `media-service/src/main/java/com/shop/mediaservice/service/impls/ProductMediaReferenceChecker.java`
- **Actual implementation:** media-service already has the reference checker (ProductMediaReferenceChecker) and it is deliberately fail-safe - an unavailable reference service is treated as REFERENCED so the purge job skips the media. The DELETE endpoint path does NOT use it: MediaLifecycleServiceImpl.softDelete() soft-deletes unconditionally, so an administrator can remove a media asset that a live product still points at, leaving the product with a dangling image reference.
- **Expected:** Deleting a referenced media asset is refused (or the references are cleared first), with the same fail-safe posture the purge job uses.
- **Trigger:** An administrator deletes a media asset used by a live product.
- **Root cause:** The reference check was wired into the background purge path but not into the interactive delete path.
- **Consequences:** A live product loses its image; the reference count and the catalogue disagree.
- **Required remediation:** Apply the existing reference checker in the delete path, reusing the fail-safe semantics already implemented.
- **Regression test:** Integration test: deleting a referenced media asset must be refused; deleting an unreferenced one must succeed.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-065 - Hiding the only visible rating of a product crashed the moderation transaction - the administrator could not remove the review

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** rating-service
- **Source files:**
  - `rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java`
- **Actual implementation:** RatingEventService.record() reads the aggregate with findAggregateByProductId, whose JPQL averages over hidden = false AND deleted = false. When the last visible rating of a product is hidden, PostgreSQL returns a NULL average. toBigDecimal() called String.valueOf(raw), which for a null Object returns the literal string "null" (not null), and new BigDecimal("null") throws NumberFormatException. The exception propagated out of record(), inside the hide transaction, so the whole hide rolled back and the API answered 500. A moderator therefore could not remove the only review of a product - exactly the situation moderation exists for, and a dead end that leaves an abusive review visible.
- **Expected:** Hiding the last visible rating succeeds and reports an average of zero.
- **Trigger:** An administrator hides a rating when it is the only visible rating for its product.
- **Root cause:** A NULL aggregate value was passed to a numeric parser that handled the 'not a BigDecimal' case but not the 'null' case.
- **Consequences:** The moderation endpoint fails with a 500 for a legitimate and important operation; the abusive review stays visible and the operator has no other route.
- **Required remediation:** toBigDecimal() now maps a null aggregate to BigDecimal.ZERO.
- **Regression test:** rating-service: RatingEventServiceTest.record_withNullAggregateAverage_writesZeroAverageInsteadOfFailing asserts the event is written with avgRating 0.00 and ratingCount 0.
- **Remediation applied:** RatingEventService.toBigDecimal maps null to BigDecimal.ZERO.setScale(2).
- **Verification evidence:** rating-service green; RatingEventServiceTest.record_withNullAggregateAverage_writesZeroAverageInsteadOfFailing asserts the event is written with avgRating 0.00 and ratingCount 0.
### F-067 - The GHN and GHTK carrier adapters never contact the carrier - they fabricate a deterministic tracking number from the order id and leave the API token unused

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** shipping-service
- **Source files:**
  - `shipping-service/src/main/java/com/shop/shippingservice/carrier/GhnCarrierAdapter.java`
  - `shipping-service/src/main/java/com/shop/shippingservice/carrier/GhtkCarrierAdapter.java`
- **Actual implementation:** GhnCarrierAdapter holds an apiToken and a shopId and never uses either. createShipment() derives the tracking number locally: "GHN" + orderId.toString().replace("-","").substring(0,10).toUpperCase() (GHTK uses 9 characters and a dot separator). No HTTP call is made to the carrier. The ManualCarrierAdapter returns a null tracking number and the NoopCarrierAdapter returns "NOOP-<orderId>" and starts at PICKED_UP.
- **Expected:** Selecting a real carrier must create a real shipment with the carrier and record the carrier-issued tracking number.
- **Trigger:** shop.shipping.carrier=ghn or =ghtk.
- **Root cause:** The carrier integrations were stubbed with a local derivation and the credential fields were left in place, so the configuration looks live.
- **Consequences:** No parcel ever reaches the carrier, so the customer receives a tracking number that the carrier does not know and that the carrier webhook can never confirm. The tracking number is also derived from the order id, so it is predictable, and two order ids sharing a 10-hex-character prefix would collide on the (unique) tracking-number column. The reconciliation scheduler then auto-delivers these phantom shipments after seven days and publishes shipping.delivered.v1, so the order is marked DELIVERED for a parcel that was never shipped.
- **Required remediation:** Implement the real carrier API calls (create-shipment) and store the carrier-issued tracking number; until then make the adapters fail fast rather than fabricate a tracking number, and label them clearly as simulators.
- **Regression test:** Contract test with a stubbed carrier endpoint asserting that the adapter calls the carrier and stores the returned tracking number; a unit test asserting the simulator adapters cannot be selected in a production profile.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-068 - A shipment that the carrier never confirmed is auto-delivered after seven days and drives the order to DELIVERED

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** shipping-service, order-service
- **Source files:**
  - `shipping-service/src/main/java/com/shop/shippingservice/scheduler/ReconciliationScheduler.java`
  - `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisherImpl.java`
- **Actual implementation:** ReconciliationScheduler runs hourly and moves any shipment whose status is in-flight (PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY) and whose last carrier update is older than shop.shipping.auto-deliver-days (default 7) to DELIVERED with autoDelivered=true, publishing shipping.delivered.v1. The order-service consumes that event and marks the order DELIVERED. The rule does not distinguish a shipment the carrier confirmed from one that was never handed over (see F-067), and the carrier-seen timestamp is only written when a carrier webhook arrives - so a shipment that never reached the carrier is auto-delivered exactly like one whose tracking stalled.
- **Expected:** The auto-delivery rule should apply to shipments the carrier has acknowledged and then stopped updating, not to shipments that were never registered with the carrier.
- **Trigger:** A shipment stays in flight for seven days without a carrier update.
- **Root cause:** The reconciliation rule uses only the last carrier update timestamp, with no evidence that the carrier ever accepted the parcel.
- **Consequences:** Orders reach DELIVERED without a delivery; the customer is told the parcel arrived. With F-067 (no real carrier call) this is the normal outcome for every GHN/GHTK shipment.
- **Required remediation:** Record a carrier-acknowledged flag/timestamp when the carrier confirms the shipment, and restrict the auto-delivery rule to shipments the carrier has acknowledged.
- **Regression test:** Integration test: a shipment never acknowledged by the carrier must not be auto-delivered.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
