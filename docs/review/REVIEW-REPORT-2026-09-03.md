# Bao Cao Code Review Tong Hop (Pass Moi - Danh Gia Doc Lap)

> **Ngay thuc hien:** 2026-09-03
> **Phuong phap:** 7 subagents review song song theo 2 truc:
> - **Truc module (5 agents):** auth+favourite+gateway, product+search+notification, order+payment+inventory, shipping+tax+promotion, rating+common-utils
> - **Truc chuyen gia (2 agents):** Security specialist, Performance specialist
>
> **Cach tiep can khac voi review cu (2026-09-02):**
> - Danh gia DOC LAP hoan toan - khong tham chieu bao cao truoc
> - Bo sung goc nhin chuyen sau Security (p6) va Performance (p7)
> - Tap trung vao: SOLID chi tiet, code quality metrics, test quality, DRY, documentation
> - Pattern baseline: 9 quy tac fleet auto-detected tu favourite-service
>
> **Loai tru:** media-service, docs/review/.

---

## 1. Tong Quan So Lieu (CHUA DEDUP)

| Module / Aspect | Critical | High | Medium | Low | Tong |
|---|---|---|---|---|---|
| auth + favourite + gateway | 3 | 6 | 8 | 8 | **25** |
| product + search + notification | 8 | 16 | 20 | 25 | **69** |
| order + payment + inventory | 5 | 8 | 10 | 10 | **33** |
| shipping + tax + promotion | 7 | 12 | 18 | 18 | **55** |
| rating + common-utils | 3 | 6 | 10 | 10 | **29** |
| Security specialist (cross-cutting) | 5 | 10 | 12 | 10 | **37** |
| Performance specialist (cross-cutting) | 5 | 10 | 24 | 19 | **58** |
| **TONG CONG (raw)** | **36** | **68** | **102** | **100** | **306** |

---

## 2. Cross-Cutting Themes (Xuat Hien O Nhieu Reviews)

### T1. JWT thieu validation aud claim (p5, p6, p7)
- p5 C2 + p6 C1 - cung vi tri BaseSecurityConfig.java:93-95
- Token Keycloak cua client A duoc chap nhan boi service B -> horizontal privilege escalation
- Fix: Build DelegatingOAuth2TokenValidator voi JwtIssuerValidator + JwtClaimValidator cho aud

### T2. Outbox race condition khi scale multi-instance (p2, p3, p5, p7)
- p2 C4, p3 outbox topic naming, p5 H1, p7 C5/M3/M16
- Multi-pod deployment -> duplicate publish -> downstream consumer nhan 2 lan
- Fix: Them @Version, dung SELECT ... FOR UPDATE SKIP LOCKED, hoac CDC (Debezium)

### T3. Webhook security: thieu timestamp/replay protection (p3, p4, p6)
- p3 C1 webhook secret default empty + H5 signature prefix rejection
- p4 C1 shipping shipment event CHECK constraint mismatch
- p6 H1 (10/10 confidence)
- Replay webhook vinh vien -> payment/shipping state manipulation
- Fix: Stripe-style signature t=<unix_ts>,v1=<hmac> + reject neu |now - t| > 300s

### T4. Connection leak: HTTP call ben trong @Transactional (p1, p2, p3, p7)
- p1 C1 register() hold TX across Keycloak HTTP call
- p3 C2 capture()/refund() in @Transactional, C3 commit coordinator sequential HTTP
- p7 C1, C2, C3 (chi tiet nhat - chi ra 10+ vi tri)
- HikariCP pool exhaustion khi load tang -> deadlock-by-starvation
- Fix: Split transaction: TX1 (DB write) -> no-TX (HTTP) -> TX2 (DB write)

### T5. Kafka serializer/deserializer chain co van de (p2, p5, p6, p7)
- p5 C1 addTrustedPackages("*") deserialization gadget + C3 DLT config dead + M6 silent string fallback
- p7 H5 producer missing batch/linger/compression + H9 future.get(10s) per event
- Fix: Trusted packages cu the com.shop.*, wire DLT, producer batch.size=32K + linger.ms=20

### T6. Hardcoded secrets/default credentials (p3, p6, p7)
- p3 C1 webhook secret empty default, H4 env var defaults changeme
- p6 C3 secret:"changeme" trong realm JSON + C5 PAYMENT_WEBHOOK_SECRET=local-test-secret
- Auth bypass fleet-wide, payment fraud
- Fix: Fail-fast ${VAR:?must be set} - KHONG default changeme

### T7. IP spoofing qua X-Forwarded-For first entry (p1, p6)
- p1 H4 RateLimitFilter unbounded + XFF first entry
- p6 H2 (9/10 confidence) + M6
- Rate limit bypass, IP allowlist bypass
- Fix: XFF entry o index (XFF.length - trustedProxyHops)

### T8. Status update order: ghi state TRUOC khi xay ra (p2, p4, p6, p7)
- p2 C2 notification insert SENT truoc khi send, C7 khong retry/DLQ
- p4 shipping auto-DELIVERED sau 7 ngay khong co buoc xac nhan
- Fix: State machine ro rang: PENDING -> PROCESSING -> SENT. Co retry/DLQ table

### T9. API contract: hardcoded path thay vi ApiPaths.* (p1, p2, p4, p6)
- p2 C3 storefront controller @PreAuthorize lan lon backoffice operations
- p4 C5 tax TaxCalculationController mount sai path
- Fix: Luon dung @RequestMapping(ApiPaths.*)

### T10. Test quality: addFilters=false che loi auth (p1, p2, p3, p6)
- p1 favourite khong test authorization
- p6 H7 actuator prometheus public, khong co test security matrix
- Fix: Slice test phai addFilters=true cho endpoint auth-gated; test security matrix 401/403

---

## 3. Top 20 Critical Findings (Uu Tien Fix Ngay)

### C1. JWT aud claim khong validate (p5 C2, p6 C1)
- File: utils/common-security/.../BaseSecurityConfig.java:93-95
- Mo ta: NimbusJwtDecoder chi check iss + exp + nbf, KHONG check aud
- Attack: Token SPA-issued (aud=ecommerce-client) submit toi backend service khac -> duoc chap nhan
- Impact: Cross-client token reuse, horizontal privilege escalation

### C2. Plaintext password log ra INFO (p6 C2)
- File: auth-service/.../service/impls/UserServiceImpl.java:42-44
- Mo ta: @LogPerformance(logInput=true) + RegisterRequest toString() -> password vao log
- Confidence: 10/10

### C3. Webhook payment secret default empty (p3 C1)
- File: payment-service/src/main/resources/application.yml:47
- Mo ta: webhookSecret empty default -> moi legit webhook 401 tren bat ky env nao thieu PAYMENT_WEBHOOK_SECRET

### C4. DB CHECK constraint ck_payment_events_status (p3 C3)
- File: payment-service/.../db/changelog/changelog-001-payments.yaml:70
- Mo ta: Moi INSERT voi status FAILED_RETRYABLE crash DataIntegrityViolationException
- Fix: New changeset drop & re-add CHECK bao gom 4 status

### C5. StripeProvider.capture/refund throw UnsupportedOperationException (p3 C2)
- File: payment-service/.../provider/StripeProvider.java:34-41
- Mo ta: Production default = stripe nhung StripeProvider stub -> prod non-functional

### C6. IdempotencyServiceImpl.begin() mo 2 Hikari connections (p3 C5)
- File: order-service/.../service/impls/IdempotencyServiceImpl.java:51-71
- Mo ta: Default pool=10 -> chi support 5 concurrent order-creations

### C7. PaymentServiceImpl.capture/refund bo ProviderResult.accepted (p3 C4)
- File: payment-service/.../service/impls/PaymentServiceImpl.java:48-64
- Mo ta: Synchronous capture API = no-op; chi webhook drives PENDING->CAPTURED

### C8. Keycloak realm import hardcode secret:"changeme" (p6 C3)
- File: docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 36
- Confidence: 10/10
- Attack: Ai co repo cung auth duoc nhu bat ky service account nao

### C9. CORS wildcard + allowCredentials tren gateway (p6 C4)
- File: gateway-service/.../config/SecurityConfig.java:54-62
- Confidence: 9/10

### C10. PAYMENT_WEBHOOK_SECRET default trong docker-compose (p6 C5)
- File: docker-compose.yml:369, 397
- Confidence: 10/10
- Attack: HMAC-SHA256(body, "local-test-secret") -> forge webhook bat ky

### C11. Parent category cycle khong phat hien (p2 C1)
- File: product-service/.../service/impls/CategoryServiceImpl.java:97-112

### C12. Notification status SENT ghi truoc khi send (p2 C2)
- File: notification-service/.../service/impls/NotificationServiceImpl.java:49, 65-71
- Impact: SMTP loi 5 phut = mat vinh vien notifications trong window

### C13. Storefront Controller co @PreAuthorize hasRole ADMIN (p2 C3)
- Files: 3 storefront controllers trong product-service
- Mo ta: Vi pham rule 5 - POST/PUT/DELETE lan GET public

### C14. OutboxRelay khong khoa PENDING rows (p2 C4)
- File: product-service/.../service/OutboxRelay.java:49-85

### C15. Reindex race + Integer.parseInt (p2 C5)
- File: search-service/.../service/impls/ReindexServiceImpl.java:133-144

### C16. Notification entity thieu @SQLRestriction (p2 C6)
- File: notification-service/.../entity/Notification.java:22-29

### C17. Notification insert khong co PENDING/retry/DLQ (p2 C7)
- File: notification-service/.../service/impls/NotificationServiceImpl.java

### C18. ProductSearchService.index/delete nem raw IllegalStateException (p2 C8)
- File: search-service/.../service/ProductSearchService.java:47, 63

### C19. Deserialization gadget addTrustedPackages("*") (p5 C1)
- File: utils/common-kafka/.../BaseKafkaListenerConfig.java:79
- Mo ta: Cho phep deserialize bat ky class -> RCE

### C20. DLT/retry config DEAD - defined but not wired (p5 C3)
- Files: utils/common-kafka/.../KafkaProperties.java:160-183 + listener config
- Mo ta: Retry block khai bao nhung khong ai doc -> poison record stall partition

---

## 4. Top 50 High Findings (Fix Trong Sprint Ke Tiep)

| # | Vi tri | Van de | Module |
|---|---|---|---|
| H1 | auth-service/.../UserServiceImpl.java:42-56 | register() holds DB transaction across Keycloak HTTP call | p1 |
| H2 | auth-service/.../UserServiceImpl.java:193-199 | rollbackKeycloakUser swallows Exception with empty catch | p1 |
| H3 | 8 auth-service DTOs @Getter/@Setter thay vi record | Pattern violation | p1 |
| H4 | gateway-service/.../filter/RateLimitFilter.java:49,76 | Unbounded ConcurrentHashMap keyed by XFF -> DoS | p1 |
| H5 | auth-service/.../UserController.java:60-66 | getCurrentAuthenticatedUser dung jwt.getSubject() (UUID) lam username -> 404 | p1, p6 |
| H6 | auth-service/.../UserController.java:40,54,62 | @PreAuthorize hasAuthority('ADMIN') method-level (lech fleet) | p1 |
| H7 | rating-service/.../outbox/RatingOutboxRelay.java:54 | break on first failure blocks subsequent events | p5 |
| H8 | payment-service/.../webhook/WebhookSignatureVerifier.java:18-37 | Reject sha256=/v1= prefix | p3 |
| H9 | order-service/.../OrderServiceImpl.java:252-289 | Admin cancelOrder(CONFIRMED) khong goi /release-committed | p3 |
| H10 | inventory-service/.../InventoryServiceImpl.java:121-148 | Reserve TOCTOU window | p3, p7 |
| H11 | payment-service/.../outbox/ | Khong co OutboxRetentionScheduler -> outbox table grows unbounded | p3 |
| H12 | order-service/.../OrderStatusServiceImpl.java:19-27 | Status machine blocks SHIPPED -> CANCELLED | p3 |
| H13 | order-service/.../OrderServiceImpl.java:101-156 | doCreateOrder private method via self-invocation | p3 |
| H14 | order-service/.../impls/PricingServiceImpl.java:46-52 | Sequential product-fetch loop in pricing | p3, p7 |
| H15 | order-service/.../OrderCommitCoordinator.java:28-67 | Sequential HTTP calls in confirm TX | p3, p7 |
| H16 | product-service/.../CategoryServiceImpl.java:79-92 | create() khong check depth/cycle | p2 |
| H17 | product-service/.../service/impls/ProductServiceImpl.java:135,164 | @CacheEvict allEntries=true cache stampede | p2, p7 |
| H18 | search-service/.../service/ProductSearchService.java:47,63 | index/delete IllegalStateException khong map BusinessException | p2 |
| H19 | notification-service/.../service/impls/NotificationServiceImpl.java:38 | UUID.fromString NPE/IAE khong catch -> poison posture | p2 |
| H20 | product-service/.../controller/ProductController.java:38-48 | storefront findAll khong cap size | p2, p6 |
| H21 | product-service/.../service/impls/ProductServiceImpl.java:72-86 | filterSpec khong default status=ACTIVE cho storefront | p2 |
| H22 | notification-service/.../service/sender/SmtpNotificationSender.java:39 | Hardcoded fallbackRecipient ignore notification.userId | p2 |
| H23 | notification-service/.../service/NotificationWriter.java:13 | @Repository stereotype sai | p2 |
| H24 | search-service/.../service/impls/ReindexServiceImpl.java:184-199 | deleteSupersededIndices nuot loi silently | p2 |
| H25 | product-service/.../controller/BackofficeProductController.java:37,43 | Redundant class-level isAuthenticated() + method-level hasAnyRole | p2 |
| H26 | notification-service/.../dto/OrderLifecycleEvent.java:14-34 | Dung Lombok class thay record | p2 |
| H27 | payment-service/.../webhook/PaymentWebhookController.java + shipping | Webhook signature no timestamp/replay protection | p3, p6 |
| H28 | gateway-service/.../filter/ClientIpResolver.java:23-37 | XFF first entry spoofing | p1, p6 |
| H29 | payment-service/.../repository/PaymentRepository.java:15 | findByIdempotencyKey global scope -> cross-user leak | p6 |
| H30 | auth-service/src/main/resources/application.yml:39-40 | /api/v1/auth/** wildcard public path landmine | p6 |
| H31 | utils/common-storage/.../service/S3ObjectStorageService.java:110-126 | download() ResponseInputStream khong try-with-resources -> fd leak | p5 |
| H32 | utils/common-spring/.../web/exception/ApiExceptionHandler.java:195-208 | Raw DB constraint message leak | p5, p6 |
| H33 | utils/common-security/.../config/BaseSecurityConfig.java:93-95 | JWT decoder synchronous discovery block first request | p5 |
| H34 | docker-compose.yml:124 | ES xpack.security.enabled=false | p6 |
| H35 | .env:106-107 | HTTP_LOG_REQUEST_BODY=true leaks passwords | p6 |
| H36 | Dockerfiles (all) | Services run as root | p6 |
| H37 | search-service/.../service/impls/SearchQueryServiceImpl.java:97-102 | ES deep pagination from+size hits 10K wall | p7 |
| H38 | order-service/.../service/OrderReconciliationScheduler.java:91-148 | N+1 HTTP per order + unbounded load | p7 |
| H39 | promotion-service/.../db/changelog/changelog-001-initial-schema.yaml:99-122 | Missing (campaign_id, status) index | p7 |
| H40 | payment-service/.../db/changelog/changelog-003-webhook-retry.yaml | Missing (status, next_retry_at) index | p7 |
| H41 | utils/common-kafka/.../KafkaProperties.java:99-108 | Producer missing batch/linger/compression/idempotence | p7 |
| H42 | product-service/.../service/impls/ProductServiceImpl.java:134-135 | allEntries=true evict cache stampede | p7 |
| H43 | notification-service/.../service/impls/NotificationServiceImpl.java:36-71 | existsByEventId + insert TOCTOU | p7 |
| H44 | utils/common-kafka/.../producer/KafkaMessagePublisher.java:85-98 | Synchronous per-event future.get(10s) trong outbox relays | p7 |
| H45 | inventory-service/.../service/impls/InventoryServiceImpl.java:123-147 | releaseExpiredReservations() runs on every reserve | p7 |
| H46 | order-service/.../OrderReconciliationScheduler.java:181-182 | stuckPendingCount() runs on every Prometheus scrape | p7 |
| H47 | rating-service/.../db/changelog/changelog-001-ratings.yaml:53-56 | No partial index on outbox PENDING | p7 |
| H48 | shipping-service/.../db/changelog/changelog-001-initial-schema.yaml | ck_shipment_events_status missing FAILED_RETRYABLE/PERMANENT | p4 |
| H49 | promotion-service | Reservation khong idempotent theo orderId | p4, p3 |
| H50 | tax-service/.../controller/TaxCalculationController | Mounted under BACKOFFICE_TAX_RATES path lech convention | p4 |

---

## 5. Pattern Compliance Summary

| Quy tac | auth+gateway | favourite | product+search+notification | order+payment+inventory | shipping+tax+promotion | rating+common-utils |
|---|---|---|---|---|---|---|
| 1. Layer + records | 8 DTOs khong record | OK | OK | OK | shipping mutable POJOs | OK |
| 2. ApiResponse/PageResponse | OK | OK | OK | OK | tax fail (H5) | OK |
| 3. @RequestMapping(ApiPaths.*) | hardcoded paths | OK | OK | OK | tax mount sai | OK |
| 4. Cap page size | user/findAll | OK | storefront ProductController | OK | tax list no pagination | OK |
| 5. Storefront no / Backoffice ADMIN | hasAuthority vs hasRole | OK | VIOLATION (C3) | OK | tax C5 | OK |
| 6. AuthenticatedUser.requireCurrent() | jwt.getSubject() | OK | auditorAware | OK | N/A | OK |
| 7. @Transactional + @SQLRestriction | RoleServiceImpl missing | OK | Notification entity (C6) | OK | OK | OK |
| 8. ddl-auto validate + Liquibase | role seed lech KC | OK | OK | CHECK constraint mismatch | CHECK mismatch | OK |
| 9. ENV_VAR default + outbox | OK | OK | SmtpNotificationSender no default | payment outbox no retention | OK | OK |

**Tong ket tuan thu:**
- **favourite-service** la diem sang nhat, gan nhu PASS moi rule
- **rating-service** + **common-utils** pass 9/9 hard rules
- **auth-service** lech nhieu nhat (identity model, @PreAuthorize style, hardcoded path)
- **product-service** vi pham rule 5 nghiem trong nhat (3 storefront controllers co @PreAuthorize ADMIN method-level)
- **shipping-service** co CHECK constraint mismatch - se crash production insert

---

## 6. Top 10 Issues Can Fix Ngay (Sprint Hien Tai)

| # | Severity | File | Issue | Effort |
|---|---|---|---|---|
| 1 | C1 | common-security/BaseSecurityConfig.java:93 | JWT aud validation | 0.5 ngay |
| 2 | C8 | docker/keycloak/import/ecommerce-realm.json | Hardcoded changeme + test users | 1 ngay |
| 3 | C10 | docker-compose.yml:369,397 | Webhook secret default -> fail-fast | 0.5 gio |
| 4 | C4 | payment-service/changelog-001-payments.yaml:70 | Add CHECK constraint cho FAILED_RETRYABLE | 1 gio |
| 5 | C3 | payment-service/application.yml:47 | Webhook secret empty default | 0.5 gio |
| 6 | C19 | common-kafka/BaseKafkaListenerConfig.java:79 | Scope trusted packages | 1 gio |
| 7 | C20 | common-kafka/KafkaProperties.java + listener config | Wire DLT + DefaultErrorHandler | 1 ngay |
| 8 | C12+C17 | notification-service/.../NotificationServiceImpl.java | Add PENDING status + retry/DLQ | 2 ngay |
| 9 | C2 | auth-service/UserServiceImpl.java:42 | Remove logInput=true + redact sanitizer | 0.5 gio |
| 10 | C13 | product-service/.../controller/* | Move POST/PUT/DELETE sang Backoffice controllers | 1 ngay |

---

## 7. Khuyen Nghi Sprint Ke Tiep

### Sprint N+1: Architectural fixes
- T2 (Outbox distributed lock): SELECT ... FOR UPDATE SKIP LOCKED + version field (anh huong 5+ service)
- T4 (Connection leak): Split @Transactional boundaries (anh huong order, payment, auth, product, inventory)
- T5 (Kafka chain): Producer config + DLT wire + trusted packages (anh huong toan fleet)
- T3 (Webhook security): Stripe-style signature voi timestamp (payment + shipping)

### Sprint N+2: Quality + Pattern consistency
- T9 (Hardcoded paths): Sweep tat ca @RequestMapping khong dung ApiPaths
- T6 (Hardcoded secrets): Fail-fast cho TAT CA env vars production
- DRY violations: ServiceTokenProvider (2 services), Transactional*EventPublisher (3 services), ListenerConfig (2 services)

### Sprint N+3: Performance optimization
- H37-H47: Indexes + connection pool tuning + pagination strategy
- H22: ES alias swap improvement
- H49: Promotion idempotency key

---

## 8. Bao Cao Chi Tiết Tung Subagent

- p1 (auth+favourite+gateway): da luu trong conversation
- p2 (product+search+notification): da luu trong conversation
- p3 (order+payment+inventory): docs/review/order-payment-inventory-2026-09-02.md (p3 tao)
- p4 (shipping+tax+promotion): docs/review/review-shipping-tax-promotion.md (p4 tao)
- p5 (rating+common-utils): da luu trong conversation
- p6 (Security specialist): da luu trong conversation
- p7 (Performance specialist): da luu trong conversation

---

## 9. Diem Manh Duoc Ghi Nhan

1. outbox pattern duoc implement o hau het service publish event
2. Testcontainers singleton lifecycle pattern tot (p3)
3. Cache serializer regression tests (p3)
4. WebhookSignatureVerifier HMAC constant-time + length check + hex parse an toan (p3)
5. PaymentStateMachine bang transition hop le + @Version (p3)
6. BackofficePaymentController/PaymentController chuan muc (p3)
7. inventory-service documentation tot nhat fleet (p7)
8. favourite-service la mau chuan (p1)
9. rating-service + common-utils pass 9/9 hard rules (p5)
10. Method security genuinely enabled va consistent tren auth-flow (p6)

---

## 10. Diem Yeu He Thong (Root Causes Tong Quat)

1. DRY violations nhieu cho: ServiceTokenProvider (2x), Transactional*EventPublisher (3x), ListenerConfig (2x), check constraint mismatch pattern (payment + shipping)
2. Default an toan chua co: changeme, local-test-secret, mock provider matchIfMissing=true, group-id shop-service chung
3. Connection leak khong chi 1 cho - 10+ vi tri (p7) - pattern "giu DB connection qua HTTP I/O" xuat hien o nhieu service khac nhau
4. Outbox chua distributed-safe: nhieu service co outbox nhung khong co lock -> scale ra nhieu pod = duplicate events
5. State machine chua day du: payment/webhook/notification/shipping deu ghi state truoc khi verify
6. Test security bypass pho bien: addFilters=false che loi auth -> 401/403 khong co test
7. Idempotency o nhieu tang khong dong nhat: payment dung key khac scope voi order; promotion khong co
8. DLT/retry dead config: khai bao trong common-kafka nhung khong wire -> poison message stall

---

**Tong ket cuoi cung:** Codebase o muc **~65% production-ready**. Can fix it nhat:
- 5 Critical security holes (C1, C2, C3, C8, C9, C10) truoc khi public
- 3 Critical data-loss (C4, C12, C17) truoc khi production load
- 3 Critical functional (C5, C6, C7) truoc khi live payment

Estimated effort: 2 sprints (4 tuan) voi 2-3 engineers de dat production-ready.
