# High-Batch Acceptance Criteria — Independent QA Review

| Field | Value |
| --- | --- |
| Run | `run-20261118-a2b6e6fc` |
| Reviewer role | Permanent independent QA / test engineer (not an implementer) |
| Batch under review | 14 HIGH findings + 3 supporting MEDIUM findings (GW-12, GW-13, GW-27) |
| Repository | `/Users/tonminh-mac/IdeaProjects/untitled5` |
| Working tree at review time | At the **start** of this review, `git status --short` showed **no** modified source file — only `production-audit/control/*`, `production-audit/state/*` (audit tooling, not source). All evidence in §2 was therefore captured against the **pre-fix** state. The four implementers' diffs landed **during** the review; §2 is not a statement about those diffs, and §3–§5 are the gate they must pass. |
| Write scope of this review | `production-audit/runs/run-20261118-a2b6e6fc/independent-reviews/high-batch-acceptance-criteria.md` (this file only) |
| Build executed | **None.** No Maven build was run: four implementation agents are compiling concurrently against the same local repository. All verification below is static, read-only inspection (`read`, `grep`, `glob`, single-step `bash`). |

---

## 0. Method and independence statement

**Method.** For each finding I (a) located the section in the source review, (b) read the *current* source file end-to-end, and (c) recorded the exact `file:line` that proves or disproves the claim. Where the audit's cited line numbers no longer match the tree, I cite the **current** line numbers and flag the drift — the ledger's `source_evidence` fields are therefore **not** a reliable pointer for the implementers.

**Independence.** This review was produced without reading any implementation diff, and without contacting the four implementers. No production or test source was modified. Nothing in this file constitutes a fix.

**Command surface used (read-only):**

```
grep -rn 'isLocalAddress|getClientIp' --include=*.java .
grep -rn 'markableAsSent' --include=*.java .
grep -n 'elasticsearch' -i search-service/pom.xml ; grep -n 'elasticsearch' -i pom.xml
grep -n 'PASSWORD|SECRET|KEYCLOAK_ADMIN|_KEY=|TOKEN' .env.example
grep -rn 'resilience4j|CircuitBreaker|Bulkhead' gateway-service/
git ls-files .env.example .env.prod.example docker/keycloak/import/ecommerce-realm.json
git status --short
```

**Severity scale used by the audit** (`source-reviews/kafka-outbox.md:822`): HIGH = data-loss / invariant violation reachable in normal operation; MEDIUM = correctness or operability gap with a workaround or bounded blast radius; LOW = hygiene, dead config, latent risk.

---

## 1. Independent verdict summary

| ID | Title (abbreviated) | Audit severity | **QA verdict** | Decisive current evidence |
| --- | --- | --- | --- | --- |
| SL-01 | `getAdminAccessToken()` holds a JVM monitor across a network call | HIGH | **CONFIRMED** | `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:328` (`synchronized (this)`) wrapping the HTTP call at `:345-350` |
| SL-02 | Silently falls back to the master-realm password grant | HIGH | **CONFIRMED** | `KeycloakAdminClient.java:118-121` (requires secret **AND** endpoint) + `:334-342` (`GRANT_TYPE=password`) |
| SL-14 | `CorrelationIdFilter` trusts an unvalidated client header into the MDC | HIGH | **CONFIRMED** | `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/CorrelationIdFilter.java:31-42`, `:53-56` |
| SL-15 | `RequestUtils.getClientIp()` trusts proxy headers unconditionally | HIGH | **CONFIRMED** | `utils/common-spring/src/main/java/com/shop/common/spring/web/util/RequestUtils.java:8-14`, `:18-27` |
| SL-16 | `HttpLoggingFilter` buffers the entire response body per request | HIGH | **CONFIRMED** | `utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java:57` |
| SL-28 | Network call inside a `@Bean` method | HIGH | **CONFIRMED** | `utils/common-storage/src/main/java/com/shop/common/storage/config/ObjectStorageAutoConfiguration.java:54` |
| SL-33 | Keycloak clients bypass the app-configured `RestClient.Builder` | HIGH | **CONFIRMED** | `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java:45`, `:59` |
| SL-37 | Fleet-wide `expected-client-ids` is the only horizontal-privilege boundary | HIGH | **CONFIRMED** | `utils/common-spring/src/main/resources/application.yml:95` + `utils/common-security/.../BaseSecurityConfig.java:98-105` |
| KAF-01 | 6 of 8 outbox relays serialise | HIGH | **CONFIRMED — SCOPE CORRECTED: 5 of 8** | 5× `MIN(e2.id)` subquery; shipping-service already uses the safe native form |
| KAF-02 | payment-service relay has no per-aggregate ordering barrier | HIGH | **CONFIRMED** | `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java:87-96` — no `markableAsSent` call in the file |
| GW-04 | Global limiter is one non-partitioned bucket, cap does not scale | HIGH | **CONFIRMED** | `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:14-15`, `:41` |
| GW-12 | Edge limiter's two Redis keys have no hash tag | MEDIUM | **CONFIRMED (latent in the shipped topology)** | `gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java:65-67` |
| GW-13 | Unauthenticated requests are not metered | MEDIUM | **CONFIRMED** | `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java:44-46` + all four filters are `GlobalFilter` |
| GW-27 | No circuit breaker and no bulkhead anywhere in the gateway | MEDIUM | **CONFIRMED** | `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java:44-51`; 0 resilience4j hits in `gateway-service/` |
| CAT-02 | Elasticsearch client dependency drift | HIGH | **CONFIRMED** | `search-service/pom.xml:36`, `:41` vs root `pom.xml:55`, `:206-207` |
| SEC-03 | Committed literal credentials in `.env.example` | HIGH | **CONFIRMED** | `.env.example:270` `KEYCLOAK_ADMIN_USER_PASSWORD=adminpass` → `docker/keycloak/import/ecommerce-realm.json:152`, `:161`, `:165-168` (`ADMIN`+`MANAGER`) |

**No finding was found NOT REPRODUCIBLE.** Two findings carry corrections that change the *scope* of the work and are carried into §2/§3 as binding conditions:

- **KAF-01 is 5 of 8, not 6 of 8.** `shipping-service` already uses the safe native query form and already asserts the correct behaviour.
- **GW-12 is latent in the shipped topology.** `docker-compose.yml:64-68` runs a **standalone** `redis:7.4-alpine` with no `--cluster-enabled`, so `CROSSSLOT` cannot fire today. The key layout still violates the invariant and must be fixed, but the audit's impact sentence ("every backoffice/search request 500s") is wrong twice over: under the default `outage-policy: FAIL_CLOSED` (`gateway-service/src/main/resources/application.yml:71`) the outcome is **503**, not 500, and only on a Cluster deployment.

---

## 2. TASK 1 — Independent confirmation, finding by finding

### 2.1 SL-01 — monitor held across a network call

- **Audit source:** `source-reviews/shared-libraries.md:389` (detail) and `:1189` (severity summary).
- **Audit evidence cited:** `KeycloakAdminClient.java:402-449` — **stale**; the current method is at `:322-369`.
- **Current source** (`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java`):
  - `:322` `private String getAdminAccessToken()`
  - `:323-326` fast path: cached token still valid → return **without** taking the monitor.
  - `:328` `synchronized (this) {`  ← the shared singleton bean's monitor.
  - `:329-332` second check inside the monitor.
  - `:334-336` `if (hasServiceAccountCredentials()) return getServiceAccountAccessToken();` → HTTP at `:130-135`, **still inside the monitor**.
  - `:338-342` the password-grant branch; `:345-350` `restClient.post()...retrieve().body(...)` — **inside the monitor**.
  - `:368` the monitor is released only after the response is parsed and the cache updated.
- **Verdict: CONFIRMED.** The bean monitor is held for the whole duration of a blocking HTTPS round trip. The double-checked locking itself is sound; the defect is the lock *scope*.
- **Correction to the audit's proposed test.** The audit proposes asserting "token requests == 1 **and** total wall time ≈ 500 ms, not 20 × 500 ms" (`shared-libraries.md:1195`). **That test passes on the unfixed code**: the fast path at `:323` is checked *before* the monitor, so only the first thread fetches; the other 19 block on the monitor and then hit the warm cache at `:329-332`. Token requests = 1 and wall time ≈ 500 ms are both already true today. The discriminating case is the **failure path** (§3.1).

### 2.2 SL-02 — silent downgrade to the master-realm password grant

- **Audit source:** `source-reviews/shared-libraries.md:404`, `:1197`.
- **Current source** (`KeycloakAdminClient.java`):
  - `:118-121` `hasServiceAccountCredentials()` → `adminClientSecret != null && !isBlank() && serviceAccountTokenEndpoint != null && !isBlank()` — the **strong** path needs **two** properties.
  - `:334-336` the selection is an `if/else` on that compound predicate.
  - `:338-342` the else-branch builds `GRANT_TYPE=password` with `CLIENT_ID=adminClientId`, `USERNAME=adminUsername`, `PASSWORD=adminPassword`.
  - `:156-159` `hasAdminCredentials()` exists but is **not** used in the selection logic (grep: the only production callers are `auth-service/src/main/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheck.java:35` and the auth-service test).
- **Verdict: CONFIRMED.** Configure `admin-client-secret` and omit the service-account token endpoint and the client silently issues a master-realm password grant on every admin call, with **no** log line and **no** exception.
- **Existing test that pins the weak path:** `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientServiceAccountTest.java:89` `withoutAServiceAccountSecretTheMasterRealmPasswordGrantIsUsed` (asserts `grant_type=password` at `:104`). Any fix must decide deliberately whether the fully-unconfigured password grant is still allowed; silently deleting this test is a regression, not a fix.

### 2.3 SL-14 — unvalidated client header into the MDC

- **Audit source:** `source-reviews/shared-libraries.md:565`, `:1221`. Audit cited `CorrelationIdFilter.java:39-50, 61-64` — **stale**.
- **Current source** (`utils/common-spring/src/main/java/com/shop/common/spring/web/filter/CorrelationIdFilter.java`):
  - `:31-32` `headerOrNew(request, CORRELATION_ID_HEADER)` / `headerOrNew(request, REQUEST_ID_HEADER)`.
  - `:34-35` `MDC.put(MdcKey.CORRELATION_ID, correlationId)` / `MDC.put(MdcKey.REQUEST_ID, requestId)`.
  - `:37-39` **the correlation id is also copied into `MdcKey.TRACE_ID`** when no trace id is present.
  - `:41-42` echoed back as `X-Correlation-Id` / `X-Request-Id` response headers.
  - `:53-56` `headerOrNew` returns `request.getHeader(header)` **verbatim** — no length cap, no charset whitelist, no format check.
- **Sink:** `utils/common-spring/src/main/resources/application.yml:63` `logging.pattern.correlation: "[%X{traceId:-},%X{spanId:-}] "` renders the spoofed trace id into every log line. `utils/common-core/src/main/java/com/shop/common/core/constants/MdcKey.java:5-7` confirms the three MDC keys.
- **Verdict: CONFIRMED.** The client fully controls the correlation id, the request id and (via `:37-39`) the trace id that operators correlate an incident on.

### 2.4 SL-15 — client IP resolved from spoofable headers

- **Audit source:** `source-reviews/shared-libraries.md:582`, `:1229`. Audit cited `RequestUtils.java:12-18, 26-35` — **stale**.
- **Current source** (`utils/common-spring/src/main/java/com/shop/common/spring/web/util/RequestUtils.java`):
  - `:8-14` `IP_HEADER_CANDIDATES` = `X-Original-Forwarded-For`, `X-Forwarded-For`, `Proxy-Client-IP`, `WL-Proxy-Client-IP`, `X-Real-IP`.
  - `:18-27` `getClientIp` returns the **first non-empty** header — no trusted-proxy allow-list, no peer check. `:26` falls back to `request.getRemoteAddr()`.
  - `:34-36` `isLocalAddress(String)` — classifies `127.0.0.1` / `localhost` as local. **Grep over the whole repository: zero callers outside this file** — the bypass primitive is public API but currently latent.
- **Amplifier:** `utils/common-spring/src/main/resources/application.yml:25` `server.forward-headers-strategy: framework` (audit cited `:47` — stale). Spring's own `ForwardedHeaderFilter` therefore also trusts those headers.
- **Sink:** `HttpLoggingFilter.java:72` logs the resolved IP for every request.
- **Verdict: CONFIRMED.** `X-Forwarded-For: 127.0.0.1` from an untrusted peer resolves to `127.0.0.1`.

### 2.5 SL-16 — unbounded per-request response buffering

- **Audit source:** `source-reviews/shared-libraries.md:602`, `:1237`. Audit cited `HttpLoggingFilter.java:64-74` — **stale**.
- **Current source** (`utils/common-spring/src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java`):
  - `:55-56` the **request** wrapper is bounded: `new ContentCachingRequestWrapper(request, props.getRequest().getMaxBodyBytes())`.
  - `:57` the **response** wrapper is **unbounded**: `new ContentCachingResponseWrapper(response)` — the full body accumulates in a `ByteArrayOutputStream` for the lifetime of the request.
  - `:64` `res.copyBodyToResponse()` writes the whole buffer out.
  - `:93-95` `props.getResponse().getMaxBodyBytes()` (`HttpLogProperties.java:49`, default 4096) bounds only the **logged slice**, not the **retained buffer**.
- **Registered by default:** `utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/WebAutoConfiguration.java:30-36` — `@ConditionalOnProperty(..., matchIfMissing = true)`.
- **Verdict: CONFIRMED.** Every response in every adopting service is fully buffered in heap, independent of whether body logging is enabled (both `Request.enabled` and `Response.enabled` default to `false`, `HttpLogProperties.java:28-29`, `:43-44`).

### 2.6 SL-28 — network call inside a `@Bean` method

- **Audit source:** `source-reviews/shared-libraries.md:766`, `:1257`. Audit cited `ObjectStorageAutoConfiguration.java:63-72` — **stale**.
- **Current source:**
  - `utils/common-storage/src/main/java/com/shop/common/storage/config/ObjectStorageAutoConfiguration.java:48-57` — the `@Bean objectStorageService(...)` method; `:54` `service.ensureBucketExists(props.getBucket());` is executed **during bean creation**.
  - `utils/common-storage/src/main/java/com/shop/common/storage/client/S3ClientFactory.java:23-30` — `S3Client.builder()...build()` with **no** `ClientOverrideConfiguration` ⇒ **no `apiCallTimeout` / `apiCallAttemptTimeout`** ⇒ an unreachable endpoint blocks the startup thread indefinitely.
  - `utils/common-storage/src/main/java/com/shop/common/storage/service/S3ObjectStorageService.java:47-59` `ensureBucketExists` = `headBucket` → on `NoSuchBucketException`/404 → `createBucket`.
  - `:61-68` `createBucket` wraps **every** `S3Exception` in a `StorageException` — including `409 BucketAlreadyOwnedByYou`, which two concurrent replicas can produce ⇒ the losing replica **fails to start**.
- **Verdict: CONFIRMED**, with both halves (startup dependency on the store, and the two-replica 409 race) present in the current tree.

### 2.7 SL-33 — Keycloak clients bypass the app-configured `RestClient.Builder`

- **Audit source:** `source-reviews/shared-libraries.md:1265` (section `:29-41` of the audit's own reading), ledger evidence `KeycloakAutoConfiguration.java:62` — **stale**.
- **Current source** (`utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java`):
  - `:29-39` `traceAware(RestClient.Builder)` installs a `SimpleClientHttpRequestFactory` (**not pooled** — one TCP connect + TLS handshake per call, `:30-32`) and a W3C `traceparent` request initialiser.
  - `:45` `traceAware(RestClient.builder())` for `keycloakTokenClient`.
  - `:59` `traceAware(RestClient.builder())` for `keycloakAdminClient`.
  - Both builders are **freshly constructed** — the context's auto-configured `RestClient.Builder` bean is never injected, so every `RestClientCustomizer` bean, Boot's observation instrumentation, and the platform's `traceparent` post-processor are silently bypassed.
- **Module constraint:** `utils/common-keycloak/pom.xml` depends only on `common-core` (`pom.xml:11`, `:17`); `common-spring` depends on `common-keycloak` (`utils/common-spring/pom.xml:29`) — a cycle exists if `common-keycloak` starts depending on `common-spring`. The fix must keep `common-keycloak` a leaf.
- **Verdict: CONFIRMED.**

### 2.8 SL-37 — fleet-wide `expected-client-ids` list

- **Audit source:** `source-reviews/shared-libraries.md:1305`. Audit cited `application.yml:158` and `BaseSecurityConfig.java:135-143` — **stale**.
- **Current source:**
  - `utils/common-spring/src/main/resources/application.yml:95` `expected-client-ids: ${SHOP_SECURITY_EXPECTED_CLIENT_IDS:ecommerce-client,order-service,rating-service,search-service,product-service,media-service}` — **six** clients.
  - `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:98-105` — the `azp` validator is added **only if** `!properties.expectedClientIds().isEmpty()`.
  - `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java:24` `@DefaultValue List<@NotBlank String> expectedClientIds` — no value on the annotation, so the effective value comes from the YAML default.
  - `gateway-service/src/main/resources/application.yml:34` carries the **same six-client default** at the edge.
- **Verdict: CONFIRMED**, and it is indeed the platform's only horizontal-privilege boundary: the only token discriminator wired into the validator chain is `azp` (`BaseSecurityConfig.java:98-105`); `expected-audiences` defaults to empty (`utils/common-spring/src/main/resources/application.yml:96`) and the `aud` validator is therefore not added either (`BaseSecurityConfig.java:89`).
- **Critical mechanism note (see §3.8):** emptying the list does not *narrow* the boundary — it **removes** it.

### 2.9 KAF-01 — outbox claim serialisation

- **Audit source:** `source-reviews/kafka-outbox.md:826`.
- **Current source — the 8 claim queries:**

| Service | Repository file | Claim query | Defective? |
| --- | --- | --- | --- |
| product | `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java:24-26` | `... AND e.id = (SELECT MIN(e2.id) ...)` | **YES** |
| inventory | `inventory-service/src/main/java/com/shop/inventoryservice/repository/OutboxEventRepository.java:25-27` | `... AND e.id = (SELECT MIN(e2.id) ...)` | **YES** |
| media | `media-service/src/main/java/com/shop/mediaservice/outbox/OutboxEventRepository.java:27-29` | `... AND e.id = (SELECT MIN(e2.id) ...)` | **YES** |
| rating | `rating-service/src/main/java/com/shop/ratingservice/outbox/OutboxEventRepository.java:24-26` | `... AND e.id = (SELECT MIN(e2.id) ...)` | **YES** |
| promotion | `promotion-service/src/main/java/com/shop/promotionservice/repository/OutboxEventRepository.java:25-27` | `... AND e.id = (SELECT MIN(e2.id) ...)` | **YES** |
| **shipping** | `shipping-service/src/main/java/com/shop/shippingservice/outbox/OutboxEventRepository.java:22-24` | native `SELECT * FROM outbox_events WHERE status = :status ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED` | **NO** — already the correct range-predicate form |
| order | `order-service/src/main/java/com/shop/orderservice/repository/OutboxEventRepository.java:25` | `findFirstByStatusOrderByIdAsc` | **NO** |
| payment | `payment-service/src/main/java/com/shop/paymentservice/outbox/OutboxEventRepository.java:24` | `findFirstByStatusOrderByIdAsc` | **NO** |

- **Relay consequence:** every relay `break`s out of the claim loop on an empty claim — product `service/OutboxRelay.java:78-80`, payment `outbox/PaymentOutboxRelay.java:68-70`, inventory `service/InventoryOutboxRelay.java:70`, media `outbox/MediaOutboxRelay.java:61`, rating `outbox/RatingOutboxRelay.java:59`, promotion `service/PromotionOutboxRelay.java:71`, shipping `outbox/ShippingOutboxRelay.java:70`.
- **The tests currently pin the defect as intended behaviour:** `ProductOutboxClaimConcurrencyIT.java:169` `assertThat(claimBWhileLocked.get()).isEmpty();` — and the test **name** at `:115` is `twoPendingRows_secondRowUntouchedWhileHeadRowLocked`. Identical assertions: inventory `:169`, media `:169`, rating `:167`, promotion `:169`.
- **The correct reference implementations:**
  - `order-service/src/test/java/com/shop/orderservice/repository/OrderOutboxClaimConcurrencyIT.java:168` → `assertThat(claimBWhileLocked.get().map(OutboxEvent::getId)).contains(row2);` (test name `:114` `twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked`).
  - `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxClaimConcurrencyIT.java:167` → same assertion.
  - `shipping-service/src/test/java/com/shop/shippingservice/outbox/ShippingOutboxClaimConcurrencyIT.java:167-169` → same assertion, with the explanatory `.as("the locked head row must not block the next pending row")` and the test name `:113` `twoPendingRows_theLockedHeadRowDoesNotBlockTheNextRow`.
- **Verdict: CONFIRMED — but the count is 5 of 8, not 6 of 8.** The audit's evidence list (`kafka-outbox.md:827`) wrongly includes `shipping-service/.../outbox/OutboxEventRepository.java:37-39`; that file has no `MIN(e2.id)` query today — it uses the safe native form, and its concurrency test already asserts the *fixed* behaviour. **This correction is binding on the implementation batch**: shipping-service must not be "fixed" and its test must remain green and unchanged.

### 2.10 KAF-02 — payment-service relay has no per-aggregate ordering barrier

- **Audit source:** `source-reviews/kafka-outbox.md:846`.
- **Current source:**
  - `utils/common-kafka/src/main/java/com/shop/common/kafka/producer/OutboxBatchOrdering.java:11-27` — `markableAsSent(keys, delivered)`: once a key fails, every later record with the **same** key is not markable (`:21-24`).
  - `payment-service/src/main/java/com/shop/paymentservice/outbox/PaymentOutboxRelay.java:87-96` — the mark loop uses the **raw per-record outcome** `published` (`:90`) and calls `markFinalState(row, published, error)` directly. **Grep over the whole repository: `markableAsSent` appears in 7 relays and 0 times in payment-service.**
  - The 7 siblings that **do** call it: inventory `InventoryOutboxRelay.java:113`, promotion `PromotionOutboxRelay.java:115`, rating `RatingOutboxRelay.java:102`, media `MediaOutboxRelay.java:105`, shipping `ShippingOutboxRelay.java:114`, product `OutboxRelay.java:111`, order `OrderOutboxRelay.java:115`.
  - Partition key: `PaymentOutboxRelay.java:80` `new OutboxMessage(e.getTopic(), e.getAggregateId().toString(), e.getPayload())` — the aggregate id **is** the Kafka key, so a reorder within a payment is **permanent** in that partition.
- **Existing test:** `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxRelayOrderTest.java` — only two tests, both about `PaymentOutboxRelay.orderById` (`:15`, `:28`). **Nothing tests the barrier.** The audit is right that this test must be extended.
- **Verdict: CONFIRMED.** 7 of 8 relays have the barrier; payment-service is the single omission.

### 2.11 GW-04 — one global bucket, cap that does not scale with replicas

- **Audit source:** `source-reviews/gateway-redis.md:1148`.
- **Current source:**
  - `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:14-15` — `GLOBAL_ROUTE_ID = "gateway-system"`, `GLOBAL_KEY = "system"`; `:41` `rateLimiter.isAllowed(GLOBAL_ROUTE_ID, GLOBAL_KEY)` for **every** request.
  - `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitProperties.java:12-13` — `replenishRate=2000`, `burstCapacity=4000`.
  - `gateway-service/src/main/resources/application.yml:74-76` — the same values, from environment variables with those defaults.
  - `k8s/gateway-service.yaml:9` `replicas: 3`; `:84-85` `minReplicas: 2`, `maxReplicas: 10`.
- **Verdict: CONFIRMED.** A single client can consume the whole 2000 rps / 4000 burst budget, and scaling 2→10 replicas does not move the cap.

### 2.12 GW-12 — no Redis-Cluster hash tag on the edge limiter's key pair

- **Audit source:** `source-reviews/gateway-redis.md:1387`.
- **Current source** (`gateway-service/src/main/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiter.java`):
  - `:17` `KEY_PREFIX = "gateway:edge-rate-limit:"`.
  - `:65-67` the two keys `...:{scope}:{clientIp}:tokens` and `...:{scope}:{clientIp}:timestamp` — **no `{...}` hash tag**.
  - `:68` `redis.execute(TOKEN_BUCKET, keys, ...)` — a two-key `EVAL`.
- **Contrast (the pattern to copy):** the framework's `RedisRateLimiter` uses `"request_rate_limiter.{" + routeId + "." + id + "}."` — an explicit hash tag.
- **Topology caveat:** `docker-compose.yml:64-68` runs a **standalone** `redis:7.4-alpine` (`command: redis-server --requirepass ... --appendonly yes`, no `--cluster-enabled`, single `redis_data` volume) ⇒ `CROSSSLOT` cannot fire in the shipped deployment. The defect is a **latent** cluster-safety violation.
- **Impact correction:** the audit says the edge limiter "500s". With the default `outage-policy: FAIL_CLOSED` (`gateway-service/src/main/resources/application.yml:71`) and `RedisEdgeRateLimiter.acquire`'s `.onErrorResume` (`:75-78`) converting the error into an *outage* decision, the observable outcome is **503 Service Unavailable** (`RateLimitRejectionContractTest` / `RateLimitOutageGuard`), not 500.
- **Verdict: CONFIRMED (latent).** Ledger note: `production-audit/state/tasks.json` has **GW-12 in state `DISCOVERED`**, not `READY` (every other ID in this batch is `READY`) — an orchestrator bookkeeping gap to close before acceptance.

### 2.13 GW-13 — unauthenticated traffic is not metered

- **Audit source:** `source-reviews/gateway-redis.md:1417`.
- **Current source:**
  - `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java:44-46` — `.pathMatchers(publicPaths).permitAll().anyExchange().authenticated()`.
  - **All four** gateway filters are Spring Cloud Gateway `GlobalFilter`s: `filter/RateLimitFilter.java:18`, `filter/AdminIpAllowlistFilter.java:21`, `filter/AdminRoleGateFilter.java:20`, `ratelimit/GlobalRateLimitFilter.java:12`. `GlobalFilter`s execute inside `DispatcherHandler` → **after** the Spring Security `WebFilterChainProxy`. An unauthenticated request to a protected path is therefore rejected with **401 before any limiter runs**.
  - `filter/RateLimitFilter.java:24-26` — the edge scope enum is `{BACKOFFICE, SEARCH}`; **no `AUTH` scope exists**, so `/api/v1/auth/**` (`gateway-service/src/main/resources/application.yml:45`, `shop.security.public-paths` at `:29`) has no dedicated edge limit.
  - Public surface (`gateway-service/src/main/resources/application.yml:37-62`) includes `/api/v1/auth/**`, `/api/v1/products/**`, `/api/v1/search/**`, `/api/v1/webhooks/**`, `/api/v1/orders/track`.
- **Verdict: CONFIRMED**, on both halves: (a) unauthenticated traffic to a protected path consumes no budget; (b) `/api/v1/auth/**` has no dedicated, IP-keyed limit.

### 2.14 GW-27 — no circuit breaker, no bulkhead

- **Audit source:** `source-reviews/gateway-redis.md:1749`.
- **Current source:**
  - `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java:44-51` — each route gets **exactly one** filter (the rate limiter). No timeout, no retry, no circuit breaker, no bulkhead.
  - `gateway-service/src/test/java/com/shop/gateway/GatewayRateLimitContextTest.java:70-71` — `assertThat(routes).hasSize(29)` and `assertThat(routes).allSatisfy(route -> assertThat(route.getFilters()).hasSize(1))`.
  - Grep `resilience4j|CircuitBreaker|Bulkhead` across the repository: **9 hits, all outside `gateway-service/`** (media `MediaReferenceClient.java:31`, rating `EligibilityClient.java:34`, order `PromotionServiceClient.java:49`, `TaxServiceClient.java:34`, `PaymentServiceClient.java:41`, `:62`, `InventoryServiceClient.java:39`, `ProductServiceClient.java:28`). `gateway-service/` → **0 hits**.
  - No `spring.cloud.gateway.httpclient.*` timeout configuration in `gateway-service/src/main/resources/application.yml`.
- **Verdict: CONFIRMED.**

### 2.15 CAT-02 — Elasticsearch client dependency drift

- **Audit source:** `source-reviews/catalog-search-media.md:1818`.
- **Current source:**
  - Root `pom.xml:55` `<elasticsearch-client.version>9.4.5</elasticsearch-client.version>`; `pom.xml:205-207` manages `co.elastic.clients:elasticsearch-java` at `${elasticsearch-client.version}`.
  - `search-service/pom.xml:33-37` — `co.elastic.clients:elasticsearch-java` with a **local** `<version>8.15.5</version>`.
  - `search-service/pom.xml:38-42` — `org.elasticsearch.client:elasticsearch-rest-client` with a **local** `<version>8.15.5</version>`.
  - The root POM manages **only** `elasticsearch-java`; `elasticsearch-rest-client` is **not** in `<dependencyManagement>` anywhere in the repository (grep over all `pom.xml`: the only `elasticsearch` hits are the two in the root POM and the two in `search-service/pom.xml`).
  - `search-service/src/main/java/com/shop/searchservice/config/ElasticsearchConfig.java:41-42` builds `new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()))`.
- **Verdict: CONFIRMED**, with the extra fact that **removing** `search-service/pom.xml:41`'s `<version>` without first adding `elasticsearch-rest-client` to the root `<dependencyManagement>` breaks the build. The root POM is explicitly in the catalog-env agent's write scope ("ES dependency block only") — good.

### 2.16 SEC-03 — committed literal credentials in `.env.example`

- **Audit source:** `source-reviews/security.md:858` (SECRET-LEAK CANDIDATE — CONFIRMED by the audit). Audit cited `.env.example:237`, `:233`, `:52`, `:22/29/80/81/99/100`, `:157` — **all stale**.
- **Current source — the literal values in `.env.example` (tracked; `git ls-files .env.example` confirms it):**

| `.env.example` line | Key | Literal value |
| --- | --- | --- |
| **:270** | `KEYCLOAK_ADMIN_USER_PASSWORD` | **`adminpass`** |
| :266 | `KEYCLOAK_TEST_USER_PASSWORD` | `changeme` |
| :22 | `POSTGRES_PASSWORD` | `admin` |
| :29 | `REDIS_PASSWORD` | `admin` |
| :55 | `KEYCLOAK_ADMIN_PASSWORD` | `local-only-change-me` |
| :59 | `GRAFANA_ADMIN_PASSWORD` | `local-only-change-me` |
| :77 | `ADMIN_SERVICE_CLIENT_SECRET` | `changeme` |
| :87 | `STORAGE_ACCESS_KEY` | `admin` |
| :88 | `STORAGE_SECRET_KEY` | `admin` |
| :107 | `ES_ADMIN_PASSWORD` | `elastic-dev-password-change-me` |
| :114/:137/:144/:264/:265 | `*_SERVICE_CLIENT_SECRET` | `changeme` |
| :152 | `MAIL_PASSWORD` | `changeme` |
| :164 | `PAYMENT_WEBHOOK_SECRET` | `local-test-secret` |

- **Why `:270` is the critical one:** `docker/keycloak/import/ecommerce-realm.json:152` seeds the user `adminuser`; `:158-163` its password credential is the placeholder `"${KEYCLOAK_ADMIN_USER_PASSWORD}"`; `:165-168` its realm roles are `["ADMIN","MANAGER"]`. `docker-compose.yml:188` requires the value: `KEYCLOAK_ADMIN_USER_PASSWORD: ${KEYCLOAK_ADMIN_USER_PASSWORD:?required}`. Copying `.env.example` to `.env` therefore provisions a realm whose `ADMIN`+`MANAGER` account password is published in git. The `# CHANGE IN PROD` trailing comments are advisory only.
- **The realm side is already clean** — `docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 116, 129, 143, 161` are all `${ENV_VAR}` placeholders, enforced by `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/RealmImportCredentialContractTest.java:64-80` and `:82-100`. **The env template is the remaining hole** — exactly as the audit says.
- **Verdict: CONFIRMED.** The tracked template contains working literals for accounts the deployment *requires*; the fix must also preserve the ability to start the stack (see §3.16).

---

## 3. TASK 2 — Acceptance criteria and verification plan

Command conventions used throughout:

- Unit-test module: `./mvnw -pl <module> test -Dtest=<FQN-or-simple-name>`
- Reactor-safe form when the module's dependencies are not installed: `./mvnw -pl <module> -am test -Dtest=<name> -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`
- Fleet quality gate (Checkstyle): `./mvnw -T1C validate` — required by `CLAUDE.md` § Quality Gate.
- `*.java` classes named `*IT` **are** executed by Surefire in the service modules (each service POM adds `**/*IT.java` to Surefire includes — e.g. `product-service/pom.xml:144-150`, `gateway-service/pom.xml:107-113`). `utils/common-*` modules have no such override and `inventory-service` relies on the `*Test` suffix instead — so **naming a new integration test `*IT` in `utils/common-*` would silently not run**.

---

### 3.1 SL-01 — monitor must not span the token request

- **Invariant (falsifiable).** When the Keycloak token endpoint **fails**, N concurrent admin operations issued against one `KeycloakAdminClient` instance must produce **exactly one** token HTTP request, and all N callers must fail within ≈1 token round-trip — i.e. the JVM monitor is not held across the request, and the failure is not retried serially per caller.
- **Target test.** `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientTokenFetchConcurrencyTest.java` — method `concurrentAdminCallsIssueOneTokenRequestAndCompleteWithinOneRoundTripWhenTheTokenEndpointFails()`.
- **Command.** `./mvnw -pl utils/common-keycloak test -Dtest=KeycloakAdminClientTokenFetchConcurrencyTest`
- **Failure mode that proves the bug was real (before the fix).** `KeycloakAdminClient.java:328` holds the monitor while `:345-350` performs the request. With a 500 ms mock latency and a 500 response, thread *i* enters the monitor, fails, releases it; thread *i+1* then re-checks the cache at `:329-332` (still empty/expired) and issues **its own** request. Result on the unfixed code: **20 token requests**, total wall time ≈ 20 × 500 ms. After the fix: **1 token request**, wall time ≈ 500 ms, and every caller sees the same failure.
- **Dependency.** **Pure unit.** `MockRestServiceServer` with a request interceptor that sleeps and returns 500. No PostgreSQL / Redis / Kafka / Keycloak / Elasticsearch.
- **Concurrency / multi-instance dimension.** 20 threads, **one shared client instance** (the bean is a singleton), one cold cache. This is a single-JVM dimension only — the fix does not change cross-replica behaviour.
- **Also required by the criterion.** The **success** path must still issue exactly one token request and must still cache the token (regression guard for the stampede the original lock was introduced to prevent). The audit's proposed success-path assertions (`shared-libraries.md:1195`) are satisfied by the current code and must **not** be used as the acceptance signal.

### 3.2 SL-02 — no silent downgrade

- **Invariant (falsifiable).** If `adminClientSecret` is non-blank and the service-account token endpoint is null/blank, `KeycloakAdminClient` must throw a `KeycloakClientException` **without issuing any HTTP request to any token endpoint** — in particular it must never send `grant_type=password`.
- **Target test.** `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientServiceAccountTest.java` — **new** method `aSecretWithoutAServiceAccountEndpointFailsClosedWithoutAPasswordGrant()`.
- **Command.** `./mvnw -pl utils/common-keycloak test -Dtest=KeycloakAdminClientServiceAccountTest`
- **Failure mode that proves the bug was real (before the fix).** With only the secret configured, `hasServiceAccountCredentials()` (`:118-121`) is `false`, so the selection at `:334` falls through to `:338-342` and the client POSTs `grant_type=password&username=...&password=...` to the **master-realm** endpoint. The pre-fix test therefore observes a password-grant request body — the opposite of the required behaviour.
- **Dependency.** **Pure unit** (`MockRestServiceServer`).
- **Concurrency / multi-instance dimension.** None.
- **Assertion shape (binding).** The test must assert **zero** HTTP requests were made (`MockRestServiceServer` request count == 0, plus `server.verify()` with no expectations) *and* that a `KeycloakClientException` was thrown. Asserting only "an exception was thrown" is insufficient: the current code throws `KeycloakClientException` **after** a failed password grant too, so that assertion alone would pass on the unfixed code.

### 3.3 SL-14 — validated correlation id

- **Invariant (falsifiable).** The values written to `MDC[correlationId]`, `MDC[requestId]` and `MDC[traceId]`, and echoed into the `X-Correlation-Id` / `X-Request-Id` response headers, must either be server-generated or match `^[A-Za-z0-9_-]{1,64}$`. A client-supplied value that does not match must **never** reach the MDC or the response. Additionally, `MDC[traceId]` must **never** be derived from a client-supplied header.
- **Target test.** `utils/common-spring/src/test/java/com/shop/common/spring/web/filter/CorrelationIdFilterValidationTest.java` — methods `aHostileCorrelationIdIsRejectedAndRegenerated()` and `anOversizedCorrelationIdIsRejectedAndRegenerated()` and `theTraceIdIsNeverDerivedFromAClientHeader()`.
- **Command.** `./mvnw -pl utils/common-spring test -Dtest=CorrelationIdFilterValidationTest`
- **Failure mode that proves the bug was real (before the fix).** `CorrelationIdFilter.java:53-56` returns the header verbatim; `:34-38` places it in three MDC keys; `:41-42` echoes it. A request carrying `X-Correlation-Id: <script>alert(1)</script>` yields an MDC value equal to the input and a response header equal to the input; a request carrying 10 000 `A`s yields an MDC value of 10 000 characters, rendered into every log line by `application.yml:63`.
- **Dependency.** **Pure unit** — construct the filter directly and drive it with `MockHttpServletRequest` / `MockHttpServletResponse` / a stub `FilterChain`. No servlet container, no Spring context.
- **Concurrency / multi-instance dimension.** None. The MDC is thread-local; the defect is per-request, not cross-instance.

### 3.4 SL-15 — client IP only from trusted proxies

- **Invariant (falsifiable).** `RequestUtils.getClientIp(request)` must return `request.getRemoteAddr()` whenever the direct peer is not a configured trusted proxy; a client-supplied forwarding header must have **no** influence on the result in that case.
- **Target test.** `utils/common-spring/src/test/java/com/shop/common/spring/web/util/RequestUtilsTrustedProxyTest.java` — method `anUntrustedPeerCannotSpoofTheClientIpViaForwardedHeaders()`; plus `aTrustedProxyChainResolvesTheRightmostUntrustedHop()`.
- **Command.** `./mvnw -pl utils/common-spring test -Dtest=RequestUtilsTrustedProxyTest`
- **Failure mode that proves the bug was real (before the fix).** `RequestUtils.java:18-27` returns the first non-empty header. A request with `remoteAddr=203.0.113.9` and header `X-Forwarded-For: 127.0.0.1` resolves to `127.0.0.1`; likewise for `X-Original-Forwarded-For`, `Proxy-Client-IP`, `WL-Proxy-Client-IP`, `X-Real-IP`.
- **Dependency.** **Pure unit** (`MockHttpServletRequest.setRemoteAddr` + `addHeader`). No real proxy.
- **Concurrency / multi-instance dimension.** None.
- **Compatibility requirement.** The gateway already has its own trusted-proxy implementation (`gateway-service/src/main/java/com/shop/gateway/filter/TrustedProxyPolicy.java`, `TrustedProxyProperties.java`, `ClientIpResolver.java`). The common-library fix must not diverge from it in semantics; see the cross-cutting risk in §4.3.

### 3.5 SL-16 — bounded response buffering

- **Invariant (falsifiable).** For a response of N bytes, the number of response bytes retained in heap by `HttpLoggingFilter` must be ≤ the configured maximum **regardless of whether response-body logging is enabled**; the client must still receive the full body.
- **Target test.** `utils/common-spring/src/test/java/com/shop/common/spring/web/filter/HttpLoggingFilterResponseBufferTest.java` — method `aLargeResponseIsNotFullyRetainedInHeap()`.
- **Command.** `./mvnw -pl utils/common-spring test -Dtest=HttpLoggingFilterResponseBufferTest`
- **Failure mode that proves the bug was real (before the fix).** `HttpLoggingFilter.java:57` wraps the response in an unbounded `ContentCachingResponseWrapper`; a 10 MB response leaves 10 MB retained in the wrapper's `ByteArrayOutputStream` until `:64` copies it out.
- **Dependency.** **Pure unit** if the retained size is observable through a seam (e.g. the wrapper type/exposed buffer size); a `MockMvc` or embedded-container test is acceptable. No external service.
- **Concurrency / multi-instance dimension.** The cost is **per request × concurrency × replicas**. The criterion is a per-request bound; the fleet bound is the product, not the assertion.

### 3.6 SL-28 — no network I/O during bean creation

- **Invariant (falsifiable).** Creating the `ObjectStorageService` bean must perform **zero** network calls, and the application context must refresh successfully while the configured object-store endpoint is unreachable. In addition, a `409 BucketAlreadyOwnedByYou` from `createBucket` must be treated as success.
- **Target test.** `utils/common-storage/src/test/java/com/shop/common/storage/config/ObjectStorageAutoConfigurationStartupTest.java` — methods `theContextStartsWhenTheObjectStoreIsUnreachable()`, `theBucketIsNotProvisionedDuringBeanCreation()`, `aBucketAlreadyOwnedByYouIsTreatedAsSuccess()`.
- **Command.** `./mvnw -pl utils/common-storage test -Dtest=ObjectStorageAutoConfigurationStartupTest`
- **Failure mode that proves the bug was real (before the fix).** With `shop.storage.auto-create-bucket=true` and an endpoint that never answers, `ObjectStorageAutoConfiguration.java:54` calls `ensureBucketExists` → `S3ObjectStorageService.java:49` `headBucket` → the SDK has **no** `apiCallTimeout` (`S3ClientFactory.java:23-30`) → the bean-creation thread blocks indefinitely and the context never refreshes. Two replicas starting together both see a missing bucket and both call `createBucket`; the loser receives 409, which `:65-67` converts to a `StorageException` → startup failure.
- **Dependency.** Needs a **stub endpoint** that accepts connections and never answers (a `ServerSocket`, or WireMock with a fixed delay). **No real S3/MinIO required.** If the SDK client is replaced by a mock, the test becomes pure unit — but then it no longer proves the "context starts when the store is unreachable" property, so the stub is strongly preferred.
- **Concurrency / multi-instance dimension.** The 409 race is a **two-replica** dimension: two contexts created concurrently against one store.

### 3.7 SL-33 — Keycloak clients use the platform `RestClient.Builder`

- **Invariant (falsifiable).** The `RestClient` used by `KeycloakTokenClient` and `KeycloakAdminClient` must be built from the application context's `RestClient.Builder` bean, so that **every** registered `RestClientCustomizer` is applied to the Keycloak clients' outgoing requests.
- **Target test.** `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/KeycloakRestClientCustomizerTest.java` — method `theKeycloakClientsUseTheApplicationConfiguredRestClientBuilder()`.
- **Command.** `./mvnw -pl utils/common-keycloak test -Dtest=KeycloakRestClientCustomizerTest`
- **Failure mode that proves the bug was real (before the fix).** `KeycloakAutoConfiguration.java:45` and `:59` call `traceAware(RestClient.builder())`. A context-registered `RestClientCustomizer` that adds a header (or a `ClientHttpRequestFactory`) has **no** effect on the Keycloak clients' requests.
- **Dependency.** **Pure unit / `ApplicationContextRunner`.** No Keycloak server is needed — the assertion is on the outgoing request that a stub `RestClient` receives.
- **Concurrency / multi-instance dimension.** None.
- **Structural constraint (binding).** `utils/common-keycloak` must remain a leaf module (it currently depends only on `common-core`). A fix that adds a `common-keycloak` → `common-spring` dependency creates a Maven cycle and is not acceptable; the tracing seam has to move to a leaf module or the builders have to be exposed as beans.

### 3.8 SL-37 — the horizontal-privilege boundary must be real

- **Invariant (falsifiable, two-sided).**
  1. The shipped default for `shop.security.expected-client-ids` in `utils/common-spring/src/main/resources/application.yml` must not enumerate more than one client id; **and**
  2. a service that starts with `shop.security.enabled=true` and an **empty** effective `expected-client-ids` list must **fail to start** (or the `azp` validator must be unconditional) — because `BaseSecurityConfig.java:98` skips the `azp` validator entirely when the list is empty, which would otherwise make the platform accept a token from **any** client.
- **Target test.** `utils/common-spring/src/test/java/com/shop/common/spring/web/SharedDefaultsSecurityTest.java` — **new** method `theShippedClientIdAllowListIsNotAFleetWideUnion()`; plus `utils/common-security/src/test/java/com/shop/common/security/config/BaseSecurityConfigClientIdBoundaryTest.java` — method `aServiceWithoutAnExplicitClientIdListRefusesToStart()` and `aTokenFromAnUndeclaredClientIsRejected()`.
- **Command.** `./mvnw -pl utils/common-spring,utils/common-security test -Dtest='SharedDefaultsSecurityTest+BaseSecurityConfigClientIdBoundaryTest'`
- **Failure mode that proves the bug was real (before the fix).** `application.yml:95` ships six client ids; `BaseSecurityConfig.java:98-105` accepts a token whose `azp` is any of them — so a token minted for `media-service` is accepted by `order-service`, and vice versa.
- **Dependency.** **Pure unit.** `SharedDefaultsSecurityTest.java:16-20` already reads the classpath `application.yml` with SnakeYAML — the new assertion extends that pattern. The boundary test needs only a fabricated `Jwt` and an `ApplicationContextRunner`.
- **Concurrency / multi-instance dimension.** None.
- **Cross-agent requirement (binding).** `gateway-service/src/main/resources/application.yml:34` carries the **same six-client default** and lives in the **gateway-limits** agent's write scope, while `utils/common-spring/src/main/resources/application.yml:95` lives in the **shared-security** agent's scope. SL-37 is **not** acceptable until both are narrowed; neither agent can see the other's diff.

### 3.9 KAF-01 — a second replica must publish

- **Invariant (falsifiable).** In each affected service, when the lowest-id `PENDING` row is locked by another transaction, a concurrent claim must return the **next** pending row (not an empty result). The claim query must use a range predicate with `ORDER BY id ASC LIMIT 1` + `FOR UPDATE SKIP LOCKED` and must not collapse the candidate set to a single row via a `MIN(id)` subquery.
- **Target tests (one per affected service — the assertion at the cited line must change from `isEmpty()` to `contains(row2)`):**
  - `product-service/src/test/java/com/shop/productservice/service/ProductOutboxClaimConcurrencyIT.java` → `twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked()` (currently `:169` asserts `isEmpty()`; the method name at `:115` must change too).
  - `inventory-service/src/test/java/com/shop/inventoryservice/service/InventoryOutboxClaimConcurrencyTest.java` → `twoPendingRows_secondRowClaimedConcurrentlyWhileHeadRowLocked()` (currently `:169`).
  - `media-service/src/test/java/com/shop/mediaservice/outbox/MediaOutboxClaimConcurrencyIT.java` → same (currently `:169`).
  - `rating-service/src/test/java/com/shop/ratingservice/outbox/RatingOutboxClaimConcurrencyIT.java` → same (currently `:167`).
  - `promotion-service/src/test/java/com/shop/promotionservice/service/PromotionOutboxClaimConcurrencyIT.java` → same (currently `:169`).
- **Command.** `./mvnw -pl product-service,inventory-service,media-service,rating-service,promotion-service test -Dtest='*OutboxClaimConcurrency*'`
- **Failure mode that proves the bug was real (before the fix).** The `MIN(e2.id)` subquery (`product OutboxEventRepository.java:24-26` and equivalents) pins exactly **one** candidate row; `SKIP LOCKED` drops it, the peer's query returns empty, and the relay `break`s (`product OutboxRelay.java:78-80`) and publishes an empty batch. The current tests **observe and pin this**: `ProductOutboxClaimConcurrencyIT.java:169` `assertThat(claimBWhileLocked.get()).isEmpty();`.
- **Dependency.** **Real PostgreSQL (Testcontainers).** `SKIP LOCKED` / `FOR UPDATE` semantics are PostgreSQL-specific; an H2 or in-memory substitute cannot reproduce the defect and would make the test vacuous.
- **Concurrency / multi-instance dimension.** Two transactions + a latch-coordinated lock (in-process); and the fleet dimension — two service replicas polling the same table must together drain the pending set.
- **Scope correction (binding).** Exactly **five** services must change. `shipping-service` must **not** be modified in behaviour: its repository (`:22-24`) and its test (`:167-169`, asserting `contains(row2)`) are the reference. `order-service` (`:168`) and `payment-service` (`:167`) must also remain unchanged. A diff that touches `shipping-service`'s claim query is out of scope and carries regression risk (its native query takes a `String` status parameter at `:24` while the JPA derived form takes an `OutboxStatus`).

### 3.10 KAF-02 — per-aggregate ordering barrier in payment-service

- **Invariant (falsifiable).** Within one `PaymentOutboxRelay` batch, if a record of aggregate A fails to publish, **no later record of aggregate A** in that batch may be marked `SENT`; a later record of a **different** aggregate B may still be marked `SENT`.
- **Target test.** `payment-service/src/test/java/com/shop/paymentservice/outbox/PaymentOutboxRelayOrderTest.java` — **new** method `aFailedRecordBlocksLaterRecordsOfTheSameAggregateButNotOfAnotherAggregate()`.
- **Command.** `./mvnw -pl payment-service test -Dtest=PaymentOutboxRelayOrderTest`
- **Failure mode that proves the bug was real (before the fix).** `PaymentOutboxRelay.java:87-96` marks each row from the raw per-record outcome `published` (`:90`); no `markableAsSent` call exists in the file. A batch `[capture(fail), refund(ok)]` of the **same** `aggregateId` marks the refund `SENT` ⇒ the broker delivers `payment.refunded.v1` **before** `payment.captured.v1` on the same partition key (`:80`), permanently.
- **Dependency.** **Pure unit** if `PaymentOutboxRelay` is constructible with a stub `KafkaPublisher`/publisher interface that returns a crafted `BatchOutcome`; otherwise **real Kafka (Testcontainers)**. The persisted status of the second row must be asserted — a mock that never touches the repository cannot prove it.
- **Concurrency / multi-instance dimension.** The barrier is a **within-batch** invariant; its *consequence* is distributed (a permanently reordered Kafka partition per `paymentId`). The test must additionally demonstrate the aggregate-key equality, i.e. both rows share one `aggregateId` and therefore one Kafka key.

### 3.11 GW-04 — fairness and a replica-aware fleet cap

- **Invariant (falsifiable).** A single client must not be able to exhaust the fleet-wide budget: under a single-client flood, a second, distinct client continues to be admitted at the configured rate. In addition, the effective fleet-wide ceiling must scale with the number of gateway replicas (or be explicitly per-replica with an aggregate accounted elsewhere).
- **Target test.** `gateway-service/src/test/java/com/shop/gateway/ratelimit/GlobalRateLimitFilterFairnessTest.java` — method `aSingleClientFloodCannotStarveOtherClients()`; and `gateway-service/src/test/java/com/shop/gateway/ratelimit/GlobalRateLimitPropertiesScaleTest.java` — method `theGlobalCeilingIsDerivedFromTheReplicaCount()`.
- **Command.** `./mvnw -pl gateway-service test -Dtest='GlobalRateLimitFilterFairnessTest+GlobalRateLimitPropertiesScaleTest'`
- **Failure mode that proves the bug was real (before the fix).** `GlobalRateLimitFilter.java:14-15` + `:41` use one key pair `("gateway-system","system")` for every request, so client A's burst consumes the shared bucket and client B is rejected with 429 even though B has consumed nothing. Separately, the ceiling is a fixed 2000 rps / 4000 burst (`GlobalRateLimitProperties.java:12-13`, `gateway-service/src/main/resources/application.yml:74-76`) while `k8s/gateway-service.yaml:84-85` scales the deployment 2→10.
- **Dependency.** **Real Redis (Testcontainers)** for the behavioural fairness criterion — two clients must share one bucket implementation, which a stub cannot express. The replica-scaling criterion is a pure property/configuration test.
- **Concurrency / multi-instance dimension.** Two distinct client identities against one Redis (fairness), and N gateway replicas against one Redis (fleet cap). Both dimensions are required; a single-client test proves neither.
- **Note on the audit's proposed fix.** "Derive the global replenish rate from the replica count" alone does **not** satisfy the stated invariant: a per-replica bucket still lets one client consume the whole fleet budget if the client can spread across replicas. The acceptance criterion must be **per-client fairness**, and the test must use **two distinct client identities**.

### 3.12 GW-12 — Redis-Cluster-safe key pair

- **Invariant (falsifiable).** The two Redis keys of one edge-rate-limit bucket must contain the **same** `{...}` hash tag, and that tag must cover the bucket identity (scope **and** client).
- **Target test.** `gateway-service/src/test/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiterClusterSlotTest.java` — method `bothBucketKeysShareTheSameHashTag()`.
- **Command.** `./mvnw -pl gateway-service test -Dtest=RedisEdgeRateLimiterClusterSlotTest`
- **Failure mode that proves the bug was real (before the fix).** `RedisEdgeRateLimiter.java:65-67` builds `gateway:edge-rate-limit:{scope}:{clientIp}:tokens` and `...:timestamp`; under Redis Cluster the two keys hash to different slots and `redis.execute(TOKEN_BUCKET, keys, ...)` (`:68`) fails with `CROSSSLOT`.
- **Dependency.** A pure-unit key-string assertion is sufficient to prove the property. A **Redis Cluster with ≥ 2 shards (Testcontainers)** is required to observe the `CROSSSLOT` failure itself; note the shipped compose is standalone (`docker-compose.yml:64-68`), so the cluster test is optional for acceptance but the key layout is not.
- **Concurrency / multi-instance dimension.** Multi-shard Redis (slot affinity), not a multi-instance gateway concern.

### 3.13 GW-13 — meter the unauthenticated traffic

- **Invariant (falsifiable).** A request that is rejected **401** for want of a valid token must still have consumed rate-limit budget, and `/api/v1/auth/**` must be subject to a dedicated, IP-keyed limit that is **stricter** than the generic per-IP limit.
- **Target test.** `gateway-service/src/test/java/com/shop/gateway/filter/PreAuthenticationRateLimitTest.java` — methods `anUnauthenticatedRequestToAProtectedPathConsumesRateLimitBudget()` and `theAuthenticationEndpointsHaveADedicatedIpScopedLimit()`.
- **Command.** `./mvnw -pl gateway-service test -Dtest=PreAuthenticationRateLimitTest`
- **Failure mode that proves the bug was real (before the fix).** `SecurityConfig.java:44-46` rejects unauthenticated traffic with 401 inside the Spring Security `WebFilterChainProxy`; all four gateway filters are `GlobalFilter`s and therefore execute only after that chain, so **zero** rate-limit budget is consumed for a flood of 401s. In parallel, `RateLimitFilter.java:24-26` defines only `BACKOFFICE` and `SEARCH`, so `/api/v1/auth/**` has no dedicated edge scope and is covered only by the per-IP L1 limit (`RateLimitKeyResolver.java:40-46` — keyed by the **client-resolved** IP).
- **Dependency.** **Real Redis (Testcontainers)** for the budget assertion (the bucket must actually be decremented). A WebFlux slice test with a stub `EdgeRateLimiter` is sufficient to prove the **ordering** property (the limiter is invoked before the 401) and is much cheaper — it is an acceptable substitute **provided** it asserts the observable 429-vs-401 outcome, not merely "the limiter was called".
- **Concurrency / multi-instance dimension.** The budget is fleet-wide in Redis, so the test must exercise it against one shared Redis from two clients.

### 3.14 GW-27 — circuit breaker / bulkhead / timeout

- **Invariant (falsifiable).** Every backend route must carry an explicit response-time bound and a bounded in-flight limit, such that a hung upstream cannot stall unrelated routes.
- **Target test.** `gateway-service/src/test/java/com/shop/gateway/config/RoutesConfigResilienceTest.java` — method `everyBackendRouteCarriesATimeoutAndABulkheadFilter()`; plus a behavioural test `aHungUpstreamDoesNotStallOtherRoutes()` using a WireMock route that never answers.
- **Command.** `./mvnw -pl gateway-service test -Dtest='RoutesConfigResilienceTest'`
- **Failure mode that proves the bug was real (before the fix).** `RoutesConfig.java:44-51` attaches exactly one filter per route (the rate limiter) and nothing else; there is no `spring.cloud.gateway.httpclient.*` timeout in `gateway-service/src/main/resources/application.yml`; `grep -rn 'resilience4j|CircuitBreaker|Bulkhead' gateway-service/` returns **0** hits. A WireMock route that never responds therefore holds gateway connections and event-loop resources until the Netty defaults fire.
- **Dependency.** **Pure unit** for the structural criterion (inspect the route's filter list / configuration). **WireMock** (no external service) for the behavioural criterion.
- **Concurrency / multi-instance dimension.** The bulkhead is a **per-gateway-instance** in-flight cap; the property that matters is "one degraded upstream does not starve the others **on the same instance**".

### 3.15 CAT-02 — one managed ES client version

- **Invariant (falsifiable).** No POM outside the root `pom.xml` `<dependencyManagement>` declares a `<version>` for `co.elastic.clients:elasticsearch-java`; and `org.elasticsearch.client:elasticsearch-rest-client` is managed by the root POM at the same version family.
- **Target test.** `search-service/src/test/java/com/shop/searchservice/config/ElasticsearchClientVersionContractTest.java` — method `theElasticsearchJavaClientVersionIsManagedOnlyByTheRootPom()`.
- **Command.** `./mvnw -pl search-service test -Dtest=ElasticsearchClientVersionContractTest` and, as a build-level cross-check, `./mvnw -pl search-service dependency:tree -Dincludes=co.elastic.clients:elasticsearch-java,org.elasticsearch.client:elasticsearch-rest-client`.
- **Failure mode that proves the bug was real (before the fix).** `search-service/pom.xml:36` overrides the managed `9.4.5` with `8.15.5`; `search-service/pom.xml:41` does the same for the low-level client, which the root POM does not manage at all. `dependency:tree` shows `search-service` resolving `elasticsearch-java:8.15.5` while every other module resolves `9.4.5`.
- **Dependency.** **Pure unit** (POM text parsing, in the style of `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/RealmImportCredentialContractTest.java`) + one `dependency:tree` invocation. No Elasticsearch server.
- **Concurrency / multi-instance dimension.** None.
- **Compile obligation (binding).** The 9.x API surface of `RestClientTransport` / `JacksonJsonpMapper` must be re-verified against `search-service/src/main/java/com/shop/searchservice/config/ElasticsearchConfig.java:41-42`; the module must **compile**, and `search-service`'s Elasticsearch integration tests (`ReindexIT`, `SearchIndexProvisioningIT`, `SearchQueryIT`, `ProductSearchIngestionIT`, `ProductSearchMonotonicProjectionIT`) must be run against a real ES container because the client/transport pairing changed.

### 3.16 SEC-03 — no working credential in the shipped template

- **Invariant (falsifiable).** No value in the tracked `.env.example` may authenticate against an account the deployment provisions; every credential-shaped key must carry either an empty value or a non-working placeholder.
- **Target test.** `utils/common-spring/src/test/java/com/shop/common/spring/deploy/DevEnvTemplateCredentialTest.java` — method `noTrackedCredentialInTheDevEnvTemplateCanAuthenticate()`.
- **Command.** `./mvnw -pl utils/common-spring test -Dtest=DevEnvTemplateCredentialTest`
- **Failure mode that proves the bug was real (before the fix).** `.env.example:270` `KEYCLOAK_ADMIN_USER_PASSWORD=adminpass` is the password of the seeded `adminuser` (`docker/keycloak/import/ecommerce-realm.json:152`, `:161`) which holds `ADMIN`+`MANAGER` (`:165-168`) and which the deployment **requires** (`docker-compose.yml:188`, `:?required`). Copying the template provisions a realm whose privileged password is published in git.
- **Dependency.** **Pure unit** (text parse of a tracked file). No Keycloak, no PostgreSQL, no Redis.
- **Concurrency / multi-instance dimension.** None.
- **Companion acceptance condition (binding).** The fix must preserve the ability to start the stack: `docker-compose.yml:188` and `:179-180` use the `:?required` idiom, so an **empty** `KEYCLOAK_ADMIN_USER_PASSWORD` makes `docker compose up` abort. Acceptance therefore requires either (a) a documented generation step / bootstrap script that writes a random value into `.env` before start, or (b) a compose-level guard that fails with an actionable message. Without it, the platform is un-startable from the template and operators will simply re-add the literal.

---

## 4. TASK 3 — Acceptance risk assessment (false-pass modes)

### 4.1 Per-finding false-pass catalogue

| ID | Most likely false pass | What I will check in the diff to catch it |
| --- | --- | --- |
| SL-01 | Using the audit's **success-path** test (token requests == 1, wall time ≈ 1 RTT). Both assertions hold on the **unfixed** code, because the fast path at `KeycloakAdminClient.java:323` is checked before the monitor and the cache is warm for threads 2..N. | The new test must exercise the **failure** path and assert the token-request **count == 1** under failure. If the test only stubs a 200 response, it proves nothing. |
| SL-01 | "Fix" = replace `synchronized (this)` with an unsynchronised fetch → N concurrent cold-cache callers issue N token requests (a stampede regression). | The diff must still contain a single-flight guard. Check that the new test asserts **exactly one** token request in the success path too, and that `KeycloakAdminClientServiceAccountTest.theServiceAccountTokenIsReusedForTheFollowingAdminCalls` (`:185`) still passes. |
| SL-02 | The test asserts "an exception was thrown" — the current code **also** throws (`KeycloakClientException`) after a failed password grant, so the assertion passes on unfixed code. | The test must assert **zero HTTP requests** to any token endpoint (MockRestServiceServer request count == 0) and that no `grant_type=password` body was sent. |
| SL-02 | The implementer forbids the password grant entirely and deletes/rewrites `KeycloakAdminClientServiceAccountTest.withoutAServiceAccountSecretTheMasterRealmPasswordGrantIsUsed` (`:89`) to make the build green. | That existing test is a **decision record**, not a bug. A deletion is only acceptable if the change is accompanied by an explicit, reviewed policy change (and `auth-service/.../KeycloakPasswordGrantPostureCheck.java:35` is updated). Flag any silent deletion or weakening. |
| SL-14 | The filter validates the **format** but still copies the validated client value into `MDC[traceId]` (`CorrelationIdFilter.java:37-39`). A value like `my-trace-42` matches the whitelist and still spoofs the trace id. | The diff must **remove** the trace-id copy (or source the trace id only from the tracing system), not merely validate. Check `:37-39`. |
| SL-14 | The validation is applied but the **response echo** (`:41-42`) still uses the raw header, or the length cap is applied only to the MDC and not to the echoed header. | The test must assert the **response header** value and length, not just the MDC value. |
| SL-15 | A new **overload** with an explicit trusted-proxy argument is added, while the existing no-arg path keeps trusting the headers — and the new test only exercises the overload. | The test must call the **production entry point** used by `HttpLoggingFilter.java:72` (`RequestUtils.getClientIp(request)`), not a new overload. |
| SL-15 | The fix is applied in `utils/common-spring` but the gateway keeps its own, divergent resolution (`gateway-service/.../filter/ClientIpResolver.java`) — the platform then has two contradictory notions of client IP. | Diff must show the gateway's `ClientIpResolver`/`TrustedProxyPolicy` and the common-library resolver agree, or the divergence must be explicitly documented. |
| SL-16 | The test asserts that the client still receives the full response body — which holds on the unfixed code (`copyBodyToResponse()` at `:64` writes everything). | The test must assert the **retained byte count** (or a bounded-buffer seam), not the delivered bytes. |
| SL-16 | The fix wraps the response only when body logging is enabled, but the **default** (`enabled=false`) path still uses the caching wrapper — the unbounded behaviour remains the default. | Check that the unbounded `ContentCachingResponseWrapper` is gone from **all** paths, not only the logging path. |
| SL-28 | The test does not set `shop.storage.auto-create-bucket=true`, so `ensureBucketExists` is never invoked and the test passes on the unfixed code. | The test must explicitly enable auto-create and point at a non-answering endpoint. Check the property is set in the test, not inherited from a default. |
| SL-28 | The network call is moved to an `ApplicationReadyEvent` listener — but the listener still has no timeout, so the *process* still hangs (just later) and readiness never goes green. | Check that a `ClientOverrideConfiguration` with `apiCallTimeout`/`apiCallAttemptTimeout` is added in `S3ClientFactory.java:23-30`, and that a 409 from `createBucket` is tolerated (`S3ObjectStorageService.java:61-68`). |
| SL-33 | The test asserts a `RestClient.Builder` **bean exists** in the context, or that the clients are non-null — both true before the fix. | The assertion must observe an **effect** of a registered `RestClientCustomizer` on the outgoing Keycloak request (e.g. a custom header present). |
| SL-33 | The fix adds `common-keycloak` → `common-spring` (or `common-security`) → **Maven cycle**; the build breaks, or the cycle is broken by moving `common-core` code and dragging the fleet along. | Check `utils/common-keycloak/pom.xml` dependencies (`common-core` only today, `pom.xml:11`, `:17`) and the root `<modules>` graph. |
| SL-37 | The shared default is emptied, `BaseSecurityConfig.java:98` then **skips** the `azp` validator → the platform accepts a token from **any** client. This is a **strict regression** that a "the default is now empty" test would happily pass. | The diff must show either (a) a fail-fast when the list is empty, or (b) the `azp` validator made unconditional. Check `BaseSecurityConfig.java:98-105` explicitly. |
| SL-37 | Only `utils/common-spring/src/main/resources/application.yml:95` is narrowed; `gateway-service/src/main/resources/application.yml:34` (a **different agent's** scope) keeps the six-client union → the edge still accepts all six. | Cross-agent check: both YAMLs must be narrowed in the same batch. Neither agent can see the other's diff — the orchestrator must diff both. |
| KAF-01 | The implementer **flips the existing assertion** (`isEmpty()` → `contains(row2)`) and updates the repository query, but the *relay* still `break`s on an empty claim — or the query is changed to `MIN(id)` with a different spelling that is still single-row. | Check the **final JPQL/native SQL** in each of the 5 repositories: it must contain a range predicate + `ORDER BY id` + `LIMIT 1` + `SKIP LOCKED`, and no `MIN(e2.id)` subquery. |
| KAF-01 | The implementer "fixes" **shipping-service** too (it is inside the declared write scope). Shipping's native query takes a `String` status parameter (`:24`); converting it to a JPA derived form changes the parameter type and risks the one repository that is already correct. | Diff must show **shipping-service's claim query unchanged** and `ShippingOutboxClaimConcurrencyIT` still asserting `contains(row2)` (`:167-169`). |
| KAF-01 | The fix is applied to 5 services but **product-service is skipped** (it is outside the kafka-outbox agent's declared "7 non-product" scope) → 4 of 8 fixed, and the fleet still has one serialised emitter. | Cross-scope check: `product-service/src/main/java/com/shop/productservice/repository/OutboxEventRepository.java:24-26` must be changed. **This is a write-scope gap in the batch plan** — flag to the orchestrator. |
| KAF-01 | The concurrency IT is deleted or skipped because it is flaky under CI timing. | `ShippingOutboxClaimConcurrencyIT` / `OrderOutboxClaimConcurrencyIT` prove the pattern is stable; a deleted IT must be treated as a regression, not a fix. |
| KAF-02 | The test asserts "the relay calls `OutboxBatchOrdering.markableAsSent`" — a pure implementation-detail assertion that passes even if the result is discarded. | The test must assert the **persisted status** of the second row is `PENDING` (not `SENT`), and that a different aggregate's row **is** `SENT`. |
| KAF-02 | The barrier is added but the aggregate **key** used for grouping differs from the Kafka partition key (`PaymentOutboxRelay.java:80` uses `aggregateId`). A barrier over the wrong key is inert. | The test must use two rows with the **same** `aggregateId` and assert the second is not marked `SENT`. |
| GW-04 | The global key becomes per-client, the 4 literal `isAllowed("gateway-system","system")` assertions in `GlobalRateLimitFilterTest` are updated to a *weaker* form (`isAllowed(any(), any())`), and no fairness test exists → nothing is proven. | The new fairness test must use **two distinct client identities** against one Redis and assert client B is still admitted while A floods. |
| GW-04 | "Scale the cap with replicas" is implemented by dividing the global bucket per replica, which still lets one client drain the fleet budget across replicas. | The fairness criterion must be evaluated **independently** of the replica-scaling criterion; both must have their own test. |
| GW-12 | The key gets a hash tag that contains only the **scope** (`{BACKOFFICE}`) → the two keys share a slot, but every client of that scope shares one slot (a hot slot) and the client identity is lost from the slot tag. | The test must assert the tag contains **both** scope and client, and that the two keys carry the **same** tag (not merely "a `{` is present"). |
| GW-13 | A `WebFilter` is added but registered at a **later** order than the Spring Security chain (`-100`), so it still runs after the 401. | Check the new filter's `Ordered` value is **< -100** (the security chain proxy order) and that the test asserts the observable 429, not "the limiter was called". |
| GW-13 | The `AUTH` scope is added but keyed by the **client-resolved** IP, which is itself spoofable in the shared-library resolver — the credential-stuffing surface is unchanged. | The auth-endpoint limit must be keyed by a value that a client cannot choose. Check the key derivation in the new scope. |
| GW-27 | `GatewayRateLimitContextTest.java:71` `hasSize(1)` is relaxed to `hasSizeGreaterThanOrEqualTo(1)` (and `:70` `hasSize(29)` similarly) → the structural criterion becomes vacuous. | The new test must assert the **presence** of a timeout/bulkhead filter (by type or by configuration) — a count is not evidence. |
| GW-27 | A circuit breaker is configured but `resilience4j` is **not** added to `gateway-service/pom.xml` → the filter silently no-ops. | Check the dependency is present and that the behavioural test (hung upstream) actually observes the protection. |
| CAT-02 | The test asserts "the string `8.15.5` is gone" → bumping the local pin to `9.4.5` passes while the **drift mechanism** (a local `<version>`) survives. | The criterion forbids the **`<version>` element**, not the literal value. Check the POM has no `<version>` under either ES artifact. |
| CAT-02 | `elasticsearch-rest-client`'s `<version>` is deleted without adding it to the root `<dependencyManagement>` → the build breaks (no managed version). | Check the root `pom.xml` manages **both** ES artifacts at the same version. |
| CAT-02 | The module does not compile against the 9.x API (`ElasticsearchConfig.java:41-42`) → a green unit test hides a compile break elsewhere. | `./mvnw -pl search-service -am compile` must pass, plus the ES integration tests against a container. |
| SEC-03 | The test blocklists the current literals (`adminpass`, `admin`, `changeme`) → changing the literal to `adminpass2` passes. | The criterion must be a **property** ("value is empty or a placeholder"), not a blocklist. |
| SEC-03 | `.env.example` is emptied but `docker-compose.yml:188` `:?required` then aborts the stack → the platform cannot start from the template. | Check that a generation step or an actionable compose guard exists (see §3.16). |
| SEC-03 | The test scans the repo root for literals and picks up the **untracked** `.env` (which holds the operator's real values) instead of `.env.example`. | The test must read **only** `.env.example` (`git ls-files` confirms it is tracked; `.env` is not). |

### 4.2 Batch-level risks (not attributable to one finding)

1. **Write-scope gaps.** (a) `product-service`'s `OutboxEventRepository`/`OutboxRelay` are outside the kafka-outbox agent's declared "7 non-product" scope but are **inside** the KAF-01 finding. (b) SL-37 needs a change in **two** agents' scopes (`utils/common-spring` and `gateway-service`). Both must be reconciled by the orchestrator before acceptance.
2. **Test-suite deletion as a fix.** KAF-01 requires **changing** five existing assertions that currently pin the defect; GW-27 requires changing two assertions in `GatewayRateLimitContextTest`; SL-02 may require changing one in `KeycloakAdminClientServiceAccountTest`. Any *deletion* of an existing test in these files must be reviewed as a **policy change**, not a refactor.
3. **Suite-not-run trap.** `*IT` classes in `utils/common-*` are **not** executed by Surefire (those modules have no `**/*IT.java` include), and the `it` Maven profile (`pom.xml:476-539`) is inactive because no module has `src/it/java`. A new integration test placed in `utils/common-storage` or `utils/common-keycloak` named `*IT` would **never run** and would be reported as "no failures".
4. **Concurrent compilation.** Four agents are building against one local Maven repository. Any acceptance run must be serialised, and any "red" result must be re-run in isolation before it is attributed to a code change.

---

## 5. TASK 4 — Regression watchlist

Run these suites (in this order) before accepting the batch.

### 5.1 `utils/common-keycloak` (SL-01, SL-02, SL-33)
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientServiceAccountTest.java` — **highest risk**: `withoutAServiceAccountSecretTheMasterRealmPasswordGrantIsUsed` (`:89`) pins the password grant; `aConfiguredServiceAccountNeverFallsBackToTheMasterRealmPasswordGrant` (`:126`) pins fail-closed on a rejected service-account token; `theServiceAccountTokenIsReusedForTheFollowingAdminCalls` (`:185`) pins the token cache.
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientGrantPostureTest.java` — all four tests build the client with `adminPassword` and no secret (`:34-35`); any removal of the password grant breaks them.
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakAdminClientRoleMappingTest.java`
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/client/KeycloakServiceTokenProviderTest.java`
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/AdminServiceAccountRealmContractTest.java`
- `utils/common-keycloak/src/test/java/com/shop/common/keycloak/config/RealmImportCredentialContractTest.java` — enforces the realm-side placeholder contract that SEC-03 relies on.
- **Command:** `./mvnw -pl utils/common-keycloak test`

### 5.2 `utils/common-spring` (SL-14, SL-15, SL-16, SL-37, SEC-03)
- `utils/common-spring/src/test/java/com/shop/common/spring/web/SharedDefaultsSecurityTest.java` — **highest risk**: it reads the shared `application.yml` (`:16-20`) and is the natural host for the SL-37 assertion.
- `utils/common-spring/src/test/java/com/shop/common/spring/deploy/ProductionEnvTemplateTest.java` — reads `.env.prod.example` and **`docker-compose.yml`** (`:24-26`, `:36-40`); the SEC-03 fix touches both.
- `utils/common-spring/src/test/java/com/shop/common/spring/CommonLibraryStarterTests.java`
- `utils/common-spring/src/test/java/com/shop/common/spring/tracing/TraceparentInterceptorTest.java` and `.../autoconfigure/TracingAutoConfigurationTest.java` — **risk from SL-33** if the traceparent post-processor is relocated.
- `utils/common-spring/src/test/java/com/shop/common/spring/web/ApiExceptionHandlerDataIntegrityTest.java`, `.../web/exception/ApiExceptionHandlerConstraintLeakTest.java`, `.../web/ServerErrorDefaultsTest.java`
- **Command:** `./mvnw -pl utils/common-spring test`

### 5.3 `utils/common-storage` (SL-28)
- `utils/common-storage/src/test/java/com/shop/common/storage/config/StoragePropertiesCredentialsTest.java` — **highest risk**: it calls `configuration.s3Client(props)` and `configuration.s3Presigner(props)` **directly** (`:26-32`, `:42-44`, `:55-56`); changing those `@Bean` signatures breaks compilation of the test.
- `utils/common-storage/src/test/java/com/shop/common/storage/service/S3ObjectStorageServiceDownloadLeakTest.java`
- **Command:** `./mvnw -pl utils/common-storage test`

### 5.4 `utils/common-kafka` (KAF-01, KAF-02 — shared barrier)
- `utils/common-kafka/src/test/java/com/shop/common/kafka/producer/OutboxBatchOrderingTest.java` — **highest risk**: it pins the exact barrier semantics (`:13-51`) that payment-service must now adopt. The barrier's contract must not change.
- `utils/common-kafka/src/test/java/com/shop/common/kafka/producer/KafkaMessagePublisherTest.java`
- `utils/common-kafka/src/test/java/com/shop/common/kafka/config/KafkaPropertiesConsumerCommitSemanticsTest.java`, `KafkaPropertiesProducerDefaultsTest.java`, `KafkaPropertiesTopicsRemovedTest.java`
- `utils/common-kafka/src/test/java/com/shop/common/kafka/consumer/BaseKafkaConsumer*Test.java` (5 classes), `.../replay/DeadLetterReplay*Test.java` (3 classes), `.../contract/KafkaTopicContractTest.java`
- **Command:** `./mvnw -pl utils/common-kafka test`

### 5.5 `gateway-service` (GW-04, GW-12, GW-13, GW-27, plus the SL-37 edge half)
- `gateway-service/src/test/java/com/shop/gateway/ratelimit/GlobalRateLimitFilterTest.java` — **highest risk**: four assertions on the literal key `("gateway-system","system")` at `:49`, `:64`, `:85`, `:103`.
- `gateway-service/src/test/java/com/shop/gateway/GatewayRateLimitContextTest.java` — **highest risk**: `:70` `hasSize(29)` and `:71` `allSatisfy(...hasSize(1))`.
- `gateway-service/src/test/java/com/shop/gateway/ratelimit/RedisEdgeRateLimiterTest.java` — key-shape assertions.
- `gateway-service/src/test/java/com/shop/gateway/ratelimit/RateLimitFilterTest.java`, `RouteRateLimitFilterTest.java`, `RateLimitRejectionContractTest.java`, `RateLimitPropertiesTest.java`, `GlobalRateLimitPropertiesTest.java`, `RateLimitKeyResolverTest.java`
- `gateway-service/src/test/java/com/shop/gateway/filter/AdminIpAllowlistFilterTest.java`, `AdminRoleGateFilterTest.java`, `ClientIpResolverTest.java`, `LiteralIpAddressTest.java`, `RequestPathMatcherTest.java`, `FilterOrderTest.java`, `GatewayErrorResponseWriterTest.java`
- `gateway-service/src/test/java/com/shop/gateway/WebFluxRateLimitTests.java`, `WebFluxIpAllowlistTests.java`, `WebFluxRouteTests.java` — **high risk** if a pre-auth `WebFilter` is introduced (filter ordering changes observable behaviour).
- `gateway-service/src/test/java/com/shop/gateway/config/SecurityConfigTest.java`, `GatewayJwtSecurityConfigTest.java`, `GatewaySecurityPostureValidatorTest.java`, `RoutesConfigTest.java`
- `gateway-service/src/test/java/com/shop/gateway/KeycloakRealmImportIT.java` — real-Keycloak import IT; **will fail without a Keycloak container**.
- **Command:** `./mvnw -pl gateway-service test` (note: `KeycloakRealmImportIT` needs Docker/Keycloak)

### 5.6 The eight outbox services (KAF-01, KAF-02)
| Module | Classes to run | Note |
| --- | --- | --- |
| product-service | `ProductOutboxClaimConcurrencyIT`, `OutboxRelayTest`, `OutboxRelayIntegrationTest`, `OutboxRelayRecoveryIT`, `ProductRatingOutboxIntegrationTest` | **KAF-01 target** |
| inventory-service | `InventoryOutboxClaimConcurrencyTest`, `InventoryOutboxRelayTest`, `InventoryOutboxRelayIntegrationTest`, `InventoryOutboxRelayRecoveryIT` | **KAF-01 target**; class is named `*Test` (not `*IT`) so Surefire picks it up by default |
| media-service | `MediaOutboxClaimConcurrencyIT`, `MediaOutboxRelayTest`, `MediaOutboxIT`, `MediaOutboxRelayRecoveryIT`, `MediaOutboxRetentionIT`, `MediaOutboxRetentionSchedulerTest` | **KAF-01 target**; needs MinIO/S3 container |
| rating-service | `RatingOutboxClaimConcurrencyIT`, `RatingOutboxRelayTest`, `RatingOutboxRelayRecoveryIT` | **KAF-01 target** |
| promotion-service | `PromotionOutboxClaimConcurrencyIT`, `PromotionOutboxRelayTest`, `PromotionOutboxRelayRecoveryIT` | **KAF-01 target** |
| shipping-service | `ShippingOutboxClaimConcurrencyIT`, `ShippingOutboxRelayTest`, `ShippingOutboxRelayRecoveryIT`, `ShippingOutboxRetentionSchedulerTest` | **REFERENCE — must stay green and unchanged** |
| order-service | `OrderOutboxClaimConcurrencyIT`, `OrderOutboxRelayTest`, `OrderOutboxRelayRecoveryIT` | **REFERENCE — must stay green and unchanged** |
| payment-service | `PaymentOutboxClaimConcurrencyIT`, `PaymentOutboxRelayOrderTest`, `PaymentOutboxRelayRecoveryIT`, `PaymentOutboxStrandedClaimIT`, `PaymentOutboxBatchAsyncIT`, `OutboxRetentionSchedulerTest` | **KAF-02 target**; `PaymentOutboxRelayOrderTest` is where the new barrier test goes |

**Command:** `./mvnw -pl product-service,inventory-service,media-service,rating-service,promotion-service,shipping-service,order-service,payment-service test`
These are **Testcontainers/PostgreSQL** suites and will not pass without Docker.

### 5.7 `search-service` (CAT-02)
- `search-service/src/test/java/com/shop/searchservice/service/impls/ReindexIT.java`, `SearchQueryIT.java`, `search/SearchIndexProvisioningIT.java`, `kafka/ProductSearchIngestionIT.java`, `kafka/ProductSearchMonotonicProjectionIT.java` — **need a real Elasticsearch container** and are the only suites that prove the new client/transport pairing works against a server.
- `search-service/src/test/java/com/shop/searchservice/service/impls/SearchQueryServiceTest.java`, `ReindexServiceTest.java`, `client/ProductBackofficeClientTest.java`, `controller/SearchControllerTest.java`, `BackofficeSearchControllerTest.java`, `BackofficeSearchAuditTest.java`
- **Command:** `./mvnw -pl search-service test` (+ `./mvnw -pl search-service compile` as a separate gate)

### 5.8 `auth-service` (SL-02 side effect)
- `auth-service/src/test/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheckTest.java` — stubs `hasAdminCredentials()` / `hasServiceAccountCredentials()` (`:22`, `:43`, `:53`, `:63`); changes to the grant-selection logic in `KeycloakAdminClient` can ripple here.
- **Command:** `./mvnw -pl auth-service test`

### 5.9 Fleet gates
- `./mvnw -T1C validate` — Checkstyle (`config/checkstyle.xml`) across the reactor, per `CLAUDE.md` § Quality Gate. **Must be serialised** — four agents are compiling concurrently.
- `./mvnw -T1C -pl utils/common-spring test` — the module-level gate named in `CLAUDE.md`; `utils/common-spring` is touched by **three** of the four agents' scopes in effect (shared-security owns it; gateway-limits and catalog-env can break it transitively).

---

## 6. TASK 5 — Verdict

### **QA_ACCEPTANCE_CRITERIA_READY**

All **14 HIGH findings** and all **3 supporting MEDIUM findings** were independently re-verified against the current source and are **CONFIRMED**; no finding was NOT REPRODUCIBLE. A single falsifiable invariant, a named target test, an exact command, the pre-fix failure mode, the dependency classification and the concurrency dimension are stated for each (§3), a false-pass catalogue is given for each (§4.1), and the regression watchlist names the exact modules and classes (§5).

The following are **binding conditions on acceptance** — they do not change any verdict, but each one, if missed, produces a false pass:

1. **KAF-01 covers 5 services, not 6.** product, inventory, media, rating, promotion. `shipping-service` is already correct (`shipping-service/.../outbox/OutboxEventRepository.java:22-24`, `ShippingOutboxClaimConcurrencyIT.java:167-169`) and must not be modified in behaviour; `order-service` and `payment-service` must also stay unchanged.
2. **KAF-01 must include product-service**, whose `OutboxEventRepository`/`OutboxRelay` fall outside the kafka-outbox agent's declared "7 non-product" scope. This is a **write-scope gap in the batch plan**, not a code issue — the orchestrator must extend the scope or route the change.
3. **SL-37 must be changed in two scopes** — `utils/common-spring/src/main/resources/application.yml:95` (shared-security) **and** `gateway-service/src/main/resources/application.yml:34` (gateway-limits) — and the fix must make the `azp` validator **unconditional or fail-fast**, because `BaseSecurityConfig.java:98` removes the boundary entirely when the list is empty.
4. **SL-01's acceptance signal must be the failure path**, not the success path; the audit's proposed success-path assertions (`shared-libraries.md:1195`) pass on the unfixed code.
5. **SL-16 and CAT-02 each need a property-level, not literal-level, assertion** (retained byte count; absence of the `<version>` element), and CAT-02 additionally needs the root POM to manage **both** ES artifacts plus a green `search-service` compile.
6. **SEC-03 must preserve startability**: emptying `.env.example` collides with `docker-compose.yml:188` (`:?required`); a generation step or an actionable compose guard is part of acceptance.
7. **GW-12's ledger state** must be normalised from `DISCOVERED` to `READY` in `production-audit/state/tasks.json` before the batch is closed (orchestrator action; the evidence gap is closed by §2.12).
8. **Test-suite deletion is not a fix.** Any removed or weakened assertion in `*OutboxClaimConcurrency*`, `GatewayRateLimitContextTest`, or `KeycloakAdminClientServiceAccountTest` must be reviewed as a policy change with a recorded rationale.

### Evidence gaps that remain (none blocking)

- **No test was executed** by this review, by design (four concurrent agents, one local Maven repository). Every "before the fix" behaviour in §3 is derived from reading the current source, not from a run. The orchestrator must produce the **red** baseline for each criterion before the **green** run — the first acceptance run that only shows green is not evidence.
- The audit's `source_evidence` line references in `production-audit/state/tasks.json` do **not** match the current tree for most of this batch. Verified stale: **SL-01, SL-14, SL-15, SL-16, SL-28, SL-33, SL-37, KAF-01, KAF-02, GW-04, GW-13** (partly), **SEC-03** (partly). Verified accurate or near-exact: **GW-12** (`RedisEdgeRateLimiter.java:66`/`:67`), **CAT-02** (`pom.xml:55`, `pom.xml:206-207`, `search-service/pom.xml:36-40`), **GW-27** (`GatewayRateLimitContextTest.java:70-72`), **SL-02** (`KeycloakAdminClient.java:118-121`). Implementers must use the current line numbers in §2 of this document, not the ledger's.
