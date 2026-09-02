# Review nền tảng dùng chung — utils/common-* + src chính

- **Ngày:** 2026-09-02
- **Scope:** 7 module `utils/` (common-core, common-security, common-kafka, common-keycloak, common-logging, common-spring, common-storage) + `/src/main` (root) + các file cấu hình (pom, `AutoConfiguration.imports`, `application.yml`, messages bundles).
- **Đã đọc:** 115 file (70+ file Java src/main + src/test; 15 file cấu hình/resources; 8 pom).
- **Loại trừ:** `media-service/` không được đọc (theo chỉ đạo); `target/` bỏ qua.
- **Kết quả:** 0 Critical, 4 High, 21 Medium, 20 Low. Không phát hiện điểm nào phải sửa file (review read-only).

---

## 1. common-core

### Findings

- [Medium][pattern] `utils/common-core/pom.xml:46-48` — `spring-boot-starter-data-jpa` là **compile dependency không optional** trong khi `package-info.java:4-8` tuyên bố đây là module "Phải chạy được khi Spring không có trên classpath / pure contracts". Mọi service (kể cả non-JPA như gateway, ở dịch vụ chỉ publish Kafka) đều bị kéo Hibernate + Spring Data vào graph. Đề xuất: chỉ giữ `jakarta.persistence-api` + `spring-data-commons` (compile), chuyển `spring-boot-starter-data-jpa` xuống `<scope>test</scope>`.
- [Low][documentation] `src/main/java/com/shop/common/core/exception/ErrorCode.java:77,121` — comment sai lệch: `CAMPAIGN_*` (PRO-7xxx) nằm trong mục "Order domain (continued)" và `ORDER_PAYMENT_NOT_CAPTURED` (ORD-4012) nằm cuối mục "Shipping". Thêm: comment dòng 72 "range 6xxx — PAY already owns 5xxx" tạo tiền lệ trôi số; SRH-12xxx và MED-12xxx dùng chung khối 12xxx (khác prefix nên chưa va chạm, nhưng kế hoạch đánh số không đồng nhất: 4 chữ số lẫn 5 chữ số). Đề xuất: sắp xếp lại + ghi rõ bảng phân vùng mã.
- [Low][documentation] `src/main/java/com/shop/common/core/exception/ErrorCode.java:40` — key `auth.password.managed.by.keycloak` (AUTH_PASSWORD_MANAGED_BY_KEYCLOAK) **không tồn tại** trong cả `messages_en.properties` lẫn `messages_vi.properties` (đã grep) → `Messages.get` trả về raw key cho client. Đề xuất: bổ sung key vào 2 bundle (hoặc tối thiểu bundle tiếng Anh làm fallback).
- [Low][clean-code] `src/main/java/com/shop/common/core/i18n/Messages.java:34,40-41` — static mutable `MessageSource` toàn cục: các `ApplicationContextRunner`/multi-context test ghi đè lẫn nhau, không có cơ chế restore → test pollution tiềm tàng. Đề xuất: giữ nguyên pattern nhưng thêm phương thức `clear()` cho test, hoặc holder theo ClassLoader.
- [Low][testing] chỉ có `AbstractMappedEntityTest`; `ApiResponse`/`PageResponse` (edge case `totalElements=0` khiến `first == last == true`, `map()`), `Messages` fallback, `BusinessException` factory không có test. Các record đơn giản nên ưu tiên thấp, nhưng `PageResponse.of` đã có phép tính `ceil` đáng pin-test 1 case.

### Files không có finding

`ApiPaths`, `MdcKey`, `OutboxStatus`, `PageableConstant`, `SoftDeletable` (JavaDoc xuất sắc), `AbstractMappedEntity` (+ test tốt), `DateTimeUtils` (cache formatter đúng), `ApiResponse`, `PageResponse`, `BusinessException` (thiết kế factory + copy-constructor ổn).

---

## 2. common-security — SOI KỸ (blast radius toàn fleet)

### Findings

- [Medium][security] `src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:93-95` — `NimbusJwtDecoder.withIssuerLocation()` chỉ validate `iss`/`exp`/`nbf`, **không validate `aud` (audience)**. Token được cấp cho BẤT KỲ client nào trong cùng realm đều được 13 service chấp nhận (không có ranh giới client-condition nào). Realms có public client (SPA) là điều gần như chắc chắn (bản thân code nhắc tới flow authorization_code cho browser) — token lọt ra từ một client yếu cũng hợp lệ khắp fleet. Đề xuất: `JwtValidators.createDefaultWithIssuer(issuer)` + validator audience (`JwtValidators.createDefault()` không có) đọc từ `shop.security.*`, hoặc tối thiểu pin `azp`/`aud` cho phép.
- [Medium][security] `src/main/java/com/shop/common/security/config/SecurityProperties.java:102-106` — CORS mặc định khi không cấu hình là `allowedOriginPatterns = ["*"]`; class Javadoc (`CorsAutoConfigurer.java:22-25`) còn khuyến khích `allowCredentials = true` kèm pattern `*`. Nghĩa là: service nào bật credentials mà quên pin origins → de facto wildcard-origin **có** credentials (browser gửi cả cookie/`Authorization`) — chống lại ý "default an toàn" trong Javadoc của record. Đề xuất: validate ở binding — nếu `allowCredentials == true` thì `allowedOriginPatterns` không được chứa `*` (throw khi bind), hoặc default chỉ bật khi service khai báo origins rõ ràng.
- [Low][security] `src/main/java/com/shop/common/security/config/SecurityProperties.java:130-142` — `/swagger-resources/**`, `/webjars/**`, `/v3/api-docs/**`, `/swagger-ui/**` luôn public ở mọi service (do `common-spring` kéo springdoc UI vào, xem §6). Không lộ env/RouteEndpoint (kiểm tra: `application.yml` chỉ expose `health,info,prometheus,metrics`, và `/actuator/metrics` KHÔNG nằm trong danh sách public; `show-details: when-authorized` đúng) — nhưng mở toàn bộ surface API docs + webjars cho anonymous. Đề xuất: chuyển swagger-defaults thành property bật/tắt (VD `shop.security.public-paths` + profile dev chỉ public) thay vì hằng số cứng.
- [Medium][clean-code] `src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java:38-49` — `from(Jwt jwt)` lấy `authorities` từ `currentAuthorities()` (SecurityContextHolder của **thread hiện tại**), không suy ra từ `jwt` tham số. Gọi `from()` với token khác token của request (test, handoff async, chức năng giả danh/quản trị) sẽ cho `realmRoles` của token A nhưng `authorities` của context B (hoặc rỗng) — mismatch lặng lẽ. Đề xuất: suy authorities trực tiếp từ `jwt` qua `JwtRolesConverter` như `JwtGrantedAuthoritiesConverter` framework làm; giữ quyền context cho `current()`.
- [Medium][testing] `src/test/.../SecurityPropertiesTest.java:10-17` — module chỉ test compact-constructor của `EndpointRule`; Javadoc còn trỏ sang `SecurityFilterChainIntegrationTest` **nằm ở module khác** (không thuộc library). Không có test nào trong module cho: matcher `public-paths` vs `anyRequest().authenticated()`, quyền đi kèm method, `JwtRolesConverter` (2 authorities/role), assembly CORS (`"*"`+credentials), JwtDecoder conditional. Đây là thành phần bảo vệ 13 service — phải có test tại chỗ. Đề xuất: 1 test slice `ApplicationContextRunner` + `MockMvc` + `@WithMockJwt`/`Jwt.withTokenValue` cho phép/đóng từng rule.
- [Low][clean-code] `config/CorsAutoConfigurer.java:60-61` — `public static final Customizer<Void> NOOP = Customizer.withDefaults();` không ai dùng (comment "kept here so callers can pass..." sai — không có caller); trường `enabled` trong `Cors` record cũng thừa vì `@ConditionalOnProperty` ở class đã giữ vai trò đó. Đề xuất: xóa.
- [Low][documentation] `config/SecurityProperties.java:53-55` — `issuerUri` `@NotBlank` **không có default**: service chỉ dùng `common-security` mà không thừa kế `application.yml` của `common-spring` (override config location, hoặc dependency trực tiếp) sẽ fail context với BindingException khó hiểu. Đề xuất: ghi rõ trong Javadoc rằng default `shop.security.issuer-uri` nằm ở common-spring, hoặc chấp nhận fail-fast kèm thông báo tường minh.

### Files không có finding

`JwtClaimExtractor` (xử lý claim phòng thủ tốt), `JwtRolesConverter` (dual-emit đã documented), `SecurityAutoConfiguration` (imports + conditional + `@EnableMethodSecurity` hợp lý), `package-info`, `SecurityPropertiesTest` (chất lượng assert ổn cho phần nó test).

---

## 3. common-kafka — điểm rủi ro cao nhất của nền tảng

### Findings

- [High][security] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:79` — `jsonDeserializer(...).addTrustedPackages("*")` tắt hoàn toàn cơ chế kiểm tra package của Spring Kafka (`JsonDeserializer` default `useTypeHeaders=true`). Bất kỳ ai ghi được vào topic mà service consume (service khác bị chiếm quyền ở bất kỳ lớp nào, hoặc Kafka lộ network) có thể gắn header `__TypeId__` trỏ tới class tùy ý trên classpath consumer → deserialization gadget (đúng họ CVE-2023-34040 của Spring Kafka). Đề xuất: giới hạn `addTrustedPackages("com.shop.*")` (tối thiểu), tốt hơn là vô hiệu `setUseTypeHeaders(false)` vì producer chỉ emit 1 kiểu event mỗi topic.
- [High][clean-code] `src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java:31-36` — instance `JsonKafkaSerializer` được cấu hình với shared `ObjectMapper` **bị vứt đi**: chỉ `serializer.getClass().getName()` được đưa vào props, Kafka client tự instantiate lại bằng no-arg constructor → serializer thực tế là `new JsonKafkaSerializer()` với `new ObjectMapper()` trần (không `JavaTimeModule`) → mọi event chứa `Instant`/`LocalDateTime` sẽ fail serialize và rơi vào fallback lặng lẽ (finding tiếp theo). Chuỗi lỗi này làm thất bại cả ý đồ `Jackson2ObjectMapperAutoConfiguration`. Đề xuất: `new DefaultKafkaProducerFactory<>(props, keySer, valueSerInstance)` cho phép truyền instance, hoặc bỏ mục đích shared-mapper hoàn toàn.
- [High][error-handling] `src/main/java/com/shop/common/kafka/serialization/JsonKafkaSerializer.java:32-43` — khi Jackson không serialize được thì **im lặng** trả về `data.toString().getBytes()` (không log, không exception). Consumer sẽ nhận "garbage" kiểu `ProductEvent@13f2e` → parse fail → chết kênh tức (message dính DLT nếu có, nếu không thì retry rồi bỏ). Đây là corrupt dữ liệu chủ động, lại trùng với hành vi "prevent data loss" cần fail-fast ở mọi mô hình outbox. Đề xuất: ném `SerializationException` (kèm topic/type trong message), để tầng publisher quyết định; không bao giờ fallback.
- [Medium][pattern] `src/main/java/com/shop/common/kafka/config/KafkaProperties.java:209-210,100-118` — default `consumer.groupId = "shop-service"` cho mọi service và `auto.offset.reset = "earliest"`: service nào quên override group-id sẽ **chia sẻ cùng một consumer group** (hai service consume cùng topic → competing consumers, một bên thầm lặng không nhận được gì); service nào nhận group mới thì replay toàn bộ lịch sử topic. Đây là 2 nút gài mặc định nguy hiểm cho 13 service. Đề xuất: **không có default group-id** (fail context khi thiếu, với thông báo rõ) và default `latest`.
- [Medium][documentation] `src/main/java/com/shop/common/kafka/config/KafkaProperties.java:160-183` — `Retry.backoffMs`/`maxAttempts` được Javadoc tuyên bố "applied by the shared consumer factory" nhưng `BaseKafkaListenerConfig` không đọc chúng; không có retry/DLT nào được wire (dependency `spring-retry` trong pom không dùng). Đề xuất: xóa khối này (nếu service tự wire) hoặc thực sự dựng `RetryTemplate`/CommonErrorHandler + DLT trong khối listener rồi mới giữ.
- [Medium][clean-code] `src/main/java/com/shop/common/kafka/serialization/JsonKafkaDeserializer.java:10-14,15` — class **dead code**: không file main nào tham chiếu (consumer dùng `org.springframework.kafka.support.serializer.JsonDeserializer` của Spring); Javadoc tự xưng "the platform's Kafka consumer deserializer … route it to the DLT" — 2 mệnh đề đều sai (không dùng, không có DLT). Đề xuất: xóa hoặc wire thật; 2 helper test (`toBytes`, `toString`) mang tiếng production API nhưng chỉ để test.
- [Low][logging] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:29-36` — debug log toàn bộ `headers` + `record` (event payload chứa dữ liệu người dùng/đơn hàng). Đề xuất: log key + topic + offsets, hạn chế dump value đầy đủ.
- [Low][clean-code] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:44-51` — `keyType` được lưu nhưng không dùng (Javadoc tự thừa nhận); bỏ tham số hoặc bỏ field.
- [Low] `pom.xml` (test deps) — `testcontainers-kafka` + `spring-boot-testcontainers` không dùng (test chạy `EmbeddedKafkaKraftBroker`).

### Files không có finding

`KafkaMessagePublisher` (interrupt/reset đúng, timeout giới hạn, traceparent test chuẩn), `KafkaPublishException`, `package-info`, `TraceparentHeaderExtractionTest` + `KafkaMessagePublisherTest` (test chất lượng thật, Docker-free — điểm sáng).

---

## 4. common-keycloak

### Findings

- [Medium][error-handling] `src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:146-153` — `verifyCredentials` trả `false` cho **mọi** `KeycloakClientException`, kể cả 5xx/timeout/chết endpoint: sự cố Keycloak bị báo thành "sai mật khẩu" trước mặt người dùng, và exception bị nuốt không log. Đề xuất: phân biệt 401/invalid_grant (→ false) với còn lại (→ ném/WARN), tối thiểu log cảnh báo.
- [Medium][error-handling] `src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:200-237` — `assignRealmRoles` bắt `RestClientResponseException` cho từng role, log WARN rồi **tiếp tục**: user đã tạo nhưng thiếu role thầm lặng → phân quyền sai mà không ai biết; call `createUser` vẫn trả về thành công. Đề xuất: nếu 1 role fetch fail → fail luôn (có thể compensation xóa user hoặc ném lỗi tổng hợp); hoặc tối thiểu trả danh sách role đã gán cho caller.
- [Medium][security] `config/KeycloakAutoConfiguration.java:64-76` + `application.yml` (common-spring) dòng 149-150 — `KeycloakAdminClient` được tạo khi có `shop.keycloak.admin-username`, mà shared yml default là `admin`/`admin` → **mọi service** đều nhận một admin client tới realm với credential mặc định. Nếu Keycloak còn default credential (hoặc env bị misconfig), toàn bộ realm trở thành admin-accessible từ bất kỳ service nào. Đề xuất: bỏ default admin-password khỏi yml (đúng như comment đầu file hứa "Sensitive values are intentionally NOT defaulted here"), chỉ tạo admin client khi **cả** username lẫn password được set.
- [Medium][performance] `client/KeycloakAdminClient.java:74,118,145` — `getAdminAccessToken()` được gọi cho **từng** thao tác (create/delete/reset), mỗi call là 1 round-trip token endpoint; `createUser` tối thiểu 3 token/phân vai trò → N+3 HTTP calls. Đề xuất: cache token + refresh theo `expires_in - skew` (hoặc dùng `OAuth2ClientCredentials` của Spring).
- [Medium][testing] module **không có một test nào** (cả pom không có test stub) — client gọi mạng quan trọng (tạo user, reset password, phân role) hoàn toàn chưa được kiểm chứng. Đề xuất: test với `MockRestServiceServer` cho các flow chính + mapping lỗi status.
- [Low][error-handling] `client/KeycloakAdminClient.java:90,127,161,191,229` — `log.error(..., e.getResponseBodyAsString())` ở nhiều chỗ: body Keycloak có thể echo tham số request (VD reset password), không loại trừ lộ dữ liệu vào log pipeline. Đề xuất: log status + `error` code + sanitized description.
- [Low][dry] `config/KeycloakProperties.java:96-98` — `issuerUri()` suy từ `serverUrl`, trong khi `SecurityProperties` (common-security) lấy `shop.security.issuer-uri` độc lập: 2 nguồn sự thật cho cùng một issuer, lệch nhau sẽ ra 401 mơ hồ. Đề xuất: cho phép `Shop.security.issuer-uri` reference `shop.keycloak.*` (VD `${shop.keycloak.issuer-uri:}`) hoặc binding chung.
- [Low][pattern] `client/KeycloakTokenClient.java:53-64` — dùng ROPC (password) grant, bị OAuth 2.1 deprecate; chấp nhận được cho mạng nội bộ nhưng cần ghi chú lý do + dự kiến thay bằng CIBA/authorization-code.

### Files không có finding

`KeycloakTokenResponse`, `KeycloakClientException`, `KeycloakProperties` (endpoint builders gọn, có Javadoc), `AutoConfiguration.imports`.

---

## 5. common-logging — module có chất lượng test tốt nhất

### Findings

- [Low][clean-code] `src/main/java/com/shop/common/logging/LogField.java:9-18` — record `LogField` **không hề được khởi tạo** ở đâu trong main (LoggerAspect format tay); Javadoc "capturing all fields written to the performance log line" sai so với code. Đề xuất: dùng nó trong `LoggerAspect` (đã có sẵn formatter JSON) hoặc xóa.
- [Low][clean-code] `src/main/java/com/shop/common/logging/aspect/LoggerAspect.java:69-74` — nhánh ERROR log không qua ngưỡng `thresholdMs` (khác với hợp đồng "chỉ log khi vượt ngưỡng"); nếu là chủ đích (luôn muốn thấy lỗi) thì ghi chú.
- [Low][logging] `src/main/java/com/shop/common/logging/Loggable.java:22` — `logInput()` default `true` cho `@Loggable` (khác `LogPerformance` default off): bật DEBUG là args của service/repository method (có thể chứa PII) bị log. Đề xuất: đổi default thành `false` cho đồng bộ.

### Files không có finding

`LogPerformance` (Javadoc đầy đủ), `Audited`, `AuditEvent` (JSON escape có test), `AuditAspect` (PII-safe đã chứng minh bằng test), `AuditActorResolver` (binding KC26 được probe kỹ), `AuditResourceResolver`, `AuditEventWriter`, `BoundedAsyncAuditEventWriter` (bounded pool + throttle đúng spec), 2 autoconfig + properties. Bộ test của module này (overflow, escape, recovery sink, tự động conditional) là hình mẫu cho các module khác.

---

## 6. common-spring — "starter ổng phọt" và config chồng chéo

### Findings

- [High][pattern] `src/main/java/com/shop/common/spring/config/CommonProperties.java:58-186` — toàn bộ aggregator `shop.common.security/keycloak/kafka/storage/logging/defaults` **không được code production nào đọc** (đã grep toàn repo: chỉ `CommonLibraryStarterTests` dùng). Operator bật/tắt `shop.common.security.enabled=false` hay `shop.common.kafka.enabled=true` sẽ **không có tác dụng gì** (switch thật là `shop.security.enabled`, `shop.kafka.enabled`, ...). Javadoc dòng 110-111 còn viện dẫn `shop.keycloak.enabled` — property không tồn tại. Đây là bề mặt vận hành lừa đảo, cộng thêm nguy cơ "cảm giác tắt security". Đề xuất: hoặc wire các toggle này vào `@ConditionalOnProperty` thực, hoặc xóa hẳn record đi (mỗi module đã có switch riêng). Đừng giữ cả hai.
- [Medium][pattern] `src/main/java/com/shop/common/spring/CommonLibraryStarter.java:29-31` — đóng gói class `@SpringBootApplication` có `main()` **trong jar được tiêu thụ**: (1) không ai nên chạy "starter" standalone, (2) nếu component scan của service bao phủ `com.shop.*` thì class này bị scan như một configuration (double annotation effect), (3) template dễ drift. Đề xuất: bỏ `main`/`@SpringBootApplication`, để lại một marker package-info; dịch chuyển example sang module riêng.
- [Medium][pattern] `pom.xml:46-116` — "Drop-in starter" kéo về **mọi thứ**: starter-web, actuator, oauth2-resource-server, OTel + exporter, springdoc-openapi (kèm Swagger UI), **cả** MapStruct lẫn ModelMapper, opencsv, spring-dotenv. Hệ quả trực tiếp: 13 service đều mang Swagger UI ra ngoài và do đó `PlatformDefaults.PUBLIC_PATHS` phải public các path swagger (§2). Đề xuất: tách thành các starter module nhỏ (common-spring-core/web/tracing/mapper) để service chọn, không đóng gói "tất cả trong một".
- [Medium][error-handling] `src/main/java/com/shop/common/spring/web/exception/ApiExceptionHandler.java:195-208` — `handleDataIntegrityViolation` trả **nguyên văn** `exception.getMostSpecificCause().getMessage()` cho client: lộ tên bảng/cột/constraint (Postgres violation message), đôi khi kèm giá trị trong unique-constraint/check — information disclosure từ lớp DB ra API công khai. Đề xuất: trả message chung (`ErrorCode.CONFLICT` đã có), log raw cause ở server (đã log) — xóa `mostSpecificMessage` khỏi body.
- [Medium][logging] `src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java:117-122` — query string được nối **nguyên vẹn** vào mỗi dòng log INFO: các param nhạy cảm trong URL (`?code=`, `?token=`, redirect OAuth) sẽ vào log pipeline toàn fleet. Đề xuất: san hóa params (VD parse và ẩn key-matching list) hoặc chỉ log path.
- [Medium][testing] `ApiExceptionHandler`/`CorrelationIdFilter`/`HttpLoggingFilter` — driver dịch API chạy trên 13 service **không có test nào trong module** (kể cả test cho nhánh mới như OptimisticLocking→409, HttpMessageNotReadable→không leak). Đề xuất: `MockMvc` + `WebMvcTest`-style test từng nhánh dịch exception.
- [Medium][dry] — traceparent injection được copy 3 lần: `tracing/TraceparentInterceptor.java:23-36` (common-spring), `common-kafka KafkaMessagePublisher.java:95-102`, `common-keycloak KeycloakAutoConfiguration.java:34-41`. Bản keycloak có comment biện minh (cycle dependency), bản kafka không. Đề xuất: hạ một helper `TextMapSetter`+injection 8 dòng xuống `common-core` (chỉ cần OTel API, như common-kafka đã dùng scope provided) để 2 bản copy dùng chung.
- [Low][security] `src/main/resources/application.yml:41-43` — `server.error.include-message: always` + `include-binding-errors: always` trên BasicErrorController: các exception nảy **trước** DispatcherServlet (filter chain, 404 của Spring Security) sẽ render message thô nếu không lọt `ApiExceptionHandler`. Đề xuất: `never`.
- [Low][pattern] `src/main/resources/application.yml:20-22` — library default ép `spring.profiles.active: dev` cho consumer nào quên override — default profile dev trong nền tảng dùng chung là dấu hiệu "prod-là-afterthought". Đề xuất: không set default profile ở library.
- [Low][dry] `src/main/java/com/shop/common/spring/mapper/...` — nền tảng vận hành **2 hệ mapping song song** (MapStruct `BaseMapper`/`EntityCreateUpdateMapper` là API public không JavaDoc + ModelMapper autoconfig + `RecordValueReader` tùy biến). Mỗi service tự chọn một đường → nửa nọ nửa kia. Đề xuất: chọn 1 (MapStruct compile-time) và deprecate đường còn lại, hoặc ghi rõ ranh giới trong Javadoc.
- [Low][performance] `src/main/java/com/shop/common/spring/mapping/RecordValueReader.java:31-41` — `getMethod(memberName)` + `invoke` cho mỗi lần đọc property; ModelMapper cache TypeMap nên chi phí chủ yếu ở warm-up, song `getMember` tạo instance mới mỗi lần lặp match. Đề xuất: cache accessor theo `ClassValue`/`ConcurrentHashMap<Class,Map<String,Method>>` nếu đo được hot.
- [Low][documentation] `web/filter/HttpLogProperties.java:44-48` — ý nghĩa `request.enabled` kép (vừa gate sự tồn tại của filter bean ở `WebAutoConfiguration`, vừa gate body log ở filter) không được ghi rõ; `response.enabled` chỉ gate body. Đề xuất: đổi tên `include-body` tách bạch hơn hoặc bổ sung Javadoc.

### Files không có finding

`I18nAutoConfiguration` (SmartInitializingSingleton bridge — thiết kế sạch, chống circular), `Jackson2ObjectMapperAutoConfiguration`, `JpaAuditingAutoConfiguration` (+ test), `ModelMapperAutoConfiguration` (+ test), `TracingAutoConfiguration` (+ test rất tốt kèm env), `MdcOtelCurrentTraceContext` (restore-parent đúng), `TraceparentInterceptor` (+ test), `CorrelationIdFilter`, `I18nProperties`, `TracingAutoConfigurationTest`, `TestSecurityConfig` (test-jar hợp lý).

---

## 7. common-storage

### Findings

- [Medium][pattern] `src/main/java/com/shop/common/storage/config/StorageProperties.java:39-43` — default `accessKey`/`secretKey` = `rustfsadmin`/`rustfsadmin` **cứng trong thư viện**, trong khi auto-config bật mặc định (`matchIfMissing=true`, `ObjectStorageAutoConfiguration.java:25`): service quên override sẽ thầm lặng dùng credential mặc định mà mọi người đều biết. Đề xuất: bỏ default credential khỏi library (chỉ có trong compose/.env của dev), fail-fast khi thiếu key.
- [Medium][pattern] `config/ObjectStorageAutoConfiguration.java:43-50` — `autoCreateBucket = true` (default) + `ensureBucketExists()` chạy trong lúc tạo bean: nếu object store chưa sẵn sàng lúc boot (khởi động song song container, backend down) thì **context fail khởi động** (`StorageException` từ S3Exception khác 404) — service liveness bị buộc vào S3. Đề xuất: tắt default auto-create (hoặc lazy + retriable), và chỉ fail khi bucket thực sự được cấu hình yêu cầu.
- [Low][error-handling] `service/S3ObjectStorageService.java:64-71` — `createBucket` không xử lý race `BucketAlreadyOwnedByYou`/409 khi nhiều service cùng khởi động lần đầu → startup fail mặc dù bucket đã tồn tại. Đề xuất: coi 409/already-owned là thành công.
- [Medium][testing] module **không có test nào**: presign plumbing, 404 handling của `exists()`/`download()`, auto-create behavior, wrapping exception hoàn toàn chưa được pin.
- [Low][clean-code] `service/StorageObject.java:24-26` — factory `of(...)` trùng canonical constructor của record; bỏ cho gọn.

### Files không có finding

`S3ClientFactory` (path-style + presigner chuẩn), `S3ObjectStorageService` (wrap exception nhất quán, docs `StorageObject.content` rõ trách nhiệm đóng stream), `StorageException`.

---

## 8. src/main (root)

### Findings

- [Low][clean-code] `src/main/java/org/example/Main.java:1-17` — file mẫu JetBrains/IntelliJ template ("Hello and welcome!", `IO.println`, `static void main()` phi chuẩn) còn sót trong monorepo; nó không thuộc module nào nhưng nằm trong working tree và gây nhiễu (VD scan, lệnh tìm class có `main`). Đề xuất: xóa.

---

## 9. Findings xuyên module (tổng hợp DRY/consistency)

- [Medium][dry] Traceparent injection ×3 (xem §6) — 2 bản copy không có comment biện minh.
- [Low][pattern] Phong cách `@ConfigurationProperties` không đồng nhất giữa các lib anh em: record (`SecurityProperties`, `CommonProperties`) vs mutable bean (`KafkaProperties`, `StorageProperties`, `KeycloakProperties`, `HttpLogProperties`, `I18nProperties`); catalog `ErrorCode` dùng prolem-style `ERR-0400` trong khi các mã domain dùng `PREFIX-####`.
- [Low][pattern] Cơ chế `enabled` không đồng bộ: `shop.security/kafka/storage/audit.web.logging`... có switch, `common-keycloak` không có switch nào, `CommonProperties` lại mô phỏng switch ảo — ba kiểu trong cùng một nền tảng.

---

## 10. Bảng tổng hợp & Đánh giá chung

### Findings theo severity × category

| Category | Critical | High | Medium | Low |
|---|---|---|---|---|
| security | 0 | 1 | 3 | 2 |
| pattern | 0 | 1 | 6 | 3 |
| error-handling | 0 | 1 | 3 | 1 |
| clean-code | 0 | 1 | 1 | 5 |
| solid | 0 | 0 | 0 | 0 |
| dry | 0 | 0 | 1 | 1 |
| logging | 0 | 0 | 1 | 2 |
| performance | 0 | 0 | 1 | 1 |
| documentation | 0 | 0 | 1 | 3 |
| testing | 0 | 0 | 4 | 1 |
| **Tổng** | **0** | **4** | **21** | **20** |

### Đánh giá chung

**Điểm mạnh:** nền tảng được chăm chút hơn hẳn chuẩn trung bình — JavaDoc dày đặc và phần lớn **chính xác**, auto-configuration đi qua `AutoConfiguration.imports` đúng chuẩn thư viện, `@ConditionalOnMissingBean` để service override, PII-handling trong audit (D6) được suy nghĩ và test kỹ, bộ test common-logging/common-kafka (tracing) là hàng thật. Không có lỗ hổng Critical nào (không có auth bypass trực tiếp trong filter chain, không leak secret ở mã nguồn, actuator exposure hẹp).

**Điểm yếu trọng tâm (4 High):**
1. **Chuỗi lỗi serializer Kafka** (2 High trong common-kafka) — instance serializer bị vứt + fallback `toString()` im lặng: đây là đường dẫn corrupt dữ liệu sự kiện **giữa các service**, khó phát hiện vì producer luôn "thành công".
2. `addTrustedPackages("*")` ở consumer — mở khóa deserialization không kiểm soát cho mọi consumer của fleet.
3. Bề mặt cấu hình giả (`shop.common.*`) + starter ổng phọt — vi phạm trực tiếp chuẩn "library phải gọn, prop phải có hiệu lực thật".

**Khuyến nghị thứ tự xử lý:** (1) sửa 3 điểm High của common-kafka trước (sửa 1 chỗ, cả fleet hưởng); (2) siết JWT audience + trusted-packages + CORS default; (3) xóa/rewire `CommonProperties` và tách kitchen-sink starter; (4) bổ sung test cho common-security/common-keycloak/common-storage — 3 module này gánh chức năng giao dịch nhưng gần như không có test nội bộ.