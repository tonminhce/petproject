# Engineering Handoff: Petproject Microservices Platform

> **Last updated**: 2026-09-03
> **Repository**: `git@github.com:tonminhce/petproject.git` (branch `main`)
> **Tech stack**: Spring Boot 4.1.1, Java 25 OpenJDK, Keycloak 26, PostgreSQL 16, Redis 7.4, Kafka 3.9.0 (KRaft), Elasticsearch 8.15, RustFS S3 Storage

---

## 1. System architecture and operational status

The project consists of 23 Maven modules (14 microservices, 8 shared utility libraries, and a parent POM aggregator):

| Service | Host port | Container name | Docker health | Primary responsibilities |
|---|:---:|---|:---:|---|
| **gateway-service** | 8080 | `gateway-service` | HEALTHY | Spring Cloud Gateway, JWT verification, Redis rate limiting, CORS, edge routing |
| **auth-service** | 8088 | `auth-service` | HEALTHY | Keycloak facade, login, refresh, logout, shadow user CRUD, role management |
| **product-service** | 8086 | `product-service` | HEALTHY | Product catalog, categories, brands, Redis caching, outbox event publishing |
| **inventory-service** | 8082 | `inventory-service` | HEALTHY | Stock management, reservations, expiration sweeper, commit and release |
| **order-service** | 8084 | `order-service` | HEALTHY | Carts, checkout saga coordination, order state machine, return management |
| **payment-service** | 8085 | `payment-service` | HEALTHY | Payment creation, Stripe integration, capture, refunds, idempotency checks |
| **shipping-service** | 8087 | `shipping-service` | HEALTHY | Consumes order events, creates shipments, generates tracking numbers, carrier webhooks |
| **notification-service** | 8090 | `notification-service` | HEALTHY | Consumes Kafka events, sends SMTP emails, persists customer notifications |
| **rating-service** | 8089 | `rating-service` | HEALTHY | 1-5 star ratings, verified purchase check (`RTG-11001`), review moderation |
| **search-service** | 8094 | `search-service` | HEALTHY | Elasticsearch indexing, fuzzy and full-text search, prefix suggestions |
| **tax-service** | 8091 | `tax-service` | HEALTHY | Tax classes, jurisdiction tax rates by location, tax calculation |
| **promotion-service** | 8093 | `promotion-service` | HEALTHY | Promotional campaigns, discount coupons, two-phase usage reservations |
| **favourite-service** | 8081 | `favourite-service` | HEALTHY | Customer wishlist storage and item management |
| **media-service** | 8083 | `media-service` | HEALTHY | File uploads, MIME type magic-byte sniffing, 6 WebP responsive variants, RustFS S3 |

### Infrastructure containers:
- **keycloak** (8080 internal, 9090 host, realm `ecommerce`): Administrator credentials `admin` / `admin`. Preloaded seed accounts: `adminuser` / `adminpass` (roles: ADMIN, MANAGER), `testuser` / `changeme` (role: USER).
- **postgres** (5432): Configured with `max_connections=300` and volume persistence.
- **redis** (6379): Secured with password `admin` (`docker exec redis redis-cli -a admin`).
- **kafka** (9092, KRaft mode): Cluster identifier `MkU3OEVBNTcwNTJENDM2Qk`.
- **elasticsearch** (9200): Single node with basic authentication credentials.
- **rustfs** (9000 API, 9001 console): S3-compatible media and asset storage.

---

## 2. GitNexus code intelligence harness

The project is indexed with GitNexus across 11,218 symbols, 25,091 relationships, and 300 execution flows.

### 2.1 Harness script commands
The helper script is located at `utils/gitnexus-harness.sh`:
```bash
# Make script executable if necessary
chmod +x utils/gitnexus-harness.sh

# Recommended terminal alias:
alias g="./utils/gitnexus-harness.sh"

# Check code intelligence index status
g status

# Re-analyze index after code modifications
g fresh

# Search execution traces by concept
g flow "create order"
g flow "verify purchase"

# Inspect symbol relationships (callers, callees, participating flows)
g here OrderService
g here CategoryServiceImpl

# Required before code modifications: Blast radius and impact analysis
g blast OrderController -d upstream
g blast RoleRepository -d upstream

# Required before git commit: Verify change scope
g changed
```

### 2.2 Mandatory workflow rules
1. Run impact analysis (`g blast <symbol> -d upstream`) before modifying any function, class, or method, and evaluate the reported blast radius.
2. Warn the team if impact analysis indicates HIGH or CRITICAL risk.
3. Do not rename symbols with raw text search-and-replace; use call-graph aware refactoring tools.
4. Run `g changed` before committing to verify changes stay within the expected scope.

---

## 3. Commit history and completed milestones

Recent commits merged to `main`:

1. `d9249d0`: `fix(cleanup): resolve C-5, H-11, H-12, H-13, L-3, L-6`: Addressed log cleanup, retention policies, and audit log tables.
2. `b34bfb1`: `fix(security): resolve C-2, C-3, C-4, H-1, H-2, L-1, L-2, L-5`: Resolved SQL injection mitigations, token isolation, CORS headers, and media upload bounds.
3. `8c23cf8`: `fix(domain): resolve H-6, H-7, H-9, H-10, H-14, M-6`: Converted domain events to Java records, fixed actor auditing, and standardized imports.
4. `c650fcb`: `perf(db): resolve M-1, M-2`: Added composite database indices and `@Builder.Default` annotations.
5. `a52f7b6`: `fix(notification): resolve M-13`: Hardened SMTP fallback and error handling in notification dispatch.
6. `7c18355`: `fix(search): resolve L-12`: Standardized Elasticsearch sort field mappings.
7. `1f2ee4d`: `fix(api): resolve M-12, M-13, L-4`: API hardening, cache headers, and gateway rate limiting.
8. `87191dc`: `fix(e2e): deploy and verify all 14 microservices in docker-compose with newman suite`:
   - Fixed `RoleRepository.findByNameIn(Collection<RoleName>)` in `auth-service`.
   - Fixed Redis deserialization errors in `findAll()` and `findTree()` in `CategoryServiceImpl.java`.
   - Fixed `Duration.ofMillis(timeout)` compilation error in `media-service`.
   - Increased PostgreSQL `max_connections` to 300 to prevent connection exhaustion.
   - Updated `@JdbcTypeCode(Types.CHAR)` mapping on `TaxRate.country`.
   - Standardized inter-service environment variables (`PRODUCT_SERVICE_URL`, `INVENTORY_SERVICE_URL`) in Docker configurations.
9. `7a37f7b`: `test(e2e): add comprehensive business lifecycle Postman collection and update test report (144/144 passed)`.
10. `7ec8584`: `test(e2e): expand Postman suite with full happy and negative edge cases (158/158 passed)`:
    - Added `AuthExceptionHandler.java` to map `KeycloakClientException` to HTTP 401 Unauthorized (`ERR-0401`).
    - Added 14 negative edge cases covering price validations, empty cart quantities, invalid ISO country codes, over-allocation stock reservations, and unverified ratings.
11. `82cc8e8`: `test(e2e): achieve 100% controller endpoint coverage in Postman (110/110 endpoints, 159/159 tests passed)`:
    - Verified all 110 controller endpoints, registered internal media references, and adjusted carrier webhook parameters.

---

## 4. Postman test suites and endpoint coverage

The test suites in `docs/postman/` cover the entire API surface:

### 4.1 Suite 1: End-to-end business lifecycle and edge cases (`petproject-e2e-business-flow.postman_collection.json`)
- **Structure**: 45 requests organized across 14 logical folders.
- **Dynamic variables**: Automated chaining for UUIDs, JWT tokens, SKU codes, slugs, and idempotency keys.
- **Scope**:
  - **Folders 1 to 10 (Happy path, 31 requests)**: Authentication, catalog creation, storefront catalog browsing, stock intake, cart operations, order creation, administrative confirmation, fulfillment dispatch, delivery completion, Stripe payment capture, verified buyer reviews, wishlist updates, search reindexing, order notifications, tax and promotional discounts.
  - **Folders 11 to 14 (Edge cases and negative paths, 14 requests)**:
    - 11. Auth edge cases: Invalid passwords (401 `ERR-0401`), missing tokens (401), non-admin users calling backoffice endpoints (403 `ERR-0403`).
    - 12. Catalog edge cases: Negative product prices (400 `ERR-0422-V`), unauthorized product modifications (403), non-existent product UUIDs (404).
    - 13. Inventory edge cases: Zero quantity cart items (400 `ERR-0422-V`), missing products (404 `PRD-2001`), negative stock intake (400), stock reservation exceeds balance (409 `INV-3002`).
    - 14. Order, review, tax, and media edge cases: Reviews submitted without verified purchases (403 `RTG-11001`), out-of-range star scores (400 `ERR-0422-V`), invalid country codes (400 `ERR-0422-V`), non-multipart media uploads (400 `ERR-0400`).

### 4.2 Suite 2: Comprehensive fleet catalog (`petproject-comprehensive.postman_collection.json`)
- **Structure**: 114 requests.
- **Endpoint coverage**: 110 out of 110 implemented controller endpoints verified without unhandled 500 errors.

### 4.3 Running tests with Newman
```bash
# Run business lifecycle and edge case suite
npx --yes newman run docs/postman/petproject-e2e-business-flow.postman_collection.json

# Run comprehensive fleet suite
npx --yes newman run docs/postman/petproject-comprehensive.postman_collection.json
```

---

## 5. Engineering conventions

1. **Commit discipline**:
   - Write clear Conventional Commits for each change.
   - Push directly to `origin/main` when changes are verified.
2. **Import standards**:
   - Place all imports at the top of Java files.
   - Do not use inline fully qualified class names (such as `java.util.concurrent.atomic.AtomicReference` inside class bodies).
   - Validate Checkstyle rules: `./mvnw -T1C validate`. Maintain zero violations.
3. **Java runtime**:
   - Compile and execute using Java 25.
4. **GitNexus verification**:
   - Run `g blast <symbol> -d upstream` before editing code symbols, and run `g changed` before creating commits.

---

## 6. Current status and recommended next steps

### Current state:
- All 14 microservices build cleanly with zero Checkstyle violations.
- Docker containers report healthy status in local testing.
- All 110 API endpoints have automated test coverage in Postman and Newman.
- Test reports are documented in `docs/postman/E2E_TEST_REPORT.md` and `FINDING.md`.

### Next steps:
1. **Continuous integration**: Add Newman test execution to GitHub Actions workflows for automated verification on pull requests.
2. **Load testing**: Run stress tests using k6 against `gateway-service` (:8080) to profile Redis rate limiter behavior under high concurrency.
3. **Distributed tracing**: Validate end-to-end W3C traceparent propagation across asynchronous Kafka event consumers and producers.
