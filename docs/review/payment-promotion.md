# Review chuyên sâu: payment-service & promotion-service

- Ngày: 2026-09-02
- Scope: toàn bộ `payment-service/src` và `promotion-service/src` (main + test + resources), **99 files** đã đọc đầy đủ (payment: 47 — 27 main java + 4 resources + 16 tests; promotion: 52 — 32 main java + 4 resources + 16 tests).
- Loại trừ: `media-service/` (ngoài scope), `common-*/` chỉ tham chiếu để kiểm chứng (AbstractMappedEntity, ApiPaths).
- Phương pháp: đọc từng file, đối chiếu pattern chuẩn của fleet (layered, BusinessException/ErrorCode, ApiResponse/PageResponse + MAX_PAGE_SIZE, BaseSecurityConfig public-paths, outbox + relay, Liquibase `ddl-auto: validate`, `${ENV_VAR:default}`), kiểm tra SEO secrets qua `git ls-files`.

**Kết quả gọn: 25 findings (1 Critical, 1 High, 7 Medium, 16 Low).** Cả hai module tuân thủ pattern tốt; mọi lệch chuẩn có chủ đích đều có comment/Javadoc giải thích. Điểm yếu tập trung ở payment-service phía vòng đời webhook (không có cơ chế retry sau FAILED) và capture/refund chưa ghi nhận kết quả gọi PSP.

---

## 1. Payment-service

### Đánh giá tổng quan

Cấu trúc chuẩn mực: `controller/dto/entity/outbox/provider/repository/service(+impls)/webhook`, records DTO, `AbstractMappedEntity` + auditing, soft delete qua `@SQLRestriction`, BusinessException với mã PAY-5xxx, outbox transactional pattern (payment + event + outbox row cùng tx), webhook HMAC-SHA256 verify thời gian hằng (`MessageDigest.isEqual`) trước khi đụng dữ liệu, env-var cho mọi secret (webhook secret mặc định rỗng = fail-closed). Không có secrets bị commit (chỉ `.env.example`/`.env.prod.example` tracked; `.env` local không tracked). Test rất tốt: slice + audit test + IT thật (Testcontainers, replay webhook, race insert, poison payload).

### Findings theo file

**service/WebhookEventService.java**

- [Critical][error-handling] `WebhookEventService.java:41` — sự kiện webhook đã lưu với status `FAILED` **không bao giờ được xử lý lại**: dedup `existsByProviderAndProviderEventId` → `return` bất kể status của event row, và mọi lỗi trong `process()` (kể cả lỗi "tạm thời" như DB gián đoạn giữa `insertEvent` và `completeWithEvent`, hoặc `OptimisticLockingFailureException` khi CAPTURED/REFUNDED về đồng thời) đều chỉ dẫn tới `markEventFailed` rồi trả 200. Khi PSP re-deliver sự kiện đó, nó bị dedup im lặng → state transition bị mất vĩnh viễn trên luồng tiền (vd: webhook REFUNDED thất bại thoáng qua → payment stuck CAPTURED, khách bị charge mà không được refund). Đề xuất: dedup chỉ skip khi status == PROCESSED; re-delivery của event FAILED phải được xử lý lại (re-process vô hại vì state machine chặn double-transition), hoặc chuyển event sang trạng thái PENDING-retryable để một reaper xử lý có giới hạn lần; phân biệt lỗi vĩnh viễn (AMOUNT_MISMATCH, invalid state) khỏi lỗi transient.
- [Medium][error-handling] `WebhookEventService.java:56-59` — `catch (Exception)` nuốt toàn bộ (BusinessException lẫn runtime) rồi vẫn ack 200 "accepted" → PSP coi delivery thành công và không re-deliver, đẩy mọi thất bại vào cái bẫy "FAILED vĩnh viễn" ở trên. Đề xuất: chỉ ack 200 khi đã persist trạng thái an toàn để retry; log BusinessException ở mức warn với eventId, không dùng chung nhánh với lỗi crash.

**service/impls/PaymentServiceImpl.java**

- [High][error-handling] `PaymentServiceImpl.java:55-63` — `refund()` (và `capture()` :44-52 tương tự) gọi provider rồi **bỏ qua hoàn toàn ProviderResult** (`accepted`, `providerEventId` không được đọc ở đâu — field `accepted` là dead code, xem `PaymentProvider.java:14`), không persist gì, không đổi state. Hệ quả: gọi lại `POST /{id}/refund` trước khi webhook REFUNDED về = gọi PSP nhiều lần (double refund); webhook mất thì không có dấu vết để reconcile; `accepted=false` (PSP từ chối) vẫn trả 200 cho client. Thêm vào đó, cùng một `payment.getIdempotencyKey()` được tái dùng cho **cả capture lẫn refund** — với PSP như Stripe nghĩa là refund request trả về kết quả capture cũ và không được xử lý. Deviation có chủ đích một phần (Stripe real adapter là plan doc riêng, hiện throw `UnsupportedOperationException`), nhưng contract `ProviderResult` hiện tại sẽ trở thành bug thật ngay khi adapter được implement. Đề xuất: thêm state trung gian (vd CAPTURE_REQUESTED/REFUND_REQUESTED) hoặc ghi PaymentEvent khi gọi PSP; persist `providerEventId`; suy hậu tố idempotency key per operation (`key + "-capture"`/`"-refund"`); kiểm tra `accepted` và ném BusinessException nếu PSP từ chối.

**service/impls/PaymentServiceImpl.java (create)**

- [Medium][error-handling] `PaymentServiceImpl.java:31-41` — idempotency key tái dùng với **payload khác** (orderId/amount khác) im lặng trả về payment cũ — che giấu lỗi client thay vì conflict; đồng thời race giữa hai create cùng key (check-then-insert) đập vào `uk_payment_idempotency_key` (`changelog-001-payments.yaml:37`) thành `DataIntegrityViolationException` raw → 500 không mã lỗi. Đề xuất: khi key đã tồn tại nhưng (orderId, amount, currency) khác → `BusinessException` conflict; catch `DataIntegrityViolationException` → re-fetch theo key và trả payment hiện hữu như Stripe semantics.

**resources/application.yml**

- [Medium][pattern] `application.yml:36` — `provider: ${PAYMENT_PROVIDER:mock}` kết hợp `MockProvider` có `matchIfMissing = true` (`MockProvider.java:10`): nếu env `PAYMENT_PROVIDER` thiếu ở prod, service chạy **provider giả im lặng** — nhận "thanh toán" mà không thu tiền. Đề xuất: không default mock ở prod (bắt buộc set provider, hoặc fail-fast khi `spring.profiles.active=prod` mà provider=mock).

**outbox/OutboxEventRepository.java (+ PaymentOutboxRelay.java)**

- [Medium][performance] `OutboxEventRepository.java:17-19` — `deleteByStatusAndSentAtBefore` được khai báo nhưng **không có scheduler nào gọi**: payment-service thiếu outbox retention (promotion-service có `OutboxRetentionScheduler`). Bảng `outbox_events` rows SENT (+ FAILED) tăng vô hạn. Đề xuất: thêm scheduler purge SENT sau N ngày như promotion/inventory, hoặc dùng chung helper từ common.

**outbox/PaymentOutboxRelay.java**

- [Medium][testing] `PaymentOutboxRelay.java:29-57` — nhánh lỗi (retry count, max-retries → FAILED, `break` head-of-line) **không có test nào**; chỉ happy-path drain được phủ gián tiếp bởi `PaymentFlowIT`. Đồng thời lý do `break` không được comment như `PromotionOutboxRelay` (nơi đã giải thích cặn kẽ head-of-line blocking). Đề xuất: unit test cho cả hai nhánh + comment lý do.

**constant/PaymentStatus.java**

- [Low][clean-code] `PaymentStatus.java:9` — `TERMINAL_WEBHOOK_STATES` không được dùng ở bất kỳ đâu (dead code). Đề xuất: xóa hoặc dùng trong WebhookEventService.

**service/PaymentWriter.java**

- [Low][dry] `PaymentWriter.java:20-28` — `insert()` và `saveAndFlush()` giống hệt nhau (cùng `repository.saveAndFlush`). Đề xuất: giữ một method duy nhất.

**repository/PaymentRepository.java**

- [Low][clean-code] `PaymentRepository.java:13` — `findById` override thừa (JpaRepository đã cung cấp). Đề xuất: xóa.

**controller/PaymentController.java & BackofficePaymentController.java**

- [Low][pattern] `PaymentController.java:35,42,49,56`, `BackofficePaymentController.java:24` — dùng `@PreAuthorize` trong khi convention fleet ghi rõ "KHÔNG dùng @PreAuthorize" (bảo mật qua `public-paths` + `anyRequest().authenticated()`). Ở đây cần thiết (role SERVICE/ADMIN không diễn tả được bằng public-paths) — deviation có chủ đích, và promotion-service đã ghi chú T8 chứng minh hướng đi; **payment-service chưa có comment giải thích**. Đề xuất: thêm javadoc ngắn gọn như promotion để deviation không bị "sửa nhầm" về sau.

**provider/StripeProvider.java**

- [Low][error-handling] `StripeProvider.java:35-41` — `capture/refund` throw `UnsupportedOperationException` → 500 raw cho client khi `PAYMENT_PROVIDER=stripe`. Deviation có chủ đích (plan doc real-stripe-adapter). Đề xuất: khi implement, thay bằng `BusinessException` PAY-6xxx thay vì ngoại lệ raw.

### Files không có finding (payment-service)

`PaymentServiceApplication.java`, `controller/` (đã nêu trên), `dto/CreatePaymentRequest.java`, `dto/PaymentResponse.java`, `entity/Payment.java`, `entity/PaymentEvent.java`, `outbox/OutboxEvent.java`, `outbox/PaymentEventPublisher.java`, `provider/PaymentProviderConfig.java`, `provider/MockProvider.java` (đã nêu ở finding yml), `repository/PaymentEventRepository.java`, `service/PaymentService.java`, `service/PaymentStateMachine.java`, `service/ReceiptService.java`, `webhook/PaymentWebhookController.java`, `webhook/WebhookPayload.java`, `webhook/WebhookSignatureVerifier.java`, `resources/db/changelog/*` (đã nêu chi tiết ở findings), toàn bộ test: `PaymentFlowIT`, `PaymentControllerTest`, `PaymentControllerAuditTest`, `BackofficePaymentControllerTest`, `PaymentServiceImplTest`, `WebhookEventServiceTest`, `PaymentStateMachineTest`, `PaymentWriterTest`, `ReceiptServiceTest`, `PaymentWebhookControllerTest`, `WebhookSignatureVerifierTest`, `MockProviderTest`, `PaymentProviderConfigTest`, `AbstractIntegrationTest`, `PaymentBootstrapIT`, `TestLiquibaseConfig`.

---

## 2. Promotion-service

### Đánh giá tổng quan

Module possesão chặt chẽ theo spec: state machine idempotent commit/release/releaseCommitted với branch order rõ ràng, optimistic-lock retry idiom đúng (tách `ReservationRetryService` ra khỏi bean transactional để tránh self-invocation), race guard bằng version-touch có Javadoc giải thích (được hỗ trợ bởi `AbstractMappedEntity.setUpdatedAt` có chú thích chính chủ), outbox transaction + relay có metric + retention, TTL sweep theo batch với flush/clear. Test xuất sắc: unit full branch matrix, IT thật với test đồng thời 8 threads chống real Postgres. Không có Critical/High/Các secrets commit. Điểm trừ chính: reserve không idempotent theo orderId (500 raw khi saga retry), và một comment sai về transaction boundary trong scheduler.

### Findings theo file

**service/impls/CampaignReservationServiceImpl.java**

- [Medium][error-handling] `CampaignReservationServiceImpl.java:102-111` — `reserve()` không idempotent theo orderId: bảng có `uk_cur_order_id` unique (`changelog-001-initial-schema.yaml:118-123`), nhưng service không tra `findByOrderId` (declared ở `CouponUsageReservationRepository.java:37` nhưng **không được dùng**). Order-service retry reserve sau khi mất response (hoặc tái dùng orderId trong saga) → `DataIntegrityViolationException` raw → 500 không mã lỗi business, không thể phân biệt với lỗi hạ tầng. Đề xuất: trước khi insert, tra `findByOrderId`; nếu có reservation PENDING/COMMITTED cùng orderId → trả về reservation hiện hữu (idempotent retry) hoặc ném `BusinessException` PRO-7xxx rõ ràng.

**service/ReservationCleanupScheduler.java**

- [Medium][documentation] `ReservationCleanupScheduler.java:68-75` — comment "Flushed batches are rolled back with the transaction" **sai với hành vi thực tế**: `catch (Exception)` nằm *trong* phương thức `@Transactional`, nên exception không propagate ra proxy → các batch đã flush trước đó được **COMMIT**, không rollback. Hành vi thực tế an toàn (flip/delete idempotent, vòng sau quét tiếp) nhưng comment mô tả ngược transaction boundary ở luồng ảnh hưởng quota — người sửa sau dễ dựa vào comment mà phán đoán sai. Đề xuất: sửa comment cho đúng (partial commit có chủ đích + convergent), hoặc nếu muốn semantic rollback từng batch thì tách mỗi batch thành `REQUIRES_NEW`.
- [Low][clean-code] `ReservationCleanupScheduler.java:56,86` — dùng `Instant.now()` thay vì `Clock` đã có sẵn (`ClockConfig` + `Clock` inject trong reservation service) — mất seam testability đã xây dựng; unit test phải dựa real time (có rủi ro flaky ở biên ngày/retention). Đề xuất: inject `Clock` như `CampaignReservationServiceImpl`.

**service/PromotionEventPublisher.java**

- [Low][documentation] `PromotionEventPublisher.java:7-10` — Javadoc lỗi thời ("Task 6 ships a logging no-op; Task 10 replaces it with the transactional outbox publisher") trong khi impl hiện tại đã là transactional outbox; interface nay chỉ còn 1 impl (seam thừa). Đề xuất: cập nhật javadoc; nếu không còn nhu cầu seam cho test thì bỏ interface (ponytail: xóa trừu tượng thừa).

**service/impls/TransactionalPromotionEventPublisher.java**

- [Low][clean-code] `TransactionalPromotionEventPublisher.java:37` — tên class "Transactional" gây hiểu nhầm: class không tự `@Transactional`, phụ thuộc hoàn toàn vào tx của caller. Javadoc có giải thích. Đề xuất: đổi tên (vd `OutboxPromotionEventPublisher`) cho khớp vai trò.

**service/impls/CampaignServiceImpl.java & CampaignReservationServiceImpl.java**

- [Low][dry] `CampaignServiceImpl.java:39-41` và `CampaignReservationServiceImpl.java:37-38` — `COUNTED_STATUSES` (PENDING + COMMITTED) trùng lặp giữa hai class, chỉ nối nhau bằng comment "mirrors". Đề xuất: một hằng số dùng chung (vd trên `UsageStatus` hoặc util nhỏ) để hai nơi không lệch nhau khi spec đổi.

**entity/OutboxEvent.java (promotion)**

- [Low][clean-code] `OutboxEvent.java:68-69` — `retryCount` thiếu `@Builder.Default` (bản payment-service có, `payment .../OutboxEvent.java:41-43`): built bằng builder mà quên set → `null` → vi phạm NOT NULL khi insert (`LifecycleAndEventsIT:272` phải set tay). Đề xuất: thêm `@Builder.Default` cho nhất quán cross-module.

**resources/application.yml**

- [Low][pattern] `application.yml:65-70` — hai prefix config song song trong cùng file: `shop.promotion.*` (reservation) và `promotion.outbox.*` (relay) — khác cả với `shop.payment.outbox.*` của payment-service. Có comment giải thích bắt chước `inventory.outbox.*` → deviation có chủ đích. Đề xuất: cân nhắc hợp nhất về một prefix khi có dịp refactor config fleet.

**controller/PromotionReservationController.java**

- [Low][clean-code] `PromotionReservationController.java:60` — `releaseCommitted` trả `ApiResponse.ok(null)` trong khi `commit`/`release` (:44, :52) dùng `ApiResponse.message(...)`; thêm `:31` `reserve` dùng `@ResponseStatus(CREATED)` kết hợp `ApiResponse.ok` (mixed style với các POST 200 còn lại). Đề xuất: thống nhất một kiểu cho các action không trả payload.

**dto/request/CampaignRequest.java**

- [Low][clean-code] `CampaignRequest.java:23-24` — không validate quan hệ `startsAt`/`endsAt` (endsAt ≤ startsAt → "campaign zombie" không bao giờ active được, lưu âm thầm); không có cross-check khi status = ACTIVE. Đề xuất: class-level constraint kiểm tra `endsAt == null || startsAt == null || endsAt.isAfter(startsAt)` (chỉ nếu backoffice muốn từ chối sớm thay vì để reserve gate xử lý).

**service/PromotionOutboxRelay.java**

- [Low][testing] `PromotionOutboxRelay.java:58-81` — nhánh lỗi (retry increment, max-retries → FAILED, `break` giữ ordering) chưa có unit test; `LifecycleAndEventsIT` chỉ phủ happy drain. Đề xuất: unit test tương tự `ReservationCleanupSchedulerTest` (mock publisher ném lỗi, verify retry count/FAILED/break).

**service/PromotionMetrics.java**

- [Low][clean-code] `PromotionMetrics.java:49` — `setPendingOutboxCount` được gọi với `pending.size()` (≤ batch-size), nên gauge "pending count" bị trần bởi kích thước batch, không phản ánh tồn đọng thật khi quá tải — metric gây hiểu nhầm đúng lúc cần nó nhất. Đề xuất: đếm bằng COUNT query trong relay, hoặc đổi tên/ghi chú là "batch visible count".

**service/DiscountCalculator.java (+ validation/ValidDiscountValueValidator.java)**

- [Low][error-handling] `DiscountCalculator.java:32` — `IllegalStateException` raw cho discountType lạ (sẽ thành 500). Controller bị chặn bởi `@Pattern`, nhưng có sự lệch pha: validator dùng `equalsIgnoreCase` (`ValidDiscountValueValidator.java:21`) còn calculator dùng exact `equals` — gọi nội bộ với "percent" sẽ thất bại bất nhất. Đề xuất: thống nhất exact equality (hoặc normalize ngay từ DTO) và ném `BusinessException` thay vì raw.

### Files không có finding (promotion-service)

`PromotionServiceApplication.java`, `config/ClockConfig.java`, `constant/CampaignStatus.java`, `constant/UsageStatus.java`, `controller/BackofficeCampaignController.java`, `dto/response/CampaignResponse.java`, `dto/response/CampaignUsageResponse.java`, `dto/response/ReservationResponse.java`, `dto/request/ReserveRequest.java`, `entity/Campaign.java`, `entity/CouponUsageReservation.java`, `repository/CampaignRepository.java`, `repository/CouponUsageReservationRepository.java` (đã nêu tại finding reserve), `repository/OutboxEventRepository.java`, `service/CampaignService.java`, `service/CampaignReservationService.java`, `service/ReservationRetryService.java`, `service/OutboxRetentionScheduler.java`, `service/impls/CampaignServiceImpl.java` (đã nêu ở finding DRY), `service/impls/CampaignReservationServiceImpl.java` (đã nêu), `service/impls/ReservationRetryServiceImpl.java`, `validation/ValidDiscountValue.java`, `validation/ValidDiscountValueValidator.java` (đã nêu), `resources/db/changelog/*` (đã nêu chi tiết), toàn bộ test: `CampaignServiceTest`, `CampaignReserveTest`, `CampaignLifecycleTest`, `DiscountCalculatorTest`, `ReservationCleanupSchedulerTest`, `ReservationRetryServiceImplTest`, `CampaignRequestValidationTest`, `BackofficeCampaignControllerTest`, `PromotionReservationControllerTest`, `BackofficeCampaignAuditTest`, `PromotionReservationAuditTest`, `ReserveFlowIT`, `LifecycleAndEventsIT`, `AbstractIntegrationTest`, `PromotionBootstrapIT`, `TestLiquibaseConfig`.

---

## Ghi chú kiểm tra bổ sung (không tính là finding)

- **Secrets mắc commit**: đã chạy `git ls-files | grep -E "\.env|secret"` ở repo root — chỉ `.env.example` và `.env.prod.example` được track (file mẫu); `.env` local **không** tracked. Không phát hiện secret thật nào bị commit trong hai module.
- **Anti-IDOR**: dữ liệu user được truy cập qua các endpoint này đều do role SERVICE/ADMIN nắm giữ (không có endpoint USER-scope), và `ReserveRequest.userId` nằm trong body là thiết kế machine-to-machine có chủ đích (spec §5.1 — order-service nói hộ user). Không có attack path đạt confidence ≥ 8/10 để báo security finding; lưu ý trust model này phụ thuộc hoàn toàn vào việc cấp ROLE_SERVICE đúng cho nội bộ service.
- **Webhook security (payment)**: đạt — HMAC-SHA256 + so sánh thời gian hằng, chặn header không đúng độ dài/không hex, route nằm trong `public-paths` chỉ method POST và xác minh chữ ký trước khi chạm DB; secret rỗng = fail-closed.
- **Idempotency/double-charge**: tạo payment có idempotency key + unique index; capture/refund state machine bảo vệ chống transition sai. Điểm cần xử lý (đã nêu ở findings): refund không chuyển state ngay nên phụ thuộc webhook, và retry không mind cho webhook FAILED events.

---

## Bảng tổng hợp findings

### Theo severity × category

| Severity \ Category | pattern | clean-code | solid | dry | error-handling | logging | security | performance | documentation | testing | Tổng |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Critical | - | - | - | - | 1 | - | - | - | - | - | **1** |
| High | - | - | - | - | 1 | - | - | - | - | - | **1** |
| Medium | 1 | - | - | - | 3 | - | - | 1 | 1 | 1 | **7** |
| Low | 2 | 8 | - | 2 | 2 | - | - | - | 1 | 1 | **16** |
| **Tổng** | **3** | **8** | **0** | **2** | **7** | **0** | **0** | **1** | **2** | **2** | **25** |

### Mức độ tuân thủ pattern chung

| Module | Tuân thủ pattern | Độ sạch & test | Điểm cần sửa ưu tiên |
|---|---|---|---|
| **payment-service** | Tốt (8/10) — đủ layer, DTO-fields mapping tay, BusinessException PAY-5xxx, outbox chuẩn, webhook secure; deviation `@PreAuthorize` có lý do nhưng thiếu comment giải thích | Khá tốt; test sâu (slice + IT thật + replay + poison payload); một số dead code nhỏ | 1) Retry/lifecycle cho webhook event FAILED (Critical); 2) ghi nhận kết quả/state khi gọi PSP capture/refund + idempotency key per-operation (High); 3) outbox retention; 4) create idempotency edge |
| **promotion-service** | Rất tốt (9/10) — spec-driven, state machine + retry idiom đúng, race guard có giải thích, metrics + retention + TTL sweep đầy đủ; deviations đều có comment chủ đích | Rất sạch; test mạnh nhất fleet (branch matrix unit + IT 8-thread race thật); một vài comment lỗi thời/sai | 1) Idempotent reserve theo orderId (500 raw khi saga retry); 2) sửa comment transaction boundary trong scheduler; 3) dọn COUNTED_STATUSES trùng + javadoc cũ |