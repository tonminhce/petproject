# Review Report — order-service + payment-service + inventory-service

> Independent review (no reference to docs/review/*). Reads **every** Java
> source under src/main/java and src/test/java of the three modules, plus
> the relevant Liquibase changelogs and the service-local application.yml.

## Files Reviewed

### order-service — 55 main + 23 test files (~5.8 K LOC main, ~4.5 K LOC test)

| Area | Files |
|---|---|
| Bootstrap | OrderServiceApplication.java |
| Controllers | OrderController, CartController, OrderStatusController |
| Service interface | OrderService, CartService, OrderStatusService, PricingService, OrderCommitCoordinator, OrderEventPublisher, StockReservationService, IdempotencyService, OrderConfirmMetrics, OrderMetrics, OrderReconciliationScheduler, ShippingDeliveredHandler, CommitOutcome, CompensationTarget |
| Service impls | OrderServiceImpl, CartServiceImpl, OrderStatusServiceImpl, PricingServiceImpl, StockReservationServiceImpl, IdempotencyServiceImpl, OrderOutboxRelay, OrderEventPublisherImpl, IdempotencyKeyCleanupScheduler, OutboxRetentionScheduler |
| Clients | InventoryServiceClient, PaymentServiceClient, ProductServiceClient, PromotionServiceClient, TaxServiceClient |
| Config/security | ShopServicesProperties, RestClientConfig, CacheConfig, ServiceTokenProvider |
| Entities/repos | Order, OrderItem, Cart, CartItem, IdempotencyKey, OutboxEvent + repos |
| Kafka | ShippingDeliveredConsumer, ShippingListenerConfig, ShippingDeliveredEvent |
| Mappers/DTOs | OrderMapper, CartMapper + 10 request/response/internal DTOs |
| Tests | OrderServiceImplTest, CartServiceImplTest, IdempotencyServiceImplTest, OrderStatusServiceImplTest, PricingServiceImplTest, OrderControllerTest, CartControllerTest, OrderStatusControllerAuditTest, ConfirmOrchestrationWebMvcTest, VerifyPurchaseEndpointTest, ShippingDeliveredConsumerTest, ShippingDeliveredHandlerTest, OrderConfirmMetricsTest, OrderMetricsTest, OrderReconciliationSchedulerTest, CacheSerializerRoundTripTest, OrderRepositoryTest, OrderItemRepositoryTest, CartRepositoryTest, PaymentServiceClientTest, TestLiquibaseConfig, AbstractOrderServiceIT, OrderCreationSagaIntegrationTest, ConfirmOrchestrationIT |

### payment-service — 22 main + 14 test files (~2.0 K LOC main, ~1.8 K LOC test)

| Area | Files |
|---|---|
| Controllers/webhook | PaymentController, BackofficePaymentController, PaymentWebhookController |
| Service | PaymentService, PaymentStateMachine, PaymentWriter, ReceiptService, WebhookEventService + PaymentServiceImpl |
| Providers | PaymentProvider, MockProvider, StripeProvider, PaymentProviderConfig |
| Outbox | OutboxEvent, OutboxEventRepository, PaymentEventPublisher, PaymentOutboxRelay |
| Webhook | WebhookPayload, WebhookSignatureVerifier |
| Scheduler | WebhookRetryScheduler |
| Entities/repos | Payment, PaymentEvent, PaymentStatus, repos |
| DTOs | CreatePaymentRequest, PaymentResponse |
| Tests | WebhookSignatureVerifierTest, PaymentWebhookControllerTest, WebhookEventServiceTest, PaymentStateMachineTest, PaymentWriterTest, ReceiptServiceTest, PaymentServiceImplTest, WebhookRetrySchedulerTest, MockProviderTest, PaymentProviderConfigTest, PaymentControllerTest, BackofficePaymentControllerTest, PaymentControllerAuditTest, PaymentFlowIT, PaymentBootstrapIT, AbstractIntegrationTest, TestLiquibaseConfig |

### inventory-service — 18 main + 11 test files (~1.9 K LOC main, ~1.5 K LOC test)

| Area | Files |
|---|---|
| Controllers | InventoryController |
| Service | InventoryService, ReservationService, InventoryCacheService, InventoryEventPublisher, InventoryMetrics, InventoryOutboxRelay, OutboxRetentionScheduler, ReservationCleanupScheduler |
| Service impls | InventoryServiceImpl, ReservationServiceImpl, TransactionalInventoryEventPublisher |
| Config | CacheConfig |
| Entities/repos | Inventory, Reservation, OutboxEvent, ReservationStatus, repos |
| DTOs/mapper | InventoryUpsertRequest, ReserveRequest, InventoryResponse, ReservationResponse, InventoryMapper |
| Tests | InventoryControllerTest, InventoryControllerAuditTest, ReservationStateEndpointTest, InventoryLifecycleIdempotencyTest, ReleaseCommittedTest, InventoryOutboxRelayIntegrationTest, ReservationCleanupSchedulerTest, InventoryServiceImplTest, ReservationServiceImplTest, InventoryRepositoryTest, AbstractIntegrationTest, TestLiquibaseConfig |

---

## Critical Findings

### C1 — Production webhook secret defaults to empty string → every webhook from outside the prod cluster is rejected
**File:** payment-service/src/main/resources/application.yml:47

```yaml
payment:
  webhook:
    secret: ${PAYMENT_WEBHOOK_SECRET:}      # default = empty string!
```

WebhookSignatureVerifier.verify() at line 19–23 returns false when the secret is blank. So **every legitimate webhook delivery** in any deployment that forgot to set PAYMENT_WEBHOOK_SECRET returns 401, the state machine never advances, and payments stay PENDING forever. There is no fail-fast at boot for this case.

Additionally the empty-default is a foot-gun: an ops engineer who leaves it blank thinks they've disabled the webhook, but the empty secret matches HMAC-SHA-256("") which is publicly computable — an attacker could spoof events with that signature header.

**Fix:** default to a non-empty placeholder, fail at startup if it equals the placeholder; or refuse to boot when webhook.secret is blank in any profile where webhooks are enabled.

---

### C2 — StripeProvider is unimplemented; production defaults to it but throws UnsupportedOperationException
**Files:**
- payment-service/src/main/resources/application.yml:42 — provider default = stripe
- payment-service/src/main/java/com/shop/paymentservice/provider/StripeProvider.java:34–41

```java
public ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey) {
    throw new UnsupportedOperationException("Stripe capture is not implemented yet");
}
public ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey) {
    throw new UnsupportedOperationException("Stripe refund is not implemented yet");
}
```

PaymentServiceImpl.capture() (line 50) and .refund() (line 61) **always** throw at runtime in any environment that hasn't explicitly set PAYMENT_PROVIDER=mock. The whole synchronous POST /payments/{id}/capture and /refund API surface is non-functional in prod. Either ship a Stripe integration (using stripe-java) or change the default to mock until you do — and gate production deploys on a smoke test that actually captures a small amount.

---

### C3 — DB CHECK constraint ck_payment_events_status does not include FAILED_RETRYABLE / FAILED_PERMANENT → state column violates constraint on insert
**Files:**
- payment-service/src/main/resources/db/changelog/changelog-001-payments.yaml:70 — CHECK (status IN ('PROCESSED', 'FAILED'))
- payment-service/src/main/resources/db/changelog/changelog-003-webhook-retry.yaml:26–33 — adds the new states and backfills, but **does not update the CHECK constraint**
- payment-service/src/main/java/com/shop/paymentservice/entity/PaymentEvent.java:23–24 — entity declares the new statuses
- payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java:67–69 — every webhook insert uses STATUS_FAILED_RETRYABLE

**Effect:** Every webhook delivery (valid or not) that triggers the dedup INSERT path **throws a SQL constraint-violation** which propagates to the controller as 500. The C3 design is broken at the DB level — the changelog forgot to drop & re-add the CHECK with the new values. The backfill UPDATE itself (changelog-003 line 32) **will also fail** at migration time in any DB that previously had FAILED rows, leaving the migration half-applied. The integration test PaymentFlowIT happens to work because it posts clean webhooks with real paymentIds — none of them ever take the FAILED_RETRYABLE path.

**Fix:** add a new changeset (e.g. 004-payment-event-status-check) that drops & re-adds the CHECK with the new values; do **not** mutate the original changeset (already shipped). The retry scheduler (C5) is dead-on-arrival until this lands.

---

### C4 — PaymentServiceImpl.capture/refund ignore the accepted flag from the provider — partial success is silently treated as success
**Files:**
- payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:48–55, 58–64
- payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:14–16 (returns ProviderResult(providerEventId, accepted))

```java
public Payment capture(UUID id) {
    Payment payment = requirePayment(id);
    if (payment.getStatus() != PaymentStatus.PENDING) throw BusinessException.of(...);
    provider.capture(payment.getId(), payment.getAmount(), payment.getCurrency(), payment.getIdempotencyKey());
    return payment;                  // status still PENDING; ProviderResult discarded
}
```

MockProvider returns accepted=true; any future real provider that returns accepted=false (declined card, provider 4xx-as-accepted) will leave the row PENDING with no operator-visible failure and the synchronous POST /capture response will be 200 OK with status=PENDING. There is no transition to FAILED on the synchronous path either — only the webhook can drive PENDING→FAILED. **The whole synchronous capture API is a no-op against the persisted state machine.** Either drive the state machine synchronously inside capture() when accepted=false, or remove the synchronous capture endpoint entirely and make webhook the single source of truth.

---

### C5 — Idempotency begin() holds a SECOND pooled DB connection for the entire saga — including 3+ HTTP calls. Pool-exhaustion in low-mid-load fleets.
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java:51–71

```java
private final TransactionTemplate requiresNewTemplate;
public IdempotencyServiceImpl(... PlatformTransactionManager txManager ...) {
    TransactionTemplate template = new TransactionTemplate(txManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.requiresNewTemplate = template;
}
@Override public Optional<OrderResponse> begin(String key, UUID userId, String requestHash) {
    ...
    requiresNewTemplate.executeWithoutResult(status -> repository.saveAndFlush(ik));  // REQUIRES_NEW → 2nd connection
    return Optional.empty();   // saga runs while the caller's TX ALSO holds its own connection
}
```

The author's own javadoc admits it: *"while the caller's transaction is active, this template holds a SECOND pooled connection for the whole saga — including the remote pricing/reserve calls. Hikari's pool must be sized with that in mind."* This means every in-flight order uses **2** Hikari connections, one of them pinned across multiple HTTP round-trips (pricing → product/tax/promotion, then reserve loop). With Hikari maximum-pool-size defaulting to 10, you support **5 concurrent order-creations**, period. OrderCreateRequest blocking flows from the storefront will start hitting HikariPool connection-not-available errors with very modest traffic.

**Fix:** move the in-flight INSERT **outside** the saga TX (Spring application-event published from the controller with @TransactionalEventListener(phase=AFTER_COMMIT)) or a debounced post-commit hook, or use @TransactionalEventListener(phase=BEFORE_COMMIT) with a one-row insert ... on conflict do nothing returning xmax = 0 SQL to keep the lock time under 1 ms.

---

## High Findings

### H1 — OrderServiceImpl.doCreateOrder() is a private method called via self-invocation — a future refactor that adds @Transactional to it will silently break the saga
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:101–156

The class's javadoc on doCreateOrder explicitly says *"NOT annotated @Transactional. Runs in the TX opened by createOrder (proxy-invoked). Self-invocation would bypass the proxy — do not call this method from inside the same class."* This works today, but it's a **trap**: any future contributor who adds @Transactional to doCreateOrder, or splits it into a helper bean without preserving the rule, will silently lose the outer TX and the order will be persisted in a half-state. Add an explicit lint/checkstyle rule forbidding @Transactional on private methods, or extract doCreateOrder to a @Component OrderCreationSaga collaborator with its own @Transactional annotation so the rule is "the proxy applies once."

---

### H2 — Inventory reserve has a classic TOCTOU window: two concurrent callers can both pass the availability check and oversell
**File:** inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:121–148

```java
Inventory inventory = inventoryRepository.findByProductId(productId)...;
int available = inventory.getAvailableQuantity() - inventory.getReservedQuantity();
if (available < request.quantity()) throw ...;
inventory.setReservedQuantity(inventory.getReservedQuantity() + request.quantity());
inventoryRepository.save(inventory);  // @Version increments; second caller OLFE
```

The @Version guard on Inventory turns the second save into an OptimisticLockingFailureException — the retry wrapper in ReservationServiceImpl re-reads and re-checks, so technically no money-losing sale happens. **But** the implementation does the redundant SELECT+Java-arithmetic dance; the correct stock-manipulation pattern is a single atomic UPDATE:

```sql
UPDATE inventory
   SET reserved_quantity = reserved_quantity + :qty,
       version = version + 1,
       last_updated = now()
 WHERE product_id = :productId
   AND (available_quantity - reserved_quantity) >= :qty
```

with @Modifying(clearAutomatically=true) on a custom repository method. Returning the rowcount = 0 surfaces as STOCK_INSUFFICIENT without ever loading the entity. This also collapses the 3-query (find → arithmetic → save) write path into 1.

---

### H3 — Admin cancelOrder(CONFIRMED) sets status = CANCELLED but leaves the committed inventory untouched — stock is permanently lost without refund flow
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:252–289

The comment correctly notes that reservations are already COMMITTED at confirm time, so the existing /release endpoint would 409. **But there's no /release-committed path called for admin cancel**. The admin path jumps straight to setStatus(CANCELLED) with no compensation — so the stock held by item-A's reservation is now stranded in the COMMITTED state and availableQuantity was decremented at confirm time. delete() on Inventory is blocked by INVENTORY_IN_USE (line 109 of InventoryServiceImpl) so even ops cannot reset the row. The reconciliation scheduler will eventually mark it RECON_MIXED for human attention, but no automatic restock. **This is a money path** — a customer-paid order that admin-cancels leaves the company with neither the goods nor the customer's money until manual reconciliation. Wire the compensation to call InventoryServiceClient.releaseCommitted(reservationId) for each item before flipping status.

---

### H4 — No OutboxRetentionScheduler in payment-service — the outbox table grows unbounded
**Files:** payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEventRepository.java:19 (the delete query exists, nobody calls it). Compare with order-service/.../OutboxRetentionScheduler.java and inventory-service/.../OutboxRetentionScheduler.java which DO have one. OutboxEvent in payment-service has the same shape and the same @Scheduled cron budget; it just never got the scheduler. Over months the table hits millions of rows and findByStatusOrderByIdAsc(PENDING, pageable) keeps scanning the whole index. **Fix:** copy order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java and point it at the payment-service repository.

---

### H5 — Webhook X-Webhook-Signature verifier rejects sha256=<hex> prefix (the format Stripe and every major PSP uses)
**File:** payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookSignatureVerifier.java:18–37

```java
if (signatureHeader == null || signatureHeader.length() != SHA256_HEX_LENGTH) return false;   // 64 chars exactly
...
byte[] provided = HexFormat.of().parseHex(signatureHeader);
```

A real Stripe Stripe-Signature header carries `t=<ts>,v1=<hex>` and v1=<hex> alone is 71 chars (v1= prefix + 64 hex). The verifier's test suite (WebhookSignatureVerifierTest.prefixedHexHeaderIsRejected) **codifies this as a feature**. When you actually wire StripeProvider, the production webhook will be rejected. Either relax the parser to strip an optional v1=/sha256= prefix, or split signature-format per provider (Stripe has its own timestamped scheme requiring tolerance checks).

---

### H6 — Race in OrderServiceImpl.confirmOrder (different keys): the loser's @Version clash is incorrectly mapped to ORD-4011
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:354–382

When two threads confirm the same order with **different** idempotency keys, both pass validateTransition(PENDING, CONFIRMED), both succeed at the remote coordinator (idempotent), then race on orderRepository.save() — loser gets OptimisticLockingFailureException, is wrapped to **ORD-4011 "confirm commit failed"** and 409. But the order IS already CONFIRMED; the caller has no way to learn that from the response, and will retry → second validateTransition(CONFIRMED, CONFIRMED) throws ORD-4004. The wire-level response is contradictory (loser gets 409/4011 then 409/4004 on retry, never a 200 with the CONFIRMED state). **Fix:** let OLFE propagate to ApiExceptionHandler.handleOptimisticLocking (already exists in common-spring — it correctly maps to CONFLICT 409 with the generic code, not ORD-4011); or detect the OLFE specifically, re-read the order, and if it's now CONFIRMED return the cached order response.

---

### H7 — PricingServiceImpl.calculate does an N+1 in the product catalog fetch
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java:53–60

```java
for (CartItem item : items) {
    ProductSnapshot snapshot = productClient.getProduct(item.getProductId());   // 1 HTTP call per item
    ...
}
```

CartItem counts of 50–100 are not unrealistic (B2B carts). The cache softens it, but the very first order, and any TTL expiry, re-fires 50–100 RTT. The product-service likely already has a /products?ids=... batch endpoint (matching pattern across the codebase). Switch the call to batch, with one Redis pipelined cache fetch and one HTTP fallback.

---

### H8 — OrderStatusServiceImpl state machine blocks SHIPPED → CANCELLED (lost-package admin cancel)
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java:19–27

A real ops scenario (carrier loses the package mid-transit, customer wants a refund) needs admin to cancel a SHIPPED order. The matrix says SHIPPED → DELIVERED only. Either add SHIPPED → CANCELLED for admin only, or document the support runbook ("refund via payment-service + manual stock adjustment via inventory backoffice").

---

## Medium Findings

### M1 — DRY violation: ReservationServiceImpl has 4 nearly identical retry loops — FIXED
**File:** inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java:22–95

Extracted a shared generic `withRetry(Supplier<T>, UUID)` policy used by all four operations. Added a void-operation regression test covering retry behavior. Verified with `ReservationServiceImplTest` (4 tests passing).

---

### M2 — Tax service has no auth header, no JWT propagation — security gap if tax ever moves off disabled-default
**Files:**
- order-service/src/main/java/com/shop/orderservice/client/TaxServiceClient.java:33–42 (no Authorization header on the call)
- order-service/src/main/resources/application.yml:50 — tax enabled default = false (so it's masked today)

When tax is enabled (planned per the comment *"MVP default false; flips when tax-service ships"*), the call goes out unauthenticated. Tax endpoints in tax-service are at /api/v1/backoffice/tax-rates/calculate and will require SERVICE role — without the bearer token, every call returns 401. Add the same ServiceTokenProvider injection that InventoryServiceClient/PromotionServiceClient use.

---

### M3 — CartServiceImpl uses @Transactional for all methods (no @Transactional(readOnly=true) even for getMyCart's happy read path)
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:42, 50, 87, 110, 124

The comment at line 40 explains getMyCart is not read-only (auto-create). But addItem/updateItem/removeItem are write paths; OK. The pattern's rule #7 says *"@Transactional(readOnly=true) cho read"* — this service is fully write, so the rule still doesn't trigger, BUT getMyCart is genuinely a read in the 99% case (cart already exists), and the cached Cart could come from a Redis layer you don't have. Mark a fast-path method @Transactional(readOnly=true) for the common read, fall through to the auto-create path on cache miss.

---

### M4 — PaymentServiceImpl.create does a findByIdempotencyKey then save — race window between the two
**File:** payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:32–43

Two parallel POST /payments with the same idempotencyKey both miss the findByIdempotencyKey → both call writer.insert → unique index uk_payment_idempotency_key rejects the second with DataIntegrityViolationException → 500. Either catch and re-fetch (mirroring IdempotencyServiceImpl.begin() in order-service), or do the lookup with INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING *.

---

### M5 — OrderService publish-then-save order: outbox event for order.created.v1 is emitted before the priced total is on the row
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:184–187 (publishCreated) is called after the second save (line 143), but the saga does this in the same TX — so consumer reads from outbox relay see the right total. However, publishCancelled / publishStatusChanged re-use the same publisher pattern; if any future contributor publishes before the status flip, consumers race. Wrap publishes in a separate method with a clear contract ("must be called after the row reaches its final state").

---

### M6 — OrderCommitCoordinator.commitForConfirm has no idempotency guard
**File:** order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:33–69

If a future caller invokes commitForConfirm twice on the same order (e.g. retry after partial network failure that lost the response), promotion/inventory commits will fire twice. The remote services are idempotent (commit on already-COMMITTED → state-machine 4xx → ORD-4011), so no double-spend, but the second attempt is wasted bandwidth and pollutes the metrics counter. Pass the idempotency state into the coordinator, or rely entirely on OrderServiceImpl.confirmOrder's idempotency-key guard to prevent re-entry (which is the case today, but it's a fragile coupling).

---

### M7 — PaymentServiceImpl.findAllByOrderId(orderId=null) does findAllByOrderByCreatedAtDesc (all payments, all users) — possible IDOR via backoffice
**File:** payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:67–73 + PaymentController.java:51–60 (only requires SERVICE/ADMIN)

A SERVICE-token holder (legitimate order-service) can omit the orderId and dump every payment in the system via the orderId optional parameter. Either make it required for SERVICE and only allow null for ADMIN, or split into findAllByOrderId (required) and findAll (ADMIN only).

---

### M8 — OrderServiceImpl.confirmOrder flag-gated payment check swallows raw exception text via log but the metric order.confirm.duration{phase} never records the payment-phase timing
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:269–286 — no Timer.Sample around the payment lookup; the order.confirm.duration{phase="payment_check"} (per the metrics class contract) is never emitted. Either add the timer or remove the phase name from the metrics javadoc contract.

---

### M9 — OrderReconciliationScheduler.pollReservationStates routeToMixed on a fully-populated exception state leaks the whole state list to logs (and the field can be empty when an early throw happens)
**File:** order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:127–143 — routeToMixed(order, states, "state poll failed: ...") is called with whatever partial states list was built before the throw. For a 10-item order with the first 5 polling OK, the WARN log lists all 5 statuses — fine, but you can also land there with an empty list (exception on the very first promotion poll), and the "states=" log entry shows [] which is indistinguishable from a "no applicable reservations" path. Distinguish the two by a third argument or a separate log message.

---

### M10 — Outbox events in payment-service are not counted by any metrics component
**File:** payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentEventPublisher.java:62 — save(...) writes the row but no metrics counter is incremented. For consistency with order/inventory (both have order.events.published / inventory.events.published), add payment.events.published and bind it to a counter in a new PaymentMetrics component (or extend the publisher to inject a MeterRegistry).

---

## Low Findings

### L1 — OrderController.createOrder length check on Idempotency-Key is done in the controller, not as a @Size validation on the header
**File:** order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:39–42
**Not actually a bug** (HTTP headers can't carry @Size), but the check duplicates the schema's varchar(64) constraint. Add a header-validation interceptor or use @RequestHeader with a custom converter that throws on >64.

### L2 — CartServiceImpl.calculateSubtotal recomputes from a fresh DB query after every mutation
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:152–156
The subtotal is recomputed via cartItemRepository.findByCartId(cart.getId()) after every write, but the in-memory cart already has all the items (or could). Compute from the loaded list passed in; one fewer round-trip per cart mutation.

### L3 — PaymentStateMachine has no path from PENDING → CANCELLED (intentional? no admin-cancel of an in-flight payment)
**File:** payment-service/src/main/java/com/shop/paymentservice/service/PaymentStateMachine.java:10–12 — only PENDING→{CAPTURED,FAILED} and CAPTURED→REFUNDED. There is no way to cancel a PENDING payment; the row sits there until webhook or admin action.

### L4 — OrderServiceImpl.createOrder leaks the per-request idempotency-hash secret if a future contributor forgets to clear the ObjectMapper
**File:** order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:191–196 (hash(req) serializes the request body via Jackson). Today the body is just {cartId, couponCode} — no PII. If a future contributor adds userId or shipping address to the request, the hash becomes a PII-handling concern (GDPR). Document this in the method javadoc.

### L5 — OutboxRetentionScheduler cron field duplicated in application.yml and inline in @Scheduled annotations
**File:** order-service/src/main/resources/application.yml:78–80 declares the cron and the scheduler has @Scheduled(cron = "${...:default}") — both hold the default. If only the YAML is updated, the inline default wins for env-driven deployments. Standardize on one source.

### L6 — WebhookEventService.handle re-reads the just-inserted event row by findFirstByProviderAndProviderEventId after the INSERT — N+1 insert-then-fetch pattern
**File:** payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java:91–97 — better: have writer.insertEvent(event) return the persisted entity (it already does — @Transactional method returning the entity), and pass it directly to process(payload, event). Saves one query per webhook.

### L7 — PaymentServiceImpl.create does not auto-fail on null currency / null amount validation (relies on bean validation only)
**File:** payment-service/src/main/java/com/shop/paymentservice/dto/CreatePaymentRequest.java:14–19 uses @NotNull/@NotBlank/@DecimalMin — good. But Currency validation only checks 3-char length, not ISO-4217 codes (XYZ is accepted). Add an @Pattern(regexp = "^[A-Z]{3}$") and a custom validator against the ISO list, or accept any 3-uppercase.

### L8 — InventoryServiceImpl.findById is annotated @Cacheable but the cache key is just #productId; a future contributor adding a filter parameter would silently break cache hits
**File:** inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:65 — @Cacheable(value = "inventory", key = "#productId"). Today there's only the productId. If a future findById(productId, includeReservedHistory) is added without thinking about cache key composition, stale reads result.

### L9 — OrderController.verify-purchase has no rate-limit / abuse guard
**File:** order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:79–89 — service-token-only endpoint returning a user's purchase history by productId. No userId == authenticatedUser check (the SERVICE token can query any user). Acceptable for inter-service, but document that SERVICE callers must be trusted, and consider a @PreAuthorize("hasRole('SERVICE')") audit on the SERVICE principal.

### L10 — OrderServiceImpl.cancelOrder doesn't release a COMMITTED reservation on admin cancel (companion to H3, but the leak is also here)
Already covered by H3.

---

## Pattern Compliance Summary

| # | Rule | Verdict | Notes |
|---|---|---|---|
| 1 | Layer controller→service→impls→repository→entity; dto/request+response records | COMPLIANT | All three services follow the layered structure. DTOs are records (OrderCreateRequest, ReserveRequest, CreatePaymentRequest, etc.). InventoryMapper uses ModelMapper for partial updates (M-class tradeoff). |
| 2 | ApiResponse<T> / PageResponse<T> envelope | COMPLIANT | Used consistently. PageResponse is used directly for paginated endpoints (not Spring Page). |
| 3 | @RequestMapping(ApiPaths.*) | COMPLIANT | Every controller pins to a constant. WEBHOOK_PAYMENTS correctly used for the unauthenticated webhook path. |
| 4 | Page-size cap Math.min(size, PageableConstant.MAX_PAGE_SIZE) | COMPLIANT | All 6 paginated endpoints apply the cap. |
| 5 | Storefront no @PreAuthorize; Backoffice ADMIN class-level | MOSTLY COMPLIANT | OrderController + CartController use isAuthenticated() (matches storefront intent); OrderStatusController uses hasAnyRole('SERVICE','ADMIN') class-level (acceptable for a special status-transition gateway). BackofficePaymentController uses hasRole('ADMIN'). InventoryController reserves ADMIN for writes, SERVICE+ADMIN for reservations — correct per design. The findAll on OrderController is the only ADMIN-protected storefront endpoint (correct — admin listing). |
| 6 | AuthenticatedUser.requireCurrent().id() | COMPLIANT | Used in all three controllers; controllers have a private currentUserId() helper to keep it DRY. |
| 7 | @Transactional(readOnly=true) for read; soft-delete @SQLRestriction | MOSTLY COMPLIANT | All read service methods in payment/inventory use readOnly=true. OrderServiceImpl correctly does so. CartServiceImpl has no read-only method even though getMyCart is mostly read (M3). Soft-delete @SQLRestriction("deleted = false") is applied to Order, Cart, Payment, PaymentEvent (NOT to OrderItem, CartItem, Inventory, Reservation, OutboxEvent — documented as hard-delete in each entity javadoc, which matches the spec). |
| 8 | ddl-auto: validate + Liquibase changelog | COMPLIANT | All three application.yml pin ddl-auto: validate and use Liquibase. Tests override to none to avoid double schema management. Changelog files follow the per-module naming convention. |
| 9 | ENV_VAR default in YAML; mapper/ directory; outbox pattern | MOSTLY COMPLIANT | All sensitive URLs and secrets have ENV_VAR defaults. mapper/ is present in order-service and inventory-service (Mappers use hand-written code; InventoryMapper uses ModelMapper for partialUpdate — acceptable but OrderMapper and CartMapper are pure manual code which is more explicit). Outbox pattern: order-service and inventory-service have it (with retention schedulers); **payment-service outbox is missing the retention scheduler** (H4). All env-var defaults ship to localhost: this is fine for dev compose but should be flagged in a prod runbook. |

### Pattern deviations worth noting

- **Lombok @RequiredArgsConstructor + @Slf4j is universal** — fine.
- **InventoryMapper uses ModelMapper** (one method); OrderMapper / CartMapper are pure manual. The mixed style is intentional (per javadoc on the mappers) but consider unifying on manual mapping.
- **Payment-service has no mapper/ directory** because it has no entity→DTO mapping (PaymentResponse.from is a static factory on the record). Acceptable.
- **Outbox event topic naming diverges**: order-service uses shop.order.lifecycle.v1 (PascalCase type), inventory-service uses shop.inventory.events.v1 (dot.case), payment-service uses shop.payment.lifecycle.v1 (PascalCase). Inventory is the outlier — documented as deliberate (CloudEvents-style).
- **@Audited on OrderStatusController confirm/ship/deliver but not on inventory cancel/release paths** — make sure the audit matrix in docs/SERVICE-CATALOG.md matches.
- **WebhookSignatureVerifier length check (!= 64) doesn't allow sha256= prefix** — Stripe convention will break it (H5).

---

## Top 5 Issues to Fix Immediately

1. **C1 — Webhook secret defaults to empty string.** A single env-var miss in any environment means **every** webhook 401s and payments are stuck PENDING. Replace the default with a non-empty placeholder and fail-fast at startup. *This is the single biggest production-readiness bug — anything sold today cannot be captured because capture() is unimplemented AND the webhook that would drive PENDING→CAPTURED is rejected.*

2. **C3 — ck_payment_events_status CHECK constraint missing FAILED_RETRYABLE / FAILED_PERMANENT.** Every webhook delivery crashes the request thread with a 500 (constraint violation), no events reach the state machine, the retry scheduler is dead-on-arrival. Add a 004-payment-event-status-check changeset that drops & re-adds the constraint with the new values.

3. **C2 — StripeProvider is a stub that throws.** The production default of stripe means any non-mock deployment hits UnsupportedOperationException on the first POST /payments/{id}/capture. Either ship a Stripe SDK integration or flip the production default to mock and gate prod on a startup smoke test.

4. **H3 — Admin cancel of a CONFIRMED order strands the stock and the money.** Wire admin cancel to call InventoryServiceClient.releaseCommitted for each item reservation before flipping status, or document the runbook for manual restock. Today the customer is paid AND the company has the goods with no automated path to reconcile.

5. **C5 — Idempotency begin() opens a second Hikari connection that lives across the entire pricing/reserve HTTP saga.** Default pool size (10) supports only **5 concurrent order creations**. With a mid-traffic storefront this saturates fast. Move the in-flight insert to a post-commit application event so the row is written **after** the saga commits, or use a single-row INSERT with ON CONFLICT to shrink the lock to <1 ms.

---

## Notable strengths (worth keeping)

- **Compensation discipline in the order saga** is exceptional: deterministic productId ordering, LIFO release-committed on confirm failure, no masking of original errors, and orderReconciliation mixed-state routing prevents silent auto-decisions on incomplete data.
- **Idempotency design** is well thought out — hash-mismatch-on-replay 409, requestHash-guard on abort, REQUIRES_NEW for the in-flight insert, loss-race handling tested.
- **The ConfirmOrchestrationIT** is one of the best end-to-end tests in the codebase (race tests, fault injection, timeout-then-replay, ledger assertions at the WireMock journal level).
- **Cache serializer regression tests** (CacheSerializerRoundTripTest) explicitly guard the shared-ObjectMapper mutation bug — good example for the rest of the fleet.
- **InventoryCacheService.evictAfterCommit** with the @Cacheable regression net (create_evictsCacheAfterCommit, etc.) is exactly the pattern the rest of the fleet should copy.
- **Webhook dedup on (provider, providerEventId)** + state-machine idempotency is correct and tested.
- **Testcontainers singleton lifecycle** in AbstractIntegrationTest + ContextCache-isolation pattern prevents the Connection-refused re-bind flake that bit order-service task 13.

---

*Review complete. All findings cite the file and line where the issue lives. Pattern compliance was checked against the nine-rule charter.*
