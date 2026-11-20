# Findings

Every finding was produced by reading the first-party source, not the documentation.

Registry size: 297 findings.

| ID | Severity | Area | Title |
| --- | --- | --- | --- |
| GW-01 | CRITICAL | gateway-redis | The trusted-proxy hop count is an index into an attacker-controlled header; the transport peer is never verified |
| GW-02 | CRITICAL | gateway-redis | A non-literal `X-Forwarded-For` entry becomes the rate-limit key: per-request bucket bypass, unbounded Redis key growth, and blocking DNS on the event loop |
| PAY-01 | CRITICAL | payment | Capture marks CAPTURED while no money moved; Stripe intent uses confirm=false and discards client_secret |
| PAY-02 | CRITICAL | payment | Unknown provider name silently falls back to the default provider so a refund can succeed against the wrong PSP |
| SHIP-01 | CRITICAL |  | The carrier call happened before the idempotency guard, so two duplicate CONFIRMED events created two parcels |
| CAT-01 | HIGH | catalog-search-media | Out-of-order Kafka events silently overwrite newer Elasticsearch state |
| CAT-02 | HIGH | catalog-search-media | Elasticsearch client dependency drift |
| CAT-05 | HIGH | catalog-search-media | No currency on any money field in the fleet |
| CAT-07 | HIGH | catalog-search-media | Promotion quotas are enforced by a racy read-then-write COUNT |
| GW-03 | HIGH | gateway-redis | Contradictory Redis-outage semantics across the three limiters, and the in-code justification is factually wrong |
| GW-04 | HIGH | gateway-redis | The "global" limiter is a single, non-partitioned bucket: no fairness, and the fleet cap does not scale with replicas |
| GW-05 | HIGH | gateway-redis | The edge bucket computes its refill from the **gateway JVM clock** while the framework buckets use the **Redis server clock** |
| KAF-01 | HIGH | kafka-outbox | 6 of 8 outbox relays serialise: a second replica publishes nothing |
| KAF-02 | HIGH | kafka-outbox | payment-service relay has no per-aggregate ordering barrier |
| OI-01 | HIGH | order-inventory | The inventory `reserve` API is not idempotent: duplicate holds and 900-second orphan holds |
| OI-02 | HIGH | order-inventory | A failed `releaseCommitted` in the confirm-compensation path is dropped: no ledger, no retry, stock permanently consumed |
| ORC-01 | HIGH | orchestrator-session | The idempotency claim was not atomic, so two concurrent confirms could both orchestrate the same order |
| ORC-02 | HIGH | orchestrator-session | Thirteen alert rules referenced metrics the platform never publishes, and the validator never checked metric existence |
| ORC-03 | HIGH | orchestrator-session | The final report hard-coded an alerting success claim and printed stale suite results as verified |
| ORC-04 | HIGH | orchestrator-session | Suite evidence could certify production readiness against a source revision that no longer existed |
| ORC-16 | HIGH | orchestrator-session | The deployment template shipped usable credential literals for every account the stack requires |
| ORC-17 | HIGH | orchestrator-session | The search client was pinned to the previous elasticsearch major while the fleet client was on the current one |
| ORC-18 | HIGH | orchestrator-session | The outbox claim collapsed the candidate range to a single row, so a second replica published nothing |
| PAY-03 | HIGH | payment | State machine allows self-transitions so a re-processed webhook publishes a duplicate payment.captured.v1 |
| PAY-04 | HIGH | payment | Webhook dedup key includes the attacker-controlled provider path segment so one signed body replays across provider labels |
| PAY-05 | HIGH | payment | Webhook retry scheduler has no claim lease so two replicas replay the same event concurrently |
| PAY-06 | HIGH | payment | No Saga compensation: a webhook that arrives after order cancellation captures the payment and never refunds it |
| PAY-07 | HIGH | payment | Global unique index on idempotency_key contradicts the per-user scoped repository lookup |
| PAY-08 | HIGH | payment | Stripe intent id is never persisted so refunds depend on the eventually consistent Search API |
| PAY-09 | HIGH | payment | Provider failure mapping conflates a declined card with an unknown outcome so a Stripe timeout is reported as a definitive rejection |
| PAY-10 | HIGH | payment | The provider call happens inside the database transaction with no client timeout |
| RTG-01 | HIGH |  | An upstream purchase-verification failure was reported as the business denial RTG-11001 (403) instead of a dependency failure |
| SEC-01 | HIGH | security | auth-service resolves the JWT subject against a DIFFERENT primary-key space |
| SEC-02 | HIGH | security | Cross-service IDOR: `verify-purchase` accepts an arbitrary `userId` with no principal cross-check |
| SEC-03 | HIGH | security | Committed literal credentials in `.env.example` for accounts the deployment *requires* |
| SHIP-02 | HIGH |  | A carrier client exception was thrown away, so a failed shipment creation was lost |
| SHIP-03 | HIGH |  | The carrier client declared no HTTP timeout |
| SHIP-04 | HIGH |  | changelog-003-webhook-retry violated a CHECK constraint, which blocked changesets 004, 005 and 006 from applying |
| SHIP-05 | HIGH |  | The reconciliation path bypassed the shipment finite state machine |
| SHIP-06 | HIGH |  | A late webhook could resurrect a DELIVERY_FAILED shipment |
| SHIP-07 | HIGH |  | Webhook retry rows had no claim, so several instances processed the same retry |
| SHIP-08 | HIGH |  | The outbox relay selected its head row with MIN(id), so a locked head row blocked every other row |
| SL-01 | HIGH | shared-libraries | `KeycloakAdminClient.getAdminAccessToken()` holds a JVM monitor across a network call |
| SL-01 | HIGH | shared-libraries | `synchronized (this)` on a shared bean, held across a network call |
| SL-02 | HIGH | shared-libraries | `KeycloakAdminClient` silently falls back to the master-realm *password* grant |
| SL-02 | HIGH | shared-libraries | silent downgrade from the service-account grant to the master-realm password grant |
| SL-05 | HIGH | shared-libraries | `DeadLetterReplayService.replay()` re-reads the whole DLT on every invocation and re-publishes already-replayed records |
| SL-05 | HIGH | shared-libraries | DLT replay is not resumable and re-publishes already-replayed records |
| SL-08 | HIGH | shared-libraries | `BaseKafkaConsumer.WIRE_MAPPER` is a bare `ObjectMapper` that rejects unknown properties |
| SL-08 | HIGH | shared-libraries | the Kafka consumer mapper rejects unknown properties, unlike every HTTP boundary |
| SL-14 | HIGH | shared-libraries | `CorrelationIdFilter` trusts an unvalidated client-supplied header and writes it into the MDC |
| SL-14 | HIGH | shared-libraries | an unvalidated client header is written into the MDC and echoed back |
| SL-15 | HIGH | shared-libraries | `RequestUtils.getClientIp()` trusts client-supplied proxy headers unconditionally |
| SL-15 | HIGH | shared-libraries | client IP resolved from spoofable headers, with a local-address bypass primitive |
| SL-16 | HIGH | shared-libraries | `HttpLoggingFilter` buffers the **entire** response body in heap per request |
| SL-16 | HIGH | shared-libraries | unbounded per-request response buffering |
| SL-24 | HIGH | shared-libraries | `ServiceCallerGuard.requireCaller()` **fails open** for an unauthenticated caller |
| SL-24 | HIGH | shared-libraries | `ServiceCallerGuard` fails open for an unauthenticated caller |
| SL-28 | HIGH | shared-libraries | `ObjectStorageAutoConfiguration` performs a **network call inside a `@Bean` method** |
| SL-28 | HIGH | shared-libraries | a network call inside a `@Bean` method, with a startup race |
| SL-33 | HIGH | shared-libraries | Keycloak clients bypass the application-configured `RestClient.Builder` |
| SL-34 | HIGH | shared-libraries | the DLT-replay actuator endpoint has no authorization |
| SL-35 | HIGH | shared-libraries | a listener factory without a recoverer silently drops records |
| SL-36 | HIGH | shared-libraries | the shared consumer configuration pins none of the commit/isolation semantics |
| SL-37 | HIGH | shared-libraries | the fleet-wide `expected-client-ids` list is the platform's only horizontal-privilege boundary, and it is broad |
| SL-46 | HIGH |  | Five duplicated ServiceTokenProvider classes fetched the Keycloak token over a non-pooled connection with no Accept header, no retry and, in search-service, no timeout at all |
| SL-47 | HIGH |  | One Keycloak admin-client property served two different OAuth clients, so the application-realm service account could never authenticate |
| CAT-03 | MEDIUM | catalog-search-media | Mutable, non-volatile pagination state on a singleton scheduled bean |
| CAT-04 | MEDIUM | catalog-search-media | Money stored as an IEEE-754 double in the search index |
| CAT-06 | MEDIUM | catalog-search-media | Product-variant changes emit no lifecycle event |
| CAT-08 | MEDIUM | catalog-search-media | Reindex lock is in-process only |
| CAT-09 | MEDIUM | catalog-search-media | Product outbox claim lease piggy-backs on the `updated_at` audit column |
| CAT-10 | MEDIUM | catalog-search-media | `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging) |
| CAT-11 | MEDIUM | catalog-search-media | No concurrency admission control on the media decode path |
| CAT-13 | MEDIUM | catalog-search-media | No Elasticsearch mapping-version gate |
| CAT-16 | MEDIUM | catalog-search-media | Reservation gates are an if/else chain |
| CAT-17 | MEDIUM | catalog-search-media | `FAILED` media outbox rows are re-published every 2 seconds for 7 days |
| CAT-18 | MEDIUM | catalog-search-media | Inconsistent event-naming convention across producers |
| CAT-19 | MEDIUM | catalog-search-media | The rating aggregate snapshot can be stale under concurrent reviews |
| CAT-23 | MEDIUM | catalog-search-media | No retention purge for the `notifications` table |
| GW-06 | MEDIUM | gateway-redis | Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget |
| GW-07 | MEDIUM | gateway-redis | Inconsistent 429 contract: empty body from L1, duplicate `X-RateLimit-Remaining`, a `-1` sentinel leaked to clients, and no `Retry-After` |
| GW-08 | MEDIUM | gateway-redis | `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced |
| GW-09 | MEDIUM | gateway-redis | The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode |
| GW-10 | MEDIUM | gateway-redis | The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop |
| GW-11 | MEDIUM | gateway-redis | The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive |
| GW-12 | MEDIUM | gateway-redis | The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster |
| GW-13 | MEDIUM | gateway-redis | Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run) |
| GW-26 | MEDIUM | gateway-redis | Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part) |
| GW-27 | MEDIUM | gateway-redis | No circuit breaker and no bulkhead anywhere in the gateway |
| GW-29 | MEDIUM | gateway-redis | INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter |
| KAF-03 | MEDIUM | kafka-outbox | `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling |
| KAF-04 | MEDIUM | kafka-outbox | `shop.kafka.retry.*` is bindable but has zero effect |
| KAF-05 | MEDIUM | kafka-outbox | product-service's media listener has no error handler, hence no DLT |
| KAF-06 | MEDIUM | kafka-outbox | No topic provisioning anywhere in the repo |
| KAF-07 | MEDIUM | kafka-outbox | product-service and payment-service use the generic `updated_at` column as the claim lease |
| KAF-08 | MEDIUM | kafka-outbox | search-service's DLT depends on an optional bean and fails open |
| KAF-09 | MEDIUM | kafka-outbox | notification-service's dedup key silently depends on the event id being a parseable UUID |
| KAF-20 | MEDIUM | kafka-outbox | The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket |
| OI-03 | MEDIUM | order-inventory | A failure of the saga's final transaction leaks every stock reservation |
| OI-04 | MEDIUM | order-inventory | `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order |
| OI-05 | MEDIUM | order-inventory | The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none |
| OI-06 | MEDIUM | order-inventory | The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim |
| OI-07 | MEDIUM | order-inventory | The reconciliation scheduler bypasses the order state machine |
| OI-08 | MEDIUM | order-inventory | The reconciliation auto-confirm bypasses the captured-payment gate |
| OI-09 | MEDIUM | order-inventory | `Cart` has no `@Version`: the cart subtotal is a lost update |
| OI-10 | MEDIUM | order-inventory | `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction |
| OI-11 | MEDIUM | order-inventory | `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act |
| OI-12 | MEDIUM | order-inventory | The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable |
| OI-13 | MEDIUM | order-inventory | The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing |
| OI-14 | MEDIUM | order-inventory | The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write |
| OI-28 | MEDIUM | order-inventory | INFO — YAML/code drift on three scheduler knobs |
| OI-29 | MEDIUM | order-inventory | INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table |
| ORC-05 | MEDIUM | orchestrator-session | Three business invariants were vacuous or used enum values that do not exist |
| ORC-06 | MEDIUM | orchestrator-session | The load runner reported PASS when the measurement host was contaminated or the invariants were blocked |
| ORC-07 | MEDIUM | orchestrator-session | The YAML subset parser silently dropped a sequence declared at the same indentation as its key |
| ORC-08 | MEDIUM | orchestrator-session | The continuous integration workflow was red on arrival |
| ORC-09 | MEDIUM | orchestrator-session | The Kubernetes manifests declared no startup probe, no pod security context and no disruption budget |
| ORC-10 | MEDIUM | orchestrator-session | The circuit breaker state was never exported, so the gateway outage rule could never fire |
| ORC-11 | MEDIUM | orchestrator-session | Request latency percentiles were unmeasurable because the histogram buckets were never published |
| ORC-14 | MEDIUM | orchestrator-session | The Stripe harness treated any test key prefix as a real credential and certified a placeholder webhook secret |
| ORC-15 | MEDIUM | orchestrator-session | The credential scan ignored untracked files and only recognised two credential shapes |
| ORC-19 | MEDIUM | orchestrator-session | The payment relay marked every record by its own outcome, which reordered one partition |
| PAY-11 | MEDIUM | payment | The REST capture and refund path bypasses the payment state machine and never sets refunded_amount |
| PAY-12 | MEDIUM | payment | Optimistic lock failures after a provider side effect surface as a server error |
| PAY-13 | MEDIUM | payment | The bare-hex signature branch has no timestamp and no nonce so signed webhooks replay forever |
| PAY-14 | MEDIUM | payment | One webhook secret is shared by all non-Stripe providers |
| PAY-15 | MEDIUM | payment | VNPay multiplies the amount by 100 unconditionally and neither provider ever contacts the PSP |
| PAY-16 | MEDIUM | payment | No currency validation and no currency comparison on the webhook path |
| PAY-17 | MEDIUM | payment | Zero-decimal currencies silently round the amount and then fail the webhook amount check |
| PAY-18 | MEDIUM | payment | Outbox rows parked as FAILED are never purged |
| PAY-19 | MEDIUM | payment | No production caller creates or captures a payment |
| RES-01 | MEDIUM | research-version | `spring-boot-starter-aop` is pinned to a 4.0.0-M2 milestone while the project runs Boot 4.1.1, and the artifact was renamed in Boot 4. |
| RES-02 | MEDIUM | research-version | `spring-boot-starter-web` is used in three modules although Boot 4 deprecated it in favour of `spring-boot-starter-webmvc`. |
| RES-03 | MEDIUM | research-version | Jackson 2 and Jackson 3 are both on the compile classpath while Boot 4 makes Jackson 3 the preferred JSON library; 93 source files still use `com.fasterxml.jackson`, and the hand-b |
| RES-04 | MEDIUM | research-version | The Kafka client (4.2.1) is two minor lines ahead of the broker (3.9.0) managed by Boot 4.1.1. |
| RES-05 | MEDIUM | research-version | No Kafka transaction is configured, so the system cannot use Kafka's exactly-once delivery; the exactly-once effect for the database write is obtained with the outbox instead. |
| RES-06 | MEDIUM | research-version | Two listener container factories have no dead-letter recoverer, so a poisoned record is logged and dropped. |
| RES-07 | MEDIUM | research-version | `retries = 3` on the producer is far below the Kafka 3.9 default of `2147483647`. |
| RES-08 | MEDIUM | research-version | The outbox claim resolves a single `MIN(id)` row, so `SKIP LOCKED` serialises claim throughput across relay instances. |
| RES-09 | MEDIUM | research-version | `jakarta.persistence.lock.timeout` is not declared on one pessimistic-lock query, so the provider default applies. |
| RES-10 | MEDIUM | research-version | `@Transactional(noRollbackFor = BusinessException.class)` suppresses the documented default rollback, and `BusinessException` is a `RuntimeException`. |
| RES-11 | MEDIUM | research-version | Five `REQUIRES_NEW` sites against a 20-connection Hikari pool while virtual threads are switchable on. |
| RES-12 | MEDIUM | research-version | A singleton bean holds mutable state (`currentPage`) mutated from a scheduled method. |
| RES-13 | MEDIUM | research-version | Monitor-based synchronization in main code would pin a virtual-thread carrier if virtual threads were enabled. |
| RES-14 | MEDIUM | research-version | Configuration is injected into singletons through non-final `@Value` fields. |
| RES-15 | MEDIUM | research-version | `shop.security.expected-audiences` is empty by default, so no audience validation runs; only the `azp` client check is active. |
| RES-16 | MEDIUM | research-version | Client roles (`resource_access`) are not mapped — only `realm_access.roles` becomes an authority. |
| RES-17 | MEDIUM | research-version | The webhook verifier accepts a second, legacy signature scheme (bare 64-hex HMAC over the body) when the header has no `t=` component. |
| RES-18 | MEDIUM | research-version | Shipped configuration files carry usable secret defaults (`changeme`, `admin`). |
| RES-19 | MEDIUM | research-version | The Stripe webhook is processed synchronously before the 2xx. |
| RES-20 | MEDIUM | research-version | The idempotency key is client-supplied (not a server-generated UUID) and is reused across the local and the Stripe layer. |
| RES-21 | MEDIUM | research-version | Deprecated Testcontainers 1.x packages are used in 21 test files. |
| RES-22 | MEDIUM | research-version | The integration-test Kafka image differs from the deployed broker. |
| RES-23 | MEDIUM | research-version | The Elasticsearch client declared at the root (9.4.5) is not the one used, and neither matches the 8.15.0 server. The 9.x line also renames the transport package, so honouring the  |
| SEC-04 | MEDIUM | security | No `aud` validation fleet-wide; a single shared `azp` allow-list for every service |
| SEC-05 | MEDIUM | security | The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding |
| SEC-06 | MEDIUM | security | Token and session lifetimes are not pinned in the realm and are not asserted anywhere |
| SEC-07 | MEDIUM | security | Password-reset accepts a weaker password than registration |
| SEC-08 | MEDIUM | security | The Keycloak error body is relayed to the client on non-401 auth failures |
| SEC-09 | MEDIUM | security | The shipping webhook verifier has no replay window |
| SEC-10 | MEDIUM | security | CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches |
| SEC-11 | MEDIUM | security | The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens |
| SEC-12 | MEDIUM | security | Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously |
| SEC-27 | MEDIUM | security | `auth-service` public-path rules are method-agnostic |
| SEC-28 | MEDIUM | security | Seeded credential pairs are documented in tracked markdown |
| SHIP-11 | MEDIUM |  | The reconciliation query had no matching index |
| SHIP-12 | MEDIUM |  | The soft-delete restriction did not match the partial index, so a soft-deleted shipment still occupied the live key |
| SHIP-13 | MEDIUM |  | Shipment events had no foreign key to their shipment |
| SHIP-14 | MEDIUM |  | A null provider event id was swallowed instead of rejected |
| SHIP-15 | MEDIUM |  | Permanently failed outbox rows were never surfaced |
| SL-03 | MEDIUM | shared-libraries | `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false) |
| SL-03 | MEDIUM | shared-libraries | `LazyJwtDecoder` builds more than one decoder under concurrent load |
| SL-04 | MEDIUM | shared-libraries | `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache |
| SL-04 | MEDIUM | shared-libraries | a failed pre-warm turns every request into a synchronous IdP discovery |
| SL-06 | MEDIUM | shared-libraries | `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish |
| SL-06 | MEDIUM | shared-libraries | the replay commits the offset of a record it failed to republish |
| SL-07 | MEDIUM | shared-libraries | `DeadLetterReplayService` blocks forever on a hung broker and loses trace context |
| SL-07 | MEDIUM | shared-libraries | the replay blocks without a timeout and loses the trace context |
| SL-09 | MEDIUM | shared-libraries | `BaseKafkaConsumer` javadoc contradicts its code about DLT routing |
| SL-09 | MEDIUM | shared-libraries | the `BaseKafkaConsumer` javadoc contradicts the code on DLT routing |
| SL-10 | MEDIUM | shared-libraries | `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract |
| SL-10 | MEDIUM | shared-libraries | `JsonKafkaDeserializer` throws the wrong exception type for the DLT contract |
| SL-11 | MEDIUM | shared-libraries | `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload |
| SL-11 | MEDIUM | shared-libraries | the serializer silently writes a non-JSON payload on failure |
| SL-12 | MEDIUM | shared-libraries | `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults |
| SL-12 | MEDIUM | shared-libraries | the shared consumer-group default is a fleet-wide trap |
| SL-13 | MEDIUM | shared-libraries | `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity |
| SL-13 | MEDIUM | shared-libraries | the `aud` validator rejects the scalar form that RFC 7519 permits |
| SL-17 | MEDIUM | shared-libraries | `ApiExceptionHandler` echoes the raw exception message for two request-shape errors |
| SL-17 | MEDIUM | shared-libraries | two exception messages are echoed to the client verbatim |
| SL-18 | MEDIUM | shared-libraries | `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down" |
| SL-18 | MEDIUM | shared-libraries | "invalid credentials" is indistinguishable from "IdP down" |
| SL-19 | MEDIUM | shared-libraries | `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable |
| SL-19 | MEDIUM | shared-libraries | disabling a user can leave live sessions behind |
| SL-20 | MEDIUM | shared-libraries | `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true` |
| SL-20 | MEDIUM | shared-libraries | user creation hard-codes `emailVerified=true` and `temporary=false` |
| SL-22 | MEDIUM | shared-libraries | `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure |
| SL-22 | MEDIUM | shared-libraries | the audit trail discards events under back-pressure with no alert |
| SL-25 | MEDIUM | shared-libraries | `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely |
| SL-25 | MEDIUM | shared-libraries | a realm ADMIN/MANAGER role bypasses the client allow-list |
| SL-26 | MEDIUM | shared-libraries | `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument |
| SL-26 | MEDIUM | shared-libraries | `AuthenticatedUser.from(jwt)` mixes two principals |
| SL-29 | MEDIUM | shared-libraries | `S3ObjectStorageService.download()` buffers up to 50 MB per call |
| SL-29 | MEDIUM | shared-libraries | `download()` buffers up to 50 MB per concurrent call |
| SL-30 | MEDIUM | shared-libraries | Five `CommonProperties` toggles are dead configuration |
| SL-30 | MEDIUM | shared-libraries | five `shop.common.*` toggles are dead configuration |
| SL-38 | MEDIUM | shared-libraries | the CORS source bean cannot be overridden |
| SL-39 | MEDIUM | shared-libraries | the CORS code default is a wildcard origin |
| SL-40 | MEDIUM | shared-libraries | the body masker only understands JSON |
| SL-41 | MEDIUM | shared-libraries | the shared defaults activate the `dev` profile |
| CAT-12 | LOW | catalog-search-media | 7-day presigned-URL TTL for catalogue images |
| CAT-14 | LOW | catalog-search-media | Unbounded `synchronized` in the search service token provider |
| CAT-15 | LOW | catalog-search-media | Bucket ACL is asserted once at startup only |
| CAT-20 | LOW | catalog-search-media | `Rating.verified` is stored but never enforced on the read path |
| CAT-21 | LOW | catalog-search-media | Tax rounding regime is neither documented nor configurable |
| CAT-22 | LOW | catalog-search-media | The pricing contract has no net/gross declaration |
| CAT-24 | LOW | catalog-search-media | `@Value`-injected configuration fields are non-final on singletons |
| CAT-25 | LOW | catalog-search-media | `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string |
| GW-14 | LOW | gateway-redis | / — No bound on the number of rate-limit keys, and the shared Redis has no memory policy |
| GW-15 | LOW | gateway-redis | Inconsistent key spaces between the layers ⇒ an authenticated client gets up to 3× the configured budget; IPv4/IPv6 spellings are separate buckets |
| GW-16 | LOW | gateway-redis | The edge bucket's TTL is refreshed on rejected requests, so an abuser's keys never expire and the key set only grows |
| GW-17 | LOW | gateway-redis | `replenishRate` (tokens per **second**) and `requestsPerMinute` (requests per **minute**) live under two similarly named property namespaces |
| GW-18 | LOW | gateway-redis | No `Retry-After` header on any 429 |
| GW-19 | LOW | gateway-redis | The root POM comment "gateway migrated to Apache APISIX" is false and misleads every future reader |
| GW-20 | LOW | gateway-redis | The "fail-fast" posture validator never verifies that the peer is a trusted proxy |
| GW-21 | LOW | gateway-redis | `RateLimitKeyResolver` is unit-tested against a **mocked** exchange, so a framework regression would silently switch every L1 key to an IP key |
| GW-22 | LOW | gateway-redis | Two independent issuer properties can drift; the one the decoder actually uses is not the one the gateway validates |
| GW-23 | LOW | gateway-redis | No explicit JWT algorithm pinning |
| GW-24 | LOW | gateway-redis | /INFO — The caller-supplied `X-Correlation-Id` is echoed into the error envelope without validation |
| GW-25 | LOW | gateway-redis | (latent) — `AbstractRateLimiter`'s configuration map is a plain `HashMap` written from the event thread and read from request threads |
| GW-28 | LOW | gateway-redis | `RedisEdgeRateLimiter.requestsPerMinute(String)` is dead code |
| KAF-10 | LOW | kafka-outbox | Producer payloads are untyped maps; the schema is not compile-time checked |
| KAF-11 | LOW | kafka-outbox | `FAILED` outbox rows are never purged in 7 of 8 services |
| KAF-12 | LOW | kafka-outbox | `DEAD` rows are never purged (media-service) and payment's `DEAD` purge is unreachable code |
| KAF-13 | LOW | kafka-outbox | `outbox_events.event_id` UNIQUE protects against nothing but UUID collisions |
| KAF-14 | LOW | kafka-outbox | The snapshot discipline that makes idempotency work is unenforced (latent  if a delta event is ever introduced) |
| KAF-15 | LOW | kafka-outbox | `enable.auto.commit=false` is inherited from a framework default, not asserted by fleet code |
| KAF-16 | LOW | kafka-outbox | order-service's non-transactional fallback paths would break atomicity if the tx manager were absent |
| KAF-17 | LOW | kafka-outbox | The legacy double-encoding `KafkaTemplate` is still registered and is the default type-match |
| KAF-18 | LOW | kafka-outbox | DLT replay drops the original headers (including `traceparent`) |
| KAF-19 | LOW | kafka-outbox | No `max.poll.interval.ms` is configured for any listener |
| OI-15 | LOW | order-inventory | `failed_compensations` has no uniqueness on `(reservation_id, kind)` |
| OI-16 | LOW | order-inventory | Three retention schedulers perform unbounded bulk DELETEs in a single transaction |
| OI-17 | LOW | order-inventory | The inventory outbox claim shape serialises every relay pod on the head row |
| OI-18 | LOW | order-inventory | `atomicReserve` lacks `flushAutomatically = true` (a latent trap) |
| OI-19 | LOW | order-inventory | The create saga reserves N lines sequentially (N × timeout, N simultaneous holds) |
| OI-20 | LOW | order-inventory | The "LIFO" compensation order is not deterministic under the parallel commit fan-out |
| OI-21 | LOW | order-inventory | The inventory retry budget is 3 attempts with a 50 ms base backoff |
| OI-22 | LOW | order-inventory | The static fan-out executors use unbounded queues against a 50-connection-per-route HTTP pool |
| OI-23 | LOW | order-inventory | A 5xx from product-service escapes as a raw exception (surfaced as a 500, not a 503) |
| OI-24 | LOW | order-inventory | `OrderStatusController.ship`/`deliver` have no per-order authorization |
| OI-25 | LOW | order-inventory | The reconciliation sweeper decides on a stale detached snapshot |
| OI-26 | LOW | order-inventory | `GuestCartCleanupScheduler` purges active carts of real users and is unbounded |
| OI-27 | LOW | order-inventory | The tracking abuse brake under-counts under concurrency |
| ORC-12 | LOW | orchestrator-session | The rendered Alertmanager configuration that carries the live bot token was not ignored by git |
| ORC-13 | LOW | orchestrator-session | The Alertmanager template rendering corrupted any value that contained an ampersand |
| PAY-20 | LOW | payment | The default provider is chosen non-deterministically when the configured provider is absent |
| PAY-21 | LOW | payment | The public webhook endpoint has no body size limit and no service level rate limit |
| PAY-22 | LOW | payment | Dead code in the webhook event service and an unreachable status value |
| PAY-23 | LOW | payment | The Stripe api-version property is inert and always reports drift |
| PAY-24 | LOW | payment | Unused imports in the provider classes |
| RES-24 | LOW | research-version | The circuit breaker counts business rejections as failures, and four advised methods swallow the exception so the breaker never opens. |
| RES-25 | LOW | research-version | The retry and timelimiter Resilience4j configuration is dead (no `@Retry`/`@TimeLimiter` anywhere) and there is no bulkhead. |
| RES-26 | LOW | research-version | No JVM memory or GC option is set, so the heap defaults to 25 % of the container limit. |
| RES-27 | LOW | research-version | `/actuator/prometheus` is an unauthenticated public path while `/actuator/metrics` is protected — the scrape endpoint publishes the same measurement data. |
| RES-28 | LOW | research-version | The edge rate limiter touches two Redis keys without a common hash tag, and it fails open on a Redis error. |
| SEC-13 | LOW | security | Unbounded / zero-allowed quantity and amount fields |
| SEC-14 | LOW | security | `SearchRequest` accepts unbounded/negative price and rating filters |
| SEC-15 | LOW | security | `OrderReturnCreateRequest.refundAmount` has no scale bound |
| SEC-16 | LOW | security | `ProductCreateRequest.priceUnit` / `weight` have no scale bound |
| SEC-17 | LOW | security | `forgot-password` leaks account existence through response latency |
| SEC-18 | LOW | security | Elasticsearch client major version (9.x) vs server (8.15.0) |
| SEC-19 | LOW | security | `UUID.fromString(jwt.sub)` throws on a service-account token |
| SEC-20 | LOW | security | `ServiceCallerGuard.requireCaller` fails open when the security context is empty |
| SEC-21 | LOW | security | The Keycloak master-realm password grant remains reachable as a fallback |
| SEC-22 | LOW | security | `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` defaults to 0 in the base compose |
| SEC-23 | LOW | security | No HTTPS enforcement, no HSTS and no CSP at the application layer |
| SEC-24 | LOW | security | Stripe search query built by string concatenation |
| SEC-25 | LOW | security | Carrier request body built by string concatenation |
| SEC-26 | LOW | security | A client secret is configured for a **public** Keycloak client |
| SEC-29 | LOW | security | The `avatar` URL is persisted and returned but never validated against internal destinations |
| SEC-30 | LOW | security | The object store is a release candidate and the CI image digests are unpinned |
| SL-21 | LOW | shared-libraries | `DateTimeUtils` cache eviction is a check-then-act race with a whole-cache flush |
| SL-21 | LOW | shared-libraries | `DateTimeUtils` cache eviction is a check-then-act flush |
| SL-23 | LOW | shared-libraries | `AuditActorResolver` treats an `AnonymousAuthenticationToken` as an authenticated caller |
| SL-23 | LOW | shared-libraries | `isAuthenticated()` is `true` for an anonymous token |
| SL-27 | LOW | shared-libraries | `SecurityProperties` null-guards `expectedAudiences` but not `expectedClientIds` |
| SL-27 | LOW | shared-libraries | `expectedClientIds` has no null guard |
| SL-31 | LOW | shared-libraries | `Messages` is a static global facade |
| SL-31 | LOW | shared-libraries | `Messages` is a static global facade |
| SL-32 | LOW | shared-libraries | `common-core`'s `Messages.fromBundle` uses `ResourceBundle.getBundle`, whose default-locale fallback can leak a wrong locale |
| SL-32 | LOW | shared-libraries | `ResourceBundle.getBundle` default-locale fallback disagrees with the Spring path |
| SL-42 | LOW | shared-libraries | dead retry configuration, and a test name that overstates the guarantee |
| SL-43 | LOW | shared-libraries | a deprecated-for-removal serializer is still the default value serializer |
| SL-44 | LOW | shared-libraries | truncated/garbled comments indicate partially-reverted edits |
| SL-45 | LOW | shared-libraries | constructor-overload explosion instead of a builder |

The full evidence for each finding, including file and line references, the root
cause and the proposed fix, is in
`production-audit/runs/run-20261118-a2b6e6fc/source-reviews/` and in the
machine-readable ledger `production-audit/state/findings/`.
