# Concurrency and Multi-Instance Safety Audit

**Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Revision audited:** `59ae0c05092a8b95ad2f02d6fd6c647e38240d5a` (HEAD at the time of writing). The working tree carried uncommitted edits in `shipping-service/**`, `rating-service/**` and `utils/**` from concurrent agents; every claim below was re-verified against the **current source**, and where the committed revision and the working tree differ the divergence is stated explicitly.
**Method:** a read of every first-party source that participates in a shared-resource interaction — 8 outbox relays and their repositories, 7 Kafka listener factories, all 31 `@Scheduled` methods, every `@Version` / `@Lock` / `@Modifying` declaration in the fleet, the Redis Lua limiter, the Elasticsearch projection, and the storage adapter. No Maven build was run and no runtime was observed; statements about runtime semantics are derived from the source with the reasoning shown, or explicitly marked as unverified.
**Scope:** concurrency only. Pattern/DDD analysis lives in `production-audit/reports/design-pattern-adoption.md`; this report does not repeat it.

---

## 1. Executive summary

The fleet has a **good concurrency core and a bad concurrency edge**. The primitives that matter most are present and, in most places, correct:

- Every outbox relay claims with `PESSIMISTIC_WRITE` + `lock.timeout = -2` (`SKIP LOCKED`) — `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java:22-26`.
- The order idempotency claim is arbitrated by a composite primary key, not by a read — `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java:50-69`.
- Stock reservation is a conditional SQL `UPDATE` — `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java:18-23`.
- Promotion quota is a conditional `UPDATE` / `INSERT … ON CONFLICT` — `promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignRepository.java:26-31`, `.../CampaignUserUsageRepository.java:13-21`.
- The Redis edge limiter is a single Lua script — `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:19-47`.

What is missing is **the last mile**: the code arbitrates *rows*, but several paths perform an **irreversible external side effect before the row arbitration happens**. That is why the single worst defect in this audit (**CONC-01**) is not a lost update — it is a duplicate physical parcel that the database is structurally unable to prevent, because by the time the unique index fires, the parcel already exists at the carrier.

The second structural theme is **per-replica schedulers with no claim**. 31 `@Scheduled` methods exist fleet-wide: 8 are outbox relays (which claim with `SKIP LOCKED`) and 10 are retention sweeps (whose `DELETE … WHERE` is naturally idempotent). Of the remaining 13 workers, 5 claim their work atomically — the inventory reservation expiry sweep, the promotion reservation expiry sweep, the compensation ledger, the notification retry loop and the shipping shipment-creation retry loop. The other 8 — including the order reconciliation sweeper and both webhook retry loops — select rows without any lock, so **every replica processes the same rows concurrently**.

Third: **commit semantics are never pinned.** No `setAckMode` call exists anywhere in the repository, so every listener container inherits the framework default `AckMode.BATCH` while `max.poll.records` is 500 (`utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:82`). A single rebalance therefore replays up to 500 records, which makes every idempotency guard in the fleet load-bearing — and several of them are not atomic (§4).

**Findings: 27.** Severity distribution: 1 CRITICAL, 9 HIGH, 11 MEDIUM, 6 LOW.

---

## 2. Per-resource verdicts

| # | Resource | Verdict under N concurrent instances | One-line reason |
| --- | --- | --- | --- |
| 1 | **PostgreSQL** | ⚠️ **Conditionally safe** | Row-level arbitration is present and correct (`SKIP LOCKED` claims, conditional `UPDATE`s, unique indexes), but four write paths perform read-modify-write without a lock and one performs an external side effect before the arbiter fires. |
| 2 | **Redis** | ✅ **Safe** | Both limiters are atomic scripts executed by the Redis server; the edge limiter uses server time (`redis.call('TIME')`) so replicas do not need synchronised clocks; the outage policy defaults to `FAIL_CLOSED`. |
| 3 | **Kafka** | ⚠️ **Safe only with idempotent consumers** | Producer side is strong (`enable.idempotence=true`, `max.in.flight=1`, `acks=all`); consumer side never pins `AckMode`, one listener factory has no dead-letter recoverer, and the relay publishes outside the claim transaction. |
| 4 | **Elasticsearch** | ⚠️ **Conditionally safe** | External versioning correctly rejects out-of-order events — but only when the event carries a parseable timestamp; otherwise the write is unversioned last-write-wins, and the reindex is guarded only in-process. |
| 5 | **Filesystem / object storage** | ✅ **Safe** | Object keys are derived from a freshly generated UUID per upload, so two replicas never collide on a key; the dedupe race is arbitrated by `uk_medias_sha256` and the loser purges its own orphans. The local filesystem is used only for per-instance temp files. |

---

## 3. Resource-by-resource analysis

### 3.1 PostgreSQL

**Isolation level.** No module overrides the JPA default, so every transaction runs at PostgreSQL's `READ COMMITTED`. Consequences that matter:

- A plain `SELECT` never blocks and never sees an uncommitted write → every "read, then decide, then write" sequence in this fleet is a TOCTOU unless a lock or a conditional `UPDATE` closes it.
- `SELECT … FOR UPDATE SKIP LOCKED` (the outbox claim) and `SELECT … FOR UPDATE` with a timeout (the cart lock) behave exactly as the fleet expects under `READ COMMITTED`.

**Row locks and claim patterns.** Six distinct claim shapes exist, and they are not equivalent:

| Claim | Mechanism | Atomic across replicas? | File:line |
| --- | --- | --- | --- |
| Outbox relay (8 modules) | `PESSIMISTIC_WRITE` + `lock.timeout=-2` | ✅ Yes | `shipping-service/.../outbox/OutboxEventRepository.java:22-26`; identical in inventory, order, payment, product, promotion, rating, media |
| Outbox final mark | `PESSIMISTIC_WRITE` + `lock.timeout=-2` by id | ✅ Yes | `shipping-service/.../outbox/OutboxEventRepository.java:32-35` |
| Compensation ledger | conditional `UPDATE … WHERE id=? AND status IN …` | ✅ Yes | `order-service/.../repository/FailedCompensationRepository.java:27-35` |
| Reservation expiry | conditional `UPDATE … WHERE id=? AND status=:pending` | ✅ Yes | `inventory-service/.../repository/ReservationRepository.java:33-38` |
| Notification retry | `PESSIMISTIC_WRITE` **without** `SKIP LOCKED` | ✅ Yes (blocks instead of skipping) | `notification-service/.../repository/NotificationRepository.java:29-39` |
| Cart (checkout only) | `PESSIMISTIC_WRITE`, `lock.timeout=3000` | ✅ Yes (blocks; times out after 3 s) | `order-service/.../repository/CartRepository.java:23-31` |

`SKIP LOCKED` on the outbox claim is the right choice: it lets N replicas drain one queue without convoying. The one place it is **not** used and would be wrong is the notification retry (`NotificationRepository.java:29-39`): it takes a plain `PESSIMISTIC_WRITE`, so a replica that finds a row locked by another replica **blocks** on it rather than skipping — correctness is fine, throughput is not.

**Optimistic vs pessimistic locking.** `@Version` is present on exactly six entities:

| Entity | `@Version` | File:line |
| --- | --- | --- |
| `Inventory` | ✅ | `inventory-service/.../entity/Inventory.java:47-48` |
| `Reservation` | ✅ | `inventory-service/.../entity/Reservation.java:63-66` |
| `Order` | ✅ | `order-service/.../entity/Order.java:84` |
| `Payment` | ✅ | `payment-service/.../entity/Payment.java:63` |
| `Shipment` | ✅ | `shipping-service/.../entity/Shipment.java:71` |
| `ShipmentEvent` | ✅ | `shipping-service/.../entity/ShipmentEvent.java:61` |

It is **absent** from `Cart` (`order-service/.../entity/Cart.java:15-27`), `CartItem`, `Rating`, `CouponUsageReservation`, `CampaignUserUsage`, and `Product`. That absence is the direct cause of CONC-03 and CONC-05.

**Unique constraints as the final arbiter.** These are the fleet's strongest concurrency primitive, because they are the only ones that cannot be raced:

| Constraint | Table | File:line |
| --- | --- | --- |
| `uk_shipment_order_live` | `shipments(order_id) WHERE deleted=false` | `shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml:40` |
| `uk_shipment_events_carrier_event` | `shipment_events(carrier, provider_event_id)` | `shipping-service/.../changelog-001-shipments.yaml:77` |
| `uk_payment_idempotency_key` | `payments(idempotency_key)` | `payment-service/.../changelog-001-payments.yaml:37` |
| `uk_payment_events_provider_event` | `payment_events(provider, provider_event_id)` | `payment-service/.../changelog-001-payments.yaml:73` |
| `uk_reservations_idempotency_key` | `reservations(idempotency_key)` | `inventory-service/.../changelog-007-reservations-idempotency-key.yaml:12-15` |
| `uk_medias_sha256` | `medias(sha256) WHERE deleted=false` | `media-service/.../changelog-001-medias.yaml:32` |
| `uk_rating_user_product_live` | `ratings(user_id, product_id) WHERE deleted=false` | `rating-service/.../changelog-001-ratings.yaml:42` |
| `uk_notification_event_id` | `notifications(event_id)` | `notification-service/.../changelog-001-notifications.yaml:40` |
| `pk_idempotency_keys` | `idempotency_keys(actor, key)` | `order-service/.../changelog-001-initial-schema.yaml:201-204` (column renamed at `changelog-005-idempotency-actor.yaml:12`) |
| `uc_failed_compensations_open` | partial unique on open compensations | `order-service/.../changelog-012-failed-compensation-sweeper.yaml:18` |

**Verdict.** The row-level layer is genuinely well built. The failure mode is not missing locks — it is **locks placed after the side effect**, and **write paths that were never given a lock or a version** (§5).

### 3.2 Redis

Two independent limiters exist.

**Framework limiter.** `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitConfiguration.java:16-31` builds two `RedisRateLimiter` beans (global and per-route). Spring Cloud Gateway's `RedisRateLimiter` executes a Lua script server-side, so the bucket is atomic across gateway replicas. Its key layout is not in this repository, so nothing further can be asserted about it.

**Hand-written edge limiter.** `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:19-47` implements a token bucket entirely in Lua:

- **Atomicity:** ✅. The read-modify-write of `tokens` and `timestamp` happens inside one script, which Redis executes atomically. Two gateway replicas hitting the same client IP cannot both consume the same token.
- **Clock:** ✅. The script uses `redis.call('TIME')[1]` (`:23`) — the **server** clock — not the caller's. This is the correct design for a fleet: replica clocks may drift, the Redis clock cannot.
- **TTL:** ✅ but with a caveat. `ttl = ceil((capacity / rate) * 2)` (`:40`) and both keys are written with `setex` (`:42-43`). The bucket therefore fully refills after at most twice the drain window, and the two keys always share a TTL because they are set in the same script.
- **Eviction:** ⚠️. The bucket is a *plain* key with no `maxmemory-policy` protection. The deployed Redis is standalone with no eviction policy configured (`docker-compose.yml:51-67` sets only `--requirepass` and `--appendonly yes`). If `maxmemory` is ever reached and an `allkeys-lru` policy is applied, an evicted `:tokens` key makes the next script evaluation reset `tokens = capacity` (`:27`) — a full-budget burst for that client IP. Nothing in the repository prevents this.
- **Key layout:** ⚠️. The script touches two keys (`:65-67`) that do **not** share a hash tag. On a standalone Redis this is fine, and the deployment *is* standalone. On a Redis Cluster the two keys hash to different slots and the script would fail with `CROSSSLOT`; the failure would be swallowed by `onErrorResume` (`:75-78`) and degrade to the outage policy.
- **Outage behaviour:** ✅. `RedisEdgeRateLimiter.acquire` maps any error to `EdgeRateLimitDecision.outage()` (`:74-78`), and `RateLimitOutageGuard` (`:21-34`) applies the configured policy. The deployed default is `FAIL_CLOSED` (`gateway-service/src/main/resources/application.yml:70`), so a Redis outage rejects traffic rather than removing the limit. This is the fail-closed posture R16 requires.

**Verdict: safe under N gateway instances.** The only multi-instance hazard is the eviction scenario (CONC-24), which is a capacity-planning problem rather than a correctness problem.

### 3.3 Kafka

**Producer side — strong.**

`KafkaProperties.buildProducerProperties` (`utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:61-73`) sets `acks=all` (`:64`), `retries=3` (`:65`), `max.in.flight.requests.per.connection=1` (`:66`), `enable.idempotence=true` (`:68`) and `compression.type=lz4` (`:69`). The combination `enable.idempotence=true` + `max.in.flight=1` + `acks=all` is exactly the recipe that guarantees no reordering and no duplication **within a producer session**. The keys are Kafka-native (no `properties.` prefix), satisfying R3.

Two limits must be stated precisely:

- The idempotent producer deduplicates **per producer session and per partition**. A broker failover or a producer restart opens a new session, and the in-flight batch can be re-delivered. This is why an idempotent producer is *not* an idempotent system.
- `isolation.level=read_committed` is set on the consumer (`:81`), but **no producer in the fleet uses transactions**, so `read_committed` and `read_uncommitted` are behaviourally identical here. The setting is harmless but currently vacuous.

**Consumer group rebalance.** Every `@KafkaListener` pins a `groupId`, enforced by `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/KafkaListenerGroupIdContractTest.java:23-50`. The groups in use are:

| Service | Group id(s) | Topic(s) | Note |
| --- | --- | --- | --- |
| shipping | `shipping-service` | `shop.order.lifecycle.v1` | `shipping-service/.../kafka/OrderEventConsumer.java:20` |
| notification | `notification-service` | `shop.order.lifecycle.v1` | `notification-service/.../kafka/OrderEventConsumer.java:20` |
| order | `order-service` | `shop.shipping.lifecycle.v1` | `order-service/.../kafka/ShippingDeliveredConsumer.java:20` |
| order | `order-service-payment-group` | `shop.payment.lifecycle.v1` | `order-service/.../kafka/PaymentLifecycleConsumer.java:21-25` |
| product | `product-service` | `shop.rating.lifecycle.v1` | `product-service/.../kafka/ProductRatingConsumer.java:19` |
| product | `product-service` | `media.lifecycle.v1` | `product-service/.../kafka/MediaDeletedConsumer.java:29` |
| search | `search-service` | `shop.product.lifecycle.v1` | `search-service/.../kafka/ProductSearchConsumer.java:23` |

`product-service` is the one case where **two listeners with disjoint topic subscriptions share a single consumer group**. Per-topic assignment (the `RangeAssignor` / `CooperativeStickyAssignor` behaviour) still gives each listener the partitions of the topic it subscribed to, so records are not stolen. The cost is real, though: the group has one membership and one rebalance protocol, so **any** membership change in either listener triggers a stop-the-world rebalance that stops *both*. A rolling restart of `product-service` therefore pauses rating ingestion and media-deletion ingestion together. That is CONC-08's sibling cost, folded into CONC-08.

**Offset commit semantics.** `enable.auto.commit=false` is set (`:80`, default `false` at `:176`), so the container owns the commit. But **no `setAckMode` call exists anywhere in the repository** — verified by a repo-wide grep over `*.java` for `setAckMode|AckMode` (zero hits outside the test tree). The container therefore inherits Spring Kafka's default `AckMode.BATCH`, with `max.poll.records=500` (`:82`) and `max.poll.interval.ms=300000` (`:83`).

The consequences are precise:

1. **Replay width.** On a rebalance, the uncommitted batch — up to 500 records — is re-delivered. Every downstream handler must therefore be idempotent for *every* record it handles, not just for the ones it expects to be duplicated.
2. **Zombie consumers.** With `BATCH` ack and a long carrier/provider call inside the handler, a consumer can exceed `max.poll.interval.ms`, be evicted, and have its partition reassigned **while it is still executing the handler**. Spring Kafka does not fence the evicted consumer. This is precisely the window that turns CONC-01 from a theoretical hazard into a reachable one (§5.1).
3. **R18 deviation.** `docs/PATTERNS.md:415-439` (R18) states that the shared consumer configuration "pins the commit semantics explicitly rather than inheriting a framework default". It does not. The rule is documented and unenforced.

**Dead-letter recoverer.** The shared base installs `DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000L, 3L))` (`utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:62-65`), so 6 of the 7 listener factories have a DLT. `product-service`'s media factory does not extend the base at all (`product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleListenerConfig.java:18-28`): no `setCommonErrorHandler` is called, so the container falls back to the framework default error handler, which retries a bounded number of times and then **logs and skips the record**. The event is lost with no dead-letter copy — a concurrency-relevant gap because the skipped record is exactly the one whose processing was failing, and no replay path exists for it (the DLT replay endpoint is additionally unreachable — see `design-pattern-adoption.md` DP-04).

**Outbox relay concurrency.** All 8 relays use the same shape (`shipping-service/.../outbox/ShippingOutboxRelay.java:58-120`):

1. `recoverStrandedClaims()` (`:122-132`) — a bulk conditional `UPDATE` that resets `SENDING` rows older than the lease back to `PENDING`. Atomic per row because the `UPDATE` takes the row lock.
2. Claim loop (`:64-81`) — one transaction per row, `PESSIMISTIC_WRITE` + `SKIP LOCKED`, sets `status=SENDING` and `claimedAt`.
3. Publish (`:86-104`) — **outside** the claim transaction.
4. Mark (`:112-117` → `:134-157`) — one transaction per row, re-reads with `findByIdForUpdate` and marks `SENT` only if the status is still `SENDING`.

Step 3 outside step 2 is a deliberate deviation from R12 (`docs/PATTERNS.md:269-276`, which requires the publish to run *inside* the claim transaction). It is a legitimate at-least-once design, but it opens a duplicate-publish window (CONC-10).

Two further relay observations:

- **`payment-service` and `product-service` have no `claimed_at` column.** Six relays stamp an explicit lease (`event.setClaimedAt(...)`: rating `:65`, media `:67`, shipping `:72`, order `:77`, promotion `:77`, inventory `:76`), and six changelogs add the column (`inventory/…-claim-lease.yaml`, `media/…`, `order/…`, `promotion/…`, `rating/…`, `shipping/006-…`). `payment-service` and `product-service` instead reuse `updated_at` as the lease proxy (`payment-service/.../outbox/OutboxEventRepository.java:43-54`; `product-service/.../repository/OutboxEventRepository.java:35-46`) — a weaker signal, because `updated_at` is bumped by any write to the row, not only by a claim. The two modules also have no `setClaimedAt` call at all (CONC-11).
- **`payment-service` does not apply the per-aggregate ordering guard.** `OutboxBatchOrdering.markableAsSent` (`utils/common-kafka/src/main/java/com/shop/common/kafka/producer/OutboxBatchOrdering.java:11-27`) is applied by 7 relays but not by `PaymentOutboxRelay` (`payment-service/.../outbox/PaymentOutboxRelay.java:88-96`), which marks each row independently from the raw batch outcome. Two events for the same payment can therefore be published out of order.

**Producer blocking.** `KafkaMessagePublisher.publish` blocks on `future.get(publishTimeoutMs, MILLISECONDS)` (`:56-66`) and `publishBatch` blocks on `latch.await(timeout, unit)` (`:114-120`). Both have timeouts. The only unbounded producer block in the fleet is `DeadLetterReplayService.java:101` — `template.send(...).get()` with **no timeout at all** — invoked from the DLT replay path (CONC-22).

**Verdict: safe only if every consumer is idempotent.** The producer side is genuinely well configured. The consumer side has three real gaps (unpinned `AckMode`, one factory without a recoverer, publish outside the claim transaction), and the Kafka rebalance model is the mechanism that converts all of them from "lost update" into "duplicate side effect".

### 3.4 Elasticsearch

**External versioning.** `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java:65-70` indexes with `.version(version).versionType(VersionType.External)`; the delete path does the same (`:104-107`). A 409 from either path is caught and treated as "stale event, rejected" (`:71-98`, `:111-135`). This is the correct mechanism: **a late-arriving event with an older version cannot overwrite a newer document.**

**Where the guarantee ends — three cases.**

1. **No version at all.** If the event carries no parseable timestamp, `ProductEventVersion.forEvent` returns `null` (`search-service/.../search/ProductEventVersion.java:16-19`), the code logs a warning (`:40-41`) and indexes **without any precondition** (`:58-62`). In that path the projection is last-write-wins, and an out-of-order event *will* overwrite a newer document.
2. **Event-time granularity.** The version is `epochSecond * 1e9 + nano` (`ProductEventVersion.java:34-41`). Two product updates within the same nanosecond produce the same version; ES `external` versioning requires a **strictly greater** version, so the second event is rejected as a conflict and silently dropped (`:71-75`).
3. **Event time vs processing time.** The ordering key is the *producer's* clock. A producer whose clock is ahead stamps a high version and permanently locks the document against every later, correctly-timestamped event — a **poisoned document**. Nothing in the fleet bounds the clock skew between producers.

**The reindex is not multi-instance safe.** `ReindexServiceImpl` guards reindex with an **in-process** `AtomicBoolean` (`search-service/.../service/impls/ReindexServiceImpl.java:37`, `:41-43`). With N replicas:

- All N replicas compute the same next index name from the same listing (`nextIndexName`, `:123-139`) → all N try to create `products-vN+1` → the losers fail on `createIndex` (`:145-152`).
- Worse, `deleteSupersededIndices` (`:175-187`) deletes **every** index matching the pattern except the one this replica just built. Replica A's cleanup therefore deletes replica B's freshly-created index — which replica B may already have aliased. The alias can end up pointing at a deleted index, which is a **search outage**, not just wasted work.

**Verdict: conditionally safe.** Single-writer projection with external versioning is correct; the fleet simply has to guarantee a single writer, and the only guard is a JVM-local boolean.

### 3.5 Filesystem and object storage

**Object storage.** `utils/common-storage/src/main/java/com/shop/common/storage/service/S3ObjectStorageService.java` is a thin, stateless adapter over the S3 client. The concurrency-relevant property is the **key layout**: the media service derives the key from a freshly generated `mediaId` (`media-service/.../service/impls/MediaUploadServiceImpl.java:109`, `:112`), so two replicas uploading the same bytes write to two *different* keys — no write collision, no overwrite.

**The dedupe race is arbitrated correctly.** `MediaUploadServiceImpl.upload` checks `mediaRepository.findBySha256(sha256)` (`:87`), writes the objects (`:122-137`), then inserts the row (`:150-155`). If two replicas upload identical bytes concurrently:

- Both miss the dedupe check.
- Both upload objects under their own distinct keys.
- Both insert the `medias` row → `uk_medias_sha256` (`media-service/.../changelog-001-medias.yaml:32`) rejects the loser.
- The loser catches `DataIntegrityViolationException` (`:160`), **purges exactly the objects it wrote** (`:162` → `purgeOrphans(writtenKeys)`), re-reads the winner and returns it (`:163-166`).

Because the keys are per-upload-UUID, the loser's purge cannot delete the winner's objects. This is a correct, well-designed concurrency story. The only weakness: the purge is best-effort, so a failed purge leaks orphaned objects (CONC-23).

**Bucket creation.** `ensureBucketExists` (`:46-59`) does `headBucket` then `createBucket` (`:61-68`) — a check-then-act race. Two replicas starting together can both see "no bucket" and both call `createBucket`; the loser gets `BucketAlreadyExists`/`BucketAlreadyOwnedByYou` from S3, which `createBucket` maps to `StorageException` (`:65-67`) — an **exception during startup** rather than a benign no-op (CONC-25).

**Filesystem.** The only local-disk use found is `media-service/src/main/java/com/shop/mediaservice/config/UploadLimitsConfig.java:18`, which points the temp directory at `${java.io.tmpdir}/media-upload`. That is per-container, per-instance local state; no cross-instance sharing, no lock needed.

**Verdict: safe.**

---

## 4. Idempotency inventory

Every idempotency mechanism in the codebase, with an explicit verdict on whether it is **genuinely atomic** (i.e. two concurrent attempts cannot both take effect).

| # | Mechanism | Location | Atomic? | Why |
| --- | --- | --- | --- | --- |
| 1 | Order idempotency claim (`actor`, `key`) | `order-service/.../service/impls/IdempotencyServiceImpl.java:50-69` | ✅ **Yes** | The claim is inserted in a `PROPAGATION_REQUIRES_NEW` transaction (`:44-46`), so it commits independently of the business transaction. The insert is arbitrated by the composite PK `pk_idempotency_keys(actor, key)` (`changelog-001-initial-schema.yaml:201-204`); the loser catches `DataIntegrityViolationException` (`:62`) and re-reads the winner (`:63-65`). Two concurrent creates with the same key: exactly one wins. |
| 2 | Order idempotency **completion** | `IdempotencyServiceImpl.java:93-102` | ⚠️ **Race** | `complete` reads the row, sets `responseStatus` and saves. It does not use a conditional `UPDATE … WHERE response_status = 0`, so it can overwrite a row that another thread already completed. Narrow, but real. |
| 3 | Order idempotency **abort** | `IdempotencyServiceImpl.java:104-116` | ❌ **Not atomic (TOCTOU)** | `abort` reads the row filtered on `responseStatus == 0`, then calls `repository.delete(entity)` — Hibernate deletes **by id**, with no re-check of `response_status` in the `WHERE` clause. A concurrent `complete` between the read and the delete loses the completed record; the key becomes reusable → a duplicate order. See CONC-16. |
| 4 | Inventory reservation idempotency key | `inventory-service/.../service/impls/InventoryServiceImpl.java:140-145` (check), `:147-155` (insert) | ⚠️ **Arbitrated, not graceful** | The check is a read-then-insert; the unique index `uk_reservations_idempotency_key` (`changelog-007:12-15`) is the real arbiter. The loser gets a `DataIntegrityViolationException`; `ReservationServiceImpl.reserveIdempotently` (`:38-49`) re-runs the reserve to convert the violation into a replay. **But the keyless path (`InventoryServiceImpl.java:132-134`, which delegates with a `null` key, is the one the whole checkout flow uses) has no key at all**, so `uk_reservations_idempotency_key` is never exercised there. See CONC-18. |
| 5 | Payment idempotency key | `payment-service/.../service/impls/PaymentServiceImpl.java:73-101` | ✅ **Yes** | Same shape as #1 and equally correct: the insert is `REQUIRES_NEW` (`payment-service/.../service/PaymentWriter.java:26-29`) so the constraint violation does not poison the outer transaction; `uk_payment_idempotency_key` (`changelog-001-payments.yaml:37`) arbitrates; the loser re-reads the winner (`PaymentServiceImpl.java:96-99`) and verifies the payload (`replay`, `:103-110`). |
| 6 | Provider-side idempotency key | `payment-service/.../provider/StripeProvider.java:191-201`, `:234-237`; `shipping-service/.../carrier/CarrierShipmentClient.java:33` | ⚠️ **External, unverified** | The key is passed to Stripe (`RequestOptions.setIdempotencyKey`) and to the carrier (`Idempotency-Key: orderId`). Whether the carrier honours it is outside this repository. If it does not, CONC-01 produces two parcels. |
| 7 | Carrier webhook dedupe | `shipping-service/.../service/impls/WebhookEventServiceImpl.java:75-104` | ✅ **Yes, for the insert** | `uk_shipment_events_carrier_event` (`changelog-001-shipments.yaml:77`) arbitrates the insert; the loser recognises the duplicate and acks no-op (`:96-100`). |
| 8 | Carrier webhook **processing** dedupe | `WebhookEventServiceImpl.java:77-80` (status check), `:109-114` (process) | ❌ **Not atomic** | The "already PROCESSED → ack no-op" check is a plain read, and the event row is only marked `PROCESSED` at the *end* of processing (`:174`). Two concurrent deliveries of the same event both see a non-PROCESSED row and both call `process`. See CONC-07. |
| 9 | Stripe webhook dedupe | `payment-service/.../service/WebhookEventService.java:90-105` | ✅ for the insert / ❌ for the effect | The insert is arbitrated by `uk_payment_events_provider_event` (`changelog-001-payments.yaml:73`). The *effect* is not: `PaymentStateMachine.transition` returns `to` when `from == to` (`payment-service/.../service/PaymentStateMachine.java:25-27`), so a second concurrent delivery drives `CAPTURED → CAPTURED` and publishes a **second** `payment.captured.v1` (`WebhookEventService.java:167-179`). See CONC-07. |
| 10 | Media content dedupe | `media-service/.../service/impls/MediaUploadServiceImpl.java:87-92`, `:160-166` | ✅ **Yes** | `uk_medias_sha256` (`changelog-001-medias.yaml:32`) arbitrates; the loser purges its own objects and returns the winner's media. |
| 11 | Rating uniqueness | `rating-service/.../changelog-001-ratings.yaml:42` | ✅ **Yes** (row) | `uk_rating_user_product_live(user_id, product_id)`. One rating per user per product. |
| 12 | Notification event dedupe | `notification-service/.../changelog-001-notifications.yaml:40` | ✅ **Yes** (row) | `uk_notification_event_id(event_id)`. |
| 13 | Cart uniqueness | `order-service/.../changelog-001-initial-schema.yaml:28` | ✅ **Yes** | `idx_carts_user_active` — one live cart per user. `CartServiceImpl.getOrCreateCart` (`:176-189`) relies on it and catches the violation (`:184-188`). |
| 14 | Shipment-per-order uniqueness | `shipping-service/.../changelog-001-shipments.yaml:40` | ✅ **Yes** (row) / ❌ **no, for the side effect** | `uk_shipment_order_live(order_id)`. It prevents two shipment *rows*, but it fires **after** the carrier call (`ShipmentServiceImpl.java:92` before `:98`), so it cannot prevent two parcels. This is CONC-01. |
| 15 | State guards (order FSM) | `order-service/.../service/impls/OrderStatusServiceImpl.java:25-29` | ⚠️ **Check-then-act** | `validateTransition(order.getStatus(), target)` reads a status that was loaded earlier and is not locked; the subsequent write is versioned (`Order.java:84`), so the *row* is protected — but any side effect performed between the check and the write is not. See CONC-15. |
| 16 | State guards (shipment FSM) | `shipping-service/.../service/ShipmentStateMachine.java:28-33` | ⚠️ **Check-then-act** | No self-transition, so a duplicate carrier status throws `SHIPMENT_INVALID_TRANSITION` instead of being absorbed. The shipment row is `@Version`-protected (`Shipment.java:71`), but the *event* row is not protected against being overwritten with `FAILED_RETRYABLE` by the loser (`WebhookEventServiceImpl.java:111-114`). |
| 17 | State guards (payment FSM) | `payment-service/.../service/PaymentStateMachine.java:21-32` | ❌ **Self-transition allowed** | `from == to` returns `to` (`:25-27`), so a replayed webhook is *accepted* rather than rejected. This converts a duplicate delivery into a duplicate event. |
| 18 | Compensation ledger uniqueness | `order-service/.../changelog-012-failed-compensation-sweeper.yaml:18` | ✅ **Yes** | `uc_failed_compensations_open` — one open compensation per target. |
| 19 | Failed-compensation claim | `order-service/.../repository/FailedCompensationRepository.java:27-35` | ✅ **Yes** | Conditional `UPDATE … WHERE id=? AND status IN … AND (nextAttemptAt IS NULL OR nextAttemptAt <= :now)` with a lease. One of only five claim patterns in the fleet that are correct across replicas, and the most complete of them — **it is the template the eight unclaimed schedulers should copy**. |
| 20 | Reservation expiry claim | `inventory-service/.../repository/ReservationRepository.java:33-38` | ✅ **Yes** | Conditional `UPDATE … WHERE id=? AND status=:pending`. |
| 21 | Promotion quota claim | `promotion-service/.../repository/CampaignRepository.java:26-31` | ✅ **Yes** | Conditional `UPDATE … WHERE used_count < max_redemptions AND budget_used + :discount <= total_budget`. |
| 22 | Per-user promotion quota claim | `promotion-service/.../repository/CampaignUserUsageRepository.java:13-21` | ✅ **Yes** | `INSERT … ON CONFLICT … DO UPDATE … WHERE used_count < :limit` — the increment is conditional inside the upsert. |
| 23 | Stock reservation | `inventory-service/.../repository/InventoryRepository.java:18-23` | ✅ **Yes** | `UPDATE inventory SET reserved = reserved + :qty WHERE product_id = :id AND (available - reserved) >= :qty`. The invariant is enforced by the database, not by Java. |

**Summary of §4.** 13 of the 23 mechanisms are genuinely atomic. The failures cluster in one place: **every mechanism that guards a *row* is atomic, and every mechanism that guards an *external side effect* is not.** That is the structural reason CONC-01 is the worst finding in this audit.

---

## 5. Race conditions and TOCTOU

### 5.1 CONC-01 — The carrier call happens before the idempotency guard (CRITICAL)

**Where.** At the audited revision `59ae0c05`, `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:78-102`:

```java
 78  private void createShipment(OrderLifecycleEvent event) {
 79      UUID orderId = event.getOrderId();
 80      if (repository.existsByOrderId(orderId)) {          // <-- the guard
 81          log.info("Shipment for order {} already exists, skipping", orderId);
 82          return;
 83      }
 ...
 92      CarrierAdapter.ShipmentDraft draft = adapter.createShipment(orderId);   // <-- the side effect
 93      shipment.setTrackingNumber(draft.trackingNumber());
 ...
 98      writer.insert(shipment);                            // <-- the arbitration
 99      } catch (DataIntegrityViolationException e) {
100          log.info("Shipment for order {} already persisted by a concurrent consumer, skipping", orderId);
101      }
```

Line 92 calls the carrier. Line 98 is the first moment the platform records that a parcel exists. `uk_shipment_order_live` (`shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml:40`) arbitrates the row — **after** the parcel has already been created.

**The interleaving.** Two duplicate `order.confirmed.v1` events for order `O` are processed by two different consumer threads, `T1` on replica A and `T2` on replica B. (Kafka guarantees ordering *within* a partition, and both records carry the same key — the order id — so they share a partition. Two threads process them concurrently **only** when a consumer-group rebalance moves the record to a new owner while the previous owner is still running it. That is the normal, documented behaviour of a rebalance: the evicted consumer is not fenced, and with `enable.auto.commit=false` and `AckMode.BATCH` the uncommitted batch is redelivered.)

1. `T1` (replica A) receives the record and enters `createShipment` (`ShipmentServiceImpl.java:78`).
2. `T1` evaluates `repository.existsByOrderId(O)` (`:80`) → **false**. The guard has passed. No row lock is held; no shipment row exists.
3. `T1` calls `adapter.createShipment(O)` (`:92`) → an HTTP POST to the carrier. The call blocks for the carrier's latency — typically seconds.
4. **During step 3** the consumer group rebalances (a second `shipping-service` replica joins, or `T1` exceeds `max.poll.interval.ms = 300000`, `KafkaProperties.java:83`). The `order.confirmed.v1` record is reassigned to `T2` on replica B. `T1` is *not* fenced and keeps running.
5. `T2` evaluates `repository.existsByOrderId(O)` (`:80`) → **still false**, because `T1` has not inserted anything yet — the insert is at `:98`, downstream of the carrier call.
6. `T2` calls `adapter.createShipment(O)` (`:92`) → **the carrier creates a second physical parcel for order `O`.**
7. `T1`'s carrier call returns; `T1` inserts the shipment (`writer.insert`, `:98`) and commits.
8. `T2`'s carrier call returns; `T2` attempts the insert → `uk_shipment_order_live` rejects it → `DataIntegrityViolationException` → caught at `:99-101` and logged as *"Shipment for order `O` already persisted by a concurrent consumer, skipping"*.
9. **Outcome:** one shipment row, one tracking number, **two parcels at the carrier**. The second parcel is invisible to the platform and can be shipped to the customer. The log line at `:100` actively misreports the situation as a benign skip.

**Aggravating factors.**

- `CarrierShipmentClient` does send `Idempotency-Key: orderId` (`shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java:33`). If the carrier honours it, step 6 returns the *same* parcel and the damage is limited to a duplicated API call. **The platform cannot verify that the carrier honours it**, and the `NoopCarrierAdapter` / `ManualCarrierAdapter` implementations do not call the carrier at all — so the platform's own guarantee is still the unique index, which fires too late.
- The same guard-before-side-effect ordering appears in `ShipmentServiceImpl.handleOrderEvent` → `createShipment` → `ShipmentCreationService` in the **working tree**, where a concurrent agent has begun the remediation (see §5.1.1).
- The `existsByOrderId` guard is also a plain read (`shipping-service/.../repository/ShipmentRepository.java:38`) with no lock, so it cannot be made safe by adding `@Lock` alone — the side effect would still have to move after the arbitration.

**Fix.** Make the *claim* the first durable act and the carrier call a second, separately-recorded step:

1. Insert the shipment row with a `carrier_requested` state **inside** one transaction, relying on `uk_shipment_order_live` to arbitrate. The loser of that race never calls the carrier.
2. Move the carrier call **after** the transaction commits, into a retryable worker that owns a lease, and record the tracking number when it returns. The worker must be safe to run twice (the carrier call carries the deterministic `Idempotency-Key: orderId`).
3. Do not swallow the `DataIntegrityViolationException` at `:99-101` as a "skip" — it means "another instance already owns this parcel", and the caller must not proceed as if nothing happened.

#### 5.1.1 The in-flight remediation in the working tree

The working tree (uncommitted, `shipping-service/**` was being edited concurrently) replaces `createShipment` with a claim-then-call flow:

- `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:71-73` now delegates to `creationService.createForOrder(orderId, resolveCarrierAdapter())`.
- `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationService.java:32-41` claims a `ShipmentCreationTask` first and only then calls the carrier (`:51`).
- `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentCreationWriter.java:25-36` performs the claim as an insert, and `ShipmentCreationService.java:36-39` catches `DataIntegrityViolationException` as the "already claimed" signal.
- `shipping-service/src/main/java/com/shop/shippingservice/repository/ShipmentCreationTaskRepository.java:23-32` provides a `PESSIMISTIC_WRITE` + `SKIP LOCKED` `findClaimable` for the retry sweeper.

The **shape** of this fix is correct — claim first, side effect second — and it is the right remediation for CONC-01. Two observations about the state of that work at read time, recorded so the next reviewer can finish it:

1. **No Liquibase changeset exists for `shipment_creation_tasks`.** A repo-wide grep for `shipment_creation_tasks` under `shipping-service/src/main/resources/db/` returns nothing, and `shipping-service/src/main/resources/db/changelog/` contains no new file. With `spring.jpa.hibernate.ddl-auto=validate` (R9, `docs/PATTERNS.md:224-235`) the service cannot start against the current schema.
2. **The claim's atomicity depends on a unique constraint on `order_id` that does not yet exist.** `ShipmentCreationService.createForOrder` relies on `DataIntegrityViolationException` being raised by the `shipment_creation_tasks` insert. Without a unique index on `order_id`, no exception is raised, both replicas claim, and both call the carrier — the same defect, relocated. The changeset must add `UNIQUE (order_id)`.

### 5.2 CONC-02 — The checkout saga releases the cart lock before it destroys the cart (HIGH)

`order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java`:

```java
121  CartAndOrder initial = executeInTx(() -> {            // TX-1: lock the cart, create the Order
123      ... cartRepository.findByIdAndUserIdForUpdate(...)  //         SELECT ... FOR UPDATE
134      Order order = orderRepository.save(Order.builder()...);
146  });                                                     // <-- TX-1 commits; the row lock is RELEASED
...
154  pricing = pricingService.calculate(...)                // remote calls, no lock
183  UUID reservationId = stockReservationService.reserve(...)  // remote calls, no lock
...
209  executeInTxWithoutResult(() -> {                       // TX-2: delete the items, mark the cart deleted
212      cartItemRepository.deleteAll(items);
213      cart.markDeleted(userId.toString());
214      cartRepository.save(cart);
216  });
```

The pessimistic cart lock (`order-service/.../repository/CartRepository.java:23-31`) is held **only** for TX-1. The destructive work — reserving stock and consuming the cart — happens in TX-2, long after the lock is gone. `docs/SERVICE-CATALOG.md:240` describes the checkout path as acquiring a pessimistic cart lock; it does, but it does not hold it across the operation the lock exists to protect.

**Interleaving.** `T1` and `T2` both submit checkout for the same cart:

1. `T1` takes the cart lock (`:123`), reads the items, creates `Order₁`, commits (`:146`). Lock released.
2. `T2` takes the cart lock (`:123`) — the cart is still `deleted = false` and still has its items, because `T1` has not reached TX-2 yet — reads the **same** items, creates `Order₂`, commits (`:146`).
3. `T1` reserves stock for its items (`:183`) and `T2` reserves the same stock again. The inventory service's conditional `UPDATE` (`InventoryRepository.java:18-23`) allows both — there is enough stock.
4. `T1` runs TX-2: deletes the items, marks the cart deleted (`:212-214`).
5. `T2` runs TX-2: deletes an empty item set, marks the cart deleted again.

**Outcome:** two orders for one cart, two stock reservations, two payment obligations. The order-level idempotency key (`IdempotencyServiceImpl`) does not help unless the two requests happen to carry the same key.

**Fix.** Hold the cart lock for the whole saga — or, better, move the destructive work into TX-1 and make the remote reservations compensate by reference to the order id. A second guard is needed independently: a unique constraint on `orders(cart_id)` would make the second order fail at the database level even if the lock is lost.

### 5.3 CONC-03 — The cart API is unlocked read-modify-write (HIGH)

`order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java` mutates the cart through `getOrCreateCart` (`:176-189`), which uses the **unlocked** `findByUserIdAndDeletedFalse` (`:177`) — the locking finder is used only by the saga (`OrderCreateSaga.java:123-125`). `Cart` has **no `@Version`** (`order-service/.../entity/Cart.java:15-27`) and `CartItem` has none either.

Concrete lost updates:

- `addItem` (`:49-82`): the quantity is read at `:57` (`existing.getQuantity() + request.quantity()`) and written at `:61-64`; the subtotal is recomputed at `:79` and written at `:80`.
- `mergeCart` (`:133-174`): the guest cart is read unlocked at `:137`, the user cart is read unlocked at `:146`, and the merged quantities are written back at `:151-155` / `:171-172`.

**Interleaving.** Two tabs (or two devices) add one unit of the same product: `T1` reads `quantity = 1` (`:57`), `T2` reads `quantity = 1` (`:57`); `T1` writes `2` (`:61`), `T2` writes `2` (`:61`). The cart shows 2 units when 3 were added. The same applies to the subtotal, which is recomputed from a stale item set at `:79`.

**Fix.** Route every cart mutation through `findByUserIdForUpdate` (`CartRepository.java:23-26`) — the locking finder already exists — and add `@Version` to `Cart` as a second line of defence.

### 5.4 CONC-15 — `confirmOrder` validates the state transition before the lock it needs (HIGH)

`order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:273-329`:

```java
282  Order order = orderRepository.findById(orderId)      // no lock
284  orderStatusService.validateTransition(order.getStatus(), OrderStatus.CONFIRMED);
301  commitCoordinator.commitForConfirm(order, items);     // remote commits — inventory reservations
307  OrderResponse response = transactionTemplate.execute(tx -> {
310      order.setStatus(OrderStatus.CONFIRMED);
311      orderRepository.save(order);                      // @Version-protected
```

`Order` is `@Version`-protected (`Order.java:84`), so the **row** cannot be corrupted: the second writer loses the version check and throws `OptimisticLockException`. But the version check happens at `:311`, **after** `commitForConfirm` (`:301`) has already committed the inventory reservations and the promotion quota over HTTP (`OrderCommitCoordinator.java:46-128`, the parallel commit loop at `:67-111`).

**Interleaving.** `T1` (the Kafka consumer, key `payment-confirm-<paymentId>`) and `T2` (an admin via `OrderStatusController.java:28-29`, key `admin-confirm-1`) confirm the same order with **different** idempotency keys. Both read `PENDING`, both pass `validateTransition`, both call `commitForConfirm` → **the inventory reservations are committed twice** and the promotion quota is claimed twice. Only then does one of them lose the version check. The order row ends up correct; the stock and the promotion budget are not.

The Kafka path is protected only because `PaymentLifecycleConsumer` uses a **stable** key (`"payment-confirm-" + event.getPaymentId()`, `order-service/.../kafka/PaymentLifecycleConsumer.java:34`) — but a *concurrent* `abort` (CONC-16) can delete that completed row and make the key reusable.

**Fix.** Take the order row lock (`SELECT … FOR UPDATE`) or perform a conditional compare-and-set (`UPDATE orders SET status='CONFIRMED' WHERE id=? AND status='PENDING'`) **before** the fan-out, and make the fan-out itself idempotent by keying it on the order id.

### 5.5 CONC-05 — The rating aggregate is computed without a lock (HIGH)

`rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java:40-78` builds the outgoing event from an **unlocked** aggregate query:

```java
42  Object[] aggregate = ratingRepository.findAggregateByProductId(rating.getProductId()).get(0);
43  BigDecimal avgRating = toBigDecimal(aggregate[0]).setScale(2, RoundingMode.HALF_UP);
44  int ratingCount = ((Number) aggregate[1]).intValue();
...
65  payload.put("avgRating", avgRating);
66  payload.put("ratingCount", ratingCount);
```

The query (`rating-service/.../repository/RatingRepository.java:22-24`) is a plain aggregate with no lock, and `Rating` has no `@Version`. Two concurrent rating submissions for the same product both read the same aggregate, and both events carry the **same** `ratingCount`. The downstream consumer (`product-service`'s `ProductRatingService`) applies the event's snapshot, so the product's cached rating ends up one behind permanently — a lost update that no constraint can detect, because both ratings were legitimately inserted.

**Fix.** Compute the aggregate inside the same transaction as the rating insert with a lock (`SELECT … FOR UPDATE` on the product's rating rows), or derive the count from a counter column maintained by a conditional `UPDATE`.

### 5.6 Other TOCTOU and read-then-write sites

| Site | File:line | Defect |
| --- | --- | --- |
| Inventory commit | `inventory-service/.../service/impls/InventoryServiceImpl.java:212-226` | Reads the inventory row, computes `newAvailable`/`newReserved` in Java (`:215-216`), writes them back (`:220-221`). Two concurrent commits of *different* reservations for the same product both read the same row. The `@Version` on `Inventory` (`Inventory.java:47-48`) turns the second write into an `OptimisticLockingFailureException`, which `ReservationServiceImpl.withRetry` (`:66-81`) retries up to 3 times — so this one **is** handled, but only by luck of the version column and the retry wrapper; the read-modify-write itself is unguarded. |
| Inventory release | `InventoryServiceImpl.java:250-262` | Same shape as above. |
| Inventory create | `InventoryServiceImpl.java:71-73` | `existsByProductId` then `save` — a plain check-then-act. The unique constraint on `product_id` (`Inventory.java:32`) arbitrates, but the `DataIntegrityViolationException` is **not** caught, so the loser gets a 500 instead of a 409. R2 requires 409. |
| Idempotency abort | `order-service/.../service/impls/IdempotencyServiceImpl.java:104-116` | See CONC-16. |
| Payment create | `payment-service/.../service/impls/PaymentServiceImpl.java:76-100` | Correct (REQUIRES_NEW insert + unique constraint + re-read). Listed here for completeness as the *reference* pattern. |
| Reindex index-name resolution | `search-service/.../service/impls/ReindexServiceImpl.java:123-139` | `nextIndexName()` reads the index list and computes `max+1`. Two replicas compute the same name. See CONC-13. |
| Bucket creation | `utils/common-storage/.../service/S3ObjectStorageService.java:46-68` | `headBucket` → `createBucket`. See CONC-25. |
| Payment capture | `payment-service/.../service/impls/PaymentServiceImpl.java:124-130` | Reads the payment, checks `status != PENDING` (`:125-127`), then calls the provider (`:129-130`). Two concurrent captures both pass the check and both call the PSP. Stripe dedupes on the idempotency key; the `MockProvider` (`payment-service/.../provider/MockProvider.java:18-21`) does not. See CONC-04. |

---

## 6. Locks, timeouts and deadlock avoidance

**In-process vs distributed locks.** The fleet has **no distributed lock implementation of any kind**. A repo-wide case-insensitive grep for `shedlock` across `*.java`, `*.xml` and `*.yml` returns zero hits. There is no Redis lock (`SET NX PX`), no PostgreSQL advisory lock (`pg_try_advisory_lock`), no ZooKeeper/etcd lease. Every "distributed lock" in this platform is a **conditional `UPDATE` or a conditional `INSERT`** in the database — which is the right mechanism and is used correctly in 13 places (§4).

The in-process locks that do exist are all **per-JVM and therefore per-replica**:

| Lock | File:line | Scope | Multi-instance effect |
| --- | --- | --- | --- |
| `AtomicBoolean reindexInProgress` | `search-service/.../service/impls/ReindexServiceImpl.java:37` | one replica | **None** — every replica reindexes (CONC-13) |
| `synchronized (committed)` | `order-service/.../service/OrderCommitCoordinator.java:103-106` | one replica | None needed — it guards a local list |
| `MemoizedCount` double-checked lock | `order-service/.../service/OrderReconciliationScheduler.java:198-217` | one replica | None needed — a local memo |
| `ReentrantLock refreshLock` | `utils/common-keycloak/.../client/KeycloakServiceTokenProvider.java:53` | one replica | **None** — N replicas each refresh the service token independently, so a Keycloak token storm at startup is proportional to N |
| `synchronized (this)` | `utils/common-keycloak/.../client/KeycloakAdminClient.java:328` | one replica | None — a local token cache |
| `AtomicReference` decoder cache | `utils/common-security/.../config/LazyJwtDecoder.java:18-42`; `gateway-service/.../config/LazyReactiveJwtDecoder.java:19-41` | one replica | None — correct double-checked initialisation |

**Lock ordering.** There is no deadlock risk from lock ordering, because the fleet never holds two database locks at once in a conflicting order. The nested-lock sites are:

- `OrderCreateSaga.execute` — the cart lock (`:123`) is taken and **released** in TX-1 before any other lock is taken.
- `IdempotencyServiceImpl.begin` — the `PROPAGATION_REQUIRES_NEW` template (`:44-46`) means the idempotency-key insert **commits before** the caller's transaction begins. This is the classic deadlock-avoidance shape: the guard is taken *first and alone*, then released.
- `ShipmentWriter.saveDelivered` (`shipping-service/.../service/ShipmentWriter.java:27-32`) — the shipment row lock and the outbox insert are in one transaction, in that order.

The one place a **lock-order inversion is conceivable** is `OrderCommitCoordinator.commitForConfirm` (`order-service/.../service/OrderCommitCoordinator.java:67-111`): it fans out N concurrent HTTP commits and then compensates in reverse order (`:130-142`). Two concurrent confirms of the same order would interleave the commits and the compensations. The compensation is recorded in the ledger on failure (`compensationFailureService.record`, `:137`), so the outcome converges — but the ledger is keyed per reservation, and a double commit followed by a double compensation nets to zero only if both sides run.

**Timeouts.**

| Timeout | Value | File:line | Behaviour on expiry |
| --- | --- | --- | --- |
| Cart pessimistic lock | 3000 ms | `order-service/.../repository/CartRepository.java:24`, `:29` | `jakarta.persistence.lock.timeout = 3000` → `PessimisticLockException` after 3 s. A second concurrent checkout gets a 500-style failure, not a clean 409. |
| Outbox claim lock | `-2` (`SKIP LOCKED`) | `shipping-service/.../outbox/OutboxEventRepository.java:23` | Never blocks — the row is skipped and another row is taken. Correct for a work queue. |
| Notification retry lock | default (blocks) | `notification-service/.../repository/NotificationRepository.java:29` | No timeout hint → the default (`-1`, wait forever) applies. A stuck holder blocks the sweeper. |
| Outbox publish | 5000 ms per module | `shipping-service/.../outbox/ShippingOutboxRelay.java:39-40` | `publishBatch` awaits a latch for the whole batch (`:94-95`); on timeout the un-completed records are marked as failures and retried. |
| Producer publish | 10 000 ms default | `utils/common-kafka/.../producer/KafkaMessagePublisher.java:30`, `:49-51` | `KafkaPublishException`. |
| DLT replay publish | **none** | `utils/common-kafka/.../replay/DeadLetterReplayService.java:101` | **Blocks forever** — CONC-22. |
| Circuit breaker | 5 s timeout, 3 retries with exponential backoff | `utils/common-spring/src/main/resources/application.yml:153-176` | `resilience4j.timelimiter` = 5 s, `retry.max-attempts` = 3. |

**Behaviour when a lock holder dies.**

- **Database locks:** released immediately by PostgreSQL when the backend connection drops. The row becomes available again. No leak. The only consequence is that the work the dead holder was doing is lost — which is why every critical path needs either a claim lease or a compensating sweep.
- **Claim leases:** the outbox lease (`claim-lease-ms:60000`) and the compensation lease (`FailedCompensationRepository.claimForProcessing`, `order-service/.../repository/FailedCompensationRepository.java:27-35`) both work: the sweeper reclaims the row after the lease. **But** the lease is measured against **instance-local wall-clock time** (`Instant.now()`, e.g. `ShippingOutboxRelay.java:123`), so a replica whose clock is behind will not reclaim a row until its own clock catches up, and a replica whose clock is ahead will reclaim a row that is still being worked on. See CONC-19.
- **The `synchronized` / `AtomicBoolean` guards:** a dead replica releases them instantly, but they protect only that replica's state — so a dead replica's "lock" protects nothing fleet-wide. This is exactly why `ReindexServiceImpl`'s `AtomicBoolean` (CONC-13) and the eight unclaimed schedulers (CONC-06, CONC-07) are unsafe.

---

## 7. Thread pools and executors

**Virtual threads.** `utils/common-spring/src/main/resources/application.yml:9-11`:

```yaml
  threads:
    virtual:
      enabled: ${VIRTUAL_THREADS_ENABLED:false}
```

**Virtual threads are OFF by default.** Every service that inherits this file runs on **platform threads** with Tomcat's default `max-threads=200`. The flag is available, but the platform's concurrency model is the pre-Java-21 one. Any code that assumes "one thread per request is cheap" is wrong today.

**Unbounded queues.** The fleet creates three thread pools, and all three use `Executors.newFixedThreadPool`:

| Pool | File:line | Threads | Queue |
| --- | --- | --- | --- |
| `SNAPSHOT_EXECUTOR` | `order-service/.../service/impls/PricingServiceImpl.java:39-46` | `max(2, cpus*2)` | **`LinkedBlockingQueue` — unbounded** |
| `COMMIT_EXECUTOR` | `order-service/.../service/OrderCommitCoordinator.java:37-44` | `max(2, cpus*2)` | **unbounded** |
| `RECONCILE_EXECUTOR` | `order-service/.../service/OrderReconciliationScheduler.java:47-54` | `max(2, cpus*2)` | **unbounded** |

`Executors.newFixedThreadPool` is documented to use an **unbounded** `LinkedBlockingQueue`. Each pool is fed one task per cart line (`PricingServiceImpl.java:56-73`), per order line (`OrderCommitCoordinator.java:67-110`) or per stuck order (`OrderReconciliationScheduler.java:90-100`). Under a checkout burst, the queue depth is bounded by *concurrency × cart size* and grows without limit — a memory-growth path, not a backpressure path. A `ThreadPoolExecutor` with a bounded queue and a `CallerRunsPolicy` would apply backpressure instead.

All three pools are `static final`, so they are **per-JVM** (per replica, not fleet-wide), and all three threads are daemons (`:44`, `:42`, `:53`), so they never block JVM shutdown. None of them is ever shut down explicitly — acceptable for daemon pools, but it means the pools are invisible to the container's shutdown grace period (`shutdown.grace-period: 30s`, `utils/common-spring/src/main/resources/application.yml:139`).

**Blocking calls on event-loop threads.** The gateway is reactive (Spring Cloud Gateway + WebFlux). Its Redis calls are reactive (`ReactiveStringRedisTemplate`, `RedisEdgeRateLimiter.java:6`) and non-blocking — ✅. But `gateway-service/.../filter/AdminIpAllowlistFilter.java:92` and `.../filter/TrustedProxyPolicy.java:48`, `:65` call `InetAddress.getByName(canonical.get())`. `getByName` performs a DNS lookup when the argument is not a literal address. `LiteralIpAddress` and `TrustedProxyPolicy` appear to pre-validate that the value is literal (R15's "A value that enters a rate-limit key is validated as a literal address"), so the lookup should resolve locally — but **the call is on the Reactor event loop**, and any hostname that reaches it would block the loop for the resolver's timeout. This is a latency hazard on the event loop rather than a correctness one; it is called out because R15 explicitly forbids "a request-supplied value [that] may trigger a name resolution".

**`send().get()` on Kafka producers.** Two producer blocks exist:

- `KafkaMessagePublisher.publish` → `future.get(publishTimeoutMs, MILLISECONDS)` (`utils/common-kafka/.../producer/KafkaMessagePublisher.java:56-66`) — **bounded**, but it is called from `PaymentWriter`/`ShippingEventPublisher` paths inside request threads. The 10 s default can outlast a request timeout, tying up a platform thread.
- `KafkaMessagePublisher.publishBatch` → `latch.await(timeout, unit)` (`:114-120`) — **bounded** (5 s in the relays).
- `DeadLetterReplayService` → `template.send(...).get()` (`utils/common-kafka/.../replay/DeadLetterReplayService.java:101`) — **unbounded**. CONC-22.

There is **no `send().get()` on an event-loop thread** — the outbox relays run on Spring's scheduler thread pool, and the gateway never publishes to Kafka. ✅

**Other blocking-in-request-path sites.**

- `ProductMediaSweepJob`/`MediaDeletedConsumer.clearWithBoundedRetry` sleeps on the listener thread: `Thread.sleep(BACKOFF_MILLIS)` (`product-service/.../kafka/MediaDeletedConsumer.java:72`) inside a 3-attempt loop (`:52-67`). It holds a Kafka consumer thread for up to 600 ms per record. Acceptable, but it is a consumer-thread sleep.
- `KeycloakServiceTokenProvider` sleeps with backoff on token acquisition (`utils/common-keycloak/.../client/KeycloakServiceTokenProvider.java:178`) — on the calling request thread.
- `ReservationServiceImpl.withRetry` sleeps between optimistic-lock retries (`inventory-service/.../service/impls/ReservationServiceImpl.java:85`) — on the calling request thread.

**Bounded audit writer.** `utils/common-logging/.../audit/BoundedAsyncAuditEventWriter.java` is the one place the fleet gets this right: it uses a **bounded** queue with `synchronized accept` (`:148`) and counts discarded events (`:114`). ✅

**Missing `@Async` / task-executor configuration.** There is **no** `@Async` usage anywhere in the repository and no `TaskExecutor` bean is declared. All fan-out is done with the three hand-rolled pools above.

---

## 8. Clock and ordering hazards

**`Instant.now()` vs an injected `Clock`.** The fleet is overwhelmingly on the wall clock:

| Metric | Value |
| --- | --- |
| Production files calling `Instant.now()` | **54** |
| `Instant.now()` call sites in production code | **128** |
| Production classes with an injected `java.time.Clock` | **8** |

The eight that do inject a `Clock` are: `notification-service/.../NotificationDeliveryService.java`, `payment-service/.../outbox/OutboxRetentionScheduler.java`, `promotion-service/.../CampaignReservationServiceImpl.java`, `shipping-service/.../scheduler/ReconciliationScheduler.java`, `shipping-service/.../scheduler/ShipmentCreationRetryScheduler.java`, `shipping-service/.../service/ShipmentCreationService.java`, `shipping-service/.../service/impls/ShipmentServiceImpl.java`, `shipping-service/.../service/impls/WebhookEventServiceImpl.java`.

**None of the 8 outbox relays injects a `Clock`** — all of them call `Instant.now()` for the lease stamp and the cutoff, e.g. `shipping-service/.../outbox/ShippingOutboxRelay.java:59`, `:72`, `:123`, `:141`.

**Where this bites.**

1. **Claim leases are compared across replicas using instance-local clocks.** The relay stamps `claimedAt = Instant.now()` (`:72`) and another replica reclaims with `cutoff = Instant.now() - claimLeaseMs` (`:123`). If replica B's clock is **ahead** of replica A's by more than the 60 s lease, B reclaims A's row *while A is still publishing it* → A's publish and B's publish both succeed → **the same event is published twice**. If B's clock is **behind**, a genuinely stranded row is not reclaimed for longer than intended.
2. **Expiry sweeps compare a local clock against a stored timestamp.** `inventory-service/.../service/ReservationCleanupScheduler.java:51` uses `Instant.now()` to select expired reservations; `promotion-service/.../service/ReservationCleanupScheduler.java:59`, `:63` does the same. A skewed replica expires reservations early or late. The inventory sweep is protected by `claimForExpiry` (a conditional `UPDATE`), so the damage is bounded to "expired slightly early" — the reservations are genuinely near expiry.
3. **Event payloads embed the producer's wall clock.** `rating-service/.../service/RatingEventService.java:56` writes `payload.put("occurredAt", Instant.now().toString())`; the search projection derives its Elasticsearch external version from that timestamp (`search-service/.../search/ProductEventVersion.java:16-19`, `:34-41`). A producer whose clock is ahead therefore stamps a version that **blocks every later, correctly-timestamped event** for that document. The symptom is a permanently frozen search projection for one product — an unbounded, self-inflicted denial of freshness.
4. **Ordering by mutable timestamps.** `ProductEventVersion` orders by `occurredAt` with a fallback to `updatedAt` (`:17-18`). `updatedAt` is a **mutable** column: it changes when the row changes, so it is not a stable event-ordering key. Any event that carries `occurredAt` is fine; any event that falls back to `updatedAt` is ordered by a value that may have been rewritten after the fact.
5. **Event time vs processing time.** Nothing in the fleet records a processing-time stamp, so there is no way to measure or alert on event lag. The order reconciliation sweeper uses `orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff)` (`order-service/.../service/OrderReconciliationScheduler.java:83-85`) with `cutoff = Instant.now() - stuckMinutes` (`:183-185`) — a **created-at** comparison, so a slow-but-healthy order can be cancelled by the sweeper while it is still being processed. With N replicas running the same sweep, that cancellation is attempted N times (CONC-06).
6. **The one place the fleet gets it right:** the Redis edge limiter uses `redis.call('TIME')` (`gateway-service/.../ratelimit/RedisEdgeRateLimiter.java:23`), so the rate-limit bucket is immune to replica clock skew.

---

## 9. Findings

| id | severity | concurrency defect | file:line | interleaving or failure scenario | recommended fix |
| --- | --- | --- | --- | --- | --- |
| CONC-01 | **CRITICAL** | Carrier side effect performed **before** the idempotency guard; the unique index that arbitrates fires only after the parcel exists | `shipping-service/.../service/impls/ShipmentServiceImpl.java:78-102` (guard `:80`, carrier call `:92`, insert `:98`); arbiter `shipping-service/.../changelog-001-shipments.yaml:40` | Replica A passes `existsByOrderId` (`:80`), blocks in the carrier call (`:92`); a group rebalance hands the record to replica B, which also passes the guard and calls the carrier; A inserts, B's insert violates `uk_shipment_order_live` and is swallowed at `:99-101` → **two parcels, one row, one tracking number** | Insert the shipment row (state `carrier_requested`) first, inside a transaction, so the unique index arbitrates *before* the carrier call; move the carrier call into a leased retry worker; keep `Idempotency-Key: orderId` on the carrier request as the second line of defence |
| CONC-02 | HIGH | The cart's pessimistic lock is released before the saga's destructive work | `order-service/.../service/impls/OrderCreateSaga.java:121-146` (lock, TX-1) vs `:180-216` (reserve + cart deletion, TX-2) | `T1` locks the cart, creates `Order₁`, commits (lock released); `T2` locks the still-live cart, creates `Order₂`; both reserve stock → two orders, two reservations | Hold the cart lock for the whole saga, or move the destructive work into TX-1; add `UNIQUE (cart_id)` on `orders` |
| CONC-03 | HIGH | Cart API performs unlocked read-modify-write on entities with no `@Version` | `order-service/.../service/impls/CartServiceImpl.java:50-82`, `:146-172`, `:176-189`; `order-service/.../entity/Cart.java:15-27` | Two concurrent `addItem` calls read `quantity=1`, both write `2` → one unit lost; the subtotal (`:79-80`) is recomputed from a stale item set | Use `findByUserIdForUpdate` (`CartRepository.java:23-26`) on every mutation path; add `@Version` to `Cart` |
| CONC-04 | HIGH | The provider call runs inside the database transaction | `payment-service/.../service/impls/PaymentServiceImpl.java:121-137` (capture, provider call `:129-130`), `:158-174` (refund) | Two concurrent captures both read `PENDING` (`:125`) and both call the PSP; the DB transaction and a pooled connection are held open for the PSP's full latency | Move the provider call outside the transaction; apply the outcome with a conditional `UPDATE … WHERE status='PENDING'`; keep the provider's idempotency key |
| CONC-05 | HIGH | The rating aggregate snapshot is computed from an unlocked query | `rating-service/.../service/RatingEventService.java:42-44`, `:65-66`; `rating-service/.../repository/RatingRepository.java:22-24` | Two concurrent ratings both read the same aggregate → both events carry the same `ratingCount` → the product's cached rating is permanently one behind | Compute the aggregate under a lock inside the rating transaction, or maintain a counter column with a conditional `UPDATE` |
| CONC-06 | HIGH | The order reconciliation sweeper has **no claim**; every replica sweeps the same rows | `order-service/.../service/OrderReconciliationScheduler.java:80-102` (select), `:149-175` (auto-confirm / auto-cancel) | All N replicas select the same stuck `PENDING` orders and each writes `CONFIRMED`/`CANCELLED` and publishes a lifecycle event → N duplicate `order.*` events; the write bypasses `OrderStatusServiceImpl.validateTransition` entirely | Copy the compensation claim (`order-service/.../repository/FailedCompensationRepository.java:27-35`): conditional `UPDATE … WHERE id=? AND status IN …` with a lease, plus a `claimed_at` column |
| CONC-07 | HIGH | The webhook retry loops have **no claim**, and the payment FSM allows a self-transition | `payment-service/.../scheduler/WebhookRetryScheduler.java:44-57`; `shipping-service/.../scheduler/WebhookRetryScheduler.java:44-57`; `payment-service/.../service/PaymentStateMachine.java:25-27` | All N replicas select the same `FAILED_RETRYABLE` event and replay it concurrently; `CAPTURED → CAPTURED` is accepted, so `WebhookEventService.java:167-179` publishes a **second** `payment.captured.v1` → the order is confirmed twice | Claim the event before replaying (conditional `UPDATE … WHERE status='FAILED_RETRYABLE'` + lease); remove the self-transition from `PaymentStateMachine` |
| CONC-08 | HIGH | Consumer commit semantics are never pinned; `AckMode` defaults to `BATCH` with `max.poll.records=500` | `utils/common-kafka/.../consumer/BaseKafkaListenerConfig.java:50-60` (no `setAckMode`); `utils/common-kafka/.../config/KafkaProperties.java:80`, `:82-83`; rule R18 `docs/PATTERNS.md:415-439` | A rebalance replays up to 500 uncommitted records; a handler that blocks (carrier call, PSP call) can exceed `max.poll.interval.ms` and be evicted **while still running** — the "zombie consumer" that makes CONC-01 reachable | Call `factory.getContainerProperties().setAckMode(RECORD)`; reduce `max.poll.records`; move long external calls out of the listener thread; assert `AckMode` in a test |
| CONC-09 | HIGH | One listener factory has no dead-letter recoverer | `product-service/.../kafka/MediaLifecycleListenerConfig.java:18-28` (no `setCommonErrorHandler`) | A media event that fails is retried by the framework default and then **logged and skipped** — no DLT record, no replay path, silent data loss | Extend `BaseKafkaListenerConfig` (as the other 6 factories do) so the `DeadLetterPublishingRecoverer` is installed |
| CONC-10 | MEDIUM | The outbox publishes **outside** the claim transaction (R12 deviation), opening a duplicate-publish window | `shipping-service/.../outbox/ShippingOutboxRelay.java:65-117`; rule R12 `docs/PATTERNS.md:269-276`; identical in all 8 relays | The relay publishes (`:86-104`) and dies before `markFinalState` (`:112-117`); the lease expires (`:122-132`), another replica reclaims the row to `PENDING`, claims and publishes it again → **the same event is published twice** | Publish inside the claim transaction as R12 requires, or accept at-least-once explicitly and make every consumer idempotent (audit the consumer side, not just the producer side) |
| CONC-11 | MEDIUM | `payment-service` and `product-service` have no `claimed_at` column; the lease is proxied by `updated_at` | `payment-service/.../outbox/OutboxEventRepository.java:43-54`; `product-service/.../repository/OutboxEventRepository.java:35-46`; compare `shipping-service/.../changelog/006-shipping-outbox-claim-lease.yaml:7-8` | `updated_at` is bumped by any write to the row, not only by a claim, so the "lease" measures something else; a row stranded in `SENDING` is reclaimed on a schedule that has nothing to do with the claim | Add the `claimed_at` column and stamp it on claim, as the other 6 modules do |
| CONC-12 | MEDIUM | `claimOnePending`'s `MIN(id)` subquery is evaluated outside the row lock | `shipping-service/.../outbox/OutboxEventRepository.java:24-26`; `product-service/.../repository/OutboxEventRepository.java:24-26` | Replica A holds the lock on the current `MIN(id)`; replica B's `FOR UPDATE SKIP LOCKED` skips it → B's claim returns **empty** → B's loop breaks (`:77-79`) → B idles although other `PENDING` rows are free | Select a batch of claimable rows directly (`WHERE status = 'PENDING' … FOR UPDATE SKIP LOCKED`) instead of resolving a single `MIN(id)` |
| CONC-13 | MEDIUM | The reindex is guarded only by an in-process `AtomicBoolean` | `search-service/.../service/impls/ReindexServiceImpl.java:37`, `:41-43`, `:123-139`, `:163-173`, `:175-187` | All N replicas reindex concurrently; they compute the same `products-vN+1` (`:123-139`); replica A's `deleteSupersededIndices` (`:175-187`) deletes replica B's freshly-created and possibly-aliased index → **the alias can point at a deleted index** | Guard the reindex with a database-backed claim (or a `products-v` index creation that fails fast on the duplicate name); make the cleanup delete only indices older than the one just aliased |
| CONC-14 | MEDIUM | Elasticsearch writes are unversioned when the event carries no parseable timestamp | `search-service/.../service/ProductSearchService.java:38-44`, `:56-62` | `ProductEventVersion.forEvent` returns `null` (`search-service/.../search/ProductEventVersion.java:16-19`) → the write goes out with no version precondition → an out-of-order event overwrites a newer document | Make the version mandatory (reject the event without one) or use an internal ES version with a monotonic sequence from the source of truth |
| CONC-15 | HIGH | `confirmOrder` validates the transition and performs the remote commits **before** the version-protected write | `order-service/.../service/impls/OrderServiceImpl.java:282`, `:284`, `:301`, `:307-315`; `order-service/.../service/OrderCommitCoordinator.java:46-128` | Two confirms with **different** idempotency keys both read `PENDING`, both pass `validateTransition`, both call `commitForConfirm` → the inventory reservations and the promotion quota are committed twice; only the final `save` (`:311`) loses the version check | Take a row lock or a compare-and-set on `orders` **before** the fan-out; key the fan-out on the order id so it is idempotent |
| CONC-16 | MEDIUM | The idempotency `abort` path is a TOCTOU delete | `order-service/.../service/impls/IdempotencyServiceImpl.java:104-116` | `abort` reads the row filtered on `response_status = 0` (`:109-112`), then deletes by id (`:112`); a concurrent `complete` (`:93-102`) that lands between the read and the delete has its completed response deleted → the key becomes reusable → a **duplicate order** | Replace the read-then-delete with a conditional `DELETE … WHERE actor=? AND key=? AND response_status = 0`, or a conditional `UPDATE … SET status='ABORTED' WHERE response_status = 0` |
| CONC-17 | MEDIUM | The inventory idempotency claim is a read-then-insert | `inventory-service/.../service/impls/InventoryServiceImpl.java:140-145`, `:147-155` | Two concurrent reserves with the same key both miss the check and both insert → the loser gets `DataIntegrityViolationException`; `ReservationServiceImpl.java:38-49` converts it into a replay **by re-running the reserve**, which re-enters the same race | Use an `INSERT … ON CONFLICT (idempotency_key) DO NOTHING RETURNING` and re-read the winner in one statement |
| CONC-18 | MEDIUM | The checkout path never sends an `Idempotency-Key` to inventory, so the reservation is not idempotent | `order-service/.../client/InventoryServiceClient.java:40-56` (no header); `order-service/.../service/impls/OrderCreateSaga.java:183-185`; `inventory-service/.../changelog-007-reservations-idempotency-key.yaml:12-15` | A retried or timed-out reserve creates a **second** reservation and reserves the stock twice; `uk_reservations_idempotency_key` is never exercised because the key is `NULL` | Send a deterministic key (e.g. `orderId:productId`) from `InventoryServiceClient.reserve` and forward it to `InventoryService.reserve(productId, request, key)` |
| CONC-19 | MEDIUM | The fleet runs on wall-clock time: 128 `Instant.now()` call sites in 54 files vs 8 injected-`Clock` classes | `utils/common-kafka/.../outbox/ShippingOutboxRelay.java:59`, `:72`, `:123`, `:141`; `inventory-service/.../service/ReservationCleanupScheduler.java:51`; `promotion-service/.../service/ReservationCleanupScheduler.java:59` | A replica whose clock is ahead reclaims another replica's live claim (60 s lease) → double publish; a replica behind never reclaims a stranded row | Inject `Clock` everywhere a timestamp is used for a **decision**; for cross-replica leases use a database timestamp (`CURRENT_TIMESTAMP`) instead of the application clock |
| CONC-20 | MEDIUM | Three `Executors.newFixedThreadPool` pools with **unbounded** queues, never shut down | `order-service/.../service/impls/PricingServiceImpl.java:39-46`; `order-service/.../service/OrderCommitCoordinator.java:37-44`; `order-service/.../service/OrderReconciliationScheduler.java:47-54` | One task is queued per cart/order line; under a checkout burst the queue grows without limit → heap growth with no backpressure | Use `ThreadPoolExecutor` with a bounded queue and `CallerRunsPolicy`; register the pools for shutdown |
| CONC-21 | MEDIUM | Virtual threads are disabled by default while the code assumes blocking is cheap | `utils/common-spring/src/main/resources/application.yml:9-11` | Every service runs on Tomcat's default 200 platform threads; a slow external call (payment, carrier, Keycloak) consumes a platform thread for its full latency | Either enable `VIRTUAL_THREADS_ENABLED` deliberately and re-test the blocking paths, or size the platform pool deliberately; do not leave the default implicit |
| CONC-22 | LOW | Unbounded blocking producer call in the DLT replay path | `utils/common-kafka/.../replay/DeadLetterReplayService.java:101` | `template.send(...).get()` with no timeout → the replay thread blocks indefinitely on a stuck broker | Use `get(timeout, unit)` and treat a timeout as a replay failure (the surrounding `catch` already handles failures) |
| CONC-23 | LOW | Object storage is written before the row that records it; the compensating purge is best-effort | `media-service/.../service/impls/MediaUploadServiceImpl.java:122-137`, `:149-172` | The dedupe race is correctly arbitrated by `uk_medias_sha256` and the loser purges its own (UUID-scoped) keys — but a failed purge leaks orphaned objects silently | Make `purgeOrphans` durable (record the orphaned keys and retry them in a sweep) |
| CONC-24 | LOW | The edge rate limiter's two keys share no hash tag; the bucket has no eviction protection | `gateway-service/.../ratelimit/RedisEdgeRateLimiter.java:65-67`, `:27`, `:40-44`; `docker-compose.yml:51-67` | On a clustered Redis the two-key script fails with `CROSSSLOT` and the failure is swallowed into the outage policy (`:75-78`); under an LRU eviction policy an evicted `:tokens` key resets the bucket to full capacity (`:27`) | Use a hash tag (`{scope:ip}`) so both keys share a slot; set an explicit `maxmemory-policy` that cannot evict rate-limit keys |
| CONC-25 | LOW | `ensureBucketExists` is a check-then-act race | `utils/common-storage/.../service/S3ObjectStorageService.java:46-59`, `:61-68` | Two replicas starting together both see "no bucket" and both call `createBucket`; the loser gets `BucketAlreadyOwnedByYou`, mapped to `StorageException` (`:65-67`) → a startup failure | Treat `BucketAlreadyExists`/`BucketAlreadyOwnedByYou` as success |
| CONC-26 | LOW | The notification retry claim blocks instead of skipping | `notification-service/.../repository/NotificationRepository.java:29-39` | A replica that finds the row locked by another replica **blocks** on it (no `lock.timeout`), stalling the sweeper instead of skipping | Add `jakarta.persistence.lock.timeout = -2` so the claim behaves like the outbox claims |
| CONC-27 | LOW | The Elasticsearch external version is derived from event time at nanosecond granularity | `search-service/.../search/ProductEventVersion.java:34-41`; `search-service/.../service/ProductSearchService.java:71-75` | Two product updates within the same nanosecond produce the same version; ES `external` versioning requires a strictly greater version, so the second event is silently rejected as a conflict | Use a monotonic per-aggregate sequence (the outbox row id) as the version, not a timestamp |

---

## 10. Per-resource verdict table

| Resource | Safe under N instances? | Blocking defects | Correct primitives in place | Net verdict |
| --- | --- | --- | --- | --- |
| **PostgreSQL** | ⚠️ Conditionally | CONC-01, 02, 03, 05, 11, 12, 15, 16, 17, 18 | `SKIP LOCKED` outbox claims (`shipping-service/.../outbox/OutboxEventRepository.java:22-26`); conditional `UPDATE` stock reservation (`inventory-service/.../repository/InventoryRepository.java:18-23`); conditional quota updates (`promotion-service/.../repository/CampaignRepository.java:26-31`); 10 unique constraints as final arbiters; `@Version` on 6 entities | **The row layer is sound; the side-effect layer is not.** One row-level defect class (unlocked read-modify-write) and one ordering defect class (side effect before guard) dominate. |
| **Redis** | ✅ Yes | CONC-24, CONC-25 (storage-side) | Lua token bucket (`gateway-service/.../ratelimit/RedisEdgeRateLimiter.java:19-47`); server-side clock (`:23`); fail-closed outage policy (`gateway-service/src/main/resources/application.yml:70`); standalone deployment (`docker-compose.yml:51-67`) | **Safe.** The only hazards are capacity (eviction) and a future move to Redis Cluster. |
| **Kafka** | ⚠️ Only with idempotent consumers | CONC-01, 07, 08, 09, 10, 11, 12 | Idempotent producer (`KafkaProperties.java:66-68`); `acks=all`; `enable.auto.commit=false`; per-listener `groupId` enforced by a test (`KafkaListenerGroupIdContractTest.java:23-50`); DLT recoverer on 6 of 7 factories; `SKIP LOCKED` outbox claims | **Producer: strong. Consumer: unpolished.** The rebalance model converts every missing consumer-side guard into a duplicate external side effect. |
| **Elasticsearch** | ⚠️ Conditionally | CONC-13, 14, 27 | External versioning with 409-as-stale (`search-service/.../service/ProductSearchService.java:65-70`, `:71-98`); alias swap that aborts before swapping on failure (`ReindexServiceImpl.java:117-120`) | **Safe with a single writer.** The fleet does not guarantee a single writer. |
| **Filesystem / object storage** | ✅ Yes | CONC-23, CONC-25 | UUID-scoped object keys (`media-service/.../service/impls/MediaUploadServiceImpl.java:109`, `:112`); `uk_medias_sha256` arbitrates the dedupe race and the loser purges only its own keys (`:160-166`); per-instance temp dir only (`media-service/.../config/UploadLimitsConfig.java:18`) | **Safe.** The write-before-row ordering is compensated correctly. |

---

## 11. What this audit could not verify

| Limitation | Consequence |
| --- | --- |
| **No Maven build and no test run.** The instructions forbade a full build because other agents were building concurrently. | Every statement about Hibernate behaviour (`lock.timeout = -2` → `SKIP LOCKED`, `@Version` → `OptimisticLockException`, JPQL `MIN(id)` subquery evaluation order) is derived from the declared intent and the module's own integration tests (`ShippingOutboxClaimConcurrencyIT`, `InventoryOutboxClaimConcurrencyTest`, `PaymentOutboxClaimConcurrencyIT`, `ProductOutboxClaimConcurrencyIT`, `PromotionOutboxClaimConcurrencyIT`, `RatingOutboxClaimConcurrencyIT`), not from an execution performed by this audit. |
| **No load test, no chaos test, no multi-instance run.** | Every "safe under N instances" verdict is **analytic**, derived from the locking, constraint and claim primitives that were read. It is not an observed behaviour. The conclusions in §3 and §10 should be re-validated with a two-replica run once the fleet is buildable. |
| **The container orchestration was not inspected for replica counts.** `docker-compose.yml` was read for the Redis deployment mode only. | The number of replicas each service actually runs in each environment is unknown to this audit; findings that require N ≥ 2 (all of the "no claim" findings) are latent in a single-replica deployment and become live at two. |
| **Spring Cloud Gateway's `RedisRateLimiter` internals are not in this repository.** | The framework limiter is characterised only by its construction (`gateway-service/.../ratelimit/RateLimitConfiguration.java:16-31`). The hand-written edge limiter was read directly and is characterised from source. |
| **Redis Cluster `CROSSSLOT` is asserted only for the edge limiter.** | The framework limiter's key layout is not in the repository. The deployed Redis is standalone (`docker-compose.yml:51-67`), so the concern is prospective. |
| **Elasticsearch internals are not in this repository.** | The claim that `VersionType.External` rejects a non-increasing version is documented Elasticsearch behaviour; the in-repo evidence is that the client *sends* `.version(version).versionType(VersionType.External)` (`search-service/.../service/ProductSearchService.java:65-70`). |
| **Carrier and PSP idempotency semantics are external.** | Whether the carrier honours `Idempotency-Key: orderId` (`shipping-service/.../carrier/CarrierShipmentClient.java:33`) cannot be verified here. CONC-01's severity is stated for the case where it does **not**; if it does, the duplicate is one wasted API call plus one duplicate parcel *request*. |
| **`shipping-service/**`, `rating-service/**` and `utils/**` were being edited concurrently.** | Line numbers in those trees are the ones observed at read time. The CONC-01 citations are against the **committed** revision `59ae0c05` (`git show HEAD:…`), which is what the sibling `design-pattern-adoption.md` also cites; the in-flight remediation is described separately in §5.1.1. |
| **The `production-audit/runs/run-20261118-a2b6e6fc/source-reviews/*.md` receipts were used as a lead only.** | Every claim in §3–§9 was re-verified against the current source; where the receipt and the current source disagreed, the **current source** was used. |
