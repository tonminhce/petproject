# Product Service Design

> **Status:** Design approved by user on 2026-08-26, pending implementation plan.
> **Path:** `docs/superpowers/specs/2026-08-26-product-service-design.md`
> **Author:** OpenCode (MiniMax-M3) + user
> **Reference:** [`hoangtien2k3/ecommerce-microservices`](https://github.com/hoangtien2k3/ecommerce-microservices) (product-service module)

---

## 1. Overview

Product-service là microservice quản lý product catalog của e-commerce platform: Product, Category, Brand với CRUD operations, Redis cache, Kafka event publishing qua Outbox pattern. Search indexing được tách ra cho search-service tương lai (loose coupling).

**Bounded context:** Product catalog (đọc public, ghi admin).

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
mapper/             ProductMapper (MapStruct), CategoryMapper, BrandMapper
config/             CacheConfig (Redis cache manager), KafkaTopicConfig, SecurityConfig (override if needed), ModelMapperConfig (deferred - use MapStruct)
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

- **3 entities (Product, Category, Brand)** trong 1 service vì cùng bounded context "catalog" và reference group chúng
- **Single controller per entity** (3 controllers) thay vì 1 mega-controller — bounded context clarity
- **Outbox pattern** giải quyết dual-write problem giữa DB transaction và Kafka publish — guaranteed at-least-once delivery
- **Cache-aside** giữ consistency đơn giản: write = invalidate, không phải write-through
- **Loose coupling search**: product-service không biết Elasticsearch tồn tại, chỉ phát events. Đổi search engine sau này → product-service không phải đổi
- **MapStruct cho mapper** (không ModelMapper) vì compile-time, hiệu năng tốt hơn, ít lỗi runtime. `BaseMapper<M, V>` interface đã có trong `common-spring`
- **Liquibase thay Flyway** match pattern auth-service đã dùng
- **Lightweight Kafka payload** chỉ gửi `eventId, eventType, productId, slug, status` — search-service tự enrich qua API khi cần. Trade-off: có thể thêm network call, nhưng tránh payload drift

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
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable`) | |

Indexes: `slug` UNIQUE PARTIAL WHERE deleted=false, `sku` UNIQUE PARTIAL WHERE deleted=false, `category_id`, `brand_id`, `status`, `deleted`.

### 3.2 Category (self-referencing tree)

| Field | Type | Constraint |
|---|---|---|
| `id` | `Long` | PK |
| `title` | `String` | not null, length 100 |
| `slug` | `String` | not null, unique (partial), length 100 |
| `imageUrl` | `String` | nullable, length 500 |
| `parent_id` | FK → categories.id | nullable (root = null) |
| audit + soft-delete | | |

`@OneToMany(mappedBy = "parent") private Set<Category> children` (lazy, `@JsonIgnore` để tránh recursion khi serialize).

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

Indexes: `eventId` UNIQUE, `status` (cho poller query), `aggregateId`.

**Rationale:** dùng `String` (TEXT) cho payload thay vì JSONB vì Outbox chỉ cần lưu và gửi nguyên vẹn JSON, không query bên trong. Tránh phức tạp Hibernate JSON mapping.

### 3.5 Common modules

- **`AbstractMappedEntity`** (NEW, đặt ở `utils/common-core/data/`):
  ```java
  @MappedSuperclass
  @EntityListeners(AuditingEntityListener.class)
  public abstract class AbstractMappedEntity {
      @CreatedDate @Column(updatable = false) private Instant createdAt;
      @LastModifiedDate @Column                private Instant updatedAt;
      @CreatedBy @Column(updatable = false, length = 100) private String createdBy;
      @LastModifiedBy @Column(length = 100)              private String updatedBy;
  }
  ```
- **`SoftDeletable`** đã có sẵn ở `utils/common-core/data/SoftDeletable.java`

### 3.6 DTOs

**Product:**
- `ProductCreateRequest` — required: title, slug, sku, priceUnit, quantity, status, categoryId, brandId; optional: description, imageUrl, weight, dimensions
- `ProductUpdateRequest` — same fields, all optional (PATCH semantics via MapStruct `NullValuePropertyMappingStrategy.IGNORE`)
- `ProductSummaryResponse` — id, title, slug, sku, priceUnit, status, imageUrl (cho list, không có relations để tránh N+1)
- `ProductDetailResponse` — summary + description, weight, dimensions, categoryId, categoryTitle, brandId, brandName, createdAt, updatedAt (cho detail, có relations)

**Category:**
- `CategoryCreateRequest` — title, slug, imageUrl (opt), parentId (opt)
- `CategoryUpdateRequest` — all optional
- `CategoryResponse` — id, title, slug, imageUrl, parentId
- `CategoryTreeResponse` — recursive DTO cho `/tree` endpoint

**Brand:**
- `BrandCreateRequest` / `BrandUpdateRequest` / `BrandResponse` — standard fields

### 3.7 Liquibase

```
product-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml
└── changelog-001-initial-schema.yaml     (products, categories, brands, outbox_events)
```

Master file include changelog-001. Initial schema tạo 4 tables + indexes + FK constraints. Soft-delete columns: `deleted BOOLEAN NOT NULL DEFAULT false`, `deleted_at TIMESTAMPTZ`, `deleted_by VARCHAR(100)`. **Partial unique indexes `WHERE deleted = false`** cho slug + sku để cho phép tạo lại sau khi soft-delete.

---

## 4. Repository layer

```java
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsByIdAndDeletedFalse(Long id);

    @EntityGraph(attributePaths = {"category", "brand"})
    Optional<Product> findWithRelationsBySlugAndDeletedFalse(String slug);

    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    boolean existsBySlugAndDeletedFalse(String slug);
    boolean existsBySkuAndDeletedFalse(String sku);
    boolean existsBySlugAndDeletedFalseAndIdNot(String slug, Long id);
    boolean existsBySkuAndDeletedFalseAndIdNot(String sku, Long id);
}
```

`CategoryRepository`, `BrandRepository`, `OutboxEventRepository` tương tự.

**N+1 strategy:**
- **Detail endpoint** (find by id/slug): `@EntityGraph` fetch category + brand trong 1 query
- **List endpoint**: KHÔNG fetch relations → trả `ProductSummaryResponse` không có `categoryTitle`/`brandName`. Client gọi detail nếu cần denormalized fields
- **Specification** luôn default `deleted = false` kể cả filter rỗng

---

## 5. Service layer

### 5.1 ProductService highlights

```java
@Override
@Transactional
@Cacheable(value = "product", key = "#id")   // cache-null-values: false ở config
public ProductDetailResponse findById(Long id) {
    return productRepository.findWithRelationsByIdAndDeletedFalse(id)
        .map(productMapper::toDetailResponse)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Product " + id + " not found"));
}

@Override
@Transactional
@Cacheable(value = "productBySlug", key = "#slug")
public ProductDetailResponse findBySlug(String slug) { ... }

@Override
@Transactional
@CachePut(value = "product", key = "#result.id")
@CacheEvict(value = "productBySlug", allEntries = true)  // clear all khi slug có thể đổi
public ProductDetailResponse update(Long id, ProductUpdateRequest request) { ... }

@Override
@Transactional
@CacheEvict(value = {"product", "productBySlug"}, allEntries = true)
public void delete(Long id) { ... }
```

> **Note:** `@Cacheable` không hoạt động với self-invocation (gọi từ method khác trong cùng class). Controller gọi qua Spring proxy nên OK. Nếu cần gọi nội bộ phải inject self.

### 5.2 CategoryService.findTree() — build tree from flat list

```java
public List<CategoryTreeResponse> findTree() {
    List<Category> all = categoryRepository.findAllByDeletedFalseOrderByTitleAsc();
    Map<Long, CategoryTreeResponse> nodeMap = new LinkedHashMap<>();
    List<CategoryTreeResponse> roots = new ArrayList<>();
    for (Category c : all) {
        nodeMap.put(c.getId(), categoryMapper.toTreeResponse(c, new ArrayList<>()));
    }
    for (Category c : all) {
        CategoryTreeResponse node = nodeMap.get(c.getId());
        if (c.getParentId() == null) {
            roots.add(node);
        } else {
            nodeMap.get(c.getParentId()).children().add(node);
        }
    }
    return roots;
}
```

Không dùng entity trực tiếp cho response — build từ flat list thành tree thông qua `CategoryTreeResponse` DTO.

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
        e.setPayload(objectMapper.writeValueAsString(Map.of(
            "eventId", e.getEventId(),
            "eventType", eventType,
            "occurredAt", Instant.now().toString(),
            "productId", p.getId(),
            "slug", p.getSlug(),
            "status", p.getStatus().name())));
        e.setStatus(OutboxStatus.PENDING);
        e.setRetryCount(0);
        outboxRepository.save(e);
    }
}
```

### 5.4 OutboxRelay (@Scheduled, dùng common-kafka `KafkaMessagePublisher`)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {
    private final OutboxEventRepository outboxRepo;
    private final KafkaMessagePublisher kafkaPublisher;  // từ common-kafka
    @Value("${product.outbox.poll-interval-ms:5000}") private long pollIntervalMs;
    @Value("${product.outbox.batch-size:100}") private int batchSize;
    @Value("${product.outbox.max-retries:10}") private int maxRetries;

    @Scheduled(fixedDelayString = "${product.outbox.poll-interval-ms:5000}")
    public void relay() {  // KHÔNG @Transactional — mỗi save() tự commit
        List<OutboxEvent> pending = outboxRepo.findTop100ByStatusOrderByIdAsc(OutboxStatus.PENDING);
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

> **Deferred (note only):** Khi scale > 1 instance, `@Scheduled` chạy ở tất cả → duplicate polls. Giải pháp tương lai: ShedLock. Trong phase này chỉ chạy 1 instance.

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

Tất cả wrapped trong `ApiResponse<T>`:
- Single: `ApiResponse.ok(data)` hoặc `ApiResponse.ok(data, "Product created successfully")`
- List/Page: `ApiResponse.ok(PageResponse.of(page))`
- Void: `ApiResponse.message("...")`

### 6.2 Authentication / Authorization

- **Anonymous GET** OK cho cả 3 controllers (catalog public)
- **POST/PUT/DELETE** yêu cầu JWT có Keycloak realm role `admin` → Spring authority `ROLE_ADMIN`
- `@EnableMethodSecurity` đã có ở `common-security` → `@PreAuthorize` work
- `JwtRolesConverter` đã có ở `common-security`

---

## 7. Configuration & security wiring

### 7.1 Common upgrades (Phase 0)

#### 7.1.1 `common-security` upgrade — method + path public endpoints

**Old:** `public-endpoints: List<String>` (path only)
**New:** `public-endpoints: List<EndpointRule>` (method + path)

```java
public class SecurityProperties {
    private List<EndpointRule> publicEndpoints = new ArrayList<>();

    public static class EndpointRule {
        private HttpMethod method;  // nullable = any method
        private String path;
        // getters/setters
    }
}
```

`BaseSecurityConfig.securityFilterChain`:
```java
for (EndpointRule rule : properties.getPublicEndpoints()) {
    if (rule.getMethod() != null) {
        auth.requestMatchers(rule.getMethod(), rule.getPath()).permitAll();
    } else {
        auth.requestMatchers(rule.getPath()).permitAll();
    }
}
auth.anyRequest().authenticated();
```

> **No backward compat:** auth-service được update sang format mới luôn (3-5 endpoints, không nhiều).

#### 7.1.2 `common-spring` new: `JpaAuditingAutoConfiguration`

```java
@AutoConfiguration
@ConditionalOnClass(AuditingHandler.class)
@EnableConfigurationProperties(JpaAuditingProperties.class)
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

> `AbstractMappedEntity` tự có `@EntityListeners(AuditingEntityListener.class)`, không cần `@EnableJpaAuditing` riêng trên từng service — chỉ cần `AuditorAware` bean.

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

```yaml
product-service:
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-product:5432/product_db
    SPRING_DATA_REDIS_HOST: redis
    SPRING_DATA_REDIS_PORT: 6379
    SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
  depends_on:
    postgres-product:
      condition: service_healthy
    redis:
      condition: service_healthy
    kafka:
      condition: service_healthy

postgres-product:
  image: postgres:16
  environment:
    POSTGRES_DB: product_db
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: postgres
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U postgres -d product_db"]
    interval: 5s
    timeout: 5s
    retries: 5
```

---

## 8. Observability

### 8.1 Logging (đã có từ common)

- `CorrelationIdFilter` → MDC `traceId` tự động
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
        this.eventsPublished = Counter.builder("product.events.published")
            .tag("event_type", "unknown")  // override khi increment
            .register(registry);
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

Scrape qua `/actuator/prometheus` (đã trong public-endpoints).

### 8.3 Audit

- `createdBy` / `updatedBy` / `deletedBy` → tự động từ `AuditorAware` (username từ JWT hoặc "system")

---

## 9. Error handling

- Bean Validation fail → `MethodArgumentNotValidException` → `ApiExceptionHandler` → `ApiResponse.error("VALIDATION_FAILED", ..., List<field errors>, path)` với HTTP 400
- Business exception → throw `BusinessException(ErrorCode.X, message)` → map HTTP status tự động qua `ApiExceptionHandler`
- Common codes: `NOT_FOUND` (404), `CONFLICT` (409), `BAD_REQUEST` (400), `UNAUTHORIZED` (401), `FORBIDDEN` (403), `INTERNAL_SERVER_ERROR` (500)
- Custom codes có thể thêm vào `common-core/exception/ErrorCode.java` nếu cần

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
// Repository — @DataJpaTest + Testcontainers Postgres
ProductRepositoryTest:        findWithRelations returns product with category/brand,
                              findBySlug excludes soft-deleted,
                              findAll with filter (categoryId, brandId, status),
                              existsBySlugAndDeletedFalseAndIdNot works for update

// Service — pure unit, mock repo + publisher
ProductServiceImplTest:       create persists and publishes event,
                              create duplicate slug throws CONFLICT,
                              update invalidates product + productBySlug cache,
                              delete publishes ProductDeleted event,
                              findById caches result

CategoryServiceImplTest:      findTree returns flat roots when no parents,
                              findTree builds nested children correctly

BrandServiceImplTest:         standard CRUD

// Controller — @WebMvcTest with security context
ProductControllerTest:        findById returns 200 with ApiResponse,
                              create without ADMIN returns 403,
                              create with invalid DTO returns 400 + validation errors,
                              findAll passes filter params,
                              @WithMockUser(roles="ADMIN") for admin tests

// OutboxRelay — full @SpringBootTest with Testcontainers Kafka
OutboxRelayIntegrationTest:   relay sends PENDING events to Kafka,
                              relay marks SENT on success,
                              relay increments retry on KafkaPublishException,
                              relay marks FAILED after max retries
                              @DynamicPropertySource cho spring.kafka.bootstrap-servers
```

> Security test dùng `@WithMockUser(roles="ADMIN")` cho admin endpoints; anonymous mặc định cho public endpoints (không cần annotation).

---

## 11. Implementation order

**Phase 0 — Common upgrades (foundation)**
1. `common-core`: tạo `AbstractMappedEntity`
2. `common-spring`: tạo `JpaAuditingAutoConfiguration` (`AuditorAware` bean)
3. `common-security`: upgrade `SecurityProperties` (List<EndpointRule>), update `BaseSecurityConfig`
4. `auth-service`: đổi `application.yml` sang EndpointRule format, verify tests
5. Verify: `./mvnw test` green toàn bộ modules

**Phase 1 — product-service skeleton + persistence**
6. Update `product-service/pom.xml` (Redis, Kafka deps)
7. `CacheConfig` class (`@EnableCaching` + `RedisCacheManagerBuilderCustomizer`)
8. Tạo entities: `Product`, `Category`, `Brand`, `OutboxEvent`
9. Tạo Liquibase changelog-001 (4 tables, partial unique constraints)
10. Verify: docker-compose up postgres-product + Liquibase migrate OK

**Phase 2 — Repository + Service + cache + DTOs**
11. Tạo request/response DTOs (validation annotations)
12. Tạo MapStruct mappers (ProductMapper, CategoryMapper, BrandMapper)
13. Tạo repositories với custom queries + `@EntityGraph`
14. Tạo services với `@Cacheable` / `@CachePut` / `@CacheEvict`
15. Tests: ProductServiceImplTest, ProductRepositoryTest, CategoryServiceImplTest
16. Verify: tests pass

**Phase 3 — Controllers + security**
17. Tạo 3 controllers với `@PreAuthorize`
18. Wire `shop.security.public-endpoints` trong product-service `application.yml`
19. Tests: ProductControllerTest với `@WithMockUser`
20. Verify: `./mvnw test` full green

**Phase 4 — Outbox + Kafka end-to-end**
21. `ProductEventPublisher` (writes OutboxEvent in same TX)
22. `OutboxRelay` dùng `KafkaMessagePublisher` từ common-kafka
23. Test: `OutboxRelayIntegrationTest` với Testcontainers Kafka + `@DynamicPropertySource`
24. Verify: create product → OutboxEvent row → relay → message trên Kafka topic

**Phase 5 — Observability**
25. `ProductMetrics` class (Counter, Gauge, Timer)
26. Wire vào service methods + OutboxRelay
27. Verify: scrape `/actuator/prometheus` thấy custom metrics

**Phase 6 — docker-compose + e2e smoke**
28. Update `docker-compose.yml`: thêm `postgres-product`, env vars
29. `./mvnw clean package -DskipTests`
30. `docker-compose up -d postgres-product redis kafka product-service`
31. Smoke: curl gateway `/api/v1/products` → verify response

---

## 12. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| ShedLock cho OutboxRelay | Phase đầu chỉ chạy 1 instance, không cần distributed lock | Khi scale > 1 instance |
| List cache (`@Cacheable` cho `findAll`) | Phức tạp invalidation (key theo filter+page), defer cho phase sau | Khi traffic pattern rõ |
| Image upload (S3) | product-service chỉ lưu `imageUrl` String; future media-service upload | Sau khi product-service stable |
| Elasticsearch trực tiếp ở product-service | Loose coupling qua Kafka — search-service lo indexing | Không bao giờ (theo design) |
| Multi-language (i18n title/description) | Single-language EN cho phase 1 | Phase sau nếu có yêu cầu |
| Bulk import / export | Admin convenience, defer | Phase sau |
| Per-route rate limit override (gateway) | Project-wide rate limit OK cho phase 1 | Phase sau |
| `Retry-After` header cho 429 | Global filter đã có X-RateLimit-* headers | Phase sau nếu cần |
| Search-service implementation | Consumer của Kafka events, độc lập | Sau khi product-service stable |

---

## 13. Cross-references

- [`docs/RATE-LIMIT.md`](./RATE-LIMIT.md) — gateway rate limit (product-service qua gateway, thừa hưởng limit)
- [`docs/ARCHITECTURE.md`](./ARCHITECTURE.md) — §1 component map, §5 data stores (Postgres + Redis), §6 cross-cutting concerns
- [`docs/ROADMAP.md`](./ROADMAP.md) — Phase 7 status, risk register
- `utils/common-core/.../viewmodel/ApiResponse.java` — response envelope
- `utils/common-core/.../viewmodel/PageResponse.java` — pagination wrapper
- `utils/common-spring/.../web/exception/ApiExceptionHandler.java` — global exception → ApiResponse.error
- `utils/common-security/.../config/SecurityAutoConfiguration.java` — JWT + @EnableMethodSecurity
- `utils/common-kafka/.../producer/KafkaMessagePublisher.java` — sync Kafka publish (10s timeout)
- `utils/common-kafka/.../config/KafkaProperties.java` — `shop.kafka.*` config
- `auth-service/src/main/java/com/shop/authservice/` — reference pattern cho service layer structure

---

## 14. Changelog

- 2026-08-26: Initial design (sections 1-6 from brainstorming) + this spec doc committed