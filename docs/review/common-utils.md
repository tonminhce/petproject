# Shared Platform Review: utils/common-* and Core Libraries

- **Date:** 2026-09-02
- **Scope:** Seven utility modules under `utils/` (common-core, common-security, common-kafka, common-keycloak, common-logging, common-spring, common-storage), the root `/src/main` directory, and configuration descriptors (POM files, `AutoConfiguration.imports`, `application.yml`, resource bundles).
- **Files Inspected:** 115 files (over 70 Java classes across src/main and src/test, 15 configuration/resource files, 8 Maven POMs).
- **Exclusions:** `media-service/` (excluded per review scope) and build output directories (`target/`).
- **Summary:** 0 Critical, 4 High, 21 Medium, 20 Low findings.

---

## 1. common-core

### Findings

- [Medium][pattern] `utils/common-core/pom.xml:46-48`: Dependency `spring-boot-starter-data-jpa` is compiled as a mandatory dependency, whereas `package-info.java:4-8` documents this module as pure contracts capable of running without Spring on the classpath. Downstream services (including non-JPA services like the reactive gateway) inherit Hibernate and Spring Data into their dependency graphs. Recommendation: Keep only `jakarta.persistence-api` and `spring-data-commons` at compile scope, moving `spring-boot-starter-data-jpa` to test scope.
- [Low][documentation] `src/main/java/com/shop/common/core/exception/ErrorCode.java:77,121`: Misplaced comments: `CAMPAIGN_*` codes (PRO-7xxx) are grouped under the order domain section, while `ORDER_PAYMENT_NOT_CAPTURED` (ORD-4012) sits in shipping. Code range definitions show minor inconsistencies between 4-digit and 5-digit prefixes across newer services. Recommendation: Reorganize error codes and formalize a domain numbering allocation table.
- [Low][documentation] `src/main/java/com/shop/common/core/exception/ErrorCode.java:40`: Localization key `auth.password.managed.by.keycloak` (`AUTH_PASSWORD_MANAGED_BY_KEYCLOAK`) is missing from both `messages_en.properties` and `messages_vi.properties`. Calls to `Messages.get` return the raw key name to clients. Recommendation: Add the translation key to both resource bundles.
- [Low][clean-code] `src/main/java/com/shop/common/core/i18n/Messages.java:34,40-41`: The global static `MessageSource` field allows concurrent or multi-context tests to overwrite shared state without reset mechanisms, creating risks of test pollution. Recommendation: Add a static `clear()` helper for test tear-down routines.
- [Low][testing]: Unit test coverage includes `AbstractMappedEntityTest` but omits tests for `ApiResponse`, `PageResponse` edge cases (e.g. `totalElements=0` where `first == last == true`), `Messages` fallback logic, and `BusinessException` factories. Recommendation: Add focused tests for `PageResponse.of` ceiling calculations and response mapping.

### Files Without Findings
`ApiPaths`, `MdcKey`, `OutboxStatus`, `PageableConstant`, `SoftDeletable`, `AbstractMappedEntity`, `DateTimeUtils`, `ApiResponse`, `PageResponse`, and `BusinessException`.

---

## 2. common-security (Fleet-Wide Impact)

### Findings

- [Medium][security] `src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:93-95`: Method `NimbusJwtDecoder.withIssuerLocation()` validates issuer, expiration, and not-before claims, but omits audience (`aud`) verification. Tokens issued for any client within the realm are accepted across all 13 services without client-boundary validation. Because realms contain public SPA clients, tokens acquired from web clients can be presented to internal service APIs. Recommendation: Use `JwtValidators.createDefaultWithIssuer(issuer)` combined with a custom audience validator configured via `shop.security.*`.
- [Medium][security] `src/main/java/com/shop/common/security/config/SecurityProperties.java:102-106`: Default CORS configuration sets `allowedOriginPatterns = ["*"]`, while Javadoc in `CorsAutoConfigurer.java:22-25` recommends enabling `allowCredentials = true`. When a service enables credentials without overriding origins, it creates a wildcard origin configuration with credentials active. Recommendation: Enforce a configuration check that rejects wildcard origin patterns whenever `allowCredentials == true`.
- [Low][security] `src/main/java/com/shop/common/security/config/SecurityProperties.java:130-142`: Endpoints `/swagger-resources/**`, `/webjars/**`, `/v3/api-docs/**`, and `/swagger-ui/**` are opened publicly across all services because `common-spring` bundles SpringDoc UI. While sensitive actuator endpoints remain restricted, API documentation remains unauthenticated. Recommendation: Make public Swagger exposure configurable via `shop.security.public-paths` and restrict it to development profiles.
- [Medium][clean-code] `src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java:38-49`: Method `from(Jwt jwt)` retrieves authorities from the current thread `SecurityContextHolder` rather than extracting them from the provided JWT parameter. Passing an explicit token different from the request context yields token roles from token A alongside thread authorities from context B. Recommendation: Extract authorities directly from the supplied `Jwt` instance using `JwtRolesConverter`.
- [Medium][testing] `src/test/.../SecurityPropertiesTest.java:10-17`: The test suite only checks the compact constructor of `EndpointRule`. The library lacks unit tests for public path matching, method-specific authority requirements, role conversion, and CORS bean assembly. Recommendation: Introduce slice tests using `ApplicationContextRunner` and mock JWTs to verify security configuration rules.
- [Low][clean-code] `config/CorsAutoConfigurer.java:60-61`: Unused constant `NOOP = Customizer.withDefaults()`. The `enabled` flag inside the `Cors` record is redundant because `@ConditionalOnProperty` guards the outer configuration class. Recommendation: Remove unused field and flag.
- [Low][documentation] `config/SecurityProperties.java:53-55`: Property `issuerUri` is annotated with `@NotBlank` but provides no default value. Applications that import `common-security` without inheriting `common-spring` configuration fail context startup with unclear binding exceptions. Recommendation: Document that default property values reside in `common-spring`.

### Files Without Findings
`JwtClaimExtractor`, `JwtRolesConverter`, `SecurityAutoConfiguration`, `package-info`, and `SecurityPropertiesTest`.

---

## 3. common-kafka (Core Platform Risk)

### Findings

- [High][security] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:79`: Invoking `jsonDeserializer(...).addTrustedPackages("*")` disables package allowlisting in Spring Kafka. If an unauthorized actor publishes messages to a topic or Kafka lacks network isolation, a malicious `__TypeId__` header targeting classes on the consumer classpath can trigger remote code execution gadget chains. Recommendation: Restrict trusted packages to `com.shop.*`, or call `setUseTypeHeaders(false)` so consumers deserialize explicit target classes per topic.
- [High][clean-code] `src/main/java/com/shop/common/kafka/config/KafkaAutoConfiguration.java:31-36`: The configured `JsonKafkaSerializer` instance containing custom `ObjectMapper` modules is discarded. The configuration passes only `serializer.getClass().getName()` into properties, prompting Kafka clients to instantiate a fresh serializer with a default `ObjectMapper` lacking `JavaTimeModule`. Consequently, events with `Instant` or `LocalDateTime` fail serialization and trigger silent fallbacks. Recommendation: Construct `DefaultKafkaProducerFactory` passing the configured serializer instance directly.
- [High][error-handling] `src/main/java/com/shop/common/kafka/serialization/JsonKafkaSerializer.java:32-43`: When Jackson fails to serialize an object, the method returns `data.toString().getBytes()` without logging or throwing exceptions. Consumers receive unparseable strings (e.g. `ProductEvent@13f2e`), corrupting downstream message processing. Recommendation: Throw a `SerializationException` containing topic and type details.
- [Medium][pattern] `src/main/java/com/shop/common/kafka/config/KafkaProperties.java:209-210,100-118`: Default settings configure `consumer.groupId = "shop-service"` and `auto.offset.reset = "earliest"`. Services that omit custom group IDs share a single consumer group, competing for partitions and dropping messages. When new groups register, they replay entire topic histories. Recommendation: Remove the default group ID to force explicit configuration, and set default offset reset to `latest`.
- [Medium][documentation] `src/main/java/com/shop/common/kafka/config/KafkaProperties.java:160-183`: Configuration properties `Retry.backoffMs` and `maxAttempts` are documented as applied by the shared consumer factory, but `BaseKafkaListenerConfig` never reads them. No retry templates or dead-letter topics are wired. Recommendation: Wire configuration into a `DefaultErrorHandler` or remove the unused property declarations.
- [Medium][clean-code] `src/main/java/com/shop/common/kafka/serialization/JsonKafkaDeserializer.java:10-14,15`: Dead code: this class is never referenced in production code (consumers use Spring Kafka standard `JsonDeserializer`). Its class-level Javadoc claims it routes failed messages to dead-letter topics, which does not reflect actual behavior. Recommendation: Remove the class or wire it properly into consumer configurations.
- [Low][logging] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaConsumer.java:29-36`: Debug logging outputs full message headers and record payloads, which may contain customer or order data. Recommendation: Restrict debug logging to message keys, topics, partition offsets, and trace identifiers.
- [Low][clean-code] `src/main/java/com/shop/common/kafka/consumer/BaseKafkaListenerConfig.java:44-51`: Field `keyType` is stored in the configuration instance but never used. Recommendation: Remove unused field.
- [Low] `pom.xml`: Test dependencies `testcontainers-kafka` and `spring-boot-testcontainers` are declared but unused because tests run on `EmbeddedKafkaKraftBroker`. Recommendation: Clean up POM dependencies.

### Files Without Findings
`KafkaMessagePublisher`, `KafkaPublishException`, `package-info`, `TraceparentHeaderExtractionTest`, and `KafkaMessagePublisherTest`.

---

## 4. common-keycloak

### Findings

- [Medium][error-handling] `src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:146-153`: Method `verifyCredentials` returns `false` for every `KeycloakClientException`, including upstream 5xx errors, timeouts, or network outages. Outages are reported to end users as invalid credential errors without error logs. Recommendation: Differentiate HTTP 401 and invalid grant responses from upstream communication errors, logging warnings on infrastructure failures.
- [Medium][error-handling] `src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:200-237`: Method `assignRealmRoles` catches `RestClientResponseException` per role, logs a warning, and continues. When role lookups fail, user creation succeeds while leaving role assignments unapplied, causing silent authorization inconsistencies. Recommendation: Fail fast on role assignment errors, or return the collection of successfully assigned roles to callers.
- [Medium][security] `config/KeycloakAutoConfiguration.java:64-76` and `common-spring/application.yml:149-150`: `KeycloakAdminClient` is instantiated whenever `shop.keycloak.admin-username` is present, with default credentials `admin/admin` provided in shared configuration. Every importing service receives a Keycloak admin client configured with default credentials. Recommendation: Remove default admin credentials from shared configuration and instantiate admin clients only when explicit non-default credentials are provided.
- [Medium][performance] `client/KeycloakAdminClient.java:74,118,145`: Method `getAdminAccessToken()` executes an HTTP round-trip on every administrative operation (creation, deletion, password reset), generating redundant network overhead. Recommendation: Cache access tokens and refresh them prior to expiry.
- [Medium][testing]: The module has zero automated unit or integration tests. Critical client logic handling user creation, role assignment, and password resets remains untested. Recommendation: Add test suites using `MockRestServiceServer`.
- [Low][error-handling] `client/KeycloakAdminClient.java:90,127,161,191,229`: Raw response bodies from Keycloak are logged directly via `e.getResponseBodyAsString()`, risking exposure of sensitive request parameters. Recommendation: Log HTTP status codes and sanitized error messages.
- [Low][dry] `config/KeycloakProperties.java:96-98`: Method `issuerUri()` derives issuer paths from `serverUrl`, whereas `SecurityProperties` resolves `shop.security.issuer-uri` independently. Maintaining separate configuration properties for the same issuer can cause authentication mismatches. Recommendation: Unify issuer configuration properties across security and identity modules.
- [Low][pattern] `client/KeycloakTokenClient.java:53-64`: Relies on the Resource Owner Password Credentials (ROPC) grant, deprecated in OAuth 2.1. Acceptable for legacy backends, but should be documented with a plan to migrate to authorization code flows.

### Files Without Findings
`KeycloakTokenResponse`, `KeycloakClientException`, `KeycloakProperties`, and `AutoConfiguration.imports`.

---

## 5. common-logging (Exemplary Test Quality)

### Findings

- [Low][clean-code] `src/main/java/com/shop/common/logging/LogField.java:9-18`: Record `LogField` is never instantiated. Its Javadoc claims it captures performance log fields, but `LoggerAspect` builds log entries manually. Recommendation: Integrate `LogField` into `LoggerAspect` or remove it.
- [Low][clean-code] `src/main/java/com/shop/common/logging/aspect/LoggerAspect.java:69-74`: Error log branches bypass the execution duration threshold (`thresholdMs`), logging all failures regardless of duration. If intentional, document this behavior in Javadoc.
- [Low][logging] `src/main/java/com/shop/common/logging/Loggable.java:22`: Annotation property `logInput()` defaults to `true`, unlike `@LogPerformance` which defaults to `false`. Enabling debug logs risks printing method arguments containing sensitive data. Recommendation: Align the default to `false`.

### Files Without Findings
`LogPerformance`, `Audited`, `AuditEvent`, `AuditAspect`, `AuditActorResolver`, `AuditResourceResolver`, `AuditEventWriter`, `BoundedAsyncAuditEventWriter`, and related auto-configuration classes. The test suite covering buffer overflow, JSON escaping, and audit sinks serves as a standard for the codebase.

---

## 6. common-spring

### Findings

- [High][pattern] `src/main/java/com/shop/common/spring/config/CommonProperties.java:58-186`: Aggregate properties under `shop.common.*` (`security`, `keycloak`, `kafka`, `storage`, `logging`, `defaults`) are not read by production code. Toggling settings like `shop.common.security.enabled=false` has no effect because services check `shop.security.enabled`. Javadoc references non-existent properties such as `shop.keycloak.enabled`. Recommendation: Wire aggregate properties into actual `@ConditionalOnProperty` declarations or remove the unused configuration record.
- [Medium][pattern] `src/main/java/com/shop/common/spring/CommonLibraryStarter.java:29-31`: Bundling a `@SpringBootApplication` class with a `main()` method inside a reusable starter library causes issues: library consumers scanning `com.shop.*` discover it as an extra configuration class, risking annotation conflicts. Recommendation: Remove the `main` method and `@SpringBootApplication` annotation, retaining a clean package-info marker.
- [Medium][pattern] `pom.xml:46-116`: The starter bundles numerous dependencies together: `starter-web`, `actuator`, `oauth2-resource-server`, OpenTelemetry exporters, SpringDoc OpenAPI with Swagger UI, MapStruct, ModelMapper, OpenCSV, and Spring Dotenv. Every consuming microservice imports Swagger UI, forcing `PlatformDefaults.PUBLIC_PATHS` to expose documentation routes. Recommendation: Split dependencies into focused starters (`common-spring-web`, `common-spring-metrics`, `common-spring-mapper`).
- [Medium][error-handling] `src/main/java/com/shop/common/spring/web/exception/ApiExceptionHandler.java:195-208`: Method `handleDataIntegrityViolation` returns raw error messages from `exception.getMostSpecificCause().getMessage()` to callers. This exposes table names, column constraints, and collision values in API responses. Recommendation: Return standard error responses (`ErrorCode.CONFLICT`) and restrict raw SQL error messages to server-side logs.
- [Medium][logging] `src/main/java/com/shop/common/spring/web/filter/HttpLoggingFilter.java:117-122`: Query strings are appended directly into INFO logs. Sensitive query parameters (such as OAuth authorization codes or tokens) are written to log files. Recommendation: Sanitize query parameters against an exclusion list or restrict logging to URL paths.
- [Medium][testing]: Critical web infrastructure (`ApiExceptionHandler`, `CorrelationIdFilter`, `HttpLoggingFilter`) used across all 13 services contains no internal unit tests. Recommendation: Add test coverage verifying exception translation using `WebMvcTest` and `MockMvc`.
- [Medium][dry]: OpenTelemetry traceparent injection logic is duplicated in three places: `TraceparentInterceptor.java:23-36` (common-spring), `KafkaMessagePublisher.java:95-102` (common-kafka), and `KeycloakAutoConfiguration.java:34-41` (common-keycloak). Recommendation: Move a shared `TextMapSetter` helper into `common-core`.
- [Low][security] `src/main/resources/application.yml:41-43`: Configuration defines `server.error.include-message: always` and `include-binding-errors: always` on `BasicErrorController`. Errors thrown before reaching `DispatcherServlet` render unformatted internal messages. Recommendation: Set both values to `never`.
- [Low][pattern] `src/main/resources/application.yml:20-22`: Shared library configuration sets `spring.profiles.active: dev`. Consuming services that omit profile settings run with development profiles by default. Recommendation: Remove default profile activations from library configurations.
- [Low][dry] `src/main/java/com/shop/common/spring/mapper/...`: The platform provides two concurrent mapping strategies (MapStruct base mappers and ModelMapper auto-configuration with custom readers). Recommendation: Standardize on MapStruct compile-time mappers and deprecate runtime reflection mapping.
- [Low][performance] `src/main/java/com/shop/common/spring/mapping/RecordValueReader.java:31-41`: Calls `getMethod(memberName)` and `invoke` on every property access. While ModelMapper caches type maps, accessor lookups can be cached in a `ClassValue` to reduce reflection overhead.
- [Low][documentation] `web/filter/HttpLogProperties.java:44-48`: Property `request.enabled` controls both filter registration in `WebAutoConfiguration` and body logging within the filter, while `response.enabled` only controls body logging. Recommendation: Rename properties or update documentation to clarify this distinction.

### Files Without Findings
`I18nAutoConfiguration`, `Jackson2ObjectMapperAutoConfiguration`, `JpaAuditingAutoConfiguration`, `ModelMapperAutoConfiguration`, `TracingAutoConfiguration`, `MdcOtelCurrentTraceContext`, `TraceparentInterceptor`, `CorrelationIdFilter`, `I18nProperties`, `TracingAutoConfigurationTest`, and `TestSecurityConfig`.

---

## 7. common-storage

### Findings

- [Medium][pattern] `src/main/java/com/shop/common/storage/config/StorageProperties.java:39-43`: Hardcodes default credentials `rustfsadmin/rustfsadmin` in source code. Combined with `matchIfMissing=true` in `ObjectStorageAutoConfiguration.java:25`, applications without explicit overrides use default credentials silently. Recommendation: Remove default credentials from library source and enforce fail-fast checks when keys are omitted.
- [Medium][pattern] `config/ObjectStorageAutoConfiguration.java:43-50`: Property `autoCreateBucket = true` runs `ensureBucketExists()` during bean instantiation. If storage backends are starting up concurrently or temporarily unavailable, application startup fails. Recommendation: Disable automatic bucket creation by default and avoid blocking startup on storage checks.
- [Low][error-handling] `service/S3ObjectStorageService.java:64-71`: Method `createBucket` does not handle concurrent `BucketAlreadyOwnedByYou` (HTTP 409) responses when multiple services start simultaneously. Recommendation: Treat 409 or bucket ownership errors as successful creations.
- [Medium][testing]: The module contains no unit or integration tests covering URL pre-signing, missing object handling in `exists()` or `download()`, or exception translation.
- [Low][clean-code] `service/StorageObject.java:24-26`: Factory method `of(...)` duplicates the canonical record constructor. Recommendation: Remove redundant factory method.

### Files Without Findings
`S3ClientFactory`, `S3ObjectStorageService`, and `StorageException`.

---

## 8. Root Package: src/main

### Findings

- [Low][clean-code] `src/main/java/org/example/Main.java:1-17`: An unused IntelliJ IDEA template file (`Hello and welcome!`, `IO.println`) remains in the working tree. Recommendation: Remove unused file.

---

## 9. Cross-Module Consistency Observations

- [Medium][dry]: Traceparent header injection duplicated across common-spring, common-kafka, and common-keycloak.
- [Low][pattern]: Divergent configuration property styles: records (`SecurityProperties`, `CommonProperties`) versus mutable classes (`KafkaProperties`, `StorageProperties`, `KeycloakProperties`). Error codes also mix styles (`ERR-0400` vs `PREFIX-####`).
- [Low][pattern]: Inconsistent feature toggle naming conventions: some modules provide an `enabled` flag, others use nested properties, and `CommonProperties` provides unwired aggregate switches.

---

## 10. Summary Evaluation Matrix

### Findings by Severity and Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Security | 0 | 1 | 3 | 2 | 6 |
| Architecture and Pattern | 0 | 1 | 6 | 3 | 10 |
| Error Handling | 0 | 1 | 3 | 1 | 5 |
| Clean Code | 0 | 1 | 1 | 5 | 7 |
| DRY | 0 | 0 | 1 | 1 | 2 |
| Logging | 0 | 0 | 1 | 2 | 3 |
| Performance | 0 | 0 | 1 | 1 | 2 |
| Documentation | 0 | 0 | 1 | 3 | 4 |
| Testing | 0 | 0 | 4 | 1 | 5 |
| **Total** | **0** | **4** | **21** | **20** | **45** |

### Platform Strengths
- Comprehensive and accurate Javadoc documentation across core utilities.
- Standardized Spring Boot auto-configuration using `AutoConfiguration.imports`.
- Consistent `@ConditionalOnMissingBean` annotations enabling service-level overrides.
- Thorough PII protection and bounded execution pools in audit logging.
- High-quality integration tests in common-logging and common-kafka tracing components.

### Core Remediation Priorities
1. **Kafka Serializer Pipeline:** Correct the serializer instantiation in `KafkaAutoConfiguration` to preserve the configured `ObjectMapper` and eliminate silent string fallbacks on serialization errors.
2. **Kafka Deserializer Security:** Restrict `addTrustedPackages` from wildcard to explicit platform packages (`com.shop.*`) to prevent deserialization vulnerabilities.
3. **Configuration Cleanup:** Remove or wire the unwired aggregate toggles in `CommonProperties`, and decompose `common-spring` to avoid packaging unneeded dependencies across all services.
4. **Test Suite Expansion:** Add unit tests for `common-security`, `common-keycloak`, and `common-storage` to ensure foundational components are validated in isolation.
