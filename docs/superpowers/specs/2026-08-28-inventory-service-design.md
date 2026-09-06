# Inventory Service Design

> **Status:** Design approved by user on 2026-08-28 (rev 2  -  incorporated 10 review points), pending implementation plan.
> **Path:** `docs/superpowers/specs/2026-08-28-inventory-service-design.md`
> **Author:** user + agent
> **Reference:** [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices) (inventory-service module)

---

## 1. Overview

Inventory-service manages product inventory stock (`available_quantity`, `reserved_quantity`)
and the **reserve -> commit / release** lifecycle through dedicated `Reservation` entities. The service provides:

- **Read path**: Fast stock lookups via Redis cache (cache-aside with short TTL), designed for high traffic.
- **Write path**: reserve/commit/release operations with **optimistic locking** (`@Version`) preventing lost updates,
  writing simultaneously to a **transactional outbox** to dispatch events to Kafka for downstream services
  (order, notification, search).
- **High durability**: The transactional outbox pattern guarantees that database state changes and event dispatch
  remain atomic, preventing lost events even during unexpected node failures.

**Bounded context:** Inventory and stock reservations (internal platform service, primarily consumed by order-service).

**Tech stack (Spring Boot 4.1.1, Java 25, package `com.shop.*`):**

| Layer | Technology |
|---|---|
| Persistence | PostgreSQL 16 + Liquibase + Spring Data JPA |
| Cache | Redis 7 + Spring Cache (cache-aside, TTL 60s) |
| Events | Apache Kafka + Transactional Outbox (same pattern as product-service) |
| Locking | `@Version` optimistic locking + manual retry loop (ReservationService) |
| Auth | Keycloak JWT + `@PreAuthorize` (admin + SERVICE role cho internal) |
| Common | `common-spring`, `common-core`, `common-security`, `common-kafka`, `common-logging` |

---

## 2. Architecture

### 2.1 Package structure (`com.shop.inventoryservice.*`)

```
config/             CacheConfig (Redis customizer  -  transactionAware defense-in-depth)
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
  ReservationCleanupScheduler // @Scheduled sweeps expired PENDING reservations (section 5.8)
  OutboxRetentionScheduler    // @Scheduled purges old SENT outbox rows (section 5.8)
  InventoryCacheService   // cache-aside read + sync invalidation (afterCommit)
mapper/             InventoryMapper (ModelMapper @Component)
```

> **Entrypoint:** `InventoryServiceApplication` = `@SpringBootApplication` **+ `@EnableScheduling`**.
> Mandatory: without `@EnableScheduling`, `InventoryOutboxRelay` (section 5.6) and both schedulers in section 5.8
> will **never execute**, resulting in silent failures where applications start but events are never published.

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `inventoryservice` DB, 3 tables (inventory, reservations, outbox_events) |
| Redis 7 | `spring-boot-starter-data-redis` + `@EnableCaching` | Cache key `inventory:{productId}`; TTL 60s; invalidate on write (transactionAware) |
| Kafka | `spring-kafka` + `common-kafka` (`KafkaMessagePublisher`) | Topic `shop.inventory.events.v1` (5 events, key = productId) |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize` cho admin/internal endpoints |

### 2.3 Decisions & rationale

- **Dual-path architecture (Sync write + async outbox):**
  - Cache invalidation occurs **after database commit** via `InventoryCacheService.evictAfterCommit()`
    (after-commit hook invoked across all write operations). `transactionAware()` on the cache manager acts as defense-in-depth (see section 5.0).
  - Outbox to Kafka serves **downstream services**.
  - Avoids self-consuming events for cache invalidation, eliminating unnecessary latency and failure modes.
- **Dedicated Reservation entity:** provides full lifecycle tracking and auditability.
- **Optimistic locking:** `@Version` on `Inventory` with an explicit manual retry loop (up to 3 attempts)
  wrapped in `ReservationService` (section 5.7), throwing `INVENTORY_VERSION_CONFLICT` if retries exhaust.
  (Avoids `@Retryable`, which requires additional dependencies and encounters self-invocation proxy limitations.)
- **Hard delete:** inventory stock is transactional operational data and uses hard deletes rather than soft deletes.
  Deletion is permitted **only when no PENDING or COMMITTED reservations exist** (section 4.1 policy).
- **Outbox pattern:** inherits the product-service pattern (`OutboxEvent` entity with `@Scheduled` relay).
- **Event ordering:** Kafka partition key equals `productId`. OutboxRelay runs as a **single thread sorted by `id` ascending**
  to preserve per-aggregate message order (see section 6).

---

## 3. Data model

### 3.1 Inventory

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK, `@GeneratedValue(UUID)` |
| `productId` | `UUID` | not null, unique |
| `availableQuantity` | `Integer` | not null, default 0, >= 0 |
| `reservedQuantity` | `Integer` | not null, default 0, >= 0 |
| `version` | `Long` | `@Version`  -  optimistic lock |
| `lastUpdated` | `Instant` | set manually in service layer on write paths (omits JPA auditing since entity does not extend `AbstractMappedEntity`) |

> **Does not extend `AbstractMappedEntity`** (uses hard deletes); retains `lastUpdated` only.
> **Null cache:** `disableCachingNullValues()` ensures non-existent productIds are not cached.
> Potential risk: querying non-existent productIds hits the database; null-caching with short TTL (30s) can be added if needed.

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

### 3.4 OutboxEvent (inheriting product-service pattern and adding aggregate_id as Kafka key)

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK, identity |
| `eventId` | `String` | not null, unique (UUID) |
| `aggregateType` | `String` | not null, "Inventory" |
| `aggregateId` | `UUID` | not null, **= productId**: used as the Kafka partition key |
| `eventType` | `String` | not null |
| `topic` | `String` | not null |
| `payload` | `String` (TEXT) | not null, JSON |
| `status` | `OutboxStatus` enum | PENDING / SENT / FAILED |
| `retryCount` | `Integer` | not null, default 0 |
| `sentAt` | `Instant` | nullable |
| `lastError` | `String` | nullable |

> **Difference from product-service:** entity does not extend `AbstractMappedEntity` (hard delete semantics).
> Table `outbox_events` omits soft-delete columns. The relay inspects `aggregateId` to assign the Kafka partition key.

Indexes: `eventId` UNIQUE, `status`, `aggregateId`.

### 3.5 Liquibase

```
inventory-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changelog-001-initial-schema.yaml     (inventory, reservations, outbox_events)
```

Creation order: `inventory` -> `reservations` -> `outbox_events`.

---

## 4. API surface

### 4.1 Endpoints  -  `/api/v1/inventory`

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `GET` | `/api/v1/inventory` | USER/ADMIN |  -  `?page=&size=` | `ApiResponse<PageResponse<InventoryResponse>>` | Paginated list |
| `GET` | `/api/v1/inventory/{productId}` | USER/ADMIN |  -  | `ApiResponse<InventoryResponse>` | Cache-aside read |
| `POST` | `/api/v1/inventory` | ADMIN | `InventoryUpsertRequest { productId, availableQuantity }` | `ApiResponse<InventoryResponse>` | Create (409 if exists) |
| `PUT` | `/api/v1/inventory/{productId}` | ADMIN | `InventoryUpsertRequest { availableQuantity }` | `ApiResponse<InventoryResponse>` | Update (optimistic lock) |
| `DELETE` | `/api/v1/inventory/{productId}` | ADMIN | - | `ApiResponse<Void>` | Hard delete: **only when no PENDING or COMMITTED reservations exist** (otherwise returns 409 `RESERVATION_INVALID_STATE`) |

> **Deletion policy:** check `reservationRepository.countByProductIdAndStatusIn(productId, [PENDING, COMMITTED]) > 0`;
> if positive, throw 409. Otherwise, hard-delete the inventory record. (Releasing expired reservations is managed
> in the background by `ReservationCleanupScheduler`, section 5.8, running every 60s).

### 4.2 Reservation endpoints  -  internal (order-service)

| M | Path | Auth | Body | Resp | Notes |
|---|---|---|---|---|---|
| `POST` | `/api/v1/inventory/{productId}/reserve` | SERVICE | `ReserveRequest { quantity, orderId? }` | `ApiResponse<ReservationResponse>` | Reserve stock |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/commit` | SERVICE |  -  | `ApiResponse<Void>` | Commit |
| `POST` | `/api/v1/inventory/reservations/{reservationId}/release` | SERVICE |  -  | `ApiResponse<Void>` | Release |

> **Internal security:** uses Keycloak realm role `SERVICE`. Order-service obtains tokens via
> client-credentials grant (dedicated client-id and scope). `JwtRolesConverter` (common-security)
> maps roles to `ROLE_SERVICE`, enabling `@PreAuthorize("hasRole('SERVICE')")`.
> Refer to `JwtRolesConverter.java` for authority mapping details.

### 4.3 Response DTOs

- `InventoryResponse { productId, availableQuantity, reservedQuantity, lastUpdated }`
- `ReservationResponse { reservationId, productId, quantity, status, expiresAt, orderId }`

### 4.4 Validation (Bean Validation with `@Valid` on controllers)

- `InventoryUpsertRequest`: `productId` `@NotNull`; `availableQuantity` `@NotNull @Min(0)`
- `ReserveRequest`: `quantity` `@NotNull @Positive`; `orderId` optional

---

## 5. Service layer

### 5.0 Cache transaction-awareness (CacheConfig)

> API note (verified via javap spring-data-redis 4.1.1): `RedisCacheManagerBuilder`
> only provides **`transactionAware()` (no-arg)**; `transactionAware(boolean)` does not exist.
> Code specifying `.transactionAware(true)` does not compile.

> **Optimistic lock retries:** Avoid `@Retryable` / `@EnableRetry` (requires additional dependencies
> and cannot proxy self-invocations). Use an explicit **manual retry loop** in `ReservationService`
> (section 5.7). If transitioning to `@Retryable` in the future, add `RetryConfig` with `@EnableRetry`
> (tracked in Open items).

```java
@Bean
public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
    return builder -> builder
        .cacheDefaults(cacheConfig())            // base: TTL 60s, no-null, prefix "inventory::"
        .withCacheConfiguration("inventory", cacheConfig())
        .transactionAware();                      // no-arg: defers eviction until after commit
}
```

> **Two-tier protection against premature eviction:**
> 1. **Primary mechanism**: all write paths (reserve, commit, release, upsert, delete) invoke
>    `inventoryCacheService.evictAfterCommit(productId)` explicitly (sections 5.1-5.3). This helper
>    registers `TransactionSynchronization.afterCommit`, executing evictions only after successful commits
>    and leaving caches untouched on rollbacks.
> 2. **Defense-in-depth**: `transactionAware()` on the builder ensures any future `@CacheEvict`
>    annotations also defer until post-commit.
>
> With both tiers in place, rollbacks never evict valid cache entries.

### 5.1 Reserve flow (write path, optimistic lock)

```java
@Transactional
public ReservationResponse reserve(UUID productId, ReserveRequest request) {
    // 1. Release expired reservations before reading Inventory (updates Inventory and increments @Version)
    releaseExpiredReservations(productId);
    // 2. Read Inventory after releasing expired reservations to obtain latest state
    Inventory inv = inventoryRepository.findByProductId(productId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
    // 3. Calculate available quantity on the fresh Inventory record
    int available = inv.getAvailableQuantity() - inv.getReservedQuantity();
    if (available < request.quantity()) {
        throw BusinessException.of(ErrorCode.STOCK_INSUFFICIENT, productId);
    }
    inv.setReservedQuantity(inv.getReservedQuantity() + request.quantity());
    inv.setLastUpdated(Instant.now());
    inventoryRepository.save(inv);

    Reservation reservation = Reservation.builder()
        .productId(productId).quantity(request.quantity())
        .status(ReservationStatus.PENDING).createdAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(reservationTtlSeconds))
        .orderId(request.orderId()).build();
    reservationRepository.save(reservation);

    inventoryEventPublisher.publishReserved(inv, reservation);  // outbox same TX
    inventoryCacheService.evictAfterCommit(productId);          // evict after commit (avoids @CacheEvict)
    return mapper.toReservationResponse(reservation);
}
```

- **Avoid `@CacheEvict` on reserve**: use explicit `evictAfterCommit()` matching commit and release
  for architectural consistency (see section 5.0 tiering) and to avoid SpEL pitfalls.
- **Optimistic lock retries**: avoid `@Retryable` here; wrap in `ReservationService.reserveWithRetry` (section 5.7)
  to bypass self-invocation proxy limitations.

#### releaseExpiredReservations(productId) - details

> Invoked **first** within reserve (prior to reading Inventory) to avoid stale reads.

```java
// private method in InventoryServiceImpl, executing in caller's @Transactional context
private void releaseExpiredReservations(UUID productId) {
    List<Reservation> expired = reservationRepository
        .findByProductIdAndStatusAndExpiresAtBefore(productId, ReservationStatus.PENDING, Instant.now());
    if (expired.isEmpty()) return;

    Inventory inv = inventoryRepository.findByProductId(productId).orElseThrow(
        () -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
    int total = expired.stream().mapToInt(Reservation::getQuantity).sum();
    inv.setReservedQuantity(inv.getReservedQuantity() - total);
    expired.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));

    reservationRepository.saveAll(expired);
    inventoryRepository.save(inv);   // increments @Version; must be called before reading inv in reserve
}
```

> **Execution sequence note:** because this method updates Inventory (incrementing version), invoke it
> **before** `findByProductId` in reserve to avoid stale state. Reserve calculates available quantity
> on the updated Inventory record. If concurrent modifications trigger an `OptimisticLockingFailureException`,
> `reserveWithRetry` (section 5.7) retries the full reservation flow including expired release.

### 5.2 Commit flow

> **Cache eviction:** avoid `@CacheEvict` with SpEL `#r.productId` (r is not a method parameter, causing runtime evaluation errors).
> Call `inventoryCacheService.evictAfterCommit(r.getProductId())` explicitly, which registers an after-commit synchronization hook.

```java
@Transactional
public void commit(UUID reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
    if (r.getStatus() != ReservationStatus.PENDING) {
        throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
    }
    if (r.getExpiresAt().isBefore(Instant.now())) {
        // Do not call save(EXPIRED) here: BusinessException (RuntimeException) triggers rollback,
        // rendering prior writes dead code. EXPIRED status is materialized by
        // ReservationCleanupScheduler (section 5.8) or lazy release in reserve (section 5.1).
        throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
    }
    Inventory inv = inventoryRepository.findByProductId(r.getProductId()).orElseThrow(
        () -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
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

**Optimistic lock retries for commit:** wrapped within `ReservationService` (calling tier); see section 5.7.

### 5.3 Release flow

> **Cache eviction:** invoke `inventoryCacheService.evictAfterCommit(r.getProductId())` explicitly post-commit.

```java
@Transactional
public void release(UUID reservationId) {
    Reservation r = reservationRepository.findById(reservationId)
        .orElseThrow(() -> BusinessException.of(ErrorCode.RESERVATION_NOT_FOUND, reservationId));
    if (r.getStatus() != ReservationStatus.PENDING) {
        throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, reservationId);
    }
    if (r.getExpiresAt().isBefore(Instant.now())) {
        // Do not call save(EXPIRED): transaction rollback discards writes.
        throw BusinessException.of(ErrorCode.RESERVATION_EXPIRED, reservationId);
    }
    Inventory inv = inventoryRepository.findByProductId(r.getProductId()).orElseThrow(
        () -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, r.getProductId()));
    inv.setReservedQuantity(inv.getReservedQuantity() - r.getQuantity());
    r.setStatus(ReservationStatus.RELEASED);
    r.setReleasedAt(Instant.now());
    inventoryRepository.save(inv);
    reservationRepository.save(r);
    inventoryEventPublisher.publishReleased(inv, r);
    inventoryCacheService.evictAfterCommit(r.getProductId());   // evict sau commit
}
```

**Optimistic lock retry cho release:** wrap trong `ReservationService`  -  xem §5.7.

### 5.4 Read path (cache-aside)

```java
@Cacheable(value = "inventory", key = "#productId")  // TTL 60s via CacheConfig
public InventoryResponse findById(UUID productId) {
    return inventoryRepository.findByProductId(productId)
        .map(mapper::toResponse)
        .orElseThrow(() -> BusinessException.of(ErrorCode.INVENTORY_NOT_FOUND, productId));
}
```

### 5.5 Event publisher (outbox)

Follows the `TransactionalProductEventPublisher` pattern: writes `OutboxEvent` records in the same transaction:

```java
public void publishReserved(Inventory inv, Reservation r) {
    // Use HashMap: Map.of throws NullPointerException on null values. orderId is optional (section 4.4);
    // reservations without orderId represent a primary flow, so Map.of causes runtime crashes.
    Map<String, Object> data = new HashMap<>();
    data.put("productId", inv.getProductId());
    data.put("reservationId", r.getId());
    data.put("quantity", r.getQuantity());
    if (r.getOrderId() != null) {
        data.put("orderId", r.getOrderId());
    }
    data.put("expiresAt", r.getExpiresAt().toString());
    save("inventory.reserved.v1", inv.getProductId(), data);
}
// + publishCommitted, publishReleased (with null-guard for orderId), publishAdjusted (upsert), publishDeleted
```

### 5.6 Outbox relay: single-threaded, preserving per-aggregate ordering

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
        // -> List<OutboxEvent> or Page<OutboxEvent> (both supported by Spring Data JPA)
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        for (OutboxEvent event : pending) {   // single-threaded sequential execution
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
                break;   // Stop: failure halts execution to preserve ordering.
                         // Failed event remains PENDING and retries on subsequent polls.
            }
        }
    }
}
```

> **Ordering:** querying `ORDER BY id ASC` and dispatching sequentially ensures events for the same
> productId are sent strictly in creation order. Parallelism is unnecessary given inventory throughput characteristics.

---


### 5.7 ReservationService  -  optimistic lock retry wrapper

Optimistic locking retries are encapsulated at the calling tier (`ReservationService`)
rather than on the `@Transactional` method directly:

```java
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final InventoryService inventoryService;   // underlying @Transactional methods

    /** Retries reservation upon OptimisticLockingFailureException (up to 3 attempts). */
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
        // similar to commitWithRetry
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
```

> **Manual retry loop rationale:** `@Retryable` requires additional dependencies and configuration.
> Resilience4j `@Retry` adds unnecessary configuration complexity for optimistic locking scenarios
> that require fresh entity re-reads while avoiding proxy self-invocation limitations. An explicit manual loop
> is straightforward, easily testable, and dependency-free.

---

### 5.8 Scheduled jobs  -  expired sweep + outbox retention

Two background jobs run via `@Scheduled` (application requires `@EnableScheduling`, section 2.1):

**1. `ReservationCleanupScheduler`**: releases stock for expired PENDING reservations.
Without this scheduler, between reservation expiration and subsequent reserve calls,
inflated reserved quantities cause available stock to appear artificially low, leading to false
`STOCK_INSUFFICIENT` errors for other users.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ReservationCleanupScheduler {

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;

    /**
     * Whole sweep in ONE transaction  -  @Scheduled invocation goes through the Spring
     * Proxied invocations honor @Transactional boundaries cleanly.
     * Runs in batches (using Pageable with batch flush/clear) to prevent loading
     * large backlogs into memory after downtimes or flash sales.
     */
    @Scheduled(fixedDelayString = "${inventory.reservation-cleanup-interval-ms:60000}")
    @Transactional
    public void releaseAllExpiredReservations() {
        int total = 0;
        while (true) {
            List<Reservation> batch = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize));
            if (batch.isEmpty()) break;
            // group by productId to adjust reservedQuantity once per product
            batch.stream().collect(Collectors.groupingBy(Reservation::getProductId))
                .forEach((productId, reservations) -> {
                    int q = reservations.stream().mapToInt(Reservation::getQuantity).sum();
                    inventoryRepository.findByProductId(productId).ifPresent(inv -> {
                        inv.setReservedQuantity(Math.max(0, inv.getReservedQuantity() - q));
                        inv.setLastUpdated(Instant.now());
                        reservations.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));
                        inventoryRepository.save(inv);           // increments @Version
                        reservationRepository.saveAll(reservations);
                    });
                });
            total += batch.size();
            entityManager.flush();
            entityManager.clear();   // bound persistence-context memory
        }
        if (total > 0) log.info("Expired-reservation sweep released {}", total);
    }

    /** Retention: EXPIRED is terminal; purged after 30 days (RELEASED/COMMITTED retained for audit). */
    @Scheduled(cron = "${inventory.reservation-retention-cron:0 0 4 * * *}")
    @Transactional
    public void purgeOldExpiredReservations() {
        try {
            int deleted = reservationRepository.deleteByStatusAndCreatedAtBefore(
                ReservationStatus.EXPIRED, Instant.now().minus(30, ChronoUnit.DAYS));
            if (deleted > 0) log.info("Purged {} EXPIRED reservations > 30 days", deleted);
        } catch (Exception ex) {
            log.error("Reservation retention purge failed  -  needs ops attention", ex);
        }
    }
}
```

> Concurrency with `reserve()`: both operations increment `@Version`. The losing transaction encounters
> an `OptimisticLockingFailureException`, rolls back cleanly, and retries on subsequent executions.
> The sweep query is idempotent (filtering PENDING where expiresAt < now) and converges safely.
>
> **UX note (MVP design):** If the sweep completes right at the TTL boundary before a user commit,
> the user receives 409 `RESERVATION_EXPIRED`. The operation is idempotent and safe.

**2. `OutboxRetentionScheduler`**: purges sent outbox events to prevent unbounded table growth.

```java
@Scheduled(cron = "${inventory.outbox.retention-cron:0 0 3 * * *}")   // daily at 03:00
public void purgeOldSentEvents() {
    int deleted = outboxRepository.deleteByStatusAndSentAtBefore(
        OutboxStatus.SENT, Instant.now().minus(7, ChronoUnit.DAYS));
    if (deleted > 0) log.info("Purged {} SENT outbox events older than 7 days", deleted);
}
```

> `FAILED` rows are not purged automatically; operations investigates root causes manually to preserve diagnostic evidence.

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

> Downstream consumers: order-service, notification-service, and search-service. Idempotency is managed
> via `processed_events` tables on consumers (inventory-service does not self-consume).

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
  # Cache: do not configure spring.cache.redis.* - CacheConfig bean is the single source of truth
  # (entryTtl 60s, disableCachingNullValues, transactionAware). YAML properties are bypassed
  # when the RedisCacheManager bean is explicitly declared.
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
    # Do not declare public-paths: all endpoints (including GET) require a JWT.
    # Raw stock counts are sensitive business data; fail-closed security is enforced.
    # Storefront display utilizes product-service availability indicators.
    # (Actuator, swagger, and API docs remain public via common-security platform defaults)

inventory:
  reservation-ttl-seconds: 900          # 15 min
  # Scheduled jobs (section 5.8): defaults match code, configured explicitly for operational visibility
  reservation-cleanup-interval-ms: 60000   # expired sweep every 60s
  reservation-cleanup-batch-size: 500      # batch sweep query (protects against backlog memory pressure)
  reservation-retention-cron: "0 0 4 * * *"   # purge EXPIRED > 30 days, 04:00
  outbox:
    poll-interval-ms: 5000
    batch-size: 100
    max-retries: 10
    retention-cron: "0 0 3 * * *"            # purge SENT > 7 days, 03:00

```

> **Cache TTL:** uses explicit `CacheConfig` bean (transactionAware); configuration properties
> act as fallbacks. `CacheConfig` represents the single source of truth for TTL, null handling, and key prefixes.

---

## 8. Error handling

> **Current status verified in repo:** `RESERVATION_NOT_FOUND` (INV-3003) through
> `INVENTORY_VERSION_CONFLICT` (INV-3007) and corresponding message keys already exist in
> `ErrorCode.java:56-60` and `messages_en.properties:69-73`. Do not re-add them.
> The only new code is:

| ErrorCode (add) | Value | HTTP | Notes |
|---|---|---|---|
| `INVENTORY_NOT_FOUND` | `INV-3008` | NOT_FOUND | Replaces `WAREHOUSE_NOT_FOUND` for non-existent inventory records |

Message key: `inventory.not.found=Inventory for product {0} was not found`.
Domain codes used across the service: `INVENTORY_NOT_FOUND` (404 on findByProductId miss),
`STOCK_INSUFFICIENT` (409), `RESERVATION_*` (404/409), `INVENTORY_ALREADY_EXISTS` (409),
`INVENTORY_VERSION_CONFLICT` (409 when retries exhaust).

---

## 9. Testing strategy

| Layer | Tool | Coverage |
|---|---|---|
| Unit | JUnit5 + Mockito + AssertJ | InventoryService (reserve/commit/release/upsert/delete), ReservationService |
| Slice | `@DataJpaTest` + Testcontainers | InventoryRepository (optimistic lock), ReservationRepository |
| Slice | `@WebMvcTest` + `@MockitoBean` + `@Import(ApiExceptionHandler.class)` | InventoryController (CRUD and reservation flows, including 404/409 error paths) |
| Integration | `@SpringBootTest` + Testcontainers | OutboxRelay end-to-end (reserve → event → Kafka), optimistic lock retry |

Test stack (Boot 4.1.1, verified from codebase):
- `@WebMvcTest` in `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`)
- `@DataJpaTest` in `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`)
- `TestEntityManager` in `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`)
- `@MockitoBean` in `org.springframework.test.context.bean.override.mockito.MockitoBean` (replacing legacy `@MockBean`).
  Used in slice tests (`@WebMvcTest`, `@DataJpaTest`). Full integration tests use Testcontainers without mocks.
- `@Import` requires `org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration`
- `@AutoConfigureMockMvc(addFilters = false)`: disables security filters in slice tests per auth-service pattern

---

## 10. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| ~~Expired reservation cleanup scheduler~~ | **IN SCOPE**: `ReservationCleanupScheduler`, see section 5.8 and Plan Task 22 | Implemented in MVP |
| ~~Outbox retention policy~~ | **IN SCOPE**: `OutboxRetentionScheduler` (purging SENT rows older than 7 days), see section 5.8 and Plan Task 22 | Implemented in MVP |
| Reservations retention (RELEASED/COMMITTED) | EXPIRED is purged in section 5.8; RELEASED/COMMITTED are retained for dispute auditing; retention duration depends on compliance policies | Future compliance requirements |
| Debezium CDC instead of @Scheduled relay | Lower latency, requires Kafka Connect infrastructure | When scaling |
| processed_events table for consumers | Inventory does not self-consume; required by external consumers | When order or notification consumes events |
| ShedLock for multi-instance OutboxRelay | Deployed as single instance currently | When scaling beyond one instance |
| Flash-sale Redis Lua check-and-decrement | Ultra-high performance alternate architecture | Deferred |
| Synchronous inventory initialization on product creation | Create inventory upon product creation | Can be handled via product event consumer later |
| Filter/search on findAll | MVP supports pagination only (`?page=&size=`); filters intentionally omitted | When specific administrative use cases arise |

---

## 11. Cross-references

- `docs/RATE-LIMIT.md`  -  gateway rate limit. **Scope cho inventory:** public reads (GET qua
  gateway) is rate-limited at the gateway like other routes. Internal endpoints (reserve, commit, release)
  are called point-to-point service-to-service without traversing the gateway, relying instead on
  JWT SERVICE role validation and optimistic locking retries.
- `docs/ARCHITECTURE.md`  -  §1 component map, §5 data stores, §6 cross-cutting
- `docs/ROADMAP.md`  -  Phase 7 status (inventory = next core service)
- `product-service`: reference implementation for outbox, cache, and mapper patterns
- `docs/superpowers/specs/2026-08-26-product-service-design.md`: reference design specification

---

## 12. Changelog

- 2026-08-28: Initial design: dual-path architecture (sync write + async outbox), dedicated Reservation entity,
  optimistic locking, hard delete, cache-aside TTL 60s, outbox to Kafka.
- 2026-08-28 (rev 2): Incorporate 10 review points: transactionAware cache evict, optimistic locking retries,
  single-thread outbox relay ordering, outbox aggregate_id as productId, strict deletion policy,
  Bean Validation, SERVICE role for internal endpoints, expired cleanup plan, explicit CacheConfig, and validation DTOs.
- 2026-08-28 (rev 3): Addressed 7 review items: (1) replaced SpEL `#r.productId` with explicit `evictAfterCommit()`,
  (2) removed non-standard `spring.retry` properties, (3) unified role checking on `hasRole('SERVICE')`,
  (4) implemented stop-on-error in OutboxRelay to preserve message order, (5) executed `releaseExpiredReservations`
  inside reserve, (6) added null-caching documentation, (7) verified test package locations.
- 2026-08-28 (rev 4): Cleanup: confirmed removal of spring.retry, reinforced `@PreAuthorize("hasRole('SERVICE')")`,
  retained break-on-error in outbox relay, designated `CacheConfig` as single source of truth over YAML properties,
  and documented `@EnableRetry` guidelines.
- 2026-08-28 (rev 5): In-depth review fixes: (1) updated CacheConfig to no-arg `.transactionAware()`
  and established two-tier eviction with manual `evictAfterCommit()`; (2) added mandatory `@EnableScheduling`;
  (3) introduced `ReservationCleanupScheduler` and `OutboxRetentionScheduler`; (4) removed dead-code save-then-throw
  calls in commit/release; (5) adopted null-safe HashMaps in event publishers; (6) enforced fail-closed security
  on GET endpoints; (7) introduced `INVENTORY_NOT_FOUND` (INV-3008); (8) verified existing codes INV-3003..3007;
  (9) clarified rate-limiting boundaries; (10) included expired sweep and retention in MVP scope.
- 2026-08-28 (rev 6): Second review iteration: (1) configured batching in `ReservationCleanupScheduler`
  with Pageable and context clears; (2) added 30-day retention purge for EXPIRED reservations;
  (3) added `clearAutomatically = true` to outbox bulk deletion queries; (4) eliminated unused `intervalMs` field;
  (5) wrapped scheduled purge jobs in try/catch with actionable ERROR logging; (6) documented concurrency edge cases
  between sweeps and releases.
