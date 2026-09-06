# Code Review Report: shipping-service and tax-service

- **Date:** 2026-09-02
- **Scope:** Full inspection of `/shipping-service/src` (58 files) and `/tax-service/src` (37 files), covering production Java classes, unit tests, integration tests, and resources. Excludes `media-service/`.
- **Review Mode:** Read-only analysis evaluating architecture patterns, clean code, SOLID and DRY principles, error handling, logging, security, performance, documentation, and test quality.
- **Baseline:** Cross-verified against `utils/common-core` (`ErrorCode`, `AbstractMappedEntity`, `SoftDeletable`, `ApiPaths`, `PageableConstant`), `utils/common-security` (`BaseSecurityConfig` and `SecurityAutoConfiguration` with `@EnableMethodSecurity`), `utils/common-kafka` (`KafkaMessagePublisher`, `JsonKafkaSerializer`, `BaseKafkaConsumer`), and `utils/common-spring` (`JpaAuditingAutoConfiguration`).

Executive Summary: Both modules demonstrate high design quality without any Critical or High severity findings. Identified issues focus on integration reliability in shipping-service (double-encoded outbox payloads, missing webhook event replay, unconstrained outbox growth) and minor input validation gaps in tax-service (nullable country codes in rate requests).

---

# Part 1: Shipping-Service

## Architectural Baseline Assessment

- Controller Security: `BackofficeShipmentController` enforces class-level `@PreAuthorize("hasRole('ADMIN')")`. Method security is enabled across the fleet via `SecurityAutoConfiguration`, aligning with repository-wide authorization practices.
- Webhook Security: The webhook endpoint resides in `public-paths` but requires constant-time HMAC-SHA256 signature verification (`MessageDigest.isEqual`), failing closed when secrets are empty. Lacks public storefront endpoints, eliminating IDOR risks.
- Domain Error Codes: Implements codes `SHP-10001` through `SHP-10006`, following the platform standard numbering scheme.
- Index Coverage: Database indexes (`uk_shipment_order_live`, `uk_shipments_tracking_live`, `idx_shipments_status_stale`, `uk_shipment_events_carrier_event`) align with query access paths.
- Outbox Architecture: Transactional outbox relay follows the sequential delivery model established in order-service.

## Findings by File

### outbox/ShippingEventPublisherImpl.java

- **[Medium][solid] `outbox/ShippingEventPublisherImpl.java:39-62` and `outbox/ShippingOutboxRelay.java:37-39`**: Outbox payloads are persisted as pre-serialized JSON strings. The outbox relay passes these strings to `KafkaMessagePublisher`, which invokes `JsonKafkaSerializer` (from common-kafka). Calling `ObjectMapper.writeValueAsBytes(String)` wraps the payload in an additional JSON string token layer. Downstream consumers on `shop.shipping.lifecycle.v1` receive double-encoded strings rather than structured objects. Internal test `ShippingFlowIT` (lines 402-403) works around this defect by unwrapping text tokens during deserialization. Recommendation: Update `JsonKafkaSerializer` to pass through raw byte arrays for strings, or configure relays to deserialize payloads to `JsonNode` before publishing.

### outbox/ShippingOutboxRelay.java

- **[Medium][error-handling] `outbox/ShippingOutboxRelay.java:54`**: The relay terminates execution on the first publishing error (`break`). A single failed event blocks the entire remaining batch until the failed message reaches `maxRetries`. While this behavior preserves aggregate ordering, the explanatory comments present in order-service were omitted during adaptation. Recommendation: Restore explanatory comments documenting head-of-line blocking and log the count of deferred events.
- **[Medium][performance] `outbox/OutboxEventRepository.java:17-19`**: Query method `deleteByStatusAndSentAtBefore` is declared without any calling sites. Shipping-service lacks an outbox retention scheduler (unlike order, promotion, and inventory services). Table `outbox_events` grows indefinitely, degrading index performance over time. Recommendation: Add an outbox retention scheduler purging sent events after a configured retention window.

### service/impls/ShipmentServiceImpl.java

- **[Medium][error-handling] `service/impls/ShipmentServiceImpl.java:69-71`**: Catching `DataIntegrityViolationException` broadly logs that a concurrent consumer won the race. Non-duplicate constraint errors (such as missing mandatory `orderId` values or check constraint failures) are caught and logged under an incorrect assumption. Recommendation: Inspect underlying database constraints and catch only duplicate violations on `uk_shipment_order_live`, rethrowing unrelated persistence errors.
- **[Medium][dry] `service/impls/ShipmentServiceImpl.java:149-164` and `service/impls/WebhookEventServiceImpl.java:113-129`**: Status transition logic (updating timestamps, previous statuses, and recording metrics) is duplicated across administrative and webhook paths. Recommendation: Extract a shared transition method (`applyTransition(shipment, next, now)`).
- **[Medium][error-handling] `service/impls/ShipmentServiceImpl.java:121`**: Method `assignTracking` does not pre-check tracking number uniqueness. Assigning a duplicate tracking number violates unique index `uk_shipments_tracking_live`, throwing an unhandled database exception that returns 500 instead of a structured 409 conflict error. Recommendation: Check `findByTrackingNumber` prior to assignment and throw domain exception `SHIPMENT_DUPLICATE`.
- **[Low][clean-code] `service/impls/ShipmentServiceImpl.java:88-104`**: Method `findAll` uses sequential `else-if` blocks: supplying both `status` and `carrier` parameters silently ignores the carrier filter. Recommendation: Support combined filtering or reject conflicting filter combinations.

### service/impls/WebhookEventServiceImpl.java

- **[Medium][error-handling] `service/impls/WebhookEventServiceImpl.java:91-101`**: Webhooks with valid signatures referencing unknown tracking numbers (or null carrier statuses) return HTTP 200 while recording the event as `FAILED`. Because the courier receives success status, the event is never redelivered. Out-of-order webhook arrivals remain unrecoverable. Recommendation: Implement a dead-letter retry table and automated replay scheduler for failed webhook events.
- **[Medium][error-handling] `service/impls/WebhookEventServiceImpl.java:105-106`**: Parses courier status using `ShipmentStatus.valueOf(payload.getCarrierStatus())`, requiring external couriers to transmit status strings matching internal enum names exactly. Integrating real couriers will cause unmapped status strings to fail with unhandled exceptions. Recommendation: Implement carrier-specific status mapping adapters inside `CarrierAdapter`.

### scheduler/ReconciliationScheduler.java

- **[Medium][error-handling] `scheduler/ReconciliationScheduler.java:45-53`**: Automatically marks in-flight shipments as `DELIVERED` after 7 days without carrier updates, omitting confirmation checks or warnings. Delayed deliveries will emit false delivery events to order-service. Configuration property `SHIPPING_NOTIFY_THRESHOLD_HOURS: 72` is defined in `application.yml` but never read in code. Recommendation: Implement pre-delivery notifications (using the 72-hour threshold) before automatic delivery transitions.
- **[Low][error-handling] `scheduler/ReconciliationScheduler.java:48` and `repository/ShipmentRepository.java:30`**: Query `last_carrier_update < cutoff` excludes records where `last_carrier_update` is null, preventing automatic delivery for shipments created with null initial carrier timestamps. Recommendation: Use `COALESCE(last_carrier_update, created_at)` in query criteria.

### Additional Observations in shipping-service

- [Low][clean-code] `outbox/ShippingEventPublisherImpl.java:50`: Directly references `Instant.now()` instead of the injected `Clock` bean.
- [Low][clean-code] `resources/application.yml:32`: Property `notify-threshold-hours` is dead configuration.
- [Low][pattern] `dto/OrderLifecycleEvent.java` and `webhook/CarrierWebhookPayload.java`: Implemented as mutable Lombok classes rather than Java records.
- [Low][clean-code] `repository/ShipmentRepository.java:18`: Redundantly declares `Optional<Shipment> findById(UUID id)`.
- [Low][documentation] `test/.../support/AbstractIntegrationTest.java:53-55`: Documentation claims integration tests bypass filter chains, whereas `ShippingFlowIT` executes with real security filters enabled.
- [Low][testing] `test/.../ShippingFlowIT.java:400-403`: Test helper `parse()` unwraps double-encoded strings, masking the underlying serialization bug.
- [Low][testing] `controller/BackofficeShipmentControllerTest.java`: Lacks test cases verifying page size limits (`Math.min(size, MAX_PAGE_SIZE)`).

## Files Without Findings (shipping-service)
`ShippingServiceApplication`, carrier adapters, clock configurations, shipment domain constants, response DTOs, domain entities, event repositories, state machine implementations, webhook verifiers, database migrations, and associated unit tests.

---

# Part 2: Tax-Service

## Architectural Baseline Assessment

- Controller Validation: Uses `@Valid` alongside Jakarta validation annotations, domain error codes `TAX-8001` through `TAX-8005`, and soft deletion via `@SQLRestriction`.
- Arithmetic Precision: Calculations use `BigDecimal` with `divide(100, 2, RoundingMode.HALF_UP)` and `numeric(5,2)` database columns backed by check constraints, verified by automated precision tests.
- Internal Gating: Calculation endpoints enforce `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")` with audit logging tracking service identities.
- Functional Indexes: Database indexes on `lower(name)` and `COALESCE(postal_code, '')` align with query lookup patterns.

## Findings by File

### dto/request/TaxRateRequest.java and TaxCalculateRequest.java

- **[Medium][error-handling] `dto/request/TaxRateRequest.java:13`**: Annotation `@Pattern(regexp = "^[A-Z]{2}$")` lacks an accompanying `@NotNull` constraint. Requests with null country values pass validation and fail during database insertion on `country char(2) NOT NULL`, throwing a `DataIntegrityViolationException` that returns 500 instead of 400 Bad Request. Recommendation: Add `@NotNull` to the country field in `TaxRateRequest`.
- [Low][error-handling] `dto/request/TaxRateRequest.java:15` and `dto/request/TaxCalculateRequest.java:13`: Property `postalCode` lacks `@Size(max = 16)` constraints, allowing oversized strings to trigger database truncation errors. Recommendation: Add `@Size(max = 16)`.

### service/impls/TaxClassServiceImpl.java

- [Low][clean-code] `service/impls/TaxClassServiceImpl.java:31,47`: Duplicate tax class names throw `DUPLICATE_TAX_RATE` (`TAX-8003`, message `tax.rate.duplicate`), producing misleading error messages during tax class creation. Recommendation: Introduce error code `DUPLICATE_TAX_CLASS`.
- [Low][error-handling] `service/impls/TaxClassServiceImpl.java:76` and `service/impls/TaxRateServiceImpl.java:76`: Invokes `auditorAware.getCurrentAuditor().orElseThrow()`, throwing unhandled `NoSuchElementException` if the auditor is missing. Recommendation: Provide a fallback to `"system"`.
- [Low][error-handling] `service/impls/TaxClassServiceImpl.java:30-37` and `service/impls/TaxRateServiceImpl.java:32-39`: Read-then-insert logic lacks concurrency protection against duplicate insertions. Recommendation: Catch `DataIntegrityViolationException` and map to `DUPLICATE_TAX_RATE`.

### service/impls/TaxCalculationServiceImpl.java

- [Low][clean-code] `service/impls/TaxCalculationServiceImpl.java:37-39`: The branch `if (ratePct == null) throw NO_MATCHING_RATE` is unreachable in production because database columns and creation DTOs enforce non-null constraints. Recommendation: Document as defensive code or remove the dead branch.
- [Low][performance] `service/impls/TaxCalculationServiceImpl.java:43-48`: Issues two sequential queries (postal-specific followed by country-wide) on checkout execution paths. Recommendation: Consolidate into a single query using `ORDER BY postal_code NULLS LAST LIMIT 1`.
- [Low][dry] `service/impls/TaxCalculationServiceImpl.java:50-52` and `service/impls/TaxRateServiceImpl.java:91-93`: Method `normalize(postalCode)` is duplicated across implementation classes. Recommendation: Extract into a shared utility method.

### controller/TaxCalculationController.java

- [Low][pattern] `controller/TaxCalculationController.java:22`: The internal service-to-service endpoint `/calculate` is mounted under `/api/v1/backoffice/tax-rates/calculate`. Recommendation: Mount under a dedicated non-backoffice path constant (`/api/v1/tax/calculate`).

### Configuration and Logging

- [Low][pattern] `resources/application.yml:24`: Default `issuer-uri` references `http://keycloak:8080/realms/ecommerce`, whereas shipping-service references `http://localhost:9090/realms/ecommerce`. Recommendation: Standardize default configuration endpoints across services.
- [Low][logging] Tax-service main code: Service methods omit structured application logging. Administrative tax rate modifications are recorded only via audit annotations. Recommendation: Add info logs for tax rate changes and warning logs when calculations fall back to default rates.

## Files Without Findings (tax-service)
`TaxServiceApplication`, administrative tax controllers, DTO records, domain entities, repositories, calculation utilities, database migrations, and associated integration test suites.

---

# Summary Findings Matrix

### Severity by Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Error Handling | 0 | 0 | 6 (5 shipping, 1 tax) | 7 (3 shipping, 4 tax) | 13 |
| SOLID | 0 | 0 | 1 | 0 | 1 |
| DRY | 0 | 0 | 1 | 1 | 2 |
| Performance | 0 | 0 | 1 | 1 | 2 |
| Clean Code | 0 | 0 | 0 | 5 (2 shipping, 3 tax) | 5 |
| Pattern Compliance | 0 | 0 | 0 | 3 (1 shipping, 2 tax) | 3 |
| Documentation | 0 | 0 | 0 | 2 | 2 |
| Testing | 0 | 0 | 0 | 3 | 3 |
| Logging | 0 | 0 | 0 | 1 | 1 |
| **Total** | **0** | **0** | **9** (8 shipping, 1 tax) | **22** (12 shipping, 10 tax) | **31** |

### Module Evaluation Summary

**shipping-service (Compliance: 8/10):** Strong layered structure, standard error codes, transactional outbox publishing, complete state machine transition matrices, constant-time HMAC webhook verification, and thorough integration testing with Testcontainers. Remediation priorities focus on resolving outbox double-encoding, adding replay mechanisms for failed webhook events, tightening database constraint exception handling, implementing outbox retention cleanup, and adding warnings before automated delivery transitions.

**tax-service (Compliance: 9/10):** Clean CRUD implementations, comprehensive validation, soft deletion, and arithmetic precision backed by `BigDecimal` calculations tested against PostgreSQL numerics. Remediation items are localized: enforcing non-null country codes on rate requests, correcting duplicate tax class error codes, consolidating postal normalization logic, and introducing structured logging on rate modifications.
