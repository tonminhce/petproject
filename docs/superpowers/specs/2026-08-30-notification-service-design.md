# Notification Service — Design

Date: 2026-08-30 · Status: Approved (pending user spec review) · Pipeline: spec → plan → SDD (concurrent with tax-service epic, separate worktree, rebases on tax's shared-file tail)

## 1. Purpose

First Kafka **consumer** in the fleet: listens to `shop.order.lifecycle.v1`
(events order already publishes via its outbox relay) and records one
notification per event, delivering it through a pluggable sender. Day-1 the
sender logs; real SMTP ships behind a flag and credentials that compose
already anticipates (`MAIL_USERNAME` / `MAIL_PASSWORD`).

## 2. Binding decisions

- **D1 — Consumer stack.** Standard `spring.kafka.*` auto-configuration
  (NOT common-kafka: it is producer-only, and this epic must not touch shared
  modules while the tax epic races the same files). Group id
  `notification-service`, `spring.kafka.consumer.auto-offset-reset: latest`
  (notifications are ephemeral; ITs override to `earliest` for deterministic
  drains). JSON decode into a tolerant event DTO
  (`@JsonIgnoreProperties(ignoreUnknown = true)`).
- **D2 — Consumed contract.** Topic `shop.order.lifecycle.v1`; event types
  `order.created.v1`, `order.status_changed.v1`. Payload fields consumed:
  `orderId` (UUID), `userId` (UUID), `status` (String), `createdAt` /
  `transitionedAt` (ISO-8601). Anything else is ignored. Unknown
  `eventType` values are **not** errors: persist a `SKIPPED` row and ack —
  forward compatibility, never crash the partition.
- **D3 — Idempotency.** Natural key `(event_type, order_id, status)` with a
  plain UNIQUE index. The relay is at-least-once, transitions never repeat
  a status, and redeliveries of the same record carry the same triple →
  duplicate deliveries become no-ops (unique violation → log + ack).
  (Fleet follow-up, not this epic: relays gaining an explicit `eventId`
  header would make this trivially exact; natural key is sufficient today.)
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
  id UUID, `event_type`, `order_id` (indexed), `user_id`, `status`
  (enum `SENT`/`FAILED`/`SKIPPED`), `channel` (enum `LOG`/`SMTP`), `subject`,
  `body`, `payload` TEXT (fleet outbox style), full audit column set.
  Soft-delete NOT applied (immutable event log rows).
- **D7 — Read API.** `GET /api/v1/backoffice/notifications?orderId=&page=`
  (ADMIN, paginated, newest first) + `GET .../{id}` — support/ops visibility
  only. `NTF-9001 NOTIFICATION_NOT_FOUND` on unknown id. No write/delete
  endpoints: the table is system-authored.
- **D8 — Error codes.** `NTF-9001` `NOTIFICATION_NOT_FOUND` appended to the
  shared `ErrorCode` enum + `notification.*` i18n keys EN+VI. Consumer-side
  failures never surface as error codes — they are row states (D5).
- **D9 — Security.** Fail-closed, no public paths; backoffice read endpoints
  ADMIN. JWT issuer per fleet `x-jwt` anchor.
- **D10 — Compose/init fixes in scope.** Add `notificationservice` DB to
  `docker/postgres/init/create-all-databases.sql` (missing today); fix the
  stub stanza: `POSTGRES_DB:*` envs → `SPRING_DATASOURCE_URL` fleet style,
  `KAFKA_SERVERS` → `SPRING_KAFKA_BOOTSTRAP_SERVERS`, keep `MAIL_*`.

## 3. Event → notification template

| eventType | subject | body |
|---|---|---|
| `order.created.v1` | `Order {orderId} created` | status, subtotal/tax/discount/total, items count |
| `order.status_changed.v1` | `Order {orderId} → {status}` | status, transitionedAt |

Templates are in-code builders (no template engine — YAGNI); body is plain
text, locale-neutral EN (i18n templates deferred until a recipient-locale
source exists).

## 4. Flow

```
@KafkaListener(shop.order.lifecycle.v1, group=notification-service)
 → parse (malformed JSON = SKIPPED row + ack, ERROR log)
 → dedupe: insert with unique natural key (violation = log + ack, no row)
 → build subject/body (§3)
 → INSERT row (status=SENT; or SKIPPED per D2/malformed above)
 → sender.send() outside the insert tx; sender throw → follow-up update FAILED
 → ack
```

Single-threaded listener (`concurrency: 1`) per partition-keyed topic is
sufficient at fleet scale; ordering per order id (Kafka key) is preserved.

## 5. Testing strategy (fleet 3 layers)

- **Unit**: template builders (both event types), dedupe key extraction,
  tolerant parsing, sender-failure → FAILED path, SMTP conditional wiring.
- **IT (Testcontainers PG + Kafka)** — the harness singleton pattern from
  promotion: real consumer drains a `KafkaTemplate`-produced record
  (awaitility): created + status-changed happy rows, duplicate redelivery →
  single row, unknown eventType → SKIPPED, malformed JSON → SKIPPED,
  sender-throws → FAILED row + ack (partition survives), backoffice list
  auth matrix.
- **Consumer slice tests**: listener with mocked sender/repo for fast matrix.

## 6. Fleet impact

`utils/common-core`: +1 `ErrorCode` entry, `ApiPaths.BACKOFFICE_NOTIFICATIONS`,
i18n keys — additive, rebased after tax merges (mechanical conflicts only:
enum tail + properties tail).
