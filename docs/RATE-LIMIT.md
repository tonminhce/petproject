# Rate Limiting

> Source of truth: implementation in `gateway-service/src/main/java/com/shop/gateway/ratelimit/*`
> (read alongside `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java`,
> `SecurityConfig.java` and `application.yml`). Diagram: [`docs/images/rate-limit-architecture.png`](./images/rate-limit-architecture.png)
> (editable source: [`docs/rate-limit-architecture.drawio`](./rate-limit-architecture.drawio)).
>
> Status: implemented and verified locally (gateway `17/17` tests, full reactor
> `BUILD SUCCESS`, smoke test on Redis thật với two concurrent requests producing
> `429` + `500`). See [`ROADMAP.md §7 (Phase 7 – Core Services)`](./ROADMAP.md)
> for production hardening checklist.

---

## 1. Vì sao rate limiter ở Gateway

- **Bảo vệ backend trước khi request đến**: backend ports của 13 service vẫn đang
  được `docker-compose.yml` `ports:` expose (xem §6). Nếu chỉ để mỗi service tự
  rate-limit, attacker có thể bypass bằng cách gọi thẳng `localhost:8086` cho
  product-service, không qua gateway. Đặt limiter ở gateway là điểm chặn duy nhất
  hiện tại.
- **Phạm vi đơn nhất**: chỉ cần một cấu hình rate-limit áp dụng cho toàn hệ thống, không
  phải sync giữa 13 service.
- **Không parse body ở edge**: gateway chỉ giới hạn theo IP / JWT subject, không phải
  theo username / password. Username-based lockout là trách nhiệm của auth-service hoặc
  Keycloak.
- **Giữ KISS / SRP**: mỗi filter có một trách nhiệm — không lẫn business rule vào
  gateway.

---

## 2. Hai tầng limiter (hai Redis bucket tách biệt)

Thiết kế gồm **hai token-bucket độc lập**, mỗi tầng có Redis bucket riêng. Tầng dưới
chặn trước, tầng trên chặn sau. Một request phải vượt qua cả hai mới được forward.

| Tầng | Bean (RateLimiter) | Order | Key | Mục đích | Default |
|---|---|---|---|---|---|
| **Global system** | `globalRateLimiter` (`@Qualifier`) | `Ordered.HIGHEST_PRECEDENCE` (`Integer.MIN_VALUE`) | cố định `gateway-system` / `system` | Capacity guard cho toàn cluster gateway, đặc biệt cho flash-sale | `2000 req/s`, burst `4000` |
| **Per-client + per-route** | `gatewayRateLimiter` (`@Primary`) | gắn vào từng route qua `requestRateLimiter()` DSL | `user:<sub>` (authenticated) hoặc `ip:<client>` (anonymous) × `routeId` | Fairness per-client per-route | `100 req/s`, burst `200` |

### 2.1 Tại sao hai tầng

- Chỉ per-client + per-route: 10 000 user × 100 req/s × 15 route = 1.5M req/s đổ vào
  backend, vượt tổng capacity. Global layer cắt ở Gateway để backend không chạm ngưỡng.
- Chỉ global: một user spam `/products` vẫn ăn trọn quota tới khi bucket chung hết — không
  công bằng với user khác.
- Hai tầng = cùng mô hình Byte đ (system + descriptor): system bucket cộng dồn tất cả
  request vào một key cố định; route bucket chia theo `routeId × user|ip`.

### 2.2 Tại sao `@Primary` ở `gatewayRateLimiter`

Spring Cloud Gateway auto-config `RequestRateLimiterGatewayFilterFactory` inject
`RateLimiter<?>` **không kèm qualifier**. Khi có hai bean `RedisRateLimiter`,
context fail với `NoUniqueBeanDefinitionException` (đã gặp khi smoke test). `@Primary`
chọn bean mặc định cho route filter; `GlobalRateLimitFilter` inject qua
`@Qualifier("globalRateLimiter")` rõ ràng nên không trúng nhầm. Hai bean tách biệt, mỗi
filter biết mình dùng cái nào.

---

## 3. Filter chain — request flow

```text
Client
  │
  ▼  HTTPS
Spring Cloud Gateway :8080
  │
  ▼
Reactive SecurityWebFilterChain        (CORS → JWT (Keycloak) → RateLimit → Route)
  │
  ▼
[GlobalFilter] GlobalRateLimitFilter     order = HIGHEST_PRECEDENCE
  │   bucket key = gateway-system / system
  │   EVAL request_rate_limiter.lua (atomic INCR+EXPIRE)
  │
  ├── denied → 429 + copy rate-limit headers (X-RateLimit-*) → response.setComplete
  │
  ▼  if allowed
[RouteFilter] RequestRateLimiterGatewayFilterFactory (one per route)
  │
  ▼  if key resolves
RateLimitKeyResolver.resolve(exchange)
  │   principal.isAuthenticated() == true   → "user:<sub>"
  │   else                                   → "ip:<client>" (XForwardedRemoteAddressResolver if hops > 0)
  │
  ▼  bucket key = {routeId, key}
EVAL Lua
  │
  ├── denied (empty key hoặc bucket empty) → 429
  │
  ▼  if allowed
forward đến auth-service / product-service / order-service / ...
```

Đọc chính xác từ code:

- `gateway-service/src/main/java/com/shop/gateway/config/RoutesConfig.java:14-46` — gắn
  filter vào từng route qua DSL:
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
  `denyEmptyKey(true)` quan trọng: nếu resolver trả về `Mono.empty()` (không có JWT, không có
  remote-address), filter từ chối luôn thay vì bỏ qua — chống bypass.

- `gateway-service/src/main/java/com/shop/gateway/ratelimit/GlobalRateLimitFilter.java:33-43` —
  Global filter chạy trước (xem `Ordered.HIGHEST_PRECEDENCE` ở line 47), gọi
  `rateLimiter.isAllowed("gateway-system", "system")`. Allowed → `chain.filter(exchange)`
  (chuyển tiếp cho route filter); denied → `reject(...)` (copy headers + `setComplete`).

- `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitKeyResolver.java:36-44` —
  Resolver chain: principal.isAuthenticated → `user:<sub>`; else `Mono.defer(resolveIpKey)` →
  `ip:<host>`. Trả `Mono.empty()` nếu không có cả hai (bypass-safe).

---

## 4. Algorithm — token bucket qua Lua atomic script

Dùng `org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter` của Spring Cloud
Gateway — đã inspect bytecode, không tự viết Lua. Lý do:

- Script `META-INF/scripts/request_rate_limiter.lua` được Spring Cloud Gateway maintain,
  atomic `GET tokens, DECR if >0, EXPIRE 60s` — race-condition free.
- `RedisRateLimiter.Response.getHeaders()` trả về headers cho allowed và denied:
  - `X-RateLimit-Remaining`
  - `X-RateLimit-Replenish-Rate`
  - `X-RateLimit-Burst-Capacity`
  - `X-RateLimit-Requested-Tokens`
- Global filter copy nguyên headers này vào response khi reject (`reject` ở
  `GlobalRateLimitFilter.java:50-54`).

**Công thức bucket**:

```text
key = request_rate_limiter.{routeId}.{key}.tokens  (counter, EXPIRE 60s)
key = request_rate_limiter.{routeId}.{key}.timestamp (last refill timestamp)

allowed when tokens >= requestedTokens
  refill every (1s / replenishRate) up to burstCapacity
  on allow: tokens -= requestedTokens
  on deny : tokens unchanged
```

Trong code:

```java
// RateLimitProperties.java:17-20
@DefaultValue("100") @Positive int replenishRate,
@DefaultValue("200") @Positive long burstCapacity,
@DefaultValue("1") @Positive int requestedTokens,
```

Mặc định nghĩa: `replenishRate` token được cộng mỗi giây, tối đa `burstCapacity` token, mỗi
request tiêu `requestedTokens` token.

---

## 5. Headers trong response

Spring Cloud Gateway's `RedisRateLimiter` thêm headers cho mọi response:

| Header | Ý nghĩa | Khi nào xuất hiện |
|---|---|---|
| `X-RateLimit-Remaining` | token còn lại sau request này | allowed + denied |
| `X-RateLimit-Replenish-Rate` | token/giây | allowed + denied |
| `X-RateLimit-Burst-Capacity` | bucket tối đa | allowed + denied |
| `X-RateLimit-Requested-Tokens` | token/request | allowed + denied |

Route filter tự gắn headers trên response 200. Global filter copy headers từ
`RateLimiter.Response.getHeaders()` trên response 429 (`GlobalRateLimitFilter.java:50-54`,
test `deniedRequestReturns429WithLimiterHeaders` ở
`GlobalRateLimitFilterTest.java:52-68`).

Chưa có `Retry-After` header. Client tính thời gian retry từ `X-RateLimit-Replenish-Rate`
hoặc đơn giản back-off vài giây. Nếu cần thêm, có thể inject một
`ResponseHeaderFilter` ngay sau Global filter.

---

## 6. Cấu hình

### 6.1 application.yml (workspace hiện tại)

```yaml
gateway:
  public-endpoints:                                   # public cho SecurityConfig
    - /actuator/health
    - /actuator/health/**
    - /actuator/info
    - /actuator/prometheus
    - /v3/api-docs/**
    - /swagger-ui/**
    - /webjars/**
    - /api/v1/auth/**
  rate-limit:                                         # per-client + per-route (route bucket)
    enabled: ${GATEWAY_RATE_LIMIT_ENABLED:true}
    replenish-rate: ${GATEWAY_RATE_LIMIT_REPLENISH_RATE:100}
    burst-capacity: ${GATEWAY_RATE_LIMIT_BURST_CAPACITY:200}
    requested-tokens: ${GATEWAY_RATE_LIMIT_REQUESTED_TOKENS:1}
    # Set only when a trusted reverse proxy is in front of the gateway.
    trusted-proxy-hops: ${GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS:0}
    global:                                            # global system (flash-sale guard)
      enabled: ${GATEWAY_GLOBAL_RATE_LIMIT_ENABLED:true}
      replenish-rate: ${GATEWAY_GLOBAL_RATE_LIMIT_REPLENISH_RATE:2000}
      burst-capacity: ${GATEWAY_GLOBAL_RATE_LIMIT_BURST_CAPACITY:4000}
      requested-tokens: ${GATEWAY_GLOBAL_RATE_LIMIT_REQUESTED_TOKENS:1}
```

### 6.2 Env vars (docker-compose đã forward cho gateway container)

```text
GATEWAY_RATE_LIMIT_ENABLED                 default true
GATEWAY_RATE_LIMIT_REPLENISH_RATE          default 100   # tokens/giây/client/route
GATEWAY_RATE_LIMIT_BURST_CAPACITY          default 200   # bucket size
GATEWAY_RATE_LIMIT_REQUESTED_TOKENS        default 1     # cost per request
GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS      default 0     # 0 = dùng remote address trực tiếp
GATEWAY_GLOBAL_RATE_LIMIT_ENABLED          default true
GATEWAY_GLOBAL_RATE_LIMIT_REPLENISH_RATE   default 2000  # tokens/giây toàn cluster
GATEWAY_GLOBAL_RATE_LIMIT_BURST_CAPACITY   default 4000
GATEWAY_GLOBAL_RATE_LIMIT_REQUESTED_TOKENS default 1
```

`docker-compose.yml:201-220` đã pass các biến này vào `gateway-service` container, mặc
định `${VAR:-default}`. Trong production, set đúng theo capacity cluster và profile.

### 6.3 Tune cho flash-sale

Giả sử cluster chịu được peak `R_total req/s` (đo qua `actuator/prometheus` và load test):

```text
GATEWAY_GLOBAL_RATE_LIMIT_REPLENISH_RATE = R_total × 0.8           # headroom 20%
GATEWAY_GLOBAL_RATE_LIMIT_BURST_CAPACITY = R_total × 2             # 2s burst
GATEWAY_GLOBAL_RATE_LIMIT_REQUESTED_TOKENS = 1

GATEWAY_RATE_LIMIT_REPLENISH_RATE = 100                             # per-user, unchanged
GATEWAY_RATE_LIMIT_BURST_CAPACITY = 200                             # per-user, unchanged
```

Global bucket chặn tổng lưu lượng khi flash sale làm cluster quá tải; per-user bucket chặn
một user spam. Có thể hạ `GATEWAY_RATE_LIMIT_REPLENISH_RATE` xuống `20` trong flash-sale window
để các user có nhiều "burst room" hơn nhưng tổng không vượt capacity. Reset sau khi flash-sale
kết thúc bằng config rollout hoặc dynamic refresh qua Spring Cloud Config (Phase 6 của
ROADMAP).

---

## 7. Test coverage

| Test class | Số test | Coverage |
|---|---|---|
| `RateLimitPropertiesTest` | 3 | constructor validation: `trustedProxyHops >= 0`, `burstCapacity >= requestedTokens`, defaults |
| `GlobalRateLimitPropertiesTest` | 2 | constructor validation, defaults (2000/4000/1) |
| `RateLimitKeyResolverTest` | 5 | `user:<sub>` cho authenticated, `ip:<remote>` cho anonymous, `AnonymousAuthenticationToken` → `ip:<remote>`, `ip:<XFF>` cho trusted-forwarded, empty key khi không có principal lẫn remote |
| `SecurityConfigTest` | 1 | CORS expose đủ các header rate-limit cho browser client |
| `GlobalRateLimitFilterTest` | 4 | allowed → chain filter; denied → 429 + copy headers + chain không gọi; disabled → bypass Redis; `Order = HIGHEST_PRECEDENCE` |
| `RoutesConfigTest` | 2 | 15 routes + 1 filter mỗi route khi enabled; 15 routes + 0 filter khi disabled |
| `GatewayRateLimitContextTest` | 2 | Bean wiring: 2 `RedisRateLimiter`, `KeyResolver`, `GlobalRateLimitFilter`; routes có đúng 1 filter |

Chạy:

```bash
./mvn -pl gateway-service -am test
```

Smoke test thật trên Redis container (đã chạy): 2 request song song khi global bucket
`1 req/s` cho kết quả `429` (limiter chặn) + `500` (request được pass tới upstream, upstream
DNS fail vì backend chưa chạy). Xác nhận Lua atomic chặn ngay tại tầng global.

---

## 8. Edge cases & behavior đã cover

- **Anonymous + không có remote address** (`requestWithoutIdentityOrRemoteAddressHasNoKey`):
  resolver trả `Mono.empty()` → route filter `denyEmptyKey=true` trả `429` ngay. Không có
  request nào bypass vì thiếu cả JWT lẫn IP.
- **Trusted proxy** (`trustedForwardedRequestUsesClientIp`): khi `trustedProxyHops >= 1`,
  dùng `XForwardedRemoteAddressResolver.maxTrustedIndex(...)` thay vì
  `getRemoteAddress()`. Nếu set `trustedProxyHops` mà proxy không thực sự là trusted, attacker
  có thể fake `X-Forwarded-For` để bypass → chỉ set khi đã verify chain proxy.
- **Anonymous user chưa authenticated**: `Principal` không null nhưng là
  `AnonymousAuthenticationToken` → `isUsableAuthentication` trả false → rơi vào IP fallback.
  Test `anonymousAuthenticationTokenUsesRemoteIpKey` bao phủ nhánh này.
- **Disabled cả hai tầng** (`enabled=false`): global filter bypass, route filter bypass.
  Routes vẫn hoạt động, không có filter. Test `canDisableRateLimiterWithoutRemovingRoutes`.
- **Bucket đầy nhưng tokens = 0**: route bucket trống → `429` ngay, không retry.

---

## 9. Failure modes

| Failure | Detection | Impact | Mitigation |
|---|---|---|---|
| Redis down | `RedisRateLimiter.isAllowed` trả `[1, -1]` (fail-open trong Lua), request vẫn pass | gateway pass mọi request, mất admission control | Redis Sentinel / Cluster; alert khi `redisRateLimiter` lỗi; document cho ops |
| Redis cache stale khi failover | Sentinel promote replica, một vài giây bucket stale → user có thể vượt bucket | burst nhỏ có thể pass | set `min-replicas-to-write=1` trên Sentinel; kiểm tra `connected-slaves` |
| Lua script fail (Redis fail trả lỗi khác) | `RedisRateLimiter.lambda$isAllowed$0` log error, return allowed | tương tự Redis down | monitor log `rate-limit` keyword; circuit breaker tùy chọn |
| `X-Forwarded-For` spoofing | attacker giả IP để bypass IP bucket | quota cao hơn bình thường | chỉ set `trustedProxyHops` khi proxy chain được verify; hoặc dùng `X-Real-IP` từ proxy đáng tin |
| Limiter fail trên request quá lớn | rate-limit bị đầy → `429` tăng | UX khó chịu cho user | expose headers đủ cho client retry; cân nhắc Retry-After |
| Global filter tắt (`enabled=false`) | request đến route filter thẳng, có thể vượt cluster capacity | mất flash-sale guard | monitor log `gateway.rate-limit.global.enabled` qua actuator |
| Backend port bypass | gọi thẳng `localhost:8086` không qua gateway | limiter vô hiệu | đổi `docker-compose.yml` để backend ports chỉ internal (`expose:` thay vì `ports:`); k8s `ClusterIP` Service |

---

## 10. Cải tiến có thể làm sau

1. **Per-route limit override**: hiện tại tất cả 15 route dùng cùng
   `RateLimitProperties`. Endpoint đắt (`/api/v1/orders`, `/api/v1/payments`) có thể
   cần quota thấp hơn. Có thể thêm `RateLimitProperties` per-route map keyed by
   `ServiceRoute.id`.
2. **Atomic dual-bucket check**: global và route check tuần tự, có thể vượt cả hai
   trong một khoảnh khắc nếu request song song. Có thể viết Lua riêng để check
   atomic, nhưng độ phức tạp tăng. Chấp nhận được ở quy mô hiện tại.
3. **`Retry-After` header**: thêm vào global filter khi reject, dựa trên
   `X-RateLimit-Replenish-Rate`.
4. **Metrics**: export `redis_rate_limiter_*` từ Spring Cloud Gateway qua
   Micrometer. Hiện chỉ có access log.
5. **Dynamic config**: hiện set qua env vars, phải rollout để thay đổi. Phase 6
   (`spring-cloud-config`) sẽ hỗ trợ refresh runtime.
6. **Anonymous token test**: thêm test cho `AnonymousAuthenticationToken` → IP fallback
   (hiện chỉ test với `Mono.empty()` và `TestingAuthenticationToken`).

---

## 11. Cross-references

- [`ARCHITECTURE.md`](./ARCHITECTURE.md) — §1 component map (Gateway filters), §5
  data stores (Redis), §6 cross-cutting concerns (rate-limit trong table).
- [`ROADMAP.md`](./ROADMAP.md) — §7 Phase 7 status, §8 risk register R5 (Liquibase
  master).
- [`SERVICE-CATALOG.md`](./SERVICE-CATALOG.md) — gateway section (sẽ cross-ref
  rate-limit khi section được thêm).
- [`COMMON-LIB-REFERENCE.md`](./COMMON-LIB-REFERENCE.md) — `common-spring`
  correlation-id, i18n, exception handler.
- [`AUTH-SERVICE-IMPLEMENTATION.md`](./AUTH-SERVICE-IMPLEMENTATION.md) — cách auth-service
  dùng `common-keycloak` cho token, không phụ thuộc vào gateway limiter.
- Diagram: [`docs/images/rate-limit-architecture.png`](./images/rate-limit-architecture.png)
  — editable source ở [`docs/rate-limit-architecture.drawio`](./rate-limit-architecture.drawio).
  Validate: `0 error(s), 0 warning(s)` (v3 swimlane layout). Mở file `.drawio`
  trong draw.io desktop hoặc nhúng link browser-fallback
  (`scripts/encode_drawio_url.py docs/rate-limit-architecture.drawio`) để xem
  trực tiếp.
