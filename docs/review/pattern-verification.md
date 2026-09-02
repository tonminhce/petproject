# Xác minh pattern bằng đọc code trực tiếp (orchestrator pass)

> Mục đích: đọc từng file code thật, đối chiếu checklist pattern, kết luận "đã đúng pattern chưa / đã chuan production chưa". Đây là pass thứ 2 do orchestrator tự đọc (không qua subagent).
> Checklist pattern (từ plan.md + docs/ARCHITECTURE.md + xác nhận người dùng + code favourite-service — service chuẩn):
> 1. Layer: controller → service (interface) → service/impls → repository → entity; dto/{request,response} là records
> 2. `ApiResponse<T>`/`PageResponse<T>` envelope; `BusinessException.of(ErrorCode.X)`
> 3. `@RequestMapping(ApiPaths.*)` — không hardcode path
> 4. Cap page size `Math.min(size, PageableConstant.MAX_PAGE_SIZE)`
> 5. Storefront: KHÔNG `@PreAuthorize`; Backoffice: `@PreAuthorize("hasRole('ADMIN')")` class-level
> 6. `AuthenticatedUser.requireCurrent().id()` (Keycloak sub UUID) → chuyển UUID
> 7. `@Transactional(readOnly=true)` cho read; soft-delete: `@SQLRestriction` + `@Modifying`/markDeleted
> 8. `ddl-auto: validate` + Liquibase changelog (audit columns, partial unique WHERE deleted=false, CHECK)
> 9. `${ENV_VAR:default}` trong yml; mapper/ manual mapping; outbox pattern cho event

## favourite-service (9 java + yml + 2 changelog) — ĐỌC ĐỦ
**Verdict: PASS mọi điểm checklist.** Controller dùng ApiPaths.FAVOURITES, comment giải thích rõ "NO @PreAuthorize (rev 2)", cap page size line 43, soft-delete @SQLRestriction + @Modifying query có deletedBy, changelog partial unique index raw SQL, ddl-auto validate, yml comment header chuẩn. Đây là file chuẩn mẫu của fleet.

## tax-service (21 java + yml + changelog) — ĐỌC ĐỦ (bỏ qua interface/service marker + 3 dto response)
Verdict: đạt ~90%, deviations:
1. **`TaxRateRequest.java:13` + `TaxCalculateRequest.java:12` — `@Pattern(regexp="^[A-Z]{2}$")` KHÔNG có `@NotNull`** → country=null lọt validation, rơi vào insert cột `char(2) NOT NULL` → 500 thay vì 400. XÁC NHẬN bằng code (khác với favourite: `@NotNull` đầy đủ).
2. `TaxClassServiceImpl.java:31` — báo duplicate name bằng `ErrorCode.DUPLICATE_TAX_RATE` (sai mã — đang nói về tax CLASS). `TaxRateServiceImpl` dùng `DUPLICATE_TAX_RATE` đúng chỗ (line 87).
3. TaxCalculationController.java:23 `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")` — endpoint internal gọi qua service-token; cần kiểm chứng method-security có được enable (common-security) không, nếu không thì annotation này inert (rủi ro).
4. **Không có package `mapper/`** — toResponse() là private method trong impls (TaxRateServiceImpl:95, TaxClassServiceImpl:80). Lệch pattern favourite (có mapper/ riêng) nhưng mapping 3-5 field nên chấp nhận được; fleet cũng làm manual mapping.
5. Changelog tốt: CHECK constraint, partial unique, FK tax_rates→tax_classes. PASS.
6. yml: không set `shop.security.public-paths` (khác favourite/rating set `[]` rõ) — phụ thuộc default common; không có `shop.application.name` (favourite có). Không nhất quán yml giữa services.

## rating-service (21 java + yml + changelog) — ĐỌC GẦN ĐỦ (còn RatingClientProperties, RatingAction, metrics, 2 dto, OutboxEventRepository, main app)
Verdict: đạt ~85%, XÁC NHẬN BẰNG CODE các lỗi đã báo:
1. **ĐÃ XÁC NHẬN double-encoding chain (C4):** `RatingEventService.java:87` set `payload = objectMapper.writeValueAsString(map)` (String JSON) → `RatingOutboxRelay.java:37-39` publish **String** qua `KafkaMessagePublisher` (JsonKafkaSerializer sẽ string-encode lần 2). Consumer phía product-service bind typed JsonDeserializer → deserialize fail → avgRating/ratingCount không cập nhật. Chuỗi bằng chứng đầy đủ, không còn là suy đoán.
2. **`ServiceTokenProvider.java:82-84`** ném `IllegalStateException` khi body rỗng; `EligibilityClient.java:64` chỉ catch `RestClientException` → 500 thay vì fail-closed 403 RTG-11001. XÁC NHẬN.
3. **`RestClientConfig.java:52-56`** bean `restClientBuilder` (dùng cho token) KHÔNG set timeout (khác `orderRestClient` có timeout từ props). XÁC NHẬN.
4. `RatingServiceImpl.submit()` (dòng 34-47) gọi HTTP eligibility **trong `@Transactional`** — giữ DB connection qua network I/O. XÁC NHẬN (dòng 44 nằm trong method @Transactional dòng 34).
5. `RatingOutboxRelay.java:54` `break` head-of-line không có comment giải thích (lệch order-service có comment).
6. `RatingRepository.findAggregateByProductId` là `List<Object[]>` + `get(0)` — pattern yếu nhưng có normalize Hibernate BigDecimal/Double (comment tại RatingEventService:101-103).
7. Storefront list cap `MAX_PAGE_SIZE` (service) nhưng `page`/`size` âm → `PageRequest.of` IllegalArgumentException → 500 raw. Không thấy `@Min` guard.
8. Có @PreAuthorize("hasRole('ADMIN')") ở BackofficeRatingController + @Audited — ĐÚNG chuẩn fleet backoffice (comment dẫn chứng "fleet backoffice convention (BackofficePaymentController)"). Storefront controller KHÔNG @PreAuthorize + comment giải thích lý do (Keycloak user có thể không có realm role USER) — đây là QUY ƯỚC fleet có chủ đích, không phải lỗi.
9. Changelog tốt: CHECK rating 1-5, comment 5-2000, hidden audit consistency, partial unique. Outbox: chỉ index (status) — relay query `findByStatusOrderByIdAsc` + PENDING thiếu partial index (status,id) → perf nếu bảng lớn.
10. yml: `client-secret: ${RATING_SERVICE_CLIENT_SECRET:changeme}` — default changeme nằm trong source yml (dev-only nhưng import prod thì nguy, đã có ở báo cáo trước).
11. Rating entity không có `@Version`; submit duplicate race → `DataIntegrityViolationException` từ unique index uk_rating_user_product_live → 500 thay vì RTG-11005. XÁC NHẬN (index ở changelog:42, service chỉ pre-check line 37-40).

## notification-service (18 java + yml + changelog) — ĐỌC GẦN ĐỦ (còn main app, constants ×2, NotificationTemplates, LoggingNotificationSender, NotificationSender interface, repo, dto response)
Verdict: đạt ~80%, XÁC NHẬN:
1. `NotificationServiceImpl.handle()` **KHÔNG có @Transactional** — insert qua `NotificationWriter.insert()` (@Transactional riêng, saveAndFlush) rồi `sender.send(saved)` ngoài tx rồi `markFailed` tx riêng. Status gán `SENT` tại builder (dòng 49) TRƯỚC khi send thực (dòng 66) — row có thể nằm mãi ở SENT dù chưa gửi. XÁC NHẬN.
2. `catch (DataIntegrityViolationException)` (dòng 58) catch TẤT CẢ constraint — kể cả `order_id NOT NULL` khi event thiếu orderId (entity line 38 nullable=false) → drop im lặng + log sai nguyên nhân "concurrent consumer". XÁC NHẬN.
3. `UUID.fromString(event.getEventId())` (dòng 38) — eventId null/sai format → NPE/IllegalArgumentException văng khỏi listener → retry 9 lần rồi discard (BaseKafkaConsumer.processMessage KHÔNG bọc handler — sẽ verify ở common-kafka). XÁC NHẬN phần notification.
4. `BackofficeNotificationController.java:35` — `PageRequest.of(page, Math.min(size,...))` cận trên có, cận dưới không → page=-1/size=0 → IllegalArgumentException → 500.
5. OrderEventConsumer dùng typed binding `OrderLifecycleEvent` — CẦN cross-check producer order-service (OrderOutboxRelay) xem có double-encode không (nghi ngờ vì rating/shipping/product đều lỗi chuỗi này; commit 2ce93f4 chỉ fix search side bằng unwrap).
6. `OrderLifecycleEvent` là class lombok (không phải record) vì Jackson binding — deviation có lý do.
7. `NotificationWriter` annotate `@Repository` dù là class service/writer — nên là @Component (minor).
8. Changelog: CHECK status/channel nhưng status KHÔNG có PENDING; không có cột soft-delete trong entity nhưng changelog CÓ deleted/deleted_at/deleted_by → entity thiếu @SQLRestriction nhưng không cần (không bao giờ soft-delete). Không có @Version/@Column length chặt (subject varchar(255) vs entity String không length — validate ddl sẽ pass vì Hibernate String default 255? risk nhỏ).
9. yml: `shop.kafka.consumer.group-id: notification-service` + `auto-offset-reset: latest` — ĐÚNG chuẩn (override default nguy hiểm "shop-service"/earliest của common — sẽ verify). `smtp.enabled: false` default + fallback-recipient ops@example.com — an toàn-by-default tốt.

## gateway-service (đọc đủ các filter/config) — XÁC NHẬN
1. **ClientIpResolver.java:22-28** tin FIRST entry của X-Forwarded-For (spoofable — client tự thêm header). RateLimitFilter key theo IP này → bypass rate limit bằng XFF giả. XÁC NHẬN (comment trong code chỉ nói "assumption" nhưng không mitigate; RateLimitKeyResolver dùng XForwardedRemoteAddressResolver có trusted-proxy-hops nhưng hops default = 0 → đằng sau proxy thật thì mọi request gộp về IP proxy).
2. **GlobalRateLimitFilter trả 429 body rỗng** (không `ApiResponse` envelope) — LỆCH mọi filter khác (gọi ApiResponse). Frontend parse JSON thất bại với endpoint này. XÁC NHẬN.
3. CORS: `application.yml:38-39` default `${CORS_ALLOWED_ORIGIN_PATTERNS:*}` + `setAllowCredentials(true)` → allow-origin `*` + credentials = browser chặn, nhưng nếu ai set origin cụ thể + credentials thì mở nguy cơ. Đã ở báo cáo High.
4. AdminRoleGateFilter + RequestPathGuard: chặn encoded/matrix path → 400 — phòng thủ tốt, có test. PASS.
5. RateLimitFilter: `ConcurrentHashMap<String,Bucket>` không evict (per-IP key vô hạn = memory), không distributed (nhiều instance gateway thì bucket riêng). XÁC NHẬN.
6. RouteTargetProperties/FilterOrder — chuẩn hóa config route, ok.

## inventory-service (controller, service, reservation, scheduler, mapper, outbox relay, event publisher) — XÁC NHẬN
1. **KHÔNG double-check:** `InventoryMapper.partialUpdate` (dòng 37-41) chỉ copy availableQuantity — `update()` cho phép available < reservedConfirmed. XÁC NHẬN (không có guard).
2. **DRY vi phạm:** ReservationServiceImpl 4 method retry wrapper IDENTICAL (dòng 31-91, MAX_ATTEMPTS=3). XÁC NHẬN — nên tách template method/decorator.
3. **ReservationCleanupScheduler** `@Transactional` bọc toàn method + try/catch TRONG method → khi lỗi giữa chừng, các batch trước ĐÃ COMMIT (exception bị catch → không rollback). Comment trong file nói đúng thực tế này (không sai như 1 agent claim). Chỉ purge EXPIRED (dòng 102) — RELEASED/COMMITTED không bao giờ purge → leak row, reservation cũ ngày càng đầy. XÁC NHẬN là gap thật.
4. OutboxRetentionScheduler: purge SENT > 7 ngày, giữ FAILED — ĐÚNG chuẩn (reference).
5. **Double-encode xác nhận:** `TransactionalInventoryEventPublisher.save()` dòng 118 `setPayload(writeValueAsString(payload))` (String) → `InventoryOutboxRelay` dòng 59-62 publish String qua KafkaMessagePublisher. Cùng chuỗi C4/R1 với rating/payment/search. XÁC NHẬN BẰNG CODE.
6. Điểm cộng inventory: relay `break` CÓ comment giải thích head-of-line (dòng 22-29 + 79) — chuẩn document; event contract dot.case có comment dẫn docs/SERVICE-CATALOG.md; HashMap + null-guard có comment lý do. Đây là mức độ documentation tốt nhất fleet.
7. `@PreAuthorize` internal endpoint (reserve/commit/release): `hasRole('SERVICE') or hasRole('ADMIN')` — gateway KHÔNG gate `/api/v1/inventory` → ĐÂY là lá chắn duy nhất, đã xác nhận method-security ACTIVE (SecurityAutoConfiguration:30 có @EnableMethodSecurity).

## auth-service (đọc đủ 25 file: DTO, controller, service, entity, mapper, changelog, yml) — XÁC NHẬN + CỦNG CỐ C1
1. **C1 xác nhận TRỌN CHUỖI 7 mắt xích (đọc tận tay):**
   - `AuthController` sign-up ở `/api/v1/auth/**` (public-paths yml dòng 40) — không cần đăng nhập.
   - `RegisterRequest.java:43` `private Set<String> roles` — field tự do, KHÔNG validate/whitelist.
   - `UserServiceImpl.extractRoles()` dòng 152-160: truyền thẳng role người dùng gửi lên (default USER nếu rỗng, nhưng BẤT KỲ role nào cũng lọt).
   - `KeycloakAdminClient.createUser(..., roles)` → `assignRealmRoles` — gán REALM ROLE thật lên Keycloak.
   - `ecommerce-realm.json` xác nhận: realm roles `['ADMIN','USER','MANAGER','SERVICE']` — "ADMIN" TỒN TẠI.
   - `JwtRolesConverter` đọc `realm_access.roles` → sinh 2 authority `ADMIN` và `ROLE_ADMIN`.
   - Toàn fleet backoffice dùng `@PreAuthorize("hasRole('ADMIN')")` → role ADMIN trong JWT = chiếm toàn bộ admin API.
   → VÔ DANH gọi sign-up với `roles: ["ADMIN"]` = đặc quyền quản trị toàn nền tảng. CRITICAL, giờ có bằng chứng line-by-line.
2. **C2 ĐƯỢC ĐÍNH CHÍNH (quan trọng — agent trước sai):** `@LogPerformance(logInput=true)` ở register + `LoggerAspect.stringify` gọi `obj.toString()` — NHƯNG `RegisterRequest`/`LoginRequest` chỉ có `@Getter/@Setter`, KHÔNG `@ToString` → toString là Object identity (không in giá trị). **Password KHÔNG bị rò vào log hôm nay.** Rủi ro tiềm ẩn: chỉ cần ai đó đổi DTO sang record hoặc thêm @ToString là rò ngay. Hạ severity xuống Medium (latent), không phải Critical active.
3. **`jakarta.transaction.Transactional`** ở UserServiceImpl:11, RoleServiceImpl:11 — LỆCH fleet (Spring @Transactional). Spring chỉ hiểu qua EJB3 parser (proxy mode) — hoạt động nhưng mất readOnly, dễ nhầm. `changePassword`/`register` @Transactional bọc 2 HTTP call Keycloak bên trong → giữ DB connection qua network I/O (cùng lỗi R5).
4. **Soft-delete + re-register giết vĩnh viễn:** users table unique trên `user_name/email/phone_number` KHÔNG partial `WHERE deleted=false` (entity `@Table uniqueConstraints` dòng 24-28 + changelog 001 dòng 47/53/63). User xóa mềm rồi KHÔNG BAO GIỜ đăng ký lại được với cùng username/email/phone. Lệch chuẩn favourite (partial unique). High (functional).
5. **`RoleServiceImpl.getUserRoles()` KHÔNG @Transactional + auth bật `open-in-view: false`** (yml dòng 23) + `user.getRoles()` LAZY → `LazyInitializationException` → GET /roles/users/{id} 500 với mọi user có role. High (bug runtime chắc chắn xảy ra).
6. `findAllUsers` KHÔNG cap size (UserController:76-82 truyền size thẳng → `PageRequest.of`), không `@Min` page/size → size=99999 hoặc page=-1 → lỗi/500. Lệch checklist #4.
7. Controller hardcode path `"/api/v1/auth"` `"/api/v1/users"` `"/api/v1/roles"` trong khi `ApiPaths` CÓ sẵn AUTH/USERS/ROLES — lệch checklist #3 (favourite dùng ApiPaths).
8. Style: `hasAuthority('ADMIN')` (auth) vs `hasRole('ADMIN')` (fleet) — cả hai đều chạy vì converter sinh 2 dạng (đã đọc JwtRolesConverter), nhưng không thống nhất.
9. `RegisterRequest` dùng Lombok class không phải record (pattern #1 nói records) — lệch; LoginRequest/RegisterRequest @NotBlank ok; gender là String tự do (không enum).
10. `rollbackKeycloakUser` catch Exception + comment "Log but don't throw" **mà KHÔNG có log** (UserServiceImpl:174-180) — nuốt lỗi im lặng.
11. `verifyCredentials` (KeycloakTokenClient:146-153): 5xx/timeout cũng trả `false` → báo "invalid credentials" sai nguyên nhân (fail-closed, chấp nhận được nhưng misleading).
12. `verifyOldPassword` (ROPC login) → `resetUserPassword`: TOCTOU giữa verify và reset — minor.
13. `register()` catch RuntimeException → rollbackKeycloakUser → xuôi; nhưng `existsByUsername` check trước rồi `createKeycloakUser` — nếu KC từ chối (username tồn tại trong KC) → KeycloakClientException → ai bắt? Không có handler trong auth (không có GlobalExceptionHandler trong danh sách file — verify common) → 500 raw.
14. Changelog: seed roles USER/PM/ADMIN trong DB local — role local KHÔNG được sync về Keycloak (assignRole chỉ ghi DB local, không gọi KC) → 2 nguồn role lệch nhau (R7 identity split). XÁC NHẬN.

## payment-service (đọc đủ 27 file chính: controller×2, webhook×3, service, writer, state machine, outbox, provider, entity, changelog, yml)
1. **C3 XÁC NHẬN + CÒN TỆ HƠN:** `WebhookEventService.handle()`: event ghi FAILED từ lúc `buildEvent` (dòng 123 `.status(STATUS_FAILED)`), xử lý lỗi → `markEventFailed`. KHÔNG có relay xử lý lại. Và dòng 41-43: `existsByProviderAndProviderEventId` → return — **provider retry CÙNG eventId bị nuốt im lặng vì event đã tồn tại**. Kết quả: payment FAILED dính vĩnh viễn, không channel nào hồi phục. CRITICAL xác nhận bằng code.
2. **H3 XÁC NHẬN:** `PaymentServiceImpl.capture()` dòng 50 và `refund()` dòng 61 gọi `provider.capture/refund(...)` rồi BỎ KẾT QUẢ ProviderResult — không kiểm tra success. StripeProvider hiện ném `UnsupportedOperationException` (chưa implement — khớp plan 2026-09-01-real-stripe-adapter). MockProvider luôn trả success.
3. HTTP provider call bên trong `@Transactional` (capture/refund) — R5 lặp lại.
4. **Điểm cộng lớn:** `PaymentWriter.completeWithEvent` ghi outbox CÙNG transaction với payment (outbox đúng chuẩn); `WebhookSignatureVerifier` HMAC constant-time (`MessageDigest.isEqual`) + check độ dài chữ ký + hex parse an toàn; webhook secret không default trong source (empty → fail-closed 403); `PaymentStateMachine` bảng hợp lệ; `Payment` có `@Version`; BackofficePaymentController/PaymentController chuẩn mực (ApiPaths, cap size, PageResponse, @Audited, @PreAuthorize đúng tầng).
5. `webhookSecret` empty default → nếu env thiếu, webhook không bao giờ process (fail-closed đúng nhưng payment dính PENDING âm thầm — cần healthcheck cảnh báo).
6. `MockProvider` `matchIfMissing = true` — không set `shop.payment.provider` ở prod = CHẠY MOCK, không thu tiền thật. Nguy hiểm (nên fail-fast như StripeProvider @PostConstruct).
7. Idempotency race: `create()` find-then-insert; DDL có `uk_payment_idempotency_key` (xác nhận changelog-001 dòng 37) → 2 request đồng thời cùng key → DataIntegrityViolationException 500 thay vì trả về bản ghi đã tồn tại. Chưa có catch như WebhookEventService làm. Medium.
8. `PaymentController.findAllByOrderId` `@RequestParam UUID orderId` REQUIRED → nhánh `orderId==null` trong service là dead code (vô hại). Không có kiểm tra ownership (ok vì SERVICE/ADMIN).
9. Structure: `WebhookEventService`, `PaymentWriter`, `ReceiptService`, `PaymentStateMachine` nằm trực tiếp trong `service/` (class cụ thể, không interface/impls) — lệch pattern layer "interface + impls" (chỉ PaymentService đúng). Đã lưu ý ở notification tương tự.
10. **R1 double-encode XÁC NHẬN ở payment:** `PaymentEventPublisher.save` dòng 53 `setPayload(writeValueAsString(payload))` → `PaymentOutboxRelay` dòng 37-39 publish String qua KafkaMessagePublisher → topic `shop.payment.lifecycle.v1` gửi String-double-encoded → consumer typed (order/notification) fail. Cùng root R1.
11. Relay `break` (PaymentOutboxRelay:54) không comment — lệch inventory/reference.

## promotion-service (đọc: retry, reservation impl, cleanup, relay, publisher, 2 controller, survey campaign impl) — module CHẤT LƯỢNG CAO
1. **Bằng chứng trực tiếp cho R1:** `TransactionalPromotionEventPublisher.save()` dòng 104 `setPayload(writeValueAsString(payload))` (String) → `PromotionOutboxRelay` dòng 60-63 publish String qua KafkaMessagePublisher. Đây là producer thứ 4 xác nhận double-encode (sau rating, payment, inventory).
2. **Retry DRY ĐÚNG mẫu:** `ReservationRetryServiceImpl.withRetry(Supplier)` — 1 template method cho 5 method. ĐÂY LÀ CÁCH CHUẨN để sửa lỗi DRY của inventory (4 wrapper copy-paste). Có comment giải thích lý do không @Transactional (mỗi attempt tự có tx riêng từ delegate) + spec reference §5.1/§5.3.
3. **Race protection chuẩn mực:** reserve() check-then-insert được bảo vệ bằng "version-touch" trên Campaign (`setUpdatedAt(now)` + `saveAndFlush` dòng 99-100) — loser nhận OptimisticLockingFailureException → retry re-read → 2 gate chạy lại với state mới. Comment dòng 93-98 giải thích TẠI SAO. Thiết kế rất tốt.
4. **ReservationCleanupScheduler comment SAI ngược với inventory (dòng 68-73):** comment nói "Flushed batches are rolled back with the transaction" — THỰC TẾ: try/catch nằm TRONG @Transactional → exception bị catch → flush trước đó **COMMIT** (không rollback). Inventory cũng try/catch trong @Transactional nhưng comment của inventory nói ĐÚNG ("Batches already flushed stay committed"). Promotion nói ngược thực tế. XÁC NHẬN bằng code + semantics Spring.
5. Điểm cộng promotion: CÓ retention purge RELEASED/EXPIRED >30 ngày (dòng 84-86 — inventory THIẾU cái này); batch + flush/clear chống phình persistence context; releaseAllExpired không publish event (quota theo status nên lật trạng thái là đủ — có comment lý do).
6. CampaignServiceImpl: Spring @Transactional(readOnly=true) đúng tầng; delete có check CAMPAIGN_IN_USE. Chuẩn.
7. Controller: PromotionReservationController hoàn hảo (ApiPaths, SERVICE/ADMIN, @Audited, @Valid, 201); BackofficeCampaignController class-level ADMIN + cap size + ApiPaths. PASS checklist #1-9 ngoại trừ R1.
8. Reserve qua SERVICE-token: userId lấy từ request (do order-service gửi) — tin tưởng service-token; không verify userId khớp token. Chấp nhận theo mô hình service-to-service.

## shipping-service (đọc: consumer, listener config, webhook impl, verifier, publisher, relay survey, scheduler, controller, yml) — khá tốt, 2 vấn đề hệ thống
1. **SAME lỗi C3-class:** `WebhookEventServiceImpl`: event build với `status(EVENT_STATUS_FAILED)` từ đầu (dòng 81); unknown tracking (91-96) / null carrierStatus (98-101) / transition reject (104-111) đều return với event FAILED. Không có relay xử lý lại; dedup dòng 70 `existsByCarrierAndProviderEventId` → **carrier retry cùng eventId bị nuốt**. Shipment dính trạng thái cũ vĩnh viễn. Cùng root với payment C3. XÁC NHẬN.
2. **R1 double-encode:** `ShippingEventPublisherImpl.save()` dòng 54 `setPayload(writeValueAsString(payload))` — producer thứ 5. Lưu ý phía tiêu thụ: `OrderEventConsumer` bind typed `OrderLifecycleEvent` qua `ShippingListenerConfig` extends `BaseKafkaListenerConfig` (JsonDeserializer typed) → nếu order-service producer double-encode thì consumer này FAIL — sẽ verify ở order-service (task #9).
3. Điểm cộng: HMAC per-carrier (map secret theo carrier.name()), verifier constant-time bản copy của payment; ReconciliationScheduler auto-deliver stale shipment có cron + cutoff; BackofficeShipmentController chuẩn mực (ApiPaths, class-level ADMIN, cap, @Audited đầy đủ); yml group-id shipping-service + auto-offset-reset latest đúng chuẩn.
4. `ShipmentStatus.valueOf(payload.getCarrierStatus())` (dòng 105-106) string khớp chính xác enum — brittle với carrier format khác ("delivered" ≠ "DELIVERED"); được bọc try/catch nên không crash nhưng event FAILED + bị nuốt retry (xem #1) → đơn HÀNG có thể dính mãi.

## product-service (đọc: rating consumer, listener config, outbox relay, publisher survey, service survey, controllers, media consumer) — C4 closed-loop
1. **Trọn chuỗi C4 đóng kín bằng code:** rating producer double-encode (đã xác nhận task #6) → `ProductRatingConsumer` bind typed `RatingLifecycleEvent` qua `RatingLifecycleListenerConfig` extends BaseKafkaListenerConfig → **JsonDeserializer typed gặp String-double-encoded → fail trước cả khi vào listener** → `ProductRatingService.apply()` (set avgRating/ratingCount) KHÔNG BAO GIỜ CHẠY. avgRating/ratingCount trên product không bao giờ cập nhật từ rating-service. Đã có đủ 3 mắt xích (producer rating đã đọc + 2 file này đọc tận tay).
2. **`ProductSearchConsumer` + `SearchListenerConfig` — BẰNG CHỨNG GHI TRONG CODE về R1:** javadoc dòng 13-21 (SearchListenerConfig) và 11-21 (ProductSearchConsumer) ghi RÕ RÀNG: "The fleet producer serializes the outbox payload STRING via JsonKafkaSerializer ... records arrive DOUBLE-ENCODED — a JSON string token wrapping the event JSON". Search tự sửa bằng StringDeserializer + unwrap-once contract (decode(): readTree → nếu textual thì unwrap → treeToValue). Commit 2ce93f4 — FIX CỤC BỘ, chừa mọi consumer typed khác (notification, shipping, product-rating) vẫn vỡ. Đây là nguồn xác nhận (không còn là suy đoán).
3. **`MediaDeletedConsumer` (product←media) cũng dùng pattern String raw + unwrap** (dòng 40-69) — fix cục bộ thứ 2 cùng kiểu. → 2 consumer đã vá, N producer vẫn double-encode, N-2 consumer typed vẫn vỡ. Root R1: sai tầng serialize ở PUBLISHER (lưu String JSON vào outbox thay vì lưu cấu trúc) + KafkaMessagePublisher không xử lý String payload.
4. `ProductOutboxRelay`: KHÔNG break, continue batch (comment dòng 29-31 giải thích deliberate — chấp nhận mất ordering vì ProductCreated/Updated là full snapshot idempotent). Đây là "relay lỏng" mà inventory/promotion comment đối chiếu. Hợp lý riêng cho product; relay không @Transactional, save từng event commit riêng (comment 23-27).
5. ProductServiceImpl: Spring @Transactional(readOnly) + ErrorCode.* + slug/sku uniqueness + MEDIA_NOT_FOUND line 181 (tích hợp media_id integrity — commit mới nhất cb4b693). Tốt.
6. ProductController: read công khai + 3 method ghi ADMIN (line 61,68,76) trong cùng controller (hơi lệch "backoffice tách riêng" nhưng có guard từng method). BackofficeProductController class-level isAuthenticated + method-level SERVICE/ADMIN (lệch convention class-level ADMIN — có vẻ có chủ đích cho media binding bởi SERVICE).
7. `ProductRatingConsumer.handleContained` ack-always poison (comment dẫn precedent order-service ShippingDeliveredConsumer): mọi BusinessException khi apply bị NUỐT + log → rating event không hợp lệ mất vĩnh viễn (không retry, không DLT). Đây là "fleet containment rule" có chủ đích. Rủi ro mất dữ liệu đã được chấp nhận.

## search-service (đọc: consumer, listener config, event dto, controller survey, reindex survey, token provider, client config) — module TỐT, đã tự vá R1
1. SearchController: KHÔNG @PreAuthorize + comment dẫn fleet precedent P2-6 OrderController ✓ (đúng quy ước); BackofficeSearchController class-level ADMIN ✓; ApiPaths ✓.
2. ReindexServiceImpl: stream page theo MAX_PAGE_SIZE, guard REINDEX_IN_PROGRESS, bulk error check từng item, alias generation list — kỹ lưỡng, ErrorCode.* đầy đủ.
3. `ServiceTokenProvider:80` — CÙNG lỗi `IllegalStateException("empty body")` như rating (không catch ở ProductBackofficeClient) → reindex 500 thay vì fail-closed có mã lỗi. XÁC NHẬN.
4. `ProductClientConfig`: timeout connect/read set từ props (line 36-37) cho product client — tốt; `restClientBuilder` (line 55 redeclare cho token) — cần check có timeout (giống vấn đề rating RestClientConfig).
5. ProductLifecycleEvent record 17 field với @JsonIgnoreProperties(ignoreUnknown=true) — dung sai additive payload. Tốt.

## order-service (đọc: relay, event publisher, delivery consumer, listener config, service survey, controllers, idempotency, reconciliation, yml, rest config) — BU tốt nhất, nhưng là trung tâm hứng chịu R1
1. **Producer #6 confirmed:** `OrderEventPublisherImpl.save()` dòng 95 `setPayload(writeValueAsString(payload))` → `OrderOutboxRelay` dòng 53-55 publish String (break CÓ comment dòng 70 + javadoc "deliberate divergence from product-service's continue-on-error relay"). → `shop.order.lifecycle.v1` double-encoded.
2. **HỆ QUẢ R1 ĐẬP NÁT lowercase flow:** 
   - **TYPED consumers của order events đều VỠ:** shipping `OrderEventConsumer` (bind OrderLifecycleEvent) và notification `OrderEventConsumer` (bind typed) — cả 2 extends BaseKafkaListenerConfig → order.created.v1 gửi ra dạng String-double-encoded → JsonDeserializer typed fail TRƯỚC listener → **shipment không bao giờ được tự tạo khi order confirmed, email thông báo đơn hàng không bao giờ gửi.** 
   - **TYPED consumer của shipping events cũng VỠ:** order-side `ShippingDeliveredConsumer` bind `ShippingDeliveredEvent` (typed) ← shipping producer double-encode → **order không bao giờ auto-DELIVERED từ webhook carrier.** 
   - BẰNG CHỨNG đủ cả 2 chiều (producer + consumer đều đã đọc tận tay).
3. **IdempotencyServiceImpl (order) = CHUẨN reference:** hiểu đúng hiểm họa `saveAndFlush` merge @IdClass (comment dòng 36-37) → dùng `REQUIRES_NEW` + INSERT thuần để race nổ DataIntegrityViolationException → catch → trả kết quả đã lưu (dòng 80-84). ĐỐI LẬP với payment `create()` (find-then-insert không catch → 500 khi race). Nên lấy order làm mẫu sửa payment.
4. **Saga createOrder:** @Transactional ngoài, saga body KHÔNG @Transactional có comment cảnh báo self-invocation (dòng 103-105) — documentation chuẩn. Pricing REMOTE (product+tax+promotion) + reserve stock HTTP ĐỀU nằm trong tx (dòng 134-166) — R5 (connection-held-over-network) ĐỦ CẢNH: 1 tx giữ connection suốt 4 HTTP call. Compensation tốt: fail reserve → release tất cả đã reserve + best-effort release promotion với log "TTL sweep covers" (171-179).
5. **publishCancelled: `refunded = false` HARCODED + comment P2-4/TODO Phase 8** — MVP thừa nhận không có refund state (khớp spec refund-flow đang pending). Trung thực, đã document.
6. OrderController: class-level isAuthenticated + KHÔNG hasRole USER có chú thích lý do đầy đủ (P2-6, dòng 33-35) — ĐÂY LÀ CÁI ÀNH XẠ QUY ƯỚC storefront mà rating/search/favourite dẫn chứng. Page cap ✓, list ADMIN ✓, SERVICE/ADMIN for internal ✓. OrderStatusController class SERVICE/ADMIN đúng.
7. OrderReconciliationScheduler: scan PENDING stuck → all-committed → AUTO_CONFIRM (+ publish order.updated); all-released → auto-cancel; mixed → log skip (manual). Recon thật, không phải trang trí.
8. RestClientConfig (order): per-service client đủ timeout connect+read (dòng 53-56) — reference cho rating/search thiếu.
9. yml: group-id order-service + latest ✓; `ORDER_SERVICE_CLIENT_SECRET:changeme` default (R4 lặp lại).
10. OrderOutboxRelay nằm trong `service/impls/` (khác service khác đặt service/ hoặc outbox/) — lệch vị trí package nhỏ.

## utils/common-* (đọc: kafka toàn bộ, exception handler, logging filter+aspect, keycloak client, security — đã đọc pass trước —, base entities, constants) — NGUỒN GỐC MỌI THỨ
1. **R1 mechanics XÁC NHẬN ĐẦY ĐỦ tại common-kafka:** `KafkaMessagePublisher` dùng `KafkaTemplate<String,Object>` + `ProducerRecord<String,Object>` (dòng 70-71, 96) → relay truyền `event.getPayload()` là **String JSON** → `JsonKafkaSerializer.serialize()` (dòng 33-42: `writeValueAsBytes(data)`) bọc string thêm 1 lớp JSON → wire nhận JSON-string-token. `BaseKafkaListenerConfig` (dòng 63-81) bind value bằng **JsonDeserializer typed** (`addTrustedPackages("*")` — mở toang, mọi package) + ErrorHandlingDeserializer bọc ngoài. → consumer typed gặp token double-encoded: DeserializationException, retry 9 lần (default Boot), KHÔNG DLT (comment dòng 36-41 thừa nhận "not wired") → **record bị bỏ im lặng.** Chuỗi đã chứng minh end-to-end ở cả producer lẫn consumer, kèm javadoc search-service xác nhận hành vi wire thực tế.
2. **`JsonKafkaDeserializer.addTrustedPackages("*")`** — trust mọi package tức là tin mọi polymorphism hint trên wire (nếu producer config thêm type headers) — hiện producer KHÔNG gửi type header nên vô hại thực tế, nhưng là lớp phòng thủ bị tắt sẵn. Nên thu hẹp về `com.shop.**`.
3. **`KafkaProperties.retry` (max-attempts 4, backoff 6000) là DEAD CONFIG:** grep KafkaAutoConfiguration — KHÔNG chỗ nào wire `CommonErrorHandler`. Consumer thực tế dùng default Boot (9 lần, 1s) + không DLT, không tồn đọng observe — event handler fail = mất vĩnh viễn sau retry. Config drift thật sự.
4. **`group-id: shop-service` + `auto-offset-reset: earliest` là default nguồn (javadoc KafkaProperties dòng 26-27) — NGUY HIỂM tiềm tàng nhưng HIỆN TẠI ĐƯỢC VÁ:** audit 13 service yml: 6 service CÓ @KafkaListener là shipping/product/search/order/notification/media — **CẢ 6 đều override đúng** (group-id riêng + latest). 7 service kia không listener → không rủi ro. → không phải bug đang cháy; là landmine cho service tương lai (quên override = share group "shop-service" = mất event âm thầm + replay từ đầu topic).
5. **ApiExceptionHandler (fleet-wide, đăng ký @Bean có điều kiện tại WebAutoConfiguration:46-50) — rất tốt:** BusinessException→mã chuẩn; validation đầy đủ 3 tầng; HttpMessageNotReadable CỐ TÌNH không in message thô (tránh lộ payload); OptimisticLocking→409 có comment dẫn finding review trước; DataIntegrityViolation→409; AccessDenied→403; fallback→500 canned. HỆ QUẢ ĐÍNH CHÍNH các suy đoán trước: (a) auth `KeycloakClientException` → 500 canned (không lộ, nhưng vẫn 500); (b) rating duplicate submit → 409 CONFLICT chứ KHÔNG phải 500 raw; (c) `LazyInitializationException` (auth getUserRoles) → 500 canned — vẫn là bug 500 nhưng không lộ internals. ĐIỂM YẾU DUY NHẤT: `DataIntegrityViolation` trả `mostSpecificCause.getMessage()` — **lộ tên bảng/cột/constraint Postgres cho client** (line 198) — schema disclosure, nên canned hóa.
6. **HttpLoggingFilter:** body logging MẶC ĐỊNH TẮT (`HttpLogProperties.enabled=false, includeBody=false`) và KHÔNG service nào bật → KHÔNG rò password qua access log hôm nay. NHƯNG `appendBody` KHÔNG redact gì (dòng 124-134) — bật `include-body` lên là login/signup body (chứa password) in nguyên văn. Latent, cùng hạng với C2 đã đính chính.
7. **SoftDeletable/AbstractMappedEntity:** field access + setter protected + markDeleted/markRestored, auditing qua AuditingEntityListener — thiết kế base chuẩn chỉnh. PageableConstant MAX_PAGE_SIZE=200 — xác nhận chuẩn cap toàn fleet.
8. common-keycloak: không có timeout trên RestClient.Builder (KeycloakAutoConfiguration cần đọc khi rảnh) + login dùng ROPC (deprecated OAuth 2.1) — ghi nhận.
9. common-security (đã đọc pass trước): @EnableMethodSecurity CÓ (SecurityAutoConfiguration:30) → toàn bộ @PreAuthorize THẬT SỰ hoạt động; BaseSecurityConfig JwtDecoder KHÔNG validate audience (Medium đã báo); JwtRolesConverter sinh cả 2 dạng authority (hasRole/hasAuthority đều chạy).

---

# KẾT LUẬN TỔNG HỢP — Pass 2 (đọc code tận tay, có line-level evidence)

## 1) Đã theo coding pattern chưa? — CÓ, mức độ rất cao (đồng đều ~90%)
Checklist 9 điểm đã verify bằng mắt trên 13 service + 7 lib:
- **PASS TOÀN FLEET:** ApiPaths.* (trừ auth hardcode), ApiResponse/PageResponse envelope, BusinessException+ErrorCode, Spring @Transactional(readOnly), @SQLRestriction soft-delete + markDeleted, ddl-auto validate + Liquibase (audit, partial unique, CHECK), `${ENV:default}` yml, outbox pattern (PENDING→SENT→FAILED + relay + retention), storefront-no-@PreAuthorize (QUY ƯỚC CÓ DOCUMENT, dẫn P2-6 OrderController), backoffice hasRole('ADMIN'), internal hasRole('SERVICE') or hasRole('ADMIN'), METHOD SECURITY THẬT SỰ BẬT.
- **Lệch có chủ đích OK:** product relay continue-on-error (full snapshot idempotent); search/media consumer String raw + unwrap (bản vá R1); OrderLifecycleEvent dùng class thay record (Jackson binding).
- **Lệch cần sửa:** (1) auth không cap page size + không dùng ApiPaths; (2) auth dùng jakarta @Transactional + không readOnly; (3) tax `@Pattern` thiếu @NotNull; (4) mapper/ thiếu ở tax; (5) 2 scheduler comment sai ngữ nghĩa transaction (promotion); (6) hasAuthority vs hasRole lẫn lộn; (7) yml không đồng bộ public-paths/name giữa service.

## 2) Đã chuan production chưa? — CHƯA. Đang ở mức "backend xuất sắc cho MVP nội bộ".
Ba lỗ hổng hệ thống ngăn cản "production-ready":
- **R1 (đã chứng minh 100%): Kafka double-encoding** — 6 producer (rating, payment, inventory, promotion, shipping, product, order) đều `setPayload(writeValueAsString(map))` + relay publish String; 4 consumer typed còn lại (notification←order, shipping←order, order←shipping, product←rating) FAIL. Đã vá cục bộ 2 nơi (search, media). HỆ QUẢ ĐANG CHÁY: shipment không tự tạo, email order không gửi, avgRating không cập nhật, order không auto-delivered. FIX GỐC: lưu payload dạng Map/Object vào outbox hoặc publisher nhận diện String, rồi bỏ các unwrap cục bộ.
- **C1 (đã chứng minh đủ 7 mắt xích): leo quyền ADMIN qua sign-up** — roles tự do → Keycloak realm role → JWT → toàn bộ backoffice. FIX: whitelist/ép USER ở server (extractRoles), KHÔNG tin request.
- **C3-payment/shipping: webhook FAILED kẹt vĩnh viễn + provider retry bị dedup nuốt** — cần trạng thái PENDING/RETRY + relay replay (đã có sẵn mẫu outbox relay).
- Phụ trợ đáng cân: auth unique không partial (soft-deleted user chặn re-register vĩnh viễn), getUserRoles LazyInitializationException (chắc chắn 500), inventory partialUpdate cho phép available<reserved, 2 service-thiếu-timeout (rating/search token), default secret `changeme`/admin xuất hiện ở yml (R4), MockProvider matchIfMissing=true.
- Đã đính chính so với pass 1: **C2 KHÔNG rò password thực tế** (RegisterRequest không @ToString; perfLog chỉ in identity toString; HttpLoggingFilter tắt body mặc định) — nhưng là latent risk 2 tầng (thêm @ToString/record hoặc bật include-body là rò).
- **Tiền đề duy nhất:** API nội bộ đang nấp sau 1 lớp "service client + service token" — nếu các endpoint /api/v1/* service-to-service được phơi ra internet thì rủi ro tăng đáng kể; cần giữ gateway là lá chắn duy nhất cho các route backoffice/internal.