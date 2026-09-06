# Architectural Pattern Verification: Direct Source Inspection

> Objective: Line-by-line inspection of production source files against platform architecture rules to determine compliance and production readiness. This second verification pass was performed directly by the orchestrator.
> Baseline Pattern Checklist (compiled from `plan.md`, `docs/ARCHITECTURE.md`, user specifications, and the reference implementation in `favourite-service`):
> 1. Layering: controller -> service (interface) -> service/impls -> repository -> entity; request and response DTOs implemented as records.
> 2. Standard response envelopes (`ApiResponse<T>`, `PageResponse<T>`) and domain error translation (`BusinessException.of(ErrorCode.X)`).
> 3. Standardized path constants (`@RequestMapping(ApiPaths.*)`) without hardcoded path literals.
> 4. Bounded pagination via `Math.min(size, PageableConstant.MAX_PAGE_SIZE)`.
> 5. Storefront endpoints avoid `@PreAuthorize`; Backoffice endpoints apply class-level `@PreAuthorize("hasRole('ADMIN')")`.
> 6. Identity extraction via `AuthenticatedUser.requireCurrent().id()` (resolving Keycloak subject UUIDs).
> 7. Read-only transactions (`@Transactional(readOnly=true)`) for queries; soft deletion via `@SQLRestriction` with `@Modifying` updates.
> 8. Schema validation (`ddl-auto: validate`) backed by Liquibase changelogs (audit metadata, partial unique indexes filtering `deleted=false`, check constraints).
> 9. Environment variable fallbacks (`${ENV_VAR:default}`); manual or MapStruct object mapping; transactional outbox pattern for Kafka event publication.

---

## favourite-service (9 Java classes, YAML config, 2 changelogs): Full Read
**Verdict: Full compliance across all checklist items.** The controller references `ApiPaths.FAVOURITES`, documents the deliberate omission of `@PreAuthorize`, enforces pagination caps (line 43), applies soft deletion with `@SQLRestriction` and audit fields, defines partial unique indexes via raw SQL, enables `ddl-auto: validate`, and documents configuration properties. This service serves as the benchmark reference for the fleet.

---

## tax-service (21 Java classes, YAML config, changelogs): Full Read
Verdict: ~90% compliance with minor deviations:
1. **`TaxRateRequest.java:13` and `TaxCalculateRequest.java:12`**: `@Pattern(regexp="^[A-Z]{2}$")` lacks an accompanying `@NotNull`. Null country values pass bean validation and collide with the database column definition (`char(2) NOT NULL`), returning 500 instead of 400 Bad Request.
2. `TaxClassServiceImpl.java:31`: Reports duplicate class names using `ErrorCode.DUPLICATE_TAX_RATE` instead of a tax-class-specific error code.
3. `TaxCalculationController.java:23`: Internal endpoint applies `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")`. Because method security is active, this annotation correctly restricts access to trusted tokens.
4. **Missing `mapper/` package**: Methods convert entities via private `toResponse()` helpers inside implementation classes (`TaxRateServiceImpl:95`, `TaxClassServiceImpl:80`). While deviating from favourite-service, manual mapping for 3 to 5 fields is acceptable.
5. Changelog Quality: Strong. Implements check constraints, partial unique indexes, and foreign keys (`tax_rates` to `tax_classes`).
6. Configuration: Omits explicit `shop.security.public-paths: []` configuration, relying instead on shared defaults.

---

## rating-service (21 Java classes, YAML config, changelogs): Near-Complete Read
Verdict: ~85% compliance. Code inspection confirms previously reported findings:
1. **Double-Encoding Pipeline Confirmed (C4):** `RatingEventService.java:87` serializes outbox payloads via `objectMapper.writeValueAsString(map)` (JSON String). Next, `RatingOutboxRelay.java:37-39` passes this string to `KafkaMessagePublisher`, which invokes `JsonKafkaSerializer` and encodes the string a second time. Consumers on product-service bind a typed `JsonDeserializer`, failing deserialization on the string token. As a result, `avgRating` and `ratingCount` are never updated on products.
2. `ServiceTokenProvider.java:82-84`: Throws `IllegalStateException` on empty responses. `EligibilityClient.java:64` catches only `RestClientException`, letting the runtime exception escape as an unhandled 500 error instead of failing closed with 403 `RTG-11001`.
3. `RestClientConfig.java:52-56`: The `restClientBuilder` bean used for token retrieval lacks connect and read timeouts, unlike `orderRestClient`.
4. `RatingServiceImpl.submit()` (lines 34-47): Executes synchronous HTTP eligibility checks inside `@Transactional`, holding open database connections during network I/O.
5. `RatingOutboxRelay.java:54`: Head-of-line break statement lacks an explanatory comment.
6. `RatingRepository.findAggregateByProductId`: Returns `List<Object[]>` with index lookups. While rudimentary, it handles Hibernate numeric normalization properly (lines 101-103).
7. Pagination: Storefront methods bound page size at the service layer, but negative `page` or `size` parameters trigger unhandled `IllegalArgumentException` in `PageRequest.of`.
8. Security Conventions: `BackofficeRatingController` enforces class-level `@PreAuthorize("hasRole('ADMIN')")` with `@Audited` logging, aligning with fleet backoffice conventions. The storefront controller intentionally omits `@PreAuthorize` because valid Keycloak users may lack explicit realm roles.
9. Database Migrations: Defines check constraints (rating values 1 to 5, comment lengths 5 to 2000), audit triggers, and partial unique indexes. The outbox table would benefit from a composite partial index on `(status, id)` to optimize polling.
10. Configuration: Default secret `${RATING_SERVICE_CLIENT_SECRET:changeme}` remains in tracked source code.
11. State Concurrency: The `Rating` entity lacks `@Version`. Concurrent submissions trigger `DataIntegrityViolationException` on `uk_rating_user_product_live`, returning 500 instead of 409 `RTG-11005`.

---

## notification-service (18 Java classes, YAML config, changelog): Core Read
Verdict: ~80% compliance. Code inspection findings:
1. `NotificationServiceImpl.handle()`: Operates without `@Transactional`. It inserts records via `NotificationWriter.insert()`, calls `sender.send()` outside transactions, and updates failures separately. Records are initialized with status `SENT` (line 49) prior to actual dispatch (line 66).
2. Broad Constraint Handling: Catching `DataIntegrityViolationException` (line 58) swallows all database constraint failures (including missing non-null `order_id` fields), discarding events silently under the mislabeled assumption of concurrent consumer conflicts.
3. Input Parsing: Invoking `UUID.fromString(event.getEventId())` without format validation causes invalid strings to throw unhandled exceptions, triggering 9 consumer retries before message abandonment.
4. Pagination Bounds: `BackofficeNotificationController.java:35` caps maximum page size but omits lower-bound validation. Negative values throw `IllegalArgumentException`.
5. Consumer Wire Deserialization: `OrderEventConsumer` uses typed binding for `OrderLifecycleEvent`. Because order-service produces double-encoded strings, this consumer encounters deserialization errors.
6. Minor Observations: `OrderLifecycleEvent` uses Lombok annotations rather than Java records to satisfy Jackson deserialization constraints. `NotificationWriter` uses `@Repository` instead of `@Component`.
7. Configuration: Overrides default consumer groups with `shop.kafka.consumer.group-id: notification-service` and `auto-offset-reset: latest`. Mail delivery is safely disabled by default (`smtp.enabled: false`).

---

## gateway-service: Complete Filter and Route Verification
1. `ClientIpResolver.java:22-28`: Extracts the first entry from `X-Forwarded-For` headers. Because clients can inject arbitrary leading entries when running behind appending proxies, callers can spoof allowlisted CIDR IPs to bypass the administrative gateway filter.
2. `GlobalRateLimitFilter`: Returns HTTP 429 with an empty response body instead of the standard structured `ApiResponse` envelope, breaking client response parsing.
3. CORS Configuration: Properties default `allowed-origin-patterns: "*"` alongside `setAllowCredentials(true)`, creating cross-origin exposure when specific origins are configured without disabling credentials.
4. Defensive Guards: `AdminRoleGateFilter` and `RequestPathGuard` reject percent-encoded and matrix-parameter path evasion attempts with 400 Bad Request, validated by comprehensive tests.
5. Edge Rate Limiter: In-memory `ConcurrentHashMap<String, Bucket>` lacks an eviction policy, accumulating entries as unique IP addresses connect.

---

## inventory-service: Complete Core Component Inspection
1. Stock Validation: Method `update()` allows setting `availableQuantity` below `reservedQuantity` without validation guards, permitting negative available balances after order commits.
2. Code Duplication: `ReservationServiceImpl` duplicates four identical retry wrapper methods (lines 31-91) with identical backoff loops.
3. Scheduler Transaction Boundaries: `ReservationCleanupScheduler` executes batch processing inside a single `@Transactional` method with internal catch blocks. Previously flushed batches remain committed. Retention purges only `EXPIRED` records, leaving `RELEASED` and `COMMITTED` rows indefinitely.
4. Outbox Retention: `OutboxRetentionScheduler` correctly purges `SENT` events older than 7 days while preserving `FAILED` records.
5. Double-Encoding Publisher: `TransactionalInventoryEventPublisher.save()` writes JSON string payloads into outbox records, which `InventoryOutboxRelay` publishes via `KafkaMessagePublisher`, confirming the shared double-encoding issue.
6. Documentation Quality: The relay documents head-of-line break behavior (lines 22-29 and 79), and event contracts reference `docs/SERVICE-CATALOG.md`.
7. Internal Security: Administrative stock mutation routes enforce `@PreAuthorize("hasRole('SERVICE') or hasRole('ADMIN')")`.

---

## auth-service: Complete Read (25 Files Inspected)
1. **Critical Privilege Escalation Confirmed Across 7 Linked Code Points:**
   - Registration endpoint `/api/v1/auth/sign-up` is unauthenticated (`public-paths` line 40).
   - `RegisterRequest.java:43` defines unvalidated `private Set<String> roles`.
   - `UserServiceImpl.extractRoles()` (lines 152-160) passes client-supplied roles directly into user creation logic.
   - `KeycloakAdminClient.createUser()` passes roles into `assignRealmRoles`.
   - `ecommerce-realm.json` contains valid realm roles: `ADMIN`, `USER`, `MANAGER`, `SERVICE`.
   - `JwtRolesConverter` parses `realm_access.roles` into both `ADMIN` and `ROLE_ADMIN` authorities.
   - Downstream backoffice endpoints enforce `@PreAuthorize("hasRole('ADMIN')")`.
   - Impact: Any caller sending `roles: ["ADMIN"]` during public registration gains full administrative access across the platform.
2. **Password Logging Clarification:** `@LogPerformance(logInput=true)` on registration invokes `LoggerAspect.stringify()`, which calls `obj.toString()`. Because `RegisterRequest` and `LoginRequest` use Lombok `@Getter/@Setter` without `@ToString`, Java standard `Object.toString()` prints memory identities rather than field values. Plaintext passwords are not printed today. However, converting DTOs to Java records or adding `@ToString` will immediately expose credentials.
3. Transaction Boundaries: Implementation uses `jakarta.transaction.Transactional` instead of Spring `@Transactional`. Registration and password change methods hold active database transactions across two outbound Keycloak HTTP exchanges.
4. Soft Deletion Re-Registration Collision: Unique constraints on `user_name`, `email`, and `phone_number` omit partial `WHERE deleted=false` clauses. Soft-deleted accounts permanently block subsequent registrations using the same username, email, or phone.
5. Lazy Initialization Exception: Method `RoleServiceImpl.getUserRoles()` lacks `@Transactional` while `open-in-view: false` is active. Accessing lazy role collections throws `LazyInitializationException`, causing `GET /api/v1/roles/users/{id}` to fail with 500 errors.
6. Pagination and Pathing: `findAllUsers` omits page size caps. Controllers hardcode path literals (`/api/v1/auth`, `/api/v1/users`, `/api/v1/roles`) instead of referencing `ApiPaths`.
7. Error Handling: `rollbackKeycloakUser` contains empty catch blocks labeled "Log but don't throw" without actual logging statements.
8. Identity Split: Role assignments via `assignRole` write only to local database tables without synchronizing roles to Keycloak, resulting in diverging authorization states.

---

## payment-service: Core Component Verification
1. **Failed Webhook Event Deadlock (C3):** `WebhookEventService.handle()` records events as `FAILED` (line 123) and marks them failed on error. Lines 41-43 check `existsByProviderAndProviderEventId` and return early on existing records. When payment providers redeliver failed webhooks, the deduplication check drops them silently. Failed payments remain unrecovered.
2. Provider Result Handling: Methods `capture()` (line 50) and `refund()` (line 61) invoke `provider.capture/refund(...)` but discard the returned `ProviderResult`. Stripe provider stubs throw `UnsupportedOperationException`.
3. Outbox Integrity: `PaymentWriter.completeWithEvent` writes outbox events within the primary payment transaction. `WebhookSignatureVerifier` implements constant-time HMAC comparison via `MessageDigest.isEqual`.
4. Mock Provider Safety: `MockProvider` is configured with `matchIfMissing = true`. Omitting provider configuration in production profiles activates mock payment processing.
5. Idempotency Race Condition: Method `create()` performs read-then-insert checks against unique constraint `uk_payment_idempotency_key`. Concurrent duplicate requests throw `DataIntegrityViolationException` returning 500 rather than returning the existing payment record.
6. Double-Encoding Publisher: Outbox payloads are pre-serialized to JSON strings, publishing double-encoded records to `shop.payment.lifecycle.v1`.

---

## promotion-service: Complete Read
1. **Outbox Encoding:** `TransactionalPromotionEventPublisher.save()` writes pre-serialized JSON strings into outbox tables, confirming the same double-encoding pattern.
2. **Exemplary Retry Pattern:** `ReservationRetryServiceImpl.withRetry(Supplier)` provides a clean template method for retry execution, serving as a model for inventory-service.
3. Concurrency Protection: Reservation logic protects against read-then-insert races by updating `updatedAt` on the Campaign entity and executing `saveAndFlush`. Collisions throw `OptimisticLockingFailureException` and trigger automated retries.
4. Scheduler Documentation Discrepancy: `ReservationCleanupScheduler` comments state that flushed batches roll back on error. In reality, catch blocks inside `@Transactional` ensure previously flushed batches remain committed.
5. Retention Cleanup: Scheduler properly purges `RELEASED` and `EXPIRED` records older than 30 days.

---

## shipping-service: Core Verification
1. **Webhook Failure Handling:** In `WebhookEventServiceImpl`, unknown tracking numbers, null carrier statuses, and rejected transitions record the event as `FAILED` and return immediately. Inbound retries are dropped by deduplication checks.
2. Outbox Serialization: Writes pre-serialized JSON strings into outbox records. Downstream consumers on order-service expect typed payloads and fail deserialization.
3. Courier Integrations: Webhook verifiers map signing secrets per carrier. Method `ShipmentStatus.valueOf` executes strict string-to-enum matching, rejecting carrier status variations.

---

## product-service: Core Read
1. **Double-Encoding Impact on Rating Consumer:** Rating outbox events are published as double-encoded strings. In product-service, `ProductRatingConsumer` binds a typed `JsonDeserializer`, failing message processing before listener execution begins. As a result, product rating statistics are never updated.
2. **Documented Precedent for Double-Encoding:** Comments in `SearchListenerConfig` (lines 13-21) and `ProductSearchConsumer` (lines 11-21) explicitly document this behavior: "The fleet producer serializes the outbox payload STRING via JsonKafkaSerializer ... records arrive DOUBLE-ENCODED". Search-service resolved this locally by switching to `StringDeserializer` and decoding payloads manually.
3. Outbox Relay Ordering: `ProductOutboxRelay` continues batch processing upon error, documenting that product snapshot events are idempotent and do not require strict ordering.
4. Admin Endpoints: `ProductController` exposes public read endpoints alongside administrative mutation methods.

---

## search-service: Complete Read
1. Path and Security Standards: Aligns with architecture conventions. Controllers use `ApiPaths` and apply class-level `@PreAuthorize("hasRole('ADMIN')")` on backoffice routes.
2. Reindexing Safeguards: `ReindexServiceImpl` streams records using `MAX_PAGE_SIZE`, prevents concurrent reindexing jobs, and performs granular error checking on bulk indexing operations.
3. Token Provider Seams: `ServiceTokenProvider:80` throws `IllegalStateException` on empty responses, returning 500 errors when token retrieval fails.

---

## order-service: Verification Findings
1. Outbox Double-Encoding Impact: Outbox relays publish pre-serialized JSON strings. Downstream typed consumers in shipping-service and notification-service fail deserialization, preventing shipment creation and customer notification delivery. Similarly, order-service fails to consume courier delivery events from shipping-service.
2. Reference Idempotency Implementation: `IdempotencyServiceImpl` executes inserts in `REQUIRES_NEW` transactions, catching constraint violations and returning existing responses. This provides a clean model for payment-service.
3. Saga Implementation Quality: Order creation coordinates pricing checks and stock reservations across services with deterministic LIFO compensation.

---

## utils/common-*: Shared Platform Foundations
1. **Root Cause Analysis of Kafka Double-Encoding (R1):** `KafkaMessagePublisher` publishes payload objects using `KafkaTemplate<String, Object>`. Outbox relays supply JSON strings, prompting `JsonKafkaSerializer` to wrap the strings in an additional JSON token layer. Consumer configurations in `BaseKafkaListenerConfig` bind typed `JsonDeserializer` instances, failing deserialization upon encountering the nested string tokens.
2. Deserializer Package Whitelisting: Consumer configurations enable `addTrustedPackages("*")`. Restricting trust to `com.shop.*` restores defense-in-depth against malicious type headers.
3. Dead Configuration: Retry properties in `KafkaProperties` are not wired into listener error handlers, leaving consumers relying on default retry limits without dead-letter queues.
4. Error Handling Quality: Central exception handler `ApiExceptionHandler` maps domain exceptions cleanly. However, `handleDataIntegrityViolation` returns raw error messages from `exception.getMostSpecificCause()`, exposing database table and column names to clients.

---

## SYNTHESIS AND ARCHITECTURAL READINESS

### 1. Pattern Compliance: High (~90% Consistent)
- **Universal Fleet Compliance:** Shared response envelopes (`ApiResponse`, `PageResponse`), structured business exceptions, read-only transaction annotations, soft-delete restrictions, Liquibase migrations, environment variable substitution, outbox lifecycles, and documented security conventions across storefront and backoffice endpoints.
- **Accepted Contextual Deviations:** Non-blocking relay loops in product-service for idempotent snapshots; local payload unwrapping in search and media consumers; Lombok entity classes where Jackson binding requires mutable structures.
- **Actionable Remediation Targets:** Enforce pagination limits and shared `ApiPaths` in auth-service; replace Jakarta transactions with Spring transactions; add missing `@NotNull` annotations on tax request DTOs; and align scheduler documentation comments.

### 2. Production Readiness Assessment: Core Gaps
The platform functions effectively as an internal service architecture, but three system-wide defects prevent immediate production readiness:
- **R1: Kafka Double-Encoding Pipeline:** Outbox relays publish pre-serialized JSON strings, causing typed message consumers to fail across order, shipping, notification, and rating workflows. Fix at the publisher layer by writing structured objects or configuring publishers to detect pre-serialized strings.
- **C1: Registration Privilege Escalation:** Public registration endpoints accept arbitrary role sets, granting realm administrator access in Keycloak and across all backend services. Fix by stripping role inputs from registration requests and assigning default roles exclusively on the server.
- **C3: Failed Webhook Deadlocks:** Payment and shipping webhooks marked failed cannot be retried because deduplication checks drop repeated delivery attempts. Introduce retryable pending states and automated replay schedulers.
