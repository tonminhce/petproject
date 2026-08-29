# Promotion Service — Design

> **Status:** rev 1 — draft for review
> **Date:** 2026-08-29
> **Depends on:** order-service confirm-hardening design (`2026-08-29-order-service-confirm-hardening-design.md`) — the two docs share one contract and MUST be read together.

## 1. Overview

Promotion service owns **discount campaigns** (coupon codes) and **coupon usage reservations** — the 2-phase (reserve/commit) lifecycle that order-service consumes during the create-order saga and confirm flow.

**Why 2-phase (decision D1):** order-service calls promotion exactly twice per order — `reserve` during pricing, `commit` during confirm (or `release` on saga failure / PENDING cancel). Burn-at-apply alone (simpler) would permanently consume a per-user coupon use when a saga fails for unrelated reasons (e.g., out of stock). The reserve/commit model mirrors `inventory-service` reservations — the fleet's proven pattern — and gives the same guarantees: a failed order never consumes quota, TTL sweep bounds orphan exposure.

**MVP scope:**

- Campaign CRUD for admins (`/api/v1/backoffice/promotions` — path constant already exists).
- Service-to-service reservation endpoints (`/api/v1/promotions/**`, new `ApiPaths.PROMOTIONS` constant).
- Discount types: `PERCENT` and `FIXED` only. No stacking, no free-shipping, no auto-applied campaigns.
- Constraints enforced at reserve: validity window, active status, min order amount, per-user limit, max redemptions, total budget.
- Kafka events via transactional outbox (consumer-free in MVP; events exist for future notification/search services).

**Non-goals (deferred):** coupon stacking, cart-item-level targeting, auto-apply, referral codes, per-category restriction, admin usage-report UI beyond a paged usage list.

## 2. Architecture

Mirror `inventory-service` (same generation, same conventions):

- Spring Boot 4.1.1, Java 25, `com.shop.promotionservice`.
- PostgreSQL via Spring Data JPA + Liquibase; DB `promotionservice` (docker-compose block already exists, port `8093`, `ServiceRoute.PROMOTION` already exists — both **verify-only** tasks).
- Auth via `common-keycloak` + `common-security` auto-config: **no per-service SecurityConfig**; **NO `shop.security.public-paths`** — every endpoint requires JWT (fail-closed; backoffice rows are business-sensitive).
- **No outbound HTTP clients, no Kafka consumer, no Redis** in MVP — this service exposes APIs and publishes events; it never calls other services. (Consequence: no `ServiceTokenProvider`, no WireMock in tests.)
- Transactional outbox → Kafka topic `shop.promotion.lifecycle.v1` (single topic, typed events — same style as `shop.order.lifecycle.v1`).
- `resilience4j-spring-boot4` is already in the scaffold pom — unused in MVP; leave the dependency (Phase 8 payment integration will use it).

### 2.1 Decisions log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Reserve/commit/release 2-phase (not burn-at-apply) | Per-user-limit coupons must survive saga failure; mirrors inventory reservations |
| D2 | Commit trigger = order-service `confirmOrder` explicit HTTP call (not Kafka consumer) | Fleet pattern is explicit calls; order is the orchestrator; consumer added later if needed |
| D3 | `discountAmount` frozen at reserve (snapshot) | Order persists discount + total at creation; commit must not change money already shown to user |
| D4 | Per-user limit counts `PENDING + CONFIRMED` usages | A pending reservation must block a second concurrent reserve by the same user (no double-dip while first saga is in flight) |
| D5 | Budget/max-redemptions counts `PENDING + CONFIRMED` | Prevent oversell during in-flight sagas; TTL sweep returns expired PENDING to the pool |
| D6 | Usage reservation TTL = 900s (same as inventory) | A create-order saga completes in seconds; 15 min bounds orphan exposure after a mid-saga crash |
| D7 | Expiry semantics: PENDING past `expiresAt` is rejected at commit (`PRO-7009`) and swept to `EXPIRED` | Mirror inventory: expired quota returns to the pool; caller (order confirm) retries idempotently — a reservation that expired was never committed, and reconciliation owns the stuck order |
| D8 | `release-committed` exists from day 1 | Confirm-flow half-commit rollback needs it; inventory is being patched to add the same endpoint (hardening doc §3) — promotion ships it correctly the first time |
| D9 | userId arrives in the reserve **body**, not from JWT | Reservation endpoints are SERVICE-role machine calls; order-service is trusted to supply the end-user id (same trust boundary as inventory's `orderId` in `ReserveRequest`) |
| D10 | Campaign soft-delete (audit entity), usage reservation hard-lifecycle (no soft delete) | Mirror inventory's split: campaign is admin CRUD (needs audit); reservation rows are terminal + purged after 30 days |

## 3. Data model

```text
campaign                     coupon_usage_reservation
--------------------------   ---------------------------------------
id UUID PK                   id UUID PK
code VARCHAR(50) UK*         campaign_id UUID FK → campaign.id
name VARCHAR(255)            user_id UUID NOT NULL
discount_type VARCHAR(10)    order_id UUID NOT NULL
  ('PERCENT'|'FIXED')        order_amount NUMERIC(19,2) NOT NULL   -- snapshot
discount_value NUMERIC(19,2) discount_amount NUMERIC(19,2) NOT NULL -- frozen (D3)
min_order_amount NUMERIC     status VARCHAR(10) NOT NULL
  NULL = no minimum            ('PENDING'|'COMMITTED'|'RELEASED'|'EXPIRED')
starts_at TIMESTAMPTZ NULL   expires_at TIMESTAMPTZ NOT NULL       -- reserved_at + TTL
ends_at TIMESTAMPTZ NULL     reserved_at TIMESTAMPTZ NOT NULL
max_redemptions INT NULL     committed_at TIMESTAMPTZ NULL
total_budget NUMERIC(19,2)   released_at TIMESTAMPTZ NULL
  NULL = unlimited
per_user_limit INT NOT NULL  -- 0 = unlimited; default 1
                             -- NO soft delete, NO audit columns (D10) —
version BIGINT NOT NULL      -- terminal rows purged after 30 days (§5.6)
  (optimistic lock, §5.2)
status VARCHAR(10)           * partial unique: (code) WHERE deleted = false
  ('ACTIVE'|'INACTIVE')
created_by/created_at/
updated_by/updated_at/deleted  -- AbstractMappedEntity (D10)
```

Notes:

- `discount_value` semantics: `PERCENT` → percent (e.g. `10.00` = 10%); `FIXED` → absolute amount in VND. Validation: `PERCENT` requires `0 < value <= 100`; `FIXED` requires `value > 0`.
- Money columns `NUMERIC(19,2)` — same as order-service.
- `coupon_usage_reservation` deliberately does **not** extend `AbstractMappedEntity` (same rationale as inventory `Reservation`: lifecycle fully described by timestamps, terminal rows purged).
- Partial unique index on `campaign.code` (`WHERE deleted = false`) — same pattern as favourite-service `favourites`.
- Index for the per-user check: `(user_id, campaign_id, status)`; index for the sweep: `(status, expires_at)` — mirror inventory's sweep index.
- Index for reconciliation polling: `(order_id)` unique on the reservation table — **one reservation per order** (order-service stores `promotion_reservation_id`, so lookups are by reservation id; the order_id index supports the reconciliation "any usage for this order?" query).

## 4. API surface

### 4.1 Service endpoints — `ApiPaths.PROMOTIONS` (`/api/v1/promotions`, **new constant**)

All require `hasRole('SERVICE') or hasRole('ADMIN')` (same as inventory reservation endpoints; **no public-paths**). Machine callers present a SERVICE-role JWT obtained via `client_credentials` (order-service's `ServiceTokenProvider`).

| Method | Path | Request | Response | Notes |
|--------|------|---------|----------|-------|
| POST | `/promotions/{code}/reserve` | `ReserveRequest { orderAmount, userId, orderId }` | 201 `ApiResponse<ReservationResponse>` | Creates PENDING usage reservation |
| POST | `/promotions/reservations/{reservationId}/commit` | — | 200 empty | Idempotent (§5.3) |
| POST | `/promotions/reservations/{reservationId}/release` | — | 200 empty | Idempotent (§5.3) |
| POST | `/promotions/reservations/{reservationId}/release-committed` | — | 200 empty | Rollback of a COMMITTED reservation (half-commit compensation, D8); idempotent |
| GET | `/promotions/reservations/{reservationId}/state` | — | `ApiResponse<ReservationResponse>` | For order-service reconciliation polling |

`ReserveRequest { UUID userId, UUID orderId, BigDecimal orderAmount }` — `orderId` is **required** (one reservation per order).

`ReservationResponse { UUID reservationId, UUID campaignId, String code, BigDecimal discountAmount, BigDecimal finalAmount, String status, Instant expiresAt }` — `discountAmount`/`finalAmount` frozen at reserve (D3).

### 4.2 Backoffice endpoints — `ApiPaths.BACKOFFICE_PROMOTIONS` (`/api/v1/backoffice/promotions`, **existing constant**)

All require `hasRole('ADMIN')`.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/backoffice/promotions` | Paged, filter by `status` |
| GET | `/backoffice/promotions/{id}` | |
| POST | `/backoffice/promotions` | 201; unique `code` (deleted-safe) → `PRO-7002` |
| PUT | `/backoffice/promotions/{id}` | Full update; **money/status edits do not affect already-reserved discounts** (frozen snapshot, D3) |
| DELETE | `/backoffice/promotions/{id}` | Soft delete; **guard**: campaign with any `PENDING`/`COMMITTED` usage → `PRO-7003` (mirror `INVENTORY_IN_USE`) |
| GET | `/backoffice/promotions/{id}/usages` | Paged usage list (admin audit view) |

## 5. Service layer

### 5.1 Reserve (`CampaignReservationServiceImpl.reserve`)

```text
1. Load campaign by code (not deleted) → else PRO-7001
2. Validate (fail order matters little; all 4xx):
   a. status == ACTIVE and (starts_at == null or now >= starts_at)
      and (ends_at == null or now <= ends_at)              → else PRO-7004
   b. orderAmount >= min_order_amount (when set)           → else PRO-7005
   c. count(usages: user_id, campaign_id,
            status IN (PENDING, CONFIRMED)) < per_user_limit → else PRO-7006
   d. when max_redemptions set:
      count(usages: campaign_id, status IN (PENDING, CONFIRMED))
        < max_redemptions                                   → else PRO-7007
   e. when total_budget set:
      COALESCE(SUM(discount_amount), 0) over
        (status IN (PENDING, CONFIRMED)) + newDiscount
        <= total_budget                                     → else PRO-7007
3. Compute discount (frozen, D3):
   PERCENT: orderAmount * value / 100, scale 2, HALF_UP
   FIXED:   min(value, orderAmount)
4. Insert usage reservation: status PENDING,
   expires_at = now + promotion.reservation-ttl-seconds (default 900)
5. Optimistic-lock guard (§5.2): steps 2c–2e + insert run against the
   campaign row's @Version; on OptimisticLockingFailureException → retry
   (max 3, backoff 50ms*attempt) → exhausted → PRO-7011
6. Outbox: promotion.reserved.v1
7. Return 201 ReservationResponse
```

**Atomicity model (§5.2):** conditions 2c–2e are check-then-insert, serialized by an optimistic-lock bump on the campaign row — the same contention profile as inventory's `@Version` on the stock row, with the same retry wrapper (mirror `ReservationServiceImpl.reserveWithRetry`: 3 attempts, linear backoff, exhausted → version-conflict error). Documented trade-off: hot campaigns serialize per reserve; acceptable for fleet scale.

### 5.3 Idempotency contract (built-in from day 1 — the contract inventory is being patched to match)

```java
// commit(): PENDING → COMMITTED; everything else idempotent-safe
if (r.getStatus() == COMMITTED) return;                    // retry OK
if (r.getStatus() == RELEASED || r.getStatus() == EXPIRED)
    throw PRO-7010;                                        // terminal-wrong-way: caller must reconcile
if (!r.getExpiresAt().isAfter(now())) throw PRO-7009;      // expired pending
r.setStatus(COMMITTED); r.setCommittedAt(now());

// release(): PENDING → RELEASED
if (r.getStatus() == RELEASED || r.getStatus() == EXPIRED) return;  // retry OK
if (r.getStatus() != PENDING) throw PRO-7010;
r.setStatus(RELEASED); r.setReleasedAt(now());

// releaseCommitted(): COMMITTED → RELEASED (half-commit rollback)
if (r.getStatus() == RELEASED || r.getStatus() == EXPIRED) return;  // retry OK
if (r.getStatus() != COMMITTED) throw PRO-7010;
r.setStatus(RELEASED); r.setReleasedAt(now());
```

- All three run inside the same optimistic-retry wrapper as reserve.
- **`PRO-7010` on commit is deliberate fail-closed**: a released/expired reservation being committed means the caller's view diverged — order-service treats it as a commit failure and reconciliation investigates. (Contrast with inventory's patch where release returns OK for both terminal states: there, terminal = quota returned, safe to no-op. Here too — release/releaseCommitted return OK for both terminal states; only **commit** rejects them.)
- Retried commits after a 5xx-timeout-then-success on the wire are handled by the `COMMITTED → return` branch — order-service may retry a commit whose first attempt actually succeeded.

### 5.4 TTL sweep + retention (mirror `ReservationCleanupScheduler`)

- `@Scheduled(fixedDelay = promotion.reservation-cleanup-interval-ms, default 60s)`:
  batch (`promotion.reservation-cleanup-batch-size`, default 500) `PENDING AND expires_at < now()` → status `EXPIRED`, flush+clear per batch. Quota (per-user, redemptions, budget) automatically returns to the pool because all checks count by status.
- `@Scheduled(cron = promotion.reservation-retention-cron, default "0 0 4 * * *")`: purge terminal (`RELEASED`/`EXPIRED`) rows older than `promotion.reservation-retention-days` (default 30).
- `@EnableScheduling` on the application class (same as inventory).

### 5.5 Events (transactional outbox)

Outbox entity mirrors inventory's **divergent** style (no soft delete on outbox rows, lifecycle `PENDING → SENT/FAILED`) — do not "align" it with product-service.

Topic `shop.promotion.lifecycle.v1`, event types (payload style = inventory's dot-style maps):

| Event | Emitted by | Key fields |
|-------|-----------|------------|
| `promotion.reserved.v1` | reserve | campaignId, code, userId, orderId, reservationId, discountAmount |
| `promotion.committed.v1` | commit | same + committedAt |
| `promotion.released.v1` | release **and** releaseCommitted | same + releasedAt + **`previousStatus`** (`PENDING` \| `COMMITTED`) |

`previousStatus` lets future consumers distinguish reserve→release (quota returned unused) from commit→rollback (confirm-flow compensation). Relay: single-thread `@Scheduled` poller, order-preserving per aggregate — copy inventory's relay implementation.

## 6. Configuration (`application.yml`)

```yaml
server:
  port: ${SERVER_PORT:8093}          # overrides common-spring default 8080 (compose maps 8093:8093)
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/promotionservice}
  jpa:
    hibernate.ddl-auto: validate      # fleet convention
    open-in-view: false
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.yaml
shop:
  security:
    csrf-disabled: true               # default anyway; explicit for clarity
    # NO public-paths — every endpoint requires JWT
  promotion:
    reservation-ttl-seconds: 900
    reservation-cleanup-batch-size: 500
    reservation-cleanup-interval-ms: 60000
    reservation-retention-cron: "0 0 4 * * *"
    reservation-retention-days: 30
```

Liquibase changesets: `createTable campaign` (+ `defaultValueBoolean` for `deleted`, `defaultValueBigInt` for `version`), partial unique index on `code` (`where: deleted = false`); `createTable coupon_usage_reservation` + 3 indexes (§3). No seed data.

## 7. Error handling

`PRO-7xxx` (range free — verified against `ErrorCode.java`; FAV-6xxx is the last used block). All added to the shared `ErrorCode` enum + **namespaced i18n keys** (`promotion.*` — the bundle is fleet-wide; `reservation.not.found` is already taken by INV-3003):

| Code | Name | Key | HTTP |
|------|------|-----|------|
| PRO-7001 | CAMPAIGN_NOT_FOUND | promotion.campaign.not.found | 404 |
| PRO-7002 | CAMPAIGN_ALREADY_EXISTS | promotion.campaign.already.exists | 409 |
| PRO-7003 | CAMPAIGN_IN_USE | promotion.campaign.in.use | 409 |
| PRO-7004 | CAMPAIGN_NOT_ACTIVE | promotion.campaign.not.active | 409 |
| PRO-7005 | MIN_ORDER_AMOUNT_NOT_MET | promotion.min.order.amount.not.met | 400 |
| PRO-7006 | PER_USER_LIMIT_EXCEEDED | promotion.per.user.limit.exceeded | 409 |
| PRO-7007 | BUDGET_EXHAUSTED | promotion.budget.exhausted | 409 |
| PRO-7008 | RESERVATION_NOT_FOUND | promotion.reservation.not.found | 404 |
| PRO-7009 | RESERVATION_EXPIRED | promotion.reservation.expired | 409 |
| PRO-7010 | RESERVATION_INVALID_STATE | promotion.reservation.invalid.state | 409 |
| PRO-7011 | RESERVATION_VERSION_CONFLICT | promotion.reservation.version.conflict | 409 |

Exception translation: fleet `ApiExceptionHandler` (common-spring) handles `BusinessException` → `ApiResponse` + i18n. Order-service maps **any 4xx** from promotion to `ORDER_PROMOTION_INVALID` (existing ORD-4008) — promotion's finer-grained codes are for logs/admin UX, not for order's user-facing message.

## 8. Testing strategy

No outbound calls → no WireMock. Three layers:

1. **Unit — `@WebMvcTest`** (controllers): seed `JwtAuthenticationToken` (TestingAuthenticationToken does NOT work with `AuthenticatedUser.current()`), `@Import(ApiExceptionHandler.class)`, `@MockitoBean` service. Both roles asserted per endpoint group (SERVICE vs ADMIN), plus 401/403 paths.
2. **Unit — plain JUnit+Mockito**: discount math (percent round-half-up, fixed capped at orderAmount), validation order, idempotency contract (§5.3 — all branches: double-commit, commit-after-release, commit-after-expired, double-release, releaseCommitted-after-release…).
3. **Integration — Testcontainers PostgreSQL** (real Liquibase + real `@Version` behavior):
   - reserve happy path + every PRO-7xxx validation branch;
   - per-user limit under concurrency: N threads reserve same (user, campaign), exactly `per_user_limit` succeed, others get version-conflict-retry → 409;
   - budget check across PENDING: reserve → TTL-expire via sweep → reserve again succeeds (quota returned);
   - commit/release/releaseCommitted state machine incl. expiry rejection;
   - sweep + retention jobs with fixed clock batches;
   - outbox relay emits 3 event types with `previousStatus` set correctly on rollback path.

## 9. Open items / Deferred

- Coupon stacking, auto-apply, item-level targeting — deferred.
- Kafka consumer (e.g., auto-release on `order.cancelled` instead of order-driven release) — unnecessary while order drives the lifecycle explicitly; revisit if a second consumer of usage state appears.
- `previousStatus` consumer semantics — recorded in events now, consumed never (MVP).
- Usage reporting/analytics — admin usage list only.

## 10. Cross-references

- `docs/superpowers/specs/2026-08-29-order-service-confirm-hardening-design.md` — reserve/commit/release caller side, `OrderCommitCoordinator`, reconciliation polling.
- `inventory-service` `ReservationServiceImpl` / `ReservationCleanupScheduler` — implementation templates for §5.1–§5.4.
- `ErrorCode.java` (common-core) — PRO-7xxx insertion point: after the FAV-6xxx block. **Anchor:** `PAYMENT_NOT_FOUND` (`PAY-5002`) is currently the enum's last entry and ends with `;` — insert the PRO block after `CART`/FAV entries with `,` separators; the block's last entry also ends with `,` so `PAYMENT_NOT_FOUND(...);` remains the sole terminator (same rule as order plan Task 1).
- `ApiPaths` — add `PROMOTIONS` constant; reuse existing `BACKOFFICE_PROMOTIONS`.

## 11. Changelog

- 2026-08-29 (rev 1): Initial design. Decisions D1–D10 from brainstorming session (2-phase reserve/commit chosen over burn-at-apply; commit trigger = order confirm explicit call; frozen discount snapshot; PENDING+CONFIRMED counting; TTL 900s; releaseCommitted from day 1; body-carried userId).
