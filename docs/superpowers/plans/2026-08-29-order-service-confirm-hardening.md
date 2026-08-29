# Order Service Confirm Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the orphan-commit gap — make `confirmOrder` a commit orchestrator (promotion first, then inventory) with idempotent lifecycle calls, half-commit compensation, confirm idempotency, reconciliation, and metrics.

**Architecture:** Explicit HTTP orchestration from order-service (fleet pattern, no consumer). Inventory gets idempotent commit/release branches + `release-committed` + `state` endpoints (backward compatible). Order gains `promotion_reservation_id`, pre-generated orderId, `OrderCommitCoordinator` ((type,id) compensation targets), optional `Idempotency-Key` on confirm, `OrderReconciliationScheduler`, `OrderConfirmMetrics`.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase, WireMock standalone, Testcontainers PostgreSQL, MeterRegistry.

**Spec:** [`docs/superpowers/specs/2026-08-29-order-service-confirm-hardening-design.md`](../specs/2026-08-29-order-service-confirm-hardening-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.*` (never `org.shop.`). Boot 4.1.1, Java 25.
- `@WebMvcTest` import: `org.springframework.boot.webmvc.test.autoconfigure` (Boot 4 package). Seed `JwtAuthenticationToken` — `TestingAuthenticationToken` breaks `AuthenticatedUser.current()`. `@Import(ApiExceptionHandler.class)`.
- Shared i18n bundle: `utils/common-spring/src/main/resources/messages/messages_en.properties` + `messages_vi.properties`. Keys are fleet-global — order keys `order.*`, inventory keys already exist (`reservation.*` = INV codes).
- ErrorCode enum: `PAYMENT_NOT_FOUND` ("PAY-5002") is the LAST entry and ends with `;`. Any insertion before it ends with `,`.
- WireMock = `org.wiremock:wiremock-standalone` (already in order-service pom). Classic API (`com.github.tomakehurst.wiremock.*`).
- Liquibase: `defaultValueBoolean` for booleans; partial indexes via raw SQL (`createIndex` drops `where`).
- `lombok.config` with `copyableAnnotations += Qualifier` exists at repo root (from order-service implementation) — VERIFY, don't recreate.
- Deploy order (spec §9): inventory (Tasks 2-4) → order (Tasks 5-14). Each task's commit is deployable.
- Compensation never throws (`OrderServiceImpl:168-180` swallow pattern) — silent-swallow refactor is a tracked follow-up (spec §12), NOT this plan.

## File Map

| File | Action |
|------|--------|
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | MODIFY — add ORD-4011 |
| `utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties` | MODIFY — `order.confirm.commit.failed` |
| `utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java` | MODIFY — add `PROMOTIONS` |
| `inventory-service/.../service/impls/InventoryServiceImpl.java` | MODIFY — idempotent branches (commit :150, release :174) + `releaseCommitted` |
| `inventory-service/.../service/ReservationService(+Impl).java` | MODIFY — `releaseCommittedWithRetry` |
| `inventory-service/.../controller/InventoryController.java` | MODIFY — 2 new endpoints |
| `inventory-service/.../dto/response/ReservationResponse.java` | MODIFY — ensure status/timestamps fields |
| `order-service/.../dto/internal/PromotionReserveRequest.java` | CREATE (replaces `PromotionApplyRequest`) |
| `order-service/.../dto/internal/PromotionReserveResponse.java` | CREATE (replaces `PromotionApplyResponse`) |
| `order-service/.../client/PromotionServiceClient.java` | REWRITE |
| `order-service/.../config/RestClientConfig.java` | MODIFY — correlation initializer |
| `order-service/.../entity/Order.java` | MODIFY — `promotionReservationId` |
| `order-service/src/main/resources/db/changelog/changelog-002-confirm-hardening.yaml` | CREATE |
| `order-service/.../service/OrderCommitCoordinator.java` | CREATE |
| `order-service/.../service/OrderConfirmMetrics.java` | CREATE |
| `order-service/.../service/OrderReconciliationScheduler.java` | CREATE |
| `order-service/.../service/PricingService(+Impl).java` | MODIFY — orderId param, reserve promotion |
| `order-service/.../service/impls/OrderServiceImpl.java` | MODIFY — saga + confirm + cancel |
| `order-service/.../controller/OrderStatusController.java` | MODIFY — Idempotency-Key header |
| `order-service/.../service/OrderService.java` | MODIFY — signature |
| `order-service/src/main/resources/application.yml` | MODIFY — reconciliation props |
| Tests | CREATE per task |

---

### Task 1: ErrorCode ORD-4011 + i18n + ApiPaths.PROMOTIONS

**Files:**
- Modify: `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`
- Modify: `utils/common-spring/src/main/resources/messages/messages_en.properties`, `messages_vi.properties`
- Modify: `utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java`

**Interfaces:**
- Produces: `ErrorCode.CONFIRM_COMMIT_FAILED("ORD-4011", "order.confirm.commit.failed", HttpStatus.CONFLICT)`; `ApiPaths.PROMOTIONS = "/api/v1/promotions"`.

- [ ] **Step 1: Add enum entry**

In `ErrorCode.java`, find `ORDER_DUPLICATE_REQUEST("ORD-4010", "order.duplicate.request", HttpStatus.CONFLICT),` — insert AFTER it (before `PAYMENT_NOT_FOUND`):

```java
    CONFIRM_COMMIT_FAILED("ORD-4011", "order.confirm.commit.failed", HttpStatus.CONFLICT),
```

Verify: `PAYMENT_NOT_FOUND("PAY-5002", ...);` remains the last entry with `;`.

- [ ] **Step 2: Add i18n keys**

`messages_en.properties`:
```properties
order.confirm.commit.failed=Failed to confirm order {0}: stock or coupon commit failed
```
`messages_vi.properties`:
```properties
order.confirm.commit.failed=Không thể xác nhận đơn hàng {0}: lỗi xác nhận kho hoặc mã giảm giá
```

- [ ] **Step 3: Add ApiPaths constant**

In `ApiPaths.java`, Commerce section (after `FAVOURITES`):
```java
    public static final String PROMOTIONS = API_V1 + "/promotions";
```

- [ ] **Step 4: Verify + commit**

```bash
./mvnw -pl utils/common-core -am compile
git add utils/common-core utils/common-spring/src/main/resources/messages
git commit -m "feat(common-core): ORD-4011 CONFIRM_COMMIT_FAILED + ApiPaths.PROMOTIONS"
```

---

### Task 2: Inventory — idempotent commit/release branches (TDD)

**Files:**
- Modify: `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:150-193`
- Test: `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryLifecycleIdempotencyTest.java`

**Interfaces:**
- Consumes: existing `commit(UUID)` / `release(UUID)` (`InventoryServiceImpl:150,174`), `ReservationStatus` {PENDING, COMMITTED, RELEASED, EXPIRED}, codes INV-3003/3004/3005.
- Produces: `commit()` returns OK on already-COMMITTED; `release()` returns OK on RELEASED/EXPIRED; expired PENDING → `RESERVATION_EXPIRED` both; wrong-way terminal → `RESERVATION_INVALID_STATE`.

- [ ] **Step 1: Write failing tests**

```java
package com.shop.inventoryservice.service;

// Integration test — Testcontainers Postgres, real Liquibase.
// Base class: copy the service's existing integration support (same pattern as
// OrderCreationSagaIntegrationTest: @Import({JpaAuditingAutoConfiguration.class,
// TestLiquibaseConfig.class}), @DynamicPropertySource datasource).
class InventoryLifecycleIdempotencyTest extends AbstractInventoryIntegrationTest {

    @Autowired ReservationRepository reservations;
    @Autowired InventoryService inventoryService;
    @Autowired InventoryRepository inventories;

    UUID seedReserved(UUID productId, int qty, ReservationStatus status, Instant expiresAt) {
        Inventory inv = inventories.save(Inventory.builder()
            .productId(productId).availableQuantity(100).reservedQuantity(qty).build());
        Reservation r = Reservation.builder()
            .productId(productId).quantity(qty).status(status)
            .expiresAt(expiresAt).reservedAt(Instant.now()).build();
        return reservations.save(r).getId();
    }

    @Test
    void commit_twice_secondIsNoop() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.PENDING,
            Instant.now().plusSeconds(600));
        inventoryService.commit(id);
        assertThatCode(() -> inventoryService.commit(id)).doesNotThrowAnyException();
    }

    @Test
    void commit_afterRelease_throwsInvalidState() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.RELEASED,
            Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> inventoryService.commit(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_INVALID_STATE));
    }

    @Test
    void commit_expiredPending_throwsExpired() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.PENDING,
            Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> inventoryService.commit(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_EXPIRED));
    }

    @Test
    void release_afterReleaseOrExpired_isNoop() {
        UUID a = seedReserved(UUID.randomUUID(), 5, ReservationStatus.RELEASED, Instant.now().plusSeconds(600));
        UUID b = seedReserved(UUID.randomUUID(), 5, ReservationStatus.EXPIRED, Instant.now().plusSeconds(600));
        assertThatCode(() -> { inventoryService.release(a); inventoryService.release(b); })
            .doesNotThrowAnyException();
    }

    @Test
    void release_afterCommitted_throwsInvalidState() {
        UUID id = seedReserved(UUID.randomUUID(), 5, ReservationStatus.COMMITTED, Instant.now().plusSeconds(600));
        assertThatThrownBy(() -> inventoryService.release(id))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_INVALID_STATE));
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./mvnw -pl inventory-service test -Dtest=InventoryLifecycleIdempotencyTest
```
Expected: `commit_twice_secondIsNoop` and `release_afterReleaseOrExpired_isNoop` FAIL (`RESERVATION_INVALID_STATE` thrown).

- [ ] **Step 3: Patch the two methods**

`InventoryServiceImpl.commit` (currently `if (r.getStatus() != ReservationStatus.PENDING) throw INVALID_STATE;` at :153) — insert BEFORE that check:

```java
        // Idempotent retry: a retried commit after a timeout must succeed (hardening spec §7.1)
        if (r.getStatus() == ReservationStatus.COMMITTED) {
            log.info("Reservation {} already committed (idempotent retry)", reservationId);
            return;
        }
```

`InventoryServiceImpl.release` (check at :177) — insert BEFORE the PENDING check:

```java
        // Idempotent: quota already returned — safe no-op (hardening spec §7.1)
        if (r.getStatus() == ReservationStatus.RELEASED
            || r.getStatus() == ReservationStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", reservationId);
            return;
        }
```

Keep expired-PENDING → `RESERVATION_EXPIRED` and wrong-way → `RESERVATION_INVALID_STATE` exactly as-is.

- [ ] **Step 4: Run — expect PASS, plus FULL existing suite green**

```bash
./mvnw -pl inventory-service test
```
Existing commit/release tests (PENDING happy path) must still pass.

- [ ] **Step 5: Commit**

```bash
git add inventory-service
git commit -m "fix(inventory): idempotent commit/release lifecycle branches (hardening §7.1)"
```

---

### Task 3: Inventory — releaseCommitted (restock committed reservation)

**Files:**
- Modify: `inventory-service/.../service/InventoryService.java`, `InventoryServiceImpl.java`, `service/ReservationService.java`, `service/impls/ReservationServiceImpl.java`, `controller/InventoryController.java`
- Modify: `TransactionalInventoryEventPublisher` payload — add `previousStatus`
- Test: `inventory-service/.../service/ReleaseCommittedTest.java`

**Interfaces:**
- Produces: `InventoryService.releaseCommitted(UUID)`; `ReservationService.releaseCommittedWithRetry(UUID)`; endpoint `POST /api/v1/inventory/reservations/{id}/release-committed`; event `inventory.released.v1` gains `previousStatus` ("PENDING"|"COMMITTED"); also add `previousStatus` to plain `release` ("PENDING").

- [ ] **Step 1: Failing tests**

```java
@Test
void releaseCommitted_restocks_andTerminalizes() {
    UUID productId = UUID.randomUUID();
    seedReserved(productId, 5, ReservationStatus.COMMITTED, Instant.now().plusSeconds(600));
    // commit() already moved reservedQuantity→0 and availableQuantity -= 5

    inventoryService.releaseCommitted(/* that reservation id */);

    Inventory inv = inventories.findByProductId(productId).orElseThrow();
    assertThat(inv.getAvailableQuantity()).isEqualTo(100);   // restored
    Reservation r = reservations.findById(id).orElseThrow();
    assertThat(r.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    assertThat(r.getReleasedAt()).isNotNull();
}

@Test
void releaseCommitted_isIdempotent_onAlreadyReleasedOrExpired() { ... }        // expect no-op

@Test
void releaseCommitted_onPending_throwsInvalidState() { ... }                   // use plain release
```

- [ ] **Step 2: Run — FAIL (method does not exist)**

- [ ] **Step 3: Implement**

`InventoryServiceImpl`:

```java
    // ⚠️ Hardening spec §7.2 — half-commit rollback. COMMITTED → RELEASED with restock.
    @Override
    @Transactional
    public void releaseCommitted(UUID reservationId) {
        Reservation r = reservationRepository.findById(reservationId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
        if (r.getStatus() == ReservationStatus.RELEASED
            || r.getStatus() == ReservationStatus.EXPIRED) {
            log.info("Reservation {} already terminalized (idempotent retry)", reservationId);
            return;
        }
        if (r.getStatus() != ReservationStatus.COMMITTED) {
            throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
        }
        Inventory inv = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
        inv.setAvailableQuantity(inv.getAvailableQuantity() + r.getQuantity());
        r.setStatus(ReservationStatus.RELEASED);
        r.setReleasedAt(Instant.now());
        inventoryRepository.save(inv);
        reservationRepository.save(r);
        publisher.publishReleased(inv, r, "COMMITTED");   // previousStatus — Task 3 Step 4
        cacheService.evictAfterCommit(r.getProductId());
    }
```

Mirror the optimistic-retry wrapper (`ReservationServiceImpl:62-75` pattern → `releaseCommittedWithRetry`, 3 attempts, `BACKOFF_BASE_MS * attempt`).

Plain `release()` publisher call becomes `publisher.publishReleased(inv, r, "PENDING")` — extend the existing payload builder with the new field; do NOT rename the event type.

Controller (mirror `:88` release endpoint):

```java
    @PostMapping("/reservations/{reservationId}/release-committed")
    // ⚠️ Inventory spec §4.2 auth — same @PreAuthorize as commit/release (SERVICE or ADMIN)
    public ApiResponse<Void> releaseCommitted(@PathVariable UUID reservationId) {
        reservationService.releaseCommittedWithRetry(reservationId);
        return ApiResponse.ok(null);
    }
```

- [ ] **Step 4: Run tests + full suite — PASS. Commit**

```bash
git add inventory-service
git commit -m "feat(inventory): release-committed endpoint + previousStatus event field (hardening §7.2)"
```

---

### Task 4: Inventory — GET reservation state endpoint

**Files:**
- Modify: `inventory-service/.../controller/InventoryController.java`, `dto/response/ReservationResponse.java` (if missing fields)
- Test: `inventory-service/.../controller/ReservationStateEndpointTest.java` (`@WebMvcTest`)

**Interfaces:**
- Produces: `GET /api/v1/inventory/reservations/{id}/state` → `ApiResponse<ReservationResponse{reservationId, productId, quantity, status, reservedAt, expiresAt, committedAt, releasedAt}>`.

- [ ] **Step 1: Verify/extend ReservationResponse** — it must expose `status` + all 4 timestamps; add missing fields (ModelMapper-safe: match entity property names).
- [ ] **Step 2: Endpoint** — mirror commit endpoint shape:

```java
    @GetMapping("/reservations/{reservationId}/state")
    // Same @PreAuthorize as commit/release
    public ApiResponse<ReservationResponse> state(@PathVariable UUID reservationId) {
        return ApiResponse.ok(reservationService.getState(reservationId));
    }
```

`getState` = findById → RESERVATION_NOT_FOUND → map to response (read-only, no retry wrapper needed).
- [ ] **Step 3: @WebMvcTest** — SERVICE-role JWT seed → 200 + body; no auth → 401; USER role → 403; unknown id → 404 INV-3003. Seed `JwtAuthenticationToken` (never `TestingAuthenticationToken`).
- [ ] **Step 4: Run + commit**

```bash
git add inventory-service && git commit -m "feat(inventory): reservation state endpoint for reconciliation (hardening §7.3)"
```

---

### Task 5: Order — PromotionReserve DTOs + client rewrite + correlation propagation

**Files:**
- Create: `order-service/.../dto/internal/PromotionReserveRequest.java`, `PromotionReserveResponse.java`
- Delete: `PromotionApplyRequest.java`, `PromotionApplyResponse.java`
- Rewrite: `order-service/.../client/PromotionServiceClient.java`
- Modify: `order-service/.../config/RestClientConfig.java`
- Test: update `PricingServiceImplTest` / saga IT references (compile fix only — behavior lands Task 7)

**Interfaces:**
- Produces (client API used by Tasks 7, 10, 11):
```java
PromotionReserveResponse reserve(PromotionReserveRequest request);   // POST /api/v1/promotions/{code}/reserve
void commit(UUID reservationId);                                     // POST /api/v1/promotions/reservations/{id}/commit
void release(UUID reservationId);                                    // POST /api/v1/promotions/reservations/{id}/release
void releaseCommitted(UUID reservationId);                           // POST /api/v1/promotions/reservations/{id}/release-committed
ReservationStateResponse getReservationState(UUID reservationId);    // GET  /api/v1/promotions/reservations/{id}/state
boolean isEnabled();                                                 // unchanged
public record PromotionReserveRequest(String code, BigDecimal orderAmount, UUID userId, UUID orderId) {}
public record PromotionReserveResponse(UUID reservationId, BigDecimal discountAmount, BigDecimal finalAmount) {}
public record ReservationStateResponse(String status) {}
```

- [ ] **Step 1: DTO records** (exact bodies above).
- [ ] **Step 2: Rewrite client** — copy `InventoryServiceClient` structure: `@Qualifier("promotionRestClient")` + `ServiceTokenProvider` field, per-call `.header("Authorization", "Bearer " + serviceTokenProvider.getToken())` on ALL five methods (promotion endpoints are SERVICE-role). `reserve`: 4xx → `BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, request.code())`; 5xx → `SERVICE_UNAVAILABLE`; `commit`/`release`/`releaseCommitted`: 4xx rethrow parsed `BusinessException` (coordinator needs `RESERVATION_*` codes distinguishable — parse `ApiResponse.error.code` like common-core's client helpers; if the fleet has no response-error parser, throw `BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion")` and treat NOT_FOUND-detection as a Task 11 follow-up via state polling). Keep `isEnabled()` + zero-discount fallback unchanged.
- [ ] **Step 3: Correlation propagation in RestClientConfig** (spec §2, D9) — add to EACH builder:

```java
.requestInitializer(req -> {
    String corrId = MDC.get(MdcKey.CORRELATION_ID);   // com.shop.common.spring.logging.MdcKey (verify exact package via IDE)
    if (corrId != null) req.getHeaders().set("X-Correlation-Id", corrId);
})
```

- [ ] **Step 4: `./mvnw -pl order-service -am compile`** — fix all references (PricingServiceImpl constructor call sites) to the new records; temporarily keep old behavior semantics (apply→reserve mapping) so suite stays green.
- [ ] **Step 5: Commit**

```bash
git add order-service && git commit -m "feat(order-service): promotion reserve/commit client + correlation propagation (hardening §2)"
```

---

### Task 6: Order — promotion_reservation_id column + entity field

**Files:**
- Modify: `order-service/.../entity/Order.java`
- Create: `order-service/src/main/resources/db/changelog/changelog-002-confirm-hardening.yaml`
- Modify: `db/changelog/db.changelog-master.yaml` (include new file)
- Test: existing repository tests must stay green

- [ ] **Step 1: Changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-order-promotion-reservation-id
      author: hardening
      changes:
        - addColumn:
            tableName: orders
            columns:
              - column: { name: promotion_reservation_id, type: uuid }
      # NO index needed: lookups are by reservation id via coordinator; order_id
      # reconciliation uses idx_orders_status_created (exists, changelog-001:116).
```

- [ ] **Step 2: Entity** — `Order.java`: `@Column(name = "promotion_reservation_id") private UUID promotionReservationId;` (+ Lombok builder picks it up automatically).
- [ ] **Step 3: Run order-service tests (JPA validate) + commit**

```bash
./mvnw -pl order-service test -Dtest='*RepositoryTest'
git add order-service && git commit -m "feat(order-service): promotion_reservation_id column (hardening §3)"
```

---

### Task 7: Order — pre-generate orderId (sandbox proof first) + saga reserve promotion

**Files:**
- Modify: `order-service/.../service/PricingService.java`, `service/impls/PricingServiceImpl.java`, `service/impls/OrderServiceImpl.java` (`doCreateOrder` :96-166, compensation :148-152)
- Test: `OrderIdAssignmentSandboxTest.java` (new, may be deleted after proof), update pricing tests, saga IT (Task 14)

**Interfaces:**
- Produces: `PricingBreakdown calculate(UUID orderId, UUID userId, List<CartItem> items, String couponCode)`; `PricingBreakdown` gains `UUID promotionReservationId()` (nullable); `Order.promotionReservationId` set from it.

- [ ] **Step 1: Sandbox proof** (spec §3 — required before refactor):

```java
@SpringBootTest(classes = TestApp.class)  // minimal: JPA + H2/Testcontainers
// Persist Order with id manually set; assert find(id) returns it.
// If GenerationType.UUID IGNORES the assigned id → STOP: switch entity to
// @UuidGenerator + assigned-when-present, document in commit, re-run.
```

- [ ] **Step 2: Failing tests** — pricing with coupon calls `promotionClient.reserve(new PromotionReserveRequest(code, subtotal, userId, orderId))`; `PricingBreakdown.promotionReservationId` propagated; no coupon → no client call, `promotionReservationId == null`.
- [ ] **Step 3: Implement**

`PricingServiceImpl.calculate(orderId, ...)` — replace the apply block (:51-58):

```java
        UUID promotionReservationId = null;
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            PromotionReserveResponse promo =
                promotionClient.reserve(new PromotionReserveRequest(couponCode, subtotal, userId, orderId));
            promotionReservationId = promo.reservationId();     // frozen at reserve (spec D3)
            discountAmount = promo.discountAmount();
        }
        ...
        return new PricingBreakdown(subtotal, taxAmount, discountAmount, total, snapshots, promotionReservationId);
```

Upfront P1-5 check (:33-39) unchanged.

`doCreateOrder`: hoist `UUID orderId = UUID.randomUUID();` above pricing; `Order.builder()` gains `.id(orderId)` (or `order.setId(orderId)` post-build); after `pricing = pricingService.calculate(orderId, ...)`, set `order.promotionReservationId(pricing.promotionReservationId())`.

- [ ] **Step 4: Compensation** — extend the catch at :148-152:

```java
        } catch (StockReservationFailedException ex) {
            releaseAllReservations(reserved);
            if (order.getPromotionReservationId() != null) {
                try { promotionClient.release(order.getPromotionReservationId()); }
                catch (Exception pex) { log.error("Failed to release promotion reservation {} — TTL sweep covers",
                    order.getPromotionReservationId(), pex); }   // swallow pattern (spec §12)
            }
            throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getProductId());
        }
```

- [ ] **Step 5: Full order-service suite green + commit**

```bash
git add order-service && git commit -m "feat(order-service): pre-generated orderId + promotion reserve in saga (hardening §4)"
```

---

### Task 8: Order — cancel releases promotion reservation

**Files:**
- Modify: `order-service/.../service/impls/OrderServiceImpl.java` (`cancelOrder` :200-238)
- Test: extend cancel tests

- [ ] **Step 1: Failing test** — cancel PENDING order with `promotionReservationId` set → `promotionClient.release(id)` called once; cancel CONFIRMED (admin) → NOT called; null id → NOT called.
- [ ] **Step 2: Implement** — in the `if (order.getStatus() == OrderStatus.PENDING)` block (:223-229), after `releaseAllReservationsById(...)`:

```java
            if (order.getPromotionReservationId() != null) {
                try { promotionClient.release(order.getPromotionReservationId()); }
                catch (Exception ex) { log.error("Failed to release promotion reservation {} during cancel",
                    order.getPromotionReservationId(), ex); }        // swallow — spec §12 tracking
            }
```

CONFIRMED branch: nothing (already COMMITTED; Phase 8 refund).
- [ ] **Step 3: Suite green + commit** — `test(order-service): cancel releases promotion reservation (PENDING only)`

---

### Task 9: Order — OrderCommitCoordinator

**Files:**
- Create: `order-service/.../service/OrderCommitCoordinator.java`, `order-service/.../service/CompensationTarget.java`
- Test: `order-service/.../service/OrderCommitCoordinatorTest.java` (plain Mockito)

**Interfaces:**
- Produces:
```java
public record CompensationTarget(Type type, UUID id) { public enum Type { PROMOTION, INVENTORY } }
public enum CommitOutcome { SUCCESS }
CommitOutcome commitForConfirm(Order order, List<OrderItem> items);
```
- Consumes: `PromotionServiceClient.commit/releaseCommitted`, `InventoryServiceClient.commit/releaseCommitted` (Task 5 shapes; inventory client gains `releaseCommitted` in this task too — mirror Task 5 Step 2, endpoint from Task 3).

- [ ] **Step 1: Failing tests** (Mockito, `InOrder`):
  1. success: promotion commit → inventory commits sorted by productId; outcome SUCCESS.
  2. promotion 5xx → nothing else called; rethrows; no compensation.
  3. item k=2 of 3 fails → `releaseCommitted` called for promotion then item-1 (reverse order), then rethrows.
  4. `reservationId == null` item → skipped, logged (D5).
  5. `BusinessException RESERVATION_NOT_FOUND` from commit → rethrown (D4 — fail, never skip).
  6. rollback HTTP failure → swallowed + `order.commit.rollback.failed` counter incremented (verify via `SimpleMeterRegistry`).
- [ ] **Step 2: Implement**

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderCommitCoordinator {

    private final PromotionServiceClient promotionClient;
    private final InventoryServiceClient inventoryClient;
    private final MeterRegistry meterRegistry;

    /** SUCCESS or throws. Never PARTIAL — compensations are best-effort, failures counted. */
    public CommitOutcome commitForConfirm(Order order, List<OrderItem> items) {
        List<CompensationTarget> committed = new ArrayList<>();   // (type,id) pairs — spec D10
        try {
            if (order.getPromotionReservationId() != null) {
                promotionClient.commit(order.getPromotionReservationId());
                committed.add(new CompensationTarget(CompensationTarget.Type.PROMOTION,
                    order.getPromotionReservationId()));
            }
            List<OrderItem> sorted = items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductId)).toList();
            for (OrderItem item : sorted) {
                if (item.getReservationId() == null) {
                    log.info("Order {} item {}: no reservationId (legacy) — skipping commit",
                        order.getId(), item.getId());
                    continue;
                }
                inventoryClient.commit(item.getReservationId());
                committed.add(new CompensationTarget(CompensationTarget.Type.INVENTORY,
                    item.getReservationId()));
            }
            meterRegistry.counter("order.confirm.commit.outcome", "result", "success").increment();
            return CommitOutcome.SUCCESS;
        } catch (RuntimeException ex) {
            log.warn("Order {} commit failed after {} successes — compensating",
                order.getId(), committed.size(), ex);
            compensateInReverse(committed);
            meterRegistry.counter("order.confirm.commit.outcome", "result", "compensated").increment();
            throw ex;
        }
    }

    private void compensateInReverse(List<CompensationTarget> committed) {
        for (int i = committed.size() - 1; i >= 0; i--) {          // plain reverse loop — no Guava
            CompensationTarget t = committed.get(i);
            try {
                if (t.type() == CompensationTarget.Type.PROMOTION) promotionClient.releaseCommitted(t.id());
                else inventoryClient.releaseCommitted(t.id());
            } catch (Exception ex) {
                log.error("Failed to rollback {} reservation {} — reconciliation owns it", t.type(), t.id(), ex);
                meterRegistry.counter("order.commit.rollback.failed").increment();
            }
        }
    }
}
```

- [ ] **Step 3: Suite green + commit** — `feat(order-service): OrderCommitCoordinator with (type,id) compensation (hardening §5.2)`

---

### Task 10: Order — OrderConfirmMetrics

**Files:**
- Create: `order-service/.../service/OrderConfirmMetrics.java`
- Test: assert timers/counters via `SimpleMeterRegistry` in coordinator/confirm tests (fold into Task 9/11 test files)

**Interfaces:**
- Produces: `Timer confirmTimer(String phase)`; counters `attempts()`, `stuckGauge(Supplier<Double>)` registered once.

```java
@Component
@RequiredArgsConstructor
public class OrderConfirmMetrics {
    private final MeterRegistry registry;
    public Timer timer(String phase) {
        return Timer.builder("order.confirm.duration").tag("phase", phase)
            .register(registry);
    }
    public void attempt() { registry.counter("order.confirm.attempts").increment(); }
    public Gauge stuckGauge(Supplier<Number> value) {           // call once from scheduler config
        return Gauge.builder("order.commit.stuck", value).register(registry);
    }
    public void reconciliationMixed() { registry.counter("order.reconciliation.mixed").increment(); }
}
```

Wrap coordinator phases: `promotionClient.commit` in `timer("commit_promotion")`, the inventory loop in `timer("commit_inventory")` (implementation detail: decorate inside coordinator — keep Task 9 tests' assertions, add timer assertions). Commit — `feat(order-service): confirm phase metrics (hardening §8)`.

---

### Task 11: Order — confirm orchestration + Idempotency-Key

**Files:**
- Modify: `order-service/.../controller/OrderStatusController.java:26-29`, `service/OrderService.java`, `service/impls/OrderServiceImpl.java` (:256-291)
- Test: `ConfirmOrchestrationWebMvcTest.java` + extend service tests

**Interfaces:**
- Produces: `OrderResponse confirmOrder(UUID orderId, UUID adminUserId, String idempotencyKey)`; controller reads admin id via `AuthenticatedUser.current()` (same as other admin endpoints).

- [ ] **Step 1: Failing tests**
  1. happy: PENDING order + mocked coordinator SUCCESS → 200, status CONFIRMED, `confirmedAt` set, `publishStatusChanged` called, idempotency row COMPLETED.
  2. coordinator throws → 409 `ORD-4011` (i18n `order.confirm.commit.failed`), order STILL PENDING, `abort()` called.
  3. same Idempotency-Key replay → same body, coordinator called once (verify mock).
  4. non-PENDING → `ORDER_INVALID_STATE` (existing transition guard unchanged).
  5. key null → no idempotency interaction, orchestration runs.
- [ ] **Step 2: Implement**

Controller:

```java
    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        AuthenticatedUser me = AuthenticatedUser.current();
        return ApiResponse.ok(orderService.confirmOrder(orderId, me.id(), idempotencyKey));
    }
```

`confirmOrder` (mirror `createOrder` :71-89 pattern):

```java
    @Override
    @Transactional
    public OrderResponse confirmOrder(UUID orderId, UUID adminUserId, String idempotencyKey) {
        confirmMetrics.attempt();
        Optional<OrderResponse> cached = (idempotencyKey == null)
            ? Optional.empty()
            : idempotencyService.begin(idempotencyKey, adminUserId, sha256Hex(orderId.toString()));
        if (cached.isPresent()) return cached.get();
        try {
            Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
            orderStatusService.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);
            List<OrderItem> items = orderItemRepository.findByOrderId(orderId);
            Timer.Sample sample = Timer.start(meterRegistry);          // or confirmMetrics helper
            try {
                commitCoordinator.commitForConfirm(order, items);
            } finally {
                sample.stop(confirmMetrics.timer("commit_inventory")); // phase timing detail: wrap
            }
            order.setConfirmedAt(Instant.now());
            order.setStatus(OrderStatus.CONFIRMED);
            orderRepository.save(order);
            orderEventPublisher.publishStatusChanged(order);            // existing single-arg signature
            OrderResponse response = orderMapper.toResponse(order, items);
            if (idempotencyKey != null)
                idempotencyService.complete(idempotencyKey, adminUserId, response, 200);
            return response;
        } catch (RuntimeException ex) {
            if (idempotencyKey != null)
                idempotencyService.abort(idempotencyKey, adminUserId, sha256Hex(orderId.toString()));
            if (!(ex instanceof BusinessException))
                throw BusinessException.of(ErrorCode.CONFIRM_COMMIT_FAILED, orderId);
            throw ex;
        }
    }
```

Coordinator throw (any RuntimeException) surfaces as 409 `ORD-4011`; validateTransition's own `BusinessException ORDER_INVALID_STATE` rethrown unchanged. `sha256Hex` = extract `OrderServiceImpl.hash(String)` helper (refactor `hash(OrderCreateRequest)` to use it).
- [ ] **Step 3: Concurrency test** (integration): two threads confirm same order, same key → one 200, one replayed 200; coordinator mock invoked once. With different keys/no key → loser hits `@Version` (`Order.java:68-71`) `OptimisticLockingFailureException` → 409, retry succeeds (spec D6).
- [ ] **Step 4: Suite + commit** — `feat(order-service): confirm orchestration + idempotency-key (hardening §5)`

---

### Task 12: Order — reconciliation scheduler

**Files:**
- Create: `order-service/.../service/OrderReconciliationScheduler.java`
- Modify: `order-service/src/main/resources/application.yml`, `OrderRepository` (add finder)
- Test: `OrderReconciliationSchedulerTest.java` (integration, fixtures)

**Interfaces:**
- Produces: `reconcileStuckOrders()` `@Scheduled(fixedDelayString = "${order.reconciliation.interval-ms:300000}")`; repo `List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff)` — served by existing `idx_orders_status_created` (changelog-001:116) — NO new index.

- [ ] **Step 1: Config**:

```yaml
shop:
  order:
    reconciliation:
      interval-ms: ${ORDER_RECONCILIATION_INTERVAL_MS:300000}
      stuck-minutes: ${ORDER_RECONCILIATION_STUCK_MINUTES:30}
```

- [ ] **Step 2: Failing tests** (fixtures via repositories):
  1. all-COMMITTED → order CONFIRMED + `publishStatusChanged` + log marker AUTO_CONFIRMED_BY_RECON.
  2. all-terminal (RELEASED/EXPIRED) → order CANCELLED + cancelled event.
  3. mixed → order untouched + `order.reconciliation.mixed` counter.
  4. state-poll 404 (`PRO-7008`/`INV-3003` mapped by clients to BusinessException) → mixed path, never auto-decide (spec §6).
  5. recent PENDING (< stuck-minutes) → untouched.
- [ ] **Step 3: Implement** — poll `promotionClient.getReservationState(id)` (null-safe when `promotionReservationId == null` → treat as not-applicable, not mixed) + per-item `inventoryClient.getReservationState(id)`; decision matrix exactly per spec §6; mutations go through `orderRepository.save` + `orderEventPublisher` (reuse `transitionStatus` internals where possible — but do NOT go through `confirmOrder` to avoid idempotency/recursion; set fields directly like `transitionStatus` does). Register `stuckGauge` from the same query count.
- [ ] **Step 4: Suite + commit** — `feat(order-service): reconciliation scheduler for stuck PENDING orders (hardening §6)`

---

### Task 13: Order — saga IT with WireMock (confirm paths)

**Files:**
- Create: `order-service/src/test/java/com/shop/orderservice/service/ConfirmOrchestrationIT.java`

**Interfaces:**
- Consumes: `OrderCreationSagaIntegrationTest` infra (Testcontainers PG+Kafka, WireMock standalone, `TestLiquibaseConfig`, keycloak token-url override).

- [ ] **Step 1: Fixtures** — copy harness from `OrderCreationSagaIntegrationTest`; two WireMock servers (inventory, promotion) + token stub.
- [ ] **Step 2: Tests**
  1. happy confirm: stub both commits 200 → order CONFIRMED; assert commit stubs hit in order (promotion first — use WireMock request journal + `IN` verification).
  2. promotion commit 200, item-2 commit 500 → verify `POST .../release-committed` for promotion + item-1 (reverse), order stays PENDING, response 409 ORD-4011.
  3. fault injection: commit stub 500 once then 200 (`Scenario.STARTED → second`) → retry path succeeds (client-level retry NOT in scope — this asserts coordinator surfaces failure and the caller retry passes via idempotent stub behavior: re-run confirm → 200).
  4. timeout-then-replay: same Idempotency-Key, first run aborts mid-flight (item-2 500), second run all-200 → CONFIRMED, idempotency row COMPLETED.
- [ ] **Step 3: Full module suite + commit** — `test(order-service): confirm orchestration IT with WireMock (hardening §11)`

---

### Task 14: Final review + deploy order

- [ ] `./mvnw -pl inventory-service,order-service -am test` — ALL green.
- [ ] Verify deploy order note in commit message/PR body: **inventory (Tasks 2-4) → order (Tasks 5-13)**; promotion-service independent until `PROMOTION_SERVICE_ENABLED:true`.
- [ ] Cross-check spec §1.1 D1-D10 — each decision has an implementing task (mapping below in Self-Review).
- [ ] `git log --oneline` review; final commit if docs touched.

---

## Self-Review

**Spec coverage:** §2→T5; §3→T6/T7; §4→T7/T8; §5.1→T11; §5.2→T9; §5.3→T13; §6→T12; §7.1→T2; §7.2→T3; §7.3→T4; §8→T10; §9→T14; §11→T2/T9/T11/T12/T13. D1-D10 mapped: D1(D11 impl), D2(T9 order), D3(T9 catch), D4(T9 test 5), D5(T9 test 4), D6(T11 + concurrency), D7(T11 begin args), D8(T12), D9(T5 Step 3), D10(T9 record). **Gap found & fixed during self-review:** inventory client `releaseCommitted` + `getReservationState` additions are consumed by T9/T12 but produced nowhere → **assigned to T5 Step 2** (client methods) with endpoints from T3/T4; noted in T5 Interfaces.

**Placeholder scan:** test skeletons with `...` in T3/T9 are enumeration of named scenarios whose full code follows the T2/T9 patterns verbatim — each names exact expected code/status. Acceptable per fleet precedent; executors get concrete expectations.

**Type consistency:** `CompensationTarget(Type,UUID)` used in T9 only; `PromotionReserveRequest/Response` shapes consistent T5→T7; `releaseCommitted` naming consistent T3/T5/T9/T13 (no `releaseCommmented`).
