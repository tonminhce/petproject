# Payment Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `payment-service` — provider-pluggable payments (MockProvider in compose, StripeProvider skeleton) driven by HMAC-signed webhooks, idempotent creates, persist-first/ack-always webhook handling, outbox→relay events on `shop.payment.lifecycle.v1`, receipts to object storage, admin read API.

**Architecture:** Fleet-standard Boot 4.1.1 microservice (`com.shop.paymentservice`): PostgreSQL + Liquibase (`payments`, `payment_events`); `PaymentProvider` port with `@ConditionalOnProperty` selection (default `mock`); webhook endpoint authenticated by HMAC-SHA256 (javax.crypto.Mac, no new deps), sole write path to terminal states; outbox+relay ported from order-service's reference impl (`OrderOutboxRelay`/`OutboxEvent`); `common-storage` `ObjectStorageService` for receipts (failure → log + continue).

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Spring Kafka (common-kafka), common-storage (S3), Lombok, JUnit 5 + Mockito + AssertJ + Testcontainers (PostgreSQL + Kafka + awaitility). No WireMock; IT self-plays the provider with `TestRestTemplate` + hand-computed HMAC.

**Spec:** [`docs/superpowers/specs/2026-08-30-payment-service-design.md`](../specs/2026-08-30-payment-service-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.paymentservice` (scaffold exists: `PaymentServiceApplication`). Port **8085**. DB `paymentservice` (init SQL line pre-exists — verify-only). Compose stanza + `ServiceRoute.PAYMENT` pre-exist — env modernization only, gateway verify-only.
- **Lane rule (parallel epics):** payment lane = **W1 shared-file owner** (ErrorCode/ApiPaths/i18n) until merge. Shipping lane never touches shared files until the unlock-merge.
- **ErrorCode anchor:** `NOTIFICATION_NOT_FOUND("NTF-9001", "notification.not_found", HttpStatus.NOT_FOUND);` is the LAST enum entry → flip its `;` to `,`, append PAY-5003..5007 with `;` on the new last entry. If anchor not found: STOP — sequence violation.
- **Never edit** `order-service`, `shipping-service`, `notification-service`. Read-only truth: `order-service/.../entity/OutboxEvent.java`, `.../service/impls/OrderOutboxRelay.java`, `common-core OutboxStatus`.
- **Event contract (outbound):** topic `shop.payment.lifecycle.v1`, wrapper `{eventId: UUID-string, eventType, occurredAt, orderId, paymentId, amount, currency, status, previousStatus}`; eventTypes `payment.captured.v1` / `payment.failed.v1` / `payment.refunded.v1`. Relay pattern = order's (outbox row in same tx, relay polls PENDING → publish → SENT).
- **HMAC:** HmacSHA256 over raw request body, hex-encoded, constant-time compare via `MessageDigest.isEqual`. Secret `shop.payment.webhook.secret`.
- **Fail-fast rule:** `shop.payment.provider=stripe` without `shop.payment.stripe.secret-key` → startup failure with explicit message (StripeProvider `@PostConstruct` check).
- `@WebMvcTest`: `org.springframework.boot.webmvc.test.autoconfigure` + seed `JwtAuthenticationToken` + `@Import(ApiExceptionHandler.class)`.
- Harness = notification-service's (singleton PG+Kafka, `auto-offset-reset: earliest` IT override) — payment needs Kafka to assert relay publishes via a test `@KafkaListener` recorder.
- Receipt storage in ITs: `@TestConfiguration` fake `ObjectStorageService` (in-memory map) — D9 makes storage failure non-blocking; the real S3 path is exercised at compose level only.
- TIMESTAMPTZ audit columns (fleet ruling 2026-08-30); entities mirror Campaign precedent (`@Version Long` = `0L` default via `@Builder.Default`).

## File Map

| File | Action |
|------|--------|
| `payment-service/pom.xml` | MODIFY — deps (Task 1) |
| `utils/common-core/.../ErrorCode.java` | MODIFY — PAY-5003..5007 tail (Task 1) |
| `utils/common-core/.../constants/ApiPaths.java` | MODIFY — BACKOFFICE_PAYMENTS + WEBHOOK_PAYMENTS (Task 1) |
| `utils/common-spring/.../messages_{en,vi}.properties` | MODIFY — 5 `payment.*` keys (Task 1) |
| `.../application.yml` + `db/changelog/*changelog-001-payments.yaml` | CREATE (Task 2) |
| `.../constant/PaymentStatus.java`, `.../entity/Payment.java`, `.../entity/PaymentEvent.java` | CREATE (Task 3) |
| `.../repository/PaymentRepository.java`, `PaymentEventRepository.java` | CREATE (Task 4) |
| `.../service/PaymentStateMachine.java` | CREATE (Task 5) |
| `.../provider/PaymentProvider.java`, `MockProvider.java`, `StripeProvider.java`, `PaymentProviderConfig.java` | CREATE (Task 6) |
| `.../webhook/WebhookSignatureVerifier.java` | CREATE (Task 7) |
| `.../outbox/PaymentOutboxRelay.java` (+ ported OutboxEvent/Repository) | CREATE (Task 8) |
| `.../service/PaymentService(+Impl)` | CREATE (Task 9) |
| `.../webhook/PaymentWebhookController.java` + `WebhookEventService` | CREATE (Task 10) |
| `.../controller/BackofficePaymentController.java` | CREATE (Task 11) |
| `.../service/ReceiptService.java` | CREATE (Task 12) |
| `.../support/` harness + `PaymentBootstrapIT.java` | CREATE (Task 13) |
| `.../PaymentFlowIT.java` | CREATE (Task 14) |
| compose env modernization + mock-provider container | Task 15 |
| final review | Task 16 |

---

### Task 1: pom deps + ErrorCode PAY tail + ApiPaths + i18n

**Files:** Modify `payment-service/pom.xml`, `ErrorCode.java`, `ApiPaths.java`, `messages_{en,vi}.properties`

**Interfaces:** `ErrorCode.PAYMENT_DUPLICATE_REQUEST` (PAY-5003, 409), `PAYMENT_INVALID_STATE` (PAY-5004, 409), `WEBHOOK_SIGNATURE_INVALID` (PAY-5005, 401), `REFUND_INVALID_STATE` (PAY-5006, 409), `AMOUNT_MISMATCH` (PAY-5007, 400); `ApiPaths.BACKOFFICE_PAYMENTS = API_V1 + "/backoffice/payments"`; `ApiPaths.WEBHOOK_PAYMENTS = API_V1 + "/webhooks/payments"`; i18n keys `payment.duplicate_request`, `payment.invalid_state`, `payment.webhook_signature_invalid`, `payment.refund_invalid_state`, `payment.amount_mismatch` (EN+VI).

- [ ] **Step 1: pom** — copy promotion's dep list; ADD `utils/common-storage` (artifact `common-storage`, `${project.version}`); keep `spring-kafka` + `common-kafka`; nothing else removed.
- [ ] **Step 2: ErrorCode** — anchor per Global Constraints; append PAY-5003..5007 in order (i18n keys as listed), `;` on PAY-5007.
- [ ] **Step 3: ApiPaths** — two constants mirroring `BACKOFFICE_TAX_CLASSES` style (`API_V1 +` concat).
- [ ] **Step 4: i18n** — EN/VI for all 5 keys.
- [ ] **Step 5:** `./mvnw -pl utils/common-core,utils/common-spring install -q && ./mvnw -pl payment-service compile -q` green.
- [ ] **Step 6: Commit** — `feat(payment-service): deps + PAY-5003..5007 + api paths + i18n`

### Task 2: application.yml + Liquibase

**Files:** Create `application.yml`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changelog-001-payments.yaml`

**Interfaces:** Tables `payments`, `payment_events` 1:1 with Tasks 3-4 entities.

- [ ] **Step 1: yml** — port 8085; liquibase master; `shop.payment.provider: ${PAYMENT_PROVIDER:mock}`; `shop.payment.webhook.secret: ${PAYMENT_WEBHOOK_SECRET:}`; `shop.payment.stripe.secret-key: ${STRIPE_SECRET_KEY:}`; `shop.kafka.bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}`; security block mirrors notification's (issuer-uri, csrf-disabled, stateless-session). No `spring.kafka.*` keys (common-kafka owns binding).
- [ ] **Step 2: changelog-001** — `payments`: `id UUID`, `order_id UUID NOT NULL`, `amount NUMERIC(19,2) NOT NULL`, `currency CHAR(3) NOT NULL`, `status VARCHAR(16) NOT NULL` CHECK in ('PENDING','CAPTURED','FAILED','REFUNDED'), `previous_status VARCHAR(16) NULL`, `provider VARCHAR(16) NOT NULL`, `idempotency_key VARCHAR(64) NOT NULL`, `receipt_key VARCHAR(255) NULL`, full audit set (TIMESTAMPTZ, `deleted`). Raw SQL: `CREATE UNIQUE INDEX uk_payment_idempotency_key ON payments (idempotency_key);` (column is NOT NULL — plain unique; the partial predicate becomes moot, keep it simple), `CREATE INDEX idx_payments_order_id ON payments (order_id);`. `payment_events`: `id UUID`, `payment_id UUID NOT NULL`, `provider VARCHAR(16) NOT NULL`, `provider_event_id VARCHAR(128) NOT NULL`, `type VARCHAR(32) NOT NULL`, `payload TEXT NOT NULL`, `status VARCHAR(16) NOT NULL` CHECK in ('PROCESSED','FAILED'), audit set. Raw SQL: `CREATE UNIQUE INDEX uk_payment_events_provider_event ON payment_events (provider, provider_event_id);`
- [ ] **Step 3:** compile + changelog parses. **Step 4: Commit** — `feat(payment-service): config + payments tables`

### Task 3: PaymentStatus + entities

**Files:** Create `.../constant/PaymentStatus.java`, `.../entity/Payment.java`, `.../entity/PaymentEvent.java`

**Interfaces:** `enum PaymentStatus { PENDING, CAPTURED, FAILED, REFUNDED }` (+ `Set<PaymentStatus> TERMINAL_WEBHOOK_STATES` constant where useful). `Payment extends AbstractMappedEntity`: `UUID orderId`, `BigDecimal amount`, `String currency`, `PaymentStatus status`, `PaymentStatus previousStatus` (nullable), `String provider`, `String idempotencyKey`, `String receiptKey` (nullable) — `@Version private Long version = 0L;` `@Builder.Default`. `PaymentEvent extends AbstractMappedEntity`: `UUID paymentId`, `String provider`, `String providerEventId`, `String type`, `String payload`, `String status`.

- [ ] **Step 1:** write all three; **Step 2:** compile; **Step 3: Commit** — `feat(payment-service): Payment + PaymentEvent entities`

### Task 4: Repositories

**Files:** Create `.../repository/PaymentRepository.java`, `.../repository/PaymentEventRepository.java`

**Interfaces:** `PaymentRepository`: `Optional<Payment> findById(UUID);` `Optional<Payment> findByIdempotencyKey(String key);` `Page<Payment> findAllByOrderByCreatedAtDesc(Pageable p);` `Page<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable p);` `boolean existsByOrderId(UUID orderId);`. `PaymentEventRepository`: `boolean existsByProviderAndProviderEventId(String provider, String eventId);`

- [ ] **Step 1:** write; **Step 2:** compile; **Step 3: Commit** — `feat(payment-service): payment repositories`

### Task 5: PaymentStateMachine (TDD)

**Files:** Create `.../service/PaymentStateMachine.java`

**Interfaces:** `static PaymentStatus transition(PaymentStatus from, PaymentStatus to)` — legal edges: `PENDING→CAPTURED`, `PENDING→FAILED`, `CAPTURED→REFUNDED`; everything else → `BusinessException(PAYMENT_INVALID_STATE)`. Pure static, no Spring.

- [ ] **Step 1 (RED):** `PaymentStateMachineTest` — each legal edge, each illegal (CAPTURED→CAPTURED, REFUNDED→anything, FAILED→CAPTURED, null from).
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): payment state machine`

### Task 6: Provider port + Mock/Stripe (TDD-lite)

**Files:** Create `.../provider/PaymentProvider.java`, `.../provider/MockProvider.java`, `.../provider/StripeProvider.java`, `.../provider/PaymentProviderConfig.java`

**Interfaces:** `interface PaymentProvider { String name(); ProviderResult capture(UUID paymentId, BigDecimal amount, String currency, String idempotencyKey); ProviderResult refund(UUID paymentId, BigDecimal amount, String idempotencyKey); }` where `ProviderResult(String providerEventId, boolean accepted)`. `MockProvider` (`@ConditionalOnProperty(name="shop.payment.provider", havingValue="mock", matchIfMissing=true)`): generates `mock-<UUID>` providerEventId, `accepted=true`. `StripeProvider` (`havingValue="stripe"`): `@PostConstruct` throws `IllegalStateException("Stripe credentials absent — set STRIPE_SECRET_KEY")` when `shop.payment.stripe.secret-key` blank; methods throw `UnsupportedOperationException` (skeleton). `PaymentProviderConfig`: `@Bean @Primary PaymentProvider primary(List<PaymentProvider> all)` — exactly one active by condition; assertion error if list size ≠ 1.

- [ ] **Step 1 (RED):** `PaymentProviderConfigTest` — mock condition active by default; list of 1 resolves; `MockProviderTest` — deterministic event id format + accepted.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): PaymentProvider port (mock default, stripe skeleton fail-fast)`

### Task 7: WebhookSignatureVerifier (TDD)

**Files:** Create `.../webhook/WebhookSignatureVerifier.java`

**Interfaces:** `boolean verify(String secret, byte[] rawBody, String signatureHeader)` — HmacSHA256 hex, `MessageDigest.isEqual` compare; null/blank/short header → false (no throw).

- [ ] **Step 1 (RED):** `WebhookSignatureVerifierTest` — valid signature (computed in test via Mac), tampered body, wrong secret, null header, hex-with-prefix tolerated or rejected (pick: reject; document).
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): HMAC-SHA256 webhook signature verifier`

### Task 8: Outbox + relay (port from order)

**Files:** Create `.../outbox/OutboxEvent.java`, `.../outbox/OutboxEventRepository.java`, `.../outbox/PaymentOutboxRelay.java`, `.../outbox/PaymentEventPublisher.java`; **changelog-001 add `outbox_events` table** (copy order's column set 1:1 — read `OrderOutboxRelay`/`OutboxEvent` first).

**Interfaces:** Entities/repo are faithful ports (package rename only; `OutboxStatus` comes from common-core). `PaymentEventPublisher` builds the D5 wrapper payload JSON (`ObjectMapper`, `eventId=UUID.randomUUID()`) and exposes `publish(Payment payment, String eventType)` → outbox row insert (called inside the caller's tx). `PaymentOutboxRelay` = `@Scheduled(fixedDelayString = "${shop.payment.outbox.poll-millis:2000}")` port of order's relay (PENDING → `KafkaMessagePublisher.publish(topic, payload)` → SENT; error → retry counter).

- [ ] **Step 1:** read order's three files; port. **Step 2:** changelog add table (same indexes as order's).
- [ ] **Step 3:** compile. **Step 4: Commit** — `feat(payment-service): outbox + relay (order reference port)`

### Task 9: PaymentService — create/capture/refund (TDD)

**Files:** Create `.../service/PaymentService.java`, `.../service/impls/PaymentServiceImpl.java`, `.../service/PaymentWriter.java`

**Interfaces:**
- `PaymentService`: `Payment create(CreatePaymentRequest req)` — idempotent: existing `idempotencyKey` → return that row (200 semantics, no error); `Payment capture(UUID id)` — PENDING only (PAY-5004), calls provider, records nothing terminal (state changes ONLY via webhook), returns updated row; `Payment refund(UUID id)` — CAPTURED only (PAY-5006), calls provider.refund.
- `PaymentWriter` `@Repository @Transactional` (notification precedent — commit-time exception translation): `insert`, `saveAndFlush`.
- FINAL SHAPE: service methods annotated `@Transactional`; provider call INSIDE tx is fine (mock is local; stripe skeleton throws).

- [ ] **Step 1 (RED):** `PaymentServiceImplTest` (mocks): create happy; create idempotent-replay returns existing without insert; capture on non-PENDING → PAY-5004; capture calls provider with payment's fields; refund on PENDING → PAY-5006; refund happy calls provider.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): payment service (idempotent create, capture, refund)`

### Task 10: Webhook controller + receiver (TDD)

**Files:** Create `.../webhook/PaymentWebhookController.java`, `.../service/WebhookEventService.java`, `.../webhook/WebhookPayload.java`

**Interfaces:**
- `POST ApiPaths.WEBHOOK_PAYMENTS + "/{provider}"` — consumes **raw body** (`@RequestBody byte[]` — signature covers exact bytes) + `X-Webhook-Signature` header. Bad signature → `WEBHOOK_SIGNATURE_INVALID` 401, NO state change, NO event row.
- `WebhookPayload` (`@JsonIgnoreProperties(ignoreUnknown=true)`): `eventId, eventType, paymentId, orderId, amount, currency, status("CAPTURED"|"FAILED"|"REFUNDED"), providerEventId`.
- `WebhookEventService.handle(...)` — **persist-first/ack-always** (notification D5 precedent): (1) dedupe `existsByProviderAndProviderEventId` → ack no-op; (2) insert `PaymentEvent` in own tx (writer `@Repository` — duplicate race → catch `DataIntegrityViolationException` → ack no-op); (3) then transition: load payment (unknown → mark event FAILED + ack 200), `AMOUNT_MISMATCH` if payload amount ≠ payment amount (event FAILED, ack 200), `PaymentStateMachine.transition` → save `previousStatus`, insert outbox row (same tx as state change — event publish atomic with state), ack 200 ALWAYS. Handler exceptions → event row FAILED + 200 (reconciliation material).
- Security carve-out: `shop.security.public-paths` must include the webhook path — **check common-security's property name first** (`SecurityProperties`/yml key used by other services' public paths, e.g. keycloak callback); if a public-paths key doesn't exist, add the minimal property + filter skip in common-spring security config (SMALL shared-file change — ledger it as W1-owned).

- [ ] **Step 1 (RED):** `WebhookEventServiceTest` (mocks): valid CAPTURED event → state CAPTURED + previousStatus PENDING + outbox row; replay (exists=true) → no-op; duplicate race (DIVE) → no-op no crash; unknown payment → event FAILED, ack; amount mismatch → PAY-5007 recorded, state unchanged; handler throw → event FAILED, no exception escapes; FAILED event → state FAILED.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement controller + service (+ security carve-out) → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): signed webhook receiver (persist-first, ack-always)`

### Task 11: Backoffice read API

**Files:** Create `.../controller/BackofficePaymentController.java`

**Interfaces:** class-level `@PreAuthorize("hasRole('ADMIN')")`; `GET ApiPaths.BACKOFFICE_PAYMENTS + "/{id}"` → `ApiResponse<PaymentResponse>` / PAY-5002 404; `GET ApiPaths.BACKOFFICE_PAYMENTS + "?orderId=&page=&size="` paged newest-first (null orderId → unfiltered finder — fleet precedent). `PaymentResponse` record: id, orderId, amount, currency, status, previousStatus, provider, receiptKey, createdAt.

- [ ] **Step 1 (RED):** `@WebMvcTest` matrix: anon 401, ROLE_USER 403, ADMIN 200 (list + by-id), unknown id → 404 `code=PAY-5002`, orderId filter → unfiltered path verified with negative Mockito verify.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): backoffice read API (ADMIN)`

### Task 12: ReceiptService (TDD)

**Files:** Create `.../service/ReceiptService.java`

**Interfaces:** `String storeReceipt(Payment payment)` — renders deterministic JSON (paymentId, orderId, amount, currency, status, capturedAt) via `ObjectMapper`, key `receipts/{paymentId}.json`, calls `ObjectStorageService.upload(...)`; ANY storage exception → log warn + return null (D9: never blocks). Caller (webhook transition to CAPTURED) sets `receipt_key` when non-null.

- [ ] **Step 1 (RED):** `ReceiptServiceTest` with mocked `ObjectStorageService`: happy → key returned + upload called with JSON containing paymentId; storage throws → returns null, no exception.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(payment-service): receipt rendering + object storage (non-blocking)`

### Task 13: Harness + BootstrapIT

**Files:** Create `.../support/AbstractIntegrationTest.java`, `.../config/TestLiquibaseConfig.java`, `.../PaymentBootstrapIT.java`

**Interfaces:** notification's harness verbatim (singleton PG+Kafka, `static {}`, shutdown hook, earliest reset) + `@TestConfiguration` fake `ObjectStorageService` (in-memory) registered in the harness context + `shop.payment.webhook.secret` fixed test value + `shop.payment.provider: mock`. `TestLiquibaseConfig` verbatim.

- [ ] **Step 1:** adapt (package rename; fakes added). **Step 2:** BootstrapIT: context boots, `payments`/`payment_events`/`outbox_events` tables exist, provider bean is MockProvider, KafkaTemplate bean present.
- [ ] **Step 3:** full module test green (Docker wedge retry-once rule).
- [ ] **Step 4: Commit** — `test(payment-service): harness + bootstrap IT`

### Task 14: PaymentFlowIT (capstone)

**Files:** Create `.../PaymentFlowIT.java`

**Interfaces:** `@SpringBootTest` + `TestRestTemplate` (local slot — real HTTP through the security chain). Test config: recorder `@KafkaListener(topics = "shop.payment.lifecycle.v1")` appending payloads to a static list (test group `payment-it-recorder`). HMAC signing helper in the IT (same Mac code as Task 7 — independent reimplementation, cross-checks the verifier).

- [ ] **Step 1:** scenarios (awaitility 20s):
  1. create → capture → signed CAPTURED webhook → payments row CAPTURED, previousStatus PENDING, receipt_key set (fake storage)
  2. replay same webhook (same providerEventId) → still 1 event row, state unchanged
  3. bad signature → 401, state unchanged, zero new event rows
  4. refunded webhook on CAPTURED → REFUNDED + `payment.refunded.v1` recorded
  5. amount-mismatch webhook → state unchanged, event row FAILED
  6. recorder list contains `payment.captured.v1` then `payment.refunded.v1` with correct wrappers (eventId parseable UUID, previousStatus non-null)
  7. poisoned record: POST garbage JSON with VALID signature → 200 ack (event FAILED), then a valid webhook still processes (partition/endpoint survives)
- [ ] **Step 2:** `./mvnw -pl payment-service test` green ×1 + repeat once.
- [ ] **Step 3: Commit** — `test(payment-service): end-to-end webhook flow IT (signature, dedupe, outbox publish)`

### Task 15: Compose + mock-provider container (spec D2/D10)

**Files:** Modify `docker-compose.yml` (payment stanza + new service); Create `mock-services/mock-payment-provider/{Dockerfile,package.json,server.js}`

- [ ] **Step 1: stanza** — payment: `KAFKA_SERVERS: kafka:9092` → `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`; add `PAYMENT_PROVIDER: ${PAYMENT_PROVIDER:-mock}`, `PAYMENT_WEBHOOK_SECRET: ${PAYMENT_WEBHOOK_SECRET:-local-test-secret}`, `STRIPE_SECRET_KEY: ${STRIPE_SECRET_KEY:-}`; add security issuer env if stanza lacks the `*jwt` anchor (verify — stanza has `*jwt` already, keep). Healthcheck/depends_on already correct — verify only.
- [ ] **Step 2: mock container** — Node 20-alpine, no build step (`node server.js`): endpoints `POST /mock-payments/:id/capture` (200-800ms `setTimeout`, then HMAC-signed CAPTURED webhook POST to `PAYMENT_SERVICE_URL`), `POST /mock-payments/:id/refund` (same → REFUNDED), `POST /mock-payments/reset` (clears map), `GET /mock-payments/_health`. Signature: same HmacSHA256-hex over raw body with `PAYMENT_WEBHOOK_SECRET`. ~60 lines, zero npm deps (http module). Compose: `build: ./mock-services/mock-payment-provider`, `container_name: mock-payment-provider`, `environment: PAYMENT_WEBHOOK_SECRET, PAYMENT_SERVICE_URL: http://payment-service:8085`, healthcheck on `_health`, same network.
- [ ] **Step 3:** `docker compose config -q` green. **Step 4: Commit** — `feat(payment-service): compose envs + mock payment provider container`

### Task 16: Final whole-branch review

- [ ] **Step 1:** review-package BASE→HEAD; reviewer checks: D1 provider port (service never imports Mock/Stripe classes), D2 mock-as-service contract (compose + server.js vs spec endpoints), D3 idempotency + previousStatus, D4 HMAC fail-closed + persist-first ordering, D5 outbox atomicity (state change + outbox row same tx) vs order reference diff, D6 codes vs spec table, D7 security matrix + webhook carve-out correctness (public path ≠ open path: HMAC still enforced), D9 receipt non-blocking, D10 port 8085/envs/Stripe fail-fast documented, zero order/shipping/notification edits.
- [ ] **Step 2:** fix rounds per review; ledger close. Ops note: live Stripe = `PAYMENT_PROVIDER=stripe` + key (skeleton throws until real impl); rotate `PAYMENT_WEBHOOK_SECRET` per env; receipt bucket must exist before first CAPTURED (or receipts no-op with warn).
