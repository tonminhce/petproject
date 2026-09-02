# Search Service — Design

- Date: 2026-09-01
- Status: Draft (pending user ratification of 4 scope decisions)
- Scope: search-service skeleton → FULL. Elasticsearch 8 index, sync via Kafka consumer (product + rating events), search/suggest APIs. Gateway already routes by ServiceRoute.SEARCH (port 8094, resource search); no gateway change.

## Verified ground truths

- ServiceRoute.SEARCH("search-service", "search", "search-service", 8094) exists (line 23 of ServiceRoute.java) → flat /api/v1/search/** is edge-routed day 1. Port 8094 free.
- search-service/pom.xml excludes common-spring's JPA starter (line 19-23) and pulls elasticsearch-java + spring-kafka + gson (line 25-35). No JPA — Elasticsearch is the only store. No service-to-service DB.
- product-service emits shop.product.lifecycle.v1 with product {productId, title, description, categoryId, status, avgRating, ratingCount, ...} (product spec §3). rating-service emits shop.rating.lifecycle.v1 (rating spec D4). Search consumes both.
- Fleet has no search library yet (skeleton only — SearchServiceApplication.java is the single class). ES integration must be implemented from scratch.
- No common-search abstraction yet — search-service owns its ES client. Future common-search possible when 2nd service needs ES (§7 open).
- Backoffice paths (/api/v1/backoffice/**) bypass gateway fleet-wide (rating D1 ground truth). search follows.

## §1 Binding decisions

### D1 — Elasticsearch 8 native client, no Spring Data ES (decision Q1)

Use co.elastic.clients:elasticsearch-java directly. Wrapper lives in search-service only (no common-search). Reasons: (1) Spring Data ES adds a thin abstraction layer over the same client — duplicated mapping metadata; (2) the fleet precedent is plain clients (common-kafka wraps spring-kafka, common-storage wraps S3 SDK); (3) V1 has exactly one consumer (search-service), so premature abstraction is unjustified.

Trade-off: more boilerplate (manual RestClient → ElasticsearchClient bean wiring), but full control over query DSL and lower dependency surface. Common-search extraction deferred (§7).

### D2 — Single index products_v1 with multi-fields per language (decision Q2)

ONE physical index `products_v1` with multi-fields: `title_vi`, `title_en`, `description_vi`, `description_en` analyzed in their respective analyzers (icu_analyzer for Vietnamese diacritics, standard for English). A `title` field holds the raw text (used for sort/exact match). Search query targets `title_vi^2 OR title_en^2` (locale preference) with cross-field fallback.

Rationale: separate per-locale indices (products_vi, products_en) double the indexing cost and complicate facet aggregation. Multi-field within one index is the ES-recommended pattern; analyzer-per-field gives locale-correct tokenization.

Mapping (excerpt):

```json
{
  "settings": { "analysis": { "analyzer": { "vi_analyzer": { "type": "custom", "tokenizer": "standard", "filter": ["lowercase", "asciifolding"] } } } },
  "mappings": { "properties": {
    "productId": { "type": "keyword" },
    "title": { "type": "text", "fields": { "vi": { "type": "text", "analyzer": "vi_analyzer" }, "en": { "type": "text", "analyzer": "standard" }, "raw": { "type": "keyword" } } },
    "categoryId": { "type": "keyword" },
    "status": { "type": "keyword" },
    "avgRating": { "type": "scaled_float", "scaling_factor": 100 },
    "ratingCount": { "type": "integer" },
    "price": { "type": "scaled_float", "scaling_factor": 100 },
    "currency": { "type": "keyword" },
    "createdAt": { "type": "date" },
    "updatedAt": { "type": "date" }
  } }
}
```

### D3 — Sync via consumer; no real-time in-line indexing (decision Q3)

search-service is a Kafka consumer only — it NEVER receives synchronous index calls from product-service. Two consumers:

- `SearchProductConsumer` (group search-service-products) on shop.product.lifecycle.v1: product.created → PUT _doc, product.updated → PUT _doc (idempotent), product.deleted → DELETE _doc.
- `SearchRatingConsumer` (group search-service-ratings) on shop.rating.lifecycle.v1: rating events update avgRating + ratingCount on the product doc (partial _update with painless script: `ctx._source.avgRating = params.avg; ctx._source.ratingCount = params.count`).

Trade-off vs real-time inline indexing: ~100ms consumer lag on average, but the rating-event payload already carries the snapshot (rating D4 spec), so no recompute needed — copy directly. Consumer is dumb and idempotent (PUT/DELETE by id, partial update by event payload).

### D4 — Search response shape (decision Q4)

```json
{ "hits": [ { "productId": "uuid", "title": "...", "score": 12.3, "snippet": "...", "avgRating": 4.5, "ratingCount": 27, "price": 999.99, "currency": "VND" } ],
  "total": 1234, "page": 0, "size": 20, "facets": { "categoryId": [{"categoryId":"a","count":42}], "priceRanges": [{"range":"0-100","count":12}] } }
```

Pagination: page + size (max 100, default 20). Highlighting on title + description (default 100 char fragment, tags <em>...</em>). Aggregations: terms on categoryId, range on price (4 buckets: 0-100k, 100k-500k, 500k-2M, 2M+ VND — fleet currency default VND; §7 open for multi-currency).

### D5 — Suggest endpoint (autocomplete)

GET /api/v1/search/suggest?q=&size=5 — completion suggester on `title.raw` (keyword field). Returns ordered array of title strings. No auth (used by storefront typeahead).

### D6 — Admin reindex endpoint

POST /api/v1/backoffice/search/reindex (ADMIN-only, async) — fetches ALL active products via outbound ProductServiceClient (NEW — search → product outbound call, mirrors rating's EligibilityClient pattern), bulk-indexes in batches of 500. Returns 202 + {jobId, totalProducts, indexedSoFar}. Status polled via GET /api/v1/backoffice/search/reindex/{jobId}. Slow path: ~10 minutes for 100k products on a single shard; V1 acceptable.

Reindex job state lives in-memory (ConcurrentHashMap) — admin tooling V1, not durable. §7 open for persistence.

### D7 — Error codes: new SRC-13xxx block

SRC-13001 SEARCH_QUERY_INVALID (400, empty q when required), SRC-13002 SEARCH_INDEX_UNAVAILABLE (503, ES cluster red/yellow), SRC-13003 REINDEX_IN_PROGRESS (409, another reindex job running), SRC-13004 REINDEX_NOT_FOUND (404, jobId unknown). i18n EN+VI keys for all four. Tail anchored at current end of ErrorCode.java (after MED-12006).

### D8 — Security: mostly public, ADMIN for reindex

/api/v1/search/** (search + suggest) are PUBLIC — no JWT (CDN-friendly; mirrors media D5). /api/v1/backoffice/search/** is ADMIN-only. No user-scoped results in V1 (personalization deferred §7).

### D9 — Schema-less (no DB)

search-service has NO relational database. ES is the only store. Kafka consumers write directly to ES via the native client. In-memory state for reindex job tracking. Liquibase/changelog is not part of this service.

### D10 — Deployment

Port 8094. Compose stanza mirrors rating: ES_URL (http://elasticsearch:9200 via dedicated elasticsearch container in compose, NOT shared with any other service), kafka env, healthcheck. **NEW compose service `elasticsearch`** (single-node, 8.x, 512m heap, 1g volume). search-service depends_on elasticsearch healthy.

ES is a NEW infrastructure dependency — recorded as fleet-level addition. The rating/notification/payment/shipping compose top-level has postgres + kafka + keycloak + rustfs; adding elasticsearch is the V1 change.

## §2 API surface

| Method | Path | Auth | Body | Behavior |
|---|---|---|---|---|
| GET | /api/v1/search/products?q=&categoryId=&minPrice=&maxPrice=&page=&size= | PUBLIC | — | Full search per D4 |
| GET | /api/v1/search/suggest?q=&size= | PUBLIC | — | Completion suggester per D5 |
| GET | /api/v1/search/health | PUBLIC | — | 200 if ES reachable, 503 otherwise (compose healthcheck) |
| POST | /api/v1/backoffice/search/reindex | ADMIN | — | Kick off reindex; 202 + jobId |
| GET | /api/v1/backoffice/search/reindex/{jobId} | ADMIN | — | Status: {status, totalProducts, indexedSoFar, startedAt, finishedAt} |

## §3 Event consumption

shop.product.lifecycle.v1 (group search-service-products):
- product.created.v1 → ES PUT /products_v1/_doc/{productId} with full doc (from event payload, includes avgRating=0, ratingCount=0 since product spec defines these defaults).
- product.updated.v1 → ES PUT /products_v1/_doc/{productId} (full doc overwrite; idempotent).
- product.deleted.v1 → ES DELETE /products_v1/_doc/{productId} (404 on already-deleted is idempotent ack).

shop.rating.lifecycle.v1 (group search-service-ratings):
- rating.submitted.v1 → ES POST /products_v1/_update/{productId} with painless script copying avgRating + ratingCount from event payload. Unknown productId → log + skip (consumer is dumb copy, does NOT cross-reference product service).

Both consumers: @EnableKafka on factory config (T10 ruling), ErrorHandlingDeserializer poison protection, manual ack after ES write succeeds.

## §4 Testing strategy

- Unit/service: ES client mocked — search returns hits, suggest returns prefixes, reindex job lifecycle (queued → running → completed/failed), index mapping JSON schema validation.
- Controller slices: 200 with hits, 400 empty q, 503 ES down, ADMIN matrix.
- IT (Testcontainers ES 8.x + real Kafka): end-to-end Kafka publish → consumer → ES write → search returns the doc. Rating partial-update reflects in search hit. Reindex from product stub: 10 products → 10 docs.

## §5 Fleet impact (lane rules)

- search lane = W1 on shared files (ErrorCode SRC-13xxx tail, ApiPaths SEARCH constant, i18n keys). No DB init SQL needed (no DB).
- product-service: NOT touched in this epic — product.lifecycle.v1 events are emitted by product spec §6 today (verify ground truth by reading product spec §6 before planning T1).
- rating-service: NOT touched — rating.lifecycle.v1 already emitted (rating D4).
- common-* modules: untouched.
- compose: ADD elasticsearch service (single-node, 8.x) — first new infra dependency since rustfs. Append search-service stanza.
- Gateway: verify-only — RoutesConfig already routes by ServiceRoute.SEARCH.

## §6 Non-goals (binding)

Personalization (user-scored ranking); A/B testing; query understanding (synonyms, spell correction); multi-currency (VND only); second-language support beyond VI/EN; analytics/aggregation service; index aliasing for zero-downtime reindex (V1 accepts ~30s downtime on schema migration); ML-based ranking; voice search.

## §7 Open items

- Common-search extraction: defer until 2nd service needs ES (analytics? reporting?). §1 D1 noted.
- Multi-currency: rating/price facets hard-coded VND buckets; multi-currency requires currency-aware facet strategy.
- Reindex job persistence: in-memory ConcurrentHashMap is lost on restart. Admin polling a job across restart returns 404. Acceptable for V1 admin tooling; persist if reindex becomes long-running.
- ES security: cluster is single-node, no auth in V1 (compose-only). Production ES would need xpack security — §6 non-goal.
- Index aliasing for zero-downtime schema migration: defer until first schema change.
- Index snapshot/restore: ops-side, not service.

## §8 Changelog

- 2026-09-01 (rev 0): Initial draft pending user ratification of D1 (native client, no Spring Data), D2 (single index multi-field), D3 (consumer-only sync), D4 (response shape with facets).