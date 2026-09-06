# Product Service Design

> **Status:** Design approved by user on 2026-08-26, pending implementation plan.
> **Path:** `docs/superpowers/specs/2026-08-26-product-service-design.md`
> **Author:** OpenCode (MiniMax-M3) + user
> **Reference:** [`hoangtien2k3/ecommerce-microservices`](https://github.com/hoangtien2k3/ecommerce-microservices) (product-service module)

---

## 1. Overview

Product-service is the microservice managing the e-commerce product catalog: Product, Category, and Brand entities with CRUD operations, Redis caching, and Kafka event publishing via the Transactional Outbox pattern. Search indexing is decoupled to a dedicated search-service for loose coupling.

**Bounded context:** Product catalog (public reads, admin writes).

**Tech stack (Spring Boot 4.1.1, Java 25, package `com.shop.*`):**

| Layer | Technology |
|---|---|
| Persistence | PostgreSQL 16 + Liquibase + Spring Data JPA |
| Cache | Redis 7 + Spring Cache abstraction |
| Events | Apache Kafka + Transactional Outbox pattern |
| Search | (deferred) Elasticsearch 8 via search-service consumer |
| Auth | Keycloak JWT + `@PreAuthorize` |
| Common | `common-spring`, `common-core`, `common-security`, `common-kafka`, `common-logging` |

---

## 2. Architecture

### 2.1 Package structure (`com.shop.productservice.*`)

```
controller/         ProductController, CategoryController, BrandController
dto/
  request/          ProductCreateRequest, ProductUpdateRequest, ...
  response/         ProductSummaryResponse, ProductDetailResponse, ...
entity/             Product, Category, Brand, OutboxEvent
repository/         ProductRepository, CategoryRepository, BrandRepository, OutboxEventRepository
service/
  ProductService    interface + ProductServiceImpl
  CategoryService
  BrandService
  ProductEventPublisher // writes OutboxEvent in same @Transactional
  OutboxRelay        // @Scheduled poller
mapper/             ProductMapper (ModelMapper @Component), CategoryMapper, BrandMapper
config/             CacheConfig (Redis cache manager), SecurityConfig (deferred, overridden only when needed); ModelMapper bean provided by common-spring auto-configuration
exception/          (uses common BusinessException, no custom exceptions)
```

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `product-service-db` schema, 4 tables |
| Redis 7 | `spring-boot-starter-data-redis` + `@EnableCaching` | Cache keys: `product::{id}`, `productBySlug::{slug}`; TTL 600s; invalidated on write |
| Kafka | `spring-kafka` + `common-kafka` (`KafkaMessagePublisher`) | Topic `shop.product.lifecycle.v1` (3 events) |
| Elasticsearch | **NONE** | Search-service consumes Kafka → indexes |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize` cho admin endpoints |

### 2.3 Decisions & rationale

- **3 entities (Product, Category, Brand)** within one service because they belong to the shared "catalog" bounded context and reference each other
- **Single controller per entity** (3 controllers) rather than a single monolithic controller, providing bounded context clarity
- **Outbox pattern** resolves the dual-write problem between database transactions and Kafka dispatches, guaranteeing at-least-once delivery
- **Cache-aside** maintains simple consistency: write operations invalidate cache entries rather than writing through
- **Loose coupling with search**: product-service has no dependency on Elasticsearch and emits domain events; future search engine changes require no modifications to product-service
- **ModelMapper for entity mappers** (aligned with auth-service pattern: `UserMapper`/`RoleMapper` `@Component` injecting `ModelMapper`). Employs `ModelMapper` with `MatchingStrategies.STRICT` + `SkipNullEnabled=true` (configured in `ModelMapperAutoConfiguration` from common-spring). Avoids `BaseMapper<M,V>` and MapStruct to enforce a single consistent pattern across the fleet (see `ARCHITECTURE.md §6` cross-cutting table, row "Mapping").
- **Liquibase instead of Flyway** matches the pattern established in auth-service
- **Lightweight Kafka payload** transmits `{eventId, eventType, productId, slug, status}`; downstream services enrich via API as needed. Trade-off: adds potential network calls while preventing payload schema drift.

---

## 3. Data model

### 3.1 Product

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK, identity |
| `title` | `String` | not null, length 200 |
| `slug` | `String` | not null, unique (partial), length 200 |
| `description` | `String` | nullable, length 2000 |
| `sku` | `String` | not null, unique (partial), length 50 |
| `priceUnit` | `BigDecimal` | not null, precision 15 scale 2 |
| `quantity` | `Integer` | not null, default 0 |
| `status` | `ProductStatus` enum | not null, length 20 (DRAFT / ACTIVE / OUT_OF_STOCK / DISCONTINUED) |
| `imageUrl` | `String` | nullable, length 500 |
| `weight` | `BigDecimal` | nullable, precision 8 scale 3 (kg) |
| `dimensions` | `String` | nullable, length 50 (e.g. "30x20x10 cm") |
| `category_id` | FK → categories.id | nullable |
| `brand_id` | FK → brands.id | nullable |
| `createdAt` / `updatedAt` / `createdBy` / `updatedBy` | (from `AbstractMappedEntity`) | |
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable`, qua `AbstractMappedEntity extends SoftDeletable`) | |

Indexes: `slug` UNIQUE PARTIAL WHERE deleted=false, `sku` UNIQUE PARTIAL WHERE deleted=false, `category_id`, `brand_id`, `status`, `deleted`.

> Entity: `@Entity @Table(name = "products") @SQLRestriction("deleted = false") extends AbstractMappedEntity`: soft-delete filter applies automatically across all queries (no `*AndDeletedFalse` repository methods needed).

### 3.2 Category (self-referencing tree)

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK |
| `title` | `String` | not null, length 100 |
| `slug` | `String` | not null, unique (partial), length 100 |
| `imageUrl` | `String` | nullable, length 500 |
| `parent_id` | FK → categories.id | nullable (root = null) |
| audit + soft-delete | | |

`@OneToMany(mappedBy = "parent") private Set<Category> children` (lazy, `@JsonIgnore` to prevent serialization recursion).

> Entity: `@SQLRestriction("deleted = false") extends AbstractMappedEntity`. Soft-deleting a parent category leaves children orphaned; `findTree()` must guard against null parents (see section 5.2).

### 3.3 Brand

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK |
| `name` | `String` | not null, length 100 |
| `slug` | `String` | not null, unique (partial), length 100 |
| `logoUrl` | `String` | nullable, length 500 |
| `description` | `String` | nullable, length 1000 |
| audit + soft-delete | | |

### 3.4 OutboxEvent

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK |
| `eventId` | `String` | not null, unique, UUID (consumer dedup key) |
| `aggregateType` | `String` | not null, "Product" |
| `aggregateId` | `Long` | not null |
| `eventType` | `String` | not null, length 50 (ProductCreated/Updated/Deleted) |
| `topic` | `String` | not null, length 100 |
| `payload` | `String` (TEXT) | not null, JSON serialized |
| `status` | `OutboxStatus` enum | not null, PENDING / SENT / FAILED |
| `retryCount` | `Integer` | not null, default 0 |
| `sentAt` | `Instant` | nullable |
| `lastError` | `String` | nullable, length 1000 |
| `createdAt` | (from `AbstractMappedEntity`) | (createdBy = "system") |
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable` via base; table requires these 3 columns) | always `false` (outbox events are hard-deleted) |

Indexes: `eventId` UNIQUE, `status` (cho poller query), `aggregateId`.

> Entity: `@Entity @Table(name = "outbox_events") extends AbstractMappedEntity`: **no** `@SQLRestriction` (the relay must observe every row).

**Rationale:** uses `String` (TEXT) for payload rather than JSONB because Outbox only stores and dispatches raw JSON without querying internal fields, avoiding Hibernate JSON mapping complexities.

### 3.5 Common modules

- **`AbstractMappedEntity`** (new, located in `utils/common-core/data/`):
  ```java
  @MappedSuperclass
  @EntityListeners(AuditingEntityListener.class)
  public abstract class AbstractMappedEntity extends SoftDeletable {
      @CreatedDate @Column(name = "created_at", updatable = false, nullable = false) private Instant createdAt;
      @LastModifiedDate @Column(name = "updated_at", nullable = false)             private Instant updatedAt;
      @CreatedBy @Column(name = "created_by", updatable = false, length = 100)     private String createdBy;
      @LastModifiedBy @Column(name = "updated_by", length = 100)                   private String updatedBy;
  }
  ```
  > `AbstractMappedEntity extends SoftDeletable` due to Java single inheritance; entities cannot extend two mapped superclasses directly.
  > OutboxEvent also inherits this base class, requiring table `outbox_events` to declare the 3 soft-delete columns (see section 3.7).
  >
  > **New pattern not yet adopted in `auth-service`.** `auth-service/User.java` currently extends `SoftDeletable` directly without the 4 audit columns; `Role.java` omits audit and soft-delete fields. Migrating auth-service is scheduled as a Phase 9 task (tracked in `ROADMAP §8.1`), adding audit columns to `users`, transitioning `User extends AbstractMappedEntity`, and adding `SoftDeletable` to `Role`.
- **`SoftDeletable`**: existing class in `utils/common-core/data/SoftDeletable.java`, structured as a `@MappedSuperclass` class (not an interface):
  - Fields: `deleted` (boolean, not null), `deletedAt` (Instant), `deletedBy` (String, **length 255**)
  - API: `markDeleted(String actor)` / `markRestored()` (no `softDelete()` or `isDeleted()` overrides)
  - Filter "deleted = false" applies per entity via `@SQLRestriction("deleted = false")` (following auth-service `User` pattern)

### 3.6 DTOs

**Product:**
- `ProductCreateRequest`  -  required: title, slug, sku, priceUnit, quantity, status, categoryId, brandId; optional: description, imageUrl, weight, dimensions
- `ProductUpdateRequest`  -  same fields, all optional (PATCH semantics  -  mapper manual `if (req.foo() != null) entity.setFoo(req.foo())`)
- `ProductSummaryResponse`: id, title, slug, sku, priceUnit, status, imageUrl (used in listings, omitting relationships to prevent N+1 queries)
- `ProductDetailResponse`: summary fields plus description, weight, dimensions, categoryId, categoryTitle, brandId, brandName, createdAt, updatedAt (detailed views with relations)

**Category:**
- `CategoryCreateRequest`  -  title, slug, imageUrl (opt), parentId (opt)
- `CategoryUpdateRequest`  -  all optional
- `CategoryResponse`  -  id, title, slug, imageUrl, parentId
- `CategoryTreeResponse`  -  recursive DTO cho `/tree` endpoint

**Brand:**
- `BrandCreateRequest` / `BrandUpdateRequest` / `BrandResponse`  -  standard fields

### 3.7 Liquibase

```
product-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changelog-001-initial-schema.yaml     (products, categories, brands, outbox_events)
```

Master file includes changelog-001. Initial schema creates 4 tables with indexes and FK constraints. **Creation order: categories -> brands -> products (with FKs) -> outbox_events** (referenced tables must exist first). Soft-delete columns: `deleted BOOLEAN NOT NULL DEFAULT false`, `deleted_at TIMESTAMPTZ`, `deleted_by VARCHAR(255)` to match `SoftDeletable` (VARCHAR 255). **Partial unique indexes `WHERE deleted = false`** for slug and sku permit re-creation after soft deletion. Table `outbox_events` includes the 3 soft-delete columns via its `AbstractMappedEntity extends SoftDeletable` inheritance.

---

## 4. Repository layer

> Entities declare `@SQLRestriction("deleted = false")`, so repository method names omit `*AndDeletedFalse` suffixes (filtered automatically).

```java
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsById(Long id);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsBySlug(String slug);

    // findById(Long): inherited, automatically filters deleted rows
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    boolean existsBySlug(String slug);
    boolean existsBySku(String sku);
    boolean existsBySlugAndIdNot(String slug, Long id);
    boolean existsBySkuAndIdNot(String sku, Long id);
}
```

`CategoryRepository` and `BrandRepository` follow the same pattern (`existsBySlug`, `existsBySlugAndIdNot`, `findAllByOrderByTitleAsc` without DeletedFalse suffix).

`OutboxEventRepository`:
```java
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    // Poller query: Pageable respects batch-size configuration
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);  // cho metrics gauge
}
```

**N+1 strategy:**
- **Detail endpoint** (find by id/slug): `@EntityGraph` fetch category + brand trong 1 query
- **List endpoint**: avoids fetching relations and returns `ProductSummaryResponse` without `categoryTitle`/`brandName`; clients call detail endpoints when denormalized fields are needed
- **Specification** only needs to filter categoryId/brandId/status; `@SQLRestriction` handles `deleted = false`

---

## 5. Service layer

### 5.1 ProductService highlights

```java
@Override
@Transactional
@Cacheable(value = "product", key = "#id")   // cache-null-values: false configured
public ProductDetailResponse findById(Long id) {
    return productRepository.findWithRelationsById(id)
        .map(productMapper::toDetailResponse)
        .orElseThrow(() -> BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND, id));
}

@Override
@Transactional
@Cacheable(value = "productBySlug", key = "#slug")
public ProductDetailResponse findBySlug(String slug) { ... }

@Override
@Transactional
@CachePut(value = "product", key = "#result.id")
@CacheEvict(value = "productBySlug", allEntries = true)  // clears all entries when slug may have changed
public ProductDetailResponse update(Long id, ProductUpdateRequest request) { ... }

@Override
@Transactional
@CacheEvict(value = {"product", "productBySlug"}, allEntries = true)
public void delete(Long id) { ... }
```

> **Note:** `@Cacheable` does not trigger on self-invocation within the same class. Controllers invoke methods through Spring proxies, ensuring caching works. Internal calls requiring caching must inject self.
>
> **Exceptions:** Avoid `new BusinessException(...)` because the constructor is private. Use factory methods: `BusinessException.of(ErrorCode.X, args...)`, `BusinessException.notFound("msg.key", args)`, `BusinessException.conflict("msg.key", args)`. Existing ErrorCodes include `PRODUCT_NOT_FOUND (PRD-2001)`, `PRODUCT_NAME_EXISTS (PRD-2002)`, and `CATEGORY_NOT_FOUND (PRD-2003)`; Phase 0 adds `PRODUCT_SLUG_EXISTS`, `PRODUCT_SKU_EXISTS`, `BRAND_NOT_FOUND`, `BRAND_SLUG_EXISTS`, and `CATEGORY_SLUG_EXISTS` with corresponding i18n message keys.
>
> **Soft-delete:** invoke `markDeleted(actor)` from `SoftDeletable`; obtain actor from `AuditorAware<String>` (`auditorAware.getCurrentAuditor().orElse("system")`), rather than hardcoding "system".

### 5.2 CategoryService.findTree()  -  build tree from flat list

```java
public List<CategoryTreeResponse> findTree() {
    List<Category> all = categoryRepository.findAllByOrderByTitleAsc();
    Map<Long, CategoryTreeResponse> nodeMap = new LinkedHashMap<>();
    List<CategoryTreeResponse> roots = new ArrayList<>();
    for (Category c : all) {
        nodeMap.put(c.getId(), categoryMapper.toTreeResponse(c, new ArrayList<>()));
    }
    for (Category c : all) {
        CategoryTreeResponse node = nodeMap.get(c.getId());
        if (c.getParent() == null) {
            roots.add(node);
        } else {
            CategoryTreeResponse parent = nodeMap.get(c.getParent().getId());
            if (parent != null) {           // guard: parent is soft-deleted, skip orphaned child
                parent.children().add(node);
            }
        }
    }
    return roots;
}
```

Avoid using entities directly in responses; construct hierarchical trees from flat lists using `CategoryTreeResponse` DTOs.

### 5.3 ProductEventPublisher (writes OutboxEvent in same TX)

```java
@Service
@RequiredArgsConstructor
public class ProductEventPublisher {
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void publishCreated(Product product)  { save(product, "ProductCreated"); }
    public void publishUpdated(Product product)  { save(product, "ProductUpdated"); }
    public void publishDeleted(Product product) { save(product, "ProductDeleted"); }

    private void save(Product p, String eventType) {
        OutboxEvent e = new OutboxEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setAggregateType("Product");
        e.setAggregateId(p.getId());
        e.setEventType(eventType);
        e.setTopic("shop.product.lifecycle.v1");
        // HashMap (avoiding Map.of) prevents NullPointerExceptions if slug/status is null;
        // services validate prior to dispatch, but this provides defensive hardening
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
            throw new IllegalStateException("Outbox payload serialization failed", ex);
        }
        e.setStatus(OutboxStatus.PENDING);
        e.setRetryCount(0);
        outboxRepository.save(e);
    }
}
```

### 5.4 OutboxRelay (@Scheduled using common-kafka `KafkaMessagePublisher`)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {
    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;  // from common-kafka
    @Value("${product.outbox.poll-interval-ms:5000}") private long pollIntervalMs;
    @Value("${product.outbox.batch-size:100}") private int batchSize;
    @Value("${product.outbox.max-retries:10}") private int maxRetries;

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval-ms:5000}")
    public void relay() {  // No @Transactional; individual save() calls commit independently
        List<OutboxEvent> pending = outboxRepo.findByStatusOrderByIdAsc(
            OutboxStatus.PENDING, PageRequest.of(0, batchSize));
        for (OutboxEvent event : pending) {
            try {
                kafkaPublisher.publish(event.getTopic(),
                    String.valueOf(event.getAggregateId()),
                    event.getPayload());
                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                event.setLastError(null);
            } catch (KafkaPublishException ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(ex.getMessage());
                if (event.getRetryCount() >= maxRetries) {
                    event.setStatus(OutboxStatus.FAILED);
                    log.error("Outbox event {} permanently failed after {} retries", event.getEventId(), maxRetries);
                } else {
                    log.warn("Outbox event {} retry {}/{}", event.getEventId(), event.getRetryCount(), maxRetries);
                }
            }
            outboxRepo.save(event);
        }
    }
}
```

> **Deferred note:** When scaling beyond one instance, `@Scheduled` runs concurrently across all nodes, causing duplicate polls. The intended solution is ShedLock; this phase deploys a single instance.

---

## 6. Controller layer

### 6.1 Endpoints

```
GET    /api/v1/products?page=0&size=20&categoryId=&brandId=&status=
GET    /api/v1/products/{id}
GET    /api/v1/products/slug/{slug}
POST   /api/v1/products        @PreAuthorize("hasRole('ADMIN')")
PUT    /api/v1/products/{id}   @PreAuthorize("hasRole('ADMIN')")
DELETE /api/v1/products/{id}   @PreAuthorize("hasRole('ADMIN')")

GET    /api/v1/categories
GET    /api/v1/categories/tree
GET    /api/v1/categories/{id}
POST   /api/v1/categories      @PreAuthorize("hasRole('ADMIN')")
PUT    /api/v1/categories/{id} @PreAuthorize("hasRole('ADMIN')")
DELETE /api/v1/categories/{id} @PreAuthorize("hasRole('ADMIN')")

GET    /api/v1/brands?page=0&size=20
GET    /api/v1/brands/{id}
POST   /api/v1/brands          @PreAuthorize("hasRole('ADMIN')")
PUT    /api/v1/brands/{id}     @PreAuthorize("hasRole('ADMIN')")
DELETE /api/v1/brands/{id}     @PreAuthorize("hasRole('ADMIN')")
```

All responses are wrapped in `ApiResponse<T>`:
- Single: `ApiResponse.ok(data)` or `ApiResponse.ok(data, "Product created successfully")`
- List/Page: `ApiResponse.ok(PageResponse.of(page))`
- Void: `ApiResponse.message("...")`

### 6.2 Authentication / Authorization

- **Anonymous GET** permitted across all 3 controllers (public catalog)
- **POST/PUT/DELETE** requires JWT with Keycloak realm role `admin` -> Spring authority `ROLE_ADMIN`
- `@EnableMethodSecurity` is configured in `common-security`, enabling `@PreAuthorize`
- `JwtRolesConverter` is provided by `common-security`

---

## 7. Configuration & security wiring

### 7.1 Common upgrades (Phase 0)

#### 7.1.1 `common-security` upgrade  -  method + path public endpoints

**Previous:** `public-paths: List<String>` (path only) permitted all methods on matching paths without distinction.
**Current:** `public-paths: List<EndpointRule>` (method + path) maintains record structure while preserving `resolvedPublicPaths()` and `PlatformDefaults`:

```java
    public record SecurityProperties(
            @DefaultValue("true") boolean enabled,
            @NotBlank String issuerUri,
            @DefaultValue("true") boolean csrfDisabled,
            @DefaultValue("true") boolean statelessSession,
            @DefaultValue List<EndpointRule> publicPaths,        // renamed from publicEndpoints
            @Valid @DefaultValue Cors cors
    ) {
    public record EndpointRule(HttpMethod method, String path) {
        public EndpointRule {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("EndpointRule.path must not be blank");
            }
        }
    }
    // resolvedPublicPaths() preserved (still merges PlatformDefaults)
}
```

`BaseSecurityConfig.securityFilterChain`: applies method-aware rules, then platform defaults, then authentication requirements:
```java
http.authorizeHttpRequests(auth -> {
    for (SecurityProperties.EndpointRule rule : properties.getPublicPaths()) {
        if (rule.method() != null) {
            auth.requestMatchers(rule.method(), rule.path()).permitAll();
        } else {
            auth.requestMatchers(rule.path()).permitAll();
        }
    }
    auth.requestMatchers(SecurityProperties.PlatformDefaults.PUBLIC_PATHS.toArray(new String[0])).permitAll();
    auth.anyRequest().authenticated();
});
```

> **Two-fold change:** (1) field changed from `publicEndpoints` to `publicPaths` (typed as `List<EndpointRule>`); (2) services configuring `public-endpoints` in YAML migrated to the `public-paths` EndpointRule format (specifically `auth-service` and `gateway-service`). Actuator and swagger endpoints are covered by PlatformDefaults and do not need explicit enumeration.

#### 7.1.2 `common-spring` new: `JpaAuditingAutoConfiguration`

```java
@AutoConfiguration
@ConditionalOnClass(AuditingHandler.class)
@EnableConfigurationProperties(JpaAuditingProperties.class)
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()))
                ? auth.getName()
                : "system";
        };
    }
}
```

> `@EnableJpaAuditing` in common-spring activates `AuditingEntityListener` across services using `common-spring`. `AbstractMappedEntity` declares `@EntityListeners(AuditingEntityListener.class)` so entity events trigger automatically without additional service annotations.

### 7.2 Product-service application.yml

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/product_db}
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
      time-to-live: 600000     # 10 min
      cache-null-values: false
      use-key-prefix: true
  kafka:
    # common-kafka reads prefix shop.kafka.* (KafkaProperties), not spring.kafka.*
    # (spring.kafka.* applies to standard Boot auto-configuration, which common-kafka bypasses)
    bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
    # Note: enable.idempotence and delivery.timeout.ms are not currently supported
    # by common-kafka buildProducerProperties() (which configures acks, retries, and max.in.flight=1).
    # Expand KafkaProperties.buildProducerProperties() if idempotent producers are required.

  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml

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

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics
```

### 7.3 CacheConfig (product-service)

```java
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerCustomizer() {
        return builder -> builder
            .withCacheConfiguration("product",
                defaultConfig(Duration.ofMinutes(10)))
            .withCacheConfiguration("productBySlug",
                defaultConfig(Duration.ofMinutes(10)));
    }

    private RedisCacheConfiguration defaultConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(ttl)
            .disableCachingNullValues()
            .computePrefixWith(name -> name + "::");
    }
}
```

### 7.4 docker-compose

> **Do not create a separate `postgres-product` container**: the repo uses a single shared PostgreSQL container with initialization scripts creating `productservice` (`docker/postgres/init/create-all-databases.sql`). Only supply environment variables to the service:

```yaml
product-service:
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/productservice
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
```

---

## 8. Observability

### 8.1 Logging (provided by common libraries)

- `CorrelationIdFilter`: automatically populates MDC `traceId`
- `HttpLoggingFilter` → access log (URI, method, status, duration)
- Application log: SLF4J qua `@Slf4j`

### 8.2 Metrics (custom trong product-service)

`ProductMetrics` class inject `MeterRegistry`:

```java
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
        // eventsPublished base: tags added per increment; Micrometer instantiates dedicated counters per tag value
        this.eventsPublished = Counter.builder("product.events.published").register(registry);
        this.relayDuration = Timer.builder("product.outbox.relay.duration").register(registry);
        Gauge.builder("product.outbox.pending.count", pendingOutboxCount, AtomicInteger::get)
            .register(registry);
    }

    public void recordCacheHit()   { cacheHit.increment(); }
    public void recordCacheMiss()  { cacheMiss.increment(); }
    public void recordEventPublished(String eventType) {
        eventsPublished.tag("event_type", eventType).increment();
    }
    public void recordRelayDuration(Duration d) { relayDuration.record(d); }
    public void setPendingOutboxCount(int n)    { pendingOutboxCount.set(n); }
}
```

Scraped via `/actuator/prometheus` (included in PlatformDefaults).

### 8.3 Audit

- `createdBy` / `updatedBy` / `deletedBy`: populated automatically by `AuditorAware` (JWT username or "system")

---

## 9. Error handling

- Bean Validation failure -> `MethodArgumentNotValidException` -> `ApiExceptionHandler` -> `ApiResponse.error("VALIDATION_FAILED", ..., List<field errors>, path)` with HTTP 400
- Business exceptions -> **`BusinessException.of(ErrorCode.X, args...)` / `BusinessException.notFound("key")` / `.conflict("key")`** (private constructor, avoid `new BusinessException`) -> mapped to HTTP status codes by `ApiExceptionHandler`
- ErrorCode provides domain error definitions: `PRODUCT_NOT_FOUND (PRD-2001)`, `PRODUCT_NAME_EXISTS (PRD-2002)`, `CATEGORY_NOT_FOUND (PRD-2003)`; Phase 0 adds `PRODUCT_SLUG_EXISTS (PRD-2004)`, `PRODUCT_SKU_EXISTS (PRD-2005)`, `BRAND_NOT_FOUND (PRD-2006)`, `BRAND_SLUG_EXISTS (PRD-2007)`, and `CATEGORY_SLUG_EXISTS (PRD-2008)` to `common-core/exception/ErrorCode.java` along with i18n keys in `common-spring/src/main/resources/messages/messages_en.properties`
- Generic codes used when domain-specific codes are absent: `NOT_FOUND`, `CONFLICT`, `BAD_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `INTERNAL_SERVER_ERROR`

---

## 10. Testing strategy

### 10.1 Test pyramid

| Layer | Tool | Coverage |
|---|---|---|
| Unit | JUnit5 + Mockito + AssertJ | Domain logic, mapper correctness |
| Slice | `@DataJpaTest` (repo), `@WebMvcTest` (controller) | Repo queries, controller routing + validation + auth |
| Integration | `@SpringBootTest` + Testcontainers (Postgres + Kafka) | OutboxRelay end-to-end |
| Smoke | Manual curl qua gateway | Verify full chain gateway → product-service → DB |

### 10.2 Test classes (~40-50 tests target)

```java
// Repository  -  @DataJpaTest + Testcontainers Postgres (+ @Import LiquibaseAutoConfiguration)
ProductRepositoryTest:        findWithRelations returns product with category/brand,
                              findBySlug excludes soft-deleted (via @SQLRestriction),
                              findAll with filter (categoryId, brandId, status)  -  no deleted predicate needed,
                              existsBySlugAndIdNot works for update

// Service  -  pure unit, mock repo + publisher
ProductServiceImplTest:       create persists and publishes event,
                              create duplicate slug throws CONFLICT,
                              update invalidates product + productBySlug cache,
                              delete publishes ProductDeleted event,
                              findById caches result

CategoryServiceImplTest:      findTree returns flat roots when no parents,
                              findTree builds nested children correctly

BrandServiceImplTest:         standard CRUD

// Controller  -  @WebMvcTest with security context
ProductControllerTest:        findById returns 200 with ApiResponse,
                              create without ADMIN returns 403,
                              create with invalid DTO returns 400 + validation errors,
                              findAll passes filter params,
                              @WithMockUser(roles="ADMIN") for admin tests

// OutboxRelay  -  full @SpringBootTest with Testcontainers Kafka
OutboxRelayIntegrationTest:   relay sends PENDING events to Kafka,
                              relay marks SENT on success,
                              relay increments retry on KafkaPublishException,
                              relay marks FAILED after max retries
                              @DynamicPropertySource cho shop.kafka.bootstrap-servers
```

> **Boot 4 test stack (Spring Boot 4.1.1, verified in Task 1 implementation):** slice tests use updated Boot 4 packages:
> - `@WebMvcTest` in `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`)
> - `@DataJpaTest` in `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`); `TestEntityManager` in `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`)
>
> Use `@MockitoBean` (`@MockBean` was removed in Boot 4). Controller tests use `@AutoConfigureMockMvc(addFilters = false)` matching auth-service; `@PreAuthorize` is not enforced in slice tests, so avoid writing 403-anonymous slice tests. Authorization is enforced by the common-security filter chain and `@PreAuthorize` (tested in integration tests). In Boot 4, `TestEntityManager` must be injected via an `@Autowired` field because the JUnit 5 extension does not resolve method parameters. `@DataJpaTest` slices do not execute Liquibase automatically, requiring `@Import(LiquibaseAutoConfiguration.class)`.
> Security tests use `@WithMockUser(roles="ADMIN")` for administrative endpoints, with anonymous access active by default for public endpoints.

---

## 11. Implementation order

**Phase 0  -  Common upgrades (foundation)**
1. `common-core`: create `AbstractMappedEntity`
2. `common-spring`: create `JpaAuditingAutoConfiguration` (`AuditorAware` bean)
3. `common-security`: upgrade `SecurityProperties` (`List<EndpointRule>` + rename `publicEndpoints` -> `publicPaths`), update `BaseSecurityConfig`
4. `auth-service`: update `application.yml` to `public-paths` EndpointRule format, verify tests
5. Verification: run `./mvnw test` across all modules

**Phase 1  -  product-service skeleton + persistence**
6. Update `product-service/pom.xml` (Redis, Kafka deps)
7. `CacheConfig` class (`@EnableCaching` + `RedisCacheManagerBuilderCustomizer`)
8. Create entities: `Product`, `Category`, `Brand`, `OutboxEvent`
9. Create Liquibase changelog-001 (4 tables, partial unique constraints)
10. Verify: docker compose up postgres redis kafka product-service + Liquibase migrate OK

**Phase 2  -  Repository + Service + cache + DTOs**
11. Create request/response DTOs with validation annotations
12. Create ModelMapper mappers (ProductMapper, CategoryMapper, BrandMapper) as `@Component` injecting `ModelMapper`
13. Create repositories with custom queries and `@EntityGraph`
14. Create services with `@Cacheable` / `@CachePut` / `@CacheEvict`
15. Tests: ProductServiceImplTest, ProductRepositoryTest, CategoryServiceImplTest
16. Verify: tests pass

**Phase 3  -  Controllers + security**
17. Create 3 controllers with `@PreAuthorize`
18. Wire `shop.security.public-paths` trong product-service `application.yml`
19. Tests: ProductControllerTest using `@WithMockUser`
20. Verify: `./mvnw test` full green

**Phase 4  -  Outbox + Kafka end-to-end**
21. `ProductEventPublisher` (writes OutboxEvent in same TX)
22. `OutboxRelay` using `KafkaMessagePublisher` from common-kafka
23. Test: `OutboxRelayIntegrationTest` with Testcontainers Kafka and `@DynamicPropertySource`
24. Verify: create product -> OutboxEvent row -> relay -> message on Kafka topic

**Phase 5  -  Observability**
25. `ProductMetrics` class (Counter, Gauge, Timer)
26. Wire into service methods and OutboxRelay
27. Verify: scrape `/actuator/prometheus` to observe custom metrics

**Phase 6  -  docker-compose + e2e smoke**
28. Update `docker-compose.yml`: add `SHOP_KAFKA_BOOTSTRAP_SERVERS`, `SPRING_DATA_REDIS_HOST/PORT`, `SERVER_PORT: 8086` for `product-service`; depends_on redis + kafka
29. `./mvnw clean package -DskipTests`
30. `docker compose up -d postgres redis kafka product-service`
31. Smoke: curl gateway `/api/v1/products` → verify response

---

## 12. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| ShedLock for OutboxRelay | Single-instance deployment initially; distributed lock not needed | When scaling beyond 1 instance |
| List caching (`@Cacheable` for `findAll`) | Invalidation complexity (keys keyed by filter and pagination); deferred | When traffic patterns stabilize |
| Image upload (S3) | product-service stores `imageUrl` string; future media-service handles uploads | Once product-service is stabilized |
| Direct Elasticsearch in product-service | Loose coupling via Kafka; search-service manages indexing | Never (by architectural design) |
| Multilingual titles and descriptions | Single language in Phase 1 | Future phase if requested |
| Bulk import / export | Admin convenience, defer | Phase sau |
| Per-route rate limit override (gateway) | Project-wide rate limit OK cho phase 1 | Phase sau |
| `Retry-After` header on 429 | Global filter already attaches X-RateLimit-* headers | Future phase if needed |
| Search-service implementation | Independent consumer of Kafka events | Once product-service is stabilized |
| Delete category/brand with products yields 409 rather than FK RESTRICT 500 | FK `onDelete: RESTRICT` currently returns DB constraint error (500); product count check needed before deletion | Subsequent phase |
| Category update cannot clear `parentId` to null | PATCH semantics require `Optional<Long>` or a sentinel value | Subsequent phase |
| Stale cache when category/brand is renamed | `ProductDetailResponse` denormalizes categoryTitle/brandName with 10-minute cache | Invalidate upon receiving category/brand events |
| Kafka producer idempotence | common-kafka `buildProducerProperties()` does not yet support `enable.idempotence` | Extend common-kafka if required |

---

## 13. Cross-references

- [`docs/RATE-LIMIT.md`](./RATE-LIMIT.md): gateway rate limiting (product-service routes via gateway, inheriting rate limits)
- [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md)  -  §1 component map, §5 data stores (Postgres + Redis), §6 cross-cutting concerns
- [`docs/ROADMAP.md`](./ROADMAP.md)  -  Phase 7 status, risk register
- `utils/common-core/.../viewmodel/ApiResponse.java`  -  response envelope
- `utils/common-core/.../viewmodel/PageResponse.java`  -  pagination wrapper
- `utils/common-spring/.../web/exception/ApiExceptionHandler.java`  -  global exception → ApiResponse.error
- `utils/common-security/.../config/SecurityAutoConfiguration.java`  -  JWT + @EnableMethodSecurity
- `utils/common-kafka/.../producer/KafkaMessagePublisher.java`  -  sync Kafka publish (10s timeout)
- `utils/common-kafka/.../config/KafkaProperties.java`  -  `shop.kafka.*` config
- `auth-service/src/main/java/com/shop/authservice/`  -  reference pattern cho service layer structure

---

## 14. Changelog

- 2026-08-26: Initial design (sections 1-6 from brainstorming) + this spec doc committed