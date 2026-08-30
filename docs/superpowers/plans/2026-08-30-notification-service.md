# Notification Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `notification-service` — the fleet's first Kafka consumer: drains `shop.order.lifecycle.v1`, stores one idempotent notification per event (`eventId` unique), delivers via a pluggable sender (logging day-1, SMTP behind a flag), plus an admin read API.

**Architecture:** Fleet-standard Boot 4.1.1 microservice (`com.shop.notificationservice`): PostgreSQL + Liquibase for the notification log; common-kafka consumer stack (`BaseKafkaListenerConfig` subclass + `shop.kafka.consumer.*` binding) with `ErrorHandlingDeserializer` poison protection; insert-first/ack-always listener; `NotificationSender` interface with `Logging` (default) and `Smtp` (`@ConditionalOnProperty`) implementations. No outbound HTTP clients, no Redis, no outbox (it consumes, never produces).

**Tech Stack:** Spring Boot 4.1.1, Java 25, JPA + Liquibase + Postgres 16, Spring Kafka (common-kafka), spring-boot-starter-mail (SMTP impl), Lombok, JUnit 5 + Mockito + AssertJ + Testcontainers (PostgreSQL + Kafka + awaitility). No WireMock.

**Spec:** [`docs/superpowers/specs/2026-08-30-notification-service-design.md`](../specs/2026-08-30-notification-service-design.md) — read alongside; the plan argues from the spec.

## Global Constraints

- Package `com.shop.notificationservice` (scaffold exists: `NotificationServiceApplication`). Port 8090. DB `notificationservice` (**added to init SQL in Task 11** — missing today).
- **Rebase rule (concurrent epics):** this plan's Task 1 touches the same shared files as the tax epic; execute AFTER tax merges. ErrorCode anchor then = `TAX_CLASS_IN_USE("TAX-8004", "tax.class.in_use", HttpStatus.CONFLICT);` as the LAST enum entry → flip that `;` to `,`, append NTF-9001 `;`-terminated. (If tax has not merged: STOP — sequence violation.)
- **Never** edit `order-service` in this plan; the consumed contract is read-only truth: `OrderEventPublisherImpl` (topics/payloads), `OrderEventPublisherImpl.save()` (eventId wrapper).
- Payload wrapper on EVERY record: `{eventId: UUID-string, eventType, occurredAt, ...data}` (verified 2026-08-30). Event types: `order.created.v1` {orderId, userId, status, items[], subtotal, taxAmount, discountAmount, total, couponCode?, createdAt}, `order.updated.v1` {orderId, status, transitionedAt}, `order.cancelled.v1` {orderId, cancelledAt, refunded}. `userId` only on `created` → column nullable.
- `@WebMvcTest`: `org.springframework.boot.webmvc.test.autoconfigure` + seed `JwtAuthenticationToken` + `@Import(ApiExceptionHandler.class)`.
- Harness = promotion's singleton pattern (`static { }` boot + shutdown hook) with BOTH PostgreSQL and Kafka containers (promotion's `support/AbstractIntegrationTest` is the copy source; ITs set `shop.kafka.consumer.auto-offset-reset: earliest` via a test `@DynamicPropertySource`).
- Listener concurrency: leave `@KafkaListener` default (1) — no explicit config.
- Money/status fields in templates: render as received (String via `String.valueOf`), locale-neutral EN, no template engine.

## File Map

| File | Action |
|------|--------|
| `notification-service/pom.xml` | MODIFY — deps (Task 1) |
| `utils/common-core/.../ErrorCode.java` | MODIFY — NTF-9001 after TAX-8004 (Task 1) |
| `utils/common-core/.../constants/ApiPaths.java` | MODIFY — BACKOFFICE_NOTIFICATIONS (Task 1) |
| `utils/common-spring/.../messages/messages_{en,vi}.properties` | MODIFY — 1 `notification.*` key (Task 1) |
| `notification-service/src/main/resources/application.yml` | CREATE (Task 2) |
| `.../db/changelog/db.changelog-master.yaml` + `changelog-001-notifications.yaml` | CREATE (Task 2) |
| `.../entity/Notification.java`, `constant/NotificationStatus.java`, `constant/NotificationChannel.java` | CREATE (Task 3) |
| `.../dto/OrderLifecycleEvent.java`, `.../dto/request/—` | CREATE (Task 3) |
| `.../repository/NotificationRepository.java` | CREATE (Task 4) |
| `.../service/NotificationTemplates.java` | CREATE (Task 5) |
| `.../service/sender/NotificationSender.java`, `LoggingNotificationSender.java`, `SmtpNotificationSender.java`, `NotificationSenderConfig.java` | CREATE (Task 6) |
| `.../kafka/NotificationListenerConfig.java`, `.../kafka/OrderEventConsumer.java` | CREATE (Task 7) |
| `.../service/NotificationService(+Impl)` | CREATE (Task 7) |
| `.../controller/BackofficeNotificationController.java` | CREATE (Task 8) |
| `.../support/` harness + `NotificationBootstrapIT.java` | CREATE (Task 9) |
| `.../NotificationFlowIT.java` | CREATE (Task 10) |
| compose + init SQL fixes | Task 11 |
| final review | Task 12 |

---

### Task 1: pom deps + ErrorCode NTF-9001 + ApiPaths + i18n

**Files:** Modify `notification-service/pom.xml`, `ErrorCode.java`, `ApiPaths.java`, `messages_{en,vi}.properties`

**Interfaces:** Produces `ErrorCode.NOTIFICATION_NOT_FOUND` (NTF-9001, 404); `ApiPaths.BACKOFFICE_NOTIFICATIONS = "/api/v1/backoffice/notifications"`; key `notification.not_found`.

- [ ] **Step 1: pom** — copy promotion's deps, KEEP `spring-kafka` + `common-kafka`, ADD `org.springframework.boot:spring-boot-starter-mail`; remove nothing else. Versions identical to promotion's.
- [ ] **Step 2: ErrorCode** — sequence-gate per Global Constraints (TAX-8004 must be the `;`-terminated last entry; else STOP). Flip TAX-8004's `;` → `,`, append as new last entry:
  ```java
          NOTIFICATION_NOT_FOUND("NTF-9001", "notification.not_found", HttpStatus.NOT_FOUND);
  ```
- [ ] **Step 3: ApiPaths** — `String BACKOFFICE_NOTIFICATIONS = "/api/v1/backoffice/notifications";`
- [ ] **Step 4: i18n** — EN `notification.not_found=Notification not found`; VI `notification.not_found=Không tìm thấy thông báo`.
- [ ] **Step 5:** `./mvnw -pl utils/common-core,utils/common-spring install -q && ./mvnw -pl notification-service compile -q` green.
- [ ] **Step 6: Commit** — `feat(notification-service): deps + NTF-9001 + api path + i18n`

### Task 2: application.yml + Liquibase

**Files:** Create `application.yml`, `db/changelog/db.changelog-master.yaml`, `db/changelog/changelog-001-notifications.yaml`

**Interfaces:** Table `notifications`, columns 1:1 with Task 3 entity.

- [ ] **Step 1: yml** —
  ```yaml
  server:
    port: ${SERVER_PORT:8090}
  spring:
    liquibase:
      change-log: classpath:db/changelog/db.changelog-master.yaml
    mail:
      host: ${MAIL_HOST:}
      port: ${MAIL_PORT:587}
      username: ${MAIL_USERNAME:}
      password: ${MAIL_PASSWORD:}
  shop:
    kafka:
      bootstrap-servers: ${SHOP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
      consumer:
        group-id: notification-service
        auto-offset-reset: latest
    notification:
      smtp:
        enabled: ${SMTP_ENABLED:false}
        fallback-recipient: ${SMTP_FALLBACK_RECIPIENT:ops@example.com}
    security:
      issuer-uri: ${SHOP_SECURITY_ISSUER_URI:http://localhost:9090/realms/ecommerce}
      csrf-disabled: true
      stateless-session: true
  ```
  (exact YAML shape mirrors promotion's — nesting under existing keys; no public-paths.)
- [ ] **Step 2: changelog-001** — `notifications`: `id UUID`, `event_id UUID NOT NULL`, `event_type VARCHAR(64) NOT NULL`, `order_id UUID NOT NULL`, `user_id UUID NULL`, `status VARCHAR(16) NOT NULL` CHECK in ('SENT','FAILED','SKIPPED'), `channel VARCHAR(8) NOT NULL` CHECK in ('LOG','SMTP'), `subject VARCHAR(255) NOT NULL`, `body TEXT NOT NULL`, `payload TEXT NOT NULL`, full audit set (same 10-column set as promotion, incl. `deleted` etc. — the entity extends `AbstractMappedEntity` so the columns must exist even though rows are never soft-deleted; keep `@SQLRestriction` OFF the entity). Raw SQL: `CREATE UNIQUE INDEX uk_notification_event_id ON notifications (event_id);` + `CREATE INDEX idx_notifications_order_id ON notifications (order_id);`
- [ ] **Step 3:** compile + changelog parses. **Step 4: Commit** — `feat(notification-service): config + notifications table`

### Task 3: Entity + enums + event DTO

**Files:** Create `.../constant/NotificationStatus.java`, `.../constant/NotificationChannel.java`, `.../entity/Notification.java`, `.../dto/OrderLifecycleEvent.java`

**Interfaces:**
- `enum NotificationStatus { SENT, FAILED, SKIPPED }`; `enum NotificationChannel { LOG, SMTP }` (plain enums, no `@Enumerated` surprises — entity uses `@Enumerated(EnumType.STRING)`).
- `Notification` = plain `@Entity extends AbstractMappedEntity` (NO `@Version`, NO `@SQLRestriction`), fields: `UUID eventId`, `UUID orderId`, `UUID userId` (nullable), `String eventType`, `NotificationStatus status`, `NotificationChannel channel`, `String subject`, `String body`, `String payload`.
- `OrderLifecycleEvent` — `@JsonIgnoreProperties(ignoreUnknown = true)` POJO: `String eventId; String eventType; String occurredAt; UUID orderId; UUID userId; String status; Instant transitionedAt; Instant cancelledAt; Boolean refunded;` (+ Lombok `@Getter @Setter @NoArgsConstructor`).

- [ ] **Step 1:** write all four; **Step 2:** compile green; **Step 3: Commit** — `feat(notification-service): Notification entity + event DTO`

### Task 4: Repository

**Files:** Create `.../repository/NotificationRepository.java`

**Interfaces:** `NotificationRepository extends JpaRepository<Notification, UUID>`: `boolean existsByEventId(UUID eventId);` `Page<Notification> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId, Pageable pageable);` `Optional<Notification> findById(UUID id);` (derived — `createdAt` audit column exists).

- [ ] **Step 1:** write; **Step 2:** compile; **Step 3: Commit** — `feat(notification-service): NotificationRepository`

### Task 5: NotificationTemplates (TDD)

**Files:** Create `.../service/NotificationTemplates.java`

**Interfaces:** `static NotificationTemplates.Draft build(OrderLifecycleEvent e)` where `Draft(String subject, String body, boolean known)` — unknown `eventType` → `known=false`, subject `"[skipped] <eventType>"`, body = raw payload summary. Known types per spec §3 table (plain String.format; `order.created.v1` subject `Order %s created`, body `status=%s, subtotal=%s, tax=%s, discount=%s, total=%s, items=%d` (items may be absent in tolerant DTO → `items == null ? 0 : items.size()` — DTO carries `List<Map<String, Object>> items`); `order.updated.v1` subject `Order %s → %s`, body `status=%s, transitionedAt=%s`; `order.cancelled.v1` subject `Order %s cancelled`, body `cancelledAt=%s, refunded=%s`).

- [ ] **Step 1 (RED):** `NotificationTemplatesTest`: one test per event type asserting exact subject/body strings from a hand-built event; unknown type → `known=false`.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement → **Step 4:** PASS.
- [ ] **Step 5: Commit** — `feat(notification-service): event → notification templates`

### Task 6: Senders (TDD wiring)

**Files:** Create `.../service/sender/NotificationSender.java`, `LoggingNotificationSender.java`, `SmtpNotificationSender.java`, `NotificationSenderConfig.java`

**Interfaces:**
- `interface NotificationSender { NotificationChannel channel(); void send(Notification n); }`
- `LoggingNotificationSender` `@Component` — logs subject/body at INFO.
- `SmtpNotificationSender` — `@Component @ConditionalOnProperty(name = "shop.notification.smtp.enabled", havingValue = "true")`, injects `JavaMailSender` + `@Value("${shop.notification.smtp.fallback-recipient}")`; sends simple text mail to fallback recipient, wraps `MessagingException` in `IllegalStateException` (caller marks FAILED).
- `NotificationSenderConfig` — `@Bean @Primary` resolver `NotificationSender primary(List<NotificationSender> all)`: returns the SMTP one if present else LOG.

- [ ] **Step 1 (RED):** `NotificationSenderConfigTest` (plain unit): only-LOG list → LOG resolver; LOG+SMTP list → SMTP; `SmtpNotificationSenderTest` with mocked `JavaMailSender`: send builds message to fallback-recipient; exception propagates.
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(notification-service): pluggable senders (log default, smtp conditional)`

### Task 7: Consumer + listener factory + service (TDD)

**Files:** Create `.../kafka/NotificationListenerConfig.java`, `.../kafka/OrderEventConsumer.java`, `.../service/NotificationService.java`, `.../service/impls/NotificationServiceImpl.java`

**Interfaces:**
- `NotificationListenerConfig extends BaseKafkaListenerConfig<String, OrderLifecycleEvent>` — `@Configuration`, `@Override @Bean(name = "notificationListenerFactory") listenerContainerFactory()` (calls protected `kafkaListenerContainerFactory()`).
- `OrderEventConsumer extends BaseKafkaConsumer<String, OrderLifecycleEvent>` — `@Component`, method `@KafkaListener(topics = "shop.order.lifecycle.v1", containerFactory = "notificationListenerFactory")` delegating to `notificationService.handle(event)`.
-   FINAL SHAPE (decided for the implementer): `OrderEventConsumer` → `NotificationWriter.insert(...)` → `NotificationSender` → on throw `NotificationWriter.markFailed(id)`. `NotificationWriter` is `@Transactional` on both methods. No self-invocation.

- [ ] **Step 1 (RED):** `NotificationServiceImplTest` (mocks): created event → writer.insert called once with SENT/LOG/subject per template, sender invoked; unknown type → SKIPPED row, sender never; `existsByEventId=true` → no insert, no send; duplicate-race (`DataIntegrityViolationException` from insert) → no send, no crash; sender throws → `markFailed(id)` called.
- [ ] **Step 2:** FAIL → **Step 3 (GREEN):** implement `NotificationWriter` + consumer + factory → **Step 4:** PASS + `./mvnw -pl notification-service test` green.
- [ ] **Step 5: Commit** — `feat(notification-service): kafka consumer + persist-first pipeline`

### Task 8: Backoffice read API

**Files:** Create `.../controller/BackofficeNotificationController.java`

**Interfaces:** class-level `@PreAuthorize("hasRole('ADMIN')")`; `GET ApiPaths.BACKOFFICE_NOTIFICATIONS + "/{id}"` → 200 `ApiResponse<NotificationResponse>` / NTF-9001 404; `GET ApiPaths.BACKOFFICE_NOTIFICATIONS + "?orderId=&page=&size="` → paged, newest first. `NotificationResponse` record mirrors entity fields (id, eventId, eventType, orderId, userId, status, channel, subject, createdAt).

- [ ] **Step 1 (RED):** `@WebMvcTest` matrix: anon 401, ROLE_USER 403, ADMIN 200 (list + by-id), unknown id → 404 `code=NTF-9001`, `orderId` filter passes `PageRequest.of(page, size)` newest-first to service (Mockito verify).
- [ ] **Step 2:** FAIL → **Step 3:** implement → **Step 4:** PASS. **Step 5: Commit** — `feat(notification-service): backoffice read API (ADMIN)`

### Task 9: Harness + BootstrapIT

**Files:** Create `.../support/AbstractIntegrationTest.java`, `.../config/TestLiquibaseConfig.java`, `.../NotificationBootstrapIT.java`

**Interfaces:** promotion's harness (singleton `static {}`, shutdown hook, `@DynamicPropertySource`) PLUS Kafka container + `shop.kafka.bootstrap-servers` binding (already in promotion's file — keep) + add `shop.kafka.consumer.auto-offset-reset: earliest` registration for IT determinism. `TestLiquibaseConfig` verbatim.

- [ ] **Step 1:** adapt (package rename; consumer reset line added).
- [ ] **Step 2:** BootstrapIT: context boots, `notifications` table exists, listener factory bean present.
- [ ] **Step 3:** full module test green (Docker wedge retry-once rule).
- [ ] **Step 4: Commit** — `test(notification-service): harness + bootstrap IT`

### Task 10: NotificationFlowIT (the capstone)

**Files:** Create `.../NotificationFlowIT.java`

- [ ] **Step 1:** one IT class, real Kafka: a `KafkaTemplate<String, String>` + `ObjectMapper` sends hand-built payloads (each send uses a fresh `UUID.randomUUID()` as `eventId` — that UUID is the dedupe key) to `shop.order.lifecycle.v1`; awaitility (20s) on repository state:
  1. created → row SENT/LOG, subject `Order <id> created`, `user_id` set
  2. updated → row SENT, `user_id` NULL
  3. cancelled → row SENT, body contains `refunded=false`
  4. duplicate eventId re-send → still exactly 1 row
  5. unknown eventType → SKIPPED row
  6. sender-throws → FAILED row + partition survives: `@SpyBean LoggingNotificationSender` with `doThrow(new RuntimeException("smtp-down")).doCallRealMethod().when(sender).send(any())` — first event's row FAILED, send a NEW event → its row SENT
  7. poisoned record: send raw malformed JSON → then send a valid event → its row appears (listener was not killed; the poisoned record was skipped at the deserializer layer)
- [ ] **Step 2:** `./mvnw -pl notification-service test` green ×1 + repeat once.
- [ ] **Step 3: Commit** — `test(notification-service): end-to-end flow IT (dedupe, poison, ack-survival)`

### Task 11: Compose + init SQL fixes (spec D10)

**Files:** Modify `docker/postgres/init/create-all-databases.sql`, `docker-compose.yml` (notification stanza only)

- [ ] **Step 1:** init SQL — add `CREATE DATABASE notificationservice;` beside `taxservice`.
- [ ] **Step 2:** stanza — replace `POSTGRES_HOST/PORT/DB/USER/PASSWORD` env block with `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/notificationservice`; `KAFKA_SERVERS: kafka:9092` → `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`; keep `MAIL_USERNAME`/`MAIL_PASSWORD`; add `SMTP_ENABLED: ${SMTP_ENABLED:-false}` + `SMTP_FALLBACK_RECIPIENT: ${SMTP_FALLBACK_RECIPIENT:-ops@example.com}`; add `kafka: condition: service_healthy` to depends_on (stanza already has it — verify, keep).
- [ ] **Step 3:** `docker compose config -q` green. **Step 4: Commit** — `chore(notification-service): compose + init sql (db, fleet-style envs)`

### Task 12: Final whole-branch review

- [ ] **Step 1:** review-package BASE→HEAD; reviewer checks: D1 common-kafka factory (no raw spring.kafka props), D2 three event types vs `OrderEventPublisherImpl` re-read, D3 `uk_notification_event_id`, D5 persist-first/ack-always ordering, D8 anchor (NTF-9001 after TAX-8004), security matrix, compose/init SQL, zero other-service regression.
- [ ] **Step 2:** fix rounds per review; ledger close. Operational note: no order-side flag needed — passive consumer; SMTP goes live by setting `SMTP_ENABLED=true` + mail creds.
