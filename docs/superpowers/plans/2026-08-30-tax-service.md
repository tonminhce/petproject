# Tax Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `tax-service` — backoffice tax classes + rates catalog and the `POST /api/v1/backoffice/tax-rates/calculate` endpoint order-service's shipped `TaxServiceClient` already calls, closing the confirm-saga's zero-tax hole.

**Architecture:** Fleet-standard Boot 4.1.1 microservice (`com.shop.taxservice`): PostgreSQL + Liquibase, common-keycloak/security auto-config (fail-closed), 2 audited soft-delete entities, 3-tier rate fallback (postal → country → class default). Purely synchronous: no Kafka, no outbox, no Redis, no schedulers.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Lombok, JUnit 5 + Mockito + AssertJ + Testcontainers (PostgreSQL only). No WireMock, no Kafka container.

**Spec:** [`docs/superpowers/specs/2026-08-30-tax-service-design.md`](../specs/2026-08-30-tax-service-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.taxservice` (scaffold exists: `TaxServiceApplication`). Port 8091. DB `taxservice`.
- **Never** edit `order-service` in this plan. Enabling tax post-deploy = `SHOP_SERVICES_TAX_ENABLED: "true"` in the order-service compose stanza (relaxed binding of `shop.services.tax.enabled`) — a §8 checklist item, not a code change here.
- `@WebMvcTest`: `org.springframework.boot.webmvc.test.autoconfigure` + seed `JwtAuthenticationToken` (TestingAuthenticationToken breaks `AuthenticatedUser.current()`) + `@Import(ApiExceptionHandler.class)`.
- ErrorCode anchor (verified 2026-08-30): enum ends `PROMOTION_RESERVATION_VERSION_CONFLICT("PRO-7011", "promotion.reservation.version.conflict", HttpStatus.CONFLICT);` at `utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java:97`. Insert rule: flip that `;` → `,`, append TAX-8001..8003 each `,`-terminated, TAX-8004 last `;`-terminated. Task 1 shows the exact block.
- Liquibase: `defaultValueBoolean` for `deleted`; **partial** unique indexes need raw SQL `sql` changesets (`createIndex` silently drops `where`); the NULL-postal dedupe needs an **expression** index on `COALESCE(postal_code, '')` — also raw SQL.
- Money/percent: `BigDecimal`; amounts `NUMERIC(19,2)`; `rate_pct NUMERIC(5,2)`; `taxAmount = amount × rate_pct / 100`, scale 2, `HALF_UP`.
- Entities extend `AbstractMappedEntity` (10 audit columns) + explicit `@Version` + `@SQLRestriction("deleted = false")` (Campaign precedent). Liquibase changelog carries the full audit column set or validation fails at boot.
- Harness = promotion's singleton pattern (`static { }` boot + shutdown hook, NO `@Testcontainers`), PostgreSQL only (drop the Kafka container).
- `lombok.config` with `copyableAnnotations += Qualifier` exists at repo root — no qualified beans here; nothing to do.

## File Map

| File | Action |
|------|--------|
| `tax-service/pom.xml` | MODIFY — deps (Task 1) |
| `utils/common-core/.../ErrorCode.java` | MODIFY — TAX-8001..8004 (Task 1) |
| `utils/common-core/.../constants/ApiPaths.java` | MODIFY — BACKOFFICE_TAX_CLASSES/RATES (Task 1) |
| `utils/common-spring/.../messages/messages_{en,vi}.properties` | MODIFY — 4 `tax.*` keys (Task 1) |
| `tax-service/src/main/resources/application.yml` | CREATE (Task 2) |
| `tax-service/src/main/resources/db/changelog/db.changelog-master.yaml` + `changelog-001-initial-schema.yaml` | CREATE (Task 2) |
| `.../entity/TaxClass.java`, `TaxRate.java` | CREATE (Task 3) |
| `.../repository/TaxClassRepository.java`, `TaxRateRepository.java` | CREATE (Task 4) |
| `.../dto/request/TaxCalculateRequest.java`, `TaxClassRequest.java`, `TaxRateRequest.java`; `dto/response/TaxCalculateResponse.java`, `TaxClassResponse.java`, `TaxRateResponse.java` | CREATE (Task 5) |
| `.../service/TaxCalculator.java` | CREATE (Task 5) |
| `.../service/TaxCalculationService(+Impl)` | CREATE (Task 6) |
| `.../service/TaxClassService(+Impl)`, `TaxRateService(+Impl)` | CREATE (Task 7) |
| `.../controller/TaxCalculationController.java`, `BackofficeTaxClassController.java`, `BackofficeTaxRateController.java` | CREATE (Task 8) |
| `.../support/` harness + BootstrapIT | CREATE (Task 9) |
| `.../TaxCalculationIT.java`, `TaxCatalogIT.java` | CREATE (Task 10) |
| compose verify + fleet compile | Task 11 |
| final review + enable checklist | Task 12 |

---

### Task 1: pom deps + ErrorCode TAX-8xxx + ApiPaths + i18n

**Files:**
- Modify: `tax-service/pom.xml`, `utils/common-core/.../ErrorCode.java`, `utils/common-core/.../constants/ApiPaths.java`, `messages_en.properties`, `messages_vi.properties`

**Interfaces:**
- Produces: `ErrorCode.TAX_CLASS_NOT_FOUND` (TAX-8001, 404), `NO_MATCHING_RATE` (TAX-8002, 404), `DUPLICATE_TAX_RATE` (TAX-8003, 409), `TAX_CLASS_IN_USE` (TAX-8004, 409); `ApiPaths.BACKOFFICE_TAX_CLASSES = "/api/v1/backoffice/tax-classes"`, `ApiPaths.BACKOFFICE_TAX_RATES = "/api/v1/backoffice/tax-rates"`; i18n keys `tax.class.not_found`, `tax.rate.no_match`, `tax.rate.duplicate`, `tax.class.in_use`.

- [ ] **Step 1: pom deps** — copy promotion-service/pom.xml dependency list, REMOVE `spring-kafka` + `common-kafka` (no Kafka here), keep: common-core, common-spring, common-security, common-keycloak, spring-boot-starter-web, data-jpa, validation, actuator, postgres driver, liquibase, lombok, test starters. Parent + `common-library.version` identical to promotion's.

- [ ] **Step 2: ErrorCode block** — at `ErrorCode.java:97` change
  `PROMOTION_RESERVATION_VERSION_CONFLICT("PRO-7011", "promotion.reservation.version.conflict", HttpStatus.CONFLICT);`
  to end with `,` then append:
  ```java
          TAX_CLASS_NOT_FOUND("TAX-8001", "tax.class.not_found", HttpStatus.NOT_FOUND),
          NO_MATCHING_RATE("TAX-8002", "tax.rate.no_match", HttpStatus.NOT_FOUND),
          DUPLICATE_TAX_RATE("TAX-8003", "tax.rate.duplicate", HttpStatus.CONFLICT),
          TAX_CLASS_IN_USE("TAX-8004", "tax.class.in_use", HttpStatus.CONFLICT);
  ```
- [ ] **Step 3: ApiPaths** — add `String BACKOFFICE_TAX_CLASSES = "/api/v1/backoffice/tax-classes"; String BACKOFFICE_TAX_RATES = "/api/v1/backoffice/tax-rates";` next to `BACKOFFICE_PROMOTIONS`.
- [ ] **Step 4: i18n** — EN: `tax.class.not_found=Tax class not found`, `tax.rate.no_match=No tax rate matches the requested destination`, `tax.rate.duplicate=Duplicate tax entry`, `tax.class.in_use=Tax class still has active rates and cannot be deleted`; VI: `tax.class.not_found=Không tìm thấy nhóm thuế`, `tax.rate.no_match=Không có mức thuế phù hợp cho điểm đến`, `tax.rate.duplicate=Thông tin thuế bị trùng lặp`, `tax.class.in_use=Nhóm thuế vẫn còn mức thuế đang hoạt động, không thể xóa`.
- [ ] **Step 5: Verify** — `./mvnw -pl utils/common-core,utils/common-spring install -q` green; `./mvnw -pl tax-service compile -q` green.
- [ ] **Step 6: Commit** — `feat(tax-service): deps + TAX-8xxx error codes + api paths + i18n`

### Task 2: application.yml + Liquibase schema

**Files:** Create `tax-service/src/main/resources/application.yml`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changelog-001-initial-schema.yaml`

**Interfaces:** Produces tables `tax_classes`, `tax_rates` (column names below are 1:1 with Task 3 entities).

- [ ] **Step 1: application.yml** — promotion's yml minus kafka/cache sections: `server.port: ${SERVER_PORT:8091}`, `spring.liquibase.change-log: classpath:db/changelog/db.changelog-master.yaml`, `shop.security.*` (issuer-uri env, csrf-disabled, stateless-session), NO public-paths (fail-closed).
- [ ] **Step 2: changelog-001** — two `createTable`s + indexes:
  - `tax_classes`: `id UUID`, `name VARCHAR(120) NOT NULL`, `default_rate_pct NUMERIC(5,2) NOT NULL`, full audit set exactly as promotion's Campaign (`created_at TIMESTAMPTZ`, `updated_at`, `created_by VARCHAR(80)`, `updated_by`, `version BIGINT` default 0, `deleted BOOLEAN` defaultValueBoolean 0, `deleted_at`, `deleted_by`), CHECK `default_rate_pct >= 0 AND <= 100`.
  - `tax_rates`: `id UUID`, `tax_class_id UUID NOT NULL` FK → tax_classes(id), `country CHAR(2) NOT NULL`, `postal_code VARCHAR(16) NULL`, `rate_pct NUMERIC(5,2) NOT NULL` CHECK 0..100, same audit set.
  - Partial uniques + expression index via raw SQL changesets:
    ```sql
    CREATE UNIQUE INDEX uk_tax_class_name_live ON tax_classes (lower(name)) WHERE deleted = false;
    CREATE UNIQUE INDEX uk_tax_rate_dest_live ON tax_rates (tax_class_id, country, COALESCE(postal_code, '')) WHERE deleted = false;
    CREATE INDEX idx_tax_rates_class_country ON tax_rates (tax_class_id, country) WHERE deleted = false;
    ```
- [ ] **Step 3: Verify** — `./mvnw -pl tax-service compile -q`; changelog parses (`db.changelog-master.yaml` lists 001).
- [ ] **Step 4: Commit** — `feat(tax-service): config + liquibase schema (tax_classes, tax_rates)`

### Task 3: Entities

**Files:** Create `.../entity/TaxClass.java`, `.../entity/TaxRate.java`

**Interfaces:** Produces `TaxClass { UUID id; String name; BigDecimal defaultRatePct; }`, `TaxRate { UUID id; UUID taxClassId; String country; String postalCode; BigDecimal ratePct; }` — getters/setters via Lombok; both `extends AbstractMappedEntity`, `@Entity`, `@SQLRestriction("deleted = false")`, explicit `@Version private long version;` field name `version` mapped to the changelog column.

- [ ] **Step 1:** write both entities, mirrors of `Campaign` (annotation-for-annotation; `@Enumerated` not needed — no enums here).
- [ ] **Step 2:** `./mvnw -pl tax-service compile -q` → BUILD SUCCESS.
- [ ] **Step 3: Commit** — `feat(tax-service): TaxClass + TaxRate entities`

### Task 4: Repositories

**Files:** Create `.../repository/TaxClassRepository.java`, `.../repository/TaxRateRepository.java`

**Interfaces:**
- `TaxClassRepository extends JpaRepository<TaxClass, UUID>`: `Optional<TaxClass> findByNameIgnoreCase(String name);` `boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);` (both @SQLRestriction-filtered automatically).
- `TaxRateRepository extends JpaRepository<TaxRate, UUID>`:
  `Optional<TaxRate> findByTaxClassIdAndCountryAndPostalCode(UUID classId, String country, String postalCode);` (exact tier — `@SQLRestriction` makes this live-only)
  `Optional<TaxRate> findByTaxClassIdAndCountryAndPostalCodeIsNull(UUID classId, String country);` (country tier)
  `List<TaxRate> findAllByTaxClassId(UUID classId);`
  `@Query("select count(r) from TaxRate r where r.taxClassId = :classId") long countByClassId(@Param("classId") UUID classId);`
  Duplicate pre-check mirrors the DB expression — `@Query("select count(r) from TaxRate r where r.taxClassId = :c and r.country = :country and coalesce(r.postalCode, '') = coalesce(:postal, '') and (:excludeId is null or r.id <> :excludeId)") long countDuplicate(...)` with `@Param`s `(UUID c, String country, String postal, UUID excludeId)`.

- [ ] **Step 1:** write interfaces exactly as above (derived names verified token-by-token against entity fields).
- [ ] **Step 2:** compile green.
- [ ] **Step 3: Commit** — `feat(tax-service): repositories (fallback lookups + duplicate pre-check)`

### Task 5: DTOs + TaxCalculator

**Files:** Create `.../dto/request/TaxCalculateRequest.java` (+`TaxClassRequest`, `TaxRateRequest`), `.../dto/response/TaxCalculateResponse.java` (+`TaxClassResponse`, `TaxRateResponse`), `.../service/TaxCalculator.java`

**Interfaces:**
- `TaxCalculateRequest(UUID taxClassId, String country, String postalCode, BigDecimal amount)` — records with `@NotNull taxClassId`, `@Pattern(regexp = "^[A-Z]{2}$") country`, `@NotNull @DecimalMin("0.00") amount`; `postalCode` unconstrained (null/blank = country tier). `TaxCalculateResponse(BigDecimal taxAmount, BigDecimal appliedRate)` — field names/types byte-match order's record.
- `TaxCalculator` (static utility): `public static TaxCalculateResponse calculate(BigDecimal amount, BigDecimal ratePct)` — `amount.multiply(ratePct).divide(100, 2, RoundingMode.HALF_UP)`.

- [ ] **Step 1 (RED):** `TaxCalculatorTest`: `calculate(100.00, 10.00)` → tax `10.00`, rate echoed `10.00`; `calculate(0, 10)` → `0.00`; exact half-cent tie `calculate(0.05, 50)` → `0.03` (0.025 HALF_UP); exact-no-tie `calculate(0.04, 50)` → `0.02`; scale check `calculate(1, 7)` → `0.07`. Expected values written literally; no computing-in-test.
- [ ] **Step 2:** run → compile FAIL (class missing).
- [ ] **Step 3 (GREEN):** implement `TaxCalculator`.
- [ ] **Step 4:** run → PASS. Request/Response records compile.
- [ ] **Step 5: Commit** — `feat(tax-service): contracts + TaxCalculator (HALF_UP)`

### Task 6: TaxCalculationService (the D2 chain)

**Files:** Create `.../service/TaxCalculationService.java` (interface), `.../service/impls/TaxCalculationServiceImpl.java`

**Interfaces:** `TaxCalculateResponse calculate(TaxCalculateRequest req)` — `@Transactional(readOnly = true)`. Throws `BusinessException.of(TAX_CLASS_NOT_FOUND, name/id)` / `BusinessException.of(NO_MATCHING_RATE, country+postal)`. Postal normalization: `postalCode == null || isBlank → null` before tier 1.

- [ ] **Step 1 (RED):** `TaxCalculationServiceImplTest` (Mockito): tier-1 hit returns rate row's pct; tier-1 miss + tier-2 hit; both miss → class default; class absent → TAX-8001; all three miss → TAX-8002; blank postal treated as null (verify repo called with `null`); asserts pin `businessException.errorCode`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3 (GREEN):** impl: load class → tier-1 → tier-2 → default → `TaxCalculator.calculate`.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5: Commit** — `feat(tax-service): 3-tier calculate chain (TAX-8001/8002)`

### Task 7: Backoffice services + guards

**Files:** Create `.../service/TaxClassService.java` + `impls/TaxClassServiceImpl.java`, `.../service/TaxRateService.java` + `impls/TaxRateServiceImpl.java`

**Interfaces:**
- Class CRUD: `TaxClassResponse create(TaxClassRequest)`, `update(UUID, TaxClassRequest)`, `get(UUID)`, `list()`, `void delete(UUID)` — `delete` = `markDeleted(auditor)` after `countByClassId(id) > 0 → TAX_CLASS_IN_USE`. Duplicate class name (create + rename) → `DUPLICATE_TAX_RATE` (TAX-8003, param = name) — single duplicate code for both entities per spec D5's uniqueness mandate; Task 1 therefore uses the GENERIC message `tax.rate.duplicate=Duplicate tax entry` / VI `Thông tin thuế bị trùng lặp`.
- Rate CRUD: `create(TaxRateRequest)` / `update(UUID, ...)` both run `countDuplicate(...)` → `DUPLICATE_TAX_RATE`; class must exist (`TAX_CLASS_NOT_FOUND`); `list(UUID classId)`, `get`, `delete` (soft, unguarded).

- [ ] **Step 1 (RED):** `TaxClassServiceImplTest` + `TaxRateServiceImplTest`: create/update/duplicate-name→8003, delete-with-rates→8004, delete-empty ok (row marked deleted), rate create dup→8003 (with NULL postal dup case: existing NULL + request blank → countDuplicate hits), unknown class→8001, update-keeps-guard.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(tax-service): backoffice services + delete guards`

### Task 8: Controllers + WebMvc security matrices

**Files:** Create `.../controller/TaxCalculationController.java`, `BackofficeTaxClassController.java`, `BackofficeTaxRateController.java`

**Interfaces:** `@PostMapping(ApiPaths.BACKOFFICE_TAX_RATES + "/calculate")` `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")` → 200 `ApiResponse<TaxCalculateResponse>` (200, not 201 — fleet POST-ruling). Class/rate controllers: class-level `@PreAuthorize("hasRole('ADMIN')")`, routes under the two ApiPaths constants + `/{id}` GET/PUT/DELETE, list endpoints.

- [ ] **Step 1 (RED):** `TaxCalculationControllerTest` + `BackofficeTax*ControllerTest` (`@WebMvcTest` + `@Import(ApiExceptionHandler.class)` + real security chain): calculate anon 401 / ROLE_USER 403 / SERVICE 200 / ADMIN 200 with pinned JSON field names (`taxAmount`, `appliedRate`); TAX-8xxx → status + `code` field via handler; backoffice ADMIN-only matrix; validation 400 (lowercase country, negative amount).
- [ ] **Step 2:** FAIL → **Step 3:** implement controllers → **Step 4:** PASS + `./mvnw -pl tax-service test` green.
- [ ] **Step 5: Commit** — `feat(tax-service): controllers (calculate SERVICE/ADMIN, backoffice ADMIN)`

### Task 9: Test harness + BootstrapIT

**Files:** Create `tax-service/src/test/java/com/shop/taxservice/support/AbstractIntegrationTest.java`, `.../config/TestLiquibaseConfig.java`, `.../TaxBootstrapIT.java`

**Interfaces:** Harness = promotion's `support/AbstractIntegrationTest` MINUS Kafka (no container, no `shop.kafka.*` property) — PostgreSQL singleton `static { postgres.start(); shutdownHook }`, same `@DynamicPropertySource` set, `@Import({JpaAuditingAutoConfiguration, TestSecurityConfig})`. TestLiquibaseConfig copied verbatim (package rename).

- [ ] **Step 1:** copy + adapt; BootstrapIT asserts context boots, 2 tables exist via `information_schema`, `taxClassRepository.count()==0`.
- [ ] **Step 2:** `./mvnw -pl tax-service test` — all green incl. IT (host-Docker wedge: retry once, document).
- [ ] **Step 3: Commit** — `test(tax-service): singleton harness + bootstrap IT`

### Task 10: Integration suites

**Files:** Create `.../TaxCalculationIT.java`, `.../TaxCatalogIT.java` (both `extends AbstractIntegrationTest`)

- [ ] **Step 1:** `TaxCalculationIT`: seed class+3 rates via repos → calculate tiers 1/2/3 end-to-end through the service; no-match → TAX-8002; unknown id → TAX-8001; rounding `amount=0.05, rate=50 → 0.03` against REAL Postgres numerics.
- [ ] **Step 2:** `TaxCatalogIT`: expression unique index rejects dup (same class/country/NULL-postal second insert → DataIntegrityViolation OR pre-check 8003 path — assert service-level 8003); recreate-after-delete (soft-delete a rate, create same key again → OK — proves `WHERE deleted=false`); soft-deleted class invisible to calculate (TAX-8001); class delete-guard 8004.
- [ ] **Step 3:** `./mvnw -pl tax-service test` green ×1 + once more (flake watch).
- [ ] **Step 4: Commit** — `test(tax-service): calculation + catalog ITs (index, guards, fallback)`

### Task 11: Sweep + compose verify + fleet compile

- [ ] **Step 1:** sweep unused imports/tests count vs plan; fix nits.
- [ ] **Step 2:** compose verify-ONLY: stanza (port 8091, DB taxservice, `<<: [*jwt, *pg-creds]`) already correct — `docker compose config -q` green, **no compose edits in this epic**.
- [ ] **Step 3:** fleet-wide compile (common-core/common-spring touched): `./mvnw -pl order-service,inventory-service,favourite-service,promotion-service compile -q` exit 0.
- [ ] **Step 4: Commit** — `chore(tax-service): sweep + fleet compile check`

### Task 12: Final whole-branch review + enable checklist

- [ ] **Step 1:** review-package from BASE → HEAD; final reviewer: D1 byte-compat vs `TaxServiceClient`/records re-read from order-service; D2 chain; D5 expression index; D7 codes/anchors; security matrices; zero other-service regression (only shared files + tax-service/*).
- [ ] **Step 2:** fix round(s) per review; ledger close.
- [ ] **Step 3:** §8 enable checklist (post-deploy, separate commit when done): `docker compose build tax-service` → `up -d` → health → **then** `SHOP_SERVICES_TAX_ENABLED: "true"` + `SPRING_DATA_REDIS_*` untouched (order already has them) → recreate order-service → place an order and confirm `taxAmount > 0` per tax class.
