# Search projection and promotion quota — CAT-01 and CAT-07

Scope: `search-service/`, `promotion-service/`. Both findings were reproduced against the
baseline revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f` and fixed in source.

---

## CAT-01 — an out-of-order product event overwrote newer indexed state

### The defect

`search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java`
applied every product event to the index unconditionally. The consumer payload carries no
sequence field, so an event that described an older state of a product could be applied
after a newer one and roll the document back. The audit confirmed that the consumer
payload has no version or sequence field that could order the events.

### The fix

**The ordering value exists in the event.** `ProductLifecycleEvent.occurredAt` is
populated by the producer with `Instant.now().toString()` at publish time
(`product-service/.../TransactionalProductEventPublisher.java`, and the same pattern in
the category and brand publishers). `ProductEventVersion` converts that instant into a
monotonic long of nanoseconds since the epoch:

- `search-service/src/main/java/com/shop/searchservice/search/ProductEventVersion.java`
  converts `occurredAt`, falling back to `updatedAt`, and returns `null` when neither
  carries a usable value.

**The index enforces the ordering.** `ProductSearchService.write` and `delete` now pass
the derived version to Elasticsearch with `version_type=external`:

- `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java`

Elasticsearch rejects a write whose version is not strictly greater than the stored one
with HTTP 409. A stale event is therefore never applied, and a redelivered event with the
same version is a no-op. The reindex path writes the same external version, so a
reindexed document and the event stream share one version space.

**The 409 is a rejection, not a failure.** The service builds its client over
`RestClientTransport` (`search-service/.../ElasticsearchConfig.java:42`), the low-level
REST transport. That transport surfaces every HTTP error status as
`org.elasticsearch.client.ResponseException extends IOException`, never as
`co.elastic.clients.elasticsearch._types.ElasticsearchException`. A `catch` on
`ElasticsearchException` alone is unreachable dead code for a 409, and the conflict was
escaping as `BusinessException(SEARCH_INDEX_FAILED)` into the Kafka listener. The service
now handles `ResponseException` explicitly and reads the status from the response, so a
stale event is logged and ignored while every other error still fails the index write.
`org.elasticsearch.client:elasticsearch-rest-client` is declared explicitly in
`search-service/pom.xml` because the class is now referenced directly.

### The tests

- `search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchMonotonicProjectionIT.java`
  - an older event arriving after a newer one does not change the indexed document;
  - a redelivered event with the same version leaves the index unchanged;
  - the index still reflects the newer event.
- `search-service/src/test/java/com/shop/searchservice/service/impls/ReindexIT.java`
  - `reindexedDocumentKeepsMonotonicProjection` proves the reindex path and the event
    stream share one version space.
- `search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchIngestionIT.java`
  - the re-activation scenario now carries a later `occurredAt`, because a producer that
    emits two different state transitions with the same timestamp is not a real producer.

### The residual risk, stated plainly

The version is derived from a wall-clock timestamp produced by the producer. Two events
emitted by the same producer within the same nanosecond, or two events emitted by
different replicas with a skewed clock, carry the same or an inverted version and the
later one is rejected. The correct producer-side design is a per-aggregate monotonic
sequence number in the event contract. That change is in `product-service` and is
recorded as a platform follow-up, not as a silent assumption.

---

## CAT-07 — the promotion quota was enforced by a racy read-then-write count

### The defect

`promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java`
called `reservationRepository.countByCampaignIdAndUserIdAndStatusIn(...)` and then
inserted a reservation. Two concurrent redemptions of the last coupon both read the count
below the limit and both inserted, so the quota was exceeded. The campaign-wide limits
were guarded the same way.

### The fix

**The quota is now enforced by an atomic conditional statement.** Two counter tables
carry the usage, and the reservation path claims a unit with a single conditional write
before anything is inserted:

- `promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignUserUsageRepository.java`
  claims the per-user unit with an `INSERT ... ON CONFLICT (campaign_id, user_id) DO
  UPDATE ... WHERE used_count < :limit`. The statement is atomic in PostgreSQL, so two
  concurrent redemptions of the last unit cannot both win: the second one updates zero
  rows and the claim returns zero.
- `promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignRepository.java`
  claims the campaign unit with a conditional `UPDATE campaign SET used_count =
  used_count + 1, budget_used = budget_used + :discount WHERE id = :id AND deleted = false
  AND (max_redemptions IS NULL OR used_count < max_redemptions) AND (total_budget IS NULL
  OR budget_used + :discount <= total_budget)`. The predicate is evaluated under the row
  lock, so the campaign-wide redemption count and the budget are enforced atomically.

**The counters are released when the reservation is released.** `release`,
`releaseCommitted` and the expiry sweeper
(`promotion-service/src/main/java/com/shop/promotionservice/service/ReservationCleanupScheduler.java`)
decrement the same counters, so a released reservation frees its unit.

**The schema is additive.** `promotion-service/src/main/resources/db/changelog/changelog-006-campaign-quota-counters.yaml`
adds the `used_count` and `budget_used` columns to `campaign`, creates the
`campaign_user_usage` table with the unique constraint `uk_cuu_campaign_user` and the
foreign key to `campaign`, adds the non-negative check constraints, and backfills the
counters from the existing reservations. No existing changeset was modified.

### The tests

`promotion-service/src/test/java/com/shop/promotionservice/service/CampaignQuotaConcurrencyIT.java`
runs against a real PostgreSQL and asserts:

- a coupon is redeemed up to its usage limit and both counters track the redemptions;
- a coupon at its usage limit is rejected and no reservation row is written;
- two threads redeeming the last use yield exactly one success;
- the persisted reservation rows and the counter rows are asserted, not only the response.

`promotion-service/src/test/java/com/shop/promotionservice/service/ReserveFlowIT.java`
keeps the eight-thread per-user race, where exactly three of eight concurrent reserves
succeed and the other five are rejected with a documented error code.

### The residual risk, stated plainly

The counter is released when a reservation is released, so a reservation that is neither
committed nor released nor expired keeps its unit until the expiry sweeper runs. The
sweeper interval, not the quota, therefore bounds how quickly a unit returns to the pool.
The counter can also drift if a reservation row is deleted outside the service, which is
why the backfill exists for the migration path.

---

## Commands and results

| command | exit | result |
| --- | --- | --- |
| `./mvnw -B -ntp -pl search-service -am test` | 0 | 72 tests, 0 failures, 0 errors, 0 skipped |
| `./mvnw -B -ntp -pl promotion-service -am test` | 0 | 138 tests, 0 failures, 0 errors, 0 skipped |
| `./mvnw -B -ntp -pl search-service,promotion-service -am test` | 0 | see the full reactor log |

## Files

Created: `search-service/src/main/java/com/shop/searchservice/search/ProductEventVersion.java`,
`search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchMonotonicProjectionIT.java`,
`promotion-service/src/main/java/com/shop/promotionservice/entity/CampaignUserUsage.java`,
`promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignUserUsageRepository.java`,
`promotion-service/src/main/resources/db/changelog/changelog-006-campaign-quota-counters.yaml`,
`promotion-service/src/test/java/com/shop/promotionservice/service/CampaignQuotaConcurrencyIT.java`.

Modified: `search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java`,
`search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java`,
`search-service/src/main/java/com/shop/searchservice/service/impls/ReindexServiceImpl.java`,
`search-service/pom.xml`, `search-service/src/test/java/com/shop/searchservice/kafka/ProductSearchIngestionIT.java`,
`search-service/src/test/java/com/shop/searchservice/service/impls/ReindexIT.java`,
`search-service/src/test/java/com/shop/searchservice/service/impls/SearchQueryIT.java`,
`promotion-service/src/main/java/com/shop/promotionservice/entity/Campaign.java`,
`promotion-service/src/main/java/com/shop/promotionservice/repository/CampaignRepository.java`,
`promotion-service/src/main/java/com/shop/promotionservice/service/ReservationCleanupScheduler.java`,
`promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java`,
`promotion-service/src/main/resources/db/changelog/db.changelog-master.yaml`,
`promotion-service/src/test/java/com/shop/promotionservice/service/CampaignLifecycleTest.java`,
`promotion-service/src/test/java/com/shop/promotionservice/service/CampaignReserveTest.java`,
`promotion-service/src/test/java/com/shop/promotionservice/service/ReservationCleanupSchedulerTest.java`,
`promotion-service/src/test/java/com/shop/promotionservice/service/ReserveFlowIT.java`.

## Blockers outside the write scope

- **CAT-02** — the declared Elasticsearch client version drifts from the server version.
  The root `pom.xml` declares the client at 9.4.5 while `search-service/pom.xml` pins
  8.15.5 and the compose file runs the server at 8.15.0. The effective client matches
  the server, so the platform runs correctly, but the declared property is misleading.
  Changing the root property is outside this write scope.
- **The producer-side ordering value** — a per-aggregate sequence number in the product
  event contract would remove the clock-skew residual risk. That is a `product-service`
  change and is outside this write scope.
