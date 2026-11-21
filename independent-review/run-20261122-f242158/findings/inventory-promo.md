# Independent Concurrency-Correctness Audit — inventory-service, promotion-service, product-service

- Revision audited: `f242158265163d65ef73d378331d2bd7a92479ee`
- Repo root: `/Users/tonminh-mac/IdeaProjects/untitled5`
- Auditor: independent distributed-systems auditor (zero-trust: only source read in this session; `docs/production-audit` claims ignored)
- Method: full read of main source + Liquibase migrations of the three services; targeted reads of `order-service` checkout path and `utils/common-*` classes where the audit questions depend on them. No maven, no docker, no tests executed — **no finding below is CONFIRMED_BY_REPRODUCTION**; test files were read as source evidence only.
- Write scope honored: this file is the only artifact written.

---

## Part A — Verified controls (answers to audit questions, with evidence)

These are mechanisms read in source that are **correct**; they are stated first so the findings can be judged against what is actually guaranteed.

### A1. Reservation model (INV Q1, Q5)
- Table `inventory`: `available_quantity` (physical on-hand), `reserved_quantity` (soft holds), `version BIGINT`, unique `product_id` — `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml:7-31`, `safety_stock_threshold` added `changelog-002-safety-stock-threshold.yaml:6-14`.
- **Saleable stock is derived** `(available_quantity - reserved_quantity)`; it is not a stored column. `reserve` guards on it (`InventoryRepository.atomicReserve`, `InventoryRepository.java:18-23`); the public API additionally exposes `InventoryResponse` with both raw columns (`InventoryMapper.java:20-28`).
- Table `reservations`: `product_id, quantity, status(PENDING|COMMITTED|RELEASED|EXPIRED), created_at, expires_at, committed_at, released_at, order_id` (`changelog-001-initial-schema.yaml:33-66`), `version BIGINT` (`changelog-004:7-15`), `idempotency_key VARCHAR(64)` with `uk_reservations_idempotency_key` (`changelog-007:6-14`), index on `order_id` (non-unique, `changelog-006:6-9`).
- DB checks: `ck_inventory_available_non_negative`, `ck_inventory_reserved_non_negative` — both `NOT VALID` (`changelog-004:17-29`). There is **no** `available_quantity >= reserved_quantity` check and **no** `quantity > 0` check on reservations (quantity>0 only via `@Positive` on the DTO, `ReserveRequest.java:9`).
- `reservations.id` is the PK (UUID) — reservation-id uniqueness is the PK. `order_id` has **no** unique constraint (see INV-1).

### A2. RESERVE path (INV Q2) — atomic guarded UPDATE, no oversell on the last unit
`InventoryServiceImpl.reserve` (`InventoryServiceImpl.java:136-169`):
1. Optional idempotency-key replay lookup (lines 139-145).
2. `saveAndFlush` the PENDING reservation row (lines 147-155).
3. `inventoryRepository.atomicReserve(productId, qty)` (line 157):
   `UPDATE inventory SET reserved_quantity = reserved_quantity + :qty, last_updated=now, version=version+1 WHERE product_id=:productId AND (available_quantity - reserved_quantity) >= :qty`
4. `updated == 0` → `STOCK_INSUFFICIENT`, transaction rolls back including the inserted reservation (lines 158-162).

Interleaving — two concurrent buyers reserving the **last** unit (available=1, reserved=0):
- T1: INSERT R1; UPDATE holds the row lock, sets reserved=1, commits.
- T2: INSERT R2; UPDATE **blocks** on the row lock; after T1 commits, PostgreSQL re-evaluates the WHERE on the newest row version (EvalPlanQual): `1 - 1 >= 1` is false → 0 rows → `STOCK_INSUFFICIENT` → T2 rolls back (R2 insert rolled back with it).
- Exactly one buyer wins. **No oversell at reserve.** The guard is a single SQL statement, so it is safe under any isolation level, on any number of replicas. Verified pattern; regression-covered by `InventoryRepositoryTest` (version mechanics) and the (mock-based, weaker) `InventoryServiceImplReserveConcurrencyTest`.
- No pessimistic lock, no `@Version` check at reserve, no Redis involved in the reserve decision. Redis is used **only** as a read cache (A4).

### A3. COMMIT / RELEASE path (INV Q3)
- `commit` (`InventoryServiceImpl.java:197-232`): idempotent if already COMMITTED; rejects non-PENDING (`RESERVATION_INVALID_STATE`) and expired (`RESERVATION_EXPIRED`); then read-modify-write of both `available` and `reserved` guarded by `@Version` (`Inventory.java:47-48`). Callers go through `commitWithRetry` (3 attempts, 50/100 ms backoff — `ReservationServiceImpl.java:52-81`), so a lost race against a concurrent writer is retried against fresh state.
- `release` (`:234-265`): PENDING→RELEASED only, decrements `reserved` only, version-guarded; idempotent on terminal states.
- `releaseCommitted` (`:267-290`): COMMITTED→RELEASED, adds qty back to `available` (refund/cancel-after-payment). Idempotent on terminal states. (Unit-covered by `ReleaseCommittedTest.java:45-97`.)
- **Expiry sweeper** (`ReservationCleanupScheduler.java:42-96`): every 60 s, per-transaction batch: `claimForExpiry` = guarded UPDATE `status=PENDING→EXPIRED, version=version+1 WHERE id=:id AND status='PENDING'` (`ReservationRepository.java:33-38`); only claimed rows are summed per product and returned to stock by `releaseReserved` (`InventoryRepository.java:25-29`, guard `reserved_quantity >= :qty`).
- **Expiry-vs-commit race is safe**: if the sweeper claims first, commit's JPA save fails the version check → retry re-reads EXPIRED → clean `RESERVATION_INVALID_STATE`. If commit writes first (it holds the reservation row lock), the sweeper's guarded UPDATE re-evaluates against the COMMITTED row → 0 rows → the sweep skips (PostgreSQL re-check semantics). Either order releases stock exactly once. Same trace applies to release/releaseCommitted.
- **Ownership (Q3c)**: `reserve/commit/release/release-committed` require `ROLE_SERVICE|ROLE_ADMIN` **and** `serviceCallerGuard.requireCaller("order-service")` (`InventoryController.java:83-128`; `ServiceCallerGuard.java:21-37` — order-service client id or a human ADMIN). There is no per-user ownership binding on reservations (no user id column at all), so user-level isolation is delegated to order-service; any order-service client credential can release any reservation by id, and a human ADMIN token can too (worth noting, not exploited in source).

### A4. Multi-instance + Redis (INV Q4)
- Redis is a **cache only** (`@Cacheable("inventory")` on `findById`, `InventoryServiceImpl.java:59-66`; `CacheConfig.java:25-33`, TTL 60 s, shared Redis ⇒ eviction after commit, `InventoryCacheService.java:25-36`). Redis is never the source of truth for stock; no SETNX/GETSET/Lua in inventory-service. Stale-cache window analyzed in INV-6.
- No JVM-local state in inventory request paths. Schedulers run on every replica (`@EnableScheduling`, `InventoryServiceApplication.java:8`) but every mutation is guarded by a DB conditional UPDATE (claims), so two replicas are correctness-safe (duplicate work and duplicate events remain — INV-3). No ShedLock/leader election anywhere (grep over poms/sources: no `shedlock`).

### A5. Kafka (INV Q6)
- Producer: transactional **outbox** (`TransactionalInventoryEventPublisher.java:96-120` → `outbox_events`), relayed by `InventoryOutboxRelay` with per-aggregate claim: `pg_try_advisory_xact_lock(hashtextextended(aggregate_id))` + `NOT EXISTS(SENDING)` + `FOR UPDATE SKIP LOCKED` (`OutboxEventRepository.java:29-39`), lease owner + `claimed_at` (`changelog-005/008`), stale-claim reclaim after 60 s (`reclaimStaleSending`, `:82-95`), `@PreDestroy` release, per-aggregate in-order marking (`OutboxBatchOrdering.markableAsSent`).
- Delivery is **at-least-once**: publish-then-crash-before-mark → lease expiry → republish. `eventId` (unique in outbox) is in every payload for consumer dedup. Retry cap 10 → terminal `FAILED` status in the table, **no DLQ topic** (INV-9).
- inventory-service consumes **no** Kafka topics (no `@KafkaListener` in its main tree); consumption idempotency/DLQ concerns apply to product-service consumers (PRD-5) and downstream services.

### A6. Promotion quota model (PROM Q7, Q8)
- Tables: `campaign` (`changelog-001-initial-schema.yaml:3-46`: code unique-when-live `uk_campaign_code_live`, `max_redemptions`, `total_budget`, `per_user_limit`, `version`), quota counters added by `changelog-006-campaign-quota-counters.yaml`: `used_count`, `budget_used` (both NOT NULL, CHECK ≥ 0, backfilled from PENDING+COMMITTED reservations), `campaign_user_usage(campaign_id, user_id, used_count)` with `uk_cuu_campaign_user` and CHECK ≥ 0. Reservations: `coupon_usage_reservation` with `uk_cur_order_id` **unique** (one reservation per order platform-wide), status CHECK, `version` (`changelog-004`), currency CHECK VND|USD (`changelog-008`).
- **Quota decrement is atomic**: campaign claim = guarded native UPDATE `used_count+1, budget_used+:discount WHERE … (max_redemptions IS NULL OR used_count < max_redemptions) AND (total_budget IS NULL OR budget_used + :discount <= total_budget)` (`CampaignRepository.claimQuota:26-31`). Per-user claim = atomic upsert `INSERT … ON CONFLICT (campaign_id,user_id) DO UPDATE SET used_count = used_count+1 WHERE used_count < :limit` (`CampaignUserUsageRepository.claimQuota:13-21`).
- N concurrent redemptions of the **last** coupon: the row/upsert lock serializes; losers re-evaluate the guard and get 0 rows → `BUDGET_EXHAUSTED` / `PER_USER_LIMIT_EXCEEDED`; both claims are in one transaction so a failed campaign claim rolls back the per-user claim (`CampaignReservationServiceImpl.java:81-88`). **No quota breach.** Concretely raced in `CampaignQuotaConcurrencyIT.java:150-195` (Postgres IT, exactly one success).
- Release mirrors it with floor-clamped decrements (`CampaignRepository.releaseQuota:33-37`, `CampaignUserUsageRepository.releaseQuota:23-26`); double-release is impossible: release paths are state-machine-guarded (`CampaignReservationServiceImpl.java:139-175`) plus `claimForExpiry` guarded UPDATE with version bump (`CouponUsageReservationRepository.java:44-50`) — same safe interleaving as inventory (see A3).
- User-specific redemption: `per_user_limit` counts PENDING+COMMITTED per (campaign,user) via the counters; one user **cannot** double-redeem concurrently (atomic upsert guard). One order cannot hold two coupon reservations (`uk_cur_order_id` + `findByOrderId` replay, `CampaignReservationServiceImpl.java:65-76`; concurrent insert loses on the unique index → `PROMOTION_DUPLICATE_REQUEST`, `:101-109`).

### A7. Promotion expiry / multi-instance (PROM Q9, Q11)
- Reservation TTL 900 s, validated at commit (`CampaignReservationServiceImpl.java:127-129`); sweep identical claim-then-release pattern (`ReservationCleanupScheduler.java:49-94`); commit↔sweep interleavings safe by the same version + guarded-claim argument as A3.
- Campaign mid-flow changes: see PROM-1 (commit does **not** re-validate campaign status/expiry; deleted campaigns fail commit).
- Multi-instance: outbox relay claim-lease identical to inventory (`PromotionOutboxRelay.java`, `OutboxEventRepository.java:29-39`); schedulers safe-by-guard; no ShedLock.

### A8. Product (PRD Q12, Q13)
- Price data: `products.price_unit numeric(15,2)` + `currency` (VND|USD, `changelog-001:94`, `changelog-008`), `product_variants.price/currency/quantity` (`changelog-006`).
- **ES sync pipeline = transactional outbox → Kafka topic `shop.product.lifecycle.v1`** (`TransactionalProductEventPublisher.java:49-88` carries price, currency, status, rating; topic constant `KafkaTopicContract.SHOP_PRODUCT_LIFECYCLE`), relayed by the same claim-lease relay. There is **no direct Elasticsearch client in product-service**; the indexer is a downstream consumer (search-service, out of scope). Per-aggregate ordering enforced; at-least-once delivery; PG↔ES consistency is eventual with no reconciliation (PRD-5).
- Caching: product-service caches its own reads (`product`/`productBySlug`, TTL 10 min — `CacheConfig.java:23,33-36`). **order-service maintains a separate `productPrice` Redis cache, TTL 10 min, key `id:v2`** (`order-service/…/client/ProductServiceClient.java:29`, `config/CacheConfig.java:21,31`) — nothing invalidates it (PRD-2). Checkout does **not** re-fetch the authoritative price on every order; it prices from this cached snapshot (`PricingServiceImpl.java:57-97`, `OrderCreateSaga.java:152-180`).
- Reservation consumption from checkout: `OrderCreateSaga.execute` → pricing (incl. promotion reserve) → per-item inventory reserve with compensation releases on failure (`OrderCreateSaga.java:182-209`); commit at payment confirmation via `OrderCommitCoordinator.commitForConfirm` (promotion commit first, then inventory commits fanned out — `OrderCommitCoordinator.java:46-115`).

### A9. Scheduled jobs in the three services (Q13)
| Service | Job | Trigger | Multi-instance behavior | Lock strategy |
|---|---|---|---|---|
| inventory | `ReservationCleanupScheduler.releaseAllExpiredReservations` | fixedDelay 60 s | safe: `claimForExpiry` guarded UPDATE; duplicates read the same batch, losers claim 0 rows | DB conditional UPDATE (no ShedLock) |
| inventory | `ReservationCleanupScheduler.purgeOldExpiredReservations` | cron 04:00 | duplicate bulk DELETE, idempotent | none (harmless) |
| inventory | `InventoryOutboxRelay.relay` | fixedDelay 5 s | per-aggregate advisory xact lock + SKIP LOCKED + SENDING lease | DB claim/lease |
| inventory | `InventoryOutboxRelay.recoverStrandedClaims` | fixedDelay 30 s | guarded reclaim of stale SENDING | DB conditional UPDATE |
| inventory | `OutboxRetentionScheduler.purgeOldSentEvents` | cron 03:00 | idempotent DELETE | none (harmless) |
| promotion | mirror of all five above (`PromotionOutboxRelay`, `ReservationCleanupScheduler.releaseAllExpiredReservations`, `purgeOldTerminalReservations` batched `deleteAllInBatch`, `OutboxRetentionScheduler`) | same | same guarantees; concurrent 30-day purge batches can deadlock internally (one instance aborts and retries next cycle) | DB claim / none |
| product | `OutboxRelay.relay` + `recoverStrandedClaims` + `OutboxRetentionScheduler` | same | same as inventory | DB claim/lease |
| product | `ProductMediaSweepJob.sweep` | cron */30 min | **JVM-local `currentPage` cursor** — each replica sweeps a different rotating page; coverage nondeterministic, self-heals on empty page | none (idempotent clears) |

---

## Part B — Findings

Severity: P0 block-ship / P1 ship-blocker / P2 fix before release / P3 fix or accept with ticket. Confidence per the audit rubric. **No finding was executed/reproduced in this session** (no maven/docker allowed), so `CONFIRMED_BY_REPRODUCTION` appears nowhere.

---

### INV-1 — Keyless reserve + no per-order uniqueness → duplicate stock holds on any retry
- **Severity**: P2. **Confidence**: HIGH_CONFIDENCE_INFERENCE (server-side gap CONFIRMED_FROM_SOURCE; the triggering retry is environment-dependent).
- **Service**: inventory-service. **Files**: `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java:83-101`; `InventoryServiceImpl.java:136-169`; `changelog-001-initial-schema.yaml:33-66` (order_id, non-unique index only).
- **Current behavior**: `POST /api/v1/inventory/{productId}/reserve` proceeds without an `Idempotency-Key` (controller lines 93-95 call the keyless `reserveWithRetry`). `reservations.order_id` is neither unique nor consulted for dedup — every call inserts a fresh PENDING reservation and, if stock allows, a fresh hold. `ReserveRequest.orderId` is stored but never checked against existing PENDING reservations.
- **Expected**: same-order reserve retries must not multiply holds (either require the key, or enforce one active reservation per (product, order), or dedupe on `order_id`).
- **Unsafe interleaving**: order-service calls reserve → inventory commits the hold → response lost (timeout/connection reset) → order-service retries → second reservation for the same (product, order) → stock double-held; if the goods were scarce, other buyers are refused until the 15-min TTL sweep frees the phantom hold. If both reservations are committed after payment, the same unit is sold twice — **oversell** (releaseCommitted path restores only one).
- **Invariant violated**: one active hold per order line; `reserved_quantity` reflects real demand.
- **Business impact**: phantom unavailability (lost sales), and a genuine double-sell window if both reservations are committed.
- **Fix / pattern**: enforce `uk_reservations_product_order_active` (partial unique index `WHERE status IN ('PENDING','COMMITTED')`), or make the key mandatory at the controller, or add read-before-insert dedup on (product_id, order_id) with the same replay semantics as the key path.
- **Regression test**: integration — reserve twice with identical (productId, orderId), no key, assert one PENDING row and `reserved_quantity` incremented once; repeat with the key path to keep replay semantics.

### INV-2 — Idempotency-key replay returns terminal/stale reservations as if current
- **Severity**: P2. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: inventory-service. **Files**: `InventoryServiceImpl.java:139-145, 171-181`.
- **Current behavior**: `replayReservation` verifies only product id and quantity match; it does not check `status` or `expires_at`. A retry (same key) after the reservation expired, was released, or was committed returns the old row with its terminal/`PENDING`-but-expired state presented as a valid hold.
- **Expected**: replay of a dead reservation should create a fresh hold (or fail with a clear error), never present a dead hold as live.
- **Unsafe interleaving**: buyer reserves (key K) → abandons → 16 min later order-service retries reserve with K → receives the EXPIRED reservation → proceeds to payment → commit fails `RESERVATION_EXPIRED`; meanwhile the sweeper has already given the unit to another buyer. Customer-visible failure at the worst moment; combined with INV-4 it is a lost-sale generator, not an oversell (commit stays guarded).
- **Invariant violated**: response of reserve ⇒ live hold.
- **Business impact**: paid checkout failures after successful reserve replay.
- **Fix / pattern**: in `replayReservation`, if status ≠ PENDING or `expires_at` ≤ now, create a new reservation with the same key (delete/rotate the key constraint) or return 409 with the terminal state.
- **Regression test**: integration — reserve with key; expire the reservation (sweep); reserve again with the same key; assert a NEW PENDING reservation (or documented 409), never the EXPIRED row.

### INV-3 — Expiry sweeper emits phantom/duplicate `inventory.released.v1` events for rows it did not release
- **Severity**: P2. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: inventory-service. **Files**: `ReservationCleanupScheduler.java:71-96` (claim loop `:73-80` skips `claim==0`, but the event loop `:91-95` iterates the **whole batch**), event content `TransactionalInventoryEventPublisher.java:60-70`.
- **Current behavior**: for each reservation in the selected batch — including rows whose `claimForExpiry` returned 0 (claimed by another replica, or concurrently COMMITTED by order-service) — the sweep publishes `inventory.released.v1` with `previousStatus=PENDING`. Two replicas sweeping concurrently both publish for the same row; a concurrently-committed reservation also gets a "released" event.
- **Expected**: publish only for rows this sweep actually claimed (`claimed > 0`), with the authoritative DB state in the payload.
- **Unsafe interleaving**: order-service commits reservation R at payment; the sweep's 60 s tick had already batch-read R as PENDING+expired; commit wins the row; sweep publishes released(PENDING) anyway → a downstream saga/CDС-style consumer treats a committed reservation as released (e.g., un-cancels an order, decrements analytics, or restocks in a CQRS projection). Duplicate eventIds also defeat consumer dedup (different eventId, same logical release).
- **Invariant violated**: every emitted event corresponds to a state transition performed by the emitter.
- **Business impact**: downstream state corruption risk (order saga/projections), duplicated events.
- **Fix / pattern**: move the publish inside the `claimed > 0` branch; re-read the reservation state before publishing; include the claim-transaction's view of status.
- **Regression test**: integration — seed one expired PENDING reservation and one concurrently COMMITTED row in the same batch window; run sweep; assert no released event for the committed row and exactly one for the expired row.

### INV-4 — Fixed 15-minute reservation TTL with no extension: paid-order stock-loss window
- **Severity**: P2. **Confidence**: mechanism CONFIRMED_FROM_SOURCE; business frequency NEEDS_RUNTIME_VERIFICATION (depends on payment-provider latency distribution).
- **Service**: inventory-service (+ order-service as consumer). **Files**: `InventoryServiceImpl.java:45-46, 209-211`; `application.yml:47` (`reservation-ttl-seconds: 900`); `ReservationCleanupScheduler.java:42-51` (sweep interval 60 s); `order-service/…/OrderCommitCoordinator.java:46-115` (commit at payment confirmation).
- **Current behavior**: reservations expire 900 s after reserve; `commit` refuses any reservation past `expires_at` **even if the stock is not yet freed**; the sweep then returns the units to sale ≤60 s later. There is no extend/heartbeat endpoint.
- **Expected**: reservation lifetime should track the payment window (extendable TTL, or commit-with-grace, or explicit handoff of the hold to the payment phase).
- **Unsafe interleaving**: order O reserves the last unit at T0 → buyer completes 3DS/bank-redirect payment at T0+16 min → commit(O) → `RESERVATION_EXPIRED` (unit may already be re-reserved by buyer B) → paid order without stock → refund/cancel flow. This is the classic "expired+released while its order is paying" double-sell window; commit's expiry check prevents oversell of the **unit**, but the **payment** is stranded.
- **Invariant violated**: a paid order must not lose its reserved stock while its reservation was never released by its owner.
- **Business impact**: refunds, support load, lost sales on slow payment rails; inventory numbers stay consistent (no oversell).
- **Fix / pattern**: (a) reservation `extend` endpoint called by payment initiation; (b) commit with a grace period where the sweeper has not yet freed the stock (claim-style `status='PENDING' AND expires_at BETWEEN now-grace AND now` transition to COMMITTED); (c) order-service should re-reserve on `RESERVATION_EXPIRED` before failing the payment confirmation.
- **Regression test**: integration — reserve at T, advance past TTL without sweep, attempt commit (assert RESERVATION_EXPIRED), run sweep, re-reserve by another buyer, then verify the first order's failure path is compensated (release/refund) exactly once.

### INV-5 — commit/release rely on optimistic-lock retry; hot products exhaust 3 attempts → checkout 409s
- **Severity**: P3. **Confidence**: mechanism CONFIRMED_FROM_SOURCE; impact NEEDS_RUNTIME_VERIFICATION (load-dependent).
- **Service**: inventory-service. **Files**: `InventoryServiceImpl.java:212-232, 250-264` (read-modify-write + `@Version`); `ReservationServiceImpl.java:23-24, 66-81` (3 attempts, 50/100 ms backoff).
- **Current behavior**: unlike `reserve` (single guarded UPDATE), `commit`/`release` do read-modify-write on the inventory row. On a hot product every concurrent commit/release serializes through version conflicts; after 3 attempts the caller gets `INVENTORY_VERSION_CONFLICT`.
- **Expected**: commit should be a guarded atomic UPDATE (like reserve/releaseReserved), e.g. `UPDATE inventory SET available=available-:q, reserved=reserved-:q WHERE product_id=:p AND available>=:q AND reserved>=:q` after a guarded `reservations.status PENDING→COMMITTED` claim — eliminating the retry loop entirely.
- **Unsafe interleaving**: N simultaneous payment confirmations of the same product → N−1 retry storms → some cross the attempt cap → `OrderCommitCoordinator` marks the item failed → order compensation for a sale that would have succeeded.
- **Invariant violated**: none (stock stays consistent) — availability only.
- **Business impact**: failed checkouts under launch/flash-sale load.
- **Fix / pattern**: convert commit/release to two guarded atomic UPDATEs (reservation claim first, inventory second), mirroring the sweep's claim+release pattern.
- **Regression test**: concurrency — 50 threads committing distinct reservations for one product; assert all succeed (or bounded-cap failures), no lost updates, `available+released == expected`.

### INV-6 — Redis `inventory` cache stale-put race (≤60 s stale stock reads)
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE (mechanism); exploitability NEEDS_RUNTIME_VERIFICATION.
- **Service**: inventory-service. **Files**: `InventoryServiceImpl.java:59-66` (`@Cacheable`), `InventoryCacheService.java:21-47` (after-commit evict), `CacheConfig.java:25-33` (shared Redis, TTL 60 s).
- **Current behavior**: classic cache-aside race: reader transaction loads the pre-commit row (cache miss), writer commits and evicts, reader then populates the just-evicted entry with the old value → stale until TTL (60 s). Affects only `GET /inventory/{productId}` style reads (PDP availability display); the order path uses the uncached reserve RPC.
- **Expected**: versioned cache keys or write-through on the mutation path; or accept and document the 60 s staleness for display-only reads.
- **Interleaving**: listed above; no oversell (reserve never reads the cache).
- **Fix / pattern**: include `version` in the cache key, or evict with a short delay + TTL as now, and keep stock reads for checkout on the transactional path (already the case).
- **Regression test**: not warranted beyond documenting; optionally a concurrency test asserting the cache never blocks a reserve decision.

### INV-7 — Admin `create()` inserts NULL `safety_stock_threshold` (Lombok `@Builder.Default` + ModelMapper no-arg construction)
- **Severity**: P2 (functional break of an admin endpoint; not concurrency). **Confidence**: HIGH_CONFIDENCE_INFERENCE (Lombok semantics well-defined; insert path not covered by any real-DB test — `InventoryControllerTest.java:86-89` mocks the service).
- **Service**: inventory-service. **Files**: `entity/Inventory.java:43-48` (`@Builder.Default` on `safetyStockThreshold`, `availableQuantity`, `reservedQuantity`, `version`); `mapper/InventoryMapper.java:30-36` (sets only available/reserved); `changelog-002-safety-stock-threshold.yaml:9-14` (`NOT NULL`).
- **Current behavior**: with Lombok `@Builder.Default` the field initializer applies only through the builder; `ModelMapper.map(request, Inventory.class)` constructs via the no-arg constructor → `safetyStockThreshold == null` at INSERT → NOT NULL violation → admin create fails at flush (HTTP 500). Same class of issue would apply to `version` if Hibernate did not default null `@Version` on persist (it does).
- **Expected**: `POST /api/v1/inventory` creates a row with the default threshold.
- **Fix / pattern**: initialize via constructor/`Inventory.builder()` in the mapper, or drop `@Builder.Default` in favor of field initializers; add a real-DB insert test.
- **Regression test**: DataJpaTest (Postgres) — `mapper.toEntity(req)` → `saveAndFlush` → assert row with threshold=10.
- **Note**: needs one runtime check (`./mvnw -pl inventory-service test`) by the owning team to downgrade/upgrade confidence.

### INV-8 — DB invariant gaps: no `available >= reserved` CHECK; constraints `NOT VALID`; no `quantity > 0`
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Files**: `changelog-004-stock-invariants-and-reservation-version.yaml:17-29`; `changelog-001-initial-schema.yaml:47` (quantity INTEGER, no CHECK).
- **Current behavior**: the DB enforces only `available ≥ 0` and `reserved ≥ 0` for new writes (NOT VALID = existing rows unvalidated). The cross-column invariant `available_quantity ≥ reserved_quantity` (equivalently derived saleable ≥ 0) lives only in app SQL guards; a stray SQL migration/script could desynchronize silently. `reservations.quantity` accepts ≤ 0 at the DB level (DTO `@Positive` is the only guard).
- **Expected**: add `CHECK (available_quantity >= reserved_quantity) NOT VALID`; add `CHECK (quantity > 0)`; consider validating existing rows.
- **Business impact**: defense-in-depth against tooling/manual data fixes.
- **Fix / pattern**: Liquibase changeSets with the two CHECKs (safe: guards already keep the invariant in app paths).

### INV-9 — Outbox `FAILED` events are terminal with no DLQ topic or reconciliation alert
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Files**: `InventoryOutboxRelay.java:225-251` (mark FAILED after `max-retries=10`), `application.yml:54`; retention only deletes SENT (`OutboxRetentionScheduler.java:21-33`).
- **Current behavior**: after 10 failed publish attempts an event stays `FAILED` in `outbox_events` forever (log + `last_error` only). No dead-letter Kafka topic, no republish tooling, no metric-based alert beyond `inventory.outbox.pending.count` (FAILED not counted).
- **Expected**: DLQ topic or ops runbook/republish endpoint + alert on FAILED count.
- **Business impact**: permanent loss of inventory events (downstream projections drift) during prolonged broker incidents.
- **Fix / pattern**: publish to `<topic>.dlq` on final failure; add FAILED gauge + alert; document manual republish.

---

### PROM-1 — `commit()` honors deactivated/expired campaigns but **fails** on soft-deleted campaigns (inconsistent mid-flow semantics)
- **Severity**: P2. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: promotion-service. **Files**: `CampaignReservationServiceImpl.java:114-135` (commit validates only reservation expiry; campaign loaded only for the event at `:130`, `:202-206`), `Campaign.java:16` (`@SQLRestriction("deleted = false")`), `CampaignServiceImpl.java:105-115` (delete guard + markDeleted), reserve-time validation `:51-63`.
- **Current behavior**: at reserve the campaign must be ACTIVE within its window; at commit **no** campaign re-validation occurs: a campaign deactivated, moved out of its window, or re-budgeted after reserve still commits with the pinned `discountAmount` — while a **soft-deleted** campaign makes `loadCampaign` throw `CAMPAIGN_NOT_FOUND`, failing the commit of a previously reserved discount.
- **Expected**: pick one semantic and enforce it symmetrically: either the reservation is a binding quote (commit must succeed regardless of campaign state, including deleted), or commit re-validates status/window consistently.
- **Unsafe interleaving**: checkout reserves discount at T0 → admin deactivates campaign at T1 → payment confirm at T2: commit succeeds (deactivation ignored). Same flow with admin **delete** at T1: commit fails → paid order loses its discount → refund/compensation. The difference is invisible to order-service until confirm-time.
- **Invariant violated**: quote validity must not depend on an unrelated admin operation class (status flip vs soft delete).
- **Business impact**: unpredictable checkout failures after campaign maintenance; honored discounts on intentionally killed campaigns (promo budget overrun).
- **Fix / pattern**: make commit campaign-state-independent (store code/discount on the reservation — already done — and skip the campaign load, or use a read that ignores `@SQLRestriction` for event enrichment); if budgets must be re-checked, do it at reserve only.
- **Regression test**: integration — reserve → deactivate (commit succeeds) and reserve → soft-delete (commit succeeds) as the documented behavior; assert used_count/budget accounting stays symmetric.

### PROM-2 — Campaign `update()` allows counters-breaking limits and silently resets `perUserLimit`
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: promotion-service. **Files**: `CampaignServiceImpl.java:78-103` (`update` sets maxRedemptions/totalBudget/perUserLimit with no comparison to `used_count`/`budget_used`; `:98` defaults omitted `perUserLimit` to 1), `changelog-006:60-67` (only `≥ 0` CHECKs).
- **Current behavior**: admin can set `max_redemptions` below `used_count` or `total_budget` below `budget_used` (all further claims blocked — no overspend, but a silent campaign freeze); a PUT omitting `perUserLimit` resets a configured 5 to 1, retroactively locking out users.
- **Expected**: reject limit reductions below current usage, or accept explicitly (force flag); treat omitted fields as "unchanged" in PUT semantics.
- **Business impact**: campaign outages/misconfigurations; no quota breach (guards hold).
- **Fix / pattern**: validation in `update` against `used_count`/`budget_used`; merge semantics for PUT.
- **Regression test**: service tests — update below usage rejected; PUT without perUserLimit preserves prior value.

### PROM-3 — `delete()` guard-then-act race: soft-deleted campaign with live reservation; quota/event handling silently skips
- **Severity**: P3. **Confidence**: HIGH_CONFIDENCE_INFERENCE (window is small; all steps CONFIRMED_FROM_SOURCE).
- **Service**: promotion-service. **Files**: `CampaignServiceImpl.java:105-115` (exists-check then markDeleted, no lock), `CampaignRepository.releaseQuota:33-37` (`WHERE deleted=false` → 0-row no-op), `ReservationCleanupScheduler.java:73-78` (`findById(...).ifPresent` — filtered by `@SQLRestriction`, so no released event), `CampaignReservationServiceImpl.java:202-206` (commit `CAMPAIGN_NOT_FOUND`).
- **Unsafe interleaving**: T1 delete(): counted-reservation check passes → T2 reserve(): campaign still live, claims quota, inserts PENDING reservation, commits → T1 `markDeleted` + save (native claim doesn't bump `campaign.version`, so the JPA version check passes) → campaign deleted with an outstanding reservation. Order pays → commit fails `CAMPAIGN_NOT_FOUND` (PROM-1 asymmetry); sweep later: quota release no-ops on the deleted row and no event is published — counters permanently orphaned.
- **Expected**: delete must atomically exclude live reservations (conditional UPDATE `… WHERE deleted=false AND NOT EXISTS(live reservation)`, or lock the campaign row), and the sweep/commit must handle deleted campaigns deterministically.
- **Business impact**: rare paid-order discount failure; harmless counter drift on a dead campaign; missing lifecycle event.
- **Fix / pattern**: single guarded DELETE statement; treat "campaign deleted" explicitly in commit/sweep semantics.
- **Regression test**: integration — interleave delete and reserve (two threads + barrier); assert either delete fails or the reservation is rejected; never a deleted campaign with a live reservation.

### PROM-4 — Concurrent same-order reserves: loser gets an error, not the winner's reservation (retry-hostile)
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: promotion-service. **Files**: `CampaignReservationServiceImpl.java:65-76` (findByOrderId replay), `:101-109` (DIV → `PROMOTION_DUPLICATE_REQUEST`).
- **Current behavior**: two concurrent reserves with the same `orderId` race the `findByOrderId` check; the unique index `uk_cur_order_id` rejects the loser, which surfaces `PROMOTION_DUPLICATE_REQUEST` (4xx) instead of replaying the winner's reservation (inventory does replay its equivalent race — `ReservationServiceImpl.java:38-49`).
- **Expected**: on unique-key loss, re-read by orderId and return the existing PENDING reservation.
- **Business impact**: occasional spurious checkout failures only if a caller retries concurrently; otherwise benign.
- **Fix / pattern**: mirror inventory's catch-DIV-then-replay.
- **Regression test**: two threads reserve with the same orderId; assert one 201 and one equal 200 (same reservationId), never a hard error.

### PROM-5 — Currency handling: VND computed/stored at scale 2 with HALF_UP rounding; no zero-decimal treatment
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE (design observation).
- **Service**: promotion-service (+ platform constant). **Files**: `DiscountCalculator.java:11-24`; `changelog-001-initial-schema.yaml:19-20` (numeric(19,2)); `changelog-008:17-18` (CHECK currency IN ('VND','USD')); `PlatformCurrency.java:8-10`.
- **Current behavior**: PERCENT rounds HALF_UP at scale 2 (deterministic, order-independent — good); FIXED clamps to `orderAmount` (`value.min(orderAmount)`, line 22 — prevents over-discount and negative totals); but VND is a zero-decimal currency handled at scale 2 end-to-end (order totals, tax, budget counters), so sub-cent arithmetic exists in a currency that has no sub-units. Stacking: only one coupon per order (`uk_cur_order_id`), no stacking rules needed at this layer.
- **Expected**: either define scale per currency (0 for VND) platform-wide, or document 2-dp VND as intentional.
- **Business impact**: cosmetic amounts in VND (e.g., 199.99 ₫), potential penny drift vs payment provider rounding.
- **Fix / pattern**: currency-aware MONEY_SCALE in `DiscountCalculator` + migration to integer minor units for VND if the payment gateway requires it.

---

### PRD-1 — No optimistic locking on `Product`: detached full-row merge silently reverts concurrent writes (ratings, media clears, other admins' edits — including price)
- **Severity**: P1. **Confidence**: CONFIRMED_FROM_SOURCE (code paths and absence of `@Version` verified; occurrence routine because rating events are constant traffic).
- **Service**: product-service. **Files**: `ProductServiceImpl.java:146-179` (`update`: `repo.findById` **outside** the transaction at `:149` → detached snapshot; inner `transactionTemplate.execute` merges it at `:160-177`), `Product.java` (no `@Version`; verified `SoftDeletable.java`, `AbstractMappedEntity.java` have none), concurrent writers: `ProductRatingService.java:19-31` (rating apply), `ProductMediaService.java:21-35` (media clear), `ProductMediaSweepJob.java:44-54`, other admins.
- **Current behavior**: `repo.save(existing)` on a detached entity makes Hibernate merge **every column** from the stale snapshot; the resulting UPDATE rewrites the whole row with values read at t0 (plus requested changes). Anything committed between t0 (read) and t1 (flush) is silently reverted. There is no `@Version`, so no `OptimisticLockingFailureException` is ever raised for this path.
- **Unsafe interleaving**: t0 admin loads product P (avgRating 4.1, count 120, price 100k) → t1 `ProductRatingConsumer` applies rating event (4.2/121) and commits → t2 admin saves a title edit; merge writes avgRating 4.1/120 back → and `publishUpdated` broadcasts the reverted record to `shop.product.lifecycle.v1` → the search index is re-aged too. The same window reverts `media_id` cleared by `MediaDeletedConsumer`/sweep, and an admin-vs-admin race loses one edit entirely (last-committer-wins full-row, no detection).
- **Invariant violated**: committed writes are durable (lost-update freedom).
- **Business impact**: silently regressed product data (ratings, media, and — in the admin-vs-admin case — price/currency) propagated to ES and the PDP; no error surfaces.
- **Fix / pattern**: add `@Version` to `Product` (and `ProductVariant`), load-and-mutate inside one transaction (`@Transactional` on update, no pre-tx read), and make the rating/media paths targeted JPQL updates (`UPDATE products SET avg_rating=:r, rating_count=:c WHERE id=:id`) so they cannot collide with full-row writes.
- **Regression test**: concurrency IT — thread A updates title while thread B applies a rating event; assert both effects visible after both commit; repeat for media-clear vs title-edit.

### PRD-2 — Checkout prices from a 10-minute Redis cache with no cross-service invalidation
- **Severity**: P1. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: product-service + order-service. **Files**: `order-service/…/client/ProductServiceClient.java:28-41` (`@Cacheable(value="productPrice", key="#productId + ':v2'")`, `@CircuitBreaker`), `order-service/…/config/CacheConfig.java:21,31` (TTL 10 min); `product-service/…/ProductServiceImpl.java:99-115` (`product`/`productBySlug` caches, TTL 10 min, `CacheConfig.java:23`), `ProductServiceImpl.java:146-179` (`@CachePut` only on product-service's own caches); `PricingServiceImpl.java:57-97` (checkout pricing from snapshots); `CartServiceImpl.java:65-77` (cart items also carry snapshot prices).
- **Current behavior**: order creation prices items from order-service's `productPrice` Redis entry (up to 10 min old). product-service's `@CachePut`/evictions never touch order-service's cache (different service, different keys; no pub/sub, no version bump, no short-TTL on price). A price change reaches checkout up to 10 minutes late; a currency change can create a mixed snapshot window that fails with `ORDER_PRICING_CURRENCY_MISMATCH` (`PricingServiceImpl.java:92-94`).
- **Expected**: checkout must re-fetch authoritative price/currency per order (or the cache must be invalidated on every product mutation via the existing `shop.product.lifecycle.v1` events / pub-sub, or keyed by product `updated_at` version).
- **Unsafe interleaving**: admin lowers price 100k→80k at T0 → buyer checks out at T0+5 min → charged 100k (stale cache) → overcharge disputes/refunds; symmetric undercharge on a price increase; currency flip → order refused mid-window.
- **Invariant violated**: price charged = authoritative catalog price at order time.
- **Business impact**: direct revenue correctness (both directions), support load; consistent-but-stale pricing is the single largest money-adjacent gap found.
- **Fix / pattern**: consume `shop.product.lifecycle.v1` in order-service to evict `productPrice::{id}:v2` (already at-least-once, ordered per aggregate); or add `updatedAt` to `ProductSnapshot` and treat cached entries older than the DB value as invalid; or drop the second-level cache for checkout pricing and rely on product-service's cache.
- **Regression test**: integration — create order-eligible product; update price; create order within TTL window; assert order total equals the new price.

### PRD-3 — `products.quantity` / `product_variants.quantity` are dead denormalized stock, exposed publicly, never reconciled with inventory-service
- **Severity**: P2. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Service**: product-service. **Files**: `Product.java:46-47`, `ProductVariant.java:55-57`, `ProductMapper.java:32,54,84` (admin-set only; exposed in `ProductSummaryResponse`/`ProductDetailResponse` on public GETs — `application.yml:47-53` makes GET /api/v1/products/** public), grep evidence: no reserve/commit path touches these columns; inventory-service keeps the real `available/reserved` pair (`InventoryRepository.java:18-29`).
- **Current behavior**: two unrelated "stock" representations. The storefront-visible `quantity` never decreases on reservation/commit, so it drifts from reality immediately after the first sale and only moves when an admin edits it. Any client (PDP, partners, future cart logic) trusting it will show availability that inventory-service will refuse (or vice versa).
- **Expected**: remove `quantity` from product payloads and serve availability from inventory-service, or subscribe `inventory.adjusted.v1`/`committed.v1` to keep a read-model in sync.
- **Business impact**: customer-facing availability lies → abandoned carts, support tickets; risk of future code paths wrongly using it for oversell decisions.
- **Fix / pattern**: treat inventory-service as the single source of stock truth; hide `quantity` from public DTOs (breaking change → version the API), or add a sync consumer.
- **Regression test**: contract test asserting public product payloads carry no `quantity`; or integration test that reserve/commit changes the served availability.

### PRD-4 — Product delete vs reservation TOCTOU (check-then-act across services)
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE (window), impact cosmetic.
- **Service**: product-service. **Files**: `ProductServiceImpl.java:182-209` (`assertNoLiveStockReservations` via HTTP at `:185`, then separate transaction marks deleted at `:187-198`), `InventoryReservationClient.java:30-48` (fail-closed on error — good).
- **Unsafe interleaving**: reservation-count check passes → buyer reserves (inventory accepts; product deletion never touches inventory) → product soft-deleted → in-flight order commits normally for a now-hidden product.
- **Expected**: acceptable eventual behavior, but document it, or re-check after markDeleted and (optionally) auto-release the new reservation.
- **Fix / pattern**: post-delete re-check + compensating release event; or block reservations for products flagged `deleted` (inventory-side allowlist is impractical — keep as known window).
- **Regression test**: interleave delete and reserve; assert order flow completes coherently (documented outcome).

### PRD-5 — ES indexing pipeline: at-least-once outbox→Kafka with terminal FAILED and no PG↔ES reconciliation
- **Severity**: P2. **Confidence**: mechanism CONFIRMED_FROM_SOURCE; actual drift NEEDS_RUNTIME_VERIFICATION.
- **Service**: product-service. **Files**: `TransactionalProductEventPublisher.java:49-88`, `OutboxRelay.java` (claim-lease relay, `max-retries=10`), `changelog-007` (lease columns); topic `shop.product.lifecycle.v1` (`KafkaTopicContract.java:39`).
- **Current behavior**: product events (price, currency, status, rating) reach the search indexer only through this pipeline. After 10 failed publishes an event goes `FAILED` permanently (INV-9 pattern) — the product silently diverges from ES until its next update; no DLQ, no reconciliation job comparing PG to the index.
- **Business impact**: search shows stale price/availability after broker incidents; permanent divergence possible.
- **Fix / pattern**: DLQ + alert (as INV-9), plus a periodic reconciliation sweep (by `updated_at` window) re-emitting `ProductUpdated` for drifted rows.
- **Regression test**: fault-injection IT — force broker downtime > 10 relay cycles; assert FAILED state + reconciliation re-publishes.

### PRD-6 — `ProductMediaSweepJob` keeps its cursor in JVM state; replicas sweep different rotating pages
- **Severity**: P3. **Confidence**: CONFIRMED_FROM_SOURCE.
- **Files**: `ProductMediaSweepJob.java:27` (`private int currentPage`), `:37-43` (cursor advance), cron `:29`.
- **Current behavior**: with 2 replicas each instance advances its own cursor over `findByMediaIdIsNotNull` pages; combined coverage is nondeterministic (both may sweep page N while page N+3 waits cycles); self-heals only when a page comes back empty. No distributed lock/cursor.
- **Expected**: persist the cursor or use keyset pagination from the DB; a distributed lock is unnecessary because clears are idempotent, but coverage should be deterministic.
- **Fix / pattern**: keyset pagination (`WHERE media_id IS NOT NULL AND updated_at > lastSeen ORDER BY updated_at`).
- **Regression test**: unit — simulate two cursors; assert all pages eventually visited within k cycles.

### PRD-7 — product-service `CacheConfig` lacks `transactionAware()` (inventory has it): `@CachePut`/`@CacheEvict` inside `@Transactional` can publish before commit or on rollback
- **Severity**: P3. **Confidence**: HIGH_CONFIDENCE_INFERENCE (config asymmetry CONFIRMED; exact window depends on Spring proxy order).
- **Files**: `product-service/…/config/CacheConfig.java:27-37` (no `.transactionAware()`) vs `inventory-service/…/config/CacheConfig.java:32` (`.transactionAware()`); affected writers `BrandServiceImpl.java:70-92` (`@CachePut`/`@CacheEvict` on `@Transactional` methods), `CategoryServiceImpl.java:78-115`.
- **Unsafe interleaving**: brand update method returns → cache put fires before the tx interceptor commits (depending on advice ordering) → a reader gets the new value while the row is uncommitted; if commit then fails, Redis keeps a value that never existed in the DB until TTL (10/30 min). `ProductServiceImpl.update` is unaffected (transactionTemplate inside the method → put happens after commit).
- **Fix / pattern**: add `.transactionAware()` to product-service's `RedisCacheManagerBuilderCustomizer` for parity, and prefer explicit after-commit eviction (as `InventoryCacheService`).
- **Regression test**: integration — force a rollback after a `@CachePut` method's return path; assert cache does not contain the phantom value.

---

## Part C — Findings count and index

| ID | Title (short) | Severity | Confidence |
|---|---|---|---|
| INV-1 | Keyless reserve + no per-order dedupe → duplicate holds | P2 | HIGH_CONFIDENCE_INFERENCE |
| INV-2 | Idempotency replay returns terminal reservations | P2 | CONFIRMED_FROM_SOURCE |
| INV-3 | Sweeper emits phantom/duplicate release events | P2 | CONFIRMED_FROM_SOURCE |
| INV-4 | Fixed 15-min TTL, no extension → paid-order stock-loss window | P2 | CONFIRMED mechanism / freq. runtime |
| INV-5 | commit/release retry cap → checkout 409s under load | P3 | CONFIRMED mechanism / impact runtime |
| INV-6 | Redis inventory cache stale-put race ≤60 s | P3 | CONFIRMED mechanism |
| INV-7 | `create()` inserts NULL `safety_stock_threshold` | P2 | HIGH_CONFIDENCE_INFERENCE |
| INV-8 | DB invariant gaps (no `available≥reserved` CHECK, NOT VALID, no qty>0) | P3 | CONFIRMED_FROM_SOURCE |
| INV-9 | Outbox FAILED terminal, no DLQ/alert | P3 | CONFIRMED_FROM_SOURCE |
| PROM-1 | commit ignores campaign deactivation but fails on deletion | P2 | CONFIRMED_FROM_SOURCE |
| PROM-2 | `update()` breaks quota limits, resets perUserLimit | P3 | CONFIRMED_FROM_SOURCE |
| PROM-3 | delete() race → deleted campaign with live reservation, silent quota/event skips | P3 | HIGH_CONFIDENCE_INFERENCE |
| PROM-4 | Same-order concurrent reserve: error instead of replay | P3 | CONFIRMED_FROM_SOURCE |
| PROM-5 | VND at scale-2 HALF_UP; no zero-decimal handling | P3 | CONFIRMED_FROM_SOURCE |
| PRD-1 | No `@Version` on Product; detached full-row merge reverts concurrent writes | **P1** | CONFIRMED_FROM_SOURCE |
| PRD-2 | Checkout prices from 10-min `productPrice` cache, no invalidation | **P1** | CONFIRMED_FROM_SOURCE |
| PRD-3 | Public dead `quantity` stock fields, never synced with inventory | P2 | CONFIRMED_FROM_SOURCE |
| PRD-4 | Product delete vs reserve TOCTOU | P3 | CONFIRMED_FROM_SOURCE |
| PRD-5 | ES pipeline: FAILED events terminal, no reconciliation | P2 | CONFIRMED mechanism / drift runtime |
| PRD-6 | Media sweep JVM-local page cursor | P3 | CONFIRMED_FROM_SOURCE |
| PRD-7 | Cache `transactionAware()` asymmetry | P3 | HIGH_CONFIDENCE_INFERENCE |

**Totals: 21 findings — P0: 0, P1: 2, P2: 8, P3: 11.**

### Top 5
1. **PRD-2 (P1)** — checkout charges a ≤10-min stale price: `order-service/.../ProductServiceClient.java:29` + `order-service/.../config/CacheConfig.java:21` (no invalidation from product-service).
2. **PRD-1 (P1)** — `ProductServiceImpl.java:149,160-177` detached full-row merge, no `@Version` on `Product` → silently reverts concurrent rating/media/price edits (and re-broadcasts them to ES).
3. **INV-1 (P2)** — `InventoryController.java:93-95` keyless reserve with no per-order uniqueness (`changelog-001:53-59`) → duplicate holds/oversell window on retries.
4. **INV-4 (P2)** — `InventoryServiceImpl.java:209-211` + `application.yml:47`: 15-min hard TTL with no extension; commit rejects while payment still running → stranded paid orders.
5. **INV-3 (P2)** — `ReservationCleanupScheduler.java:91-95`: sweep publishes `inventory.released.v1` for batch rows it never claimed (incl. rows concurrently COMMITTED by payment).

### Files I could not read / did not read
None blocked — everything attempted was readable. The following in-scope files were **not opened** (all trivial DTO records, interfaces, or config/properties with no concurrency surface per targeted grep): product-service `Brand/Category/Product/ProductVariant service interfaces`, `BrandEventPublisher`/`CategoryEventPublisher`/`ProductEventPublisher` interfaces, `TransactionalBrandEventPublisher`/`TransactionalCategoryEventPublisher`, `Brand`/`Category`/product `OutboxEvent` entities, `BrandRepository`/`CategoryRepository`/product `OutboxEventRepository`, `BrandMapper`/`CategoryMapper`, `Brand*`/`Category*`/`ProductVariant*` request/response DTOs (variant requests seen via grep only), `BrandResponse`/`CategoryResponse`/`CategoryTreeResponse`/`MediaReferenceCountResponse`, `BrandController`/`CategoryController`/`BackofficeBrandController`/`BackofficeCategoryController`/`ProductVariantController`/`BackofficeProductVariantController`, `MediaClientConfig`/`MediaClientProperties`/`InventoryClientProperties`/`ProductMediaSweepProperties`, `MediaLifecycleListenerConfig`/`MediaLifecycleEvent`/`RatingLifecycleEvent`, `ProductMetrics`, `changelog-002-outbox-pending-index.yaml`/`changelog-005-media-id-index.yaml`, `CategoryServiceImpl.java:121-147`; promotion-service `PromotionEventPublisher`/`PromotionMetrics`/`PromotionErrorCode`/`PromotionMessageBundleConfiguration`/`messages/*.properties`/`005-promotion-outbox-claim-lease.yaml`/`changelog-007-promotion-outbox-claim-lease-owner.yaml`/`PromotionOutboxRelay.java:201-268` (verbatim clone of the fully-read inventory relay) / response DTOs; inventory-service `InventoryResponse`/`ReservationResponse`/`ActiveReservationCountResponse` DTOs (content known via mapper/tests). Out-of-scope shared classes read only in part: `KafkaMessagePublisher`, `OutboxPublishLedger`, `BaseKafkaConsumer` (73 lines; not opened), `KeycloakServiceTokenProvider` — the at-least-once duplicate semantics of the relay are inferred from the relay + ordering helper source, not from `KafkaMessagePublisher` internals.
