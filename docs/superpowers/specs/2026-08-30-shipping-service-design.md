# Shipping Service — Design

## 1. Purpose

Second fleet Kafka consumer and the order-lifecycle completer: turns
CONFIRMED orders into shipments, tracks them to DELIVERED (webhook-driven
with an auto-stale guard), and emits `shipping.delivered` for the post-merge
order wiring. Ships standalone — it does not touch order-service.

## 2. Binding decisions

- **D1 — Carrier port from day 1, manual-first (user decision Q2).**
  `CarrierAdapter { Carrier carrier(); ShipmentDraft createShipment(...);
  void cancelShipment(...); }`. Exactly two implementations this epic:
  `ManualCarrierAdapter` (admin supplies the tracking number; adapter just
  validates format) and `NoopCarrierAdapter` (internal/digital delivery —
  auto-generates `NOOP-<shipmentId>` tracking). GHN/GHTK adapters are future
  work behind the same port. `shipments.carrier` column keeps the schema
  carrier-agnostic.

- **D2 — Shipment state machine (marketplace-grade, user decision Q3).**
  `CREATED → PICKED_UP → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED`;
  `DELIVERY_FAILED` reachable from any in-flight state (ops escalation, allows
  retry to IN_TRANSIT). Auto-stale guard: reconciliation scheduler flips
  in-flight shipments with `last_carrier_update > SHIPMENT_AUTO_DELIVER_DAYS`
  (default 7) to `DELIVERED` with `auto_delivered = true` audit flag.
  `CONFIRMED` (customer explicit confirm) and `RETURNED` (RMA) are reserved
  states — spec'd, not implemented. Invariant: pure time never delivers — the
  scheduler only closes out shipments already in an in-flight carrier state
  (`PICKED_UP`+), never `CREATED`.

- **D3 — Order consumption (second consumer group).** Listens on
  `shop.order.lifecycle.v1` (group `shipping-service`, `latest` prod / IT
  override — notification precedent). Reacts to wrapper `status == CONFIRMED`:
  creates one shipment per order via the configured adapter (default manual →
  `CREATED` awaiting admin tracking number; `NoopCarrierAdapter` → straight to
  `PICKED_UP` with synthetic tracking). Idempotent: unique live constraint on
  `order_id` + persist-first/ack-always handling (duplicate delivery event →
  ack + no-op). Other statuses ignored. `CANCELLED` on an un-shipped shipment
  → shipment `CANCELLED` (audit trail); shipped orders are not auto-cancelled.

- **D4 — Carrier webhook receiver.** `POST /api/v1/webhooks/shipping/{carrier}`:
  HMAC-SHA256 fail-closed verify (401, no state change), `event_id` unique
  live on `shipment_events` (replay → ack + no-op), persist-first/ack-always
  (payment D4 precedent). Valid events drive
  PICKED_UP/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/DELIVERY_FAILED transitions
  and bump `last_carrier_update`. On DELIVERED (webhook or scheduler): write
  outbox row → `ShippingOutboxRelay` publishes
  `shop.shipping.lifecycle.v1` / `shipping.delivered.v1`
  `{eventId, eventType, occurredAt, orderId, shipmentId, carrier,
  trackingNumber, autoDelivered}`.

- **D5 — Admin manual tracking (the MVP write path).** Admin backoffice:
  list shipments (filters: status, carrier, orderId; paged newest-first), get
  by id, `POST /{id}/tracking` (set tracking number on a CREATED manual
  shipment → PICKED_UP), `POST /{id}/transition` (manual carrier status
  advance with body `{status}` validated by the FSM), `POST /{id}/fail` +
  `POST /{id}/retry`. All `hasRole('ADMIN')`. ManualCarrierAdapter shipments
  are admin-driven; adapter shipments never skip FSM edges.

- **D6 — Error codes: new SHP-1xxxx block (thousands 1–9 exhausted).**
  `SHP-10001 SHIPMENT_NOT_FOUND` (404), `SHP-10002 SHIPMENT_DUPLICATE`
  (409, order already shipped), `SHP-10003 SHIPMENT_INVALID_TRANSITION` (409),
  `SHP-10004 WEBHOOK_SIGNATURE_INVALID` (401), `SHP-10005 TRACKING_REQUIRED`
  (400, manual ship without tracking), `SHP-10006 CARRIER_NOT_CONFIGURED`
  (409). i18n keys EN+VI.

- **D7 — Security: fail-closed with one signature carve-out.** No public
  paths except the carrier webhook (HMAC-authenticated via the same
  public-paths carve-out pattern payment introduces — shipping spec consumes
  the pattern, does not re-own it). Backoffice ADMIN-only.

- **D8 — Schema (TIMESTAMPTZ audit columns).** `shipments(id, order_id,
  carrier, tracking_number, status, previous_status, auto_delivered bool,
  last_carrier_update, delivered_at, created_at, updated_at, version,
  deleted)` — unique live index on `order_id`, index on
  `(status, last_carrier_update)` for the scheduler sweep.
  `shipment_events(id, shipment_id, carrier, provider_event_id, type, payload
  jsonb, status, created_at, ...)` — unique live on
  `(carrier, provider_event_id)`. Campaign-precedent entity
  (`@Version Long`, soft-delete).

- **D9 — Metrics + alert surface.** Micrometer counters port the
  `OrderMetrics` precedent: `shipping.delivered.count` tagged
  `{auto=true|false}`, `shipping.failed.count`, plus a gauge of stale
  in-flight shipments. "No silent delivery" invariant = scheduler flips +
  metric increment; alert wiring (Prometheus rules) is ops-side, out of scope.

- **D10 — Deployment.** Port **8096**, DB `shippingservice` (init SQL
  addition owned by the shipping lane's tail window), compose stanza with
  `SHOP_KAFKA_BOOTSTRAP_SERVERS` (second consumer group), `SHIPMENT_AUTO_DELIVER_DAYS: "7"`,
  `SHIPPING_NOTIFY_THRESHOLD_HOURS` (reserved for notif follow-up), webhook
  secret env. Fixed scheduler (cron `0 0 * * * *`) — no config seam needed.

## 3. API

| Method | Path | Roles | Notes |
|---|---|---|---|
| GET | `/api/v1/backoffice/shipments` | ADMIN | paged, filters status/carrier/orderId |
| GET | `/api/v1/backoffice/shipments/{id}` | ADMIN | SHP-10001 if missing |
| POST | `/api/v1/backoffice/shipments/{id}/tracking` | ADMIN | CREATED → PICKED_UP (manual) |
| POST | `/api/v1/backoffice/shipments/{id}/transition` | ADMIN | manual carrier FSM advance |
| POST | `/api/v1/backoffice/shipments/{id}/fail` | ADMIN | → DELIVERY_FAILED |
| POST | `/api/v1/backoffice/shipments/{id}/retry` | ADMIN | DELIVERY_FAILED → IN_TRANSIT |
| POST | `/api/v1/webhooks/shipping/{carrier}` | HMAC (D7) | carrier state events |

## 4. Delivery flow (happy + stale paths)

order `CONFIRMED` event → shipment CREATED (manual) → admin tracking →
PICKED_UP → carrier webhooks advance to DELIVERED → outbox →
`shipping.delivered.v1` `{autoDelivered:false}`. Stale path: scheduler sweep
at day 7 → DELIVERED `auto_delivered=true` → same event tagged. Both paths
feed the post-merge order wiring (order DELIVERED link).

## 5. Testing strategy (fleet 3 layers)

- **Unit/service**: FSM (every legal edge + representative illegal →
  SHP-10003), stale sweep (day-6 no-op, day-7 flip + flag, CREATED never
  flipped), order-event handling (CONFIRMED → create idempotent, CANCELLED
  un-shipped, SHIPPED ignored), HMAC verify.
- **Controller slices**: webhook 401/replay/poison-ack, backoffice ADMIN
  matrix, validation 400s.
- **IT (Testcontainers PG + real Kafka)**: order-CONFIRMED → shipment row
  (exactly one under duplicate publish), tracking + webhook advance to
  DELIVERED with outbox publish asserted, scheduler sweep via truncated
  `SHIPMENT_AUTO_DELIVER_DAYS`, `last_carrier_update` semantics. 2-scenario
  flow IT mirrors NotificationFlowIT structure.

## 6. Fleet impact (lane rules)

- **shipping lane = W2 for shared-file tails** (SHP-1xxxx block, ApiPaths
  `BACKOFFICE_SHIPMENTS` + webhook constant, i18n, init SQL
  `CREATE DATABASE shippingservice;`) — gated behind the branch-to-branch
  unlock merge from payment, exactly the af71412 mechanism.
- **order-service: untouched.** Post-merge wiring task (single owner):
  confirm-guard consumes payment state, order DELIVERED transition consumes
  `shipping.delivered.v1`, gateway routes for both services.
- notification templates for shipped/delivered subjects = follow-up.

## 7. Out of scope

Real carrier integrations (GHN/GHTK adapters), customer confirm endpoint,
RMA/RETURNED flow, label PDF generation and storage (common-storage
integration reserved), dispute windows, address validation.
