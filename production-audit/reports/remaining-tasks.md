# Remaining Tasks

Generated: 2026-11-25T02:55:41Z

- Registered tasks: 268

| State | Count |
| --- | --- |
| BLOCKED | 0 |
| DISCOVERED | 8 |
| FAILED_VERIFICATION | 0 |
| IMPLEMENTED | 0 |
| IN_PROGRESS | 0 |
| NORMALIZED | 0 |
| READY | 172 |
| REOPENED | 0 |
| REQUIREMENT_CONFLICT | 0 |
| VERIFIED | 88 |
| VERIFYING | 0 |

## Open tasks by severity

| ID | Severity | Area | Title |
| --- | --- | --- | --- |
| CAT-03 | MEDIUM |  | Mutable, non-volatile pagination state on a singleton scheduled bean |
| CAT-04 | MEDIUM |  | Money stored as an IEEE-754 double in the search index |
| CAT-08 | MEDIUM |  | Reindex lock is in-process only |
| CAT-09 | MEDIUM |  | Product outbox claim lease piggy-backs on the `updated_at` audit column |
| CAT-10 | MEDIUM |  | `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging) |
| CAT-11 | MEDIUM |  | No concurrency admission control on the media decode path |
| CAT-13 | MEDIUM |  | No Elasticsearch mapping-version gate |
| CAT-16 | MEDIUM |  | Reservation gates are an if/else chain |
| CAT-17 | MEDIUM |  | `FAILED` media outbox rows are re-published every 2 seconds for 7 days |
| CAT-18 | MEDIUM |  | Inconsistent event-naming convention across producers |
| CAT-19 | MEDIUM |  | The rating aggregate snapshot can be stale under concurrent reviews |
| CAT-23 | MEDIUM |  | No retention purge for the `notifications` table |
| GW-06 | MEDIUM |  | Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget |
| GW-08 | MEDIUM |  | `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced |
| GW-09 | MEDIUM |  | The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode |
| GW-10 | MEDIUM |  | The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop |
| GW-11 | MEDIUM |  | The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive |
| GW-12 | MEDIUM |  | The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster |
| GW-13 | MEDIUM |  | Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run) |
| GW-26 | MEDIUM |  | Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part) |
| GW-27 | MEDIUM |  | No circuit breaker and no bulkhead anywhere in the gateway |
| GW-29 | MEDIUM |  | INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter |
| K8S-24 | MEDIUM | kubernetes | No service account is declared for the platform workloads |
| K8S-25 | MEDIUM | kubernetes | The Kubernetes deployment set covers only a subset of the fourteen platform services |
| KAF-03 | MEDIUM |  | `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling |
| KAF-04 | MEDIUM |  | `shop.kafka.retry.*` is bindable but has zero effect |
| KAF-05 | MEDIUM |  | product-service's media listener has no error handler, hence no DLT |
| KAF-06 | MEDIUM |  | No topic provisioning anywhere in the repo |
| KAF-07 | MEDIUM |  | product-service and payment-service use the generic `updated_at` column as the claim lease |
| KAF-08 | MEDIUM |  | search-service's DLT depends on an optional bean and fails open |
| KAF-09 | MEDIUM |  | notification-service's dedup key silently depends on the event id being a parseable UUID |
| KAF-20 | MEDIUM |  | The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket |
| OBS-04 | MEDIUM | harness | Expose the compose, load and stripe harness command families required by the operating contract |
| OI-03 | MEDIUM |  | A failure of the saga's final transaction leaks every stock reservation |
| OI-04 | MEDIUM |  | `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order |
| OI-05 | MEDIUM |  | The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none |
| OI-06 | MEDIUM |  | The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim |
| OI-07 | MEDIUM |  | The reconciliation scheduler bypasses the order state machine |
| OI-08 | MEDIUM |  | The reconciliation auto-confirm bypasses the captured-payment gate |
| OI-09 | MEDIUM |  | `Cart` has no `@Version`: the cart subtotal is a lost update |
| OI-10 | MEDIUM |  | `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction |
| OI-11 | MEDIUM |  | `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act |
| OI-12 | MEDIUM |  | The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable |
| OI-13 | MEDIUM |  | The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing |
| OI-14 | MEDIUM |  | The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write |
| OI-28 | MEDIUM |  | INFO — YAML/code drift on three scheduler knobs |
| OI-29 | MEDIUM |  | INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table |
| RES-01 | MEDIUM |  | `spring-boot-starter-aop` is pinned to a 4.0.0-M2 milestone while the project runs Boot 4.1.1, and the artifact was renamed in Boot 4. |
| RES-02 | MEDIUM |  | `spring-boot-starter-web` is used in three modules although Boot 4 deprecated it in favour of `spring-boot-starter-webmvc`. |
| RES-03 | MEDIUM |  | Jackson 2 and Jackson 3 are both on the compile classpath while Boot 4 makes Jackson 3 the preferred JSON library; 93 source files still use `com.fasterxml.jackson`, and the hand-b |
| RES-04 | MEDIUM |  | The Kafka client (4.2.1) is two minor lines ahead of the broker (3.9.0) managed by Boot 4.1.1. |
| RES-05 | MEDIUM |  | No Kafka transaction is configured, so the system cannot use Kafka's exactly-once delivery; the exactly-once effect for the database write is obtained with the outbox instead. |
| RES-06 | MEDIUM |  | Two listener container factories have no dead-letter recoverer, so a poisoned record is logged and dropped. |
| RES-07 | MEDIUM |  | `retries = 3` on the producer is far below the Kafka 3.9 default of `2147483647`. |
| RES-08 | MEDIUM |  | The outbox claim resolves a single `MIN(id)` row, so `SKIP LOCKED` serialises claim throughput across relay instances. |
| RES-09 | MEDIUM |  | `jakarta.persistence.lock.timeout` is not declared on one pessimistic-lock query, so the provider default applies. |
| RES-10 | MEDIUM |  | `@Transactional(noRollbackFor = BusinessException.class)` suppresses the documented default rollback, and `BusinessException` is a `RuntimeException`. |
| RES-11 | MEDIUM |  | Five `REQUIRES_NEW` sites against a 20-connection Hikari pool while virtual threads are switchable on. |
| RES-12 | MEDIUM |  | A singleton bean holds mutable state (`currentPage`) mutated from a scheduled method. |
| RES-13 | MEDIUM |  | Monitor-based synchronization in main code would pin a virtual-thread carrier if virtual threads were enabled. |
| RES-14 | MEDIUM |  | Configuration is injected into singletons through non-final `@Value` fields. |
| RES-15 | MEDIUM |  | `shop.security.expected-audiences` is empty by default, so no audience validation runs; only the `azp` client check is active. |
| RES-16 | MEDIUM |  | Client roles (`resource_access`) are not mapped — only `realm_access.roles` becomes an authority. |
| RES-17 | MEDIUM |  | The webhook verifier accepts a second, legacy signature scheme (bare 64-hex HMAC over the body) when the header has no `t=` component. |
| RES-18 | MEDIUM |  | Shipped configuration files carry usable secret defaults (`changeme`, `admin`). |
| RES-19 | MEDIUM |  | The Stripe webhook is processed synchronously before the 2xx. |
| RES-20 | MEDIUM |  | The idempotency key is client-supplied (not a server-generated UUID) and is reused across the local and the Stripe layer. |
| RES-21 | MEDIUM |  | Deprecated Testcontainers 1.x packages are used in 21 test files. |
| RES-22 | MEDIUM |  | The integration-test Kafka image differs from the deployed broker. |
| RES-23 | MEDIUM |  | The Elasticsearch client declared at the root (9.4.5) is not the one used, and neither matches the 8.15.0 server. The 9.x line also renames the transport package, so honouring the  |
| SEC-04 | MEDIUM |  | No `aud` validation fleet-wide; a single shared `azp` allow-list for every service |
| SEC-05 | MEDIUM |  | The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding |
| SEC-06 | MEDIUM |  | Token and session lifetimes are not pinned in the realm and are not asserted anywhere |
| SEC-07 | MEDIUM |  | Password-reset accepts a weaker password than registration |
| SEC-08 | MEDIUM |  | The Keycloak error body is relayed to the client on non-401 auth failures |
| SEC-09 | MEDIUM |  | The shipping webhook verifier has no replay window |
| SEC-10 | MEDIUM |  | CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches |
| SEC-11 | MEDIUM |  | The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens |
| SEC-12 | MEDIUM |  | Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously |
| SEC-27 | MEDIUM |  | `auth-service` public-path rules are method-agnostic |
| SEC-28 | MEDIUM |  | Seeded credential pairs are documented in tracked markdown |
| SL-03 | MEDIUM |  | `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false) |
| SL-04 | MEDIUM |  | `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache |
| SL-06 | MEDIUM |  | `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish |
| SL-07 | MEDIUM |  | `DeadLetterReplayService` blocks forever on a hung broker and loses trace context |
| SL-09 | MEDIUM |  | `BaseKafkaConsumer` javadoc contradicts its code about DLT routing |
| SL-10 | MEDIUM |  | `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract |
| SL-11 | MEDIUM |  | `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload |
| SL-12 | MEDIUM |  | `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults |
| SL-13 | MEDIUM |  | `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity |
| SL-17 | MEDIUM |  | `ApiExceptionHandler` echoes the raw exception message for two request-shape errors |
| SL-18 | MEDIUM |  | `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down" |
| SL-19 | MEDIUM |  | `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable |
| SL-20 | MEDIUM |  | `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true` |
| SL-22 | MEDIUM |  | `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure |
| SL-25 | MEDIUM |  | `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely |
| SL-26 | MEDIUM |  | `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument |
| SL-29 | MEDIUM |  | `S3ObjectStorageService.download()` buffers up to 50 MB per call |
| SL-30 | MEDIUM |  | Five `CommonProperties` toggles are dead configuration |
| SL-38 | MEDIUM |  | the CORS source bean cannot be overridden |
| SL-39 | MEDIUM |  | the CORS code default is a wildcard origin |
| SL-40 | MEDIUM |  | the body masker only understands JSON |
| SL-41 | MEDIUM |  | the shared defaults activate the `dev` profile |
| CAT-12 | LOW |  | 7-day presigned-URL TTL for catalogue images |
| CAT-14 | LOW |  | Unbounded `synchronized` in the search service token provider |
| CAT-15 | LOW |  | Bucket ACL is asserted once at startup only |
| CAT-20 | LOW |  | `Rating.verified` is stored but never enforced on the read path |
| CAT-21 | LOW |  | Tax rounding regime is neither documented nor configurable |
| CAT-22 | LOW |  | The pricing contract has no net/gross declaration |
| CAT-24 | LOW |  | `@Value`-injected configuration fields are non-final on singletons |
| CAT-25 | LOW |  | `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string |
| GW-14 | LOW |  | / — No bound on the number of rate-limit keys, and the shared Redis has no memory policy |
| GW-15 | LOW |  | Inconsistent key spaces between the layers ⇒ an authenticated client gets up to 3× the configured budget; IPv4/IPv6 spellings are separate buckets |
| GW-16 | LOW |  | The edge bucket's TTL is refreshed on rejected requests, so an abuser's keys never expire and the key set only grows |
| GW-17 | LOW |  | `replenishRate` (tokens per **second**) and `requestsPerMinute` (requests per **minute**) live under two similarly named property namespaces |
| GW-18 | LOW |  | No `Retry-After` header on any 429 |
| GW-19 | LOW |  | The root POM comment "gateway migrated to Apache APISIX" is false and misleads every future reader |
| GW-20 | LOW |  | The "fail-fast" posture validator never verifies that the peer is a trusted proxy |
| GW-21 | LOW |  | `RateLimitKeyResolver` is unit-tested against a **mocked** exchange, so a framework regression would silently switch every L1 key to an IP key |
| GW-22 | LOW |  | Two independent issuer properties can drift; the one the decoder actually uses is not the one the gateway validates |
| GW-23 | LOW |  | No explicit JWT algorithm pinning |
| GW-24 | LOW |  | /INFO — The caller-supplied `X-Correlation-Id` is echoed into the error envelope without validation |
| GW-25 | LOW |  | (latent) — `AbstractRateLimiter`'s configuration map is a plain `HashMap` written from the event thread and read from request threads |
| GW-28 | LOW |  | `RedisEdgeRateLimiter.requestsPerMinute(String)` is dead code |
| KAF-10 | LOW |  | Producer payloads are untyped maps; the schema is not compile-time checked |
| KAF-11 | LOW |  | `FAILED` outbox rows are never purged in 7 of 8 services |
| KAF-12 | LOW |  | `DEAD` rows are never purged (media-service) and payment's `DEAD` purge is unreachable code |
| KAF-13 | LOW |  | `outbox_events.event_id` UNIQUE protects against nothing but UUID collisions |
| KAF-14 | LOW |  | The snapshot discipline that makes idempotency work is unenforced (latent  if a delta event is ever introduced) |
| KAF-15 | LOW |  | `enable.auto.commit=false` is inherited from a framework default, not asserted by fleet code |
| KAF-16 | LOW |  | order-service's non-transactional fallback paths would break atomicity if the tx manager were absent |
| KAF-17 | LOW |  | The legacy double-encoding `KafkaTemplate` is still registered and is the default type-match |
| KAF-18 | LOW |  | DLT replay drops the original headers (including `traceparent`) |
| KAF-19 | LOW |  | No `max.poll.interval.ms` is configured for any listener |
| OI-15 | LOW |  | `failed_compensations` has no uniqueness on `(reservation_id, kind)` |
| OI-16 | LOW |  | Three retention schedulers perform unbounded bulk DELETEs in a single transaction |
| OI-17 | LOW |  | The inventory outbox claim shape serialises every relay pod on the head row |
| OI-18 | LOW |  | `atomicReserve` lacks `flushAutomatically = true` (a latent trap) |
| OI-19 | LOW |  | The create saga reserves N lines sequentially (N × timeout, N simultaneous holds) |
| OI-20 | LOW |  | The "LIFO" compensation order is not deterministic under the parallel commit fan-out |
| OI-21 | LOW |  | The inventory retry budget is 3 attempts with a 50 ms base backoff |
| OI-22 | LOW |  | The static fan-out executors use unbounded queues against a 50-connection-per-route HTTP pool |
| OI-23 | LOW |  | A 5xx from product-service escapes as a raw exception (surfaced as a 500, not a 503) |
| OI-24 | LOW |  | `OrderStatusController.ship`/`deliver` have no per-order authorization |
| OI-25 | LOW |  | The reconciliation sweeper decides on a stale detached snapshot |
| OI-26 | LOW |  | `GuestCartCleanupScheduler` purges active carts of real users and is unbounded |
| OI-27 | LOW |  | The tracking abuse brake under-counts under concurrency |
| PAY-22 | LOW | payment-service | Dead code in the webhook event service and an unreachable status value |
| PAY-23 | LOW | payment-service | The Stripe api-version property is inert and always reports drift |
| PAY-24 | LOW | payment-service | Unused imports in the provider classes |
| RES-24 | LOW |  | The circuit breaker counts business rejections as failures, and four advised methods swallow the exception so the breaker never opens. |
| RES-25 | LOW |  | The retry and timelimiter Resilience4j configuration is dead (no `@Retry`/`@TimeLimiter` anywhere) and there is no bulkhead. |
| RES-26 | LOW |  | No JVM memory or GC option is set, so the heap defaults to 25 % of the container limit. |
| RES-27 | LOW |  | `/actuator/prometheus` is an unauthenticated public path while `/actuator/metrics` is protected — the scrape endpoint publishes the same measurement data. |
| RES-28 | LOW |  | The edge rate limiter touches two Redis keys without a common hash tag, and it fails open on a Redis error. |
| SEC-13 | LOW |  | Unbounded / zero-allowed quantity and amount fields |
| SEC-14 | LOW |  | `SearchRequest` accepts unbounded/negative price and rating filters |
| SEC-15 | LOW |  | `OrderReturnCreateRequest.refundAmount` has no scale bound |
| SEC-16 | LOW |  | `ProductCreateRequest.priceUnit` / `weight` have no scale bound |
| SEC-17 | LOW |  | `forgot-password` leaks account existence through response latency |
| SEC-18 | LOW |  | Elasticsearch client major version (9.x) vs server (8.15.0) |
| SEC-19 | LOW |  | `UUID.fromString(jwt.sub)` throws on a service-account token |
| SEC-20 | LOW |  | `ServiceCallerGuard.requireCaller` fails open when the security context is empty |
| SEC-21 | LOW |  | The Keycloak master-realm password grant remains reachable as a fallback |
| SEC-22 | LOW |  | `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` defaults to 0 in the base compose |
| SEC-23 | LOW |  | No HTTPS enforcement, no HSTS and no CSP at the application layer |
| SEC-24 | LOW |  | Stripe search query built by string concatenation |
| SEC-25 | LOW |  | Carrier request body built by string concatenation |
| SEC-26 | LOW |  | A client secret is configured for a **public** Keycloak client |
| SEC-29 | LOW |  | The `avatar` URL is persisted and returned but never validated against internal destinations |
| SEC-30 | LOW |  | The object store is a release candidate and the CI image digests are unpinned |
| SL-21 | LOW |  | `DateTimeUtils` cache eviction is a check-then-act race with a whole-cache flush |
| SL-23 | LOW |  | `AuditActorResolver` treats an `AnonymousAuthenticationToken` as an authenticated caller |
| SL-27 | LOW |  | `SecurityProperties` null-guards `expectedAudiences` but not `expectedClientIds` |
| SL-31 | LOW |  | `Messages` is a static global facade |
| SL-32 | LOW |  | `common-core`'s `Messages.fromBundle` uses `ResourceBundle.getBundle`, whose default-locale fallback can leak a wrong locale |
| SL-42 | LOW |  | dead retry configuration, and a test name that overstates the guarantee |
| SL-43 | LOW |  | a deprecated-for-removal serializer is still the default value serializer |
| SL-44 | LOW |  | truncated/garbled comments indicate partially-reverted edits |
| SL-45 | LOW |  | constructor-overload explosion instead of a builder |
