# Edge Gateway Rate Limiting Architecture

> **Implementation source of truth**: `gateway-service/src/main/java/com/shop/gateway/ratelimit/*`
> Supporting references: `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java`, `SecurityConfig.java`, and `application.yml`.
> Architecture diagram: [`docs/images/rate-limit-architecture.png`](./images/rate-limit-architecture.png)
> Editable diagram source: [`docs/architecture/gateway-security-rate-limit.drawio`](./architecture/gateway-security-rate-limit.drawio).

---

## 1. Rationale for edge-tier rate limiting

- **Direct backend protection**: While all microservice ports are reachable within the container network, edge limiting prevents excessive traffic from consuming backend resources. Placing the limiter at the edge gateway establishes a single admission control barrier before requests reach downstream services.
- **Unified policy scope**: A single rate-limiting configuration applies across the fleet instead of managing disparate rate-limiting filters across 14 services.
- **Header-only processing**: The gateway limits requests by client IP address or JWT principal subject without buffering or inspecting request payloads. User-specific credential lockouts remain the responsibility of `auth-service` and Keycloak.
- **Separation of concerns**: Rate limiting operates purely as a network admission control filter, decoupled from internal business logic.

---

## 2. Two-tier token bucket architecture

The rate limiter employs two independent token buckets backed by Redis. Requests pass sequentially through both tiers before the gateway forwards them downstream.

| Tier | Bean qualifier | Filter precedence | Redis key structure | Operational objective | Default settings |
|---|---|---|---|---|---|
| **Global system** | `globalRateLimiter` (`@Qualifier`) | `Ordered.HIGHEST_PRECEDENCE` (`Integer.MIN_VALUE`) | Static key `gateway-system` / `system` | Protects cluster capacity during high-concurrency traffic surges | 2,000 req/s, burst 4,000 |
| **Per-client per-route** | `gatewayRateLimiter` (`@Primary`) | Route filter via `requestRateLimiter()` DSL | `user:<sub>` (authenticated) or `ip:<remote>` (anonymous) combined with `routeId` | Ensures per-client and per-route fairness | 100 req/s, burst 200 |

### 2.1 Rationale for two layers

- **Per-client alone is insufficient**: With 10,000 concurrent clients each allowed 100 req/s across 15 routes, total downstream traffic could reach 1.5M req/s, exceeding backend datastore limits. The global layer drops excess requests at the edge before backend capacity saturates.
- **Global alone is insufficient**: A single aggressive client calling `/products` could consume the entire shared pool, starving legitimate users.
- **Combined model**: The global bucket counts all incoming cluster traffic against a fixed key, while the route bucket isolates traffic per route and client identifier.

### 2.2 Edge scopes: backoffice and search (F-034)

The two tiers above are not the whole picture: two further scopes are metered
per **client IP** by `RateLimitFilter` — the backoffice prefixes
(`/api/v1/backoffice/**`, 10 req/min) and the search prefix (`/api/v1/search/**`,
60 req/min). Every other path is unlimited.

| Property | Value |
|---|---|
| Bucket location | Redis — `gateway:edge-rate-limit:<scope>:<client-ip>:{tokens,timestamp}` |
| Algorithm | Token bucket in a Lua script (atomic read → refill → consume → write) |
| Capacity | The configured requests-per-minute value (10 / 60) |
| Refill rate | The same value per minute (10 rpm = 0.1667 tokens/s) |
| Rejection | HTTP 429 with the fleet error envelope and `X-RateLimit-Remaining: 0` |
| Redis outage | The error propagates (HTTP 500) — a limiter outage is never reported as "too many requests" |

**F-034 — the budget is fleet-wide.** The buckets used to live in the gateway
JVM (a Caffeine cache of bucket4j buckets), which multiplied the effective
limit by the replica count: with four replicas a single client could make
4 x 10 backoffice requests per minute, i.e. four times the budget that is
supposed to protect the backoffice. The bucket now lives in Redis, so every
replica meters the client against the same budget. `RedisEdgeRateLimiterTest`
proves this by driving two independent limiter instances (two replicas)
against one Redis.

The refill rate is fractional (10 rpm = 1 token per 6 s), which is why the
edge buckets use their own script instead of the Spring Cloud Gateway
limiter, whose replenish rate is an integer number of tokens per second.

**Configuration** (`gateway.edge-rate-limit.*`):

| Environment variable | Default | Meaning |
|---|---|---|
| `GATEWAY_EDGE_RATE_LIMIT_ENABLED` | `true` | Switches the edge scopes off entirely |
| `GATEWAY_EDGE_RATE_LIMIT_BACKOFFICE_RPM` | `10` | Fleet-wide budget per client IP for the backoffice prefixes |
| `GATEWAY_EDGE_RATE_LIMIT_SEARCH_RPM` | `60` | Fleet-wide budget per client IP for the search prefix |

### 2.3 Primary bean designation

Spring Cloud Gateway autoconfiguration for `RequestRateLimiterGatewayFilterFactory` injects `RateLimiter<?>` without a bean qualifier. When registering two `RedisRateLimiter` beans, the Spring context fails with a `NoUniqueBeanDefinitionException`. Marking `gatewayRateLimiter` as `@Primary` resolves this for route-level filters, while `GlobalRateLimitFilter` injects `globalRateLimiter` explicitly using `@Qualifier("globalRateLimiter")`.

---

## 3. Filter execution pipeline

```text
Client request
  │
  ▼  HTTPS
Spring Cloud Gateway :8080
  │
  ▼
Reactive SecurityWebFilterChain (CORS -> JWT signature validation -> Rate limiting -> Routing)
  │
  ▼
[GlobalFilter] GlobalRateLimitFilter (Order: HIGHEST_PRECEDENCE)
  │   Key: gateway-system / system
  │   Evaluates request_rate_limiter.lua atomically in Redis
  │
  ├── Denied -> Return HTTP 429 and copy X-RateLimit-* headers -> response.setComplete
  │
  ▼ Allowed
[RouteFilter] RequestRateLimiterGatewayFilterFactory (Configured per route)
  │
  ▼
RateLimitKeyResolver.resolve(exchange)
  │   If authenticated -> "user:<sub>"
  │   If anonymous     -> "ip:<remote-ip>" (evaluates trusted proxy hops if configured)
  │
  ▼ Evaluates Redis bucket {routeId, key}
Evaluates Lua script
  │
  ├── Denied (bucket exhausted or empty key) -> Return HTTP 429
  │
  ▼ Allowed
Forward request to backend microservice
```

### Key implementation details:

- **Route filter registration (`RoutesConfig.java:14-46`)**:
  ```java
  routesBuilder.route(route.id(), spec -> {
      final var routeSpec = spec.path(route.path());
      if (rateLimitProperties.enabled()) {
          routeSpec.filters(filters -> filters.requestRateLimiter(config -> config
                  .setRateLimiter(rateLimiter)
                  .setKeyResolver(keyResolver)
                  .setDenyEmptyKey(true)
                  .setEmptyKeyStatus("429")));
      }
      return routeSpec.uri(route.uri());
  });
  ```
  Setting `denyEmptyKey(true)` guarantees that if the key resolver returns `Mono.empty()` (missing JWT and missing remote address), the request is rejected immediately with HTTP 429 rather than bypassing the rate limiter.

- **Global filter execution (`GlobalRateLimitFilter.java:33-43`)**:
  Executes at `Ordered.HIGHEST_PRECEDENCE`, calling `rateLimiter.isAllowed("gateway-system", "system")`. Allowed requests proceed down the filter chain; rejected requests exit immediately with HTTP 429 and rate-limit headers.

- **Key resolution (`RateLimitKeyResolver.java:36-44`)**:
  Checks whether the user principal is authenticated. If valid, resolves to `user:<userId>`. Otherwise, falls back to remote IP resolution (`ip:<client-ip>`). Returns `Mono.empty()` if neither identifier is available.

---

## 4. Token bucket algorithm via Redis Lua script

The gateway relies on the default Redis Lua script provided by Spring Cloud Gateway (`META-INF/scripts/request_rate_limiter.lua`). This script executes atomic token decrement and expiration updates in Redis, preventing race conditions during concurrent requests.

### Bucket mechanics:

```text
Redis key 1: request_rate_limiter.{routeId}.{key}.tokens    (Token counter, 60s TTL)
Redis key 2: request_rate_limiter.{routeId}.{key}.timestamp (Last refill timestamp)

Conditions:
  Allowed when tokens >= requestedTokens
  Tokens refill at (replenishRate / second) up to burstCapacity
  When allowed: tokens = tokens - requestedTokens
  When denied: tokens remain unchanged
```

### Configuration properties (`RateLimitProperties.java`):

```java
@DefaultValue("100") @Positive int replenishRate,
@DefaultValue("200") @Positive long burstCapacity,
@DefaultValue("1") @Positive int requestedTokens,
```

- `replenishRate`: Tokens added per second.
- `burstCapacity`: Maximum token capacity of the bucket.
- `requestedTokens`: Tokens consumed per request (defaults to 1).

---

## 5. HTTP response headers

When enabled, response headers communicate remaining quota to clients:

| Header | Meaning | Present on |
|---|---|---|
| `X-RateLimit-Remaining` | Remaining available tokens in the bucket | HTTP 200 and 429 |
| `X-RateLimit-Replenish-Rate` | Configured replenishment rate (tokens/sec) | HTTP 200 and 429 |
| `X-RateLimit-Burst-Capacity` | Maximum capacity of the token bucket | HTTP 200 and 429 |
| `X-RateLimit-Requested-Tokens` | Tokens consumed by the current request | HTTP 200 and 429 |

Route filters append these headers on successful responses. `GlobalRateLimitFilter` copies them from the Redis response on HTTP 429 rejections. Clients can calculate backoff delays using `X-RateLimit-Replenish-Rate`.

---

## 6. Configuration reference

### 6.1 `application.yml` definition

```yaml
shop:
  security:
    public-paths:
      - /actuator/health
      - /actuator/health/**
      - /actuator/info
      - /v3/api-docs/**
      - /swagger-ui/**
      - /webjars/**
      - /api/v1/auth/**
  rate-limit:
    enabled: ${GATEWAY_RATE_LIMIT_ENABLED:true}
    replenish-rate: ${GATEWAY_RATE_LIMIT_REPLENISH_RATE:100}
    burst-capacity: ${GATEWAY_RATE_LIMIT_BURST_CAPACITY:200}
    requested-tokens: ${GATEWAY_RATE_LIMIT_REQUESTED_TOKENS:1}
    trusted-proxy-hops: ${GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS:0}
    global:
      enabled: ${GATEWAY_GLOBAL_RATE_LIMIT_ENABLED:true}
      replenish-rate: ${GATEWAY_GLOBAL_RATE_LIMIT_REPLENISH_RATE:2000}
      burst-capacity: ${GATEWAY_GLOBAL_RATE_LIMIT_BURST_CAPACITY:4000}
      requested-tokens: ${GATEWAY_GLOBAL_RATE_LIMIT_REQUESTED_TOKENS:1}
```

### 6.2 Environment variables

```text
GATEWAY_RATE_LIMIT_ENABLED                 Default: true
GATEWAY_RATE_LIMIT_REPLENISH_RATE          Default: 100   (tokens/sec per client/route)
GATEWAY_RATE_LIMIT_BURST_CAPACITY          Default: 200   (bucket size)
GATEWAY_RATE_LIMIT_REQUESTED_TOKENS        Default: 1     (cost per request)
GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS      Default: 0     (0 = use direct remote address)
GATEWAY_GLOBAL_RATE_LIMIT_ENABLED          Default: true
GATEWAY_GLOBAL_RATE_LIMIT_REPLENISH_RATE   Default: 2000  (tokens/sec across gateway cluster)
GATEWAY_GLOBAL_RATE_LIMIT_BURST_CAPACITY   Default: 4000
GATEWAY_GLOBAL_RATE_LIMIT_REQUESTED_TOKENS Default: 1
```

---

## 7. Automated test verification

| Test class | Count | Covered scenarios |
|---|:---:|---|
| `RateLimitPropertiesTest` | 3 | Validates constructor rules: `trustedProxyHops >= 0`, `burstCapacity >= requestedTokens`, and defaults |
| `GlobalRateLimitPropertiesTest` | 2 | Validates global limits and property defaults (2000/4000/1) |
| `RateLimitKeyResolverTest` | 5 | Validates `user:<sub>` resolution, anonymous `ip:<remote>` fallback, `AnonymousAuthenticationToken` mapping, trusted proxy extraction, and empty key handling |
| `SecurityConfigTest` | 1 | Ensures CORS configuration exposes rate-limit headers to browser clients |
| `GlobalRateLimitFilterTest` | 4 | Verifies chain execution on allowed requests, 429 response with headers on rejection, bypass when disabled, and filter order |
| `RoutesConfigTest` | 2 | Confirms filter attachment across all routes when enabled, and clean pass-through when disabled |
| `GatewayRateLimitContextTest` | 2 | Spring context validation: verifies bean wiring for both limiters, key resolver, and global filter |

Test command:
```bash
./mvnw -pl gateway-service -am test
```

---

## 8. Failure modes and mitigations

| Failure scenario | Detection | Impact | Mitigation strategy |
|---|---|---|---|
| Redis connectivity failure | `RedisRateLimiter.isAllowed` returns `[1, -1]`, logging a warning | Gateway fails open, allowing traffic without rate enforcement | Deploy Redis Sentinel or Redis Cluster in production, configure alerting on connection pool failures |
| Redis failover lag | Sentinel promotes replica; brief cache lag on token counts | Small burst of requests may exceed quota during promotion | Configure `min-replicas-to-write=1` and monitor replication lag |
| Proxy IP spoofing | Client supplies fabricated `X-Forwarded-For` header | Client circumvents IP-based bucket restrictions | Keep `trustedProxyHops=0` unless behind a verified edge proxy that overwrites incoming forwarding headers |
| Backend port bypass | Caller sends requests directly to port 8086, bypassing port 8080 | Gateway rate limiting is bypassed | In production, restrict container port mappings using internal Docker networks or Kubernetes `ClusterIP` services |
