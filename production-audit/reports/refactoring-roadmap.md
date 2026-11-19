# Refactoring Roadmap

Generated: 2026-11-19T16:03:53Z

The roadmap is ordered by business risk. Every entry names the design pattern that
resolves it and the evidence that justifies it.

| Order | ID | Severity | Pattern | Change |
| --- | --- | --- | --- | --- |
| 1 | SHIP-01 | CRITICAL | Reservation | The order id is claimed in a shipment_creation_tasks row before the carrier call; a partial unique index on order_id arbitrates the claim; the carrier request carries a deterministic Idempotency-Key. |
| 2 | GW-01 | CRITICAL |  | The trusted-proxy hop count is an index into an attacker-controlled header; the transport peer is never verified |
| 3 | GW-02 | CRITICAL |  | A non-literal `X-Forwarded-For` entry becomes the rate-limit key: per-request bucket bypass, unbounded Redis key growth, and blocking DNS on the event loop |
| 4 | PAY-01 | CRITICAL | Strategy | Either confirm the intent server-side with a configured payment method and verify the intent status, or return the client_secret to the caller and only transition to CAPTURED after a verified provider confirmation event. |
| 5 | PAY-02 | CRITICAL | Factory Method | Reject an unknown provider with PAYMENT_PROVIDER_REJECTED. Remove the silent fallback. Make MockProvider conditional so it cannot silently back a production payment. |
| 6 | SHIP-02 | HIGH | Reconciliation Sweeper | The failure is recorded on the creation task with an attempt count and a next attempt time, and a retry worker picks it up. |
| 7 | SHIP-03 | HIGH | Adapter | The carrier request factory declares a connect and read timeout inside a budget. |
| 8 | SHIP-04 | HIGH | Migration | A new changeset 002b widens the constraint before 003 runs; the already applied changesets 001 to 006 are untouched so their checksums stay valid. |
| 9 | RTG-01 | HIGH | Circuit Breaker | The upstream failure escapes the guarded call as BusinessException.serviceUnavailable (503 ERR-0503) with the cause preserved; the circuit breaker records it; a fallback maps an open breaker to the same dependency failure; a genuine not-eligible answer still returns 403 RTG-11001. |
| 10 | SHIP-05 | HIGH | State Machine | The state machine owns every transition, the status setters are closed, and the sweep goes through the state machine with a per-row error boundary. |
| 11 | SHIP-06 | HIGH | State Machine | The edge is removed; an operator retry is the only way out of DELIVERY_FAILED; the webhook path uses the state machine. |
| 12 | SHIP-07 | HIGH | Reconciliation Sweeper | A conditional update claims the row with a claimed_at stamp and the claim is released on failure. |
| 13 | SHIP-08 | HIGH | Transactional Outbox | The claim selects with ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED, so a locked row is skipped. |
| 14 | OBS-01 | HIGH | Adapter | A compose managed telemetry pipeline from the services through the collector into the metric, log and trace backends, with dashboards and alert rules that read live data. |
| 15 | SL-46 | HIGH | Adapter | One shared KeycloakServiceTokenProvider in common-keycloak with a pooled HttpClient, an Accept: application/json default header, a bounded retry inside a total refresh budget, automatic transport retries disabled so the policy is in one place, and a failure cooldown that collapses a refresh herd. The five service classes became thin adapters. |
| 16 | SL-47 | HIGH | Adapter | A distinct service-account-client-id property (default ecommerce-admin) drives the client_credentials grant, while the master-realm password grant keeps admin-cli. |
| 17 | PAY-06 | HIGH | Saga | Consume the order lifecycle topic, and on a late capture for a cancelled order emit a compensation refund. |
| 18 | PAY-04 | HIGH | Idempotent Consumer | Derive the dedup identity only from the signed content and the provider identity bound to the verifying secret. |
| 19 | CAT-01 | HIGH |  | Out-of-order Kafka events silently overwrite newer Elasticsearch state |
| 20 | CAT-02 | HIGH |  | Elasticsearch client dependency drift |
| 21 | CAT-05 | HIGH |  | No currency on any money field in the fleet |
| 22 | CAT-07 | HIGH |  | Promotion quotas are enforced by a racy read-then-write COUNT |
| 23 | GW-03 | HIGH |  | Contradictory Redis-outage semantics across the three limiters, and the in-code justification is factually wrong |
| 24 | GW-04 | HIGH |  | The "global" limiter is a single, non-partitioned bucket: no fairness, and the fleet cap does not scale with replicas |
| 25 | GW-05 | HIGH |  | The edge bucket computes its refill from the **gateway JVM clock** while the framework buckets use the **Redis server clock** |
| 26 | KAF-01 | HIGH |  | 6 of 8 outbox relays serialise: a second replica publishes nothing |
| 27 | KAF-02 | HIGH |  | payment-service relay has no per-aggregate ordering barrier |
| 28 | OI-01 | HIGH |  | The inventory `reserve` API is not idempotent: duplicate holds and 900-second orphan holds |
| 29 | OI-02 | HIGH |  | A failed `releaseCommitted` in the confirm-compensation path is dropped: no ledger, no retry, stock permanently consumed |
| 30 | PAY-03 | HIGH | State | Reject identity transitions in the state machine and publish a lifecycle event only when the status actually changed. |
| 31 | PAY-30 | HIGH | Adapter | A dedicated PSP callback adapter per provider that verifies the vendor signature, maps the PSP payload to the carrier payload and hands it to the existing webhook event service. |
| 32 | SEC-01 | HIGH |  | auth-service resolves the JWT subject against a DIFFERENT primary-key space |
| 33 | SEC-02 | HIGH |  | Cross-service IDOR: `verify-purchase` accepts an arbitrary `userId` with no principal cross-check |
| 34 | SEC-03 | HIGH |  | Committed literal credentials in `.env.example` for accounts the deployment *requires* |
| 35 | SL-01 | HIGH |  | `KeycloakAdminClient.getAdminAccessToken()` holds a JVM monitor across a network call |
| 36 | SL-02 | HIGH |  | `KeycloakAdminClient` silently falls back to the master-realm *password* grant |
| 37 | SL-05 | HIGH |  | `DeadLetterReplayService.replay()` re-reads the whole DLT on every invocation and re-publishes already-replayed records |
| 38 | SL-08 | HIGH |  | `BaseKafkaConsumer.WIRE_MAPPER` is a bare `ObjectMapper` that rejects unknown properties |
| 39 | SL-14 | HIGH |  | `CorrelationIdFilter` trusts an unvalidated client-supplied header and writes it into the MDC |
| 40 | SL-15 | HIGH |  | `RequestUtils.getClientIp()` trusts client-supplied proxy headers unconditionally |
| 41 | SL-16 | HIGH |  | `HttpLoggingFilter` buffers the **entire** response body in heap per request |
| 42 | SL-24 | HIGH |  | `ServiceCallerGuard.requireCaller()` **fails open** for an unauthenticated caller |
| 43 | SL-28 | HIGH |  | `ObjectStorageAutoConfiguration` performs a **network call inside a `@Bean` method** |
| 44 | SL-33 | HIGH |  | Keycloak clients bypass the application-configured `RestClient.Builder` |
| 45 | SL-34 | HIGH |  | the DLT-replay actuator endpoint has no authorization |
| 46 | SL-35 | HIGH |  | a listener factory without a recoverer silently drops records |
| 47 | SL-36 | HIGH |  | the shared consumer configuration pins none of the commit/isolation semantics |
| 48 | SL-37 | HIGH |  | the fleet-wide `expected-client-ids` list is the platform's only horizontal-privilege boundary, and it is broad |
| 49 | PAY-05 | HIGH | Reservation | Claim the rows with a lease column and a conditional update, or use SELECT FOR UPDATE SKIP LOCKED inside a transaction. |
| 50 | PAY-07 | HIGH | Repository | Make the unique constraint match the lookup scope, for example unique on user_id plus idempotency_key, and align the index name. |
| 51 | PAY-08 | HIGH | Repository | Persist the provider intent id on the payment row at creation and use it for refunds. |
| 52 | PAY-09 | HIGH | Specification | Distinguish a definitive decline from an indeterminate outcome, and on an indeterminate outcome reconcile by idempotency key rather than inventing an identifier. |
| 53 | PAY-10 | HIGH | Adapter | Move the provider call outside the transaction and configure explicit connect and read timeouts. |
| 54 | PERF-01 | MEDIUM | Adapter | A reproducible load run against the compose stack whose result becomes the performance evidence. |
| 55 | SHIP-11 | MEDIUM | Migration | The index matches the due-retry predicate. |
| 56 | SHIP-12 | MEDIUM | Repository | The restriction matches the partial index and the creation claim reopens the soft-deleted row. |
| 57 | SHIP-13 | MEDIUM | Migration | The foreign key is added, validated for existing rows and enforced for new rows. |
| 58 | SHIP-14 | MEDIUM | Idempotent Consumer | A signed payload without an event id is rejected with a bad request before any write, and an insert violation is classified by constraint name. |
| 59 | SHIP-15 | MEDIUM | Reconciliation Sweeper | The exhausted row is parked as DEAD, counted by a metric, and purged by the retention scheduler. |
| 60 | PAY-31 | MEDIUM | Adapter | The verifier accepts any of the documented field set variants and rejects only a body whose signature matches none of them |
| 61 | PAY-32 | MEDIUM | Adapter | The refund response signature is verified over the documented response field set. |
| 62 | PAY-33 | MEDIUM | Adapter | The production deployment requires the provider to be named explicitly and the startup message names the variable to set |
| 63 | PAY-19 | MEDIUM | Saga | Wire the checkout saga to create and capture the payment, or document the external caller contract and add the missing consumer. |
| 64 | PAY-15 | MEDIUM | Strategy | Use the currency-unit rules already present in StripeCurrencyUnits and either integrate the PSP or remove the provider from the selectable set. |
| 65 | CAT-03 | MEDIUM |  | Mutable, non-volatile pagination state on a singleton scheduled bean |
| 66 | CAT-04 | MEDIUM |  | Money stored as an IEEE-754 double in the search index |
| 67 | CAT-06 | MEDIUM |  | Product-variant changes emit no lifecycle event |
| 68 | CAT-08 | MEDIUM |  | Reindex lock is in-process only |
| 69 | CAT-09 | MEDIUM |  | Product outbox claim lease piggy-backs on the `updated_at` audit column |
| 70 | CAT-10 | MEDIUM |  | `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging) |
| 71 | CAT-11 | MEDIUM |  | No concurrency admission control on the media decode path |
| 72 | CAT-13 | MEDIUM |  | No Elasticsearch mapping-version gate |
| 73 | CAT-16 | MEDIUM |  | Reservation gates are an if/else chain |
| 74 | CAT-17 | MEDIUM |  | `FAILED` media outbox rows are re-published every 2 seconds for 7 days |
| 75 | CAT-18 | MEDIUM |  | Inconsistent event-naming convention across producers |
| 76 | CAT-19 | MEDIUM |  | The rating aggregate snapshot can be stale under concurrent reviews |
| 77 | CAT-23 | MEDIUM |  | No retention purge for the `notifications` table |
| 78 | GW-06 | MEDIUM |  | Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget |
| 79 | GW-07 | MEDIUM |  | Inconsistent 429 contract: empty body from L1, duplicate `X-RateLimit-Remaining`, a `-1` sentinel leaked to clients, and no `Retry-After` |
| 80 | GW-08 | MEDIUM |  | `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced |
| 81 | GW-09 | MEDIUM |  | The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode |
| 82 | GW-10 | MEDIUM |  | The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop |
| 83 | GW-11 | MEDIUM |  | The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive |
| 84 | GW-12 | MEDIUM |  | The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster |
| 85 | GW-13 | MEDIUM |  | Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run) |
| 86 | GW-26 | MEDIUM |  | Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part) |
| 87 | GW-27 | MEDIUM |  | No circuit breaker and no bulkhead anywhere in the gateway |
| 88 | GW-29 | MEDIUM |  | INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter |
| 89 | KAF-03 | MEDIUM |  | `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling |
| 90 | KAF-04 | MEDIUM |  | `shop.kafka.retry.*` is bindable but has zero effect |
| 91 | KAF-05 | MEDIUM |  | product-service's media listener has no error handler, hence no DLT |
| 92 | KAF-06 | MEDIUM |  | No topic provisioning anywhere in the repo |
| 93 | KAF-07 | MEDIUM |  | product-service and payment-service use the generic `updated_at` column as the claim lease |
| 94 | KAF-08 | MEDIUM |  | search-service's DLT depends on an optional bean and fails open |
| 95 | KAF-09 | MEDIUM |  | notification-service's dedup key silently depends on the event id being a parseable UUID |
| 96 | KAF-20 | MEDIUM |  | The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket |
| 97 | OI-03 | MEDIUM |  | A failure of the saga's final transaction leaks every stock reservation |
| 98 | OI-04 | MEDIUM |  | `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order |
| 99 | OI-05 | MEDIUM |  | The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none |
| 100 | OI-06 | MEDIUM |  | The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim |
| 101 | OI-07 | MEDIUM |  | The reconciliation scheduler bypasses the order state machine |
| 102 | OI-08 | MEDIUM |  | The reconciliation auto-confirm bypasses the captured-payment gate |
| 103 | OI-09 | MEDIUM |  | `Cart` has no `@Version`: the cart subtotal is a lost update |
| 104 | OI-10 | MEDIUM |  | `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction |
| 105 | OI-11 | MEDIUM |  | `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act |
| 106 | OI-12 | MEDIUM |  | The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable |
| 107 | OI-13 | MEDIUM |  | The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing |
| 108 | OI-14 | MEDIUM |  | The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write |
| 109 | OI-28 | MEDIUM |  | INFO — YAML/code drift on three scheduler knobs |
| 110 | OI-29 | MEDIUM |  | INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table |
| 111 | PAY-11 | MEDIUM | State | Route every transition through the state machine and record refunded_amount. |
| 112 | SEC-04 | MEDIUM |  | No `aud` validation fleet-wide; a single shared `azp` allow-list for every service |
| 113 | SEC-05 | MEDIUM |  | The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding |
| 114 | SEC-06 | MEDIUM |  | Token and session lifetimes are not pinned in the realm and are not asserted anywhere |
| 115 | SEC-07 | MEDIUM |  | Password-reset accepts a weaker password than registration |
| 116 | SEC-08 | MEDIUM |  | The Keycloak error body is relayed to the client on non-401 auth failures |
| 117 | SEC-09 | MEDIUM |  | The shipping webhook verifier has no replay window |
| 118 | SEC-10 | MEDIUM |  | CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches |
| 119 | SEC-11 | MEDIUM |  | The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens |
| 120 | SEC-12 | MEDIUM |  | Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously |
| 121 | SEC-27 | MEDIUM |  | `auth-service` public-path rules are method-agnostic |
| 122 | SEC-28 | MEDIUM |  | Seeded credential pairs are documented in tracked markdown |
| 123 | SL-03 | MEDIUM |  | `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false) |
| 124 | SL-04 | MEDIUM |  | `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache |
| 125 | SL-06 | MEDIUM |  | `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish |
| 126 | SL-07 | MEDIUM |  | `DeadLetterReplayService` blocks forever on a hung broker and loses trace context |
| 127 | SL-09 | MEDIUM |  | `BaseKafkaConsumer` javadoc contradicts its code about DLT routing |
| 128 | SL-10 | MEDIUM |  | `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract |
| 129 | SL-11 | MEDIUM |  | `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload |
| 130 | SL-12 | MEDIUM |  | `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults |
| 131 | SL-13 | MEDIUM |  | `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity |
| 132 | SL-17 | MEDIUM |  | `ApiExceptionHandler` echoes the raw exception message for two request-shape errors |
| 133 | SL-18 | MEDIUM |  | `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down" |
| 134 | SL-19 | MEDIUM |  | `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable |
| 135 | SL-20 | MEDIUM |  | `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true` |
| 136 | SL-22 | MEDIUM |  | `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure |
| 137 | SL-25 | MEDIUM |  | `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely |
| 138 | SL-26 | MEDIUM |  | `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument |
| 139 | SL-29 | MEDIUM |  | `S3ObjectStorageService.download()` buffers up to 50 MB per call |
| 140 | SL-30 | MEDIUM |  | Five `CommonProperties` toggles are dead configuration |
| 141 | SL-38 | MEDIUM |  | the CORS source bean cannot be overridden |
| 142 | SL-39 | MEDIUM |  | the CORS code default is a wildcard origin |
| 143 | SL-40 | MEDIUM |  | the body masker only understands JSON |
| 144 | SL-41 | MEDIUM |  | the shared defaults activate the `dev` profile |
| 145 | PAY-12 | MEDIUM | Optimistic Locking | Persist the provider intent before the guarded transition and make the transition retry-safe. |
| 146 | PAY-13 | MEDIUM | Specification | Require a signed timestamp and enforce the tolerance on every provider branch. |
| 147 | RES-01 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 148 | RES-02 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 149 | RES-03 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 150 | RES-04 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 151 | RES-05 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 152 | RES-06 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 153 | RES-07 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 154 | RES-08 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 155 | RES-09 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 156 | RES-10 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 157 | RES-11 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 158 | RES-12 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 159 | RES-13 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 160 | RES-14 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 161 | RES-15 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 162 | RES-16 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 163 | RES-17 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 164 | RES-18 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 165 | RES-19 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 166 | RES-20 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 167 | RES-21 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 168 | RES-22 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 169 | RES-23 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 170 | PAY-14 | MEDIUM | Specification | Configure a distinct secret per provider and bind the secret to the provider identity. |
| 171 | PAY-16 | MEDIUM | Specification | Validate against an explicit supported-currency set and compare currency on the webhook path. |
| 172 | PAY-17 | MEDIUM | Specification | Compare in the same unit on both sides and define a rounding policy that is symmetric. |
| 173 | PAY-18 | MEDIUM | Reconciliation Sweeper | Purge or archive failed rows once they exceed the retention window. |
| 174 | PAY-34 | LOW | Adapter | The canonical strings come from an independent implementation and a containerised test drives a callback through the payment service to a captured payment |
| 175 | RES-24 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 176 | RES-25 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 177 | RES-26 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 178 | RES-27 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 179 | RES-28 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 180 | CAT-12 | LOW |  | 7-day presigned-URL TTL for catalogue images |
| 181 | CAT-14 | LOW |  | Unbounded `synchronized` in the search service token provider |
| 182 | CAT-15 | LOW |  | Bucket ACL is asserted once at startup only |
| 183 | CAT-20 | LOW |  | `Rating.verified` is stored but never enforced on the read path |
| 184 | CAT-21 | LOW |  | Tax rounding regime is neither documented nor configurable |
| 185 | CAT-22 | LOW |  | The pricing contract has no net/gross declaration |
| 186 | CAT-24 | LOW |  | `@Value`-injected configuration fields are non-final on singletons |
| 187 | CAT-25 | LOW |  | `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string |
| 188 | GW-14 | LOW |  | / — No bound on the number of rate-limit keys, and the shared Redis has no memory policy |
| 189 | GW-15 | LOW |  | Inconsistent key spaces between the layers ⇒ an authenticated client gets up to 3× the configured budget; IPv4/IPv6 spellings are separate buckets |
| 190 | GW-16 | LOW |  | The edge bucket's TTL is refreshed on rejected requests, so an abuser's keys never expire and the key set only grows |
| 191 | GW-17 | LOW |  | `replenishRate` (tokens per **second**) and `requestsPerMinute` (requests per **minute**) live under two similarly named property namespaces |
| 192 | GW-18 | LOW |  | No `Retry-After` header on any 429 |
| 193 | GW-19 | LOW |  | The root POM comment "gateway migrated to Apache APISIX" is false and misleads every future reader |
| 194 | GW-20 | LOW |  | The "fail-fast" posture validator never verifies that the peer is a trusted proxy |
| 195 | GW-21 | LOW |  | `RateLimitKeyResolver` is unit-tested against a **mocked** exchange, so a framework regression would silently switch every L1 key to an IP key |
| 196 | GW-22 | LOW |  | Two independent issuer properties can drift; the one the decoder actually uses is not the one the gateway validates |
| 197 | GW-23 | LOW |  | No explicit JWT algorithm pinning |
| 198 | GW-24 | LOW |  | /INFO — The caller-supplied `X-Correlation-Id` is echoed into the error envelope without validation |
| 199 | GW-25 | LOW |  | (latent) — `AbstractRateLimiter`'s configuration map is a plain `HashMap` written from the event thread and read from request threads |
| 200 | GW-28 | LOW |  | `RedisEdgeRateLimiter.requestsPerMinute(String)` is dead code |
| 201 | KAF-10 | LOW |  | Producer payloads are untyped maps; the schema is not compile-time checked |
| 202 | KAF-11 | LOW |  | `FAILED` outbox rows are never purged in 7 of 8 services |
| 203 | KAF-12 | LOW |  | `DEAD` rows are never purged (media-service) and payment's `DEAD` purge is unreachable code |
| 204 | KAF-13 | LOW |  | `outbox_events.event_id` UNIQUE protects against nothing but UUID collisions |
| 205 | KAF-14 | LOW |  | The snapshot discipline that makes idempotency work is unenforced (latent  if a delta event is ever introduced) |
| 206 | KAF-15 | LOW |  | `enable.auto.commit=false` is inherited from a framework default, not asserted by fleet code |
| 207 | KAF-16 | LOW |  | order-service's non-transactional fallback paths would break atomicity if the tx manager were absent |
| 208 | KAF-17 | LOW |  | The legacy double-encoding `KafkaTemplate` is still registered and is the default type-match |
| 209 | KAF-18 | LOW |  | DLT replay drops the original headers (including `traceparent`) |
| 210 | KAF-19 | LOW |  | No `max.poll.interval.ms` is configured for any listener |
| 211 | OI-15 | LOW |  | `failed_compensations` has no uniqueness on `(reservation_id, kind)` |
| 212 | OI-16 | LOW |  | Three retention schedulers perform unbounded bulk DELETEs in a single transaction |
| 213 | OI-17 | LOW |  | The inventory outbox claim shape serialises every relay pod on the head row |
| 214 | OI-18 | LOW |  | `atomicReserve` lacks `flushAutomatically = true` (a latent trap) |
| 215 | OI-19 | LOW |  | The create saga reserves N lines sequentially (N × timeout, N simultaneous holds) |
| 216 | OI-20 | LOW |  | The "LIFO" compensation order is not deterministic under the parallel commit fan-out |
| 217 | OI-21 | LOW |  | The inventory retry budget is 3 attempts with a 50 ms base backoff |
| 218 | OI-22 | LOW |  | The static fan-out executors use unbounded queues against a 50-connection-per-route HTTP pool |
| 219 | OI-23 | LOW |  | A 5xx from product-service escapes as a raw exception (surfaced as a 500, not a 503) |
| 220 | OI-24 | LOW |  | `OrderStatusController.ship`/`deliver` have no per-order authorization |
| 221 | OI-25 | LOW |  | The reconciliation sweeper decides on a stale detached snapshot |
| 222 | OI-26 | LOW |  | `GuestCartCleanupScheduler` purges active carts of real users and is unbounded |
| 223 | OI-27 | LOW |  | The tracking abuse brake under-counts under concurrency |
| 224 | PAY-20 | LOW | Factory Method | Fail fast when the configured provider is not registered. |
| 225 | SEC-13 | LOW |  | Unbounded / zero-allowed quantity and amount fields |
| 226 | SEC-14 | LOW |  | `SearchRequest` accepts unbounded/negative price and rating filters |
| 227 | SEC-15 | LOW |  | `OrderReturnCreateRequest.refundAmount` has no scale bound |
| 228 | SEC-16 | LOW |  | `ProductCreateRequest.priceUnit` / `weight` have no scale bound |
| 229 | SEC-17 | LOW |  | `forgot-password` leaks account existence through response latency |
| 230 | SEC-18 | LOW |  | Elasticsearch client major version (9.x) vs server (8.15.0) |
| 231 | SEC-19 | LOW |  | `UUID.fromString(jwt.sub)` throws on a service-account token |
| 232 | SEC-20 | LOW |  | `ServiceCallerGuard.requireCaller` fails open when the security context is empty |
| 233 | SEC-21 | LOW |  | The Keycloak master-realm password grant remains reachable as a fallback |
| 234 | SEC-22 | LOW |  | `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` defaults to 0 in the base compose |
| 235 | SEC-23 | LOW |  | No HTTPS enforcement, no HSTS and no CSP at the application layer |
| 236 | SEC-24 | LOW |  | Stripe search query built by string concatenation |
| 237 | SEC-25 | LOW |  | Carrier request body built by string concatenation |
| 238 | SEC-26 | LOW |  | A client secret is configured for a **public** Keycloak client |
| 239 | SEC-29 | LOW |  | The `avatar` URL is persisted and returned but never validated against internal destinations |
| 240 | SEC-30 | LOW |  | The object store is a release candidate and the CI image digests are unpinned |
| 241 | SL-21 | LOW |  | `DateTimeUtils` cache eviction is a check-then-act race with a whole-cache flush |
| 242 | SL-23 | LOW |  | `AuditActorResolver` treats an `AnonymousAuthenticationToken` as an authenticated caller |
| 243 | SL-27 | LOW |  | `SecurityProperties` null-guards `expectedAudiences` but not `expectedClientIds` |
| 244 | SL-31 | LOW |  | `Messages` is a static global facade |
| 245 | SL-32 | LOW |  | `common-core`'s `Messages.fromBundle` uses `ResourceBundle.getBundle`, whose default-locale fallback can leak a wrong locale |
| 246 | SL-42 | LOW |  | dead retry configuration, and a test name that overstates the guarantee |
| 247 | SL-43 | LOW |  | a deprecated-for-removal serializer is still the default value serializer |
| 248 | SL-44 | LOW |  | truncated/garbled comments indicate partially-reverted edits |
| 249 | SL-45 | LOW |  | constructor-overload explosion instead of a builder |
| 250 | PAY-21 | LOW | Bulkhead | Bound the accepted body size and apply a service level rate limit. |
| 251 | PAY-22 | LOW |  | Remove the dead code. |
| 252 | PAY-23 | LOW |  | Compare against the SDK constant or remove the check. |
| 253 | PAY-24 | LOW |  | Remove the unused imports. |
