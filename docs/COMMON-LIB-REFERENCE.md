# Common Library Reference

> How to use the 7 `utils/` modules from any backend service. Workspace source
> is already adapted to `com.shop.*`; reference repo source is
> [`com.ecommerce.*`](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib).
>
> **TL;DR** — every backend service adds ONE Maven dep (`common-spring`) and
> gets the rest transitively. There is no need to depend on the other 6
> directly; `common-spring` re-exports the auto-configurations.

```
┌──────────────────────────────────────────────────────────────────┐
│  <service>  ──>  common-spring  ──>  common-core                 │
│                                   ──>  common-security           │
│                                   ──>  common-logging            │
│                                   ──>  common-keycloak (opt-in)  │
│                                   ──>  common-kafka     (opt-in) │
│                                   ──>  common-storage   (opt-in) │
└──────────────────────────────────────────────────────────────────┘
```

## 0. Where the files live in this workspace

```
utils/
├── common-core/        ← contracts, exceptions, viewmodels, constants
│   └── src/main/java/com/shop/common/core/
│       ├── constants/        ApiPaths, MdcKey, PageableConstant
│       ├── data/             SoftDeletable (soft-delete base)
│       ├── exception/        BusinessException, ErrorCode
│       ├── i18n/             Messages
│       ├── util/             DateTimeUtils
│       └── viewmodel/        ApiResponse, PageResponse
│
├── common-security/     ← JWT, OAuth2 resource server, CORS
│   └── src/main/java/com/shop/common/security/
│       ├── config/           SecurityAutoConfiguration, BaseSecurityConfig,
│       │                     CorsAutoConfigurer, SecurityProperties
│       └── jwt/              JwtClaimExtractor, JwtRolesConverter, AuthenticatedUser
│
├── common-logging/      ← @LogPerformance AOP, @Loggable, request/response log filter
│   └── src/main/java/com/shop/common/logging/
│       ├── aspect/           LoggerAspect
│       ├── config/           LoggingAutoConfiguration, PerformanceLogProperties
│       └── *.java            LogPerformance, Loggable, LogField
│
├── common-keycloak/     ← Keycloak clients (token + admin, REST via RestClient)
│   └── src/main/java/com/shop/common/keycloak/
│       ├── client/           KeycloakTokenClient, KeycloakAdminClient
│       ├── config/           KeycloakAutoConfiguration, KeycloakProperties
│       ├── dto/              KeycloakTokenResponse
│       └── exception/        KeycloakClientException
│
├── common-kafka/        ← KafkaTemplate wrapper, base consumer
│   └── src/main/java/com/shop/common/kafka/
│       ├── config/           KafkaAutoConfiguration, KafkaProperties
│       ├── consumer/         BaseKafkaConsumer, BaseKafkaListenerConfig
│       ├── exception/        KafkaPublishException
│       ├── producer/         KafkaMessagePublisher
│       └── serialization/    JsonKafkaSerializer, JsonKafkaDeserializer
│
├── common-storage/      ← S3-compatible object storage
│   └── src/main/java/com/shop/common/storage/
│       ├── client/           S3ClientFactory
│       ├── config/           ObjectStorageAutoConfiguration, StorageProperties
│       ├── exception/        StorageException
│       └── service/          ObjectStorageService, S3ObjectStorageService, StorageObject
│
└── common-spring/       ← meta-starter (registers every auto-config + platform defaults)
    └── src/main/java/com/shop/common/spring/
        ├── CommonLibraryStarter.java          (@SpringBootApplication reference)
        └── config/CommonProperties.java
```

## 1. Wiring it into a service

### 1.1 Maven dependency

```xml
<!-- <service>/pom.xml -->
<dependencies>
    <!-- one dependency is enough — transitively pulls the other 6 -->
    <dependency>
        <groupId>com.shop.microservices</groupId>
        <artifactId>common-spring</artifactId>
        <version>${revision}</version>
    </dependency>

    <!-- explicit opt-in for Kafka / Keycloak / Storage beans (only if you need them) -->
    <!-- <dependency><groupId>com.shop.microservices</groupId><artifactId>common-kafka</artifactId></dependency> -->
    <!-- <dependency><groupId>com.shop.microservices</groupId><artifactId>common-keycloak</artifactId></dependency> -->
    <!-- <dependency><groupId>com.shop.microservices</groupId><artifactId>common-storage</artifactId></dependency> -->

    <!-- service-specific -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.liquibase</groupId>
        <artifactId>liquibase-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 1.2 Service skeleton

```java
// auth-service/src/main/java/com/shop/authservice/AuthServiceApplication.java
package com.shop.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuthServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}
```

No `@ComponentScan` on the parent — component scan picks up everything below
`com.shop.authservice` automatically. The common lib auto-configurations are
loaded from `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## 2. common-core — the must-know API

### 2.1 `ApiResponse<T>` envelope

[Workspace source](../utils/common-core/src/main/java/com/shop/common/core/viewmodel/ApiResponse.java) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib/viewmodel/ApiResponse.java)

Every controller MUST return an `ApiResponse<T>`. The record auto-injects
`traceId` from MDC (`MdcKey.TRACE_ID`) so distributed tracing works out of
the box.

```java
@GetMapping("/{id}")
public ApiResponse<UserResponse> get(@PathVariable Long id) {
    return ApiResponse.ok(userService.findById(id));               // success, no message
}

@PostMapping
public ApiResponse<UserResponse> create(@RequestBody @Valid RegisterRequest req) {
    return ApiResponse.ok(userService.create(req), "User created"); // success + message
}

@DeleteMapping("/{id}")
public ApiResponse<Void> delete(@PathVariable Long id) {
    userService.delete(id);
    return ApiResponse.message("User " + id + " deleted");
}
```

JSON shape (success):

```json
{
  "success": true,
  "code": "OK",
  "message": "User created",
  "data": { "id": 42, "username": "alice" },
  "traceId": "8f3a9c1e",
  "timestamp": "2026-08-23T10:00:00Z"
}
```

Error shape (set by `GlobalExceptionHandler` in common-spring):

```json
{
  "success": false,
  "code": "AUTH-1001",
  "message": "Username already exists",
  "errors": ["username: alice"],
  "path": "/api/v1/auth/signup",
  "traceId": "8f3a9c1e",
  "timestamp": "2026-08-23T10:00:01Z"
}
```

### 2.2 `ApiPaths` constants

[Workspace source](../utils/common-core/src/main/java/com/shop/common/core/constants/ApiPaths.java)

Pin every `@RequestMapping` to one of these constants. No string literals.

```java
@RequestMapping(ApiPaths.USERS)   // → "/api/v1/users"
@RequestMapping(ApiPaths.PRODUCTS)
@RequestMapping(ApiPaths.ORDERS)
```

| Constant | Value |
|----------|-------|
| `API_V1` | `/api/v1` |
| `AUTH`, `USERS`, `ROLES` | `/api/v1/auth`, `/users`, `/roles` |
| `PRODUCTS`, `CATEGORIES` | `/api/v1/products`, `/categories` |
| `CARTS`, `ORDERS`, `PAYMENTS` | `/api/v1/carts`, `/orders`, `/payments` |
| `INVENTORY`, `SHIPPINGS`, `FAVOURITES`, `MEDIAS` | `/api/v1/inventory`, `/shippings`, `/favourites`, `/medias` |
| `NOTIFICATIONS`, `EMAILS` | `/api/v1/notifications`, `/emails` |
| `BACKOFFICE_RATINGS`, `BACKOFFICE_PRODUCTS`, `BACKOFFICE_SEARCH` | split storefront vs. backoffice |
| `BACKOFFICE_PROMOTIONS`, `BACKOFFICE_TAX_CLASSES`, `BACKOFFICE_TAX_RATES` | `/api/v1/backoffice/…` |

### 2.3 `BusinessException` + `ErrorCode`

[Workspace source](../utils/common-core/src/main/java/com/shop/common/core/exception/BusinessException.java) ·
[ErrorCode.java](../utils/common-core/src/main/java/com/shop/common/core/exception/ErrorCode.java)

Throw sites — pick the smallest API that fits:

```java
import static com.shop.common.core.exception.BusinessException.*;
import com.shop.common.core.exception.ErrorCode;

// Canonical — preferred for NEW services (machine-readable code + i18n message)
throw BusinessException.of(ErrorCode.PRODUCT_NOT_FOUND);
throw BusinessException.of(ErrorCode.AUTH_USERNAME_EXISTS, username);

// Convenience shortcuts — all map to an ErrorCode under the hood, message from i18n key
throw BusinessException.badRequest("auth.password.too.short");   // BAD_REQUEST code
throw BusinessException.unauthorized("auth.token.expired");       // UNAUTHORIZED code
throw BusinessException.forbidden("auth.role.missing");            // FORBIDDEN code
throw BusinessException.notFound("auth.user.not.found.with.username", username);  // NOT_FOUND code
throw BusinessException.conflict("auth.email.exists", email);     // CONFLICT code
throw BusinessException.internalServerError("payment.stripe.timeout");
```

> **Canonical pattern (for new services):** `BusinessException.of(ErrorCode.X, args...)`. This emits a stable `code` field (e.g. `PRD-2001`, `AUTH-1003`) in `ApiResponse.error(...)` for dashboards. The convenience shortcuts (`notFound("key")`, `conflict("key")`) are acceptable for ad-hoc messages but produce only the i18n message — no machine-readable code. **auth-service** currently uses string keys (`notFound("auth.user.not.found.for.update", id)`); migration to enum is a tracked Phase-9 follow-up — see [`ROADMAP §8.1`](./ROADMAP.md).

`ErrorCode` is an enum that pins `code + httpStatus + messageKey`. New
error codes are added here so dashboards stay canonical. The
`Messages.get(messageKey, args)` call resolves the localized string from
`i18n/messages*.properties`.

### 2.4 `MdcKey` — correlation ID propagation

[Workspace source](../utils/common-core/src/main/java/com/shop/common/core/constants/MdcKey.java)

Used implicitly by `ApiResponse` and the request/response log filter. You
normally don't import this; the filter does. If you write a custom async
piece, set it explicitly:

```java
MDC.put(MdcKey.TRACE_ID, correlationId);
try {
    // ... work
} finally {
    MDC.remove(MdcKey.TRACE_ID);
}
```

### 2.5 `HeaderConstants` — removed

`HeaderConstants`, `CollectionUtils`, `StringUtils`, `ErrorResponse` and
`ResourceNotFoundException` from the reference were **not ported** to the
workspace `common-core`. Use `MdcKey` for the correlation header,
`ApiResponse.error(...)` for the error shape, and `BusinessException`
(`badRequest/unauthorized/notFound/...`) for typed errors.

### 2.6 `PageableConstant` — pagination defaults

```java
PageableConstant.DEFAULT_PAGE  = 0;
PageableConstant.DEFAULT_SIZE  = 10;
PageableConstant.MAX_PAGE_SIZE = 100;
PageableConstant.DEFAULT_SORT_BY = "id";
```

Use these in your controllers to avoid magic numbers.

## 3. common-security — JWT + CORS

### 3.1 What it auto-configures

`SecurityAutoConfiguration` ([workspace source](../utils/common-security/src/main/java/com/shop/common/security/config/SecurityAutoConfiguration.java))
plus `BaseSecurityConfig` provide:

- OAuth2 Resource Server with JWT (issuer = Keycloak `JWT_ISSUER_URI`)
- Method-level `@PreAuthorize("hasAuthority('USER' or 'ADMIN')")`
- CORS via `CorsAutoConfigurer`
- Public paths whitelist: `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/**`
- A `JwtAuthenticationConverter` that maps Keycloak realm roles to Spring
  `GrantedAuthority`s

### 3.2 Use it in a service

```java
// auth-service/.../controller/UserController.java
@RestController
@RequestMapping(ApiPaths.USERS)
@RequiredArgsConstructor
public class UserController {

    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.ok(userService.findByUsername(jwt.getSubject()));
    }

    @GetMapping("/all")
    @PreAuthorize("hasAuthority('ADMIN')")
    public ApiResponse<Page<UserResponse>> list(...) { ... }

    @PutMapping("/{id}")
    @PreAuthorize("isAuthenticated() and hasAuthority('USER')")
    public ApiResponse<UserResponse> update(...) { ... }
}
```

### 3.3 `AuthenticatedUser` helper

[Workspace source](../utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java)

```java
@AuthenticationPrincipal AuthenticatedUser me;
String userId   = me.getId();
String username = me.getUsername();
Set<String> roles = me.getRoles();   // ["USER", "ADMIN"]
String token    = me.getToken();
```

### 3.4 Add public endpoints

```yaml
# <service>/src/main/resources/application.yml
shop:
  security:
    public-paths:
      - /api/v1/payments/webhook/stripe   # signature-verified, no JWT
      - /api/v1/notifications/health
```

`SecurityProperties` is a record with `List<String> publicPaths` and `resolvedPublicPaths()` that merges service paths with `PlatformDefaults.PUBLIC_PATHS` (actuator/swagger/api-docs — always public).

> **Method-aware variant (planned for product-service, see spec §7.1.1):** for services that need GET-only public endpoints (e.g. catalog browse), `publicPaths` will be upgraded to `List<EndpointRule>` with `{ method: HttpMethod.GET, path: /api/v1/products/** }` format. `auth-service` keeps the simple list form. Migration tracked in [`ROADMAP §8.1`](./ROADMAP.md).

Reference: [SecurityProperties.java](../utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java).

### 3.5 Reference (original)

[common-lib/common-security](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-security)

### 3.6 ModelMapper — single canonical mapping pattern

[Workspace source](../utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/ModelMapperAutoConfiguration.java) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-spring/src/main/java/com/ecommerce/commonlib/mapper)

> **Single pattern for the whole fleet — KHÔNG dùng MapStruct / BaseMapper / EntityCreateUpdateMapper.** [Workspace decision 2026-08-26] chốt ModelMapper, sync theo auth-service (`UserMapper`/`RoleMapper` là `@Component` inject `ModelMapper`). Nếu MapStruct trong file mới → review fail.

```java
// common-spring/ModelMapperAutoConfiguration — wired automatically
@Bean @ConditionalOnMissingBean(ModelMapper.class)
public ModelMapper modelMapper() {
    ModelMapper mapper = new ModelMapper();
    mapper.getConfiguration()
        .setMatchingStrategy(MatchingStrategies.STRICT)
        .setSkipNullEnabled(true)
        .setFieldMatchingEnabled(true);
    return mapper;
}
```

Usage in any service:

```java
@Component
public class XxxMapper {
    private final ModelMapper modelMapper;
    public XxxMapper(ModelMapper modelMapper) { this.modelMapper = modelMapper; }

    public XxxResponse toResponse(Xxx entity) {
        // modelMapper.map(...) cho field cùng tên + STRICT match
        // manual setter cho relations / computed fields
    }

    public Xxx toEntity(XxxCreateRequest request) {
        Xxx e = modelMapper.map(request, Xxx.class);
        e.setId(null);
        return e;
    }

    public void partialUpdate(Xxx target, XxxUpdateRequest request) {
        // DTO là record → null check từng field thủ công
        if (request.foo() != null) target.setFoo(request.foo());
    }
}
```

**DTO convention**: dùng `record` (compile-time check, immutability), hoặc `@Builder @Getter @Setter @AllArgsConstructor` class (giống auth-service `UserResponse`) — nếu record thì mapper dùng constructor trực tiếp `new XxxResponse(id, ...)`; nếu class thì `XxxResponse.builder()...build()`. Common-spring không ép kiểu.

Exceptions: relationship fields với tên không khớp (vd `Product.category` → DTO `categoryId`/`categoryTitle`) — xử lý thủ công trong mapper, KHÔNG dùng custom ModelConverter trừ khi tái sử dụng nhiều nơi.

## 4. common-logging — `@LogPerformance` + `@Loggable`

### 4.1 `@LogPerformance` — time a service method

[Workspace source](../utils/common-logging/src/main/java/com/shop/common/logging/LogPerformance.java) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/common-lib/common-logging/src/main/java/com/ecommerce/commonlib/logging/LogPerformance.java)

```java
import com.shop.common.logging.LogPerformance;

@Service
public class ProductServiceImpl implements ProductService {

    @Override
    @LogPerformance(title = "list products", logInput = false, thresholdMs = 50)
    public Page<ProductDto> findAll(int page, int size) {
        // ...
    }
}
```

When the method returns, the aspect emits a JSON log entry like:

```json
{"ts":"...","level":"INFO","logger":"com.shop.common.logging.aspect.LoggerAspect",
 "title":"list products","duration_ms":3.21,"correlationId":"..."}
```

### 4.2 `@Loggable` — request/response body log

[Workspace source](../utils/common-logging/src/main/java/com/shop/common/logging/Loggable.java)

Annotate a controller method to dump its request/response bodies (subject
to size limits):

```java
@PostMapping
@Loggable(in = true, out = true, maxBodyBytes = 2048)
public ApiResponse<...> create(@Valid @RequestBody ... req) { ... }
```

Configure globally via `application.yml`:

```yaml
shop:
  web:
    logging:
      request:
        enabled: true
        include-body: true
        max-body-bytes: 2048
      response:
        enabled: true
        include-body: false
      performance:
        threshold-ms: 50
```

### 4.3 `LogField` — structured fields

[Workspace source](../utils/common-logging/src/main/java/com/shop/common/logging/LogField.java)

```java
@LogPerformance(title = "checkout")
public OrderDto checkout(@LogField("userId") String userId, CartDto cart) { ... }
```

The aspect will add `"userId":"alice"` to the log JSON.

## 5. common-keycloak — admin client

### 5.1 When to use it

Use this for **admin** operations against Keycloak (create user, assign role,
delete user). Do NOT use it for token issuance or validation (the resource
server filter in common-security does that automatically).

### 5.2 Beans

[Workspace source](../utils/common-keycloak/) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-keycloak)

> The reference's single `KeycloakAuthClient` was split in workspace commit
> `2c6c35c` into two focused clients backed by Spring `RestClient` (no
> Keycloak admin SDK).

| Bean | Purpose |
|------|---------|
| `KeycloakTokenClient` | login (ROPC) / refresh / logout / auth-code exchange |
| `KeycloakAdminClient` | admin ops: create/delete user, reset password, assign realm roles |
| `KeycloakTokenResponse` | record for token JSON |
| `KeycloakProperties` | `shop.keycloak.*` config |
| `KeycloakClientException` | unchecked wrapper over Keycloak HTTP errors |

### 5.3 Configuration

```yaml
shop:
  keycloak:
    server-url:        http://keycloak:8080
    public-server-url: http://keycloak.ecommerce.local
    realm:             ecommerce
    client-id:         ecommerce-client
    client-secret:     ${KEYCLOAK_CLIENT_SECRET}
    admin-realm:       master
    admin-client-id:   admin-cli
    admin-username:    admin
    admin-password:    ${KEYCLOAK_ADMIN_PASSWORD}
```

### 5.4 Usage example

```java
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final KeycloakAdminClient keycloakAdminClient;   // from common-keycloak

    @Override
    @Transactional
    public User register(RegisterRequest req) {
        String keycloakId = keycloakAdminClient.createUser(
            req.getUsername(),
            req.getEmail(),
            req.getFullName(),
            req.getPassword(),
            List.of("USER")     // realm roles
        );
        // mirror into local DB, set user.setKeycloakUserId(keycloakId), etc.
    }
}
```

Reference user creation flow:
[UserServiceImpl.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/service/UserServiceImpl.java).

### 5.5 SSO (auth-service only — not yet wired)

The reference auth-service uses `KeycloakAuthClient` for a backend-mediated SSO
flow. Workspace auth-service currently uses `KeycloakTokenClient.login(...)`
(ROPC, `POST /api/v1/auth/login`) instead — the redirect-based SSO flow is
deferred. If later needed, the full flow is in the reference
[AuthController.java](https://github.com/hoangtien2k3/ecommerce-microservices/blob/main/auth-service/src/main/java/com/ecommerce/authservice/controller/AuthController.java).

## 6. common-kafka — publish + consume

### 6.1 Beans

[Workspace source](../utils/common-kafka/) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-kafka)

| Bean | Purpose |
|------|---------|
| `KafkaMessagePublisher` | send POJOs to a topic |
| `BaseKafkaListenerConfig` | enables `@KafkaListener` |
| `BaseKafkaConsumer<T>` | abstract base class |
| `JsonKafkaSerializer<T>` / `JsonKafkaDeserializer<T>` | JSON serde |
| `KafkaProperties` | `shop.kafka.*` config |
| `KafkaAutoConfiguration` | wires the beans |

### 6.2 Publish

```java
@Service
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaMessagePublisher publisher;

    public void publishCreated(Order order) {
        publisher.publish(
            "order.created.v1",
            order.getOrderId().toString(),          // message key (for partitioning)
            new OrderCreatedEvent(order.getOrderId(), order.getUserId(), order.getTotal())
        );
    }
}
```

### 6.3 Consume

```java
@Component
public class OrderCreatedListener extends BaseKafkaConsumer<OrderCreatedEvent> {

    public OrderCreatedListener(KafkaProperties props) {
        super(props, "order.created.v1", "payment-service");
    }

    @Override
    protected void handle(OrderCreatedEvent event, Acknowledgment ack) {
        paymentService.startCheckout(event);
        ack.acknowledge();
    }
}
```

Reference: `common-lib/common-kafka/consumer/BaseKafkaConsumer.java`
[link](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-kafka/src/main/java/com/ecommerce/commonlib/kafka).

### 6.4 Configuration

```yaml
shop:
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: payment-service
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: com.shop.common.kafka.serialization.JsonKafkaDeserializer
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: com.shop.common.kafka.serialization.JsonKafkaSerializer
```

## 7. common-storage — S3-compatible object storage

### 7.1 Beans

[Workspace source](../utils/common-storage/) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-storage)

| Bean | Purpose |
|------|---------|
| `ObjectStorageService` | interface (put / get / delete / presign) |
| `S3ObjectStorageService` | impl backed by AWS SDK v2 |
| `S3ClientFactory` | builds `S3Client` for RustFS / MinIO / AWS S3 |
| `StorageObject` | immutable record: key + bytes + content-type |
| `StorageProperties` | `shop.storage.*` |
| `StorageException` | unchecked |

### 7.2 Configuration

```yaml
shop:
  storage:
    endpoint:   http://rustfs:9000
    region:     us-east-1
    bucket:     ecommerce-media
    access-key: ${STORAGE_ACCESS_KEY}
    secret-key: ${STORAGE_SECRET_KEY}
    path-style-access: true   # required for RustFS / MinIO
```

### 7.3 Usage example (media-service)

```java
@Service
@RequiredArgsConstructor
public class MediaService {

    private final ObjectStorageService storage;

    public MediaDto upload(MultipartFile file, String uploadedBy) {
        String key = "media/" + UUID.randomUUID() + "/" + file.getOriginalFilename();
        storage.put(key, file.getBytes(), file.getContentType());
        return new MediaDto(key, file.getContentType(), file.getSize());
    }

    public String getPresignedUrl(String key, Duration ttl) {
        return storage.presignGet(key, ttl);   // → https://rustfs/...?X-Amz-Signature=…
    }
}
```

## 8. common-spring — the umbrella starter

### 8.1 What it provides

[Workspace source](../utils/common-spring/) ·
[Reference](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-spring)

A single dependency on `common-spring` registers every auto-configuration in
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
com.shop.common.logging.config.LoggingAutoConfiguration
com.shop.common.security.config.SecurityAutoConfiguration
com.shop.common.keycloak.config.KeycloakAutoConfiguration
com.shop.common.storage.config.ObjectStorageAutoConfiguration
com.shop.common.kafka.config.KafkaAutoConfiguration
com.shop.common.spring.autoconfigure.I18nAutoConfiguration
com.shop.common.spring.autoconfigure.WebAutoConfiguration
com.shop.common.spring.autoconfigure.ModelMapperAutoConfiguration
```

Spring Boot dedupes; the other 6 modules' auto-configurations are
re-imported via this single starter, so a service only needs ONE Maven
dependency to get everything.

### 8.2 Platform-wide `application.yml`

[Workspace source](../utils/common-spring/src/main/resources/application.yml)

This file (committed in the `common-spring` jar) supplies defaults for
**every** service:

```yaml
spring:
  threads.virtual.enabled: ${VIRTUAL_THREADS_ENABLED:false}
server:
  port: ${SERVER_PORT:8080}
  shutdown: graceful
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.probes.enabled: true     # liveness + readiness
  health.livenessstate.enabled: true
  health.readinessstate.enabled: true
logging:
  pattern.correlation: "[%X{traceId:-},%X{spanId:-}] "
springdoc:
  api-docs.path: /v3/api-docs
  swagger-ui.path: /swagger-ui.html
```

Services can override anything they need in their own `application.yml`.

### 8.3 `CommonProperties` — `shop.*` config root

[Workspace source](../utils/common-spring/src/main/java/com/shop/common/spring/config/CommonProperties.java)

Everything that begins with `shop.*` in YAML is bound here:

```yaml
shop:
  web:
    cors:
      allowed-origin-patterns: "*"
      allowed-methods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]
    exception-handler.enabled: true
  rest-client:
    connect-timeout: 5s
    read-timeout: 30s
  i18n:
    default-locale: vi
  openapi:
    title: Order Service API
    version: v1
```

### 8.4 JPA auditing — `JpaAuditingAutoConfiguration`

[Workspace source](../utils/common-spring/src/main/java/com/shop/common/spring/autoconfigure/JpaAuditingAutoConfiguration.java)

Every context that runs JPA entities gets `@EnableJpaAuditing` + the fleet
`AuditorAware` (authenticated principal, else `system`) for free — no
service declares its own `@EnableJpaAuditing`; entities simply extend
`AbstractMappedEntity` (common-core), which carries
`@EntityListeners(AuditingEntityListener.class)`.

```yaml
shop:
  jpa:
    auditing:
      enabled: true   # default: true (matchIfMissing)
```

A context that consumes this starter **without a datasource** must opt out —
otherwise `@EnableJpaAuditing` registers a `jpaMappingContext` that throws
`JPA metamodel must not be empty` at boot (no `EntityManagerFactory` exists):

```yaml
shop:
  jpa:
    auditing:
      enabled: false
```

Why a property instead of `@ConditionalOnBean(EntityManagerFactory)`:
bean conditions are evaluated while Spring *parses* configuration classes,
before any `@Bean` method registers its definition — the
`EntityManagerFactory` is never visible at that moment (verified
empirically during fleet-hardening H-11: a class-level
`@ConditionalOnBean` silently disabled auditing across fleet integration
tests, leaving `created_at` NULL). The starter's own no-datasource smoke
test (`CommonLibraryStarterTests`) is the reference opt-out example.

---

## 9. Cheat sheet — "I want to…"

| I want to… | Import this | Code |
|-----------|-------------|------|
| Return a success JSON | `ApiResponse` | `ApiResponse.ok(dto, "msg")` |
| Return a plain message | `ApiResponse` | `ApiResponse.message("done")` |
| Throw 404 with i18n key | `BusinessException` | `BusinessException.notFound("auth.user.not.found", id)` |
| Pin a controller path | `ApiPaths` | `@RequestMapping(ApiPaths.USERS)` |
| Read the current user | `AuthenticatedUser` | `@AuthenticationPrincipal AuthenticatedUser me;` |
| Restrict to ADMIN | spring-security | `@PreAuthorize("hasAuthority('ADMIN')")` |
| Add a public endpoint | `SecurityProperties` | `shop.security.public-paths: [/api/v1/...]` |
| Map entity ↔ DTO | `ModelMapper` | `@Component xxxMapper(ModelMapper mm)` — xem [`§3.6`](#36-modelmapper--single-canonical-mapping-pattern) |
| Time a service method | `@LogPerformance` | `@LogPerformance(title="...")` |
| Log a request body | `@Loggable` | `@Loggable(in=true, out=true)` |
| Publish to Kafka | `KafkaMessagePublisher` | `publisher.publish(topic, key, payload)` |
| Consume from Kafka | extend `BaseKafkaConsumer<T>` | override `handle(event, ack)` |
| Login / refresh / logout against Keycloak | `KeycloakTokenClient` | injected via DI |
| Keycloak admin ops (create user, roles) | `KeycloakAdminClient` | injected via DI |
| Upload a file | `ObjectStorageService` | `storage.put(key, bytes, contentType)` |
| Presigned URL | `ObjectStorageService` | `storage.presignGet(key, Duration.ofMinutes(5))` |
| Get correlation ID | `MdcKey` | `MDC.get(MdcKey.TRACE_ID)` (don't, ApiResponse does it) |
| Get a header name | `HeaderConstants` | `HeaderConstants.CORRELATION_ID` |

---

## 10. Source map (workspace ↔ reference)

| Workspace | Reference repo | Adapt? |
|-----------|---------------|--------|
| `utils/common-core/src/main/java/com/shop/common/core/...` | [common-lib/common-core/src/main/java/com/ecommerce/commonlib/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-core/src/main/java/com/ecommerce/commonlib) | already adapted — keep updating from reference |
| `utils/common-spring/src/main/java/com/shop/common/spring/...` | [common-lib/common-spring/src/main/java/com/ecommerce/commonlib/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-spring/src/main/java/com/ecommerce/commonlib) | workspace adds the umbrella starter |
| `utils/common-security/src/main/java/com/shop/common/security/...` | [common-lib/common-security/src/main/java/com/ecommerce/commonlib/security/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-security) | already adapted |
| `utils/common-logging/src/main/java/com/shop/common/logging/...` | [common-lib/common-logging/src/main/java/com/ecommerce/commonlib/logging/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-logging) | already adapted |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/...` | [common-lib/common-keycloak/src/main/java/com/ecommerce/commonlib/keycloak/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-keycloak) | already adapted |
| `utils/common-kafka/src/main/java/com/shop/common/kafka/...` | [common-lib/common-kafka/src/main/java/com/ecommerce/commonlib/kafka/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-kafka) | already adapted |
| `utils/common-storage/src/main/java/com/shop/common/storage/...` | [common-lib/common-storage/src/main/java/com/ecommerce/commonlib/storage/...](https://github.com/hoangtien2k3/ecommerce-microservices/tree/main/common-lib/common-storage) | already adapted |

When porting new patterns from the reference, copy the Java file then run
`sed -i '' 's/com\.ecommerce/com.shop/g' file.java` to rename the package.

---

See also — [`ROADMAP.md`](./ROADMAP.md) · [`ARCHITECTURE.md`](./ARCHITECTURE.md) · [`SERVICE-CATALOG.md`](./SERVICE-CATALOG.md)
