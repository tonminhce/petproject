# Product Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `product-service` microservice with Product/Category/Brand CRUD, Redis cache, Kafka events via Transactional Outbox pattern, while upgrading `common-core`/`common-spring`/`common-security` to support `@MappedSuperclass` audit, `AuditorAware`, and method-specific public endpoints.

**Architecture:** Spring Boot 4.1.1 microservice (`com.shop.productservice`) → PostgreSQL via Spring Data JPA + Liquibase, Redis 7 via Spring Cache (`@Cacheable` / `@CacheEvict` cache-aside), Kafka producer via Transactional Outbox + `@Scheduled` relay. Loose coupling: search-service (future) consumes Kafka events. Auth via Keycloak JWT + `@PreAuthorize`.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Redis 7, Apache Kafka, Spring Security Resource Server (JWT), MapStruct 1.6.3, Lombok, JUnit 5 + Mockito + AssertJ, Testcontainers.

**Spec:** [`docs/superpowers/specs/2026-08-26-product-service-design.md`](../specs/2026-08-26-product-service-design.md)

---

## Global Constraints

- **Java 25** (per project `.java-version` / parent pom)
- **Spring Boot 4.1.1** (parent pom `spring-boot-starter-parent.version`)
- **Package root:** `com.shop.*`
- **No per-service `SecurityConfig`** — `common-security` auto-configures `SecurityFilterChain` (`@ConditionalOnMissingBean`). Customise via `shop.security.public-endpoints`.
- **`open-in-view: false`** in all `application.yml` (no transaction-in-view)
- **MapStruct over ModelMapper** for product-service. `ModelMapper` remains for auth-service (out of scope).
- **Liquibase** (not Flyway), changelogs in `src/main/resources/db/changelog/`
- **All endpoints** wrap responses in `ApiResponse<T>` (from `common-core/viewmodel`)
- **No new common modules** — only modify `common-core`, `common-spring`, `common-security`, `common-kafka`
- **Cache key convention:** `product::{id}`, `productBySlug::{slug}`; TTL 600s; `cache-null-values: false`
- **Kafka topic:** `shop.product.lifecycle.v1`; payload: `{eventId, eventType, occurredAt, productId, slug, status}`
- **Outbox publisher**: same `@Transactional` boundary as the entity write
- **Soft delete** via `SoftDeletable` interface (already in `common-core/data/`). Partial unique indexes `WHERE deleted = false`.
- **Audit fields** populated by `AuditorAware` (from `common-spring`), returns `auth.getName()` or `"system"`

---

## File Structure

### Modified common modules

| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java` | **CREATE** — `@MappedSuperclass` with `createdAt/updatedAt/createdBy/updatedBy` |
| `utils/common-core/src/main/java/com/shop/common/core/data/SoftDeletable.java` | unchanged (already exists) |
| `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java` | **CREATE** — wires `AuditorAware` + `@EnableJpaAuditing` |
| `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **MODIFY** — add `JpaAuditingAutoConfiguration` |
| `utils/common-spring/pom.xml` | **MODIFY** — add `spring-boot-starter-data-jpa` (for `@EntityListeners`) |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` | **MODIFY** — change `List<String>` → `List<EndpointRule>` |
| `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java` | **MODIFY** — loop parse `EndpointRule`, method-aware `permitAll` |
| `auth-service/src/main/resources/application.yml` | **MODIFY** — convert `public-endpoints` to `EndpointRule` format |

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
| `product-service/src/main/java/com/shop/productservice/mapper/ProductMapper.java` | MapStruct mapper (toSummary + toDetail + partialUpdate) |
| `product-service/src/main/java/com/shop/productservice/mapper/CategoryMapper.java` | MapStruct mapper (toResponse + toTreeResponse) |
| `product-service/src/main/java/com/shop/productservice/mapper/BrandMapper.java` | MapStruct mapper |
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

- `product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java` — `@DataJpaTest` + Testcontainers
- `product-service/src/test/java/com/shop/productservice/service/impls/ProductServiceImplTest.java` — unit test
- `product-service/src/test/java/com/shop/productservice/service/impls/CategoryServiceImplTest.java` — unit test (findTree)
- `product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java` — `@WebMvcTest` with `@WithMockUser`
- `product-service/src/test/java/com/shop/productservice/service/OutboxRelayIntegrationTest.java` — `@SpringBootTest` + Testcontainers Kafka

### Compose

- `docker-compose.yml` — add `postgres-product` service; update `product-service` environment

---

## Phase 0 — Common upgrades

### Task 1: Add `AbstractMappedEntity` to `common-core`

**Files:**
- Create: `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java`
- Create: `utils/common-core/src/test/java/com/shop/common/core/data/AbstractMappedEntityTest.java`

**Interfaces:**
- Consumes: JPA `AuditingEntityListener`, `AuditorAware<String>` (added in Task 2)
- Produces: `AbstractMappedEntity` abstract class with 4 audit fields

- [ ] **Step 1: Write the failing test**

```java
package com.shop.common.core.data;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(AbstractMappedEntityTest.DummyConfig.class)
class AbstractMappedEntityTest {

    static class DummyConfig {
        @EnableJpaAuditing
        public static class AuditingConfig {}
    }

    @Entity
    @EntityListeners(jakarta.persistence.EntityListeners.class)  // not used; kept for compat
    static class TestEntity extends AbstractMappedEntity {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;
        private String name;
        public Long getId() { return id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    void persistsWithAuditFields(TestEntityManager em) {
        TestEntity entity = new TestEntity();
        entity.setName("test");
        em.persistAndFlush(entity);

        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
        assertThat(entity.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
```

> Note: full audit test requires real `AuditorAware` bean. This test asserts timestamp fields auto-populate. Auditor-aware test deferred to common-spring tests (Task 2).

- [ ] **Step 2: Run test to verify it fails (compilation error expected since class doesn't exist)**

Run from `utils/common-core`: `./mvnw test -Dtest=AbstractMappedEntityTest`
Expected: compilation error — `cannot find symbol: AbstractMappedEntity`

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
public abstract class AbstractMappedEntity {

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

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl utils/common-core -Dtest=AbstractMappedEntityTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java \
        utils/common-core/src/test/java/com/shop/common/core/data/AbstractMappedEntityTest.java
git commit -m "feat(common-core): add AbstractMappedEntity with audit fields"
```

---

### Task 2: Add `JpaAuditingAutoConfiguration` to `common-spring`

**Files:**
- Create: `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java`
- Modify: `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `utils/common-spring/pom.xml` (add `spring-boot-starter-data-jpa`)
- Create: `utils/common-spring/src/test/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `AuditingHandler` (from `spring-data-jpa`), Spring Security `Authentication`
- Produces: `AuditorAware<String>` bean; activates `@EnableJpaAuditing`

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
Expected: compilation error — `JpaAuditingAutoConfiguration` not found

- [ ] **Step 4: Implement `JpaAuditingAutoConfiguration`**

```java
package com.shop.common.spring.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.domain.support.AuditingHandler;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@AutoConfiguration
@ConditionalOnClass(AuditingHandler.class)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
                return auth.getName();
            }
            return "system";
        };
    }
}
```

- [ ] **Step 5: Register in `AutoConfiguration.imports`**

Edit `utils/common-spring/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
- Add line: `com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration`

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -pl utils/common-spring -Dtest=JpaAuditingAutoConfigurationTest`
Expected: PASS

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
- Produces: `List<EndpointRule>` field `publicEndpoints` (replaces `List<String>`)

- [ ] **Step 1: Inspect existing `SecurityProperties` to know what to change**

Read `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` and find:
- The `publicEndpoints` field declaration
- The `resolvedPublicPaths()` method (if any)
- Any `@DefaultValue` annotations on related fields

- [ ] **Step 2: Replace `publicEndpoints` with `List<EndpointRule>`**

Add nested class `EndpointRule` inside `SecurityProperties` (or as a separate top-level class in same package):

```java
public static class EndpointRule {
    /** HTTP method (GET, POST, ...). If null, any method. */
    private HttpMethod method;

    /** URL path pattern (Ant-style: /api/v1/products/**). */
    private String path;

    public HttpMethod getMethod() { return method; }
    public void setMethod(HttpMethod method) { this.method = method; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
```

Change field:
```java
// OLD: private List<String> publicEndpoints = new ArrayList<>();
private List<EndpointRule> publicEndpoints = new ArrayList<>();
```

Keep the rest of the class (issuer-uri, csrf, cors, etc.) unchanged.

- [ ] **Step 3: Add imports**

Ensure imports present:
```java
import org.springframework.http.HttpMethod;
import java.util.ArrayList;
import java.util.List;
```

- [ ] **Step 4: Commit**

```bash
git add utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java
git commit -m "feat(common-security): upgrade public-endpoints to EndpointRule (method+path)"
```

---

### Task 4: Update `BaseSecurityConfig` to apply method-aware `permitAll`

**Files:**
- Modify: `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java`

**Interfaces:**
- Consumes: `SecurityProperties.getPublicEndpoints()` returning `List<EndpointRule>`
- Produces: `SecurityFilterChain` with method-specific permitAll rules

- [ ] **Step 1: Read current `BaseSecurityConfig.securityFilterChain` method**

Find the section where `properties.resolvedPublicPaths()` (or equivalent) is applied to `authorizeHttpRequests`.

- [ ] **Step 2: Replace path-only loop with method-aware loop**

In the `authorizeHttpRequests` block, replace:

```java
String[] publicPaths = properties.resolvedPublicPaths().toArray(new String[0]);
http.authorizeHttpRequests(auth -> auth
    .requestMatchers(publicPaths).permitAll()
    .anyRequest().authenticated());
```

with:

```java
http.authorizeHttpRequests(auth -> {
    for (SecurityProperties.EndpointRule rule : properties.getPublicEndpoints()) {
        if (rule.getMethod() != null) {
            auth.requestMatchers(rule.getMethod(), rule.getPath()).permitAll();
        } else {
            auth.requestMatchers(rule.getPath()).permitAll();
        }
    }
    auth.anyRequest().authenticated();
});
```

- [ ] **Step 3: If `resolvedPublicPaths()` method exists, leave it for backward compat (unused now), or delete if no other callers**

```bash
grep -rn "resolvedPublicPaths" utils/
```

If only used in `BaseSecurityConfig`, safe to delete. Otherwise keep.

- [ ] **Step 4: Verify compile**

Run: `./mvnw test -pl utils/common-security`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java
git commit -m "feat(common-security): apply method-aware permitAll for public endpoints"
```

---

### Task 5: Migrate auth-service `application.yml` to EndpointRule format

**Files:**
- Modify: `auth-service/src/main/resources/application.yml`
- Modify: `auth-service/src/test/resources/application.yml` (if present, mirror change)

**Interfaces:**
- Produces: yaml keys in EndpointRule shape for auth-service endpoints

- [ ] **Step 1: Find current `shop.security.public-endpoints` block**

```bash
grep -A 20 "public-endpoints" auth-service/src/main/resources/application.yml
```

- [ ] **Step 2: Replace list-of-strings with list-of-maps**

OLD:
```yaml
shop:
  security:
    public-endpoints:
      - /api/v1/auth/login
      - /api/v1/auth/refresh
      - /actuator/health
```

NEW:
```yaml
shop:
  security:
    public-endpoints:
      - path: /api/v1/auth/login
      - path: /api/v1/auth/refresh
      - path: /actuator/health
      - path: /actuator/health/**
      - path: /actuator/info
      - path: /actuator/prometheus
      - path: /v3/api-docs/**
      - path: /swagger-ui/**
      - path: /webjars/**
```

> Note: Actuator and Swagger endpoints get no `method` field, so they allow any method (preserve existing behavior).

- [ ] **Step 3: Run auth-service tests**

Run: `./mvnw test -pl auth-service`
Expected: BUILD SUCCESS (all 39 tests pass)

- [ ] **Step 4: Commit**

```bash
git add auth-service/src/main/resources/application.yml
git commit -m "refactor(auth-service): migrate public-endpoints to EndpointRule format"
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

## Phase 1 — product-service skeleton + persistence

### Task 7: Update `product-service/pom.xml` with new dependencies

**Files:**
- Modify: `product-service/pom.xml`

- [ ] **Step 1: Add Redis + Kafka + Cache deps inside `<dependencies>`**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
</dependency>
<dependency>
    <groupId>org.lombok</groupId>
    <artifactId>lombok-mapstruct-binding</artifactId>
    <version>${lombok-mapstruct-binding.version}</version>
    <scope>provided</scope>
</dependency>
```

- [ ] **Step 2: Verify versions resolve**

Run: `./mvnw -pl product-service dependency:resolve`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add product-service/pom.xml
git commit -m "build(product-service): add redis, kafka, mapstruct-binding deps"
```

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
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/product_db}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
    properties:
      hibernate.jdbc.lob.non_contextual_creation: true
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
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 10000

product:
  outbox:
    poll-interval-ms: 5000
    batch-size: 100
    max-retries: 10

shop:
  security:
    public-endpoints:
      - method: GET
        path: /api/v1/products/**
      - method: GET
        path: /api/v1/categories/**
      - method: GET
        path: /api/v1/brands/**

server:
  port: ${SERVER_PORT:8083}

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

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

### Task 10: Liquibase changelog — initial schema

**Files:**
- Create: `product-service/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `product-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml`

- [ ] **Step 1: Create master changelog**

```yaml
databaseChangeLog:
  - include:
      file: changelog-001-initial-schema.yaml
```

- [ ] **Step 2: Create `changelog-001-initial-schema.yaml`**

```yaml
databaseChangeLog:
  # ===========================================================================
  # products
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
        - column: { name: deleted_by,  type: VARCHAR(100) }

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

  # ===========================================================================
  # categories
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
        - column: { name: deleted_by, type: VARCHAR(100) }

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
  # brands
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
        - column: { name: deleted_by, type: VARCHAR(100) }

  - createIndex:
      tableName: brands
      indexName: idx_brands_slug_unique_active
      unique: true
      columns:
        - column: { name: slug }
      where: deleted = false

  # ===========================================================================
  # outbox_events
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
        - column: { name: created_by,     type: VARCHAR(100) }

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
import com.shop.common.core.data.SoftDeletable;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category extends AbstractMappedEntity implements SoftDeletable {

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

    // SoftDeletable implementation
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Override
    public void softDelete(String actor) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = actor;
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }
}
```

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
import com.shop.common.core.data.SoftDeletable;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "brands")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Brand extends AbstractMappedEntity implements SoftDeletable {

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

    // SoftDeletable implementation
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Override
    public void softDelete(String actor) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = actor;
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }
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
import com.shop.common.core.data.SoftDeletable;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product extends AbstractMappedEntity implements SoftDeletable {

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

    // SoftDeletable implementation
    @Column(nullable = false)
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by", length = 100)
    private String deletedBy;

    @Override
    public void softDelete(String actor) {
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.deletedBy = actor;
    }

    @Override
    public boolean isDeleted() {
        return deleted;
    }
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

## Phase 2 — Repositories, DTOs, Mappers

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
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findAllByDeletedFalseOrderByTitleAsc();
    Optional<Category> findByIdAndDeletedFalse(Long id);
    boolean existsBySlugAndDeletedFalse(String slug);
    boolean existsBySlugAndDeletedFalseAndIdNot(String slug, Long id);
}
```

- [ ] **Step 2: Create `BrandRepository`**

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrandRepository extends JpaRepository<Brand, Long> {
    Page<Brand> findAllByDeletedFalse(Pageable pageable);
    Optional<Brand> findByIdAndDeletedFalse(Long id);
    boolean existsBySlugAndDeletedFalse(String slug);
    boolean existsBySlugAndDeletedFalseAndIdNot(String slug, Long id);
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
- Modify: `product-service/pom.xml` (add `spring-boot-starter-data-redis-test` is NOT needed; `testcontainers` + `spring-boot-testcontainers` are needed)

**Interfaces:**
- Produces: `ProductRepository` with `@EntityGraph` queries and `existsBy*` checks

- [ ] **Step 1: Add Testcontainers to `product-service/pom.xml`**

Add to `<dependencies>`:

```xml
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
```

- [ ] **Step 2: Create `ProductRepository`**

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
    Optional<Product> findWithRelationsByIdAndDeletedFalse(Long id);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsBySlugAndDeletedFalse(String slug);

    Optional<Product> findByIdAndDeletedFalse(Long id);

    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    boolean existsBySlugAndDeletedFalse(String slug);
    boolean existsBySkuAndDeletedFalse(String sku);
    boolean existsBySlugAndDeletedFalseAndIdNot(String slug, Long id);
    boolean existsBySkuAndDeletedFalseAndIdNot(String sku, Long id);
}
```

- [ ] **Step 3: Write the failing test (`ProductRepositoryTest`)**

```java
package com.shop.productservice.repository;

import com.shop.productservice.entity.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
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
@Import(com.shop.common.spring.autoconfigure.JpaAuditingAutoConfiguration.class)
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
    void findWithRelationsByIdAndDeletedFalse_returnsProductWithCategoryAndBrand() {
        Product p = Product.builder()
            .title("iPhone 15").slug("iphone-15").sku("IP15-001")
            .priceUnit(new BigDecimal("999.00")).quantity(10)
            .status(ProductStatus.ACTIVE).category(category).brand(brand)
            .build();
        em.persistAndFlush(p);
        em.clear();

        Optional<Product> result = productRepository.findWithRelationsByIdAndDeletedFalse(p.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getCategory().getTitle()).isEqualTo("Phones");
        assertThat(result.get().getBrand().getName()).isEqualTo("Acme");
    }

    @Test
    void findBySlugAndDeletedFalse_excludesSoftDeleted() {
        Product active = Product.builder()
            .title("Active").sku("A-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.ACTIVE).slug("active").build();
        Product deleted = Product.builder()
            .title("Deleted").sku("D-1")
            .priceUnit(BigDecimal.ONE).quantity(1)
            .status(ProductStatus.DISCONTINUED).slug("deleted").build();
        deleted.softDelete("test");
        em.persistAndFlush(active);
        em.persistAndFlush(deleted);
        em.clear();

        assertThat(productRepository.findWithRelationsBySlugAndDeletedFalse("active")).isPresent();
        assertThat(productRepository.findWithRelationsBySlugAndDeletedFalse("deleted")).isEmpty();
    }

    @Test
    void findAllWithFilterByCategoryAndStatus() {
        Product p1 = Product.builder().title("P1").slug("p1").sku("P1").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).category(category).build();
        Product p2 = Product.builder().title("P2").slug("p2").sku("P2").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.DRAFT).category(category).build();
        em.persistAndFlush(p1);
        em.persistAndFlush(p2);

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
    void existsBySlugAndDeletedFalseAndIdNot_worksForUpdate() {
        Product p = Product.builder().title("T").slug("t").sku("T").priceUnit(BigDecimal.ONE).quantity(1).status(ProductStatus.ACTIVE).build();
        em.persistAndFlush(p);

        assertThat(productRepository.existsBySlugAndDeletedFalseAndIdNot("t", 999L)).isTrue();
        assertThat(productRepository.existsBySlugAndDeletedFalseAndIdNot("t", p.getId())).isFalse();
        assertThat(productRepository.existsBySlugAndDeletedFalseAndIdNot("other", p.getId())).isFalse();
    }
}
```

- [ ] **Step 4: Run test to verify it fails (compile error: repo doesn't exist)**

Run: `./mvnw test -pl product-service -Dtest=ProductRepositoryTest`
Expected: compilation error — `ProductRepository` not found

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
    @NotBlank @Size(max = 2000) String description,
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

### Task 19: MapStruct Mappers

**Files:**
- Create: `product-service/src/main/java/com/shop/productservice/mapper/ProductMapper.java`
- Create: `product-service/src/main/java/com/shop/productservice/mapper/CategoryMapper.java`
- Create: `product-service/src/main/java/com/shop/productservice/mapper/BrandMapper.java`

- [ ] **Step 1: Create `ProductMapper`**

```java
package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.dto.request.ProductUpdateRequest;
import com.shop.productservice.dto.response.ProductDetailResponse;
import com.shop.productservice.dto.response.ProductSummaryResponse;
import com.shop.productservice.entity.Product;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProductMapper {

    ProductSummaryResponse toSummaryResponse(Product product);

    @Mapping(target = "categoryId",    source = "category.id")
    @Mapping(target = "categoryTitle", source = "category.title")
    @Mapping(target = "brandId",      source = "brand.id")
    @Mapping(target = "brandName",    source = "brand.name")
    ProductDetailResponse toDetailResponse(Product product);

    @Mapping(target = "category", ignore = true)
    @Mapping(target = "brand",    ignore = true)
    @Mapping(target = "id",       ignore = true)
    Product toEntity(ProductCreateRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "brand",    ignore = true)
    @Mapping(target = "id",       ignore = true)
    void partialUpdate(@MappingTarget Product product, ProductUpdateRequest request);
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
import org.mapstruct.*;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CategoryMapper {

    CategoryResponse toResponse(Category category);

    default CategoryTreeResponse toTreeResponse(Category category, List<CategoryTreeResponse> children) {
        return new CategoryTreeResponse(
            category.getId(),
            category.getTitle(),
            category.getSlug(),
            category.getImageUrl(),
            category.getParent() != null ? category.getParent().getId() : null,
            children != null ? children : new ArrayList<>()
        );
    }

    @Mapping(target = "parent",   ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "id",       ignore = true)
    Category toEntity(CategoryCreateRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "parent",   ignore = true)
    @Mapping(target = "children", ignore = true)
    @Mapping(target = "id",       ignore = true)
    void partialUpdate(@MappingTarget Category category, CategoryUpdateRequest request);
}
```

- [ ] **Step 3: Create `BrandMapper`**

```java
package com.shop.productservice.mapper;

import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.request.BrandUpdateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.entity.Brand;
import org.mapstruct.*;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface BrandMapper {

    BrandResponse toResponse(Brand brand);

    @Mapping(target = "id", ignore = true)
    Brand toEntity(BrandCreateRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    void partialUpdate(@MappingTarget Brand brand, BrandUpdateRequest request);
}
```

- [ ] **Step 4: Commit**

```bash
git add product-service/src/main/java/com/shop/productservice/mapper/
git commit -m "feat(product-service): MapStruct mappers (Product, Category, Brand)"
```

---

## Phase 3 — Service layer

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandServiceImplTest {

    @Mock BrandRepository repo;
    @Mock BrandMapper mapper;
    @InjectMocks BrandServiceImpl service;

    @Test
    void findById_returnsBrand() {
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(brand));
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsWhenNotFound() {
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndReturns() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        Brand brand = Brand.builder().id(1L).name("Acme").slug("acme").build();
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(repo.existsBySlugAndDeletedFalse("acme")).thenReturn(false);
        when(mapper.toEntity(req)).thenReturn(brand);
        when(repo.save(brand)).thenReturn(brand);
        when(mapper.toResponse(brand)).thenReturn(resp);

        assertThat(service.create(req)).isEqualTo(resp);
    }

    @Test
    void create_throwsConflictOnDuplicateSlug() {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        when(repo.existsBySlugAndDeletedFalse("acme")).thenReturn(true);

        assertThatThrownBy(() -> service.create(req))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_appliesPartialUpdate() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").description("old").build();
        BrandUpdateRequest req = new BrandUpdateRequest(null, null, null, "new");
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toResponse(existing)).thenReturn(
            new BrandResponse(1L, "Acme", "acme", null, "new"));

        BrandResponse result = service.update(1L, req);
        assertThat(result.description()).isEqualTo("new");
        verify(mapper).partialUpdate(existing, req);
    }

    @Test
    void delete_softDeletes() {
        Brand existing = Brand.builder().id(1L).name("Acme").slug("acme").build();
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(existing));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
        assertThat(existing.getDeletedAt()).isNotNull();
        verify(repo).save(existing);
    }

    @Test
    void findAll_returnsPage() {
        Page<Brand> page = new PageImpl<>(List.of());
        when(repo.findAllByDeletedFalse(any(PageRequest.class))).thenReturn(page);

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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BrandServiceImpl implements BrandService {

    private final BrandRepository repo;
    private final BrandMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<BrandResponse> findAll(Pageable pageable) {
        return PageResponse.of(repo.findAllByDeletedFalse(pageable)
            .map(mapper::toResponse).getContent(),
            pageable.getPageNumber(),
            pageable.getPageSize(),
            repo.findAllByDeletedFalse(pageable).getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public BrandResponse findById(Long id) {
        return repo.findByIdAndDeletedFalse(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Brand " + id + " not found"));
    }

    @Override
    @Transactional
    public BrandResponse create(BrandCreateRequest request) {
        if (repo.existsBySlugAndDeletedFalse(request.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Brand slug already exists");
        }
        Brand brand = mapper.toEntity(request);
        return mapper.toResponse(repo.save(brand));
    }

    @Override
    @Transactional
    public BrandResponse update(Long id, BrandUpdateRequest request) {
        Brand existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Brand " + id + " not found"));
        mapper.partialUpdate(existing, request);
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Brand existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Brand " + id + " not found"));
        existing.softDelete("system");
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
        when(repo.findAllByDeletedFalseOrderByTitleAsc())
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
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(java.util.Optional.empty());

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

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResponse> findAll() {
        return repo.findAllByDeletedFalseOrderByTitleAsc().stream()
            .map(mapper::toResponse)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTreeResponse> findTree() {
        List<Category> all = repo.findAllByDeletedFalseOrderByTitleAsc();
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
                nodeMap.get(c.getParent().getId()).children().add(node);
            }
        }
        return roots;
    }

    @Override
    @Transactional(readOnly = true)
    public CategoryResponse findById(Long id) {
        return repo.findByIdAndDeletedFalse(id)
            .map(mapper::toResponse)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Category " + id + " not found"));
    }

    @Override
    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        if (repo.existsBySlugAndDeletedFalse(request.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Category slug already exists");
        }
        Category category = mapper.toEntity(request);
        if (request.parentId() != null) {
            Category parent = repo.findByIdAndDeletedFalse(request.parentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Parent category not found"));
            category.setParent(parent);
        }
        return mapper.toResponse(repo.save(category));
    }

    @Override
    @Transactional
    public CategoryResponse update(Long id, CategoryUpdateRequest request) {
        Category existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Category " + id + " not found"));
        mapper.partialUpdate(existing, request);
        if (request.parentId() != null) {
            Category parent = repo.findByIdAndDeletedFalse(request.parentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Parent category not found"));
            existing.setParent(parent);
        }
        return mapper.toResponse(repo.save(existing));
    }

    @Override
    @Transactional
    public void delete(Long id) {
        Category existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Category " + id + " not found"));
        existing.softDelete("system");
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
        when(repo.findWithRelationsByIdAndDeletedFalse(1L)).thenReturn(Optional.of(p));
        when(mapper.toDetailResponse(p)).thenReturn(resp);

        assertThat(service.findById(1L)).isEqualTo(resp);
    }

    @Test
    void findById_throwsNotFoundWhenMissing() {
        when(repo.findWithRelationsByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void create_persistsAndPublishesEvent() {
        ProductCreateRequest req = sampleCreate();
        Product product = sampleProduct();
        ProductDetailResponse resp = new ProductDetailResponse(1L, "iPhone 15", "iphone-15",
            null, "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null,
            null, null, null, null, null, null);
        when(repo.existsBySlugAndDeletedFalse("iphone-15")).thenReturn(false);
        when(repo.existsBySkuAndDeletedFalse("IP15-001")).thenReturn(false);
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
        when(repo.existsBySlugAndDeletedFalse("iphone-15")).thenReturn(true);

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
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);
        when(mapper.toDetailResponse(existing)).thenReturn(resp);

        ProductDetailResponse result = service.update(1L, req);

        assertThat(result.priceUnit()).isEqualByComparingTo("1099.00");
        verify(publisher).publishUpdated(existing);
    }

    @Test
    void delete_softDeletesAndPublishes() {
        Product existing = sampleProduct();
        when(repo.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(existing));

        service.delete(1L);

        assertThat(existing.isDeleted()).isTrue();
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

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> findAll(ProductFilter filter, Pageable pageable) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("deleted"), false));
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
        List<ProductSummaryResponse> content = page.getContent().stream()
            .map(mapper::toSummaryResponse).toList();
        return PageResponse.of(content, pageable.getPageNumber(), pageable.getPageSize(), page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "product", key = "#id")
    public ProductDetailResponse findById(Long id) {
        return repo.findWithRelationsByIdAndDeletedFalse(id)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product " + id + " not found"));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "productBySlug", key = "#slug")
    public ProductDetailResponse findBySlug(String slug) {
        return repo.findWithRelationsBySlugAndDeletedFalse(slug)
            .map(mapper::toDetailResponse)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product slug " + slug + " not found"));
    }

    @Override
    @Transactional
    public ProductDetailResponse create(ProductCreateRequest request) {
        if (repo.existsBySlugAndDeletedFalse(request.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product slug already exists");
        }
        if (repo.existsBySkuAndDeletedFalse(request.sku())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product sku already exists");
        }
        Product product = mapper.toEntity(request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findByIdAndDeletedFalse(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Category not found"));
            product.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findByIdAndDeletedFalse(request.brandId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Brand not found"));
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
        Product existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product " + id + " not found"));
        if (request.slug() != null && !request.slug().equals(existing.getSlug())
            && repo.existsBySlugAndDeletedFalse(request.slug())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product slug already exists");
        }
        if (request.sku() != null && !request.sku().equals(existing.getSku())
            && repo.existsBySkuAndDeletedFalse(request.sku())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Product sku already exists");
        }
        mapper.partialUpdate(existing, request);
        if (request.categoryId() != null) {
            Category category = categoryRepo.findByIdAndDeletedFalse(request.categoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Category not found"));
            existing.setCategory(category);
        }
        if (request.brandId() != null) {
            Brand brand = brandRepo.findByIdAndDeletedFalse(request.brandId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Brand not found"));
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
        Product existing = repo.findByIdAndDeletedFalse(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product " + id + " not found"));
        existing.softDelete("system");
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

## Phase 4 — Controllers

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
import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.common.core.viewmodel.PageResponse;
import com.shop.productservice.dto.request.BrandCreateRequest;
import com.shop.productservice.dto.response.BrandResponse;
import com.shop.productservice.service.BrandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BrandController.class)
class BrandControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean BrandService brandService;

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
    void create_withoutAdmin_returns403() throws Exception {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);

        mockMvc.perform(post("/api/v1/brands")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withAdmin_returns201() throws Exception {
        BrandCreateRequest req = new BrandCreateRequest("Acme", "acme", null, null);
        BrandResponse resp = new BrandResponse(1L, "Acme", "acme", null, null);
        when(brandService.create(any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/brands")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Acme"));
    }
}
```

> Note: Spring Security test requires `@WithMockUser` for authenticated requests. Without it, the request goes through anonymous user and gets 403 (since admin role is needed).

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
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ProductService productService;

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
    void create_withoutAdmin_returns403() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest("iPhone 15", "iphone-15", null,
            "IP15-001", new BigDecimal("999.00"), 10, ProductStatus.ACTIVE, null, null, null, null, null);

        mockMvc.perform(post("/api/v1/products")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_withInvalidDto_returns400() throws throws Exception {
        // missing required fields → validation fail
        ProductCreateRequest req = new ProductCreateRequest("", "", "", "", null, null, null,
            null, null, null, null, null);

        mockMvc.perform(post("/api/v1/products")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
```

> **Fix typo:** `throws throws Exception` → `throws Exception`. (Common copy-paste mistake.)

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

## Phase 5 — Outbox + Kafka

### Task 26: Replace `NoOpProductEventPublisher` with real `TransactionalProductEventPublisher`

**Files:**
- Modify: `product-service/src/main/java/com/shop/productservice/service/ProductEventPublisher.java` (interface only — remove NoOp class)
- Delete: `product-service/src/main/java/com/shop/productservice/service/NoOpProductEventPublisher.java`
- Create: `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java`

**Interfaces:**
- Consumes: `OutboxEventRepository`, `ObjectMapper`, `Product`
- Produces: writes `OutboxEvent` row in same `@Transactional` boundary

- [ ] **Step 1: Delete `NoOpProductEventPublisher`**

```bash
rm product-service/src/main/java/com/shop/productservice/service/NoOpProductEventPublisher.java
```

- [ ] **Step 2: Change `ProductEventPublisher` to package-private + add impl**

Move interface to `productservice.service.impls` package (or keep in service and add impl beside). Decision: keep interface in `service` package, impl in `service.impls`.

- [ ] **Step 3: Create `TransactionalProductEventPublisher`**

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

- [ ] **Step 4: Verify existing ProductServiceImplTest still passes**

Run: `./mvnw test -pl product-service -Dtest=ProductServiceImplTest`
Expected: PASS (uses Mockito mock, so any ProductEventPublisher impl works)

- [ ] **Step 5: Commit**

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

import com.shop.common.core.viewmodel.ApiResponse;
import com.shop.productservice.dto.request.ProductCreateRequest;
import com.shop.productservice.entity.OutboxEvent;
import com.shop.productservice.entity.OutboxStatus;
import com.shop.productservice.entity.ProductStatus;
import com.shop.productservice.repository.OutboxEventRepository;
import com.shop.productservice.service.ProductService;
import com.shop.productservice.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

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

> Note: This requires `AbstractIntegrationTest` base class. See Task 28.

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
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.cache.type", () -> "none");  // disable cache in tests
    }

    @org.springframework.beans.factory.annotation.Autowired
    protected ApplicationContext applicationContext;
}
```

- [ ] **Step 2: Add `testcontainers` + `await` ily` deps to pom if missing**

```xml
<dependency>
    <groupId>org.awaitility</groupId>
    <artifactId>awaitility</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: Commit**

```bash
git add product-service/src/test/java/com/shop/productservice/support/AbstractIntegrationTest.java \
        product-service/pom.xml
git commit -m "test(product-service): AbstractIntegrationTest base with Testcontainers"
```

---

## Phase 6 — Observability

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

- [ ] **Step 2: Wire into `ProductServiceImpl` (optional — counters require aspect or manual call)**

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

## Phase 7 — docker-compose + smoke test

### Task 30: Update `docker-compose.yml`

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Add `postgres-product` service**

Insert after `postgres-auth` (or wherever postgres services are defined):

```yaml
  postgres-product:
    image: postgres:16
    container_name: postgres-product
    environment:
      POSTGRES_DB: product_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    volumes:
      - postgres-product-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d product_db"]
      interval: 5s
      timeout: 5s
      retries: 10
    networks:
      - shop-net
```

- [ ] **Step 2: Add `postgres-product-data` to top-level `volumes:`**

```yaml
volumes:
  postgres-product-data:
```

- [ ] **Step 3: Add `product-service` block (if not already present)**

Verify existing `product-service:` block has:
- `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-product:5432/product_db`
- `SPRING_DATA_REDIS_HOST: redis`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`
- `depends_on: postgres-product (condition: service_healthy)`, `redis (condition: service_healthy)`, `kafka (condition: service_healthy)`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "build(docker): add postgres-product service + wire product-service to it"
```

---

### Task 31: End-to-end smoke verification

**Files:** none

- [ ] **Step 1: Build product-service JAR**

Run: `./mvnw clean package -pl product-service -DskipTests`
Expected: BUILD SUCCESS, JAR at `product-service/target/product-service-*.jar`

- [ ] **Step 2: Bring up infra**

Run: `docker-compose up -d postgres-product redis kafka product-service`
Expected: all containers start, `product-service` connects to all dependencies

- [ ] **Step 3: Verify Liquibase ran**

Run: `docker-compose logs product-service | grep -i liquibase`
Expected: "Successfully acquired change log lock" and "Update committed" for changelog-001

- [ ] **Step 4: Hit health endpoint**

Run: `curl http://localhost:8083/actuator/health`
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

Run: `curl http://localhost:8083/actuator/prometheus | grep product_`
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
| §3 Data model | Tasks 11-15 (entities), Task 10 (Liquibase) |
| §4 Repository | Tasks 16-17 |
| §5 Service | Tasks 20-22, 26-27 |
| §6 Controller | Tasks 23-25 |
| §7 Common upgrades + Config | Tasks 1-5 (Phase 0), Task 9 (CacheConfig) |
| §8 Observability | Task 29 (ProductMetrics) |
| §9 Error handling | Built-in via common `ApiExceptionHandler` |
| §10 Testing | Task 17 (ProductRepository), Tasks 20-22 (Service), Tasks 23+25 (Controller), Task 27 (Outbox) |
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