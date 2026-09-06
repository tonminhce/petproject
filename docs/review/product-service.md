# In-Depth Review: product-service

- **Date:** 2026-09-02
- **Scope:** Complete inspection of `/product-service/src` (83 files: 62 main, 21 test; full read of code, tests, and resources).
- **Review Mode:** Read-only analysis without modifying code files. Cross-verified against `utils/common-core`, `utils/common-security`, `utils/common-kafka`, `common-spring/application.yml`, and the rating-service publisher (excluding `media-service/`).
- **Baseline:** Layered architecture (`controller`, `dto`, `entity`, `mapper`, `repository`, `service` with `impls`), `ApiResponse`, `BusinessException.of(ErrorCode.X)`, `PageableConstant.MAX_PAGE_SIZE`, JWT authentication with public path declarations, and transactional outbox event publication via `KafkaMessagePublisher`.

Executive Summary: The module demonstrates high implementation maturity: transactional outbox patterns, wire-shape integration tests, per-entry cache TTLs, auditing metadata, and comprehensive backoffice test matrices. However, two high-impact defects were discovered: **one Critical deserialization defect that breaks rating synchronization (due to wire-format mismatches on double-encoded strings), and one High defect allowing cyclical parent categories (causing infinite loops and 500 errors on the category tree endpoint)**.

---

## Findings by File

### kafka/RatingLifecycleListenerConfig.java and kafka/ProductRatingConsumer.java

- **[Critical][error-handling] `kafka/RatingLifecycleListenerConfig.java:12-22` and `ProductRatingConsumer.java:18`**: Wire-format mismatch breaks rating synchronization. The rating-service outbox writes payload data as pre-serialized JSON strings, which `KafkaMessagePublisher` and `JsonKafkaSerializer` string-encode a second time (double-encoding, proven in `MediaDeletedConsumerIT.wireShapeIsDoubleEncodedOnTheRealBroker`). `RatingLifecycleListenerConfig` extends `BaseKafkaListenerConfig<String, RatingLifecycleEvent>`, binding using Spring Kafka typed `JsonDeserializer` (which lacks string unwrapping logic). Consequently, every message on `shop.rating.lifecycle.v1` fails deserialization. Fields `avgRating` and `ratingCount` are never updated on products. Meanwhile, the rating-service outbox marks events as `SENT`, dropping updates permanently without retries. Comments in `MediaLifecycleListenerConfig.java:14-24` already document this exact issue for media events. Recommendation: Replace typed `JsonDeserializer` with `StringDeserializer` in `ratingListenerFactory`, unwrap the payload manually in `ProductRatingConsumer` (mirroring `MediaDeletedConsumer.decode`), and add an integration test verifying wire format compatibility.

### Controller Pagination Limits

- **[Medium][pattern] `controller/ProductController.java:46` and `controller/BrandController.java:37`**: Storefront controllers omit page size caps using `PageableConstant.MAX_PAGE_SIZE`. While `BackofficeProductController:51` applies `Math.min(size, PageableConstant.MAX_PAGE_SIZE)`, storefront endpoints do not. Anonymous clients can request `GET /api/v1/products?size=1000000`, causing uncontrolled database query and memory allocation overhead. Recommendation: Apply consistent pagination capping using `Math.min(size, PageableConstant.MAX_PAGE_SIZE)` across all controllers.

### controller/ProductController.java, BrandController.java, CategoryController.java

- **[Medium][testing]**: Missing security test matrices for ADMIN-gated mutation endpoints. `ProductControllerTest` and `BrandControllerTest` configure `@AutoConfigureMockMvc(addFilters = false)`, disabling filter chains and method security. Tests omit assertions verifying that unauthenticated requests return 401 Unauthorized and standard users receive 403 Forbidden on POST, PUT, and DELETE routes. Recommendation: Add slice tests with filters enabled using mock JWTs to verify 401 and 403 responses on protected mutation routes.
- **[Low][clean-code] `ProductController.java:43-44`, `BrandController.java:35-36`**: Uses magic strings `"0"` and `"20"` for pagination defaults. Meanwhile, `BackofficeProductController.java:48` combines `"" + PageableConstant.DEFAULT_PAGE_NUMBER` with literal `"20"`, ignoring `PageableConstant.DEFAULT_PAGE_SIZE = 10`. Recommendation: Standardize default pagination constants across controllers.
- **[Low][clean-code]**: Unreleased products exposed on public storefronts. Method `GET /api/v1/products` does not filter out `DRAFT` or `DISCONTINUED` products for unauthenticated users. If storefront channels should only expose active catalog items, queries should filter on `status = ACTIVE`.

### service/CategoryServiceImpl.java

- **[High][clean-code] `CategoryServiceImpl.java:104-108`**: Missing parent cycle detection. The `update` method allows setting `parentId == id` (self-referencing parent) or creating two-node circular references (A -> B and B -> A). During category tree traversal in `findTree()`, circular hierarchies cause infinite recursion during Jackson JSON serialization, returning 500 errors on the public `GET /api/v1/categories/tree` endpoint. Recommendation: Enforce `parentId != id` and walk the ancestor chain to reject cycles before saving; add a visited set guard inside `findTree` as defense-in-depth.
- **[Medium][clean-code] `CategoryServiceImpl.java:114-123` and `BrandServiceImpl.java:82-91`**: Deletion operations omit dependency verification. Deleting a parent category leaves child records filtered by `@SQLRestriction("deleted = false")`, causing children to appear as root nodes or vanish from category trees. Similarly, deleting a brand linked to products causes `ProductMapper.toDetailResponse` to resolve brand references to null, stripping brand names from product responses and outbox payloads while search indexes retain stale data. Recommendation: Reject deletions when active child entities or linked products remain, or reassign foreign references explicitly.

### service/impls/ProductServiceImpl.java and Outbox Publishers

- **[Medium][error-handling] `TransactionalProductEventPublisher.java:93`**: Stale `updatedAt` timestamps in event payloads. In Spring Data JPA, `@LastModifiedDate` updates when the persistence context flushes at commit time. Because publishers execute inside the transaction prior to commit (`repo.save(existing)` is a no-op on managed entities without flush), `ProductUpdated` and `ProductDeleted` events contain outdated timestamps from previous modifications. Downstream consumers (such as search-service) receive stale event metadata. Recommendation: Call `saveAndFlush` before event publishing to ensure entities contain refreshed timestamps.
- **[Medium][error-handling] `ProductServiceImpl.java:179-183`**: Synchronous HTTP HEAD requests to media-service execute inside active write transactions (`assertMediaExists`). Holding open database connections during external network exchanges (with timeouts up to 3 seconds) exhausts connection pools during media latency spikes. Recommendation: Validate media existence prior to opening database transactions.
- **[Low][clean-code] `ProductServiceImpl.java:132-160` and `ProductMapper.partialUpdate`**: The update API provides no mechanism to explicitly clear `mediaId`, `categoryId`, or `brandId`. Supplying null values preserves existing assignments. Recommendation: Support explicit field clearing via JSON Patch or dedicated unset flags.
- **[Low][performance] `ProductServiceImpl.java:52-59`**: Method `findAll` executes unnecessary join fetches for categories and brands. While `ProductRepository.findAll(spec, pageable)` applies `@EntityGraph` for backoffice detail views, summary responses do not need these associations, resulting in redundant joins on storefront listings. Recommendation: Provide separate repository methods for summary and detail queries.
- **[Low][performance]**: Query `findBySlug` lacks negative caching when slugs do not exist, querying the database on every lookup attempt.

### service/impls/Transactional{Brand,Category,Product}EventPublisher.java

- **[Medium][dry]**: Three publisher classes share near-identical code structures: building `OutboxEvent` records, serializing payloads, setting status to `PENDING`, persisting records, and incrementing metrics. Recommendation: Extract shared publishing logic into an abstract base class or utility.

### service/OutboxRelay.java

- **[Low][error-handling] `OutboxRelay.java:48-84`**: Multiple service replicas execute outbox polling loops concurrently without distributed locking or leader election, allowing identical pending records to be published twice. Downstream consumers operate idempotently, mitigating immediate data corruption, but this remains an unmanaged at-least-once mechanism. Additionally, `metrics.setPendingOutboxCount(pending.size())` records only the current batch size (up to 100) rather than total pending records. Recommendation: Use count queries for pending gauges and document single-instance assumptions.

### Consumer Poison Posture (MediaDeletedConsumer, ProductRatingConsumer)

- **[Low][error-handling]**: Consumers catch all exceptions and log errors without routing failed records to dead-letter topics or incrementing alert metrics. Transient database errors during rating event consumption result in dropped rating updates. Recommendation: Increment failure counters (`product.consumer.handled.failure`) to enable monitoring and alerting.

### kafka/MediaDeletedConsumer.java

- No findings. Successfully implements raw string decoding, poison handling, and acknowledgement skipping, serving as a model for `ProductRatingConsumer`.

### client/MediaHeadClient.java

- No findings. Implements clear error handling (200, 404, or `MED-12006`), validated by unit tests in `MediaHeadClientTest`. Propagates correlation IDs and W3C traceparents correctly.

### security/ServiceTokenProvider.java

- No findings. Caches tokens with a 30-second refresh skew and synchronizes refresh calls against concurrency races.

### config/CacheConfig.java

- No findings. Documents immediate write consistency, custom serializers for records, and per-entry TTLs.

### application.yml

- **[Low][security] `application.yml:81`**: Default configuration provides fallback client secret `${PRODUCT_SERVICE_CLIENT_SECRET:changeme}` in tracked source control. Recommendation: Remove default secrets to enforce environment variable injection in production profiles.
- **[Low][clean-code]**: Outbox settings and timeout parameters (`timeout-ms: 3000`) use static literals rather than environment variable placeholders.

### Database Migrations

- **[Medium][performance] `changelog-004-media-reference.yaml:16`**: Column `media_id` lacks an index. Method `ProductRepository.findByMediaId` executes a sequential table scan on every incoming `MediaDeleted` event. Recommendation: Add an index on `products(media_id)` (optionally partial with `WHERE deleted = false`).
- Other changelogs are well structured, including partial unique indexes on active records and partial indexes for outbox polling.

---

## Files Without Findings
`ProductServiceApplication.java`, domain constants, filter specifications, request and response DTO records, entity classes, media consumers, cache configurations, repositories, and service test suites.

---

## Summary Findings Matrix

### Severity by Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Error Handling | 1 | 0 | 2 | 2 | 5 |
| Clean Code | 0 | 1 | 1 | 3 | 5 |
| Pattern Compliance | 0 | 0 | 1 | 0 | 1 |
| DRY | 0 | 0 | 1 | 0 | 1 |
| Performance | 0 | 0 | 1 | 2 | 3 |
| Testing | 0 | 0 | 1 | 0 | 1 |
| Security | 0 | 0 | 0 | 1 | 1 |
| **Total** | **1** | **1** | **7** | **8** | **17** |

## Architectural Assessment

- **Structure:** Strong adherence to platform guidelines: package naming, layered separation, `ApiResponse` and `PageResponse` envelopes, Java records for DTOs, domain exceptions with `ErrorCode`, Liquibase migrations with validation, and environment variable fallbacks.
- **Security Conventions:** Applies `@PreAuthorize` across backoffice endpoints with method security enabled via `@EnableMethodSecurity`. Slice tests verify role checks for backoffice controllers.
- **Key Remediation Priorities:** (1) Correct the rating consumer deserializer configuration to resolve rating synchronization failures (Critical); (2) Introduce parent cycle validation in category updates to prevent infinite recursion on tree endpoints (High); (3) Ensure entity timestamps are flushed before event publication; and (4) Add database index coverage on `media_id`.
