# Review Report — auth-service, favourite-service, gateway-service

Ngày review: 2026-09-02. Phạm vi: đọc toàn bộ `src/main/java`, `src/test/java`, `src/main/resources` của 3 module (đã loại media-service). Đọc bổ sung các module `utils/common-*` (security, keycloak, logging, core, spring) để xác minh chéo các claim về JWT claim, converter, exception handler, aspect log.

Tổng số file đã đọc: **95** (auth-service: 38, favourite-service: 16, gateway-service: 41).

---

## 1. AUTH-SERVICE

### 1.1 Findings theo file

**dto/request/RegisterRequest.java**
- [Critical][security] `dto/request/RegisterRequest.java:43` — Trường `private Set<String> roles;` do client tự điền, không có allowlist/validate, được chuyển thẳng vào Keycloak và DB (xem UserServiceImpl bên dưới). Endpoint đăng ký là public → **leo quyền (privilege escalation)**: attacker gửi `POST /api/v1/auth/sign-up` với body `{"roles":["ADMIN"], ...}` sẽ được cấp realm role `ADMIN` trong Keycloak (JWT `realm_access.roles=ADMIN`), hợp lệ với `AdminRoleGateFilter` của gateway và mọi `@PreAuthorize("hasAuthority('ADMIN')")`. Đề xuất: tuyệt đối bỏ `roles` khỏi request đăng ký, mặc định `USER`; việc gán role chỉ qua `RoleService.assignRole` (ADMIN-gated); nếu giữ field thì allowlist + uppercase hóa. Bổ sung test đăng ký với `roles=[ADMIN]` phải bị từ chối (hiện không có test nào).

**dto/request/RegisterRequest.java (khác)**
- [Medium][security] `dto/request/RegisterRequest.java:28-31` — `email` chỉ có `@Size(max=50)` + `@Pattern`, không `@NotBlank`; ràng buộc duy nhất là `@NotBlank` trên entity (chỉ chạy lúc flush). Kết hợp với `KeycloakAdminClient.buildUserPayload` đặt `emailVerified: true` cho người tự đăng ký → ai cũng có thể đăng ký email của người khác và được đánh dấu "đã xác thực". Đề xuất: `@NotBlank @Email` trên DTO, yêu cầu luồng xác minh email trước khi `emailVerified=true`.

**dto/response/UserResponse.java**
- [Low][clean-code] `dto/response/UserResponse.java:16` — Trường đặt tên `fullname` (không theo camelCase `fullName` như toàn bộ codebase và entity). Là một phần của JSON contract; khi sửa sẽ break client. Đề xuất: đổi thành `fullName` và cập nhật mapper/test.

**controller/UserController.java**
- [High][security] `controller/UserController.java:40,54,62` + `service/impls/UserServiceImpl.java:211-222` — **Định danh người dùng sai trên toàn bộ cụm `/me`**: trong Keycloak (KC 26, chứng minh bởi `KeycloakRealmImportIT`), JWT `sub` = Keycloak user id (UUID), username nằm ở `preferred_username`. Code đang xử lý mâu thuẫn:
  - `PUT /me`, `DELETE /me`: `UUID.fromString(jwt.getSubject())` → `findById(...)` tra bằng **local id** (Hibernate tự sinh), khác với Keycloak sub → luôn `404`.
  - `GET /me`, `PUT /me/password`: `findByUsername(jwt.getSubject())` tra cột `user_name` bằng giá trị UUID → luôn `404`.
  → Cả 4 endpoint self-service thực tế không hoạt động với token thật (chỉ pass test vì test dùng `sub="alice"`, xem `SecurityFilterChainIntegrationTest.S5`). Đề xuất: tra user bằng `keycloakUserId` (trường đã tồn tại) hoặc dùng claim `preferred_username`/`AuthenticatedUser.username()`; thêm integration test với token Keycloak thật.

**service/impls/UserServiceImpl.java**
- [Critical][logging] `service/impls/UserServiceImpl.java:43` — `@LogPerformance(title = "Register user", logInput = true)`; `LoggerAspect` (common-logging) log `RegisterRequest.toString()` (Lombok) vào INFO khi duration ≥ 50ms mặc định. Register thực hiện nhiều HTTP call tới Keycloak → gần như luôn vượt ngưỡng → **password plaintext + email + phone vào log INFO**. Đề xuất: bỏ `logInput`, hoặc thêm cơ chế mask trong `stringify` cho các field nhạy cảm.
- [Critical][security] `service/impls/UserServiceImpl.java:141-150,169-172` — `createKeycloakUser` và `resolveRoles` nhận role trực tiếp từ `request.getRoles()` (qua `extractRoles`) — vector leo quyền mô tả ở `RegisterRequest` trên. `KeycloakAdminClient.assignRealmRoles` không hề filter role. DB cũng không lọc (vai trò lưu theo enum). Đây là vị trí cần fix chính.
- [High][security] `service/impls/UserServiceImpl.java:88-94` — `delete` chỉ soft-delete bản ghi local, **không vô hiệu hóa/khóa user trong Keycloak** (không logout session, không disable). Một tài khoản bị admin xóa vẫn đăng nhập và gọi API bình thường (JWT Keycloak vẫn hợp lệ; uy quyền ở mọi service chỉ dựa vào JWT). Đồng thời sau soft-delete, `existsByUsername/Email/Phone` (bị `@SQLRestriction` lọc) trả `false` cho định danh còn chiếm chỗ → đăng ký lại trùng tên: pre-check qua nhưng fail ở DB (unique constraint) hoặc ở Keycloak (username đã tồn tại → `KeycloakClientException` → 500). Đề xuất: delete phải gọi `keycloakAdminClient` (disable user + revoke session), restore thì enable lại; xử lý conflict định danh theo đúng nguồn lỗi.
- [Medium][error-handling] `service/impls/UserServiceImpl.java:49-55` — Compensating block chỉ bọc quanh `userRepository.save()`, nhưng flush xảy ra lúc commit transaction → lỗi tại commit (unique-race, constraint) không trigger `rollbackKeycloakUser` → **Keycloak user mồ côi** (dữ liệu phân tán lệch). Đề xuất: dùng `saveAndFlush` trong try, hoặc đặt compensating action ở transaction synchronization (`afterCompletion` với status ROLLBACK).
- [Medium][error-handling] `service/impls/UserServiceImpl.java:141-150,224-229` — `KeycloakClientException` (extends `RuntimeException`, không phải `BusinessException`) không được khai báo trong `ApiExceptionHandler` của common-spring → mọi lỗi Keycloak khi register/login/changePassword (KC down, username trùng, mật khẩu yếu) rò rỉ thành **500 raw**. Đề xuất: bọc và chuyển thành `BusinessException` phù hợp (409/400/503) tại service.
- [Medium][error-handling] `service/impls/UserServiceImpl.java:60-65,187-203` — `update` cho phép đổi `email`/`phone` mà không re-check uniqueness (khác register); vi phạm unique → `DataIntegrityViolationException` (được handler map về 409 generic nhưng mất mã `auth.*` miêu tả). Đề xuất: thêm `existsByEmail/Phone` exclude-self trước khi lưu, trả mã conflict đúng.
- [Medium][pattern] `service/impls/UserServiceImpl.java:118-123` + `controller/UserController.java:76-82` — `findAllUsers` không chặn `PageableConstant.MAX_PAGE_SIZE` (chuẩn fleet yêu cầu); `sortBy` nhận chuỗi thô → `Sort.by` với property không tồn tại hoặc `sortOrder` rác → `IllegalArgumentException` → 500. Đề xuất: `Math.min(size, MAX_PAGE_SIZE)`, whitelist field sort + map direction.
- [Low][error-handling] `service/impls/UserServiceImpl.java:174-180` — `rollbackKeycloakUser` nuốt exception với comment "Log but don't throw" **nhưng không có lệnh log nào** — comment sai lệch so với code, mất hẳn dấu vết khi rollback thất bại. Đề xuất: thêm `log.error` kèm keycloakUserId.

**service/impls/RoleServiceImpl.java**
- [High][error-handling] `service/impls/RoleServiceImpl.java:68-75` — `getUserRoles` thiếu `@Transactional(readOnly = true)` trong khi `open-in-view: false` → truy cập `user.getRoles()` (LAZY) ngoài session → `LazyInitializationException` → `GET /api/v1/roles/users/{id}` luôn 500. Đề xuất: thêm `@Transactional(readOnly = true)` (hoặc fetch roles trong truy vấn).
- [Low][clean-code] `service/impls/RoleServiceImpl.java:31-34` — `Optional.of(repo.findByName(...).orElseThrow(...))` — bọc Optional bên ngoài `orElseThrow` không bao giờ rỗng, API gây hiểu nhầm. Đề xuất: trả thẳng `Role` và ném exception, hoặc trả `Optional` thật.
- [Low][clean-code] `service/impls/RoleServiceImpl.java:25-28` — Constructor injection thủ công trong khi toàn module dùng `@RequiredArgsConstructor`; style không nhất quán.
- [Low][pattern] `repository/RoleRepository.java:18` — `findByNameIn(List<String>)` nhận `String` cho tham số enum; hoạt động nhờ converter ngầm của Spring Data nhưng kiểu dữ liệu lỏng lẻo. Đề xuất: `Collection<RoleName>`.

**controller/AuthController.java, RoleController.java, UserController.java**
- [Medium][pattern] — Cả 3 controller hardcode path (`"/api/v1/auth"`, `"/api/v1/roles"`, `"/api/v1/users"`) thay vì hằng `com.shop.common.core.constants.ApiPaths.AUTH/USERS/ROLES` (chuẩn fleet: `@RequestMapping(ApiPaths.*)`, favorite-service tuân thủ). Đồng thời auth-service dùng `@PreAuthorize` + `@AuthenticationPrincipal Jwt` ở controller (chuẩn fleet: không `@PreAuthorize`, dùng `AuthenticatedUser.requireCurrent()`). `@PreAuthorize` ở đây là cách duy nhất phân quyền ADMIN cho routes `/api/v1/users|roles` (gateway chỉ gate backoffice) nên có thể coi là **deviation có chủ đích một phần**; tuy nhiên việc tự parse `jwt.getSubject()` thay vì `AuthenticatedUser`/`JwtClaimExtractor` chính là gốc rễ của lỗi định danh `/me` ở trên — nên thay phải bằng API an toàn dùng chung `JwtClaimExtractor.username()/subject()` với comment giải thích.

**dto/request/*.java (toàn bộ)**
- [Low][pattern] — DTO auth-service viết bằng class Lombok `@Getter/@Setter` thay vì Java records (chuẩn fleet: records; favourite-service đã theo). Đề xuất: chuyển dần sang record.

**mapper/UserMapper.java**
- [Low][clean-code] `mapper/UserMapper.java:33-35` — `mergeToEntity` không được dùng ở đâu (dead code); đồng thời mapper inject `ModelMapper` chỉ để map một request đơn giản trong khi `toResponse` map tay — dụng cụ nặng cho việc nhỏ. Đề xuất: xóa `mergeToEntity`; cân nhắc map tay luôn cho `toEntity`.

**repository/UserRepository.java**
- [Low][clean-code] `repository/UserRepository.java:66-67` — `findByIdIncludingDeleted` (native query) không có caller nào (kể cả admin restore). Javadoc mô tả như đang dùng. Đề xuất: xóa hoặc thêm endpoint admin thực sự dùng nó.
- [Positive] Các `@Modifying` soft-delete/restore có javadoc đầy đủ, đúng chuẩn soft delete.

**resources/application.yml**
- [Low][security] `application.yml:14-16` — Default fallback `${POSTGRES_PASSWORD:admin}`/`${POSTGRES_USER:admin}` là credential yếu mặc định trong config được commit (`.env` thật không bị track — đã kiểm tra `git ls-files`). Đề xuất: bỏ default cụ thể trong yml, bắt buộc env cung cấp ở môi trường non-dev.
- [Positive] `ddl-auto: validate`, `open-in-view: false`, `shop.security.public-paths` chỉ mở `/api/v1/auth/**`, shutdown graceful — đúng chuẩn.

**resources/db/changelog/**
- [Positive] Cả 3 file sạch: seed 3 role chuẩn, unique constraints, soft-delete + audit columns đầy đủ; comment ở `db.changelog-master.yaml` giải thích lý do dùng absolute path.

### 1.2 Test — findings

**SecurityFilterChainIntegrationTest.java** (fixture `security-filter-chain-fixture.yml`)
- [Positive] Test duy nhất trong fleet chạy filter chain thật (S1–S8), javadoc mô tả rất tốt lý do mock JwtDecoder, tự chứng minh nhánh method-scoped rule trong `BaseSecurityConfig`.
- [Medium][testing] `SecurityFilterChainIntegrationTest.java:174-185` — S5 encode sai giả định chuẩn production (`sub="alice"`), khiến lỗi định danh `/me` (High ở trên) không bị phát hiện; fixture và test hợp nhau nhưng cùng sai với Keycloak thật. Đề xuất: dùng sub là UUID + `preferred_username` đúng như token KC thật.

**AuthControllerTest / UserControllerTest / RoleControllerTest**
- [Medium][testing] — Cả 3 slice test dùng `@AutoConfigureMockMvc(addFilters = false)` → toàn bộ lớp authorization (`@PreAuthorize`, JWT) không được exec; không có test âm về vai trò (USER gọi `/api/v1/users` → 403) và không có test leo quyền khi đăng ký. Đề xuất: ít nhất thêm slice test bật filter với `SecurityMockMvcRequestPostProcessors.jwt()` cho các endpoint ADMIN-gated (hoặc chuyển sang `SecurityFilterChainIntegrationTest`).

**service/impls/*Test**
- [Positive] Độ phủ tốt: rollback KC, duplicate, mismatch password, soft-delete restore — không phụ thuộc ordering/môi trường. Thiếu sót duy nhất: không có case `roles=[ADMIN]` khi register (đã nêu tại finding Critical).

### 1.3 Files không có finding
`AuthServiceApplication.java`, `constant/RoleName.java`, `mapper/RoleMapper.java`, `service/AuthService.java`, `service/UserService.java`, `service/RoleService.java`, `service/impls/AuthServiceImpl.java`, `dto/request/{LoginRequest, ChangePasswordRequest, RefreshTokenRequest, RoleRequest}.java`, `dto/response/TokenResponse.java`, `entity/{User, Role}.java`, `db/changelog/**/(cả 3)`, `service/impls/AuthServiceImplTest.java`, `service/impls/RoleServiceImplTest.java`, `service/impls/UserServiceImplTest.java` (trừ các gap đã nêu ở mục test).

---

## 2. FAVOURITE-SERVICE

### 2.1 Findings theo file

**service/impls/FavouriteServiceImpl.java**
- [Medium][error-handling] `service/impls/FavouriteServiceImpl.java:48-58` — `create` check `existsBy...` rồi save: dưới race (double-click / 2 request đồng thời), `DataIntegrityViolationException` từ partial unique index (`idx_favourites_user_product_unique_active` — index đã có, tốt) sẽ bị handler trả về mã conflict generic thay vì `FAV-6002 FAVOURITE_ALREADY_EXISTS` như nhánh tuần tự. Đề xuất: bắt `DataIntegrityViolationException` quanh save và ném `BusinessException.of(ErrorCode.FAVOURITE_ALREADY_EXISTS)`.
- [Medium][pattern] `service/impls/FavouriteServiceImpl.java:31-38` + toàn module — `userId` lưu vào cột `favourites.user_id` chính là **Keycloak sub** (`AuthenticatedUser.requireCurrent().id()`), trong khi auth-service lưu user định danh local bằng Hibernate-generated UUID khác hẳn. Hai hệ định danh không thể join, mọi báo cáo/backoffice nối favourite ↔ user sẽ lệch; chính sự nhập nhằng này là gốc của lỗi `/me` ở auth-service. Deviation cần ghi nhận và thống nhất một mô hình định danh duy nhất trên toàn fleet (khuyến nghị: dùng Keycloak sub làm khóa ngoài, auth-service lưu `keycloakUserId` làm natural key).

**mapper/FavouriteMapper.java**
- [Low][dry] `mapper/FavouriteMapper.java:11-15` — Inject `ModelMapper` nhưng **không dùng** (toàn bộ map tay, comment tự thừa nhận). Field dead + bean thừa trong context. Đề xuất: xóa field/constructor, chuyển mapper thành `@Component` không phụ thuộc.

**repository/FavouriteRepository.java**
- [Low][clean-code] `repository/FavouriteRepository.java:30` — `findByUserIdAndProductId` không có caller (chỉ `existsByUserIdAndProductId` được dùng). Đề xuất: xóa.
- [Positive] `findByIdAndUserId`, 2 soft-delete query có điều kiện `AND f.userId = :userId` — IDOR-safe từ tầng query, javadoc giải thích rõ lý do trả NOT_FOUND không rò existence.

**resources/db/changelog/db.changelog-master.yaml**
- [Low][documentation] — Favourite dùng `relativeToChangelogFile: true` trong khi comment của auth-service khẳng định "Liquibase 5.0+ ignores relativeToChangelogFile — dùng absolute". Hai module mâu thuẫn nhau về behavior của cùng một thư viện; một trong hai comment sai (hoặc đã lỗi thời) — cần thống nhất và sửa comment tương ứng.
- [Positive] `changelog-001-initial-schema.yaml` — partial unique index đúng chuẩn (kèm raw SQL giải thích), đủ audit + soft-delete columns, index lookup `user_id`.

**controller/FavouriteController.java**
- [Positive] Tuân thủ chuẩn fleet gần như tuyệt đối: `ApiPaths.FAVOURITES`, `PageableConstant.MAX_PAGE_SIZE`, `AuthenticatedUser.requireCurrent().id()` với defensive parse (trả 401 thay vì 500 khi sub không phải UUID), no `@PreAuthorize`, records, comment giải thích chủ đích.

**resources/application.yml**
- [Positive] `public-paths: []` (toàn bộ private theo đúng bản chất dữ liệu cá nhân), `ddl-auto: validate`, `${ENV_VAR:default}`.
- [Low][logging] — Toàn module không có `@LogPerformance`/`@Loggable` ở bất kỳ luồng chính nào (create/delete) trong khi fleet có sẵn annotation — thiếu nhật ký hiệu năng/luồng. Đề xuất: thêm `@LogPerformance` (logInput=false) cho write path.

### 2.2 Files không có finding
`FavouriteServiceApplication.java`, `dto/request/FavouriteCreateRequest.java`, `dto/response/FavouriteResponse.java`, `entity/Favourite.java`, `service/FavouriteService.java`, `config/TestLiquibaseConfig.java` (comment tốt về `@EnableJpaAuditing`), `FavouriteControllerTest.java` (test malformed subject → 401, seed đúng SecurityContext, `@Import(ApiExceptionHandler)`), `FavouriteServiceImplTest.java` (verify ownership-scoped delete, mã lỗi chính xác), `FavouriteRepositoryTest.java` (Testcontainers, kiểm thử cả partial unique index và re-add sau soft delete — chất lượng cao).

---

## 3. GATEWAY-SERVICE

### 3.1 Findings theo file

**filter/ClientIpResolver.java + filter/AdminIpAllowlistFilter.java**
- [High][security] `filter/ClientIpResolver.java:22-28` + `filter/AdminIpAllowlistFilter.java:58-75` — Filter lấy **entry đầu tiên** của header `X-Forwarded-For` do client tự gửi (giả định "một trusted edge overwrites XFF" chỉ nằm trong comment). Với edge **append** thông thường (`$proxy_add_x_forwarded_for` của nginx) hoặc gateway reachable trực tiếp, attacker gửi `X-Forwarded-For: <IP nằm trong CIDR allowlist>` → **bypass toàn bộ D5 IP allowlist** (deny-by-default cho cả gateway trừ webhook/health). `server.forward-headers-strategy: framework` không sanitize giá trị XFF thô trong `request.getHeaders()`. Đề xuất: resolve theo `trusted-proxy-hops` (`XForwardedRemoteAddressResolver`, như `RateLimitKeyResolver` đã làm) hoặc buộc config overwrite proxy; test hiện tại chỉ chứng minh "first entry decisive" chứ chưa test vector giả mạo.
- [Low][clean-code] `filter/ClientIpResolver.java:43-49` — `stripPort` không xử lý IPv6 dạng `[2001:db8::1]:4711` → chuỗi còn bracket bị `isLiteralAddress` loại → client IPv6 hợp lệ có port bị chặn/đẩy về UNKNOWN. Đề xuất: parse đúng RFC-form IPv6-port.

**config/SecurityConfig.java + resources/application.yml**
- [Medium][security] `application.yml:38-39` + `config/SecurityConfig.java:59` — CORS mặc định `allowed-origin-patterns: "*"` **kèm** `setAllowCredentials(true)` → gateway mirror origin bất kỳ với `Access-Control-Allow-Credentials: true` (không nhất quán với common-spring: các service để `allow-credentials: false`). Hiện tại auth là Bearer (không tự attach) nên tác động hạn chế; nhưng đây chính là cấu hình "CORS quá rộng kèm credentials" sẽ thành lỗ hổng ngay khi có bất kỳ endpoint dùng cookie (SSO session). Đề xuất: pin danh sách origin cụ thể qua env (không default `*`), hoặc `allowCredentials=false`.

**ratelimit/RateLimitFilter.java** (bucket4j edge)
- [Medium][performance] `ratelimit/../filter/RateLimitFilter.java:49,75-77` — `ConcurrentHashMap<String, Bucket>` **không bao giờ evict**; key chứa địa chỉ client (client có thể xoay XFF tự do để tăng số bucket) → tăng trưởng bộ nhớ không chặn theo số lượng IP duy nhất từng thấy. Đề xuất: Caffeine `expireAfterAccess`/LRU cap, hoặc định kỳ quét bucket hết hạn.
- [Medium][solid] — Kiến trúc 3 tầng rate-limit chồng lấp: (1) Redis requestRateLimiter per-route per-user/IP (`RoutesConfig`), (2) `GlobalRateLimitFilter` redis bucket chung, (3) bucket4j per-IP edge scopes. Chi phí vận hành + độ phức tạp cao so với lợi ích (V1 gateway đơn instance); đồng thời `trusted-proxy-hops` chỉ được dùng bởi tầng (1), còn `ClientIpResolver` (tầng 3 + 5) tự đọc XFF thô — hai cơ chế resolve IP khác nhau cho cùng một khái niệm. Đề xuất: gộp ít nhất một tầng; thống nhất một `ClientIpResolver` duy nhất dùng `trusted-proxy-hops`.
- [Low][clean-code] `ratelimit/GlobalRateLimitFilter.java:50-54` — Reject 429 trả `setComplete()` với body rỗng, phá vỡ hợp đồng envelope fleet mà `GatewayErrorResponseWriter` đang áp dụng ở mọi nơi khác. Đề xuất: dùng `GatewayErrorResponseWriter` cho cả tầng này.

**config/GatewayProperties.java**
- [Low][clean-code] `config/GatewayProperties.java:14-15` — `keycloakIssuerUri` được yaml/test property inject nhưng **không nơi nào đọc** (JwtDecoder dùng `spring.security.oauth2.resourceserver.jwt.issuer-uri`); config chết, gây hiểu nhầm có hiệu lực. Đề xuất: xóa field + dòng yaml, hoặc wiring thật vào `ReactiveJwtDecoder`.

**config/SecurityConfig.java (khác)**
- [Low][documentation] — `AdminIpAllowlistFilter` khi active chặn cả `/actuator/prometheus` và mọi route storefront của IP ngoài văn phòng (comment có ghi rõ), nhưng chưa ghi chú vận hành rằng Prometheus scraper phải nằm trong CIDR allowlist. Đề xuất: thêm vào yaml comment.

**Test**
- [Low][clean-code] `WebFluxRouteTests.java:3,20` — Import trùng lặp `WireMockServer` 2 lần (hợp lệ nhưng cẩu thả).
- [Low][testing] `ratelimit/RateLimitPropertiesTest.java` (impl) + `filter/RateLimitFilterTest.java:198-204` — test "defaults" khởi tạo record bằng constructor thủ công thay vì bind từ `application.yml`, nên không phát hiện được drift giữa yml và code (vd capacity thực tế). Đề xuất: một test bind `@ConfigurationProperties` duy nhất từ yml `src/main/resources`.

**docker/keycloak/import/ecommerce-realm.json** (file ngoài 3 module nhưng trực tiếp cấu thành miền bảo mật của gateway — `KeycloakRealmImportIT` đọc chính file này)
- [High][security] `docker/keycloak/import/ecommerce-realm.json:64,77,90,103` — 4 client secret `"changeme"` **được commit vào git** (đã xác nhận `git ls-files` track file) và compose/start-docker import nguyên file này làm realm production (comment trong IT: "the value the compose envs (changeme) rely on"). Kẻ có quyền đọc repo (hoặc lịch sử git) lấy được client_secret của 4 confidential client → `client_credentials` grant → JWT service-account mang realm role `SERVICE` → gọi được các endpoint internal SERVICE-gated. Đề xuất: sinh secret mạnh qua env lúc deploy (placeholder + inject khi import), xóa giá trị thật khỏi file tracked; xoay secret hiện tại.

### 3.2 Files không có finding
`GatewayServiceApplication.java`, `config/EdgeFiltersConfiguration.java`, `config/RouteTargetProperties.java`, `config/RoutesConfig.java` (route table đầy đủ 27 route, D1 gate đúng chỗ), `constant/ServiceRoute.java` (enum + prefix cho filter, không hardcode route yaml rời rạc — tốt), `routing/ApiPaths.java`, `filter/AdminIpAllowlistProperties.java` (fail-fast khi CIDR sai), `filter/EdgeRateLimitProperties.java`, `filter/FilterOrder.java`, `filter/GatewayErrorResponseWriter.java` (envelope + i18n choice được document), `filter/RequestPathGuard.java` (xử lý evasion percent-encoding/matrix bearer rất kỹ, có comment), `filter/AdminRoleGateFilter.java` (decode-path matching + resource_access fallback, trả 403 envelope), `ratelimit/RateLimitConfiguration.java`, `ratelimit/RateLimitKeyResolver.java`, `ratelimit/GlobalRateLimitProperties.java`, `resources/logback-spring.xml`, và các test: `WebFluxRouteTests`, `WebFluxIpAllowlistTests`, `WebFluxRateLimitTests`, `SecurityConfigTest`, `RoutesConfigTest`, `FilterOrderTest`, `GatewayErrorResponseWriterTest`, `AdminIpAllowlistFilterTest`, `AdminRoleGateFilterTest`, `ClientIpResolverTest`, `RateLimitFilterTest`, `GlobalRateLimitFilterTest`, `GlobalRateLimitPropertiesTest`, `RateLimitKeyResolverTest`, `GatewayRateLimitContextTest`, `KeycloakRealmImportIT` (IT rất giá trị, đọc realm thật, chứng minh KC 26 subject là UUID), `support/TestKeys.java`.

---

## 4. TỔNG HỢP

### 4.1 Bảng findings theo Severity × Category

| Category \ Severity | Critical | High | Medium | Low | Tổng |
|---|---|---|---|---|---|
| security | 2 (role escalation — 1 gốc, ghi tại 2 vị trí password… xem ghi chú) | 4 (identity /me, soft-delete không khóa KC, XFF spoof, realm secret commit) | 2 (emailVerified, CORS + credentials) | 1 (admin/admin defaults) | 9 |
| error-handling | — | 1 (LazyInitializationException) | 4 (đền bù KC sót ở commit, KeycloakClientException → 500, update uniqueness không check, race create không map mã) | 1 (catch rỗng "Log but don't throw") | 6 |
| logging | 1 (password vào log INFO) | — | — | 1 (favourite thiếu log luồng chính) | 2 |
| pattern | — | — | 3 (@PreAuthorize/Jwt thay AuthenticatedUser + path literal; page cap thiếu; userId 2 mô hình định danh) | 2 (DTO lớp thay vì records; `findByNameIn` tham số String) | 5 |
| performance | — | — | 1 (bucket map không evict) | — | 1 |
| clean-code | — | — | — | 10 (fullname, dead code ×4, Optional kép, ctor style, IPv6 port, import trùng, config chết, 429 rỗng…) | 10 |
| dry / solid | — | — | 1 (3 tầng rate-limit chồng lấp) | 1 (ModelMapper không dùng) | 2 |
| documentation | — | — | — | 2 (relativeToChangelogFile mâu thuẫn; prometheus + allowlist) | 2 |
| testing | — | — | 2 (slice test tắt filter + thiếu test role-negative; S5 giả định sub sai) | 1 (test defaults không bind yml) | 3 |
| **Tổng** | **3** | **5** | **13** | **19** | **40** |

Ghi chú: Critical đếm 3 vị trí tương ứng 2 gốc lỗi — (a) leo quyền ADMIN qua đăng ký, phát hiện tại `RegisterRequest.java:43` và `UserServiceImpl.java:141-172`; (b) mật khẩu plaintext vào log tại `UserServiceImpl.java:43` (xếp hàng logging).

### 4.2 Đánh giá tuân thủ pattern chung

- **auth-service — tuân thủ mức khá, lệch chuẩn đáng kể ở lớp an ninh định danh**: soft-delete/auditing/Liquibase/yml env-var/exception code đúng baseline; nhưng dùng `@PreAuthorize` + `@AuthenticationPrincipal Jwt` + path literal thay vì `AuthenticatedUser`/`ApiPaths`, DTO không phải record, thiếu page cap. Hai vấn đề đặc biệt nghiêm trọng: leo quyền ADMIN qua đăng ký và password lọt vào log — cần sửa trước khi shipped; cụm `/me` hiện tại không hoạt động với token Keycloak thật.
- **favourite-service — module sạch pattern nhất trong ba**: records, `ApiPaths`, `MAX_PAGE_SIZE`, `AuthenticatedUser`, `ErrorCode`, ownership-scoped query, partial unique index, Testcontainers; chỉ còn nitpick và 1 mâu thuẫn mô hình định danh userId cần thống nhất với auth-service.
- **gateway-service — đúng bản chất WebFlux, kỹ lưỡng vượt mức cần thiết về chiều sâu**: route table + D1/D4/D5 filter gọn, env-driven, guard evasion vector cẩn thận, test rất dày và có giá trị thật (WireMock + Keycloak container). Điểm yếu: độ phức tạp 3 tầng rate-limit, XFF trust-first-entry có thể bypass D5 khi đứng sau appending proxy, CORS wildcard + credentials, và secret client Keycloak "changeme" đang bị commit cùng realm import.