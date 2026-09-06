# Code Review Report: auth-service, favourite-service, gateway-service

Review Date: 2026-09-02. Scope: Full read of `src/main/java`, `src/test/java`, and `src/main/resources` across all three modules (excluding media-service). Supplemental cross-reading of `utils/common-*` (security, keycloak, logging, core, spring) to verify claims regarding JWT handling, converters, exception handlers, and logging aspects.

Total files reviewed: **95** (auth-service: 38, favourite-service: 16, gateway-service: 41).

---

## 1. AUTH-SERVICE

### 1.1 Findings by File

**dto/request/RegisterRequest.java**
- [Critical][security] `dto/request/RegisterRequest.java:43`: The `private Set<String> roles;` field is supplied directly by the client without an allowlist or validation checks. It flows into Keycloak and the database as an enum (detailed in UserServiceImpl below). The registration endpoint is unauthenticated: an attacker sending `POST /api/v1/auth/sign-up` with `{"roles":["ADMIN"], ...}` receives the realm role `ADMIN` in Keycloak (`realm_access.roles=ADMIN`). This passes the gateway `AdminRoleGateFilter` and any downstream `@PreAuthorize("hasAuthority('ADMIN')")` checks. Recommendation: Remove `roles` completely from registration requests, defaulting all signups to `USER`. Delegate role assignments exclusively to `RoleService.assignRole` (which is ADMIN-gated). If the field must remain, enforce an uppercase allowlist. Add automated tests asserting that registration with `roles=[ADMIN]` fails with 400 Bad Request.

**dto/request/RegisterRequest.java (Additional)**
- [Medium][security] `dto/request/RegisterRequest.java:28-31`: The `email` field specifies only `@Size(max=50)` and `@Pattern`, missing `@NotBlank`. The only presence constraint exists on the JPA entity, evaluated at flush time. Combined with `KeycloakAdminClient.buildUserPayload` setting `emailVerified: true` for self-registered accounts, any caller can register an arbitrary email and claim verified status. Recommendation: Add `@NotBlank` and `@Email` on the DTO, requiring an email verification loop before setting `emailVerified=true`.

**dto/response/UserResponse.java**
- [Low][clean-code] `dto/response/UserResponse.java:16`: Property name `fullname` deviates from the standard camelCase `fullName` used across the entity model and codebase. Changing this field alters the JSON contract and breaks API clients. Recommendation: Rename to `fullName` and update mappers and tests.

**controller/UserController.java**
- [High][security] `controller/UserController.java:40,54,62` and `service/impls/UserServiceImpl.java:211-222`: Identity mismatch across all `/me` self-service endpoints. In Keycloak (KC 26, confirmed by `KeycloakRealmImportIT`), JWT `sub` contains the Keycloak user UUID, while the username is placed in `preferred_username`. The current implementation presents conflicting queries:
  - `PUT /me` and `DELETE /me`: `UUID.fromString(jwt.getSubject())` calls `findById(...)` looking up local database primary keys (Hibernate-generated), which differ from Keycloak subjects, returning 404.
  - `GET /me` and `PUT /me/password`: `findByUsername(jwt.getSubject())` queries the `user_name` column using the Keycloak subject UUID string, returning 404.
  Consequently, all four self-service endpoints fail with realistic Keycloak tokens (tests masked this by hardcoding `sub="alice"` in `SecurityFilterChainIntegrationTest.S5`). Recommendation: Query accounts using `keycloakUserId` or resolve identity via `preferred_username` and `AuthenticatedUser.username()`. Add integration tests exercising realistic Keycloak tokens.

**service/impls/UserServiceImpl.java**
- [Critical][logging] `service/impls/UserServiceImpl.java:43`: Method annotation `@LogPerformance(title = "Register user", logInput = true)` triggers `LoggerAspect` from common-logging to print `RegisterRequest.toString()` at INFO level whenever execution time exceeds 50ms. User registration makes multiple HTTP calls to Keycloak, consistently exceeding this limit. This writes plaintext passwords, email addresses, and phone numbers directly into standard logs. Recommendation: Remove `logInput = true`, or add sensitive field redaction to the `stringify` utility in common-logging.
- [Critical][security] `service/impls/UserServiceImpl.java:141-150,169-172`: Methods `createKeycloakUser` and `resolveRoles` accept roles directly from `request.getRoles()` via `extractRoles`. `KeycloakAdminClient.assignRealmRoles` does not sanitize incoming roles, and the database stores whatever enum names are supplied. This constitutes the primary vector for privilege escalation.
- [High][security] `service/impls/UserServiceImpl.java:88-94`: The `delete` operation performs only a local soft delete. It does not disable the account in Keycloak or terminate active user sessions. An account deleted by an administrator can continue authenticating and calling backend services because JWT evaluation relies strictly on token claims. Furthermore, soft deletion leaves `existsByUsername/Email/Phone` (filtered by `@SQLRestriction`) returning `false` while database and Keycloak unique constraints remain held. Subsequent registration attempts pass pre-checks but crash during database commit or Keycloak user creation. Recommendation: Account deletion must call `KeycloakAdminClient` to disable the user and invalidate active sessions. Restoration must re-enable the user in Keycloak.
- [Medium][error-handling] `service/impls/UserServiceImpl.java:49-55`: The compensating rollback block wraps only `userRepository.save()`. Because database flushes occur at transaction commit, errors during commit (such as concurrent unique constraint collisions) bypass the catch block without triggering `rollbackKeycloakUser`. This leaves orphaned user accounts in Keycloak. Recommendation: Use `saveAndFlush` inside the try block, or register compensating actions using Spring transaction synchronization (`afterCompletion` with status `ROLLBACK`).
- [Medium][error-handling] `service/impls/UserServiceImpl.java:141-150,224-229`: `KeycloakClientException` (an unchecked runtime exception outside the `BusinessException` hierarchy) is not declared in `ApiExceptionHandler`. Keycloak communication errors (network timeouts, duplicate usernames, password policy violations) leak as raw 500 responses. Recommendation: Catch and translate `KeycloakClientException` into specific `BusinessException` instances (409 Conflict, 400 Bad Request, or 503 Service Unavailable).
- [Medium][error-handling] `service/impls/UserServiceImpl.java:60-65,187-203`: The `update` method permits modifications to `email` and `phone` without re-validating uniqueness. Collisions throw `DataIntegrityViolationException`, returning generic 409 errors rather than structured `auth.*` error codes. Recommendation: Check `existsByEmail/Phone` excluding the current entity ID before saving.
- [Medium][pattern] `service/impls/UserServiceImpl.java:118-123` and `controller/UserController.java:76-82`: Method `findAllUsers` omits the fleet-wide `PageableConstant.MAX_PAGE_SIZE` boundary. Parameter `sortBy` accepts raw client input passed into `Sort.by`, throwing `IllegalArgumentException` and 500 responses on invalid property names. Recommendation: Constrain page size with `Math.min(size, MAX_PAGE_SIZE)`, enforce a whitelist of sortable properties, and map sort direction safely.
- [Low][error-handling] `service/impls/UserServiceImpl.java:174-180`: Method `rollbackKeycloakUser` catches exceptions with a comment reading "Log but don't throw", yet contains no logging invocation. Rollback failures leave zero diagnostic records. Recommendation: Add a `log.error` call recording the affected `keycloakUserId`.

**service/impls/RoleServiceImpl.java**
- [High][error-handling] `service/impls/RoleServiceImpl.java:68-75`: Method `getUserRoles` lacks `@Transactional(readOnly = true)`. Because `open-in-view: false` is configured, accessing the lazy collection `user.getRoles()` outside an active persistence context throws `LazyInitializationException`, causing `GET /api/v1/roles/users/{id}` to fail with 500. Recommendation: Add `@Transactional(readOnly = true)` or fetch roles eagerly within the query.
- [Low][clean-code] `service/impls/RoleServiceImpl.java:31-34`: Code uses `Optional.of(repo.findByName(...).orElseThrow(...))`. Wrapping `orElseThrow` inside `Optional.of` creates an API that never returns empty. Recommendation: Return the `Role` entity directly and throw exceptions on absence.
- [Low][clean-code] `service/impls/RoleServiceImpl.java:25-28`: Manual constructor injection is used while the rest of the codebase relies on `@RequiredArgsConstructor`.
- [Low][pattern] `repository/RoleRepository.java:18`: Method `findByNameIn(List<String>)` accepts strings for enum fields. While functional via Spring Data converters, it weakens compile-time type safety. Recommendation: Accept `Collection<RoleName>`.

**controller/AuthController.java, RoleController.java, UserController.java**
- [Medium][pattern]: All three controllers hardcode literal URL paths (`"/api/v1/auth"`, `"/api/v1/roles"`, `"/api/v1/users"`) rather than referencing `com.shop.common.core.constants.ApiPaths`. Additionally, controllers use `@PreAuthorize` alongside `@AuthenticationPrincipal Jwt` rather than the standard helper `AuthenticatedUser.requireCurrent()`. Using `@PreAuthorize` here gates ADMIN access for `/api/v1/users` and `/api/v1/roles` (since the gateway handles backoffice gating), which represents a deliberate design choice; however, parsing `jwt.getSubject()` directly leads to the `/me` lookup defect noted above. Recommendation: Standardize on `JwtClaimExtractor` and `AuthenticatedUser`.

**dto/request/*.java (Fleet-wide in auth-service)**
- [Low][pattern]: DTOs in auth-service use mutable Lombok `@Getter/@Setter` classes instead of Java records. Recommendation: Migrate DTOs to records to align with fleet standards.

**mapper/UserMapper.java**
- [Low][clean-code] `mapper/UserMapper.java:33-35`: Method `mergeToEntity` has no callers. The mapper injects `ModelMapper` for simple request mapping while `toResponse` uses manual assignment. Recommendation: Remove `mergeToEntity` and map entities manually.

**repository/UserRepository.java**
- [Low][clean-code] `repository/UserRepository.java:66-67`: Native query `findByIdIncludingDeleted` has no callers. Recommendation: Remove unused query or expose an administrative endpoint that uses it.
- [Positive]: Soft delete and restore operations annotated with `@Modifying` provide comprehensive Javadoc and follow soft-delete standards.

**resources/application.yml**
- [Low][security] `application.yml:14-16`: Fallbacks `${POSTGRES_PASSWORD:admin}` and `${POSTGRES_USER:admin}` embed weak credentials in committed source control. Recommendation: Remove default passwords from configuration files, requiring explicit environment variables.
- [Positive]: Production properties configure `ddl-auto: validate`, `open-in-view: false`, and restrictive public paths (`/api/v1/auth/**`), combined with graceful shutdown settings.

**resources/db/changelog/**
- [Positive]: All three changelog files define clean migrations: three default roles, explicit unique constraints, soft-delete columns, and auditing metadata. `db.changelog-master.yaml` documents the rationale for using absolute paths.

### 1.2 Test Suite Findings

**SecurityFilterChainIntegrationTest.java**
- [Positive]: This test suite runs real Spring Security filter chains across scenarios S1 through S8, documenting mock JwtDecoder configurations and method-level security paths in `BaseSecurityConfig`.
- [Medium][testing] `SecurityFilterChainIntegrationTest.java:174-185`: Scenario S5 hardcodes `sub="alice"`, masking the UUID identity lookup defect on `/me`. Recommendation: Configure test tokens with realistic UUID subject claims and valid `preferred_username` properties.

**AuthControllerTest, UserControllerTest, RoleControllerTest**
- [Medium][testing]: All three slice test suites configure `@AutoConfigureMockMvc(addFilters = false)`. Security filter chains and `@PreAuthorize` validations are disabled entirely. Tests lack negative authorization scenarios (such as verifying that standard users calling `/api/v1/users` receive 403 Forbidden) and omit validation for privilege escalation on registration. Recommendation: Enable filter chains in slice tests using `SecurityMockMvcRequestPostProcessors.jwt()` for ADMIN-gated routes.

**service/impls/*Test**
- [Positive]: Strong test coverage for Keycloak rollbacks, duplicate accounts, password mismatches, and soft-delete restoration without order dependencies. The only gap remains the missing test case for `roles=[ADMIN]` on registration.

### 1.3 Files Without Findings
`AuthServiceApplication.java`, `constant/RoleName.java`, `mapper/RoleMapper.java`, `service/AuthService.java`, `service/UserService.java`, `service/RoleService.java`, `service/impls/AuthServiceImpl.java`, `dto/request/{LoginRequest, ChangePasswordRequest, RefreshTokenRequest, RoleRequest}.java`, `dto/response/TokenResponse.java`, `entity/{User, Role}.java`, all database changelogs, and service unit tests.

---

## 2. FAVOURITE-SERVICE

### 2.1 Findings by File

**service/impls/FavouriteServiceImpl.java**
- [Medium][error-handling] `service/impls/FavouriteServiceImpl.java:48-58`: Method `create` executes an `existsBy...` query followed by `save`. Under concurrent requests, duplicate submissions trigger a database `DataIntegrityViolationException` on the partial unique index `idx_favourites_user_product_unique_active`. The central exception handler maps this to a generic conflict error rather than `FAV-6002 FAVOURITE_ALREADY_EXISTS`. Recommendation: Catch `DataIntegrityViolationException` around the save operation and throw `BusinessException.of(ErrorCode.FAVOURITE_ALREADY_EXISTS)`.
- [Medium][pattern] `service/impls/FavouriteServiceImpl.java:31-38`: The `favourites.user_id` column stores the Keycloak subject UUID (`AuthenticatedUser.requireCurrent().id()`), whereas auth-service stores local Hibernate-generated UUIDs. These disparate identifiers prevent joins between user and favourite records in backoffice reporting. Recommendation: Standardize across the fleet on Keycloak subject UUIDs as the universal user identifier.

**mapper/FavouriteMapper.java**
- [Low][dry] `mapper/FavouriteMapper.java:11-15`: Injects `ModelMapper` without using it. All field conversions use manual assignment. Recommendation: Remove the unused injection and make the mapper an unassisted Spring `@Component`.

**repository/FavouriteRepository.java**
- [Low][clean-code] `repository/FavouriteRepository.java:30`: Query method `findByUserIdAndProductId` has no callers. Recommendation: Remove unused method.
- [Positive]: Methods `findByIdAndUserId` and soft-delete queries enforce `AND f.userId = :userId` directly in SQL, preventing IDOR vulnerabilities at the query layer. Javadoc clearly explains why returning `NOT_FOUND` prevents resource existence leakage.

**resources/db/changelog/db.changelog-master.yaml**
- [Low][documentation]: Changelog uses `relativeToChangelogFile: true`, contradicting comments in auth-service stating that Liquibase 5.0 ignores this flag. Recommendation: Align configuration documentation across both modules.
- [Positive]: `changelog-001-initial-schema.yaml` implements partial unique indexes with clear inline documentation, auditing metadata, and index coverage on `user_id`.

**controller/FavouriteController.java**
- [Positive]: Full compliance with platform guidelines: references `ApiPaths.FAVOURITES`, enforces `PageableConstant.MAX_PAGE_SIZE`, parses `AuthenticatedUser.requireCurrent().id()` defensively with 401 fallbacks, avoids redundant `@PreAuthorize`, uses Java records, and documents design choices.

**resources/application.yml**
- [Positive]: Configures empty public paths (`public-paths: []`), `ddl-auto: validate`, and environment variable placeholders.
- [Low][logging]: Core write operations omit performance logging annotations. Recommendation: Add `@LogPerformance(logInput = false)` to creation and deletion endpoints.

### 2.2 Files Without Findings
`FavouriteServiceApplication.java`, `dto/request/FavouriteCreateRequest.java`, `dto/response/FavouriteResponse.java`, `entity/Favourite.java`, `service/FavouriteService.java`, `config/TestLiquibaseConfig.java`, `FavouriteControllerTest.java`, `FavouriteServiceImplTest.java`, and `FavouriteRepositoryTest.java`.

---

## 3. GATEWAY-SERVICE

### 3.1 Findings by File

**filter/ClientIpResolver.java and filter/AdminIpAllowlistFilter.java**
- [High][security] `filter/ClientIpResolver.java:22-28` and `filter/AdminIpAllowlistFilter.java:58-75`: The resolver reads the first element in client-supplied `X-Forwarded-For` headers under the assumption that an edge proxy overwrites the header. When operating behind appending reverse proxies (such as Nginx `$proxy_add_x_forwarded_for`) or when reachable directly, callers can supply `X-Forwarded-For: <trusted-cidr-ip>` to bypass the deny-by-default administrative IP filter. Setting `server.forward-headers-strategy: framework` does not sanitize raw headers accessed via `request.getHeaders()`. Recommendation: Resolve client IPs based on trusted proxy hop counts (`XForwardedRemoteAddressResolver`, matching `RateLimitKeyResolver`).
- [Low][clean-code] `filter/ClientIpResolver.java:43-49`: Method `stripPort` does not handle IPv6 bracket notation (e.g. `[2001:db8::1]:4711`), causing valid IPv6 addresses with ports to be rejected as malformed. Recommendation: Use standard RFC-compliant parsing for IPv6 address and port pairs.

**config/SecurityConfig.java and resources/application.yml**
- [Medium][security] `application.yml:38-39` and `config/SecurityConfig.java:59`: CORS configuration pairs wildcard allowed origins (`allowed-origin-patterns: "*"`) with `setAllowCredentials(true)`. The gateway mirrors arbitrary request origins with `Access-Control-Allow-Credentials: true`. While Bearer tokens mitigate direct risk today, this configuration becomes vulnerable if cookies or ambient credentials are introduced. Recommendation: Bind allowed origins to explicit environment variables or disable credential sharing.

**ratelimit/RateLimitFilter.java (Bucket4j Edge)**
- [Medium][performance] `ratelimit/.../RateLimitFilter.java:49,75-77`: In-memory `ConcurrentHashMap<String, Bucket>` lacks an eviction policy. Because bucket keys are derived from client addresses, rotating IP headers causes map memory consumption to grow indefinitely. Recommendation: Replace with a bounded Caffeine cache configured with `expireAfterAccess` eviction.
- [Medium][solid]: Rate limiting is divided across three overlapping tiers: (1) Redis route-level rate limiting in `RoutesConfig`, (2) `GlobalRateLimitFilter` backed by Redis, and (3) Bucket4j edge rate limiting. This architecture introduces operational overhead while using divergent IP resolution mechanisms (trusted proxy hops in tier 1 versus raw header parsing in tier 3). Recommendation: Consolidate rate limiting and standardize on a single resolver.
- [Low][clean-code] `ratelimit/GlobalRateLimitFilter.java:50-54`: HTTP 429 rejections invoke `setComplete()` with an empty response body, violating the structured API envelope contract enforced by `GatewayErrorResponseWriter`. Recommendation: Use `GatewayErrorResponseWriter` for rate limit rejections.

**config/GatewayProperties.java**
- [Low][clean-code] `config/GatewayProperties.java:14-15`: Property `keycloakIssuerUri` is bound from configuration but never referenced; the JWT decoder uses standard Spring Security properties. Recommendation: Remove unused property or wire it explicitly.

**config/SecurityConfig.java (Documentation)**
- [Low][documentation]: When enabled, `AdminIpAllowlistFilter` restricts Prometheus scraping endpoints and storefront routes for external IPs, but deployment runbooks do not document the requirement to include Prometheus scrapers in CIDR allowlists. Recommendation: Document scraper allowlist requirements in application configuration comments.

**Test Suite Cleanliness**
- [Low][clean-code] `WebFluxRouteTests.java:3,20`: Redundant duplicate import of `WireMockServer`.
- [Low][testing] `RateLimitPropertiesTest.java` and `RateLimitFilterTest.java:198-204`: Property default tests construct records manually rather than validating bindings from `application.yml`. Recommendation: Add automated tests verifying configuration property bindings directly from YAML.

**docker/keycloak/import/ecommerce-realm.json**
- [High][security] `docker/keycloak/import/ecommerce-realm.json:64,77,90,103`: Four client secrets are committed with the value `changeme` in tracked files. Repository access allows obtaining confidential client secrets and acquiring service tokens with `SERVICE` realm roles. Recommendation: Inject client secrets dynamically from environment variables during deployment and rotate exposed secrets.

### 3.2 Files Without Findings
`GatewayServiceApplication.java`, `config/EdgeFiltersConfiguration.java`, `config/RouteTargetProperties.java`, `config/RoutesConfig.java`, `constant/ServiceRoute.java`, `routing/ApiPaths.java`, `filter/AdminIpAllowlistProperties.java`, `filter/EdgeRateLimitProperties.java`, `filter/FilterOrder.java`, `filter/GatewayErrorResponseWriter.java`, `filter/RequestPathGuard.java`, `filter/AdminRoleGateFilter.java`, `ratelimit/RateLimitConfiguration.java`, `ratelimit/RateLimitKeyResolver.java`, `ratelimit/GlobalRateLimitProperties.java`, `resources/logback-spring.xml`, and the associated WebFlux test suites.

---

## 4. SUMMARY

### 4.1 Findings Matrix: Severity by Category

| Category | Critical | High | Medium | Low | Total |
|---|---|---|---|---|---|
| Security | 2 (Role escalation, password logging) | 4 (Identity mismatch, Keycloak soft-delete sync, IP spoofing, hardcoded secrets) | 2 (Unverified email flag, CORS with credentials) | 1 (Default database passwords) | 9 |
| Error Handling | 0 | 1 (LazyInitializationException) | 4 (Unchecked commit flush, unmapped Keycloak exceptions, unvalidated updates, race error mapping) | 1 (Silent exception catch) | 6 |
| Logging | 1 (Plaintext password logging) | 0 | 0 | 1 (Missing performance logs in favourite-service) | 2 |
| Pattern Compliance | 0 | 0 | 3 (Non-standard authorization, unconstrained pagination, conflicting identity formats) | 2 (Mutable DTOs, loose repository types) | 5 |
| Performance | 0 | 0 | 1 (Unbounded rate-limit bucket cache) | 0 | 1 |
| Clean Code | 0 | 0 | 0 | 10 (Field naming, dead methods, nested optionals, IPv6 formatting, dead config, empty 429) | 10 |
| DRY and SOLID | 0 | 0 | 1 (Overlapping rate-limiting tiers) | 1 (Unused ModelMapper bean) | 2 |
| Documentation | 0 | 0 | 0 | 2 (Contradictory Liquibase comments, missing scraper documentation) | 2 |
| Testing | 0 | 0 | 2 (Security filters disabled in slice tests, hardcoded test identities) | 1 (Unverified YAML property bindings) | 3 |
| **Total** | **3** | **5** | **13** | **19** | **40** |

*Note: The 3 Critical findings reflect two underlying defects: unauthenticated privilege escalation via registration requests, and plaintext password logging in performance interceptors.*

### 4.2 Pattern Compliance Assessment

- **auth-service:** Demonstrates solid adherence to auditing, soft delete, and database migration guidelines, but deviates on identity management and authorization conventions. Key concerns include unvalidated role assignment on registration, plaintext password logging, and identity lookup mismatches on self-service routes.
- **favourite-service:** Serves as the cleanest reference implementation across all three modules. Employs Java records, shared API path constants, pagination bounds, contextual user extraction, and ownership-scoped queries.
- **gateway-service:** Delivers thorough reactive filtering, clean route configurations, defensive path normalization, and comprehensive integration testing. Opportunities for improvement include simplifying the three rate-limiting layers, hardening client IP resolution against header spoofing, and securing CORS settings.
