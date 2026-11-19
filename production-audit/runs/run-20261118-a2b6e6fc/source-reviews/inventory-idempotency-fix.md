# OI-01 — inventory `reserve` is now idempotent (inventory-service)

- **Finding**: OI-01 (HIGH) — `POST /api/v1/inventory/{productId}/reserve` had no idempotency key and
  `reservations` had no uniqueness constraint, so a duplicated/lost reserve call created a second hold and
  decremented the stock twice (the only oversell path found by the audit).
- **Scope of this change**: `inventory-service/` only. No route, request body shape, Kafka topic, shared library
  or root configuration was touched.
- **Branch**: `audit/production-hardening-run-20261118-a2b6e6fc`.

---

## 1. The idempotency contract

| Situation | Result |
| --- | --- |
| Reserve **with** an idempotency key, key not seen before | one `PENDING` reservation row, stock `reserved_quantity` incremented once, exactly one `inventory.reserved.v1` outbox event, `201` with the new `reservationId` |
| Reserve **with** an idempotency key, same key + **same product + same quantity** | the **existing** reservation is returned (same `reservationId`), stock is **not** decremented again, **no** second `inventory.reserved.v1` event |
| Reserve **with** an idempotency key, same key + **different product or different quantity** | rejected with **HTTP 409** (`ERR-0409`, `ErrorCode.CONFLICT`); no stock change, no event, no new row |
| Reserve **without** an idempotency key | unchanged behaviour: a new reservation, a new decrement, a new event (see §5 for the caller evidence) |

The binding of a key is exactly **(productId, quantity)**, as required by the task. The `orderId` is stored on the
reservation but is deliberately *not* part of the binding: the task specifies product + quantity, and the caller
derives the key from its own line intent, so the order id is already implied by the key.

### How the key reaches the reserve path

The key travels as an **HTTP request header**, not as a body field:

```
POST /api/v1/inventory/{productId}/reserve
Idempotency-Key: <caller supplied, <= 64 chars>
```

- `InventoryController.reserve` (`inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java:83-101`)
  reads `@RequestHeader(value = ReservationIdempotency.KEY_HEADER, required = false)`.
- A missing/blank header takes the **unchanged** keyless path
  (`reservationService.reserveWithRetry(productId, request)`), so the existing HTTP contract and the existing
  controller/audit tests keep working untouched.
- A present header is trimmed, length-checked (≤ 64, otherwise `400` / `ERR-0400`) and forwarded to
  `reservationService.reserveWithRetry(productId, request, key)`.
- `ReservationServiceImpl.reserveIdempotently` (`.../service/impls/ReservationServiceImpl.java:38-49`) calls
  `InventoryService.reserve(productId, request, key)`.
- `InventoryServiceImpl.reserve(productId, request, key)` (`.../service/impls/InventoryServiceImpl.java:136-169`)
  owns the dedupe logic.

**Why a header and not a body field**: the task forbids changing the request shapes. The `ReserveRequest` record
(`inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java:8-11`) is byte-for-byte
unchanged, and the header convention is the one the repo already uses for the same purpose in the sibling service
(`order-service/.../controller/OrderController.java:37`, `.../controller/OrderStatusController.java:28`).

### Why the caller must send a deterministic key

A retry is only a duplicate if it carries the *same* key. The key must therefore be a stable function of the
business intent (the audit sketch at §8.2(a) of the source review suggests `SHA-256(orderId + productId + lineIndex)`).
The inventory service never invents a key — a missing key means "not idempotent", by design.

---

## 2. Liquibase changeset (additive, DB-level arbiter)

- **File**: `inventory-service/src/main/resources/db/changelog/changelog-007-reservations-idempotency-key.yaml`
- **ChangeSet id**: `007-reservations-idempotency-key` (author `production-audit`)
- **Registered in**: `inventory-service/src/main/resources/db/changelog/db.changelog-master.yaml:17-19`
- **No existing changeset was modified.**

Changes applied:

| Change | Detail |
| --- | --- |
| `addColumn` | `reservations.idempotency_key VARCHAR(64)`, **nullable** (rows without a key keep the legacy behaviour; PostgreSQL treats NULLs as distinct in a unique constraint) |
| `addUniqueConstraint` | `uk_reservations_idempotency_key` on `reservations(idempotency_key)` — a real unique constraint, not a plain index |

The unique constraint is the **final arbiter**: two concurrent replicas that both try to claim the same key cannot
both create a hold. The second INSERT blocks on the unique index until the first transaction commits, then fails
with a unique violation, and that transaction is rolled back in full (nothing is committed).

Entity mapping: `Reservation.idempotencyKey` → `@Column(name = "idempotency_key", length = 64)`
(`inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java:60-61`), consistent with
`spring.jpa.hibernate.ddl-auto: validate`. The lookup finder is
`ReservationRepository.findByIdempotencyKey` (`.../repository/ReservationRepository.java:31`).

---

## 3. How the conflict case is detected

Two layers:

1. **Fast path** — `InventoryServiceImpl.reserve` looks the key up with
   `reservationRepository.findByIdempotencyKey(key)`. If a row exists, `replayReservation` compares the stored
   binding with the requested intent (`.../service/impls/InventoryServiceImpl.java:171-181`):
   - same `productId` **and** same `quantity` → return the existing reservation (no decrement, no event);
   - otherwise → `throw BusinessException.conflict("order.duplicate.request", key)` → **HTTP 409**, error code
     `ERR-0409` (`ErrorCode.CONFLICT`), message *"Idempotency-Key {0} reused with different request body"*.
     A warning line is logged with the stored vs. requested binding.
2. **Lost race** — if the lookup finds nothing but the INSERT then hits the unique constraint, the transaction is
   rolled back (the decrement is undone with it) and `DataIntegrityViolationException` leaves the transactional
   method. `ReservationServiceImpl.reserveIdempotently` catches it **outside** the transaction and calls
   `inventoryService.reserve(...)` a second time; this time the fast path finds the committed winner and either
   replays it or rejects it as a conflict. Postgres only reports the violation after the winning transaction has
   committed, so the winner is always visible to the second call.

The conflict is therefore detected **without** ever decrementing stock or publishing an event for the rejected
request.

> Note on the error code: `ErrorCode` and the message bundles live in `utils/common-core` / `utils/common-spring`,
> which are outside this write scope, so the conflict reuses the shared `ErrorCode.CONFLICT` (`ERR-0409`) and the
> existing bundle keys `order.duplicate.request` and `order.idempotency.key.tooLong`. The rendered texts are
> service-agnostic ("Idempotency-Key {0} reused with different request body", "Idempotency-Key header must not
> exceed {0} characters").

---

## 4. Files created / modified

### Created

| File | Purpose |
| --- | --- |
| `inventory-service/src/main/java/com/shop/inventoryservice/constant/ReservationIdempotency.java` | `KEY_HEADER = "Idempotency-Key"` and `MAX_KEY_LENGTH = 64` shared by the controller and the service |
| `inventory-service/src/main/resources/db/changelog/changelog-007-reservations-idempotency-key.yaml` | additive changeset: `idempotency_key` column + `uk_reservations_idempotency_key` unique constraint |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryReserveIdempotencyTest.java` | new Testcontainers/PostgreSQL integration test (12 tests) |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerIdempotencyKeyTest.java` | new controller test for the header contract (3 tests) |

### Modified

| File | Change |
| --- | --- |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java` | new nullable `idempotencyKey` column mapping |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/ReservationRepository.java` | new `findByIdempotencyKey(String)` finder |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryService.java` | new overload `reserve(productId, request, idempotencyKey)` |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationService.java` | new overload `reserveWithRetry(productId, request, idempotencyKey)` |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java` | idempotent reserve: key normalisation, fast-path replay, conflict rejection, insert-before-decrement ordering, `saveAndFlush` on the unique constraint |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java` | lost-race handling: catch `DataIntegrityViolationException` outside the transaction and replay the committed winner; `@Slf4j` added |
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` | read the optional `Idempotency-Key` header, validate its length, forward it |
| `inventory-service/src/main/resources/db/changelog/db.changelog-master.yaml` | include the new changeset 007 (additive) |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplTest.java` | **existing test updated** (not weakened): the stubs now mock `reservationRepository.saveAndFlush(...)` instead of `save(...)`, because the reserve now flushes the insert so the unique constraint is evaluated inside the transaction |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplReserveConcurrencyTest.java` | **existing test updated** (not weakened): the lenient stub moved from `save` to `saveAndFlush` |

No test was disabled, deleted or weakened. No HTTP route, request body shape or Kafka topic changed.

---

## 5. Decision on the keyless path (with caller evidence)

**Decision: a reserve without an idempotency key keeps the current behaviour (a new reservation, a new decrement,
a new event). The keyless path is NOT removed, because the call path does not always supply a key today.**

Evidence (all in `order-service/`, read-only):

- `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:182-188` — the create saga
  reserves one line at a time with
  `new ReserveRequest(orderItem.getQuantity(), order.getId())`.
- `order-service/src/main/java/com/shop/orderservice/dto/internal/ReserveRequest.java:8-11` — that DTO carries
  **only** `quantity` and `orderId`; there is no idempotency field.
- `order-service/src/main/java/com/shop/orderservice/service/impls/StockReservationServiceImpl.java:18-20` — passes
  the DTO straight through.
- `order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java:40-56` — the HTTP call sets
  only the `Authorization` header and sends that body; **no `Idempotency-Key` header is sent**.

`order-service` is the only production caller of this endpoint (a repo-wide search for `/api/v1/inventory/{productId}/reserve`
returns only `InventoryServiceClient.java:43` plus tests). Because the caller currently sends no key, making the key
mandatory in the inventory service would break every checkout. The key is therefore optional, and the inventory
service is ready for the caller to start sending a deterministic key.

**Follow-up needed outside this write scope** (for the `order-service` owner): derive a stable key per order line —
e.g. `SHA-256(orderId + ":" + productId + ":" + lineIndex)` — and send it as the `Idempotency-Key` header on
`InventoryServiceClient.reserve`. Until then the keyless path remains the live path, and the OI-01 defect is only
*closed for keyed callers*. The inventory side is complete and enforced at the database level.

---

## 6. Additional pre-existing defect found and fixed (needed for OI-01)

While writing the tests it became clear that the reserve response carried a **null `reservationId`**:

- `Reservation` has a non-null `@Version Long version = 0L` (`@Builder.Default`), so Spring Data's
  `JpaMetamodelEntityInformation.isNew` returns `false` (the version is not `null`) and `save(...)` performs a
  **merge**, not a persist. The managed copy — the one that receives the generated UUID — was discarded, so the
  original instance kept `id == null`.
- Consequence in the old code: `mapper.toReservationResponse(reservation)` returned `reservationId = null`, and the
  `inventory.reserved.v1` outbox payload carried `"reservationId": null` as well. The order-service reads exactly
  that field (`InventoryServiceClient.java:48`), so a reservation could not be identified by its caller.

Fix: the reserve now uses the **returned** managed instance —
`Reservation reservation = reservationRepository.saveAndFlush(Reservation.builder()...build());`
(`InventoryServiceImpl.java:147-155`). The new tests assert that the returned `reservationId` equals the persisted
row id and that the emitted event payload contains it.

This was not an optional extra: the OI-01 contract ("a duplicate request returns the existing reservation") is
meaningless if the first response cannot identify the reservation.

---

## 7. Implementation notes / why the ordering is what it is

- **The reservation row is inserted (and flushed) BEFORE the stock decrement.** This is required for two reasons:
  1. the unique constraint is evaluated before any stock mutation, so a duplicate key can never decrement stock;
  2. a duplicate must not fail with `STOCK_INSUFFICIENT` when the winner already took the last unit — the duplicate
     has to replay the winner's reservation, not be rejected for lack of stock.
- **The insert is flushed** (`saveAndFlush`) so the unique violation is raised inside the transaction (and rolled
  back there) instead of at commit time.
- **The whole reserve stays in one transaction** (`@Transactional` on `InventoryServiceImpl.reserve`), so the row,
  the decrement and the outbox event commit or roll back together.
- **Lock ordering**: the unique index is taken before the inventory row, always in that order, so two concurrent
  keyed reserves cannot deadlock.
- **The replay path publishes nothing and evicts the cache nothing** — it is a pure read.

---

## 8. Verification — commands, exit codes, test counts

### 8.1 Mandatory gate

```
$ ./mvnw -B -ntp -pl inventory-service -am test
[INFO] Tests run: 89, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
exit code: 0
```

Per class (inventory-service):

| Test class | Tests | Result |
| --- | --- | --- |
| `InventoryRepositoryTest` | 7 | pass |
| `InventoryControllerAuditTest` | 2 | pass |
| `InventoryControllerTest` (lead's security-context update) | 7 | pass |
| `InventoryControllerIdempotencyKeyTest` **(new)** | 3 | pass |
| `InternalInventoryControllerTest` | 3 | pass |
| `ReservationStateEndpointTest` | 4 | pass |
| `InventoryReserveIdempotencyTest` **(new)** | 12 | pass |
| `InventoryServiceImplReserveConcurrencyTest` | 2 | pass |
| `ReservationServiceImplTest` | 4 | pass |
| `InventoryServiceImplTest` | 25 | pass |
| `ReleaseCommittedTest` | 3 | pass |
| `InventoryOutboxRelayTest` | 5 | pass |
| `InventoryOutboxClaimConcurrencyTest` | 2 | pass |
| `InventoryLifecycleIdempotencyTest` | 5 | pass |
| `InventoryOutboxRelayIntegrationTest` | 1 | pass |
| `ReservationCleanupSchedulerTest` | 4 | pass |
| **total** | **89** | **0 failures, 0 errors, 0 skipped** |

The upstream modules built by `-am` (`common-core`, `common-spring`, `common-kafka`, …) also passed
(`common-spring`: 36 tests, 0 failures).

**Honest note on run-to-run stability**: one of the three executions of this exact command failed with 12 errors
in the Testcontainers-based classes. The cause was infrastructure, not the change: the shared Kafka container
(`confluentinc/cp-kafka:7.6.0`) timed out during startup —
`ContainerLaunchException: Timed out waiting for log output matching '.*\[KafkaServer id=\d+\] started.*'` — while
24 containers (other agents' Testcontainers + the local docker-compose stack) were competing for Docker. The
Postgres container started fine; every failing test failed in the static initialiser of `AbstractIntegrationTest`,
before any assertion. The command was re-run and passed 89/89 (§8.1); the earlier run and the `-Pit` run also
passed. **Under Docker contention this module's suite is flaky for reasons unrelated to OI-01.**

### 8.2 The two `*IT` classes (not part of the `test` phase; run explicitly with the `it` profile)

```
$ ./mvnw -B -ntp -pl inventory-service -am verify -Pit -Dsurefire.skip=true
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0   (ReservationsOrderIdIndexIT 3, InventoryOutboxRelayRecoveryIT 4)
[INFO] BUILD SUCCESS
exit code: 0
```

### 8.3 Quality gate

```
$ ./mvnw -B -ntp -T1C -pl inventory-service validate
[INFO] You have 0 Checkstyle violations.
[INFO] BUILD SUCCESS
exit code: 0
```

### 8.4 Focused run of the new integration test (during development)

```
$ ./mvnw -B -ntp -pl inventory-service -am test -Dtest=InventoryReserveIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
exit code: 0
```

### 8.5 Blast-radius check (repo rule, GitNexus)

```
$ node .gitnexus/run.cjs impact "Method:inventory-service/.../InventoryService.java:InventoryService.reserve#2" --direction upstream --repo .
risk: LOW, impactedCount: 3, processes affected: 1 ("Reserve → AtomicReserve"), modules affected: 2 (Impls, Controller)
```

The only cross-module caller is the HTTP client in `order-service`
(`InventoryServiceClient.reserve`, `order-service/.../client/InventoryServiceClient.java:40`), which is additive and
backwards compatible (the new header is optional).

---

## 9. New tests

`inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryReserveIdempotencyTest.java`
(extends the existing `AbstractIntegrationTest` → real PostgreSQL 16 + Kafka through Testcontainers; no mocks):

| Test | Covers |
| --- | --- |
| `reserveWithAnIdempotencyKey_createsOneReservationOneDecrementAndOneReservedEvent` | happy path: 1 row, `reserved_quantity` +2, exactly 1 `inventory.reserved.v1` event whose payload carries the `reservationId`/`quantity`/`orderId` |
| `reserveWithTheSameKeyAndTheSameBinding_returnsTheExistingReservationWithoutASecondHold` | edge case: same key + same product + same quantity → same `reservationId`, still 1 row, no second decrement, no second event |
| `reserveWithTheSameKeyAndADifferentQuantity_isRejected` | edge case: same key + different quantity → HTTP 409 / `ERR-0409`, no extra row, no extra decrement, no extra event |
| `reserveWithTheSameKeyAndADifferentProduct_isRejected` | edge case: same key + different product → HTTP 409 / `ERR-0409`, no row/decrement/event on the second product |
| `reserveWithoutAnIdempotencyKey_keepsCreatingANewReservation` | the keyless path is unchanged (2 rows, `reserved_quantity` +4, 2 events) |
| **`concurrentReservesWithTheSameKey_createExactlyOneReservationAndOneDecrement`** | **concurrency**: 2 threads, `CyclicBarrier` + `CountDownLatch`, same key → both get the **same** `reservationId`, exactly 1 row, `reserved_quantity` +3, exactly 1 event |
| **`concurrentReservesOfTheLastUnitWithDifferentKeys_yieldOneSuccessAndOneInsufficientStock`** | **concurrency**: 2 threads, barrier, different keys, 1 unit of stock → exactly 1 success, exactly 1 `STOCK_INSUFFICIENT` (`INV-3002`), 1 row, `available_quantity` 1, `reserved_quantity` 1, 1 event (oversell protection still holds) |
| `commitStaysIdempotentForAHoldCreatedWithAnIdempotencyKey` | regression: a second `commit` changes nothing and publishes no second `inventory.committed.v1` |
| `releaseStaysIdempotentForAHoldCreatedWithAnIdempotencyKey` | regression: a second `release` changes nothing and publishes no second `inventory.released.v1` |
| `theExpirySweeperReleasesAnExpiredHoldCreatedWithAnIdempotencyKey` | regression: `ReservationCleanupScheduler` expires the hold, `reserved_quantity` back to 0, one `inventory.released.v1` |
| `theIdempotencyKeyIsEnforcedByADatabaseUniqueConstraint` | the DB-level constraint `uk_reservations_idempotency_key` exists (`pg_constraint`) |
| `aSecondReservationRowWithTheSameIdempotencyKeyIsRejectedByTheDatabase` | a second row with the same key is rejected by PostgreSQL itself |

`inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerIdempotencyKeyTest.java`
(`@WebMvcTest`, with the `azp=order-service` JWT principal as required by the shared `ServiceCallerGuard`):

| Test | Covers |
| --- | --- |
| `reserveWithAnIdempotencyKeyHeader_forwardsTheKeyToTheService` | the header reaches `reserveWithRetry(productId, request, key)` |
| `reserveWithoutAnIdempotencyKeyHeader_usesTheKeylessReservePath` | without the header the keyless overload is used (the keyed one is never called) |
| `reserveWithAnIdempotencyKeyLongerThanTheLimit_isRejected` | a key longer than 64 chars → HTTP 400 / `ERR-0400`, the service is never called |

All new tests assert the **persisted state** (rows, counters) and the **emitted events** (count *and* payload), not
only the response status.

---

## 10. Acceptance criteria — status

| Criterion | Status |
| --- | --- |
| Reserve is idempotent for a caller-supplied key | met — keyed duplicate returns the existing reservation, no second decrement, no second event |
| Key enforced at DB level with a unique constraint | met — `uk_reservations_idempotency_key` (additive changeset 007) |
| Duplicate is bound: same key + same product + same quantity → existing reservation | met |
| Duplicate with a different product or quantity → rejected | met — HTTP 409 / `ERR-0409` |
| No key → current behaviour, unless the call path always supplies one | met — the call path does **not** supply one today (`InventoryServiceClient.java:40-56`), so the keyless path is preserved; see §5 |
| `./mvnw -B -ntp -pl inventory-service -am test` passes | met — exit 0, 89 tests, 0 failures |
| New tests against real PostgreSQL via Testcontainers | met — `InventoryReserveIdempotencyTest` extends `AbstractIntegrationTest` |
| Happy path / duplicate / conflicting quantity / same-key concurrency / last-unit concurrency / commit-release-expiry regressions | met — 12 integration tests + 3 controller tests |
| Assert persisted state and emitted events | met — row counts, counters, outbox counts and outbox payloads |
| Existing tests still pass; old behaviour updated where it encoded it | met — only the `save` → `saveAndFlush` stubs in two existing mock-based tests changed; no test disabled or weakened |
| No comments in the code | met — verified by grep over every touched file |
| No route / request-shape / topic change | met — the header is additive and optional; `ReserveRequest` is unchanged |

## 11. What I could NOT satisfy / limitations

1. **The caller does not send the key yet.** The inventory side is complete, but `order-service`
   (`InventoryServiceClient.reserve`, `order-service/.../client/InventoryServiceClient.java:40-56`) still sends no
   `Idempotency-Key`, so the live path remains the non-idempotent one. `order-service` is outside this write scope;
   the caller must derive a deterministic per-line key (e.g. `SHA-256(orderId:productId:lineIndex)`) and send it as
   the header. **Until that happens, the end-to-end oversell path described in OI-01 is not closed** — only the
   inventory-side capability and its DB-level enforcement are.
2. **The conflict error code is a shared one.** `ErrorCode` lives in `utils/common-core` (outside the write scope),
   so the conflict is reported as `ERR-0409` (`ErrorCode.CONFLICT`) instead of a dedicated `INV-30xx` code. The
   message comes from the shared bundle key `order.duplicate.request`.
3. **No new i18n message key was added** for the same reason (the bundles live in `utils/common-spring`). The
   existing generic keys `order.idempotency.key.tooLong` and `order.duplicate.request` are reused; their texts are
   service-agnostic, but their *names* carry the `order.` prefix.
4. **The `*IT` classes are not executed by the mandatory `test` command** (surefire only picks up `*Test`/`*Tests`/
   `Test*`); they were run separately with `-Pit` and pass (§8.2). The new tests are deliberately named `*Test` so
   that they run in the mandatory command.
5. **The idempotency key is not bound to the `orderId`.** The task specified product + quantity; a caller that
   reuses one key for two different order ids on the same product and quantity would receive the first reservation.
   The key must therefore be unique per order line — the caller's responsibility.
6. **A second pre-existing defect was found but left alone** because it belongs to another finding/service: the
   null `reservationId` described in §6 was fixed here because OI-01 cannot be closed without it, but the same
   merge-vs-persist pattern may exist in other entities of this module (e.g. `Inventory`, which also has a
   `@Version`); those were not audited.
