# Review chuyên sâu: search-service & notification-service

- Ngày: 2026-09-02
- Phạm vi: tất cả file trong `search-service/src` (39 file) và `notification-service/src` (30 file).
- Không đọc `media-service/` theo yêu cầu.
- Đối chiếu: baseline pattern fleet (controller/ApiResponse/PageResponse, BusinessException + ErrorCode, common-kafka, security filter chain, test slice + IT).
- Xác minh thêm (ngoài 2 module): `utils/common-kafka` (`BaseKafkaConsumer`, `BaseKafkaListenerConfig`, `JsonKafkaSerializer`) và `utils/common-core` (`PageableConstant`, `AbstractMappedEntity`) để chốt ngữ nghĩa consumer/deserializer — không có file env thật bị git track (chỉ `.env.example` với placeholder).

Tổng kết nhanh: **0 Critical, 0 High, 10 Medium, 15 Low** (25 findings). Cả hai module đều ở mức trưởng thành tốt; search-service gần như mẫu mực về contract test và comment chủ đích, notification-service tuân thủ nhưng kém đồng nhất và có vài lỗ hổng edge-validation lộ raw 500.

---

# Part 1 — Search-service

## controller/SearchController.java

Có ghi chú chủ đích (javadoc) về việc **không** dùng `@PreAuthorize` (fleet precedent P2-6), dùng `@Valid @ModelAttribute` cho query string, trả về `ApiResponse<PageResponse<T>>` — đúng chuẩn. Không có finding.

## controller/BackofficeSearchController.java

- [Low][pattern] `controller/BackofficeSearchController.java:24` — class-level `@PreAuthorize("hasRole('ADMIN')")` khác baseline "KHÔNG dùng @PreAuthorize". Đây là deviation CÓ chủ đích (javadoc nêu rõ: reindex là thao tác nặng chỉ ADMIN; filter chain chỉ làm authentication nên cần role-gate cục bộ — nhất quán với product-service backoffice theo ghi chú tại `ProductClientConfig`). Giữ nguyên; chỉ ghi nhận.

## client/ProductBackofficeClient.java

- [Medium][error-handling] `client/ProductBackofficeClient.java:70-73` — javadoc cam kết "ANY failure → SRH-12002 (503)" nhưng chỉ bắt `RestClientException`. Các ngoại lệ khác nổi lên từ bên trong try vẫn lọt thô ra ngoài: `ServiceTokenProvider.refreshToken()` ném `IllegalStateException` khi Keycloak trả body rỗng (`security/ServiceTokenProvider.java:79-81`), hoặc `IllegalArgumentException` khi `token-url`/base-url cấu hình sai → GlobalExceptionHandler trả 500 thô thay vì 503 SRH-12002, phá vỡ contract "never raw exception". Đề xuất: cho `ServiceTokenProvider` ném `BusinessException.of(ErrorCode.SEARCH_QUERY_FAILED)` (hoặc bắt `Exception` chung quanh khối `.header(...getToken())`/`.retrieve()` và map toàn bộ sang SRH-12002).

## kafka/ProductSearchConsumer.java

- [Medium][error-handling] `kafka/ProductSearchConsumer.java:51-53` — posture "ack-always": mọi lỗi handler bị log và nuốt để offset vẫn advance. Khi Elasticsearch down (toàn bộ window), **mọi** sự kiện lifecycle bị mất vĩnh viễn không báo động gì ngoài `log.error` — index stale cho tới khi có người chạy reindex thủ công. Là deviation có chủ đích (comment nêu precedent product-service), nhưng thiếu vòng an toàn về observability. Đề xuất: thêm counter `search_ingestion_failures_total` (+ alert khi rate tăng), và trong log error bổ sung eventId/partition/offset khi decode thành công — hiện log không có bất kỳ định danh record nào để truy poison.

## kafka/SearchListenerConfig.java

Không extend `BaseKafkaListenerConfig` và dùng `StringDeserializer` raw vì wire double-encoded (`JsonKafkaSerializer` string-token) — deviation có chủ đích, javadoc rất đầy đủ (F-5). Không có finding.

## kafka/ProductLifecycleEvent.java

Record 17 field + `@JsonIgnoreProperties(ignoreUnknown=true)` — đúng chuẩn DTO record, tương thích tiến hoá producer. Không có finding.

## service/impls/ReindexServiceImpl.java

- [Medium][solid] `service/impls/ReindexServiceImpl.java:71-84` — race giữa reindex và consumer: trong lúc stream + bulk vào `products-v{n+1}`, các sự kiện Kafka vẫn được ghi vào **alias cũ** (`ProductSearchService.index` ghi theo alias); doc bị consumer cập nhật *sau* khi product-service đã trả snapshot trang đó nhưng *trước* lúc swap alias sẽ mất bản cập nhật ở index mới, chỉ hồi phục khi có sự kiện mới tiếp theo cho chính product đó. Cửa sổ = toàn bộ thời gian reindex (dài với catalog lớn). Đề xuất: tối thiểu ghi nhận trong javadoc; tốt hơn là dual-write (consumer cũng ghi vào index generation đang build) hoặc replay consumer offset sau swap.

- [Medium][error-handling] `service/impls/ReindexServiceImpl.java:137` — `Integer.parseInt(name.substring(...))` trong `nextIndexName()`: nếu tồn tại index ops-type không khớp pattern số (vd `products-v2_old` — vẫn khớp `products-v*`), `NumberFormatException` không được catch (chỉ catch `IOException | ElasticsearchException`) → raw 500, vi phạm chính cam kết trong javadoc class. Đề xuất: lọc/validate tên theo regex `products-v\d+` trước khi parse, hoặc map NumberFormatException sang `SEARCH_QUERY_FAILED`.

## service/impls/SearchQueryServiceImpl.java

- [Low][clean-code] `service/impls/SearchQueryServiceImpl.java:66` — `Math.min(request.size(), SIZE_CAP)` là mã chết tại runtime vì controller đã reject `size > 200` (400) qua `@Max` trong `SearchRequest`; hai tầng bảo vệ lệch ngữ nghĩa (controller → 400 error, service → silent clamp, thậm chí có IT kiểm chứng hành vi clamp). Đề xuất: giữ một nguồn chân lý duy nhất — bỏ clamp ở service (controller đã chặn) hoặc bỏ `@Max` ở DTO; không cần cả hai.

- [Low][clean-code] `service/impls/SearchQueryServiceImpl.java:126-135` (cùng `search/IndexProvisioner.java:72`) — tiền tệ lưu kiểu `double` trong ES mapping và chuyển `BigDecimal.doubleValue()` khi build range query. Với giá 2 chữ số thập phân, `Double.toString` shortest-repr hiện che được sai số, nhưng đây là kiểu mô hình dữ liệu rủi ro cho tiền (rounding ngầm ở ngưỡng biên, halfFloat cho rating thì ít vấn đề). Đề xuất: `scaled_float` (scale 100) hoặc lưu cents dạng `long`; nếu giữ `double` thì ghi comment lý do.

## dto/request/SearchRequest.java

- [Low][clean-code] `dto/request/SearchRequest.java:25-29` — thiếu validation khoảng: `minPrice`/`maxPrice`/`minRating` chấp nhận giá trị âm, `minPrice > maxPrice`, `minRating > 5` không bị chặn → query vô nghĩa (không crash, nhưng là input rác qua trust boundary). Đề xuất: `@PositiveOrZero` cho 3 field giá/rating, `@DecimalMax("5.0")` cho rating; cân nhắc cross-field check min ≤ max.

## config/ElasticsearchConfig.java

- [Low][error-handling] `config/ElasticsearchConfig.java:34-39` — chỉ kiểm tra `username` khác rỗng rồi dùng thẳng `properties.password()`: đặt `ELASTICSEARCH_USERNAME` mà quên `ELASTICSEARCH_PASSWORD` (hoặc ngược lại) tạo cấu hình basic-auth lệch lạc/thất bại muộn, khó debug. Đề xuất: validate cặp username/password cùng có hoặc cùng vắng ngay khi bind.

- [Low][clean-code] `config/ElasticsearchConfig.java:31-32` — magic numbers 3000/10000 cho connect/socket timeout không cấu hình được, trong khi `ShopServicesProperties.Service` đã có `timeoutMs` bật/tắt được — không nhất quán trong nội bộ module. Đề xuất: đưa vào `SearchProperties` với default.

## security/ServiceTokenProvider.java

Xem finding tại `ProductBackofficeClient.java` (IllegalStateException không được map). Còn lại: cache token + refresh trước 30s hết hạn + `synchronized` double-check — đúng; không log token. Không có finding riêng.

## search/IndexProvisioner.java

- Xem finding price-double phía trên. Còn lại: template + index + alias idempotent, fail-to-degraded (log error, không crash startup) — đúng posture đã ghi. Không có finding riêng.

## application.yml

- [Low][security] `resources/application.yml:27` — `client-secret: ${SEARCH_SERVICE_CLIENT_SECRET:changeme}`: mật khẩu mặc định được commit. Không tạo attack path khả dụng (Keycloak thật sẽ không chấp nhận `changeme`; dùng cho dev local) — báo ở mức Low vì đây là default "thực dùng" khi dev, dễ thành thói quen đưa lên prod mà không fail-fast. Đề xuất: bỏ default hoặc cho khởi động fail khi biến môi trường thiếu ở profile không phải dev.

## service/* (ProductDocuments, ProductSearchService, ReindexService, SearchQueryService, SearchMetrics)

- `ProductDocuments` DRY tốt (2 nguồn hội tụ 1 hàm); parse `updatedAt` fail-soft có lý do (đã comment).
- `ProductSearchService` xử lý 404 delete qua cả `Result.NotFound` lẫn `ElasticsearchException.status()==404`; `IllegalStateException` trên IOException chỉ nằm trong đường consumer (được container catch) — không lọt API.
- Interface `ReindexService`/`SearchQueryService` có JavaDoc đầy đủ contract BusinessException (SRH-12001/12002) — chuẩn.
- Không có finding.

## Tests — search-service

- [Medium][testing] `test/.../search/SearchIndexProvisioningIT.java:34-35` — phụ thuộc thứ tự class chạy: cả 3 IT dùng chung context (cùng `AbstractSearchIntegrationTest`, không `@DirtiesContext`); `ReindexIT` **xóa** `products-v1` (delete superseded indices) và đổi alias sang `products-v{n}`. Nếu `ReindexIT` chạy trước trong cùng JVM, `provision()` re-run chỉ tạo lại index mồ côi `products-v1` (alias đã tồn tại nên không gắn lại) và assert `containsOnlyKeys("products-v1")` fail. Đề xuất: `@DirtiesContext` cho ReindexIT, hoặc assertion không phụ thuộc alias target cụ thể.

- [Low][testing] `test/.../service/impls/ReindexIT.java:46-56` — hai `WireMockServer` static không bao giờ `stop()` (dựa vào JVM exit). Không sai về chức năng nhưng là mẫu resource-leak trong test; đề xuất `@AfterAll` shutdown hoặc dùng WireMock extension.

### Files không có finding (search-service)

- `SearchServiceApplication.java` (có `@ConfigurationPropertiesScan` — đúng cho record properties)
- `config/SearchProperties.java`, `config/ShopServicesProperties.java`, `config/ProductClientConfig.java` (truyền correlation-id + traceparent; ghi chú P0-4 về `@Qualifier` rất đáng giá)
- `dto/request/ReindexRequest.java`, `dto/response/ProductSearchResponse.java`, `dto/response/ReindexResponse.java`
- `metrics/SearchMetrics.java`
- `kafka/SearchListenerConfig.java` (deviation có chủ đích)
- Tests: `client/ProductBackofficeClientTest.java`, `controller/BackofficeSearchAuditTest.java`, `controller/BackofficeSearchControllerTest.java`, `controller/SearchControllerTest.java`, `kafka/ProductSearchIngestionIT.java`, `service/impls/ReindexServiceTest.java`, `service/impls/SearchQueryIT.java`, `service/impls/SearchQueryServiceTest.java`, `support/AbstractSearchIntegrationTest.java`

---

# Part 2 — Notification-service

## controller/BackofficeNotificationController.java

- [Medium][error-handling] `controller/BackofficeNotificationController.java:33-35` — `page`/`size` không có `@Min`/`@PositiveOrZero`: `size=0`, `size=-5` hay `page=-1` lọt qua `Math.min` (chỉ chặn cận trên) vào `PageRequest.of(...)` → `IllegalArgumentException` thô ra khỏi controller → GlobalExceptionHandler trả 500 (thay vì 400 validation như chuẩn `ERR-0422-V` bên search-service). Đề xuất: annotate như `SearchRequest` (`@PositiveOrZero` cho page, `@Min(1) @Max(PageableConstant.MAX_PAGE_SIZE)` cho size) hoặc bắt và map sang BusinessException.

- [Low][pattern] `controller/BackofficeNotificationController.java:24` — class-level `@PreAuthorize("hasRole('ADMIN')")`, khác baseline "không @PreAuthorize" nhưng là deviation có chủ đích (giống `BackofficeSearchController`; không có endpoint user-facing nào khác nên filter chain không đủ). Ghi nhận, không phải violation.

## dto/OrderLifecycleEvent.java

- [Low][pattern] `dto/OrderLifecycleEvent.java:14-34` — DTO wire mutable (Lombok `@Getter/@Setter/@NoArgsConstructor`) thay vì Java record như baseline và như `search-service` `ProductLifecycleEvent` (cùng vai trò Kafka payload, và JsonDeserializer xử lý record bình thường — đã được chứng minh ở module kia). Không có comment giải thích chủ đích. Đề xuất: chuyển sang record; tối thiểu bỏ `@Setter` nếu không ai mutate ngoài test.

## service/impls/NotificationServiceImpl.java

- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:58-60` — catch toàn bộ `DataIntegrityViolationException` và gán nghĩa "duplicate eventId do consumer chạy đua". Nếu vi phạm constraint KHÁC — điển hình `order_id NOT NULL` khi event thiếu `orderId` (field nullable trong DTO, cột `nullable=false` trong changelog), hoặc check constraint `ck_notifications_status` — thì event bị drop im lặng kèm log sai nguyên nhân ("already persisted by a concurrent consumer"). Đề xuất: đọc `((DataIntegrityViolationException) e).getMostSpecificCause()`/`SQLException.getConstraint()` và chỉ nuốt đúng constraint `uk_notification_event_id`; còn lại rethrow.

- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:38` — `UUID.fromString(event.getEventId())` ném NPE (`eventId` null) hoặc `IllegalArgumentException` (sai format). `BaseKafkaConsumer.processMessage` (utils/common-kafka) KHÔNG bọc handler — exception văng khỏi listener → `DefaultErrorHandler` retry ~9 lần (0 interval) rồi discard: partition không chết hẳn nhưng bị churn và event mất im lặng. Kém hơn hẳn search-service vốn tự contains trong listener. Đề xuất: validate `eventId`/`orderId` đầu `handle()` (log + return skip) hoặc try/catch quanh listener, nhất quán với posture "poison không làm kẹt partition".

- [Medium][error-handling] `service/impls/NotificationServiceImpl.java:49,66` — ghi status `SENT` TRƯỚC khi `sender.send()` thực sự thành công, commit xong mới send: nếu process chết giữa commit và send, row nằm mãi ở `SENT` dù mail/log chưa hề gửi; lỗi send mới sửa thành `FAILED`. Đồng thời toàn bộ đây là at-most-once đối với việc gửi thực (offset advance, không retry send) mà không có JavaDoc ghi nhận. Đề xuất: thêm trạng thái `PENDING` → `SENT`/`FAILED` sau send, hoặc đổi thứ tự (send trước, insert kết quả sau); tối thiểu viết rõ semantic trong JavaDoc class.

- [Low][documentation] `service/impls/NotificationServiceImpl.java:26-29` — class/`handle()` không có JavaDoc mô tả at-most-once, hội tụ dedupe (exists + unique index), cửa sổ SENT-before-send — tương phản rõ với mức độ comment của search-service.

## service/NotificationWriter.java

- [Low][pattern] `service/NotificationWriter.java:12-14` — class nằm trong package `service/` nhưng không theo cặp interface + `impls/`, và annotate `@Repository` thay vì `@Service` (để lấy exception translation — chính đáng nhưng không ghi). Cấu trúc lai này đơn giản hơn chuẩn nhưng không được giải thích. Đề xuất: thêm 1 dòng javadoc lý do, hoặc chuyển `@Service`.

## repository/NotificationRepository.java

- [Low][dry] `repository/NotificationRepository.java:19` — khai báo lại `Optional<Notification> findById(UUID id)` trùng y hệt method kế thừa từ `JpaRepository` (cùng signature, cùng semantic). Đề xuất: xóa, dùng method có sẵn.

## service/sender/NotificationSenderConfig.java

- [Low][clean-code] `service/sender/NotificationSenderConfig.java:19` — `orElseGet(LoggingNotificationSender::new)` tạo instance thứ 2 của `LoggingNotificationSender` NẰM NGOÀI Spring (bản `@Component` lúc nào cũng có sẵn trong `List<NotificationSender>` nhưng bị filter bỏ vì `channel() != SMTP`), dẫn tới 2 instance cùng loại trong context. Đề xuất: fallback về chính bean LOG trong list (`all.stream().filter(SMTP).findFirst().orElse(all.stream().filter(LOG).findFirst().orElseThrow())`) — một nguồn chân lý.

## service/sender/SmtpNotificationSender.java

Thông báo chỉ gửi tới `fallbackRecipient` (ops), không tới email người dùng — không có input email/phone nào trong toàn module nên không có bề mặt cần validation (baseline note "chú ý validation email/phone" không áp dụng; ghi nhận là scope MVP). Không lộ notification của user khác: 2 endpoint đều ADMIN-only, `NotificationResponse` không trả `body`/`payload`. Không có finding.

## entity/Notification.java & changelog

Entity kế thừa `AbstractMappedEntity` (auditing + soft-delete), enum STRING, `@Builder.Default` — đúng chuẩn. Changelog có CHECK constraints (status/channel), unique index `uk_notification_event_id`, index `order_id` — tốt. Lưu ý phụ (không phải finding): lưu cả `payload` JSON gốc (chứa item list, amounts) — hợp lệ cho audit nhưng là PII-adjacent; đảm bảo mã hoá ở tầng DB khi đi prod.

## application.yml (notification-service)

- [Low][security] `resources/application.yml:8` — `POSTGRES_PASSWORD:admin` (default admin/admin đã commit); `:38` `SMTP_FALLBACK_RECIPIENT:ops@example.com`. Không phải attack path trực tiếp (datasource localhost, SMTP disabled mặc định) — nhưng default không fail-fast khi quên override ở môi trường thật. Đề xuất: bỏ default password trong file hoặc chặn khởi động khi env thiếu (profile-aware), và không ship "ví dụ" recipient rơi vào quên-config.

## Tests — notification-service

- [Medium][testing] `test/.../support/NotificationBootstrapIT.java:55` — `assertThat(notificationRepository.count()).isZero()` giả định DB trống; nhưng `NotificationFlowIT` ghi rows vào cùng PostgreSQL container static (không truncate ở đâu) và không `@DirtiesContext` về phía FlowIT — nếu `NotificationFlowIT` chạy trước trong JVM, BootstrapIT fail. `@DirtiesContext` hiện có trên BootstrapIT chỉ "bẩn" context sau khi class kết thúc, không cứu được assertion. Đề xuất: bỏ assert zero (chỉ assert schema/bảng), hoặc xoá dữ liệu `notifications` trong `@BeforeEach` của BootstrapIT, hoặc pin order/container riêng.

- [Low][testing] `test/.../service/impls/NotificationServiceImplTest.java` — thiếu case: `eventId` null/sai format (đường NPE/IAE tại `:38`), `orderId` null (đường DIVE bị che là "duplicate"), và `test/.../controller/BackofficeNotificationControllerTest.java` thiếu case `size=0`/`page=-1` (đường 500 tại controller). Các finding N1/N2/N4 do đó không có rào chắn hồi quy.

### Files không có finding (notification-service)

- `NotificationServiceApplication.java`, `constant/NotificationChannel.java`, `constant/NotificationStatus.java`
- `dto/response/NotificationResponse.java` (self-mapping `from()` gọn)
- `entity/Notification.java`
- `kafka/NotificationListenerConfig.java`, `kafka/OrderEventConsumer.java` (dùng đúng base class + ErrorHandlingDeserializer của fleet)
- `service/NotificationService.java`, `service/NotificationTemplates.java`, `service/sender/NotificationSender.java`, `service/sender/LoggingNotificationSender.java`, `service/sender/SmtpNotificationSender.java`
- `resources/db/changelog/*.yaml`
- Tests: `NotificationFlowIT.java` (IT chất lượng cao, chứng minh poison survival + dedupe race), `config/TestLiquibaseConfig.java`, `controller/BackofficeNotificationControllerTest.java` (trừ gap nêu trên), `service/NotificationTemplatesTest.java`, `service/NotificationWriterTest.java`, `service/sender/*Test.java`, `support/AbstractIntegrationTest.java`

---

# Tổng hợp

## Bảng findings theo Severity × Category

| Severity | pattern | clean-code | dry | solid | error-handling | logging | security | performance | documentation | testing | Tổng |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Critical | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| High | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| Medium | 0 | 0 | 0 | 1 | 7 | 0 | 0 | 0 | 0 | 2 | **10** |
| Low | 3 | 5 | 1 | 0 | 1 | 0 | 2 | 0 | 1 | 2 | **15** |
| **Tổng** | 3 | 5 | 1 | 1 | 8 | 0 | 2 | 0 | 1 | 2 | **25** |

- Search-service: 5 Medium, 8 Low (13 findings).
- Notification-service: 5 Medium, 7 Low (12 findings).

## Đánh giá tuân thủ pattern

**search-service — tuân thủ mức RẤT CAO (ấn tượng).**
Layered structure, record DTO, `ApiResponse<PageResponse<T>>`, error code `SRH-*` qua `BusinessException`, security theo filter chain (storefront không `@PreAuthorize` với javadoc giải thích precedent fleet), Kafka qua common-kafka (listener config riêng vì wire double-encoded — deviation có chủ đích, giải trình bằng chứng F-5), metrics Micrometer, audit endpoint. Mọi deviation đều có comment chủ đích đầy đủ. Test là thế mạnh: IT chạy container ES/Kafka thật, security matrix 401/403, contract test wire shape thật, unit test cho error-mapping — chất lượng test vào hàng tốt nhất kiểu codebase này. 2 vấn đề Medium đáng sửa nhất: cửa sổ race reindex–consumer và contract 503 bị thủng bởi exception không thuộc `RestClientException`; test cần tháo dependency thứ tự chạy giữa ReindexIT và SearchIndexProvisioningIT. Lệch chuẩn kiến trúc hợp lý (ES thay vì JPA) đúng như dự liệu, naming/response/error vẫn theo chuẩn chung.

**notification-service — tuân thủ TỐT nhưng kém đồng nhất hơn search-service.**
Đúng layered + entity/changelog + enum + sender strategy (interface nhiều impl chính đáng — LOG default/SMTP conditional). Consumer sinh tồn poison dựa vào ErrorHandlingDeserializer của fleet (đã được IT chứng minh) nhưng thiếu containment chủ động ở tầng handler như search-service — lỗi semantic payload (eventId/orderId null/sai) gây retry-churn rồi drop. Điểm yếu tập trung: validation tham số controller (size=0 → 500 thô), catch-all `DataIntegrityViolationException` che mọi constraint violation, và trạng thái `SENT` ghi trước khi thực sự gửi. JavaDoc mỏng hơn hẳn (service chủ chốt không có). DTO mutable khác chuẩn record của toàn fleet và của chính search-service — nên thống nhất. Không có vấn đề IDOR: 2 endpoint đều ADMIN-only, không endpoint user-facing, response không trả body/payload. 3 file test slice/unit/IT chất lượng tốt; thiếu edge test cho 3 finding N1/N2/N4 và có 1 test order-dependency (BootstrapIT vs FlowIT có thể flaky theo thứ tự class).