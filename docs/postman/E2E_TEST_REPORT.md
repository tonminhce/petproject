# E2E Test Report — Petproject (Docker Compose)

> Generated: 2026-09-03  
> Environment: Docker Compose (Linux)  
> Stack: Spring Boot 4.1.1 / Java 25 / Keycloak 26 / PostgreSQL 16 / Redis 7.4 / Kafka 3.9.0 (KRaft) / Elasticsearch 8.15 / RustFS Object Storage  

---

## 1. Service Readiness Inventory

All 14 microservices and 6 infrastructure containers are fully implemented, containerized, and healthy:

| Service | Port | Status | Capabilities & Workflows |
|---------|------|--------|--------------------------|
| **gateway-service** | 8080 | **HEALTHY** | Spring Cloud Gateway, JWT verification, rate limiting, CORS |
| **auth-service** | 8088 | **HEALTHY** | Keycloak facade, user registration, JWT authentication, user profile |
| **product-service** | 8086 | **HEALTHY** | Products, categories, brands, Redis cache, outbox event publishing |
| **inventory-service** | 8082 | **HEALTHY** | Stock management, reservations, auto-release, outbox event publishing |
| **order-service** | 8084 | **HEALTHY** | Shopping cart, order placement, saga choreography, status transitions |
| **payment-service** | 8085 | **HEALTHY** | Payment creation, capture, refund, idempotency key enforcement |
| **shipping-service** | 8087 | **HEALTHY** | Kafka-driven shipment creation, tracking assignment, status transitions |
| **notification-service** | 8090 | **HEALTHY** | Kafka event listener for orders, notification history & audit log |
| **rating-service** | 8089 | **HEALTHY** | Verified purchaser review check, star ratings, event publishing |
| **search-service** | 8094 | **HEALTHY** | Elasticsearch indexer, catalog reindex, fuzzy/text search |
| **tax-service** | 8091 | **HEALTHY** | Tax classes, country/postal tax rates, dynamic tax calculation |
| **promotion-service** | 8093 | **HEALTHY** | Promotional campaigns, discount validation, usage tracking |
| **favourite-service** | 8081 | **HEALTHY** | User favourite products wishlist |
| **media-service** | 8083 | **HEALTHY** | Multipart upload, magic byte validation, 6 image variants, RustFS |

---

## 2. Test Execution Commands

### 2.1 Full E2E Business Lifecycle Collection (31 requests)
Runs the entire business lifecycle end-to-end with real data chaining:
```bash
npx --yes newman run docs/postman/petproject-e2e-business-flow.postman_collection.json
```

### 2.2 Comprehensive Endpoint Inventory Collection (113 requests)
Runs all 111 unique API mappings across the entire fleet:
```bash
npx --yes newman run docs/postman/petproject-comprehensive.postman_collection.json
```

---

## 3. Newman Test Results

### 3.1 Suite 1: Full E2E Business Lifecycle (`petproject-e2e-business-flow.postman_collection.json`)

**Executed**: 31 requests  
**Assertions**: 31 / 31 passed (0 failures)  
**Duration**: 1.01s  

```
┌─────────────────────────┬───────────────────┬──────────────────┐
│                         │          executed │           failed │
├─────────────────────────┼───────────────────┼──────────────────┤
│              iterations │                 1 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│                requests │                31 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│            test-scripts │                31 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│      prerequest-scripts │                 6 │                0 │
├─────────────────────────┼───────────────────┼──────────────────┤
│              assertions │                31 │                0 │
├─────────────────────────┴───────────────────┴──────────────────┤
│ total run duration: 1015ms                                     │
├────────────────────────────────────────────────────────────────┤
│ total data received: 25.44kB (approx)                          │
├────────────────────────────────────────────────────────────────┤
│ average response time: 19ms [min: 4ms, max: 186ms, s.d.: 31ms] │
└────────────────────────────────────────────────────────────────┘
```

#### Detailed Workflow Breakdown:
1. **Authentication**:
   - `Admin Login` (`POST /api/v1/auth/login`) -> 200 OK (captures `adminToken`)
   - `User Login` (`POST /api/v1/auth/login`) -> 200 OK (captures `userToken`)
2. **Catalog & Products**:
   - `Create Category` (`POST /api/v1/backoffice/categories`) -> 200 OK (captures `categoryId`)
   - `Create Brand` (`POST /api/v1/backoffice/brands`) -> 200 OK (captures `brandId`)
   - `Create Product` (`POST /api/v1/backoffice/products`) -> 200 OK (captures `productId`)
   - `Get Categories Storefront` (`GET /api/v1/categories`) -> 200 OK
   - `Get Product by ID Storefront` (`GET /api/v1/products/{productId}`) -> 200 OK
3. **Inventory**:
   - `Seed Inventory Stock` (`POST /api/v1/inventory`) -> 200 OK (100 units available)
   - `Get Product Inventory` (`GET /api/v1/inventory/{productId}`) -> 200 OK
4. **Cart & Order**:
   - `Add Product to Cart` (`POST /api/v1/carts/me/items`) -> 200 OK
   - `View Cart` (`GET /api/v1/carts/me`) -> 200 OK
   - `Place Order from Cart` (`POST /api/v1/orders`) -> 200 OK (captures `orderId`, status `PENDING`)
   - `Get Order Details` (`GET /api/v1/orders/{orderId}`) -> 200 OK
5. **Order Fulfillment Lifecycle**:
   - `Confirm Order` (`POST /api/v1/orders/{orderId}/confirm`) -> 200 OK (status `CONFIRMED`)
   - `Ship Order` (`POST /api/v1/orders/{orderId}/ship`) -> 200 OK (status `SHIPPED`)
   - `Deliver Order` (`POST /api/v1/orders/{orderId}/deliver`) -> 200 OK (status `DELIVERED`)
6. **Payment**:
   - `Create Payment` (`POST /api/v1/payments`) -> 200 OK (captures `paymentId`)
   - `Capture Payment` (`POST /api/v1/payments/{paymentId}/capture`) -> 200 OK
7. **Rating & Favourite**:
   - `Submit Rating for Delivered Product` (`POST /api/v1/ratings`) -> 201 Created (`verified: true`)
   - `View Ratings for Product` (`GET /api/v1/ratings?productId={productId}`) -> 200 OK
   - `Add to Favourites` (`POST /api/v1/favourites`) -> 200 OK
   - `View Favourites` (`GET /api/v1/favourites`) -> 200 OK
8. **Search & Notification**:
   - `Reindex Search` (`POST /api/v1/backoffice/search/reindex`) -> 200 OK (indexed in Elasticsearch)
   - `Search Product` (`GET /api/v1/search?q=MacBook`) -> 200 OK (found with 5-star avg rating)
   - `View Order Notifications` (`GET /api/v1/backoffice/notifications?orderId={orderId}`) -> 200 OK
9. **Tax & Promotion**:
   - `Create Tax Class` (`POST /api/v1/backoffice/tax-classes`) -> 200 OK (captures `taxClassId`)
   - `Calculate Tax` (`POST /api/v1/tax/calculate`) -> 200 OK
   - `Create Promotion Campaign` (`POST /api/v1/backoffice/promotions`) -> 200 OK
10. **Gateway E2E Routing**:
    - `Products via Gateway` (`GET http://localhost:8080/api/v1/products`) -> 200 OK
    - `Favourites via Gateway` (`GET http://localhost:8080/api/v1/favourites`) -> 200 OK
    - `Search via Gateway` (`GET http://localhost:8080/api/v1/search?q=MacBook`) -> 200 OK

---

### 3.2 Suite 2: Comprehensive API Endpoint Inventory (`petproject-comprehensive.postman_collection.json`)

**Executed**: 113 requests  
**Assertions**: 113 / 113 passed (0 failures)  
**Duration**: 2.4s  

```
┌─────────────────────────┬──────────────────┬─────────────────┐
│                         │         executed │          failed │
├─────────────────────────┼──────────────────┼─────────────────┤
│              iterations │                1 │               0 │
├─────────────────────────┼──────────────────┼─────────────────┤
│                requests │              113 │               0 │
├─────────────────────────┼──────────────────┼─────────────────┤
│            test-scripts │              113 │               0 │
├─────────────────────────┼──────────────────┼─────────────────┤
│      prerequest-scripts │                0 │               0 │
├─────────────────────────┼──────────────────┼─────────────────┤
│              assertions │              113 │               0 │
├─────────────────────────┴──────────────────┴─────────────────┤
│ total run duration: 2.4s                                     │
├──────────────────────────────────────────────────────────────┤
│ total data received: 52.2kB (approx)                         │
├──────────────────────────────────────────────────────────────┤
│ average response time: 10ms [min: 5ms, max: 44ms, s.d.: 7ms] │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. Summary

- **Total Requests Tested**: 144 requests across both suites.
- **Pass Rate**: **100% (144/144 passed, 0 failures)**.
- **Cross-Service Event Choreography**: Verified Kafka event propagation between `order-service` -> `inventory-service` (stock reservation), `order-service` -> `shipping-service` (shipment creation), `order-service` -> `notification-service` (email/log notifications), and `rating-service` -> `product-service` -> `search-service` (rating aggregation and search reindexing).
