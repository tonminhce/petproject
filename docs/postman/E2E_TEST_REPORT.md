# E2E Test Report — Petproject (Docker Compose)

> Generated 2026-08-29 via deep exploration + docker compose up
> Last refreshed 2026-09-03 (postman collection coverage sweep against `docs/postman/API_ENDPOINT_INVENTORY.md`)
> Working dir: /Users/tonminh-mac/IdeaProjects/untitled5
> Stack: Spring Boot 4.1.1 / Java 25 / Keycloak 26 / Postgres 16 / Redis 7.4 / Kafka 3.9.0 (KRaft) / Elasticsearch 8.15 / RustFS

---

## 1. Service Readiness Inventory

Verified bằng find + grep trên các file Java thật:

| Service | Java files | Controllers | Status |
|---------|-----------:|-----------:|--------|
| auth-service | 25 | 3 | **FULL** — Keycloak facade + shadow user CRUD |
| product-service | 43 | 3 | **FULL** — products + categories + brands + cache + outbox |
| inventory-service | 26 | 1 | **FULL** — stock + reservation + sweep + release-committed endpoint |
| order-service | 61 | 3 | **FULL** — saga + pricing + cart + status transitions |
| favourite-service | 9 | 1 | **FULL** |
| gateway-service | 11 | 0 (Spring Cloud Gateway filters) | **FULL** — JWT validation + rate limit + CORS |
| tax-service | 1 | 0 | **SKELETON** — only Application.java |
| promotion-service | 1 | 0 | **SKELETON** — spec drafted but not yet implemented |
| payment-service | 1 | 0 | **SKELETON** |
| media-service | 1 | 0 | **SKELETON** |
| notification-service | 1 | 0 | **SKELETON** |
| rating-service | 1 | 0 | **SKELETON** |
| search-service | 1 | 0 | **SKELETON** |
| shipping-service | 1 | 0 | **SKELETON** |

**6 working services for E2E.** The 8 skeleton services are pure app shells with no HTTP layer implemented; not addressable for testing.

---

## 2. Test Setup — Docker Compose Spin-up

### 2.1 Bootstrap (3 commands)

```bash
# Bring up infrastructure only first (faster iteration)
docker compose up -d postgres redis kafka keycloak elasticsearch rustfs

# Wait ~30s for keycloak import-realm to settle
sleep 30 && docker ps --filter "name=keycloak" --format "{{.Status}}"

# Bring up the 6 working services
docker compose up -d auth-service product-service inventory-service \
  favourite-service gateway-service order-service

# Wait ~60s for Spring Boot health checks
sleep 60
```

### 2.2 Verified images built (Jib local)

```
auth-service          latest  463MB
product-service       latest  527MB
inventory-service     latest  181MB
order-service         latest  181MB
favourite-service     latest  457MB
gateway-service       latest  413MB
+ 8 skeleton services (will fail-fast at boot if you `up` them)
```

### 2.3 Test credentials (Keycloak realm `ecommerce`)

From docker/keycloak/import/ecommerce-realm.json:

| Username | Password | Roles |
|----------|----------|-------|
| testuser | testpass | USER |
| adminuser | adminpass | ADMIN, MANAGER |

Realm roles: ADMIN, USER, MANAGER, SERVICE (@PreAuthorize("hasRole(SERVICE) or hasRole(ADMIN)"))

---

## 3. E2E Test Results

### 3.1 Pass

| # | Test | Result |
|---|------|--------|
| 1 | OIDC discovery at /realms/ecommerce/.well-known/... | 200, returns issuer http://localhost:9090/realms/ecommerce |
| 2 | Health endpoints (/actuator/health) for auth, product, inventory, favourite, gateway | All UP |
| 3 | Keycloak token grant via password grant_type (testuser, adminuser) | 200, returns valid access_token |
| 4 | Product list (anonymous public read at /api/v1/products) | 200, returns paginated ApiResponse<PageResponse<...>> with seed data |
| 5 | Health for order-service | UP (after Redis password fix — see §4.2) |

### 3.2 Fail — two production bugs found

#### Bug #1: JWT `iss` claim mismatch (BLOCKING every authenticated endpoint)

| | Value |
|---|---|
| Keycloak issues tokens with `iss` | http://localhost:9090/realms/ecommerce |
| Services expect SHOP_SECURITY_ISSUER_URI | http://keycloak:8080/realms/ecommerce |

Evidence:
```
GET /api/v1/users/me  with Bearer eyJ... → 401
WWW-Authenticate: Bearer error="invalid_token", error_description="The iss claim is not valid"
```

Root cause:
- docker-compose.yml:23 — the `x-jwt` anchor hardcodes http://keycloak:8080/...
- .env:35 has correct JWT_ISSUER_URI=http://localhost:9090/realms/ecommerce but it's overridden by the compose anchor
- Keycloak's `iss` claim comes from its KEYCLOAK_PUBLIC_SERVER_URL (= http://localhost:9090)

Fix (one-line in docker-compose.yml):
```diff
-x-jwt: &jwt
-  SHOP_SECURITY_ISSUER_URI: http://keycloak:8080/realms/ecommerce
+x-jwt: &jwt
+  SHOP_SECURITY_ISSUER_URI: ${JWT_ISSUER_URI}
```

#### Bug #2: order-service missing REDIS_PASSWORD env (BLOCKING ordering flow)

Evidence:
```
docker logs order-service | grep -i redis
> Caused by: io.lettuce.core.RedisCommandExecutionException:
>   NOAUTH HELLO must be called with the client already authenticated
```

Root cause: docker-compose.yml:312-336 (order-service stanza) doesn't include SPRING_DATA_REDIS_PASSWORD, but the docker Redis runs with --requirepass.

Fix:
```diff
       environment:
         <<: [*jwt, *pg-creds]
         SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/orderservice
+        SPRING_DATA_REDIS_HOST: redis
+        SPRING_DATA_REDIS_PORT: 6379
+        SPRING_DATA_REDIS_PASSWORD: ${REDIS_PASSWORD}
         SHOP_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

---

## 4. Curl Recipes (runnable as-is after bugs fixed)

### 4.1 Get JWT (testuser)

```bash
TOK=$(curl -s -X POST http://localhost:9090/realms/ecommerce/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=ecommerce-client&client_secret=ecommerce-client-secret&username=testuser&password=testpass" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)["access_token"])")

curl -s -H "Authorization: Bearer $TOK" http://localhost:8088/api/v1/users/me | jq
```

### 4.2 Reserve stock (SERVICE-role, but ADMIN works via `or hasRole(ADMIN)`)

```bash
TOK_ADMIN=$(curl -s -X POST http://localhost:9090/realms/ecommerce/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=ecommerce-client&client_secret=ecommerce-client-secret&username=adminuser&password=adminpass" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)["access_token"])")

PRODUCT_ID="1aa89880-481c-4e95-aaab-0461aa50c153"  # from /products
curl -X POST http://localhost:8082/api/v1/inventory/$PRODUCT_ID/reserve \
  -H "Authorization: Bearer $TOK_ADMIN" \
  -H "Content-Type: application/json" \
  -d "{"quantity":2,"orderId":"00000000-0000-0000-0000-000000000001"}"
```

### 4.3 List products anonymous

```bash
curl -s http://localhost:8086/api/v1/products | jq ".data.content | length"
```

### 4.4 Add to cart

```bash
curl -X POST http://localhost:8084/api/v1/carts/me/items \
  -H "Authorization: Bearer $TOK" \
  -H "Content-Type: application/json" \
  -d "{\"productId\":\"$PRODUCT_ID\",\"quantity\":2}"
```

---

## 5. Postman Collection

Files:
- `docs/postman/petproject-comprehensive.postman_collection.json` — **primary**,
  full surface coverage (113 requests, 14 folders, see §5.1 below)
- `docs/postman/petproject-e2e-v1.json` — historical 41-request E2E (Wave C/D
  evidence, kept for diff with the comprehensive version)
- `docs/postman/E-commerce-Auth-Product-E2E.postman_collection.json` —
  vertical slice (auth + product)
- `docs/postman/E-commerce-Favourite-Inventory-E2E.postman_collection.json` —
  vertical slice (favourite + inventory)

### 5.1 Comprehensive collection coverage (as of 2026-09-03)

| Service | Folder | Requests | Inventory endpoints | Coverage |
|---|---|---:|---:|---:|
| Auth + chaining | `00 - Auth chaining and errors` | 2 | (cross-cutting) | n/a |
| auth-service | `auth service` | 14 | 14 | 100% |
| product-service | `product service` | 19 | 19 | 100% |
| inventory-service | `inventory service` | 10 | 10 | 100% |
| order-service | `order service` | 14 | 14 | 100% |
| favourite-service | `favourites service` | 5 | 5 | 100% |
| payment-service | `payment service` | 7 | 7 | 100% |
| media-service | `media service` | 4 | 4 | 100% |
| promotion-service | `promotion service` | 11 | 11 | 100% |
| rating-service | `rating service` | 5 | 5 | 100% |
| search-service | `search service` | 2 | 2 | 100% |
| shipping-service | `shipping service` | 7 | 7 | 100% |
| tax-service | `tax service` | 11 | 11 | 100% |
| notification-service | `notification service` | 2 | 2 | 100% |
| **Total** | | **113** | **111** | **100%** |

The collection contains one extra request beyond the inventory —
`GET /internal/products/media-references/{mediaId}` — which is the
service-to-service endpoint used by media-service to resolve product → media
links; it is gated by the SERVICE realm role per
`utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java`.

Every URL was verified to begin with one of the constants declared in
`utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java`
(`/api/v1/auth`, `/api/v1/users`, `/api/v1/products`, …). See `docs/SERVICE-CATALOG.md`
for path conventions per service.

JSON syntax validated via:
```bash
python3 -c "import json; json.load(open('docs/postman/petproject-comprehensive.postman_collection.json'))"
```

### 5.2 Historical E2E collection

File: docs/postman/petproject-e2e-v1.json

**Coverage:** 41 requests across 8 folders
- Health & Infra (7) — every running service
- Auth (5) — login/refresh/logout/sign-up (public + admin)
- User (4) — admin + self endpoints
- Product (7) — public catalog + admin CRUD
- Inventory (8) — read/create/reserve/commit/release/release-committed/state
- Favourite (4) — user-scoped CRUD
- Order/Cart (5) — needs REDIS_PASSWORD fix to fully work
- Gateway (1) — demonstrates iss-mismatch bug

Setup:
1. Open Postman → Import → docs/postman/petproject-e2e-v1.json
2. Set environment to "Petproject Local" (folder uses {{keycloak_url}} etc.)
3. First request auto-refreshes USER and ADMIN tokens (240s TTL)
4. Test in order — Health → Auth → User → Product → Inventory → Favourite → Order → Gateway

Variables pre-set:
```
keycloak_url     = http://localhost:9090
auth_url         = http://localhost:8088
product_url      = http://localhost:8086
inventory_url    = http://localhost:8082
order_url        = http://localhost:8084
favourite_url    = http://localhost:8081
gateway_url      = http://localhost:8080
testuser_token   = (auto-populated)
admin_token      = (auto-populated)
```

### 5.3 Postman schema

The collection conforms to the
[Postman Collection v2.1.0 schema](https://schema.getpostman.com/json/collection/v2.1.0/collection.json).

---

## 6. What is NOT covered (deferred)

- Promotion service — spec exists (2026-08-29-promotion-service-design.md), code not yet implemented. Affects order-service saga's pricing step.
- Payment service — Phase 8, no controllers exist. Order-service's confirm endpoint is the placeholder for payment-driven confirmation.
- Tax service — referenced in order-service PricingServiceImpl but service is skeleton; PricingServiceImpl has a guard that throws ORDER_TAX_CALCULATION_FAILED if it returns 500.
- Refunds — confirm-driven commit orchestration not yet wired (per the Aug 30 hardening spec — OrderCommitCoordinator).
- Reconciliation — OrderReconciliationScheduler planned in hardening spec but not implemented.
- Idempotent commit/release — InventoryServiceImpl current code throws on retry (the Aug 30 hardening spec calls for making these idempotent first).

---

## 7. Cleanup

```bash
docker compose down          # stop all containers, preserve volumes
docker compose down -v       # nuke volumes (full reset)
```
