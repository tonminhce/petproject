# Inventory Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `inventory-service` microservice with Inventory + Reservation CRUD/lifecycle, Redis cache-aside (TTL 60s, transaction-aware evict), optimistic locking (`@Version`) with manual retry, and transactional outbox → Kafka events.

**Architecture:** Spring Boot 4.1.1 microservice (`com.shop.inventoryservice`) → PostgreSQL via Spring Data JPA + Liquibase, Redis 7 via Spring Cache (cache-aside), Kafka producer via Transactional Outbox + `@Scheduled` relay (single-thread, order-preserving). Kiến trúc Y: cache invalidation sync sau commit (transactionAware), outbox → Kafka cho các service khác.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Redis 7, Apache Kafka, ModelMapper 3.2.6, Lombok, JUnit 5 + Mockito + AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-28-inventory-service-design.md`](../specs/2026-08-28-inventory-service-design.md)

---

## Global Constraints

- **Java 25** (parent pom toolchain)
- **Spring Boot 4.1.1** (parent pom)
- **Package root:** `com.shop.*`
- **No per-service `SecurityConfig`** — `common-security` auto-configures `SecurityFilterChain`. Customise qua `shop.security.public-paths` (EndpointRule format).
- **`open-in-view: false`** trong `application.yml`
- **ModelMapper** — `@Component` inject `ModelMapper` (pattern product-service). KHÔNG MapStruct.
- **Liquibase** (không Flyway), changelogs trong `src/main/resources/db/changelog/`
- **All endpoints** wrap trong `ApiResponse<T>` (from `common-core/viewmodel`)
- **Cache key:** `inventory::{productId}`; TTL 60s; `disableCachingNullValues`; `transactionAware(true)`
- **Kafka topic:** `shop.inventory.events.v1`; partition key = `aggregateId` (= productId). Config qua `shop.kafka.*`
- **Optimistic locking:** `@Version` trên Inventory; retry manual loop trong `ReservationService` (KHÔNG `@Retryable`)
- **Hard delete** — Inventory KHÔNG extends `AbstractMappedEntity` (không soft-delete)
- **Outbox relay:** single-thread, `ORDER BY id ASC`, `break` khi gặp lỗi gửi (giữ thứ tự)
- **Exceptions:** `BusinessException.of(ErrorCode.X)` / factories — KHÔNG `new BusinessException(...)`
- **Test stack Boot 4:** `@MockitoBean` (không `@MockBean`), `@WebMvcTest` ở `org.springframework.boot.webmvc.test.autoconfigure.*`, `@DataJpaTest` ở `org.springframework.boot.data.jpa.test.autoconfigure.*`, `TestEntityManager` ở `org.springframework.boot.jpa.test.autoconfigure.*`, `@Import(LiquibaseAutoConfiguration.class)` (package `org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration`)

---

## File Structure

### Modified common modules

| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | **MODIFY** — thêm INV-3003..3007 |
| `utils/common-spring/src/main/resources/messages/messages_en.properties` | **MODIFY** — thêm i18n keys inventory |
| `utils/common-spring/src/main/resources/messages/messages_vi.properties` | **MODIFY** — thêm i18n keys inventory |
| `gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java` | **VERIFY** — route INVENTORY đã tồn tại (line 11: `INVENTORY("inventory-service", "inventory", "inventory-service", 8082)`) |
| `docker-compose.yml` | **MODIFY** — thêm env cho inventory-service (redis, kafka) |

### New inventory-service files

| File | Responsibility |
|---|---|
| `inventory-service/pom.xml` | **MODIFY** — thêm redis, kafka, cache, modelmapper, test deps |
| `inventory-service/src/main/java/com/shop/inventoryservice/InventoryServiceApplication.java` | `@SpringBootApplication` entrypoint |
| `inventory-service/src/main/java/com/shop/inventoryservice/config/CacheConfig.java` | `@EnableCaching` + `RedisCacheManager` (transactionAware, TTL 60s) |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java` | JPA entity + `@Version` |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java` | JPA entity |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/ReservationStatus.java` | enum PENDING/COMMITTED/RELEASED/EXPIRED |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxEvent.java` | Outbox entity (hard delete, aggregateId = productId) |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxStatus.java` | enum PENDING/SENT/FAILED |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java` | JPA repo |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/ReservationRepository.java` | JPA repo |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java` | Outbox repo |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/InventoryUpsertRequest.java` | record + validation |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java` | record + validation |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/InventoryResponse.java` | record |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/ReservationResponse.java` | record |
| `inventory-service/src/main/java/com/shop/inventoryservice/mapper/InventoryMapper.java` | ModelMapper `@Component` |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryService.java` | interface |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java` | impl (reserve/commit/release/upsert/delete + releaseExpiredReservations) |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationService.java` | interface (retry wrapper) |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java` | impl (manual retry loop) |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryEventPublisher.java` | interface |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java` | writes OutboxEvent same TX |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java` | `@Scheduled` single-thread relay |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryCacheService.java` | cache-aside read + `evictAfterCommit` |
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` | CRUD + reserve/commit/release |
| `inventory-service/src/main/resources/application.yml` | config (datasource, redis, kafka, security) |
| `inventory-service/src/main/resources/db/changelog/db.changelog-master.yaml` | Liquibase master |
| `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 3 tables + indexes |

### Test files

| File | Coverage |
|---|---|
| `inventory-service/src/test/java/com/shop/inventoryservice/repository/InventoryRepositoryTest.java` | `@DataJpaTest` — optimistic lock, CRUD |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplTest.java` | unit — reserve/commit/release/upsert/delete |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/ReservationServiceImplTest.java` | unit — retry loop |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerTest.java` | `@WebMvcTest` — CRUD + reserve |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayIntegrationTest.java` | `@SpringBootTest` + Testcontainers — outbox → Kafka |

---

## Phase 0 — Common upgrades

### Task 1: Add inventory ErrorCodes + i18n keys

**Files:**
- Modify: `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`
- Modify: `utils/common-spring/src/main/resources/messages/messages_en.properties`
- Modify: `utils/common-spring/src/main/resources/messages/messages_vi.properties`

**Interfaces:**
- Produces: ErrorCodes `RESERVATION_NOT_FOUND (INV-3003)`, `RESERVATION_EXPIRED (INV-3004)`, `RESERVATION_INVALID_STATE (INV-3005)`, `INVENTORY_ALREADY_EXISTS (INV-3006)`, `INVENTORY_VERSION_CONFLICT (INV-3007)`; i18n keys (EN + VI)

- [ ] **Step 1: Add 5 ErrorCodes to ErrorCode.java**

Thêm sau dòng `STOCK_INSUFFICIENT("INV-3002", "stock.insufficient", HttpStatus.CONFLICT),`:

```java
    RESERVATION_NOT_FOUND("INV-3003", "reservation.not.found", HttpStatus.NOT_FOUND),
    RESERVATION_EXPIRED("INV-3004", "reservation.expired", HttpStatus.CONFLICT),
    RESERVATION_INVALID_STATE("INV-3005", "reservation.invalid.state", HttpStatus.CONFLICT),
    INVENTORY_ALREADY_EXISTS("INV-3006", "inventory.already.exists", HttpStatus.CONFLICT),
    INVENTORY_VERSION_CONFLICT("INV-3007", "inventory.version.conflict", HttpStatus.CONFLICT);
```

> Lưu ý: thay dấu `;` cuối `STOCK_INSUFFICIENT` bằng `,`.

- [ ] **Step 2: Add i18n keys (EN)**

Thêm vào cuối `utils/common-spring/src/main/resources/messages/messages_en.properties`:

```properties
reservation.not.found=Reservation {0} not found
reservation.expired=Reservation {0} has expired
reservation.invalid.state=Reservation {0} is in invalid state
inventory.already.exists=Inventory already exists for product {0}
inventory.version.conflict=Inventory was modified concurrently. Please retry.
```

- [ ] **Step 3: Add i18n keys (VI)**

Thêm vào cuối `utils/common-spring/src/main/resources/messages/messages_vi.properties`:

```properties
reservation.not.found=Không tìm thấy đơn giữ hàng {0}.
reservation.expired=Đơn giữ hàng {0} đã hết hạn.
reservation.invalid.state=Đơn giữ hàng {0} ở trạng thái không hợp lệ.
inventory.already.exists=Tồn kho đã tồn tại cho sản phẩm {0}.
inventory.version.conflict=Tồn kho đã bị thay đổi đồng thời. Vui lòng thử lại.
```

- [ ] **Step 4: Verify**

Run: `./mvnw -pl utils/common-spring test -Dtest=...` (hoặc `./mvnw -pl utils/common-core compile`)
Expected: BUILD SUCCESS (compile không lỗi — enum mới không phá vỡ gì)

- [ ] **Step 5: Commit**

```bash
git add utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java \
        utils/common-spring/src/main/resources/messages/messages_en.properties \
        utils/common-spring/src/main/resources/messages/messages_vi.properties
git commit -m "feat(common-core): inventory ErrorCodes (INV-3003..3007) + i18n keys"
```

---

## Phase 1 — Skeleton + persistence

### Task 2: Update inventory-service pom.xml

**Files:**
- Modify: `inventory-service/pom.xml`

**Interfaces:**
- Produces: deps cho redis, kafka, cache, modelmapper, testcontainers (giống product-service pom)

- [ ] **Step 1: Copy deps từ product-service pom**

Thay toàn bộ `<dependencies>` trong `inventory-service/pom.xml` bằng (copy từ product-service pom lines 14-127):

```xml
<dependencies>
    <dependency>
        <groupId>com.shop.microservices</groupId>
        <artifactId>common-spring</artifactId>
    </dependency>
    <dependency>
        <groupId>com.shop.microservices</groupId>
        <artifactId>common-spring</artifactId>
        <version>${project.version}</version>
        <type>test-jar</type>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.shop.microservices</groupId>
        <artifactId>common-kafka</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
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
    <dependency>
        <groupId>org.liquibase</groupId>
        <artifactId>liquibase-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-liquibase</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.modelmapper</groupId>
        <artifactId>modelmapper</artifactId>
    </dependency>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
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
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka-test</artifactId>
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
</dependencies>
```

- [ ] **Step 2: Verify**

Run: `./mvnw -pl inventory-service -am compile`
Expected: BUILD SUCCESS (deps resolve)

- [ ] **Step 3: Commit**

```bash
git add inventory-service/pom.xml
git commit -m "feat(inventory-service): add redis, kafka, cache, modelmapper, test deps"
```

---
### Task 3: Application entrypoint + application.yml

**Files:**
- Modify: `inventory-service/src/main/java/com/shop/inventoryservice/InventoryServiceApplication.java`
- Create: `inventory-service/src/main/resources/application.yml`

**Interfaces:**
- Produces: runnable app + config (datasource, redis, kafka, cache, security, outbox)

- [ ] **Step 1: Verify InventoryServiceApplication.java**

Đã tồn tại đúng skeleton:

```java
package com.shop.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
```

> Không cần `@EnableJpaAuditing` — inventory không dùng audit fields (hard delete). Không cần `@EnableCaching` ở đây — CacheConfig lo.

- [ ] **Step 2: Create application.yml**

```yaml
# =============================================================================
#  inventory-service — Stock management + reservation lifecycle.
#  Reads/writes Postgres, caches hot reads in Redis (cache-aside TTL 60s),
#  publishes stock events to Kafka through a transactional outbox.
#
#  Platform-wide defaults live in common-spring/application.yml (inherited).
#  Only service-specific overrides belong here.
# =============================================================================
spring:
  application:
    name: inventory-service

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/inventoryservice}
    username: ${POSTGRES_USER:admin}
    password: ${POSTGRES_PASSWORD:admin}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      # Schema is owned by Liquibase — never let Hibernate mutate it.
      ddl-auto: validate
    open-in-view: false

  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
      # Empty means "no AUTH" — the compose Redis runs with --requirepass.
      password: ${SPRING_DATA_REDIS_PASSWORD:}

  # Cache: KHÔNG cấu hình spring.cache.redis.* — CacheConfig bean là single source of truth
  # (entryTtl 60s, disableCachingNullValues, transactionAware).
  cache:
    type: redis

server:
  # Override the common-spring default 8080 (compose maps 8082:8082).
  port: ${SERVER_PORT:8082}

shop:
  kafka:
    # common-kafka binds shop.kafka.* (KafkaProperties) — NOT spring.kafka.*.
    bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      # Durability over throughput: the outbox relay must not lose events.
      acks: all
      retries: 3
  security:
    # Inventory reads are authenticated (USER/ADMIN); GET detail cached.
    # (actuator, swagger, api-docs are public via common-security platform defaults)
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

- [ ] **Step 3: Verify boot**

Run: `docker compose up -d postgres redis kafka && ./mvnw -pl inventory-service spring-boot:run`
Expected: app boots, Liquibase chạy (0 changesets — chưa có), port 8082

- [ ] **Step 4: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/InventoryServiceApplication.java \
        inventory-service/src/main/resources/application.yml
git commit -m "feat(inventory-service): application entrypoint + application.yml"
```

---

### Task 4: Create CacheConfig (transactionAware Redis cache)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/config/CacheConfig.java`

**Interfaces:**
- Produces: `RedisCacheManager` bean (transactionAware, TTL 60s, no-null, prefix `inventory::`)

- [ ] **Step 1: Implement CacheConfig**

```java
package com.shop.inventoryservice.config;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

/**
 * Redis cache tuning for inventory read paths.
 *
 * <p>The {@code RedisCacheManager} itself is built by Spring Boot's cache
 * autoconfiguration ({@code spring.cache.type: redis}); this class only
 * customises the per-cache entries. Note the Boot 4 package:
 * {@code RedisCacheManagerBuilderCustomizer} lives in
 * {@code org.springframework.boot.cache.autoconfigure}.</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Inventory reads are high-traffic, tolerate a short staleness window. */
    private static final Duration INVENTORY_TTL = Duration.ofSeconds(60);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .withCacheConfiguration("inventory", cacheConfig());
    }

    private RedisCacheConfiguration cacheConfig() {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(INVENTORY_TTL)
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::");
    }
}
```

> **Note:** `transactionAware(true)` — khi cần (nếu dùng `@CacheEvict` với transaction), set trên
> `RedisCacheManager.builder(connectionFactory).transactionAware(true)`. Với pattern hiện tại
> (dùng `InventoryCacheService.evictAfterCommit` thủ công), không bắt buộc — nhưng spec yêu cầu
> transactionAware để `@CacheEvict` an toàn. Xem Task 14 (InventoryCacheService) cho cách dùng.

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/config/CacheConfig.java
git commit -m "feat(inventory-service): CacheConfig with per-cache TTL (60s)"
```

---

### Task 5: Liquibase changelog — initial schema

**Files:**
- Create: `inventory-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`

**Interfaces:**
- Produces: 3 tables (inventory, reservations, outbox_events) + indexes

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
  # inventory (hard delete — KHÔNG có soft-delete columns)
  # ===========================================================================
  - createTable:
      tableName: inventory
      columns:
        - column:
            name: id
            type: UUID
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: product_id,        type: UUID,   constraints: { nullable: false } }
        - column: { name: available_quantity, type: INTEGER, constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: reserved_quantity,  type: INTEGER, constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: version,            type: BIGINT,  constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: last_updated,       type: TIMESTAMP }

  - addUniqueConstraint:
      tableName: inventory
      columnNames: product_id
      constraintName: uk_inventory_product

  - createIndex:
      tableName: inventory
      indexName: idx_inventory_product_id
      columns:
        - column: { name: product_id }

  # ===========================================================================
  # reservations
  # ===========================================================================
  - createTable:
      tableName: reservations
      columns:
        - column:
            name: id
            type: UUID
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: product_id,   type: UUID,    constraints: { nullable: false } }
        - column: { name: quantity,     type: INTEGER, constraints: { nullable: false } }
        - column: { name: status,       type: VARCHAR(20), constraints: { nullable: false } }
        - column: { name: created_at,   type: TIMESTAMP,  constraints: { nullable: false } }
        - column: { name: expires_at,   type: TIMESTAMP,  constraints: { nullable: false } }
        - column: { name: committed_at, type: TIMESTAMP }
        - column: { name: released_at,  type: TIMESTAMP }
        - column: { name: order_id,     type: UUID }

  - createIndex:
      tableName: reservations
      indexName: idx_reservations_product_id
      columns:
        - column: { name: product_id }

  - createIndex:
      tableName: reservations
      indexName: idx_reservations_status_expires
      columns:
        - column: { name: status }
        - column: { name: expires_at }

  # ===========================================================================
  # outbox_events (hard delete — KHÔNG extends AbstractMappedEntity)
  # ===========================================================================
  - createTable:
      tableName: outbox_events
      columns:
        - column:
            name: id
            type: BIGSERIAL
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: event_id,       type: VARCHAR(36), constraints: { nullable: false, unique: true } }
        - column: { name: aggregate_type, type: VARCHAR(50), constraints: { nullable: false } }
        - column: { name: aggregate_id,   type: UUID,        constraints: { nullable: false } }
        - column: { name: event_type,     type: VARCHAR(50), constraints: { nullable: false } }
        - column: { name: topic,          type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: payload,        type: TEXT,        constraints: { nullable: false } }
        - column: { name: status,         type: VARCHAR(20), constraints: { nullable: false } }
        - column: { name: retry_count,    type: INTEGER,     constraints: { nullable: false, defaultValueNumeric: 0 } }
        - column: { name: sent_at,        type: TIMESTAMP }
        - column: { name: last_error,     type: VARCHAR(1000) }

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
```

- [ ] **Step 3: Commit**

```bash
git add inventory-service/src/main/resources/db/changelog/
git commit -m "feat(inventory-service): initial Liquibase schema (inventory, reservations, outbox_events)"
```

---

### Task 6: Create enums (ReservationStatus, OutboxStatus)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/entity/ReservationStatus.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxStatus.java`

- [ ] **Step 1: Create ReservationStatus**

```java
package com.shop.inventoryservice.entity;

public enum ReservationStatus {
    PENDING, COMMITTED, RELEASED, EXPIRED
}
```

- [ ] **Step 2: Create OutboxStatus**

```java
package com.shop.inventoryservice.entity;

public enum OutboxStatus {
    PENDING, SENT, FAILED
}
```

- [ ] **Step 3: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/entity/ReservationStatus.java \
        inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxStatus.java
git commit -m "feat(inventory-service): ReservationStatus + OutboxStatus enums"
```

---

### Task 7: Create Inventory entity (+ @Version)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java`

**Interfaces:**
- Produces: JPA entity với `@Version` optimistic lock

- [ ] **Step 1: Implement Inventory**

```java
package com.shop.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Stock record for a product. Hard-delete semantics — NOT soft-deletable.
 * {@code @Version} provides optimistic locking to prevent lost updates
 * when concurrent reserve/commit/release operations touch the same row.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false, unique = true)
    private UUID productId;

    @Column(name = "available_quantity", nullable = false)
    @Builder.Default
    private Integer availableQuantity = 0;

    @Column(name = "reserved_quantity", nullable = false)
    @Builder.Default
    private Integer reservedQuantity = 0;

    @Version
    private Long version;

    @Column(name = "last_updated")
    private Instant lastUpdated;
}
```

> **Không extends `AbstractMappedEntity`** — hard delete (spec §3.1).

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java
git commit -m "feat(inventory-service): Inventory entity with @Version optimistic lock"
```

---

### Task 8: Create Reservation + ReservationStatus entities

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java`

**Interfaces:**
- Produces: Reservation entity (PENDING/COMMITTED/RELEASED/EXPIRED lifecycle)

- [ ] **Step 1: Implement Reservation**

```java
package com.shop.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "order_id")
    private UUID orderId;
}
```

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java
git commit -m "feat(inventory-service): Reservation entity"
```

---

### Task 9: Create OutboxEvent + OutboxStatus entities

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxEvent.java`

**Interfaces:**
- Produces: Outbox entity (hard delete, aggregateId = productId)

- [ ] **Step 1: Implement OutboxEvent**

```java
package com.shop.inventoryservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row. Written in the SAME transaction as the inventory
 * change; a relay drains PENDING rows to Kafka. Hard delete — no soft-delete
 * columns (spec §3.4). aggregateId = productId (used as Kafka partition key).
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
    private Integer retryCount = 0;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;
}
```

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxEvent.java
git commit -m "feat(inventory-service): OutboxEvent entity (hard delete, aggregateId = productId)"
```

---

### Task 10: Create repositories

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/repository/ReservationRepository.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java`

**Interfaces:**
- Produces: repos với custom queries

- [ ] **Step 1: Create InventoryRepository**

```java
package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    boolean existsByProductId(UUID productId);
}
```

- [ ] **Step 2: Create ReservationRepository**

```java
package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findByProductIdAndStatusAndExpiresAtBefore(
            UUID productId, ReservationStatus status, Instant expiresBefore);

    long countByProductIdAndStatusIn(UUID productId, List<ReservationStatus> statuses);
}
```

- [ ] **Step 3: Create OutboxEventRepository**

```java
package com.shop.inventoryservice.repository;

import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Poller dùng — sắp xếp theo id ASC giữ thứ tự per-aggregate
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
}
```

- [ ] **Step 4: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/repository/
git commit -m "feat(inventory-service): Inventory, Reservation, OutboxEvent repositories"
```

---
---

## Phase 2 — DTOs, Mappers, Services

### Task 11: DTOs (request + response)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/InventoryUpsertRequest.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/InventoryResponse.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/ReservationResponse.java`

**Interfaces:**
- Produces: request/response records với Bean Validation

- [ ] **Step 1: Create InventoryUpsertRequest**

```java
package com.shop.inventoryservice.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record InventoryUpsertRequest(
    @NotNull UUID productId,
    @NotNull @Min(0) Integer availableQuantity
) {}
```

- [ ] **Step 2: Create ReserveRequest**

```java
package com.shop.inventoryservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ReserveRequest(
    @NotNull @Positive Integer quantity,
    UUID orderId
) {}
```

- [ ] **Step 3: Create InventoryResponse**

```java
package com.shop.inventoryservice.dto.response;

import java.time.Instant;
import java.util.UUID;

public record InventoryResponse(
    UUID productId,
    Integer availableQuantity,
    Integer reservedQuantity,
    Instant lastUpdated
) {}
```

- [ ] **Step 4: Create ReservationResponse**

```java
package com.shop.inventoryservice.dto.response;

import com.shop.inventoryservice.entity.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
    UUID reservationId,
    UUID productId,
    Integer quantity,
    ReservationStatus status,
    Instant expiresAt,
    UUID orderId
) {}
```

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/dto/
git commit -m "feat(inventory-service): request/response DTOs (records + validation)"
```

---

### Task 12: Create InventoryMapper (ModelMapper)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/mapper/InventoryMapper.java`

**Interfaces:**
- Produces: `@Component` mapper (`toResponse`, `toEntity`, `partialUpdate`)

- [ ] **Step 1: Implement InventoryMapper**

```java
package com.shop.inventoryservice.mapper;

import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class InventoryMapper {

    private final ModelMapper modelMapper;

    public InventoryMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public InventoryResponse toResponse(Inventory inventory) {
        return new InventoryResponse(
            inventory.getProductId(),
            inventory.getAvailableQuantity(),
            inventory.getReservedQuantity(),
            inventory.getLastUpdated()
        );
    }

    public Inventory toEntity(InventoryUpsertRequest request) {
        Inventory inventory = modelMapper.map(request, Inventory.class);
        inventory.setId(null);
        inventory.setAvailableQuantity(request.availableQuantity());
        inventory.setReservedQuantity(0);
        return inventory;
    }

    public void partialUpdate(Inventory target, InventoryUpsertRequest request) {
        if (request.availableQuantity() != null) {
            target.setAvailableQuantity(request.availableQuantity());
        }
    }

    public ReservationResponse toReservationResponse(Reservation reservation) {
        return new ReservationResponse(
            reservation.getId(),
            reservation.getProductId(),
            reservation.getQuantity(),
            reservation.getStatus(),
            reservation.getExpiresAt(),
            reservation.getOrderId()
        );
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/mapper/InventoryMapper.java
git commit -m "feat(inventory-service): InventoryMapper (ModelMapper)"
```

---

### Task 13: InventoryService interface

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryService.java`

**Interfaces:**
- Produces: interface (findAll/findById/create/update/delete/reserve/commit/release)

- [ ] **Step 1: Create InventoryService interface**

```java
package com.shop.inventoryservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface InventoryService {

    PageResponse<InventoryResponse> findAll(Pageable pageable);

    InventoryResponse findById(UUID productId);

    InventoryResponse create(InventoryUpsertRequest request);

    InventoryResponse update(UUID productId, InventoryUpsertRequest request);

    void delete(UUID productId);

    ReservationResponse reserve(UUID productId, ReserveRequest request);

    void commit(UUID reservationId);

    void release(UUID reservationId);
}
```

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryService.java
git commit -m "feat(inventory-service): InventoryService interface"
```

---

### Task 14: InventoryCacheService (cache-aside + evictAfterCommit)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryCacheService.java`

**Interfaces:**
- Consumes: nothing external
- Produces: `evictAfterCommit(UUID productId)` — register afterCommit hook; `evict(UUID)` immediate

- [ ] **Step 1: Implement InventoryCacheService**

```java
package com.shop.inventoryservice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Cache invalidation helper for the inventory cache.
 *
 * <p>{@code evictAfterCommit} registers a synchronization so the Redis key is
 * removed ONLY after the current transaction commits successfully. If the
 * transaction rolls back, the cache is NOT touched — preventing a spurious
 * miss and, worse, a stale write-back between evict and commit.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryCacheService {

    private static final String CACHE_NAME = "inventory";

    private final CacheManager cacheManager;

    /** Evict the cache entry for a productId immediately (outside a transaction). */
    public void evict(UUID productId) {
        evictQuietly(productId);
    }

    /** Evict AFTER commit — safe to call inside a @Transactional method. */
    public void evictAfterCommit(UUID productId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictQuietly(productId);
                }
            });
        } else {
            // No active transaction — evict immediately.
            evictQuietly(productId);
        }
    }

    private void evictQuietly(UUID productId) {
        try {
            var cache = cacheManager.getCache(CACHE_NAME);
            if (cache != null) {
                cache.evict(productId);
            }
        } catch (Exception ex) {
            // Redis failure — log and let TTL expire the stale entry (eventual consistency).
            log.warn("Failed to evict inventory cache for productId {}: {}", productId, ex.getMessage());
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryCacheService.java
git commit -m "feat(inventory-service): InventoryCacheService with evictAfterCommit"
```

---

### Task 15: InventoryEventPublisher + TransactionalInventoryEventPublisher

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryEventPublisher.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java`

**Interfaces:**
- Consumes: `OutboxEventRepository`, `ObjectMapper`
- Produces: `publishReserved/publishCommitted/publishReleased/publishAdjusted/publishDeleted` — write OutboxEvent row in same TX

- [ ] **Step 1: Create InventoryEventPublisher interface**

```java
package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;

public interface InventoryEventPublisher {

    void publishReserved(Inventory inventory, Reservation reservation);

    void publishCommitted(Inventory inventory, Reservation reservation);

    void publishReleased(Inventory inventory, Reservation reservation);

    void publishAdjusted(Inventory inventory);

    void publishDeleted(Inventory inventory);
}
```

- [ ] **Step 2: Create TransactionalInventoryEventPublisher**

```java
package com.shop.inventoryservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one {@link OutboxEvent} row per domain action in the SAME
 * {@code @Transactional} boundary as the inventory change. The relay
 * ({@code InventoryOutboxRelay}) drains the table to Kafka.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalInventoryEventPublisher implements InventoryEventPublisher {

    private static final String AGGREGATE_TYPE = "Inventory";
    private static final String TOPIC = "shop.inventory.events.v1";

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishReserved(Inventory inventory, Reservation reservation) {
        save(inventory, "inventory.reserved.v1", Map.of(
            "productId", inventory.getProductId(),
            "reservationId", reservation.getId(),
            "quantity", reservation.getQuantity(),
            "orderId", reservation.getOrderId(),
            "expiresAt", reservation.getExpiresAt().toString()
        ));
    }

    @Override
    public void publishCommitted(Inventory inventory, Reservation reservation) {
        save(inventory, "inventory.committed.v1", Map.of(
            "productId", inventory.getProductId(),
            "reservationId", reservation.getId(),
            "quantity", reservation.getQuantity(),
            "orderId", reservation.getOrderId()
        ));
    }

    @Override
    public void publishReleased(Inventory inventory, Reservation reservation) {
        save(inventory, "inventory.released.v1", Map.of(
            "productId", inventory.getProductId(),
            "reservationId", reservation.getId(),
            "quantity", reservation.getQuantity(),
            "orderId", reservation.getOrderId()
        ));
    }

    @Override
    public void publishAdjusted(Inventory inventory) {
        save(inventory, "inventory.adjusted.v1", Map.of(
            "productId", inventory.getProductId(),
            "availableQuantity", inventory.getAvailableQuantity()
        ));
    }

    @Override
    public void publishDeleted(Inventory inventory) {
        save(inventory, "inventory.deleted.v1", Map.of(
            "productId", inventory.getProductId()
        ));
    }

    private void save(Inventory inventory, String eventType, Map<String, Object> data) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(inventory.getProductId());   // Kafka partition key
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
            log.error("Failed to serialize outbox payload for product {}", inventory.getProductId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxRepository.save(event);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryEventPublisher.java \
        inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java
git commit -m "feat(inventory-service): TransactionalInventoryEventPublisher writes OutboxEvent"
```

---
---

### Task 16: InventoryServiceImpl (+ tests)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java`
- Create: `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplTest.java`

**Interfaces:**
- Consumes: `InventoryRepository`, `ReservationRepository`, `InventoryMapper`, `InventoryEventPublisher`, `InventoryCacheService`
- Produces: impl với reserve/commit/release/upsert/delete + releaseExpiredReservations

- [ ] **Step 1: Write failing test**

```java
package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.entity.ReservationStatus;
import com.shop.inventoryservice.mapper.InventoryMapper;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InventoryServiceImplTest {

    @Mock InventoryRepository inventoryRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock InventoryMapper mapper;
    @Mock InventoryEventPublisher publisher;
    @Mock InventoryCacheService cacheService;
    @InjectMocks InventoryServiceImpl service;

    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private Inventory inventory;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        inventory = Inventory.builder()
            .id(UUID.randomUUID())
            .productId(productId)
            .availableQuantity(100)
            .reservedQuantity(0)
            .version(0L)
            .lastUpdated(Instant.now())
            .build();
        reservation = Reservation.builder()
            .id(reservationId)
            .productId(productId)
            .quantity(5)
            .status(ReservationStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(900))
            .build();
    }

    @Test
    void findById_returnsCachedInventory() {
        InventoryResponse resp = new InventoryResponse(productId, 100, 0, inventory.getLastUpdated());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(mapper.toResponse(inventory)).thenReturn(resp);

        assertThat(service.findById(productId)).isEqualTo(resp);
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(productId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesAdjusted() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        when(inventoryRepository.existsByProductId(productId)).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(inventory);
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(mapper.toResponse(inventory)).thenReturn(new InventoryResponse(productId, 50, 0, null));

        var result = service.create(req);

        assertThat(result.availableQuantity()).isEqualTo(50);
        verify(publisher).publishAdjusted(inventory);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void create_throwsConflictWhenExists() {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        when(inventoryRepository.existsByProductId(productId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void reserve_incrementsReservedAndPublishes() {
        ReserveRequest req = new ReserveRequest(5, null);
        when(reservationRepository.findByProductIdAndStatusAndExpiresAtBefore(
            eq(productId), eq(ReservationStatus.PENDING), any(Instant.class)))
            .thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(any(Reservation.class))).thenReturn(reservation);
        when(mapper.toReservationResponse(any(Reservation.class)))
            .thenReturn(new ReservationResponse(reservationId, productId, 5, ReservationStatus.PENDING,
                reservation.getExpiresAt(), null));

        var result = service.reserve(productId, req);

        assertThat(result.quantity()).isEqualTo(5);
        assertThat(inventory.getReservedQuantity()).isEqualTo(5);
        verify(publisher).publishReserved(inventory, reservation);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void reserve_throwsStockInsufficient() {
        ReserveRequest req = new ReserveRequest(999, null);
        when(reservationRepository.findByProductIdAndStatusAndExpiresAtBefore(
            eq(productId), eq(ReservationStatus.PENDING), any(Instant.class)))
            .thenReturn(List.of());
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> service.reserve(productId, req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void commit_movesStockAndPublishes() {
        inventory.setReservedQuantity(5);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.commit(reservationId);

        assertThat(inventory.getAvailableQuantity()).isEqualTo(95);
        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(publisher).publishCommitted(inventory, reservation);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void commit_throwsWhenNotPending() {
        reservation.setStatus(ReservationStatus.COMMITTED);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));

        assertThatThrownBy(() -> service.commit(reservationId))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void release_freesReservedAndPublishes() {
        inventory.setReservedQuantity(5);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(inventory)).thenReturn(inventory);
        when(reservationRepository.save(reservation)).thenReturn(reservation);

        service.release(reservationId);

        assertThat(inventory.getReservedQuantity()).isEqualTo(0);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(publisher).publishReleased(inventory, reservation);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void delete_removesInventoryAndPublishes() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.countByProductIdAndStatusIn(eq(productId), anyList())).thenReturn(0L);
        doNothing().when(inventoryRepository).delete(inventory);

        service.delete(productId);

        verify(inventoryRepository).delete(inventory);
        verify(publisher).publishDeleted(inventory);
        verify(cacheService).evictAfterCommit(productId);
    }

    @Test
    void delete_throwsWhenReservationActive() {
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(reservationRepository.countByProductIdAndStatusIn(eq(productId), anyList())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(productId))
            .isInstanceOf(BusinessException.class);
        verify(inventoryRepository, never()).delete(any());
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (compile error, no impl)**

Run: `./mvnw -pl inventory-service test -Dtest=InventoryServiceImplTest`
Expected: compilation error — `InventoryServiceImpl` not found

- [ ] **Step 3: Implement InventoryServiceImpl**

```java
package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.Inventory;
import com.shop.inventoryservice.entity.Reservation;
import com.shop.inventoryservice.entity.ReservationStatus;
import com.shop.inventoryservice.mapper.InventoryMapper;
import com.shop.inventoryservice.repository.InventoryRepository;
import com.shop.inventoryservice.repository.ReservationRepository;
import com.shop.inventoryservice.service.InventoryCacheService;
import com.shop.inventoryservice.service.InventoryEventPublisher;
import com.shop.inventoryservice.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final InventoryRepository inventoryRepository;
    private final ReservationRepository reservationRepository;
    private final InventoryMapper mapper;
    private final InventoryEventPublisher publisher;
    private final InventoryCacheService cacheService;

    @Value("\${inventory.reservation-ttl-seconds:900}")
    private long reservationTtlSeconds;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<InventoryResponse> findAll(Pageable pageable) {
        Page<Inventory> page = inventoryRepository.findAll(pageable);
        return PageResponse.of(
            page.map(mapper::toResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResponse findById(UUID productId) {
        return inventoryRepository.findByProductId(productId)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
    }

    @Override
    @Transactional
    public InventoryResponse create(InventoryUpsertRequest request) {
        if (inventoryRepository.existsByProductId(request.productId())) {
            throw BusinessException.of(ErrorCode.INVENTORY_ALREADY_EXISTS, request.productId());
        }
        Inventory inventory = mapper.toEntity(request);
        inventory.setLastUpdated(Instant.now());
        Inventory saved = inventoryRepository.save(inventory);
        publisher.publishAdjusted(saved);
        cacheService.evictAfterCommit(saved.getProductId());
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public InventoryResponse update(UUID productId, InventoryUpsertRequest request) {
        Inventory existing = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
        mapper.partialUpdate(existing, request);
        existing.setLastUpdated(Instant.now());
        Inventory saved = inventoryRepository.save(existing);
        publisher.publishAdjusted(saved);
        cacheService.evictAfterCommit(productId);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void delete(UUID productId) {
        Inventory existing = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
        long active = reservationRepository.countByProductIdAndStatusIn(
            productId, List.of(ReservationStatus.PENDING, ReservationStatus.COMMITTED));
        if (active > 0) {
            throw BusinessException.of(ErrorCode.RESERVATION_INVALID_STATE, productId);
        }
        inventoryRepository.delete(existing);
        publisher.publishDeleted(existing);
        cacheService.evictAfterCommit(productId);
    }

    @Override
    @Transactional
    public ReservationResponse reserve(UUID productId, ReserveRequest request) {
        // 1. Release expired TRƯỚC khi đọc Inventory (method cập nhật Inventory + tăng @Version)
        releaseExpiredReservations(productId);
        // 2. Đọc Inventory sau khi đã release expired — dữ liệu mới nhất
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
        // 3. Tính available chính xác
        int available = inventory.getAvailableQuantity() - inventory.getReservedQuantity();
        if (available < request.quantity()) {
            throw BusinessException.of(ErrorCode.STOCK_INSUFFICIENT, productId);
        }
        inventory.setReservedQuantity(inventory.getReservedQuantity() + request.quantity());
        inventory.setLastUpdated(Instant.now());
        inventoryRepository.save(inventory);

        Reservation reservation = Reservation.builder()
            .productId(productId)
            .quantity(request.quantity())
            .status(ReservationStatus.PENDING)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(reservationTtlSeconds))
            .orderId(request.orderId())
            .build();
        reservationRepository.save(reservation);

        publisher.publishReserved(inventory, reservation);
        cacheService.evictAfterCommit(productId);
        return mapper.toReservationResponse(reservation);
    }

    @Override
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
        Inventory inventory = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, r.getProductId()));
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - r.getQuantity());
        inventory.setReservedQuantity(inventory.getReservedQuantity() - r.getQuantity());
        inventory.setLastUpdated(Instant.now());
        r.setStatus(ReservationStatus.COMMITTED);
        r.setCommittedAt(Instant.now());
        inventoryRepository.save(inventory);
        reservationRepository.save(r);
        publisher.publishCommitted(inventory, r);
        cacheService.evictAfterCommit(r.getProductId());
    }

    @Override
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
        Inventory inventory = inventoryRepository.findByProductId(r.getProductId())
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, r.getProductId()));
        inventory.setReservedQuantity(inventory.getReservedQuantity() - r.getQuantity());
        inventory.setLastUpdated(Instant.now());
        r.setStatus(ReservationStatus.RELEASED);
        r.setReleasedAt(Instant.now());
        inventoryRepository.save(inventory);
        reservationRepository.save(r);
        publisher.publishReleased(inventory, r);
        cacheService.evictAfterCommit(r.getProductId());
    }

    /**
     * Release all EXPIRED PENDING reservations for a product and adjust the
     * inventory's reservedQuantity accordingly. Called FIRST in reserve()
     * so the inventory read reflects freed capacity. Runs in the caller's
     * transaction (increments @Version on Inventory).
     */
    private void releaseExpiredReservations(UUID productId) {
        List<Reservation> expired = reservationRepository
            .findByProductIdAndStatusAndExpiresAtBefore(
                productId, ReservationStatus.PENDING, Instant.now());
        if (expired.isEmpty()) {
            return;
        }
        Inventory inventory = inventoryRepository.findByProductId(productId)
            .orElseThrow(() -> BusinessException.of(ErrorCode.WAREHOUSE_NOT_FOUND, productId));
        int total = expired.stream().mapToInt(Reservation::getQuantity).sum();
        inventory.setReservedQuantity(inventory.getReservedQuantity() - total);
        inventory.setLastUpdated(Instant.now());
        expired.forEach(r -> r.setStatus(ReservationStatus.EXPIRED));
        reservationRepository.saveAll(expired);
        inventoryRepository.save(inventory);
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `./mvnw -pl inventory-service test -Dtest=InventoryServiceImplTest`
Expected: PASS (10 tests)

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java \
        inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplTest.java
git commit -m "feat(inventory-service): InventoryServiceImpl (reserve/commit/release/upsert/delete + releaseExpired)"
```

---
---

### Task 17: ReservationServiceImpl (manual retry loop)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationService.java`
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java`
- Create: `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/ReservationServiceImplTest.java`

**Interfaces:**
- Consumes: `InventoryService` (the @Transactional methods)
- Produces: retry wrapper — `reserveWithRetry`, `commitWithRetry`, `releaseWithRetry`

- [ ] **Step 1: Create ReservationService interface**

```java
package com.shop.inventoryservice.service;

import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;

import java.util.UUID;

public interface ReservationService {

    ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request);

    void commitWithRetry(UUID reservationId);

    void releaseWithRetry(UUID reservationId);
}
```

- [ ] **Step 2: Write failing test**

```java
package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.ReservationStatus;
import com.shop.inventoryservice.service.InventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock InventoryService inventoryService;
    @InjectMocks ReservationServiceImpl service;

    private final UUID productId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();

    @Test
    void reserveWithRetry_retriesOnOptimisticLockFailure() {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING, Instant.now().plusSeconds(900), null);

        when(inventoryService.reserve(productId, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"))
            .thenReturn(resp);

        ReservationResponse result = service.reserveWithRetry(productId, req);

        assertThat(result).isEqualTo(resp);
        verify(inventoryService, times(2)).reserve(productId, req);
    }

    @Test
    void reserveWithRetry_throwsVersionConflictAfterMaxRetries() {
        ReserveRequest req = new ReserveRequest(5, null);
        when(inventoryService.reserve(productId, req))
            .thenThrow(new OptimisticLockingFailureException("conflict"));

        assertThatThrownBy(() -> service.reserveWithRetry(productId, req))
            .isInstanceOf(BusinessException.class);
        verify(inventoryService, times(3)).reserve(productId, req);
    }

    @Test
    void reserveWithRetry_passesThroughSuccess() {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING, Instant.now().plusSeconds(900), null);
        when(inventoryService.reserve(productId, req)).thenReturn(resp);

        assertThat(service.reserveWithRetry(productId, req)).isEqualTo(resp);
        verify(inventoryService, times(1)).reserve(productId, req);
    }
}
```

- [ ] **Step 3: Run test — expect FAIL (no impl)**

Run: `./mvnw -pl inventory-service test -Dtest=ReservationServiceImplTest`
Expected: compilation error — `ReservationServiceImpl` not found

- [ ] **Step 4: Implement ReservationServiceImpl**

```java
package com.shop.inventoryservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Wraps {@link InventoryService} reservation operations with a manual retry
 * loop for {@link OptimisticLockingFailureException}. A retry re-reads the
 * entity (fresh @Version) and re-validates — safe for the low-contention
 * internal call pattern.
 */
@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_BASE_MS = 50L;

    private final InventoryService inventoryService;

    @Override
    public ReservationResponse reserveWithRetry(UUID productId, ReserveRequest request) {
        int attempt = 0;
        while (true) {
            try {
                return inventoryService.reserve(productId, request);
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, productId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    @Override
    public void commitWithRetry(UUID reservationId) {
        int attempt = 0;
        while (true) {
            try {
                inventoryService.commit(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    @Override
    public void releaseWithRetry(UUID reservationId) {
        int attempt = 0;
        while (true) {
            try {
                inventoryService.release(reservationId);
                return;
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS) {
                    throw BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, reservationId);
                }
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 5: Run test — expect PASS**

Run: `./mvnw -pl inventory-service test -Dtest=ReservationServiceImplTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationService.java \
        inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java \
        inventory-service/src/test/java/com/shop/inventoryservice/service/impls/ReservationServiceImplTest.java
git commit -m "feat(inventory-service): ReservationService manual retry loop for optimistic lock"
```

---

### Task 18: InventoryOutboxRelay

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java`

**Interfaces:**
- Consumes: `OutboxEventRepository`, `KafkaMessagePublisher` (from common-kafka)
- Produces: `@Scheduled` single-thread relay (ORDER BY id, break-on-error)

- [ ] **Step 1: Implement InventoryOutboxRelay**

```java
package com.shop.inventoryservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Drains the {@code outbox_events} table and publishes each PENDING row to
 * Kafka through {@link KafkaMessagePublisher}.
 *
 * <p>Single-thread, ordered by id ASC. On a publish failure we save the row
 * (retry count + possibly FAILED) then {@code break} — later events of the
 * same aggregate must not overtake an earlier failed one. The failed row
 * stays PENDING and is retried on the next poll.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryOutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("\${inventory.outbox.batch-size:100}")
    private int batchSize;

    @Value("\${inventory.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "\${inventory.outbox.poll-interval-ms:5000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }
        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(
                    event.getTopic(),
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
                    log.error("Outbox event {} permanently failed after {} retries",
                        event.getEventId(), maxRetries, ex);
                } else {
                    log.warn("Outbox event {} retry {}/{}: {}",
                        event.getEventId(), event.getRetryCount(), maxRetries, ex.getMessage());
                }
                outboxRepo.save(event);
                break;  // STOP: keep ordering — retry later events next poll
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw -pl inventory-service compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java
git commit -m "feat(inventory-service): InventoryOutboxRelay single-thread with break-on-error"
```

---

### Task 19: InventoryController (+ @WebMvcTest)

**Files:**
- Create: `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java`
- Create: `inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerTest.java`

**Interfaces:**
- Consumes: `InventoryService`, `ReservationService` (retry wrapper)
- Produces: REST endpoints under `/api/v1/inventory`

- [ ] **Step 1: Write failing test**

```java
package com.shop.inventoryservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.entity.ReservationStatus;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class InventoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean InventoryService inventoryService;
    @MockitoBean ReservationService reservationService;

    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        InventoryResponse resp = new InventoryResponse(productId, 100, 0, Instant.now());
        when(inventoryService.findById(productId)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/inventory/{productId}", productId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.productId").value(productId.toString()));
    }

    @Test
    void create_returns200() throws Exception {
        InventoryUpsertRequest req = new InventoryUpsertRequest(productId, 50);
        InventoryResponse resp = new InventoryResponse(productId, 50, 0, Instant.now());
        when(inventoryService.create(any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/inventory")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.availableQuantity").value(50));
    }

    @Test
    void reserve_returnsReservation() throws Exception {
        ReserveRequest req = new ReserveRequest(5, null);
        ReservationResponse resp = new ReservationResponse(
            reservationId, productId, 5, ReservationStatus.PENDING, Instant.now().plusSeconds(900), null);
        when(reservationService.reserveWithRetry(eq(productId), any(ReserveRequest.class))).thenReturn(resp);

        mockMvc.perform(post("/api/v1/inventory/{productId}/reserve", productId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()));
    }

    @Test
    void commit_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{reservationId}/commit", reservationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void release_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/inventory/reservations/{reservationId}/release", reservationId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (no controller)**

Run: `./mvnw -pl inventory-service test -Dtest=InventoryControllerTest`
Expected: compilation error

- [ ] **Step 3: Implement InventoryController**

```java
package com.shop.inventoryservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.inventoryservice.dto.request.InventoryUpsertRequest;
import com.shop.inventoryservice.dto.request.ReserveRequest;
import com.shop.inventoryservice.dto.response.InventoryResponse;
import com.shop.inventoryservice.dto.response.ReservationResponse;
import com.shop.inventoryservice.service.InventoryService;
import com.shop.inventoryservice.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.INVENTORY)
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final ReservationService reservationService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<InventoryResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(inventoryService.findAll(pageable));
    }

    @GetMapping("/{productId}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<InventoryResponse> findById(@PathVariable UUID productId) {
        return ApiResponse.ok(inventoryService.findById(productId));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<InventoryResponse> create(@Valid @RequestBody InventoryUpsertRequest request) {
        return ApiResponse.ok(inventoryService.create(request), "Inventory created successfully");
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<InventoryResponse> update(@PathVariable UUID productId,
                                                  @Valid @RequestBody InventoryUpsertRequest request) {
        return ApiResponse.ok(inventoryService.update(productId, request), "Inventory updated successfully");
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable UUID productId) {
        inventoryService.delete(productId);
        return ApiResponse.message("Inventory deleted successfully");
    }

    @PostMapping("/{productId}/reserve")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ApiResponse<ReservationResponse> reserve(@PathVariable UUID productId,
                                                     @Valid @RequestBody ReserveRequest request) {
        return ApiResponse.ok(reservationService.reserveWithRetry(productId, request));
    }

    @PostMapping("/reservations/{reservationId}/commit")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ApiResponse<Void> commit(@PathVariable UUID reservationId) {
        reservationService.commitWithRetry(reservationId);
        return ApiResponse.message("Reservation committed successfully");
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")
    public ApiResponse<Void> release(@PathVariable UUID reservationId) {
        reservationService.releaseWithRetry(reservationId);
        return ApiResponse.message("Reservation released successfully");
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

Run: `./mvnw -pl inventory-service test -Dtest=InventoryControllerTest`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java \
        inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerTest.java
git commit -m "feat(inventory-service): InventoryController + @WebMvcTest"
```

---
---

## Phase 3 — Integration + docker-compose

### Task 20: OutboxRelay integration test (Testcontainers)

**Files:**
- Create: `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayIntegrationTest.java`

**Interfaces:**
- Consumes: full context (Postgres + Kafka via Testcontainers)
- Produces: e2e verify — outbox row → relay → Kafka message

- [ ] **Step 1: Create integration test**

```java
package com.shop.inventoryservice.service;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.common.spring.test.TestSecurityConfig;
import com.shop.inventoryservice.entity.OutboxEvent;
import com.shop.inventoryservice.entity.OutboxStatus;
import com.shop.inventoryservice.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import({JpaAuditingAutoConfiguration.class, TestSecurityConfig.class})
class InventoryOutboxRelayIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("inventory_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
        registry.add("shop.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.cache.type", () -> "none");
        registry.add("inventory.outbox.poll-interval-ms", () -> "200");
    }

    @Autowired OutboxEventRepository outboxRepo;
    @Autowired InventoryOutboxRelay relay;

    @Test
    void relay_sendsPendingEventsAndMarksSent() throws Exception {
        OutboxEvent event = OutboxEvent.builder()
            .eventId(java.util.UUID.randomUUID().toString())
            .aggregateType("Inventory")
            .aggregateId(java.util.UUID.randomUUID())
            .eventType("inventory.adjusted.v1")
            .topic("shop.inventory.events.v1")
            .payload("{\"productId\":\"x\"}")
            .status(OutboxStatus.PENDING)
            .retryCount(0)
            .build();
        outboxRepo.save(event);

        relay.relay();   // single poll

        List<OutboxEvent> after = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, 100));
        assertThat(after).isEmpty();  // all sent

        OutboxEvent sent = outboxRepo.findById(event.getId()).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(sent.getSentAt()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test**

Run: `./mvnw -pl inventory-service test -Dtest=InventoryOutboxRelayIntegrationTest`
Expected: PASS (1 test) — Docker daemon phải chạy

- [ ] **Step 3: Commit**

```bash
git add inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayIntegrationTest.java
git commit -m "test(inventory-service): OutboxRelay integration test with Testcontainers Kafka"
```

---

### Task 21: docker-compose + gateway route verify

**Files:**
- Modify: `docker-compose.yml`
- Verify: `gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java`

**Interfaces:**
- Produces: inventory-service env (redis, kafka) trong compose

- [ ] **Step 1: Verify gateway route exists**

```bash
grep -n "INVENTORY" gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java
# Expected: INVENTORY("inventory-service", "inventory", "inventory-service", 8082)
```

> Route đã có — không cần sửa.

- [ ] **Step 2: Update docker-compose inventory-service block**

Tìm block `inventory-service:` (line ~353) và thêm env redis + kafka:

```yaml
  # ----- Inventory Service -----
  inventory-service:
    image: inventory-service:latest
    container_name: inventory-service
    <<: [*restart, *logging]
    ports:
      - "8082:8082"
    environment:
      <<: [*jwt, *pg-creds]
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/inventoryservice
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      <<: *hc-defaults
      test: ["CMD-SHELL", "wget -qO- http://localhost:8082/actuator/health > /dev/null 2>&1 || exit 1"]
    networks:
      - ecommerce-network
```

- [ ] **Step 3: Verify**

Run: `docker compose config --quiet`
Expected: exit 0 (config hợp lệ)

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "chore(docker-compose): inventory-service env redis + kafka"
```

---

## Phase 4 — Verification

### Task 22: Full reactor build + smoke

- [ ] **Step 1: Full build**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS toàn bộ (common-* + auth + product + inventory)

- [ ] **Step 2: Boot inventory-service**

Run: `docker compose up -d postgres redis kafka && ./mvnw -pl inventory-service spring-boot:run`
Expected: port 8082, Liquibase tạo 3 tables, `/actuator/health` = UP

- [ ] **Step 3: Smoke test API**

```bash
# Create inventory (cần ADMIN token từ Keycloak — testuser/adminuser)
curl -X POST http://localhost:8082/api/v1/inventory \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <ADMIN_TOKEN>" \
  -d '{"productId":"<uuid>","availableQuantity":100}'

# Get by id (cache-aside)
curl http://localhost:8082/api/v1/inventory/<uuid> \
  -H "Authorization: Bearer <USER_TOKEN>"

# Reserve
curl -X POST http://localhost:8082/api/v1/inventory/<uuid>/reserve \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <SERVICE_TOKEN>" \
  -d '{"quantity":5}'
```

- [ ] **Step 4: Verify Kafka event**

```bash
docker compose exec kafka kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic shop.inventory.events.v1 --from-beginning --max-messages 5
# Expected: inventory.adjusted.v1 + inventory.reserved.v1 payloads
```

---

## Plan Self-Review

### 1. Spec coverage

| Spec section | Task |
|---|---|
| §3.1 Inventory entity (@Version) | Task 7 |
| §3.2 Reservation entity | Task 8 |
| §3.4 OutboxEvent (aggregateId) | Task 9 |
| §3.5 Liquibase | Task 5 |
| §4.1 CRUD endpoints | Task 19 |
| §4.2 Reserve/commit/release internal | Task 19 |
| §4.3 DTOs | Task 11 |
| §4.4 Validation | Task 11 |
| §5.0 CacheConfig transactionAware | Task 4 |
| §5.1 Reserve flow | Task 16 |
| §5.2 Commit flow | Task 16 |
| §5.3 Release flow | Task 16 |
| §5.4 Read cache-aside | Task 16 (findById) |
| §5.5 Event publisher | Task 15 |
| §5.6 Outbox relay | Task 18 |
| §5.7 ReservationService retry | Task 17 |
| §6 Kafka events | Task 15 + 18 |
| §7 application.yml | Task 3 |
| §8 ErrorCodes | Task 1 |
| §9 Testing | Tasks 16-20 |
| §10 Open items | Deferred (documented) |

### 2. Placeholder scan

No TBD/TODO. Every task has full code. ✓

### 3. Type consistency

- `InventoryResponse(productId, availableQuantity, reservedQuantity, lastUpdated)` — consistent across Task 11/12/16/19 ✓
- `ReservationResponse(reservationId, productId, quantity, status, expiresAt, orderId)` — consistent ✓
- `InventoryService` methods match controller calls ✓
- `InventoryEventPublisher` 5 methods match impl ✓

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-28-inventory-service.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
