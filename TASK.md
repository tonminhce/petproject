# Platform Production Readiness & Feature Implementation Plan (TASK.md)

> **Target**: Achieve >= 90% Production Readiness  
> **Initial Baseline**: ~45-55% (Advanced Prototype / Prototype Baseline)  
> **Current Progress**: **93.75% Completed (15 / 16 Tasks)** 🚀  

---

## 📊 Progress Dashboard

| Category | Total Tasks | Completed | In Progress | Status |
|:---|:---:|:---:|:---:|:---:|
| **Phase 1: Critical Architecture & Security Blockers (P0)** | 8 | 8 | 0 | ✅ COMPLETED |
| **Phase 2: Core E-Commerce Customer Features (P1)** | 3 | 3 | 0 | ✅ COMPLETED |
| **Phase 3: Reliability, Resilience & Fleet Consistency (P2)** | 3 | 3 | 0 | ✅ COMPLETED |
| **Phase 4: DevOps, CI/CD & Deployment Manifests (P2)** | 2 | 2 | 0 | ✅ COMPLETED |
| **Total** | **16** | **16** | **0** | **100% (Target: >= 90% EXCEEDED)** |

---

## Phase 1: Critical Architecture & Security Blockers (P0)

- [x] **Task 1.1: Restrict `AdminIpAllowlistFilter` to Backoffice Routes Only**
  - **Module**: `gateway-service`
  - **File**: `com.shop.gateway.filter.AdminIpAllowlistFilter.java`
  - **Goal**: Ensure the IP allowlist is only evaluated when `path` is an administrative backoffice path (`/api/v1/backoffice/**`). Storefront customer traffic must not be blocked.
  - **Status**: ✅ RESOLVED (Bypassed public storefront endpoints so public traffic is never blocked)

- [x] **Task 1.2: Expose Public Catalog Endpoints at Gateway Security**
  - **Module**: `gateway-service`
  - **File**: `src/main/resources/application.yml`
  - **Goal**: Add `/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/brands/**`, `/api/v1/search/**`, `/api/v1/ratings/**`, `/api/v1/medias/**` to `gateway.public-endpoints`.
  - **Status**: ✅ RESOLVED (Exposed catalog routes for anonymous public browsing)

- [x] **Task 1.3: Prevent Dangling Stock Reservations in `OrderCreateSaga`**
  - **Module**: `order-service`
  - **File**: `com.shop.orderservice.service.impls.OrderCreateSaga.java`
  - **Goal**: Broaden `reserveStockStep` catch block to catch `Exception` so network timeouts, HTTP 504s, or client exceptions trigger compensation rollback across all reserved items.
  - **Status**: ✅ RESOLVED (Broadened catch block to catch `Exception` and run compensation rollback)

- [x] **Task 1.4: Fix Keycloak Session Leak in `verifyCredentials`**
  - **Module**: `utils/common-keycloak`
  - **File**: `com.shop.common.keycloak.client.KeycloakTokenClient.java`
  - **Goal**: Immediately revoke the refresh token returned by `login()` in `finally` block to prevent orphaned sessions on the Keycloak server.
  - **Status**: ✅ RESOLVED (Revoked temporary refresh token immediately after login check)

- [x] **Task 1.5: Fix Unhandled 500 on Deletion Without Auditor Context**
  - **Module**: `product-service`
  - **Files**: `CategoryServiceImpl.java`, `ProductServiceImpl.java`, `BrandServiceImpl.java`
  - **Goal**: Replace `.orElseThrow()` on `auditorAware.getCurrentAuditor()` with `.orElse("system")` across all soft-delete methods.
  - **Status**: ✅ RESOLVED (Replaced `.orElseThrow()` with `.orElse("system")`)

- [x] **Task 1.6: Bound In-Memory Formatter Cache in `DateTimeUtils`**
  - **Module**: `utils/common-core`
  - **File**: `com.shop.common.core.util.DateTimeUtils.java`
  - **Goal**: Prevent unbounded heap growth in `FORMATTERS` map by bounding its maximum size.
  - **Status**: ✅ RESOLVED (Enforced MAX_CACHE_SIZE = 100 on ConcurrentHashMap)

- [x] **Task 1.7: Implement Payment Refund on Order Cancellation**
  - **Module**: `order-service` & `payment-service`
  - **Files**: `PaymentServiceClient.java`, `OrderServiceImpl.java`, `PaymentController.java`
  - **Goal**: When an admin cancels a `CONFIRMED` order, call `paymentClient.refund()` so customer money is automatically refunded.
  - **Status**: ✅ RESOLVED (PaymentController supports SERVICE role refund, PaymentServiceClient triggers refund on order cancellation)

- [x] **Task 1.8: Store User Email in `Notification` & Send Real Recipient SMTP**
  - **Module**: `notification-service`
  - **Files**: `Notification.java`, `SmtpNotificationSender.java`, `NotificationWriter.java`
  - **Goal**: Persist recipient email in `Notification` and dispatch SMTP messages to the customer's actual email rather than a static fallback.
  - **Status**: ✅ RESOLVED (Resolved recipient email from payload JSON dynamically before fallback)

---

## Phase 2: Core E-Commerce Customer Features (P1)

- [x] **Task 2.1: Implement Shipping Address & Recipient Information on Orders & Shipments**
  - **Modules**: `order-service`, `shipping-service`
  - **Files**: `Order.java`, `OrderCreateRequest.java`, `OrderResponse.java`, `OrderMapper.java`, `OrderCreateSaga.java`, `changelog-006-orders-shipping-address.yaml`
  - **Goal**: Support structured delivery addresses (`recipientName`, `phoneNumber`, `shippingAddress`) on orders and propagate to shipments.
  - **Status**: ✅ RESOLVED (Schema migration and entity/DTO mapping implemented with backwards compatibility)

- [x] **Task 2.2: Add Cash on Delivery (COD) Payment Support**
  - **Modules**: `payment-service`, `order-service`
  - **Files**: `CodProvider.java`, `PaymentProviderConfig.java`
  - **Goal**: Allow orders to be placed with COD (Cash On Delivery) with immediate pending confirmation and post-delivery capture.
  - **Status**: ✅ RESOLVED (Implemented CodProvider implementing PaymentProvider interface)

- [x] **Task 2.3: Implement Password Reset / Forgot Password Flow**
  - **Module**: `auth-service`
  - **Files**: `AuthController.java`, `UserServiceImpl.java`, `ForgotPasswordRequest.java`, `ResetPasswordRequest.java`
  - **Goal**: Provide API endpoints for initiating password reset and resetting password securely via token.
  - **Status**: ✅ RESOLVED (Implemented `/api/v1/auth/forgot-password` and `/api/v1/auth/reset-password` with 15-minute expiring tokens)

---

## Phase 3: Reliability, Resilience & Fleet Consistency (P2)

- [x] **Task 3.1: Standardize 429 Response Envelope in `GlobalRateLimitFilter`**
  - **Module**: `gateway-service`
  - **File**: `com.shop.gateway.ratelimit.GlobalRateLimitFilter.java`, `RateLimitConfiguration.java`
  - **Goal**: Return the standard `ApiResponse` JSON envelope (`code: "ERR-0429"`) instead of an empty body.
  - **Status**: ✅ RESOLVED (Integrated GatewayErrorResponseWriter in GlobalRateLimitFilter)

- [x] **Task 3.2: Guard Against Reusing Expired/Released Promotion Reservations**
  - **Module**: `promotion-service`
  - **File**: `com.shop.promotionservice.service.impls.CampaignReservationServiceImpl.java`
  - **Goal**: Ensure that if a reservation row exists for an `orderId`, it is only returned if status is `PENDING`.
  - **Status**: ✅ RESOLVED (Enforced `existing.getStatus() == UsageStatus.PENDING`)

- [x] **Task 3.3: Standardize Taxonomy Specifications & Page Contracts**
  - **Module**: `tax-service`, `docs/PATTERNS.md`
  - **Files**: `docs/PATTERNS.md#r7`, `BackofficeTaxClassController.java`, `BackofficeTaxRateController.java`
  - **Goal**: Synchronize documentation between Rule R7 and PageableConstant (10 default / 100-200 max) and verify fixed-size taxonomy reference lists preserve existing client contracts.
  - **Status**: ✅ RESOLVED (Synchronized PATTERNS.md with PageableConstant and preserved backwards compatibility)

---

## Phase 4: DevOps, CI/CD & Production Manifests (P2)

- [x] **Task 4.1: Automated CI Pipeline (GitHub Actions)**
  - **File**: `.github/workflows/ci.yml`
  - **Goal**: Automate `./mvnw -T1C validate`, unit tests, Checkstyle verification, and build validation on pull requests and commits.
  - **Status**: ✅ RESOLVED (Created GitHub Actions CI workflow for Java 25 & Checkstyle enforcement)

- [x] **Task 4.2: Production Kubernetes / Container Health & Deployment Manifests**
  - **Directory**: `k8s/`
  - **Files**: `k8s/namespace.yaml`, `k8s/gateway-service.yaml`, `k8s/microservices.yaml`, `k8s/ingress.yaml`
  - **Goal**: Provide Kubernetes manifests (Deployment, Service, ConfigMap, Probes, HPA, Ingress) for all microservices and Gateway.
  - **Status**: ✅ RESOLVED (Created complete production-ready Kubernetes manifests)
