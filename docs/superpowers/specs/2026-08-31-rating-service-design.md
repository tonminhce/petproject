# Rating Service — Design

- Date: 2026-08-31
- Status: Approved (4 scope decisions ratified by user with refinements)
- Scope: rating-service skeleton → FULL; +1 endpoint in order-service; +denormalized
  columns + first Kafka consumer in product-service; compose + init SQL. Gateway
  untouched (ServiceRoute.RATING already exists: resource `ratings`, port 8089).

## Verified ground truths

- Gateway routes ONLY `/api/v1/{resource}/**` from `ServiceRoute` enum
  (`RoutesConfig.java:31`). `RATING("rating-service", "ratings", "rating-service", 8089)`
  already exists → flat `/api/v1/ratings/**` is edge-routed day 1. Port 8089 free.
- Fleet precedent P2-6 (`OrderController.java:32`): storefront endpoints deliberately
  NOT `@PreAuthorize("hasRole('USER')")` — Keycloak users may lack the explicit USER
  realm role (unhelpful 403). Class-level authentication + service-layer owner checks.
- `Backoffice*Controller` paths (`/api/v1/backoffice/**`) are NOT edge-routed for ANY
  service (no enum resource matches) — backoffice is direct-service-access fleet-wide.
  Rating follows this; the edge gap is recorded in §4 as a fleet-wide open item.
- `order.created.v1` carries userId + items[productId]; `order.updated.v1` carries only
  orderId+status — hence sync verification (D1) instead of event-join.
- ApiPaths: `STOREFRONT_RATINGS`/`BACKOFFICE_RATINGS` constants exist but the fleet has
  no `/storefront/*` controllers; order/payment expose flat paths. Rating uses flat
  `ApiPaths.RATINGS` (new constant) + existing `BACKOFFICE_RATINGS`.
- ErrorCode tail ends `ORDER_PAYMENT_NOT_CAPTURED("ORD-4012", …)`; next block RTG-11xxx.
- Keycloak realm import contains only `ecommerce-client`; service clients
  (`order-service`/`changeme` style) are provisioned manually at ops time — fleet
  convention, rating follows it (§4).
- `payment_events` unique index + payment outbox relay are the copy-source for the
  rating outbox; partial-unique-with-WHERE is supported (payment idempotency pattern).

## D1 — Verified purchase: sync check (decision Q1-A)

order-service gains one endpoint (the only order-service change):

```java
@GetMapping("/verify-purchase")                       // on OrderController
@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
public ApiResponse<PageResponse<OrderItemResponse>> verifyPurchase(
        @RequestParam UUID userId, @RequestParam UUID productId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size)
```

Single-trip query (OrderQueryService → repository `@Query` JOIN orders + order_items):
`status = DELIVERED AND order.userId = ? AND item.productId = ? AND order.deleted = false`,
paged, mapped to existing `OrderItemResponse`. Eligibility = `content` non-empty.

rating-service `EligibilityClient` mirrors `PaymentServiceClient`: per-call
`Bearer ServiceTokenProvider.getToken()`, `ORDER_SERVICE_URL` (default
`http://localhost:8084`), timeout 3000 ms. **Fail-closed**: any non-2xx, timeout,
malformed body → `false` + ERROR log (lookup attempts logged → spam observable).
No feature flag: eligibility is rating's core dependency (unlike payment's optional
gate). Raw client exceptions never surface — mapped to RTG-11001 at the service seam.

## D2 — API surface (roles per P2-6 / fleet backoffice convention)

| Method | Path | Auth | Body | Behavior |
|---|---|---|---|---|
| POST | `/api/v1/ratings` | authenticated (no @PreAuthorize, P2-6) | `{productId, rating, comment}` | Eligibility via D1 → `verified`; 201 + body. Ineligible → 403 RTG-11001; (user,product) row already exists → 409 RTG-11005 (edit via PUT) |
| GET | `/api/v1/ratings?productId=&page=&size=` | authenticated | — | Page of visible (`hidden=false, deleted=false`) ratings for product, newest first, includes `verified` badge |
| PUT | `/api/v1/ratings/{productId}` | authenticated; owner row resolved by JWT userId + productId | `{rating, comment}` | Updates own rating; `verified` preserved; `editedAt=now`; 404 RTG-11002 if none. Event action=UPDATED |
| POST | `/api/v1/backoffice/ratings/{id}/hide` | ADMIN | `{reason}` | `hidden=true` + `hiddenAt/hiddenBy/hiddenReason`; 409 RTG-11003 if already hidden. Event action=HIDDEN |
| POST | `/api/v1/backoffice/ratings/{id}/unhide` | ADMIN | — | `hidden=false` (audit fields retained as history); 409 RTG-11004 if not hidden. Event action=UNHIDDEN |

## D3 — Data model

`ratings` (changelog-001-ratings.yaml):

```sql
id UUID PK (generated), product_id UUID NOT NULL, user_id UUID NOT NULL,
rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
comment TEXT NOT NULL CHECK (char_length(comment) BETWEEN 5 AND 2000),
verified BOOLEAN NOT NULL DEFAULT FALSE,
hidden BOOLEAN NOT NULL DEFAULT FALSE,
hidden_at TIMESTAMPTZ NULL, hidden_by UUID NULL, hidden_reason VARCHAR(500) NULL,
edited_at TIMESTAMPTZ NULL,
deleted BOOLEAN NOT NULL DEFAULT FALSE,
+ AbstractMappedEntity audit columns
CONSTRAINT ck_ratings_audit CHECK (
  (hidden = FALSE) OR (hidden = TRUE AND hidden_at IS NOT NULL AND hidden_by IS NOT NULL));
CREATE UNIQUE INDEX uk_rating_user_product_live ON ratings (user_id, product_id) WHERE deleted = FALSE;
CREATE INDEX idx_ratings_product_live ON ratings (product_id) WHERE deleted = FALSE AND hidden = FALSE;
```

No state machine — a rating row has no lifecycle beyond visible flags (FSM-free like
favourite, unlike order/shipping).

## D4 — Outbox event (rating → world)

Topic `shop.rating.lifecycle.v1`, **Kafka message key = productId** (per-product
partition ordering → last-write-wins is safe on the consumer). Outbox row committed in
the same transaction as the rating write (payment/shipping pattern); relay ports from
payment's `PaymentOutboxRelay`.

```json
{ "eventId": "uuid", "eventType": "rating.submitted.v1", "occurredAt": "ISO-8601",
  "ratingId": "uuid", "productId": "uuid", "userId": "uuid",
  "rating": 5, "comment": "string", "verified": true,
  "action": "CREATED|UPDATED|HIDDEN|UNHIDDEN", "visible": true,
  "avgRating": 4.32, "ratingCount": 27 }
```

**ADJUDICATION (deviation from user sketch — flagged for review):** the payload
CARRIES `avgRating`/`ratingCount` snapshots, recomputed in the same transaction
(`AVG(rating), COUNT(*)` over `hidden=false AND deleted=false` for that product).
Rationale: the sketch's `ratingAggregateService.currentAvg(...)` inside product's
consumer would require a synchronous product→rating client inside a Kafka listener —
a new failure mode, new boilerplate, and a recompute stampede on replays. Snapshot-in-
event keeps the consumer a dumb idempotent copy (no client, no failure semantics),
self-contained on replay, and satisfies the "recompute, not delta-math" rule by
construction. `visible` is kept in the payload per user ruling (future consumers may
filter without trusting snapshots).

## D5 — Product-service denormalization (decision Q2-A)

```sql
ALTER TABLE products ADD COLUMN avg_rating NUMERIC(3,2) DEFAULT 0.00;  -- nullable
ALTER TABLE products ADD COLUMN rating_count INTEGER NOT NULL DEFAULT 0;
```

Product's FIRST consumer: `RatingLifecycleListenerConfig` (`@EnableKafka` on the
factory config class — notification T10 ruling) + `ProductRatingConsumer extends
BaseKafkaConsumer` (group `product-service`, `ErrorHandlingDeserializer` poison
protection inherited) + `RatingLifecycleEvent` DTO (flattened envelope). Handler:
unknown productId → ack-skip; else copy `avgRating`/`ratingCount` onto the product,
save — `@Transactional`, idempotent on replay (snapshot copy, not delta).
`ProductResponse` gains `avgRating` + `ratingCount`.

## D6 — Rules & moderation posture (decision Q3-A)

- One rating per (user, product) ever: POST on existing row → 409 RTG-11005
  (`RATING_ALREADY_EXISTS`); edit via PUT (action UPDATED, `editedAt` set,
  `verified` preserved — original eligibility is NOT re-checked on edit).
- `verified` is stamped at submit time only.
- Auto-publish; ADMIN hide/unhide is the abuse lever (fast-hide beats queue latency).
  Future flip: `rating.moderation.mode=queued` env — code path documented as
  non-breaking (same outbox flow either way); NOT implemented in MVP.
- Abuse watch-list (not blocking): comment length bounds (D3), lookup logging (D1),
  hide endpoint; toxicity filter = §5 non-goal.

## D7 — Errors, i18n, metrics

ErrorCode tail (after `ORD-4012`, closes with `;`): `RATING_NOT_ELIGIBLE("RTG-11001",
"rating.not_eligible", 403)`, `RATING_NOT_FOUND("RTG-11002", "rating.not_found", 404)`,
`RATING_ALREADY_HIDDEN("RTG-11003", "rating.already_hidden", 409)`,
`RATING_NOT_HIDDEN("RTG-11004", "rating.not_hidden", 409)`,
`RATING_ALREADY_EXISTS("RTG-11005", "rating.already_exists", 409)`. Bean-validation
failures use the fleet ERR-0422-V convention. i18n EN+VI keys for all five.
Meter `rating_submitted_total{action}`.

## D8 — Infra

rating-service: port 8089, postgres db `ratingservice` (+ init SQL user/db), yml keys
`ORDER_SERVICE_URL:http://localhost:8084`, `KEYCLOAK_TOKEN_URL`, `client-id
${RATING_SERVICE_CLIENT_ID:rating-service}` / `changeme`, outbox poll 2000 ms (matches payment's relay cadence; spec originally said 5000 — corrigendum T1 review).
Compose: rating-service stanza (8089, db envs, `ORDER_SERVICE_URL:
http://order-service:8084`), appended after promotion stanza. Gateway: **zero
changes**. Keycloak: `rating-service` client provisioned at ops time (§4).

## §4 Ops & Contracts

1. Smoke: create order → pay+confirm (payment gate on) → admin ship/deliver →
   `POST /api/v1/ratings` → 201 `verified:true`; product GET shows updated
   `avgRating`/`ratingCount` after consumer tick; `PUT` edit; admin hide → product
   aggregate drops the rating on next event.
2. Consumer contract: value = JSON string-wrapped envelope (fleet unwrap-once rule);
   consumer must tolerate replay (snapshot copy) and unknown productIds.
3. Provision `rating-service` Keycloak client (client-credentials, service-account
   role `SERVICE`) — same manual step as every other service client (realm import gap
   is fleet-wide, §5).
4. Known fleet gaps NOT fixed here (recorded): `/api/v1/backoffice/**` not
   edge-routed (all Backoffice controllers, direct-service access); realm import
   lacks per-service clients.

## §5 Non-goals (binding)

Photos/videos (media epic), helpful-votes, review replies/threads, AI toxicity
filter, moderation queue implementation, public (JWT-less) rating reads.

## §6 Open items

- Eligibility refund predicate ("delivered AND not refunded") when refund flow ships.
- Redis-cached aggregates if per-product review volume makes snapshot recompute hot.
- Public ratings read = 1 gateway public-endpoints line later (no service change).
- Moderation queue flip via `rating.moderation.mode`.
