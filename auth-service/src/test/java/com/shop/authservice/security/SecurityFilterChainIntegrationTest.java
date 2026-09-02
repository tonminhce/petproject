package com.shop.authservice.security;

import com.shop.authservice.dto.response.TokenResponse;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.service.AuthService;
import com.shop.authservice.service.RoleService;
import com.shop.authservice.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for the real Spring Security filter chain wired by
 * {@code common-security} (see {@code SecurityAutoConfiguration} and
 * {@code BaseSecurityConfig}). Unlike {@code AuthControllerTest} /
 * {@code UserControllerTest} / {@code RoleControllerTest}, which use
 * {@code @WebMvcTest} with {@code @AutoConfigureMockMvc(addFilters = false)},
 * this test exercises the actual filter chain end-to-end against
 * {@link MockMvc}.
 *
 * <h3>What this test covers</h3>
 * <ol>
 *   <li>Platform-default public paths ({@code /actuator/health}) bypass auth.</li>
 *   <li>Service-specific public paths ({@code /api/v1/auth/**}) bypass auth
 *       for any HTTP method (POST login / sign-up reach the controller).</li>
 *   <li>Protected paths ({@code /api/v1/users/me}) require authentication —
 *       anonymous access returns 401, valid JWT (via
 *       {@code SecurityMockMvcRequestPostProcessors.jwt()}) returns 200.</li>
 *   <li>Method-scoped authorization: POST to a path that has no controller
 *       handler is not silently permitted; the chain still rejects anonymous
 *       callers before the dispatcher resolves a handler.</li>
 * </ol>
 *
 * <h3>Why exclude JPA / Liquibase autoconfiguration</h3>
 * <p>{@code UserService} and {@code AuthService} are mocked with
 * {@link MockitoBean}, so their JPA / Keycloak dependencies are never
 * resolved. The remaining {@code @Service} / {@code @Component} beans
 * (exception handler, mappers, etc.) still load, so the security
 * filter chain and controller layer are wired exactly as in production.
 * DataSource / Hibernate / Liquibase autoconfigurations are excluded to
 * keep the test self-contained without a running PostgreSQL or
 * pre-applied schema.</p>
 *
 * <h3>Why mock {@link JwtDecoder}</h3>
 * <p>{@code BaseSecurityConfig#jwtDecoder()} builds a
 * {@code NimbusJwtDecoder.withIssuerLocation(...)} which performs a network
 * call to the Keycloak realm's JWK set at context-startup time. Replacing
 * it with a mock keeps the test hermetic. {@code with(jwt())} injects a
 * pre-built {@code JwtAuthenticationToken} into the
 * {@code SecurityContext}, so the resource-server filter accepts the
 * request without ever calling the decoder.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "shop.security.issuer-uri=http://localhost:9999/realms/test",
                // No datasource → no EntityManagerFactory; common-spring's
                // JpaAuditingAutoConfiguration would otherwise register an eager
                // jpaMappingContext that fails with "JPA metamodel must not be
                // empty". Same opt-out contract as common-spring's
                // CommonLibraryStarterTests (fleet-hardening T4 carry).
                "shop.jpa.auditing.enabled=false",
                // Strip JPA / datasource / liquibase autoconfigs so the
                // test does not require a running PostgreSQL. The
                // mocked services (@MockitoBean UserService etc.)
                // replace every bean that would otherwise try to
                // connect to the database.
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,"
                        + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                        + "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.config.import=classpath:security-filter-chain-fixture.yml")
class SecurityFilterChainIntegrationTest {

    private static final String SIGN_UP_BODY = """
            {
              "fullName": "Alice Wonder",
              "username": "alice123",
              "password": "Passw0rd",
              "email": "alice@example.com",
              "gender": "female",
              "phone": "0901234567"
            }
            """;

    private static final String LOGIN_BODY = """
            {
              "username": "alice",
              "password": "Passw0rd"
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RoleService roleService;

    @MockitoBean
    private UserMapper userMapper;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("S1: GET /actuator/health without auth → 200 (platform default public path)")
    void actuatorHealthIsPublicWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S2: POST /api/v1/auth/login without auth → 200 (service public-path, any method)")
    void loginIsPublicWithoutAuthentication() throws Exception {
        when(authService.login(anyString(), anyString()))
                .thenReturn(TokenResponse.builder()
                        .accessToken("at")
                        .refreshToken("rt")
                        .tokenType("Bearer")
                        .expiresIn(300L)
                        .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S3: POST /api/v1/auth/sign-up without auth → 200 (service public-path, any method)")
    void signUpIsPublicWithoutAuthentication() throws Exception {
        when(userService.register(any())).thenReturn(User.builder().username("alice123").build());

        mockMvc.perform(post("/api/v1/auth/sign-up")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_UP_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S4: DELETE /api/v1/users/me without auth → 401 (anyRequest().authenticated() — method-scoped rule does not match DELETE)")
    void deleteCurrentUserWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S5: GET /api/v1/users/me with valid JWT (sub=alice) → 200 (chain authenticates)")
    void getCurrentUserWithJwtIsOk() throws Exception {
        UUID id = UUID.randomUUID();
        User user = User.builder().id(id).username("alice").build();
        UserResponse response = UserResponse.builder().id(id).username("alice").build();

        when(userService.findByUsername("alice")).thenReturn(user);
        when(userMapper.toResponse(user)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject("alice"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S6: POST /api/v1/users/me without auth → 401 (no public-paths match for any method)")
    void postToProtectedPathWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S7: POST /api/v1/users/me without auth → 401 (POST does not match the GET-scoped EndpointRule → falls through to anyRequest().authenticated())")
    void postToMethodScopedRuleWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Method-scoped EndpointRule (GET-only) matches the request, so the filter
     * chain permits it. {@code UserController.getCurrentUser} has
     * {@code @PreAuthorize("isAuthenticated()")} which then rejects the anonymous
     * caller with {@code AuthorizationDeniedException} → 403. Contrast with S4
     * (DELETE → 401): if the rule had NOT matched the GET, both S4 and S8
     * would return 401, so the 401/403 split is the proof the
     * {@code if (rule.method() != null)} branch in {@code BaseSecurityConfig}
     * was exercised.
     */
    @Test
    @DisplayName("S8: GET /api/v1/users/me without auth → 403")
    void getCurrentUserMatchesMethodScopedPublicRuleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isForbidden());
    }
}
