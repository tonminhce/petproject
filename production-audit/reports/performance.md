# Performance & Concurrency

Generated: 2026-11-10T10:05:42

Findings in this category: **5**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-013 | P2 | OPEN | Outbox relay holds a PESSIMISTIC_WRITE row lock and an open DB transaction across a blocking Kafka publish |
| F-027 | P2 | OPEN | cancelOrder holds a database transaction open across several remote HTTP calls |
| F-034 | P2 | OPEN | The edge rate limiter uses in-process bucket4j buckets, so the effective limit scales with the replica count |
| F-062 | P2 | OPEN | The media upload path amplifies memory to hundreds of megabytes for a single maximum-size image |
| F-066 | P2 | OPEN | Remote calls are made inside database transactions in three more services (product media check, rating eligibi |

---

### F-013 - Outbox relay holds a PESSIMISTIC_WRITE row lock and an open DB transaction across a blocking Kafka publish

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** inventory-service, product-service, payment-service, shipping-service, promotion-service, media-service, rating-service
- **Source files:**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java`
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/KafkaMessagePublisher.java`
- **Actual implementation:** InventoryOutboxRelay.relay() wraps claimOnePending (PESSIMISTIC_WRITE + SKIP LOCKED), the blocking kafkaPublisher.publish() call (future.get(10s)) and the status save in a single TransactionTemplate transaction. The row lock and the DB transaction therefore stay open for the full broker round-trip, up to 10 seconds per event, with a batch size of 100.
- **Expected:** The claim transaction is short; the Kafka publish happens outside the lock; the mark-published step is its own transaction.
- **Trigger:** A slow or degraded Kafka broker.
- **Root cause:** Claim, publish and mark-published were fused into one transaction to prevent double-publish, trading lock-hold time for correctness.
- **Consequences:** Long-running transactions hold row locks and connections; under broker degradation the relay can pin a HikariCP connection for 10s per event and exhaust the pool (default maximum-pool-size=20), and the outbox_events table accumulates PENDING rows behind head-of-line blocking.
- **Required remediation:** Split into three phases: (1) short claim transaction that flips the row to SENDING and returns it; (2) publish outside any transaction; (3) short update transaction that sets SENT/FAILED. Recover SENDING rows older than a lease timeout on startup. KafkaMessagePublisher already exposes publishBatch for the async fan-out.
- **Regression test:** Concurrency test: with a broker stub that delays 2s per publish, the relay must not hold more than one row lock at a time and the Hikari pool must not be exhausted; a simulated crash between publish and mark must leave the row recoverable.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-027 - cancelOrder holds a database transaction open across several remote HTTP calls

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
- **Actual implementation:** cancelOrder() is annotated @Transactional and calls stockReservationService.release(...) per item, promotionClient.release(...), promotionClient.releaseCommitted(...) and paymentClient.refundByOrderId(...) - each a remote HTTP call with a 3-5 second timeout - all inside the open transaction. The order row update and the outbox event are written at the end of the same transaction.
- **Expected:** Remote calls run outside the transaction; the local state change and its outbox event commit in a short transaction.
- **Trigger:** A slow downstream during a cancellation.
- **Root cause:** The cancel workflow mixes remote orchestration with the local transaction.
- **Consequences:** A pooled DB connection is held for the sum of the downstream latencies. With Hikari maximum-pool-size=20, a burst of cancellations against a degraded inventory or payment service can exhaust the pool and stall unrelated requests on the same service.
- **Required remediation:** Split the cancel workflow: read the order, perform the remote releases outside a transaction, then commit the status change plus outbox event in a short transaction (the same shape the confirm path already uses).
- **Regression test:** Concurrency test: hold the inventory service at 2s per call and assert the order-service connection pool is not exhausted by concurrent cancels.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-034 - The edge rate limiter uses in-process bucket4j buckets, so the effective limit scales with the replica count

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/java/com/shop/gateway/filter/RateLimitFilter.java`
  - `gateway-service/src/main/resources/application.yml`
- **Actual implementation:** RateLimitFilter's javadoc states 'Buckets live in-process (single gateway instance, V1)'. The buckets are a Caffeine cache of Bucket4j buckets keyed by scope + client IP, held in each gateway JVM. The two Redis-backed tiers (global 2000/4000 and per-client 100/200) are distributed; this third tier is not.
- **Expected:** A documented, consistent rate-limit model. If the edge scopes are intended to be fleet-wide, they need the same Redis-backed implementation as the other two tiers.
- **Trigger:** Running more than one gateway replica.
- **Root cause:** The tier was implemented as an in-process optimisation and labelled 'V1' without a migration plan or an operational note that the limit is per replica.
- **Consequences:** The backoffice (10 rpm) and search (60 rpm) limits are multiplied by the replica count: with 4 replicas a single client can make 40 backoffice requests per minute. This weakens exactly the control that protects the backoffice.
- **Required remediation:** Move the edge buckets to the same Redis-backed RedisRateLimiter used by the other tiers, or document the per-replica semantics explicitly in the deployment runbook and align the configured values with the replica count.
- **Regression test:** Multi-instance test: two gateway replicas must share the edge limit bucket.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-062 - The media upload path amplifies memory to hundreds of megabytes for a single maximum-size image

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** media-service
- **Source files:**
  - `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaUploadServiceImpl.java`
  - `media-service/src/main/java/com/shop/mediaservice/service/impls/VariantRenderer.java`
- **Actual implementation:** The upload path holds, simultaneously: the raw source bytes (file.getBytes(), up to shop.media.max-upload-bytes), a decoded BufferedImage (width x height x 4 bytes - the guard permits up to 8192 x 8192, i.e. 268 MB for one image), and the encoded bytes of every rendered variant (up to six byte arrays). The source is also parsed twice: once by ImageMetadataReader for the EXIF/GPS inspection and once by ImageIO, and the image is decoded a second time by ImageIO.read after the dimension probe. The dimension guard limits width and height but the pixel-count guard is width*height <= 8192*8192, which is exactly the 268 MB case.
- **Expected:** The upload path must bound its peak heap so a single upload cannot exhaust the service's heap, and must not decode the same image twice.
- **Trigger:** An administrator uploads a large (but permitted) image.
- **Root cause:** The size guard is expressed in source bytes and in pixel dimensions, but not in the memory the decoder will actually allocate; the header probe and the full decode are two separate passes over the same bytes.
- **Consequences:** A single upload can allocate several hundred megabytes of heap. Concurrent uploads multiply that, and the result is a heap-exhaustion outage of media-service rather than a rejected upload. The objective's 'memory and CPU behaviour for large uploads' requirement is not met.
- **Required remediation:** Express the guard in decoded-memory terms (width x height x 4 plus the source size must fit a configured budget), decode once (reuse the ImageReader stream for both the dimension probe and the decode), and stream the source to the digest instead of materialising it twice.
- **Regression test:** Performance test: upload an image at the configured maximum and assert the peak heap stays inside the configured budget; a unit test asserts that an image whose decoded size exceeds the budget is rejected before any large allocation.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-066 - Remote calls are made inside database transactions in three more services (product media check, rating eligibility, order cancel)

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** product-service, rating-service, order-service
- **Source files:**
  - `product-service/src/main/java/com/shop/productservice/service/impls/ProductServiceImpl.java`
  - `rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java`
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java`
- **Actual implementation:** ProductServiceImpl.create/update call assertMediaExists -> mediaHeadClient.exists() inside the @Transactional method. RatingServiceImpl.submit calls eligibilityClient.isEligible() inside its @Transactional method. OrderServiceImpl.cancelOrder (already recorded as F-027) performs the inventory, promotion and payment calls inside its @Transactional method. Each holds a pooled DB connection and an open transaction for the duration of a remote round-trip (3-5 second timeouts).
- **Expected:** Remote calls run outside the transaction; the local state change commits in a short transaction - the shape the checkout saga already uses deliberately.
- **Trigger:** Any slowdown of media-service, order-service or the payment provider.
- **Root cause:** The same pattern as F-027, repeated in three services that were written independently.
- **Consequences:** A degraded downstream holds DB connections and transactions open. With Hikari maximum-pool-size=20 a burst of concurrent requests against a slow dependency can exhaust the pool and stall unrelated requests on the same service.
- **Required remediation:** Move the remote verification out of the transaction: perform it first, then open the short transaction for the write. Where the remote result must be authoritative (the rating eligibility check), fail the request rather than holding the transaction open.
- **Regression test:** Concurrency test: hold the downstream at 2s per call and assert the service's connection pool is not exhausted.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
