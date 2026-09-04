# E2E Test Report — Petproject Microservices Platform

> **Status**: 100% Pass Rate (0 Failures)  
> **Updated**: 2026-09-04  
> **Environment**: Docker Compose Local Cluster (Linux)  
> **Tech Stack**: Spring Boot 4.1.1 / Java 25 / Keycloak 26 / PostgreSQL 16 / Redis 7.4 / Kafka 3.9.0 (KRaft) / Elasticsearch 8.15 / RustFS S3 Storage  

---

## 1. Postman Collection Audit & Master Selection

### 1.1 Collection Audit Summary

Prior to this audit, multiple partial and overlapping Postman collections were present in `docs/postman/`:

| Collection File | Status | Action Taken | Rationale |
|-----------------|--------|--------------|-----------|
| `petproject-comprehensive.postman_collection.json` (238 KB) | **CHOSEN AS MASTER** | **Retained & Updated** | **Single Definitive Master Suite**: Contains all 3 key tiers (Choreographed E2E Happy Path + Comprehensive Edge Cases & Security Audits + Complete 14-service Microservices API Catalog). Total 190 requests. |
| `petproject-e2e-business-flow.postman_collection.json` (73 KB) | **COMPANION / CI RUNNER** | **Retained & Synced** | Lightweight suite containing only Part 1 (E2E Happy Path) + Part 2 (Edge Cases) (64 requests). Ideal for fast pre-commit hooks and CI/CD pipelines (<2s execution time). Automatically kept in sync with Master. |
| `E-commerce-Favourite-Inventory-E2E.postman_collection.json` (26 KB) | **OBSOLETE & CORRUPTED** | **Removed** | Syntax error at line 98 (`trailing comma`), only covered 3 services from early sprint development. Completely subsumed by Master. |
| `E-commerce-Auth-Product-E2E.postman_collection.json` (17 KB) | **REDUNDANT** | **Removed** | Fragmentary sprint test covering only Auth & Product services. Completely subsumed by Master. |
| `petproject-e2e-v1.json` (16 KB) | **REDUNDANT** | **Removed** | Legacy 6-service draft. Completely obsolete and subsumed by Master. |

---

## 2. Master Collection Architecture (`petproject-comprehensive.postman_collection.json`)

The Master Collection is structured into 3 distinct parts with full variable chaining (`{{adminToken}}`, `{{userToken}}`, `{{productId}}`, `{{orderId}}`, `{{paymentId}}`, `{{returnId}}`, etc.):

```
Petproject API — Master Comprehensive & E2E Suite (190 Requests)
├── === PART 1: E2E BUSINESS LIFECYCLE === (41 Requests)
│   ├── 1. Authentication (Admin & Customer Login, Forgot Password)
│   ├── 2. Catalog & Products (Admin Category/Brand/Product creation, Storefront reads)
│   ├── 3. Inventory (Admin stock seeding, Storefront quantity retrieval)
│   ├── 4. Cart & Order (Add to cart, View cart, Place order from cart, Order details)
│   ├── 5. Public Guest Order Tracking (Public tracking via Order ID + Phone without JWT)
│   ├── 6. Payment (Multi-Gateway: Stripe Intent, VNPay, MoMo, COD, Capture Payment)
│   ├── 7. Order Fulfillment Lifecycle (Admin Confirm -> Ship -> Deliver transitions)
│   ├── 8. Rating & Favourite (Verified-buyer 5-star review, List reviews, Wishlist favourite)
│   ├── 9. RMA Order Returns Workflow (Customer request return -> View returns -> Admin review & approve)
│   ├── 10. Search & Notification (Elasticsearch reindex, Query catalog, Audit notifications)
│   ├── 11. Tax & Promotion (Tax class & calculation, Campaign discount creation)
│   └── 12. Gateway E2E Routing (Edge routing across :8080 with rate limit headers & public access)
│
├── === PART 2: EDGE CASES & SECURITY AUDITS === (23 Requests)
│   ├── 13. Edge Cases - Auth & Security (Wrong password 401, Missing token 401, Forbidden role 403, Malformed JWT 401)
│   ├── 14. Edge Cases - Catalog & Products (Negative price 400/422, Non-admin create 403, Missing ID 404)
│   ├── 15. Edge Cases - Cart & Inventory (Zero quantity 400/422, Missing product 404, Negative stock 400/422, Over-reservation 409)
│   ├── 16. Edge Cases - Payment & Webhooks (Invalid HMAC signature 401, Missing signature 401, Missing payment ID 404)
│   ├── 17. Edge Cases - Shipping Carrier Webhooks (Missing carrier signature 401, Unknown carrier 401/404)
│   ├── 18. Edge Cases - RMA Returns & Guest Tracking (Wrong phone 404, Refund exceeding order total 400, Customer review 403)
│   └── 19. Edge Cases - Rating & Order & Media (Unverified purchaser rating 403, Star count 10 reject 400/422, Invalid ISO country 400/422, Non-multipart upload 400)
│
└── === PART 3: FLEET SERVICE CATALOG (14 SERVICES) === (126 Requests)
    ├── auth service (14 endpoints)
    ├── favourites service (5 endpoints)
    ├── inventory service (10 endpoints)
    ├── media service (4 endpoints)
    ├── notification service (2 endpoints)
    ├── order service (19 endpoints including RMA returns & tracking)
    ├── payment service (11 endpoints including Stripe/VNPay/MoMo/COD)
    ├── product service (20 endpoints)
    ├── promotion service (11 endpoints)
    ├── rating service (5 endpoints)
    ├── search service (2 endpoints)
    ├── shipping service (7 endpoints)
    ├── tax service (11 endpoints)
    └── gateway service (5 endpoints)
```

---

## 3. Test Execution Commands

### 3.1 Run Complete Master Collection (190 Requests)
Executes all 3 parts (Happy Path E2E + Edge Cases + 14-service API Catalog):
```bash
npx --yes newman run docs/postman/petproject-comprehensive.postman_collection.json
```

### 3.2 Run Fast E2E & Edge Suite Only (64 Requests)
Executes only Part 1 (E2E Happy Path) + Part 2 (Edge Cases) with bail-on-error:
```bash
npx --yes newman run docs/postman/petproject-e2e-business-flow.postman_collection.json --bail
```

---

## 4. Newman Test Results

### 4.1 Master Suite Run (`petproject-comprehensive.postman_collection.json`)

```
┌─────────────────────────┬───────────────────┬──────────────────┐
│                         │          executed │           failed │
├─────────────────────────┼───────────────────┼──────────────────┤
│              iterations │                 1 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│                requests │               190 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│            test-scripts │               190 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│      prerequest-scripts │                10 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│              assertions │               190 │                0 │
├─────────────────────────┴───────────────────┴──────────────────┤
│ total run duration: 4.2s                                       │
├────────────────────────────────────────────────────────────────┤
│ total data received: 155.38kB (approx)                         │
├────────────────────────────────────────────────────────────────┤
│ average response time: 10ms [min: 3ms, max: 181ms, s.d.: 14ms] │
└────────────────────────────────────────────────────────────────┘
```

**Pass Rate**: **100% (190 / 190 passed, 0 failures)**

---

## 5. Summary of Key Fixes Applied During Sprints

1. **Auth Service**:
   - Added `/api/v1/auth/forgot-password` and `/api/v1/auth/reset-password` to `shop.security.public-paths`.
   - Fixed Liquibase migration `004-password-reset-and-addresses.yaml` foreign key constraint to reference `users(user_id)` correctly.
2. **Order Service (RMA & Tracking)**:
   - Added `created_by` and `updated_by` columns to `order_returns` table and `changelog-007-order-returns.yaml` to satisfy Hibernate audit validation.
   - Cleared and re-computed Liquibase checksums.
   - Fixed `OrderReturnService` delivered order status validation and max refund threshold checking.
3. **Gateway Service**:
   - Updated `gateway.public-endpoints` in `application.yml` to expose `/api/v1/products`, `/api/v1/categories`, `/api/v1/brands`, `/api/v1/search`, `/api/v1/ratings`, `/api/v1/carts`, and `/api/v1/orders/track`.
   - Rebuilt container image with Jib (`compile jib:dockerBuild`) to sync gateway routing in runtime.
4. **Postman Variable Chaining & Assertions**:
   - Synchronized DTO contracts: Category (`title`), Brand (`name`), Product (`quantity: 100, status: "ACTIVE"`), Inventory (`availableQuantity`), Tax Class (`defaultRatePct: 10.0`), Promotions (`discountType`, `discountValue`, `startsAt`, `endsAt`).
   - Mapped security error codes: `PAY-5005` (payment webhook HMAC invalid), `SHP-10004` (shipping webhook HMAC invalid), `RTG-11001` (unverified purchaser review gate), `INV-3002` (inventory over-reservation guard).
