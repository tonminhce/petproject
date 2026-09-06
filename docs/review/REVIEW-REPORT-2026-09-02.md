# Consolidated Code Review Report: Petproject Platform (Fleet-wide, excluding media-service)

- **Date:** 2026-09-02
- **Methodology:** Nine parallel review passes grouped by domain (product, order, payment and promotion, shipping and tax, inventory and rating, search and notification, auth and favourite and gateway, common-utils, infrastructure and configuration). Baseline patterns were verified against codebase standards prior to consistency checks. Security rubric: concrete attack vectors only, confidence >= 8/10.
- **Detailed Module Reports:** Available in `docs/review/{product-service,order-service,payment-promotion,shipping-tax,inventory-rating,search-notification,auth-favourite-gateway,common-utils,infra-config}.md` (containing full descriptions and line-level fix recommendations).
- **Scope of this Document:** Consolidated overview covering deduplication, risk prioritization, and system-wide root causes.

## 1. Metrics Overview

| Module | Scope | File Count | Critical | High | Medium | Low |
|---|---|---|---|---|---|---|
| product-service | service and tests | 83 | 1 | 1 | 7 | 9 |
| order-service | service and tests | 90 | 0 | 1 | 4 | 19 |
| payment + promotion | 2 services | 99 | 1 | 1 | 7 | 16 |
| shipping + tax | 2 services | 95 | 0 | 0 | 10 | 21 |
| inventory + rating | 2 services | 80 | 0 | 0 | 11 | 20 |
| search + notification | 2 services | 69 | 0 | 0 | 10 | 16 |
| auth + favourite + gateway | 3 components | 95 | 3 | 5 | 13 | 19 |
| common-utils (7 libs) | 7 shared utility libraries | 115 | 0 | 4 | 23 | 26 |
| infra & config | pom, compose, docker, scripts | ≈21 | 0 | 2 | 14 | 20 |
| **Total** | | **≈747 passes** | **5** | **14** | **99** | **166** |

> Total **284 findings**. After deduplication: 5 Critical instances represent **4 root causes** (privilege escalation spans two locations), while 14 High instances represent **13 root causes** (realm credentials appeared in both auth and infra reports).

## 2. Critical Findings: 4 Root Causes

### C1. ADMIN Privilege Escalation via Public Registration: auth-service
- **Location:** `dto/request/RegisterRequest.java:43` and `service/impls/UserServiceImpl.java:141-150,169-172`
- **Description:** The `private Set<String> roles;` field is supplied directly by the client without validation or allowlisting. It is passed straight into Keycloak (`KeycloakAdminClient.assignRealmRoles` performs no filtering) and the database (stored as an enum). Because `POST /api/v1/auth/sign-up` is unauthenticated, any client can send `{"roles":["ADMIN"], ...}` to acquire the `ADMIN` realm role in Keycloak (`realm_access.roles=ADMIN`). This passes the gateway `AdminRoleGateFilter` and any downstream `@PreAuthorize("hasAuthority('ADMIN')")` checks.
- **Recommendation:** Strip `roles` entirely from the registration DTO and default all new signups to `USER`. Role assignments must only occur via `RoleService.assignRole` (which is gated by ADMIN permissions). If the field is retained for testing, enforce an allowlist and uppercase conversion. Add integration tests verifying that registration requests containing `roles=[ADMIN]` are rejected (no tests currently cover this).

### C2. Plaintext Password and PII Logged at INFO Level: auth-service
- **Location:** `service/impls/UserServiceImpl.java:43`
- **Description:** `@LogPerformance(title = "Register user", logInput = true)` triggers `LoggerAspect` from common-logging to print `RegisterRequest.toString()` at INFO level whenever execution time exceeds 50ms. User registration makes multiple outbound HTTP calls to Keycloak, so execution time consistently exceeds this threshold. As a result, plaintext passwords, email addresses, and phone numbers are written directly to stdout and log aggregators.
- **Recommendation:** Remove `logInput = true`, or introduce sensitive field masking in the `stringify` method of common-logging.

### C3. Failed Payment Webhooks Never Reprocessed: payment-service
- **Location:** `WebhookEventService.java:41`
- **Description:** The deduplication check `existsByProviderAndProviderEventId` returns immediately regardless of the event status. Any exception during `process()` (including transient connection errors between `insertEvent` and `completeWithEvent`, or `OptimisticLockingFailureException`) marks the event as `FAILED` and returns HTTP 200. When the payment provider redelivers the event, the deduplication check silently skips it. Consequently, state transitions are permanently lost on payment flows (for instance, a transient refund failure leaves a payment stuck in `CAPTURED`, causing customer overcharges without automated recourse).
- **Recommendation:** Deduplicate only when `status == PROCESSED`. Allow `FAILED` events to be retried (the underlying payment state machine protects against duplicate transitions), or transition them to a retryable `PENDING` state picked up by an automated reaper. Separate permanent business errors (such as amount mismatches or invalid states) from transient network failures.

### C4. Serialization Wire Format Mismatch Breaking Rating Synchronization: product-service (root cause in common-kafka)
- **Location:** `kafka/RatingLifecycleListenerConfig.java:12-22` and `ProductRatingConsumer.java:18` (caused by `JsonKafkaSerializer` in common-kafka)
- **Description:** Rating-service publishes its outbox payload as a JSON string through `JsonKafkaSerializer`, resulting in double-encoding (validated in `MediaDeletedConsumerIT.wireShapeIsDoubleEncodedOnTheRealBroker`). `RatingLifecycleListenerConfig` binds using Spring Kafka typed `JsonDeserializer` without unwrapping string tokens. Consequently, every message on `shop.rating.lifecycle.v1` fails deserialization with a type mismatch error. `avgRating` and `ratingCount` on products are never updated, while the rating-service outbox marks the records as `SENT`, causing silent data loss without retries.
- **Recommendation:** Switch `ratingListenerFactory` to `StringDeserializer` and manually unwrap the payload inside `ProductRatingConsumer` (mirroring `MediaDeletedConsumer.decode`, where `MediaLifecycleListenerConfig.java:14-24` already documents this exact issue for media events). Include an integration test verifying the wire format. See R1 below for the core serializer fix.

## 3. High Findings: 14 Locations (13 Root Causes After Deduplication)

| ID | Location | Issue | Recommended Fix |
|---|---|---|---|
| H1 | product `CategoryServiceImpl.java:104-108` | Missing parent cycle detection causes infinite recursion in `findTree()`, triggering 500 errors on public `/api/v1/categories/tree` | Enforce `parentId != id`, validate the full ancestor chain, and add a visited set guard inside `findTree` |
| H2 | order `kafka/ShippingDeliveredConsumer.java:24-30` | Broad exception handler catches all errors and commits offsets, silently dropping `shipping.delivered` events on transient failures | Rethrow exceptions to trigger Spring Kafka retries with a `DefaultErrorHandler` and dead-letter topic, or add metrics and a reconciliation scanner for stale `SHIPPED` orders |
| H3 | payment `PaymentServiceImpl.java:44-63` | `capture()` and `refund()` ignore the `ProviderResult` (`accepted` field is dead code), skipping state transitions and persistence; reuses a single idempotency key for both actions, risking duplicate refunds without audit traces | Add intermediate states (`CAPTURE_REQUESTED` and `REFUND_REQUESTED`) or persist `PaymentEvent` records; store `providerEventId`; scope idempotency keys per operation (append `-capture` or `-refund`); check `accepted` |
| H4 | auth `UserController.java:40,54,62` and `UserServiceImpl.java:211-222` | Identity mismatch on `/me` endpoints: parsing `jwt.getSubject()` (Keycloak UUID) as a local database ID causes all four `/me` endpoints to return 404 for valid tokens (tests masked this using `sub="alice"`) | Query via `keycloakUserId` or use `JwtClaimExtractor.username()` and `subject()`; update test fixtures to use realistic Keycloak tokens |
| H5 | auth `UserServiceImpl.java:88-94` | Soft delete removes only the local record without disabling the account or terminating sessions in Keycloak; deleted accounts remain fully capable of logging in and invoking APIs | Invoke `KeycloakAdminClient` to disable the user and invalidate sessions on delete; re-enable on restore |
| H6 | auth `RoleServiceImpl.java:68-75` | Missing `@Transactional(readOnly = true)` combined with `open-in-view: false` throws `LazyInitializationException` on lazy role collections, causing `GET /api/v1/roles/users/{id}` to return 500 | Add `@Transactional(readOnly = true)` or fetch roles eagerly in the repository query |
| H7 | auth `filter/ClientIpResolver.java:22-28` | Reads the first entry in client-supplied `X-Forwarded-For` headers without validation; attackers can spoof allowlisted CIDR IPs to bypass the deny-by-default gateway filter | Resolve IP addresses using trusted proxy hop counts (`XForwardedRemoteAddressResolver`, consistent with `RateLimitKeyResolver`) |
| H8 | infra and auth `docker/keycloak/import/ecommerce-realm.json:64,77,90,103,127,136` | Realm import file used in development and production contains plaintext credentials in git: client secrets set to `changeme`, plus default user credentials `adminuser/adminpass` and `testuser/testpass` | Rotate credentials during deployment via environment variables and admin APIs; strip user passwords from realm exports; add startup probes that reject default credentials in non-dev environments |
| H9 | infra `docker-compose.yml:628` | `notification-service` is missing the `*pg-creds` anchor, falling back to `${POSTGRES_USER:admin}`; rotating production passwords breaks notification-service silently | Attach the `*pg-creds` anchor to the container stanza; remove the `:admin` fallback from `application.yml` |
| H10 | common-kafka `BaseKafkaListenerConfig.java:79` | `addTrustedPackages("*")` disables package filtering on `JsonDeserializer`; untrusted topic messages with malicious `__TypeId__` headers can trigger arbitrary deserialization gadget chains | Restrict trusted packages to `com.shop.*`, or disable type headers via `setUseTypeHeaders(false)` so each listener deserializes a fixed event class |
| H11 | common-kafka `KafkaAutoConfiguration.java:31-36` | Pre-configured `JsonKafkaSerializer` instance is discarded; only its class name is passed into configuration properties, causing Kafka to construct a raw `new ObjectMapper()` lacking `JavaTimeModule`; events containing `Instant` fail serialization and trigger H12 | Instantiate `DefaultKafkaProducerFactory` passing the configured serializer instance directly |
| H12 | common-kafka `JsonKafkaSerializer.java:32-43` | When Jackson serialization fails, the serializer silently returns `data.toString().getBytes()`, publishing corrupt strings like `ProductEvent@13f2e` to the broker | Throw a `SerializationException` containing topic and payload type details; never fall back to string representations |
| H13 | common-spring `CommonProperties.java:58-186` | Aggregate configuration properties under `shop.common.*` are never read by production code; toggles like `shop.common.security.enabled=false` have no effect because services check `shop.security.enabled` | Wire properties into actual `@ConditionalOnProperty` annotations or remove the redundant record |

## 4. Architectural Root Causes Across Modules

Addressing the following root causes resolves multiple findings across services simultaneously:

- **R1: Kafka Serializer Chain (common-kafka):** Issues H10, H11, and H12 combine to publish corrupted records. Finding C4 (rating) and shipping medium issues (double-encoded outbox strings) stem directly from this defect. The fix requires passing the configured serializer instance (`JavaTimeModule` enabled), throwing exceptions on failure instead of falling back to string coercion, restricting trusted packages, and ensuring producers publish typed objects rather than pre-serialized strings.
- **R2: Silent Event Loss and Missing Dead Letter Queues:** Findings C3, H2, payment webhook ack-on-error, shipping webhook dropped events, unconfirmed notification delivery, and search consumer unconditional acks all lack retries, dead letter topics, reconciliation scans, or delivery metrics. Standardize error handling in `BaseKafkaListenerConfig` with dead-letter topic routing, and only acknowledge external webhooks after persisting state.
- **R3: Fragmented User Identity (Keycloak Subject vs Local UUID):** Auth `/me` endpoints (H4) and favourite-service store Keycloak subject UUIDs, whereas auth-service models use local auto-generated UUIDs. Test fixtures (`sub="alice"`) masked this incompatibility. Establish Keycloak subject as the canonical identity across the fleet, retaining local IDs only as internal surrogate primary keys.
- **R4: Inconsistent Authorization Implementation:** Auth-service relies on `@PreAuthorize` and manual JWT subject parsing instead of the standard `AuthenticatedUser.requireCurrent()` helper. JWT decoders omit audience validation (`aud` claim in `BaseSecurityConfig.java:93`), CORS configurations allow wildcard origins with credentials, client IP allowlists can be spoofed (H7), and public registration endpoints accept client-supplied roles (C1).
- **R5: Production Fallbacks to Default Credentials:** Realm configurations (H8), missing compose anchors (H9), Keycloak running in `start-dev` mode, default mock payment providers, default database credentials, and unauthenticated infrastructure ports bound to `0.0.0.0` create systemic security risks. Enforce a fail-fast startup check that refuses to launch in non-development environments if default credentials remain active.
- **R6: Long-Running Database Transactions Holding Connections Across Network I/O:** Rating `submit()` invokes HTTP eligibility checks inside `@Transactional` blocks; product `update()` makes HTTP HEAD calls to verify media within active write transactions. This exhausts HikariCP connection pools under load. Remote network operations must execute outside database transaction boundaries.
- **R7: Error Handling Leaks and Duplicated Client Seams:** `ServiceTokenProvider.getToken()` throws `IllegalStateException` rather than `RestClientException`, escaping fail-closed catch blocks and returning raw 500 errors (duplicated across search and rating clients). `ApiExceptionHandler` exposes raw SQL constraint violation messages directly to API callers, and broad exception handlers misattribute database errors to duplicate event IDs.
- **R8: Missing Intermediate Lifecycle States:** Payment capture and refund flows, notification records marked `SENT` before transmission, and automated shipping delivery without carrier confirmation all record terminal states prematurely. Introduce explicit pending and requested states before calling external providers.
- **R9: False Sense of Test Coverage:** Slice tests in product-service and auth-service disable filters (`addFilters=false`), leaving authorization logic untested. Integration tests codify anti-patterns (such as verifying that consumers swallow exceptions), test suites share dirty database state across runs, and fixtures use invalid identity values.
- **R10: Hazardous Default Configurations in Shared Libraries:** A common consumer group ID (`shop-service`) paired with `auto.offset.reset=earliest` creates partition stealing risks across services. Retry properties are defined without being wired, common-spring exports Swagger UI to all 13 services (requiring public path exemptions), and dependency versions override parent declarations unpredictably.

## 5. Pattern Compliance by Module

| Module | Status and Evaluation |
|---|---|
| product-service | High compliance (clean records, MapStruct mappers, outbox pattern, validated application configuration). Minor gaps: storefront controllers omit page size caps present in backoffice routes; parent category hierarchy lacks cycle checks. |
| order-service | High compliance. Main weaknesses lie in compensation error handling (catches only a single exception type) and Redis cache availability becoming a hard dependency in checkout paths. |
| payment + promotion | Follows architecture patterns well, but lifecycle state machines need hardening around webhook replay, capture and refund coordination, and mock provider production safeguards. |
| shipping + tax | Adheres to core patterns; contains minor duplication in status transition code. Needs robust carrier integration adapters instead of direct enum parsing on webhooks. |
| inventory + rating | Compliant overall. Issues center on stock boundary checks (preventing negative available stock), delete race conditions, and transaction boundaries in scheduled tasks. |
| search + notification | Clean error contracts on paper, but fragile at token provider boundaries. Test suites exhibit execution order dependencies. |
| auth + favourite + gateway | Widest pattern deviation: conflicting identity models, non-standard `@PreAuthorize` usage, hardcoded API paths, and manual JWT parsing. Requires targeted remediation given that it handles authentication secrets. |
| common-utils | Serves as the fleet foundation but carries high technical debt: unsafe defaults (shared group IDs, fallback credentials), unwired configuration records, and duplicate outbox implementations. High return on investment: fixes here resolve issues across all 13 services. |
| infra & config | Functional for local development (~6/10 readiness for production). Clean Docker Compose anchor reuse and comprehensive container healthchecks, but vulnerable to configuration drift and shared default credentials between environments. |

**Fleet Strengths Noted:** Unified `ApiResponse` and `PageResponse` envelopes across all microservices; outbox pattern implemented on every event-publishing service; environment variable substitution with sensible local fallbacks; healthcheck-driven startup dependencies across 21 containers; defensive shell automation with strict error flags (`set -e`) and destructive operation confirmations; and clear operational runbooks in `docs/PRODUCTION-READINESS.md`.

## 6. Medium Findings by Module (99 Total)

### product-service (7)
| Location | Summary |
|---|---|
| `ProductController.java:46`, `BrandController.java:37` | Storefront endpoints omit `MAX_PAGE_SIZE` constraints enforced in backoffice controllers |
| Test suite | Missing test coverage for ADMIN-gated write endpoints due to `addFilters=false` |
| `CategoryServiceImpl.java:114-123`, `BrandServiceImpl.java:82-91` | Deletion omits dependency verification, risking orphaned category subtrees and unlinked brands |
| `TransactionalProductEventPublisher.java:93` | `updatedAt` timestamp is stale in update and delete events because messages publish before database flush |
| `ProductServiceImpl.java:179-183` | Issues HTTP HEAD requests to media-service while holding an open database transaction |
| Outbox publishers | Outbox implementation boilerplate duplicated across three repositories |
| `changelog-004-media-reference.yaml:16` | Missing index on `media_id` causes sequential scans on every incoming `MediaDeleted` event |

### order-service (4)
| Location | Summary |
|---|---|
| `InventoryServiceClient.java:55-65`, `OrderServiceImpl.java:159-184` | Compensation logic catches only `StockReservationFailedException`, letting 5xx network errors leak stock |
| `ProductServiceClient.java:36-50` | `@Cacheable` methods lack a custom `CacheErrorHandler`, causing Redis outages to fail checkout flows |
| `OrderServiceImpl.java:263-289` | Untested race condition between order cancellation and confirmation |
| `OrderOutboxRelay` | Outbox message relay lacks test cases covering publishing failure branches |

### payment + promotion (7)
| Location | Summary |
|---|---|
| `WebhookEventService.java:56-59` | Catches general exceptions and returns HTTP 200, preventing provider redelivery on internal failure |
| `PaymentServiceImpl.java:31-41` | Reusing idempotency keys with modified request payloads silently returns cached payments |
| `application.yml:36`, `MockProvider.java:10` | `PAYMENT_PROVIDER` defaults to mock with `matchIfMissing=true`, risking unintended mock use in production |
| `OutboxEventRepository.java:17-19` | Missing retention cleanup scheduler causes outbox table to grow indefinitely |
| `PaymentOutboxRelay.java:29-57` | Head-of-line blocking error branch lacks automated test verification |
| `CampaignReservationServiceImpl.java:102-111` | `reserve()` is not idempotent per order ID, throwing constraint violations on retries |
| `ReservationCleanupScheduler.java:68-75` | Comment misstates transaction boundaries regarding batch rollback behavior |

### shipping + tax (10)
| Location | Summary |
|---|---|
| `ShippingEventPublisherImpl.java:39-62`, Outbox Relay | Payload strings double-encoded when serialized to Kafka |
| `ShippingOutboxRelay.java:54` | Head-of-line break statement lacks an explanatory comment |
| `OutboxEventRepository.java:17-19` | Missing outbox retention scheduler leads to index degradation |
| `ShipmentServiceImpl.java:69-71` | Catches broad `DataIntegrityViolationException`, masking genuine persistence errors |
| `ShipmentServiceImpl.java:149-164`, Webhook Service | Status transition logic duplicated between admin controllers and webhook listeners |
| `ShipmentServiceImpl.java:121` | Duplicate tracking numbers return 500 instead of 409 Conflict |
| `WebhookEventServiceImpl.java:91-101` | Valid webhooks referencing unknown tracking numbers return 200 and stay permanently `FAILED` without retries |
| `WebhookEventServiceImpl.java:105-106` | Uses `ShipmentStatus.valueOf` without lenient mapping, failing on unexpected carrier status strings |
| `ReconciliationScheduler.java:45-53` | Automatically marks shipments `DELIVERED` after 7 days without delivery confirmation; 72-hour threshold is dead code |
| `TaxRateRequest.java:13` | Country code `@Pattern` lacks `@NotNull`, allowing null values to bypass validation and trigger 500s |

### inventory + rating (11)
| Location | Summary |
|---|---|
| `InventoryServiceImpl.java:88` | Allows `available < reserved` without database check constraints, enabling negative stock |
| `InventoryServiceImpl.java:106` | Race condition on stock deletion and missing foreign key on `reservations.product_id` |
| `InventoryServiceImpl.java:152-239` | Audit logs omit specific item quantities when recording reservation commit and release actions |
| `ReservationServiceImpl.java:31-91` | Redundant retry wrapper methods duplicated four times without shared logic |
| `ReservationCleanupScheduler.java:74` | Javadoc comment inverts actual transaction boundary semantics |
| `ReservationCleanupScheduler.java:100-110` | Retention scheduler purges only `EXPIRED` rows; `RELEASED` and `COMMITTED` records accumulate forever |
| `RatingServiceImpl.java:35-64` | Remote HTTP eligibility call executes inside an active database transaction |
| `RatingServiceImpl.java:37-39` | Concurrent duplicate rating submissions return 500 instead of 409 `RTG-11005` |
| `RestClientConfig.java:53-56` | Token acquisition client lacks connection timeouts, risking thread pool exhaustion on Keycloak latency |
| `EligibilityClient.java:57,64` | `IllegalStateException` bypasses error mapping to return raw 500 responses |
| `OutboxEventRepository.java:19` | Unused outbox retention method leaves records unpurged |

### search + notification (10)
| Location | Summary |
|---|---|
| `ProductBackofficeClient.java:70-73` | `IllegalStateException` escapes error handler, returning raw 500 instead of `SRH-12002` |
| `ProductSearchConsumer.java:51-53` | Unconditional message acknowledgment drops events silently when Elasticsearch is unreachable |
| `ReindexServiceImpl.java:71-84` | Race condition between background reindexing and live event consumers causes stale data after index swap |
| `ReindexServiceImpl.java:137` | Uncaught `NumberFormatException` during index version parsing returns 500 |
| `SearchIndexProvisioningIT` | Test order dependency across three integration tests sharing dirty cluster state |
| `BackofficeNotificationController.java:33-35` | Pagination parameters lack lower bound validation, returning 500 on negative inputs |
| `NotificationServiceImpl.java:58-60` | Broad catch block labels all data integrity violations as duplicate events |
| `NotificationServiceImpl.java:38` | Malformed UUID strings throw unhandled exceptions, causing 9 rapid retries before dropping the event |
| `NotificationServiceImpl.java:49,66` | Records marked `SENT` before physical dispatch; at-most-once delivery semantics not documented |
| `NotificationBootstrapIT:55` | Assertions assume an empty database, causing intermittent test failures |

### auth + favourite + gateway (13)
| Location | Summary |
|---|---|
| `RegisterRequest.java:28-31` | Email field lacks `@NotBlank`; self-registered accounts are set to `emailVerified=true` automatically |
| `UserServiceImpl.java:49-55` | Compensation logic does not guard commit-time flushes, risking orphaned Keycloak accounts |
| `UserServiceImpl.java:141-150,224-229` | `KeycloakClientException` unhandled by exception handler, producing unformatted 500 errors |
| `UserServiceImpl.java:60-65,187-203` | User profile updates do not re-verify email and phone uniqueness before save |
| `UserServiceImpl.java:118-123`, Controller | Omission of max page size limits; unvalidated `sortBy` strings trigger `IllegalArgumentException` |
| Controllers (3) | Hardcoded API path strings; non-standard `@PreAuthorize` usage; manual parsing of JWT subject claims |
| `SecurityFilterChainIntegrationTest:174-185` | Test fixture hardcoding `sub="alice"` masks `/me` endpoint 404 defects |
| Slice test suites (3) | `addFilters=false` leaves role verification and authorization paths untested |
| `FavouriteServiceImpl.java:48-58` | Concurrent favourite creation returns generic conflict instead of domain error `FAV-6002` |
| `FavouriteServiceImpl.java:31-38` | Stores Keycloak subject UUIDs while auth-service stores local UUIDs, preventing cross-table joins |
| Gateway `application.yml:38-39` | Wildcard CORS origin configured alongside `allowCredentials=true` |
| `RateLimitFilter.java:49,75-77` | In-memory token bucket map lacks eviction, leaking memory as unique client IPs increase |
| Rate-limiting architecture | Three overlapping rate-limiting layers using two different IP resolution mechanisms |

### common-utils (23)
| Location | Summary |
|---|---|
| `common-core/pom.xml:46-48` | `starter-data-jpa` compiled in core contract library, leaking Hibernate into non-relational services |
| `BaseSecurityConfig.java:93-95` | JWT decoder omits `aud` validation, allowing tokens from any realm client across the fleet |
| `SecurityProperties.java:102-106` | Default CORS configuration permits wildcard origins with credentials enabled |
| `AuthenticatedUser.java:38-49` | `from(jwt)` extracts authorities from the current thread SecurityContext instead of the token |
| common-security test suite | Zero automated test coverage for public path matchers, authority mapping, or CORS filters |
| `KafkaProperties.java:209-210,100-118` | Shared consumer group ID (`shop-service`) and default `earliest` offset configuration across services |
| `KafkaProperties.java:160-183` | Retry configuration properties defined but never bound to listener factories |
| `JsonKafkaDeserializer.java:10-14` | Dead code containing inaccurate class-level Javadoc comments |
| `KeycloakTokenClient.java:146-153` | Upstream 5xx errors and timeouts reported to users as invalid credential failures |
| `KeycloakAdminClient.java:200-237` | `assignRealmRoles` swallows per-role assignment errors, masking authorization failures |
| `KeycloakAutoConfiguration.java:64-76` | Configures admin client with default credentials (`admin/admin`) across all services |
| `KeycloakAdminClient.java:74,118,145` | Obtains fresh admin tokens on every operation instead of caching the access token |
| common-keycloak | Zero automated test coverage for external Keycloak client HTTP communications |
| `CommonLibraryStarter.java:29-31` | `@SpringBootApplication` and `main()` method embedded inside a reusable starter library |
| `common-spring/pom.xml:46-116` | Starter pulls transitive dependencies (Swagger UI, duplicate mapper libraries) into all services |
| `ApiExceptionHandler.java:195-208` | Returns raw database constraint error messages directly to API callers |
| `HttpLoggingFilter.java:117-122` | Logs raw query string parameters, risking exposure of sensitive tokens |
| common-spring | Zero unit test coverage for central exception handling logic across services |
| Traceparent injection | Propagation logic duplicated across common-spring, common-kafka, and common-keycloak |
| `StorageProperties.java:39-43` | Hardcoded default storage credentials (`rustfsadmin/rustfsadmin`) in library source |
| `ObjectStorageAutoConfiguration.java:43-50` | `autoCreateBucket=true` triggers application startup failure when object store is not yet ready |
| common-storage | Lacks automated tests for pre-signed URL generation, missing files, or bucket creation |

### infra and config (14)
| Location | Summary |
|---|---|
| `pom.xml:73,187-190` | Explicit `starter-aop` dependency pinned to milestone `4.0.0-M2`, overriding parent `4.1.1` |
| `pom.xml:347-366` | Missing container memory limits (`MaxRAMPercentage` and `mem_limit`) can lead to host memory exhaustion |
| `pom.xml:349-366` | All 14 microservice Docker images execute as the `root` user inside containers |
| `docker-compose.yml:52-53,71-72,90-91,126-127,150-152` | Five infrastructure ports exposed on `0.0.0.0` with default passwords (Postgres, Kafka PLAINTEXT) |
| `docker-compose.yml:25-28,45-63`, init SQL | 12 service databases and Keycloak share a single database superuser |
| `docker-compose.yml:174` | Keycloak `start-dev` mode configuration inherited by production deployment overlay |
| `docker-compose.yml:392-401`, prod overlay | Mock payment provider defined in base compose and active by default via `PAYMENT_PROVIDER=mock` |
| `docker-compose.prod.yml` | Eight services write concurrently to a single shared `audit.jsonl` file without log rotation |
| `ecommerce-realm.json:35-48` | Production client configuration retains `publicClient` settings and `localhost:*` redirect URIs |
| `docker-compose.prod.yml:56`, env | Empty `ADMIN_IP_ALLOWLIST` defaults to allowing all traffic rather than failing closed |
| `start-docker.sh:211,221-226` | Scripts display incorrect Keycloak host port (8080 instead of 9090) and echo default passwords |
| `mock-payment-provider/server.js:22-44,58` | Mock provider webhooks operate fire-and-forget without retries on delivery failures |
| `docker-README.md:29,78` | Documentation repeats the incorrect Keycloak host port mapping |
| `README.md:12` | References Kafka 4.x while Compose defines Kafka 3.9.0 |

**Low Severity Findings (166 Total):** Documented across module review reports. Covers non-enforced checkstyle configurations, unused `${revision}` properties, Redis credentials exposed in process command lines, container count mismatches in documentation, and unused environment variables.

## 7. Remediation Roadmap

- **P0: Immediate Action (Current Sprint):** Fix C1 (registration privilege escalation), C2 (password logging), C3 (failed webhook retry handling), H8 and H9 (realm credentials and notification service compose anchor), H3 (duplicate refunds), H2 (dead-letter queue for shipping consumer), and C4 (product-service rating consumer deserialization).
- **P1: High Priority (Next Sprint):** Resolve R1 (harden common-kafka serializer chain H10, H11, and H12), H4 and H5 (correct `/me` identity lookups and disable users in Keycloak on deletion), H7 (secure client IP resolution), H6 (add missing `@Transactional`), R7 (handle `ServiceTokenProvider` runtime errors and mask SQL messages), and introduce connection timeouts for identity clients.
- **P2: Medium Priority (Subsequent Sprints):** Complete R5 (fail-fast on default credentials, run Keycloak in production mode, isolate mock payment profiles, bind infrastructure ports to loopback), implement outbox retention schedulers for payment, shipping, and rating services, create dedicated database users per service, add database check constraints on inventory balances, and switch container images to non-root execution.
- **P3: Technical Debt Remediation:** Unify the user identity model (R3), eliminate `addFilters=false` from integration tests, remove dead configuration classes (`CommonProperties`, `JsonKafkaDeserializer`), standardize pagination limits and sorting whitelists, consolidate rate-limiting filters, and align documentation versions.

---

*For detailed findings, file locations, line numbers, and specific code recommendations, refer to the individual module reports in `docs/review/`. This document consolidates findings from all nine parallel review streams.*
