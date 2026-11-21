# Actual System Map — petproject @ f242158265163d65ef73d378331d2bd7a92479ee

Independent audit artifact. Every statement below is traced to source read in this run.
Trust nothing from `production-audit/` or `docs/`.

## 1. Physical topology (as deployed)

### docker-compose (dev, `docker-compose.yml`)
- Shared single `postgres` instance; **logical database per service** (`authservice`,
  `productservice`, `orderservice`, `paymentservice`, `shippingservice`,
  `inventoryservice`, `favouriteservice`, `mediaservice`, `taxservice`,
  `promotionservice`, `ratingservice`, `notificationservice`) —
  `docker-compose.yml:280,300,332,366,416,442,468,486,520,538,557,609`.
- Single `kafka` (KRaft) at `kafka:9092`, `redis`, `keycloak:8080` (public `localhost:9090`).
- Every service reaches Kafka via `SHOP_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`.

### Kubernetes (`k8s/`)
- Namespace `shop-system`. Ingress (nginx, TLS via cert-manager, host `api.shop.example.com`)
  routes `/` → `gateway-service:8080` — `k8s/ingress.yaml:17-27`. **Only one ingress rule
  exists; no backend service is exposed directly.**
- All service `Service` objects are ClusterIP (no `type: LoadBalancer/NodePort` anywhere in
  `k8s/microservices.yaml`). The gateway is the **only** ingress entry point, but inside the
  cluster any pod can call any service directly (there is **no NetworkPolicy** in `k8s/`).
- Gateway Deployment: 3 replicas + HPA (2–10) — `k8s/gateway-service.yaml:9,95-113`.
  Env sets `JWT_ISSUER_URI`, `SPRING_DATA_REDIS_HOST`, `CORS_ALLOWED_ORIGIN_PATTERNS`.
  It does **NOT** set `GATEWAY_TRUSTED_PROXY_CIDRS` / `GATEWAY_TRUSTED_PROXY_HOPS` /
  `ADMIN_IP_ALLOWLIST` (see ARCH-1 in findings/arch-design.md).
- Databases: one logical DB per service on a shared postgres
  (`k8s/microservices.yaml` datasource URLs). `search-service` has no datasource (ES only).

## 2. Route table (complete, from `ServiceRoute.java` + `RoutesConfig.java`)

All routes are generated from the `ServiceRoute` enum (`gateway-service/.../constant/ServiceRoute.java:8-40`)
by `RoutesConfig.gatewayRoutes()` (`config/RoutesConfig.java:40-54`); path pattern is always
`/api/v1/{resource}/**`; URI is `http://{service}:{port}` unless overridden by
`gateway.route-targets.overrides` (`RouteTargetProperties.java:15-17`).

| Route id | Path prefix | Target | Auth |
|---|---|---|---|
| auth-service | /api/v1/auth/** | auth-service:8088 | public (gateway + service) |
| auth-users | /api/v1/users/** | auth-service:8088 | authenticated |
| auth-roles | /api/v1/roles/** | auth-service:8088 | authenticated (+ADMIN @PreAuthorize in service) |
| favourite-service | /api/v1/favourites/** | favourite-service:8081 | authenticated |
| inventory-service | /api/v1/inventory/** | inventory-service:8082 | authenticated (+role @PreAuthorize) |
| media-service | /api/v1/medias/** | media-service:8083 | public (gateway) |
| order-service | /api/v1/orders/** | order-service:8084 | authenticated (track public) |
| order-cart | /api/v1/carts/** | order-service:8084 | public (gateway + service) |
| payment-service | /api/v1/payments/** | payment-service:8085 | authenticated |
| product-service | /api/v1/products/** | product-service:8086 | public (gateway) |
| shipping-service | /api/v1/shipping/** | shipping-service:8087 | authenticated |
| payment-webhooks | /api/v1/webhooks/payments/** | payment-service:8085 | public (signature-validated in service) |
| shipping-webhooks | /api/v1/webhooks/shipping/** | shipping-service:8087 | public (signature-validated in service) |
| rating-service | /api/v1/ratings/** | rating-service:8089 | public (gateway) |
| notification-service | /api/v1/notifications/** | notification-service:8090 | authenticated |
| tax-service | /api/v1/tax/** | tax-service:8091 | authenticated |
| promotion-service | /api/v1/promotions/** | promotion-service:8093 | authenticated |
| search-service | /api/v1/search/** | search-service:8094 | public (gateway) |
| backoffice-ratings | /api/v1/backoffice/ratings/** | rating-service:8089 | ADMIN (gateway gate) + service @PreAuthorize |
| backoffice-products | /api/v1/backoffice/products/** | product-service:8086 | ADMIN + service |
| backoffice-categories | /api/v1/backoffice/categories/** | product-service:8086 | ADMIN + service |
| backoffice-brands | /api/v1/backoffice/brands/** | product-service:8086 | ADMIN + service |
| backoffice-search | /api/v1/backoffice/search/** | search-service:8094 | ADMIN + service |
| backoffice-promotions | /api/v1/backoffice/promotions/** | promotion-service:8093 | ADMIN + service |
| backoffice-tax-classes | /api/v1/backoffice/tax-classes/** | tax-service:8091 | ADMIN + service |
| backoffice-tax-rates | /api/v1/backoffice/tax-rates/** | tax-service:8091 | ADMIN + service |
| backoffice-notifications | /api/v1/backoffice/notifications/** | notification-service:8090 | ADMIN + service |
| backoffice-payments | /api/v1/backoffice/payments/** | payment-service:8085 | ADMIN + service |
| backoffice-shipments | /api/v1/backoffice/shipments/** | shipping-service:8087 | ADMIN + service |
| backoffice-medias | /api/v1/backoffice/medias/** | media-service:8083 | ADMIN + service |

Gateway `public-endpoints` (`gateway-service/src/main/resources/application.yml:39-64`):
`/actuator/health(/...)`, `/actuator/info`, `/actuator/prometheus`, `/v3/api-docs/**`,
`/swagger-ui/**`, `/webjars/**`, plus the public API paths above.

**Not routed by the gateway:** `/api/v1/internal/**` (`common-core .../constants/ApiPaths.java:33-34`
defines `INTERNAL_PRODUCT_MEDIA_REFERENCES`, `INTERNAL_INVENTORY_RESERVATIONS`). These are
service-to-service only (direct ClusterIP HTTP + service token + `@PreAuthorize hasRole('SERVICE')`,
e.g. `inventory-service/.../InternalInventoryController.java:24`).

### Edge filter chain, in intended order (`filter/FilterOrder.java`)
1. `AdminIpAllowlistFilter` (GlobalFilter, order = HIGHEST_PRECEDENCE) — active only when
   `ADMIN_IP_ALLOWLIST` non-empty; bypasses health/webhooks/public storefront.
2. `GlobalRateLimitFilter` (GlobalFilter, order = HIGHEST_PRECEDENCE — **same value as 1**)
   — fleet-wide two-tier (lane + client) Redis token bucket.
3. `RateLimitFilter` (GlobalFilter, +10) — per-scope buckets: BACKOFFICE 10 rpm/IP,
   SEARCH 60 rpm/IP.
4. `AdminRoleGateFilter` (GlobalFilter, +20) — backoffice prefixes require `ADMIN` in
   `realm_access.roles` OR any `resource_access.*.roles` of the validated JWT; rejects
   matrix-variable and percent-encoded paths (anti-evasion).
5. `DownstreamResilienceFilter` (GlobalFilter, +30) — per-route circuit breaker +
   per-service bulkhead.
6. Spring Security chain: public paths permitAll, everything else JWT-authenticated
   (`SecurityConfig.java:44-53`); `PreAuthRateLimitFilter` runs before AUTHENTICATION
   (3000 rpm/IP default).
7. Route filter `RouteRateLimitFilter` (per route, SCG `RedisRateLimiter` 100 rps /
   burst 200, keyed user-principal else client IP).

CORS: gateway-level `CorsWebFilter` with required explicit origin patterns
(`CORS_ALLOWED_ORIGIN_PATTERNS` must be set; `*` + credentials fails startup via
`GatewaySecurityPostureValidator.java:44-55`). allowCredentials=true, exposed rate-limit
headers (`SecurityConfig.java:22-29,56-68`). **No other security headers (HSTS,
X-Content-Type-Options, X-Frame-Options, CSP) are configured anywhere** (grep across
gateway + utils: 0 hits).

## 3. JWT / OIDC trust flow

```
Browser (access_token, azp=ecommerce-client)
  │  TLS
  ▼
nginx ingress (X-Forwarded-For added)                     k8s/ingress.yaml
  ▼
gateway-service
  │  ReactiveJwtDecoder = LazyReactiveJwtDecoder          config/LazyReactiveJwtDecoder.java
  │    → NimbusReactiveJwtDecoder.withIssuerLocation(issuer)   (OIDC discovery + JWKS, cached)
  │  validators: issuer == JWT_ISSUER_URI, exp/nbf,
  │              aud only if gateway.security.expected-audiences set (NOT set by default),
  │              azp ∈ gateway.security.expected-client-ids (default ecommerce-client)
  │                                              config/GatewayJwtSecurityConfig.java:45-69
  │  edge gates: IP allowlist, rate limits, backoffice ADMIN role gate
  ▼  (original Authorization header forwarded unchanged)
service (servlet, common-security)
  │  JwtDecoder = LazyJwtDecoder                          common-security/config/LazyJwtDecoder.java
  │    → NimbusJwtDecoder.withIssuerLocation(shop.security.issuer-uri) (prewarm thread)
  │  validators: issuer, exp/nbf,
  │              azp ∈ per-service allowlist injected by AudienceBoundaryEnvironmentCustomiser
  │              (shop.security.audience.per-service-client-ids, application-shared.yml:96-137),
  │              aud only if configured (default empty)
  │                                              common-security/config/BaseSecurityConfig.java:85-107
  │  roles: JwtRolesConverter maps realm_access.roles → ROLE_X + X authorities
  │                                              common-security/jwt/JwtRolesConverter.java:33-48
  ▼
controller @PreAuthorize (hasRole('ADMIN') / hasRole('SERVICE') / business rules)
```

- **No header-based trust**: no `X-User-*` / `X-Roles` / `X-Email` header is read or written
  anywhere (grep over the whole repo: 0 hits in first-party source). Identity always comes
  from re-validated JWT claims; `sub` is the user id.
- **Every service validates the token independently** — services are not defenseless if the
  gateway is bypassed in-cluster (authz still applies; gateway-only losses are rate limits,
  the ADMIN edge gate and the IP allowlist).
- **Service-to-service**: `KeycloakServiceTokenProvider` client_credentials grant
  (`common-keycloak/client/KeycloakServiceTokenProvider.java:83-169`) with in-memory cache,
  refresh lock, expiry skew 30 s, failure cooldown 2 s, bounded backoff, pooled client with
  explicit timeouts. Callers attach `Authorization: Bearer <service token>` explicitly
  (e.g. `order-service/.../client/InventoryServiceClient.java:44`). The callee's azp
  allowlist (e.g. `order-service → inventory-service` allowed in
  `application-shared.yml:106-109`) is the actual inter-service authorization, plus
  `@PreAuthorize hasRole('SERVICE')` on internal controllers.
- **JWKS/rotation**: standard Nimbus caching from issuer location; no custom cache tuning.
  NEEDS_RUNTIME_VERIFICATION: Keycloak downtime at gateway cold start is tolerated (lazy
  decoder builds on first request; servlet variant prewarms in a daemon thread and retries
  synchronously on first decode).
- `KeycloakTokenClient` (password grant / auth-code / refresh, used by auth-service) and
  `KeycloakAdminClient` (admin REST with service-account token, single-flight fetch) live in
  `common-keycloak`. The master-realm password grant is explicitly refused — only a
  realm service-account secret is accepted (`KeycloakAdminClient.java:403-421`).

## 4. Asynchronous topology (from `KafkaTopicContract.java:50-82` — the contract the code enforces)

| Topic | Producer | Mode | In-fleet consumers |
|---|---|---|---|
| shop.order.lifecycle.v1 | order-service | CONSUMED | notification-, shipping-service |
| shop.payment.lifecycle.v1 | payment-service | CONSUMED | order-service |
| shop.product.lifecycle.v1 | product-service | CONSUMED | search-service |
| shop.rating.lifecycle.v1 | rating-service | CONSUMED | product-service |
| shop.shipping.lifecycle.v1 | shipping-service | CONSUMED | order-service |
| media.lifecycle.v1 | media-service | CONSUMED | product-service |
| shop.inventory.events.v1 | inventory-service | FIRE_AND_FORGET | (audit/analytics only) |
| shop.promotion.lifecycle.v1 | promotion-service | FIRE_AND_FORGET | (audit only) |
| shop.category.lifecycle.v1 | product-service | FIRE_AND_FORGET | (audit only) |
| shop.brand.lifecycle.v1 | product-service | FIRE_AND_FORGET | (audit only) |

Every producer uses the DB **outbox** pattern (per-service relay, e.g.
`order-service/.../OrderOutboxRelay.java`): claim (SENDING + lease) → batched publish with
per-record outcome → per-key ordering guard (`OutboxBatchOrdering`) → SENT/FAILED with retry
cap → stale-lease reclamation. At-least-once semantics; consumer idempotency is expected
(duplicate-request error codes exist: `ORD-4010`, `PAY-5003`, `PRO-7012`).

Synchronous cross-service calls (order-service): product, inventory (reservation),
tax, promotion, payment — `order-service/.../config/RestClientConfig.java` with explicit
per-client timeouts, pooled HttpClient5, `X-Correlation-Id` propagation; checkout inventory
reservation is synchronous + compensatable (OrderCreateSaga / OrderCommitCoordinator exist in
order-service; deep audit of saga semantics is out of this run's module scope).

## 5. Observability / cross-cutting

- Trace: OpenTelemetry via `spring-boot-starter-opentelemetry` + OTLP exporter
  (`TracingAutoConfiguration.java:104-127`); W3C `traceparent` injected into every
  RestClient call (`TraceparentInterceptor` + BeanPostProcessor on `RestClient.Builder`) and
  into Kafka records (`KafkaMessagePublisher.traceparentRecord`); MDC bridge
  (`MdcOtelCurrentTraceContext`). Known gap on order-service sync clients: ARCH-21.
- Correlation: `CorrelationIdFilter` accepts caller `X-Correlation-Id`/`X-Request-Id` only if
  they match `[A-Za-z0-9_-]{1,64}` (log-injection safe), else generates; echoes back.
- Audit: `@Audited` → `AuditAspect` → bounded async writer (queue 1000, drop + count on
  overflow) → stdout `AUDIT` logger or `AUDIT_LOG_PATH` file.
- Rate limiting: Redis Lua token buckets (atomic scripts, hash-tagged keys for cluster
  co-location); details and issues in findings ARCH-1/ARCH-3/ARCH-10/ARCH-18.
