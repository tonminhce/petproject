# Favourite Service Design

> **Status:** Design pending user review (2026-08-28)
> **Path:** `docs/superpowers/specs/2026-08-28-favourite-service-design.md`
> **Author:** OpenCode (MiniMax-M3) + user
> **Reference:** [`hoangtien2k3/ecommerce-microservices`](https://github.com/hoangtien2k3/ecommerce-microservices) (favourite-service module)
> **Pattern source:** [`docs/superpowers/specs/2026-08-26-product-service-design.md`](./2026-08-26-product-service-design.md) — favourite-service is a deliberate slim-down of that pattern.

---

## 1. Overview

Favourite-service là microservice quản lý wishlist/favourites của user — relationship N-N giữa user và product, user-scoped ownership (a user's favourites are never visible to other users).

**Bounded context:** User engagement — private per-user CRUD on a (userId, productId) tuple.

**Tech stack (Spring Boot 4.1.1, Java 25, package `com.shop.favouriteservice`):**

| Layer | Choice | Why |
|---|---|---|
| Persistence | PostgreSQL 16 + Liquibase + Spring Data JPA | Same as auth-service + product-service |
| Cache | **None (deliberate, see §6.2)** | Per-user private data; cache invalidation dominates benefit |
| Events | **None** | No downstream consumers identified |
| Auth | Keycloak JWT + `@PreAuthorize("isAuthenticated()")` | All endpoints authenticated |
| Common | `common-spring`, `common-core`, `common-security`, `common-logging` | Same fleet baseline |

**Out of scope (deliberate):**
- Cross-service validation of `productId` against product-service — see §6.1
- Redis caching — see §6.2
- Kafka events — see §6.3
- Keycloak admin operations — favourite-service is read-only on JWT subject, never mutates Keycloak
- Admin endpoints — favourites are private to the user; no ADMIN role checks needed

---

## 2. Architecture

### 2.1 Package structure (`com.shop.favouriteservice.*`)

```
favourite-service/
├── FavouriteServiceApplication.java                # @SpringBootApplication entrypoint
├── controller/FavouriteController.java             # REST endpoints under ApiPaths.FAVOURITES
├── dto/
│   ├── request/FavouriteCreateRequest.java         # record: { productId }
│   └── response/FavouriteResponse.java             # record: { id, userId, productId, createdAt }
├── entity/Favourite.java                           # extends AbstractMappedEntity + @SQLRestriction
├── repository/FavouriteRepository.java             # JpaRepository<Favourite, UUID>
├── mapper/FavouriteMapper.java                     # @Component inject ModelMapper
└── service/
    ├── FavouriteService.java                       # interface
    └── impls/FavouriteServiceImpl.java             # @Service + @Transactional
```

No `config/` package — no cache config, no security override (common-security is sufficient).
No `exception/` — uses `BusinessException` from common-core.

### 2.2 Integrations map

| Integration | Library | Boundary |
|---|---|---|
| Postgres | `spring-boot-starter-data-jpa` + Liquibase | `favourite-service-db` schema, 1 table |
| Keycloak JWT | Spring Security Resource Server | `@PreAuthorize`; current user via `AuthenticatedUser.requireCurrent()` (see §2.3) |
| Redis | **NONE** | See §6.2 |
| Kafka | **NONE** | See §6.3 |
| Elasticsearch | **NONE** | N/A |
| product-service | **NONE** (no cross-service call) | See §6.1 |

### 2.3 Decisions & rationale

- **Single entity, single controller, no DTO splitting** — favourite is a single tuple, no relations to load.
- **`AbstractMappedEntity` over `SoftDeletable` direct** — match product-service pattern; gives audit (createdAt/updatedAt/createdBy/updatedBy) for free alongside soft-delete.
- **`@SQLRestriction("deleted = false")`** — auto-filter every query; no `*AndDeletedFalse` repository method suffix (consistent with auth-service User + product-service Product).
- **Partial unique index** on (userId, productId) WHERE deleted = false — duplicate prevention that allows soft-deleted resurrection of same (user, product).
- **`AuthenticatedUser.requireCurrent()` static call** — pulls the verified JWT subject from `SecurityContextHolder` via common-security helper. The `id` field is `String` (JWT `sub` claim, which is Keycloak-formatted as a UUID string); service layer parses to `UUID` once at the controller boundary.
  - **Why not `@AuthenticationPrincipal AuthenticatedUser me`** — Spring Security's `AuthenticationPrincipalArgumentResolver` matches on principal type. Our principal is `Jwt` (set by `JwtAuthenticationConverter`), not `AuthenticatedUser`. There is no built-in converter for the custom record. Static `requireCurrent()` is the documented idiom in `common-security` and is the cleanest path.
  - `UUID.fromString(me.id())` — Keycloak issues `sub` as a UUID v4 string; throw `BusinessException.unauthorized("favourite.user.subject.malformed")` (new i18n key in §7.2) if parse fails (defensive — a valid Keycloak JWT will never produce this exception).

---

## 3. Data model

### 3.1 Favourite

| Field | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK, identity (`@GeneratedValue(UUID)`) |
| `userId` | `UUID` | not null — sourced from `AuthenticatedUser.id()` at create time |
| `productId` | `UUID` | not null — accepted from request, no validation |
| `createdAt` / `updatedAt` / `createdBy` / `updatedBy` | (from `AbstractMappedEntity`) | auto-populated by `AuditorAware` |
| `deleted` / `deletedAt` / `deletedBy` | (from `SoftDeletable`) | `markDeleted(actor)` API |

**Indexes:**
- `UNIQUE INDEX (user_id, product_id) WHERE deleted = false` — duplicate prevention, allows re-favourite after soft-delete
- `INDEX (user_id)` — list-by-user query path

**No FK** to product-service's `products` table (cross-schema coupling avoided per §6.1).

### 3.2 Entity (`Favourite.java`)

```java
@Entity
@Table(name = "favourites")
@SQLRestriction("deleted = false")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Favourite extends AbstractMappedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;
}
```

### 3.3 Liquibase

```
favourite-service/src/main/resources/db/changelog/
├── db.changelog-master.yaml       (include changelog-001)
└── changelog-001-initial-schema.yaml
```

Single changeset creates `favourites` table with all 11 columns (4 audit + 3 soft-delete + 2 domain + 2 system) plus the partial unique index + user_id index. Column types match SoftDeletable (255) and AbstractMappedEntity (100). See §7.1 for full YAML.

---

## 4. Repository layer

```java
public interface FavouriteRepository extends JpaRepository<Favourite, UUID> {

    // Auto-filtered via @SQLRestriction — no *AndDeletedFalse suffix needed

    List<Favourite> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Favourite> findByIdAndUserId(UUID id, UUID userId);

    Optional<Favourite> findByUserIdAndProductId(UUID userId, UUID productId);

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Soft-delete by (userId, favouriteId) pair — guards against cross-user deletion.
     * Bypasses @SQLRestriction via native UPDATE; row stays in DB for audit.
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

**N+1 / batch:** Single-entity with no relations — no N+1 risk. List endpoint uses single query with index on `user_id`.

---

## 5. Service layer (`FavouriteServiceImpl`)

### 5.1 Methods (interface)

```java
public interface FavouriteService {
    List<FavouriteResponse> findAllByCurrentUser(UUID userId);
    FavouriteResponse findById(UUID id, UUID userId);
    FavouriteResponse create(UUID userId, FavouriteCreateRequest request);
    void deleteById(UUID id, UUID userId);
    void deleteByProductId(UUID userId, UUID productId);
}
```

Service methods take `UUID userId` directly — the controller is responsible for resolving and parsing the JWT subject before calling (see §8). This keeps the service testable without any Spring Security context in unit tests.

### 5.2 Implementation highlights

```java
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
        return mapper.toResponse(repo.save(favourite));
    }

    @Override
    @Transactional
    public void deleteById(UUID id, UUID userId) {
        int affected = repo.softDeleteByIdAndUserId(
                id, userId, auditorAware.getCurrentAuditor().orElse("system"));
        if (affected == 0) {
            throw BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id);
        }
    }

    @Override
    @Transactional
    public void deleteByProductId(UUID userId, UUID productId) {
        int affected = repo.softDeleteByUserIdAndProductId(
                userId, productId, auditorAware.getCurrentAuditor().orElse("system"));
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

**Concurrency / idempotency:** Concurrent double-add race tolerated — `@SQLRestriction` + partial unique index means second save throws `DataIntegrityViolationException` (translated to `CONFLICT` by `ApiExceptionHandler`). Acceptable; user retries see "already exists".

**Soft-delete actor:** `auditorAware.getCurrentAuditor().orElse("system")` — same pattern as `ProductServiceImpl.delete`. For async paths (none here) the actor would be "system".

---

## 6. Deliberate non-decisions (with rationale)

### 6.1 No cross-service `productId` validation
Per user choice during brainstorm (2026-08-28). Trade-off:
- **Pro:** Removes dependency on product-service availability; service stays at S = 1–2 days.
- **Pro:** Simpler error model — never depends on cross-service latency.
- **Con:** Orphan FK possible if a product is deleted in product-service but the favourite row remains.
- **Mitigation (future, not in this spec):** GET `/api/v1/favourites` could optionally join product-service for display; we keep raw IDs and let the UI/future BFF enrich. No code change required.

### 6.2 No Redis cache (deliberate over-engineering avoidance)
Per user choice during brainstorm (2026-08-28). Detailed justification:

| Factor | product-service (Redis OK) | favourite-service (Redis skip) |
|---|---|---|
| Read pattern | Public catalog — many readers, few writers | Per-user dashboard — only the user reads their own |
| Content stability | Product rarely changes | User adds/removes frequently |
| Per-key benefit | One cache entry serves many users | One entry per (user) — invalidation per write |
| Realistic TTL | 10 minutes | ≤ 30 seconds — minimal benefit |
| Data size | Large table + joins | Tiny table + index lookup |
| Stampede risk | Real during flash sales | Negligible |

**When to revisit:**
- GET `/api/v1/favourites` traffic from a single user exceeds tens of req/s sustained
- Cross-service favourite-count aggregation needed (then move counting to product-service, consume via Kafka)
- Profile shows DB latency hot

### 6.3 No Kafka events
No downstream consumers identified. Favourite toggle is purely private to the user. If analytics/search/notification need events later, this service can add a product-event-style Outbox without restructuring.

---

## 7. Configuration & security

### 7.1 No Phase 0 common changes needed
Unlike product-service (which required `AbstractMappedEntity`, `JpaAuditingAutoConfiguration`, `SecurityProperties` EndpointRule migration), favourite-service needs no common upgrade:
- `AbstractMappedEntity` already exists (`common-core/data/`).
- `JpaAuditingAutoConfiguration` already wired in `common-spring` (auto-configuration list).
- `SecurityProperties.EndpointRule` already supports method-aware; favourite-service just doesn't list any public paths.

### 7.2 New ErrorCodes + i18n (added to common)

`utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`:

```java
// In a new // ---- Favourite domain ---- section (range 6xxx — PAY already owns 5xxx):
FAVOURITE_NOT_FOUND("FAV-6001", "favourite.not.found", HttpStatus.NOT_FOUND),
FAVOURITE_ALREADY_EXISTS("FAV-6002", "favourite.already.exists", HttpStatus.CONFLICT),
```

`utils/common-spring/src/main/resources/messages/messages_en.properties` (and `messages_vi.properties`):
```properties
favourite.not.found=Favourite {0} not found
favourite.already.exists=Favourite already exists
favourite.user.subject.malformed=Authentication token subject is not a valid user id
```

Vietnamese (`messages_vi.properties`):
```properties
favourite.not.found=Không tìm thấy mục yêu thích {0}
favourite.already.exists=Mục yêu thích đã tồn tại
favourite.user.subject.malformed=Token xác thực không chứa user id hợp lệ
```

### 7.3 favourite-service `application.yml`

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
    # No public paths — every endpoint requires a valid JWT.
    public-paths: []
```

(`/actuator/*` health/prometheus endpoints already exposed by `common-spring/application.yml` baseline.)

### 7.4 No per-service `SecurityConfig`
`common-security` auto-configures JWT chain via `BaseSecurityConfig.securityFilterChain` (`@ConditionalOnMissingBean`). The empty `public-paths` list means everything goes through `anyRequest().authenticated()` plus platform defaults (actuator/swagger/api-docs always public).

### 7.5 docker-compose.yml delta
Add `favourite-service` block (env vars + depends_on postgres). Mirror the pattern used by `product-service` block — but WITHOUT Redis/Kafka dependencies.

---

## 8. Controller layer

```java
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
        return ApiResponse.ok(service.create(currentUserId(), request), "Favourite added successfully");
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
            // Defensive — a valid Keycloak JWT `sub` is always a UUID. This branch
            // exists only to prevent 500 if a non-UUID subject is ever introduced.
            throw BusinessException.unauthorized("favourite.user.subject.malformed");
        }
    }
}
```

**Class-level `@PreAuthorize("isAuthenticated()")`** guards every endpoint; matches the design intent that favourite data is private. Owner check (`userId` match) happens in service layer.

**Why `AuthenticatedUser.requireCurrent()` static rather than `@AuthenticationPrincipal`:** see §2.3 — Spring Security's resolver matches on principal type (`Jwt`), not on the custom `AuthenticatedUser` record. Static call goes through `SecurityContextHolder` which is the documented `common-security` idiom.

---

## 9. DTOs

```java
// FavouriteCreateRequest.java
public record FavouriteCreateRequest(
        @NotNull(message = "productId must not be null")
        UUID productId
) {}

// FavouriteResponse.java
public record FavouriteResponse(
        UUID id,
        UUID userId,
        UUID productId,
        Instant createdAt
) {}
```

Records match product-service convention. Response includes `createdAt` (from AbstractMappedEntity) for ordering display. Excludes `updatedAt`/`createdBy`/etc. — minimal surface matches use case (UI shows item + when favourited).

---

## 10. Mapper

```java
@Component
public class FavouriteMapper {

    private final ModelMapper modelMapper;

    public FavouriteMapper(ModelMapper modelMapper) {
        this.modelMapper = modelMapper;
    }

    public FavouriteResponse toResponse(Favourite favourite) {
        return new FavouriteResponse(
                favourite.getId(),
                favourite.getUserId(),
                favourite.getProductId(),
                favourite.getCreatedAt()
        );
    }

    // No toEntity(CreateRequest) — service builds entity directly with .builder()
    // (only 2 fields, mapper overhead exceeds its value)
}
```

---

## 11. Error handling

- **No favourite found** → `BusinessException.of(ErrorCode.FAVOURITE_NOT_FOUND, id)` → HTTP 404, code `FAV-6001`.
- **Duplicate (user, product)** → `BusinessException.of(ErrorCode.FAVOURITE_ALREADY_EXISTS)` → HTTP 409, code `FAV-6002`.
- **Owner mismatch** (someone else's favouriteId) → treat as `FAVOURITE_NOT_FOUND` (do NOT throw FORBIDDEN — don't leak existence).
- **Bean Validation fail** on `FavouriteCreateRequest` → `ApiExceptionHandler` → 400 + validation errors array.
- **DataIntegrityViolation** on concurrent double-add race → `ApiExceptionHandler` → 409 CONFLICT (handled centrally, no per-service code).

No per-service `@ExceptionHandler`. Centralized in `common-spring/web/exception/ApiExceptionHandler.java`.

---

## 12. Testing strategy

### 12.1 Test pyramid

| Layer | Tool | Target |
|---|---|---|
| Unit | JUnit5 + Mockito | Domain logic, validation, ownership checks |
| Slice JPA | `@DataJpaTest` + Testcontainers Postgres | Repository queries, partial unique constraint, soft-delete filter |
| Slice MVC | `@WebMvcTest` + `@MockitoBean` | Controller routing, auth, response envelope |

### 12.2 Test classes (~19 tests target)

**`FavouriteServiceImplTest`** — 10 tests (pure unit, mock repo + mapper + auditor):
1. `findAllByCurrentUser_returnsMappedList`
2. `findById_returnsFavourite_whenOwnedByCurrentUser`
3. `findById_throwsNotFound_whenMissing`
4. `findById_throwsNotFound_whenOwnedByOtherUser`
5. `create_persistsAndReturnsResponse`
6. `create_throwsConflict_whenDuplicate`
7. `deleteById_softDeletes_whenFound`
8. `deleteById_throwsNotFound_whenNotOwned`
9. `deleteByProductId_softDeletes_whenFound`
10. `deleteByProductId_throwsNotFound_whenMissing`

**`FavouriteRepositoryTest`** — 3 tests (`@DataJpaTest` + `@Import(LiquibaseAutoConfiguration.class)` + Testcontainers Postgres):
1. `findByUserId_filtersSoftDeleted`
2. `softDeleteByUserIdAndProductId_keepsRowAndSetsFlags`
3. `partialUniqueConstraint_allowsReAddingAfterSoftDelete`

**`FavouriteControllerTest`** — 6 tests (`@WebMvcTest(FavouriteController.class)` + `@AutoConfigureMockMvc(addFilters=false)` + `@MockitoBean(FavouriteService)` + `@Import(ApiExceptionHandler.class)`):
1. `findAll_returns200WithEnvelope`
2. `findById_returns200WithEnvelope`
3. `create_returns200WithCreatedEnvelope`
4. `create_returns400_whenProductIdMissing`
5. `deleteById_returns200WithMessageEnvelope`
6. `deleteByProduct_returns200WithMessageEnvelope`

Total **~19 tests** across 3 classes.

**Fleet convention:** every `@WebMvcTest` controller-slice class must `@Import(ApiExceptionHandler.class)` so validation failures populate `errors[]` in the response envelope. Confirmed pattern in `product-service/src/test/java/com/shop/productservice/controller/ProductControllerTest.java:31`.

For `@DataJpaTest` repository slices, the fleet pattern is `@AutoConfigureTestDatabase(replace = Replace.NONE)` (skip embedded H2/HSQL replacement) + `@Import({JpaAuditingAutoConfiguration.class, LiquibaseAutoConfiguration.class})` (since `@DataJpaTest` does not auto-include either) + static `@Container` + `@DynamicPropertySource` (NOT `@ServiceConnection`). Confirmed in `product-service/src/test/java/com/shop/productservice/repository/ProductRepositoryTest.java:33-57`.

### 12.3 Boot 4 specifics (verified via product-service)
- `@MockitoBean` (NOT `@MockBean`).
- `@WebMvcTest` from `org.springframework.boot.webmvc.test.autoconfigure.*` (artifact `spring-boot-starter-webmvc-test`).
- `@DataJpaTest` from `org.springframework.boot.data.jpa.test.autoconfigure.*` (artifact `spring-boot-data-jpa-test`).
- `TestEntityManager` from `org.springframework.boot.jpa.test.autoconfigure.*` (artifact `spring-boot-jpa-test`), injected via `@Autowired` field (NOT method param — Boot 4 doesn't resolve method param for `TestEntityManager`).
- `@DataJpaTest` slice does NOT run Liquibase by default → test classes must `@Import(LiquibaseAutoConfiguration.class)`.

---

## 13. Observability

- **Logging** — `CorrelationIdFilter` → MDC `traceId` automatic; `HttpLoggingFilter` → access log; SLF4J via `@Slf4j` if needed.
- **Metrics** — rely on platform defaults (`/actuator/prometheus` exposes JVM + HTTP metrics). Custom product-level metrics OUT OF SCOPE — favourite has no high-rate business operation worth a custom counter.
- **Health** — `/actuator/health` + liveness/readiness probes (platform default 9000 port override not relevant here).

---

## 14. File scope (~17 files)

### Modified common modules
| File | Change |
|---|---|
| `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java` | Add `FAVOURITE_NOT_FOUND`, `FAVOURITE_ALREADY_EXISTS` |
| `utils/common-spring/src/main/resources/messages/messages_en.properties` | Add `favourite.not.found`, `favourite.already.exists` |
| `utils/common-spring/src/main/resources/messages/messages_vi.properties` | Add Vietnamese versions |

### New favourite-service files
| File | Responsibility |
|---|---|
| `favourite-service/src/main/java/com/shop/favouriteservice/FavouriteServiceApplication.java` | `@SpringBootApplication` entrypoint |
| `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java` | JPA entity |
| `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java` | JPA repo with soft-delete queries |
| `favourite-service/src/main/java/com/shop/favouriteservice/dto/request/FavouriteCreateRequest.java` | record DTO |
| `favourite-service/src/main/java/com/shop/favouriteservice/dto/response/FavouriteResponse.java` | record DTO |
| `favourite-service/src/main/java/com/shop/favouriteservice/mapper/FavouriteMapper.java` | `@Component` ModelMapper |
| `favourite-service/src/main/java/com/shop/favouriteservice/service/FavouriteService.java` | interface |
| `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java` | impl |
| `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` | REST endpoints |
| `favourite-service/src/main/resources/application.yml` | config |
| `favourite-service/src/main/resources/db/changelog/db.changelog-master.yaml` | Liquibase master |
| `favourite-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 1 table + 2 indexes |

### New test files
| File | Coverage |
|---|---|
| `favourite-service/src/test/java/com/shop/favouriteservice/service/impls/FavouriteServiceImplTest.java` | 10 unit tests |
| `favourite-service/src/test/java/com/shop/favouriteservice/repository/FavouriteRepositoryTest.java` | 3 JPA slice tests |
| `favourite-service/src/test/java/com/shop/favouriteservice/controller/FavouriteControllerTest.java` | 6 MVC slice tests |

### Modified parent / docker
| File | Change |
|---|---|
| `pom.xml` (parent) | Add `<module>favourite-service</module>` (already present per AGENTS.md — verify) |
| `docker-compose.yml` | **Verify** `favourite-service` container block (already exists at line 372) — align env/ports/depends_on/networks with spec §7.5; no Redis/Kafka deps |

---

## 15. Implementation order

**Step 1 — Common additions (~15 min)**
1. Add `FAVOURITE_NOT_FOUND` + `FAVOURITE_ALREADY_EXISTS` to `ErrorCode.java`
2. Add `favourite.not.found` + `favourite.already.exists` to both `messages_*.properties`
3. Run `./mvnw -pl utils/common-core,utils/common-spring test` to verify nothing breaks

**Step 2 — favourite-service skeleton + persistence (~2–3 hrs)**
4. Update `favourite-service/pom.xml` (deps: common-spring, JPA, Liquibase, Postgres, ModelMapper, Lombok, test stack)
5. `FavouriteServiceApplication.java`
6. `application.yml` (port 8081)
7. `Favourite` entity
8. `FavouriteRepository` interface
9. Liquibase changelog (master + 001)
10. Verify: `docker compose up postgres favourite-service` boots, Liquibase creates table, JPA validates schema

**Step 3 — Service + DTOs + Mapper (~2 hrs)**
11. `FavouriteCreateRequest` + `FavouriteResponse` records
12. `FavouriteMapper`
13. `FavouriteService` interface + `FavouriteServiceImpl`
14. Tests: `FavouriteServiceImplTest` (10 tests)

**Step 4 — Controller + tests (~2 hrs)**
15. `FavouriteController`
16. Tests: `FavouriteControllerTest` (6 tests)
17. `FavouriteRepositoryTest` (3 tests)
18. Verify: `./mvnw test -pl favourite-service -am` green

**Step 5 — Docker + smoke (~30 min)**
19. **Verify** `docker-compose.yml` favourite-service block matches schema (already exists; fix only if drift)
20. `./mvnw clean package -DskipTests`
21. `docker compose up -d postgres favourite-service`
22. Smoke: `curl localhost:8081/actuator/health` → UP

**Total estimated time:** 6–8 hours (1 day) for senior engineer.

---

## 16. Open items / Deferred

| Item | Reason | When |
|---|---|---|
| Cross-service product validation | YAGNI for MVP | When analytics/search service consumes favourite events |
| Redis cache | Per-user cache invalidation dominates benefit | When traffic justifies it (see §6.2) |
| Kafka events | No consumers identified | When notification (e.g. "X favourited your product" — admin feature) requires it |
| Admin endpoints (list any user's favourites) | Out of scope for private data | If compliance/audit requires it |
| Bulk favourite/import | Convenience, defer | Phase 8+ |
| Pagination on `findAllByCurrentUser` | Page size implicit — assumption is small per-user list (~tens, not thousands) | If measured user has > 100 favourites |

---

## 17. Cross-references

- [`docs/SERVICE-CATALOG.md §7`](./../SERVICE-CATALOG.md) — endpoint surface, domain model reference
- [`docs/COMMON-LIB-REFERENCE.md §3.3`](./../COMMON-LIB-REFERENCE.md) — `AuthenticatedUser` helper from common-security
- [`docs/superpowers/specs/2026-08-26-product-service-design.md`](./2026-08-26-product-service-design.md) — pattern template (favourite = product-service − Kafka − Redis − extras)
- [`utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java`](./../../../utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java) — audit + soft-delete base
- [`utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java`](./../../../utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java) — exception factories used in service layer
- `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` — older `@AuthenticationPrincipal Jwt` pattern (for reference; favourite uses the cleaner `AuthenticatedUser`)

---

## 18. Changelog

- 2026-08-28: Initial design (this document). Drafted from brainstorm: scope = favourite-service, no cross-service validation, soft-delete, no Redis cache, no Kafka events.
