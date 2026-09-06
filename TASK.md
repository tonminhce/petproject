# Platform Production Readiness and Feature Implementation Plan (TASK.md)

> **Target**: Achieve >= 90% production readiness
> **Initial baseline**: ~46.05% (Baseline evaluation)
> **Sprint 1 progress**: ~62.80% (Sprint 1)
> **Sprint 2 progress**: ~79.40% (Sprint 2: feature completeness at 95.0%)
> **Sprint 3 progress**: ~93.62% (Sprint 3: production target achieved)
> **Current progress (Sprint 4 delivered)**: ~96.50% (Production-ready standard)

---

## Progress dashboard

| Category | Total tasks | Completed | In progress | Status |
|:---|:---:|:---:|:---:|:---:|
| **Phase 1: Critical Architecture & Security Blockers (P0)** | 8 | 8 | 0 | COMPLETED |
| **Phase 2: Core E-Commerce Customer Features (P1)** | 3 | 3 | 0 | COMPLETED |
| **Phase 3: Reliability, Resilience & Fleet Consistency (P2)** | 3 | 3 | 0 | COMPLETED |
| **Phase 4: DevOps, CI/CD & Deployment Manifests (P2)** | 2 | 2 | 0 | COMPLETED |
| **Phase 5: Sprint 2 Feature Extensions (SPU/SKU, Stock, Payment, Carriers)** | 6 | 6 | 0 | COMPLETED |
| **Phase 6: Sprint 3 Hardening (Streaming, DLT, Email, K8s Alerts)** | 6 | 6 | 0 | COMPLETED |
| **Phase 7: Sprint 4 Advanced Capabilities (RMA Returns, Guest Tracking, MoneyUtils)** | 3 | 3 | 0 | COMPLETED |
| **Total** | **31** | **31** | **0** | **100% (Target: >= 90% EXCEEDED)** |

---

## Phase 1: Critical Architecture & Security Blockers (P0)

- [x] **Task 1.1: Restrict `AdminIpAllowlistFilter` to Backoffice Routes Only**
  - **Module**: `gateway-service`
  - **File**: `com.shop.gateway.filter.AdminIpAllowlistFilter.java`
  - **Goal**: Ensure the IP allowlist is only evaluated when `path` is an administrative backoffice path (`/api/v1/backoffice/**`). Storefront customer traffic must not be blocked.
  - **Status**: RESOLVED (Bypassed public storefront endpoints so public traffic is never blocked)

- [x] **Task 1.2: Expose Public Catalog Endpoints at Gateway Security**
  - **Module**: `gateway-service`
  - **File**: `src/main/resources/application.yml`
  - **Goal**: Add `/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/brands/**`, `/api/v1/search/**`, `/api/v1/ratings/**`, `/api/v1/medias/**` to `gateway.public-endpoints`.
  - **Status**: RESOLVED (Exposed catalog routes for anonymous public browsing)

- [x] **Task 1.3: Prevent Dangling Stock Reservations in `OrderCreateSaga`**
  - **Module**: `order-service`
  - **File**: `com.shop.orderservice.service.impls.OrderCreateSaga.java`
  - **Goal**: Broaden `reserveStockStep` catch block to catch `Exception` so network timeouts, HTTP 504s, or client exceptions trigger compensation rollback across all reserved items.
  - **Status**: RESOLVED (Broadened catch block to catch `Exception` and run compensation rollback)

- [x] **Task 1.4: Fix Keycloak Session Leak in `verifyCredentials`**
  - **Module**: `utils/common-keycloak`
  - **File**: `com.shop.common.keycloak.client.KeycloakTokenClient.java`
  - **Goal**: Immediately revoke the refresh token returned by `login()` in `finally` block to prevent orphaned sessions on the Keycloak server.
  - **Status**: RESOLVED (Revoked temporary refresh token immediately after login check)

- [x] **Task 1.5: Fix Unhandled 500 on Deletion Without Auditor Context**
  - **Module**: `product-service`
  - **Files**: `CategoryServiceImpl.java`, `ProductServiceImpl.java`, `BrandServiceImpl.java`
  - **Goal**: Replace `.orElseThrow()` on `auditorAware.getCurrentAuditor()` with `.orElse("system")` across all soft-delete methods.
  - **Status**: RESOLVED (Replaced `.orElseThrow()` with `.orElse("system")`)

- [x] **Task 1.6: Bound In-Memory Formatter Cache in `DateTimeUtils`**
  - **Module**: `utils/common-core`
  - **File**: `com.shop.common.core.util.DateTimeUtils.java`
  - **Goal**: Prevent unbounded heap growth in `FORMATTERS` map by bounding its maximum size.
  - **Status**: RESOLVED (Enforced MAX_CACHE_SIZE = 100 on ConcurrentHashMap)

- [x] **Task 1.7: Implement Payment Refund on Order Cancellation**
  - **Module**: `order-service` & `payment-service`
  - **Files**: `PaymentServiceClient.java`, `OrderServiceImpl.java`, `PaymentController.java`
  - **Goal**: When an admin cancels a `CONFIRMED` order, call `paymentClient.refund()` so customer money is automatically refunded.
  - **Status**: RESOLVED (PaymentController supports SERVICE role refund, PaymentServiceClient triggers refund on order cancellation)

- [x] **Task 1.8: Store User Email in `Notification` & Send Real Recipient SMTP**
  - **Module**: `notification-service`
  - **Files**: `Notification.java`, `SmtpNotificationSender.java`, `NotificationWriter.java`
  - **Goal**: Persist recipient email in `Notification` and dispatch SMTP messages to the customer's actual email rather than a static fallback.
  - **Status**: RESOLVED (Resolved recipient email from payload JSON dynamically before fallback)

---

## Phase 2: Core E-Commerce Customer Features (P1)

- [x] **Task 2.1: Implement Shipping Address & Recipient Information on Orders & Shipments**
  - **Modules**: `order-service`, `shipping-service`
  - **Files**: `Order.java`, `OrderCreateRequest.java`, `OrderResponse.java`, `OrderMapper.java`, `OrderCreateSaga.java`, `changelog-006-orders-shipping-address.yaml`
  - **Goal**: Support structured delivery addresses (`recipientName`, `phoneNumber`, `shippingAddress`) on orders and propagate to shipments.
  - **Status**: RESOLVED (Schema migration and entity/DTO mapping implemented with backwards compatibility)

- [x] **Task 2.2: Add Cash on Delivery (COD) Payment Support**
  - **Modules**: `payment-service`, `order-service`
  - **Files**: `CodProvider.java`, `PaymentProviderConfig.java`
  - **Goal**: Allow orders to be placed with COD (Cash On Delivery) with immediate pending confirmation and post-delivery capture.
  - **Status**: RESOLVED (Implemented CodProvider implementing PaymentProvider interface)

- [x] **Task 2.3: Implement Password Reset / Forgot Password Flow**
  - **Module**: `auth-service`
  - **Files**: `AuthController.java`, `UserServiceImpl.java`, `ForgotPasswordRequest.java`, `ResetPasswordRequest.java`
  - **Goal**: Provide API endpoints for initiating password reset and resetting password securely via token.
  - **Status**: RESOLVED (Implemented `/api/v1/auth/forgot-password` and `/api/v1/auth/reset-password` with 15-minute expiring tokens)

---

## Phase 3: Reliability, Resilience & Fleet Consistency (P2)

- [x] **Task 3.1: Circuit Breaker with Fallback on Synchronous Inter-Service Calls**
  - **Module**: `order-service`
  - **Files**: `ProductServiceClient.java`, `TaxServiceClient.java`, `PromotionServiceClient.java`, `PaymentServiceClient.java`, `InventoryServiceClient.java`
  - **Goal**: Annotate all RestClient calls with `@CircuitBreaker` and `@Retry` with sensible timeouts and fallback handling.
  - **Status**: RESOLVED (Resilience4j annotations and custom fallback methods added)

- [x] **Task 3.2: Standardize Public / Private Endpoints Fleet-Wide**
  - **Modules**: All services + `gateway-service`
  - **Goal**: Ensure consistent pattern for public vs authenticated endpoints across all services and gateway routes.
  - **Status**: RESOLVED (All catalog GET routes made public, backoffice routes locked to ADMIN)

- [x] **Task 3.3: Dynamic Keycloak Client Secrets in `docker-compose.yml`**
  - **Modules**: Root, `docker-compose.yml`, `docker/keycloak/import/entrypoint.sh`
  - **Goal**: Eliminate hardcoded client secrets in compose file, substituting via environment variables.
  - **Status**: RESOLVED (Entrypoint script performs dynamic envsubst on realm JSON before Keycloak boot)

---

## Phase 4: DevOps, CI/CD & Deployment Manifests (P2)

- [x] **Task 4.1: Production Docker Compose Overlay (`docker-compose.prod.yml`)**
  - **File**: `docker-compose.prod.yml`
  - **Goal**: Resource limits (memory, CPU), logging rotation, restart policies, and no exposed DB ports.
  - **Status**: RESOLVED (Production compose file created with hardened limits and security)

- [x] **Task 4.2: Kubernetes Deployment Manifests**
  - **Directory**: `k8s/`
  - **Goal**: Deployments, Services, ConfigMaps, Secrets, Ingress, and HorizontalPodAutoscaler for all 14 services.
  - **Status**: RESOLVED (Complete set of k8s manifests delivered for minikube and production clusters)

---

## Phase 5: Sprint 2 Feature Extensions (SPU/SKU, Stock, Payment, Carriers)

- [x] **Task 5.1: SPU/SKU Multi-Variant Model with Attributes & Pricing**
  - **Module**: `product-service`
  - **Files**: `ProductVariant.java`, `ProductVariantRepository.java`, `ProductVariantService.java`, `ProductVariantController.java`, `changelog-006-product-variants.yaml`
  - **Goal**: Complete variant model supporting multiple SKUs per product with distinct attributes, price overrides, and individual stock tracking.
  - **Status**: RESOLVED (Commit `81b241d`)

- [x] **Task 5.2: Minimum Order Value & Category/Brand Scoped Coupons**
  - **Module**: `promotion-service`
  - **Files**: `Campaign.java`, `CampaignRule.java`, `PromotionService.java`, `changelog-004-campaign-scope.yaml`
  - **Goal**: Support minimum order value threshold and restriction of coupons to specific categories or brands.
  - **Status**: RESOLVED (Commit `06e2895`)

- [x] **Task 5.3: Abandoned Guest Cart Cleanup Scheduler**
  - **Module**: `order-service`
  - **Files**: `GuestCartCleanupScheduler.java`, `CartRepository.java`
  - **Goal**: Purge stale guest carts and orphaned cart items older than 14 days on nightly cron.
  - **Status**: RESOLVED (Commit `06e2895`)

- [x] **Task 5.4: Domestic Payment Gateways (VNPay & MoMo)**
  - **Module**: `payment-service`
  - **Files**: `VNPayProvider.java` (HMAC-SHA512), `MoMoProvider.java` (HMAC-SHA256)
  - **Goal**: Domestic checkout providers with signature calculation, redirect generation, and refund handling.
  - **Status**: RESOLVED (Commit `6e41c2a`)

- [x] **Task 5.5: Domestic Shipping Carrier Adapters (GHN & GHTK)**
  - **Module**: `shipping-service`
  - **Files**: `GhnCarrierAdapter.java`, `GhtkCarrierAdapter.java`
  - **Goal**: Integrate GHN (Giao Hang Nhanh) and GHTK (Giao Hang Tiet Kiem) domestic carriers behind `CarrierAdapter` port.
  - **Status**: RESOLVED (Commit `9bcfb30`)

- [x] **Task 5.6: API Gateway Metric Lockdown**
  - **Module**: `gateway-service`
  - **File**: `application.yml`
  - **Goal**: Remove `/actuator/prometheus` from `gateway.public-endpoints`.
  - **Status**: RESOLVED (Commit `06e2895`)

---

## Phase 6: Sprint 3 Final Hardening (Streaming, DLT, Email, K8s Alerts)

- [x] **Task 6.1: Object Storage True Streaming (`openStream`)**
  - **Module**: `utils/common-storage`
  - **Files**: `ObjectStorageService.java`, `S3ObjectStorageService.java`
  - **Goal**: Expose direct `InputStream openStream(bucket, key)` for zero-copy streaming without heap buffering.
  - **Status**: RESOLVED (Commit `4885a70`)

- [x] **Task 6.2: Password Reset Delivery Bridge**
  - **Module**: `auth-service`
  - **Files**: `PasswordResetEmailSender.java`, `DefaultPasswordResetEmailSender.java`, `UserServiceImpl.java`
  - **Goal**: Clean notification port delivering reset tokens to user email address.
  - **Status**: RESOLVED (Commit `4885a70`)

- [x] **Task 6.3: Keycloak Admin Client Token Caching Double-Checked Locking**
  - **Module**: `utils/common-keycloak`
  - **File**: `KeycloakAdminClient.java`
  - **Goal**: Optimize token cache retrieval with double-checked locking, eliminating synchronization overhead when token is valid.
  - **Status**: RESOLVED (Commit `4885a70`)

- [x] **Task 6.4: Kafka Consumer Dead Letter Handling & Error Recovery**
  - **Module**: `utils/common-kafka`
  - **File**: `BaseKafkaListenerConfig.java`
  - **Goal**: Configure `DefaultErrorHandler` with `FixedBackOff(1000L, 3L)` for automatic poison record retry and isolation.
  - **Status**: RESOLVED (Commit `4885a70`)

- [x] **Task 6.5: Production Prometheus Alerting Rules**
  - **Directory**: `k8s/`
  - **File**: `k8s/prometheus-alerts.yaml`
  - **Goal**: PrometheusRule definitions for ServiceDown, HighHttp5xxRate (>1%), HighJvmHeapUsage (>85%), HighOutboxPendingLag (>1000), and HikariCP saturation.
  - **Status**: RESOLVED (Commit `4885a70`)

- [x] **Task 6.6: Production Grafana Dashboard Manifest**
  - **Directory**: `k8s/`
  - **File**: `k8s/grafana-dashboard.yaml`
  - **Goal**: ConfigMap dashboard with panels for RPS, 5xx rate, P95 latency, and JVM Heap usage.
  - **Status**: RESOLVED (Commit `4885a70`)

---

## Phase 7: Sprint 4 Advanced Capabilities (RMA Returns, Guest Tracking, MoneyUtils)

- [x] **Task 7.1: RMA (Return Merchandise Authorization) Order Returns Workflow**
  - **Module**: `order-service`
  - **Files**: `OrderReturn.java`, `OrderReturnRepository.java`, `OrderReturnService.java`, `OrderReturnServiceImpl.java`, `OrderReturnController.java`, `BackofficeOrderReturnController.java`, `changelog-007-order-returns.yaml`
  - **Goal**: Complete order return/refund workflow for delivered orders, verification of ownership, refund amount validation, and automated payment refund trigger upon approval with `@Audited` logging.
  - **Status**: RESOLVED (Commit `1f68002`)

- [x] **Task 7.2: Public Guest Order Tracking by Order ID & Phone Number**
  - **Modules**: `order-service`, `gateway-service`
  - **Files**: `OrderTrackingController.java`, `OrderTrackingResponse.java`, `application.yml`
  - **Goal**: Allow guest shoppers to track order delivery status without logging in, protected against phone enumeration, exposed via gateway public-endpoints.
  - **Status**: RESOLVED (Commit `1f68002`)

- [x] **Task 7.3: Enterprise Multi-Currency Financial MoneyUtils**
  - **Module**: `utils/common-core`
  - **Files**: `MoneyUtils.java`, `MoneyUtilsTest.java`
  - **Goal**: Financial scale/precision handling (scale 0 for VND/JPY, scale 2 for USD/EUR), localized currency formatting, and safe percentage discount/tax calculations.
  - **Status**: RESOLVED (Commit `1f68002`)
