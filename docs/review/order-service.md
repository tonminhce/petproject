# Review Report — order-service

- Phạm vi: `/order-service/src` (main + test + resources) — **read-only**, không đọc media-service/
- Người review: senior Java reviewer (agent)
- Ngày: 2026-09-02
- Số file đã đọc: **90** (63 main, 27 test)
- Tham chiếu chéo đã xác minh: `utils/common-kafka` (BaseKafkaConsumer, BaseKafkaListenerConfig, KafkaAutoConfiguration), `utils/common-security` (BaseSecurityConfig, JwtRolesConverter, AuthenticatedUser), `utils/common-spring` (ApiExceptionHandler), `utils/common-core` (ErrorCode, PageableConstant, BusinessException, AbstractMappedEntity)
- Kiểm tra secrets committed: `git ls-files` chỉ thấy `.env.example`, `.env.prod.example` — không có file chứa secret thật bị track.

**Nhận định tổng quan (đọc trước):** đây là module có chất lượng tốt nhất trong nhánh review này — saga tạo đơn có compensation tường minh, transactional outbox, idempotency chống race, @Version cho chuyển trạng thái đồng thời, và bộ test dày đặc khác thường (IT với Testcontainers + WireMock chứng minh cả ordering và compensation, kể cả 2 test concurrency + @RepeatedTest cache). Các tìm ra dưới đây phần lớn là Low; 2 vấn đề đáng làm ngay nằm ở consumer Kafka nuốt exception và lỗ hổng compensation khi lỗi không phải stock-409.

---

## Findings theo file

### `kafka/ShippingDeliveredConsumer.java`

- **[High][error-handling] `kafka/ShippingDeliveredConsumer.java:24-30`** — `handleContained` bọc mọi exception của `handler.handle()` trong try/catch và chỉ log. Vì `BaseKafkaConsumer.processMessage` không ném ra ngoài, listener trả về thành công ⇒ Spring Kafka **commit offset** ⇒ sự kiện `shipping.delivered.v1` bị **mất vĩnh viễn** khi có lỗi tạm thời (DB down, connection pool cạn). Không có DLT, không retry (`BaseKafkaListenerConfig` có `ErrorHandlingDeserializer` chỉ lo deserialization, không lo handler exception; `auto-offset-reset: latest` + group-id cố định khiến sự kiện cũ cũng không được đọc lại). Hậu quả: đơn kẹt ở `SHIPPED` vĩnh viễn, mất luồng "delivered" (khách nhận hàng mà hệ thống không ghi nhận — luồng tiền/refund bị ảnh hưởng). Test `ShippingDeliveredConsumerTest.onMessage_handlerThrows_exceptionContainedNeverEscapesListener` đã "codify" chính anti-pattern này. Đề xuất: rethrow để Spring Kafka retry + nối `DefaultErrorHandler`/DLT topic; hoặc ít nhất đếm metric "consume failed" + đưa vào reconciliation scan cho SHIPPED-old.

### `client/InventoryServiceClient.java` + `service/impls/OrderServiceImpl.java`

- **[Medium][error-handling] `client/InventoryServiceClient.java:55-65` + `service/impls/OrderServiceImpl.java:159-184`** — lỗ hổng compensation trong saga tạo đơn: vòng lặp reserve chỉ bắt `StockReservationFailedException` (phát sinh từ 409/404). Nếu call reserve thứ N thất bại kiểu khác — **5xx inventory** (`HttpServerErrorException` không được catch trong client, trào ra raw) hoặc 401/4xx khác (client map thành `BusinessException INTERNAL_SERVER_ERROR`) — thì exception này không rơi vào catch ⇒ các reservation của item 1..N-1 (đã gọi reserve thành công từ xa) **không được release**, rò rỉ stock tới hết TTL inventory (~30 phút, do reservation `expiresAt`). TX cục bộ rollback (không còn order row) nên reconciliation không thấy gì để quét. Đề xuất: bắt `RuntimeException` (hoặc mọi exception từ STEP reserve) trong vòng lặp, chạy `releaseAllReservations` rồi rethrow/dịch mã lỗi phù hợp — compensation phải phủ mọi mode thất bại của reserve, không chỉ stock-409.

### `client/ProductServiceClient.java` (liên quan `OrderServiceImpl`, `CartServiceImpl`)

- **[Medium][error-handling] `client/ProductServiceClient.java:36-50`** — `@Cacheable("productPrice")` không khai báo `CacheErrorHandler`. Mặc định Spring (`SimpleCacheErrorHandler`) rethrow ⇒ khi Redis sập, **mọi call** `getProduct` ném exception ⇒ add-to-cart và create-order đều 500 dù product-service vẫn khỏe mạnh. Redis (cache) vô tình trở thành hard dependency của luồng tiền. Đề xuất: `CacheErrorHandler` log-and-proceed (cache miss → call thẳng) để cache hạ giá thành best-effort.
- **[Low][error-handling] `client/ProductServiceClient.java:48-49`** — `resp` và `resp.data()` không guard null (product-service trả `200 {success:true, data:null}` hoặc body rỗng ⇒ NPE ⇒ 500). Ghi chú: `CartServiceImpl.java:54` **có** guard `snapshot == null` nhưng `OrderServiceImpl`/`PricingServiceImpl` thì không — không nhất quán. Đề xuất: thống nhất guard null = `BusinessException PRODUCT_NOT_FOUND` ở client.

### `service/impls/OrderServiceImpl.java`

- **[Medium][testing] `service/impls/OrderServiceImpl.java:263-289`** — race cancel-vs-confirm chưa có test và đang phụ thuộc hoàn toàn vào giả định "remote state machine nghiêm ngặt": `cancelOrder` gọi release stock + release promotion **từ xa trước** khi save CANCELLED được `@Version` bảo vệ. Nếu một bên inventory/promotion trả 200 cho release trên reservation đã COMMITTED (idempotent), ta có order CONFIRMED nhưng stock đã về kho — không có cơ chế nào sửa (reconciliation chỉ quét PENDING). Hiện tại interleave chính (người dùng cancel trong khi admin confirm) được cứu bởi giả định inventory/promotion reject release-committed ở sai trạng thái, nhưng `ConfirmOrchestrationIT` chỉ test confirm-vs-confirm. Đề xuất: thêm IT cancel-vs-confirm (như `race()` hiện có), và xác nhận contract "release trên COMMITTED phải fail" với inventory/promotion-service bằng văn bản spec.
- **[Low][clean-code] `service/impls/OrderServiceImpl.java:349-374`** — log "Confirm commit failed for order {}" xuất hiện 2 lần (inner catch 352 + outer catch 372) và nhánh wrap ở outer catch hầu như không thể chạy cho luồng coordinator (mọi RuntimeException của coordinator đã bị wrap thành `BusinessException` ở inner catch rồi). Luồng điều khiển khó đọc, dễ gây tưởng có 2 điểm xử lý. Đề xuất: gộp một chỗ — inner catch log + wrap, outer catch chỉ abort + rethrow.
- **[Low][error-handling] `service/impls/OrderServiceImpl.java:152-153`** — `pricing.snapshots().get(item.getProductId())` rồi gọi thẳng `snapshot.title()` mà không guard: nếu pricing trả về map thiếu productId (behavior thay đổi của PricingService — VD cache trả snapshot của item đã bị thay) ⇒ NPE ⇒ 500 với dữ liệu đã insert ở giữa saga. Đề xuất: `Objects.requireNonNull` + ném `BusinessException` có OrderId context.
- **[Low][clean-code] `service/impls/OrderServiceImpl.java:397-403`** — `transitionStatus` có `case CANCELLED` (chú thích "not used here") + `default` không thể xảy ra; vì chỉ gọi với SHIPPED/DELIVERED, switch 5 nhánh là dead code thừa vòng if. Đề xuất: rút còn if/else cho 2 nhánh thực.

### `controller/OrderController.java`

- **[Low][error-handling] `controller/OrderController.java:52-57, 63-70, 89-95`** — `page`/`size` không validate giá trị âm: `PageRequest.of(-1, …)` hoặc `Math.min(-5, MAX)` ném `IllegalArgumentException` ⇒ fallback handler biến thành 500 thay vì 400 (chỉ mỗi việc cap MAX được làm). Đề xuất: clamp `Math.max(0, page)` / `Math.max(1, size)` trước khi tạo PageRequest.
- **[Low][dry] `controller/OrderController.java:99-101` vs `controller/CartController.java:53-55`** — `private static UUID currentUserId()` lặp lại nguyên xi ở 2 controller (và OrderStatusController làm inline 1 lần nữa). Đề xuất: helper dùng chung (VD trong common-security hoặc static util của module).
- **[Low][error-handling] `controller/OrderStatusController.java:31-33`** — endpoint confirm nhận `Idempotency-Key` header nhưng **không** có guard 64 ký tự như `OrderController.createOrder` (guard đã được thêm sau review M5). Key > 64 ⇒ `DataIntegrityViolationException` từ DB ⇒ 409 CONFLICT kèm cả `mostSpecificCause` thô, không nhất quán với create. Đề xuất: thêm cùng guard (hoặc tách validator chung).

### `controller/*` (pattern note)

- **[Low][pattern] `controller/CartController.java:20`, `controller/OrderController.java:27`, `controller/OrderStatusController.java:23`** — dùng `@PreAuthorize` ở tầng controller trong khi baseline pattern là "bảo mật do BaseSecurityConfig filter chain + public-paths, KHÔNG dùng @PreAuthorize". **Deviation có chủ đích**: role gate (`hasRole('ADMIN')`, `hasAnyRole('SERVICE','ADMIN')`) không thể biểu đạt bằng public-paths, và `OrderController.java:33-36` có comment P2-6 giải thích lý do không dùng `hasRole('USER')`. Đề xuất: giữ nguyên nhưng document deviation này vào README/service note để reviewer sau không "sửa" theo baseline. Xác minh tích cực: `JwtRolesConverter` phát cả `ROLE_`-prefixed authority nên `hasRole('ADMIN')` khớp realm role, không có mismatch fail-open.

### `client/PaymentServiceClient.java` + `service/impls/OrderServiceImpl.java`

- **[Low][dry] `client/PaymentServiceClient.java:39,71-73` vs `service/impls/OrderServiceImpl.java:336`** — magic string `"CAPTURED"` xuất hiện ở 2 file: client đã filter `STATUS_CAPTURED.equals(status)` rồi, caller lại check lại `"CAPTURED".equals(payment.status())` (redundant kill). Đề xuất: hằng chung (hoặc bỏ check thứ 2, giữ guard ở client như contract).

### `service/impls/IdempotencyServiceImpl.java`

- **[Low][error-handling] `service/impls/IdempotencyServiceImpl.java:94-105`** — corsair window: nếu process crash sau `begin()` (in-flight row đã commit trong REQUIRES_NEW) mà trước `complete()`, hàng in-flight tồn tại tới hết TTL **24h**; mọi lần retry cùng key của client bị chặn bằng 409 `ORDER_DUPLICATE_REQUEST` (không replay, không re-run). Không có stale-detection (vd in-flight quá X phút → coi như chết). Đề xuất: thêm timestamp `started_at` + stale cleanup, hoặc cho resolve() coi in-flight quá TTL/ngưỡng là "có thể chiếm lại" khi hash khớp.

### `service/OrderReconciliationScheduler.java`

- **[Low][performance] `service/OrderReconciliationScheduler.java:91-94`** — `findByStatusAndCreatedAtBefore` load toàn bộ PENDING-cũ vào bộ nhớ mỗi 5 phút, không phân trang; polling state tuần tự từng item. Khi có sự cố kéo dài (inventory sập > 30 phút), danh sách có thể rất dài và mỗi vòng lặp gây N+1 HTTP. Với quy mô MVP chấp nhận được, nhưng cần có comment ghi rõ giới hạn này hoặc sweep theo lô. Đề xuất: `PageRequest` + cursor by createdAt.
- **[Low][clean-code] `service/OrderReconciliationScheduler.java:51-53,112-113`** — magic string `"COMMITTED"/"RELEASED"/"EXPIRED"` so khớp với `ReservationStateResponse.status()` đến từ inventory/promotion mà không có hằng số dùng chung; mọi thay đổi tên state ở service khác sẽ âm thầm biến thành "mixed path". Đề xuất: enum/hằng chung trong common (hoặc tối thiểu constant + JavaDoc chỉ nguồn contract).

### `service/impls/PricingServiceImpl.java`

- **[Low][documentation] `service/impls/PricingServiceImpl.java:88-90`** — `new TaxCalculateRequest(null, null, null, taxableAmount)` luôn gửi taxClass/country/postal = null với comment "defer". Khi bật `TAX_SERVICE_ENABLED=true`, thuế sẽ được tính thiếu ngữ cảnh (không theo quốc gia/loại thuế) ⇒ con số total sai trong luồng tiền. Đề xuất: hoặc validate tax-service mặc định xử lý null, hoặc fail khi tax enabled mà thiếu country/postal, kèm TODO gắn task cụ thể.

### `service/impls/OrderEventPublisherImpl.java`

- **[Low][clean-code] `service/impls/OrderEventPublisherImpl.java:3`** — import `KafkaMessagePublisher` không sử dụng.

### `service/OrderMetrics.java`

- **[Low][documentation] `service/OrderMetrics.java:56-58` + `service/impls/OrderOutboxRelay.java:47-48`** — tên metric `order.events.published` thực chất đếm lúc **persist outbox row** (trong `OrderEventPublisherImpl.save`), không phải lúc Kafka publish thực sự; và `order.outbox.pending.count` chỉ đếm batch hiện tại (≤100) chứ không phải tổng PENDING. Tên gây hiểu nhầm khi alert trên dashboard. Đề xuất: đổi tên rõ nghĩa (`order.outbox.persisted`, `order.outbox.batch.pending`) hoặc đếm đúng tổng bằng COUNT query.

### `config/RestClientConfig.java`

- **[Low][performance] `config/RestClientConfig.java:53-56`** — dùng `SimpleClientHttpRequestFactory`: mỗi request outbound mở socket mới (không connection pool), với Java 25 có sẵn `JdkClientHttpRequestFactory` (HttpClient có pooling + HTTP/2). Lưu lượng saga (N reserve + commit + state polls theo item) khiến overhead này nhân lên. Đề xuất: chuyển sang JDK HttpClient factory.

### `resources/application.yml`

- **[Low][security] `resources/application.yml:7-8,64`** — mật khẩu mặc định được commit trong config: `POSTGRES_PASSWORD:admin`, `ORDER_SERVICE_CLIENT_SECRET:changeme`. Với Keycloak thật, secret sai → 401 (fail closed) nên không phải backdoor tức thời, nhưng nếu ai đó dựng realm theo đúng default này (hoặc quên inject env var khi deploy), service account `order-service` bị xâm nhập bằng secret công khai. Đề xuất: bỏ default cho client-secret (fail fast khi thiếu biến môi trường ở profile prod).
- Ghi nhận tích cực: `public-paths: []` (mọi endpoint yêu cầu JWT), `ddl-auto: validate`, Liquibase changelog đầy đủ index (partial unique index cart-active, partial index outbox-pending, `idx_orders_status_created` dùng chung cho reconciliation — có comment tránh đánh index trùng). Các knob Phase-8 chưa dùng (`expired-cart-days`, `cancelled-order-days`) có comment "reserved" rõ ràng.

### `service/impls/CartServiceImpl.java`

- **[Low][error-handling] `service/impls/CartServiceImpl.java:56-79`** — `addItem` không xử lý race double-insert: 2 request đồng thời thêm cùng product mới đều thấy `findByCartIdAndProductId = empty` ⇒ 2 INSERT ⇒ unique index `(cart_id, product_id)` chặn cái thứ 2 ⇒ `DataIntegrityViolationException` ⇒ 409 kèm message thô. Ngay trong cùng class, `getOrCreateCart` đã có xử lý đúng cho chính kịch bản race này (catch + re-fetch winner). Đề xuất: làm tương tự — catch conflict rồi merge lại theo winner.

### Testing (đánh giá chung)

- **[Medium][testing] `service/impls/OrderOutboxRelay.java`** — không có test nào cho relay: ngắt khi lỗi đầu tiên (bảo toàn ordering), đếm retry, chuyển `FAILED` sau `max-retries`, metric timer. Đây là "egress" duy nhất của toàn bộ event lifecycle — lỗi ở đây = consumer không bao giờ thấy sự kiện. Đề xuất: unit test 3 scenario (publish fail → retry++, quá hạn → FAILED, break-on-first-failure) bằng mock publisher; tương tự thiếu test cho `IdempotencyKeyCleanupScheduler`/`OutboxRetentionScheduler` (nhẹ hơn, có thể gộp).
- **[Low][testing] `controller/OrderControllerTest.java`** — endpoint `GET /api/v1/orders` (findAll) gắn `hasRole('ADMIN')` nhưng không có test role-matrix (USER bị 403) như `VerifyPurchaseEndpointTest` đã làm. Đề xuất: thêm 1 case.
- Nhìn chung: test coverage **rất tốt** — IT chứng minh ordering commit (WireMock journal + fixedDelay chống flake), compensation LIFO, idempotency replay, 2 race concurrency, tax-failure release, cache round-trip. Điểm cộng lớn so với các module khác.

---

## Files không có finding

- `mapper/CartMapper.java`, `mapper/OrderMapper.java`
- `entity/Cart.java`, `entity/CartItem.java`, `entity/Order.java`, `entity/OrderItem.java`, `entity/IdempotencyKey.java`, `entity/OutboxEvent.java`
- `constant/OrderStatus.java`, `exception/StockReservationFailedException.java`
- `repository/OrderRepository.java`, `repository/OrderItemRepository.java` (note: có JavaDoc giải thích index mapping), `repository/CartRepository.java`, `repository/CartItemRepository.java`, `repository/IdempotencyKeyRepository.java`, `repository/OutboxEventRepository.java`
- `service/CartService.java`, `service/OrderService.java`, `service/IdempotencyService.java`, `service/OrderStatusService.java`, `service/PricingService.java`, `service/StockReservationService.java`, `service/OrderEventPublisher.java`, `service/CommitOutcome.java`, `service/CompensationTarget.java`, `service/ShippingDeliveredHandler.java`
- `service/impls/CartServiceImpl` (trừ 1 race note ở trên), `service/impls/OrderStatusServiceImpl.java`, `service/impls/StockReservationServiceImpl.java`, `service/impls/OutboxRetentionScheduler.java`, `service/impls/IdempotencyKeyCleanupScheduler.java`
- `security/ServiceTokenProvider.java`, `config/ShopServicesProperties.java`, `config/CacheConfig.java`, `kafka/ShippingListenerConfig.java`
- `dto/**` (toàn bộ records + ShippingDeliveredEvent)
- `resources/db/changelog/*` (5 file — schema rõ ràng, index hợp lý, changelog có comment)
- Test: `CartServiceImplTest`, `IdempotencyServiceImplTest`, `OrderServiceImplTest`, `OrderStatusServiceImplTest`, `PricingServiceImplTest`, `OrderCommitCoordinatorTest`, `OrderReconciliationSchedulerTest`, `OrderConfirmMetricsTest`, `OrderMetricsTest`, `ShippingDeliveredHandlerTest`, `CartControllerTest`, `ConfirmOrchestrationWebMvcTest`, `OrderStatusControllerAuditTest`, `VerifyPurchaseEndpointTest`, `OrderCreationSagaIntegrationTest`, `ConfirmOrchestrationIT`, `AbstractOrderServiceIT`, `TestLiquibaseConfig`, `CacheSerializerRoundTripTest`, `PaymentServiceClientTest`, `CartRepositoryTest`, `OrderItemRepositoryTest`, `OrderRepositoryTest`

---

## Tổng hợp

| Severity | error-handling | security | performance | testing | clean-code | dry | pattern | documentation | **Tổng** |
|---|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **1** |
| Medium | 2 | 0 | 0 | 2 | 0 | 0 | 0 | 0 | **4** |
| Low | 5 | 1 | 2 | 1 | 3 | 2 | 1 | 2 | **17** |
| **Tổng** | **8** | **1** | **2** | **3** | **3** | **2** | **1** | **2** | **22** |

## Đánh giá pattern compliance & độ sạch

**Tuân thủ pattern: cao (~9/10).** Cấu trúc layered đúng chuẩn (controller/dto/entity/mapper/repository/service+impls/kafka/client/config), toàn bộ endpoint trả `ApiResponse<T>` / `ApiResponse<PageResponse<T>>`, lỗi đi qua `BusinessException` + `ErrorCode` (không có throw RuntimeException trần ra API — đã đối chiếu `ApiExceptionHandler` có fallback), page size cap `PageableConstant.MAX_PAGE_SIZE`, dữ liệu scope theo userId chống IDOR thống nhất (`cartId` + userId khi tạo đơn, hide-existence qua `ORDER_NOT_FOUND` ở cancel/findById — đã có test). Chỉ 1 deviation đáng kể: dùng `@PreAuthorize` ở controller thay vì thuần public-paths — **deviation có chủ đích, có comment**, không cần sửa.

**Độ sạch: tốt.** Điểm mạnh hiếm thấy: saga có compensation tường minh + deterministic ordering (sort theo productId), transactional outbox với relay giữ ordering per-aggregate, idempotency đúng chuẩn ledger (in-flight/complete/hash-check/abort REQUIRES_NEW), @Version chống race chuyển trạng thái, comment chất lượng cao (giải thích "tại sao", trích dẫn review/spec), test suite đạt chuẩn production-grade. Nhược điểm chính: 1 consumer Kafka nuốt exception (High — mất sự kiện delivered), 1 lỗ hổng compensation khi reserve lỗi khác 409 (Medium), và Redis cache trở thành hard dependency (Medium). Còn lại là nitpick dễ dọn.