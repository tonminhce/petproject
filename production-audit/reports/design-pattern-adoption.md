# Design Pattern Adoption Audit

**Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Revision audited:** `59ae0c05092a8b95ad2f02d6fd6c647e38240d5a` (working tree at the time of writing; `shipping-service/**`, `rating-service/**` and `utils/**` were being edited concurrently by other agents, so line numbers in those three trees are the ones observed at read time)
**Method:** every claim below was produced by reading the first-party source. The audit used a behavioural-method census over every `@Entity`, a class census for each pattern, and a direct read of every cited file. **No Maven build was run** and no runtime behaviour was observed; statements about runtime semantics are either derived from the source with the reasoning shown, or explicitly marked as unverified.

---

## 1. Module inventory

The reactor contains **22** Maven modules, not 23:

| Level | Count | Modules |
| --- | --- | --- |
| Root reactor (`pom.xml:11-25`) | 15 | `utils`, `auth-service`, `product-service`, `order-service`, `payment-service`, `inventory-service`, `shipping-service`, `notification-service`, `rating-service`, `search-service`, `promotion-service`, `tax-service`, `favourite-service`, `media-service`, `gateway-service` |
| `utils/` children (`utils/pom.xml:8-14`) | 7 | `common-core`, `common-security`, `common-logging`, `common-keycloak`, `common-kafka`, `common-spring`, `common-storage` |

A **23rd module directory** exists — `utils/common-patterns/` — but it is **not part of the reactor and contains no source**:

- `utils/common-patterns/pom.xml` does not exist (verified by `test -f` → `NO`).
- `utils/pom.xml:8-14` does not list it.
- `utils/common-patterns/src/main/java/com/shop/common/patterns/registry/` exists but is an **empty directory** (verified with `ls -la`: `total 0`, two entries, both `.` and `..`).
- Only stale build output remains: `utils/common-patterns/target/test-classes/com/shop/common/patterns/FleetPatternComplianceTest.class`.

This is finding **DP-01** (§6.1).

---

## 2. Bounded contexts

The fleet is a **service-per-context** decomposition. The contexts map onto the modules as follows. Every row is anchored to the entity class or the absence of one.

| # | Bounded context | Module(s) | Aggregate roots owned | Evidence |
| --- | --- | --- | --- | --- |
| 1 | **Catalogue** | `product-service` | `Product` (+`ProductVariant`, `Brand`, `Category`) | `product-service/src/main/java/com/shop/productservice/entity/Product.java:12-20`, `.../entity/ProductVariant.java`, `.../entity/Brand.java`, `.../entity/Category.java` |
| 2 | **Catalogue projection / Query** | `search-service` | *none* — a read model over Elasticsearch | `search-service/src/main/java/com/shop/searchservice/service/ProductSearchService.java:22-30` (no `@Entity` anywhere under `search-service/src/main`) |
| 3 | **Asset management** | `media-service` | `Media` (+`MediaVariant`) | `media-service/src/main/java/com/shop/mediaservice/entity/Media.java`, `.../entity/MediaVariant.java` |
| 4 | **Ordering** | `order-service` | `Order` (+`OrderItem`), `Cart` (+`CartItem`), `OrderReturn` | `order-service/src/main/java/com/shop/orderservice/entity/Order.java:13-17`, `.../entity/Cart.java`, `.../entity/OrderItem.java`, `.../entity/OrderReturn.java` |
| 5 | **Inventory / Stock** | `inventory-service` | `Inventory`, `Reservation` | `inventory-service/src/main/java/com/shop/inventoryservice/entity/Inventory.java:19-26`, `.../entity/Reservation.java:22-29` |
| 6 | **Fulfilment / Shipping** | `shipping-service` | `Shipment`, `ShipmentEvent` | `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java`, `.../entity/ShipmentEvent.java` |
| 7 | **Notification** | `notification-service` | `Notification` | `notification-service/src/main/java/com/shop/notificationservice/entity/Notification.java` |
| 8 | **Payments** | `payment-service` | `Payment`, `PaymentEvent` | `payment-service/src/main/java/com/shop/paymentservice/entity/Payment.java:13-17`, `.../entity/PaymentEvent.java` |
| 9 | **Commerce promotions** | `promotion-service` | `Campaign` (+`CampaignUserUsage`, `CouponUsageReservation`) | `promotion-service/src/main/java/com/shop/promotionservice/entity/Campaign.java:13-17`, `.../entity/CouponUsageReservation.java`, `.../entity/CampaignUserUsage.java` |
| 10 | **Taxation** | `tax-service` | `TaxClass`, `TaxRate` | `tax-service/src/main/java/com/shop/taxservice/entity/TaxClass.java`, `.../entity/TaxRate.java` |
| 11 | **Identity & Access** | `auth-service` | `User`, `Role`, `UserAddress`, `PasswordResetToken` | `auth-service/src/main/java/com/shop/authservice/entity/User.java`, `.../entity/Role.java`, `.../entity/UserAddress.java`, `.../entity/PasswordResetToken.java` |
| 12 | **Reputation** | `rating-service` | `Rating` | `rating-service/src/main/java/com/shop/ratingservice/entity/Rating.java` |
| 13 | **Wishlist** | `favourite-service` | `Favourite` | `favourite-service/src/main/java/com/shop/favouriteservice/entity/Favourite.java` |
| 14 | **Edge / API gateway** | `gateway-service` | *none* — a routing and rate-limiting shell | no `@Entity` under `gateway-service/src/main` |
| 15 | **Shared kernel / platform** | `utils/common-*` (7 modules) | *none* — technical building blocks | `utils/common-core/src/main/java/com/shop/common/core/data/AbstractMappedEntity.java:14-16` (`@MappedSuperclass`), `.../core/exception/ErrorCode.java`, `.../core/viewmodel/ApiResponse.java`, `.../core/constants/ApiPaths.java` |

### 2.1 Context relationships

| Upstream | Downstream | Relationship | Mechanism | Evidence |
| --- | --- | --- | --- | --- |
| Catalogue | Catalogue projection | **Customer/Supplier** with a published event contract | Kafka topic `shop.product.lifecycle.v1` | `utils/common-kafka/src/main/java/com/shop/common/kafka/contract/KafkaTopicContract.java:39` (topic constant), `:55-56` (`new Topic(SHOP_PRODUCT_LIFECYCLE, "product-service", Mode.CONSUMED, …)`) |
| Catalogue | Asset management | **Customer/Supplier** | `media.lifecycle.v1` | `KafkaTopicContract.java:42` |
| Ordering | Fulfilment | **Customer/Supplier** | `shop.order.lifecycle.v1` | `KafkaTopicContract.java:37`, `:51-52` |
| Ordering | Notification | **Customer/Supplier** | `shop.order.lifecycle.v1` | `KafkaTopicContract.java:51-52` |
| Fulfilment | Ordering | **Customer/Supplier** | `shop.shipping.lifecycle.v1` | `KafkaTopicContract.java:41`, `:59-60` |
| Payments | Ordering | **Customer/Supplier** | `shop.payment.lifecycle.v1` | `KafkaTopicContract.java:38`, `:53-54` |
| Commerce promotions | Ordering | **Supplier/ACL** (sync HTTP) | `PromotionServiceClient` | `order-service/src/main/java/com/shop/orderservice/client/PromotionServiceClient.java` (referenced at `OrderCreateSaga.java:51`, `:193`) |
| Inventory / Stock | Ordering | **Supplier/ACL** (sync HTTP) | `InventoryServiceClient` | `order-service/src/main/java/com/shop/orderservice/client/InventoryServiceClient.java` (referenced at `OrderCreateSaga.java:183`) |
| Ordering | Payments | **Supplier/ACL** (sync HTTP) | `PaymentServiceClient` | `order-service/src/main/java/com/shop/orderservice/client/PaymentServiceClient.java` (injected at `OrderServiceImpl.java:68`) |
| Ordering | Catalogue / Tax | **Supplier/ACL** (sync HTTP) | `ProductServiceClient`, `TaxServiceClient` | `order-service/src/main/java/com/shop/orderservice/service/impls/PricingServiceImpl.java:35-37` (fields), `:64`, `:112` (calls) |
| Reputation | Catalogue | **Customer/Supplier** | `shop.rating.lifecycle.v1` | `KafkaTopicContract.java:40`, `:57-58` |

### 2.2 Aggregate-to-context boundary leaks

The contexts are not uniformly closed. Three leaks are visible in the source:

1. **Ordering reads fulfilment state directly.** `OrderReconciliationScheduler.pollReservationStates` (`order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:131-147`) reaches into `inventoryClient.getReservationState(...)` (`:139`) and `promotionClient.getReservationState(...)` (`:135`) and then **decides the order's fate** (`:118-124`). The Ordering context owns the decision but not the facts it decides on — the classic anemic-context smell of a duplicated read model.
2. **Identity is not the owner of the platform user.** `auth-service` owns `User`, but the rest of the fleet resolves users through JWT claims. `docs/PATTERNS.md:397-411` (rule **R17**) documents the two-identifier-space defect this produced.
3. **The shared kernel carries domain-adjacent policy.** `utils/common-core/src/main/java/com/shop/common/core/constants/PageableConstant.java` and `.../core/exception/ErrorCode.java` are technical, but the error catalogue is a *fleet-wide* enum spanning every context (`ErrorCode` contains `ORDER_*`, `PAY_*`, `INV_*`, `SHIP_*`, `RATING_*`, `PROMOTION_*` …), so a change in one context touches a shared file. Evidence: `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:107` uses `ErrorCode.PAYMENT_DUPLICATE_REQUEST`; `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:144` uses `ErrorCode.TRACKING_REQUIRED` — both from the same enum.

---

## 3. Rich vs. anemic domain model

### 3.1 Behavioural-method census

Every `@Entity` class under every module was scanned for method declarations that are not Lombok-generated accessors, `equals`/`hashCode`/`toString`, or `builder`. The complete result:

```
### promotion-service/src/main/java/com/shop/promotionservice/entity/Campaign.java
    public void activate() {
    public void deactivate() {
```

**One entity in the whole fleet has behaviour.** `Campaign.activate()` / `deactivate()` are at `promotion-service/src/main/java/com/shop/promotionservice/entity/Campaign.java:72-78`.

Everything else is a Lombok data carrier. For example `Order` (`order-service/.../entity/Order.java:13-17`) is annotated `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` and holds only fields; `Shipment` (`shipping-service/.../entity/Shipment.java`), `Payment` (`payment-service/.../entity/Payment.java:16`), `Inventory` (`inventory-service/.../entity/Inventory.java:21-25`), `Rating` (`rating-service/.../entity/Rating.java`), `Product` (`product-service/.../entity/Product.java:20`) and `Reservation` (`inventory-service/.../entity/Reservation.java:24-28`) are the same shape.

### 3.2 Verdict per aggregate

| Aggregate | Root file | Behaviour on the entity | Where the rules actually live | Verdict |
| --- | --- | --- | --- | --- |
| **Order** | `order-service/.../entity/Order.java` | none | `order-service/.../service/impls/OrderStatusServiceImpl.java:16-22` (transition table), `.../service/impls/OrderServiceImpl.java:273-339` (confirm), `.../service/impls/OrderCreateSaga.java:120-221` (create) | **Anemic.** The invariant "an order may only move PENDING→CONFIRMED→SHIPPED→DELIVERED" is enforced by a separate `@Service` that receives the *entity* as a parameter (`OrderStatusServiceImpl.validateTransition(OrderStatus from, OrderStatus to)`, `:25-29`), so the entity cannot protect itself. |
| **Inventory / Reservation** | `inventory-service/.../entity/Inventory.java`, `.../entity/Reservation.java` | none | `inventory-service/.../service/impls/InventoryServiceImpl.java:132-195` (reserve), `:197-232` (commit), `:234-265` (release) | **Anemic, but with an atomic data model.** The critical rule "reserved + available ≤ stock" is expressed as a *conditional SQL UPDATE* (`inventory-service/.../repository/InventoryRepository.java:18-23`), not as domain code. This is a deliberate and defensible trade-off, but it means the invariant is not expressible or testable without a database. |
| **Campaign** | `promotion-service/.../entity/Campaign.java` | `activate()`, `deactivate()` (`:72-78`) | `promotion-service/.../service/impls/CampaignReservationServiceImpl.java:44-105` | **The only rich aggregate in the fleet** — and even here the interesting rules (per-user quota, budget) are conditional SQL updates (`.../repository/CampaignRepository.java:26-31`, `.../repository/CampaignUserUsageRepository.java:13-21`), not entity methods. |
| **Payment** | `payment-service/.../entity/Payment.java` | none | `payment-service/.../service/PaymentStateMachine.java:21-32`, `.../service/impls/PaymentServiceImpl.java:121-137` (capture), `:158-174` (refund) | **Anemic.** The state machine is a static utility, not the aggregate. `PaymentStateMachine.transition` takes two enum values, not the aggregate. |
| **Shipment** | `shipping-service/.../entity/Shipment.java` | none | `shipping-service/.../service/ShipmentStateMachine.java:29-34`, `.../service/impls/ShipmentServiceImpl.java:180-195` | **Anemic.** The FSM is a `static final Set<Map.Entry<…>>` in a separate class (`ShipmentStateMachine.java:12-24`). |
| **Rating** | `rating-service/.../entity/Rating.java` | none | `rating-service/.../service/impls/RatingServiceImpl.java:36-63` | **Anemic.** |

**Aggregate conclusion.** The fleet implements a **transaction-script** model with a thin anemic data layer. This is a legitimate architectural choice and it is internally consistent, but it is *not* DDD in the aggregate sense: there is no aggregate root with invariants, no domain event, no repository abstraction over the aggregate boundary, and (except for `Campaign`) no behaviour on the entities. Every invariant is enforced by a service method, a static helper, or a database constraint.

### 3.3 Where a domain model is missing and it costs correctness

Two of the concurrency defects in `concurrency.md` are direct consequences of the anemic model:

- **`ShipmentServiceImpl.createShipment`** (`shipping-service/.../service/impls/ShipmentServiceImpl.java:78-102`) performs the carrier side effect (`:92`) before the guard (`:98`). A rich `Shipment.create(adapter, orderId)` that owns the ordering could not express this mistake.
- **`RatingEventService.record`** (`rating-service/.../service/RatingEventService.java:40-78`) computes the aggregate snapshot (`avgRating`, `ratingCount`) from an unlocked aggregate query (`:42`) and embeds it in the event (`:65-66`) — a rule that belongs on the `Rating` aggregate.

---

## 4. SOLID

Each principle below lists at least one concrete violating class with a `file:line` anchor and a severity. Severity is this audit's own judgement, not a fleet-standard value.

### 4.1 SRP — Single Responsibility — **Violated**

| ID | Class and anchor | Why it violates SRP | Severity |
| --- | --- | --- | --- |
| SOLID-SRP-01 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:59-482` | 482 lines, **17 injected collaborators** (`:65-81`), and it implements order creation (`:147`), cancellation plus two compensation strategies (`:178-271`), confirmation plus the commit fan-out (`:273-339`), shipping/delivery transitions (`:340-370`), three read models (`:371-389`) and return handling. It also owns request hashing (`:160-176`) and metric emission (`OrderConfirmMetrics`, `:76`). Changing the return policy, the confirmation policy or the read projection each edit the same class. | **HIGH** |
| SOLID-SRP-02 | `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:31-196` | One class carries: order-event dispatch (`:69-76`), shipment creation *and* carrier selection (`:78-102`), cancellation (`:104-115`), three read projections (`:118-134`), manual tracking assignment (`:142-155`), the FSM transition (`:158-173`) and metric emission (`:185-191`). Carrier selection alone (`CarrierFactory` resolution at `:84-86`) is a separate reason to change. | **MEDIUM** |
| SOLID-SRP-03 | `payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java:36-365` | Handles Stripe signature verification and event-type mapping (`:60-84`), the carrier webhook protocol and dedupe (`:86-111`), retry re-execution (`:113-127`), full and partial refund application (`:148-202`), payload serialization (`:204-238`), event persistence (`:248-264`) and SHA-256 helper functions (`:353-359`). Two protocols, two refund semantics, two persistence concerns. | **HIGH** |
| SOLID-SRP-04 | `inventory-service/src/main/java/com/shop/inventoryservice/service/impls/InventoryServiceImpl.java:31-300` | Reserve (`:132-195`), commit (`:197-232`), release (`:234-265`), release-committed (`:267-290`) and deletion (`:116-128`) are five distinct reservation-lifecycle responsibilities behind one interface, and the delete path additionally calls `publisher.publishDeleted` (`:126`). | **MEDIUM** |

### 4.2 OCP — Open/Closed — **Violated**

| ID | Class and anchor | Why it violates OCP | Severity |
| --- | --- | --- | --- |
| SOLID-OCP-01 | `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierFactory.java:41-47` | `getCarrier(String)` returns `defaultCarrier` for any unknown name (`:46`). A new carrier therefore does **not** require editing the factory (good), but a *typo* silently degrades to the default carrier instead of failing. The closed half of the contract is broken in the wrong direction: the set of accepted names is open, the set of rejected names is empty. | **MEDIUM** |
| SOLID-OCP-02 | `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierConfig.java:15-22` | The primary adapter is resolved with `orElse(all.get(0))` (`:21`). "The first adapter" is a function of Spring's injection order, not of a declared contract — adding a carrier changes which adapter is "first" for every deployment that does not set `shop.shipping.carrier`. | **MEDIUM** |
| SOLID-OCP-03 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:14-16` | The interface is extended by adding a `default` method that *silently drops* a parameter: `refund(paymentId, amount, idempotencyKey, providerIntentId)` delegates to the 3-argument overload (`:15`). Any provider that does not override it receives a refund with a `null` intent id and cannot be told apart from one that does. This is an open interface whose default extension is not safe for all implementors — the classic OCP trap of an interface that grows a behaviour nobody opted into. | **HIGH** |
| SOLID-OCP-04 | `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentStateMachine.java:12-24` | The legal-transition table is a `static final Set` — adding a status means editing the class. By contrast `PaymentStateMachine` (`payment-service/.../service/PaymentStateMachine.java:12-16`) uses a `Map<Status, Set<Status>>`, which is only marginally better. Neither is data-driven, so a new carrier status requires a code change in the FSM rather than configuration. | **LOW** |

### 4.3 LSP — Liskov Substitution — **Violated**

| ID | Class and anchor | Why it violates LSP | Severity |
| --- | --- | --- | --- |
| SOLID-LSP-01 | `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierAdapter.java:17-20` | The interface returns a `ShipmentDraft` whose `initialStatus` differs per implementation with observable consequences: `GhnCarrierAdapter.createShipment` returns `ShipmentStatus.CREATED` (`shipping-service/.../carrier/GhnCarrierAdapter.java:58`), `ManualCarrierAdapter` returns `CREATED` (`:21` of that file), but `NoopCarrierAdapter` returns `ShipmentStatus.PICKED_UP` (`shipping-service/.../carrier/NoopCarrierAdapter.java:21`). The caller stores it verbatim (`ShipmentServiceImpl.java:95`), so the shipment's initial state depends on the configured adapter. Worse, `ShipmentServiceImpl.assignTracking` requires `status == CREATED && carrier == MANUAL` (`:147`), so under the NOOP adapter a shipment can **never** be assigned a tracking number. A substitutable carrier must not change the state machine's reachable set. | **HIGH** |
| SOLID-LSP-02 | `payment-service/src/main/java/com/shop/paymentservice/provider/MockProvider.java:18-26` | `capture` returns `ProviderResult.confirmed(...)` unconditionally (`:20`) and `refund` likewise (`:25`). A `PaymentProvider` substitutable for a real PSP must be able to return `REJECTED` or `INDETERMINATE`; the mock cannot. This is precisely the defect the audit's own PAY-01 finding describes — the mock is not substitutable, so any test that swaps it in asserts a behaviour no real provider has. It is currently gated by `@ConditionalOnProperty(name = "shop.payment.provider", havingValue = "mock")` (`:10`), which contains the damage but does not fix the contract. | **HIGH** |
| SOLID-LSP-03 | `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierAdapter.java:12-15` | `default String carrierCode()` returns the literal `"MANUAL"` when `carrier()` returns `null` (`:14`). A caller relying on `carrierCode()` cannot distinguish "this adapter has no carrier" from "this adapter is the manual carrier" — a subtype weakens the base type's contract. | **LOW** |

### 4.4 ISP — Interface Segregation — **Violated**

| ID | Class and anchor | Why it violates ISP | Severity |
| --- | --- | --- | --- |
| SOLID-ISP-01 | `utils/common-storage/src/main/java/com/shop/common/storage/service/ObjectStorageService.java:7-37` | One interface exposes **13** methods spanning five capabilities: bucket administration (`defaultBucket`, `ensureBucketExists`), write (`upload` ×2), read (`download` ×2, `openStream`), existence (`exists`), delete (`delete` ×2) and URL minting (`presignedGetUrl` ×2, `presignedPutUrl`). Every consumer — the media uploader, the search indexer, the presigned-URL controller — depends on all of it. There is **one** implementation (`utils/common-storage/.../service/S3ObjectStorageService.java`), so the interface cannot be segmented without changing the implementation, and no implementation throws `UnsupportedOperationException` (verified by a repo-wide grep: zero hits under any `src/main` tree). | **MEDIUM** |
| SOLID-ISP-02 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:6-16` | `PaymentProvider` mixes the *capture* role and the *refund* role behind one type. A provider that can only capture (or only refund) must still implement both. The fleet currently avoids this by implementing both in every class, but the interface offers no segment. | **LOW** |
| SOLID-ISP-03 | `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:14-16` (same anchor as SOLID-OCP-03) | The 4-argument `refund` default throws away `providerIntentId`. A caller that depends on the 4-argument overload is coupled to a contract that not every implementor honours — the ISP cost of widening one interface instead of declaring a second, narrower one. | **MEDIUM** |

### 4.5 DIP — Dependency Inversion — **Violated**

| ID | Class and anchor | Why it violates DIP | Severity |
| --- | --- | --- | --- |
| SOLID-DIP-01 | `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:41` + `:84-86` | The service depends on the **concrete** `CarrierFactory` class, not on an abstraction, and it is optional (`@Autowired(required = false)` at `:50`, with `carrierFactory` possibly `null` at `:56`). The abstraction that exists — `CarrierAdapter` — is bypassed for the *selection* concern. The `null` branch at `:84-86` is a second, silent carrier-selection strategy. | **HIGH** |
| SOLID-DIP-02 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:51` (field) and `:66` (constructor parameter) | The saga depends on the **concrete** `PromotionServiceClient` class — an HTTP client — rather than on a port. `PricingService` (`:49`) *is* an interface, so the abstraction exists where it is not needed and is absent where it is. The saga is bound to the HTTP transport, with no seam for a stub, a retry policy, or an offline implementation. | **MEDIUM** |
| SOLID-DIP-03 | `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:70` and `:83` | `PlatformTransactionManager` is injected **optionally** (`@Autowired(required = false)`) and the class carries a fallback path (`executeInTx` at `:102-107` runs the action with **no transaction at all** when the manager is absent). The unit-of-work dependency is inverted into an optional runtime branch: the caller's transactionality depends on whether a bean happens to exist. | **HIGH** |
| SOLID-DIP-04 | `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:35` and `:62-70` | `PaymentServiceImpl` depends on the concrete `PaymentProviderFactory` and falls back to comparing `defaultProvider.name()` with the requested name (`:65-70`) when the factory is absent — a second, incompatible provider-resolution strategy in the same method. The abstract `PaymentProvider` port exists; the *resolution* port does not. | **MEDIUM** |

### 4.6 SOLID summary

| Principle | Verdict | Worst finding | Severity |
| --- | --- | --- | --- |
| SRP | Violated | `OrderServiceImpl` (482 lines, 17 collaborators) | HIGH |
| OCP | Violated | `PaymentProvider.refund` default drops `providerIntentId` | HIGH |
| LSP | Violated | `MockProvider` cannot represent a rejected/indeterminate outcome | HIGH |
| ISP | Violated | `ObjectStorageService` — 13 methods, 5 capabilities, 1 implementation | MEDIUM |
| DIP | Violated | `OrderCreateSaga` runs with **no transaction** when the tx manager bean is absent | HIGH |

---

## 5. Pattern inventory

Status vocabulary: **Real** — implemented and structurally correct; **Partial** — implemented but with a material gap; **Decorative** — the shape exists but the mechanism does not; **Missing** — not present.

| # | Pattern | Status | Evidence (file:line) | Verdict |
| --- | --- | --- | --- | --- |
| 1 | **Transactional Outbox** | **Real** | 8 relay implementations: `inventory-service/.../service/InventoryOutboxRelay.java:58-124`, `product-service/.../service/OutboxRelay.java:58-122`, `order-service/.../service/impls/OrderOutboxRelay.java:59-126`, `payment-service/.../outbox/PaymentOutboxRelay.java:51-97`, `promotion-service/.../service/PromotionOutboxRelay.java:59-126`, `rating-service/.../outbox/RatingOutboxRelay.java:52-110`, `shipping-service/.../outbox/ShippingOutboxRelay.java:55-120`, `media-service/.../outbox/MediaOutboxRelay.java:54-113`. Every claim uses `PESSIMISTIC_WRITE` + `lock.timeout = -2` (SKIP LOCKED), e.g. `shipping-service/.../outbox/OutboxEventRepository.java:22-26`. Every relay has a stranded-claim recovery sweep, e.g. `ShippingOutboxRelay.recoverStrandedClaims` (`:122-132`). | **Correctly implemented.** The write and the outbox row are committed in one transaction (`ShipmentWriter.saveDelivered`, `shipping-service/.../service/ShipmentWriter.java:27-32`). One deviation: `docs/PATTERNS.md:269-276` (**R12**) requires the publish to run *inside* the claim transaction; all 8 relays publish **after** the claim transaction commits (`ShippingOutboxRelay.java:65-95`) and compensate with a lease instead. That is a legitimate at-least-once design, but it is a deviation from R12 and it opens a duplicate-publish window (see `concurrency.md` CONC-10). |
| 2 | **Idempotent Consumer** | **Partial** | Per-consumer guards exist and are real: shipping's `uk_shipment_events_carrier_event` (`shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml:77`), payment's `uk_payment_events_provider_event` (`payment-service/src/main/resources/db/changelog/changelog-001-payments.yaml:73`), notification's `notifications.event_id UNIQUE` (`notification-service/.../entity/Notification.java:38`), media's `uk_medias_sha256` (`media-service/src/main/resources/db/changelog/changelog-001-medias.yaml:32`), rating's `uk_rating_user_product_live` (`rating-service/src/main/resources/db/changelog/changelog-001-ratings.yaml:42`), inventory's `uk_reservations_idempotency_key` (`inventory-service/src/main/resources/db/changelog/changelog-007-reservations-idempotency-key.yaml:12-15`). | **No shared framework.** There is no `IdempotentConsumer` base class, no `@Idempotent` annotation and no common claim/guard helper in `utils/common-kafka`. Each service re-implements the guard differently. Two concrete gaps: (a) the inventory keyless reserve path is still live because the caller sends no key (`order-service/.../client/InventoryServiceClient.java` — per `inventory-idempotency-fix.md:157-158`), and (b) `product-service`'s media listener factory has **no error handler and therefore no DLT** (`product-service/.../kafka/MediaLifecycleListenerConfig.java:18-28` — it never calls `setCommonErrorHandler`, unlike the shared base at `utils/common-kafka/.../consumer/BaseKafkaListenerConfig.java:58`). |
| 3 | **Saga / compensation** | **Partial** | A real create-saga with a compensation ledger: `order-service/.../service/impls/OrderCreateSaga.java:120-238`; a real confirm-saga with parallel fan-out and reverse-order compensation: `order-service/.../service/OrderCommitCoordinator.java:46-142`; a durable compensation ledger with a **lease-based claim**: `order-service/.../service/CompensationFailureService.java:104-140` + `order-service/.../repository/FailedCompensationRepository.java:27-35` (`UPDATE … WHERE f.id = :id AND f.status IN :statuses AND (f.nextAttemptAt IS NULL OR f.nextAttemptAt <= :now)`) and a DB-level uniqueness guard `uc_failed_compensations_open` (`order-service/src/main/resources/db/changelog/changelog-012-failed-compensation-sweeper.yaml:16-20`). | **Real but incomplete.** The compensation *ledger* is strong; the *orchestration* is not. The create saga has no persistent state machine: if the process dies between the reservation and the final commit, nothing drives it forward — only `OrderReconciliationScheduler` (`:80-102`) polls for stuck `PENDING` orders, and that scheduler itself has no claim (see `concurrency.md` CONC-06) and bypasses the order state machine. There is no orchestrated-compensation choreography across the payment boundary: `OrderCommitCoordinator` compensates inventory and promotion (`:130-142`) but a capture that races a cancellation is not compensated anywhere (documented as PAY-06 in `production-audit/reports/findings.md:27`). |
| 4 | **State Machine** | **Real (with one defect)** | Three FSMs: `shipping-service/.../service/ShipmentStateMachine.java:12-34`, `payment-service/.../service/PaymentStateMachine.java:12-32`, `order-service/.../service/impls/OrderStatusServiceImpl.java:16-29`. | **Structurally real.** Defect: `PaymentStateMachine.transition` returns `to` when `from == to` (`payment-service/.../service/PaymentStateMachine.java:25-27`), so a re-processed webhook can drive `CAPTURED → CAPTURED` and publish a second `payment.captured.v1` through `WebhookEventService.process` (`payment-service/.../service/WebhookEventService.java:167-179`). `ShipmentStateMachine` has no self-transition, but it *does* contain the backward edge `DELIVERY_FAILED → IN_TRANSIT` (`ShipmentStateMachine.java:20`), which is what makes a late carrier event roll a failed delivery back. |
| 5 | **Strategy** | **Real** | Port + 4 implementations: `shipping-service/.../carrier/CarrierAdapter.java:8-21` with `GhnCarrierAdapter`, `GhtkCarrierAdapter`, `ManualCarrierAdapter`, `NoopCarrierAdapter`. Payment port + 5 implementations: `payment-service/.../provider/PaymentProvider.java:6-16` with `StripeProvider`, `MoMoProvider`, `VNPayProvider`, `CodProvider`, `MockProvider`. | **Real and used.** Selection is by Spring injection (`CarrierConfig.primary`, `:15-22`) or by registry lookup (`CarrierFactory.getCarrier`, `:34-47`). The weakness is in substitutability, not in the pattern's existence — see SOLID-LSP-01 and SOLID-LSP-02. |
| 6 | **Factory** | **Real (one silent-substitution defect)** | `shipping-service/.../carrier/CarrierFactory.java:11-56`; `payment-service/.../provider/PaymentProviderFactory.java:12`; `utils/common-storage/.../client/S3ClientFactory.java:13`. | **Real.** `CarrierFactory.getCarrier(String)` returns the default carrier for an unknown name (`CarrierFactory.java:46`) — the exact defect `docs/PATTERNS.md:336-352` (**R14**) forbids for payments and which the payment factory *does* implement correctly. The two factories are inconsistent. |
| 7 | **Adapter** | **Real** | `CarrierAdapter` (port) → `GhnCarrierAdapter` / `GhtkCarrierAdapter` wrap the third-party carrier HTTP API through `shipping-service/.../carrier/CarrierShipmentClient.java:26-43`. `utils/common-storage/.../service/ObjectStorageService.java:7-37` adapts S3/RustFS to a storage port with a single implementation `S3ObjectStorageService`. | **Real.** One weakness: `CarrierShipmentClient.createShipment` maps *every* exception to `CARRIER_NOT_CONFIGURED` (`:39-42`), so a carrier outage and a carrier rejection are indistinguishable to the caller. |
| 8 | **Template Method** | **Partial** | Shared base classes: `utils/common-kafka/.../consumer/BaseKafkaListenerConfig.java:24-122` (the `listenerContainerFactory()` hook at `:48`, implemented by 6 service configs) and `utils/common-core/.../data/AbstractMappedEntity.java:14-41` (`@MappedSuperclass` audit/soft-delete template). | **Partial.** The Kafka base is a genuine template (`kafkaListenerContainerFactory()` at `:54-60` supplies the factory; `deadLetterErrorHandler` at `:62-65` supplies the recoverer). `AbstractMappedEntity` is *not* a template method — it is a mapped superclass with fixed audit columns and no protected hooks, so subclasses cannot vary the audit behaviour. |
| 9 | **Chain of Responsibility** | **Real (framework-provided)** | The gateway's filter chain: `GlobalRateLimitFilter` (`gateway-service/.../ratelimit/GlobalRateLimitFilter.java:12-47`, `Ordered.HIGHEST_PRECEDENCE` at `:47`), `RouteRateLimitFilter` (`gateway-service/.../ratelimit/RouteRateLimitFilter.java:12-46`), `AdminIpAllowlistFilter`, `AdminRoleGateFilter` (`gateway-service/.../filter/`). | **Real but framework-provided** — the chain is Spring Cloud Gateway's `GlobalFilter`/`GatewayFilter` ordering, not a hand-written chain. `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value, so the "allowlist first" contract is not enforced by ordering (recorded as GW-08 in `production-audit/reports/findings.md:73`). |
| 10 | **Repository** | **Real** | Spring Data repositories across every stateful module, e.g. `shipping-service/.../repository/ShipmentRepository.java:18-39`, `order-service/.../repository/OrderRepository.java:13-27`, `inventory-service/.../repository/InventoryRepository.java:12-30`. | **Real.** Caveat: the repositories are *not* insulated from the domain — several expose JPQL that encodes business rules (`InventoryRepository.atomicReserve`, `:18-23`; `CampaignRepository.claimQuota`, `promotion-service/.../repository/CampaignRepository.java:26-31`), so the repository is a **Unit of Work** collaborator rather than a persistence abstraction. |
| 11 | **Unit of Work / TransactionTemplate** | **Real** | `TransactionTemplate` is used explicitly where a programmatic boundary is needed: `shipping-service/.../outbox/ShippingOutboxRelay.java:52` + `:65-76` + `:116`; `order-service/.../service/CompensationFailureService.java:63-68` (three templates with distinct propagation); `order-service/.../service/impls/OrderCreateSaga.java:56` + `:83`. | **Real, and used correctly** — the relays use one transaction per claim and one per mark; the compensation service uses `PROPAGATION_REQUIRES_NEW` for the record, the claim and the update (`:64`, `:66`, `:68`). The gap is the *optional* transaction manager in `OrderCreateSaga` (SOLID-DIP-03). |
| 12 | **Reconciliation sweeper** | **Real** | 5 sweepers: `shipping-service/.../scheduler/ReconciliationScheduler.java:45-63`, `order-service/.../service/OrderReconciliationScheduler.java:80-102`, `inventory-service/.../service/ReservationCleanupScheduler.java:42`, `promotion-service/.../service/ReservationCleanupScheduler.java:49`, `media-service/.../job/MediaPurgeJob.java:26`, `product-service/.../job/ProductMediaSweepJob.java:29`. | **Real but not multi-instance-safe.** There is **no scheduler lock anywhere in the repository** — a repo-wide case-insensitive grep for `shedlock` over `*.java`, `*.xml` and `*.yml` returns **zero hits**. Every `@Scheduled` method therefore fires on **every** replica. The compensation ledger is the only one that claims its work (`FailedCompensationRepository.claimForProcessing`, `:27-35`); the others do not. |
| 13 | **CQRS projection** | **Partial** | `search-service` owns **no** persistence of its own (no `@Entity` under `search-service/src/main`) and maintains an Elasticsearch read model from the product lifecycle topic: `search-service/.../service/ProductSearchService.java:32-92`, driven by the `product.lifecycle` consumer. `ReindexServiceImpl` (`:40-70`) rebuilds the projection from the source of truth. | **Partial — it is a projection, but not a full CQRS split.** There is a genuine read model, but (a) it is fed by Kafka events, not by the write model's own outbox transaction, so the projection is only *eventually* consistent; (b) there is no separate command model, no read/write model version negotiation beyond Elasticsearch external versioning (`ProductSearchService.java:68-69`), and no projection checkpoint table — a replay from the beginning is indistinguishable from a fresh build. |

---

## 6. Documentation claims that the code does not realise

### 6.1 DP-01 — `utils/common-patterns` is documented as an existing verification suite and does not exist

| Claim | Source |
| --- | --- |
| "`common-patterns`: Architectural verification suites for fleet pattern compliance." | `README.md:612` |
| "**Pattern harness (`utils/common-patterns`)**: Every agent MUST run `./mvnw -T1C -pl utils/common-patterns test` before claiming a wave done. Rules encoded: R1 … R2 … R3 … R4 … R5 … R6." | `docs/superpowers/plans/2026-09-03-bug-sweep.md:116-122` |

Reality:

- No `utils/common-patterns/pom.xml` → the Maven command in the plan **cannot succeed**; the project is not in the reactor.
- `utils/common-patterns/src/main/java/com/shop/common/patterns/registry/` is an **empty directory** — there is no `FleetPatternComplianceTest` source anywhere in the repository (verified: `find . -name 'FleetPatternComplianceTest.java' -not -path '*/target/*'` returns nothing).
- Only a **compiled** `FleetPatternComplianceTest.class` survives in `utils/common-patterns/target/test-classes/`.

**Severity: HIGH.** A mandatory quality gate that cannot run is worse than no gate: it produces a green "I ran the harness" signal.

### 6.2 DP-02 — Rules R2, R5, R6 are documented as "encoded" but only R1, R3 and R4 are machine-enforced

`docs/superpowers/plans/2026-09-03-bug-sweep.md:118-122` claims the harness encodes R1–R6. The only machine enforcement that exists is `config/checkstyle.xml`:

| Rule | Enforced? | Where |
| --- | --- | --- |
| R1 (records, no `@Builder`) | **Yes** | `config/checkstyle.xml:18-25` (two `RegexpMultiline` rules) |
| R2 (`DataIntegrityViolationException` → 409) | No | — |
| R3 (Kafka key not `properties.`-prefixed) | **Yes** | `config/checkstyle.xml:49-52` |
| R4 (no `TrustedPackages.ALL`) | **Yes** | `config/checkstyle.xml:54-57` |
| R5 (non-root container) | No | — |
| R6 (env vars exist in `.env.example`) | No | — |

`docs/PATTERNS.md:293` is honest about this ("The build fails immediately if any fleet rule (R1, R3, R4) is violated"), but the plan document claims six. **Severity: MEDIUM** — the two documents disagree about what the gate does.

### 6.3 DP-03 — Rules R13–R18 are prose only

`docs/PATTERNS.md:309-439` defines R13 (confirmed provider outcome), R14 (no provider substitution), R15 (rate-limit identity from the transport peer), R16 (fail-closed authorization guard), R17 (platform user resolved by external identity) and R18 (consumer mapper + dead-letter recoverer). None of them has a machine check in `config/checkstyle.xml` or a test harness. R18 in particular is only partially honoured: the shared base installs a recoverer (`utils/common-kafka/.../consumer/BaseKafkaListenerConfig.java:58`) but `product-service`'s media factory does not use the base at all (`product-service/.../kafka/MediaLifecycleListenerConfig.java:18-28`). **Severity: MEDIUM.**

### 6.4 DP-04 — The DLT replay endpoint is documented as operable and is unreachable

`docs/PATTERNS.md:424-426` (R18) states "The dead-letter replay endpoint requires an administrative role and validates the requested topic against the topic contract." The endpoint exists and does exactly that (`utils/common-kafka/.../replay/DeadLetterReplayEndpoint.java:24-33`), but:

- the bean is gated on `shop.kafka.dlq-replay.enabled=true` (`utils/common-kafka/.../config/KafkaAutoConfiguration.java:66`), and **no YAML in the repository sets that property** (repo-wide grep over `*.yml`/`*.yaml` for `dlq`/`replay` returns nothing);
- even if the bean existed, the actuator web exposure list is `health,info,prometheus,metrics` (`utils/common-spring/src/main/resources/application.yml:36`) — `deadLetterReplay` is not in it.

**Severity: HIGH.** The dead-letter topic is a write-only bucket.

### 6.5 DP-05 — "Transactional Outbox … break-on-error to preserve per-aggregate ordering" is true for 7 relays and false for 1

`docs/SERVICE-CATALOG.md:190` describes the inventory relay as using "break-on-error to preserve per-aggregate ordering". The mechanism is `OutboxBatchOrdering.markableAsSent` (`utils/common-kafka/.../producer/OutboxBatchOrdering.java:11-27`), applied by 7 relays: product (`:111`), rating (`:102`), media (`:105`), shipping (`:110`), order (`:115`), promotion (`:115`), inventory (`:113`). **`payment-service` does not apply it** — `PaymentOutboxRelay.relay` marks each row independently from the raw batch outcome (`payment-service/.../outbox/PaymentOutboxRelay.java:87-96`). **Severity: MEDIUM.**

### 6.6 DP-06 — "Acquires pessimistic cart lock (`SELECT FOR UPDATE`)" is true for checkout and false for the cart API

`docs/SERVICE-CATALOG.md:240` documents the checkout path as acquiring a pessimistic cart lock. That is correct — `OrderCreateSaga` uses `findByUserIdForUpdate` / `findByIdAndUserIdForUpdate` (`order-service/.../service/impls/OrderCreateSaga.java:123` and `:125`; the lock is declared at `order-service/.../repository/CartRepository.java:23-31`). But the **cart REST path does not use it**: `CartServiceImpl.getOrCreateCart` uses the unlocked `findByUserIdAndDeletedFalse` (`order-service/.../service/impls/CartServiceImpl.java:178`), and the subtotal/quantity updates at `:57`, `:79-80`, `:102-103`, `:117-118`, `:151-155` and `:171-172` are unlocked read-modify-writes on an entity with **no `@Version`**. **Severity: HIGH.**

---

## 7. Refactoring recommendations, ordered by blast radius

Blast radius = number of modules and consumers that must change together.

### R1 — Restore the pattern harness, or delete the claim (blast radius: 1 module, fleet-wide process)

**Files**
- Create `utils/common-patterns/pom.xml` (parent `utils/pom.xml`, artifact `common-patterns`).
- Add `<module>common-patterns</module>` to `utils/pom.xml:8-14`.
- Create `utils/common-patterns/src/test/java/com/shop/common/patterns/FleetPatternComplianceTest.java` with the R1–R6 checks the plan already promises.
- Or, if the harness is not wanted: remove `README.md:612` and `docs/superpowers/plans/2026-09-03-bug-sweep.md:116-122`.

**Do first.** Until this is resolved, every "the gate passed" statement in the fleet's history is unsupported.

### R2 — Move the carrier side effect behind the aggregate (blast radius: shipping-service only, one external API)

**Files**
- `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:78-102` — split `createShipment` into (a) a transactional claim of the `order_id` and (b) a post-commit carrier call.
- `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java` — add a `carrierRequested` / `trackingNumber` nullable state so "row claimed, parcel not yet created" is representable.
- `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java:26-43` — send a deterministic idempotency key (`order_id` is already in the body at `:33`).
- `shipping-service/src/main/resources/db/changelog/` — new additive changeset for the new column.

**Why first:** it is the only finding in this audit that produces **two physical parcels for one order** — an external, irreversible side effect that the database cannot arbitrate.

### R3 — Move the provider call out of the database transaction (blast radius: payment-service, 1 external API)

**Files**
- `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java:121-137` (capture) and `:158-174` (refund) — the provider call at `:129-130` and `:166-167` runs inside the `@Transactional` boundary.
- `payment-service/src/main/java/com/shop/paymentservice/service/PaymentWriter.java` — add a non-transactional `applyCaptureOutcome` writer and an explicit `PENDING → CAPTURED` compare-and-set (`UPDATE … WHERE id=? AND status='PENDING'`).
- `payment-service/src/main/java/com/shop/paymentservice/service/PaymentStateMachine.java:25-27` — remove the self-transition so a re-processed webhook cannot publish a second `payment.captured.v1`.

### R4 — Give every scheduled worker a claim (blast radius: 6 modules, no external API)

**Files**
- `payment-service/src/main/java/com/shop/paymentservice/scheduler/WebhookRetryScheduler.java:47-49` and `payment-service/.../repository/PaymentEventRepository.java:18-19`.
- `shipping-service/src/main/java/com/shop/shippingservice/scheduler/WebhookRetryScheduler.java:47-49` and `shipping-service/.../repository/ShipmentEventRepository.java`.
- `order-service/src/main/java/com/shop/orderservice/service/OrderReconciliationScheduler.java:80-102`.
- `search-service/src/main/java/com/shop/searchservice/service/impls/ReindexServiceImpl.java:37-49` (the in-process `AtomicBoolean`).
- New additive Liquibase changesets in each module for a `claimed_at` + `status='IN_PROGRESS'` pair.

**Template to copy:** `order-service/.../service/CompensationFailureService.java:104-140` + `order-service/.../repository/FailedCompensationRepository.java:27-35` — this is the only claim in the fleet that is correct.

### R5 — Make the cart write path atomic (blast radius: order-service only)

**Files**
- `order-service/src/main/java/com/shop/orderservice/entity/Cart.java` — add `@Version`.
- `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:176-189` — `getOrCreateCart` must use the locking finder (`CartRepository.findByUserIdForUpdate`, `order-service/.../repository/CartRepository.java:23-26`) for every mutation path, not just checkout.
- `order-service/src/main/java/com/shop/orderservice/service/impls/CartServiceImpl.java:54-80` and `:146-172` — the quantity and subtotal read-modify-writes.

### R6 — Restore substitutability to the Strategy ports (blast radius: shipping-service, payment-service)

**Files**
- `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierAdapter.java:17-20` — the `ShipmentDraft` contract must fix the initial status (always `CREATED`) and move the `PICKED_UP` choice into an explicit operator action.
- `shipping-service/src/main/java/com/shop/shippingservice/carrier/NoopCarrierAdapter.java:21` — return `CREATED`.
- `payment-service/src/main/java/com/shop/paymentservice/provider/MockProvider.java:18-26` — return `INDETERMINATE`/`REJECTED` for a configurable scripted outcome instead of always `CONFIRMED`.
- `payment-service/src/main/java/com/shop/paymentservice/provider/PaymentProvider.java:14-16` — replace the lossy `default refund(...)` with a separate `RefundCapableProvider` interface.

### R7 — Segregate the storage port and invert the dependency on the carrier factory (blast radius: utils/common-storage + shipping-service)

**Files**
- `utils/common-storage/src/main/java/com/shop/common/storage/service/ObjectStorageService.java:7-37` — split into `ObjectStorageReader`, `ObjectStorageWriter`, `ObjectStorageUrlIssuer`.
- `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:41` + `:84-86` — depend on a `CarrierSelector` abstraction, never on a nullable concrete `CarrierFactory`.
- `order-service/src/main/java/com/shop/orderservice/service/impls/OrderCreateSaga.java:70` + `:83` + `:102-107` — make `PlatformTransactionManager` mandatory; remove the no-transaction fallback.

### R8 — Split the three god services (blast radius: order-service, payment-service, shipping-service)

**Files**
- `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:59-482` — extract `OrderCancellationCompensator` (`:178-271`) and `OrderConfirmationService` (`:273-339`).
- `payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java:36-365` — extract `StripeWebhookAdapter` (`:60-84`) and `PaymentRefundApplier` (`:148-202`).
- `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java:31-196` — extract `ShipmentCreator` (`:78-102`).

---

## 8. What this audit could not verify

| Limitation | Consequence |
| --- | --- |
| **No Maven build was run.** The instructions forbade a full build because other agents were building concurrently. | Runtime claims (e.g. that Hibernate maps `lock.timeout = -2` to `SKIP LOCKED`) are derived from the declared intent and the module's own integration tests (`ShippingOutboxClaimConcurrencyIT`, `InventoryOutboxClaimConcurrencyTest`, `PaymentOutboxClaimConcurrencyIT`, `ProductOutboxClaimConcurrencyIT`, `PromotionOutboxClaimConcurrencyIT`, `RatingOutboxClaimConcurrencyIT`), not from a run performed by this audit. |
| **Elasticsearch internals are not in this repository.** | The claim that `VersionType.External` rejects a non-increasing version is the documented Elasticsearch semantic; the in-repo evidence is only that the client *sends* `.version(version).versionType(VersionType.External)` (`search-service/.../service/ProductSearchService.java:65-70`). |
| **Spring Cloud Gateway's `RedisRateLimiter` internals are not in this repository.** | The gateway's *framework* rate limiter is characterised only by its construction (`gateway-service/.../ratelimit/RateLimitConfiguration.java:16-31`); the hand-written Lua limiter (`gateway-service/.../ratelimit/RedisEdgeRateLimiter.java:19-47`) was read directly and is characterised from source. |
| **The gateway `GlobalRateLimiter`/`RouteRateLimiter` key layout is not in this repository.** | Claims about Redis Cluster `CROSSSLOT` are made **only** for the edge limiter, whose two keys are constructed in-repo (`RedisEdgeRateLimiter.java:65-67`). |
| **`shipping-service/**` and `rating-service/**` were being edited concurrently.** | Line numbers in those trees are the ones observed at read time. The `ShipmentServiceImpl.java:80/92/98` anchors were re-read immediately before this report was written. |
| **The `production-audit/runs/run-20261118-a2b6e6fc/source-reviews/*.md` receipts were used as a lead only.** | Every claim in §3–§6 was re-verified against the current source; where the receipt and the current source disagreed (line numbers, and several findings that had since been fixed), the **current source** was used and the divergence is stated. |
| **No runtime load test or chaos test was executed.** | "Safe under N instances" verdicts in `concurrency.md` are analytic, derived from the locking and constraint primitives that were read. |
