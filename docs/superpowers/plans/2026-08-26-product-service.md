# Product Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `product-service` microservice with Product/Category/Brand CRUD, Redis cache, Kafka events via Transactional Outbox pattern, while upgrading `common-core`/`common-spring`/`common-security` to support `@MappedSuperclass` audit, `AuditorAware`, and method-specific public endpoints.

**Architecture:** Spring Boot 4.1.1 microservice (`com.shop.productservice`) → PostgreSQL via Spring Data JPA + Liquibase, Redis 7 via Spring Cache (`@Cacheable` / `@CacheEvict` cache-aside), Kafka producer via Transactional Outbox + `@Scheduled` relay. Loose coupling: search-service (future) consumes Kafka events. Auth via Keycloak JWT + `@PreAuthorize`.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Redis 7, Apache Kafka, Spring Security Resource Server (JWT), ModelMapper 3.2.6, Lombok, JUnit 5 + Mockito + AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-26-product-service-design.md`](../specs/2026-08-26-product-service-design.md)

---

## Global Constraints

- **Java 25** (per project `.java-version` / parent pom)
- **Spring Boot 4.1.1** (parent pom `spring-boot-starter-parent.version`)
- **Package root:** `com.shop.*`
- **No per-service `SecurityConfig`**: `common-security` auto-configures `SecurityFilterChain` (`@ConditionalOnMissingBean`). Customise via `shop.security.public-paths` (EndpointRule format, renamed from legacy `public-endpoints`).
- **`open-in-view: false`** in all `application.yml` (no transaction-in-view)
- **ModelMapper**: Aligned with auth-service and fleet convention (`UserMapper`/`RoleMapper` `@Component` injecting `ModelMapper`). MapStruct was rejected to maintain pattern consistency. `common-spring/ModelMapperAutoConfiguration` preconfigures STRICT matching and skip-null behavior.
- **Liquibase** (not Flyway), changelogs in `src/main/resources/db/changelog/`
- **All endpoints** wrap responses in `ApiResponse<T>` (from `common-core/viewmodel`)
- **No new common modules**  -  only modify `common-core`, `common-spring`, `common-security`, `common-kafka`
- **Cache key convention:** `product::{id}`, `productBySlug::{slug}`; TTL 600s; `cache-null-values: false`
- **Kafka topic:** `shop.product.lifecycle.v1`; payload: `{eventId, eventType, occurredAt, productId, slug, status}`. **Configure via `shop.kafka.*`** (common-kafka `KafkaProperties`), do not use `spring.kafka.*`.
- **Outbox publisher**: same `@Transactional` boundary as the entity write
- **Soft delete** following auth-service pattern: entity `extends AbstractMappedEntity` (which extends `SoftDeletable`) + `@SQLRestriction("deleted = false")`, deleted via `markDeleted(actor)` (actor from `AuditorAware`, falling back to "system"). Partial unique indexes `WHERE deleted = false`. Do not use the method suffix `*AndDeletedFalse` in repositories.
- **Audit fields** populated by `AuditorAware` (from `common-spring`), returns `Optional.of(auth.getName())` when authenticated (non-null + non-`anonymousUser`) else `Optional.of("system")` (Spring Data 4.x  -  `AuditorAware.getCurrentAuditor()` returns `Optional<T>`, NOT `T`)
- **Exceptions** use `BusinessException.of(...)` / `notFound("key")` / `conflict("key")` factories (private constructor, avoid `new BusinessException(...)`); messages represent i18n keys.
- **Boot 4 test stack:** `@MockitoBean` (not `@MockBean`); controller slices use the new package `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`); controller tests specify `@AutoConfigureMockMvc(addFilters = false)` (authorization is tested in integration tests rather than slices); **JPA slices in Boot 4 split into 2 distinct artifacts** (verified via jar inspection in Task 1 implementation): `@DataJpaTest` in `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`); `TestEntityManager` in `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`). Boot 4 also does not allow `TestEntityManager` injection via method parameters; it must be `@Autowired` as a field. `@DataJpaTest` requires `@Import(LiquibaseAutoConfiguration.class)` (slices do not run Liquibase automatically); Spring Boot auto-wires `TransactionAwareCacheManagerProxy` for Redis, allowing `@Cacheable` + `@Transactional(readOnly=true)` without additional configuration.

---

## File Structure

### Modified common modules

| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java` | **CREATE** - `@MappedSuperclass` auditing (createdAt/updatedAt/createdBy/updatedBy) **`extends SoftDeletable`** (existing class providing `markDeleted()`/`markRestored()`) |
| `utils/common-core/src/main/java/com/shop/common/core/data/SoftDeletable.java` | unchanged (already exists  -  class, NOT interface) |
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | **MODIFY** - add `PRODUCT_SLUG_EXISTS (PRD-2004)`, `PRODUCT_SKU_EXISTS (PRD-2005)`, `BRAND_NOT_FOUND (PRD-2006)`, `BRAND_SLUG_EXISTS (PRD-2007)`, `CATEGORY_SLUG_EXISTS (PRD-2008)` |
| `utils/common-spring/src/main/resources/messages/messages_en.properties` | **MODIFY** - add product domain i18n keys |
| `utils/common-spring/src/main/resources/messages/messages_vi.properties` | **MODIFY** - add product domain localized keys |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java` | **CREATE**  -  wires `AuditorAware` + `@EnableJpaAuditing` |
| `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **MODIFY**  -  add `JpaAuditingAutoConfiguration` |
| `utils/common-spring/pom.xml` | **MODIFY**  -  add `spring-boot-starter-data-jpa` (for `@EntityListeners`) |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` | **MODIFY** - retain record format; update `List<String> publicEndpoints` -> `List<EndpointRule> publicPaths` (rename field and add nested record `EndpointRule(HttpMethod method, String path)`); retain `resolvedPublicPaths()` and `PlatformDefaults` |
| `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java` | **MODIFY**  -  loop `EndpointRule` method-aware + platform defaults + `anyRequest().authenticated()` |
| `auth-service/src/main/resources/application.yml` | **MODIFY** - rename `public-endpoints` -> `public-paths`, convert to `EndpointRule` format (contain only `- path: /api/v1/auth/**`, omit actuator entries covered by platform defaults) |
| `gateway-service/src/main/resources/application.yml` | **MODIFY** - rename `public-endpoints` -> `public-paths`, convert to `EndpointRule` format (following same pattern) |

### New product-service files

| File | Responsibility |
|---|---|
| `product-service/src/main/java/com/shop/productservice/ProductServiceApplication.java` | `@SpringBootApplication` entrypoint |
| `product-service/src/main/java/com/shop/productservice/config/CacheConfig.java` | `@EnableCaching` + `RedisCacheManager` TTL per cache name |
| `product-service/src/main/java/com/shop/productservice/entity/Product.java` | Product JPA entity |
| `product-service/src/main/java/com/shop/productservice/entity/Category.java` | Category tree entity |
| `product-service/src/main/java/com/shop/productservice/entity/Brand.java` | Brand entity |
| `product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java` | Outbox event entity |
| `product-service/src/main/java/com/shop/productservice/entity/ProductStatus.java` | enum DRAFT / ACTIVE / OUT_OF_STOCK / DISCONTINUED |
| `product-service/src/main/java/com/shop/productservice/entity/OutboxStatus.java` | enum PENDING / SENT / FAILED |
| `product-service/src/main/java/com/shop/productservice/repository/ProductRepository.java` | JPA repo with custom queries |
| `product-service/src/main/java/com/shop/productservice/repository/CategoryRepository.java` | JPA repo |
| `product-service/src/main/java/com/shop/productservice/repository/BrandRepository.java` | JPA repo |
| `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java` | Outbox repo |
| `product-service/src/main/java/com/shop/productservice/dto/request/ProductCreateRequest.java` | record with Bean Validation |
| `product-service/src/main/java/com/shop/productservice/dto/request/ProductUpdateRequest.java` | record (all optional) |
| `product-service/src/main/java/com/shop/productservice/dto/request/CategoryCreateRequest.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/request/CategoryUpdateRequest.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/request/BrandCreateRequest.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/request/BrandUpdateRequest.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/response/ProductSummaryResponse.java` | list DTO (no relations) |
| `product-service/src/main/java/com/shop/productservice/dto/response/ProductDetailResponse.java` | detail DTO (with categoryTitle, brandName) |
| `product-service/src/main/java/com/shop/productservice/dto/response/CategoryResponse.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/response/CategoryTreeResponse.java` | recursive record for tree endpoint |
| `product-service/src/main/java/com/shop/productservice/dto/response/BrandResponse.java` | record |
| `product-service/src/main/java/com/shop/productservice/dto/ProductFilter.java` | optional filter params for list |
| `product-service/src/main/java/com/shop/productservice/mapper/ProductMapper.java` | ModelMapper `@Component` (toSummary + toDetail + partialUpdate) |
| `product-service/src/main/java/com/shop/productservice/mapper/CategoryMapper.java` | ModelMapper `@Component` (toResponse + toTreeResponse) |
| `product-service/src/main/java/com/shop/productservice/mapper/BrandMapper.java` | ModelMapper `@Component` |
| `product-service/src/main/java/com/shop/productservice/service/ProductService.java` | interface |
| `product-service/src/main/java/com/shop/productservice/service/impls/ProductServiceImpl.java` | impl with `@Cacheable`/`@CachePut`/`@CacheEvict` |
| `product-service/src/main/java/com/shop/productservice/service/CategoryService.java` | interface |
| `product-service/src/main/java/com/shop/productservice/service/impls/CategoryServiceImpl.java` | impl with tree building |
| `product-service/src/main/java/com/shop/productservice/service/BrandService.java` | interface |
| `product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java` | impl |
| `product-service/src/main/java/com/shop/productservice/service/ProductEventPublisher.java` | writes OutboxEvent in same TX |
| `product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java` | `@Scheduled` poller using `KafkaMessagePublisher` |
| `product-service/src/main/java/com/shop/productservice/service/ProductMetrics.java` | Micrometer metrics |
| `product-service/src/main/java/com/shop/productservice/controller/ProductController.java` | REST endpoints |
| `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java` | REST endpoints (with `/tree`) |
| `product-service/src/main/java/com/shop/productservice/controller/BrandController.java` | REST endpoints |
| `product-service/src/main/resources/application.yml` | config (datasource, redis, kafka, security) |
| `product-service/src/main/resources/db/changelog/db.changelog-master.yaml` | Liquibase master |
| `product-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 4 tables + indexes |

### Test files

- `product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java`  -  `@DataJpaTest` + Testcontainers
- `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplTest.java`  -  unit test
- `product-service/src/test/java/com/shop/productservice/service/impls/CategoryServiceImplTest.java`  -  unit test (findTree)
- `product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java`  -  `@WebMvcTest` with `@WithMockUser`
- `product-service/src/test/java/com/shop/productservice/service/OutboxRelayIntegrationTest.java`  -  `@SpringBootTest` + Testcontainers Kafka

### Compose

- `docker-compose.yml`: Do not create a separate postgres-product container (repo shares one Postgres instance with initialization scripts); update `product-service` env: SPRING_DATA_REDIS_HOST, SHOP_KAFKA_BOOTSTRAP_SERVERS, SERVER_PORT; depends_on redis + kafka.

---

## Phase 0  -  Common upgrades

### Task 1: Add `AbstractMappedEntity` to `common-core` + product ErrorCodes + i18n keys

**Files:**
- Create: `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java`
- Modify: `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` (add PRD-2004..2008)
- Modify: `utils/common-spring/src/main/resources/messages/messages_en.properties` + `messages_vi.properties`
- Create: `utils/common-core/src/test/java/com/shop/common/core/data/AbstractMappedEntityTest.java`

**Interfaces:**
- Consumes: JPA `AuditingEntityListener`, `AuditorAware<String>` (added in Task 2), `SoftDeletable` (existing `@MappedSuperclass` with `markDeleted(String)`/`markRestored()`)
- Produces: `AbstractMappedEntity` abstract class (4 audit fields) **`extends SoftDeletable`**; ErrorCode PRD domain codes; i18n keys

- [ ] **Step 1: Write failing test** (Boot 4: `@DataJpaTest` in new package; `TestEntityManager` must be `@Autowired` as a field, not injected via method parameters)

```java
package com.shop.common.core.data;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class AbstractMappedEntityTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableJpaAuditing
    static class AuditingConfig {}

    @Entity
    static class TestEntity extends AbstractMappedEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        public Long getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Autowired TestEntityManager em;

    @Test
    void persistsWithAuditAndSoftDeleteFields() {
        TestEntity entity = new TestEntity();
        entity.setName("test");
        em.persistAndFlush(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.isDeleted()).isFalse();
    }

    @Test
    void markDeletedSetsFlags() {
        TestEntity entity = new TestEntity();
        entity.setName("test");
        em.persistAndFlush(entity);

        entity.markDeleted("alice");

        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();
        assertThat(entity.getDeletedBy()).isEqualTo("alice");
    }
}
```

> **Boot 4 note (verified Task 1 implementation):** Spring Boot 4 splits **both WebMvc and JPA** into dedicated artifacts:
> - `@DataJpaTest` resides in `org.springframework.boot.data.jpa.test.autoconfigure.*` -> artifact `spring-boot-data-jpa-test`
> - `TestEntityManager` resides in `org.springframework.boot.jpa.test.autoconfigure.*` -> artifact `spring-boot-jpa-test`
> - Older plan iterations assumed JPA slices remained in the old package, which was corrected in Task 1. Common-core pom now adds four dependencies: `spring-boot-starter-data-jpa` (compile, for `AuditingEntityListener`), `spring-boot-data-jpa-test`/`spring-boot-jpa-test`/`com.h2database:h2` (test). `TestEntityManager` must be `@Autowired` as a field; the JUnit 5 extension in Boot 4 does not resolve method-parameter `TestEntityManager`.

- [ ] **Step 2: Run test to verify it fails (compilation error expected since class doesn't exist)**

Run from `utils/common-core`: `./mvnw test -Dtest=AbstractMappedEntityTest`
Expected: compilation error  -  `cannot find symbol: AbstractMappedEntity`

- [ ] **Step 3: Implement `AbstractMappedEntity`**

```java
package com.shop.common.core.data;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AbstractMappedEntity extends SoftDeletable {

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 100)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    public Instant getCreatedAt()  { return createdAt; }
    public Instant getUpdatedAt()  { return updatedAt; }
    public String  getCreatedBy()  { return createdBy; }
    public String  getUpdatedBy()  { return updatedBy; }
}
```

> `extends SoftDeletable`: entities only extend one base class (Java single inheritance), consolidating both auditing and soft-delete state. `SoftDeletable` already defines `deleted/deletedAt/deletedBy` (deleted_by VARCHAR(255)) along with `markDeleted()`/`markRestored()`.
>
> **This pattern is new compared to the legacy auth-service.** `auth-service/User.java:32` directly extends `SoftDeletable` without audit fields, while `auth-service/Role.java` omits soft-delete and audit fields. Migrating auth-service to `AbstractMappedEntity` is tracked in [`ROADMAP §8.1`](docs/ROADMAP.md) as a follow-up, requiring Liquibase changeset 003 for `users` (4 audit columns).

- [ ] **Step 4: Add ErrorCodes and i18n keys**

`ErrorCode.java`: add to block `// ---- Product domain ----`:

```java
PRODUCT_NOT_FOUND("PRD-2001", "product.not.found", HttpStatus.NOT_FOUND),        // existing
PRODUCT_NAME_EXISTS("PRD-2002", "product.name.exists", HttpStatus.CONFLICT),     // existing
CATEGORY_NOT_FOUND("PRD-2003", "category.not.found", HttpStatus.NOT_FOUND),      // existing
PRODUCT_SLUG_EXISTS("PRD-2004", "product.slug.exists", HttpStatus.CONFLICT),     // NEW
PRODUCT_SKU_EXISTS("PRD-2005", "product.sku.exists", HttpStatus.CONFLICT),       // NEW
BRAND_NOT_FOUND("PRD-2006", "brand.not.found", HttpStatus.NOT_FOUND),            // NEW
BRAND_SLUG_EXISTS("PRD-2007", "brand.slug.exists", HttpStatus.CONFLICT),         // NEW
CATEGORY_SLUG_EXISTS("PRD-2008", "category.slug.exists", HttpStatus.CONFLICT);   // NEW
```

`messages_en.properties`: add product domain keys:

```properties
product.not.found=Product {0} not found
product.slug.exists=Product slug already exists
product.sku.exists=Product sku already exists
category.not.found=Category {0} not found
category.slug.exists=Category slug already exists
brand.not.found=Brand {0} not found
brand.slug.exists=Brand slug already exists
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl utils/common-core -Dtest=AbstractMappedEntityTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java \
        utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java \
        utils/common-spring/src/main/resources/messages/messages_en.properties \
        utils/common-spring/src/main/resources/messages/messages_vi.properties \
        utils/common-core/src/test/java/com/shop/common/core/data/AbstractMappedEntityTest.java
git commit -m "feat(common-core): AbstractMappedEntity (audit + soft-delete) + product ErrorCodes + i18n keys"
```

---

### Task 2: Add `JpaAuditingAutoConfiguration` to `common-spring`

**Files:**
- Create: `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java`
- Modify: `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `utils/common-spring/pom.xml` (add `spring-boot-starter-data-jpa`)
- Create: `utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `AuditingEntityListener` (from `spring-data-jpa`; note that `AuditingHandler` was removed in Spring Data 4.x), Spring Security `Authentication`
- Produces: `AuditorAware<String>` bean (returns `Optional<String>`); activates `@EnableJpaAuditing` via nested config (gated on `EntityManagerFactory` bean presence to prevent "JPA metamodel must not be empty" errors in JPA-less test contexts)

- [ ] **Step 1: Modify `common-spring/pom.xml` to add JPA dep**

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
```

- [ ] **Step 2: Write the failing test**

```java
package com.shop.common.spring.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JpaAuditingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JpaAuditingAutoConfiguration.class));

    @Test
    void registersAuditorAwareBean() {
        contextRunner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AuditorAware.class);
        });
    }

    @Test
    void auditorReturnsUsernameWhenAuthenticated() {
        contextRunner.run(ctx -> {
            AuditorAware<String> auditor = ctx.getBean(AuditorAware.class);
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice", "n/a",
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            assertThat(auditor.getCurrentAuditor()).contains("alice");
            SecurityContextHolder.clearContext();
        });
    }

    @Test
    void auditorReturnsSystemWhenAnonymous() {
        contextRunner.run(ctx -> {
            AuditorAware<String> auditor = ctx.getBean(AuditorAware.class);
            assertThat(auditor.getCurrentAuditor()).contains("system");
        });
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -pl utils/common-spring -Dtest=JpaAuditingAutoConfigurationTest`
Expected: compilation error  -  `JpaAuditingAutoConfiguration` not found

- [ ] **Step 4: Implement `JpaAuditingAutoConfiguration`**

```java
package com.shop.common.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

@AutoConfiguration
@ConditionalOnClass(AuditingEntityListener.class)
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
                return Optional.of(auth.getName());
            }
            return Optional.of("system");
        };
    }

    /**
     * Activate JPA auditing only when an EntityManagerFactory is present (real services
     * with JPA). Nested config avoids "JPA metamodel must not be empty" in JPA-less
     * test contexts (e.g. CommonLibraryStarterTests).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(jakarta.persistence.EntityManagerFactory.class)
    @EnableJpaAuditing(auditorAwareRef = "auditorAware")
    static class JpaAuditingActivation {
    }
}
```

- [ ] **Step 5: Register in `AutoConfiguration.imports`**

Edit `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
- Add line: `com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration`

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -pl utils/common-spring -Dtest=JpaAuditingAutoConfigurationTest`
Expected: PASS

> If `CommonLibraryStarterTests` fails with "JPA metamodel must not be empty", check whether the test runner application context supplies an `EntityManagerFactory` stub, potentially using `.withBean(EntityManagerFactory.class, this::stubEmf)` in `ApplicationContextRunner`. Smoke tests (`CommonLibraryStarterTests`) passed after updating the exclude list in Task 2 implementation; do not modify unless issues arise.

- [ ] **Step 7: Commit**

```bash
git add utils/common-spring/pom.xml \
        utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java \
        utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfigurationTest.java
git commit -m "feat(common-spring): auto-configure JPA auditing + AuditorAware bean"
```

---

### Task 3: Upgrade `SecurityProperties` to support method+path public endpoints

**Files:**
- Modify: `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java`

**Interfaces:**
- Produces: `List<EndpointRule>` field `publicPaths` (replaces `List<String> publicEndpoints` to match `COMMON-LIB-REFERENCE §3.4`); retain `resolvedPublicPaths()` and `PlatformDefaults.PUBLIC_PATHS` (actuator and swagger remain public)

- [ ] **Step 1: Inspect existing `SecurityProperties` to know what to change**

Read `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` (currently structured as a record):
- `@DefaultValue List<String> publicEndpoints`
- `resolvedPublicPaths()` merge service paths ∪ `PlatformDefaults.PUBLIC_PATHS`
- Constructor compact `SecurityProperties { ... }` cho null-safety

> Do not convert record to class: the codebase (auth-service, gateway-service) and `@DefaultValue` convention binding depend on the record definition. Only update field types, add nested records, and rename fields.

- [ ] **Step 2: Replace `publicEndpoints` field type + add nested record + rename**

```java
public record SecurityProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank String issuerUri,
        @DefaultValue("true") boolean csrfDisabled,
        @DefaultValue("true") boolean statelessSession,
        @DefaultValue List<EndpointRule> publicPaths,    // renamed from publicEndpoints
        @Valid @DefaultValue Cors cors
) {

    /** method == null → any HTTP method. */
    public record EndpointRule(HttpMethod method, String path) {
        public EndpointRule {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("EndpointRule.path must not be blank");
            }
        }
    }

    // resolvedPublicPaths() updated: streams publicPaths (extracting path) instead of publicEndpoints
    public List<String> resolvedPublicPaths() {
        return java.util.stream.Stream
                .concat(PlatformDefaults.PUBLIC_PATHS.stream(),
                        publicPaths.stream().map(EndpointRule::path))
                .distinct()
                .toList();
    }

    // Compact constructor: publicPaths == null → List.of()
}
```

Additional imports:
```java
import org.springframework.http.HttpMethod;
```

> ⚠️ **Breaking change:** callers of the old field `publicEndpoints` (e.g. `properties.getPublicEndpoints()`) → field renamed to `publicPaths`, record accessor is `properties.publicPaths()` (NOT `properties.getPublicPaths()`  -  that's bean-style, won't compile). Currently only `BaseSecurityConfig` uses this property (Task 4).

- [ ] **Step 3: Commit**

```bash
git add utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java
git commit -m "feat(common-security): public-paths as List<EndpointRule> (rename + method+path), keep platform defaults"
```

---

### Task 4: Update `BaseSecurityConfig` to apply method-aware `permitAll`

**Files:**
- Modify: `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java`

**Interfaces:**
- Consumes: `SecurityProperties.publicPaths()` (record accessor) returning `List<EndpointRule>`
- Produces: `SecurityFilterChain` with method-specific permitAll rules

- [ ] **Step 1: Read current `BaseSecurityConfig.securityFilterChain` method**

Find the section where `properties.resolvedPublicPaths()` is applied to `authorizeHttpRequests`.

- [ ] **Step 2: Replace path-only loop with method-aware loop (retaining platform defaults)**

```java
http.authorizeHttpRequests(auth -> {
    for (SecurityProperties.EndpointRule rule : properties.getPublicPaths()) {
        if (rule.method() != null) {
            auth.requestMatchers(rule.method(), rule.path()).permitAll();
        } else {
            auth.requestMatchers(rule.path()).permitAll();
        }
    }
    // Platform defaults (actuator, swagger, api-docs) remain public; do not remove
    auth.requestMatchers(SecurityProperties.PlatformDefaults.PUBLIC_PATHS.toArray(new String[0])).permitAll();
    auth.anyRequest().authenticated();
});
```

> `SecurityProperties` is a record, so the accessor for `publicPaths` is `properties.publicPaths()` (canonical record accessor), not `getPublicPaths()`. `EndpointRule` accessors are `rule.method()` and `rule.path()`.

- [ ] **Step 3: Retain `resolvedPublicPaths()`** (used elsewhere for backward compatibility); do not delete

```bash
grep -rn "resolvedPublicPaths" utils/ auth-service/ gateway-service/ product-service/
```

- [ ] **Step 4: Verify compile**

Run: `./mvnw test -pl utils/common-security`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java
git commit -m "feat(common-security): method-aware permitAll for EndpointRules, keep platform defaults public"
```

---

### Task 5: Migrate auth-service and gateway-service `application.yml` to EndpointRule format

**Files:**
- Modify: `auth-service/src/main/resources/application.yml`
- Modify: `gateway-service/src/main/resources/application.yml`

**Interfaces:**
- Produces: YAML keys structured as `EndpointRule`. Do not list actuator/swagger/api-docs (covered by platform defaults; re-adding causes duplicates).

- [ ] **Step 1: Auth-service yml**

OLD (`auth-service/src/main/resources/application.yml`):
```yaml
shop:
  security:
    public-endpoints:
      - /api/v1/auth/**
```

NEW:
```yaml
shop:
  security:
    public-paths:            # renamed from public-endpoints
      - path: /api/v1/auth/**
```

- [ ] **Step 2: Gateway-service yml**

Similarly, inspect `shop.security.public-endpoints` in `gateway-service/src/main/resources/application.yml` and convert to `public-paths` EndpointRule. Do not add actuator entries; defaults handle them.

- [ ] **Step 3: Run auth-service tests**

Run: `./mvnw test -pl auth-service`
Expected: BUILD SUCCESS (all 37 tests pass; controller tests use `addFilters=false` so they remain unaffected)

- [ ] **Step 4: Commit**

```bash
git add auth-service/src/main/resources/application.yml \
        gateway-service/src/main/resources/application.yml
git commit -m "refactor(auth,gateway): migrate public-endpoints to public-paths EndpointRule"
```

---

### Task 6: Verify full reactor build still green

**Files:** none (verification only)

- [ ] **Step 1: Run full reactor build**

Run from project root: `./mvnw clean test`
Expected: BUILD SUCCESS across all modules (common-core, common-spring, common-security, common-kafka, auth-service)

- [ ] **Step 2: If failures, fix them before proceeding to Phase 1**

Do NOT proceed to Phase 1 until Phase 0 is fully green.

---

## Phase 1  -  product-service skeleton + persistence

### Task 7: Update `product-service/pom.xml` with new dependencies

**Files:**
- Modify: `product-service/pom.xml`

- [ ] **Step 1: Add Redis + Kafka deps inside `<dependencies>`** (ModelMapper already present)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
```

> Do not add `org.mapstruct:mapstruct`: ModelMapper is used across the fleet (matching auth-service).
> `org.modelmapper:modelmapper` already exists in the current pom.

---

### Task 8: Create `ProductServiceApplication.java` + initial `application.yml`

**Files:**
- Modify: `product-service/src/main/java/com/shop/productservice/ProductServiceApplication.java`
- Create: `product-service/src/main/resources/application.yml`

- [ ] **Step 1: Ensure `@SpringBootApplication` exists**

```java
package com.shop.productservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductServiceApplication.class, args);
    }
}
```

- [ ] **Step 2: Create `application.yml`**

```yaml
spring:
  application:
    name: product-service
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/productservice}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  cache:
    type: redis
    redis:
      time-to-live: 600000
      cache-null-values: false
      use-key-prefix: true
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

# common-kafka (KafkaProperties) reads prefix SHOP kafka.*, not spring.kafka.*
shop:
  kafka:
    bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3

product:
  outbox:
    poll-interval-ms: 5000
    batch-size: 100
    max-retries: 10

shop:
  security:
    public-paths:
      - method: GET
        path: /api/v1/products/**
      - method: GET
        path: /api/v1/categories/**
      - method: GET
        path: /api/v1/brands/**

server:
  port: ${SERVER_PORT:8086}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

> Remove `enable.idempotence` / `delivery.timeout.ms` (unsupported by common-kafka `buildProducerProperties()`). Remove `hibernate.jdbc.lob.non_contextual_creation` (obsolete in Hibernate 6+).

- [ ] **Step 3: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/ProductServiceApplication.java \
        product-service/src/main/resources/application.yml
git commit -m "feat(product-service): application entrypoint + application.yml"
```

---

### Task 9: Create `CacheConfig.java`

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/config/CacheConfig.java`

**Interfaces:**
- Produces: `RedisCacheManagerBuilderCustomizer` configuring TTL per cache name

- [ ] **Step 1: Implement `CacheConfig`**

```java
package com.shop.productservice.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private static final Duration PRODUCT_TTL = Duration.ofMinutes(10);

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .withCacheConfiguration("product",
                defaultConfig(PRODUCT_TTL))
            .withCacheConfiguration("productBySlug",
                defaultConfig(PRODUCT_TTL));
    }

    private RedisCacheConfiguration defaultConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/config/CacheConfig.java
git commit -m "feat(product-service): CacheConfig with per-cache TTL"
```

---

### Task 10: Liquibase changelog  -  initial schema

**Files:**
- Create: `product-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `product-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`

- [ ] **Step 1: Create master changelog**

```yaml
databaseChangeLog:
  - include:
      file: changelog-001-initial-schema.yaml
```

- [ ] **Step 2: Create `changelog-001-initial-schema.yaml`** (categories → brands → products(+FKs) → outbox; `deleted_by` VARCHAR(255) match `SoftDeletable`)

```yaml
databaseChangeLog:
  # ===========================================================================
  # categories (created first so products can reference via FK)
  # ===========================================================================
  - createTable:
      tableName: categories
      columns:
        - column:
            name: id
            type: BIGINT
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: title,     type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: slug,      type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: image_url, type: VARCHAR(500) }
        - column: { name: parent_id, type: BIGINT }
        - column: { name: created_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: updated_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: created_by, type: VARCHAR(100) }
        - column: { name: updated_by, type: VARCHAR(100) }
        - column: { name: deleted,    type: BOOLEAN,     constraints: { nullable: false, defaultValue: false } }
        - column: { name: deleted_at, type: TIMESTAMP }
        - column: { name: deleted_by, type: VARCHAR(255) }

  - addForeignKeyConstraint:
      baseTableName: categories
      baseColumnNames: parent_id
      constraintName: fk_categories_parent
      referencedTableName: categories
      referencedColumnNames: id
      onDelete: RESTRICT

  - createIndex:
      tableName: categories
      indexName: idx_categories_slug_unique_active
      unique: true
      columns:
        - column: { name: slug }
      where: deleted = false

  - createIndex:
      tableName: categories
      indexName: idx_categories_parent_id
      columns:
        - column: { name: parent_id }

  # ===========================================================================
  # brands (created before products)
  # ===========================================================================
  - createTable:
      tableName: brands
      columns:
        - column:
            name: id
            type: BIGINT
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: name,        type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: slug,        type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: logo_url,    type: VARCHAR(500) }
        - column: { name: description, type: VARCHAR(1000) }
        - column: { name: created_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: updated_at, type: TIMESTAMP, constraints: { nullable: false } }
        - column: { name: created_by, type: VARCHAR(100) }
        - column: { name: updated_by, type: VARCHAR(100) }
        - column: { name: deleted,    type: BOOLEAN,     constraints: { nullable: false, defaultValue: false } }
        - column: { name: deleted_at, type: TIMESTAMP }
        - column: { name: deleted_by, type: VARCHAR(255) }

  - createIndex:
      tableName: brands
      indexName: idx_brands_slug_unique_active
      unique: true
      columns:
        - column: { name: slug }
      where: deleted = false

  # ===========================================================================
  # products (after categories and brands so FKs are valid)
  # ===========================================================================
  - createTable:
      tableName: products
      columns:
        - column:
            name: id
            type: BIGINT
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: title,       type: VARCHAR(200), constraints: { nullable: false } }
        - column: { name: slug,        type: VARCHAR(200), constraints: { nullable: false } }
        - column: { name: description, type: VARCHAR(2000) }
        - column: { name: sku,         type: VARCHAR(50),  constraints: { nullable: false } }
        - column: { name: price_unit,  type: NUMERIC(15,2), constraints: { nullable: false } }
        - column: { name: quantity,    type: INTEGER,      constraints: { nullable: false, defaultValue: 0 } }
        - column: { name: status,      type: VARCHAR(20),  constraints: { nullable: false } }
        - column: { name: image_url,   type: VARCHAR(500) }
        - column: { name: weight,      type: NUMERIC(8,3) }
        - column: { name: dimensions,  type: VARCHAR(50) }
        - column: { name: category_id, type: BIGINT }
        - column: { name: brand_id,    type: BIGINT }
        - column: { name: created_at,  type: TIMESTAMP,    constraints: { nullable: false } }
        - column: { name: updated_at,  type: TIMESTAMP,    constraints: { nullable: false } }
        - column: { name: created_by,  type: VARCHAR(100) }
        - column: { name: updated_by,  type: VARCHAR(100) }
        - column: { name: deleted,     type: BOOLEAN,      constraints: { nullable: false, defaultValue: false } }
        - column: { name: deleted_at,  type: TIMESTAMP }
        - column: { name: deleted_by,  type: VARCHAR(255) }

  - addForeignKeyConstraint:
      baseTableName: products
      baseColumnNames: category_id
      constraintName: fk_products_categories
      referencedTableName: categories
      referencedColumnNames: id
      onDelete: RESTRICT

  - addForeignKeyConstraint:
      baseTableName: products
      baseColumnNames: brand_id
      constraintName: fk_products_brands
      referencedTableName: brands
      referencedColumnNames: id
      onDelete: RESTRICT

  - createIndex:
      tableName: products
      indexName: idx_products_slug_unique_active
      unique: true
      columns:
        - column: { name: slug }
      where: deleted = false

  - createIndex:
      tableName: products
      indexName: idx_products_sku_unique_active
      unique: true
      columns:
        - column: { name: sku }
      where: deleted = false

  - createIndex:
      tableName: products
      indexName: idx_products_category_id
      columns:
        - column: { name: category_id }

  - createIndex:
      tableName: products
      indexName: idx_products_brand_id
      columns:
        - column: { name: brand_id }

  - createIndex:
      tableName: products
      indexName: idx_products_status
      columns:
        - column: { name: status }

  - createIndex:
      tableName: products
      indexName: idx_products_deleted
      columns:
        - column: { name: deleted }

  # ===========================================================================
  # outbox_events (inheriting AbstractMappedEntity extending SoftDeletable, with deleted columns)
  # ===========================================================================
  - createTable:
      tableName: outbox_events
      columns:
        - column:
            name: id
            type: BIGINT
            autoIncrement: true
            constraints:
              primaryKey: true
              nullable: false
        - column: { name: event_id,       type: VARCHAR(36),  constraints: { nullable: false, unique: true } }
        - column: { name: aggregate_type, type: VARCHAR(50),  constraints: { nullable: false } }
        - column: { name: aggregate_id,   type: BIGINT,       constraints: { nullable: false } }
        - column: { name: event_type,     type: VARCHAR(50),  constraints: { nullable: false } }
        - column: { name: topic,          type: VARCHAR(100), constraints: { nullable: false } }
        - column: { name: payload,        type: TEXT,         constraints: { nullable: false } }
        - column: { name: status,         type: VARCHAR(20),  constraints: { nullable: false } }
        - column: { name: retry_count,    type: INTEGER,      constraints: { nullable: false, defaultValue: 0 } }
        - column: { name: sent_at,        type: TIMESTAMP }
        - column: { name: last_error,     type: VARCHAR(1000) }
        - column: { name: created_at,     type: TIMESTAMP,    constraints: { nullable: false } }
        - column: { name: updated_at,     type: TIMESTAMP,    constraints: { nullable: false } }
        - column: { name: created_by,     type: VARCHAR(100) }
        - column: { name: updated_by,     type: VARCHAR(100) }
        - column: { name: deleted,        type: BOOLEAN,      constraints: { nullable: false, defaultValue: false } }
        - column: { name: deleted_at,     type: TIMESTAMP }
        - column: { name: deleted_by,     type: VARCHAR(255) }

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
git add product-service/src/main/resources/db/changelog/
git commit -m "feat(product-service): initial Liquibase schema (products, categories, brands, outbox_events)"
```

---

### Task 11: Create `ProductStatus` and `OutboxStatus` enums

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/entity/ProductStatus.java`
- Create: `product-service/src/main/java/com/shop/productservice/entity/OutboxStatus.java`

- [ ] **Step 1: Create `ProductStatus`**

```java
package com.shop.productservice.entity;

public enum ProductStatus {
    DRAFT, ACTIVE, OUT_OF_STOCK, DISCONTINUED
}
```

- [ ] **Step 2: Create `OutboxStatus`**

```java
package com.shop.productservice.entity;

public enum OutboxStatus {
    PENDING, SENT, FAILED
}
```

- [ ] **Step 3: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/entity/ProductStatus.java \
        product-service/src/main/java/com/shop/productservice/entity/OutboxStatus.java
git commit -m "feat(product-service): ProductStatus and OutboxStatus enums"
```

---

### Task 12: Create `Category` entity

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/entity/Category.java`

**Interfaces:**
- Consumes: `AbstractMappedEntity`, `SoftDeletable` from common-core
- Produces: JPA entity, self-referencing via `parent_id`

- [ ] **Step 1: Implement `Category`**

```java
package com.shop.productservice.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "categories")
@SQLRestriction("deleted = false")     // filters soft-deleted rows automatically, following auth-service pattern
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @JsonIgnore
    @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY)
    private Set<Category> children = new HashSet<>();
}
```

> Do not manually declare `deleted/deletedAt/deletedBy`/`softDelete(String)`: the base class already includes them (via `AbstractMappedEntity extends SoftDeletable`). Delete by calling `markDeleted(actor)` (from `SoftDeletable`).

- [ ] **Step 2: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/entity/Category.java
git commit -m "feat(product-service): Category entity (self-referencing tree)"
```

---

### Task 13: Create `Brand` entity

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/entity/Brand.java`

- [ ] **Step 1: Implement `Brand`**

```java
package com.shop.productservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "brands")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(length = 1000)
    private String description;
}
```

- [ ] **Step 2: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/entity/Brand.java
git commit -m "feat(product-service): Brand entity"
```

---

### Task 14: Create `Product` entity

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/entity/Product.java`

- [ ] **Step 1: Implement `Product`**

```java
package com.shop.productservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
@SQLRestriction("deleted = false")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(name = "price_unit", nullable = false, precision = 15, scale = 2)
    private BigDecimal priceUnit;

    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(precision = 8, scale = 3)
    private BigDecimal weight;

    @Column(length = 50)
    private String dimensions;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;
}
```

- [ ] **Step 2: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/entity/Product.java
git commit -m "feat(product-service): Product entity"
```

---

### Task 15: Create `OutboxEvent` entity

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java`

- [ ] **Step 1: Implement `OutboxEvent`**

```java
package com.shop.productservice.entity;

import com.shop.common.core.data.AbstractMappedEntity;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "outbox_events")
// Omit @SQLRestriction: the outbox relay must inspect every event even when the base entity defines deleted flags
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent extends AbstractMappedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

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
git add product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java
git commit -m "feat(product-service): OutboxEvent entity"
```

---

## Phase 2  -  Repositories, DTOs, Mappers

### Task 16: Repositories (Category, Brand, Outbox)

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/repository/CategoryRepository.java`
- Create: `product-service/src/main/java/com/shop/productservice/repository/BrandRepository.java`
- Create: `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java`

- [ ] **Step 1: Create `CategoryRepository`**

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    // findById(Long): inherited method, automatically filters soft-deleted rows (@SQLRestriction)
    List<Category> findAllByOrderByTitleAsc();
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
}
```

- [ ] **Step 2: Create `BrandRepository`**

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    // findAll(Pageable) + findById(Long): inherited from JpaRepository, automatically filters deleted rows
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, Long id);
}
```

- [ ] **Step 3: Create `OutboxEventRepository`**

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
    long countByStatus(OutboxStatus status);
}
```

- [ ] **Step 4: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/repository/
git commit -m "feat(product-service): Category, Brand, OutboxEvent repositories"
```

---

### Task 17: `ProductRepository` + `@DataJpaTest`

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/repository/ProductRepository.java`
- Create: `product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java`
- Modify: `product-service/pom.xml`: add test deps `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:kafka`, `spring-kafka-test`, `awaitility`. **JPA slice dependencies (`spring-boot-data-jpa-test`, `spring-boot-jpa-test`, `h2`) are transitively provided by `common-core/pom.xml`** (Task 1 transitive). Do not re-add them to product-service pom.

**Interfaces:**
- Produces: `ProductRepository` with `@EntityGraph` queries and `existsBy*` checks

- [ ] **Step 1: Add Testcontainers deps to `product-service/pom.xml`**

Add to `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Create `ProductRepository`** (omit `*AndDeletedFalse` suffix; `@SQLRestriction` filters deleted records automatically)

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ProductRepository
        extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsById(Long id);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsBySlug(String slug);

    // findById(Long), findAll(Pageable), findAll(Spec, Pageable): inherited from JpaRepository/JpaSpecificationExecutor

    boolean existsBySlug(String slug);
    boolean existsBySku(String sku);
    boolean existsBySlugAndIdNot(String slug, Long id);
    boolean existsBySkuAndIdNot(String sku, Long id);
}
```

- [ ] **Step 3: Write the failing test (`ProductRepositoryTest`)** (Boot 4 packages verified in Task 1 + `@Import(LiquibaseAutoConfiguration.class)` since `@DataJpaTest` does not execute Liquibase automatically):

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration.class,
    LiquibaseAutoConfiguration.class
})
class ProductRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("product_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.liquibase.change-log", () -> "classpath:db/changelog/db.changelog-master.yaml");
    }

    @Autowired TestEntityManager em;
    @Autowired ProductRepository productRepository;

    private Category category;
    private Brand brand;

    @BeforeEach
    void setUp() {
        category = Category.builder().title("Phones").slug("phones").build();
        em.persistAndFlush(category);
        brand = Brand.builder().name("Acme").slug("acme").build();
        em.persistAndFlush(brand);
    }

    @Test
    void findWithRelationsById_returnsProductWithCategoryAndBrand() {
        Product p = Product.builder()
            .title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10)
            .status(ProductStatus.ACTIVE).category(category).brand(brand)
            .build();
        em.persistAndFlush(p);
        em.clear();

        Optional<Product> result = productRepository.findWithRelationsById(p.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getCategory().getTitle()).isEqualTo("Phones");
        assertThat(result.get().getBrand().getName()).isEqualTo("Acme");
    }

    @Test
    void findWithRelationsBySlug_excludesSoftDeleted() {
        Product active = Product.builder()
            .title("Active").sku("A-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.ACTIVE).slug("active").build();
        Product deleted = Product.builder()
            .title("Deleted").sku("D-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.DISCONTINUED).slug("deleted").build();
        deleted.markDeleted("test");                                   // SoftDeletable API
        em.persistAndFlush(active);
        em.persistAndFlush(deleted);
        em.clear();

        assertThat(productRepository.findWithRelationsBySlug("active")).isPresent();
        assertThat(productRepository.findWithRelationsBySlug("deleted")).isEmpty();  // Filtered by @SQLRestriction
    }

    @Test
    void findAllWithFilterByCategoryAndStatus() {
        Product p1 = Product.builder().title("P1").slug("p1").sku("P1").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).category(category).build();
        Product p2 = Product.builder().title("P2").slug("p2").sku("P2").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.DRAFT).category(category).build();
        em.persistAndFlush(p1);
        em.persistAndFlush(p2);

        // Predicate deleted=false is unnecessary; @SQLRestriction filters automatically
        Specification<Product> spec = (root, query, cb) ->
            cb.and(
                cb.equal(root.get("category").get("id"), category.getId()),
                cb.equal(root.get("status"), ProductStatus.ACTIVE)
            );

        Page<Product> page = productRepository.findAll(spec, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("P1");
    }

    @Test
    void existsBySlugAndIdNot_worksForUpdate() {
        Product p = Product.builder().title("T").slug("t").sku("T").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).build();
        em.persistAndFlush(p);

        assertThat(productRepository.existsBySlugAndIdNot("t", 999L)).isTrue();
        assertThat(productRepository.existsBySlugAndIdNot("t", p.getId())).isFalse();
        assertThat(productRepository.existsBySlugAndIdNot("other", p.getId())).isFalse();
    }
}
```

- [ ] **Step 4: Run test to verify it fails (compile error: repo doesn't exist)**

Run: `./mvnw test -pl product-service -Dtest=ProductRepositoryTest`
Expected: compilation error  -  `ProductRepository` not found

- [ ] **Step 5: Add `ProductRepository` (already created in Step 2)**

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=ProductRepositoryTest`
Expected: PASS (4 tests)

- [ ] **Step 7: Commit**

```bash
git add product-service/pom.xml \
        product-service/src/main/java/com/shop/productservice/repository/ProductRepository.java \
        product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java
git commit -m "feat(product-service): ProductRepository + @DataJpaTest coverage"
```

---

### Task 18: DTOs (request + response)

**Files:**
- Create: 6 request DTOs
- Create: 5 response DTOs + 1 filter record
- (Create: `product-service/src/main/java/com/shop/productservice/dto/`)

- [ ] **Step 1: Create `ProductCreateRequest`**

```java
package com.shop.productservice.dto.request;

import com.shop.productservice.entity.ProductStatus;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record ProductCreateRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 200) String slug,
    @Size(max = 2000)           String description,   // optional theo spec §3.6
    @NotBlank @Size(max = 50)  String sku,
    @NotNull  @DecimalMin("0.0") BigDecimal priceUnit,
    @NotNull  @Min(0)            Integer quantity,
    @NotNull                    ProductStatus status,
    @Size(max = 500)             String imageUrl,
    @DecimalMin("0.0")           BigDecimal weight,
    @Size(max = 50)              String dimensions,
    Long categoryId,
    Long brandId
) {}
```

- [ ] **Step 2: Create `ProductUpdateRequest`** (same fields, no constraints)

```java
package com.shop.productservice.dto.request;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;

public record ProductUpdateRequest(
    String title,
    String slug,
    String description,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl,
    BigDecimal weight,
    String dimensions,
    Long categoryId,
    Long brandId
) {}
```

- [ ] **Step 3: Create `ProductSummaryResponse`**

```java
package com.shop.productservice.dto.response;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;

public record ProductSummaryResponse(
    Long id,
    String title,
    String slug,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl
) {}
```

- [ ] **Step 4: Create `ProductDetailResponse`**

```java
package com.shop.productservice.dto.response;

import com.shop.productservice.entity.ProductStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductDetailResponse(
    Long id,
    String title,
    String slug,
    String description,
    String sku,
    BigDecimal priceUnit,
    Integer quantity,
    ProductStatus status,
    String imageUrl,
    BigDecimal weight,
    String dimensions,
    Long categoryId,
    String categoryTitle,
    Long brandId,
    String brandName,
    Instant createdAt,
    Instant updatedAt
) {}
```

- [ ] **Step 5: Create `CategoryCreateRequest`, `CategoryUpdateRequest`, `CategoryResponse`, `CategoryTreeResponse`**

```java
// CategoryCreateRequest.java
package com.shop.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
    @NotBlank @Size(max = 100) String title,
    @NotBlank @Size(max = 100) String slug,
    @Size(max = 500)           String imageUrl,
    Long parentId
) {}
```

```java
// CategoryUpdateRequest.java
package com.shop.productservice.dto.request;

public record CategoryUpdateRequest(
    String title,
    String slug,
    String imageUrl,
    Long parentId
) {}
```

```java
// CategoryResponse.java
package com.shop.productservice.dto.response;

public record CategoryResponse(
    Long id,
    String title,
    String slug,
    String imageUrl,
    Long parentId
) {}
```

```java
// CategoryTreeResponse.java
package com.shop.productservice.dto.response;

import java.util.List;

public record CategoryTreeResponse(
    Long id,
    String title,
    String slug,
    String imageUrl,
    Long parentId,
    List<CategoryTreeResponse> children
) {}
```

- [ ] **Step 6: Create `BrandCreateRequest`, `BrandUpdateRequest`, `BrandResponse`**

```java
// BrandCreateRequest.java
package com.shop.productservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandCreateRequest(
    @NotBlank @Size(max = 100)  String name,
    @NotBlank @Size(max = 100)  String slug,
    @Size(max = 500)            String logoUrl,
    @Size(max = 1000)           String description
) {}
```

```java
// BrandUpdateRequest.java
package com.shop.productservice.dto.request;

public record BrandUpdateRequest(
    String name,
    String slug,
    String logoUrl,
    String description
) {}
```

```java
// BrandResponse.java
package com.shop.productservice.dto.response;

public record BrandResponse(
    Long id,
    String name,
    String slug,
    String logoUrl,
    String description
) {}
```

- [ ] **Step 7: Create `ProductFilter`**

```java
package com.shop.productservice.dto;

import com.shop.productservice.entity.ProductStatus;

public record ProductFilter(Long categoryId, Long brandId, ProductStatus status) {}
```

- [ ] **Step 8: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/dto/
git commit -m "feat(product-service): request/response DTOs + ProductFilter"
```

---

### Task 19: ModelMapper mappers (sync auth-service pattern)

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/mapper/ProductMapper.java`
- Create: `product-service/src/main/java/com/shop/productservice/mapper/CategoryMapper.java`
- Create: `product-service/src/main/java/com/shop/productservice/mapper/BrandMapper.java`

> **Aligned with auth-service pattern**: `@Component` classes inject the `ModelMapper` bean (from `common-spring/ModelMapperAutoConfiguration`, configured with STRICT matching and skip-null). Each mapper exposes `toResponse(entity)` (building DTOs manually or via `modelMapper.map(...)`), `toEntity(CreateRequest)`, and `partialUpdate(@MappingTarget entity, UpdateRequest)` (ignoring nulls: `if (req.foo() != null) entity.setFoo(req.foo())`). Do not use MapStruct, `BaseMapper`, or `EntityCreateUpdateMapper`.

- [ ] **Step 1: Create `ProductMapper`**

```java
package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.Product;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class ProductMapper {

    private final ModelMapper modelMapper;

    public ProductMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public ProductSummaryResponse toSummaryResponse(Product product) {
        return new ProductSummaryResponse(
            product.getId(),
            product.getTitle(),
            product.getSlug(),
            product.getSku(),
            product.getPriceUnit(),
            product.getQuantity(),
            product.getStatus(),
            product.getImageUrl()
        );
    }

    public ProductDetailResponse toDetailResponse(Product product) {
        ProductDetailResponse r = modelMapper.map(product, ProductDetailResponse.class);
        // Map category/brand relation fields manually (not inferred by ModelMapper)
        if (product.getCategory() != null) {
            r.setCategoryId(product.getCategory().getId());
            r.setCategoryTitle(product.getCategory().getTitle());
        }
        if (product.getBrand() != null) {
            r.setBrandId(product.getBrand().getId());
            r.setBrandName(product.getBrand().getName());
        }
        return r;
    }

    public Product toEntity(ProductCreateRequest request) {
        Product p = modelMapper.map(request, Product.class);
        p.setId(null);   // guarantees identity insertion
        return p;
    }

    public void partialUpdate(Product target, ProductUpdateRequest request) {
        if (request.title()       != null) target.setTitle(request.title());
        if (request.slug()        != null) target.setSlug(request.slug());
        if (request.description() != null) target.setDescription(request.description());
        if (request.sku()         != null) target.setSku(request.sku());
        if (request.priceUnit()   != null) target.setPriceUnit(request.priceUnit());
        if (request.quantity()    != null) target.setQuantity(request.quantity());
        if (request.status()      != null) target.setStatus(request.status());
        if (request.imageUrl()    != null) target.setImageUrl(request.imageUrl());
        if (request.weight()      != null) target.setWeight(request.weight());
        if (request.dimensions()  != null) target.setDimensions(request.dimensions());
        // categoryId/brandId set separately in service after lookup
    }
}
```

- [ ] **Step 2: Create `CategoryMapper`**

```java
package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class CategoryMapper {

    private final ModelMapper modelMapper;

    public CategoryMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
            category.getId(),
            category.getTitle(),
            category.getSlug(),
            category.getImageUrl(),
            category.getParent() != null ? category.getParent().getId() : null
        );
    }

    public CategoryTreeResponse toTreeResponse(Category category, List<CategoryTreeResponse> children) {
        return new CategoryTreeResponse(
            category.getId(),
            category.getTitle(),
            category.getSlug(),
            category.getImageUrl(),
            category.getParent() != null ? category.getParent().getId() : null,
            children != null ? children : new ArrayList<>()
        );
    }

    public Category toEntity(CategoryCreateRequest request) {
        Category c = modelMapper.map(request, Category.class);
        c.setId(null);
        return c;
    }

    public void partialUpdate(Category target, CategoryUpdateRequest request) {
        if (request.title()     != null) target.setTitle(request.title());
        if (request.slug()      != null) target.setSlug(request.slug());
        if (request.imageUrl() != null) target.setImageUrl(request.imageUrl());
        // parentId set separately in service after lookup
    }
}
```

- [ ] **Step 3: Create `BrandMapper`**

```java
package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import org.modelmapper.ModelMapper;
import org.springframework.stereotype.Component;

@Component
public class BrandMapper {

    private final ModelMapper modelMapper;

    public BrandMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public BrandResponse toResponse(Brand brand) {
        return new BrandResponse(
            brand.getId(),
            brand.getName(),
            brand.getSlug(),
            brand.getLogoUrl(),
            brand.getDescription()
        );
    }

    public Brand toEntity(BrandCreateRequest request) {
        Brand b = modelMapper.map(request, Brand.class);
        b.setId(null);
        return b;
    }

    public void partialUpdate(Brand target, BrandUpdateRequest request) {
        if (request.name()        != null) target.setName(request.name());
        if (request.slug()        != null) target.setSlug(request.slug());
        if (request.logoUrl()     != null) target.setLogoUrl(request.logoUrl());
        if (request.description() != null) target.setDescription(request.description());
    }
}
```

> **DTOs are records** (Task 18): mappers invoke canonical constructors directly `new BrandResponse(brand.getId(), ...)`.

- [ ] **Step 4: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/mapper/
git commit -m "feat(product-service): ModelMapper mappers (Product, Category, Brand)"
```

---

## Phase 3  -  Service layer

### Task 20: `BrandService` + tests

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/service/BrandService.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java`
- Create: `product-service/src/test/java/com/shop/productservice/service/impls/BrandServiceImplTest.java`

- [ ] **Step 1: Create `BrandService` interface**

```java
package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import org.springframework.data.domain.Pageable;

public interface BrandService {
    PageResponse<BrandResponse> findAll(Pageable pageable);
    BrandResponse findById(Long id);
    BrandResponse create(BrandCreateRequest request);
    BrandResponse update(Long id, BrandUpdateRequest request);
    void delete(Long id);
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.mapper.BrandMapper;
import com.shop.productservice.repository.BrandRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    @Mock BrandRepository repo;
    @Mock BrandMapper mapper;
    @Mock AuditorAware<String> auditorAware;
    @InjectMocks BrandServiceImpl service;

    @Test
    void findById_returnsBrand() {
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(brand));
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndReturns() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.existsBySlug("acme")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(brand);
        when(repo.save(brand)).thenReturn(brand);
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.create(req)).isEqualTo(resp);
    }

    @Test
    void create_throwsConflictOnDuplicateSlug() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        when(repo.existsBySlug("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_appliesPartialUpdate() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").description("old").build();
        BrandUpdateRequest req = new BrandUpdateRequest(null, null, null, "new");
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toResponse(existing)).thenReturn(
            new BrandResponse(1L, "Acme", "acme", null, "new"));

        BrandResponse result = service.update(1L, req);
        assertThat(result.description()).isEqualTo("new");
        verify(mapper).partialUpdate(existing, req);
    }

    @Test
    void delete_softDeletesWithActor() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").build();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedBy()).isEqualTo("alice");
        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void findAll_returnsPage() {
        Page<Brand> page = new PageImpl<>(List.of());
        when(repo.findAll(any(PageRequest.class))).thenReturn(page);

        PageResponse<BrandResponse> result = service.findAll(PageRequest.of(0, 10));
        assertThat(result.content()).isEmpty();
    }
}
```

- [ ] **Step 3: Run test to verify it fails (compile error: impl doesn't exist)**

Run: `./mvnw test -pl product-service -Dtest=BrandServiceImplTest`
Expected: compilation error

- [ ] **Step 4: Implement `BrandServiceImpl`**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.mapper.BrandMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.service.BrandService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository repo;
    private final BrandMapper mapper;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BrandResponse> findAll(Pageable pageable) {
        Page<Brand> page = repo.findAll(pageable);                  // Single query; @SQLRestriction filters deleted
        return PageResponse.of(
            page.map(mapper::toResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public BrandResponse findById(Long id) {
        return repo.findById(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.conflict("brand.slug.exists");
        }
        Brand brand = mapper.toEntity(request);
        return mapper.toResponse(repo.save(brand));
    }

    @Override
    @Transactional
    public BrandResponse update(Long id, BrandUpdateRequest request) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        mapper.partialUpdate(existing, request);
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Brand existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElse("system"));
        repo.save(existing);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=BrandServiceImplTest`
Expected: PASS (7 tests)

- [ ] **Step 6: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/service/BrandService.java \
        product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java \
        product-service/src/test/java/com/shop/productservice/service/impls/BrandServiceImplTest.java
git commit -m "feat(product-service): BrandService CRUD with soft-delete"
```

---

### Task 21: `CategoryService` + `findTree` test

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/service/CategoryService.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/impls/CategoryServiceImpl.java`
- Create: `product-service/src/test/java/com/shop/productservice/service/impls/CategoryServiceImplTest.java`

- [ ] **Step 1: Create `CategoryService` interface**

```java
package com.shop.productservice.service;

import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;

import java.util.List;

public interface CategoryService {
    List<CategoryResponse> findAll();
    List<CategoryTreeResponse> findTree();
    CategoryResponse findById(Long id);
    CategoryResponse create(CategoryCreateRequest request);
    CategoryResponse update(Long id, CategoryUpdateRequest request);
    void delete(Long id);
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.mapper.CategoryMapper;
import com.shop.productservice.repository.CategoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CategoryServiceImplTest {

    @Mock CategoryRepository repo;
    @Mock CategoryMapper mapper;
    @InjectMocks CategoryServiceImpl service;

    @Test
    void findTree_buildsNestedStructure() {
        Category root = Category.builder().id(1L).title("Electronics").slug("electronics").build();
        Category child1 = Category.builder().id(2L).title("Phones").slug("phones").parent(root).build();
        Category child2 = Category.builder().id(3L).title("Laptops").slug("laptops").parent(root).build();
        Category grandchild = Category.builder().id(4L).title("iPhone").slug("iphone").parent(child1).build();
        when(repo.findAllByOrderByTitleAsc())
            .thenReturn(List.of(root, child1, child2, grandchild));
        when(mapper.toTreeResponse(eq(root),     any())).thenAnswer(inv -> new CategoryTreeResponse(1L, "Electronics", "electronics", null, null, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child1),   any())).thenAnswer(inv -> new CategoryTreeResponse(2L, "Phones", "phones", null, 1L, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(child2),   any())).thenAnswer(inv -> new CategoryTreeResponse(3L, "Laptops", "laptops", null, 1L, inv.getArgument(1)));
        when(mapper.toTreeResponse(eq(grandchild), any())).thenAnswer(inv -> new CategoryTreeResponse(4L, "iPhone", "iphone", null, 2L, inv.getArgument(1)));

        List<CategoryTreeResponse> tree = service.findTree();

        assertThat(tree).hasSize(1);
        CategoryTreeResponse rootResp = tree.get(0);
        assertThat(rootResp.title()).isEqualTo("Electronics");
        assertThat(rootResp.children()).hasSize(2);
        assertThat(rootResp.children().stream().filter(c -> c.title().equals("Phones")).findFirst().orElseThrow().children())
            .extracting(CategoryTreeResponse::title).containsExactly("iPhone");
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findById(1L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(BusinessException.class);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -pl product-service -Dtest=CategoryServiceImplTest`
Expected: compile error

- [ ] **Step 4: Implement `CategoryServiceImpl`**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.entity.Category;
import com.shop.productservice.mapper.CategoryMapper;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.service.CategoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository repo;
    private final CategoryMapper mapper;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return repo.findAllByOrderByTitleAsc().stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> findTree() {
        List<Category> all = repo.findAllByOrderByTitleAsc();
        Map<Long, CategoryTreeResponse> nodeMap = new LinkedHashMap<>();
        List<CategoryTreeResponse> roots = new ArrayList<>();
        for (Category c : all) {
            nodeMap.put(c.getId(), mapper.toTreeResponse(c, new ArrayList<>()));
        }
        for (Category c : all) {
            CategoryTreeResponse node = nodeMap.get(c.getId());
            if (c.getParent() == null) {
                roots.add(node);
            } else {
                // guard: parent is soft-deleted, skip orphaned child
                CategoryTreeResponse parent = nodeMap.get(c.getParent().getId());
                if (parent != null) {
                    parent.children().add(node);
                }
            }
        }
        return roots;
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return repo.findById(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
    }

    @Override
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.conflict("category.slug.exists");
        }
        Category category = mapper.toEntity(request);
        if (request.parentId() != null) {
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            category.setParent(parent);
        }
        return mapper.toResponse(repo.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        mapper.partialUpdate(existing, request);
        if (request.parentId() != null) {
            Category parent = repo.findById(request.parentId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.parentId()));
            existing.setParent(parent);
        }
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Category existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElse("system"));
        repo.save(existing);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=CategoryServiceImplTest`
Expected: PASS (2 tests)

- [ ] **Step 6: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/service/CategoryService.java \
        product-service/src/main/java/com/shop/productservice/service/impls/CategoryServiceImpl.java \
        product-service/src/test/java/com/shop/productservice/service/impls/CategoryServiceImplTest.java
git commit -m "feat(product-service): CategoryService with findTree"
```

---

### Task 22: `ProductService` + tests (cache + soft-delete + publish hooks)

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/service/ProductService.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/impls/ProductServiceImpl.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/ProductEventPublisher.java` (stub for now)
- Create: `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplTest.java`

- [ ] **Step 1: Create `ProductEventPublisher` stub (full impl in Task 24)**

```java
package com.shop.productservice.service;

import com.shop.productservice.entity.Product;

public interface ProductEventPublisher {
    void publishCreated(Product product);
    void publishUpdated(Product product);
    void publishDeleted(Product product);
}
```

```java
package com.shop.productservice.service;

import com.shop.productservice.entity.Product;
import org.springframework.stereotype.Service;

@Service
public class NoOpProductEventPublisher implements ProductEventPublisher {
    @Override public void publishCreated(Product p) {}
    @Override public void publishUpdated(Product p) {}
    @Override public void publishDeleted(Product p) {}
}
```

> Replace this stub with the real `TransactionalProductEventPublisher` in Task 24 (after `OutboxEventRepository` is wired). For now, tests can use Mockito mock or this no-op.

- [ ] **Step 2: Create `ProductService` interface**

```java
package com.shop.productservice.service;

import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import org.springframework.data.domain.Pageable;

public interface ProductService {
    PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable);
    ProductDetailResponse findById(Long id);
    ProductDetailResponse findBySlug(String slug);
    ProductDetailResponse create(ProductCreateRequest request);
    ProductDetailResponse update(Long id, ProductUpdateRequest request);
    void delete(Long id);
}
```

- [ ] **Step 3: Write the failing test**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.entity.*;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    @Mock ProductRepository repo;
    @Mock CategoryRepository categoryRepo;
    @Mock BrandRepository brandRepo;
    @Mock ProductMapper mapper;
    @Mock ProductEventPublisher publisher;
    @Mock AuditorAware<String> auditorAware;
    @InjectMocks ProductServiceImpl service;

    private ProductCreateRequest sampleCreate() {
        return new ProductCreateRequest("iPhone 15", "iphone-15", "desc", "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null);
    }

    private Product sampleProduct() {
        return Product.builder().id(1L).title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10).status(ProductStatus.ACTIVE).build();
    }

    @Test
    void findById_returnsProduct() {
        Product p = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null);
        when(repo.findWithRelationsById(1L)).thenReturn(Optional.of(p));
        when(mapper.toDetailResponse(p)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(repo.findWithRelationsById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesEvent() {
        ProductCreateRequest req = sampleCreate();
        Product product = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null);
        when(repo.existsBySlug("iphone-15")).thenReturn(false);
        when(repo.existsBySku("IP15-001")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(product);
        when(repo.save(product)).thenReturn(product);
        when(mapper.toDetailResponse(product)).thenReturn(resp);

        ProductDetailResponse result = service.create(req);

        assertThat(result).isEqualTo(resp);
        verify(publisher).publishCreated(product);
    }

    @Test
    void create_throwsConflictOnDuplicateSlug() {
        ProductCreateRequest req = sampleCreate();
        when(repo.existsBySlug("iphone-15")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(publisher);
    }

    @Test
    void update_appliesPartialUpdateAndPublishes() {
        Product existing = sampleProduct();
        ProductUpdateRequest req = new ProductUpdateRequest(null, null, "new desc", null,
            new BigDecimal("1099.00"), null, null, null, null, null, null, null);
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            "new desc", "IP15-001", new BigDecimal("1099.00"), 10, ProductStatus.ACTIVE,
            null, null, null, null, null, null, null, null, null);
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        ProductDetailResponse result = service.update(1L, req);

        assertThat(result.priceUnit()).isEqualByComparingTo("1099.00");
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void delete_softDeletesWithActorAndPublishes() {
        Product existing = sampleProduct();
        when(repo.findById(1L)).thenReturn(Optional.of(existing));
        when(auditorAware.getCurrentAuditor()).thenReturn(Optional.of("alice"));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedBy()).isEqualTo("alice");
        verify(repo).save(existing);
        verify(publisher).publishDeleted(existing);
    }

    @Test
    void findAll_returnsPagedSummary() {
        Product p = sampleProduct();
        when(repo.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(new PageImpl<>(List.of(p)));
        when(mapper.toSummaryResponse(p)).thenReturn(
            new com.shop.productservice.dto.response.ProductSummaryResponse(
                1L, "iPhone 15", "iphone-15", "IP15-001",
                new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null));

        var result = service.findAll(new ProductFilter(null, null, null), PageRequest.of(0, 10));

        assertThat(result.content()).hasSize(1);
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./mvnw test -pl product-service -Dtest=ProductServiceImplTest`
Expected: compile error

- [ ] **Step 5: Implement `ProductServiceImpl`**

```java
package com.shop.productservice.service.impls;

import com.shop.common.core.exception.BusinessException;
import com.shop.common.core.exception.ErrorCode;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Brand;
import com.shop.productservice.entity.Category;
import com.shop.productservice.entity.Product;
import com.shop.productservice.mapper.ProductMapper;
import com.shop.productservice.repository.BrandRepository;
import com.shop.productservice.repository.CategoryRepository;
import com.shop.productservice.repository.ProductRepository;
import com.shop.productservice.service.ProductEventPublisher;
import com.shop.productservice.service.ProductService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository repo;
    private final CategoryRepository categoryRepo;
    private final BrandRepository brandRepo;
    private final ProductMapper mapper;
    private final ProductEventPublisher publisher;
    private final AuditorAware<String> auditorAware;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable) {
        // Predicate deleted=false is unnecessary; @SQLRestriction filters automatically
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), filter.categoryId()));
            }
            if (filter.brandId() != null) {
                predicates.add(cb.equal(root.get("brand").get("id"), filter.brandId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<Product> page = repo.findAll(spec, pageable);
        return PageResponse.of(
            page.map(mapper::toSummaryResponse).getContent(),
            page.getNumber(),
            page.getSize(),
            page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product", key = "#id")
    public ProductDetailResponse findById(Long id) {
        return repo.findWithRelationsById(id)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "productBySlug", key = "#slug")
    public ProductDetailResponse findBySlug(String slug) {
        return repo.findWithRelationsBySlug(slug)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, "slug=" + slug));
    }

    @Override
    @Transactional
    public ProductDetailResponse create(ProductCreateRequest request) {
        if (repo.existsBySlug(request.slug())) {
            throw BusinessException.conflict("product.slug.exists");
        }
        if (repo.existsBySku(request.sku())) {
            throw BusinessException.conflict("product.sku.exists");
        }
        Product product = mapper.toEntity(request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findById(request.categoryId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.categoryId()));
            product.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findById(request.brandId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, request.brandId()));
            product.setBrand(brand);
        }
        Product saved = repo.save(product);
        publisher.publishCreated(saved);
        return mapper.toDetailResponse(saved);
    }

    @Override
    @Transactional
    @Caching(put = @CachePut(value = "product", key = "#id"),
             evict = @CacheEvict(value = "productBySlug", allEntries = true))
    public ProductDetailResponse update(Long id, ProductUpdateRequest request) {
        Product existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
        if (request.slug() != null && !request.slug().equals(existing.getSlug())
            && repo.existsBySlug(request.slug())) {
            throw BusinessException.conflict("product.slug.exists");
        }
        if (request.sku() != null && !request.sku().equals(existing.getSku())
            && repo.existsBySku(request.sku())) {
            throw BusinessException.conflict("product.sku.exists");
        }
        mapper.partialUpdate(existing, request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findById(request.categoryId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.CATEGORY_NOT_FOUND, request.categoryId()));
            existing.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findById(request.brandId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.BRAND_NOT_FOUND, request.brandId()));
            existing.setBrand(brand);
        }
        Product saved = repo.save(existing);
        publisher.publishUpdated(saved);
        return mapper.toDetailResponse(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"product", "productBySlug"}, allEntries = true)
    public void delete(Long id) {
        Product existing = repo.findById(id)
            .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
        existing.markDeleted(auditorAware.getCurrentAuditor().orElse("system"));
        repo.save(existing);
        publisher.publishDeleted(existing);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=ProductServiceImplTest`
Expected: PASS (7 tests)

- [ ] **Step 7: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/service/
git commit -m "feat(product-service): ProductService with cache + soft-delete + event hooks"
```

---

## Phase 4  -  Controllers

### Task 23: `BrandController` + `@WebMvcTest`

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/controller/BrandController.java`
- Create: `product-service/src/test/java/com/shop/productservice/controller/BrandControllerTest.java`

- [ ] **Step 1: Create `BrandController`**

```java
package com.shop.productservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/brands")
@RequiredArgsConstructor
public class BrandController {

    private final BrandService brandService;

    @GetMapping
    public ApiResponse<PageResponse<BrandResponse>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(brandService.findAll(PageRequest.of(page, size)));
    }

    @GetMapping("/{id}")
    public ApiResponse<BrandResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(brandService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BrandResponse> create(@Valid @RequestBody BrandCreateRequest request) {
        return ApiResponse.ok(brandService.create(request), "Brand created successfully");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BrandResponse> update(@PathVariable Long id,
                                              @Valid @RequestBody BrandUpdateRequest request) {
        return ApiResponse.ok(brandService.update(id, request), "Brand updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        brandService.delete(id);
        return ApiResponse.message("Brand deleted successfully");
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Boot 4 package
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;          // Boot 4 package
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;     // Do not use @MockBean
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BrandController.class)
@AutoConfigureMockMvc(addFilters = false)   // Disable security filters per auth-service pattern; test @PreAuthorize in integration suite
class BrandControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean BrandService brandService;

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(brandService.findById(1L)).thenReturn(resp);

        mockMvc.perform(get("/api/v1/brands/{id}", 1L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(1))
            .andExpect(jsonPath("$.data.name").value("Acme"));
    }

    @Test
    void create_returns200() throws Exception {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(brandService.create(any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/brands")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Acme"));
    }
}
```

> **Do not write 403-anonymous tests in slice tests**: `@WebMvcTest` does not load common-security's `SecurityAutoConfiguration` (without explicit `@Import(SecurityAutoConfiguration.class)`); with `addFilters=false` security filters remain disabled, and `@PreAuthorize` relies on method-security AOP not active in slices. Verify authorization via integration tests (`@SpringBootTest` loading the full security chain).

- [ ] **Step 3: Run test to verify it fails (compile error: controller doesn't exist)**

- [ ] **Step 4: Controller already created in Step 1**

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=BrandControllerTest`
Expected: PASS (3 tests)

- [ ] **Step 6: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/controller/BrandController.java \
        product-service/src/test/java/com/shop/productservice/controller/BrandControllerTest.java
git commit -m "feat(product-service): BrandController with @PreAuthorize"
```

---

### Task 24: `CategoryController`

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/controller/CategoryController.java`

- [ ] **Step 1: Create `CategoryController`**

```java
package com.shop.productservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.productservice.dto.request.CategoryCreateRequest;
import com.shop.productservice.dto.request.CategoryUpdateRequest;
import com.shop.productservice.dto.response.CategoryResponse;
import com.shop.productservice.dto.response.CategoryTreeResponse;
import com.shop.productservice.service.CategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    public ApiResponse<List<CategoryResponse>> findAll() {
        return ApiResponse.ok(categoryService.findAll());
    }

    @GetMapping("/tree")
    public ApiResponse<List<CategoryTreeResponse>> findTree() {
        return ApiResponse.ok(categoryService.findTree());
    }

    @GetMapping("/{id}")
    public ApiResponse<CategoryResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(categoryService.findById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryCreateRequest request) {
        return ApiResponse.ok(categoryService.create(request), "Category created successfully");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<CategoryResponse> update(@PathVariable Long id,
                                                 @Valid @RequestBody CategoryUpdateRequest request) {
        return ApiResponse.ok(categoryService.update(id, request), "Category updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ApiResponse.message("Category deleted successfully");
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/controller/CategoryController.java
git commit -m "feat(product-service): CategoryController with /tree endpoint"
```

---

### Task 25: `ProductController` + test

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/controller/ProductController.java`
- Create: `product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java`

- [ ] **Step 1: Create `ProductController`**

```java
package com.shop.productservice.controller;

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.ProductFilter;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<PageResponse<ProductSummaryResponse>> findAll(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long brandId,
            @RequestParam(required = false) ProductStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        ProductFilter filter = new ProductFilter(categoryId, brandId, status);
        Pageable pageable = PageRequest.of(page, size);
        return ApiResponse.ok(productService.findAll(filter, pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductDetailResponse> findById(@PathVariable Long id) {
        return ApiResponse.ok(productService.findById(id));
    }

    @GetMapping("/slug/{slug}")
    public ApiResponse<ProductDetailResponse> findBySlug(@PathVariable String slug) {
        return ApiResponse.ok(productService.findBySlug(slug));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductDetailResponse> create(@Valid @RequestBody ProductCreateRequest request) {
        return ApiResponse.ok(productService.create(request), "Product created successfully");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<ProductDetailResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody ProductUpdateRequest request) {
        return ApiResponse.ok(productService.update(id, request), "Product updated successfully");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.message("Product deleted successfully");
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.shop.productservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;   // Boot 4 package
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;          // Boot 4 package
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@AutoConfigureMockMvc(addFilters = false)   // see note in BrandControllerTest
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductService productService;

    private ProductDetailResponse sample() {
        return new ProductDetailResponse(1L, "iPhone 15", "iphone-15", null, "IP15-001",
            new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null, null, null,
            null, null);
    }

    @Test
    void findById_returns200WithApiResponse() throws Exception {
        when(productService.findById(1L)).thenReturn(sample());

        mockMvc.perform(get("/api/v1/products/{id}", 1L))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    void findBySlug_returns200() throws Exception {
        when(productService.findBySlug("iphone-15")).thenReturn(sample());

        mockMvc.perform(get("/api/v1/products/slug/{slug}", "iphone-15"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.slug").value("iphone-15"));
    }

    @Test
    void create_withInvalidDto_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest("", "", null, "",
            null, null, null, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails (compile error)**

- [ ] **Step 4: Controller already created in Step 1**

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl product-service -Dtest=ProductControllerTest`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/controller/ProductController.java \
        product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java
git commit -m "feat(product-service): ProductController with filter params + slug lookup"
```

---

## Phase 5  -  Outbox + Kafka

### Task 26: Replace `NoOpProductEventPublisher` with real `TransactionalProductEventPublisher`

**Files:**
- Modify: `product-service/src/main/java/com/shop/productservice/service/ProductEventPublisher.java` (interface only)
- Delete: `product-service/src/main/java/com/shop/productservice/service/NoOpProductEventPublisher.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java`

**Interfaces:**
- Consumes: `OutboxEventRepository`, `ObjectMapper`, `Product`
- Produces: writes `OutboxEvent` row in same `@Transactional` boundary

- [ ] **Step 1: Delete `NoOpProductEventPublisher`**

```bash
rm product-service/src/main/java/com/shop/productservice/service/NoOpProductEventPublisher.java
```

- [ ] **Step 2: Create `TransactionalProductEventPublisher`**

> The `ProductEventPublisher` interface already exists in package `service` (Task 22); retain its package location. The implementation belongs in `service.impls`. Do not make `ProductEventPublisher` package-private; Spring injects by interface type.

```java
package com.shop.productservice.service.impls;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.entity.Product;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.ProductEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionalProductEventPublisher implements ProductEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void publishCreated(Product product)  { save(product, "ProductCreated");  }
    @Override
    public void publishUpdated(Product product)  { save(product, "ProductUpdated");  }
    @Override
    public void publishDeleted(Product product) { save(product, "ProductDeleted"); }

    private void save(Product p, String eventType) {
        OutboxEvent e = new OutboxEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setAggregateType("Product");
        e.setAggregateId(p.getId());
        e.setEventType(eventType);
        e.setTopic("shop.product.lifecycle.v1");
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", e.getEventId());
        payload.put("eventType", eventType);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("productId", p.getId());
        payload.put("slug", p.getSlug());
        payload.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        try {
            e.setPayload(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize outbox event payload for product {}", p.getId(), ex);
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        e.setStatus(OutboxStatus.PENDING);
        e.setRetryCount(0);
        outboxRepository.save(e);
    }
}
```

- [ ] **Step 3: Verify existing ProductServiceImplTest still passes**

Run: `./mvnw test -pl product-service -Dtest=ProductServiceImplTest`
Expected: PASS (uses Mockito mock, so any ProductEventPublisher impl works)

- [ ] **Step 4: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/service/
git commit -m "feat(product-service): TransactionalProductEventPublisher writes OutboxEvent"
```

---

### Task 27: `OutboxRelay` + integration test

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java`
- Create: `product-service/src/test/java/com/shop/productservice/service/OutboxRelayIntegrationTest.java`
- Modify: `product-service/pom.xml` (add `kafka-junit` is not needed; use Testcontainers Kafka + spring-kafka-test)

- [ ] **Step 1: Add Testcontainers Kafka to `product-service/pom.xml`**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Create `OutboxRelay`**

```java
package com.shop.productservice.service;

import com.shop.common.kafka.producer.KafkaMessagePublisher;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.repository.OutboxEventRepository;
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
public class OutboxRelay {

    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;

    @Value("${product.outbox.batch-size:100}")
    private int batchSize;

    @Value("${product.outbox.max-retries:10}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval-ms:5000}")
    public void relay() {
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        if (pending.isEmpty()) return;

        log.info("Relaying {} outbox event(s)", pending.size());
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(event.getTopic(),
                    String.valueOf(event.getAggregateId()),
                    event.getPayload());
                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                event.setLastError(null);
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
            }
            outboxRepo.save(event);
        }
    }
}
```

- [ ] **Step 3: Enable scheduling in `ProductServiceApplication.java`**

Add `@EnableScheduling` annotation:

```java
@SpringBootApplication
@EnableScheduling
public class ProductServiceApplication { ... }
```

- [ ] **Step 4: Write the failing integration test**

```java
package com.shop.productservice.service;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.ProductService;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class OutboxRelayIntegrationTest extends AbstractIntegrationTest {

    @Autowired ProductService productService;
    @Autowired OutboxEventRepository outboxRepo;

    @Test
    void relay_publishesProductCreatedEventToKafka() {
        // 1. Trigger a product create → writes OutboxEvent
        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", null,
            "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null);
        productService.create(req);

        // 2. Manually trigger relay (don't wait for @Scheduled)
        outboxRelay().relay();

        // 3. Assert OutboxEvent marked SENT
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long pendingCount = outboxRepo.countByStatus(OutboxStatus.PENDING);
            long sentCount    = outboxRepo.countByStatus(OutboxStatus.SENT);
            assertThat(pendingCount).isEqualTo(0);
            assertThat(sentCount).isGreaterThanOrEqualTo(1);
        });
    }

    private OutboxRelay outboxRelay() {
        return applicationContext.getBean(OutboxRelay.class);
    }
}
```

- [ ] **Step 5: Verify integration test runs**

Run: `./mvnw test -pl product-service -Dtest=OutboxRelayIntegrationTest`
Expected: PASS (assuming Task 28 base class is set up; if not, defer to Task 28 then re-run)

- [ ] **Step 6: Commit**

```bash
git add product-service/pom.xml \
        product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java \
        product-service/src/main/java/com/shop/productservice/ProductServiceApplication.java \
        product-service/src/test/java/com/shop/productservice/service/OutboxRelayIntegrationTest.java
git commit -m "feat(product-service): OutboxRelay @Scheduled poller with Kafka publish"
```

---

### Task 28: `AbstractIntegrationTest` base class

**Files:**
- Create: `product-service/src/test/java/com/shop/productservice/support/AbstractIntegrationTest.java`

- [ ] **Step 1: Create base class**

```java
package com.shop.productservice.support;

import com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(JpaAuditingAutoConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
        .withDatabaseName("product_test")
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
        registry.add("spring.cache.type", () -> "none");  // disable cache in tests
    }

    @Autowired
    protected ApplicationContext applicationContext;
}
```

> `@SpringBootTest` loads `LiquibaseAutoConfiguration` automatically in the full application context; `@Import(LiquibaseAutoConfiguration.class)` is not required unlike `@DataJpaTest` slices.

- [ ] **Step 2: Verify pom.xml contains required dependencies**

Task 17 Step 1 added `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:kafka`, and `spring-kafka-test` to `product-service/pom.xml`. If running integration tests prior to Task 17, add `awaitility` separately:

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

Verify: `./mvnw -pl product-service dependency:resolve` → BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add product-service/src/test/java/com/shop/productservice/support/AbstractIntegrationTest.java \
        product-service/pom.xml
git commit -m "test(product-service): AbstractIntegrationTest base with Testcontainers"
```

---

## Phase 6  -  Observability

### Task 29: `ProductMetrics`

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/service/ProductMetrics.java`

- [ ] **Step 1: Implement `ProductMetrics`**

```java
package com.shop.productservice.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ProductMetrics {

    private final Counter cacheHit;
    private final Counter cacheMiss;
    private final Counter eventsPublished;
    private final Timer relayDuration;
    private final AtomicInteger pendingOutboxCount = new AtomicInteger(0);

    public ProductMetrics(MeterRegistry registry) {
        this.cacheHit = Counter.builder("product.cache.hit").register(registry);
        this.cacheMiss = Counter.builder("product.cache.miss").register(registry);
        this.eventsPublished = Counter.builder("product.events.published").register(registry);
        this.relayDuration = Timer.builder("product.outbox.relay.duration").register(registry);
        Gauge.builder("product.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);
    }

    public void recordCacheHit()  { cacheHit.increment(); }
    public void recordCacheMiss() { cacheMiss.increment(); }
    public void recordEventPublished(String eventType) {
        eventsPublished.tag("event_type", eventType).increment();
    }
    public void recordRelayDuration(Duration d) { relayDuration.record(d); }
    public void setPendingOutboxCount(int n)    { pendingOutboxCount.set(n); }
}
```

- [ ] **Step 2: Wire into `ProductServiceImpl` (optional  -  counters require aspect or manual call)**

For simplicity in this phase, wire only `eventsPublished` into `TransactionalProductEventPublisher`. Skip cache hit/miss wiring (requires `@Aspect` or manual instrumentation in service).

- [ ] **Step 3: Wire into `TransactionalProductEventPublisher`**

Inject `ProductMetrics` and call `recordEventPublished(eventType)` in `save()`.

- [ ] **Step 4: Wire into `OutboxRelay`**

Inject `ProductMetrics`, wrap `relay()` body in `Timer.Sample`, call `setPendingOutboxCount(pending.size())`.

- [ ] **Step 5: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/service/ProductMetrics.java \
        product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java \
        product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java
git commit -m "feat(product-service): ProductMetrics wired into publisher + relay"
```

---

## Phase 7  -  docker-compose + smoke test

### Task 30: Update `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`

> The repository includes a `product-service` block (port `8086:8086`, env `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/productservice`). Do not create a separate `postgres-product` container: the repository shares one Postgres instance with database initialization scripts (`docker/postgres/init/create-all-databases.sql`).

- [ ] **Step 1: Verify existing `product-service:` configuration**

Ensure `product-service:` in `docker-compose.yml` specifies the following environment variables:
- `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/productservice` (existing)
- `SPRING_DATA_REDIS_HOST: redis` (add if missing)
- `SPRING_DATA_REDIS_PORT: 6379` (add if missing)
- `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` (add using prefix `SHOP_KAFKA_*` for common-kafka, not `SPRING_KAFKA_*`)
- `SERVER_PORT: 8086` (existing or add)

Along with `depends_on`:
- `postgres: condition: service_healthy` (existing)
- `redis: condition: service_healthy` (add)
- `kafka: condition: service_healthy` (add)

Reference snippet (if replacing the block entirely):

```yaml
  product-service:
    image: product-service:latest
    container_name: product-service
    <<: [*restart, *logging]
    ports:
      - "8086:8086"
    environment:
      <<: [*jwt, *pg-creds]
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/productservice
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SERVER_PORT: 8086
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      <<: *hc-defaults
      test: ["CMD-SHELL", "wget -qO- http://localhost:8086/actuator/health > /dev/null 2>&1 || exit 1"]
    networks:
      - ecommerce-network
```

- [ ] **Step 2: Verify Kafka and Redis exist in compose** (existing: `redis:7.4-alpine`, `apache/kafka:3.9.0` earlier in file)

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "build(docker): wire product-service to redis + kafka (shop.kafka.*); reuse postgres"
```

---

### Task 31: End-to-end smoke verification

**Files:** none

- [ ] **Step 1: Build product-service JAR**

Run: `./mvnw clean package -pl product-service -DskipTests`
Expected: BUILD SUCCESS, JAR at `product-service/target/product-service-*.jar`

- [ ] **Step 2: Bring up infra**

Run: `docker compose up -d postgres redis kafka product-service`   (No postgres-product container; shared Postgres)
Expected: all containers start, `product-service` connects to all dependencies

- [ ] **Step 3: Verify Liquibase ran**

Run: `docker compose logs product-service | grep -i liquibase`
Expected: "Successfully acquired change log lock" and "Update committed" for changelog-001

- [ ] **Step 4: Hit health endpoint** (port 8086 theo compose)

Run: `curl http://localhost:8086/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 5: Create a category via gateway (with admin token)**

```bash
# 1. Get admin token from auth-service
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin"}' | jq -r .data.accessToken)

# 2. Create category
curl -X POST http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Phones","slug":"phones"}'
```

Expected: 200 OK with category data wrapped in `ApiResponse`

- [ ] **Step 6: Create a brand similarly**

- [ ] **Step 7: Create a product**

```bash
curl -X POST http://localhost:8080/api/v1/products \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"iPhone 15",
    "slug":"iphone-15",
    "description":"Latest iPhone",
    "sku":"IP15-001",
    "priceUnit":999.00,
    "quantity":10,
    "status":"ACTIVE",
    "categoryId":1,
    "brandId":1
  }'
```

Expected: 200 OK

- [ ] **Step 8: Read product back (anonymous)**

```bash
curl http://localhost:8080/api/v1/products/1
```

Expected: 200 OK with full product detail (denormalized categoryTitle, brandName)

- [ ] **Step 9: Verify Kafka event published**

Run: `docker-compose exec kafka kafka-console-consumer --bootstrap-server kafka:9092 --topic shop.product.lifecycle.v1 --from-beginning --max-messages 1`
Expected: JSON payload matching the published event

- [ ] **Step 10: Verify Prometheus metrics**

Run: `curl http://localhost:8086/actuator/prometheus | grep product_`
Expected: `product_events_published_total`, `product_outbox_pending_count`

- [ ] **Step 11: Stop containers**

Run: `docker-compose down`

---

### Task 32: Final cleanup + commit

**Files:** possibly tweak README or ROADMAP

- [ ] **Step 1: Run full reactor build one last time**

Run: `./mvnw clean test`
Expected: BUILD SUCCESS across all modules

- [ ] **Step 2: Update `ROADMAP.md` to mark Phase 7 product-service as done**

Find `ROADMAP.md`, update status.

- [ ] **Step 3: Commit**

```bash
git add docs/ROADMAP.md
git commit -m "docs: mark product-service phase complete"
```

---

## Self-Review

**1. Spec coverage:**

| Spec section | Task(s) |
|---|---|
| §1 Overview | Tasks 7-9 (skeleton), Task 30 (docker) |
| §2 Architecture | All Phase 1-5 tasks |
| §3 Data model | Tasks 11-15 (entities), Task 10 (Liquibase); Task 1 (`AbstractMappedEntity extends SoftDeletable`) |
| §4 Repository | Tasks 16-17 (@SQLRestriction, no `*AndDeletedFalse` suffix) |
| §5 Service | Tasks 20-22, 26-27 (factories + markDeleted + AuditorAware) |
| §6 Controller | Tasks 23-25 |
| §7 Common upgrades + Config | Task 1 (AbstractMappedEntity + ErrorCode + i18n keys), Task 2 (JpaAuditingAutoConfiguration), Tasks 3-4 (EndpointRule + platform defaults), Task 5 (auth + gateway yml), Task 9 (CacheConfig) |
| §8 Observability | Task 29 (ProductMetrics) |
| §9 Error handling | Task 1 (product ErrorCodes + i18n) + factories `BusinessException.of/notFound/conflict` |
| §10 Testing | Task 1 (AbstractMappedEntityTest: `@DataJpaTest` Boot 4 package `org.springframework.boot.data.jpa.test.autoconfigure.*`, `TestEntityManager` in `org.springframework.boot.jpa.test.autoconfigure.*`, `@Autowired` field), Task 17 (ProductRepository: Boot 4 packages with `@Import(LiquibaseAutoConfiguration.class)`), Tasks 20-22 (Service), Tasks 23+25 (Controller: `@WebMvcTest` Boot 4 package with `@MockitoBean` and `addFilters=false`), Task 27 (Outbox integration) |
| §11 Implementation order | Tasks 1-31 follow Phases 0-6 |
| §12 Deferred items | Documented in spec, not in plan (intentional) |

**2. Placeholder scan:** No `TBD`, `TODO`, `implement later`, `fill in`. All code shown.

**3. Type consistency:** Names match across tasks (`ProductDetailResponse`, `productBySlug` cache, `OutboxStatus.PENDING`, etc.).

---

**Plan complete and saved to `docs/superpowers/plans/2026-08-26-product-service.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?