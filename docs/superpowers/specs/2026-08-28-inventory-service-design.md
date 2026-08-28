# Inventory Service Design

> **Status:** Design approved by user on 2026-08-28 (rev 2 — incorporated 10 review points), pending implementation plan.
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
| Locking | `@Version` optimistic locking + manual retry loop (ReservationService) |
| Auth | Keycloak JWT + `@PreAuthorize` (admin + SERVICE role cho internal) |
| Common | `common-spring`, `common-core`, `common-security`, `common-kafka`, `common-logging` |

---

## 2. Architecture

### 2.1 Package structure (`com.shop.inventoryservice.*`)

```
config/             CacheConfig (Redis cache manager, transactionAware)
controller/         InventoryController (CRUD + reserve/commit/release)
dto/
  request/          InventoryUpsertRequest, ReserveRequest, ...
  response/         InventoryResponse, ReservationResponse, ...
entity/             Inventory, Reservation, ReservationStatus, OutboxEvent, OutboxStatus
repository/         InventoryRepository, ReservationRepository, OutboxEventRepository
service/
  InventoryService        interface + InventoryServiceImpl
  ReservationService      interface + ReservationServiceImpl
  InventoryEventPublisher // writes OutboxEvent in same @Transactional
  InventoryOutboxRelay    // @Scheduled single-thread poller → Kafka
  InventoryCacheService   // cache-aside read + sync invalidation (transactionAware)
mapper/             InventoryMapper (ModelMapper @Component)
```

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `inventoryservice` DB, 3 tables (inventory, reservations, outbox_events) |
| Redis 7 | `spring-boot-starter-data-redis` + `@EnableCaching` | Cache key `inventory:{productId}`; TTL 60s; invalidate on write (transactionAware) |
| Kafka | `spring-kafka` + `common-kafka` (`KafkaMessagePublisher`) | Topic `shop.inventory.events.v1` (5 events, key = productId) |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize` cho admin/internal endpoints |

### 2.3 Decisions & rationale

- **Kiến trúc Y — Sync write + async outbox (user đã phê duyệt):**
  - Cache invalidation xảy ra **sau commit DB** (transactionAware cache / afterCommit hook).
  - Outbox → Kafka dành cho **các service khác** consume.
  - Không self-consume event để invalidate cache (tránh độ trễ + thêm điểm lỗi).
- **Reservation entity riêng (Cách B):** đầy đủ lifecycle, trace lịch sử.
- **Optimistic locking (`@Version`) + manual retry loop (max 3 attempts, backoff 50ms):**
  wrap trong `ReservationService` (§5.7), throw `INVENTORY_VERSION_CONFLICT` nếu vẫn fail.
  (KHÔNG dùng `@Retryable` — cần `@EnableRetry` chưa có, self-invocation không proxy.)
- **Hard delete:** stock là transactional data — xóa thật, không soft-delete. **Chỉ xóa khi không còn
  reservation PENDING/COMMITTED** (xem §4.1).
- **Outbox pattern:** kế thừa product-service (`OutboxEvent` entity + `@Scheduled` relay).
- **Event ordering:** Kafka partition key = `productId`. OutboxRelay chạy **single-thread, sắp xếp
  theo `id` tăng dần** để giữ thứ tự per-aggregate (xem §6).

---

## 3. Data model

### 3.1 Inventory

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK, `@GeneratedValue(UUID)` |
| `productId` | `UUID` | not null, unique |
| `availableQuantity` | `Integer` | not null, default 0, >= 0 |
| `reservedQuantity` | `Integer` | not null, default 0, >= 0 |
| `version` | `Long` | `@Version` — optimistic lock |
| `lastUpdated` | `Instant` | on update |

> **Không extends `AbstractMappedEntity`** (hard delete). Chỉ giữ `lastUpdated`.
> **Null cache:** `disableCachingNullValues()` — productId không tồn tại không được cache.
> Rủi ro: attacker có thể spam productId giả → load DB. Có thể cache null với TTL ngắn (30s)
> nếu cần chống spam, nhưng chấp nhận hiện tại.

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

### 3.4 OutboxEvent (kế thừa product-service + bổ sung aggregate_id làm Kafka key)

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK, identity |
| `eventId` | `String` | not null, unique (UUID) |
| `aggregateType` | `String` | not null, "Inventory" |
| `aggregateId` | `UUID` | not null, **= productId** — dùng làm Kafka partition key |
| `eventType` | `String` | not null |
| `topic` | `String` | not null |
| `payload` | `String` (TEXT) | not null, JSON |
| `status` | `OutboxStatus` enum | PENDING / SENT / FAILED |
| `retryCount` | `Integer` | not null, default 0 |
| `sentAt` | `Instant` | nullable |
| `lastError` | `String` | nullable |

> **Khác product-service:** entity KHÔNG extends `AbstractMappedEntity` (hard delete, không soft-delete).
> Bảng `outbox_events` KHÔNG cần cột soft-delete. Relay đọc `aggregateId` để set Kafka key.

Indexes: `eventId` UNIQUE, `status`, `aggregateId`.

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
| `POST` | `/api/v1/inventory` | ADMIN | `InventoryUpsertRequest { productId, availableQuantity }` | `ApiResponse<InventoryResponse>` | Create (409 nếu tồn tại) |
| `PUT` | `/api/v1/inventory/{productId}` | ADMIN | `InventoryUpsertRequest { availableQuantity }` | `ApiResponse<InventoryResponse>` | Update (optimistic lock) |
| `DELETE` | `/api/v1/inventory/{productId}` | ADMIN | — | `ApiResponse<Void>` | Hard delete — **chỉ khi không còn reservation PENDING/COMMITTED** (nếu còn → 409 `RESERVATION_INVALID_STATE`) |

> **Chính sách delete:** kiểm tra `reservationRepository.countByProductIdAndStatusIn(productId, [PENDING, COMMITTED]) > 0`
> → throw 409. Nếu không có, hard delete inventory. (Tự động release expired reservations là job riêng — §10.)

### 4.2 Reservation endpoints — internal (order-service)

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `POST` | `/api/v1/inventory/{productId}/reserve` | SERVICE | `ReserveRequest { quantity, orderId? }` | `ApiResponse<ReservationResponse>` | Reserve stock |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/commit` | SERVICE | — | `ApiResponse<Void>` | Commit |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/release` | SERVICE | — | `ApiResponse<Void>` | Release |

> **Bảo mật internal:** dùng Keycloak realm role `SERVICE`. Order-service lấy token qua
> client-credentials grant (client id riêng, scope `inventory:write`).
> `JwtRolesConverter` (common-security) emit cả `SERVICE` và `ROLE_SERVICE` → dùng
> `@PreAuthorize("hasRole('SERVICE')` (Spring Security tự map `ROLE_SERVICE` prefix).
> Xem `JwtRolesConverter.java` để biết authority mapping chi tiết.

### 4.3 Response DTOs

- `InventoryResponse { productId, availableQuantity, reservedQuantity, lastUpdated }`
- `ReservationResponse { reservationId, productId, quantity, status, expiresAt, orderId }`

### 4.4 Validation (Bean Validation, `@Valid` trên controller)

- `InventoryUpsertRequest`: `productId` `@NotNull`; `availableQuantity` `@NotNull @Min(0)`
- `ReserveRequest`: `quantity` `@NotNull @Positive`; `orderId` optional

---

## 5. Service layer

### 5.0 Cache transaction-awareness (CacheConfig)

> **Retry optimistic lock:** KHÔNG dùng `@Retryable` / `@EnableRetry` (cần thêm dependency
> + không proxy được self-invocation). Dùng **manual retry loop** trong `ReservationService`
> (§5.7). Nếu sau này chuyển sang `@Retryable`, thêm `RetryConfig` với `@EnableRetry`
> (track trong Open items).

```java
@Bean
public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
    RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
        .entryTtl(Duration.ofSeconds(60))
        .disableCachingNullValues()
        .computePrefixWith(name -> name + "::");
    return RedisCacheManager.builder(connectionFactory)
        .cacheDefaults(config)
        .transactionAware(true)   // evict chạy SAU commit — chống cache-miss oan
        .build();
}
```

> Với `transactionAware(true)`, `@CacheEvict` trên method service chỉ thực sự xóa Redis
> sau khi transaction commit thành công. Rollback → không xóa.

### 5.1 Reserve flow (write path, optimistic lock)

```java
@Transactional
@CacheEvict(value = "inventory", key = "#productId")   // productId là tham số method → SpEL OK
public ReservationResponse reserve(UUID productId, ReserveRequest request) {
    // 1. Release expired TRƯỚC khi đọc Inventory (method này cập nhật Inventory + tăng @Version)
    releaseExpiredReservations(productId);
    // 2. Đọc Inventory sau khi đã release expired — dữ liệu mới nhất
    Inventory inv = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
    // 3. Tính available trên bản inventory đã được release expired
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
    return mapper.toReservationResponse(reservation);
}
```

- Cache evict: `@CacheEvict` + transactionAware cache → evict sau commit.
- **Optimistic lock retry:** KHÔNG dùng `@Retryable` ở đây — wrap qua `ReservationService.reserveWithRetry` (§5.7)
  vì `@Retryable` cần `@EnableRetry` (chưa có) và self-invocation không proxy được.

#### releaseExpiredReservations(productId) — chi tiết

> Gọi **đầu tiên** trong reserve (trước khi đọc Inventory) để tránh đọc stale inventory.

```java
// private method trong InventoryServiceImpl — chạy trong cùng @Transactional của reserve
private void releaseExpiredReservations(UUID productId) {
    List<Reservation> expired = reservationRepository
        .findByProductIdAndStatusAndExpiresAtBefore(productId, ReservationStatus.PENDING, Instant.now());
    if (expired.isEmpty()) return;

    Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow(
        () -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
    int total = expired.stream().mapToInt(Reservation::getQuantity).sum();
    inv.setReservedQuantity(inv.getReservedQuantity() - total);
    expired.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));

    reservationRepository.saveAll(expired);
    inventoryRepository.save(inv);   // tăng @Version — cần gọi TRƯỚC khi reserve đọc inv
}
```

> **Lưu ý thứ tự gọi:** vì method này cập nhật Inventory (tăng version), gọi nó **trước**
> khi `findByProductId` trong reserve để không đọc bản stale. Như vậy reserve tính
> `available = availableQuantity - reservedQuantity` trên bản inventory đã release expired.
> Nếu vẫn xảy ra `OptimisticLockingFailureException` (concurrent), `reserveWithRetry` (§5.7)
> sẽ retry — mỗi lần retry gọi lại reserve (bao gồm release expired).

### 5.2 Commit flow

> **Cache evict:** KHÔNG dùng `@CacheEvict` với SpEL `#r.productId` (r không phải tham số method — lỗi runtime).
> Dùng `inventoryCacheService.evictAfterCommit(r.getProductId())` thủ công — helper bọc
> `TransactionSynchronizationManager.registerSynchronization(afterCommit)` để evict chỉ chạy sau commit.

```java
@Transactional
public void commit(UUID reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
    if (r.getStatus() != ReservationStatus.PENDING) {
        throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
    }
    if (r.getExpiresAt().isBefore(Instant.now())) {
        r.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.save(r);
        throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
    }
    Inventory inv = inventoryRepository.findByProductId(r.getProductId()).orElseThrow(
        () -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, r.getProductId()));
    inv.setAvailableQuantity(inv.getAvailableQuantity() - r.getQuantity());
    inv.setReservedQuantity(inv.getReservedQuantity() - r.getQuantity());
    r.setStatus(ReservationStatus.COMMITTED);
    r.setCommittedAt(Instant.now());
    inventoryRepository.save(inv);
    reservationRepository.save(r);
    inventoryEventPublisher.publishCommitted(inv, r);
    inventoryCacheService.evictAfterCommit(r.getProductId());   // evict sau commit
}
```

**Optimistic lock retry cho commit:** wrap trong `ReservationService` (tầng gọi) — xem §5.7.

### 5.3 Release flow

> **Cache evict:** dùng `inventoryCacheService.evictAfterCommit(r.getProductId())` thủ công (sau commit).

```java
@Transactional
public void release(UUID reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
    if (r.getStatus() != ReservationStatus.PENDING) {
        throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
    }
    if (r.getExpiresAt().isBefore(Instant.now())) {
        r.setStatus(ReservationStatus.EXPIRED);
        reservationRepository.save(r);
        throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
    }
    Inventory inv = inventoryRepository.findByProductId(r.getProductId()).orElseThrow(
        () -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, r.getProductId()));
    inv.setReservedQuantity(inv.getReservedQuantity() - r.getQuantity());
    r.setStatus(ReservationStatus.RELEASED);
    r.setReleasedAt(Instant.now());
    inventoryRepository.save(inv);
    reservationRepository.save(r);
    inventoryEventPublisher.publishReleased(inv, r);
    inventoryCacheService.evictAfterCommit(r.getProductId());   // evict sau commit
}
```

**Optimistic lock retry cho release:** wrap trong `ReservationService` — xem §5.7.

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
    save("inventory.reserved.v1", inv.getProductId(), Map.of(
        "eventId", eventId, "eventType", "inventory.reserved.v1",
        "occurredAt", Instant.now().toString(),
        "productId", inv.getProductId(), "quantity", r.getQuantity(),
        "reservationId", r.getId(), "orderId", r.getOrderId()));
}
// + publishCommitted, publishReleased, publishAdjusted (upsert), publishDeleted
```

### 5.6 Outbox relay — single-thread, giữ thứ tự per-aggregate

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryOutboxRelay {
    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval-ms:5000}")
    public void relay() {
        // Repository signature: findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable)
        // → List<OutboxEvent> hoặc Page<OutboxEvent> (Spring Data JPA support cả hai)
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        for (OutboxEvent event : pending) {   // single-thread, tuần tự
            try {
                kafkaPublisher.publish(event.getTopic(),
                    event.getAggregateId().toString(),  // Kafka key = productId
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
                }
                outboxRepo.save(event);
                break;   // ⚠️ STOP: fail event → dừng ngay, KHÔNG gửi event sau.
                         // Giữ thứ tự per-aggregate. Event fail vẫn PENDING → retry poll sau.
            }
        }
    }
}
```

> **Thứ tự:** query `ORDER BY id ASC` + publish tuần tự single-thread → event của cùng productId
> được gửi theo đúng thứ tự tạo. Không dùng parallel — inventory throughput không cao.

---


### 5.7 ReservationService — optimistic lock retry wrapper

Vì `@CacheEvict` không dùng SpEL (fix 1), retry optimistic lock được wrap ở tầng gọi
(`ReservationService`) thay vì trên chính method `@Transactional`:

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final InventoryService inventoryService;   // method @Transactional bên trong

    /** Retry reserve khi OptimisticLockingFailureException (tối đa 3 lần). */
    public ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request) {
        int attempts = 0;
        while (true) {
            try {
                return inventoryService.reserve(productId, request);
            } catch (OptimisticLockingFailureException ex) {
                if (++attempts >= 3) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, productId);
                }
                sleep(50L * attempts);   // backoff 50ms, 100ms
            }
        }
    }

    public void commitWithRetry(UUID reservationId) {
        int attempts = 0;
        while (true) {
            try {
                inventoryService.commit(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempts >= 3) throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
                sleep(50L * attempts);
            }
        }
    }

    public void releaseWithRetry(UUID reservationId) {
        // tương tự commitWithRetry
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> **Chọn manual retry loop thay vì `@Retryable`:** `@Retryable` (spring-retry) cần
> `@EnableRetry` + dependency riêng (chưa có trong common-spring). Resilience4j `@Retry`
> đã có sẵn nhưng yêu cầu `@Retry(name=...)` + fallback — thêm phức tạp cho optimistic lock
> (cần re-read entity mới sau retry, self-invocation không proxy được). Manual loop đơn giản,
> dễ test, không phụ thuộc thêm.

---

## 6. Kafka events

Topic: `shop.inventory.events.v1`. Partition key = `aggregateId` (= productId).

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
  # Cache: KHÔNG cấu hình spring.cache.redis.* — CacheConfig bean là single source of truth
  # (entryTtl 60s, disableCachingNullValues, transactionAware). Các thuộc tính yml sẽ bị
  # bỏ qua khi bean RedisCacheManager được khai báo tường minh.
  cache:
    type: redis
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

> **Cache TTL:** dùng `CacheConfig` bean tường minh (transactionAware) — cấu hình properties
> chỉ là fallback. `CacheConfig` là single source of truth cho TTL/null/prefix.

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
| Integration | `@SpringBootTest` + Testcontainers | OutboxRelay end-to-end (reserve → event → Kafka), optimistic lock retry |

Test stack (Boot 4.1.1 — verified từ product-service source):
- `@WebMvcTest` ở `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`)
- `@DataJpaTest` ở `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`)
- `TestEntityManager` ở `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`)
- `@MockitoBean` ở `org.springframework.test.context.bean.override.mockito.MockitoBean` (không phải `@MockBean`)
  — **Chỉ dùng trong slice test (`@WebMvcTest`, `@DataJpaTest`).** `@SpringBootTest` dùng `@MockitoBean` hoặc
  Testcontainers thật (không mock). Verified từ product-service controller tests.
- `@Import` cần `org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration` (package `boot.liquibase.autoconfigure`, KHÔNG phải `boot.autoconfigure.liquibase`)
- `@AutoConfigureMockMvc(addFilters = false)` — tắt security filter ở slice (pattern auth-service)

---

## 10. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| Expired reservation cleanup scheduler | Job quét reservation EXPIRED → release stock | **Phase sau nhưng có plan tạm:** `@Scheduled` trong service (release expired) |
| Debezium CDC thay vì @Scheduled relay | Độ trễ thấp hơn, cần Kafka Connect | Khi scale |
| processed_events table cho consumer | Inventory không self-consume — cần khi có consumer ngoài | Khi order/notification consume |
| ShedLock cho OutboxRelay multi-instance | Hiện 1 instance | Khi scale > 1 |
| Flash-sale Redis Lua check&trừ | Hiệu năng cực cao, kiến trúc khác | Deferred |
| Product-service sync khởi tạo inventory | Khi product created → tạo inventory record | Có thể thêm consumer product events sau |

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
- 2026-08-28 (rev 2): Incorporate 10 review points — transactionAware cache evict, @Retryable optimistic
  lock, single-thread outbox relay ordering, outbox aggregate_id = productId, delete policy khi còn
  reservation, Bean Validation, SERVICE role cho internal endpoints, expired cleanup plan, CacheConfig
  tường minh, validation DTO.
- 2026-08-28 (rev 3): Fix 7 review points — (1) SpEL `#r.productId` → `evictAfterCommit()` thủ công,
  (2) bỏ `spring.retry` yml sai chuẩn, (3) `hasRole('SERVICE')` thống nhất JwtRolesConverter,
  (4) OutboxRelay stop-on-error giữ thứ tự, (5) releaseExpiredReservations trong reserve,
  (6) null cache note, (7) test package verify từ source (boot.liquibase.autoconfigure).
- 2026-08-28 (rev 4): Cleanup — (1) xác nhận spring.retry đã bỏ, (2) @PreAuthorize hasRole('SERVICE'),
  (3) outbox relay break-on-error giữ thứ tự, (4) bỏ spring.cache.redis.* (CacheConfig single source
  of truth), (5) thêm @EnableRetry trong RetryConfig (chỉ khi chuyển sang @Retryable).
