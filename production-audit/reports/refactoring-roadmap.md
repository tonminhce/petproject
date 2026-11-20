# Refactoring Roadmap

Generated: 2026-11-19T22:33:06Z

The roadmap is ordered by business risk. Every entry names the design pattern that
resolves it and the evidence that justifies it.

| Order | ID | Severity | Pattern | Change |
| --- | --- | --- | --- | --- |
| 1 | SHIP-01 | CRITICAL | Reservation | The order id is claimed in a shipment_creation_tasks row before the carrier call; a partial unique index on order_id arbitrates the claim; the carrier request carries a deterministic Idempotency-Key. |
| 2 | GW-01 | CRITICAL |  | The trusted-proxy hop count is an index into an attacker-controlled header; the transport peer is never verified |
| 3 | GW-02 | CRITICAL |  | A non-literal `X-Forwarded-For` entry becomes the rate-limit key: per-request bucket bypass, unbounded Redis key growth, and blocking DNS on the event loop |
| 4 | PAY-01 | CRITICAL | Strategy | Either confirm the intent server-side with a configured payment method and verify the intent status, or return the client_secret to the caller and only transition to CAPTURED after a verified provider confirmation event. |
| 5 | QA-01 | CRITICAL |  | The harness certified readiness from stale suite evidence that predated the current source revision |
| 6 | PAY-02 | CRITICAL | Factory Method | Reject an unknown provider with PAYMENT_PROVIDER_REJECTED. Remove the silent fallback. Make MockProvider conditional so it cannot silently back a production payment. |
| 7 | SHIP-02 | HIGH | Reconciliation Sweeper | The failure is recorded on the creation task with an attempt count and a next attempt time, and a retry worker picks it up. |
| 8 | SHIP-03 | HIGH | Adapter | The carrier request factory declares a connect and read timeout inside a budget. |
| 9 | SHIP-04 | HIGH | Migration | A new changeset 002b widens the constraint before 003 runs; the already applied changesets 001 to 006 are untouched so their checksums stay valid. |
| 10 | CAT-06 | HIGH |  | The compose Elasticsearch server was pinned to 8.15.0 while the managed Java client is 9.4.5, and Elasticsearch refuses a direct 8.15 to 9.4.5 upgrade |
| 11 | OBS-08 | HIGH |  | The compose stack held three different Redis passwords across the environment file and the running containers |
| 12 | PAY-40 | HIGH |  | The Stripe sandbox credential is a fabricated local placeholder, so no genuine Stripe interaction has ever occurred |
| 13 | PAY-41 | HIGH |  | Execute the real Stripe sandbox acceptance matrix with a genuine test-mode credential |
| 14 | RTG-01 | HIGH | Circuit Breaker | The upstream failure escapes the guarded call as BusinessException.serviceUnavailable (503 ERR-0503) with the cause preserved; the circuit breaker records it; a fallback maps an open breaker to the same dependency failure; a genuine not-eligible answer still returns 403 RTG-11001. |
| 15 | SHIP-05 | HIGH | State Machine | The state machine owns every transition, the status setters are closed, and the sweep goes through the state machine with a per-row error boundary. |
| 16 | SHIP-06 | HIGH | State Machine | The edge is removed; an operator retry is the only way out of DELIVERY_FAILED; the webhook path uses the state machine. |
| 17 | SHIP-07 | HIGH | Reconciliation Sweeper | A conditional update claims the row with a claimed_at stamp and the claim is released on failure. |
| 18 | SHIP-08 | HIGH | Transactional Outbox | The claim selects with ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED, so a locked row is skipped. |
| 19 | OBS-05 | HIGH |  | The gateway service exposed no Prometheus metrics because the micrometer registry was missing from its module |
| 20 | OBS-06 | HIGH |  | The Prometheus scrape configuration referenced exporter containers that did not exist, so every infrastructure rule fired spuriously |
| 21 | OBS-07 | HIGH |  | The runtime alert rules divided by sentinel metric values and produced false positives on every service |
| 22 | PERF-01 | HIGH |  | Load measurements taken while parallel Maven builds saturated the host produced invalid latency and throughput figures |
| 23 | SL-46 | HIGH | Adapter | One shared KeycloakServiceTokenProvider in common-keycloak with a pooled HttpClient, an Accept: application/json default header, a bounded retry inside a total refresh budget, automatic transport retries disabled so the policy is in one place, and a failure cooldown that collapses a refresh herd. The five service classes became thin adapters. |
| 24 | SL-47 | HIGH | Adapter | A distinct service-account-client-id property (default ecommerce-admin) drives the client_credentials grant, while the master-realm password grant keeps admin-cli. |
| 25 | K8S-21 | HIGH |  | No startup probe on any kubernetes workload |
| 26 | K8S-22 | HIGH |  | No container or pod security context on any kubernetes workload |
| 27 | K8S-23 | HIGH |  | No pod disruption budget protects the multi replica deployments |
| 28 | OBS-01 | HIGH |  | Implement the Prometheus to Alertmanager to Telegram runtime alert pipeline |
| 29 | OBS-02 | HIGH |  | Add the CI Telegram notification workflow with failure grouping and secret isolation |
| 30 | OBS-03 | HIGH |  | Verify real Telegram delivery for the CI and runtime routes |
| 31 | PAY-06 | HIGH | Saga | Consume the order lifecycle topic, and on a late capture for a cancelled order emit a compensation refund. |
| 32 | PAY-04 | HIGH | Idempotent Consumer | Derive the dedup identity only from the signed content and the provider identity bound to the verifying secret. |
| 33 | CAT-01 | HIGH |  | Out-of-order Kafka events silently overwrite newer Elasticsearch state |
| 34 | CAT-02 | HIGH |  | Elasticsearch client dependency drift |
| 35 | CAT-05 | HIGH |  | No currency on any money field in the fleet |
| 36 | CAT-07 | HIGH |  | Promotion quotas are enforced by a racy read-then-write COUNT |
| 37 | GW-03 | HIGH |  | Contradictory Redis-outage semantics across the three limiters, and the in-code justification is factually wrong |
| 38 | GW-04 | HIGH |  | The "global" limiter is a single, non-partitioned bucket: no fairness, and the fleet cap does not scale with replicas |
| 39 | GW-05 | HIGH |  | The edge bucket computes its refill from the **gateway JVM clock** while the framework buckets use the **Redis server clock** |
| 40 | KAF-01 | HIGH |  | 6 of 8 outbox relays serialise: a second replica publishes nothing |
| 41 | KAF-02 | HIGH |  | payment-service relay has no per-aggregate ordering barrier |
| 42 | OI-01 | HIGH |  | The inventory `reserve` API is not idempotent: duplicate holds and 900-second orphan holds |
| 43 | OI-02 | HIGH |  | A failed `releaseCommitted` in the confirm-compensation path is dropped: no ledger, no retry, stock permanently consumed |
| 44 | PAY-03 | HIGH | State | Reject identity transitions in the state machine and publish a lifecycle event only when the status actually changed. |
| 45 | PAY-30 | HIGH | Adapter | A dedicated PSP callback adapter per provider that verifies the vendor signature, maps the PSP payload to the carrier payload and hands it to the existing webhook event service. |
| 46 | SEC-01 | HIGH |  | auth-service resolves the JWT subject against a DIFFERENT primary-key space |
| 47 | SEC-02 | HIGH |  | Cross-service IDOR: `verify-purchase` accepts an arbitrary `userId` with no principal cross-check |
| 48 | SEC-03 | HIGH |  | Committed literal credentials in `.env.example` for accounts the deployment *requires* |
| 49 | SL-01 | HIGH |  | `KeycloakAdminClient.getAdminAccessToken()` holds a JVM monitor across a network call |
| 50 | SL-02 | HIGH |  | `KeycloakAdminClient` silently falls back to the master-realm *password* grant |
| 51 | SL-05 | HIGH |  | `DeadLetterReplayService.replay()` re-reads the whole DLT on every invocation and re-publishes already-replayed records |
| 52 | SL-08 | HIGH |  | `BaseKafkaConsumer.WIRE_MAPPER` is a bare `ObjectMapper` that rejects unknown properties |
| 53 | SL-14 | HIGH |  | `CorrelationIdFilter` trusts an unvalidated client-supplied header and writes it into the MDC |
| 54 | SL-15 | HIGH |  | `RequestUtils.getClientIp()` trusts client-supplied proxy headers unconditionally |
| 55 | SL-16 | HIGH |  | `HttpLoggingFilter` buffers the **entire** response body in heap per request |
| 56 | SL-24 | HIGH |  | `ServiceCallerGuard.requireCaller()` **fails open** for an unauthenticated caller |
| 57 | SL-28 | HIGH |  | `ObjectStorageAutoConfiguration` performs a **network call inside a `@Bean` method** |
| 58 | SL-33 | HIGH |  | Keycloak clients bypass the application-configured `RestClient.Builder` |
| 59 | SL-34 | HIGH |  | the DLT-replay actuator endpoint has no authorization |
| 60 | SL-35 | HIGH |  | a listener factory without a recoverer silently drops records |
| 61 | SL-36 | HIGH |  | the shared consumer configuration pins none of the commit/isolation semantics |
| 62 | SL-37 | HIGH |  | the fleet-wide `expected-client-ids` list is the platform's only horizontal-privilege boundary, and it is broad |
| 63 | PAY-05 | HIGH | Reservation | Claim the rows with a lease column and a conditional update, or use SELECT FOR UPDATE SKIP LOCKED inside a transaction. |
| 64 | PAY-07 | HIGH | Repository | Make the unique constraint match the lookup scope, for example unique on user_id plus idempotency_key, and align the index name. |
| 65 | PAY-08 | HIGH | Repository | Persist the provider intent id on the payment row at creation and use it for refunds. |
| 66 | PAY-09 | HIGH | Specification | Distinguish a definitive decline from an indeterminate outcome, and on an indeterminate outcome reconcile by idempotency key rather than inventing an identifier. |
| 67 | PAY-10 | HIGH | Adapter | Move the provider call outside the transaction and configure explicit connect and read timeouts. |
| 68 | SHIP-11 | MEDIUM | Migration | The index matches the due-retry predicate. |
| 69 | SHIP-12 | MEDIUM | Repository | The restriction matches the partial index and the creation claim reopens the soft-deleted row. |
| 70 | SHIP-13 | MEDIUM | Migration | The foreign key is added, validated for existing rows and enforced for new rows. |
| 71 | SHIP-14 | MEDIUM | Idempotent Consumer | A signed payload without an event id is rejected with a bad request before any write, and an insert violation is classified by constraint name. |
| 72 | SHIP-15 | MEDIUM | Reconciliation Sweeper | The exhausted row is parked as DEAD, counted by a metric, and purged by the retention scheduler. |
| 73 | PAY-31 | MEDIUM | Adapter | The verifier accepts any of the documented field set variants and rejects only a body whose signature matches none of them |
| 74 | PAY-32 | MEDIUM | Adapter | The refund response signature is verified over the documented response field set. |
| 75 | PAY-33 | MEDIUM | Adapter | The production deployment requires the provider to be named explicitly and the startup message names the variable to set |
| 76 | K8S-25 | MEDIUM |  | The Kubernetes deployment set covers only a subset of the fourteen platform services |
| 77 | PAY-19 | MEDIUM | Saga | Wire the checkout saga to create and capture the payment, or document the external caller contract and add the missing consumer. |
| 78 | K8S-24 | MEDIUM |  | No service account is declared for the platform workloads |
| 79 | PAY-15 | MEDIUM | Strategy | Use the currency-unit rules already present in StripeCurrencyUnits and either integrate the PSP or remove the provider from the selectable set. |
| 80 | CAT-03 | MEDIUM |  | Mutable, non-volatile pagination state on a singleton scheduled bean |
| 81 | CAT-04 | MEDIUM |  | Money stored as an IEEE-754 double in the search index |
| 82 | CAT-08 | MEDIUM |  | Reindex lock is in-process only |
| 83 | CAT-09 | MEDIUM |  | Product outbox claim lease piggy-backs on the `updated_at` audit column |
| 84 | CAT-10 | MEDIUM |  | `FAILED` outbox rows are terminal in product, rating and promotion (no replay, no DEAD aging) |
| 85 | CAT-11 | MEDIUM |  | No concurrency admission control on the media decode path |
| 86 | CAT-13 | MEDIUM |  | No Elasticsearch mapping-version gate |
| 87 | CAT-16 | MEDIUM |  | Reservation gates are an if/else chain |
| 88 | CAT-17 | MEDIUM |  | `FAILED` media outbox rows are re-published every 2 seconds for 7 days |
| 89 | CAT-18 | MEDIUM |  | Inconsistent event-naming convention across producers |
| 90 | CAT-19 | MEDIUM |  | The rating aggregate snapshot can be stale under concurrent reviews |
| 91 | CAT-23 | MEDIUM |  | No retention purge for the `notifications` table |
| 92 | GW-06 | MEDIUM |  | Two to three serialised Redis round trips per request, and rejected traffic still burns the fleet-wide budget |
| 93 | GW-07 | MEDIUM |  | Inconsistent 429 contract: empty body from L1, duplicate `X-RateLimit-Remaining`, a `-1` sentinel leaked to clients, and no `Retry-After` |
| 94 | GW-08 | MEDIUM |  | `GlobalRateLimitFilter` and `AdminIpAllowlistFilter` share the same order value; the documented "IP allowlist first" contract is not enforced |
| 95 | GW-09 | MEDIUM |  | The lazy JWT decoder performs a blocking OIDC discovery + JWKS fetch **on the event loop** at first decode |
| 96 | GW-10 | MEDIUM |  | The "literal IP" guard in the admin allowlist accepts non-IP strings, so `InetAddress.getByName` performs a DNS lookup on the event loop |
| 97 | GW-11 | MEDIUM |  | The Kubernetes deployment points at a Redis that no manifest in this repo defines, sets no Redis password, and leaves the admin allowlist inactive |
| 98 | GW-12 | MEDIUM |  | The edge limiter's two Redis keys have no hash tag ⇒ `CROSSSLOT` failure in Redis Cluster |
| 99 | GW-13 | MEDIUM |  | Unauthenticated requests are not metered by any gateway limiter (401 is returned before the limiters run) |
| 100 | GW-26 | MEDIUM |  | Duplicate/ambiguous `X-RateLimit-Remaining` header (merged with GW-07 above as the header part) |
| 101 | GW-27 | MEDIUM |  | No circuit breaker and no bulkhead anywhere in the gateway |
| 102 | GW-29 | MEDIUM |  | INFO — Scope/bypass asymmetry between the IP allowlist and the edge rate limiter |
| 103 | KAF-03 | MEDIUM |  | `BaseKafkaConsumer` / `KafkaProperties` javadoc contradicts the code on poison handling |
| 104 | KAF-04 | MEDIUM |  | `shop.kafka.retry.*` is bindable but has zero effect |
| 105 | KAF-05 | MEDIUM |  | product-service's media listener has no error handler, hence no DLT |
| 106 | KAF-06 | MEDIUM |  | No topic provisioning anywhere in the repo |
| 107 | KAF-07 | MEDIUM |  | product-service and payment-service use the generic `updated_at` column as the claim lease |
| 108 | KAF-08 | MEDIUM |  | search-service's DLT depends on an optional bean and fails open |
| 109 | KAF-09 | MEDIUM |  | notification-service's dedup key silently depends on the event id being a parseable UUID |
| 110 | KAF-20 | MEDIUM |  | The DLT replay endpoint is disabled in every service: the DLT is a write-only bucket |
| 111 | OBS-04 | MEDIUM |  | Expose the compose, load and stripe harness command families required by the operating contract |
| 112 | OI-03 | MEDIUM |  | A failure of the saga's final transaction leaks every stock reservation |
| 113 | OI-04 | MEDIUM |  | `idempotencyService.complete()` failing after the order is committed frees the key and lets a retry create a second order |
| 114 | OI-05 | MEDIUM |  | The duplicate-checkout protection is opt-in: a checkout without an `Idempotency-Key` header gets none |
| 115 | OI-06 | MEDIUM |  | The expiry sweep publishes a spurious `inventory.released.v1` for reservations it did not claim |
| 116 | OI-07 | MEDIUM |  | The reconciliation scheduler bypasses the order state machine |
| 117 | OI-08 | MEDIUM |  | The reconciliation auto-confirm bypasses the captured-payment gate |
| 118 | OI-09 | MEDIUM |  | `Cart` has no `@Version`: the cart subtotal is a lost update |
| 119 | OI-10 | MEDIUM |  | `CompensationFailureService.retryPending` performs up to 50 sequential HTTP calls inside a single transaction |
| 120 | OI-11 | MEDIUM |  | `InventoryServiceImpl.delete()`'s active-reservation guard is a check-then-act |
| 121 | OI-12 | MEDIUM |  | The refund gateway is called before the state is persisted: a DB failure makes the return re-reviewable and the refund repeatable |
| 122 | OI-13 | MEDIUM |  | The `productPrice` cache is never invalidated: up to 10 minutes of stale checkout pricing |
| 123 | OI-14 | MEDIUM |  | The return flow's cumulative-refund and pending-return checks are unlocked read-modify-write |
| 124 | OI-28 | MEDIUM |  | INFO — YAML/code drift on three scheduler knobs |
| 125 | OI-29 | MEDIUM |  | INFO — `ReturnStatus.APPROVED` is a dead state and the return flow has no transition table |
| 126 | PAY-11 | MEDIUM | State | Route every transition through the state machine and record refunded_amount. |
| 127 | SEC-04 | MEDIUM |  | No `aud` validation fleet-wide; a single shared `azp` allow-list for every service |
| 128 | SEC-05 | MEDIUM |  | The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding |
| 129 | SEC-06 | MEDIUM |  | Token and session lifetimes are not pinned in the realm and are not asserted anywhere |
| 130 | SEC-07 | MEDIUM |  | Password-reset accepts a weaker password than registration |
| 131 | SEC-08 | MEDIUM |  | The Keycloak error body is relayed to the client on non-401 auth failures |
| 132 | SEC-09 | MEDIUM |  | The shipping webhook verifier has no replay window |
| 133 | SEC-10 | MEDIUM |  | CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches |
| 134 | SEC-11 | MEDIUM |  | The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens |
| 135 | SEC-12 | MEDIUM |  | Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously |
| 136 | SEC-27 | MEDIUM |  | `auth-service` public-path rules are method-agnostic |
| 137 | SEC-28 | MEDIUM |  | Seeded credential pairs are documented in tracked markdown |
| 138 | SL-03 | MEDIUM |  | `LazyJwtDecoder` builds **N** decoders under concurrent first-request load (javadox guarantee is false) |
| 139 | SL-04 | MEDIUM |  | `LazyJwtDecoder` performs a synchronous OIDC discovery on the **request** thread with no negative cache |
| 140 | SL-06 | MEDIUM |  | `DeadLetterReplayService.replay()` commits the offset of a record it **failed** to republish |
| 141 | SL-07 | MEDIUM |  | `DeadLetterReplayService` blocks forever on a hung broker and loses trace context |
| 142 | SL-09 | MEDIUM |  | `BaseKafkaConsumer` javadoc contradicts its code about DLT routing |
| 143 | SL-10 | MEDIUM |  | `JsonKafkaDeserializer` throws `RuntimeException`, which defeats the `ErrorHandlingDeserializer` DLT contract |
| 144 | SL-11 | MEDIUM |  | `JsonKafkaSerializer` silently degrades a failed serialization to a **non-JSON** payload |
| 145 | SL-12 | MEDIUM |  | `KafkaProperties.buildConsumerProperties()` leaves critical consumer settings to framework defaults |
| 146 | SL-13 | MEDIUM |  | `KafkaProperties.ConsumerConfig.groupId` default is a **fleet-wide** shared identity |
| 147 | SL-17 | MEDIUM |  | `ApiExceptionHandler` echoes the raw exception message for two request-shape errors |
| 148 | SL-18 | MEDIUM |  | `KeycloakTokenClient.verifyCredentials()` cannot distinguish "wrong password" from "Keycloak is down" |
| 149 | SL-19 | MEDIUM |  | `KeycloakAdminClient.updateUserEnabled()` can leave live sessions after a successful disable |
| 150 | SL-20 | MEDIUM |  | `KeycloakAdminClient.buildUserPayload()` hard-codes `emailVerified=true` |
| 151 | SL-22 | MEDIUM |  | `BoundedAsyncAuditEventWriter` silently discards audit events under back-pressure |
| 152 | SL-25 | MEDIUM |  | `ServiceCallerGuard` lets any holder of a realm ADMIN/MANAGER role bypass the client check entirely |
| 153 | SL-26 | MEDIUM |  | `AuthenticatedUser.from(Jwt)` takes its authorities from the ambient context, not from the argument |
| 154 | SL-29 | MEDIUM |  | `S3ObjectStorageService.download()` buffers up to 50 MB per call |
| 155 | SL-30 | MEDIUM |  | Five `CommonProperties` toggles are dead configuration |
| 156 | SL-38 | MEDIUM |  | the CORS source bean cannot be overridden |
| 157 | SL-39 | MEDIUM |  | the CORS code default is a wildcard origin |
| 158 | SL-40 | MEDIUM |  | the body masker only understands JSON |
| 159 | SL-41 | MEDIUM |  | the shared defaults activate the `dev` profile |
| 160 | PAY-12 | MEDIUM | Optimistic Locking | Persist the provider intent before the guarded transition and make the transition retry-safe. |
| 161 | PAY-13 | MEDIUM | Specification | Require a signed timestamp and enforce the tolerance on every provider branch. |
| 162 | RES-01 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 163 | RES-02 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 164 | RES-03 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 165 | RES-04 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 166 | RES-05 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 167 | RES-06 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 168 | RES-07 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 169 | RES-08 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 170 | RES-09 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 171 | RES-10 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 172 | RES-11 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 173 | RES-12 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 174 | RES-13 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 175 | RES-14 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 176 | RES-15 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 177 | RES-16 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 178 | RES-17 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 179 | RES-18 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 180 | RES-19 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 181 | RES-20 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 182 | RES-21 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 183 | RES-22 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 184 | RES-23 | MEDIUM |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 185 | PAY-14 | MEDIUM | Specification | Configure a distinct secret per provider and bind the secret to the provider identity. |
| 186 | PAY-16 | MEDIUM | Specification | Validate against an explicit supported-currency set and compare currency on the webhook path. |
| 187 | PAY-17 | MEDIUM | Specification | Compare in the same unit on both sides and define a rounding policy that is symmetric. |
| 188 | PAY-18 | MEDIUM | Reconciliation Sweeper | Purge or archive failed rows once they exceed the retention window. |
| 189 | PAY-34 | LOW | Adapter | The canonical strings come from an independent implementation and a containerised test drives a callback through the payment service to a captured payment |
| 190 | RES-24 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 191 | RES-25 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 192 | RES-26 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 193 | RES-27 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 194 | RES-28 | LOW |  | Align the dependency, the configuration or the test fixture with the official guidance of the version actually declared. |
| 195 | CAT-12 | LOW |  | 7-day presigned-URL TTL for catalogue images |
| 196 | CAT-14 | LOW |  | Unbounded `synchronized` in the search service token provider |
| 197 | CAT-15 | LOW |  | Bucket ACL is asserted once at startup only |
| 198 | CAT-20 | LOW |  | `Rating.verified` is stored but never enforced on the read path |
| 199 | CAT-21 | LOW |  | Tax rounding regime is neither documented nor configurable |
| 200 | CAT-22 | LOW |  | The pricing contract has no net/gross declaration |
| 201 | CAT-24 | LOW |  | `@Value`-injected configuration fields are non-final on singletons |
| 202 | CAT-25 | LOW |  | `CouponUsageReservation.@Version` is hand-bumped inside a JPQL string |
| 203 | GW-14 | LOW |  | / — No bound on the number of rate-limit keys, and the shared Redis has no memory policy |
| 204 | GW-15 | LOW |  | Inconsistent key spaces between the layers ⇒ an authenticated client gets up to 3× the configured budget; IPv4/IPv6 spellings are separate buckets |
| 205 | GW-16 | LOW |  | The edge bucket's TTL is refreshed on rejected requests, so an abuser's keys never expire and the key set only grows |
| 206 | GW-17 | LOW |  | `replenishRate` (tokens per **second**) and `requestsPerMinute` (requests per **minute**) live under two similarly named property namespaces |
| 207 | GW-18 | LOW |  | No `Retry-After` header on any 429 |
| 208 | GW-19 | LOW |  | The root POM comment "gateway migrated to Apache APISIX" is false and misleads every future reader |
| 209 | GW-20 | LOW |  | The "fail-fast" posture validator never verifies that the peer is a trusted proxy |
| 210 | GW-21 | LOW |  | `RateLimitKeyResolver` is unit-tested against a **mocked** exchange, so a framework regression would silently switch every L1 key to an IP key |
| 211 | GW-22 | LOW |  | Two independent issuer properties can drift; the one the decoder actually uses is not the one the gateway validates |
| 212 | GW-23 | LOW |  | No explicit JWT algorithm pinning |
| 213 | GW-24 | LOW |  | /INFO — The caller-supplied `X-Correlation-Id` is echoed into the error envelope without validation |
| 214 | GW-25 | LOW |  | (latent) — `AbstractRateLimiter`'s configuration map is a plain `HashMap` written from the event thread and read from request threads |
| 215 | GW-28 | LOW |  | `RedisEdgeRateLimiter.requestsPerMinute(String)` is dead code |
| 216 | KAF-10 | LOW |  | Producer payloads are untyped maps; the schema is not compile-time checked |
| 217 | KAF-11 | LOW |  | `FAILED` outbox rows are never purged in 7 of 8 services |
| 218 | KAF-12 | LOW |  | `DEAD` rows are never purged (media-service) and payment's `DEAD` purge is unreachable code |
| 219 | KAF-13 | LOW |  | `outbox_events.event_id` UNIQUE protects against nothing but UUID collisions |
| 220 | KAF-14 | LOW |  | The snapshot discipline that makes idempotency work is unenforced (latent  if a delta event is ever introduced) |
| 221 | KAF-15 | LOW |  | `enable.auto.commit=false` is inherited from a framework default, not asserted by fleet code |
| 222 | KAF-16 | LOW |  | order-service's non-transactional fallback paths would break atomicity if the tx manager were absent |
| 223 | KAF-17 | LOW |  | The legacy double-encoding `KafkaTemplate` is still registered and is the default type-match |
| 224 | KAF-18 | LOW |  | DLT replay drops the original headers (including `traceparent`) |
| 225 | KAF-19 | LOW |  | No `max.poll.interval.ms` is configured for any listener |
| 226 | OI-15 | LOW |  | `failed_compensations` has no uniqueness on `(reservation_id, kind)` |
| 227 | OI-16 | LOW |  | Three retention schedulers perform unbounded bulk DELETEs in a single transaction |
| 228 | OI-17 | LOW |  | The inventory outbox claim shape serialises every relay pod on the head row |
| 229 | OI-18 | LOW |  | `atomicReserve` lacks `flushAutomatically = true` (a latent trap) |
| 230 | OI-19 | LOW |  | The create saga reserves N lines sequentially (N × timeout, N simultaneous holds) |
| 231 | OI-20 | LOW |  | The "LIFO" compensation order is not deterministic under the parallel commit fan-out |
| 232 | OI-21 | LOW |  | The inventory retry budget is 3 attempts with a 50 ms base backoff |
| 233 | OI-22 | LOW |  | The static fan-out executors use unbounded queues against a 50-connection-per-route HTTP pool |
| 234 | OI-23 | LOW |  | A 5xx from product-service escapes as a raw exception (surfaced as a 500, not a 503) |
| 235 | OI-24 | LOW |  | `OrderStatusController.ship`/`deliver` have no per-order authorization |
| 236 | OI-25 | LOW |  | The reconciliation sweeper decides on a stale detached snapshot |
| 237 | OI-26 | LOW |  | `GuestCartCleanupScheduler` purges active carts of real users and is unbounded |
| 238 | OI-27 | LOW |  | The tracking abuse brake under-counts under concurrency |
| 239 | PAY-20 | LOW | Factory Method | Fail fast when the configured provider is not registered. |
| 240 | SEC-13 | LOW |  | Unbounded / zero-allowed quantity and amount fields |
| 241 | SEC-14 | LOW |  | `SearchRequest` accepts unbounded/negative price and rating filters |
| 242 | SEC-15 | LOW |  | `OrderReturnCreateRequest.refundAmount` has no scale bound |
| 243 | SEC-16 | LOW |  | `ProductCreateRequest.priceUnit` / `weight` have no scale bound |
| 244 | SEC-17 | LOW |  | `forgot-password` leaks account existence through response latency |
| 245 | SEC-18 | LOW |  | Elasticsearch client major version (9.x) vs server (8.15.0) |
| 246 | SEC-19 | LOW |  | `UUID.fromString(jwt.sub)` throws on a service-account token |
| 247 | SEC-20 | LOW |  | `ServiceCallerGuard.requireCaller` fails open when the security context is empty |
| 248 | SEC-21 | LOW |  | The Keycloak master-realm password grant remains reachable as a fallback |
| 249 | SEC-22 | LOW |  | `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` defaults to 0 in the base compose |
| 250 | SEC-23 | LOW |  | No HTTPS enforcement, no HSTS and no CSP at the application layer |
| 251 | SEC-24 | LOW |  | Stripe search query built by string concatenation |
| 252 | SEC-25 | LOW |  | Carrier request body built by string concatenation |
| 253 | SEC-26 | LOW |  | A client secret is configured for a **public** Keycloak client |
| 254 | SEC-29 | LOW |  | The `avatar` URL is persisted and returned but never validated against internal destinations |
| 255 | SEC-30 | LOW |  | The object store is a release candidate and the CI image digests are unpinned |
| 256 | SL-21 | LOW |  | `DateTimeUtils` cache eviction is a check-then-act race with a whole-cache flush |
| 257 | SL-23 | LOW |  | `AuditActorResolver` treats an `AnonymousAuthenticationToken` as an authenticated caller |
| 258 | SL-27 | LOW |  | `SecurityProperties` null-guards `expectedAudiences` but not `expectedClientIds` |
| 259 | SL-31 | LOW |  | `Messages` is a static global facade |
| 260 | SL-32 | LOW |  | `common-core`'s `Messages.fromBundle` uses `ResourceBundle.getBundle`, whose default-locale fallback can leak a wrong locale |
| 261 | SL-42 | LOW |  | dead retry configuration, and a test name that overstates the guarantee |
| 262 | SL-43 | LOW |  | a deprecated-for-removal serializer is still the default value serializer |
| 263 | SL-44 | LOW |  | truncated/garbled comments indicate partially-reverted edits |
| 264 | SL-45 | LOW |  | constructor-overload explosion instead of a builder |
| 265 | PAY-21 | LOW | Bulkhead | Bound the accepted body size and apply a service level rate limit. |
| 266 | PAY-22 | LOW |  | Remove the dead code. |
| 267 | PAY-23 | LOW |  | Compare against the SDK constant or remove the check. |
| 268 | PAY-24 | LOW |  | Remove the unused imports. |
