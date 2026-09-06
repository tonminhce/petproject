# In-Depth Review: payment-service and promotion-service

- **Date:** 2026-09-02
- **Scope:** Complete analysis of `payment-service/src` and `promotion-service/src` (main, test, and resources), covering **99 files** (payment: 47 files across 27 main Java classes, 4 resources, 16 tests; promotion: 52 files across 32 main Java classes, 4 resources, 16 tests).
- **Exclusions:** `media-service/` (excluded from scope); `common-*/` used solely for cross-verification (e.g. `AbstractMappedEntity`, `ApiPaths`).
- **Methodology:** Systematic inspection against fleet baseline guidelines (layered architecture, `BusinessException` with `ErrorCode`, `ApiResponse` and `PageResponse` with `MAX_PAGE_SIZE`, `BaseSecurityConfig` public paths, outbox relays, Liquibase `ddl-auto: validate`, and `${ENV_VAR:default}` configuration patterns). Verified committed secrets using `git ls-files`.

Executive Summary: **25 findings (1 Critical, 1 High, 7 Medium, 16 Low).** Both modules show strong adherence to platform standards; deliberate deviations include explanatory comments or Javadoc. Key risks center on payment webhook lifecycle handling (lack of retry mechanisms for `FAILED` events) and unhandled provider outcomes during synchronous capture and refund calls.

---

## 1. Payment-Service

### Architectural Assessment

Clean layered structure: `controller`, `dto`, `entity`, `outbox`, `provider`, `repository`, `service` (with `impls`), and `webhook`. Employs Java records for DTOs, `AbstractMappedEntity` with auditing metadata, soft delete via `@SQLRestriction`, domain exceptions mapped to `PAY-5xxx`, transactional outbox coordination, constant-time HMAC-SHA256 signature verification (`MessageDigest.isEqual`) before payload processing, and fail-closed empty defaults for webhook secrets. No secrets are committed in source control (`.env.example` and `.env.prod.example` templates only). Strong test suite combining WebMvc slices, audit verifications, and Testcontainers integration tests verifying webhook replay, concurrent insertion races, and malformed payloads.

### Findings by File

**service/WebhookEventService.java**

- [Critical][error-handling] `WebhookEventService.java:41`: Webhook events saved with status `FAILED` are never reprocessed. The deduplication check `existsByProviderAndProviderEventId` returns immediately regardless of event row status. Any failure during `process()` (including transient connection errors between `insertEvent` and `completeWithEvent`, or `OptimisticLockingFailureException` under concurrent status delivery) marks the event as `FAILED` and returns HTTP 200. When the payment provider redelivers the event, deduplication drops it silently. Consequently, state transitions are permanently lost (e.g. a transient failure during a `REFUNDED` webhook leaves the payment stuck in `CAPTURED`, overcharging customers without automated resolution). Recommendation: Deduplicate only when `status == PROCESSED`. Allow redeliveries of `FAILED` events to execute (the underlying state machine prevents illegal double transitions), or transition records to a retryable `PENDING` state handled by a background reaper. Differentiate permanent payload errors (such as amount mismatches) from transient network failures.
- [Medium][error-handling] `WebhookEventService.java:56-59`: Catch block `catch (Exception)` swallows both business and runtime exceptions while returning HTTP 200 "accepted". The payment provider treats delivery as complete and ceases redeliveries, trapping failures in the permanent `FAILED` state described above. Recommendation: Return HTTP 200 only after safely persisting state for subsequent retries; log business exceptions at warning level with event identifiers.

**service/impls/PaymentServiceImpl.java**

- [High][error-handling] `PaymentServiceImpl.java:55-63`: Methods `refund()` and `capture()` (lines 44-52) invoke external providers but ignore the returned `ProviderResult` (`accepted` and `providerEventId` are unreferenced dead code, see `PaymentProvider.java:14`). No state updates or payment events are persisted. Consequently, repeating `POST /{id}/refund` before receiving the asynchronous `REFUNDED` webhook issues duplicate refund calls to the provider (risking double refunds). If webhooks are dropped, no audit trail remains for reconciliation. Furthermore, rejected operations (`accepted=false`) still return HTTP 200 to callers. Additionally, the same idempotency key (`payment.getIdempotencyKey()`) is reused for both capture and refund actions. For providers like Stripe, this causes refund requests to return cached capture results without executing. While the real Stripe adapter remains planned for future implementation (currently throwing `UnsupportedOperationException`), this contract flaw will cause functional bugs upon integration. Recommendation: Introduce intermediate states (e.g. `CAPTURE_REQUESTED`, `REFUND_REQUESTED`) or persist `PaymentEvent` audit records; store `providerEventId`; scope idempotency keys per operation (append `-capture` or `-refund`); check `accepted` and throw structured business exceptions when providers reject transactions.

**service/impls/PaymentServiceImpl.java (create)**

- [Medium][error-handling] `PaymentServiceImpl.java:31-41`: Reusing an idempotency key with conflicting request payloads (differing order IDs or amounts) silently returns the cached payment record rather than rejecting the conflict. Furthermore, race conditions during concurrent creation calls with identical keys collide on unique constraint `uk_payment_idempotency_key` (`changelog-001-payments.yaml:37`), throwing an unhandled `DataIntegrityViolationException` that returns 500 without structured error codes. Recommendation: Throw a conflict `BusinessException` when an existing key matches different order parameters; catch `DataIntegrityViolationException`, re-query by key, and return the existing record cleanly.

**resources/application.yml**

- [Medium][pattern] `application.yml:36`: Property `provider: ${PAYMENT_PROVIDER:mock}` combined with `matchIfMissing = true` on `MockProvider` (`MockProvider.java:10`) causes the service to run with mock payment processing in production if `PAYMENT_PROVIDER` is unset, recording successful payments without charging customers. Recommendation: Require explicit provider configuration in production profiles and fail fast on startup if the provider defaults to mock.

**outbox/OutboxEventRepository.java and PaymentOutboxRelay.java**

- [Medium][performance] `OutboxEventRepository.java:17-19`: Method `deleteByStatusAndSentAtBefore` is declared but never invoked by any scheduled task. Payment-service lacks an outbox retention scheduler (unlike promotion-service). Records with status `SENT` and `FAILED` accumulate indefinitely. Recommendation: Add a scheduled task purging sent outbox events older than a configurable retention window.

**outbox/PaymentOutboxRelay.java**

- [Medium][testing] `PaymentOutboxRelay.java:29-57`: Error handling logic (incrementing retry counts, transitioning to `FAILED` after maximum attempts, head-of-line break statements) lacks unit test verification; only the successful drain path is tested indirectly via `PaymentFlowIT`. Additionally, the break statement lacks an explanatory comment detailing head-of-line blocking semantics. Recommendation: Add unit tests covering failure paths and document head-of-line ordering requirements.

**constant/PaymentStatus.java**

- [Low][clean-code] `PaymentStatus.java:9`: Constant `TERMINAL_WEBHOOK_STATES` is unused dead code. Recommendation: Remove or apply in `WebhookEventService`.

**service/PaymentWriter.java**

- [Low][dry] `PaymentWriter.java:20-28`: Methods `insert()` and `saveAndFlush()` execute identical calls to `repository.saveAndFlush`. Recommendation: Consolidate into a single method.

**repository/PaymentRepository.java**

- [Low][clean-code] `PaymentRepository.java:13`: Redundant explicit declaration of `findById` already inherited from `JpaRepository`. Recommendation: Remove redundant declaration.

**controller/PaymentController.java and BackofficePaymentController.java**

- [Low][pattern] `PaymentController.java:35,42,49,56`, `BackofficePaymentController.java:24`: Uses `@PreAuthorize` annotations, diverging from the baseline guideline that centralizes authorization in filter chains. This is a deliberate design choice required to enforce `SERVICE` and `ADMIN` role checks that cannot be expressed via public path rules alone. Promotion-service documents this rationale; payment-service omits the explanatory comment. Recommendation: Add explanatory Javadoc clarifying the deliberate use of method security.

**provider/StripeProvider.java**

- [Low][error-handling] `StripeProvider.java:35-41`: Methods `capture` and `refund` throw `UnsupportedOperationException`, returning raw 500 errors when `PAYMENT_PROVIDER=stripe`. This aligns with the current implementation roadmap. Recommendation: When implementing the Stripe provider, map integration errors to structured `PAY-6xxx` business exceptions.

### Files Without Findings (payment-service)
`PaymentServiceApplication.java`, controller components, request and response DTOs, domain entities, outbox models, repository interfaces, state machine utilities, webhook verifiers, database migrations, and associated integration test suites.

---

## 2. Promotion-Service

### Architectural Assessment

Follows domain specifications closely: idempotent commit, release, and release-committed state transitions; proper optimistic locking retry patterns (decoupling `ReservationRetryService` from transactional beans to avoid self-invocation proxy bypasses); concurrency guards via version-touching with clear documentation; transactional outbox with metrics and retention cleanup; and batched TTL expiration sweeps with explicit entity manager flushes and clears. Unit and integration tests cover complex multi-threaded concurrency scenarios across 8 worker threads against PostgreSQL. Primary improvement areas involve enforcing order ID idempotency during reservations and correcting a transaction boundary comment in the cleanup scheduler.

### Findings by File

**service/impls/CampaignReservationServiceImpl.java**

- [Medium][error-handling] `CampaignReservationServiceImpl.java:102-111`: Method `reserve()` is not idempotent per order ID. While the database enforces unique constraint `uk_cur_order_id` (`changelog-001-initial-schema.yaml:118-123`), the service does not query `findByOrderId` (declared in `CouponUsageReservationRepository.java:37` but unused). When order-service retries reservation requests during transient network timeouts, requests collide on the unique constraint, throwing an unhandled `DataIntegrityViolationException` that returns 500 rather than structured business errors. Recommendation: Query `findByOrderId` prior to insertion; return the existing reservation if present with status `PENDING` or `COMMITTED`, or throw a descriptive `BusinessException`.

**service/ReservationCleanupScheduler.java**

- [Medium][documentation] `ReservationCleanupScheduler.java:68-75`: The comment stating "Flushed batches are rolled back with the transaction" contradicts runtime behavior. Catch blocks reside inside the `@Transactional` method, preventing exceptions from propagating to the transaction proxy. Previously flushed batches remain committed. While this behavior is safe and ensures convergent progress across cycles, the documentation misleads engineers regarding rollback boundaries. Recommendation: Correct the comment to document intentional partial commits, or isolate individual batch processing into `REQUIRES_NEW` transactions.
- [Low][clean-code] `ReservationCleanupScheduler.java:56,86`: Directly references `Instant.now()` instead of injecting the configured `Clock` bean (`ClockConfig` is present and injected in the reservation service), reducing testability and introducing potential clock drift in retention tests. Recommendation: Inject and reference the shared `Clock` bean.

**service/PromotionEventPublisher.java**

- [Low][documentation] `PromotionEventPublisher.java:7-10`: Javadoc references an obsolete task description ("Task 6 ships a logging no-op; Task 10 replaces it with the transactional outbox publisher"). The interface has only a single transactional outbox implementation. Recommendation: Update documentation or eliminate the redundant interface abstraction.

**service/impls/TransactionalPromotionEventPublisher.java**

- [Low][clean-code] `TransactionalPromotionEventPublisher.java:37`: Class name "Transactional" implies that the class manages transactions directly, whereas it relies entirely on the caller active transaction. Recommendation: Rename to `OutboxPromotionEventPublisher` to clarify its role.

**service/impls/CampaignServiceImpl.java and CampaignReservationServiceImpl.java**

- [Low][dry] `CampaignServiceImpl.java:39-41` and `CampaignReservationServiceImpl.java:37-38`: Constant `COUNTED_STATUSES` (`PENDING` and `COMMITTED`) is defined independently in both classes with comments noting they mirror each other. Recommendation: Extract into a shared enum set on `UsageStatus`.

**entity/OutboxEvent.java (promotion-service)**

- [Low][clean-code] `OutboxEvent.java:68-69`: Field `retryCount` lacks `@Builder.Default` (unlike the payment-service equivalent in `OutboxEvent.java:41-43`). Building instances without explicitly setting retry count leaves the field null, violating database non-null constraints. Recommendation: Add `@Builder.Default` to initialize retry counts to zero.

**resources/application.yml**

- [Low][pattern] `application.yml:65-70`: Uses two separate configuration prefixes: `shop.promotion.*` (reservations) and `promotion.outbox.*` (relays). Recommendation: Consolidate configuration under a unified prefix.

**controller/PromotionReservationController.java**

- [Low][clean-code] `PromotionReservationController.java:60`: Method `releaseCommitted` returns `ApiResponse.ok(null)` while `commit` and `release` (lines 44 and 52) use `ApiResponse.message(...)`. Additionally, `reserve` (line 31) combines `@ResponseStatus(CREATED)` with `ApiResponse.ok`. Recommendation: Standardize response envelopes across mutation endpoints that do not return payloads.

**dto/request/CampaignRequest.java**

- [Low][clean-code] `CampaignRequest.java:23-24`: Omits validation ensuring `endsAt` falls after `startsAt`, allowing creation of campaigns that can never become active. Recommendation: Add a class-level validation constraint enforcing valid date ranges.

**service/PromotionOutboxRelay.java**

- [Low][testing] `PromotionOutboxRelay.java:58-81`: Error handling paths (retry increments, transition to `FAILED` after maximum attempts, head-of-line blocking) lack dedicated unit tests. Recommendation: Add unit tests verifying failure handling using a mock publisher.

**service/PromotionMetrics.java**

- [Low][clean-code] `PromotionMetrics.java:49`: Metric gauge `setPendingOutboxCount` is populated with `pending.size()` (capped by batch size), reflecting only the current batch size rather than total pending records. Recommendation: Populate gauges via count queries or rename the metric to reflect batch visibility.

**service/DiscountCalculator.java and validation/ValidDiscountValueValidator.java**

- [Low][error-handling] `DiscountCalculator.java:32`: Throws unhandled `IllegalStateException` on unrecognized discount types. While controller inputs are validated via regex patterns, validators use case-insensitive matching (`equalsIgnoreCase`) whereas calculators perform case-sensitive comparisons (`equals`). Recommendation: Normalize discount types in request DTOs and throw structured business exceptions on unrecognized types.

### Files Without Findings (promotion-service)
`PromotionServiceApplication.java`, `ClockConfig`, domain constants, backoffice controllers, DTO records, entity definitions, repositories, retry services, outbox schedulers, Liquibase changelogs, and associated unit and integration test suites.

---

## Supplemental Security and Operational Notes

- **Committed Secrets Verification:** Executed `git ls-files | grep -E "\.env|secret"` across the repository root. Only `.env.example` and `.env.prod.example` templates exist in source control. Real secrets are not tracked.
- **IDOR Protection:** Endpoints in both services require `SERVICE` or `ADMIN` roles. Client-supplied `userId` fields in machine-to-machine requests (such as reservation calls initiated by order-service) are standard in service-to-service communication.
- **Webhook Cryptographic Integrity:** Payment webhook verifiers implement constant-time HMAC-SHA256 comparison (`MessageDigest.isEqual`), reject malformed signatures, and restrict public paths to POST requests verified prior to database access. Empty secret configurations fail closed with HTTP 403.
- **Concurrency and Idempotency:** Payment creation enforces unique database indexes on idempotency keys; capture and refund operations rely on state machine validation. Priorities include resolving webhook deadlocks on failed events and adding operation-specific idempotency keys for captures and refunds.

---

## Summary Findings Matrix

### Severity by Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Architecture and Pattern | 0 | 0 | 1 | 2 | 3 |
| Clean Code | 0 | 0 | 0 | 8 | 8 |
| DRY | 0 | 0 | 0 | 2 | 2 |
| Error Handling | 1 | 1 | 3 | 2 | 7 |
| Performance | 0 | 0 | 1 | 0 | 1 |
| Documentation | 0 | 0 | 1 | 1 | 2 |
| Testing | 0 | 0 | 1 | 1 | 2 |
| **Total** | **1** | **1** | **7** | **16** | **25** |

### Module Evaluation Summary

| Microservice | Pattern Compliance | Code Quality and Testing | Priority Remediation Items |
|---|---|---|---|
| **payment-service** | Strong (8/10): Clean layering, manual DTO mapping, structured `PAY-5xxx` exceptions, transactional outbox, and secure webhooks. Method security annotations require documentation. | Solid test coverage across WebMvc slices, audit trails, and Testcontainers integration tests. Minor dead code. | 1) Reprocessing lifecycle for failed webhook events (Critical); 2) Recording outcomes and state transitions for capture/refund with operation-scoped idempotency keys (High); 3) Outbox retention scheduling; 4) Payment creation idempotency race handling. |
| **promotion-service** | Exemplary (9/10): Clean state machine transitions, retry template patterns, optimistic locking guards, metrics, retention, and TTL batch sweeps. | High quality; thorough unit branch matrices and 8-thread concurrent integration tests against real PostgreSQL. | 1) Idempotent reservations scoped by order ID; 2) Aligning scheduler documentation comments with transaction semantics; 3) Consolidating shared status constants. |
