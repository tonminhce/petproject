# Test Cache Isolation — Fleet Rule

- Date: 2026-08-31
- Status: Approved (user-diagnosed root cause; fix approach ratified)
- Scope: binding rule for ALL services with `@Cacheable` + shared Testcontainers caches.

## Root cause (order-service flake, order-wiring era)

`OrderCreationSagaIntegrationTest.productPriceCacheHit_returnsProductSnapshot` failed
intermittently (`verify(1, GET /products/{id})` saw 2 requests). Cause chain:

1. `AbstractOrderServiceIT` Redis container is `static final` — one JVM-wide instance,
   **never cleared between tests**.
2. Cache `productPrice` TTL = 10 min — entries outlive every test.
3. `@BeforeEach resetDownstreamStubs` resets WireMock stubs but **not** Redis cache state.
4. A prior test's cached `ProductSnapshot` entry (or TX-deferred write from
   `RedisCacheConfiguration.transactionAware()`) makes the second `getProduct()` call
   MISS → second HTTP fetch → strict `verify(1)` fails.

The CCE-on-cached-record bug was a DIFFERENT failure mode (regression-guarded by
`CacheSerializerRoundTripTest`); this flake is cache-state pollution.

## Rules (binding for every current + future service)

1. **Every IT base class that boots a cache-capable context MUST reset cache state in
   `@BeforeEach`** — `cacheManager.getCache("<name>").clear()` per cache the suite's
   tests can populate. Stub resets alone are insufficient.
2. **Cache-hit assertions stay STRICT** (`verify(1, ...)`) — never mask flakes with
   `@Disabled`, `@Retryable`, relaxed verify counts, or removed assertions. The strict
   count IS the regression guard (C1 polymorphic-typing fix).
3. **Determinism proof for any cache-behavior test**: `@RepeatedTest(50)` on the test
   method (or an equivalent repeat gate) — a test that only passes sometimes is a bug.
4. **Key namespaces per test are NOT required** — UUID-keyed entries cannot collide
   (astronomically improbable); blanket `cache.clear()` per test is the whole fix.
5. New `@Cacheable` introduced by any future epic ⇒ same epic's IT base gets the
   matching `clear()` line + a repeated-test proof on its cache-hit assertion.
6. **Any `RedisCacheManager` in this fleet MUST configure immediateWrites**
   (`RedisCacheWriter.create(connectionFactory, c -> c.immediateWrites())` wired via
   `RedisCacheManagerBuilder.cacheWriter(...)` — see order-service `CacheConfig`).
   spring-data-redis 4.1.1 defaults the writer to asynchronous writes: the `@Cacheable`
   PUT is fire-and-forget on a Netty/Reactor event-loop thread, so a same-transaction
   (or same-request) reader racing it by tens of µs misses and re-fetches downstream
   (Redis MONITOR wire evidence: `GET#2 BEFORE SET` in 13/50 iterations). No Boot
   `spring.cache.redis.*` property exists for this. Note `clear()` is async under the
   default too — immediate writes make the IT-base per-test `cache.clear()` (rule 1)
   synchronous as well. New caching services copy order-service `CacheConfig`.

## Fleet sweep obligation

Any service introducing the next `@Cacheable` must `rg -l "@Cacheable" <module>/src`
and confirm each cache name appears in its IT base `@BeforeEach` clear list.
