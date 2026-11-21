# Independent Kafka / Messaging / Distributed-Systems Audit — kafka-messaging

- Revision audited: `f242158265163d65ef73d378331d2bd7a92479ee`
- Auditor: kafka-messaging (independent, zero-trust — docs and `production-audit/` ignored; all evidence re-derived from source)
- Date: 2026-11-22
- Write scope honored: only this file was written. No application source modified. No maven/docker run.

## 0. Scope & method

Read completely (source only):
- `utils/common-kafka` — all 19 main sources + `pom.xml` + `AutoConfiguration.imports` (test sources listed but not reviewed).
- `auth-service` — all 42 main java files + all 8 resources (yml + 6 liquibase changesets + master).
- `notification-service` — all 23 main java + 4 resources.
- `rating-service` — all 22 main java + 6 resources.
- `search-service` — all 25 main java + 1 resource.
- `favourite-service` — all 9 main java + 3 resources.
- `media-service` — all 35 main java + 9 resources.
- `docker-compose.yml` Kafka + Keycloak sections; `docker/keycloak/import/ecommerce-realm.json` (grep-level).
- Shared configs actually imported by these services: `utils/common-config/src/main/resources/application-shared.yml`, `utils/common-core` (`AbstractMappedEntity`, `OutboxStatus`), `utils/common-logging` (`LoggerAspect`, `LogPerformance`), `utils/common-storage` (`S3ObjectStorageService`).
- Sampled outside deep scope to complete the producer/consumer inventory (topic constants, keys, payloads, claim semantics): order/payment/shipping/inventory/promotion/product event publishers + outbox relays, order/payment/shipping consumers, `product-service` `ProductRatingService`/`MediaDeletedConsumer`.

Evidence convention: every claim cites `file:line` at the audited revision.

---

## 1. Outbox pattern — answered from source

**There is no outbox in common-kafka.** common-kafka provides only producer/consumer plumbing (`KafkaMessagePublisher`, `BaseKafkaListenerConfig`, `BaseKafkaConsumer`, `KafkaTopicContract`, DLT replay). The outbox is **per-service**, duplicated in 8 services (order, product, inventory, payment, shipping, rating, media, promotion), each with its own `outbox_events` table:

- rating: entity `rating-service/.../outbox/OutboxEvent.java:10-56`, table `changelog-001-ratings.yaml:45-56` (+ lease columns `changelog-002/003`).
- media: entity `media-service/.../outbox/OutboxEvent.java:10-59`, table `changelog-002-outbox.yaml:3-14` (+ `failed_at` `changelog-003`, lease `changelog-004/005`).

**Business TX → outbox row:** same transaction, confirmed.
- rating: `RatingServiceImpl.submit` `rating-service/.../impls/RatingServiceImpl.java:48-61` wraps `saveAndFlush(rating)` + `ratingEventService.record(...)` (REQUIRED propagation, `RatingEventService.java:40`) in one `transactionTemplate`.
- media: `MediaUploadServiceImpl.upload` `media-service/.../impls/MediaUploadServiceImpl.java:150-155` and `MediaLifecycleServiceImpl.softDelete` `:29-50` (`@Transactional`).
- order: `OrderEventPublisherImpl.save` writes PENDING row inside the caller's TX (`order-service/.../OrderEventPublisherImpl.java:80-104`).

**Who publishes:** scheduled poller per service (no Debezium, no CDC). Rating `RatingOutboxRelay.relay()` `@Scheduled(fixedDelay=2000ms)` `rating-service/.../outbox/RatingOutboxRelay.java:61`; media `MediaOutboxRelay.relay()` `media-service/.../outbox/MediaOutboxRelay.java:62`; order `OrderOutboxRelay` `:62-64` (5000 ms).

**Is polling safe for 2 instances?** Yes for 7 of 8 relays: claim uses
`pg_try_advisory_xact_lock(hashtextextended(aggregate_id,0))` + `NOT EXISTS (... status='SENDING')` + `FOR UPDATE SKIP LOCKED` (`OutboxEventRepository.claimAggregateEvents` rating `:28-38`, media `:30-40`, same pattern in product/payment/inventory/promotion). The single-flight-per-aggregate clause additionally prevents two instances from concurrently publishing different events of the same aggregate.
**Exception — order-service**: claims one row at a time with `findFirstByStatusOrderByIdAsc` under plain `PESSIMISTIC_WRITE` (blocking, no SKIP LOCKED, no per-aggregate guard) — see KFK-2.

**Crash between Kafka ack and mark → duplicate publish?** Yes, by design (at-least-once). Ack arrives in `KafkaMessagePublisher.publishBatch` via `future.whenComplete` (`KafkaMessagePublisher.java:102-112`); rows are marked SENT in a *separate* TX (`markFinalState`, rating `:210-236`). A crash in between leaves rows `SENDING`; the lease reaper (`recoverStrandedClaims`, 60 s lease, rating `:196-208`) requeues them → **republish**. Downstream dedup compensates (see per-consumer table §3): notification dedups on `eventId` unique index, search uses ES external versioning, product rating re-applies an identical snapshot, order/shipping use state-machine guards. KFK-11 documents the residual windows.

**Ordering guarantee:** partition key = `aggregateId.toString()` (rating `RatingOutboxRelay.java:70-74`, media `:69-75`, order `OrderOutboxRelay.java:77-82`). Producer is configured `max.in.flight.requests.per.connection=1` + `enable.idempotence=true` + `acks=all` (`KafkaProperties.buildProducerProperties` `:61-73`) → per-partition, per-producer ordering. Per-aggregate single-flight in the claim query preserves cross-relay ordering — except order-service (KFK-2). `OutboxBatchOrdering.markableAsSent` (`OutboxBatchOrdering.java:11-27`) refuses to mark later events of a key as SENT when an earlier one failed, forcing whole-key re-drive in order.

**Retry / backoff / poison:** relay-level retry with `max-retries:10` (rating/media yml), exponential-ish backoff only via re-poll (no per-event delay); after max attempts rows become `FAILED`. Media keeps retrying FAILED forever and ages it to `DEAD` after 7 days with a metric (`MediaOutboxRetentionScheduler.java:37-42`, `MediaOutboxRelay.java:30-31`); rating/product/order/inventory/payment/promotion park `FAILED` as terminal with no re-drive (KFK-3). Shipping ages exhausted rows to DEAD and counts them (`ShippingOutboxRelay.java:155-171`, `ShippingMetrics.java:47-52`).

**Backlog metric/alert:** only order/inventory/promotion/product relays publish a pending gauge (`OrderOutboxRelay.java:68`, `InventoryOutboxRelay.java:73`, `PromotionOutboxRelay.java:75`, product `OutboxRelay.java:75`). **Rating exposes none** (RatingMetrics has only `rating_submitted_total`, `RatingMetrics.java:16-18`) and media only `media_outbox_dead_total` — no pending/FAILED gauges for in-scope producers. No consumer-lag or DLT-depth metrics anywhere. See KFK-3/KFK-4.

---

## 2. Every Kafka producer

All business producers go through the outbox → relay → `KafkaMessagePublisher.publishBatch` (String key = aggregate UUID, String value = pre-serialized JSON from the `payload` column). **No service calls `kafkaTemplate.send` directly** — the only direct sends are (a) inside `KafkaMessagePublisher` itself and (b) `DeadLetterReplayService.replay` `utils/common-kafka/.../DeadLetterReplayService.java:101` (intentional replay). `auth-service`, `notification-service`, `search-service`, `favourite-service` produce **nothing** (AUTH-6).

| # | Producer service | Topic | Key | Payload (top-level fields) | Path |
|---|---|---|---|---|---|
| 1 | order-service | shop.order.lifecycle.v1 | orderId | eventId, eventType (`order.created.v1`/`order.updated.v1`/`order.cancelled.v1`), occurredAt, orderId, userId, status, items[], subtotal, taxAmount, discountAmount, total, currency, (+couponCode/transitionedAt/cancelledAt/refunded) | `OrderEventPublisherImpl.java:38-104` |
| 2 | product-service | shop.product.lifecycle.v1 | productId | eventId, eventType (`ProductCreated`/`ProductUpdated`/`ProductDeleted`), occurredAt, productId, slug, status, title, description, brandId/Name, categoryId/Name, price, currency, imageUrl, avgRating, ratingCount, updatedAt | `TransactionalProductEventPublisher.java:49-88` |
| 3 | product-service | shop.category.lifecycle.v1 | categoryId | taxonomy event (audit-only, no in-fleet consumer) | `TransactionalCategoryEventPublisher.java` |
| 4 | product-service | shop.brand.lifecycle.v1 | brandId | taxonomy event (audit-only) | `TransactionalBrandEventPublisher.java` |
| 5 | inventory-service | shop.inventory.events.v1 | productId | eventId, eventType (`inventory.reserved/committed/released/adjusted/deleted/low_stock.v1`), occurredAt, productId, reservationId?, quantity/orderId/expiresAt/previousStatus/availableQuantity/safetyStockThreshold | `TransactionalInventoryEventPublisher.java:34-120` |
| 6 | payment-service | shop.payment.lifecycle.v1 | paymentId | eventId, eventType (payment lifecycle), occurredAt, orderId, paymentId, amount, currency, status, previousStatus | `PaymentEventPublisher.java:28-62` |
| 7 | shipping-service | shop.shipping.lifecycle.v1 | shipmentId | eventId, eventType (`shipping.delivered.v1`), occurredAt, orderId, shipmentId, carrier, trackingNumber, autoDelivered | `ShippingEventPublisherImpl.java:30-63` |
| 8 | rating-service | shop.rating.lifecycle.v1 | productId | eventId, eventType (`rating.submitted.v1`), occurredAt, ratingId, productId, userId, rating, comment, verified, action, visible, **avgRating, ratingCount** | `RatingEventService.java:41-78` |
| 9 | promotion-service | shop.promotion.lifecycle.v1 | campaignId | eventId, eventType (`promotion.reserved/committed/released.v1`), occurredAt, campaignId, code, userId, orderId, reservationId, discountAmount, (+committedAt/releasedAt/previousStatus) | `TransactionalPromotionEventPublisher.java:34-97` |
| 10 | media-service | media.lifecycle.v1 | mediaId | eventType (`MediaCreated`/`MediaDeleted`), mediaId, sha256, contentType, canonicalPath, variants[], occurredAt — **note: no `eventId` field** | `MediaEventService.java:33-63` |

Payloads are JSON produced by `objectMapper.writeValueAsString(payload)` and stored in the outbox `payload` TEXT column; the relay sends the stored string verbatim (`RatingOutboxRelay.java:70-74`, `KafkaMessagePublisher.publishBatch:87-113`). W3C `traceparent` is injected into headers when a span is active (`KafkaMessagePublisher.java:142-149`).

---

## 3. Every Kafka consumer (7 production listeners, all via `BaseKafkaListenerConfig` factories)

Container factory (shared): `ConcurrentKafkaListenerContainerFactory`, consumer = raw `StringDeserializer` key/value (`BaseKafkaListenerConfig.java:105-121`), **concurrency: default 1 (never set)**, `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + `FixedBackOff(1000 ms, 3 retries)` (`:62-65`), observation enabled. Consumer props: `enable.auto.commit=false`, `auto.offset.reset=earliest`, `isolation.level=read_committed`, `max.poll.records=500`, `max.poll.interval.ms=300000`, `session.timeout.ms=45000` (`KafkaProperties.java:75-87,173-180`). Ack mode: container default BATCH → offsets committed after each poll batch; crash mid-batch redelivers the batch (at-least-once). Typed decode uses Jackson-3 mapper with `FAIL_ON_UNKNOWN_PROPERTIES` disabled (`KafkaWireMapper.java:9-11`); undecodable payloads throw `PoisonRecordException` (`BaseKafkaConsumer.java:66-71`) → DLT after retries.

| # | Consumer | Topic | groupId | Factory | Decode | Idempotency | Redelivery consequence |
|---|---|---|---|---|---|---|---|
| 1 | notification `OrderEventConsumer` (`kafka/OrderEventConsumer.java:20-25`) | shop.order.lifecycle.v1 | notification-service | notificationListenerFactory | typed → DLT on poison | `existsByEventId` + UNIQUE `uk_notification_event_id` (`changelog-001:39-40`, entity `Notification.java:38-39`) + insert race caught (`NotificationServiceImpl.java:61-84`) | duplicate order event → dedup hit → **no double email** (P3 residual: KFK-5 claim race can still double-send) |
| 2 | search `ProductSearchConsumer` (`kafka/ProductSearchConsumer.java:23-27`) | shop.product.lifecycle.v1 | search-service | searchListenerFactory | **manual, exceptions swallowed → ack-skip (KFK-1)** | ES external versioning from `occurredAt/updatedAt` epoch-nanos; stale event = ES 409 → ignored (`ProductSearchService.java:64-74`) | re-index same version → 409 → no-op; **poison/stale failures are silently dropped** → stale search until next event/reindex |
| 3 | shipping `OrderEventConsumer` (`kafka/OrderEventConsumer.java:20-25`) | shop.order.lifecycle.v1 | shipping-service | shippingListenerFactory | typed → DLT | `existsByOrderId` guard on creation (`ShipmentCreationService.java:51`); cancel is a no-op if shipment past CREATED (`ShipmentServiceImpl.java:87-96`) | duplicate CONFIRMED → second create skipped; duplicate CANCELLED → idempotent transition guard. No double side effect |
| 4 | order `PaymentLifecycleConsumer` (`kafka/PaymentLifecycleConsumer.java:21-43`) | shop.payment.lifecycle.v1 | order-service-payment-group | paymentListenerFactory | typed → DLT | `confirmOrder(orderId, actor, "payment-confirm-"+paymentId)` idempotency-key service (`OrderServiceImpl.java:274-324`); invalid-state-transition → skip (`:36-39`) | duplicate captured event → idempotency cache hit → **no double confirm**; state guard prevents double stock commit |
| 5 | order `ShippingDeliveredConsumer` (`kafka/ShippingDeliveredConsumer.java:20-25`) | shop.shipping.lifecycle.v1 | order-service | shippingListenerFactory | typed → DLT | state machine: only SHIPPED→DELIVERED applied, else no-op (`ShippingDeliveredHandler.java:36-39`) | duplicate delivered → no-op. No double state mutation |
| 6 | product `ProductRatingConsumer` (`kafka/ProductRatingConsumer.java:19-24`) | shop.rating.lifecycle.v1 | product-service | ratingListenerFactory | typed → DLT | applies authoritative snapshot avgRating/ratingCount (`ProductRatingService.java:20-31`); missing product → ack-skip | duplicate → same snapshot re-applied → **idempotent**; out-of-order (only via relay bug) could regress aggregate — see KFK-2/KFK-3 notes |
| 7 | product `MediaDeletedConsumer` (`kafka/MediaDeletedConsumer.java:29-50`) | media.lifecycle.v1 | product-service | mediaListenerFactory | **manual, ALL exceptions swallowed → ack-skip (KFK-1)** | `clearReference` is a natural-key clear (idempotent); 3×200 ms bounded retry then ack-skip with reconciliation-sweep backstop (`:52-68`) | duplicate MediaDeleted → clear no-op; **permanent failure → silently dropped** → product keeps dead media reference |

No consumers exist for `shop.inventory.events.v1`, `shop.promotion.lifecycle.v1`, `shop.category.lifecycle.v1`, `shop.brand.lifecycle.v1` — declared `FIRE_AND_FORGET` with rationale in the contract (`KafkaTopicContract.java:64-82`), enforced by `KafkaTopicContractTest` (CONSUMED ⇒ listener exists).

---

## 4. Topic inventory vs the reported list

Code-defined contract `utils/common-kafka/.../KafkaTopicContract.java:37-48`:

| Contract constant | Reported list | Producers | Consumers | Verdict |
|---|---|---|---|---|
| shop.order.lifecycle.v1 | ✓ | order | notification, shipping | match |
| shop.product.lifecycle.v1 | ✓ | product | search | match |
| shop.category.lifecycle.v1 | ✓ | product | none (FIRE_AND_FORGET) | match |
| shop.brand.lifecycle.v1 | ✓ | product | none (FIRE_AND_FORGET) | match |
| shop.inventory.events.v1 | ✓ | inventory | none (FIRE_AND_FORGET) | match |
| shop.payment.lifecycle.v1 | ✓ | payment | order | match |
| shop.shipping.lifecycle.v1 | ✓ | shipping | order | match |
| shop.rating.lifecycle.v1 | ✓ | rating | product | match |
| shop.promotion.lifecycle.v1 | ✓ | promotion | none (FIRE_AND_FORGET) | match |
| media.lifecycle.v1 | ✓ | media | product | match |

- **Topics in code but not in the list:** none. DLT destinations (`<topic>.DLT` by `DeadLetterPublishingRecoverer`, both `-dlt`/`.DLT` accepted by `KafkaTopicContract.originalTopicOf:112-124`) exist implicitly per consumed topic and are not in either list — they are unmonitored/unconsumed (KFK-4).
- **Topics in the list but not in code:** none.
- **Auto-create:** compose defines **no topics and no partition counts** (`docker-compose.yml:81-108`); nothing in code provisions topics (only `KafkaPropertiesTopicsRemovedTest` confirms `shop.kafka.topics` was removed). Topics are therefore auto-created by the broker with image defaults (1 partition, RF=1, 7-day retention) — KFK-9.
- **Compose Kafka:** single-node KRaft (`KAFKA_PROCESS_ROLES: broker,controller`, `KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093`), PLAINTEXT listeners only, offsets/txn log RF=1, advertised listener `kafka:9092` (`docker-compose.yml:88-99`). All services point at `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`.

---

## 5. Event contracts & versioning

- Versioning is a naming convention only: topic suffix `.v1` + `eventType` discriminator values like `order.created.v1` (`KafkaTopicContract.java:37-42`, producers §2). **No schema registry, no schema validation at the wire.**
- Compatibility strategy is *tolerant reader*: consumer mappers disable `FAIL_ON_UNKNOWN_PROPERTIES` (`KafkaWireMapper.java:9-11`) and DTOs carry `@JsonIgnoreProperties(ignoreUnknown=true)` (`OrderLifecycleEvent.java:11`, `ProductLifecycleEvent.java:8`). **Producer adds a field → consumers ignore it, nothing breaks.** Renaming/removing a field or changing a type silently binds `null`/fails decode: type mismatch → `PoisonRecordException` → DLT (3 pointless retries first); nullable rename → event processed with missing data and **acked** (e.g. notification would email with `status=null`).
- `eventType` unknown to a consumer: notification persists as `SKIPPED` with channel LOG (`NotificationServiceImpl.java:71-72`, `NotificationTemplates.build:20-26`); search logs and skips (`ProductSearchConsumer.java:43-44`); product media logs and skips (`MediaDeletedConsumer.java:44-45`). Good — forward compatibility is observable, not fatal.
- Ordering/atomicity is carried by convention: consumers trust `eventId` for dedup (notification), `occurredAt/updatedAt` for ordering (search). **media.lifecycle.v1 carries no `eventId`** (`MediaEventService.java:42-52`) — the only producer whose events cannot be deduped by ID (consumer is idempotent by nature today, but the contract is weaker; noted in KFK-11).

---

## 6. auth-service / Keycloak

- **Integration:** pure admin REST + ROPC. Token flows via `KeycloakTokenClient` (password grant login `utils/common-keycloak/.../KeycloakTokenClient.java:41-52`, refresh `:54-64`, code exchange `:66-77`, logout `:79-102`, `verifyCredentials` = full login + immediate logout `:104-118`). User lifecycle via `KeycloakAdminClient` (create user + credentials + realm roles `:175-212`, reset password `:305-329`, enable/disable + force-logout `:233-259`, role assign/remove `:423-513`). Admin auth = service-account `client_credentials` only; master-realm password grant is refused (`requestAdminToken:403-421`, constructor warn `:116-119`). **No direct Keycloak DB access.**
- **Roles:** local mirror `users/user_role/roles` (changelog 001/006); self-registration whitelist `USER` only (`UserServiceImpl.extractRoles:228-245`); admin assign/revoke calls Keycloak FIRST then local TX (`RoleServiceImpl.assignRole:46-65`) — KC-first avoids privilege divergence but a crash after KC assign leaves local mirror stale (mirror is not authoritative; JWT realm roles are what gate `@PreAuthorize`).
- **Secrets:** `client-secret: ${...:changeme}` defaults shipped in search (`application.yml:29`), rating (`:45`), media (`:59`); DB `admin/admin` defaults in every service yml (e.g. `rating-service/application.yml:8-10`). Admin secret has **no** default (`application-shared.yml:161`) → fails safe. Realm import resolves secrets from env placeholders (`docker/keycloak/import/ecommerce-realm.json:64-129`). AUTH-3.
- **Passwords:** never stored locally (`User` entity has no password field, `entity/User.java:26-71`); registration complexity enforced by regex (min 8, upper/lower/digit, `RegisterRequest.java:18-22`); reset path accepts **min 6, no complexity** (`ResetPasswordRequest.java:18-21`) — AUTH-5. `@LogPerformance` does not log arguments by default (`LogPerformance.java:14`, `LoggerAspect.java:42`) → password not logged on `register`.
- **Lockout:** nothing in code; realm import sets no `bruteForceProtected` and the only client used for login (`ecommerce-client`) is `publicClient` with `directAccessGrantsEnabled: true` (`ecommerce-realm.json:29-36`); the posture check merely logs a warning (`KeycloakPasswordGrantPostureCheck.java:44-49`). AUTH-2.
- **Auth events via outbox?** **No.** auth-service has no Kafka producer/consumer code and publishes no user-registered/deleted/disabled events (AUTH-6); notification therefore cannot greet or deactivate-notify users.

---

## 7. notification-service

- **Provider:** Jakarta SMTP via `JavaMailSender` (`SmtpNotificationSender.java:51-63`), selected only when `shop.notification.smtp.enabled=true`; otherwise a LOG sender (`NotificationSenderConfig.java:13-20`). **There is no Telegram integration anywhere in the service** (channel enum only has `LOG, SMTP`, `NotificationChannel.java:3-5`).
- **Retry:** DB-backed outbox-style delivery: PENDING → SENDING (claim w/ 900 s heartbeat) → SENT / FAILED_RETRYABLE (5^n backoff, base 300 s, max 6 attempts, `NotificationRetryPolicy.java:28-38`) / FAILED_PERMANENT (address-invalid classifier `NotificationFailureClassifier.java:13-26`), polled by `NotificationRetryScheduler` every 60 s under `PESSIMISTIC_WRITE` (`NotificationRepository.findRetryCandidates:29-39`).
- **Secrets:** mail credentials from env with empty defaults (`notification-service/application.yml:21-25`); `SMTP_FALLBACK_RECIPIENT` mandatory when SMTP enabled (fail-fast `SmtpNotificationSender.java:30-35`).
- **Duplicate events → double notify?** Dedup by `eventId` unique index makes redelivery safe (§3 row 1); the residual claim race can double-send (KFK-5). Consequence if it happens: duplicate email to one address — **P3, acceptable** for order notifications.
- **Template injection:** none — subjects/bodies are `String.format` over UUID/enum/numeric fields (`NotificationTemplates.java:28-53`); no user free-text enters subject/body; `MimeMessageHelper` with UTF-8, `html=false`.
- **KFK-6:** the event payload contains **no recipient email** (order events carry `userId` only, `OrderEventPublisherImpl.java:38-53`; `NotificationServiceImpl.payload(event)` stores exactly that, `:132-138`), so `resolveRecipient` (`SmtpNotificationSender.java:65-85`) falls through to `fallbackRecipient` for **every** email — customers are never emailed; all order data lands in one shared mailbox.

---

## 8. rating-service

- **Verified purchase:** enforced synchronously against order-service `/api/v1/orders/verify-purchase` with service token; circuit-breaker fallback **rethrows** (fail-closed — a rating can never be silently marked verified/unverified due to outage) (`EligibilityClient.java:34-63`); result stored on the row (`RatingServiceImpl.submit:43-54`) and shipped in the event payload (`RatingEventService.java:62`).
- **Duplicate review prevention:** app-level check (`RatingServiceImpl.submit:38-41`) **plus** DB partial unique `uk_rating_user_product_live ON ratings(user_id, product_id) WHERE deleted=false` (`changelog-001-ratings.yaml:42`). Concurrent duplicate submit loses at the DB with a raw `DataIntegrityViolationException` (500 instead of 409) — cosmetic.
- **Rating aggregate race:** aggregate is computed **server-side in the rating TX** from live rows after `saveAndFlush` (`RatingEventService.record:42-44` → `RatingRepository.findAggregateByProductId`) — the event carries the snapshot, so the consumer does no read-modify-write and cannot lose updates by its own RMW race (`ProductRatingService.apply` just sets fields, `product-service/.../ProductRatingService.java:26-29`). Two concurrent submits can each snapshot an aggregate that misses the other row (last event wins, self-corrects on next event). Out-of-order delivery would regress the snapshot — prevented per-aggregate by the relay claim clause (§1), except after KFK-2-style relay bugs (order relay only) or duplicate republish windows (KFK-11, benign: identical payload).

---

## 9. media-service

- **Upload flow:** `POST /api/v1/backoffice/medias` guarded `hasRole('ADMIN')` (`BackofficeMediaController.java:26,32-39`) → allowlist content-type (`MediaFormats.formatOfContentType:31-42`) → magic-byte sniff of first 32 bytes (`MediaUploadServiceImpl.java:69-75`) → 10 MB cap (`:77-81`, servlet cap `UploadLimitsConfig.java:14-22`) → SHA-256 dedup (`:86-92`, DB partial unique `uk_medias_sha256`) → decode-dimension/heap budget (`VariantRenderer.decode:63-99`) → variant render → S3 put → DB row + MediaCreated outbox event in one TX (`:150-155`); orphan purge on any failure (`:132-137,160-172`).
- **Presigned URLs:** GET-only presign (`S3ObjectStorageService.presignedGetUrl:196-204`); **no presigned PUT is issued anywhere** — an unauthenticated user cannot obtain an upload URL. Media *resolve* (`GET /api/v1/medias/{id}`) issues a 302 to the presigned URL (`MediaPublicController.java:26-35`); variant whitelist (`MediaQueryServiceImpl.java:43-46`) and UUID-keyed objects (`MediaUploadServiceImpl.objectKey:206-208`) close path traversal. Bucket ACL asserted private at boot (`BucketBootstrap.java:41-48`). TTL default **7 days** (`media-service/application.yml:63`) — MEDIA-1.
- **Public-read exposure:** presigned URLs are bearer URLs valid 7 days for anyone who obtains them (referrer/log leakage); bucket itself is private. `shop.security.public-paths: []` (`media-service/application.yml:43`) — resolve requires a JWT.

---

## 10. favourite / search — cache-aside

- **favourite-service:** **no cache at all** — no Redis/Spring Cache usage in source (grep `Redis|Cacheable` → 0 hits); Postgres-backed with soft delete and ownership-scoped queries (`FavouriteServiceImpl`, `FavouriteRepository`). Nothing to invalidate; **stale-read severity: n/a**. Also no Kafka — deleted products leave favourites dangling (product service emits ProductDeleted but nobody consumes it for favourites; user-visible dead favourite entries until user removes them — P3 cosmetic, not tracked as a finding).
- **search-service:** ES is the projection; event application is idempotent by external versioning (`ProductEventVersion.forEvent` epoch-nanos, `ProductEventVersion.java:16-41`; stale = ES 409 → logged + skipped, `ProductSearchService.java:70-74`). Stale reads come from three bounded windows: (a) relay lag (2 s poll + Kafka), (b) ES refresh, (c) full-reindex swap race (KFK-8). Manual full reindex exists as compensation (`POST /api/v1/backoffice/search/reindex`, `BackofficeSearchController.java:26-37`).

---

## 11. Spring Kafka config in common-kafka — security & semantics

- **Deserializer trust:** consumers use plain `StringDeserializer` for key and value (`BaseKafkaListenerConfig.java:113-121`) — **no Java-object deserialization, no `TRUSTED_PACKAGES`, no Jackson polymorphic typing on the wire** → no classic deserialization gadget surface. `JsonKafkaDeserializer` (`serialization/JsonKafkaDeserializer.java`) requires an explicit target class, is not wired into any consumer factory.
- **Error deserializer / DLT:** `DeadLetterPublishingRecoverer` on `stringKafkaTemplate` or fallback own template (`BaseKafkaListenerConfig.java:62-103`); `FixedBackOff(1000, 3)` then DLT. Poison decode path throws `PoisonRecordException` (`BaseKafkaConsumer.java:66-71`).
- **Concurrency:** never configured → 1 consumer thread per listener per instance. With 1-partition auto-created topics (KFK-9) this is currently *consistent* but caps throughput.
- **Poll semantics:** `max.poll.records=500`, `max.poll.interval.ms=300000`, `session.timeout.ms=45000`, `enable.auto.commit=false`, commit per batch.
- **Missing-events fail mode:** none — no topic-existence validation, no lag/miss detection; `auto.offset.reset=earliest` protects new groups, but a group offline longer than broker retention silently loses events (no replay mechanism except search's manual reindex). NEEDS_RUNTIME_VERIFICATION of broker retention (compose sets none → image default 168 h).
- **Latent trap:** default `kafkaTemplate` bean wires deprecated `JsonKafkaSerializer`, which on serialization failure silently sends `data.toString()` bytes (`JsonKafkaSerializer.java:28-37`); `KafkaMessagePublisher.publish` also calls `value.toString()` (`KafkaMessagePublisher.java:54-57`) with no type guard — safe today only because every caller passes pre-serialized JSON strings (KFK-7).

---

## FINDINGS

Severity: P0 data-loss/money-loss now, P1 likely production incident, P2 real functional/security gap, P3 hygiene/latent.

---

### KFK-1 — Poison records are silently ack-skipped by search & media-deleted consumers (DLT path bypassed)
- **Severity:** P2 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java:29-54`; `product-service/src/main/java/com/shop/productservice/kafka/MediaDeletedConsumer.java:35-84`; contrast `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:66-71` + `BaseKafkaListenerConfig.java:62-65`
- **Current behavior:** both consumers do their own `decode()` inside a try/catch that logs and returns; `MediaDeletedConsumer` additionally swallows *every* exception from `clearReference` (not just `TransientDataAccessException`) at `:47-49` after its 3×200 ms retry loop ack-skips at `:58-61`. `BaseKafkaConsumer`'s `PoisonRecordException`→DLT machinery is never reached because the exception never escapes the listener.
- **Failure window:** (1) producer adds/changes a field so JSON binds differently or corrupt payload lands → (2) decode throws → (3) log.error → (4) offset commits → event never in DLT, never replayable; for media-deleted: product keeps `image_url` pointing at a media row that the purge job hard-deletes after the 30 d grace (`MediaPurgeJob.java:26-62`) → permanently broken product images until manual repair.
- **Business consequence:** stale/incorrect search results and broken product images with **no dead-letter trace** — silent divergence between product-service and its projections.
- **Fix + pattern:** let decode failures throw `PoisonRecordException` (use `BaseKafkaConsumer.processMessage(rawValue, headers, Type.class, …)` like the other five consumers); in `clearWithBoundedRetry`, catch only `TransientDataAccessException` and rethrow the rest so the shared `DefaultErrorHandler` retries and DLTs. Add a reconciliation sweep in product-service as defense-in-depth (the log message references one; no such scheduled sweep exists in product-service source).
- **Regression test:** publish an undecodable payload to both topics and assert (a) listener does not commit past the record, (b) record lands in `<topic>.DLT` after 3 retries; publish a `MediaDeleted` with product DB down → assert DLT, not ack.

### KFK-2 — order-service outbox relay: blocking per-row claim, no per-aggregate single-flight → out-of-order publishes possible
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `order-service/src/main/java/com/shop/orderservice/service/impls/OrderOutboxRelay.java:126-147` (`findFirstByStatusOrderByIdAsc` loop), `order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java:23-25` (plain `PESSIMISTIC_WRITE`, no SKIP LOCKED); contrast `payment-service/.../PaymentOutboxRelay.java:136` / `rating-service/.../OutboxEventRepository.java:28-38`
- **Current behavior:** claims rows one at a time with no `NOT EXISTS (SENDING for aggregate)` guard, unlike all 7 other relays. Two order-service instances can concurrently hold events of the same order (instance A publishing id=1 while instance B already publishes id=2); two producers → per-partition arrival order undefined.
- **Failure window:** order created (event 1) and immediately confirmed (event 2) during a deploy with 2 replicas → event 2 delivered before event 1 → shipping creates shipment from CONFIRMED before the created event lands (guarded, ok) but notification emits "Order X → CONFIRMED" **before** "Order X created"; created email still sent afterwards (out of order, not lost). Also instance B's claim can block on A's row lock (no lock-timeout hint).
- **Business consequence:** out-of-order customer emails and downstream confusion; no money/stock impact (consumers are state-guarded).
- **Fix + pattern:** adopt the rating/media claim query verbatim (advisory lock + `NOT EXISTS SENDING` + `FOR UPDATE SKIP LOCKED`, per-aggregate batches) — it is already proven in 7 other modules.
- **Regression test:** two-branch test claiming rows of the same aggregate in different transactions asserting the second claim returns empty; integration test with 2 relay instances asserting per-key publish order.

### KFK-3 — Outbox `FAILED` is terminal in rating/product/order (silent event loss; rating has zero backlog observability)
- **Severity:** P2 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `rating-service/.../outbox/RatingOutboxRelay.java:30` (claimable = PENDING only) and `:220-234` (FAILED terminal); `rating-service/.../outbox/OutboxRetentionScheduler.java:22-27` (purges SENT only); `rating-service/.../metrics/RatingMetrics.java:16-18` (no outbox meter); `product-service/.../service/OutboxRelay.java:34`; same pattern in order/inventory/payment/promotion relays; contrast media `MediaOutboxRelay.java:30-31` + `MediaOutboxRetentionScheduler.java:37-42`
- **Current behavior:** after `max-retries` (10) failed publishes the row is parked `FAILED`; only media retries FAILED forever (then DEAD + `media_outbox_dead_total`) and shipping ages to DEAD with `shipping.outbox.failed.count`. Rating additionally exposes **no** pending/FAILED gauge and no alert — a dead broker or a poison payload permanently kills product-rating events with only a `log.error`.
- **Failure window:** Kafka down >10 relay cycles (10×2 s + claim churn) while ratings are submitted → events exhaust retries → parked FAILED → relay resumes but never re-drives → product avgRating/ratingCount and ES aggregates freeze at stale values **forever** for the affected products.
- **Business consequence:** customer-visible wrong product ratings; no metric fires; remediation requires manual DB surgery.
- **Fix + pattern:** unify on the media semantics (FAILED stays due; age to DEAD after retention with counter) or add a DEAD-letter webhook/metric; add `shop_outbox_pending{module}` / `shop_outbox_failed{module}` gauges to all 8 relays; alert on gauge > 0 for N minutes.
- **Regression test:** force 11 publish failures → assert row re-driven (or aged DEAD + counter) instead of parked; assert gauge reflects FAILED rows.

### KFK-4 — DLT is a dead end: replay endpoint never enabled, no DLT metrics/consumers
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `utils/common-kafka/.../config/KafkaAutoConfiguration.java:63-70` (`shop.kafka.dlq-replay.enabled` gate); grep across all `application.yml` → property set nowhere; `DeadLetterReplayEndpoint.java:23-39` (ADMIN-gated but dormant)
- **Current behavior:** `DeadLetterReplayEndpoint` bean is created only when `shop.kafka.dlq-replay.enabled=true` — no service sets it → the replay capability is dead code; nothing consumes or measures `<topic>.DLT`.
- **Failure window:** any record reaching DLT (payment captured, shipping delivered, order lifecycle…) sits there until manual `kafka-console-consumer` surgery.
- **Business consequence:** recoverable failures stay unrecovered (e.g., a transient DB outage longer than 3 retries permanently skips order-confirm events unless replayed by hand).
- **Fix + pattern:** enable the endpoint in production profiles (it is `@PreAuthorize("hasRole('ADMIN')")` already, `DeadLetterReplayEndpoint.java:24`), export a `kafka_dlt_records_total` counter via a `DeadLetterPublishingRecoverer` wrapper, and add a runbook for `POST /actuator/deadLetterReplay`.
- **Regression test:** context test asserting the endpoint bean exists when the property is set in the prod profile; wire test replaying a canned DLT record back to the original topic.

### KFK-5 — Notification initial-delivery claim race can double-send (no lock/version on the first claim)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `notification-service/.../service/impls/NotificationServiceImpl.java:76` (`nextRetryAt = now`), `:88-93` (markSending→deliver); `notification-service/.../service/NotificationWriter.java:32-43,88-94` (read-modify-write claim, no `@Version` — `AbstractMappedEntity.java:16-25` has none); `NotificationRepository.java:29-39` (PENDING rows with `nextRetryAt <= now` are scheduler-claimable); `NotificationRetryScheduler.java:73-88`
- **Current behavior:** a freshly inserted notification is `PENDING` with `nextRetryAt=now` — immediately claimable by the retry scheduler on the *other* replica, which marks SENDING and delivers, while the original consumer thread's unlocked `markSending` also succeeds and delivers. Both paths pass the `status==SENDING` gate in `markSent`.
- **Failure window:** insert commits at t0; scheduler poll (60 s cadence) on replica B fires in the sub-second window while replica A is between `insert` and `deliver` → two SMTP sends for one event.
- **Business consequence:** duplicate email to a single recipient — P3, acceptable but should be fixed since the machinery for a CAS claim already exists.
- **Fix + pattern:** make the claim atomic: `UPDATE notifications SET status='SENDING', next_retry_at=:deadline WHERE id=:id AND status IN ('PENDING','FAILED_RETRYABLE')` and require `rowCount==1` before delivering (same pattern as `consumeIfUnused` in auth).
- **Regression test:** two threads claim the same notification; assert exactly one `deliver()` and one send.

### KFK-6 — Order emails can never reach customers: payloads carry no recipient, SMTP falls back to one shared mailbox
- **Severity:** P2 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `order-service/.../OrderEventPublisherImpl.java:38-53` (payload fields — no email); `notification-service/.../service/impls/NotificationServiceImpl.java:75,132-138` (payload stored verbatim); `notification-service/.../service/sender/SmtpNotificationSender.java:65-85` (`recipientEmail`/`email` lookup → always misses → `fallbackRecipient` at `:84`)
- **Current behavior:** in SMTP mode every notification (order totals, items, statuses of *all* users) is delivered to `SMTP_FALLBACK_RECIPIENT`. Customers receive nothing; one mailbox receives everyone's order data.
- **Failure window:** always, whenever `SMTP_ENABLED=true`.
- **Business consequence:** "order confirmation email" feature is non-functional; order PII consolidated into a single inbox (privacy/compliance exposure).
- **Fix + pattern:** include recipient contact in the event (email or a userId the notification service can resolve via auth-service), or have notification-service call auth-service for the user's email keyed by `userId`; keep fallback only as an error path (alert when used).
- **Regression test:** contract test asserting `OrderLifecycleEvent` carries a resolvable recipient; integration test asserting `resolveRecipient` returns the customer address, fallback only when absent.

### KFK-7 — Serializer fallback traps: `toString()` payloads can silently corrupt the wire
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE (code), impact inference
- **Files:** `utils/common-kafka/.../serialization/JsonKafkaSerializer.java:28-37` (catch → `data.toString().getBytes()`, deprecated but wired as the default `kafkaTemplate` value serializer, `KafkaAutoConfiguration.java:29-37`); `utils/common-kafka/.../producer/KafkaMessagePublisher.java:54-57` (`value.toString()` unconditionally)
- **Current behavior:** today all producers pass pre-serialized JSON strings, so nothing breaks. Any future caller that (a) uses the default `kafkaTemplate` with a POJO that fails Jackson serialization, or (b) passes a non-String to `KafkaMessagePublisher.publish`, puts a Java `toString()` blob on the topic; consumers then DLQ it (typed) or ack-skip it (KFK-1 consumers).
- **Failure window:** first misuse after a refactor — no compile-time guard.
- **Business consequence:** silent schema corruption; hard to diagnose (payload looks like `com.shop...Event@1f2a`).
- **Fix + pattern:** delete `JsonKafkaSerializer` (already `@Deprecated forRemoval`) and wire `StringSerializer` for the default template; in `KafkaMessagePublisher`, require `String` (overload or `instanceof` guard throwing `KafkaPublishException`).
- **Regression test:** compile-level: publisher signature accepts only `String`; context test asserting no `JsonKafkaSerializer` bean remains.

### KFK-8 — Full reindex race: events consumed during reindex are applied to the old index and lost from the new one
- **Severity:** P3 | **Confidence:** HIGH_CONFIDENCE_INFERENCE
- **Files:** `search-service/.../service/impls/ReindexServiceImpl.java:57-70` (build → swap → delete), `:91-121` (bulk targets `newIndex` directly), consumer path writes via alias (`ProductSearchService.write:55-69`), `:37` per-instance `AtomicBoolean` gate
- **Current behavior:** during `fullReindex`, incoming product events go to the *old* index (alias not yet swapped). After the swap, the new index contains the snapshot as-of page-read; any event whose version is newer than its document but was consumed before the swap is never re-applied to the new index (ES external-versioning only rejects *older* writes; nobody replays skipped-in-time events). Two search-service replicas can also run concurrent reindexes (the gate is per-JVM) and compute colliding `products-vN+1` names.
- **Failure window:** product updated between its snapshot page read and `swapAlias` → new index serves pre-update price/title until the next event for that product or the next reindex.
- **Business consequence:** bounded but untracked stale search data after every reindex.
- **Fix + pattern:** during reindex, dual-write to both indices or replay the topic into the new index after swap (versioning makes replay safe); make the in-progress flag cluster-wide (e.g., a distributed lock/row) or key index names by a run id.
- **Regression test:** integration test — update product between snapshot and swap, assert new index contains the update; two replicas start reindex concurrently → second gets `SEARCH_REINDEX_IN_PROGRESS`.

### KFK-9 — Topics are unmanaged: auto-created with broker defaults (1 partition, RF=1, 7-day retention)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE (absence of provisioning); broker defaults NEEDS_RUNTIME_VERIFICATION
- **Files:** `docker-compose.yml:81-108` (no `KAFKA_NUM_PARTITIONS`, no topic definitions, internal-topic RF=1); no topic-provisioning code (grep: only `KafkaTopicContract` metadata); `KafkaProperties.java:61-87` (client-side only)
- **Current behavior:** first produce auto-creates each of the 10 topics with image defaults; single broker with RF=1 everywhere.
- **Failure window:** (a) broker disk loss = total event loss incl. in-flight outbox semantics (rows already SENT are never re-sent); (b) any consumer group offline > retention silently loses events (notification loses emails forever — no reconciliation exists); (c) 1 partition caps consumer parallelism permanently.
- **Business consequence:** dev-grade durability in the stack that carries payment/shipping state transitions.
- **Fix + pattern:** declare topics in compose (`KAFKA_CREATE_TOPICS`-style init or a init-container running `kafka-topics.sh --create --partitions N --replication-factor 1 --if-not-exists`) with partition counts matching expected consumers; at minimum set `log.retention.hours` explicitly; document that production needs RF≥3.
- **Regression test:** integration assert `TopicDescription` partition/replica counts equal contract expectations at context startup.

### KFK-10 — Missing composite index on outbox claim path (rating & media)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `rating-service/.../db/changelog/changelog-001-ratings.yaml:45-56` + `002/003` (indexes: status, claimed_at, lease_owner — **no aggregate_id**); `media-service/.../db/changelog/changelog-002-outbox.yaml:7-14` + 003-005 (same); claim query filters `WHERE e.aggregate_id = :aggregateId AND e.status IN (...)` (`OutboxEventRepository.java:28-38` both services)
- **Current behavior:** per-aggregate claim resolves via `idx_outbox_status` then filters by aggregate; with a large backlog each aggregate claim scans O(pending rows); `findClaimableAggregates` mitigates by pre-selecting aggregates, but the per-aggregate query still degrades as PENDING grows.
- **Failure window:** backlog spike (broker outage) → relay loop slows exactly when it must catch up (self-reinforcing lag).
- **Business consequence:** slow event recovery after incidents.
- **Fix + pattern:** `CREATE INDEX idx_outbox_aggregate_status ON outbox_events (aggregate_id, status);` in both changelogs.
- **Regression test:** changelog integration test asserting the index exists (informational schema assert); explain-plan smoke test under 10k backlog rows.

### KFK-11 — At-least-once duplicate windows and the media event contract gap (design note, no action-blocking defect)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `KafkaMessagePublisher.publishBatch:87-127` (timeout → unacked-but-maybe-delivered records re-queued), `RatingOutboxRelay.markFinalState:210-236` (separate mark TX), `MediaEventService.java:42-52` (media events carry no `eventId`)
- **Current behavior:** relay crash/timeout between broker ack and DB mark → one duplicate publish per event after lease reclaim (§1). Downstream dedup is **present and sufficient today**: notification `uk_notification_event_id`; search external versioning; order idempotency service + state guards; shipping `existsByOrderId` + state guards; product rating re-applies identical snapshot; media consumer clear is idempotent. **Exception:** media events have no `eventId`, so future consumers of `media.lifecycle.v1` cannot dedup by ID — only by payload.
- **Failure window:** every relay crash costs exactly one duplicate per in-flight event; consumers absorb it.
- **Business consequence:** none today; contract debt for media.
- **Fix + pattern:** add `eventId` to media payloads (one-line, `MediaEventService.record`) to match all other producers.
- **Regression test:** contract test asserting every outbox payload contains a UUID `eventId` (extend the existing `KafkaTopicContractTest` style).

---

### AUTH-1 — Registration is a two-phase write with compensating delete only (crash windows)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `auth-service/.../service/impls/UserServiceImpl.java:64-85` (local save → Keycloak create → link), `:109-115` (`rollbackLocalUser` only on Keycloak *exception*)
- **Current behavior:** `register` is not transactional (only `@LogPerformance`); three persistent systems involved (local DB, Keycloak user, role links inside Keycloak). Crash after local save → orphan row with `keycloak_user_id=NULL` that still occupies the partial-unique username/email (`003-partial-unique-users.yaml:17-39`) → user cannot re-register; crash after Keycloak create but before local link → user can log in (ROPC is Keycloak-only) but every `resolvePlatformUserId` call fails (`UserServiceImpl.java:184-190`) → orders/addresses broken for that account.
- **Business consequence:** stuck registrations requiring manual cleanup in two systems.
- **Fix + pattern:** make Keycloak create *first* with the Keycloak-generated id, then create the local row in one TX (rollback deletes the Keycloak user — the helper `rollbackKeycloakUser` already exists at `:259-265` but is unused); add a scheduled reconciler for rows with `keycloak_user_id IS NULL` older than N minutes.
- **Regression test:** fail the local insert after KC create → assert KC user deleted; seeded orphan row → reconciler removes it and frees the username.

### AUTH-2 — No account lockout/brute-force protection; ROPC posture warn-only
- **Severity:** P2 | **Confidence:** CONFIRMED_FROM_SOURCE (realm template, call path) / runtime defaults NEEDS_RUNTIME_VERIFICATION
- **Files:** `docker/keycloak/import/ecommerce-realm.json:29-36` (`ecommerce-client` public, `directAccessGrantsEnabled: true`; no `bruteForceProtected` key anywhere in the import); `utils/common-keycloak/.../KeycloakTokenClient.java:41-52` (password grant login); `auth-service/.../health/KeycloakPasswordGrantPostureCheck.java:44-49` (log.warn only); `AuthController.java:34-38` (public `/login`)
- **Current behavior:** public login endpoint proxies a ROPC password grant for a public client; neither code nor realm import enables brute-force detection/lockout or a password policy; `verifyCredentials` (change-password old-pw check) also performs full ROPC logins.
- **Failure window:** internet-facing `/api/v1/auth/login` with no rate limit/lockout → unlimited credential stuffing; the in-code posture check acknowledges the risk but only logs.
- **Business consequence:** account takeover at scale; ATO then reaches orders/addresses/payment methods.
- **Fix + pattern:** enable Keycloak brute-force detection + password policy in the realm import; migrate first-party clients to Authorization Code + PKCE and disable direct grants (the migration path is already worded in the posture check); add request-level rate limiting on `/login`, `/forgot-password`.
- **Regression test:** realm-import contract test asserting `bruteForceProtected: true`, `passwordPolicy` non-empty, and `directAccessGrantsEnabled: false` after migration; integration test asserting temporary lockout after N bad logins.

### AUTH-3 — Usable secret defaults shipped in config (`changeme`, `admin/admin`)
- **Severity:** P2 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `search-service/src/main/resources/application.yml:29`, `rating-service/src/main/resources/application.yml:45`, `media-service/src/main/resources/application.yml:59` (`client-secret: ${...:changeme}`); DB `admin` defaults e.g. `rating-service/application.yml:8-10`, `media-service/application.yml:8-10`, `notification-service/application.yml:8-10`, `search-service/application.yml` datasource via shared, `favourite-service/application.yml:8-10`, `auth-service/application.yml:18-22`; admin secret fails safe (`application-shared.yml:161` — no default)
- **Current behavior:** a deployment that forgets one env var silently runs with well-known credentials against the realm/db.
- **Failure window:** any env-var omission during provisioning.
- **Business consequence:** service-account or DB compromise with a guessable secret.
- **Fix + pattern:** follow the existing fail-fast pattern used for `SMTP_FALLBACK_RECIPIENT`/`PAYMENT_WEBHOOK_SECRET` (`?VAR must be set` syntax or startup validation); keep `.env` (git-ignored) as the only source.
- **Regression test:** context test asserting app startup fails when `RATING_SERVICE_CLIENT_SECRET` is unset (after defaults removed).

### AUTH-4 — Accounts created with `emailVerified=true` and no verification flow
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE (behavior); takeover path HIGH_CONFIDENCE_INFERENCE
- **Files:** `utils/common-keycloak/.../KeycloakAdminClient.java:515-538` (`emailVerified: true` at `:521`); no verify-email action anywhere in auth-service; reset flow trusts the email channel (`UserServiceImpl.forgotPassword:311-340`)
- **Current behavior:** any registration is instantly "verified". Email format is validated, not deliverability. A user who typos an email that resolves to someone else's controlled address hands that address the ability to receive this account's reset links (15-minute, single-use token — token handling itself is solid: hashed, `consumeIfUnused`, `PasswordResetTokenRepository.java:19-22`).
- **Business consequence:** niche account-takeover path; also breaks any future "verified email" gating.
- **Fix + pattern:** set `emailVerified=false` + `requiredActions=["VERIFY_EMAIL"]` (send the KC verify-email mail), or verify at registration before creating credentials.
- **Regression test:** register → assert realm user has `emailVerified=false` and verify action; forgot-password for unverified account → behavior defined by policy.

### AUTH-5 — Password policy mismatch on reset + SMTP-inside-transaction + token burned before Keycloak reset
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `auth-service/.../dto/request/ResetPasswordRequest.java:18-21` (min 6, no complexity) vs `RegisterRequest.java:18-22` / `ChangePasswordRequest.java:18-27` (min 8 + complexity); `UserServiceImpl.forgotPassword` `@Transactional` sends mail inside the TX `:311-340`; `resetPassword` consumes the token (`:360-362`) *before* `resetUserPassword` (`:364`)
- **Current behavior:** a user can reset to a 6-char password that registration would reject; SMTP latency holds a pooled DB connection + TX open; a Keycloak failure after `consumeIfUnused` forces the user to request a new token.
- **Business consequence:** weak-password entry point via reset; connection-pool pressure under SMTP stalls; minor reset UX failure.
- **Fix + pattern:** share one password validator across all three paths; move email send after commit (`TransactionSynchronization`/outbox); move Keycloak reset before consume, or re-issue token on failure.
- **Regression test:** reset with `Password1` (fails old policy) → 400 after fix; SMTP failure during forgot-password → token row persists.

### AUTH-6 — auth-service emits no lifecycle events (and disabled users keep valid access tokens)
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE (no events); token semantics HIGH_CONFIDENCE_INFERENCE
- **Files:** `auth-service` — no Kafka producer code (only the unused autoconfigured templates arrive via `common-spring`→`common-kafka`, `utils/common-spring/pom.xml:37`); `UserServiceImpl.delete:146-155` (KC disable + local soft-delete, session revoke not forced on disable path — `KeycloakAdminClient.updateUserEnabled:241-259` does logout)
- **Current behavior:** user registered/deleted/disabled/password-changed events are published nowhere (no outbox); downstream services keep their local copies of user-derived data (notification rows, ratings) with no invalidation signal. Disabled/deleted users' previously issued access tokens remain valid until expiry because resource servers validate issuer signature, not live status (standard JWT model; `revokeSessions` kills refresh/sessions only, `UserServiceImpl.revokeSessions:371-378`).
- **Business consequence:** soft-deleted user can finish an in-flight checkout with an existing token; no downstream reconciliation trigger.
- **Fix + pattern:** add an outbox + `shop.user.lifecycle.v1` (or accept and document the model); on account state change call the Keycloak not-before endpoint to invalidate outstanding tokens.
- **Regression test:** after delete, an old access token is rejected by resource servers (requires the not-before fix).

### MEDIA-1 — 7-day presigned GET TTL
- **Severity:** P3 | **Confidence:** CONFIRMED_FROM_SOURCE
- **Files:** `media-service/src/main/resources/application.yml:61-66` (`presign-ttl: ${MEDIA_PRESIGN_TTL:7d}`), `MediaQueryServiceImpl.resolve:56`, `S3ObjectStorageService.presignedGetUrl:196-204`
- **Current behavior:** every product-image resolution hands out a bearer URL valid 7 days for the `original` variant too (variant selectable). Bucket is private (ACL asserted, `BucketBootstrap.java:41-48`) and resolve requires JWT, but leaked URLs (browser history, proxies, referrer) keep working for a week, and originals are downloadable by anyone holding a URL.
- **Business consequence:** long-lived unauthenticated access to originals; cache/link-rot tradeoff is the counter-argument.
- **Fix + pattern:** cut TTL to minutes-hours (CDN friendly via stable CDN path + short signed cookies), exclude `original` from public resolve.
- **Regression test:** config assert default TTL ≤ 1 h; resolve of `variant=original` rejected/redirected per policy.

### MEDIA-2 — Verified-safe (recorded as positive control, not a defect)
- Upload requires ADMIN; no presigned PUT exists (`BackofficeMediaController.java:26-39`, grep: only GET presign in `S3ObjectStorageService`); content-type allowlist + magic-byte sniff + 10 MB cap + decode budget (`MediaUploadServiceImpl.java:62-107`, `VariantRenderer.java:63-99`); object keys are `UUID/variant.ext` from enums (`MediaUploadServiceImpl.java:206-208`); EXIF/GPS never persisted (variants re-encoded, `MediaMetadataInspector.java:19-37`); orphan purge on storage/DB failure (`MediaUploadServiceImpl.java:130-172`); private-ACL boot assertion (`BucketBootstrap.java:41-48`).

---

## Answers recap to audit questions (quick index)

1. Outbox — per-service, in-TX write, poller-relay with advisory-lock + SKIP LOCKED claims (order-service differs, KFK-2), at-least-once with downstream dedup (KFK-11), FAILED handling inconsistent (KFK-3), backlog metrics partial (KFK-3/KFK-4).
2. Producers — §2, all outbox-relayed, zero direct `kafkaTemplate.send` in business code.
3. Consumers — §3 with per-consumer redelivery consequence.
4. Topics — §4, list matches contract exactly; DLTs implicit and unmanaged (KFK-4); auto-created topics (KFK-9).
5. Contracts — §5, tolerant-reader JSON POJOs, no registry; add-field safe, rename/type-change → DLT or silent-null.
6. Auth — §6/AUTH-1..6; Keycloak via admin REST + ROPC; **no auth events published**.
7. Notification — §7/KFK-5/KFK-6; SMTP only, no Telegram exists; template injection: none.
8. Rating — §8; verified-purchase fail-closed ✓; unique partial index ✓; aggregate snapshot avoids RMW race ✓.
9. Media — §9/MEDIA-1/MEDIA-2; unauthenticated upload impossible; no traversal; private bucket; 7-day URL TTL.
10. Favourite/search — §10; favourite has no cache; search projection is version-guarded, reindex race KFK-8.
11. common-kafka config — §11; no trusted-packages surface; ack BATCH semantics; concurrency 1; no missing-events fail mode.

## Findings count by severity

| Severity | Count | IDs |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 5 | KFK-1, KFK-3, KFK-6, AUTH-2, AUTH-3 |
| P3 | 12 | KFK-2, KFK-4, KFK-5, KFK-7, KFK-8, KFK-9, KFK-10, KFK-11, AUTH-1, AUTH-4, AUTH-5, AUTH-6, MEDIA-1 |

(17 numbered findings; MEDIA-2 recorded as a verified-positive control.)

## Top-5 one-liners

1. **KFK-6 (P2)** — every SMTP order notification goes to one fallback mailbox because order events carry no recipient email (`SmtpNotificationSender.java:84`, `OrderEventPublisherImpl.java:38-53`).
2. **KFK-1 (P2)** — search and media-deleted consumers ack-skip poison/failed records with no DLT (`ProductSearchConsumer.java:33-36`, `MediaDeletedConsumer.java:47-49`) — silent projection divergence.
3. **KFK-3 (P2)** — rating outbox parks FAILED events forever with zero backlog metrics (`RatingOutboxRelay.java:30,220-234`, `RatingMetrics.java:16-18`) — product ratings can freeze silently.
4. **AUTH-2 (P2)** — no lockout/brute-force protection and ROPC enabled on a public client (`ecommerce-realm.json:36`, `KeycloakPasswordGrantPostureCheck.java:44-49`).
5. **AUTH-3 (P2)** — `changeme` service-account secret defaults shipped in search/rating/media configs (`search application.yml:29`, `rating:45`, `media:59`).
