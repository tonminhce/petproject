package com.shop.authservice.security;

import com.shop.authservice.dto.response.TokenResponse;
import com.shop.authservice.dto.response.UserResponse;
import com.shop.authservice.entity.User;
import com.shop.authservice.mapper.UserMapper;
import com.shop.authservice.service.AddressService;
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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "shop.security.issuer-uri=http://localhost:9999/realms/test",
                "shop.jpa.auditing.enabled=false",
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

    @MockitoBean
    private AddressService addressService;

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
                .thenReturn(new TokenResponse("at", "rt", "Bearer", 300L));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOGIN_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S3: POST /api/v1/auth/sign-up without auth → 200 (service public-path, any method)")
    void signUpIsPublicWithoutAuthentication() throws Exception {
        when(userService.register(any()))
                .thenReturn(new UserResponse(UUID.randomUUID(), null, "alice123", null, null, null, null));

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
        UserResponse response = new UserResponse(id, null, "alice", null, null, null, null);

        when(userService.findByUsername("alice")).thenReturn(response);

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

    @Test
    @DisplayName("S8: GET /api/v1/users/me without auth → 403")
    void getCurrentUserMatchesMethodScopedPublicRuleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isForbidden());
    }
}
