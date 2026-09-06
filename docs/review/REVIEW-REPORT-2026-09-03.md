# Consolidated Code Review Report: Second Independent Pass

> **Date:** 2026-09-03
> **Review Methodology:** Seven parallel review agents structured along two complementary axes:
> - **Domain Axis (5 agents):** auth, favourite, and gateway; product, search, and notification; order, payment, and inventory; shipping, tax, and promotion; rating and common-utils.
> - **Specialist Axis (2 agents):** Security specialist and Performance specialist.
>
> **Key Differences from the 2026-09-02 Review:**
> - Completely independent evaluation with no references to previous reports.
> - In-depth security (p6) and performance (p7) assessments.
> - Heavy emphasis on SOLID principles, code quality metrics, test effectiveness, DRY hygiene, and documentation accuracy.
> - Baseline verification against the 9 fleet rules established by favourite-service.
>
> **Exclusions:** media-service and existing review documents.

---

## 1. Raw Metrics Overview (Pre-Deduplication)

| Module or Functional Area | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| auth + favourite + gateway | 3 | 6 | 8 | 8 | **25** |
| product + search + notification | 8 | 16 | 20 | 25 | **69** |
| order + payment + inventory | 5 | 8 | 10 | 10 | **33** |
| shipping + tax + promotion | 7 | 12 | 18 | 18 | **55** |
| rating + common-utils | 3 | 6 | 10 | 10 | **29** |
| Security specialist (fleet-wide) | 5 | 10 | 12 | 10 | **37** |
| Performance specialist (fleet-wide) | 5 | 10 | 24 | 19 | **58** |
| **Total (Raw)** | **36** | **68** | **102** | **100** | **306** |

---

## 2. Cross-Cutting Themes Across Review Streams

### T1. Missing Audience Validation in JWT Decoder (p5, p6, p7)
- Location: `BaseSecurityConfig.java:93-95`
- Issue: `NimbusJwtDecoder` validates issuer, expiration, and not-before claims, but omits the `aud` claim.
- Consequence: Keycloak tokens issued for one client (e.g. `ecommerce-client`) are accepted by backend services without audience checks, enabling token replay and horizontal privilege escalation.
- Fix: Configure a `DelegatingOAuth2TokenValidator` combining `JwtIssuerValidator` and a dedicated `JwtClaimValidator` for audience verification.

### T2. Outbox Race Conditions Under Multi-Pod Scaling (p2, p3, p5, p7)
- Location: `product-service` OutboxRelay, `order-service`, `rating-service`, and related outbox tables.
- Issue: Outbox relays query pending records without row locking or concurrent coordination. Running multiple pods causes duplicate publishing across downstream consumers.
- Fix: Implement `SELECT ... FOR UPDATE SKIP LOCKED` combined with an optimistic `@Version` column, or migrate outbox streaming to Debezium CDC.

### T3. Missing Timestamp and Replay Verification on Webhooks (p3, p4, p6)
- Location: Payment and shipping webhook controllers.
- Issue: Webhook signatures do not incorporate timestamp checks, and default secrets fallback to empty or predictable strings.
- Consequence: Attackers can capture and replay webhook payloads indefinitely to manipulate payment and shipment states.
- Fix: Adopt Stripe-style signed headers (`t=<unix_ts>,v1=<hmac>`) and reject requests where `|now - t| > 300` seconds.

### T4. Connection Pool Leaks: Remote HTTP Calls Inside Active Database Transactions (p1, p2, p3, p7)
- Locations: User registration holding transactions across Keycloak calls; payment capture and refund flows; order commit coordination loops.
- Issue: Holding open database connections across synchronous network I/O exhausts HikariCP pools rapidly under load, causing thread starvation.
- Fix: Separate database writes from remote HTTP calls: execute database write step 1, complete the HTTP exchange outside any transaction, then record final state in database write step 2.

### T5. Kafka Serializer and Consumer Configuration Gaps (p2, p5, p6, p7)
- Locations: `BaseKafkaListenerConfig.java:79`, `KafkaProperties.java:160-183`, and publisher relay loops.
- Issue: Broad package trust (`addTrustedPackages("*")`), unwired dead-letter queues, synchronous `future.get(10s)` blocking in publisher loops, and missing producer batching parameters.
- Fix: Restrict trusted packages to `com.shop.*`, wire default error handlers to explicit dead-letter topics, and tune producer properties (`batch.size=32768`, `linger.ms=20`, `compression.type=lz4`).

### T6. Hardcoded Secrets and Default Credentials (p3, p6, p7)
- Locations: Docker Compose environment definitions, Keycloak realm import files, and default application properties.
- Issue: Predictable fallback values like `changeme` and `local-test-secret` enable authentication bypasses and payment tampering.
- Fix: Adopt fail-fast syntax `${VAR:?must be set}` for all credentials across deployment descriptors.

### T7. Client IP Spoofing via Unvalidated X-Forwarded-For Headers (p1, p6)
- Locations: `RateLimitFilter.java` and `ClientIpResolver.java`.
- Issue: Using the leftmost entry of client-supplied `X-Forwarded-For` headers allows callers to bypass rate limiters and CIDR allowlists.
- Fix: Extract IP addresses from the rightmost untrusted hop index: `XFF.length - trustedProxyHops`.

### T8. Premature State Recording: Writing Terminal Status Prior to Verification (p2, p4, p6, p7)
- Locations: Notifications recorded as `SENT` before dispatch; shipping records marked `DELIVERED` automatically after 7 days without courier confirmation.
- Fix: Model lifecycles with explicit intermediate stages: `PENDING -> PROCESSING -> SENT/DELIVERED`, supported by dead-letter and retry tracking.

### T9. Hardcoded API Paths Bypassing Standard Route Constants (p1, p2, p4, p6)
- Locations: Multiple controllers using raw strings rather than `com.shop.common.core.constants.ApiPaths`.
- Fix: Standardize all controller classes on `@RequestMapping(ApiPaths.*)`.

### T10. Security Gaps in Slice Tests via addFilters=false (p1, p2, p3, p6)
- Locations: Unit and slice tests disabling security filters entirely, leaving 401 and 403 authorization logic unverified.
- Fix: Enable security filters on slice tests for protected endpoints and add an automated authorization test matrix.

---

## 3. Top 20 Critical Findings (Immediate Remediation)

### C1. Missing Audience Validation in JWT Decoder
- File: `utils/common-security/.../BaseSecurityConfig.java:93-95`
- Description: `NimbusJwtDecoder` validates expiration and issuer, but skips audience verification.
- Impact: Single-page application tokens can be submitted directly to backend APIs without audience checks, enabling horizontal privilege escalation.

### C2. Plaintext Passwords Logged at INFO Level
- File: `auth-service/.../service/impls/UserServiceImpl.java:42-44`
- Description: `@LogPerformance(logInput=true)` coupled with `RegisterRequest.toString()` prints sensitive registration inputs into standard log output.

### C3. Payment Webhook Secret Defaulting to Empty String
- File: `payment-service/src/main/resources/application.yml:47`
- Description: An empty default causes valid webhook signatures to fail with 401 Unauthorized whenever `PAYMENT_WEBHOOK_SECRET` is unset.

### C4. Database Check Constraint Mismatch on Payment Events
- File: `payment-service/.../db/changelog/changelog-001-payments.yaml:70`
- Description: The database constraint rejects records inserted with `FAILED_RETRYABLE`, throwing a `DataIntegrityViolationException`.
- Fix: Add a Liquibase changeset updating `ck_payment_events_status` to include all valid enum values.

### C5. Stripe Provider Stubs Throwing UnsupportedOperationException
- File: `payment-service/.../provider/StripeProvider.java:34-41`
- Description: When configured for production use, capture and refund invocations throw runtime exceptions because methods remain unimplemented stubs.

### C6. Idempotency Service Opening Multiple Database Connections
- File: `order-service/.../service/impls/IdempotencyServiceImpl.java:51-71`
- Description: Nested connection acquisitions in `begin()` exhaust small connection pools (pool size 10), capping concurrent order creations at 5 requests.

### C7. Payment Capture Ignoring Provider Result Status
- File: `payment-service/.../service/impls/PaymentServiceImpl.java:48-64`
- Description: Synchronous capture endpoints ignore `ProviderResult.accepted`, relying entirely on asynchronous webhooks to advance state.

### C8. Hardcoded Credentials in Keycloak Realm Import File
- File: `docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 127`
- Description: Realm import file commits shared default secrets and test user passwords into source control.

### C9. Insecure CORS Configuration on API Gateway
- File: `gateway-service/.../config/SecurityConfig.java:54-62`
- Description: Combining wildcard allowed origins (`*`) with `allowCredentials=true` creates cross-origin security vulnerabilities.

### C10. Default Payment Webhook Secret in Docker Compose
- File: `docker-compose.yml:369, 397`
- Description: Defaulting the webhook signing secret to `local-test-secret` permits signature forgery in test environments that bleed into staging.

### C11. Unchecked Parent Hierarchy Cycles in Category Service
- File: `product-service/.../service/impls/CategoryServiceImpl.java:97-112`
- Description: Category updates do not check for ancestor circularities, resulting in infinite loops during tree traversal.

### C12. Notifications Marked SENT Prior to Dispatch
- File: `notification-service/.../service/impls/NotificationServiceImpl.java:49, 65-71`
- Description: Records are marked `SENT` in the database before SMTP delivery finishes. Transient mail server outages cause permanent notification loss.

### C13. Storefront Controllers Mixing Admin Operations
- Files: Storefront controller definitions in `product-service`
- Description: Endpoints combine public storefront read methods with ADMIN-gated mutation endpoints on the same path mapping.

### C14. Outbox Relay Queries Omitting Row-Level Locking
- File: `product-service/.../service/OutboxRelay.java:49-85`
- Description: Concurrent publisher executions select the same pending rows, publishing duplicate events to the broker.

### C15. Search Reindexing Race Conditions and Number Parsing Errors
- File: `search-service/.../service/impls/ReindexServiceImpl.java:133-144`
- Description: Uncaught `NumberFormatException` during version suffix parsing terminates index migration tasks unexpectedly.

### C16. Notification Entity Missing Soft-Delete SQL Restriction
- File: `notification-service/.../entity/Notification.java:22-29`
- Description: Omitting `@SQLRestriction("is_deleted = false")` causes queries to return soft-deleted records alongside active notifications.

### C17. Notification Dispatch Lacking Retry Tracking and Dead-Letter State
- File: `notification-service/.../service/impls/NotificationServiceImpl.java`
- Description: Transient dispatch failures are dropped without transition to a retryable queue or dead-letter storage.

### C18. Raw Runtime Exceptions Escaping Search Service
- File: `search-service/.../service/ProductSearchService.java:47, 63`
- Description: Throws uncaught `IllegalStateException` directly out of service methods rather than mapping to standard domain exceptions.

### C19. Insecure Deserialization via Wildcard Package Trust
- File: `utils/common-kafka/.../BaseKafkaListenerConfig.java:79`
- Description: `addTrustedPackages("*")` allows arbitrary Java classes to be targeted by malicious Kafka event type headers.

### C20. Dead-Letter Topic Configuration Declared but Never Bound
- Files: `utils/common-kafka/.../KafkaProperties.java:160-183` and listener configuration beans
- Description: Retry and dead-letter configuration properties are defined in configuration classes but never attached to consumer factories.

---

## 4. Top 50 High-Priority Findings

| ID | Location | Summary | Stream |
|---|---|---|---|
| H1 | `auth-service/.../UserServiceImpl.java:42-56` | Registration holds database transactions open across outbound Keycloak calls | p1 |
| H2 | `auth-service/.../UserServiceImpl.java:193-199` | Rollback logic swallows exceptions without error logging | p1 |
| H3 | Auth-service request DTOs | Request bodies use Lombok classes rather than Java records | p1 |
| H4 | `gateway-service/.../RateLimitFilter.java:49,76` | In-memory token bucket map grows unbounded with unique client IPs | p1 |
| H5 | `auth-service/.../UserController.java:60-66` | Profile lookup queries local username using Keycloak subject UUID | p1, p6 |
| H6 | `auth-service/.../UserController.java:40,54,62` | Controller applies `@PreAuthorize` at method level instead of relying on centralized gateway gates | p1 |
| H7 | `rating-service/.../RatingOutboxRelay.java:54` | Head-of-line break statement stops processing subsequent events on first transient error | p5 |
| H8 | `payment-service/.../WebhookSignatureVerifier.java:18-37` | Signature verifier rejects standard signature format prefixes | p3 |
| H9 | `order-service/.../OrderServiceImpl.java:252-289` | Cancellation of confirmed orders skips release calls for committed stock reservations | p3 |
| H10 | `inventory-service/.../InventoryServiceImpl.java:121-148` | Time-of-check to time-of-use race condition on stock reservation balances | p3, p7 |
| H11 | `payment-service` outbox repository | Missing outbox retention cleanup scheduler | p3 |
| H12 | `order-service/.../OrderStatusServiceImpl.java:19-27` | State machine transition table prohibits cancellation once shipment begins | p3 |
| H13 | `order-service/.../OrderServiceImpl.java:101-156` | Self-invocation of internal method bypasses transactional proxy | p3 |
| H14 | `order-service/.../PricingServiceImpl.java:46-52` | Sequential product price lookups executed in a loop | p3, p7 |
| H15 | `order-service/.../OrderCommitCoordinator.java:28-67` | Sequential HTTP network calls performed inside confirmation transaction | p3, p7 |
| H16 | `product-service/.../CategoryServiceImpl.java:79-92` | Category creation omits maximum depth and cycle checks | p2 |
| H17 | `product-service/.../ProductServiceImpl.java:135,164` | `@CacheEvict(allEntries=true)` risks cache stampedes under high concurrency | p2, p7 |
| H18 | `search-service/.../ProductSearchService.java:47,63` | Indexing exceptions not translated to standard business errors | p2 |
| H19 | `notification-service/.../NotificationServiceImpl.java:38` | Uncaught `IllegalArgumentException` on invalid UUID strings triggers repeated retries | p2 |
| H20 | `product-service/.../ProductController.java:38-48` | Storefront product search omits page size caps | p2, p6 |
| H21 | `product-service/.../ProductServiceImpl.java:72-86` | Storefront product filters do not restrict results to `ACTIVE` items by default | p2 |
| H22 | `notification-service/.../SmtpNotificationSender.java:39` | Mail sender directs notifications to a fallback recipient instead of the target user | p2 |
| H23 | `notification-service/.../NotificationWriter.java:13` | Incorrect `@Repository` stereotype applied to service component | p2 |
| H24 | `search-service/.../ReindexServiceImpl.java:184-199` | Index cleanup routine swallows exceptions silently during obsolete index deletion | p2 |
| H25 | `product-service/.../BackofficeProductController.java:37,43` | Redundant authentication annotations at class and method levels | p2 |
| H26 | `notification-service/.../OrderLifecycleEvent.java:14-34` | Event DTO implemented as mutable Lombok class instead of record | p2 |
| H27 | Payment and shipping webhook controllers | Webhook handling lacks timestamp verification and replay protection | p3, p6 |
| H28 | `gateway-service/.../ClientIpResolver.java:23-37` | Resolves client IP from untrusted first header entry in `X-Forwarded-For` | p1, p6 |
| H29 | `payment-service/.../PaymentRepository.java:15` | Idempotency query lacks user scoping, risking cross-tenant data leakage | p6 |
| H30 | `auth-service/src/main/resources/application.yml:39-40` | Broad wildcard pattern exposes unauthenticated routes under `/api/v1/auth/**` | p6 |
| H31 | `utils/common-storage/.../S3ObjectStorageService.java:110-126` | S3 response stream not closed in try-with-resources block, risking descriptor leaks | p5 |
| H32 | `utils/common-spring/.../ApiExceptionHandler.java:195-208` | Raw database constraint violation details exposed in API response bodies | p5, p6 |
| H33 | `utils/common-security/.../BaseSecurityConfig.java:93-95` | Synchronous OIDC discovery blocks first incoming request on application start | p5 |
| H34 | `docker-compose.yml:124` | Elasticsearch configured with authentication disabled (`xpack.security.enabled=false`) | p6 |
| H35 | Environment configuration files | Enabling raw HTTP body logging risks writing passwords to log files | p6 |
| H36 | Dockerfile configurations | Service containers execute with `root` user privileges | p6 |
| H37 | `search-service/.../SearchQueryServiceImpl.java:97-102` | Deep pagination using offset and limit encounters the 10,000 document limit | p7 |
| H38 | `order-service/.../OrderReconciliationScheduler.java:91-148` | Issues N+1 HTTP calls per pending order without concurrency controls | p7 |
| H39 | `promotion-service` changelog | Missing composite database index on `(campaign_id, status)` | p7 |
| H40 | `payment-service` changelog | Missing composite database index on `(status, next_retry_at)` | p7 |
| H41 | `utils/common-kafka/.../KafkaProperties.java:99-108` | Producer default settings omit batching, linger time, and compression | p7 |
| H42 | `product-service/.../ProductServiceImpl.java:134-135` | Full cache clearing on product updates triggers database cache stampedes | p7 |
| H43 | `notification-service/.../NotificationServiceImpl.java:36-71` | Read-then-insert pattern on event deduplication lacks database constraints | p7 |
| H44 | `utils/common-kafka/.../KafkaMessagePublisher.java:85-98` | Synchronous `future.get(10s)` blocking inside outbox publisher loops | p7 |
| H45 | `inventory-service/.../InventoryServiceImpl.java:123-147` | Reservation cleanup scan executes synchronously on every reservation call | p7 |
| H46 | `order-service/.../OrderReconciliationScheduler.java:181-182` | Database count query executed synchronously on every Prometheus scrape | p7 |
| H47 | `rating-service` changelog | Missing partial index on pending outbox events | p7 |
| H48 | `shipping-service` changelog | Status check constraint rejects `FAILED_RETRYABLE` and `FAILED_PERMANENT` | p4 |
| H49 | `promotion-service` | Campaign reservation calls are not idempotent per order ID | p4, p3 |
| H50 | `tax-service/.../TaxCalculationController` | Mounted under non-standard backoffice route instead of public calculation path | p4 |

---

## 5. Pattern Compliance Summary

| Evaluation Rule | auth + gateway | favourite | product + search + notification | order + payment + inventory | shipping + tax + promotion | rating + common-utils |
|---|---|---|---|---|---|---|
| 1. Layering and Java records | 8 DTOs not records | Compliant | Compliant | Compliant | Mutable POJOs in shipping | Compliant |
| 2. Standard ApiResponse and PageResponse | Compliant | Compliant | Compliant | Compliant | Tax service gap (H5) | Compliant |
| 3. Centralized ApiPaths usage | Hardcoded paths | Compliant | Compliant | Compliant | Non-standard tax path | Compliant |
| 4. Bounded pagination | User query unconstrained | Compliant | Storefront search uncapped | Compliant | Tax list unpaginated | Compliant |
| 5. Storefront public vs Backoffice ADMIN | Role check deviation | Compliant | Violation (C3) | Compliant | Tax calculation path (C5) | Compliant |
| 6. AuthenticatedUser.requireCurrent() | Direct JWT parsing | Compliant | Auditor context gap | Compliant | Not applicable | Compliant |
| 7. Transactional boundaries and soft-delete | RoleService missing | Compliant | Notification entity (C6) | Compliant | Compliant | Compliant |
| 8. ddl-auto validate and Liquibase | Role seed mismatch | Compliant | Compliant | Status check constraint | Status check constraint | Compliant |
| 9. Environment variables and Outbox | Compliant | Compliant | Mail recipient default missing | Outbox retention scheduler missing | Compliant | Compliant |

**Summary of Findings:**
- **favourite-service** represents the cleanest reference implementation, satisfying all structural guidelines.
- **rating-service** and **common-utils** pass all core framework conventions.
- **auth-service** exhibits the highest variance (identity resolution, authorization conventions, hardcoded paths).
- **product-service** shows the most prominent architectural boundary issue (storefront controllers defining admin methods).
- **shipping-service** contains database check constraint mismatches that cause insertion errors under retry flows.

---

## 6. Priority Action Items (Current Sprint)

| Priority | ID | Location | Task Description | Estimated Effort |
|---|---|---|---|---|
| 1 | C1 | `common-security/BaseSecurityConfig.java:93` | Implement JWT audience claim verification | 0.5 days |
| 2 | C8 | `docker/keycloak/import/ecommerce-realm.json` | Rotate static credentials and remove test users from production realm | 1.0 day |
| 3 | C10 | `docker-compose.yml:369,397` | Enforce fail-fast configuration for webhook secrets | 0.5 hours |
| 4 | C4 | `payment-service/changelog-001-payments.yaml:70` | Update payment event status check constraints | 1.0 hour |
| 5 | C3 | `payment-service/application.yml:47` | Fix default payment webhook secret handling | 0.5 hours |
| 6 | C19 | `common-kafka/BaseKafkaListenerConfig.java:79` | Restrict deserializer trusted package scope | 1.0 hour |
| 7 | C20 | `common-kafka/KafkaProperties.java` | Wire dead-letter topic routing into consumer error handlers | 1.0 day |
| 8 | C12, C17 | `notification-service/.../NotificationServiceImpl.java` | Introduce pending status and dead-letter retry tracking | 2.0 days |
| 9 | C2 | `auth-service/UserServiceImpl.java:42` | Remove input logging and sanitize user data logs | 0.5 hours |
| 10 | C13 | `product-service/.../controller/*` | Move admin mutation endpoints to backoffice controllers | 1.0 day |

---

## 7. Strategic Remediation Roadmap

### Sprint N+1: Architectural Hardening
- **T2 (Distributed Outbox):** Introduce `SELECT ... FOR UPDATE SKIP LOCKED` and version fields across outbox tables.
- **T4 (Connection Hygiene):** Reorganize transaction boundaries so remote network exchanges execute outside database transactions.
- **T5 (Kafka Hardening):** Tune producer throughput settings, bind dead-letter queues, and restrict deserializer packages.
- **T3 (Webhook Integrity):** Implement timestamped HMAC signatures for payment and shipping integrations.

### Sprint N+2: Code Quality and Pattern Alignment
- **T9 (Path Standardization):** Migrate all controller request mappings to shared `ApiPaths` constants.
- **T6 (Configuration Safety):** Add fail-fast checks for all sensitive environment variables across production profiles.
- **DRY Remediation:** Consolidate duplicate implementations of token providers, event publishers, and listener configurations.

### Sprint N+3: Performance Tuning
- **H37-H47:** Add missing composite indexes, tune connection pool allocations, and refine pagination queries.
- **H22:** Refactor Elasticsearch alias swap operations to avoid concurrent update races.
- **H49:** Enforce order-scoped idempotency keys on promotion reservation requests.

---

## 8. Fleet Strengths Noted

1. Consistent adoption of the transactional outbox pattern across event-producing services.
2. Robust singleton container management across integration test suites using Testcontainers.
3. Automated regression tests verifying cache serialization safety.
4. Constant-time HMAC comparison and input length validation in webhook signature verifiers.
5. Strict status transition validation and optimistic locking in the payment state machine.
6. Clean separation between backoffice and storefront operations in payment controllers.
7. Clear and comprehensive API documentation throughout inventory-service.
8. Model implementation quality demonstrated by favourite-service.
9. Full adherence to framework conventions in rating-service and shared utilities.
10. Consistent method-level security enforcement across core authentication paths.

---

## 9. Systemic Gaps and Fleet-Wide Challenges

1. Duplication of infrastructural boilerplate: client token providers, transactional outbox publishers, and listener configs.
2. Insecure fallback values: default secrets, mock providers enabled by default, and shared consumer group names.
3. Database connections held across synchronous HTTP calls across multiple microservices.
4. Outbox implementations vulnerable to duplicate delivery when scaled to multiple pods.
5. Incomplete state transitions that mark records completed before downstream confirmation.
6. Bypassing security filter chains in test suites via `addFilters=false`.
7. Discrepancies in idempotency scoping across order and payment services.
8. Unwired dead-letter queue configurations in shared message consumer templates.

---

**Final Readiness Summary:** The platform is evaluated at approximately **65% production readiness**. Resolving the top critical items will establish the baseline reliability required for production deployment:
- Six critical security vulnerabilities (C1, C2, C3, C8, C9, C10).
- Three critical data integrity issues (C4, C12, C17).
- Three functional defects in payment and order processing (C5, C6, C7).

Estimated remediation time: two development sprints with two to three engineers to achieve full production readiness.
