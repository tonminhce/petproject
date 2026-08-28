# Favourite Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `favourite-service` microservice with user-scoped favourites CRUD, soft-delete with audit, Keycloak JWT auth — no Kafka, no Redis, no cross-service calls.

**Architecture:** Spring Boot 4.1.1 microservice (`com.shop.favouriteservice`) → PostgreSQL via Spring Data JPA + Liquibase. Auth via Keycloak JWT (every endpoint requires authentication; current user resolved via `AuthenticatedUser.requireCurrent()` from `common-security`). Soft-delete via `AbstractMappedEntity` (extends `SoftDeletable`); `AuthedUser.id()` parsed from JWT `sub` to `UUID` at the controller boundary.

**Tech Stack:** Spring Boot 4.1.1, Java 25, Spring Data JPA + Liquibase + Postgres 16, Apache Kafka — **NOT USED**, Redis — **NOT USED**, Spring Security Resource Server (JWT, no Keycloak admin SDK), ModelMapper 3.2.6, Lombok, JUnit 5 + Mockito + AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-28-favourite-service-design.md`](../specs/2026-08-28-favourite-service-design.md) — read alongside this plan; the plan argues from the spec.

---

## Global Constraints

- **Java 25** (per project `.java-version` / parent pom)
- **Spring Boot 4.1.1** (parent pom `spring-boot-starter-parent.version`)
- **Package root:** `com.shop.*`
- **Group:** `com.shop.microservices` — version `${revision}` (1.0-SNAPSHOT)
- **No per-service `SecurityConfig`** — `common-security` auto-configures `SecurityFilterChain` (`@ConditionalOnMissingBean`). Configure via `shop.security.public-paths` (EndpointRule list — favourite-service declares empty list).
- **`open-in-view: false`** in all `application.yml` (no transaction-in-view).
- **ModelMapper** (sync theo auth-service + product-service — `@Component` inject `ModelMapper`). MapStruct đã bị reject toàn fleet.
- **Liquibase** (not Flyway), changelogs in `src/main/resources/db/changelog/`
- **All endpoints** wrap responses in `ApiResponse<T>` (from `common-core/viewmodel`).
- **No new common modules** — only modify `common-core`, `common-spring` for the 2 ErrorCodes + 3 i18n keys.
- **Favourite-service dependency baseline** (mirror product-service/pom.xml minus Kafka/Redis and without `spring-security-test` — controller slice uses `addFilters=false` so security isn't on the path; JPA slices don't need it either): `common-spring`, `spring-boot-starter-data-jpa`, `spring-boot-starter-liquibase`, `liquibase-core`, `postgresql`, `modelmapper`, `lombok`. Test: `spring-boot-starter-test`, `spring-boot-starter-webmvc-test` (Boot 4), `spring-boot-data-jpa-test`, `spring-boot-jpa-test`, `testcontainers-junit-jupiter`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `awaitility`.
- **Schema rules:** entity `extends AbstractMappedEntity` (auto `created_at`/`updated_at`/`created_by`/`updated_by` + `deleted`/`deleted_at`/`deleted_by`) + `@SQLRestriction("deleted = false")`. UUID primary key. Partial unique indexes `WHERE deleted = false`. FK `onDelete: RESTRICT` (but favourite-service declares **no FK** to product-service per §6.1).
- **Auth pattern:** `@PreAuthorize("isAuthenticated()")` at class level; current user via `AuthenticatedUser.requireCurrent().id()` (parsed to `UUID` at controller boundary). NOT `@AuthenticationPrincipal AuthenticatedUser` — Spring's resolver matches on `Jwt` principal type, not the custom record. See spec §2.3 for full rationale.
- **Exceptions** dùng `BusinessException.of(ErrorCode.X, args...)` factories — constructor private, KHÔNG `new BusinessException(...)`; message là i18n keys.
- **Test stack Boot 4:** `@MockitoBean` (không `@MockBean`); controller slice uses new package `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`); controller tests `@AutoConfigureMockMvc(addFilters = false)` (không test 403 ở slice — common-security chain test ở integration). JPA slice ở Boot 4 đã tách artifacts riêng: `@DataJpaTest` ở `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`); `TestEntityManager` ở `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`). Boot 4 cũng KHÔNG cho phép `TestEntityManager` inject qua method param — phải `@Autowired` field. `@DataJpaTest` cần `@Import(LiquibaseAutoConfiguration.class)` (slice không tự chạy Liquibase).
- **No Kafka.** No Redis. No `@EnableCaching`. No `@Scheduled`. No cross-service HTTP. No Resilence4j. No common-keycloak dep. No common-storage dep. No common-kafka dep.

---

## File Structure

### Modified common modules
| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | Add `FAVOURITE_NOT_FOUND ("FAV-6001")` + `FAVOURITE_ALREADY_EXISTS ("FAV-6002")` in a new "// ---- Favourite domain ----" section (range 6xxx — PAY already owns 5xxx) |
| `utils/common-spring/src/main/resources/messages/messages_en.properties` | Add `favourite.not.found`, `favourite.already.exists`, `favourite.user.subject.malformed` |
| `utils/common-spring/src/main/resources/messages/messages_vi.properties` | Add Vietnamese variants of the same 3 keys |

### New favourite-service files (main)
| File | Responsibility |
|---|---|
| `favourite-service/pom.xml` | Maven module — Boot 4 deps + test jars per Global Constraints |
| `favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java` | `@SpringBootApplication` entrypoint |
| `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java` | JPA entity, extends `AbstractMappedEntity`, `@SQLRestriction("deleted = false")` |
| `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java` | `JpaRepository<Favourite, UUID>` + derived queries + 2 `@Modifying` soft-delete JPQL queries |
| `favourite-service/src/main/java/com/shop/favouriteservice/dto/request/FavouriteCreateRequest.java` | record: `UUID productId` @NotNull |
| `favourite-service/src/main/java/com/shop/favouriteservice/dto/response/FavouriteResponse.java` | record: `id`, `userId`, `productId`, `createdAt` |
| `favourite-service/src/main/java/com/shop/favouriteservice/mapper/FavouriteMapper.java` | `@Component` `ModelMapper` injected; only `toResponse()` — entity is built directly with `.builder()` in service (simpler than a record→entity round-trip) |
| `favourite-service/src/main/java/com/shop/favouriteservice/service/FavouriteService.java` | interface: 5 methods |
| `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java` | `@Service` + `@RequiredArgsConstructor` (no `@LogPerformance` — annotation exists in `common-logging` but product-service fleet doesn't use it; ponytail-lite keeps the service minimal) |
| `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` | `@RequestMapping(ApiPaths.FAVOURITES)`, class-level `@PreAuthorize("isAuthenticated()")`, 5 endpoints, static `currentUserId()` helper |
| `favourite-service/src/main/resources/application.yml` | port 8081, datasource, JPA validate, Liquibase, empty `shop.security.public-paths` |
| `favourite-service/src/main/resources/db/changelog/db.changelog-master.yaml` | include 001 |
| `favourite-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 1 table + 2 indexes (partial unique + user_id) |

### New favourite-service files (tests)
| File | Coverage |
|---|---|
| `favourite-service/src/test/java/com/shop/favouriteservice/service/impls/FavouriteServiceImplTest.java` | 10 unit tests (Mockito `@Mock` repo + mapper + `AuditorAware`, `@InjectMocks` service) |
| `favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java` | 3 JPA slice tests (`@DataJpaTest` + `@Import(LiquibaseAutoConfiguration.class)` + `@Autowired TestEntityManager` field + Testcontainers Postgres) |
| `favourite-service/src/test/java/com/shop/favouriteservice/controller/FavouriteControllerTest.java` | 6 MVC slice tests (`@WebMvcTest(FavouriteController.class)` + `@AutoConfigureMockMvc(addFilters=false)` + `@MockitoBean(FavouriteService)` + `@Import(ApiExceptionHandler.class)`) |

### Modified infra
| File | Change |
|---|---|
| `docker-compose.yml` | Add `favourite-service` block (SPRING_DATASOURCE_URL env, depends_on postgres) — NO Redis/Kafka deps |
| `pom.xml` (parent) | **VERIFY** `<module>favourite-service</module>` already present (line 29 per existing parent) — no edit expected |

---

## Task 1: Common additions — Favourite ErrorCodes + i18n keys

**Files:**
- Modify: `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`
- Modify: `utils/common-spring/src/main/resources/messages/messages_en.properties`
- Modify: `utils/common-spring/src/main/resources/messages/messages_vi.properties`

**Interfaces:**
- Produces: 2 new `ErrorCode` enum constants; 3 new i18n keys each in `messages_en.properties` + `messages_vi.properties`

- [ ] **Step 1: Add ErrorCode constants**

Open `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`. Add a new section block at the end of the enum (before the closing brace), after the `PAYMENT_*` block:

```java
    // ---- Favourite domain ---- (range 6xxx — PAY already owns 5xxx)
    FAVOURITE_NOT_FOUND("FAV-6001", "favourite.not.found", HttpStatus.NOT_FOUND),
    FAVOURITE_ALREADY_EXISTS("FAV-6002", "favourite.already.exists", HttpStatus.CONFLICT);
```

Note the **semicolon** at the end of `FAVOURITE_ALREADY_EXISTS(...);` — that becomes the last entry, so the existing semicolon on the prior line (`PAYMENT_NOT_FOUND(...);`) must be removed first.

Find the line:
```java
    PAYMENT_NOT_FOUND("PAY-5002", "payment.not.found", HttpStatus.NOT_FOUND);
```
Replace it with:
```java
    PAYMENT_NOT_FOUND("PAY-5002", "payment.not.found", HttpStatus.NOT_FOUND),
```
(remove the trailing `;`, replace with `,`).

- [ ] **Step 2: Add English i18n keys**

Open `utils/common-spring/src/main/resources/messages/messages_en.properties`. Append the following lines at the bottom:

```properties
favourite.not.found=Favourite {0} not found
favourite.already.exists=Favourite already exists
favourite.user.subject.malformed=Authentication token subject is not a valid user id
```

- [ ] **Step 3: Add Vietnamese i18n keys**

Open `utils/common-spring/src/main/resources/messages/messages_vi.properties`. Append:

```properties
favourite.not.found=Không tìm thấy mục yêu thích {0}
favourite.already.exists=Mục yêu thích đã tồn tại
favourite.user.subject.malformed=Token xác thực không chứa user id hợp lệ
```

- [ ] **Step 4: Verify common modules still compile and tests pass**

Run from project root:
```bash
./mvnw -pl utils/common-core,utils/common-spring -am test -q
```
Expected: `BUILD SUCCESS` for both modules. No new tests added — only additive changes to existing files; existing tests must remain green.

- [ ] **Step 5: Commit**

```bash
git add utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java \
        utils/common-spring/src/main/resources/messages/messages_en.properties \
        utils/common-spring/src/main/resources/messages/messages_vi.properties
git commit -m "feat(common): FAVOURITE_NOT_FOUND + FAVOURITE_ALREADY_EXISTS ErrorCodes + i18n keys"
```

---

## Task 2: favourite-service module — pom.xml (extend existing) + Application class

**Files:**
- Modify: `favourite-service/pom.xml` (scaffold exists; add 7 missing deps + spring-boot-starter-liquibase)
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java`
- Verify (no edit): `pom.xml` (parent) — `<module>favourite-service</module>` is already declared at line 29

**Interfaces:**
- Consumes: parent reactor build (parent pom already has `favourite-service` module)
- Produces: Maven-compilable module with `FavouriteServiceApplication.main(String[])`

**Current pom.xml state** (verified at execution time):
- Has: `common-spring`, `spring-boot-starter-data-jpa`, `liquibase-core`, `postgresql`, `modelmapper`, `lombok`, `spring-boot-starter-test`, `spring-boot-maven-plugin`
- Missing: `spring-boot-starter-liquibase` (without it, Spring Boot can't auto-configure Liquibase even though `liquibase-core` is on the classpath), `spring-boot-starter-webmvc-test` (Boot 4 webmvc test artifact), `spring-boot-data-jpa-test` (Boot 4 data JPA test artifact), `spring-boot-jpa-test` (Boot 4 jpa test artifact for `TestEntityManager`), `testcontainers-junit-jupiter`, `spring-boot-testcontainers`, `testcontainers-postgresql`, `awaitility`

NO `common-kafka`, `spring-boot-starter-data-redis`, `spring-boot-starter-cache`, `spring-kafka`, `spring-security-test` — keep that absence.

- [ ] **Step 1: Modify `favourite-service/pom.xml` — add missing dependencies**

Open `favourite-service/pom.xml` and make these targeted additions. Use your editor's "add new dependency block after…" UX to avoid touching existing lines.

**1a.** After the existing `spring-boot-starter-data-jpa` block (or after `liquibase-core`; consistency doesn't matter), insert:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-liquibase</artifactId>
        </dependency>
```

**1b.** Inside the existing `<!-- Tests -->` comment block, append these test dependencies after the existing `spring-boot-starter-test`:

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
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

(7 test deps total — `spring-security-test` deliberately omitted; controller slice uses `addFilters=false` so security isn't on the path. JPA slices don't need it either — matches product-service pom.)

- [ ] **Step 2: Verify parent pom.xml declares favourite-service**

Open `pom.xml` (parent) and verify `<module>favourite-service</module>` appears in the `<modules>` block (line 29). Already present from initial scaffold; do nothing.

- [ ] **Step 3: Create FavouriteServiceApplication.java**

Create `favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java`:

```java
package com.shop.favouriteservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FavouriteServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FavouriteServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Verify compile**

Run from project root:
```bash
./mvnw -pl favourite-service -am compile
```
Expected: `BUILD SUCCESS`. No `application.yml` yet, but Application class still compiles (no context bootstrap on `compile`).

- [ ] **Step 5: Commit**

```bash
git add favourite-service/pom.xml \
        favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java
git commit -m "feat(favourite-service): module skeleton (extended pom + SpringBootApplication)"
```

---

## Task 3: application.yml + Liquibase schema

**Files:**
- Create: `favourite-service/src/main/resources/application.yml`
- Create: `favourite-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `favourite-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`

**Interfaces:**
- Produces: app boots locally on port 8081; Liquibase creates `favourites` table with partial unique index.

- [ ] **Step 1: Create application.yml**

Create `favourite-service/src/main/resources/application.yml`:

```yaml
# =============================================================================
#  favourite-service — User wishlist (favourites).
#  Pure user-scoped CRUD. No Kafka, no Redis, no external service calls.
#
#  Platform-wide defaults live in `common-spring/application.yml` (inherited).
#  Only service-specific overrides belong here.
# =============================================================================
spring:
  application:
    name: favourite-service

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/favouriteservice}
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

server:
  # Override the common-spring default 8080 (compose maps 8081:8081).
  port: ${SERVER_PORT:8081}
  shutdown: graceful

shop:
  application:
    name: favourite-service
  security:
    # Every endpoint requires a valid JWT — favourite data is private to its owner.
    # Platform defaults (actuator/swagger/api-docs) stay public via common-security.
    public-paths: []
```

(`/actuator/*` health/prometheus endpoints already exposed by `common-spring/application.yml` baseline — no override needed here.)

- [ ] **Step 2: Create Liquibase master changelog**

Create `favourite-service/src/main/resources/db/changelog/db.changelog-master.yaml`:

```yaml
databaseChangeLog:
  - include:
      file: changelog-001-initial-schema.yaml
```

- [ ] **Step 3: Create initial schema changelog**

Create `favourite-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`:

```yaml
databaseChangeLog:
  # ===============================================================================
  # favourites table
  #   11 columns: id + 2 domain (user_id, product_id) + 4 audit (AbstractMappedEntity)
  #               + 3 soft-delete (SoftDeletable: deleted, deleted_at, deleted_by)
  #   2 indexes: partial unique on (user_id, product_id) WHERE deleted=false
  #              + lookup index on (user_id)
  #   No FK to product-service.products (cross-schema coupling avoided per spec §6.1)
  # ===============================================================================
  - createTable:
      tableName: favourites
      columns:
        - column:
            name: id
            type: UUID
            constraints:
              primaryKey: true
              nullable: false
        - column:
            name: user_id
            type: UUID
            constraints:
              nullable: false
        - column:
            name: product_id
            type: UUID
            constraints:
              nullable: false
        - column:
            name: created_at
            type: TIMESTAMP
            constraints:
              nullable: false
        - column:
            name: updated_at
            type: TIMESTAMP
            constraints:
              nullable: false
        - column:
            name: created_by
            type: VARCHAR(100)
        - column:
            name: updated_by
            type: VARCHAR(100)
        - column:
            name: deleted
            type: BOOLEAN
            constraints:
              nullable: false
              defaultValue: false
        - column:
            name: deleted_at
            type: TIMESTAMP
        - column:
            name: deleted_by
            type: VARCHAR(255)

  - createIndex:
      tableName: favourites
      indexName: idx_favourites_user_product_unique_active
      unique: true
      columns:
        - column:
            name: user_id
        - column:
            name: product_id
      # Partial unique — only enforced for active (non-deleted) rows.
      # Allows the same (user, product) to be re-favourited after a soft-delete.
      where: deleted = false

  - createIndex:
      tableName: favourites
      indexName: idx_favourites_user_id
      columns:
        - column:
            name: user_id
```

- [ ] **Step 4: Verify Liquibase YAML is parseable + Application loads**

Run from project root (compiles + loads context minus DB connection — this catches YAML syntax errors):
```bash
./mvnw -pl favourite-service -am test-compile
```
Expected: `BUILD SUCCESS`.

(Liquibase migration itself runs only when the app boots against a live Postgres. A future Task 11 will verify end-to-end with docker-compose.)

- [ ] **Step 5: Commit**

```bash
git add favourite-service/src/main/resources/application.yml \
        favourite-service/src/main/resources/db/changelog/
git commit -m "feat(favourite-service): application.yml + Liquibase initial schema"
```

---

## Task 4: Favourite entity

**Files:**
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java`

**Interfaces:**
- Consumes: `AbstractMappedEntity` (common-core) — provides 4 audit cols + soft-delete cols + `markDeleted` / `markRestored`
- Produces: `Favourite` entity mappable to `favourites` table (matches Task 3 Liquibase DDL exactly)

- [ ] **Step 1: Create entity file**

Create `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java`:

```java
package com.shop.favouriteservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Entity
@Table(name = "favourites")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Favourite extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;
}
```

Note: `@GeneratedValue(strategy = GenerationType.UUID)` matches the actual fleet convention — confirmed in `product-service/src/main/java/com/shop/productservice/entity/Product.java:22`, `Category.java:24`, `Brand.java:23`. Bare `@GeneratedValue` is unsafe for UUID columns in Hibernate 6 (may fall back to `IDENTITY` strategy which expects an integer column).

- [ ] **Step 2: Verify entity compiles and JPA schema validation passes**

Run from project root:
```bash
./mvnw -pl favourite-service -am compile
```
Expected: `BUILD SUCCESS`.

(JPA schema validation `ddl-auto: validate` only triggers at app boot — that's a future Task 11 verification with Postgres running.)

- [ ] **Step 3: Commit**

```bash
git add favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java
git commit -m "feat(favourite-service): Favourite entity (extends AbstractMappedEntity, soft-delete)"
```

---

## Task 5: FavouriteRepository — interface with derived queries + soft-delete JPQL

**Files:**
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java`

**Interfaces:**
- Consumes: `Favourite` entity (Task 4)
- Produces: Bean implementing `JpaRepository<Favourite, UUID>` with 5 derived queries + 2 `@Modifying` soft-delete updates. Used by `FavouriteServiceImpl` (Task 6) and `FavouriteRepositoryTest` (Task 9).

- [ ] **Step 1: Create repository interface**

Create `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java`:

```java
package com.shop.favouriteservice.repository;

import com.shop.favouriteservice.entity.Favourite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavouriteRepository extends JpaRepository<Favourite, UUID> {

    /**
     * Returns the current user's favourites, newest first. {@code @SQLRestriction}
     * on the entity auto-filters soft-deleted rows — no {@code AndDeletedFalse} suffix needed.
     */
    List<Favourite> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Finds a favourite by id scoped to its owning user. Returns empty when the row
     * exists but belongs to a different user — lets the service throw NOT_FOUND
     * without leaking cross-user existence.
     */
    Optional<Favourite> findByIdAndUserId(UUID id, UUID userId);

    Optional<Favourite> findByUserIdAndProductId(UUID userId, UUID productId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Soft-deletes a single favourite when it belongs to the given user.
     *
     * @return number of rows updated (0 if id missing or owner mismatch — both
     *         intentionally treated as "not found" by the service layer)
     */
    @Modifying
    @Query("""
            UPDATE Favourite f
               SET f.deleted = true,
                   f.deletedAt = CURRENT_TIMESTAMP,
                   f.deletedBy = :deletedBy
             WHERE f.id = :id
               AND f.userId = :userId
               AND f.deleted = false
            """)
    int softDeleteByIdAndUserId(@Param("id") UUID id,
                                 @Param("userId") UUID userId,
                                 @Param("deletedBy") String deletedBy);

    /**
     * Soft-deletes by (userId, productId) pair. Used by the
     * {@code DELETE /api/v1/favourites/by-product/{productId}} endpoint.
     */
    @Modifying
    @Query("""
            UPDATE Favourite f
               SET f.deleted = true,
                   f.deletedAt = CURRENT_TIMESTAMP,
                   f.deletedBy = :deletedBy
             WHERE f.userId = :userId
               AND f.productId = :productId
               AND f.deleted = false
            """)
    int softDeleteByUserIdAndProductId(@Param("userId") UUID userId,
                                        @Param("productId") UUID productId,
                                        @Param("deletedBy") String deletedBy);
}
```

- [ ] **Step 2: Verify compile**

Run from project root:
```bash
./mvnw -pl favourite-service -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java
git commit -m "feat(favourite-service): FavouriteRepository (5 derived queries + 2 soft-delete UPDATE)"
```

---

## Task 6: DTOs + Mapper

**Files:**
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/dto/request/FavouriteCreateRequest.java`
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/dto/response/FavouriteResponse.java`
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/mapper/FavouriteMapper.java`

**Interfaces:**
- Produces: 2 records + 1 mapper bean. Used by service layer (Task 7) and controller (Task 8).

- [ ] **Step 1: Create FavouriteCreateRequest record**

Create `favourite-service/src/main/java/com/shop/favouriteservice/dto/request/FavouriteCreateRequest.java`:

```java
package com.shop.favouriteservice.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record FavouriteCreateRequest(
        @NotNull(message = "productId must not be null")
        UUID productId
) {}
```

- [ ] **Step 2: Create FavouriteResponse record**

Create `favourite-service/src/main/java/com/shop/favouriteservice/dto/response/FavouriteResponse.java`:

```java
package com.shop.favouriteservice.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record FavouriteResponse(
        UUID id,
        UUID userId,
        UUID productId,
        Instant createdAt
) {}
```

(The `@Builder` lets the mapper use `FavouriteResponse.builder()...build()` if preferred; manual `new FavouriteResponse(...)` also works.)

- [ ] **Step 3: Create FavouriteMapper**

Create `favourite-service/src/main/java/com/shop/favouriteservice/mapper/FavouriteMapper.java`:

```java
package com.shop.favouriteservice.mapper;

import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class FavouriteMapper {

    private final ModelMapper modelMapper;

    public FavouriteMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public FavouriteResponse toResponse(Favourite favourite) {
        // Manual mapping — only 4 fields, ModelMapper overhead exceeds benefit.
        // Same approach as auth-service UserMapper.toResponse.
        return new FavouriteResponse(
                favourite.getId(),
                favourite.getUserId(),
                favourite.getProductId(),
                favourite.getCreatedAt()
        );
    }

    // No toEntity() — FavouriteServiceImpl.create() builds the entity via
    // .builder() directly (only 2 user-supplied fields + generated id).
}
```

- [ ] **Step 4: Verify compile**

Run from project root:
```bash
./mvnw -pl favourite-service -am compile
```
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add favourite-service/src/main/java/com/shop/favouriteservice/dto/ \
        favourite-service/src/main/java/com/shop/favouriteservice/mapper/
git commit -m "feat(favourite-service): DTOs (records) + FavouriteMapper"
```

---

## Task 7: FavouriteService interface + impl (TDD — tests first)

**Files:**
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/service/FavouriteService.java` (interface)
- Create: `favourite-service/src/test/java/com/shop/favouriteservice/service/impls/FavouriteServiceImplTest.java` (tests — write FIRST)
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java` (impl)

**Interfaces:**
- Consumes: `FavouriteRepository` (Task 5), `FavouriteMapper` (Task 6), `AuditorAware<String>` (from `JpaAuditingAutoConfiguration`)
- Produces: 5 service methods, all throwing `BusinessException.of(ErrorCode.X)` on error paths. Used by controller (Task 8) and unit test (this task).

- [ ] **Step 1: Write the failing test**

Create `favourite-service/src/test/java/com/shop/favouriteservice/service/impls/FavouriteServiceImplTest.java`:

```java
package com.shop.favouriteservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import com.shop.favouriteservice.mapper.FavouriteMapper;
import com.shop.favouriteservice.repository.FavouriteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FavouriteServiceImplTest {

    @Mock private FavouriteRepository repo;
    @Mock private FavouriteMapper mapper;
    @Mock private AuditorAware<String> auditorAware;

    @InjectMocks private FavouriteServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID favouriteId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private Favourite sampleFavourite() {
        // Note: no `.createdAt(...)` here — createdAt lives on AbstractMappedEntity
        // (superclass) which is NOT @SuperBuilder. Subclass @Builder only exposes
        // subclass fields. Auditing auto-fills createdAt via JpaAuditingEntityListener.
        return Favourite.builder()
                .id(favouriteId)
                .userId(userId)
                .productId(productId)
                .build();
    }

    private FavouriteResponse sampleResponse() {
        return FavouriteResponse.builder()
                .id(favouriteId)
                .userId(userId)
                .productId(productId)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void findAllByCurrentUser_returnsMappedList() {
        Favourite fav = sampleFavourite();
        when(repo.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(fav));
        when(mapper.toResponse(fav)).thenReturn(sampleResponse());

        List<FavouriteResponse> result = service.findAllByCurrentUser(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(favouriteId);
    }

    @Test
    void findById_returnsFavourite_whenOwnedByCurrentUser() {
        Favourite fav = sampleFavourite();
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.of(fav));
        when(mapper.toResponse(fav)).thenReturn(sampleResponse());

        FavouriteResponse result = service.findById(favouriteId, userId);

        assertThat(result.id()).isEqualTo(favouriteId);
    }

    @Test
    void findById_throwsNotFound_whenMissing() {
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(favouriteId, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void findById_throwsNotFound_whenOwnedByOtherUser() {
        when(repo.findByIdAndUserId(favouriteId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(favouriteId, userId))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndReturnsResponse() {
        when(repo.existsByUserIdAndProductId(userId, productId)).thenReturn(false);
        ArgumentCaptor<Favourite> captor = ArgumentCaptor.forClass(Favourite.class);
        Favourite saved = sampleFavourite();
        when(repo.save(any(Favourite.class))).thenReturn(saved);
        when(mapper.toResponse(saved)).thenReturn(sampleResponse());

        FavouriteResponse result = service.create(userId,
                new FavouriteCreateRequest(productId));

        verify(repo).save(captor.capture());
        Favourite persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(userId);
        assertThat(persisted.getProductId()).isEqualTo(productId);
        assertThat(result.id()).isEqualTo(favouriteId);
    }

    @Test
    void create_throwsConflict_whenDuplicate() {
        when(repo.existsByUserIdAndProductId(userId, productId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(userId,
                new FavouriteCreateRequest(productId)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteById_softDeletes_whenFound() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByIdAndUserId(eq(favouriteId), eq(userId), eq("alice"))).thenReturn(1);

        service.deleteById(favouriteId, userId);

        verify(repo).softDeleteByIdAndUserId(favouriteId, userId, "alice");
    }

    @Test
    void deleteById_throwsNotFound_whenNotOwned() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByIdAndUserId(eq(favouriteId), eq(userId), eq("alice"))).thenReturn(0);

        assertThatThrownBy(() -> service.deleteById(favouriteId, userId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void deleteByProductId_softDeletes_whenFound() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByUserIdAndProductId(userId, productId, "alice")).thenReturn(1);

        service.deleteByProductId(userId, productId);

        verify(repo).softDeleteByUserIdAndProductId(userId, productId, "alice");
    }

    @Test
    void deleteByProductId_throwsNotFound_whenMissing() {
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));
        when(repo.softDeleteByUserIdAndProductId(userId, productId, "alice")).thenReturn(0);

        assertThatThrownBy(() -> service.deleteByProductId(userId, productId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }
}
```

- [ ] **Step 2: Run test to verify it fails to compile (interface not yet defined)**

Run from project root:
```bash
./mvnw -pl favourite-service -am test -Dtest=FavouriteServiceImplTest
```
Expected: `COMPILATION FAILURE` — `cannot find symbol: FavouriteServiceImpl` (and `FavouriteService` interface). This is the failing-test gate.

- [ ] **Step 3: Write the interface**

Create `favourite-service/src/main/java/com/shop/favouriteservice/service/FavouriteService.java`:

```java
package com.shop.favouriteservice.service;

import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;

import java.util.List;
import java.util.UUID;

public interface FavouriteService {

    List<FavouriteResponse> findAllByCurrentUser(UUID userId);

    FavouriteResponse findById(UUID id, UUID userId);

    FavouriteResponse create(UUID userId, FavouriteCreateRequest request);

    void deleteById(UUID id, UUID userId);

    void deleteByProductId(UUID userId, UUID productId);
}
```

- [ ] **Step 4: Write the implementation**

Create `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java`:

```java
package com.shop.favouriteservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.entity.Favourite;
import com.shop.favouriteservice.mapper.FavouriteMapper;
import com.shop.favouriteservice.repository.FavouriteRepository;
import com.shop.favouriteservice.service.FavouriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FavouriteServiceImpl implements FavouriteService {

    private final FavouriteRepository repo;
    private final FavouriteMapper mapper;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public List<FavouriteResponse> findAllByCurrentUser(UUID userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FavouriteResponse findById(UUID id, UUID userId) {
        return mapper.toResponse(findOwnedOrThrow(id, userId));
    }

    @Override
    @Transactional
    public FavouriteResponse create(UUID userId, FavouriteCreateRequest request) {
        if (repo.existsByUserIdAndProductId(userId, request.productId())) {
            throw BusinessException.of(ErrorCode.FAVOURITE_ALREADY_EXISTS);
        }
        Favourite favourite = Favourite.builder()
                .userId(userId)
                .productId(request.productId())
                .build();
        Favourite saved = repo.save(favourite);
        return mapper.toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteById(UUID id, UUID userId) {
        String actor = auditorAware.getCurrentAuditor().orElse("system");
        int affected = repo.softDeleteByIdAndUserId(id, userId, actor);
        if (affected == 0) {
            throw BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id);
        }
    }

    @Override
    @Transactional
    public void deleteByProductId(UUID userId, UUID productId) {
        String actor = auditorAware.getCurrentAuditor().orElse("system");
        int affected = repo.softDeleteByUserIdAndProductId(userId, productId, actor);
        if (affected == 0) {
            throw BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, productId);
        }
    }

    private Favourite findOwnedOrThrow(UUID id, UUID userId) {
        return repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run from project root:
```bash
./mvnw -pl favourite-service -am test -Dtest=FavouriteServiceImplTest
```
Expected: `BUILD SUCCESS` — 10 tests passed.

- [ ] **Step 6: Commit**

```bash
git add favourite-service/src/main/java/com/shop/favouriteservice/service/ \
        favourite-service/src/test/java/com/shop/favouriteservice/service/
git commit -m "feat(favourite-service): FavouriteService interface + impl + 10 unit tests"
```

---

## Task 8: FavouriteController (TDD — tests first)

**Files:**
- Create: `favourite-service/src/test/java/com/shop/favouriteservice/controller/FavouriteControllerTest.java` (tests — write FIRST)
- Create: `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` (controller)

**Interfaces:**
- Consumes: `FavouriteService` interface (Task 7), `ApiPaths.FAVOURITES` constant (common-core), `AuthenticatedUser.requireCurrent()` static helper (common-security)
- Produces: REST endpoints — 5 endpoints under `/api/v1/favourites`. Used by clients via API gateway.

- [ ] **Step 1: Write the failing test**

Create `favourite-service/src/test/java/com/shop/favouriteservice/controller/FavouriteControllerTest.java`:

```java
package com.shop.favouriteservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.common.spring.web.exception.ApiExceptionHandler;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.service.FavouriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.webmvc.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FavouriteController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandler.class)
class FavouriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private FavouriteService favouriteService;

    private final UUID userId = UUID.randomUUID();
    private final UUID favouriteId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    private FavouriteResponse sampleResponse() {
        return FavouriteResponse.builder()
                .id(favouriteId)
                .userId(userId)
                .productId(productId)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void findAll_returns200WithEnvelope() throws Exception {
        when(favouriteService.findAllByCurrentUser(any(UUID.class))).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/favourites"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(favouriteId.toString()))
                .andExpect(jsonPath("$.data[0].productId").value(productId.toString()));
    }

    @Test
    void findById_returns200WithEnvelope() throws Exception {
        when(favouriteService.findById(eq(favouriteId), any(UUID.class))).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/favourites/" + favouriteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(favouriteId.toString()));
    }

    @Test
    void create_returns200WithCreatedEnvelope() throws Exception {
        when(favouriteService.create(any(UUID.class), any(FavouriteCreateRequest.class)))
                .thenReturn(sampleResponse());

        FavouriteCreateRequest req = new FavouriteCreateRequest(productId);
        mockMvc.perform(post("/api/v1/favourites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Favourite added successfully"))
                .andExpect(jsonPath("$.data.id").value(favouriteId.toString()));
    }

    @Test
    void create_returns400_whenProductIdMissing() throws Exception {
        mockMvc.perform(post("/api/v1/favourites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("productId")));
    }

    @Test
    void deleteById_returns200WithMessageEnvelope() throws Exception {
        mockMvc.perform(delete("/api/v1/favourites/" + favouriteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Favourite removed successfully"));

        verify(favouriteService).deleteById(eq(favouriteId), any(UUID.class));
    }

    @Test
    void deleteByProduct_returns200WithMessageEnvelope() throws Exception {
        mockMvc.perform(delete("/api/v1/favourites/by-product/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Favourite removed successfully"));

        verify(favouriteService).deleteByProductId(any(UUID.class), eq(productId));
    }
}
```

- [ ] **Step 2: Run test to verify it fails to compile (controller not yet defined)**

Run from project root:
```bash
./mvnw -pl favourite-service -am test -Dtest=FavouriteControllerTest
```
Expected: `COMPILATION FAILURE` — `cannot find symbol: class FavouriteController`.

- [ ] **Step 3: Write the controller**

Create `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java`:

```java
package com.shop.favouriteservice.controller;

import com.shop.common.core.constants.ApiPaths;
import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.security.jwt.AuthenticatedUser;
import com.shop.favouriteservice.dto.request.FavouriteCreateRequest;
import com.shop.favouriteservice.dto.response.FavouriteResponse;
import com.shop.favouriteservice.service.FavouriteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.FAVOURITES)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class FavouriteController {

    private final FavouriteService service;

    @GetMapping
    public ApiResponse<List<FavouriteResponse>> findAll() {
        return ApiResponse.ok(service.findAllByCurrentUser(currentUserId()));
    }

    @GetMapping("/{favouriteId}")
    public ApiResponse<FavouriteResponse> findById(@PathVariable UUID favouriteId) {
        return ApiResponse.ok(service.findById(favouriteId, currentUserId()));
    }

    @PostMapping
    public ApiResponse<FavouriteResponse> create(@Valid @RequestBody FavouriteCreateRequest request) {
        return ApiResponse.ok(service.create(currentUserId(), request),
                "Favourite added successfully");
    }

    @DeleteMapping("/{favouriteId}")
    public ApiResponse<Void> deleteById(@PathVariable UUID favouriteId) {
        service.deleteById(favouriteId, currentUserId());
        return ApiResponse.message("Favourite removed successfully");
    }

    @DeleteMapping("/by-product/{productId}")
    public ApiResponse<Void> deleteByProduct(@PathVariable UUID productId) {
        service.deleteByProductId(currentUserId(), productId);
        return ApiResponse.message("Favourite removed successfully");
    }

    private static UUID currentUserId() {
        String sub = AuthenticatedUser.requireCurrent().id();
        try {
            return UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            // Defensive — valid Keycloak JWT subjects are always UUIDs. Prevents 500
            // only if a non-UUID subject is ever introduced.
            throw BusinessException.unauthorized("favourite.user.subject.malformed");
        }
    }
}
```

- [ ] **Step 4: Run controller tests**

Run from project root:
```bash
./mvnw -pl favourite-service -am test -Dtest=FavouriteControllerTest
```
Expected: `BUILD SUCCESS` — 6 tests passed.

- [ ] **Step 5: Commit**

```bash
git add favourite-service/src/main/java/com/shop/favouriteservice/controller/ \
        favourite-service/src/test/java/com/shop/favouriteservice/controller/
git commit -m "feat(favourite-service): FavouriteController + 6 MVC slice tests"
```

---

## Task 9: FavouriteRepositoryTest — JPA slice with Testcontainers Postgres

**Files:**
- Create: `favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java`

**Interfaces:**
- Consumes: `FavouriteRepository` (Task 5), `Favourite` entity (Task 4), Liquibase schema (Task 3)
- Produces: 3 integration assertions on `@SQLRestriction`, soft-delete UPDATE, and partial unique constraint behavior

- [ ] **Step 1: Write the failing test class**

Create `favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java`:

```java
package com.shop.favouriteservice.repository;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import com.shop.favouriteservice.entity.Favourite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors product-service's {@code ProductRepositoryTest} pattern exactly so the
 * fleet stays consistent: {@code @DataJpaTest} with {@code Replace.NONE}, explicit
 * imports for {@code JpaAuditingAutoConfiguration} + {@code LiquibaseAutoConfiguration},
 * a static {@code @Container} + {@code @DynamicPropertySource} (NOT
 * {@code @ServiceConnection}).
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class
})
class FavouriteRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("favourite_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log",
                () -> "classpath:db/changelog/db.changelog-master.yaml");
        // Liquibase owns the schema; Hibernate must NOT validate before Liquibase runs.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TestEntityManager em;

    @Autowired
    private FavouriteRepository repo;

    private static final UUID alice = UUID.randomUUID();

    private Favourite persistFavourite(UUID userId, UUID productId) {
        Favourite fav = Favourite.builder().userId(userId).productId(productId).build();
        return em.persistAndFlush(fav);
    }

    @Test
    void findByUserId_filtersSoftDeleted() {
        Favourite active = persistFavourite(alice, UUID.randomUUID());
        Favourite tombstoned = persistFavourite(alice, UUID.randomUUID());
        em.getEntityManager()
                .createQuery("UPDATE Favourite f SET f.deleted = true, f.deletedAt = CURRENT_TIMESTAMP WHERE f.id = :id")
                .setParameter("id", tombstoned.getId())
                .executeUpdate();

        List<Favourite> result = repo.findByUserIdOrderByCreatedAtDesc(alice);

        assertThat(result).extracting(Favourite::getId).containsExactly(active.getId());
    }

    @Test
    void softDeleteByUserIdAndProductId_keepsRowAndSetsFlags() {
        Favourite fav = persistFavourite(alice, UUID.randomUUID());
        UUID productId = fav.getProductId();

        int affected = repo.softDeleteByUserIdAndProductId(alice, productId, "alice");

        assertThat(affected).isEqualTo(1);
        em.clear();
        Favourite raw = em.getEntityManager()
                .createQuery("SELECT f FROM Favourite f WHERE f.id = :id", Favourite.class)
                .setParameter("id", fav.getId())
                .getSingleResult();
        assertThat(raw.isDeleted()).isTrue();
        assertThat(raw.getDeletedAt()).isNotNull();
        assertThat(raw.getDeletedBy()).isEqualTo("alice");
        // Repository finder still skips soft-deleted rows.
        assertThat(repo.findByUserIdOrderByCreatedAtDesc(alice)).isEmpty();
    }

    @Test
    void partialUniqueConstraint_allowsReAddingAfterSoftDelete() {
        UUID productId = UUID.randomUUID();
        persistFavourite(alice, productId);
        int deleted = repo.softDeleteByUserIdAndProductId(alice, productId, "alice");
        assertThat(deleted).isEqualTo(1);
        em.clear();

        // After soft-delete, the partial unique index releases (user, product), so the
        // same pair can be re-inserted.
        Favourite re = persistFavourite(alice, productId);
        assertThat(re.getId()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it passes (will pass — verifies behaviour, not implementation)**

Run from project root (downloads Postgres image on first run, ~30 seconds; subsequent runs reuse):
```bash
./mvnw -pl favourite-service -am test -Dtest=FavouriteRepositoryTest
```
Expected: `BUILD SUCCESS` — 3 tests passed. (This task establishes the contract that the repo meets the spec's soft-delete + partial unique guarantees.)

- [ ] **Step 3: Commit**

```bash
git add favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java
git commit -m "test(favourite-service): FavouriteRepositoryTest — soft-delete filter + UPDATE + partial unique"
```

---

## Task 10: docker-compose.yml — verify + extend favourite-service container

**Files:**
- Verify (no edit expected): `docker-compose.yml` already has a `favourite-service:` block at line 372 (verified at planning time)
- Verify (no edit expected): `docker/postgres/init/create-all-databases.sql` already includes `CREATE DATABASE favouriteservice;`

**Interfaces:**
- Consumes: `docker-compose.yml` structure (existing pattern from `product-service` block), env vars `POSTGRES_USER`, `POSTGRES_PASSWORD`
- Produces: `docker compose up -d postgres favourite-service` boots the service; `curl localhost:8081/actuator/health` returns `UP`.

> **Why a Task 10 at all if the block exists:** catch any drift between the existing block and what the spec demands (POSTGRES_USER fallback, depends_on healthy condition, network name, port 8081). Drift detected → fix.

- [ ] **Step 1: Compare existing `favourite-service` block against the required schema**

Open `docker-compose.yml` around line 372. Verify the existing block matches this required shape (only `build` directives may legitimately differ — verify only env, ports, depends_on, networks):

```yaml
  favourite-service:
    image: favourite-service:latest
    container_name: favourite-service
    restart: unless-stopped
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/favouriteservice
      POSTGRES_USER: ${POSTGRES_USER:-admin}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-admin}
      SERVER_PORT: 8081
    ports:
      - "8081:8081"
    depends_on:
      postgres:
        condition: service_healthy
    networks:
      - shop-network
```

If anything diverges, fix it inline. If the existing block uses `image:` (pre-built) rather than `build:`, that's fine — Jib builds happen at `mvn package`, not at compose time.

- [ ] **Step 2: Validate docker-compose syntax**

Run from project root:
```bash
docker compose config --quiet
```
Expected: exit code 0 (no output). If errors, fix YAML indentation (must match existing blocks exactly).

- [ ] **Step 3: Build Docker image and start container**

```bash
./mvnw -pl favourite-service -am clean package -DskipTests
docker compose up -d postgres favourite-service
```
Expected: favourite-service container starts cleanly. `docker compose ps` shows it `healthy` after ~10s.

- [ ] **Step 4: Smoke test**

```bash
curl -s http://localhost:8081/actuator/health
```
Expected: `{"status":"UP"}` (Liquibase has created the `favourites` table; JPA validated schema).

- [ ] **Step 5: Commit (only if Step 1 required edits)**

If you modified `docker-compose.yml`:
```bash
git add docker-compose.yml
git commit -m "chore(docker): align favourite-service compose block with spec"
```
If no edit was needed, skip this step — no commit necessary.

---

## Task 11: Full reactor build + all tests green

**Files:** none (verification only)

- [ ] **Step 1: Full reactor build with all tests**

Run from project root:
```bash
./mvnw clean test
```
Expected: `BUILD SUCCESS`. All modules green:
- `utils/common-core` (existing tests still pass — additive ErrorCode + i18n change)
- `utils/common-spring`
- `auth-service` (37 existing tests still pass)
- `product-service` (existing tests still pass)
- `favourite-service` (NEW: 10 service + 3 repo + 6 controller = 19 tests)

- [ ] **Step 2: Confirm favourite-service test counts**

```bash
./mvnw -pl favourite-service test 2>&1 | grep -E "Tests run:|BUILD"
```
Expected: `Tests run: 19, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.

- [ ] **Step 3: Verify spec-mapping (cross-check spec → implemented features)**

Confirm each spec section has a corresponding test or implementation:
- §3 entity → Task 4 ✓
- §4 endpoints → Task 8 (controller) ✓
- §4.1 auth pattern → Task 8 (`AuthenticatedUser.requireCurrent()`) ✓
- §5 service methods → Task 7 (10 unit tests cover all 5 methods) ✓
- §6.1 no cross-service call → Task 5 (no RestClient dep) ✓
- §6.2 no Redis → Task 2 (no redis dep in pom) ✓
- §11 error mapping → Task 7 unit tests assert `BusinessException` thrown ✓
- §12.2 ~19 tests → confirmed in Step 2 ✓

- [ ] **Step 4: Final commit (if any uncommitted changes)**

If Step 1 generated incidental files (e.g. `.flattened-pom.xml` updates), commit them:
```bash
git status
# If only .flattened-pom.xml changed (auto-generated by flatten-maven-plugin):
git add favourite-service/.flattened-pom.xml
git commit -m "chore(favourite-service): regenerated flattened pom"
```

---

## Self-Review Checklist (verify before declaring done)

- [ ] Each task's commit message follows `feat(...)` / `test(...)` / `chore(...)` convention
- [ ] No `TBD` / `TODO` / "implement later" anywhere in the plan
- [ ] Each code block is self-contained — no references to "similar to Task N" without repeating
- [ ] Method signatures match across tasks (e.g. `softDeleteByIdAndUserId(UUID, UUID, String)` in Task 5 repo ≡ same call in Task 7 service ≡ matched by `eq()` in Task 7 test)
- [ ] Test counts match the spec §12.2 plan (10 + 3 + 6 = 19)
- [ ] Boot 4 test stack conventions applied (`@MockitoBean`, `@DataJpaTest` from new package, `TestEntityManager @Autowired` field)
- [ ] All run commands use `./mvnw` (project wrapper) — not system `mvn`
- [ ] Spec section coverage: every spec section (§1–§18) maps to at least one task

---

## Definition of Done

- [ ] All 11 Tasks committed
- [ ] `./mvnw clean test` → `BUILD SUCCESS` across full reactor
- [ ] `favourite-service` reports 19 tests passing
- [ ] `docker compose up -d postgres favourite-service` boots cleanly
- [ ] `curl http://localhost:8081/actuator/health` returns `{"status":"UP"}`
- [ ] Spec self-review checklist above passes
- [ ] No file under `favourite-service/` references Kafka, Redis, common-keycloak, common-storage, RestClient, or Resilience4j
