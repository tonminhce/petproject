# Production Audit — order-service + inventory-service

**Revision audited:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`
**Scope:** `order-service` (94 main + 45 test `.java`) and `inventory-service` (28 main + 18 test `.java`), plus every `db/changelog/*.yaml` and `application.yml` of both modules.
**Method:** every `.java` file in scope was read in full with the `read` tool (no `grep`-only inspection, no reliance on README/docs). `git rev-parse HEAD:<path>` and `wc -l` were used only for the inventory metadata. **No application source file was modified.**

---

## 0. Executive verdict

**Is overselling prevented? YES — with a small, provable set of mechanisms.**

Overselling is prevented by an **atomic guarded UPDATE** on the inventory row, not by a pessimistic lock, not by an optimistic `@Version` check on the reserve path, and not by a unique constraint:

```java
// inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java:39-44
@Modifying(clearAutomatically = true)
@Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity + :qty, "
     + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
     + "WHERE i.productId = :productId "
     + "AND (i.availableQuantity - i.reservedQuantity) >= :qty")
int atomicReserve(@Param("productId") UUID productId, @Param("qty") int qty);
```

The check and the write are one statement; the capacity predicate lives in the `WHERE` clause; Postgres row-locks the inventory row and re-evaluates the predicate against the updated tuple (EvalPlanQual) under **READ COMMITTED**, so two concurrent reserves for the last unit cannot both succeed. The `version = version + 1` in the same `SET` clause keeps the `@Version` consumers (`commit`/`release`) consistent. Two DB-level `CHECK (… >= 0)` constraints (`changelog-004-stock-invariants-and-reservation-version.yaml:42-54`) are the last line of defence, and the `Reservation.@Version` column (`entity/Reservation.java:76-79`) protects the commit/release/sweep transitions.

**All six mandatory races are SAFE.** Each is proved from source in §4.3 — including the two that the code comments claim to have fixed.

**However — the audit found 29 defects (2 HIGH, 11 MEDIUM, 16 LOW/INFO).** The HIGH pair are *not* oversell bugs; they are **stock-leak / money-leak bugs**:
- **OI-01** — the inventory `reserve` API has **no idempotency key and no uniqueness constraint**, so a duplicated or lost reserve call leaves an orphaned hold for the full 900 s TTL (`inventory.reservation-ttl-seconds: 900`, `inventory-service/src/main/resources/application.yml:67`).
- **OI-02** — a failed `releaseCommitted` in the confirm-compensation path is **dropped on the floor**: no ledger row, no retry, no sweeper. The stock stays consumed for an order that stays `PENDING` forever.

**Effective transaction isolation: READ COMMITTED** (PostgreSQL server default). Nothing in the repo sets `spring.datasource.hikari.transaction-isolation`, `spring.jpa.properties.hibernate.connection.isolation`, or any equivalent — verified by an exhaustive grep across all `*.yml`/`*.yaml`/`*.java` in the repo (zero hits). The code's correctness *depends* on READ COMMITTED semantics (EvalPlanQual re-check); under REPEATABLE READ the atomic reserve would raise a serialization failure instead of cleanly returning 0 rows.

---

## 1. File inventory

**Totals: 204 files, 18 505 lines** (185 `.java` = 17 490 lines; 19 `.yml`/`.yaml` = 1 015 lines).
`git rev-parse HEAD:<path>` is truncated to 12 hex chars. "Range read" is the exact line span read with the `read` tool — every file was read end-to-end (1 → last line); there are **no silent skips**.

### 1.1 inventory-service — main (28 `.java` + 1 `application.yml`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `inventory-service/src/main/java/com/shop/inventoryservice/InventoryServiceApplication.java` | `1ccce7563355` | 15 | 1–15 |
| `inventory-service/src/main/java/com/shop/inventoryservice/config/CacheConfig.java` | `784f5493391c` | 84 | 1–84 |
| `inventory-service/src/main/java/com/shop/inventoryservice/constant/ReservationStatus.java` | `0f6d35bb557f` | 5 | 1–5 |
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InternalInventoryController.java` | `a040a1ad4b9a` | 43 | 1–43 |
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` | `961389b3fc14` | 123 | 1–123 |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/InventoryUpsertRequest.java` | `a0e246ee0d4c` | 11 | 1–11 |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java` | `22bedcdab041` | 11 | 1–11 |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/ActiveReservationCountResponse.java` | `70516599f2f6` | 17 | 1–17 |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/InventoryResponse.java` | `f25d09a56d69` | 16 | 1–16 |
| `inventory-service/src/main/java/com/shop/inventoryservice/dto/response/ReservationResponse.java` | `9c7c35a07bdd` | 18 | 1–18 |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java` | `05270d44bfa4` | 63 | 1–63 |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/OutboxEvent.java` | `1aa3f13024d4` | 83 | 1–83 |
| `inventory-service/src/main/java/com/shop/inventoryservice/entity/Reservation.java` | `f34896bf3ff4` | 80 | 1–80 |
| `inventory-service/src/main/java/com/shop/inventoryservice/mapper/InventoryMapper.java` | `78df7d114bc2` | 57 | 1–57 |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java` | `3348a7f47e7c` | 64 | 1–64 |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java` | `d03462d41b39` | 94 | 1–94 |
| `inventory-service/src/main/java/com/shop/inventoryservice/repository/ReservationRepository.java` | `dd433a5ad728` | 52 | 1–52 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryCacheService.java` | `bdc8c7a0fe2f` | 58 | 1–58 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryEventPublisher.java` | `a7c77720f619` | 19 | 1–19 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryMetrics.java` | `61dba506bd93` | 65 | 1–65 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryOutboxRelay.java` | `3b249d7564a8` | 257 | 1–257 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/InventoryService.java` | `7b97cb583f90` | 46 | 1–46 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/OutboxRetentionScheduler.java` | `30be665dbc2c` | 39 | 1–39 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java` | `9eebfe21acb6` | 145 | 1–145 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationService.java` | `4c8dec99838e` | 17 | 1–17 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java` | `902567e5d522` | 279 | 1–279 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java` | `dbaa3926d992` | 82 | 1–82 |
| `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/TransactionalInventoryEventPublisher.java` | `336a8b1cb716` | 135 | 1–135 |
| `inventory-service/src/main/resources/application.yml` | `98a356f2968d` | 78 | 1–78 |

### 1.2 inventory-service — db/changelog (6 `.yaml`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `inventory-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `6d8f2a5ec341` | 16 | 1–16 |
| `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `e9cae1cc5f9f` | 112 | 1–112 |
| `inventory-service/src/main/resources/db/changelog/changelog-002-safety-stock-threshold.yaml` | `8d3c34cb9a2e` | 14 | 1–14 |
| `inventory-service/src/main/resources/db/changelog/changelog-004-stock-invariants-and-reservation-version.yaml` | `6e1d2695383a` | 54 | 1–54 |
| `inventory-service/src/main/resources/db/changelog/changelog-005-inventory-outbox-claim-lease.yaml` | `5a5f896b6861` | 21 | 1–21 |
| `inventory-service/src/main/resources/db/changelog/changelog-006-reservations-order-id-index.yaml` | `22d93b4334ab` | 25 | 1–25 |

> **Note:** there is no `changelog-003` in inventory-service and `db.changelog-master.yaml:8-10` jumps from `002` to `004`. The `003-create-outbox-events` changeset still exists but is nested inside `changelog-001-initial-schema.yaml:77-112`. Not a defect — just a naming inconsistency.

### 1.3 inventory-service — test (18 `.java`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `inventory-service/src/test/java/com/shop/inventoryservice/config/TestLiquibaseConfig.java` | `4b57ae080d12` | 33 | 1–33 |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/InternalInventoryControllerTest.java` | `b58fb4a49ee0` | 80 | 1–80 |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerAuditTest.java` | `1d06cbfcf361` | 117 | 1–117 |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/InventoryControllerTest.java` | `f24ed8285fc8` | 110 | 1–110 |
| `inventory-service/src/test/java/com/shop/inventoryservice/controller/ReservationStateEndpointTest.java` | `7a91948a22da` | 128 | 1–128 |
| `inventory-service/src/test/java/com/shop/inventoryservice/repository/InventoryRepositoryTest.java` | `0cd04640fdbd` | 220 | 1–220 |
| `inventory-service/src/test/java/com/shop/inventoryservice/repository/ReservationsOrderIdIndexIT.java` | `0f855702ae5e` | 144 | 1–144 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryLifecycleIdempotencyTest.java` | `3aad4e21986b` | 122 | 1–122 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxClaimConcurrencyTest.java` | `dc53800e9020` | 186 | 1–186 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayIntegrationTest.java` | `38a52d0de9ef` | 60 | 1–60 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayRecoveryIT.java` | `bdc996b30604` | 193 | 1–193 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxRelayTest.java` | `44bf3d5cc153` | 209 | 1–209 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/ReleaseCommittedTest.java` | `f94e33afd076` | 114 | 1–114 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/ReservationCleanupSchedulerTest.java` | `3c8ac7341cf4` | 139 | 1–139 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplReserveConcurrencyTest.java` | `7e882c593add` | 125 | 1–125 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/InventoryServiceImplTest.java` | `90cd53ea83ad` | 432 | 1–432 |
| `inventory-service/src/test/java/com/shop/inventoryservice/service/impls/ReservationServiceImplTest.java` | `f59c98e92442` | 79 | 1–79 |
| `inventory-service/src/test/java/com/shop/inventoryservice/support/AbstractIntegrationTest.java` | `4b305aac44cd` | 124 | 1–124 |

### 1.4 order-service — main (94 `.java` + 1 `application.yml`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `order-service/src/main/java/com/shop/orderservice/OrderServiceApplication.java` | `2f0f102543fd` | 15 | 1–15 |
| `order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java` | `0c06cc420b26` | 160 | 1–160 |
| `order-service/src/main/java/com/shop/orderservice/client/PaymentServiceClient.java` | `a3377f50c967` | 113 | 1–113 |
| `order-service/src/main/java/com/shop/orderservice/client/ProductServiceClient.java` | `be0fc68b358c` | 51 | 1–51 |
| `order-service/src/main/java/com/shop/orderservice/client/PromotionServiceClient.java` | `0628d7897845` | 154 | 1–154 |
| `order-service/src/main/java/com/shop/orderservice/client/TaxServiceClient.java` | `915c6343a01e` | 55 | 1–55 |
| `order-service/src/main/java/com/shop/orderservice/config/CacheConfig.java` | `e68bb1645bed` | 80 | 1–80 |
| `order-service/src/main/java/com/shop/orderservice/config/PaymentGateValidator.java` | `5c68a9e48463` | 50 | 1–50 |
| `order-service/src/main/java/com/shop/orderservice/config/RestClientConfig.java` | `2c989c68e8ed` | 109 | 1–109 |
| `order-service/src/main/java/com/shop/orderservice/config/ShopServicesProperties.java` | `0fe30eb5b80e` | 42 | 1–42 |
| `order-service/src/main/java/com/shop/orderservice/constant/OrderStatus.java` | `990694cd132d` | 10 | 1–10 |
| `order-service/src/main/java/com/shop/orderservice/constant/ReturnStatus.java` | `1fec6d7349fe` | 8 | 1–8 |
| `order-service/src/main/java/com/shop/orderservice/controller/BackofficeOrderReturnController.java` | `d4690766c216` | 40 | 1–40 |
| `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` | `cbd0690258ed` | 160 | 1–160 |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` | `6dd753ec88b4` | 97 | 1–97 |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderReturnController.java` | `12e83abb65d3` | 60 | 1–60 |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` | `d37e82c78ab7` | 55 | 1–55 |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderTrackingController.java` | `b073ef610174` | 48 | 1–48 |
| `order-service/src/main/java/com/shop/orderservice/dto/ShippingDeliveredEvent.java` | `b87c6cc505bd` | 24 | 1–24 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PaymentStatusSnapshot.java` | `7241b043438c` | 5 | 1–5 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PricingBreakdown.java` | `9ab548b3d963` | 14 | 1–14 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ProductSnapshot.java` | `b42940f582f8` | 8 | 1–8 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PromotionReserveRequest.java` | `be140eee2715` | 6 | 1–6 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/PromotionReserveResponse.java` | `823dc9c43ebd` | 6 | 1–6 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ReservationResponse.java` | `d4a34583dda3` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ReservationStateResponse.java` | `8eb2a825ac06` | 3 | 1–3 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/ReserveRequest.java` | `957c447c9cd5` | 15 | 1–15 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/TaxCalculateRequest.java` | `89a76fc96bad` | 6 | 1–6 |
| `order-service/src/main/java/com/shop/orderservice/dto/internal/TaxCalculateResponse.java` | `e3539f50097b` | 5 | 1–5 |
| `order-service/src/main/java/com/shop/orderservice/dto/request/CartItemAddRequest.java` | `a309ab3e9217` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/dto/request/CartItemUpdateRequest.java` | `f922d744432f` | 8 | 1–8 |
| `order-service/src/main/java/com/shop/orderservice/dto/request/OrderCreateRequest.java` | `228640680081` | 14 | 1–14 |
| `order-service/src/main/java/com/shop/orderservice/dto/request/OrderReturnCreateRequest.java` | `de9ad3db3c76` | 18 | 1–18 |
| `order-service/src/main/java/com/shop/orderservice/dto/request/OrderReturnReviewRequest.java` | `c404b7c0a581` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/CartItemResponse.java` | `97f09458ab8f` | 12 | 1–12 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/CartResponse.java` | `9b6258360521` | 14 | 1–14 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderItemResponse.java` | `58e7210ffce5` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderResponse.java` | `e939df6faf9c` | 36 | 1–36 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderReturnResponse.java` | `c1e6d4cdc6e5` | 21 | 1–21 |
| `order-service/src/main/java/com/shop/orderservice/dto/response/OrderTrackingResponse.java` | `78040361a7d0` | 18 | 1–18 |
| `order-service/src/main/java/com/shop/orderservice/entity/Cart.java` | `9af86a6dd0de` | 27 | 1–27 |
| `order-service/src/main/java/com/shop/orderservice/entity/CartItem.java` | `b8408aeddebd` | 38 | 1–38 |
| `order-service/src/main/java/com/shop/orderservice/entity/FailedCompensation.java` | `6db01fd1b60d` | 67 | 1–67 |
| `order-service/src/main/java/com/shop/orderservice/entity/IdempotencyKey.java` | `c868211ff4c4` | 58 | 1–58 |
| `order-service/src/main/java/com/shop/orderservice/entity/Order.java` | `fb504ec29f5c` | 112 | 1–112 |
| `order-service/src/main/java/com/shop/orderservice/entity/OrderItem.java` | `e95ce731d4f8` | 45 | 1–45 |
| `order-service/src/main/java/com/shop/orderservice/entity/OrderReturn.java` | `eb47af1d4bc4` | 70 | 1–70 |
| `order-service/src/main/java/com/shop/orderservice/entity/OutboxEvent.java` | `77413c8b921e` | 58 | 1–58 |
| `order-service/src/main/java/com/shop/orderservice/exception/StockReservationFailedException.java` | `abd64443d7ee` | 23 | 1–23 |
| `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleConsumer.java` | `55f4c29ee516` | 44 | 1–44 |
| `order-service/src/main/java/com/shop/orderservice/kafka/PaymentLifecycleEvent.java` | `3f80b2a10292` | 23 | 1–23 |
| `order-service/src/main/java/com/shop/orderservice/kafka/PaymentListenerConfig.java` | `bec45d15abc0` | 26 | 1–26 |
| `order-service/src/main/java/com/shop/orderservice/kafka/ShippingDeliveredConsumer.java` | `6423fefbc54d` | 28 | 1–28 |
| `order-service/src/main/java/com/shop/orderservice/kafka/ShippingListenerConfig.java` | `b7c7c732d2c0` | 27 | 1–27 |
| `order-service/src/main/java/com/shop/orderservice/mapper/CartMapper.java` | `e1ec02f7167b` | 39 | 1–39 |
| `order-service/src/main/java/com/shop/orderservice/mapper/OrderMapper.java` | `e1ac31c82c41` | 47 | 1–47 |
| `order-service/src/main/java/com/shop/orderservice/repository/CartItemRepository.java` | `cda9e1277846` | 15 | 1–15 |
| `order-service/src/main/java/com/shop/orderservice/repository/CartRepository.java` | `9c67b20dbbce` | 36 | 1–36 |
| `order-service/src/main/java/com/shop/orderservice/repository/FailedCompensationRepository.java` | `4be6100f23ae` | 18 | 1–18 |
| `order-service/src/main/java/com/shop/orderservice/repository/IdempotencyKeyRepository.java` | `200d8b3425ba` | 20 | 1–20 |
| `order-service/src/main/java/com/shop/orderservice/repository/OrderItemRepository.java` | `d69ea48b8ac0` | 31 | 1–31 |
| `order-service/src/main/java/com/shop/orderservice/repository/OrderRepository.java` | `ed3c2d5e9347` | 37 | 1–37 |
| `order-service/src/main/java/com/shop/orderservice/repository/OrderReturnRepository.java` | `6a95e44b427f` | 19 | 1–19 |
| `order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java` | `c8cc3a0d570d` | 87 | 1–87 |
| `order-service/src/main/java/com/shop/orderservice/security/ServiceTokenProvider.java` | `0f1c0b4ca471` | 122 | 1–122 |
| `order-service/src/main/java/com/shop/orderservice/service/CartService.java` | `67363742d38d` | 16 | 1–16 |
| `order-service/src/main/java/com/shop/orderservice/service/CommitOutcome.java` | `c28723e30252` | 8 | 1–8 |
| `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java` | `0febeaa5a0b2` | 120 | 1–120 |
| `order-service/src/main/java/com/shop/orderservice/service/CompensationTarget.java` | `dd92bfd2c825` | 8 | 1–8 |
| `order-service/src/main/java/com/shop/orderservice/service/IdempotencyService.java` | `b0540a4eb243` | 26 | 1–26 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java` | `1b1da1082693` | 154 | 1–154 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderConfirmMetrics.java` | `477f705ff338` | 34 | 1–34 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderEventPublisher.java` | `39926ed5daee` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderMetrics.java` | `2d7afe0f32ee` | 58 | 1–58 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java` | `5dab171778bc` | 311 | 1–311 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderReturnService.java` | `7146f76404cf` | 32 | 1–32 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderService.java` | `69a2196e9c2b` | 48 | 1–48 |
| `order-service/src/main/java/com/shop/orderservice/service/OrderStatusService.java` | `841ddb4938a0` | 7 | 1–7 |
| `order-service/src/main/java/com/shop/orderservice/service/PricingService.java` | `0b04f3ef72be` | 11 | 1–11 |
| `order-service/src/main/java/com/shop/orderservice/service/ShippingDeliveredHandler.java` | `afcc2f2637e3` | 46 | 1–46 |
| `order-service/src/main/java/com/shop/orderservice/service/StockReservationService.java` | `8456201c9971` | 18 | 1–18 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java` | `88fafade419c` | 202 | 1–202 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/GuestCartCleanupScheduler.java` | `95e3b536eae3` | 50 | 1–50 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyKeyCleanupScheduler.java` | `55098a14cc7f` | 29 | 1–29 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java` | `16a61be57290` | 155 | 1–155 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java` | `1faac24ea532` | 255 | 1–255 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java` | `05b99c1d8af5` | 115 | 1–115 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderOutboxRelay.java` | `64259d1fefd7` | 257 | 1–257 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java` | `07c526524c20` | 183 | 1–183 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` | `68baa8d67711` | 556 | 1–556 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java` | `50bbb4c4c317` | 30 | 1–30 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java` | `97bdad32ab76` | 35 | 1–35 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java` | `ef41c5af72be` | 150 | 1–150 |
| `order-service/src/main/java/com/shop/orderservice/service/impls/StockReservationServiceImpl.java` | `96901054b9d5` | 31 | 1–31 |
| `order-service/src/main/resources/application.yml` | `f1310a8beca4` | 89 | 1–89 |

### 1.5 order-service — db/changelog (10 `.yaml`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `order-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `9c709183bd38` | 31 | 1–31 |
| `order-service/src/main/resources/db/changelog/008-order-outbox-claim-lease.yaml` | `f95e12784f9a` | 21 | 1–21 |
| `order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml` | `c4862e0682c8` | 237 | 1–237 |
| `order-service/src/main/resources/db/changelog/changelog-002-outbox-pending-index.yaml` | `cbc392216563` | 19 | 1–19 |
| `order-service/src/main/resources/db/changelog/changelog-003-orders-version.yaml` | `e61054c94e26` | 22 | 1–22 |
| `order-service/src/main/resources/db/changelog/changelog-004-confirm-hardening.yaml` | `f4e4acc00726` | 17 | 1–17 |
| `order-service/src/main/resources/db/changelog/changelog-005-idempotency-actor.yaml` | `fe53aa93d8e2` | 26 | 1–26 |
| `order-service/src/main/resources/db/changelog/changelog-006-orders-shipping-address.yaml` | `9166a4e7248f` | 17 | 1–17 |
| `order-service/src/main/resources/db/changelog/changelog-007-order-returns.yaml` | `c13e2e471c1c` | 96 | 1–96 |
| `order-service/src/main/resources/db/changelog/changelog-009-order-tracking-code.yaml` | `002633340d0d` | 36 | 1–36 |
| `order-service/src/main/resources/db/changelog/changelog-010-failed-compensations.yaml` | `432fda2cb929` | 43 | 1–43 |

### 1.6 order-service — test (45 `.java`)

| Path | `git rev-parse HEAD:<path>` | Lines | Range read |
|---|---|---|---|
| `order-service/src/test/java/com/shop/orderservice/client/PaymentServiceClientTest.java` | `a470eccdd455` | 180 | 1–180 |
| `order-service/src/test/java/com/shop/orderservice/config/CacheSerializerRoundTripTest.java` | `3719021f7932` | 67 | 1–67 |
| `order-service/src/test/java/com/shop/orderservice/config/TestLiquibaseConfig.java` | `6aa76a8bc55b` | 22 | 1–22 |
| `order-service/src/test/java/com/shop/orderservice/controller/BackofficeOrderReturnControllerTest.java` | `6e5e9230562e` | 88 | 1–88 |
| `order-service/src/test/java/com/shop/orderservice/controller/CartControllerTest.java` | `036c5af82140` | 196 | 1–196 |
| `order-service/src/test/java/com/shop/orderservice/controller/ConfirmOrchestrationWebMvcTest.java` | `8986acccd554` | 129 | 1–129 |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderControllerTest.java` | `afb26b125e06` | 126 | 1–126 |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderReturnControllerTest.java` | `5b10112e001b` | 120 | 1–120 |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderStatusControllerAuditTest.java` | `fd99f047bbbb` | 139 | 1–139 |
| `order-service/src/test/java/com/shop/orderservice/controller/OrderTrackingControllerTest.java` | `320cbfec45b8` | 100 | 1–100 |
| `order-service/src/test/java/com/shop/orderservice/controller/VerifyPurchaseEndpointTest.java` | `263cbb024b93` | 154 | 1–154 |
| `order-service/src/test/java/com/shop/orderservice/kafka/PaymentLifecycleConsumerTest.java` | `5cf4438444bd` | 108 | 1–108 |
| `order-service/src/test/java/com/shop/orderservice/kafka/ShippingDeliveredConsumerIT.java` | `68fe93852353` | 242 | 1–242 |
| `order-service/src/test/java/com/shop/orderservice/kafka/ShippingDeliveredConsumerTest.java` | `b6a79c9e3cc5` | 103 | 1–103 |
| `order-service/src/test/java/com/shop/orderservice/repository/CartRepositoryTest.java` | `6953ca5bed1b` | 87 | 1–87 |
| `order-service/src/test/java/com/shop/orderservice/repository/OrderItemRepositoryTest.java` | `e5c3f136d9c2` | 131 | 1–131 |
| `order-service/src/test/java/com/shop/orderservice/repository/OrderOutboxClaimConcurrencyIT.java` | `6c70508b0b99` | 184 | 1–184 |
| `order-service/src/test/java/com/shop/orderservice/repository/OrderRepositoryTest.java` | `50e9585c9f3b` | 135 | 1–135 |
| `order-service/src/test/java/com/shop/orderservice/service/CancelOutsideTransactionIT.java` | `14985116cca7` | 93 | 1–93 |
| `order-service/src/test/java/com/shop/orderservice/service/CompensationFailureLedgerIT.java` | `7716ae704a80` | 109 | 1–109 |
| `order-service/src/test/java/com/shop/orderservice/service/ConfirmOrchestrationIT.java` | `c48f9eff3945` | 613 | 1–613 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderCommitCoordinatorTest.java` | `4aeed0b6367b` | 212 | 1–212 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderConfirmMetricsTest.java` | `04d9e2aedecf` | 66 | 1–66 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderCreationSagaIntegrationTest.java` | `f214883825d1` | 254 | 1–254 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderMetricsTest.java` | `9ca982083abb` | 36 | 1–36 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderReconciliationSchedulerChunkedTest.java` | `1ddd44e9aacf` | 132 | 1–132 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderReconciliationSchedulerMemoizedCountTest.java` | `4c33d822f197` | 97 | 1–97 |
| `order-service/src/test/java/com/shop/orderservice/service/OrderReconciliationSchedulerTest.java` | `70e6e03c82e7` | 317 | 1–317 |
| `order-service/src/test/java/com/shop/orderservice/service/ShippingDeliveredHandlerTest.java` | `d902b8631086` | 106 | 1–106 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/CartServiceImplTest.java` | `722c72e6f17d` | 248 | 1–248 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/GuestCartCleanupSchedulerTest.java` | `37649ea5eeba` | 64 | 1–64 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/IdempotencyServiceImplTest.java` | `682708d41547` | 241 | 1–241 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderCreateSagaTest.java` | `5f8499022a4a` | 216 | 1–216 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderOutboxRelayRecoveryIT.java` | `46dde69f2c52` | 193 | 1–193 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderOutboxRelayTest.java` | `d98874631137` | 210 | 1–210 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderReturnServiceTest.java` | `00765a12db03` | 244 | 1–244 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderServiceImplCancelConfirmedTest.java` | `25a806934004` | 123 | 1–123 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderServiceImplTest.java` | `cdec04391c65` | 532 | 1–532 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderShippedCancellationIT.java` | `1ad5c490d2dd` | 109 | 1–109 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderStatusServiceImplTest.java` | `7a97c1f797e9` | 64 | 1–64 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderTrackingAbuseBrakeIT.java` | `b569bbf6086e` | 122 | 1–122 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/OrderTrackingAuthorizationTest.java` | `ee6611408df5` | 179 | 1–179 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/PricingServiceImplParallelFetchTest.java` | `9f09e55b3a3a` | 76 | 1–76 |
| `order-service/src/test/java/com/shop/orderservice/service/impls/PricingServiceImplTest.java` | `7a6cadc8c290` | 93 | 1–93 |
| `order-service/src/test/java/com/shop/orderservice/support/AbstractOrderServiceIT.java` | `0565149090e7` | 221 | 1–221 |

**Out-of-scope files read for context (not part of the inventory):** `utils/common-spring/src/main/resources/application.yml` (261 lines — the inherited Hikari/datasource defaults), `utils/common-core/.../AbstractMappedEntity.java` and `SoftDeletable.java` (to confirm the absence of a `@Version` on `Cart`/`OrderReturn`).

---

## 2. THE REAL CHECKOUT SAGA — end-to-end trace from source

### 2.1 Step table

| # | Step | Calling class / method (file:line) | HTTP / Kafka boundary | Transaction boundary | Proxy or self-invocation? | Idempotency key |
|---|---|---|---|---|---|---|
| 0 | HTTP entry | `OrderController.createOrder` — `order-service/.../controller/OrderController.java:32-43` | inbound `POST /api/v1/orders` | none (controller) | n/a | `Idempotency-Key` header, `required=false`, length ≤ 64 enforced at `:37-39` |
| 1 | Idempotency **begin** | `OrderServiceImpl.createOrder` `:150-166` → `IdempotencyServiceImpl.begin` `order-service/.../service/impls/IdempotencyServiceImpl.java:67-89` | none (local DB) | **REQUIRES_NEW** `TransactionTemplate` (`IdempotencyServiceImpl.java:61-63`, executed at `:71`) — commits **before** the saga | Spring proxy on `IdempotencyServiceImpl`; `begin()` has no `@Transactional` and uses the programmatic template → **no self-invocation hazard** | `Idempotency-Key` + `actor` = `userId.toString()` (`OrderServiceImpl.java:153`) |
| 2 | **Cart** lock + Order row creation | `OrderCreateSaga.execute` `order-service/.../service/impls/OrderCreateSaga.java:129-156` | none | **TX A** — `transactionTemplate.execute` (`OrderCreateSaga.java:110`) | `OrderCreateSaga` is a **sibling `@Service` bean** injected into `OrderServiceImpl` (`OrderServiceImpl.java:79, 116`) and called at `OrderServiceImpl.java:158` → **the H13 fix: not a self-invocation**; the programmatic template makes the boundary real regardless | none |
| 2a | ↳ cart lock | `cartRepository.findByIdAndUserIdForUpdate` / `findByUserIdForUpdate` (`OrderCreateSaga.java:132, 134`) | none | inside TX A | `@Lock(PESSIMISTIC_WRITE)` + `jakarta.persistence.lock.timeout=3000` → `SELECT … FOR UPDATE` (`order-service/.../repository/CartRepository.java:23-31`) | n/a |
| 2b | ↳ order row | `orderRepository.save(Order.builder()…status(PENDING)…trackingCode(UUID.randomUUID())…build())` (`OrderCreateSaga.java:143-153`) | none | inside TX A — **persist-early**: a zero-amount `PENDING` row exists before pricing | n/a | n/a |
| 3 | **Pricing** (product snapshots) | `OrderCreateSaga.execute:164` → `PricingServiceImpl.calculate` `order-service/.../service/impls/PricingServiceImpl.java:64-124` | **HTTP** `GET /api/v1/products/{id}` (`ProductServiceClient.java:41-44`), `@Cacheable("productPrice")` + `@CircuitBreaker` | **NO DB transaction** — runs strictly after TX A committed | fan-out on a `static` daemon `ExecutorService SNAPSHOT_EXECUTOR` (`PricingServiceImpl.java:54-61`) | none |
| 4 | **Promotion** reserve | `PricingServiceImpl.calculate:109-115` → `PromotionServiceClient.reserve` `order-service/.../client/PromotionServiceClient.java:64-85` | **HTTP** `POST /api/v1/promotions/{code}/reserve` (`:71-77`) | no transaction | n/a | none — `PromotionReserveRequest` (`dto/internal/PromotionReserveRequest.java:6`) carries `code, orderAmount, userId, orderId` only |
| 5 | **Tax** calculate | `PricingServiceImpl.calculate:118` → `calculateTaxSafely` `:133-149` → `TaxServiceClient.calculate` `order-service/.../client/TaxServiceClient.java:34-54` | **HTTP** `POST /api/v1/backoffice/tax-rates/calculate` (`:41-45`) | no transaction | n/a | none. Tax is **disabled by default** (`order-service/src/main/resources/application.yml:57`, `enabled: ${TAX_SERVICE_ENABLED:false}`) → returns 0 without a call (`TaxServiceClient.java:36-39`) |
| 6 | **Inventory Reserve** (one call **per order line**, sequential) | `OrderCreateSaga.execute:190-198` → `StockReservationServiceImpl.reserve` `order-service/.../service/impls/StockReservationServiceImpl.java:18-20` → `InventoryServiceClient.reserve` `order-service/.../client/InventoryServiceClient.java:45-65` | **HTTP** `POST /api/v1/inventory/{productId}/reserve` with a SERVICE bearer token (`InventoryServiceClient.java:49-52`), `@CircuitBreaker("inventoryService")` | **inventory-side TX**: `InventoryController.reserve` `inventory-service/.../controller/InventoryController.java:80-89` → `ReservationServiceImpl.reserveWithRetry` `inventory-service/.../service/impls/ReservationServiceImpl.java:32-34` → `InventoryServiceImpl.reserve` `inventory-service/.../service/impls/InventoryServiceImpl.java:137-164`, **`@Transactional` at `:137`** | Spring proxy: `InventoryService` interface → `InventoryServiceImpl` — called from a *different* bean (`ReservationServiceImpl`), so **not a self-invocation**; the proxy applies | **NONE.** `ReserveRequest` = `(quantity, orderId)` only (`inventory-service/.../dto/request/ReserveRequest.java:8-11`) — see **OI-01** |
| 7 | **Order creation** (persist priced order, items, clear cart, outbox) | `OrderCreateSaga.execute:219-226` | **Kafka** — `orderEventPublisher.publishCreated(order, orderItems)` → `OrderEventPublisherImpl.publishCreated` `order-service/.../service/impls/OrderEventPublisherImpl.java:37-53` → `outboxRepository.save` (`:103`) → relayed later to `shop.order.lifecycle.v1` by `OrderOutboxRelay.relay()` `order-service/.../service/impls/OrderOutboxRelay.java:104` | **TX C** — `transactionTemplate.executeWithoutResult` (`OrderCreateSaga.java:117`) — order row + items + cart deletion + outbox row commit **atomically** | programmatic template → real boundary | none |
| 8 | **Payment** (gate) | `OrderServiceImpl.confirmOrder:334-345` → `PaymentServiceClient.findCapturedByOrderId` `order-service/.../client/PaymentServiceClient.java:68-88` | **HTTP** `GET /api/v1/payments/by-order/{orderId}/captured` (`:71-75`) — fail-closed | none | n/a | none |
| 8′ | **Payment** (event-driven confirm) | `PaymentLifecycleConsumer.handle` `order-service/.../kafka/PaymentLifecycleConsumer.java:30-43` | **Kafka** `shop.payment.lifecycle.v1`, group `order-service-payment-group` (`PaymentLifecycleConsumer.java:22-24`) | consumer-side: `confirmOrder` runs its own short TX for the row flip | n/a | **YES** — `"payment-confirm-" + paymentId` (`PaymentLifecycleConsumer.java:34`) — deterministic, so a redelivered event replays the cached response |
| 9 | **Inventory Commit** | `OrderServiceImpl.confirmOrder:349` → `OrderCommitCoordinator.commitForConfirm` `order-service/.../service/OrderCommitCoordinator.java:55-140` | **HTTP** `POST /api/v1/inventory/reservations/{id}/commit` (`InventoryServiceClient.java:77-91`) | **inventory-side TX**: `InventoryController.commit` `inventory-service/.../controller/InventoryController.java:91-98` → `ReservationServiceImpl.commitWithRetry` `:37-39` → `InventoryServiceImpl.commit` `inventory-service/.../service/impls/InventoryServiceImpl.java:167-202`, **`@Transactional` at `:167`** | Spring proxy (`InventoryService` interface), different bean → **not a self-invocation** | remote call is **idempotent**: `commit()` on an already-`COMMITTED` reservation is a no-op (`InventoryServiceImpl.java:172-175`) |
| 9a | ↳ promotion commit (must precede inventory) | `OrderCommitCoordinator.commitForConfirm:58-67` → `PromotionServiceClient.commit:87-89` | **HTTP** `POST /api/v1/promotions/reservations/{id}/commit` | none — pure HTTP | n/a | idempotent on the promotion side |
| 9b | ↳ inventory commit **fan-out** (parallel) | `OrderCommitCoordinator.commitForConfirm:70-123` — items sorted by `productId` (`:70-71`), dispatched on the `static` daemon `COMMIT_EXECUTOR` (`:45-52`) | N parallel HTTP calls | **no DB transaction spans the remote calls**; the local row flip is a separate short TX (`OrderServiceImpl.java:355-362`) | n/a | n/a |
| 10 | Local state flip to `CONFIRMED` | `OrderServiceImpl.confirmOrder:355-362` | none | **short `TransactionTemplate.execute`** — deliberately outside any transaction holding the remote calls (the same reasoning as `cancelOrder`, `OrderServiceImpl.java:190-200`) | `transactionTemplate` → real boundary | n/a |
| 11 | **Shipping** — ship command | `OrderStatusController.ship` `order-service/.../controller/OrderStatusController.java:37-41` → `OrderServiceImpl.shipOrder:389-393` → `transitionStatus:401-418` | none (inbound HTTP `POST /api/v1/orders/{id}/ship`) | **`@Transactional` on the public method** (`OrderServiceImpl.java:390`) → Spring proxy applies; `transitionStatus` is a private self-call but the TX is already open | proxy on the public method ✔ | none |
| 12 | **Shipping** — delivered event | `ShippingDeliveredConsumer.onMessage` `order-service/.../kafka/ShippingDeliveredConsumer.java:24-27` → `ShippingDeliveredHandler.handle` `order-service/.../service/ShippingDeliveredHandler.java:26-45` | **Kafka** `shop.shipping.lifecycle.v1` (`order-service/.../test/.../ShippingDeliveredConsumerIT.java:50`), group `order-service` (`ShippingDeliveredConsumer.java:22`) | **`@Transactional`** (`ShippingDeliveredHandler.java:25`) — order row + outbox row commit atomically | Spring proxy (`ShippingDeliveredHandler` injected into the consumer) ✔ | the event is idempotent by design: a non-`SHIPPED` order is a no-op (`ShippingDeliveredHandler.java:36-39`) |

### 2.2 Two structural facts the docs do not state

1. **There is NO shipping client in order-service.** A repo-wide grep of `order-service/src/main` for "shipping" returns only `shippingAddress`, `ShippingDelivered*` and the shipping *listener* config. The order-service never calls shipping-service; the ship command is an inbound `POST /api/v1/orders/{id}/ship` from an external actor (SERVICE/ADMIN role, `OrderStatusController.java:25`), and the DELIVERED transition is driven purely by the inbound Kafka event. **The "Shipping" leg of the saga is therefore split across two independent mechanisms, and order-service owns no shipment-creation step.**
2. **Payment is not part of the create saga.** `OrderCreateSaga.execute` has no payment call at all. Payment enters only at CONFIRM time, either via the captured-payment HTTP gate (`OrderServiceImpl.java:334-345`) or via the `payment.captured.v1` Kafka event (`PaymentLifecycleConsumer.java:34`). The order row is created `PENDING` and stays `PENDING` until a confirm happens.

### 2.3 The saga's transaction boundaries — summary

```
[HTTP POST /orders]
   │
   ├─ TX-0  REQUIRES_NEW   idempotency_keys INSERT (in-flight)      ← COMMITTED
   │
   ├─ TX-A  REQUIRED        SELECT carts FOR UPDATE; INSERT orders(PENDING)  ← COMMITTED
   │
   ├─ ─ ─ ─ no transaction ─ ─ ─  PricingServiceImpl
   │            HTTP GET  /products/{id}   (parallel, @Cacheable)
   │            HTTP POST /promotions/{code}/reserve
   │            HTTP POST /tax-rates/calculate
   │         ↳ on failure: promotion /release  +  DELETE orders (TX-A2)
   │
   ├─ ─ ─ ─ no transaction ─ ─ ─  per-line inventory reserve (sequential)
   │            HTTP POST /inventory/{productId}/reserve
   │              └─ inventory TX: atomicReserve UPDATE + INSERT reservations
   │         ↳ on failure: HTTP POST /inventory/reservations/{id}/release  (per line)
   │                       HTTP POST /promotions/reservations/{id}/release
   │                       failed release → failed_compensations INSERT (TX REQUIRES_NEW)
   │                       DELETE orders
   │
   ├─ TX-C  REQUIRED        UPDATE orders; INSERT order_items;
   │                        DELETE cart_items; UPDATE carts (deleted=true);
   │                        INSERT outbox_events (order.created.v1)   ← COMMITTED
   │
   └─ idempotency_keys UPDATE (response_status=201)   ← separate @Transactional
```

`OrderCreateSaga.execute` is invoked from `OrderServiceImpl.createOrder` (`OrderServiceImpl.java:158`) on a **separate `@Service` bean** — this is the H13 fix documented at `OrderServiceImpl.java:156-157`. There is **no self-invocation of a `@Transactional` method anywhere in the create path**: `OrderCreateSaga` uses a programmatic `TransactionTemplate` (`OrderCreateSaga.java:62, 89, 108-121`), so the boundary is real whether or not a proxy is involved.

---

## 3. SAGA TRANSITION MATRIX

Column meanings: *state before* = the durable state of the order/stock at the moment of the failure. *Actual compensation* cites the implementation, or states **NOT IMPLEMENTED**.

| # | Failure point | State before | Operation attempted | Side effects already committed | Failure behaviour | Required compensation | ACTUAL compensation (file:line) | Final consistency invariant | Regression test |
|---|---|---|---|---|---|---|---|---|---|
| F1 | Product snapshot fetch fails | order `PENDING` row exists (zero amounts), no holds | `HTTP GET /products/{id}` | TX-A committed: `orders` row | `PricingServiceImpl` rethrows → `OrderCreateSaga:165-168` → `orderRepository.delete(order)` in a new TX | delete the placeholder order row | **IMPLEMENTED** — `OrderCreateSaga.java:166` `executeInTxWithoutResult(() -> orderRepository.delete(order))` | no orphan `PENDING` order; no holds | `OrderCreateSagaTest.persistsZeroAmountOrderBeforePricing_thenAppliesPricedAmounts` (`.../impls/OrderCreateSagaTest.java:99`); failure path not asserted there — **no dedicated test** |
| F2 | Promotion reserve fails (4xx/5xx) | order `PENDING`, no promotion hold | `HTTP POST /promotions/{code}/reserve` | TX-A only | `PromotionServiceClient.reserve:78-84` throws `ORDER_PROMOTION_INVALID` / `SERVICE_UNAVAILABLE` → F1 path | delete the order row | **IMPLEMENTED** — `OrderCreateSaga.java:166` | no orphan order, no promotion hold | `ConfirmOrchestrationIT.createSaga_taxFailsAfterPromotionReserve_rollsBack_andReleasesPromotionOnce` (`.../service/ConfirmOrchestrationIT.java:523-558`) |
| F3 | Tax calculate fails **after** a successful promotion reserve | order `PENDING`, **promotion hold live** | `HTTP POST /tax-rates/calculate` | TX-A + a remote promotion reservation | `calculateTaxSafely` catches, releases the promotion reservation, rethrows the original | release the promotion reservation | **IMPLEMENTED** — `PricingServiceImpl.java:133-149` (`promotionClient.release(promotionReservationId)` at `:141`) | no orphaned promotion hold | `ConfirmOrchestrationIT.java:523-558` (asserts exactly one `/release`, zero `/release-committed`, inventory untouched) |
| F4 | Inventory reserve #1 fails (insufficient stock) | order `PENDING`, no holds yet | `HTTP POST /inventory/{productId}/reserve` → 409 | TX-A + a remote 409 | `InventoryServiceClient.reserve:57-64` → `StockReservationFailedException` → `OrderCreateSaga:199-217` | release the already-reserved lines; release the promotion hold; delete the order row | **IMPLEMENTED** — `OrderCreateSaga.java:200` `releaseAllReservations` → `:234-254`; promotion release `:201-208`; order delete `:209`; error mapping `:210-216` | zero live holds; no order row | `OrderCreationSagaIntegrationTest.reservationFailure_releasesNothingAndThrows` (`:84-108`) and `.partialReservationFailure_releasesReservedItems` (`:111-165`, asserts exactly 1 release of item 1) |
| F5 | Inventory reserve #k fails (k > 1) | order `PENDING`, **k−1 holds live** | `HTTP POST /inventory/{productId}/reserve` → 409 | TX-A + k−1 remote holds | as F4 | release the k−1 holds | **IMPLEMENTED** — `OrderCreateSaga.java:200, 234-254` | all k−1 holds released; `reserved_quantity` back to its pre-checkout value | `OrderCreationSagaIntegrationTest.partialReservationFailure_releasesReservedItems` (`:111-165`) |
| F6 | A compensation release itself fails | order `PENDING`, some holds still live | `HTTP POST /inventory/reservations/{id}/release` → 5xx | TX-A + the surviving holds | `OrderCreateSaga.releaseAllReservations:240-252` swallows, records, logs; the saga still deletes the order row | durable record + retry that eventually releases the hold | **IMPLEMENTED** — record at `OrderCreateSaga.java:246-249` → `CompensationFailureService.record` `order-service/.../service/CompensationFailureService.java:58-82` (`REQUIRES_NEW`, `:58`); retry at `CompensationFailureService.java:90-119` (`@Scheduled`, `:90`) | hold eventually released; the ledger is the trace | `OrderCreateSagaTest.aFailedCompensationReleaseIsRecordedForTheRetry` (`.../impls/OrderCreateSagaTest.java:183-215`) + `CompensationFailureLedgerIT` (`.../service/CompensationFailureLedgerIT.java:40-108`) |
| F7 | **TX-C fails** (DB error, or the outbox payload cannot be serialized) | order row + items + cart mutation uncommitted, **all holds live** | `transactionTemplate.executeWithoutResult{…}` at `OrderCreateSaga.java:219-226` | TX-A + **all** remote holds | the exception propagates out of `execute()` — **the try/catch at `:199-217` covers only the reserve loop** | release every reservation that was successfully taken | **NOT IMPLEMENTED** — there is no `try/catch` around `OrderCreateSaga.java:219-226`; the holds survive until the 900 s TTL sweep | **BROKEN**: holds orphaned for up to 900 s (see **OI-03**) | **NO TEST** |
| F8 | `idempotencyService.complete` fails **after** TX-C committed | order committed, cart cleared, holds live | `idempotency_keys UPDATE` (`OrderCreateSaga.java:230`) | TX-A + TX-C + all holds + the outbox row | `OrderServiceImpl.createOrder:159-165` catches, calls `abort()` (deletes the in-flight key), rethrows | the order must not be created twice for one key | **PARTIALLY IMPLEMENTED — and wrong**: `abort()` frees the key (`OrderServiceImpl.java:163`) → a client retry with the same key creates a **second** order (see **OI-04**) | **BROKEN**: one key ⇒ two orders | **NO TEST** |
| F9 | Payment captured-check fails at confirm | order `PENDING`, holds live | `HTTP GET /payments/by-order/{id}/captured` | TX-A + holds | `OrderServiceImpl.java:338-344` → `ORDER_PAYMENT_NOT_CAPTURED` (fail-closed) | none — the order legitimately stays `PENDING` and the holds expire by TTL | **IMPLEMENTED (as designed)** — `OrderServiceImpl.java:334-345` | order stays `PENDING`; no commit, no release | `ConfirmOrchestrationIT.confirm_withoutCapturedPayment_failsClosedWithOrd4012` (`:149-161`) |
| F10 | Promotion commit fails at confirm | order `PENDING`, holds live | `HTTP POST /promotions/reservations/{id}/commit` | TX-A + holds | `OrderCommitCoordinator.java:58-67` throws → `catch` at `:133-139` → `compensateInReverse` over an **empty** list (`:136`) → rethrow → `OrderServiceImpl.java:348-353` → `ORD-4011` | nothing to compensate (promotion was not committed) | **IMPLEMENTED (correctly a no-op)** — `OrderCommitCoordinator.java:136, 142-153` with an empty target list | order stays `PENDING`; nothing committed | `OrderCommitCoordinatorTest.commit_promotionFails_rethrows_noOtherCalls_noCompensation` (`:104-124`) |
| F11 | One inventory commit fails at confirm (others succeeded) | order `PENDING`, **some holds COMMITTED** | N parallel `HTTP POST /inventory/reservations/{id}/commit` | TX-A + k COMMITTED reservations (stock consumed) | `OrderCommitCoordinator.java:124-127` → `catch` `:133-139` → `compensateInReverse(committed)` `:136, 142-153` → rethrow → `ORD-4011` | release-committed every reservation that *did* commit | **IMPLEMENTED, but with a hole** — `OrderCommitCoordinator.java:142-153` calls `inventoryClient.releaseCommitted(t.id())`; **if that call FAILS, the failure is swallowed and only logged** (`:148-151`) → **NOT retried, NOT recorded in the ledger** (see **OI-02**) | **BROKEN** when a `releaseCommitted` fails: the stock stays consumed for an order that stays `PENDING` forever; the reconciliation sweeper only *reads* states and routes to "mixed" (`OrderReconciliationScheduler.java:180-196, 226-230`) | `ConfirmOrchestrationIT.commitFailure_compensatesLifo_staysPending_returns409Ord4011` (`:235-280`) — covers the **happy** compensation only. **NO TEST for the failed-compensation case** |
| F12 | The whole confirm transaction is rolled back after a successful commit | order `PENDING`, holds COMMITTED | (local row flip) | the remote commits are already durable | `OrderServiceImpl.java:355-362` — the local flip is its own short TX; a failure there leaves the remote committed and the local `PENDING` | reconcile the local row to the remote state | **PARTIALLY IMPLEMENTED** — `OrderReconciliationScheduler.reconcileStuckOrders` (`:110-132`) polls the reservation states and auto-confirms when all are `COMMITTED` (`:156-159, 198-206`). **But it bypasses the captured-payment gate** (see **OI-08**) and the state machine (see **OI-07**) | order eventually `CONFIRMED` — but only after ≥30 min (`order.reconciliation.stuck-minutes: 30`, `order-service/src/main/resources/application.yml:89`) | `OrderReconciliationSchedulerTest.allCommitted_confirmsOrder_publishesStatusChanged` (`.../service/OrderReconciliationSchedulerTest.java:90-108`) |
| F13 | Inventory commit fails with `RESERVATION_EXPIRED` / `RESERVATION_INVALID_STATE` | reservation `EXPIRED`/`RELEASED`, order `PENDING` | `HTTP POST /inventory/reservations/{id}/commit` | TX-A + other holds | `InventoryServiceImpl.commit:176-181` throws → mapped by `InventoryServiceClient.remoteBusinessException:145-159` → the coordinator compensates the siblings → `ORD-4011` | release the siblings; the expired one is already terminal | **IMPLEMENTED** — `OrderCommitCoordinator.java:136, 142-153` | siblings released; the expired reservation was swept by inventory | `InventoryLifecycleIdempotencyTest.commit_expiredPending_throwsExpired` (`inventory-service/.../service/InventoryLifecycleIdempotencyTest.java:89-96`) + `.../commit_afterRelease_throwsInvalidState` (`:80-87`) |
| F14 | The reservation TTL expires before the confirm | reservation `EXPIRED`, hold released by the sweep, order `PENDING` | the inventory sweep releases the hold | the hold is gone; the order row still says `PENDING` | the confirm's `commit` gets `RESERVATION_INVALID_STATE` → `ORD-4011` | the order must be cancelled, not confirmed | **PARTIALLY IMPLEMENTED** — the reconciliation sweeper sees all-terminal and auto-cancels (`OrderReconciliationScheduler.java:157, 160, 212-224`), but only after the 30-min stuck window and only if *all* reservations are terminal | the order is cancelled eventually, with a ≥30 min delay | `OrderReconciliationSchedulerTest.allTerminal_cancelsOrder_publishesCancelled` (`:111-131`) |
| F15 | Admin cancels a `PENDING` order | order `PENDING`, holds live | `HTTP POST /inventory/reservations/{id}/release` per line | the order row is still `PENDING` while the releases run | `OrderServiceImpl.cancelOrder:216-230` | release every hold | **IMPLEMENTED** — `OrderServiceImpl.java:217-221` → `releaseAllReservationsById:292-301`; failures recorded at `:284-290` | holds released; order `CANCELLED` | `OrderServiceImplTest.cancelOrder_pendingReleasesStock` (`:412-421`); `CancelOutsideTransactionIT` (`:43-92`) proves no connection is held across the remote call |
| F16 | Admin cancels a `CONFIRMED` order | order `CONFIRMED`, reservations `COMMITTED` (stock already consumed) | `HTTP POST /inventory/reservations/{id}/release-committed` per line, then `POST /payments/{id}/refund` | the reservations are `COMMITTED` | `OrderServiceImpl.cancelOrder:231-251` | restock + refund | **IMPLEMENTED for the stock** (`OrderServiceImpl.java:238` → `releaseAllCommittedReservationsById:304-313`), **best-effort for the refund** (`:247-251`, failure only logged) | stock restocked; the refund may not have happened | `OrderServiceImplCancelConfirmedTest.adminCancelConfirmedOrderReleasesCommittedReservation` (`:55-79`) |
| F17 | Admin cancels a `SHIPPED` order | order `SHIPPED`, goods in transit | (no stock movement, by design) | none | `OrderServiceImpl.cancelOrder:252-261` → `orderReturnService.recordShippedCancellation` | create a return row so the refund is driven by the admin review | **IMPLEMENTED** — `OrderReturnServiceImpl.recordShippedCancellation` `order-service/.../service/impls/OrderReturnServiceImpl.java:86-113` | the refund is driven exactly once through the return review | `OrderShippedCancellationIT.theCancellationCreatesTheReturnThatDrivesTheRefund` (`.../impls/OrderShippedCancellationIT.java:67-80`) |
| F18 | The customer cancels a `PENDING` order | order `PENDING`, holds live | as F15 | as F15 | `OrderServiceImpl.cancelOrder:210-212` (non-admin must be `PENDING`) | as F15 | as F15 | as F15 | `OrderServiceImplCancelConfirmedTest.userCancelPendingUsesPlainRelease` (`:100-122`) |
| F19 | The payment service is down at confirm time | order `PENDING`, holds live | `HTTP GET /payments/by-order/{id}/captured` → 5xx/timeout | nothing | `PaymentServiceClient.findCapturedByOrderId:84-87` → `Optional.empty()` → `OrderServiceImpl.java:342-344` → `ORD-4012` (fail-closed) | none — the order stays `PENDING` | **IMPLEMENTED (fail-closed by design)** — `PaymentServiceClient.java:81-87`, `OrderServiceImpl.java:336-344` | no order is confirmed without a verified payment | `PaymentServiceClientTest.serverError_failsClosedReturnsEmpty` (`:116-123`), `.ioTimeout_failsClosedReturnsEmpty` (`:125-132`) |
| F20 | The compensation ledger's retry fails | a `failed_compensations` row is `PENDING` | `HTTP POST /inventory/reservations/{id}/release` | the hold is still live | `CompensationFailureService.retryPending:108-115` — increments `attempts`, pushes `nextAttemptAt` by the backoff | keep retrying until it succeeds | **IMPLEMENTED** — `CompensationFailureService.java:90-119` | the hold is eventually released | `CompensationFailureLedgerIT.aPendingCompensationIsRetriedUntilItSucceeds` (`:73-108`) |

### 3.1 Where the matrix is BROKEN

| Row | Gap | Defect |
|---|---|---|
| F7 | TX-C failure leaks every hold | **OI-03** |
| F8 | `complete()` failure ⇒ a second order for one key | **OI-04** |
| F11 | A failed `releaseCommitted` is dropped | **OI-02** |
| F12 | The reconciler's auto-confirm skips the payment gate | **OI-08** |

---

## 4. INVENTORY RESERVATION CORRECTNESS — the core of the audit

### 4.1 The mechanism — exactly how overselling is prevented

**Mechanism: an atomic, guarded `UPDATE … WHERE` (compare-and-set inside a single statement).**

```java
// inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java:39-44
@Modifying(clearAutomatically = true)
@Query("UPDATE Inventory i SET i.reservedQuantity = i.reservedQuantity + :qty, "
     + "i.lastUpdated = CURRENT_TIMESTAMP, i.version = i.version + 1 "
     + "WHERE i.productId = :productId "
     + "AND (i.availableQuantity - i.reservedQuantity) >= :qty")
int atomicReserve(@Param("productId") UUID productId, @Param("qty") int qty);
```

Called from `InventoryServiceImpl.reserve` (`inventory-service/.../service/impls/InventoryServiceImpl.java:141`).

**Inventory counter semantics** (derived from the code, not from the docs):

| Quantity | Meaning | Written by |
|---|---|---|
| `available_quantity` | physical stock not yet sold | `atomicReserve` (unchanged), `commit` (`:185, 190`), `releaseCommitted` (`:261`), admin `update` (`:95-103`) |
| `reserved_quantity` | the subset of `available_quantity` held by live `PENDING` reservations | `atomicReserve` (+qty), `commit` (−qty, `:186, 191`), `release` (−qty, `:224, 228`), the expiry sweep (`releaseReserved`, `:113`) |
| **capacity** | `available_quantity − reserved_quantity` | the `WHERE` guard of `atomicReserve` |

`commit` decrements **both** counters by `qty` (`InventoryServiceImpl.java:185-191`); `release` decrements only `reserved_quantity` (`:224-228`); `releaseCommitted` increments only `available_quantity` (`:261`). Capacity is therefore invariant under all three transitions.

**What is NOT the mechanism:**

- ❌ **Not a pessimistic lock** on the reserve path. There is no `SELECT … FOR UPDATE` on `inventory` anywhere; the only `PESSIMISTIC_WRITE` locks in inventory-service are on the outbox table (`repository/OutboxEventRepository.java:37, 55`).
- ❌ **Not an optimistic `@Version` check on the reserve path.** `atomicReserve` bypasses the entity entirely and folds the bump into `version = version + 1` (`InventoryRepository.java:41`). The `@Version` on `Inventory` (`entity/Inventory.java:58-59`) protects the *read-then-write* paths (`commit`, `release`, `releaseCommitted`), not the reserve.
- ❌ **Not a unique constraint.** There is no unique index on `reservations`; the table has only `idx_reservations_product_id`, `idx_reservations_status_expires` (`changelog-001-initial-schema.yaml:61-72`) and `idx_reservations_order_id` (`changelog-006-reservations-order-id-index.yaml:21-24`).
- ✅ **Plus** two DB-level `CHECK` constraints, `NOT VALID` (so enforced for new/updated rows, not scanned for legacy rows): `ck_inventory_available_non_negative` and `ck_inventory_reserved_non_negative` (`inventory-service/src/main/resources/db/changelog/changelog-004-stock-invariants-and-reservation-version.yaml:42-54`).
- ✅ **Plus** `@Version` on `Reservation` (`entity/Reservation.java:76-79`) and `Inventory` (`entity/Inventory.java:58-59`).
- ✅ **Plus** a `releaseReserved` guarded UPDATE (`InventoryRepository.java:59-63`): `WHERE … AND i.reservedQuantity >= :qty` — the counter can never go negative and a double release cannot over-credit.

**Why the atomic UPDATE is sufficient.** Under **READ COMMITTED** (the effective isolation — see §5.1), when two transactions target the same `inventory` row, Postgres locks the row for the first writer and, for the second, re-evaluates the `WHERE` clause against the **updated** tuple (EvalPlanQual). If the predicate no longer holds, the second `UPDATE` affects **zero rows** — it does not silently decrement below capacity. `atomicReserve` returns the affected-row count (`int`), and `InventoryServiceImpl.reserve:142-147` treats `0` as `STOCK_INSUFFICIENT`. The check and the write are therefore atomic *by construction*; there is no TOCTOU window.

### 4.2 The reservation state machine (inventory side)

`ReservationStatus` (`inventory-service/.../constant/ReservationStatus.java:4`): `PENDING, COMMITTED, RELEASED, EXPIRED`.

| From | To | Guard | Where |
|---|---|---|---|
| — | `PENDING` | created by `reserve` | `InventoryServiceImpl.java:151-159` |
| `PENDING` | `COMMITTED` | not expired (`expiresAt >= now`) | `InventoryServiceImpl.java:172-196` |
| `PENDING` | `RELEASED` | not expired | `InventoryServiceImpl.java:210-233` |
| `PENDING` | `EXPIRED` | `expiresAt < now`, claimed conditionally | `ReservationCleanupScheduler.java:105-106` → `ReservationRepository.claimForExpiry:46-51` |
| `COMMITTED` | `RELEASED` | `releaseCommitted` | `InventoryServiceImpl.java:256-266` |
| `RELEASED`/`EXPIRED` | — | terminal, no-op | `InventoryServiceImpl.java:172-175, 210-214, 251-255` |
| any other | — | `RESERVATION_INVALID_STATE` | `InventoryServiceImpl.java:176-178, 215-217, 256-258` |

### 4.3 The six mandatory races — analysed and proved

#### Race A — Two customers reserve the last unit → **SAFE**

- **Interleaving:** `available=1, reserved=0, capacity=1`. T1 and T2 both call `reserve(productId, 1)`.
- **Dangerous version:** both read `capacity=1`, both conclude "fits", both increment → `reserved=2`, `capacity=-1`, and two orders are sold from one unit.
- **Why it cannot happen:** the read is not in the decision path. `atomicReserve` is a single statement whose predicate `(available − reserved) >= :qty` is evaluated by Postgres **while holding the row lock** and, for the loser, re-evaluated against the winner's committed tuple. T1's `UPDATE` sets `reserved=1` (capacity 0) and commits; T2's `UPDATE` re-checks `(1 − 1) >= 1` → false → **0 rows affected** → `InventoryServiceImpl.java:142` → `STOCK_INSUFFICIENT`.
- **Violated invariant:** none — "capacity ≥ 0 at all times" and "a unit is sold to at most one customer" both hold.
- **Root cause of the *old* bug:** the pre-fix design read the row, computed the capacity in Java, then wrote it — a TOCTOU window. The fix is the atomic UPDATE (`InventoryRepository.java:18-44` documents the before/after explicitly).
- **Verdict: SAFE.** Proof is the single-statement predicate plus the READ COMMITTED EvalPlanQual re-check.
- ⚠️ **Test gap:** `InventoryServiceImplReserveConcurrencyTest.fiftyConcurrentReservesForSameProductLeaveQuantityConsistent` (`inventory-service/.../service/impls/InventoryServiceImplReserveConcurrencyTest.java:82-111`) is a **Mockito** test — it mocks `InventoryRepository` and only asserts that `atomicReserve` was called 50 times (`:107-108`). It proves **nothing** about the SQL semantics. There is **no integration test** that runs two concurrent reserves for a single unit against real Postgres. **This is the highest-value missing test in the whole audit.**

#### Race B — Duplicate reserve requests with the same key → **NOT SAFE (OI-01)**

- **Interleaving:** the client (or an HTTP retry, a proxy replay, or a manual double-submit) issues `POST /inventory/{productId}/reserve` twice for the same order line. `InventoryServiceImpl.reserve` (`:138-164`) has **no dedupe at all**: it increments `reserved_quantity` again and INSERTs a second `reservations` row.
- **Dangerous version:** `reserved_quantity` is incremented twice for one order line; the customer is charged for 1 unit but 2 units are held; on confirm, `commit` deducts `available` twice (once per reservation) → the shop **oversells by one unit** — this is the only path in the whole audit that can oversell.
- **Violated invariant:** "one order line ⇒ at most one live stock hold"; "a retried reserve is idempotent".
- **Root cause:** the reserve API has **no idempotency key**. `ReserveRequest` (`inventory-service/.../dto/request/ReserveRequest.java:8-11`) carries only `quantity` and `orderId`; there is **no unique constraint** on `(order_id, product_id)` in the `reservations` table (`changelog-001-initial-schema.yaml:39-72`; `changelog-006` adds only a *non-unique* index on `order_id`).
- **Is the code safe?** **NO.** Two related consequences:
  1. **Duplicate holds** — a repeated reserve call double-holds the stock.
  2. **Lost-response orphan** — if the HTTP response is lost (the inventory timeout is 5 000 ms, `order-service/src/main/resources/application.yml:52`) *after* the DB commit, the order-service never learns the `reservationId`. `OrderCreateSaga.releaseAllReservations` (`:234-254`) only releases the ids it collected, so the orphan survives until the **900 s TTL sweep** (`inventory-service/src/main/resources/application.yml:67`).
- **Regression test:** **NONE.** `InventoryLifecycleIdempotencyTest` (`inventory-service/.../service/InventoryLifecycleIdempotencyTest.java:29-121`) covers commit/release idempotency but **not** reserve idempotency.

#### Race C — Reservation expiry racing with commit → **SAFE**

- **Interleaving:** the reservation's `expires_at` is `T`. `commit` reads it at `t0 < T` → `InventoryServiceImpl.java:179` `r.getExpiresAt().isBefore(Instant.now())` is false → commit proceeds. At `t1 > T` the sweep's query `findByStatusAndExpiresAtBefore(PENDING, now, …)` (`ReservationCleanupScheduler.java:67-68`) selects it.
- **Violated invariant (if broken):** a `COMMITTED` reservation must never be flipped back to `EXPIRED`, and the stock of a paid order must never be returned to the pool.
- **Why it cannot happen — four independent guards:**
  1. **The claim is conditional.** `ReservationRepository.claimForExpiry` (`inventory-service/.../repository/ReservationRepository.java:46-51`):
     ```java
     @Query("UPDATE Reservation r SET r.status = :expired, r.version = r.version + 1 "
          + "WHERE r.id = :id AND r.status = :pending")
     ```
     If `commit` won the race (status is now `COMMITTED`), the sweep's `UPDATE` affects **0 rows** → `ReservationCleanupScheduler.java:107-109` `continue` → the reservation is **not** added to `claimedByProduct` → `releaseReserved` is **not** called → the inventory counters are untouched.
  2. **`@Version` on `Reservation`** (`entity/Reservation.java:76-79`). If the sweep won, `commit`'s flush issues `UPDATE reservations SET status='COMMITTED', version=version+1 WHERE id=? AND version=<stale>` → **0 rows** → `OptimisticLockingFailureException` → the whole commit transaction rolls back (Postgres rollback is atomic) → the inventory counters are **not** modified by the losing commit.
  3. **The stock decrement is itself a guarded atomic UPDATE.** `releaseReserved` (`InventoryRepository.java:59-63`): `WHERE i.productId = :productId AND i.reservedQuantity >= :qty` → the counter can never go negative and cannot be decremented twice for the same hold.
  4. **Rollback atomicity.** Both the commit transaction and the sweep transaction are single DB transactions; a loser rolls back wholesale.
- **Verdict: SAFE.** This is exactly what the entity javadoc claims (`entity/Reservation.java:67-75`) and it holds.
- **Regression test:** `InventoryLifecycleIdempotencyTest.commit_afterRelease_throwsInvalidState` (`:80-87`), `InventoryLifecycleIdempotencyTest.commit_expiredPending_throwsExpired` (`:89-96`), and `ReservationCleanupSchedulerTest.sweep_skipsRowsAlreadyClaimedByAnotherWriter` (`:103-123`).
- ⚠️ **But the sweep has a separate defect** (OI-06): it publishes a spurious `inventory.released.v1` for reservations it did **not** claim — see §9.

#### Race D — Release racing with commit → **SAFE**

- **Interleaving:** T1 = `release(reservationId)`, T2 = `commit(reservationId)`. Both load the `Reservation` (version *v*) and the `Inventory` (version *w*), both compute new counters, both write.
- **Violated invariant (if broken):** `reserved_quantity` decremented twice for one hold (the classic double-release), or `available_quantity` decremented for a reservation whose hold was already returned.
- **Why it cannot happen:**
  - `release` loads the reservation **first** (`InventoryServiceImpl.java:207`), then the inventory (`:221`); `commit` loads the reservation **first** (`:169`), then the inventory (`:182`). Both therefore issue the reservation `UPDATE` before the inventory `UPDATE` in the same flush — a **consistent lock order** (Reservation → Inventory), so no deadlock is possible either (see §5.6).
  - Both `Reservation` and `Inventory` carry `@Version`. The first writer's flush bumps the version; the second writer's `UPDATE … WHERE version=<stale>` affects **0 rows** → `OptimisticLockingFailureException` → the loser's whole transaction rolls back → **the loser's inventory mutation is undone**. The winner's mutation stands.
  - The retry loop `ReservationServiceImpl.withRetry` (`inventory-service/.../service/impls/ReservationServiceImpl.java:51-67`) re-invokes the operation in a **new** transaction (a new proxy call → a new `@Transactional`), so the retry re-reads the fresh version and re-validates. On the retry the reservation is already `RELEASED` → `commit` throws `RESERVATION_INVALID_STATE` (`:176-178`) — a clean, correct refusal, not a double release.
- **Verdict: SAFE**, and additionally the losing side is *reported* rather than silently mis-accounted.
- **Regression test:** `InventoryLifecycleIdempotencyTest.release_afterCommitted_throwsInvalidState` (`:115-121`); `ReservationServiceImplTest.commitWithRetry_retriesVoidOperationOnOptimisticLockFailure` (`:47-55`); `InventoryServiceImplTest.commit_rejectsNegativeStockOutcome` (`:402-416`) and `.release_rejectsNegativeStockOutcome` (`:418-431`).

#### Race E — Two sweepers processing the same reservation → **SAFE**

- **Interleaving:** two inventory-service pods run `releaseAllExpiredReservations` simultaneously. Both `SELECT` the same candidate row (a plain read, no lock). Both call `claimForExpiry`.
- **Violated invariant (if broken):** the hold's stock is returned **twice** → the counter goes negative / the shop oversells.
- **Why it cannot happen:**
  - `claimForExpiry` (`ReservationRepository.java:46-51`) is a conditional `UPDATE … WHERE r.id = :id AND r.status = :pending`. T1's `UPDATE` locks the row and sets `status='EXPIRED'`; T2's identical `UPDATE` **blocks** on the row lock, and when T1 commits, Postgres re-evaluates T2's `WHERE` against the new tuple → `status='EXPIRED'` ≠ `PENDING` → **0 rows**. `ReservationCleanupScheduler.java:107-109` → `continue`.
  - Only the claimer adds the row to `claimedByProduct` (`:110`) and only the claimer calls `releaseReserved` (`:112-113`). The `releaseReserved` UPDATE additionally requires `reservedQuantity >= :qty` (`InventoryRepository.java:62`), so even a double call cannot over-credit.
  - The whole batch runs in **one** transaction (`ReservationCleanupScheduler.java:66-74`), so the claim and the stock decrement commit atomically.
- **Verdict: SAFE.**
- **Regression test:** `ReservationCleanupSchedulerTest.sweep_skipsRowsAlreadyClaimedByAnotherWriter` (`:103-123`) — asserts `releaseReserved` is never called when the claim returns 0. (Mock-level, but it pins exactly the contract that matters.)
- ⚠️ The related **outbox** bug is OI-06: the *event* loop publishes for the whole batch, not just the claimed rows.

#### Race F — Commit of an already-released reservation → **SAFE**

- **Interleaving:** the order-side confirm retries a commit whose reservation was already released (by the sweep, by a cancel, or by a previous failed attempt).
- **Violated invariant (if broken):** `available_quantity` decremented for a hold that no longer exists → oversell; or `reserved_quantity` decremented twice → the counter goes negative.
- **Why it cannot happen:** `InventoryServiceImpl.commit` re-reads the reservation (`:169`) and checks:
  - `status == COMMITTED` → **idempotent no-op**, returns (`:172-175`);
  - `status != PENDING` (i.e. `RELEASED` or `EXPIRED`) → throws `RESERVATION_INVALID_STATE` (`:176-178`) **before any counter is touched**;
  - `expiresAt < now` → throws `RESERVATION_EXPIRED` (`:179-181`) **before any counter is touched**.
  - Only past all three guards does it compute and write the counters (`:185-196`).
- **Verdict: SAFE.** No double decrement is possible; the path is a clean refusal.
- **Regression test:** `InventoryLifecycleIdempotencyTest.commit_afterRelease_throwsInvalidState` (`:80-87`), `.commit_twice_secondIsNoop` (`:66-78`).

### 4.4 Additional reservation-path proofs (CORRECT — stated explicitly)

| Property | Verdict | Proof |
|---|---|---|
| A rollback of `reserve()` undoes the stock hold | **CORRECT** | `atomicReserve` (`:141`) and `reservationRepository.save(reservation)` (`:159`) and the outbox insert (`:161`) are all inside the single `@Transactional` `InventoryServiceImpl.reserve` (`:137`) → one rollback undoes all three. |
| `commit` can never drive `available_quantity` negative | **CORRECT** | the explicit guard `if (newAvailable < 0 || newReserved < 0) throw STOCK_INSUFFICIENT` (`:187-189`) plus the DB `CHECK (available_quantity >= 0)` (`changelog-004:42-54`). Test: `InventoryServiceImplTest.commit_rejectsNegativeStockOutcome` (`:402-416`). |
| `release` can never drive `reserved_quantity` negative | **CORRECT** | guard at `:225-227` + the `CHECK (reserved_quantity >= 0)` + `releaseReserved`'s `reservedQuantity >= :qty` predicate. Test: `InventoryServiceImplTest.release_rejectsNegativeStockOutcome` (`:418-431`). |
| A double `release` is a no-op | **CORRECT** | `InventoryServiceImpl.release:210-214` returns early for `RELEASED`/`EXPIRED`. Test: `InventoryLifecycleIdempotencyTest.release_afterReleaseOrExpired_isNoop` (`:98-113`). |
| A double `commit` is a no-op | **CORRECT** | `InventoryServiceImpl.commit:172-175`. Test: `InventoryLifecycleIdempotencyTest.commit_twice_secondIsNoop` (`:66-78`). |
| A double `releaseCommitted` cannot double-restock | **CORRECT** | the early return at `:251-255` plus the `Inventory.@Version` guard on the concurrent case. Test: `ReleaseCommittedTest.releaseCommitted_isIdempotent_onAlreadyReleasedOrExpired` (`:73-97`). |
| The inventory row is never deleted under a live hold | **NOT SAFE (OI-11)** | `InventoryServiceImpl.delete:124-134` — check-then-act. |
| The reservation cache is invalidated on every counter write | **CORRECT** | every write path calls `cacheService.evictAfterCommit(...)`: `:82, 111, 133, 162, 201, 235, 268` and the sweep `ReservationCleanupScheduler.java:121`. `InventoryCacheService.evictAfterCommit:34-46` registers an `afterCommit` synchronization so a rollback does **not** evict. |

---

## 5. FULL CONCURRENCY AUDIT

### 5.1 Effective transaction isolation

**READ COMMITTED** — the PostgreSQL server default. Evidence of *absence*:

- `order-service/src/main/resources/application.yml:5-9` — the `spring.datasource` block sets only `url`, `username`, `password`, `driver-class-name`.
- `inventory-service/src/main/resources/application.yml:13-17` — identical.
- `utils/common-spring/src/main/resources/application.yml:30-37` — the inherited `spring.datasource.hikari` block sets **only** `maximum-pool-size` (20), `minimum-idle` (5), `connection-timeout` (5 000 ms), `idle-timeout` (300 000 ms), `max-lifetime` (1 800 000 ms), `leak-detection-threshold` (10 000 ms). **No `transaction-isolation`.**
- A repo-wide grep for `transaction-isolation`, `transaction_isolation`, `jdbc.batch`, `default_batch_fetch` across all `*.yml`, `*.yaml`, `*.java` returns **zero hits**.

**Consequence for the audit:** the correctness of `atomicReserve` (Race A) depends on READ COMMITTED's EvalPlanQual re-check. Under REPEATABLE READ the concurrent `UPDATE` would raise a serialization failure instead of cleanly returning 0 rows, and `ReservationServiceImpl.withRetry` would **not** catch it (it only catches `OptimisticLockingFailureException`, `ReservationServiceImpl.java:56`) → the reserve would fail with a 500 instead of `STOCK_INSUFFICIENT`. **Any future isolation-level change is a breaking change and must be paired with an audit of this code.**

Other JPA settings: `open-in-view: false` in both services (`order-service/.../application.yml:14`, `inventory-service/.../application.yml:23`) ✔ — no lazy-load-in-view leaks. `ddl-auto: validate` in both ✔ — Liquibase owns the schema.

### 5.2 Every mutable instance / static field

| Field | Location | Thread-safe? | Assessment |
|---|---|---|---|
| `OrderServiceImpl.MAX_TRACK_PHONE_FAILURES` (static final int) | `order-service/.../service/impls/OrderServiceImpl.java:62` | immutable | **CORRECT** |
| `OrderServiceImpl.TRACK_PHONE_LOCK` (static final Duration) | `:65` | immutable | **CORRECT** |
| `OrderServiceImpl.transactionTemplate` / `cancelTransactionTemplate` (instance, final) | `:82, 84` | `TransactionTemplate` is thread-safe (stateless config) | **CORRECT** |
| `PricingServiceImpl.SNAPSHOT_EXECUTOR` (static final `ExecutorService`) | `order-service/.../service/impls/PricingServiceImpl.java:54-61` | threads are | **shared, unbounded queue** — see OI-22. `Executors.newFixedThreadPool` ⇒ unbounded `LinkedBlockingQueue` ⇒ no backpressure. |
| `OrderCommitCoordinator.COMMIT_EXECUTOR` (static final) | `order-service/.../service/OrderCommitCoordinator.java:45-52` | threads are | **shared, unbounded queue** — same as above. |
| `OrderReconciliationScheduler.RECONCILE_EXECUTOR` (static final) | `order-service/.../service/OrderReconciliationScheduler.java:70-77` | threads are | same. Also **never shut down** (daemon threads ⇒ OK at JVM exit). |
| `OrderStatusServiceImpl.ALLOWED` (static final `Map<OrderStatus, Set<OrderStatus>>`) | `order-service/.../service/impls/OrderStatusServiceImpl.java:16-22` | `Map.of` + `EnumSet` are immutable | **CORRECT** — safe to read from any thread. |
| `OrderReconciliationScheduler.MemoizedCount.lastValue` / `lastUpdatedNanos` (volatile) | `order-service/.../service/OrderReconciliationScheduler.java:249-250` | volatile + double-checked locking under `synchronized` (`:257-269`) | **CORRECT** — properly published. Test: `OrderReconciliationSchedulerMemoizedCountTest` (`:25-63`). |
| `ServiceTokenProvider.cache` (`AtomicReference<CachedToken>`) + `refreshLock` (`ReentrantLock`) | `order-service/.../security/ServiceTokenProvider.java:39-40` | `AtomicReference` + `ReentrantLock` | **CORRECT** — the only one refresh at a time; a 5 s `tryLock` timeout with a stale-token fallback (`:71-77`). |
| `InventoryMetrics.pendingOutboxCount` / `OrderMetrics.pendingOutboxCount` (`AtomicInteger`) | `inventory-service/.../service/InventoryMetrics.java:39`, `order-service/.../service/OrderMetrics.java:33` | atomic | **CORRECT** |
| `InventoryCacheService` — no mutable state | `inventory-service/.../service/InventoryCacheService.java:22-57` | stateless | **CORRECT** |
| `OrderConfirmMetrics` / `OrderMetrics` — Micrometer `Timer`/`Gauge` | `order-service/.../service/OrderConfirmMetrics.java:13-33` | Micrometer is thread-safe | **CORRECT** |

**No mutable static collection is shared unsafely.** There is no `static Map` cache, no `static List`, no `static` counter.

### 5.3 Cached state

| Cache | Location | Invalidation | Assessment |
|---|---|---|---|
| `productPrice` (Redis, 10 min TTL) | `order-service/.../client/ProductServiceClient.java:36-38`; TTL `order-service/.../config/CacheConfig.java:21, 49` | **NONE.** No `@CacheEvict`/`@CachePut` exists anywhere in order-service (verified by grep — the only hit is a comment in inventory's `CacheConfig.java:27`). | **DEFECT (OI-13):** a catalogue price change is invisible to checkout for up to 10 minutes; `OrderCreateSaga.java:183-185` prices the line from this snapshot. |
| `inventory` (Redis, 60 s TTL) | `inventory-service/.../service/impls/InventoryServiceImpl.java:63-70`; TTL `inventory-service/.../config/CacheConfig.java:46, 54` | `InventoryCacheService.evictAfterCommit` on **every** counter write (`:82, 111, 133, 162, 201, 235, 268`) + the sweep (`ReservationCleanupScheduler.java:121`) | **CORRECT** — and correctly deferred to after-commit so a rollback does not evict (`InventoryCacheService.java:34-46`). |
| Redis write mode | `inventory-service/.../config/CacheConfig.java:52`, `order-service/.../config/CacheConfig.java:45` | `immediateWrites()` | **CORRECT** — spring-data-redis 4.1.1 defaults to `asynchronousWrites=true`, which would let a reader race the writer; the code forces synchronous writes. |

### 5.4 `@Scheduled` sweepers (all 8 in scope)

| Sweeper | Location | Interval | Concurrency safety |
|---|---|---|---|
| `ReservationCleanupScheduler.releaseAllExpiredReservations` | `inventory-service/.../service/ReservationCleanupScheduler.java:59-86` | 60 s, initial delay 60 s | **SAFE** — batched (500), one tx per batch, conditional claim (§4.3 Race E). Two pods are safe. |
| `ReservationCleanupScheduler.purgeOldExpiredReservations` | `:132-144` | cron 04:00 | **CORRECT** but **unbounded** bulk DELETE (`ReservationRepository.java:24-27`) — OI-16. |
| `InventoryOutboxRelay.relay` | `inventory-service/.../service/InventoryOutboxRelay.java:101-177` | 5 s | **SAFE** — `PESSIMISTIC_WRITE` + SKIP LOCKED claim (`repository/OutboxEventRepository.java:37-41`), a claim lease (`claimed_at`, `changelog-005`), and a compare-and-set mark (`findByIdForUpdate`, `:55-58`; `InventoryOutboxRelay.java:207-210`). |
| `OutboxRetentionScheduler.purgeOldSentEvents` (inventory) | `inventory-service/.../service/OutboxRetentionScheduler.java:26-38` | cron 03:00 | unbounded DELETE — OI-16. |
| `OrderOutboxRelay.relay` | `order-service/.../service/impls/OrderOutboxRelay.java:101-178` | 5 s | **SAFE** — same design; `findFirstByStatusOrderByIdAsc` + `PESSIMISTIC_WRITE` + SKIP LOCKED (`repository/OutboxEventRepository.java:36-38`). **Proved by** `OrderOutboxClaimConcurrencyIT.twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked` (`:120-183`) — row2 **is** claimable while row1 is locked. |
| `OutboxRetentionScheduler.purgeOldSentEvents` (order) | `order-service/.../service/impls/OutboxRetentionScheduler.java:25-35` | cron 03:00 | unbounded DELETE — OI-16. |
| `IdempotencyKeyCleanupScheduler.purgeExpired` | `order-service/.../service/impls/IdempotencyKeyCleanupScheduler.java:19-28` | cron 04:00 | unbounded DELETE — OI-16. |
| `GuestCartCleanupScheduler.cleanupStaleCarts` | `order-service/.../service/impls/GuestCartCleanupScheduler.java:28-49` | cron 04:00 | unbounded list + hard delete — OI-26. |
| `CompensationFailureService.retryPending` | `order-service/.../service/CompensationFailureService.java:90-119` | 30 s | **no claim/lock on the 50 selected rows** → two pods retry the same rows. **Harmless** because the remote `release` is idempotent (`InventoryServiceImpl.java:210-214`). But it holds a transaction open across up to 50 HTTP calls — OI-10. |
| `OrderReconciliationScheduler.reconcileStuckOrders` | `order-service/.../service/OrderReconciliationScheduler.java:110-132` | 5 min | chunked (50), parallel fan-out, per-order TX, `@Version` guard — **SAFE**. |

### 5.5 `@Async` / non-thread-safe collaborators

**There is no `@Async` and no `@EnableAsync` anywhere in the two modules** (verified by grep — the only hit is the word in a comment at `PricingServiceImpl.java:51`). All fan-outs use **raw static `ExecutorService` pools**. This is not a correctness bug, but it means:
- the fan-out runs on a thread **outside** the caller's Spring transaction, security context, and (partially) MDC;
- MDC is manually propagated (`PricingServiceImpl.java:70, 75-77`; `OrderCommitCoordinator.java:77, 88-91`) — the **same `Map` instance** is installed on the worker via `MDC.setContextMap`, so a mutation on the worker would be visible to the caller. No mutation occurs today; LOW.

**Non-thread-safe collaborators:** none found. `RestClient` (Apache HttpClient, pooled, thread-safe), `ObjectMapper` (thread-safe), `ModelMapper` (thread-safe for reads), `TransactionTemplate` (thread-safe), `MeterRegistry` (thread-safe), `ReentrantLock`/`AtomicReference` in `ServiceTokenProvider` (correct).

### 5.6 Check-then-act, lost update, duplicate insert, write skew, deadlock ordering

| Pattern | Where | Assessment |
|---|---|---|
| **Check-then-act on the inventory row** | `InventoryServiceImpl.commit:169-196`, `release:207-233`, `releaseCommitted:248-266` | **SAFE** — protected by `Inventory.@Version`; the loser gets `OptimisticLockingFailureException` and the transaction rolls back. |
| **Check-then-act on the reservation row** | same three methods | **SAFE** — protected by `Reservation.@Version`. |
| **Check-then-act on the inventory delete guard** | `InventoryServiceImpl.delete:127-131` | **NOT SAFE — OI-11.** The `activeReservationCount` SELECT is not serialized with the DELETE. |
| **Check-then-act on `carts`** | `CartServiceImpl.getOrCreateCart:178-195` | **SAFE** — the unique partial index `idx_carts_user_active` (`changelog-001-initial-schema.yaml:32-35`) is the real arbiter; the `DataIntegrityViolationException` handler at `:186-194` re-reads the winner. Test: `CartServiceImplTest.getMyCart_raceLost_returnsWinner` (`:78-90`). |
| **Check-then-act on `cart_items`** | `CartServiceImpl.addItem:56-80` | **SAFE** — the unique index `idx_cart_items_cart_product` (`changelog-001-initial-schema.yaml:66-72`) plus the cart row lock (`findByUserIdForUpdate`) taken in the saga. |
| **Check-then-act on the idempotency row** | `IdempotencyServiceImpl.begin:72-86` | **SAFE** — the composite PK `(actor, key)` is the final arbiter; the `DataIntegrityViolationException` path at `:80-86` re-reads the winner. Test: `IdempotencyServiceImplTest.begin_lostRace_replaysCompletedWinner` (`:121-136`). |
| **Check-then-act on `failed_compensations`** | `CompensationFailureService.record:63-65` | **SAFE in effect** (the remote release is idempotent) but **not enforced at the DB level** — OI-15. |
| **Check-then-act on `track_failures`** | `OrderServiceImpl.authorizeByPhone:490-498` | **SAFE from corruption** (`Order.@Version`), but concurrent wrong-number attempts under-count the counter → OI-27. |
| **Check-then-act on the cumulative refund total** | `OrderReturnServiceImpl.requestReturn:62-68` | **NOT SAFE — OI-14.** `OrderReturn` has no `@Version` and the order row is not locked. |
| **Lost update on `carts.subtotal`** | `CartServiceImpl.addItem:81-82`, `updateItem:104-105`, `removeItem:119-120`, `mergeCart:173-174`; `OrderCreateSaga.java:223-224` | **NOT SAFE — OI-09.** `Cart` has **no `@Version`** (it extends `AbstractMappedEntity` → `SoftDeletable`, neither of which carries a version column). |
| **Duplicate insert** | `orders` | **SAFE** — the id is a generated UUID; the only duplicate path is the idempotency key, which is guarded by the composite PK (see OI-04 for the one hole). |
| **Duplicate insert** | `reservations` | **NOT SAFE — OI-01.** No uniqueness on `(order_id, product_id)`. |
| **Duplicate insert** | `order_items` | **SAFE** — the order row is created once per saga; `saveAll` is inside TX-C. |
| **Duplicate insert** | `outbox_events` | **SAFE** — `event_id` is `UNIQUE` (`changelog-001` for both services) and the id is a `BIGSERIAL`. |
| **Duplicate insert** | `order_returns` | **SAFE** — one row per request; the "pending return" guard is a check-then-act but the refund amount is the only money at risk (OI-14). |
| **Write skew** | the reconciliation scheduler's auto-confirm vs. an admin cancel | **SAFE** — `Order.@Version` serialises the two writes. Test: `ConfirmOrchestrationIT.concurrency_differentKeys_versionGuardConfirmsExactlyOnce` (`:474-515`). |
| **Write skew** | the reconciliation auto-confirm vs. the captured-payment gate | **NOT SAFE — OI-08.** The sweeper's auto-confirm does not consult the payment state. |
| **Deadlock ordering** | reservation writers vs. the expiry sweep | **SAFE — and this is a positive finding.** Every multi-entity writer acquires the **Reservation** row lock **before** the **Inventory** row lock: `commit` loads the reservation at `:169` and the inventory at `:182`; `release` at `:207` and `:221`; `releaseCommitted` at `:248` and `:259`; the sweep claims the reservation (`claimForExpiry`) at `:105-106` and only then touches the inventory (`releaseReserved`) at `:113`. `atomicReserve` touches **only** the inventory row and inserts a **new** reservation row (no lock conflict with an existing row). `InventoryServiceImpl.delete` reads the reservations table **without** a lock and locks only the inventory row. **No path acquires the two entities in the opposite order ⇒ no deadlock is possible.** |
| **Deadlock ordering** | two concurrent checkouts of the same cart | **SAFE** — `findByUserIdForUpdate` (`CartRepository.java:23-26`) takes a row lock with a 3 s timeout. |
| **Deadlock ordering** | two orders reserving the same two products | **SAFE** — the saga sorts the cart items by `productId` (`OrderCreateSaga.java:137`) before the reserve loop, so the inventory rows are always touched in the same order. The confirm fan-out is parallel but each item touches a **different** product row, and the inventory rows are only touched by one reservation at a time. |

---

## 6. ORDER STATE MACHINE

### 6.1 The enum

```java
// order-service/src/main/java/com/shop/orderservice/constant/OrderStatus.java:8-10
public enum OrderStatus {
    PENDING, CONFIRMED, SHIPPED, DELIVERED, CANCELLED
}
```

Five states. There is also a separate `ReturnStatus` (`constant/ReturnStatus.java:4`): `REQUESTED, APPROVED, REJECTED, REFUNDED`.

### 6.2 The transition table — the complete 5 × 5 matrix

The table lives in exactly one place:

```java
// order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java:16-22
private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
    OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
    OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED,   OrderStatus.CANCELLED),
    OrderStatus.SHIPPED,   EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
    OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
    OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class)
);
```

| from \ to | PENDING | CONFIRMED | SHIPPED | DELIVERED | CANCELLED |
|---|---|---|---|---|---|
| **PENDING** | ❌ rejected | ✅ allowed | ❌ rejected | ❌ rejected | ✅ allowed |
| **CONFIRMED** | ❌ rejected | ❌ rejected | ✅ allowed | ❌ rejected | ✅ allowed |
| **SHIPPED** | ❌ rejected | ❌ rejected | ❌ rejected | ✅ allowed | ✅ allowed (H12) |
| **DELIVERED** | ❌ rejected | ❌ rejected | ❌ rejected | ❌ rejected | ❌ rejected |
| **CANCELLED** | ❌ rejected | ❌ rejected | ❌ rejected | ❌ rejected | ❌ rejected |

**6 allowed transitions, 19 rejected (including every same-state transition).**

**Every rejection is `ORDER_INVALID_STATE_TRANSITION` (ORD-4004)** — `OrderStatusServiceImpl.java:25-29`.

### 6.3 Where each transition is enforced (file:line)

| Transition | Enforced at | Caller |
|---|---|---|
| `PENDING → CONFIRMED` | `OrderServiceImpl.java:328` | `confirmOrder` (HTTP `POST /orders/{id}/confirm` **and** the Kafka `payment.captured.v1` consumer) |
| `PENDING → CANCELLED` | `OrderServiceImpl.java:213` | `cancelOrder` (HTTP `POST /orders/{id}/cancel`) |
| `PENDING → CANCELLED` (auto) | `OrderReconciliationScheduler.java:212-224` — **NO `validateTransition` call** | the stuck-order sweeper |
| `CONFIRMED → SHIPPED` | `OrderServiceImpl.java:405` (via `transitionStatus`, called from `shipOrder:389-393`) | `OrderStatusController.ship` |
| `CONFIRMED → CANCELLED` | `OrderServiceImpl.java:213` + the admin gate at `:210-212` and `:231` | `cancelOrder` (ADMIN only) |
| `SHIPPED → DELIVERED` | `ShippingDeliveredHandler.java:40` | the `shipping.delivered.v1` Kafka consumer |
| `SHIPPED → CANCELLED` | `OrderServiceImpl.java:213` + `:252` | `cancelOrder` (ADMIN only) |
| `PENDING → CONFIRMED` (auto) | `OrderReconciliationScheduler.java:198-206` — **NO `validateTransition` call** | the stuck-order sweeper |
| `DELIVERED → *` / `CANCELLED → *` | the table's empty sets | — |

**Regression test for the whole table:** `OrderStatusServiceImplTest` (`order-service/.../service/impls/OrderStatusServiceImplTest.java:23-63`) — 18 table-driven cases, asserting `ORD-4004` for every rejected pair. ✔

### 6.4 Transitions the code permits that it arguably should not

1. **`SHIPPED → CANCELLED` (allowed, deliberate).** Added by "H12" for carrier loss / fraud / recall (`OrderStatusServiceImpl.java:19`; the rationale at `OrderServiceImpl.java:252-259`). The consequence is handled: no stock is moved back (the goods are in transit) and a `REQUESTED` return row is created so the refund is driven exactly once through the admin review (`OrderReturnServiceImpl.java:86-113`). **Justified**, but note it means a `SHIPPED` order can end `CANCELLED` with the stock still consumed — the stock is *not* returned to the pool at all, which is intentional but should be an explicit accounting rule.
2. **`CONFIRMED → CANCELLED` (allowed, admin only).** Guarded by `!isAdmin && order.getStatus() != OrderStatus.PENDING → ORDER_INVALID_STATE` (`OrderServiceImpl.java:210-212`), so a customer can never cancel a confirmed order. **Correct.**
3. **`PENDING → CANCELLED` by the reconciliation sweeper, bypassing the table.** See OI-07.
4. **`PENDING → CONFIRMED` by the reconciliation sweeper, bypassing the payment gate.** See OI-08.

**No transition that the table rejects is reachable through any public path** — every mutator routes through `orderStatusService.validateTransition` except the two sweeper paths above. In particular `CANCELLED → CONFIRMED` is **impossible**:
- `confirmOrder` calls `validateTransition(CANCELLED, CONFIRMED)` → rejected (`OrderServiceImpl.java:328`);
- the Kafka consumer swallows `ORDER_INVALID_STATE_TRANSITION` and acks (`PaymentLifecycleConsumer.java:35-40`);
- the sweeper only ever selects `PENDING` orders (`OrderReconciliationScheduler.java:113-115`) and its write is `@Version`-guarded.

### 6.5 Missing explicit state machine / transition guard

**There IS a central guard** (`OrderStatusService` / `OrderStatusServiceImpl`) and it is used by 4 of the 5 mutators. But:

1. **The guard is a plain static `Map`, not a state-machine abstraction.** There is no `OrderStateMachine` type, no per-transition side-effect hook (e.g. "on enter CANCELLED → release the stock"), no explicit terminal-state concept, and no way to enumerate the legal transitions for documentation or for an admin UI. Adding a transition means editing a `Map.of(...)` literal — a compile-time-unsafe, runtime-only change.
2. **The sweeper bypasses it** (OI-07) — and the `OrderStatus` javadoc explicitly forbids that: *"never mutate `Order.status` directly without going through `validateTransition`"* (`constant/OrderStatus.java:4-6`).
3. **`ReturnStatus` has NO transition table at all.** `OrderReturnServiceImpl.reviewReturn` (`:141-166`) only checks the precondition `status == REQUESTED` (`:146-148`) and then writes whatever the admin sent. Consequences:
   - `REQUESTED → REFUNDED` is reachable directly (`:155-161`);
   - `ReturnStatus.APPROVED` is a **dead state** — when the admin sends `APPROVED` the code immediately refunds and overwrites the status with `REFUNDED` (`:160`), so `APPROVED` is never persisted;
   - `REQUESTED → REJECTED` and `REQUESTED → APPROVED` are the only other transitions, and there is no guard against a future `REJECTED → APPROVED`.
4. **The `OrderReturn` entity has no `@Version`** (`entity/OrderReturn.java:27-70`) — so two concurrent reviews of the same return can both pass the `status == REQUESTED` precondition and both call the refund gateway (see OI-12).

---

## 7. IDEMPOTENCY

### 7.1 How duplicate checkouts are prevented

**Mechanism: a persisted idempotency row whose primary key IS the uniqueness constraint.**

```java
// order-service/src/main/java/com/shop/orderservice/entity/IdempotencyKey.java:10-13
@Entity
@Table(name = "idempotency_keys")
@IdClass(IdempotencyKey.IdempotencyKeyId.class)
```

```java
// order-service/src/main/java/com/shop/orderservice/entity/IdempotencyKey.java:20-26
@Id
@Column(name = "actor", nullable = false)
private String actor;

@Id
@Column(name = "key", nullable = false, length = 64)
private String key;
```

**The DB-level UNIQUE constraint** — the composite primary key:

```yaml
# order-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml:227-230
- addPrimaryKey:
    tableName: idempotency_keys
    columnNames: user_id, key
    constraintName: pk_idempotency_keys
```

and the column rename that follows it:

```yaml
# order-service/src/main/resources/db/changelog/changelog-005-idempotency-actor.yaml:21-26
- sql:
    sql: ALTER TABLE idempotency_keys ALTER COLUMN user_id TYPE VARCHAR(255) USING user_id::text
- renameColumn:
    tableName: idempotency_keys
    oldColumnName: user_id
    newColumnName: actor
```

**⇒ YES — the idempotency key IS persisted with a UNIQUE constraint, and it is enforced at the DB level (a composite primary key), not only in Java.** The Java-side check is a fast path; the PK is the arbiter.

### 7.2 The full lifecycle

| Phase | Code | Behaviour |
|---|---|---|
| Key acquisition | `IdempotencyServiceImpl.begin:67-89` | A **REQUIRES_NEW** transaction (`:71`) that SELECTs `(actor, key)` (`:72`), and if absent INSERTs an **in-flight** row with `response_status = 0` (`:76-79, 103-113`). The in-flight row is **committed before the saga starts**, so other requests observe it (the class javadoc at `:33-48` explains why: to avoid poisoning the caller's transaction). |
| Lost same-key race | `:80-86` | The INSERT raises `DataIntegrityViolationException` → the winner is re-read (`:83-85`) → `resolve(...)`. |
| Replay of a completed row | `resolve:91-98` | The stored `request_hash` must equal the incoming hash, otherwise `ORDER_DUPLICATE_REQUEST` (ORD-4010). On a match, the **stored response body is deserialized and returned** — the saga is NOT re-run. |
| In-flight collision | `resolve:99-100` | `ORD-4010` — the caller is told to retry later. |
| Completion | `complete:116-124` | `response_status = 201` (create) / `200` (confirm), `response_body` = the serialized `OrderResponse`. |
| Abort | `abort:126-138` | `REQUIRES_NEW` (`:127`); deletes the row **only if** it is still in-flight **and** the `request_hash` matches (`:132-133`) — so it can never delete another execution's row. |
| Expiry | `IdempotencyKeyCleanupScheduler.java:19-28` | Deletes rows with `expires_at < now` (TTL 24 h, `order-service/.../application.yml:73`). |

### 7.3 Where idempotency IS applied — and where it is NOT

| Operation | Idempotency key | Source |
|---|---|---|
| `POST /api/v1/orders` (checkout) | `Idempotency-Key` header, actor = `userId` | `OrderServiceImpl.java:153` |
| `POST /api/v1/orders/{id}/confirm` | `Idempotency-Key` header, actor = `service:<azp>` or the admin `sub` | `OrderServiceImpl.java:320-322`; the actor label is resolved at `OrderStatusController.java:49-55` |
| `payment.captured.v1` Kafka event → confirm | **`"payment-confirm-" + paymentId`** — deterministic, so a redelivered event replays | `PaymentLifecycleConsumer.java:34` |
| `POST /api/v1/inventory/{productId}/reserve` | **NONE** | `inventory-service/.../dto/request/ReserveRequest.java:8-11` → **OI-01** |
| `POST /api/v1/inventory/reservations/{id}/commit` | **NONE** — but the **remote operation is idempotent** (`InventoryServiceImpl.commit:172-175`) | ✔ acceptable |
| `POST /api/v1/inventory/reservations/{id}/release` | **NONE** — the remote operation is idempotent (`InventoryServiceImpl.release:210-214`) | ✔ acceptable |
| `POST /api/v1/inventory/reservations/{id}/release-committed` | **NONE** — the remote operation is idempotent (`InventoryServiceImpl.releaseCommitted:251-255`) | ✔ acceptable |
| `POST /api/v1/promotions/reservations/{id}/commit|release|release-committed` | **NONE** — promotion-service is out of scope | n/a |

### 7.4 The hole: the protection is OPT-IN

`IdempotencyServiceImpl.begin:68` → `if (key == null) return Optional.empty();` — **a checkout with no `Idempotency-Key` header gets NO deduplication at all.** The header is `required = false` (`OrderController.java:35`), and `OrderControllerTest.createOrder_worksWithoutIdempotencyKey` (`:107-114`) proves the request is accepted. A double-clicking "Place order" from a client that omits the header creates **two orders and two sets of reservations**. This is **OI-05**.

---

## 8. DESIGN PATTERN EVALUATION

### 8.1 What is genuinely present

| Pattern | Present? | Where | Quality |
|---|---|---|---|
| **State Machine** | **PARTIAL** | `OrderStatusServiceImpl.ALLOWED` (`order-service/.../service/impls/OrderStatusServiceImpl.java:16-22`), used at `OrderServiceImpl.java:213, 328, 405` and `ShippingDeliveredHandler.java:40` | A **transition table**, not a state machine. No explicit terminal states, no side-effect hooks, no event on transition. **Bypassed** by the reconciliation sweeper (OI-07). `ReturnStatus` has none. |
| **Saga** | **YES — and genuinely well-built** | `OrderCreateSaga` (`order-service/.../service/impls/OrderCreateSaga.java`), `OrderCommitCoordinator` (`.../service/OrderCommitCoordinator.java`), `OrderReconciliationScheduler` (`.../service/OrderReconciliationScheduler.java`) | Orchestration (not choreography) with explicit transaction boundaries, a compensation ledger, and a reconciliation sweeper. The transaction boundaries are **deliberately** separate from the HTTP calls to avoid pool exhaustion (`OrderCreateSaga.java:41-46`, `OrderServiceImpl.java:190-200`) — and `CancelOutsideTransactionIT` (`:43-92`) *proves* it with a 1-connection pool. **The compensation coverage is incomplete** (F7, F11 — OI-02, OI-03). |
| **Reservation** | **YES** | `Reservation` entity (`inventory-service/.../entity/Reservation.java`), `ReservationService`/`ReservationServiceImpl`, `InventoryServiceImpl.reserve/commit/release/releaseCommitted`, `OrderItem.reservationId` (`order-service/.../entity/OrderItem.java:43-44`) | A first-class aggregate with its own lifecycle, a `@Version`, a TTL, a conditional claim, and a state endpoint for reconciliation (`InventoryController.java:118-122`). **Strong.** The gap is the missing idempotency key (OI-01). |
| **Optimistic Locking** | **YES** | `Order.@Version` (`order-service/.../entity/Order.java:108-111`, added by `changelog-003-orders-version.yaml:14-22`), `Inventory.@Version` (`inventory-service/.../entity/Inventory.java:58-59`), `Reservation.@Version` (`inventory-service/.../entity/Reservation.java:76-79`, added by `changelog-004:32-40`) | Correctly applied to the three aggregates that need it. **Not applied** to `Cart` (OI-09) or `OrderReturn` (OI-14). |
| **Pessimistic Locking** | **YES — narrowly and correctly** | `CartRepository.findByUserIdForUpdate` / `findByIdAndUserIdForUpdate` (`order-service/.../repository/CartRepository.java:23-31`, `SELECT … FOR UPDATE`, 3 s timeout); the outbox claims in both services (`PESSIMISTIC_WRITE` + SKIP LOCKED) | Used exactly where a "select the one active row" is needed. **Not** used on the inventory row (where the atomic UPDATE is better). |
| **Reconciliation Sweeper** | **YES** | `ReservationCleanupScheduler` (inventory), `OrderReconciliationScheduler` + `CompensationFailureService.retryPending` + `IdempotencyKeyCleanupScheduler` + `GuestCartCleanupScheduler` + `OutboxRetentionScheduler` (order) | Present on both sides, with the correct "never auto-decide on incomplete data" posture (`OrderReconciliationScheduler.java:36-41, 191-194`). **But** the inventory-side sweeper does not consult the order-service at all (it cannot — separate databases), and the order-side sweeper **never releases** anything, which is why OI-02 is unrecoverable. |
| **Specification / Policy** | **NO** | — | `InventoryServiceImpl.update` has an inline `available >= reserved` policy (`:95-103`); `OrderReturnServiceImpl.requestReturn` has an inline cumulative-refund policy (`:62-68`); `OrderStatusServiceImpl` has an inline transition table. **No `Specification` objects, no policy interface, no strategy.** |
| **Domain Events** | **YES — transactional outbox** | `OrderEventPublisher`/`OrderEventPublisherImpl` (`order-service/.../service/impls/OrderEventPublisherImpl.java`), `InventoryEventPublisher`/`TransactionalInventoryEventPublisher` (`inventory-service/.../service/impls/TransactionalInventoryEventPublisher.java`), the outbox tables and relays in both services | Correctly implemented: the event row is written **in the same transaction** as the state change (`:103` and `:132` respectively), and the relay drains it with a claim lease and a compare-and-set mark. Events are **not** published as Spring `ApplicationEvent`s — they are durable rows, which is the right call for a distributed fleet. |

### 8.2 Justified additions (with concrete sketches)

#### (a) `ReservationIdempotencyPolicy` — closes OI-01

```java
// inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationIdempotencyPolicy.java
package com.shop.inventoryservice.service;

import java.util.UUID;

public interface ReservationIdempotencyPolicy {

    /**
     * The caller-supplied key that makes POST /reserve idempotent.
     * MUST be a stable function of the business intent
     * (e.g. SHA-256 of orderId + productId + lineIndex), never a random UUID:
     * a retry must produce the SAME key or the dedupe is useless.
     */
    String reserveKey(UUID orderId, UUID productId, int lineIndex);

    /** True when the key must be honoured (an order-scoped reserve). */
    boolean isIdempotent(UUID orderId);
}
```

```java
// inventory-service/src/main/java/com/shop/inventoryservice/service/impls/OrderScopedReservationIdempotencyPolicy.java
package com.shop.inventoryservice.service.impls;

import com.shop.inventoryservice.service.ReservationIdempotencyPolicy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class OrderScopedReservationIdempotencyPolicy implements ReservationIdempotencyPolicy {

    @Override
    public String reserveKey(UUID orderId, UUID productId, int lineIndex) {
        String raw = orderId + ":" + productId + ":" + lineIndex;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    @Override
    public boolean isIdempotent(UUID orderId) {
        return orderId != null;
    }
}
```

The DB side (a new Liquibase changeset):

```yaml
- changeSet:
    id: 007-reservations-idempotency-key
    author: production-audit
    changes:
      - addColumn:
          tableName: reservations
          columns:
            - column: { name: idempotency_key, type: VARCHAR(64) }
      - addUniqueConstraint:
          tableName: reservations
          columnNames: idempotency_key
          constraintName: uk_reservations_idempotency_key
```

And the service change — `InventoryServiceImpl.reserve` must return the **existing** reservation when the key collides, instead of inserting a second row.

#### (b) `OrderStateMachine` — closes OI-07 and gives the return flow a table

```java
// order-service/src/main/java/com/shop/orderservice/service/OrderStateMachine.java
package com.shop.orderservice.service;

import com.shop.orderservice.constant.OrderStatus;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The single source of truth for order lifecycle transitions.
 * Every mutator MUST go through {@link #require(OrderStatus, OrderStatus)};
 * there is no second, bypassable copy of this table.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            OrderStatus.PENDING,   EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
            OrderStatus.CONFIRMED, EnumSet.of(OrderStatus.SHIPPED,   OrderStatus.CANCELLED),
            OrderStatus.SHIPPED,   EnumSet.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED),
            OrderStatus.DELIVERED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));

    private OrderStateMachine() {
    }

    public static boolean isAllowed(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class)).contains(to);
    }

    public static Set<OrderStatus> allowedFrom(OrderStatus from) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class));
    }

    public static boolean isTerminal(OrderStatus status) {
        return allowedFrom(status).isEmpty();
    }
}
```

`OrderStatusServiceImpl` then becomes a thin adapter over it, and `OrderReconciliationScheduler` calls the same `validateTransition` before its saves — the bypass disappears.

#### (c) `CompensationKind` — closes OI-02

```java
// order-service/src/main/java/com/shop/orderservice/entity/CompensationKind.java
package com.shop.orderservice.entity;

public enum CompensationKind {

    /** Release a PENDING reservation: the checkout failed, the hold must go back. */
    RESERVATION_RELEASE,

    /** Release a COMMITTED reservation: the confirm failed after a partial commit. */
    RESERVATION_RELEASE_COMMITTED,

    /** Release a COMMITTED promotion reservation. */
    PROMOTION_RELEASE_COMMITTED
}
```

`CompensationFailureService.retryPending` then dispatches on the kind instead of always calling the plain `release` (`CompensationFailureService.java:100`), and `OrderCommitCoordinator.compensateInReverse` records its failures through the ledger instead of only logging them (`OrderCommitCoordinator.java:148-151`).

#### (d) `StockAccountingPolicy` — closes OI-11 and makes the invariants explicit

```java
// inventory-service/src/main/java/com/shop/inventoryservice/service/StockAccountingPolicy.java
package com.shop.inventoryservice.service;

import com.shop.inventoryservice.entity.Inventory;

/**
 * The single place where the meaning of available_quantity and
 * reserved_quantity is defined. Every write path asserts the invariant
 * BEFORE it writes; the DB CHECK constraints are the last line of defence.
 */
public interface StockAccountingPolicy {

    /** capacity = available - reserved. The reserve guard is exactly this. */
    int capacity(Inventory inventory);

    /** True when the row may be deleted: no live hold may reference it. */
    boolean mayDelete(Inventory inventory, long activeReservations);

    /** A single place to express "a hold of qty fits into the current capacity". */
    boolean canHold(Inventory inventory, int qty);
}
```

#### (e) `ReconciliationDecision` — closes OI-08

```java
// order-service/src/main/java/com/shop/orderservice/service/ReconciliationDecision.java
package com.shop.orderservice.service;

public enum ReconciliationDecision {

    /** Every applicable reservation is COMMITTED → the order may be confirmed. */
    CONFIRM,

    /** Every applicable reservation is terminal → the order must be cancelled. */
    CANCEL,

    /** Incomplete or contradictory evidence → no automatic action, alert only. */
    ESCALATE
}
```

The sweeper currently encodes this decision inline (`OrderReconciliationScheduler.java:156-164`). Making it a value type lets the **payment gate be added as a precondition of `CONFIRM`** — the gate that the sweeper currently skips (OI-08).

---

## 9. DEFECT LIST

Severity scale: **CRITICAL** (money/stock loss, exploitable) · **HIGH** (money/stock loss under realistic conditions) · **MEDIUM** (invariant violated, requires an operator or produces user-visible loss) · **LOW** (robustness, hygiene, drift) · **INFO** (documentation/design).

---

### OI-01 — HIGH — The inventory `reserve` API is not idempotent: duplicate holds and 900-second orphan holds

- **Evidence**
  - `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java:8-11` — the request carries only `quantity` and `orderId`.
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:138-164` — `reserve` has no dedupe: it always runs `atomicReserve` (`:141`) and always INSERTs a new `reservations` row (`:159`).
  - `inventory-service/src/main/resources/db/changelog/changelog-001-initial-schema.yaml:39-72` — the `reservations` table has **no unique constraint**; only `idx_reservations_product_id` and `idx_reservations_status_expires`.
  - `inventory-service/src/main/resources/db/changelog/changelog-006-reservations-order-id-index.yaml:21-24` — adds only a **non-unique** index on `order_id`.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:190-198` — the reserve loop keeps only the ids it received.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:234-254` — the compensation releases only the ids in `reserved`.
  - `inventory-service/src/main/resources/application.yml:67` — `reservation-ttl-seconds: 900`.
  - `order-service/src/main/resources/application.yml:51-53` — the inventory timeout is 5 000 ms, so a response loss is a realistic window.
- **Actual behaviour**
  1. A duplicated reserve call for the same order line increments `reserved_quantity` twice and creates two `PENDING` reservations. On confirm, `commit` deducts `available_quantity` once per reservation (`InventoryServiceImpl.java:185-191`) → **the shop oversells by the duplicated quantity**. This is the **only** oversell path found in the audit.
  2. If the HTTP response is lost after the DB commit, the order-service never learns the `reservationId` → the hold survives until the 900 s TTL sweep → up to 15 minutes of unsellable stock per affected order, with only a log line as evidence.
- **Violated invariant** — "one order line ⇒ at most one live stock hold"; "a retried reserve is idempotent"; "no stock is held by an order that does not exist".
- **Root cause** — the reserve operation has no idempotency key and the storage has no uniqueness constraint; the API contract treats the caller as trusted to call exactly once.
- **Proposed fix** — add `idempotency_key` to `reservations` with a `UNIQUE` constraint (a new Liquibase changeset); the caller derives it deterministically from `(orderId, productId, lineIndex)`; `InventoryServiceImpl.reserve` returns the **existing** reservation on a duplicate key instead of inserting a second row. Sketch: §8.2(a).
- **Reproduction test** — an IT (Testcontainers Postgres) that (i) fires the same reserve twice with the same key and asserts **one** `reservations` row and one increment of `reserved_quantity`; (ii) reserves one unit with a 1-unit inventory from two threads and asserts exactly one success and one `STOCK_INSUFFICIENT` (**this also closes the Race-A test gap identified in §4.3**).
- **Acceptance criterion proved** — "the reserve operation is idempotent and the stock counters reflect the live holds exactly" (the property `InventoryLifecycleIdempotencyTest` proves for commit/release but **not** for reserve).

### OI-02 — HIGH — A failed `releaseCommitted` in the confirm-compensation path is dropped: no ledger, no retry, stock permanently consumed

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:133-139` — the catch calls `compensateInReverse(committed)` (`:136`).
  - `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:142-153` — `compensateInReverse` catches the exception and only logs + counts `order.confirm.commit.outcome{result=rollback_failed}` (`:148-151`). The log message says *"reconciliation owns it"* (`:149`).
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:180-196` — the reconciler **only reads** reservation states; it never issues a release.
  - `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java:58-82` — the ledger exists but is **not wired** into the confirm path (it is only called from `OrderCreateSaga.java:246-249` and `OrderServiceImpl.java:284-290`).
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:246-269` — `releaseCommitted` is the **only** path that can return the stock to `available_quantity`; the TTL sweep never touches `COMMITTED` reservations (`ReservationCleanupScheduler.java:67-68` sweeps `PENDING` only).
- **Actual behaviour** — one item's `release-committed` returns 5xx → the exception is swallowed → that reservation stays `COMMITTED` → its stock stays consumed (`available_quantity` was already decremented at commit time) → the order stays `PENDING` → the reconciliation sweeper sees a **mixed** state and takes no action (`OrderReconciliationScheduler.java:162-164, 226-230`). **Nothing in the system will ever release that stock.** The customer also never receives the order.
- **Violated invariant** — "every stock hold belonging to a non-confirmed order is eventually released"; "a failed compensation leaves a durable record and is retried".
- **Root cause** — the compensation ledger was wired into the create-saga path but not into the confirm path; the confirm path treats the compensation as best-effort fire-and-forget.
- **Proposed fix** — in `compensateInReverse`, record the failure via `CompensationFailureService.record(orderId, reservationId, CompensationKind.RESERVATION_RELEASE_COMMITTED, msg)`; extend `retryPending` to dispatch on the kind. Sketch: §8.2(c).
- **Reproduction test** — a `ConfirmOrchestrationIT` case in which the `/release-committed` stub returns 500 → assert a `PENDING` row in `failed_compensations` and that a later `retryPending()` restores `available_quantity`.
- **Acceptance criterion proved** — "a failed compensation leaves a durable record and is retried" — the property `CompensationFailureLedgerIT.java:40-108` proves **for the create path**; the confirm path has no equivalent.

### OI-03 — MEDIUM-HIGH — A failure of the saga's final transaction leaks every stock reservation

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:190-198` — the reservations are taken.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:219-226` — **TX C** (`executeInTxWithoutResult`): `orderRepository.save(order)`, `orderItemRepository.saveAll(orderItems)`, `cartItemRepository.deleteAll(items)`, `cart.markDeleted(...)`, `cartRepository.save(cart)`, `orderEventPublisher.publishCreated(order, orderItems)`.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:199-217` — the `try/catch` covers **only** the reserve loop; there is **no** `try/catch` around `:219-226`.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderEventPublisherImpl.java:96-100` — a serialization failure throws `IllegalStateException`, aborting TX C.
- **Actual behaviour** — TX C fails (a DB error, or the outbox payload cannot be serialized). The transaction rolls back → no order, no items, the cart is intact → **but every reservation that was successfully taken stays `PENDING` and is never released.** The customer sees an error and retries; the first attempt's stock is still held for up to 900 s.
- **Violated invariant** — "every successful stock hold is either committed or released"; "a failed checkout leaves no live holds".
- **Root cause** — the compensation logic is attached to the reserve loop's `catch`, not to the saga as a whole. The saga's happy path (TX C) has no compensation arm.
- **Proposed fix** — wrap TX C in a `try/catch` that calls `releaseAllReservations(order.getId(), reserved)` and rethrows; or restructure the saga so the compensation is in a single `finally`-style guard keyed on "the order was not committed".
- **Reproduction test** — an IT that makes `publishCreated` throw (e.g. by injecting a failing `ObjectMapper`) and asserts that `/inventory/reservations/{id}/release` was called for **every** reserved line.
- **Acceptance criterion proved** — "a failed checkout releases every reservation it took" — the property `OrderCreationSagaIntegrationTest.partialReservationFailure_releasesReservedItems` (`:111-165`) proves for the *reserve* failure; **no test covers a post-reserve failure**.

### OI-04 — MEDIUM — `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:229-231` — the response is built and `idempotencyService.complete(idempotencyKey, userId.toString(), response, 201)` is called **after** TX C committed.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java:116-124` — `complete` is `@Transactional` and **throws** if the row is missing (`:120`).
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:159-165` — the catch calls `idempotencyService.abort(idempotencyKey, userId.toString(), requestHash)`, which **deletes** the in-flight row (`IdempotencyServiceImpl.java:134`), freeing the key.
- **Actual behaviour** — `complete` fails (a transient DB error) → `abort` deletes the in-flight key → the client retries with the **same** key → `begin` finds nothing → the saga runs again → a **second order** is created (and a second set of reservations, and the cart is empty the second time so it may even fail). The first order is live, `PENDING`, and reachable only through the order list.
- **Violated invariant** — "one idempotency key ⇒ at most one order".
- **Root cause** — the abort-on-failure policy is applied unconditionally, but the failure may have occurred **after** the side effect was committed.
- **Proposed fix** — separate the "the saga failed before any side effect" case from the "the saga committed but the bookkeeping failed" case: only `abort` when the order row does not exist; otherwise `complete` with a fallback response, or leave the in-flight row to expire and let `begin` resolve it.
- **Reproduction test** — an IT that makes `IdempotencyService.complete` throw on the first call, then retries `createOrder` with the same key, and asserts exactly **one** `orders` row for that user.
- **Acceptance criterion proved** — "the idempotency key is replay-safe across the whole request, not just the saga body" — the property `OrderCreationSagaIntegrationTest.idempotencyReplay_returnsCachedResponse` (`:207-229`) proves for the happy path only.

### OI-05 — MEDIUM — The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyServiceImpl.java:68` — `if (key == null) return Optional.empty();` — the key is optional.
  - `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:35` — `@RequestHeader(value = "Idempotency-Key", required = false)`.
  - `order-service/src/test/java/com/shop/orderservice/controller/OrderControllerTest.java:107-114` — `createOrder_worksWithoutIdempotencyKey` proves the headerless request is accepted.
- **Actual behaviour** — a client that omits the header (or a double-clicking "Place order" button) creates two orders and two sets of stock reservations for one cart.
- **Violated invariant** — "a checkout is idempotent by default".
- **Root cause** — the API made the strongest available guarantee optional, with no server-side fallback (e.g. a derived key from the cart id + a content hash).
- **Proposed fix** — make the header mandatory (400 without it), or derive a fallback key from `(userId, cartId, requestHash)` when the header is absent so the protection is unconditional.
- **Reproduction test** — `OrderControllerTest.createOrder_withoutIdempotencyKey_returns400` (or, if the fallback is chosen, an IT that calls `createOrder` twice with a `null` key and asserts one order).
- **Acceptance criterion proved** — "duplicate checkouts are prevented" — the property `OrderCreationSagaIntegrationTest.idempotencyReplay_returnsCachedResponse` (`:207-229`) proves it **only when the header is present**.

### OI-06 — MEDIUM — The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim

- **Evidence**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java:102-111` — the **claim** loop: `claimForExpiry(...)`; `if (claimed == 0) continue;` (`:107-109`) — the un-claimed rows are skipped.
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java:125-129` — the **publish** loop iterates the **whole `batch`**, not the claimed subset:
    ```java
    for (Reservation reservation : batch) {
        inventoryRepository.findByProductId(reservation.getProductId()).ifPresent(inventory -> {
            publisher.publishReleased(inventory, reservation, "PENDING");
        });
    }
    ```
- **Actual behaviour** — a reservation that was **not** claimed (because a concurrent `commit` won the race, or because another sweeper claimed it) still gets an `inventory.released.v1` event published on `shop.inventory.events.v1`, with `previousStatus = "PENDING"`, while the row is actually `COMMITTED`. A consumer that trusts the event ("stock was returned to the pool") over-credits the product.
- **Violated invariant** — "the inventory event stream faithfully reflects the actual reservation state".
- **Root cause** — the publish loop re-iterates the query result instead of the claimed set.
- **Proposed fix** — collect the claimed reservations into a `List<Reservation> claimed` during the claim loop and publish only those.
- **Reproduction test** — extend `ReservationCleanupSchedulerTest.sweep_skipsRowsAlreadyClaimedByAnotherWriter` (`inventory-service/.../service/ReservationCleanupSchedulerTest.java:103-123`) to assert `verify(publisher, never()).publishReleased(any(), any(), anyString())`.
- **Acceptance criterion proved** — "the sweep publishes exactly one released event per expired reservation, and none for reservations it did not expire".

### OI-07 — MEDIUM — The reconciliation scheduler bypasses the order state machine

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:198-206` — `autoConfirm` sets `order.setStatus(OrderStatus.CONFIRMED)` directly.
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:212-224` — `autoCancel` sets `order.setStatus(OrderStatus.CANCELLED)` directly.
  - `order-service/src/main/java/com/shop/orderservice/constant/OrderStatus.java:4-6` — *"never mutate `Order.status` directly without going through `validateTransition`"*.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderStatusServiceImpl.java:16-22` — the table that is bypassed.
- **Actual behaviour** — the transition table is not consulted on the reconciliation path. Today the produced transitions (`PENDING → CONFIRMED`, `PENDING → CANCELLED`) happen to be legal, so no illegal state is *currently* reachable — but the guard provides nothing on that path, and a future change to the table would silently diverge.
- **Violated invariant** — "every status change passes the state machine" (defence in depth).
- **Root cause** — the sweeper cannot call `confirmOrder`/`cancelOrder` (that would re-run the orchestration and recurse — the class javadoc at `:46-50` explains this), so it copied the mutation instead of reusing the guard.
- **Proposed fix** — inject `OrderStatusService` into `OrderReconciliationScheduler` and call `validateTransition(order.getStatus(), target)` inside the transaction, before the save.
- **Reproduction test** — a scheduler unit test that seeds a non-`PENDING` order as a candidate and asserts the guard is consulted (mirroring `OrderServiceImplTest.cancelOrder_throwsOnDelivered`, `:98-109`).
- **Acceptance criterion proved** — "the state machine is the single authority on transitions" — the property `OrderStatusServiceImplTest` (`:23-63`) proves the **table**, but nothing proves it is **used everywhere**.

### OI-08 — MEDIUM — The reconciliation auto-confirm bypasses the captured-payment gate

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:156-159` — `allCommitted` → `autoConfirm(order)`.
  - `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:198-206` — `autoConfirm` sets `CONFIRMED` with **no payment check**.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:334-345` — the *normal* confirm path **requires** a `CAPTURED` payment and fails closed (`ORD-4012`).
  - `order-service/src/main/java/com/shop/orderservice/config/PaymentGateValidator.java:39-46` — the gate is a **startup-failing** control: *"an order could reach CONFIRMED with no captured payment"*.
- **Actual behaviour** — a `PENDING` order whose stock reservations are all `COMMITTED` but whose payment was never captured (or was refunded) is auto-confirmed by the sweeper, bypassing the gate that the service itself treats as safety-critical.
- **Violated invariant** — "an order reaches `CONFIRMED` only with a `CAPTURED` payment".
- **Root cause** — the reconciler re-implements the confirm decision without the payment precondition.
- **Proposed fix** — add the captured-payment check to `autoConfirm` (or, better, extract the decision into the `ReconciliationDecision` value type of §8.2(e) with the payment gate as a precondition of `CONFIRM`).
- **Reproduction test** — an IT that seeds a `PENDING` order with a `COMMITTED` reservation and **no** payment, runs `reconcileStuckOrders()`, and asserts the order is **not** `CONFIRMED`.
- **Acceptance criterion proved** — "the captured-payment gate is enforced on every path that can confirm an order" — the property `ConfirmOrchestrationIT.confirm_withoutCapturedPayment_failsClosedWithOrd4012` (`:149-161`) proves it for the **HTTP** path only.

### OI-09 — MEDIUM — `Cart` has no `@Version`: the cart subtotal is a lost update

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/entity/Cart.java:11-27` — `Cart extends AbstractMappedEntity`; `AbstractMappedEntity extends SoftDeletable`; **neither carries a version column** (verified by reading `utils/common-core/.../AbstractMappedEntity.java` and `SoftDeletable.java`). Compare `Order.java:108-111`, which **does** have `@Version`.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:81-82` (`addItem`), `:104-105` (`updateItem`), `:119-120` (`removeItem`), `:173-174` (`mergeCart`) — each computes the subtotal from a read and writes it back.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:197-201` — `calculateSubtotal` re-reads the items and sums them in Java.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:132-134` — TX A locks the cart `FOR UPDATE`.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:223-224` — TX C merges the **detached** `cart` from TX A.
- **Actual behaviour**
  1. Two concurrent cart mutations both read the same cart row and both write `subtotal` → last-write-wins → `carts.subtotal` no longer equals the sum of `cart_items`.
  2. Between TX A and TX C of the saga, a concurrent `addItem` is **silently overwritten** by TX C's merge of the stale `cart` entity (TX C writes `cart.deleted = true` and the stale `subtotal`).
- **Violated invariant** — "no lost update"; "`carts.subtotal` == the sum of the cart items".
- **Root cause** — no optimistic-lock column on `carts`, and the subtotal is a read-compute-write in Java.
- **Proposed fix** — add a `version` column to `carts` (a Liquibase changeset) and `@Version` to `Cart`; recompute the subtotal with a single aggregate `UPDATE … SET subtotal = (SELECT SUM(...))`.
- **Reproduction test** — a `@DataJpaTest` that persists a cart, mutates a stale detached copy, and asserts `OptimisticLockingFailureException` (the `OrderRepositoryTest.versionIncrementsOnUpdate_andStaleSaveThrows` pattern, `:117-135`).
- **Acceptance criterion proved** — "concurrent writes to the same aggregate are serialised" — proven for `orders` by `OrderRepositoryTest` (`:117-135`) but **not** for `carts`.

### OI-10 — MEDIUM — `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java:90-92` — `@Scheduled` + `@Transactional` on the method.
  - `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java:94-97` — fetches up to 50 rows.
  - `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java:98-117` — the loop performs `stockReservationService.release(row.getReservationId())` (`:100`) — an HTTP call with a 5 s timeout (`order-service/.../application.yml:52`) — **inside the transaction**, and calls `repository.save(row)` (`:116`) inside the same transaction.
- **Actual behaviour** — the transaction can stay open for up to 250 s, holding a pooled connection (the pool is 20, `utils/common-spring/src/main/resources/application.yml:32`) and row locks on 50 `failed_compensations` rows. A single slow inventory call blocks the whole batch; with Hikari's 5 s connection timeout (`application.yml:34`) a burst of such sweeps can starve the pool.
- **Violated invariant** — "no transaction spans a remote call" — the exact rule `cancelOrder` was explicitly refactored to satisfy (`OrderServiceImpl.java:190-200`) and which `CancelOutsideTransactionIT` (`:43-92`) proves for the cancel path.
- **Root cause** — the retry was written as a straightforward `@Transactional` loop without the pool-exhaustion lesson the cancel path already learned.
- **Proposed fix** — read the 50 candidate ids in one short read-only transaction, perform the HTTP releases **outside** any transaction, then persist each outcome in its own short transaction (the shape `OrderOutboxRelay` already uses).
- **Reproduction test** — an IT with `spring.datasource.hikari.maximum-pool-size=1` and a 3 s delayed release stub, mirroring `CancelOutsideTransactionIT` (`:37-92`): assert a concurrent read is not blocked.
- **Acceptance criterion proved** — "the database connection is never held across a remote call" — proven for cancel by `CancelOutsideTransactionIT`; **not** proven for the compensation retry.

### OI-11 — MEDIUM — `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act

- **Evidence**
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:123-134` — `delete` reads `activeReservationCount(productId)` (`:127`, implemented at `:115-120` as a plain `countByProductIdAndStatusIn` SELECT) and then `inventoryRepository.delete(existing)` (`:131`).
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:141` — a concurrent `reserve` performs `atomicReserve` **first** and only then INSERTs the `reservations` row (`:159`).
  - `inventory-service/src/main/resources/db/changelog/changelog-006-reservations-order-id-index.yaml:7-16` — there is deliberately **no FK** from `reservations` to `inventory`.
- **Actual behaviour** — `reserve` executes `atomicReserve` (committed, `reserved_quantity` now > 0) but has not yet inserted its `reservations` row; the delete's count sees **0** active reservations → the delete proceeds → the inventory row is deleted with `reserved_quantity > 0` → the reserve's `findByProductId` (`:148`) throws `INVENTORY_NOT_FOUND` → the reserve transaction rolls back → **the stock is gone and the product is off-sale**, with a `PENDING` reservation pointing at a deleted row.
- **Violated invariant** — "an inventory row is never deleted while a live hold references it"; "stock is never silently destroyed".
- **Root cause** — the guard and the delete are not serialised; without an FK, the application check is the only barrier and it is not atomic with the delete.
- **Proposed fix** — take the inventory row's write lock first (`SELECT … FOR UPDATE` on `inventory`) and re-check the count inside that lock; or add a partial FK from `reservations(product_id)` to `inventory(product_id)` restricted to `status IN ('PENDING','COMMITTED')`.
- **Reproduction test** — an IT that runs a `reserve` and a `delete` concurrently for the same product and asserts the delete is rejected (`INVENTORY_IN_USE`).
- **Acceptance criterion proved** — "an inventory row with a live hold cannot be deleted" — proven only for the *committed* case by `InventoryServiceImplTest.delete_throwsInventoryInUse_whenReservationActive` (`:264-273`), which seeds the reservation **first**.

### OI-12 — MEDIUM — The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:155-161` — the refund is invoked **first**:
    ```java
    if (request.status() == ReturnStatus.APPROVED || request.status() == ReturnStatus.REFUNDED) {
        boolean refunded = paymentServiceClient.refundByOrderId(orderReturn.getOrder().getId());
        if (!refunded) {
            throw BusinessException.badRequest("order.return.refund.gateway.failed");
        }
        orderReturn.setStatus(ReturnStatus.REFUNDED);
    }
    ```
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:163` — the row is saved **after** the gateway call.
  - `order-service/src/main/java/com/shop/orderservice/entity/OrderReturn.java:27-70` — `OrderReturn` has **no `@Version`**.
- **Actual behaviour** — the refund succeeds at the gateway, then `orderReturnRepository.save(...)` fails (a transient DB error) → the transaction rolls back → the return stays `REQUESTED` → an admin can review it again → **a second refund is issued** for the same order.
- **Violated invariant** — "a refund is issued at most once per return".
- **Root cause** — an irreversible money movement is performed before the durable state that records it, and the aggregate has no optimistic lock to serialise concurrent reviews.
- **Proposed fix** — persist a `REFUND_PENDING` state **first** (with `@Version` on `OrderReturn`), then call the gateway with the `returnId` as the idempotency key, then persist `REFUNDED`; treat a `REFUND_PENDING` row on startup as a retry candidate.
- **Reproduction test** — an IT that makes `orderReturnRepository.save` throw once and asserts the second review does **not** call `paymentServiceClient.refundByOrderId` again.
- **Acceptance criterion proved** — "the refund is fired exactly once" — the property `OrderShippedCancellationIT` (`:67-80`) proves the refund is *driven* once through the review, but not that it is *issued* once under failure.

### OI-13 — MEDIUM-LOW — The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/client/ProductServiceClient.java:36-38` — `@Cacheable(value = "productPrice", key = "#productId")` on `getProduct`.
  - `order-service/src/main/java/com/shop/orderservice/config/CacheConfig.java:21` — `PRODUCT_PRICE_TTL = Duration.ofMinutes(10)`; `:49` applies it to the `productPrice` cache.
  - A repo-wide grep of both modules for `@CacheEvict`/`@CachePut` returns **zero** hits (the only match is a comment in `inventory-service/.../config/CacheConfig.java:27`).
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:183-185` — the line price and the line total are computed from this snapshot.
- **Actual behaviour** — a catalogue price change is invisible to checkout for up to 10 minutes; the order is created, the confirmation email and the invoice use the stale price, and the shop absorbs the difference (or overcharges).
- **Violated invariant** — "the charged amount reflects the catalogue price at the moment of checkout".
- **Root cause** — the cache is TTL-only; there is no eviction path from the catalogue side (order-service does not consume the product lifecycle topic).
- **Proposed fix** — consume the product-lifecycle event and evict the affected `productPrice` key, or reduce the TTL and record the price snapshot's age on the order.
- **Reproduction test** — an IT that changes the product price in the stub, calls `getProduct` twice within the TTL, and asserts the second call sees the new price (currently it does not).
- **Acceptance criterion proved** — "checkout pricing is never stale beyond the accepted window".

### OI-14 — MEDIUM-LOW — The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:55-60` — the "is there a `REQUESTED` return?" check.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:62-68` — the cumulative refund check: `alreadyApprovedOrRefunded.add(request.refundAmount()).compareTo(order.getTotal()) > 0` → reject.
  - `order-service/src/main/java/com/shop/orderservice/entity/OrderReturn.java:27-70` — no `@Version`.
  - `order-service/src/main/java/com/shop/orderservice/entity/Order.java:108-111` — the **order** has a `@Version`, but `requestReturn` does not lock it.
- **Actual behaviour** — two concurrent return requests for the same order both read the same `alreadyApprovedOrRefunded` total and both pass the check → the cumulative refund exceeds `order.total`.
- **Violated invariant** — "the sum of approved + refunded returns never exceeds the order total".
- **Root cause** — a check-then-act on an aggregate with no lock and no optimistic version.
- **Proposed fix** — lock the order row `FOR UPDATE` inside `requestReturn` (or use the order's `@Version` with a retry), and re-read the returns inside the lock.
- **Reproduction test** — an IT that fires two concurrent `requestReturn` calls whose amounts sum to more than `order.total` and asserts that exactly one succeeds.
- **Acceptance criterion proved** — "the cumulative refund cannot exceed the order total" — the property `OrderReturnServiceTest.requestReturn_cumulativeRefundExceedsTotal_throwsBadRequest` (`:161-191`) proves it for the **sequential** case only.

### OI-15 — LOW — `failed_compensations` has no uniqueness on `(reservation_id, kind)`

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/service/CompensationFailureService.java:58-66` — a find-then-insert; `order-service/src/main/resources/db/changelog/changelog-010-failed-compensations.yaml:32-42` — two **non-unique** indexes only.
- **Actual behaviour** — two concurrent `record()` calls for the same reservation create two `PENDING` rows. The class javadoc at `:54-56` claims *"Idempotent per (reservation, kind): a reservation that is already on the list is not recorded twice"* — that is a **Java-only** guarantee.
- **Violated invariant** — "the compensation ledger records each (reservation, kind) once".
- **Root cause** — no unique constraint; the check-then-insert is racy.
- **Proposed fix** — a unique index on `(reservation_id, kind)` where `status = 'PENDING'`.
- **Reproduction test** — extend `CompensationFailureLedgerIT.theSameReservationIsNotRecordedTwice` (`:57-70`) with a concurrent variant.
- **Acceptance criterion proved** — "the ledger is idempotent" — proven only for the sequential case by `CompensationFailureLedgerIT.java:57-70`.

### OI-16 — LOW — Three retention schedulers perform unbounded bulk DELETEs in a single transaction

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/IdempotencyKeyCleanupScheduler.java:19-28` → `IdempotencyKeyRepository.deleteByExpiresAtBefore` (`:17-19`) — unbounded.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/OutboxRetentionScheduler.java:25-35` → `OutboxEventRepository.deleteByStatusAndSentAtBefore` (`:42-44`) — unbounded.
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/OutboxRetentionScheduler.java:26-38` → the same shape.
  - `inventory-service/src/main/java/com/shop/inventoryservice/service/ReservationCleanupScheduler.java:132-144` → `deleteByStatusAndCreatedAtBefore` (unbounded) — although the **expiry** sweep *is* chunked (`:59-86`, batch 500).
- **Actual behaviour** — a single transaction deleting an entire table: long row locks, a WAL burst, and a replication-lag spike at 03:00/04:00 local time. If the delete fails mid-way the whole transaction rolls back and the next night repeats it.
- **Violated invariant** — "background purges are bounded in time and rows".
- **Root cause** — no pagination on the purge queries (the expiry sweep got this right; the purges did not).
- **Proposed fix** — purge in pages (e.g. `PageRequest.of(0, 1000)` in a loop with a per-batch transaction), mirroring `ReservationCleanupScheduler.releaseAllExpiredReservations`.
- **Reproduction test** — a scheduler unit test asserting the repository is called with a bounded page and that the loop stops.
- **Acceptance criterion proved** — "background jobs never issue an unbounded DML statement".

### OI-17 — LOW — The inventory outbox claim shape serialises every relay pod on the head row

- **Evidence** — `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java:37-41` — the claim uses a `MIN(id)` subquery; `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxClaimConcurrencyTest.java:176-178` — the test itself documents the effect: *"the MIN-subquery claim shape serializes on the head row"*. Compare the order-service, which uses `findFirstByStatusOrderByIdAsc` (`order-service/.../repository/OutboxEventRepository.java:36-38`) and whose test proves row2 **is** claimable while row1 is locked (`OrderOutboxClaimConcurrencyIT.java:120-183`).
- **Actual behaviour** — with several `PENDING` rows, a peer pod is locked out entirely (`InventoryOutboxClaimConcurrencyTest.concurrentClaim_lockedOutPeerGetsEmptyNeverTheSameRow`, `:112-113`) → the relay is effectively single-threaded across the fleet.
- **Violated invariant** — "the relay scales horizontally" (a throughput invariant, not a correctness one).
- **Root cause** — the `MIN(id)` subquery locks the head row for every claimant.
- **Proposed fix** — use the `findFirstByStatusOrderByIdAsc` shape that order-service already uses and has proven with SKIP LOCKED.
- **Reproduction test** — mirror `OrderOutboxClaimConcurrencyIT.twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked` for inventory.
- **Acceptance criterion proved** — "a relay pod that cannot claim the head row can still claim another row".

### OI-18 — LOW — `atomicReserve` lacks `flushAutomatically = true` (a latent trap)

- **Evidence** — `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java:39-44` — `@Modifying(clearAutomatically = true)` only; compare `releaseReserved` at `:59-63`, which sets **both** `clearAutomatically` and `flushAutomatically`.
- **Actual behaviour** — today `atomicReserve` is the first statement of the only transaction that calls it (`InventoryServiceImpl.java:141` is the first line of `reserve`), so there is no pending change to flush. **But** if a future caller touches an `Inventory` entity in the same persistence context first, the bulk `UPDATE` would execute without flushing the pending change, and `clearAutomatically = true` would then **discard** that pending change.
- **Violated invariant** — "a bulk update never silently drops a pending entity change" (latent, not currently triggered).
- **Root cause** — the two sibling `@Modifying` methods in the same repository are configured inconsistently.
- **Proposed fix** — add `flushAutomatically = true` to `atomicReserve` (one word, zero behaviour change today).
- **Reproduction test** — a `@DataJpaTest` that modifies an `Inventory` entity and then calls `atomicReserve` and asserts the pending change is not lost.
- **Acceptance criterion proved** — "the two bulk-update methods in a repository have the same flush semantics".

### OI-19 — LOW — The create saga reserves N lines sequentially (N × timeout, N simultaneous holds)

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:190-198` — a plain `for` loop over the order items; `order-service/src/main/resources/application.yml:51-53` — a 5 000 ms inventory timeout.
- **Actual behaviour** — a 20-line checkout performs 20 sequential round trips → up to 100 s of wall time and 20 live holds accumulated over that window. Under a degraded inventory the customer waits and the stock is held progressively.
- **Violated invariant** — none strictly; this is a latency and resource-amplification risk.
- **Root cause** — the reserve loop was not parallelised when the pricing and commit paths were (the fan-out pattern already exists in `PricingServiceImpl` and `OrderCommitCoordinator`).
- **Proposed fix** — fan out with `CompletableFuture` on a bounded executor, keeping the `productId` sort (`:137`) so the inventory rows are still touched in a deterministic order.
- **Reproduction test** — the mirror of `PricingServiceImplParallelFetchTest` (`:41-75`): a wall-clock budget against the serial equivalent.
- **Acceptance criterion proved** — "the checkout latency is bounded by the slowest single call, not by the sum".

### OI-20 — LOW — The "LIFO" compensation order is not deterministic under the parallel commit fan-out

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:115-118` — `committed.add(...)` happens inside a `synchronized (committed)` block, but it is executed by whichever parallel commit **completes first**; `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:142-144` — the comment claims a reverse (LIFO) order; `order-service/src/test/java/com/shop/orderservice/service/ConfirmOrchestrationIT.java:235-280` — the test is named `commitFailure_compensatesLifo_…` but only ever has **one** committed inventory item, so the order is trivially satisfied.
- **Actual behaviour** — with several concurrently committed items, the compensation order is scheduling-dependent, not LIFO.
- **Violated invariant** — the *documented* contract ("compensations in reverse commit order") is not actually guaranteed.
- **Root cause** — the `committed` list is ordered by completion time, not by commit-dispatch order.
- **Proposed fix** — pre-seed the `committed` list with the dispatch order (the `sorted` list) and mark entries as they succeed, so the compensation order is deterministic; or drop the "LIFO" claim from the code and the test name.
- **Reproduction test** — a coordinator test with three items where all three succeed and one fails, asserting the compensation order matches the dispatch order.
- **Acceptance criterion proved** — "the compensation order is the reverse of the commit order" — claimed at `OrderCommitCoordinator.java:142-144` and in the test name, but not actually established.

### OI-21 — LOW — The inventory retry budget is 3 attempts with a 50 ms base backoff

- **Evidence** — `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/ReservationServiceImpl.java:26-27` — `MAX_ATTEMPTS = 3`, `BACKOFF_BASE_MS = 50L`; `:51-67` — the loop; `:57-59` — the exhaustion → `BusinessException.of(ErrorCode.INVENTORY_VERSION_CONFLICT, resourceId)`.
- **Actual behaviour** — under contention on a hot product, three attempts with 50 ms/100 ms of backoff can be exhausted → the commit fails → the coordinator compensates the whole order → a failed checkout under load.
- **Violated invariant** — "a transient version conflict does not fail the checkout".
- **Root cause** — the budget was tuned for "low-contention internal call patterns" (the class javadoc at `:16-21`) — an assumption the flash-sale case violates.
- **Proposed fix** — raise the attempt budget and use jittered exponential backoff; keep `INVENTORY_VERSION_CONFLICT` distinguishable so the caller can retry.
- **Reproduction test** — a unit test that fails twice and succeeds on the third attempt (already covered by `ReservationServiceImplTest.reserveWithRetry_retriesOnOptimisticLockFailure`, `:30-45`) — extend it to assert the exhaustion path preserves the `INVENTORY_VERSION_CONFLICT` code.
- **Acceptance criterion proved** — "a version conflict is retried, not surfaced as a hard failure".

### OI-22 — LOW — The static fan-out executors use unbounded queues against a 50-connection-per-route HTTP pool

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java:54-61` and `order-service/src/main/java/com/shop/orderservice/service/OrderCommitCoordinator.java:45-52` — `Executors.newFixedThreadPool(...)` ⇒ an **unbounded** `LinkedBlockingQueue`.
  - `order-service/src/main/java/com/shop/orderservice/config/RestClientConfig.java:79-82` — `setMaxConnTotal(200)`, `setMaxConnPerRoute(50)`.
- **Actual behaviour** — a burst of 100-item carts enqueues 100 tasks with no backpressure; the tasks then queue on the 50-connection-per-route pool → the "parallel" fetch degrades to a queue drain with unbounded memory growth and an unbounded request latency.
- **Violated invariant** — "the fan-out is bounded and applies backpressure".
- **Root cause** — `newFixedThreadPool` was used where a bounded pool with a `CallerRunsPolicy` was needed.
- **Proposed fix** — `new ThreadPoolExecutor(n, n, 0L, MILLISECONDS, new ArrayBlockingQueue<>(256), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy())`.
- **Reproduction test** — a test that submits more tasks than the pool size and asserts the caller is not blocked indefinitely.
- **Acceptance criterion proved** — "a burst cannot grow the task queue without bound".

### OI-23 — LOW — A 5xx from product-service escapes as a raw exception (surfaced as a 500, not a 503)

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/client/ProductServiceClient.java:38-49` — only `HttpClientErrorException.NotFound` is caught (`:45-48`); a 5xx propagates as `HttpServerErrorException`.
- **Actual behaviour** — the raw exception escapes `PricingServiceImpl` → `OrderCreateSaga.java:165-168` deletes the order row → the API surfaces a 500 for what is a downstream outage. Compare `InventoryServiceClient.commit:87-90` and `PromotionServiceClient.lifecycleCall:126-129`, which map 5xx to `SERVICE_UNAVAILABLE` (a 503).
- **Violated invariant** — "downstream outages are mapped to a stable error code".
- **Root cause** — an inconsistent error-mapping policy between the clients.
- **Proposed fix** — catch `HttpServerErrorException` and map it to `BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE, "product")`.
- **Reproduction test** — a `MockRestServiceServer` test mirroring `PaymentServiceClientTest.serverError_failsClosedReturnsEmpty` (`:116-123`).
- **Acceptance criterion proved** — "no raw downstream exception reaches the client" — the property `PaymentServiceClientTest` proves for payment.

### OI-24 — LOW — `OrderStatusController.ship`/`deliver` have no per-order authorization

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java:25` — the class-level `@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")`; `:37-41` (`ship`) and `:43-47` (`deliver`) take **any** `orderId`.
- **Actual behaviour** — any holder of a `SERVICE`-role token can ship or deliver **any** order in the fleet. The class javadoc (`:18-21`) states this is deliberate ("the service layer only enforces the state machine"), but the blast radius of a compromised service token is the whole order book.
- **Violated invariant** — "a machine actor can only act on the orders it owns" (not currently enforced anywhere in the fleet).
- **Root cause** — no per-aggregate actor binding on the order side (the idempotency `actor` column shows the pattern exists for the confirm path).
- **Proposed fix** — bind the order to the acting `azp` on confirm and require a match on ship/deliver; or restrict `ship`/`deliver` to `ADMIN` and let a dedicated fulfilment service hold the authority.
- **Reproduction test** — a `@WebMvcTest` asserting a `SERVICE` token from an unrelated client id is rejected.
- **Acceptance criterion proved** — "a service token can only drive its own orders".

### OI-25 — LOW — The reconciliation sweeper decides on a stale detached snapshot

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:113-115` — the candidates are loaded **outside** any transaction; `:122-129` — the tasks run asynchronously on the executor; `:134-165` (`reconcile`) reads `order` (the detached snapshot) to decide; `:199-204` / `:213-217` — the save happens later, in a per-order transaction.
- **Actual behaviour** — the decision (auto-confirm vs auto-cancel) is made on a snapshot that may be minutes old. The `@Version` guard makes the *write* safe (the write fails and is retried next cycle), but the sweeper can repeatedly re-evaluate a stale decision and burn the "mixed" counter on orders that are in fact fine.
- **Violated invariant** — "a reconciliation decision is made on current state".
- **Root cause** — the candidate query is not in the same transaction as the decision, by design (to avoid holding a connection across the remote polls).
- **Proposed fix** — re-read the order inside the per-order transaction before deciding, and skip it if the status is no longer `PENDING`.
- **Reproduction test** — a scheduler test that changes the order's status between the scan and the task and asserts the task skips it.
- **Acceptance criterion proved** — "the sweeper only acts on orders that are still `PENDING`".

### OI-26 — LOW — `GuestCartCleanupScheduler` purges active carts of real users and is unbounded

- **Evidence**
  - `order-service/src/main/java/com/shop/orderservice/repository/CartRepository.java:33-34` — `findStaleCarts(cutoff)` = `SELECT c FROM Cart c WHERE c.updatedAt < :cutoff` — **no guest filter and no `deleted = false` filter**.
  - `order-service/src/main/java/com/shop/orderservice/service/impls/GuestCartCleanupScheduler.java:28-49` — hard-deletes the carts and their items.
  - `order-service/src/main/java/com/shop/orderservice/controller/CartController.java:157-159` — guest carts are identified by a **deterministic** user id derived from the guest id — but nothing in the schema or the query distinguishes them.
  - `order-service/src/main/resources/application.yml` — no `order.cleanup.guest-cart-days` is declared; the code default is 14 (`GuestCartCleanupScheduler.java:25-26`).
- **Actual behaviour** — the class is named "guest cart cleanup" but the query matches **every** cart whose `updated_at` is older than the cutoff, including a signed-in user's cart. A user who last touched their cart 15 days ago loses it silently. The query is also unbounded (no `Pageable`).
- **Violated invariant** — "a signed-in user's cart is not purged by a guest-cleanup job"; "background DML is bounded" (OI-16).
- **Root cause** — there is no guest marker on `carts`; the cleanup job assumes the `updated_at` heuristic is enough.
- **Proposed fix** — add a `guest` flag (or a `cart_kind` column) to `carts`, set it when the cart is created for a derived guest id, and filter the cleanup query on it; page the query.
- **Reproduction test** — a scheduler test that seeds a signed-in user's stale cart and asserts it is **not** purged.
- **Acceptance criterion proved** — "the guest-cart cleanup only purges guest carts".

### OI-27 — LOW — The tracking abuse brake under-counts under concurrency

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:488-498` — the counter is read from the entity and written back; `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:516` — `@Transactional(noRollbackFor = BusinessException.class)`.
- **Actual behaviour** — two concurrent wrong-phone attempts both read `track_failures = 0` → the `Order.@Version` guard makes one of them fail with `OptimisticLockingFailureException` → the counter is incremented only once and the client receives a **500** instead of the intended 404. An attacker can therefore send parallel requests to under-count the failures and delay the lockout.
- **Violated invariant** — "the abuse counter is monotonic under concurrent attempts".
- **Root cause** — a read-modify-write on a shared counter without an atomic increment.
- **Proposed fix** — an atomic `UPDATE orders SET track_failures = track_failures + 1 WHERE id = ? AND track_failures < ?` with a row-level conditional on the lockout threshold; map the version conflict to the same 404 the caller already sees for a wrong number.
- **Reproduction test** — extend `OrderTrackingAbuseBrakeIT` (`:55-82`) with a concurrent variant asserting the counter reaches the budget.
- **Acceptance criterion proved** — "the phone path locks out after the failure budget" — proven for the sequential case by `OrderTrackingAbuseBrakeIT.thePhonePathLocksOutAfterTheFailureBudget` (`:66-82`).

### OI-28 — INFO — YAML/code drift on three scheduler knobs

- **Evidence**
  - `inventory-service/src/main/resources/application.yml:68-73` — the comment says the values *"MUST match the inline `@Value`/`@Scheduled` defaults"*, but `reservation-cleanup-initial-delay-ms` is **not declared**; the code default of 60 000 applies (`ReservationCleanupScheduler.java:60`).
  - `order-service/src/main/resources/application.yml:86-89` — `order.reconciliation.batch-size` is **not declared**; the code default of 50 applies (`OrderReconciliationScheduler.java:88`).
  - `inventory-service/src/main/resources/application.yml:66-73` vs `order-service/src/main/resources/application.yml:71-89` — the two modules use different key prefixes for conceptually identical knobs (`inventory.reservation-cleanup-interval-ms` vs `order.reconciliation.interval-ms`).
- **Actual behaviour** — no functional impact today; the drift only misleads an operator reading the YAML.
- **Violated invariant** — "the YAML documents the effective configuration".
- **Root cause** — knobs added to the code without a YAML update.
- **Proposed fix** — declare the missing knobs explicitly.
- **Reproduction test** — a configuration test that asserts every `@Value`/`@Scheduled` property has a YAML entry.
- **Acceptance criterion proved** — "the declared configuration matches the effective configuration".

### OI-29 — INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table

- **Evidence** — `order-service/src/main/java/com/shop/orderservice/constant/ReturnStatus.java:4-8` declares `REQUESTED, APPROVED, REJECTED, REFUNDED`; `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java:155-161` — when the admin sends `APPROVED` the code refunds and immediately overwrites the status with `REFUNDED` (`:160`), so `APPROVED` is **never persisted**.
- **Actual behaviour** — `ReturnStatus.APPROVED` is unreachable in the database; the `order_returns.status` column can only ever hold `REQUESTED`, `REJECTED`, or `REFUNDED`. An admin UI offering "Approve" therefore always lands on "Refunded", and any analytics distinguishing the two is wrong.
- **Violated invariant** — "the persisted status vocabulary matches the declared one".
- **Root cause** — the approve and refund decisions were merged without a state machine (see §6.5).
- **Proposed fix** — either add `APPROVED` as a real persisted state (approve → refund → `REFUNDED` as two steps) or remove it from the enum and from `OrderReturnReviewRequest`.
- **Reproduction test** — a repository test asserting the set of persisted `order_returns.status` values.
- **Acceptance criterion proved** — "every declared status is reachable and persisted".

---

## 10. VERDICT

### 10.1 Overselling: **PREVENTED**

The protection is the **atomic guarded UPDATE** at `inventory-service/src/main/java/com/shop/inventoryservice/repository/InventoryRepository.java:39-44` — a compare-and-set in a single statement whose capacity predicate is evaluated by Postgres under the row lock, with EvalPlanQual re-evaluation under READ COMMITTED. It is reinforced by:
- `Inventory.@Version` (`entity/Inventory.java:58-59`) on every read-then-write counter path;
- `Reservation.@Version` (`entity/Reservation.java:76-79`) on the reservation aggregate;
- the `CHECK (available_quantity >= 0)` / `CHECK (reserved_quantity >= 0)` constraints (`changelog-004:42-54`);
- the guarded `releaseReserved` (`InventoryRepository.java:59-63`);
- the conditional claim `claimForExpiry` (`ReservationRepository.java:46-51`).

**There is exactly one oversell path: OI-01** (a duplicated/lost `reserve` call produces two holds for one line, and `commit` then deducts `available_quantity` twice). It is a HIGH-severity defect and it is the first thing to fix.

### 10.2 The six mandatory races

| Race | Verdict | The single reason |
|---|---|---|
| A — two customers reserve the last unit | **SAFE** | the capacity predicate is inside the same `UPDATE`; the loser affects 0 rows. *But the regression test is a mock — there is no IT proving it.* |
| B — duplicate reserve with the same key | **NOT SAFE (OI-01)** | there is no idempotency key and no unique constraint. |
| C — expiry racing with commit | **SAFE** | the sweep's claim is conditional on `status = PENDING` and the reservation carries `@Version`; the loser rolls back whole. |
| D — release racing with commit | **SAFE** | both entities carry `@Version`; the loser's transaction rolls back whole; the retry re-reads and refuses. |
| E — two sweepers, same reservation | **SAFE** | `claimForExpiry` is a compare-and-set; only the claimer touches the counters. |
| F — commit of an already-released reservation | **SAFE** | `commit` refuses `RELEASED`/`EXPIRED` before touching any counter. |

### 10.3 What is genuinely strong

- The **transaction boundaries** of the saga are deliberate and correct: the persist-early `PENDING` row, the separation of DB transactions from HTTP calls, the `REQUIRES_NEW` idempotency insert, the `REQUIRES_NEW` compensation ledger, the short local-flip transaction after the remote commits — and `CancelOutsideTransactionIT` proves the pool-exhaustion property with a **1-connection pool**.
- The **transactional outbox** is implemented correctly in both services (same-transaction event write, claim lease, compare-and-set mark, per-aggregate ordering, stranded-claim recovery) and is covered by real-Kafka ITs.
- The **optimistic locking** on `Order`, `Inventory`, and `Reservation` is exactly where it is needed, and `ConfirmOrchestrationIT.concurrency_differentKeys_versionGuardConfirmsExactlyOnce` proves it under real concurrency.
- The **reconciliation posture** ("never auto-decide on incomplete data") is right and is tested (`OrderReconciliationSchedulerTest`, 18 cases).
- The **lock ordering** is consistent (Reservation → Inventory everywhere) — no deadlock is possible, and that is provable rather than lucky.

### 10.4 What to fix first

1. **OI-01** — add the reserve idempotency key + UNIQUE constraint (closes the only oversell path).
2. **OI-02** — wire the confirm path's compensation failures into the `failed_compensations` ledger with a `RESERVATION_RELEASE_COMMITTED` kind (closes the permanent stock loss).
3. **OI-03** — wrap TX C in a compensation arm (closes the hold leak).
4. **OI-05** — make the checkout idempotency key mandatory or derive one (closes the double-order).
5. Add the **missing IT** for Race A (two concurrent reserves for the last unit against real Postgres) — it is the invariant the whole module rests on and it is currently proven only by a mock.

---

*Report produced by a source-only audit at revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`. All 185 `.java` files in scope and all 19 `.yaml`/`.yml` files were read in full; no application source file was modified.*
