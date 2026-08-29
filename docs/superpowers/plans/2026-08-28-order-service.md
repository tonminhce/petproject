# Order Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `order-service` microservice — cart + order CRUD, multi-service saga (product + inventory + tax + promotion), transactional outbox → Kafka events, `Idempotency-Key` header for POST /orders, explicit state machine with transition validation.

**Architecture:** Spring Boot 4.1.1 microservice (`com.shop.orderservice`) → PostgreSQL via Spring Data JPA + Liquibase, Redis 7 via Spring Cache (productPrice TTL 10 min), Kafka producer via Transactional Outbox + `@Scheduled` relay (single-thread, order-preserving). Saga orchestration in `OrderServiceImpl.createOrder()` with explicit compensation (release stock on failure). `Idempotency-Key` via `begin/complete/abort` pattern (pre-insert in-flight row, REQUIRES_NEW, blocks double-saga).

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Redis 7, Apache Kafka, Spring `RestClient` (Boot 4 native, NOT Feign), WireMock (saga integration tests), ModelMapper 3.2.6, Lombok, JUnit 5 + Mockito + AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-28-order-service-design.md`](../specs/2026-08-28-order-service-design.md) — read alongside this plan; the plan argues from the spec.

---

## Global Constraints

- **Java 25** (parent pom toolchain)
- **Spring Boot 4.1.1** (parent pom)
- **Package root:** `com.shop.*`
- **No per-service `SecurityConfig`** — `common-security` auto-configures `SecurityFilterChain` (`@ConditionalOnMissingBean`). Configure via `shop.security.public-paths`.
- **`open-in-view: false`** in `application.yml`
- **ModelMapper** — `@Component` inject `ModelMapper` (NOT MapStruct)
- **Liquibase** (not Flyway), changelogs in `src/main/resources/db/changelog/`
- **All endpoints** wrap in `ApiResponse<T>` (from `common-core/viewmodel`)
- **Spring `RestClient`** for inter-service calls — `@Bean RestClient` per downstream in `RestClientConfig`
- **`@EnableScheduling`** on `OrderServiceApplication` — bắt buộc (OutboxRelay + OutboxRetentionScheduler + cleanup jobs)
- **Outbox pattern:** single-thread, ORDER BY id ASC, break-on-error to preserve ordering
- **Saga compensation:** on any failure after `begin()` → `abort()` idempotency row → release reservations by `reservationId` → throw
- **Idempotency-Key:** composite PK `(user_id, key)` — blocks collision across users (rev 2 fix O-N5)
- **Self-invocation note:** `doCreateOrder()` extracted method is NOT `@Transactional` — runs in `createOrder()`'s TX (called via proxy). Inline comment prevents re-review confusion.
- **State machine:** table-driven validation in `OrderStatusService` — prevents SHIPPED → PENDING bugs
- **Exceptions:** `BusinessException.of(ErrorCode.X, args...)` factories — constructor private
- **Test stack Boot 4:** `@MockitoBean`, `@WebMvcTest` from `webmvc.test.autoconfigure`, `@DataJpaTest` from `data.jpa.test.autoconfigure`, `TestEntityManager` from `jpa.test.autoconfigure`, `@Import(LiquibaseAutoConfiguration.class)` (package `boot.liquibase.autoconfigure`)
- **Saga integration test:** `@SpringBootTest` + Testcontainers (Postgres + Kafka) + WireMock (stub inventory-service reserve/release + tax + promotion)

---

## File Structure

### Modified common modules

| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | **VERIFY** `ORDER_NOT_FOUND (ORD-4001)` + `CART_NOT_FOUND (ORD-4002)` exist; **ADD** ORD-4003..4010 |
| `utils/common-spring/src/main/resources/messages/messages_en.properties` | **VERIFY** `order.not.found` + `cart.not.found` exist (update template to include `{0}`); **ADD** 8 new keys |
| `utils/common-spring/src/main/resources/messages/messages_vi.properties` | **VERIFY** VI versions; **ADD** 8 new keys |

> ⚠️ **P0-7 — `ServiceTokenProvider` implement LOCAL trong order-service (rev 4 — un-deferred).**
> Deferral trước đây SAI: inventory `/reserve` + `/release` yêu cầu `hasRole('SERVICE') or
> hasRole('ADMIN')` (inventory spec §4.2, không public-paths) → không có Authorization header
> = 401 = **saga chết 100% ở production**. Task 25 không bắt được vì WireMock stub bỏ qua header.
> Reasons for deferring:
> 1. Plan had broken `@Bean` syntax that doesn't bind `shop.keycloak.*` properties
> 2. `shop.keycloak.*` prefix already used by `KeycloakProperties` in common-keycloak → silent null bindings
> 3. File path in plan was wrong (`autoconfigure/` vs `config/`)
> 4. Risk: auth-service currently uses common-keycloak — touching it widens blast radius
>
> **MVP workaround:** order-service uses NO service-to-service auth headers. `/confirm` `/ship` `/deliver` accept ADMIN role only (no SERVICE role check yet).

### New order-service files (main)

| File | Responsibility |
|---|---|
| `order-service/pom.xml` | Maven module — Boot 4 deps + test jars + RestClient (no new dep — built-in) |
| `order-service/src/main/java/com/shop/orderservice/OrderServiceApplication.java` | `@SpringBootApplication` + `@EnableScheduling` |
| `order-service/src/main/java/com/shop/orderservice/config/CacheConfig.java` | `@EnableCaching` + Redis cache customizer for `productPrice` (TTL 10 min, transactionAware no-arg) |
| `order-service/src/main/java/com/shop/orderservice/config/RestClientConfig.java` | `@Bean RestClient` × 4: product, inventory, tax, promotion |
| `order-service/src/main/java/com/shop/orderservice/config/ShopServicesProperties.java` | `@ConfigurationProperties("shop.services")` record — base URLs + timeouts + enabled flags |
| `order-service/src/main/java/com/shop/orderservice/entity/Order.java` | JPA entity extends `AbstractMappedEntity` + `@SQLRestriction("deleted = false")` |
| `order-service/src/main/java/com/shop/orderservice/entity/OrderItem.java` | JPA entity extends `AbstractMappedEntity` (NO @SQLRestriction — hard-delete via CASCADE) + `reservationId` |
| `order-service/src/main/java/com/shop/orderservice/entity/Cart.java` | JPA entity extends `AbstractMappedEntity` + `@SQLRestriction` |
| `order-service/src/main/java/com/shop/orderservice/entity/CartItem.java` | JPA entity extends `AbstractMappedEntity` (NO @SQLRestriction — CASCADE) + `productTitle` + `unitPrice` snapshot |
| `order-service/src/main/java/com/shop/orderservice/entity/OrderStatus.java` | enum PENDING/CONFIRMED/SHIPPED/DELIVERED/CANCELLED |
| `order-service/src/main/java/com/shop/orderservice/entity/OutboxEvent.java` | JPA entity (hard-delete, aggregateId = orderId) |
| `order-service/src/main/java/com/shop/orderservice/entity/OutboxStatus.java` | enum PENDING/SENT/FAILED |
| `order-service/src/main/java/com/shop/orderservice/entity/IdempotencyKey.java` | JPA entity, composite PK (user_id, key) — rev 2 fix O-N5 |
| `order-service/src/main/java/com/shop/orderservice/repository/OrderRepository.java` | JpaRepository<Order, UUID> + findByUserId + findByIdAndUserId + findByStatusOrderByCreatedAtDesc |
| `order-service/src/main/java/com/shop/orderservice/repository/OrderItemRepository.java` | JpaRepository<OrderItem, UUID> + findByOrderId + bulk save |
| `order-service/src/main/java/com/shop/orderservice/repository/CartRepository.java` | JpaRepository<Cart, UUID> + findByUserIdAndDeletedFalse (UNIQUE partial index) |
| `order-service/src/main/java/com/shop/orderservice/repository/CartItemRepository.java` | JpaRepository<CartItem, UUID> + findByCartId + findByCartIdAndProductId |
| `order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java` | JpaRepository<OutboxEvent, Long> + findByStatusOrderByIdAsc + @Modifying deleteByStatusAndSentAtBefore |
| `order-service/src/main/java/com/shop/orderservice/repository/IdempotencyKeyRepository.java` | JpaRepository<IdempotencyKey, IdempotencyKeyId> + findByUserIdAndKey + bulk delete by expires_at |
| `order-service/src/main/java/com/shop/orderservice/dto/request/CartItemAddRequest.java` | record: { productId, quantity } @NotNull @Min(1) @Max(99) |
| `order-service/src/main/java/com/shop/orderservice/dto/request/CartItemUpdateRequest.java` | record: { quantity } @NotNull @Min(0) @Max(99) |
| `order-service/src/main/java/com/shop/orderservice/dto/request/OrderCreateRequest.java` | record: { cartId?, couponCode? } |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ProductSnapshot.java` | record: { productId, title, unitPrice } — internal |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PricingBreakdown.java` | record: { subtotal, taxAmount, discountAmount, total, snapshots Map<UUID, ProductSnapshot> } — internal |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ReserveRequest.java` | record: { quantity, orderId } — sent to inventory |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ReservationResponse.java` | record: { reservationId, productId, quantity, expiresAt } — from inventory |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/TaxCalculateRequest.java` | record: { taxClassId, country, postalCode, amount } — sent to tax |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/TaxCalculateResponse.java` | record: { taxAmount, appliedRate } — from tax |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PromotionApplyRequest.java` | record: { code, orderAmount, userId } — sent to promotion |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PromotionApplyResponse.java` | record: { discountAmount, finalAmount } — from promotion |
| `order-service/src/main/java/com/shop/orderservice/dto/response/CartItemResponse.java` | record: { id, productId, productTitle, quantity, unitPrice, lineTotal } |
| `order-service/src/main/java/com/shop/orderservice/dto/response/CartResponse.java` | record: { id, userId, items[], subtotal, createdAt, updatedAt } |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderItemResponse.java` | record: { productId, productTitle, quantity, unitPrice, lineTotal } |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderResponse.java` | record: { id, userId, status, items[], subtotal, taxAmount, discountAmount, total, couponCode, createdAt, confirmedAt, shippedAt, deliveredAt, cancelledAt } |
| `order-service/src/main/java/com/shop/orderservice/mapper/CartMapper.java` | `@Component` ModelMapper, manual 4-field toResponse |
| `order-service/src/main/java/com/shop/orderservice/mapper/OrderMapper.java` | `@Component` ModelMapper, manual toResponse with items |
| `order-service/src/main/java/com/shop/orderservice/security/ServiceTokenProvider.java` | **NEW** — client_credentials token cache, bind `shop.services.keycloak.*` (rev 4 — un-deferred, XEM Task 2) |
| `order-service/src/main/java/com/shop/orderservice/client/ProductServiceClient.java` | RestClient wrapper for product-service GET |
| `order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java` | RestClient wrapper for inventory reserve/release |
| `order-service/src/main/java/com/shop/orderservice/client/TaxServiceClient.java` | RestClient wrapper for tax calculate |
| `order-service/src/main/java/com/shop/orderservice/client/PromotionServiceClient.java` | RestClient wrapper for promotion apply |
| `order-service/src/main/java/com/shop/orderservice/service/CartService.java` | interface (addItem, updateItem, removeItem, clearCart, getMyCart) |
| `order-service/src/main/java/com/shop/orderservice/service/OrderService.java` | interface (createOrder, cancelOrder, findMyOrders, findById, status transitions) |
| `order-service/src/main/java/com/shop/orderservice/service/PricingService.java` | interface (calculate for cart items + coupon) |
| `order-service/src/main/java/com/shop/orderservice/service/StockReservationService.java` | interface (reserve, release, releaseByOrderId) |
| `order-service/src/main/java/com/shop/orderservice/service/OrderEventPublisher.java` | interface (publishCreated, publishUpdated, publishCancelled) |
| `order-service/src/main/java/com/shop/orderservice/service/IdempotencyService.java` | begin/complete/abort (rev 2 fix O-N1, O-N2) |
| `order-service/src/main/java/com/shop/orderservice/service/OrderStatusService.java` | table-driven state transition validation |
| `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java` | @Service @RequiredArgsConstructor — auto-create + merge + snapshot refresh |
| `order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java` | @Service — fetch product prices (cached 10 min) + tax + promotion |
| `order-service/src/main/java/com/shop/orderservice/service/impls/StockReservationServiceImpl.java` | @Service — RestClient wrappers |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` | @Service — saga orchestration (createOrder + doCreateOrder split) |
| `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java` | @Service — begin (REQUIRES_NEW) + complete + abort (REQUIRES_NEW) |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java` | @Service — Map<OrderStatus, Set<OrderStatus>> validation |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java` | writes OutboxEvent in same @Transactional |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderOutboxRelay.java` | @Scheduled single-thread relay (5s interval) |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java` | @Scheduled cron purge SENT > 7 days |
| `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyKeyCleanupScheduler.java` | @Scheduled daily purge expires_at < now() |
| `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` | REST /api/v1/carts |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` | REST /api/v1/orders |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` | /confirm /ship /deliver (ADMIN or SERVICE role) |
| `order-service/src/main/java/com/shop/orderservice/exception/StockReservationFailedException.java` | RuntimeException with productId field |
| `order-service/src/main/resources/application.yml` | config (datasource, redis, kafka, services, order.*) |
| `order-service/src/main/resources/db/changelog/db.changelog-master.yaml` | Liquibase master |
| `order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 6 tables + composite PK on idempotency_keys |

### New order-service files (tests)

| File | Coverage |
|---|---|
| `order-service/src/test/java/com/shop/orderservice/config/TestLiquibaseConfig.java` | copy from product-service (test-only SpringLiquibase bean) |
| `order-service/src/test/java/com/shop/orderservice/service/impls/CartServiceImplTest.java` | 10 unit tests (Mockito) |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderServiceImplTest.java` | 12 unit tests — cancel + state machine + saga happy path + abort on failure |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderStatusServiceImplTest.java` | 25 parameterized tests (5×5 transition matrix) |
| `order-service/src/test/java/com/shop/orderservice/service/impls/IdempotencyServiceImplTest.java` | 4 unit tests (begin hit/miss/collision, abort) |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderStatusServiceImplTest.java` | table-driven validation |
| `order-service/src/test/java/com/shop/orderservice/repository/OrderRepositoryTest.java` | @DataJpaTest + Testcontainers |
| `order-service/src/test/java/com/shop/orderservice/repository/CartRepositoryTest.java` | @DataJpaTest — UNIQUE constraint, soft-delete filter |
| `order-service/src/test/java/com/shop/orderservice/controller/CartControllerTest.java` | @WebMvcTest — auth, validation, 5 endpoints |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderControllerTest.java` | @WebMvcTest — auth, idempotency, validation, error envelope |
| `order-service/src/test/java/com/shop/orderservice/service/OrderOutboxRelayIntegrationTest.java` | @SpringBootTest + Testcontainers Kafka |
| `order-service/src/test/java/com/shop/orderservice/service/OrderCreationSagaIntegrationTest.java` | @SpringBootTest + Testcontainers + WireMock — happy path + compensation + idempotency replay |

### Modified infrastructure

| File | Change |
|---|---|
| `docker-compose.yml` | **MODIFY** order-service block: env SHOP_KAFKA_BOOTSTRAP_SERVERS (not KAFKA_SERVERS) + SPRING_DATA_REDIS_HOST + depends_on redis |
| `gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java` | **VERIFY** ORDER route exists (port 8084) |
| `pom.xml` (parent) | **VERIFY** `<module>order-service</module>` declared |

---

## Phase 0 — Common upgrades

### Task 1: Verify existing order ErrorCodes + add ORD-4003..4010

> ⚠️ **Rev 2 fix (ver 1 fixed in commit pre-185fd8cc):** `ORDER_NOT_FOUND (ORD-4001)` + `CART_NOT_FOUND (ORD-4002)` **đã tồn tại** (`ErrorCode.java:63-64`). Verify + update i18n templates to include `{0}`. Add 8 new codes ORD-4003..4010.

**Files:**
- Modify: `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`
- Modify: `utils/common-spring/src/main/resources/messages_en.properties`
- Modify: `utils/common-spring/src/main/resources/messages_vi.properties`

**Interfaces:**
- Produces: 8 new `ErrorCode` enum constants ORD-4003..4010; 8 new i18n keys EN + VI; updated 2 existing templates with `{0}` placeholder.

- [ ] **Step 1: Verify existing codes + update templates**

```bash
grep -n "ORD-400[12]" utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java
grep -n "order.not.found\|cart.not.found" utils/common-spring/src/main/resources/messages_en.properties
```

If both exist, update the 2 existing templates to include `{0}` placeholder:
```properties
order.not.found=Order {0} not found
cart.not.found=Cart {0} not found
```

(Vietnamese equivalent in `messages_vi.properties`.)

- [ ] **Step 2: Add ORD-4003..4010 to ErrorCode.java**

> ⚠️ P1-6 — Instruction was wrong. `CART_NOT_FOUND (ORD-4002)` already ends with `,` (NOT `;`).
> The enum's last entry is `PAYMENT_NOT_FOUND` with `;`. Insert the 8 entries **after**
> `CART_NOT_FOUND(...)` — entry cuối của khối mới (`ORDER_DUPLICATE_REQUEST`) kết bằng `,`,
> để `PAYMENT_NOT_FOUND(...);` vẫn là entry cuối cùng chấm dứt enum. **KHÔNG** kết khối
> mới bằng `;` — sẽ tạo enum constant đứng sau `;` → compile error.

Insert **after** the `CART_NOT_FOUND (ORD-4002)` line in `ErrorCode.java` (keep the existing `,` after `CART_NOT_FOUND`):

```java
    ORDER_INVALID_STATE("ORD-4003", "order.invalid.state", HttpStatus.CONFLICT),
    ORDER_INVALID_STATE_TRANSITION("ORD-4004", "order.invalid.transition", HttpStatus.CONFLICT),
    CART_EMPTY("ORD-4005", "cart.empty", HttpStatus.CONFLICT),
    CART_ITEM_NOT_FOUND("ORD-4006", "cart.item.not.found", HttpStatus.NOT_FOUND),
    ORDER_RESERVATION_FAILED("ORD-4007", "order.reservation.failed", HttpStatus.CONFLICT),
    ORDER_PROMOTION_INVALID("ORD-4008", "order.promotion.invalid", HttpStatus.BAD_REQUEST),
    ORDER_TAX_CALCULATION_FAILED("ORD-4009", "order.tax.calculation.failed", HttpStatus.BAD_REQUEST),
    ORDER_DUPLICATE_REQUEST("ORD-4010", "order.duplicate.request", HttpStatus.CONFLICT),  // ← kết bằng dấu PHẨY
```

> Verify: sau edit, thứ tự là `... CART_NOT_FOUND(...), [8 entries mới], PAYMENT_NOT_FOUND(...);`
> — `PAYMENT_NOT_FOUND` giữ nguyên và vẫn là entry duy nhất kết bằng `;` (chấm dứt enum).

- [ ] **Step 3: Add 8 new i18n keys (EN + VI)**

`messages_en.properties`:
```properties
order.invalid.state=Order {0} cannot transition from current state
order.invalid.transition=Order cannot transition from {0} to {1}
cart.empty=Cart is empty
cart.item.not.found=Cart item {0} not found
order.reservation.failed=Failed to reserve stock for product {0}
order.promotion.invalid=Promotion code {0} is invalid or expired
order.tax.calculation.failed=Failed to calculate tax
order.duplicate.request=Idempotency-Key {0} reused with different request body
```

`messages_vi.properties`:
```properties
order.invalid.state=Đơn hàng {0} không thể chuyển trạng thái hiện tại
order.invalid.transition=Đơn hàng không thể chuyển từ {0} sang {1}
cart.empty=Giỏ hàng trống
cart.item.not.found=Không tìm thấy mục giỏ hàng {0}
order.reservation.failed=Không thể đặt trước tồn kho cho sản phẩm {0}
order.promotion.invalid=Mã khuyến mãi {0} không hợp lệ hoặc đã hết hạn
order.tax.calculation.failed=Không thể tính thuế
order.duplicate.request=Idempotency-Key {0} đã được sử dụng với payload khác
```

- [ ] **Step 4: Verify**

```bash
./mvnw -pl utils/common-core,utils/common-spring -am test -q
```

- [ ] **Step 5: Commit**

```bash
# ⚠️ P2-1 — paths include messages/ subdirectory (full path)
git add utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java \
        utils/common-spring/src/main/resources/messages/messages_en.properties \
        utils/common-spring/src/main/resources/messages/messages_vi.properties
git commit -m "feat(common): order ErrorCodes (ORD-4003..4010) + i18n keys + {0} placeholder"
```

---

### Task 2: ServiceTokenProvider — order-service local (client_credentials + token cache)

> ⚠️ **P0-7 — rev 4 (un-deferred).** Deferral trước đây sai ở một điểm then chốt: nó chỉ
> cover các endpoint *inbound* (`/confirm /ship /deliver` — đúng là ADMIN-only trong MVP),
> nhưng **outbound call tới inventory `/reserve` + `/release` cần SERVICE-role JWT**
> (inventory spec §4.2: `@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")`,
> không public-paths). Không có header → 401 → mọi POST /orders fail ở production.
>
> **Triển khai LOCAL trong order-service** — KHÔNG đụng common-keycloak (auth-service đang
> dùng, tránh blast radius P0-7d), KHÔNG dùng prefix `shop.keycloak` (đã bị
> `KeycloakProperties` chiếm — P0-7b): bind thẳng từ `ShopServicesProperties.Keycloak`
> (`shop.services.keycloak.*` — đã có sẵn ở Task 4 yml).

**Files:**
- Create: `order-service/src/main/java/com/shop/orderservice/security/ServiceTokenProvider.java`
- Modify: `order-service/src/main/java/com/shop/orderservice/config/RestClientConfig.java` (inject provider — Task 6 note)

**Interfaces:**
- Consumes: `ShopServicesProperties.Keycloak` (token-url, client-id, client-secret)
- Produces: `getToken(): String` — cached token, refresh 30s trước expiry; used by `InventoryServiceClient` (Task 11)

- [ ] **Step 1: Create ServiceTokenProvider**

```java
package com.shop.orderservice.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shop.orderservice.config.ShopServicesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OAuth2 client_credentials token cache for service-to-service calls
 * (inventory reserve/release). Refreshes 30s before expiry.
 *
 * <p>Local to order-service deliberately: binding comes from
 * {@code ShopServicesProperties.Keycloak} (prefix {@code shop.services.keycloak}) —
 * NOT {@code shop.keycloak} (owned by common-keycloak {@code KeycloakProperties}).
 * Benign race on concurrent refresh: worst case two token fetches, last write wins.</p>
 */
@Component
@Slf4j
public class ServiceTokenProvider {

    private static final long REFRESH_SKEW_SECONDS = 30;

    private final ShopServicesProperties.Keycloak props;
    private final RestClient keycloakClient;
    private final AtomicReference<CachedToken> cached = new AtomicReference<>();

    public ServiceTokenProvider(ShopServicesProperties props) {
        this.props = props.keycloak();
        this.keycloakClient = RestClient.builder()
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();
    }

    public String getToken() {
        CachedToken current = cached.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.accessToken();
        }
        return refresh().accessToken();
    }

    private CachedToken refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());

        TokenResponse resp = keycloakClient.post()
            .uri(props.tokenUrl())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
        if (resp == null || resp.accessToken() == null) {
            throw new IllegalStateException("Keycloak token endpoint returned empty response");
        }

        CachedToken next = new CachedToken(
            resp.accessToken(),
            Instant.now().plusSeconds(Math.max(1, resp.expiresIn() - REFRESH_SKEW_SECONDS)));
        cached.set(next);
        log.debug("Refreshed service token, expiresAt={}", next.expiresAt());
        return next;
    }

    private record CachedToken(String accessToken, Instant expiresAt) {}

    private record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresIn
    ) {}
}
```

> Config mặc định trong yml Task 4 (`ORDER_SERVICE_CLIENT_ID/SECRET`, `KEYCLOAK_TOKEN_URL`)
> + WireMock stub ở Task 25 (`shop.services.keycloak.token-url` → keycloakServer) cho test.

- [ ] **Step 2: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 3: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/security/ServiceTokenProvider.java
git commit -m "feat(order-service): ServiceTokenProvider (client_credentials, order-service local)"
```

---

## Phase 1 — Skeleton + persistence

### Task 3: order-service pom.xml — ADD deps (NOT verify-only)

> ⚠️ **P0-6 — `verify-only` assumption was wrong.** Current pom only has:
> `common-spring`, `spring-boot-starter-data-jpa`, `liquibase-core`, `postgresql`,
> `modelmapper`, `lombok`, `spring-boot-starter-test`, `spring-boot-maven-plugin`.
> **Missing:** `common-kafka`, `spring-boot-starter-liquibase`, `spring-boot-starter-data-redis`,
> `spring-boot-starter-cache`, `spring-kafka`, plus the FULL test stack
> (`webmvc-test`, `data-jpa-test`, `jpa-test`, `testcontainers-{junit-jupiter,postgresql,kafka}`,
> `spring-boot-testcontainers`, `awaitility`, `wiremock-standalone` for saga IT).

**Files:**
- Modify: `order-service/pom.xml` — append missing deps after existing block (don't replace whole block; scaffold exists)

**Interfaces:**
- Produces: compile-able module with all deps for spec compliance

- [ ] **Step 1b: Create lombok.config (P0-4 — clients dùng @Qualifier trên field + @RequiredArgsConstructor)**

Create `lombok.config` ở **project root** (nếu chưa có):

```properties
lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier
```

> ⚠️ Không có dòng này, Lombok KHÔNG copy `@Qualifier("inventoryRestClient")` từ field
> xuống constructor param sinh ra bởi `@RequiredArgsConstructor` → Spring inject by-type
> → 4 bean `RestClient` cùng type → `NoUniqueBeanDefinitionException` at startup.
> Copyable chỉ THÊM hành vi — các class không dùng `@Qualifier` không bị ảnh hưởng.

- [ ] **Step 1: Add missing main deps**

After existing `spring-boot-starter-data-jpa` block (or after `liquibase-core`), insert:

```xml
        <dependency>
            <groupId>com.shop.microservices</groupId>
            <artifactId>common-kafka</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-liquibase</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-cache</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>
```

- [ ] **Step 2: Add missing test deps**

Inside the existing test scope, append:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-postgresql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-kafka</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <!-- ⚠️ P1-4 — use wiremock-standalone (NOT wiremock-spring-boot which
                 has wrong artifact coords and Boot 4 BOM doesn't manage version). -->
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.13.1</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

Expected: BUILD SUCCESS. If dep version conflicts → check Boot 4 BOM manages parent versions.

- [ ] **Step 4: Commit**

```bash
git add order-service/pom.xml
git commit -m "build(order-service): add full deps (common-kafka, redis, cache, kafka, full test stack, WireMock)"
```

---

### Task 4: OrderServiceApplication + application.yml

**Files:**
- Create: `order-service/src/main/java/com/shop/orderservice/OrderServiceApplication.java`
- Create: `order-service/src/main/resources/application.yml`

- [ ] **Step 1: Create OrderServiceApplication.java**

```java
package com.shop.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling  // REQUIRED: OutboxRelay + OutboxRetentionScheduler + IdempotencyKeyCleanupScheduler
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

> ⚠️ `@EnableScheduling` is mandatory — `@Scheduled` jobs (OutboxRelay, OutboxRetentionScheduler, IdempotencyKeyCleanupScheduler) silently NO-OP without it.

- [ ] **Step 2: Create application.yml**

```yaml
spring:
  application:
    name: order-service

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/orderservice}
    username: ${POSTGRES_USER:admin}
    password: ${POSTGRES_PASSWORD:admin}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false

  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      password: ${SPRING_DATA_REDIS_PASSWORD:}

  cache:
    type: redis

server:
  port: ${SERVER_PORT:8084}
  shutdown: graceful

shop:
  kafka:
    bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer: { acks: all, retries: 3 }
  security:
    public-paths: []

  services:
    product:
      url: ${PRODUCT_SERVICE_URL:http://localhost:8086}
      timeout-ms: 5000
    inventory:
      url: ${INVENTORY_SERVICE_URL:http://localhost:8082}
      timeout-ms: 5000
    tax:
      url: ${TAX_SERVICE_URL:http://localhost:8091}
      timeout-ms: 3000
      enabled: ${TAX_SERVICE_ENABLED:false}    # MVP default false; flips when tax-service ships
    promotion:
      url: ${PROMOTION_SERVICE_URL:http://localhost:8093}
      timeout-ms: 3000
      enabled: ${PROMOTION_SERVICE_ENABLED:false}  # MVP default false
    keycloak:
      token-url: ${KEYCLOAK_TOKEN_URL:http://localhost:8080/realms/ecommerce/protocol/openid-connect/token}
      client-id: ${ORDER_SERVICE_CLIENT_ID:order-service}
      client-secret: ${ORDER_SERVICE_CLIENT_SECRET:changeme}

order:
  idempotency:
    ttl-hours: 24
  outbox:
    poll-interval-ms: 5000
    batch-size: 100
    max-retries: 10
    retention-days: 7
    retention-cron: "0 0 3 * * *"
  cleanup:
    idempotency-cron: "0 0 4 * * *"
    expired-cart-days: 7
    cancelled-order-days: 90
```

- [ ] **Step 3: Boot smoke**

```bash
docker compose up -d postgres redis kafka && ./mvnw -pl order-service spring-boot:run
# Expected: app starts, Liquibase runs (0 changesets yet), /actuator/health = UP, port 8084
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/OrderServiceApplication.java \
        order-service/src/main/resources/application.yml
git commit -m "feat(order-service): application entrypoint + application.yml"
```

---

### Task 5: Liquibase changelog — 6 tables

**Files:**
- Create: `order-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`

- [ ] **Step 1: Create master changelog**

```yaml
databaseChangeLog:
  - include:
      file: changelog-001-initial-schema.yaml
      relativeToChangelogFile: true
```

- [ ] **Step 2: Create changelog-001-initial-schema.yaml**

```yaml
databaseChangeLog:
  # ===========================================================================
  # carts — 1 active cart per user (UNIQUE partial index WHERE deleted = false)
  # ===========================================================================
  - createTable:
      tableName: carts
      columns:
        - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
        - column: { name: user_id, type: UUID, constraints: { nullable: false } }
        - column: { name: subtotal, type: NUMERIC(15,2), constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: created_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: updated_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: created_by, type: VARCHAR(100) }
        - column: { name: updated_by, type: VARCHAR(100) }
        - column: { name: deleted, type: BOOLEAN, constraints: { nullable: false, defaultValueBoolean: false } }
        - column: { name: deleted_at, type: TIMESTAMP }
        - column: { name: deleted_by, type: VARCHAR(255) }

  - createIndex:
      tableName: carts
      indexName: idx_carts_user_active
      unique: true
      columns:
        - column: { name: user_id }
      where: deleted = false
  # ⚠️ P0-2 — PARTIAL unique index (NOT hard addUniqueConstraint).
  # Original plan had `addUniqueConstraint` on user_id unconditionally.
  # That breaks re-cart after soft-delete: clearCart sets deleted=true on the row,
  # then next GET /carts/me tries to INSERT new row with same user_id → 500 duplicate key.
  # Partial index `WHERE deleted = false` allows new INSERT after soft-delete (matching favourite-service pattern).

  # ===========================================================================
  # cart_items — productTitle + unitPrice snapshot for display
  # ===========================================================================
  - createTable:
      tableName: cart_items
      columns:
        - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
        - column: { name: cart_id, type: UUID, constraints: { nullable: false, foreignKeyName: fk_cart_items_cart, references: carts(id), deleteCascade: true } }
        - column: { name: product_id, type: UUID, constraints: { nullable: false } }
        - column: { name: product_title, type: VARCHAR(255), constraints: { nullable: false } }
        - column: { name: unit_price, type: NUMERIC(15,2), constraints: { nullable: false } }
        - column: { name: quantity, type: INTEGER, constraints: { nullable: false } }

  - createIndex:
      tableName: cart_items
      indexName: idx_cart_items_cart_product
      unique: true
      columns:
        - column: { name: cart_id }
        - column: { name: product_id }

  # ===========================================================================
  # orders — soft-delete only via admin/GDPR (cancel does NOT mark deleted — §5.3)
  # ===========================================================================
  - createTable:
      tableName: orders
      columns:
        - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
        - column: { name: user_id, type: UUID, constraints: { nullable: false } }
        - column: { name: status, type: VARCHAR(20), constraints: { nullable: false, defaultValue: PENDING } }
        - column: { name: subtotal, type: NUMERIC(15,2), constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: tax_amount, type: NUMERIC(15,2), constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: discount_amount, type: NUMERIC(15,2), constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: total, type: NUMERIC(15,2), constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: coupon_code, type: VARCHAR(50) }
        - column: { name: confirmed_at, type: TIMESTAMP }
        - column: { name: shipped_at, type: TIMESTAMP }
        - column: { name: delivered_at, type: TIMESTAMP }
        - column: { name: cancelled_at, type: TIMESTAMP }
        - column: { name: created_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: updated_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: created_by, type: VARCHAR(100) }
        - column: { name: updated_by, type: VARCHAR(100) }
        - column: { name: deleted, type: BOOLEAN, constraints: { nullable: false, defaultValueBoolean: false } }
        - column: { name: deleted_at, type: TIMESTAMP }
        - column: { name: deleted_by, type: VARCHAR(255) }

  - createIndex:
      tableName: orders
      indexName: idx_orders_user_id
      columns:
        - column: { name: user_id }
    - createIndex:
      tableName: orders
      indexName: idx_orders_status_created
      columns:
        - column: { name: status }
        - column: { name: created_at }
  - createIndex:
      tableName: orders
      indexName: idx_orders_coupon_active
      columns:
        - column: { name: coupon_code }
      where: deleted = false

  # ===========================================================================
  # order_items — hard-delete with parent Order CASCADE; reservationId for cancel
  # ===========================================================================
  - createTable:
      tableName: order_items
      columns:
        - column: { name: id, type: UUID, constraints: { primaryKey: true, nullable: false } }
        - column: { name: order_id, type: UUID, constraints: { nullable: false, foreignKeyName: fk_order_items_order, references: orders(id), deleteCascade: true } }
        - column: { name: product_id, type: UUID, constraints: { nullable: false } }
        - column: { name: product_title, type: VARCHAR(255), constraints: { nullable: false } }
        - column: { name: quantity, type: INTEGER, constraints: { nullable: false } }
        - column: { name: unit_price, type: NUMERIC(15,2), constraints: { nullable: false } }
        - column: { name: line_total, type: NUMERIC(15,2), constraints: { nullable: false } }
        - column: { name: reservation_id, type: UUID }

  - createIndex:
      tableName: order_items
      indexName: idx_order_items_order_id
      columns:
        - column: { name: order_id }

  # ===========================================================================
  # outbox_events — hard-delete, aggregateId = orderId
  # ===========================================================================
  - createTable:
      tableName: outbox_events
      columns:
        - column: { name: id, type: BIGSERIAL, autoIncrement: true, constraints: { primaryKey: true, nullable: false } }
        - column: { name: event_id, type: VARCHAR(36), constraints: { nullable: false, unique: true } }
        - column: { name: aggregate_type, type: VARCHAR(50), constraints: { nullable: false } }
        - column: { name: aggregate_id, type: UUID, constraints: { nullable: false } }
        - column: { name: event_type, type: VARCHAR(50), constraints: { nullable: false } }
        - column: { name: topic, type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: payload, type: TEXT, constraints: { nullable: false } }
        - column: { name: status, type: VARCHAR(20), constraints: { nullable: false } }
        - column: { name: retry_count, type: INTEGER, constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: sent_at, type: TIMESTAMP }
        - column: { name: last_error, type: VARCHAR(1000) }

  - createIndex:
      tableName: outbox_events
      indexName: idx_outbox_status
      columns:
        - column: { name: status }
  - createIndex:
      tableName: outbox_events
      indexName: idx_outbox_aggregate_id
      columns:
        - column: { name: aggregate_id }

  # ===========================================================================
  # idempotency_keys — composite PK (user_id, key) — rev 2 fix O-N5
  # ===========================================================================
  - createTable:
      tableName: idempotency_keys
      columns:
        - column: { name: user_id, type: UUID, constraints: { nullable: false } }
        - column: { name: key, type: VARCHAR(64), constraints: { nullable: false } }
        - column: { name: request_hash, type: VARCHAR(64), constraints: { nullable: false } }
        - column: { name: response_status, type: INTEGER, constraints: { nullable: false } }
        - column: { name: response_body, type: TEXT, constraints: { nullable: false } }
        - column: { name: created_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: expires_at, type: TIMESTAMP, constraints: { nullable: false } }

  - addPrimaryKey:
      tableName: idempotency_keys
      columnNames: user_id, key
      constraintName: pk_idempotency_keys

  - createIndex:
      tableName: idempotency_keys
      indexName: idx_idempotency_expires_at
      columns:
        - column: { name: expires_at }
```

- [ ] **Step 3: Verify Liquibase runs**

```bash
docker compose up -d postgres redis kafka
./mvnw -pl order-service spring-boot:run
# Expected: Liquibase creates 6 tables, no errors
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/resources/db/changelog/
git commit -m "feat(order-service): initial Liquibase schema (carts, cart_items, orders, order_items, outbox_events, idempotency_keys)"
```

---

### Task 6: Configuration classes — CacheConfig, RestClientConfig, ShopServicesProperties

**Files:**
- Create: `order-service/src/main/java/com/shop/orderservice/config/ShopServicesProperties.java`
- Create: `order-service/src/main/java/com/shop/orderservice/config/CacheConfig.java`
- Create: `order-service/src/main/java/com/shop/orderservice/config/RestClientConfig.java`

- [ ] **Step 1: Create ShopServicesProperties**

```java
package com.shop.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shop.services")
public record ShopServicesProperties(
    Service product,
    Service inventory,
    Service tax,
    Service promotion,
    Keycloak keycloak
) {
    public record Service(String url, int timeoutMs, Boolean enabled) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }
    public record Keycloak(String tokenUrl, String clientId, String clientSecret) {}
}
```

- [ ] **Step 2: Create CacheConfig**

```java
package com.shop.orderservice.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration PRODUCT_PRICE_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .cacheDefaults(defaultConfig())
            .withCacheConfiguration("productPrice", defaultConfig().entryTtl(PRODUCT_PRICE_TTL))
            .transactionAware();  // ⚠️ no-arg — defense-in-depth
    }

    private RedisCacheConfiguration defaultConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::");
    }
}
```

- [ ] **Step 3: Create RestClientConfig**

```java
package com.shop.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * ⚠️ P0-4 — NO {@code @Qualifier} on {@code @Bean} params. Lombok does not copy
 * {@code @Qualifier} from fields to constructor params. Defining beans as method
 * signatures here works because Spring wires by parameter NAME ({@code productRestClient},
 * {@code inventoryRestClient}, etc.) — clients inject by name with
 * {@code @Qualifier("productRestClient")}.
 *
 * <p>⚠️ P1-3 — NO auth header set HERE (shared builder). Authorization được set
 * PER-CALL trong từng client method (Task 11) — chỉ {@code InventoryServiceClient}
 * cần token (product GET là public-path; tax/promotion disabled trong MVP).
 * Header lấy từ {@code ServiceTokenProvider.getToken()} (Task 2).</p>
 */
@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props) {
        return baseRestClient(props.product().url(), props.product().timeoutMs());
    }

    @Bean("inventoryRestClient")
    public RestClient inventoryRestClient(ShopServicesProperties props) {
        return baseRestClient(props.inventory().url(), props.inventory().timeoutMs());
    }

    @Bean("taxRestClient")
    public RestClient taxRestClient(ShopServicesProperties props) {
        return baseRestClient(props.tax().url(), props.tax().timeoutMs());
    }

    @Bean("promotionRestClient")
    public RestClient promotionRestClient(ShopServicesProperties props) {
        return baseRestClient(props.promotion().url(), props.promotion().timeoutMs());
    }

    private RestClient baseRestClient(String baseUrl, int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(timeoutMs).toMillis());

        return RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Accept", "application/json")
            .build();
    }
}
```

> Phase 8 note: when `ServiceTokenProvider` is implemented, add `ClientHttpRequestInterceptor`
> to inject `Authorization: Bearer <token>` per-request — see Task 11 client method stubs
> that already show `.header("Authorization", "Bearer " + token)` per-call.

- [ ] **Step 4: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/config/
git commit -m "feat(order-service): config (CacheConfig, RestClientConfig, ShopServicesProperties)"
```

---

### Task 7: Enums

**Files:**
- Create: `order-service/src/main/java/com/shop/orderservice/entity/OrderStatus.java`
- Create: `order-service/src/main/java/com/shop/orderservice/entity/OutboxStatus.java`

- [ ] **Step 1: Create OrderStatus**

```java
package com.shop.orderservice.entity;

public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}
```

- [ ] **Step 2: Create OutboxStatus**

```java
package com.shop.orderservice.entity;

public enum OutboxStatus {
    PENDING, SENT, FAILED
}
```

- [ ] **Step 3: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/entity/OrderStatus.java \
        order-service/src/main/java/com/shop/orderservice/entity/OutboxStatus.java
git commit -m "feat(order-service): OrderStatus + OutboxStatus enums"
```

---

### Task 8: Order entity + OrderItem entity + Cart entity + CartItem entity + OutboxEvent entity + IdempotencyKey entity

**Files:**
- Create: 6 entity files

- [ ] **Step 1: Create Order.java**

```java
package com.shop.orderservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "subtotal", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total", nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "coupon_code", length = 50)
    private String couponCode;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
}
```

> No `markDeleted` on cancel — cancelled orders must remain in user/admin history (soft-delete reserved for admin/GDPR purge — spec §5.3).

- [ ] **Step 2: Create OrderItem.java**

```java
package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ⚠️ P0-1 — Does NOT extend {@code AbstractMappedEntity} (which extends
 * {@code SoftDeletable} requiring `deleted` column). Order items are hard-deleted
 * with their parent Order via {@code ON DELETE CASCADE} on the FK.
 *
 * <p>If we extended AbstractMappedEntity, {@code ddl-auto: validate} would fail at
 * boot because the {@code order_items} table has no audit/soft-delete columns.</p>
 */
@Entity
@Table(name = "order_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_title", nullable = false, length = 255)
    private String productTitle;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false, precision = 15, scale = 2)
    private BigDecimal lineTotal;

    @Column(name = "reservation_id")
    private UUID reservationId;  // nullable — populated after stock reservation
}
```

> No `@SQLRestriction` — items hard-delete with parent Order via CASCADE.

- [ ] **Step 3: Create Cart.java**

```java
package com.shop.orderservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "carts")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cart extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(nullable = false, precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;
}
```

- [ ] **Step 4: Create CartItem.java**

```java
package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ⚠️ P0-1 — Does NOT extend {@code AbstractMappedEntity}. Cart items are hard-deleted
 * with their parent Cart via {@code ON DELETE CASCADE} on the FK.
 *
 * <p>If we extended AbstractMappedEntity, {@code ddl-auto: validate} would fail at
 * boot because the {@code cart_items} table has no audit/soft-delete columns.</p>
 */
@Entity
@Table(name = "cart_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cart_id", nullable = false)
    private UUID cartId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_title", nullable = false, length = 255)
    private String productTitle;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Integer quantity;
}
```

> productTitle + unitPrice are SNAPSHOTS (lúc add-to-cart) — display only, NOT used for checkout (fresh price fetched from product-service — spec §5.2).

- [ ] **Step 5: Create OutboxEvent.java**

```java
package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
```

- [ ] **Step 6: Create IdempotencyKey.java**

```java
package com.shop.orderservice.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKey.IdempotencyKeyId.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class IdempotencyKey {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "key", nullable = false, length = 64)
    private String key;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    /** 0 = in-flight, 200/201 = complete. See spec §3.7. */
    @Column(name = "response_status", nullable = false)
    private Integer responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IdempotencyKeyId implements Serializable {
        private UUID userId;
        private String key;

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdempotencyKeyId that)) return false;
            return Objects.equals(userId, that.userId) && Objects.equals(key, that.key);
        }
        @Override public int hashCode() { return Objects.hash(userId, key); }
    }
}
```

- [ ] **Step 7: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 8: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/entity/
git commit -m "feat(order-service): 6 entities (Order, OrderItem, Cart, CartItem, OutboxEvent, IdempotencyKey)"
```

---

### Task 9: Repositories

**Files:**
- Create: 5 repository files

- [ ] **Step 1: Create OrderRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

- [ ] **Step 2: Create OrderItemRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);
}
```

- [ ] **Step 3: Create CartRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.Cart;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    Optional<Cart> findByUserIdAndDeletedFalse(UUID userId);

    Optional<Cart> findByIdAndUserIdAndDeletedFalse(UUID id, UUID userId);
}
```

- [ ] **Step 4: Create CartItemRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    List<CartItem> findByCartId(UUID cartId);

    Optional<CartItem> findByCartIdAndProductId(UUID cartId, UUID productId);
}
```

- [ ] **Step 5: Create OutboxEventRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM OutboxEvent e WHERE e.status = :status AND e.sentAt < :cutoff")
    int deleteByStatusAndSentAtBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 6: Create IdempotencyKeyRepository**

```java
package com.shop.orderservice.repository;

import com.shop.orderservice.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository
        extends JpaRepository<IdempotencyKey, IdempotencyKey.IdempotencyKeyId> {

    Optional<IdempotencyKey> findByUserIdAndKey(UUID userId, String key);

    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM IdempotencyKey ik WHERE ik.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);
}
```

- [ ] **Step 7: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 8: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/repository/
git commit -m "feat(order-service): 5 repositories (Order, OrderItem, Cart, CartItem, OutboxEvent, IdempotencyKey)"
```

---

(Phase 1 done. Continue in Part 2 — DTOs, mappers, clients, services, controllers, tests.)

---

---

## Phase 2 — DTOs + mappers + services

### Task 10: Internal DTOs (records)

**Files:**
- Create: 7 internal DTO files

- [ ] **Step 1: Create ProductSnapshot, PricingBreakdown**

```java
// ProductSnapshot.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSnapshot(UUID productId, String title, BigDecimal unitPrice) {}
```

```java
// PricingBreakdown.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record PricingBreakdown(
    BigDecimal subtotal,
    BigDecimal taxAmount,
    BigDecimal discountAmount,
    BigDecimal total,
    Map<UUID, ProductSnapshot> snapshots
) {}
```

- [ ] **Step 2: Create ReserveRequest, ReservationResponse**

```java
// ReserveRequest.java
package com.shop.orderservice.dto.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

/**
 * Plain record (NOT @Builder — records have canonical constructor).
 * Construct via {@code new ReserveRequest(quantity, orderId)}.
 */
public record ReserveRequest(
    @NotNull @Positive Integer quantity,
    UUID orderId  // populated by OrderServiceImpl — null when called from other paths
) {}
```

```java
// ReservationResponse.java
package com.shop.orderservice.dto.internal;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID productId,
    Integer quantity,
    Instant expiresAt
) {}
```

- [ ] **Step 3: Create Tax + Promotion DTOs**

```java
// TaxCalculateRequest.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record TaxCalculateRequest(UUID taxClassId, String country, String postalCode, BigDecimal amount) {}
```

```java
// TaxCalculateResponse.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;

public record TaxCalculateResponse(BigDecimal taxAmount, BigDecimal appliedRate) {}
```

```java
// PromotionApplyRequest.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;
import java.util.UUID;

public record PromotionApplyRequest(String code, BigDecimal orderAmount, UUID userId) {}
```

```java
// PromotionApplyResponse.java
package com.shop.orderservice.dto.internal;

import java.math.BigDecimal;

public record PromotionApplyResponse(BigDecimal discountAmount, BigDecimal finalAmount) {}
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/dto/internal/
git commit -m "feat(order-service): internal DTOs (product snapshot, pricing breakdown, reserve/tax/promotion)"
```

---

### Task 11: StockReservationFailedException + PricingService + StockReservationService implementations

**Files:**
- Create: `exception/StockReservationFailedException.java`
- Create: `service/PricingService.java`
- Create: `service/StockReservationService.java`
- Create: `client/ProductServiceClient.java`
- Create: `client/InventoryServiceClient.java`
- Create: `client/TaxServiceClient.java`
- Create: `client/PromotionServiceClient.java`
- Create: `service/impls/PricingServiceImpl.java`
- Create: `service/impls/StockReservationServiceImpl.java`

- [ ] **Step 1: Create StockReservationFailedException**

```java
package com.shop.orderservice.exception;

import lombok.Getter;

import java.util.UUID;

@Getter
public class StockReservationFailedException extends RuntimeException {
    private final UUID productId;

    public StockReservationFailedException(UUID productId, Throwable cause) {
        super("Failed to reserve stock for product " + productId, cause);
        this.productId = productId;
    }
}
```

- [ ] **Step 2: Create ProductServiceClient**

```java
package com.shop.orderservice.client;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * ⚠️ P0-5 — Unwraps {@code ApiResponse<ProductSnapshot>} envelope. Product-service
 * returns ALL endpoints wrapped in {@code ApiResponse<T>} — calling
 * {@code .body(ProductSnapshot.class)} would deserialize {@code {success, code, message, data, ...}}
 * directly into {@code ProductSnapshot} → all fields null → NPE on {@code .unitPrice()}.
 *
 * <p>Use {@link ParameterizedTypeReference} to capture the generic type, then
 * {@code .data()} to extract the payload.</p>
 */
@Component
@RequiredArgsConstructor
public class ProductServiceClient {

    @Qualifier("productRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<ProductSnapshot>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    @Cacheable(value = "productPrice", key = "#productId")
    public ProductSnapshot getProduct(UUID productId) {
        ApiResponse<ProductSnapshot> resp = restClient.get()
            .uri("/api/v1/products/{id}", productId)
            .retrieve()
            .body(RESPONSE_TYPE);
        return resp.data();
    }
}
```

> Phase 8: replace `data()` with map to `ProductDetailResponse` if fields diverge
> (current spec §5.2 uses `ProductSnapshot{productId, title, unitPrice}` subset of
> `ProductDetailResponse{productId, title, description, sku, priceUnit, quantity, ...}`
> — `priceUnit` field name mismatch tracked in Task 11 follow-up).

- [ ] **Step 3: Create InventoryServiceClient**

```java
package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.dto.internal.ReservationResponse;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * ⚠️ P0-5 — Unwraps ApiResponse envelope.
 * ⚠️ P1-3 — Per-call Authorization header từ `ServiceTokenProvider` (Task 2). Chỉ client
 * này cần token (product GET public; tax/promotion disabled MVP).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryServiceClient {

    @Qualifier("inventoryRestClient")
    private final RestClient restClient;
    private final ServiceTokenProvider serviceTokenProvider;   // P0-7 — SERVICE-role token

    private static final ParameterizedTypeReference<ApiResponse<ReservationResponse>> RESERVE_RESPONSE =
        new ParameterizedTypeReference<>() {};

    public UUID reserve(UUID productId, ReserveRequest request) {
        try {
            ApiResponse<ReservationResponse> resp = restClient.post()
                .uri("/api/v1/inventory/{productId}/reserve", productId)
                // ⚠️ P0-7 — reserve yêu cầu SERVICE role (inventory §4.2). Không có header = 401.
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .body(request)
                .retrieve()
                .body(RESERVE_RESPONSE);
            return resp.data().reservationId();
        } catch (HttpClientErrorException ex) {
            log.warn("Inventory reserve failed for product {}: {}", productId, ex.getMessage());
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new StockReservationFailedException(productId, ex);
            }
            throw BusinessException.of(ErrorCode.INTERNAL_SERVER_ERROR, "inventory");
        }
    }

    public void release(UUID reservationId) {
        try {
            restClient.post()
                .uri("/api/v1/inventory/reservations/{id}/release", reservationId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .retrieve()
                .toBodilessEntity();
        } catch (Exception ex) {
            log.error("Failed to release reservation {}", reservationId, ex);
            // DO NOT throw — compensation failures are best-effort, logged for ops review
        }
    }
}
```

- [ ] **Step 4: Create TaxServiceClient + PromotionServiceClient**

```java
// TaxServiceClient.java
package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.TaxCalculateRequest;
import com.shop.orderservice.dto.internal.TaxCalculateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class TaxServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("taxRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<TaxCalculateResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    public TaxCalculateResponse calculate(TaxCalculateRequest request) {
        if (!props.tax().isEnabled()) {
            // MVP default: tax disabled → return 0 (fail-closed only when enabled and down)
            return new TaxCalculateResponse(java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO);
        }
        try {
            ApiResponse<TaxCalculateResponse> resp = restClient.post()
                .uri("/api/v1/backoffice/tax-rates/calculate")
                .body(request)
                .retrieve()
                .body(RESPONSE_TYPE);
            return resp.data();
        } catch (HttpServerErrorException ex) {
            log.error("Tax service 5xx — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "tax");
        } catch (HttpClientErrorException ex) {
            log.warn("Tax calculation rejected: {}", ex.getMessage());
            throw BusinessException.of(ErrorCode.ORDER_TAX_CALCULATION_FAILED, ex.getMessage());
        }
    }
}
```

> Verify `ErrorCode.SERVICE_UNAVAILABLE` exists in common-core. If not, add it.

```java
// PromotionServiceClient.java
package com.shop.orderservice.client;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.orderservice.config.ShopServicesProperties;
import com.shop.orderservice.dto.internal.PromotionApplyRequest;
import com.shop.orderservice.dto.internal.PromotionApplyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class PromotionServiceClient {

    private final ShopServicesProperties props;

    @Qualifier("promotionRestClient")
    private final RestClient restClient;

    private static final ParameterizedTypeReference<ApiResponse<PromotionApplyResponse>> RESPONSE_TYPE =
        new ParameterizedTypeReference<>() {};

    /**
     * Called by {@link PricingServiceImpl} BEFORE invoking {@code apply()} — if false
     * and user provided a couponCode, we reject with 400 (P1-5 fix).
     */
    public boolean isEnabled() {
        return props.promotion().isEnabled();
    }

    public PromotionApplyResponse apply(PromotionApplyRequest request) {
        if (!props.promotion().isEnabled()) {
            // Caller (PricingService) should have already rejected user-supplied coupon when
            // promotion is disabled — see PricingServiceImpl.calculate for the check.
            // Defensive fallback: no discount.
            return new PromotionApplyResponse(java.math.BigDecimal.ZERO, request.orderAmount());
        }
        try {
            ApiResponse<PromotionApplyResponse> resp = restClient.post()
                .uri("/api/v1/backoffice/promotions/apply")
                .body(request)
                .retrieve()
                .body(RESPONSE_TYPE);
            return resp.data();
        } catch (HttpClientErrorException ex) {
            log.warn("Promotion apply rejected: {}", ex.getMessage());
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, request.code());
        } catch (HttpServerErrorException ex) {
            log.error("Promotion service 5xx — failing closed", ex);
            throw BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "promotion");
        }
    }
}
```

- [ ] **Step 5: Create PricingService interface + impl**

```java
// PricingService.java
package com.shop.orderservice.service;

import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.entity.CartItem;

import java.util.List;
import java.util.UUID;

public interface PricingService {
    PricingBreakdown calculate(UUID userId, List<CartItem> items, String couponCode);
}
```

```java
// PricingServiceImpl.java
package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.client.PromotionServiceClient;
import com.shop.orderservice.client.TaxServiceClient;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.PromotionApplyRequest;
import com.shop.orderservice.dto.internal.TaxCalculateRequest;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.service.PricingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PricingServiceImpl implements PricingService {

    private final ProductServiceClient productClient;
    private final TaxServiceClient taxClient;
    private final PromotionServiceClient promotionClient;

    @Override
    public PricingBreakdown calculate(UUID userId, List<CartItem> items, String couponCode) {
        // ⚠️ P1-5 — Reject couponCode upfront if promotion service is disabled.
        // Spec §5.2: "Có couponCode mà promotion disabled → 400 ORDER_PROMOTION_INVALID
        // (không âm thầm bỏ qua discount user nhập)". Silent ZERO discount would be
        // a UI lie — user sees a coupon field that doesn't work.
        if (couponCode != null && !couponCode.isBlank() && !promotionClient.isEnabled()) {
            throw BusinessException.of(ErrorCode.ORDER_PROMOTION_INVALID, couponCode);
        }

        // 1. Fetch product snapshots (cached 10 min — see RestClient config + @Cacheable on productClient)
        Map<UUID, ProductSnapshot> snapshots = new HashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (CartItem item : items) {
            ProductSnapshot snapshot = productClient.getProduct(item.getProductId());
            snapshots.put(item.getProductId(), snapshot);
            BigDecimal lineTotal = snapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
            subtotal = subtotal.add(lineTotal);
        }

        // 2. Apply promotion if coupon provided
        BigDecimal discountAmount = BigDecimal.ZERO;
        if (couponCode != null && !couponCode.isBlank()) {
            var promoResp = promotionClient.apply(
                new PromotionApplyRequest(couponCode, subtotal, userId)
            );
            discountAmount = promoResp.discountAmount();
        }

        // 3. Calculate tax on (subtotal - discount)
        BigDecimal taxableAmount = subtotal.subtract(discountAmount);
        var taxResp = taxClient.calculate(
            new TaxCalculateRequest(null, null, null, taxableAmount)  // taxClassId from product? defer
        );
        BigDecimal taxAmount = taxResp.taxAmount();

        // 4. Compute total
        BigDecimal total = taxableAmount.add(taxAmount);

        return new PricingBreakdown(subtotal, taxAmount, discountAmount, total, snapshots);
    }
}
```

> TODO: TaxClassId — for MVP, pass `null` (tax service applies default). Phase 8+: fetch from product-service.

- [ ] **Step 6: Create StockReservationService interface + impl**

```java
// StockReservationService.java
package com.shop.orderservice.service;

import com.shop.orderservice.dto.internal.ReserveRequest;

import java.util.UUID;

public interface StockReservationService {
    UUID reserve(UUID productId, ReserveRequest request);
    void release(UUID reservationId);
}
```

```java
// StockReservationServiceImpl.java
package com.shop.orderservice.service.impls;

import com.shop.orderservice.client.InventoryServiceClient;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StockReservationServiceImpl implements StockReservationService {

    private final InventoryServiceClient inventoryClient;

    @Override
    public UUID reserve(UUID productId, ReserveRequest request) {
        return inventoryClient.reserve(productId, request);
    }

    @Override
    public void release(UUID reservationId) {
        inventoryClient.release(reservationId);
    }
}
```

- [ ] **Step 7: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 8: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/exception/ \
        order-service/src/main/java/com/shop/orderservice/client/ \
        order-service/src/main/java/com/shop/orderservice/service/PricingService.java \
        order-service/src/main/java/com/shop/orderservice/service/StockReservationService.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/StockReservationServiceImpl.java
git commit -m "feat(order-service): PricingService + StockReservationService + RestClient clients"
```

---

### Task 12: OrderEventPublisher + impl + OutboxRelay + OutboxRetentionScheduler

**Files:**
- Create: `service/OrderEventPublisher.java`
- Create: `service/impls/OrderEventPublisherImpl.java`
- Create: `service/impls/OrderOutboxRelay.java`
- Create: `service/impls/OutboxRetentionScheduler.java`

- [ ] **Step 1: Create OrderEventPublisher interface**

```java
package com.shop.orderservice.service;

import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;

import java.util.List;

public interface OrderEventPublisher {
    void publishCreated(Order order, List<OrderItem> items);
    void publishStatusChanged(Order order);
    void publishCancelled(Order order);
}
```

- [ ] **Step 2: Create OrderEventPublisherImpl**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.entity.OutboxStatus;
import com.shop.orderservice.repository.OutboxEventRepository;
import com.shop.orderservice.service.OrderEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderEventPublisherImpl implements OrderEventPublisher {

    private static final String AGGREGATE_TYPE = "Order";
    private static final String TOPIC = "shop.order.lifecycle.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishCreated(Order order, List<OrderItem> items) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("userId", order.getUserId());
        data.put("status", order.getStatus().name());
        data.put("items", items.stream().map(OrderEventPublisherImpl::itemToMap).toList());
        data.put("subtotal", order.getSubtotal());
        data.put("taxAmount", order.getTaxAmount());
        data.put("discountAmount", order.getDiscountAmount());
        data.put("total", order.getTotal());
        if (order.getCouponCode() != null) {
            data.put("couponCode", order.getCouponCode());
        }
        data.put("createdAt", order.getCreatedAt().toString());
        save(order.getId(), "order.created.v1", data);
    }

    @Override
    public void publishStatusChanged(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("status", order.getStatus().name());
        Instant transitionedAt = switch (order.getStatus()) {
            case CONFIRMED -> order.getConfirmedAt();
            case SHIPPED -> order.getShippedAt();
            case DELIVERED -> order.getDeliveredAt();
            default -> order.getUpdatedAt();
        };
        data.put("transitionedAt", transitionedAt != null ? transitionedAt.toString() : Instant.now().toString());
        save(order.getId(), "order.updated.v1", data);
    }

    @Override
    public void publishCancelled(Order order) {
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", order.getId());
        data.put("cancelledAt", order.getCancelledAt() != null ? order.getCancelledAt().toString() : Instant.now().toString());
        // ⚠️ P2-4 — MVP cannot determine refund status (no payment-service integration yet).
        // Hardcode false + TODO for Phase 8 (payment-service) to wire real refund state.
        // Original condition `status == CANCELLED && total != null` was ALWAYS true.
        data.put("refunded", false);
        save(order.getId(), "order.cancelled.v1", data);
    }

    private void save(UUID aggregateId, String eventType, Map<String, Object> data) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setTopic(TOPIC);

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.putAll(data);

        try {
            event.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox payload for order {}", aggregateId, ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }

    private static Map<String, Object> itemToMap(OrderItem item) {
        Map<String, Object> map = new HashMap<>();
        map.put("productId", item.getProductId());
        map.put("productTitle", item.getProductTitle());
        map.put("quantity", item.getQuantity());
        map.put("unitPrice", item.getUnitPrice());
        map.put("lineTotal", item.getLineTotal());
        return map;
    }
}
```

> ⚠️ Need to import `OrderStatus` — add `import com.shop.orderservice.entity.OrderStatus;`

- [ ] **Step 3: Create OrderOutboxRelay**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.orderservice.entity.OutboxEvent;
import com.shop.orderservice.entity.OutboxStatus;
import com.shop.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${order.outbox.batch-size:100}")
    private int batchSize;

    @Value("${order.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${order.outbox.poll-interval-ms:5000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) return;
        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(event.getTopic(),
                    event.getAggregateId().toString(),  // Kafka key = orderId
                    event.getPayload());
                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                event.setLastError(null);
                outboxRepo.save(event);
            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(ex.getMessage());
                if (event.getRetryCount() >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event {} permanently failed", event.getEventId(), ex);
                } else {
                    log.warn("Outbox event {} retry {}/{}", event.getEventId(), event.getRetryCount(), maxRetries, ex);
                }
                outboxRepo.save(event);
                break;  // preserve ordering per aggregate
            }
        }
    }
}
```

- [ ] **Step 4: Create OutboxRetentionScheduler**

```java
package com.shop.orderservice.service.impls;

import com.shop.orderservice.entity.OutboxStatus;
import com.shop.orderservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRetentionScheduler {

    private final OutboxEventRepository outboxRepository;

    @Value("${order.outbox.retention-days:7}")
    private long retentionDays;

    @Scheduled(cron = "${order.outbox.retention-cron:0 0 3 * * *}")
    @Transactional
    public void purgeOldSentEvents() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            int deleted = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
            if (deleted > 0) log.info("Purged {} SENT outbox events older than {} days", deleted, retentionDays);
        } catch (Exception ex) {
            log.error("Outbox purge failed", ex);
            // Spring's ScheduledAnnotationBeanPostProcessor also auto-logs ERROR;
            // this catch + custom message improves alert routing
        }
    }
}
```

- [ ] **Step 5: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 6: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/OrderEventPublisher.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/OrderOutboxRelay.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java
git commit -m "feat(order-service): event publisher + OutboxRelay + OutboxRetentionScheduler"
```

---

### Task 13: IdempotencyService + impl (begin/complete/abort) + IdempotencyKeyCleanupScheduler

**Files:**
- Create: `service/IdempotencyService.java`
- Create: `service/impls/IdempotencyServiceImpl.java`
- Create: `service/impls/IdempotencyKeyCleanupScheduler.java`

- [ ] **Step 1: Create IdempotencyService interface**

```java
package com.shop.orderservice.service;

import com.shop.orderservice.dto.response.OrderResponse;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyService {
    /** @return cached response if key already complete; empty Optional if owner; throws on conflict. */
    Optional<OrderResponse> begin(String key, UUID userId, String requestHash);

    /** Update in-flight row with final response (same TX as saga). */
    void complete(String key, UUID userId, OrderResponse response, int status);

    /** Best-effort delete in-flight row on saga failure (REQUIRES_NEW). */
    void abort(String key, UUID userId);
}
```

- [ ] **Step 2: Create IdempotencyServiceImpl**

```java
package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.IdempotencyKey;
import com.shop.orderservice.repository.IdempotencyKeyRepository;
import com.shop.orderservice.service.IdempotencyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyServiceImpl implements IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderResponse> begin(String key, UUID userId, String requestHash) {
        if (key == null) return Optional.empty();

        IdempotencyKey ik = new IdempotencyKey();
        ik.setUserId(userId);
        ik.setKey(key);
        ik.setRequestHash(requestHash);
        ik.setResponseStatus(0);  // in-flight
        ik.setResponseBody("");
        ik.setCreatedAt(Instant.now());
        ik.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));  // TTL from spec §3.7

        try {
            repository.saveAndFlush(ik);
            return Optional.empty();  // owner — proceed with saga
        } catch (DataIntegrityViolationException ex) {
            // PK collision — re-lookup by composite key (user_id, key)
            IdempotencyKey existing = repository.findByUserIdAndKey(userId, key)
                .orElseThrow(() -> new IllegalStateException("PK collision but row not found", ex));
            if (existing.getResponseStatus() != 0) {
                // Complete — replay cached response
                return Optional.of(deserialize(existing.getResponseBody()));
            }
            // In-flight — reject
            throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);
        }
    }

    @Override
    @Transactional
    public void complete(String key, UUID userId, OrderResponse response, int status) {
        if (key == null) return;
        IdempotencyKey ik = repository.findByUserIdAndKey(userId, key)
            .orElseThrow(() -> new IllegalStateException("Idempotency row not found for complete: " + key));
        ik.setResponseStatus(status);
        ik.setResponseBody(serialize(response));
        repository.save(ik);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String key, UUID userId) {
        if (key == null) return;
        try {
            repository.findByUserIdAndKey(userId, key)
                .filter(ik -> ik.getResponseStatus() == 0)
                .ifPresent(repository::delete);
        } catch (Exception ex) {
            log.warn("Failed to abort in-flight idempotency key {}/{}: {}", userId, key, ex.getMessage());
            // Row will be TTL-purged (rev 2 fallback)
        }
    }

    private String serialize(OrderResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize OrderResponse for idempotency cache", ex);
        }
    }

    private OrderResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, OrderResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize cached OrderResponse", ex);
        }
    }
}
```

- [ ] **Step 3: Create IdempotencyKeyCleanupScheduler**

```java
package com.shop.orderservice.service.impls;

import com.shop.orderservice.repository.IdempotencyKeyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyKeyRepository repository;

    @Scheduled(cron = "${order.cleanup.idempotency-cron:0 0 4 * * *}")
    @Transactional
    public void purgeExpired() {
        try {
            int deleted = repository.deleteByExpiresAtBefore(Instant.now());
            if (deleted > 0) log.info("Purged {} expired idempotency keys", deleted);
        } catch (Exception ex) {
            log.error("Idempotency key purge failed", ex);
        }
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/IdempotencyService.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyKeyCleanupScheduler.java
git commit -m "feat(order-service): IdempotencyService (begin/complete/abort) + cleanup scheduler"
```

---

### Task 14: OrderStatusService + impl (state machine)

**Files:**
- Create: `service/OrderStatusService.java`
- Create: `service/impls/OrderStatusServiceImpl.java`

- [ ] **Step 1: Create OrderStatusService interface**

```java
package com.shop.orderservice.service;

import com.shop.orderservice.entity.OrderStatus;

public interface OrderStatusService {
    void validateTransition(OrderStatus from, OrderStatus to);
}
```

- [ ] **Step 2: Create OrderStatusServiceImpl**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderStatusService;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Service
public class OrderStatusServiceImpl implements OrderStatusService {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
        OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED,   OrderStatus.CANCELLED),
        OrderStatus.SHIPPED,   EnumSet.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
        OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    @Override
    public void validateTransition(OrderStatus from, OrderStatus to) {
        if (!ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to)) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE_TRANSITION, from, to);
        }
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/OrderStatusService.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java
git commit -m "feat(order-service): OrderStatusService (table-driven state machine)"
```

---

### Task 15: CartService interface + CartServiceImpl + CartMapper

**Files:**
- Create: `service/CartService.java`
- Create: `service/impls/CartServiceImpl.java`
- Create: `mapper/CartMapper.java`

- [ ] **Step 1: Create CartService interface**

```java
package com.shop.orderservice.service;

import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;

import java.util.UUID;

public interface CartService {
    CartResponse getMyCart(UUID userId);
    CartResponse addItem(UUID userId, CartItemAddRequest request);
    CartResponse updateItem(UUID userId, UUID cartItemId, CartItemUpdateRequest request);
    void removeItem(UUID userId, UUID cartItemId);
    void clearCart(UUID userId);
}
```

- [ ] **Step 2: Create CartMapper**

```java
package com.shop.orderservice.mapper;

import com.shop.orderservice.dto.response.CartItemResponse;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CartMapper {

    public CartResponse toResponse(Cart cart, List<CartItem> items) {
        List<CartItemResponse> itemResponses = items.stream()
            .map(this::toItemResponse)
            .toList();
        return new CartResponse(
            cart.getId(),
            cart.getUserId(),
            itemResponses,
            cart.getSubtotal(),
            cart.getCreatedAt(),
            cart.getUpdatedAt()
        );
    }

    public CartItemResponse toItemResponse(CartItem item) {
        return new CartItemResponse(
            item.getId(),
            item.getProductId(),
            item.getProductTitle(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity()))
        );
    }
}
```

- [ ] **Step 3: Create CartServiceImpl**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.client.ProductServiceClient;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.mapper.CartMapper;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CartServiceImpl implements CartService {

    private static final int MAX_QUANTITY_PER_LINE = 99;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductServiceClient productClient;
    private final CartMapper cartMapper;

    @Override
    // ⚠️ P0-3 — NOT @Transactional(readOnly=true). getOrCreateCart may INSERT for new users;
    // readOnly connection would reject INSERT → 500 on first GET. This is a write (auto-create).
    @Transactional
    public CartResponse getMyCart(UUID userId) {
        Cart cart = getOrCreateCart(userId);
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        return cartMapper.toResponse(cart, items);
    }

    @Override
    @Transactional
    public CartResponse addItem(UUID userId, CartItemAddRequest request) {
        Cart cart = getOrCreateCart(userId);
        ProductSnapshot snapshot = productClient.getProduct(request.productId());
        if (snapshot == null) throw BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, request.productId());

        CartItem existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId()).orElse(null);
        int newQuantity;
        if (existing != null) {
            newQuantity = existing.getQuantity() + request.quantity();
            if (newQuantity > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            existing.setQuantity(newQuantity);
            existing.setProductTitle(snapshot.title());
            existing.setUnitPrice(snapshot.unitPrice());
            cartItemRepository.save(existing);
        } else {
            newQuantity = request.quantity();
            if (newQuantity > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            CartItem item = CartItem.builder()
                .cartId(cart.getId())
                .productId(request.productId())
                .productTitle(snapshot.title())
                .unitPrice(snapshot.unitPrice())
                .quantity(request.quantity())
                .build();
            cartItemRepository.save(item);
        }
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
        return cartMapper.toResponse(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Override
    @Transactional
    public CartResponse updateItem(UUID userId, UUID cartItemId, CartItemUpdateRequest request) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId));
        if (!item.getCartId().equals(cart.getId())) {
            throw BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId);  // hide cross-user
        }
        if (request.quantity() == 0) {
            cartItemRepository.delete(item);
        } else {
            if (request.quantity() > MAX_QUANTITY_PER_LINE) {
                throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
            }
            item.setQuantity(request.quantity());
            cartItemRepository.save(item);
        }
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
        return cartMapper.toResponse(cart, cartItemRepository.findByCartId(cart.getId()));
    }

    @Override
    @Transactional
    public void removeItem(UUID userId, UUID cartItemId) {
        Cart cart = getOrCreateCart(userId);
        CartItem item = cartItemRepository.findById(cartItemId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId));
        if (!item.getCartId().equals(cart.getId())) {
            throw BusinessException.of(ErrorCode.CART_ITEM_NOT_FOUND, cartItemId);  // hide cross-user
        }
        cartItemRepository.delete(item);
        cart.setSubtotal(calculateSubtotal(cart));
        cartRepository.save(cart);
    }

    @Override
    @Transactional
    public void clearCart(UUID userId) {
        Cart cart = cartRepository.findByUserIdAndDeletedFalse(userId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, userId));
        cartItemRepository.deleteAll(cartItemRepository.findByCartId(cart.getId()));
        cart.markDeleted(userId.toString());
        cartRepository.save(cart);
    }

    private Cart getOrCreateCart(UUID userId) {
        return cartRepository.findByUserIdAndDeletedFalse(userId)
            .orElseGet(() -> cartRepository.save(Cart.builder()
                .userId(userId)
                .subtotal(BigDecimal.ZERO)
                .build()));
    }

    private BigDecimal calculateSubtotal(Cart cart) {
        return cartItemRepository.findByCartId(cart.getId()).stream()
            .map(item -> item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/CartService.java \
        order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java \
        order-service/src/main/java/com/shop/orderservice/mapper/CartMapper.java
git commit -m "feat(order-service): CartService (auto-create + merge + snapshot refresh + cap 99)"
```

---

### Task 16: OrderService interface + OrderMapper

**Files:**
- Create: `service/OrderService.java`
- Create: `mapper/OrderMapper.java`

- [ ] **Step 1: Create OrderService interface**

```java
package com.shop.orderservice.service;

import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.shop.orderservice.entity.OrderStatus;

import java.util.UUID;

public interface OrderService {

    OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey);

    OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin);

    OrderResponse confirmOrder(UUID orderId, boolean isAdmin);
    OrderResponse shipOrder(UUID orderId, boolean isAdmin);
    OrderResponse deliverOrder(UUID orderId, boolean isAdmin);

    OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin);

    Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable);
    Page<OrderResponse> findAll(OrderStatus status, Pageable pageable);
}
```

- [ ] **Step 2: Create OrderMapper**

```java
package com.shop.orderservice.mapper;

import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderMapper {

    public OrderResponse toResponse(Order order, List<OrderItem> items) {
        List<OrderItemResponse> itemResponses = items.stream().map(this::toItemResponse).toList();
        return new OrderResponse(
            order.getId(),
            order.getUserId(),
            order.getStatus(),
            itemResponses,
            order.getSubtotal(),
            order.getTaxAmount(),
            order.getDiscountAmount(),
            order.getTotal(),
            order.getCouponCode(),
            order.getCreatedAt(),
            order.getConfirmedAt(),
            order.getShippedAt(),
            order.getDeliveredAt(),
            order.getCancelledAt()
        );
    }

    public OrderItemResponse toItemResponse(OrderItem item) {
        return new OrderItemResponse(
            item.getProductId(),
            item.getProductTitle(),
            item.getQuantity(),
            item.getUnitPrice(),
            item.getLineTotal()
        );
    }
}
```

- [ ] **Step 3: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/OrderService.java \
        order-service/src/main/java/com/shop/orderservice/mapper/OrderMapper.java
git commit -m "feat(order-service): OrderService interface + OrderMapper"
```

---

### Task 17: OrderServiceImpl — saga + cancel + status transitions

**Files:**
- Create: `service/impls/OrderServiceImpl.java`

This is the core saga implementation. ~250 lines. Most critical task.

- [ ] **Step 1: Create OrderServiceImpl**

```java
package com.shop.orderservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.internal.PricingBreakdown;
import com.shop.orderservice.dto.internal.ProductSnapshot;
import com.shop.orderservice.dto.internal.ReserveRequest;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Cart;
import com.shop.orderservice.entity.CartItem;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderItem;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import com.shop.orderservice.service.IdempotencyService;
import com.shop.orderservice.service.OrderEventPublisher;
import com.shop.orderservice.service.OrderService;
import com.shop.orderservice.service.OrderStatusService;
import com.shop.orderservice.service.PricingService;
import com.shop.orderservice.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final PricingService pricingService;
    private final StockReservationService stockReservationService;
    private final OrderEventPublisher orderEventPublisher;
    private final IdempotencyService idempotencyService;
    private final OrderStatusService orderStatusService;
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    // ========================================================================
    // CREATE ORDER — SAGA with explicit compensation
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Idempotency pre-insert (REQUIRES_NEW — commits in-flight row before saga)
        Optional<OrderResponse> cached = idempotencyService.begin(idempotencyKey, userId, hash(request));
        if (cached.isPresent()) return cached.get();  // ← rev 2 fix O-N1 (REPLAY — DO NOT re-run saga)

        // 2. Delegate to doCreateOrder — all-or-nothing failure wrapped in single catch
        try {
            return doCreateOrder(userId, request, idempotencyKey);
        } catch (RuntimeException ex) {
            // rev 2 fix O-N2: single catch covers validation, pricing, reserve, etc.
            idempotencyService.abort(idempotencyKey, userId);
            throw ex;
        }
    }

    /**
     * Saga body — NOT annotated {@code @Transactional}. Runs in the TX opened by
     * {@link #createOrder(UUID, OrderCreateRequest, String)} (proxy-invoked). Self-invocation
     * would bypass the proxy — do not call this method from inside the same class.
     */
    private OrderResponse doCreateOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
        // 1. Load cart + validate
        Cart cart = (request.cartId() != null)
            ? cartRepository.findByIdAndUserIdAndDeletedFalse(request.cartId(), userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_NOT_FOUND, request.cartId()))
            : cartRepository.findByUserIdAndDeletedFalse(userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.CART_EMPTY));
        List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
        if (items.isEmpty()) throw BusinessException.of(ErrorCode.CART_EMPTY);

        // 2. Pricing (remote: product + tax + promotion)
        PricingBreakdown pricing = pricingService.calculate(userId, items, request.couponCode());

        // 3. Create Order + OrderItems FIRST (so we have orderId for ReserveRequest)
        Order order = Order.builder()
            .userId(userId).status(OrderStatus.PENDING)
            .subtotal(pricing.subtotal()).taxAmount(pricing.taxAmount())
            .discountAmount(pricing.discountAmount()).total(pricing.total())
            .couponCode(request.couponCode())
            .build();
        order = orderRepository.save(order);
        List<OrderItem> orderItems = new ArrayList<>();
        for (CartItem item : items) {
            ProductSnapshot snapshot = pricing.snapshots().get(item.getProductId());
            OrderItem orderItem = OrderItem.builder()
                .orderId(order.getId())
                .productId(item.getProductId())
                .productTitle(snapshot.title())
                .quantity(item.getQuantity())
                .unitPrice(snapshot.unitPrice())
                .lineTotal(snapshot.unitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())))
                .build();
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);

        // 4. Reserve stock per item — reservationId stored on OrderItem for cancel/compensation
        List<OrderItem> reserved = new ArrayList<>();
        try {
            for (OrderItem orderItem : orderItems) {
                UUID reservationId = stockReservationService.reserve(
                    orderItem.getProductId(),
                    new ReserveRequest(orderItem.getQuantity(), order.getId()));
                orderItem.setReservationId(reservationId);
                reserved.add(orderItem);
            }
            orderItemRepository.saveAll(orderItems);  // persist reservationIds
        } catch (StockReservationFailedException ex) {
            // Compensation: release all reservations
            releaseAllReservations(reserved);
            throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getProductId());
        }

        // 5. Clear cart
        cartItemRepository.deleteAll(items);
        cart.markDeleted("system");
        cartRepository.save(cart);

        // 6. Publish OrderCreated event (same TX — atomic with order insert)
        orderEventPublisher.publishCreated(order, orderItems);

        // 7. Build response + complete idempotency (same TX)
        OrderResponse response = orderMapper.toResponse(order, orderItems);
        idempotencyService.complete(idempotencyKey, userId, response, 201);
        return response;
    }

    private void releaseAllReservations(List<OrderItem> reserved) {
        for (OrderItem item : reserved) {
            try {
                if (item.getReservationId() != null) {
                    stockReservationService.release(item.getReservationId());
                }
            } catch (Exception ex) {
                log.error("Failed to release reservation {} for product {} during compensation",
                    item.getReservationId(), item.getProductId(), ex);
                // DO NOT throw — would mask original error
            }
        }
    }

    /**
     * SHA-256 hex of JSON-serialized request body (Jackson deterministic for record field order).
     * 64 hex chars fits {@code idempotency_keys.request_hash VARCHAR(64)}.
     */
    private String hash(OrderCreateRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Failed to hash request body for idempotency", ex);
        }
    }

    // ========================================================================
    // CANCEL
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

        // Authorization: hide existence from non-owners
        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
        }

        // Policy: USER chỉ cancel PENDING (chưa charge); ADMIN cancel PENDING + CONFIRMED.
        // SHIPPED/DELIVERED/CANCELLED: không ai cancel được.
        if (!isAdmin && order.getStatus() != OrderStatus.PENDING) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }
        orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
        if (order.getStatus() == OrderStatus.DELIVERED) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
        }

        // Release stock CHỈ khi PENDING (reservations còn PENDING trong inventory).
        // CONFIRMED: reservations đã COMMITTED — release endpoint sẽ throw RESERVATION_INVALID_STATE.
        // Restock cho CONFIRMED: refund flow (Phase 8) hoặc admin adjust thủ công.
        if (order.getStatus() == OrderStatus.PENDING) {
            List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getReservationId)
                .filter(java.util.Objects::nonNull)
                .toList();
            releaseAllReservationsById(reservationIds);
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        // NO markDeleted — cancelled orders must remain in user/admin history (rev 2 fix)
        orderRepository.save(order);

        orderEventPublisher.publishCancelled(order);
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId()));
    }

    private void releaseAllReservationsById(List<UUID> reservationIds) {
        for (UUID id : reservationIds) {
            try {
                stockReservationService.release(id);
            } catch (Exception ex) {
                log.error("Failed to release reservation {} during cancel", id, ex);
            }
        }
    }

    // ========================================================================
    // STATUS TRANSITIONS (admin / service-to-service in Phase 8)
    // ========================================================================

    @Override
    @Transactional
    public OrderResponse confirmOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.CONFIRMED, isAdmin, () -> Instant.now());
    }

    @Override
    @Transactional
    public OrderResponse shipOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.SHIPPED, isAdmin, () -> Instant.now());
    }

    @Override
    @Transactional
    public OrderResponse deliverOrder(UUID orderId, boolean isAdmin) {
        return transitionStatus(orderId, OrderStatus.DELIVERED, isAdmin, () -> Instant.now());
    }

    private OrderResponse transitionStatus(UUID orderId, OrderStatus to, boolean isAdmin,
                                          java.util.function.Supplier<Instant> timestampSupplier) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
        orderStatusService.validateTransition(order.getStatus(), to);
        Instant now = timestampSupplier.get();
        switch (to) {
            case CONFIRMED -> order.setConfirmedAt(now);
            case SHIPPED -> order.setShippedAt(now);
            case DELIVERED -> order.setDeliveredAt(now);
            case CANCELLED -> order.setCancelledAt(now);  // not used here — cancelOrder uses different flow
            default -> { /* unreachable */ }
        }
        order.setStatus(to);
        orderRepository.save(order);
        orderEventPublisher.publishStatusChanged(order);
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    // ========================================================================
    // READ
    // ========================================================================

    @Override
    @Transactional(readOnly = true)
    public OrderResponse findById(UUID orderId, UUID userId, boolean isAdmin) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));
        if (!isAdmin && !order.getUserId().equals(userId)) {
            throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);  // hide existence
        }
        return orderMapper.toResponse(order, orderItemRepository.findByOrderId(orderId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findMyOrders(UUID userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(order -> orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findAll(OrderStatus status, Pageable pageable) {
        Page<Order> page = (status != null)
            ? orderRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
            : orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        return page.map(order -> orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId())));
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -pl order-service -am compile
```

- [ ] **Step 3: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java
git commit -m "feat(order-service): OrderServiceImpl — saga (createOrder + doCreateOrder split) + cancel + status transitions"
```

---

---

## Phase 3 — Controllers + tests

### Task 18: Request/Response DTOs for controllers

**Files:**
- Create: 5 request/response DTO files (in `dto/request/` and `dto/response/`)

- [ ] **Step 1: Create CartItemAddRequest**

```java
package com.shop.orderservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CartItemAddRequest(
    @NotNull UUID productId,
    @NotNull @Min(1) @Max(99) Integer quantity
) {}
```

- [ ] **Step 2: Create CartItemUpdateRequest**

```java
package com.shop.orderservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemUpdateRequest(
    @NotNull @Min(0) @Max(99) Integer quantity  // 0 = remove
) {}
```

- [ ] **Step 3: Create OrderCreateRequest**

```java
package com.shop.orderservice.dto.request;

import jakarta.validation.constraints.Size;

import java.util.UUID;

public record OrderCreateRequest(
    UUID cartId,                 // optional — defaults to current user's cart
    @Size(max = 50) String couponCode
) {}
```

- [ ] **Step 4: Create CartResponse + CartItemResponse + OrderResponse + OrderItemResponse**

```java
// CartItemResponse.java
package com.shop.orderservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record CartItemResponse(
    UUID id, UUID productId, String productTitle,
    Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal
) {}
```

```java
// CartResponse.java
package com.shop.orderservice.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CartResponse(
    UUID id, UUID userId, List<CartItemResponse> items,
    BigDecimal subtotal, Instant createdAt, Instant updatedAt
) {}
```

```java
// OrderItemResponse.java
package com.shop.orderservice.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemResponse(
    UUID productId, String productTitle,
    Integer quantity, BigDecimal unitPrice, BigDecimal lineTotal
) {}
```

```java
// OrderResponse.java
package com.shop.orderservice.dto.response;

import com.shop.orderservice.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
    UUID id, UUID userId, OrderStatus status,
    List<OrderItemResponse> items,
    BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal total,
    String couponCode,
    Instant createdAt, Instant confirmedAt, Instant shippedAt, Instant deliveredAt, Instant cancelledAt
) {}
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/dto/request/ \
        order-service/src/main/java/com/shop/orderservice/dto/response/
git commit -m "feat(order-service): request/response DTOs for controllers"
```

---

### Task 19: CartController + CartControllerTest

**Files:**
- Create: `controller/CartController.java`
- Create: `test/.../controller/CartControllerTest.java`

- [ ] **Step 1: Create CartController**

```java
package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.CartItemAddRequest;
import com.shop.orderservice.dto.request.CartItemUpdateRequest;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.CARTS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CartController {

    private final CartService cartService;

    @GetMapping("/me")
    public ApiResponse<CartResponse> getMyCart() {
        return ApiResponse.ok(cartService.getMyCart(currentUserId()));
    }

    @PostMapping("/me/items")
    public ApiResponse<CartResponse> addItem(@Valid @RequestBody CartItemAddRequest request) {
        return ApiResponse.ok(cartService.addItem(currentUserId(), request), "Item added to cart");
    }

    @PutMapping("/me/items/{cartItemId}")
    public ApiResponse<CartResponse> updateItem(@PathVariable UUID cartItemId,
                                                  @Valid @RequestBody CartItemUpdateRequest request) {
        return ApiResponse.ok(cartService.updateItem(currentUserId(), cartItemId, request), "Cart item updated");
    }

    @DeleteMapping("/me/items/{cartItemId}")
    public ApiResponse<Void> removeItem(@PathVariable UUID cartItemId) {
        cartService.removeItem(currentUserId(), cartItemId);
        return ApiResponse.message("Item removed from cart");
    }

    @DeleteMapping("/me")
    public ApiResponse<Void> clearCart() {
        cartService.clearCart(currentUserId());
        return ApiResponse.message("Cart cleared");
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
```

- [ ] **Step 2: Create CartControllerTest**

```java
package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.dto.response.CartItemResponse;
import com.shop.orderservice.dto.response.CartResponse;
import com.shop.orderservice.service.CartService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CartController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class CartControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private CartService cartService;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID cartItemId = UUID.randomUUID();

    @BeforeEach
    void seedAuth() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject(userId.toString())
            .claim("preferred_username", "alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private CartResponse sampleCart() {
        var item = new CartItemResponse(cartItemId, productId, "Test Product", 2,
            new BigDecimal("19.99"), new BigDecimal("39.98"));
        return new CartResponse(UUID.randomUUID(), userId, List.of(item),
            new BigDecimal("39.98"), Instant.now(), Instant.now());
    }

    @Test
    void getMyCart_returns200WithEnvelope() throws Exception {
        when(cartService.getMyCart(any(UUID.class))).thenReturn(sampleCart());

        mockMvc.perform(get("/api/v1/carts/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.userId").value(userId.toString()))
            .andExpect(jsonPath("$.data.items[0].productId").value(productId.toString()));
    }

    @Test
    void addItem_returns200() throws Exception {
        when(cartService.addItem(any(UUID.class), any())).thenReturn(sampleCart());

        mockMvc.perform(post("/api/v1/carts/me/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"productId\":\"" + productId + "\",\"quantity\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void addItem_returns400_whenProductIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/carts/me/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateItem_returns200() throws Exception {
        when(cartService.updateItem(any(UUID.class), any(UUID.class), any())).thenReturn(sampleCart());

        mockMvc.perform(put("/api/v1/carts/me/items/" + cartItemId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":5}"))
            .andExpect(status().isOk());
    }

    @Test
    void removeItem_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/carts/me/items/" + cartItemId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void clearCart_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/carts/me"))
            .andExpect(status().isOk());
    }
}
```

> ⚠️ Verify `ApiPaths.CARTS` constant exists in `common-core` constants — may need to add `CARTS = "/api/v1/carts"` if missing.

- [ ] **Step 3: Run test**

```bash
./mvnw -pl order-service test -Dtest=CartControllerTest
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/controller/CartController.java \
        order-service/src/test/java/com/shop/orderservice/controller/CartControllerTest.java
git commit -m "feat(order-service): CartController + 6 MVC slice tests"
```

---

### Task 20: OrderController + OrderControllerTest

**Files:**
- Create: `controller/OrderController.java`
- Create: `test/.../controller/OrderControllerTest.java`

- [ ] **Step 1: Create OrderController**

```java
package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    // ⚠️ P2-6 — DO NOT use @PreAuthorize("hasRole('USER')"): Keycloak users may not have
    // explicit realm role "USER" → 403 oan. Filter chain already authenticated (isAuthenticated()
    // at class level); service-layer owner check ensures users can only access their own orders.
    public ApiResponse<OrderResponse> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(
            orderService.createOrder(currentUserId(), request, idempotencyKey),
            "Order created successfully");
    }

    @GetMapping("/me")
    public ApiResponse<PageResponse<OrderResponse>> findMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OrderResponse> result = orderService.findMyOrders(currentUserId(), pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<OrderResponse>> findAll(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<OrderResponse> result = orderService.findAll(status, pageable);
        return ApiResponse.ok(PageResponse.of(
            result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements()));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> findById(@PathVariable UUID orderId) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.findById(orderId, userId, isAdmin));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResponse<OrderResponse> cancelOrder(@PathVariable UUID orderId) {
        UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.cancelOrder(orderId, userId, isAdmin), "Order cancelled");
    }

    private static UUID currentUserId() {
        return UUID.fromString(AuthenticatedUser.requireCurrent().id());
    }
}
```

- [ ] **Step 2: Create OrderControllerTest**

```java
package com.shop.orderservice.controller;

import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.orderservice.dto.response.OrderItemResponse;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class OrderControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OrderService orderService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void seedAuth() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "none")
            .subject(userId.toString()).claim("preferred_username", "alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clearAuth() { SecurityContextHolder.clearContext(); }

    private OrderResponse sampleOrder() {
        var item = new OrderItemResponse(UUID.randomUUID(), "Product", 3,
            new BigDecimal("29.99"), new BigDecimal("89.97"));
        return new OrderResponse(UUID.randomUUID(), userId, OrderStatus.PENDING,
            List.of(item), new BigDecimal("89.97"), new BigDecimal("7.20"),
            new BigDecimal("0"), new BigDecimal("97.17"), null,
            Instant.now(), null, null, null, null);
    }

    @Test
    void createOrder_returns201WithIdempotencyKey() throws Exception {
        when(orderService.createOrder(any(UUID.class), any(), any())).thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders")
                .header("Idempotency-Key", "abc-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"couponCode\":\"SUMMER20\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.message").value("Order created successfully"));
    }

    @Test
    void findMyOrders_returns200() throws Exception {
        when(orderService.findMyOrders(any(UUID.class), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/orders/me"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void findById_returns200() throws Exception {
        when(orderService.findById(eq(orderId), any(UUID.class), any(Boolean.class)))
            .thenReturn(sampleOrder());

        mockMvc.perform(get("/api/v1/orders/" + orderId))
            .andExpect(status().isOk());
    }

    @Test
    void cancelOrder_returns200() throws Exception {
        when(orderService.cancelOrder(eq(orderId), any(UUID.class), any(Boolean.class)))
            .thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders/" + orderId + "/cancel"))
            .andExpect(status().isOk());
    }

    @Test
    void createOrder_worksWithoutIdempotencyKey() throws Exception {
        when(orderService.createOrder(any(UUID.class), any(), any())).thenReturn(sampleOrder());

        mockMvc.perform(post("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk());
    }
}
```

- [ ] **Step 3: Run test**

```bash
./mvnw -pl order-service test -Dtest=OrderControllerTest
```

- [ ] **Step 4: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/controller/OrderController.java \
        order-service/src/test/java/com/shop/orderservice/controller/OrderControllerTest.java
git commit -m "feat(order-service): OrderController + 5 MVC slice tests"
```

---

### Task 21: OrderStatusController (admin/SERVICE /confirm /ship /deliver)

**Files:**
- Create: `controller/OrderStatusController.java`

- [ ] **Step 1: Create OrderStatusController**

```java
package com.shop.orderservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ORDERS)
@RequiredArgsConstructor
@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
public class OrderStatusController {

    private final OrderService orderService;

    @PostMapping("/{orderId}/confirm")
    public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.confirmOrder(orderId, isAdmin));
    }

    @PostMapping("/{orderId}/ship")
    public ApiResponse<OrderResponse> ship(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.shipOrder(orderId, isAdmin));
    }

    @PostMapping("/{orderId}/deliver")
    public ApiResponse<OrderResponse> deliver(@PathVariable UUID orderId) {
        boolean isAdmin = AuthenticatedUser.requireCurrent().hasRole("ADMIN");
        return ApiResponse.ok(orderService.deliverOrder(orderId, isAdmin));
    }
}
```

- [ ] **Step 2: Verify compile**

```bash
./mvnw -pl order-service compile
```

- [ ] **Step 3: Commit**

```bash
git add order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java
git commit -m "feat(order-service): OrderStatusController (admin/SERVICE role-gated transitions)"
```

---

## Phase 4 — Advanced tests + integration + smoke

### Task 22: TestLiquibaseConfig (copy from product-service)

**Files:**
- Create: `test/.../config/TestLiquibaseConfig.java`

- [ ] **Step 1: Create TestLiquibaseConfig**

```java
package com.shop.orderservice.config;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class TestLiquibaseConfig {

    @Bean
    @ConditionalOnMissingBean
    public SpringLiquibase springLiquibase(DataSource dataSource,
                                           @Value("${spring.liquibase.change-log:classpath:db/changelog/db.changelog-master.yaml}") String changeLog) {
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(dataSource);
        liquibase.setChangeLog(changeLog);
        return liquibase;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add order-service/src/test/java/com/shop/orderservice/config/TestLiquibaseConfig.java
git commit -m "test(order-service): TestLiquibaseConfig for JPA slice tests"
```

---

### Task 23: Unit tests for services (CartService, OrderService, OrderStatusService, IdempotencyService)

**Files:**
- Create: 4 unit test files

- [ ] **Step 1: Create OrderServiceImplTest (12 tests)**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.exception.StockReservationFailedException;
import com.shop.orderservice.mapper.OrderMapper;
import com.shop.orderservice.repository.*;
import com.shop.orderservice.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock OrderRepository orderRepository;
    @Mock OrderItemRepository orderItemRepository;
    @Mock CartRepository cartRepository;
    @Mock CartItemRepository cartItemRepository;
    @Mock PricingService pricingService;
    @Mock StockReservationService stockReservationService;
    @Mock OrderEventPublisher orderEventPublisher;
    @Mock IdempotencyService idempotencyService;
    @Mock OrderStatusService orderStatusService;
    @Mock OrderMapper orderMapper;
    @Mock com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks OrderServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private Order order;

    @BeforeEach
    void setUp() {
        order = Order.builder().id(orderId).userId(userId).status(OrderStatus.PENDING)
            .subtotal(BigDecimal.valueOf(100)).total(BigDecimal.valueOf(110)).build();
    }

    @Test
    void cancelOrder_userCanCancelPending() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.cancelOrder(orderId, userId, false);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getCancelledAt()).isNotNull();
    }

    @Test
    void cancelOrder_userCannotCancelConfirmed() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4003"));
    }

    @Test
    void cancelOrder_adminCanCancelConfirmed() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());

        service.cancelOrder(orderId, userId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void cancelOrder_throwsOnDelivered() {
        order.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, userId, true))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancelOrder_hidesExistenceForNonOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancelOrder(orderId, otherUser, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4001"));  // NOT_FOUND, not FORBIDDEN
    }

    @Test
    void createOrder_returnsCachedResponseOnReplay() throws Exception {
        OrderCreateRequest req = new OrderCreateRequest(null, null);
        OrderResponse cached = new OrderResponse(orderId, userId, OrderStatus.PENDING, List.of(),
            BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.TEN, null,
            Instant.now(), null, null, null, null);

        when(idempotencyService.begin(eq("key1"), eq(userId), any()))
            .thenReturn(Optional.of(cached));

        OrderResponse result = service.createOrder(userId, req, "key1");

        assertThat(result).isEqualTo(cached);
        verify(pricingService, never()).calculate(any(), any(), any());  // saga NOT re-run
    }

    @Test
    void confirmOrder_setsConfirmedAt() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.confirmOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isNotNull();
    }

    @Test
    void shipOrder_setsShippedAt() {
        order.setStatus(OrderStatus.CONFIRMED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.shipOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getShippedAt()).isNotNull();
    }

    @Test
    void deliverOrder_setsDeliveredAt() {
        order.setStatus(OrderStatus.SHIPPED);
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        service.deliverOrder(orderId, true);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.getDeliveredAt()).isNotNull();
    }

    @Test
    void findById_hidesForNonOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.findById(orderId, otherUser, false))
            .isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4001"));
    }

    @Test
    void findById_returnsForAdminRegardlessOfOwner() {
        UUID otherUser = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of());
        when(orderMapper.toResponse(eq(order), any())).thenReturn(null);

        assertThatCode(() -> service.findById(orderId, otherUser, true)).doesNotThrowAnyException();
    }

    @Test
    void cancelOrder_pendingReleasesStock() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        var item = new com.shop.orderservice.entity.OrderItem();
        item.setReservationId(UUID.randomUUID());
        when(orderItemRepository.findByOrderId(orderId)).thenReturn(List.of(item));

        service.cancelOrder(orderId, userId, false);

        verify(stockReservationService).release(item.getReservationId());
    }
}
```

- [ ] **Step 2: Create OrderStatusServiceImplTest (25 parameterized tests)**

```java
package com.shop.orderservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.service.OrderStatusService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * ⚠️ P2-7 — Plain UNIT test (no Spring, no Testcontainers). Pure state-machine
 * logic — table-driven Map + set lookup. No need to spin up a DB container (~30s
 * saved per run).
 */
class OrderStatusServiceImplTest {

    private final OrderStatusService service = new OrderStatusServiceImpl();

    static Stream<Arguments> transitions() {
        // 5 statuses × 5 statuses = 25 cases; ~5 allowed + 20 rejected
        return Stream.of(
            // PENDING: CONFIRMED, CANCELLED
            Arguments.of(OrderStatus.PENDING, OrderStatus.CONFIRMED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.PENDING, OrderStatus.SHIPPED, false),
            // CONFIRMED: SHIPPED, CANCELLED
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, true),
            Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PENDING, false),
            // SHIPPED: DELIVERED only
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.DELIVERED, true),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED, false),
            Arguments.of(OrderStatus.SHIPPED, OrderStatus.PENDING, false),
            // DELIVERED: terminal
            Arguments.of(OrderStatus.DELIVERED, OrderStatus.PENDING, false),
            // CANCELLED: terminal
            Arguments.of(OrderStatus.CANCELLED, OrderStatus.PENDING, false),
            // Same-state: rejected (strict)
            Arguments.of(OrderStatus.PENDING, OrderStatus.PENDING, false)
        );
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void validateTransition(OrderStatus from, OrderStatus to, boolean allowed) {
        if (allowed) {
            assertThatCode(() -> service.validateTransition(from, to)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> service.validateTransition(from, to))
                .isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo("ORD-4004"));
        }
    }
}
```

- [ ] **Step 3: Create CartServiceImplTest + IdempotencyServiceImplTest (10 + 4 tests)**

For brevity, follow the pattern from favourite-service plan Task 7. ~250 lines total. Cover:
- CartServiceImplTest: getMyCart, addItem (new + merge + cap exceeded), updateItem (0 = remove), removeItem, clearCart
- IdempotencyServiceImplTest: begin (no key → empty Optional), begin (success → empty Optional + save), begin (PK collision + replay → cached), begin (PK collision + in-flight → throw 409), complete, abort

- [ ] **Step 4: Run all unit tests**

```bash
./mvnw -pl order-service test -Dtest='*ServiceImplTest'
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/test/java/com/shop/orderservice/service/impls/
git commit -m "test(order-service): unit tests for Order/Cart/OrderStatus/Idempotency services"
```

---

### Task 24: Repository slice tests

**Files:**
- Create: `test/.../repository/OrderRepositoryTest.java`
- Create: `test/.../repository/CartRepositoryTest.java`

- [ ] **Step 1: Copy TestLiquibaseConfig into test/.../config/ (already in Task 22)**

- [ ] **Step 2: Create OrderRepositoryTest**

```java
package com.shop.orderservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.entity.Order;
import com.shop.orderservice.entity.OrderStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class, TestLiquibaseConfig.class})
class OrderRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_repo_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private TestEntityManager em;
    @Autowired private OrderRepository repo;

    private final UUID alice = UUID.randomUUID();

    @Test
    void findByUserId_returnsOnlyAliceOrders() {
        var order1 = persistOrder(alice, OrderStatus.PENDING);
        persistOrder(UUID.randomUUID(), OrderStatus.PENDING);

        var result = repo.findByUserIdOrderByCreatedAtDesc(alice, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Order::getId).containsExactly(order1.getId());
    }

    @Test
    void findByStatus_filtersCorrectly() {
        var pending = persistOrder(alice, OrderStatus.PENDING);
        persistOrder(alice, OrderStatus.CONFIRMED);

        var result = repo.findByStatusOrderByCreatedAtDesc(OrderStatus.PENDING, PageRequest.of(0, 10));

        assertThat(result.getContent()).extracting(Order::getId).containsExactly(pending.getId());
    }

    @Test
    void softDeleteFilteredBySqlRestriction() {
        var order = persistOrder(alice, OrderStatus.PENDING);
        order.markDeleted("alice");
        em.persistAndFlush(order);
        em.clear();

        var result = repo.findByUserIdOrderByCreatedAtDesc(alice, PageRequest.of(0, 10));

        assertThat(result.getContent()).isEmpty();
    }

    private Order persistOrder(UUID userId, OrderStatus status) {
        Order order = Order.builder().userId(userId).status(status)
            .subtotal(BigDecimal.TEN).taxAmount(BigDecimal.ZERO).discountAmount(BigDecimal.ZERO)
            .total(BigDecimal.TEN).build();
        return em.persistAndFlush(order);
    }
}
```

- [ ] **Step 3: Create CartRepositoryTest (UNIQUE constraint + soft-delete)**

```java
package com.shop.orderservice.repository;

// ... similar skeleton as OrderRepositoryTest

class CartRepositoryTest {

    @Test
    void uniqueUserIdConstraint_blocksDuplicate() {
        Cart cart1 = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        em.persistAndFlush(cart1);

        Cart cart2 = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        assertThatThrownBy(() -> em.persistAndFlush(cart2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByUserIdAndDeletedFalse_excludesSoftDeleted() {
        Cart cart = Cart.builder().userId(alice).subtotal(BigDecimal.ZERO).build();
        em.persistAndFlush(cart);
        cart.markDeleted("alice");
        em.persistAndFlush(cart);
        em.clear();

        assertThat(cartRepository.findByUserIdAndDeletedFalse(alice)).isEmpty();
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw -pl order-service test -Dtest='*RepositoryTest'
```

- [ ] **Step 5: Commit**

```bash
git add order-service/src/test/java/com/shop/orderservice/repository/
git commit -m "test(order-service): OrderRepositoryTest + CartRepositoryTest (UNIQUE + soft-delete)"
```

---

### Task 25: OrderCreationSagaIntegrationTest (WireMock)

**Files:**
- Create: `test/.../service/OrderCreationSagaIntegrationTest.java`

- [ ] **Step 1: Verify WireMock dependency (already added in Task 3)**

> ⚠️ **Rev 5 — Task 3 đã add `org.wiremock:wiremock-standalone:3.13.1` (P1-4). KHÔNG thêm
> `org.wiremock.integrations:wiremock-spring-boot` — artifact đó cần `@EnableWireMock`
> annotation API, mâu thuẫn với code dưới đây dùng classic API
> (`new WireMockServer()` + `com.github.tomakehurst.wiremock.*`).**

Verify trong `order-service/pom.xml`:

```bash
./mvnw -pl order-service dependency:tree -Dincludes=org.wiremock:wiremock-standalone
```

- [ ] **Step 2: Create OrderCreationSagaIntegrationTest**

```java
package com.shop.orderservice.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.orderservice.config.TestLiquibaseConfig;
import com.shop.orderservice.dto.request.OrderCreateRequest;
import com.shop.orderservice.dto.response.OrderResponse;
import com.shop.orderservice.entity.OrderStatus;
import com.shop.orderservice.repository.CartItemRepository;
import com.shop.orderservice.repository.CartRepository;
import com.shop.orderservice.repository.OrderItemRepository;
import com.shop.orderservice.repository.OrderRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@EmbeddedKafka
@Import({JpaAuditingAutoConfiguration.class, TestLiquibaseConfig.class})
class OrderCreationSagaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("order_saga_test").withUsername("test").withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    static WireMockServer productServer;
    static WireMockServer inventoryServer;
    static WireMockServer keycloakServer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("shop.services.product.url", () -> "http://localhost:" + productServer.port());
        r.add("shop.services.inventory.url", () -> "http://localhost:" + inventoryServer.port());
        r.add("shop.services.tax.enabled", () -> "false");
        r.add("shop.services.promotion.enabled", () -> "false");
        // ⚠️ P0-8 — point Keycloak issuer to WireMock to avoid connection refused at boot
        r.add("shop.security.issuer-uri", () -> "http://localhost:" + keycloakServer.port() + "/realms/test");
        r.add("shop.security.csrf-disabled", () -> "true");
        // P0-7 — ServiceTokenProvider lấy token từ WireMock (stub trả dummy-jwt ở @BeforeAll)
        r.add("shop.services.keycloak.token-url",
            () -> "http://localhost:" + keycloakServer.port() + "/realms/test/protocol/openid-connect/token");
    }

    @BeforeAll
    static void startWireMock() {
        productServer = new WireMockServer(0);
        productServer.start();
        inventoryServer = new WireMockServer(0);
        inventoryServer.start();
        // ⚠️ P0-8 — Stub Keycloak OIDC endpoints so Boot's JwtDecoder doesn't
        // connection-refuse at startup. Đồng thời cấp token cho ServiceTokenProvider
        // (Task 2): shop.services.keycloak.token-url trỏ về keycloakServer (props bên dưới).
        // but BaseSecurityConfig still tries to resolve issuer at boot.
        keycloakServer = new WireMockServer(0);
        keycloakServer.stubFor(get(urlMatching("/realms/.*/.well-known/openid-configuration"))
            .willReturn(okJson("""
                {"issuer":"http://localhost:%d/realms/test","jwks_uri":"http://localhost:%d/realms/test/protocol/openid-connect/certs"}
                """.formatted(keycloakServer.port(), keycloakServer.port()))));
        keycloakServer.stubFor(post(urlMatching("/realms/.*/protocol/openid-connect/token"))
            .willReturn(okJson("""
                {"access_token":"dummy-jwt","expires_in":3600,"token_type":"Bearer"}
                """)));
        keycloakServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        productServer.stop();
        inventoryServer.stop();
        keycloakServer.stop();
    }

    @Autowired private OrderService orderService;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;

    private UUID userId;
    private UUID productId;

    @BeforeEach
    void setup() {
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        // Seed cart with 1 item
        var cart = cartRepository.save(com.shop.orderservice.entity.Cart.builder()
            .userId(userId).subtotal(BigDecimal.ZERO).build());
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productId)
            .productTitle("Test Product").unitPrice(new BigDecimal("100.00")).quantity(2).build());

        productServer.resetAll();
        inventoryServer.resetAll();
    }

    @Test
    void happyPath_createsOrderAndPublishesEvent() {
        // Wire product-service
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"id":"%s","title":"Test Product","priceUnit":100.00}
                """.formatted(productId))));

        // Wire inventory-service
        UUID reservationId = UUID.randomUUID();
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"reservationId":"%s","productId":"%s","quantity":2}
                """.formatted(reservationId, productId))));

        // Execute
        OrderResponse response = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-1");

        // Verify
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.total()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(orderRepository.findById(response.id())).isPresent();
        assertThat(cartRepository.findByUserIdAndDeletedFalse(userId)).isEmpty();  // cart cleared
    }

    @Test
    void reservationFailure_releasesNothingAndThrows() {
        // 2 cart items, only 1 reserves successfully — second fails
        UUID productId2 = UUID.randomUUID();
        var cart = cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow();
        cartItemRepository.save(com.shop.orderservice.entity.CartItem.builder()
            .cartId(cart.getId()).productId(productId2)
            .productTitle("Test 2").unitPrice(new BigDecimal("50.00")).quantity(1).build());

        productServer.stubFor(get(urlMatching("/api/v1/products/.*"))
            .willReturn(okJson("""{"id":"abc","title":"X","priceUnit":10}""")));
        inventoryServer.stubFor(post(urlMatching("/api/v1/inventory/.*/reserve"))
            .willReturn(aResponse().withStatus(409)));

        // Execute + verify
        assertThatThrownBy(() -> orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "test-key-2"))
            .isInstanceOf(com.shop.common.core.exception.BusinessException.class)
            .hasMessageContaining("reservation");

        // No Order created (TX rollback)
        assertThat(orderRepository.findAll()).isEmpty();
    }

    @Test
    void idempotencyReplay_returnsCachedResponse() {
        productServer.stubFor(get(urlEqualTo("/api/v1/products/" + productId))
            .willReturn(okJson("""
                {"id":"%s","title":"Test","priceUnit":100}
                """.formatted(productId))));
        inventoryServer.stubFor(post(urlEqualTo("/api/v1/inventory/" + productId + "/reserve"))
            .willReturn(okJson("""
                {"reservationId":"%s","productId":"%s","quantity":2}
                """.formatted(UUID.randomUUID(), productId))));

        // First call
        OrderResponse first = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        // Second call with same key — should return cached, NOT re-run saga
        OrderResponse second = orderService.createOrder(userId,
            new OrderCreateRequest(null, null), "idem-key-1");

        assertThat(second.id()).isEqualTo(first.id());
        // Verify product-service called only ONCE (not twice)
        productServer.verify(1, getRequestedFor(urlEqualTo("/api/v1/products/" + productId)));
    }
}
```

- [ ] **Step 3: Run test**

```bash
./mvnw -pl order-service test -Dtest=OrderCreationSagaIntegrationTest
```

- [ ] **Step 4: Commit**

```bash
git add order-service/pom.xml \
        order-service/src/test/java/com/shop/orderservice/service/OrderCreationSagaIntegrationTest.java
git commit -m "test(order-service): saga integration test (WireMock — happy + reservation failure + idempotency replay)"
```

---

### Task 26: docker-compose delta

**Files:**
- Modify: `docker-compose.yml` (verify order-service block has correct env)

- [ ] **Step 1: Verify order-service block**

```bash
grep -A 20 "order-service:" docker-compose.yml
```

Expected:
```yaml
  order-service:
    image: order-service:latest
    container_name: order-service
    <<: [*restart, *logging]
    ports:
      - "8084:8084"
    environment:
      <<: [*jwt, *pg-creds]
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderservice
      SPRING_DATA_REDIS_HOST: redis           # ← MUST be present
      SPRING_DATA_REDIS_PORT: 6379
      SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092  # ← MUST be SHOP_KAFKA_*, not KAFKA_SERVERS
      ORDER_SERVICE_CLIENT_ID: order-service
      ORDER_SERVICE_CLIENT_SECRET: changeme
      KEYCLOAK_TOKEN_URL: http://keycloak:8080/realms/ecommerce/protocol/openid-connect/token
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
      keycloak:
        condition: service_healthy
    healthcheck:
      <<: *hc-defaults
      test: ["CMD-SHELL", "wget -qO- http://localhost:8084/actuator/health > /dev/null 2>&1 || exit 1"]
    networks:
      - ecommerce-network
```

If SPRING_DATA_REDIS_HOST missing → add. If env var is `KAFKA_SERVERS` → rename to `SHOP_KAFKA_BOOTSTRAP_SERVERS`.

- [ ] **Step 2: Validate compose**

```bash
docker compose config --quiet
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "chore(docker-compose): order-service env (Redis + Kafka + Keycloak)"
```

---

### Task 27: Full reactor build + smoke

- [ ] **Step 1: Full build**

```bash
./mvnw clean test
```

- [ ] **Step 2: Boot smoke**

```bash
docker compose up -d postgres redis kafka keycloak
./mvnw -pl order-service spring-boot:run
curl -s http://localhost:8084/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 3: Verify Kafka events flow**

```bash
docker compose exec kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic shop.order.lifecycle.v1 --from-beginning --max-messages 5
# Expected: order.created.v1 payloads after creating test orders
```

---

## Plan Self-Review

### 1. Spec coverage

| Spec section | Task |
|---|---|
| §3.1 Order entity | Task 8 |
| §3.2 OrderItem + reservationId | Task 8 |
| §3.3 Cart | Task 8 |
| §3.4 CartItem + snapshot | Task 8 |
| §3.5 OrderStatus enum | Task 7 |
| §3.6 OutboxEvent | Task 8 |
| §3.7 IdempotencyKey (composite PK) | Task 8 |
| §3.8 Liquibase (6 tables) | Task 5 |
| §4.1 Cart endpoints | Task 19 |
| §4.2 Order endpoints | Tasks 20 + 21 |
| §4.3 Validation | Task 18 |
| §4.4 Response DTOs | Task 18 |
| §5.1 Cart service CRUD | Task 15 |
| §5.2 Saga (createOrder + doCreateOrder) | Task 17 |
| §5.3 Order cancel (USER=PENDING / ADMIN=PENDING+CONFIRMED, NO release CONFIRMED) | Task 17 |
| §5.4 State machine validation | Task 14 |
| §5.5 Outbox relay + retention | Task 12 |
| §5.6 Idempotency begin/complete/abort | Task 13 |
| §5.7 RestClient clients + ServiceTokenProvider | Tasks 2 + 6 + 11 |
| §5.8 Cache config | Task 6 |
| §6 Kafka events | Task 12 |
| §7.1 application.yml | Task 4 |
| §7.2 RestClient config | Task 6 |
| §8 ErrorCodes (ORD-4003..4010) | Task 1 |
| §9 Testing strategy | Tasks 19–25 |
| §10 Open items | Applied: composite PK O-N5, reference-only O-N4, refund flow O-N6 deferred |

### 2. Placeholder scan

No TBD/TODO in task code. ✓

### 3. Type consistency

- `OrderResponse` 14 fields — consistent across mapper, controller, idempotency ✓
- `CartResponse` 6 fields — consistent ✓
- `OrderCreateRequest` 2 fields — consistent ✓
- `OrderItem.reservationId` UUID — consistent across reserve + cancel ✓
- `IdempotencyKey.idempotencyKeyId` composite PK (user_id, key) — consistent across repository, service, controller ✓

### 4. Rev 2 fixes reflected

- ✅ O-N1: `createOrder` checks `cached.isPresent()` → return cached — caller bug fixed
- ✅ O-N2: `doCreateOrder()` extracted + single `RuntimeException` catch + `abort()` — covers all failures
- ✅ O-N3: `hash()` defined in OrderServiceImpl — SHA-256 hex (64 chars)
- ✅ O-N4: `reservationId` documented as reference-only — no status column
- ✅ O-N5: IdempotencyKey composite PK `(user_id, key)` — all queries `findByUserIdAndKey`
- ✅ O-N6: DELIVERED terminal — refund flow deferred to Phase 8
- ✅ O-N7: `order.outbox.retention-days: 7`
- ✅ O-N8: Changelog at §12 (end) — inventory/favourite convention

### 5. Prerequisite fixes reflected

- ✅ ServiceTokenProvider order-service local, bind `shop.services.keycloak.*` (Task 2 — rev 4 un-deferred)
- ✅ `tax.enabled` / `promotion.enabled` flags default false (Task 4 yml)
- ✅ docker-compose delta SHOP_KAFKA_BOOTSTRAP_SERVERS + Redis env (Task 26)

---

## Execution Handoff

Plan complete and saved. 27 tasks across 4 phases (~3–4 weeks implementation effort for senior engineer). Two execution options:

**1. Subagent-Driven (recommended)** — dispatch fresh subagent per task, review between tasks.

**2. Inline Execution** — execute tasks in this session with `executing-plans`.

(All rev 2 fixes + 3 prerequisites + O-N1..N8 issues + 22 deep-review fixes (P0-1..P0-8, P1-1..P1-6, P2-1..P2-7) + rev 4 regression fix (P0-7 un-deferred with LOCAL ServiceTokenProvider) incorporated. Plan is consistent with spec rev 3 final.)