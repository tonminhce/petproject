# Code Review Report: inventory-service and rating-service

- **Review Mode:** Read-only inspection without modifying source code files.
- **Scope:** Complete analysis of `inventory-service/src` (41 files) and `rating-service/src` (39 files), covering production Java sources, tests, and resources. Excludes `media-service/`.
- **Total Files Inspected:** 80 files reviewed.
- **Baseline Standards:** Fleet guidelines (layered controllers, DTOs, JPA entities, MapStruct mappers, repositories, services with implementations, standard `ApiResponse` and `PageResponse` envelopes, structured `BusinessException` with `ErrorCode`, transactional outbox for Kafka events, `AuthenticatedUser.requireCurrent()`, bounded pagination via `PageableConstant.MAX_PAGE_SIZE`, and Spring Security filter chains with configured public paths).

Executive Summary: **0 Critical, 0 High** findings. Both modules demonstrate solid compliance with fleet baseline standards (transactional outbox publishing, optimistic locking with retries, fail-closed eligibility checks, and query-level IDOR protections). Identified risks center on edge-case data integrity in inventory management (negative stock via administrative updates, delete and reserve race conditions, incomplete retention cleanup) and network resilience gaps in rating-service outbound calls (missing token client timeouts, unhandled exceptions in fail-closed seams).

---

# 1. INVENTORY-SERVICE

## service/impls/InventoryServiceImpl.java

- **[Medium][solid] `service/impls/InventoryServiceImpl.java:88`**: Method `update()` does not enforce `availableQuantity >= reservedQuantity`. Administrators can reduce available stock below the currently reserved quantity. Subsequent calls to `commit()` (lines 165-166) subtract from available stock, driving `available_quantity` into negative values. The database schema in `changelog-001-initial-schema.yaml` provides only a default value of 0 without check constraints (`CHECK (available_quantity >= 0)`). Recommendation: Validate `request.availableQuantity() >= existing.getReservedQuantity()` during update requests and add database check constraints in Liquibase.

- **[Medium][error-handling] `service/impls/InventoryServiceImpl.java:106`**: Deletion exhibits a time-of-check to time-of-use race condition: `countByProductIdAndStatusIn` (lines 110-111) checks for active reservations before calling `delete(existing)` without locking rows. A concurrent reservation request can insert a reservation immediately after the count check returns zero, creating orphaned reservations that cause downstream order commits to fail with 404 `INVENTORY_NOT_FOUND`. Schema migration `changelog-001` also lacks a foreign key constraint linking `reservations.product_id` to inventory records. Recommendation: Enforce foreign keys and execute deletions via conditional delete queries.

- **[Medium][logging] `service/impls/InventoryServiceImpl.java:152-239`**: Audit logs do not record modified stock quantities during inventory decrements. The `@Audited` annotation on controller endpoints records actions, resource identifiers, and actors, but omits specific quantities. The service layer does not log commit, release, or release-committed actions outside idempotent no-op paths. Recommendation: Include adjusted quantities and previous statuses in structured audit logs.

- **[Low][solid] `service/impls/InventoryServiceImpl.java:266-284`**: Method `releaseExpiredReservations()` decrements `reservedQuantity` without a `Math.max(0, ...)` boundary guard, whereas `ReservationCleanupScheduler.java:85` applies this guard. If data anomalies cause reserved stock to fall below the expired sum, this method drives `reservedQuantity` negative while the scheduler clamps it to zero. Recommendation: Standardize on consistent boundary guards.

## service/impls/ReservationServiceImpl.java

- **[Medium][dry] `service/impls/ReservationServiceImpl.java:31-91`**: Four retry wrapper methods (`reserveWithRetry`, `commitWithRetry`, `releaseWithRetry`, `releaseCommittedWithRetry`) duplicate identical backoff loops and exception translation (`OptimisticLockingFailureException` to `INVENTORY_VERSION_CONFLICT`). Recommendation: Consolidate these implementations into a shared retry utility method.

- **[Low][error-handling] `service/impls/ReservationServiceImpl.java:38,53,69,85`**: Methods catch only `OptimisticLockingFailureException`. Under heavy write concurrency, PostgreSQL lock timeouts and deadlocks throw `CannotAcquireLockException` or `PessimisticLockException`, escaping unhandled as raw 500 errors. Recommendation: Include `CannotAcquireLockException` in the retryable exception clause.

## service/ReservationCleanupScheduler.java

- **[Medium][documentation] `service/ReservationCleanupScheduler.java:74`**: The comment stating "Batches already flushed stay committed; the next cycle continues" contradicts the implementation. The `@Transactional` annotation on `releaseAllExpiredReservations` (line 51) wraps the entire batch loop. Any failure in later batches rolls back all changes, including previously flushed batches. Recommendation: Align the comment with transaction behavior or move `@Transactional` to individual batch executions using `REQUIRES_NEW`.

- **[Medium][performance] `service/ReservationCleanupScheduler.java:100-110`**: Retention cleanup purges only records with `EXPIRED` status. Terminal statuses `RELEASED` and `COMMITTED` are never purged, causing the `reservations` table to grow indefinitely (~1 row per completed order). This contradicts the Javadoc on `Reservation` claiming terminal records are purged after 30 days. Recommendation: Implement scheduled deletion for `RELEASED` and `COMMITTED` records past the retention cutoff.

## controller/InventoryController.java

- **[Low][pattern] `controller/InventoryController.java:42-111`**: Uses `@PreAuthorize` across all endpoints, which diverges from the baseline recommendation to centralize authorization at the gateway. While this deviation is deliberate and documented in `application.yml:66-71`, it introduces inconsistencies with other services. Additionally, `isAuthenticated()` at lines 42 and 52 is redundant with the filter chain configuration. Recommendation: Establish consistent authorization conventions across services.

- **[Low][clean-code] `controller/InventoryController.java:105-107`**: Method `releaseCommitted` returns `ApiResponse.ok(null)` with redundant null data, while `reserve` (lines 78-84) wraps HTTP 201 responses in an `ok(...)` envelope rather than `created(...)`. Recommendation: Use semantic status helpers and avoid returning null payloads.

- **[Low][logging] `controller/InventoryController.java:58-72`**: Endpoints `create`, `update`, and `delete` lack `@Audited` annotations, whereas reservation endpoints include auditing. Recommendation: Add auditing to administrative mutations, particularly deletions.

- **[Low][error-handling] `controller/InventoryController.java:45-48`**: Query parameter `page` lacks lower-bound validation. Supplying `?page=-1` causes `PageRequest.of` to throw `IllegalArgumentException`, returning 500 instead of 400 Bad Request. Recommendation: Enforce `page = Math.max(0, page)` or add parameter validation.

## service/impls/TransactionalInventoryEventPublisher.java

- **[Low][dry] `service/impls/TransactionalInventoryEventPublisher.java:46-89`**: Methods `publishReserved`, `publishCommitted`, and `publishReleased` duplicate payload map construction for product identifiers, reservation identifiers, quantities, and order references. Recommendation: Extract map construction into a shared helper method.

## mapper/InventoryMapper.java

- **[Low][clean-code] `mapper/InventoryMapper.java:29-35`**: Method `toEntity()` invokes `ModelMapper.map` and then manually reassigns the mapped fields (`id`, `availableQuantity`, `reservedQuantity`). In `partialUpdate` (lines 37-42), null checks are redundant because request fields are annotated with `@NotNull`. Recommendation: Replace ModelMapper with direct assignment in this class.

## service/InventoryOutboxRelay.java (and RatingOutboxRelay)

- **[Low][error-handling] `service/InventoryOutboxRelay.java:58`**: The outbox relay does not lock rows during polling (`SELECT ... FOR UPDATE SKIP LOCKED` is omitted). When running multiple service instances, concurrent relays can select and publish the same pending event twice. Recommendation: Document single-instance operational assumptions or introduce row-level claim locking. Additionally, `InventoryMetrics.setPendingOutboxCount(pending.size())` records only the current batch size (up to 100) rather than the total pending count, making the metric misleading.

## main/resources/application.yml

- **[Low][security] `main/resources/application.yml:16-17`**: Default configuration provides fallback credentials `${POSTGRES_PASSWORD:admin}` and username `admin` in tracked source control. Recommendation: Remove static defaults from configuration files to enforce environment variable injection in non-development deployments.

## Test Suite Findings (inventory-service)

- **[Low][testing] `InventoryControllerTest.java:97,104`**: Tests `commit_returns200` and `release_returns200` assert only envelope status without verifying underlying service interactions. Disabling security filters via `addFilters = false` (line 35) bypasses authorization validation. Recommendation: Verify service interactions and add negative authorization test cases (verifying standard users receive 403 on administrative endpoints).

- **[Low][testing]**: The module lacks integration tests verifying concurrent reservations and commits on the same product. While unit tests cover retry loops and optimistic locking independently, end-to-end multi-threaded stock deduction remains unverified. Recommendation: Add a multi-threaded integration test verifying concurrent stock operations.

## Files Without Findings (inventory-service)
`InventoryServiceApplication.java`, `config/CacheConfig.java`, `constant/ReservationStatus.java`, `dto/request/InventoryUpsertRequest.java`, `dto/request/ReserveRequest.java`, `dto/response/InventoryResponse.java`, `dto/response/ReservationResponse.java`, `entity/Inventory.java`, `entity/OutboxEvent.java`, `entity/Reservation.java`, `repository/InventoryRepository.java`, `repository/OutboxEventRepository.java`, `service/InventoryCacheService.java`, `service/InventoryEventPublisher.java`, `service/InventoryService.java`, `service/ReservationService.java`, `service/OutboxRetentionScheduler.java`, `db/changelog/db.changelog-master.yaml`, and associated unit and integration test suites.

---

# 2. RATING-SERVICE

## service/impls/RatingServiceImpl.java

- **[Medium][performance] `service/impls/RatingServiceImpl.java:35-64`**: Method `submit()` is annotated with `@Transactional` but issues synchronous HTTP calls to order-service (`eligibilityClient.isEligible`, line 44) inside the transaction. Database connections remain held during network I/O (up to the 3-second timeout, or longer under retries). Under high load, connection pools can be exhausted. Recommendation: Perform eligibility checks before beginning the database transaction.

- **[Medium][error-handling] `service/impls/RatingServiceImpl.java:37-39`**: Concurrent rating submissions for the same user and product can pass the initial check `findByUserIdAndProductId...` simultaneously. The second submission violates the partial unique index `uk_rating_user_product_live`, throwing an unhandled `DataIntegrityViolationException` that returns 500 instead of 409 `RTG-11005`. Recommendation: Catch constraint violations in `submit()` and map them to `RATING_ALREADY_EXISTS`.

- **[Low][pattern] `service/impls/RatingServiceImpl.java:67-73`**: Page size bounding via `PageableConstant.MAX_PAGE_SIZE` is enforced inside the service layer rather than in controller endpoints, diverging from standard patterns. Recommendation: Move pagination bounds enforcement to the controller layer.

## config/RestClientConfig.java

- **[Medium][error-handling] `config/RestClientConfig.java:53-56`**: The `restClientBuilder` bean used by `ServiceTokenProvider` configures no connect or read timeouts, unlike `orderRestClient` which sets a 3-second timeout. Default HTTP request factories block indefinitely if Keycloak hangs, causing synchronized token refresh calls to stall and blocking rating verifications across the application. Recommendation: Set connection and read timeouts on the token client.

## eligibility/EligibilityClient.java

- **[Medium][error-handling] `eligibility/EligibilityClient.java:57,64`**: The documented fail-closed contract stating "ANY failure maps to false" fails when `ServiceTokenProvider.getToken()` throws `IllegalStateException` or null pointer exceptions instead of `RestClientException`. These exceptions escape unhandled, causing `submit()` to return 500 rather than 403 `RTG-11001`. Test `submit_clientFailureSeam_propagatesWithoutSave` formalizes this 500 response. Recommendation: Broaden exception handling to catch provider runtime exceptions and log diagnostics while adhering to fail-closed behavior.

## outbox/

- **[Medium][performance] `outbox/OutboxEventRepository.java:19`**: Query method `deleteByStatusAndSentAtBefore` is never called. Rating-service lacks an outbox retention scheduler, allowing the `outbox_events` table to grow indefinitely. Inventory-service implements `OutboxRetentionScheduler` to purge sent events older than 7 days. Recommendation: Introduce an outbox retention scheduler matching inventory-service.

## main/resources/application.yml

- **[Low][security] `main/resources/application.yml:43`**: Client secret configuration defaults to `${RATING_SERVICE_CLIENT_SECRET:changeme}` in tracked source control. Recommendation: Remove default secrets to enforce environment variable injection.

- **[Low][pattern] `main/resources/application.yml:22-23`**: Producer configuration specifies `bootstrap-servers` without setting `acks`. Inventory-service explicitly configures `acks: all` with comments detailing data loss prevention. Recommendation: Explicitly set `acks: all` to ensure durability.

## dto/response/RatingResponse.java

- **[Low][pattern] `dto/response/RatingResponse.java:30-41`**: Uses a static factory method `from()` on the DTO record rather than a dedicated mapper component in the `mapper/` package. Recommendation: Move mapping logic to a dedicated mapper component or document this deviation.

## security/ServiceTokenProvider.java

- **[Low][testing] `security/ServiceTokenProvider.java`**: The token provider has no automated unit tests verifying token caching, refresh timing, or single-flight concurrency. Recommendation: Add test cases verifying token reuse and refresh logic.

## controller/StorefrontRatingController.java

- **[Low][logging] `controller/StorefrontRatingController.java:42-58`**: User write actions (`submit` and `edit`) lack `@Audited` annotations and produce no service-level logs. Only administrative moderation actions are audited. Recommendation: Add `@Audited` logging to rating submissions.

- **[Low][error-handling] `controller/StorefrontRatingController.java:214-217`**: Parameter `page` lacks lower-bound validation, causing negative values to throw `IllegalArgumentException` and return 500 errors. Recommendation: Validate or clamp page parameters.

## test/support/AbstractIntegrationTest.java (rating-service)

- **[Low][testing] `test/support/AbstractIntegrationTest.java:22-28`**: Docker containers are started inside a static initializer block without shutdown hooks or explanatory documentation. Test containers remain running after test execution. Inventory-service includes proper shutdown hooks. Recommendation: Add container shutdown hooks matching inventory-service.

## Files Without Findings (rating-service)
`RatingServiceApplication.java`, `config/RatingClientProperties.java`, `constant/RatingAction.java`, `controller/BackofficeRatingController.java`, `dto/request/RatingEditRequest.java`, `dto/request/RatingHideRequest.java`, `dto/request/RatingSubmitRequest.java`, `entity/Rating.java`, `metrics/RatingMetrics.java`, `outbox/OutboxEvent.java`, `outbox/RatingOutboxRelay.java`, `repository/RatingRepository.java`, `service/RatingService.java`, `service/RatingEventService.java`, database migrations, and associated test suites.

---

# SUMMARY

## Findings Matrix by Severity and Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Architecture and SOLID | 0 | 0 | 1 | 1 | 2 |
| Error Handling | 0 | 0 | 4 | 4 | 8 |
| Logging | 0 | 0 | 1 | 2 | 3 |
| DRY | 0 | 0 | 1 | 1 | 2 |
| Documentation | 0 | 0 | 1 | 0 | 1 |
| Performance | 0 | 0 | 3 | 0 | 3 |
| Pattern Compliance | 0 | 0 | 0 | 4 | 4 |
| Clean Code | 0 | 0 | 0 | 2 | 2 |
| Security | 0 | 0 | 0 | 2 | 2 |
| Testing | 0 | 0 | 0 | 4 | 4 |
| **Total** | **0** | **0** | **11** | **20** | **31** |

## Platform Compliance Evaluation

**inventory-service (41 files):** Demonstrates strong pattern adherence with clean layering, documented transactional outbox relays, and optimistic locking with retry wrappers. Primary improvements involve guarding against negative stock balances on administrative updates, resolving delete race conditions, expanding retention schedules to terminal statuses, and aligning transaction comments with actual code behavior.

**rating-service (39 files):** Clean, concise codebase with comprehensive web and service test slices covering security matrix rules and constraint mappings. IDOR vulnerabilities are prevented by resolving user identities directly from JWT tokens. Priorities for improvement include adding connection timeouts to identity clients, ensuring fail-closed checks catch runtime exceptions, separating remote HTTP calls from active database transactions, and implementing outbox retention cleanup.

**Cross-Module Observations:** Both microservices share consistent error codes, response envelopes, and outbox patterns. Aligning conventions around `@PreAuthorize` usage, outbox retention schedules, and pagination validation will ensure uniform behavior across the fleet.
