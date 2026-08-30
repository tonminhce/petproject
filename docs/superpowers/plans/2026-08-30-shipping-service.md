# Shipping Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `shipping-service` — the fleet's second Kafka consumer and order-lifecycle completer: turns CONFIRMED orders into shipments via a `CarrierAdapter` port (manual-first), advances them by per-carrier HMAC-signed webhooks, auto-delivers stale in-flight shipments hourly, and publishes `shipping.delivered.v1` via outbox+relay.

**Architecture:** Fleet-standard Boot 4.1.1 microservice (`com.shop.shippingservice`): PostgreSQL + Liquibase (`shipments`, `shipment_events`); common-kafka consumer stack (`BaseKafkaListenerConfig` subclass, notification precedent incl. `@EnableKafka`) consuming `shop.order.lifecycle.v1` (group `shipping-service`); carrier webhook endpoint per-carrier-secret HMAC; hourly reconciliation scheduler (`Clock`-injected for testability); Micrometer counters (OrderMetrics precedent); outbox+relay ported from order-service reference.

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Spring Kafka (common-kafka), Lombok, JUnit 5 + Mockito + AssertJ + Testcontainers (PostgreSQL + Kafka + awaitility). No WireMock; IT self-plays the carrier with `TestRestTemplate` + hand-computed HMAC.

**Spec:** [`docs/superpowers/specs/2026-08-30-shipping-service-design.md`](../specs/2026-08-30-shipping-service-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.shippingservice` (scaffold exists: `ShippingServiceApplication`). Port **8087**. DB `shippingservice` (init SQL line pre-exists — verify-only). Compose stanza + `ServiceRoute.SHIPPING` pre-exist — env modernization only, gateway verify-only.
- **Lane rule (parallel epics):** shipping lane = **W2**. Task 1's shared-file edits are BLOCKED until the payment epic's anchor lands and the branch-to-branch unlock merge runs (af71412 mechanism). Sequence gate below; if it fails: STOP.
- **ErrorCode anchor:** after payment's Task 1, the LAST enum entry is `AMOUNT_MISMATCH("PAY-5007", "payment.amount_mismatch", HttpStatus.BAD_REQUEST);` → flip its `;` to `,`, append SHP-10001..10006 with `;` on the new last entry. If anchor not found: STOP — sequence violation.
- **Never edit** `order-service`, `payment-service`, `notification-service`. Read-only truth: `OrderEventPublisherImpl` (payload wrapper), `OrderOutboxRelay` (relay port), notification's `NotificationListenerConfig`/`BaseKafkaListenerConfig` (consumer stack — INCLUDING the T10 rulings: `@EnableKafka` on the factory-owning config; key deserializer is StringDeserializer).
- **Order event contract (inbound):** topic `shop.order.lifecycle.v1`, wrapper `{eventId, eventType, occurredAt, ...data}`; shipping reacts to `order.updated.v1` where `status == "CONFIRMED"` (create shipment) and where `status == "CANCELLED"` (cancel un-shipped shipment). All other events ignored (ack).
- **Shipment event contract (outbound):** topic `shop.shipping.lifecycle.v1`, wrapper `{eventId, eventType, occurredAt, orderId, shipmentId, carrier, trackingNumber, autoDelivered}`; eventType `shipping.delivered.v1`.
- **FSM (spec D2):** `CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`; any in-flight (PICKED_UP+) → `DELIVERY_FAILED`; `DELIVERY_FAILED → IN_TRANSIT` (retry). Pure time NEVER delivers: scheduler only flips in-flight states.
- **HMAC:** same mechanism as payment (HmacSHA256, hex, `MessageDigest.isEqual`) but PER-CARRIER secret lookup: `shop.shipping.webhook.secrets.<carrier>` map; unknown/unconfigured carrier → 401 before body processing.
- `@WebMvcTest`: `org.springframework.boot.webmvc.test.autoconfigure` + seed `JwtAuthenticationToken` + `@Import(ApiExceptionHandler.class)`.
- Harness = notification-service's (singleton PG+Kafka, earliest reset) + fixed `Clock` bean + recorder `@KafkaListener` on `shop.shipping.lifecycle.v1`.
- TIMESTAMPTZ audit columns; Campaign-precedent entities (`@Version Long` = `0L` `@Builder.Default`).
- Security carve-out: reuse whatever public-paths mechanism payment's Task 10 lands (verify it exists before Task 8; if shipping runs first, port from payment's branch).

## File Map

| File | Action |
|------|--------|
| `shipping-service/pom.xml` | MODIFY — deps (Task 1) |
| `utils/common-core/.../ErrorCode.java` | MODIFY — SHP-1xxxx tail (Task 1, gated) |
| `utils/common-core/.../constants/ApiPaths.java` | MODIFY — BACKOFFICE_SHIPMENTS + WEBHOOK_SHIPPING (Task 1, gated) |
| `utils/common-spring/.../messages_{en,vi}.properties` | MODIFY — 6 `shipping.*` keys (Task 1, gated) |
| `.../application.yml` + `db/changelog/*changelog-001-shipments.yaml` | CREATE (Task 2) |
| `.../constant/ShipmentStatus.java`, `.../constant/Carrier.java`, `.../entity/Shipment.java`, `.../entity/ShipmentEvent.java` | CREATE (Task 3) |
| `.../repository/ShipmentRepository.java`, `ShipmentEventRepository.java` | CREATE (Task 4) |
| `.../service/ShipmentStateMachine.java` | CREATE (Task 5) |
| `.../carrier/CarrierAdapter.java`, `ManualCarrierAdapter.java`, `NoopCarrierAdapter.java`, `CarrierConfig.java` | CREATE (Task 6) |
| `.../kafka/ShippingListenerConfig.java`, `.../kafka/OrderEventConsumer.java`, `.../service/ShipmentService(+Impl)` | CREATE (Task 7) |
| `.../webhook/CarrierWebhookController.java` + `WebhookEventService` | CREATE (Task 8) |
| `.../controller/BackofficeShipmentController.java` | CREATE (Task 9) |
| `.../scheduler/ReconciliationScheduler.java`, `.../service/ShippingMetrics.java` | CREATE (Task 10) |
| `.../outbox/` port + `ShippingEventPublisher.java` | CREATE (Task 11) |
| `.../support/` harness + `ShippingBootstrapIT.java` | CREATE (Task 12) |
| `.../ShippingFlowIT.java` | CREATE (Task 13) |
| compose env modernization | Task 14 |
| final review | Task 15 |

---

### Task 1: pom deps + ErrorCode SHP tail + ApiPaths + i18n  (SEQUENCE-GATED)

**Files:** Modify `shipping-service/pom.xml`, `ErrorCode.java`, `ApiPaths.java`, `messages_{en,vi}.properties`

**Interfaces:** `ErrorCode.SHIPMENT_NOT_FOUND` (SHP-10001, 404), `SHIPMENT_DUPLICATE` (SHP-10002, 409), `SHIPMENT_INVALID_TRANSITION` (SHP-10003, 409), `WEBHOOK_SIGNATURE_INVALID` (SHP-10004, 401), `TRACKING_REQUIRED` (SHP-10005, 400), `CARRIER_NOT_CONFIGURED` (SHP-10006, 409); `ApiPaths.BACKOFFICE_SHIPMENTS = API_V1 + "/backoffice/shipments"`; `ApiPaths.WEBHOOK_SHIPPING = API_V1 + "/webhooks/shipping"`; i18n keys `shipping.not_found`, `shipping.duplicate`, `shipping.invalid_transition`, `shipping.webhook_signature_invalid`, `shipping.tracking_required`, `shipping.carrier_not_configured` (EN+VI).

- [ ] **Step 0: GATE** — verify ErrorCode last entry is `PAY-5007` (payment Task 1 merged into this branch via unlock-merge). Not found → STOP.
- [ ] **Step 1: pom** — promotion's dep list + `spring-kafka` + `common-kafka` (notification's pom is the closest copy source); no mail/storage deps.
- [ ] **Step 2: ErrorCode** — append SHP-10001..10006 in order; `;` on SHP-10006. (5-digit block: thousands 1–9 exhausted — spec D6 documents the fleet extension.)
- [ ] **Step 3: ApiPaths** — two constants (`API_V1 +` concat style).
- [ ] **Step 4: i18n** — EN/VI for all 6 keys.
- [ ] **Step 5:** `./mvnw -pl utils/common-core,utils/common-spring install -q && ./mvnw -pl shipping-service compile -q` green.
- [ ] **Step 6: Commit** — `feat(shipping-service): deps + SHP-10001..10006 + api paths + i18n`

### Task 2: application.yml + Liquibase

**Files:** Create `application.yml`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changelog-001-shipments.yaml`

- [ ] **Step 1: yml** — port 8087; liquibase master; `shop.kafka.bootstrap-servers` + `consumer.group-id: shipping-service` + `auto-offset-reset: latest`; `shop.shipping.auto-deliver-days: ${SHIPMENT_AUTO_DELIVER_DAYS:7}`; `shop.shipping.notify-threshold-hours: ${SHIPPING_NOTIFY_THRESHOLD_HOURS:72}`; `shop.shipping.webhook.secrets.<carrier>` map from env (`SHIPMENT_WEBHOOK_SECRET_GHN` etc., all defaulting empty); security block mirrors notification's.
- [ ] **Step 2: changelog-001** — `shipments`: `id UUID`, `order_id UUID NOT NULL`, `carrier VARCHAR(16) NOT NULL` CHECK in ('MANUAL','NOOP','GHN','GHTK'), `tracking_number VARCHAR(64) NULL`, `status VARCHAR(24) NOT NULL` CHECK in ('CREATED','PICKED_UP','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','DELIVERY_FAILED','CANCELLED'), `previous_status VARCHAR(24) NULL`, `auto_delivered BOOLEAN NOT NULL DEFAULT false`, `last_carrier_update TIMESTAMPTZ NULL`, `delivered_at TIMESTAMPTZ NULL`, full audit set. Raw SQL: `CREATE UNIQUE INDEX uk_shipment_order_live ON shipments (order_id) WHERE deleted = false;`, `CREATE INDEX idx_shipments_status_stale ON shipments (status, last_carrier_update);`. `shipment_events`: `id UUID`, `shipment_id UUID NULL` (webhook may arrive before shipment known → FAILED row), `carrier VARCHAR(16) NOT NULL`, `provider_event_id VARCHAR(128) NOT NULL`, `type VARCHAR(32) NOT NULL`, `payload TEXT NOT NULL`, `status VARCHAR(16) NOT NULL` CHECK in ('PROCESSED','FAILED'), audit set. Raw SQL: `CREATE UNIQUE INDEX uk_shipment_events_carrier_event ON shipment_events (carrier, provider_event_id);`
- [ ] **Step 3:** compile + changelog parses. **Step 4: Commit** — `feat(shipping-service): config + shipments tables`

### Task 3: Enums + entities

**Files:** Create `.../constant/ShipmentStatus.java`, `.../constant/Carrier.java`, `.../entity/Shipment.java`, `.../entity/ShipmentEvent.java`

**Interfaces:** `enum ShipmentStatus { CREATED, PICKED_UP, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, CANCELLED }` + `static boolean inFlight(ShipmentStatus s)` (PICKED_UP..OUT_FOR_DELIVERY — the scheduler's predicate). `enum Carrier { MANUAL, NOOP, GHN, GHTK }`. `Shipment`: `UUID orderId`, `Carrier carrier`, `String trackingNumber` (nullable), `ShipmentStatus status`, `ShipmentStatus previousStatus` (nullable), `boolean autoDelivered`, `Instant lastCarrierUpdate` (nullable), `Instant deliveredAt` (nullable), `@Version Long = 0L`. `ShipmentEvent`: `UUID shipmentId` (nullable), `Carrier carrier`, `String providerEventId`, `String type`, `String payload`, `String status`.

- [ ] **Step 1:** write all four; **Step 2:** compile; **Step 3: Commit** — `feat(shipping-service): Shipment + ShipmentEvent entities`

### Task 4: Repositories

**Files:** Create `.../repository/ShipmentRepository.java`, `.../repository/ShipmentEventRepository.java`

**Interfaces:** `ShipmentRepository`: `Optional<Shipment> findById(UUID);` `Optional<Shipment> findByOrderId(UUID orderId);` (unique live — returns the one), `Page<Shipment> findAllByOrderByCreatedAtDesc(Pageable p);` `Page<Shipment> findAllByStatusOrderByCreatedAtDesc(ShipmentStatus s, Pageable p);` `Page<Shipment> findAllByCarrierOrderByCreatedAtDesc(Carrier c, Pageable p);` `List<Shipment> findByStatusInAndLastCarrierUpdateBefore(Collection<ShipmentStatus> statuses, Instant cutoff);` `boolean existsByOrderId(UUID orderId);`. `ShipmentEventRepository`: `boolean existsByCarrierAndProviderEventId(String carrier, String eventId);`

- [ ] **Step 1:** write; **Step 2:** compile; **Step 3: Commit** — `feat(shipping-service): shipment repositories`

### Task 5: ShipmentStateMachine (TDD)

**Files:** Create `.../service/ShipmentStateMachine.java`

**Interfaces:** `static ShipmentStatus transition(ShipmentStatus from, ShipmentStatus to)` — legal: full happy chain edges, any in-flight → DELIVERY_FAILED, DELIVERY_FAILED → IN_TRANSIT, CREATED → CANCELLED, in-flight → CANCELLED (ops only); illegal (CREATED→DELIVERED, DELIVERED→anything, terminal loops) → `BusinessException(SHIPMENT_INVALID_TRANSITION)`. Pure static.

- [ ] **Step 1 (RED):** happy-chain walk test + every illegal jump (exhaustive pair check for 7×7 in a loop — assert exactly the legal set).
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): shipment state machine`

### Task 6: CarrierAdapter port (TDD)

**Files:** Create `.../carrier/CarrierAdapter.java`, `.../carrier/ManualCarrierAdapter.java`, `.../carrier/NoopCarrierAdapter.java`, `.../carrier/CarrierConfig.java`

**Interfaces:** `interface CarrierAdapter { Carrier carrier(); ShipmentDraft createShipment(UUID orderId); }` where `ShipmentDraft(String trackingNumber, ShipmentStatus initialStatus)`. `ManualCarrierAdapter`: draft = `(null, CREATED)` — tracking comes later from admin (`TRACKING_REQUIRED` enforced at the tracking endpoint, not here). `NoopCarrierAdapter` (`@ConditionalOnProperty(name="shop.shipping.carrier", havingValue="noop")`): draft = `("NOOP-"+shipmentId, PICKED_UP)`. Selection: `shop.shipping.carrier: MANUAL` default; `CarrierConfig` primary resolver (exactly-one assertion, payment Task 6 pattern).

- [ ] **Step 1 (RED):** config resolves manual by default; noop condition; drafts shape-correct.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): CarrierAdapter port (manual default, noop auto)`

### Task 7: Order consumer + ShipmentService (TDD)

**Files:** Create `.../kafka/ShippingListenerConfig.java`, `.../kafka/OrderEventConsumer.java`, `.../service/ShipmentService.java`, `.../service/impls/ShipmentServiceImpl.java`, `.../service/ShipmentWriter.java`

**Interfaces:**
- `ShippingListenerConfig extends BaseKafkaListenerConfig<String, OrderLifecycleEvent>` — `@Configuration @EnableKafka` (T10 notif ruling: without it the listener is inert), factory bean `shippingListenerFactory`.
- `OrderEventConsumer extends BaseKafkaConsumer<String, OrderLifecycleEvent>` — `@Component`, `@KafkaListener(topics = "shop.order.lifecycle.v1", containerFactory = "shippingListenerFactory")` → `shipmentService.handleOrderEvent(event)`.
- `OrderLifecycleEvent` DTO: tolerant copy of notification's (`@JsonIgnoreProperties(ignoreUnknown=true)`, wrapper + `orderId` + `status` fields).
- `ShipmentService.handleOrderEvent(OrderLifecycleEvent e)` — **persist-first/ack-always**: `status=="CONFIRMED"` → `existsByOrderId` fast-path; create shipment via adapter (dup race → `SHIPMENT_DUPLICATE` catch DIVE → ack no-op); `status=="CANCELLED"` → shipment CREATED → CANCELLED (in-flight/shipped: ignore, log); other statuses → ack no-op. Idempotent on the order `eventId` too: processed-eventId ledger is the shipment itself (exists check), matching spec D3 — no separate dedupe table this epic.
- `ShipmentWriter` `@Repository @Transactional` (commit-time translation precedent).

- [ ] **Step 1 (RED):** `ShipmentServiceImplTest` (mocks): CONFIRMED → adapter called + row inserted CREATED (manual); CONFIRMED duplicate (exists=true) → no insert; CONFIRMED DIVE race → no crash; CANCELLED CREATED → CANCELLED; CANCELLED in-flight → untouched; status `DELIVERED` event → no-op.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): order consumer + idempotent shipment creation`

### Task 8: Carrier webhook receiver (TDD)

**Files:** Create `.../webhook/CarrierWebhookController.java`, `.../service/WebhookEventService.java`, `.../webhook/CarrierWebhookPayload.java`

**Interfaces:**
- `POST ApiPaths.WEBHOOK_SHIPPING + "/{carrier}"` — raw body + `X-Webhook-Signature`. Secret lookup by `{carrier}` from `shop.shipping.webhook.secrets` map; missing/blank → `WEBHOOK_SIGNATURE_INVALID` 401 BEFORE parsing. Bad signature → 401, no state change, no event row.
- `CarrierWebhookPayload`: `eventId, eventType, trackingNumber, carrierStatus` (PICKED_UP|IN_TRANSIT|OUT_FOR_DELIVERY|DELIVERED|DELIVERY_FAILED).
- `WebhookEventService.handle(carrier, body, signature)` — persist-first/ack-always: dedupe `(carrier, eventId)` → ack no-op; insert `ShipmentEvent` own tx (DIVE → ack no-op); resolve shipment by trackingNumber (unknown → event FAILED, ack); FSM advance via `ShipmentStateMachine` (illegal → event FAILED, ack); update `last_carrier_update = now` (injected `Clock`), `delivered_at` when DELIVERED + outbox row (same tx — Task 11 publisher) + metrics; ack 200 ALWAYS.

- [ ] **Step 1 (RED):** `WebhookEventServiceTest` (mocks): valid advance + last_carrier_update set; DELIVERED → delivered_at + outbox + `shipping.delivered.count` counter; replay no-op; unknown tracking → event FAILED; illegal transition → event FAILED; unconfigured carrier → 401 before anything.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement (+ security public-paths carve-out, same mechanism as payment — verify it landed) → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): per-carrier signed webhook receiver`

### Task 9: Admin backoffice API

**Files:** Create `.../controller/BackofficeShipmentController.java`

**Interfaces:** class-level `@PreAuthorize("hasRole('ADMIN')")`. `GET .../shipments` (filters status/carrier/orderId, paged newest-first), `GET .../shipments/{id}` (SHP-10001), `POST .../shipments/{id}/tracking` body `{trackingNumber}` (CREATED+manual only; blank → SHP-10005; success → CREATED→PICKED_UP + last_carrier_update), `POST .../shipments/{id}/transition` body `{status}` (FSM via Task 5; illegal → SHP-10003), `POST .../shipments/{id}/fail`, `POST .../shipments/{id}/retry`. `ShipmentResponse` record mirrors entity + createdAt.

- [ ] **Step 1 (RED):** `@WebMvcTest` matrix: anon 401, USER 403, ADMIN 200 (list + by-id + tracking + transition), unknown id 404 SHP-10001, blank tracking 400 SHP-10005, illegal transition 409 SHP-10003 (service-throw mapping), filter params verified to service (Mockito).
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): admin shipment backoffice API`

### Task 10: Reconciliation scheduler + metrics (TDD)

**Files:** Create `.../scheduler/ReconciliationScheduler.java`, `.../service/ShippingMetrics.java`

**Interfaces:** `ShippingMetrics` (OrderMetrics precedent): counters `shipping.delivered.count{auto}`, `shipping.failed.count`, `shipping.advance.count{from,to}`, gauge `shipping.stale.inflight`. `ReconciliationScheduler` `@Scheduled(cron = "${shop.shipping.reconcile-cron:0 0 * * * *}")` (hourly): `findByStatusInAndLastCarrierUpdateBefore(inFlight, now - autoDeliverDays)` → per shipment: FSM to DELIVERED, `autoDelivered=true`, `delivered_at`, outbox row (`autoDelivered:true`), counter `delivered{auto=true}`. Pure time never touches CREATED (inFlight excludes it by construction).

- [ ] **Step 1 (RED):** unit with fixed `Clock`: in-flight past cutoff → flipped + flagged + counter; in-flight under cutoff → untouched; CREATED past cutoff → untouched (explicit test); empty repo → no-op.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(shipping-service): hourly stale-delivery reconciliation + metrics`

### Task 11: Outbox + relay + event publisher

**Files:** Create `.../outbox/OutboxEvent.java`, `.../outbox/OutboxEventRepository.java`, `.../outbox/ShippingOutboxRelay.java`, `.../outbox/ShippingEventPublisher.java`; **changelog-001 add `outbox_events`** (order column set 1:1).

**Interfaces:** faithful port (Task 8 of payment plan is the freshest copy in-fleet — read order's original as truth). `ShippingEventPublisher.publishDelivered(Shipment s, boolean autoDelivered)` → wrapper per Global Constraints (eventId fresh UUID, eventType `shipping.delivered.v1`), outbox row in caller's tx.

- [ ] **Step 1:** port + changelog. **Step 2:** compile. **Step 3: Commit** — `feat(shipping-service): outbox + relay + delivered event publisher`

### Task 12: Harness + BootstrapIT

**Files:** Create `.../support/AbstractIntegrationTest.java`, `.../config/TestLiquibaseConfig.java`, `.../config/TestClockConfig.java`, `.../ShippingBootstrapIT.java`

**Interfaces:** notification's harness (singleton PG+Kafka, earliest reset) + `TestClockConfig` (fixed mutable `Clock` bean the scheduler/webhooks use — IT advances it) + test `@KafkaListener` recorder on `shop.shipping.lifecycle.v1` + webhook secret for carrier `GHN` set to a fixed test value. `TestLiquibaseConfig` verbatim.

- [ ] **Step 1:** adapt. **Step 2:** BootstrapIT: context boots, tables exist, listener factory + `@EnableKafka` live (consumer bean present), manual adapter default.
- [ ] **Step 3:** full module test green (wedge retry-once).
- [ ] **Step 4: Commit** — `test(shipping-service): harness + bootstrap IT`

### Task 13: ShippingFlowIT (capstone)

**Files:** Create `.../ShippingFlowIT.java`

**Interfaces:** `@SpringBootTest` + `TestRestTemplate` + recorder listener + mutable test Clock. Sends order events via `KafkaTemplate<String,String>` (fresh eventId each send).

- [ ] **Step 1:** scenarios (awaitility 20s):
  1. order CONFIRMED event → shipment row CREATED (manual), exactly 1 under a duplicate CONFIRMED re-send (fresh eventId, same order — exists-check path)
  2. admin tracking POST → PICKED_UP
  3. signed GHN webhooks walk IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED; `last_carrier_update` advances; recorder sees `shipping.delivered.v1` with `autoDelivered:false`
  4. webhook replay (same eventId) → no extra transitions, recorder count unchanged
  5. DELIVERY_FAILED webhook → status flipped; admin retry → IN_TRANSIT
  6. stale sweep: new order CONFIRMED → tracking → PICKED_UP → advance Clock past `auto-deliver-days` → trigger scheduler bean directly (`@Async`-safe: call method) → DELIVERED `auto_delivered=true`, recorder event `autoDelivered:true`
  7. CREATED shipment + Clock far past → run scheduler → untouched (pure-time-never-delivers invariant)
  8. order CANCELLED on CREATED shipment → CANCELLED
- [ ] **Step 2:** `./mvnw -pl shipping-service test` green ×1 + repeat once.
- [ ] **Step 3: Commit** — `test(shipping-service): end-to-end flow IT (dedupe, webhook walk, stale sweep)`

### Task 14: Compose env modernization (spec D10)

**Files:** Modify `docker-compose.yml` (shipping stanza only)

- [ ] **Step 1:** `KAFKA_SERVERS: kafka:9092` → `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` (verify stanza's env block shape — POSTGRES_* style block → `SPRING_DATASOURCE_URL` if present, else keep); add `SHIPMENT_AUTO_DELIVER_DAYS: ${SHIPMENT_AUTO_DELIVER_DAYS:-7}`, `SHIPPING_NOTIFY_THRESHOLD_HOURS: ${SHIPPING_NOTIFY_THRESHOLD_HOURS:-72}`, `SHIPMENT_WEBHOOK_SECRET_GHN: ${SHIPMENT_WEBHOOK_SECRET_GHN:-}` (placeholder per-carrier pattern). Nothing else.
- [ ] **Step 2:** `docker compose config -q` green. **Step 3: Commit** — `chore(shipping-service): compose fleet-style envs + webhook secret placeholders`

### Task 15: Final whole-branch review

- [ ] **Step 1:** review-package BASE→HEAD; reviewer checks: D1 adapter port (service never imports concrete adapters), D2 FSM exhaustive-pair test + CREATED-never-stale, D3 consumer idempotency (exists + DIVE race), D4 per-carrier HMAC + persist-first, D5 manual write path, D6 codes vs spec, D7 matrix + carve-out, D8 partial unique index on order_id, D9 metric tags, D10 port 8087/envs/hourly cron, zero order/payment/notification edits, `@EnableKafka` present (T10 ruling).
- [ ] **Step 2:** fix rounds per review; ledger close. Ops note: shipping is live-on-merge for CONFIRMED orders (consumer, latest offset); auto-deliver = 7d default; carrier webhooks need per-carrier secrets + gateway exposure decision in the wiring task.
