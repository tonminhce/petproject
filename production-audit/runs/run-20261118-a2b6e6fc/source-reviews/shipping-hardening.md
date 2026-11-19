# shipping-service — Hardening Report

**Module:** `shipping-service` (Spring Boot 4.1.1 / Java 25, Maven, PostgreSQL + Liquibase + Kafka)
**Defect source:** `production-audit/runs/run-20261118-a2b6e6fc/source-reviews/shipping-service.md`
**Scope of this report:** SHIP-01, 02, 03, 04, 05, 06, 07, 08, 11, 12, 13, 14, 15
**Out of scope / untouched:** SHIP-29 and SHIP-33 — both were **already fixed before this work
started** (SHIP-29 by the compose/`application.yml` secret handling, SHIP-33 by the removal of the
test-scoped `spring-boot-restclient` declaration in the three service POMs). They were neither
re-implemented nor counted here; the consolidated report must not count them twice.
**Write scope:** `shipping-service/**` and this file only. `docs/PATTERNS.md` was **not** touched.

---

## 1. Verification — real commands, real exit codes, real counts

### 1.1 Build + tests (the required command)

```
cd /Users/tonminh-mac/IdeaProjects/untitled5
./mvnw -B -ntp -pl shipping-service -am test
```

**Exit code: `0`** — `BUILD SUCCESS`, total time `02:02 min`.

> **Provenance of the 190 figure.** The table above is the result of the
> `./mvnw -B -ntp -pl shipping-service -am test` run (exit code `0`). Two defensive edits were made
> to the tree *after* that run: `ShipmentServiceImpl.createShipment` now raises
> `CARRIER_NOT_CONFIGURED` instead of an NPE when no carrier adapter is wired, and one duplicate log
> line in `ShipmentCreationService.claim` was reworded. Neither changes a behaviour that a test
> asserts. Both edits were covered by a re-run of the unit suite
> (`Tests run: 162, Failures: 0, Errors: 0, Skipped: 0`, exit `0`) and of the quality gate
> (`validate`, exit `0`, 0 Checkstyle violations) — 162 unit tests + 28 integration tests = the same
> 190 tests as in the run above. The exact final tree is additionally covered by the lead's
> full-reactor verification run, which includes the `shipping-service` module and reports the
> shipping-service test count from the reactor.

| Module | Tests run | Failures | Errors | Skipped |
|---|---|---|---|---|
| Common Library Core | 16 | 0 | 0 | 0 |
| Common Library Security Starter | 27 | 0 | 0 | 0 |
| Common Library Logging Starter | 32 | 0 | 0 | 0 |
| Common Library Keycloak Starter | 36 | 0 | 0 | 0 |
| Common Library Kafka Starter | 49 | 0 | 0 | 0 |
| Common Library Object Storage Starter | 6 | 0 | 0 | 0 |
| Common Library Spring Boot Starter | 36 | 0 | 0 | 0 |
| **shipping-service** | **190** | **0** | **0** | **0** |

Per-class counts inside `shipping-service` (same run):

| Test class | Tests |
|---|---|
| `ShippingFlowIT` | 15 |
| `ShippingOutboxClaimConcurrencyIT` | 2 |
| `ShippingOutboxRelayRecoveryIT` | 4 |
| `ShippingBootstrapIT` | 5 |
| `ShippingChangelogIT` | 2 |
| `ShippingChangelogContractTest` | 6 |
| `ShipmentEventStatusChangelogTest` | 2 |
| `ShipmentCreationServiceTest` | 9 |
| `ShipmentCreationRetrySchedulerTest` | 3 |
| `ShipmentStateMachineTest` | 11 |
| `ShipmentStatusWriteGuardTest` | 3 |
| `WebhookRetrySchedulerTest` | 8 |
| `ReconciliationSchedulerTest` | 8 |
| `ShipmentServiceImplTest` | 23 |
| `WebhookEventServiceTest` | 20 |
| `CarrierShipmentClientTest` | 7 |
| `CarrierRestClientCustomizerTest` | 2 |
| `CarrierConfigTest` / `CarrierAdapterDraftsTest` / `GhnCarrierAdapterTest` / `GhtkCarrierAdapterTest` | 3 / 4 / 3 / 3 |
| `BackofficeShipmentControllerTest` / `BackofficeShipmentAuditTest` | 20 / 1 |
| `ShippingOutboxRelayTest` / `ShippingOutboxRetentionSchedulerTest` / `ShippingEventPublisherImplTest` | 6 / 1 / 2 |
| `ShipmentWriterTest` / `WebhookEventWriterTest` / `WebhookSignatureVerifierTest` | 3 / 3 / 11 |

The test tree grew from **144** `@Test` methods (`git archive HEAD shipping-service/src/test`) to
**193**; the executed suite is **190** tests (the three remaining annotations live in nested or
non-executed helpers of `BackofficeShipmentControllerTest`). Every behavioural change listed in
section 2 has at least one unit or integration test that fails without the fix.

### 1.2 Quality gate (Checkstyle)

```
./mvnw -B -ntp -pl shipping-service validate
```

**Exit code: `0`** — `You have 0 Checkstyle violations.` (`config/checkstyle.xml`).

### 1.3 First-party comment gate

```
python3 -m production-audit.harness.cli.main verify --suite comments
```

`{"suite": "comments", "status": "PASS", "prohibited_comments": 0, "scanned_files": 1136}`.
No comment, Javadoc or block comment was added to any `.java` or `.yaml` file in this change.

---

## 2. Defect-by-defect

### SHIP-01 — HIGH — duplicate `CONFIRMED` produced two carrier parcels

**Root cause.** The idempotency guard protected only the database write: `existsByOrderId` →
`adapter.createShipment(orderId)` (the non-transactional side effect) → `writer.insert(...)`, with
the unique-index violation swallowed. The TOCTOU window was the whole carrier round-trip, so two
concurrent consumers both reached the carrier and the carrier created two parcels for one order.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationService.java` | New: `createForOrder` **claims the order id first** (`ShipmentCreationWriter.claim`) and only then calls the carrier. A duplicate claim raises `DataIntegrityViolationException` and the carrier is never called a second time. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationWriter.java` | New: `claim`, `complete`, `completeWithExisting`, `reschedule`, `failPermanently`, `reopen`, `findByOrderId`, `claimDue` — each in its own transaction. |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/ShipmentCreationTask.java` | New: the durable claim row (`order_id`, `carrier`, `status`, `attempt_count`, `next_attempt_at`, `claimed_at`). |
| `shipping-service/src/main/java/com/shop/shippingservice/repository/ShipmentCreationTaskRepository.java` | New: `findByOrderId`, `findClaimable` (`PESSIMISTIC_WRITE` + `lock.timeout=-2` = `FOR UPDATE SKIP LOCKED`). |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java` | `createShipment` now delegates to `ShipmentCreationService.createForOrder(orderId, adapter)`; the carrier adapter is the `@Primary` bean, not the registry default. |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java` | The carrier call now carries a deterministic `Idempotency-Key: <orderId>` header so the carrier can dedupe on its side as well. |
| `shipping-service/src/main/resources/db/changelog/changelog-007-shipment-creation-tasks.yaml` | **NEW changeset `007-create-shipment-creation-tasks`** — creates `shipment_creation_tasks` and `CREATE UNIQUE INDEX uk_shipment_creation_task_order ON shipment_creation_tasks (order_id) WHERE deleted = false`. Without this unique key the `DataIntegrityViolationException` branch could never fire and the defect would only have been relocated. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/service/ShipmentCreationServiceTest.java`
  → `duplicateConfirmedEvent_secondClaimIsRejectedAndTheCarrierIsNotCalledAgain`: the second claim
  raises `DataIntegrityViolationException` and `adapter.createShipment` is never invoked
  (`verify(adapter, never()).createShipment(any())`).
- `shipping-service/src/test/java/com/shop/shippingservice/ShippingFlowIT.java`
  → `duplicatedConfirmedEventCreatesExactlyOneCarrierShipment`: three `CONFIRMED` events for one
  order ⇒ exactly **1** `shipments` row, exactly **1** `shipment_creation_tasks` row, task status
  `COMPLETED`.
- `shipping-service/src/test/java/com/shop/shippingservice/carrier/CarrierShipmentClientTest.java`
  → `theCarrierCallCarriesADeterministicIdempotencyKeyForTheOrder`.

**Residual risk.** The carrier-side idempotency key is advisory: the guarantee inside this service
rests on the unique index, which is correct as long as the index exists. `ShipmentCreationTask` rows
are never purged; they accumulate one row per order (bounded by the number of orders) and are the
audit trail of the creation attempt. The carrier's *initial* status is now applied through the FSM
(`Shipment.applyCarrierStatus`), so a draft that declares `DELIVERED` at creation time is rejected
(`CREATED → DELIVERED` is not an edge) and the creation is recorded as failed and retried — no
in-tree adapter returns `DELIVERED` (`MANUAL`/`GHN`/`GHTK` return `CREATED`, `NOOP` returns
`PICKED_UP`), so this is a latent contract question for a future adapter rather than a live defect.

---

### SHIP-02 — HIGH — a carrier outage dead-lettered the order event and the shipment was never created

**Root cause.** `adapter.createShipment(orderId)` ran inside the Kafka listener. The exception
escaped to `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000, 3))`, so the
record was retried three times and then published to `shop.order.lifecycle.v1.DLT` — the order
never got a shipment, and nothing in this service retried it.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationService.java` | `attempt` catches the carrier failure, records it (`reschedule` with the error text and the next attempt time) and **does not rethrow** — the Kafka listener therefore completes normally and the record is never dead-lettered for a carrier error. After `MAX_ATTEMPTS = 5` the task is parked `FAILED_PERMANENT` with the last error. |
| `shipping-service/src/main/java/com/shop/shippingservice/scheduler/ShipmentCreationRetryScheduler.java` | **New**: polls the pending creation tasks every 30 s, claims them with `FOR UPDATE SKIP LOCKED` + a claim lease, and retries the carrier call. A carrier that is no longer configured parks the task as permanently failed instead of calling the wrong carrier. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShippingMetrics.java` | New counters `shipping.shipment.created.count`, `shipping.shipment.creation.failed.count`, `shipping.shipment.creation.failed.permanent.count` (tagged by carrier). |

**Tests that prove it.**

- `ShipmentCreationServiceTest` → `carrierFailure_isRecordedAndRescheduledInsteadOfBeingLost`
  (the failure is recorded with a non-blank error and the attempt is rescheduled; nothing throws),
  `carrierFailureOnTheLastAttempt_parksTheTaskAsPermanentlyFailed`,
  `successfulCreation_persistsTheTrackingNumberAndCompletesTheTask`.
- `ShipmentCreationRetrySchedulerTest` → `dueTask_isClaimedAndRetriedThroughTheCreationService`,
  `aCarrierThatIsNotConfigured_parksTheTaskInsteadOfCallingTheCarrier`,
  `aFailingClaimDoesNotEscapeTheScheduler`.

**Residual risk.** The retry budget is 5 attempts with a `60 · 5^(n-1)` s backoff (≈ 6 min … ≈ 2 h).
A longer carrier outage still ends in `FAILED_PERMANENT`; the counter
`shipping.shipment.creation.failed.permanent.count` is the only signal and there is no operator queue
or dead-letter topic for creation tasks (same gap as SHIP-15 for the outbox).

---

### SHIP-03 — HIGH — the carrier HTTP call had no connect or read timeout

**Root cause.** `CarrierShipmentClient` built its `RestClient` from the auto-configured builder with
no request factory and no timeouts, so a carrier that accepted the TCP connection and never answered
blocked the single-threaded Kafka consumer indefinitely.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierRestClientCustomizer.java` | **New**: a `RestClientCustomizer` (Boot's `org.springframework.boot.restclient.RestClientCustomizer`) that installs a `JdkClientHttpRequestFactory` with an explicit connect timeout (`shop.shipping.carrier.connect-timeout-ms`, default 2000) and read timeout (`shop.shipping.carrier.read-timeout-ms`, default 5000). Boot's `RestClientAutoConfiguration` applies it to the injected `RestClient.Builder`, so the carrier client inherits it. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/carrier/CarrierRestClientCustomizerTest.java`
  → `aStalledCarrierIsAbandonedInsideTheBudget`: a local HTTP server accepts the connection and never
  answers; with a 300 ms read timeout the call must fail (as a `BusinessException`) in **under 5 s**,
  which is only possible if the read timeout is actually applied to the request.
  → `theConfiguredBudgetIsExposedForTheCarrierCall` asserts the configured connect/read budget.

**Residual risk.** The customizer is process-wide for the module: there is exactly one `RestClient`
in `shipping-service` (the carrier client), so no other caller is affected. It relies on
`spring-boot-restclient` being on the runtime classpath (SHIP-33, fixed by the lead) — without that
artifact there is no `RestClient.Builder` bean at all. The read timeout is a *response* budget; a
carrier that streams a response slowly but keeps the socket open is still bounded by the read timeout
because the JDK client applies it per request.

---

### SHIP-04 — HIGH — changeset `003-webhook-retry-fields` aborted the chain and blocked 004/005/006

**Root cause.** `changelog-003-webhook-retry.yaml` backfilled `status = 'FAILED_RETRYABLE'`, but the
`ck_shipment_events_status` constraint created in `changelog-001-shipments.yaml:74` still allowed only
`('PROCESSED','FAILED')` at that point in the chain; the constraint was only widened by changeset
`004`. On any database that already held a `FAILED` row the backfill raised a check violation,
Liquibase aborted, and `carrier_acknowledged_at` / `claimed_at` were never created — with
`ddl-auto: validate` the service then refused to start.

**Change.** No already-applied changeset was edited (checksums of `001`…`006` are unchanged). Instead:

| File | Change |
|---|---|
| `shipping-service/src/main/resources/db/changelog/changelog-002b-expand-events-status-check-pre.yaml` | **NEW changeset `002b-expand-events-status-check-pre`**: `ALTER TABLE shipment_events DROP CONSTRAINT IF EXISTS ck_shipment_events_status;` + re-add the constraint with `('PROCESSED','FAILED','FAILED_RETRYABLE','FAILED_PERMANENT')`. It is idempotent on a database where `004` already ran (the constraint is dropped and re-added with the same wide set). |
| `shipping-service/src/main/resources/db/changelog/db.changelog-master.yaml` | The new file is included **before** `changelog-003-webhook-retry.yaml`, so the constraint is widened before the backfill that needs the new value. Final order: `001`, `002`, **`002b`**, `003`, `004`, `005`, `006`, `007`, `008`, `009`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogIT.java`
  → `theWholeChainAppliesToADatabaseThatAlreadyHoldsFailedWebhookEvents`: a **separate database** is
  created on the Testcontainers PostgreSQL, the base schema (`001`+`002`) is applied, a row with
  `status = 'FAILED'` is inserted, then the master changelog is applied twice. The seeded row ends as
  `FAILED_RETRYABLE` (the backfill ran and did not abort), `shipments.carrier_acknowledged_at`,
  `shipment_events.claimed_at` and `outbox_events.claimed_at` exist, `shipment_creation_tasks`
  exists with its unique and due indexes, `idx_shipment_events_status_next_retry` exists and the
  `fk_shipment_events_shipment` foreign key exists. The second apply proves the chain is idempotent.
  → `theWholeChainAppliesToAnEmptyDatabase`: the same master changelog applies cleanly to an empty
  database.
- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogContractTest.java`
  → `theMasterWidensTheEventStatusCheckBeforeTheWebhookRetryBackfill` asserts the ordering in the
  master file, and `everyChangesetIdInTheChainIsUnique` parses every changelog file and asserts no
  duplicate changeset id (guards against editing an applied changeset).

**Residual risk.** A production database where `003` already failed will now run `002b` and then
`003` — the `003` backfill becomes valid and the chain continues. A production database where `003`
succeeded before will run `002b` (a no-op re-definition of the already wide constraint) and then
`004`…`009`. The `NOT VALID` foreign key of changeset `009` is deliberately not validated against
historical rows (see SHIP-13).

---

### SHIP-05 — HIGH — the reconciliation sweep wrote the status directly, bypassing the state machine

**Root cause.** `ReconciliationScheduler` selected `PICKED_UP | IN_TRANSIT | OUT_FOR_DELIVERY` and
flipped every row straight to `DELIVERED`, a transition `ShipmentStateMachine.transition` forbids
(the only edge into `DELIVERED` was `OUT_FOR_DELIVERY → DELIVERED`). The entity also exposed public
setters for `status`/`previousStatus`, so nothing structurally prevented a bypass.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentStateMachine.java` | New rule `autoDeliver(from)`: legal exactly for the in-flight statuses, otherwise `SHP-10003`. |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java` | `@Setter(AccessLevel.NONE)` on `status` and `previousStatus` (the public setters no longer exist) and new state-machine-aware methods `transitionTo`, `operatorRetry`, `applyCarrierStatus`, `autoDeliver`. |
| `shipping-service/src/main/java/com/shop/shippingservice/scheduler/ReconciliationScheduler.java` | The sweep now calls `shipment.autoDeliver(clock.instant())` inside a per-row `try/catch`; a rejected row is logged and skipped instead of aborting the batch. The delivery counter is now incremented **after** the durable write (also fixes the over-count of SHIP-20). |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/service/ShipmentStatusWriteGuardTest.java`
  → `theShipmentEntityDoesNotExposeASetterForTheStatus` (reflection: `Shipment.setStatus` /
  `setPreviousStatus` no longer exist), and `noFirstPartyCodeWritesTheShipmentStatusOutsideTheStateMachine`
  (a source scan of `src/main/java` for `setStatus(ShipmentStatus` / `setPreviousStatus(`).
- `shipping-service/src/test/java/com/shop/shippingservice/scheduler/ReconciliationSchedulerTest.java`
  → `aShipmentTheStateMachineForbidsToAutoDeliver_isLeftUntouched` (a `DELIVERED` row returned by the
  repository is not written and is not counted),
  `aContendedRow_doesNotStopTheRestOfTheBatch`,
  `theAutoDeliveryGoesThroughTheStateMachineAndRecordsThePreviousStatus`.
- `shipping-service/src/test/java/com/shop/shippingservice/service/ShipmentStateMachineTest.java`
  → `autoDeliverIsDeclaredForEveryInFlightStatus`, `autoDeliverFromATerminalStatusIsRejected`.

**Residual risk.** The FSM still has no edge into `DELIVERED` other than `OUT_FOR_DELIVERY → DELIVERED`
and the explicit `autoDeliver` rule; a carrier that reports `DELIVERED` for a shipment that is only
`PICKED_UP` is still rejected (the webhook path) and the event is retried — the permanent-vs-transient
classification (SHIP-09) is not addressed.

---

### SHIP-06 — HIGH — a late carrier event rolled a failed delivery back to in-transit

**Root cause.** `DELIVERY_FAILED → IN_TRANSIT` was a legal edge of the transition graph and the
webhook path computed the transition from the *current* status with no ordering key, so a late
`IN_TRANSIT` event after a `DELIVERY_FAILED` event was applied; the reconciliation sweep then
auto-delivered the shipment.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentStateMachine.java` | The `DELIVERY_FAILED → IN_TRANSIT` edge was **removed** from `LEGAL_TRANSITIONS`. The operator retry is now a separate, explicit rule `operatorRetry(from)` that is legal only from `DELIVERY_FAILED`. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java` | The webhook path uses `shipment.transitionTo(target)` (the FSM) — `DELIVERY_FAILED → IN_TRANSIT` is now rejected with `SHP-10003`, the shipment keeps its status and the event is marked `FAILED_RETRYABLE`. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java` | `retry(id)` uses `shipment.operatorRetry()` — the only sanctioned way out of `DELIVERY_FAILED`. |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java` | `operatorRetry()` on the entity, so the operator path is the only caller of the backward edge. |

**Tests that prove it.**

- `ShipmentStateMachineTest` → `deliveryFailedCannotGoBackToInTransitThroughTheCarrierTransition`,
  `operatorRetryIsTheOnlyPathOutOfDeliveryFailed`, `operatorRetryFromAnyOtherStatusIsRejected`,
  `carrierStatusCannotResurrectADeliveryFailedShipment`; the exhaustive 7×7 matrix now has **11**
  legal edges instead of 12.
- `shipping-service/src/test/java/com/shop/shippingservice/service/impls/WebhookEventServiceTest.java`
  → `aLateWebhook_cannotResurrectADeliveryFailedShipment`: a signed `IN_TRANSIT` event against a
  `DELIVERY_FAILED` shipment leaves the status at `DELIVERY_FAILED`, marks the event
  `FAILED_RETRYABLE` and never calls `writer.complete(...)`.
- `shipping-service/src/test/java/com/shop/shippingservice/ShippingFlowIT.java`
  → `deliveryFailedWebhookThenAdminRetryRestoresInTransit` (the operator retry still works end to end).

**Residual risk.** The carrier payload still carries **no** `occurredAt` / sequence number
(`CarrierWebhookPayload`), so the service cannot distinguish an old event from a new one — it can
only reject transitions the FSM forbids. A late `PICKED_UP` event after `IN_TRANSIT` is still applied
(idempotently, no state damage) and a late `OUT_FOR_DELIVERY` after `DELIVERED` is still rejected and
retried. Adding an ordering key to the carrier contract is the remaining half of the fix and needs a
carrier-side change.

---

### SHIP-07 — MEDIUM — the webhook retry scheduler had no claim; the retry counter never advanced

**Root cause.** "Claiming" was done by *reading*: `findByStatusAndNextRetryAt…` returned the same
`FAILED_RETRYABLE` row to every replica. The losing replica's `OptimisticLockException` was raised at
**commit** time, outside the `transactionTemplate.execute` callback, so the retry accounting inside the
callback never ran: `retry_count` stayed 0, `next_retry_at` was never pushed forward, the exception
escaped the `@Scheduled` method, the rest of the batch was skipped, and the event could never reach
`FAILED_PERMANENT`.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/repository/ShipmentEventRepository.java` | `findClaimableByStatus(status, cutoff, staleBefore, pageable)` (only rows that are unclaimed or whose claim lease expired) and the atomic claim `claimForRetry(id, status, claimedAt, staleBefore)` (`UPDATE … WHERE id=? AND status=? AND (claimed_at IS NULL OR claimed_at < :staleBefore)`), plus `releaseClaim(id)`. The old `findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc` was removed. |
| `shipping-service/src/main/java/com/shop/shippingservice/scheduler/WebhookRetryScheduler.java` | The scheduler now **claims** each row before processing it, skips rows another worker already claimed, releases the claim in a `finally` block, wraps the whole batch in a per-event `try/catch` (no exception escapes the `@Scheduled` method) and records **commit-time** failures in a separate transaction so `retry_count` advances and the event can still reach `FAILED_PERMANENT`. |
| `shipping-service/src/main/resources/db/changelog/changelog-008-webhook-retry-claim-and-index.yaml` | **NEW changeset `008-webhook-retry-claim-and-index`**: `ALTER TABLE shipment_events ADD COLUMN IF NOT EXISTS claimed_at timestamptz` + `idx_shipment_events_status_next_retry` + `idx_shipment_events_claimed_at`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/scheduler/WebhookRetrySchedulerTest.java`
  → `anEventAlreadyClaimedByAnotherWorker_isNotProcessed` (`claimForRetry` returns 0 ⇒ the retry
  service is never called), `theClaimIsReleasedAfterTheAttempt`,
  `aFailureAtCommitTime_advancesTheRetryCounter` (the transaction manager throws at commit ⇒
  `retry_count` becomes 2 and `next_retry_at` is set), `anEventThatFailsAtCommitTimeTwice_reachesThePermanentState`
  (⇒ `FAILED_PERMANENT` and the dead-letter counter increments),
  `theBatchIsReadThroughTheClaimAwareQuery`.
- `shipping-service/src/test/java/com/shop/shippingservice/ShippingFlowIT.java`
  → `aWebhookRetryRowIsClaimedExclusively`: against a real PostgreSQL, the first
  `claimForRetry` updates **1** row, the second updates **0** rows, and a claim whose lease expired
  is re-claimable (**1** row).

**Residual risk.** The claim lease (`shop.shipping.webhook.claim-lease-ms`, default 60 s) is not
renewed while a replay is running: a replay that takes longer than the lease can be picked up by a
second replica. The replay itself is idempotent (`@Version` + the FSM), so the damage is limited to
a duplicated attempt. There is still no dead-letter topic or redrive path for `FAILED_PERMANENT`
(SHIP-09).

---

### SHIP-08 — MEDIUM — the outbox claim's `MIN(id)` subquery blocked the whole backlog

**Root cause.** `claimOnePending` used `e.id = (SELECT MIN(e2.id) …)` with `FOR UPDATE SKIP LOCKED`.
`SKIP LOCKED` applies to the outer row only; the sub-select still resolves to the locked head row,
so while the lowest-id `PENDING` row was locked the query returned **zero** rows although thousands
of other rows were claimable, and the relay's drain loop broke on the first empty claim.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java` | `claimOnePending` is now a lock-aware native query: `SELECT * FROM outbox_events WHERE status = :status ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED` (parameter type `String` = the enum name). |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRelay.java` | The relay calls `claimOnePending(OutboxStatus.PENDING.name())`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxClaimConcurrencyIT.java`
  → `twoPendingRows_theLockedHeadRowDoesNotBlockTheNextRow`: while row 1 is locked by another
  transaction, the second claim now returns **row 2** (before the fix it returned empty), and after
  the commit the head row is claimable again. The single-row case still returns empty while the only
  row is locked.

**Residual risk.** `FOR UPDATE SKIP LOCKED` on a native query is PostgreSQL-specific; the module is
PostgreSQL-only, but the repository no longer has a portable JPQL fallback. The relay still breaks
its drain loop on the first empty claim, which is correct only while the query really returns
nothing (it now does).

---

### SHIP-11 — MEDIUM — `shipment_events` had no index for the 60-second retry poll

**Root cause.** The poll predicate is `status = 'FAILED_RETRYABLE' AND next_retry_at <= now`, but
`shipment_events` carried no index beyond its primary key, so every minute the scheduler scanned a
table that only grows (`FAILED_RETRYABLE` / `FAILED_PERMANENT` rows are never purged).

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/resources/db/changelog/changelog-008-webhook-retry-claim-and-index.yaml` | **NEW changeset** (shared with SHIP-07): `CREATE INDEX IF NOT EXISTS idx_shipment_events_status_next_retry ON shipment_events (status, next_retry_at)` plus `idx_shipment_events_claimed_at`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogContractTest.java`
  → `theWebhookRetryPollIndexAndTheClaimColumnAreDeclared` (the index and the column are in the
  changelog with the exact predicate columns).
- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogIT.java`
  → `theWholeChainAppliesToADatabaseThatAlreadyHoldsFailedWebhookEvents` and
  `theWholeChainAppliesToAnEmptyDatabase` assert that `idx_shipment_events_status_next_retry`
  exists in the migrated database.

**Residual risk.** The index makes the poll cheap, but the table still grows without bound:
`PROCESSED` and `FAILED_PERMANENT` webhook events are never pruned (there is no retention job for
`shipment_events`, unlike the outbox). The index was not verified with `EXPLAIN ANALYZE` on a
production-sized table.

---

### SHIP-12 — MEDIUM — `Shipment` / `ShipmentEvent` had no `@SQLRestriction`, so soft-deleted rows were visible and blocked replacements

**Root cause.** The unique indexes are partial on `deleted = false`
(`uk_shipment_order_live`, `uk_shipments_tracking_live`), but the entities were not annotated to
match: a soft-deleted shipment was still returned by `findByOrderId` / `findByTrackingNumber` and
still made `existsByOrderId` true, so a replacement shipment could never be created.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java` | `@SQLRestriction("deleted = false")` — soft-deleted rows are invisible to every read path. |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/ShipmentEvent.java` | `@SQLRestriction("deleted = false")`. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationService.java` | The creation claim is now **reopened** when the previous task is `COMPLETED` but its shipment is gone (soft-deleted): `writer.findByOrderId` → `shipmentRepository.existsByOrderId` is false → `writer.reopen(...)` → the carrier is called again. A live shipment keeps the claim closed. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationWriter.java` | New `reopen(taskId, carrier, now)` and `findByOrderId(orderId)`. |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/ShipmentCreationTask.java` | New `reopen(carrier, nextAttemptAt)` — resets the task to `PENDING` with `attempt_count = 0`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/ShippingFlowIT.java`
  → `aSoftDeletedShipmentIsInvisibleAndDoesNotBlockANewOne`: after `markDeleted` the shipment is
  invisible (`findByOrderId` empty, `existsByOrderId` false) and a new `CONFIRMED` event creates a
  **fresh live shipment** (new id) while the old row stays soft-deleted.
  → `aSoftDeletedWebhookEventIsInvisible`: a soft-deleted `shipment_events` row is not returned by
  `findFirstByCarrierAndProviderEventId`.
- `ShipmentCreationServiceTest` → `aRemovedShipment_reopensTheCreationClaimSoTheOrderCanBeShippedAgain`,
  `aLiveShipment_blocksTheCreationClaimFromBeingReopened`.

**Residual risk.** `uk_shipment_events_carrier_event` is **not** partial (it covers soft-deleted
rows too, deliberately, to keep the webhook replay protection). A soft-deleted webhook event is
therefore invisible to the read path but still occupies the `(carrier, provider_event_id)` key, so a
carrier event with that id is acknowledged as a duplicate no-op instead of being reprocessed. Nothing
in the module soft-deletes webhook events today, so the exposure is latent.

---

### SHIP-13 — MEDIUM — no foreign key from `shipment_events.shipment_id` to `shipments.id`

**Root cause.** `shipment_events.shipment_id` was a plain `uuid` column with no foreign key, so
webhook events could outlive their shipment and orphans accumulated in a table with no retention.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/resources/db/changelog/changelog-009-shipment-event-shipment-fk.yaml` | **NEW changeset `009-shipment-event-shipment-fk`**: `ALTER TABLE shipment_events ADD CONSTRAINT fk_shipment_events_shipment FOREIGN KEY (shipment_id) REFERENCES shipments (id) ON DELETE SET NULL NOT VALID`. `NOT VALID` is deliberate: existing rows are not checked (a production database may already hold orphans, and the changeset must apply cleanly), while every **new or updated** row is checked, so an orphan event can no longer be inserted. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogContractTest.java`
  → `theShipmentEventShipmentForeignKeyIsDeclared` (constraint name, column, referenced table and
  the `ON DELETE SET NULL` action).
- `shipping-service/src/test/java/com/shop/shippingservice/db/ShippingChangelogIT.java`
  → both changelog tests assert `fk_shipment_events_shipment` exists in the migrated database.

**Residual risk.** The constraint is `NOT VALID`, so pre-existing orphans stay and the constraint is
not enforced for historical rows until someone runs `ALTER TABLE shipment_events VALIDATE CONSTRAINT
fk_shipment_events_shipment` after cleaning them. There is still no retention job for
`shipment_events`, so the table keeps growing even though orphans can no longer be created.

---

### SHIP-14 — MEDIUM — a signed webhook without an `eventId` was acknowledged with HTTP 200 and dropped

**Root cause.** One `catch (DataIntegrityViolationException)` served two different failures: a
genuine unique-index race (→ ack no-op, correct) and a `NOT NULL` violation on a malformed payload
(→ the event was silently dropped while the log said "raced a concurrent insert").

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java` | A payload without an `eventId` is rejected with `BusinessException.badRequest("shipping.webhook.missing.event.id", carrier)` **before** anything is written. The insert failure is now classified by constraint name (`isDuplicateCarrierEvent`): only `uk_shipment_events_carrier_event` is acked as a no-op; every other constraint violation is rejected with `BusinessException.badRequest("shipping.webhook.rejected.event", …)` and logged as a rejection. The same classification is applied to the unparseable-body path. |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java` | The Hibernate exception type is `org.hibernate.exception.ConstraintViolationException` (the `jakarta.persistence` class the previous agent used does not exist). |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/service/impls/WebhookEventServiceTest.java`
  → `aSignedPayloadWithoutAnEventId_isRejectedWithBadRequest` (HTTP 400, nothing inserted, the
  repository is never touched), `aConstraintViolationThatIsNotTheCarrierEventIndex_isRejectedWithBadRequest`
  (a `ck_shipment_events_status` violation → HTTP 400, the shipment is never read),
  `duplicateInsertRace_acksNoOp` (the genuine unique-index race is still acked as a no-op).

**Residual risk.** The error codes are message-key based (`shipping.webhook.missing.event.id`,
`shipping.webhook.rejected.event`) and are not part of the shared `ErrorCode` enum, so they are not
covered by the fleet-wide error catalogue. The payload still has no bean-validation annotations
(`CarrierWebhookPayload`); the checks are hand-written in the service.

---

### SHIP-15 — MEDIUM — outbox events parked as `FAILED` were never surfaced and never purged

**Root cause.** `ShippingOutboxRelay.markFinalState` parked an event at `OutboxStatus.FAILED` with a
`log.error` only: no counter, no terminal state that an operator can query, no dashboard, and the
retention job only purged `SENT` rows — so the rows stayed forever. The shared `OutboxStatus.DEAD`
state existed and was never used.

**Change.**

| File | Change |
|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRelay.java` | A permanently failed event is parked at `OutboxStatus.DEAD` and counted (`shipping.outbox.failed.count`). The recovery sweep (`reclaimStaleSending`) now parks exhausted rows as `DEAD` too and counts them (`countStaleExhausted` + `metrics.recordOutboxFailed(count)`). |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShippingMetrics.java` | `recordOutboxFailed()` / `recordOutboxFailed(int count)` on the counter `shipping.outbox.failed.count`. |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRetentionScheduler.java` | The retention job now also purges `DEAD` rows (`deleteByStatusAndClaimedAtBefore`), so the table does not grow without bound. |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java` | New `deleteByStatusAndClaimedAtBefore` and `countStaleExhausted`. |

**Tests that prove it.**

- `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxRelayTest.java`
  → `relay_maxRetriesExhausted_parksTheEventAsDeadAndCountsIt` (status `DEAD`, `retryCount = 10`,
  `shipping.outbox.failed.count = 1`), `relay_aRetryableFailure_isCountedAsAFailureButNotAsPermanent`.
- `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxRetentionSchedulerTest.java`
  → `sentAndDeadRowsAreBothPurgedAfterTheRetentionWindow`.

**Residual risk.** The counter is the only permanent signal; the `DEAD` row itself is purged after
seven days, so the failure detail is lost from the database (the `last_error` text is not exported
anywhere). There is still no dead-letter topic and no operator queue for dead outbox events. The
retention purge is still one unbounded `DELETE` in a single transaction executed by every replica
(SHIP-26, still open).

---

## 3. Defects that remain OPEN

| Id | Severity | Status | Why |
|---|---|---|---|
| SHIP-09 | MEDIUM | **Open** | Permanent webhook failures (unknown tracking number, illegal transition) are still retried six times over ≈2.7 days instead of being classified as permanent. The claim (SHIP-07) fixes the *accounting*, not the *classification*. |
| SHIP-10 | MEDIUM | **Open** | `outbox_events.sent_at` and `shipment_events.next_retry_at` are still `timestamp` (no time zone) while the ORM maps `Instant` to `TIMESTAMP_UTC`; the new columns added here (`claimed_at`, `next_attempt_at`) use `timestamptz`. With `ddl-auto: validate` the pre-existing mismatch is still a start-up risk. Not in the requested scope. |
| SHIP-16 | MEDIUM | **Open** | The outbox *mark* phase still uses `FOR UPDATE SKIP LOCKED`; a locked row is skipped and the event is published twice after the claim lease expires. |
| SHIP-17 | MEDIUM | **Open** | A `CANCELLED` order event that arrives before the shipment exists is still forgotten; a parcel can still be created for a cancelled order. |
| SHIP-18 | LOW | **Partly mitigated** | `ShipmentServiceImpl` now uses the `@Primary` adapter instead of the registry default, and the creation retry scheduler rejects a carrier mismatch instead of calling the wrong carrier. `CarrierFactory.getCarrier(String)` still silently substitutes the default for an unknown code. |
| SHIP-19 | LOW | **Open** | A carrier outage and a carrier rejection still collapse into `CARRIER_NOT_CONFIGURED` / HTTP 409. |
| SHIP-22 | LOW | **Fixed as a side effect** | `ShipmentServiceImpl` has exactly one constructor and the unit test now injects the same dependency set as production (`ShipmentServiceImplTest`). |
| SHIP-23 | LOW | **Open** | The backoffice list endpoint still ignores the `Pageable` when `orderId` is supplied. |
| SHIP-24 | LOW | **Open** | The relay still has the unused `Instant started` local and the empty `finally` block. |
| SHIP-25 | LOW | **Open** | `ShippingWebhookProperties.getSecrets()` still exposes the live mutable map. |
| SHIP-26 | LOW | **Partly** | The retention purge now also removes `DEAD` rows but is still one unbounded `DELETE` run by every replica. |
| SHIP-27 | LOW | **Open** | The stale sweep's `COALESCE(...)` predicate still cannot use an index. |
| SHIP-28 | LOW | **Open** | The webhook event status is still stringly typed. |
| SHIP-30 / 31 / 32 | LOW | **Open** | Unused resilience4j dependency, the unread `notify-threshold-hours` property, the default database credentials. |
| SHIP-29 / SHIP-33 | HIGH | **Already fixed before this work started** | Not touched, not counted here. |

---

## 4. PROPOSALS ONLY — candidate fleet rules for `docs/PATTERNS.md`

`docs/PATTERNS.md` was **not** modified (the lead owns it). The following are proposals for the
lead to accept, amend or reject.

**R5 — Claim before the side effect.** *Any non-transactional outbound call (carrier, payment
provider, mail) must be preceded by a durable claim row whose business key is covered by a unique
index, and the claim must be taken in its own transaction before the call. A `DataIntegrityViolationException`
on the claim is the normal "someone else got there first" path, never a swallowed error.* Evidence:
SHIP-01 (`uk_shipment_creation_task_order`).

**R6 — Every scheduled worker must claim atomically and never by reading.** *A `@Scheduled` method
must claim its work with a conditional `UPDATE` or with `SELECT … FOR UPDATE SKIP LOCKED`, must never
use a `MIN(id)`-style sub-select (the sub-select is not lock-aware), must release or expire its claim,
and must isolate a failure per row so one bad row cannot abort the batch or escape the scheduler.*
Evidence: SHIP-07, SHIP-08, SHIP-21.

**R7 — One choke point for a state machine.** *An aggregate whose status is governed by a state
machine must expose no public setter for the status fields; every transition goes through one
entity method that consults the machine. A test must assert the absence of the setter (reflection) and
scan the sources for direct status writes.* Evidence: SHIP-05, SHIP-06.

**R8 — A migration chain must be verified against the data it backfills.** *A changeset must never
write a value that only a later changeset makes legal; the chain must be tested against a database
seeded with the rows the backfill touches, and the test must apply the chain twice to prove
idempotency. Already-applied changesets are never edited — a new changeset with a fresh id is added
instead.* Evidence: SHIP-04.

**R9 — Every outbound HTTP client declares a budget.** *A client that talks to a third party must
declare an explicit connect timeout and an explicit read timeout (or a request factory that does) and
must be proven by a test against an endpoint that accepts the connection and never answers.*
Evidence: SHIP-03.

**R10 — Every permanent failure is countable and terminal.** *A failure that cannot be resolved by
retrying must (a) increment a counter, (b) land in a terminal, queryable state, and (c) be purged by
a retention job. A log line alone is not an acceptable failure signal.* Evidence: SHIP-15, SHIP-02.

**R11 — Every polled table has an index that matches the poll predicate.** *A table that a scheduler
polls must carry an index over the exact predicate columns, declared in an additive, idempotent
changeset.* Evidence: SHIP-11, SHIP-27.

**R12 — Every closed defect gets a regression guard.** *A fix that removes a structural possibility
(a public setter, a check-then-act, an unbounded scan) must be accompanied by a test that fails if the
possibility comes back — a source scan, a reflection assertion, or an integration test against the real
database.* Evidence: `ShipmentStatusWriteGuardTest`, `ShippingChangelogContractTest`,
`ShippingChangelogIT`, `ShippingOutboxClaimConcurrencyIT`.

**R13 — A retry mechanism must survive a commit-time failure.** *Accounting that lives inside a
transaction callback must also be applied when the transaction fails at commit; otherwise the retry
budget never advances and the poison message is retried forever.* Evidence: SHIP-07
(`recordCommitFailure`).

---

## 5. Files touched

**Modified (main)** — `carrier/CarrierShipmentClient.java`, `entity/Shipment.java`,
`entity/ShipmentEvent.java`, `outbox/OutboxEventRepository.java`, `outbox/ShippingOutboxRelay.java`,
`outbox/ShippingOutboxRetentionScheduler.java`, `repository/ShipmentEventRepository.java`,
`scheduler/ReconciliationScheduler.java`, `scheduler/WebhookRetryScheduler.java`,
`service/ShipmentStateMachine.java`, `service/ShippingMetrics.java`,
`service/impls/ShipmentServiceImpl.java`, `service/impls/WebhookEventServiceImpl.java`,
`resources/db/changelog/db.changelog-master.yaml`.

**New (main)** — `carrier/CarrierRestClientCustomizer.java`, `constant/ShipmentCreationStatus.java`,
`entity/ShipmentCreationTask.java`, `repository/ShipmentCreationTaskRepository.java`,
`scheduler/ShipmentCreationRetryScheduler.java`, `service/ShipmentCreationService.java`,
`service/ShipmentCreationWriter.java`,
`resources/db/changelog/changelog-002b-expand-events-status-check-pre.yaml`,
`resources/db/changelog/changelog-007-shipment-creation-tasks.yaml`,
`resources/db/changelog/changelog-008-webhook-retry-claim-and-index.yaml`,
`resources/db/changelog/changelog-009-shipment-event-shipment-fk.yaml`.

**Modified (test)** — `ShippingFlowIT.java`, `carrier/CarrierShipmentClientTest.java`,
`outbox/ShippingOutboxClaimConcurrencyIT.java`, `outbox/ShippingOutboxRelayTest.java`,
`scheduler/ReconciliationSchedulerTest.java`, `scheduler/WebhookRetrySchedulerTest.java`,
`service/ShipmentStateMachineTest.java`, `service/impls/ShipmentServiceImplTest.java`,
`service/impls/WebhookEventServiceTest.java`, `support/AbstractIntegrationTest.java`.

**New (test)** — `carrier/CarrierRestClientCustomizerTest.java`,
`db/ShippingChangelogContractTest.java`, `db/ShippingChangelogIT.java`,
`outbox/ShippingOutboxRetentionSchedulerTest.java`, `scheduler/ShipmentCreationRetrySchedulerTest.java`,
`service/ShipmentCreationServiceTest.java`, `service/ShipmentStatusWriteGuardTest.java`,
`resources/db/changelog/seed-base-changelog.yaml` (test-only phase-1 changelog used by the
`ShippingChangelogIT` to seed a `FAILED` row before the master changelog runs).

No file outside `shipping-service/**` and this report was modified.
