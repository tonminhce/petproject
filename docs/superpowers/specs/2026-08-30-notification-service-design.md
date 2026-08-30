# Notification Service — Design

Date: 2026-08-30 · Status: Approved (pending user spec review) · Pipeline: spec → plan → SDD (concurrent with tax-service epic, separate worktree, rebases on tax's shared-file tail)

## 1. Purpose

First Kafka **consumer** in the fleet: listens to `shop.order.lifecycle.v1`
(events order already publishes via its outbox relay) and records one
notification per event, delivering it through a pluggable sender. Day-1 the
sender logs; real SMTP ships behind a flag and credentials that compose
already anticipates (`MAIL_USERNAME` / `MAIL_PASSWORD`).

## 2. Binding decisions

- **D1 — Consumer stack: common-kafka (fleet-consistency).** A listener
  config extends `BaseKafkaListenerConfig<String, OrderLifecycleEvent>`
  (JsonDeserializer + `ErrorHandlingDeserializer` poison-record protection
  built into the shared factory) and exposes the container-factory bean for
  `@KafkaListener`. Binding is the SAME `shop.kafka.*` namespace producers
  use: `shop.kafka.bootstrap-servers` (compose env
  `SHOP_KAFKA_BOOTSTRAP_SERVERS` — unchanged), `shop.kafka.consumer.group-id:
  notification-service`, `shop.kafka.consumer.auto-offset-reset: latest`
  (notifications are ephemeral; ITs override to `earliest` for deterministic
  drains). A poisoned record never reaches listener code — the shared
  factory logs and skips it, the partition stays unblocked.
- **D2 — Consumed contract (verified against `OrderEventPublisherImpl`).**
  Topic `shop.order.lifecycle.v1`; every record's payload is wrapped as
  `{eventId, eventType, occurredAt, ...data}`. Consumed event types:
  - `order.created.v1` — data: `orderId`, `userId`, `status`, `items[]`,
    `subtotal`, `taxAmount`, `discountAmount`, `total`, `couponCode?`,
    `createdAt`
  - `order.updated.v1` — data: `orderId`, `status`, `transitionedAt`
    (no `userId`)
  - `order.cancelled.v1` — data: `orderId`, `cancelledAt`, `refunded`
    (no `userId`, no `status`)
  `userId` is therefore nullable in the stored row (only `created` carries
  it). Unknown `eventType` values are **not** errors: persist a `SKIPPED`
  row and ack — forward compatibility, never crash the partition.
- **D3 — Idempotency on `eventId`.** Every payload carries the outbox-generated
  `eventId` (UUID) — dedupe is a plain UNIQUE column on the stored row.
  Exact for every event type (no natural-key workaround needed, `cancelled`
  included): at-least-once relay redeliveries repeat the same `eventId`,
  a second insert violates the unique index → log + ack, no duplicate row.
- **D4 — Sender pluggability (user decision Q2=C).**
  `NotificationSender` interface (`send(Notification) -> void`).
  - `LoggingNotificationSender` — always present, logs the notification.
  - `SmtpNotificationSender` — `@ConditionalOnProperty`
    `shop.notification.smtp.enabled=true` + `spring.mail.username` set;
    plain `JavaMailSender`. MVP recipient policy: there is no user-email
    service, so SMTP sends to `shop.notification.smtp.fallback-recipient`
    (a single ops address); per-user mailboxes are future work when a user
    profile source exists. Send failure = logged with the stored row marked
    FAILED — never a crash.
- **D5 — Processing order: persist-first, ack-always.** Consume → resolve
  template → insert notification row (`SENT`/`FAILED`/`SKIPPED`) in a
  transaction → then ack. Sender runs after the insert commits; a sender
  failure marks the row FAILED in a short follow-up update and the listener
  still acks (no partition blocking, no retry storms; ops re-drive via §3
  list endpoint if ever needed).
- **D6 — Model.** `Notification` entity (plain `@Entity`, no `@Version` —
  insert-only, no concurrent mutation, CouponUsageReservation precedent):
  id UUID, `event_id` UUID UNIQUE (`uk_notification_event_id`, the D3 key),
  `event_type`, `order_id` (indexed), `user_id` (nullable, D2),
  `status` (enum `SENT`/`FAILED`/`SKIPPED`), `channel` (enum `LOG`/`SMTP`),
  `subject`, `body`, `payload` TEXT (fleet outbox style), full audit column
  set. Soft-delete NOT applied (immutable event log rows).
- **D7 — Read API.** `GET /api/v1/backoffice/notifications?orderId=&page=`
  (ADMIN, paginated, newest first) + `GET .../{id}` — support/ops visibility
  only. `NTF-9001 NOTIFICATION_NOT_FOUND` on unknown id. No write/delete
  endpoints: the table is system-authored.
- **D8 — Error codes.** `NTF-9001` `NOTIFICATION_NOT_FOUND` appended to the
  shared `ErrorCode` enum + `notification.*` i18n keys EN+VI. Explicit anchor
  procedure (this epic rebases on the tax epic's shared-file commit): the
  enum tail is then `..., "TAX-8001", ...` ascending through
  `TAX_CLASS_IN_USE("TAX-8004", ...)` as the last entry, `;`-terminated
  → flip the TAX-8004 line's `;` to `,`, append
  `NOTIFICATION_NOT_FOUND("NTF-9001", "notification.not_found", ...)` as the
  new last entry, `;`-terminated. Consumer-side failures never surface as
  error codes — they are row states (D5).
- **D9 — Security.** Fail-closed, no public paths; backoffice read endpoints
  ADMIN. JWT issuer per fleet `x-jwt` anchor.
- **D10 — Compose/init fixes in scope (explicit).** (a) Add to
  `docker/postgres/init/create-all-databases.sql`:
  `CREATE DATABASE notificationservice;` (missing today — keycloak,
  taxservice etc. are there; notification is not). (b) Fix the stub stanza:
  replace the `POSTGRES_HOST/PORT/DB/USER/PASSWORD` block with
  `SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/notificationservice`
  (fleet style), replace `KAFKA_SERVERS: kafka:9092` with
  `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` (the `shop.kafka.*` binding,
  D1), keep `MAIL_USERNAME` / `MAIL_PASSWORD`.

## 3. Event → notification template

| eventType | subject | body |
|---|---|---|
| `order.created.v1` | `Order {orderId} created` | status, subtotal/tax/discount/total, items count |
| `order.updated.v1` | `Order {orderId} → {status}` | status, transitionedAt |
| `order.cancelled.v1` | `Order {orderId} cancelled` | cancelledAt, refunded |

Templates are in-code builders (no template engine — YAGNI); body is plain
text, locale-neutral EN (i18n templates deferred until a recipient-locale
source exists).

## 4. Flow

```
@KafkaListener(shop.order.lifecycle.v1, factory = notificationListenerFactory)
 → parse (unknown eventType = SKIPPED row + ack; poisoned/malformed records
   are handled by the shared ErrorHandlingDeserializer — logged + skipped
   before listener code, partition unblocked)
 → dedupe: insert with UNIQUE event_id (violation = log + ack, no row)
 → build subject/body (§3)
 → INSERT row (status=SENT; or SKIPPED per above)
 → sender.send() outside the insert tx; sender throw → follow-up update FAILED
 → ack
```

Listener concurrency stays at the `@KafkaListener` default (1) — sufficient
at fleet scale and preserves per-order ordering (Kafka key = orderId);
no explicit concurrency config.

## 5. Testing strategy (fleet 3 layers)

- **Unit**: template builders (all three event types), eventId extraction,
  SKIPPED-for-unknown-type logic, sender-failure → FAILED path, SMTP
  conditional wiring.
- **IT (Testcontainers PG + Kafka)** — the harness singleton pattern from
  promotion: real consumer drains a `KafkaTemplate`-produced record
  (awaitility): created + updated + cancelled happy rows (assert `user_id`
  NULL on updated/cancelled), duplicate redelivery → single row (same
  `eventId`), unknown eventType → SKIPPED, poisoned record → listener not
  invoked + next valid record still processed, sender-throws → FAILED row +
  ack (partition survives), backoffice list auth matrix.
- **Consumer slice tests**: listener with mocked sender/repo for fast matrix.

## 6. Fleet impact

`utils/common-core`: +1 `ErrorCode` entry, `ApiPaths.BACKOFFICE_NOTIFICATIONS`,
i18n keys — additive, rebased after tax merges (mechanical conflicts only:
enum tail + properties tail).
