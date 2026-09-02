# Báo cáo Code Review tổng hợp — petproject (toàn dự án, trừ media-service)

- **Ngày:** 2026-09-02
- **Phương pháp:** 9 subagent review song song theo từng miền (product, order, payment+promotion, shipping+tax, inventory+rating, search+notification, auth+favourite+gateway, common-utils, infra/config). Baseline pattern đã được xác nhận với người dùng trước khi kiểm tra nhất quán. Rubric bảo mật: chỉ ghi nhận đường tấn công cụ thể, confidence ≥ 8/10.
- **Báo cáo chi tiết từng module:** `docs/review/{product-service,order-service,payment-promotion,shipping-tax,inventory-rating,search-notification,auth-favourite-gateway,common-utils,infra-config}.md` (đầy đủ mô tả + đề xuất fix theo từng file/dòng).
- Báo cáo này là bản **tổng hợp**: dedup, ưu tiên hóa, và các root cause xuyên suốt hệ thống.

## 1. Tổng quan số liệu

| Module | Phạm vi | Số file | Critical | High | Medium | Low |
|---|---|---|---|---|---|---|
| product-service | service + tests | 83 | 1 | 1 | 7 | 9 |
| order-service | service + tests | 90 | 0 | 1 | 4 | 19 |
| payment + promotion | 2 services | 99 | 1 | 1 | 7 | 16 |
| shipping + tax | 2 services | 95 | 0 | 0 | 10 | 21 |
| inventory + rating | 2 services | 80 | 0 | 0 | 11 | 20 |
| search + notification | 2 services | 69 | 0 | 0 | 10 | 16 |
| auth + favourite + gateway | 3 thành phần | 95 | 3 | 5 | 13 | 19 |
| common-utils (7 libs) | 7 thư viện dùng chung | 115 | 0 | 4 | 23 | 26 |
| infra & config | pom, compose, docker, scripts | ≈21 | 0 | 2 | 14 | 20 |
| **Tổng** | | **≈747 lượt** | **5** | **14** | **99** | **166** |

> Tổng **284 findings**. Sau khi dedup: 5 vị trí Critical = **4 root cause** (leo quyền chiếm 2 vị trí); 14 High = **13 root cause** (realm credentials xuất hiện ở cả 2 báo cáo auth + infra).

## 2. Critical — chi tiết 4 root cause

### C1. Leo quyền ADMIN qua endpoint đăng ký public — auth-service
- **Vị trí:** `dto/request/RegisterRequest.java:43` + `service/impls/UserServiceImpl.java:141-150,169-172`
- **Mô tả:** Trường `private Set<String> roles;` do client tự điền, không allowlist/validate, được chuyển thẳng vào Keycloak (`KeycloakAdminClient.assignRealmRoles` không filter) và DB (lưu theo enum). `POST /api/v1/auth/sign-up` là public → attacker gửi `{"roles":["ADMIN"], ...}` được cấp realm role `ADMIN` trong JWT (`realm_access.roles=ADMIN`), hợp lệ với `AdminRoleGateFilter` của gateway và mọi `hasAuthority('ADMIN')`.
- **Đề xuất:** Bỏ `roles` khỏi request đăng ký, mặc định `USER`; gán role chỉ qua `RoleService.assignRole` (đã ADMIN-gated). Nếu giữ field thì allowlist + uppercase. Bổ sung test đăng ký `roles=[ADMIN]` phải bị từ chối (hiện chưa có test nào).

### C2. Log plaintext password + PII ra log INFO — auth-service
- **Vị trí:** `service/impls/UserServiceImpl.java:43`
- **Mô tả:** `@LogPerformance(title = "Register user", logInput = true)`; `LoggerAspect` (common-logging) log `RegisterRequest.toString()` vào INFO khi duration ≥ 50ms. Register thực hiện nhiều HTTP call tới Keycloak → gần như luôn vượt ngưỡng → **password plaintext + email + phone vào log pipeline**.
- **Đề xuất:** Bỏ `logInput`, hoặc thêm cơ chế mask các field nhạy cảm trong `stringify` của common-logging.

### C3. Webhook thanh toán FAILED không bao giờ được xử lý lại — payment-service
- **Vị trí:** `WebhookEventService.java:41`
- **Mô tả:** Dedup `existsByProviderAndProviderEventId` → `return` **bất kể status** của event row; mọi lỗi trong `process()` (kể cả lỗi tạm thời giữa `insertEvent` và `completeWithEvent`, hay `OptimisticLockingFailureException`) chỉ dẫn tới `markEventFailed` rồi trả 200. Khi PSP re-deliver, event bị dedup im lặng → state transition **bị mất vĩnh viễn trên luồng tiền** (ví dụ webhook REFUNDED thất bại thoáng qua → payment stuck CAPTURED, khách bị charge mà không được refund).
- **Đề xuất:** Dedup chỉ skip khi `status == PROCESSED`; event FAILED được re-process (an toàn vì state machine chặn double-transition), hoặc chuyển sang trạng thái PENDING-retryable cho reaper xử lý; phân biệt lỗi vĩnh viễn (AMOUNT_MISMATCH, invalid state) khỏi lỗi transient.

### C4. Wire-format mismatch giết chết luồng đồng bộ rating — product-service (gốc ở common-kafka)
- **Vị trí:** `kafka/RatingLifecycleListenerConfig.java:12-22` + `ProductRatingConsumer.java:18` (gốc: `JsonKafkaSerializer` của common-kafka)
- **Mô tả:** Rating-service publish payload outbox dạng JSON String qua `JsonKafkaSerializer` → **double-encoded** (đã được minh chứng thực tế bằng `MediaDeletedConsumerIT.wireShapeIsDoubleEncodedOnTheRealBroker`). `RatingLifecycleListenerConfig` bind bằng typed `JsonDeserializer` của Spring Kafka (không có logic unwrap string token) → **mọi record `shop.rating.lifecycle.v1` lỗi deserialization** → `avgRating`/`ratingCount` trên product không bao giờ cập nhật; phía rating-service outbox vẫn đánh dấu SENT nên không retry — mất mát câm lặng.
- **Đề xuất:** Chuyển `ratingListenerFactory` sang `StringDeserializer` + tự unwrap trong `ProductRatingConsumer` (mirror `MediaDeletedConsumer.decode` — comment trong `MediaLifecycleListenerConfig.java:14-24` đã mô tả đúng lỗi này cho topic media), kèm wire-shape IT như bên media. Xem thêm R1 để fix tận gốc serializer.

## 3. High — 14 vị trí (13 gốc sau dedup)

| # | Vị trí | Vấn đề | Fix đề xuất |
|---|---|---|---|
| H1 | product `CategoryServiceImpl.java:104-108` | Không chống cycle parent → `findTree()` đệ quy vô hạn → 500 trên public `/api/v1/categories/tree` | Validate `parentId != id` + kiểm chuỗi ancestor; guard visited-set trong `findTree` |
| H2 | order `kafka/ShippingDeliveredConsumer.java:24-30` | Nuốt mọi exception handler → offset commit → event `shipping.delivered` mất vĩnh viễn khi lỗi tạm thời | Rethrow để Spring Kafka retry + `DefaultErrorHandler`/DLT; hoặc metric + reconciliation scan cho đơn SHIPPED cũ |
| H3 | payment `PaymentServiceImpl.java:44-63` | `capture()`/`refund()` bỏ qua hoàn toàn `ProviderResult` (field `accepted` dead code), không persist/đổi state; tái dùng 1 idempotency key cho cả capture lẫn refund → double-refund, không dấu vết reconcile | Thêm state trung gian (CAPTURE_REQUESTED/REFUND_REQUESTED) hoặc ghi PaymentEvent; persist `providerEventId`; idempotency key per operation (`+ "-capture"/"-refund"`); kiểm tra `accepted` |
| H4 | auth `UserController.java:40,54,62` + `UserServiceImpl.java:211-222` | Định danh sai trên cụm `/me`: parse `jwt.getSubject()` (Keycloak UUID) làm local id → 4 endpoint `/me` luôn 404 với token thật (test che bằng `sub="alice"`) | Dùng `JwtClaimExtractor.username()/subject()` hoặc tra theo `keycloakUserId`; sửa fixture test theo token KC thật |
| H5 | auth `UserServiceImpl.java:88-94` | Soft-delete chỉ xóa bản ghi local, **không disable/khóa user trong Keycloak** → user bị xóa vẫn đăng nhập và gọi API bình thường | Delete phải gọi `KeycloakAdminClient` (disable + revoke session); restore thì enable lại |
| H6 | auth `RoleServiceImpl.java:68-75` | Thiếu `@Transactional(readOnly = true)` + `open-in-view: false` → `LazyInitializationException` → `GET /api/v1/roles/users/{id}` luôn 500 | Thêm `@Transactional(readOnly = true)` hoặc fetch roles trong query |
| H7 | auth `filter/ClientIpResolver.java:22-28` | Lấy entry **đầu tiên** của `X-Forwarded-For` do client tự gửi → attacker spoof IP trong CIDR allowlist → bypass toàn bộ D5 allowlist deny-by-default | Resolve theo `trusted-proxy-hops` (`XForwardedRemoteAddressResolver` như `RateLimitKeyResolver` đã làm) |
| H8 | infra+auth `docker/keycloak/import/ecommerce-realm.json:64,77,90,103,127,136` | Realm import (dùng chung dev VÀ prod) chứa credential công khai trong git: 4 client secret `changeme`, `adminuser/adminpass` (ADMIN+MANAGER), `testuser/testpass` | Xoay toàn bộ lúc deploy qua admin API/env; export realm không chứa mật khẩu user; startup probe từ chối khởi động nếu còn default credential |
| H9 | infra `docker-compose.yml:628` | `notification-service` thiếu anchor `*pg-creds` → fallback `${POSTGRES_USER:admin}` → prod xoay mật khẩu là notification-service chết thầm lặng | Thêm `*pg-creds` vào stanza; bỏ fallback `:admin` khỏi `application.yml` |
| H10 | common-kafka `BaseKafkaListenerConfig.java:79` | `addTrustedPackages("*")` tắt kiểm tra package của `JsonDeserializer` → ai ghi được vào topic có thể header `__TypeId__` tới class tùy ý trên classpath → deserialization gadget (họ CVE-2023-34040) | `addTrustedPackages("com.shop.*")` tối thiểu; tốt hơn `setUseTypeHeaders(false)` (mỗi topic 1 kiểu event) |
| H11 | common-kafka `KafkaAutoConfiguration.java:31-36` | Instance `JsonKafkaSerializer` cấu hình sẵn **bị vứt đi** — chỉ `getClass().getName()` được đưa vào props, Kafka tự instantiate bằng `new ObjectMapper()` trần (mất `JavaTimeModule`) → event chứa `Instant` fail serialize và rơi vào fallback H12 | `new DefaultKafkaProducerFactory<>(props, keySer, valueSerInstance)` truyền instance thật |
| H12 | common-kafka `JsonKafkaSerializer.java:32-43` | Jackson serialize fail thì **im lặng** trả `data.toString().getBytes()` → consumer nhận garbage kiểu `ProductEvent@13f2e` → corrupt không báo lỗi | Ném `SerializationException` (kèm topic/type), để publisher quyết định; không bao giờ fallback |
| H13 | common-spring `CommonProperties.java:58-186` | Toàn bộ aggregator `shop.common.*` **không được code production nào đọc** → operator tắt `shop.common.security.enabled=false` không có tác dụng (switch thật là `shop.security.enabled`) — bề mặt vận hành "lừa đảo" | Wire các toggle vào `@ConditionalOnProperty` thật hoặc xóa hẳn record |

## 4. Root cause hệ thống (dedup xuyên module)

Fix ở các điểm dưới đây sẽ xử lý đồng thời nhiều finding ở nhiều service:

- **R1 — Chuỗi serializer Kafka (common-kafka):** H10 + H11 + H12 cùng tạo ra/sắp tạo ra corrupt event; **C4** (rating) và shipping Medium (outbox String double-encoded, tiền lệ commit `2ce93f4`) đều là triệu chứng của chuỗi này. Fix tại 1 điểm: giữ instance serializer đã cấu hình (`JavaTimeModule`), fallback → throw, trusted packages cụ thể, và chuẩn hóa producer luôn publish object (không String) hoặc passthrough raw bytes cho `CharSequence`.
- **R2 — Sự kiện bị mất vĩnh viễn (không DLT/replay):** C3, H2, payment webhook catch-Exception-vẫn-ack-200, shipping webhook FAILED vĩnh viễn, notification `send()` sau commit (at-most-once không ghi nhận), consumer search ack-always — tất cả đều thiếu một trong: retry, DLT, replay/reaper, metric. Cần một chuẩn chung ở `BaseKafkaListenerConfig` (error handler + DLT) và chuẩn ack webhook chỉ khi đã persist trạng thái an toàn.
- **R3 — Định danh người dùng phân mảnh (Keycloak sub vs local UUID):** auth `/me` (H4), favourite lưu `user_id` = Keycloak sub trong khi auth-service lưu local UUID — không join được, và fixture test (`sub="alice"`) che lỗi. Cần chốt **một** mô hình định danh (khuyến nghị: Keycloak sub làm natural key toàn fleet, auth-service lưu `keycloakUserId`).
- **R4 — Authorization không nhất quán:** auth-service dùng `@PreAuthorize` + parse `jwt.getSubject()` thủ công (lệch baseline `AuthenticatedUser.requireCurrent()`); JWT decoder không validate `aud` (common `BaseSecurityConfig.java:93`); CORS `"*"` kèm credentials (common + gateway); allowlist IP spoof được (H7); quyền nâng cấp từ client (C1).
- **R5 — Credential mặc định chảy vào production:** H8, H9, Keycloak `start-dev` (compose:174), mock PSP + `PAYMENT_PROVIDER: mock` default (payment yml + compose:392-401), `admin/admin` KeycloakAdminClient default, storage `rustfsadmin/rustfsadmin` default trong library, Redis password lộ trên argv, 5 cổng infra bind `0.0.0.0` với default creds. Mô hình hiện tại là "an toàn nếu operator nhớ xoay" — cần "an toàn mặc định": fail-fast khi còn default credential ở prod.
- **R6 — Giữ DB transaction/connection qua network I/O:** rating `submit()` gọi HTTP eligibility trong `@Transactional`; product `update()` gọi HEAD media trong transaction write → cạn pool dưới tải. Chuẩn: mọi remote call nằm ngoài transaction.
- **R7 — Error mapping rò rỉ (cùng pattern, nhiều bản copy):** `ServiceTokenProvider.getToken()` ném `IllegalStateException` không phải `RestClientException` → thoát catch fail-closed → 500 thô (lặp y hệt ở search `ProductBackofficeClient.java:70` và rating `EligibilityClient.java:57`); `ApiExceptionHandler.handleDataIntegrityViolation` trả nguyên message DB constraint cho client; catch-all `DataIntegrityViolationException` gán nhãn "duplicate eventId" sai nguyên nhân (shipping, notification).
- **R8 — Lifecycle thiếu trạng thái trung gian:** payment capture/refund (H3), notification SENT-ghi-trước-khi-send, shipping auto-DELIVERED sau 7 ngày không bước xác nhận — đều là "ghi state khi chưa thực sự xảy ra" hoặc "không ghi khi đã gọi provider".
- **R9 — Test tạo ảo giác an toàn:** `addFilters=false` ở slice tests của product + auth (không ai test 401/403); test "chính thức hóa" anti-pattern (order consumer swallow test, rating 500-codified test); order-dependency giữa IT (search, notification dùng chung container không truncate); fixture sai (`sub="alice"`).
- **R10 — Default nguy hiểm tập trung trong common:** consumer group-id default `"shop-service"` chung + `earliest`; retry props được khai báo nhưng không wire; common-spring kéo Swagger UI ra 13 service (→ phải public path swagger); `spring-boot-starter-aop` pin milestone 4.0.0-M2 ghi đè parent 4.1.1.

## 5. Tuân thủ pattern theo module

| Module | Đánh giá |
|---|---|
| product-service | Tuân thủ tốt (records, mapper, outbox, yml chuẩn). Lệch nhẹ: storefront controller không cap page size như backoffice; guard cycle parent thiếu. |
| order-service | Tuân thủ tốt. Điểm yếu chuỗi compensation (chỉ bắt 1 loại exception) và cache trở thành hard dependency của luồng tiền. |
| payment + promotion | Đúng pattern nhưng state machine luồng tiền chưa hoàn thiện (webhook replay, capture/refund, provider mock default). |
| shipping + tax | Đúng pattern, một số chỗ lặp logic (transition ×2). Chưa sẵn sàng cho carrier thật (`ShipmentStatus.valueOf`, mapping webhook). |
| inventory + rating | Hợp chuẩn; lỗi tập trung quanh DB constraint (âm kho, TOCTOU) và transaction boundary (scheduler). |
| search + notification | Error-contract tốt trên giấy nhưng bị thủng ở seam token; test có order-dependency. |
| auth + favourite + gateway | **Lệch pattern nhiều nhất** (identity model, `@PreAuthorize` deviation, hardcode path, parse JWT thủ công) — đồng thời là nơi duy nhất xử lý mật khẩu → rủi ro bảo mật cao nhất. |
| common-utils | Nền móng — độ chuẩn hóa chưa cao: default nguy hiểm (group-id, credentials), dead config, 4 lần copy outbox/traceparent. **Nơi đáng đầu tư nhất** vì 1 fix ở đây chữa nhiều service (xem R1). |
| infra & config | ~6/10 cho giai đoạn dev→tiền-prod: anchors DRY tốt, healthcheck đầy đủ, fail-safe script. Hụt lớn nhất ở boundary dev↔prod (credential trôi sang prod). |

**Điểm mạnh được ghi nhận:** envelope `ApiResponse`/`PageResponse` nhất quán toàn fleet; outbox pattern có mặt ở mọi service publish event; `${ENV_VAR:default}` + `.env.example` có fail-closed rotation; compose dùng anchors + `depends_on: condition: service_healthy` đầy đủ 21 container; script shell có `set -e` + confirm trước khi xóa volume; `docs/PRODUCTION-READINESS.md` và overlay prod ingress-only được thiết kế có chủ đích.

## 6. Bảng Medium theo module (99)

### product-service (7)
| Vị trí | Tóm tắt |
|---|---|
| `ProductController.java:46`, `BrandController.java:37` | Storefront không cap `MAX_PAGE_SIZE` như backoffice |
| tests | Thiếu security matrix cho endpoint ghi ADMIN-gated (dùng `addFilters=false`) |
| `CategoryServiceImpl.java:114-123`, `BrandServiceImpl.java:82-91` | Delete không kiểm tra phụ thuộc → cây category mồ côi / sản phẩm mất brand |
| `TransactionalProductEventPublisher.java:93` | `updatedAt` stale trong event update/delete (publish trước flush) |
| `ProductServiceImpl.java:179-183` | Gọi HTTP HEAD media bên trong transaction write |
| outbox writers ×3 | Boilerplate outbox lặp lần thứ 4 toàn fleet (DRY) |
| `changelog-004-media-reference.yaml:16` | `media_id` không index → seq-scan mỗi event MediaDeleted |

### order-service (4)
| Vị trí | Tóm tắt |
|---|---|
| `InventoryServiceClient.java:55-65` + `OrderServiceImpl.java:159-184` | Lỗ hổng compensation: chỉ catch `StockReservationFailedException`, 5xx lọt → rò stock |
| `ProductServiceClient.java:36-50` | `@Cacheable` không `CacheErrorHandler` → Redis sập = 500 luồng tiền |
| `OrderServiceImpl.java:263-289` | Race cancel-vs-confirm chưa test, phụ thuộc contract "release-committed phải fail" |
| `OrderOutboxRelay` | Relay (egress duy nhất) không test nhánh lỗi |

### payment + promotion (7)
| Vị trí | Tóm tắt |
|---|---|
| `WebhookEventService.java:56-59` | Catch Exception vẫn ack 200 "accepted" → PSP không re-deliver |
| `PaymentServiceImpl.java:31-41` | Idempotency key tái dùng payload khác im lặng trả payment cũ; race unique → 500 |
| `application.yml:36` + `MockProvider.java:10` | `PAYMENT_PROVIDER` default mock + `matchIfMissing` → provider giả im lặng ở prod |
| `OutboxEventRepository.java:17-19` | Không retention scheduler → bảng outbox tăng vô hạn |
| `PaymentOutboxRelay.java:29-57` | Nhánh lỗi relay không test, `break` không comment |
| `CampaignReservationServiceImpl.java:102-111` | `reserve()` không idempotent theo orderId → unique constraint → 500 |
| `ReservationCleanupScheduler.java:68-75` | Comment sai transaction boundary (batch không rollback như comment nói) |

### shipping + tax (10)
| Vị trí | Tóm tắt |
|---|---|
| `ShippingEventPublisherImpl.java:39-62` + Relay | String payload double-encoded (tiền lệ commit `2ce93f4`) |
| `ShippingOutboxRelay.java:54` | `break` head-of-line thiếu comment giải thích (đã có ở order-service) |
| `OutboxEventRepository.java:17-19` | Không retention scheduler, index pending thoái hóa |
| `ShipmentServiceImpl.java:69-71` | Catch `DataIntegrityViolationException` quá rộng, che lỗi dữ liệu thật |
| `ShipmentServiceImpl.java:149-164` vs Webhook | Logic transition lặp 2 nơi (admin path vs webhook path) |
| `ShipmentServiceImpl.java:121` | Tracking trùng → 500 thay vì 409 |
| `WebhookEventServiceImpl.java:91-101` | Webhook hợp lệ nhưng tracking chưa có → 200 + FAILED vĩnh viễn, không replay |
| `WebhookEventServiceImpl.java:105-106` | `ShipmentStatus.valueOf` đòi carrier gửi đúng nguyên văn enum |
| `ReconciliationScheduler.java:45-53` | Auto DELIVERED sau 7 ngày không bước xác nhận; ngưỡng 72h khai báo nhưng không dùng |
| `TaxRateRequest.java:13` | `@Pattern` thiếu `@NotNull` → country null lọt validation → 500 |

### inventory + rating (11)
| Vị trí | Tóm tắt |
|---|---|
| `InventoryServiceImpl.java:88` | Cho phép `available < reserved` → kho âm, không CHECK constraint |
| `InventoryServiceImpl.java:106` | Delete TOCTOU + thiếu FK `reservations.product_id` |
| `InventoryServiceImpl.java:152-239` | Thiếu trace "ai trừ bao nhiêu" (quantity) trong audit commit/release |
| `ReservationServiceImpl.java:31-91` | 4 wrapper retry ~giống hệt nhau (DRY) |
| `ReservationCleanupScheduler.java:74` | Comment sai transaction boundary — nhưng theo hướng ngược lại của promotion-service |
| `ReservationCleanupScheduler.java:100-110` | Retention chỉ purge EXPIRED → RELEASED/COMMITTED tăng vô hạn |
| `RatingServiceImpl.java:35-64` | HTTP eligibility trong `@Transactional` |
| `RatingServiceImpl.java:37-39` | Race submit trùng → 500 thay vì 409 `RTG-11005` |
| `RestClientConfig.java:53-56` | Token client không timeout → Keycloak treo = `synchronized` treo vô hạn toàn service |
| `EligibilityClient.java:57,64` | `IllegalStateException` thoát fail-closed catch (đồng nguồn R7) |
| `OutboxEventRepository.java:19` (rating) | Retention method mồ côi, bảng outbox tăng vô hạn |

### search + notification (10)
| Vị trí | Tóm tắt |
|---|---|
| `ProductBackofficeClient.java:70-73` | `IllegalStateException` lọt ngoài catch → 500 thay vì SRH-12002 (đồng nguồn R7) |
| `ProductSearchConsumer.java:51-53` | Ack-always không có counter/alert — lỗi ES = mất event câm lặng |
| `ReindexServiceImpl.java:71-84` | Race reindex vs consumer → bản update mất sau swap alias |
| `ReindexServiceImpl.java:137` | `NumberFormatException` (index `products-v2_old`) không bắt → 500 |
| `SearchIndexProvisioningIT` | Order-dependency giữa 3 IT (chung context, ReindexIT xóa index) |
| `BackofficeNotificationController.java:33-35` | `page`/`size` không validate cận dưới → 500 thay vì 400 |
| `NotificationServiceImpl.java:58-60` | Catch-all DataIntegrityViolation gán nhãn "duplicate" sai nguyên nhân |
| `NotificationServiceImpl.java:38` | `UUID.fromString` NPE/IllegalArgument → retry 9× churn, event mất |
| `NotificationServiceImpl.java:49,66` | Ghi SENT trước khi send thật; at-most-once không ghi nhận JavaDoc |
| `NotificationBootstrapIT:55` | Assert count=0 giả định DB trống → fail theo thứ tự chạy |

### auth + favourite + gateway (13)
| Vị trí | Tóm tắt |
|---|---|
| `RegisterRequest.java:28-31` | Email không `@NotBlank`; `emailVerified=true` cho người tự đăng ký |
| `UserServiceImpl.java:49-55` | Compensate không bọc flush-at-commit → Keycloak user mồ côi |
| `UserServiceImpl.java:141-150,224-229` | `KeycloakClientException` không map trong handler → 500 raw |
| `UserServiceImpl.java:60-65,187-203` | Update email/phone không re-check uniqueness |
| `UserServiceImpl.java:118-123` + controller | Không cap page size; `sortBy` thô → IllegalArgumentException → 500 |
| Controllers (3) | Hardcode path API thay vì `ApiPaths.*`; `@PreAuthorize` deviation; parse `jwt.getSubject()` thủ công |
| `SecurityFilterChainIntegrationTest:174-185` | Fixture `sub="alice"` che lỗi định danh `/me` (H4) |
| Slice tests ×3 | `addFilters=false` → không test authorization/leo quyền |
| `FavouriteServiceImpl.java:48-58` | Race create → conflict generic thay vì `FAV-6002` |
| `FavouriteServiceImpl.java:31-38` | `user_id` = Keycloak sub ≠ local UUID của auth (hai hệ định danh, không join được) |
| Gateway `application.yml:38-39` | CORS `"*"` + `allowCredentials=true` |
| `RateLimitFilter.java:49,75-77` | Bucket map không evict → tăng bộ nhớ theo số IP từng thấy |
| Kiến trúc rate-limit | 3 tầng chồng lấp + 2 cơ chế resolve IP khác nhau cho cùng khái niệm |

### common-utils (23)
| Vị trí | Tóm tắt |
|---|---|
| `common-core/pom.xml:46-48` | `starter-data-jpa` compile trong module "pure contracts" → kéo Hibernate vào cả gateway |
| `BaseSecurityConfig.java:93-95` | JWT không validate `aud` → token của client bất kỳ hợp lệ toàn fleet |
| `SecurityProperties.java:102-106` | CORS default `"*"` + Javadoc khuyến khích credentials |
| `AuthenticatedUser.java:38-49` | `from(jwt)` lấy authorities từ SecurityContext của thread hiện tại, không từ jwt |
| common-security tests | Không test nào cho matcher public-paths/authorities/CORS cho 13 service |
| `KafkaProperties.java:209-210,100-118` | Default group-id `"shop-service"` chung + `earliest` — 2 nút gài cho 13 service |
| `KafkaProperties.java:160-183` | Retry props khai báo nhưng không wire (dead config) |
| `JsonKafkaDeserializer.java:10-14` | Dead code, Javadoc sai 2 mệnh đề |
| `KeycloakTokenClient.java:146-153` | 5xx/timeout báo thành "sai mật khẩu" trước mặt user |
| `KeycloakAdminClient.java:200-237` | `assignRealmRoles` nuốt lỗi từng role → phân quyền sai thầm lặng |
| `KeycloakAutoConfiguration.java:64-76` | Admin client với default `admin/admin` cho mọi service |
| `KeycloakAdminClient.java:74,118,145` | Token fetch mỗi thao tác → N+3 HTTP calls |
| common-keycloak | Không test nào cho client gọi mạng quan trọng |
| `CommonLibraryStarter.java:29-31` | `@SpringBootApplication` có `main()` trong jar tiêu thụ |
| `common-spring/pom.xml:46-116` | "Drop-in starter" kéo mọi thứ (Swagger UI ra 13 service, 2 mapper lib) |
| `ApiExceptionHandler.java:195-208` | Trả nguyên message DB constraint cho client (information disclosure) |
| `HttpLoggingFilter.java:117-122` | Query string thô vào log (code/token OAuth) |
| common-spring | Không test cho handler dịch lỗi chạy trên 13 service |
| traceparent injection ×3 | Copy 3 lần: common-spring, common-kafka, common-keycloak |
| `StorageProperties.java:39-43` | Default `rustfsadmin/rustfsadmin` cứng trong library |
| `ObjectStorageAutoConfiguration.java:43-50` | `autoCreateBucket=true` → boot fail khi S3 chưa sẵn sàng |
| common-storage | Không test presign/404/auto-create |

### infra & config (14)
| Vị trí | Tóm tắt |
|---|---|
| `pom.xml:73,187-190` | `starter-aop` pin milestone 4.0.0-M2 ghi đè parent 4.1.1 |
| `pom.xml:347-366` | Thiếu `MaxRAMPercentage`/`mem_limit` → 15 JVM có thể OOM máy dev |
| `pom.xml:349-366` | 14 ảnh service chạy **root** trong container |
| `docker-compose.yml:52-53,71-72,90-91,126-127,150-152` | 5 cổng infra bind `0.0.0.0` + default creds (postgres admin/admin, kafka PLAINTEXT...) |
| `docker-compose.yml:25-28,45-63` + init SQL | 12 DB + Keycloak chung 1 superuser, không phân quyền per-service |
| `docker-compose.yml:174` | Keycloak `start-dev` được prod overlay kế thừa |
| `docker-compose.yml:392-401` + prod | Mock PSP trong compose gốc + `PAYMENT_PROVIDER=mock` default → rủi ro tài chính |
| `docker-compose.prod.yml` (nhiều dòng) | 8 service ghi chung 1 file `audit.jsonl` → interleave/hỏng, không rotation |
| `ecommerce-realm.json:35-48` | `ecommerce-client` publicClient + directAccessGrants + `redirectUris: localhost:*` vào prod |
| `docker-compose.prod.yml:56` + env | `ADMIN_IP_ALLOWLIST` rỗng = allow-all, không fail-fast |
| `start-docker.sh:211,221-226` | Doc sai port Keycloak (8080 thay vì 9090) + in credential cứng ra màn hình |
| `mock-payment-provider/server.js:22-44,58` | Webhook fire-and-forget không retry → mất CAPTURED/REFUNDED |
| `docker-README.md:29,78` | Lặp lỗi sai port Keycloak |
| `README.md:12` | Khai Kafka 4.x trong khi compose dùng 3.9.0 |

**Low (166):** chi tiết đầy đủ trong các báo cáo module (vd: không có checkstyle thật chạy, `${revision}` khai báo không dùng, Redis password trên argv, docker-README đếm sai container, script e2e parse JSON không guard, `.env` còn biến vô dụng...).

## 7. Lộ trình khắc phục ưu tiên

- **P0 (khẩn cấp — tuần này):** C1 (leo quyền roles), C2 (log password), C3 (webhook FAILED replay), H8/H9 (credential realm + notification pg-creds), H3 (double-refund), H2 (consumer swallow → DLT), C4 (fix rating consumer).
- **P1 (tuần sau):** R1 — cứng hóa chuỗi serializer common-kafka (H10/H11/H12), H4/H5 (định danh `/me`, delete→disable Keycloak), H7 (XFF resolver), H6 (thiếu @Transactional), R7 (seam ServiceTokenProvider, handler không lộ DB message), `CacheErrorHandler` + timeout cho token clients.
- **P2 (sprint tới):** R5 hoàn thiện (fail-fast default credential, Keycloak prod command, mock PSP profile dev, bind loopback), outbox retention đầy đủ (payment/shipping/rating), DB per-service role + CHECK constraint kho, non-root image + JVM flags, audit file per service.
- **P3 (nợ kỹ thuật):** R3 thống nhất mô hình định danh, R9 sửa test (bỏ `addFilters=false`, sửa fixture, fix order-dependency IT), xóa dead config (`CommonProperties`, `JsonKafkaDeserializer`, checkstyle), thống nhất cap page size/sort whitelist, gộp rate-limit, sửa doc (port Keycloak, Kafka version).

---

*Chi tiết từng finding (mô tả đầy đủ, đường dẫn file+dòng, đề xuất fix cụ thể) nằm trong 9 báo cáo module tại `docs/review/`. Bản tổng hợp này do orchestrator tổng hợp từ kết quả các subagent (2026-09-02).*