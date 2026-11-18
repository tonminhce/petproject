# Security Source Review — run-20261118-a2b6e6fc

**Repository:** `/Users/tonminh-mac/IdeaProjects/untitled5`
**Git revision:** `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`
**Reviewer role:** A10 (Security Engineer)
**Scope:** auth-service (42 main + 13 test Java files, read completely), fleet-wide security configuration, docker-compose / Keycloak realm, env templates, CI secret handling.
**Method:** source read + `git ls-files` / `git rev-parse` / `git check-ignore` / `grep` metrics. README, `HANDOFF.md`, `FINDING.md`, `docs/**` were **not** treated as evidence.

**Secret policy for this document:** no real secret value is reproduced anywhere below. Every credential is described by *kind*, *location* and *shape* only (e.g. "literal default password, 9 chars, well-known").

---

## 0. Executive verdict

| Question | Verdict | Primary evidence |
|---|---|---|
| Custom auth service issuing JWTs, or Keycloak-issued tokens? | **Keycloak-issued.** auth-service is a thin proxy/mirror; it signs nothing. | `auth-service/.../AuthServiceImpl.java:14-40`, `utils/common-keycloak/.../KeycloakTokenClient.java:53-83` |
| Default-deny filter chain? | **YES** for every servlet service and for the reactive gateway. | `utils/common-security/.../BaseSecurityConfig.java:72-84`, `gateway-service/.../SecurityConfig.java:44-47` |
| Confirmed IDOR candidates | **1 confirmed** (cross-service horizontal), **1 identity-key defect** that breaks the self-service isolation surface | SEC-02, SEC-01 |
| Secret-leak candidates | **3 confirmed** (committed literal admin password, committed literal test password, credential disclosure in tracked docs) | SEC-03, SEC-28 |
| Raw SQL concatenation / SSRF / path traversal | **None found.** All queries are parameterised; all outbound URLs are configuration-derived. | §7 |
| CI secret handling | **No secrets are used in CI**, but there is **no SCA/SAST/secret-scan gate** and the OWASP plugin is bound to a phase CI never reaches. | SEC-17 |

Severity counts over 30 numbered defects: **HIGH 3 · MEDIUM 10 · LOW 16 · INFO 1**.

---

## 1. FILE INVENTORY

All blobs verified with `git rev-parse HEAD:<path>` at revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`.
"Ranges read" states the line span actually read with the `read` tool (or `bash` for metrics-only files, marked *[metrics/grep]*).

### 1.1 auth-service — main (42 Java files, ALL read completely)

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `auth-service/src/main/java/com/shop/authservice/AuthServiceApplication.java` | `0913e173c73823c4ba2b2d7dde3f9e22dced3b43` | 13 | 1-13 (full) |
| `auth-service/src/main/java/com/shop/authservice/constant/RoleName.java` | `7f6755ceede46b502725e27f88c7264c60283510` | 19 | 1-19 (full) |
| `auth-service/src/main/java/com/shop/authservice/controller/AddressController.java` | `b210259b76b3908ba39dcacdaf2aa34494a65356` | 67 | 1-67 (full) |
| `auth-service/src/main/java/com/shop/authservice/controller/AuthController.java` | `d22b67c74b6e50c3a9bc65a51f85520de86a31e5` | 63 | 1-63 (full) |
| `auth-service/src/main/java/com/shop/authservice/controller/RoleController.java` | `86aa4e9c1ae8c1c585827b26ee5f883a33a92555` | 44 | 1-44 (full) |
| `auth-service/src/main/java/com/shop/authservice/controller/UserController.java` | `69b6364c269fe0571028aad53138f124e407a9ef` | 85 | 1-85 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/AddressCreateRequest.java` | `540c62860eafe79283ff8df0a0c4c85843a6d827` | 33 | 1-33 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/AddressUpdateRequest.java` | `56f7788a61b7e0afd74eee42db0f261f0f5c1247` | 33 | 1-33 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/ChangePasswordRequest.java` | `a71cfebddcf3aec4f7cc922b0870f11936f9ee2b` | 20 | 1-20 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/ForgotPasswordRequest.java` | `a8bdd0c2b55e514c3d0f175a9f8ddd957f713f83` | 10 | 1-10 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/LoginRequest.java` | `51d3c8a470ff8e0a1d50dc29a95cc084df400426` | 12 | 1-12 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/RefreshTokenRequest.java` | `0967424a9c9308b90e6a5aef2fa8338dc4b2a3fc` | 9 | 1-9 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/RegisterRequest.java` | `4602ef8675f9b85a4c7c25c2471fa93bc1a93645` | 49 | 1-49 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/ResetPasswordRequest.java` | `cd501a9d1f93c76e59dbe2e744d15142b269f5fc` | 14 | 1-14 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/RoleRequest.java` | `f9e3194202b12f638e55a7b9d1ba33df03d21a4c` | 9 | 1-9 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/request/UpdateUserRequest.java` | `df66970adea41c52b6436aff1bda7a62aad6be98` | 24 | 1-24 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/response/AddressResponse.java` | `05813bc4714996c0fe12049f82ed12bfb97ac28a` | 19 | 1-19 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/response/TokenResponse.java` | `2ac41b4ed63402e455e99160e3a29bb313d16dee` | 18 | 1-18 (full) |
| `auth-service/src/main/java/com/shop/authservice/dto/response/UserResponse.java` | `37b2776d3e81ecbc905187b23408976d0043acdc` | 21 | 1-21 (full) |
| `auth-service/src/main/java/com/shop/authservice/entity/PasswordResetToken.java` | `9e743714f5ae57f9ed087a93deafedb1f567c3d2` | 65 | 1-65 (full) |
| `auth-service/src/main/java/com/shop/authservice/entity/Role.java` | `6318edb0bb4867436a51dde984d4b5cf73386bbc` | 28 | 1-28 (full) |
| `auth-service/src/main/java/com/shop/authservice/entity/User.java` | `720d04f746bc151e07af6519aec0284930cce2d7` | 73 | 1-73 (full) |
| `auth-service/src/main/java/com/shop/authservice/entity/UserAddress.java` | `1c48465b4f114bf29169ce5a60fb9632a9cb4028` | 64 | 1-64 (full) |
| `auth-service/src/main/java/com/shop/authservice/exception/AuthExceptionHandler.java` | `351ac1e0330246915764d351d99cee371f02d838` | 30 | 1-30 (full) |
| `auth-service/src/main/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheck.java` | `b448048079b9587db54f931e1eec30952da39d20` | 71 | 1-71 (full) |
| `auth-service/src/main/java/com/shop/authservice/mapper/RoleMapper.java` | `90aba3267c32e0016a64d6098530c1952a9c2529` | 17 | 1-17 (full) |
| `auth-service/src/main/java/com/shop/authservice/mapper/UserMapper.java` | `200cc8244c27e133cd6dd509c2373005f616bb61` | 36 | 1-36 (full) |
| `auth-service/src/main/java/com/shop/authservice/repository/PasswordResetTokenRepository.java` | `8338265f6fe535e69da2cdcb0b777f30475585fa` | 35 | 1-35 (full) |
| `auth-service/src/main/java/com/shop/authservice/repository/RoleRepository.java` | `939dacfc18c8f05f7f44ca37a4228f264381c497` | 19 | 1-19 (full) |
| `auth-service/src/main/java/com/shop/authservice/repository/UserAddressRepository.java` | `dda8b2c52a5421a488f604e1ffd60b30b13e6c2f` | 22 | 1-22 (full) |
| `auth-service/src/main/java/com/shop/authservice/repository/UserRepository.java` | `25363f0a4dfabaa5c541240846acf7539ec59d0e` | 61 | 1-61 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/AddressService.java` | `1b21b66f3e363e352dc8979962ea1c54f18e42a7` | 21 | 1-21 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/AuthService.java` | `673248176290fe0c376299d319e05a09448aa781` | 12 | 1-12 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/PasswordResetEmailSender.java` | `28136d053ca96756ff8f5770e16bf422fb824923` | 6 | 1-6 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/RoleService.java` | `48845e188be5ca206ce7c54cedcbab65567487bc` | 18 | 1-18 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/UserService.java` | `9587044cd8d7add0fb036f6822d5d9cc030cf566` | 41 | 1-41 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/AddressServiceImpl.java` | `30ffa2b179fc05c7d27862e2936ac40269f99904` | 133 | 1-133 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/AuthServiceImpl.java` | `45f7a87130b9ad32e59a5eadb17bde802e479559` | 41 | 1-41 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/RoleServiceImpl.java` | `8e67f09a4e54dbb8c62187dcb278e68841482b07` | 167 | 1-167 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/SmtpPasswordResetEmailSender.java` | `9bc1b0c499685d2e23d0c858fddbc4a0001770b8` | 76 | 1-76 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/UnconfiguredPasswordResetEmailSender.java` | `dbf2119f02d9b021632b01d20e39a5714f7cabd1` | 27 | 1-27 (full) |
| `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java` | `b29ba668a7e3a18d462083ed36725fbbd5aa72cf` | 428 | 1-428 (full) |

### 1.2 auth-service — resources (config + Liquibase)

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `auth-service/src/main/resources/application.yml` | `62a7a0e6b81c068be96ed9bd764381e55be24f43` | 74 | 1-74 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/001-create-users-and-roles.yaml` | `238955d5953504e56ad7427fa62e15b677b8f652` | 115 | 1-115 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/002-add-soft-delete.yaml` | `5d89a4879b2ae788f3fc6952739f36f2cba33182` | 37 | 1-37 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/003-partial-unique-users.yaml` | `a0ee0e82b86b020859413a2ef118acdd7e4dfa01` | 57 | 1-57 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/004-password-reset-and-addresses.yaml` | `c9c7c3263d3c702a20c2e2ac1e9ea8043d75d5b9` | 116 | 1-116 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/005-password-reset-token-version.yaml` | `316da3edfa82472462eb5b0872601f92b9960fb0` | 17 | 1-17 (full) |
| `auth-service/src/main/resources/db/changelog/changeset/006-align-roles-with-realm.yaml` | `d0ad713b8e98236bf9d29b7c05a9ffddc0cb3b8e` | 40 | 1-40 (full) |

### 1.3 auth-service — test (13 Java files, ALL read completely) + test resources

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `auth-service/src/main/resources/db/changelog/db.changelog-master.yaml` | `e09d1582df8e79a59cbfd121808abb9550416f6b` | 15 | 1-15 (full) |
| `auth-service/src/test/java/com/shop/authservice/constant/RoleCatalogueContractTest.java` | `99847bfa23f5bbf40a97776ad29cf1762872283c` | 109 | 1-109 (full) |
| `auth-service/src/test/java/com/shop/authservice/controller/AuthControllerTest.java` | `140c372cd2c22d38b9beb76f6bff5137d6ee1539` | 115 | 1-115 (full) |
| `auth-service/src/test/java/com/shop/authservice/controller/RoleControllerTest.java` | `66bf10289608c009198985edb7fccad814c2580c` | 79 | 1-79 (full) |
| `auth-service/src/test/java/com/shop/authservice/controller/UserControllerTest.java` | `d4157ac69bc3bacb5d94f95a087503900034bd76` | 97 | 1-97 (full) |
| `auth-service/src/test/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheckTest.java` | `854d8c0f419d4b861cbe0670d615cd185a575e5e` | 83 | 1-83 (full) |
| `auth-service/src/test/java/com/shop/authservice/repository/PasswordResetTokenConsumptionTest.java` | `105de730282435418309defba14045b11fbc1bf3` | 181 | 1-181 (full) |
| `auth-service/src/test/java/com/shop/authservice/repository/RoleCatalogueMigrationTest.java` | `7cbc4c2283ffc5713b2d79a4dec5d91b8a19b350` | 102 | 1-102 (full) |
| `auth-service/src/test/java/com/shop/authservice/security/SecurityFilterChainIntegrationTest.java` | `e8cb3071081ea7c6849e6675ba71597afe1a8cdc` | 221 | 1-221 (full) |
| `auth-service/src/test/java/com/shop/authservice/service/impls/AuthServiceImplTest.java` | `84166026353bdb9f0bd4c249966b313a92acf0e4` | 58 | 1-58 (full) |
| `auth-service/src/test/java/com/shop/authservice/service/impls/PasswordResetEmailSenderTest.java` | `1ec07ab21f939e540adeca891f680ab25f40fd24` | 88 | 1-88 (full) |
| `auth-service/src/test/java/com/shop/authservice/service/impls/RoleServiceImplTest.java` | `8aec74d74c13ea755f16397755a62d9d106679bf` | 288 | 1-288 (full) |
| `auth-service/src/test/java/com/shop/authservice/service/impls/UserServiceImplForgotPasswordLoggingTest.java` | `8a0510e2fcbdfa0cec5cdd7fcc3c993c011369e4` | 132 | 1-132 (full) |
| `auth-service/src/test/java/com/shop/authservice/service/impls/UserServiceImplTest.java` | `fbc636a4f88aa75e0cca06f6310b5edf65951ff9` | 579 | 1-579 (full) |
| `auth-service/src/test/resources/security-filter-chain-fixture.yml` | `392af3b59fa19c5da3fa68eb73ee62c3e5760f26` | 26 | 1-26 (full) |

### 1.4 common-security / common-keycloak / common-spring / common-core / common-logging

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `auth-service/src/test/resources/db/changelog/legacy-roles-changelog.yaml` | `7ac83ac135c839e3a19ee496a1188523b3246569` | 18 | 1-18 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java` | `1d0aeef381223e88adedf72a66cf3eaaeac38ef5` | 158 | 1-158 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/config/CorsAutoConfigurer.java` | `ef73b28ca0e1fe5eee08074ebd21d39780ecc853` | 66 | 1-66 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/config/LazyJwtDecoder.java` | `9efbbe5ef1d5f509ec63ce281780f4a8ffbbb8d5` | 96 | 1-96 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityAutoConfiguration.java` | `381e887a2d0163c0cf501711c388d70fc1a7fd43` | 40 | 1-40 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java` | `2fe5f1944cad06e60e20a5131bcf65e92631e6ca` | 161 | 1-161 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java` | `24068ee763b4bb5ed39d522f785906833673fb94` | 90 | 1-90 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/JwtClaimExtractor.java` | `98f30ae41c65b6382f437e4a6157af8be2d7b532` | 81 | 1-81 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/jwt/JwtRolesConverter.java` | `60e6d431471610b6b77d5051364aa5f2f94f5160` | 64 | 1-64 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/package-info.java` | `ad4324ed2483524817cf4e6b8388acf061c11d2e` | 6 | 1-6 (full) |
| `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java` | `c2b01d347476dc396517a85a35f28f410dcfd68a` | 68 | 1-68 (full) |
| `utils/common-security/pom.xml` | `9aaecaab3da24235159169fc4ea31db7bcdc59b0` | 75 | 1-75 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java` | `852942a8ba3b7a9c00d2e2969163a6ea07a85eb5` | 599 | 1-599 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java` | `c2f1a10c7128c7674fc4f2f15d84406986aec5b5` | 179 | 1-179 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java` | `6513991273bc2e8f77131425bdead72fe224a3fb` | 101 | 1-101 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakProperties.java` | `9e9094d99ecf3702dcf33eb5f87b3c1b95444f64` | 116 | 1-116 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/dto/KeycloakTokenResponse.java` | `c22271447bfd5cc210dca21d7cd987f99a1d2814` | 17 | 1-17 (full) |
| `utils/common-keycloak/src/main/java/com/shop/common/keycloak/exception/KeycloakClientException.java` | `b44bac609371388395536a33a6cf406b6325a284` | 26 | 1-26 (full) |
| `utils/common-keycloak/pom.xml` | `9fa954f51247ee49f7af86c10bea553a7bb29b38` | 96 | 1-96 (full) |

### 1.5 gateway-service

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `utils/common-spring/src/main/resources/application.yml` | `6987d30b9b798384585d4db41a36decd96c6eb39` | 261 | 1-261 (full) |
| `utils/common-spring/src/main/java/com/shop/common/spring/web/exception/ApiExceptionHandler.java` | `3d175e0d84bc75c445dee3393d1078f747ed2fd8` | 338 | 1-260 (full) |
| `utils/common-spring/pom.xml` | `4809ad116fac0fb2d459571d2129bfe67a1e25a8` | 191 | 1-191 (full) |
| `utils/common-core/src/main/java/com/shop/common/core/data/SoftDeletable.java` | `1225eb759585a8cd5faa233445bfa1142302b087` | 53 | 1-53 (full) |
| `utils/common-logging/src/main/java/com/shop/common/logging/audit/AuditActorResolver.java` | `eb7d0a9e4450c08a19bf3b2be26bef27f546ab02` | 81 | 1-81 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java` | `7653260e1cfb464c955a5c5f38d29d796a21f23c` | 64 | 1-64 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtSecurityConfig.java` | `2956ff90f3918c7e43ef5560e3e51ac5940ec82c` | 106 | 1-106 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/GatewayJwtProperties.java` | `812dcc57a183cf56bcef0907cc5b9c55f0a7d329` | 32 | 1-32 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/GatewaySecurityPostureValidator.java` | `e14c6f98e0eea861f5f51c9aaf204b2e5fc232e1` | 128 | 1-128 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/GatewayProperties.java` | `2b680e2e097aa9a03d15bf94d081a372d125d8a5` | 23 | 1-23 (full) |
| `gateway-service/src/main/java/com/shop/gateway/config/LazyReactiveJwtDecoder.java` | `c5b893dfaca3abeedec9cbec4d62f8636d6bbdda` | 57 | 1-57 (full) |
| `gateway-service/src/main/java/com/shop/gateway/constant/ServiceRoute.java` | `17a72ba25c3297b236c292d5b9793c79c5325c48` | 98 | 1-98 (full) |
| `gateway-service/src/main/java/com/shop/gateway/filter/AdminRoleGateFilter.java` | `54be7bcd495764f20b067403f37ea96edd827331` | 122 | 1-122 (full) |

### 1.6 order-service / favourite-service

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistFilter.java` | `1c7d45c46d7ad368cd735ebd968183e51974e290` | 151 | 1-151 (full) |
| `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java` | `c713524d6d8609c75fe7aa2eefacfe493eff29fc` | 31 | 1-31 (full) |
| `gateway-service/src/main/java/com/shop/gateway/filter/RequestPathGuard.java` | `13c256c4dba7348f0f5a42f005926920d03fade5` | 72 | 1-72 (full) |
| `gateway-service/src/main/resources/application.yml` | `669b709e997e7017ec6c72d34c76e0cbf82b414b` | 149 | 1-149 (full) |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java` | `6dd753ec88b411f9c25e1c030cbab58cf3e2363f` | 97 | 1-97 (full) |
| `order-service/src/main/java/com/shop/orderservice/controller/CartController.java` | `cbd0690258ed1d1267047c1aee8b5262ef7d6d46` | 160 | 1-160 (full) |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderReturnController.java` | `12e83abb65d3a7daaa30b603b2eb49ab0be63901` | 60 | 1-60 (full) |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java` | `d37e82c78ab7dcf96267e6450dbbd9fdd572ffca` | 56 | 1-56 (full) |
| `order-service/src/main/java/com/shop/orderservice/controller/OrderTrackingController.java` | `b073ef610174bc8350f458ccff7ef6fb0dfba869` | 48 | 1-48 (full) |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java` | `68baa8d677111e6a078ce646ff7f62d4c1015dbb` | 557 | 195-274, 415-484 (grep-targeted) |
| `order-service/src/main/java/com/shop/orderservice/service/impls/OrderReturnServiceImpl.java` | `07c526524c20ea3a1a1035d0c2263aac5f39d0bf` | 183 | 43-133 (grep-targeted) |

### 1.7 payment-service / shipping-service

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `order-service/src/main/resources/application.yml` | `f1310a8beca43ec6ab082182e0f219c06148ce51` | 89 | 1-89 (full) |
| `favourite-service/src/main/java/com/shop/favouriteservice/controller/FavouriteController.java` | `9384a4efd424dd14a5de9aa75662dfbf5b862193` | 74 | 1-74 (full) |
| `favourite-service/src/main/java/com/shop/favouriteservice/service/impls/FavouriteServiceImpl.java` | `9edc66cebddd079735b67f90cea2b5a5309d4665` | 91 | 1-91 (full) |
| `favourite-service/src/main/java/com/shop/favouriteservice/repository/FavouriteRepository.java` | `658b3bafc7294752357fec8fcedb95bb61587450` | 69 | 1-69 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java` | `3712ce17d9881e5fd98234bdf6f5a5ec6ea2745b` | 96 | 1-96 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/dto/CreatePaymentRequest.java` | `3508e253ff0c43e7c6ca9c145c42e7a22c0bb534` | 27 | 1-27 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/webhook/PaymentWebhookController.java` | `f621fa4f3aa22884b58802e41d198914753ec420` | 46 | 1-46 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookSignatureVerifier.java` | `a6aeb523f9ec26796ff328651a76141614f9daa1` | 192 | 1-192 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/provider/StripeProvider.java` | `d26a4fb5a056509fc6212d961635870183e4cb9e` | 137 | 1-137 (full) |
| `payment-service/src/main/java/com/shop/paymentservice/service/WebhookEventService.java` | `e99ac4ceaf363705379daad89d81225f8fe88fbc` | 400 | 74-133 (grep-targeted) |
| `payment-service/src/main/resources/application.yml` | `6a6f801cdf618a2388ec3693f6ec5d244fe57828` | 67 | 1-67 (full) |
| `shipping-service/src/main/java/com/shop/shippingservice/webhook/CarrierWebhookController.java` | `e380bb8dbe35b59d021e49d19b829ef210a49259` | 30 | 1-30 (full) |

### 1.8 promotion-service / rating-service / inventory-service / product-service / media-service / search-service

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `shipping-service/src/main/java/com/shop/shippingservice/webhook/WebhookSignatureVerifier.java` | `4d2a2091c739d5952ae08f14bd0ea5cec476bcf6` | 35 | 1-35 (full) |
| `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java` | `4ae6412d8006536d1b1050faf9ddbc02281a70c0` | 245 | 1-245 (full) |
| `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java` | `74e5677a50f10a01d22e9628ddd4b3bed506ac5c` | 97 | 1-97 (full) |
| `shipping-service/src/main/resources/application.yml` | `8cd0ca48635acd98d5053fd944ea35473cc97216` | 48 | 1-48 (full) |
| `promotion-service/src/main/java/com/shop/promotionservice/controller/PromotionReservationController.java` | `a87354249990c8c3549fa4f09cd7ffc582b21c2c` | 75 | 1-75 (full) |
| `promotion-service/src/main/java/com/shop/promotionservice/dto/request/ReserveRequest.java` | `f29fa158c2c029a204f8250d4df8caec2809f0e3` | 13 | 1-13 (full) |
| `promotion-service/src/main/java/com/shop/promotionservice/dto/request/CampaignRequest.java` | `75dc0208588a2630d7f29fa871e3c4e7c05cd545` | 29 | 1-29 (full) |
| `promotion-service/src/main/java/com/shop/promotionservice/validation/ValidDiscountValueValidator.java` | `064ae8240efe70a0735f63c6c5ce002eea37b613` | 32 | 1-32 (full) |
| `rating-service/src/main/java/com/shop/ratingservice/controller/StorefrontRatingController.java` | `ce9a69037fbebbb4a341c82ea663abbd4ee64f4d` | 68 | 1-68 (full) |
| `rating-service/src/main/java/com/shop/ratingservice/service/impls/RatingServiceImpl.java` | `ea96f02ad962d64af5786ab790d9a8584b763961` | 138 | 1-138 (full) |
| `rating-service/src/main/java/com/shop/ratingservice/dto/request/RatingSubmitRequest.java` | `c8114082c892d087a22850d1d5215c216da55cc3` | 23 | 1-23 (full) |
| `rating-service/src/main/java/com/shop/ratingservice/eligibility/EligibilityClient.java` | `4487c9ce254a205d92fcd4a64ec94db00b1781f5` | 73 | 1-73 (full) |
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InventoryController.java` | `961389b3fc146f595a72c16c951403f617b958e8` | 123 | 1-123 (full) |

### 1.9 Root-level build, deploy, env and CI files

| Path | `git rev-parse HEAD:<path>` | Lines | Ranges read |
|---|---|---|---|
| `inventory-service/src/main/java/com/shop/inventoryservice/controller/InternalInventoryController.java` | `a040a1ad4b9a99cd5df8d444e6dae5b42bf5922d` | 43 | 1-43 (full) |
| `product-service/src/main/java/com/shop/productservice/client/MediaHeadClient.java` | `0939cb31b99e12e222886ba9e5533d546efc7729` | 63 | 1-63 (full) |
| `product-service/src/main/java/com/shop/productservice/security/ServiceTokenProvider.java` | `a7bfd4c82c6a0929a1e9aa311f6f3ba7f4180da2` | 125 | 1-125 (full) |
| `product-service/src/main/java/com/shop/productservice/dto/request/ProductCreateRequest.java` | `c333c9818c6c3e9ac9c6877795b756b43ec6ef91` | 54 | 1-54 (full) |
| `product-service/src/main/resources/application.yml` | `1444f56c07a0b03c4c2ad51112de44462fff544b` | 107 | 1-107 (full) |
| `media-service/src/main/java/com/shop/mediaservice/controller/MediaPublicController.java` | `17d5e591b7512e69f6dd7b6a2bd6469879abeacc` | 47 | 1-47 (full) |
| `media-service/src/main/java/com/shop/mediaservice/service/impls/MediaQueryServiceImpl.java` | `f54d77fcefd9372b9e46a2ef1e266eb34335801d` | 94 | 1-94 (full) |
| `search-service/src/main/java/com/shop/searchservice/dto/request/SearchRequest.java` | `1e13876e53ea3afdbdefd933eb8dedde5ce0febe` | 56 | 1-56 (full) |
| `pom.xml` | `fa2d99d18ec40486cc4fac8ea24f6f03f1584eb3` | 554 | 1-70, 340-420, 460-554 [grep/metrics] |
| `docker-compose.yml` | `51374a0ac0b5cf9cfb9e7abe05c0062d1b9c30e0` | 766 | 1-384 [grep/metrics] + targeted 205-384 |
| `docker-compose.prod.yml` | `d98d02163a784fc833b25ba1a57a7f99274cb4eb` | 205 | 1-205 (full) |
| `.env.example` | `d6bb5c15bc85228f7930fdebf2eda9b43e9d8a00` | 237 | KEY NAMES + VALUE SHAPES only (values not reproduced) |
| `.env.prod.example` | `3dcf40d8a49dc5625dafa9b6e21fe29e6d980e55` | 191 | KEY NAMES + VALUE SHAPES only (values not reproduced) |
| `.gitignore` | `93b4c759c0ae525a4f606bdc2212d72538344bd1` | 173 | grep-targeted (env rules) |
| `.github/workflows/ci.yml` | `7de4b05b15b0fde1cd65f9bad76284633a8b840f` | 28 | 1-28 (full) |
| `docker/keycloak/import/ecommerce-realm.json` | `d7e0cc223e8cad3d62c39e00f3f849395dbd0901` | 223 | 1-223 (full) |

---

## 2. IDENTITY FLOW

### 2.1 The real login path (end to end)

`POST /api/v1/auth/login` → `AuthController.login` → `AuthServiceImpl.login` → `KeycloakTokenClient.login` → **Keycloak token endpoint**.

`auth-service/src/main/java/com/shop/authservice/controller/AuthController.java:34-38`
```java
    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse tokens = authService.login(request.username(), request.password());
        return ApiResponse.ok(tokens);
    }
```

`auth-service/src/main/java/com/shop/authservice/service/impls/AuthServiceImpl.java:16-20`
```java
    @Override
    public TokenResponse login(String username, String password) {
        KeycloakTokenResponse keycloakResponse = keycloakTokenClient.login(username, password);
        return mapToTokenResponse(keycloakResponse);
    }
```

`utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:53-64`
```java
    public KeycloakTokenResponse login(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(GRANT_TYPE, PASSWORD);
        form.add(USERNAME, username);
        form.add(PASSWORD, password);
        form.add(CLIENT_ID, clientId);
        if (clientSecret != null && !clientSecret.isEmpty()) {
            form.add(CLIENT_SECRET, clientSecret);
        }

        return postToken(form);
    }
```

**Verdict: Keycloak-issued tokens.** auth-service never signs, mints or stores a JWT — it has no JWT library, no signing key and no `JwtEncoder`/`NimbusJwtEncoder` anywhere in the repo (verified by grep for `jwt|oauth2|oidc` across `*.java`: the only JWT *creation* is Keycloak's). auth-service keeps a **shadow copy** of the user row (`users.keycloak_user_id`, `auth-service/.../entity/User.java:63-64`) for fast lookups.

### 2.2 Issuer, algorithm, key material

| Property | Value | Evidence |
|---|---|---|
| Issuer (`iss`) | `http://keycloak:8080/realms/ecommerce` (internal URL, pinned by `--hostname`) | `docker-compose.yml:234`, `utils/common-spring/src/main/resources/application.yml:138`, `gateway-service/src/main/resources/application.yml:30` |
| Algorithm | RS256 (Keycloak realm default; **not pinned** in the realm import) | `docker/keycloak/import/ecommerce-realm.json` — no `attributes` key for the signing algorithm |
| Key material | Keycloak's own JWKS, fetched at runtime from the realm's JWKS endpoint; **no key material in the repo** | `utils/common-security/.../BaseSecurityConfig.java:116` (`NimbusJwtDecoder.withIssuerLocation`), `:117` |
| Key material source (hostile-input control) | Issuer+timestamp validated on every decode; optional `aud` / `azp` validators | `BaseSecurityConfig.java:122-145` |

```java
    private OAuth2TokenValidator<Jwt> buildValidatorChain() {
        var validators = new ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(new JwtIssuerValidator(properties.issuerUri()));
        validators.add(new JwtTimestampValidator());
        if (!properties.expectedAudiences().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("aud", aud -> { ... }));
        }
        if (!properties.expectedClientIds().isEmpty()) {
            validators.add(new JwtClaimValidator<Object>("azp", azp -> { ... }));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
```
`utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:122-145`

The decoder is lazy so a Keycloak outage does not block startup — `utils/common-security/.../LazyJwtDecoder.java:43-46, 62-78`.

### 2.3 TTL, refresh, revocation

| Concern | Reality | Evidence |
|---|---|---|
| Access-token TTL | **Not pinned in the realm** → Keycloak default (300 s). The fleet only relays `expires_in`. | `docker/keycloak/import/ecommerce-realm.json` (no `accessTokenLifespan`); `utils/common-keycloak/.../KeycloakTokenResponse.java:13` |
| Refresh token | Issued by Keycloak (`refresh_token` grant), relayed verbatim. `TokenResponse` has no `refresh_expires_in`. | `KeycloakTokenClient.java:73-83`; `auth-service/.../dto/response/TokenResponse.java:12-17` |
| Refresh lifetime | **Not pinned** → Keycloak default offline/session idle timeout. | realm import has no `ssoSessionIdleTimeout` / `offlineSessionIdleTimeout` / `offlineSessionMaxLifespan` |
| Logout | `POST /api/v1/auth/logout` → Keycloak `logout` endpoint with the refresh token → refresh token revoked server-side. | `AuthController.java:46-50`; `KeycloakTokenClient.java:113-136` |
| Session revocation on credential change | **Correct.** After a password change or reset, `KeycloakAdminClient.logoutUser()` is called so pre-existing refresh tokens die. | `auth-service/.../UserServiceImpl.java:160` and `:398`; `utils/common-keycloak/.../KeycloakAdminClient.java:295-311` |
| Password-reset token | Single-use, atomically consumed **before** the password is written; SHA-256 hashed at rest; 15 min TTL. | `UserServiceImpl.java:349-361, 375-394`; `PasswordResetTokenRepository.java:31-34`; `PasswordResetToken.java:36-44` |

### 2.4 The grant actually used — ROPC is enabled

`docker/keycloak/import/ecommerce-realm.json:30-52`
```json
    {
      "clientId": "ecommerce-client",
      "name": "Ecommerce Public Client",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": true,
      "directAccessGrantsEnabled": true,
      "standardFlowEnabled": true,
      "serviceAccountsEnabled": false,
      "implicitFlowEnabled": false,
      "fullScopeAllowed": true,
      "redirectUris": [
        "http://localhost:*",
        "http://127.0.0.1:*",
        "http://api.ecommerce.local/*"
      ],
      "webOrigins": [ "*" ],
```

The platform's own login **is** the Resource Owner Password Credentials grant (`GRANT_TYPE = PASSWORD`, `KeycloakTokenClient.java:55`). The fleet knows this and only warns:

`auth-service/src/main/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheck.java:53-59`
```java
            boolean enabled = adminClient.isDirectAccessGrantEnabled(clientId);
            if (enabled) {
                log.warn("SECURITY-POSTURE: Keycloak client '{}' still has the password (ROPC) grant enabled. "
                        + "The OAuth 2.0 Security Best Current Practice deprecates it: the user discloses "
                        + "their password to the client and the flow is not bound to MFA. ...",
                        clientId);
```
→ **SEC-05**.

### 2.5 Admin / service identities

The master-realm password grant has been replaced by a **realm-scoped service account** (`ecommerce-admin`, `client_credentials`) — this is correct and is a positive finding:

- `utils/common-keycloak/.../KeycloakAdminClient.java:414-416` → `if (hasServiceAccountCredentials()) { return getServiceAccountAccessToken(); }`
- `docker/keycloak/import/ecommerce-realm.json:118-130` → the `ecommerce-admin` client; realm-management permissions limited to `manage-users`, `view-realm`, `view-clients` (`:214-219`).
- The master-realm credential path is still implemented (`KeycloakAdminClient.java:418-422`) but only used when `shop.keycloak.admin-client-secret` is unset; `utils/common-keycloak/.../KeycloakAutoConfiguration.java:83-99` creates the admin client if **either** credential is present.
- `docker-compose.yml:346` wires `SHOP_KEYCLOAK_ADMIN_CLIENT_SECRET: ${ADMIN_SERVICE_CLIENT_SECRET}` → the service-account path is the one actually used.

### 2.6 Identity mapping — the principal id is NOT the auth-service row key (**SEC-01**)

`utils/common-security/src/main/java/com/shop/common/security/jwt/JwtClaimExtractor.java:30-33`
```java
    /** Subject — the stable, immutable Keycloak user id. */
    public static String subject(Jwt jwt) {
        return jwt.getSubject();
    }
```
`utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java:39-46` → `id = JwtClaimExtractor.subject(jwt)` = the **Keycloak user UUID**.

`auth-service/src/main/java/com/shop/authservice/entity/User.java:29-32, 63-64`
```java
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id", unique = true, nullable = false)
    private UUID id;
    ...
    @Column(name = "keycloak_user_id", unique = true)
    private String keycloakUserId;
```
→ the local primary key is a **JPA-generated UUID**, stored separately from the Keycloak id string.

Consequence (see SEC-01 for the full defect): `auth-service/.../UserController.java:36` and `:51`, and all five `AddressController` handlers (`:36, :43, :51, :58, :64`) resolve the local row with `UUID.fromString(AuthenticatedUser.requireCurrent().id())` — the **Keycloak** id — against a table whose primary key is a **different** UUID. `user_addresses.user_id` additionally carries a foreign key to `users(user_id)` (`auth-service/src/main/resources/db/changelog/changeset/004-password-reset-and-addresses.yaml:59-64`).

---

## 3. AUTHORIZATION

### 3.1 The default-deny rule — YES

`utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:72-86`
```java
        http.authorizeHttpRequests(auth -> {
                    for (SecurityProperties.EndpointRule rule : properties.publicPaths()) {
                        if (rule.method() != null) {
                            auth.requestMatchers(rule.method(), rule.path()).permitAll();
                        } else {
                            auth.requestMatchers(rule.path()).permitAll();
                        }
                    }
                    // Platform defaults (actuator, swagger, api-docs) are always public — do NOT drop
                    auth.requestMatchers(SecurityProperties.PlatformDefaults.PUBLIC_PATHS.toArray(new String[0]))
                            .permitAll();
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults()));
```
`auth.anyRequest().authenticated()` at **`BaseSecurityConfig.java:83`** is the default-deny. The chain is `@ConditionalOnMissingBean(SecurityFilterChain.class)` (`:53`), so a service that declares its own chain replaces it wholesale — no service does.

`@EnableMethodSecurity` is on (`utils/common-security/.../SecurityAutoConfiguration.java:31`), so `@PreAuthorize` is enforced.

Reactive edge:
`gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java:41-48`
```java
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers(publicPaths).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
```
`anyExchange().authenticated()` at `SecurityConfig.java:46` — default-deny at the edge too.

**Every service reaches this chain**: all 13 servlet services depend on `common-spring` (`utils/common-spring/pom.xml:20-22` → `common-security`), which brings `spring-boot-starter-oauth2-resource-server` (`utils/common-spring/pom.xml:54-56`). No service sets `shop.security.enabled=false`.

### 3.2 Complete path-matcher rule list

**Platform defaults — always public in every servlet service** (`utils/common-security/.../SecurityProperties.java:144-156`):
```
/actuator/health        /actuator/health/**     /actuator/info
/actuator/prometheus    /v3/api-docs            /v3/api-docs/**
/swagger-ui.html        /swagger-ui/**          /swagger-resources/**
/webjars/**
```

**Service-specific rules** (`shop.security.public-paths`, bound as `List<EndpointRule>`):

| Service | Rule | Evidence |
|---|---|---|
| auth-service | `ANY /api/v1/auth/sign-up` · `ANY /api/v1/auth/login` · `ANY /api/v1/auth/refresh` · `ANY /api/v1/auth/logout` · `ANY /api/v1/auth/forgot-password` · `ANY /api/v1/auth/reset-password` | `auth-service/src/main/resources/application.yml:60-66` |
| order-service | `ANY /api/v1/carts/**` · `ANY /api/v1/orders/track` | `order-service/src/main/resources/application.yml:43-45` |
| payment-service | `POST /api/v1/webhooks/payments/**` | `payment-service/src/main/resources/application.yml:65-67` |
| shipping-service | `ANY /api/v1/webhooks/shipping/**` | `shipping-service/src/main/resources/application.yml:47-48` |
| product-service | `GET /api/v1/products/**` · `GET /api/v1/categories/**` · `GET /api/v1/brands/**` | `product-service/src/main/resources/application.yml:69-75` |
| favourite / inventory / media / notification / promotion / rating / search / tax | *(none — every path requires authentication)* | respective `application.yml` |

**Gateway edge rules** (`gateway-service/src/main/resources/application.yml:62-86`):
```
/actuator/health  /actuator/health/**  /actuator/info  /v3/api-docs/**  /swagger-ui/**  /webjars/**
/api/v1/auth/**   /api/v1/webhooks/payments/**   /api/v1/webhooks/shipping/**
/api/v1/products  /api/v1/products/**   /api/v1/categories  /api/v1/categories/**
/api/v1/brands    /api/v1/brands/**    /api/v1/search     /api/v1/search/**
/api/v1/ratings   /api/v1/ratings/**   /api/v1/medias     /api/v1/medias/**
/api/v1/carts     /api/v1/carts/**     /api/v1/orders/track
```
Note the asymmetry: the gateway treats `/api/v1/carts/**` and `/api/v1/medias/**` as public while order-service / media-service still require a JWT for them — the edge is more permissive than the origin, which is the safe direction.

### 3.3 Where role checks are applied

| Layer | Mechanism | Evidence |
|---|---|---|
| Method level | `@PreAuthorize` on controllers/methods | 38 occurrences across 24 controller classes (grep inventory in §1.6–1.8) |
| Class level (admin surface) | `@PreAuthorize("hasRole('ADMIN')")` on every `Backoffice*Controller` | `notification/.../BackofficeNotificationController.java:24`, `order/.../BackofficeOrderReturnController.java:23`, `rating/.../BackofficeRatingController.java`, `search/.../BackofficeSearchController.java`, `shipping/.../BackofficeShipmentController.java`, `tax/.../BackofficeTaxClassController.java`, `tax/.../BackofficeTaxRateController.java`, `payment/.../BackofficePaymentController.java`, `product/.../Backoffice{Brand,Category,Product,ProductVariant}Controller.java`, `media/.../BackofficeMediaController.java`, `promotion/.../BackofficeCampaignController.java` |
| Admin surface (auth-service) | `@PreAuthorize("hasAuthority('ADMIN')")` | `auth-service/.../RoleController.java:22` |
| Self-service (auth-service) | `@PreAuthorize("isAuthenticated()")` | `auth-service/.../UserController.java:33, 41, 48, 57`, `auth-service/.../AddressController.java:29` |
| Service-to-service | `hasRole('SERVICE') or hasRole('ADMIN')` **plus** `ServiceCallerGuard.requireCaller("order-service")` | `inventory/.../InventoryController.java:82-87, 92-95, 101-104, 110-113`, `promotion/.../PromotionReservationController.java:34-39, 44-47, 53-56, 62-65, 71-72`, `payment/.../PaymentController.java:39-43, 48-52, 57-61, 66, 90` |
| Edge (gateway) | `AdminRoleGateFilter` — 12 backoffice prefixes require the `ADMIN` realm role | `gateway-service/.../AdminRoleGateFilter.java:39-63, 101-121` |
| Edge (network) | `AdminIpAllowlistFilter` — CIDR allow-list, deny-by-default when active | `gateway-service/.../AdminIpAllowlistFilter.java:71-89` |

`ServiceCallerGuard` — the second factor for service-to-service calls — `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java:27-51`:
```java
    public void requireCaller(String... allowedClientIds) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // No authentication at all: the endpoint's own @PreAuthorize is the
            // authority on whether the request may proceed, ...
            return;                                    // <-- fail-open branch, see SEC-20
        }
        if (!authentication.isAuthenticated()) {
            throw BusinessException.of(ErrorCode.FORBIDDEN, "caller is not authenticated");
        }
        if (hasHumanAdminRole(authentication)) {
            return;                                    // ADMIN/MANAGER bypass the client check
        }
        String clientId = clientIdOf(authentication);
        if (clientId == null || clientId.isBlank()) { ... throw ... }
        List<String> allowed = List.of(allowedClientIds);
        if (!allowed.contains(clientId)) { ... throw ... }
    }
```

### 3.4 Role → authority mapping

`utils/common-security/src/main/java/com/shop/common/security/jwt/JwtRolesConverter.java:48-63` emits **two** authorities per realm role — the raw name (`ADMIN`) and the prefixed name (`ROLE_ADMIN`) — so both `hasAuthority('ADMIN')` and `hasRole('ADMIN')` work. **Proved correct**: `RoleController` uses `hasAuthority('ADMIN')` while the rest of the fleet uses `hasRole('ADMIN')`; both resolve. The gateway's `AdminRoleGateFilter` reads the raw `realm_access.roles` list directly (`AdminRoleGateFilter.java:101-103`) and matches `ADMIN` — consistent.

---

## 4. USER ISOLATION (IDOR)

### 4.1 Summary

| # | Endpoint | Caller-supplied id | Cross-check against principal | Verdict |
|---|---|---|---|---|
| 1 | `GET /api/v1/orders/verify-purchase?userId=&productId=` | `userId` (query) | **NONE** | **CONFIRMED IDOR — SEC-02** |
| 2 | `GET/PUT/DELETE /api/v1/users/me/**` and `/api/v1/users/me/addresses/**` | — (principal used) | principal used, but the **key space is wrong** | **DEFECT — SEC-01** |

Everything else was checked and found correctly scoped (proof in §4.3).

### 4.2 The confirmed IDOR

`order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:81-92`
```java
    @GetMapping("/verify-purchase")
    @PreAuthorize("hasAnyRole('SERVICE','ADMIN')")
    public ApiResponse<PageResponse<OrderItemResponse>> verifyPurchase(
            @RequestParam UUID userId, @RequestParam UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, PageableConstant.MAX_PAGE_SIZE));
        Page<OrderItemResponse> result =
                orderService.findDeliveredItemsByUserAndProduct(userId, productId, pageable);
```
There is **no** `ServiceCallerGuard.requireCaller(...)` on this handler (the class does not import `ServiceCallerGuard` at all), and the `userId` is never compared to the caller's own identity.

`order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:447-452`
```java
    @Override
    @Transactional(readOnly = true)
    public Page<OrderItemResponse> findDeliveredItemsByUserAndProduct(UUID userId, UUID productId, Pageable pageable) {
        return orderItemRepository.findDeliveredByUserAndProduct(userId, productId, pageable)
                .map(orderMapper::toItemResponse);
    }
```
`order-service/src/main/java/com/shop/orderservice/repository/OrderItemRepository.java:21-30`
```java
    @Query("""
        SELECT i FROM OrderItem i
        WHERE i.productId = :productId
          AND EXISTS (SELECT o FROM Order o WHERE o.id = i.orderId
                      AND o.userId = :userId AND o.status = com.shop.orderservice.constant.OrderStatus.DELIVERED
                      AND o.deleted = false)
        ORDER BY i.id
        """)
    Page<OrderItem> findDeliveredByUserAndProduct(@Param("userId") UUID userId,
        @Param("productId") UUID productId, Pageable pageable);
```

**Why it is reachable by more than it should be.** The fleet-wide accepted-client list is identical for every service and contains six client ids:

`utils/common-spring/src/main/resources/application.yml:158`
```yaml
    expected-client-ids: ${SHOP_SECURITY_EXPECTED_CLIENT_IDS:ecommerce-client,order-service,rating-service,search-service,product-service,media-service}
```
Every service account in the realm carries the realm role `SERVICE` (`docker/keycloak/import/ecommerce-realm.json:170-209`). Therefore a token minted for **any** of `order-service`, `rating-service`, `search-service`, `product-service`, `media-service` satisfies `hasAnyRole('SERVICE','ADMIN')` **and** the `azp` validator, and can read the delivered purchase history of **any** user for any product. The response exposes `productId, productTitle, quantity, unitPrice, lineTotal` (`order-service/.../dto/response/OrderItemResponse.java`) — order-level PII and revenue data.

`rating-service` is the *intended* caller (`rating-service/.../eligibility/EligibilityClient.java:51-60` calls this endpoint with the JWT-derived user id). The endpoint never checks that the caller **is** rating-service.

### 4.3 Candidates checked and REJECTED (isolation is correct)

| Candidate | Location | Check that makes it safe |
|---|---|---|
| Favourite read/update/delete | `favourite-service/.../FavouriteController.java:42-57` | `FavouriteServiceImpl.java:44-45, 68-73` → `FavouriteRepository.findByIdAndUserId` (`:28`) and `softDeleteByIdAndUserId` (`:38-50`) — the owner predicate is in the SQL. |
| Order read / cancel | `order-service/.../OrderController.java:67-79` | `OrderServiceImpl.java:425` and `:206`: `if (!isAdmin && !order.getUserId().equals(userId)) throw …ORDER_NOT_FOUND` (existence hidden). |
| Order returns | `order-service/.../OrderReturnController.java:35-55` | `OrderReturnServiceImpl.java:47` and `:121`: `if (!order.getUserId().equals(userId))`. Cumulative refund bounded by `order.getTotal()` at `OrderReturnServiceImpl.java:66`. |
| Rating submit / edit | `rating-service/.../StorefrontRatingController.java:55-67` | `RatingServiceImpl.java:44` (`findByUserIdAndProductIdAndDeletedFalse(jwtUserId, …)`) and `:87` (same predicate for edit). |
| Cart items | `order-service/.../CartController.java:64-98` | `CartController.resolveCartUserId` (`:134-143`) prefers the **principal**; the guest id is a UUID-format bearer capability (`:59-60, :150-155`). Merge requires header + param agreement (`:122-132`). |
| Addresses (auth-service) | `auth-service/.../AddressController.java:47-66` | `AddressServiceImpl.java:65, 89, 108` → `UserAddressRepository.findByIdAndUserId` (`:17`). Ownership filter present. (The id-space bug is SEC-01, not an IDOR.) |
| Payment create (userId in body) | `payment-service/.../PaymentController.java:38-45` | `serviceCallerGuard.requireCaller("order-service")` at `:43` — only the order-service client may set the user id. |
| Promotion reserve (userId in body) | `promotion-service/.../PromotionReservationController.java:32-41` | `serviceCallerGuard.requireCaller("order-service")` at `:39`. |
| Role assign/revoke/read | `auth-service/.../RoleController.java:28-43` | Class-level `@PreAuthorize("hasAuthority('ADMIN')")` at `:22`. |
| User read by id / list / restore | `auth-service/.../UserController.java:63-84` | Class/method-level `@PreAuthorize("isAuthenticated() and hasRole('ADMIN')")` at `:64, :70, :80`. |
| Notification list/read | `notification-service/.../BackofficeNotificationController.java:24, 30-44` | Class-level `hasRole('ADMIN')`. |
| Inventory reservations | `inventory-service/.../InventoryController.java:80-122` | `hasRole('SERVICE') or hasRole('ADMIN')` + `ServiceCallerGuard.requireCaller("order-service")`. |

### 4.4 Other places where a caller-supplied id is trusted

- `promotion-service` `ReserveRequest.userId` (`promotion-service/.../dto/request/ReserveRequest.java:10`) and `payment-service` `CreatePaymentRequest.userId` (`payment-service/.../dto/CreatePaymentRequest.java:17`) are body fields, but both are behind `ServiceCallerGuard.requireCaller("order-service")`. **Accepted** — service-to-service trust boundary, not an end-user IDOR. However, note `ServiceCallerGuard` lets `ADMIN`/`MANAGER` bypass the client check (`ServiceCallerGuard.java:38-40`), so an admin can create a reservation or payment against any user's id. That is intended privilege, not a defect.
- `media-service` `MediaLifecycleServiceImpl` reads the principal (`AuthenticatedUser`) — audited below in §4.5.

---

## 5. SECRET HANDLING

### 5.1 Is `.env` tracked? — NO (correct)

```
$ git check-ignore -v .env
.gitignore:22:.env    .env
$ git ls-files | grep -E '^\.env'
.env.example
.env.prod.example
```
`.gitignore:21-25`
```
# Ignore actual env file (may contain real secrets)
.env
.env.*
!.env.example
!.env.template
!.env.prod.example
```
The untracked `.env` **exists on this workstation** and holds 87 keys (kind: live deployment values for Keycloak admin, Postgres, Redis, object storage, Elasticsearch, mail, per-service client secrets and the payment webhook secret). It is correctly ignored and correctly untracked — **not a leak**, but it is a local file that must never be copied into an image layer or a CI artifact.

### 5.2 Committed literal credentials — CONFIRMED LEAK

`.env.example` is a **tracked** file (`git ls-files` confirms) and it ships **literal, non-placeholder** values for credentials that the deployment then *requires*:

| Location | Kind of secret | Value shape | Notes |
|---|---|---|---|
| `.env.example:237` | **Keycloak seeded ADMIN account password** (`adminuser`, roles ADMIN+MANAGER — `docker/keycloak/import/ecommerce-realm.json:151-168`) | literal, 9 chars, well-known | The file's own comment says "CHANGE IN PROD"; `docker-compose.yml:253` makes it **mandatory**: `KEYCLOAK_ADMIN_USER_PASSWORD: ${KEYCLOAK_ADMIN_USER_PASSWORD:?required}`. A deployment that copies `.env.example` → `.env` gets a publicly-known administrator credential. |
| `.env.example:233` | Keycloak seeded USER account password (`testuser`) | literal, 8 chars, well-known | Same mechanism (`docker-compose.yml:248`). |
| `.env.example:52` | Keycloak **master-realm** bootstrap administrator **username** | literal, 5 chars, well-known | `docker-compose.yml:236` requires it. |
| `.env.example:22, 29, 80, 81, 99, 100` | Postgres / Redis / S3 access key / S3 secret key / Elasticsearch admin user / Elasticsearch admin password | literal, 13–17 chars | Marked "CHANGE IN PROD"; all consumed by `docker-compose.yml:27-28, 72, 127-131`. |
| `.env.example:157` | Payment webhook HMAC secret | literal, 19 chars | `shop.payment.webhook.secret` — the value that authenticates inbound payment webhooks. |

The **prod** template is materially better: `.env.prod.example:63-134` uses non-random "change-me"-style placeholders for every one of these (13–14 chars) and `KEYCLOAK_CLIENT_SECRET` is a placeholder, not a literal. Still non-empty, so a verbatim copy is a known value, but it is at least not a shipped working password.

**This is exactly the class of defect the repo already fixed elsewhere** — the realm import no longer carries a literal (`docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 116, 129, 143, 161` are all `${ENV_VAR}` placeholders, mounted read-only at `docker-compose.yml:263`) — but the *same* literal was left behind in `.env.example`. → **SEC-03**.

### 5.3 Credential disclosure in tracked documentation

`HANDOFF.md:31` documents the seeded Keycloak accounts **with their passwords in prose** (kinds: Keycloak admin password, Keycloak user password). The same literals are repeated in `docs/PRODUCTION-READINESS.md` and in 8 files under `docs/review/` (grep for the two known literals matched `HANDOFF.md`, `docs/PRODUCTION-READINESS.md`, `docs/review/REVIEW-REPORT-2026-09-02.md`, `docs/review/REVIEW-REPORT-2026-09-03.md`, `docs/review/auth-favourite-gateway.md`, `docs/review/infra-config.md`, `docs/review/inventory-rating.md`, `docs/review/order-service.md`, `docs/review/pattern-verification.md`, `docs/review/product-service.md`). → **SEC-28**.

### 5.4 Hardcoded secrets in source — none material

`git ls-files -z | xargs -0 grep -nEI "(password|passwd|secret|api[_-]?key|token|private[_-]?key|credential)[[:space:]]*[:=][[:space:]]*[\"'][^\"'[:space:]]{6,}[\"']"` over all tracked files returned only three hits, none of them a real credential:
- `docs/postman/generate_master_collection.js:122, 135` — Postman **variable placeholders**.
- `docs/postman/generate_master_collection.js:690` — a deliberately-wrong password used to assert a 401.
- `scripts/run-sonar-scan.sh:28` — reads `SONAR_TOKEN` from the environment.

**Proved correct:** no credential is stored in a Java source file, a `application.yml`, a `docker-compose*.yml`, a Liquibase changeset, or a tracked properties file.

### 5.5 Key material and client secrets

- The realm import carries **placeholders only** — `docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 116, 129, 143, 161`. The template is mounted read-only (`docker-compose.yml:263`) and the substituted realm is written **inside** the container (`docker/keycloak/import/entrypoint.sh`), so the working tree is never mutated by a rotation. **Correct.**
- `docker-compose.yml:236-253` uses `:?required` for every Keycloak secret → the deployment **fails fast** rather than starting with an empty credential. **Correct.**
- The JWKS is the only key material used for token verification and it is fetched from Keycloak at runtime; nothing is embedded. **Correct.**

---

## 6. INPUT VALIDATION

### 6.1 `@Valid` coverage

`@Valid @RequestBody` is present on every mutating endpoint inspected: `auth-service/.../AuthController.java:29, 35, 41, 47, 53, 59`, `auth-service/.../UserController.java:35, 42`, `auth-service/.../RoleController.java:30, 36`, `auth-service/.../AddressController.java:42, 50`, `order-service/.../OrderController.java:34`, `.../CartController.java:73, 81`, `.../OrderReturnController.java:38`, `favourite-service/.../FavouriteController.java:48`, `rating-service/.../StorefrontRatingController.java:57, 64`, `payment-service/.../PaymentController.java:41`, `promotion-service/.../PromotionReservationController.java:37`, `inventory-service/.../InventoryController.java:62, 68, 85`, `product-service` backoffice controllers, `search-service/.../SearchController.java:36` (`@Valid @ModelAttribute`).
Global mapping of validation failures → 400 `VALIDATION_FAILED` with field detail: `utils/common-spring/.../ApiExceptionHandler.java:69-83`.

### 6.2 Bounded / unbounded numeric fields

| Field | Constraint | Verdict |
|---|---|---|
| `CartItemAddRequest.quantity` | `@NotNull @Min(1) @Max(99)` (`order-service/.../dto/request/CartItemAddRequest.java:11`) | bounded |
| `CartItemUpdateRequest.quantity` | `@NotNull @Min(0) @Max(99)` (`order-service/.../dto/request/CartItemUpdateRequest.java:8`) | **allows 0** → SEC-13 |
| `InventoryUpsertRequest.availableQuantity` | `@NotNull @Min(0)` (`inventory-service/.../dto/request/InventoryUpsertRequest.java:10`) | **no upper bound** → SEC-13 |
| `Inventory ReserveRequest.quantity` | `@NotNull @Positive` (`inventory-service/.../dto/request/ReserveRequest.java:9`) | positive, unbounded above → SEC-13 |
| `OrderReturnCreateRequest.refundAmount` | `@NotNull @Positive`, **no `@Digits`** (`order-service/.../dto/request/OrderReturnCreateRequest.java:15-17`) | unbounded scale → SEC-15 (mitigated by `OrderReturnServiceImpl.java:66`) |
| `CreatePaymentRequest.amount` | `@NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2)` (`payment-service/.../dto/CreatePaymentRequest.java:14`) | bounded |
| `CreatePaymentRequest.currency` | `@NotBlank @Size(min = 3, max = 3)` | bounded |
| `ProductCreateRequest.priceUnit` / `weight` | `@DecimalMin("0.0")`, **no `@Digits`** (`product-service/.../dto/request/ProductCreateRequest.java:30-31, 46-47`) | unbounded scale → SEC-16 |
| `ProductCreateRequest.quantity` | `@NotNull @Min(0)` (`:34-35`) | no upper bound |
| `ProductVariantCreateRequest.price` / `quantity` | `@DecimalMin("0.00")` / `@Min(0)` (`product-service/.../dto/request/ProductVariantCreateRequest.java:21-26`) | no upper bound / no `@Digits` |
| `TaxClassRequest.defaultRatePct`, `TaxRateRequest.ratePct` | `@DecimalMin("0.00") @DecimalMax("100.00")` (`tax-service/.../dto/request/TaxClassRequest.java:12`, `TaxRateRequest.java:15`) | bounded — correct |
| `CampaignRequest.discountValue` | `@NotNull @DecimalMin("0")` **plus** a cross-field validator that caps `PERCENT` at 100 (`promotion-service/.../validation/ValidDiscountValueValidator.java:21-29`) | bounded — correct |
| `CampaignRequest.totalBudget` / `maxRedemptions` / `perUserLimit` | `@Positive` / `@PositiveOrZero`, no upper bound | unbounded |
| `RatingSubmitRequest.rating` | `@NotNull @Min(1) @Max(5)` (`rating-service/.../dto/request/RatingSubmitRequest.java:15-18`) | bounded — correct |
| `TaxCalculateRequest.amount` | `@NotNull @DecimalMin("0.00")` | bounded below, unbounded scale |
| `SearchRequest.page` / `size` | `@PositiveOrZero @Max(MAX_PAGE_NUMBER)` / `@Min(1) @Max(MAX_PAGE_SIZE)` (`search-service/.../dto/request/SearchRequest.java:36-42`) | bounded — correct |
| `SearchRequest.minPrice` / `maxPrice` / `minRating` | **no constraint at all** (`search-service/.../dto/request/SearchRequest.java:25-29`) | negative filters accepted → SEC-14 |
| `UserController.getAllUsers(page, size)` | clamped in the service: `int safeSize = Math.min(Math.max(size, 1), 100);` (`auth-service/.../UserServiceImpl.java:208-213`) and the sort column whitelisted (`ALLOWED_SORT_FIELDS`, `:49-51, 210`) | bounded — **correct** (no `Sort`-injection) |
| Every other paged controller | `PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE))` | bounded |

### 6.3 String-length validation

`RegisterRequest` caps fullName/username/password at 50 and enforces complexity (`auth-service/.../dto/request/RegisterRequest.java:10-34`); `ChangePasswordRequest` enforces `min 8 / max 50` + complexity (`.../ChangePasswordRequest.java:12-15`); addresses cap every field (`.../AddressCreateRequest.java:8-29`); `ProductCreateRequest` caps title/slug/description/sku (`.../ProductCreateRequest.java:14-27`).

**Password-policy inconsistency → SEC-07:** `ResetPasswordRequest.newPassword` is only `@Size(min = 6)` with **no** complexity rule (`auth-service/.../dto/request/ResetPasswordRequest.java:9-11`), while sign-up and change-password require 8+ characters with upper/lower/digit. A holder of a reset token can therefore set a weaker password than registration would accept.

---

## 7. SQL / INJECTION / SSRF / PATH TRAVERSAL

### 7.1 SQL injection — NOT FOUND (proved correct)

Every query is either a derived Spring Data method name, a JPQL `@Query` with **named parameters**, or one of six native queries that also use named parameters:

```
auth-service/.../repository/UserRepository.java:59           SELECT * FROM users WHERE user_id = :id
media-service/.../repository/MediaRepository.java:26          SELECT EXISTS(SELECT 1 FROM medias WHERE id = :id)
media-service/.../repository/MediaRepository.java:43          ... WHERE id = :id AND deleted = false
media-service/.../repository/MediaRepository.java:54          SELECT id FROM medias WHERE deleted = true AND deleted_at <= :cutoff
media-service/.../repository/MediaRepository.java:58          SELECT object_key FROM media_variants WHERE media_id = :id
media-service/.../repository/MediaRepository.java:68          DELETE FROM medias WHERE id = :id
```
No `+`/`String.format`/`concat` is applied to any SQL fragment anywhere in `src/main` — the only string-concatenation hits in the codebase are log messages, exception messages, a Stripe search query (SEC-24) and a carrier JSON body (SEC-25). The one place a caller-supplied string reaches a *query parameter* of a non-SQL API is `RoleMapper.toRoleName` (`auth-service/.../mapper/RoleMapper.java:10-16`), which uses `RoleName.valueOf` on an enum → an unknown name raises `IllegalArgumentException` → `BusinessException.badRequest`. No injection surface.

### 7.2 SSRF — NOT FOUND (proved correct)

No user-controlled value reaches an outbound URL. Every `RestClient` target is configuration-derived:
- `KeycloakTokenClient` / `KeycloakAdminClient` — endpoints built from `KeycloakProperties` (`utils/common-keycloak/.../config/KeycloakProperties.java:60-115`).
- Cross-service clients use **path templates** with UUID path variables, never absolute URLs from input: `order-service/.../client/ProductServiceClient.java:42`, `InventoryServiceClient.java:49, 80, 105, 118, 131`, `PaymentServiceClient.java:72, 102`, `PromotionServiceClient.java:72, 102, 119`, `product-service/.../client/MediaHeadClient.java:50`, `rating-service/.../eligibility/EligibilityClient.java:54-57`.
- `CarrierShipmentClient.createShipment` posts to a **configured** endpoint (`shipping-service/.../carrier/CarrierShipmentClient.java:57`).
- The only user-supplied URL-shaped field is `avatar` (`RegisterRequest.java:36-37`, `UpdateUserRequest.java:21-22`, pattern `^(https?://)\S+$`) — it is **stored and returned, never fetched** (no HTTP client references it anywhere). The pattern permits `http://` (clear-text) and does not exclude internal hosts, but since it is never dereferenced there is no SSRF today; it becomes one if a future renderer fetches it → SEC-29.

### 7.3 Path traversal — NOT FOUND (proved correct)

The only user-controlled value that influences an object-storage key is the media `variant` query parameter — and it is allow-listed **before** it reaches the storage layer:

`media-service/src/main/java/com/shop/mediaservice/service/impls/MediaQueryServiceImpl.java:28-46`
```java
    private static final Set<String> KNOWN_VARIANTS = Set.of(VARIANT_ORIGINAL, VARIANT_DISPLAY, VARIANT_THUMB);
    ...
    public URL resolve(UUID mediaId, String variant, String format) {
        String variantName = variantOrDefault(variant);
        String formatName = format == null || format.isBlank() ? DEFAULT_FORMAT : format.toLowerCase();
        if (!KNOWN_VARIANTS.contains(variantName)) {
            log.info("Media read rejected: unknown variant '{}' for media {}", variantName, mediaId);
            throw BusinessException.of(ErrorCode.MEDIA_NOT_FOUND);
        }
```
The presigned object key comes from the DB row (`row.getObjectKey()`, `:56`), and the key itself is server-generated (`mediaId + "/" + variant + "." + ext`, `media-service/.../service/impls/MediaUploadServiceImpl.java:225`). The only `Paths.get(...)` in the codebase is a fixed temp directory (`media-service/.../config/UploadLimitsConfig.java:27`).

At the edge, the gateway additionally rejects percent-encoded and matrix-variable paths before any prefix decision (`gateway-service/.../filter/RequestPathGuard.java:20-46`, used at `AdminRoleGateFilter.java:57-70` and `AdminIpAllowlistFilter.java:77-80`).

### 7.4 Injection-adjacent patterns (not exploitable today, but fragile)

- `payment-service/.../provider/StripeProvider.java:118` — `.setQuery("metadata['payment_id']:'" + paymentId + "'")` — a Stripe **search-query** built by concatenation. `paymentId` is a server-generated UUID (`Payment.id`), not attacker input today → **SEC-24** (pattern hardening).
- `shipping-service/.../carrier/CarrierShipmentClient.java:60` — `.body("{\"order_id\":\"" + orderId + "\"" + (shopId == null ? "" : ",\"shop_id\":\"" + shopId + "\"") + "}")` — a JSON body built by concatenation. `orderId` is a UUID, `shopId` is configuration → **SEC-25** (pattern hardening).

---

## 8. CORS, CSRF, COOKIES, HTTPS, SECURITY HEADERS

### 8.1 CORS

**Servlet services** — `utils/common-spring/src/main/resources/application.yml:163-180`
```yaml
    cors:
      enabled: ${ECOMMERCE_CORS_ENABLED:true}
      allowed-origin-patterns:
        - ${CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:3000,http://localhost:5173}
      allowed-methods: [GET, POST, PUT, PATCH, DELETE, OPTIONS]
      allowed-headers: ["*"]
      allow-credentials: false
      max-age-seconds: 3600
```
`allow-credentials: false` and an explicit origin list — **no wildcard default**. The builder warns loudly if an operator combines credentials with `*` (`utils/common-security/.../config/CorsAutoConfigurer.java:42-45`).

**Gateway** — `gateway-service/.../config/SecurityConfig.java:51-63` sets `allowCredentials(true)`, which is only safe with an explicit list, and the posture validator **refuses to boot** on a wildcard:
`gateway-service/.../config/GatewaySecurityPostureValidator.java:60-71`
```java
    private void validateCors() {
        var patterns = gatewayProperties.corsAllowedOriginPatterns();
        boolean wildcard = patterns.stream().anyMatch(p -> p != null && p.trim().equals("*"));
        if (wildcard) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGIN_PATTERNS contains '*' while the gateway enables "
                    + "allowCredentials(true). Any website could send credentialed cross-origin "
                    + "requests to the gateway and read the responses. ...");
        }
```
and the variable is **mandatory** in the compose (`docker-compose.yml:297`: `${CORS_ALLOWED_ORIGIN_PATTERNS:?required}`; `gateway-service/src/main/resources/application.yml:61` uses `:?... must be set explicitly`). **Proved correct.**

### 8.2 CSRF

Disabled fleet-wide: `shop.security.csrf-disabled: true` (`utils/common-spring/src/main/resources/application.yml:139`) → `http.csrf(AbstractHttpConfigurer::disable)` (`BaseSecurityConfig.java:58-60`); gateway `csrf(ServerHttpSecurity.CsrfSpec::disable)` (`SecurityConfig.java:42`).
**Justified**: `SessionCreationPolicy.STATELESS` (`:61-63`), no `spring-session` dependency, and **zero** `Cookie`/`HttpOnly`/`SameSite`/`setSecure` references in the whole codebase (grep over all `src/main`). Authentication is exclusively a bearer token in the `Authorization` header, so there is no ambient credential for CSRF to abuse. **Proved correct.**

### 8.3 Cookies

No cookie is set or read anywhere (`grep -riE "Cookie|HttpOnly|SameSite|setSecure"` over all `src/main` → 0 hits). No `SESSION` cookie is issued because the session is stateless. → nothing to flag for cookie flags.

### 8.4 HTTPS enforcement

**None at the application layer.** There is no `server.ssl.*`, no `requiresChannel(...)`, no `forwarded-headers-strategy` hardening in the compose, and no TLS termination inside the compose network: `docker-compose.yml:234` starts Keycloak with `--hostname=http://keycloak:8080` (clear-text issuer), and the prod overlay delegates TLS to an external reverse proxy that the repository does not provide (`docker-compose.prod.yml:24-28` documents this as an operator prerequisite).

Consequence: **no `Strict-Transport-Security` is emitted by the application.** Spring Security's default `headers()` filter adds HSTS only for requests it sees as HTTPS, and none of the services terminates TLS, so the header is effectively absent. No CSP is configured either (Spring's default CSP is not enabled by `HttpSecurity` out of the box). → **SEC-23**.

Related but correct: `utils/common-spring/src/main/resources/application.yml:47` sets `forward-headers-strategy: framework`, and the gateway overrides it to `none` (`gateway-service/src/main/resources/application.yml:15`) precisely so the client IP cannot be spoofed through `X-Forwarded-For` — with the corresponding allow-list posture validator (`GatewaySecurityPostureValidator.java:114-127`).

### 8.5 Security headers

`BaseSecurityConfig` never calls `.headers(...)`, so Spring Security's **defaults** apply: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `X-XSS-Protection: 0`, `Cache-Control: no-cache, no-store, max-age=0, must-revalidate`, `Pragma: no-cache`, plus HSTS on HTTPS requests. No service disables them (`grep` for `frameOptions|contentTypeOptions|\.headers\(` over all `src/main` → 0 hits). Error responses are curated so internals do not leak: `server.error.include-message: never`, `include-stacktrace: never`, `include-exception: false` (`utils/common-spring/src/main/resources/application.yml:48-60`). **Proved correct.**

---

## 9. DEPENDENCY RISK

Declared versions read from the poms (no CVE numbers asserted — each line below needs a CVE scan against the declared version):

| Component | Declared version | Evidence | Risk note |
|---|---|---|---|
| Spring Boot | **4.1.1** | `pom.xml:9` (parent) | Current-generation; still needs a CVE scan against 4.1.1. |
| Java | **25** | `pom.xml:34-36` | — |
| Spring Cloud | **2025.1.0** | `pom.xml:42` | needs CVE scan |
| Keycloak server | **26.0** (`quay.io/keycloak/keycloak:26.0`) | `docker-compose.yml:214` | needs CVE scan; **not** pinned to a patch digest |
| Keycloak admin client | in-house REST client, **no `keycloak-admin-client` dependency** | `utils/common-keycloak/pom.xml` | the SDK is absent — the client is hand-written REST; no SDK CVE exposure but also no SDK fixes |
| Stripe Java SDK | **24.24.0** | `payment-service/pom.xml:79-82` | Declared inline in the module (not in `dependencyManagement`) → needs CVE scan |
| Elasticsearch Java client | **9.4.5** | `pom.xml:55`, `pom.xml:206-207` | **Major-version skew** vs the server image → SEC-18 |
| Elasticsearch server | **8.15.0** (`docker.elastic.co/elasticsearch/elasticsearch:8.15.0`) | `docker-compose.yml:135` | Needs CVE scan; a superseded 8.x line |
| OWASP dependency-check plugin | **13.0.0**, `failBuildOnCVSS 8` | `pom.xml:66, 368-386` | bound to phase **`verify`** — CI never reaches it → SEC-17 |
| SonarQube plugin | 4.0.0.4121 | `pom.xml:67` | — |
| Testcontainers | 2.0.5 | `pom.xml:73` | test scope |
| Gson | 2.13.2 | `pom.xml:52` | used by the Stripe SDK |
| AWS SDK | 2.54.2 | `pom.xml:71` | needs CVE scan |
| Liquibase | inherited from the Spring Boot BOM | — | needs CVE scan |
| PostgreSQL driver | inherited (BOM) | — | needs CVE scan |
| springdoc-openapi | 3.1.0 | `pom.xml:46` | needs CVE scan |
| Maven wrapper | pinned to `apache-maven-3.9.16` | `.mvn/wrapper/maven-wrapper.properties` | good (pinned) |

Other container images (needs CVE scan against the pinned tag): `postgres:16-alpine` (`docker-compose.yml:46`), `redis:7.4-alpine` (`:69`), `apache/kafka:3.9.0` (`:89`), `rustfs/rustfs:1.0.0-rc.5` (`:187`), `eclipse-temurin:25-jre-alpine` (`pom.xml:402`). `rustfs:1.0.0-rc.5` is a **release candidate** used as the production object store → SEC-30.

---

## 10. DEFECT LIST

Severity: **HIGH** = privilege/data exposure or a broken identity invariant; **MEDIUM** = a weakened control with a plausible path to impact; **LOW** = hardening / defence-in-depth; **INFO** = verified-correct posture worth recording.

---

### SEC-01 — auth-service resolves the JWT subject against a DIFFERENT primary-key space

* **Severity:** HIGH
* **Marked:** IDOR-adjacent / isolation-key defect (not a direct cross-user read)
* **Evidence:**
  * `auth-service/src/main/java/com/shop/authservice/controller/UserController.java:36` — `UUID userId = UUID.fromString(AuthenticatedUser.requireCurrent().id());`
  * `auth-service/src/main/java/com/shop/authservice/controller/UserController.java:51` — same, for `DELETE /me`
  * `auth-service/src/main/java/com/shop/authservice/controller/AddressController.java:36, 43, 51, 58, 64` — the same expression for all five address handlers
  * `utils/common-security/src/main/java/com/shop/common/security/jwt/AuthenticatedUser.java:39-46` → `id` = `JwtClaimExtractor.subject(jwt)` = the **Keycloak** user UUID
  * `auth-service/src/main/java/com/shop/authservice/entity/User.java:29-32` — `@Id @GeneratedValue(strategy = GenerationType.UUID) @Column(name = "user_id")` (locally generated)
  * `auth-service/src/main/java/com/shop/authservice/entity/User.java:63-64` — `keycloak_user_id` is a **separate** column
  * `auth-service/src/main/resources/db/changelog/changeset/004-password-reset-and-addresses.yaml:59-64` — `user_addresses.user_id` has a FK to `users(user_id)`
  * `auth-service/src/main/resources/db/changelog/changeset/001-create-users-and-roles.yaml:32-34` — `users.user_id` is the primary key
* **Actual behaviour:** `PUT /api/v1/users/me`, `DELETE /api/v1/users/me` and `POST /api/v1/users/me/addresses` resolve the Keycloak subject and look it up in `users(user_id)` / insert it into `user_addresses.user_id`, which is keyed by a *locally generated* UUID. The lookup misses (404 `auth.user.not.found.for.update`) and the address insert violates the FK (mapped to a generic 409 by `utils/common-spring/.../ApiExceptionHandler.java:196-208`). The self-service profile and address surfaces are non-functional for every real Keycloak user.
* **Violated invariant:** *"the principal identifier is the key of the row it addresses"* — the tenant/user key derived from the token is not the key of the row in auth-service's own tables. Every other service in the fleet (order, favourite, rating, cart) keys its rows by the Keycloak subject, so auth-service is the odd one out.
* **Root cause:** the local shadow row was given an independent surrogate key (`users.user_id`) while the Keycloak identity lives in a second column, and the controllers then chose the *token* key rather than resolving the row first. `UserServiceImpl.getCurrentAuthenticatedUser()` already resolves by **username** (`auth-service/.../UserServiceImpl.java:317-323`) — the correct pattern exists but was not used by the controllers.
* **Proposed fix:** resolve the local row before using its id. Add `Optional<User> findByKeycloakUserId(String)` to `UserRepository` and resolve `AuthenticatedUser.requireCurrent().id()` → `user.getId()` (or, better, align the id spaces by populating `users.user_id` with the Keycloak UUID at registration and dropping the separate generated key). Until then, change `UserController.java:36, 51` and `AddressController.java:36, 43, 51, 58, 64` to resolve through the username → local row path.
* **Reproduction test:** `auth-service` — register a user, obtain a Keycloak access token, then `PUT /api/v1/users/me` with `{"fullName":"X"}` and assert `200` (currently `404`); and `POST /api/v1/users/me/addresses` and assert `201` (currently `409`).
* **Acceptance criterion:** the self-service endpoints succeed for a freshly registered user, and `user_addresses.user_id` equals `users.user_id` for that user.

---

### SEC-02 — Cross-service IDOR: `verify-purchase` accepts an arbitrary `userId` with no principal cross-check

* **Severity:** HIGH
* **Marked:** **CONFIRMED IDOR**
* **Evidence:**
  * `order-service/src/main/java/com/shop/orderservice/controller/OrderController.java:81-92` — `@RequestParam UUID userId` fed straight into the query; `@PreAuthorize("hasAnyRole('SERVICE','ADMIN')")`; **no `ServiceCallerGuard` import or call in the class**
  * `order-service/src/main/java/com/shop/orderservice/service/impls/OrderServiceImpl.java:447-452` — `findDeliveredItemsByUserAndProduct(userId, productId, pageable)`
  * `order-service/src/main/java/com/shop/orderservice/repository/OrderItemRepository.java:21-30` — the JPQL filters on `:userId` only
  * `utils/common-spring/src/main/resources/application.yml:158` — `expected-client-ids` = `ecommerce-client,order-service,rating-service,search-service,product-service,media-service` (identical for **every** service)
  * `docker/keycloak/import/ecommerce-realm.json:170-209` — every service account carries the realm role `SERVICE`
  * `order-service/src/main/java/com/shop/orderservice/dto/response/OrderItemResponse.java` — response exposes `productId, productTitle, quantity, unitPrice, lineTotal`
* **Actual behaviour:** any client holding a token minted for one of the six accepted clients can call `GET /api/v1/orders/verify-purchase?userId=<any-uuid>&productId=<any-uuid>` and read another user's delivered purchase history — product titles, quantities, unit prices and line totals. The endpoint never checks that the caller is `rating-service`, and never compares `userId` with the caller's own subject.
* **Violated invariant:** *"a service-to-service endpoint that accepts a subject id must bind it to the calling client's scope"* — the `azp` allow-list is fleet-wide, so the SERVICE role is the only discriminator and it is shared by five clients.
* **Root cause:** the intended caller (`rating-service`, see `rating-service/.../eligibility/EligibilityClient.java:51-60`) is not enforced, unlike every other service-to-service endpoint in the fleet, which pairs `hasRole('SERVICE')` with `serviceCallerGuard.requireCaller("order-service")` (e.g. `inventory-service/.../InventoryController.java:87`, `payment-service/.../PaymentController.java:43`, `promotion-service/.../PromotionReservationController.java:39`).
* **Proposed fix:** (a) add `serviceCallerGuard.requireCaller("rating-service")` to `verifyPurchase`; and (b) narrow `shop.security.expected-client-ids` per service instead of shipping one fleet-wide list; and (c) drop the `userId` parameter from the public signature and derive it from the caller's token where the caller is acting on behalf of itself.
* **Reproduction test:** `order-service` — mint a `search-service` client-credentials token, call `GET /api/v1/orders/verify-purchase?userId=<victim>&productId=<any>` and assert `403` (currently `200` with the victim's items).
* **Acceptance criterion:** only `rating-service` (or an explicitly allow-listed client) can call the endpoint, and a subject id outside the caller's scope is rejected.

---

### SEC-03 — Committed literal credentials in `.env.example` for accounts the deployment *requires*

* **Severity:** HIGH
* **Marked:** **SECRET-LEAK CANDIDATE — CONFIRMED**
* **Evidence:**
  * `.env.example:237` — **Keycloak seeded ADMIN account password** (literal, well-known, 9 chars) — tracked file (`git ls-files` lists `.env.example`)
  * `.env.example:233` — Keycloak seeded USER account password (literal, well-known, 8 chars)
  * `.env.example:52` — Keycloak **master-realm** administrator username (literal, well-known, 5 chars)
  * `.env.example:22, 29, 80, 81, 99, 100` — Postgres / Redis / S3 access key / S3 secret key / Elasticsearch admin user / Elasticsearch admin password (literal)
  * `.env.example:157` — payment webhook HMAC secret (literal, 19 chars)
  * `docker-compose.yml:253` — `KEYCLOAK_ADMIN_USER_PASSWORD: ${KEYCLOAK_ADMIN_USER_PASSWORD:?required}` (the value the file above supplies)
  * `docker-compose.yml:236-237` — `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` required
  * `docker/keycloak/import/ecommerce-realm.json:151-168` — the seeded `adminuser` account holds realm roles `ADMIN` and `MANAGER`; `:161` is the password placeholder that the compose variable fills
  * `docker/keycloak/import/ecommerce-realm.json:132-150` — the seeded `testuser` account
  * `.env.prod.example:63-134` — the **prod** template uses non-random "change-me"-style placeholders (the correct pattern)
* **Actual behaviour:** the deployment contract is "copy `.env.example` to `.env` and start". Doing so provisions a Keycloak realm in which the `ADMIN`+`MANAGER` account has a password that is published in this git repository. Because the realm is the *only* authority for token content (`auth-service/.../RoleServiceImpl.java:24-34`), a login with that credential yields an `ADMIN` realm role and therefore passes `hasRole('ADMIN')` on every backoffice endpoint in the fleet and the gateway's `AdminRoleGateFilter`.
* **Violated invariant:** *"a shipped configuration template must never contain a working credential for a privileged account"* — the realm import already satisfies this (placeholders only, `docker/keycloak/import/ecommerce-realm.json:64, 77, 90, 103, 116, 129, 143, 161`); the env template does not.
* **Root cause:** the F-080 remediation moved the literal out of the realm JSON but did not remove it from `.env.example`; the "CHANGE IN PROD" trailing comment is advisory only and is trivially missed by a copy-and-start operator.
* **Proposed fix:** replace every credential value in `.env.example` with an empty value or a `generate-me` placeholder and add a pre-flight guard that refuses to start when a placeholder value is still in place (the compose file already uses the `:?required` idiom — add a `:?must be changed` check on the known placeholders). Rotate the seeded account password on every deployment, including the first.
* **Reproduction test:** a repository test that parses `.env.example`, collects every key whose name matches `PASSWORD|SECRET|ACCESS_KEY|ADMIN`, and fails if the value is non-empty and does not match a placeholder pattern.
* **Acceptance criterion:** `.env.example` contains no value that authenticates against any provisioned account; the seeded ADMIN password is unique per deployment.

---

### SEC-04 — No `aud` validation fleet-wide; a single shared `azp` allow-list for every service

* **Severity:** MEDIUM
* **Evidence:**
  * `utils/common-spring/src/main/resources/application.yml:158-159` — `expected-client-ids: …ecommerce-client,order-service,rating-service,search-service,product-service,media-service` and `expected-audiences: ${SHOP_SECURITY_EXPECTED_CLIENT_IDS:}` (empty)
  * `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:126-134` — the `aud` validator is only added when the list is non-empty
  * `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java:38-52` — documents why (the provisioned realm emits no `aud`)
  * `gateway-service/src/main/resources/application.yml:56` — the same six-client list at the edge
* **Actual behaviour:** the *only* client discriminator on any token is `azp`, and the accepted set is the union of all six fleet clients. A token minted for `media-service` is therefore accepted by `payment-service`, `order-service`, `tax-service`, … — each service accepts tokens issued to clients it never talks to. The service-to-service boundary is then re-established (or not) only by the ad-hoc `ServiceCallerGuard` calls, which SEC-02 shows are missing in one place.
* **Violated invariant:** *"a token presented to service X must have been minted for X's own callers"* — the tenant/client boundary is fleet-wide instead of per-service.
* **Root cause:** the realm has no audience mapper and no client scopes, so `aud` cannot be used; the compensating control was applied globally instead of per service.
* **Proposed fix:** set a per-service `shop.security.expected-client-ids` (e.g. `payment-service` → `order-service`; `order-service` → `order-service,ecommerce-client`), and add an audience mapper to the realm so `aud` can be enforced again.
* **Reproduction test:** present a `media-service` token to `payment-service` and assert `401` (currently accepted by the filter chain).
* **Acceptance criterion:** every service accepts only the client ids it actually calls.

---

### SEC-05 — The platform's own login is the deprecated ROPC grant, on a public client, with no MFA binding

* **Severity:** MEDIUM
* **Evidence:**
  * `docker/keycloak/import/ecommerce-realm.json:35-39` — `publicClient: true`, `directAccessGrantsEnabled: true`, `standardFlowEnabled: true`
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:53-64` — `form.add(GRANT_TYPE, PASSWORD)` — the fleet's login *is* ROPC
  * `auth-service/src/main/java/com/shop/authservice/health/KeycloakPasswordGrantPostureCheck.java:53-59` — the posture is only logged
  * `docker/keycloak/import/ecommerce-realm.json:41-51` — `redirectUris: ["http://localhost:*", "http://127.0.0.1:*", …]`, `webOrigins: ["*"]`
* **Actual behaviour:** the user's password is collected by the application and forwarded to Keycloak in a password grant; the flow carries no MFA binding, and the realm permits it for every deployment. The wildcard-port HTTP redirect URIs and `webOrigins: ["*"]` additionally permit an open-redirect surface on the authorization-code path that is enabled in parallel (`standardFlowEnabled: true`).
* **Violated invariant:** *"first-party authentication uses an authorization-code flow bound to PKCE and MFA"* — the OAuth 2.0 Security BCP deprecates ROPC.
* **Root cause:** first-party clients depend on the grant, so it cannot be removed unilaterally; the fleet only *measures* it.
* **Proposed fix:** migrate the web/mobile clients to Authorization Code + PKCE, then set `directAccessGrantsEnabled: false` on `ecommerce-client` and turn the posture warning into a startup failure (`GatewaySecurityPostureValidator`-style fail-fast). Narrow `redirectUris`/`webOrigins` to the exact production origins.
* **Reproduction test:** the existing `KeycloakPasswordGrantPostureCheckTest` already asserts the grant is measured — extend it to assert the deployment **fails to start** when the grant is enabled.
* **Acceptance criterion:** `directAccessGrantsEnabled` is `false` in the shipped realm, and no application code sends `grant_type=password`.

---

### SEC-06 — Token and session lifetimes are not pinned in the realm and are not asserted anywhere

* **Severity:** MEDIUM
* **Evidence:** `docker/keycloak/import/ecommerce-realm.json` (no `accessTokenLifespan`, `ssoSessionIdleTimeout`, `ssoSessionMaxLifespan`, `offlineSessionIdleTimeout`, `offlineSessionMaxLifespan`); `utils/common-keycloak/src/main/java/com/shop/common/keycloak/dto/KeycloakTokenResponse.java:14` (`refreshExpiresIn` is parsed but never used or asserted); `auth-service/.../dto/response/TokenResponse.java:12-17` (the response drops `refresh_expires_in` entirely)
* **Actual behaviour:** access-token TTL, refresh-token validity and idle/max session timeouts are whatever the Keycloak version happens to default to, and they change silently when the Keycloak image is upgraded (`docker-compose.yml:214` pins only the major version `26.0`). Clients cannot even learn the refresh lifetime — the relay drops `refresh_expires_in`.
* **Violated invariant:** *"security-relevant lifetimes are explicit, versioned configuration, not implicit defaults"*.
* **Root cause:** the realm import was authored for roles/clients and the lifetime knobs were never added.
* **Proposed fix:** pin `accessTokenLifespan`, `ssoSessionIdleTimeout`, `ssoSessionMaxLifespan` and `offlineSessionIdleTimeout` in `docker/keycloak/import/ecommerce-realm.json`; propagate `refreshExpiresIn` in `TokenResponse`; add a contract test (in the style of `auth-service/.../constant/RoleCatalogueContractTest.java`) that asserts the realm's lifetimes.
* **Reproduction test:** parse the realm JSON and assert the four lifetime keys exist and match the documented values.
* **Acceptance criterion:** every lifetime is explicit in the realm and covered by a failing-on-drift test.

---

### SEC-07 — Password-reset accepts a weaker password than registration

* **Severity:** MEDIUM
* **Evidence:**
  * `auth-service/src/main/java/com/shop/authservice/dto/request/ResetPasswordRequest.java:9-11` — `@Size(min = 6)` only, **no** `@Pattern`
  * `auth-service/src/main/java/com/shop/authservice/dto/request/RegisterRequest.java:18-22` — `@Size(min = 8, max = 50)` **+** `@Pattern("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[a-zA-Z\\d]{8,}$")`
  * `auth-service/src/main/java/com/shop/authservice/dto/request/ChangePasswordRequest.java:11-15` — same complexity rule
* **Actual behaviour:** `POST /api/v1/auth/reset-password` sets the Keycloak password to whatever the token holder supplies, including a 6-character all-lowercase password. The account ends up with a credential the platform would have refused to create. The endpoint is public (`auth-service/src/main/resources/application.yml:66`).
* **Violated invariant:** *"one password policy for the whole identity surface"*.
* **Root cause:** the reset DTO was authored without the complexity constraint the other two DTOs carry.
* **Proposed fix:** give `ResetPasswordRequest.newPassword` the same `@Size(min = 8, max = 50)` + `@Pattern` as `ChangePasswordRequest.newPassword`, and extract the rule into a shared constant.
* **Reproduction test:** `POST /api/v1/auth/reset-password` with a 6-char lowercase `newPassword` and assert `400` (currently accepted).
* **Acceptance criterion:** the reset path enforces the platform password policy.

---

### SEC-08 — The Keycloak error body is relayed to the client on non-401 auth failures

* **Severity:** MEDIUM
* **Evidence:**
  * `auth-service/src/main/java/com/shop/authservice/exception/AuthExceptionHandler.java:21-28` — `String message = status == HttpStatus.UNAUTHORIZED ? "Invalid username or password." : ex.getMessage();`
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:170-177` — `new KeycloakClientException("Token request failed: " + e.getResponseBodyAsString(), …)`
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:206-211, 243-248, 276-279, 304-310, 353-359, 388-394, 441-447, 477-482, 505-510, 532-537` — the same pattern for the admin path
* **Actual behaviour:** any Keycloak failure that is not a 401 (for example a 400 from a malformed grant, or a 500) puts the raw upstream response body into the HTTP response body of the auth API. The upstream body carries Keycloak's internal error text (and in some configurations the realm name, the client id and the failed grant details).
* **Violated invariant:** *"an upstream error body is not echoed to an untrusted caller"*.
* **Root cause:** `AuthExceptionHandler` treats the 401 case as the only sensitive one; every other status is relayed verbatim.
* **Proposed fix:** always answer with a curated message for the auth endpoints, keep the upstream body in the **log** only (it already is logged at ERROR in `KeycloakTokenClient.java:171`), and never in the response body.
* **Reproduction test:** force a non-401 Keycloak failure and assert the response body contains no substring of the upstream body.
* **Acceptance criterion:** no auth response body contains upstream Keycloak text.

---

### SEC-09 — The shipping webhook verifier has no replay window

* **Severity:** MEDIUM
* **Evidence:**
  * `shipping-service/src/main/java/com/shop/shippingservice/webhook/WebhookSignatureVerifier.java:18-34` — the verifier accepts **only** a bare 64-char hex HMAC over the raw body; there is no timestamp component and no tolerance check
  * `shipping-service/src/main/java/com/shop/shippingservice/service/impls/WebhookEventServiceImpl.java:59-65` — the signature is the only gate
  * `shipping-service/src/main/java/com/shop/shippingservice/webhook/CarrierWebhookController.java:22-29` — the endpoint is anonymous (`/api/v1/webhooks/shipping/**` is a public path, `shipping-service/src/main/resources/application.yml:47-48`)
  * `payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookSignatureVerifier.java:46, 88-99, 160-164` — the **payment** verifier *does* implement a ±300 s tolerance, but only for the Stripe-style `t=…,v1=…` header shape; the bare-hex branch at `:112-120` has none
* **Actual behaviour:** a captured carrier request (body + `X-Webhook-Signature`) can be replayed for as long as the carrier's secret is unchanged. The per-provider event-id de-duplication (`WebhookEventServiceImpl.java:77-82`) limits the *state* damage for events that carry a provider event id, but a body without a parsable/duplicate id is persisted and retried (`persistUnparseableEvent`, `:196-218`).
* **Violated invariant:** *"a webhook signature binds the body AND the moment it was produced"*.
* **Root cause:** the shipping verifier was implemented before the Stripe-style scheme existed and was never upgraded.
* **Proposed fix:** accept the `t=…,v1=…` shape in the shipping verifier too, enforce the same ±300 s tolerance, and add a `t=` requirement (bare-hex accepted only behind an explicit compatibility flag).
* **Reproduction test:** shipping-service — sign a body with a timestamp 1 hour old and assert the request is rejected.
* **Acceptance criterion:** every webhook verifier enforces a timestamp tolerance.

---

### SEC-10 — CI runs no SCA, no SAST and no secret scan; the OWASP plugin is bound to a phase CI never reaches

* **Severity:** MEDIUM
* **Evidence:**
  * `.github/workflows/ci.yml:24-28` — the only build steps are `./mvnw -T1C validate` and `./mvnw -T1C test`
  * `pom.xml:377-385` — `dependency-check-maven` is bound to phase **`verify`**, which CI never invokes
  * `pom.xml:66` — plugin version 13.0.0, `failBuildOnCVSS 8` (`pom.xml:373`)
  * `.github/` contains **only** `workflows/ci.yml` — no `dependabot.yml`, no code-scanning config, no secret-scanning config
  * `.github/workflows/ci.yml` — no `permissions:` block, no `concurrency:` block, no pinned action SHAs (`actions/checkout@v4`, `actions/setup-java@v4` are floating tags)
* **Actual behaviour:** a dependency with a known high-severity CVE is merged without any automated check. The OWASP scanner exists but is dormant in CI. There is no automated secret detection to catch a future commit of a credential (which is exactly how SEC-03 shipped).
* **Violated invariant:** *"every merge is gated by an automated dependency/secret scan"*.
* **Root cause:** the CI workflow was written before the OWASP plugin was added to the pom and was never extended.
* **Proposed fix:** add a CI job that runs `./mvnw -T1C verify -Ddependency-check.skip=false` (or `dependency-check:check`) with a cached NVD database, enable GitHub secret scanning + Dependabot, pin actions to commit SHAs, and set an explicit `permissions: contents: read` on the workflow.
* **Reproduction test:** a CI dry-run must fail when a deliberately vulnerable test dependency is introduced (or the workflow must reference the `verify` phase).
* **Acceptance criterion:** CI fails on a CVSS ≥ 8 dependency and on a detected secret.

---

### SEC-11 — The `verified-purchase` client discriminator is the fleet-wide `azp` list, and `azp` is absent for user tokens

* **Severity:** MEDIUM
* **Evidence:**
  * `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java:135-143` — the `azp` validator **rejects** a token with no `azp`
  * `utils/common-spring/src/main/resources/application.yml:141-158` — the fleet's own comment records the measured realm behaviour: "user token → azp=ecommerce-client, aud absent"
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakProperties.java:32` — the default client id is `ecommerce-client`, the **public** client
* **Actual behaviour:** the `azp` check is satisfiable only because every interactive login is minted for `ecommerce-client`, which is in the allow-list. The check therefore provides *no* discrimination between a human user and a service account — both carry an accepted `azp`; only the realm role differs. Any future realm change that mints user tokens for a different client id (or a client not in the list) takes the whole fleet offline, and the accepted set cannot be narrowed per service (SEC-04).
* **Violated invariant:** *"the client-identity check must discriminate between the caller classes it is meant to separate"*.
* **Root cause:** `azp` was chosen as a substitute for `aud` because the realm has no audience mapper; the substitute is a *client* discriminator, not a *caller-class* discriminator.
* **Proposed fix:** add an audience mapper (or a client scope) to the realm so `aud` carries the target service, and enforce `aud` per service (the validator already exists at `BaseSecurityConfig.java:126-134`); keep `azp` only as a secondary check.
* **Reproduction test:** issue a token for a client that is not in `expected-client-ids` and assert `401` at every service (proves the control is live), then flip the realm to emit `aud` and assert the `aud` validator engages.
* **Acceptance criterion:** `aud` is enforced per service and `azp` is no longer the primary discriminator.

---

### SEC-12 — Gateway edge permits `/api/v1/carts/**`, `/api/v1/ratings/**` and `/api/v1/medias/**` anonymously

* **Severity:** MEDIUM
* **Evidence:**
  * `gateway-service/src/main/resources/application.yml:62-86` — the `public-endpoints` list includes `/api/v1/carts`, `/api/v1/carts/**`, `/api/v1/ratings`, `/api/v1/ratings/**`, `/api/v1/medias`, `/api/v1/medias/**`
  * `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java:44-46` — `pathMatchers(publicPaths).permitAll()`
  * `order-service/src/main/resources/application.yml:43-45` — order-service does **not** make `/api/v1/carts/**` public (the gateway is more permissive than the origin)
* **Actual behaviour:** the edge performs no authentication on those prefixes; the request reaches the origin and is authenticated there (order-service: `CartController.resolveCartUserId`, `CartController.java:134-143`; rating-service: the fleet filter chain). The result is that a failure of the origin's own chain (a mis-scoped `public-paths` entry, a `@PreAuthorize` removal) would be invisible at the edge, and the edge's rate-limit/allow-list behaviour differs from the origin's.
* **Violated invariant:** *"the edge and the origin agree on which paths are anonymous"*.
* **Root cause:** the gateway's public list was written from the storefront's needs, not from the services' actual `public-paths`.
* **Proposed fix:** derive the gateway's public list from the union of the services' `shop.security.public-paths` and keep `/api/v1/carts/**` out of it (or make it method-scoped), so the edge is never more permissive than the origin.
* **Reproduction test:** a gateway route test asserting that `GET /api/v1/carts/me` without a token is rejected at the edge.
* **Acceptance criterion:** every gateway public path is also public in the target service.

---

### SEC-13 — Unbounded / zero-allowed quantity and amount fields

* **Severity:** LOW
* **Evidence:**
  * `order-service/src/main/java/com/shop/orderservice/dto/request/CartItemUpdateRequest.java:8` — `@NotNull @Min(0) @Max(99) Integer quantity` (zero is accepted)
  * `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/InventoryUpsertRequest.java:10` — `@NotNull @Min(0) Integer availableQuantity` (no upper bound)
  * `inventory-service/src/main/java/com/shop/inventoryservice/dto/request/ReserveRequest.java:9` — `@NotNull @Positive Integer quantity` (no upper bound)
  * `product-service/src/main/java/com/shop/productservice/dto/request/ProductCreateRequest.java:30-35` — `priceUnit` `@DecimalMin("0.0")` (no `@Digits`), `quantity` `@Min(0)` (no upper bound)
  * `product-service/src/main/java/com/shop/productservice/dto/request/ProductVariantCreateRequest.java:21-26` — same pattern
  * `promotion-service/src/main/java/com/shop/promotionservice/dto/request/CampaignRequest.java:25-27` — `maxRedemptions`, `totalBudget`, `perUserLimit` unbounded above
* **Actual behaviour:** a caller can create a cart line with quantity 0, request a reservation of `Integer.MAX_VALUE` units, or set a stock level of `Integer.MAX_VALUE`. The reservation path is service-to-service and guarded (`inventory-service/.../InventoryController.java:87`), but the *quantity* still reaches the reservation arithmetic unguarded.
* **Violated invariant:** *"a domain quantity has a documented upper bound"*.
* **Root cause:** the DTOs carry only lower bounds.
* **Proposed fix:** add `@Max` to every quantity/stock field (mirroring `CartItemAddRequest`'s `@Max(99)`) and `@Digits` to every monetary field.
* **Reproduction test:** a validation test asserting `quantity = 100` on the inventory upsert is rejected with 400.
* **Acceptance criterion:** every quantity/amount field has an upper bound and a scale bound.

---

### SEC-14 — `SearchRequest` accepts unbounded/negative price and rating filters

* **Severity:** LOW
* **Evidence:** `search-service/src/main/java/com/shop/searchservice/dto/request/SearchRequest.java:25-29` — `BigDecimal minPrice`, `BigDecimal maxPrice`, `BigDecimal minRating` carry **no** validation annotation, while `page`/`size` in the same record are fully bounded (`:36-42`)
* **Actual behaviour:** `minPrice=-999999` / `minRating=-5` are accepted and forwarded to the Elasticsearch query builder. The impact is limited to a filter that can widen or empty the result set.
* **Violated invariant:** *"numeric query parameters are bounded like every other numeric parameter"*.
* **Root cause:** the record was annotated for the pagination fields only.
* **Proposed fix:** add `@DecimalMin("0.00")` to `minPrice`/`maxPrice` and `@DecimalMin("0.0") @DecimalMax("5.0")` to `minRating`, and assert `minPrice <= maxPrice` in the compact constructor.
* **Reproduction test:** `GET /api/v1/search?minPrice=-1` must return `400`.
* **Acceptance criterion:** no unbounded numeric filter remains on the public search API.

---

### SEC-15 — `OrderReturnCreateRequest.refundAmount` has no scale bound

* **Severity:** LOW
* **Evidence:** `order-service/src/main/java/com/shop/orderservice/dto/request/OrderReturnCreateRequest.java:15-17` — `@NotNull @Positive BigDecimal refundAmount`, **no `@Digits`**; contrast `payment-service/.../dto/CreatePaymentRequest.java:14` (`@Digits(integer = 17, fraction = 2)`)
* **Actual behaviour:** an arbitrarily long decimal scale is accepted. The damage is bounded downstream — the cumulative refund is compared against the order total (`order-service/.../service/impls/OrderReturnServiceImpl.java:66`), so no over-refund is possible — but the value is persisted with unbounded scale.
* **Violated invariant:** *"a monetary value has a fixed precision and scale"*.
* **Root cause:** inconsistent DTO conventions between services.
* **Proposed fix:** `@Digits(integer = 17, fraction = 2)` to match the payment DTO.
* **Reproduction test:** a validation test with a 40-fraction-digit refund amount asserting `400`.
* **Acceptance criterion:** monetary DTOs share one precision/scale convention.

---

### SEC-16 — `ProductCreateRequest.priceUnit` / `weight` have no scale bound

* **Severity:** LOW
* **Evidence:** `product-service/src/main/java/com/shop/productservice/dto/request/ProductCreateRequest.java:29-31, 46-47` — `@DecimalMin("0.0")` with no `@Digits`; same in `ProductUpdateRequest.java:27, 32` and `ProductVariantCreateRequest.java:21-22` / `ProductVariantUpdateRequest.java:16-17`
* **Actual behaviour:** a price with an unbounded fraction is persisted; downstream money arithmetic (`PricingServiceImpl` in order-service) multiplies quantity × unit price.
* **Violated invariant:** *"a monetary value has a fixed precision and scale"*.
* **Root cause:** same as SEC-15.
* **Proposed fix:** `@Digits(integer = 17, fraction = 2)` on every price/weight field.
* **Reproduction test:** a validation test asserting a 20-fraction-digit price is rejected.
* **Acceptance criterion:** catalogue prices carry a fixed scale.

---

### SEC-17 — `forgot-password` leaks account existence through response latency

* **Severity:** LOW
* **Evidence:** `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java:334-367` — the unknown-account branch returns immediately after a single `findByEmail`; the known-account branch performs `deleteByUserId` + `insert` + a **synchronous SMTP dispatch** (`passwordResetEmailSender.sendResetEmail`, `:362`) before returning. The response *body* is identical for both branches (`:345` and `:366` — verified by `auth-service/.../UserServiceImplForgotPasswordLoggingTest.java:93-120`) but the latency differs by the whole DB + SMTP round trip.
* **Actual behaviour:** a caller can distinguish "account exists" from "account does not exist" by measuring the response time of `POST /api/v1/auth/forgot-password`.
* **Violated invariant:** *"the response to an enumeration probe must be indistinguishable in body **and** timing"*.
* **Root cause:** the log-line uniformity fix (F-057) addressed the log channel but not the timing channel.
* **Proposed fix:** dispatch the mail asynchronously (or push the work to a queue) so both branches return after the same work; or pad the unknown branch with a delay equal to the median dispatch time.
* **Reproduction test:** a timing test that asserts the p50 latency of the unknown-account branch is within a small factor of the known-account branch.
* **Acceptance criterion:** the two branches are indistinguishable in latency.

---

### SEC-18 — Elasticsearch client major version (9.x) vs server (8.15.0)

* **Severity:** LOW
* **Evidence:** `pom.xml:55` (`<elasticsearch-client.version>9.4.5</elasticsearch-client.version>`), `pom.xml:206-207` (`elasticsearch-java` 9.4.5); `docker-compose.yml:135` (`image: docker.elastic.co/elasticsearch/elasticsearch:8.15.0`)
* **Actual behaviour:** the 9.x Java client is used against an 8.15.0 server. The 9.x client expects the 8.18+/9.x REST contract; request parameters or response fields introduced after 8.15 can silently be ignored or rejected. The server line (8.15.0) is also not the newest 8.x and is not digest-pinned.
* **Violated invariant:** *"the client and the server are on a supported version pair"*.
* **Root cause:** the client was upgraded independently of the compose image.
* **Proposed fix:** align the client to the server line (or upgrade the server image to a version the 9.x client officially supports) and pin both to digests; run a CVE scan against 8.15.0 and against `elasticsearch-java` 9.4.5.
* **Reproduction test:** the existing `SearchQueryIT` / `ReindexIT` against a matching ES container version (Testcontainers `elasticsearch` is already declared, `search-service/pom.xml:89`) — assert the client version and the container version match.
* **Acceptance criterion:** client and server versions are in the compatibility matrix published by Elastic.

---

### SEC-19 — `UUID.fromString(jwt.sub)` throws on a service-account token

* **Severity:** LOW
* **Evidence:** `auth-service/src/main/java/com/shop/authservice/controller/UserController.java:36, 51` and `auth-service/src/main/java/com/shop/authservice/controller/AddressController.java:36, 43, 51, 58, 64` — `UUID.fromString(AuthenticatedUser.requireCurrent().id())`; a Keycloak `client_credentials` token carries `sub = "service-account-<clientId>"` (documented at `utils/common-logging/.../AuditActorResolver.java:16-19`), which is **not** a UUID
* **Actual behaviour:** a `SERVICE`-role token that reaches these endpoints raises `IllegalArgumentException`, which no `@ExceptionHandler` covers → the generic 500 path. The request fails closed (no data access), but the response is a server error rather than a 403, and the stack is logged at ERROR.
* **Violated invariant:** *"an untrusted identifier shape produces a 4xx, never a 500"*.
* **Root cause:** the principal id is parsed as a UUID at the controller boundary without a shape check.
* **Proposed fix:** resolve the row by username (`UserServiceImpl.getCurrentAuthenticatedUser()`, `UserServiceImpl.java:317-323`) — which is the same fix as SEC-01 — or guard the parse and return 403.
* **Reproduction test:** call `PUT /api/v1/users/me` with a `search-service` client token and assert `403` (currently 500).
* **Acceptance criterion:** no endpoint returns 500 for a well-formed but unusual principal.

---

### SEC-20 — `ServiceCallerGuard.requireCaller` fails open when the security context is empty

* **Severity:** LOW
* **Evidence:** `utils/common-security/src/main/java/com/shop/common/security/service/ServiceCallerGuard.java:28-34`
```java
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            // No authentication at all: the endpoint's own @PreAuthorize is the
            // authority on whether the request may proceed, and the guard only
            // binds an AUTHENTICATED service caller to its owner.
            return;
        }
```
* **Actual behaviour:** the guard returns **without error** when the context holds no authentication. In the servlet stack this is unreachable today (the filter chain always populates an `AnonymousAuthenticationToken`, which is non-null, so the guard falls through to the `azp` check and throws `FORBIDDEN`), but the branch is a fail-open default: any future code path that clears or short-circuits the security context before the guard runs silently disables the client check.
* **Violated invariant:** *"a guard must fail closed"*.
* **Root cause:** the null case was treated as "not my concern".
* **Proposed fix:** make the null case throw `FORBIDDEN` (the endpoint's own `@PreAuthorize` already guarantees a principal is present, so the branch can only ever be a bug).
* **Reproduction test:** a unit test that calls `requireCaller("order-service")` with an empty `SecurityContextHolder` and asserts `FORBIDDEN`.
* **Acceptance criterion:** `requireCaller` never returns without an authentication present.

---

### SEC-21 — The Keycloak master-realm password grant remains reachable as a fallback

* **Severity:** LOW
* **Evidence:**
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java:414-416` — service-account path preferred; `:418-422` — the master-realm `password` grant is used when `adminClientSecret` is blank
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakProperties.java:36-42` — `adminRealm = "master"`, `adminClientId = "admin-cli"`, `adminUsername`/`adminPassword`
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/config/KeycloakAutoConfiguration.java:83-99` — the admin client is created if **either** the master credential **or** the service-account secret is present
* **Actual behaviour:** if the operator sets only `KEYCLOAK_ADMIN_USERNAME`/`KEYCLOAK_ADMIN_PASSWORD` (which `.env.example:52-53` still offers), every admin call authenticates against the **master** realm with a plain password grant — a realm-wide administrator credential travelling on every call, outside the application realm's scope.
* **Violated invariant:** *"the admin path is realm-scoped and uses a machine credential"*.
* **Root cause:** the legacy path was kept as a silent fallback instead of being removed or made an explicit opt-in.
* **Proposed fix:** require `shop.keycloak.admin-client-secret` for the admin client to exist, and delete the master-realm password grant path (or gate it behind an explicit `shop.keycloak.master-admin-legacy-enabled=true` that fails the startup posture check).
* **Reproduction test:** a startup test with only `admin-username`/`admin-password` set that asserts the admin client is **not** created.
* **Acceptance criterion:** the admin path always uses `client_credentials` against the application realm.

---

### SEC-22 — `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` defaults to 0 in the base compose

* **Severity:** LOW
* **Evidence:**
  * `docker-compose.yml:302` — `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS: ${GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS:-0}`
  * `.env.example` — the same variable defaults to `0`
  * `gateway-service/src/main/resources/application.yml:99` — the *application* default is `1`
  * `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java:16-24` — with `hops == 0` the resolver returns the **socket** address
  * `gateway-service/src/main/java/com/shop/gateway/config/GatewaySecurityPostureValidator.java:114-127` — the fail-fast only triggers when `hops <= 0` **and** `forward-headers-strategy != none`
  * `docker-compose.prod.yml:62` — the prod overlay correctly forces `1`
* **Actual behaviour:** the base compose is safe only because no reverse proxy sits in front of it. If the base compose is deployed behind the reverse proxy that `docker-compose.prod.yml:24-28` mandates, `hops = 0` makes the client IP resolve to the **proxy's** address: the admin IP allowlist (`AdminIpAllowlistFilter.java:84-88`) then evaluates the proxy instead of the caller, and every anonymous caller shares one rate-limit bucket.
* **Violated invariant:** *"the client-IP trust model matches the deployed topology"*.
* **Root cause:** three different defaults for the same variable (application yml = 1, compose = 0, prod overlay = 1) with no single source of truth.
* **Proposed fix:** make `1` the default everywhere except an explicitly proxy-less local profile; document the dependency in `.env.example` next to the variable.
* **Reproduction test:** a gateway test asserting that with `hops = 0` and a proxy in the chain the admin allowlist evaluates the proxy address (documents the hazard), plus a lint rule that the compose default equals the application default.
* **Acceptance criterion:** one default for `GATEWAY_RATE_LIMIT_TRUSTED_PROXY_HOPS` across all layers.

---

### SEC-23 — No HTTPS enforcement, no HSTS and no CSP at the application layer

* **Severity:** LOW
* **Evidence:**
  * `grep` over all `src/main` for `requiresChannel|HSTS|Strict-Transport|server.ssl` → **0 hits**
  * `docker-compose.yml:234` — Keycloak is started with `--hostname=http://keycloak:8080` (clear-text issuer inside the network)
  * `docker-compose.prod.yml:24-28` — TLS is delegated to an external reverse proxy that the repository does not provide
  * `utils/common-security/.../BaseSecurityConfig.java:52-89` — no `.headers(...)` call, so Spring's defaults apply (HSTS only for requests seen as HTTPS)
* **Actual behaviour:** the applications emit no `Strict-Transport-Security` and no CSP. `KEYCLOAK_PUBLIC_SERVER_URL` defaults to `http://localhost:9090` (`utils/common-spring/src/main/resources/application.yml:183`), i.e. a clear-text browser-facing identity endpoint by default. The bearer token therefore travels in clear text unless the operator terminates TLS and hardens the public URL.
* **Violated invariant:** *"the platform's own defaults are safe for a browser-facing deployment"*.
* **Root cause:** TLS was delegated to the edge, and no deployment-time guard enforces that the delegation actually happened.
* **Proposed fix:** add a startup posture check that fails when `KEYCLOAK_PUBLIC_SERVER_URL` is not `https://` outside a dev profile, add an explicit `headers(...)` block enabling HSTS (`max-age=31536000; includeSubDomains`) and a CSP for the API surfaces, and change the public-server-url default to `https://`.
* **Reproduction test:** a startup test asserting that a non-`https` public server URL in the `prod` profile aborts the boot.
* **Acceptance criterion:** a production deployment refuses to start with a clear-text public identity URL.

---

### SEC-24 — Stripe search query built by string concatenation

* **Severity:** LOW
* **Evidence:** `payment-service/src/main/java/com/shop/paymentservice/provider/StripeProvider.java:116-124`
```java
        PaymentIntentSearchParams searchParams = PaymentIntentSearchParams.builder()
                .setQuery("metadata['payment_id']:'" + paymentId + "'")
                .setLimit(1L)
                .build();
```
* **Actual behaviour:** `paymentId` is a server-generated `UUID` (`payment-service/.../entity/Payment.java:50` region), so it is **not** attacker-controlled today and the concatenation cannot be exploited. The pattern is nonetheless an injection sink: if the id ever becomes client-supplied (e.g. a request DTO), a crafted value would alter the Stripe search query and could select a **different** payment intent — i.e. a refund against someone else's payment.
* **Violated invariant:** *"a query language fragment is never assembled by concatenation"*.
* **Root cause:** the Stripe SDK offers no parameterised search-builder for this query shape.
* **Proposed fix:** validate `paymentId` against a strict UUID pattern before building the query and fail closed on a mismatch; add a unit test asserting a non-UUID id is rejected before the SDK call.
* **Reproduction test:** `refund` with a non-UUID payment id must throw before any Stripe call.
* **Acceptance criterion:** the query is either built from validated components or replaced with a local `provider_reference` column (the file's own comment at `:110-115` notes that column is missing).

---

### SEC-25 — Carrier request body built by string concatenation

* **Severity:** LOW
* **Evidence:** `shipping-service/src/main/java/com/shop/shippingservice/carrier/CarrierShipmentClient.java:56-62`
```java
            var response = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiToken)
                    .body("{\"order_id\":\"" + orderId + "\"" + (shopId == null ? "" : ",\"shop_id\":\"" + shopId + "\"") + "}")
```
* **Actual behaviour:** `orderId` is a UUID and `shopId` is configuration, so no user input reaches the body today. The pattern is still fragile: a configuration value containing `"` would break the JSON (and `shopId` is an operator-editable env value).
* **Violated invariant:** *"a JSON body is serialised by the JSON mapper"*.
* **Root cause:** the `ObjectMapper` is present in the class but is only used for the response.
* **Proposed fix:** build the body with `objectMapper.writeValueAsString(Map.of("order_id", orderId, "shop_id", shopId))`.
* **Reproduction test:** a unit test with a `shopId` containing a quote asserting the request body is still valid JSON.
* **Acceptance criterion:** no request body is assembled by string concatenation.

---

### SEC-26 — A client secret is configured for a **public** Keycloak client

* **Severity:** LOW
* **Evidence:**
  * `docker/keycloak/import/ecommerce-realm.json:35` — `"publicClient": true` for `ecommerce-client`
  * `docker-compose.yml:339, 356` — `SHOP_KEYCLOAK_CLIENT_SECRET` / `KEYCLOAK_CLIENT_SECRET` are wired from the environment
  * `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java:59-61` — the secret is added to the password-grant form when non-empty
  * `.env.example:62` — `KEYCLOAK_CLIENT_SECRET` is present in the template (value empty there)
* **Actual behaviour:** a confidential-client secret is collected and transmitted for a client Keycloak treats as public; Keycloak ignores it, so the value is inert but it is a secret-shaped string in the process environment, in the compose file and in the environment of a service that also handles user passwords.
* **Violated invariant:** *"a public client has no client secret"*.
* **Root cause:** the secret variable was retained from the pre-ROPC-configuration era.
* **Proposed fix:** remove `KEYCLOAK_CLIENT_SECRET` from the compose wiring and the template for the public client; if a confidential client is ever needed for the password grant, convert `ecommerce-client` to `publicClient: false` and manage the secret accordingly.
* **Reproduction test:** a realm contract test asserting that a `publicClient: true` client has no `secret` and that no `*_CLIENT_SECRET` env var is wired for it.
* **Acceptance criterion:** public clients carry no client secret anywhere in the deployment.

---

### SEC-27 — `auth-service` public-path rules are method-agnostic

* **Severity:** INFO
* **Evidence:** `auth-service/src/main/resources/application.yml:60-66` — six rules with no `method:` key; `utils/common-security/.../config/BaseSecurityConfig.java:73-79` — a rule without a method matches **every** method
* **Actual behaviour:** `/api/v1/auth/login` is anonymous for GET, PUT, DELETE and PATCH as well as POST. No handler exists for those methods today (the endpoints are POST-only), but a future non-POST handler added under one of those paths would be anonymous by default, with no compile-time or start-time signal.
* **Violated invariant:** *"an anonymous path rule should be as narrow as the handler it exposes"*.
* **Root cause:** convenience — the rule list was written for the POST handlers that exist.
* **Proposed fix:** add `method: POST` to each auth rule (the `EndpointRule` record already supports it and the branch at `BaseSecurityConfig.java:74-77` is exercised by the test fixture `auth-service/src/test/resources/security-filter-chain-fixture.yml:25-26`).
* **Reproduction test:** extend `auth-service/.../security/SecurityFilterChainIntegrationTest.java` with a `GET /api/v1/auth/login` case asserting `401`.
* **Acceptance criterion:** every anonymous rule is method-scoped unless the path is intentionally method-agnostic.

---

### SEC-28 — Seeded credential pairs are documented in tracked markdown

* **Severity:** MEDIUM
* **Marked:** **SECRET-LEAK CANDIDATE — CONFIRMED**
* **Evidence:** `HANDOFF.md:31` states the Keycloak administrator username/password pair and the seeded `testuser` password in prose (kinds: Keycloak admin password, Keycloak user password); the same literals recur in `docs/PRODUCTION-READINESS.md` and in `docs/review/REVIEW-REPORT-2026-09-02.md`, `docs/review/REVIEW-REPORT-2026-09-03.md`, `docs/review/auth-favourite-gateway.md`, `docs/review/infra-config.md`, `docs/review/inventory-rating.md`, `docs/review/order-service.md`, `docs/review/pattern-verification.md`, `docs/review/product-service.md` (grep for the two known literals across `*.md`)
* **Actual behaviour:** the credential pair for the realm's `ADMIN`+`MANAGER` account — the same literal that `.env.example:237` ships — is published in the repository in plain text, alongside the Keycloak administrator credentials.
* **Violated invariant:** *"credentials are never written to a tracked document"*.
* **Root cause:** the runbooks were written against the pre-F-080 realm, which carried the literal password.
* **Proposed fix:** replace every credential literal in the tracked markdown with a placeholder and a reference to the environment variable that supplies it; scrub the values from the git history (or accept and document the residual risk after rotating the affected credentials).
* **Reproduction test:** a CI grep gate that fails when a tracked `.md` file contains a value matching the seeded-account password pattern.
* **Acceptance criterion:** no tracked file contains a working credential for any provisioned account.

---

### SEC-29 — The `avatar` URL is persisted and returned but never validated against internal destinations

* **Severity:** LOW
* **Evidence:**
  * `auth-service/src/main/java/com/shop/authservice/dto/request/RegisterRequest.java:36-37` — `@Pattern(regexp = "^(https?://)\\S+$", message = "Avatar URL must be a valid HTTP or HTTPS URL")`
  * `auth-service/src/main/java/com/shop/authservice/dto/request/UpdateUserRequest.java:21-22` — same pattern
  * `auth-service/src/main/java/com/shop/authservice/entity/User.java:60-61` — the value is persisted and returned in `UserResponse.avatar`
* **Actual behaviour:** the pattern permits `http://` (clear-text) and any host, including `http://169.254.169.254/…` or `http://localhost:8080/…`. The value is **not dereferenced by any code today** (no HTTP client references it), so there is no SSRF today — but the stored value is returned to every client that reads a user profile, including the backoffice, and a future avatar renderer would inherit an SSRF sink with a pre-populated internal-host value.
* **Violated invariant:** *"a user-supplied URL is validated against a destination policy before it is stored, not only when it is fetched"*.
* **Root cause:** the validation is a syntactic URL check, not a destination policy.
* **Proposed fix:** require `https://`, resolve-and-reject private/loopback/link-local addresses at write time (and again at fetch time), and cap the length.
* **Reproduction test:** a validation test asserting `http://169.254.169.254/latest/meta-data/` is rejected at registration.
* **Acceptance criterion:** only `https` public destinations are accepted for `avatar`.

---

### SEC-30 — The object store is a release candidate and the CI image digests are unpinned

* **Severity:** LOW
* **Evidence:** `docker-compose.yml:187` — `image: rustfs/rustfs:1.0.0-rc.5`; `docker-compose.yml:46, 69, 89, 135, 214` — all other images are also tag-pinned, not digest-pinned
* **Actual behaviour:** the S3-compatible object store that holds every media object runs an **RC** build in the shipped topology. Tag pins (not digests) mean the image content behind a tag can change under the deployment.
* **Violated invariant:** *"production images are pinned to an immutable artefact"*.
* **Root cause:** convenience of the dev compose.
* **Proposed fix:** pin every image by digest in the production overlay and move the object store to a stable release before production.
* **Reproduction test:** a compose lint that asserts every `image:` in the prod overlay is digest-pinned.
* **Acceptance criterion:** no release-candidate image is used in a production topology and all images are digest-pinned.

---

## 11. PROVED-CORRECT POSTURE (verified, not assumed)

These are explicit **negative** findings — each was checked against source and found sound.

1. **Default-deny everywhere.** `BaseSecurityConfig.java:83` (`anyRequest().authenticated()`), `SecurityConfig.java:46` (`anyExchange().authenticated()`); all 13 servlet services reach the chain through `common-spring → common-security` (`utils/common-spring/pom.xml:20-22`).
2. **No custom `SecurityFilterChain` overrides** — `BaseSecurityConfig.securityFilterChain` is `@ConditionalOnMissingBean(SecurityFilterChain.class)` (`:53`) and no service declares one.
3. **Issuer + timestamp validation on every token** (`BaseSecurityConfig.java:124-125`), plus `azp` (`:135-143`).
4. **Self-registration cannot grant a privileged role.** `auth-service/.../UserServiceImpl.java:247-266` — the server-side whitelist is `Set.of("USER")`; ADMIN/SERVICE/MANAGER in the request body are dropped. Covered by `auth-service/.../UserServiceImplTest.java:467-531`.
5. **Role grants reach the realm, not just the local mirror.** `auth-service/.../RoleServiceImpl.java:68` (`keycloakAdminClient.assignRealmRole(...)`) runs **before** the local write, and the mirror can only claim what the realm granted (`:121-133`, `:149-160`). Covered by `auth-service/.../RoleServiceImplTest.java:110-152`.
6. **Password-reset tokens are single-use and atomically consumed.** `auth-service/.../UserServiceImpl.java:391` + `auth-service/.../repository/PasswordResetTokenRepository.java:31-34` (conditional `UPDATE … WHERE used = false`); hashed at rest (`:419-427`); 15-minute TTL (`:357`). Covered against a real PostgreSQL by `auth-service/.../repository/PasswordResetTokenConsumptionTest.java:139-180`.
7. **Credential change revokes live sessions.** `auth-service/.../UserServiceImpl.java:160, 398` → `KeycloakAdminClient.logoutUser` (`utils/common-keycloak/.../KeycloakAdminClient.java:295-311`).
8. **The reset flow does not enumerate accounts through the log.** Both branches write the identical line (`UserServiceImpl.java:345` and `:366`); verified by `auth-service/.../UserServiceImplForgotPasswordLoggingTest.java:93-120`.
9. **The reset email is not a no-op.** `auth-service/.../UnconfiguredPasswordResetEmailSender.java:24-26` fails the request (503 `AUTH-1010`) instead of pretending a mail was sent; the SMTP sender refuses to start without a from-address and a reset base URL (`SmtpPasswordResetEmailSender.java:38-50`).
10. **No SQL built by concatenation** — §7.1.
11. **No SSRF sink reachable from user input** — §7.2.
12. **No path traversal** — §7.3.
13. **Webhook authenticity** — payment: the official Stripe SDK verifier (`payment-service/.../service/WebhookEventService.java:84-85`) plus a constant-time HMAC verifier (`payment-service/.../webhook/WebhookSignatureVerifier.java:109, 117`); shipping: constant-time HMAC over the raw body (`shipping-service/.../webhook/WebhookSignatureVerifier.java:26-30`) with a per-carrier secret (`shipping-service/.../service/impls/WebhookEventServiceImpl.java:61-65`). The Stripe path **fails closed** when no secret is configured (`WebhookEventService.java:76-81`).
14. **CORS is not wildcard by default** and the gateway refuses to boot with `*` + credentials — §8.1.
15. **CSRF disabled is safe** — stateless, no cookies, no spring-session — §8.2.
16. **Error responses do not leak internals** — `server.error.include-message: never`, `include-stacktrace: never`, `include-exception: false` (`utils/common-spring/src/main/resources/application.yml:55-60`) and a curated global handler (`utils/common-spring/.../ApiExceptionHandler.java`).
17. **`.env` is gitignored and untracked**; the realm import carries placeholders only — §5.1, §5.5.
18. **The audit actor is derived from the verified token, never from a header** — `utils/common-logging/.../AuditActorResolver.java:44-63`.
19. **`sortBy` cannot inject a sort property** — `auth-service/.../UserServiceImpl.java:49-51, 210` (an allow-list) plus `size` clamped to ≤ 100 (`:209`).
20. **The guest-cart capability cannot be enumerated** — the id must be a canonical UUID (`order-service/.../CartController.java:59-60, 150-155`) and the merge endpoint requires header/query agreement (`:122-132`).
21. **Order tracking is gated by a token and lock-out-protected on the legacy phone path** — `order-service/.../OrderTrackingController.java:34-47` and `OrderServiceImpl.java:481-484`.

---

## 12. RESIDUAL RISK / NOT COVERED

* The `notification-service` has no user-facing endpoint in this revision (only `BackofficeNotificationController`), so its `Notification.userId` column was exercised only by grep — no user-isolation path to audit.
* `media-service`'s `MediaLifecycleServiceImpl` reads the principal via `AuthenticatedUser`; the surrounding purge/reconciliation logic was reviewed for authorization only, not for its media-lifecycle correctness.
* `k8s/`, `scripts/`, `load-tests/` and `mock-services/` were not in the declared scope and were not read.
* No CVE numbers are asserted anywhere in this report. Every dependency listed in §9 needs a CVE scan against the **declared** version.
* The report does not verify runtime behaviour: no service was started and no request was issued. All findings are derived from source at revision `a2b6e6fc336f42f851166cab0cf0d1bba42db12f`.
