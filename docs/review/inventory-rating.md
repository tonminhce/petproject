# Code Review — inventory-service & rating-service

- **Trạng thái:** READ-ONLY — không sửa file code nào của dự án; file duy nhất được tạo là báo cáo này.
- **Phạm vi:** toàn bộ `inventory-service/src` (41 file) và `rating-service/src` (39 file) — main/java, test/java, resources. **Không đọc `media-service/`.**
- **Tổng số file đã đọc:** 80/80 (không bỏ sót file nào).
- **Baseline so sánh:** pattern chuẩn fleet (layered controller/dto/entity/mapper/repository/service+impls, ApiResponse/PageResponse, BusinessException+ErrorCode, Kafka qua outbox, `AuthenticatedUser.requireCurrent()`, `PageableConstant.MAX_PAGE_SIZE`, security qua filter chain + public-paths trong application.yml).

Kết luận nhanh: **0 Critical, 0 High**. Cả hai module tuân thủ tốt baseline chuẩn (outbox transactional, optimistic lock + retry, fail-closed eligibility, IDOR-safe). Vấn đề tập trung ở data-integrity/inventory (tồn kho âm qua đường admin, race delete/reserve, retention bỏ sót trạng thái terminal) và resilience của outbound call ở rating-service (timeout thiếu, fail-closed bị thủng).

---

# 1. INVENTORY-SERVICE

## service/impls/InventoryServiceImpl.java

- **[Medium][solid] `service/impls/InventoryServiceImpl.java:88` — `update()` không chặn `availableQuantity < reservedQuantity`. Admin có thể set available thấp hơn lượng đang giữ chỗ; `commit()` sau đó trừ tiếp (dòng 165-166) → `available_quantity` âm trong DB. Không có CHECK constraint nào ở tầng DB (`changelog-001-initial-schema.yaml` chỉ có `defaultValueNumeric: 0`, không `CHECK (available_quantity >= 0)` / `(reserved >= 0)`). Đề xuất: validate `request.availableQuantity() >= existing.getReservedQuantity()` khi update + thêm CHECK constraint ở changelog làm lớp phòng thủ cuối.

- **[Medium][error-handling] `service/impls/InventoryServiceImpl.java:106` — `delete()` có TOCTOU: `countByProductIdAndStatusIn` (dòng 110-111) rồi `delete(existing)` không serialized bởi lock nào; `reserve()` chạy song song có thể insert reservation sau khi count = 0 → reservation mồ côi khiến `commit`/`release` của order sau đó ném `INVENTORY_NOT_FOUND` (404 cho order-service). `changelog-001` cũng không có FK `reservations.product_id → inventory`. Đề xuất: FK + (tùy chọn) xóa bằng câu DELETE có điều kiện đếm, hoặc chấp nhận và để reserve-side xử lý INVENTORY_NOT_FOUND có chủ đích (ghi rõ trong contract).

- **[Medium][logging] `service/impls/InventoryServiceImpl.java:152-239` — thiếu dấu vết "ai trừ bao nhiêu" cho giao dịch trừ kho. `@Audited` ở controller (reserve/commit/release) chỉ ghi action/resourceType/resourceId/actor — không có `quantity`; service không log gì cho commit/release/releaseCommitted (chỉ log idempotent no-op). Đề xuất: thêm quantity (và previousStatus) vào audit line hoặc log cấu trúc khi commit/releaseCommitted (đây là thay đổi số dư thực tế).

- **[Low][solid] `service/impls/InventoryServiceImpl.java:266-284` — `releaseExpiredReservations()` trừ `reservedQuantity` không có guard `Math.max(0, ...)`, trong khi `ReservationCleanupScheduler.java:85` làm cùng thao tác CÓ guard. Nếu dữ liệu lệch (reserved < tổng expired), đường này đẩy `reservedQuantity` âm còn đường scheduler thì kẹp về 0. Đề xuất: dùng chung một guard nhất quán.

## service/impls/ReservationServiceImpl.java

- **[Medium][dry] `service/impls/ReservationServiceImpl.java:31-91` — 4 wrapper retry (`reserveWithRetry`/`commitWithRetry`/`releaseWithRetry`/`releaseCommittedWithRetry`) lặp lại y hệt nhau ~20 dòng mỗi cái: loop + backoff + map `OptimisticLockingFailureException` → `INVENTORY_VERSION_CONFLICT`. Đề xuất: gom thành 1 helper `withRetry(Supplier<T>/Runnable, id)` dùng chung 4 nơi.

- **[Low][error-handling] `service/impls/ReservationServiceImpl.java:38/53/69/85` — chỉ catch `OptimisticLockingFailureException`. Trên product nóng, deadlock hoặc lock-timeout của Postgres (`CannotAcquireLockException`/`PessimisticLockException`) không được retry mà lọt thẳng ra ngoài thành 500. Đề xuất: nhóm `OptimisticLockingFailureException` + `CannotAcquireLockException` vào cùng nhánh retry.

## service/ReservationCleanupScheduler.java

- **[Medium][documentation] `service/ReservationCleanupScheduler.java:74` — comment "Batches already flushed stay committed; the next cycle continues" SAI so với code: `@Transactional` đặt trên cả method `releaseAllExpiredReservations` (dòng 51) bao trùm toàn bộ vòng lặp batch → bất kỳ lỗi nào ở batch sau rollback TẤT CẢ (kể cả batch đã flush+clear). Đề xuất: hoặc sửa comment, hoặc tách `@Transactional` xuống từng batch (mỗi batch là `REQUIRES_NEW` hoặc method riêng) để đúng ý định "partial progress giữ lại".

- **[Medium][performance] `service/ReservationCleanupScheduler.java:100-110` — retention chỉ purge `EXPIRED`. Các trạng thái terminal `RELEASED` (từ `release()` và `releaseCommitted()`) và `COMMITTED` KHÔNG BAO GIỜ bị xóa → bảng `reservations` tăng vô hạn (~1 row mỗi đơn hàng bình thường), trong khi Javadoc của `Reservation` tuyên bố "terminal rows are purged after 30 days". Đề xuất: thêm purge cho RELEASED/COMMITTED với cutoff phù hợp (hoặc sửa Javadoc nếu giữ lại có chủ đích vì reconciliation).

## controller/InventoryController.java

- **[Low][pattern] `controller/InventoryController.java:42-111` — dùng `@PreAuthorize` trên toàn bộ endpoint, lệch baseline "KHÔNG dùng @PreAuthorize" (deviation CÓ chủ đích — application.yml:66-71 giải thích gating theo role → hạ 1 bậc severity). Hai nốt phụ: (i) `isAuthenticated()` ở dòng 42/52 thừa so với `anyRequest().authenticated()` của filter chain; (ii) không nhất quán với `StorefrontRatingController` trong chính lần review này (rating cố tình KHÔNG dùng @PreAuthorize với lý do "Keycloak user có thể thiếu realm role"). Đề xuất: chốt 1 quy ước fleet và áp cho cả hai.

- **[Low][clean-code] `controller/InventoryController.java:105-107` — `releaseCommitted` trả `ApiResponse.ok(null)` (data null không cần thiết), và `reserve` (dòng 78-84) trả envelope `ok(...)` trong khi HTTP status là 201 — semantics của envelope không khớp status code. Đề xuất: dùng variant `created(...)` nếu common-core có; tránh `ok(null)`.

- **[Low][logging] `controller/InventoryController.java:58-72` — `create`/`update`/`delete` (thay đổi/хóa stock record) không có `@Audited` trong khi reserve/commit/release thì có. Đề xuất: thêm `@Audited` cho delete (hành động phá hủy không phục hồi dễ dàng).

- **[Low][error-handling] `controller/InventoryController.java:45-48` — `page` không validate `>= 0`: `?page=-1` → `PageRequest.of` ném `IllegalArgumentException` → 500 thay vì 400. Đề xuất: dùng `page = Math.max(0, page)` hoặc validate ở controller.

## service/impls/TransactionalInventoryEventPublisher.java

- **[Low][dry] `service/impls/TransactionalInventoryEventPublisher.java:46-89` — `publishReserved`/`publishCommitted`/`publishReleased` lặp lại 3 lần đúng 1 khối build map (productId/reservationId/quantity/orderId null-guard). Đề xuất: tách 1 method `reservationData(reservation)` dùng chung.

## mapper/InventoryMapper.java

- **[Low][clean-code] `mapper/InventoryMapper.java:29-35` — `toEntity()` chạy `ModelMapper.map` rồi set lại tay toàn bộ field đáng lẽ được map (id/availableQuantity/reservedQuantity) — trộn lẫn hai cách mà không giá trị gì; `partialUpdate:37-42` có null-guard vô nghĩa vì request field là `@NotNull`. Baseline khuyên: field ít thì map tay hoàn toàn. Đề xuất: bỏ ModelMapper khỏi class này, map tay.

## service/InventoryOutboxRelay.java (+ RatingOutboxRelay cùng mô hình)

- **[Low][error-handling] `service/InventoryOutboxRelay.java:58` — relay không có cơ chế claim row (không `SELECT ... FOR UPDATE SKIP LOCKED`, không khóa): nếu service scale 2+ instance, cùng một row PENDING có thể được publish 2 lần. Giả định single-instance không được ghi ở đâu. (Rating-service có cùng vấn đề.) Đề xuất: ít nhất ghi rõ giả định single-instance + consumer idempotent; nếu cần scale, dùng claim-lock. Nốt phụ: `InventoryMetrics.setPendingOutboxCount(pending.size())` chỉ phản ánh batch hiện tại (≤ batch-size 100) chứ không phải tổng pending — tên metric gây hiểu lầm.

## main/resources/application.yml

- **[Low][security] `main/resources/application.yml:16-17` — default credential `POSTGRES_PASSWORD:admin` (và username `admin`) nằm trong config được git track. Mẫu `${ENV_VAR:default}` đúng pattern, nhưng default dễ đoán + được ghi tài liệu là "thực dùng" nếu quên set env. Đề xuất: giữ default chỉ cho dev; đảm bảo compose/prod luôn inject env (kiểm tra bằng `git ls-files` cho file env — không có file .env nào bị track).

## Test (inventory)

- **[Low][testing] `test/java/com/shop/inventoryservice/controller/InventoryControllerTest.java:97,104` — `commit_returns200`/`release_returns200` chỉ assert envelope success, không stub service, không `verify` service nào được gọi (assert rỗng). Và `addFilters = false` (dòng 35) bỏ qua security chain cho các endpoint của controller này (bù đắp một phần bởi ReservationStateEndpointTest + AuditTest chỉ tập trung endpoint reserve/state). Đề xuất: verify tương tác service; thêm 1 test USER→403 cho create/update/delete.

- **[Low][testing] toàn module — không có test integration cho đua tranh đồng thời reserve/commit (2 luồng cùng sản phẩm). Hiện có: unit test retry wrapper + repository test stale-write OLE — cơ chế được test rời, nhưng bài toán "trừ kho đồng thời không âm" chưa được chứng minh end-to-end. Đề xuất: 1 IT multi-thread reserve→commit trên cùng productId.

## Files không có finding (inventory-service)

`InventoryServiceApplication.java`, `config/CacheConfig.java`, `constant/ReservationStatus.java`, `dto/request/InventoryUpsertRequest.java`, `dto/request/ReserveRequest.java`, `dto/response/InventoryResponse.java`, `dto/response/ReservationResponse.java`, `entity/Inventory.java`, `entity/OutboxEvent.java`, `entity/Reservation.java`, `repository/InventoryRepository.java`, `repository/OutboxEventRepository.java`, `service/InventoryCacheService.java`, `service/InventoryEventPublisher.java`, `service/InventoryService.java`, `service/ReservationService.java`, `service/OutboxRetentionScheduler.java`, `db/changelog/db.changelog-master.yaml`, `test/config/TestLiquibaseConfig.java`, `test/controller/InventoryControllerAuditTest.java`, `test/controller/ReservationStateEndpointTest.java`, `test/repository/InventoryRepositoryTest.java`, `test/service/InventoryLifecycleIdempotencyTest.java`, `test/service/InventoryOutboxRelayIntegrationTest.java`, `test/service/ReleaseCommittedTest.java`, `test/service/ReservationCleanupSchedulerTest.java`, `test/service/impls/InventoryServiceImplTest.java`, `test/service/impls/ReservationServiceImplTest.java`, `test/support/AbstractIntegrationTest.java`.

---

# 2. RATING-SERVICE

## service/impls/RatingServiceImpl.java

- **[Medium][performance] `service/impls/RatingServiceImpl.java:35-64` — `submit()` là `@Transactional` nhưng gọi HTTP ra order-service (`eligibilityClient.isEligible`, dòng 44) NGAY TRONG transaction: giữ connection/transaction DB mở trong suốt network I/O (đến 3s timeout, hoặc hơn khi có retry/treo). Dưới tải, pool connection bị chiếm giữ thời gian dài. Đề xuất: gọi eligibility TRƯỚC khi mở transaction (tách verify ra ngoài `@Transactional`), hoặc chuyển sang `@Transactional` chỉ quanh thao tác write.

- **[Medium][error-handling] `service/impls/RatingServiceImpl.java:37-39` — race submit trùng: hai request đồng thời cùng (user, product) đều vượt pre-check `findByUserIdAndProductId...`; cái thứ hai đập unique partial index `uk_rating_user_product_live` → `DataIntegrityViolationException` không được dịch → 500 thay vì 409 `RTG-11005`. Index là phòng thủ tốt, nhưng cần xử lý lỗi. Đề xuất: catch constraint violation trong submit và map sang `RATING_ALREADY_EXISTS` (hoặc dùng `insert ... on conflict`).

- **[Low][pattern] `service/impls/RatingServiceImpl.java:67-73` — build `PageRequest` + cap size `PageableConstant.MAX_PAGE_SIZE` ở SERVICE layer; baseline chuẩn đặt ở controller (so sánh `InventoryController.findAll`). Đề xuất: chuyển cap về controller cho nhất quán fleet.

## config/RestClientConfig.java

- **[Medium][error-handling] `config/RestClientConfig.java:53-56` — bean `restClientBuilder` (dùng bởi `ServiceTokenProvider`) KHÔNG set connect/read timeout, khác hẳn `orderRestClient` (timeout 3s). Default `SimpleClientHttpRequestFactory` không có timeout → Keycloak treo = `refreshToken()` (synchronized) treo vô hạn, chặn MỌI luồng `verify-purchase` của service (dead-lock theo nghĩa liveness). Đề xuất: cấu hình timeout cho client token endpoint (nên nhỏ, ~2-3s) và cân nhắc giới hạn thời gian chờ synchronized refresh.

## eligibility/EligibilityClient.java

- **[Medium][error-handling] `eligibility/EligibilityClient.java:57,64` — contract fail-closed "ANY failure maps to false" (ghi rõ trong Javadoc dòng 353-358) bị thủng: `ServiceTokenProvider.getToken()` ném `IllegalStateException`/NPE (không phải `RestClientException`) → thoát khỏi `catch (RestClientException)`, không log gì, và `submit()` trả 500 thay vì 403 `RTG-11001`. Test hiện tại (`submit_clientFailureSeam_propagatesWithoutSave`) còn "chính thức hóa" hành vi 500 này. Đề xuất: catch rộng ra ở seam token-provider (hoặc biến token fetch thành phần của try với log), giữ nguyên fail-closed text.

## outbox/

- **[Medium][performance] `outbox/OutboxEventRepository.java:19` — `deleteByStatusAndSentAtBefore` KHÔNG ĐƯỢC GỌI ở đâu (không có retention scheduler; grep toàn module cho thấy method mồ côi). Inventory-service có `OutboxRetentionScheduler` purge SENT > 7 ngày với đầy đủ lý do; rating-service thì bảng `outbox_events` tăng vô hạn (1 row mỗi lifecycle write). Đề xuất: thêm scheduler purge SENT > N ngày giống inventory, hoặc xóa method chết nếu cố tình không purge (kèm comment lý do).

## main/resources/application.yml

- **[Low][security] `main/resources/application.yml:43` — `client-secret: ${RATING_SERVICE_CLIENT_SECRET:changeme}`: secret mặc định hiển nhiên trong config tracked, sẽ được dùng thật nếu ops quên set env. Đề xuất: bỏ default (fail-fast khi thiếu env) hoặc thay bằng giá trị ngẫu nhiên mỗi môi trường.

- **[Low][pattern] `main/resources/application.yml:22-23` — `shop.kafka` chỉ set `bootstrap-servers`, không set producer `acks`; inventory-service (`application.yml:39-42`) set `acks: all` + comment "the outbox relay must not lose events". Độ bền outbox giữa hai module khác nhau một cách thầm lặng. Đề xuất: xác nhận default của common-kafka; nếu không có, set `acks: all` cho nhất quán.

## dto/response/RatingResponse.java

- **[Low][pattern] `dto/response/RatingResponse.java:30-41` — static factory `from()` đặt trên DTO record thay vì package `mapper/` theo baseline (inventory có `InventoryMapper` component). Deviation không có comment giải thích (dù bản thân cách này gọn). Đề xuất: hoặc di chuyển sang mapper component cho nhất quán, hoặc thêm 1 dòng code-comment ghi nhận deviation chủ đích.

## security/ServiceTokenProvider.java

- **[Low][testing] `security/ServiceTokenProvider.java` — không có bất kỳ test nào cho logic cache/refresh/skew (30s) (thư mục test không có `security/`). Thành phần quan trọng cho mọi outbound call. Đề xuất: test ít nhất: không refresh khi token còn hạn, refresh khi gần hết hạn, single-flight khi nhiều luồng.

## controller/StorefrontRatingController.java

- **[Low][logging] `controller/StorefrontRatingController.java:42-58` — submit/edit (thao tác ghi của người dùng) không có `@Audited` và service không log gì; chỉ backoffice hide/unhide có audit. Đề xuất: cân nhắc thêm `@Audited` cho submit (tạo nội dung người dùng) tương tự reserve của inventory.

- **[Low][error-handling] `controller/StorefrontRatingController.java:214-217` — `page` không validate `>= 0` → `?page=-1` ném `IllegalArgumentException` → 500 (giống inventory). Đề xuất: clamp hoặc validate.

## test/support/AbstractIntegrationTest.java (rating)

- **[Low][testing] `test/support/AbstractIntegrationTest.java:22-28` — containers khởi động trong static block, không stop, KHÔNG có shutdown hook và không có Javadoc giải thích; bản inventory có cả shutdown hook lẫn Javadoc lý do (context caching Testcontainers). Hiện tại container Docker bị rò rỉ sau khi build test kết thúc. Đề xuất: đồng bộ với bản inventory (hook + comment), hoặc chuyển sang `@Testcontainers`.

## Files không có finding (rating-service)

`RatingServiceApplication.java`, `config/RatingClientProperties.java`, `constant/RatingAction.java`, `controller/BackofficeRatingController.java`, `dto/request/RatingEditRequest.java`, `dto/request/RatingHideRequest.java`, `dto/request/RatingSubmitRequest.java`, `entity/Rating.java`, `metrics/RatingMetrics.java`, `outbox/OutboxEvent.java`, `outbox/RatingOutboxRelay.java` (trừ vấn đề chung đã nêu ở mục relay của inventory), `repository/RatingRepository.java`, `service/RatingService.java`, `service/RatingEventService.java`, `db/changelog/changelog-001-ratings.yaml`, `db/changelog/db.changelog-master.yaml`, `test/config/TestLiquibaseConfig.java`, `test/controller/BackofficeRatingAuditTest.java`, `test/controller/BackofficeRatingControllerTest.java`, `test/controller/StorefrontRatingControllerTest.java`, `test/dto/RatingSubmitValidationTest.java`, `test/eligibility/EligibilityClientTest.java`, `test/entity/RatingMappingIT.java`, `test/i18n/RatingI18nKeysTest.java`, `test/metrics/RatingMetricsTest.java`, `test/outbox/RatingOutboxRelayTest.java`, `test/service/RatingEventServiceTest.java`, `test/service/impls/RatingServiceImplEditTest.java`, `test/service/impls/RatingServiceImplModerationTest.java`, `test/service/impls/RatingServiceImplTest.java`.

---

# TỔNG HỢP

## Bảng findings theo severity × category

| Category \ Severity | Critical | High | Medium | Low | Tổng |
|---|---|---|---|---|---|
| solid | 0 | 0 | 1 | 1 | 2 |
| error-handling | 0 | 0 | 4 | 4 | 8 |
| logging | 0 | 0 | 1 | 2 | 3 |
| dry | 0 | 0 | 1 | 1 | 2 |
| documentation | 0 | 0 | 1 | 0 | 1 |
| performance | 0 | 0 | 3 | 0 | 3 |
| pattern | 0 | 0 | 0 | 4 | 4 |
| clean-code | 0 | 0 | 0 | 2 | 2 |
| security | 0 | 0 | 0 | 2 | 2 |
| testing | 0 | 0 | 0 | 4 | 4 |
| **Tổng** | **0** | **0** | **11** | **20** | **31** |

(Lưu ý bảng đếm theo bullet tương ứng ở trên.)

## Đánh giá pattern compliance & độ sạch chung

**Inventory-service (41 file):** Tuân thủ pattern tốt — cấu trúc layered chuẩn, layered service/impls, outbox transactional có tài liệu rõ, optimistic lock (@Version) + retry wrapper cho trừ kho là thiết kế đúng và được test ở 3 tầng (repository/unit/IT). Điểm lệch chuẩn duy nhất là `@PreAuthorize` method-level (có chủ đích, ghi trong application.yml). Yếu nhất: data-integrity cận biên (update không chặn âm, race delete/reserve, retention bỏ sót RELEASED/COMMITTED) và một comment mô tả sai hành vi transaction. Tài liệu comment cực kỳ chi tiết (hiếm thấy) nhưng cần rà lại tính đúng đắn của comment với code.

**Rating-service (39 file):** Code gọn, sạch, test slice/webmvc/service đầy đủ hơn hẳn mức trung bình (đủ security matrix, constraint DB, i18n, fail-closed). IDOR được chặn đúng chuẩn (ownership resolve từ JWT userId ở service layer, không có endpoint theo id thuần storefront). Điểm trừ: resilience của outbound call (timeout thiếu trên token client, fail-closed thủng một nhánh, HTTP-in-transaction) và 2 khoảng trống vận hành (outbox không purge, container test rò rỉ). Deviation nhỏ về mapper/pageable không có comment chủ đích.

**Cross-module:** hai module nhất quán về envelope, ErrorCode, outbox và changelog; các điểm bất nhất cần chốt quy ước chung: (1) dùng hay không dùng `@PreAuthorize`, (2) retention outbox/reservation áp cho những trạng thái nào, (3) nơi build/cap Pageable.