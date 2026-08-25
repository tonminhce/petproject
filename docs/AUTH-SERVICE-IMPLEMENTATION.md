# Auth Service Implementation Plan

> Scope: Build `auth-service` from scratch, porting patterns from `hoangtien2k3/ecommerce-microservices` reference. User identity is delegated to Keycloak — auth-service only stores a shadow copy (username, email, phone, role mapping) for fast lookups.

**Total: ~28 files** across 9 packages + Liquibase changelog + tests.

> **STATUS (2026-08-25)** — core implemented: `AuthController` (sign-up / login /
> refresh / logout), `UserController` (me / profile / soft-delete / restore),
> entities `User`+`Role` (UUID, soft-delete via `SoftDeletable`), repositories,
> DTOs, ModelMapper, `AuthServiceImpl` / `UserServiceImpl` / `RoleServiceImpl`,
> Liquibase (master + 2 changesets + seed). **Deviations from this plan**:
> login is **ROPC** (`POST /api/v1/auth/login`) — the SSO flow
> (`GET /login`→302, `/callback`, `/session`), `RoleController`,
> `SecurityConfig`, `SsoProperties`, `SsoSessionStore`, per-service
> `application.yml` and tests are **not yet done**. `KeycloakAuthClient` was
> refactored to `KeycloakTokenClient` + `KeycloakAdminClient` (RestClient).
> This doc remains the target design; current reality is summarized in
> [`SERVICE-CATALOG.md §1`](./SERVICE-CATALOG.md).

---

## Phase 0 — Pre-flight checklist

Before writing code, confirm these from the common-lib port:

- [x] `utils/common-core` has `BusinessException`, `ErrorCode`, `ApiResponse`
- [x] `utils/common-keycloak` has `KeycloakTokenClient`, `KeycloakAdminClient`, `KeycloakProperties`, `KeycloakTokenResponse` (refactored from `KeycloakAuthClient` in commit `2c6c35c`)
- [x] `utils/common-spring` has `ApiExceptionHandler`, `I18nAutoConfiguration`, message bundles
- [ ] Postgres running (see `docker-compose.yml`)
- [ ] Keycloak running with realm `ecommerce` configured

---

## Phase 1 — POM + Application class (2 files)

### 1.1 `auth-service/pom.xml`

Add these dependencies to your existing POM (which already has `common-spring`):

```xml
<!-- JPA + PostgreSQL -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Liquibase for schema migrations -->
<dependency>
    <groupId>org.liquibase</groupId>
    <artifactId>liquibase-core</artifactId>
</dependency>

<!-- ModelMapper for entity <-> DTO -->
<dependency>
    <groupId>org.modelmapper</groupId>
    <artifactId>modelmapper</artifactId>
</dependency>

<!-- Lombok (compile-time only) -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Test -->
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

### 1.2 `auth-service/src/main/java/com/shop/authservice/AuthServiceApplication.java`

Already exists — just verify it has:
- `@SpringBootApplication`
- `@EnableJpaAuditing` (if you plan to use auditing)
- `main(String[] args)` running `SpringApplication.run(...)`

---

## Phase 2 — Configuration (3 files)

### 2.1 `auth-service/src/main/resources/application.yml`

Copy from reference — sets port 8088, datasource, Liquibase, Keycloak, i18n, CORS. Your existing `common-spring/application.yml` already provides defaults via inheritance.

### 2.2 `src/main/java/.../config/SecurityConfig.java`

Defines the auth-service-specific `SecurityFilterChain`:

- **PUBLIC endpoints** (no JWT required):
  - `/v3/api-docs/**`, `/swagger-ui/**`, `/actuator/**`
  - `ApiPaths.AUTH + "/signup"` (self-registration)
  - `ApiPaths.AUTH + "/login"` (SSO redirect)
  - `ApiPaths.AUTH + "/callback"` (Keycloak callback)
  - `ApiPaths.AUTH + "/session"` (ticket exchange)
  - `ApiPaths.AUTH + "/refresh"` (token refresh)
  - `ApiPaths.AUTH + "/logout"` (logout)
- All others require JWT (`oauth2ResourceServer().jwt(...)`)
- `@EnableMethodSecurity` for `@PreAuthorize` on individual methods
- Inject `JwtAuthenticationConverter` (from common-security autoConfig)

### 2.3 `src/main/java/.../config/SsoProperties.java`

```java
@ConfigurationProperties(prefix = "keycloak.sso")
public class SsoProperties {
    private String backendCallbackUrl;          // http://api.ecommerce.local/api/v1/auth/callback
    private List<String> allowedRedirectUris;   // anti open-redirect
    private String defaultFrontendRedirect;
    private String scope = "openid profile email";
    private long stateTtlSeconds = 300;
    private long ticketTtlSeconds = 60;
}
```

---

## Phase 3 — Entities (3 files)

### 3.1 `entity/RoleName.java`

```java
public enum RoleName { USER, PM, ADMIN }
```

### 3.2 `entity/Role.java`

- `@Entity @Table(name = "roles")`
- `Long id` (PK, identity)
- `RoleName name` (`@Enumerated(STRING)`, `@NaturalId`, unique, length 60)
- Lombok: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @ToString`

### 3.3 `entity/User.java`

- `@Entity @Table(name = "users")` with 3 unique constraints: `user_name`, `email`, `phone_number`
- Fields:
  - `Long id` (PK, `@GeneratedValue(IDENTITY)`)
  - `String fullName` (3-100 chars)
  - `String username` (3-100, `@NaturalId`)
  - `String email` (email validation, `@NaturalId`)
  - `String gender` (not blank)
  - `String phone` (Vietnam phone regex `^\+84[0-9]{9,10}$|^0[0-9]{9,10}$`)
  - `String avatar` (HTTP/HTTPS URL regex)
  - `String keycloakUserId` (UUID from Keycloak admin API, unique)
  - `Set<Role> roles` (`@ManyToMany(LAZY)`, name = `user_role` join table)

---

## Phase 4 — Repositories (2 files)

### 4.1 `repository/RoleRepository.java`

```java
public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByName(RoleName name);
    
    @Query("SELECT u.roles FROM User u WHERE u.id = :id")
    List<Role> findByUserId(@Param("id") Long id);
}
```

### 4.2 `repository/UserRepository.java`

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
}
```

---

## Phase 5 — Liquibase changelog (2 files)

### 5.1 `src/main/resources/db/changelog/db.changelog-master.yaml`

Master changelog that includes all individual changesets.

### 5.2 `src/main/resources/db/changelog/changeset/001-create-users-and-roles.yaml`

Creates:
- `users` table with columns matching `User` entity
- `roles` table with columns matching `Role` entity
- `user_role` join table (user_id, role_id)
- Pre-populate `roles` with `USER`, `PM`, `ADMIN` rows

**Acceptance**: `mvn spring-boot:run` starts without `ddl-auto: validate` errors.

---

## Phase 6 — Constants (1 file)

### 6.1 `constant/RoleConstant.java`

```java
public final class RoleConstant {
    public static final String ROLE_USER = "USER";
    public static final String ROLE_PM = "PM";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String DEFAULT_ROLE = ROLE_USER;
    public static final int ROLE_NAME_MIN_LENGTH = 2;
    public static final int ROLE_NAME_MAX_LENGTH = 60;
    private RoleConstant() {}
}
```

---

## Phase 7 — DTOs (6 files)

### 7.1 `dto/request/RegisterRequest.java`

Lombok `@Getter @Setter`. Fields with Bean Validation:
- `fullName` (6-50)
- `username` (6-50)
- `password` (8-50, must contain upper/lower/digit)
- `email` (regex)
- `gender` (not blank)
- `phone` (Vietnam phone regex)
- `avatar` (URL regex, optional)
- `Set<String> roles` (optional — defaults to USER)

### 7.2 `dto/request/LoginRequest.java`

- `username` (not blank)
- `password` (not blank)

### 7.3 `dto/request/ChangePasswordRequest.java`

- `oldPassword`, `newPassword`, `confirmPassword`

(Note: reference throws `auth.password.managed.by.keycloak` — Keycloak handles password changes.)

### 7.4 `dto/request/UpdateUserRequest.java`

Same fields as RegisterRequest but ALL optional (no `@NotBlank`). Used for PATCH semantics.

### 7.5 `dto/request/RefreshTokenRequest.java`

- `refreshToken` (not blank)

### 7.6 `dto/response/UserResponse.java`

```java
@Builder @Getter @Setter @AllArgsConstructor
public class UserResponse {
    private Long id;
    private String fullname;
    private String username;
    private String email;
    private String gender;
    private String phone;
    private String avatar;
}
```

---

## Phase 8 — Mappers (2 files)

### 8.1 `mapper/UserMapper.java`

```java
@Component
public class UserMapper {
    private final ModelMapper modelMapper;  // from common-spring ModelMapperAutoConfiguration
    
    public UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .fullname(user.getFullName())
                .username(user.getUsername())
                // ... map all fields
                .build();
    }
    
    public User toEntity(RegisterRequest request) {
        return modelMapper.map(request, User.class);
    }
}
```

### 8.2 `mapper/RoleMapper.java`

Static utility:

```java
public final class RoleMapper {
    public static RoleName toRoleName(String roleName) {
        try {
            return RoleName.valueOf(roleName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BusinessException.badRequest("auth.unsupported.role", roleName);
        }
    }
}
```

---

## Phase 9 — Services (4 files)

### 9.1 `service/UserService.java` (interface)

Methods: `register`, `update`, `changePassword`, `delete`, `findById`, `findByUsername`, `findAllUsers`, `existsByUsername`, `existsByEmail`, `existsByPhoneNumber`.

### 9.2 `service/UserServiceImpl.java`

Key implementation details:
- `@Transactional @LogPerformance` on `register`
- Check uniqueness BEFORE calling Keycloak (avoid orphan Keycloak users)
- Call `keycloakAdminClient.createUser(...)` (from `common-keycloak`) to get Keycloak UUID
- Save local shadow copy with `keycloakUserId`
- On any RuntimeException → rollback: `keycloakAuthClient.deleteUser(keycloakUserId)`
- `changePassword` throws `BusinessException.badRequest("auth.password.managed.by.keycloak")`

### 9.3 `service/RoleService.java` (interface) + `service/RoleServiceImpl.java`

- `findByName(RoleName)` → `Optional<Role>`
- `assignRole(userId, roleName)` → boolean (false if already has)
- `revokeRole(userId, roleName)` → boolean (false if not present)
- `getUserRoles(userId)` → `List<String>`

### 9.4 `service/SsoSessionStore.java`

In-memory `ConcurrentHashMap`-based store for SSO handshake secrets:
- `loginStates` — CSRF guard for `/login → /callback` flow (TTL: 300s)
- `tickets` — single-use handle for `/callback → /session` flow (TTL: 60s)

Methods: `createLoginState`, `consumeLoginState`, `storeTokens`, `consumeTokens`. **Single-replica only** — comment that scale-out would need Redis.

---

## Phase 10 — Controllers (3 files)

### 10.1 `controller/AuthController.java`

`@RequestMapping("/api/v1/auth")` — public endpoints (no `@PreAuthorize`).

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/signup` | Self-registration via Keycloak admin API |
| `GET`  | `/login` | Backend-mediated SSO: 302 to Keycloak authorize endpoint with `state` |
| `GET`  | `/callback` | Receives `code` + `state` from Keycloak → exchanges for tokens → returns ticket |
| `GET`  | `/session` | Frontend swaps single-use `ticket` for tokens |
| `POST` | `/refresh` | Refresh access token via refresh_token |
| `POST` | `/logout` | Logout via refresh_token |

Inject: `UserService`, `KeycloakTokenClient` + `KeycloakAdminClient`, `KeycloakProperties`, `SsoProperties`, `SsoSessionStore`.

**SSO flow** (the interesting part):
```
Browser → GET /api/v1/auth/login?redirect_uri=...
   ↓ auth-service creates state=ABC123, frontendRedirect="..."
   ↓ 302 to Keycloak /protocol/openid-connect/auth?...
Browser → Keycloak login page
   ↓ user enters credentials
Keycloak → 302 to /api/v1/auth/callback?code=XYZ&state=ABC123
   ↓ auth-service validates state, consumes it
   ↓ exchanges code for tokens via KeycloakTokenClient.exchangeAuthorizationCode
   ↓ creates ticket=DEF456, stores tokens
   ↓ 302 to frontendRedirect?ticket=DEF456
Frontend → GET /api/v1/auth/session?ticket=DEF456
   ↓ consumes ticket, returns tokens as JSON
```

### 10.2 `controller/UserController.java`

`@RequestMapping(ApiPaths.USERS)` — protected with `@PreAuthorize`.

| Method | Path | Auth |
|--------|------|------|
| `PUT`    | `/{id}` | USER |
| `PUT`    | `/me/password` | USER |
| `DELETE` | `/{id}` | USER or ADMIN |
| `GET`    | `?username=` | USER or ADMIN |
| `GET`    | `/{id}` | USER or ADMIN |
| `GET`    | `/all` | ADMIN |
| `GET`    | `/me` | authenticated (from JWT) |

### 10.3 `controller/RoleController.java`

`@RequestMapping(ApiPaths.ROLES)` — all ADMIN only.

| Method | Path |
|--------|------|
| `POST` | `/users/{userId}/assign` (body: roleName) |
| `POST` | `/users/{userId}/revoke` (body: roleName) |
| `GET`  | `/users/{userId}` |

---

## Phase 11 — Tests (5 files, optional but recommended)

| Test class | Coverage |
|-----------|----------|
| `AuthControllerTest` | @WebMvcTest — signup, SSO redirect, token endpoints |
| `UserControllerTest` | @WebMvcTest with `@MockBean UserService` |
| `RoleControllerTest` | @WebMvcTest with `@MockBean RoleService` |
| `UserServiceImplTest` | Mockito — uniqueness checks, Keycloak rollback |
| `RoleServiceImplTest` | Mockito — assign/revoke edge cases |

Reference has them in `src/test/java/.../controller/` and `src/test/java/.../service/`.

---

## Phase 12 — Message bundle (optional override)

If auth-service needs auth-specific messages, override at:
`auth-service/src/main/resources/messages/messages_vi.properties`

The common-spring bundle already has:
- `auth.username.exists`, `auth.email.exists`, `auth.phone.exists`
- `auth.user.not.found.*`, `auth.role.not.found.*`
- `auth.password.managed.by.keycloak`

No new keys needed unless you add custom flows.

---

## Task order (recommended)

```
Phase 1 → 2.1 → 3 → 4 → 5 → 7 → 8 → 9 (UserService only) → 10.1 (signup only)
   ↓ smoke test: mvn spring-boot:run, register user via Postman
Phase 2.2 → 10.1 (SSO) → 2.3 → 9.4 → 10.1 (full)
   ↓ smoke test: SSO flow via browser
Phase 6 → 8 → 9.3 → 10.3
   ↓ smoke test: assign role
Phase 9.2 (update/delete) → 10.2
   ↓ smoke test: CRUD user
Phase 11 — tests
```

---

## Verification checklist

- [ ] `mvn clean compile` succeeds
- [ ] `mvn spring-boot:run` starts on port 8088
- [ ] `curl localhost:8088/actuator/health` returns `{"status":"UP"}`
- [ ] Postgres has `users`, `roles`, `user_role` tables after Liquibase runs
- [ ] `POST /api/v1/auth/signup` creates user in Keycloak + DB
- [ ] `GET /api/v1/auth/login` returns 302 to Keycloak
- [ ] Browser SSO flow completes, frontend gets tokens
- [ ] `GET /api/v1/users/me` with valid JWT returns current user
- [ ] `POST /api/v1/roles/users/{id}/assign` works for ADMIN

---

## Reference files

All file paths in this document map to the reference repo:
`/tmp/reference-ecommerce/auth-service/src/main/java/com/ecommerce/authservice/...`

Open those side-by-side while implementing. The reference is byte-stable (only minor refactor for your `com.shop.authservice` package vs `com.ecommerce.authservice`).