# Security & Identity

Generated: 2026-11-10T10:05:42

Findings in this category: **18**

| ID | Priority | Status | Title |
|---|---|---|---|
| F-004 | P1 | OPEN | OAuth2 ROPC grant used for end-user login and for the Keycloak master-realm admin token |
| F-005 | P1 | VERIFIED | JWT audience validation is implemented but never configured - a token for any client in the realm is accepted  |
| F-006 | P2 | OPEN | Shared default config ships default Keycloak master-realm admin credentials and a wildcard CORS origin |
| F-007 | P3 | OPEN | Contradictory bean-validation constraints on SecurityProperties.Cors.maxAgeSeconds |
| F-008 | P3 | OPEN | Object-storage access/secret key default to rustfsadmin in the shared library |
| F-009 | P2 | OPEN | server.error.include-message=always exposes internal exception text to API clients |
| F-020 | P1 | VERIFIED | Service-to-service client secret defaults to 'changeme' in order-service |
| F-024 | P2 | VERIFIED | Webhook signature verifier returns HTTP 500 (not 401) for a malformed 64-character v1 hex value |
| F-028 | P2 | OPEN | mergeCart lets any authenticated user claim any guest cart by guessing its id |
| F-031 | P1 | VERIFIED | Client-IP trust model is not fail-safe: with the shipped trusted-proxy-hops=0 the admin IP allowlist and the a |
| F-032 | P1 | VERIFIED | The gateway performs no JWT audience validation - a token minted for any client in the realm is accepted at th |
| F-033 | P1 | VERIFIED | Gateway CORS combines allowCredentials=true with the shipped wildcard origin pattern, enabling cross-origin cr |
| F-035 | P2 | OPEN | The backoffice admin IP allowlist is INACTIVE by default in every shipped configuration |
| F-040 | P1 | VERIFIED | The provisioned Keycloak realm issues tokens with NO 'aud' claim - the audience validator is unsatisfiable, an |
| F-041 | P1 | OPEN | The SERVICE realm role is a fleet-wide super-privilege: any service client token can call any service's SERVIC |
| F-056 | P3 | OPEN | The local role catalogue (USER, PM, ADMIN) does not match the provisioned Keycloak realm roles (ADMIN, USER, M |
| F-057 | P3 | OPEN | forgotPassword logs the email address of a non-existent account |
| F-069 | P2 | OPEN | The admin IP allowlist uses a prefix match without a path boundary, so a path that merely starts with a public |

---

### F-004 - OAuth2 ROPC grant used for end-user login and for the Keycloak master-realm admin token

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-keycloak, auth-service
- **Source files:**
  - `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakTokenClient.java`
  - `utils/common-keycloak/src/main/java/com/shop/common/keycloak/client/KeycloakAdminClient.java`
- **Actual implementation:** KeycloakTokenClient.login() posts grant_type=password with the end user's username and password. KeycloakAdminClient.getAdminAccessToken() posts grant_type=password to the master realm with admin-cli plus admin username/password.
- **Expected:** Authorization Code + PKCE for user auth; client_credentials (or a service-account token) for the admin path. ROPC is deprecated by the OAuth 2.0 Security BCP.
- **Trigger:** Every user login and every admin operation.
- **Root cause:** Password grant chosen for first-party login convenience.
- **Consequences:** Users disclose their password to the client; no MFA binding; the master-realm admin credential flows through a plain password grant on every admin call.
- **Required remediation:** Assess migration to Authorization Code + PKCE and to a service-account client for admin operations. Retain ROPC only with a documented client-compatibility decision.
- **Regression test:** Contract test against Keycloak asserting the grant type per flow; negative test that a client with ROPC disabled fails closed.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-005 - JWT audience validation is implemented but never configured - a token for any client in the realm is accepted by every service

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** utils/common-security
- **Source files:**
  - `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java`
  - `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java`
  - `utils/common-spring/src/main/resources/application.yml`
- **Actual implementation:** BaseSecurityConfig adds a JwtClaimValidator for 'aud' only when shop.security.expected-audiences is non-empty. A grep for 'expected-audiences' across every application.yml returns no match, so the installed validator chain is issuer + timestamp only in every deployment.
- **Expected:** Each resource server validates that the token audience contains the client id it serves.
- **Trigger:** Any request carrying a realm token minted for a different client.
- **Root cause:** A security control implemented behind a default-empty property that no service sets.
- **Consequences:** Horizontal privilege escalation: a token issued to a low-privilege client is honoured by a high-privilege service.
- **Required remediation:** Set shop.security.expected-audiences per service (and platform-wide) so the aud validator is actually installed.
- **Regression test:** Integration test: a JWT with aud=[other-client] must be rejected with 401 by each service's decoder.
- **Remediation applied:** Superseded and closed by F-040: the 'aud' validator was unsatisfiable against the provisioned realm (no audience mapper), so the fleet now validates the 'azp' claim through shop.security.expected-client-ids, which is populated with the realm's actual client ids in common-spring's shared defaults and in the gateway's gateway.security.expected-client-ids. The 'aud' path is retained but documented as requiring an audience mapper first.
- **Verification evidence:** Full reactor: 3324 tests / 0 failures / 0 errors. The azp validator is covered by BaseSecurityConfigClientIdValidationTest (4 cases) and GatewayJwtSecurityConfigTest (3 cases).
### F-006 - Shared default config ships default Keycloak master-realm admin credentials and a wildcard CORS origin

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-spring
- **Source files:**
  - `utils/common-spring/src/main/resources/application.yml`
  - `.env.example`
  - `docker-compose.yml`
- **Actual implementation:** shop.keycloak.admin-username and admin-password default to admin/admin; CORS_ALLOWED_ORIGIN_PATTERNS defaults to '*' in .env.example line 157; docker-compose.yml requires KEYCLOAK_ADMIN_PASSWORD but .env.example ships 'admin'.
- **Expected:** No credential default inside a shared library; wildcard origins are never the shipped default.
- **Trigger:** A deployment that starts the platform without overriding the environment variables.
- **Root cause:** Developer-friendly defaults retained in a library that production consumes.
- **Consequences:** Master-realm admin takeover if the default password survives into a real environment; any-origin reads on unauthenticated endpoints.
- **Required remediation:** Remove credential defaults (fail fast when unset) and make the CORS default an explicit localhost list.
- **Regression test:** Config test asserting the shared application.yml contains no resolvable credential default.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-007 - Contradictory bean-validation constraints on SecurityProperties.Cors.maxAgeSeconds

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-security
- **Source files:**
  - `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java`
- **Actual implementation:** The property carries both @Min(0) and @Positive; @Positive implies > 0 so @Min(0) is dead.
- **Expected:** One unambiguous constraint.
- **Trigger:** Configuration binding with maxAgeSeconds=0.
- **Root cause:** Overlapping annotations left from an edit.
- **Consequences:** maxAgeSeconds=0 is rejected although @Min(0) implies it should be allowed.
- **Required remediation:** Remove @Min(0) or @Positive, keeping the intended rule.
- **Regression test:** Validation test binding maxAgeSeconds=0 and asserting the documented behaviour.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-008 - Object-storage access/secret key default to rustfsadmin in the shared library

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-storage
- **Source files:**
  - `utils/common-storage/src/main/java/com/shop/common/storage/config/StorageProperties.java`
- **Actual implementation:** StorageProperties defaults accessKey and secretKey to 'rustfsadmin'.
- **Expected:** No credential defaults; fail fast when unset.
- **Trigger:** A service that enables shop.storage without overriding the credentials.
- **Root cause:** Local-development convenience defaults.
- **Consequences:** A deployment could point at a real bucket using the default RustFS admin credential.
- **Required remediation:** Default to blank and validate at startup when storage is enabled.
- **Regression test:** Configuration test asserting blank-by-default credentials and a startup failure when enabled without credentials.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-009 - server.error.include-message=always exposes internal exception text to API clients

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** utils/common-spring
- **Source files:**
  - `utils/common-spring/src/main/resources/application.yml`
- **Actual implementation:** Shared defaults set server.error.include-message: always and include-binding-errors: always with include-stacktrace: never.
- **Expected:** Generic error text for 5xx; detailed text only for 4xx client errors.
- **Trigger:** Any unhandled server-side exception (SQL, I/O, SDK) in a service that does not override the block.
- **Root cause:** Shared default tuned for developer ergonomics.
- **Consequences:** Internal messages (SQL fragments, hostnames, stack-derived detail) can reach end users.
- **Required remediation:** Default include-message to never and let the platform exception handler emit the curated ApiResponse message instead.
- **Regression test:** WebMvc test asserting a 500 response body contains no internal exception text.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-020 - Service-to-service client secret defaults to 'changeme' in order-service

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/resources/application.yml`
- **Actual implementation:** shop.services.keycloak.client-secret: ${ORDER_SERVICE_CLIENT_SECRET:changeme}. The value is used for the client_credentials token request that authenticates order-service to inventory, promotion and payment.
- **Expected:** No credential default in a shipped configuration; the service must fail fast when the secret is absent.
- **Trigger:** A deployment that does not set ORDER_SERVICE_CLIENT_SECRET.
- **Root cause:** Developer placeholder left in the shipped default.
- **Consequences:** Either the service fails to authenticate (availability) or, if the Keycloak client is created with the same 'changeme' secret, any party that knows the literal can mint SERVICE-role tokens for order-service and call the confirm, ship, deliver, cancel and verify-purchase endpoints directly.
- **Required remediation:** Remove the default and validate at startup; rotate the realm client secret.
- **Regression test:** Configuration test asserting the shipped default is absent and the context fails to start without the secret.
- **Remediation applied:** order-service: shop.services.keycloak.client-secret no longer defaults to the literal 'changeme'; the default is empty, which makes the client_credentials exchange fail closed instead of succeeding with a well-known secret.
- **Verification evidence:** Full reactor 3324/0/0 with the credential default removed.
### F-024 - Webhook signature verifier returns HTTP 500 (not 401) for a malformed 64-character v1 hex value

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** payment-service
- **Source files:**
  - `payment-service/src/main/java/com/shop/paymentservice/webhook/WebhookSignatureVerifier.java`
- **Actual implementation:** In the Stripe-style branch, parseStripeHeader() only validates that v1 has length 64, then verifyInternal() calls HexFormat.of().parseHex(stripe.v1Hex) with no try/catch. HexFormat.parseHex throws IllegalArgumentException for a non-hexadecimal string of the correct length. The legacy bare-hex branch DOES wrap parseHex in a try/catch and returns false.
- **Expected:** A malformed signature must be rejected as an invalid signature (401 PAY-5005), never as an unhandled server error.
- **Trigger:** An unauthenticated caller posts a webhook with a 64-character non-hex v1 component.
- **Root cause:** Inconsistent error handling between the two verification branches.
- **Consequences:** An unauthenticated request can provoke a 500 with a stack trace instead of a clean 401. It pollutes error budgets and alerting, and the error path is reachable without authentication.
- **Required remediation:** Validate the hex charset in parseStripeHeader (or wrap the parseHex call) and return false so the caller raises WEBHOOK_SIGNATURE_INVALID.
- **Regression test:** Unit test: verify() with a 64-character non-hex v1 must return false rather than throw.
- **Remediation applied:** WebhookSignatureVerifier now catches IllegalArgumentException from HexFormat.parseHex in the Stripe-style branch, so a 64-character non-hex v1 value is rejected as an invalid signature (401) instead of escaping as a 500 on an unauthenticated endpoint.
- **Verification evidence:** payment-service green (36 tests) with the malformed-hex signature now rejected instead of throwing.
### F-028 - mergeCart lets any authenticated user claim any guest cart by guessing its id

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** order-service
- **Source files:**
  - `order-service/src/main/java/com/shop/orderservice/controller/CartController.java`
- **Actual implementation:** POST /api/v1/carts/merge is authenticated but takes guestCartId as a free query parameter and derives the guest user id as UUID.nameUUIDFromBytes("guest:" + guestCartId). There is no check that the guest cart was created by the caller.
- **Expected:** A guest cart may only be merged by a caller who proves possession of the guest cart id, or the merge must be scoped to carts that were created for that browser session.
- **Trigger:** An authenticated user submits another user's guestCartId to /api/v1/carts/merge.
- **Root cause:** The guest cart id is treated as a public identifier rather than a capability token.
- **Consequences:** A guest cart's contents can be claimed by an unrelated account. Impact is limited because a guest cart holds only product selections, but it breaks the guest-to-account handoff contract and can pollute another user's cart.
- **Required remediation:** Treat the guest cart id as a capability: require it to be presented in a header that matches the cart's creation record, and/or rate-limit and audit merge attempts.
- **Regression test:** Integration test: merging a guest cart id that the caller did not create must be rejected.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-031 - Client-IP trust model is not fail-safe: with the shipped trusted-proxy-hops=0 the admin IP allowlist and the anonymous per-IP rate limit are bypassable via a spoofed X-Forwarded-For, and the WebFlux tests override the two decisive properties

- **Priority / severity:** P1 / High (confidence: RUNTIME_ADJACENT, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/resources/application.yml`
  - `gateway-service/src/main/java/com/shop/gateway/filter/ClientIpResolver.java`
  - `gateway-service/src/main/java/com/shop/gateway/ratelimit/RateLimitKeyResolver.java`
  - `gateway-service/src/test/java/com/shop/gateway/WebFluxIpAllowlistTests.java`
  - `utils/common-spring/src/main/resources/application.yml`
- **Actual implementation:** gateway-service/application.yml line 4 sets server.forward-headers-strategy: framework. In Spring Boot 4.1.1, ReactiveWebServerConfiguration.forwardedHeaderTransformer() is registered with @ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework"), so the ForwardedHeaderTransformer IS active in this WebFlux gateway; it rewrites the request remote address from the client-supplied X-Forwarded-For, taking the LEFTMOST entry. ClientIpResolver and RateLimitKeyResolver only use XForwardedRemoteAddressResolver when trustedProxyHops > 0; with the shipped default of 0 they fall back to exchange.getRequest().getRemoteAddress(), which is now the attacker-controlled header value. WebFluxIpAllowlistTests overrides BOTH decisive properties (server.forward-headers-strategy=NONE and gateway.rate-limit.trusted-proxy-hops=1) so the suite never exercises the shipped configuration. docker-compose.prod.yml defaults hops to 1; docker-compose.yml and .env.example use 0.
- **Expected:** The client IP used for the admin allowlist and for the anonymous rate-limit bucket must come from the trusted end of the X-Forwarded-For chain, and the shipped default must be the safe one. The test suite must exercise the production configuration.
- **Trigger:** Any deployment that runs with trusted-proxy-hops=0 (the shipped .env.example value and the docker-compose.yml default) and receives a request with an attacker-chosen X-Forwarded-For header.
- **Root cause:** The 'prevent forwarded IP spoofing' fix (commit 90cea14, H28) is only effective when trusted-proxy-hops >= 1. The code and the tests both assume that value, but the shipped default is 0, and no startup validation ties the two settings together.
- **Consequences:** With hops=0, an unauthenticated caller sets X-Forwarded-For: <an address inside the allowlist CIDR> and the admin IP allowlist grants access. The same header defeats the anonymous 100 rps / 200 burst per-IP bucket, because each request lands in a fresh ip:<spoofed> bucket. When the gateway sits directly behind a load balancer with hops=0, the opposite failure also occurs: every anonymous client shares the proxy's single bucket, so one client can exhaust the limit for all of them.
- **Required remediation:** Make the safe posture the default and validate it at startup: set server.forward-headers-strategy to NONE in the gateway so the gateway resolves the client IP itself, set the default trusted-proxy-hops to 1, and fail startup when the strategy is 'framework' while trusted-proxy-hops is 0. Remove the property overrides from WebFluxIpAllowlistTests so the suite runs against the production configuration, and add a regression test asserting that a spoofed leftmost X-Forwarded-For does not grant allowlist access.
- **Regression test:** WebFlux integration test with the production properties: a request with X-Forwarded-For set to an allowlisted address must still be rejected; a request with hops=0 configuration must fail the application startup.
- **Remediation applied:** gateway-service: server.forward-headers-strategy changed from 'framework' to 'none' so the gateway resolves the client IP itself from the raw X-Forwarded-For chain; gateway.rate-limit.trusted-proxy-hops now defaults to 1 (was 0). A new GatewaySecurityPostureValidator refuses to start when trusted-proxy-hops is 0 while a forwarded-header strategy other than 'none' is active.
- **Verification evidence:** gateway-service green; GatewaySecurityPostureValidatorTest (5 cases) covers the wildcard-CORS and zero-hop rejections; the gateway now resolves the client IP itself with forward-headers-strategy=none and trusted-proxy-hops=1.
### F-032 - The gateway performs no JWT audience validation - a token minted for any client in the realm is accepted at the edge

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java`
  - `gateway-service/src/main/resources/application.yml`
  - `gateway-service/pom.xml`
- **Actual implementation:** The gateway depends only on common-core, not common-security, so common-security's BaseSecurityConfig (issuer + timestamp + audience validator chain) never applies - SecurityAutoConfiguration is @ConditionalOnWebApplication(type = SERVLET) and the gateway is reactive. SecurityConfig builds a ServerHttpSecurity chain with oauth2ResourceServer(jwt(Customizer.withDefaults())) and the only JWT configuration is spring.security.oauth2.resourceserver.jwt.issuer-uri. Spring Boot's reactive auto-configuration therefore builds a NimbusReactiveJwtDecoder whose validator is JwtValidators.createDefaultWithIssuer(...) - issuer and timestamp only. There is no JwtDecoder bean, no audience validator and no JwtRolesConverter in the gateway source.
- **Expected:** The edge must reject a token whose audience does not include the client the gateway serves, and it must map Keycloak realm roles into authorities so route-level role rules can be expressed.
- **Trigger:** Any valid realm token, including one issued for a low-privilege or unrelated client, presented to the gateway.
- **Root cause:** The audience validator added to common-security (the 'C1 fix') is implemented in a servlet-only auto-configuration, so the reactive edge - the one place where the check matters most - has no equivalent.
- **Consequences:** Horizontal privilege escalation at the edge: a token issued to a different client is honoured by the gateway, and the gateway's public-endpoint list is the only remaining boundary. Roles are not mapped to authorities either, so any future hasRole() route rule in the gateway would behave unexpectedly.
- **Required remediation:** Add a reactive JWT decoder to the gateway that applies the same validator chain as common-security (issuer, timestamp, and audience when configured), and map realm_access.roles to authorities. Extract the validator chain into common-security so both stacks share one implementation.
- **Regression test:** WebFlux integration test: a token with aud=[other-client] must be rejected with 401 by the gateway.
- **Remediation applied:** gateway-service: new GatewayJwtSecurityConfig declares a ReactiveJwtDecoder with the same validator chain the servlet stack uses (issuer + timestamp + azp), so the edge now rejects a token minted for a client it does not accept. Previously the edge used Boot's reactive auto-configuration (issuer + timestamp only).
- **Verification evidence:** gateway-service green; GatewayJwtSecurityConfigTest (3 cases) including issuer mismatch; the reactive decoder is now lazy so the edge boots without the IdP being reachable.
### F-033 - Gateway CORS combines allowCredentials=true with the shipped wildcard origin pattern, enabling cross-origin credentialed data theft

- **Priority / severity:** P1 / High (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/java/com/shop/gateway/config/SecurityConfig.java`
  - `gateway-service/src/main/resources/application.yml`
  - `.env.example`
- **Actual implementation:** SecurityConfig.corsWebFilter() sets config.setAllowCredentials(true) and config.setAllowedOriginPatterns(properties.corsAllowedOriginPatterns()). The application.yml requires the property with the placeholder ${CORS_ALLOWED_ORIGIN_PATTERNS:?...}, but .env.example line 157 ships CORS_ALLOWED_ORIGIN_PATTERNS=*. With allowedOriginPatterns containing '*' and allowCredentials=true, Spring echoes the request Origin instead of sending '*' and adds Access-Control-Allow-Credentials: true, so any website can issue credentialed cross-origin requests to the gateway and read the responses. The fail-fast placeholder only covers the case where the variable is absent; the shipped example supplies the wildcard value, so it never triggers.
- **Expected:** allowCredentials=true must only ever be combined with an explicit origin allow-list. A wildcard origin must not be shippable with credentials enabled.
- **Trigger:** A deployment that copies .env.example (the shipped default) into the environment.
- **Root cause:** The configuration guard validates presence, not value; the credentialed-CORS hazard is a property of the combination, not of the variable alone.
- **Consequences:** Any web page can read authenticated responses from the gateway on behalf of a logged-in user - classic cross-origin data theft. The gateway is the only component that sets allowCredentials(true); the shared servlet stack defaults it to false.
- **Required remediation:** Validate the combination at startup: refuse to boot when allowCredentials is true and the resolved origin patterns contain '*'. Ship an explicit localhost allow-list in .env.example instead of '*'.
- **Regression test:** Configuration test: starting the gateway with CORS_ALLOWED_ORIGIN_PATTERNS='*' must fail with a clear error.
- **Remediation applied:** gateway-service: GatewaySecurityPostureValidator refuses to start when gateway.cors-allowed-origin-patterns contains '*' while the gateway's CORS configuration enables allowCredentials(true). The shipped .env.example wildcard remains a configuration mistake that now fails the deployment instead of silently enabling cross-origin data theft.
- **Verification evidence:** gateway-service green; GatewaySecurityPostureValidatorTest.wildcardCorsOriginIsRejected asserts the startup refusal.
### F-035 - The backoffice admin IP allowlist is INACTIVE by default in every shipped configuration

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistFilter.java`
  - `docker-compose.prod.yml`
  - `.env.prod.example`
- **Actual implementation:** AdminIpAllowlistFilter.filter() returns chain.filter(exchange) immediately when properties.active() is false. docker-compose.prod.yml ships ADMIN_IP_ALLOWLIST: ${ADMIN_IP_ALLOWLIST:-} - an empty value, which is INACTIVE. .env.prod.example does not define ADMIN_IP_ALLOWLIST. The inline comment acknowledges that 'env ABSENT or EMPTY STRING = filter INACTIVE (allow-all)'.
- **Expected:** A network-layer control in front of the backoffice should fail closed in production, or its absence should block startup.
- **Trigger:** A production deployment that does not explicitly populate ADMIN_IP_ALLOWLIST.
- **Root cause:** The control is opt-in with an allow-all default, and the default is what the production compose file ships.
- **Consequences:** The backoffice (products, categories, brands, promotions, tax, notifications, payments, ratings, shipments, search) is reachable from any network origin; only the JWT role check stands between an attacker and administrative functions. The compose comment documents the hazard but nothing enforces it.
- **Required remediation:** Make the allowlist mandatory in the prod profile (fail startup when absent or empty) or add a startup guard that logs a prominent warning and exposes a health indicator so the gap is visible in monitoring.
- **Regression test:** Configuration test: the prod profile must fail to start without ADMIN_IP_ALLOWLIST.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-040 - The provisioned Keycloak realm issues tokens with NO 'aud' claim - the audience validator is unsatisfiable, and the platform had no client-identity check at all

- **Priority / severity:** P1 / High (confidence: RUNTIME_MEASURED, evidence: RUNTIME_MEASURED)
- **Status:** VERIFIED | verification: VERIFIED
- **Affected modules:** utils/common-security, gateway-service, auth-service, docker/keycloak
- **Source files:**
  - `docker/keycloak/import/ecommerce-realm.json`
  - `utils/common-security/src/main/java/com/shop/common/security/config/BaseSecurityConfig.java`
  - `utils/common-security/src/main/java/com/shop/common/security/config/SecurityProperties.java`
- **Actual implementation:** Measured against the provisioned realm on Keycloak 26.0 (realm created from docker/keycloak/import/ecommerce-realm.json): a USER token (ROPC, client ecommerce-client) carries aud=ABSENT, azp=ecommerce-client, realm_access.roles=[USER]; an ADMIN token carries aud=ABSENT, azp=ecommerce-client, realm_access.roles=[ADMIN,MANAGER]; a service token (client_credentials, client order-service) carries aud=ABSENT, azp=order-service, realm_access.roles=[SERVICE]. The realm declares no clientScopes and no protocolMappers, so no audience mapper exists. The fleet therefore had NO audience and NO client-identity control: any token minted by any client in the realm was accepted by every service and by the gateway. The 'aud' validator already present in BaseSecurityConfig was unsatisfiable - enabling shop.security.expected-audiences would have rejected 100% of the tokens and taken the whole fleet offline.
- **Expected:** Each service accepts tokens only from the clients it is meant to serve, and the mechanism must work against the realm as provisioned.
- **Trigger:** Any token issued by the realm reaches a service or the gateway.
- **Root cause:** The 'C1 fix' assumed an 'aud' claim that this realm never emits, and the property guarding it was empty everywhere, so the control was inert and its failure mode was hidden.
- **Consequences:** No client-to-service separation existed. The only discriminator was the realm role, and the SERVICE role is fleet-wide (see F-041). Additionally, the shipped 'aud' validator was a latent outage landmine: the first operator who followed the documentation and set expected-audiences would have locked every user out.
- **Required remediation:** Validate the 'azp' claim (the only client discriminator the realm emits) through a new shop.security.expected-client-ids property, wire the same validator into the reactive gateway edge, and document that expected-audiences must stay empty until an audience mapper is added to the realm.
- **Regression test:** BaseSecurityConfigClientIdValidationTest and GatewayJwtSecurityConfigTest assert that a token from an unknown client is rejected and one from an accepted client passes.
- **Remediation applied:** common-security: new expectedClientIds property + azp validator; gateway: new GatewayJwtSecurityConfig with a ReactiveJwtDecoder and an azp validator; common-spring: platform default client-id allow-list.
- **Verification evidence:** Keycloak 26.0 token inspection (aud absent, azp present for user/admin/service tokens) plus 7 new unit tests across common-security and gateway-service; full reactor green.
### F-041 - The SERVICE realm role is a fleet-wide super-privilege: any service client token can call any service's SERVICE-gated endpoint, including capturing or refunding any payment

- **Priority / severity:** P1 / High (confidence: RUNTIME_MEASURED, evidence: RUNTIME_MEASURED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** payment-service, order-service, inventory-service, shipping-service, product-service
- **Source files:**
  - `payment-service/src/main/java/com/shop/paymentservice/controller/PaymentController.java`
  - `payment-service/src/main/java/com/shop/paymentservice/service/impls/PaymentServiceImpl.java`
  - `order-service/src/main/java/com/shop/orderservice/controller/OrderStatusController.java`
  - `docker/keycloak/import/ecommerce-realm.json`
- **Actual implementation:** Every service client in the realm (order-service, rating-service, search-service, product-service, media-service) is granted the single realm role SERVICE. A client_credentials token for order-service carries realm_access.roles=[SERVICE] and is accepted by every @PreAuthorize("hasAnyRole('SERVICE','ADMIN')") endpoint in the fleet - for example PaymentController.capture and PaymentController.refund, which take a payment id and have no ownership or caller check, and OrderStatusController's confirm/ship/deliver/cancel. Because the realm emits no audience (F-040), a token cannot be bound to the service it was issued to.
- **Expected:** A service token should only be able to invoke the specific operations its owner service needs, and a compromised service secret must not grant fleet-wide administrative power.
- **Trigger:** Compromise of any single service client secret, or a service making a call outside its remit.
- **Root cause:** A single coarse SERVICE role is used as the only machine-identity discriminator, with no per-caller binding.
- **Consequences:** One leaked service secret (for example order-service's, which the audit found defaulted to the literal 'changeme') grants the ability to capture or refund any payment in the platform, commit any inventory reservation and cancel any order.
- **Required remediation:** Introduce a per-service role or validate the 'azp' claim per operation so a token is bound to its issuing service, and add an ownership check to the money endpoints. Tracked as follow-up task TSK-041.
- **Regression test:** Required: an integration test asserting that an order-service token cannot call a payment capture for a payment it does not own.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-056 - The local role catalogue (USER, PM, ADMIN) does not match the provisioned Keycloak realm roles (ADMIN, USER, MANAGER, SERVICE)

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/constant/RoleName.java`
  - `docker/keycloak/import/ecommerce-realm.json`
  - `auth-service/src/main/java/com/shop/authservice/mapper/RoleMapper.java`
- **Actual implementation:** RoleName declares USER, PM and ADMIN, while the Keycloak realm ships ADMIN, USER, MANAGER and SERVICE. RoleMapper.toRoleName maps an unknown name to BusinessException.badRequest("auth.unsupported.role"), so the mismatch is handled cleanly at the API boundary - assigning MANAGER or SERVICE through /api/v1/roles returns a 400 with a clear code rather than an unmapped exception. The local catalogue is nevertheless a subset with one invented role (PM) that does not exist in the realm.
- **Expected:** The local role catalogue and the realm's realm roles stay in step.
- **Trigger:** An operator tries to grant MANAGER or SERVICE through the admin role API.
- **Root cause:** The catalogue was written independently of the realm import.
- **Consequences:** The SERVICE role - which every service-to-service call depends on - cannot be managed through the auth-service admin API; MANAGER cannot be granted either. The mismatch is fail-closed (400) rather than dangerous.
- **Required remediation:** Align RoleName with the realm (add MANAGER and SERVICE, or drop PM), or generate the catalogue from the realm at startup.
- **Regression test:** Contract test asserting that every RoleName constant exists as a realm role in docker/keycloak/import/ecommerce-realm.json.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-057 - forgotPassword logs the email address of a non-existent account

- **Priority / severity:** P3 / Low (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** auth-service
- **Source files:**
  - `auth-service/src/main/java/com/shop/authservice/service/impls/UserServiceImpl.java`
- **Actual implementation:** forgotPassword() returns the same generic message for every input (no user enumeration through the API - that part is correct), but it logs 'Password reset requested for non-existent email: {}' with the submitted address, while the existing-account branch logs the username. The log therefore becomes a list of addresses that do not belong to the platform.
- **Expected:** Logs do not accumulate third-party email addresses. A single indistinguishable log line per request is enough to drive an operational alert.
- **Trigger:** Any forgot-password request for an address that is not registered.
- **Root cause:** The two branches log at different granularity.
- **Consequences:** The log stream becomes a soft user-enumeration oracle for anyone with log access, and it accumulates personal data about unrelated people.
- **Required remediation:** Log one indistinguishable line per request (optionally with a hash of the address) and raise a counter for the not-found case so an enumeration attempt is still visible to monitoring.
- **Regression test:** Unit test: the two branches produce the same log shape (no raw address for the not-found case).
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
### F-069 - The admin IP allowlist uses a prefix match without a path boundary, so a path that merely starts with a public prefix bypasses it

- **Priority / severity:** P2 / Medium (confidence: SOURCE_VERIFIED, evidence: SOURCE_VERIFIED)
- **Status:** OPEN | verification: NOT_VERIFIED
- **Affected modules:** gateway-service
- **Source files:**
  - `gateway-service/src/main/java/com/shop/gateway/filter/AdminIpAllowlistFilter.java`
- **Actual implementation:** isPublicStorefront() tests path.startsWith("/api/v1/products"), ("/api/v1/categories"), ("/api/v1/brands"), ("/api/v1/search"), ("/api/v1/ratings"), ("/api/v1/medias") and ("/api/v1/auth") with no trailing separator, and the webhooks bypass uses startsWith("/api/v1/webhooks/"). A request for a path such as /api/v1/ratings-admin or /api/v1/auth-internal therefore bypasses the allowlist even though it is not a public storefront route.
- **Expected:** The bypass must match a path segment boundary, exactly as the scope matching in RateLimitFilter already does (matchesPrefix compares against prefix or prefix + "/").
- **Trigger:** A request to a path whose name merely starts with a public prefix.
- **Root cause:** A raw startsWith is used where the sibling filter already implements a segment-aware match.
- **Consequences:** Any route whose path begins with a public prefix is reachable from outside the allowlist. Today no such route exists, so the impact is latent - but it is exactly the kind of prefix confusion that becomes an allowlist bypass when a route is added.
- **Required remediation:** Reuse the segment-aware prefix match that RateLimitFilter already implements.
- **Regression test:** Unit test: a path such as /api/v1/ratings-admin must NOT be treated as a public storefront path.
- **Remediation applied:** (none yet)
- **Verification evidence:** (none yet)
