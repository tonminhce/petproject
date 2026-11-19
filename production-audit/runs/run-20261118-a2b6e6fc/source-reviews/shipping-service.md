# shipping-service — Source Review

**Module:** `shipping-service` (Spring Boot 4.1.1 / Java 25)
**Audit baseline:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`
**Auditor role:** A7/A4 — Shipping & Distributed Systems
**Run:** `run-20261118-a2b6e6fc`

## Scope and method

Every `.java` file under `shipping-service/src/` (main **and** test), every file under
`shipping-service/src/main/resources/db/changelog/`, and `shipping-service/src/main/resources/application.yml`
were read from line 1 to EOF. Nothing was skipped. No file failed to open; there is no unread-file
carve-out in this report.

**Blob-hash / line-number basis.** The blob hashes in the inventory are
`git rev-parse a2b6e6fc336f42f851166cab0cf0d1bba42db12f:<path>` (first 12 hex), and the line counts
and `file:line` citations are against that **baseline revision**. The repository working tree is at
`66d91881` ("refactor(common): remove explanatory comments from first-party source"), which post-dates
the baseline. That later commit removed **explanatory Javadoc/comments only** from this module
(`git diff a2b6e6fc -- shipping-service` = 36 files, +5 / −465 lines). The only non-comment code
deltas between the audited baseline and the current working tree are three cosmetic substitutions
that do not change behaviour, and they are called out here so the two revisions can be reconciled:

| Baseline (`a2b6e6fc`) | Working tree (`66d91881`) | Behavioural effect |
|---|---|---|
| `WebhookRetryScheduler.java:31` `BASE_BACKOFF_SECONDS = 300L; // 5 minutes` | same constant, comment stripped (`WebhookRetryScheduler.java:24`) | none |
| `ShippingOutboxRelay.java:114` `return null;  // nothing pending …` | `return null;` (`ShippingOutboxRelay.java:68`) | none |
| `ShippingOutboxRelay.java:204` `return;  // the sweep already moved this row; skip.` | `return;` (`ShippingOutboxRelay.java:137`) | none |
| `WebhookEventServiceImpl.java:143-146` comment lines inside the unknown-tracking branch | comments stripped (`WebhookEventServiceImpl.java:135-136`) | none |

All other baseline-vs-HEAD differences in this module are deleted comment lines.

---

## 1. FILE INVENTORY TABLE

| File | Blob (a2b6e6fc) | Lines | Range |
|---|---|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/ShippingServiceApplication.java` | `c76e5e10a2b2` | 17 | 1–17 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierAdapter.java` | `d830579a4279` | 21 | 1–21 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierConfig.java` | `980e41400435` | 33 | 1–33 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierFactory.java` | `cb2b1c6887b8` | 59 | 1–59 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java` | `74e5677a50f1` | 97 | 1–97 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/GhnCarrierAdapter.java` | `5c2239cd9417` | 74 | 1–74 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/GhtkCarrierAdapter.java` | `54095002e72f` | 68 | 1–68 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/ManualCarrierAdapter.java` | `58a290a06b72` | 23 | 1–23 |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/NoopCarrierAdapter.java` | `9f7506eff633` | 23 | 1–23 |
| `shipping-service/src/main/java/com/shop/shippingservice/config/ClockConfig.java` | `3053b45b8ced` | 15 | 1–15 |
| `shipping-service/src/main/java/com/shop/shippingservice/config/ShippingWebhookProperties.java` | `f8c77e1cd8ee` | 20 | 1–20 |
| `shipping-service/src/main/java/com/shop/shippingservice/constant/Carrier.java` | `a85cc95a913a` | 5 | 1–5 |
| `shipping-service/src/main/java/com/shop/shippingservice/constant/ShipmentStatus.java` | `c2d5b7126758` | 9 | 1–9 |
| `shipping-service/src/main/java/com/shop/shippingservice/controller/BackofficeShipmentController.java` | `f9867d35b91b` | 84 | 1–84 |
| `shipping-service/src/main/java/com/shop/shippingservice/dto/OrderLifecycleEvent.java` | `2868cb6fb6a5` | 21 | 1–21 |
| `shipping-service/src/main/java/com/shop/shippingservice/dto/request/AssignTrackingRequest.java` | `ba94aa8a6793` | 4 | 1–4 |
| `shipping-service/src/main/java/com/shop/shippingservice/dto/request/ShipmentTransitionRequest.java` | `0001a5ff9a72` | 6 | 1–6 |
| `shipping-service/src/main/java/com/shop/shippingservice/dto/response/ShipmentResponse.java` | `d3d02a9b980f` | 38 | 1–38 |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/Shipment.java` | `d51ced5787cc` | 75 | 1–75 |
| `shipping-service/src/main/java/com/shop/shippingservice/entity/ShipmentEvent.java` | `aa485af85870` | 72 | 1–72 |
| `shipping-service/src/main/java/com/shop/shippingservice/kafka/OrderEventConsumer.java` | `dff78300f3da` | 28 | 1–28 |
| `shipping-service/src/main/java/com/shop/shippingservice/kafka/ShippingListenerConfig.java` | `fbf965ed34d8` | 27 | 1–27 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEvent.java` | `f3044f3561ee` | 58 | 1–58 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java` | `6ed9abe244de` | 86 | 1–86 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisher.java` | `1728b62be4f6` | 8 | 1–8 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingEventPublisherImpl.java` | `52f527329498` | 64 | 1–64 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRelay.java` | `09774511930a` | 251 | 1–251 |
| `shipping-service/src/main/java/com/shop/shippingservice/outbox/ShippingOutboxRetentionScheduler.java` | `0cc5563c3fce` | 33 | 1–33 |
| `shipping-service/src/main/java/com/shop/shippingservice/repository/ShipmentEventRepository.java` | `c5273af3c901` | 28 | 1–28 |
| `shipping-service/src/main/java/com/shop/shippingservice/repository/ShipmentRepository.java` | `9dd02835de23` | 47 | 1–47 |
| `shipping-service/src/main/java/com/shop/shippingservice/scheduler/ReconciliationScheduler.java` | `5914d87f994d` | 64 | 1–64 |
| `shipping-service/src/main/java/com/shop/shippingservice/scheduler/WebhookRetryScheduler.java` | `aec6b3cca69e` | 117 | 1–117 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentService.java` | `51743baf6bcc` | 27 | 1–27 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentStateMachine.java` | `faff176f434a` | 35 | 1–35 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShipmentWriter.java` | `05e7b2c87e21` | 33 | 1–33 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/ShippingMetrics.java` | `d17a6a6476ea` | 37 | 1–37 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/WebhookEventService.java` | `d57e5aab9f09` | 21 | 1–21 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/WebhookEventWriter.java` | `bf2facd23d85` | 42 | 1–42 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/ShipmentServiceImpl.java` | `ca3833ca9812` | 199 | 1–199 |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java` | `4ae6412d8006` | 245 | 1–245 |
| `shipping-service/src/main/java/com/shop/shippingservice/webhook/CarrierWebhookController.java` | `e380bb8dbe35` | 30 | 1–30 |
| `shipping-service/src/main/java/com/shop/shippingservice/webhook/CarrierWebhookPayload.java` | `30abc8fb5efa` | 18 | 1–18 |
| `shipping-service/src/main/java/com/shop/shippingservice/webhook/WebhookSignatureVerifier.java` | `4d2a2091c739` | 35 | 1–35 |
| `shipping-service/src/main/resources/application.yml` | `8cd0ca48635a` | 48 | 1–48 |
| `shipping-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `8577b93f6647` | 19 | 1–19 |
| `shipping-service/src/main/resources/db/changelog/changelog-001-shipments.yaml` | `3bf986c75400` | 84 | 1–84 |
| `shipping-service/src/main/resources/db/changelog/changelog-002-shipping-outbox.yaml` | `60bf6c4079ed` | 43 | 1–43 |
| `shipping-service/src/main/resources/db/changelog/changelog-003-webhook-retry.yaml` | `dc23dab308fa` | 29 | 1–29 |
| `shipping-service/src/main/resources/db/changelog/changelog-004-expand-events-status-check.yaml` | `47b8edac50c0` | 26 | 1–26 |
| `shipping-service/src/main/resources/db/changelog/changelog-005-carrier-acknowledged-at.yaml` | `ebcea7951c0b` | 21 | 1–21 |
| `shipping-service/src/main/resources/db/changelog/006-shipping-outbox-claim-lease.yaml` | `0abc0e940cd1` | 13 | 1–13 |
| `shipping-service/src/test/java/com/shop/shippingservice/ShippingFlowIT.java` | `4c4791f50443` | 603 | 1–603 |
| `shipping-service/src/test/java/com/shop/shippingservice/carrier/CarrierAdapterDraftsTest.java` | `d68f686f4a97` | 55 | 1–55 |
| `shipping-service/src/test/java/com/shop/shippingservice/carrier/CarrierConfigTest.java` | `bbba1592db2b` | 77 | 1–77 |
| `shipping-service/src/test/java/com/shop/shippingservice/carrier/CarrierShipmentClientTest.java` | `97e232e8cd26` | 104 | 1–104 |
| `shipping-service/src/test/java/com/shop/shippingservice/carrier/GhnCarrierAdapterTest.java` | `09975f9f6d73` | 65 | 1–65 |
| `shipping-service/src/test/java/com/shop/shippingservice/carrier/GhtkCarrierAdapterTest.java` | `ffed53500a68` | 64 | 1–64 |
| `shipping-service/src/test/java/com/shop/shippingservice/config/TestClockConfig.java` | `4d5eaa4fd89a` | 43 | 1–43 |
| `shipping-service/src/test/java/com/shop/shippingservice/config/TestLiquibaseConfig.java` | `d43d4cdea2e7` | 22 | 1–22 |
| `shipping-service/src/test/java/com/shop/shippingservice/controller/BackofficeShipmentAuditTest.java` | `2d444b0ca061` | 90 | 1–90 |
| `shipping-service/src/test/java/com/shop/shippingservice/controller/BackofficeShipmentControllerTest.java` | `5865f677583c` | 359 | 1–359 |
| `shipping-service/src/test/java/com/shop/shippingservice/db/ShipmentEventStatusChangelogTest.java` | `95bb6cff4eef` | 56 | 1–56 |
| `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingEventPublisherImplTest.java` | `1e0d82c7a921` | 86 | 1–86 |
| `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxClaimConcurrencyIT.java` | `cf5acb201028` | 184 | 1–184 |
| `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxRelayRecoveryIT.java` | `91aa00664cb4` | 193 | 1–193 |
| `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxRelayTest.java` | `1e37cfd2ef0d` | 208 | 1–208 |
| `shipping-service/src/test/java/com/shop/shippingservice/scheduler/ReconciliationSchedulerTest.java` | `e1082ef30a2a` | 136 | 1–136 |
| `shipping-service/src/test/java/com/shop/shippingservice/scheduler/WebhookRetrySchedulerTest.java` | `a8525b164a13` | 125 | 1–125 |
| `shipping-service/src/test/java/com/shop/shippingservice/service/ShipmentStateMachineTest.java` | `30ca9b1af3bf` | 84 | 1–84 |
| `shipping-service/src/test/java/com/shop/shippingservice/service/ShipmentWriterTest.java` | `421ae7e9c7a4` | 83 | 1–83 |
| `shipping-service/src/test/java/com/shop/shippingservice/service/WebhookEventWriterTest.java` | `67914f1478fd` | 98 | 1–98 |
| `shipping-service/src/test/java/com/shop/shippingservice/service/impls/ShipmentServiceImplTest.java` | `1f9a8c5b10ea` | 405 | 1–405 |
| `shipping-service/src/test/java/com/shop/shippingservice/service/impls/WebhookEventServiceTest.java` | `98fdaf4e8334` | 401 | 1–401 |
| `shipping-service/src/test/java/com/shop/shippingservice/support/AbstractIntegrationTest.java` | `a27940677305` | 122 | 1–122 |
| `shipping-service/src/test/java/com/shop/shippingservice/support/ShippingBootstrapIT.java` | `728924ddce7c` | 92 | 1–92 |
| `shipping-service/src/test/java/com/shop/shippingservice/webhook/WebhookSignatureVerifierTest.java` | `26e3aaac9c7e` | 101 | 1–101 |

**Counts:** 68 Java files + 8 resource files = 76 files. Baseline line counts: Java 43 main
(2 297 lines) + 25 test (3 856 lines) = 6 153 Java lines; resources 283 lines; **6 436 lines read
end to end**.

**Not in scope / not audited (stated explicitly):** `shipping-service/pom.xml`,
`shipping-service/.flattened-pom.xml`, and everything under `shipping-service/target/` (build
output, not source). The task scope for this module was the Java sources, the Liquibase changelog
directory and `application.yml`; those three files were read only far enough to confirm they hold no
Java logic (they are not listed as audited artefacts above). No file in the module was left unread
within the declared scope.

---

## 2. CARRIER ADAPTER ARCHITECTURE

### 2.1 The port

`carrier/CarrierAdapter.java:8-21` is the port every carrier integrates through:

```java
public interface CarrierAdapter {

    Carrier carrier();

    default String carrierCode() {
        Carrier c = carrier();
        return (c != null) ? c.name() : "MANUAL";
    }

    ShipmentDraft createShipment(UUID orderId);

    record ShipmentDraft(String trackingNumber, ShipmentStatus initialStatus) {
    }
}
```

`carrierCode()` (`CarrierAdapter.java:12-15`) masks a `null` carrier as `"MANUAL"` — a violated
implementation contract is silently rewritten into a valid carrier code, and that rewritten code is
what `CarrierFactory` indexes the adapter under (`CarrierFactory.java:21-24`).

### 2.2 The adapters

| Adapter | File | `carrier()` | `createShipment` behaviour | Initial status | Tracking number |
|---|---|---|---|---|---|
| Manual | `carrier/ManualCarrierAdapter.java:10-22` | `MANUAL` | no carrier call | `CREATED` (`:21`) | `null` |
| Noop | `carrier/NoopCarrierAdapter.java:10-22` | `NOOP` | no carrier call | `PICKED_UP` (`:21`) | `"NOOP-" + orderId` (`:21`) |
| Ghn | `carrier/GhnCarrierAdapter.java:28-73` | `GHN` | `CarrierShipmentClient.createShipment(GHN, endpoint, token, shopId, orderId, jsonPath)` (`:68-70`) | `CREATED` (`:72`) | carrier-issued |
| Ghtk | `carrier/GhtkCarrierAdapter.java:25-67` | `GHTK` | `CarrierShipmentClient.createShipment(GHTK, endpoint, token, null, orderId, jsonPath)` (`:62-64`) | `CREATED` (`:66`) | carrier-issued |

Both real adapters fail fast at construction when the endpoint or token is missing
(`GhnCarrierAdapter.java:53-60`, `GhtkCarrierAdapter.java:47-54`) — the context fails to start rather
than fabricating tracking numbers (`CarrierShipmentClientTest.java:73-88` proves both).

### 2.3 The "factory" and how an adapter is actually chosen

* `carrier/CarrierConfig.java:18-27` — `@Bean @Primary primary(List<CarrierAdapter> all, @Value("${shop.shipping.carrier:manual}") String carrierName)`
  picks the adapter whose `carrier().name()` matches the configured name, `orElse(all.get(0))`.
* `carrier/CarrierConfig.java:29-32` — `carrierFactory(List<CarrierAdapter> all)` builds the `CarrierFactory`
  from the same list.
* `carrier/CarrierFactory.java:19-35` — the constructor indexes every adapter by
  `carrierCode().toUpperCase(Locale.ROOT)` into a `ConcurrentHashMap` and remembers the **first** one as
  `defaultCarrier`.
* `service/impls/ShipmentServiceImpl.java:84-86` — the only production use is
  `carrierFactory.getDefaultCarrier()`.

**The registry is never used for dispatch.** `CarrierFactory.getCarrier(Carrier)`
(`CarrierFactory.java:37-42`) and `getCarrier(String)` (`CarrierFactory.java:44-50`) have **no
production caller** — the only callers in the whole module are
`carrier/CarrierConfigTest.java:47-51`. The effective dispatch mechanism is the `@Primary` bean
chosen by `CarrierConfig.primary`, i.e. selection by Spring wiring, not by the factory.

### 2.4 Which patterns are genuinely present

| Pattern | Verdict | Evidence |
|---|---|---|
| **Strategy** | **Genuine** | One port (`CarrierAdapter.java:8-21`) with four interchangeable implementations (`ManualCarrierAdapter`, `NoopCarrierAdapter`, `GhnCarrierAdapter`, `GhtkCarrierAdapter`); the concrete strategy is chosen once, from configuration, at `CarrierConfig.java:18-27`. The call site is polymorphic: `ShipmentServiceImpl.java:90-92` calls `adapter.carrier()` / `adapter.createShipment(orderId)` against the interface type. |
| **Adapter** (GoF) | **Genuine** | Each carrier adapter translates a carrier-specific REST response into the port's neutral `ShipmentDraft` (`GhnCarrierAdapter.java:68-73`, `GhtkCarrierAdapter.java:62-67`), and the shared transport/parsing lives in `carrier/CarrierShipmentClient.java:53-96`. This is a hexagonal port with an anti-corruption adapter per carrier. |
| **Factory Method** | **Naming only** | `CarrierFactory` never constructs anything (`CarrierFactory.java:19-35` only indexes beans Spring already built) and it is not subclassed to vary product creation. It is a **Registry**, not a Factory Method — and the registry lookup API is dead code. |
| **Template Method** | **Naming only** | The closest candidate is the relay's `relay()` → claim/publish/mark decomposition (`ShippingOutboxRelay.java:100-172`), but there is no abstract hook, no overridable step and no inheritance-oriented design; it is a plain private-method decomposition. |
| **Singleton** | **Genuine but trivial** | Spring beans are singletons; `CarrierFactory`'s map is filled in the constructor and never mutated afterwards (`CarrierFactory.java:16-35`), so the shared instance is effectively immutable. |

### 2.5 Is an unknown carrier name rejected or silently substituted?

**Both, depending on where the name enters — and the runtime answer is the dangerous one.**

* **At startup (the `shop.shipping.carrier` property): rejected.** Every adapter is conditional on a
  specific value (`ManualCarrierAdapter.java:11` `matchIfMissing = true`; `NoopCarrierAdapter.java:11`;
  `GhnCarrierAdapter.java:15`; `GhtkCarrierAdapter.java:15`), so an unknown value registers **zero**
  `CarrierAdapter` beans and `CarrierConfig.java:26` (`all.get(0)`) throws
  `IndexOutOfBoundsException` → the context fails to start. Fail-fast is correct, but the failure is
  an `IndexOutOfBoundsException` with no message naming the bad property value (SHIP-18).
* **At runtime (`CarrierFactory.getCarrier(String)`): silently substituted.**
  `CarrierFactory.java:44-50` returns `defaultCarrier` for any unknown code, and the module's own test
  asserts that behaviour: `carrier/CarrierConfigTest.java:51`
  `assertThat(factory.getCarrier("unknown_carrier")).isSameAs(factory.getDefaultCarrier())`.
  A null or blank code also yields the default (`CarrierFactory.java:42-44`).
* **The carrier enum itself is closed**: `constant/Carrier.java:3-5` has exactly
  `MANUAL, NOOP, GHN, GHTK`, and the webhook path rejects anything else
  (`WebhookEventServiceImpl.java:188-194` → `Carrier.valueOf` → SHP-10004/401,
  `WebhookEventServiceTest.java:291-300`).

**Verdict:** an unknown carrier name is *rejected at startup* but *silently substituted at runtime*.
Because the runtime lookup has no production caller today, the exposure is low; the API is a trap for
the next caller (SHIP-18).

### 2.6 Is a carrier failure distinguishable from a carrier rejection?

**No — and there is no rejection concept in the module at all.** Every carrier-side failure mode is
collapsed into a single error code, `ErrorCode.CARRIER_NOT_CONFIGURED` = `SHP-10006` = HTTP **409
CONFLICT** (`utils/common-core/.../ErrorCode.java:117`):

| Failure mode | Code path | Result |
|---|---|---|
| HTTP call throws (timeout, connection refused, 4xx/5xx) | `CarrierShipmentClient.java:66-69` | `CARRIER_NOT_CONFIGURED` |
| Empty response body | `CarrierShipmentClient.java:73-76` | `CARRIER_NOT_CONFIGURED` |
| Non-JSON response body | `CarrierShipmentClient.java:78-83` | `CARRIER_NOT_CONFIGURED` |
| No tracking number at the configured JSON path | `CarrierShipmentClient.java:90-94` | `CARRIER_NOT_CONFIGURED` |
| Missing endpoint or token | `GhnCarrierAdapter.java:53-60`, `GhtkCarrierAdapter.java:47-54` | `CARRIER_NOT_CONFIGURED` at startup |

Only the log line differs between the cases. A carrier that *refuses* the shipment (4xx) and a
carrier that is *down* (timeout/5xx) are indistinguishable to the caller, and both are reported as
"carrier not configured" — which is factually wrong when the carrier is configured but failing, and
the 409 status tells the caller the request was in conflict rather than that a dependency failed.
`CarrierShipmentClientTest.java:61-70` locks in exactly this collapse.

The consequence propagates into the order pipeline: `adapter.createShipment(orderId)` is called
**inside** the Kafka listener (`ShipmentServiceImpl.java:92`), so a carrier outage throws out of the
listener and the order event is dead-lettered (see §4, scenario A, and SHIP-02).

---

## 3. SHIPMENT STATE MACHINE

### 3.1 The statuses

`constant/ShipmentStatus.java:3-4` — `CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED,
DELIVERY_FAILED, CANCELLED` (7 values). `ShipmentStatus.inFlight` (`ShipmentStatus.java:6-8`) =
`PICKED_UP | IN_TRANSIT | OUT_FOR_DELIVERY`. The database mirrors the enum with
`ck_shipments_status` (`changelog-001-shipments.yaml:37`), so an unknown status cannot be persisted.

### 3.2 The legal transitions

`service/ShipmentStateMachine.java:12-24` declares exactly 12 edges:

| From | To | Line |
|---|---|---|
| CREATED | PICKED_UP | `:13` |
| PICKED_UP | IN_TRANSIT | `:14` |
| IN_TRANSIT | OUT_FOR_DELIVERY | `:15` |
| OUT_FOR_DELIVERY | DELIVERED | `:16` |
| PICKED_UP | DELIVERY_FAILED | `:17` |
| IN_TRANSIT | DELIVERY_FAILED | `:18` |
| OUT_FOR_DELIVERY | DELIVERY_FAILED | `:19` |
| DELIVERY_FAILED | IN_TRANSIT | `:20` |
| CREATED | CANCELLED | `:21` |
| PICKED_UP | CANCELLED | `:22` |
| IN_TRANSIT | CANCELLED | `:23` |
| OUT_FOR_DELIVERY | CANCELLED | `:24` |

Anything else — including a null `from` or `to` — raises
`BusinessException.of(ErrorCode.SHIPMENT_INVALID_TRANSITION)` = `SHP-10003` / HTTP 409
(`ShipmentStateMachine.java:29-34`; `ErrorCode.java:114`). The matrix is proven exhaustively:
`service/ShipmentStateMachineTest.java:44-60` walks all 7 × 7 pairs.

### 3.3 Transitions the code permits that it should not

* **A delivered shipment can be cancelled?** **No.** `DELIVERED` has no outgoing edge
  (`ShipmentStateMachine.java:12-24`) — `DELIVERED → CANCELLED` throws SHP-10003. The example in the
  brief is **refuted**.
* **A cancelled shipment can be delivered?** **No.** `CANCELLED` has no outgoing edge either.
  **Refuted.** `DELIVERED` and `CANCELLED` are both terminal through the FSM.
* **The FSM is not the single enforcement point.** Status is written on four paths, and only three of
  them go through the FSM:

  | Writer | File:line | Goes through the FSM? |
  |---|---|---|
  | Operator transition / fail / retry | `ShipmentServiceImpl.java:183-198` (`advance`, FSM at `:185`) | **Yes** |
  | Order-cancellation event | `ShipmentServiceImpl.java:107-118` (FSM at `:115`) | **Yes** |
  | Carrier webhook | `WebhookEventServiceImpl.java:140-180` (FSM at `:153`) | **Yes** |
  | **Reconciliation scheduler** | **`ReconciliationScheduler.java:55-61`** | **No — direct `setStatus(DELIVERED)`** |
  | Shipment creation | `ShipmentServiceImpl.java:98` (`shipment.setStatus(draft.initialStatus())`) | No — the status comes from the carrier draft, not from the FSM |

  The reconciliation scheduler therefore performs a transition the FSM **forbids**: the sweep selects
  `PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY` (`ReconciliationScheduler.java:25-27`, `:48`) and flips
  all of them to `DELIVERED` (`:57`), while the FSM only allows `OUT_FOR_DELIVERY → DELIVERED`.
  The module's own test codifies the bypass: `scheduler/ReconciliationSchedulerTest.java:88-100`
  ("pickedUpPastCutoff_alsoFlipsToDelivered"). **SHIP-05.**
* **The FSM permits a backward edge that a late event can exploit.** `DELIVERY_FAILED → IN_TRANSIT`
  (`ShipmentStateMachine.java:20`) is legitimate for an operator retry, but the webhook path computes
  the transition from the *current* status (`WebhookEventServiceImpl.java:153`) with no ordering key,
  so a **delayed** `IN_TRANSIT` event arriving after a `DELIVERY_FAILED` event rolls the shipment
  back to `IN_TRANSIT` — after which the stale sweep auto-delivers it. **SHIP-06**, analysed in §4 C.
* **The entity's status field is publicly writable.** `entity/Shipment.java:24` applies Lombok
  `@Setter` to the whole entity, and there is no `@PreUpdate` hook, so nothing structurally prevents a
  future code path (or a data-fix script) from writing `status` without consulting the FSM.

### 3.4 Verdict

The transition table itself is correct, minimal and exhaustively tested — `DELIVERED` and `CANCELLED`
are terminal, and no illegal edge can be taken *through the FSM*. But the FSM is **not** the single
place where transitions are enforced: the reconciliation scheduler writes the status directly, and it
writes a transition the FSM explicitly forbids. The fix is to give the auto-delivery path its own
explicit FSM edge (or a dedicated `transitionAutoDeliver`) so that all four writers converge on one
choke point (SHIP-05, §9 sketch 2).

---

## 4. CONCURRENCY AND MULTI-INSTANCE ANALYSIS

No distributed scheduler lock exists anywhere in the repository — `grep -rn "shedlock\|ShedLock"`
over all POMs, Java sources and YAML files returns **nothing**. Every `@Scheduled` method in this
module therefore runs on **every replica**. The four scheduled methods are:

| Scheduler | File:line | Schedule | Multi-instance safety |
|---|---|---|---|
| Outbox relay | `outbox/ShippingOutboxRelay.java:97-100` | fixedDelay 2 000 ms | safe by DB claim (see F) |
| Outbox retention purge | `outbox/ShippingOutboxRetentionScheduler.java:20-22` | cron `0 0 3 * * *` | benign (idempotent delete) |
| Reconciliation sweep | `scheduler/ReconciliationScheduler.java:45-46` | cron `0 0 * * * *` | safe on data, unsafe on counters (§4 D) |
| Webhook retry | `scheduler/WebhookRetryScheduler.java:51-52` | fixedDelay 60 000 ms | **no claim** (§4 E) |

### A. Duplicate shipment creation from a redelivered order event

**Concurrent actors.** (1) the Kafka listener thread of replica 1; (2) the Kafka listener thread of
replica 2 — a consumer-group rebalance can hand the same uncommitted record to both owners
briefly; (3) the carrier's create-shipment endpoint.

**Shared resource.** The `shipments` table (one row per order, protected by the partial unique index
`uk_shipment_order_live` — `changelog-001-shipments.yaml:40`) **and** the carrier's create-shipment
endpoint, which is an external, non-transactional resource keyed by `order_id`
(`carrier/CarrierShipmentClient.java:60`).

**Dangerous interleaving.**
`T1: repository.existsByOrderId(orderId)` → false (`ShipmentServiceImpl.java:80`) →
`T2: repository.existsByOrderId(orderId)` → false → `T1: adapter.createShipment(orderId)` → the carrier
creates parcel **P1** (`ShipmentServiceImpl.java:92`) → `T2: adapter.createShipment(orderId)` → the
carrier creates parcel **P2** → `T1: writer.insert(shipment)` → OK (`:98`) → `T2: writer.insert(shipment)`
→ unique violation on `uk_shipment_order_live` → `DataIntegrityViolationException` → swallowed at
`ShipmentServiceImpl.java:101-104`.

**Violated invariant.** "At most one carrier shipment exists per order." The database invariant
survives — exactly one `shipments` row exists — but the **external** invariant does not: the carrier
holds two parcels for one order, the second tracking number is never persisted and never reaches the
customer, and the carrier is billed twice. The customer receives two deliveries for one order.

**Root cause.** The idempotency guard protects only the database write. The non-transactional side
effect (the carrier call) happens **before** the guarded write, inside a check-then-act
(`existsByOrderId` at `:80` → insert at `:98`) whose TOCTOU window is the whole carrier round-trip.
The unique index can only arbitrate the row, never the carrier call.

**Proposed fix.** Claim the order id *first*: insert the shipment row inside a transaction (status
`CREATED`, `tracking_number` null) and only then call the carrier and update the row; or send a
deterministic idempotency key to the carrier (`order_id` is already in the request body,
`CarrierShipmentClient.java:60`) and require the carrier to dedupe on it; or take a PostgreSQL
advisory lock keyed by the order id around the whole create. The first option is the smallest change
that also makes the failure recoverable.

**Reproduction test.** An IT with `MockRestServiceServer` (or a stub GHN endpoint) that counts the
create-shipment calls: publish the same `CONFIRMED` order event from two threads (or publish it twice
with the consumer group rebalancing) and assert the carrier call count.

**Verification.** Before the fix the counter is 2 and `shipments` has 1 row; after the fix the counter
is 1, `shipments` has exactly 1 row, and the persisted `tracking_number` equals the tracking number
returned by that single call. The pure-redelivery case (same record replayed after an offset-commit
failure on one replica) is **already safe** and is proven by
`ShippingFlowIT.java:125-142` and `ShipmentServiceImplTest.java:92-99` — the defect is the
*concurrent* variant. → **SHIP-01 (HIGH)**.

### B. Two concurrent carrier status updates for the same shipment

**Concurrent actors.** Two webhook request threads (the carrier can deliver two status events
concurrently), or the same event processed by the webhook path and by the retry path
(`WebhookEventServiceImpl.handle` at `:98-103` and `WebhookRetryScheduler.replayOne` at `:66-102`).

**Shared resource.** The `shipments` row (optimistic version column `Shipment.version`,
`entity/Shipment.java:72-74`), the `shipment_events` row (`ShipmentEvent.version`,
`entity/ShipmentEvent.java:59-61`) and the `outbox_events` table.

**Dangerous interleaving.** `T1` and `T2` both read the shipment with `findByTrackingNumber`
(`WebhookEventServiceImpl.java:141`, outside any transaction → a detached copy at version *v*). Both
compute a **legal** transition from the same base status (e.g. from `OUT_FOR_DELIVERY`: one event says
`DELIVERED`, the other says `DELIVERY_FAILED`; both are legal edges at `ShipmentStateMachine.java:16`
and `:19`). Both mutate their own copy (`:152-160`). `T1` commits: the merge issues
`UPDATE … WHERE id=? AND version=v`, the row count is 1, the version becomes *v+1*, and the
`shipment_events` row and the `outbox_events` row are written in the **same** transaction
(`WebhookEventWriter.java:25-32`). `T2` commits: its `UPDATE … WHERE version=v` matches 0 rows →
`OptimisticLockException` at flush → the whole transaction rolls back → `T2`'s shipment change, its
event status change and its outbox row are all discarded.

**Violated invariant.** None for the shipment: exactly one status change and exactly one
`shipping.delivered.v1` outbox row per shipment. The proof is structural: `Shipment.version` is
`@Version` (`entity/Shipment.java:72-74`) and the shipment update, the event update and the outbox
insert all happen inside one `@Transactional` boundary (`WebhookEventWriter.complete`,
`WebhookEventWriter.java:25-32`; `ShipmentWriter.saveDelivered`, `service/ShipmentWriter.java:27-32`),
so a version failure rolls the published event back with the status change.

**Root cause (of the *residual* problem).** The loser is not lost, but it is mishandled: the
`OptimisticLockException` surfaces when the loser's transaction commits, which happens inside the
`writer.complete(...)` call and is therefore caught by `WebhookEventServiceImpl.handle`'s
`catch (Exception e)` (`:108-111`) → the event is marked `FAILED_RETRYABLE` (`:110`, `:182-186`).
On the next retry the transition is recomputed against the **new** status
(`WebhookEventServiceImpl.java:153`), where it is now illegal, so the event burns its whole retry
budget (6 attempts over ≈2.7 days, `WebhookRetryScheduler.java:105-111`) before being parked as
`FAILED_PERMANENT`. A legitimate, merely-late carrier event is therefore converted into a permanent
failure.

**Proposed fix.** Make the conflict explicit instead of incidental: catch
`OptimisticLockException` separately and re-evaluate the transition against the re-read status
(idempotent re-application), or record the carrier's event sequence so that a losing writer can tell
"stale" from "conflicting".

**Reproduction test.** Two threads POST two signed webhooks for the same tracking number with
different `carrierStatus` values, both legal from the current status; assert the final status, the
`shipments.version`, and the number of `shipping.delivered.v1` outbox rows.

**Verification.** Final status equals one of the two events (never a mix), `version` advanced by
exactly 1, and exactly one delivered outbox row exists. The loser is parked `FAILED_PERMANENT`
after ~2.7 days — that is the observable defect (SHIP-09), not a data-corruption one. → **verdict:
SAFE against corruption, loser's event is mishandled.**

### C. An out-of-order delivery event arriving after a newer one

**Concurrent actors.** The carrier's status-update thread and its delivery thread (two different
webhook deliveries), or the carrier's network reordering two POSTs.

**Shared resource.** The `shipments` row, in particular `status` and `last_carrier_update`
(`entity/Shipment.java:45-57`).

**Dangerous interleaving.** `E_new` (status `DELIVERED`) is applied at *t1*; `E_old` (status
`IN_TRANSIT`, sent earlier, delivered later) is applied at *t2 > t1*. The transition is computed from
the **current** status (`WebhookEventServiceImpl.java:153`) and `E_old` carries no ordering key —
`webhook/CarrierWebhookPayload.java:14-17` has no timestamp and no sequence number, and
`shipment.setLastCarrierUpdate(now)` (`:164`) stores the **receipt** time, not the event time.

Two outcomes:
* If the newer event was `DELIVERED`: `DELIVERED → IN_TRANSIT` has no edge
  (`ShipmentStateMachine.java:12-24`) → `BusinessException` SHP-10003 → the event is parked
  `FAILED_RETRYABLE` (`WebhookEventServiceImpl.java:108-111`, `:182-186`) → **the shipment is not
  corrupted**, but the event is retried for ≈2.7 days and then parked (SHIP-09).
* If the newer event was `DELIVERY_FAILED` (the dangerous case): `DELIVERY_FAILED → IN_TRANSIT`
  **is** a legal edge (`ShipmentStateMachine.java:20`) → the late `E_old` is **applied**: the shipment
  goes back to `IN_TRANSIT`, `previous_status` becomes `DELIVERY_FAILED`, `last_carrier_update` becomes
  the receipt time. Nothing else in the module ever moves it forward again unless the carrier sends
  another event, so the reconciliation sweep eventually flips it to `DELIVERED`
  (`ReconciliationScheduler.java:55-61`) → **the order is marked DELIVERED for a parcel whose
  delivery had failed**.

**Violated invariant.** "A shipment's status never moves backwards." The FSM models a *graph* of
states, not a monotonic sequence, and the webhook path has no event ordering key.

**Root cause.** The carrier payload carries no `occurredAt`/sequence (`CarrierWebhookPayload.java:14-17`)
and the receipt time is stored in `last_carrier_update` (`WebhookEventServiceImpl.java:164`), so the
service cannot tell a stale event from a fresh one; the only ordering signal it has is the state
graph, and the graph contains the backward edge `DELIVERY_FAILED → IN_TRANSIT`
(`ShipmentStateMachine.java:20`).

**Proposed fix.** Add `occurredAt` (and ideally a monotonic sequence) to the carrier payload and to
`ShipmentEvent`; reject an event whose carrier timestamp is older than the stored
`last_carrier_update`; and restrict `DELIVERY_FAILED → IN_TRANSIT` to the operator retry path only
(`ShipmentServiceImpl.retry`, `:172-173`).

**Reproduction test.** An IT that posts `DELIVERY_FAILED` and then an older `IN_TRANSIT` event for the
same tracking number, then advances the clock past `auto-deliver-days` and runs the sweep.

**Verification.** Before the fix the shipment ends in `DELIVERED` with `auto_delivered = true`; after
the fix it stays `DELIVERY_FAILED` and no delivered outbox row is written. → **SHIP-06 (HIGH)**.

### D. The reconciliation scheduler running on two replicas

**Concurrent actors.** Replica A's scheduler thread and replica B's scheduler thread (both fire the
hourly cron; there is no leader election).

**Shared resource.** The stale `shipments` rows selected by
`ShipmentRepository.findStaleAcknowledgedShipments` (`repository/ShipmentRepository.java:40-44`) and the
`outbox_events` table.

**Dangerous interleaving.** `A: findStaleAcknowledgedShipments(...)` → list L (no lock, no claim) →
`B: findStaleAcknowledgedShipments(...)` → the same list L → both mutate their **detached** copies to
`DELIVERED` (`ReconciliationScheduler.java:56-59`) → `A: writer.saveDelivered(shipment, true)`
(`:61`) commits: the merge's `UPDATE … WHERE version=v` succeeds (v → v+1) and the
`shipping.delivered.v1` outbox row is inserted **in the same transaction**
(`service/ShipmentWriter.java:27-32` → `outbox/ShippingEventPublisherImpl.java:62`) →
`B: writer.saveDelivered(...)` commits: version check fails → `OptimisticLockException` at commit →
B's transaction (shipment update **and** outbox insert) rolls back.

**Violated invariant.** None on the data: exactly one status change and exactly one
`shipping.delivered.v1` event per shipment — proven by the `@Version` column
(`entity/Shipment.java:72-74`) plus the single transaction that couples the shipment write and the
outbox insert (`ShipmentWriter.java:27-32`). **Two defects remain:**

1. The loser throws. `ReconciliationScheduler.reconcile` has no try/catch around the loop
   (`ReconciliationScheduler.java:55-62`), so the `OptimisticLockException` escapes the `@Scheduled`
   method → Spring logs "Unexpected error occurred in scheduled task" → **the rest of replica B's
   batch is not processed in that tick**. The remaining stale shipments are only closed out by
   whichever replica wins each row.
2. The counter over-counts. `metrics.recordDelivered(true)` runs **before** the write
   (`ReconciliationScheduler.java:60` before `:61`), so `shipping.delivered.count{auto="true"}` is
   incremented even for the replica whose commit was rolled back — the metric reports more
   auto-deliveries than shipments that were actually delivered.

**Root cause.** Two writers, one read-only query with no claim, and a metric side effect placed
before the durable write; no leader election (no ShedLock in the repo) to stop the second replica
from doing the same work.

**Proposed fix.** Either add a scheduler lock (ShedLock) so only one replica sweeps, or make the
sweep idempotent per row (claim the row with a conditional UPDATE, or catch
`OptimisticLockException` per row and continue with the next shipment) and move the metric
increment after the successful write.

**Reproduction test.** Two threads (or two application contexts) calling `reconcile()` on the same
stale shipment; assert the final status, `version`, the number of `shipping.delivered.v1` outbox
rows, and the value of `shipping.delivered.count{auto="true"}`.

**Verification.** Before the fix: one outbox row (correct) but the counter reads 2, and the
`OptimisticLockException` aborts the batch. After the fix: one outbox row, counter reads 1, no
exception escapes. → **SHIP-20 (MEDIUM, metric) / SHIP-21 (MEDIUM, batch abort)**.

### E. The webhook retry scheduler claiming the same event twice

**Concurrent actors.** Replica A's `WebhookRetryScheduler` thread and replica B's — the same
`@Scheduled` method on every replica, fixedDelay 60 s.

**Shared resource.** The `shipment_events` row (status `FAILED_RETRYABLE`, `next_retry_at`,
`retry_count`, `version`) and the target `shipments` row.

**Dangerous interleaving.** `A: eventRepository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(...)`
(`scheduler/WebhookRetryScheduler.java:54-56` → `repository/ShipmentEventRepository.java:26-27`) —
a **plain read, no `@Lock`, no claim marker, no status transition** — returns the same event to both
replicas. `A` and `B` both call `webhookEventService.retry(event)` (`:72`), which re-parses the stored
payload and calls `process(payload, event)`
(`service/impls/WebhookEventServiceImpl.java:114-138`) → both mutate their copies of the shipment
(`:162-171`) and of the event (`:173-174`) → `A` commits (both version checks pass) → `B` commits →
**both** version checks fail → `OptimisticLockException`.

**Violated invariant.** "Every failed attempt advances the retry counter exactly once, and a poison
event reaches `FAILED_PERMANENT`." The shipment state is *not* corrupted (B's transaction rolls back
atomically — `WebhookEventWriter.complete` is one transaction, `WebhookEventWriter.java:25-32`), but
the retry accounting is broken: the `OptimisticLockException` is raised **at commit time**, i.e.
*after* the `transactionTemplate.execute(...)` callback has returned, so it is **not** caught by the
`catch (Exception e)` inside the callback (`WebhookRetryScheduler.java:86-100`). The exception escapes
`replayOne` → escapes `replay()` → escapes the `@Scheduled` method → Spring logs it → the remaining
events in that batch are skipped, `retry_count` is never advanced, `next_retry_at` is never pushed
forward, and the event is retried **every 60 seconds forever** — it can never reach
`FAILED_PERMANENT`, and the dead-letter path is never reached for that event.

**Root cause.** The retry queue has no claim/lease: "claiming" is done by *reading*. The only
concurrency control is the `@Version` of the rows the worker happens to write, and that control
fires at commit — too late for the retry accounting, which lives inside the transaction callback.

**Proposed fix.** Claim the event atomically before processing, e.g.
`UPDATE shipment_events SET status='IN_PROGRESS', claimed_at=now() WHERE id=? AND status='FAILED_RETRYABLE'`
(affected-row check) or `SELECT … FOR UPDATE SKIP LOCKED` with a claim-lease column and a recovery
sweep for stale claims; and move the retry accounting (or wrap the whole callback) so that
commit-time failures are counted too.

**Reproduction test.** Two threads call `replayOne(event)` on the same `ShipmentEvent` instance
(mocking a commit-time `OptimisticLockException` on the second save), or two application contexts race
the scheduler; assert `retry_count`, `next_retry_at` and the final status.

**Verification.** Before the fix: `retry_count` stays 0, the event is still `FAILED_RETRYABLE`, the
exception escapes, and the row is re-selected on the next tick. After the fix: `retry_count` advances
by exactly one per attempt and the event reaches `FAILED_PERMANENT` after `MAX_ATTEMPTS`. →
**SHIP-07 (MEDIUM)**.

### F. The outbox relay claiming rows with a minimum-identifier subquery under a skip-locked read

**Concurrent actors.** Replica A's relay thread and replica B's relay thread (fixedDelay 2 s, no
leader election).

**Shared resource.** The `outbox_events` rows and the Kafka topic `shop.shipping.lifecycle.v1`.

**Dangerous interleaving — safety.** `A: claimOnePending(PENDING)`
(`outbox/OutboxEventRepository.java:35-39`: `@Lock(PESSIMISTIC_WRITE)` + `lock.timeout = -2`
= `FOR UPDATE SKIP LOCKED`, with the predicate `e.id = (SELECT MIN(e2.id) …)`) → A locks the head row
→ in the **same transaction** A moves it to `SENDING`, stamps `claimed_at` and commits
(`ShippingOutboxRelay.java:111-122`) → `B: claimOnePending(PENDING)` → the head row is locked → SKIP
LOCKED drops it → **B gets `Optional.empty()`** → B's drain loop breaks (`:123-125`). The module's own
integration test proves this against a real PostgreSQL:
`outbox/ShippingOutboxClaimConcurrencyIT.java:106` (`assertThat(claimBWhileLocked.get()).isEmpty()`)
and `:107` (after A commits, B gets the same row id because the *test* only selects, it does not
flip the status).

**Dangerous interleaving — liveness.** The `MIN(id)` subquery is evaluated **without** the
skip-locked semantics: it still points at the locked head row even though the outer query cannot
lock it. So while the lowest-id `PENDING` row is locked, `claimOnePending` returns **zero rows even
if thousands of other rows are claimable** — the relay is blocked behind the head of the queue. The
same test documents it: `ShippingOutboxClaimConcurrencyIT.java:169` asserts that row 2 is still
`PENDING` while row 1 is locked. In production, `A` holds the head lock only for the length of one
small transaction, so the window is short — but every relay tick in which *any* replica wins the head
row means **all other replicas publish nothing** (`ShippingOutboxRelay.java:123-125` breaks the whole
drain on the first empty claim). Effective relay parallelism is 1× regardless of the replica count.

**Violated invariant.** "No outbox row is published twice" — **holds** (the claim and the
`SENDING` transition are one transaction, `ShippingOutboxRelay.java:111-122`).
"The relay drains the pending backlog" — **violated** (head-of-line blocking).

**Root cause.** The `MIN(id)` subquery is a lock-unaware way of writing `ORDER BY id LIMIT 1`.
`FOR UPDATE SKIP LOCKED` applies to the outer row only; the sub-select still sees the locked row, so
the outer predicate can only ever match that one row.

**Proposed fix.** Replace the JPQL with a native query:
`SELECT * FROM outbox_events WHERE status = 'PENDING' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1`.
Optionally stop the drain loop on the first empty claim only when the queue is genuinely empty.

**Reproduction test.** Extend `ShippingOutboxClaimConcurrencyIT` with a case that locks row 1 and
asserts that a second claim returns **row 2** (today it returns empty).

**Verification.** Before the fix the second claim is empty and row 2 stays `PENDING`; after the fix
the second claim returns row 2 and the backlog drains. → **SHIP-08 (MEDIUM, liveness)**.

**Related defect in the same relay (mark phase).** `markFinalState` re-reads the row with
`findByIdForUpdate` (`OutboxEventRepository.java:55-58`), which is **also** `SKIP LOCKED`. If the row
happens to be locked at that moment, the query returns empty and the method returns without marking
(`ShippingOutboxRelay.java:199-205`) → the row stays `SENDING` → after the claim lease expires the
recovery sweep returns it to `PENDING` (`ShippingOutboxRelay.java:184-197`) → **the event is published
a second time**. That is at-least-once delivery (the event was already sent to Kafka before the mark),
but the downstream `order-service` then sees two `shipping.delivered.v1` events for one shipment. →
**SHIP-16 (MEDIUM)**.

---

## 5. SINGLETON AND SHARED MUTABLE STATE

Every Spring bean in this module is a singleton; the table below is the complete census of mutable
state reachable by more than one thread.

### 5.1 Mutable instance fields

| Field | File:line | Mutability | Assessment |
|---|---|---|---|
| `ShippingOutboxRelay.batchSize` | `outbox/ShippingOutboxRelay.java:69-70` | `@Value` injected, non-final, non-volatile | Written once by the container during refresh, read by the scheduler thread. The Spring bean registry's publication gives a happens-before edge, so this is *benign* — but a non-final `@Value` field is a smell; it should be `final` via constructor binding. |
| `ShippingOutboxRelay.maxRetries` | `ShippingOutboxRelay.java:72-73` | same | same |
| `ShippingOutboxRelay.publishTimeoutMs` | `ShippingOutboxRelay.java:75-76` | same | same |
| `ShippingOutboxRelay.claimLeaseMs` | `ShippingOutboxRelay.java:83-84` | same | same |
| `WebhookRetryScheduler.batchSize` | `scheduler/WebhookRetryScheduler.java:38-39` | same | same |
| `ShippingWebhookProperties.secrets` | `config/ShippingWebhookProperties.java:11` | a **mutable `HashMap`** on a singleton, exposed **by reference** through `getSecrets()` (`:13-15`) and `setSecrets()` (`:17-19`) | Any holder of the properties bean can mutate the live secret map. It is only written during `@ConfigurationProperties` binding, so today it is effectively write-once — but the exposure is unnecessary and the bean is publishable to `/actuator/configprops`. **SHIP-25 (LOW)** |
| `CarrierFactory.carriers` | `carrier/CarrierFactory.java:16` | a `ConcurrentHashMap` | Mutated **only** in the constructor (`:19-35`); never mutated afterwards → effectively immutable after construction ✓. `getAllCarriers()` (`:56-58`) returns `Collections.unmodifiableMap(carriers)` — an unmodifiable **view** of the live map, not a snapshot; iteration over it is weakly consistent (safe with `ConcurrentHashMap`) ✓. |
| `ShippingMetrics.staleInflightCount` | `service/ShippingMetrics.java:14` | an `AtomicInteger` | Correctly thread-safe ✓. The gauge is registered once in the constructor (`:18-19`) ✓. |

Every other field in the module is `final`: `ShipmentServiceImpl:36-41`, `WebhookEventServiceImpl:50-55`,
`ShipmentWriter:14-15`, `WebhookEventWriter:16-18`, `ReconciliationScheduler:29-33`,
`CarrierShipmentClient:18-19`, `ShippingEventPublisherImpl:26-27`, `GhnCarrierAdapter:33-37`,
`GhtkCarrierAdapter:30-33`, `WebhookRetryScheduler:33-36`.

### 5.2 Static mutable state

| Symbol | File:line | Assessment |
|---|---|---|
| `ShipmentStateMachine.LEGAL_TRANSITIONS` | `service/ShipmentStateMachine.java:12-24` | `static final Set.of(...)` — immutable ✓ |
| `ReconciliationScheduler.IN_FLIGHT` | `scheduler/ReconciliationScheduler.java:25-27` | `static final` + `Collectors.toUnmodifiableSet()` — immutable ✓ |
| `ShipmentEvent.STATUS_*` (4 constants) | `entity/ShipmentEvent.java:30-34` | `public static final String` — **stringly-typed** state persisted into a `varchar(16)` with a DB CHECK (`changelog-001-shipments.yaml:63`, widened by `changelog-004-expand-events-status-check.yaml:16-19`). An enum would make the four states type-safe; today a typo is only caught by the CHECK constraint at runtime. **SHIP-28 (LOW)** |
| `ShippingEventPublisherImpl` constants | `outbox/ShippingEventPublisherImpl.java:22-24` | immutable ✓ |
| static caches / `SimpleDateFormat` / `DateTimeFormatter` | — | **None exist in this module.** |

### 5.3 Shared collections, cached request state, non-thread-safe collaborators

* **Shared collections:** only `CarrierFactory.carriers` (ConcurrentHashMap ✓) and
  `ShippingWebhookProperties.secrets` (HashMap, exposed by reference — SHIP-25).
  `OutboxBatchOrdering.markableAsSent` (shared library) builds a **local** `HashMap` per call
  (`utils/common-kafka/.../OutboxBatchOrdering.java:44`) ✓.
* **Cached request state:** **none**. No field in the module holds per-request data; the webhook body
  is a method-local `byte[]` (`webhook/CarrierWebhookController.java:26`) and the parsed payload is a
  local (`WebhookEventServiceImpl.java:60-67`). No sticky/last-request cache exists.
* **Non-thread-safe collaborators:** **none found.** `RestClient` and `ObjectMapper` are immutable
  and thread-safe once constructed (`CarrierShipmentClient.java:18-24`,
  `ShippingEventPublisherImpl.java:27`); `Mac` is instantiated per call
  (`webhook/WebhookSignatureVerifier.java:27`), `MessageDigest` per call
  (`WebhookEventServiceImpl.java:217`), `HexFormat.of()` is immutable.
* **Date/number formatting helpers:** there are **no** date or number formatters in the module. The
  three `truncate` helpers — `scheduler/WebhookRetryScheduler.java:113-116`,
  `service/impls/WebhookEventServiceImpl.java:241-244`,
  `outbox/ShippingOutboxRelay.java:243-250` — are `static`, pure and stateless ✓. The only
  timestamp rendering is `Instant.now().toString()` (`ShippingEventPublisherImpl.java:51`), which is
  thread-safe ✓. **This is a genuine positive finding: the classic `SimpleDateFormat` singleton
  hazard is absent.**

### 5.4 Scheduled methods (4)

| Method | File:line | Schedule | Runs on every replica? | Guard |
|---|---|---|---|---|
| `ShippingOutboxRelay.relay` | `outbox/ShippingOutboxRelay.java:97-120` | fixedDelay 2 000 ms, initialDelay 2 000 ms | yes | DB claim (`OutboxEventRepository.java:35-39`) — safe, but see the `MIN(id)` liveness defect (§4 F) |
| `WebhookRetryScheduler.replay` | `scheduler/WebhookRetryScheduler.java:51-64` | fixedDelay 60 000 ms | yes | **none** — no claim (§4 E) |
| `ReconciliationScheduler.reconcile` | `scheduler/ReconciliationScheduler.java:45-63` | cron `0 0 * * * *` | yes | `@Version` only; no leader election (§4 D) |
| `ShippingOutboxRetentionScheduler.purgeOldSentEvents` | `outbox/ShippingOutboxRetentionScheduler.java:20-32` | cron `0 0 3 * * *` | yes | idempotent bulk delete; benign but unbounded (**SHIP-26 LOW**) |

No `@Scheduled` method in the module holds a lock, uses ShedLock (absent from the whole repository),
or checks a lease before doing work — except the relay's claim.

### 5.5 Check-then-act patterns

| # | Site | Guard |
|---|---|---|
| 1 | `ShipmentServiceImpl.createShipment`: `existsByOrderId` (`:80`) → `adapter.createShipment` (`:92`) → `writer.insert` (`:98`) | The **row** is protected by `uk_shipment_order_live`; the **carrier call is not** (§4 A, SHIP-01). |
| 2 | `WebhookEventServiceImpl.handle`: `findFirstByCarrierAndProviderEventId` (`:77-78`) → `writer.insert` (`:96`) | Protected by `uk_shipment_events_carrier_event` ✓; the catch at `:97-101` also swallows a NOT NULL violation (SHIP-14). |
| 3 | `WebhookEventServiceImpl.persistUnparseableEvent`: `findFirst...` (`:198-199`) → `writer.insert` (`:204`) | Same unique index ✓ |
| 4 | `WebhookEventServiceImpl.handle` dedupe of a PROCESSED event (`:77-82`) | Read-then-decide; a concurrent duplicate is arbitrated by the unique index, and the loser returns without processing ✓ — exactly-once **for the same `providerEventId`**. |
| 5 | `WebhookRetryScheduler.replay` → `replayOne` (`:53-63`, `:66-103`) | **No claim** — the row is *read*, never marked in flight (§4 E, SHIP-07). |
| 6 | `ShippingOutboxRelay.markFinalState`: `findByIdForUpdate` → status check → save (`:199-224`) | Properly locked ✓ — but the lock is `SKIP LOCKED`, so a lock conflict silently skips the mark (SHIP-16). |
| 7 | `ReconciliationScheduler.reconcile`: `findStaleAcknowledgedShipments` → mutate → save (`:48-62`) | Guarded only by `@Version`; the loser throws and aborts the batch (§4 D, SHIP-21). |
| 8 | `CarrierConfig.primary`: `all.get(0)` fallback (`:26`) | The "first" adapter depends on Spring's injection order — not a contract. Unreachable in production (an unknown property value registers zero adapters and fails the startup), but it is a fragile fallback. |

---

## 6. TRANSACTIONS AND DATABASE INTEGRITY

### 6.1 Transaction boundaries

| Boundary | File:line | Propagation | What it makes atomic |
|---|---|---|---|
| `ShipmentWriter.insert` | `service/ShipmentWriter.java:17-20` | `@Transactional` (REQUIRED) | the shipment row insert (and the `DataIntegrityViolationException` it raises) |
| `ShipmentWriter.save` | `ShipmentWriter.java:22-25` | `@Transactional` | the shipment update (with its `@Version` check) |
| `ShipmentWriter.saveDelivered` | `ShipmentWriter.java:27-32` | `@Transactional` | **the status change AND the `shipping.delivered.v1` outbox insert** — this is the coupling that makes the double-replica sweep safe (§4 D) |
| `WebhookEventWriter.insert` | `service/WebhookEventWriter.java:20-23` | `@Transactional` | the webhook-event insert (the idempotency claim) |
| `WebhookEventWriter.complete` | `WebhookEventWriter.java:25-32` | `@Transactional` | **the shipment update + the event update + the outbox insert** — the coupling that makes concurrent carrier updates safe (§4 B) |
| `WebhookEventWriter.saveEvent` | `WebhookEventWriter.java:34-37` | `@Transactional` | the event status/error update |
| `ShippingOutboxRetentionScheduler.purgeOldSentEvents` | `outbox/ShippingOutboxRetentionScheduler.java:20-32` | `@Transactional` | one unbounded bulk delete |
| Outbox relay claim / mark / reclaim | `outbox/ShippingOutboxRelay.java:111-122`, `:164-169`, `:189-192` | `TransactionTemplate` (one transaction per row) | claim+status flip, mark+CAS, bulk reclaim |
| Webhook retry | `scheduler/WebhookRetryScheduler.java:66-103` | `TransactionTemplate` (one transaction per event) | the retry attempt — but the failure accounting sits **inside** the callback, so commit-time failures escape it (§4 E) |

**`ShipmentServiceImpl` and `WebhookEventServiceImpl` are deliberately *not* `@Transactional`.** Their
multi-step work is therefore split across several transactions: `ShipmentServiceImpl.createShipment`
performs the carrier call outside any transaction and the insert in its own
(`ShipmentServiceImpl.java:92-101`); `WebhookEventServiceImpl.handle` inserts the event in one
transaction and applies the status change in another (`WebhookEventServiceImpl.java:96`, `:175`).
The composite write that matters is delegated to `ShipmentWriter` / `WebhookEventWriter`, which is the
right factoring — but it is not obvious from the service layer, and `ShipmentServiceImpl.cancelShipment`
(`:104-118`) mutates a detached entity and saves it in a separate transaction, so the
`previous_status`/`status` pair is written by a single merge ✓.

### 6.2 Unique constraints

| Constraint | Definition | Protects |
|---|---|---|
| `uk_shipment_order_live` | `CREATE UNIQUE INDEX uk_shipment_order_live ON shipments (order_id) WHERE deleted = false` (`changelog-001-shipments.yaml:40`) | one live shipment per order — the real guard behind `ShipmentServiceImpl.createShipment` |
| `uk_shipments_tracking_live` | `CREATE UNIQUE INDEX uk_shipments_tracking_live ON shipments (tracking_number) WHERE tracking_number IS NOT NULL AND deleted = false` (`changelog-001-shipments.yaml:84`) | no two shipments may share a tracking number |
| `uk_shipment_events_carrier_event` | `CREATE UNIQUE INDEX uk_shipment_events_carrier_event ON shipment_events (carrier, provider_event_id)` (`changelog-001-shipments.yaml:77`) | the webhook idempotency store — one row per (carrier, provider event id) |
| `outbox_events.event_id UNIQUE` | `changelog-002-shipping-outbox.yaml:17` | one outbox row per event id |

Note the asymmetry: `uk_shipment_order_live` and `uk_shipments_tracking_live` are **partial**
(`WHERE deleted = false`), while `uk_shipment_events_carrier_event` is **not** — a soft-deleted
webhook event still blocks the re-insert of the same provider event id.

### 6.3 Check constraints (the database's second state machine)

* `ck_shipments_carrier` — `carrier IN ('MANUAL','NOOP','GHN','GHTK')` (`changelog-001-shipments.yaml:34`)
* `ck_shipments_status` — `status IN ('CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','DELIVERY_FAILED','CANCELLED')` (`changelog-001-shipments.yaml:37`)
* `ck_shipment_events_status` — originally `('PROCESSED','FAILED')` (`changelog-001-shipments.yaml:74`),
  widened to `('PROCESSED','FAILED','FAILED_RETRYABLE','FAILED_PERMANENT')` by
  `changelog-004-expand-events-status-check.yaml:16-19`

The status CHECK constraint is a **value** check, not a **transition** check — the database cannot
prevent `DELIVERED → IN_TRANSIT`; only the FSM can.

### 6.4 Optimistic locking

* `shipments.version` — `@Version` (`entity/Shipment.java:72-74`), column `bigint NOT NULL DEFAULT 0`
  (`changelog-001-shipments.yaml:24`). Every status write goes through a merge, so a concurrent
  writer loses with an `OptimisticLockException`. This is the mechanism that protects §4 B and §4 D.
* `shipment_events.version` — `@Version` (`entity/ShipmentEvent.java:59-61`), `bigint NOT NULL DEFAULT 0`
  (`changelog-001-shipments.yaml:64`).
* `outbox_events` has **no** version column — its CAS is done with an explicit
  `PESSIMISTIC_WRITE` re-read (`OutboxEventRepository.java:55-58`, `ShippingOutboxRelay.java:202`).

### 6.5 Foreign keys

**There are none.** `shipment_events.shipment_id` is a plain `uuid` column with no FK
(`changelog-001-shipments.yaml:58`, `entity/ShipmentEvent.java:40-41`); `outbox_events.aggregate_id`
has no FK (`changelog-002-shipping-outbox.yaml:19`); `shipments.order_id` has no FK (a cross-service
reference to `order-service`, correctly not enforced locally). Consequence: webhook events can
outlive their shipment and are never cascaded or pruned (**SHIP-13, MEDIUM**).

### 6.6 Liquibase — every changeset id, and is it additive and safe?

Master: `db/changelog/db.changelog-master.yaml:1-19` includes the six files in order.

| # | File | changeSet id | Type | Additive? | Safe on an existing DB? |
|---|---|---|---|---|---|
| 1 | `changelog-001-shipments.yaml:3-44` | `001-create-shipments` | createTable `shipments` + 2 CHECKs + 2 indexes | base schema | ✗ **not re-runnable** (`CREATE TABLE`, `ALTER TABLE … ADD CONSTRAINT`, `CREATE UNIQUE INDEX` are not idempotent) — it is a one-shot changeset, fine for a Liquibase-managed DB, but it **fails** if the table already exists outside Liquibase, and `CREATE UNIQUE INDEX uk_shipment_order_live` **fails if duplicate live orders already exist**. No `preConditions` guard. |
| 2 | `changelog-001-shipments.yaml:45-78` | `002-create-shipment-events` | createTable `shipment_events` + CHECK + unique index | base schema | same as above |
| 3 | `changelog-001-shipments.yaml:79-84` | `003-unique-tracking-number-live` | unique index on `tracking_number` | additive index | ✗ fails if two live shipments already share a tracking number (no pre-condition) |
| 4 | `changelog-002-shipping-outbox.yaml:3-43` | `002-create-shipping-outbox-events` | createTable `outbox_events` + 3 indexes | base schema | one-shot |
| 5 | `changelog-003-webhook-retry.yaml:5-29` | `003-webhook-retry-fields` | addColumn ×3 + **backfill UPDATE** | additive columns ✓ | ✗ **the backfill violates the still-narrow CHECK constraint.** At this point in the chain `ck_shipment_events_status` still allows only `('PROCESSED','FAILED')` (`changelog-001-shipments.yaml:74`); the constraint is only widened by changeset `004`. So `UPDATE shipment_events SET status='FAILED_RETRYABLE' WHERE status='FAILED'` (`:24-29`) raises a check-constraint violation **on any database that already holds a FAILED row** → the changeset fails → Liquibase aborts → `004`, `005` and `006` never run → `shipments.carrier_acknowledged_at` and `outbox_events.claimed_at` are never created → with `ddl-auto: validate` (`application.yml:13`) the service refuses to start. The module's own comment acknowledges it: `changelog-004-expand-events-status-check.yaml:2-9` ("the 003 backfill UPDATE violates it too"). **SHIP-04 (HIGH)** |
| 6 | `changelog-004-expand-events-status-check.yaml:10-26` | `004-expand-events-status-check` | DROP+ADD the CHECK + idempotent backfill | ✓ additive & idempotent | ✓ safe — the widened constraint still admits every pre-existing value (`PROCESSED`, `FAILED`), and the re-run of the backfill matches only rows still `FAILED` |
| 7 | `changelog-005-carrier-acknowledged-at.yaml:8-21` | `005-carrier-acknowledged-at` | `ADD COLUMN IF NOT EXISTS` + backfill | ✓ additive & idempotent | ✓ safe |
| 8 | `006-shipping-outbox-claim-lease.yaml:7-13` | `006-shipping-outbox-claim-lease` | `ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` | ✓ additive & idempotent | ✓ safe |

**Summary:** changesets 004–006 are additive and idempotent; 001–003 are one-shot schema creations
without pre-conditions; **003 is broken against an existing database with FAILED rows** and it blocks
every later changeset.

**Two schema issues that are not about Liquibase at all:**

* `Instant` columns typed `timestamp` (without time zone) next to `timestamptz` siblings:
  `outbox_events.sent_at` (`changelog-002-shipping-outbox.yaml:25`) and
  `shipment_events.next_retry_at` (`changelog-003-webhook-retry.yaml:20`) are `timestamp`, while
  `shipments.created_at`/`updated_at` and `shipment_events.created_at`/`updated_at` are `timestamptz`
  (`changelog-001-shipments.yaml:25-26`, `:65-66`) — for the *same* `java.time.Instant` type
  (`entity/OutboxEvent.java:45-46`, `entity/ShipmentEvent.java:67-68`). Hibernate 6 maps `Instant` to
  `TIMESTAMP_UTC` (`timestamp(6) with time zone`) on PostgreSQL, and `ddl-auto: validate`
  (`application.yml:13`) compares column types, so the mismatch is a start-up risk. **It cannot be
  confirmed without running the service** (no Maven build was permitted for this review), and the
  module's own integration tests never exercise the validation path — they set
  `spring.jpa.hibernate.ddl-auto=none` (`test/.../support/AbstractIntegrationTest.java:41`). The
  same `timestamp`-for-`Instant` pattern exists in the sibling services' outbox tables, so if it is a
  defect it is platform-wide. **SHIP-10 (MEDIUM)**.
* No index supports the two hot polling queries: `shipment_events (status, next_retry_at)` (polled
  every 60 s by `WebhookRetryScheduler.java:54-56`) and `shipments (carrier_acknowledged_at)` (the
  hourly sweep's `COALESCE(last_carrier_update, carrier_acknowledged_at)` predicate cannot use the
  existing `idx_shipments_status_stale` on `(status, last_carrier_update)`,
  `changelog-001-shipments.yaml:43`). The sibling `notification-service` already has
  `idx_notifications_status_next_retry` — the shipping equivalent is missing.
  **SHIP-11 (MEDIUM)**, **SHIP-27 (LOW)**.

---

## 7. WEBHOOK HANDLING

### 7.1 The endpoint

`webhook/CarrierWebhookController.java:22-29` — `POST /api/v1/webhooks/shipping/{carrier}`
(`ApiPaths.WEBHOOK_SHIPPING` = `/api/v1/webhooks/shipping`, `utils/common-core/.../ApiPaths.java:57`),
`consumes = application/json`, the body read as **raw bytes** (`:26`) and the signature taken from the
optional `X-Webhook-Signature` header (`:25`). The path is public
(`application.yml:47-48` → `/api/v1/webhooks/shipping/**`), so **the HMAC signature is the only
authentication** — which makes the signature check the single most security-critical method in the
module.

### 7.2 Signature verification — correct

`webhook/WebhookSignatureVerifier.java:18-34`:

```java
public static boolean verify(String secret, byte[] rawBody, String signatureHeader) {
    if (secret == null || secret.isBlank() || rawBody == null) {
        return false;
    }
    if (signatureHeader == null || signatureHeader.length() != SHA256_HEX_LENGTH) {
        return false;
    }
    try {
        byte[] provided = HexFormat.of().parseHex(signatureHeader);
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
        byte[] expected = mac.doFinal(rawBody);
        return MessageDigest.isEqual(expected, provided);
    } catch (RuntimeException | GeneralSecurityException e) {
        return false;
    }
}
```

Positives: HMAC-SHA256 over the **raw** body (no re-serialisation, so no canonicalisation
ambiguity); a fixed 64-hex length check (`:22-24`); a **constant-time** comparison via
`MessageDigest.isEqual` (`:30`) — no timing side channel; every failure mode returns `false` instead
of throwing (`:31-33`). The secret is resolved per carrier and a missing/blank secret is rejected
before the comparison (`WebhookEventServiceImpl.java:61-64`), so an unconfigured carrier cannot be
"verified" with an empty key. Covered by
`webhook/WebhookSignatureVerifierTest.java:30-100` (tampered body, wrong secret, null/blank/short/
prefixed/non-hex headers, uppercase hex accepted).

### 7.3 Replay window — **SEC-09 is CONFIRMED**

**There is no replay window.** The signature covers the body and nothing else:
`WebhookSignatureVerifier.verify` takes `(secret, rawBody, signatureHeader)` and has **no timestamp,
nonce, sequence or expiry** parameter (`WebhookSignatureVerifier.java:18`), and the wire payload has
no time field either — `webhook/CarrierWebhookPayload.java:14-17` carries only `eventId`,
`eventType`, `trackingNumber`, `carrierStatus`. A captured request is therefore replayable at any
time, indefinitely; the HMAC stays valid forever because it is a pure function of (secret, body).

**Impact, bounded by the idempotency store.** The replayed body carries the same `providerEventId`,
and a `PROCESSED` event is an explicit acked no-op (`WebhookEventServiceImpl.java:79-82`;
`WebhookEventServiceTest.java:198-209`; end-to-end proof in `ShippingFlowIT.java:200-226`). So a
replay of an *already-processed* event changes no state. Three residual exposures remain:

1. A replay of an event that is still `FAILED_RETRYABLE` re-drives processing **immediately**,
   bypassing the exponential backoff (`WebhookEventServiceImpl.java:108-111` → the existing row is
   reused and re-processed).
2. A replay keeps re-inserting/refreshing work for the same `providerEventId`; nothing expires —
   `shipment_events` has **no retention** (unlike `outbox_events`, which is purged after 7 days,
   `ShippingOutboxRetentionScheduler.java:22-32`).
3. There is no replay-window *detection* — the service cannot distinguish a carrier's genuine retry
   from an attacker's replay.

**Verdict:** the SEC-09 claim ("the shipping webhook verifier has no replay window") is **confirmed**
against the source at `webhook/WebhookSignatureVerifier.java:18-34` and
`webhook/CarrierWebhookPayload.java:14-17`. The severity is reduced — not eliminated — by the
idempotency store. The fix is a signed timestamp (`t` field inside the signed body, or an
`X-Webhook-Timestamp` header included in the MAC) plus a ±5-minute tolerance and a `providerEventId`
uniqueness window.

### 7.4 The idempotency store

The `shipment_events` table with the unique index
`uk_shipment_events_carrier_event (carrier, provider_event_id)` (`changelog-001-shipments.yaml:77`)
is the store. The flow in `service/impls/WebhookEventServiceImpl.handle` (`:59-112`):

1. Resolve the carrier (`Carrier.valueOf`, `:60`, `:188-194`) — an unknown carrier is a 401.
2. Look up the secret and verify the signature (`:61-65`) — a failure is a 401 and **persists nothing**
   (`WebhookEventServiceTest.java:303-312`).
3. Parse the body (`:67-74`); an unparseable body is persisted with the synthetic id
   `unparseable-<sha256(body)>` (`:196-223`) so a repeated garbage body dedupes
   (`WebhookEventServiceTest.java:348-360`).
4. Look up `(carrier, providerEventId)` (`:77-78`); if `PROCESSED` → ack no-op (`:79-82`).
5. Otherwise insert a `FAILED_RETRYABLE` row with the raw payload (`:85-96`); a concurrent insert of
   the same key loses the unique index and is acked as a no-op (`:97-101`).
6. Apply the transition and mark the event `PROCESSED` (`:107`, `:173-175`).

Gaps: (a) a **null** `providerEventId` (the payload has no validation,
`CarrierWebhookPayload.java:14`) makes the insert violate `NOT NULL`, and the resulting
`DataIntegrityViolationException` is caught by the "raced a concurrent insert" branch
(`:97-101`) → the signed-but-malformed webhook is **acked 200 and silently dropped** with a
misleading log line — **SHIP-14 (MEDIUM)**; (b) no TTL/retention on the store.

### 7.5 The retry mechanism

`scheduler/WebhookRetryScheduler.java`. Every event starts as `FAILED_RETRYABLE` with
`next_retry_at = now` (`WebhookEventServiceImpl.java:91-93`) and is flipped to `PROCESSED` inside
`process()` on success (`:164`). The scheduler selects
`status = 'FAILED_RETRYABLE' AND next_retry_at <= now ORDER BY next_retry_at` with a page size of 50
(`ShipmentEventRepository.java:26-27`, `WebhookRetryScheduler.java:54-56`) and processes each event in
its own transaction (`:66-103`).

The backoff is `300 · 5^(attempt-1)` seconds (`WebhookRetryScheduler.java:105-111`): 5 min → 25 min →
2 h 5 min → 10 h 25 min → 52 h; the sixth attempt is the last (`MAX_ATTEMPTS = 6`, `:30`). So a poison
event occupies the retry queue for **≈2.7 days** and is retried six times, even when the failure is
*permanent* (unknown tracking number `:142-147`, illegal transition `:151-158`, unparseable status
`:146-148`). `WebhookEventServiceImpl.retry` rethrows everything except an unparseable payload as a
bare `RuntimeException` (`:135-137`), so the scheduler cannot classify permanent vs transient.
**SHIP-09 (MEDIUM)**.

### 7.6 Dead-letter behaviour

`FAILED_PERMANENT` (`entity/ShipmentEvent.java:34`) is the terminal state, set when the retry budget
is exhausted (`:68-72`) or when the failing attempt reaches `MAX_ATTEMPTS` (`:90-95`). It **is
observable**: the Micrometer counter `shipment_webhook_failed_permanent_total`
(`WebhookRetryScheduler.java:48`, incremented at `:72` and `:92`; asserted in
`WebhookRetrySchedulerTest.java:84-85`) — a genuine positive finding, and the only dead-letter
*signal* in the module.

What is missing: there is **no dead-letter topic and no redrive path** for `FAILED_PERMANENT` rows —
no API, no scheduler, no retention job; the rows sit in the table forever, reachable only by hand.
The Kafka-side DLT (`utils/common-kafka/.../BaseKafkaListenerConfig.java:88-89`) is unrelated to this
table. `WebhookRetryScheduler` also has no claim, so the same event is retried concurrently by every
replica (§4 E, SHIP-07).

---

## 8. ORDER EVENT CONSUMPTION

### 8.1 Topic, group and container

| Aspect | Value | Evidence |
|---|---|---|
| Topic | `shop.order.lifecycle.v1` (`KafkaTopicContract.SHOP_ORDER_LIFECYCLE`), declared `Mode.CONSUMED` with the rationale "notification-service and shipping-service react to order lifecycle changes" | `utils/common-kafka/.../KafkaTopicContract.java:70`, `:86-87`; `kafka/OrderEventConsumer.java:20` |
| Consumer group | `shipping-service` — **pinned in the annotation** | `kafka/OrderEventConsumer.java:22`; also configured at `application.yml:26` and injected into the consumer factory by `KafkaProperties.buildConsumerProperties()` (`utils/common-kafka/.../KafkaProperties.java:102`) |
| Container factory | `shippingListenerFactory`, a `ConcurrentKafkaListenerContainerFactory` from the shared `BaseKafkaListenerConfig` | `kafka/ShippingListenerConfig.java:22-26` |
| Error handler | `DefaultErrorHandler(DeadLetterPublishingRecoverer(stringKafkaTemplate), FixedBackOff(1000 ms, 3))` → after 3 retries the record goes to `shop.order.lifecycle.v1.DLT` and the offset is committed | `utils/common-kafka/.../BaseKafkaListenerConfig.java:38-49` |
| Concurrency | default 1 (never overridden) → one consumer thread per replica | `kafka/ShippingListenerConfig.java:22-26` |
| Offsets | `auto.offset.reset: earliest` | `application.yml:27-30` |
| Poison payloads | `PoisonRecordException` → the same DLT path | `utils/common-kafka/.../BaseKafkaConsumer.java:110-115` |

The group id is the service's identity: changing it resets the committed offsets and replays the
whole topic — the module says so itself (`kafka/OrderEventConsumer.java:20-21`).

### 8.2 The partition key

**The shipping-service does not choose the partition key — the producer does.** `order-service`
publishes the order lifecycle events through its own outbox relay, keyed by the aggregate id, which is
the order id (`order-service/.../OrderOutboxRelay.java:96` `String key = e.getAggregateId().toString()`;
`order-service/.../OrderEventPublisherImpl.java:79-83` sets `aggregateId = order.getId()`). The
shipping-service's own IT publishes with the same key (`ShippingFlowIT.java:377`
`kafkaMessagePublisher.publish(ORDER_TOPIC, orderId.toString(), …)`).

Consequence: **all events of one order land on one partition**, so Kafka's per-partition ordering
guarantees the relative order of `order.confirmed.v1` and `order.cancelled.v1` for that order — *as
long as* the producer keeps keying by the order id and the partition count is stable. The shipping
side cannot break this ordering, and it does not depend on it for correctness either (see §8.4).

### 8.3 The handler and its idempotency

`kafka/OrderEventConsumer.java:24-26` → `BaseKafkaConsumer.processMessage`
(`utils/common-kafka/.../BaseKafkaConsumer.java:52-58`), which tolerates the legacy double-encoded
wire shape (`:59-61`; proven by `ShippingFlowIT.java:352-367`) → `ShipmentService::handleOrderEvent`.

`service/impls/ShipmentServiceImpl.java:69-76` acts on exactly two statuses:

| Event status | Action | Idempotent? |
|---|---|---|
| `CONFIRMED` | `createShipment` (`:78-105`) | **at the row level yes** (the `existsByOrderId` pre-check at `:80` plus the unique index `uk_shipment_order_live`), **at the carrier level NO** (§4 A, SHIP-01) |
| `CANCELLED` | `cancelShipment` (`:107-118`) | yes — a second CANCELLED finds a non-`CREATED` shipment and leaves it alone (`:109-113`); an in-flight shipment is deliberately **not** cancelled (test `ShipmentServiceImplTest.java:132-146`) |
| anything else | no-op | — (test `ShipmentServiceImplTest.java:148-153`) |

### 8.4 Can an out-of-order event corrupt a shipment?

* **Within the order topic: no.** The producer's per-order key gives Kafka per-partition ordering,
  and every write is idempotent at the row level (§8.3), so a redelivery of `CONFIRMED` cannot create
  a second shipment row and a redelivery of `CANCELLED` cannot cancel twice
  (`ShippingFlowIT.java:125-142`).
* **The gap is the "cancel-before-create" ordering.** `cancelShipment` looks the shipment up with
  `findByOrderId` and does nothing when it is absent (`ShipmentServiceImpl.java:108`, `ifPresent`).
  If the `CANCELLED` event is processed **before** the shipment exists — the CONFIRMED event having
  been dead-lettered, delayed, or lost — the cancellation is **silently forgotten**, and the later
  CONFIRMED creates a **CREATED shipment for a cancelled order**: the customer is billed for a parcel
  that nobody will cancel. There is no tombstone, no "cancelled orders" table and no ordering key in
  the handler. **SHIP-17 (MEDIUM)**.
* **A carrier failure is fatal to the order event.** `adapter.createShipment(orderId)`
  (`ShipmentServiceImpl.java:92`) runs inside the listener; the `BusinessException` it raises
  (`CarrierShipmentClient.java:64-69`) propagates out of `handleOrderEvent` → the error handler
  retries three times → the record is published to the **DLT** and the offset is committed. The
  shipment is never created and nothing in this service retries it. **SHIP-02 (HIGH)**.

---

## 9. DESIGN PATTERN EVALUATION

### 9.1 What is genuinely there

| Pattern | Verdict | Where |
|---|---|---|
| Strategy | **Genuine and correctly used** | `carrier/CarrierAdapter.java:8-21` + 4 implementations; the concrete strategy is selected once by configuration at `carrier/CarrierConfig.java:18-27`; the call site is polymorphic (`ShipmentServiceImpl.java:90-92`) |
| Adapter (GoF) | **Genuine** | Each carrier adapter translates a carrier-specific response into the neutral `ShipmentDraft` (`GhnCarrierAdapter.java:68-73`, `GhtkCarrierAdapter.java:62-67`), with the transport and JSON-path extraction factored into `carrier/CarrierShipmentClient.java:53-96` |
| Registry | **Genuine but unused** | `carrier/CarrierFactory.java:19-35` indexes the adapters; the lookup API (`:37-50`) has no production caller (§2.3) |
| Outbox (transactional outbox) | **Genuine and well built** | `service/ShipmentWriter.java:27-32` (status + outbox row in one transaction), `outbox/ShippingOutboxRelay.java` (claim → publish → mark with a claim lease and a recovery sweep) |
| Repository | **Genuine** (Spring Data) | `repository/ShipmentRepository.java`, `repository/ShipmentEventRepository.java`, `outbox/OutboxEventRepository.java` |
| State machine (table-driven) | **Genuine** | `service/ShipmentStateMachine.java:12-24` — but **not applied everywhere** (§3.3, SHIP-05) |
| Facade / port-adapter (hexagonal) | **Partly** | `CarrierAdapter` and `WebhookEventService` are ports; the services are the driving adapters |
| Factory Method | **Naming only** | `CarrierFactory` never constructs a product (§2.4) |
| Template Method | **Naming only** | the relay's phase decomposition is not a template (§2.4) |
| Decorator / Observer / Command | **Absent** | — |

### 9.2 The additions that are justified

**(1) A `CarrierResolver` that rejects unknown carriers** (replaces the silent substitution of
`CarrierFactory.getCarrier`, §2.5):

```java
public final class CarrierResolver {

    private final Map<String, CarrierAdapter> adapters;

    public CarrierResolver(List<CarrierAdapter> adapterList) {
        Map<String, CarrierAdapter> byCode = new LinkedHashMap<>();
        for (CarrierAdapter adapter : adapterList) {
            byCode.put(adapter.carrierCode().toUpperCase(Locale.ROOT), adapter);
        }
        this.adapters = Map.copyOf(byCode);
    }

    public CarrierAdapter resolve(Carrier carrier) {
        CarrierAdapter adapter = adapters.get(carrier.name());
        if (adapter == null) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrier.name());
        }
        return adapter;
    }

    public CarrierAdapter resolve(String carrierCode) {
        String key = carrierCode == null ? "" : carrierCode.trim().toUpperCase(Locale.ROOT);
        CarrierAdapter adapter = adapters.get(key);
        if (adapter == null) {
            throw BusinessException.of(ErrorCode.CARRIER_NOT_CONFIGURED, carrierCode);
        }
        return adapter;
    }
}
```

The dispatcher then resolves by the shipment's **stored** carrier
(`ShipmentServiceImpl.dispatchToCarrier(shipment)` → `resolver.resolve(shipment.getCarrier())`), so a
shipment that says `GHN` can never be serviced by the MANUAL adapter.

**(2) One choke point for the shipment status** — put the transition on the entity so no caller can
write `status` directly, and give the auto-delivery path its own explicit rule:

```java
@Entity
@Table(name = "shipments")
public class Shipment extends AbstractMappedEntity {

    public ShipmentStatus status() {
        return status;
    }

    public void transitionTo(ShipmentStatus target) {
        ShipmentStatus next = ShipmentStateMachine.transition(status, target);
        this.previousStatus = status;
        this.status = next;
    }

    public void autoDeliver(Instant now) {
        ShipmentStatus next = ShipmentStateMachine.autoDeliver(status);
        this.previousStatus = status;
        this.status = next;
        this.autoDelivered = true;
        this.deliveredAt = now;
    }
}
```

with

```java
public static ShipmentStatus autoDeliver(ShipmentStatus from) {
    if (!ShipmentStatus.inFlight(from)) {
        throw BusinessException.of(ErrorCode.SHIPMENT_INVALID_TRANSITION, from, ShipmentStatus.DELIVERED);
    }
    return ShipmentStatus.DELIVERED;
}
```

and the public setters for `status`/`previousStatus` removed from the entity.

**(3) A claimable job queue** shared by the outbox relay and the webhook retry scheduler — it fixes
both claim defects (§4 E, §4 F) with one mechanism:

```java
public interface ClaimableJobQueue {

    Optional<ClaimedJob> claimNext(String claimableStatus, Instant now, Duration lease);

    void complete(ClaimedJob job, boolean success, String error);

    int reclaimExpiredClaims(String claimedStatus, Instant cutoff);
}
```

backed by a native claim query that is lock-aware (no `MIN(id)` subquery):

```sql
SELECT * FROM outbox_events
 WHERE status = 'PENDING'
 ORDER BY id
   FOR UPDATE SKIP LOCKED
 LIMIT 1
```

and by `shipment_events` gaining the same `claimed_at` column the outbox already has
(`outbox/OutboxEvent.java:56-57`).

**(4) A typed carrier failure taxonomy** so a carrier failure is distinguishable from a carrier
rejection (§2.6):

```java
public enum CarrierFailureKind {
    NOT_CONFIGURED,
    UNAVAILABLE,
    REJECTED,
    INVALID_RESPONSE
}

public class CarrierFailure extends RuntimeException {

    private final CarrierFailureKind kind;
    private final String carrier;

    public CarrierFailure(CarrierFailureKind kind, String carrier, String detail) {
        super(detail);
        this.kind = kind;
        this.carrier = carrier;
    }

    public CarrierFailureKind kind() {
        return kind;
    }

    public String carrier() {
        return carrier;
    }
}
```

with `CarrierShipmentClient` throwing `UNAVAILABLE` for transport errors, `REJECTED` for a 4xx
response and `INVALID_RESPONSE` for a malformed body, and a mapper translating the kind into an
`ErrorCode` and an HTTP status (503 / 422 / 502).

**(5) A claim-first shipment creation** that makes the carrier call idempotent-by-construction
(§4 A):

```java
@Transactional
public Shipment reserve(UUID orderId, Carrier carrier) {
    Shipment shipment = Shipment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .carrier(carrier)
            .status(ShipmentStatus.CREATED)
            .build();
    try {
        return shipmentRepository.saveAndFlush(shipment);
    } catch (DataIntegrityViolationException ex) {
        throw new ShipmentAlreadyExistsException(orderId);
    }
}

@Transactional
public void attachCarrierTracking(Shipment shipment, String trackingNumber, Instant acknowledgedAt) {
    shipment.setTrackingNumber(trackingNumber);
    shipment.setCarrierAcknowledgedAt(acknowledgedAt);
    shipmentRepository.save(shipment);
}
```

The `reserve` call claims the order id under the unique index **before** the carrier is contacted, so
a duplicate event never produces a second parcel; the carrier call then only ever happens for the
row that won the claim.

---

## 10. DEFECT LIST

Severity is the harness-readable word in the heading. **33 defects: 8 HIGH, 13 MEDIUM, 12 LOW.**

### SHIP-01 — HIGH — The carrier shipment is created before the idempotency guard, so a duplicated CONFIRMED order event produces two parcels at the carrier

**Evidence.** `service/impls/ShipmentServiceImpl.java:80` (`existsByOrderId`),
`:92` (`adapter.createShipment(orderId)` — the external side effect), `:101-104` (the insert and the
swallowed `DataIntegrityViolationException`); `carrier/CarrierShipmentClient.java:53-70` (the HTTP
call); the guard `changelog-001-shipments.yaml:40` (`uk_shipment_order_live`).

**Actual behaviour.** Two concurrent deliveries of the `CONFIRMED` order event (a consumer-group
rebalance, or two replicas) both pass `existsByOrderId`, both call the carrier, and the carrier
creates **two** parcels. One `shipments` row survives (the other loses the unique index and the
exception is swallowed at `:102-104`); the second tracking number is discarded — the customer is
sent two parcels, the carrier is billed twice, and one parcel is untracked.

**Violated invariant.** At most one carrier shipment exists per order. The database invariant
holds; the external one does not.

**Root cause.** The idempotency guard protects only the database write, while the non-transactional
side effect happens before it, inside a check-then-act whose TOCTOU window is the whole carrier
round-trip.

**Proposed fix.** Claim the order id first (§9 sketch 5): insert the shipment row in its own
transaction, then call the carrier and update the row; or send a deterministic idempotency key
(`order_id` is already in the body, `CarrierShipmentClient.java:60`) and require the carrier to
dedupe on it.

**Reproduction test.** An IT with a stubbed carrier that counts the create-shipment calls; deliver
the same `CONFIRMED` event from two threads (or publish it twice with a rebalance) and assert the
call count.

**Acceptance criterion.** With a GHN carrier configured, N duplicate `CONFIRMED` events produce
**exactly one** create-shipment call and exactly one `shipments` row, and the persisted tracking
number is the one that call returned.

### SHIP-02 — HIGH — A carrier failure during order confirmation is dead-lettered and the shipment is never created

**Evidence.** `carrier/CarrierShipmentClient.java:64-69` (throws `BusinessException`),
`service/impls/ShipmentServiceImpl.java:92` (called inside the listener),
`kafka/OrderEventConsumer.java:24-26` (no catch),
`utils/common-kafka/.../BaseKafkaListenerConfig.java:88-89`
(`DefaultErrorHandler(DeadLetterPublishingRecoverer, FixedBackOff(1000, 3))`).

**Actual behaviour.** The carrier is down or returns 500 → the exception escapes the listener → the
record is retried three times with a 1 s backoff → it is published to `shop.order.lifecycle.v1.DLT`
and the offset is committed. The order has **no shipment, forever**, and this service raises no
alarm: the only trace is the DLT record and a log line. The outbox/retry machinery that exists for
webhooks is not used for order events.

**Violated invariant.** Every `CONFIRMED` order eventually gets a shipment.

**Root cause.** The carrier call is executed inline in the Kafka listener, and the listener's error
handler treats any failure as permanent after 3 attempts.

**Proposed fix.** Persist the intent and the failure: catch the carrier exception in
`handleOrderEvent`, store a `SHIPMENT_CREATION_PENDING` row (or an outbox row) and retry it with
backoff; or route the failure to a dedicated retry topic instead of the DLT.

**Reproduction test.** An IT with the GHN adapter pointed at an endpoint that returns 500 for the
first call and 200 afterwards: assert that a shipment is eventually created (or that a retry record
exists) and that the DLT stays empty of shipping-caused records.

**Acceptance criterion.** A transient carrier outage never leaves an order without a shipment, and
no `CONFIRMED` order event reaches the dead-letter topic because of a carrier error.

### SHIP-03 — HIGH — The carrier HTTP call has no connect or read timeout, so a stalled carrier blocks the Kafka consumer thread indefinitely

**Evidence.** `carrier/CarrierShipmentClient.java:21-24` (the `RestClient` is built from the
auto-configured `RestClient.Builder` with no timeout), `:29-35` (the blocking call);
`application.yml:1-48` (no `spring.http.client.connect-timeout` / `read-timeout` anywhere);
`kafka/ShippingListenerConfig.java:22-26` (no container tuning); `shipping-service/pom.xml:67-70`
(resilience4j is on the classpath but **unused** — `grep resilience4j` over the module finds no
annotation).

**Actual behaviour.** A carrier that accepts the TCP connection and never answers leaves the
listener thread blocked in `restClient.post()…retrieve()` with no read timeout. The container has
concurrency 1, so the partition stops being polled; the backlog grows and every subsequent order
event for that partition waits behind the hung call.

**Violated invariant.** A dependent service can stall the order pipeline indefinitely.

**Root cause.** The client inherits the framework defaults (no timeouts) and no timeout, timeout
decorator or circuit breaker is configured.

**Proposed fix.** Set `spring.http.client.connect-timeout` and `read-timeout` (or build the
`RestClient` on a `JdkClientHttpRequestFactory` with explicit timeouts), and put a resilience4j
time-limiter/circuit-breaker around the carrier call.

**Reproduction test.** Point the adapter at a socket that accepts and never responds; assert the call
fails within the configured budget and that the consumer thread is released.

**Acceptance criterion.** Every carrier call completes or fails within a configured, documented
budget (e.g. connect 2 s / read 5 s), and no listener thread stays blocked longer than that.

### SHIP-04 — HIGH — Liquibase changeset `003-webhook-retry-fields` fails on an existing database and blocks changesets 004, 005 and 006

**Evidence.** `db/changelog/changelog-003-webhook-retry.yaml:24-29` (the backfill
`UPDATE … SET status = 'FAILED_RETRYABLE' WHERE status = 'FAILED'`) versus
`db/changelog/changelog-001-shipments.yaml:74`
(`CHECK (status IN ('PROCESSED','FAILED'))` — the constraint is only widened by
`changelog-004-expand-events-status-check.yaml:16-19`); the module's own comment at
`changelog-004-expand-events-status-check.yaml:2-9` states the 003 backfill "violates it too".

**Actual behaviour.** On any database that already holds a `FAILED` `shipment_events` row, the 003
backfill raises a check-constraint violation → the changeset fails → Liquibase aborts → `004`,
`005-carrier-acknowledged-at` and `006-shipping-outbox-claim-lease` never run →
`shipments.carrier_acknowledged_at` and `outbox_events.claimed_at` are never created → with
`spring.jpa.hibernate.ddl-auto: validate` (`application.yml:13`) the service **cannot start**.
On a fresh database the table is empty, so the UPDATE matches nothing and the bug stays hidden.

**Violated invariant.** The migration chain must apply cleanly to the databases that exist in
production.

**Root cause.** The backfill was written against a status value the constraint does not yet allow,
and the constraint widening was deferred to a later changeset.

**Proposed fix.** Make 003 column-only and move the backfill after the constraint widening (it is
already idempotent in 004, `changelog-004-…:21-26`), or merge both into one changeset.

**Reproduction test.** A Liquibase test that seeds a `shipment_events` row with status `FAILED` and
then runs the master changelog against it.

**Acceptance criterion.** The full changelog applies cleanly — and idempotently — to a database
seeded with `FAILED` rows, and `carrier_acknowledged_at` / `claimed_at` exist afterwards.

### SHIP-05 — HIGH — The reconciliation scheduler writes the shipment status directly, bypassing the state machine and performing a transition the FSM forbids

**Evidence.** `scheduler/ReconciliationScheduler.java:55-61` (`shipment.setStatus(ShipmentStatus.DELIVERED)`),
against `service/ShipmentStateMachine.java:12-24` (the only edge into `DELIVERED` is
`OUT_FOR_DELIVERY → DELIVERED`) and `scheduler/ReconciliationScheduler.java:25-27`, `:48` (the sweep
selects `PICKED_UP`, `IN_TRANSIT` and `OUT_FOR_DELIVERY`).

**Actual behaviour.** A shipment in `PICKED_UP` or `IN_TRANSIT` is flipped straight to `DELIVERED`,
a transition `ShipmentStateMachine.transition` would reject with SHP-10003. The FSM is therefore not
the single enforcement point, and the two rules disagree about what is legal. The behaviour is
locked in by `scheduler/ReconciliationSchedulerTest.java:88-100`.

**Violated invariant.** Every status change must pass the state machine.

**Root cause.** The auto-delivery path was never given a place in the FSM, so it wrote the entity
field directly instead of going through it.

**Proposed fix.** Add an explicit `autoDeliver` rule to the state machine (§9 sketch 2), route the
sweep through it, and remove the public setters for `status`/`previousStatus` from the entity.

**Reproduction test.** A unit test that asserts every status-changing call site goes through
`ShipmentStateMachine` (e.g. a test that the sweep calls `autoDeliver` and that
`ShipmentStateMachine.transition(IN_TRANSIT, DELIVERED)` is still rejected).

**Acceptance criterion.** `grep -n "setStatus(ShipmentStatus" ` over `src/main` returns only the
state-machine-aware helpers, and the sweep's transition is declared in the FSM.

### SHIP-06 — HIGH — A late carrier event can roll a failed delivery back to in-transit, after which the sweep auto-delivers the shipment

**Evidence.** `service/ShipmentStateMachine.java:20` (`DELIVERY_FAILED → IN_TRANSIT` is legal),
`service/impls/WebhookEventServiceImpl.java:140-180` (the transition is computed from the current
status; `lastCarrierUpdate` is set to the **receipt** time at `:164`), `webhook/CarrierWebhookPayload.java:14-17`
(the payload carries no timestamp and no sequence number).

**Actual behaviour.** The carrier sends `DELIVERY_FAILED` (applied), then an older `IN_TRANSIT`
event arrives late. The FSM permits `DELIVERY_FAILED → IN_TRANSIT`, so the late event is applied: the
shipment is back in `IN_TRANSIT` with `previous_status = DELIVERY_FAILED`, and unless the carrier
sends another event the reconciliation sweep flips it to `DELIVERED` after `auto-deliver-days`
(`ReconciliationScheduler.java:55-61`) → **the order is reported DELIVERED for a parcel whose
delivery had failed**.

**Violated invariant.** A shipment's status never moves backwards; a failed delivery is never
silently converted into a delivery.

**Root cause.** The FSM models a state graph, not a monotonic sequence, and the webhook payload
carries no ordering key, so "older" cannot be distinguished from "newer".

**Proposed fix.** Add `occurredAt` (and a monotonic sequence) to the carrier payload and to
`ShipmentEvent`, store it, and reject an event that is older than the last applied carrier update;
restrict `DELIVERY_FAILED → IN_TRANSIT` to the operator retry path (`ShipmentServiceImpl.retry`).

**Reproduction test.** An IT that posts `DELIVERY_FAILED` and then an older `IN_TRANSIT` event for
the same tracking number, advances the clock past `auto-deliver-days` and runs the sweep.

**Acceptance criterion.** The shipment stays `DELIVERY_FAILED`, no delivered outbox row is written,
and the sweep leaves it alone.

### SHIP-07 — MEDIUM — The webhook retry scheduler has no claim: two replicas process the same event and the loser's failure happens at commit time, so the retry counter never advances

**Evidence.** `repository/ShipmentEventRepository.java:26-27` (a plain find — no `@Lock`, no claim
column), `scheduler/WebhookRetryScheduler.java:53-63` (the batch is read, never claimed),
`:66-103` (the per-event transaction), `:86-100` (the `catch` is **inside** the
`transactionTemplate.execute` callback, so a commit-time failure escapes it).

**Actual behaviour.** Replica A and replica B both read the same `FAILED_RETRYABLE` row and both
process it; A commits, B's commit fails the `@Version` check on the shipment and on the event →
`OptimisticLockException` raised **at commit**, outside the callback → the exception escapes
`replayOne` and `replay()`, Spring logs it, the remaining events of that batch are skipped, and
`retry_count` / `next_retry_at` are **never updated** → the event is retried every 60 s forever and
can never reach `FAILED_PERMANENT`.

**Violated invariant.** Every failed attempt advances the retry counter exactly once, and a poison
event terminates.

**Root cause.** The retry queue is claimed by *reading*; the only concurrency control is the
`@Version` of the rows the worker writes, and it fires too late for the retry accounting.

**Proposed fix.** Claim the event atomically before processing (a conditional `UPDATE … WHERE
status='FAILED_RETRYABLE'`, or `FOR UPDATE SKIP LOCKED` plus a claim lease and a recovery sweep —
§9 sketch 3) and make the retry accounting cover commit-time failures.

**Reproduction test.** Two threads call `replayOne(event)` on the same event, or two application
contexts race the scheduler; assert `retry_count`, `next_retry_at` and the final status.

**Acceptance criterion.** With two replicas, one attempt advances `retry_count` by exactly one, no
exception escapes the scheduler, and a permanently failing event reaches `FAILED_PERMANENT` after
`MAX_ATTEMPTS`.

### SHIP-08 — MEDIUM — The outbox claim's `MIN(id)` subquery is not lock-aware, so the head row blocks every other pending row and only one replica makes progress

**Evidence.** `outbox/OutboxEventRepository.java:35-39` (`FOR UPDATE SKIP LOCKED` +
`e.id = (SELECT MIN(e2.id) …)`), `outbox/ShippingOutboxRelay.java:110-127` (the drain loop breaks
on the first empty claim), and the module's own integration test, which codifies the behaviour:
`outbox/ShippingOutboxClaimConcurrencyIT.java:106` (`claimBWhileLocked` is empty) and `:169`
(row 2 is still `PENDING` while row 1 is locked).

**Actual behaviour.** While the lowest-id `PENDING` row is locked by another transaction, the
`MIN(id)` sub-select still resolves to that row, the outer `FOR UPDATE SKIP LOCKED` drops it, and
the query returns **zero rows** although thousands of other rows are claimable. Every replica that
loses the head race publishes nothing in that tick (`ShippingOutboxRelay.java:123-125`), so the
relay's effective parallelism is 1× regardless of the replica count.

**Violated invariant.** The relay drains the pending backlog.

**Root cause.** `MIN(id)` is a lock-unaware spelling of `ORDER BY id LIMIT 1`; `SKIP LOCKED` applies
to the outer row only.

**Proposed fix.** Use a native claim query:
`SELECT * FROM outbox_events WHERE status='PENDING' ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1`.

**Reproduction test.** Extend `ShippingOutboxClaimConcurrencyIT` with a case that locks row 1 and
asserts that the next claim returns row 2.

**Acceptance criterion.** With the head row locked, a second claim returns the next pending row and
the backlog drains with more than one replica.

### SHIP-09 — MEDIUM — Permanent webhook failures are retried with an exponential backoff for ≈2.7 days instead of being classified as permanent

**Evidence.** `scheduler/WebhookRetryScheduler.java:105-111` (`300 · 5^(attempt-1)` seconds) and
`:30` (`MAX_ATTEMPTS = 6`); `service/impls/WebhookEventServiceImpl.java:142-147` (unknown tracking
number), `:151-158` (illegal transition), `:135-137` (every other failure is rethrown as a bare
`RuntimeException`), `:182-186` (`markFailedRetryable`).

**Actual behaviour.** A webhook whose tracking number is unknown, or whose transition is illegal, is
retried six times over 5 min → 25 min → 2 h 5 min → 10 h 25 min → 52 h before it is parked as
`FAILED_PERMANENT`. Each retry re-reads the shipment and re-derives the same permanent error, so the
queue is occupied by events that can never succeed.

**Violated invariant.** A failure that cannot be resolved by retrying must not consume the retry
budget.

**Root cause.** The service does not classify failures; the scheduler sees one opaque exception.

**Proposed fix.** Classify at the failure site (permanent vs transient) and mark permanent failures
`FAILED_PERMANENT` immediately; cap the backoff at a sane maximum.

**Reproduction test.** A unit test that posts a webhook for an unknown tracking number and asserts
the event is `FAILED_PERMANENT` after one attempt, with `retry_count == 0`.

**Acceptance criterion.** Unknown-tracking and illegal-transition webhooks reach
`FAILED_PERMANENT` within a single attempt; transient failures keep the exponential backoff.

### SHIP-10 — MEDIUM — `Instant` columns are declared `timestamp` (no time zone) while `ddl-auto: validate` is enabled, and the module's tests never exercise validation

**Evidence.** `db/changelog/changelog-002-shipping-outbox.yaml:25` (`sent_at type: timestamp`),
`db/changelog/changelog-003-webhook-retry.yaml:20` (`next_retry_at type: timestamp`) — against
`db/changelog/changelog-001-shipments.yaml:25-26`, `:65-66` (`created_at`/`updated_at` are
`timestamptz` for the same `java.time.Instant` type); entity mappings
`entity/OutboxEvent.java:45-46`, `entity/ShipmentEvent.java:67-68`; `application.yml:13`
(`ddl-auto: validate`); `test/.../support/AbstractIntegrationTest.java:41`
(`ddl-auto=none` in the tests).

**Actual behaviour.** Hibernate 6 maps `java.time.Instant` to `TIMESTAMP_UTC`
(`timestamp(6) with time zone`) on PostgreSQL, and the schema validator compares JDBC types, so a
`timestamp without time zone` column is a candidate for a `SchemaManagementException` at start-up.
**This could not be confirmed by execution** (no Maven build was permitted for this review) and the
module's integration tests set `ddl-auto=none`, so the validation path is untested. The identical
pattern exists in the sibling services' outbox tables, so if it is a defect it is platform-wide.

**Violated invariant.** The migrated schema must match the ORM's expected schema when
`ddl-auto: validate` is on.

**Root cause.** Two different timestamp types were used for the same Java type within the same
schema.

**Proposed fix.** Declare every `Instant` column as `timestamptz` (as `shipments.created_at`
already is) and add an IT that boots with `ddl-auto: validate` against the Liquibase-migrated
schema.

**Reproduction criterion.** The service starts against a Liquibase-migrated database with
`ddl-auto=validate`, and an IT asserts the schema validation passes.

### SHIP-11 — MEDIUM — `shipment_events` has no index on `(status, next_retry_at)` although it is polled every 60 seconds

**Evidence.** `repository/ShipmentEventRepository.java:26-27` (the poll query),
`scheduler/WebhookRetryScheduler.java:51-64` (fixedDelay 60 000 ms),
`db/changelog/changelog-001-shipments.yaml:45-71` (the table definition — indexes: none beyond the
primary key), and the sibling `notification-service` which **does** have
`idx_notifications_status_next_retry`.

**Actual behaviour.** Every minute the scheduler performs a full scan of `shipment_events` (the
status and `next_retry_at` predicates are unindexed), on a table that only grows — `FAILED_RETRYABLE`
and `FAILED_PERMANENT` rows are never purged.

**Violated invariant.** Polling cost must not grow linearly with the retained history.

**Root cause.** The index that the retry feature needs was not added with the feature (the same
omission exists for the outbox claim index in the sibling services).

**Proposed fix.** Add an index on `(status, next_retry_at)` (a new additive, idempotent changeset),
and a retention job for terminal webhook events.

**Reproduction test.** `EXPLAIN ANALYZE` the poll query against a table with 10⁵ rows, before and
after the index.

**Acceptance criterion.** The poll query uses an index scan; the index exists in the changelog as an
idempotent changeset.

### SHIP-12 — MEDIUM — `Shipment` and `ShipmentEvent` have no `@SQLRestriction("deleted = false")` although the unique indexes are partial on `deleted = false`

**Evidence.** `entity/Shipment.java` (no `@SQLRestriction`; contrast
`product-service/.../entity/Product.java:14`), `entity/ShipmentEvent.java`,
`repository/ShipmentRepository.java:46` (`existsByOrderId` — no `deleted` filter),
`db/changelog/changelog-001-shipments.yaml:40`
(`CREATE UNIQUE INDEX uk_shipment_order_live ON shipments (order_id) WHERE deleted = false`).

**Actual behaviour.** A soft-deleted shipment is still returned by every query
(`findByOrderId`, `findByTrackingNumber`, the backoffice list) and still blocks the creation of a
replacement — `existsByOrderId` sees it and `createShipment` skips — while the partial unique index
would have allowed a new row. The order can therefore never get a new shipment. (Nothing in this
module soft-deletes a shipment today, so the exposure is latent — but `SoftDeletable.markDeleted`
is public and inherited, `utils/common-core/.../SoftDeletable.java:41-45`.)

**Violated invariant.** A soft-deleted row is invisible to the application, and the uniqueness
semantics of "one live shipment per order" are consistent between the guard and the constraint.

**Root cause.** The partial index was written with the soft-delete semantics in mind, but the
entities were not annotated to match.

**Proposed fix.** Add `@SQLRestriction("deleted = false")` to `Shipment` and `ShipmentEvent`, as the
other services do, and make `existsByOrderId` consistent with the index.

**Reproduction test.** Soft-delete a shipment, then assert `findByOrderId` and `existsByOrderId` do
not see it and that a new order-confirmed event can create a fresh shipment.

**Acceptance criterion.** Soft-deleted rows are invisible to every read path and no longer block the
creation of a live row.

### SHIP-13 — MEDIUM — No foreign key from `shipment_events.shipment_id` to `shipments.id`

**Evidence.** `db/changelog/changelog-001-shipments.yaml:58` (`shipment_id` is a plain `uuid` with no
FK), `entity/ShipmentEvent.java:40-41`.

**Actual behaviour.** Webhook events can outlive the shipment they refer to; nothing cascades or
prevents an orphan, and the table has no retention, so orphans accumulate.

**Violated invariant.** Referential integrity of the event → shipment relation.

**Root cause.** The FK was never added; `shipments.order_id` correctly has none (cross-service), but
the intra-service FK was omitted too.

**Proposed fix.** Add `FOREIGN KEY (shipment_id) REFERENCES shipments(id) ON DELETE SET NULL` (the
column is nullable) in a new additive changeset, or document the deliberate omission.

**Acceptance criterion.** The FK exists in the changelog and an orphan event cannot be inserted.

### SHIP-14 — MEDIUM — A signed webhook without a `providerEventId` is silently dropped and acknowledged with HTTP 200

**Evidence.** `webhook/CarrierWebhookPayload.java:14` (no validation on `eventId`),
`service/impls/WebhookEventServiceImpl.java:85-96` (the insert with a null `provider_event_id`),
`:97-101` (the `DataIntegrityViolationException` is caught by the "raced a concurrent insert" branch
and logged as such), `db/changelog/changelog-001-shipments.yaml:60`
(`provider_event_id ... nullable: false`).

**Actual behaviour.** The carrier sends a well-signed event without `eventId` → the insert violates
`NOT NULL` → the exception is caught by the race branch → the webhook is acknowledged **200 OK**,
the event is dropped, and the log says "raced a concurrent insert" — a misleading diagnosis of a
malformed payload.

**Violated invariant.** A malformed carrier message must be rejected explicitly, not acknowledged.

**Root cause.** The one catch clause serves two different failures (a genuine unique-index race and a
constraint violation on a malformed payload).

**Proposed fix.** Validate the payload (`@NotBlank` on `eventId`, `trackingNumber`, `carrierStatus`)
and reject with 400; separate the two catch paths by inspecting the constraint name.

**Reproduction test.** POST a correctly signed body without `eventId`; assert a 4xx response and that
no `shipment_events` row is silently created or dropped.

**Acceptance criterion.** A malformed signed payload returns 400 with a distinct error code and a
distinct log message.

### SHIP-15 — MEDIUM — Outbox events parked as `FAILED` are never surfaced and never purged

**Evidence.** `outbox/ShippingOutboxRelay.java:210-218` (the row is parked at `FAILED` with only a
`log.error`), `utils/common-core/.../OutboxStatus.java:4` (the enum has a `DEAD` state that the
shipping relay never uses), `outbox/ShippingOutboxRetentionScheduler.java:22-32` (only `SENT` rows
are purged), `service/ShippingMetrics.java` (no counter for outbox failures).

**Actual behaviour.** An event that exhausts `max-retries` (default 10) leaves the pipeline with a
log line only: no metric, no dead-letter topic, no dashboard, and the row stays in the table
forever. The `DEAD` status that the shared enum provides is never used.

**Violated invariant.** Every permanently failed event is observable and eventually dispositioned.

**Root cause.** The failure path was implemented as a log statement rather than as a terminal state
with a signal.

**Proposed fix.** Add a counter (`shipping.outbox.failed.total`), transition the row to `OutboxStatus.DEAD`
with the reason, publish it to a dead-letter topic, and include `DEAD`/`FAILED` in the retention job
(or an explicit operator queue).

**Acceptance criterion.** A permanently failed outbox event increments a counter, lands in `DEAD`,
and is visible to an operator without a database query.

### SHIP-16 — MEDIUM — The outbox mark phase uses `SKIP LOCKED`, so a locked row silently skips the mark and the event is published twice

**Evidence.** `outbox/OutboxEventRepository.java:55-58` (`findByIdForUpdate` with
`lock.timeout = -2` = `FOR UPDATE SKIP LOCKED`), `outbox/ShippingOutboxRelay.java:199-205` (an
empty result → `return`, the row stays `SENDING`), `:184-197` (the sweep returns a stale `SENDING`
row to `PENDING`), `:141-142` (the publish already happened before the mark).

**Actual behaviour.** If the row is locked when the mark runs, the mark is skipped, the row stays
`SENDING`, and after the claim lease (60 s) the recovery sweep returns it to `PENDING` → the relay
publishes the event **a second time**. The downstream `order-service` then sees two
`shipping.delivered.v1` events for one shipment.

**Violated invariant.** A published event is marked `SENT` exactly once, or is knowingly retried.

**Root cause.** `SKIP LOCKED` is the right choice for the *claim* (never block a worker) but the
wrong choice for the *mark* (a lost mark turns into a duplicate publish).

**Proposed fix.** Use a blocking/`NOWAIT` lock for the mark and retry, or treat a skipped mark as an
explicit "leave it `SENDING`" decision and document the resulting at-least-once behaviour; the
consumer side should be idempotent by `eventId`.

**Reproduction test.** Hold the row's lock in another transaction while `markFinalState` runs, then
advance the clock past the claim lease and run the relay: assert how many times the event is
published to Kafka.

**Acceptance criterion.** An event is published at most once unless the publish itself failed, and
every skipped mark is visible in the logs and metrics.

### SHIP-17 — MEDIUM — A `CANCELLED` order event that arrives before the shipment exists is forgotten, and a parcel is later created for a cancelled order

**Evidence.** `service/impls/ShipmentServiceImpl.java:107-114` (`cancelShipment` does
`findByOrderId(...).ifPresent(...)` — a missing shipment is a silent no-op), `:78-105` (the
`CONFIRMED` path creates a shipment unconditionally when none exists).

**Actual behaviour.** The order is cancelled while its `CONFIRMED` event is delayed, dead-lettered or
lost. The `CANCELLED` event finds no shipment and is dropped. When the `CONFIRMED` event is later
processed, a **CREATED shipment is created for a cancelled order** — the parcel is picked, packed
and shipped, and nothing will cancel it.

**Violated invariant.** A cancelled order never produces a shipment.

**Root cause.** The handler keeps no memory of a cancellation that arrived before the aggregate
existed, and the shipment creation path does not consult the order's current state.

**Proposed fix.** Record the cancellation (a tombstone table or a `cancelled_orders` marker) and have
the creation path consult it; or have the creation path call the order service for the authoritative
order state before creating a shipment.

**Reproduction test.** Publish `CANCELLED` for an order that has no shipment yet, then publish
`CONFIRMED`; assert that no shipment is created.

**Acceptance criterion.** A shipment is never created for an order whose cancellation was already
observed.

### SHIP-18 — LOW — An unknown carrier name is silently substituted with the default adapter at runtime

**Evidence.** `carrier/CarrierFactory.java:44-50` (`(adapter != null) ? adapter : defaultCarrier`),
`carrier/CarrierAdapter.java:12-15` (`carrierCode()` rewrites a null carrier to `"MANUAL"`),
`test/.../carrier/CarrierConfigTest.java:51` (the test asserts the substitution).

**Actual behaviour.** `getCarrier("ghn")` on a deployment where GHN is not configured returns the
**MANUAL** adapter — a caller that believes it is talking to GHN is actually talking to a no-op
adapter that produces no tracking number and no carrier call. (At startup the same misconfiguration
*is* rejected, but with an `IndexOutOfBoundsException` from `CarrierConfig.java:26` rather than a
configuration error naming the bad value.)

**Violated invariant.** An unknown carrier must be an error, not a different carrier.

**Root cause.** The registry's lookup falls back instead of failing.

**Proposed fix.** Replace the registry with the `CarrierResolver` of §9 sketch 1, which throws
`CARRIER_NOT_CONFIGURED` for an unknown code, and make `CarrierConfig.primary` fail with a message
naming the invalid `shop.shipping.carrier` value.

**Acceptance criterion.** `getCarrier("unknown")` raises a `BusinessException`; a misconfigured
`shop.shipping.carrier` fails the startup with a message that names the property and the accepted
values.

### SHIP-19 — LOW — A carrier failure and a carrier rejection are indistinguishable

**Evidence.** `carrier/CarrierShipmentClient.java:64-69` (transport failure),
`:73-76` (empty body), `:78-83` (non-JSON body), `:90-94` (missing tracking number) — all
`ErrorCode.CARRIER_NOT_CONFIGURED` = `SHP-10006` = HTTP 409
(`utils/common-core/.../ErrorCode.java:117`);
`carrier/GhnCarrierAdapter.java:53-60` and `carrier/GhtkCarrierAdapter.java:47-54` (missing
configuration — same code); `test/.../carrier/CarrierShipmentClientTest.java:61-70` (the collapse is
locked in by a test).

**Actual behaviour.** A carrier that refuses the shipment (4xx) and a carrier that is unreachable
(timeout/5xx) produce the same error code and the same HTTP 409 — and the message says the carrier
is *not configured*, which is wrong when it is configured and failing. There is no concept of a
carrier-side rejection anywhere in the module.

**Violated invariant.** Different failure modes must be distinguishable by the caller and by the
operator.

**Root cause.** A single error code is used for every carrier-side outcome.

**Proposed fix.** Introduce the `CarrierFailureKind` taxonomy of §9 sketch 4 and map each kind to its
own error code and HTTP status (503 for `UNAVAILABLE`, 422 for `REJECTED`, 502 for
`INVALID_RESPONSE`).

**Acceptance criterion.** A 4xx from the carrier and a 500 from the carrier produce different error
codes and different HTTP statuses.

### SHIP-20 — MEDIUM — The auto-delivery counter is incremented before the durable write, so it over-counts when the write is rolled back

**Evidence.** `scheduler/ReconciliationScheduler.java:60` (`metrics.recordDelivered(true)`) executed
**before** `:61` (`writer.saveDelivered(shipment, true)`), against the `@Version` column
`entity/Shipment.java:72-74`; the same ordering defect in the webhook path:
`service/impls/WebhookEventServiceImpl.java:170` (`metrics.recordDelivered(false)`) before
`:175` (`writer.complete(...)`).

**Actual behaviour.** On two replicas, the replica whose commit loses the version check has already
incremented `shipping.delivered.count{auto="true"}` → the metric reports more auto-deliveries than
shipments that were actually delivered. The same holds for `shipping.advance.count` in
`ShipmentServiceImpl.advance` (`ShipmentServiceImpl.java:188`) and
`WebhookEventServiceImpl.process` (`:166`).

**Violated invariant.** A metric must count committed state changes, not attempted ones.

**Root cause.** The metric side effect is emitted before the transaction that performs the write.

**Proposed fix.** Move the metric increments after the successful save, or emit them from a
transaction-synchronisation/`@TransactionalEventListener(AFTER_COMMIT)` hook.

**Reproduction test.** Two threads reconcile the same stale shipment; assert
`shipping.delivered.count{auto="true"}` equals the number of rows that actually reached `DELIVERED`.

**Acceptance criterion.** Every counter equals the number of committed state changes, never the
number of attempts.

### SHIP-21 — MEDIUM — The reconciliation sweep lets an `OptimisticLockException` escape and abandons the rest of its batch

**Evidence.** `scheduler/ReconciliationScheduler.java:55-62` (the loop has no per-shipment
try/catch), `entity/Shipment.java:72-74` (the `@Version` column that produces the conflict on a
second replica).

**Actual behaviour.** Two replicas reconcile the same stale shipments; the loser's commit throws
`OptimisticLockException` → it escapes the `@Scheduled` method → Spring logs "Unexpected error
occurred in scheduled task" → **the remaining shipments in that replica's batch are not processed in
that tick**, and the sweep's work silently shifts to whichever replica wins each row.

**Violated invariant.** One failing row must not abort the processing of the others.

**Root cause.** No per-item error isolation in the batch loop.

**Proposed fix.** Catch `OptimisticLockException` per shipment and continue with the next one (or
adopt a scheduler lock so only one replica sweeps).

**Reproduction test.** Two threads call `reconcile()` over a list of stale shipments with one row
contended; assert that all non-contended rows are still processed and that no exception escapes.

**Acceptance criterion.** A contended row is skipped with a warning and the remaining rows are
processed; the scheduler method never throws.

### SHIP-22 — LOW — `@Autowired(required = false)` on a constructor parameter is meaningless and the second public constructor hides a wiring error

**Evidence.** `service/impls/ShipmentServiceImpl.java:43-57` (the annotated constructor) and
`:59-66` (a second public constructor that passes `carrierFactory = null`), `:84-86` (the null-check
fallback).

**Actual behaviour.** Spring ignores parameter-level `@Autowired`, so the "optional" marker has no
effect; the dependency is in fact required by the primary constructor. The five-argument constructor
is used by the unit tests (`ShipmentServiceImplTest.java:57`) and silently disables the factory
path, so the tested configuration differs from the production one in exactly the branch that
decides the carrier.

**Violated invariant.** The production wiring and the tested wiring must be the same.

**Root cause.** A convenience constructor was added for tests instead of injecting a test double.

**Proposed fix.** Keep a single constructor and inject a test stub of `CarrierFactory` (or an
interface) in the test.

**Acceptance criterion.** `ShipmentServiceImpl` has exactly one constructor, and the unit test
constructs it with the same dependency set as production.

### SHIP-23 — LOW — The backoffice list endpoint ignores pagination when `orderId` is supplied

**Evidence.** `service/impls/ShipmentServiceImpl.java:122-127` (a `PageImpl` built from a single
`Optional`, ignoring `pageable`), `controller/BackofficeShipmentController.java:38-50` (the endpoint
accepts `page`/`size`), `test/.../service/impls/ShipmentServiceImplTest.java:388-390` (the test
asserts that page 2 still returns the element).

**Actual behaviour.** `GET /api/v1/backoffice/shipments?orderId=…&page=2` returns the same single
shipment as page 0, and the response reports `totalElements = 1` regardless of the requested page —
a client paginating over the filtered list loops forever on the same record.

**Violated invariant.** The paging contract of a list endpoint.

**Root cause.** The `orderId` filter takes a different code path that ignores the `Pageable`.

**Proposed fix.** Either reject a non-zero page with `orderId` (or document the filter as
single-valued) or apply the pageable to the filtered query.

**Acceptance criterion.** With `orderId` and `page=1`, the response is empty and `totalElements`
matches the filter.

### SHIP-24 — LOW — Dead code in the outbox relay: an unused local and an empty `finally` block

**Evidence.** `outbox/ShippingOutboxRelay.java:101` (`Instant started = Instant.now();` — never
read) and `:170-171` (`finally { }` — an empty block).

**Actual behaviour.** The unused variable and the empty `finally` suggest that error handling was
planned and never written; the `try/finally` gives a false impression that failures are handled
inside the relay (they are not — a publish failure is handled per record, but an unexpected
`RuntimeException` in the drain loop escapes the scheduler).

**Violated invariant.** — (code hygiene)

**Root cause.** Leftovers from an earlier design.

**Proposed fix.** Delete both, and wrap the relay body in a catch that logs and swallows unexpected
runtime failures so one bad tick cannot kill the schedule.

**Acceptance criterion.** The relay has no unused local and no empty `finally`; an unexpected
exception in a tick is logged and does not stop the schedule.

### SHIP-25 — LOW — `ShippingWebhookProperties` exposes its mutable secret map by reference

**Evidence.** `config/ShippingWebhookProperties.java:11` (`private Map<String, String> secrets = new HashMap<>()`),
`:13-15` (`getSecrets()` returns the live map), `:17-19` (`setSecrets()` replaces it wholesale).

**Actual behaviour.** Any holder of the singleton properties bean can mutate the live secret map at
runtime; the map is also exposed to `/actuator/configprops` if that endpoint is enabled, and the
secrets are plain `String`s held in memory for the process lifetime.

**Violated invariant.** — (encapsulation)

**Root cause.** A mutable map is used as a properties bean without a defensive copy.

**Proposed fix.** Return `Map.copyOf(secrets)` (and store an unmodifiable map), or use a
`Map<String, String>` bound into an immutable holder.

**Acceptance criterion.** `getSecrets()` returns an unmodifiable map; a caller cannot mutate the
bean's state.

### SHIP-26 — LOW — The retention purge is one unbounded `DELETE` in a single transaction, executed by every replica

**Evidence.** `outbox/ShippingOutboxRetentionScheduler.java:20-32` (`@Transactional` +
`deleteByStatusAndSentAtBefore`), `outbox/OutboxEventRepository.java:41-43` (a bulk delete with no
`LIMIT` and no batching), `application.yml` / `@Scheduled(cron = ...)` with no leader election.

**Actual behaviour.** At 03:00 every replica runs the same unbounded delete of seven-day-old `SENT`
rows in one transaction; on a large backlog this is a long transaction that holds locks and can
block the relay's claim queries, and all replicas do identical work.

**Violated invariant.** A maintenance job must not be unbounded and must not run N times.

**Root cause.** No batching and no scheduler lock.

**Proposed fix.** Delete in bounded batches (`LIMIT 1000` in a loop) and add a scheduler lock, or let
the relay's own retention piggy-back on the claim loop.

**Acceptance criterion.** The purge runs in bounded batches, holds no long transaction, and only one
replica executes it per cycle.

### SHIP-27 — LOW — The stale sweep's predicate cannot use any index

**Evidence.** `repository/ShipmentRepository.java:40-44`
(`COALESCE(s.lastCarrierUpdate, s.carrierAcknowledgedAt) < :cutoff`), the only related index is
`idx_shipments_status_stale ON shipments (status, last_carrier_update)`
(`db/changelog/changelog-001-shipments.yaml:43`), and there is **no** index on `carrier_acknowledged_at`.

**Actual behaviour.** The hourly sweep scans every in-flight row, because the `COALESCE` expression
is not indexable and the second column of the composite index is not the one being compared.

**Violated invariant.** — (performance)

**Root cause.** A function was applied to the column in the predicate.

**Proposed fix.** Express the predicate as
`(s.lastCarrierUpdate IS NULL AND s.carrierAcknowledgedAt < :cutoff) OR s.lastCarrierUpdate < :cutoff`
and add an index on `(status, carrier_acknowledged_at)`.

**Acceptance criterion.** The sweep query uses an index scan on a populated table.

### SHIP-28 — LOW — The webhook event status is a stringly-typed state machine

**Evidence.** `entity/ShipmentEvent.java:30-34` (four `public static final String` constants),
`:56-57` (`@Column(name = "status", nullable = false, length = 16) private String status;`),
`db/changelog/changelog-004-expand-events-status-check.yaml:16-19` (the CHECK constraint that stands
in for the type).

**Actual behaviour.** The four event states are plain strings; a typo is a runtime
`DataIntegrityViolationException` from the database rather than a compile error, and the state
transitions of the retry machinery (`FAILED_RETRYABLE → PROCESSED / FAILED_PERMANENT`) are expressed
as string comparisons spread over
`service/impls/WebhookEventServiceImpl.java:80`, `:91`, `:116`, `:128`, `:174`, `:183` and
`scheduler/WebhookRetryScheduler.java:69`, `:81`, `:91`.

**Violated invariant.** — (type safety)

**Root cause.** The status was modelled as a string because the column was already created as
`varchar`.

**Proposed fix.** Model it as a Java enum (`ShipmentEventStatus`) with `@Enumerated(EnumType.STRING)`,
keeping the column type, and add the enum values to the CHECK constraint in a new additive changeset.

**Acceptance criterion.** No `String` comparison remains for the event status, and an invalid status
is rejected at compile time.

### SHIP-29 — HIGH — The container cannot start from the shipped compose stack: the application requires `SHIPMENT_WEBHOOK_SECRET_GHTK` but `docker-compose.yml` provides only the GHN secret

**Evidence.** `shipping-service/src/main/resources/application.yml:36`
(`GHTK: ${SHIPMENT_WEBHOOK_SECRET_GHTK:?SHIPMENT_WEBHOOK_SECRET_GHTK must be set}`) and `:35`
(the GHN equivalent); `docker-compose.yml:399` provides **only**
`SHIPMENT_WEBHOOK_SECRET_GHN: ${SHIPMENT_WEBHOOK_SECRET_GHN:?required}` — there is no
`SHIPMENT_WEBHOOK_SECRET_GHTK` entry, and no `GHTK` secret is exported by the compose file.

**Actual behaviour.** `docker compose up` on a machine without `SHIPMENT_WEBHOOK_SECRET_GHTK`
exported resolves the placeholder to an unresolved-token error and the application context aborts
during binding: the shipping-service never starts. The compose file's own `${…:?required}` guard
covers only the GHN secret, so the failure is invisible to the compose tooling — it surfaces as a
container that exits immediately.

Secondary problem: the fail-fast is **unconditional**. `shop.shipping.carrier` defaults to `manual`
(`application.yml:30`) and the default deployment therefore never talks to GHTK at all, yet the
service still refuses to boot without a GHTK secret.

**Violated invariant.** The reference deployment must start from the shipped configuration.

**Root cause.** The application treats the webhook secrets as unconditionally mandatory while the
deployment manifest treats only one of them as mandatory.

**Proposed fix.** Make the GHTK secret conditional in the compose file
(`${SHIPMENT_WEBHOOK_SECRET_GHTK:-}`) and make the application tolerate an absent secret for
carriers that are not enabled — the code already handles a missing secret correctly at runtime
(`WebhookEventServiceImpl.java:61-64` rejects a request for a carrier without a secret with 401), so
the fail-fast placeholder should only guard the carrier that is actually enabled.

**Reproduction test.** `docker compose config shipping-service` plus a start attempt with only the
GHN secret set; assert the container reaches a healthy state.

**Acceptance criterion.** With `SHOP_SHIPPING_CARRIER=manual` and only the GHN secret set, the
service starts and reports healthy; a request to a carrier without a configured secret is rejected
with 401 at runtime rather than at boot.

### SHIP-30 — LOW — `resilience4j-spring-boot4` is a declared but entirely unused dependency

**Evidence.** `shipping-service/pom.xml:67-70` (the dependency); a grep for `resilience4j`,
`@CircuitBreaker`, `@Retry`, `@Bulkhead`, `@TimeLimiter` and `@RateLimiter` over
`shipping-service/src` returns **no match** — the module uses none of it.

**Actual behaviour.** The module that makes the only outbound call to a third party (the carrier)
has a resilience library on the classpath and uses none of it: no timeout, no circuit breaker, no
bulkhead, no retry policy. The carrier call is a single unguarded blocking call
(`carrier/CarrierShipmentClient.java:29-35`).

**Violated invariant.** — (dependency hygiene)

**Root cause.** The dependency was added platform-wide and never wired up in this module.

**Proposed fix.** Either use it (time limiter + circuit breaker around the carrier call, §9 sketch 4)
or remove the dependency from this module's POM.

**Acceptance criterion.** Every declared resilience dependency is used by an annotation, or is
removed.

### SHIP-31 — LOW — `shop.shipping.notify-threshold-hours` is configured and exported but never read: the shipping-notification feature does not exist

**Evidence.** `shipping-service/src/main/resources/application.yml:32`
(`notify-threshold-hours: ${SHIPPING_NOTIFY_THRESHOLD_HOURS:72}`) and `docker-compose.yml:397`
(the same variable is exported); a grep for `notifyThreshold` / `notify-threshold` over
`shipping-service/src` matches **only** the YAML line — no Java code reads it.

**Actual behaviour.** An operator who sets `SHIPPING_NOTIFY_THRESHOLD_HOURS` reasonably concludes
that a shipping notification fires after that many hours. Nothing happens: there is no
notification path, no scheduler, no threshold check anywhere in the module.

**Violated invariant.** Configuration must correspond to implemented behaviour.

**Root cause.** The property survived from an earlier design; the feature was never implemented or
the property was never removed.

**Proposed fix.** Implement the threshold (a scheduled scan that raises an event for shipments in
flight beyond the threshold) or delete the property from `application.yml` and from the compose
file.

**Acceptance criterion.** Every property under `shop.shipping.*` is read by the code, or is removed.

### SHIP-32 — LOW — The shipped configuration carries default database credentials and a default issuer URI

**Evidence.** `shipping-service/src/main/resources/application.yml:6-8`
(`username: ${POSTGRES_USER:admin}`, `password: ${POSTGRES_PASSWORD:admin}`),
`:39` (`issuer-uri: ${SHOP_SECURITY_ISSUER_URI:http://localhost:9090/realms/ecommerce}`).

**Actual behaviour.** A deployment that forgets to set `POSTGRES_PASSWORD` silently starts against
the `admin/admin` account instead of failing; the compose stack overrides the datasource
(`docker-compose.yml:394`), but any other launcher does not.

**Violated invariant.** Missing production configuration must fail fast, not fall back to a weak
default.

**Root cause.** Inconsistent with the webhook secrets in the same file, which do use the
fail-fast `:?` form (`:35-36`).

**Proposed fix.** Use the fail-fast placeholder form for the datasource credentials and the issuer
URI, exactly as the webhook secrets do.

**Acceptance criterion.** The service refuses to start when `POSTGRES_PASSWORD` or
`SHOP_SECURITY_ISSUER_URI` is unset.

### SHIP-33 — HIGH (cross-module) — `spring-boot-restclient` is declared with `<scope>test</scope>` in shipping-service, payment-service and rating-service, removing the `RestClient.Builder` auto-configuration from the runtime classpath

**Evidence.**
* Baseline `shipping-service/pom.xml:83-87`:
  `<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-restclient</artifactId><scope>test</scope></dependency>`
  — the same three lines appear in baseline `payment-service/pom.xml:133-137` and baseline
  `rating-service/pom.xml:117-121`.
* `utils/common-spring/pom.xml:77-80` declares the same artifact **without** a scope (compile), so
  the artifact would be on the runtime classpath transitively — but a **direct** declaration in the
  service POM **overrides** the transitive scope (Maven's "nearest wins" rule for direct
  dependencies), demoting it to test scope.
* `utils/common-spring/pom.xml:46-48` (`spring-boot-starter-web`) and `:59-60`
  (`spring-boot-starter-actuator`) are compile-scoped, so every other type the module needs is
  present at runtime.
* The missing bean is `RestClient.Builder`, injected at
  `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java:21`:
  `public CarrierShipmentClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper)`.
* The bean is provided by `org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration`,
  which in Spring Boot 4 lives in the **`spring-boot-restclient` artifact** and is registered through
  that artifact's own
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  (verified in the local repository:
  `~/.m2/repository/org/springframework/boot/spring-boot-restclient/4.1.1/spring-boot-restclient-4.1.1.jar`
  → `org/springframework/boot/restclient/autoconfigure/RestClientAutoConfiguration.class` and an
  imports file listing `org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration`).
  With the artifact demoted to test scope, the auto-configuration class is not on the runtime
  classpath at all, so **no `RestClient.Builder` bean exists**.

**Runtime symptom (reported by the team lead from the docker compose run).**

```
Parameter 0 of constructor in com.shop.shippingservice.carrier.CarrierShipmentClient
required a bean of type 'org.springframework.web.client.RestClient$Builder' that could not be found.
```

The shipping-service container aborts during context refresh.

**Actual behaviour and which modules were really broken.**

| Module | Injects `RestClient.Builder`? | Defines its own `RestClient.Builder` bean? | Was it actually broken? |
|---|---|---|---|
| **shipping-service** | **yes** — `CarrierShipmentClient.java:21` (the only injection point in the module) | **no** — the module declares no `RestClient.Builder` bean anywhere | **YES — the container failed to start.** This is the only module whose startup depended on the auto-configured builder. |
| **rating-service** | yes — `rating-service/.../config/RestClientConfig.java:23` and `.../security/ServiceTokenProvider.java:32` | **yes** — `rating-service/.../config/RestClientConfig.java:60-64` (`@Bean @Scope("prototype") public RestClient.Builder restClientBuilder() { return RestClient.builder(); }`) | **No — latent only.** Its own prototype-scoped bean satisfies both injection points, so the context starts. The real (silent) damage is that Boot's `RestClientAutoConfiguration` was absent: the auto-configured builder (which applies the `RestClientCustomizer` beans and the observation instrumentation) never existed, and rating-service's manual `RestClient.builder()` is not customised by it — only the `TraceparentRestClientBuilderPostProcessor` (`utils/common-spring/.../TracingAutoConfiguration.java:66-92`) still adds the traceparent interceptor, because it is a `BeanPostProcessor` over any `RestClient.Builder` bean. rating-service also sets its own connect/read timeouts through an Apache HttpClient request factory (`rating-service/.../config/RestClientConfig.java:41-58`), so it is unaffected by the missing timeouts. |
| **payment-service** | **no** — a grep for `RestClient` over `payment-service/src/main` returns **nothing** | no | **No — latent only.** The module never touches `RestClient`, so the demoted scope changed nothing at runtime. |

**Violated invariant.** A module's runtime classpath must contain the artifact that provides the
auto-configuration for the beans it injects.

**Root cause.** A test-scoped declaration of a production artifact in three service POMs; Maven's
direct-dependency-wins rule silently replaced the compile-scoped transitive dependency from
`common-spring`, and nothing in the build or the test suite caught it because the module's own
tests construct the client by hand (`test/.../carrier/CarrierShipmentClientTest.java:32-34` uses
the static `RestClient.builder()` factory, never the injected builder), so the container-level
wiring was never exercised.

**Proposed fix.** Remove the `<scope>test</scope>` from the `spring-boot-restclient` declaration in
the three POMs (or delete the direct declaration entirely and rely on the compile-scoped transitive
dependency from `common-spring`, which is the cleaner option). *The lead has already applied the
scope removal in `shipping-service/pom.xml`, `payment-service/pom.xml` and `rating-service/pom.xml`;
this review does not modify any POM.*

**Reproduction test.** An IT that boots the **full** application context (not a slice) and asserts
that a `RestClient.Builder` bean exists — for example
`ShippingBootstrapIT` asserting `applicationContext.getBean(RestClient.Builder.class)` is not null.
The existing integration tests did not catch the defect because `ShippingBootstrapIT` never asserts
the presence of that bean.

**Acceptance criterion.** `mvn dependency:tree` for shipping-service, payment-service and
rating-service shows `org.springframework.boot:spring-boot-restclient` at **compile** scope, all three
containers start, and a context-boot IT asserts the `RestClient.Builder` bean is present.

---

## 11. STARTUP AND RUNTIME-CLASSPATH VERIFICATION (runtime evidence incorporated)

This section answers the four questions raised with the runtime evidence from the docker compose run.

### 11.1 Is `CarrierShipmentClient` the only injection point of `RestClient.Builder` in shipping-service?

**Yes.** A grep for `RestClient` over `shipping-service/src` returns 11 hits, of which exactly one
is an injection point:

* `carrier/CarrierShipmentClient.java:10` (the import), `:18` (the field), `:21` (the **injection
  point**: `public CarrierShipmentClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper)`).

The other ten hits are in the test sources, where the **static factory** is used instead of an
injected builder — `test/.../carrier/CarrierShipmentClientTest.java:11`, `:32`
(`RestClient.builder()`), `test/.../carrier/GhnCarrierAdapterTest.java:12`, `:31`, `:58`, and
`test/.../carrier/GhtkCarrierAdapterTest.java:12`, `:31`, `:58`. That is exactly why the defect was
never caught by the test suite: no test ever asks the **container** for a `RestClient.Builder`.

### 11.2 Does the module define a `RestClient.Builder` bean of its own?

**No.** The module declares no `@Bean` of that type: the only `@Bean` methods in the main sources are
`config/ClockConfig.java:11-14` (`Clock`), `carrier/CarrierConfig.java:18-27` (`CarrierAdapter`) and
`:29-32` (`CarrierFactory`). There is no `RestClientConfig` and no `RestClient.Builder` bean anywhere
in `shipping-service/src/main`. Hence the container error "required a bean of type
`org.springframework.web.client.RestClient$Builder` that could not be found" is exactly what the
code predicts: one injection point, zero local providers, and the auto-configuration that would have
provided it was off the runtime classpath.

### 11.3 Do the sibling modules define their own builder, and did they actually break?

* **rating-service — defines its own, therefore did NOT break.**
  `rating-service/src/main/java/com/shop/ratingservice/config/RestClientConfig.java:60-64`:

  ```java
  @Bean
  @Scope("prototype")
  public RestClient.Builder restClientBuilder() {
      return RestClient.builder();
  }
  ```

  This bean satisfies both of rating-service's injection points
  (`RestClientConfig.java:23` and `security/ServiceTokenProvider.java:32`), so its context starts
  even without the `spring-boot-restclient` artifact. Its scope defect is therefore **latent**: the
  only real consequence is that Boot's `RestClientAutoConfiguration` (customizers + observation) is
  absent, so the manual builder is not customised by it; the traceparent interceptor is still applied
  by `utils/common-spring/.../TracingAutoConfiguration.java:66-92` (a `BeanPostProcessor` over any
  `RestClient.Builder` bean). rating-service also sets its own connect/read timeouts
  (`RestClientConfig.java:41-58`), so it is not exposed to SHIP-03.
* **payment-service — does not use `RestClient` at all, therefore did NOT break.**
  A grep for `RestClient` over `payment-service/src/main` returns **no match**. The test-scoped
  declaration was harmless; the defect was purely latent.

**Only shipping-service was actually broken**, and it was broken completely: the container could not
start.

### 11.4 A systematic sweep for the same class of problem in shipping-service

Every externally-provided type the module injects was checked against the providing artifact and its
scope. `spring-boot-starter-web` (`utils/common-spring/pom.xml:47`),
`spring-boot-starter-actuator` (`:59`), `spring-boot-starter-validation` (`:51`) and
`spring-boot-starter-oauth2-resource-server` (`:55`) are all compile-scoped in `common-spring`,
and `spring-boot-starter-data-jpa`, `spring-boot-starter-liquibase` and `spring-boot-starter-kafka`
are declared directly in `shipping-service/pom.xml:38-52`.

| Injected type | Injection point | Provider | On the runtime classpath? |
|---|---|---|---|
| `RestClient.Builder` | `carrier/CarrierShipmentClient.java:21` | `spring-boot-restclient` auto-config | ✗ **was demoted to test scope — SHIP-33** |
| `ObjectMapper` | `carrier/CarrierShipmentClient.java:22`, `outbox/ShippingEventPublisherImpl.java:27` | Jackson via `spring-boot-starter-web` + Boot's `JacksonAutoConfiguration` | ✓ |
| `Clock` | `service/impls/ShipmentServiceImpl.java:45`, `service/impls/WebhookEventServiceImpl.java:54`, `scheduler/ReconciliationScheduler.java:36` | `config/ClockConfig.java:11-14` (local) | ✓ |
| `KafkaTemplate<String,String>` (`@Qualifier("stringKafkaTemplate")`) | `kafka/ShippingListenerConfig.java:18` | `utils/common-kafka/.../KafkaAutoConfiguration.java:70-71`, registered through common-kafka's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | ✓ |
| `KafkaProperties` | `kafka/ShippingListenerConfig.java:17` | `KafkaAutoConfiguration.java:46` (`@EnableConfigurationProperties`) | ✓ |
| `KafkaMessagePublisher` | `outbox/ShippingOutboxRelay.java:48` | `KafkaAutoConfiguration.java:97-101` | ✓ |
| `MeterRegistry` | `scheduler/WebhookRetryScheduler.java:37`, `service/ShippingMetrics.java:16` | actuator (compile scope via `common-spring`) + Boot's `MetricsAutoConfiguration` | ✓ |
| `PlatformTransactionManager` | `outbox/ShippingOutboxRelay.java:49`, `scheduler/WebhookRetryScheduler.java:36` | `spring-boot-starter-data-jpa` (direct) | ✓ |
| `JwtDecoder` | common-security's `BaseSecurityConfig.java:112-116` | `spring-boot-starter-oauth2-resource-server` (compile scope via `common-spring`) | ✓ |
| `AuditEventWriter` | common-logging (the `@Audited` aspect) | `common-logging` (direct, `pom.xml:61-65`) | ✓ |
| `DataSource`, `SpringLiquibase`, `EntityManager` | JPA / Liquibase | `spring-boot-starter-data-jpa`, `spring-boot-starter-liquibase` (direct) | ✓ |
| `ShippingWebhookProperties` | `service/impls/WebhookEventServiceImpl.java:43` | `@ConfigurationPropertiesScan` on `ShippingServiceApplication.java:9` | ✓ |
| `CarrierAdapter` (`@Primary`) | `service/impls/ShipmentServiceImpl.java:47` | `carrier/CarrierConfig.java:18-27` (local) | ✓ |

**The `RestClient.Builder` scope demotion was the only classpath-scope defect in this module.**

Two further start-up blockers were found that are **not** classpath problems but still prevent the
container from starting:

1. **SHIP-29 (HIGH)** — `SHIPMENT_WEBHOOK_SECRET_GHTK` is required by `application.yml:36` but is
   not supplied by `docker-compose.yml` (only the GHN secret is, at `:399`), so the compose stack
   cannot start the service as shipped.
2. The `JwtDecoder` is built lazily (`utils/common-security/.../BaseSecurityConfig.java:115-116` wraps
   `NimbusJwtDecoder.withIssuerLocation(...)` in a `LazyJwtDecoder`), so an unreachable Keycloak does
   **not** prevent start-up — no finding, recorded here so the absence is a verified result rather
   than an omission.

Finally, the module's own integration tests cannot catch a missing auto-configured bean, because
`test/.../support/AbstractIntegrationTest.java:41` sets `spring.jpa.hibernate.ddl-auto=none` and
`test/.../support/ShippingBootstrapIT.java:34-72` asserts only the tables, the listener factory, the
consumer liveness, the carrier adapter and the scheduler bean. **A `RestClient.Builder` assertion
belongs in `ShippingBootstrapIT`** (the acceptance criterion of SHIP-33).

---
