# Search Service — Design

- Date: 2026-08-31
- Status: Approved (4 scope decisions ratified: event-driven ingestion, ProductUpdated
  on rating save, query surface A, reindex + no-autostart)
- Scope: search-service skeleton → FULL (ES index + consumer + query API + reindex);
  product-service: enriched event payload + rating-driven ProductUpdated. Gateway zero.

## Verified ground truths

- `ServiceRoute.SEARCH` = resource `search`, port **8094**; `/api/v1/search/**`
  edge-routed day 1. Compose stanza pre-wired (ES URL/creds + `KAFKA_SERVERS`) —
  kafka env key MISMATCHES fleet (`SHOP_KAFKA_BOOTSTRAP_SERVERS`) → fix in epic
  (f4ec967 URL-pattern precedent).
- product-service publishes `shop.product.lifecycle.v1` (outbox, aggregateId=productId
  → per-product partition ordering ✓) with eventTypes `ProductCreated/ProductUpdated/
  ProductDeleted`. **Payload today is a stub**: eventId, eventType, occurredAt,
  productId, slug, status ONLY. NO product fields. Zero existing consumers on the
  topic → payload enrichment is additive-safe.
- `ProductRatingService.apply` saves product via plain repo.save — **no event** →
  rating-driven star updates are invisible to any event-driven consumer today (the
  Q2 gap; closed by D4).
- ES 8.15.0 single-node in compose (`elasticsearch`, healthchecked); search pom already
  has `elasticsearch-java` + spring-kafka; no yml beyond skeleton.
- ErrorCode tail ends `RATING_ALREADY_EXISTS("RTG-11005", …)`; next block SRH-12xxx.
- Fleet test-cache rule (spec 2026-08-31-test-cache-isolation-design.md) applies if any
  caching lands here — search adds NO cache in MVP (ES is the read store).

## D1 — Event ingestion (decision Q1-A)

`SearchListenerConfig` (@EnableKafka on factory config — T10 ruling) +
`ProductSearchConsumer extends BaseKafkaConsumer<String, ProductLifecycleEvent>`
(group `search-service`, `auto-offset-reset: latest`, ErrorHandlingDeserializer
inherited). Containment = fleet posture: handler try/catch-log-swallow, listener never
throws; poison = log-and-skip (NO DLT — fleet rule, spec §4(2) of order-wiring).

Handler: `ProductCreated|ProductUpdated` → `ProductSearchService.index(event.payload)`
(dumb upsert — payload is a FULL snapshot after D2); `ProductDeleted` → delete doc by
id (404 = ack-ok); unknown eventType → ack-skip. Consumer group lag tolerable (ES
eventual vs DB source of truth).

## D2 — Enriched product event payload (additive, binding contract change)

`TransactionalProductEventPublisher.save()` payload gains the full catalog snapshot
(built from the Product entity via ProductMapper — single mapping source):
`title, description, brandId, brandName, categoryId, categoryName, slug (existing),
status (existing), price (priceUnit), imageUrl, avgRating, ratingCount, updatedAt`.
product-service change ONLY in the publisher + rating service (D4). Contract note in
spec §4: additive-only; eventType strings UNCHANGED (`ProductCreated` etc.).

## D3 — Index & document

Index template `products-v{n}` behind alias **`products`** (reindex-safe atomic alias
swap, D5). Mapping: `id` keyword; `title` text (searchable, boost 3) + `.keyword`
subfield; `description` text (boost 1); `brandName` text (boost 2) + keyword subfield;
`brandId`/`categoryId` keyword; `categoryName` text (boost 1); `slug` keyword;
`imageUrl` keyword (index: false); `price` double; `avgRating` half_float (null when
never rated); `ratingCount` integer; `status` keyword (only ACTIVE indexed — consumer
skips/deletes non-ACTIVE); `updatedAt` date. Standard analyzer MVP — Vietnamese
segmentation is an open item (§6).

## D4 — Star freshness (decision Q2-A)

`ProductRatingService.apply` calls `productEventPublisher.publishUpdated(product)`
AFTER repo.save (same tx — outbox row commits atomically; REQUIRED propagation joins).
Enriched payload carries the new avgRating/ratingCount → search consumer updates the
doc. Single ingestion path preserved; every future consumer benefits.

## D5 — Query API (decision Q3-A) & Reindex (Q4)

`GET /api/v1/search` — authenticated (P2-6: no @PreAuthorize; edge JWT). Params:
`q` (multi_match: title^3, brandName^2, categoryName, description — best_fields),
`brandId`, `categoryId`, `minPrice`, `maxPrice`, `minRating`, `status` ignored (always
ACTIVE-only), `sort` = `relevance` (default) | `price_asc` | `price_desc` |
`rating_desc` | `newest`, `page` (0), `size` (20, cap 200). Empty `q` + filters =
browse (match_all + filters). Response `ApiResponse<PageResponse<ProductSearchResponse>>
` (id, title, brandName, categoryName, slug, imageUrl, price, avgRating, ratingCount).

`POST /api/v1/backoffice/search/reindex` — class @PreAuthorize ADMIN. Streams ALL
ACTIVE products page-by-page into `products-v{n+1}`, then atomically swaps alias
`products` → new index, deletes the old index. Body optional `dryRun` (counts only).
Response `ApiResponse<ReindexResponse{indexed, indexName, tookMs}>`. 409
`SEARCH_REINDEX_IN_PROGRESS` if one is already running (in-process lock — single
instance MVP). NO auto full-sync on startup (ops: first deploy → run reindex once;
events keep it fresh after — §4).

## D6 — Errors, i18n, metrics

ErrorCode tail (flip RTG-11005 `,`; `SEARCH_REINDEX_IN_PROGRESS("SRH-12001",
"search.reindex_in_progress", 409)` new `;` terminator), `SEARCH_QUERY_FAILED
("SRH-12002", "search.query_failed", 503)` on ES unavailability at query time (client
catches EsIOException/timeout → 503, never leaks raw). i18n EN+VI 2 keys. Meter
`search_queries_total{sort}` on the query endpoint.

## D7 — Infra

yml: port 8094, `elasticsearch.{url:${ELASTICSEARCH_URL:http://localhost:9200},
username:${ELASTICSEARCH_USERNAME:},password:${ELASTICSEARCH_PASSWORD:}}`,
`shop.kafka` block (fleet binding), NO outbox (search publishes nothing in MVP).
`ElasticsearchClient` bean: raw `elasticsearch-java` client + RestClient transport
from props (BasicCredentials when username set). Compose: `KAFKA_SERVERS` →
`SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` (stanza otherwise intact). Gateway: zero
changes.

## §4 Ops & Contracts

1. First deploy: run reindex once (ADMIN) to bootstrap existing catalog; events keep
   it fresh thereafter. Re-index after ES data loss or mapping bump.
2. Consumer contract: value = JSON string-wrapped envelope (unwrap-once); payload is
   FULL snapshot (D2 contract); consumer tolerates replay (idempotent upsert by id),
   unknown eventTypes, unknown productIds (status filter handles delisted).
3. ES creds via compose `.env` (`ELASTICSEARCH_USERNAME/PASSWORD`) — required vars,
   no defaults.
4. Event contract (D2): additive-only enrichment; eventType strings unchanged;
   per-product partition ordering (aggregateId=productId) — consumers may rely on it.

## §5 Non-goals (binding)

Facets/aggregations, autocomplete/suggestions, synonym/Vietnamese analyzer, geo,
ML relevance, search analytics store, outbox publishing from search, ES security
beyond BasicCredentials (fleet local posture).

## §6 Open items

- Vietnamese analyzer (custom tokenizer/nori alternative) when storefront search
  quality work begins.
- Facets/aggregations when storefront sidebar lands.
- product/inventory `immediateWrites` alignment (test-cache rule 6 follow-up).
- Cross-service search (products + ratings text) — ES-side join out of scope.
