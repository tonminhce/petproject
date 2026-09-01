# Architecture

> Companion to [`ROADMAP.md`](./ROADMAP.md). All diagrams reference the
> source-of-truth repo
> [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices).
> See [`docs/RATE-LIMIT.md`](./RATE-LIMIT.md) for the two-layer rate-limit design.

## 1. Component map

```
                        ┌────────────────────────────────────────┐
                        │            Browser / Mobile            │
                        └────────────────────┬───────────────────┘
                                             │  HTTPS (PKCE w/ Keycloak)
                                             ▼
         ┌──────────────────────────────────────────────────────────────┐
         │  Spring Cloud Gateway :8080                                  │
         │  ──────────────────────────────────────────────────────────  │
         │  Routes  /api/v1/* → forward full path (no rewrite)          │
         │  Filters  • Spring Security OAuth2 Resource Server           │
         │           • Correlation-Id (X-Correlation-Id, MDC)           │
         │           • Global rate-limit (Redis, system bucket,         │
         │             HIGHEST_PRECEDENCE — flash-sale guard)           │
         │           • Per-route rate-limit (Redis, user|ip × route)    │
         │             — see docs/RATE-LIMIT.md                         │
         │           • Circuit breaker (Resilience4j, future)           │
         │           • Logging filter (request/response)                │
         │  Health  :8080/actuator/health (liveness + readiness)        │
         └──────┬───────┬───────┬───────┬─────────┬──────────┬───────────┬───────┘
                │       │       │       │         │          │           │
         ┌──────▼─┐ ┌───▼───┐ ┌─▼────┐ ┌▼──────┐ ┌▼───────┐ ┌▼────────┐ ┌▼────────┐
         │ auth   │ │product│ │order │ │payment│ │shipping│ │inventory│ │favourite│
         │ :8088  │ │ :8086 │ │ :8084│ │ :8085 │ │ :8087  │ │  :8082  │ │  :8081  │
         └────────┘ └───────┘ └──────┘ └───────┘ └────────┘ └─────────┘ └─────────┘
               │       │       │       │       │       │       │
        ┌──────▼─┐ ┌───▼───┐ ┌─▼────┐ ┌▼─────┐ ┌▼─────┐ ┌▼─────┐ ┌▼──────┐
        │ rating │ │media  │ │ notif│ │search│ │  tax │ │promo │ │        │
        │ :8089  │ │ :8083 │ │ :8090│ │ :8094│ │ :8091│ │ :8093│ │        │
        └────────┘ └───────┘ └──────┘ └──────┘ └──────┘ └──────┘ └────────┘
               │       │       │       │       │       │       │
        ┌──────▼───────▼───────▼───────▼───────▼───────▼───────▼────────┐
        │                       Messaging Bus                         │
        │            Apache Kafka 3.9 (KRaft mode, no ZK)             │
        │  Topics  order.created.v1 · order.updated.v1 ·              │
        │          payment.success.v1 · payment.failed.v1 ·            │
        │          shop.product.lifecycle.v1 · notification.send.v1     │
        └──────┬────────────────┬───────────────────┬─────────────────┘
               │                │                   │
        ┌──────▼────┐ ┌─────────▼─────────┐ ┌──────▼─────────┐
        │ Postgres  │ │      Redis 7      │ │ Elasticsearch 8 │
        │   :5432   │ │      :6379        │ │     :9200       │
        │  12 DBs   │ │  (sessions, JWT   │ │   (products,    │
        │  + keycloak│ │  blacklist, rate)│ │    ratings)     │
        └───────────┘ └───────────────────┘ └────────────────┘
                                                              │
        ┌─────────────────────────────────────────┐    ┌───────▼──────┐
        │  Keycloak 26   realm: `ecommerce`       │    │  RustFS (S3) │
        │  :8080 (admin : admin/admin)           │    │   :9000      │
        │  Users, Roles, Clients, Realm roles    │    │  media/      │
        └─────────────────────────────────────────┘    └──────────────┘
```

## 2. Request flow — login

> ⚠️ **Current workspace status (2026-08-25)**: auth-service implements
> **ROPC** (`POST /api/v1/auth/login` via `KeycloakTokenClient.login(...)`),
> NOT the redirect-based SSO below. This diagram is the **target SSO flow**
> from the reference and the deferred design; see
> [`SERVICE-CATALOG.md §1.2`](./SERVICE-CATALOG.md) for the implemented
> endpoints.

```
Browser           Gateway :8080          auth-service :8088       Keycloak :8080       Postgres
   │                   │                       │                       │                  │
   │ GET /auth/login    │                       │                       │                  │
   ├───────────────────►│                       │                       │                  │
   │                   │ strip prefix           │                       │                  │
   │                   │ JwtAuthenticationFilter│                       │                  │
   │                   │ (PUBLIC, no token)     │                       │                  │
   │                   ├──────────────────────►│                       │                  │
   │                   │                       │ AuthController.ssoLogin│                  │
   │                   │                       │ generate state → SsoSessionStore (Redis)│
   │                   │                       │ 302 → /realms/ecommerce/protocol/openid-connect/auth│
   │ ◄─────────────────┤                       │                       │                  │
   │  302 Found         │                       │                       │                  │
   │  Location: keycloak.ecommerce.local/realms/ecommerce/...          │                  │
   │                                                                                   │
   │ GET /realms/ecommerce/protocol/openid-connect/auth?...                          │
   ├───────────────────────────────────────────────────────────────────────────────►    │
   │  200 HTML (Keycloak login page)                                                   │
   │                                                                                  │
   │ POST credentials, then 302 → /auth/callback?code=…&state=…                       │
   │ ◄──────────────────────────────────────────────────────────────────────────────  │
   │                                                                                  │
   │ GET /auth/callback?code=…&state=… (to Gateway)                                    │
   ├───────────────────►│                       │                       │             │
   │                   ├──────────────────────►│ AuthController.ssoCallback           │
   │                   │                       │ 1. consume state → frontend redirect  │
   │                   │                       │ 2. POST /protocol/openid-connect/token│
   │                   │                       ├──────────────────────►│             │
   │                   │                       │  KeycloakTokenResponse (access+refresh)
   │                   │                       │ ◄─────────────────────┤             │
   │                   │                       │ 3. store ticket (Redis TTL 60s)     │
   │                   │                       │ 4. 302 → frontend /auth/callback?ticket=…
   │ ◄─────────────────┤                       │                                       │
   │  302 ecommerce.local/auth/callback?ticket=…                                        │
   │                                                                                   │
   │ GET /auth/session?ticket=… (to Gateway)                                           │
   ├───────────────────►│                       │                                       │
   │                   ├──────────────────────►│ SsoSessionStore.consumeTokens          │
   │                   │                       │ → KeycloakTokenResponse               │
   │                   │                       │ 201 { access_token, refresh_token, …}  │
   │ ◄─────────────────┤                       │                                       │
   │  200 JSON tokens                                                                 │
   │                                                                                   │
   │ (frontend stores access_token in memory, refresh_token in httpOnly cookie)        │
```

Reference for the SSO logic:
[AuthController.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/controller/AuthController.java).

## 3. Domain flows

### 3.1 Place-order happy path

```
cart-service        order-service      product-service    inventory-service   payment-service     Kafka      notification-service
    │                   │                   │                   │                  │               │               │
    │ POST /carts        │                   │                   │                  │               │               │
    │ (validate items)   │                   │                   │                  │               │               │
    │                   │                   │                   │                  │               │               │
    │ POST /orders       │                   │                   │                  │               │               │
    ├──────────────────►│                   │                   │                  │               │               │
    │                   │ reserve stock     │                   │                  │               │               │
    │                   ├──────────────────────────────────────►│                  │               │               │
    │                   │ ◄─── reserved                       │                  │               │               │
    │                   │ publish OrderCreated                │                  │               │               │
    │                   ├──────────────────────────────────────────────────────────────────────────►│               │
    │                   │                                              │ consume OrderCreated        │               │
    │                   │                                              ├──────────────────────────────►│               │
    │                   │                                              │ Stripe.checkout()            │               │
    │                   │                                              │ publish PaymentSucceeded      │               │
    │                   │ ◄─────────────────────────────────────────────────────────────────────────┤               │
    │                   │ confirm stock deduction      │              │                              │               │
    │                   ├─────────────────────────────►│              │                              │               │
    │                   │ publish OrderConfirmed                       │                              │               │
    │                   ├──────────────────────────────────────────────────────────────────────────►│               │
    │ 201 OrderDto      │                                              │ consume OrderConfirmed       │               │
    │ ◄─────────────────┤                                              │ send order.email             │               │
    │                   │                                              │                              │               │
```

Reference flow for the orchestration logic:
[order-service tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/order-service/src/main/java/com/ecommerce/orderservice).

### 3.2 Product indexing path (CDC-lite)

```
product-service                  Kafka                search-service                 Elasticsearch
      │                          │                         │                              │
      │ POST/PUT /products       │                         │                              │
      │ (save to PG)             │                         │                              │
      │                          │                         │                              │
      │ publish ProductIndexed   │                         │                              │
      ├─────────────────────────►│                         │                              │
      │                          │ consume ProductIndexed  │                              │
      │                          ├────────────────────────►│                              │
      │                          │                         │ upsert ProductDoc            │
      │                          │                         ├─────────────────────────────►│
      │                          │                         │                              │
```

This is the simplest "index on write" pattern. For high-volume catalogs, switch
to Debezium + Kafka Connect — see [ROADMAP §8 risk R6](./ROADMAP.md).

## 4. Module / package layout (target state)

```
shop-microservices/                       # parent aggregator (pom)
├── utils/
│   ├── common-core/        ──┐
│   ├── common-spring/       │  every service depends on common-spring
│   ├── common-security/    ─┤  which transitively pulls in all 7
│   ├── common-logging/     ─┤
│   ├── common-keycloak/    ─┤
│   ├── common-kafka/       ─┤
│   └── common-storage/     ─┘
│
├── gateway-service/                    # Spring Cloud Gateway
│   ├── pom.xml
│   └── src/main/{java,resources}/...
│
├── auth-service/                       # 8088
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/shop/authservice/
│       │   ├── AuthServiceApplication.java
│       │   ├── config/      SecurityConfig.java, SsoProperties.java, KeycloakProps.java
│       │   ├── controller/  AuthController.java, UserController.java, RoleController.java
│       │   ├── dto/         request/, response/
│       │   ├── entity/      User.java, Role.java, RoleName.java, AbstractMappedEntity.java
│       │   ├── exception/   *.java
│       │   ├── mapper/      UserMapper.java, RoleMapper.java (ModelMapper @Component)
│       │   ├── repository/  UserRepository.java, RoleRepository.java
│       │   └── service/     UserService.java + UserServiceImpl.java, RoleService.java + Impl
│       └── resources/
│           ├── application.yml
│           ├── logback-spring.xml
│           ├── db/changelog/db.changelog-master.yaml
│           └── db/changelog/ddl/001-init.yaml
│
├── product-service/                    # 8086   (same shape)
├── order-service/                      # 8084
├── payment-service/                    # 8085   + event/, http/ (Stripe client)
├── shipping-service/                   # 8087
├── inventory-service/                  # 8082
├── favourite-service/                  # 8081   smallest
├── rating-service/                     # 8089   split: storefront + backoffice
├── media-service/                      # 8083   + storage/
├── tax-service/                        # 8091   tax-classes + tax-rates
├── promotion-service/                  # 8093
├── search-service/                     # 8094   ES indexer + query
└── notification-service/               # 8090   + event/, email/
```

## 5. Data stores

| Store | Used by | Database / index | Notes |
|-------|---------|------------------|-------|
| Postgres 16 | every backend service | one DB per service (`authservice`, `productservice`, `orderservice`, `paymentservice`, `shippingservice`, `inventoryservice`, `favouriteservice`, `ratingservice`, `mediaservice`, `taxservice`, `promotionservice`, `notificationservice`) + `keycloak` | Created by `docker/postgres/init/create-all-databases.sql` |
| Redis 7 | gateway (rate limit — global system bucket + per-route bucket, see [`docs/RATE-LIMIT.md`](./RATE-LIMIT.md)), auth (SSO session store), order (cart cache) | DB 0–15 logical | `spring.session.store-type=redis` (when wired); key schema `request_rate_limiter.{routeId}.{key}.tokens` |
| Kafka 3.9 | order → payment/notification/search; product → search | topics above | KRaft mode (no Zookeeper), one broker is fine for dev |
| Elasticsearch 8.15 | search-service (products, ratings), optional logs | `products`, `ratings`, `logs-*` | Single-node, security off in dev |
| Keycloak 26 | all services (JWT issuer) | `keycloak` DB on the same Postgres | Realm `ecommerce`, clients `ecommerce-client`, `swagger-ui`, service clients `order-service`/`rating-service`/`search-service` (confidential, client_credentials only) |
| RustFS | media-service (S3 SDK v2) | bucket `ecommerce-media` | S3-compatible, console on :9001 |

## 6. Cross-cutting concerns

| Concern | Implementation | Library |
|---------|----------------|---------|
| API envelope | `ApiResponse<T>` (success+code+message+data+errors+path+traceId+timestamp) | `common-core/viewmodel/ApiResponse.java` |
| Path constants | `ApiPaths.AUTH`, `ApiPaths.PRODUCTS`, … (no string literals) | `common-core/constants/ApiPaths.java` |
| Domain errors | `BusinessException.of(ErrorCode.X)` | `common-core/exception/BusinessException.java` |
| Global handler | `@RestControllerAdvice` translating `BusinessException` → `ApiResponse.error` | `common-spring/web/exception/ApiExceptionHandler.java` |
| Performance log | `@LogPerformance(title="…", thresholdMs=50)` | `common-logging` |
| Request/response log | Servlet filter, body up to 2048 bytes | `common-spring/web/HttpLoggingFilter.java` |
| Correlation ID | `X-Correlation-Id` header → MDC (`MdcKey.TRACE_ID`) → response header | `common-spring/web` |
| i18n | ResourceBundle `messages/messages*.properties`, default locale `vi` | `common-core/i18n` |
| Validation | `jakarta.validation.constraints.*` + `@Valid` on DTOs | Spring Boot starter-validation |
| Mapping | `ModelMapper` (`@Component` mapper class + STRICT matching + skip-null) — single pattern for all services | `common-spring/mapper` |
| Auth | OAuth2 Resource Server (JWT) | `common-security` |
| Keycloak admin | `KeycloakTokenClient` (login/refresh) + `KeycloakAdminClient` (user mgmt) | `common-keycloak` |
| Kafka | `KafkaMessagePublisher`, `BaseKafkaConsumer`, JSON serdes | `common-kafka` |
| Object storage | `ObjectStorageService` (put/get/delete/presigned URL) | `common-storage` |
| Resilience | `@CircuitBreaker(name="product-service", fallbackMethod="…")` on inter-service HTTP | Resilience4j 2.4 |
| Rate limit | Two-layer Redis token bucket: global system (flash-sale guard, `Ordered.HIGHEST_PRECEDENCE`) + per-client + per-route (key = `user:<sub>` or `ip:<client>`). Default: 100/200/1 per route, 2000/4000/1 system. See [`docs/RATE-LIMIT.md`](./RATE-LIMIT.md) for full design | `spring-cloud-gateway` `RedisRateLimiter` + Lua script |
| Observability | Micrometer + Prometheus (`management.endpoints.web.exposure.include: health,info,metrics,prometheus`) | SB Actuator |

## 7. Network & ports

```
:8080   Spring Cloud Gateway      (host)
:8088   auth-service
:8086   product-service
:8084   order-service
:8085   payment-service
:8087   shipping-service
:8082   inventory-service
:8081   favourite-service
:8089   rating-service
:8083   media-service
:8091   tax-service
:8093   promotion-service
:8094   search-service
:8090   notification-service

:5432   postgres          (host)
:6379   redis             (host)
:9092   kafka             (host)
:9200   elasticsearch     (host)
:8080   keycloak          (host — collides with gateway locally; expose keycloak on 8180 in workspace)
:9000   rustfs (S3 API)   (host)
:9001   rustfs (console)   (host)
```

> Note — in the workspace the gateway listens on `:8080` and Keycloak also on
> `:8080` is fine **inside Docker**, but locally you must either run Keycloak
> on a different port or use `k3d`. The reference k3d manifest uses
> `keycloak.ecommerce.local` so the ports do not collide.

## 8. Sequence — inter-service request (synchronous)

```
order-service                          product-service              inventory-service
      │                                       │                            │
      │ GET /api/products/{id}                │                            │
      │ (Resilience4j @CircuitBreaker)        │                            │
      │──────────────────────────────────────►│                            │
      │                                       │ ProductDto                 │
      │ ◄─────────────────────────────────────┤                            │
      │                                                                     │
      │ GET /api/inventory/{productId}                                      │
      │ (Resilience4j @Retry + @CircuitBreaker)                             │
      ├───────────────────────────────────────────────────────────────────►│
      │                                                                     │
      │ InventoryDto                                                        │
      │ ◄──────────────────────────────────────────────────────────────────┤
      │                                                                     │
```

The `@CircuitBreaker` annotation is enabled by `common-spring` Resilience4j
auto-config. The `fallbackMethod` should throw `BusinessException.of(ErrorCode.SERVICE_UNAVAILABLE)`.

## 9. Deployment topology (target)

```
┌─────────────────────────────────────────────────────────────────────────┐
│ k3d cluster  (1 control-plane + 2 agents, port-mapped)                  │
│                                                                         │
│   ingress-nginx-controller     (namespace: ingress-nginx)              │
│   argocd                       (namespace: argocd)                      │
│   ecommerce                                                         │
│   ├── postgres           (StatefulSet, PVC 10Gi)                       │
│   ├── redis              (Deployment, PVC 1Gi)                          │
│   ├── kafka              (StatefulSet, PVC 20Gi)                       │
│   ├── elasticsearch      (StatefulSet, PVC 30Gi)                       │
│   ├── rustfs             (StatefulSet, PVC 30Gi)                       │
│   ├── keycloak           (Deployment, depends_on postgres)              │
│   ├── auth-service       (Deployment, replicas 2, HPA)                 │
│   ├── product-service    (Deployment, replicas 2, HPA)                 │
│   ├── order-service      (Deployment, replicas 3, HPA)                 │
│   ├── payment-service    (Deployment, replicas 2, HPA)                 │
│   ├── shipping-service   (Deployment, replicas 2, HPA)                 │
│   ├── inventory-service  (Deployment, replicas 2, HPA)                 │
│   ├── favourite-service  (Deployment, replicas 1)                      │
│   ├── rating-service     (Deployment, replicas 2, HPA)                 │
│   ├── media-service      (Deployment, replicas 2, HPA)                 │
│   ├── tax-service        (Deployment, replicas 1)                      │
│   ├── promotion-service  (Deployment, replicas 1)                      │
│   ├── search-service     (Deployment, replicas 2, HPA)                 │
│   ├── notification-service (Deployment, replicas 1)                    │
│   └── gateway-service    (Deployment, replicas 2, HPA)                 │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

Reference manifests — [k8s/backend/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/backend),
[k8s/infra/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/infra),
[k8s/ingress/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/ingress).

---

See also — [`SERVICE-CATALOG.md`](./SERVICE-CATALOG.md) · [`COMMON-LIB-REFERENCE.md`](./COMMON-LIB-REFERENCE.md)
