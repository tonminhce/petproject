# Order Service — Confirm Hardening & Promotion 2-Phase Integration — Design

> **Status:** rev 1 — draft for review
> **Date:** 2026-08-29
> **Depends on:** promotion-service design (`2026-08-29-promotion-service-design.md`) — one shared contract. This doc is an **amendment to the shipped order-service and inventory-service**; it must be implemented (or consciously skipped) before promotion-service can be flagged `enabled: true`.

## 1. Overview

Shipped order-service has two production gaps this design closes:

1. **Orphan commit:** inventory exposes `POST /reservations/{id}/commit`, but **nothing calls it** — `confirmOrder` does not, there is no Kafka consumer, payment is Phase 8. Inventory reservations are `PENDING` with a 900s TTL; the sweep **releases the stock of any order left `PENDING` longer than 15 minutes** — the order still exists and can be confirmed later, against stock that was returned.
2. **Non-idempotent lifecycle calls:** `InventoryServiceImpl.commit()` / `release()` accept `PENDING` only (`InventoryServiceImpl.java:150-157, 174-181`) — a network-timeout retry after a successful call gets `RESERVATION_INVALID_STATE` (409), which the confirm flow would (wrongly) treat as failure.

Closing these enables design **A**: `confirmOrder` becomes a commit orchestrator — promotion first (cheapest rollback: 1 HTTP), then inventory items (deterministic order) — with half-commit compensation via `release-committed`, idempotent confirm (Idempotency-Key), reconciliation as the safety net, and metrics on every phase.

### 1.1 Decisions log

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | Commit trigger = explicit HTTP from `confirmOrder` (not Kafka consumer) | Fleet pattern; one orchestrator; promotion doc D2 mirrors |
| D2 | Promotion commits first, inventory second | Cheapest rollback — 1 compensating HTTP vs N |
| D3 | Any commit failure → order stays `PENDING`, compensate committed reservations in reverse via `release-committed`, rethrow | All-or-nothing at order level; sweep + reconciliation are belt-and-suspenders |
| D4 | `RESERVATION_NOT_FOUND` during commit is a **failure**, not a silent skip | Retention purge only removes terminal rows — a `PENDING` order whose reservation row is missing is a real inconsistency; fail + let reconciliation investigate. (Explicit rejection of "treat as resolved" from the original proposal.) |
| D5 | `reservationId == null` on an order item (legacy/pre-inventory data) → skip commit, `log.info` | Cheap compatibility; such items simply have nothing to commit |
| D6 | Confirm accepts optional `Idempotency-Key`; reuse `IdempotencyServiceImpl` | Retried confirm after timeout must not double-commit (idempotent branches make it safe anyway, but replay must also return the same 200 body, not re-run orchestration) |
| D7 | Idempotency scope for confirm: `userId` = the **admin's** id from the JWT; `requestHash = SHA-256(orderId.toString())` | `IdempotencyKey.user_id` is `NOT NULL` with unique `(user_id, key)` — do NOT stuff orderId into the userId column; two admins using the same key is a client bug and yields two rows, which is fine |
| D8 | Reconciliation owns stuck `PENDING` orders (> 30 min): all-committed → auto-confirm; all-terminal → auto-cancel; mixed → alert | Bounded automatic recovery; mixed state needs human eyes |
| D9 | OTel/W3C `traceparent` propagation **deferred** | `RestClientConfig` currently propagates nothing (only `Accept` header) — trace injection is new infrastructure; belongs to a common-logging follow-up, not this epic |
| D10 | Coordinator tracks compensation targets as `(type, id)` pairs, never bare UUIDs | Promotion and inventory reservation ids are both UUIDs — indistinguishable after the fact |

## 2. Promotion contract change (order side)

Replace the apply-style call with the reserve/commit triple. DTOs in `com.shop.orderservice.dto.internal`:

```java
// replaces PromotionApplyRequest
public record PromotionReserveRequest(String code, BigDecimal orderAmount, UUID userId, UUID orderId) {}
// replaces PromotionApplyResponse — adds reservationId
public record PromotionReserveResponse(UUID reservationId, BigDecimal discountAmount, BigDecimal finalAmount) {}
```

`PromotionServiceClient` changes:

| Old | New |
|-----|-----|
| `POST /api/v1/backoffice/promotions/apply` | `POST /api/v1/promotions/{code}/reserve` (`ApiPaths.PROMOTIONS` — new constant in common-core) |
| no `Authorization` header | `.header("Authorization", "Bearer " + serviceTokenProvider.getToken())` — SERVICE role, identical to `InventoryServiceClient` (inject the existing `ServiceTokenProvider`) |
| `apply(req)` | `reserve(req)` + `commit(UUID)` + `release(UUID)` + `releaseCommitted(UUID)` + `getReservationState(UUID)` |
| 4xx → `ORDER_PROMOTION_INVALID` | unchanged semantics (any promotion 4xx at reserve = invalid coupon from the user's perspective) |

Keep `isEnabled()` and its defensive zero-discount fallback unchanged — `PricingServiceImpl`'s upfront P1-5 check (`PricingServiceImpl:33-39`) remains the first gate; the client guard is a cheap second net.

## 3. Order entity + Liquibase

- `Order` gains `promotion_reservation_id UUID NULL` (one reservation per order — promotion usage is order-scoped, unlike per-item inventory reservations).
- New Liquibase changeset: `addColumn order.promotion_reservation_id`, nullable, **no backfill** (existing rows simply have no promotion reservation — D5/D6 compatibility).
- **Pre-generated orderId:** `doCreateOrder` currently creates the order at step 3 (`OrderServiceImpl.java:115-121`) *after* pricing (step 2, line 112) — but `PromotionReserveRequest` needs `orderId`. Change: generate `UUID orderId = UUID.randomUUID()` at the top of the saga, set on the entity before persisting (`order.setId(orderId)`). `@GeneratedValue(strategy = GenerationType.UUID)` (`Order.java:19-21`) respects a manually assigned id (Hibernate generates only when null) — **the plan must include a sandbox test proving this before the refactor lands**; fallback if violated: switch the entity to `@UuidGenerator` + assigned-when-present, same semantics.

## 4. Saga changes (`doCreateOrder`)

```text
1. begin idempotency (unchanged)
2. Pre-generate orderId, load cart + items (unchanged order of validation)
3. pricingService.calculate(orderId, userId, items, couponCode)
     ← signature gains orderId; inside, when coupon present:
        promotionReserve = promotionClient.reserve(
            new PromotionReserveRequest(code, subtotal, userId, orderId))
4. Persist Order (+ promotionReservationId = promotionReserve.reservationId()) + items
5. Reserve inventory per item (unchanged) — FAIL:
     releaseAllReservations(reserved)              (unchanged)
     if promotionReservationId != null:
         promotionClient.release(promotionReservationId)   best-effort, log failures
     throw ORDER_RESERVATION_FAILED
6. Clear cart, publish, complete (unchanged)
```

Notes:

- Crash between steps 3 and 5 → the idempotency row stays `IN_FLIGHT`, `abort()` runs on the rethrow path; a client retry re-runs the saga and creates a **new** promotion reservation; the orphaned one expires via TTL sweep. Documented, bounded (≤ 15 min exposure), no order-side action.
- Replay (`begin()` returns cached) skips the saga entirely — no double reserve.
- Cancel flow (existing, `cancelOrder`): `PENDING` → release stock **and** release promotion reservation (add alongside `releaseAllReservationsById`); `CONFIRMED`+ → nothing (already committed; Phase 8 refund).

## 5. Confirm orchestration

### 5.1 Endpoint

```java
@PostMapping("/{orderId}/confirm")
public ApiResponse<OrderResponse> confirm(@PathVariable UUID orderId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    return ApiResponse.ok(orderService.confirmOrder(orderId, userId, isAdmin, idempotencyKey));
}
```

`confirmOrder` mirrors `createOrder`'s pattern: `begin(key, adminUserId, sha256(orderId))` → replay returns cached body; on failure `abort()` + rethrow. `key == null` → skip idempotency entirely (allowed; gateway/client is encouraged to send it).

### 5.2 `OrderCommitCoordinator` (new component, unit-testable in isolation)

```java
public CommitOutcome commitForConfirm(Order order, List<OrderItem> items) {
    List<CompensationTarget> committed = new ArrayList<>();   // (type, id) pairs — D10
    try {
        if (order.getPromotionReservationId() != null) {
            promotionClient.commit(order.getPromotionReservationId());
            committed.add(CompensationTarget.of(PROMOTION, order.getPromotionReservationId()));
        }
        for (OrderItem item : sortedByProductId(items)) {
            if (item.getReservationId() == null) { log.info(...); continue; }   // D5
            try {
                inventoryClient.commit(item.getReservationId());
                committed.add(CompensationTarget.of(INVENTORY, item.getReservationId()));
            } catch (BusinessException ex) {
                if (ex.getErrorCode() == ErrorCode.RESERVATION_NOT_FOUND) throw ex;   // D4 — fail, do NOT skip
                throw ex;
            }
        }
        return CommitOutcome.SUCCESS;
    } catch (RuntimeException ex) {
        compensateInReverse(committed);           // release-committed on each, best-effort
        throw ex;                                  // order stays PENDING (D3)
    }
}
```

- `compensateInReverse`: iterate the list backwards, call `releaseCommitted` (promotion) / `releaseCommitted` (inventory); failures logged + `order.commit.rollback.failed` counter — **never throw** (would mask the original error). Plain reverse loop — no Guava (`Lists.reverse` is not on the classpath).
- Caller (`confirmOrder`) wraps: coordinator success → set `CONFIRMED`/`confirmedAt`, save, `publishStatusChanged(order)` (existing single-arg signature — no "COMMIT_OK" param), complete idempotency. Coordinator throw → idempotency `abort()`, rethrow as `CONFIRM_COMMIT_FAILED` (**new** `ORD-4011`, key `order.confirm.commit.failed`, 409).

### 5.3 Why this is safe (failure model)

| Scenario | State after | Recovery |
|----------|-------------|----------|
| Promotion commit 5xx | nothing committed, order PENDING | user/admin retries; reservations still PENDING until TTL |
| Inventory item k fails | promotion + items 1..k-1 committed → **compensated** via release-committed; item k PENDING → TTL sweep | retry confirm re-runs everything (idempotent branches absorb duplicates) |
| Compensation HTTP itself fails | some rows COMMITTED while order PENDING | **reconciliation** (§6) detects all-committed → auto-confirms |
| Timeout on commit that actually succeeded | retry hits `COMMITTED → return` idempotent branch | invisible |

## 6. Reconciliation scheduler (order side)

`OrderReconciliationScheduler`, `@Scheduled(fixedDelay = order.reconciliation.interval-ms, default 300000)`:

- Candidates: `PENDING` orders with `created_at < now - 30min` (`order.reconciliation.stuck-minutes`).
- For each: poll `promotionClient.getReservationState(promotionReservationId)` (when present) + `inventoryClient.getReservationState(reservationId)` per item. A `404 PRO-7008`/`INV-3003` on a stuck order's reservation is **inconsistent data** (TTL expiry marks EXPIRED, purge is 30 days — within the 30-min window nothing deletes rows) → route to the mixed/alert path, never auto-decide.
- Decide:
  - all COMMITTED → set `CONFIRMED`, `publishStatusChanged`, log `AUTO_CONFIRMED_BY_RECON`;
  - all terminal (RELEASED/EXPIRED) and no committed leftovers → set `CANCELLED`, release nothing (already terminal), publish cancelled;
  - mixed → emit metric `order.reconciliation.mixed` + structured log for alerting; **no automatic action** (D8).
- All state polling is read-only; every mutation goes through the normal service methods so events stay truthful.
- Note: this scheduler does **not** replace inventory's own PENDING sweep — they compose: sweep terminalizes expired reservations; reconciliation finalizes orders whose reservations went terminal without a cancel call.

## 7. Inventory-service amendments (preconditions)

Small, surgical, all **verified against current code**:

### 7.1 Idempotent lifecycle branches — `InventoryServiceImpl.commit()` (line 150) / `release()` (line 174)

- `commit()`: `if (status == COMMITTED) return;` (idempotent retry) before the `PENDING` check; expired-PENDING still → `RESERVATION_EXPIRED` (INV-3004); `RELEASED`/`EXPIRED` → `RESERVATION_INVALID_STATE` (INV-3005) — caller must reconcile, mirroring promotion §5.3.
- `release()`: `if (status == RELEASED || status == EXPIRED) return;` before the `PENDING` check (quota already returned — safe no-op).
- Error codes already exist: INV-3003/3004/3005 — **no enum changes**.
- Blast radius (GitNexus, measured): `commit` upstream = LOW, 2 symbols (`InventoryController.commit` path) — the earlier "CRITICAL / 20 symbols" estimate was wrong.

### 7.2 New endpoint `POST /api/v1/inventory/reservations/{id}/release-committed`

`COMMITTED → RELEASED`: restock `availableQuantity += quantity` (`reservedQuantity` already decremented by commit), set `releasedAt`, publish `inventory.released.v1` with **`previousStatus: "COMMITTED"`** field, evict cache after commit (`cacheService.evictAfterCommit`), all inside the optimistic-retry wrapper (`commitWithRetry` pattern). Idempotent: already-RELEASED/EXPIRED → return OK. PENDING → INV-3005 (use plain release for that).

### 7.3 New endpoint `GET /api/v1/inventory/reservations/{id}/state`

Returns the existing `ReservationResponse` (add `previousStatus`-free — just status/timestamps), `hasRole('SERVICE') or hasRole('ADMIN')`. Read-only, used by reconciliation.

## 8. Observability

New `OrderConfirmMetrics` (Mirror `ProductMetrics` if present; else plain `MeterRegistry` wrapper):

| Metric | Type | Tags | Alert |
|--------|------|------|-------|
| `order.confirm.duration` | Timer | `phase=commit_promotion\|commit_inventory\|publish\|rollback` | p99 > 500ms warn |
| `order.confirm.commit.outcome` | Counter | `result=success\|compensated\|rollback_failed` | `rollback_failed > 0` page |
| `order.confirm.attempts` | Counter | — | — |
| `order.commit.stuck` | Gauge | — | PENDING older than 30 min; > 5 page |
| `order.reconciliation.mixed` | Counter | — | > 0 page |

## 9. Compatibility & migration

- Existing `PENDING` orders: `promotion_reservation_id` is null → promotion step skipped (D5 analog); `reservationId == null` items skipped; null-id orders confirm fine once 7.1 lands (retry after timeout no longer 409s).
- No backfill, no data migration beyond the additive column (§3).
- Feature flags: inventory/promotion commit orchestration is **not** flag-gated — `promotionClient.commit` is skipped when `promotionReservationId == null`, and promotion calls only happen when the flag-enabled saga created one. Inventory commit orchestration activates unconditionally with this release (that is the point: closing the orphan-commit gap).
- Deploy order: **inventory 7.1–7.3 first** (backward compatible additions), then order-service, then promotion-service (which requires none of order's changes at runtime until `enabled: true`).

## 10. Configuration additions (order-service `application.yml`)

```yaml
shop:
  order:
    reconciliation:
      interval-ms: 300000
      stuck-minutes: 30
```

No new Keycloak/URL config — promotion reuses `shop.services.promotion.{url,timeout-ms,enabled}` (already present, `application.yml:50-53`).

## 11. Testing strategy

| Category | Tool | Coverage |
|----------|------|----------|
| Unit | JUnit + Mockito + AssertJ | `OrderCommitCoordinator`: success path, promotion-fail, item-k-fail → reverse compensation order asserted via InOrder, rollback-failure swallowed + metric; `isPromotionReservation`-style magic **absent by construction** (type-tagged targets) |
| Unit | — | Idempotent-branch matrix for inventory `commit`/`release` (7.1): double-commit, commit-after-release, commit-after-expire, double-release |
| `@WebMvcTest` | seed `JwtAuthenticationToken` | confirm endpoint: Idempotency-Key optional/replayed (same body), 409 `ORD-4011` on commit failure |
| Integration | Testcontainers Postgres | confirm end-to-end with WireMock **standalone** stubs for inventory/promotion: happy path; promotion OK + item-2 fail → release-committed called for promotion + item-1; stub WireMock fault injection (`withStatus(500)` / `SocketDisconnect`) for timeout-then-retry idempotency — no Resilience4j test decorators needed |
| Concurrency | ExecutorService + barrier | 2 confirms same order: second replays cached 200 (same key) or 409s (no key) without double-commit |
| Reconciliation | Testcontainers fixtures | all-committed → auto-confirm; all-terminal → auto-cancel; mixed → untouched + metric |
| Compat | Testcontainers fixtures | legacy order with null reservationIds confirms cleanly (D5) |

## 12. Open items / Deferred

- W3C `traceparent` propagation via OTel agent — common-logging follow-up (D9).
- Payment-service (Phase 8) will replace/supersede parts of confirm orchestration (payment-driven confirm); this design deliberately keeps the coordinator swappable.
- Refund flow for CONFIRMED cancels — Phase 8, unchanged.
- `order.commit.stuck` gauge needs a small scheduled counter query — implementation detail for the plan (avoid per-scrape full scans: cache gauge value from the reconciliation tick).

## 13. Cross-references

- `docs/superpowers/specs/2026-08-29-promotion-service-design.md` — the promotion side of the contract (reserve/commit/release/release-committed/state, idempotency contract §5.3).
- `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` — saga (lines 71-195), cancel (200-248), transitions (250-291).
- `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java` — commit/release patch points (lines 150-193).
- `order-service/.../IdempotencyServiceImpl.java` — begin/complete/abort reuse for confirm.

## 14. Changelog

- 2026-08-29 (rev 1): Initial design from brainstorming: design A (confirm-driven commit) + production hardening. Corrections applied to the reviewed proposal: compensation targets as (type,id) pairs (Guava `Lists.reverse` dropped), confirm idempotency scoped by admin userId (not orderId-in-userId-column), `RESERVATION_NOT_FOUND` fails instead of silently succeeding (D4), OTel deferred after verifying `RestClientConfig` propagates nothing today, Resilience4j already present in promotion pom but WireMock fault injection chosen for tests, impact re-measured as LOW/2-symbols.
