# Distributed Systems & Messaging

Generated: 2026-11-10T10:05:42

Findings in this category: **8**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-001 | P1 | OPEN | Kafka decode failures are silently dropped: no DLT, no retry, no operator visibility |
| F-002 | P2 | OPEN | Four published lifecycle topics have no consumer: inventory, promotion, category, brand |
| F-012 | P2 | VERIFIED | Expiry sweep releases stock without emitting the inventory.released.v1 event - outbox and domain state diverge |
| F-017 | P1 | OPEN | No DLT consumer or replay path exists - a message that exhausts its 3 retries is stranded permanently |
| F-018 | P1 | VERIFIED | auto-offset-reset=latest in every Kafka consumer service: events published while a group has no member are ski |
| F-029 | P2 | OPEN | Consumer group ids are inconsistent: one order-service listener relies on the default group id |
| F-045 | P3 | VERIFIED | The promotion TTL sweep emitted no promotion.released.v1 event, unlike the inventory sweep - the two sweeps ha |
| F-058 | P1 | VERIFIED | product-service's outbox relay burned the whole retry budget inside one tick and then parked the event as FAIL |

---

### F-001 - Kafka decode failures are silently dropped: no DLT, no retry, no operator visibility

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-kafka, order-service, notification-service, product-service, search-service, shipping-service
- **Source files:**
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java`
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java`
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java`
- **Actual implementation:** BaseKafkaConsumer.decodeContained() catches every decode exception, logs at ERROR and returns null; the caller then returns without invoking the handler, so the record is ack-skipped. A handler exception, by contrast, DOES propagate to the container error handler (DefaultErrorHandler + DeadLetterPublishingRecoverer, FixedBackOff(1000ms, 3 retries)). KafkaProperties.Retry (backoff-ms, max-attempts) is documented 'INTENTIONALLY UNUSED' - the real retry policy is hardcoded in BaseKafkaListenerConfig.
- **Expected:** A malformed or poison record must be routed to the DLQ (or at minimum surfaced as a metric) rather than dropped, so the loss is observable and replayable.
- **Trigger:** Any payload that does not bind to the target DTO - schema drift, a producer bug, or corruption.
- **Root cause:** Deliberate 'ack-always poison posture' in the decode path, plus a dead Retry configuration block that operators can tune without effect.
- **Consequences:** Silent loss of order / payment / shipping / rating / media events. The offset advances as if the event had been handled; no DLQ entry exists; only a log line records it. A schema-evolution mistake upstream can therefore drop events across the fleet with no alarm.
- **Required remediation:** Make the decode failure path publish to the DLQ (or throw so the container error handler routes it), and either wire shop.kafka.retry into BaseKafkaListenerConfig or delete the dead block.
- **Regression test:** Unit test: a consumer given a malformed payload must either publish to the DLT topic or raise the error so the container routes it; the offset must not advance as a silent success.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-002 - Four published lifecycle topics have no consumer: inventory, promotion, category, brand

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** inventory-service, promotion-service, product-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java`
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/TransactionalPromotionEventPublisher.java`
  - `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalCategoryEventPublisher.java`
  - `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalBrandEventPublisher.java`
- **Actual implementation:** Producers publish shop.inventory.events.v1, shop.promotion.lifecycle.v1, shop.category.lifecycle.v1 and shop.brand.lifecycle.v1. A fleet-wide scan for @KafkaListener shows no consumer bound to any of these four topics.
- **Expected:** Every published lifecycle topic either has a consumer or is explicitly documented as fire-and-forget audit.
- **Trigger:** Normal operation.
- **Root cause:** Topics declared by producers without a matching consumer contract.
- **Consequences:** Published events accumulate with no consumer; documentation implies downstream projections that do not exist.
- **Required remediation:** Document the four topics as unconsumed, or implement the missing projections (for example category/brand into the search index).
- **Regression test:** Static topology test: every produced topic must have a declared consumer or an explicit allow-list entry.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-012 - Expiry sweep releases stock without emitting the inventory.released.v1 event - outbox and domain state diverge

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** inventory-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java`
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java`
- **Actual implementation:** InventoryServiceImpl.release() calls publisher.publishReleased(inventory, r, "PENDING") and releaseCommitted() calls publishReleased(inv, r, "COMMITTED"), both writing an outbox row in the same transaction. ReservationCleanupScheduler.releaseBatch() decrements reservedQuantity and flips the status to EXPIRED but never calls the event publisher, so no outbox row is written.
- **Expected:** Every stock movement - including TTL expiry - produces the corresponding lifecycle event in the same transaction.
- **Trigger:** Any reservation that reaches its TTL and is swept.
- **Root cause:** The scheduler reimplements the release transition instead of delegating to the shared transactional release path.
- **Consequences:** shop.inventory.events.v1 under-reports releases; any downstream projection (or reconciliation job) that trusts the event stream diverges from the inventory table. It also hides the release from the outbox-driven audit trail.
- **Required remediation:** Route the sweep through the same transactional release path so the outbox row is written atomically with the stock change.
- **Regression test:** Integration test: sweeping an expired reservation must leave exactly one outbox row of type inventory.released.v1 with previousStatus=PENDING.
- **Remediation applied:** ReservationCleanupScheduler now publishes an inventory.released.v1 outbox row (previousStatus=PENDING) for every expired reservation it claims, in the same transaction as the stock movement.
- **Verification evidence:** sweep_publishesReleasedEventForEachExpiredReservation asserts the outbox event for a swept reservation.
### F-017 - No DLT consumer or replay path exists - a message that exhausts its 3 retries is stranded permanently

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service, product-service, search-service, shipping-service, notification-service
- **Source files:**
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java`
  - `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleConsumer.java`
- **Actual implementation:** BaseKafkaListenerConfig installs a DeadLetterPublishingRecoverer, so a listener that throws 4 times routes its record to <topic>.DLT. A fleet-wide search for DLT / DeadLetter shows no consumer, no replay scheduler and no operator tooling for any DLQ topic.
- **Expected:** DLQ entries are surfaced (metric / alert) and replayable after the root cause is fixed.
- **Trigger:** A downstream dependency (payment, inventory, promotion, search) is unavailable long enough to exhaust the FixedBackOff(1000ms, 3) retry budget.
- **Root cause:** The DLQ publish side was implemented; the consume/replay side was never built.
- **Consequences:** A payment.captured.v1 that fails 3 times is lost forever: the order stays PENDING until the 30-minute reconciler, which polls reservation states - but the reservations were never committed, so it is auto-cancelled. The customer paid and the order is cancelled. The same loss applies to shipping.delivered (the order never reaches DELIVERED) and to rating/media lifecycle events.
- **Required remediation:** Add a DLQ consumer per topic that persists the failure for operator replay, or at minimum a metric plus an alert and a documented manual replay procedure using kafka-console-consumer.
- **Regression test:** Integration test: force a listener to fail 4 times, assert the record lands in the DLQ, then run the replay path and assert the message is processed.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-018 - auto-offset-reset=latest in every Kafka consumer service: events published while a group has no member are skipped

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** order-service, payment-service, product-service, search-service, shipping-service, notification-service, media-service
- **Source files:**
  - `order-service/src/main/resources/application.yml`
  - `payment-service/src/main/resources/application.yml`
  - `product-service/src/main/resources/application.yml`
  - `search-service/src/main/resources/application.yml`
  - `shipping-service/src/main/resources/application.yml`
  - `notification-service/src/main/resources/application.yml`
  - `media-service/src/main/resources/application.yml`
- **Actual implementation:** Every service that consumes Kafka sets shop.kafka.consumer.auto-offset-reset: latest. The shared default in common-kafka KafkaProperties.ConsumerConfig is also 'earliest', but every service overrides it to latest. With 'latest', a consumer group with no committed offsets (a new group, or a group whose offsets were expired) starts at the END of the topic and never sees the backlog.
- **Expected:** At-least-once delivery semantics where a consumer group that was down replays the events it missed, which is the premise of the documented 'at-least-once message delivery with recovery mechanisms' claim.
- **Trigger:** A consumer service is scaled to zero, redeployed with a new group id, has its offsets expired, or starts after events were published.
- **Root cause:** latest was chosen to avoid replaying the whole topic on a first deployment, without considering the missed-event window.
- **Consequences:** Silent event loss. A shipping.delivered event published during a notification-service restart is never delivered (the order never reaches DELIVERED); a payment.captured.v1 published during an order-service restart is never seen (the order stays PENDING until the reconciler cancels it).
- **Required remediation:** Set auto-offset-reset to earliest for consumers that need gap-free processing, and ensure every consumer group has durable, monitored offsets. Alternatively keep latest but add a reconciliation sweep for every event-driven projection.
- **Regression test:** Integration test: publish an event with no active consumer, start the consumer, and assert the event is processed.
- **Remediation applied:** shop.kafka.consumer.auto-offset-reset changed from 'latest' to 'earliest' in all seven Kafka-consuming services (media, notification, order, payment, product, search, shipping), so a consumer group replays the events it missed instead of skipping them.
- **Verification evidence:** Full reactor 3324/0/0 with auto-offset-reset=earliest across all seven consuming services.
### F-029 - Consumer group ids are inconsistent: one order-service listener relies on the default group id

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/kafka/ShippingDeliveredConsumer.java`
  - `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleConsumer.java`
  - `order-service/src/main/resources/application.yml`
- **Actual implementation:** PaymentLifecycleConsumer declares an explicit groupId 'order-service-payment-group'. ShippingDeliveredConsumer declares no groupId and therefore inherits shop.kafka.consumer.group-id = 'order-service'. Two listeners in the same service can therefore share a consumer group, in which case Kafka assigns each partition to only one member and each message is delivered to exactly one of them.
- **Expected:** Every listener in a service has an explicit, distinct consumer group id.
- **Trigger:** A second listener is added to order-service without an explicit group id.
- **Root cause:** Mixed convention - some listeners pin a group id, others rely on the shared default.
- **Consequences:** Adding one more listener can silently halve the delivery of an existing topic; the shipping.delivered event may stop reaching order-service entirely.
- **Required remediation:** Give every @KafkaListener an explicit groupId and add a static test that fails when a listener has no explicit group.
- **Regression test:** Static test: assert every production @KafkaListener declares groupId.
- **Remediation applied:** Not yet implemented - scope confirmed by the harness static check MSG-001: SIX production listeners across five services rely on the shared default consumer group id (shop.kafka.consumer.group-id) instead of declaring their own. Once a second listener is added to any of those services, Kafka splits the partitions between them and one of the two stops receiving its topic.
- **Verification evidence:** harness verify --suite source -> MSG-001 FAIL with the 6 listener paths (notification OrderEventConsumer, order ShippingDeliveredConsumer, product MediaDeletedConsumer + ProductRatingConsumer, search ProductSearchConsumer, shipping OrderEventConsumer).
### F-045 - The promotion TTL sweep emitted no promotion.released.v1 event, unlike the inventory sweep - the two sweeps had diverged

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** promotion-service, inventory-service
- **Source files:**
  - `promotion-service/src/main/java/com/shop/promotionservice/service/ReservationCleanupScheduler.java`
  - `promotion-service/src/main/java/com/shop/promotionservice/service/impls/TransactionalPromotionEventPublisher.java`
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java`
- **Actual implementation:** The promotion sweep's javadoc stated 'NO events to publish - only the status changes' and it indeed wrote no outbox row, while the inventory sweep (after the F-012 remediation) publishes inventory.released.v1 for every swept reservation. The two sweeps therefore had different contracts for the same kind of transition.
- **Expected:** Every state transition of a reservation is described by the event stream, or the divergence is deliberate and documented.
- **Trigger:** A coupon reservation reaches its TTL and is swept.
- **Root cause:** The two sweeps were written independently against different (inconsistent) contracts.
- **Consequences:** shop.promotion.lifecycle.v1 reports a reservation that was never released. The practical impact today is bounded because that topic has no consumer at all (F-002), so the divergence is invisible today but would surface the moment a consumer is added.
- **Required remediation:** The sweep now publishes promotion.released.v1 (previousStatus=PENDING) in the same transaction as the status transition, matching the inventory sweep and the service-layer release path.
- **Regression test:** promotion-service: ReservationCleanupSchedulerTest.sweep_publishesReleasedEventForEachExpiredReservation.
- **Remediation applied:** ReservationCleanupScheduler injects CampaignRepository + PromotionEventPublisher and publishes the released event per claimed reservation.
- **Verification evidence:** promotion-service green; sweep_publishesReleasedEventForEachExpiredReservation asserts the released event.
### F-058 - product-service's outbox relay burned the whole retry budget inside one tick and then parked the event as FAILED, permanently losing the product lifecycle event

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** product-service
- **Source files:**
  - `product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java`
- **Actual implementation:** Unlike the inventory, promotion and media relays, product-service's OutboxRelay returned TRUE from its publish-failure branch and kept draining the batch. Because the claim always takes the MIN(id) PENDING row, the next iteration re-claimed the SAME failing row and retried it, so the max-retries budget (10) was consumed within ONE tick instead of across the 5-second poll interval. The row was then parked as FAILED - and product-service has no retry, alert or repair path for FAILED rows - so a transient broker outage permanently lost the product lifecycle event. shop.product.lifecycle.v1 therefore stopped reaching search-service and the search index diverged from the catalogue with no repair route. Each retry also re-held the PESSIMISTIC_WRITE row lock across a blocking publish (compounding F-013).
- **Expected:** A publish failure leaves the row PENDING for the next tick, the retry budget is spread across ticks, and per-aggregate event ordering is preserved.
- **Trigger:** Any transient Kafka outage or broker error while the relay is draining.
- **Root cause:** The relay's failure branch did not stop the batch, unlike every sibling relay.
- **Consequences:** Permanent, silent loss of product lifecycle events; the Elasticsearch index diverges from the catalogue with no automated repair.
- **Required remediation:** The failure branch now returns FALSE (STOP), matching the inventory, promotion and media relays: the row stays PENDING, the retry budget is spread across ticks and ordering is preserved.
- **Regression test:** product-service outbox relay test asserting that a failed publish leaves the row PENDING and stops the batch for that tick.
- **Remediation applied:** product-service OutboxRelay publish-failure branch returns Boolean.FALSE (STOP) instead of Boolean.TRUE.
- **Verification evidence:** product-service green; the relay failure branch now returns FALSE (STOP) so the row stays PENDING for the next tick.
