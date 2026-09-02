# Search Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** search-service skeleton → FULL (Elasticsearch 8 index, sync via Kafka consumer for product + rating events, search/suggest/reindex APIs).

**Architecture:** ES 8 native client (co.elastic.clients). Two Kafka consumers (product + rating) dumb-copy event payloads to ES. Public search/suggest; ADMIN reindex. Spec is binding authority: docs/superpowers/specs/2026-09-01-search-service-design.md.

**Tech Stack:** Spring Boot 4 (fleet BOM), NO JPA, NO Liquibase (no DB), common-core/common-spring/common-security/common-kafka, Elasticsearch 8 + elasticsearch-java client, spring-kafka, gson (event payload unwrap).

**Spec:** docs/superpowers/specs/2026-09-01-search-service-design.md

## Global Constraints

- Port 8094; gateway ServiceRoute.SEARCH already exists — zero gateway changes.
- ErrorCode block SRC-13xxx appended after MED-12006 — flip , → ; on MED-12006.
- Public /api/v1/search/** (search, suggest, health) are JWT-bypass. /api/v1/backoffice/search/** is ADMIN-only.
- Two separate Kafka consumer groups: search-service-products and search-service-ratings (independent progress tracking, no replay stampede).
- ES is a NEW compose service. First new infra dependency since rustfs.
- No DB; ES is the only store. No Liquibase. application.yml has no datasource block.
- Copy-sources: payment's BaseKafkaListenerConfig + BaseKafkaConsumer, payment's outbox relay (NOT used here — search is consumer-only).
- i18n: utils/common-spring/src/main/resources/messages/messages_{en,vi}.properties.

---

### Task 1: search-service bootstrap — pom, config, ES client, listeners

**Files:**
- Modify: search-service/pom.xml (add common-core, common-security, common-kafka; keep elasticsearch-java, spring-kafka, gson, lombok; test: starter-test, webmvc-test, security-test, awaitility).
- Create: search-service/src/main/resources/application.yml (port 8094, NO datasource, shop.kafka block, shop.search.elasticsearch.{url:${ELASTICSEARCH_URL:http://localhost:9200}, connect-timeout-ms:2000, socket-timeout-ms:5000}, shop.search.index.name:products_v1, shop.search.reindex.batch-size:500, no DB).
- Create: search-service/src/main/java/com/shop/searchservice/config/ElasticsearchConfig.java (@Bean ElasticsearchClient via RestClient + ElasticsearchTransport; @Bean ElasticsearchIndices index manager bean).
- Create: search-service/src/main/java/com/shop/searchservice/config/SearchProperties.java (@ConfigurationProperties shop.search: Elasticsearch(String url, long connectTimeoutMs, long socketTimeoutMs), Index(String name), Reindex(int batchSize)).
- Create: search-service/src/main/java/com/shop/searchservice/indices/ElasticsearchIndices.java (ensureIndex(name, mappingJson) at @PostConstruct; idempotent on existing index).
- Test: SearchPropertiesTest (@ConfigurationProperties binding via ApplicationContextRunner).

- [ ] **Step 1: pom + yml + config records.**
- [ ] **Step 2: ES client bean + ensureIndex.** Mapping JSON read from src/main/resources/indices/products_v1.json (multi-field per spec D2).
- [ ] **Step 3: failing test** IndicesIT (Testcontainers elasticsearch 8.x — single-node, security disabled): ensureIndex creates; second call is no-op (already exists).
- [ ] **Step 4: run** GREEN.
- [ ] **Step 5: commit** feat(search): bootstrap — ES client, index ensure, config

### Task 2: error codes + i18n

**Files:**
- Modify: utils/common-core/.../exception/ErrorCode.java — verified tail: flip MED-12006 ; → , append SRC-13001..13004 with SRC-13004 as ; terminator. Anchor verify by running `grep -n MED-12006 utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java`.
- Modify: utils/common-spring/src/main/resources/messages/messages_en.properties + messages_vi.properties (4 keys search.query_invalid ... reindex.not_found).

- [ ] **Step 1:** append 4 codes + 8 i18n keys.
- [ ] **Step 2: run** ./mvnw -pl utils/common-core,utils/common-spring compile → PASS.
- [ ] **Step 3: commit** feat(search): error codes SRC-13xxx + i18n keys

### Task 3: SearchService + response DTOs

**Files:**
- Create: dto/ProductHit.java (record: productId, title, score, snippet, avgRating, ratingCount, price, currency).
- Create: dto/SearchResponse.java (record: List<ProductHit> hits, long total, int page, int size, Map<String, List<FacetBucket>> facets).
- Create: dto/FacetBucket.java (record: String key, long count).
- Create: dto/SuggestResponse.java (record: List<String> suggestions).
- Create: service/SearchService.java (search(query, filters, pageable) builds ES query: multi_match on title_vi^2 + title_en^2 + description_vi + description_en; bool filter on categoryId + priceRange + status=ACTIVE; aggregations terms on categoryId, range on price; highlighter on title + description).
- Create: service/SuggestService.java (completion suggester via ESCompletionSuggester; falls back to prefix query on title.raw if completion not configured).
- Test: SearchServiceTest (Mockito ES client): query with q=phone, category filter, price range; verify query DSL; assert response mapping (score, snippet, facets).

- [ ] **Step 1: failing tests** (3+ cases).
- [ ] **Step 2: implement** ES query builder + response mapper.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(search): SearchService + SuggestService + DTOs

### Task 4: controllers (public + backoffice)

**Files:**
- Create: controller/PublicSearchController.java (separate file, @RequestMapping(ApiPaths.SEARCH) — package scan-level public permit; security carve-out pattern via common-security public-paths mechanism):
  - GET /products?q=&categoryId=&minPrice=&maxPrice=&page=&size= → ApiResponse<SearchResponse>
  - GET /suggest?q=&size= → ApiResponse<SuggestResponse>
  - GET /health → 200 / 503 based on ES ping
- Create: controller/BackofficeSearchController.java — @RequestMapping(ApiPaths.BACKOFFICE_SEARCH), class @PreAuthorize("hasRole('ADMIN')"):
  - POST /reindex → 202 + jobId
  - GET /reindex/{jobId} → ApiResponse<ReindexStatus>
- Create: dto/ReindexStatus.java (record: UUID jobId, String status, long totalProducts, long indexedSoFar, Instant startedAt, Instant finishedAt).
- Modify: utils/common-security SecurityConfig (or wherever public-paths is configured) — ADD /api/v1/search/** to public-paths list (payment/shipping/media webhook precedent).
- Test: PublicSearchControllerTest (@WebMvcTest with permitAll), BackofficeSearchControllerTest (ADMIN matrix; CUSTOMER 403).

- [ ] **Step 1: failing tests** (public 200 + 503; ADMIN reindex 202; CUSTOMER 403; health 503 when ES mocked down).
- [ ] **Step 2: implement** controllers + public-paths carve-out.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(search): public search + ADMIN reindex controllers

### Task 5: reindex service + ProductServiceClient

**Files:**
- Create: client/ProductServiceClient.java (mirrors EligibilityClient pattern: per-call Bearer token via new ServiceTokenProvider in search-service, PRODUCT_SERVICE_URL=${PRODUCT_SERVICE_URL:http://localhost:8086}, timeout 5000 ms; GET /api/v1/products?page=&size= via page-of-summary; loop until exhausted; fail-closed on non-2xx → throw BusinessException SRC-13002).
- Create: service/ReindexService.java (start(): scans products via client, batches 500, bulk-indexes via ElasticsearchClient.bulk(); tracks job state in ConcurrentHashMap<UUID, ReindexStatus>; status lifecycle: QUEUED → RUNNING → COMPLETED | FAILED).
- Create: service/ReindexJobRegistry.java (@Component ConcurrentHashMap<UUID, ReindexStatus> jobs + update(jobId, statusFn)).
- Test: ReindexServiceTest (Mockito ES + ProductClient): start() kicks job async; GET /reindex/{jobId} returns IN_PROGRESS then COMPLETED; ES failure mid-batch → status FAILED with partial count.

- [ ] **Step 1: failing tests** (3+ cases).
- [ ] **Step 2: implement.** Use @Async or CompletableFuture.supplyAsync for the actual reindex loop.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(search): ReindexService + ProductServiceClient outbound

### Task 6: product-event consumer (first consumer)

**Files:**
- Create: search-service/src/main/java/com/shop/searchservice/config/ProductListenerConfig.java (factory bean productListenerFactory, groupId shop.kafka.consumer.group-id.products: search-service-products, topics: shop.product.lifecycle.v1, key: StringDeserializer, value: StringDeserializer; @EnableKafka on this config class).
- Create: search-service/src/main/java/com/shop/searchservice/dto/ProductLifecycleEvent.java (record: eventId, eventType, occurredAt, productId, title, description, categoryId, status, avgRating, ratingCount, price, currency — flattened envelope).
- Create: search-service/src/main/java/com/shop/searchservice/kafka/SearchProductConsumer.java (extends BaseKafkaConsumer<String, ProductLifecycleEvent>; @KafkaListener(topics=shop.product.lifecycle.v1, containerFactory=productListenerFactory); handler per spec §3: created/updated → ES PUT _doc/{productId} with full doc from event; deleted → ES DELETE _doc/{productId}; unknown eventType → log + ack).
- Test: SearchProductConsumerTest (Mockito ES client): created → PUT called with event payload; updated → PUT (overwrite); deleted → DELETE; unknown eventType → ack no throw.

- [ ] **Step 1: failing tests** (4 cases).
- [ ] **Step 2: implement** consumer.
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(search): product-event consumer — index sync

### Task 7: rating-event consumer

**Files:**
- Create: search-service/src/main/java/com/shop/searchservice/config/RatingListenerConfig.java (factory bean ratingListenerFactory, groupId shop.kafka.consumer.group-id.ratings: search-service-ratings, topics: shop.rating.lifecycle.v1).
- Create: search-service/src/main/java/com/shop/searchservice/dto/RatingLifecycleEvent.java (record: eventId, eventType, occurredAt, ratingId, productId, avgRating, ratingCount — fields actually used by search; ignore rating-specific fields).
- Create: search-service/src/main/java/com/shop/searchservice/kafka/SearchRatingConsumer.java (extends BaseKafkaConsumer<String, RatingLifecycleEvent>; handler: ES POST /products_v1/_update/{productId} with painless script `ctx._source.avgRating = params.avg; ctx._source.ratingCount = params.count;`; unknown productId → log + skip; ES 404 on update → log + skip (product not yet indexed)).
- Test: SearchRatingConsumerTest (Mockito ES client): valid event → _update called with correct params; unknown productId → log no throw.

- [ ] **Step 1: failing tests** (2 cases).
- [ ] **Step 2: implement.**
- [ ] **Step 3: run** GREEN.
- [ ] **Step 4: commit** feat(search): rating-event consumer — partial update on avg/count

### Task 8: compose + cross-module verification

**Files:**
- Modify: docker-compose.yml — ADD elasticsearch service (image: docker.elastic.co/elasticsearch/elasticsearch:8.13.4, single-node, xpack.security.enabled=false, ES_JAVA_OPTS=-Xms512m -Xmx512m, volume es-data, healthcheck via curl http://localhost:9200/_cluster/health). ADD search-service stanza (port 8094, kafka env, ELASTICSEARCH_URL=http://elasticsearch:9200, depends_on elasticsearch healthy). NO DB envs.
- Verify: RoutesConfigTest already covers /api/v1/search/** via ServiceRoute.SEARCH.
- Test: E2E smoke checklist (compose file only).

- [ ] **Step 1:** docker compose config -q → exit 0.
- [ ] **Step 2:** rg '"clientId"' docker/keycloak/import/ecommerce-realm.json — confirm search-service client NOT in import → add ops note in spec §4.
- [ ] **Step 3:** full fleet compile: ./mvnw -T1C install -DskipTests -q; then ./mvnw -pl search-service test all green.
- [ ] **Step 4: commit** chore(compose): elasticsearch service + search-service stanza

### Task 9: final whole-branch review

- [ ] Dispatch reviewer subagent: whole-branch diff vs main; spec D1-D10 + §5/§6 audit; Kafka consumer → ES write → search hit end-to-end (via IT with Testcontainers ES); reindex round-trip; zero-regression (only search-service/* + ErrorCode/i18n/ApiPaths/common-security public-paths tails + compose + spec); security (PUBLIC path carve-out, ADMIN gate, fail-closed ProductServiceClient); consumer idempotency (PUT/DELETE/_update all replay-safe); ES schema validation. Fix rounds until APPROVED (or APPROVED-WITH-FINDINGS adjudicated).