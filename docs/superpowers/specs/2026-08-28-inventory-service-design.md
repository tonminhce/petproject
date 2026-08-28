# Inventory Service Design

> **Status:** Design approved by user on 2026-08-28, pending implementation plan.
> **Path:** `docs/superpowers/specs/2026-08-28-inventory-service-design.md`
> **Author:** user + agent
> **Reference:** [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices) (inventory-service module)

---

## 1. Overview

Inventory-service quản lý tồn kho (stock) của sản phẩm: `available_quantity`, `reserved_quantity`,
và lifecycle **reserve → commit / release** qua entity `Reservation` riêng. Service phục vụ:

- **Read path**: check stock nhanh qua Redis cache (cache-aside, TTL ngắn) — phù hợp high-traffic.
- **Write path**: reserve/commit/release với **optimistic locking** (`@Version`) chống lost update,
  đồng thời ghi **transactional outbox** → publish event lên Kafka cho các service khác
  (order, notification, search).
- **Persistence cao**: outbox pattern đảm bảo "DB change" và "event publish" là một khối atomic —
  không mất event kể cả khi crash giữa chừng.

**Bounded context:** Inventory & stock reservation (internal — order-service là consumer chính).

**Tech stack (Spring Boot 4.1.1, Java 25, package `com.shop.*`):**

| Layer | Technology |
|---|---|
| Persistence | PostgreSQL 16 + Liquibase + Spring Data JPA |
| Cache | Redis 7 + Spring Cache (cache-aside, TTL 60s) |
| Events | Apache Kafka + Transactional Outbox (giống product-service) |
| Locking | `@Version` optimistic locking (JPA) |
| Auth | Keycloak JWT + `@PreAuthorize` |
| Common | `common-spring`, `common-core`, `common-security`, `common-kafka`, `common-logging` |

---

## 2. Architecture

### 2.1 Package structure (`com.shop.inventoryservice.*`)

```
config/             CacheConfig (Redis cache manager)
controller/         InventoryController (CRUD + reserve/commit/release)
dto/
  request/          InventoryUpsertRequest, ReserveRequest, ...
  response/         InventoryResponse, ReservationResponse, ...
entity/             Inventory, Reservation, ReservationStatus
repository/         InventoryRepository, ReservationRepository, OutboxEventRepository
service/
  InventoryService        interface + InventoryServiceImpl
  ReservationService      interface + ReservationServiceImpl
  InventoryEventPublisher // writes OutboxEvent in same @Transactional
  InventoryOutboxRelay    // @Scheduled poller → Kafka
  InventoryCacheService   // cache-aside read + sync invalidation
mapper/             InventoryMapper (ModelMapper @Component)
```

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `inventoryservice` DB, 3 tables (inventory, reservations, outbox_events) |
| Redis 7 | `spring-boot-starter-data-redis` + `@EnableCaching` | Cache key `inventory:{productId}`; TTL 60s; invalidate on write |
| Kafka | `spring-kafka` + `common-kafka` (`KafkaMessagePublisher`) | Topic `shop.inventory.events.v1` (5 events, key = productId) |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize` cho admin/internal endpoints |

### 2.3 Decisions & rationale

- **Kiến trúc Y — Sync write + async outbox (user đã phê duyệt):**
  - Cache invalidation xảy ra **đồng bộ ngay sau commit DB** trong write path (xóa Redis key).
  - Outbox → Kafka dành cho **các service khác** consume.
  - Không self-consume event để invalidate cache (tránh độ trễ + thêm điểm lỗi).
- **Reservation entity riêng (Cách B):** đầy đủ lifecycle, trace lịch sử.
- **Optimistic locking (`@Version`)** thay vì pessimistic: gọi nội bộ, ít conflict, tránh deadlock, retry tự động.
- **Hard delete:** stock là transactional data — xóa thật, không soft-delete.
- **Outbox pattern:** kế thừa product-service (`OutboxEvent` entity + `@Scheduled` relay).
- **Event ordering:** Kafka partition key = `productId` → mọi event của cùng product vào cùng partition,
  consumer xử lý tuần tự.

---

## 3. Data model

### 3.1 Inventory

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK, `@GeneratedValue(UUID)` |
| `productId` | `UUID` | not null, unique |
| `availableQuantity` | `Integer` | not null, default 0 |
| `reservedQuantity` | `Integer` | not null, default 0 |
| `version` | `Long` | `@Version` — optimistic lock |
| `lastUpdated` | `Instant` | on update |

> **Không extends `AbstractMappedEntity`** (hard delete). Chỉ giữ `lastUpdated`.

Entity: `@Entity @Table(name = "inventory") @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.

### 3.2 Reservation

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `productId` | `UUID` | not null, indexed |
| `quantity` | `Integer` | not null, > 0 |
| `status` | `ReservationStatus` enum | PENDING / COMMITTED / RELEASED / EXPIRED |
| `createdAt` | `Instant` | not null |
| `expiresAt` | `Instant` | not null |
| `committedAt` | `Instant` | nullable |
| `releasedAt` | `Instant` | nullable |
| `orderId` | `UUID` | nullable |

Entity: `@Entity @Table(name = "reservations")`.

### 3.3 ReservationStatus enum

```java
public enum ReservationStatus { PENDING, COMMITTED, RELEASED, EXPIRED }
```

### 3.4 OutboxEvent

Giống hệt product-service `OutboxEvent` — copy entity + repository.

### 3.5 Liquibase

```
inventory-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changelog-001-initial-schema.yaml     (inventory, reservations, outbox_events)
```

Thứ tự tạo: `inventory` → `reservations` → `outbox_events`.

---

## 4. API surface

### 4.1 Endpoints — `/api/v1/inventory`

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `GET` | `/api/v1/inventory` | USER/ADMIN | — `?page=&size=` | `ApiResponse<PageResponse<InventoryResponse>>` | Paginated list |
| `GET` | `/api/v1/inventory/{productId}` | USER/ADMIN | — | `ApiResponse<InventoryResponse>` | Cache-aside read |
| `POST` | `/api/v1/inventory` | ADMIN | `InventoryUpsertRequest { productId, availableQuantity }` | `ApiResponse<InventoryResponse>` | Create/upsert |
| `PUT` | `/api/v1/inventory/{productId}` | ADMIN | `InventoryUpsertRequest { availableQuantity }` | `ApiResponse<InventoryResponse>` | Update (optimistic lock) |
| `DELETE` | `/api/v1/inventory/{productId}` | ADMIN | — | `ApiResponse<Void>` | Hard delete |

### 4.2 Reservation endpoints — internal (order-service)

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `POST` | `/api/v1/inventory/{productId}/reserve` | internal | `ReserveRequest { quantity, orderId? }` | `ApiResponse<ReservationResponse>` | Reserve stock |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/commit` | internal | — | `ApiResponse<Void>` | Commit |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/release` | internal | — | `ApiResponse<Void>` | Release |

### 4.3 Response DTOs

- `InventoryResponse { productId, availableQuantity, reservedQuantity, lastUpdated }`
- `ReservationResponse { reservationId, productId, quantity, status, expiresAt }`

---

## 5. Service layer

### 5.1 Reserve flow (write path, optimistic lock)

```java
@Transactional
public ReservationResponse reserve(UUID productId, ReserveRequest request) {
    Inventory inv = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
    int available = inv.getAvailableQuantity() - inv.getReservedQuantity();
    if (available < request.quantity()) {
        throw BusinessException.of(ErrorCode.STOCK_INSUFFICIENT, productId);
    }
    inv.setReservedQuantity(inv.getReservedQuantity() + request.quantity());
    inventoryRepository.save(inv);

    Reservation reservation = Reservation.builder()
        .productId(productId).quantity(request.quantity())
        .status(ReservationStatus.PENDING).createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(reservationTtlSeconds))
        .orderId(request.orderId()).build();
    reservationRepository.save(reservation);

    inventoryEventPublisher.publishReserved(inv, reservation);  // outbox same TX
    inventoryCacheService.evict(productId);  // sync invalidation (afterCommit hook)
    return mapper.toReservationResponse(reservation);
}
```

> Cache invalidation chạy **sau commit** — dùng `TransactionSynchronizationManager` afterCommit hook.

### 5.2 Commit flow

```java
@Transactional
public void commit(UUID reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
    if (r.getStatus() != ReservationStatus.PENDING) throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
    if (r.getExpiresAt().isBefore(Instant.now())) { r.setStatus(EXPIRED); ... }
    Inventory inv = inventoryRepository.findByProductId(r.getProductId()).orElseThrow(...);
    inv.setAvailableQuantity(inv.getAvailableQuantity() - r.getQuantity());
    inv.setReservedQuantity(inv.getReservedQuantity() - r.getQuantity());
    r.setStatus(COMMITTED); r.setCommittedAt(Instant.now());
    // save both, publish outbox, evict cache
}
```

### 5.3 Release flow

```java
@Transactional
public void release(UUID reservationId) {
    Reservation r = ...;
    if (r.getStatus() != PENDING) throw ...;
    Inventory inv = ...;
    inv.setReservedQuantity(inv.getReservedQuantity() - r.getQuantity());
    r.setStatus(RELEASED); r.setReleasedAt(Instant.now());
    // save both, publish outbox, evict cache
}
```

### 5.4 Read path (cache-aside)

```java
@Cacheable(value = "inventory", key = "#productId")  // TTL 60s via CacheConfig
public InventoryResponse findById(UUID productId) {
    return inventoryRepository.findByProductId(productId)
        .map(mapper::toResponse)
        .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
}
```

### 5.5 Event publisher (outbox)

Giống hệt `TransactionalProductEventPublisher` — viết `OutboxEvent` row trong cùng transaction:

```java
public void publishReserved(Inventory inv, Reservation r) {
    save("inventory.reserved.v1", Map.of(
        "eventId", eventId, "eventType", "inventory.reserved.v1",
        "occurredAt", Instant.now().toString(),
        "productId", inv.getProductId(), "quantity", r.getQuantity(),
        "reservationId", r.getId(), "orderId", r.getOrderId()));
}
// + publishCommitted, publishReleased, publishAdjusted (upsert), publishDeleted
```

### 5.6 Outbox relay

Copy `OutboxRelay` từ product-service (same `@Scheduled` + `KafkaMessagePublisher` + retry/FAILED).

---

## 6. Kafka events

Topic: `shop.inventory.events.v1`. Partition key = `productId`.

| Event | Payload (key fields) |
|---|---|
| `inventory.reserved.v1` | productId, reservationId, quantity, orderId, expiresAt |
| `inventory.committed.v1` | productId, reservationId, quantity, orderId |
| `inventory.released.v1` | productId, reservationId, quantity, orderId |
| `inventory.adjusted.v1` | productId, availableQuantity (upsert/adjust) |
| `inventory.deleted.v1` | productId |

> Consumers tương lai: order-service, notification, search. Idempotency qua `processed_events`
> table khi có consumer ngoài (inventory-service không self-consume).

---

## 7. Configuration

### 7.1 application.yml

```yaml
spring:
  application: { name: inventory-service }
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/inventoryservice}
    username: ${POSTGRES_USER:admin}
    password: ${POSTGRES_PASSWORD:admin}
  jpa:
    hibernate: { ddl-auto: validate }
    open-in-view: false
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      password: ${SPRING_DATA_REDIS_PASSWORD:}
  cache:
    type: redis
    redis: { time-to-live: 60000, cache-null-values: false, use-key-prefix: true }
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

server:
  port: ${SERVER_PORT:8082}

shop:
  kafka:
    bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer: { acks: all, retries: 3 }
  security:
    public-paths:
      - method: GET
        path: /api/v1/inventory/**

inventory:
  reservation-ttl-seconds: 900          # 15 min
  outbox:
    poll-interval-ms: 5000
    batch-size: 100
    max-retries: 10
```

---

## 8. Error handling

| ErrorCode (add) | Value | HTTP |
|---|---|---|
| `RESERVATION_NOT_FOUND` | `INV-3003` | NOT_FOUND |
| `RESERVATION_EXPIRED` | `INV-3004` | CONFLICT |
| `RESERVATION_INVALID_STATE` | `INV-3005` | CONFLICT |
| `INVENTORY_ALREADY_EXISTS` | `INV-3006` | CONFLICT |
| `INVENTORY_VERSION_CONFLICT` | `INV-3007` | CONFLICT (optimistic lock retry exhausted) |

i18n keys thêm vào `messages_en.properties` + `messages_vi.properties`.

---

## 9. Testing strategy

| Layer | Tool | Coverage |
|---|---|---|
| Unit | JUnit5 + Mockito + AssertJ | InventoryService (reserve/commit/release/upsert/delete), ReservationService |
| Slice | `@DataJpaTest` + Testcontainers | InventoryRepository (optimistic lock), ReservationRepository |
| Slice | `@WebMvcTest` + `@MockitoBean` | InventoryController, ReservationController |
| Integration | `@SpringBootTest` + Testcontainers | OutboxRelay end-to-end (reserve → event → Kafka) |

---

## 10. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| Expired reservation cleanup scheduler | Cần job quét reservation hết hạn → release stock | Phase sau (hoặc @Scheduled trong service) |
| Debezium CDC thay vì @Scheduled relay | Độ trễ thấp hơn, nhưng cần Kafka Connect | Khi scale |
| processed_events table cho consumer | Inventory không self-consume — chỉ cần khi có consumer ngoài | Khi order/notification consume |
| ShedLock cho OutboxRelay multi-instance | Hiện 1 instance | Khi scale > 1 |
| Flash-sale Redis Lua check&trừ | Hiệu năng cực cao, kiến trúc khác | Deferred |

---

## 11. Cross-references

- `docs/RATE-LIMIT.md` — gateway rate limit (inventory qua gateway)
- `docs/ARCHITECTURE.md` — §1 component map, §5 data stores, §6 cross-cutting
- `docs/ROADMAP.md` — Phase 7 status (inventory = next core service)
- `product-service` — mẫu outbox + cache + mapper pattern (đã shipped)
- `docs/superpowers/specs/2026-08-26-product-service-design.md` — spec mẫu

---

## 12. Changelog

- 2026-08-28: Initial design — Kiến trúc Y (sync write + async outbox), Reservation entity (Cách B),
  optimistic locking, hard delete, cache-aside TTL 60s, outbox → Kafka.
