# Code Review — Shipping-service & Tax-service

- **Ngày:** 2026-09-02
- **Phạm vi:** `/shipping-service/src` (58 files) + `/tax-service/src` (37 files) — toàn bộ main/java, test/java, resources.
- **Loại review:** READ-ONLY, chuyên sâu (pattern, clean code, SOLID/DRY, error handling/logging, security, performance, documentation, testing).
- **Baseline đối chiếu:** utils/common-core (ErrorCode, AbstractMappedEntity/SoftDeletable, ApiPaths, PageableConstant), utils/common-security (BaseSecurityConfig + SecurityAutoConfiguration có `@EnableMethodSecurity`), utils/common-kafka (KafkaMessagePublisher, JsonKafkaSerializer, BaseKafkaConsumer), utils/common-spring (JpaAuditingAutoConfiguration). Không đọc `media-service/`.
- **Kết luận nhanh ở cuối file** (mục "Đánh giá tổng hợp").

---

# Phần 1: Shipping-service

## Đánh giá mẫu (baseline đối chiếu)

- Controller `@PreAuthorize("hasRole('ADMIN')")` ở `BackofficeShipmentController` — mặc dù prompt baseline ghi "không dùng @PreAuthorize", thực tế toàn repo (product/order/payment/rating/tax/notification/search) đều dùng @PreAuthorize và common-security bật `@EnableMethodSecurity`, nên đây **là chuẩn repo** — không tính là deviation.
- Webhook endpoint nằm trong `public-paths` của application.yml nhưng được bảo vệ bằng HMAC-SHA256 constant-time (`MessageDigest.isEqual`), fail-closed (secret rỗng → reject). Đúng chuẩn. Không có endpoint storefront nên không có bề mặt IDOR nào.
- Error codes `SHP-10001..10006` theo đúng lược đồ 5-chữ-số mới của repo (RTG-11xxx, SRH-12xxx...). Chuẩn.
- Các file chỉ index DB (`uk_shipment_order_live`, `uk_shipments_tracking_live`, `idx_shipments_status_stale`, `uk_shipment_events_carrier_event`) khớp chính xác với câu query tương ứng. Tốt.
- Outbox + successive-relay theo đúng mô hình order-service (có đối chiếu `OrderOutboxRelay`).

## Findings theo file

### outbox/ShippingEventPublisherImpl.java

- **[Medium][solid] `outbox/ShippingEventPublisherImpl.java:39-62` + `outbox/ShippingOutboxRelay.java:37-39`** — Payload được lưu dạng JSON **String**, relay publish String này qua `KafkaMessagePublisher` với value-serializer là `JsonKafkaSerializer` (common-kafka). `ObjectMapper.writeValueAsBytes(String)` sẽ bọc thêm một lớp JSON string (`"{\"eventId\":...}"`) → consumer của topic `shop.shipping.lifecycle.v1` nhận chuỗi JSON **double-encoded**, không phải object. Bằng chứng nội bộ: commit `2ce93f4 fix(search): unwrap double-encoded product events` từng phải vá consumer vì đúng lỗi này, và `ShippingFlowIT` (dòng 402-403) chủ động "unwrap" khi đọc. Đề xuất: sửa tận gốc ở `JsonKafkaSerializer` (passthrough raw bytes cho `CharSequence`/`String`), hoặc relay publish `objectMapper.readTree(payload)`; trước mắt xác nhận consumer shipment lifecycle (notification-service) có unwrap.

### outbox/ShippingOutboxRelay.java

- **[Medium][error-handling] `outbox/ShippingOutboxRelay.java:54`** — `break` sau lỗi publish đầu tiên: một event head bị lỗi (Kafka down, payload xấu) chặn toàn bộ batch phía sau; các event sau chỉ được xử lý khi head đạt `maxRetries`. Đây là hành vi "preserves per-aggregate ordering" có chủ đích (order-service có comment giải thích) — **deviation có chủ đích** nhưng comment đó đã bị lược bỏ khi copy. Đề xuất: giữ hành vi nhưng chép lại comment giải thích + log tổng số event còn lại bị hoãn.
- **[Medium][performance] `outbox/OutboxEventRepository.java:17-19`** — `deleteByStatusAndSentAtBefore` được khai báo nhưng **không có call-site nào** trong module (so với order/promotion/inventory đều có `OutboxRetentionScheduler`). Bảng `outbox_events` tăng vô hạn; index `idx_outbox_pending_id` sẽ thoái hóa khi bảng phình. Đề xuất: thêm scheduler retention giống các service khác (xoá SENT sau N ngày), hoặc xoá method chết.

### service/impls/ShipmentServiceImpl.java

- **[Medium][error-handling] `service/impls/ShipmentServiceImpl.java:69-71`** — `catch (DataIntegrityViolationException)` quét toàn bộ và log "raced a concurrent consumer": một event CONFIRMED có `orderId = null` hoặc vi phạm CHECK constraint cũng bị nuốt y hệt, che giấu lỗi dữ liệu thật bằng log sai nguyên nhân. Đề xuất: chỉ bắt khi database exception đến từ unique index `uk_shipment_order_live` (kiểm tra constraint name trong cause), còn lại rethrow hoặc log.error.
- **[Medium][dry] `service/impls/ShipmentServiceImpl.java:149-164` vs `service/impls/WebhookEventServiceImpl.java:113-129`** — Logic áp dụng transition (set previousStatus/status/deliveredAt + `recordAdvance/recordFailed/recordDelivered`) bị lặp y hệt ở admin path và webhook path. Rủi ro hai nơi lệch nhau khi sửa (đã thấy metrics ở webhook path đo sau khi `writer.complete` còn admin path đo trước khi save). Đề xuất: extract một `applyTransition(shipment, next, now)` dùng chung.
- **[Medium][error-handling] `service/impls/ShipmentServiceImpl.java:121`** — `assignTracking` gán tracking trùng với một shipment live khác sẽ nổ unique index `uk_shipments_tracking_live` → `DataIntegrityViolationException` lọt API thành 500 thay vì 409 BusinessException (vd `SHIPMENT_DUPLICATE`). Đề xuất: pre-check `findByTrackingNumber` và báo lỗi domain rõ ràng.
- **[Low][clean-code] `service/impls/ShipmentServiceImpl.java:88-104`** — `findAll`: thứ tự filter là else-if nên khi gửi cả `status` lẫn `carrier` thì carrier bị bỏ qua **âm thầm**; nhánh `orderId` còn ignore hẳn tham số page/size (PageResponse trả về number/size của request, dễ gây nhầm). Đề xuất: reject khi hai filter mâu thuẫn, hoặc ghi rõ precedence trong JavaDoc.

### service/impls/WebhookEventServiceImpl.java

- **[Medium][error-handling] `service/impls/WebhookEventServiceImpl.java:91-101`** — Webhook có chữ ký hợp lệ nhưng tracking number chưa tồn tại (hoặc `carrierStatus` null) bị **ack 200 và event bị chôn vĩnh viễn ở trạng thái FAILED** — carrier sẽ không gửi lại. Kịch bản thực: webhook PICKED_UP đến trước khi admin gán tracking (carrier MANUAL), hoặc event đến lệch thứ tự. Không có queue retry/replay nào; reconciliation chỉ cứu các shipment **in-flight**, shipment còn `CREATED` thì kẹt mãi mãi. Đề xuất: bảng retry + job replay cho FAILED events, hoặc ít nhất metric/Gauge đếm FAILED để cảnh báo vận hành.
- **[Medium][error-handling] `service/impls/WebhookEventServiceImpl.java:105-106`** — Ánh xạ trạng thái bằng `ShipmentStatus.valueOf(payload.getCarrierStatus())`: yêu cầu carrier gửi **đúng nguyên văn tên enum** (viết hoa, gạch dưới). Không có adapter mapping mã riêng của carrier (GHN/GHTK). Khi ghép adapter thật (theo plan real-carrier-adapters), gần như mọi event sẽ rơi vào nhánh FAILED ở trên. Đề xuất: mapping status theo từng carrier trong `CarrierAdapter` (chung chỗ với webhook secret), test mapping bằng fixture của từng hãng.

### scheduler/ReconciliationScheduler.java

- **[Medium][error-handling/logic] `scheduler/ReconciliationScheduler.java:45-53`** — Tự động đánh `DELIVERED` mọi shipment in-flight sau N ngày (mặc định 7) không có `lastCarrierUpdate` mà không có bước xác nhận/cảnh báo nào — với hàng thật giao chậm >7 ngày sẽ phát event "delivered" sai cho order-service (gây dồn tác động đến fulfillment/refund). Cấu hình `SHIPPING_NOTIFY_THRESHOLD_HOURS:72` trong application.yml **chưa được dùng ở đâu**, cho thấy có ý định notify trước khi auto-deliver nhưng chưa cài. Đề xuất: hoàn thiện bước notify (72h) trước khi auto-deliver, và/hoặc threshold theo từng carrier.
- **[Low][error-handling] `scheduler/ReconciliationScheduler.java:48` + `repository/ShipmentRepository.java:30`** — `last_carrier_update < cutoff` loại bỏ hàng có `NULL` trong SQL: shipment sinh từ `NoopCarrierAdapter` (status PICKED_UP ngay khi tạo, lastCarrierUpdate null) không bao giờ được auto-deliver. Đề xuất: `COALESCE(last_carrier_update, created_at)` hoặc set `lastCarrierUpdate` ngay khi tạo shipment ở trạng thái in-flight.

### Các file khác trong shipping-service

- **[Low][clean-code] `outbox/ShippingEventPublisherImpl.java:50`** — `occurredAt` dùng `Instant.now()` thay vì `Clock` bean đã inject khắp module (test dùng mutable clock nên timestamp này không thể assert deterministic).
- **[Low][clean-code] `src/main/resources/application.yml:32`** — `notify-threshold-hours` là cấu hình chết (không có consumer trong code). Đề xuất: dùng hoặc xoá.
- **[Low][pattern] `dto/OrderLifecycleEvent.java` + `webhook/CarrierWebhookPayload.java`** — DTO dùng Lombok mutable class (@Getter/@Setter) thay vì record như chuẩn `dto/request|response`. Jackson deserialize record hoàn toàn được trên Java 25/Spring Boot 4; trừ khi có lý do đặc biệt nên chuyển sang record cho đồng bộ.
- **[Low][clean-code] `repository/ShipmentRepository.java:18`** — `Optional<Shipment> findById(UUID id)` khai báo lại y hệt method kế thừa từ `JpaRepository` — thừa.
- **[Low][documentation] `test/.../support/AbstractIntegrationTest.java:53-55`** — JavaDoc ghi IT "skip the filter chain entirely" nhưng `ShippingFlowIT` thực tế chạy qua filter chain thật (gửi Bearer token qua TestRestTemplate, JwtDecoder stub). Doc sai so với code.
- **[Low][documentation] `outbox/ShippingOutboxRelay.java:44-55`** — Bản copy từ order-service đã đánh rơi comment "`break` — stop draining on failure, preserves per-aggregate ordering"; hành vi không hiển nhiên nên cần giải thích.
- **[Low][testing] `test/.../ShippingFlowIT.java:400-403`** — Helper `parse()` "unwrap" payload double-encoded nên bộ IT **che giấu** lỗi wire-format thay vì assert đúng format kỳ vọng (liên quan Medium finding phía trên). Đề xuất: sau khi sửa serializer, xoá nhánh `node.isTextual()`.
- **[Low][testing] `test/.../config/TestClockConfig.java:33-35`** — `withZone(zone)` trả về `this` (bỏ qua zone được yêu cầu); OK cho test dùng UTC fixed nhưng là bẫy nếu reuse ở chỗ khác. Đề xuất: ném UnsupportedOperationException hoặc cài hẳn zone.
- **[Low][testing] `controller/BackofficeShipmentControllerTest.java`** — Chưa có test cho phép chặn page size (`Math.min(size, MAX_PAGE_SIZE)` ở controller dòng 44): thiếu case `size=9999` bị clamp. Đề xuất: thêm 1 test.

## Files không có finding (Shipping-service)

`ShippingServiceApplication`, `carrier/CarrierAdapter`, `carrier/CarrierConfig`, `carrier/ManualCarrierAdapter`, `carrier/NoopCarrierAdapter`, `config/ClockConfig`, `config/ShippingWebhookProperties`, `constant/Carrier`, `constant/ShipmentStatus`, `controller/BackofficeShipmentController`, `dto/request/AssignTrackingRequest`, `dto/request/ShipmentTransitionRequest`, `dto/response/ShipmentResponse`, `entity/Shipment`, `entity/ShipmentEvent`, `kafka/OrderEventConsumer`, `kafka/ShippingListenerConfig`, `outbox/OutboxEvent`, `repository/ShipmentEventRepository` (được nhắc gián tiếp ở 2 findings trên), `service/ShipmentService`, `service/ShipmentStateMachine`, `service/ShipmentWriter`, `service/WebhookEventService`, `webhook/WebhookSignatureVerifier`, `db/changelog/*` (3 file), toàn bộ test còn lại: `CarrierAdapterDraftsTest`, `CarrierConfigTest`, `BackofficeShipmentAuditTest`, `ReconciliationSchedulerTest`, `ShipmentStateMachineTest`, `ShipmentWriterTest`, `WebhookEventWriterTest`, `ShipmentServiceImplTest`, `WebhookEventServiceTest`, `WebhookSignatureVerifierTest`, `ShipmentWriterTest`, `support/ShippingBootstrapIT`, `config/TestLiquibaseConfig`.

(Ghi nhận tích cực: `WebhookSignatureVerifierTest` phủ đầy đủ tamper/wrong-secret/null/prefixed-hex/case; `ShipmentStateMachineTest` duyệt ma trận 7x7; `ShippingFlowIT` có 8 luồng end-to-end thật với Testcontainers Kafka+PG.)

---

# Phần 2: Tax-service

## Đánh giá mẫu (baseline đối chiếu)

- CRUD controller dùng `@Valid` + Jakarta annotations, error code `TAX-8001..8005` đúng lược đồ repo, soft-delete qua `markDeleted()` + `@SQLRestriction("deleted = false")` — rất đúng chuẩn.
- **Tiền thuế đúng chuẩn số học**: `TaxCalculator` dùng `BigDecimal` xuyên suốt, `divide(100, 2, RoundingMode.HALF_UP)`, `numeric(5,2)` + CHECK constraint ở DB, và test IT chạy trên numerics thật (100.00×7%→7.00; 0.05×50%→0.03). Không tìm thấy sai sót tiền bạc.
- Endpoint calculate được gate `hasAnyRole('SERVICE','ADMIN')` — đúng mô hình service-to-service bảo mật; test audit phân biệt được actor "service".
- Unique index dùng functional index (`lower(name)`, `COALESCE(postal_code,'')`) khớp với JPQL pre-check — tốt.

## Findings theo file

### dto/request/TaxRateRequest.java (+ TaxCalculateRequest.java)

- **[Medium][error-handling] `dto/request/TaxRateRequest.java:13`** — `@Pattern(regexp = "^[A-Z]{2}$")` **không kèm `@NotNull`** (Jakarta coi null là hợp lệ cho @Pattern). Request create/update rate với `country = null` vượt validation, `countDuplicate` tính `country = null` → count 0 (luôn "pass"), rồi insert chết ở cột `country char(2) NOT NULL` → `DataIntegrityViolationException` → HTTP 500 thay vì 400. Đề xuất: thêm `@NotNull` vào `country` của `TaxRateRequest`. (Ở `TaxCalculateRequest.java:12`, country null bị nuốt âm thầm thành "lấy default rate" — nên quyết định rõ: bắt buộc nhập country hoặc ghi tài liệu hành vi này.)
- **[Low][error-handling] `dto/request/TaxRateRequest.java:15` + `dto/request/TaxCalculateRequest.java:13`** — `postalCode` không có `@Size(max = 16)` trong khi cột DB là `varchar(16)` → postal > 16 ký tự gây lỗi 500 "value too long". Đề xuất: thêm `@Size`.

### service/impls/TaxClassServiceImpl.java

- **[Low][clean-code] `service/impls/TaxClassServiceImpl.java:31,47`** — Trùng tên tax **class** lại ném `DUPLICATE_TAX_RATE` (TAX-8003, message key `tax.rate.duplicate`): sai miền ngữ nghĩa (không có `DUPLICATE_TAX_CLASS`). Admin nhận thông báo "duplicate tax rate" khi tạo class trùng tên. Đề xuất: thêm error code riêng TAX-80xx cho class.
- **[Low][error-handling] `service/impls/TaxClassServiceImpl.java:76` + `service/impls/TaxRateServiceImpl.java:76`** — `auditorAware.getCurrentAuditor().orElseThrow()` ném `NoSuchElementException` trần nếu auditor rỗng (test `deleteRequiresAuditor` thậm chí assert leak này). Với bean mặc định của common-spring thì luôn trả `Optional.of("system")` nên thực tế không xảy ra, nhưng pattern không an toàn nếu service override AuditorAware. Đề xuất: fallback `"system"` thay vì orElseThrow trần.
- **[Low][error-handling] `service/impls/TaxClassServiceImpl.java:30-37` + `service/impls/TaxRateServiceImpl.java:32-39`** — Pattern check-then-insert không chống race: hai request create trùng tên/tuple đồng thời đều vượt pre-check rồi 1 request chết ở unique index → 500. (Backoffice, tần suất thấp.) Đề xuất: bắt `DataIntegrityViolationException` và map sang `DUPLICATE_TAX_RATE` như shipping đã làm với shipment.

### service/impls/TaxCalculationServiceImpl.java

- **[Low][clean-code] `service/impls/TaxCalculationServiceImpl.java:37-39`** — Nhánh `if (ratePct == null) throw NO_MATCHING_RATE` là **không thể chạm tới trong production**: `default_rate_pct` NOT NULL ở DB + được validate @NotNull 0..100 khi tạo (IT phải `alter table drop not null` mới test được nhánh này). Mã chết gây hiểu nhầm về hành vi resolve. Đề xuất: giữ nhánh làm guard phòng thủ thì ghi comment "defensive, unreachable via API"; hoặc bỏ hẳn đi kèm xoá NO_MATCHING_RATE khỏi ErrorCode nếu thiết kế mới là "luôn có default".
- **[Low][performance] `service/impls/TaxCalculationServiceImpl.java:43-48`** — Luôn 2 query tuần tự (postal-specific rồi mới country-wide) cho đường calculate nằm trên critical path checkout (order-service gọi đồng bộ). Đề xuất: 1 query duy nhất `ORDER BY postal_code NULLS LAST LIMIT 1`, hoặc chấp nhận theo KISS vì bảng nhỏ — nếu chấp nhận thì ghi chú lý do.
- **[Low][dry] `service/impls/TaxCalculationServiceImpl.java:50-52` vs `service/impls/TaxRateServiceImpl.java:91-93`** — `normalize(postalCode)` lặp y hệt ở hai impl. Đề xuất: một static helper dùng chung (vd trong request record).

### controller/TaxCalculationController.java

- **[Low][pattern] `controller/TaxCalculationController.java:22`** — `/calculate` (gọi nội bộ service-to-service) bị nhét dưới namespace `/api/v1/backoffice/tax-rates/calculate`. Internal endpoint không phải chức năng backoffice, đặt chung với CRUD admin dễ gây nhầm khi cấu hình public-paths/rate-limit/gateway route sau này. Đề xuất: thêm `ApiPaths` riêng (vd `API_V1 + "/tax/calculate"`) và tách controller.

### resources/application.yml + tổng thể

- **[Low][pattern] `resources/application.yml:24`** — Default `issuer-uri` là `http://keycloak:8080/realms/ecommerce` trong khi shipping-service dùng `http://localhost:9090/realms/ecommerce` — default env không nhất quán giữa hai service (docker vs localhost), dễ bẫy khi dev local. Đề xuất: thống nhất một default cho toàn repo.
- **[Low][logging] toàn bộ tax-service main code** — Không có `@Slf4j`/logger nào: các thao tác đổi rate/class (ảnh hưởng trực tiếp số tiền thu thuế) chỉ được ghi qua @Audited, không có log nghiệp vụ (ai đổi rate nào từ bao nhiêu sang bao nhiêu). Đề xuất: log info đổi rate/class kèm id + giá trị cũ/mới, và log warn khi calculate fallback về default rate (hiện không biết bao nhiêu đơn đang dùng default).

## Files không có finding (Tax-service)

`TaxServiceApplication`, `controller/BackofficeTaxClassController`, `controller/BackofficeTaxRateController`, `dto/request/TaxClassRequest`, `dto/response/*` (3 file), `entity/TaxClass`, `entity/TaxRate`, `repository/TaxClassRepository`, `repository/TaxRateRepository` (JPQL coalesce dup-check đã nhận xét tích cực), `service/TaxClassService`, `service/TaxRateService`, `service/TaxCalculationService`, `service/TaxCalculator`, `service/impls/TaxRateServiceImpl` (phần CRUD), `db/changelog-db.changelog-master.yaml`, `db/changelog/changelog-001-initial-schema.yaml`, và toàn bộ test: `TaxCalculationIT`, `TaxCatalogIT`, `TaxCalculatorTest`, `TaxCalculationServiceImplTest`, `TaxClassServiceImplTest`, `TaxRateServiceImplTest`, `TaxCalculationControllerTest`, `BackofficeTaxClassControllerTest`, `BackofficeTaxRateControllerTest`, `BackofficeTaxAuditTest`, `TaxBootstrapIT`, `AbstractIntegrationTest`, `config/TestLiquibaseConfig`.

(Ghi nhận tích cực: test tính thuế phủ đầy đủ 3 tier resolve + làm tròn + NO_MATCHING_RATE qua thao tác schema thật; controller slice có ma trận 401/403 cho cả 3 controller; IT xác minh soft-delete không rò sang calculate.)

---

# Bảng tổng hợp findings

## Theo severity × category

| Category | Critical | High | Medium | Low | Tổng |
|---|---|---|---|---|---|
| error-handling | 0 | 0 | 6 (shipping 5, tax 1) | 7 (shipping 3, tax 4) | 13 |
| solid | 0 | 0 | 1 | 0 | 1 |
| dry | 0 | 0 | 1 | 1 | 2 |
| performance | 0 | 0 | 1 | 1 | 2 |
| clean-code | 0 | 0 | 0 | 5 (shipping 2, tax 3) | 5 |
| pattern | 0 | 0 | 0 | 3 (shipping 1, tax 2) | 3 |
| documentation | 0 | 0 | 0 | 2 | 2 |
| testing | 0 | 0 | 0 | 3 | 3 |
| logging | 0 | 0 | 0 | 1 | 1 |
| security | 0 | 0 | 0 | 0 | 0 |
| **Tổng** | **0** | **0** | **9** (8 shipping, 1 tax) | **22** (12 shipping, 10 tax) | **31** |

## Đánh giá tuân thủ pattern của từng service

**Shipping-service — tuân thủ tốt (8/10).** Cấu trúc layer chuẩn (controller/service/impls/repository/entity/outbox/kafka/webhook), BusinessException + mã SHP-100xx đúng lược đồ mới, outbox transactional khớp tư tưởng order-service, state machine có ma trận test đầy đủ, security webhook HMAC constant-time fail-closed và backoffice ADMIN-gated đúng chuẩn repo, index DB khớp query, test phủ rất dày (unit + slice + 8 flow IT với Testcontainers). Điểm yếu tập trung ở **độ tin cậy tích hợp**: (1) wire-format outbox double-encoded (lỗi đã từng sửa tận consumer ở search-service), (2) webhook hợp lệ nhưng "lạc" tracking bị ack rồi chôn vĩnh viễn không có replay, (3) bắt exception quá rộng che lỗi dữ liệu, (4) outbox không có job dọn dẹp, (5) auto-deliver sau 7 ngày thiếu bước cảnh báo 72h đã khai báo config nhưng chưa dùng.

**Tax-service — tuân thủ rất tốt (9/10).** CRUD chuẩn mẫu đến từng chi tiết (`@Valid`, soft-delete với `@SQLRestriction`, auditor, functional unique index + JPQL coalesce pre-check, CHECK constraint DB), **tính thuế chuẩn BigDecimal + HALF_UP và được test trên DB thật** — điều quan trọng nhất với service động tới tiền thì hoàn toàn đạt. Vấn đề đều nhỏ và cục bộ: validation hở duy nhất đáng kể là `country` nullable trên `TaxRateRequest` gây 500; còn lại là sai ngữ nghĩa error code, mã chết (`NO_MATCHING_RATE` not reachable), normalize trùng lặp, đặt đường dẫn calculate dưới namespace backoffice, thiếu logging nghiệp vụ đổi rate.

**So sánh chung:** cả hai module đều là codebase sạch, dễ đọc, tuân thủ convention repo; không có Critical/High. Tax-service sạch hơn về validation và chuẩn số học; shipping-service phức tạp hơn (state machine + webhook + outbox) nên có nhiều rủi ro vận hành tiềm ẩn hơn, cần ưu tiên xử lý 3 Medium nhóm "mất event / sai format" trước khi ghép carrier thật.