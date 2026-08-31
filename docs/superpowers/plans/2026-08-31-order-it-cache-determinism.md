# Order IT Cache Determinism Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** kill the pre-existing `productPriceCacheHit` flake via deterministic per-test Redis cache state + a 50x determinism gate.

**Architecture:** 2-line fix in the IT base (`cache.clear()` in `@BeforeEach`) + `@RepeatedTest(50)` proof + fleet sweep for other shared caches. Spec: `docs/superpowers/specs/2026-08-31-test-cache-isolation-design.md` (rules 1–3 are binding).

**Tech Stack:** JUnit 5 `@RepeatedTest`, Spring Cache abstraction, existing Testcontainers IT base.

**Spec:** docs/superpowers/specs/2026-08-31-test-cache-isolation-design.md

## Global Constraints

- **NO masking**: `@Disabled`, `@Retryable`, relaxed verify counts, or assertion removal are FORBIDDEN (spec rule 2). Strict `verify(1, GET)` stays.
- Fix location: `AbstractOrderServiceIT.resetDownstreamStubs` (`@BeforeEach`) — locate via `rg -l "resetDownstreamStubs" order-service/src/test`.
- Key namespaces per test: NOT required (spec rule 4).
- Gate: `./mvnw -pl order-service test` green (154+ tests with repeat multiplier).

---

### Task 1: cache clear in IT base + determinism gate

**Files:**
- Modify: `order-service/src/test/java/com/shop/orderservice/**/AbstractOrderServiceIT.java` (exact path via rg above)
- Modify: `order-service/src/test/java/com/shop/orderservice/service/OrderCreationSagaIntegrationTest.java` (annotate `productPriceCacheHit_returnsProductSnapshot`)

**Interfaces:**
- Produces: fleet precedent for spec rule 1/3 (any future IT base copies this shape).

- [ ] **Step 1: failing-proof first** — run `./mvnw -pl order-service test -Dtest=OrderCreationSagaIntegrationTest#productPriceCacheHit_returnsProductSnapshot` 5x, record any failure (flake evidence; may pass all 5 — the 50x gate in Step 3 is the real proof).
- [ ] **Step 2: the fix** (user-ratified, exact):

```java
@Autowired
private CacheManager cacheManager;

// inside resetDownstreamStubs(), after the WireMock resets:
// Deterministic Redis cache state per test — pre-existing flake from order-wiring era
var cache = cacheManager.getCache("productPrice");
if (cache != null) cache.clear();
```

- [ ] **Step 3: determinism gate** — annotate the test:

```java
@RepeatedTest(50)
```

(import `org.junit.jupiter.api.RepeatedTest`), run the single method: 50/50 green. If any iteration fails, STOP and report — the fix hypothesis is wrong, do not iterate blind.
- [ ] **Step 4:** `./mvnw -pl order-service test` full suite green (with the 50x multiplier).
- [ ] **Step 5: commit** `fix(order-it): deterministic Redis state for productPrice cache (pre-existing flake fix)`

**Amendment (root cause, evidence-based).** Implementation revealed the per-test `cache.clear()`
alone could NOT kill the flake: Redis MONITOR wire evidence (see
`cache-flake-root-cause-verdict.md`) proved the real root cause is spring-data-redis 4.1.1's
`DefaultRedisCacheWriter` defaulting to `asynchronousWrites=true` — the `@Cacheable` PUT is
dispatched fire-and-forget on a Netty/Reactor event-loop thread (reactive connection), so the
test's second `getProduct()` races its own first call's cache write by tens of µs (13/50
iterations saw `GET#2 BEFORE SET` → duplicate HTTP fetch → `verify(1)` "received 2"). The
fix therefore has three legs: (1) `CacheConfig` supplies an explicit
`RedisCacheWriter.create(factory, c -> c.immediateWrites())` via
`RedisCacheManagerBuilder.cacheWriter(...)` — no Boot property exists for this — restoring
synchronous put/clear semantics (serializer config, TTL, statistics, `.transactionAware()`
unchanged; `enableStatistics()` still wraps the custom writer via `withStatisticsCollector`);
(2) the repeated test's `productId` stays fresh per iteration (`@BeforeEach`
`UUID.randomUUID()`, new instance per repetition) so no stale entry can alias across
iterations; (3) the `cache.clear()` in `AbstractOrderServiceIT` stays as defense-in-depth for
cross-test/cross-class pollution (e.g. `ConfirmOrchestrationIT` reuses class-fixed product
ids) — and is itself synchronous under immediate writes. Spec rule 6 codifies the writer
requirement fleet-wide.

### Task 2: fleet sweep — other shared caches

**Files:**
- Modify: only files the sweep proves need the same treatment (none expected)
- Verify: `rg -n "@Cacheable|CacheManager" order-service/src/main --type java` (enumerate cache names in use) and `rg -ln "CacheManager|getCache" order-service/src/test`
- Check: does `OrderMetricsTest` exist and touch shared caches? (`rg -l "OrderMetrics" order-service/src/test`)

- [ ] **Step 1:** list every `@Cacheable` cache name in order-service main (expect exactly `productPrice`).
- [ ] **Step 2:** for EACH cache name found, confirm the IT base clears it (Task 1 adds `productPrice`; add lines for any others — same 2-line shape, same commit style if needed).
- [ ] **Step 3:** confirm no OTHER IT base in order-service boots a caching context without reset (rg for `@EnableCaching`/`RedisCacheConfiguration` in test scope).
- [ ] **Step 4:** fleet-wide scan for the same latent pattern: `rg -l "@EnableCaching" */src/main --type java | xargs -I{} dirname {}` → for each service with caching + Testcontainers ITs, note in report whether its IT base resets caches (rating/favourite/shipping have no caches today — expected "n/a"; document).
- [ ] **Step 5: commit (only if files changed)** `chore(order-it): sweep remaining shared caches (spec rule 5)`

### Task 3: full verification + close

- [ ] **Step 1:** `./mvnw -pl order-service test` green, report exact count (153 base + repeat multiplier ≈ 202+ test executions).
- [ ] **Step 2:** run the full suite 2 more times (stability signal; 3 consecutive greens).
- [ ] **Step 3:** `./mvnw -T1C install -DskipTests -q` (fleet compile unaffected).
- [ ] **Step 4:** reviewer gate (one dispatch — whole diff vs main, spec rules 1-5 compliance, no masking).
- [ ] **Step 5: merge** `epic/order-it-cache-determinism` → main at MAIN ROOT `--no-ff`, push.
