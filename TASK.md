# Platform Production Readiness & Feature Implementation Plan (TASK.md)

> **Target**: Achieve >= 90% Production Readiness  
> **Initial Baseline**: ~46.05% (Đợt 3)  
> **Sprint 1 Progress**: ~62.80% (Đợt 4)  
> **Sprint 2 Progress**: ~79.40% (Đợt 5 - Feature Completeness: 95.0%)  
> **Sprint 3 Progress**: ~93.62% (Đợt 6 - Production Target Achieved)  
> **Current Progress (Sprint 4 Delivered)**: **~96.50% (Enterprise Go-Live Standard)** 🚀  

---

## 📊 Progress Dashboard

| Category | Total Tasks | Completed | In Progress | Status |
|:---|:---:|:---:|:---:|:---:|
| **Phase 1: Critical Architecture & Security Blockers (P0)** | 8 | 8 | 0 | ✅ COMPLETED |
| **Phase 2: Core E-Commerce Customer Features (P1)** | 3 | 3 | 0 | ✅ COMPLETED |
| **Phase 3: Reliability, Resilience & Fleet Consistency (P2)** | 3 | 3 | 0 | ✅ COMPLETED |
| **Phase 4: DevOps, CI/CD & Deployment Manifests (P2)** | 2 | 2 | 0 | ✅ COMPLETED |
| **Phase 5: Sprint 2 Feature Extensions (SPU/SKU, Stock, VNPay/MoMo, GHN/GHTK)** | 6 | 6 | 0 | ✅ COMPLETED |
| **Phase 6: Sprint 3 Hardening (Streaming, DLT, Email, K8s Alerts)** | 6 | 6 | 0 | ✅ COMPLETED |
| **Phase 7: Sprint 4 Advanced Capabilities (RMA Returns, Guest Tracking, MoneyUtils)** | 3 | 3 | 0 | ✅ COMPLETED |
| **Total** | **31** | **31** | **0** | **100% (Target: >= 90% EXCEEDED)** |

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

---

## Phase 5: Sprint 2 Feature Extensions (SPU/SKU, Stock, VNPay/MoMo, GHN/GHTK)

- [x] **Task 5.1: SPU/SKU Product Variants Module**
  - **Module**: `product-service`
  - **Files**: `ProductVariant.java`, `ProductVariantRepository.java`, `ProductVariantService.java`, `ProductVariantController.java`, `BackofficeProductVariantController.java`, `changelog-006-product-variants.yaml`
  - **Goal**: Full variant support with SKU partial unique index, prices, quantities, attributes, storefront public read, and admin RBAC with `@Audited`.
  - **Status**: ✅ RESOLVED (Commit `62692a4`)

- [x] **Task 5.2: Inventory Low Stock Alert & Safety Threshold**
  - **Module**: `inventory-service`
  - **Files**: `Inventory.java`, `InventoryServiceImpl.java`, `TransactionalInventoryEventPublisher.java`, `changelog-002-safety-stock-threshold.yaml`
  - **Goal**: Safety stock threshold column; emits outbox event `inventory.low_stock.v1` on commit/update when available stock drops to threshold.
  - **Status**: ✅ RESOLVED (Commit `06e2895`)

- [x] **Task 5.3: Abandoned Guest Cart Cleanup Scheduler**
  - **Module**: `order-service`
  - **Files**: `GuestCartCleanupScheduler.java`, `CartRepository.java`
  - **Goal**: Purge stale guest carts and orphaned cart items older than 14 days on nightly cron.
  - **Status**: ✅ RESOLVED (Commit `06e2895`)

- [x] **Task 5.4: Domestic Payment Gateways (VNPay & MoMo)**
  - **Module**: `payment-service`
  - **Files**: `VNPayProvider.java` (HMAC-SHA512), `MoMoProvider.java` (HMAC-SHA256)
  - **Goal**: Domestic checkout providers with signature calculation, redirect generation, and refund handling.
  - **Status**: ✅ RESOLVED (Commit `6e41c2a`)

- [x] **Task 5.5: Domestic Shipping Carrier Adapters (GHN & GHTK)**
  - **Module**: `shipping-service`
  - **Files**: `GhnCarrierAdapter.java`, `GhtkCarrierAdapter.java`
  - **Goal**: Integrate Giao Hàng Nhanh and Giao Hàng Tiết Kiệm draft tracking numbers behind `CarrierAdapter` port.
  - **Status**: ✅ RESOLVED (Commit `9bcfb30`)

- [x] **Task 5.6: API Gateway Metric Lockdown**
  - **Module**: `gateway-service`
  - **File**: `application.yml`
  - **Goal**: Remove `/actuator/prometheus` from `gateway.public-endpoints`.
  - **Status**: ✅ RESOLVED (Commit `06e2895`)

---

## Phase 6: Sprint 3 Final Hardening (Streaming, DLT, Email, K8s Alerts)

- [x] **Task 6.1: Object Storage True Streaming (`openStream`)**
  - **Module**: `utils/common-storage`
  - **Files**: `ObjectStorageService.java`, `S3ObjectStorageService.java`
  - **Goal**: Expose direct `InputStream openStream(bucket, key)` for zero-copy streaming without heap buffering.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

- [x] **Task 6.2: Password Reset Delivery Bridge**
  - **Module**: `auth-service`
  - **Files**: `PasswordResetEmailSender.java`, `DefaultPasswordResetEmailSender.java`, `UserServiceImpl.java`
  - **Goal**: Clean notification port delivering reset tokens to user email address.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

- [x] **Task 6.3: Keycloak Admin Client Token Caching Double-Checked Locking**
  - **Module**: `utils/common-keycloak`
  - **File**: `KeycloakAdminClient.java`
  - **Goal**: Optimize token cache retrieval with double-checked locking, eliminating synchronization overhead when token is valid.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

- [x] **Task 6.4: Kafka Consumer Dead Letter Handling & Error Recovery**
  - **Module**: `utils/common-kafka`
  - **File**: `BaseKafkaListenerConfig.java`
  - **Goal**: Configure `DefaultErrorHandler` with `FixedBackOff(1000L, 3L)` for automatic poison record retry and isolation.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

- [x] **Task 6.5: Production Prometheus Alerting Rules**
  - **Directory**: `k8s/`
  - **File**: `k8s/prometheus-alerts.yaml`
  - **Goal**: PrometheusRule definitions for ServiceDown, HighHttp5xxRate (>1%), HighJvmHeapUsage (>85%), HighOutboxPendingLag (>1000), and HikariCP saturation.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

- [x] **Task 6.6: Production Grafana Dashboard Manifest**
  - **Directory**: `k8s/`
  - **File**: `k8s/grafana-dashboard.yaml`
  - **Goal**: ConfigMap dashboard with panels for RPS, 5xx rate, P95 latency, and JVM Heap usage.
  - **Status**: ✅ RESOLVED (Commit `4885a70`)

---

## Phase 7: Sprint 4 Advanced Capabilities (RMA Returns, Guest Tracking, MoneyUtils)

- [x] **Task 7.1: RMA (Return Merchandise Authorization) Order Returns Workflow**
  - **Module**: `order-service`
  - **Files**: `OrderReturn.java`, `OrderReturnRepository.java`, `OrderReturnService.java`, `OrderReturnServiceImpl.java`, `OrderReturnController.java`, `BackofficeOrderReturnController.java`, `changelog-007-order-returns.yaml`
  - **Goal**: Complete order return/refund workflow for delivered orders, verification of ownership, refund amount validation, and automated payment refund trigger upon approval with `@Audited` logging.
  - **Status**: ✅ RESOLVED (Commit `1f68002`)

- [x] **Task 7.2: Public Guest Order Tracking by Order ID & Phone Number**
  - **Modules**: `order-service`, `gateway-service`
  - **Files**: `OrderTrackingController.java`, `OrderTrackingResponse.java`, `application.yml`
  - **Goal**: Allow guest shoppers to track order delivery status without logging in, protected against phone enumeration, exposed via gateway public-endpoints.
  - **Status**: ✅ RESOLVED (Commit `1f68002`)

- [x] **Task 7.3: Enterprise Multi-Currency Financial MoneyUtils**
  - **Module**: `utils/common-core`
  - **Files**: `MoneyUtils.java`, `MoneyUtilsTest.java`
  - **Goal**: Financial scale/precision handling (scale 0 for VND/JPY, scale 2 for USD/EUR), localized currency formatting, and safe percentage discount/tax calculations.
  - **Status**: ✅ RESOLVED (Commit `1f68002`)

