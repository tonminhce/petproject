# Search Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** search-service skeleton → FULL (ES product index + event-driven ingestion + query API + ADMIN reindex); product-side payload enrichment + rating-driven ProductUpdated.

**Architecture:** product outbox (`shop.product.lifecycle.v1`, key=productId) → search consumer dumb-upserts full-snapshot docs into ES index behind alias `products`; query = multi_match + filters + sort; reindex = stream+bulk+alias-swap. Spec is binding: `docs/superpowers/specs/2026-08-31-search-service-design.md`.

**Tech Stack:** elasticsearch-java 8.15 client (raw, RestClient transport), spring-kafka (common-kafka base), Testcontainers (postgres/kafka/elasticsearch 8.15.0).

**Spec:** docs/superpowers/specs/2026-08-31-search-service-design.md

## Global Constraints

- Port 8094; gateway `ServiceRoute.SEARCH` exists — **zero gateway changes**.
- ErrorCode: flip `RATING_ALREADY_EXISTS("RTG-11005", …)` `;`→`,`; append `// ---- Search domain ----` with `SEARCH_REINDEX_IN_PROGRESS("SRH-12001","search.reindex_in_progress",409)`, `SEARCH_QUERY_FAILED("SRH-12002","search.query_failed",503)`; SRH-12002 gets `;`.
- P2-6: GET /api/v1/search NO @PreAuthorize (authenticated class-level); backoffice class-level ADMIN.
- Containment: consumer never throws (fleet log-and-skip, NO DLT).
- Copy-sources: rating-service (consumer stack + IT base + meter), product TransactionalProductEventPublisher (enrichment site).
- i18n: `utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties`.
- Verify ApiPaths: add `SEARCH = API_V1 + "/search"` + `BACKOFFICE_SEARCH = API_V1 + "/backoffice/search"` if absent.
- Test gates: `./mvnw -pl search-service test`, `./mvnw -pl product-service test` (52 baseline), order 202 untouched.

---

### Task 1: search bootstrap — yml, ES client, index provisioning, IT base

**Files:**
- Modify: `search-service/src/main/resources/application.yml` (port 8094; `elasticsearch.{url:${ELASTICSEARCH_URL:http://localhost:9200},username:${ELASTICSEARCH_USERNAME:},password:${ELASTICSEARCH_PASSWORD:}}`; `shop.kafka` block — copy rating's, group-id `search-service`, latest)
- Create: `config/ElasticsearchConfig.java` — `ElasticsearchClient` bean: RestClientTransport over Apache RestClient from props; BasicCredentials when username non-empty; timeouts 3s connect / 10s SO
- Create: `config/SearchIndexConfig.java` + `search/IndexProvisioner.java` — on startup (`ApplicationRunner`, ES-down tolerated with ERROR + no crash): ensure index template `products-template` (mapping per spec D3) + index `products-v1` + alias `products` (idempotent — exists-checks)
- Create: `config/SearchProperties.java` (`@ConfigurationProperties("elasticsearch")` record url/username/password + `@ConfigurationPropertiesScan` on app)
- Create: test support `AbstractSearchIntegrationTest.java` — Testcontainers: postgres + kafka (port rating's `AbstractIntegrationTest` singleton pattern) + **elasticsearch 8.15.0** container (env `discovery.type=single-node`, `xpack.security.enabled=false`, passwordless for tests); point props at container
- Test: `SearchIndexProvisioningIT` — context boots against container; index + alias exist; template present (assert via client `getIndex`/`getAlias`)

**Interfaces:**
- Produces: `ElasticsearchClient` bean (all later tasks); alias `products`; index naming `products-v{n}`.

- [ ] **Step 1:** IT first (RED — no config classes). **Step 2:** implement. **Step 3:** `./mvnw -pl search-service test` green. **Step 4: commit** `feat(search): bootstrap — ES client, index template/alias provisioning, IT base`

### Task 2: product-side — enriched payload + rating-driven ProductUpdated

**Files:**
- Modify: `product-service/.../service/impls/TransactionalProductEventPublisher.java` — `save()` payload gains full snapshot per spec D2: `title, description, brandId, brandName, categoryId, categoryName, slug (exists), status (exists), price (priceUnit), imageUrl, avgRating, ratingCount, updatedAt` — resolve brand/category names EXACTLY the way `ProductMapper.toSummaryResponse` does (same relations/accessors — read the mapper first; do NOT invent a second mapping path). eventType strings + envelope fields untouched; key stays aggregateId=productId
- Modify: `product-service/.../service/ProductRatingService.java` — inject `ProductEventPublisher`; after `productRepository.save(product)` add `publisher.publishUpdated(product)` (same tx; outbox row atomic)
- Test: extend/add publisher payload test pinning EVERY field name+type for a fully-loaded product (incl. avgRating/ratingCount + null-safety for never-rated products: avgRating null → JSON null); ProductRatingServiceTest: apply() now emits ProductUpdated (verify + same-tx)

- [ ] **Step 1: failing tests** (payload parity matrix + rating-emits-updated). **Step 2: implement.** **Step 3:** `./mvnw -pl product-service test` green (52+). **Step 4: commit** `feat(product): full-snapshot lifecycle payloads + ProductUpdated on rating apply`

### Task 3: search consumer — ingestion

**Files:**
- Create: `search-service/.../kafka/dto/ProductLifecycleEvent.java` — record: eventId, eventType, occurredAt, productId (UUID), slug, status, title, description, brandId, brandName, categoryId, categoryName, price (BigDecimal), imageUrl, avgRating (BigDecimal), ratingCount (Integer), updatedAt (String→Instant mapping in handler) — `@JsonIgnoreProperties(ignoreUnknown=true)`
- Create: `kafka/SearchListenerConfig.java` + `kafka/ProductSearchConsumer.java` (rating's shape verbatim; containment log-and-swap; unknown eventType → ack-skip)
- Create: `service/ProductSearchService.java` — `index(payload)`: status != ACTIVE → delete-by-id (404 ok); else upsert doc (`products` alias, id=productId, fields per D3 mapping; avgRating null allowed); `delete(productId)`. Doc write via client `index` request with `refresh=false` (tests use refresh forcing)
- Test: `ProductSearchIngestionIT` (Testcontainers kafka+ES): produce real JSON envelopes (string-wrapped value per fleet contract) → assert doc indexed/updated/deleted; non-ACTIVE update deletes; unknown eventType consumed no-op; malformed payload consumed no-throw (poison path)

- [ ] **Step 1: IT RED** → **Step 2: implement** → **Step 3:** module green. **Step 4: commit** `feat(search): product lifecycle consumer — dumb upsert/delete into ES`

### Task 4: query API + errors + i18n + meter

**Files:**
- Modify: common-core ErrorCode (tail per Global Constraints) + common-spring i18n (2 EN + 2 VI)
- Create: `dto/request/SearchParams.java` (validated: q ≤ 200 chars, page ≥ 0, size ≤ 200), `dto/response/ProductSearchResponse.java` + `ReindexResponse.java` (Task 5)
- Create: `service/SearchQueryService.java` — bool query: must multi_match(q, [title^3, brandName^2, categoryName, description], best_fields, lenient) when q present else match_all; filter terms/terms-range (brandId, categoryId, price gte/lte, avgRating gte when minRating set); sort mapping (relevance→_score, price_asc/desc→price, rating_desc→avgRating desc with nulls_last, newest→updatedAt desc); page/size → from/size (cap 200); ES failure (EsIOException/timeout) → BusinessException SRH-12002 503
- Create: `controller/SearchController.java` — GET `/api/v1/search` (ApiPaths.SEARCH; no @PreAuthorize; P2-6 javadoc) → ApiResponse<PageResponse<ProductSearchResponse>>
- Create: `metrics/SearchMetrics.java` (rating's RatingMetrics pattern) — `search_queries_total{sort}`
- Test: `SearchQueryIT` (seed docs via service): q relevance ordering (title match ranks above description match); brand/category/price-range/minRating filters (minRating excludes never-rated); each sort; pagination + cap; empty-q browse; ES-down → 503 SRH-12002 (stop container or point client at dead port in a slice test); controller 401 unauth; meter increments

- [ ] **Step 1: RED → implement → GREEN.** **Step 2:** module suite green. **Step 3: commit** `feat(search): query API — multi_match + filters + sorts + SRH errors + meter`

### Task 5: reindex endpoint

**Files:**
- Create: `controller/BackofficeSearchController.java` — @RequestMapping(ApiPaths.BACKOFFICE_SEARCH) + class ADMIN; `POST /reindex` body optional `{dryRun:boolean}`; 409 SRH-12001 via in-process AtomicBoolean lock
- Create: `service/ReindexService.java` — resolve next `products-v{n+1}` (max existing +1); page through ALL ACTIVE products (ProductRepository in search? NO — search has no product DB. Reindex source = product-service data... **ADJUDICATION: reindex pulls from ES? No. Source of truth is product DB which search does not own → reindex needs a SERVICE read: product backoffice list (SERVICE+ADMIN or SERVICE) paginated — check product's existing internal-friendly endpoint (GET /api/v1/products is storefront? has page API) → call with SERVICE token (EligibilityClient-pattern client: `ProductBackofficeClient`, props `PRODUCT_SERVICE_URL:${PRODUCT_SERVICE_URL:http://localhost:8086}`, fail → SRH-12002-family? define REINDEX source-failure → 503 SRH-12002). Bulk index into new index → alias swap (atomic: remove old alias, add new, delete old index) → ReindexResponse. dryRun: count only, no writes.**
- Test: `ReindexIT`: with product WireMock serving 2 pages → reindex creates v2, swaps alias, docs queryable via alias, old index deleted; dryRun returns counts with no index created; concurrent second call → 409; product-service down → 503

- [ ] **Step 1: RED → implement → GREEN** (WireMock pattern from rating EligibilityClientTest for the source client). **Step 2:** module green. **Step 3: commit** `feat(search): ADMIN reindex — stream+bulk+alias swap`

### Task 6: compose fix + cross-module verification

**Files:**
- Modify: `docker-compose.yml` — search stanza `KAFKA_SERVERS: kafka:9092` → `SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092` (f4ec967 pattern; verify search yml binds that key)

- [ ] **Step 1:** `docker compose config -q` exit 0. **Step 2:** full battery: `./mvnw -T1C install -DskipTests -q`; `./mvnw -pl search-service,product-service,order-service test` all green (search ~20+, product 52+, order 202); gateway 19/19. **Step 3: commit** `chore(compose): search kafka env key — fleet binding (SHOP_KAFKA_BOOTSTRAP_SERVERS)`

### Task 7: final whole-branch review

- [ ] Dispatch reviewer: whole-branch diff vs main; spec D1–D7 + §4/§5/§6 audit; E2E hop check (product save → enriched payload → outbox → search consumer → ES doc → query API; rating apply → ProductUpdated → doc stars refresh); zero regression (product payload contract additive-only, no other consumers break — verify no other module consumes shop.product.lifecycle.v1); security (P2-6, ADMIN, ES creds no defaults in prod posture); fleet rules (containment no-DLT, immediateWrites n/a — no cache, meter idiom). Fix rounds until APPROVED / adjudicated APPROVED-WITH-FINDINGS.
