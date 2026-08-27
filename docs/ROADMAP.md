# E-Commerce Microservices — Roadmap

> **Purpose** — Detailed execution plan to take the workspace from "infra-ready
> scaffolding" to "production-grade platform", referencing the source-of-truth
> repo [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices).
>
> **Workspace** — `/Users/tonminh-mac/IdeaProjects/untitled5`
> **Stack** — Spring Boot 4.1.1 · Java 25 (Temurin) · Maven 3.9+ · Spring Cloud
> Gateway (workspace swap) vs. Apache APISIX (reference).
>
> **Companion docs** —
> [ARCHITECTURE.md](./ARCHITECTURE.md) ·
> [RATE-LIMIT.md](./RATE-LIMIT.md) ·
> [SERVICE-CATALOG.md](./SERVICE-CATALOG.md) ·
> [COMMON-LIB-REFERENCE.md](./COMMON-LIB-REFERENCE.md)

---

## 1. Current State (Where We Are)

### 1.1 What's already done ✅

| Area | Artifact | Reference |
|------|----------|-----------|
| Parent POM | `pom.xml` — SB 4.1.1, Java 25 toolchain, Jib, flatten-plugin, revision | [ref: pom.xml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/pom.xml) |
| Common libs (7) | `utils/{common-core, common-security, common-logging, common-keycloak, common-kafka, common-spring, common-storage}` | [ref: common-lib](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib) |
| Docker infra | `docker-compose.yml` — Postgres 16, Redis 7, Kafka 3.9, Elasticsearch 8.15, Keycloak 26, RustFS | [ref: docker-compose.yml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/docker-compose.yml) |
| Lifecycle scripts | `start-docker.sh`, `stop-docker.sh` | workspace only |
| Gateway | `gateway-service/` — Spring Cloud Gateway, JWT validation, route table, 15 routes (13 services + users/roles via auth) | workspace decision (replaces APISIX) |
| Gateway rate limit | Two-layer Redis token bucket at gateway: (1) global system bucket, default `2000 req/s` · burst `4000`, ordered `HIGHEST_PRECEDENCE` for flash-sale guard; (2) per-client + per-route bucket, default `100 req/s` · burst `200`, key = `user:<sub>` or `ip:<client>`. Implemented via `spring-cloud-gateway` `RedisRateLimiter` Lua script. 17 unit + slice tests pass; smoke verified against real Redis. See [`RATE-LIMIT.md`](./RATE-LIMIT.md) | workspace decision (replaces APISIX rate-limit) |
| auth-service (core) | Controllers (auth/users), services, entities, repositories, DTOs, ModelMapper, Liquibase (2 changesets + seed), soft-delete | [ref: auth-service](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/auth-service) |
| common-keycloak | `KeycloakAuthClient` refactored → `KeycloakTokenClient` + `KeycloakAdminClient` (RestClient, no admin SDK) | workspace decision (modern) |
| Docker images | 14 built via Jib (gateway + 13 services) | workspace only |
| Live verification | gateway health 200, protected 401, JWT validation works | verified manually |

### 1.2 What's TODO ❌

| Area | Gap | Reference |
|------|-----|-----------|
| Backend code (12) | product/order/payment/shipping/inventory/favourite/rating/media/tax/promotion/search/notification = `Application.java` only | [per-service tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/order-service) |
| auth-service infra | `application.yml` ✅ (datasource/JPA/Liquibase/security), `RoleController` ✅, 37 unit + slice tests ✅; chỉ còn thiếu SmokeIT (Testcontainers) | [ref: auth-service application.yml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/application.yml) |
| Liquibase (12) | 12 services have no master changelogs/DDL/seed (auth-service ✅ has master + 2 changesets) | [ref: db/changelog](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/auth-service/src/main/resources/db) |
| API docs | OpenAPI/Swagger not exercised (common-spring has springdoc auto-config) | [ref: springdoc](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/application.yml) |
| Tests | 0 unit, 0 integration tests (only 1 context-load in common-spring) | workspace gap |
| Frontend | Missing (Next.js 16 / React 19) | [ref: frontend](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/frontend) |
| K8s manifests | Missing | [ref: k8s/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s) |
| CI/CD | Missing (GH Actions, SonarCloud, GHCR, ArgoCD) | [ref: .github/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/.github) |
| Observability dashboards | Micrometer + Prometheus exposed, no Grafana | workspace gap |

---

## 2. Architecture Overview

```
                    ┌──────────────────────────────────────────────────┐
                    │              Browser / Mobile client             │
                    └────────────────────┬─────────────────────────────┘
                                         │  HTTPS  (CORS pre-flight)
                                         ▼
        ┌────────────────────────────────────────────────────────────────┐
        │   Spring Cloud Gateway  :8080  (workspace)                    │
        │   ──────────────────────────────────────────────────────────── │
        │   • JWT validation (OAuth2 Resource Server → Keycloak JWK)     │
        │   • 15 routes  /api/v1/{auth,users,roles,products,orders,…}/*  │
        │   • Correlation-ID propagation (X-Correlation-Id)              │
        │   • Rate-limit (Redis bucket) · CORS · Resilience4j filters    │
        └────────────────┬───────────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          13 BACKEND MICROSERVICES                                  │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐         │
│ │ auth :8088 │ │product:8086│ │ order :8084│ │payment:8085│ │shipping:8087│        │
│ └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘         │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐         │
│ │favourite:81│ │inventory:82│ │ rating :89 │ │  media :83 │ │  tax   :91 │         │
│ └────────────┘ └────────────┘ └────────────┘ └────────────┘ └────────────┘         │
│ ┌────────────┐ ┌────────────┐                                                       │
│ │notif  :90  │ │search  :94 │                                                       │
│ └────────────┘ └────────────┘                                                       │
└──┬────────┬────────────┬──────────────┬──────────────────┬──────────────────────────┘
   │        │            │              │                  │
   ▼        ▼            ▼              ▼                  ▼
┌──────┐ ┌──────┐ ┌──────────┐ ┌────────────────┐ ┌─────────────────┐
│PG 16 │ │Redis │ │Kafka 3.9 │ │Elasticsearch 8 │ │  RustFS (S3)    │
│:5432 │ │ :6379│ │  :9092   │ │     :9200      │ │  :9000/:9001    │
└──────┘ └──────┘ └──────────┘ └────────────────┘ └─────────────────┘
                              ▲
                              │
                    ┌────────────────────┐
                    │   Keycloak 26      │
                    │   :8080 (realm:    │
                    │   ecommerce)       │
                    └────────────────────┘
```

**Key differences vs. reference repo**

| Layer | Reference repo | Workspace |
|-------|---------------|-----------|
| Gateway | Apache APISIX 3.9 (OpenID-Connect plugin, Lua routes) | Spring Cloud Gateway (Java, YAML routes) |
| Build base image | `eclipse-temurin:21-jre-alpine` | `eclipse-temurin:25-jre-alpine` |
| Spring/Java | SB 3.3.5 / Java 21 | SB 4.1.1 / Java 25 |
| Toolchain | JDK 21 | JDK 25 via `~/.m2/toolchains.xml` |
| Module path | `com.ecommerce.*` | `com.shop.*` |
| GroupId | `com.ecommerce.microservices` | `com.shop.microservices` |

---

## 3. Service Implementation Plan

The reference repo uses `com.ecommerce.*`; the workspace will keep `com.shop.*`
(every `import` below must be renamed on copy-paste).

### 3.1 Master table

| Service | Port | Domain entities | API endpoints | DB schema | Complexity |
|---------|------|-----------------|---------------|-----------|------------|
| auth-service | 8088 | `User`, `Role`, `RoleName` | 9 endpoints under `/api/v1/{auth,users,roles}` | `users`, `roles`, `user_role` | **L** |
| product-service | 8086 | `Product`, `Category`, `Brand`, `OutboxEvent` (workspace) | 6 `/api/v1/products` + 6 `/api/v1/categories` + 5 `/api/v1/brands` (+ `/slug/{slug}` + `/categories/tree`) | `products`, `categories`, `brands`, `outbox_events` | **L** (cache + outbox + Kafka) |
| order-service | 8084 | `Order`, `Cart` | 7 endpoints `/api/orders` + 5 `/api/carts` | `orders`, `carts` | **L** (Kafka + state mgmt) |
| payment-service | 8085 | `Payment` (inferred) | CRUD `/api/v1/payments` | `payments` | **L** (Stripe integration) |
| inventory-service | 8082 | `Inventory` | CRUD `/api/v1/inventory` | `inventory` | **M** |
| shipping-service | 8087 | `Shipping` | CRUD `/api/v1/shippings` | `shippings` | **M** |
| favourite-service | 8081 | `Favourite` | CRUD `/api/v1/favourites` | `favourites` | **S** |
| rating-service | 8089 | `Rating` | split: `/api/v1/storefront/ratings` + `/api/v1/backoffice/ratings` | `ratings` | **M** |
| media-service | 8083 | `Media` (metadata) | upload/download `/api/v1/medias` + presigned URLs | `medias` (blob in RustFS) | **M** |
| tax-service | 8091 | `TaxClass`, `TaxRate` | `/api/v1/backoffice/tax-classes` + `/tax-rates` | `tax_classes`, `tax_rates` | **M** |
| promotion-service | 8093 | `Promotion` (coupons) | CRUD `/api/v1/backoffice/promotions` | `promotions` | **M** |
| search-service | 8094 | ES document `ProductDoc` | `/api/v1/storefront/search` + Kafka indexer | — (ES only) | **M** (ES + Kafka) |
| notification-service | 8090 | `Notification`, `Email` | `/api/v1/notifications` + Kafka consumer + SMTP | `notifications`, `emails` | **L** (Kafka + mail) |

Complexity legend — **S** = 1–2 days · **M** = 3–5 days · **L** = 1–2 weeks ·
**XL** = 2+ weeks.

### 3.2 Per-service spec — auth-service (template)

This is the reference implementation we will clone first. See
[ARCHITECTURE.md §3.1](./ARCHITECTURE.md) and [SERVICE-CATALOG.md §1](./SERVICE-CATALOG.md).

| Property | Value |
|----------|-------|
| Package | `com.shop.authservice` |
| Base path | `/api/v1/auth`, `/api/v1/users`, `/api/v1/roles` |
| Keycloak integration | `KeycloakTokenClient` + `KeycloakAdminClient` (in `common-keycloak`) |
| External deps | Keycloak (token verification) |
| Ref controller | [AuthController.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/controller/AuthController.java) (538 LOC incl. SSO flow) |
| Ref entity | [User.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/entity/User.java) |
| Ref YAML | [application.yml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/application.yml) |
| Ref DB | [db.changelog-master.yaml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/db/changelog/db.changelog-master.yaml) + `db/changelog/ddl/*.sql` |

The other 12 services follow the same skeleton — see §3.3 for the cross-cutting
concerns and [SERVICE-CATALOG.md](./SERVICE-CATALOG.md) for the per-service
endpoint catalogue.

### 3.3 Cross-cutting concerns for every service

Every backend service MUST adopt these conventions (all taken from
`common-lib/common-spring` auto-configs):

1. **`ApiResponse<T>` envelope** — every controller returns
   `ApiResponse.ok(...)` / `ApiResponse.message(...)`. Source:
   [`ApiResponse.java`](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/viewmodel/ApiResponse.java).
2. **`ApiPaths` constants** — `@RequestMapping(ApiPaths.X)` instead of
   string literals. Source:
   [`ApiPaths.java`](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/constants/ApiPaths.java).
3. **`BusinessException` + `ErrorCode`** — domain errors throw
   `BusinessException.of(ErrorCode.X)`. The auto-configured
   `GlobalExceptionHandler` translates to `ApiResponse.error(...)`.
4. **`@LogPerformance(title = "...")` AOP** — annotate any service method that
   should be timed (default threshold 50 ms). Source: `common-logging`.
5. **`ModelMapper` mappers** — `Entity ↔ DTO` qua `xxxMapper.java` class (`@Component` inject `ModelMapper` bean từ `common-spring/ModelMapperAutoConfiguration`). Single pattern cho toàn fleet — xem [`COMMON-LIB-REFERENCE §3.5`](#common-library-reference) (MapStruct đã bị reject vì làm pattern không nhất quán).
6. **Virtual threads** — `spring.threads.virtual.enabled: true` in YAML (already
   on by default in SB 4.x; keep it).
7. **JWT auth** — `oauth2ResourceServer.jwt(...)` chain in `SecurityConfig`,
   with `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/**` whitelisted.
8. **Path convention = `/api/v1/*`** — every controller serves under
   `/api/v1/...` using the `ApiPaths` constants. The gateway forwards the FULL
   path (`/api/v1/{resource}/**` → service, **no StripPrefix/rewrite**), so a
   service MUST map the same prefix its route declares. This deliberately
   deviates from the reference repo (e.g. reference product-service serves
   `/api/products`; workspace will serve `/api/v1/products`).

---

## 4. Phased Implementation Plan

The phases are ordered by **business risk × dependencies**. Total calendar:
~14–18 weeks for a 2-engineer team (see §6).

### Phase 6 — Service Scaffolding Completion · 1–2 wk

**Goal** every service has a runnable, validated skeleton.

| Task | Reference file |
|------|----------------|
| Copy `application.yml` template (datasource + JPA + Liquibase + security + observability) | [auth-service/application.yml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/application.yml) |
| Add `SecurityConfig.java` per service (whitelist `/v3/api-docs`, `/actuator`, login endpoints) | [SecurityConfig.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/config/SecurityConfig.java) |
| Add `db/changelog/db.changelog-master.yaml` + `db/changelog/ddl/001-init.yaml` per service | [db.changelog-master.yaml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/db/changelog/db.changelog-master.yaml) |
| Add per-service `SsoProperties` / `SecurityFilterChain` customization where needed | — |
| Wire `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant: true` per service | [YAML block](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/resources/application.yml#L66-L78) |
| Update each `Application.java` to add `@EnableJpaAuditing`, `@EnableConfigurationProperties`, package scan for `com.shop.*` | — |
| Verify `docker compose up -d postgres keycloak && mvn -pl <svc> spring-boot:run` boots cleanly | manual |

**Definition of done** every service starts, registers `/actuator/health` = UP,
serves `/v3/api-docs` JSON, validates a JWT minted by Keycloak.

---

### Phase 7 — Core Services Implementation · 3–4 wk

Implement in this exact order (each one unblocks the next):

| Wk | Service | Why this order |
|----|---------|----------------|
| 1 | **auth-service** | all other services validate JWTs issued by Keycloak via this realm |
| 1–2 | **product-service** | feeds orders, inventory, search |
| 2 | **inventory-service** | tight coupling with order lifecycle |
| 2–3 | **order-service** | orchestrator (calls product + inventory + payment + tax + promotion) |
| 3–4 | **payment-service** | consumes `OrderCreated` Kafka events, publishes `PaymentSucceeded` |
| 4 | **favourite-service**, **rating-service** | thin BFF over products |

Per service, the deliverable checklist is:

- [ ] Entities (with validation + JPA auditing fields) → see
      [`Product.java`](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/product-service/src/main/java/com/ecommerce/productservice/entity/Product.java) for the canonical `@Entity` pattern.
- [ ] Repositories (Spring Data JPA + custom queries where needed)
- [ ] DTOs in `dto/request` + `dto/response`
- [ ] ModelMapper mappers (`@Component` inject `ModelMapper`)
- [ ] `Service` interface + `ServiceImpl`
- [ ] Controllers wired through `@RequestMapping(ApiPaths.X)`
- [ ] `BusinessException.of(ErrorCode.X)` thrown on domain errors
- [ ] Liquibase DDL
- [ ] Unit tests (`@ExtendWith(MockitoExtension.class)`) for services
- [ ] REST-assured controller test (`@WebMvcTest`)

---

### Phase 8 — Integration Services · 2–3 wk

| Service | Highlight | Reference |
|---------|-----------|-----------|
| payment-service | Consumes `OrderCreated`, produces `PaymentSucceeded` | [ref tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/payment-service/src/main/java/com/ecommerce/paymentservice) |
| notification-service | Kafka `@KafkaListener` for order/payment events → SMTP via `common-logging` AOP + JavaMailSender | [ref tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/notification-service/src/main/java/com/ecommerce/notificationservice) |
| search-service | Elasticsearch indexer (Kafka consumer) + `/storefront/search` query endpoint | [ref tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/search-service/src/main/java/com/ecommerce/search) |
| media-service | Upload/download against RustFS via `common-storage` (S3 SDK v2) | [ref tree](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/media-service/src/main/java/com/ecommerce/mediaservice) |

**Kafka topics to create** (one-shot Liquibase on `infra/kafka`):

| Topic | Producer | Consumer | Partitions |
|-------|----------|----------|------------|
| `order.created.v1` | order-service | payment-service, search-service, notification-service | 6 |
| `order.updated.v1` | order-service | notification-service | 3 |
| `payment.success.v1` | payment-service | order-service, notification-service | 3 |
| `payment.failed.v1` | payment-service | order-service | 3 |
| `product.indexed.v1` | product-service | search-service | 6 |

---

### Phase 9 — API Documentation · 3–5 d

| Task | How |
|------|-----|
| Per-service OpenAPI 3 JSON | Already enabled via `common-spring` (`springdoc-openapi-starter-webmvc-ui 2.6.0`) — just expose controllers. URL: `http://<svc>:808N/v3/api-docs` |
| Swagger UI per service | `http://<svc>:808N/swagger-ui.html` (Keycloak PKCE pre-wired in YAML) |
| Aggregated docs | `springdoc-openapi-starter-webflux-ui` aggregation endpoint in gateway (gateway proxies `/v3/api-docs/<service>`) |
| Postman collection | Export from Swagger UI → import into Postman → commit to `docs/postman/ecommerce.postman_collection.json` |
| Versioning | All paths pinned to `ApiPaths.API_V1 = /api/v1`. New major = bump to `/api/v2` per service independently |

---

### Phase 10 — Testing Strategy · 2 wk (parallel)

| Layer | Tool | Target | Owner |
|-------|------|--------|-------|
| Unit | JUnit 5 + Mockito + AssertJ | 80 % line coverage on services | dev per service |
| Slice | `@WebMvcTest` + spring-security-test | every controller | dev per service |
| Integration | Testcontainers (Postgres, Kafka, ES, Keycloak, LocalStack for S3) | one happy + one sad per endpoint | shared infra team |
| Contract | Spring Cloud Contract (consumer-driven) | order ↔ payment, order ↔ notification | shared infra team |
| E2E | REST Assured + Cucumber | top 5 user journeys (signup → browse → cart → checkout → pay) | QA |
| Performance | k6 scripts (committed in `docs/k8s/perf/`) | p95 latency < 300 ms on `/api/v1/products` | QA |

Coverage target: **≥ 80 %** on `service/`, `controller/`, `mapper/`.
`dto/`, `entity/`, `config/` are excluded from JaCoCo (already configured in
reference parent POM: [pom.xml jacoco config](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/pom.xml#L335-L346)).

---

### Phase 11 — Observability · 1 wk

| Concern | Tool | Status |
|---------|------|--------|
| Metrics | Micrometer → Prometheus (already enabled via `common-spring`) | ✅ |
| Health probes | Liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness` (port 9000) | partial — needs split per k8s |
| Structured logging | Logback JSON encoder (`net.logstash.logback:logstash-logback-encoder`) + MDC `X-Correlation-Id` | TODO |
| Distributed tracing | OpenTelemetry SDK + OTLP exporter → Jaeger (or Tempo via Grafana) | TODO |
| Dashboards | Grafana dashboards JSON committed in `docs/grafana/` | TODO |
| Alerting | Prometheus alerting rules in `docs/grafana/alerts/*.yaml` | TODO |

---

### Phase 12 — CI/CD · 1 wk

Pipeline (per push, per PR, per tag):

```
┌─────────────────────────────────────────────────────────────────────┐
│  GitHub Actions                                                     │
│  ─────────────────────────────────────────────────────────────────  │
│  1. build.yml      mvn -B verify  (unit + IT + JaCoCo)             │
│  2. jib.yml         mvn -P image jib:build → ghcr.io/<org>/<svc>    │
│  3. sonar.yml       mvn sonar:sonar → SonarCloud                   │
│  4. update-argo.yml (on tag) push new image tag to argocd repo      │
│                                                                     │
│  ArgoCD then auto-syncs each Application to k3d/prod.               │
└─────────────────────────────────────────────────────────────────────┘
```

Secrets needed: `GHCR_TOKEN`, `SONAR_TOKEN`, `KEYCLOAK_ADMIN_PASSWORD`,
`POSTGRES_PASSWORD`. Reference:
[.github/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/.github)
+ [k8s/argocd/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/argocd).

---

### Phase 13 — Kubernetes Deployment · 1–2 wk

| Step | Action | Reference |
|------|--------|-----------|
| 1 | Install `k3d` + `kubectl` (via `k3d-setup.sh`) | [k3d-setup.sh](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/k3d-setup.sh) |
| 2 | Create cluster `k3d create --config k3d-config.yaml` | [k3d-config.yaml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/k3d-config.yaml) |
| 3 | Apply `k8s/namespace.yaml`, `secrets.yaml`, `configmap.yaml` | [configmap.yaml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/k8s/configmap.yaml) |
| 4 | Apply infra: Postgres, Redis, Kafka, Elasticsearch, RustFS, Keycloak (StatefulSets) | [k8s/infra/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/infra) |
| 5 | Apply NGINX Ingress Controller | [k8s/ingress/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/ingress) |
| 6 | Apply 13 backend Deployments + Services | [k8s/backend/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/backend) |
| 7 | Adapt base image `eclipse-temurin:25-jre-alpine` + JVM args | edit each Deployment |
| 8 | Apply `k8s/gateway/` for Spring Cloud Gateway Ingress | [k8s/gateway/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/gateway) |
| 9 | Wire ArgoCD Applications (`k8s/argocd/`) | [k8s/argocd/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s/argocd) |
| 10 | TLS via cert-manager + Let's Encrypt (`staging` first) | community chart |

> ⚠️ Workspace gateway replacement — the reference uses Apache APISIX as a
> Kubernetes Deployment. We will keep Spring Cloud Gateway as the workspace's
> gateway, exposing it via a single `Ingress` resource
> (`api.ecommerce.local`).

---

### Phase 14 — Frontend · 3–4 wk (optional, lower priority)

Bring forward only after backend stabilises.

| Stack | Choice | Rationale |
|-------|--------|-----------|
| Framework | Next.js 16 + React 19 | matches reference |
| Data | TanStack Query 5 | caching, retries, devtools |
| State | Zustand 5 | tiny, no boilerplate |
| Styling | Tailwind 4 + Lucide icons | matches reference |
| HTTP | Axios with Keycloak interceptor | standard |
| Auth | `next-auth` with Keycloak provider (PKCE) | standard |

Reference: [frontend/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/frontend).

---

## 5. Risk Register

| # | Risk | Impact | Likelihood | Mitigation |
|---|------|--------|-----------|------------|
| R1 | Spring Boot 4.1.1 + Spring Cloud 2025.1.0 incompatibilities with `common-spring` auto-configs | High | Medium | Phase 6 boots each service in isolation; if a starter class breaks, downgrade that piece of `common-spring` to use plain Spring Boot autoconfig |
| R2 | Java 25 LTS not yet GA on all CI runners (GitHub-hosted = Ubuntu 24.04, Temurin 21) | Medium | High | Pin a self-hosted `temurin-25` runner via `runs-on: [self-hosted, linux, jdk25]` label |
| R3 | Keycloak 26 + Spring Security 7 OAuth2 resource-server strict issuer validation | High | Medium | Set `KEYCLOAK_PUBLIC_SERVER_URL` correctly; use `org.springframework.security:spring-security-oauth2-resource-server:7.0.x` |
| R4 | ES 9.x client API differs from 8.x used in reference | Medium | High | Pin `co.elastic.clients:elasticsearch-java:9.4.x` only; rewrite `search-service` from scratch using new Java API Client |
| R5 | Liquibase master changelog collision when 13 services auto-migrate at boot | Medium | High | Set `spring.jpa.hibernate.ddl-auto: validate`; only Liquibase creates/modifies schema |
| R6 | Kafka 4.x with virtual threads + reactive consumers | Medium | Medium | Reference repo uses Kafka 3.9 in classic `KafkaListener`; keep that pattern, upgrade Kafka to 4.0 only after Phase 8 |
| R7 | Common-lib `ApiResponse` / `BusinessException` are in `com.ecommerce.*` but workspace uses `com.shop.*` | Low | Certain | Apply a single regex rename across the imported files (`sed -i 's/com.ecommerce/com.shop/g'`) |
| R8 | No integration tests = hidden regressions when services talk to each other | High | High | Mandate one happy + one sad Testcontainers IT per endpoint before merging a service PR |
| R9 | Jib 3.5.2 → 3.5.x required for Java 25 base image | Low | Low | Bump `jib-maven-plugin` to 3.5.2+ in parent POM (already pinned in ref: [pom.xml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/pom.xml#L387)) |
| R10 | Front-end rebuild after every backend change slows iteration | Low | Medium | Enable Vite/Next dev-server proxy to local gateway; only rebuild prod image at release |

---

## 6. Resource Estimates

| Phase | Calendar weeks | Team size | Engineer-days | Prerequisite |
|-------|----------------|-----------|---------------|--------------|
| 6 — Scaffolding | 1–2 | 2 | 14 | Docker infra live (✅) |
| 7 — Core services (6) | 3–4 | 2 | 50 | Phase 6 |
| 8 — Integration services (4) | 2–3 | 2 | 30 | Phase 7 auth + order done |
| 9 — API docs | 0.5 | 1 | 2 | Phase 7 in progress |
| 10 — Testing strategy | 2 | 2 | 16 | parallel from Phase 7 onward |
| 11 — Observability | 1 | 1 | 5 | Phase 7 done |
| 12 — CI/CD | 1 | 1 | 5 | Phase 7 done |
| 13 — K8s | 1–2 | 1 | 8 | Phase 6 done |
| 14 — Frontend | 3–4 | 1–2 | 35 | Phase 7 done |
| **Total** | **14–18 wk** | **2 avg** | **~165 d** | — |

Assumptions: senior Spring Boot engineer, no major blocker, no scope creep,
pair programming for the first service (auth-service) so the pattern is locked
in.

---

## 7. Reference Implementation Mapping

For every workspace artifact, the corresponding file in the reference repo.

| Workspace | Reference repo | Notes |
|-----------|----------------|-------|
| `gateway-service/` (Spring Cloud Gateway) | `deploy/apisix/config.yaml` + `apisix.yaml` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/deploy/apisix)) | Workspace chose Java-native gateway. Route table mirrors APISIX upstream definitions; copy the per-service upstream URI list verbatim |
| `auth-service/src/...` | `auth-service/src/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/auth-service/src/main/java/com/ecommerce/authservice)) | Direct adaptation. Rename package `com.ecommerce.authservice` → `com.shop.authservice`. Keep the `AuthController` SSO flow as-is (backend-mediated Authorization Code) |
| `product-service/src/...` | `product-service/src/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/product-service/src/main/java/com/ecommerce/productservice)) | Direct adaptation. `AbstractMappedEntity` is a per-service base entity — copy |
| `order-service/src/...` | `order-service/src/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/order-service/src/main/java/com/ecommerce/orderservice)) | Same package rename |
| `payment-service/src/...` | `payment-service/src/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/payment-service/src/main/java/com/ecommerce/paymentservice)) | Has `event/` and `http/` packages — copy both (Stripe / PayPal client goes in `http/`) |
| `inventory-service/src/...` | `inventory-service/src/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/inventory-service/src/main/java/com/ecommerce/inventoryservice)) | Note: ref uses `model/` not `entity/` — workspace should keep `entity/` for consistency |
| `shipping-service/src/...` | `shipping-service/...` | — |
| `favourite-service/src/...` | `favourite-service/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/favourite-service/src/main/java/com/ecommerce/favouriteservice)) | — |
| `rating-service/src/...` | `rating-service/...` | Two base paths: storefront + backoffice |
| `media-service/src/...` | `media-service/...` | Consumes `common-storage` (`S3ObjectStorageService`) |
| `tax-service/src/...` | `tax-service/...` | Two entities (TaxClass, TaxRate), 1:N relation |
| `promotion-service/src/...` | `promotion-service/...` | Coupon validation endpoints |
| `search-service/src/...` | `search-service/...` | ES 8 Java Client (workspace will bump to ES 9) |
| `notification-service/src/...` | `notification-service/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/notification-service/src/main/java/com/ecommerce/notificationservice)) | `event/` package contains Kafka consumers |
| `utils/common-core/...` | `common-lib/common-core/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib)) | Direct adaptation. Confirmed packages: `constants`, `exception`, `i18n`, `util`, `viewmodel` |
| `utils/common-spring/...` | `common-lib/common-spring/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-spring/src/main/java/com/ecommerce/commonlib)) | Confirmed packages: `autoconfigure`, `csv`, `data`, `mapper`, `openapi`, `web` |
| `utils/common-security/...` | `common-lib/common-security/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-security)) | — |
| `utils/common-keycloak/...` | `common-lib/common-keycloak/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-keycloak)) | Workspace provides `KeycloakTokenClient` + `KeycloakAdminClient` (RestClient) — replaces reference `KeycloakAuthClient`, `KeycloakClientProperties`, `UserService`, `RoleService`, `RealmService`, `TokenService` |
| `utils/common-kafka/...` | `common-lib/common-kafka/...` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-kafka)) | Provides `KafkaMessagePublisher`, `BaseKafkaConsumer`, JSON serdes |
| `utils/common-logging/...` | `common-lib/common-logging/...` | `@LogPerformance` AOP + request/response logging filter |
| `utils/common-storage/...` | `common-lib/common-storage/...` | `ObjectStorageService` (S3-compatible) |
| `docker-compose.yml` | `docker-compose.yml` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/docker-compose.yml)) | Drop the `apisix:` and `frontend:` services; keep the rest |
| `k8s/` (TODO) | `k8s/` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s)) | Direct copy of `k8s/backend/*.yaml`, `k8s/infra/`, `k8s/ingress/`; rewrite gateway as Spring Cloud Gateway Ingress |
| `docs/` (THIS doc) | `docs/keycloak-postgres-rollout.md` ([ref](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/docs/keycloak-postgres-rollout.md)) | Reference only has one doc; workspace extends with ROADMAP, ARCHITECTURE, SERVICE-CATALOG, COMMON-LIB-REFERENCE |

---

## 8. Next Steps (Immediate)

auth-service is now feature-complete (commits 24–25/08 + latest): controllers,
services, Liquibase, `application.yml`, `RoleController`, 37 unit + slice
tests. Note: a per-service `SecurityConfig.java` is **intentionally skipped** —
`common-security` auto-configures the exact same chain
(`@ConditionalOnMissingBean`), config-drive via `shop.security.*` (DRY).
The sequence now:

1. **Add auth-service SmokeIT** — `@SpringBootTest` with Testcontainers
   Postgres + Keycloak asserting `POST /api/v1/auth/sign-up` creates a user
   (this is the only remaining auth gap), then copy the IT skeleton to every
   service.

2. **Implement product-service** (next core service) — entities, repository,
   DTO, ModelMapper helper, controllers under `/api/v1/products` +
   `/api/v1/categories` (workspace path convention, NOT reference
   `/api/products`), Liquibase DDL, unit + slice tests.

3. **Promote the pattern to the remaining 11 services** using the per-service
   [reference tree](https://github.com/hoangtien2k3/ecommerce-microservices) as
   the source of truth, renaming `com.ecommerce.*` → `com.shop.*` and pinning
   each base path to `/api/v1/*`.

4. **Keep docs in sync** — update [`docs/SERVICE-CATALOG.md`](./SERVICE-CATALOG.md)
   per service as it ships.

### 8.1 Known cross-service follow-ups (from product-service design sync 2026-08-26)

Three patterns were locked in for product-service that **auth-service does not yet adopt**. They are out-of-scope for product-service but tracked here so the gap is visible. Each is a single Phase-9 candidate task (~0.5–1 d):

| Pattern | Where it lands | Auth-service gap |
|---|---|---|
| `BusinessException.of(ErrorCode.X)` (enum) instead of `notFound("auth.user.not.found.xyz")` (string key) | `utils/common-core/.../ErrorCode.java` already has `AUTH_USER_NOT_FOUND (AUTH-1006)`, `AUTH_USERNAME_EXISTS (AUTH-1003)`, `AUTH_EMAIL_EXISTS (AUTH-1004)`, etc. | `UserServiceImpl` (`AuthControllerTest`-style) — convert 9 throw sites to enum. Verify 37 tests still pass |
| `AbstractMappedEntity` + `JpaAuditingAutoConfiguration` | New `utils/common-core/.../AbstractMappedEntity.java` + `utils/common-spring/.../JpaAuditingAutoConfiguration.java` (created in product-service Phase 0) | `User` entity stays as `extends SoftDeletable` only — no audit fields. If adopted: add Liquibase changeset 003 to add `created_at`, `updated_at`, `created_by`, `updated_by` columns; switch `User` to extend `AbstractMappedEntity` |
| `public-paths: List<EndpointRule>` (rename + method-aware) | `common-security/SecurityProperties.java` record updated in product-service Phase 0 | `auth-service/application.yml` migrates from `public-endpoints: [-/api/v1/auth/**]` to `public-paths: [-path:/api/v1/auth/**]` |

> **Why not bundle into product-service Phase 0**: each migration touches auth-service's 37 tests and would expand product-service Phase 0 scope by ~3 tasks (refactor auth). Better scoped as a focused Phase-9 task.

---

## 9. References

### 9.1 Source of truth

- **Main repo** — [hoangtien2k3/ecommerce-microservices](https://github.com/hoangtien2k3/ecommerce-microservices)
- **Common library** — [common-lib/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib)
  · `common-core`, `common-spring`, `common-security`, `common-keycloak`,
  `common-kafka`, `common-logging`, `common-storage`
- **Parent POM** — [pom.xml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/pom.xml)
- **Docker Compose** — [docker-compose.yml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/docker-compose.yml)
- **K8s manifests** — [k8s/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/k8s)
  · `backend/`, `infra/`, `ingress/`, `gateway/`, `argocd/`, `frontend/`,
  `github-runner/`, `configmap.yaml`, `secrets.yaml`, `namespace.yaml`
- **k3d scripts** — [k3d-config.yaml](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/k3d-config.yaml)
  · [k3d-setup.sh](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/k3d-setup.sh)
- **Frontend** — [frontend/](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/frontend)
- **Docs** — [docs/keycloak-postgres-rollout.md](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/docs/keycloak-postgres-rollout.md)

### 9.2 Per-service anchors (raw file URLs)

- **auth-service** — [controller/AuthController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/controller/AuthController.java) · [controller/UserController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/controller/UserController.java) · [controller/RoleController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/controller/RoleController.java) · [service/UserServiceImpl.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/service/UserServiceImpl.java) · [entity/User.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/entity/User.java) · [entity/Role.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/entity/Role.java) · [repository/UserRepository.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/repository/UserRepository.java) · [config/SecurityConfig.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/java/com/ecommerce/authservice/config/SecurityConfig.java) · [application.yml](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/resources/application.yml) · [db.changelog-master.yaml](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/auth-service/src/main/resources/db/changelog/db.changelog-master.yaml)
- **product-service** — [ProductController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/product-service/src/main/java/com/ecommerce/productservice/controller/ProductController.java) · [CategoryController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/product-service/src/main/java/com/ecommerce/productservice/controller/CategoryController.java) · [Product.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/product-service/src/main/java/com/ecommerce/productservice/entity/Product.java) · [Category.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/product-service/src/main/java/com/ecommerce/productservice/entity/Category.java) · [AbstractMappedEntity.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/product-service/src/main/java/com/ecommerce/productservice/entity/AbstractMappedEntity.java)
- **order-service** — [OrderController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/order-service/src/main/java/com/ecommerce/orderservice/controller/OrderController.java) · [CartController.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/order-service/src/main/java/com/ecommerce/orderservice/controller/CartController.java) · [Order.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/order-service/src/main/java/com/ecommerce/orderservice/entity/Order.java) · [Cart.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/order-service/src/main/java/com/ecommerce/orderservice/entity/Cart.java) · [AbstractMappedEntity.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/order-service/src/main/java/com/ecommerce/orderservice/entity/AbstractMappedEntity.java)
- **common-lib** — [ApiPaths.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/constants/ApiPaths.java) · [ApiResponse.java](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/viewmodel/ApiResponse.java)
- **k8s/backend** — [auth-service.yaml](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/k8s/backend/auth-service.yaml) · [configmap.yaml](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/k8s/configmap.yaml) · [secrets.yaml](https://raw.githubusercontent.com/hoangtien2k3/ecommerce-microservices/main/k8s/secrets.yaml)

### 9.3 Stack documentation (offline Maven cache)

- Spring Boot 4.1.1 — `~/.m2/repository/org/springframework/boot/spring-boot-docs/4.1.1/`
- Spring Cloud 2025.1.0 — `~/.m2/repository/org/springframework/cloud/`
- Keycloak 26 — `KC_DB=postgres` mode is enabled in `docker-compose.yml`
- Apache Kafka 4.x — `KAFKA_PROCESS_ROLES=broker,controller` (KRaft, no ZK)
- Elasticsearch 9.4.x — Java Client at `co.elastic.clients:elasticsearch-java`
- Resilience4j 2.4 — circuit breaker / retry on inter-service HTTP
- springdoc-openapi 3.1 — Swagger UI auto-configured in `common-spring`

---

**Document owners** — backend platform team. Update whenever a phase completes.
Last revision: 2026-08-25.
