# Order Service Design

> **Status:** Design pending user review (2026-08-28)
> **Path:** `docs/superpowers/specs/2026-08-28-order-service-design.md`
> **Author:** user + agent
> **Reference:** [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/order-service) (order-service + cart-service modules — workspace DEVİATES: combines into 1 service + adds OrderItem 1:N relation + adds state machine + adds idempotency + uses RestClient instead of Feign)
> **Pattern source:**
> - [`product-service`](./2026-08-26-product-service-design.md) — Outbox + Redis cache + Kafka envelope
> - [`inventory-service`](./2026-08-28-inventory-service-design.md) — Optimistic lock + saga compensation + `@Scheduled` schedulers
> - [`favourite-service`](./2026-08-28-favourite-service-design.md) — Slim user-scoped CRUD pattern

---

## 1. Overview

Order-service là microservice **orchestrator** của platform — gọi đồng thời
`product-service` + `inventory-service` + `tax-service` + `promotion-service` để
tạo Order từ Cart, publish `OrderCreated` lên Kafka cho payment-service consume.

**Bounded context:** Checkout flow — user-owned Cart → multi-service reservation
saga → Order với state machine (PENDING → CONFIRMED → SHIPPED → DELIVERED).

**Tech stack (Spring Boot 4.1.1, Java 25, package `com.shop.orderservice`):**

| Layer | Choice | Why |
|---|---|---|
| Persistence | PostgreSQL 16 + Liquibase + Spring Data JPA | Same as auth + product + inventory |
| Cache | **None for Cart** — per-user, write-heavy (invalidates benefit); **Cache for product price lookups** (10 min TTL via `CacheManager` from common) | YAGNI on Cart; reuse product cache via inter-service call |
| Events | Apache Kafka + Transactional Outbox (giống product + inventory) | `OrderCreated` consumed by payment + notification + search |
| Inter-service | **Spring `RestClient` (Boot 4 native)** with `@Bean` configs per downstream | No Feign (workspace decision — see §2.3) |
| Auth | Keycloak JWT + `@PreAuthorize("isAuthenticated()")` cho USER endpoints, `@PreAuthorize("hasRole('ADMIN')")` cho admin | Same fleet baseline |
| State machine | Explicit `OrderStatus` enum + `OrderStatusTransition` table-driven validation | Avoid ad-hoc if/else chains |
| Idempotency | `Idempotency-Key` HTTP header on POST /orders → stored in `idempotency_keys` table | Industry standard (Stripe, Square, PayPal pattern) |
| Common | `common-spring`, `common-core`, `common-security`, `common-kafka`, `common-logging` | Same fleet baseline |

**Out of scope (deliberate):**

- Payment processing — payment-service owns (consumes OrderCreated, publishes PaymentSucceeded)
- Shipping — shipping-service owns (consumes OrderShipped event from order)
- Multi-currency / FX — single currency assumed (USD or platform default)
- Partial order fulfillment — atomic shipment per Order
- Admin dashboard / analytics — separate BI service
- Email notifications — notification-service consumes Kafka events

---

## 2. Architecture

### 2.1 Package structure (`com.shop.orderservice.*`)

```
order-service/
├── OrderServiceApplication.java              # @SpringBootApplication + @EnableScheduling
├── config/
│   ├── CacheConfig.java                       # RedisCacheManager customizer (per-cache TTL for product price)
│   └── RestClientConfig.java                  # @Bean RestClient for product/inventory/tax/promotion
├── controller/
│   ├── OrderController.java                   # REST /api/v1/orders
│   └── CartController.java                    # REST /api/v1/carts
├── dto/
│   ├── request/
│   │   ├── OrderCreateRequest.java            # record: { cartId, couponCode? }
│   │   ├── CartItemAddRequest.java           # record: { productId, quantity }
│   │   ├── CartItemUpdateRequest.java        # record: { quantity }
│   ├── response/
│   │   ├── OrderResponse.java                 # record: { id, userId, status, items[], subtotal, taxAmount, discountAmount, total, createdAt }
│   │   ├── OrderItemResponse.java             # record: { productId, productTitle, quantity, unitPrice, lineTotal }
│   │   ├── CartResponse.java                  # record: { id, userId, items[], subtotal, createdAt }
│   │   └── CartItemResponse.java              # record: { id, productId, productTitle, quantity, unitPrice, lineTotal }
├── entity/
│   ├── Order.java                             # extends AbstractMappedEntity + @SQLRestriction
│   ├── OrderItem.java                         # extends AbstractMappedEntity (1:N relation to Order, NO @SQLRestriction — items deleted with order hard-delete) + reservationId
│   ├── Cart.java                              # extends AbstractMappedEntity + @SQLRestriction
│   ├── CartItem.java                          # extends AbstractMappedEntity (1:N relation to Cart, hard-delete via CASCADE) + productTitle/unitPrice snapshot
│   ├── OrderStatus.java                       # enum PENDING/CONFIRMED/SHIPPED/DELIVERED/CANCELLED
│   ├── OutboxEvent.java                       # hard-delete, aggregateId = orderId
│   └── OutboxStatus.java                      # enum PENDING/SENT/FAILED
├── repository/
│   ├── OrderRepository.java                   # JpaRepository<Order, UUID>
│   ├── OrderItemRepository.java               # JpaRepository<OrderItem, UUID>
│   ├── CartRepository.java                    # JpaRepository<Cart, UUID>
│   ├── CartItemRepository.java                # JpaRepository<CartItem, UUID>
│   ├── OutboxEventRepository.java             # @Modifying bulk delete
│   └── IdempotencyKeyRepository.java          # @Modifying insert + select
├── service/
│   ├── OrderService.java                       # interface
│   ├── CartService.java                        # interface
│   ├── OrderEventPublisher.java                # interface
│   ├── PricingService.java                     # interface — fetch product price + calculate tax + apply promotion
│   ├── StockReservationService.java            # interface — call inventory-service reserve + release on compensation
│   └── impls/
│       ├── OrderServiceImpl.java                # @Service + @Transactional + saga orchestration
│       ├── CartServiceImpl.java                 # @Service + @Transactional
│       ├── TransactionalOrderEventPublisher.java # writes OutboxEvent in same @Transactional
│       ├── RestClientPricingService.java        # calls product + tax + promotion
│       ├── RestClientStockReservationService.java # calls inventory reserve/release
│       ├── OrderOutboxRelay.java                 # @Scheduled single-thread relay
│       ├── IdempotencyService.java                # store + retrieve Idempotency-Key
│       └── OrderStatusService.java                # state transition validation
├── client/                                       # RestClient wrappers (typed responses, error handling)
│   ├── ProductServiceClient.java                # GET /api/v1/products/{id}
│   ├── InventoryServiceClient.java              # POST /api/v1/inventory/{productId}/reserve + release
│   ├── TaxServiceClient.java                     # POST /api/v1/backoffice/tax-rates/calculate
│   └── PromotionServiceClient.java                # POST /api/v1/backoffice/promotions/apply
└── saga/                                          # Saga orchestration utilities
    └── OrderCreationSaga.java                    # coordinates reserve-product + release-on-failure
```

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `orderservice` DB, 6 tables (orders, order_items, carts, cart_items, outbox_events, idempotency_keys) |
| Redis 7 | `spring-boot-starter-data-redis` + `@EnableCaching` | Cache key `productPrice::{productId}`; TTL 10 min; invalidate on price change (via product-service event listener — TODO) |
| Kafka | `spring-kafka` + `common-kafka` (`KafkaMessagePublisher`) | Topic `shop.order.lifecycle.v1` (3 events: created/updated/cancelled), key = orderId |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize` cho USER/ADMIN endpoints |
| product-service | Spring `RestClient` (Boot 4) with `@LoadBalanced`-style config (manual URL via `shop.services.product.url`) | GET `/api/v1/products/{id}` for price lookup |
| inventory-service | Spring `RestClient` | POST `/api/v1/inventory/{productId}/reserve`, POST `/reservations/{id}/release` |
| tax-service | Spring `RestClient` | POST `/api/v1/backoffice/tax-rates/calculate` |
| promotion-service | Spring `RestClient` | POST `/api/v1/backoffice/promotions/apply` |

### 2.3 Decisions & rationale

- **Combine Cart + Order in 1 service** — reference repo splits into `order-service` + `cart-service`. Workspace combines because Cart is order-specific (always owned by current user, never cross-service). Reduces inter-service coupling.
- **Add `OrderItem` 1:N relation** — reference has flat Order with single `product_id`. Workspace adds proper items because real orders have multiple products. Requires `OrderItemRepository` + JSON serialization tweak.
- **Spring `RestClient` over Feign** — workspace already standardized on `RestClient` (common-keycloak refactor). RestClient is Boot 4 native, type-safe via `ParameterizedTypeReference`, no extra dep.
- **Per-service RestClient beans in `RestClientConfig`** — each downstream service gets its own `@Bean RestClient` configured with base URL from `application.yml`. Resilient (per-client timeout, retry via Resilience4j).
- **`Idempotency-Key` header on POST /orders** — Stripe/Square pattern. Client sends `Idempotency-Key: <uuid>`; server stores first response for 24h; replay returns cached response. Prevents double-charge if client retries on network timeout. Industry standard, MUST implement.
- **Saga with explicit compensation** — order creation reserves stock in inventory-service; if downstream (tax/promotion) fails, release all reservations. No SAGA framework (e.g., Axon, Temporal) — keep simple manual try/catch + finally release loop. Documented in §5.2.
- **State machine in `OrderStatusService`** — table-driven validation: `Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS`. Prevents invalid transitions (e.g., SHIPPED → PENDING). Single source of truth.
- **Cache only product prices, NOT Carts** — per-user Cart invalidated on every write (add/remove/update item). Cache TTL 30s would barely help. YAGNI. Product prices cached 10 min because (a) multiple orders hit same product, (b) writes are rare (admin updates), (c) staleness acceptable for price (inventory is real-time).
- **No inter-service Kafka consumer in MVP** — order-service ONLY produces Kafka events (no `@KafkaListener`). Listening to product/inventory events for cache invalidation is deferred (§10).
- **Hard-delete `OrderItem` + `CartItem` (not soft-delete)** — they have no audit value; they belong to parent Order/Cart which IS soft-deleted. CASCADE on parent delete.

### 2.4 Open-question decisions (rev 2 — moved from §12 per O-N8)

| # | Question | Decision | Rationale |
|---|---|---|---|
| 1 | Idempotency-Key collision (same key, khác payload) | **409 `ORDER_DUPLICATE_REQUEST`** | 422 gây nhầm "validation error"; silently process = nguy cơ double-charge. 409 nói đúng bản chất: key đang conflict với request khác. Client bug → client phải sinh key mới. |
| 2 | Cart auto-create on GET | **Auto-create empty cart** | UX chuẩn (user không cần biết cart existence); owner-scoped nên không rủi ro; match flow của Amazon/Shopee. |
| 3 | Add same productId lần 2 | **Sum quantity, cap 99/line** — vượt → `400` + i18n `cart.item.quantity.exceeded` (dùng `BusinessException.badRequest`, không cần ErrorCode mới) | UX standard; cap chống spam quantity. Snapshot giá/title refresh theo lần add mới nhất. |
| 4 | `/confirm` `/ship` `/deliver` MVP | **ADMIN-only**; `@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")` prepped sẵn | Phase 8 chỉ cần đổi role check / thêm Kafka consumer, không đổi contract. |
| 5 | Cart subtotal recompute | **Application-layer recalc từ snapshot unitPrice** | Trigger khó debug; cart nhỏ; recalc từ cột đã lưu (rev 2) không cần HTTP call. |
| 6 | Cancel khi CONFIRMED | **ADMIN-only**; CONFIRMED-cancel **không release stock** (reservations đã COMMITTED — inventory release từ chối non-PENDING); restock thuộc refund flow Phase 8 / admin adjust thủ công | Tránh user tự cancel sau khi charge; tránh gọi nhầm release endpoint. |
| 7 | Idempotency-Key retention | **24h** + daily purge (`order.cleanup.idempotency-cron`) | Stripe standard; đủ cho mọi retry window thực tế (client retry trong phút/giờ, không ngày). |

> Cả 7 quyết định áp dụng theo khuyến nghị review rev 2 — user flip câu nào thì sửa đúng
> hàng đó + các đoạn code liên quan (Q1/Q6/Q7 ảnh hưởng §3.7/§5.2/§5.3; Q3 ảnh hưởng §5.1).

---

## 3. Data model

### 3.1 Order

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK, `@GeneratedValue(UUID)` |
| `userId` | `UUID` | not null, indexed — from JWT subject at create time |
| `status` | `OrderStatus` enum | not null, default `PENDING` |
| `subtotal` | `BigDecimal(15,2)` | not null, >= 0 — sum of item line totals |
| `taxAmount` | `BigDecimal(15,2)` | not null, default 0 |
| `discountAmount` | `BigDecimal(15,2)` | not null, default 0 — from promotion |
| `total` | `BigDecimal(15,2)` | not null — `subtotal + taxAmount - discountAmount` |
| `couponCode` | `String(50)` | nullable — from `OrderCreateRequest.couponCode` |
| `createdAt` / `updatedAt` / `createdBy` / `updatedBy` | (from `AbstractMappedEntity`) | auto-populated |
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable`) | soft-delete CHỈ cho admin purge/GDPR — cancel KHÔNG markDeleted (rev 2: cancelled order phải visible trong lịch sử) |
| `confirmedAt` | `Instant` | nullable — set on PENDING → CONFIRMED |
| `shippedAt` | `Instant` | nullable — set on CONFIRMED → SHIPPED |
| `deliveredAt` | `Instant` | nullable — set on SHIPPED → DELIVERED |
| `cancelledAt` | `Instant` | nullable — set on * → CANCELLED |

**Indexes:**
- `UNIQUE INDEX (id)` (PK auto)
- `INDEX (user_id)` — list-by-user query
- `INDEX (status, created_at)` — admin dashboard query (open item)
- `INDEX (coupon_code) WHERE deleted = false` — analytics

Entity: `@Entity @Table(name = "orders")` + `@SQLRestriction("deleted = false")` + extends `AbstractMappedEntity`.

### 3.2 OrderItem

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `orderId` | `UUID` | not null, FK → `orders(id)` ON DELETE CASCADE |
| `productId` | `UUID` | not null, indexed — snapshot at order time |
| `productTitle` | `String(255)` | not null — **snapshot** (product may be renamed later) |
| `quantity` | `Integer` | not null, > 0 |
| `unitPrice` | `BigDecimal(15,2)` | not null — **snapshot** (product price may change) |
| `lineTotal` | `BigDecimal(15,2)` | not null — `quantity * unitPrice` |
| `reservationId` | `UUID` | nullable — inventory reservation cho line này (rev 2) |

> **`reservationId` (rev 2 — QUAN TRỌNG):** lưu reservation id trả về từ inventory-service
> ngay khi reserve. Không có nó thì cancel/compensation **không thể release** đúng
> reservation (inventory chỉ có release-by-reservationId; không có endpoint by-orderId).
> Giải pháp này xóa luôn workaround `releaseByOrderId` + TODO "list endpoint trong
> inventory-service" — không cần sửa inventory-service.
>
> **`reservationId` là REFERENCE ONLY (rev 3, O-N4):** trạng thái hiện tại của reservation
> (PENDING/COMMITTED/RELEASED) nằm ở inventory-service — source of truth. KHÔNG thêm cột
> `reservationStatus` local: order-service không consume inventory events trong MVP
> (§2.3) nên cột đó sẽ drift so với thực tế và tạo ảo giác về tính chính xác. Audit
> "reservation còn active không" → query inventory-service bằng reservationId.

**No `deleted` column** — items are hard-deleted with their parent Order. `@Entity @Table(name = "order_items")`. `@SQLRestriction` NOT applied (parent CASCADE handles).

**Why snapshot productTitle + unitPrice:** product-service is source of truth at order time. Renaming/repricing a product later must NOT change historical Order records. This is **audit-grade immutability**.

### 3.3 Cart

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `userId` | `UUID` | not null, **UNIQUE** — 1 active cart per user (UNIQUE partial index `WHERE deleted = false`) |
| `subtotal` | `BigDecimal(15,2)` | not null, default 0 — recalculated on every item add/remove |
| `createdAt` / `updatedAt` / `createdBy` / `updatedBy` | (from `AbstractMappedEntity`) | auto-populated |
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable`) | soft-delete only via "Clear cart" endpoint; checkout hard-converts Cart → Order (Cart items destroyed, Order items created) |

Entity: extends `AbstractMappedEntity` + `@SQLRestriction("deleted = false")`.

### 3.4 CartItem

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `cartId` | `UUID` | not null, FK → `carts(id)` ON DELETE CASCADE |
| `productId` | `UUID` | not null, indexed |
| `productTitle` | `String(255)` | not null — **snapshot** lúc add-to-cart (rev 2) |
| `unitPrice` | `BigDecimal(15,2)` | not null — **snapshot** lúc add-to-cart (rev 2) |
| `quantity` | `Integer` | not null, > 0 |

> **Snapshot columns (rev 2):** bắt buộc để `CartResponse`/`CartItemResponse` trả được
> `productTitle`/`unitPrice`/`lineTotal`/`subtotal` (§4.4) mà **không gọi product-service
> mỗi lần GET cart**. Snapshot được fetch khi add (và refresh lại khi re-add cùng product);
> phần còn lại của cart hiển thị giá snapshot. Tại checkout, giá được fetch lại FRESH
> (§3.4 rationale giữ nguyên — user thấy giá mới nhất khi đặt hàng).

**Snapshot `productTitle`/`unitPrice` phục vụ DISPLAY ONLY** (render cart mà không gọi product-service) — checkout vẫn fetch giá FRESH từ product-service (§5.2 step 3). Nếu giá thay đổi giữa lúc add và checkout, user thấy giá mới khi đặt hàng (transparency). Cart items KHÔNG soft-delete — CASCADE với parent.

### 3.5 OrderStatus enum

```java
public enum OrderStatus {
    PENDING,      // created, stock reserved, awaiting payment
    CONFIRMED,    // payment received (consumed PaymentSucceeded event)
    SHIPPED,      // shipping created (consumed from shipping-service OR admin action)
    DELIVERED,    // delivery confirmed
    CANCELLED     // cancelled by user (PENDING only) or admin (any non-DELIVERED)
}
```

**Transition rules** (in `OrderStatusService`):
```
PENDING   → CONFIRMED | CANCELLED
CONFIRMED → SHIPPED   | CANCELLED
SHIPPED   → DELIVERED
DELIVERED → (terminal)
CANCELLED → (terminal)
```

> **DELIVERED terminal là có chủ ý (rev 3, O-N6):** không có `DELIVERED → CANCELLED`
> cho delivery-failed/return flow vì return cần refund (payment-service, Phase 8) +
> RMA flow (shipping-service). Khi build return flow, THÊM transition mới qua changelog
> (vd `DELIVERED → REFUNDED` enum value mới) — không sửa bảng hiện tại.

### 3.6 OutboxEvent (kế thừa product-service pattern)

Identical to inventory-service OutboxEvent §3.4. `aggregateId = orderId` (used as Kafka partition key for ordering). Topic `shop.order.lifecycle.v1`. Events: `order.created.v1`, `order.updated.v1`, `order.cancelled.v1`.

### 3.7 IdempotencyKey

| Field | Type | Constraint |
|---|---|---|
| `key` | `String(64)` | **PK composite part** — UUID từ header `Idempotency-Key` (rev 3, O-N5) |
| `userId` | `UUID` | **PK composite part** — idempotency scope PER USER (Stripe pattern: account+key) |
| `requestHash` | `String(64)` | not null — SHA-256 hex của canonical JSON request (xem `hash()` ở §5.6) |
| `responseStatus` | `Integer` | not null — HTTP status code returned (200, 201); **`0` = in-flight** (rev 2) |
| `responseBody` | `TEXT` | not null — JSON-serialized ApiResponse body; **`""` khi in-flight** (rev 2) |
| `createdAt` | `Instant` | not null |
| `expiresAt` | `Instant` | not null — `created_at + 24h` |

> **Concurrency design (rev 2):** lookup-then-store như rev 1 có race — 2 request cùng
> `Idempotency-Key` đến đồng thời → cả 2 miss lookup → **cả 2 chạy saga → double reserve
> + 2 Orders**. Fix: **pre-insert** row `in-flight` (`responseStatus=0`, `responseBody=""`)
> ngay TRƯỚC saga:
> - Insert thành công → request này là "owner", chạy saga, UPDATE row với response thật.
> - PK collision → request trùng: re-lookup; nếu row đã có response (`status != 0`) →
>   trả cached response; nếu vẫn in-flight (request đang xử lý) → `409 ORDER_DUPLICATE_REQUEST`
>   kèm message "request in progress, retry shortly" (client retry sẽ nhận cached response).
> Owner crash giữa chừng → row in-flight until TTL purge; client retry nhận 409 — chấp nhận
> cho MVP (Stripe xử lý bằng cách replay request gốc — phức tạp hơn, defer).
>
> Store trong CÙNG TX với saga → crash rollback cả row in-flight (idempotency không
> ghi khi order không commit). Pre-insert cần TX riêng? Không — nếu pre-insert cùng TX
> với saga thì collision-detect không hoạt động (chưa commit, request kia không thấy row).
> → **Pre-insert trong TX ngắn riêng (REQUIRES_NEW hoặc trước khi mở TX chính)**, sau đó
> saga chạy TX chính và update row cuối cùng.

**Retention:** daily `@Scheduled` cleanup of `expires_at < now()` rows.

### 3.8 Liquibase

```
order-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changelog-001-initial-schema.yaml     (6 tables + indexes)
```

Thứ tự tạo: `carts` → `cart_items` → `orders` → `order_items` → `outbox_events` → `idempotency_keys`.

---

## 4. API surface

### 4.1 Cart endpoints — `/api/v1/carts`

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `GET` | `/api/v1/carts/me` | USER | — | `ApiResponse<CartResponse>` | Current user's active cart (auto-creates if not exists) |
| `POST` | `/api/v1/carts/me/items` | USER | `CartItemAddRequest { productId, quantity }` | `ApiResponse<CartResponse>` | Add item (merge if same productId already exists, sum quantities) |
| `PUT` | `/api/v1/carts/me/items/{cartItemId}` | USER | `CartItemUpdateRequest { quantity }` | `ApiResponse<CartResponse>` | Update quantity (remove if quantity ≤ 0) |
| `DELETE` | `/api/v1/carts/me/items/{cartItemId}` | USER | — | `ApiResponse<Void>` | Remove item |
| `DELETE` | `/api/v1/carts/me` | USER | — | `ApiResponse<Void>` | Clear cart (soft-delete) |

> **Owner check:** every cart endpoint resolves `currentUserId` from JWT and queries `cartRepository.findByUserIdAndDeletedFalse(userId)` — never accepts `userId` from request body.

### 4.2 Order endpoints — `/api/v1/orders`

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `POST` | `/api/v1/orders` | USER | `OrderCreateRequest { cartId?, couponCode? }` + header `Idempotency-Key: <uuid>` | `ApiResponse<OrderResponse>` | Create order from cart (defaults to current user's cart if `cartId` omitted); runs saga (§5.2) |
| `GET` | `/api/v1/orders/me?page=&size=` | USER | — | `ApiResponse<PageResponse<OrderResponse>>` | Current user's orders, newest first |
| `GET` | `/api/v1/orders/{orderId}` | USER/ADMIN | — | `ApiResponse<OrderResponse>` | Owner check for USER; ADMIN bypasses |
| `GET` | `/api/v1/orders?page=&size=&status=` | ADMIN | — | `ApiResponse<PageResponse<OrderResponse>>` | All orders, optional status filter |
| `POST` | `/api/v1/orders/{orderId}/cancel` | USER (owner) / ADMIN | — | `ApiResponse<OrderResponse>` | Cancel order; USER only if PENDING, ADMIN if any non-DELIVERED status |
| `POST` | `/api/v1/orders/{orderId}/confirm` | internal (`SERVICE` role) | — | `ApiResponse<OrderResponse>` | PENDING → CONFIRMED (called by payment-service via Kafka consumer — future; admin-only in MVP) |
| `POST` | `/api/v1/orders/{orderId}/ship` | internal (`SERVICE` role) | — | `ApiResponse<OrderResponse>` | CONFIRMED → SHIPPED (admin in MVP) |
| `POST` | `/api/v1/orders/{orderId}/deliver` | internal (`SERVICE` role) | — | `ApiResponse<OrderResponse>` | SHIPPED → DELIVERED (admin in MVP) |

> **Service-to-service auth:** internal endpoints (`/confirm`, `/ship`, `/deliver`) gated by `@PreAuthorize("hasRole('SERVICE')")`. JWT issued by order-service's client-credentials flow.
> **Kafka consumer for PaymentSucceeded** is a **Phase 8 follow-up** — in MVP, admin manually calls `/confirm`. Documented in §10.

### 4.3 Validation (Bean Validation, `@Valid` on controller)

- `CartItemAddRequest`: `productId` `@NotNull`; `quantity` `@NotNull @Min(1) @Max(99)`
- `CartItemUpdateRequest`: `quantity` `@NotNull @Min(0) @Max(99)` (0 = remove)
- `OrderCreateRequest`: `cartId` optional; `couponCode` optional `@Size(max=50)`

### 4.4 Response DTOs

```java
record OrderResponse(
    UUID id, UUID userId, OrderStatus status,
    List<OrderItemResponse> items,
    BigDecimal subtotal, BigDecimal taxAmount, BigDecimal discountAmount, BigDecimal total,
    String couponCode,
    Instant createdAt, Instant confirmedAt, Instant shippedAt, Instant deliveredAt, Instant cancelledAt
) {}

record OrderItemResponse(UUID productId, String productTitle, int quantity,
                         BigDecimal unitPrice, BigDecimal lineTotal) {}

record CartResponse(UUID id, UUID userId, List<CartItemResponse> items,
                    BigDecimal subtotal, Instant createdAt, Instant updatedAt) {}

record CartItemResponse(UUID id, UUID productId, String productTitle,
                        int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {}
```

> **Why productTitle + unitPrice in CartItemResponse:** for UI display (don't want to fetch product-service for every cart render). Cached at add-to-cart time from product-service response. Invalidate cache on product update (TODO via Kafka consumer).

---

## 5. Service layer

### 5.1 Cart service (simple CRUD)

Cart logic is straightforward — no saga, no outbound calls (except product title/price lookup on add). Documented inline in `CartServiceImpl`.

**`addItem(userId, request)` flow:**
```java
@Transactional
public CartResponse addItem(UUID userId, CartItemAddRequest request) {
    Cart cart = cartRepository.findByUserIdAndDeletedFalse(userId)
        .orElseGet(() -> cartRepository.save(Cart.builder().userId(userId).subtotal(BigDecimal.ZERO).build()));
    ProductSnapshot snapshot = productServiceClient.getProduct(request.productId());  // HTTP GET (cached 10 min)
    // Validate product exists + active
    CartItem existing = cartItemRepository.findByCartIdAndProductId(cart.getId(), request.productId())
        .orElse(null);
    if (existing != null) {
        // Merge (Q3): cộng quantity; refresh snapshot giá/título mới nhất
        existing.setQuantity(existing.getQuantity() + request.quantity());
        existing.setProductTitle(snapshot.title());
        existing.setUnitPrice(snapshot.unitPrice());
        if (existing.getQuantity() > MAX_QUANTITY_PER_LINE) {
            throw BusinessException.badRequest("cart.item.quantity.exceeded", MAX_QUANTITY_PER_LINE);
        }
        cartItemRepository.save(existing);
    } else {
        CartItem item = CartItem.builder()
            .cartId(cart.getId())
            .productId(request.productId())
            .productTitle(snapshot.title())      // snapshot — display only (§3.4)
            .unitPrice(snapshot.unitPrice())     // snapshot — display only (§3.4)
            .quantity(request.quantity())
            .build();
        cartItemRepository.save(item);
    }
    cart.setSubtotal(calculateCartSubtotal(cart));  // từ unitPrice SNAPSHOT đã lưu — không gọi HTTP
    cartRepository.save(cart);
    return cartMapper.toResponse(cart, cartItemRepository.findByCartId(cart.getId()));
}
```

> `calculateCartSubtotal` tính từ `CartItem.unitPrice` snapshot trong DB — cart read
> hoàn toàn không đụng product-service. Checkout fetch giá fresh riêng (§5.2).

### 5.2 Order creation — SAGA with compensation (CORE FLOW)

> ⚠️ **This is the most complex code in the service. Saga pattern with explicit
> compensation. No SAGA framework — manual try/catch + release loop.**

```java
// Outer TX wraps steps 2-8 (local writes). Remote calls (pricing, reserve) happen
// INSIDE — MVP chấp nhận giữ connection trong lúc call ra (traffic thấp); khi tối ưu:
// tách pricing ra trước TX + short-TX cho write phase (documented ở rev 2 note cuối).
@Transactional
public OrderResponse createOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
    // 1. Idempotency PRE-INSERT (rev 2) — REQUIRES_NEW, committed ngay TRƯỚC TX chính.
    //    Collision: có response → REPLAY cached (KHÔNG chạy saga); in-flight cùng user
    //    → throw ORDER_DUPLICATE_REQUEST.
    //    ⚠️ O-N1 (P0): BẮT BUỘC check Optional return — bỏ qua ⇒ replay chạy lại toàn bộ
    //    saga ⇒ double reserve + double Order + double charge.
    Optional<OrderResponse> cached = idempotencyService.begin(userId, idempotencyKey, hash(request));
    if (cached.isPresent()) {
        return cached.get();   // replay — không đụng inventory/cart
    }

    try {
        return doCreateOrder(userId, request, idempotencyKey);   // steps 2-8
    } catch (RuntimeException ex) {
        // O-N2 (P1): in-flight row đã COMMIT riêng (REQUIRES_NEW) — MỌI failure sau
        // begin() (pricing down, reserve fail, validation...) phải abort để client
        // retry được ngay thay vì kẹt 409 đến hết TTL 24h. abort() best-effort,
        // REQUIRES_NEW, idempotent (row đã xoá → no-op).
        idempotencyService.abort(userId, idempotencyKey);
        throw ex;
    }
}

// ⚠️ KHÔNG gắn @Transactional ở đây — self-invocation không qua proxy; method này
// chạy TRONG TX đã mở bởi createOrder() (caller qua proxy). Giống lý do plan
// inventory từ chối @Retryable nội bộ.
private OrderResponse doCreateOrder(UUID userId, OrderCreateRequest request, String idempotencyKey) {
    // 2. Load cart + validate non-empty
    Cart cart = (request.cartId() != null)
        ? cartRepository.findByIdAndUserIdAndDeletedFalse(request.cartId(), userId).orElseThrow(CART_NOT_FOUND)
        : cartRepository.findByUserIdAndDeletedFalse(userId).orElseThrow(CART_EMPTY);
    List<CartItem> items = cartItemRepository.findByCartId(cart.getId());
    if (items.isEmpty()) throw BusinessException.of(ErrorCode.CART_EMPTY);

    // 3. Calculate pricing (remote: product prices fresh + tax + promotion)
    PricingBreakdown pricing = pricingService.calculate(userId, items, request.couponCode());

    // 4. Create Order + OrderItems TRƯỚC khi reserve — để có orderId gửi sang
    //    inventory (ReserveRequest.orderId). Nếu saga fail phía dưới → TX rollback
    //    xoá sạch order (local), phần remote do compensation xử lý.
    Order order = Order.builder()
        .userId(userId).status(OrderStatus.PENDING)
        .subtotal(pricing.subtotal()).taxAmount(pricing.taxAmount())
        .discountAmount(pricing.discountAmount()).total(pricing.total())
        .couponCode(request.couponCode())
        .build();
    order = orderRepository.save(order);
    List<OrderItem> orderItems = items.stream()
        .map(item -> OrderItem.builder()
            .orderId(order.getId())
            .productId(item.getProductId())
            .productTitle(pricing.snapshot(item.getProductId()).title())
            .quantity(item.getQuantity())
            .unitPrice(pricing.snapshot(item.getProductId()).unitPrice())
            .lineTotal(pricing.snapshot(item.getProductId()).unitPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
            .build())
        .toList();
    orderItemRepository.saveAll(orderItems);

    // 5. Reserve stock per item — orderId ĐÃ CÓ; reservationId lưu vào OrderItem
    List<OrderItem> reserved = new ArrayList<>();
    try {
        for (OrderItem orderItem : orderItems) {
            UUID reservationId = stockReservationService.reserve(
                orderItem.getProductId(),
                ReserveRequest.builder()
                    .quantity(orderItem.getQuantity())
                    .orderId(order.getId())          // ← rev 2: gắn orderId để trace/release
                    .build());
            orderItem.setReservationId(reservationId);
            reserved.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);      // persist reservationIds
    } catch (StockReservationFailedException ex) {
        // Compensation: release các reservation đã tạo (by reservationId — đáng tin).
        // (Abort in-flight row do catch-all ở createOrder xử lý — không lặp ở đây.)
        releaseAllReservations(reserved);
        throw BusinessException.of(ErrorCode.ORDER_RESERVATION_FAILED, ex.getProductId());
    }

    // 6. Clear cart (items CASCADE-vulnerable nhưng delete explicit cho chắc)
    cartItemRepository.deleteAll(items);
    cart.markDeleted("system");
    cartRepository.save(cart);

    // 7. Publish OrderCreated via outbox (same TX — atomic với order insert)
    orderEventPublisher.publishCreated(order, orderItems);

    // 8. Complete idempotency row (same TX) — response BHẾT XUẤT HIỆN trước khi build return
    OrderResponse orderResponse = orderMapper.toResponse(order, orderItems);
    if (idempotencyKey != null) {
        idempotencyService.complete(userId, idempotencyKey, orderResponse, 201);
    }
    return orderResponse;
}

private void releaseAllReservations(List<OrderItem> reserved) {
    // Best-effort: không fail compensation nếu release fail (logged + metric)
    for (OrderItem item : reserved) {
        try {
            stockReservationService.release(item.getReservationId());
        } catch (Exception ex) {
            log.error("Failed to release reservation {} for product {} during compensation",
                item.getReservationId(), item.getProductId(), ex);
            // DO NOT throw — would mask original error
        }
    }
}
```

**Saga invariants (rev 2):**
- Idempotency row pre-inserted TRƯỚC saga → concurrent duplicate không chạy saga 2 lần
- All reservations released if ANY step fails — by `reservationId` lưu trên `OrderItem` (không còn workaround by-orderId)
- Order + OrderItems + OutboxEvent + idempotency-completion trong SAME `@Transactional`
- **Tax/promotion degradation (rev 2):** hai service này CHƯA tồn tại trong workspace. Config
  `shop.services.tax.enabled` / `shop.services.promotion.enabled` (default `false` cho MVP):
  disabled → `taxAmount=0`, bỏ qua promotion call. Enabled mà service down/5xx → fail-closed:
  `503 SERVICE_UNAVAILABLE` (không tạo order với số tiền sai). Có `couponCode` mà promotion
  disabled → `400 ORDER_PROMOTION_INVALID` (không âm thầm bỏ qua discount user nhập).
- Compensation failures logged but NEVER mask original error
- **Long-TX note:** remote calls nằm trong TX giữ connection — chấp nhận MVP (traffic thấp,
  pool 10 đủ); khi tối ưu: tách pricing ra trước TX + write-phase TX ngắn (refactor sau,
  không đổi contract)

### 5.3 Order cancel

```java
@Transactional
public OrderResponse cancelOrder(UUID orderId, UUID userId, boolean isAdmin) {
    Order order = orderRepository.findById(orderId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId));

    // Authorization — hide existence from non-owners
    if (!isAdmin && !order.getUserId().equals(userId)) {
        throw BusinessException.of(ErrorCode.ORDER_NOT_FOUND, orderId);
    }

    // Policy (Q6, rev 2): USER chỉ cancel được order PENDING (chưa charge).
    // ADMIN được cancel PENDING + CONFIRMED. SHIPPED/DELIVERED không ai cancel được.
    if (!isAdmin && order.getStatus() != OrderStatus.PENDING) {
        throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);
    }
    orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
    if (order.getStatus() == OrderStatus.DELIVERED) {
        throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE, orderId);  // terminal
    }

    // Release stock — CHỈ khi PENDING (reservations còn PENDING trong inventory).
    // ⚠️ CONFIRMED: reservations đã COMMITTED — inventory release endpoint từ chối
    // non-PENDING (RESERVATION_INVALID_STATE) → KHÔNG release ở đây. Restock là việc
    // của refund flow (Phase 8) hoặc admin tự adjust inventory thủ công.
    if (order.getStatus() == OrderStatus.PENDING) {
        List<UUID> reservationIds = orderItemRepository.findByOrderId(orderId).stream()
            .map(OrderItem::getReservationId)
            .filter(Objects::nonNull)
            .toList();
        releaseAllReservations(reservationIds);   // best-effort, by reservationId (rev 2)
    }

    // State transition + audit
    orderStatusService.validateTransition(order.getStatus(), OrderStatus.CANCELLED);
    order.setStatus(OrderStatus.CANCELLED);
    order.setCancelledAt(Instant.now());
    // ⚠️ KHÔNG markDeleted (rev 2 fix): cancelled order phải VẪN HIỆN trong lịch sử
    // của user + admin list (@SQLRestriction sẽ ẩn row deleted=true → order "biến mất"
    // sau khi cancel là bug). Soft-delete chỉ dành cho admin purge / GDPR flow.
    orderRepository.save(order);

    orderEventPublisher.publishCancelled(order);
    return orderMapper.toResponse(order, orderItemRepository.findByOrderId(order.getId()));
}
```

### 5.4 State transition validation (`OrderStatusService`)

```java
@Service
public class OrderStatusService {
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
        OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
        OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED,   OrderStatus.CANCELLED),
        OrderStatus.SHIPPED,   EnumSet.of(OrderStatus.DELIVERED),
        OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
        OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    public void validateTransition(OrderStatus from, OrderStatus to) {
        if (!ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to)) {
            throw BusinessException.of(ErrorCode.ORDER_INVALID_STATE_TRANSITION, from, to);
        }
    }
}
```

### 5.5 Outbox relay

Same pattern as product + inventory. Single-thread, ORDER BY id ASC, break-on-error to preserve per-aggregate ordering. Schedule: every 5s. Event types: `order.created.v1`, `order.updated.v1`, `order.cancelled.v1`.

### 5.6 Idempotency service

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {
    // rev 3: PK composite (user_id, key) — O-N5: idempotency scope PER USER (Stripe pattern
    // là (account, key)). 2 user dùng trùng key (client buggy gửi key tĩnh) KHÔNG xung đột.
    // Repository lookup: findByUserIdAndKey(userId, key) — không còn check userId thủ công.

    /** Insert row in-flight. Collision (cùng user+key) → re-lookup: replay cached / 409 in-flight. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OrderResponse> begin(UUID userId, String key, String requestHash) {
        if (key == null) return Optional.empty();
        try {
            idempotencyRepository.save(IdempotencyKey.builder()
                .userId(userId).key(key).requestHash(requestHash)
                .responseStatus(0).responseBody("")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plus(ttlHours, ChronoUnit.HOURS))
                .build());                                    // owner — chạy saga
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            IdempotencyKey existing = idempotencyRepository
                .findByUserIdAndKey(userId, key).orElseThrow();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);   // same key, khác payload
            }
            if (existing.getResponseStatus() != 0) {
                return Optional.of(deserialize(existing.getResponseBody()));          // replay — cached response
            }
            throw BusinessException.of(ErrorCode.ORDER_DUPLICATE_REQUEST, key);       // in-flight — retry soon
        }
    }

    /** Update row với response thật — trong CÙNG TX với saga (crash → rollback cả row). */
    @Transactional
    public void complete(UUID userId, String key, OrderResponse response, int status) {
        if (key == null) return;
        IdempotencyKey ik = idempotencyRepository.findByUserIdAndKey(userId, key).orElseThrow();
        ik.setResponseStatus(status);
        ik.setResponseBody(serialize(response));
        idempotencyRepository.save(ik);
    }

    /**
     * Best-effort xoá row in-flight khi saga FAIL (gọi trong catch-all của createOrder,
     * REQUIRES_NEW — TX chính đang rollback). Không có abort, client bị kẹt 409 đến hết
     * TTL (24h). Row đã commit ở begin() nên phải xoá bằng TX riêng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(UUID userId, String key) {
        if (key == null) return;
        try {
            idempotencyRepository.findByUserIdAndKey(userId, key)
                .filter(ik -> ik.getResponseStatus() == 0)
                .ifPresent(idempotencyRepository::delete);
        } catch (Exception ex) {
            log.warn("Failed to abort in-flight idempotency key {}: {}", key, ex.getMessage());
            // Row sẽ được TTL purge — client retry nhận 409 đến hết TTL (accept fallback)
        }
    }
}
```

**`hash(request)` — thuộc `OrderServiceImpl` (O-N3):**

```java
/**
 * requestHash: SHA-256 hex (đúng 64 ký tự — khớp cột request_hash) của JSON serialize
 * OrderCreateRequest. Jackson serialize record theo THỨ TỰ FIELD KHAI BÁO → deterministic
 * cho cùng DTO ⇒ hash ổn định giữa các retry của cùng payload client.
 */
private String hash(OrderCreateRequest request) {
    try {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(request)));
    } catch (NoSuchAlgorithmException | JsonProcessingException ex) {
        throw new IllegalStateException("requestHash computation failed", ex);
    }
}
```

Cleanup: `@Scheduled` daily purge `expires_at < now()` (cron `order.cleanup.idempotency-cron`).

### 5.7 Inter-service clients (`RestClient`)

Each client is a typed wrapper around `RestClient` bean:

```java
@Component
public class InventoryServiceClient implements StockReservationService {
    private final RestClient restClient;

    public InventoryServiceClient(@Qualifier("inventoryRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public UUID reserve(UUID productId, ReserveRequest request) {
        try {
            ReservationResponse resp = restClient.post()
                .uri("/api/v1/inventory/{productId}/reserve", productId)
                .header("Authorization", "Bearer " + serviceTokenProvider.getToken())
                .body(request)
                .retrieve()
                .body(ReservationResponse.class);  // or via ParameterizedTypeReference
            return resp.reservationId();
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new StockReservationFailedException(productId, ex);
            }
            throw new RestClientException("Inventory reserve failed", ex);
        }
    }
    // + release, releaseByOrderId
}
```

**Service token (rev 2 — verified gap):** `KeycloakTokenClient` hiện tại CHỈ có
password grant + authorization-code grant (`utils/common-keycloak/.../KeycloakTokenClient.java`)
— **CHƯA có `client_credentials`**. Cần implement mới `ServiceTokenProvider` (client_id
`order-service` + secret, cache token, refresh trước expiry ~30s skew) trong common-keycloak
hoặc nội bộ order-service. Đây là item PHẢI LÀM trước khi wire `InventoryServiceClient`.

### 5.8 Cache config (product price)

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .cacheDefaults(defaultConfig())
            .withCacheConfiguration("productPrice", priceConfig(Duration.ofMinutes(10)))
            .transactionAware();  // no-arg — defense-in-depth
    }
}
```

Pricing service uses `@Cacheable("productPrice", key="#productId")` on product lookups.

---

## 6. Kafka events

Topic: `shop.order.lifecycle.v1`. Partition key = `aggregateId` (= orderId).

| Event | Trigger | Payload (key fields) |
|---|---|---|
| `order.created.v1` | POST /orders success | orderId, userId, items[], subtotal, taxAmount, discountAmount, total, couponCode, createdAt |
| `order.updated.v1` | State transition CONFIRMED/SHIPPED/DELIVERED | orderId, status, transitionedAt |
| `order.cancelled.v1` | POST /orders/{id}/cancel | orderId, cancelledAt, refunded (bool — true if payment already captured) |

> Consumers (future):
> - **payment-service** — consumes `order.created.v1` → initiates Stripe checkout → publishes `payment.success.v1` or `payment.failed.v1`
> - **notification-service** — consumes all 3 → sends email confirmation / status change / cancellation
> - **search-service** — consumes `order.created.v1` → increments product popularity counter in ES
>
> Idempotency: consumers must use `processed_events` table (not yet implemented in MVP — documented in §10).

---

## 7. Configuration

### 7.1 application.yml

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
    promotion:
      url: ${PROMOTION_SERVICE_URL:http://localhost:8093}
      timeout-ms: 3000
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
    retention-days: 7                     # purge SENT cũ hơn N ngày (rev 3, O-N7 — không hardcode)
    retention-cron: "0 0 3 * * *"    # purge SENT > 7 ngày (same pattern inventory — in MVP scope)
  cleanup:
    expired-cart-days: 7
    cancelled-order-days: 90
    idempotency-cron: "0 0 4 * * *"  # daily 4 AM
```

> **docker-compose delta (rev 2 — verified block hiện tại line 292):** env KHÔNG đúng:
> (1) `KAFKA_SERVERS: kafka:9092` → phải là `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`
> (common-kafka bind `shop.kafka.*`, không đọc `KAFKA_SERVERS`); (2) THIẾU Redis — thêm
> `SPRING_DATA_REDIS_HOST: redis` + depends_on redis (thiếu → `@Cacheable` productPrice
> ném `RedisConnectionFailureException` → 500 mọi pricing call trong compose).

### 7.2 RestClient config

```java
@Configuration
public class RestClientConfig {

    @Bean("productRestClient")
    public RestClient productRestClient(ShopServicesProperties props) {
        return RestClient.builder()
            .baseUrl(props.getProduct().getUrl())
            .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
            .requestFactory(clientHttpRequestFactory(props.getProduct().getTimeoutMs()))
            .build();
    }
    // + inventoryRestClient, taxRestClient, promotionRestClient
}
```

---

## 8. Error handling

> ⚠️ **Rev 2 — verified trong repo:** `ORDER_NOT_FOUND (ORD-4001)` và `CART_NOT_FOUND
> (ORD-4002)` + i18n keys `order.not.found` / `cart.not.found` **ĐÃ TỒN TẠI**
> (`ErrorCode.java:63-64`, `messages_en.properties:65-66`). Bảng rev 1 bị shift numbering
> từ ORD-4002 (gán trùng CART_NOT_FOUND). Đánh số lại: codes mới = **ORD-4003..4010**.
> Lưu ý: templates hiện tại không có placeholder `{0}` — cập nhật thành
> `order.not.found=Order {0} not found` / `cart.not.found=Cart {0} not found`.

| ErrorCode | Value | HTTP | Trạng thái |
|---|---|---|---|
| `ORDER_NOT_FOUND` | `ORD-4001` | NOT_FOUND | **Đã tồn tại** — cập nhật template thêm `{0}`; cũng dùng cho owner mismatch (hide existence) |
| `CART_NOT_FOUND` | `ORD-4002` | NOT_FOUND | **Đã tồn tại** — cập nhật template thêm `{0}` |
| `ORDER_INVALID_STATE` | `ORD-4003` | CONFLICT | Mới — cancel/state không hợp lệ với role hiện tại |
| `ORDER_INVALID_STATE_TRANSITION` | `ORD-4004` | CONFLICT | Mới — transition không hợp lệ (vd SHIPPED → PENDING) |
| `CART_EMPTY` | `ORD-4005` | CONFLICT | Mới — checkout cart rỗng |
| `CART_ITEM_NOT_FOUND` | `ORD-4006` | NOT_FOUND | Mới — cartItemId không thuộc cart hiện tại của user |
| `ORDER_RESERVATION_FAILED` | `ORD-4007` | CONFLICT | Mới — stock reservation thất bại cho 1 item |
| `ORDER_PROMOTION_INVALID` | `ORD-4008` | BAD_REQUEST | Mới — coupon không hợp lệ / hết hạn / min_order_amount không đạt |
| `ORDER_TAX_CALCULATION_FAILED` | `ORD-4009` | BAD_REQUEST | Mới — tax service trả lỗi |
| `ORDER_DUPLICATE_REQUEST` | `ORD-4010` | CONFLICT | Mới — `Idempotency-Key` reused với payload khác, HOẶC request in-flight (§3.7) |

> **Reuse (không tạo code mới):** `PRODUCT_NOT_FOUND` → dùng `PRD-2001` (common-core);
> `STOCK_INSUFFICIENT` → dùng `INV-3002` (common-core). Không cần alias ORD-*.

i18n keys mới (thêm vào `messages_en.properties` + `messages_vi.properties`):
```
order.not.found=Order {0} not found
order.invalid.state=Order {0} cannot transition from current state
order.invalid.transition=Order cannot transition from {0} to {1}
cart.empty=Cart is empty
cart.item.not.found=Cart item {0} not found
order.reservation.failed=Failed to reserve stock for product {0}
order.promotion.invalid=Promotion code {0} is invalid or expired
order.tax.calculation.failed=Failed to calculate tax
order.duplicate.request=Idempotency-Key {0} reused with different request body
```

---

## 9. Testing strategy

| Layer | Tool | Coverage |
|---|---|---|
| Unit | JUnit5 + Mockito + AssertJ | CartService (CRUD), OrderService (cancel + state machine), OrderStatusService (all transitions), IdempotencyService (cache hit/miss/expiry), OutboxRelay (success/retry/break) |
| Slice | `@DataJpaTest` + Testcontainers | OrderRepository (find by user/status), CartRepository (unique constraint), OrderItemRepository (CASCADE) |
| Slice | `@WebMvcTest` + `@MockitoBean` + `@Import(ApiExceptionHandler.class)` | OrderController, CartController (auth, validation, response envelope, 404/409/400) |
| Integration | `@SpringBootTest` + Testcontainers (Postgres + Kafka) + `WireMock` for downstream | Order creation saga (success + compensation on reserve failure), state machine transitions end-to-end, idempotency replay |

**Test stack** — same Boot 4 conventions as inventory + favourite (`@MockitoBean`, `@WebMvcTest` from `webmvc.test.autoconfigure`, `@DataJpaTest` from `data.jpa.test.autoconfigure`, `TestEntityManager` from `jpa.test.autoconfigure`, `@Import(LiquibaseAutoConfiguration.class)`).

**Saga test strategy:**
- Use `WireMock` to stub `inventory-service` reserve endpoint — verify happy path AND reservation-failed compensation
- Verify `release` endpoint is called for each successful reservation when 1 fails
- Verify Order + OrderItems NOT created when saga fails (TX rollback)
- Verify OutboxEvent NOT created when saga fails
- Verify `Idempotency-Key` cached response returned on replay

**State machine test strategy:**
- Parameterized test for ALL transition pairs (5×5 = 25 cases, ~10 allowed + 15 rejected)
- Verify timestamp fields set correctly per transition

---

## 10. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| Kafka consumer for `payment.success.v1` → PENDING → CONFIRMED | Out of MVP scope; admin manually `/confirm` | Phase 8 (when payment-service ships) |
| Kafka consumer for `shop.product.lifecycle.v1` → invalidate productPrice cache | YAGNI for MVP | When price staleness becomes issue |
| `processed_events` table in order-service for idempotent consumer side | Not producing consumer-side events in MVP | When Kafka consumers added |
| Auto-recalc subtotal on Cart update via database trigger | Application-layer recalc is sufficient | Never (premature) |
| Multi-cart per user (e.g., "save for later" feature) | Spec assumes 1 active cart per user | Phase 9+ |
| Address management for shipping | shipping-service owns | When shipping-service ships |
| Partial refunds on cancel-after-CONFIRMED | Requires payment-service integration | Phase 8 |
| ~~Reservation tracking: store `reservationId` on `OrderItem`~~ | **RESOLVED rev 2** — `reservationId` cột trên `OrderItem`, saga lưu ngay khi reserve; cancel/compensation release by reservationId. Không cần endpoint mới ở inventory-service | Done (in scope) |
| OutboxRetentionScheduler (purge SENT > 7 days) | Same pattern as inventory | Phase 7 (in service implementation) |
| OpenAPI/Swagger per-service | common-spring auto-configures | Phase 9 |
| Resilience4j circuit breaker on RestClient calls | Retry via Spring Retry for now | When SLA measured |
| Distributed tracing (OpenTelemetry) | Out of scope | Phase 11 |
| Stock snapshot table for historical reports | Deferred | Phase 9 |

---

## 11. Cross-references

- [`docs/ROADMAP.md` §4 Phase 7](../ROADMAP.md) — order-service scheduled Wk 2–3 (orchestrator)
- [`docs/SERVICE-CATALOG.md` §3](../SERVICE-CATALOG.md) — endpoint catalogue for order + cart
- [`docs/COMMON-LIB-REFERENCE.md §3.3`](../COMMON-LIB-REFERENCE.md) — `AuthenticatedUser` helper from common-security
- [`docs/superpowers/specs/2026-08-26-product-service-design.md`](./2026-08-26-product-service-design.md) — pattern template (Outbox, audit entity, mapper)
- [`docs/superpowers/specs/2026-08-28-inventory-service-design.md`](./2026-08-28-inventory-service-design.md) — pattern template (saga compensation, optimistic lock, scheduler)
- [`docs/superpowers/specs/2026-08-28-favourite-service-design.md`](./2026-08-28-favourite-service-design.md) — pattern template (slim service, JWT auth)
- [`utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java`](../../../utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java) — audit + soft-delete base
- [`utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java`](../../../utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java) — exception factories used in service layer
- [`utils/common-kafka/src/main/java/com/shop/common/kafka/producer/KafkaMessagePublisher.java`](../../../utils/common-kafka/src/main/java/com/shop/common/kafka/producer/KafkaMessagePublisher.java) — `publish(topic, key, payload)`

---

## 12. Changelog

- 2026-08-28: Initial design (this document). Combines Cart + Order into 1 service (workspace divergence from reference); adds OrderItem 1:N relation; introduces saga with compensation pattern; introduces `Idempotency-Key` header; documents state machine + transition table; uses Spring `RestClient` (not Feign) for inter-service calls. Deferred: Kafka consumers for payment/shipping events (admin endpoints in MVP).
- 2026-08-28 (rev 2): Deep review fixes — (1) §8 renumber: ORD-4001/4002 + i18n keys đã tồn tại trong repo, codes mới = ORD-4003..4010; (2) `reservationId` cột mới trên `OrderItem` — cancel/compensation release by reservationId, xoá workaround releaseByOrderId + TODO inventory endpoint; saga gắn `orderId` vào ReserveRequest; (3) cart_items thêm `productTitle`/`unitPrice` snapshot columns — CartResponse không gọi product-service mỗi lần GET, checkout vẫn fetch fresh; (4) idempotency race fix: pre-insert in-flight row (`begin`/`complete`/`abort`) thay lookup-then-store — chặn double-saga khi 2 request cùng key; (5) cancelOrder: sửa điều kiện ĐẢO (USER không cancel được CONFIRMED, được PENDING), bỏ `markDeleted` (cancelled order phải visible), CONFIRMED-cancel không release stock (reservations COMMITTED); (6) saga tạo Order TRƯỚC reserve + fix `orderResponse` dùng trước khi khai báo; (7) tax/promotion degradation flags (chưa tồn tại trong workspace) + fail-closed khi enabled mà service down; (8) §5.7: verified `KeycloakTokenClient` chưa có client_credentials → `ServiceTokenProvider` là item mới bắt buộc; (9) compose delta: `KAFKA_SERVERS` → `SHOP_KAFKA_BOOTSTRAP_SERVERS` + thiếu Redis env; (10) bỏ `IdempotencyConfig` (Liquibase owns schema); (11) outbox retention vào MVP scope; (12) §2.4: 7 open questions resolved.
- 2026-08-28 (rev 3): Review lần 2 (8 items O-N1..O-N8) — (1) **O-N1 P0**: `begin()` return value bị bỏ qua → replay KHÔNG trả cached response mà chạy lại saga (double reserve/charge). Fix: bắt buộc `if (cached.isPresent()) return cached.get()`. (2) **O-N2 P1**: pricing/remote failure không abort in-flight row → kẹt 409 đến hết TTL. Fix: catch-all `RuntimeException` quanh steps 2-8 (extract `doCreateOrder`) gọi `abort()` một điểm duy nhất. (3) **O-N3 P2**: định nghĩa `hash()` — SHA-256 hex 64 ký tự của canonical JSON (Jackson record = deterministic). (4) **O-N4 P2**: `reservationId` là REFERENCE ONLY — không thêm cột `reservationStatus` local (order không consume inventory events trong MVP → sẽ drift); audit state qua inventory-service. (5) **O-N5 P2**: PK `idempotency_keys` → composite `(user_id, key)` — key scope per user, 2 user trùng key không xung đột. (6) **O-N6 P3**: DELIVERED terminal là có chủ ý — return flow cần refund/RMA (Phase 8+), ghi note vào §3.5. (7) **O-N7 P3**: `outbox.retention-days: 7` config thay hardcode. (8) **O-N8 P3**: dời Design decisions → §2.4, Changelog trả về vị trí section cuối (§12) — nhất quán fleet convention. Kèm phát hiện extra: §3.1 stale row `markDeleted on cancel` (trái fix rev 2) → sửa.