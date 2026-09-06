# In-Depth Review: search-service and notification-service

- **Date:** 2026-09-02
- **Scope:** Complete inspection of all files in `search-service/src` (39 files) and `notification-service/src` (30 files). Excludes `media-service/`.
- **Baseline:** Fleet guidelines (`controller`, `ApiResponse`, `PageResponse`, `BusinessException` with `ErrorCode`, `common-kafka`, Spring Security filter chains, test slices, and integration tests).
- **Cross-Verification:** Inspected `utils/common-kafka` (`BaseKafkaConsumer`, `BaseKafkaListenerConfig`, `JsonKafkaSerializer`) and `utils/common-core` (`PageableConstant`, `AbstractMappedEntity`) to evaluate consumer and deserializer behaviors. Verified that real secrets are not tracked in git (only `.env.example` templates exist).

Executive Summary: **0 Critical, 0 High, 10 Medium, 15 Low** (25 findings total). Both modules demonstrate strong engineering maturity. Search-service provides rigorous contract testing and documented architecture rationale, while notification-service satisfies baseline patterns but exhibits edge-case parameter validation gaps that expose raw 500 errors.

---

# Part 1: Search-Service

## controller/SearchController.java

Includes clear Javadoc documenting the deliberate omission of `@PreAuthorize` (referencing fleet precedent P2-6), binds query strings via `@Valid @ModelAttribute`, and returns standard `ApiResponse<PageResponse<T>>` envelopes. No findings.

## controller/BackofficeSearchController.java

- [Low][pattern] `controller/BackofficeSearchController.java:24`: Uses class-level `@PreAuthorize("hasRole('ADMIN')")`. This is a deliberate, documented deviation: reindexing is an intensive operation restricted to administrators, and security filter chains authenticate without evaluating roles. Retained as an intentional pattern.

## client/ProductBackofficeClient.java

- [Medium][error-handling] `client/ProductBackofficeClient.java:70-73`: Javadoc commits that any failure maps to `SRH-12002` (503 Service Unavailable), but catch blocks catch only `RestClientException`. Other runtime exceptions thrown during execution escape unhandled: `ServiceTokenProvider.refreshToken()` throws `IllegalStateException` when Keycloak returns an empty body (`security/ServiceTokenProvider.java:79-81`), and misconfigured URLs throw `IllegalArgumentException`. These exceptions bypass error mapping and return raw 500 responses. Recommendation: Throw `BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED)` from `ServiceTokenProvider` or catch general exceptions around client invocations to map all errors to `SRH-12002`.

## kafka/ProductSearchConsumer.java

- [Medium][error-handling] `kafka/ProductSearchConsumer.java:51-53`: Employs an unconditional message acknowledgement posture where handler failures are logged and caught so offsets advance. When Elasticsearch is unavailable, all lifecycle events are dropped without monitoring alerts beyond standard error logs, leaving search indexes stale until manual reindexing runs. Recommendation: Introduce a `search_ingestion_failures_total` counter metric with alerting thresholds, and record event identifiers, partition numbers, and offsets in error log statements.

## kafka/SearchListenerConfig.java

Does not extend `BaseKafkaListenerConfig` and configures `StringDeserializer` directly to handle double-encoded payloads (`JsonKafkaSerializer` string tokens). This is a deliberate, documented architectural choice. No findings.

## kafka/ProductLifecycleEvent.java

Java record with 17 fields annotated with `@JsonIgnoreProperties(ignoreUnknown = true)`. Fully compliant. No findings.

## service/impls/ReindexServiceImpl.java

- [Medium][solid] `service/impls/ReindexServiceImpl.java:71-84`: Race condition between reindexing and live event consumption. During streaming and bulk indexing into `products-v{n+1}`, Kafka consumers continue writing to the old alias (`ProductSearchService.index` targets the alias). Documents updated by consumers after snapshot generation but before the alias swap lose their updates in the new index. Recommendation: Implement dual-writing during index construction or replay consumer offsets following the alias swap.
- [Medium][error-handling] `service/impls/ReindexServiceImpl.java:137`: In `nextIndexName()`, calling `Integer.parseInt(name.substring(...))` on non-numeric index names (e.g. `products-v2_old` matching `products-v*`) throws an unhandled `NumberFormatException`, returning a raw 500 error. Recommendation: Validate index names against regex pattern `products-v\d+` prior to parsing.

## service/impls/SearchQueryServiceImpl.java

- [Low][clean-code] `service/impls/SearchQueryServiceImpl.java:66`: Executing `Math.min(request.size(), SIZE_CAP)` is redundant because the controller already enforces `@Max(200)` in `SearchRequest`. Recommendation: Consolidate validation in the request DTO and remove the redundant service clamp.
- [Low][clean-code] `service/impls/SearchQueryServiceImpl.java:126-135` and `search/IndexProvisioner.java:72`: Monetary amounts are stored as `double` in Elasticsearch mappings and converted via `BigDecimal.doubleValue()`. Floating-point representations risk precision errors during range filtering. Recommendation: Model prices as `scaled_float` (scale 100) or store whole currency cents as `long`.

## dto/request/SearchRequest.java

- [Low][clean-code] `dto/request/SearchRequest.java:25-29`: Missing boundary validations: fields `minPrice`, `maxPrice`, and `minRating` accept negative values, and ratings exceed 5.0 without validation errors. Recommendation: Add `@PositiveOrZero` on price and rating fields, and enforce `@DecimalMax("5.0")` for ratings.

## config/ElasticsearchConfig.java

- [Low][error-handling] `config/ElasticsearchConfig.java:34-39`: Checks only that `username` is non-empty before retrieving `properties.password()`. Setting username without a password results in malformed credentials. Recommendation: Validate that username and password are provided together.
- [Low][clean-code] `config/ElasticsearchConfig.java:31-32`: Timeout values (3000ms connect, 10000ms socket) are hardcoded constants. Recommendation: Bind timeouts to `SearchProperties`.

## security/ServiceTokenProvider.java

See finding in `ProductBackofficeClient.java`. Caches access tokens with a 30-second refresh skew and synchronizes token acquisition without logging raw tokens. No separate findings.

## search/IndexProvisioner.java

Implements idempotent template, index, and alias creation with graceful failure degradation. No separate findings.

## application.yml (search-service)

- [Low][security] `resources/application.yml:27`: Hardcoded default secret `${SEARCH_SERVICE_CLIENT_SECRET:changeme}` in tracked configuration. Recommendation: Remove default secrets from configuration to enforce explicit environment variable injection.

## Testing Assessment (search-service)

- [Medium][testing] `SearchIndexProvisioningIT.java:34-35`: Test execution order dependency. Three integration test suites share application context without `@DirtiesContext`. `ReindexIT` deletes `products-v1` and moves aliases. If `ReindexIT` runs first in the test worker, subsequent index provisioning assertions fail. Recommendation: Add `@DirtiesContext` to `ReindexIT` or update assertions to avoid hardcoded alias expectations.
- [Low][testing] `ReindexIT.java:46-56`: Static `WireMockServer` instances are never explicitly stopped, relying on process termination. Recommendation: Add `@AfterAll` shutdown hooks.

## Files Without Findings (search-service)
`SearchServiceApplication.java`, property configuration classes, DTO records, `SearchMetrics.java`, and associated unit and integration tests.

---

# Part 2: Notification-Service

## controller/BackofficeNotificationController.java

- [Medium][error-handling] `controller/BackofficeNotificationController.java:33-35`: Pagination parameters `page` and `size` lack lower-bound validation. Supplying negative values passes through `Math.min` into `PageRequest.of(...)`, throwing an unhandled `IllegalArgumentException` that returns a raw 500 response instead of 400 Bad Request. Recommendation: Annotate parameters with `@PositiveOrZero` for page and `@Min(1) @Max(MAX_PAGE_SIZE)` for size.
- [Low][pattern] `controller/BackofficeNotificationController.java:24`: Applies class-level `@PreAuthorize("hasRole('ADMIN')")`, matching `BackofficeSearchController`. Retained as an intentional pattern.

## dto/OrderLifecycleEvent.java

- [Low][pattern] `dto/OrderLifecycleEvent.java:14-34`: Implemented as a mutable Lombok class rather than a Java record. Recommendation: Migrate DTO to a record to align with platform conventions.

## service/impls/NotificationServiceImpl.java

- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:58-60`: Catches all `DataIntegrityViolationException` instances under the assumption that conflicts originate from concurrent duplicate consumer processing. Non-duplicate constraint failures (such as missing mandatory `order_id` values or status check constraint violations) are dropped silently with inaccurate log messages. Recommendation: Inspect the underlying constraint cause and swallow only violations matching `uk_notification_event_id`, rethrowing other constraint errors.
- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:38`: Method `UUID.fromString(event.getEventId())` throws `IllegalArgumentException` on malformed identifiers. Because `BaseKafkaConsumer.processMessage` does not wrap listener invocations, the exception triggers 9 rapid retries before the record is dropped. Recommendation: Validate event and order identifiers at the start of `handle()` and discard malformed events gracefully.
- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:49,66`: Notification status is set to `SENT` before `sender.send()` executes. If the application terminates between database commit and physical message transmission, records remain marked `SENT` permanently without dispatch. Recommendation: Record notifications as `PENDING`, transitioning to `SENT` or `FAILED` only after message dispatch completes.
- [Low][documentation] `service/impls/NotificationServiceImpl.java:26-29`: Service methods lack documentation describing at-most-once delivery semantics and deduplication boundaries.

## service/NotificationWriter.java

- [Low][pattern] `service/NotificationWriter.java:12-14`: Located in `service/` without an interface pair, annotated with `@Repository` instead of `@Service`. Recommendation: Change annotation to `@Service` or document the specific rationale for repository semantics.

## repository/NotificationRepository.java

- [Low][dry] `repository/NotificationRepository.java:19`: Redundantly re-declares `Optional<Notification> findById(UUID id)` inherited from `JpaRepository`. Recommendation: Remove redundant declaration.

## service/sender/NotificationSenderConfig.java

- [Low][clean-code] `service/sender/NotificationSenderConfig.java:19`: Expression `orElseGet(LoggingNotificationSender::new)` creates a second unmanaged instance of `LoggingNotificationSender` outside the Spring context. Recommendation: Resolve the logging sender bean directly from the injected sender list.

## service/sender/SmtpNotificationSender.java

Notifications route strictly to `fallbackRecipient` (operations address), omitting recipient lookups. No personal contact information is processed. No findings.

## entity/Notification.java and Database Migrations

Inherits `AbstractMappedEntity` with auditing metadata, string enums, and check constraints for status and channel. No findings.

## application.yml (notification-service)

- [Low][security] `resources/application.yml:8`: Default database password `${POSTGRES_PASSWORD:admin}` and fallback recipient `${SMTP_FALLBACK_RECIPIENT:ops@example.com}` committed in configuration. Recommendation: Remove default passwords in production configuration profiles.

## Testing Assessment (notification-service)

- [Medium][testing] `NotificationBootstrapIT.java:55`: Assertion `assertThat(notificationRepository.count()).isZero()` assumes an empty database. If `NotificationFlowIT` executes earlier in the test run, un-truncated database state causes `BootstrapIT` to fail. Recommendation: Clear notification records in `@BeforeEach` or update assertions to verify schema existence rather than row counts.
- [Low][testing] `NotificationServiceImplTest.java`: Test coverage omits negative scenarios for malformed UUIDs and null order identifiers.

## Files Without Findings (notification-service)
`NotificationServiceApplication.java`, notification enums, response DTOs, listener configurations, sender implementations, and database migration scripts.

---

# Summary Findings Matrix

### Severity by Category

| Category | Pattern | Clean Code | DRY | SOLID | Error Handling | Security | Testing | Documentation | Total |
|---|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| Medium | 0 | 0 | 0 | 1 | 7 | 0 | 2 | 0 | **10** |
| Low | 3 | 5 | 1 | 0 | 1 | 2 | 2 | 1 | **15** |
| **Total** | **3** | **5** | **1** | **1** | **8** | **2** | **4** | **1** | **25** |

- Search-service: 5 Medium, 8 Low (13 findings).
- Notification-service: 5 Medium, 7 Low (12 findings).

### Platform Compliance Evaluation

**search-service:** Exceptional compliance. Implements clean layering, record DTOs, standard response envelopes, structured `SRH-*` domain error codes, filter-based security, and Micrometer metrics. Deliberate deviations are thoroughly documented in code comments. Test suites feature comprehensive integration tests running on real Elasticsearch and Kafka containers. Priority improvements focus on handling reindex update races and broadening error translation for non-REST exceptions.

**notification-service:** Compliant overall, but exhibits looser conventions than search-service. Employs layered structures, auditing entities, and sender strategies. Priority improvements include validating controller pagination parameters (preventing 500 errors on negative inputs), distinguishing constraint violation types, and recording notifications as `PENDING` until dispatch confirmation.
