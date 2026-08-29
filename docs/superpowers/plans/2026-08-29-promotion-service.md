# Promotion Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `promotion-service` — discount campaigns (coupon codes) + 2-phase coupon usage reservations (reserve/commit/release/release-committed/state) consumed by order-service during saga + confirm, with TTL sweep, outbox events, and admin CRUD.

**Architecture:** Mirror `inventory-service` generation: Boot 4.1.1 microservice (`com.shop.promotionservice`), PostgreSQL + Liquibase, common-keycloak/security auto-config (no public-paths, fail-closed), transactional outbox → Kafka `shop.promotion.lifecycle.v1`. No outbound HTTP clients, no Redis, no Kafka consumer. Campaign = audited CRUD entity (soft delete); usage reservation = hard-lifecycle rows purged after 30 days.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Spring Kafka (outbox relay), Lombok, JUnit 5 + Mockito + AssertJ + Testcontainers (PostgreSQL + Kafka). No WireMock (zero outbound calls).

**Spec:** [`docs/superpowers/specs/2026-08-29-promotion-service-design.md`](../specs/2026-08-29-promotion-service-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.promotionservice` (scaffold exists: `PromotionServiceApplication`). Port 8093. DB `promotionservice`.
- **Never** edit `order-service` in this plan — its integration ships via `2026-08-29-order-service-confirm-hardening.md`.
- `@WebMvcTest`: `org.springframework.boot.webmvc.test.autoconfigure` + seed `JwtAuthenticationToken` (TestingAuthenticationToken breaks `AuthenticatedUser.current()`) + `@Import(ApiExceptionHandler.class)`.
- Shared i18n bundle (`utils/common-spring/src/main/resources/messages/`): promotion keys are namespaced `promotion.*` — `reservation.not.found` is TAKEN (INV-3003).
- ErrorCode: insert PRO block after the FAV-6xxx block; every entry ends `,` — `PAYMENT_NOT_FOUND("PAY-5002",...);` must remain the ONLY `;` terminator.
- Liquibase: `defaultValueBoolean` for `deleted`; partial unique index via raw SQL (`createIndex` silently drops `where`).
- Money: `BigDecimal`, column `NUMERIC(19,2)`; percent math scale 2 `HALF_UP`.
- Optimistic-retry pattern everywhere = mirror `inventory-service/.../ReservationServiceImpl` (3 attempts, backoff `50ms * attempt`, exhausted → version-conflict code).
- `lombok.config` with `copyableAnnotations += Qualifier` exists at repo root — promotion has no qualified beans; nothing to do.

## File Map

| File | Action |
|------|--------|
| `promotion-service/pom.xml` | MODIFY — add missing deps (Task 1) |
| `utils/common-core/.../exception/ErrorCode.java` | MODIFY — PRO-7001..7011 (Task 1) |
| `utils/common-spring/.../messages/messages_{en,vi}.properties` | MODIFY — 11 `promotion.*` keys (Task 1) |
| `promotion-service/src/main/resources/application.yml` | CREATE (Task 2) |
| `promotion-service/src/main/resources/db/changelog/db.changelog-master.yaml` + `changelog-001-initial-schema.yaml` | CREATE (Task 2) |
| `.../entity/Campaign.java`, `CouponUsageReservation.java`, `constant/UsageStatus.java` | CREATE (Task 3) |
| `.../repository/CampaignRepository.java`, `CouponUsageReservationRepository.java` | CREATE (Task 4) |
| `.../dto/request/ReserveRequest.java`, `dto/response/ReservationResponse.java`, `dto/request/CampaignRequest.java`, `dto/response/CampaignResponse.java` | CREATE (Task 5) |
| `.../service/DiscountCalculator.java` | CREATE (Task 5) |
| `.../service/CampaignReservationService(+Impl).java`, `service/impls/CampaignReservationServiceImpl.java` | CREATE (Tasks 6-7) |
| `.../controller/PromotionReservationController.java` | CREATE (Task 8) |
| `.../service/CampaignService(+Impl).java`, `controller/BackofficeCampaignController.java` | CREATE (Task 9) |
| `.../entity/OutboxEvent.java`, `service/TransactionalPromotionEventPublisher.java`, `service/OutboxRelayScheduler.java`, `service/OutboxRetentionScheduler.java` | CREATE (Task 10) |
| `.../service/ReservationCleanupScheduler.java` | CREATE (Task 11) |
| `.../support/` test infra | CREATE (Task 12) |
| ITs | CREATE (Tasks 12-13) |

---

### Task 1: pom deps + ErrorCode PRO-7xxx + i18n keys

**Files:**
- Modify: `promotion-service/pom.xml`, `utils/common-core/.../ErrorCode.java`, `messages_en.properties`, `messages_vi.properties`

**Interfaces:**
- Produces: `PRO-7001..7011` constants; compile-able module.

- [ ] **Step 1: pom** — verify scaffold, ADD (keep existing entries incl. resilience4j): `common-core`, `common-spring`, `common-security`, `common-keycloak`, `spring-boot-starter-data-jpa`, `spring-boot-starter-liquibase` (`org.springframework.boot:spring-boot-starter-liquibase`), `org.postgresql:postgresql` (runtime), `spring-kafka`, `common-kafka`, `lombok` (annotationProcessor + provided). Test deps: `spring-boot-starter-test`, `spring-boot-webmvc-test`, `spring-boot-data-jpa-test`, `testcontainers-{junit-jupiter,postgresql,kafka}`, `spring-boot-testcontainers`, `awaitility`. (Mirror inventory-service pom versions — copy dependency blocks verbatim from there.)
- [ ] **Step 2: ErrorCode block** — insert after the last `FAV-` entry:

```java
    CAMPAIGN_NOT_FOUND("PRO-7001", "promotion.campaign.not.found", HttpStatus.NOT_FOUND),
    CAMPAIGN_ALREADY_EXISTS("PRO-7002", "promotion.campaign.already.exists", HttpStatus.CONFLICT),
    CAMPAIGN_IN_USE("PRO-7003", "promotion.campaign.in.use", HttpStatus.CONFLICT),
    CAMPAIGN_NOT_ACTIVE("PRO-7004", "promotion.campaign.not.active", HttpStatus.CONFLICT),
    MIN_ORDER_AMOUNT_NOT_MET("PRO-7005", "promotion.min.order.amount.not.met", HttpStatus.BAD_REQUEST),
    PER_USER_LIMIT_EXCEEDED("PRO-7006", "promotion.per.user.limit.exceeded", HttpStatus.CONFLICT),
    BUDGET_EXHAUSTED("PRO-7007", "promotion.budget.exhausted", HttpStatus.CONFLICT),
    PROMOTION_RESERVATION_NOT_FOUND("PRO-7008", "promotion.reservation.not.found", HttpStatus.NOT_FOUND),
    PROMOTION_RESERVATION_EXPIRED("PRO-7009", "promotion.reservation.expired", HttpStatus.CONFLICT),
    PROMOTION_RESERVATION_INVALID_STATE("PRO-7010", "promotion.reservation.invalid.state", HttpStatus.CONFLICT),
    PROMOTION_RESERVATION_VERSION_CONFLICT("PRO-7011", "promotion.reservation.version.conflict", HttpStatus.CONFLICT),
```

(Constant names avoid clashing with INV's `RESERVATION_*`; keys stay fully namespaced.) Verify `PAYMENT_NOT_FOUND(...);` still last with `;`.

- [ ] **Step 3: i18n** — EN: `Promotion campaign {0} not found` / `Campaign code {0} already exists` / `Campaign {0} has recorded usage and cannot be deleted` / `Campaign {0} is not active` / `Order amount {0} is below the minimum for campaign {1}` / `User {0} already reached the usage limit for {1}` / `Campaign {0} budget is exhausted` / `Promotion reservation {0} not found` / `Promotion reservation {0} has expired` / `Promotion reservation {0} is in invalid state` / `Promotion reservation {0} version conflict — retry`. VI equivalents (đầy đủ 11 keys, giữ placeholder `{0}`/`{1}` đúng vị trí).
- [ ] **Step 4: `./mvnw -pl promotion-service -am compile` + commit** — `feat(promotion-service): deps + PRO-7xxx error codes + i18n`

---

### Task 2: application.yml + Liquibase schema

**Files:**
- Create: `promotion-service/src/main/resources/application.yml`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changelog-001-initial-schema.yaml`

- [ ] **Step 1: yml** — exact block from spec §6 (`port ${SERVER_PORT:8093}`, datasource `${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/promotionservice}`, `ddl-auto: validate`, `open-in-view: false`, liquibase master, `shop.security.csrf-disabled: true`, **no public-paths**, `shop.promotion.*` 5 props: `reservation-ttl-seconds: 900`, `reservation-cleanup-batch-size: 500`, `reservation-cleanup-interval-ms: 60000`, `reservation-retention-cron: "0 0 4 * * *"`, `reservation-retention-days: 30`; Kafka bootstrap `${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`).
- [ ] **Step 2: master changelog** — include `changelog-001-initial-schema.yaml`.
- [ ] **Step 3: initial schema** — `campaign`: id uuid PK; code varchar(50) NOT NULL; name varchar(255) NOT NULL; discount_type varchar(10) NOT NULL CHECK IN ('PERCENT','FIXED'); discount_value numeric(19,2) NOT NULL; min_order_amount numeric(19,2); starts_at/ends_at timestamptz; max_redemptions int; total_budget numeric(19,2); per_user_limit int NOT NULL DEFAULT 1; status varchar(10) NOT NULL DEFAULT 'INACTIVE' CHECK IN ('ACTIVE','INACTIVE'); version bigint NOT NULL DEFAULT 0; + AbstractMappedEntity audit columns (`created_by, created_at, updated_by, updated_at, deleted` with `defaultValueBoolean: true` for deleted). Partial unique via raw SQL: `CREATE UNIQUE INDEX uk_campaign_code_live ON campaign (code) WHERE deleted = false`.
  `coupon_usage_reservation`: id uuid PK; campaign_id uuid NOT NULL (FK → campaign.id); user_id uuid NOT NULL; order_id uuid NOT NULL; order_amount/discount_amount numeric(19,2) NOT NULL; status varchar(10) NOT NULL CHECK IN ('PENDING','COMMITTED','RELEASED','EXPIRED'); expires_at/reserved_at timestamptz NOT NULL; committed_at/released_at timestamptz. Indexes: `idx_cur_user_campaign_status (user_id, campaign_id, status)`; `idx_cur_status_expires (status, expires_at)`; `uk_cur_order_id UNIQUE (order_id)` (one reservation per order — spec §3).
- [ ] **Step 4: Boot smoke** (`./mvnw -pl promotion-service spring-boot:run` → health UP, Liquibase 2 tables) + commit — `feat(promotion-service): config + schema (campaign, coupon_usage_reservation)`

---

### Task 3: Entities + UsageStatus

**Files:**
- Create: `.../constant/UsageStatus.java`, `.../entity/Campaign.java`, `.../entity/CouponUsageReservation.java`

**Interfaces:**
- Produces: `enum UsageStatus { PENDING, COMMITTED, RELEASED, EXPIRED }`; `Campaign` builder (Lombok `@Builder @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Entity`, extends the same `AbstractMappedEntity` base `Order`/`Cart` extend — copy the exact import from `order-service/.../entity/Order.java`); `CouponUsageReservation` mirrors inventory `Reservation` (NO AbstractMappedEntity — spec D10): plain `@Entity` + explicit `created` audit only (`reservedAt` serves).

- [ ] **Step 1:** Write both entities exactly per schema Task 2 (field names = column names camelCased). `Campaign` gets convenience `activate()`/`deactivate()` setting status.
- [ ] **Step 2:** Compile + JPA validate boot + commit — `feat(promotion-service): Campaign + CouponUsageReservation entities`

---

### Task 4: Repositories

**Files:**
- Create: `.../repository/CampaignRepository.java`, `.../repository/CouponUsageReservationRepository.java`

**Interfaces:**
- Produces (exact signatures used by Tasks 6/7/9/11):

```java
public interface CampaignRepository extends JpaRepository<Campaign, UUID> {
    Optional<Campaign> findByCodeAndDeletedFalse(String code);
    boolean existsByCodeAndDeletedFalseAndIdNot(String code, UUID id);
    Page<Campaign> findAllByDeletedFalse(Pageable pageable);
    Page<Campaign> findAllByStatusAndDeletedFalse(CampaignStatus status, Pageable pageable);
}

public interface CouponUsageReservationRepository extends JpaRepository<CouponUsageReservation, UUID> {
    long countByCampaignIdAndUserIdAndStatusIn(UUID campaignId, UUID userId, Collection<UsageStatus> statuses);
    long countByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);
    @Query("select coalesce(sum(r.discountAmount), 0) from CouponUsageReservation r " +
           "where r.campaignId = :campaignId and r.status in :statuses")
    BigDecimal sumDiscountByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);
    boolean existsByCampaignIdAndStatusIn(UUID campaignId, Collection<UsageStatus> statuses);
    Page<CouponUsageReservation> findByCampaignId(UUID campaignId, Pageable pageable);
    Optional<CouponUsageReservation> findByOrderId(UUID orderId);
    List<CouponUsageReservation> findByStatusAndExpiresAtBefore(UsageStatus status, Instant now, Pageable pageable);
    List<CouponUsageReservation> findByStatusInAndReleasedAtBefore(Collection<UsageStatus> statuses, Instant cutoff, Pageable pageable);
    // retention uses reservedAt for non-released rows; simpler: purge on max(committedAt, releasedAt, reservedAt)
    @Query("select r from CouponUsageReservation r where r.status in :statuses and " +
           "coalesce(r.committedAt, r.releasedAt, r.reservedAt) < :cutoff")
    List<CouponUsageReservation> findTerminalBefore(@Param("statuses") Collection<UsageStatus> statuses,
                                                    @Param("cutoff") Instant cutoff, Pageable pageable);
}
```

`enum CampaignStatus { ACTIVE, INACTIVE }` in `constant/`. Compile + commit — `feat(promotion-service): repositories`

---

### Task 5: DTOs + DiscountCalculator (TDD for math)

**Files:**
- Create: `.../dto/request/ReserveRequest.java`, `dto/response/ReservationResponse.java`, `dto/request/CampaignRequest.java`, `dto/response/CampaignResponse.java`, `.../service/DiscountCalculator.java`
- Test: `.../service/DiscountCalculatorTest.java` (plain JUnit)

**Interfaces:**
- Produces:

```java
public record ReserveRequest(@NotNull UUID userId, @NotNull UUID orderId,
                             @NotNull @Positive BigDecimal orderAmount) {}
public record ReservationResponse(UUID reservationId, UUID campaignId, String code,
                                  BigDecimal discountAmount, BigDecimal finalAmount,
                                  String status, Instant expiresAt) {
    public static ReservationResponse from(Campaign c, CouponUsageReservation r) { ... }
}
public final class DiscountCalculator {
    /** PERCENT: amount * value / 100, scale 2 HALF_UP. FIXED: min(value, amount). */
    public static BigDecimal compute(String discountType, BigDecimal value, BigDecimal orderAmount) {}
}
```

- [ ] **Step 1: Failing tests** — `compute("PERCENT", 10.00, 199.999→use 199.99)` = 20.00 (wait: `199.99 * 10 / 100 = 19.999 → 20.00` HALF_UP); `compute("PERCENT", 33.33, 100.00)` = 33.33; `compute("FIXED", 50000, 199.99)` = 199.99 (capped); `compute("FIXED", 50, 199.99)` = 50.00; unknown type → `IllegalStateException`.
- [ ] **Step 2: FAIL → implement (BigDecimal only, no double) → PASS → commit** — `feat(promotion-service): DTOs + discount calculator`

---

### Task 6: reserve — validation chain + optimistic guard (TDD)

**Files:**
- Create: `.../service/CampaignReservationService.java`, `.../service/impls/CampaignReservationServiceImpl.java`
- Test: `.../service/CampaignReserveTest.java` (Mockito unit)

**Interfaces:**
- Produces: `ReservationResponse reserve(String code, ReserveRequest request)`.
- Consumes: repos (Task 4), `DiscountCalculator`, `TransactionalPromotionEventPublisher` (Task 10 — stub interface now, wire in Task 10).

- [ ] **Step 1: Failing tests** (one per branch, exact codes):
  1. unknown/deleted code → `CAMPAIGN_NOT_FOUND`
  2. INACTIVE / before starts_at / after ends_at → `CAMPAIGN_NOT_ACTIVE`
  3. `orderAmount < min_order_amount` → `MIN_ORDER_AMOUNT_NOT_MET` (null min → skip)
  4. `countByCampaignIdAndUserIdAndStatusIn(c, u, [PENDING, COMMITTED]) >= per_user_limit` → `PER_USER_LIMIT_EXCEEDED` (per_user_limit=0 → skip check)
  5. max_redemptions reached (count PENDING+CONFIRMED) → `BUDGET_EXHAUSTED`
  6. budget: `sumDiscount(PENDING+CONFIRMED) + newDiscount > total_budget` → `BUDGET_EXHAUSTED`
  7. happy: saves PENDING row with `expiresAt = now + ttl` (inject `Clock` bean for testability — `@Bean Clock Clock.systemUTC()` + `@MockitoBean Clock` frozen in tests), publishes `promotion.reserved.v1`, returns frozen amounts (`finalAmount = orderAmount - discount`)
  8. `OptimisticLockingFailureException` ×2 then success → retry wrapper exhausts at 3 → `PROMOTION_RESERVATION_VERSION_CONFLICT`
- [ ] **Step 2: FAIL → implement**:

```java
    @Override
    public ReservationResponse reserve(String code, ReserveRequest request) {
        int attempt = 0;
        while (true) {
            try {
                return doReserve(code, request);            // @Transactional via self-injected proxy OR
                                                            // split: caller-facing method delegates to
                                                            // TransactionalTemplate — mirror inventory:
                                                            // controller → *WithRetry → non-transactional
                                                            // wrapper → transactional service method.
            } catch (OptimisticLockingFailureException ex) {
                if (++attempt >= MAX_ATTEMPTS)
                    throw BusinessException.of(ErrorCode.PROMOTION_RESERVATION_VERSION_CONFLICT, code);
                sleep(BACKOFF_BASE_MS * attempt);
            }
        }
    }
```

`doReserve` (transactional, in `CampaignServiceImpl`-style transactional bean): load campaign (`findByCodeAndDeletedFalse` → NOT_FOUND); run checks 2-6 in spec §5.1 order; `campaign.setVersionTouch()` is implicit — **bump campaign row to serialize concurrent reserves**: `campaign.setUpdatedAt(Instant.now()); campaignRepository.saveAndFlush(campaign);` (forces `@Version` increment → competing reserves retry). Build reservation (`discountAmount = DiscountCalculator.compute(...)`, `status=PENDING`, `expiresAt=clock.instant().plusSeconds(ttl)`), save, publish outbox event, return `ReservationResponse.from`.
- [ ] **Step 3: PASS + commit** — `feat(promotion-service): reserve with validation chain + optimistic guard (spec §5.1)`

---

### Task 7: commit / release / releaseCommitted — idempotent state machine (TDD)

**Files:**
- Modify: `CampaignReservationService(+Impl)`
- Test: `.../service/CampaignLifecycleTest.java`

**Interfaces:**
- Produces: `void commit(UUID id)` / `void release(UUID id)` / `void releaseCommitted(UUID id)` / `ReservationResponse getState(UUID id)`; controller-facing `*WithRetry` variants on the same wrapper.

- [ ] **Step 1: Failing matrix tests** (spec §5.3 — exact):
  - `commit(PENDING, not expired)` → COMMITTED + `committedAt` + event `promotion.committed.v1`
  - `commit(COMMITTED)` → no-op (idempotent retry)
  - `commit(RELEASED)` / `commit(EXPIRED)` → `PROMOTION_RESERVATION_INVALID_STATE`
  - `commit(PENDING expired)` → `PROMOTION_RESERVATION_EXPIRED`
  - `release(PENDING)` → RELEASED + `releasedAt` + event `previousStatus="PENDING"`
  - `release(RELEASED)` / `release(EXPIRED)` → no-op
  - `release(COMMITTED)` → `PROMOTION_RESERVATION_INVALID_STATE`
  - `releaseCommitted(COMMITTED)` → RELEASED + event `previousStatus="COMMITTED"`
  - `releaseCommitted(RELEASED/EXPIRED)` → no-op; `releaseCommitted(PENDING)` → INVALID_STATE
  - `getState(unknown)` → `PROMOTION_RESERVATION_NOT_FOUND`
- [ ] **Step 2: FAIL → implement** (exact branch code from spec §5.3; each method `@Transactional`, wrapped by `*WithRetry` for `OptimisticLockingFailureException`).
- [ ] **Step 3: PASS + commit** — `feat(promotion-service): idempotent commit/release/releaseCommitted lifecycle (spec §5.3)`

---

### Task 8: Reservation controller + @WebMvcTest

**Files:**
- Create: `.../controller/PromotionReservationController.java`
- Test: `.../controller/PromotionReservationControllerTest.java`

- [ ] **Step 1: Controller** — `@RequestMapping(ApiPaths.PROMOTIONS)`; routes per spec §4.1: `POST /{code}/reserve` (201 + `ApiResponse`), `POST /reservations/{id}/commit|release|release-committed` (200), `GET /reservations/{id}/state` (200). Class-level `@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")` — same as inventory reservation endpoints (inventory spec §4.2).
- [ ] **Step 2: Failing @WebMvcTest** — `@Import(ApiExceptionHandler.class)`, `@MockitoBean` service. Cases: SERVICE-role JWT seed → all 5 routes pass-through; no token → 401; USER role → 403; reserve body validation (`@Valid` on `ReserveRequest` → 422/400 per fleet handler); error mapping: service throws `BusinessException(PRO-7006)` → 409 + i18n message rendered (locale-safe assert on `ApiResponse.error.code`, not localized text).
- [ ] **Step 3: PASS + commit** — `feat(promotion-service): reservation endpoints (SERVICE role)`

---

### Task 9: Backoffice — campaign CRUD

**Files:**
- Create: `.../service/CampaignService.java`, `.../service/impls/CampaignServiceImpl.java`, `.../controller/BackofficeCampaignController.java`
- Test: `CampaignServiceTest.java` (unit) + `BackofficeCampaignControllerTest.java` (@WebMvcTest)

**Interfaces:**
- Produces: `Page<CampaignResponse> findAll(CampaignStatus status, Pageable)`; `CampaignResponse findById(UUID)`; `CampaignResponse create(CampaignRequest)`; `CampaignResponse update(UUID, CampaignRequest)`; `void delete(UUID)`; `Page<CampaignResponse> usages(UUID campaignId, Pageable)` (maps `CouponUsageReservation` rows).
- Consumes: `ApiPaths.BACKOFFICE_PROMOTIONS` (constant EXISTS in common-core — do NOT recreate).

- [ ] **Step 1: Failing unit tests**:
  1. create: duplicate live code → `CAMPAIGN_ALREADY_EXISTS`; PERCENT with `value > 100` or `<= 0` → `MIN_ORDER_AMOUNT_NOT_MET`-style validation error — **use** `BusinessException.of(ErrorCode.ERR_0400...)`? No: add no new code — validate in `CampaignRequest` via Jakarta (`@DecimalMin("0")`, `@DecimalMax("100")` only when type PERCENT → custom `@ValidDiscountValue` class-level constraint) → handler 400/422. Name the constraint in the plan: `@interface ValidDiscountValue` on the record.
  2. update: not found → `CAMPAIGN_NOT_FOUND`; code change colliding live → `CAMPAIGN_ALREADY_EXISTS`; edits do NOT touch existing reservations (assert no reservation repo interaction).
  3. delete: any PENDING/COMMITTED usage → `CAMPAIGN_IN_USE` (`existsByCampaignIdAndStatusIn`); else soft delete (`markDeleted` pattern from `AbstractMappedEntity`).
  4. activate/deactivate via PUT status field.
- [ ] **Step 2: Implement** — mapper: static `CampaignResponse.from(Campaign)`; `BackofficeCampaignController`: `@RequestMapping(ApiPaths.BACKOFFICE_PROMOTIONS)`, `@PreAuthorize("hasRole('ADMIN')")`, routes GET `/` paged+`status` filter, GET `/{id}`, POST (201), PUT `/{id}`, DELETE `/{id}` (200 empty), GET `/{id}/usages`.
- [ ] **Step 3: @WebMvcTest ADMIN role matrix** (401/403/SERVICE-403/200/201) + PASS + commit — `feat(promotion-service): backoffice campaign CRUD`

---

### Task 10: Outbox + Kafka events

**Files:**
- Create: `.../entity/OutboxEvent.java`, `.../repository/OutboxEventRepository.java`, `.../service/TransactionalPromotionEventPublisher.java`, `.../service/OutboxRelayScheduler.java`, `.../service/OutboxRetentionScheduler.java`
- Test: `OutboxRelayIT.java` (Task 12 harness)

**Interfaces:**
- Produces: topic `shop.promotion.lifecycle.v1`; event types `promotion.reserved.v1`, `promotion.committed.v1`, `promotion.released.v1` (payload maps: campaignId, code, userId, orderId, reservationId, discountAmount, + committedAt/releasedAt, **previousStatus** on released).

- [ ] **Step 1: Copy trio from inventory-service** (files verified to exist): `TransactionalInventoryEventPublisher` → rename `TransactionalPromotionEventPublisher`, topic → `shop.promotion.lifecycle.v1`, event names → `promotion.*.v1`; `OutboxEvent` → same divergence note (hard-delete lifecycle `PENDING → SENT/FAILED`, no soft delete — do NOT align with product-service); relay + retention schedulers → copy with `promotion.outbox.*` prop names (`@Scheduled` fixedDelay `${promotion.outbox.relay-interval-ms:2000}`, retention cron `${promotion.outbox.retention-cron:0 0 5 * * *}`). Add `publisher.publishReleased(Inventory, Reservation, String previousStatus)`-equivalent: `publishReleased(Campaign, CouponUsageReservation, String previousStatus)`.
- [ ] **Step 2: Wire into Task 6/7 call sites** (replace the Task 6 stub).
- [ ] **Step 3: Compile + commit** — `feat(promotion-service): transactional outbox → shop.promotion.lifecycle.v1 (spec §5.5)`

---

### Task 11: Cleanup schedulers (TTL sweep + retention)

**Files:**
- Create: `.../service/ReservationCleanupScheduler.java`
- Modify: `PromotionServiceApplication.java` — `@EnableScheduling`

- [ ] **Step 1: Copy `inventory-service/.../ReservationCleanupScheduler`** (verified) and rename: statuses `UsageStatus`, repo calls from Task 4 (`findByStatusAndExpiresAtBefore(PENDING, now, pageOf(batch))` → set `EXPIRED`; `findTerminalBefore([RELEASED, EXPIRED], cutoff, pageOf(batch))` → delete), props → `promotion.reservation-cleanup-batch-size` / `-interval-ms` / `reservation-retention-cron` / `-days`, keep **batch loop + flush+clear per batch** (`EntityManager`), javadoc intact. No cache eviction (no Redis here) — remove `cacheService` bits.
- [ ] **Step 2: IT in Task 13 asserts quota return** — reserve ×N to hit `per_user_limit` → advance past TTL (repo-updated `expiresAt`) → run sweep method directly → reserve succeeds again (spec D5/D6).
- [ ] **Step 3: Commit** — `feat(promotion-service): TTL sweep + retention purge (spec §5.4)`

---

### Task 12: Test harness (support classes)

**Files:**
- Create: `promotion-service/src/test/java/com/shop/promotionservice/support/AbstractIntegrationTest.java`, `TestLiquibaseConfig.java`

- [ ] **Step 1: Copy from order-service** (`TestLiquibaseConfig` verbatim, package rename) + harness pattern from `OrderCreationSagaIntegrationTest`: `@SpringBootTest`, Testcontainers PostgreSQL (reuse container per JVM) + Kafka container, `@DynamicPropertySource` datasource + `spring.kafka.bootstrap-servers` + `shop.security.csrf-disabled=true` + `shop.services.keycloak.token-url` NOT needed (no outbound) — but endpoints still need JWT validation: add test `keycloak` via `common-keycloak` test support if inventory has one (check `inventory-service/src/test/.../support/` and copy THAT instead — **inventory is the closer sibling: prefer copying inventory's integration support wholesale with renames**).
- [ ] **Step 2: One smoke IT**: boot context, Liquibase ran, `GET /actuator/health` UP. Commit — `test(promotion-service): integration harness`

---

### Task 13: Integration suites

**Files:**
- Create: `ReserveFlowIT.java`, `LifecycleAndEventsIT.java`

- [ ] **ReserveFlowIT**: (1) happy reserve → 201 + frozen amounts + outbox row PENDING; (2) each PRO-7xxx branch via real repos; (3) **concurrency**: `ExecutorService` + `CyclicBarrier`, 8 threads reserve same `(user, campaign)` with `per_user_limit=3` → exactly 3 × 201, 5 × 409 `PER_USER_LIMIT_EXCEEDED` (version-retry path exercised); (4) budget: fill PENDING to budget → 409 `BUDGET_EXHAUSTED` → sweep PENDING to EXPIRED → reserve OK again.
- [ ] **LifecycleAndEventsIT**: commit → `promotion.committed.v1` relayed to Kafka (AssertJ `awaitility` on consumer); release w/ `previousStatus=PENDING`; releaseCommitted w/ `previousStatus=COMMITTED`; commit-after-expiry → 409 `PROMOTION_RESERVATION_EXPIRED`; retention purge deletes terminal rows older than cutoff.
- [ ] **Full module suite + commit** — `test(promotion-service): reserve flow + lifecycle integration suites`

---

### Task 14: Compose / gateway verify + final

- [ ] **VERIFY-ONLY** (do not edit): `docker-compose.yml` promotion block (lines 489-505 — image, port 8093, DB `promotionservice`, healthcheck) ✓; `ServiceRoute.PROMOTION(...8093)` ✓ (line 20).
- [ ] `./mvnw -pl promotion-service -am test` — ALL green.
- [ ] Cross-check spec §2.1 D1-D10 (D1→T6/T7, D2→n/a-order-side, D3→T6 frozen, D4/D5→T4 counts+T13, D6→T2/T11, D7→T7, D8→T7, D9→ReserveRequest body, D10→T3).
- [ ] Enable checklist in commit message body: `PROMOTION_SERVICE_ENABLED:true` only after order-side hardening plan ships.
- [ ] Final review commit if needed.

---

## Self-Review

**Spec coverage:** §4.1→T8; §4.2→T9; §5.1→T6; §5.2→T6 retry+flush; §5.3→T7; §5.4→T11; §5.5→T10; §6→T2; §7→T1; §8→T8/T13(+unit in T5-T7); §9→T14 checklist; §10→T1 (ApiPaths.PROMOTIONS added by ORDER plan Task 1 — **dependency noted: common-core changes from the hardening plan must land first**). D1-D10 mapped inline in T14.
**Placeholder scan:** pom Task 1 says "copy dependency blocks verbatim from inventory-service pom" — a concrete source file action, not a placeholder. Event payload fields enumerated in T10.
**Type consistency:** `UsageStatus` (not `ReservationStatus`) everywhere; `PROMOTION_RESERVATION_*` constant names match T1 exactly where consumed in T6/T7/T8; `ReservationResponse.from(Campaign, CouponUsageReservation)` consistent T5→T6→T8.
