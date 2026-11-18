# Kafka / Transactional-Outbox Source Review

**Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Git revision:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`
**Reviewers:** A5 (Kafka/Outbox Engineer), A4 (Distributed Systems Architect)
**Scope:** every Kafka producer/consumer, every transactional-outbox implementation, every inbox/idempotency/dedup table, every Liquibase changelog that creates an outbox/inbox/event table.
**Method:** source-only. Every claim below is backed by `file:line` evidence from the tree at the revision above. README/docs were **not** used as evidence; where a doc comment disagrees with the code, the code wins and the divergence is reported.

---

## 0. Executive verdict

| Area | Verdict |
| --- | --- |
| Topic catalog | **COMPLETE** — all 10 brief topics exist, are produced, and are declared in a single registry with a build-enforced contract test. |
| Outbox atomicity (business write + outbox insert in ONE tx) | **CORRECT in 8/8 implementations.** Verified per-service below. |
| Outbox claim / multi-replica safety | **CORRECT (no double-publish) in 8/8**, but **parallelism only in 2/8** — see KAF-01. |
| Consumer idempotency | **CORRECT in 7/7 consumers**, but only 3/7 have a *durable dedup record*; the other 4 rely on state-machine guards or external upsert semantics. See §4. |
| Producer transactions / EOS | **NOT USED anywhere** — no `transaction.id`, no `KafkaTransactionManager`. Delivery is at-least-once by design, and the design is internally consistent. See §6. |
| Poison-message handling | **MIXED** — DLT works for 6/7 listeners (proven by an embedded-Kafka test); product-service's media listener has **no** error handler at all. See KAF-05. |
| Defects found | **20** (2 HIGH, 8 MEDIUM, 10 LOW). |

---

## 1. TOPIC CATALOG

### 1.1 Brief-topic checklist (all 10 from the task brief)

All ten are **VERIFIED EXISTS** — names match the brief exactly, none is DIFFERENT NAME, none is NOT FOUND.

| # | Brief topic | Verdict | Declared at | Produced at |
| --- | --- | --- | --- | --- |
| 1 | `shop.order.lifecycle.v1` | **VERIFIED EXISTS** | `utils/common-kafka/src/main/java/com/shop/common/kafka/contract/KafkaTopicContract.java:70` | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java:31,87` |
| 2 | `shop.product.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:72` | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java:39,66` |
| 3 | `shop.category.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:81` | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalCategoryEventPublisher.java:38,65` |
| 4 | `shop.brand.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:82` | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalBrandEventPublisher.java:37,64` |
| 5 | `shop.inventory.events.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:79` | `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java:39,116` |
| 6 | `shop.payment.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:71` | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentEventPublisher.java:23,45` |
| 7 | `shop.shipping.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:74` | `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisherImpl.java:23,46` |
| 8 | `shop.rating.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:73` | `rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java:40,67` |
| 9 | `shop.promotion.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:80` | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/TransactionalPromotionEventPublisher.java:40,92` |
| 10 | `media.lifecycle.v1` | **VERIFIED EXISTS** | `KafkaTopicContract.java:75` | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaEventService.java:36,57` |

No topic literal exists outside the contract. `KafkaTopicContractTest.theContractDeclaresEveryTopicTheFleetPublishes` (`utils/common-kafka/src/test/java/com/shop/common/kafka/contract/KafkaTopicContractTest.java:123-149`) scans every production source for a literal matching `"(shop\.[a-z0-9.-]+\.v1|media\.lifecycle\.v1)"` and fails if any is not declared. **This is a genuinely strong guardrail — proven present, not assumed.**

### 1.2 Full catalog (topic → producer → consumer(s) → group id → partition key)

| Topic | Producer service | Event types emitted | Consumer service(s) | Consumer group id | Partition key | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| `shop.order.lifecycle.v1` | order-service | `order.created.v1`, `order.updated.v1`, `order.cancelled.v1` | notification-service, shipping-service | `notification-service`; `shipping-service` | `aggregateId.toString()` = **orderId** | produce `OrderEventPublisherImpl.java:52,67,78`; consume `notification-service/.../kafka/OrderEventConsumer.java:22-23`, `shipping-service/.../kafka/OrderEventConsumer.java:22-23`; key `order-service/.../impls/OrderOutboxRelay.java:142` |
| `shop.product.lifecycle.v1` | product-service | `ProductCreated`, `ProductUpdated`, `ProductDeleted` | search-service | `search-service` | **productId** | produce `TransactionalProductEventPublisher.java:47,52,57`; consume `search-service/.../kafka/ProductSearchConsumer.java:36-37`; key `product-service/.../service/OutboxRelay.java:143` |
| `shop.category.lifecycle.v1` | product-service | `CategoryCreated`, `CategoryUpdated`, `CategoryDeleted` | **none (FIRE_AND_FORGET)** | — | **categoryId** | produce `TransactionalCategoryEventPublisher.java:38,65`; mode `KafkaTopicContract.java:108-112`; key `OutboxRelay.java:143` |
| `shop.brand.lifecycle.v1` | product-service | `BrandCreated`, `BrandUpdated`, `BrandDeleted` | **none (FIRE_AND_FORGET)** | — | **brandId** | produce `TransactionalBrandEventPublisher.java:37,64`; mode `KafkaTopicContract.java:113-117`; key `OutboxRelay.java:143` |
| `shop.inventory.events.v1` | inventory-service | `inventory.reserved.v1`, `inventory.committed.v1`, `inventory.released.v1`, `inventory.adjusted.v1`, `inventory.deleted.v1` (+ low-stock) | **none (FIRE_AND_FORGET)** | — | **productId** | produce `TransactionalInventoryEventPublisher.java:39,116`; mode `KafkaTopicContract.java:99-103`; key `inventory-service/.../InventoryOutboxRelay.java:142-143`; aggregate `TransactionalInventoryEventPublisher.java:114` |
| `shop.payment.lifecycle.v1` | payment-service | `payment.captured.v1`, `payment.failed.v1`, `payment.refunded.v1` | order-service | `order-service-payment-group` | **paymentId** | produce `PaymentEventPublisher.java:23,45`; consume `order-service/.../kafka/PaymentLifecycleConsumer.java:21-24`; key `payment-service/.../PaymentOutboxRelay.java:142`; aggregate `PaymentEventPublisher.java:36` |
| `shop.shipping.lifecycle.v1` | shipping-service | `shipping.delivered.v1` | order-service | `order-service` | **shipmentId** | produce `ShippingEventPublisherImpl.java:23,46`; event type `ShippingEventPublisherImpl.java:24`; consume `order-service/.../kafka/ShippingDeliveredConsumer.java:22-23`; key `shipping-service/.../ShippingOutboxRelay.java:137`; aggregate `ShippingEventPublisherImpl.java:37` |
| `shop.rating.lifecycle.v1` | rating-service | `rating.submitted.v1` (actions CREATED/UPDATED/HIDDEN/UNHIDDEN carried in payload `action` field) | product-service | `product-service` | **productId** | produce `rating-service/.../service/RatingEventService.java:39,66-67`; consume `product-service/.../kafka/ProductRatingConsumer.java:21-22`; key `rating-service/.../RatingOutboxRelay.java:129-130`; aggregate `RatingEventService.java:65` |
| `shop.promotion.lifecycle.v1` | promotion-service | `promotion.reserved.v1`, `promotion.committed.v1`, `promotion.released.v1` | **none (FIRE_AND_FORGET)** | — | **campaignId** | produce `TransactionalPromotionEventPublisher.java:40,55,68,83,92`; mode `KafkaTopicContract.java:104-107`; key `promotion-service/.../PromotionOutboxRelay.java:142`; aggregate `TransactionalPromotionEventPublisher.java:90` |
| `media.lifecycle.v1` | media-service | `MediaCreated`, `MediaDeleted` | product-service | `product-service` | **mediaId** | produce `MediaEventService.java:36,57,62-63`; consume `product-service/.../kafka/MediaDeletedConsumer.java:66-67`; key `media-service/.../MediaOutboxRelay.java:140`; aggregate `MediaEventService.java:55` |

**Consumer group identity is deliberate and pinned.** Every `@KafkaListener` hard-codes its `groupId` in the annotation (never inherits `shop.kafka.consumer.group-id`). `KafkaListenerGroupIdContractTest` (`utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/KafkaListenerGroupIdContractTest.java:35-62`) scans every production source and fails if any listener omits `groupId`. **CORRECT — proven.**

**Note on `product-service`'s two listeners.** `ProductRatingConsumer` and `MediaDeletedConsumer` deliberately share the group id `product-service` (`ProductRatingConsumer.java:21`, `MediaDeletedConsumer.java:66` with the rationale at `:62-65`). This is safe because they bind to **different topics**; sharing an id across different topics only affects the offset namespace per topic, and each topic's offsets are independent. **CORRECT.**

### 1.3 Partition-key and ordering contract

All 8 relays set the partition key to `aggregateId.toString()` — the aggregate id, never the event id. Evidence: `OrderOutboxRelay.java:142`, `OutboxRelay.java:143` (product), `MediaOutboxRelay.java:140`, `RatingOutboxRelay.java:129-130`, `ShippingOutboxRelay.java:137`, `PaymentOutboxRelay.java:142`, `PromotionOutboxRelay.java:142`, `InventoryOutboxRelay.java:142-143`. Producer side confirms `enable.idempotence=true`, `acks=all`, `max.in.flight.requests.per.connection=1` (`utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:83-91,154-163`) — the canonical recipe that preserves per-partition order across retries. **CORRECT.**

---

## 2. FILE INVENTORY

`git rev-parse HEAD:<path>` truncated to 12 hex chars. "Ranges read" is the portion actually inspected; `full` = entire file read line-by-line.

### 2.1 Shared Kafka module (`utils/common-kafka`)

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `0dad22d91556` | 148 | `utils/common-kafka/src/main/java/com/shop/common/kafka/contract/KafkaTopicContract.java` | full |
| `18a700dd1700` | 246 | `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java` | full |
| `b2ae406e8ddf` | 103 | `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java` | full |
| `48e5423bb2bb` | 276 | `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/KafkaMessagePublisher.java` | full |
| `fa87a8bcec55` | 58 | `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/OutboxBatchOrdering.java` | full |
| `bf45c30d1e21` | 118 | `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java` | full |
| `c6d4cbee91b0` | 113 | `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java` | full |
| `e81602785fb5` | 8 | `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/PoisonRecordException.java` | full |
| `fbd2fdd4be1e` | 15 | `utils/common-kafka/src/main/java/com/shop/common/kafka/exception/KafkaPublishException.java` | full |
| `96f6350d4b26` | 145 | `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java` | full |
| `5267be97bfee` | 52 | `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayEndpoint.java` | 1-30 (read tool) + 1-52 via cat |
| `4f9cc943d718` | 68 | `utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaSerializer.java` | full |
| `19a00c040c4e` | 67 | `utils/common-kafka/src/main/java/com/shop/common/kafka/serialization/JsonKafkaDeserializer.java` | full |
| `efbc1cfa6cfa` | 260 | `utils/common-kafka/src/test/java/com/shop/common/kafka/contract/KafkaTopicContractTest.java` | 1-160 |
| `bd714cd1ed3f` | 116 | `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/KafkaListenerGroupIdContractTest.java` | 1-80 |
| `fbe72e0b2d6f` | 175 | `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaConsumerDeadLetterTest.java` | 1-170 |
| `02c98a69696c` | 21 | `utils/common-core/src/main/java/com/shop/common/core/constants/OutboxStatus.java` | full |
| `fa7d1aac90e3` | 46 | `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java` | full |

### 2.2 order-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `77413c8b921e` | 58 | `order-service/src/main/java/com/shop/orderservice/entity/OutboxEvent.java` | full |
| `c8cc3a0d570d` | 87 | `order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java` | full |
| `64259d1fefd7` | 257 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderOutboxRelay.java` | full |
| `05b99c1d8af5` | 115 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java` | full |
| `68baa8d67711` | 556 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` | 145-300, 300-400, structural grep of 59-516 |
| `1faac24ea532` | 255 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java` | 100-260 |
| `97bdad32ab76` | 35 | `order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java` | structural grep (23-32) |
| `55f4c29ee516` | 44 | `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleConsumer.java` | full |
| `6423fefbc54d` | 28 | `order-service/src/main/java/com/shop/orderservice/kafka/ShippingDeliveredConsumer.java` | full |
| `bec45d15abc0` | 26 | `order-service/src/main/java/com/shop/orderservice/kafka/PaymentListenerConfig.java` | full |
| `b7c7c732d2c0` | 27 | `order-service/src/main/java/com/shop/orderservice/kafka/ShippingListenerConfig.java` | full |
| `3f80b2a10292` | 23 | `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleEvent.java` | full |
| `b87c6cc505bd` | 24 | `order-service/src/main/java/com/shop/orderservice/dto/ShippingDeliveredEvent.java` | full |
| `afcc2f2637e3` | 46 | `order-service/src/main/java/com/shop/orderservice/service/ShippingDeliveredHandler.java` | full |
| `6c70508b0b99` | 184 | `order-service/src/test/java/com/shop/orderservice/repository/OrderOutboxClaimConcurrencyIT.java` | 55-184 |
| `cbc392216563` | 19 | `order-service/src/main/resources/db/changelog/changelog-002-outbox-pending-index.yaml` | full |
| `f95e12784f9a` | 21 | `order-service/src/main/resources/db/changelog/008-order-outbox-claim-lease.yaml` | full |
| `c4862e0682c8` | 237 | `order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | 205-240 |

### 2.3 product-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `aa84200e597a` | 54 | `product-service/src/main/java/com/shop/productservice/entity/OutboxEvent.java` | full |
| `fdf67fb5f640` | 98 | `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java` | full |
| `bfab1f596532` | 258 | `product-service/src/main/java/com/shop/productservice/service/OutboxRelay.java` | full |
| `584f4ca61362` | 45 | `product-service/src/main/java/com/shop/productservice/service/OutboxRetentionScheduler.java` | structural grep (32-41) |
| `16269716dbff` | 102 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java` | full |
| `9c2f14c0f50b` | 83 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalBrandEventPublisher.java` | full |
| `4669a42b80b0` | 84 | `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalCategoryEventPublisher.java` | structural grep (22-65) |
| `3a21f98f8ab8` | 92 | `product-service/src/main/java/com/shop/productservice/service/impls/BrandServiceImpl.java` | structural grep (29-92) |
| `f54fd8f011d6` | 156 | `product-service/src/main/java/com/shop/productservice/service/impls/CategoryServiceImpl.java` | structural grep (30-155) |
| `be77c451f981` | 39 | `product-service/src/main/java/com/shop/productservice/service/ProductRatingService.java` | full |
| `84fadd4bab0e` | 74 | `product-service/src/main/java/com/shop/productservice/service/ProductMediaService.java` | 44-74 |
| `d3188d30e063` | 123 | `product-service/src/main/java/com/shop/productservice/kafka/MediaDeletedConsumer.java` | full |
| `1c24fedf0b9e` | 27 | `product-service/src/main/java/com/shop/productservice/kafka/ProductRatingConsumer.java` | full |
| `52a530281078` | 44 | `product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleListenerConfig.java` | full |
| `447065459db7` | 26 | `product-service/src/main/java/com/shop/productservice/kafka/RatingLifecycleListenerConfig.java` | full |
| `a2ee37fedbfd` | 29 | `product-service/src/main/java/com/shop/productservice/kafka/RatingLifecycleEvent.java` | full |
| `941ad4fb1af2` | 17 | `product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleEvent.java` | full |
| `999eaea5c6c1` | 186 | `product-service/src/test/java/com/shop/productservice/service/ProductOutboxClaimConcurrencyIT.java` | 1-186 |
| `71184eb0d8e6` | 20 | `product-service/src/main/resources/db/changelog/changelog-002-outbox-pending-index.yaml` | full |

### 2.4 media-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `232d89059348` | 61 | `media-service/src/main/java/com/shop/mediaservice/outbox/OutboxEvent.java` | full |
| `30297ca6f6d0` | 105 | `media-service/src/main/java/com/shop/mediaservice/outbox/OutboxEventRepository.java` | full |
| `bb2505c72f79` | 256 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRelay.java` | full |
| `7e120bdcfba7` | 83 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaEventService.java` | full |
| `9088d674e5ff` | 47 | `media-service/src/main/java/com/shop/mediaservice/outbox/MediaOutboxRetentionScheduler.java` | full |
| `cb9c137eddaa` | 85 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaLifecycleServiceImpl.java` | 40-70 |
| `93a37b17bcb2` | 248 | `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaUploadServiceImpl.java` | 150-185 |
| `34048ebd94d2` | 186 | `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxClaimConcurrencyIT.java` | 1-120 |
| `43eb3e30fcb5` | 14 | `media-service/src/main/resources/db/changelog/changelog-002-outbox.yaml` | full |
| `177033dfa5df` | 18 | `media-service/src/main/resources/db/changelog/changelog-003-outbox-aging.yaml` | full |
| `f2f52d077379` | 14 | `media-service/src/main/resources/db/changelog/changelog-004-media-outbox-claim-lease.yaml` | full |

### 2.5 rating-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `2f04a92e63ca` | 58 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEvent.java` | structural grep |
| `048f659e161e` | 86 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEventRepository.java` | structural grep (30-86) |
| `fbfd993aedf9` | 240 | `rating-service/src/main/java/com/shop/ratingservice/outbox/RatingOutboxRelay.java` | structural grep (63-208) |
| `f989fbd38eea` | 106 | `rating-service/src/main/java/com/shop/ratingservice/service/RatingEventService.java` | structural grep + 39-92 |
| `3cf4f115f4ad` | 43 | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxRetentionScheduler.java` | structural grep (30-39) |
| `ea96f02ad962` | 138 | `rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java` | 29-75 |
| `c8c9d505d317` | 184 | `rating-service/src/test/java/com/shop/ratingservice/outbox/RatingOutboxClaimConcurrencyIT.java` | structural grep (54-119) |
| `e41a8d48b9d1` | 18 | `rating-service/src/main/resources/db/changelog/changelog-002-rating-outbox-claim-lease.yaml` | full |

### 2.6 shipping-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `f3044f3561ee` | 58 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEvent.java` | structural grep |
| `6ed9abe244de` | 86 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java` | structural grep (30-86) |
| `09774511930a` | 251 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRelay.java` | structural grep (67-220) |
| `1728b62be4f6` | 8 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisher.java` | structural grep |
| `52f527329498` | 64 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisherImpl.java` | structural grep (20-62) |
| `0cc5563c3fce` | 33 | `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRetentionScheduler.java` | structural grep (20-29) |
| `05e7b2c87e21` | 33 | `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentWriter.java` | full |
| `bf2facd23d85` | 42 | `shipping-service/src/main/java/com/shop/shippingservice/service/WebhookEventWriter.java` | structural grep |
| `dff78300f3da` | 28 | `shipping-service/src/main/java/com/shop/shippingservice/kafka/OrderEventConsumer.java` | full |
| `fbf965ed34d8` | 27 | `shipping-service/src/main/java/com/shop/shippingservice/kafka/ShippingListenerConfig.java` | full |
| `2868cb6fb6a5` | 21 | `shipping-service/src/main/java/com/shop/shippingservice/dto/OrderLifecycleEvent.java` | full |
| `cf5acb201028` | 184 | `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxClaimConcurrencyIT.java` | structural grep (54-119) |
| `60bf6c4079ed` | 43 | `shipping-service/src/main/resources/db/changelog/changelog-002-shipping-outbox.yaml` | full |
| `0abc0e940cd1` | 13 | `shipping-service/src/main/resources/db/changelog/006-shipping-outbox-claim-lease.yaml` | full |
| `3bf986c75400` | 84 | `shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml` | 14-43 |

### 2.7 payment-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `df0cf95a2db8` | 61 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEvent.java` | 40-62 + structural |
| `3df60dd01acd` | 91 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEventRepository.java` | full |
| `96c8abec3b97` | 256 | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java` | 100-200 + structural |
| `57aeca128bee` | 63 | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentEventPublisher.java` | full |
| `ac9a21a4e48e` | 92 | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxRetentionScheduler.java` | structural grep (66-84) |
| `d5a59220332a` | 82 | `payment-service/src/main/java/com/shop/paymentservice/service/PaymentWriter.java` | 74-100 + structural |
| `3a4f8b432d27` | 183 | `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxClaimConcurrencyIT.java` | 54-183 |
| `a3e1f0753c35` | 43 | `payment-service/src/main/resources/db/changelog/changelog-002-payment-outbox.yaml` | full |
| `2602249c631c` | 30 | `payment-service/src/main/resources/db/changelog/changelog-005-outbox-retention.yaml` | full |

### 2.8 promotion-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `0d93391b50f4` | 83 | `promotion-service/src/main/java/com/shop/promotionservice/entity/OutboxEvent.java` | structural grep |
| `044448a5f603` | 93 | `promotion-service/src/main/java/com/shop/promotionservice/repository/OutboxEventRepository.java` | structural grep (32-93) |
| `fef736bfea52` | 257 | `promotion-service/src/main/java/com/shop/promotionservice/service/PromotionOutboxRelay.java` | structural grep (69-226) |
| `d47647db7160` | 39 | `promotion-service/src/main/java/com/shop/promotionservice/service/OutboxRetentionScheduler.java` | structural grep (26-35) |
| `376323f7283b` | 111 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/TransactionalPromotionEventPublisher.java` | structural grep (26-109) |
| `3da6beb76354` | 234 | `promotion-service/src/main/java/com/shop/promotionservice/service/impls/CampaignReservationServiceImpl.java` | structural grep (42-212) |
| `15280a9502ff` | 186 | `promotion-service/src/test/java/com/shop/promotionservice/service/PromotionOutboxClaimConcurrencyIT.java` | structural grep (56-121) |
| `dce0742c67bc` | 45 | `promotion-service/src/main/resources/db/changelog/changelog-002-outbox.yaml` | full |
| `a7cbe136bc49` | 21 | `promotion-service/src/main/resources/db/changelog/005-promotion-outbox-claim-lease.yaml` | full |

### 2.9 inventory-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `1aa3f13024d4` | 83 | `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxEvent.java` | structural grep |
| `d03462d41b39` | 94 | `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java` | structural grep (32-94) |
| `3b249d7564a8` | 257 | `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java` | structural grep (69-225) |
| `30be665dbc2c` | 39 | `inventory-service/src/main/java/com/shop/inventoryservice/service/OutboxRetentionScheduler.java` | structural grep (26-35) |
| `336a8b1cb716` | 135 | `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java` | structural grep (24-132) |
| `902567e5d522` | 279 | `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java` | structural grep (35-272) |
| `dc53800e9020` | 186 | `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxClaimConcurrencyTest.java` | 1-120 |
| `5a5f896b6861` | 21 | `inventory-service/src/main/resources/db/changelog/changelog-005-inventory-outbox-claim-lease.yaml` | full |

### 2.10 notification-service / search-service

| Blob | Lines | Path | Ranges read |
| --- | --- | --- | --- |
| `93dcd08a3f75` | 28 | `notification-service/src/main/java/com/shop/notificationservice/kafka/OrderEventConsumer.java` | full |
| `16ad2f0a5df4` | 27 | `notification-service/src/main/java/com/shop/notificationservice/kafka/NotificationListenerConfig.java` | full |
| `c24af105d23b` | 39 | `notification-service/src/main/java/com/shop/notificationservice/dto/OrderLifecycleEvent.java` | full |
| `76474b746c37` | 145 | `notification-service/src/main/java/com/shop/notificationservice/service/impls/NotificationServiceImpl.java` | 51-124 + structural |
| `647a3d3ef10c` | 43 | `notification-service/src/main/resources/db/changelog/changelog-001-notifications.yaml` | full |
| `a7ecefd1870e` | 71 | `search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java` | full |
| `89d2786d812a` | 31 | `search-service/src/main/java/com/shop/searchservice/kafka/SearchListenerConfig.java` | full |
| `6eb31da1f9a7` | 32 | `search-service/src/main/java/com/shop/searchservice/kafka/ProductLifecycleEvent.java` | full |
| `daedf01c9f65` | 72 | `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java` | 36-72 |

### 2.11 Configuration read

`*/src/main/resources/application.yml` for all 11 Kafka-participating services (full `kafka:`/`outbox:` blocks), plus `utils/common-spring/src/main/resources/application.yml:215-218`, `docker-compose.yml:88-108,389,436,461,508`, and the per-service `db/changelog/db.changelog-master.yaml` file lists.

---

## 3. OUTBOX CORRECTNESS — PER IMPLEMENTATION

**Eight** transactional-outbox implementations exist (the brief listed five): order, product, media, rating, shipping, payment, promotion, inventory.

### 3.0 Shared shape (all 8)

Every relay implements a **3-phase claim → publish → mark** cycle so the row lock is never held across a Kafka publish:

- **claim**: one short transaction per row; row moved `PENDING → SENDING`; claim timestamp stamped; commit.
- **publish**: the whole batch is published **outside any transaction** via `KafkaMessagePublisher.publishBatch` (`utils/common-kafka/.../producer/KafkaMessagePublisher.java:165-222`), which fans the sends out asynchronously and waits on a bounded `CountDownLatch` (`:171,205`) so the relay's wall-clock budget is the slowest single publish, not the sum.
- **mark**: one short transaction per row; compare-and-set re-read under the row's write lock.

The lock-never-held-across-publish invariant is the single most important correctness property of this design and it is **CORRECT in all 8**. Evidence: `OrderOutboxRelay.java:116-127` (claim tx), `:138-147` (publish, no tx), `:169-174` (mark tx); identical structure at `OutboxRelay.java:118-128,139-148,170-175`, `MediaOutboxRelay.java:114-125,136-145,166-171`, `RatingOutboxRelay.java:102-113,132-133,158`, `ShippingOutboxRelay.java:110-118,141-142,168`, `PaymentOutboxRelay.java:118-128,139-145,154-162`, `PromotionOutboxRelay.java:115-123,146-147,173`, `InventoryOutboxRelay.java:115-123,145-146,172`.

---

### 3.1 order-service — **ATOMIC, CORRECT**

**(a) Business write + outbox insert in ONE transaction — YES.**

Three separate write paths, all verified:

| Path | Transaction boundary | Outbox insert | Proof |
| --- | --- | --- | --- |
| create | `OrderCreateSaga.executeInTxWithoutResult(...)` | `orderEventPublisher.publishCreated(order, orderItems)` at `OrderCreateSaga.java:225` — **inside** the lambda at `:219-226` | `OrderCreateSaga.java:219` opens the tx, `:220-224` writes order + items + cart, `:225` writes the outbox row — one lambda, one tx |
| cancel | `cancelTransactionTemplate.execute(tx -> applyCancelledStatus(order))` | `applyCancelledStatus` → `orderRepository.save(order)` `:273` then `orderEventPublisher.publishCancelled(order)` `:275` | `OrderServiceImpl.java:263-265` opens the tx; `:273` and `:275` are in the same method body |
| confirm | `transactionTemplate.execute(tx -> { ... })` | `order.setStatus(CONFIRMED)` `:358`, `orderRepository.save(order)` `:359`, `orderEventPublisher.publishStatusChanged(order)` `:360` | `OrderServiceImpl.java:355-362` — status change and outbox row commit together |

`OrderEventPublisherImpl` is deliberately **not** `@Transactional` (`order-service/.../impls/OrderEventPublisherImpl.java:25-28` — no annotation on the class or on `save()`), so it *joins* the caller's transaction via the shared `EntityManager`. `outboxRepository.save(event)` at `:103` therefore commits or rolls back with the order row. **VERIFIED CORRECT.**

⚠️ Conditional caveat: both `cancelOrder` (`:263`) and `confirmOrder` (`:355`) have a `transactionTemplate == null` fallback (`applyCancelledStatus` / `applyConfirmedStatus` run **non-transactionally**). That fallback only fires on the test-only constructor (`OrderServiceImpl.java:129-146`, which passes `null` for the tx manager). In the Spring-wired production bean the template is non-null. Noted in KAF-16.

**(b) How are pending rows claimed — `SELECT ... FOR UPDATE SKIP LOCKED` — YES.**

```java
// order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java:36-38
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
Optional<OutboxEvent> findFirstByStatusOrderByIdAsc(OutboxStatus status);
```

`jakarta.persistence.lock.timeout = -2` is the Hibernate/Spring-portable spelling of `SKIP LOCKED`. The derived query becomes `... WHERE status = ? ORDER BY id ASC LIMIT 1 FOR UPDATE SKIP LOCKED`, and PostgreSQL skips the locked row and continues the scan → **the peer claims the NEXT row**. Proven empirically by the service's own test:

```java
// order-service/src/test/java/com/shop/orderservice/repository/OrderOutboxClaimConcurrencyIT.java:175-176
// With findFirstByStatusOrderByIdAsc + SKIP LOCKED, row1 is skipped, and B claims row2!
assertThat(claimBWhileLocked.get().map(OutboxEvent::getId)).contains(row2);
```

**A lease column is present and used:** `claimed_at` (`OutboxEvent.java:56-57`), added by `order-service/src/main/resources/db/changelog/008-order-outbox-claim-lease.yaml:11-20` and indexed. The stale sweep is `reclaimStaleSending` (`OutboxEventRepository.java:75-86`):

```java
"UPDATE OutboxEvent e SET "
+ "e.status = CASE WHEN e.retryCount + 1 >= :maxRetries THEN :failed ELSE :pending END, "
+ "e.retryCount = e.retryCount + 1, "
+ "e.lastError = :reason "
+ "WHERE e.status = :sending AND e.claimedAt < :cutoff"
```

driven by `OrderOutboxRelay.recoverStrandedClaims()` (`:190-203`) with `cutoff = now - claimLeaseMs` (`:191`, lease default `60_000` ms at `:85-86`).

**(c) Are concurrent publishers safe on multiple replicas — YES.** SKIP LOCKED guarantees two replicas never hold the same row. The mark phase is a compare-and-set: `markFinalState` re-reads under `PESSIMISTIC_WRITE` and bails if the status is no longer `SENDING`:

```java
// OrderOutboxRelay.java:208-211
OutboxEvent e = outboxRepo.findByIdForUpdate(event.getId()).orElse(null);
if (e == null || e.getStatus() != OutboxStatus.SENDING) {
    return;  // the sweep already moved this row; skip.
}
```

This closes the read-then-write race the repository javadoc describes (`OutboxEventRepository.java:46-55`). **CORRECT.**

**(d) Publish failure handling.** `KafkaMessagePublisher.publishBatch` returns an index-aligned per-record outcome list (`KafkaMessagePublisher.java:260-275`); a record that did not complete before the deadline is reported as a failure (`:213-219`). The relay marks only the failed rows back to `PENDING`, incrementing `retry_count`, and parks the row as `FAILED` once the budget is spent (`OrderOutboxRelay.java:217-227`). `maxRetries` default 10 (`OrderOutboxRelay.java:74-75`; `order-service/src/main/resources/application.yml` `order.outbox.max-retries: 10`). **Backoff**: there is no *time-based* backoff between relay ticks — the retry interval is the relay poll interval (`order.outbox.poll-interval-ms: 5000`). Acceptable for an outbox. **Poison handling**: a row that exhausts its budget is parked as `FAILED` and is **never automatically replayed**; no DLT, no operator alerting path in code (only `log.error` at `:222-223`). See KAF-11.

**(e) Can an event be published more than once — YES (at-least-once, by design).** The window is the crash between broker ACK and the DB status update: the relay publishes (`:146-147`), then updates the row (`:173`). A crash in between leaves the row `SENDING`; the lease sweep returns it to `PENDING` and it is published again. This is inherent to the pattern and is documented as such (`OutboxBatchOrdering.java:22-24`: "This is at-least-once: a blocked record may be published twice. The consumer must stay idempotent"). The consumer-side contract holds (§4).

**(f) Unique constraints / ordering guarantees.** `event_id varchar(36) NOT NULL UNIQUE` (`OutboxEvent.java:19-20`; DB: `order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` §outbox_events). The uniqueness is on the *event* id only — it prevents UUID collisions, **not** duplicate insertion of the same business fact. Ordering: the batch is published in ascending-`id` order (`OrderOutboxRelay.orderById` `:244-247`, applied at `:138`), and a failed record blocks later records **of the same aggregate** via `OutboxBatchOrdering.markableAsSent` (`:166`; helper at `utils/common-kafka/.../producer/OutboxBatchOrdering.java:39-57`). **Ordering is CORRECT for order-service.**

**(g) Aging / retention cleanup — YES.**
```java
// order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java:25-31
@Scheduled(cron = "${order.outbox.retention-cron:0 0 3 * * *}")
...
int deleted = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
```
`order.outbox.retention-days: 7`, cron `0 0 3 * * *` (`order-service/src/main/resources/application.yml`). The `SENT`-only purge is the finding in KAF-11. Partial index for the drain: `CREATE INDEX idx_outbox_pending_id ON outbox_events (id) WHERE status = 'PENDING'` (`order-service/src/main/resources/db/changelog/changelog-002-outbox-pending-index.yaml:16-19`).

---

### 3.2 product-service — **ATOMIC, CORRECT** (two caveats: KAF-01, KAF-07)

**(a) ONE transaction — YES.** `TransactionalProductEventPublisher` is deliberately **not** `@Transactional` — its javadoc states the reason explicitly:

> "The publisher itself is intentionally NOT `@Transactional`: the caller's transaction (e.g. `ProductServiceImpl.create/update/delete`) provides the boundary. If no transaction is open the row would still be committed individually, defeating atomicity — by participating in an existing transaction we guarantee the entity change and the outbox row either both commit or both roll back."
> — `product-service/src/main/java/com/shop/productservice/service/impls/TransactionalProductEventPublisher.java:26-31`

Callers verified `@Transactional`: `BrandServiceImpl.java:57` (`create`) → `publisher.publishCreated(saved)` `:64`; `:69-70` (`update`) → `:79`; `:84-85` (`delete`) → `:91`. `CategoryServiceImpl.java:78` → `:93`; `:98-99` → `:114`; `:148-149` → `:155`. `ProductRatingService.java:25` (`@Transactional`) → `productRepository.save(product)` `:36` then `productEventPublisher.publishUpdated(saved)` `:37`. `ProductMediaService.java:44` (`@Transactional`) → `productRepository.save(product)` `:54` then `productEventPublisher.publishUpdated(saved)` `:55`. **All inside the transaction. VERIFIED CORRECT.**

**(b) Claim — `SELECT ... FOR UPDATE SKIP LOCKED`, but via a `MIN(id)` subquery.**
```java
// product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java:35-39
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
@Query("SELECT e FROM OutboxEvent e WHERE e.status = :status AND e.id = ("
     + "SELECT MIN(e2.id) FROM OutboxEvent e2 WHERE e2.status = :status)")
Optional<OutboxEvent> claimOnePending(@Param("status") OutboxStatus status);
```
**No lease column** — the lease is `updated_at` (`OutboxEventRepository.java:78`: `WHERE e.status = :sending AND e.updatedAt < :cutoff`); the entity has no `claimed_at` field (`product-service/.../entity/OutboxEvent.java:18-53` extends `AbstractMappedEntity`, whose `updated_at` is `@LastModifiedDate`, `utils/common-core/.../AbstractMappedEntity.java:22-24`). See **KAF-07**.

**(c) Concurrent publishers safe — YES, but they serialise — KAF-01.** See §3.9 for the cross-service analysis; the product-service proof is its own test asserting the peer gets *nothing*:
```java
// product-service/src/test/java/com/shop/productservice/service/ProductOutboxClaimConcurrencyIT.java:39-42 (assertions of the 2nd test)
// assertThat(claimBWhileLocked.get()).isEmpty();
// assertThat(outboxRepo.findById(row2).map(OutboxEvent::getStatus)).contains(OutboxStatus.PENDING);
```
test name: `twoPendingRows_secondRowUntouchedWhileHeadRowLocked`.

**(d)-(g)** Same shape as order-service: per-record outcome marking (`:151-157`), `markableAsSent` ordering barrier (`:167`), `FAILED` parking at `maxRetries` (`:221-227`), compare-and-set mark (`:206-231`), retention `SENT`-only purge (`product-service/.../OutboxRetentionScheduler.java:32-37`, 7 days).

---

### 3.3 media-service — **ATOMIC, CORRECT — the most complete implementation**

**(a) ONE transaction — YES.** `MediaEventService.record` is annotated `@Transactional(propagation = Propagation.REQUIRED)` (`media-service/.../outbox/MediaEventService.java:48`) so it *joins* the caller's transaction. Callers: `MediaLifecycleServiceImpl.softDelete` `@Transactional` (`:47`) → `mediaRepository.softDelete(mediaId, actor)` `:62` → `mediaEvents.record(media, MediaEventType.MediaDeleted)` `:67` — same tx. `MediaUploadServiceImpl.upload` → `transactionTemplate.execute(tx -> { mediaRepository.saveAndFlush(media); mediaEvents.record(persisted, MediaEventType.MediaCreated); ... })` (`:160-165`) — same tx. **VERIFIED CORRECT.**

**(b) Claim.** `claimOneDue` with `status IN (PENDING, FAILED)` and the same `MIN(id)` subquery (`media-service/.../outbox/OutboxEventRepository.java:40-44`). **Has** a dedicated `claimed_at` column (`OutboxEvent.java:54-60`, added by `media-service/src/main/resources/db/changelog/changelog-004-media-outbox-claim-lease.yaml:12-14`) plus a `failed_at` column (`OutboxEvent.java:48-49`, `changelog-003-outbox-aging.yaml:16-18`).

**(c) Safe on multiple replicas — YES** (same serialisation caveat, KAF-01).

**(d) Publish failure → retry, and FAILED is explicitly NOT terminal.** `markFinalState` (`MediaOutboxRelay.java:213-227`) parks the row as `FAILED` with `failed_at = now`, and the relay's due-set is `List.of(PENDING, FAILED)` (`MediaOutboxRelay.java:70`) → **FAILED rows are replayed forever** until the aging sweep moves them to `DEAD`:
```java
// media-service/.../outbox/OutboxEventRepository.java:101-104
"UPDATE OutboxEvent e SET e.status = com.shop.common.core.constants.OutboxStatus.DEAD "
+ "WHERE e.status = com.shop.common.core.constants.OutboxStatus.FAILED AND e.failedAt < :cutoff"
```
This is the **only** service with a terminal-DEAD path. **CORRECT and best-in-class.**

**(e)** At-least-once, same window as §3.1(e). **(f)** `event_id UNIQUE` (`changelog-002-outbox.yaml:9`); per-media ordering barrier `markableAsSent` (`MediaOutboxRelay.java:163`). **(g)** Retention purges `SENT` older than the window AND ages `FAILED → DEAD` (`MediaOutboxRetentionScheduler.java:32,37`), with a WARN + `recordOutboxDead` metric (`:39-41`). **DEAD rows are never purged — KAF-12.**

---

### 3.4 rating-service — **ATOMIC, CORRECT**

**(a) ONE transaction — YES.** `RatingEventService.record` is `@Transactional(propagation = Propagation.REQUIRED)` (`rating-service/.../service/RatingEventService.java:54`) → joins the caller. `RatingServiceImpl.submit` opens the tx explicitly:
```java
// rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java:54-69
RatingResponse response = transactionTemplate.execute(status -> {
    Rating rating = Rating.builder()...build();
    Rating saved = ratingRepository.saveAndFlush(rating);   // :66
    ratingEventService.record(saved, RatingAction.CREATED);  // :67  <-- inside the same tx
    return RatingResponse.from(saved);
});
```
`edit` `@Transactional` (`:85`) → `record` `:96`; `hide` `:101` → `:117`; `unhide` `:122` → `:135`. **All atomic. VERIFIED CORRECT.**

**(b)** `claimOnePending` with the `MIN(id)` subquery (`rating-service/.../outbox/OutboxEventRepository.java:35-39`). **Has** `claimed_at` (`changelog-002-rating-outbox-claim-lease.yaml:11-18`). **(c)** Safe, serialised (KAF-01). **(d)** Retry → `FAILED` parking; FAILED is terminal for rating (no replay, no DEAD aging) — KAF-11. **(e)** At-least-once. **(f)** Per-aggregate ordering via `markableAsSent` (`RatingOutboxRelay.java:151`); aggregate = `productId` (`RatingEventService.java:65`) so all ratings of one product share a partition. **(g)** `SENT`-only purge (`rating-service/.../OutboxRetentionScheduler.java:30-39`, 7 days).

---

### 3.5 shipping-service — **ATOMIC, CORRECT**

**(a) ONE transaction — YES.** `ShipmentWriter.saveDelivered`:
```java
// shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentWriter.java:27-32
@Transactional
public Shipment saveDelivered(Shipment shipment, boolean autoDelivered) {
    Shipment saved = repository.save(shipment);          // :29
    publisher.publishDelivered(saved, autoDelivered);     // :30  <-- same tx
    return saved;
}
```
`ShippingEventPublisherImpl` is not `@Transactional` → joins. **VERIFIED CORRECT.**

**(b)** `claimOnePending` `MIN(id)` subquery (`shipping-service/.../outbox/OutboxEventRepository.java:35-39`); **has** `claimed_at` (`changelog-006-shipping-outbox-claim-lease.yaml:11-13`). **(c)** Safe, serialised. **(d)** Retry → `FAILED` terminal. **(e)** At-least-once. **(f)** Per-aggregate ordering via `markableAsSent` (`ShippingOutboxRelay.java:161`); aggregate = `shipmentId` (`ShippingEventPublisherImpl.java:37`). ⚠️ Note: `shop.shipping.lifecycle.v1` is keyed on **shipmentId**, but the consumer `ShippingDeliveredConsumer` acts on **orderId** — the payload carries both (`order-service/.../dto/ShippingDeliveredEvent.java:18-19`). Correct, since one shipment belongs to one order. **(g)** `SENT`-only purge (`ShippingOutboxRetentionScheduler.java:24-25`, 7 days).

---

### 3.6 payment-service — **ATOMIC, but MISSING THE ORDERING BARRIER (KAF-02)**

**(a) ONE transaction — YES.** `PaymentWriter.completeWithEvent`:
```java
// payment-service/src/main/java/com/shop/paymentservice/service/PaymentWriter.java:74-81
@Transactional
public Payment completeWithEvent(Payment payment, PaymentEvent event, String outboxEventType) {
    event.setStatus(PaymentEvent.STATUS_PROCESSED);
    eventRepository.saveAndFlush(event);          // :77
    Payment saved = repository.saveAndFlush(payment); // :78
    publisher.publish(saved, outboxEventType);    // :79  <-- same tx
    return saved;
}
```
`PaymentEventPublisher` is not `@Transactional` → joins. **VERIFIED CORRECT.**

**(b) Claim.** `findFirstByStatusOrderByIdAsc` + `PESSIMISTIC_WRITE` + SKIP LOCKED (`payment-service/.../outbox/OutboxEventRepository.java:35-37`), exposed as `claimOnePending` (`:39-41`). **No `claimed_at` column** — the lease is `updated_at` (`:84`: `WHERE e.status = :sending AND e.updatedAt < :cutoff`), and the entity uses Hibernate `@UpdateTimestamp` (`payment-service/.../outbox/OutboxEvent.java:58-60`). See KAF-07. **This is the *second* correct parallel-claiming implementation** — proven by `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxClaimConcurrencyIT.java:174-175`:
```java
// B claims the next available row (row2) concurrently because SKIP LOCKED skips row1
assertThat(claimBWhileLocked.get().map(OutboxEvent::getId)).contains(row2);
```

**(c) Safe on multiple replicas — YES**, and it parallelises correctly.

**(d)** Per-record outcome marking (`PaymentOutboxRelay.java:153-162`); retry → `FAILED` parking (`:206-212`). **(e)** At-least-once. **(f) ⚠️ ORDERING BARRIER MISSING — KAF-02.** The mark loop marks each row purely by its own publish outcome:
```java
// payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java:153-162
List<BatchOutcome.RecordOutcome> outcomes = outcome.outcomes();
for (int i = 0; i < rows.size(); i++) {
    BatchOutcome.RecordOutcome recordOutcome = (outcomes.size() == rows.size()) ? outcomes.get(i) : null;
    final boolean published = recordOutcome != null && recordOutcome.success();
    ...
    transactionTemplate.executeWithoutResult(tx -> markFinalState(row, published, error));
}
```
There is **no** `OutboxBatchOrdering.markableAsSent(...)` call anywhere in `PaymentOutboxRelay`. Every other relay has one: `OrderOutboxRelay.java:166`, `OutboxRelay.java:167` (product), `MediaOutboxRelay.java:163`, `RatingOutboxRelay.java:151`, `ShippingOutboxRelay.java:161`, `PromotionOutboxRelay.java:166`, `InventoryOutboxRelay.java:165`.

**(g)** Retention purges `SENT` (`OutboxRetentionScheduler.java:74-75`) and `DEAD` (`:83-84`) — but the payment relay never sets `DEAD`, so the DEAD purge is dead code and the DEAD path is unreachable (KAF-12).

---

### 3.7 promotion-service — **ATOMIC, CORRECT**

**(a) ONE transaction — YES.** `TransactionalPromotionEventPublisher` is not `@Transactional` (relies on the caller, per its javadoc at `:28-29`). Callers: `CampaignReservationServiceImpl` — `eventPublisher.publishReserved(campaign, saved)` `:132`, `publishCommitted` `:162`, `publishReleased` `:187` and `:212`; `ReservationCleanupScheduler.java:98`. `CampaignServiceImpl` methods are `@Transactional` (`:63,85,116`) but they publish *campaign* lifecycle events through the same publisher — and `TransactionalPromotionEventPublisher.java:47,59,72` (`publishReserved/Committed/Released`) are the reservation-flavoured ones. The `CampaignServiceImpl` publisher field is `PromotionEventPublisher` (`:42` of `CampaignReservationServiceImpl`), and `CampaignServiceImpl` publishes via `publisher.publishCreated/Updated/Deleted`? — the grep shows `CampaignServiceImpl` has no `promotionEventPublisher` field, so campaign CRUD does not emit events. The atomic claim that matters (reservation write + outbox row) holds: `CampaignReservationServiceImpl` methods are `@Transactional` and the publish call is inside them. **VERIFIED CORRECT** for the reservation path.

**(b)** `claimOnePending` `MIN(id)` subquery (`promotion-service/.../repository/OutboxEventRepository.java:37-41`); **has** `claimed_at` (`changelog-005-promotion-outbox-claim-lease.yaml:11-20`). **(c)** Safe, serialised. **(d)** Retry → `FAILED` terminal. **(e)** At-least-once. **(f)** Ordering barrier present (`PromotionOutboxRelay.java:166`); aggregate = `campaignId` (`:90`). **(g)** `SENT`-only purge (`promotion-service/.../OutboxRetentionScheduler.java:30-31`, 7 days).

---

### 3.8 inventory-service — **ATOMIC, CORRECT**

**(a) ONE transaction — YES.** `TransactionalInventoryEventPublisher` is not `@Transactional` (relies on the caller; javadoc `:24`). Callers in `InventoryServiceImpl` (all `@Transactional`): `create` `:73-74` → `publisher.publishAdjusted(saved)` `:81`; `update` `:87-88` → `:107`, `publishLowStock(saved)` `:109`; `delete` `:123-124` → `publishDeleted(existing)` `:132`; `reserve` `:137-138` → `publishReserved(inventory, reservation)` `:161`; `commit` `:167-168` → `publishCommitted(inventory, r)` `:197`, `publishLowStock(saved)` `:199`; `release` `:205-206` → `publishReleased(inventory, r, "PENDING")` `:234`; `releaseCommitted` `:246-247` → `publishReleased(inv, r, "COMMITTED")` `:267`. **Every publish call sits inside a `@Transactional` method. VERIFIED CORRECT.**

**(b)** `claimOnePending` `MIN(id)` subquery (`inventory-service/.../repository/OutboxEventRepository.java:37-41`); **has** `claimed_at` (`changelog-005-inventory-outbox-claim-lease.yaml:11-20`). **(c)** Safe, serialised. **(d)** Retry → `FAILED` terminal. **(e)** At-least-once. **(f)** Ordering barrier present (`InventoryOutboxRelay.java:165`); aggregate = `productId` (`TransactionalInventoryEventPublisher.java:114`). **(g)** `SENT`-only purge (`inventory-service/.../OutboxRetentionScheduler.java:30-31`, 7 days).

---

### 3.9 Cross-service claim strategy — the KAF-01 divergence

| Service | Claim query | SKIP LOCKED semantics | Parallel claim on 2 replicas? | Own test assertion |
| --- | --- | --- | --- | --- |
| order-service | `findFirstByStatusOrderByIdAsc` (`OutboxEventRepository.java:38`) | ORDER BY + LIMIT 1 → PG skips the locked row and continues the scan | **YES** | `OrderOutboxClaimConcurrencyIT.java:176` asserts B claims `row2` |
| payment-service | `findFirstByStatusOrderByIdAsc` (`OutboxEventRepository.java:37`) | same | **YES** | `PaymentOutboxClaimConcurrencyIT.java:175` asserts B claims `row2` |
| product-service | `MIN(id)` subquery (`:37-39`) | the WHERE pins exactly ONE candidate row; SKIP LOCKED drops it → **empty result** | **NO** | `ProductOutboxClaimConcurrencyIT.java:39,42` asserts B empty and `row2` still PENDING |
| inventory-service | `MIN(id)` subquery (`:39-41`) | same | **NO** | `InventoryOutboxClaimConcurrencyTest.java:121` `twoPendingRows_secondRowUntouchedWhileHeadRowLocked` |
| media-service | `MIN(id)` subquery (`:42-44`) | same | **NO** | `MediaOutboxClaimConcurrencyIT.java:121` same name |
| rating-service | `MIN(id)` subquery (`:37-39`) | same | **NO** | `RatingOutboxClaimConcurrencyIT.java:119` same name |
| promotion-service | `MIN(id)` subquery (`:39-41`) | same | **NO** | `PromotionOutboxClaimConcurrencyIT.java:121` same name |
| shipping-service | `MIN(id)` subquery (`:37-39`) | same | **NO** | `ShippingOutboxClaimConcurrencyIT.java:119` same name |

The 6 `MIN(id)` services' own tests **encode the serialisation as intended behaviour**, so this is a designed limitation, not an accident — but it is not what the repository javadoc claims ("a second relay instance can claim a different row immediately instead of waiting behind the publish", `MediaOutboxRelay.java:33-35`, `OrderOutboxRelay.java:36-38`). **See KAF-01.**

---

## 4. CONSUMER IDEMPOTENCY

**There is no generic inbox/dedup table in the fleet.** `grep -rni "inbox|dedup|processed_event|consumed_event"` across `.java` and `.yaml` returns only media-upload SHA-256 dedup and Stripe/shipping **webhook** dedup (`payment-service/.../service/WebhookEventService.java:35-38`, `payment-service/.../repository/PaymentEventRepository.java:17`) — those are HTTP-webhook concerns, not Kafka. The only durable dedup tables reachable from a Kafka consumer are `notifications.event_id` (UNIQUE) and `idempotency_keys` (PK `(actor, key)`).

| # | Consumer | Topic | Idempotent? | Mechanism | Durable dedup record? | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `notification-service/.../kafka/OrderEventConsumer.java:22` (group `notification-service`) | `shop.order.lifecycle.v1` | **YES** | Pre-check `repository.existsByEventId(eventId)` (`NotificationServiceImpl.java:61-64`) **plus** a UNIQUE index as the arbiter: `CREATE UNIQUE INDEX uk_notification_event_id ON notifications (event_id)` (`notification-service/src/main/resources/db/changelog/changelog-001-notifications.yaml:40`; entity `Notification.java:43-44`), with the race caught at `:82-85` (`DataIntegrityViolationException` → skip) | **YES** — UNIQUE index | **CORRECT** (caveat KAF-09) |
| 2 | `shipping-service/.../kafka/OrderEventConsumer.java:22` (group `shipping-service`) | `shop.order.lifecycle.v1` | **YES** | `repository.existsByOrderId(orderId)` pre-check (`ShipmentServiceImpl.java:80-83`) **plus** `CREATE UNIQUE INDEX uk_shipment_order_live ON shipments (order_id) WHERE deleted = false` (`shipping-service/.../changelog-001-shipments.yaml:40`), with `DataIntegrityViolationException` caught at `:102-104` | **YES** — partial UNIQUE index | **CORRECT** |
| 3 | `order-service/.../kafka/ShippingDeliveredConsumer.java:22` (group `order-service`) | `shop.shipping.lifecycle.v1` | **YES** | State-machine guard: `if (order.getStatus() != OrderStatus.SHIPPED) { ...no-op... }` (`ShippingDeliveredHandler.java:36-39`) — a replay sees `DELIVERED` and no-ops | Indirect (the order row's status) | **CORRECT** — naturally idempotent |
| 4 | `order-service/.../kafka/PaymentLifecycleConsumer.java:21` (group `order-service-payment-group`) | `shop.payment.lifecycle.v1` | **YES (double-guarded)** | (i) Durable dedup: `idempotencyService.begin(idempotencyKey, actor, requestHash)` with `idempotencyKey = "payment-confirm-" + event.paymentId()` (`PaymentLifecycleConsumer.java:34`) → `idempotency_keys` PK `(actor, key)` (`order-service/.../entity/IdempotencyKey.java:12,20-25`; DB `changelog-001-initial-schema.yaml:227-230`, actor rename `changelog-005-idempotency-actor.yaml:22-26`). (ii) State guard: `ORDER_INVALID_STATE_TRANSITION` is swallowed (`:36-39`) | **YES** — composite PK + 24 h TTL (`order-service/src/main/resources/application.yml` `order.idempotency.ttl-hours: 24`) | **CORRECT** |
| 5 | `product-service/.../kafka/ProductRatingConsumer.java:21` (group `product-service`) | `shop.rating.lifecycle.v1` | **YES** | "Dumb idempotent copy of the carried avgRating/ratingCount snapshot onto the product row. No recompute … replaying the same event overwrites with identical values." (`ProductRatingService.java:11-15`); `product.setAvgRating(...)` / `setRatingCount(...)` `:33-34` — a pure assignment, convergent under replay | **NO** — no dedup row | **CORRECT** (idempotent by state assignment, not by dedup) |
| 6 | `product-service/.../kafka/MediaDeletedConsumer.java:66` (group `product-service`) | `media.lifecycle.v1` | **YES** | `product.setMediaId(null)` (`ProductMediaService.java:52`) — a convergent set-to-null. Plus a bounded in-consumer retry for transient DB failures (`MediaDeletedConsumer.java:89-105`) | **NO** | **CORRECT** |
| 7 | `search-service/.../kafka/ProductSearchConsumer.java:36` (group `search-service`) | `shop.product.lifecycle.v1` | **YES** | Elasticsearch index by document id = `event.productId().toString()` (`ProductSearchService.java:43-46`) → last-write-wins upsert; delete is idempotent (`Result.NotFound` handled `:58-60`, 404 handled `:62-66`) | **NO** (external system provides it) | **CORRECT** |

**Conclusion for §4:** every consumer is idempotent, so the absence of a generic inbox table is **not** a CRITICAL finding for this fleet — but the task brief's premise does not hold. The reason is structural: **every payload is a full snapshot of the target state, and every handler is a convergent assignment** (copy-reader / upsert / set-to-null / state-machine guard), not an accumulator that would double-count on replay. The two consumers that perform *effects* rather than *assignments* (notification send, payment confirm) are the two that carry a durable UNIQUE/PK-backed dedup record.

**However**, this design has a sharp edge that must be recorded: **the payload being a snapshot is not enforced anywhere.** Producers build the payload from an ad-hoc `HashMap`/`LinkedHashMap` (§5). If a future event type carries a *delta* (e.g. "increment ratingCount by 1") rather than a snapshot, the consumer will silently double-apply it and there is no inbox table to catch it. That is the real CRITICAL-adjacent risk in this area, recorded as **KAF-14**.

---

## 5. EVENT SCHEMA

**Producer side — untyped maps, no shared schema.**
Every producer serialises a `HashMap` or `LinkedHashMap` to a JSON string:

| Producer | Payload construction |
| --- | --- |
| order-service | `OrderEventPublisherImpl.java:39-51` (`HashMap`), envelope `:89-93` |
| product-service | `TransactionalProductEventPublisher.java:68-90` (`HashMap`); brand `TransactionalBrandEventPublisher.java:66-71`; category `TransactionalCategoryEventPublisher.java` (same shape) |
| media-service | `MediaEventService.java:61-71` (`LinkedHashMap` — "Field order + names are contract (spec D4)") |
| rating-service | `RatingEventService.java:72-82` |
| payment-service | `PaymentEventPublisher.java:29-36` + envelope `:47-51` |
| shipping-service | `ShippingEventPublisherImpl.java:40-50` |
| promotion-service | `TransactionalPromotionEventPublisher.java:47-83` |
| inventory-service | `TransactionalInventoryEventPublisher.java` (same shape) |

Serialization is `objectMapper.writeValueAsString(payload)` and the string is stored in `outbox_events.payload` (`TEXT`). On the wire the payload is **single-encoded UTF-8 JSON** (`KafkaMessagePublisher` forwards the String raw, `KafkaMessagePublisher.java:111,177,240`); `JsonKafkaSerializer` (which would double-encode) is `@Deprecated(forRemoval = true)` (`utils/common-kafka/.../serialization/JsonKafkaSerializer.java:23-31`) and is only bound to the legacy `kafkaTemplate` bean — see KAF-17.

**Consumer side — typed records / POJOs, tolerant of unknown fields.**

| Consumer DTO | Kind | Unknown-field tolerance |
| --- | --- | --- |
| `notification-service/.../dto/OrderLifecycleEvent.java:24-38` | Java `record` (13 fields) | `@JsonIgnoreProperties(ignoreUnknown = true)` `:23` |
| `shipping-service/.../dto/OrderLifecycleEvent.java:14-20` | Lombok POJO (5 fields) | `:13` |
| `order-service/.../dto/ShippingDeliveredEvent.java:14-23` | Lombok POJO (8 fields) | `:13` |
| `order-service/.../kafka/PaymentLifecycleEvent.java:12-22` | Java `record` (9 fields) | `:11` |
| `product-service/.../kafka/RatingLifecycleEvent.java:15-29` | Java `record` (13 fields) | `:14` |
| `product-service/.../kafka/MediaLifecycleEvent.java:14-16` | Java `record` (2 fields) | `:13` |
| `search-service/.../kafka/ProductLifecycleEvent.java:14-32` | Java `record` (17 fields) | `:13` |

**Additive evolution is SUPPORTED.** Every consumer DTO carries `@JsonIgnoreProperties(ignoreUnknown = true)`, so a producer that adds a field does not break a consumer. The explicit rationale is at `search-service/.../ProductLifecycleEvent.java:10-12`: "Tolerates unknown fields so additive payload enrichment from product-service never breaks ingestion." **CORRECT — and proven at every one of the 7 DTOs.**

**The weakness is the producer side, not the consumer side.** Because the producer payload is an untyped map, a *producer-side* field rename or removal compiles and ships; the failure then appears only as a `null` inside the consumer (e.g. `event.orderId()` → `null` → a NOT NULL column violation). The consumer DTOs are also intolerant of **missing** fields (only of extra ones). Recorded as **KAF-10**.

**One decode path is not Jackson-typed:** `BaseKafkaConsumer.decodeContained` uses a static `ObjectMapper` with only `JavaTimeModule` registered (`utils/common-kafka/.../consumer/BaseKafkaConsumer.java:39-40`). It relies on each DTO's own `@JsonIgnoreProperties` to tolerate unknown fields — which every DTO does, so it works today, but the base class provides no safety net of its own. Recorded as part of **KAF-10**.

---

## 6. EXACTLY-ONCE ANALYSIS

### 6.1 Are producer transactions used? **NO.**

Exhaustive search across the whole repo:

```
grep -rn "transaction.id|transactionalId|TransactionalProducer|initTransactions|
           KafkaTransactionManager|transactional.id" --include=*.java --include=*.yml --include=*.yaml --include=*.properties .
→ NONE FOUND
```

- No `KafkaTransactionManager` bean exists.
- No `KafkaTemplate.executeInTransaction(...)` / `sendOffsetsToTransaction(...)` call exists.
- No `transaction.id` producer property is set anywhere.

The producer config is explicitly the **idempotent-but-not-transactional** recipe:

```java
// utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:81-95
props.put("acks", producer.getAcks());                       // "all"   :154
props.put("retries", producer.getRetries());                 // 3       :155
props.put("max.in.flight.requests.per.connection", 1);        //         :85
props.put("enable.idempotence", producer.isEnableIdempotence()); // true :163
```

`enable.idempotence=true` + `acks=all` + `max.in.flight=1` gives **exactly-once per producer session**: the broker de-duplicates retries of the same `(producerId, epoch, sequence)` batch. The producer javadoc states this correctly: "idempotent exactly-once-per-attempt delivery without per-service config" (`KafkaProperties.java:87-90`).

### 6.2 Why producer transactions would NOT make the system exactly-once anyway

Even if `transaction.id` were added tomorrow, it would **not** make the DB write + Kafka publish + consumer side effect atomic, for three independent reasons:

1. **XA is not in play.** A Kafka transaction can only be co-committed with a resource that speaks the Kafka transaction protocol. PostgreSQL does not. Making the Postgres commit and the Kafka commit atomic requires either a distributed transaction coordinator (XA/2PC — not implemented, and not implemented by any mainstream Postgres+Kafka stack) or the **outbox pattern** — which is what this codebase already uses.
2. **A Kafka transaction does not cover the consumer.** Kafka's EOS story is `read_committed` + `sendOffsetsToTransaction` on the *consumer* side, which atomically commits the consumer's offset and its own output records. It still does not include the consumer's **database** write. A consumer that reads a Kafka record and writes to Postgres is outside the Kafka transaction boundary no matter how the producer is configured.
3. **The producer's transaction would only cover the relay's publish, not the business write.** The business write happens in the request thread's Postgres transaction; the publish happens later in the relay thread. The only thing that binds them is the **outbox row**. A Kafka transaction would make "publish N records atomically" a fact; it would not make "publish N records iff the business write committed" a fact.

**What the codebase actually guarantees** — and this is the honest statement:

| Boundary | Guarantee | Mechanism | Evidence |
| --- | --- | --- | --- |
| business write → event durably recorded | **Atomic** | same DB transaction | §3, verified per service |
| outbox row → Kafka broker | **At-least-once** | 3-phase claim/publish/mark + lease | §3(d)-(e) |
| broker → consumer offset commit | **At-least-once** | `enable.auto.commit=false` | see below |
| consumer offset → consumer DB side effect | **At-least-once** | idempotent handler (§4) | §4 |

**`enable.auto.commit` — verified, not assumed.** `KafkaProperties.buildConsumerProperties()` (`:99-106`) does **not** set `enable.auto.commit`. The Kafka client default is `true` (auto-commit on the poll timer), which would break at-least-once. Spring Kafka neutralises this: `KafkaMessageListenerContainer$ListenerConsumer.determineAutoCommit(Properties)` sets `enable.auto.commit=false` when neither the consumer-factory configuration map nor the effective properties contain the key. Verified against the actual bytecode of the shipped dependency (`spring-kafka-4.1.1.jar`, `org/springframework/kafka/listener/KafkaMessageListenerContainer$ListenerConsumer.class`, `determineAutoCommit`):
```
 28: invokevirtual  // Map.containsKey
 31: ifnonnull 51
 35: aload_1
 36: ldc_w  // String enable.auto.commit
 39: ldc_w  // String false
 42: invokevirtual  // Properties.setProperty
```
**CORRECT, but the safety rests on a framework-internal default rather than on fleet-owned configuration** — recorded as **KAF-15**. (`DeadLetterReplayService` sets `ENABLE_AUTO_COMMIT_CONFIG, "false"` explicitly at `:75`, showing the awareness exists elsewhere in the codebase.)

---

## 7. MANDATORY SCENARIO ANALYSIS

### 7.1 Duplicate event delivery
**What happens:** the same record is delivered twice (broker redelivery after a rebalance, outbox re-publish after a relay crash, or a DLT replay).
**Actual behaviour:** the handler runs twice. Every one of the 7 consumers converges (§4): notification is short-circuited by `existsByEventId` **and** the `uk_notification_event_id` UNIQUE index (`NotificationServiceImpl.java:61-64`, `changelog-001-notifications.yaml:40`); shipment creation is short-circuited by `existsByOrderId` **and** `uk_shipment_order_live` (`ShipmentServiceImpl.java:80-83`, `changelog-001-shipments.yaml:40`); the order confirm replays the cached response from `idempotency_keys` (`IdempotencyServiceImpl.java:72-75, 91-98`); the rating/media/search handlers are convergent assignments.
**Verdict:** **handled correctly.** No double-notification, no double-shipment, no double-confirm, no double-count.

### 7.2 Consumer restart mid-processing
**What happens:** the consumer process dies after the handler's DB write but before the offset commit.
**Actual behaviour:** the offset was never committed (`enable.auto.commit=false`), so the record is redelivered on restart and the handler runs again — §7.1 applies. If the process dies *during* the handler's transaction, the Postgres transaction rolls back and nothing happened at all.
**Verdict:** **correctly handled**, subject to the consumer's idempotency (proven) — and to the DB transaction actually covering the side effect. Verified: `ProductRatingService.apply` is `@Transactional` (`:25`), `ShippingDeliveredHandler.handle` is `@Transactional` (`:25`), `NotificationServiceImpl.handle`'s insert goes through `writer.insert` (`NotificationServiceImpl.java:81`) — *but note that `NotificationServiceImpl.handle` is not itself `@Transactional`*, so the `existsByEventId` check at `:61` and the insert at `:81` are **not in one transaction**. That is safe here (the UNIQUE index is the real arbiter and the `DataIntegrityViolationException` is caught at `:82-85`), but it means the dedup relies on the DB constraint rather than on a serialisable check-then-act. Acceptable and correctly handled.

### 7.3 Publisher crash after Kafka ack but before DB status update
**What happens:** the relay publishes to the broker (broker ACKs), then the pod dies before `markFinalState` commits.
**Actual behaviour:** the row stays `SENDING` with `claimed_at` = claim time. After `claimLeaseMs` (60 s default, `OrderOutboxRelay.java:85-86`), the stale sweep returns it to `PENDING` (or `FAILED` if the retry budget is spent) and the relay publishes it **again**. → **duplicate delivery**, handled by §7.1.
**Verdict:** **correctly handled** — this is the textbook at-least-once window and the code closes it with the lease rather than losing the event.
**Sub-case — payment/product (no `claimed_at` column):** the lease is `updated_at`. A bulk `@Modifying` UPDATE does not trigger Hibernate's `@UpdateTimestamp`/`@LastModifiedDate`, so `reclaimStaleSending` leaves `updated_at` untouched — which is correct here. But the lease is coupled to a generic audit column (**KAF-07**).

### 7.4 Concurrent outbox pollers on 2 replicas
**What happens:** two relay instances poll the same table.
**Actual behaviour:** `PESSIMISTIC_WRITE` + `jakarta.persistence.lock.timeout = -2` (`FOR UPDATE SKIP LOCKED`) means the loser never sees the winner's row. **No double-publish, ever** — proven by 8 dedicated concurrency ITs.
**Actual throughput:** order-service and payment-service **parallelise** (peer claims the next row — `OrderOutboxClaimConcurrencyIT.java:176`, `PaymentOutboxClaimConcurrencyIT.java:175`). The other **six services serialise**: the `MIN(id)` subquery pins a single candidate row, SKIP LOCKED drops it when locked, and the peer gets an **empty** claim and stops claiming for that tick (`ProductOutboxClaimConcurrencyIT.java:39,42` and 5 sibling tests assert exactly this).
**Verdict:** **safe (no double-publish) in 8/8; productive (parallel) in only 2/8** — **KAF-01**.

### 7.5 Event replay
**What happens:** an operator republishes a topic (or replays a DLT) to rebuild a projection.
**Actual behaviour:** the replayed records are handled by the same idempotent handlers → convergent state, no duplication of effects (§7.1). The one caveat: replaying `shop.order.lifecycle.v1` will re-trigger the notification path, and although the `notifications.event_id` UNIQUE index prevents a second row, the row is already `PROCESSED`/delivered, so `writer.insert` raises `DataIntegrityViolationException` → caught at `NotificationServiceImpl.java:82-85` → skip. **Correct.**
**Caveat:** `DeadLetterReplayService.replay` (`:64-127`) republishes to the original topic with the original key and value but **drops the original headers** (`:105`: `new ProducerRecord<>(originalTopic, record.key(), record.value())`). The W3C `traceparent` header added by `KafkaMessagePublisher.traceparentRecord` (`:239-246`) is therefore lost on replay → the replayed record starts a new trace instead of linking to the original. Recorded as **KAF-18** (LOW).

### 7.6 Rebalance during processing
**What happens:** a consumer group rebalance occurs while a listener is mid-handler.
**Actual behaviour:** Spring Kafka's `DefaultErrorHandler` + `FixedBackOff` does not commit the failed record; the container revokes the partition, the new owner re-reads it from the last committed offset and the handler runs again → duplicate delivery → §7.1. The `ConsumerRebalanceListener` in Spring Kafka 4.x defers revocation until in-flight processing completes, so the handler is not interrupted mid-write.
**Verdict:** **correctly handled** (idempotency covers it). One consequence worth noting: a handler that takes longer than `max.poll.interval.ms` will trigger a rebalance storm. No service sets `max.poll.interval.ms` (`KafkaProperties.buildConsumerProperties()` `:99-106` sets only bootstrap/group/offset-reset/deserializer), so the Kafka client default of **300 000 ms (5 min)** applies. The longest handler is `orderService.confirmOrder` (remote payment check + commit coordinator). If it exceeds 5 min the consumer is evicted and the record is reprocessed — still safe (idempotent), but noisy. Recorded as **KAF-19** (LOW).

### 7.7 Poison message (deserialization failure) — **what actually happens**

**Two distinct paths, with different outcomes:**

**(a) Typed consumers (5 of 7) — `notification`, `order×2`, `shipping`, `product-rating`.**
The raw value is a `String`; the decode happens inside the handler:
```java
// utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:111-116
} catch (Exception ex) {
    log.error("Failed to decode Kafka payload for {} — routing to the dead-letter topic: {}",
            type.getSimpleName(), rawValue, ex);
    throw new PoisonRecordException(
            "Undecodable Kafka payload for " + type.getSimpleName(), ex);
}
```
The exception propagates out of the `@KafkaListener` method to the container's `CommonErrorHandler`:
```java
// utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:87-92
if (template != null) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
    factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
} else {
    factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
}
```
→ **3 retries at 1 s, then the record is published to `<original-topic>.DLT` and the offset advances. The consumer loop does NOT die and does NOT spin infinitely.**
**Proven, not assumed:** `BaseKafkaConsumerDeadLetterTest.unbindablePayload_isDeadLetteredAndNeverHandedToTheListener` (`utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaConsumerDeadLetterTest.java:83-94`) runs against a real `EmbeddedKafkaKraftBroker`, publishes `"{ this is not json }"`, and asserts (i) a record appears on the DLT topic and (ii) `PoisonConsumer.RECEIVED` is empty.

> ⚠️ **The javadoc contradicts the code.** `BaseKafkaConsumer.java:20-22` says "Decode failures are contained ack-skips — the listener method never throws and the offset always advances", and `:49-50` says "A decode failure logs at ERROR and ack-skips without invoking the handler (fleet containment: no DLT, no container crash)". The code throws and **does** route to a DLT. `KafkaProperties.java:113-118` repeats the same wrong claim ("neither deserialize failures nor handler failures ever reach a `CommonErrorHandler` that could trigger retry / DLT routing"). **The behaviour is correct; the documentation is wrong and actively misleading** — recorded as **KAF-03**.

**(b) Self-decoding consumers (2 of 7) — `product/media`, `search`.**
`MediaDeletedConsumer` and `ProductSearchConsumer` do their own decode inside a `try/catch` and **never throw**:
```java
// search-service/src/main/java/com/shop/searchservice/kafka/ProductSearchConsumer.java:46-51
try {
    event = decode(rawValue);
} catch (Exception ex) {
    log.error("Failed to decode product lifecycle payload — ack-skipping: {}", rawValue, ex);
    return;
}
```
→ a malformed payload is logged at ERROR and **acked away**. No DLT, no retry, no replay possible. `MediaDeletedConsumer` is the same shape (`:72-87`).
**Verdict:** the fleet's poison posture is **inconsistent by design**: 5 consumers DLT, 2 consumers silently drop. For the two self-decoding consumers the drop is *intentional* ("Ack-always poison posture … No DLT (fleet containment rule)", `MediaDeletedConsumer.java:27-29`). The operational cost is that a poison record in `media.lifecycle.v1` or `shop.product.lifecycle.v1` is unrecoverable — it exists only in a log line, with no DLT to inspect or replay.

**And one listener has no error handler at all — KAF-05:**
```java
// product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleListenerConfig.java:36-42
var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
Map<String, Object> props = kafkaProperties.buildConsumerProperties();
ConsumerFactory<String, String> consumerFactory =
    new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer());
factory.setConsumerFactory(consumerFactory);
factory.getContainerProperties().setObservationEnabled(true);
return factory;                      // <-- NO setCommonErrorHandler(...)
```
No `DefaultErrorHandler`, no `DeadLetterPublishingRecoverer`. The container falls back to Spring Kafka's built-in default (`DefaultErrorHandler` with `FixedBackOff(0, 9)` — 10 attempts, **zero** delay, and **no recoverer** → records are logged and skipped). This is the only listener factory in the fleet without an explicit error handler. Because `MediaDeletedConsumer` never throws, the handler is currently unreachable — but `product-service`'s *other* listener (`ratingListenerFactory`, `RatingLifecycleListenerConfig.java:21-25`) does have the DLT recoverer. **Inconsistent poison policy inside one service.**

**(c) DLQ/DLT — where it exists, and whether it is reachable.**
`DeadLetterPublishingRecoverer(template)` defaults to `<original-topic>.DLT`. There is **no** code that creates those topics and **no** operator-facing replay path enabled:
```java
// utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java:88-94
@Bean
@ConditionalOnClass(org.springframework.boot.actuate.endpoint.annotation.Endpoint.class)
@ConditionalOnProperty(prefix = "shop.kafka.dlq-replay", name = "enabled", havingValue = "true")
@ConditionalOnMissingBean
public DeadLetterReplayEndpoint deadLetterReplayEndpoint(DeadLetterReplayService replayService) {
```
`shop.kafka.dlq-replay.enabled` is **not set in any service's configuration** (`grep -rn "dlq-replay" --include=*.yml --include=*.yaml .` → no matches). So the replay endpoint is **off by default in every deployed service**, and the DLT is a write-only bucket. Recorded as **KAF-20** (MEDIUM).

---

## 8. DESIGN PATTERN EVALUATION

### 8.1 Transactional Outbox — **GENUINELY PRESENT, 8/8, CORRECTLY IMPLEMENTED**

This is the strongest part of the codebase. The pattern is not a name-drop: every element of the canonical formulation is present and verified.

| Canonical requirement | Present? | Evidence |
| --- | --- | --- |
| Business write and event record committed atomically | **YES 8/8** | §3(a) per service |
| Event record stored in the same DB | **YES** | `outbox_events` table in 8 schemas |
| A separate relay publishes to the broker | **YES** | 8 relay classes, `@Scheduled` |
| The relay never holds a DB lock across a publish | **YES** | 3-phase structure, §3.0 |
| Claim is race-free across replicas | **YES** | `PESSIMISTIC_WRITE` + SKIP LOCKED in 8/8 |
| A crashed claim is recoverable | **YES** | lease column (6/8) or `updated_at` (2/8) + `reclaimStaleSending` |
| The mark phase is a compare-and-set | **YES** | `findByIdForUpdate` + `status != SENDING` guard in 8/8 |
| Retention / aging | **PARTIAL** | 8/8 purge `SENT`; only media ages `FAILED → DEAD`; `DEAD` is never purged; `FAILED` is never purged in 7/8 (KAF-11, KAF-12) |

**Sketch of the canonical relay** (all 8 follow it):
```
@Transactional(REQUIRED) claim():
    row = SELECT ... WHERE status='PENDING' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED   -- 1 row
    row.status = SENDING; row.claimedAt = now                                            -- lease
    save(row)                                                                            -- commit here

no transaction:
    outcome = publisher.publishBatch(rows)        -- parallel sends, bounded latch

@Transactional(REQUIRED) mark(row, ok):
    row = SELECT ... WHERE id=? FOR UPDATE SKIP LOCKED                                   -- re-read
    if row.status != SENDING: return                                                    -- CAS guard
    if ok:  row.status = SENT; row.sentAt = now
    else:   row.retryCount++; row.status = (retryCount >= max) ? FAILED : PENDING
```
The one **legitimately missing** ingredient versus a production-grade outbox is a **dead-letter table for exhausted events**: an exhausted row is parked in `FAILED` with only a `log.error` (`OrderOutboxRelay.java:222-223`), no metric, no operator workflow, and — in 7/8 services — no automatic purge (KAF-11). media-service is the exception and does it properly (`MediaOutboxRelay.java:216-221` + `MediaOutboxRetentionScheduler.java:37-42` with `recordOutboxDead`).

### 8.2 Idempotent Consumer / Inbox — **PARTIALLY PRESENT, CORRECT WHERE PRESENT**

- A **generic inbox table is absent** from the fleet. Confirmed by exhaustive grep (§4).
- Idempotency is instead achieved by **three complementary mechanisms**, all verified:
  1. **DB-constraint dedup** — `notifications.event_id` UNIQUE (`changelog-001-notifications.yaml:40`), `shipments(order_id) WHERE deleted=false` UNIQUE (`changelog-001-shipments.yaml:40`), `idempotency_keys(actor, key)` PK (`changelog-001-initial-schema.yaml:227-230`).
  2. **State-machine guards** — `ShippingDeliveredHandler.java:36-39`; `PaymentLifecycleConsumer.java:36-39`.
  3. **Convergent assignment / external upsert** — `ProductRatingService.java:33-34`, `ProductMediaService.java:52`, `ProductSearchService.java:43-46`.
- **Why the absence is not CRITICAL here:** every payload is a **full snapshot** of the target state and every handler is convergent. Replay converges.
- **Why it is nonetheless a latent CRITICAL risk:** nothing *enforces* the snapshot discipline (§5, KAF-10/KAF-14). The first delta-shaped event would double-apply with no inbox table to catch it.

**Sketch of the missing inbox pattern** (recommended, not present):
```java
@Entity @Table(name = "inbox_messages",
    uniqueConstraints = @UniqueConstraint(name = "uk_inbox_consumer_event",
        columnNames = {"consumer_group", "event_id"}))
class InboxMessage {
    @Id Long id;
    String consumerGroup;   // the consumer's identity
    String eventId;         // the producer's event_id (already carried in every payload)
    Instant receivedAt;
}
// handler: INSERT ... ON CONFLICT DO NOTHING; if 0 rows -> already processed -> ack and return
```

### 8.3 Retry — **PRESENT on the producer side, INCONSISTENT on the consumer side**

- **Producer side:** `retries=3` + `acks=all` + `enable.idempotence=true` + `max.in.flight=1` (`KafkaProperties.java:83-91, 154-163`). Plus the outbox-level retry: `retry_count` incremented per failed publish and the row returned to `PENDING` (`OrderOutboxRelay.java:217-227` etc.), bounded by `max-retries` (10). Plus the claim-lease retry for a crashed relay (§7.3).
- **Consumer side:** the retry is hardcoded `new FixedBackOff(1000L, 3L)` (`BaseKafkaListenerConfig.java:89,91`). The `shop.kafka.retry.*` properties (`KafkaProperties.java:128-150`: `backoffMs=6000`, `maxAttempts=4`) are **never read** — the class javadoc even says so (`:110-127`), yet the properties remain bindable, so an operator can set them and observe no effect. **KAF-04.**
- **One listener has no retry at all** (product/media, KAF-05) and **two consumers self-retry in-handler** (`MediaDeletedConsumer.java:89-105` — 3 attempts, 200 ms backoff, `TransientDataAccessException` only).

### 8.4 DLQ / DLT — **PRESENT AND PROVEN, BUT UNREACHABLE OPERATIONALLY**

- `DeadLetterPublishingRecoverer` is wired into 6 of 7 listener factories (`BaseKafkaListenerConfig.java:88-89`, and the 6 subclasses that pass a `stringKafkaTemplate`).
- The DLT path is **proven by a real embedded-broker test** (`BaseKafkaConsumerDeadLetterTest.java:83-94`).
- But: product/media has no DLT (KAF-05), 2 consumers ack-skip poison records by design (§7.7b), and the **replay endpoint is disabled in every service** (`shop.kafka.dlq-replay.enabled` is set nowhere) — so the DLT cannot be drained operationally. **KAF-20.**
- Also note: the DLT topics themselves are never provisioned (no `NewTopic`, no admin script — see KAF-06). `DeadLetterPublishingRecoverer` will create them on the broker only if `auto.create.topics.enable=true`.

### 8.5 Observer / Domain Events — **PARTIALLY PRESENT, DEGRADED TO A RELAY-BOUND SIDE EFFECT**

The classic Observer / Domain Events shape would have the aggregate raise a domain event in-process and a separate listener persist it to the outbox. This codebase does **not** do that: the event is built directly inside the transaction by a dedicated publisher bean, and the "observer" role is played by the **outbox relay**, which is a scheduled poller over a table, not an in-process subscriber.

What *is* present and good:
- **A typed seam per aggregate** — `OrderEventPublisher`, `ProductEventPublisher`, `BrandEventPublisher`, `CategoryEventPublisher`, `ShippingEventPublisher`, `PaymentEventPublisher`, `PromotionEventPublisher`, `InventoryEventPublisher`. The dependency is on the **interface** at every call site (`ProductServiceImpl` → `ProductEventPublisher`, `BrandServiceImpl.java:33` → `BrandEventPublisher`, etc.), so the publishers are substitutable and testable.
- **The publisher is deliberately not transactional** so it joins the caller's transaction (§3(a)) — the correct transactional-outbox discipline.
- **Consumer-side decoupling is genuine**: shipping-service reacts to `shop.order.lifecycle.v1` without calling order-service (`ShipmentServiceImpl.handleOrderEvent` `:69-76`); search-service reacts to `shop.product.lifecycle.v1` without calling product-service.

What is missing: no in-process domain-event publication, no `@DomainEvents` / `@AfterDomainEventPublication` style aggregate hook, and no aggregate-level event collection. **This is an acceptable, arguably better choice** for a transactional-outbox architecture (the outbox *is* the durable event channel). The cost is that the event payload construction is duplicated in the publisher rather than owned by the aggregate — the aggregate never knows what it emitted. **LOW observation, not a defect.**

### 8.6 Repository — **GENUINELY PRESENT AND CORRECTLY APPLIED**

- Each service owns a dedicated `OutboxEventRepository extends JpaRepository<OutboxEvent, Long>` with hand-written JPQL for the lock-sensitive operations. This is the **right** call: derived queries cannot express `FOR UPDATE SKIP LOCKED` or the bulk `@Modifying` sweep, so the claim/reclaim/mark queries are explicit and reviewable.
- The bulk sweep uses `@Modifying(clearAutomatically = true, flushAutomatically = true)` — the `flushAutomatically` flag is the detail most implementations get wrong (without it the sweep can run against a stale persistence context); it is present in 8/8 (`OrderOutboxEventRepository.java:75`, `MediaOutboxEventRepository.java:79`, `product OutboxEventRepository.java:73`, etc.).
- The compare-and-set re-read (`findByIdForUpdate`) exists in 8/8.
- **One inconsistency:** 6/8 use the derived `findFirstByStatusOrderByIdAsc` form while 2/8 use an explicit `MIN(id)` JPQL — both are correct for safety, but they differ in parallelism (KAF-01) and the divergence is not documented anywhere.

---

## 9. DEFECT LIST

Severity scale: **HIGH** = data-loss / invariant violation reachable in normal operation; **MEDIUM** = correctness or operability gap with a workaround or a bounded blast radius; **LOW** = hygiene, dead config, latent risk.

---

### KAF-01 — HIGH — 6 of 8 outbox relays serialise: a second replica publishes nothing
- **Evidence:** `product-service/.../repository/OutboxEventRepository.java:37-39`; `inventory-service/.../repository/OutboxEventRepository.java:39-41`; `media-service/.../outbox/OutboxEventRepository.java:42-44`; `rating-service/.../outbox/OutboxEventRepository.java:37-39`; `promotion-service/.../repository/OutboxEventRepository.java:39-41`; `shipping-service/.../outbox/OutboxEventRepository.java:37-39` — all use `... AND e.id = (SELECT MIN(e2.id) ...)`.
  Contrast: `order-service/.../repository/OutboxEventRepository.java:38` and `payment-service/.../outbox/OutboxEventRepository.java:37` use `findFirstByStatusOrderByIdAsc`.
- **Actual behaviour:** with `MIN(id)`, the WHERE clause pins exactly ONE candidate row. `SKIP LOCKED` drops that row when another transaction holds it, so the peer's query returns **empty**, and the relay's claim loop `break`s (`OutboxRelay.java:129-131`, `MediaOutboxRelay.java:126-128`, `InventoryOutboxRelay`, `RatingOutboxRelay`, `PromotionOutboxRelay`, `ShippingOutboxRelay` — same shape). The peer then publishes an empty batch and idles until the next tick.
  **This is asserted as intended behaviour by the services' own tests:** `ProductOutboxClaimConcurrencyIT.java:39` (`assertThat(claimBWhileLocked.get()).isEmpty()`), `:42` (row2 still PENDING), test name `twoPendingRows_secondRowUntouchedWhileHeadRowLocked`; identical assertions in `InventoryOutboxClaimConcurrencyTest.java:121`, `MediaOutboxClaimConcurrencyIT.java:121`, `RatingOutboxClaimConcurrencyIT.java:119`, `PromotionOutboxClaimConcurrencyIT.java:121`, `ShippingOutboxClaimConcurrencyIT.java:119`.
- **Violated invariant:** the repository javadoc's claim — "a second relay instance can claim a different row immediately instead of waiting behind the publish" (`MediaOutboxRelay.java:33-35`, `OrderOutboxRelay.java:36-38`, `OutboxRelay.java:35-38`).
- **Root cause:** `SKIP LOCKED` only advances past a locked row when the query has a *range* predicate it can keep scanning (`ORDER BY id ASC LIMIT 1` over `status='PENDING'`). The `MIN(id)` subquery collapses the range to one row before the lock is taken.
- **Impact:** horizontal scale-out of 6 services' event emitters yields **zero** additional publish throughput. With 2 replicas polling every 2 s (`shop.media.outbox.poll-millis: 2000` etc.), the second replica contributes nothing while the first is claiming. A slow or GC-stalled first replica stalls event emission for the whole service.
- **Proposed fix:** replace the `MIN(id)` claim with the derived form already proven in order-service/payment-service:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
  Optional<OutboxEvent> findFirstByStatusOrderByIdAsc(OutboxStatus status);
  ```
  (for media: `findFirstByStatusInOrderByIdAsc(DUE_STATUSES)`).
- **Reproduction test:** adapt `OrderOutboxClaimConcurrencyIT.twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked` (`:120-182`) to the affected service and change the assertion at `:176` to `contains(row2)`. It currently fails.
- **Acceptance criterion:** in every service's `*OutboxClaimConcurrencyIT`, the second test asserts `claimBWhileLocked` contains `row2` while `row1` is locked; the relay's `claimedIds.size()` on two replicas sums to the pending-row count.

---

### KAF-02 — HIGH — payment-service relay has no per-aggregate ordering barrier
- **Evidence:** `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java:153-162` — the mark loop uses the raw per-record outcome; **no call to `OutboxBatchOrdering.markableAsSent(...)`** exists anywhere in the file.
  Every sibling relay has one: `OrderOutboxRelay.java:166`, `product OutboxRelay.java:167`, `MediaOutboxRelay.java:163`, `RatingOutboxRelay.java:151`, `ShippingOutboxRelay.java:161`, `PromotionOutboxRelay.java:166`, `InventoryOutboxRelay.java:165`.
- **Actual behaviour:** if a `payment.captured.v1` record fails to publish while a later `payment.refunded.v1` for the **same payment** succeeds, both are marked SENT/handled independently: the capture is retried later but the refund is not. The broker therefore delivers `payment.refunded.v1` **before** `payment.captured.v1` for that payment — and because both share the partition key `paymentId` (`PaymentOutboxRelay.java:142`), the reorder is permanent within that partition. The consumer (`order-service/.../kafka/PaymentLifecycleConsumer.java:31`) acts only on `payment.captured.v1`; a refund that arrives first leaves the order unconfirmed until the capture is redelivered, and the payment's own compensation semantics are inverted.
  The fleet's own design note describes exactly this hazard: *"a refund seen before the capture it compensates"* (`utils/common-kafka/.../producer/OutboxBatchOrdering.java:12-14`).
- **Violated invariant:** "within one aggregate, a record that failed blocks every later record of the SAME aggregate" (`OutboxBatchOrdering.java:17-20`).
- **Root cause:** the ordering barrier was applied to 7 of 8 relays and missed on payment-service.
- **Impact:** per-payment event ordering is not guaranteed on `shop.payment.lifecycle.v1`. With `max.in.flight=1` and a single partition per payment key, the reorder is confined to one payment but is durable.
- **Proposed fix:** in `PaymentOutboxRelay.relay()`, collect the partition keys and apply the barrier before the mark loop:
  ```java
  List<String> keys = new ArrayList<>();
  for (OutboxEvent e : rows) { keys.add(e.getAggregateId().toString()); }
  boolean[] markable = OutboxBatchOrdering.markableAsSent(keys, delivered);
  // then markable[i] instead of published
  ```
- **Reproduction test:** extend `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxRelayOrderTest.java` (exists) with a two-row same-aggregate batch where row 1's publish fails and row 2's succeeds; assert row 2 is **not** marked SENT.
- **Acceptance criterion:** `PaymentOutboxRelay` contains a `markableAsSent` call, and the test asserts the later same-aggregate record returns to `PENDING`.

---

### KAF-03 — MEDIUM — `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling
- **Evidence (doc says ack-skip, no DLT):**
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:20-22` — "Decode failures are contained ack-skips — the listener method never throws and the offset always advances."
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:49-50` — "A decode failure logs at ERROR and ack-skips without invoking the handler (fleet containment: **no DLT**, no container crash)."
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:113-118` — "neither deserialize failures nor handler failures ever reach a `CommonErrorHandler` that could trigger retry / DLT routing."
- **Evidence (code does the opposite):** `BaseKafkaConsumer.java:111-116` **throws** `PoisonRecordException`; `BaseKafkaListenerConfig.java:88-89` installs a `DefaultErrorHandler(recoverer, FixedBackOff(1000,3))` with a `DeadLetterPublishingRecoverer`; `BaseKafkaConsumerDeadLetterTest.java:83-94` proves the record lands on the DLT.
- **Actual behaviour:** the **code is correct** (3 retries → DLT → offset advances, listener never sees the payload). The **documentation is wrong**.
- **Violated invariant:** documentation must describe actual behaviour — this repo's own stated rule is "Do NOT trust README/docs. Verify against source", and here the source-comment is the unreliable artefact.
- **Root cause:** the code was changed from ack-skip to throw-and-DLT; the comments were not updated.
- **Impact:** an operator reading the javadoc concludes there is no DLT and no retry, and designs runbooks accordingly. It also makes the `Retry` block's "INTENTIONALLY UNUSED" justification (KAF-04) factually unsupported.
- **Proposed fix:** rewrite `BaseKafkaConsumer.java:20-22,49-50` and `KafkaProperties.java:109-127` to state the actual posture: "decode failure → `PoisonRecordException` → `DefaultErrorHandler` → 3 retries at 1 s → DLT (`<topic>.DLT`) → offset advances; the handler is never invoked."
- **Reproduction test:** none required (documentation fix); add a comment-drift guard if desired — the existing `BaseKafkaConsumerDeadLetterTest` already proves the behaviour.
- **Acceptance criterion:** no comment in `common-kafka` asserts "ack-skip" or "no DLT" for the typed-decode path.

---

### KAF-04 — MEDIUM — `shop.kafka.retry.*` is bindable but has zero effect
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:128-150` defines `Retry { backoffMs = 6_000; maxAttempts = 4; }`. The actual consumer retry is hardcoded:
  ```java
  // utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:89
  factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
  // :91
  factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));
  ```
  `KafkaProperties.getRetry()` is never read by any production code (verified by grep across the repo).
- **Actual behaviour:** setting `shop.kafka.retry.max-attempts=10` or `shop.kafka.retry.backoff-ms=30000` changes **nothing**. The effective policy is always 3 retries at 1 000 ms.
- **Violated invariant:** a configuration property that claims to control behaviour must control it.
- **Root cause:** the retry policy was centralised as a literal in `BaseKafkaListenerConfig` instead of being read from `KafkaProperties`.
- **Impact:** operators cannot tune the retry/DLT policy without a code change; during an incident the only lever is a redeploy. The property's presence is worse than its absence — it looks like a working knob.
- **Proposed fix:** `factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(kafkaProperties.getRetry().getBackoffMs(), kafkaProperties.getRetry().getMaxAttempts())))`, and remove the "INTENTIONALLY UNUSED" block or wire it.
- **Reproduction test:** in `BaseKafkaListenerConfigWireTest` (`utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfigWireTest.java`), set `kafkaProperties.getRetry().setMaxAttempts(7)` and assert the container's error handler uses a `FixedBackOff` with `maxAttempts == 7`.
- **Acceptance criterion:** `KafkaProperties.getRetry()` has at least one production caller; no `shop.kafka.retry.*` property is documented as inert.

---

### KAF-05 — MEDIUM — product-service's media listener has no error handler, hence no DLT
- **Evidence:** `product-service/src/main/java/com/shop/productservice/kafka/MediaLifecycleListenerConfig.java:33-43` — the factory sets a consumer factory and observation, and **never calls `setCommonErrorHandler(...)`**. Contrast `RatingLifecycleListenerConfig.java:21-25` in the *same service*, which does.
- **Actual behaviour:** the container falls back to Spring Kafka's built-in default (`DefaultErrorHandler` with `FixedBackOff(0, 9)` — 10 attempts, **zero** delay, **no recoverer**). Any exception that escapes the listener is retried 10 times with no backoff and then **logged and skipped** — no DLT, no replay, and a hot spin of 10 immediate retries on a persistent failure.
  Currently `MediaDeletedConsumer` never throws (`:72-87` catches everything), so the handler is unreachable — the gap is latent, not active.
- **Violated invariant:** uniform poison policy within a service; every listener should have a defined, uniform DLT route.
- **Root cause:** `MediaLifecycleListenerConfig` predates the fleet-wide flip to `BaseKafkaListenerConfig` (its javadoc says so: "Standalone raw-string factory (predates the fleet-wide flip…)", `:15-19`) and was never migrated.
- **Impact:** if `MediaDeletedConsumer` is ever changed to let an exception escape (or if a framework-level exception occurs), poison records on `media.lifecycle.v1` are dropped after 10 hot retries with no DLT and no replay path.
- **Proposed fix:** migrate `MediaLifecycleListenerConfig` to extend `BaseKafkaListenerConfig<String>` exactly as `RatingLifecycleListenerConfig` does — the factory, error handler, DLT recoverer and consumer properties are then identical across the fleet.
- **Reproduction test:** publish a malformed record to `media.lifecycle.v1` in an embedded-Kafka IT and assert a record appears on `media.lifecycle.v1.DLT` (currently it does not).
- **Acceptance criterion:** every `*ListenerConfig` in the repo calls `setCommonErrorHandler(...)`; a fleet-wide grep asserts zero `@Bean(name = "*ListenerFactory")` methods without it.

---

### KAF-06 — MEDIUM — No topic provisioning anywhere in the repo
- **Evidence:** exhaustive search —
  ```
  grep -rn "NewTopic|kafka-topics|--create|KAFKA_CREATE_TOPICS|auto.create" \
        --include=*.java --include=*.yml --include=*.yaml --include=*.sh --include=*.properties .
  ```
  returns **no** topic-creation code. The only `kafka-topics.sh` hit is a **health check** (`docker-compose.yml:110`), not a provisioning step:
  ```yaml
  # docker-compose.yml:110
  test: ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1"]
  ```
  The broker is `apache/kafka:3.9.0` with no `KAFKA_AUTO_CREATE_TOPICS_ENABLE` setting (`docker-compose.yml:89-108`).
- **Actual behaviour:** topics exist only because the Kafka broker's default `auto.create.topics.enable=true` creates them on the first produce. Auto-created topics get the broker default `num.partitions=1`.
- **Violated invariant:** the platform publishes to 10 named topics plus a `.DLT` per consumed topic; their existence and partition count must be a declared, reviewed artefact.
- **Root cause:** topic provisioning was never made explicit; it is inherited from a dev-broker default.
- **Impact:** (a) with `auto.create.topics.enable=false` — the standard production posture — **every publish fails** with `UNKNOWN_TOPIC_OR_PART`, the outbox drains to `FAILED`, and events are silently lost (only a `log.error` at `OrderOutboxRelay.java:222-223`); (b) with auto-create on, `num.partitions=1` defeats the entire partition-key design that the relays implement in 8/8 services; (c) DLT topics are created on demand by the recoverer with the same default partition count.
- **Proposed fix:** add a `KafkaTopicsConfiguration` with `@Bean NewTopic` definitions (or an idempotent `AdminClient` initializer) in `common-kafka`, declaring each of the 10 topics with an explicit partition count and the `<topic>.DLT` topics, sourced from `KafkaTopicContract` so the contract stays the single source of truth.
- **Reproduction test:** start the fleet against a broker with `auto.create.topics.enable=false` and assert every topic in `KafkaTopicContract.TOPICS` exists with the declared partition count.
- **Acceptance criterion:** a `NewTopic` bean exists for all 10 contract topics + 6 `.DLT` topics; a startup assertion fails fast if any topic is missing.

---

### KAF-07 — MEDIUM — product-service and payment-service use the generic `updated_at` column as the claim lease
- **Evidence:** no `claimed_at` column exists on either table.
  - `product-service/.../repository/OutboxEventRepository.java:78` — `"WHERE e.status = :sending AND e.updatedAt < :cutoff"`.
  - `payment-service/.../outbox/OutboxEventRepository.java:84` — `"WHERE e.status = :sending AND e.updatedAt < :cutoff"`.
  - `product-service/.../entity/OutboxEvent.java:18` — `extends AbstractMappedEntity`; `utils/common-core/.../AbstractMappedEntity.java:22-24` — `@LastModifiedDate @Column(name = "updated_at")`.
  - `payment-service/.../outbox/OutboxEvent.java:58-60` — Hibernate `@UpdateTimestamp`.
  The other **six** services all have a dedicated column: `order-service/.../entity/OutboxEvent.java:56-57` (`claimed_at`), `media-service/.../outbox/OutboxEvent.java:59-60`, `rating`/`shipping`/`inventory`/`promotion` (respective `changelog-*-outbox-claim-lease.yaml`).
- **Actual behaviour:** the lease works today because claiming a row dirties it (`status = SENDING`) and therefore bumps `updated_at`. But `updated_at` is a **generic audit column**: any other write to an outbox row — an ops script, an ad-hoc "reset this row" fix, a future field addition, or a bulk `UPDATE` that touches the row — silently **resets the claim lease**, so a genuinely stranded `SENDING` row is not reclaimed on time and the event is delayed by up to a full lease interval (60 s) or, in the worst case, is misread as fresh.
- **Violated invariant:** the lease column must be owned by the claim protocol, not shared with an unrelated audit mechanism.
- **Root cause:** `claimed_at` was added to 6 services by 6 separate changelogs and missed on 2.
- **Impact:** delayed or delayed-forever event recovery after a relay crash in product-service and payment-service; a latent coupling that will bite the first person who writes to an outbox row ad hoc.
- **Proposed fix:** add `claimed_at timestamptz` to both tables (`ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS claimed_at timestamptz;` + index), add the field to both entities, set it in the claim phase (as `MediaOutboxRelay.java:121` already does), and change the reclaim predicate to `e.claimedAt < :cutoff`.
- **Reproduction test:** insert a `SENDING` row with `updated_at = now()` and `claimed_at = now() - 10 min`; assert the reclaim sweep picks it up. It does not today (the predicate reads `updated_at`).
- **Acceptance criterion:** all 8 outbox tables have a `claimed_at` column and all 8 reclaim predicates reference `claimedAt`.

---

### KAF-08 — MEDIUM — search-service's DLT depends on an optional bean and fails open
- **Evidence:** `search-service/src/main/java/com/shop/searchservice/kafka/SearchListenerConfig.java:21-24`:
  ```java
  public SearchListenerConfig(KafkaProperties kafkaProperties,
          ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider) {
      super(String.class, kafkaProperties, kafkaTemplateProvider.getIfAvailable());
  }
  ```
  and the base class:
  ```java
  // utils/common-kafka/src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:87-92
  if (template != null) {
      DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template);
      factory.setCommonErrorHandler(new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L)));
  } else {
      factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, 3L)));  // no recoverer
  }
  ```
- **Actual behaviour:** if the `stringKafkaTemplate` bean is absent, the container silently degrades to a `DefaultErrorHandler` with **no recoverer** → after 3 retries the record is logged and **dropped**. Nothing warns; the DLT simply stops existing. Every other listener config injects the template with a mandatory `@Qualifier("stringKafkaTemplate")` (e.g. `NotificationListenerConfig.java:17-19`), so this is the single fail-open path in the fleet.
- **Violated invariant:** the poison-record route must be deterministic, not dependent on the presence of an optional bean.
- **Root cause:** `ObjectProvider.getIfAvailable()` was used to tolerate a missing template; the degradation chosen was "drop silently" instead of "fail fast".
- **Impact:** a missing/renamed bean in search-service silently converts every poison record into data loss, with no log line at startup and no alarm.
- **Proposed fix:** make the template a required dependency (`@Qualifier("stringKafkaTemplate") KafkaTemplate<String,String>`) so the context fails to start if it is missing; or, if optionality is genuinely needed, log a WARN at bean creation and install a recoverer that fails loudly.
- **Reproduction test:** build the `SearchListenerConfig` context without a `stringKafkaTemplate` bean and assert the context startup **fails** (today it succeeds and silently drops).
- **Acceptance criterion:** no `BaseKafkaListenerConfig` subclass passes `null` as the template; the `template == null` branch at `BaseKafkaListenerConfig.java:90-92` is removed or logs at ERROR.

---

### KAF-09 — MEDIUM — notification-service's dedup key silently depends on the event id being a parseable UUID
- **Evidence:** `notification-service/.../service/impls/NotificationServiceImpl.java:102-113`:
  ```java
  private UUID parseEventId(OrderLifecycleEvent event) {
      if (event == null || event.eventId() == null || event.eventId().isBlank()) {
          log.warn("Skipping notification event with missing eventId");
          return null;
      }
      try {
          return UUID.fromString(event.eventId());
      } catch (IllegalArgumentException e) {
          log.warn("Skipping notification event with invalid eventId {}", event.eventId());
          return null;
      }
  }
  ```
  and the caller: `NotificationServiceImpl.java:57-60` — `if (eventId == null) { return; }` → the event is **dropped without error**.
  The DB column is `event_id uuid NOT NULL` with `CREATE UNIQUE INDEX uk_notification_event_id ON notifications (event_id)` (`notification-service/.../changelog-001-notifications.yaml:16,40`).
- **Actual behaviour:** the producer's `event_id` is `UUID.randomUUID().toString()` (`OrderEventPublisherImpl.java:83`), so it parses today. If order-service ever changes the event-id format (a composite key, a ULID, a namespaced id), **every notification is silently dropped** with only a WARN per event — the dedup key, the storage column, and the transport format are coupled by an undocumented assumption.
- **Violated invariant:** a consumer's dedup key must be an explicit, contract-level field, not an inferred format.
- **Root cause:** the id format is a convention, not a declared contract; `KafkaTopicContract` does not specify the envelope shape at all.
- **Impact:** a one-line change in order-service silently disables the entire notification pipeline, with no exception and no metric.
- **Proposed fix:** declare the envelope in the contract (`KafkaTopicContract`) — e.g. a `record EventEnvelope(String eventId, String eventType, String occurredAt)` with `eventId` typed as `String` on the wire and validated against a documented format — and make the notification column `varchar(64)` rather than `uuid`, or keep `uuid` but validate at the producer and fail the publish rather than skip at the consumer.
- **Reproduction test:** publish an `order.created.v1` whose `eventId` is `"abc-123"`; assert a notification row exists (or a loud error is raised) — today nothing is created.
- **Acceptance criterion:** the event-id format is declared in `KafkaTopicContract` and covered by a contract test; a non-conforming event id causes a DLT, not a silent skip.

---

### KAF-10 — LOW — Producer payloads are untyped maps; the schema is not compile-time checked
- **Evidence:** `OrderEventPublisherImpl.java:39-51,89-93`; `TransactionalProductEventPublisher.java:68-90`; `MediaEventService.java:61-71`; `RatingEventService.java:72-82`; `PaymentEventPublisher.java:29-36,47-51`; `ShippingEventPublisherImpl.java:40-50`; `TransactionalPromotionEventPublisher.java:47-83`; `TransactionalInventoryEventPublisher.java` (same shape).
- **Actual behaviour:** every payload is a `HashMap`/`LinkedHashMap` serialised by `objectMapper.writeValueAsString(payload)`. A field rename or removal compiles and ships; the failure appears only at the consumer as a `null` inside a DTO, and (because the consumer DTOs are `@JsonIgnoreProperties(ignoreUnknown = true)`) a *producer-side* typo is silently invisible to the consumer's unknown-field tolerance — it just yields `null`.
- **Violated invariant:** an event contract should be owned by one artefact and checked by the compiler.
- **Root cause:** the producers were written before the consumer DTOs and were never refactored onto them.
- **Impact:** a producer-side field rename breaks consumers silently. The `media` payload is the one exception — its javadoc calls the field set a contract (`MediaEventService.java:60`).
- **Proposed fix:** move the payload records into `utils/common-kafka` (or a `common-events` module) and have producers use `objectMapper.writeValueAsString(new ProductLifecycleEvent(...))`; consumers already bind to exactly these shapes.
- **Reproduction test:** rename a field in one producer's map and assert the corresponding consumer DTO no longer compiles / a contract test fails.
- **Acceptance criterion:** no producer builds a payload from a `Map`; every payload type lives in a shared module and is referenced by both producer and consumer.

---

### KAF-11 — LOW — `FAILED` outbox rows are never purged in 7 of 8 services
- **Evidence:** every retention scheduler deletes only `SENT` (plus `DEAD` in payment):
  ```java
  // order-service/.../OutboxRetentionScheduler.java:30
  int deleted = outboxRepository.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
  ```
  identical at `product OutboxRetentionScheduler.java:36-37`, `rating OutboxRetentionScheduler.java:34-35`, `shipping ShippingOutboxRetentionScheduler.java:24-25`, `payment OutboxRetentionScheduler.java:74-75`, `promotion OutboxRetentionScheduler.java:30-31`, `inventory OutboxRetentionScheduler.java:30-31`.
  Only media-service ages `FAILED → DEAD` (`media OutboxEventRepository.java:101-104`) and only media-service's scheduler purges `DEAD`… which it does not (see KAF-12).
  `OutboxStatus.DEAD` is declared in the shared enum (`utils/common-core/.../OutboxStatus.java:20`) and documented as "terminal failure", but only media-service ever sets it.
- **Actual behaviour:** a row that exhausts its retry budget is parked as `FAILED` (`OrderOutboxRelay.java:220-227`) and is **never** deleted and **never** replayed in 7 services. It stays forever, with only the `log.error` at the moment of parking as a signal. The `outbox_events` table therefore grows without bound for every permanently-failing event.
- **Violated invariant:** every terminal state must have a retention rule.
- **Root cause:** the media-service H-5 aging design (`FAILED` replayable → `DEAD` terminal) was not rolled out to the other 7 services.
- **Impact:** unbounded table growth on a permanently-failing topic (e.g. a malformed payload that always fails to serialise), with no metric and no operator workflow to find and fix it.
- **Proposed fix:** roll out media's pattern fleet-wide — age `FAILED` → `DEAD` after the retention window, purge `DEAD` after a further grace period, and emit a counter for both transitions.
- **Reproduction test:** insert a `FAILED` row older than the retention window, run the retention scheduler, assert it is deleted (or aged to `DEAD` and then deleted).
- **Acceptance criterion:** every retention scheduler purges `SENT` **and** either ages or purges `FAILED`; a `*_outbox_failed_total` metric exists per service.

---

### KAF-12 — LOW — `DEAD` rows are never purged (media-service) and payment's `DEAD` purge is unreachable code
- **Evidence:** `media-service/.../outbox/MediaOutboxRetentionScheduler.java:32` purges `SENT`; `:37` ages `FAILED → DEAD`; **nothing deletes `DEAD`**. Meanwhile `payment-service/.../outbox/OutboxRetentionScheduler.java:83-84` deletes `DEAD` — but the payment relay **never sets `DEAD`** (its `markFinalState` only writes `SENT`/`PENDING`/`FAILED`, `PaymentOutboxRelay.java:199-212`), so that delete statement can never match a row.
- **Actual behaviour:** media's terminal failures accumulate forever; payment's terminal-state cleanup is dead code that will silently rot.
- **Violated invariant:** a terminal state must be reclaimable; dead code in a retention path is a latent defect.
- **Root cause:** media's aging design was implemented one step short of its own purge; payment copied the purge without the aging.
- **Impact:** unbounded growth of `DEAD` rows in media-service; a false sense of cleanup in payment-service.
- **Proposed fix:** add `deleteByStatusAndUpdatedAtBefore(DEAD, cutoff)` to the media repository and call it in `MediaOutboxRetentionScheduler`; either add `FAILED → DEAD` aging to payment or delete the unreachable DEAD purge.
- **Reproduction test:** insert a `DEAD` media row older than the window, run the scheduler, assert it is gone.
- **Acceptance criterion:** every status in `OutboxStatus` has exactly one owner service and is purged (or explicitly documented as retained) by that service's retention job.

---

### KAF-13 — LOW — `outbox_events.event_id` UNIQUE protects against nothing but UUID collisions
- **Evidence:** `order-service/.../entity/OutboxEvent.java:19-20` — `@Column(name = "event_id", nullable = false, unique = true, length = 36)`; the value is `UUID.randomUUID().toString()` (`OrderEventPublisherImpl.java:83`). Same shape in all 8 entities.
- **Actual behaviour:** the UNIQUE constraint guarantees only that no two rows share a UUID. It does **not** prevent the same *business fact* from being written twice into the outbox — e.g. a retried request that bypasses the idempotency layer, or a caller that invokes the publisher twice. Both rows are published; consumers tolerate it (snapshot semantics), but the fleet has no defence at the source.
- **Violated invariant:** an event that describes a business fact should be uniquely identifiable by that fact.
- **Root cause:** the event id is a random surrogate, not a deterministic business key.
- **Impact:** low — duplicate publishes are already tolerated by design (§4). The residual risk is the volume of duplicates and the loss of the ability to detect them.
- **Proposed fix:** add a deterministic business key column with a UNIQUE constraint (e.g. `(aggregate_type, aggregate_id, event_type, business_version)`), or derive the `event_id` deterministically from the business key so a retry produces the same id and the UNIQUE constraint does the dedup.
- **Reproduction test:** insert two outbox rows for the same `(aggregateId, eventType, version)` and assert the second is rejected.
- **Acceptance criterion:** every outbox table has a UNIQUE constraint that expresses the business identity of the event, not just the surrogate id.

---

### KAF-14 — LOW — The snapshot discipline that makes idempotency work is unenforced (latent CRITICAL if a delta event is ever introduced)
- **Evidence:** the idempotency of 4 of the 7 consumers rests entirely on the payload being a **full snapshot**: `ProductRatingService.java:11-15` ("dumb idempotent copy of the carried avgRating/ratingCount snapshot … replaying the same event overwrites with identical values"); `ProductMediaService.java:52` (set-to-null); `ProductSearchService.java:43-46` (upsert by id). The producers build those snapshots by hand (`TransactionalProductEventPublisher.java:68-90`), and nothing — no type, no test, no contract — prevents a future producer from emitting a **delta** (e.g. `{"ratingCountDelta": 1}`).
- **Actual behaviour:** today this is fine. The first delta-shaped event would be **double-applied** on every duplicate delivery, at-least-once, with no inbox table (§8.2) and no state guard to catch it.
- **Violated invariant:** the at-least-once contract's safety net is the snapshot discipline, and that discipline is a convention rather than an enforced property.
- **Root cause:** the snapshot discipline is documented in consumer javadoc but has no enforcement mechanism (shared type, schema registry, or inbox table).
- **Impact:** latent — a HIGH-severity future defect with no current guard.
- **Proposed fix:** the shared payload records (KAF-10) make the snapshot shape explicit and type-checked; additionally add the inbox table sketched in §8.2 so a delta event can be applied exactly once.
- **Reproduction test:** publish the same `rating.submitted.v1` twice and assert `rating_count` is unchanged (passes today — it is a regression guard for the future delta event).
- **Acceptance criterion:** every event type is declared as a snapshot in `KafkaTopicContract`, and a shared payload type per event type enforces it at compile time.

---

### KAF-15 — LOW — `enable.auto.commit=false` is inherited from a framework default, not asserted by fleet code
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:99-106` — `buildConsumerProperties()` sets only `bootstrap.servers`, `group.id`, `auto.offset.reset` and `key.deserializer`. `enable.auto.commit` is **not** set. The Kafka client default is `true`.
- **Actual behaviour:** correctness currently depends on Spring Kafka's `KafkaMessageListenerContainer$ListenerConsumer.determineAutoCommit(Properties)` forcing the property to `"false"` when absent (verified in the shipped `spring-kafka-4.1.1` bytecode — see §6.2). Any move to a raw `KafkaConsumer`, a different container, or a custom `ConsumerFactory` would silently flip the fleet to auto-commit and **lose at-least-once** (records committed on the poll timer while the handler is still running). `DeadLetterReplayService` sets it explicitly (`:75`), showing the pattern exists but was not applied to the shared consumer factory.
- **Violated invariant:** a correctness-critical client property should be owned by the application, not inherited.
- **Root cause:** the property was never added to `buildConsumerProperties()`.
- **Impact:** latent — safe today, silently unsafe after any refactor of the consumer wiring.
- **Proposed fix:** add `props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");` to `buildConsumerProperties()` (`KafkaProperties.java:99-106`).
- **Reproduction test:** assert `buildConsumerProperties().get("enable.auto.commit")` equals `"false"`.
- **Acceptance criterion:** `enable.auto.commit=false` is present in `buildConsumerProperties()` and asserted by a unit test.

---

### KAF-16 — LOW — order-service's non-transactional fallback paths would break atomicity if the tx manager were absent
- **Evidence:** `order-service/.../impls/OrderServiceImpl.java:263-265`:
  ```java
  return (cancelTransactionTemplate != null)
          ? cancelTransactionTemplate.execute(tx -> applyCancelledStatus(order))
          : applyCancelledStatus(order);
  ```
  and `:355-363`:
  ```java
  OrderResponse response = (transactionTemplate != null)
          ? transactionTemplate.execute(tx -> { ... orderEventPublisher.publishStatusChanged(order); ... })
          : applyConfirmedStatus(order, items);
  ```
  `cancelTransactionTemplate`/`transactionTemplate` are `null` only in the test constructor (`:129-146`).
- **Actual behaviour:** in the fallback, `orderRepository.save(order)` and `orderEventPublisher.publishCancelled(order)` run in **two separate auto-commit transactions** — the exact failure the transactional-outbox pattern exists to prevent. Not reachable in production (the Spring-wired bean always has a transaction manager), but it is a silent correctness cliff guarded only by a null check.
- **Violated invariant:** the business write and the outbox row must never be committable independently.
- **Root cause:** the fallback exists to support unit tests that do not wire a transaction manager.
- **Impact:** low in production; a test using the null-manager constructor would silently exercise a non-atomic path and could hide a regression.
- **Proposed fix:** make the transaction manager a required constructor argument (the tests can supply a `TestTransactionManager` or use `@Transactional` test support), and remove the fallback branches.
- **Reproduction test:** an IT asserting that a forced exception in `publishCreated` rolls the order row back — i.e. that the order and the outbox row are all-or-nothing.
- **Acceptance criterion:** no production code path writes an order and its outbox event outside an explicit transaction.

---

### KAF-17 — LOW — The legacy double-encoding `KafkaTemplate` is still registered and is the default type-match
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java:49-63` registers a `KafkaTemplate<String, Object>` whose producer uses `JsonKafkaSerializer` — the class that **double-encodes** a JSON string (`utils/common-kafka/.../serialization/JsonKafkaSerializer.java:14-18`, `@Deprecated(forRemoval = true)` at `:31`). The outbox-safe template is the separately-named `stringKafkaTemplate` (`:70-77`).
  Every current call site uses the safe one (`@Qualifier("stringKafkaTemplate")` in all 6 `BaseKafkaListenerConfig` subclasses, and `KafkaMessagePublisher` is constructed with it at `KafkaAutoConfiguration.java:98-101`), so **nothing is currently broken** — verified by grep: the only unqualified `KafkaTemplate` injection in the codebase is `SearchListenerConfig.java:22`, which uses `ObjectProvider<KafkaTemplate<String,String>>` and therefore matches the **String** template by generics, not the legacy one.
- **Actual behaviour:** the deprecated bean remains injectable. A future contributor writing `private final KafkaTemplate<String, String> template;` (by type, without the qualifier) would get the **legacy** bean and emit double-encoded records that only the legacy unwrap-once path tolerates (`BaseKafkaConsumer.java:98-101`).
- **Violated invariant:** the deprecated path should be unreachable, not merely discouraged.
- **Root cause:** the bean was kept "for any non-outbox caller" (`KafkaAutoConfiguration.java:38-41`) with no current callers.
- **Impact:** latent footgun on a wire format that the whole fleet carefully standardised on.
- **Proposed fix:** delete the `kafkaProducerFactory`/`kafkaTemplate` beans (`KafkaAutoConfiguration.java:49-63`) and `JsonKafkaSerializer` (now that no caller exists), and let the compiler prove it.
- **Reproduction test:** a context test asserting that exactly one `KafkaTemplate` bean exists, and that it is `KafkaTemplate<String,String>` with `StringSerializer` for both key and value.
- **Acceptance criterion:** `grep -rn "JsonKafkaSerializer" --include=*.java` returns no production hits.

---

### KAF-18 — LOW — DLT replay drops the original headers (including `traceparent`)
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java:105`:
  ```java
  template.send(new ProducerRecord<>(originalTopic, record.key(), record.value())).get();
  ```
  The new record carries **no headers**, so the W3C `traceparent` that `KafkaMessagePublisher.traceparentRecord` injected on the original publish (`:239-246`) is lost.
- **Actual behaviour:** a replayed record starts a fresh trace instead of linking to the original, breaking the end-to-end trace across a poison-record recovery. The original key **is** preserved, so partition affinity is retained — good.
- **Violated invariant:** a replay should preserve the observable context of the original record.
- **Root cause:** the replay constructs a bare `ProducerRecord` rather than copying the source record's headers.
- **Impact:** low — observability only.
- **Proposed fix:** copy the source headers onto the replayed record (`new ProducerRecord<>(originalTopic, null, record.key(), record.value(), record.headers())`), adding the replay headers.
- **Reproduction test:** publish → poison → replay → assert the replayed record carries the original `traceparent`.
- **Acceptance criterion:** replayed records retain the original headers.

---

### KAF-19 — LOW — No `max.poll.interval.ms` is configured for any listener
- **Evidence:** `utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaProperties.java:99-106` sets only `bootstrap.servers`, `group.id`, `auto.offset.reset`, `key.deserializer`. `max.poll.interval.ms` is absent from every `application.yml` and from `buildConsumerProperties()`.
- **Actual behaviour:** the Kafka client default of **300 000 ms (5 min)** applies. The longest handler is `orderService.confirmOrder` (`order-service/.../impls/OrderServiceImpl.java:317-379`), which performs a remote payment check (`:334-345`) and a commit-coordinator saga (`:349`). If it exceeds 5 min the consumer is evicted from the group, the partition is reassigned, and the record is reprocessed from the last committed offset — a rebalance storm. The result is still *correct* (idempotent handlers, §4) but it amplifies load exactly when the system is already degraded.
- **Violated invariant:** the consumer's poll timeout should be sized against the slowest handler.
- **Root cause:** `max.poll.interval.ms` was never added to the shared consumer properties.
- **Impact:** low-to-moderate under degradation; no data loss.
- **Proposed fix:** set `max.poll.interval.ms` explicitly in `buildConsumerProperties()` (e.g. 900 000) and size it against the slowest handler, or make the handler's remote calls bounded so they cannot approach the limit.
- **Reproduction test:** a consumer IT that stalls the handler past the configured `max.poll.interval.ms` and asserts no eviction occurs.
- **Acceptance criterion:** `max.poll.interval.ms` is set explicitly in `buildConsumerProperties()` and is greater than the worst-case handler duration.

---

### KAF-20 — MEDIUM — The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket
- **Evidence:** the replay actuator endpoint is gated on a property that is set nowhere:
  ```java
  // utils/common-kafka/src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java:88-94
  @Bean
  @ConditionalOnClass(org.springframework.boot.actuate.endpoint.annotation.Endpoint.class)
  @ConditionalOnProperty(prefix = "shop.kafka.dlq-replay", name = "enabled", havingValue = "true")
  @ConditionalOnMissingBean
  public DeadLetterReplayEndpoint deadLetterReplayEndpoint(DeadLetterReplayService replayService) {
      return new DeadLetterReplayEndpoint(replayService);
  }
  ```
  `grep -rn "dlq-replay" --include=*.yml --include=*.yaml .` → **no matches** in any service, in `docker-compose*.yml`, or in `k8s/`.
  The replay implementation itself exists and is unit-tested (`utils/common-kafka/src/main/java/com/shop/common/kafka/replay/DeadLetterReplayService.java:64-127`, `utils/common-kafka/src/test/java/com/shop/common/kafka/replay/DeadLetterReplayServiceTest.java`).
- **Actual behaviour:** every listener that dead-letters a poison record writes it to `<topic>.DLT` and moves on. **No operator-facing path exists to read, inspect, or drain that topic** in any deployed configuration. The only way to recover a dead-lettered record is to attach a raw Kafka client by hand. Combined with KAF-05 (product/media has no DLT at all) and the two self-decoding consumers that ack-skip poison records (§7.7b), a large fraction of poison records are **unrecoverable by design**.
- **Violated invariant:** a dead-letter queue must have a drain path; a DLT without a replay mechanism is an expensive black hole.
- **Root cause:** the endpoint was implemented behind a safety flag and the flag was never turned on, and no runbook documents the manual procedure that would otherwise be needed.
- **Impact:** every poison record that reaches a DLT is permanently lost to the platform's own tooling. Since DLT records are by definition records the platform could not process, this is the exact population an operator most needs to be able to replay.
- **Proposed fix:** enable the endpoint in the deployed profiles (`shop.kafka.dlq-replay.enabled: true` for the non-prod profiles, with the endpoint behind the existing actuator security rules), and add a runbook section describing the manual `KafkaConsumer` fallback for prod.
- **Reproduction test:** an actuator IT that publishes a poison record, waits for the DLT, invokes the endpoint, and asserts the record is republished to the original topic.
- **Acceptance criterion:** `shop.kafka.dlq-replay.enabled` is set in at least one deployed profile, and a runbook documents the DLT drain procedure for every service that owns a DLT.

---

## 10. WHAT IS CORRECT — POSITIVE FINDINGS (do not "fix" these)

1. **The transactional-outbox pattern is genuinely and correctly implemented in 8/8 services.** Business write + outbox insert share one transaction in every service, verified per service in §3(a) with the exact `@Transactional` boundary cited.
2. **The claim → publish → mark relay never holds a DB lock across a Kafka publish** — the single most important property of the pattern (`OrderOutboxRelay.java:116-147,169-174` and 7 siblings).
3. **The mark phase is a compare-and-set under `PESSIMISTIC_WRITE`** — `findByIdForUpdate` + `status != SENDING` guard in 8/8, closing the read-then-write race with the stale sweep.
4. **`FOR UPDATE SKIP LOCKED` is used correctly and portably** (`jakarta.persistence.lock.timeout = -2`) in 8/8, and the safety property (no double-publish across replicas) is proven by 8 dedicated concurrency ITs.
5. **A claim lease exists in every implementation**, so a crashed relay cannot strand an event silently.
6. **The topic contract is enforced, not aspirational.** `KafkaTopicContract` is the single source of truth, and `KafkaTopicContractTest` proves (a) every consumed topic is declared, (b) every `CONSUMED` topic has a listener, (c) every `FIRE_AND_FORGET` topic states why, (d) every declared topic is really produced, and (e) **no topic literal exists outside the contract** (`:138-144` scans every production source for `"shop.*.v1"` / `"media.lifecycle.v1"` literals).
7. **Every `@KafkaListener` pins its consumer group id**, enforced by `KafkaListenerGroupIdContractTest` — this prevents two services from silently competing for the same partitions and prevents accidental offset resets.
8. **The DLT path is real and proven** by an embedded-Kafka test that publishes malformed bytes and asserts both "a record reached the DLT" and "the listener never saw the payload" (`BaseKafkaConsumerDeadLetterTest.java:83-94`).
9. **Consumer-side idempotency holds for all 7 consumers**, with durable UNIQUE/PK-backed dedup for the 3 that perform effects (notification, shipment, order-confirm).
10. **The wire format is single-encoded and uniformly decoded.** The producer forwards the JSON string raw (`KafkaMessagePublisher.java:111,177,240`); consumers unwrap once and tolerate the legacy double-encoded shape (`BaseKafkaConsumer.java:96-101`).
11. **Additive schema evolution works**: all 7 consumer DTOs carry `@JsonIgnoreProperties(ignoreUnknown = true)`.
12. **Producer durability settings are the canonical recipe**: `acks=all`, `retries=3`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=1` (`KafkaProperties.java:83-91,154-163`) — per-partition ordering is preserved across retries.
13. **Per-aggregate ordering is enforced on the wire** via the partition key = aggregate id in 8/8 relays, and (with the one payment-service exception, KAF-02) via the `OutboxBatchOrdering` barrier in 7/8.
14. **Retention exists in 8/8**, with a partial `PENDING` drain index (`CREATE INDEX idx_outbox_pending_id ON outbox_events (id) WHERE status = 'PENDING'`) keeping drain latency flat as history grows.
15. **The `shop.kafka.*` namespace is used consistently** — every service configures `shop.kafka.bootstrap-servers` (not `spring.kafka.*`), so the `SHOP_KAFKA_BOOTSTRAP_SERVERS` env var genuinely reaches the shared producer/consumer factories.

---

## 11. FILE INVENTORY SUMMARY

- **Source files read:** 118 (56 production Java, 20 test Java, 17 Liquibase YAML, 11 `application.yml`, plus the shared-module and configuration files listed in §2).
- **Outbox implementations found:** 8 (order, product, media, rating, shipping, payment, promotion, inventory) — the brief named five; **product, promotion, rating and shipping were additionally verified**.
- **Kafka consumers found:** 7 `@KafkaListener` methods across 7 classes in 6 services (order-service has two).
- **Topics declared:** 10 — **all 10 topics named in the task brief VERIFIED EXISTS, none NOT FOUND, none DIFFERENT NAME**.
- **Durable inbox/dedup tables for Kafka consumers:** 0 generic; 3 domain-specific (`notifications.event_id` UNIQUE, `shipments(order_id) WHERE deleted=false` UNIQUE, `idempotency_keys(actor,key)` PK).
- **Producer transactions:** none (`transaction.id` / `KafkaTransactionManager` absent repo-wide).
- **Defects:** 20 — 2 HIGH, 8 MEDIUM, 10 LOW.
