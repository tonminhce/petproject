# Code Review Report: order-service

- **Scope:** Complete analysis of `/order-service/src` (main, test, and resources) in read-only mode. Excludes `media-service/`.
- **Reviewer:** Senior Software Engineer (Review Agent)
- **Date:** 2026-09-02
- **Files Inspected:** 90 files (63 main, 27 test).
- **Cross-Module Verification:** Verified dependencies against `utils/common-kafka` (`BaseKafkaConsumer`, `BaseKafkaListenerConfig`, `KafkaAutoConfiguration`), `utils/common-security` (`BaseSecurityConfig`, `JwtRolesConverter`, `AuthenticatedUser`), `utils/common-spring` (`ApiExceptionHandler`), and `utils/common-core` (`ErrorCode`, `PageableConstant`, `BusinessException`, `AbstractMappedEntity`).
- **Committed Secrets Check:** Executed `git ls-files` verification; only `.env.example` and `.env.prod.example` templates exist in source control, with no real secrets tracked.

Executive Assessment: This module demonstrates the highest implementation quality within this review scope. Key highlights include an explicit compensation saga for order creation, transactional outbox publishing, database-backed idempotency protection against request races, `@Version` optimistic locking for state transitions, and a comprehensive test suite (Testcontainers and WireMock integration tests verifying execution ordering, LIFO compensation, concurrent checkout races, and cache determinism). The primary remediation priorities involve fixing a Kafka consumer that swallows delivery exceptions and plugging a compensation gap when stock reservation fails with non-409 errors.

---

## Findings by File

### kafka/ShippingDeliveredConsumer.java

- **[High][error-handling] `kafka/ShippingDeliveredConsumer.java:24-30`**: Method `handleContained` catches all exceptions thrown by `handler.handle()` and logs them without rethrowing. Because `BaseKafkaConsumer.processMessage` does not receive an exception, the listener completes successfully and Spring Kafka commits the partition offset. Consequently, incoming `shipping.delivered.v1` events are permanently lost during transient database or connection pool failures. The listener lacks dead-letter queue routing and retries (`ErrorHandlingDeserializer` handles only deserialization errors, while fixed consumer group IDs and `auto-offset-reset: latest` prevent re-reading past events). Stalled orders remain stuck in `SHIPPED` status, breaking order completion, delivery reconciliation, and downstream settlement. Test `ShippingDeliveredConsumerTest.onMessage_handlerThrows_exceptionContainedNeverEscapesListener` formalizes this defect. Recommendation: Rethrow exceptions to allow Spring Kafka retries via `DefaultErrorHandler` and dead-letter topic routing, or increment failure metrics and reconcile stalled `SHIPPED` orders via scheduled background scans.

### client/InventoryServiceClient.java and service/impls/OrderServiceImpl.java

- **[Medium][error-handling] `client/InventoryServiceClient.java:55-65` and `service/impls/OrderServiceImpl.java:159-184`**: Compensation gap in the order creation saga. The reservation loop catches only `StockReservationFailedException` (originating from 404 or 409 status codes). If reservation call N fails with an unexpected HTTP 5xx error (unhandled `HttpServerErrorException`) or non-409 client error (mapped to `INTERNAL_SERVER_ERROR`), the exception escapes the catch block. Consequently, previously reserved items 1 through N-1 are never released, locking warehouse inventory until reservation expiration (~30 minutes). Because the local order transaction rolls back without writing an order record, background reconciliation schedulers cannot discover the orphaned reservations. Recommendation: Catch all runtime exceptions during reservation steps, execute `releaseAllReservations` for established reservations, and translate the error code appropriately.

### client/ProductServiceClient.java (relates to OrderServiceImpl and CartServiceImpl)

- **[Medium][error-handling] `client/ProductServiceClient.java:36-50`**: Annotation `@Cacheable("productPrice")` lacks a custom `CacheErrorHandler`. By default, Spring `SimpleCacheErrorHandler` rethrows cache access exceptions. If Redis becomes unavailable, all calls to `getProduct` throw exceptions, causing add-to-cart and order creation requests to fail with 500 errors even when product-service is operating normally. This turns the Redis cache into a hard runtime dependency in the critical path. Recommendation: Configure a `CacheErrorHandler` that logs cache errors and proceeds with direct service invocations on cache misses.
- **[Low][error-handling] `client/ProductServiceClient.java:48-49`**: Omits null checks on `resp` and `resp.data()`. If product-service returns a 200 OK response with an empty body or null data, calling `resp.data()` throws a `NullPointerException`. Note: `CartServiceImpl.java:54` contains a null check (`snapshot == null`), but `OrderServiceImpl` and `PricingServiceImpl` do not. Recommendation: Unify null checking in the client to throw `BusinessException(PRODUCT_NOT_FOUND)`.

### service/impls/OrderServiceImpl.java

- **[Medium][testing] `service/impls/OrderServiceImpl.java:263-289`**: Untested race condition between order cancellation and confirmation. Method `cancelOrder` invokes remote stock and promotion release calls before persisting `CANCELLED` status under `@Version` protection. If an external inventory or promotion service returns HTTP 200 for release calls on previously committed reservations (due to idempotent success semantics), an order can reach `CONFIRMED` status while warehouse stock has already been released. Current logic relies on downstream services rejecting release requests on committed reservations, but `ConfirmOrchestrationIT` verifies only concurrent confirmations. Recommendation: Add integration tests for concurrent cancel and confirm requests, and formalize contract specifications requiring downstream services to reject releases on committed reservations.
- **[Low][clean-code] `service/impls/OrderServiceImpl.java:349-374`**: Log message "Confirm commit failed for order {}" is duplicated across inner (line 352) and outer (line 372) catch blocks. Because the coordinator wraps runtime exceptions into `BusinessException` in the inner catch, the outer catch wrapper is unreachable for coordinator execution paths. Recommendation: Consolidate error handling so the inner catch logs and wraps exceptions while the outer block executes cleanup.
- **[Low][error-handling] `service/impls/OrderServiceImpl.java:152-153`**: Accesses `pricing.snapshots().get(item.getProductId()).title()` without validating that the snapshot exists in the pricing map. If pricing logic returns a map missing an item key, this call throws a `NullPointerException` after partial saga writes. Recommendation: Use `Objects.requireNonNull` and throw a structured `BusinessException` with order context.
- **[Low][clean-code] `service/impls/OrderServiceImpl.java:397-403`**: Method `transitionStatus` includes a `case CANCELLED` marked as unused alongside an unreachable default branch. Because callers invoke this method only for `SHIPPED` and `DELIVERED` transitions, the five-branch switch statement contains dead code. Recommendation: Simplify to direct conditional checks for the two active states.

### controller/OrderController.java

- **[Low][error-handling] `controller/OrderController.java:52-57, 63-70, 89-95`**: Query parameters `page` and `size` lack lower-bound validation. Supplying negative values causes `PageRequest.of(-1, ...)` or `Math.min(-5, MAX)` to throw `IllegalArgumentException`, resulting in 500 errors instead of 400 Bad Request. Recommendation: Clamp parameters with `Math.max(0, page)` and `Math.max(1, size)`.
- **[Low][dry] `controller/OrderController.java:99-101` and `controller/CartController.java:53-55`**: Helper method `private static UUID currentUserId()` is duplicated verbatim across both controllers, with an inline variant in `OrderStatusController`. Recommendation: Extract into a shared utility class.
- **[Low][error-handling] `controller/OrderStatusController.java:31-33`**: The order confirmation endpoint accepts an `Idempotency-Key` header but omits the 64-character length limit enforced in `OrderController.createOrder`. Supplying keys longer than 64 characters triggers database constraint violations, returning 409 Conflict with raw error messages. Recommendation: Apply consistent header length validation across all controllers.

### Controller Security Conventions

- **[Low][pattern] `controller/CartController.java:20`, `OrderController.java:27`, `OrderStatusController.java:23`**: Controllers use `@PreAuthorize` annotations, diverging from the baseline guideline that centralizes authorization in `BaseSecurityConfig` filter chains. This is a deliberate design choice: role checks (`hasRole('ADMIN')`, `hasAnyRole('SERVICE','ADMIN')`) cannot be fully expressed through public path declarations alone, and comments in `OrderController.java:33-36` explain why `hasRole('USER')` is avoided. Recommendation: Retain current annotations and document this pattern deviation in the module README. Verification confirms that `JwtRolesConverter` emits `ROLE_`-prefixed authorities correctly matching realm roles.

### client/PaymentServiceClient.java and service/impls/OrderServiceImpl.java

- **[Low][dry] `client/PaymentServiceClient.java:39,71-73` and `service/impls/OrderServiceImpl.java:336`**: The string literal `"CAPTURED"` appears in both files. The client filters results with `STATUS_CAPTURED.equals(status)`, and the caller repeats the check with `"CAPTURED".equals(payment.status())`. Recommendation: Reference a shared constant and eliminate the duplicate check.

### service/impls/IdempotencyServiceImpl.java

- **[Low][error-handling] `service/impls/IdempotencyServiceImpl.java:94-105`**: If a process crashes after `begin()` commits an in-flight row via `REQUIRES_NEW` but before `complete()` executes, the in-flight record remains locked for its full 24-hour TTL. Subsequent retry requests with the same idempotency key are rejected with 409 `ORDER_DUPLICATE_REQUEST` without opportunities to retry. Recommendation: Add a `started_at` timestamp and permit recovery of stale in-flight records when payload hashes match after a configurable timeout.

### service/OrderReconciliationScheduler.java

- **[Low][performance] `service/OrderReconciliationScheduler.java:91-94`**: Query `findByStatusAndCreatedAtBefore` loads all stale pending orders into memory every 5 minutes without pagination, polling item states sequentially. During extended outages, this query can return large result sets and issue N+1 HTTP calls. Recommendation: Paginate queries with `PageRequest` and process records in batches.
- **[Low][clean-code] `service/OrderReconciliationScheduler.java:51-53,112-113`**: Status strings `"COMMITTED"`, `"RELEASED"`, and `"EXPIRED"` from `ReservationStateResponse.status()` are matched against string literals without shared constants. Renaming states in upstream services can cause silent transition failures. Recommendation: Share common status enums or define explicit constants with Javadoc references.

### service/impls/PricingServiceImpl.java

- **[Low][documentation] `service/impls/PricingServiceImpl.java:88-90`**: Invocations of `new TaxCalculateRequest(null, null, null, taxableAmount)` pass null values for tax class, country, and postal code with a comment reading "defer". When `TAX_SERVICE_ENABLED=true` is toggled on, tax calculations lack location context, producing incorrect order totals. Recommendation: Validate that tax-service handles null parameters gracefully or reject requests missing country information when tax calculation is enabled.

### service/impls/OrderEventPublisherImpl.java

- **[Low][clean-code] `service/impls/OrderEventPublisherImpl.java:3`**: Unused import of `KafkaMessagePublisher`.

### service/OrderMetrics.java

- **[Low][documentation] `service/OrderMetrics.java:56-58` and `OrderOutboxRelay.java:47-48`**: Metric `order.events.published` increments when outbox records are saved to the database (`OrderEventPublisherImpl.save`), not when Kafka publication actually succeeds. Similarly, `order.outbox.pending.count` reflects only the current batch size (up to 100) rather than the total pending count. Recommendation: Rename metrics to `order.outbox.persisted` and `order.outbox.batch.pending` to avoid misleading monitoring dashboards.

### config/RestClientConfig.java

- **[Low][performance] `config/RestClientConfig.java:53-56`**: Uses `SimpleClientHttpRequestFactory`, opening a new socket connection per outbound HTTP request without connection pooling. In high-throughput saga operations (multiple reservations, commits, and state checks), this creates significant socket allocation overhead. Recommendation: Switch to `JdkClientHttpRequestFactory` to take advantage of HTTP client pooling and HTTP/2 multiplexing in Java 25.

### resources/application.yml

- **[Low][security] `resources/application.yml:7-8,64`**: Static default credentials exist in configuration: `${POSTGRES_PASSWORD:admin}` and `${ORDER_SERVICE_CLIENT_SECRET:changeme}`. While invalid client secrets fail closed (returning 401 against real Keycloak instances), deploying with default values exposes service accounts. Recommendation: Remove default secrets from production configuration profiles.
- Positive Observations: Configures empty public paths (`public-paths: []`), `ddl-auto: validate`, and Liquibase changelogs with complete indexes (partial unique indexes on active carts, partial indexes on pending outbox events, and composite indexes on `idx_orders_status_created`).

### service/impls/CartServiceImpl.java

- **[Low][error-handling] `service/impls/CartServiceImpl.java:56-79`**: Method `addItem` does not handle race conditions on duplicate inserts. Two concurrent requests adding the same new product can both observe an empty result from `findByCartIdAndProductId`. Both execute inserts, causing the unique index on `(cart_id, product_id)` to fail the second request with a raw `DataIntegrityViolationException`. In the same class, `getOrCreateCart` handles this exact race condition by catching conflicts and re-fetching the winning cart. Recommendation: Adopt the same catch-and-retry approach in `addItem`.

### Testing Assessment

- **[Medium][testing] `service/impls/OrderOutboxRelay.java`**: The outbox relay lacks dedicated unit tests covering head-of-line blocking on initial errors (preserving aggregate ordering), retry incrementing, transitioning to `FAILED` after exceeding `max-retries`, and execution timer metrics. Recommendation: Add unit tests verifying these operational branches using a mock publisher.
- **[Low][testing] `controller/OrderControllerTest.java`**: Endpoint `GET /api/v1/orders` is annotated with `hasRole('ADMIN')` but lacks role-matrix test cases (verifying standard users receive 403 Forbidden). Recommendation: Add negative authorization test coverage.
- General Evaluation: Test coverage is outstanding. Integration tests verify commit ordering with WireMock journals and delays, LIFO compensation handling, idempotency replay mechanisms, multi-threaded concurrency, and cache round-trip serialization.

---

## Files Without Findings
`mapper/CartMapper.java`, `mapper/OrderMapper.java`, `entity/Cart.java`, `entity/CartItem.java`, `entity/Order.java`, `entity/OrderItem.java`, `entity/IdempotencyKey.java`, `entity/OutboxEvent.java`, `constant/OrderStatus.java`, `exception/StockReservationFailedException.java`, all repositories, service interfaces, database changelogs, and associated unit and integration test suites.

---

## Summary Evaluation Matrix

| Severity | Error Handling | Security | Performance | Testing | Clean Code | DRY | Pattern | Documentation | Total |
|---|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **1** |
| Medium | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | **4** |
| Low | 5 | 1 | 2 | 1 | 3 | 2 | 1 | 2 | **17** |
| **Total** | **8** | **1** | **2** | **3** | **3** | **2** | **1** | **2** | **22** |

## Pattern Compliance and Code Quality

**Pattern Compliance: High (~9/10).** Follows standard layered architecture conventions (controllers, DTOs, entities, mappers, repositories, services, Kafka publishers, REST clients, configuration). Endpoints return standard `ApiResponse<T>` and `PageResponse<T>` envelopes, exceptions map through `BusinessException` and `ErrorCode`, pagination enforces `PageableConstant.MAX_PAGE_SIZE`, and data access is scoped to authenticated user identities to prevent IDOR vulnerabilities. The single notable deviation involves using `@PreAuthorize` at the controller layer; this is a deliberate and documented architectural choice that requires no modification.

**Code Quality: Strong.** The codebase demonstrates sophisticated engineering practices: deterministic saga compensation ordering (sorted by product ID), transactional outbox relay preserving per-aggregate event order, ledger-style idempotency handling via `REQUIRES_NEW` transactions, `@Version` locking against status transition races, and comprehensive integration testing. Key remediation targets focus on rethrowing Kafka consumer exceptions (High), broadening saga compensation error handling beyond 409 conflicts (Medium), and decoupling Redis cache availability from checkout execution (Medium).
