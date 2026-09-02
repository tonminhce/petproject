# Review chuyên sâu — product-service

- **Ngày:** 2026-09-02
- **Phạm vi:** toàn bộ `/Users/tonminh-mac/IdeaProjects/untitled5/product-service/src` (83 file: 62 main, 21 test; đã đọc đủ từng file, kể cả test và resources)
- **Loại review:** READ-ONLY (không sửa code). Đã đối chiếu chéo với `utils/common-core`, `utils/common-security`, `utils/common-kafka`, `common-spring/application.yml` và producer phía `rating-service` (không đụng tới `media-service/`)
- **Baseline:** layered pattern com.shop.\<service\> (controller/dto/entity/mapper/repository/service+impls), `ApiResponse`, `BusinessException.of(ErrorCode.X)`, `PageableConstant.MAX_PAGE_SIZE`, JWT + public-paths, outbox → Kafka qua `KafkaMessagePublisher`.

Kết luận ngắn: module có chất lượng cao hơn mặt bằng chung — outbox transaction, wire-shape IT, cache TTL per-entry, audit, test matrix backoffice đều rất tốt; **nhưng tồn tại 1 lỗi Critical phá vỡ luồng đồng bộ rating (deserializer không khớp wire format double-encoded) và 1 lỗi High cho phép tạo vòng lặp category (tree endpoint 500 hàng loạt)**.

---

## Findings theo file

### kafka/RatingLifecycleListenerConfig.java + kafka/ProductRatingConsumer.java
- **[Critical][error-handling] `kafka/RatingLifecycleListenerConfig.java:12-22` (kết hợp `ProductRatingConsumer.java:18`) — wire-format mismatch phá vỡ luồng đồng bộ rating.** Producer phía rating-service ghi payload outbox dạng JSON *String* và relay publish qua `KafkaMessagePublisher` → `JsonKafkaSerializer` string-encode lần nữa (double-encoded — đã được minh chứng thực tế bằng `MediaDeletedConsumerIT.wireShapeIsDoubleEncodedOnTheRealBroker`). `RatingLifecycleListenerConfig` lại extends `BaseKafkaListenerConfig<String, RatingLifecycleEvent>` → bind bằng Spring Kafka `JsonDeserializer` (đã kiểm chứng source spring-kafka 4.1.1: `JacksonJsonDeserializer` **không có** logic unwrap string token). Hệ quả: **mọi record `shop.rating.lifecycle.v1` đều lỗi deserialization** → avgRating/ratingCount trên product không bao giờ cập nhật; phía rating-service outbox vẫn đánh dấu SENT (publish thành công) nên không retry — mất mát câm lặng. Chính comment trong `MediaLifecycleListenerConfig.java:14-24` đã mô tả đúng lỗi này cho topic media nhưng không được áp dụng cho rating. Đề xuất: bỏ typed `JsonDeserializer`, chuyển `ratingListenerFactory` sang `StringDeserializer` + tự unwrap trong `ProductRatingConsumer` (mirror `MediaDeletedConsumer.decode`), kèm một wire-shape IT như của media.

### controller/... — một vấn đề chung về giới hạn page size
- **[Medium][pattern] `controller/ProductController.java:46` và `controller/BrandController.java:37` — không chặn page size bằng `PageableConstant.MAX_PAGE_SIZE`.** `BackofficeProductController:51` có `Math.min(size, PageableConstant.MAX_PAGE_SIZE)` còn storefront thì không, trái baseline "page size được chặn bởi PageableConstant.MAX_PAGE_SIZE". Client ẩn danh có thể gọi `GET /api/v1/products?size=1000000` — query + memory nặng không kiểm soát. Đề xuất: dùng chung một helper cap (hoặc ít nhất `Math.min` như backoffice) ở cả 3 controller.

### controller/ProductController.java, BrandController.java, CategoryController.java
- **[Medium][testing] Thiếu security matrix cho các endpoint ghi ADMIN-gated.** `ProductControllerTest` và `BrandControllerTest` dùng `@AutoConfigureMockMvc(addFilters = false)` (bỏ qua cả filter chain lẫn method security) nên không hề verify 401 (vô danh) / 403 (customer) cho POST/PUT/DELETE products/brands/categories — chỉ `BackofficeProductControllerTest` có matrix đầy đủ. Nếu ai đó vô tình gỡ `@PreAuthorize`, toàn bộ suite vẫn xanh. Đề xuất: thêm 2 test slice (anonymous→401, ROLE_USER→403) cho 3 controller mutation, gương theo `BackofficeProductControllerTest`.
- **[Low][clean-code] `ProductController.java:43-44`, `BrandController.java:35-36` — magic number `"0"`/`"20"` cho page/size; `BackofficeProductController.java:48` dùng `"" + PageableConstant.DEFAULT_PAGE_NUMBER` (string concat) nhưng size vẫn literal `"20"`; trong khi `PageableConstant.DEFAULT_PAGE_SIZE = 10` không được dùng ở đâu → default size mỗi nơi một kiểu. Đề xuất: thống nhất hằng số từ `PageableConstant`.
- **[Low][clean-code] DRAFT/DISCONTINUED products lộ công khai.** `GET /api/v1/products` (public-path) không lọc status theo kênh storefront — khách vô danh thấy cả sản phẩm DRAFT (chưa phát hành). Nếu spec không yêu cầu lộ DRAFT cho kênh này, nên lọc ACTIVE (hoặc tách endpoint). Đây là nghiệp vụ, không phải lỗ hổng bảo mật.

### service/CategoryServiceImpl.java
- **[High][clean-code] `CategoryServiceImpl.java:104-108` — không chống cycle parent.** `update` cho phép `parentId == id` (tự làm cha của chính mình) và tạo chu trình hai chiều (A→B rồi B→A). Khi đó `findTree()` dựng node `children` chứa chính nó/vòng lặp → JSON serialization đệ quy vô hạn (Jackson fail) → `GET /api/v1/categories/tree` trả 500 cho **mọi** client vô danh, và nhánh bị cycle biến mất khỏi cây. Một thao tác admin nhập liệu sơ suất làm sập endpoint public cho tới khi sửa tay. Đề xuất: validate `parentId != id` và kiểm tra chuỗi ancestor (đi ngược parent) trước khi set; có thể thêm guard chống cycle trong `findTree` (visited set) để fail-safe.
- **[Medium][clean-code] `CategoryServiceImpl.java:114-123` / `BrandServiceImpl.java:82-91` — delete không kiểm tra dữ liệu phụ thuộc.** Xóa category có con → khi `findTree()` chạy, child có `parent` bị lọc bởi `@SQLRestriction("deleted = false")` → Hibernate trả null → child bị "đẩy lên" root hoặc biến mất (mồ côi thầm lặng). Xóa brand đang được sản phẩm tham chiếu → `ProductMapper.toDetailResponse` đọc `brand` qua `@SQLRestriction` → null → **mọi sản phẩm của brand đó mất brandId/brandName trong detail response** (và trong outbox payload `brandName=null` — trong khi search index vẫn giữ giá trị cũ tới khi có ProductUpdated). Đề xuất: chặn (hoặc cảnh báo) delete khi còn children/products tham chiếu, hoặc xử lý tường minh (reassign/null + republish).

### service/impls/ProductServiceImpl.java (+ Transactional*EventPublisher)
- **[Medium][error-handling] `TransactionalProductEventPublisher.java:93` — `updatedAt` trong event payload bị STALE trên luồng update/delete.** `@LastModifiedDate` của Spring Data auditing chỉ được gán lúc flush (`@PreUpdate` fire tại commit), trong khi publisher chạy *bên trong* transaction trước commit (`repo.save(existing)` là no-op với entity đã managed, không flush). Vì vậy event `ProductUpdated`/`ProductDeleted` mang `updatedAt` của lần ghi trước; tương tự `ProductMediaService.clearReference` và `ProductRatingService.apply` (cả hai publish trước commit). Đồng thời response của `update()` (`mapper.toDetailResponse(saved)`) cũng hiển thị updatedAt cũ. Luồng create thì OK (`@PrePersist` fire ngay tại `persist()`). Test `TransactionalProductEventPublisherTest` set `updatedAt` tay nên không phát hiện. Tác động: consumer downstream (search) nhận timestamp lệch một phiên bản. Đề xuất: gọi `saveAndFlush` trước khi publish (hoặc đọc `updatedAt` lại sau flush) và thêm assertion integration cho giá trị updatedAt thực.
- **[Medium][error-handling] `ProductServiceImpl.java:179-183` — gọi HTTP remote (HEAD media, timeout tối đa 3s) bên trong transaction write.** `assertMediaExists` chạy khi transaction đã mở → giữ DB connection suốt thời gian timeout; media chậm/nghẽn (503 đến muộn) làm nghẽn pool connection cho toàn bộ backlog ghi. Đề xuất: kiểm tra media **trước khi** mở transaction (tách `exists()` ra ngoài `@Transactional` — ví dụ validate trong controller hoặc service method không transactional rồi mới gọi transactional method), hoặc tối thiểu giảm timeout.
- **[Low][clean-code] `ProductServiceImpl.java:132-160` + `ProductMapper.partialUpdate` — không có cách nào qua API để *bỏ* mediaId/categoryId/brandId.** Null = "giữ nguyên" nên không thể gỡ tham chiếu media (về lại imageUrl legacy) hay tháo category/brand. Hiện chỉ consumer `MediaDeleted` mới clear được mediaId. Đề xuất: bổ sung cơ chế unset tường minh (ví dụ JSON patch hoặc sentinel), hoặc ghi chú rõ đây là giới hạn của PATCH-style update.
- **[Low][performance] `ProductServiceImpl.java:52-59` — storefront `findAll` (summary) vẫn fetch-join category+brand.** `ProductRepository.findAll(spec, pageable)` bị override với `@EntityGraph(category, brand)` phục vụ backoffice detail, nhưng summary mapping không dùng quan hệ → over-fetch 2 join trên mọi trang storefront. Đề xuất: tách 2 method repo (một có EntityGraph cho detail, một không cho summary).
- **[Low][performance] `findBySlug` thiếu negative-caching** (do `disableCachingNullValues`) — slug không tồn tại bị truy vấn lại mỗi lần. Chấp nhận được; chỉ lưu ý nếu bị bot scan slug.

### service/impls/Transactional{Brand,Category,Product}EventPublisher.java
- **[Medium][dry] Ba class gần như giống hệt nhau** (`save()` build OutboxEvent: set các field, serialize payload, set PENDING, save, record metric — khác nhau mỗi block payload). Cùng với `RatingEventService` (rating-service) và relay pattern, đây là lần trùng lặp thứ 4 của cùng một boilerplate outbox. Đề xuất: trích base class/helper dùng chung trong common-kafka (hoặc ít nhất một abstract `OutboxEventWriter` trong module) khi có service thứ 4 cần outbox; hiện tại 3 bản copy chấp nhận được nhưng rủi ro lệch contract (như payload 17-field của product đã phải test riêng).

### service/OutboxRelay.java
- **[Low][error-handling] `OutboxRelay.java:48-84` — nhiều instance chạy relay song song, không lock/chọn leader** → cùng một PENDING row có thể được 2 replica đọc và publish trùng (duplicate event). Consumers hiện tại idempotent (dumb copy, clear reference) nên tác động thấp, nhưng cần ghi nhận đây là at-least-once có chủ đích. Đồng thời `metrics.setPendingOutboxCount(pending.size())` ghi gauge bằng kích thước *batch đang fetch* (≤100) chứ không phải tổng số PENDING — metric sai khi backlog > batchSize. Đề xuất: dùng `countByStatus(PENDING)` cho gauge (hoặc đổi tên metric thành pending_batch_size).
- (deviation có chủ đích, đã comment rõ trong class: không `@Transactional` trên cả vòng lặp để mỗi `save()` tự commit — hợp lý, không phàn nàn gì thêm.)

### kafka/MediaDeletedConsumer.java, ProductRatingConsumer.java (posture ack-always)
- **[Low][error-handling] Handler failure bị nuốt (`catch (Exception) log.error`) → record ack, không DLT, không alert** — "ack-always poison posture (fleet containment rule)", deviation có chủ đích và có comment dẫn precedent. Hệ quả cần biết: DB sập thoáng qua lúc xử lý rating event = bản snapshot rating bị mất vĩnh viễn (offset đã trôi). Đề xuất: bổ sung metric đếm swallowed failure (`product.consumer.handled.failure`) để ít nhất có cảnh báo, đừng chỉ log.

### kafka/MediaDeletedConsumer.java
- Không có finding — xử lý double-encoded + poison + ack-skip chuẩn mực (đúng pattern lẽ ra phải áp cho rating consumer). `MediaLifecycleListenerConfig` cũng OK (tự unwrap, comment rất tốt).

### client/MediaHeadClient.java
- Không có finding nghiêm trọng. Error posture 3-trạng-thái (200/404/khác→MED-12006) đúng spec, có test đầy đủ tại `MediaHeadClientTest`. Lưu ý nhỏ: log 404 ở mức INFO sẽ ồn nếu có bot gõ mediaId ngẫu nhiên vào create/update — có thể hạ xuống WARN/DEBUG (không tính điểm sever).
- Ghi nhận tích cực: timeout 3s, propagation X-Correlation-Id + W3C traceparent đầy đủ.

### security/ServiceTokenProvider.java
- Không có finding bảo mật. Cache token với refresh-skew 30s, `synchronized` chống refresh race — đúng. Ghi nhận nhỏ: `expiresIn` về 0/âm (Keycloak lỗi cấu hình) sẽ khiến token luôn bị refresh mỗi lần gọi — có thể thêm guard `expiresIn > REFRESH_SKEW_SECONDS` (Low, không đưa vào bảng).

### config/CacheConfig.java
- Không có finding. Comment giải thích kỹ `immediateWrites()` (fleet rule 6), serializer cho record, per-entry TTL — ví dụ tốt về documentation. (`defaultTyping(true)` + `@class` hint an toàn vì Redis là hạ tầng nội bộ trong network tin cậy.)

### application.yml
- **[Low][security] `application.yml:81` — default hardcoded `client-secret: ${PRODUCT_SERVICE_CLIENT_SECRET:changeme}`.** Nếu quên set env, service chạy với secret "changeme" — nếu một Keycloak test/QA có client đặt đúng secret đó thì service xác thực thành công với credential mặc định ai-cũng-biết. Đề xuất: không đặt default có vẻ "thật" (để trống/throw khi thiếu), hoặc đặt default rõ ràng là không hợp lệ kèm fail-fast lúc khởi động.
- **[Low][clean-code] `product.outbox.*` + `timeout-ms: 3000` là literal config, không env-backed** — không sai, chỉ lưu ý khi deploy cần tune mà không deploy lại.
- Xác nhận: `git ls-files` không thấy file `.env`/secret thật nào bị track (chỉ `.env.example`, `.env.prod.example`) — OK.

### resources/db/changelog/*
- **[Medium][performance] `changelog-004-media-reference.yaml:16` — cột `media_id` không có index.** `ProductRepository.findByMediaId(mediaId)` (WHERE media_id = ? AND deleted = false) seq-scan toàn bảng sản phẩm cho **mỗi** event MediaDeleted. Đề xuất: changelog mới `CREATE INDEX idx_products_media_id ON products (media_id)` (có thể partial `WHERE deleted = false`).
- Các changelog còn lại OK: partial unique index (slug/sku theo deleted=false), partial index PENDING (id) cho relay (changelog-002) là chi tiết rất tốt. Lưu ý nhỏ: `changelog-003` khai `avg_rating ... DEFAULT 0.00` trong khi entity để nullable (không vi phạm validate, chỉ hơi lệch ý nghĩa) — không đáng kể.

### entity/, dto/, mapper/, repository/ (còn lại)
- Không có finding đáng kể:
  - `Product.mediaId` có comment giải thích rõ không-FK (cross-service integrity eventual) — tốt.
  - `BrandMapper/CategoryMapper/ProductMapper` — manual map response + `partialUpdate` null-guard tường minh, `toEntity` chủ động `setId(null)` chống mass assignment id — đúng chuẩn.
  - `ProductRepository` override `findAll(spec, pageable)` kèm EntityGraph chỉ với ManyToOne (scalar join, pagination ở SQL) — đúng Hibernate 6, không bị in-memory pagination.
  - DTO request đầy đủ `@NotBlank/@Size/@DecimalMin/@Min` — không có mass assignment.
  - Lưu ý nhỏ (không tính): `CategoryMapper.partialUpdate` không set `parentId` (được service xử lý riêng) — hành vi đúng nhưng dễ nhầm khi đọc lệch file; comment thêm 1 dòng sẽ rõ.

### kafka/*Event.java (envelopes)
- Không có finding. `@JsonIgnoreProperties(ignoreUnknown = true)` trên cả hai envelope và comment tương thích contract 7/13 field — chuẩn.

---

## Files không có finding

- `ProductServiceApplication.java`, `constant/ProductStatus.java`, `dto/ProductFilter.java`
- Toàn bộ `dto/request/*` và `dto/response/*`
- `entity/Brand.java`, `entity/Category.java`, `entity/OutboxEvent.java`, `entity/Product.java`
- `kafka/MediaDeletedConsumer.java`, `kafka/MediaLifecycleEvent.java`, `kafka/MediaLifecycleListenerConfig.java`, `kafka/RatingLifecycleEvent.java`
- `client/MediaHeadClient.java` (ngoài lưu ý log level nhỏ), `config/CacheConfig.java`, `config/MediaClientConfig.java`, `config/MediaClientProperties.java`
- `security/ServiceTokenProvider.java`
- `repository/BrandRepository.java`, `repository/CategoryRepository.java`, `repository/OutboxEventRepository.java`, `repository/ProductRepository.java`
- `service/BrandService.java`, `BrandEventPublisher.java`, `CategoryService.java`, `CategoryEventPublisher.java`, `ProductEventPublisher.java`, `ProductMediaService.java` (chỉ kế thừa finding stale-updatedAt), `ProductMetrics.java`, `ProductRatingService.java` (kế thừa finding stale-updatedAt + wire bug ở config), `ProductService.java`
- `db/changelog/changelog-001..003.yaml`, `db.changelog-master.yaml`
- Các test: `MediaHeadClientTest`, `MediaHeadValidationIT`, `ProductControllerAuditTest`, `MediaDeletedConsumerIT`, `MediaDeletedConsumerTest`, `ProductRatingConsumerTest`, `ProductMapperMediaFieldsTest`, `ProductMapperRatingFieldsTest`, `ProductRepositoryTest`, `OutboxRelayIntegrationTest`, `ProductRatingOutboxIntegrationTest`, `ProductRatingServiceTest`, `BrandServiceImplTest`, `CategoryServiceImplTest`, `ProductServiceImplTest`, `TransactionalProductEventPublisherTest`, `support/AbstractIntegrationTest`, `config/TestLiquibaseConfig`

---

## Tổng hợp findings theo severity × category

| Category | Critical | High | Medium | Low | Tổng |
|---|---|---|---|---|---|
| error-handling | 1 | 0 | 2 | 2 | 5 |
| clean-code | 0 | 1 | 1 | 3 | 5 |
| pattern | 0 | 0 | 1 | 0 | 1 |
| dry | 0 | 0 | 1 | 0 | 1 |
| performance | 0 | 0 | 1 | 2 | 3 |
| testing | 0 | 0 | 1 | 0 | 1 |
| security | 0 | 0 | 0 | 1 | 1 |
| logging | 0 | 0 | 0 | 0 | 0 |
| documentation | 0 | 0 | 0 | 0 | 0 |
| solid | 0 | 0 | 0 | 0 | 0 |
| **Tổng** | **1** | **1** | **7** | **8** | **17** |

(1 ghi chú deviation "có chủ đích" chưa tính là finding: ack-always consumer posture — theo fleet containment rule.)

## Đánh giá tuân thủ pattern chung

- **Cấu trúc:** tuân thủ rất tốt — package gốc `com.shop.productservice`, layered đủ (controller/dto/entity/mapper/repository/service+impls/kafka/client/security/config), `ApiResponse`/`PageResponse`, records cho DTO, `BusinessException.of(ErrorCode.*)`, `ddl-auto: validate` + Liquibase, `${ENV_VAR:default}`.
- **Deviation cần lưu ý:** (1) dùng `@PreAuthorize` thay vì chỉ filter chain — deviation có chủ đích: method security được bật ở `SecurityAutoConfiguration` (`@EnableMethodSecurity`), đã kiểm chứng hoạt động (test matrix backoffice chứng minh 401/403), có comment dẫn precedent `OrderController.verifyPurchase`; đây thực ra là mẫu tốt hơn baseline mô tả. (2) Các mã lỗi dùng chung là `PRD-2xxx`, `MED-12xxx` thay vì "PREFIX-6xxx" như baseline nói — khác biệt chỉ ở tài liệu, không phải lỗi code. (3) Kafka consumer ack-always (nuốt lỗi handler) — deviation có chủ đích theo fleet rule.
- **Độ sạch:** tốt, comment public API đầy đủ và chính xác (hiếm gặp — ví dụ CacheConfig, MediaDeletedConsumer, changelog). Test phủ dày (unit + slice + IT có Testcontainers thật), riêng việc không có wire-IT cho rating consumer đã để lọt lỗi Critical — đây là bài học quan trọng nhất của đợt review.